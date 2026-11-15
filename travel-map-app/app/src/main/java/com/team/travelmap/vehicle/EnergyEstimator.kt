package com.team.travelmap.vehicle

import com.team.travelmap.core.model.EnergyState
import com.team.travelmap.core.model.PowerType

/**
 * 담당: 2번(차량·내비·통합). 차량 에너지 원본 값으로 주행가능거리를 추정하는 순수 로직.
 * SDK 에 의존하지 않으므로 단위 테스트(EnergyEstimatorTest)로 검증한다.
 *
 * - 전기차: 용량(kWh) × 잔량(%)/100 × 전비(km/kWh)
 * - 내연기관: 탱크(L) × 잔량(%)/100 ÷ 연비(L/100km) × 100
 * - 전비·연비가 없거나 비정상(0 이하·범위 밖)이면 기본값을 쓴다. 정차 중 순간 전비가 0 으로 오는 경우 대비.
 * - 잔량을 모르면 rangeKm = null.
 */
object EnergyEstimator {

    // 가정값: Pleos SDK 공개 API 로는 배터리 용량·탱크 크기를 읽을 수 없다(getEvBatteryCapacity 는 @hide).
    // 시연 차량이 정해지면 그 차의 값으로 바꾼다.
    const val EV_BATTERY_CAPACITY_KWH = 77.4
    const val DEFAULT_EV_KM_PER_KWH = 5.0
    const val FUEL_TANK_LITERS = 60.0
    const val DEFAULT_FUEL_L_PER_100KM = 8.0

    private val VALID_EV_KM_PER_KWH = 1.0..15.0
    private val VALID_FUEL_L_PER_100KM = 2.0..30.0

    fun estimate(
        evSocPercent: Int?,
        evCurrentKmPerKWh: Float?,
        evCumulativeKmPerKWh: Float?,
        fuelPercent: Int?,
        fuelLPer100Km: Float?,
    ): EnergyState {
        if (evSocPercent != null) {
            val soc = evSocPercent.coerceIn(0, 100)
            val efficiency = listOf(evCurrentKmPerKWh, evCumulativeKmPerKWh)
                .firstNotNullOfOrNull { it?.toDouble()?.takeIf { v -> v in VALID_EV_KM_PER_KWH } }
                ?: DEFAULT_EV_KM_PER_KWH
            return EnergyState(
                type = PowerType.EV,
                levelPercent = soc.toFloat(),
                rangeKm = EV_BATTERY_CAPACITY_KWH * soc / 100.0 * efficiency,
            )
        }
        if (fuelPercent != null) {
            val pct = fuelPercent.coerceIn(0, 100)
            val lPer100 = fuelLPer100Km?.toDouble()?.takeIf { it in VALID_FUEL_L_PER_100KM }
                ?: DEFAULT_FUEL_L_PER_100KM
            return EnergyState(
                type = PowerType.FUEL,
                levelPercent = pct.toFloat(),
                rangeKm = FUEL_TANK_LITERS * pct / 100.0 / lPer100 * 100.0,
            )
        }
        return EnergyState()
    }
}
