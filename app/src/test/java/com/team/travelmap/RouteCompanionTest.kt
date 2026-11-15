package com.team.travelmap

import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.core.model.PlaceCategory
import com.team.travelmap.curation.TourismEntry
import com.team.travelmap.curation.companion.*
import com.team.travelmap.story.StoryPoint
import org.junit.Assert.*
import org.junit.Test

class RouteCompanionTest {
    // 대전 근처, 북쪽(위도 증가)으로 20km 가는 경로. 위도 0.009° ≈ 1km.
    private val here = GeoPoint(36.30, 127.40)
    private val target = GeoPoint(36.48, 127.40)
    private fun north(km: Double, eastKm: Double = 0.0) = GeoPoint(36.30 + km * 0.009044, 127.40 + eastKm * 0.011147)
    private val story = "이곳은 오래된 성곽 길을 따라 걷는 산책로로, 봄에는 벚꽃이 피고 가을에는 단풍이 아름답다. 주말에는 시민들이 많이 찾는다."

    private fun cand(id: String, at: GeoPoint, category: PlaceCategory = PlaceCategory.SIGHT, overview: String = story,
                     curated: Boolean = false, audio: String? = null, name: String = "장소$id") =
        CompanionCandidate(id, name, at, category, overview, curated = curated, audioStoryId = audio)

    private fun ctx(now: Long = 0L, at: GeoPoint = here, onHighway: Boolean = false, ic: Int? = null, planned: Set<String> = emptySet()) =
        CompanionContext(now, at, target, planned, onHighway, ic)

    @Test fun `경로 앞쪽 범위 안의 장소를 고른다`() {
        val s = RouteCompanion.suggest(ctx(), listOf(cand("1", north(2.0, 0.3))), CompanionState())
        assertEquals("1", s!!.candidate.id)
        assertEquals(2.0, s.aheadKm, 0.1)
        assertEquals(0.3, s.offRouteKm, 0.1)
    }

    @Test fun `지나간 곳·너무 가까운 곳·너무 먼 곳·옆으로 먼 곳은 고르지 않는다`() {
        val list = listOf(cand("behind", north(-2.0)), cand("tooNear", north(0.2)), cand("tooFar", north(8.0)),
            cand("side", north(2.0, 3.0)))
        assertNull(RouteCompanion.suggest(ctx(), list, CompanionState()))
    }

    @Test fun `경로에 더 가깝고 검수한 장소를 먼저, 음식점은 조금 뒤로`() {
        val list = listOf(cand("far-off", north(2.0, 1.2)), cand("close", north(3.0, 0.1)),
            cand("food", north(2.0, 0.0), category = PlaceCategory.FOOD))
        assertEquals("close", RouteCompanion.suggest(ctx(), list, CompanionState())!!.candidate.id)
        val curated = list + cand("curated", north(3.0, 0.6), curated = true)
        assertEquals("curated", RouteCompanion.suggest(ctx(), curated, CompanionState())!!.candidate.id)
    }

    @Test fun `소개문이 짧거나 이미 코스·보여 줌·거절한 곳은 빼고 충전소도 뺀다`() {
        val list = listOf(cand("short", north(2.0), overview = "짧은 소개"), cand("planned", north(2.0)),
            cand("shown", north(2.5)), cand("declined", north(3.0)), cand("charger", north(3.5), category = PlaceCategory.CHARGER))
        val state = CompanionState(shownIds = setOf("shown"), declinedIds = setOf("declined"))
        assertNull(RouteCompanion.suggest(ctx(planned = setOf("planned")), list, state))
    }

    @Test fun `고속도로에서는 진출 IC 가 3km 안일 때만 묻는다`() {
        val list = listOf(cand("1", north(2.0)))
        assertNull(RouteCompanion.suggest(ctx(onHighway = true, ic = null), list, CompanionState()))
        assertNull(RouteCompanion.suggest(ctx(onHighway = true, ic = 8_000), list, CompanionState()))
        assertNotNull(RouteCompanion.suggest(ctx(onHighway = true, ic = 2_500), list, CompanionState()))
    }

    @Test fun `직전 제안 뒤 10분과 3km 가 모두 지나야 다시 묻는다`() {
        val list = listOf(cand("1", north(5.0)))
        val last = CompanionState(lastPromptAtMillis = 0L, lastPromptPoint = here)
        assertNull(RouteCompanion.suggest(ctx(now = 5 * 60_000L, at = north(4.0)), list, last)) // 5분
        assertNull(RouteCompanion.suggest(ctx(now = 11 * 60_000L, at = north(1.0)), list, last)) // 1km
        assertNotNull(RouteCompanion.suggest(ctx(now = 11 * 60_000L, at = north(3.5)), list, last))
    }

    @Test fun `목표가 1km 안이면 곧 도착이라 묻지 않는다`() {
        val closeTarget = CompanionContext(0L, here, north(0.8))
        assertNull(RouteCompanion.suggest(closeTarget, listOf(cand("1", north(0.6))), CompanionState()))
    }

    @Test fun `우회 시간은 경로 위면 거의 0, 옆으로 떨어지면 늘어난다`() {
        assertEquals(0, RouteCompanion.detourMinutes(here, north(5.0), target))
        val off = RouteCompanion.detourMinutes(here, north(5.0, 1.5), target) // 옆 1.5km: 약 0.3km 더 → 1분
        assertEquals(1, off)
        val farOff = RouteCompanion.detourMinutes(here, north(5.0, 5.0), target) // 옆 5km: 약 3km 더 → 6분
        assertTrue(farOff in 5..7)
        val s = RouteCompanion.suggest(ctx(), listOf(cand("1", north(2.0, 1.0))), CompanionState())!!
        assertEquals(s.detourDriveMinutes + RouteCompanion.SHORT_STOP_MINUTES, s.detourTotalMinutes)
    }

