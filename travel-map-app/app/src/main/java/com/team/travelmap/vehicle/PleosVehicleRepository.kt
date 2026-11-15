package com.team.travelmap.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.EvBatteryEfficiencyListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import android.content.Context
import android.util.Log
import com.team.travelmap.core.model.EnergyState
import com.team.travelmap.core.model.Gear
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * 담당: 2번(차량·내비·통합). Pleos Vehicle SDK 2.3.2 연동.
 * 패턴: Vehicle(context).initialize() → getXxx() 도메인 객체 → get/register (비동기 onComplete/onFailed) → release()
 *
 * 에너지(주행가능거리) 계산 — Vehicle 2.3.2 소스로 확인한 공개 API만 쓴다.
 *  - 전기차: getEvBattery().getEvBatteryStateOfCharge (Int, %) × 배터리 용량 × 전비
 *      전비: getCurrentEvEfficiency(km/kWh, 리스너로 갱신) → 0 이하이면 getCumulativeEvEfficiency → 기본값
 *  - 내연기관: getFuel().getFuelPercent (Int, %) × 연료탱크 용량 ÷ 연비
 *      연비: getCurrentFuelEconomy(L/100km) → 0 이하이면 기본값
 *  - 배터리 용량(getEvBatteryCapacity)·잔량 Wh(getEvBatteryLevel)·남은 거리(getRangeRemainingDistance)는
 *    @hide·deprecated 라 쓰지 않는다. 그래서 용량·탱크 크기는 아래 상수로 가정한다.
 *  - 잔량(%)을 못 읽으면 energy.rangeKm = null 로 두고, 룰렛은 RouletteFilter.fallbackRangeKm 로 거른다.
 *  - Virtual Console(adb forward tcp:8088 tcp:8088 → http://127.0.0.1:8088/)에서 값을 바꿔 가며 확인.
 */
class PleosVehicleRepository(context: Context) : VehicleRepository {

    private val appContext = context.applicationContext
    private var vehicle: Vehicle? = null

    private val _gear = MutableStateFlow(Gear.UNKNOWN)
    override val gear: StateFlow<Gear> = _gear.asStateFlow()
    private val _energy = MutableStateFlow(EnergyState())
    override val energy: StateFlow<EnergyState> = _energy.asStateFlow()

    private val gearListener = object : GearSelectionListener {
        override fun onSelected(gear: VehicleGear?) { _gear.value = gear.toDomain() }
        override fun onFailed(e: Exception) { Log.w(TAG, "gear listener failed", e) }
    }

    // 에너지 원본 값. SDK 콜백은 binder 스레드에서 오므로 @Volatile 로 두고 publishEnergy() 에서 한 번에 계산한다.
    @Volatile private var evSocPercent: Int? = null
    @Volatile private var evCurrentKmPerKWh: Float? = null
    @Volatile private var evCumulativeKmPerKWh: Float? = null
    @Volatile private var fuelPercent: Int? = null
    @Volatile private var fuelLPer100Km: Float? = null

    private val socListener = object : EvBatteryStateOfChargeListener {
        override fun onEvBatteryStateOfChargeUpdated(value: Int?) { evSocPercent = value; publishEnergy() }
        override fun onFailed(e: Exception) { warnUnsupported("SoC listener failed", e) }
    }
    private val evEfficiencyListener = object : EvBatteryEfficiencyListener {
        override fun onEvBatteryEfficiencyUpdated(efficiency: Float?) { evCurrentKmPerKWh = efficiency; publishEnergy() }
        override fun onFailed(e: Exception) { warnUnsupported("EV efficiency listener failed", e) }
    }
    private val fuelPercentListener = object : FuelPercentListener {
        override fun onFuelPercentUpdated(value: Int?) { fuelPercent = value; publishEnergy() }
        override fun onFailed(e: Exception) { warnUnsupported("fuel listener failed", e) }
    }

    override fun start() {
        if (vehicle != null) return
        val v = runCatching { Vehicle(appContext).also { it.initialize() } }
            .onFailure { Log.w(TAG, "Vehicle initialize 실패", it) }
            .getOrNull() ?: return
        vehicle = v
        runCatching {
            v.getGear().getCurrentGearSelectionState({ _gear.value = it.toDomain() }, { Log.w(TAG, "gear read", it) })
            v.getGear().registerGearSelection(gearListener)
        }.onFailure { Log.w(TAG, "gear 구독 실패", it) }
        runCatching {
            v.getEvBattery().registerEvBatteryStateOfCharge(socListener)
            v.getEvBattery().registerCurrentEvEfficiency(evEfficiencyListener)
        }.onFailure { Log.w(TAG, "EV 배터리 구독 실패", it) }
        runCatching { v.getFuel().registerFuelPercent(fuelPercentListener) }
            .onFailure { Log.w(TAG, "연료 구독 실패", it) }
        refreshEnergy()
    }

    override fun stop() {
        val v = vehicle ?: return
        vehicle = null
        runCatching { v.getGear().unregisterGearSelection(gearListener) }
        runCatching { v.getEvBattery().unregisterEvBatteryStateOfCharge(socListener) }
        runCatching { v.getEvBattery().unregisterCurrentEvEfficiency(evEfficiencyListener) }
        runCatching { v.getFuel().unregisterFuelPercent(fuelPercentListener) }
        runCatching { v.release() }
    }

    /** 에너지 관련 값을 한 번씩 다시 읽는다. 이후 변화는 리스너로 받는다. */
    fun refreshEnergy() {
        val v = vehicle ?: return
        runCatching {
            val ev = v.getEvBattery()
            ev.getEvBatteryStateOfCharge({ evSocPercent = it; publishEnergy() }, { warnUnsupported("SoC read", it) })
            ev.getCurrentEvEfficiency({ evCurrentKmPerKWh = it; publishEnergy() }, { warnUnsupported("EV efficiency read", it) })
            ev.getCumulativeEvEfficiency({ evCumulativeKmPerKWh = it; publishEnergy() }, { warnUnsupported("EV cumulative efficiency read", it) })
        }.onFailure { Log.w(TAG, "EV 배터리 읽기 실패", it) }
        runCatching {
            val fuel = v.getFuel()
            fuel.getFuelPercent({ fuelPercent = it; publishEnergy() }, { warnUnsupported("fuel read", it) })
            fuel.getCurrentFuelEconomy({ fuelLPer100Km = it; publishEnergy() }, { warnUnsupported("fuel economy read", it) })
        }.onFailure { Log.w(TAG, "연료 읽기 실패", it) }
    }

    // 에뮬레이터·차종에 따라 지원하지 않는 속성이 많다(ERROR_NOT_SUPPORTED_PROPERTY). 스택 없이 한 줄만 남긴다.
    private fun warnUnsupported(what: String, e: Exception) = Log.w(TAG, "$what: ${e.message}")

    private fun publishEnergy() {
        val state = EnergyEstimator.estimate(
            evSocPercent = evSocPercent,
            evCurrentKmPerKWh = evCurrentKmPerKWh,
            evCumulativeKmPerKWh = evCumulativeKmPerKWh,
            fuelPercent = fuelPercent,
            fuelLPer100Km = fuelLPer100Km,
        )
        if (state != _energy.value) {
            Log.d(TAG, "energy: $state (soc=$evSocPercent, eff=$evCurrentKmPerKWh/$evCumulativeKmPerKWh, fuel=$fuelPercent, l100=$fuelLPer100Km)")
            _energy.value = state
        }
    }

    override suspend fun odometerKm(): Double? {
        val v = vehicle ?: return null
        return withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine { cont ->
                runCatching {
                    v.getOdometer().getCurrentDistanceDriven(
                        { value -> if (cont.isActive) cont.resume(value?.toDouble()) },
                        { e -> Log.w(TAG, "odometer", e); if (cont.isActive) cont.resume(null) },
                    )
                }.onFailure { if (cont.isActive) cont.resume(null) }
            }
        }
    }

    private fun VehicleGear?.toDomain(): Gear = when (this) {
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        VehicleGear.GEAR_DRIVE -> Gear.DRIVE
        null, VehicleGear.GEAR_UNKNOWN -> Gear.UNKNOWN
        else -> Gear.DRIVE // 수동 단수(1~n단) 등은 주행으로 취급
    }

    private companion object { const val TAG = "PleosVehicle" }
}
