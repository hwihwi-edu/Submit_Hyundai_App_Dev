package com.team.travelmap.curation.companion

import com.team.travelmap.core.geo.Geo
import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.core.model.Place
import com.team.travelmap.core.trip.TripState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 담당: 3번. 가는 길 동행을 실제 주행에 연결한다.
 * 안내(Guiding)가 시작되면 세션을 새로 만들고, 위치가 바뀔 때마다 [RouteCompanionSession]에 물어
 * 지금 보여 줄 제안 하나를 [suggestion]으로 내보낸다. 화면(1번)은 이것만 구독한다.
 *
 * - 제안은 한 번에 하나. 답이 없으면 [AUTO_DISMISS_MS] 뒤 "괜찮아요"로 닫는다(이야기를 펼쳤으면 더 오래 둔다).
 * - "잠깐 들르기"는 [accept]가 장소를 돌려주고, 경유지 추가는 호출한 쪽이 TripCoordinator.addStopNow 로 한다(2번).
 * - [enabled]가 꺼지면(설정 "주변 장소 추천") 묻지 않는다.
 * - 음성: [voiceEnabled](설정 "음성 해설")가 켜져 있으면 카드가 뜰 때 [PlaceNarration.spokenAsk]를,
 *   "이야기 듣기"(녹음 이야기가 없는 곳)에서 소개 글을 [narrator]로 읽는다. 녹음 이야기가 재생 중이면 겹치지 않게 읽지 않는다.
 * - 다음 경유지·목적지 [nearTargetMeters] 안에서는 새로 묻지 않는다(곧 도착 안내가 나오는 자리, [StopApproachAnnouncer]).
 * - 말로 대답하기: 질문을 끝까지 읽으면 [answers]로 [LISTEN_MS] 동안 듣는다([answerEnabled]가 켜져 있을 때).
 *   "응/좋아/들를래" → 잠깐 들르기([addStop]), "괜찮아/아니" → 닫기, "이야기/설명" → 소개 읽기 뒤 다시 묻기.
 *   못 알아들으면 카드는 그대로(버튼 또는 [AUTO_DISMISS_MS] 뒤 닫힘).
 */
