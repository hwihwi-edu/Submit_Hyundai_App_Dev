package com.team.travelmap

import com.team.travelmap.core.model.Gear
import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.core.model.Place
import com.team.travelmap.core.model.PlaceCategory
import com.team.travelmap.core.trip.TripLegs
import com.team.travelmap.core.trip.WaypointReview
import com.team.travelmap.core.trip.WaypointReviewRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WaypointReviewTest {
    private fun place(name: String) = Place(name, GeoPoint(36.0, 127.0), PlaceCategory.SIGHT)
    private val wps = listOf(place("1번"), place("2번"))
    private val start = TripLegs(place("목적지"), wps)
    private val parkedAtFirst = start.onWaypointArrived().onGear(Gear.PARK).first

    @Test fun `경유지에 주차하면 그 경유지 리뷰를 요청한다`() {
        assertEquals(WaypointReviewRequest(wps[0], 0), WaypointReview.request(parkedAtFirst, true, null))
    }

    @Test fun `설정이 꺼져 있으면 요청하지 않는다`() {
        assertNull(WaypointReview.request(parkedAtFirst, false, null))
    }

    @Test fun `도착만 하고 주차하지 않았으면 요청하지 않는다`() {
        assertNull(WaypointReview.request(start.onWaypointArrived(), true, null))
    }

    @Test fun `같은 경유지에서 닫았으면 다시 요청하지 않는다`() {
        assertNull(WaypointReview.request(parkedAtFirst, true, dismissedIndex = 0))
    }

    @Test fun `다시 출발하면 사라지고 다음 경유지에서는 다시 요청한다`() {
        val departed = parkedAtFirst.onGear(Gear.DRIVE).first
        assertNull(WaypointReview.request(departed, true, dismissedIndex = 0))
        val parkedAtSecond = departed.onWaypointArrived().onGear(Gear.PARK).first
        assertEquals(WaypointReviewRequest(wps[1], 1), WaypointReview.request(parkedAtSecond, true, dismissedIndex = 0))
    }

    @Test fun `안내 중이 아니면 요청하지 않는다`() {
        assertNull(WaypointReview.request(null, true, null))
    }
}
