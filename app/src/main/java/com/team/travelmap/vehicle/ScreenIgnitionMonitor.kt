package com.team.travelmap.vehicle

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.util.Log
import com.team.travelmap.core.model.Ignition
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 담당: 2번(차량·내비·통합). 차량 화면이 켜지고 꺼지는 것으로 시동 상태를 판단한다.
 *
 * 왜 화면인가: Pleos Vehicle SDK 의 IgnitionState 는 @hide 내부용이고 공개 API 가 없다.
 * 안드로이드 표준 IGNITION_STATE(0x11400409)도 Pleos 에뮬레이터 v2.1.0 의 차량 신호 목록에 없다.
 * 실제 차에서는 시동을 끄면 헤드유닛 화면이 꺼지고, 켜면 다시 켜지므로 이것을 시동으로 본다.
 * 시동을 오래 꺼서 시스템이 완전히 종료되는 경우는 앱이 다시 시작될 때 TripCoordinator 의 복원이 처리한다.
 *
 * Mock·Pleos 모드 모두 이 클래스를 쓴다. 에뮬레이터에서는 전원 키(adb shell input keyevent KEYCODE_POWER)로 시험한다.
 */
class ScreenIgnitionMonitor(context: Context) {
    private val appContext = context.applicationContext
    private val powerManager = appContext.getSystemService(PowerManager::class.java)

    private val _state = MutableStateFlow(if (powerManager?.isInteractive != false) Ignition.ON else Ignition.OFF)
    val state: StateFlow<Ignition> = _state.asStateFlow()

    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_ON -> _state.value = Ignition.ON
                Intent.ACTION_SCREEN_OFF -> _state.value = Ignition.OFF
            }
            Log.d(TAG, "ignition=${_state.value}")
        }
    }

    fun start() {
        if (registered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        // 시스템 방송은 NOT_EXPORTED 로도 받는다.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            appContext.registerReceiver(receiver, filter)
        }
        registered = true
    }

    fun stop() {
        if (!registered) return
        runCatching { appContext.unregisterReceiver(receiver) }
        registered = false
    }

    private companion object { const val TAG = "Ignition" }
}
