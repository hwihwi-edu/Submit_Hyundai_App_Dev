package com.team.travelmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.team.travelmap.AppContainer
import com.team.travelmap.core.model.Gear
import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.core.model.RegionId
import com.team.travelmap.core.model.TravelTheme
import com.team.travelmap.settings.MarkStyle
import com.team.travelmap.settings.Settings
import com.team.travelmap.story.StoryRepository
import com.team.travelmap.vehicle.MockVehicleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 개발자 화면에서만 켜는 화면 흉내(저장하지 않음, 앱을 다시 켜면 꺼짐). */
internal object DevFlags {
    /** 실제 차가 서 있어도 주행 제한 화면으로 보여 준다(잠긴 탭·큰 버튼 확인용). */
    val fakeDriving = MutableStateFlow(false)
    /** 노트북에서 보낸 글자(tools/dev_text.sh). 여행 탭 검색창·검색어 해석 칸이 받아 쓴다. 쓴 뒤 null 로 비운다. */
    val externalText = MutableStateFlow<String?>(null)
    /** 마지막으로 받은 글자(개발자 화면 표시용). */
    val lastExternalText = MutableStateFlow<String?>(null)
    const val ACTION_TEXT = "com.team.travelmap.DEV_TEXT"
}

/**
 * 개발자 모드일 때만 노트북에서 글자를 받는다(에뮬레이터 키보드로 한글 치기 번거로워서).
 * 노트북: tools/dev_text.sh "여자친구랑 1박2일 부산"  → adb 방송 → 여행 탭 검색창에 넣고 바로 추천.
 */
@Composable
internal fun DevTextReceiver(enabled: Boolean) {
    val context = LocalContext.current
    androidx.compose.runtime.DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: android.content.Intent?) {
                val text = intent?.getStringExtra("text")?.takeIf { it.isNotBlank() } ?: return
                DevFlags.lastExternalText.value = text
                DevFlags.externalText.value = text
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context, receiver, android.content.IntentFilter(DevFlags.ACTION_TEXT), androidx.core.content.ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
}

private val TEST_POINTS = listOf(
    "서울시청" to GeoPoint(37.5663, 126.9779),
    "대전역" to GeoPoint(36.3324, 127.4345),
    "해운대해수욕장" to GeoPoint(35.1587, 129.1604),
)
private val CITY_NAMES = listOf("11" to "서울", "30" to "대전", "26" to "부산")

/**
 * 담당: 1번. 개발자 화면(설정 > 앱 버전 5번). 팀이 시연 전에 기능을 하나씩 확인하는 곳.
 * 앱 상태 / 가짜 내비·차량 / 주행 중 화면 / 주변 추천 / 테마·코스 / 검색어 해석 / 이야기 재생 / 방문 기록 / 2번 신호 / 초기화.
 */
@Composable
internal fun DevPanel(c: AppContainer, onClose: () -> Unit) {
    val colors = LocalChaumColors.current
    LazyColumn(
        Modifier.fillMaxSize().background(colors.card, RoundedCornerShape(22.dp)).border(1.dp, colors.outline, RoundedCornerShape(22.dp)).padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Title("개발자 화면")
                Spacer(Modifier.weight(1f))
                SecondaryButton("설정으로", height = 64.dp, onClick = onClose)
            }
            Body("시연 전에 기능을 하나씩 확인하는 곳이에요. 일반 사용자에게는 보이지 않아요.")
        }
        item { AppStatus(c) }
        item { FakeCar(c) }
        item { FakeDriving() }
        item { NearbyCheck(c) }
        item { ThemeCheck(c) }
        item { QueryCheck() }
        item { StoryCheck(c) }
        item { VisitCheck(c) }
        item { SignalCheck(c) }
        item { ResetCheck(c, onClose) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val colors = LocalChaumColors.current
    Column(
        Modifier.fillMaxWidth().background(colors.surface2, RoundedCornerShape(20.dp)).border(1.dp, colors.outline, RoundedCornerShape(20.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(title, style = LocalChaumType.current.placeName, color = colors.text, fontWeight = FontWeight.Bold)
        content()
    }
}

@Composable
private fun Line(text: String) = Text(text, style = LocalChaumType.current.placeAddress, color = LocalChaumColors.current.text)

@Composable
private fun DevToggle(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 72.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = LocalChaumType.current.body, color = LocalChaumColors.current.text, modifier = Modifier.weight(1f))
        MasilSwitch(checked)
    }
}

