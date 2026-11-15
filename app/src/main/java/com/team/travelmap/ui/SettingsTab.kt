package com.team.travelmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.team.travelmap.AppContainer
import com.team.travelmap.BuildConfig
import com.team.travelmap.settings.Settings
import com.team.travelmap.settings.ThemeMode
import kotlinx.coroutines.launch

/**
 * 담당: 1번(지도·화면). 설정 탭. 불편할 수 있는 자동 동작은 모두 켜기·끄기.
 * ready = false 인 항목은 값만 저장되고, 해당 기능(2번·3번)이 생기면 그 값을 읽는다.
 */
@Composable
internal fun SettingsTab(c: AppContainer) {
    val colors = LocalChaumColors.current
    val s by c.settings.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmDeleteAll by remember { mutableStateOf(false) }
    fun set(t: (Settings) -> Settings) = c.settings.update(t)
    // 개발자 화면: 앱 버전을 5번 누르면 켜진다. 켜진 뒤에는 설정 맨 위에 "개발자 화면" 줄이 생긴다.
    var showDev by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    var versionTaps by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    if (showDev) { DevPanel(c) { showDev = false }; return }

    LazyColumn(
        Modifier.fillMaxSize().background(colors.card, RoundedCornerShape(22.dp)).border(1.dp, colors.outline, RoundedCornerShape(22.dp)).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { Spacer(Modifier.size(14.dp)); Title("편안한 여행을 위해") }
        if (s.devMode) item {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 80.dp)
                    .background(colors.selectedBg, RoundedCornerShape(18.dp))
                    .clickable(role = Role.Button) { showDev = true }
                    .padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Label("개발자 화면", "테스트용 기능 모음(가짜 내비, 주변·테마·코스 확인 등)", Modifier.weight(1f))
                Text("›", style = LocalChaumType.current.panelTitle, color = colors.primary)
            }
        }

        // 확정 시안 06: 묶음마다 연한 초록 카드, 줄 전체를 눌러 켜고 끈다.
        item {
            Group("여행 기록") {
                Toggle("방문 자동 기록", "끄면 도착해도 바로 채우지 않고 기록할지 물어봐요", s.autoRecordVisit) { v -> set { it.copy(autoRecordVisit = v) } }
                Toggle("지도에 방문 표시", "끄면 기록은 남고 지도에만 보이지 않아요", s.showVisitedOnMap) { v -> set { it.copy(showVisitedOnMap = v) } }
                Toggle("재방문 횟수 표시", "칸 이름 옆에 ×2, ×3", s.showVisitCount) { v -> set { it.copy(showVisitCount = v) } }
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Label("기본 표시", "칸마다 따로 고르지 않은 곳에 써요")
                    MarkPicker(current = s.defaultMark, default = s.defaultMark, allowDefault = false) { m ->
                        m?.let { mark -> set { it.copy(defaultMark = mark) } }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Label("방문 기록 전체 지우기", "모든 기록과 칸 표시를 지워요", Modifier.weight(1f))
                    SecondaryButton("지우기", height = 64.dp) { confirmDeleteAll = true }
                }
            }
        }
        item {
            Group("주행과 안내") {
                Toggle("음성 이야기 자동 재생", "이야기 장소에 가까워지면 저절로 재생", s.storyAutoplay) { v -> set { it.copy(storyAutoplay = v) } }
                Toggle("음성 해설", "지나는 곳·곧 도착하는 곳을 음성으로 알려 줘요", s.voiceGuide) { v -> set { it.copy(voiceGuide = v) } }
                Toggle("말로 대답하기", "\"들렀다 갈까요?\"에 '응'·'괜찮아'·'이야기'로 대답해요(마이크 사용)", s.voiceAnswer) { v -> set { it.copy(voiceAnswer = v) } }
                Toggle("주변 장소 추천", "주행 중 지나는 곳 3km 안 관광지·음식점을 보여줘요", s.nearbyCards) { v -> set { it.copy(nearbyCards = v) } }
                Toggle("여행 재개 알림", "경유지에 세운 뒤 시동을 다시 켜면 \"여행을 계속할까요?\"", s.resumeAlert) { v -> set { it.copy(resumeAlert = v) } }
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                    Label("해설 볼륨", "${(s.guideVolume * 100).toInt()}%")
                    Slider(
                        value = s.guideVolume, onValueChange = { v -> set { it.copy(guideVolume = v) } },
                        colors = SliderDefaults.colors(thumbColor = colors.primary, activeTrackColor = colors.primary),
                    )
                }
            }
        }
        item {
            Group("취향과 편의") {
                Toggle("경유지 리뷰 요청", "끄면 리뷰를 묻지 않아요", s.waypointReview) { v -> set { it.copy(waypointReview = v) } }
                Toggle("시작 질문", "끄면 질문 없이 기본값으로 바로 추천", s.askStartQuestions, ready = false) { v -> set { it.copy(askStartQuestions = v) } }
                Toggle("날씨 반영", "비·눈 예보가 있으면 실내 공간이 확인된 코스를 우선해요", s.weatherAware) { v -> set { it.copy(weatherAware = v) } }
                Toggle("\"핫\" 표시", "다시 오고 싶다는 응답이 많은 곳에 표시", s.hotBadge) { v -> set { it.copy(hotBadge = v) } }
                Toggle("랜덤 다트 아이콘", "누르면 바로 여행지를 골라줘요", s.dartIcon, ready = false) { v -> set { it.copy(dartIcon = v) } }
            }
        }
        item {
            Group("화면") {
                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Label("주간·야간", "자동이면 차량 설정을 따라요")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ThemeMode.entries.forEach { m ->
                            ChipTab(m.label, s.themeMode == m, Modifier.width(146.dp).heightIn(min = 64.dp)) { set { it.copy(themeMode = m) } }
                        }
                    }
                }
                Toggle("화면 움직임 줄이기", "칸이 채워질 때 번지는 효과·반짝임을 끕니다", !s.motionEffects, ready = false) { v -> set { it.copy(motionEffects = !v) } }
                Toggle("해설 글 보기", "주행 중에는 설정과 관계없이 가려요", s.showGuideText, ready = false) { v -> set { it.copy(showGuideText = v) } }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("원하는 기능만 켜고 떠나세요", style = LocalChaumType.current.body, color = colors.textSub, modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp))
                Text(
                    "앱 버전 ${BuildConfig.VERSION_NAME}" + when {
                        s.devMode -> "  · 개발자 모드"
                        versionTaps in 2..4 -> "  (${5 - versionTaps}번 더 누르면 개발자 모드)"
                        else -> ""
                    },
                    style = LocalChaumType.current.body, color = colors.textSub,
                    modifier = Modifier.heightIn(min = 56.dp).clickable(role = Role.Button) {
                        if (!s.devMode && ++versionTaps >= 5) { set { it.copy(devMode = true) }; versionTaps = 0 }
                    }.padding(vertical = 12.dp),
                )
                Body("지도 경계: 통계청 SGIS 행정구역 경계(공공누리 제1유형), vuski/admdongkor 가공(CC BY 4.0)")
                Body("도시 경계: 통계청 SGIS 2025년 2분기")
                Body("장소·사진·오디오 이야기: 한국관광공사 TourAPI(공공누리), 오디")
                Body("글꼴: 42dot Asta Sans (SIL OFL 1.1)")
            }
        }
    }

    if (confirmDeleteAll) {
        ConfirmDialog(
            title = "방문 기록을 모두 지울까요?",
            message = "모든 방문 기록과 칸 표시가 지워지고 되돌릴 수 없어요.",
            confirm = "모두 지우기",
            onConfirm = { scope.launch { c.visits.deleteAll() } },
            onDismiss = { confirmDeleteAll = false },
        )
    }
}

/** 설정 묶음: 연한 초록 둥근 카드 + 굵은 제목. */
@Composable
private fun Group(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val colors = LocalChaumColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .background(colors.surface2, RoundedCornerShape(20.dp))
            .border(1.dp, colors.outline, RoundedCornerShape(20.dp))
            .padding(horizontal = 24.dp, vertical = 12.dp),
    ) {
        Text(title, style = LocalChaumType.current.placeName, color = colors.text, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
        content()
    }
}

@Composable
private fun Label(title: String, sub: String, modifier: Modifier = Modifier) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    Column(modifier) {
        Text(title, style = type.placeName, color = colors.text)
        Text(sub, style = type.placeAddress, color = colors.textSub)
    }
}

/** 한 줄 전체를 눌러도 켜지고 꺼진다(차량 화면에서 작은 스위치만 누르기 어렵다). */
@Composable
private fun Toggle(title: String, sub: String, checked: Boolean, ready: Boolean = true, onChange: (Boolean) -> Unit) {
    val colors = LocalChaumColors.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 88.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(title, if (ready) sub else "$sub · 준비 중", Modifier.weight(1f))
        MasilSwitch(checked)
    }
}
