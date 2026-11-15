package com.team.travelmap

import com.team.travelmap.core.model.*
import com.team.travelmap.curation.ThemeCoursePlanner
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class ThemeCourseRoutesTest {
    private val request = CoursePlanningRequest(TravelTheme.FOOD,"30",LocalDate.of(2026,11,15),
        GeoPoint(36.3,127.3),100,3,50.0,null,setOf(TravelTheme.FOOD,TravelTheme.CAFE),
        LocalDateTime.of(2026,11,15,9,0))
    private fun candidate(id: String, theme: TravelTheme, point: GeoPoint) = CourseCandidate(
        ThemePlaceCard(id,Place(id,point,PlaceCategory.FOOD,RegionId("30170")),setOf(theme),
            "사진","","","소개",emptyMap(),mapOf(theme to "테스트"),
            source="테스트",collectedAt="테스트",detailCollectedAt="테스트"),20,1.0)
    private val a = candidate("1",TravelTheme.FOOD,GeoPoint(36.31,127.3))
    private val b = candidate("4",TravelTheme.CAFE,GeoPoint(36.32,127.3))
    private val open = CourseVisitAvailabilityProvider { _,_,_ -> CourseVisitDecision(CourseVisitState.AVAILABLE,"테스트") }
    private val costs = CourseTravelCostProvider { from,to -> when {
        from == request.origin && to == a.card.place.point -> CourseTravelCost(5.0,8.0,false)
        from == a.card.place.point && to == b.card.place.point -> CourseTravelCost(5.0,8.0,false)
        from == request.origin && to == b.card.place.point -> CourseTravelCost(8.0,1.0,false)
        from == b.card.place.point && to == a.card.place.point -> CourseTravelCost(8.0,1.0,false)
        else -> null
    } }
    private fun plan(visits: CourseVisitAvailabilityProvider = open) =
        ThemeCoursePlanner().recommend(request,listOf(a,b),costs,visits=visits)

    @Test fun `같은 장소 구성 안에서 시간과 거리 우선 동선을 비교한다`() {
        val result = plan()
        assertEquals(1,result.courses.size)
        val course = result.courses.single()
        assertEquals(2,course.routeOptions.size)
        val fast = course.usingRoute(CourseRouteCriterion.FASTEST)!!
        val short = course.usingRoute(CourseRouteCriterion.SHORTEST_DISTANCE)!!
        assertEquals(listOf("1","4"),fast.stops.map { it.card.contentId })
        assertEquals(listOf("4","1"),short.stops.map { it.card.contentId })
        assertEquals(10.0,fast.travelMinutes,0.001)
        assertEquals(16.0,fast.distanceKm,0.001)
        assertEquals(16.0,short.travelMinutes,0.001)
        assertEquals(2.0,short.distanceKm,0.001)
        assertEquals(request.departureAt!!.plusMinutes(8),short.stops.first().arrivalAt)
        assertEquals(56.0,short.totalMinutes,0.001)
    }

    @Test fun `같은 동선은 두 기준을 가진 옵션 하나로 합친다`() {
        val result = ThemeCoursePlanner().recommend(request,listOf(a,b),
            CourseTravelCostProvider { _,_ -> CourseTravelCost(10.0,1.0,false) },visits=open)
        val options = result.courses.single().routeOptions
        assertEquals(1,options.size)
        assertEquals(CourseRouteCriterion.entries.toSet(),options.single().criteria)
    }

    @Test fun `유지 장소도 두 기준에 맞춰 순서를 다시 계산한다`() {
        val previous = plan().courses.single()
        val result = ThemeCoursePlanner().recommend(request,listOf(a,b),costs,previous,setOf("1","4"),open)
        val chosen = result.courses.single().usingRoute(CourseRouteCriterion.SHORTEST_DISTANCE)!!
        assertEquals(listOf("4","1"),chosen.stops.map { it.card.contentId })
    }

    @Test fun `거리상 짧아도 마감인 순서는 옵션에 넣지 않는다`() {
        val result = plan(CourseVisitAvailabilityProvider { id,at,_ ->
            CourseVisitDecision(if (id == "1" && at >= request.departureAt!!.plusMinutes(15))
                CourseVisitState.UNAVAILABLE else CourseVisitState.AVAILABLE,"등록 마감")
        })
        val option = result.courses.single().routeOptions.single()
        assertEquals(CourseRouteCriterion.entries.toSet(),option.criteria)
        assertEquals(listOf("1","4"),option.stops.map { it.card.contentId })
    }

    @Test fun `탐색 폭이 작아도 두 목적의 후보를 각각 유지한다`() {
        val result = ThemeCoursePlanner(beamWidth=1).recommend(request,listOf(a,b),costs,visits=open)
        assertEquals(2,result.courses.single().routeOptions.size)
    }

    @Test fun `마무리 지점 이동도 두 목적 비교에 포함한다`() {
        val finish = GeoPoint(36.2,127.2)
        val travel = CourseTravelCostProvider { from,to ->
            if (to == finish) {
                if (from == a.card.place.point) CourseTravelCost(1.0,1.0,false)
                else CourseTravelCost(10.0,10.0,false)
            } else costs.cost(from,to)
        }
        val result = ThemeCoursePlanner().recommend(request.copy(finishPoint=finish),listOf(a,b),travel,visits=open)
        val course = result.courses.single()
        assertEquals(1,course.routeOptions.size)
        assertEquals(17.0,course.travelMinutes,0.001)
        assertEquals(3.0,course.distanceKm,0.001)
    }
}
