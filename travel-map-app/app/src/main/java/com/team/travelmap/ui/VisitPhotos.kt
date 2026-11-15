package com.team.travelmap.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.asImageBitmap
import com.team.travelmap.AppContainer
import com.team.travelmap.visit.VisitEntity

// 담당: 1번(화면). 방문 기록의 "간 장소" 사진: 기록 카드, 기록 상세, 내 지도 사진 마커가 함께 쓴다.

/** 기록 카드 사진: 그림, 잘라도 되는지(공공누리 3유형은 Fit), 출처 문구. */
internal class VisitPhoto(val bitmap: androidx.compose.ui.graphics.ImageBitmap, val crop: Boolean, val source: String)

internal sealed interface PhotoState {
    data object Loading : PhotoState
    data object None : PhotoState
    class Ready(val photo: VisitPhoto) : PhotoState
}

@Composable
internal fun rememberVisitPhoto(c: AppContainer, visit: VisitEntity): PhotoState {
    val context = androidx.compose.ui.platform.LocalContext.current
    val state by androidx.compose.runtime.produceState<PhotoState>(PhotoState.Loading, visit.id, visit.placeName) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { visitedPlacePhoto(c, context, visit) }.getOrNull()?.let { PhotoState.Ready(it) } ?: PhotoState.None
        }
    }
    return state
}

/** 공공누리 유형 → 출처 문구. */
internal fun licenseLabel(license: String): String = when (license) {
    "Type1" -> "한국관광공사 TourAPI · 공공누리 제1유형"
    "Type3" -> "한국관광공사 TourAPI · 공공누리 제3유형(변경 금지)"
    else -> "한국관광공사 TourAPI"
}

/**
 * 실제로 간 장소의 사진. 관광 목록에서 이름(같으면) 또는 좌표(300m 안 가장 가까운 곳)로 장소를 찾고,
 * 앱에 들어 있는 사진이 있으면 그것, 없으면 한국관광공사 사진 주소에서 받아 캐시에 둔다(인터넷 없으면 이름만).
 * 공공누리 3유형(변경 금지) 사진은 자르지 않고(Fit) 보여 준다.
 */
internal fun visitedPlacePhoto(c: AppContainer, context: android.content.Context, visit: VisitEntity): VisitPhoto? {
    val key = "${visit.id}|${visit.placeName}|${visit.photoUri}"
    synchronized(photoCache) { photoCache.get(key) }?.let { return it }
    return loadVisitedPlacePhoto(c, context, visit)?.also { synchronized(photoCache) { photoCache.put(key, it) } }
}

/** 기록 카드·지도 사진 마커·기록 상세가 같은 사진을 다시 읽지 않게 메모리에 잠깐 둔다. */
private val photoCache = android.util.LruCache<String, VisitPhoto>(24)

private fun loadVisitedPlacePhoto(c: AppContainer, context: android.content.Context, visit: VisitEntity): VisitPhoto? {
    val name = visit.placeName ?: return null
    val all = c.tourism.all
    val here = if (visit.placeLat != null && visit.placeLng != null) com.team.travelmap.core.model.GeoPoint(visit.placeLat, visit.placeLng) else null
    fun dist(e: com.team.travelmap.curation.TourismEntry) = here?.let {
        com.team.travelmap.core.geo.Geo.distanceMeters(it, com.team.travelmap.core.model.GeoPoint(e.lat, e.lng))
    } ?: 0.0
    val entry = all.filter { it.name == name }.minByOrNull(::dist)
        ?: here?.let { all.filter { e -> e.imageUrl.isNotBlank() }.minByOrNull(::dist)?.takeIf { dist(it) <= 300 } }
        ?: return null
    // 1) 앱에 들어 있는 사진
    runCatching { c.themeCuration.cards() }.getOrDefault(emptyList())
        .firstOrNull { it.contentId == entry.contentId && it.localImageAsset != null }
        ?.let { card -> loadAsset(context, card.localImageAsset!!)?.let { return VisitPhoto(it, card.imageAllowsCropping, card.imageAttribution.ifBlank { licenseLabel(entry.imageLicense) }) } }
    // 2) 관광공사 사진 주소(받은 적 있으면 캐시)
    if (entry.imageUrl.isBlank()) return null
    val crop = entry.imageLicense != "Type3"
    val file = java.io.File(context.cacheDir, "visit_photos/${entry.contentId}.jpg")
    if (!file.exists()) runCatching {
        file.parentFile?.mkdirs()
        val conn = java.net.URL(entry.imageUrl.replaceFirst("http://", "https://")).openConnection() as java.net.HttpURLConnection
        conn.connectTimeout = 5000; conn.readTimeout = 8000
        conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
    }.onFailure { file.delete() }
    if (!file.exists()) return null
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 700) sample *= 2
    return android.graphics.BitmapFactory.decodeFile(file.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
        ?.asImageBitmap()?.let { VisitPhoto(it, crop, licenseLabel(entry.imageLicense)) }
}
