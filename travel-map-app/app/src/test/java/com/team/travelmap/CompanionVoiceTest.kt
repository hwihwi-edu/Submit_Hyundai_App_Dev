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

/** 3번 음성 해설: 동행 카드 질문·이야기 읽기, 곧 도착 안내. */
@OptIn(ExperimentalCoroutinesApi::class)
class CompanionVoiceTest {
    private class FakeNarrator : PlaceNarrator {
        val said = mutableListOf<String>()
        var stops = 0
        override fun speak(text: String, onDone: (() -> Unit)?): Boolean { said += text; onDone?.invoke(); return true }
        override fun stop() { stops++ }
    }

    private val here = GeoPoint(36.30, 127.40)
    private fun north(km: Double) = GeoPoint(36.30 + km * 0.009044, 127.40)
    private val region = Region(RegionId("30140"), "대전광역시", "중구", north(20.0))
    private val story = "이곳은 오래된 성곽 길을 따라 걷는 산책로로, 봄에는 벚꽃이 피고 가을에는 단풍이 아름답다. 주말에는 시민들이 많이 찾는다."

    private fun guiding(dest: Place, waypoints: List<Place> = emptyList(), legs: TripLegs = TripLegs(dest, waypoints)) =
        TripState.Guiding(region, dest, waypoints, legs)

    // ---------- 동행 카드 ----------

    private fun companion(scope: TestScope, voice: Boolean, audioStoryId: String? = null, storyPlaying: Boolean = false,
                          dest: Place = Place("목적지", north(20.0), PlaceCategory.SIGHT)): Triple<RouteCompanionController, FakeNarrator, Pair<MutableStateFlow<TripState>, MutableStateFlow<GeoPoint?>>> {
        val n = FakeNarrator()
        val trip = MutableStateFlow<TripState>(TripState.Idle)
        val loc = MutableStateFlow<GeoPoint?>(null)
        val cands = listOf(CompanionCandidate("a", "앞쪽 공원", north(2.0), PlaceCategory.SIGHT, story, audioStoryId = audioStoryId))
        val c = RouteCompanionController(scope, trip, loc, MutableStateFlow(false), MutableStateFlow(null), MutableStateFlow(true),
            loadCandidates = { cands }, now = { scope.testScheduler.currentTime },
            loadDispatcher = UnconfinedTestDispatcher(scope.testScheduler),
            narrator = n, voiceEnabled = MutableStateFlow(voice), isStoryPlaying = { storyPlaying })
        trip.value = guiding(dest); loc.value = here; scope.runCurrent()
        scope.advanceTimeBy(3 * 60_000L + 1); loc.value = north(0.05); scope.runCurrent()
        return Triple(c, n, trip to loc)
    }

