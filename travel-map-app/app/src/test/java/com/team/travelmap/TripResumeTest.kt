package com.team.travelmap

import com.team.travelmap.core.model.Gear
import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.core.model.Place
import com.team.travelmap.core.model.PlaceCategory
import com.team.travelmap.core.model.Region
import com.team.travelmap.core.model.RegionId
import com.team.travelmap.core.trip.TripLegs
import com.team.travelmap.core.trip.TripResume
import com.team.travelmap.core.trip.TripSnapshot
import com.team.travelmap.core.trip.TripState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripResumeTest {
    private fun place(name: String) = Place(name, GeoPoint(36.0, 127.0), PlaceCategory.SIGHT, RegionId("30110"), "주소", null, "설명")
    private val dest = place("목적지")
    private val wps = listOf(place("1번"), place("2번"))
    private val parkedAtFirst = TripLegs(dest, wps).onWaypointArrived().onGear(Gear.PARK).first

    @Test fun `경유지에 주차한 채로 시동을 껐다 켜면 다음 경유지로 계속할지 묻는다`() {
        val r = TripResume.request(parkedAtFirst, ignitionWasOffAtStop = true, enabled = true, nowMillis = 1_000)!!
        assertEquals(wps[1], r.next)
        assertFalse(r.toDestination)
        assertEquals(1, r.remainingWaypoints)
        assertEquals(1_000 + TripResume.AUTO_CONTINUE_MS, r.autoContinueAtMillis)
    }

    @Test fun `마지막 경유지에서는 목적지로 계속할지 묻는다`() {
        val parkedAtLast = parkedAtFirst.departFromWaypoint().onWaypointArrived().onGear(Gear.PARK).first
        val r = TripResume.request(parkedAtLast, true, true, 0)!!
        assertEquals(dest, r.next)
        assertTrue(r.toDestination)
        assertEquals(0, r.remainingWaypoints)
    }

    @Test fun `설정이 꺼져 있거나 시동을 끄지 않았으면 묻지 않는다`() {
        assertNull(TripResume.request(parkedAtFirst, ignitionWasOffAtStop = true, enabled = false, nowMillis = 0))
        assertNull(TripResume.request(parkedAtFirst, ignitionWasOffAtStop = false, enabled = true, nowMillis = 0))
    }

    @Test fun `경유지에 주차하지 않았으면 묻지 않는다`() {
        assertNull(TripResume.request(TripLegs(dest, wps), true, true, 0))
        assertNull(TripResume.request(TripLegs(dest, wps).onWaypointArrived(), true, true, 0))
    }

    @Test fun `계속하면 다음 구간으로 넘어간다`() {
        val next = parkedAtFirst.departFromWaypoint()
        assertEquals(listOf(wps[1]), next.naviWaypoints())
        assertFalse(next.stoppedAtWaypoint)
    }

    @Test fun `안내 중인 여행을 저장했다가 그대로 복원한다`() {
        val region = Region(RegionId("30110"), "대전광역시", "동구", GeoPoint(36.31, 127.45))
        val state = TripState.Guiding(region, dest, wps, parkedAtFirst)
        val json = TripSnapshot.of(state, ignitionOffAtStop = true, startedAt = 123L, startOdometerKm = 12.5, dismissedReviewIndex = 0)!!.toJson()
        val snap = TripSnapshot.fromJson(json)!!
        assertEquals(state, snap.toState())
        assertTrue(snap.ignitionOffAtStop)
        assertEquals(123L, snap.startedAt)
        assertEquals(12.5, snap.startOdometerKm!!, 0.0)
        assertEquals(0, snap.dismissedReviewIndex)
    }

    @Test fun `대기·완료 상태는 저장하지 않고 깨진 저장값은 무시한다`() {
        assertNull(TripSnapshot.of(TripState.Idle, false, 0, null, null))
        assertNull(TripSnapshot.fromJson("{깨진 값"))
        assertNull(TripSnapshot.fromJson(null))
    }
}
