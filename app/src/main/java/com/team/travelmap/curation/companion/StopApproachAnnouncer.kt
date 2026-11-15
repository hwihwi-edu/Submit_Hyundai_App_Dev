package com.team.travelmap.curation.companion

import com.team.travelmap.core.geo.Geo
import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.core.model.Place
import com.team.travelmap.core.trip.TripState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** 곧 도착 안내 한 건. 화면은 [headline]을 크게, [story]를 짧게 보여 준다. */
data class ApproachNotice(
    val place: Place,
    val isDestination: Boolean,
    /** "곧 경유지 한빛탑에 도착해요" */
    val headline: String,
    /** 장소 소개 앞부분. 소개가 없으면 null(제목만 읽는다). */
    val story: String?,
) {
    /** 소리로 읽는 말: 제목 + 소개. */
    val spoken: String get() = if (story.isNullOrBlank()) headline else "$headline. $story"

    companion object {
        const val MAX_STORY_CHARS = 160

        /** [arrived]: 이미 거의 다 왔으면(위치 갱신이 드문 경우) "도착했어요"로 말한다. */
        fun of(place: Place, isDestination: Boolean, description: String?, arrived: Boolean = false): ApproachNotice {
            val what = if (isDestination) "목적지" else "경유지"
            // 음식점은 소개 없이 이름만(2026-11-15 사용자 결정: 식당은 이야기 없이).
            val story = description?.takeIf { place.category != com.team.travelmap.core.model.PlaceCategory.FOOD }
                ?.let { PlaceNarration.firstSentences(cleanOverview(it), MAX_STORY_CHARS) }
                ?.takeIf { it.isNotBlank() }
            val headline = if (arrived) "$what ${place.name}에 도착했어요" else "곧 $what ${place.name}에 도착해요"
            return ApproachNotice(place, isDestination, headline, story)
        }
    }
}

/**
 * 담당: 3번. 안내 중 다음 경유지·목적지에 [radiusMeters] 안으로 가까워지면 그곳 소개를 한 번 알려 준다
 * (카드 [notice] + 설정 "음성 해설"이 켜져 있으면 [narrator]로 읽기). 2차 기획 "주행 중 동행: 음성 해설".
 *
 * - 지금 향하는 곳(다음 경유지, 없으면 목적지)만 알린다. 같은 여행에서 같은 곳은 한 번만.
 *   경유지에 주차하지 않고 지나치면 단계(legs)가 넘어가지 않으므로, 알린 경유지에 실제로 닿은 뒤([ARRIVED_M] 안)에는
 *   목적지를 알린다. 장소가 몰려 있어도(부산 코스) 앞 안내를 끊지 않게 [MIN_GAP_MS] 안에는 새로 알리지 않는다.
 * - 경유지에 주차해 있는 동안(리뷰 화면)은 알리지 않는다.
 * - 소개 글은 [describe]가 준다(코스 장소 설명 → 관광공사 소개문). 없으면 "곧 ○○에 도착해요"만.
 * - 녹음 이야기가 재생 중이어도 읽는다: 읽는 동안 이야기는 멈췄다가 이어 간다(AppContainer 가 narrator 에 연결).
 * - [SHOW_MS] 뒤 또는 [dismiss]로 카드가 닫힌다. 안내가 끝나면(Guiding 이 아니면) 기록을 지운다.
 */
class StopApproachAnnouncer(
    private val scope: CoroutineScope,
    private val trip: StateFlow<TripState>,
    private val location: StateFlow<GeoPoint?>,
    private val voiceEnabled: StateFlow<Boolean>,
    private val narrator: PlaceNarrator,
    private val describe: suspend (Place) -> String?,
    private val radiusMeters: Double = RADIUS_M,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val _notice = MutableStateFlow<ApproachNotice?>(null)
    val notice: StateFlow<ApproachNotice?> = _notice.asStateFlow()

    private val announced = mutableSetOf<String>()
    /** 알린 곳 가운데 실제로 [ARRIVED_M] 안까지 간 곳. */
    private val reached = mutableSetOf<String>()
    private var lastShownAt: Long? = null
    private var hideJob: Job? = null
    private var spoke = false

    init {
        scope.launch {
            combine(trip, location) { t, here -> t to here }.collect { (t, here) ->
                val g = t as? TripState.Guiding
                if (g == null) { announced.clear(); reached.clear(); lastShownAt = null; dismiss(); return@collect }
                if (here == null || g.legs.parkedAtWaypoint) return@collect
                val legTarget = g.legs.nextWaypoint ?: g.destination
                val legKey = keyOf(legTarget)
                if (legKey in announced && Geo.distanceMeters(here, legTarget.point) <= ARRIVED_M) reached += legKey
                val target = when {
                    legKey !in announced -> legTarget
                    // 경유지를 주차 없이 지나친 경우: 그 경유지에 닿은 뒤에만 목적지를 알린다.
                    legTarget != g.destination && legKey in reached && keyOf(g.destination) !in announced -> g.destination
                    else -> return@collect
                }
                val distance = Geo.distanceMeters(here, target.point)
                if (distance > radiusMeters) return@collect
                lastShownAt?.let { if (now() - it < MIN_GAP_MS) return@collect }
                announced += keyOf(target)
                val description = runCatching { describe(target) }
                    .getOrElse { if (it is kotlinx.coroutines.CancellationException) throw it; null }
                show(ApproachNotice.of(target, isDestination = target == g.destination, description, arrived = distance <= ARRIVED_M))
            }
        }
    }

    /** "닫기": 카드를 닫고 읽던 소개를 멈춘다. */
    fun dismiss() {
        hideJob?.cancel()
        _notice.value = null
        if (spoke) narrator.stop()
        spoke = false
    }

    private fun show(n: ApproachNotice) {
        lastShownAt = now()
        _notice.value = n
        spoke = voiceEnabled.value && narrator.speak(n.spoken)
        hideJob?.cancel()
        hideJob = scope.launch { delay(SHOW_MS); dismiss() }
    }

    private fun keyOf(p: Place) = "${p.name}@${p.point.latitude},${p.point.longitude}"

    companion object {
        /** 이 거리 안으로 들어오면 알린다(도심 2km ≈ 3~5분 전). */
        const val RADIUS_M = 2_000.0
        const val SHOW_MS = 40_000L
        /** 안내 사이 최소 간격(앞 안내를 끊지 않게). */
        const val MIN_GAP_MS = 15_000L
        /** 이 안이면 "도착했어요". */
        const val ARRIVED_M = 300.0
    }
}
