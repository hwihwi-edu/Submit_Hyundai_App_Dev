package com.team.travelmap

import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.curation.hot.HotPlaces
import com.team.travelmap.curation.hot.HotPlaces.Answer
import com.team.travelmap.curation.hot.HotPlaces.PlaceRef
import org.junit.Assert.*
import org.junit.Test

class HotPlacesTest {
    private val cafe = PlaceRef("1", "성심당", GeoPoint(36.3276, 127.4273))
    private val park = PlaceRef("2", "테미공원", GeoPoint(36.3150, 127.4210))
    private val places = listOf(cafe, park)
    private fun at(p: PlaceRef, yes: Boolean?, name: String = p.name, dLat: Double = 0.0, sample: Boolean = false) =
        Answer(name, GeoPoint(p.point.latitude + dLat, p.point.longitude), yes, sample = sample)

    @Test fun `3명 이상 응답에 70퍼센트 이상이 다시 오고 싶다면 핫`() {
        val stats = HotPlaces.aggregate(places, listOf(at(cafe, true), at(cafe, true), at(cafe, true), at(cafe, false)))
        val s = stats.getValue("1")
        assertEquals(4, s.answers)
        assertEquals(0.75, s.revisitRate, 1e-9)
        assertTrue(s.isHot)
        assertEquals("또 오고 싶다 75% · 4명", s.label)
    }

    @Test fun `응답이 적거나 비율이 낮으면 핫이 아니다`() {
        assertFalse(HotPlaces.aggregate(places, listOf(at(cafe, true), at(cafe, true))).getValue("1").isHot)
        val low = HotPlaces.aggregate(places, listOf(at(park, true), at(park, false), at(park, false))).getValue("2")
        assertFalse(low.isHot)
    }

    @Test fun `재방문 의사에 답하지 않은 리뷰는 세지 않는다`() {
        val s = HotPlaces.aggregate(places, listOf(at(cafe, true), at(cafe, true), at(cafe, null), at(cafe, true))).getValue("1")
        assertEquals(3, s.answers)
        assertTrue(s.isHot)
    }

    @Test fun `이름이 같으면 300m 안, 이름이 다르면 60m 안 장소로 묶고 나머지는 버린다`() {
        val nearSameName = at(cafe, true, dLat = 0.002)               // 약 220m
        val farSameName = at(cafe, true, dLat = 0.005)                // 약 550m → 버림
        val coordOnly = at(park, true, name = "공원 입구", dLat = 0.0003) // 약 33m
        val stats = HotPlaces.aggregate(places, listOf(nearSameName, farSameName, coordOnly))
        assertEquals(1, stats.getValue("1").answers)
        assertEquals(1, stats.getValue("2").answers)
    }

    @Test fun `예시 응답이 섞이면 예시 포함으로 표시하고, 예시는 장소 id 로 묶는다`() {
        val sample = Answer("성심당 본점", GeoPoint(0.0, 0.0), true, sample = true, placeId = "1")
        val s = HotPlaces.aggregate(places, listOf(sample, sample, at(cafe, true))).getValue("1")
        assertEquals(3, s.answers)
        assertTrue(s.includesSample)
        assertTrue(s.label.endsWith("예시 포함"))
    }

    @Test fun `주차 편함·오래 기다림 비율`() {
        val list = listOf(
            Answer("성심당", cafe.point, true, parking = "EASY", waiting = "LONG"),
            Answer("성심당", cafe.point, true, parking = "HARD", waiting = "NONE"),
            Answer("성심당", cafe.point, false, parking = null, waiting = "LONG"),
        )
        val s = HotPlaces.aggregate(places, list).getValue("1")
        assertEquals(0.5, s.parkingEasyRate!!, 1e-9)
        assertEquals(2.0 / 3, s.waitingLongRate!!, 1e-9)
    }
}
