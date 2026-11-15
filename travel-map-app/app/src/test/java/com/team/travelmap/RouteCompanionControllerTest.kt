package com.team.travelmap

import com.team.travelmap.core.model.*
import com.team.travelmap.core.trip.TripLegs
import com.team.travelmap.core.trip.TripState
import com.team.travelmap.curation.companion.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RouteCompanionControllerTest {
    private val here = GeoPoint(36.30, 127.40)
    private fun north(km: Double) = GeoPoint(36.30 + km * 0.009044, 127.40)
    private val dest = Place("목적지", north(20.0), PlaceCategory.SIGHT, RegionId("30140"))
    private val region = Region(RegionId("30140"), "대전광역시", "중구", north(20.0))
    private val story = "이곳은 오래된 성곽 길을 따라 걷는 산책로로, 봄에는 벚꽃이 피고 가을에는 단풍이 아름답다. 주말에는 시민들이 많이 찾는다."
    private val cands = listOf(CompanionCandidate("a", "앞쪽 공원", north(2.0), PlaceCategory.SIGHT, story, regionId = RegionId("30140")))

    private class Fixture(val scope: TestScope, val trip: MutableStateFlow<TripState>, val loc: MutableStateFlow<GeoPoint?>,
                          val enabled: MutableStateFlow<Boolean>, val c: RouteCompanionController)

    private fun fixture(): Fixture {
        val scope = TestScope(UnconfinedTestDispatcher())
        val trip = MutableStateFlow<TripState>(TripState.Idle)
        val loc = MutableStateFlow<GeoPoint?>(null)
        val enabled = MutableStateFlow(true)
        val c = RouteCompanionController(scope, trip, loc, MutableStateFlow(false), MutableStateFlow(null), enabled,
            loadCandidates = { cands }, now = { scope.testScheduler.currentTime },
            loadDispatcher = UnconfinedTestDispatcher(scope.testScheduler))
        return Fixture(scope, trip, loc, enabled, c)
    }

    private fun guiding() = TripState.Guiding(region, dest, emptyList(), TripLegs(dest, emptyList()))

    /** 안내 시작 → 출발 직후(묻지 않음) → 3분 뒤 조금 움직여 첫 제안이 나오게 한다. */
    private fun Fixture.startAndWait() {
        trip.value = guiding(); loc.value = here; scope.runCurrent()
        assertNull(c.suggestion.value)
        scope.advanceTimeBy(3 * 60_000L + 1); loc.value = north(0.05); scope.runCurrent()
    }

    @Test fun `안내 중 위치가 들어오면 앞쪽 장소를 제안하고 괜찮아요로 닫힌다`() {
        val f = fixture()
        f.startAndWait()
        val s = f.c.suggestion.value
        assertEquals("a", s!!.candidate.id)
        assertEquals("앞쪽 공원", s.narration.headline)
        f.c.decline()
        assertNull(f.c.suggestion.value)
        // 거절한 곳은 다시 묻지 않는다
        f.loc.value = north(0.2); f.scope.runCurrent()
        assertNull(f.c.suggestion.value)
    }

    @Test fun `답이 없으면 45초 뒤 저절로 닫히고, 이야기를 펼치면 더 오래 둔다`() {
        val f = fixture()
        f.startAndWait()
        assertNotNull(f.c.suggestion.value)
        f.c.openStory()
        assertTrue(f.c.storyOpen.value)
        f.scope.advanceTimeBy(RouteCompanionController.AUTO_DISMISS_MS + 1); f.scope.runCurrent()
        assertNotNull(f.c.suggestion.value)
        f.scope.advanceTimeBy(RouteCompanionController.STORY_OPEN_DISMISS_MS); f.scope.runCurrent()
        assertNull(f.c.suggestion.value)
    }

    @Test fun `설정이 꺼져 있거나 안내 중이 아니면 묻지 않는다`() {
        val f = fixture()
        f.loc.value = here; f.scope.runCurrent()
        assertNull(f.c.suggestion.value)               // Idle
        f.enabled.value = false; f.trip.value = guiding(); f.scope.runCurrent()
        assertNull(f.c.suggestion.value)               // 설정 꺼짐
    }

    @Test fun `잠깐 들르기는 장소를 돌려주고 경유지로 넣을 Place 를 만든다`() {
        val f = fixture()
        f.startAndWait()
        val s = f.c.accept()!!
        assertNull(f.c.suggestion.value)
        val p = f.c.placeOf(s)
        assertEquals("앞쪽 공원", p.name)
        assertEquals(RegionId("30140"), p.regionId)
    }
}
