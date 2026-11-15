package com.team.travelmap.vehicle

import com.team.travelmap.core.model.EnergyState
import com.team.travelmap.core.model.Gear
import kotlinx.coroutines.flow.StateFlow

/**
 * 담당: 2번(차량·내비·통합). 다른 모듈은 이 인터페이스만 쓴다.
 * 구현: [PleosVehicleRepository](실제 SDK), [MockVehicleRepository](에뮬레이터 밖·개발용)
 */
interface VehicleRepository {
    val gear: StateFlow<Gear>
    val energy: StateFlow<EnergyState>

    fun start()
    fun stop()

    /** 누적 주행거리(km). 여행 시작·완료 시점 차이로 여행 1회 주행거리를 계산한다. 실패 시 null. */
    suspend fun odometerKm(): Double?
}
