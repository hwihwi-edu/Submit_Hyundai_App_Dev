package com.team.travelmap

import com.team.travelmap.core.model.TravelSeason
import com.team.travelmap.core.model.TravelTheme
import com.team.travelmap.core.model.WeatherExposure
import com.team.travelmap.curation.ThemeAutoTagger
import com.team.travelmap.curation.TourismEntry
import org.junit.Assert.*
import org.junit.Test

class ThemeAutoTaggerTest {
    private fun entry(type: String, code: String, name: String = "장소") = TourismEntry(
        "1", name, "30140", type, 36.3, 127.4, "주소", "", "", "테스트",
        classification = mapOf("lclsSystm1" to code.take(2), "lclsSystm2" to code, "lclsSystm3" to code + "00"),
    )

    @Test fun `분류 코드로 테마를 붙이고 자동 분류 문구를 남긴다`() {
        assertEquals(setOf(TravelTheme.HISTORY), ThemeAutoTagger.tag(entry("12", "HS01"), "")!!.reasons.keys)
        assertEquals(setOf(TravelTheme.CAFE), ThemeAutoTagger.tag(entry("39", "FD05"), "")!!.reasons.keys)
        assertEquals(setOf(TravelTheme.FOOD), ThemeAutoTagger.tag(entry("39", "FD01"), "")!!.reasons.keys)
        assertEquals(setOf(TravelTheme.FESTIVAL), ThemeAutoTagger.tag(entry("15", "EV01"), "")!!.reasons.keys)
        val park = ThemeAutoTagger.tag(entry("12", "VE03"), "")!!
        assertEquals(setOf(TravelTheme.HEALING), park.reasons.keys)
        assertTrue(park.reasons.values.all { ThemeAutoTagger.isAuto(it) })
    }

    @Test fun `자연·공원은 야외로, 그 밖은 실내로 단정하지 않는다`() {
        assertEquals(WeatherExposure.OUTDOOR, ThemeAutoTagger.tag(entry("12", "NA04"), "")!!.weatherExposure)
        assertEquals(WeatherExposure.UNKNOWN, ThemeAutoTagger.tag(entry("39", "FD05"), "")!!.weatherExposure)
    }

    @Test fun `애매한 분류는 붙이지 않는다`() {
        assertNull(ThemeAutoTagger.tag(entry("14", "VE07", "갤러리 현대"), "현대 미술 전시"))
        assertNull(ThemeAutoTagger.tag(entry("12", "EX07"), "체험 마을"))
        assertNull(ThemeAutoTagger.tag(entry("38", "SH04", "약국"), ""))
    }

    @Test fun `문화시설은 박물관·민속 같은 낱말이 있을 때만 역사`() {
        val museum = ThemeAutoTagger.tag(entry("14", "VE07", "옛터민속박물관"), "")!!
        assertEquals(setOf(TravelTheme.HISTORY), museum.reasons.keys)
    }

    @Test fun `계절 낱말은 자연·행사에만 계절 테마와 계절을 붙인다`() {
        val cherry = ThemeAutoTagger.tag(entry("12", "NA04"), "봄이면 벚꽃이 흐드러지게 핀다")!!
        assertEquals(setOf(TravelTheme.HEALING, TravelTheme.SEASONAL), cherry.reasons.keys)
        assertEquals(setOf(TravelSeason.SPRING), cherry.preferredSeasons)
        val food = ThemeAutoTagger.tag(entry("39", "FD01"), "벚꽃길 앞 식당")!!
        assertEquals(setOf(TravelTheme.FOOD), food.reasons.keys)
        assertTrue(food.preferredSeasons.isEmpty())
    }

    @Test fun `도심 광장·묘소는 빼고, 이름이 카페인 음식점은 카페로`() {
        assertNull(ThemeAutoTagger.tag(entry("12", "VE03", "대전역 동광장"), ""))
        assertNull(ThemeAutoTagger.tag(entry("12", "HS01", "김정선생묘소일원"), ""))
        val cafe = ThemeAutoTagger.tag(entry("39", "FD01", "한옥카페 소담"), "")!!
        assertEquals(setOf(TravelTheme.CAFE), cafe.reasons.keys)
    }
}
