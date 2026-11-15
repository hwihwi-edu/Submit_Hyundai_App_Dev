package com.team.travelmap.curation

import com.team.travelmap.core.model.TravelSeason
import com.team.travelmap.core.model.TravelTheme
import com.team.travelmap.core.model.WeatherExposure

/** 자동으로 붙인 테마. [reasons] 문구에 "자동 분류"를 넣어 검수한 장소와 구분한다. */
data class AutoThemeTag(
    val reasons: Map<TravelTheme, String>,
    val preferredSeasons: Set<TravelSeason> = emptySet(),
    val weatherExposure: WeatherExposure = WeatherExposure.UNKNOWN,
)

/**
 * 담당: 3번. 사람이 검수한 25곳(theme_curation.json)만으로는 코스가 적고 반복돼서,
 * 관광공사 분류 코드(lclsSystm)와 소개문 낱말로 테마를 자동으로 붙인다(2026-11-15 사용자 결정).
 *
 * - 검수 장소는 그대로 두고, 그 밖의 장소에만 쓴다. 문구에 "자동 분류 · 검수 전"을 남긴다.
 * - 애매한 분류(갤러리·문화원·체험·쇼핑 등)는 붙이지 않는다. 잘못 붙이는 것보다 빠지는 편이 낫다.
 * - 실내 여부는 원문 근거가 없으므로 INDOOR 로 단정하지 않는다. 자연·공원·전망대만 OUTDOOR.
 */
object ThemeAutoTagger {
    private data class Rule(val prefix: String, val theme: TravelTheme, val label: String, val outdoor: Boolean = false)

    /** 관광공사 분류(lclsSystm2) 앞자리 → 테마. 위에서부터 먼저 맞는 것. */
    private val RULES = listOf(
        Rule("HS", TravelTheme.HISTORY, "역사관광"),
        Rule("NA03", TravelTheme.HEALING, "생태·습지", outdoor = true),
        Rule("NA", TravelTheme.HEALING, "자연관광", outdoor = true),
        Rule("EX05", TravelTheme.HEALING, "자연 체험", outdoor = true),
        Rule("VE01", TravelTheme.HEALING, "전망대", outdoor = true),
        Rule("VE03", TravelTheme.HEALING, "공원", outdoor = true),
        Rule("VE04", TravelTheme.HEALING, "테마거리·산책", outdoor = true),
        Rule("EV", TravelTheme.FESTIVAL, "축제·공연·행사"),
        Rule("FD05", TravelTheme.CAFE, "카페·찻집"),
        Rule("FD01", TravelTheme.FOOD, "한식"),
        Rule("FD02", TravelTheme.FOOD, "외국 음식"),
        Rule("FD03", TravelTheme.FOOD, "빵·간식"),
        Rule("FD04", TravelTheme.FOOD, "주점·기타 음식"),
    )

    /** 소개문 낱말 → 계절. 이 낱말이 있으면 SEASONAL 테마도 함께 붙인다. */
    private val SEASON_WORDS = listOf(
        "벚꽃" to TravelSeason.SPRING, "진달래" to TravelSeason.SPRING, "철쭉" to TravelSeason.SPRING,
        "유채" to TravelSeason.SPRING, "수국" to TravelSeason.SUMMER, "해수욕" to TravelSeason.SUMMER,
        "단풍" to TravelSeason.AUTUMN, "억새" to TravelSeason.AUTUMN, "은행나무" to TravelSeason.AUTUMN,
        "눈꽃" to TravelSeason.WINTER, "설경" to TravelSeason.WINTER,
    )

    private val CAFE_NAME_WORDS = listOf("카페", "커피", "찻집", "다방", "티하우스")

    /** 박물관·전시관은 갤러리(VE07)와 섞여 있어 이름·소개문에 이 낱말이 있을 때만 역사로 본다. */
    private val HISTORY_WORDS = listOf("박물관", "역사관", "기념관", "민속", "유적", "향교", "서원", "사찰")

    /** 방문 가치가 낮거나 장소가 아닌 것(도심 광장, 묘소·비석, 전국 단위 할인 행사). */
    private val SKIP_NAME_WORDS = listOf("광장", "묘소", "유허비", "그랜드세일", "디자인위크")

    fun tag(entry: TourismEntry, description: String): AutoThemeTag? {
        if (SKIP_NAME_WORDS.any { it in entry.name }) return null
        val code = entry.classification["lclsSystm2"].orEmpty()
        val reasons = linkedMapOf<TravelTheme, String>()
        var outdoor = false
        RULES.firstOrNull { code.startsWith(it.prefix) }?.let { rule ->
            reasons[rule.theme] = "관광공사 분류(${rule.label})로 자동 분류 · 검수 전"
            outdoor = rule.outdoor
        }
        // 음식점인데 이름이 카페·커피·찻집이면 카페로(분류가 한식 등으로 잘못 들어간 곳).
        if (entry.contentTypeId == "39" && TravelTheme.CAFE !in reasons && CAFE_NAME_WORDS.any { it in entry.name }) {
            reasons.remove(TravelTheme.FOOD)
            reasons[TravelTheme.CAFE] = "이름(${CAFE_NAME_WORDS.first { it in entry.name }})으로 자동 분류 · 검수 전"
        }
        if (entry.contentTypeId == "14" && TravelTheme.HISTORY !in reasons) {
            HISTORY_WORDS.firstOrNull { it in entry.name || it in description }?.let { word ->
                reasons[TravelTheme.HISTORY] = "소개문의 '$word'로 자동 분류 · 검수 전"
            }
        }
        if (entry.contentTypeId == "15" && TravelTheme.FESTIVAL !in reasons) {
            reasons[TravelTheme.FESTIVAL] = "관광공사 유형(행사)으로 자동 분류 · 검수 전"
        }
        val seasons = SEASON_WORDS.filter { (word, _) -> word in description }.map { it.second }.toSet()
        // 계절 테마는 자연·공원·행사처럼 그 계절에 "가 볼 곳"일 때만(음식점 소개의 '벚꽃' 같은 우연은 제외).
        if (seasons.isNotEmpty() && reasons.keys.any { it == TravelTheme.HEALING || it == TravelTheme.FESTIVAL }) {
            val word = SEASON_WORDS.first { (w, _) -> w in description }.first
            reasons[TravelTheme.SEASONAL] = "소개문의 '$word'로 자동 분류 · 검수 전"
        }
        if (reasons.isEmpty()) return null
        return AutoThemeTag(
            reasons = reasons,
            preferredSeasons = if (TravelTheme.SEASONAL in reasons) seasons else emptySet(),
            weatherExposure = if (outdoor) WeatherExposure.OUTDOOR else WeatherExposure.UNKNOWN,
        )
    }

    /** 자동 분류 문구인지(화면에서 "검수 전" 표시나 순위 감점에 쓸 수 있다). */
    fun isAuto(reason: String): Boolean = "자동 분류" in reason
}
