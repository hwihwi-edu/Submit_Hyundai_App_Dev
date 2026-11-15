package com.team.travelmap.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 관광공사 사진 주소를 한 번 받아 앱 캐시에 두고 읽는다(기록 카드의 방식과 같음).
 * 앱에 사진 파일이 없는 자동 분류 장소용. 실패하면 null(화면은 "사진 없음").
 * 공공누리 3유형(변경 금지) 사진도 화면 폭에 맞게 줄여 읽기만 하고 내용은 바꾸지 않는다.
 */
internal fun loadRemotePhoto(context: Context, contentId: String, imageUrl: String, maxWidth: Int = 900): ImageBitmap? {
    if (imageUrl.isBlank()) return null
    val file = File(context.cacheDir, "theme_photos/$contentId.jpg")
    if (!file.exists()) runCatching {
        file.parentFile?.mkdirs()
        val conn = URL(imageUrl.replaceFirst("http://", "https://")).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000; conn.readTimeout = 8000
        conn.inputStream.use { input -> file.outputStream().use { input.copyTo(it) } }
    }.onFailure { file.delete() }
    if (!file.exists()) return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
    }.getOrNull()
}
