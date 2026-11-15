package com.team.travelmap.vehicle

import com.team.travelmap.core.model.EnergyState
import com.team.travelmap.core.model.Gear
import com.team.travelmap.core.model.PowerType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 개발용 가짜 차량. 디버그 패널에서 기어·배터리를 바꿔 가며 다른 모듈을 테스트한다.
 * 기본값(480km)은 서울 출발 기준 "갈 수 있는 곳만" 모드에 서울·대전이 들어오고 부산은 빠지는 값이다.
 */
class MockVehicleRepository : VehicleRepository {
    private val _gear = MutableStateFlow(Gear.PARK)
    override val gear: StateFlow<Gear> = _gear.asStateFlow()

    private val _energy = MutableStateFlow(EnergyState(PowerType.EV, levelPercent = 85f, rangeKm = 480.0))
    override val energy: StateFlow<EnergyState> = _energy.asStateFlow()

    private var odometer = 12_345.0

    override fun start() = Unit
    override fun stop() = Unit
    override suspend fun odometerKm(): Double = odometer

    // ---- 디버그용 조작 ----
    fun setGear(gear: Gear) { _gear.value = gear }
    fun setEnergy(levelPercent: Float, rangeKm: Double) {
        _energy.value = EnergyState(PowerType.EV, levelPercent, rangeKm)
    }
    fun addDistance(km: Double) { odometer += km }
}
