package com.team.travelmap

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.team.travelmap.ui.AppScreen
import com.team.travelmap.ui.TravelMapTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = (application as TravelMapApp).container
        // 서류·시연 캡처용: adb shell am start -n com.team.travelmap/.MainActivity --ez hide_debug true
        val hideDebug = intent.getBooleanExtra(EXTRA_HIDE_DEBUG, false)
        // 3번 말로 대답하기: 앱을 열 때(정차 중) 한 번 마이크 권한을 묻는다. 거절하면 버튼으로만 대답한다.
        if (container.settings.state.value.voiceAnswer &&
            checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
        setContent {
            val settings by container.settings.state.collectAsStateWithLifecycle()
            TravelMapTheme(settings.themeMode) { AppScreen(container, showDebugPanel = !hideDebug) }
        }
    }

    companion object {
        /** true 면 Mock 디버그 패널을 숨긴다(캡처용). Mock 동작 자체는 그대로다. */
        const val EXTRA_HIDE_DEBUG = "hide_debug"
        private const val REQ_MIC = 31
    }
}
