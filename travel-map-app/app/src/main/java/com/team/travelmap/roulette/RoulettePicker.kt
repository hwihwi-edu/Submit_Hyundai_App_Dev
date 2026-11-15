package com.team.travelmap.roulette

import com.team.travelmap.core.geo.Geo
import com.team.travelmap.core.model.EnergyState
import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.core.model.PilotScope
import com.team.travelmap.core.model.Region
import com.team.travelmap.core.model.RegionId
import kotlin.random.Random

/**
 * 담당: 3번(콘텐츠·룰렛·오디오). 안드로이드 의존성이 없는 순수 로직이라 단위 테스트로 검증한다.
 *
 * 후보는 항상 활성 지역(isActive, 기본 PilotScope = 서울·대전·부산 46칸)으로 한정한다.
 *
 * 모드 1. ALL       : 활성 지역 전체에서 랜덤. 주행가능거리를 넘는 곳은 needsRecharge = true 로 표시
 *                      → UI 에서 충전소·주유소 경유를 제안한다. (전국 확대 시 = 전국 랜덤)
 * 모드 2. REACHABLE  : 지금 연료·배터리로 갈 수 있는 곳만 랜덤. (차량 특화)
 *                      주행가능거리를 모르면(rangeKm == null) filter.fallbackRangeKm 로 대신 거른다.
 */
enum class RouletteMode { ALL, REACHABLE }

data class RouletteFilter(
    val onlyUnvisited: Boolean = true,
    /** 편도 최대 거리(km). null 이면 제한 없음. "편도 2시간" 같은 UI 값은 km 로 바꿔 넣는다. */
    val maxOneWayKm: Double? = null,
    /** 왕복 기준으로 주행가능거리를 따질지. */
    val roundTrip: Boolean = true,
    /** 주행가능거리를 못 읽을 때 쓸 값(km). */
    val fallbackRangeKm: Double = 200.0,
    val excludeSido: Set<String> = emptySet(),
)

data class RouletteCandidate(
    val region: Region,
    /** 예상 도로 거리(km, 편도). 직선거리 × ROAD_FACTOR 로 근사. */
    val estimatedRoadKm: Double,
    val needsRecharge: Boolean,
)

class RoulettePicker(
    private val random: Random = Random.Default,
    private val isActive: (RegionId) -> Boolean = PilotScope::isActive,
) {

    fun candidates(
        regions: List<Region>,
        visited: Set<RegionId>,
        origin: GeoPoint,
        energy: EnergyState,
        mode: RouletteMode,
        filter: RouletteFilter = RouletteFilter(),
    ): List<RouletteCandidate> {
        if (!origin.latitude.isFinite() || !origin.longitude.isFinite() ||
            origin.latitude !in -90.0..90.0 || origin.longitude !in -180.0..180.0) return emptyList()
        if (filter.maxOneWayKm?.let { !it.isFinite() || it < 0 } == true) return emptyList()
        // NaN과 음수는 '거리 미확인'으로 처리한다. 0km는 유효한 잔여 거리다.
        val range = energy.rangeKm?.takeIf { it.isFinite() && it >= 0 }
            ?: filter.fallbackRangeKm.takeIf { it.isFinite() && it >= 0 }
            ?: 0.0
        val usable = range * SAFETY_MARGIN
        return regions.asSequence()
            .filter { isActive(it.id) }
            .filterNot { filter.onlyUnvisited && it.id in visited }
            .filterNot { it.id.sidoCode in filter.excludeSido }
            .filter { it.center.latitude.isFinite() && it.center.longitude.isFinite() &&
                it.center.latitude in -90.0..90.0 && it.center.longitude in -180.0..180.0 }
            .map { region ->
                val oneWay = Geo.distanceMeters(origin, region.center) / 1000.0 * ROAD_FACTOR
                val needed = if (filter.roundTrip) oneWay * 2 else oneWay
                RouletteCandidate(region, oneWay, needsRecharge = needed > usable)
            }
            .filter { c -> filter.maxOneWayKm?.let { c.estimatedRoadKm <= it } ?: true }
            .filter { c -> mode == RouletteMode.ALL || !c.needsRecharge }
            .filter { c -> c.estimatedRoadKm >= MIN_TRIP_KM } // 지금 있는 동네는 제외
            .toList()
    }

    fun pick(candidates: List<RouletteCandidate>): RouletteCandidate? =
        if (candidates.isEmpty()) null else candidates[random.nextInt(candidates.size)]

    companion object {
        /** 직선거리 → 도로거리 근사 계수. 2번이 실제 경로 결과와 비교해 조정. */
        const val ROAD_FACTOR = 1.3
        /** 주행가능거리 중 실제로 쓸 비율(여유분 15%). */
        const val SAFETY_MARGIN = 0.85
        const val MIN_TRIP_KM = 5.0
    }
}
