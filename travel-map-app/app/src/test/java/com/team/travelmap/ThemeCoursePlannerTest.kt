package com.team.travelmap

import com.team.travelmap.core.model.*
import com.team.travelmap.curation.ThemeCoursePlanner
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Duration
import com.team.travelmap.curation.hours.*
import org.junit.Assert.*
import org.junit.Test

class ThemeCoursePlannerTest {
    private val planner = ThemeCoursePlanner()
    private val request = CoursePlanningRequest(
        theme = TravelTheme.FOOD, cityCode = "30", travelDate = LocalDate.of(2026, 11, 14),
        origin = GeoPoint(36.3, 127.3), availableMinutes = 100, courseCount = 3,
        maxDistanceKm = 10.0, finishPoint = null,
        requiredThemes = setOf(TravelTheme.FOOD, TravelTheme.CAFE),
        departureAt = LocalDateTime.of(2026,11,14,9,0),
    )
    private val open = CourseVisitAvailabilityProvider { _, _, _ -> CourseVisitDecision(CourseVisitState.AVAILABLE, "테스트") }
    private fun recommend(
        request: CoursePlanningRequest, candidates: List<CourseCandidate>, travel: CourseTravelCostProvider,
        previous: ThemeCourse? = null, lockedIds: Set<String> = emptySet(),
        visits: CourseVisitAvailabilityProvider = open,
    ) = planner.recommend(request, candidates, travel, previous, lockedIds, visits)

    private val costs = CourseTravelCostProvider { _, _ -> CourseTravelCost(10.0, 1.0, true) }
    private fun candidate(id: Int, theme: TravelTheme, city: String = "30", stay: Int? = 20): CourseCandidate {
        val card = ThemePlaceCard(
            contentId = id.toString(),
            place = Place("장소$id", GeoPoint(36.3 + id * 0.001, 127.3), PlaceCategory.FOOD, RegionId(city + "170")),
            themes = setOf(theme), imageUrl = "사진", thumbnailUrl = "", imageLicense = "",
            introduction = "소개", operatingInfo = mapOf("opentimefood" to "10~18"),
            themeReasons = mapOf(theme to "테스트 근거"), source = "테스트", collectedAt = "2026-11-14",
            detailCollectedAt = "2026-11-14",
        )
        return CourseCandidate(card, stay, 1.0)
    }
    private val candidates get() = (1..3).map { candidate(it, TravelTheme.FOOD) } +
        (4..6).map { candidate(it, TravelTheme.CAFE) }
    private fun ids(course: ThemeCourse) = course.stops.map { it.card.contentId }.toSet()

    @Test fun `서로 다른 장소 조합의 코스 3개를 반환하고 시간 거리 도시 조건을 지킨다`() {
        val result = recommend(request, candidates + candidate(7, TravelTheme.FOOD, "11"), costs)
        assertEquals(3, result.courses.size)
        assertEquals(3, result.courses.map(::ids).toSet().size)
        result.courses.forEach { course ->
            assertTrue(course.totalMinutes <= 100)
            assertTrue(course.distanceKm <= 10)
            assertTrue(course.stops.all { it.card.place.regionId?.sidoCode == "30" })
            assertTrue(course.stops.flatMap { it.card.themes }.containsAll(request.requiredThemes))
            assertTrue(course.includesEstimatedTravel)
        }
    }

    @Test fun `선택한 여행 시간이 길어지면 더 많은 장소가 들어갈 수 있다`() {
        val short = recommend(request, candidates, costs)
        val long = recommend(request.copy(availableMinutes = 180), candidates, costs)
        assertEquals(3, short.courses.maxOf { it.stops.size })
        assertEquals(6, long.courses.maxOf { it.stops.size })
    }

    @Test fun `재추천은 유지 장소를 보존하고 이전 미고정 장소보다 새 대안을 우선한다`() {
        val previous = recommend(request, candidates, costs).courses.first()
        val locked = setOf(previous.stops.first().card.contentId)
        val result = recommend(request, candidates, costs, previous, locked)
        assertTrue(result.courses.all { ids(it).containsAll(locked) })
        assertTrue((ids(result.courses.first()) intersect (ids(previous) - locked)).isEmpty())
    }

