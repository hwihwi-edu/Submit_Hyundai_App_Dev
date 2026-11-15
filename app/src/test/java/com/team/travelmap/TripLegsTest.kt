package com.team.travelmap

import com.team.travelmap.core.model.Gear
import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.core.model.Place
import com.team.travelmap.core.model.PlaceCategory
import com.team.travelmap.core.trip.TripLegs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripLegsTest {
    private fun place(name: String) = Place(name, GeoPoint(36.0, 127.0), PlaceCategory.SIGHT)
    private val dest = place("목적지")
    private val wps = listOf(place("1번"), place("2번"), place("3번"), place("4번"))

    /** 경유지 도착 → P → D 를 한 번 거친다. */
    private fun TripLegs.visitAndDepart(): Pair<TripLegs, Boolean> {
        val parked = onWaypointArrived().onGear(Gear.PARK).first
        return parked.onGear(Gear.DRIVE)
    }

    @Test fun `처음에는 1번 경유지와 목적지만 내비에 넘긴다`() {
        val legs = TripLegs(dest, wps)
        assertEquals(listOf(wps[0]), legs.naviWaypoints())
        assertEquals(4, legs.remainingWaypoints.size)
    }

    @Test fun `경유지 도착 후 주차하고 다시 출발하면 다음 경유지로 넘어간다`() {
        val (next, request) = TripLegs(dest, wps).visitAndDepart()
        assertTrue(request)
        assertEquals(listOf(wps[1]), next.naviWaypoints())
        assertFalse(next.arrivedAtWaypoint)
        assertFalse(next.parkedAtWaypoint)
    }

    @Test fun `경유지 4곳을 모두 들르면 마지막 구간은 목적지만 넘긴다`() {
        var legs = TripLegs(dest, wps)
        repeat(4) { legs = legs.visitAndDepart().first }
        assertNull(legs.nextWaypoint)
        assertTrue(legs.naviWaypoints().isEmpty())
    }

    @Test fun `주차하지 않고 지나치면 넘어가지 않는다`() {
        val (legs, request) = TripLegs(dest, wps).onWaypointArrived().onGear(Gear.DRIVE)
        assertFalse(request)
        assertEquals(listOf(wps[0]), legs.naviWaypoints())
    }

    @Test fun `경유지 도착 전의 주차·출발은 무시한다`() {
        val parked = TripLegs(dest, wps).onGear(Gear.PARK).first
        val (legs, request) = parked.onGear(Gear.DRIVE)
        assertFalse(request)
        assertFalse(legs.parkedAtWaypoint)
        assertEquals(0, legs.nextIndex)
    }

    @Test fun `경유지에 주차해 있는 동안 stoppedAtWaypoint 가 참`() {
        val parked = TripLegs(dest, wps).onWaypointArrived().onGear(Gear.PARK).first
        assertTrue(parked.stoppedAtWaypoint)
        assertFalse(parked.onGear(Gear.DRIVE).first.stoppedAtWaypoint)
    }

    @Test fun `경유지가 없으면 도착 이벤트를 무시한다`() {
        val legs = TripLegs(dest, emptyList()).onWaypointArrived()
        assertFalse(legs.arrivedAtWaypoint)
        assertTrue(legs.naviWaypoints().isEmpty())
    }

    @Test fun `달리는 중 끼운 장소는 지금 향하던 곳 앞, 다음 경유지가 된다`() {
        val extra = place("잠깐")
        val legs = TripLegs(dest, wps).visitAndDepart().first   // 1번 지나 2번으로 가는 중
        val inserted = legs.insertNext(extra)!!
        assertEquals(extra, inserted.nextWaypoint)
        assertEquals(listOf(wps[0], extra, wps[1], wps[2], wps[3]), inserted.waypoints)
        assertEquals(listOf(extra), inserted.naviWaypoints())
        // 끼운 곳에 들렀다 다시 출발하면 원래 가던 2번으로
        assertEquals(wps[1], inserted.visitAndDepart().first.nextWaypoint)
    }

    @Test fun `경유지에 도착·주차 중이면 끼우지 않는다`() {
        val arrived = TripLegs(dest, wps).onWaypointArrived()
        assertNull(arrived.insertNext(place("잠깐")))
        assertNull(arrived.onGear(Gear.PARK).first.insertNext(place("잠깐")))
    }

    @Test fun `마지막 구간(목적지로 가는 중)에도 끼울 수 있다`() {
        val last = TripLegs(dest, emptyList())
        val inserted = last.insertNext(place("잠깐"))!!
        assertEquals("잠깐", inserted.nextWaypoint!!.name)
        assertEquals(dest, inserted.placeAfterStop)
    }
}
