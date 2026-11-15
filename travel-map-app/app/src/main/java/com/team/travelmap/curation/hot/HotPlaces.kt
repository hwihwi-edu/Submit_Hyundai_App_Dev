package com.team.travelmap.curation.hot

import com.team.travelmap.core.geo.Geo
import com.team.travelmap.core.model.GeoPoint

/**
 * 담당: 3번. "핫" 표시(README 6절: 재방문 의사가 높은 곳에 "핫"). 순수 로직.
 *
 * 리뷰는 1번 DB(visit/Review.kt)의 재방문 의사(wouldRevisit)·주차·웨이팅 응답을 쓴다(별점은 #34 에서 뺌).
 * 실제 사용자가 아직 없으므로 시연용 "예시 응답"(assets/example_reviews.json, 문장 없이 응답 수만)을 더한다.
 * 예시가 섞인 장소는 [HotStat.includesSample] 로 화면에 "예시 포함"을 표시한다.
 *
 * 기준(2026-11-15 사용자 위임, 3번 결정): 재방문 의사 응답 [MIN_ANSWERS]개 이상, "다시 오고 싶다" 비율 [MIN_REVISIT_RATE] 이상.
 */
object HotPlaces {
    const val MIN_ANSWERS = 3
    const val MIN_REVISIT_RATE = 0.7
    /** 리뷰 좌표와 관광 장소를 같은 곳으로 볼 거리. 이름이 같으면 [NAME_MATCH_RADIUS_M], 이름 없이 좌표만이면 [COORD_MATCH_RADIUS_M]. */
    const val NAME_MATCH_RADIUS_M = 300.0
    const val COORD_MATCH_RADIUS_M = 60.0

    /** 장소 하나. 관광 목록(TourismEntry)에서 만든다. */
    data class PlaceRef(val id: String, val name: String, val point: GeoPoint)

    /** 리뷰 하나의 응답(실제 리뷰 또는 예시). [placeId] 를 알면(예시 데이터) 좌표 대신 그 id 로 묶는다. */
    data class Answer(
        val placeName: String,
        val point: GeoPoint,
        val wouldRevisit: Boolean?,
        val parking: String? = null,
        val waiting: String? = null,
        val sample: Boolean = false,
        val placeId: String? = null,
    )

    data class HotStat(
        val answers: Int,
        val revisitYes: Int,
        val includesSample: Boolean,
        /** 주차가 "편했어요"(EASY) 비율. 응답이 없으면 null. */
        val parkingEasyRate: Double? = null,
        /** 웨이팅이 "오래 기다렸어요"(LONG) 비율. 응답이 없으면 null. */
        val waitingLongRate: Double? = null,
    ) {
        val revisitRate: Double get() = if (answers == 0) 0.0 else revisitYes.toDouble() / answers
        val isHot: Boolean get() = answers >= MIN_ANSWERS && revisitRate >= MIN_REVISIT_RATE

        /** 설명 한 줄. 예시가 섞이면 사람 수 대신 "응답 N건"으로 쓰고 "예시 포함"을 붙인다(실제 이용자 수로 오해하지 않게). */
        val label: String get() = buildString {
            append("또 오고 싶다 ${(revisitRate * 100).toInt()}%")
            append(if (includesSample) " · 응답 ${answers}건 · 예시 포함" else " · ${answers}명")
        }
    }

    /** 응답을 장소별로 묶어 통계를 낸다. 장소를 못 찾은 응답은 버린다(다른 도시·지운 장소 등). */
    fun aggregate(places: List<PlaceRef>, answers: List<Answer>): Map<String, HotStat> {
        val byId = places.associateBy { it.id }
        val grouped = answers.mapNotNull { a ->
            val id = a.placeId?.takeIf { it in byId } ?: match(places, a)?.id
            id?.let { it to a }
        }.groupBy({ it.first }, { it.second })
        return grouped.mapValues { (_, list) ->
            val revisit = list.mapNotNull { it.wouldRevisit }
            val parking = list.mapNotNull { it.parking }
            val waiting = list.mapNotNull { it.waiting }
            HotStat(
                answers = revisit.size,
                revisitYes = revisit.count { it },
                includesSample = list.any { it.sample },
                parkingEasyRate = parking.takeIf { it.isNotEmpty() }?.let { p -> p.count { it == "EASY" }.toDouble() / p.size },
                waitingLongRate = waiting.takeIf { it.isNotEmpty() }?.let { w -> w.count { it == "LONG" }.toDouble() / w.size },
            )
        }
    }

    /** 이름이 같고 300m 안이면 그 장소, 아니면 60m 안 가장 가까운 장소. */
    fun match(places: List<PlaceRef>, a: Answer): PlaceRef? {
        val sameName = places.filter { it.name == a.placeName }
            .minByOrNull { Geo.distanceMeters(it.point, a.point) }
            ?.takeIf { Geo.distanceMeters(it.point, a.point) <= NAME_MATCH_RADIUS_M }
        return sameName ?: places.minByOrNull { Geo.distanceMeters(it.point, a.point) }
            ?.takeIf { Geo.distanceMeters(it.point, a.point) <= COORD_MATCH_RADIUS_M }
    }
}
