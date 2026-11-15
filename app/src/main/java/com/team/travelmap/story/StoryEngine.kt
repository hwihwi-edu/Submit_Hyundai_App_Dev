package com.team.travelmap.story

import android.os.SystemClock
import com.team.travelmap.core.geo.Geo
import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.core.model.NaviEvent
import com.team.travelmap.navi.NaviRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 담당: 3번(콘텐츠·룰렛·오디오). 안내 중에 위치를 주기적으로 갱신하고, StoryTrigger 결과에 따라 재생한다.
 */
class StoryEngine(
    private val scope: CoroutineScope,
    private val navi: NaviRepository,
    private val repository: StoryRepository,
    private val player: StoryPlayer,
    private val autoplayEnabled: () -> Boolean = { true },
) {
    private var job: Job? = null
    private val played = mutableSetOf<String>()
    private val retryAfter = mutableMapOf<String, Long>()
    private var cautionUntil = 0L
    private var routePoints: List<StoryPoint> = emptyList()

    private val _upcoming = MutableStateFlow<StoryPoint?>(null)
    /** 경로 위에서 아직 듣지 않은 가장 가까운 이야기(주행 중 화면 "길 위 이야기" 카드). 1번 화면이 읽는다. */
    val upcoming: StateFlow<StoryPoint?> = _upcoming.asStateFlow()

    /** id 로 이야기 찾기(3번 가는 길 동행: 근처 녹음 이야기 재생). */
    fun storyById(id: String): StoryPoint? = repository.all.firstOrNull { it.id == id }

    /** "이야기 듣기" 버튼: 반경 밖이어도 지금 바로 재생하고 들은 것으로 친다. */
    fun playNow(story: StoryPoint): Boolean {
        val ok = player.play(story)
        if (ok) {
            played += story.id
            retryAfter.remove(story.id)
            _upcoming.value = nearestUnplayed(navi.currentLocation.value)
        }
        return ok
    }

    private fun nearestUnplayed(current: GeoPoint?): StoryPoint? {
        val left = routePoints.filterNot { it.id in played }
        return if (current == null) left.firstOrNull() else left.minByOrNull { Geo.distanceMeters(current, it.point) }
    }

    /** 경로를 시작할 때 호출. 출발지·목적지 축 주변 스토리만 후보로 둔다. */
    fun startTrip(from: GeoPoint?, to: GeoPoint) {
        stopTrip()
        played.clear()
        retryAfter.clear()
        cautionUntil = 0L
        routePoints = if (from == null) repository.all else StoryTrigger.alongRoute(from, to, repository.all)
        job = scope.launch {
            launch {
                navi.events.collect { e ->
                    if (e is NaviEvent.CautionAhead) cautionUntil = SystemClock.elapsedRealtime() + CAUTION_HOLD_MS
                }
            }
            while (isActive) {
                navi.refreshLocation()
                val now = SystemClock.elapsedRealtime()
                val next = StoryTrigger.next(
                    current = navi.currentLocation.value,
                    points = routePoints,
                    alreadyPlayed = played + retryAfter.filterValues { now < it }.keys,
                    isPlaying = player.nowPlaying.value != null,
                    cautionActive = now < cautionUntil,
                    autoplayEnabled = autoplayEnabled(),
                )
                if (next != null) {
                    if (player.play(next)) {
                        played += next.id
                        retryAfter.remove(next.id)
                    } else {
                        // 실패한 파일이 가까운 다른 이야기의 재생을 계속 막지 않도록 잠시 미룬다.
                        retryAfter[next.id] = now + RETRY_DELAY_MS
                    }
                }
                _upcoming.value = nearestUnplayed(navi.currentLocation.value)
                delay(POLL_MS)
            }
        }
    }

    fun stopTrip() {
        job?.cancel()
        job = null
        _upcoming.value = null
        player.stop()
    }

    private companion object {
        const val POLL_MS = 3_000L
        const val CAUTION_HOLD_MS = 20_000L
        const val RETRY_DELAY_MS = 30_000L
    }
}
