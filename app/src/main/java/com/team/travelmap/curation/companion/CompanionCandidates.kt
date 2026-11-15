package com.team.travelmap.curation.companion

import com.team.travelmap.core.geo.Geo
import com.team.travelmap.curation.TourismEntry
import com.team.travelmap.curation.TourismRepository
import com.team.travelmap.story.StoryPoint

/**
 * 담당: 3번. 관광 목록 → 가는 길 동행 후보. 소개문이 있는 관광지·문화시설·행사·음식점만.
 * 녹음된 이야기(오디 등) 지점이 [storyRadiusKm] 안에 있으면 그 이야기를 붙여 음성으로 들려줄 수 있게 한다.
 * 여행 시작 때 한 번 만들어 [RouteCompanionSession]에 넘긴다(1만여 곳, 수십 ms).
 */
fun companionCandidates(
    entries: List<TourismEntry>,
    overviewOf: (String) -> String,
    curatedIds: Set<String> = emptySet(),
    stories: List<StoryPoint> = emptyList(),
    storyRadiusKm: Double = 1.0,
): List<CompanionCandidate> = entries.mapNotNull { e ->
    val place = e.asPlace() ?: return@mapNotNull null
    val overview = overviewOf(e.contentId)
    if (cleanOverview(overview).length < RouteCompanion.MIN_OVERVIEW_CHARS) return@mapNotNull null
    val story = stories.firstOrNull { Geo.distanceMeters(it.point, place.point) <= storyRadiusKm * 1000 }
    CompanionCandidate(
        id = e.contentId, name = e.name, point = place.point, category = place.category, overview = overview,
        hasImage = e.imageUrl.isNotBlank() || e.thumbnailUrl.isNotBlank(),
        curated = e.contentId in curatedIds, audioStoryId = story?.id, regionId = place.regionId,
    )
}

/** 앱에서 쓰는 형태: 관광 목록·소개문은 [TourismRepository], 검수 장소는 테마 큐레이션 id, 이야기는 StoryRepository. */
fun TourismRepository.companionCandidates(curatedIds: Set<String>, stories: List<StoryPoint>): List<CompanionCandidate> =
    companionCandidates(all, { detailsOf(it).description }, curatedIds, stories)
