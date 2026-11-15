package com.team.travelmap

import com.team.travelmap.core.model.EnergyState
import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.core.model.PilotScope
import com.team.travelmap.core.model.PowerType
import com.team.travelmap.core.model.Region
import com.team.travelmap.core.model.RegionId
import com.team.travelmap.roulette.RouletteFilter
import com.team.travelmap.roulette.RouletteMode
import com.team.travelmap.roulette.RoulettePicker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class RoulettePickerTest {
    private val seoulCityHall = GeoPoint(37.5663, 126.9779)
    private val gangnam = Region(RegionId("11680"), "서울특별시", "강남구", GeoPoint(37.4966, 127.0630))      // 약 11km
    private val daejeonSeo = Region(RegionId("30170"), "대전광역시", "서구", GeoPoint(36.2805, 127.3417))    // 약 147km
    private val haeundae = Region(RegionId("26350"), "부산광역시", "해운대구", GeoPoint(35.1993, 129.1459)) // 약 327km
    private val suwon = Region(RegionId("41110"), "경기도", "수원시", GeoPoint(37.2901, 127.0120))          // 비활성
    private val regions = listOf(gangnam, daejeonSeo, haeundae, suwon)
    private val picker = RoulettePicker(Random(42))

    private fun ev(rangeKm: Double) = EnergyState(PowerType.EV, 50f, rangeKm)

    @Test fun `시범 지역은 서울 부산 대전만`() {
        assertTrue(PilotScope.isActive(gangnam.id))
        assertTrue(PilotScope.isActive(daejeonSeo.id))
        assertTrue(PilotScope.isActive(haeundae.id))
        assertFalse(PilotScope.isActive(suwon.id))
    }

    @Test fun `전체 랜덤은 시범 지역만 후보로 두고 먼 곳은 충전 필요로 표시`() {
        val c = picker.candidates(regions, emptySet(), seoulCityHall, ev(480.0), RouletteMode.ALL)
        assertEquals(setOf(gangnam, daejeonSeo, haeundae), c.map { it.region }.toSet())
        assertFalse(c.first { it.region == daejeonSeo }.needsRecharge)
        assertTrue(c.first { it.region == haeundae }.needsRecharge)
    }

    @Test fun `배터리 넉넉하면 서울과 대전까지`() {
        val c = picker.candidates(regions, emptySet(), seoulCityHall, ev(480.0), RouletteMode.REACHABLE)
        assertEquals(setOf(gangnam, daejeonSeo), c.map { it.region }.toSet())
    }

    @Test fun `배터리 적으면 서울 안에서만`() {
        val c = picker.candidates(regions, emptySet(), seoulCityHall, ev(90.0), RouletteMode.REACHABLE)
        assertEquals(listOf(gangnam), c.map { it.region })
    }

    @Test fun `다녀온 곳은 제외`() {
        val c = picker.candidates(regions, setOf(gangnam.id), seoulCityHall, ev(480.0), RouletteMode.ALL)
        assertFalse(c.any { it.region == gangnam })
    }

    @Test fun `주행가능거리를 모르면 fallback 거리로 거른다`() {
        val c = picker.candidates(
            regions, emptySet(), seoulCityHall, EnergyState(), RouletteMode.REACHABLE,
            RouletteFilter(roundTrip = false, fallbackRangeKm = 300.0),
        )
        assertEquals(setOf(gangnam, daejeonSeo), c.map { it.region }.toSet())
    }

    @Test fun `후보가 없으면 null`() {
        assertEquals(null, picker.pick(emptyList()))
    }
    @Test fun `잘못된 주행가능거리는 대체 거리로 판단`() {
        for (range in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0)) {
            val c = picker.candidates(regions, emptySet(), seoulCityHall, ev(range),
                RouletteMode.REACHABLE, RouletteFilter(fallbackRangeKm = 90.0))
            assertEquals(listOf(gangnam), c.map { it.region })
        }
    }

    @Test fun `잔여 거리 0은 대체하지 않고 후보에서 제외`() {
        assertTrue(picker.candidates(regions, emptySet(), seoulCityHall, ev(0.0),
            RouletteMode.REACHABLE).isEmpty())
    }

    @Test fun `현재 위치가 잘못되면 추첨하지 않음`() {
        assertTrue(picker.candidates(regions, emptySet(), GeoPoint(Double.NaN, 127.0),
            ev(480.0), RouletteMode.ALL).isEmpty())
    }
}
