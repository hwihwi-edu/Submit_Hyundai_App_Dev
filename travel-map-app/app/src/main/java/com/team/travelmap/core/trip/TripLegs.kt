package com.team.travelmap.core.trip

import com.team.travelmap.core.model.Gear
import com.team.travelmap.core.model.Place

/**
 * 담당: 2번(차량·내비·통합). 경유지가 여러 곳인 여행을 구간으로 나눠 내비에 넘기는 순서표. 순수 로직.
 *
 * NaviHelper 는 경유지를 최대 2곳(FIRST/SECOND)만 받으므로, 앱이 전체 경유지를 기억하고
 * 내비에는 항상 "다음 경유지 1곳 + 최종 목적지"만 보낸다(이슈 #9 결정).
 *
 *  출발지 → 1 → 2 → 3 → 목적지
 *  처음:            현재 위치 → 1 → 목적지
 *  1번 도착·주차(P) 후 다시 출발(D): 현재 위치 → 2 → 목적지
 *  ...
 *  마지막 경유지에서 출발: 현재 위치 → 목적지
 *
 * 다음 구간으로 넘어가는 조건: 경유지 도착 이벤트 → 기어 P → 기어 D(다시 출발).
 * 주차하지 않고 지나치면 넘어가지 않는다(내비는 목적지 안내를 계속한다).
 */
data class TripLegs(
    val destination: Place,
    val waypoints: List<Place>,
    /** 지금 향하고 있는 경유지 번호(0부터). waypoints.size 이면 남은 경유지 없음. */
    val nextIndex: Int = 0,
    /** 지금 향하던 경유지에 도착 이벤트를 받았는지. */
    val arrivedAtWaypoint: Boolean = false,
    /** 경유지 도착 후 주차(P)했는지. */
    val parkedAtWaypoint: Boolean = false,
) {
    /** 지금 향하고 있는 경유지. 없으면 목적지로 가는 마지막 구간. */
    val nextWaypoint: Place? get() = waypoints.getOrNull(nextIndex)

    /** 아직 들르지 않은 경유지(지금 향하는 곳 포함). */
    val remainingWaypoints: List<Place> get() = waypoints.drop(nextIndex)

    /** 지금 경유지에 주차해 있는지(경유지 리뷰·"여행을 계속할까요?" 판단용). */
    val stoppedAtWaypoint: Boolean get() = arrivedAtWaypoint && parkedAtWaypoint

    /** 지금 내비에 넘길 경유지 목록(0~1곳). */
    fun naviWaypoints(): List<Place> = listOfNotNull(nextWaypoint)

    /** 내비의 경유지 도착 이벤트. 향하던 경유지가 있을 때만 기록한다. */
    fun onWaypointArrived(): TripLegs =
        if (nextWaypoint == null) this else copy(arrivedAtWaypoint = true)

    /**
     * 기어 변화. 경유지에 도착한 뒤 P → D 가 되면 다음 구간으로 넘어간다.
     * @return 바뀐 순서표와, 내비에 다음 구간을 요청해야 하면 true
     */
    fun onGear(gear: Gear): Pair<TripLegs, Boolean> = when {
        !arrivedAtWaypoint -> this to false
        gear == Gear.PARK -> copy(parkedAtWaypoint = true) to false
        gear == Gear.DRIVE && parkedAtWaypoint -> departFromWaypoint() to true
        else -> this to false
    }

    /** 지금 경유지를 마치고 다음 구간으로 넘어간다(다시 출발 또는 "여행을 계속할까요?"에서 계속). */
    fun departFromWaypoint(): TripLegs =
        copy(nextIndex = nextIndex + 1, arrivedAtWaypoint = false, parkedAtWaypoint = false)

    /**
     * 달리는 중 "잠깐 들르기"(3번 가는 길 동행): [place]를 지금 향하던 곳 앞에 끼워 다음 경유지로 만든다.
     * 경유지에 도착·주차 중이면 순서가 꼬이므로 끼우지 않는다(null).
     */
    fun insertNext(place: Place): TripLegs? {
        if (arrivedAtWaypoint || parkedAtWaypoint) return null
        return copy(waypoints = waypoints.take(nextIndex) + place + waypoints.drop(nextIndex))
    }

    /** 지금 경유지를 마친 뒤 향할 곳. 남은 경유지가 없으면 목적지. */
    val placeAfterStop: Place get() = waypoints.getOrNull(nextIndex + 1) ?: destination
}
