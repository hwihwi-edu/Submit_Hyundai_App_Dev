package com.team.travelmap.navi

import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.core.model.Guidance
import com.team.travelmap.core.model.NaviConnection
import com.team.travelmap.core.model.NaviEvent
import com.team.travelmap.core.model.Place
import com.team.travelmap.core.model.TripCost
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 담당: 2번(차량·내비·통합). 다른 모듈은 이 인터페이스만 쓴다.
 * 구현: [PleosNaviRepository](NaviHelper 3.0.5), [MockNaviRepository](주행 시뮬레이션)
 */
interface NaviRepository {
    val connection: StateFlow<NaviConnection>
    val guidance: StateFlow<Guidance>
    /** 현재 위치. 오디오 스토리 재생 판단에 쓴다. 안내 중에는 [refreshLocation]으로 주기적으로 갱신. */
    val currentLocation: StateFlow<GeoPoint?>
    val events: SharedFlow<NaviEvent>

    fun connect()
    fun disconnect()

    /** 목적지 + 경유지(최대 2개, NaviHelper 제한) 경로 요청. 결과는 events/guidance 로 온다. */
    fun requestRoute(destination: Place, waypoints: List<Place> = emptyList()): Result<Unit>

    /** 내비 앱 화면을 앞으로 띄운다(출발 버튼). */
    fun showNavigation(): Result<Unit>

    fun refreshLocation()

    /**
     * 현재 위치 → [to] 까지 내비가 계산한 거리·시간(경로 안내는 시작하지 않는다).
     * 한 번에 하나씩 처리하며, 내비가 응답하지 않으면 null. 호출하는 쪽은 null 이면 직선거리 추정으로 대신한다.
     */
    suspend fun tripCost(to: Place): TripCost?

    companion object {
        const val MAX_WAYPOINTS = 2
    }
}
