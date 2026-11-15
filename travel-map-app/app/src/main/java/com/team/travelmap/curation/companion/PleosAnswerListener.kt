package com.team.travelmap.curation.companion

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.ResultListener
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale

/**
 * 담당: 3번. Pleos SpeechToText SDK(기기 안 인식, ON_DEVICE)로 동행 질문의 대답을 듣는다.
 * 2026-11-15 Pleos 가상 기기에서 신청·키 없이 인식 세션이 시작되고 마이크 녹음이 되는 것을 확인했다.
 *
 * 쓰는 법(SDK 3.3.1): 매니페스트 `pleos.car.permission.STT_SERVICE`(normal) + `RECORD_AUDIO`(실행 중 허용),
 * initialize 성공 뒤 addListener → setLocale(Locale.KOREA) → request(). 결과는 onUpdated(문장, 마지막인지).
 * HYBRID·SERVER 모드는 Pleos Playground 앱 등록이 필요해서 쓰지 않는다.
 *
 * 에뮬레이터에서는 Extended controls → Microphone → "Virtual microphone uses host audio input"을 켜야
 * Mac 마이크 소리가 들어간다(끄면 인식은 시작되지만 아무 말도 안 들림).
 */
class PleosAnswerListener(context: Context) : AnswerListener {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private var stt: SpeechToText? = null
    private var ready = false
    private var unavailable = false
    private var startWhenReady = false

    private var current: ((String?) -> Unit)? = null
    private var lastHeard: String? = null
    private var timeout: Runnable? = null
    @Volatile private var maxRms = 0.0
    @Volatile private var minRms = Double.MAX_VALUE

    override fun listen(timeoutMs: Long, onResult: (String?) -> Unit): Boolean {
        cancel()
        if (unavailable || !hasMicPermission()) return false
        val s = stt ?: create() ?: return false
        current = onResult
        lastHeard = null
        Log.i(TAG, "듣기 시작(${timeoutMs}ms, 준비=${ready})")
        timeout = Runnable { Log.i(TAG, "시간 끝"); finish(lastHeard) }.also { main.postDelayed(it, timeoutMs) }
        if (ready) start(s) else startWhenReady = true
        return true
    }

    override fun cancel() {
        current = null
        startWhenReady = false
        timeout?.let(main::removeCallbacks)
        timeout = null
        stt?.let { runCatching { it.stop() } }
    }

    private fun start(s: SpeechToText) {
        runCatching { s.request() }.onFailure { Log.w(TAG, "듣기 시작 실패", it); finish(null) }
    }

    /** 한 번만 돌려준다. 알아들을 수 있는 말이 나오면 문장이 끝나기 전에도 바로 끝낸다. */
    private fun finish(text: String?) {
        val cb = current ?: return
        Log.i(TAG, "들은 말: ${text ?: "(없음)"} · 소리 크기 ${"%.1f".format(minRms)}~${"%.1f".format(maxRms)}")
        maxRms = 0.0; minRms = Double.MAX_VALUE
        current = null
        timeout?.let(main::removeCallbacks)
        timeout = null
        stt?.let { runCatching { it.stop() } }
        cb(text)
    }

    private fun create(): SpeechToText? = runCatching {
        SpeechToText(appContext, Mode.ON_DEVICE).also { s ->
            stt = s
            s.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    main.post {
                        runCatching {
                            s.addListener(listener)
                            s.setLocale(Locale.KOREA)
                        }.onFailure { fail("준비 실패", it); return@post }
                        ready = true
                        if (startWhenReady) { startWhenReady = false; start(s) }
                    }
                }

                override fun onFailure(error: ErrorType) { main.post { fail("초기화 실패: $error", null) } }
            })
        }
    }.getOrElse { fail("만들기 실패(Pleos 음성 서비스 없음?)", it); null }

    private val listener = object : ResultListener {
        override fun onUpdated(text: String, isFinal: Boolean) {
            Log.d(TAG, "onUpdated final=$isFinal: $text")
            main.post {
                if (current == null) return@post
                lastHeard = text
                // 중간 결과는 바뀔 수 있어서(빨래 → 나갈래) 최종 문장에서만 끝낸다.
                if (isFinal) finish(text)
            }
        }
        // 엔진은 "말 끝남"(onEndedRecognition)을 최종 문장(onUpdated final)보다 먼저 보낸다(2026-11-15 가상 기기 확인:
        // 중간 "빨래" → 끝남 → 최종 "나갈래"). 끝남 뒤 잠깐 최종 문장을 기다렸다가, 안 오면 마지막 중간 결과로 끝낸다.
        override fun onEndedRecognition() {
            Log.d(TAG, "onEndedRecognition")
            main.postDelayed({ finish(lastHeard) }, FINAL_WAIT_MS)
        }
        override fun onError(error: ErrorType) {
            Log.w(TAG, "듣는 중 오류: $error")
            main.post { finish(lastHeard) }
        }
        override fun onStartedRecognition() = Unit
        override fun onReady() = Unit
        override fun onUpdatedEpdData(start: Long, end: Long) = Unit
        /** 마이크 소리 크기(진단용). 값이 거의 바뀌지 않으면 마이크 소리가 안 들어오는 것. */
        override fun onUpdatedRms(rms: Double) { if (rms > maxRms) maxRms = rms; if (rms < minRms) minRms = rms }
    }

    private fun hasMicPermission(): Boolean =
        appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun fail(message: String, error: Throwable?) {
        Log.w(TAG, message, error)
        unavailable = true
        ready = false
        val cb = current
        current = null
        timeout?.let(main::removeCallbacks)
        stt?.let { runCatching { it.release() } }
        stt = null
        cb?.invoke(null)
    }

    private companion object {
        const val TAG = "PleosAnswerListener"
        /** 말 끝남 신호 뒤 최종 문장을 기다리는 시간. */
        const val FINAL_WAIT_MS = 1_500L
    }
}
