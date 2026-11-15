package com.team.travelmap.story

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 담당: 3번(콘텐츠·룰렛·오디오). assets 의 음성 파일을 재생한다. 오디오 포커스 정책 준수:
 *  - 포커스를 받은 뒤에만 재생
 *  - 내비 안내로 포커스를 일시 상실하면 음성을 일시정지한다
 *  - 일시적으로 뺏기면 일시정지, 완전히 뺏기면 정지
 *  - 음성 해설(PleosPlaceNarrator)이 읽는 동안은 [holdForSpeech]로 멈췄다가 [releaseAfterSpeech]로 이어 간다.
 *    차량 오디오는 내비 안내 음성과 미디어를 함께 틀어 포커스만으로는 멈추지 않는다(2026-11-15 확인).
 */
class StoryPlayer(context: Context, initialVolume: Float = 1f) {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private var player: MediaPlayer? = null
    private var hasFocus = false
    private var userPaused = false
    private var pausedByFocus = false
    private var pausedForSpeech = false
    private var volume = normalizedStoryVolume(initialVolume)

    private val _nowPlaying = MutableStateFlow<StoryPoint?>(null)
    val nowPlaying: StateFlow<StoryPoint?> = _nowPlaying.asStateFlow()

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            when (change) {
                AudioManager.AUDIOFOCUS_LOSS -> stop()
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    hasFocus = false
                    player?.let { p ->
                        if (p.isPlaying) {
                            p.pause()
                            pausedByFocus = true
                        }
                    }
                }
                AudioManager.AUDIOFOCUS_GAIN -> {
                    hasFocus = true
                    if (pausedByFocus && !userPaused) player?.start()
                    pausedByFocus = false
                }
            }
        }
        .build()

    /** 재생 시작에 성공한 경우에만 true. 실패한 이야기는 엔진이 다음 위치 확인 때 재시도한다. */
    fun play(story: StoryPoint, onFinished: () -> Unit = {}): Boolean {
        stop()
        if (audioManager.requestAudioFocus(focusRequest) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return false
        hasFocus = true
        return runCatching {
            // 준비 도중 실패해도 stop()이 이 인스턴스를 해제할 수 있도록 먼저 보관한다.
            val mediaPlayer = MediaPlayer()
            player = mediaPlayer
            mediaPlayer.apply {
                setAudioAttributes(attributes)
                appContext.assets.openFd(story.audioAsset).use { afd ->
                    setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                }
                setOnCompletionListener { stop(); onFinished() }
                setOnErrorListener { _, what, extra ->
                    Log.w("StoryPlayer", "재생 중 오류: ${story.audioAsset} ($what, $extra)")
                    stop()
                    true
                }
                prepare()
                setVolume(volume, volume)
                start()
            }
            _nowPlaying.value = story
            true
        }.getOrElse {
            Log.w("StoryPlayer", "재생 실패: ${story.audioAsset}", it)
            stop()
            false
        }
    }

    /** 음성 해설이 읽기 시작할 때: 재생 중이면 잠시 멈춘다. */
    fun holdForSpeech() {
        val p = player ?: return
        if (runCatching { p.isPlaying }.getOrDefault(false)) {
            p.pause()
            pausedForSpeech = true
        }
    }

    /** 음성 해설이 끝났을 때: [holdForSpeech]로 멈춘 것만 이어 간다(사용자가 멈춘 것은 그대로). */
    fun releaseAfterSpeech() {
        if (pausedForSpeech && !userPaused && hasFocus) player?.start()
        pausedForSpeech = false
    }

    /** 앱 설정을 읽는 쪽에서 호출한다. 현재 재생과 이후 재생에 같은 값을 적용한다. */
    fun setVolume(value: Float) {
        volume = normalizedStoryVolume(value)
        player?.setVolume(volume, volume)
    }

    fun pauseOrResume() {
        val p = player ?: return
        if (!userPaused) {
            userPaused = true
            if (p.isPlaying) p.pause()
        } else {
            userPaused = false
            if (hasFocus) p.start() else pausedByFocus = true
        }
    }

    fun stop() {
        player?.let { p ->
            runCatching { p.stop() }
            // stop이 실패해도 네이티브 리소스는 별도로 해제한다.
            runCatching { p.release() }
        }
        player = null
        hasFocus = false
        userPaused = false
        pausedByFocus = false
        pausedForSpeech = false
        _nowPlaying.value = null
        audioManager.abandonAudioFocusRequest(focusRequest)
    }
}
