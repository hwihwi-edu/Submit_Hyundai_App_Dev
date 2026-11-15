package com.team.travelmap.curation.companion

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import java.util.Locale

/**
 * 담당: 3번. Pleos TextToSpeech SDK(기기 안 합성, ON_DEVICE)로 문장을 읽는다.
 * 2026-11-15 Pleos 가상 기기에서 신청·키 없이 한국어 음성이 나오는 것을 귀로 확인했다.
 *
 * 쓰는 법에서 확인한 점(SDK 3.3.1):
 *  - 매니페스트에 `pleos.car.permission.TTS_SERVICE`(normal) 가 있어야 한다.
 *  - initialize 가 끝난 뒤에 addEventListener 를 불러야 한다(먼저 부르면 SDK 안에서 lateinit 오류).
 *  - 언어는 Locale.KOREA(ko_KR). Locale.KOREAN("ko")은 엔진이 "unsupported locale: ko-"로 거부한다.
 *
 * Pleos 음성 서비스가 없는 기기(일반 AAOS 에뮬레이터 등)에서는 처음 한 번 실패한 뒤 [speak]가 false 를 돌려줘
 * 화면이 글로만 보여 준다. 읽는 동안에는 오디오 포커스(잠시, 다른 소리 줄이기)를 잡는다.
 * 볼륨은 SDK 에 조절 기능이 없어 설정 "해설 볼륨"이 적용되지 않는다(녹음 이야기에만 적용).
 */
class PleosPlaceNarrator(
    context: Context,
    /** 읽기 시작(true)·끝(false). 메인 스레드에서 부른다. 녹음 이야기를 멈췄다 이어 가는 데 쓴다. */
    private val onSpeaking: (Boolean) -> Unit = {},
) : PlaceNarrator {
    private val appContext = context.applicationContext
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
        .build()

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var unavailable = false
    /** 준비가 끝나기 전에 들어온 마지막 문장. 준비되면 바로 읽는다. */
    @Volatile private var pending: String? = null
    @Volatile private var pendingDone: (() -> Unit)? = null
    /** 지금 읽는 문장이 끝나면 부를 것. 다른 문장으로 바뀌거나 멈추면 지운다. */
    @Volatile private var doneCallback: (() -> Unit)? = null
    /** 마지막으로 읽기 시작한 시각. 새 문장이 앞 문장을 끊을 때 앞 문장의 onStop 이 늦게 와도 끝난 것으로 보지 않으려고 쓴다. */
    @Volatile private var lastSayAt = 0L

    override fun speak(text: String, onDone: (() -> Unit)?): Boolean {
        if (unavailable || text.isBlank()) return false
        val t = tts ?: create() ?: return false
        if (!ready) {
            pending = text
            pendingDone = onDone
            return true
        }
        return say(t, text, onDone)
    }

    override fun stop() {
        pending = null
        pendingDone = null
        doneCallback = null
        tts?.let { runCatching { it.stop() } }
        releaseFocus()
    }

    private fun say(t: TextToSpeech, text: String, onDone: (() -> Unit)? = null): Boolean {
        doneCallback = onDone
        lastSayAt = android.os.SystemClock.uptimeMillis()
        audioManager.requestAudioFocus(focusRequest)
        main.post { onSpeaking(true) }
        return runCatching { t.speak(text, true) }
            .onFailure { Log.w(TAG, "읽기 실패", it); releaseFocus() }
            .isSuccess
    }

    private fun create(): TextToSpeech? = runCatching {
        TextToSpeech(appContext, Mode.ON_DEVICE).also { t ->
            tts = t
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    runCatching {
                        t.addEventListener(listener)
                        t.setLocale(Locale.KOREA)
                    }.onFailure { fail("준비 실패", it); return }
                    ready = true
                    pending?.let { text -> val done = pendingDone; pending = null; pendingDone = null; say(t, text, done) }
                }

                override fun onFailure(error: ErrorType) = fail("초기화 실패: $error", null)
            })
        }
    }.getOrElse { fail("만들기 실패(Pleos 음성 서비스 없음?)", it); null }

    private val listener = object : EventListener {
        override fun onStart() = Unit
        override fun onReady() = Unit
        override fun onDone() {
            val cb = doneCallback
            doneCallback = null
            releaseFocus()
            cb?.let { main.post(it) }
        }
        override fun onStop() {
            // 새 문장이 앞 문장을 끊은 경우(fadeout)의 onStop 은 무시한다.
            if (android.os.SystemClock.uptimeMillis() - lastSayAt > REPLACED_STOP_MS) releaseFocus()
        }
        override fun onError(error: ErrorType) {
            Log.w(TAG, "읽는 중 오류: $error")
            doneCallback = null
            releaseFocus()
        }
        override fun onUpdatedRms(rms: Double) = Unit
    }

    private fun fail(message: String, error: Throwable?) {
        Log.w(TAG, message, error)
        unavailable = true
        ready = false
        pending = null
        tts?.let { runCatching { it.release() } }
        tts = null
    }

    private fun releaseFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest)
        main.post { onSpeaking(false) }
    }

    private companion object {
        const val TAG = "PleosPlaceNarrator"
        const val REPLACED_STOP_MS = 700L
    }
}
