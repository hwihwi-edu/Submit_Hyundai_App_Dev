package com.team.travelmap

import com.team.travelmap.curation.hours.*
import java.time.LocalDateTime
import org.junit.Assert.*
import org.junit.Test

class OperatingHoursTest {
    private fun hours(window: HoursWindow = HoursWindow((1..7).toList(), 600, 1200)) = OperatingHours(
        contentId="1", contentTypeId="39", status=HoursStatus.READY, reason="등록 정보",
        rawHours="10:00~20:00", rawRest="연중무휴", eventStartDate="", eventEndDate="",
        sourceFingerprint="테스트", sourceCollectedAt="테스트", closedWeekdays=emptyList(), windows=listOf(window),
    )
    private fun at(hour: Int, minute: Int = 0) = LocalDateTime.of(2026,11,15,hour,minute)

    @Test fun `개점부터 폐점 전까지 체류 완료 가능한지 비교한다`() {
        assertEquals(VisitAvailability.EXPECTED_AVAILABLE, OperatingHoursEvaluator.assess(hours(),at(10),30).availability)
        assertEquals(HoursReason.OUTSIDE_HOURS, OperatingHoursEvaluator.assess(hours(),at(20),0).reason)
        assertEquals(HoursReason.VISIT_END_AFTER_CLOSE, OperatingHoursEvaluator.assess(hours(),at(19,50),20).reason)
    }

    @Test fun `준비시간 안 도착과 체류 중 준비시간 진입을 구분한다`() {
        val data = hours(HoursWindow((1..7).toList(),600,1200,breaks=listOf(HoursBreak(900,1020))))
        assertEquals(HoursReason.BREAK_TIME, OperatingHoursEvaluator.assess(data,at(15),10).reason)
        assertEquals(HoursReason.BREAK_TIME, OperatingHoursEvaluator.assess(data,at(14,50),20).reason)
        assertEquals(VisitAvailability.EXPECTED_AVAILABLE, OperatingHoursEvaluator.assess(data,at(17),20).availability)
    }

    @Test fun `마지막 주문과 입장 마감은 폐점과 별도로 적용한다`() {
        val order = hours(HoursWindow((1..7).toList(),600,1200,lastOrderMinute=1160))
        assertEquals(HoursReason.LAST_ORDER, OperatingHoursEvaluator.assess(order,at(19,20),10).reason)
        val entry = hours(HoursWindow((1..7).toList(),600,1200,lastAdmissionMinute=1020))
        assertEquals(HoursReason.LAST_ADMISSION, OperatingHoursEvaluator.assess(entry,at(17),10).reason)
    }

    @Test fun `휴무 요일에는 시간 안이어도 방문 가능으로 표시하지 않는다`() {
        val data = hours().copy(closedWeekdays=listOf(7))
        assertEquals(HoursReason.CLOSED_WEEKDAY, OperatingHoursEvaluator.assess(data,at(12),30).reason)
    }

    @Test fun `미확인과 미제공 자료는 영업 불가로 단정하지 않는다`() {
        assertEquals(VisitAvailability.NEEDS_CHECK, OperatingHoursEvaluator.assess(null,at(12),30).availability)
        assertEquals(VisitAvailability.NEEDS_CHECK,
            OperatingHoursEvaluator.assess(hours().copy(status=HoursStatus.REVIEW),at(12),30).availability)
    }

    @Test fun `행사 기간 밖은 운영시간이 맞아도 제외한다`() {
        val data = hours().copy(contentTypeId="15",eventStartDate="20261113",eventEndDate="20261114")
        assertEquals(HoursReason.EVENT_DATE, OperatingHoursEvaluator.assess(data,at(12),30).reason)
    }

    @Test fun `다음 날로 넘어가는 체류는 다음 단계의 날짜별 검수를 요구한다`() {
        val data = hours(HoursWindow((1..7).toList(),0,1440))
        assertEquals(HoursReason.NEXT_DAY_REVIEW, OperatingHoursEvaluator.assess(data,at(23,50),20).reason)
    }
}
