package com.team.travelmap.ui

import com.team.travelmap.core.model.*
import com.team.travelmap.curation.ThemeCoursePlanner
import com.team.travelmap.curation.ThemeRecommendation
import com.team.travelmap.curation.ThemeRecommendationInput
import com.team.travelmap.curation.ThemeRecommender
import java.time.LocalDateTime
import kotlin.random.Random

/** "누구랑" 답 → 우대 테마(2026-11-15 사용자 결정). 점수는 ThemeRecommender 에서 더한다. */
internal val Companion.preferredThemes: Set<TravelTheme> get() = when (this) {
    Companion.ALONE -> setOf(TravelTheme.CAFE, TravelTheme.HISTORY, TravelTheme.HEALING)
    Companion.TWO -> setOf(TravelTheme.CAFE, TravelTheme.FOOD, TravelTheme.SEASONAL)
    Companion.FAMILY -> setOf(TravelTheme.HEALING, TravelTheme.FESTIVAL, TravelTheme.HISTORY)
    Companion.FRIENDS -> setOf(TravelTheme.FOOD, TravelTheme.FESTIVAL, TravelTheme.CAFE)
}

/**
 * 고른 도시에서 코스에 쓸 수 있는 테마 전부를 추천 순서로 돌려준다. 화면은 이 순서대로 칩을 그린다.
 * 날씨를 모르면 precipitationExpected = null.
 */
internal fun recommendThemes(
    allCards: List<ThemePlaceCard>, companion: Companion, city: String, departureAt: LocalDateTime,
    isDemoReady: (String) -> Boolean, precipitationExpected: Boolean? = null,
): List<ThemeRecommendation> = ThemeRecommender.rank(ThemeRecommendationInput(
    cards = allCards.filter { it.place.regionId?.sidoCode == city && isDemoReady(it.contentId) },
    preferredThemes = companion.preferredThemes, travelDate = departureAt.toLocalDate(),
    precipitationExpected = precipitationExpected,
))

/** 화면과 같은 코스 입력 경로를 JVM 테스트에서도 검증한다. 기존 추정 정책은 유지한다. */
internal fun planDemoCourses(
    allCards: List<ThemePlaceCard>, theme: TravelTheme, length: TripLength, city: String,
    departureAt: LocalDateTime, visits: CourseVisitAvailabilityProvider,
    isDemoReady: (String) -> Boolean,
    previous: ThemeCourse? = null, locked: Set<String> = emptySet(),
    /** 있으면 장소 점수를 ±[JITTER] 흔들어 같은 조건에도 다른 조합이 나오게 한다. 테스트는 null(결정적). */
    random: Random? = null,
): CourseRecommendations {
    val cityCards = allCards.filter { it.place.regionId?.sidoCode == city }
    val cards = cityCards.filter { isDemoReady(it.contentId) }
    val unavailable = locked - cards.map { it.contentId }.toSet()
    if (unavailable.isNotEmpty()) return CourseRecommendations(emptyList(),
        setOf(CourseWarning.LOCKED_PLACE_UNAVAILABLE), unavailable)
    if (cityCards.none { theme in it.themes }) return CourseRecommendations(emptyList(), setOf(CourseWarning.NOT_ENOUGH_COURSES))
    if (cards.none { theme in it.themes }) return CourseRecommendations(emptyList(), setOf(CourseWarning.OPERATING_HOURS_NOT_VERIFIED))
    val has = { t: TravelTheme -> cards.any { t in it.themes } }
    val extra = when (length) {
        TripLength.TWO_HOURS -> emptySet()
        // 반나절은 카페 하나(없으면 식당). 단, 카페가 MIN_EXTRA_PLACES 곳 미만이면 코스마다 같은 카페로
        // 끝나므로(서울 검증: 5개 모두 같은 카페) 장소가 충분한 식당을 넣는다.
        TripLength.HALF_DAY -> listOf(TravelTheme.CAFE, TravelTheme.FOOD)
            .filter { it != theme && has(it) }
            .let { opts -> opts.firstOrNull { t -> cards.count { t in it.themes } >= MIN_EXTRA_PLACES } ?: opts.firstOrNull() }
            ?.let(::setOf).orEmpty()
        TripLength.FULL_DAY -> setOf(TravelTheme.FOOD, TravelTheme.CAFE).filter { it != theme && has(it) }.toSet()
    }
    val candidates = cards.map { CourseCandidate(it, CourseEstimates.stayMinutes(it),
        (if (theme in it.themes) 2.0 else 1.0) * (random?.let { r -> 1.0 + (r.nextDouble() * 2 - 1) * JITTER } ?: 1.0)) }
    val origin = GeoPoint(cards.map { it.place.point.latitude }.average(), cards.map { it.place.point.longitude }.average())
    val request = CoursePlanningRequest(
        theme = theme, cityCode = city, travelDate = departureAt.toLocalDate(), origin = origin,
        availableMinutes = length.minutes, courseCount = if (cards.size >= 8) 5 else 3,
        maxDistanceKm = null, finishPoint = null, requiredThemes = setOf(theme) + extra,
        departureAt = departureAt,
    )
    return ThemeCoursePlanner().recommend(request, candidates, CourseEstimates.travel,
        previous = previous, lockedIds = locked, visits = visits)
}

