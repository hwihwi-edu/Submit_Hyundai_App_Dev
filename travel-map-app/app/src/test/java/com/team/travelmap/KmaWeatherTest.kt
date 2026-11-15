package com.team.travelmap

import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.curation.weather.*
import java.time.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class KmaWeatherTest {
    @Test fun `초단기예보 요청 HH30과 응답 HH00을 각각 보존한다`() = runTest {
        val repository = KmaWeatherRepository(serviceKey = { "테스트키" },
            clock = Clock.fixed(Instant.parse("2026-11-14T13:40:00Z"), ZoneOffset.UTC),
            transport = WeatherTransport { _, params ->
                assertEquals("2130", params["base_time"])
                WeatherHttpResponse(200, """{"response":{"header":{"resultCode":"00"},"body":{"pageNo":1,
                    "totalCount":1,"items":{"item":{"baseDate":"20261114","baseTime":"2100","nx":60,"ny":127,
                    "category":"T1H","fcstDate":"20261114","fcstTime":"2300","fcstValue":"12"}}}}}""")
            })
        val result = repository.load(GeoPoint(37.5665,126.978), WeatherKind.ULTRA_FORECAST, true) as WeatherResult.Success
        assertEquals(LocalDateTime.of(2026,11,14,21,30), result.snapshot.requestedBaseAt)
        assertEquals(LocalDateTime.of(2026,11,14,21,0), result.snapshot.issuedAt)
    }

    @Test fun `서울 좌표를 기상청 격자로 변환한다`() {
        assertEquals(WeatherGrid(60, 127), KmaGrid.from(GeoPoint(37.5665, 126.9780)))
    }

    @Test fun `제공 시각 이전에는 이전 발표분을 사용하고 자정을 넘겨 계산한다`() {
        assertEquals(LocalDateTime.of(2026,11,14,23,0), KmaSchedule.latest(
            WeatherKind.OBSERVATION, LocalDateTime.of(2026,11,15,0,9)))
        assertEquals(LocalDateTime.of(2026,11,15,0,0), KmaSchedule.latest(
            WeatherKind.OBSERVATION, LocalDateTime.of(2026,11,15,0,10)))
        assertEquals(LocalDateTime.of(2026,11,14,11,30), KmaSchedule.latest(
            WeatherKind.ULTRA_FORECAST, LocalDateTime.of(2026,11,14,12,44)))
        assertEquals(LocalDateTime.of(2026,11,14,12,30), KmaSchedule.latest(
            WeatherKind.ULTRA_FORECAST, LocalDateTime.of(2026,11,14,12,45)))
        assertEquals(LocalDateTime.of(2026,11,13,23,0), KmaSchedule.latest(
            WeatherKind.FORECAST, LocalDateTime.of(2026,11,14,2,9)))
    }

    @Test fun `결측 기온 강수확률을 정상 날씨로 바꾸지 않는다`() {
        val missing = WeatherFrame(LocalDateTime.of(2026,11,14,12,0),
            mapOf("TMP" to "-999", "PTY" to "-999", "POP" to "101"))
        assertNull(missing.temperatureC)
        assertNull(missing.precipitationType)
        assertNull(missing.precipitationProbability)
        assertEquals(0, WeatherFrame(missing.validAt, mapOf("PTY" to "0")).precipitationType)
    }

    @Test fun `날씨 설정을 끄면 키 제공자와 네트워크를 호출하지 않는다`() = runTest {
        val repository = KmaWeatherRepository(serviceKey = { error("키 조회 금지") },
            transport = WeatherTransport { _, _ -> error("호출 금지") })
        assertEquals(WeatherResult.Disabled, repository.load(GeoPoint(37.5665,126.978), WeatherKind.FORECAST, false))
    }

    @Test fun `여러 페이지를 결합하고 발표 시각과 대상 시각을 보존한다`() = runTest {
        var calls = 0
        val repository = KmaWeatherRepository(serviceKey = { "테스트키" },
            clock = Clock.fixed(Instant.parse("2026-11-14T03:10:00Z"), ZoneOffset.UTC),
            transport = WeatherTransport { _, params ->
                calls++
                val page = params.getValue("pageNo")
                val category = if (page == "1") "T1H" else "PTY"
                val value = if (page == "1") "20" else "0"
                WeatherHttpResponse(200, """{"response":{"header":{"resultCode":"00"},"body":{
                    "pageNo":$page,"totalCount":2,"items":{"item":[{"baseDate":"20261114","baseTime":"1200",
                    "nx":60,"ny":127,"category":"$category","obsrValue":"$value"}]}}}}""")
            })
        val result = repository.load(GeoPoint(37.5665,126.978), WeatherKind.OBSERVATION, true) as WeatherResult.Success
        assertEquals(2, calls)
        assertEquals(20.0, result.snapshot.frames.single().temperatureC!!, 0.001)
        assertNull(result.snapshot.at(LocalDateTime.of(2026,11,14,15,0)))
    }

    @Test fun `요청과 다른 격자 응답은 차단한다`() = runTest {
        val repository = KmaWeatherRepository(serviceKey = { "테스트키" },
            clock = Clock.fixed(Instant.parse("2026-11-14T03:10:00Z"), ZoneOffset.UTC),
            transport = WeatherTransport { _, _ -> WeatherHttpResponse(200,
                """{"response":{"header":{"resultCode":"00"},"body":{"pageNo":1,"totalCount":1,
                    "items":{"item":{"baseDate":"20261114","baseTime":"1200","nx":67,"ny":100,
                    "category":"T1H","obsrValue":"20"}}}}}""") })
        assertEquals(WeatherResult.Failure(WeatherFailure.INVALID_RESPONSE),
            repository.load(GeoPoint(37.5665,126.978), WeatherKind.OBSERVATION, true))
    }

    @Test fun `HTTP 호출 제한은 날씨 없음과 구분한다`() = runTest {
        val repository = KmaWeatherRepository(serviceKey = { "테스트키" },
            transport = WeatherTransport { _, _ -> WeatherHttpResponse(429, "") })
        assertEquals(WeatherResult.Failure(WeatherFailure.QUOTA, 429),
            repository.load(GeoPoint(37.5665,126.978), WeatherKind.FORECAST, true))
    }
}
