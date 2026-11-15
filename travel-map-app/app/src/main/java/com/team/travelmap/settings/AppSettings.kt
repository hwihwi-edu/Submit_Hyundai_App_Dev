package com.team.travelmap.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 담당: 1번(지도·화면). 설정 화면의 값 저장소.
 *
 * [팀 공유] 2번·3번은 읽기만 한다: `container.settings.state.value.storyAutoplay` 처럼 쓰거나 state 를 collect.
 * 원칙: 사용자가 불편할 수 있는 자동 동작(기록·알림·재생·표시·질문·효과)은 모두 켜기·끄기를 둔다.
 */
data class Settings(
    // 내 지도
    /** 끄면 도착·주차해도 바로 칸을 채우지 않고 "기록할까요?"를 묻는다. */
    val autoRecordVisit: Boolean = true,
    /** 끄면 기록은 쌓이지만 지도에 다녀온 곳을 칠하지 않는다. */
    val showVisitedOnMap: Boolean = true,
    /** 여러 번 간 곳에 ×2, ×3 표시. */
    val showVisitCount: Boolean = true,
    /** 칸 표시를 따로 고르지 않은 곳의 기본 표시. */
    val defaultMark: MarkStyle = MarkStyle.Pattern,
    // 해설·소리 (3번 연결)
    val voiceGuide: Boolean = true,
    /** 동행 질문("잠깐 들렀다 갈까요?")에 말로 대답하기(마이크 사용, 3번). */
    val voiceAnswer: Boolean = true,
    val storyAutoplay: Boolean = true,
    /** 0.0 ~ 1.0 */
    val guideVolume: Float = 0.6f,
    // 화면
    /** 해설 글 보기(주행 중에는 설정과 관계없이 가린다). */
    val showGuideText: Boolean = true,
    val nearbyCards: Boolean = true,
    val dartIcon: Boolean = true,
    val motionEffects: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.AUTO,
    // 추천 (3번 연결)
    val askStartQuestions: Boolean = true,
    val weatherAware: Boolean = true,
    val hotBadge: Boolean = true,
    // 리뷰·알림 (2번 연결)
    val waypointReview: Boolean = true,
    val resumeAlert: Boolean = true,
    /** 개발자 화면(설정 > 앱 버전 5번 누르기). */
    val devMode: Boolean = false,
)

enum class ThemeMode(val label: String) { AUTO("자동"), DAY("주간"), NIGHT("야간") }

class AppSettings(context: Context) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(read())
    val state: StateFlow<Settings> = _state.asStateFlow()

    fun update(transform: (Settings) -> Settings) {
        val next = transform(_state.value)
        write(next)
        _state.value = next
    }

    private fun read(): Settings {
        val d = Settings()
        return Settings(
            autoRecordVisit = prefs.getBoolean("autoRecordVisit", d.autoRecordVisit),
            showVisitedOnMap = prefs.getBoolean("showVisitedOnMap", d.showVisitedOnMap),
            showVisitCount = prefs.getBoolean("showVisitCount", d.showVisitCount),
            defaultMark = MarkStyle.parse(prefs.getString("defaultMark", null)) ?: d.defaultMark,
            voiceGuide = prefs.getBoolean("voiceGuide", d.voiceGuide),
            voiceAnswer = prefs.getBoolean("voiceAnswer", d.voiceAnswer),
            storyAutoplay = prefs.getBoolean("storyAutoplay", d.storyAutoplay),
            guideVolume = prefs.getFloat("guideVolume", d.guideVolume),
            showGuideText = prefs.getBoolean("showGuideText", d.showGuideText),
            nearbyCards = prefs.getBoolean("nearbyCards", d.nearbyCards),
            dartIcon = prefs.getBoolean("dartIcon", d.dartIcon),
            motionEffects = prefs.getBoolean("motionEffects", d.motionEffects),
            themeMode = runCatching { ThemeMode.valueOf(prefs.getString("themeMode", null)!!) }.getOrDefault(d.themeMode),
            askStartQuestions = prefs.getBoolean("askStartQuestions", d.askStartQuestions),
            weatherAware = prefs.getBoolean("weatherAware", d.weatherAware),
            hotBadge = prefs.getBoolean("hotBadge", d.hotBadge),
            waypointReview = prefs.getBoolean("waypointReview", d.waypointReview),
            resumeAlert = prefs.getBoolean("resumeAlert", d.resumeAlert),
            devMode = prefs.getBoolean("devMode", d.devMode),
        )
    }

    private fun write(s: Settings) {
        prefs.edit()
            .putBoolean("autoRecordVisit", s.autoRecordVisit)
            .putBoolean("showVisitedOnMap", s.showVisitedOnMap)
            .putBoolean("showVisitCount", s.showVisitCount)
            .putString("defaultMark", s.defaultMark.serialize())
            .putBoolean("voiceGuide", s.voiceGuide)
            .putBoolean("voiceAnswer", s.voiceAnswer)
            .putBoolean("storyAutoplay", s.storyAutoplay)
            .putFloat("guideVolume", s.guideVolume)
            .putBoolean("showGuideText", s.showGuideText)
            .putBoolean("nearbyCards", s.nearbyCards)
            .putBoolean("dartIcon", s.dartIcon)
            .putBoolean("motionEffects", s.motionEffects)
            .putString("themeMode", s.themeMode.name)
            .putBoolean("askStartQuestions", s.askStartQuestions)
            .putBoolean("weatherAware", s.weatherAware)
            .putBoolean("hotBadge", s.hotBadge)
            .putBoolean("waypointReview", s.waypointReview)
            .putBoolean("resumeAlert", s.resumeAlert)
            .putBoolean("devMode", s.devMode)
            .apply()
    }
}
