package com.team.travelmap

import com.team.travelmap.core.model.*
import com.team.travelmap.curation.ThemeRecommendationInput
import com.team.travelmap.curation.ThemeRecommender
import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ThemeRecommenderTest {
    private val autumn = LocalDate.of(2026, 11, 15)

    private fun card(
        id: String, vararg themes: TravelTheme,
        exposure: WeatherExposure = WeatherExposure.UNKNOWN,
        seasons: Set<TravelSeason> = emptySet(),
        category: PlaceCategory = PlaceCategory.SIGHT,
        start: String? = null, end: String? = null,
    ) = ThemePlaceCard(
        contentId = id, place = Place("장소$id", GeoPoint(36.3, 127.3), category, RegionId("30140")),
        themes = themes.toSet(), imageUrl = "", thumbnailUrl = "", imageLicense = "Type1", introduction = "",
        operatingInfo = emptyMap(), themeReasons = emptyMap(), preferredSeasons = seasons,
        weatherExposure = exposure, source = "테스트", collectedAt = "", detailCollectedAt = "",
        eventStartDate = start, eventEndDate = end,
    )

    private fun rank(cards: List<ThemePlaceCard>, preferred: Set<TravelTheme> = emptySet(),
                     date: LocalDate = autumn, rain: Boolean? = null) =
        ThemeRecommender.rank(ThemeRecommendationInput(cards, preferred, date, rain))

    @Test fun `장소가 있는 테마만 모두 돌려준다`() {
        val result = rank(listOf(card("1", TravelTheme.FOOD), card("2", TravelTheme.CAFE, TravelTheme.FOOD)))
        assertEquals(setOf(TravelTheme.FOOD, TravelTheme.CAFE), result.map { it.theme }.toSet())
        assertEquals(2, result.first { it.theme == TravelTheme.FOOD }.placeCount)
    }

    @Test fun `장소가 없으면 빈 목록`() {
        assertTrue(rank(emptyList()).isEmpty())
    }

    @Test fun `누구랑에 맞는 테마가 장소 수가 적어도 앞선다`() {
        val cards = listOf(card("1", TravelTheme.FOOD), card("2", TravelTheme.FOOD), card("3", TravelTheme.FOOD),
            card("4", TravelTheme.HEALING))
        val result = rank(cards, preferred = setOf(TravelTheme.HEALING))
        assertEquals(TravelTheme.HEALING, result.first().theme)
        assertTrue(result.first().reasons.any { "함께" in it })
    }

    @Test fun `지금 계절 장소가 있는 테마를 우대한다`() {
        val cards = listOf(card("1", TravelTheme.HISTORY), card("2", TravelTheme.HEALING, seasons = setOf(TravelSeason.AUTUMN)))
        assertEquals(TravelTheme.HEALING, rank(cards).first().theme)
        val spring = rank(cards, date = LocalDate.of(2026, 4, 1))
        assertEquals(TravelTheme.HISTORY, spring.first().theme) // 계절이 안 맞으면 테마 순서(역사 < 힐링)
    }

    @Test fun `비 예보면 실내 장소 테마가 야외 테마보다 앞선다`() {
        val cards = listOf(card("1", TravelTheme.HEALING, exposure = WeatherExposure.OUTDOOR),
            card("2", TravelTheme.CAFE, exposure = WeatherExposure.INDOOR))
        assertEquals(TravelTheme.CAFE, rank(cards, rain = true).first().theme)
        val dry = rank(cards, rain = false)
        assertEquals(dry.first { it.theme == TravelTheme.HEALING }.score, dry.first { it.theme == TravelTheme.CAFE }.score, 0.0)
    }

    @Test fun `날씨를 모르면 실내외로 점수를 바꾸지 않는다`() {
        val cards = listOf(card("1", TravelTheme.HEALING, exposure = WeatherExposure.OUTDOOR),
            card("2", TravelTheme.CAFE, exposure = WeatherExposure.INDOOR))
        val unknown = rank(cards, rain = null)
        assertEquals(unknown[0].score, unknown[1].score, 0.0)
    }

    @Test fun `기간이 지난 축제는 축제 테마 장소로 세지 않는다`() {
        val ended = card("1", TravelTheme.FESTIVAL, category = PlaceCategory.FESTIVAL, start = "20260101", end = "20260110")
        val now = card("2", TravelTheme.FESTIVAL, category = PlaceCategory.FESTIVAL, start = "20261101", end = "20261130")
        assertTrue(rank(listOf(ended)).isEmpty())
        assertEquals(1, rank(listOf(ended, now)).single().placeCount)
    }

    @Test fun `계절 판정`() {
        assertEquals(TravelSeason.WINTER, ThemeRecommender.seasonOf(LocalDate.of(2026, 12, 1)))
        assertEquals(TravelSeason.SPRING, ThemeRecommender.seasonOf(LocalDate.of(2026, 3, 1)))
        assertEquals(TravelSeason.SUMMER, ThemeRecommender.seasonOf(LocalDate.of(2026, 8, 31)))
        assertEquals(TravelSeason.AUTUMN, ThemeRecommender.seasonOf(LocalDate.of(2026, 11, 30)))
    }

    @Test fun `알아서 고르기는 상위 3개 안에서 점수 높은 테마가 더 자주 나온다`() {
        val ranking = listOf(
            com.team.travelmap.curation.ThemeRecommendation(TravelTheme.FOOD, 5.0, 3, emptyList()),
            com.team.travelmap.curation.ThemeRecommendation(TravelTheme.CAFE, 1.0, 1, emptyList()),
            com.team.travelmap.curation.ThemeRecommendation(TravelTheme.HISTORY, 1.0, 1, emptyList()),
            com.team.travelmap.curation.ThemeRecommendation(TravelTheme.HEALING, 9.0, 1, emptyList()),
        )
        val random = kotlin.random.Random(7)
        val picks = (1..300).map { ThemeRecommender.pick(ranking, random)!!.theme }
        assertFalse(TravelTheme.HEALING in picks) // 4위는 후보 밖
        assertEquals(3, picks.toSet().size)
        assertTrue(picks.count { it == TravelTheme.FOOD } > picks.count { it == TravelTheme.CAFE })
        assertNull(ThemeRecommender.pick(emptyList(), random))
    }

    @Test fun `도시 예보는 여행 시간대에 비가 한 번이라도 있으면 비, 결측이 섞이면 모름`() {
        val base = java.time.LocalDateTime.of(2026, 11, 15, 9, 0)
        fun snap(vararg pty: String?) = com.team.travelmap.curation.weather.WeatherSnapshot(
            com.team.travelmap.curation.weather.WeatherKind.FORECAST,
            com.team.travelmap.curation.weather.WeatherGrid(67, 100), base.minusHours(4), java.time.Instant.EPOCH,
            pty.mapIndexedNotNull { i, v -> v?.let { com.team.travelmap.curation.weather.WeatherFrame(base.plusHours(i.toLong()), mapOf("PTY" to it)) } })
        val eval = com.team.travelmap.curation.weather.CityPrecipitation.Companion::evaluate
        assertEquals(true, eval(snap("0", "1", "0"), base.plusMinutes(20), base.plusHours(2)))
        assertEquals(false, eval(snap("0", "0", "0"), base, base.plusHours(2)))
        assertNull(eval(snap("0", null, "0"), base, base.plusHours(2)))
    }
}
