package com.team.travelmap

import com.team.travelmap.core.model.PowerType
import com.team.travelmap.vehicle.EnergyEstimator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EnergyEstimatorTest {

    private fun ev(soc: Int?, current: Float? = null, cumulative: Float? = null) =
        EnergyEstimator.estimate(soc, current, cumulative, fuelPercent = null, fuelLPer100Km = null)

    @Test fun `전기차 - 현재 전비로 계산`() {
        val s = ev(soc = 50, current = 6f)
        assertEquals(PowerType.EV, s.type)
        assertEquals(50f, s.levelPercent)
        assertEquals(77.4 * 0.5 * 6.0, s.rangeKm!!, 0.01)
    }

    @Test fun `전기차 - 정차 중 현재 전비 0 이면 누적 전비 사용`() {
        assertEquals(77.4 * 0.8 * 4.5, ev(soc = 80, current = 0f, cumulative = 4.5f).rangeKm!!, 0.01)
    }

    @Test fun `전기차 - 전비를 모르면 기본값 사용`() {
        assertEquals(77.4 * 1.0 * 5.0, ev(soc = 100).rangeKm!!, 0.01)
    }

    @Test fun `전기차 - 잔량 범위 밖 값은 0~100 으로 자름`() {
        assertEquals(0.0, ev(soc = -5, current = 6f).rangeKm!!, 0.0)
        assertEquals(100f, ev(soc = 130, current = 6f).levelPercent)
    }

    @Test fun `내연기관 - 연비로 계산`() {
        val s = EnergyEstimator.estimate(null, null, null, fuelPercent = 50, fuelLPer100Km = 10f)
        assertEquals(PowerType.FUEL, s.type)
        assertEquals(60.0 * 0.5 / 10.0 * 100.0, s.rangeKm!!, 0.01)
    }

    @Test fun `잔량을 모르면 주행가능거리 null`() {
        val s = EnergyEstimator.estimate(null, 6f, null, null, 8f)
        assertEquals(PowerType.UNKNOWN, s.type)
        assertNull(s.rangeKm)
    }
}