/** 1. 앱 상태: 지금 무엇으로 동작하는지와 데이터 개수. */
@Composable
private fun AppStatus(c: AppContainer) {
    val context = LocalContext.current
    val gear by c.vehicle.gear.collectAsStateWithLifecycle()
    val restricted by c.restrictions.restricted.collectAsStateWithLifecycle()
    val here by c.navi.currentLocation.collectAsStateWithLifecycle()
    val trip by c.trip.state.collectAsStateWithLifecycle()
    val visits by remember { c.visits.visits }.collectAsStateWithLifecycle(emptyList())
    val counts by produceState<List<Int>?>(null) {
        value = withContext(Dispatchers.IO) {
            runCatching { listOf(c.tourism.all.size, c.themeCuration.cards().size, StoryRepository(context).all.size) }.getOrNull()
        }
    }
    Section("1. 앱 상태") {
        Line("차량·내비: ${if (c.usePleos) "Pleos 실제" else "가짜(Mock)"}${if (c.devForcedMock) " · 개발자 화면에서 켬" else ""}")
        Line("기어 ${gear.name} · 주행 제한(차량) ${if (restricted) "켜짐" else "꺼짐"}")
        Line("현재 위치 ${here?.let { "%.4f, %.4f".format(it.latitude, it.longitude) } ?: "아직 없음"}")
        Line("여행 상태 ${trip::class.simpleName}")
        Line("방문 기록 ${visits.size}건")
        counts?.let { (tour, theme, story) -> Line("관광 장소 ${tour}곳 · 테마 카드 ${theme}곳(오늘 날짜 기준) · 이야기 ${story}편") }
    }
}

/** 2. 가짜 내비·차량: 다음 실행부터 Mock. Mock 일 때는 기어·배터리 조작. */
@Composable
private fun FakeCar(c: AppContainer) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE) }
    var force by remember { mutableStateOf(prefs.getBoolean("devForceMock", false)) }
    Section("2. 가짜 내비·차량") {
        DevToggle("다음 실행부터 가짜 내비·차량 쓰기", force) { v -> force = v; prefs.edit().putBoolean("devForceMock", v).apply() }
        Line("앱을 완전히 닫고 다시 열면 적용돼요. 가짜 내비는 출발하면 직선으로 60초 만에 도착해요.")
        val mock = c.vehicle as? MockVehicleRepository
        if (mock != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("기어 P", Modifier.weight(1f), height = 64.dp) { mock.setGear(Gear.PARK) }
                SecondaryButton("기어 D", Modifier.weight(1f), height = 64.dp) { mock.setGear(Gear.DRIVE) }
                SecondaryButton("배터리 20%", Modifier.weight(1f), height = 64.dp) { mock.setEnergy(20f, 90.0) }
                SecondaryButton("배터리 100%", Modifier.weight(1f), height = 64.dp) { mock.setEnergy(100f, 450.0) }
            }
        } else {
            Line("지금은 Pleos 실제 차량이라 기어는 Virtual Console(127.0.0.1:8088)에서 바꿔요.")
        }
    }
}

/** 3. 주행 중 화면 미리보기. */
@Composable
private fun FakeDriving() {
    val fake by DevFlags.fakeDriving.collectAsStateWithLifecycle()
    Section("3. 주행 중 화면 미리보기") {
        DevToggle("주행 중처럼 보이기(탭 잠금·사진 숨김 확인)", fake) { DevFlags.fakeDriving.value = it }
        Line("켜면 여행·테마 탭만 열려요. 메뉴 아래 \"끄기\" 줄을 누르면 돌아와요.")
        if (fake) SecondaryButton("지금 끄기", Modifier.fillMaxWidth(), height = 64.dp) { DevFlags.fakeDriving.value = false }
    }
}

