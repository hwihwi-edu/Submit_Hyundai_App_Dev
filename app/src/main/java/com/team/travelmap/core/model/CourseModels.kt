package com.team.travelmap.core.model

import java.time.LocalDate
import java.time.LocalDateTime

/** 담당: 3번 추천, 1번 화면, 2번 경로 비용 제공. 미정 정책에는 기본값을 두지 않는다. */
data class CoursePlanningRequest(
    val theme: TravelTheme,
    val cityCode: String,
    val travelDate: LocalDate,
    val origin: GeoPoint,
    val availableMinutes: Int,
    val courseCount: Int,
    /** null이면 별도 거리 상한 없음. 주행가능거리 보장과는 구분한다. */
    val maxDistanceKm: Double?,
    /** 복귀/마무리 지점. null이면 마지막 방문 장소에서 코스 종료. 호출자가 명시한다. */
    val finishPoint: GeoPoint?,
    /** 중심 테마와 필수 식사·카페 조합은 팀 정책이 정해진 후 호출자가 전달한다. */
    val requiredThemes: Set<TravelTheme>,
    /** 한국 시간 출발 시각. 없으면 운영시간을 비교할 수 없어 데모 코스를 만들지 않는다. */
    val departureAt: LocalDateTime? = null,
)

data class CourseCandidate(
    val card: ThemePlaceCard,
    /** 미확인 값은 null. 임의의 체류시간으로 대체하지 않는다. */
    val stayMinutes: Int?,
    /** 질문·날씨·계절 등의 점수는 별도 산정해 전달한다. */
    val preferenceScore: Double,
)

data class CourseTravelCost(
    val minutes: Double,
    val distanceKm: Double,
    val estimated: Boolean,
)

/** SDK를 직접 호출하지 않는다. 2번이 마련한 경로 비용 스냅샷 등을 전달한다. 미확인은 null. */
fun interface CourseTravelCostProvider {
    fun cost(from: GeoPoint, to: GeoPoint): CourseTravelCost?
}

enum class CourseVisitState { AVAILABLE, UNAVAILABLE, UNKNOWN }
data class CourseVisitDecision(val state: CourseVisitState, val reason: String)

fun interface CourseVisitAvailabilityProvider {
    fun check(contentId: String, arrival: LocalDateTime, stayMinutes: Int): CourseVisitDecision
}

data class CourseStop(
    val card: ThemePlaceCard, val stayMinutes: Int,
    val arrivalAt: LocalDateTime? = null,
) {
    val leaveAt: LocalDateTime? get() = arrivalAt?.plusMinutes(stayMinutes.toLong())
}

/** 탐색 중 제외된 방문 시각의 예시. 해당 장소가 모든 시각에 불가하다는 뜻은 아니다. */
data class CourseVisitRejection(
    val contentId: String, val arrivalAt: LocalDateTime, val decision: CourseVisitDecision,
)

enum class CourseRouteCriterion { FASTEST, SHORTEST_DISTANCE }

data class CourseRouteOption(
    /** 같은 동선이 두 기준을 모두 만족하면 옵션 하나에 두 기준을 기록한다. */
    val criteria: Set<CourseRouteCriterion>,
    val stops: List<CourseStop>,
    val travelMinutes: Double,
    val distanceKm: Double,
    val includesEstimatedTravel: Boolean,
) {
    val stayMinutes: Int get() = stops.sumOf { it.stayMinutes }
    val totalMinutes: Double get() = travelMinutes + stayMinutes
}

/** 코스 하나는 장소 구성 하나다. 방문 순서 대안은 routeOptions에서 비교한다. */
data class ThemeCourse(
    val stops: List<CourseStop>,
    val travelMinutes: Double,
    val stayMinutes: Int,
    val distanceKm: Double,
    val preferenceScore: Double,
    val includesEstimatedTravel: Boolean,
    val routeOptions: List<CourseRouteOption> = emptyList(),
) {
    val totalMinutes: Double get() = travelMinutes + stayMinutes

    /** 화면에서 선택한 기준의 동선을 기존 내비 연결 형태로 변환한다. */
    fun usingRoute(criterion: CourseRouteCriterion): ThemeCourse? {
        val option = routeOptions.find { criterion in it.criteria } ?: return null
        return copy(stops = option.stops, travelMinutes = option.travelMinutes,
            stayMinutes = option.stayMinutes, distanceKm = option.distanceKm,
            includesEstimatedTravel = option.includesEstimatedTravel)
    }
}

enum class CourseWarning {
    MISSING_STAY_TIME, MISSING_TRAVEL_COST, INVALID_CANDIDATE,
    LOCKED_PLACE_UNAVAILABLE, NOT_ENOUGH_COURSES, NO_NEW_ALTERNATIVE,
    MISSING_DEPARTURE_TIME, INVALID_DEPARTURE_TIME, MISSING_HOURS_PROVIDER,
    OPERATING_HOURS_NOT_VERIFIED, VISIT_OUTSIDE_HOURS, LOCKED_PLACE_NOT_FEASIBLE,
    DAY_BOUNDARY_NOT_SUPPORTED,
}

data class CourseRecommendations(
    val courses: List<ThemeCourse>,
    val warnings: Set<CourseWarning>,
    val unavailableLockedIds: Set<String> = emptySet(),
    /** 탐색량 제한으로 전체 조합의 최적해를 보장하지 못한 경우. */
    val searchTruncated: Boolean = false,
    val rejectedVisits: List<CourseVisitRejection> = emptyList(),
)
