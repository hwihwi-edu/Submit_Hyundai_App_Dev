package com.team.travelmap.curation.hot

import android.content.Context
import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.curation.TourismRepository
import com.team.travelmap.visit.ReviewEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 담당: 3번. 실제 리뷰(1번 ReviewRepository) + 시연용 예시 응답(assets/example_reviews.json)으로
 * 장소별 "핫" 통계를 낸다. 화면은 [stats] 를 구독해 contentId 로 찾는다.
 */
class HotRepository(
    context: Context,
    scope: CoroutineScope,
    private val tourism: TourismRepository,
    reviews: Flow<List<ReviewEntity>>,
) {
    @Serializable
    private data class SampleFile(val note: String = "", val items: List<SampleItem> = emptyList())

    @Serializable
    private data class SampleItem(
        val contentId: String, val placeName: String, val lat: Double, val lng: Double,
        val revisitYes: Int, val revisitNo: Int,
        val parking: Map<String, Int> = emptyMap(), val waiting: Map<String, Int> = emptyMap(),
    )

    private val appContext = context.applicationContext

    /** 예시 응답을 한 명씩 풀어 둔다(주차·웨이팅은 앞사람부터 차례로 배정). */
    private val samples: List<HotPlaces.Answer> by lazy {
        runCatching {
            val text = appContext.assets.open("example_reviews.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            Json { ignoreUnknownKeys = true }.decodeFromString<SampleFile>(text).items.flatMap { it.expand() }
        }.getOrDefault(emptyList())
    }

    private val places: List<HotPlaces.PlaceRef> by lazy {
        tourism.all.map { HotPlaces.PlaceRef(it.contentId, it.name, GeoPoint(it.lat, it.lng)) }
    }

    /** contentId → 통계. 리뷰가 바뀔 때마다 다시 계산한다. */
    val stats: StateFlow<Map<String, HotPlaces.HotStat>> = reviews
        // 보조 기능이 앱을 죽이지 않게: 관광 목록을 못 읽는 등 실패하면 배지 없이 간다.
        .map { list -> runCatching { HotPlaces.aggregate(places, samples + list.map { it.toAnswer() }) }.getOrDefault(emptyMap()) }
        .catch { emit(emptyMap()) }
        .flowOn(Dispatchers.Default)
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    private fun ReviewEntity.toAnswer() = HotPlaces.Answer(
        placeName = placeName, point = GeoPoint(latitude, longitude),
        wouldRevisit = wouldRevisit, parking = parking, waiting = waiting,
    )

    private fun SampleItem.expand(): List<HotPlaces.Answer> {
        val total = revisitYes + revisitNo
        val parkingList = parking.flatMap { (k, n) -> List(n) { k } }
        val waitingList = waiting.flatMap { (k, n) -> List(n) { k } }
        return (0 until total).map { i ->
            HotPlaces.Answer(
                placeName = placeName, point = GeoPoint(lat, lng), wouldRevisit = i < revisitYes,
                parking = parkingList.getOrNull(i), waiting = waitingList.getOrNull(i),
                sample = true, placeId = contentId,
            )
        }
    }
}
