package com.team.travelmap

import com.team.travelmap.core.model.Gear
import com.team.travelmap.core.model.RegionId
import com.team.travelmap.core.trip.IcPassDetector
import com.team.travelmap.core.trip.IcTurn
import com.team.travelmap.core.trip.ParkLog
import com.team.travelmap.core.trip.TripSnapshot
import com.team.travelmap.core.trip.TripState
import com.team.travelmap.core.trip.UpcomingTurn
import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.core.model.Place
import com.team.travelmap.core.model.PlaceCategory
import com.team.travelmap.core.model.Region
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParkLogTest {
    private val jung = RegionId("30140")
    private val yuseong = RegionId("30200")

    /** 출발해서(D) P 를 넣었을 때 기록해야 하는지. */
    private fun parkAfterDrive(log: ParkLog = ParkLog()): Pair<ParkLog, Boolean> =
        log.onGear(Gear.DRIVE).first.onGear(Gear.PARK)

    @Test fun `출발 전 P 는 세지 않고, 움직인 뒤의 P 는 센다`() {
        assertFalse(ParkLog().onGear(Gear.PARK).second)
        assertTrue(parkAfterDrive().second)
    }

    @Test fun `고속도로 위 P 는 세지 않고 IC 진출 뒤에는 다시 센다`() {
        val onHighway = ParkLog().onGear(Gear.DRIVE).first.onIc(IcTurn.ENTER)
        assertFalse(onHighway.onGear(Gear.PARK).second)
        assertTrue(onHighway.onIc(IcTurn.EXIT).onGear(Gear.PARK).second)
    }

    @Test fun `같은 구는 한 번만 담고 목적지 구는 따로 뺀다`() {
        val log = ParkLog().add(jung).add(yuseong).add(jung)
        assertEquals(listOf(jung, yuseong), log.regions)
        assertEquals(listOf(jung), log.regionsExcept(yuseong))
    }

    @Test fun `가까운 IC 진입 안내가 사라지면 진입한 것으로 본다`() {
        val d = IcPassDetector()
        assertNull(d.onTurns(listOf(UpcomingTurn(IcTurn.ENTER, 800.0))))
        assertNull(d.onTurns(listOf(UpcomingTurn(IcTurn.ENTER, 250.0))))
        assertNull(d.onTurns(listOf(UpcomingTurn(IcTurn.ENTER, 30.0))))
        assertEquals(IcTurn.ENTER, d.onTurns(listOf(UpcomingTurn(null, 12_000.0))))
        // 다음 IC 진출도 같은 방식
        assertNull(d.onTurns(listOf(UpcomingTurn(IcTurn.EXIT, 100.0))))
        assertEquals(IcTurn.EXIT, d.onTurns(emptyList()))
    }

    @Test fun `멀리 있던 IC 안내가 사라진 것은 경로 변경이라 지난 것으로 보지 않는다`() {
        val d = IcPassDetector()
        d.onTurns(listOf(UpcomingTurn(IcTurn.ENTER, 2_000.0)))
        assertNull(d.onTurns(listOf(UpcomingTurn(null, 500.0))))
    }

    @Test fun `연달아 있는 다른 IC 로 바뀌면 앞 IC 를 지난 것으로 본다`() {
        val d = IcPassDetector()
        d.onTurns(listOf(UpcomingTurn(IcTurn.EXIT, 40.0)))
        // 진출 직후 바로 다른 고속도로 진입 안내(거리가 크게 늘어남)
        assertEquals(IcTurn.EXIT, d.onTurns(listOf(UpcomingTurn(IcTurn.ENTER, 900.0))))
    }

    @Test fun `경로가 새로 시작되면 이전 안내는 잊는다`() {
        val d = IcPassDetector()
        d.onTurns(listOf(UpcomingTurn(IcTurn.ENTER, 50.0)))
        d.reset()
        assertNull(d.onTurns(listOf(UpcomingTurn(null, 300.0))))
    }

    @Test fun `주차한 구 기록을 저장했다가 복원한다`() {
        val region = Region(jung, "대전광역시", "중구", GeoPoint(36.32, 127.42))
        val dest = Place("목적지", GeoPoint(36.3, 127.4), PlaceCategory.SIGHT, jung)
        val log = ParkLog(departed = true, onHighway = true, regions = listOf(yuseong))
        val snap = TripSnapshot.fromJson(TripSnapshot.of(TripState.Guiding(region, dest), false, 0, null, null, log)!!.toJson())!!
        assertEquals(log, snap.toParkLog())
    }
}