    @Test fun `모든 장소 유지로 새로운 대안이 없으면 중복 대안을 만들지 않는다`() {
        val previous = recommend(request, candidates, costs).courses.first()
        val result = recommend(request, candidates, costs, previous, ids(previous))
        assertEquals(1, result.courses.size)
        assertEquals(ids(previous), ids(result.courses.single()))
        assertTrue(CourseWarning.NO_NEW_ALTERNATIVE in result.warnings)
        assertTrue(CourseWarning.NOT_ENOUGH_COURSES in result.warnings)
    }

    @Test fun `다른 도시의 유지 장소는 몰래 교체하지 않고 실패 원인을 반환한다`() {
        val result = recommend(request, candidates + candidate(7, TravelTheme.FOOD, "11"),
            costs, lockedIds = setOf("7"))
        assertTrue(result.courses.isEmpty())
        assertEquals(setOf("7"), result.unavailableLockedIds)
        assertTrue(CourseWarning.LOCKED_PLACE_UNAVAILABLE in result.warnings)
    }

    @Test fun `체류시간과 이동시간이 없으면 임의 시간으로 추천하지 않는다`() {
        val noStay = recommend(request, listOf(candidate(1, TravelTheme.FOOD, stay = null)), costs)
        assertTrue(noStay.courses.isEmpty())
        assertTrue(CourseWarning.MISSING_STAY_TIME in noStay.warnings)
        val noTravel = recommend(request, candidates, CourseTravelCostProvider { _, _ -> null })
        assertTrue(noTravel.courses.isEmpty())
        assertTrue(CourseWarning.MISSING_TRAVEL_COST in noTravel.warnings)
    }

    @Test fun `종료된 행사는 요청 테마여도 재추천에 들어가지 않는다`() {
        val base = candidate(1, TravelTheme.FESTIVAL)
        val expired = base.copy(card = base.card.copy(
            place = base.card.place.copy(category = PlaceCategory.FESTIVAL),
            eventStartDate = "20261001", eventEndDate = "20261031",
        ))
        val result = recommend(request.copy(theme = TravelTheme.FESTIVAL,
            requiredThemes = setOf(TravelTheme.FESTIVAL)), listOf(expired), costs)
        assertTrue(result.courses.isEmpty())
    }

    @Test fun `마무리 지점을 지정하면 해당 이동시간도 시간 제한에 포함한다`() {
        val finish = GeoPoint(36.2, 127.2)
        val result = recommend(request.copy(finishPoint = finish, availableMinutes = 65), candidates,
            CourseTravelCostProvider { _, to -> CourseTravelCost(if (to == finish) 20.0 else 10.0, 1.0, false) })
        // 식당·카페 2곳의 이동+체류 60분에 마무리 이동 20분이 추가되어 65분 내 구성이 불가능하다.
        assertTrue(result.courses.isEmpty())
    }
    @Test fun `방문 예정 시각을 이동 체류 순서대로 누적한다`() {
        val result = recommend(request, candidates, costs)
        result.courses.forEach { course ->
            var time = request.departureAt!!
            course.stops.forEach { stop ->
                assertEquals(time.plusMinutes(10), stop.arrivalAt)
                time = stop.leaveAt!!
            }
            assertEquals(Duration.between(request.departureAt, time).toMinutes().toDouble(), course.totalMinutes, 0.001)
        }
    }

    @Test fun `운영시간 미확인과 마감 방문은 제외하되 원본 후보를 바꾸지 않는다`() {
        val original = candidates
        val result = recommend(request, original, costs, visits = CourseVisitAvailabilityProvider { id, _, _ ->
            when (id) {
                "1" -> CourseVisitDecision(CourseVisitState.UNAVAILABLE, "마감")
                "2" -> CourseVisitDecision(CourseVisitState.UNKNOWN, "미확인")
                else -> CourseVisitDecision(CourseVisitState.AVAILABLE, "등록 시간")
            }
        })
        assertTrue(result.courses.isNotEmpty())
        assertTrue(result.courses.all { course -> course.stops.none { it.card.contentId in setOf("1","2") } })
        assertEquals(6, original.size)
        assertTrue(CourseWarning.VISIT_OUTSIDE_HOURS in result.warnings)
        assertTrue(CourseWarning.OPERATING_HOURS_NOT_VERIFIED in result.warnings)
    }