class RouteCompanionController(
    private val scope: CoroutineScope,
    private val trip: StateFlow<TripState>,
    private val location: StateFlow<GeoPoint?>,
    private val onHighway: StateFlow<Boolean>,
    private val nextIcExitMeters: StateFlow<Int?>,
    private val enabled: StateFlow<Boolean>,
    private val loadCandidates: suspend () -> List<CompanionCandidate>,
    private val now: () -> Long = System::currentTimeMillis,
    /** 후보 목록(1만여 곳)을 만드는 스레드. 테스트는 테스트 디스패처를 넘긴다. */
    private val loadDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val narrator: PlaceNarrator = TextOnlyNarrator,
    private val voiceEnabled: StateFlow<Boolean> = MutableStateFlow(false),
    /** 녹음 이야기(StoryPlayer)가 재생 중인지. 재생 중이면 질문을 소리로 읽지 않는다(글은 보여 준다). */
    private val isStoryPlaying: () -> Boolean = { false },
    private val nearTargetMeters: Double = StopApproachAnnouncer.RADIUS_M,
    /** true 인 동안(곧 도착 안내가 떠 있을 때)은 묻지 않고, 떠 있던 제안도 닫는다. 한 번에 카드 하나. */
    private val blocked: StateFlow<Boolean> = MutableStateFlow(false),
    private val answers: AnswerListener = NoAnswerListener,
    private val answerEnabled: StateFlow<Boolean> = MutableStateFlow(false),
    /** 말로 "들를래"라고 했을 때 경유지로 넣는다(TripCoordinator.addStopNow). */
    private val addStop: (Place) -> Result<Unit> = { Result.failure(IllegalStateException("경유지 추가 없음")) },
    /** 말로 "이야기"라고 했을 때 녹음 이야기 재생(StoryEngine). 재생했으면 true. */
    private val playRecorded: (String) -> Boolean = { false },
) {
    private val _suggestion = MutableStateFlow<CompanionSuggestion?>(null)
    val suggestion: StateFlow<CompanionSuggestion?> = _suggestion.asStateFlow()

    /** "이야기 듣기"를 눌러 글을 펼친 상태. 화면은 이때 [CompanionSuggestion.narration] story 를 보여 준다. */
    private val _storyOpen = MutableStateFlow(false)
    val storyOpen: StateFlow<Boolean> = _storyOpen.asStateFlow()

    /** 대답을 듣고 있는 중. 화면은 마이크 표시와 "응 / 괜찮아 / 이야기"를 보여 준다. */
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private var session: RouteCompanionSession? = null
    private var sessionKey: Any? = null
    private var candidates: List<CompanionCandidate>? = null
    private var expireJob: Job? = null
    /** 이 컨트롤러가 읽기 시작한 문장이 있는지. 닫을 때 남의 안내(곧 도착)까지 끊지 않으려고 둔다. */
    private var spoke = false
    /** 이번 여행에서 대답하는 법을 이미 안내했는지("'들를래' 또는 '괜찮아'라고 말해 주세요"). */
    private var answerGuided = false

    init {
        scope.launch {
            combine(trip, location, enabled, blocked) { t, here, on, b -> Snapshot(t, here, on, b) }.collect { (t, here, on, isBlocked) ->
                val guiding = t as? TripState.Guiding
                if (guiding == null || !on) { reset(); return@collect }
                // 곧 도착 안내가 이미 말하고 있으므로 읽기는 멈추지 않고 카드만 닫는다.
                if (isBlocked) { spoke = false; close(); return@collect }
                // 같은 여행(목적지·출발 경유지 목록) 동안은 세션을 유지한다. 경유지를 추가해도 같은 여행.
                val key = guiding.destination to guiding.region.id
                if (key != sessionKey) {
                    reset()
                    sessionKey = key
                    // 보조 기능이 앱을 죽이지 않게: 후보를 못 읽으면 이번 여행은 제안 없이 간다.
                    val list = candidates ?: (runCatching { withContext(loadDispatcher) { loadCandidates() } }
                        .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; emptyList() })
                        .also { if (it.isNotEmpty()) candidates = it }
                    session = RouteCompanionSession(list)
                }
                // 경유지에 도착·주차 중이면 "잠깐 들르기"를 넣을 수 없으므로 묻지 않는다.
                if (_suggestion.value != null || here == null || guiding.legs.arrivedAtWaypoint || guiding.legs.parkedAtWaypoint) return@collect
                val target = (guiding.legs.nextWaypoint ?: guiding.destination).point
                if (Geo.distanceMeters(here, target) <= nearTargetMeters) return@collect
                val planned = plannedIds(guiding)
                val ctx = CompanionContext(now(), here, target, planned, onHighway.value, nextIcExitMeters.value)
                session?.next(ctx)?.let { show(it) }
            }
        }
    }

    /** "이야기 듣기": 글을 펼치고 닫히는 시간을 늘린다. 녹음된 이야기가 있으면 호출한 쪽이 StoryPlayer 로 재생한다. */
    fun openStory() {
        val s = _suggestion.value
        stopListening()
        _storyOpen.value = true
        scheduleExpire(STORY_OPEN_DISMISS_MS)
        if (s == null) return
        // 녹음 이야기가 있으면 화면이 StoryPlayer 로 틀므로 질문 읽기만 멈춘다. 없으면 소개 글을 읽고 다시 묻는다.
        if (s.narration.audioStoryId != null) stopSpeaking()
        else if (voiceEnabled.value) say(s.narration.story + " " + ASK_AGAIN) { listenFor(s) }
    }

    /** "잠깐 들르기": 제안을 닫고 그 장소를 돌려준다. 경유지 추가는 호출한 쪽에서. */
    fun accept(): CompanionSuggestion? {
        val s = _suggestion.value ?: return null
        session?.accepted(s.candidate.id)
        close()
        return s
    }

    /** "괜찮아요" 또는 시간이 지나 닫힘. */
    fun decline() {
        val s = _suggestion.value ?: return
        session?.declined(s.candidate.id)
        close()
    }

    private fun show(s: CompanionSuggestion) {
        _storyOpen.value = false
        _suggestion.value = s
        scheduleExpire(AUTO_DISMISS_MS)
        if (voiceEnabled.value && !isStoryPlaying()) {
            // 기기 안 인식은 "응" 같은 한 글자를 잘 못 잡는다(2026-11-15 가상 기기 확인). 여행에서 처음 물을 때 대답하는 법을 알려 준다.
            val guide = if (answerEnabled.value && !answerGuided) { answerGuided = true; " $ANSWER_GUIDE" } else ""
            say(s.narration.spokenAsk + guide) { listenFor(s) }
        }
    }

    /** 질문을 다 읽은 뒤 대답을 듣는다. 카드가 바뀌었거나 닫혔으면 듣지 않는다. */
    private fun listenFor(s: CompanionSuggestion) {
        if (_suggestion.value !== s || !answerEnabled.value) return
        val started = answers.listen(LISTEN_MS) { heard ->
            _listening.value = false
            if (_suggestion.value !== s) return@listen
            when (VoiceAnswerParser.parse(heard)) {
                VoiceAnswer.YES -> stopByVoice(s)
                VoiceAnswer.NO -> { decline(); say(OK_NEXT_TIME) }
                VoiceAnswer.STORY -> {
                    openStory()
                    s.narration.audioStoryId?.let { playRecorded(it) }
                }
                null -> Unit
            }
        }
        _listening.value = started
    }

    /** 말로 "들를래": 경유지에 넣고 알려 준다. */
    private fun stopByVoice(s: CompanionSuggestion) {
        addStop(placeOf(s))
            .onSuccess { accept(); say("${s.narration.headline}${PlaceNarration.objectParticle(s.narration.headline)} 경유지로 넣었어요.") }
            .onFailure { say(CANNOT_ADD) }
    }

    private fun stopListening() {
        if (_listening.value) answers.cancel()
        _listening.value = false
    }

    private fun say(text: String, onDone: (() -> Unit)? = null) {
        if (narrator.speak(text, onDone)) spoke = true
    }

    private fun stopSpeaking() {
        if (spoke) narrator.stop()
        spoke = false
    }

    private fun scheduleExpire(ms: Long) {
        expireJob?.cancel()
        expireJob = scope.launch { delay(ms); decline() }
    }

    private fun close() {
        expireJob?.cancel()
        stopListening()
        stopSpeaking()
        _storyOpen.value = false
        _suggestion.value = null
    }

    private fun reset() {
        close()
        answerGuided = false
        session = null
        sessionKey = null
    }

    /** 코스에 이미 든 장소: 관광 장소와 이름이 같거나 [PLANNED_RADIUS_M] 안이면 같은 곳으로 본다. */
    private fun plannedIds(g: TripState.Guiding): Set<String> {
        val stops: List<Place> = g.waypoints + g.destination
        return candidates.orEmpty().filter { c ->
            stops.any { p -> p.name == c.name || Geo.distanceMeters(p.point, c.point) <= PLANNED_RADIUS_M }
        }.map { it.id }.toSet()
    }

    /** 경유지로 넣을 장소(관광 목록 좌표·이름). */
    fun placeOf(s: CompanionSuggestion): Place = Place(
        name = s.candidate.name, point = s.candidate.point, category = s.candidate.category,
        regionId = s.candidate.regionId,
    )

    private data class Snapshot(val trip: TripState, val here: GeoPoint?, val on: Boolean, val blocked: Boolean)

    companion object {
        /** 질문을 읽은 뒤 대답을 기다리는 시간. */
        const val LISTEN_MS = 8_000L
        const val ASK_AGAIN = "잠깐 들렀다 갈까요?"
        const val ANSWER_GUIDE = "'들를래' 또는 '괜찮아'라고 말해 주세요."
        const val OK_NEXT_TIME = "알겠어요. 다음에 들러요."
        const val CANNOT_ADD = "지금은 경유지를 넣을 수 없어요. 다시 출발한 뒤에 말해 주세요."
        const val AUTO_DISMISS_MS = 45_000L
        const val STORY_OPEN_DISMISS_MS = 120_000L
        const val PLANNED_RADIUS_M = 150.0
    }
}
