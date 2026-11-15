package com.team.travelmap.ui

import com.team.travelmap.core.model.*
import com.team.travelmap.core.trip.TripLegs
import com.team.travelmap.curation.weather.*
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CoursePlanningSupportTest {
    private val departure = LocalDateTime.of(2026,11,15,9,0)
    private fun card(id: String, theme: TravelTheme) = ThemePlaceCard(
        contentId=id, place=Place("장소$id",GeoPoint(36.3,127.3),PlaceCategory.FOOD,RegionId("30170")),
        themes=setOf(theme),imageUrl="사진",thumbnailUrl="",imageLicense="Type1",introduction="소개",
        operatingInfo=emptyMap(),themeReasons=mapOf(theme to "테스트"),source="테스트",
        collectedAt="테스트",detailCollectedAt="테스트",
    )
    private val cards get() = listOf(card("1",TravelTheme.FOOD),card("2",TravelTheme.FOOD),
        card("3",TravelTheme.CAFE),card("4",TravelTheme.CAFE))
    private val open = CourseVisitAvailabilityProvider { _,_,_ -> CourseVisitDecision(CourseVisitState.AVAILABLE,"등록 시간") }

    @Test fun `화면 입력 경로가 출발 시각과 운영시간 검사를 전달하여 코스를 만든다`() {
        val checked = mutableListOf<LocalDateTime>()
        val result = planDemoCourses(cards,TravelTheme.FOOD,TripLength.HALF_DAY,"30",departure,
            visits=CourseVisitAvailabilityProvider { _,arrival,stay ->
                checked += arrival
                assertTrue(stay > 0)
                CourseVisitDecision(CourseVisitState.AVAILABLE,"등록 시간")
            },isDemoReady={true})
        assertTrue(result.courses.isNotEmpty())
        assertTrue(checked.isNotEmpty())
        assertTrue(checked.all { it >= departure && it.toLocalDate() == departure.toLocalDate() })
        assertFalse(CourseWarning.MISSING_DEPARTURE_TIME in result.warnings)
        assertFalse(CourseWarning.MISSING_HOURS_PROVIDER in result.warnings)
        assertTrue(result.courses.all { course -> course.stops.all { it.arrivalAt != null } })
    }

    @Test fun `데모 미확인 장소만 추천에서 걸러내고 원본 카드 목록은 유지한다`() {
        val original = cards
        val result = planDemoCourses(original,TravelTheme.FOOD,TripLength.HALF_DAY,"30",departure,open,{ it != "1" })
        assertTrue(result.courses.isNotEmpty())
        assertTrue(result.courses.all { course -> course.stops.none { it.card.contentId == "1" } })
        assertEquals(4,original.size)
    }

    @Test fun `운영시간이 미확인인 유지 장소를 조용히 해제하지 않는다`() {
        val result = planDemoCourses(cards,TravelTheme.FOOD,TripLength.HALF_DAY,"30",departure,open,
            { it != "1" },locked=setOf("1"))
        assertTrue(result.courses.isEmpty())
        assertEquals(setOf("1"),result.unavailableLockedIds)
        assertTrue(CourseWarning.LOCKED_PLACE_UNAVAILABLE in result.warnings)
    }

    @Test fun `시간 검수 때문에 후보가 없으면 장소 데이터 부족과 구분한다`() {
        val result = planDemoCourses(cards,TravelTheme.FOOD,TripLength.HALF_DAY,"30",departure,open,{false})
        assertTrue(result.courses.isEmpty())
        assertTrue(CourseWarning.OPERATING_HOURS_NOT_VERIFIED in result.warnings)
    }

    @Test fun `화면에서 받은 다섯 경유지를 모두 순차 안내에 전달한다`() {
        val stops = (1..6).map { CourseStop(card(it.toString(),TravelTheme.FOOD),20) }
        val course = ThemeCourse(stops,30.0,120,10.0,6.0,true)
        val plan = course.toTripPlan()!!
        assertEquals(stops.last().card.place,plan.destination)
        assertEquals(stops.dropLast(1).map { it.card.place },plan.waypoints)
        var legs = TripLegs(plan.destination,plan.waypoints)
        for (place in plan.waypoints) {
            assertEquals(listOf(place),legs.naviWaypoints())
            legs = legs.onWaypointArrived().onGear(Gear.PARK).first
            val (next, changed) = legs.onGear(Gear.DRIVE)
            assertTrue(changed)
            legs = next
            assertEquals(plan.destination,legs.destination)
        }
        assertTrue(legs.naviWaypoints().isEmpty())
    }

    @Test fun `빈 코스로 안내를 시작하지 않는다`() {
        assertNull(ThemeCourse(emptyList(),0.0,0,0.0,0.0,false).toTripPlan())
    }

    private fun twoRoutes(): ThemeCourse {
        val fastStops = listOf(
            CourseStop(card("1",TravelTheme.FOOD),20,departure.plusMinutes(10)),
            CourseStop(card("2",TravelTheme.CAFE),30,departure.plusMinutes(40)),
        )
        val shortStops = listOf(
            CourseStop(fastStops[1].card,30,departure.plusMinutes(20)),
            CourseStop(fastStops[0].card,20,departure.plusMinutes(65)),
        )
        return ThemeCourse(fastStops,20.0,50,12.0,4.0,true,listOf(
            CourseRouteOption(setOf(CourseRouteCriterion.FASTEST),fastStops,20.0,12.0,true),
            CourseRouteOption(setOf(CourseRouteCriterion.SHORTEST_DISTANCE),shortStops,35.0,8.0,true),
        ))
    }

    @Test fun `거리 동선의 방문 시각 비용과 순서를 날씨 및 내비 입력에 그대로 넘긴다`() {
        val original = twoRoutes()
        val input = CourseRecommendations(listOf(original),emptySet())
        val selected = input.forRoute(CourseRouteCriterion.SHORTEST_DISTANCE).courses.single()
        assertEquals(original.stops.map { it.card.contentId }.toSet(),selected.stops.map { it.card.contentId }.toSet())
        assertEquals(listOf("2","1"),selected.stops.map { it.card.contentId })
        assertEquals(listOf(departure.plusMinutes(20),departure.plusMinutes(65)),selected.stops.map { it.arrivalAt })
        assertEquals(85.0,selected.totalMinutes,0.0)
        assertEquals(35.0,selected.travelMinutes,0.0)
        assertEquals(8.0,selected.distanceKm,0.0)
        val plan = selected.toTripPlan()!!
        assertEquals(listOf(original.stops[1].card.place),plan.waypoints)
        assertEquals(original.stops[0].card.place,plan.destination)
        assertEquals(listOf("1","2"),input.courses.single().stops.map { it.card.contentId })
        val fastest = input.forRoute(CourseRouteCriterion.FASTEST).courses.single()
        assertEquals(original.stops,fastest.stops)
        assertEquals(70.0,fastest.totalMinutes,0.0)
    }

    @Test fun `선택 기준이 없는 코스는 제외하고 원래 경고 및 검수 정보를 유지한다`() {
        val full = twoRoutes()
        val fastOnly = full.copy(routeOptions=full.routeOptions.filter { CourseRouteCriterion.FASTEST in it.criteria })
        val rejection = CourseVisitRejection("제외",departure,CourseVisitDecision(CourseVisitState.UNAVAILABLE,"휴무"))
        val input = CourseRecommendations(listOf(fastOnly),setOf(CourseWarning.LOCKED_PLACE_UNAVAILABLE),
            unavailableLockedIds=setOf("남긴장소"),searchTruncated=true,rejectedVisits=listOf(rejection))
        val result = input.forRoute(CourseRouteCriterion.SHORTEST_DISTANCE)
        assertTrue(result.courses.isEmpty())
        assertEquals(setOf(CourseWarning.LOCKED_PLACE_UNAVAILABLE,CourseWarning.NOT_ENOUGH_COURSES),result.warnings)
        assertEquals(input.unavailableLockedIds,result.unavailableLockedIds)
        assertTrue(result.searchTruncated)
        assertEquals(listOf(rejection),result.rejectedVisits)
        assertEquals(1,input.courses.size)
    }

    @Test fun `선택 기준 코스가 세 개 이상이면 부족 경고를 새로 추가하지 않는다`() {
        val result = CourseRecommendations(List(3) { twoRoutes() },emptySet()).forRoute(CourseRouteCriterion.FASTEST)
        assertEquals(3,result.courses.size)
        assertFalse(CourseWarning.NOT_ENOUGH_COURSES in result.warnings)
    }

    @Test fun `거리 동선 선택 후 바뀐 도착 시각의 강수 예보를 적용하고 같은 순서로 안내한다`() = runTest {
        val original = twoRoutes()
        val indoor = original.stops[1].card.copy(weatherExposure=WeatherExposure.INDOOR,weatherEvidence="실내 좌석")
        val fast = original.routeOptions.first { CourseRouteCriterion.FASTEST in it.criteria }
            .let { option -> option.copy(stops=option.stops.map { stop ->
                if (stop.card.contentId == indoor.contentId) stop.copy(card=indoor) else stop
            }) }
        val short = CourseRouteOption(setOf(CourseRouteCriterion.SHORTEST_DISTANCE),listOf(
            CourseStop(indoor,30,departure.plusMinutes(80)),
            CourseStop(original.stops[0].card,20,departure.plusMinutes(125)),
        ),95.0,8.0,true)
        val base = CourseRecommendations(listOf(original.copy(stops=fast.stops,routeOptions=listOf(fast,short))),emptySet())
        val clock = Clock.fixed(Instant.parse("2026-11-15T00:15:00Z"),ZoneOffset.UTC)
        val snapshot = WeatherSnapshot(WeatherKind.FORECAST,
            KmaGrid.from(indoor.place.point),departure.withHour(8),clock.instant(),
            (9..12).map { hour -> WeatherFrame(departure.withHour(hour),mapOf("PTY" to if (hour < 11) "0" else "1")) })
        val weather = CourseWeatherRecommender(load={_,_,_->WeatherResult.Success(snapshot)},clock=clock)
        val fastResult = weather.apply(base.forRoute(CourseRouteCriterion.FASTEST),emptySet(),true)
        val shortResult = weather.apply(base.forRoute(CourseRouteCriterion.SHORTEST_DISTANCE),emptySet(),true)
        assertEquals(false,fastResult.courses.single().visits.single { it.contentId == indoor.contentId }.precipitationExpected)
        assertEquals(true,shortResult.courses.single().visits.single { it.contentId == indoor.contentId }.precipitationExpected)
        assertFalse(fastResult.courses.single().indoorPriority)
        assertTrue(shortResult.courses.single().indoorPriority)
        val selected = shortResult.recommendations.courses.single()
        assertEquals(short.stops,selected.stops)
        assertEquals(listOf(indoor.place),selected.toTripPlan()!!.waypoints)
        assertEquals(original.stops[0].card.place,selected.toTripPlan()!!.destination)
    }

    // ---- 테마 없이 추천(누구랑·얼마나·어디까지) ----
    private fun themed(id: String, vararg themes: TravelTheme, city: String = "30170", lat: Double = 36.3) = ThemePlaceCard(
        contentId=id, place=Place("장소$id",GeoPoint(lat,127.3),PlaceCategory.SIGHT,RegionId(city)),
        themes=themes.toSet(),imageUrl="",thumbnailUrl="",imageLicense="Type1",introduction="",
        operatingInfo=emptyMap(),themeReasons=emptyMap(),source="테스트",collectedAt="",detailCollectedAt="",
    )

    @Test fun `누구랑만 바꿔도 추천 테마 1위가 달라진다`() {
        val all = listOf(themed("1",TravelTheme.FOOD),themed("2",TravelTheme.FOOD),themed("3",TravelTheme.HEALING),
            themed("4",TravelTheme.CAFE))
        assertEquals(TravelTheme.FOOD,recommendThemes(all,Companion.FRIENDS,"30",departure,{true}).first().theme)
        assertEquals(TravelTheme.HEALING,recommendThemes(all,Companion.FAMILY,"30",departure,{true}).first().theme)
    }

    @Test fun `다른 도시 장소와 검수 안 된 장소는 추천 테마에 넣지 않는다`() {
        val all = listOf(themed("1",TravelTheme.CAFE,city="11110"),themed("2",TravelTheme.FOOD),themed("3",TravelTheme.HEALING))
        val ranking = recommendThemes(all,Companion.ALONE,"30",departure,{ it != "3" })
        assertEquals(listOf(TravelTheme.FOOD),ranking.map { it.theme })
    }

    @Test fun `흔들림이 있으면 같은 조건에도 다른 조합이 나올 수 있고 없으면 항상 같다`() {
        val all = (1..8).map { themed("$it",if (it % 2 == 0) TravelTheme.FOOD else TravelTheme.CAFE,lat=36.30+it*0.01) }
        fun ids(r: kotlin.random.Random?) = planDemoCourses(all,TravelTheme.FOOD,TripLength.HALF_DAY,"30",departure,open,{true},random=r)
            .courses.map { c -> c.stops.map { it.card.contentId } }
        assertEquals(ids(null),ids(null))
        val variants = (1..20).map { ids(kotlin.random.Random(it)) }.toSet()
        assertTrue(variants.size > 1)
    }

    @Test fun `반나절은 카페가 3곳 미만이면 카페 대신 식당을 꼭 넣는다`() {
        val few = listOf(themed("h1",TravelTheme.HEALING,lat=36.30),themed("h2",TravelTheme.HEALING,lat=36.31),
            themed("h3",TravelTheme.HEALING,lat=36.32),themed("c1",TravelTheme.CAFE,lat=36.33),
            themed("f1",TravelTheme.FOOD,lat=36.34),themed("f2",TravelTheme.FOOD,lat=36.35),themed("f3",TravelTheme.FOOD,lat=36.36))
        val r = planDemoCourses(few,TravelTheme.HEALING,TripLength.HALF_DAY,"30",departure,open,{true})
        assertTrue(r.courses.isNotEmpty())
        assertTrue(r.courses.all { c -> c.stops.any { TravelTheme.FOOD in it.card.themes } })
    }
}
