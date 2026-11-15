package com.team.travelmap.story

import android.content.Context
import com.team.travelmap.core.model.GeoPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 담당: 3번(콘텐츠·룰렛·오디오). assets/stories.json 에서 스토리 포인트를 읽는다.
 * [{"id":"gongsanseong","title":"백제 웅진 도읍기 이야기","lat":36.4646,"lng":127.1258,
 *   "audio":"stories/gongsanseong.mp3","radiusM":2000}]
 */
class StoryRepository(private val context: Context) {
    @Serializable
    private data class Dto(
        val id: String,
        val title: String,
        val lat: Double,
        val lng: Double,
        val audio: String,
        val radiusM: Double = 2_000.0,
    )

    private val json = Json { ignoreUnknownKeys = true }

    val all: List<StoryPoint> by lazy {
        val text = context.assets.open("stories.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
        json.decodeFromString<List<Dto>>(text).map {
            StoryPoint(it.id, it.title, GeoPoint(it.lat, it.lng), it.audio, it.radiusM)
        }
    }
}
