package com.team.travelmap.core.trip

import com.team.travelmap.core.model.Place

/**
 * 담당: 2번(차량·내비·통합). "여행을 계속할까요?" 신호.
 * 화면(1번)은 [TripCoordinator.resumeRequest] 가 null 이 아니면 큰 버튼 2개(계속/아니요)를 보여 주고
 * 고른 답을 [TripCoordinator.answerResume] 로 넘긴다. [autoContinueAtMillis] 까지 답이 없으면 자동으로 계속한다.
 *
 * @property next 계속하면 향할 곳(다음 경유지, 없으면 목적지)
 * @property toDestination next 가 최종 목적지인지
 * @property remainingWaypoints 계속하면 남는 경유지 수(next 포함)
 * @property autoContinueAtMillis 자동 계속 시각(System.currentTimeMillis 기준). 화면 카운트다운에 쓴다.
 */
data class ResumeRequest(
    val next: Place,
    val toDestination: Boolean,
    val remainingWaypoints: Int,
    val autoContinueAtMillis: Long,
)

object TripResume {
    /** 이 시간 동안 답이 없으면 계속한다(2번 담당자 결정, 이슈 #21). */
    const val AUTO_CONTINUE_MS = 10_000L

    /**
     * 시동이 다시 켜졌을 때 알림을 띄울지 판단한다. 순수 함수.
     * - 경유지에 도착해 주차(P)한 상태에서 시동이 꺼졌다 켜진 경우에만(화면만 잠깐 꺼진 경우를 거르기 위해)
     * - 설정 "여행 재개 알림"이 꺼져 있으면 띄우지 않는다(지금처럼 P → D 로 다음 구간)
     */
    fun request(legs: TripLegs?, ignitionWasOffAtStop: Boolean, enabled: Boolean, nowMillis: Long): ResumeRequest? {
        if (legs == null || !enabled || !ignitionWasOffAtStop || !legs.stoppedAtWaypoint) return null
        return ResumeRequest(
            next = legs.placeAfterStop,
            toDestination = legs.waypoints.getOrNull(legs.nextIndex + 1) == null,
            remainingWaypoints = (legs.waypoints.size - legs.nextIndex - 1).coerceAtLeast(0),
            autoContinueAtMillis = nowMillis + AUTO_CONTINUE_MS,
        )
    }
}