/** 4. 주변 추천(주행 중 카드·상세 화면과 같은 계산). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NearbyCheck(c: AppContainer) {
    val here by c.navi.currentLocation.collectAsStateWithLifecycle()
    var pointIndex by remember { mutableIntStateOf(1) }
    var radius by remember { mutableDoubleStateOf(3.0) }
    val points = TEST_POINTS + listOfNotNull(here?.let { "현재 위치" to it })
    val point = points[pointIndex.coerceAtMost(points.lastIndex)]
    val list by produceState<List<Nearby>?>(null, point, radius) {
        value = null
        value = withContext(Dispatchers.Default) { c.tourism.nearby(point.second, radius, 8) }
    }
    Section("4. 주변 추천 확인") {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            points.forEachIndexed { i, (name, _) -> ChipTab(name, i == pointIndex, Modifier.width(170.dp).height(56.dp)) { pointIndex = i } }
            listOf(1.0, 3.0, 5.0).forEach { r -> ChipTab("${r.toInt()}km", r == radius, Modifier.width(90.dp).height(56.dp)) { radius = r } }
        }
        when {
            list == null -> Line("찾는 중…")
            list!!.isEmpty() -> Line("${point.first} ${radius.toInt()}km 안에 장소가 없어요.")
            else -> list!!.forEach { n -> Line("${n.entry.name}  ${n.kind}  직선 ${formatKm(n.km)}") }
        }
    }
}

/** 5. 테마 추천: 도시 × 테마 장소 수, 누르면 이름 목록. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ThemeCheck(c: AppContainer) {
    var pick by remember { mutableStateOf<Pair<String, TravelTheme>?>(null) }
    val cards by produceState<List<com.team.travelmap.core.model.ThemePlaceCard>?>(null) {
        value = withContext(Dispatchers.IO) { runCatching { c.themeCuration.cards() }.getOrDefault(emptyList()) }
    }
    Section("5. 테마 추천 확인") {
        val all = cards ?: run { Line("불러오는 중…"); return@Section }
        CITY_NAMES.forEach { (code, name) ->
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(name, style = LocalChaumType.current.body, color = LocalChaumColors.current.text, modifier = Modifier.width(70.dp).padding(top = 14.dp))
                TravelTheme.entries.forEach { t ->
                    val n = all.count { it.place.regionId?.sidoCode == code && t in it.themes }
                    ChipTab("${t.displayName} $n", pick == code to t, Modifier.width(150.dp).height(56.dp)) { pick = code to t }
                }
            }
        }
        pick?.let { (code, t) ->
            val names = all.filter { it.place.regionId?.sidoCode == code && t in it.themes }.map { it.place.name }
            Line(if (names.isEmpty()) "장소 없음" else names.joinToString(", "))
        }
    }
}

/** 6. 검색어 해석(여행 탭 검색창과 같은 해석기). */
@Composable
private fun QueryCheck() {
    var text by remember { mutableStateOf("여자친구랑 1박2일 부산 맛집") }
    val last by DevFlags.lastExternalText.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(last) { last?.let { text = it } }
    val q = RuleTripQueryParser.parse(text)
    Section("6. 검색어 해석 확인") {
        OutlinedTextField(text, { text = it }, singleLine = true, textStyle = LocalChaumType.current.body, modifier = Modifier.fillMaxWidth())
        Line("누구랑 ${q.companion?.label ?: "못 알아들음"} · 기간 ${q.durationLabel() ?: "못 알아들음"} · 도시 ${CITY_NAMES.firstOrNull { it.first == q.cityCode }?.second ?: "못 알아들음"} · 테마 ${q.theme?.displayName ?: "없음"}")
        Line("지금은 단어 규칙이에요. 출시 때 AI 해석기로 바꾸면 같은 칸이 채워져요.")
        Line("노트북에서 보내기: tools/dev_text.sh \"여자친구랑 1박2일 부산\"  (여행 탭 검색창에도 들어가요)")
    }
}

/** 7. 이야기 재생(오디오 포커스·덕킹 확인). */
@Composable
private fun StoryCheck(c: AppContainer) {
    val context = LocalContext.current
    val playing by c.storyPlayer.nowPlaying.collectAsStateWithLifecycle()
    val stories by produceState(emptyList<com.team.travelmap.story.StoryPoint>()) {
        value = withContext(Dispatchers.IO) { runCatching { StoryRepository(context).all }.getOrDefault(emptyList()) }
    }
    Section("7. 이야기 재생 확인") {
        stories.forEach { st ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(st.title.replace(Regex("""^\[[^\]]*\]\s*"""), ""), style = LocalChaumType.current.placeAddress, color = LocalChaumColors.current.text, modifier = Modifier.weight(1f))
                if (playing?.id == st.id) SecondaryButton("■ 멈춤", height = 56.dp) { c.storyPlayer.stop() }
                else SecondaryButton("▶ 듣기", height = 56.dp) { c.storyPlayer.play(st) }
            }
        }
    }
    VoiceAnswerCheck(c)
}

