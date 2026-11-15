package com.team.travelmap.core.model

/**
 * 공통 도메인 모델. [공통 약속] 여기 있는 타입을 바꿀 때는 팀 전체에 공유하고 바꾼다.
 *
 * 좌표 규칙: 항상 (위도 latitude, 경도 longitude) 순서.
 * NaviHelper 3.0.5 에서 SDK 좌표 인자 순서가 (위도, 경도)로 바뀌었으므로 앱 전체를 같은 순서로 통일한다.
 */
data class GeoPoint(val latitude: Double, val longitude: Double)

/** 시·군·구 행정구역 코드(5자리, 예: "44770" = 충남 서천군). 모든 모듈의 공통 키. */
@JvmInline
value class RegionId(val code: String) {
    /** 앞 2자리 = 시·도 코드 (예: "44" = 충남). 권역 배지 계산에 쓴다. */
    val sidoCode: String get() = code.take(2)
}

/** 시·군·구 하나. center 는 룰렛 거리 계산과 기본 목적지에 쓰는 대표 지점. */
data class Region(
    val id: RegionId,
    val sidoName: String,
    val name: String,
    val center: GeoPoint,
) {
    val fullName: String get() = "$sidoName $name"
}

enum class PlaceCategory { FOOD, SIGHT, FESTIVAL, CHARGER, LANDMARK }

/** 목적지·경유지·큐레이션 항목으로 쓰는 장소. */
data class Place(
    val name: String,
    val point: GeoPoint,
    val category: PlaceCategory,
    val regionId: RegionId? = null,
    val address: String? = null,
    val poiId: String? = null,
    val description: String? = null,
)

// ---------- 차량 ----------

enum class Gear { PARK, REVERSE, NEUTRAL, DRIVE, UNKNOWN }

/**
 * 시동 상태. Pleos SDK·에뮬레이터 모두 시동 신호(IGNITION_STATE)를 앱에 주지 않으므로
 * 차량 화면(헤드유닛)이 켜지고 꺼지는 것으로 대신 판단한다(vehicle/ScreenIgnitionMonitor).
 */
enum class Ignition { ON, OFF }

enum class PowerType { EV, FUEL, UNKNOWN }

/**
 * 에너지 상태. rangeKm 이 null 이면 주행가능거리를 모른다는 뜻이고,
 * 이때 룰렛의 "갈 수 있는 곳만" 모드는 사용자가 정한 거리(km)로 대신 거른다.
 */
data class EnergyState(
    val type: PowerType = PowerType.UNKNOWN,
    val levelPercent: Float? = null,
    val rangeKm: Double? = null,
)

// ---------- 내비 ----------

sealed interface NaviConnection {
    data object Disconnected : NaviConnection
    data object Connecting : NaviConnection
    data object Connected : NaviConnection
    data class Failed(val reason: String) : NaviConnection
}

data class Guidance(
    val active: Boolean = false,
    val destinationName: String? = null,
    val remainDistanceMeters: Int? = null,
    val remainTimeSeconds: Int? = null,
    /** 이번 경로의 예상 통행료(원). 경로 시작(onRouteStarted) 때 받는다. 0 = 무료 경로, null = 모름. */
    val tollFareWon: Int? = null,
    /** 경로상 첫 고속도로 IC 진출까지 남은 거리(m). 안내 목록에 없으면 null. 예: "대전IC 까지 8km" 표시. */
    val nextIcExitMeters: Int? = null,
)

/**
 * 현재 위치 → 한 지점까지 내비가 계산한 경로 비용(NaviHelper requestTrip).
 * 코스의 "도시까지 오는 시간"처럼 출발 전 실제 거리·시간이 필요할 때 쓴다.
 * [estimated] = true 면 내비 값이 아니라 직선거리로 어림한 값(Mock·응답 없음).
 */
data class TripCost(
    val distanceMeters: Int,
    val durationSeconds: Int,
    /** 도착 시 예상 배터리(%). 전기차가 아니거나 모르면 null. */
    val socAtDestPercent: Int?,
    val estimated: Boolean,
)

sealed interface NaviEvent {
    data object RouteStarted : NaviEvent
    data class DestinationArrived(val name: String?) : NaviEvent
    data object RouteCancelled : NaviEvent
    /** 경유지 도착. 여러 경유지를 구간으로 나눠 안내할 때 다음 구간 판단에 쓴다(TripLegs). */
    data class WaypointArrived(val name: String?) : NaviEvent
    /** 고속도로 IC 를 지남(진입 = true, 진출 = false). 고속도로 위 주차(휴게소)는 방문 기록에서 뺀다(ParkLog). */
    data class HighwayChanged(val onHighway: Boolean) : NaviEvent
    /** 단속·주의 구간 안내가 들어옴. 오디오 스토리는 잠시 재생을 미룬다. */
    data class CautionAhead(val count: Int) : NaviEvent
    data class Error(val message: String) : NaviEvent
}
