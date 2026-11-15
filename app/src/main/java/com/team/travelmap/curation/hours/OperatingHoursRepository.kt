package com.team.travelmap.curation.hours

import android.content.Context
import com.team.travelmap.curation.TourismRepository
import com.team.travelmap.core.model.CourseVisitAvailabilityProvider
import com.team.travelmap.core.model.CourseVisitDecision
import com.team.travelmap.core.model.CourseVisitState
import java.security.MessageDigest
import java.time.LocalDateTime
import kotlinx.serialization.json.Json

/** 원본 목록은 유지한다. 데모용 시간 해석 가능 후보와 실제 시각 검사를 별도로 제공한다. */
class OperatingHoursRepository(
    context: Context,
    private val tourism: TourismRepository = TourismRepository(context),
) : CourseVisitAvailabilityProvider {
    private val appContext = context.applicationContext
    private val byId by lazy {
        val text = appContext.assets.open("operating_hours.json")
            .bufferedReader(Charsets.UTF_8).use { it.readText() }
        Json { ignoreUnknownKeys = true }.decodeFromString<List<OperatingHours>>(text).associateBy { it.contentId }
    }
    private val entries by lazy { tourism.all.associateBy { it.contentId } }
    private val verifiedById by lazy { byId.mapValues { (_, hours) -> verifySource(hours) } }

    /** 해석 가능 여부다. 이 값만으로 도착 시 영업 중이라고 판정하지 않는다. */
    fun isDemoReady(contentId: String): Boolean = verified(contentId)?.status == HoursStatus.READY

    /** 검수 화면에는 REVIEW·MISSING 원문도 제공한다. */
    fun raw(contentId: String): OperatingHours? = byId[contentId]

    fun assess(contentId: String, arrival: LocalDateTime, stayMinutes: Int): HoursAssessment {
        val current = verified(contentId)
        if (byId[contentId] != null && current == null) {
            return HoursAssessment(VisitAvailability.NEEDS_CHECK, HoursReason.SOURCE_CHANGED)
        }
        return OperatingHoursEvaluator.assess(current, arrival, stayMinutes)
    }

    override fun check(contentId: String, arrival: LocalDateTime, stayMinutes: Int): CourseVisitDecision {
        val assessment = assess(contentId, arrival, stayMinutes)
        val state = when (assessment.availability) {
            VisitAvailability.EXPECTED_AVAILABLE -> CourseVisitState.AVAILABLE
            VisitAvailability.EXPECTED_UNAVAILABLE -> CourseVisitState.UNAVAILABLE
            VisitAvailability.NEEDS_CHECK -> CourseVisitState.UNKNOWN
        }
        return CourseVisitDecision(state, assessment.reason.name)
    }

    private fun verified(contentId: String): OperatingHours? = verifiedById[contentId]

    private fun verifySource(hours: OperatingHours): OperatingHours? {
        val entry = entries[hours.contentId] ?: return null
        val intro = tourism.detailsOf(hours.contentId).intro
        val fields = when (entry.contentTypeId) {
            "12" -> "usetime" to "restdate"
            "14" -> "usetimeculture" to "restdateculture"
            "15" -> "playtime" to null
            "39" -> "opentimefood" to "restdatefood"
            else -> return null
        }
        val text = listOf(entry.contentTypeId, intro[fields.first].orEmpty().trim(),
            fields.second?.let { intro[it] }.orEmpty().trim(),
            intro["eventstartdate"].orEmpty(), intro["eventenddate"].orEmpty()).joinToString("\n")
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return hours.takeIf { it.sourceFingerprint == hash }
    }
}