/** 7-1. 말로 대답 확인(3번): 동행 질문을 읽고 8초 동안 들은 말과 알아들은 결과를 보여 준다. */
@Composable
private fun VoiceAnswerCheck(c: AppContainer) {
    var status by remember { mutableStateOf("") }
    var heard by remember { mutableStateOf<String?>(null) }
    Section("7-1. 말로 대답 확인") {
        Line("질문을 읽은 뒤 8초 동안 들어요. \"들를래\"·\"괜찮아\"·\"이야기\" 중 하나로 대답해 보세요(\"응\" 한 글자는 잘 못 알아들어요).")
        Line("에뮬레이터는 Extended controls → Microphone → \"Virtual microphone uses host audio input\"을 켜야 Mac 마이크가 들어가요.")
        SecondaryButton("묻고 듣기", height = 64.dp) {
            heard = null
            val listen = {
                status = "듣는 중…"
                val ok = c.answers.listen(com.team.travelmap.curation.companion.RouteCompanionController.LISTEN_MS) { text ->
                    heard = text ?: "(못 들음)"
                    status = "결과: " + when (com.team.travelmap.curation.companion.VoiceAnswerParser.parse(text)) {
                        com.team.travelmap.curation.companion.VoiceAnswer.YES -> "잠깐 들르기"
                        com.team.travelmap.curation.companion.VoiceAnswer.NO -> "닫기"
                        com.team.travelmap.curation.companion.VoiceAnswer.STORY -> "이야기 읽기"
                        null -> "알아듣지 못함(카드는 그대로)"
                    }
                }
                if (!ok) status = "들을 수 없어요(마이크 권한·Pleos 음성 서비스 확인)"
            }
            status = "묻는 중…"
            if (!c.narrator.speak("바로 앞에 경복궁이 있어요. 조선의 으뜸 궁궐이에요. 잠깐 들렀다 갈까요?") { listen() }) listen()
        }
        heard?.let { Line("들은 말: $it") }
        if (status.isNotEmpty()) Line(status)
    }
}

/** 8. 방문 기록: 샘플 넣기·전체 지우기(내 지도·기록 화면 확인용). */
@Composable
private fun VisitCheck(c: AppContainer) {
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    Section("8. 방문 기록") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("샘플 3곳 넣기", Modifier.weight(1f), height = 64.dp) {
                scope.launch {
                    // 간 장소까지 넣어 기록 카드에 그 장소 사진이 뜨게 한다.
                    listOf(Triple("11110", "🏯", "경복궁"), Triple("30200", "☕", "대전시민천문대"), Triple("26350", "🌊", "해운대해수욕장"))
                        .forEach { (code, emoji, name) ->
                            val place = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                c.tourism.all.firstOrNull { it.name == name }?.let {
                                    com.team.travelmap.core.model.Place(it.name, com.team.travelmap.core.model.GeoPoint(it.lat, it.lng), com.team.travelmap.core.model.PlaceCategory.SIGHT, RegionId(code))
                                }
                            }
                            c.visits.checkManually(RegionId(code), place); c.visits.setMark(RegionId(code), MarkStyle.Emoji(emoji))
                        }
                }
            }
            SecondaryButton("전체 지우기", Modifier.weight(1f), height = 64.dp) { confirm = true }
        }
        Line("샘플: 서울 종로구 경복궁 🏯, 대전 유성구 대전시민천문대 ☕, 부산 해운대구 해운대해수욕장 🌊 (직접 체크, 간 장소 포함)")
    }
    if (confirm) ConfirmDialog("방문 기록을 모두 지울까요?", "모든 방문 기록과 칸 표시가 지워지고 되돌릴 수 없어요.", "모두 지우기",
        onConfirm = { scope.launch { c.visits.deleteAll() } }, onDismiss = { confirm = false })
}

/** 9. 2번 신호(리뷰·다시 출발) 지금 값. 화면은 1번이 다음에 붙인다. */
@Composable
private fun SignalCheck(c: AppContainer) {
    val review by c.trip.reviewRequest.collectAsStateWithLifecycle()
    val resume by c.trip.resumeRequest.collectAsStateWithLifecycle()
    Section("9. 리뷰·여행을 계속할까요") {
        Line("경유지 리뷰: ${review?.let { "${it.place.name} (경유지 ${it.index + 1})" } ?: "없음"}")
        Line("여행을 계속할까요: ${resume?.let { "다음 ${it.next.name}, 남은 경유지 ${it.remainingWaypoints}곳" } ?: "없음"}")
        Line("둘 다 안내 중 주차·시동으로 생겨요. 아래 버튼으로 화면만 미리 볼 수 있어요(리뷰는 실제로 저장돼요).")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("리뷰 화면 미리보기", Modifier.weight(1f), height = 64.dp) {
                PromptPreview.review.value = com.team.travelmap.core.trip.WaypointReviewRequest(PromptPreview.samplePlace, 0)
            }
            SecondaryButton("계속할까요 미리보기", Modifier.weight(1f), height = 64.dp) { PromptPreview.resume.value = PromptPreview.sampleResume() }
        }
    }
}

/** 10. 초기화. */
@Composable
private fun ResetCheck(c: AppContainer, onClose: () -> Unit) {
    Section("10. 초기화") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SecondaryButton("설정 처음값으로", Modifier.weight(1f), height = 64.dp) { c.settings.update { Settings(devMode = true) } }
            SecondaryButton("개발자 모드 끄기", Modifier.weight(1f), height = 64.dp) {
                DevFlags.fakeDriving.value = false
                c.settings.update { it.copy(devMode = false) }
                onClose()
            }
        }
    }
}
