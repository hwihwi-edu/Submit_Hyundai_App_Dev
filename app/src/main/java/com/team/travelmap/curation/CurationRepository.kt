package com.team.travelmap.curation

import android.content.Context
import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.core.model.Place
import com.team.travelmap.core.model.PlaceCategory
import com.team.travelmap.core.model.Region
import com.team.travelmap.core.model.RegionId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 담당: 3번(콘텐츠·룰렛·오디오). 지역별 맛집·명소·축제 리스트.
 *
 * assets/curation.json 키는 두 종류를 쓸 수 있다.
 *  - 시·군·구 5자리 코드("11110" 종로구): 그 구에만 나오는 장소. 룰렛으로 그 구가 뽑히면 첫 명소가 목적지가 된다.
 *  - 시·도 2자리 코드("11" 서울): 초기 샘플. 현재 경유지 목록에는 사용하지 않는다.
 * 관광공사 목록은 별도의 tourism_catalog.json에서 읽어 선택한 구에 한정한다.
 *
 * TODO(3번)
 *  - 서울·대전·부산 전체 데이터의 상세·접근성 검수와 분류 검색 UI 연결
 *  - 구 전용 장소가 없으면 그 구의 대표 지점(center)이 목적지 → [defaultDestination]
 *    (도시 추천 장소를 목적지로 쓰면 뽑힌 구와 실제 도착지가 달라져 도착 인증이 어긋나므로 경유지로만 쓴다)
 */
class CurationRepository(private val context: Context) {

    @Serializable
    private data class PlaceDto(
        val name: String,
        val lat: Double,
        val lng: Double,
        val category: String = "SIGHT",
        val description: String? = null,
        val address: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val tourism = TourismRepository(context)

    private val byRegion: Map<String, List<PlaceDto>> by lazy {
        val text = context.assets.open("curation.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        json.decodeFromString<Map<String, List<PlaceDto>>>(text)
    }

    /** 그 구의 전체 경유지 후보. 쇼핑·숙박 등은 TourismRepository.search로 조회한다. */
    fun placesOf(regionId: RegionId): List<Place> {
        // 도시 샘플 추천을 대체하고 실제 소속 구의 전체 후보를 연결한다.
        // 목적지 선정은 검수 전 자동 변경하지 않는다.
        val catalog = tourism.entriesOf(regionId).mapNotNull { tourism.waypointOf(it) }
        val curated = regionOnly(regionId)
        return curated.filterNot { sample -> catalog.any { it.name == sample.name } } + catalog
    }

    private fun regionOnly(regionId: RegionId): List<Place> = toPlaces(byRegion[regionId.code].orEmpty(), regionId)

    private fun toPlaces(list: List<PlaceDto>, regionId: RegionId): List<Place> =
        list.map { d ->
            Place(
                name = d.name,
                point = GeoPoint(d.lat, d.lng),
                category = runCatching { PlaceCategory.valueOf(d.category) }.getOrDefault(PlaceCategory.SIGHT),
                regionId = regionId,
                address = d.address,
                description = d.description,
            )
        }

    /** 그 구 전용 첫 명소, 없으면 구 대표 지점. */
    fun defaultDestination(region: Region): Place =
        regionOnly(region.id).firstOrNull { it.category == PlaceCategory.SIGHT || it.category == PlaceCategory.LANDMARK }
            ?: Place(region.fullName, region.center, PlaceCategory.LANDMARK, region.id)
}
