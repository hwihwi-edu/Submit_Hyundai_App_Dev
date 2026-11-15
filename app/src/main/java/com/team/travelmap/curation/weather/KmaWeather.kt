package com.team.travelmap.curation.weather

import com.team.travelmap.core.model.GeoPoint
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.*

enum class WeatherKind(val operation: String) {
    OBSERVATION("getUltraSrtNcst"), ULTRA_FORECAST("getUltraSrtFcst"), FORECAST("getVilageFcst")
}

data class WeatherGrid(val x: Int, val y: Int)

/** 발표·대상 시각은 모두 한국 시간. 미제공·결측을 0도·맑음·강수없음으로 바꾸지 않는다. */
data class WeatherFrame(val validAt: LocalDateTime, val values: Map<String, String>) {
    fun number(category: String): Double? = values[category]?.toDoubleOrNull()
        ?.takeIf { it.isFinite() && it > -900 && it < 900 }
    val temperatureC: Double? get() = number("TMP") ?: number("T1H")
    val precipitationType: Int? get() = number("PTY")?.takeIf { it % 1.0 == 0.0 && it in 0.0..7.0 }?.toInt()
    val precipitationProbability: Double? get() = number("POP")?.takeIf { it in 0.0..100.0 }
}

data class WeatherSnapshot(
    val kind: WeatherKind, val grid: WeatherGrid, val issuedAt: LocalDateTime,
    val fetchedAt: java.time.Instant, val frames: List<WeatherFrame>,
    /** 초단기예보는 요청 HH30과 응답 HH00을 구분해 원본 시각을 보존한다. */
    val requestedBaseAt: LocalDateTime = issuedAt,
) {
    val source: String get() = "기상청 단기예보 조회서비스"
    /** 지정 시각 자료만 반환. 실황으로 미래 예보를 대체하거나 임의 보간하지 않는다. */
    fun at(time: LocalDateTime): WeatherFrame? = frames.find { it.validAt == time }
}

enum class WeatherFailure { MISSING_KEY, AUTHORIZATION, QUOTA, NO_DATA, NETWORK, INVALID_RESPONSE, HTTP_ERROR }
sealed interface WeatherResult {
    data class Success(val snapshot: WeatherSnapshot) : WeatherResult
    data class Failure(val reason: WeatherFailure, val httpStatus: Int? = null) : WeatherResult
    data object Disabled : WeatherResult
}

/** 공식 활용가이드 260623의 발표·제공 시각 기준. API허브 authKey가 아닌 공공데이터포털 serviceKey 사용. */
object KmaSchedule {
    fun latest(kind: WeatherKind, nowKst: LocalDateTime): LocalDateTime = when (kind) {
        WeatherKind.OBSERVATION -> nowKst.minusMinutes(10).withMinute(0).withSecond(0).withNano(0)
        WeatherKind.ULTRA_FORECAST -> {
            val available = nowKst.minusMinutes(15)
            val half = available.withMinute(30).withSecond(0).withNano(0)
            if (half > available) half.minusHours(1) else half
        }
        WeatherKind.FORECAST -> {
            val available = nowKst.minusMinutes(10)
            (0L..1L).flatMap { day -> listOf(2, 5, 8, 11, 14, 17, 20, 23).map { hour ->
                available.toLocalDate().minusDays(day).atTime(LocalTime.of(hour, 0))
            } }.filter { it <= available }.maxOrNull()!!
        }
    }
}

/** 기상청 DFS 람베르트 정각원추도법. 앱 좌표는 (위도, 경도), 결과는 격자 (x, y). */
object KmaGrid {
    fun from(point: GeoPoint): WeatherGrid {
        require(point.latitude.isFinite() && point.longitude.isFinite())
        require(point.latitude in 31.651814..43.393490 && point.longitude in 123.310165..132.774963)
        val rad = PI / 180.0
        val re = 6371.00877 / 5.0
        val slat1 = 30.0 * rad
        val slat2 = 60.0 * rad
        val sn = ln(cos(slat1) / cos(slat2)) /
            ln(tan(PI / 4 + slat2 / 2) / tan(PI / 4 + slat1 / 2))
        val sf = tan(PI / 4 + slat1 / 2).pow(sn) * cos(slat1) / sn
        val ro = re * sf / tan(PI / 4 + 38.0 * rad / 2).pow(sn)
        val ra = re * sf / tan(PI / 4 + point.latitude * rad / 2).pow(sn)
        var theta = point.longitude * rad - 126.0 * rad
        if (theta > PI) theta -= 2 * PI
        if (theta < -PI) theta += 2 * PI
        theta *= sn
        val grid = WeatherGrid(floor(ra * sin(theta) + 43 + 0.5).toInt(),
            floor(ro - ra * cos(theta) + 136 + 0.5).toInt())
        require(grid.x in 1..149 && grid.y in 1..253)
        return grid
    }
}