    @Test fun `같은 장소도 마감 전 도착 순서에서만 코스에 포함한다`() {
        val result = recommend(request, listOf(candidate(1, TravelTheme.FOOD),candidate(4, TravelTheme.CAFE)), costs,
            visits = CourseVisitAvailabilityProvider { id, arrival, stay ->
                val data = OperatingHours(contentId=id, contentTypeId="39", status=HoursStatus.READY,
                    reason="등록 시간", rawHours="테스트", rawRest="연중무휴", eventStartDate="",eventEndDate="",
                    sourceFingerprint="테스트",sourceCollectedAt="테스트",closedWeekdays=emptyList(),
                    windows=listOf(HoursWindow((1..7).toList(),0,if (id == "1") 570 else 1440)))
                val assessment = OperatingHoursEvaluator.assess(data,arrival,stay)
                CourseVisitDecision(when (assessment.availability) {
                    VisitAvailability.EXPECTED_AVAILABLE -> CourseVisitState.AVAILABLE
                    VisitAvailability.EXPECTED_UNAVAILABLE -> CourseVisitState.UNAVAILABLE
                    VisitAvailability.NEEDS_CHECK -> CourseVisitState.UNKNOWN
                }, assessment.reason.name)
            })
        assertEquals(1, result.courses.size)
        assertEquals(listOf("1","4"),result.courses.single().stops.map { it.card.contentId })
    }

    @Test fun `출발 시각이나 검사 제공자가 없으면 영업시간 검사를 건너뛰지 않는다`() {
        assertTrue(CourseWarning.MISSING_DEPARTURE_TIME in
            recommend(request.copy(departureAt=null),candidates,costs).warnings)
        assertTrue(CourseWarning.INVALID_DEPARTURE_TIME in
            recommend(request.copy(departureAt=request.departureAt!!.plusDays(1)),candidates,costs).warnings)
        assertTrue(CourseWarning.MISSING_HOURS_PROVIDER in planner.recommend(request,candidates,costs).warnings)
    }

    @Test fun `유지한 장소가 마감이라면 다른 장소로 조용히 바꾸지 않는다`() {
        val previous = recommend(request,candidates,costs).courses.first()
        val locked = previous.stops.first().card.contentId
        val result = recommend(request,candidates,costs,previous,setOf(locked),
            visits=CourseVisitAvailabilityProvider { id,_,_ ->
                CourseVisitDecision(if (id == locked) CourseVisitState.UNAVAILABLE else CourseVisitState.AVAILABLE,"등록 시간")
            })
        assertTrue(result.courses.isEmpty())
        assertTrue(CourseWarning.LOCKED_PLACE_NOT_FEASIBLE in result.warnings)
    }

    @Test fun `초 미만 이동시간을 올려도 방문 시각과 코스 총시간이 일치한다`() {
        val result = recommend(request,candidates,CourseTravelCostProvider { _,_ -> CourseTravelCost(0.001,0.1,true) })
        result.courses.forEach { course ->
            var time = request.departureAt!!
            course.stops.forEach { stop ->
                assertEquals(time.plusSeconds(1),stop.arrivalAt)
                time = stop.leaveAt!!
            }
            assertEquals(Duration.between(request.departureAt,time).seconds/60.0,course.totalMinutes,0.00001)
        }
    }

    @Test fun `당일 조합에서 날짜가 넘어가는 방문을 몰래 섞지 않는다`() {
        val result = recommend(request.copy(departureAt=LocalDateTime.of(2026,11,14,23,50)),candidates,costs)
        assertTrue(result.courses.isEmpty())
        assertTrue(CourseWarning.DAY_BOUNDARY_NOT_SUPPORTED in result.warnings)
    }


    @Test fun `코스 5개는 한 장소를 2개 코스까지만 쓰고 두 코스가 2곳 이상 겹치지 않는다`() {
        fun course(vararg ids: Int) = ThemeCourse(ids.map { CourseStop(candidate(it, TravelTheme.FOOD).card, 20) }, 10.0, 60, 1.0, 1.0, true)
        val ranked = listOf(course(1, 2, 9), course(1, 3, 9), course(1, 4, 9), course(2, 3, 9), course(5, 6, 7), course(1, 5, 8))
        val picked = planner.pickDiverse(ranked, 3, emptySet())
        assertEquals(listOf(ranked[0], ranked[4], ranked[5]), picked)
        // 남긴 장소(9)는 셈에서 빼고, 모자라면 원래 순서로 채운다
        val locked = planner.pickDiverse(ranked, 5, setOf("9"))
        assertEquals(5, locked.size)
        assertEquals(ranked[0], locked[0])
    }
}
