package com.team.travelmap.ui

import com.team.travelmap.core.geo.Geo
import com.team.travelmap.core.model.CourseTravelCost
import com.team.travelmap.core.model.CourseTravelCostProvider
import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.core.model.PlaceCategory
import com.team.travelmap.core.model.ThemePlaceCard
import com.team.travelmap.core.model.TravelTheme
import com.team.travelmap.curation.TourismEntry
import com.team.travelmap.curation.TourismRepository

/**
 * 담당: 1번. 코스 화면용 임시 추정값(사용자 결정 2026-11-15: "추정값 + '추정' 표시").
 * 2번 실제 이동 비용·3번 체류시간 데이터가 오면 이 파일의 값만 바꾼다. 화면에는 항상 "추정"으로 표시한다.
 */
internal object CourseEstimates {
    /** 직선거리에 곱하는 도로 우회 비율. */
    const val ROAD_FACTOR = 1.3
    /** 도심 평균 주행 속도(km/h). */
    const val CITY_KMH = 40.0

    /** 유형별 머무는 시간(분). 카페 테마 장소는 40분, 행사는 90분, 나머지 60분. */
    fun stayMinutes(card: ThemePlaceCard): Int = when {
        card.place.category == PlaceCategory.FESTIVAL -> 90
        TravelTheme.CAFE in card.themes -> 40
        else -> 60
    }

    /** 직선거리 × 1.3 을 시속 40km 로 나눈 이동 시간. estimated = true. */
    val travel = CourseTravelCostProvider { from, to ->
        val km = Geo.distanceMeters(from, to) / 1000.0 * ROAD_FACTOR
        CourseTravelCost(minutes = km / CITY_KMH * 60.0, distanceKm = km, estimated = true)
    }
}

/** "얼마나" 답 → 쓸 수 있는 시간(분). 1박2일은 날짜별 코스로 나눌 때 추가한다. */
internal enum class TripLength(val label: String, val minutes: Int) {
    TWO_HOURS("2시간", 120), HALF_DAY("반나절", 240), FULL_DAY("하루", 480)
}

/** "누구랑" 답. 아직 점수에는 반영하지 않는다(3번 점수 산정 예정). */
internal enum class Companion(val label: String) { ALONE("혼자"), TWO("둘이서"), FAMILY("가족"), FRIENDS("친구") }

/** 관광공사 소개 정보 키 → 화면 이름. 원문은 그대로 두고 태그만 걷어 낸다. */
internal val OPERATING_LABELS = linkedMapOf(
    "eventstartdate" to "시작일", "eventenddate" to "종료일", "playtime" to "공연·행사 시간", "eventplace" to "행사 장소",
    "usetime" to "이용 시간", "opentimefood" to "영업 시간", "usetimeculture" to "이용 시간",
    "restdate" to "쉬는 날", "restdatefood" to "쉬는 날", "restdateculture" to "쉬는 날",
    "usefee" to "요금", "usetimefestival" to "요금", "usefeeculture" to "요금",
    "firstmenu" to "대표 메뉴", "treatmenu" to "메뉴",
    "parking" to "주차", "parkingfood" to "주차", "parkingculture" to "주차",
    "infocenter" to "문의", "infocenterfood" to "문의", "infocenterculture" to "문의", "sponsor1tel" to "문의",
)

internal fun String.plainText(): String =
    replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n").replace(Regex("<[^>]+>"), "").trim()

/** "20261115" → "2026년 11월 15일". 형식이 다르면 원문. */
internal fun String.koreanDate(): String =
    if (length == 8 && all(Char::isDigit)) "${substring(0, 4)}년 ${substring(4, 6).toInt()}월 ${substring(6, 8).toInt()}일" else this

/** 주변 장소: 관광지·문화시설·음식점, 직선거리 순. 거리는 화면에 "직선"으로 표시한다. */
internal data class Nearby(val entry: TourismEntry, val km: Double) {
    val kind: String get() = when (entry.contentTypeId) {
        "39" -> "음식점"; "14" -> "문화시설"; "15" -> "행사"; else -> "관광지"
    }
}

internal fun TourismRepository.nearby(point: GeoPoint, radiusKm: Double, limit: Int, excludeId: String? = null): List<Nearby> =
    all.asSequence()
        .filter { it.contentTypeId in setOf("12", "14", "39") && it.contentId != excludeId }
        .map { Nearby(it, Geo.distanceMeters(point, GeoPoint(it.lat, it.lng)) / 1000.0) }
        .filter { it.km <= radiusKm }
        .sortedBy { it.km }
        .take(limit)
        .toList()

internal fun formatKm(km: Double): String = if (km < 1) "${(km * 1000).toInt()}m" else "%.1fkm".format(km)
