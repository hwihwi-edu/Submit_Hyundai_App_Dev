package com.team.travelmap.ui

import com.team.travelmap.core.model.TravelTheme

/**
 * 담당: 1번(화면 연결). 여행 탭 검색창에 타이핑한 문장 → 누구랑·기간·도시·테마.
 * 지금은 단어 규칙(RuleTripQueryParser). 앱 출시 때 AI 해석기로 바꿀 수 있게 인터페이스로 둔다.
 * 못 알아들은 항목은 null 로 두고, 화면은 이전 값을 그대로 쓴다(임의로 채우지 않음).
 */
internal data class TripQuery(
    val raw: String,
    val companion: Companion? = null,
    /** 1 = 당일. 2 이상이면 n일 일정(1박2일 = 2). */
    val days: Int? = null,
    /** 당일 일정의 길이. days 가 2 이상이면 null. */
    val length: TripLength? = null,
    /** 시·도 코드("11" 서울, "30" 대전, "26" 부산). */
    val cityCode: String? = null,
    val theme: TravelTheme? = null,
)

internal fun interface TripQueryParser {
    fun parse(text: String): TripQuery
}

/** 단어 규칙 해석기. TODO(3번·AI): 출시 때 자연어 AI 해석기로 교체(같은 TripQuery 를 돌려주면 화면은 그대로). */
internal object RuleTripQueryParser : TripQueryParser {
    const val MAX_DAYS = 7

    private val cityWords = listOf(
        "11" to listOf("서울", "종로", "강남", "홍대", "마포", "명동", "잠실", "성수", "이태원", "여의도"),
        "30" to listOf("대전", "유성", "대덕", "둔산", "엑스포"),
        "26" to listOf("부산", "해운대", "광안리", "서면", "기장", "남포", "영도", "송정"),
    )
    private val companionWords = listOf(
        Companion.ALONE to listOf("혼자", "나홀로", "혼행", "솔로"),
        Companion.FAMILY to listOf("가족", "부모님", "엄마", "아빠", "아이", "애들", "아들", "딸", "조카"),
        Companion.FRIENDS to listOf("친구들", "친구", "동기", "동료"),
        Companion.TWO to listOf("여자친구", "남자친구", "여친", "남친", "애인", "연인", "와이프", "아내", "남편", "커플", "둘이", "데이트"),
    )
    private val themeWords = listOf(
        TravelTheme.FESTIVAL to listOf("축제", "행사", "공연", "페스티벌"),
        TravelTheme.HISTORY to listOf("역사", "궁", "유적", "박물관", "전통"),
        TravelTheme.FOOD to listOf("맛집", "먹방", "먹거리", "식당", "밥"),
        TravelTheme.CAFE to listOf("카페", "커피", "디저트", "빵"),
        TravelTheme.SEASONAL to listOf("계절", "벚꽃", "단풍", "꽃", "눈꽃"),
        TravelTheme.HEALING to listOf("힐링", "산책", "자연", "숲", "바다", "쉬", "여유"),
    )

    override fun parse(text: String): TripQuery {
        val t = text.replace(" ", "")
        // 둘이 → TWO 지만 "친구 둘이" 처럼 겹치면 앞 목록(가족·친구)이 먼저 이긴다. 여자친구는 TWO 로(친구보다 먼저 검사).
        val companion = if (t.contains("여자친구") || t.contains("남자친구")) Companion.TWO
            else companionWords.firstOrNull { (_, ws) -> ws.any { t.contains(it) } }?.first
        val city = cityWords.firstOrNull { (_, ws) -> ws.any { t.contains(it) } }?.first
        val theme = themeWords.firstOrNull { (_, ws) -> ws.any { t.contains(it) } }?.first

        var days: Int? = null
        var length: TripLength? = null
        Regex("""(\d+)박(\d+)일""").find(t)?.let { m -> days = m.groupValues[2].toInt() }
            ?: Regex("""(\d+)박""").find(t)?.let { m -> days = m.groupValues[1].toInt() + 1 }
            ?: Regex("""(\d+)일""").find(t)?.let { m -> days = m.groupValues[1].toInt() }
        if (days == null) when {
            t.contains("주말") -> days = 2
            t.contains("반나절") -> { days = 1; length = TripLength.HALF_DAY }
            t.contains("당일") || t.contains("하루") -> { days = 1; length = TripLength.FULL_DAY }
            else -> Regex("""(\d+)시간""").find(t)?.let { m ->
                val h = m.groupValues[1].toInt()
                days = 1
                length = when { h <= 2 -> TripLength.TWO_HOURS; h <= 5 -> TripLength.HALF_DAY; else -> TripLength.FULL_DAY }
            }
        }
        days = days?.coerceIn(1, MAX_DAYS)
        if (days == 1 && length == null) length = TripLength.FULL_DAY
        if (days != null && days!! >= 2) length = null
        return TripQuery(text, companion, days, length, city, theme)
    }
}

internal fun TripQuery.durationLabel(): String? = when {
    days == null -> null
    days!! >= 2 -> "${days!! - 1}박 ${days}일"
    else -> length?.label
}