    @Test fun `세션은 한 번 보여 준 곳을 다시 묻지 않고 간격을 지킨다`() {
        val session = RouteCompanionSession(listOf(cand("1", north(2.0)), cand("2", north(7.5))))
        assertNull(session.next(ctx(now = 0L)))                       // 출발 직후는 묻지 않음
        assertEquals("1", session.next(ctx(now = 4 * 60_000L))!!.candidate.id)
        assertNull(session.next(ctx(now = 5 * 60_000L)))               // 간격 10분 전
        // 15분 뒤 4km 더 간 자리: 1번은 지났고 이미 보여 줌 → 2번(3.5km 앞)
        assertEquals("2", session.next(ctx(now = 15 * 60_000L, at = north(4.0)))!!.candidate.id)
        session.declined("2")
        assertTrue("2" in session.state.declinedIds)
    }

    @Test fun `문장 만들기 - 관광지는 짧은 소개 후 묻고 음식점은 묻기만`() {
        val sight = PlaceNarration.of(cand("1", north(2.1), name = "경복궁"), aheadKm = 2.1, driveMinutes = 1)
        assertEquals("경복궁", sight.headline)
        assertEquals("약 2킬로미터 앞에 경복궁이 있어요", sight.intro)
        assertNotNull(sight.brief)
        assertTrue(sight.brief!!.length <= PlaceNarration.MAX_BRIEF_CHARS)
        assertEquals("1분만 돌아가면 돼요. 잠깐 들렀다 갈까요?", sight.question)
        assertTrue(sight.spokenAsk.startsWith("약 2킬로미터 앞에 경복궁이 있어요. "))
        assertTrue(sight.spokenAsk.endsWith("잠깐 들렀다 갈까요?"))

        val food = PlaceNarration.of(cand("2", north(0.6), category = PlaceCategory.FOOD, name = "신화수산활어회타운", audio = "odii"),
            aheadKm = 0.6, driveMinutes = 0)
        assertEquals("바로 앞에 음식점 신화수산활어회타운이 있어요. 가는 길이에요. 잠깐 들렀다 갈까요?", food.spokenAsk)
        assertNull(food.brief)
        assertEquals("", food.story)
        assertNull("음식점은 녹음 이야기도 붙이지 않는다", food.audioStoryId)
        assertFalse(food.hasMoreStory)

        assertEquals("가", PlaceNarration.subjectParticle("성심당 카페"))
        assertEquals("이", PlaceNarration.subjectParticle("경복궁"))
        assertEquals("가", PlaceNarration.subjectParticle("내원사(대전)"))
        assertEquals("을(를)", PlaceNarration.objectParticle("Cafe 1"))
        assertEquals("를", PlaceNarration.objectParticle("대각사(부산)"))
        val long = (1..10).joinToString(" ") { "이것은 ${it}번째 문장으로 장소에 대한 설명을 이어서 적은 것이다." }
        val n = PlaceNarration.of(cand("1", north(1.0), overview = "<p>$long</p>"))
        assertTrue(n.story.length <= PlaceNarration.MAX_STORY_CHARS)
        assertTrue(n.story.endsWith("."))
        assertFalse(n.story.contains("<p>"))
        assertTrue(n.hasMoreStory)
        assertEquals("길은 그대로 · 둘러보기 30분", PlaceNarration.detourLabel(0, 30))
    }

    @Test fun `관광 목록에서 후보 만들기 - 소개문 없는 곳 제외, 근처 녹음 이야기 연결`() {
        fun entry(id: String, type: String, lat: Double) = TourismEntry(id, "곳$id", "30140", type, lat, 127.40, "주소", "", "", "테스트")
        val entries = listOf(entry("a", "12", 36.32), entry("b", "12", 36.34), entry("c", "32", 36.36), entry("d", "39", 36.38))
        val overviews = mapOf("a" to story, "b" to "", "c" to story, "d" to story)
        val stories = listOf(StoryPoint("s1", "이야기", GeoPoint(36.3205, 127.40), "stories/s1.mp3"))
        val out = companionCandidates(entries, { overviews[it].orEmpty() }, curatedIds = setOf("d"), stories = stories)
        assertEquals(listOf("a", "d"), out.map { it.id }) // b 소개문 없음, c 숙박(32)은 경유지 유형 아님
        assertEquals("s1", out.first { it.id == "a" }.audioStoryId)
        assertTrue(out.first { it.id == "d" }.curated)
        assertEquals(PlaceCategory.FOOD, out.first { it.id == "d" }.category)
    }

    @Test fun `첫 제안은 출발하고 3분이 지나거나 1500m 를 간 뒤부터`() {
        val list = listOf(cand("1", north(3.0)))
        val started = CompanionState(startedAtMillis = 0L, startPoint = here)
        assertNull(RouteCompanion.suggest(ctx(now = 60_000L, at = north(0.5)), list, started))          // 1분, 0.5km
        assertNotNull(RouteCompanion.suggest(ctx(now = 3 * 60_000L, at = north(0.5)), list, started))  // 3분
        assertNotNull(RouteCompanion.suggest(ctx(now = 60_000L, at = north(1.6)), list, started))      // 1.6km
    }
}
