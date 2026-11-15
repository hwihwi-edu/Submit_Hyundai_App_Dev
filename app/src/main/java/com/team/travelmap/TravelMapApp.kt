package com.team.travelmap

import android.app.Application
import com.team.travelmap.core.trip.TripState
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 차량·내비 연결은 Activity 가 아니라 앱 수명에 묶는다.
 * 출발 버튼으로 내비 화면이 앞에 뜨면 MainActivity 는 onStop 되지만,
 * 그동안에도 도착 이벤트·현재 위치를 받아 오디오 스토리를 재생해야 하기 때문.
 * 안내 중(Guiding·Arrived)에는 TripForegroundService 로 프로세스가 정리되지 않게 지킨다.
 */
class TravelMapApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this).also { c ->
            c.vehicle.start()
            c.navi.connect()
            c.restrictions.start()
            c.ignition.start()
            c.scope.launch { c.vehicle.gear.collect { c.restrictions.onGearChanged(it) } }
            c.scope.launch {
                c.trip.state.map { it is TripState.Guiding || it is TripState.Arrived }
                    .distinctUntilChanged()
                    .collect { TripForegroundService.update(this@TravelMapApp, guiding = it) }
            }
        }
    }
}