    @Test fun `카드가 뜨면 질문을 읽고 이야기 듣기에서 소개 글을 읽는다`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val (c, n) = companion(scope, voice = true)
        assertNotNull(c.suggestion.value)
        assertEquals(listOf(c.suggestion.value!!.narration.spokenAsk), n.said)   // 말로 대답하기가 꺼져 있으면 대답 안내 없음
        assertTrue(n.said[0].startsWith("약 2킬로미터 앞에 앞쪽 공원이 있어요. 이곳은 오래된"))
        c.openStory()
        assertEquals(c.suggestion.value!!.narration.story + " " + RouteCompanionController.ASK_AGAIN, n.said.last())
        c.decline()
        assertTrue("닫으면 읽던 말을 멈춘다", n.stops > 0)
    }

    @Test fun `음성 해설이 꺼져 있으면 읽지 않는다`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val (c, n) = companion(scope, voice = false)
        assertNotNull(c.suggestion.value)
        c.openStory()
        assertTrue(n.said.isEmpty())
    }

    @Test fun `녹음 이야기가 있는 곳은 소개 글 대신 녹음을 틀도록 읽기를 멈춘다`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val (c, n) = companion(scope, voice = true, audioStoryId = "odii-1")
        c.openStory()
        assertEquals(1, n.said.size)
        assertEquals(1, n.stops)
    }

    @Test fun `녹음 이야기가 재생 중이면 질문을 소리로 읽지 않는다`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val (c, n) = companion(scope, voice = true, storyPlaying = true)
        assertNotNull(c.suggestion.value)
        assertTrue(n.said.isEmpty())
    }

    @Test fun `목적지 가까이에서는 새로 묻지 않는다`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        // 목적지가 1.5km 앞(곧 도착 안내 자리) → 동행 제안 없음
        val (c) = companion(scope, voice = true, dest = Place("목적지", north(1.5), PlaceCategory.SIGHT))
        assertNull(c.suggestion.value)
    }

    @Test fun `곧 도착 안내가 떠 있으면 동행 카드를 닫고 묻지 않는다`() {
        val scope = TestScope(UnconfinedTestDispatcher())
        val n = FakeNarrator()
        val trip = MutableStateFlow<TripState>(TripState.Idle)
        val loc = MutableStateFlow<GeoPoint?>(null)
        val blocked = MutableStateFlow(false)
        val cands = listOf(CompanionCandidate("a", "앞쪽 공원", north(2.0), PlaceCategory.SIGHT, story))
        val c = RouteCompanionController(scope, trip, loc, MutableStateFlow(false), MutableStateFlow(null), MutableStateFlow(true),
            loadCandidates = { cands }, now = { scope.testScheduler.currentTime },
            loadDispatcher = UnconfinedTestDispatcher(scope.testScheduler),
            narrator = n, voiceEnabled = MutableStateFlow(true), blocked = blocked)
        trip.value = guiding(Place("목적지", north(20.0), PlaceCategory.SIGHT)); loc.value = here; scope.runCurrent()
        scope.advanceTimeBy(3 * 60_000L + 1); loc.value = north(0.05); scope.runCurrent()
        assertNotNull(c.suggestion.value)
        blocked.value = true; scope.runCurrent()
        assertNull("곧 도착 안내가 뜨면 동행 카드는 닫힌다", c.suggestion.value)
        assertEquals("곧 도착 안내의 말을 끊지 않는다", 0, n.stops)
        loc.value = north(0.1); scope.runCurrent()
        assertNull(c.suggestion.value)
    }

    @Test fun `음식점 경유지는 곧 도착 안내에서 소개를 읽지 않는다`() {
        val n = ApproachNotice.of(Place("개미집", here, PlaceCategory.FOOD), isDestination = false, description = "낙지볶음으로 유명한 집이다. 오래됐다.")
        assertNull(n.story)
        assertEquals("곧 경유지 개미집에 도착해요", n.spoken)
    }

    // ---------- 말로 대답하기 ----------

    private class FakeAnswers : AnswerListener {
        var pending: ((String?) -> Unit)? = null
        var listens = 0
        var cancels = 0
        override fun listen(timeoutMs: Long, onResult: (String?) -> Unit): Boolean { listens++; pending = onResult; return true }
        override fun cancel() { cancels++; pending = null }
        fun answer(text: String?) { val cb = pending; pending = null; cb?.invoke(text) }
    }

    private class Talk(val c: RouteCompanionController, val n: FakeNarrator, val a: FakeAnswers, val added: MutableList<Place>)

    private fun talk(answerOn: Boolean = true, addOk: Boolean = true, audio: String? = null): Talk {
        val scope = TestScope(UnconfinedTestDispatcher())
        val n = FakeNarrator(); val a = FakeAnswers(); val added = mutableListOf<Place>()
        val trip = MutableStateFlow<TripState>(TripState.Idle)
        val loc = MutableStateFlow<GeoPoint?>(null)
        val cands = listOf(CompanionCandidate("a", "앞쪽 공원", north(2.0), PlaceCategory.SIGHT, story + " " + story + " " + story, audioStoryId = audio))
        val c = RouteCompanionController(scope, trip, loc, MutableStateFlow(false), MutableStateFlow(null), MutableStateFlow(true),
            loadCandidates = { cands }, now = { scope.testScheduler.currentTime },
            loadDispatcher = UnconfinedTestDispatcher(scope.testScheduler),
            narrator = n, voiceEnabled = MutableStateFlow(true), answers = a, answerEnabled = MutableStateFlow(answerOn),
            addStop = { p -> if (addOk) { added += p; Result.success(Unit) } else Result.failure(IllegalStateException()) })
        trip.value = guiding(Place("목적지", north(20.0), PlaceCategory.SIGHT)); loc.value = here; scope.runCurrent()
        scope.advanceTimeBy(3 * 60_000L + 1); loc.value = north(0.05); scope.runCurrent()
        return Talk(c, n, a, added)
    }

    @Test fun `대답 알아듣기`() {
        assertEquals(VoiceAnswer.YES, VoiceAnswerParser.parse("응"))
        assertEquals(VoiceAnswer.YES, VoiceAnswerParser.parse("네 들렀다 갈게요"))
        assertEquals(VoiceAnswer.YES, VoiceAnswerParser.parse("좋아 가자"))
        // 가상 기기에서 "응, 들렀다 갈래"를 엔진이 "나갈래"로 들은 실제 결과
        assertEquals(VoiceAnswer.YES, VoiceAnswerParser.parse("나갈래"))
        assertEquals(VoiceAnswer.YES, VoiceAnswerParser.parse("들렀다"))
        assertEquals(VoiceAnswer.NO, VoiceAnswerParser.parse("아니 괜찮아"))
        assertEquals(VoiceAnswer.NO, VoiceAnswerParser.parse("안 들를래"))
        assertEquals(VoiceAnswer.NO, VoiceAnswerParser.parse("다음에"))
        assertEquals(VoiceAnswer.STORY, VoiceAnswerParser.parse("이야기 들려줘"))
        assertEquals(VoiceAnswer.STORY, VoiceAnswerParser.parse("어떤 곳이야?"))
        assertNull(VoiceAnswerParser.parse("오늘 날씨 덥네"))
        assertNull(VoiceAnswerParser.parse(""))
        assertNull(VoiceAnswerParser.parse(null))
    }

    @Test fun `질문을 다 읽으면 듣고 응이면 경유지로 넣는다`() {
        val t = talk()
        assertTrue("처음 물을 때 대답하는 법을 알려 준다", t.n.said[0].endsWith(RouteCompanionController.ANSWER_GUIDE))
        assertEquals(1, t.a.listens)
        assertTrue(t.c.listening.value)
        t.a.answer("응 들를래")
        assertEquals(listOf("앞쪽 공원"), t.added.map { it.name })
        assertNull(t.c.suggestion.value)
        assertFalse(t.c.listening.value)
        assertEquals("앞쪽 공원을 경유지로 넣었어요.", t.n.said.last())
    }

    @Test fun `괜찮아면 닫고 이야기면 소개를 읽고 다시 묻는다`() {
        val t = talk()
        t.a.answer("이야기 들려줘")
        assertTrue(t.c.storyOpen.value)
        assertTrue(t.n.said.last().endsWith(RouteCompanionController.ASK_AGAIN))
        assertEquals("소개를 읽은 뒤 다시 듣는다", 2, t.a.listens)
        t.a.answer("아니 괜찮아")
        assertNull(t.c.suggestion.value)
        assertEquals(RouteCompanionController.OK_NEXT_TIME, t.n.said.last())
        assertTrue(t.added.isEmpty())
    }

    @Test fun `못 알아들으면 카드는 그대로, 경유지를 못 넣으면 알려 준다`() {
        val t = talk(addOk = false)
        t.a.answer("음 글쎄")
        assertNotNull(t.c.suggestion.value)
        assertFalse(t.c.listening.value)
        val t2 = talk(addOk = false)
        t2.a.answer("응")
        assertEquals(RouteCompanionController.CANNOT_ADD, t2.n.said.last())
    }

    @Test fun `말로 대답하기가 꺼져 있으면 듣지 않는다`() {
        val t = talk(answerOn = false)
        assertEquals(0, t.a.listens)
        assertFalse(t.c.listening.value)
    }

    // ---------- 곧 도착 안내 ----------

    private class Approach(val scope: TestScope, val trip: MutableStateFlow<TripState>, val loc: MutableStateFlow<GeoPoint?>,
                           val voice: MutableStateFlow<Boolean>, val n: FakeNarrator, val a: StopApproachAnnouncer)

    private fun approach(describe: suspend (Place) -> String? = { "1993년 대전 엑스포 때 세운 탑이다. 전망대에서 갑천이 내려다보인다." }): Approach {
        val scope = TestScope(UnconfinedTestDispatcher())
        val trip = MutableStateFlow<TripState>(TripState.Idle)
        val loc = MutableStateFlow<GeoPoint?>(null)
        val voice = MutableStateFlow(true)
        val n = FakeNarrator()
        val a = StopApproachAnnouncer(scope, trip, loc, voice, n, describe, now = { scope.testScheduler.currentTime })
        return Approach(scope, trip, loc, voice, n, a)
    }

    @Test fun `경유지 2km 안에 들어오면 한 번 소개하고 목적지에서 다시 소개한다`() {
        val f = approach()
        val wp = Place("한빛탑", north(10.0), PlaceCategory.SIGHT)
        val dest = Place("대전엑스포과학공원", north(20.0), PlaceCategory.SIGHT)
        f.trip.value = guiding(dest, listOf(wp)); f.loc.value = here; f.scope.runCurrent()
        assertNull(f.a.notice.value)

        f.loc.value = north(8.5); f.scope.runCurrent()
        val n = f.a.notice.value!!
        assertFalse(n.isDestination)
        assertEquals("곧 경유지 한빛탑에 도착해요", n.headline)
        assertEquals(1, f.n.said.size)
        assertTrue(f.n.said[0].startsWith("곧 경유지 한빛탑에 도착해요. 1993년"))

        // 같은 곳은 다시 알리지 않는다
        f.a.dismiss(); f.loc.value = north(9.5); f.scope.runCurrent()
        assertNull(f.a.notice.value)
        assertEquals(1, f.n.said.size)

        // 경유지를 지나 다음 구간(목적지)으로
        f.scope.advanceTimeBy(StopApproachAnnouncer.MIN_GAP_MS + 1)
        f.trip.value = guiding(dest, listOf(wp), TripLegs(dest, listOf(wp), nextIndex = 1))
        f.loc.value = north(18.5); f.scope.runCurrent()
        assertTrue(f.a.notice.value!!.isDestination)
        assertEquals("곧 목적지 대전엑스포과학공원에 도착해요", f.a.notice.value!!.headline)
        assertEquals(2, f.n.said.size)
    }

    @Test fun `경유지에 주차하지 않고 지나쳐도 목적지에 가까워지면 알린다`() {
        val f = approach(describe = { null })
        val wp = Place("한빛탑", north(10.0), PlaceCategory.SIGHT)
        val dest = Place("과학공원", north(20.0), PlaceCategory.SIGHT)
        f.trip.value = guiding(dest, listOf(wp)); f.loc.value = north(9.0); f.scope.runCurrent()
        assertFalse(f.a.notice.value!!.isDestination)
        // 경유지에 닿기 전에는 목적지를 알리지 않는다
        f.scope.advanceTimeBy(StopApproachAnnouncer.MIN_GAP_MS + 1)
        f.loc.value = north(19.0); f.scope.runCurrent()
        assertFalse(f.a.notice.value?.isDestination ?: false)
        f.loc.value = north(10.0); f.scope.runCurrent()   // 경유지를 지나침(주차 없음, legs 는 그대로)
        f.loc.value = north(19.0); f.scope.runCurrent()
        assertTrue(f.a.notice.value!!.isDestination)
        assertEquals(2, f.a.let { f.n.said.size })
    }

    @Test fun `장소가 몰려 있어도 지금 향하는 곳만 차례로 알리고 앞 안내를 끊지 않는다`() {
        val f = approach(describe = { null })
        val a = Place("개미집", north(1.0), PlaceCategory.FOOD)
        val b = Place("큰집", north(1.2), PlaceCategory.FOOD)
        val dest = Place("무명일기", north(1.5), PlaceCategory.FOOD)
        f.trip.value = guiding(dest, listOf(a, b)); f.loc.value = here; f.scope.runCurrent()
        assertEquals(listOf("곧 경유지 개미집에 도착해요"), f.n.said)
        // 위치가 계속 바뀌어도 다른 곳(큰집·목적지)을 바로 알리지 않는다
        f.loc.value = north(0.3); f.scope.runCurrent()
        f.loc.value = north(0.6); f.scope.runCurrent()
        assertEquals(1, f.n.said.size)
        // 개미집에 주차 → 다시 출발해 다음 구간(큰집): 간격이 지난 뒤에만
        f.trip.value = guiding(dest, listOf(a, b), TripLegs(dest, listOf(a, b), nextIndex = 1)); f.scope.runCurrent()
        assertEquals(1, f.n.said.size)
        f.scope.advanceTimeBy(StopApproachAnnouncer.MIN_GAP_MS + 1); f.loc.value = north(0.7); f.scope.runCurrent()
        assertEquals("곧 경유지 큰집에 도착해요", f.n.said.last())
    }

    @Test fun `소개가 없으면 제목만 읽고 음성이 꺼져 있으면 카드만 보인다`() {
        val f = approach(describe = { null })
        f.voice.value = false
        val dest = Place("어느 식당", north(1.0), PlaceCategory.FOOD)
        f.trip.value = guiding(dest); f.loc.value = here; f.scope.runCurrent()
        assertEquals("곧 목적지 어느 식당에 도착해요", f.a.notice.value!!.spoken)
        assertTrue(f.n.said.isEmpty())
    }

    @Test fun `이미 거의 다 와서 알게 되면 도착했어요라고 말한다`() {
        val f = approach(describe = { null })
        val dest = Place("한빛탑", north(5.0), PlaceCategory.SIGHT)
        f.trip.value = guiding(dest); f.loc.value = here; f.scope.runCurrent()
        f.loc.value = north(4.9); f.scope.runCurrent()   // 위치가 드물게 와서 100m 앞에서 처음 알게 됨
        assertEquals("목적지 한빛탑에 도착했어요", f.a.notice.value!!.headline)
    }

    @Test fun `시간이 지나면 카드가 닫히고 안내가 끝나면 기록을 지운다`() {
        val f = approach()
        val dest = Place("목적지", north(1.0), PlaceCategory.SIGHT)
        f.trip.value = guiding(dest); f.loc.value = here; f.scope.runCurrent()
        assertNotNull(f.a.notice.value)
        f.scope.advanceTimeBy(StopApproachAnnouncer.SHOW_MS + 1); f.scope.runCurrent()
        assertNull(f.a.notice.value)
        // 새 여행에서는 같은 곳도 다시 알린다
        f.trip.value = TripState.Idle; f.scope.runCurrent()
        f.trip.value = guiding(dest); f.scope.runCurrent()
        assertNotNull(f.a.notice.value)
    }

    @Test fun `경유지에 주차해 있는 동안은 알리지 않는다`() {
        val f = approach()
        val wp = Place("한빛탑", north(1.0), PlaceCategory.SIGHT)
        val dest = Place("목적지", north(20.0), PlaceCategory.SIGHT)
        f.trip.value = guiding(dest, listOf(wp), TripLegs(dest, listOf(wp), arrivedAtWaypoint = true, parkedAtWaypoint = true))
        f.loc.value = north(1.0); f.scope.runCurrent()
        assertNull(f.a.notice.value)
    }
}
