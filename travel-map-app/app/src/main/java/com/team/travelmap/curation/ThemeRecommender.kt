package com.team.travelmap.curation

import com.team.travelmap.core.model.PlaceCategory
import com.team.travelmap.core.model.ThemePlaceCard
import com.team.travelmap.core.model.TravelSeason
import com.team.travelmap.core.model.TravelTheme
import com.team.travelmap.core.model.WeatherExposure
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.random.Random

/** 추천 테마 한 줄. 화면은 순서대로 칩을 그리고 [reasons]를 짧은 설명으로 쓸 수 있다. */
data class ThemeRecommendation(
    val theme: TravelTheme,
    val score: Double,
    /** 이 테마로 쓸 수 있는 장소 수(날짜가 지난 축제 제외). */
    val placeCount: Int,
    val reasons: List<String>,
)

/** 테마 추천 입력. 날씨를 모르면 [precipitationExpected] = null 이고 점수에 넣지 않는다. */
data class ThemeRecommendationInput(
    /** 고른 도시에서 코스에 쓸 수 있는 장소 카드(운영시간 검수 등은 호출자가 거른다). */
    val cards: List<ThemePlaceCard>,
    /** "누구랑" 답에 맞춰 우대할 테마. */
    val preferredThemes: Set<TravelTheme>,
    val travelDate: LocalDate,
    val precipitationExpected: Boolean? = null,
)

/**
 * 담당: 3번. "누구랑·얼마나·어디까지" 답으로 테마 순위를 매긴다(기획: 답하면 테마와 코스를 골라 줌, 이슈 #9: 추천 테마를 여러 개 보여 줌).
 * 장소가 하나도 없는 테마는 돌려주지 않는다. 점수 기준(2026-11-15 사용자 결정)은 아래 상수에 모아 둔다.
 */
object ThemeRecommender {
    /** "누구랑"에 맞는 테마 가산점. */
    const val COMPANION_BONUS = 3.0
    /** 장소 하나당 가산점과 상한 장소 수. 장소가 많을수록 서로 다른 코스를 만들기 쉽다. */
    const val PLACE_BONUS = 0.5
    const val PLACE_BONUS_CAP = 4
    /** 지금 계절에 어울리는 장소 하나당 가산점과 상한. */
    const val SEASON_BONUS = 1.0
    const val SEASON_BONUS_CAP = 2
    /** 비·눈이 오면 (실내 비율 − 야외 비율) × 이 값을 더한다. 미확인 장소는 실내로 보지 않는다. */
    const val WEATHER_WEIGHT = 2.0

    fun rank(input: ThemeRecommendationInput): List<ThemeRecommendation> {
        val season = seasonOf(input.travelDate)
        val usable = input.cards.filter { validEvent(it, input.travelDate) }
        return TravelTheme.entries.mapNotNull { theme ->
            val places = usable.filter { theme in it.themes }
            if (places.isEmpty()) return@mapNotNull null
            var score = 0.0
            val reasons = mutableListOf<String>()
            if (theme in input.preferredThemes) {
                score += COMPANION_BONUS
                reasons += "함께 가는 사람과 어울려요"
            }
            score += minOf(places.size, PLACE_BONUS_CAP) * PLACE_BONUS
            val inSeason = places.count { season in it.preferredSeasons }
            if (inSeason > 0) {
                score += minOf(inSeason, SEASON_BONUS_CAP) * SEASON_BONUS
                reasons += "${season.displayName}에 가기 좋아요"
            }
            if (input.precipitationExpected == true) {
                val indoor = places.count { it.weatherExposure == WeatherExposure.INDOOR } +
                    places.count { it.weatherExposure == WeatherExposure.MIXED } * 0.5
                val outdoor = places.count { it.weatherExposure == WeatherExposure.OUTDOOR }
                val delta = (indoor - outdoor) / places.size * WEATHER_WEIGHT
                score += delta
                if (delta > 0) reasons += "비·눈 예보에도 실내 장소가 있어요"
            }
            if (reasons.isEmpty()) reasons += "장소 ${places.size}곳"
            ThemeRecommendation(theme, score, places.size, reasons)
        }.sortedWith(compareByDescending<ThemeRecommendation> { it.score }.thenBy { it.theme.ordinal })
    }

    /**
     * "알아서" 추천에서 중심 테마를 고른다. 상위 [top]개 중 점수에 비례한 확률로 뽑아
     * 같은 답이어도 매번 다른 테마가 나올 수 있게 한다(1위가 가장 자주 나온다).
     */
    fun pick(ranking: List<ThemeRecommendation>, random: Random, top: Int = PICK_TOP): ThemeRecommendation? {
        val pool = ranking.take(top)
        if (pool.isEmpty()) return null
        val weights = pool.map { it.score.coerceAtLeast(MIN_PICK_WEIGHT) }
        var roll = random.nextDouble() * weights.sum()
        pool.forEachIndexed { i, rec -> roll -= weights[i]; if (roll < 0) return rec }
        return pool.last()
    }

    /** 무작위 중심 테마 후보 수와 최소 가중치(점수 0 이하 테마도 가끔 나오게). */
    const val PICK_TOP = 3
    const val MIN_PICK_WEIGHT = 0.5

    fun seasonOf(date: LocalDate): TravelSeason = when (date.monthValue) {
        3, 4, 5 -> TravelSeason.SPRING
        6, 7, 8 -> TravelSeason.SUMMER
        9, 10, 11 -> TravelSeason.AUTUMN
        else -> TravelSeason.WINTER
    }

    /** ThemeCoursePlanner 와 같은 기준: 행사는 기간 안일 때만 쓴다. */
    private fun validEvent(card: ThemePlaceCard, day: LocalDate): Boolean {
        if (card.place.category != PlaceCategory.FESTIVAL) return true
        val start = runCatching { LocalDate.parse(card.eventStartDate, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
        val end = runCatching { LocalDate.parse(card.eventEndDate, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
        return start != null && end != null && start <= day && day <= end
    }

    private val TravelSeason.displayName: String get() = when (this) {
        TravelSeason.SPRING -> "봄"
        TravelSeason.SUMMER -> "여름"
        TravelSeason.AUTUMN -> "가을"
        TravelSeason.WINTER -> "겨울"
    }
}
