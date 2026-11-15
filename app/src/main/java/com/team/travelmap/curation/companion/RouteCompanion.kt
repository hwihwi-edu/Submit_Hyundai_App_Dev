package com.team.travelmap.curation.companion

import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.core.model.PlaceCategory
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * 담당: 3번. "가는 길 동행" — 달리는 중 경로 앞쪽의 볼만한 곳을 하나 골라
 * "곧 ○○을 지나요 · 이야기 들려드릴까요? · 잠깐 들렀다 갈까요?"를 제안한다(README 6절 컨셉·주행 중 동행·가는 길 추천).
 *
 * 순수 로직이다. 화면(카드)·경유지 추가(NaviHelper.addWaypoint)·음성은 이 결과를 받아 쓴다.
 * - 1번: [CompanionSuggestion]으로 카드를 그리고, 버튼에 따라 [RouteCompanionSession]의 shown/declined/accepted 를 부른다.
 * - 2번: "잠깐 들르기"면 [CompanionSuggestion.candidate] 좌표로 경유지를 추가하고 지금 구간 안내(TripLegs)에 넣는다.
 * - 음성: [PlaceNarrator](앱은 Pleos TTS)로 질문·이야기를 읽는다(RouteCompanionController).
 *
 * 경로 선(polyline)은 SDK가 주지 않으므로 "지금 위치 → 다음 목표(경유지/목적지)" 직선을 경로로 본다(추정).
 */
data class CompanionCandidate(
    val id: String,
    val name: String,
    val point: GeoPoint,
    val category: PlaceCategory,
    /** 관광공사 소개문(원문). 비어 있으면 들려줄 이야기가 없어 후보에서 뺀다. */
    val overview: String,
    val hasImage: Boolean = false,
    /** 테마 큐레이션(검수한 장소)에 들어 있으면 true. */
    val curated: Boolean = false,
    /** 이 장소 근처에 녹음된 이야기(오디 등)가 있으면 그 id. 있으면 글 대신 음성으로 들려줄 수 있다. */
    val audioStoryId: String? = null,
    /** 시·군·구(경유지로 넣을 때 방문 기록 판정에 씀). */
    val regionId: com.team.travelmap.core.model.RegionId? = null,
)

/** 지금 달리는 상황. 시각은 epoch 밀리초(테스트에서 고정하기 쉽게). */
data class CompanionContext(
    val nowMillis: Long,
    val here: GeoPoint,
    /** 다음 목표(다음 경유지, 없으면 목적지). */
    val target: GeoPoint,
    /** 이미 코스에 들어 있는 장소 id(다시 추천하지 않음). */
    val plannedIds: Set<String> = emptySet(),
    /** PR #30: 안내 중 IC 진입~진출 사이면 true. */
    val onHighway: Boolean = false,
    /** PR #30: 경로상 첫 IC 진출까지 남은 거리(m). 모르면 null. */
    val nextIcExitMeters: Int? = null,
)

/** 한 번의 여행 동안 쌓이는 상태. 같은 곳을 다시 묻지 않고, 너무 자주 묻지 않게 한다. */
data class CompanionState(
    val shownIds: Set<String> = emptySet(),
    val declinedIds: Set<String> = emptySet(),
    val acceptedIds: Set<String> = emptySet(),
    val lastPromptAtMillis: Long? = null,
    val lastPromptPoint: GeoPoint? = null,
    /** 이번 여행에서 처음 위치를 받은 시각·자리. 출발 직후에는 묻지 않으려고 쓴다. */
    val startedAtMillis: Long? = null,
    val startPoint: GeoPoint? = null,
)

data class CompanionSuggestion(
    val candidate: CompanionCandidate,
    /** 경로(직선)를 따라 앞으로 몇 km 뒤에 지나는지. */
    val aheadKm: Double,
    /** 경로에서 옆으로 떨어진 거리(km). */
    val offRouteKm: Double,
    /** 들렀다 가면 늘어나는 이동 시간(분, 추정). 머무는 시간은 [RouteCompanion.SHORT_STOP_MINUTES]로 따로. */
    val detourDriveMinutes: Int,
    val narration: PlaceNarration,
) {
    /** 들렀다 가면 늘어나는 전체 시간(이동 + 잠깐 머무는 시간, 분). */
    val detourTotalMinutes: Int get() = detourDriveMinutes + RouteCompanion.SHORT_STOP_MINUTES
}

