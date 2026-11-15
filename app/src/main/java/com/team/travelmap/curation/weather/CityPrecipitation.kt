package com.team.travelmap.curation.weather

import com.team.travelmap.core.model.GeoPoint
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException

/**
 * 담당: 3번. 테마 순위에 쓰는 도시 단위 강수 판단(코스가 나오기 전 단계).
 * 여행 시간대의 정시 예보 중 하나라도 비·눈(PTY > 0)이면 true(결측이 섞여 있어도), 모두 0이면 false.
 * 꺼짐·키 없음·조회 실패·최신 발표분이 아님, 또는 비 없이 결측이 섞이면 null(맑음으로 간주하지 않는다).
 */
class CityPrecipitation(
    private val load: suspend (GeoPoint, WeatherKind, Boolean) -> WeatherResult,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun expected(point: GeoPoint, from: LocalDateTime, until: LocalDateTime, enabled: Boolean): Boolean? {
        if (!enabled || until < from) return null
        val result = try {
            load(point, WeatherKind.FORECAST, true)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        }
        val snapshot = (result as? WeatherResult.Success)?.snapshot ?: return null
        val now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul"))
        if (snapshot.kind != WeatherKind.FORECAST || snapshot.requestedBaseAt != KmaSchedule.latest(WeatherKind.FORECAST, now)) return null
        return evaluate(snapshot, from, until)
    }

    companion object {
        fun evaluate(snapshot: WeatherSnapshot, from: LocalDateTime, until: LocalDateTime): Boolean? {
            val hours = generateSequence(from.withMinute(0).withSecond(0).withNano(0)) { it.plusHours(1) }
                .takeWhile { it <= until }.toList()
            val codes = hours.map { snapshot.at(it)?.precipitationType }
            return when {
                codes.any { it != null && it > 0 } -> true
                codes.isNotEmpty() && codes.all { it == 0 } -> false
                else -> null
            }
        }
    }
}
