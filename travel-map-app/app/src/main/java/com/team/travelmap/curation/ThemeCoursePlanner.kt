package com.team.travelmap.curation

import com.team.travelmap.core.model.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

/** 담당: 3번. UI·SDK·네트워크와 독립된 코스 조합기. 시간·거리·점수는 호출자가 제공한다. */
class ThemeCoursePlanner(private val beamWidth: Int = 64) {
    companion object {
        /** 코스 5개 안에서 한 장소가 들어갈 수 있는 최대 코스 수. */
        const val MAX_PLACE_REUSE = 2
        /** 두 코스가 함께 가질 수 있는 최대 장소 수(남긴 장소 제외). */
        const val MAX_SHARED_PLACES = 1
    }

    init { require(beamWidth > 0) }

    fun recommend(
        request: CoursePlanningRequest,
        candidates: List<CourseCandidate>,
        travel: CourseTravelCostProvider,
        previous: ThemeCourse? = null,
        lockedIds: Set<String> = emptySet(),
        visits: CourseVisitAvailabilityProvider? = null,
    ): CourseRecommendations {
        require(request.cityCode in PilotScope.ACTIVE_SIDO)
        require(request.availableMinutes > 0 && request.courseCount in 3..5)
        require(request.theme in request.requiredThemes)
        require(request.maxDistanceKm == null || (request.maxDistanceKm.isFinite() && request.maxDistanceKm >= 0))
        require(validPoint(request.origin) && (request.finishPoint == null || validPoint(request.finishPoint)))
        val warnings = mutableSetOf<CourseWarning>()
        val departure = request.departureAt ?: return CourseRecommendations(emptyList(), setOf(CourseWarning.MISSING_DEPARTURE_TIME))
        if (departure.toLocalDate() != request.travelDate) return CourseRecommendations(emptyList(), setOf(CourseWarning.INVALID_DEPARTURE_TIME))
        if (visits == null) return CourseRecommendations(emptyList(), setOf(CourseWarning.MISSING_HOURS_PROVIDER))
        // 장소별 첫 제외 시각만 진단 예시로 보관하여 경로 조합 수만큼 목록이 커지지 않게 한다.
        val rejectedVisits = linkedMapOf<String, CourseVisitRejection>()
        val eligible = candidates.distinctBy { it.card.contentId }.filter { candidate ->
            val card = candidate.card
            if (card.place.regionId?.sidoCode != request.cityCode || !validEvent(card, request.travelDate)) return@filter false
            if (!validPoint(card.place.point) || !candidate.preferenceScore.isFinite()) {
                warnings += CourseWarning.INVALID_CANDIDATE
                return@filter false
            }
            if (candidate.stayMinutes == null || candidate.stayMinutes <= 0) {
                warnings += CourseWarning.MISSING_STAY_TIME
                return@filter false
            }
            true
        }
        val availableIds = eligible.map { it.card.contentId }.toSet()
        val unavailable = lockedIds - availableIds
        if (unavailable.isNotEmpty()) return CourseRecommendations(emptyList(),
            warnings + CourseWarning.LOCKED_PLACE_UNAVAILABLE, unavailable)

        val costs = mutableMapOf<Pair<GeoPoint, GeoPoint>, CourseTravelCost?>()
        fun leg(from: GeoPoint, to: GeoPoint): CourseTravelCost? {
            val key = from to to
            if (!costs.containsKey(key)) {
                val value = if (from == to) CourseTravelCost(0.0, 0.0, false) else travel.cost(from, to)
                costs[key] = value?.takeIf {
                    it.minutes.isFinite() && it.distanceKm.isFinite() && it.minutes >= 0 && it.distanceKm >= 0
                }
            }
            return costs[key].also { if (it == null) warnings += CourseWarning.MISSING_TRAVEL_COST }
        }
        fun fits(minutes: Double, distance: Double) = minutes <= request.availableMinutes &&
            (request.maxDistanceKm == null || distance <= request.maxDistanceKm)

        val previousFreeIds = previous?.stops.orEmpty().map { it.card.contentId }.toSet() - lockedIds
        // 유지한 장소도 각 동선 기준에 맞춰 방문 순서를 다시 계산한다.
        val ranking = compareBy<State> { state -> state.ids.count { it in previousFreeIds } }
            .thenByDescending { it.score }
        val timeRanking = ranking.thenBy { it.travelSeconds }.thenBy { it.distanceKm }.thenBy { it.ids.joinToString(",") }
        val distanceRanking = ranking.thenBy { it.distanceKm }.thenBy { it.travelSeconds }.thenBy { it.ids.joinToString(",") }
        var frontier = listOf(State())
        val complete = mutableMapOf<Set<String>, MutableMap<CourseRouteCriterion, ThemeCourse>>()
        var truncated = false
        for (depth in eligible.indices) {
            val next = mutableListOf<State>()
            for (state in frontier) for (candidate in eligible) {
                val card = candidate.card
                val stay = candidate.stayMinutes ?: continue
                if (card.contentId in state.ids) continue
                val edge = leg(state.stops.lastOrNull()?.card?.place?.point ?: request.origin, card.place.point) ?: continue
                if (edge.minutes > request.availableMinutes) continue
                val edgeSeconds = ceil(edge.minutes * 60).toLong()
                val elapsedToArrival = state.travelSeconds + state.stayMinutes * 60L + edgeSeconds
                if (elapsedToArrival + stay * 60L > request.availableMinutes * 60L) continue
                // 초 미만은 올림하여 실제 계산보다 이른 도착으로 마감 조건을 통과시키지 않는다.
                val arrival = runCatching { departure.plusSeconds(elapsedToArrival) }.getOrNull() ?: continue
                if (arrival.toLocalDate() != request.travelDate) {
                    warnings += CourseWarning.DAY_BOUNDARY_NOT_SUPPORTED
                    continue
                }
                val decision = visits.check(card.contentId, arrival, stay)
                if (decision.state != CourseVisitState.AVAILABLE) {
                    warnings += if (decision.state == CourseVisitState.UNKNOWN) CourseWarning.OPERATING_HOURS_NOT_VERIFIED
                        else CourseWarning.VISIT_OUTSIDE_HOURS
                    rejectedVisits.putIfAbsent(card.contentId, CourseVisitRejection(card.contentId, arrival, decision))
                    continue
                }
                val finish = request.finishPoint?.let { leg(card.place.point, it) }
                if (request.finishPoint != null && finish == null) continue
                if (finish != null && finish.minutes > request.availableMinutes) continue
                val finishSeconds = finish?.let { ceil(it.minutes * 60).toLong() } ?: 0L
                val extended = State(
                    stops = state.stops + CourseStop(card, stay, arrival),
                    travelSeconds = state.travelSeconds + edgeSeconds,
                    stayMinutes = state.stayMinutes + stay,
                    distanceKm = state.distanceKm + edge.distanceKm,
                    score = state.score + candidate.preferenceScore,
                    estimated = state.estimated || edge.estimated,
                )
                val duration = (extended.travelSeconds + extended.stayMinutes * 60L + finishSeconds) / 60.0
                val distance = extended.distanceKm + (finish?.distanceKm ?: 0.0)
                if (!extended.score.isFinite() || !distance.isFinite() || !fits(duration, distance)) continue
                next += extended
                if (extended.ids.containsAll(lockedIds) &&
                    extended.stops.flatMap { it.card.themes }.containsAll(request.requiredThemes)) {
                    val course = ThemeCourse(extended.stops, (extended.travelSeconds + finishSeconds) / 60.0,
                        extended.stayMinutes, distance, extended.score, extended.estimated || finish?.estimated == true)
                    val best = complete.getOrPut(extended.ids.toSet()) { mutableMapOf() }
                    for (criterion in CourseRouteCriterion.entries) {
                        val old = best[criterion]
                        if (old == null || routeComparator(criterion).compare(course, old) < 0) {
                            best[criterion] = course
                        }
                    }
                }
            }
            // 두 목적의 탐색 후보를 함께 유지한다. 시간/거리를 임의 가중치로 합산하지 않는다.
            frontier = (next.sortedWith(timeRanking).take(beamWidth) +
                next.sortedWith(distanceRanking).take(beamWidth)).distinctBy { it.ids }
            if (frontier.size < next.size) truncated = true
            if (frontier.isEmpty()) break
        }
        val courses = complete.values.map { best ->
            val options = best.entries.groupBy { (_, course) -> course.stops.map { it.card.contentId } }
                .values.map { sameRoute ->
                    val route = sameRoute.first().value
                    CourseRouteOption(sameRoute.map { it.key }.toSet(), route.stops,
                        route.travelMinutes, route.distanceKm, route.includesEstimatedTravel)
                }
            // 기존 stops/시간/거리 필드는 시간 우선 동선의 호환 표현이다. UI는 두 옵션을 비교한다.
            best.getValue(CourseRouteCriterion.FASTEST).copy(routeOptions = options)
        }.sortedWith(
            compareBy<ThemeCourse> { course -> course.stops.count { it.card.contentId in previousFreeIds } }
                .thenByDescending { it.preferenceScore }.thenBy { it.distanceKm }
        ).let { ranked -> pickDiverse(ranked, request.courseCount, lockedIds) }
        if (courses.size < request.courseCount) warnings += CourseWarning.NOT_ENOUGH_COURSES
        if (courses.isEmpty() && lockedIds.isNotEmpty()) warnings += CourseWarning.LOCKED_PLACE_NOT_FEASIBLE
        val previousIds = previous?.stops?.map { it.card.contentId }?.toSet()
        if (previousIds != null && courses.isNotEmpty() &&
            courses.all { course -> course.stops.map { it.card.contentId }.toSet() == previousIds }) {
            warnings += CourseWarning.NO_NEW_ALTERNATIVE
        }
        return CourseRecommendations(courses, warnings, searchTruncated = truncated, rejectedVisits = rejectedVisits.values.toList())
    }

