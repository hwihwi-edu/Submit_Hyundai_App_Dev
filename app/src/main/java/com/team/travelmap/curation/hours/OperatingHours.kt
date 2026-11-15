package com.team.travelmap.curation.hours

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.serialization.Serializable

@Serializable
enum class HoursStatus { READY, REVIEW, MISSING }

@Serializable
data class HoursBreak(val startMinute: Int, val endMinute: Int)

@Serializable
data class HoursWindow(
    val weekdays: List<Int>, val openMinute: Int, val closeMinute: Int,
    val breaks: List<HoursBreak> = emptyList(),
    val lastOrderMinute: Int? = null, val lastAdmissionMinute: Int? = null,
)

/** 담당: 3번. 등록 원문을 해석한 결과이며 현장의 실제 영업 여부가 아니다. */
@Serializable
data class OperatingHours(
    val contentId: String, val contentTypeId: String, val status: HoursStatus, val reason: String,
    val rawHours: String, val rawRest: String, val eventStartDate: String, val eventEndDate: String,
    val sourceFingerprint: String, val sourceCollectedAt: String,
    val closedWeekdays: List<Int>, val windows: List<HoursWindow>,
)

enum class VisitAvailability { EXPECTED_AVAILABLE, EXPECTED_UNAVAILABLE, NEEDS_CHECK }
enum class HoursReason {
    REGISTERED_HOURS, UNKNOWN_HOURS, SOURCE_CHANGED, EVENT_DATE, CLOSED_WEEKDAY,
    OUTSIDE_HOURS, BREAK_TIME, LAST_ORDER, LAST_ADMISSION, VISIT_END_AFTER_CLOSE,
    NEXT_DAY_REVIEW, INVALID_INPUT,
}
data class HoursAssessment(val availability: VisitAvailability, val reason: HoursReason)

/** 입력 시각은 한국 시간. 임의 대기·방문시간·공휴일 추정 없이 등록된 범위만 비교한다. */
object OperatingHoursEvaluator {
    fun assess(hours: OperatingHours?, arrival: LocalDateTime, stayMinutes: Int): HoursAssessment {
        fun unknown(reason: HoursReason) = HoursAssessment(VisitAvailability.NEEDS_CHECK, reason)
        fun closed(reason: HoursReason) = HoursAssessment(VisitAvailability.EXPECTED_UNAVAILABLE, reason)
        if (stayMinutes < 0) return unknown(HoursReason.INVALID_INPUT)
        if (hours == null || hours.status != HoursStatus.READY) return unknown(HoursReason.UNKNOWN_HOURS)
        val day = arrival.toLocalDate()
        if (hours.contentTypeId == "15") {
            fun date(raw: String): LocalDate? = runCatching {
                LocalDate.parse(raw, DateTimeFormatter.BASIC_ISO_DATE)
            }.getOrNull()
            val start = date(hours.eventStartDate) ?: return unknown(HoursReason.UNKNOWN_HOURS)
            val end = date(hours.eventEndDate) ?: return unknown(HoursReason.UNKNOWN_HOURS)
            if (day < start || day > end) return closed(HoursReason.EVENT_DATE)
        }
        if (day.dayOfWeek.value in hours.closedWeekdays) return closed(HoursReason.CLOSED_WEEKDAY)
        val leave = arrival.plusMinutes(stayMinutes.toLong())
        if (leave > day.plusDays(1).atStartOfDay()) return unknown(HoursReason.NEXT_DAY_REVIEW)
        val matching = hours.windows.filter { day.dayOfWeek.value in it.weekdays }
        if (matching.isEmpty()) return unknown(HoursReason.UNKNOWN_HOURS)
        var failed: HoursReason = HoursReason.OUTSIDE_HOURS
        for (window in matching) {
            if (window.openMinute !in 0..1439 || window.closeMinute !in 1..1440 || window.openMinute >= window.closeMinute) {
                return unknown(HoursReason.INVALID_INPUT)
            }
            val begin = day.atStartOfDay().plusMinutes(window.openMinute.toLong())
            val end = day.atStartOfDay().plusMinutes(window.closeMinute.toLong())
            if (arrival < begin || arrival >= end) continue
            if (window.lastAdmissionMinute?.let { arrival >= day.atStartOfDay().plusMinutes(it.toLong()) } == true) {
                failed = HoursReason.LAST_ADMISSION
                continue
            }
            if (window.lastOrderMinute?.let { arrival >= day.atStartOfDay().plusMinutes(it.toLong()) } == true) {
                failed = HoursReason.LAST_ORDER
                continue
            }
            if (window.breaks.any { pause ->
                    val pauseStart = day.atStartOfDay().plusMinutes(pause.startMinute.toLong())
                    val pauseEnd = day.atStartOfDay().plusMinutes(pause.endMinute.toLong())
                    (arrival >= pauseStart && arrival < pauseEnd) || (arrival < pauseEnd && leave > pauseStart)
                }) {
                failed = HoursReason.BREAK_TIME
                continue
            }
            if (leave > end) {
                failed = HoursReason.VISIT_END_AFTER_CLOSE
                continue
            }
            return HoursAssessment(VisitAvailability.EXPECTED_AVAILABLE, HoursReason.REGISTERED_HOURS)
        }
        return closed(failed)
    }
}
