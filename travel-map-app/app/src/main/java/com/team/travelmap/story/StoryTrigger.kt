package com.team.travelmap.story

import com.team.travelmap.core.geo.Geo
import com.team.travelmap.core.model.GeoPoint

/**
 * 담당: 3번(콘텐츠·룰렛·오디오). 경로 주변 유적지·설화 포인트.
 * audioAsset: assets 안의 음성 파일 경로 (예: "stories/gongsanseong.mp3")
 */
data class StoryPoint(
    val id: String,
    val title: String,
    val point: GeoPoint,
    val audioAsset: String,
    val radiusMeters: Double = 2_000.0,
)

/**
 * 지금 재생할 스토리를 고르는 순수 로직(단위 테스트 대상).
 * 규칙: 재생 중이 아니고, 주의 구간이 아니고, 아직 안 들은 포인트 중 반경 안에 있는 가장 가까운 것.
 *
 * 위치는 NaviRepository.currentLocation(내비 현재 위치)을 쓴다.
 * FusedLocation SDK 를 쓸 수 있게 되면 Proximity Alert(지오펜싱)로 바꿀 수 있다.
 */
object StoryTrigger {
    fun next(
        current: GeoPoint?,
        points: List<StoryPoint>,
        alreadyPlayed: Set<String>,
        isPlaying: Boolean,
        cautionActive: Boolean,
        autoplayEnabled: Boolean = true,
    ): StoryPoint? {
        if (!autoplayEnabled || current == null || isPlaying || cautionActive) return null
        return points.asSequence()
            .filterNot { it.id in alreadyPlayed }
            .map { it to Geo.distanceMeters(current, it.point) }
            .filter { (p, d) -> d <= p.radiusMeters }
            .minByOrNull { it.second }
            ?.first
    }

    /** 경로를 정한 직후, 출발지·목적지 축 근처 포인트만 미리 추린다(corridorKm 폭). */
    fun alongRoute(from: GeoPoint, to: GeoPoint, points: List<StoryPoint>, corridorKm: Double = 15.0): List<StoryPoint> {
        val direct = Geo.distanceMeters(from, to)
        return points.filter { p ->
            val detour = Geo.distanceMeters(from, p.point) + Geo.distanceMeters(p.point, to) - direct
            detour <= corridorKm * 1000 * 2
        }
    }
}