    /**
     * 점수 순서를 지키되 코스끼리 너무 겹치지 않게 고른다(같은 곳만 반복되는 문제, 2026-11-15).
     * 남긴 장소(locked)는 모든 코스에 들어가므로 셈에서 뺀다. 모자라면 남은 자리는 원래 순서로 채운다.
     */
    internal fun pickDiverse(ranked: List<ThemeCourse>, count: Int, lockedIds: Set<String>): List<ThemeCourse> {
        val picked = mutableListOf<ThemeCourse>()
        val uses = mutableMapOf<String, Int>()
        fun ids(c: ThemeCourse) = c.stops.map { it.card.contentId }.filter { it !in lockedIds }.toSet()
        for (course in ranked) {
            if (picked.size == count) break
            val mine = ids(course)
            val tooOften = mine.any { (uses[it] ?: 0) >= MAX_PLACE_REUSE }
            val tooSimilar = picked.any { (ids(it) intersect mine).size > MAX_SHARED_PLACES }
            if (tooOften || tooSimilar) continue
            picked += course
            mine.forEach { uses[it] = (uses[it] ?: 0) + 1 }
        }
        if (picked.size < count) picked += ranked.filter { it !in picked }.take(count - picked.size)
        return picked
    }

    private data class State(
        val stops: List<CourseStop> = emptyList(), val travelSeconds: Long = 0,
        val stayMinutes: Int = 0, val distanceKm: Double = 0.0, val score: Double = 0.0,
        val estimated: Boolean = false,
    ) { val ids: List<String> get() = stops.map { it.card.contentId } }

    private fun routeComparator(criterion: CourseRouteCriterion): Comparator<ThemeCourse> = when (criterion) {
        CourseRouteCriterion.FASTEST -> compareBy<ThemeCourse> { it.travelMinutes }.thenBy { it.distanceKm }
        CourseRouteCriterion.SHORTEST_DISTANCE -> compareBy<ThemeCourse> { it.distanceKm }.thenBy { it.travelMinutes }
    }.thenBy { it.stops.joinToString(",") { stop -> stop.card.contentId } }

    private fun validPoint(point: GeoPoint) = point.latitude.isFinite() && point.longitude.isFinite() &&
        point.latitude in -90.0..90.0 && point.longitude in -180.0..180.0

    private fun validEvent(card: ThemePlaceCard, day: LocalDate): Boolean {
        if (card.place.category != PlaceCategory.FESTIVAL) return true
        val start = runCatching { LocalDate.parse(card.eventStartDate, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
        val end = runCatching { LocalDate.parse(card.eventEndDate, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
        return start != null && end != null && start <= day && day <= end
    }
}
