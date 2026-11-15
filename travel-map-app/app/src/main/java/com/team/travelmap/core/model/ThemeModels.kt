package com.team.travelmap.core.model

/** 담당: 3번 생성, 1번 화면 표시. 이름 대신 enum 값으로 테마를 주고받는다. */
enum class TravelTheme(val displayName: String) {
    FESTIVAL("축제·행사"), HISTORY("역사"), FOOD("맛집"),
    CAFE("카페투어"), SEASONAL("계절"), HEALING("힐링")
}

enum class TravelSeason { SPRING, SUMMER, AUTUMN, WINTER }

/** 미확인 장소를 비 오는 날 실내 장소로 단정하지 않는다. */
enum class WeatherExposure { INDOOR, OUTDOOR, MIXED, UNKNOWN }

/** 사진은 URL 확보 상태다. 개별 이용조건 확인·파일 저장은 별도로 필요하다. */
data class ThemePlaceCard(
    val contentId: String,
    val place: Place,
    val themes: Set<TravelTheme>,
    val imageUrl: String,
    val thumbnailUrl: String,
    val imageLicense: String,
    val introduction: String,
    val operatingInfo: Map<String, String>,
    val themeReasons: Map<TravelTheme, String>,
    val preferredSeasons: Set<TravelSeason> = emptySet(),
    val weatherExposure: WeatherExposure = WeatherExposure.UNKNOWN,
    val source: String,
    val collectedAt: String,
    val detailCollectedAt: String,
    val eventStartDate: String? = null,
    val eventEndDate: String? = null,
    val localImageAsset: String? = null,
    val imageAttribution: String = "",
    /** Type3 사진은 원본을 유지한다. 화면은 ContentScale.Fit 등 잘리지 않는 표시를 사용한다. */
    val imageAllowsCropping: Boolean = false,
    /** 원문에 존재하는 실내외 분류 근거. UNKNOWN을 실내로 간주하지 않는다. */
    val weatherEvidence: String = "",
)
