package com.team.travelmap.curation

import android.content.Context
import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.core.model.Place
import com.team.travelmap.core.model.PlaceCategory
import com.team.travelmap.core.model.RegionId
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 담당: 3번. 수집한 관광정보 전체 목록. 시설·운영·접근성을 검증했다는 뜻은 아니다. */
@Serializable
data class TourismEntry(
    val contentId: String,
    val name: String,
    val regionCode: String,
    val contentTypeId: String,
    val lat: Double,
    val lng: Double,
    val address: String,
    val modifiedAt: String,
    val collectedAt: String,
    val source: String,
    val imageUrl: String = "",
    val thumbnailUrl: String = "",
    val imageLicense: String = "",
    val phone: String = "",
    val classification: Map<String, String> = emptyMap(),
) {
    /** 공통 PlaceCategory로 표현 가능한 유형만 경유지로 제공한다. */
    fun asPlace(): Place? {
        val category = when (contentTypeId) {
            "12", "14" -> PlaceCategory.SIGHT
            "39" -> PlaceCategory.FOOD
            "15" -> PlaceCategory.FESTIVAL
            else -> return null
        }
        return Place(name, GeoPoint(lat, lng), category, RegionId(regionCode),
            address = address)
    }
}

/** 제공된 편의시설 설명을 원문으로 보존한다. 미기재 항목은 이용 불가를 뜻하지 않는다. */
@Serializable
data class TourismDetails(
    val description: String = "",
    val food: Map<String, String> = emptyMap(),
    val intro: Map<String, String> = emptyMap(),
    val introStatus: String = "pending",
    val introCollectedAt: String = "",
    val accessibility: Map<String, String> = emptyMap(),
    val accessibilityStatus: String = "미확인",
    val commonCollectedAt: String = "",
    val accessibilityCollectedAt: String = "",
)

class TourismRepository(context: Context) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    val all: List<TourismEntry> by lazy {
        val text = appContext.assets.open("tourism_catalog.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        json.decodeFromString<List<TourismEntry>>(text)
    }
    private val byRegion by lazy { all.groupBy { it.regionCode } }
    private val detailsById: Map<String, TourismDetails> by lazy {
        val text = appContext.assets.open("tourism_details.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        json.decodeFromString<Map<String, TourismDetails>>(text)
    }

    fun detailsOf(contentId: String): TourismDetails = detailsById[contentId] ?: TourismDetails()

    /** 목록에서는 긴 원문을 줄여 표시하고, 전체 설명은 detailsOf에서 보존한다. */
    fun waypointOf(entry: TourismEntry): Place? {
        val summary = detailsOf(entry.contentId).description
            .replace(Regex("\\s+"), " ").trim()
        val display = if (summary.length > 120) summary.take(120).trimEnd() + "…" else summary
        return entry.asPlace()?.copy(description = display.takeIf { it.isNotEmpty() })
    }

    fun entriesOf(regionId: RegionId): List<TourismEntry> = byRegion[regionId.code].orEmpty()

    /** 전체 유형을 보존한다. 미지정 조건은 제한하지 않으며 임의로 건수를 줄이지 않는다. */
    fun search(
        regionId: RegionId? = null,
        contentTypeId: String? = null,
        keyword: String = "",
        /** 시설 이용 가능 판정이 아니라, 제공된 접근성 설명이 있는 장소만 조회한다. */
        withAccessibilityInfo: Boolean = false,
    ): List<TourismEntry> {
        val entries = regionId?.let(::entriesOf) ?: all
        val query = keyword.trim()
        return entries.filter {
            (contentTypeId == null || it.contentTypeId == contentTypeId) &&
                (query.isEmpty() || it.name.contains(query, ignoreCase = true) || it.address.contains(query)) &&
                (!withAccessibilityInfo || detailsOf(it.contentId).accessibility.values.any { text -> text.isNotBlank() })
        }
    }
}
