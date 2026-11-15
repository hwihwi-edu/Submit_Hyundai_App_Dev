package com.team.travelmap.curation

import android.content.Context
import com.team.travelmap.core.model.PilotScope
import com.team.travelmap.core.model.RegionId
import com.team.travelmap.core.model.ThemePlaceCard
import com.team.travelmap.core.model.TravelSeason
import com.team.travelmap.core.model.TravelTheme
import com.team.travelmap.core.model.WeatherExposure
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 담당: 3번. 원본 관광정보에 근거가 확인된 테마 태그를 연결한다. 기존 목적지 규칙은 유지한다.
 * 검수 25곳 뒤에 ThemeAutoTagger 로 자동 분류한 장소를 이어 붙인다("자동 분류 · 검수 전" 문구).
 */
class ThemeCurationRepository(context: Context) {
    @Serializable
    private data class ThemeSeed(
        val contentId: String,
        val reasons: Map<String, String>,
        val evidence: String,
        val preferredSeasons: List<String> = emptyList(),
        val weatherExposure: String = "UNKNOWN",
        val weatherEvidence: String = "",
    )

    private val appContext = context.applicationContext
    private val tourism = TourismRepository(appContext)
    private val seeds by lazy {
        val text = appContext.assets.open("theme_curation.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        Json { ignoreUnknownKeys = true }.decodeFromString<List<ThemeSeed>>(text)
    }
    private val entries by lazy { tourism.all.associateBy { it.contentId } }
    @Serializable
    private data class ImageAsset(
        val contentId: String, val assetPath: String, val imageLicense: String,
        val attribution: String, val allowCropping: Boolean,
    )
    private val images by lazy {
        val text = appContext.assets.open("tourism_images.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        Json { ignoreUnknownKeys = true }.decodeFromString<List<ImageAsset>>(text).associateBy { it.contentId }
    }

    /** 검수 장소가 아닌 곳 중 자동으로 테마를 붙일 수 있는 곳(한 번만 계산). */
    private val autoTags: Map<String, AutoThemeTag> by lazy {
        val seeded = seeds.map { it.contentId }.toSet()
        tourism.all.asSequence()
            .filter { it.contentId !in seeded && PilotScope.isActive(RegionId(it.regionCode)) }
            .mapNotNull { e -> ThemeAutoTagger.tag(e, tourism.detailsOf(e.contentId).description)?.let { e.contentId to it } }
            .toMap()
    }

    /**
     * 검수한 장소(theme_curation.json) 다음에 자동 분류 장소를 이어 붙인다.
     * 행사 날짜는 여행 예정일 기준. 기간 내라도 실제 개최 시간·휴무는 별도 확인해야 한다.
     * [includeAuto] = false 면 검수 장소만(이전 동작).
     */
    fun cards(
        theme: TravelTheme? = null,
        regionId: RegionId? = null,
        travelDate: LocalDate = LocalDate.now(ZoneId.of("Asia/Seoul")),
        includeAuto: Boolean = true,
    ): List<ThemePlaceCard> {
        val curated = seeds.mapNotNull { seed ->
            val reasons = seed.reasons.mapNotNull { (key, value) ->
                TravelTheme.entries.find { it.name == key }?.let { it to value }
            }.toMap()
            card(seed.contentId, reasons, theme, regionId, travelDate,
                seasons = seed.preferredSeasons.mapNotNull { value -> TravelSeason.entries.find { it.name == value } }.toSet(),
                exposure = WeatherExposure.entries.find { it.name == seed.weatherExposure } ?: WeatherExposure.UNKNOWN,
                weatherEvidence = seed.weatherEvidence)
        }
        if (!includeAuto) return curated
        val auto = autoTags.mapNotNull { (id, tag) ->
            card(id, tag.reasons, theme, regionId, travelDate, tag.preferredSeasons, tag.weatherExposure, weatherEvidence = "",
                requireIntroduction = false)
        }
        return curated + auto
    }

    /** 검수 장소 id(자동 분류와 구분할 때). */
    val curatedIds: Set<String> by lazy { seeds.map { it.contentId }.toSet() }

    private fun card(
        contentId: String, reasons: Map<TravelTheme, String>, theme: TravelTheme?, regionId: RegionId?,
        travelDate: LocalDate, seasons: Set<TravelSeason>, exposure: WeatherExposure, weatherEvidence: String,
        /** 검수 장소는 소개문이 근거라 필수. 자동 분류 장소는 소개문이 없어도 사진·운영 정보만 있으면 쓴다. */
        requireIntroduction: Boolean = true,
    ): ThemePlaceCard? {
        val entry = entries[contentId] ?: return null
        val region = RegionId(entry.regionCode)
        if (!PilotScope.isActive(region) || (regionId != null && region != regionId)) return null
        if (reasons.isEmpty() || (theme != null && theme !in reasons)) return null
        val detail = tourism.detailsOf(entry.contentId)
        if (entry.imageUrl.isBlank() || entry.imageLicense.isBlank() || detail.intro.isEmpty()) return null
        if (requireIntroduction && detail.description.isBlank()) return null
        val eventStart = detail.intro["eventstartdate"]
        val eventEnd = detail.intro["eventenddate"]
        if (entry.contentTypeId == "15") {
            val start = parseDate(eventStart) ?: return null
            val end = parseDate(eventEnd) ?: return null
            if (end < start || travelDate < start || travelDate > end) return null
        }
        val place = tourism.waypointOf(entry) ?: return null
        val image = images[entry.contentId]?.takeIf { it.imageLicense == entry.imageLicense }
        return ThemePlaceCard(
            contentId = entry.contentId, place = place, themes = reasons.keys,
            imageUrl = entry.imageUrl, thumbnailUrl = entry.thumbnailUrl,
            imageLicense = entry.imageLicense, introduction = detail.description,
            operatingInfo = detail.intro, themeReasons = reasons,
            preferredSeasons = seasons, weatherExposure = exposure,
            source = entry.source, collectedAt = entry.collectedAt,
            detailCollectedAt = detail.introCollectedAt,
            eventStartDate = eventStart, eventEndDate = eventEnd,
            localImageAsset = image?.assetPath, imageAttribution = image?.attribution.orEmpty(),
            imageAllowsCropping = image?.allowCropping ?: false,
            weatherEvidence = weatherEvidence,
        )
    }

    private fun parseDate(value: String?): LocalDate? = value?.let {
        runCatching { LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
    }
}
