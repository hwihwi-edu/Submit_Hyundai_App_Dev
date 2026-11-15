package com.team.travelmap.core.trip

import com.team.travelmap.core.model.Place

/**
 * 담당: 2번(차량·내비·통합). 경유지 리뷰를 띄울 때를 알려 주는 신호.
 * 화면(1번)은 [TripCoordinator.reviewRequest] 가 null 이 아닐 때 짧은 리뷰 화면을 보여 주고,
 * 저장·건너뛰기 후 [TripCoordinator.dismissReview] 를 부른다. 리뷰 저장(DB)은 1번 담당.
 *
 * @property place 리뷰할 경유지
 * @property index 코스 안에서 몇 번째 경유지인지(0부터)
 */
data class WaypointReviewRequest(val place: Place, val index: Int)

object WaypointReview {
    /**
     * 리뷰를 띄울지 판단한다. 순수 함수.
     * - 경유지에 도착해 주차(P)해 있을 때만
     * - 설정 "경유지 리뷰"가 켜져 있을 때만
     * - 같은 경유지에서 이미 닫았으면(dismissedIndex) 다시 띄우지 않는다
     * 다시 출발(D)하면 legs 가 다음 구간으로 넘어가 stoppedAtWaypoint 가 false 가 되므로 자동으로 사라진다.
     */
    fun request(legs: TripLegs?, reviewEnabled: Boolean, dismissedIndex: Int?): WaypointReviewRequest? {
        if (legs == null || !reviewEnabled || !legs.stoppedAtWaypoint) return null
        if (dismissedIndex == legs.nextIndex) return null
        val place = legs.nextWaypoint ?: return null
        return WaypointReviewRequest(place, legs.nextIndex)
    }
}
