package com.team.travelmap.curation.weather

import com.team.travelmap.core.model.*
import java.time.Clock
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class CourseWeatherStatus { DISABLED, AVAILABLE, PARTIAL, UNAVAILABLE }

data class CourseStopWeather(
    val contentId: String,
    val arrivalAt: LocalDateTime?,
    val precipitationExpected: Boolean?,
    val indoorSpaceConfirmed: Boolean,
    val userSelected: Boolean,
)

data class WeatherRankedCourse(val course: ThemeCourse, val visits: List<CourseStopWeather>) {
    val indoorPriority: Boolean get() = visits.any {
        !it.userSelected && it.precipitationExpected == true && it.indoorSpaceConfirmed
    }
}

data class CourseWeatherResult(
    val recommendations: CourseRecommendations,
    val status: CourseWeatherStatus,
    val courses: List<WeatherRankedCourse>,
    /** 발표·요청·조회·대상 시각은 원본 스냅샷에서 확인한다. 인증키는 포함하지 않는다. */
    val snapshots: List<WeatherSnapshot> = emptyList(),
    val failures: Set<WeatherFailure> = emptySet(),
)

/** 기존 코스 후보의 순서만 조정한다. 선택 장소·야외 장소를 날씨로 삭제하지 않는다. */
class CourseWeatherRecommender(
    private val load: suspend (GeoPoint, WeatherKind, Boolean) -> WeatherResult,
    private val clock: Clock = Clock.systemUTC(),
) {
    suspend fun apply(base: CourseRecommendations, selectedIds: Set<String>, enabled: Boolean): CourseWeatherResult {
        if (!enabled) return CourseWeatherResult(base, CourseWeatherStatus.DISABLED, emptyList())
        if (base.courses.isEmpty()) return CourseWeatherResult(base, CourseWeatherStatus.UNAVAILABLE, emptyList())
        val gridPoints = base.courses.flatMap { it.stops }.mapNotNull { stop ->
            runCatching { KmaGrid.from(stop.card.place.point) to stop.card.place.point }.getOrNull()
        }.toMap()
        val semaphore = Semaphore(3)
        val results = coroutineScope {
            gridPoints.map { (grid, point) -> async {
                val result = try {
                    semaphore.withPermit { load(point, WeatherKind.FORECAST, true) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    WeatherResult.Failure(WeatherFailure.NETWORK)
                }
                grid to result
            } }.awaitAll().toMap()
        }
        val now = LocalDateTime.ofInstant(clock.instant(), ZoneId.of("Asia/Seoul"))
        val latest = KmaSchedule.latest(WeatherKind.FORECAST, now)
        val snapshots = results.mapNotNull { (grid, result) ->
            (result as? WeatherResult.Success)?.snapshot?.takeIf {
                it.kind == WeatherKind.FORECAST && it.grid == grid && it.requestedBaseAt == latest
            }
        }.associateBy { it.grid }
        val courses = base.courses.map { course ->
            WeatherRankedCourse(course, course.stops.map { stop ->
                val grid = runCatching { KmaGrid.from(stop.card.place.point) }.getOrNull()
                val snapshot = grid?.let { snapshots[it] }
                CourseStopWeather(stop.card.contentId, stop.arrivalAt,
                    precipitation(snapshot, stop.arrivalAt),
                    stop.card.weatherEvidence.isNotBlank() && stop.card.weatherExposure in
                        setOf(WeatherExposure.INDOOR, WeatherExposure.MIXED),
                    stop.card.contentId in selectedIds)
            })
        }
        // 실내 공간 확인 여부로 우선한다. 기온·강수확률의 임의 임계값은 넣지 않는다.
        val ranked = courses.sortedByDescending { it.indoorPriority }
        val known = courses.sumOf { row -> row.visits.count { it.precipitationExpected != null } }
        val total = courses.sumOf { it.visits.size }
        val status = when { known == 0 -> CourseWeatherStatus.UNAVAILABLE
            known < total -> CourseWeatherStatus.PARTIAL
            else -> CourseWeatherStatus.AVAILABLE }
        return CourseWeatherResult(base.copy(courses = ranked.map { it.course }), status, ranked,
            snapshots.values.toList(), results.values.mapNotNull { (it as? WeatherResult.Failure)?.reason }.toSet())
    }

    /** 도착 전후 시간대 예보를 확인한다. 범위 밖·결측을 맑음으로 간주하지 않는다. */
    private fun precipitation(snapshot: WeatherSnapshot?, arrival: LocalDateTime?): Boolean? {
        if (snapshot == null || arrival == null) return null
        val before = arrival.withMinute(0).withSecond(0).withNano(0)
        val after = if (before == arrival) before else before.plusHours(1)
        val codes = listOf(before, after).distinct().map { snapshot.at(it)?.precipitationType }
        return when { codes.any { it != null && it > 0 } -> true
            codes.all { it == 0 } -> false
            else -> null }
    }
}
