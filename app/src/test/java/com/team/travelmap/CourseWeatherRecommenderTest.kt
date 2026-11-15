package com.team.travelmap

import com.team.travelmap.core.model.*
import com.team.travelmap.curation.weather.*
import java.time.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class CourseWeatherRecommenderTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-15T01:15:00Z"),ZoneOffset.UTC)
    private val issued = LocalDateTime.of(2026,11,15,8,0)
    private val arrival = LocalDateTime.of(2026,11,15,11,30)
    private fun course(id: String, exposure: WeatherExposure, proof: String = "근거") = ThemeCourse(
        stops=listOf(CourseStop(ThemePlaceCard(
            contentId=id,place=Place(id,GeoPoint(37.5665,126.978),PlaceCategory.FOOD,RegionId("11110")),
            themes=setOf(TravelTheme.FOOD),imageUrl="사진",thumbnailUrl="",imageLicense="",introduction="소개",
            operatingInfo=emptyMap(),themeReasons=emptyMap(),source="테스트",collectedAt="테스트",detailCollectedAt="테스트",
            weatherExposure=exposure,weatherEvidence=proof),20,arrival)),
        travelMinutes=30.0,stayMinutes=20,distanceKm=5.0,preferenceScore=2.0,includesEstimatedTravel=true)
    private val base get() = CourseRecommendations(listOf(course("out",WeatherExposure.OUTDOOR),
        course("in",WeatherExposure.INDOOR)),emptySet())
    private fun snapshot(code: String) = WeatherSnapshot(WeatherKind.FORECAST,WeatherGrid(60,127),issued,
        clock.instant(),listOf(11,12).map { WeatherFrame(arrival.withHour(it).withMinute(0),mapOf("PTY" to code)) })
    private fun recommender(snapshot: WeatherSnapshot) = CourseWeatherRecommender(
        load={_,_,_->WeatherResult.Success(snapshot)},clock=clock)

    @Test fun `비와 눈 예보 모두 실내 우선이며 야외 후보는 삭제하지 않는다`() = runTest {
        for (code in listOf("1","3")) {
            val result = recommender(snapshot(code)).apply(base,emptySet(),true)
            assertEquals(listOf("in","out"),result.recommendations.courses.map { it.stops.single().card.contentId })
            assertEquals(CourseWeatherStatus.AVAILABLE,result.status)
        }
    }

    @Test fun `직접 선택한 야외 장소는 유지하고 선택 장소만으로 우선 점수를 만들지 않는다`() = runTest {
        val wet = recommender(snapshot("3"))
        val result = wet.apply(base,setOf("out"),true)
        assertTrue(result.recommendations.courses.any { it.stops.single().card.contentId == "out" })
        assertTrue(result.courses.first { it.course.stops.single().card.contentId == "out" }.visits.single().userSelected)
        val selectedIndoor = wet.apply(base,setOf("in"),true)
        assertEquals(base.courses,selectedIndoor.recommendations.courses)
    }

    @Test fun `날씨 off에서는 조회하지 않고 원래 추천을 유지한다`() = runTest {
        var calls = 0
        val result = CourseWeatherRecommender(load={_,_,_->calls++;WeatherResult.Success(snapshot("1"))},clock=clock)
            .apply(base,emptySet(),false)
        assertEquals(0,calls)
        assertEquals(base,result.recommendations)
        assertEquals(CourseWeatherStatus.DISABLED,result.status)
    }

    @Test fun `같은 기상 격자는 추천 실행 한 번에 한 번만 조회한다`() = runTest {
        var calls = 0
        CourseWeatherRecommender(load={_,_,_->calls++;WeatherResult.Success(snapshot("1"))},clock=clock)
            .apply(base,emptySet(),true)
        assertEquals(1,calls)
    }

    @Test fun `키 누락은 맑음으로 처리하지 않고 기본 추천을 유지한다`() = runTest {
        val result = CourseWeatherRecommender(load={_,_,_->WeatherResult.Failure(WeatherFailure.MISSING_KEY)},clock=clock)
            .apply(base,emptySet(),true)
        assertEquals(base,result.recommendations)
        assertEquals(CourseWeatherStatus.UNAVAILABLE,result.status)
        assertTrue(WeatherFailure.MISSING_KEY in result.failures)
    }

    @Test fun `오래된 발표분과 도착 시각을 덮지 않는 자료는 미확인이다`() = runTest {
        val stale = snapshot("1").copy(issuedAt=issued.minusHours(3),requestedBaseAt=issued.minusHours(3))
        assertEquals(CourseWeatherStatus.UNAVAILABLE,recommender(stale).apply(base,emptySet(),true).status)
        val missing = snapshot("0").copy(frames=listOf(WeatherFrame(arrival.withMinute(0),mapOf("PTY" to "0"))))
        assertEquals(CourseWeatherStatus.UNAVAILABLE,recommender(missing).apply(base,emptySet(),true).status)
    }

    @Test fun `실내 근거가 없으면 실내 우선으로 올리지 않는다`() = runTest {
        val input = CourseRecommendations(listOf(course("out",WeatherExposure.OUTDOOR),course("in",WeatherExposure.INDOOR,"")),emptySet())
        assertEquals(input,recommender(snapshot("1")).apply(input,emptySet(),true).recommendations)
    }
}
