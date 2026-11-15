package com.team.travelmap.core.model

/**
 * [공통 약속] 프로토타입 시범 지역.
 * 지도는 전국을 그대로 보여 주되, 여기 포함된 시·도의 시·군·구만 활성화(흰색)되고
 * 나머지는 회색 비활성으로 표시·선택 불가·룰렛 후보 제외된다.
 *
 * 서울특별시(11) 25개 구 + 부산광역시(26) 16개 구·군 + 대전광역시(30) 5개 구 = 46칸
 * 전국으로 넓힐 때는 ACTIVE_SIDO 만 바꾸면 된다.
 */
object PilotScope {
    val ACTIVE_SIDO: Set<String> = setOf("11", "26", "30")

    fun isActive(id: RegionId): Boolean = id.sidoCode in ACTIVE_SIDO
}