/** 장소 점수 흔들림 폭(±25%). 고른 테마 장소(2.0)가 다른 장소(1.0)보다 낮아지지는 않는다. */
internal const val JITTER = 0.25

/** 반나절 코스에 꼭 넣는 카페·식당이 이보다 적으면 다른 쪽을 넣는다(같은 곳 반복 방지). */
internal const val MIN_EXTRA_PLACES = 3

internal data class CourseTripPlan(val destination: Place, val waypoints: List<Place>)

/** 선택한 기준의 방문 순서·시각으로 바꾼다. 기준이 없으면 다른 동선으로 대체하지 않는다. */
internal fun CourseRecommendations.forRoute(criterion: CourseRouteCriterion): CourseRecommendations {
    val selected = courses.mapNotNull { it.usingRoute(criterion) }
    return copy(courses = selected,
        warnings = if (selected.size < 3) warnings + CourseWarning.NOT_ENOUGH_COURSES else warnings)
}

/** 내비에는 TripLegs가 한 곳씩 넘긴다. 이 경계에서 전체 경유지를 자르지 않는다. */
internal fun ThemeCourse.toTripPlan(): CourseTripPlan? {
    val places = stops.map { it.card.place }
    return places.lastOrNull()?.let { CourseTripPlan(it, places.dropLast(1)) }
}

internal fun courseEmptyReason(r: CourseRecommendations, theme: TravelTheme, cityName: String): String = when {
    CourseWarning.LOCKED_PLACE_UNAVAILABLE in r.warnings || CourseWarning.LOCKED_PLACE_NOT_FEASIBLE in r.warnings ->
        "남긴 곳을 이번 일정에 넣을 수 없어요. 남긴 장소와 운영시간을 확인해 주세요."
    CourseWarning.MISSING_DEPARTURE_TIME in r.warnings || CourseWarning.INVALID_DEPARTURE_TIME in r.warnings ||
        CourseWarning.MISSING_HOURS_PROVIDER in r.warnings -> "코스 정보를 확인하지 못했어요. 다시 추천해 주세요."
    CourseWarning.OPERATING_HOURS_NOT_VERIFIED in r.warnings || CourseWarning.VISIT_OUTSIDE_HOURS in r.warnings ->
        "지금 출발하는 일정에 맞는 운영시간을 확인하지 못했어요. 다른 테마나 도시를 골라 보세요."
    CourseWarning.MISSING_TRAVEL_COST in r.warnings -> "이동시간 정보를 확인하지 못해 코스를 만들 수 없어요."
    else -> "$cityName 에서 ${theme.displayName} 코스를 아직 만들 수 없어요. 시간을 늘리거나 다른 도시·테마를 골라 보세요."
}
