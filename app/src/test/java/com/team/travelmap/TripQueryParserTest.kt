package com.team.travelmap

import com.team.travelmap.core.model.TravelTheme
import com.team.travelmap.ui.Companion
import com.team.travelmap.ui.RuleTripQueryParser
import com.team.travelmap.ui.TripLength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripQueryParserTest {
    private fun p(s: String) = RuleTripQueryParser.parse(s)

    @Test fun girlfriendOneNightBusan() {
        val q = p("나 여자친구랑 1박2일로 부산 갈거야")
        assertEquals(Companion.TWO, q.companion)
        assertEquals(2, q.days)
        assertNull(q.length)
        assertEquals("26", q.cityCode)
    }

    @Test fun familyHalfDayDaejeonFood() {
        val q = p("가족이랑 반나절 대전 맛집")
        assertEquals(Companion.FAMILY, q.companion)
        assertEquals(1, q.days)
        assertEquals(TripLength.HALF_DAY, q.length)
        assertEquals("30", q.cityCode)
        assertEquals(TravelTheme.FOOD, q.theme)
    }

    @Test fun twoNightsThreeDaysAndDistrictName() {
        val q = p("친구들이랑 2박 3일 해운대")
        assertEquals(Companion.FRIENDS, q.companion)
        assertEquals(3, q.days)
        assertEquals("26", q.cityCode)
    }

    @Test fun hoursAndUnknowns() {
        val q = p("혼자 2시간 산책")
        assertEquals(Companion.ALONE, q.companion)
        assertEquals(TripLength.TWO_HOURS, q.length)
        assertNull(q.cityCode)
        assertEquals(TravelTheme.HEALING, q.theme)
    }

    @Test fun longTripIsCapped() {
        assertEquals(RuleTripQueryParser.MAX_DAYS, p("서울 10박11일").days)
    }
}