object RouteCompanion {
    /** 경로 앞쪽 범위(km). 너무 가까우면 버튼을 누를 틈이 없고, 너무 멀면 와닿지 않는다. */
    const val MIN_AHEAD_KM = 0.5
    const val MAX_AHEAD_KM = 5.0
    /** 경로(직선)에서 옆으로 이 거리 안만. */
    const val CORRIDOR_KM = 1.5
    /** 다음 목표까지 이보다 가까우면 곧 도착하므로 묻지 않는다. */
    const val NEAR_TARGET_KM = 1.0
    /** 제안 간격: 시간과 거리 둘 다 넘어야 다시 묻는다. */
    const val MIN_INTERVAL_MINUTES = 10
    const val MIN_INTERVAL_KM = 3.0
    /** 첫 제안은 출발하고 이 시간이 지나거나 이 거리를 간 뒤부터(출발 직후 바로 묻지 않게). */
    const val FIRST_PROMPT_AFTER_MINUTES = 3
    const val FIRST_PROMPT_AFTER_KM = 1.5
    /** 고속도로에서는 진출 IC 가 이 거리 안일 때만(기획: IC 근처 3km). */
    const val HIGHWAY_IC_METERS = 3_000
    /** 소개문이 이보다 짧으면 들려줄 이야기로 부족하다. */
    const val MIN_OVERVIEW_CHARS = 40
    /** 우회 이동시간 추정: 직선 × 도로 우회 비율 ÷ 평균 속도(코스 추정과 같은 값). */
    const val ROAD_FACTOR = 1.3
    const val AVG_KMH = 40.0
    /** "잠깐 들르기"에 잡는 머무는 시간(분). */
    const val SHORT_STOP_MINUTES = 30

    /** 지금 물을 만한 곳 하나. 없거나 지금은 묻지 않을 때면 null. */
    fun suggest(ctx: CompanionContext, candidates: List<CompanionCandidate>, state: CompanionState): CompanionSuggestion? {
        if (!intervalPassed(ctx, state)) return null
        if (ctx.onHighway && (ctx.nextIcExitMeters == null || ctx.nextIcExitMeters > HIGHWAY_IC_METERS)) return null
        val frame = Frame(ctx.here)
        val (tx, ty) = frame.xy(ctx.target)
        val routeKm = sqrt(tx * tx + ty * ty)
        if (routeKm < NEAR_TARGET_KM) return null
        val excluded = ctx.plannedIds + state.shownIds + state.declinedIds + state.acceptedIds
        return candidates.asSequence()
            .filter { it.id !in excluded && it.category in ALLOWED && cleanOverview(it.overview).length >= MIN_OVERVIEW_CHARS }
            .mapNotNull { c ->
                val (px, py) = frame.xy(c.point)
                val along = (px * tx + py * ty) / routeKm
                val off = abs(px * ty - py * tx) / routeKm
                if (along < MIN_AHEAD_KM || along > minOf(MAX_AHEAD_KM, routeKm) || off > CORRIDOR_KM) null
                else Triple(c, along, off)
            }
            .sortedWith(compareByDescending<Triple<CompanionCandidate, Double, Double>> { (c, along, off) -> score(c, along, off) }
                .thenBy { it.first.id })
            .firstOrNull()
            ?.let { (c, along, off) ->
                detourMinutes(ctx.here, c.point, ctx.target).let { d -> CompanionSuggestion(c, along, off, d, PlaceNarration.of(c, along, d)) }
            }
    }

    /** 높을수록 먼저: 경로에 가까울수록, 검수한 장소·사진·녹음 이야기가 있을수록, 조금 더 가까울수록. 음식점은 조금 낮게. */
    fun score(c: CompanionCandidate, aheadKm: Double, offRouteKm: Double): Double {
        var s = 2.0 * (1 - offRouteKm / CORRIDOR_KM)
        if (c.curated) s += 1.0
        if (c.audioStoryId != null) s += 1.0
        if (c.hasImage) s += 0.5
        if (c.category == PlaceCategory.FOOD) s -= 0.5
        s += 0.5 * (1 - aheadKm / MAX_AHEAD_KM)
        return s
    }

    /** 들렀다 가면 늘어나는 이동 시간(분): (지금→장소→목표 − 지금→목표) 직선 × 우회 비율 ÷ 평균 속도. */
    fun detourMinutes(here: GeoPoint, stop: GeoPoint, target: GeoPoint): Int {
        val f = Frame(here)
        val extraKm = (f.km(here, stop) + f.km(stop, target) - f.km(here, target)) * ROAD_FACTOR
        // 경로 위 장소의 계산 오차(수 m)로 "1분"이 나오지 않게 10m 미만은 0으로 본다.
        if (extraKm < 0.01) return 0
        return kotlin.math.ceil(extraKm / AVG_KMH * 60).toInt()
    }

    private fun intervalPassed(ctx: CompanionContext, state: CompanionState): Boolean {
        val at = state.lastPromptAtMillis ?: return firstPromptAllowed(ctx, state)
        if (ctx.nowMillis - at < MIN_INTERVAL_MINUTES * 60_000L) return false
        val p = state.lastPromptPoint ?: return true
        return Frame(ctx.here).km(ctx.here, p) >= MIN_INTERVAL_KM
    }

    private fun firstPromptAllowed(ctx: CompanionContext, state: CompanionState): Boolean {
        val start = state.startedAtMillis ?: return true
        if (ctx.nowMillis - start >= FIRST_PROMPT_AFTER_MINUTES * 60_000L) return true
        val p = state.startPoint ?: return true
        return Frame(ctx.here).km(ctx.here, p) >= FIRST_PROMPT_AFTER_KM
    }

    private val ALLOWED = setOf(PlaceCategory.SIGHT, PlaceCategory.LANDMARK, PlaceCategory.FESTIVAL, PlaceCategory.FOOD)

    /** 지금 위치 기준 평면 근사(수 km 범위라 충분). x 동쪽, y 북쪽, 단위 km. */
    private class Frame(origin: GeoPoint) {
        private val lat0 = origin.latitude
        private val lng0 = origin.longitude
        private val kx = 111.320 * cos(Math.toRadians(lat0))
        private val ky = 110.574
        fun xy(p: GeoPoint) = (p.longitude - lng0) * kx to (p.latitude - lat0) * ky
        fun km(a: GeoPoint, b: GeoPoint): Double {
            val (ax, ay) = xy(a); val (bx, by) = xy(b)
            return sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by))
        }
    }
}

/** 한 번의 여행 동안 제안 상태를 들고 있는 작은 세션. 여행이 끝나면 새로 만든다. */
class RouteCompanionSession(private val candidates: List<CompanionCandidate>) {
    var state: CompanionState = CompanionState()
        private set

    /** 위치가 갱신될 때마다 부른다. 제안이 나오면 바로 "보여 줌"으로 기록해 같은 곳을 다시 묻지 않는다. */
    fun next(ctx: CompanionContext): CompanionSuggestion? {
        if (state.startedAtMillis == null) state = state.copy(startedAtMillis = ctx.nowMillis, startPoint = ctx.here)
        return RouteCompanion.suggest(ctx, candidates, state)?.also { shown(it.candidate.id, ctx.nowMillis, ctx.here) }
    }

    fun shown(id: String, atMillis: Long, at: GeoPoint) {
        state = state.copy(shownIds = state.shownIds + id, lastPromptAtMillis = atMillis, lastPromptPoint = at)
    }

    /** "괜찮아요" 또는 시간이 지나 카드가 닫힘. */
    fun declined(id: String) { state = state.copy(declinedIds = state.declinedIds + id) }

    /** "잠깐 들르기"를 눌러 경유지로 넣음. */
    fun accepted(id: String) { state = state.copy(acceptedIds = state.acceptedIds + id) }
}

/** 원문 소개문에서 태그·불필요한 공백을 걷어 낸다. */
internal fun cleanOverview(raw: String): String = raw
    .replace(Regex("""<br\s*/?>""", RegexOption.IGNORE_CASE), " ")
    .replace(Regex("""<[^>]+>"""), "")
    .replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
    .replace(Regex("""\s+"""), " ")
    .trim()
