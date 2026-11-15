package com.team.travelmap.ui

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.team.travelmap.AppContainer
import com.team.travelmap.R
import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.core.model.Gear
import com.team.travelmap.core.model.PilotScope
import com.team.travelmap.core.model.Place
import com.team.travelmap.core.model.PlaceCategory
import com.team.travelmap.core.model.RegionId
import com.team.travelmap.core.trip.TripState
import com.team.travelmap.navi.NaviRepository
import com.team.travelmap.roulette.RouletteFilter
import com.team.travelmap.roulette.RouletteMode
import com.team.travelmap.vehicle.MockVehicleRepository
import kotlinx.coroutines.launch

/** 상단 메뉴 5개(2차 기획 확정). driveOk = 주행 중에도 열리는 탭. */
internal enum class MenuTab(val label: String, val driveOk: Boolean) {
    TRIP("여행", true), THEME("테마", true), MY_MAP("내 지도", false), HISTORY("기록", false), SETTINGS("설정", false)
}

/**
 * 첫 화면. 담당: 1번(지도·화면). Codex 「Pleos 디자인 적용가이드」·ui 시안 기준.
 * 헤더 아래 메뉴 탭 5개(여행 / 테마 추천 / 내 지도 / 여행 기록 / 설정). 주행 중에는 여행·테마 추천만 열린다.
 * 창이 가로로 넓으면(단독 1682×1210) 지도+패널 좌우, 세로로 길면(1:1 분할 841×1210) 위아래로 놓는다.
 * 패널 안의 로직 호출은 2번(여행 흐름)·3번(룰렛·검색·스토리) 것을 쓴다.
 */
@Composable
fun AppScreen(c: AppContainer, showDebugPanel: Boolean = true) {
    val trip by c.trip.state.collectAsStateWithLifecycle()
    val realRestricted by c.restrictions.restricted.collectAsStateWithLifecycle()
    val fakeDriving by DevFlags.fakeDriving.collectAsStateWithLifecycle()
    // 개발자 화면 "주행 중 화면 미리보기"가 켜져 있으면 주행 제한 화면으로 보여 준다.
    val restricted = realRestricted || fakeDriving
    val devMode = c.settings.state.collectAsStateWithLifecycle().value.devMode
    DevTextReceiver(devMode)
    val visited by remember { c.visits.visitedRegions }.collectAsStateWithLifecycle(emptySet())
    val activeCount = remember { c.regions.shapes.count { PilotScope.isActive(it.region.id) } }
    var tab by rememberSaveable { mutableStateOf(MenuTab.TRIP) }
    // 테마 탭과 여행 탭(코스 추천)이 같은 테마를 쓴다.
    var theme by rememberSaveable { mutableStateOf(com.team.travelmap.core.model.TravelTheme.HEALING) }
    // 테마 탭 "코스 보기"에서 고른 도시(시·도 코드). 여행 탭 코스 추천이 이 도시로 시작한다.
    var courseCity by rememberSaveable { mutableStateOf<String?>(null) }
    // 내 지도 "방문 기록 보기"로 기록 탭의 그 기록 상세를 연다.
    var openVisitId by rememberSaveable { mutableStateOf<Long?>(null) }
    // 관광 데이터(1만여 곳)·테마 카드를 화면이 뜨자마자 IO 에서 미리 읽어 둔다(테마 탭 첫 로드 지연 줄이기).
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { c.themeCuration.cards(); c.tourism.all } }
    }
    val colors = LocalChaumColors.current

    // 주행 제한이 걸리면 잠긴 탭에서 여행 탭으로 돌아온다.
    LaunchedEffect(restricted) { if (restricted && !tab.driveOk) tab = MenuTab.TRIP }
    // 여행이 끝나면(기록 여부 확인 포함) 여행 탭으로.
    LaunchedEffect(trip is TripState.Completed) { if (trip is TripState.Completed) tab = MenuTab.TRIP }

    BoxWithConstraints(Modifier.fillMaxSize().background(colors.window)) {
        val wide = maxWidth >= maxHeight * 1.1f
        // 창 크기 확인용(logcat -s AppScreen).
        LaunchedEffect(maxWidth, maxHeight) { Log.d("AppScreen", "window ${maxWidth}x${maxHeight} wide=$wide") }
        val type = if (wide) WideType else SplitType
        val side = if (wide) 32.dp else 24.dp

        CompositionLocalProvider(LocalChaumType provides type) {
            // 2번 신호 화면(경유지 리뷰·여행을 계속할까요). 어느 탭에서든 위에 뜬다.
            TripPrompts(c)
            Column(Modifier.fillMaxSize().padding(horizontal = side)) {
                Header(visited.count(PilotScope::isActive), activeCount, driving = trip is TripState.Guiding || restricted)
                MenuTabs(tab, restricted) { tab = it }
                HorizontalDivider(color = colors.divider)
                if (fakeDriving) {
                    // 개발자 화면의 주행 중 미리보기: 설정 탭이 잠기므로 여기서 끈다.
                    Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp).background(colors.selectedBg, RoundedCornerShape(14.dp))
                            .clickable(role = Role.Button) { DevFlags.fakeDriving.value = false }.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("개발자: 주행 중 화면 미리보기 중", style = LocalChaumType.current.placeAddress, color = colors.text, modifier = Modifier.weight(1f))
                        Text("끄기", style = LocalChaumType.current.body, color = colors.primary)
                    }
                }
                Spacer(Modifier.height(20.dp))
                Box(Modifier.weight(1f).fillMaxWidth().padding(bottom = side)) {
                    when (tab) {
                        MenuTab.TRIP -> TripTab(c, wide, side, trip, restricted, visited, activeCount, showDebugPanel, theme, courseCity) { theme = it }
                        MenuTab.THEME -> ThemeTab(c, restricted, theme, onTheme = { theme = it },
                            onCourses = { city -> courseCity = city; tab = MenuTab.TRIP }) { tab = MenuTab.TRIP }
                        MenuTab.MY_MAP -> MyMapTab(c, wide, side) { id -> openVisitId = id; tab = MenuTab.HISTORY }
                        MenuTab.HISTORY -> HistoryTab(c, wide, restricted, openVisitId, onOpened = { openVisitId = null }) { tab = MenuTab.TRIP }
                        MenuTab.SETTINGS -> SettingsTab(c)
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuTabs(tab: MenuTab, restricted: Boolean, onSelect: (MenuTab) -> Unit) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    // 확정 시안: 글자 탭 + 선택 탭 아래 초록 밑줄과 점. 주행 중 잠긴 탭은 흐리게.
    Row(Modifier.fillMaxWidth()) {
        MenuTab.entries.forEach { t ->
            val enabled = !restricted || t.driveOk
            val on = tab == t
            Box(
                Modifier
                    .weight(1f)
                    .height(72.dp)
                    .clickable(enabled = enabled, role = Role.Tab) { onSelect(t) }
                    .semantics { selected = on },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    t.label, style = type.cityTab,
                    color = when { on -> colors.text; enabled -> colors.textSub; else -> colors.textSub.copy(alpha = 0.4f) },
                    fontWeight = if (on) androidx.compose.ui.text.font.FontWeight.Bold else null,
                )
                if (on) {
                    Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth(0.6f).height(3.dp).background(colors.primary))
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 0.dp).size(9.dp).background(colors.primary, CircleShape))
                }
            }
        }
    }
}

/** 여행 탭: 지도 + 여행 단계별 패널(기존 첫 화면). */
@Composable
private fun TripTab(
    c: AppContainer, wide: Boolean, side: Dp, trip: TripState, restricted: Boolean,
    visited: Set<RegionId>, activeCount: Int, showDebugPanel: Boolean,
    theme: com.team.travelmap.core.model.TravelTheme, courseCity: String?, onTheme: (com.team.travelmap.core.model.TravelTheme) -> Unit,
) {
    val colors = LocalChaumColors.current
    var focusSido by rememberSaveable { mutableStateOf<String?>(null) }
    val highlighted: RegionId? = when (val s = trip) {
        is TripState.Planned -> s.region.id
        is TripState.Guiding -> s.region.id
        is TripState.Arrived -> s.region.id
        else -> null
    }
    // 여행지가 새로 정해지면(룰렛·지도·내비에서 돌아온 뒤 화면 재생성 포함) 그 도시로 확대해 보여 준다.
    LaunchedEffect(highlighted) { highlighted?.let { focusSido = it.sidoCode } }
    // 테마 탭 "코스 보기"로 넘어오면 그 도시로 확대한다.
    LaunchedEffect(courseCity) { courseCity?.let { focusSido = it } }
    // 완료하면 방문한 도시를 보여 준다.
    LaunchedEffect(trip) { (trip as? TripState.Completed)?.let { focusSido = it.region.id.sidoCode } }

    val guiding = trip is TripState.Guiding || trip is TripState.Arrived
    val canPickOnMap = !restricted && (trip is TripState.Idle || trip is TripState.Completed || trip is TripState.Planned)
    val visitedCount = visited.count(PilotScope::isActive)

    val mapArea: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            // 도시 탭: 안내 중·주행 제한에서는 숨긴다(가이드 '안내 중' 상태).
            // 분할 창에서 여행지를 고른 뒤(Planned)에는 경유지 목록 자리를 위해 도시 탭을 숨긴다("다시 고르기"로 돌아감).
            val planningInSplit = !wide && trip is TripState.Planned
            if (!restricted && !guiding && !planningInSplit) {
                CityTabs(focusSido) { focusSido = it }
                Spacer(Modifier.height(16.dp))
            }
            ChaumMap(
                c, focusSido, highlighted,
                title = if (restricted || guiding) "주행 중 · 지도 잠김" else mapViews.first { it.first == focusSido }.second,
                onRegionTap = if (!canPickOnMap) null else { id ->
                    focusSido = id.sidoCode
                    c.regions.find(id)?.let { r -> c.trip.plan(r, c.curation.defaultDestination(r)) }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        }
    }

    val panel: @Composable (Modifier) -> Unit = { m ->
        Column(
            m.background(colors.card, RoundedCornerShape(22.dp)).border(1.dp, colors.outline, RoundedCornerShape(22.dp)).padding(side),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Mock 시험 버튼은 맨 위에: 주행 중 카드(동행·이야기·주변)가 많으면 아래쪽은 화면 밖으로 밀려 안 보였다(#38 참고).
            if (!c.usePleos && showDebugPanel) DebugPanel(c)
            when (val s = trip) {
                TripState.Idle -> {
                    if (restricted) Notice("주차 후 여행지를 고를 수 있어요.")
                    else CoursePanel(c, theme, onTheme, initialCity = courseCity) { RecommendPanel(c, visited) { id -> focusSido = id.sidoCode } }
                }
                is TripState.Completed -> CompletedPanel(c, s, visitedCount, activeCount, visited)
                is TripState.Planned -> PlannedPanel(c, s, restricted)
                is TripState.Guiding -> DrivingPanel(c, s)
                is TripState.Arrived -> {
                    Kicker("도착")
                    Title("${s.region.shortName}에 도착했어요")
                    Body("주차(P)하면 방문 기록에 저장됩니다.")
                }
            }
        }
    }

    if (wide) {
        Row(Modifier.fillMaxSize()) {
            mapArea(Modifier.weight(953f).fillMaxHeight())
            Spacer(Modifier.width(20.dp))
            panel(Modifier.weight(645f).fillMaxHeight())
        }
    } else if (trip is TripState.Guiding || (trip is TripState.Idle && !restricted)) {
        // 분할 창 대기(코스 선택, 시안 01)·주행 중: 지도 없이 패널만.
        // 분할 창 주행 중: 옆에 Pleos 내비 지도가 있으므로 지도를 빼고 주행 중 패널만(Codex 마실 주행 중 시안).
        panel(Modifier.fillMaxSize())
    } else {
        // 분할 창: 메뉴 탭(66+16dp)이 생긴 만큼 지도를 줄여 패널 높이를 지킨다.
        val mapHeight: Dp = when {
            guiding || restricted -> 461.dp
            // 경유지 목록이 최소 2줄은 보이도록(841×1210 실측: 380dp 이면 목록이 40dp 로 눌려 고를 수 없었음).
            trip is TripState.Planned -> 220.dp
            else -> 458.dp
        }
        Column(Modifier.fillMaxSize()) {
            mapArea(Modifier.height(mapHeight).fillMaxWidth())
            Spacer(Modifier.height(16.dp))
            panel(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

// ---------- 공통 조각 ----------

@Composable
private fun Header(visitedCount: Int, total: Int, driving: Boolean) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    Row(Modifier.fillMaxWidth().height(88.dp), verticalAlignment = Alignment.CenterVertically) {
        // 마실 글자 + 산·길 심볼(Codex 승인 시안). 심볼 아래 끝을 글자 아래선에 맞춘다.
        Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.semantics(mergeDescendants = true) { contentDescription = "마실" }) {
            Text("마실", style = type.panelTitle, color = colors.primary, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold)
            Spacer(Modifier.width(8.dp))
            MasilSymbol(Modifier.padding(bottom = 4.dp).size(width = 72.dp, height = 46.dp), colors.primary, colors.window)
        }
        Spacer(Modifier.weight(1f))
        if (driving) {
            Pill("주행 중")
            return@Row
        }
        Row(
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.semantics(mergeDescendants = true) {
                contentDescription = "다녀온 곳 ${visitedCount}곳, 시범 지역 ${total}곳"
            },
        ) {
            Text("다녀온 곳", style = type.label, color = colors.textSub, modifier = Modifier.padding(bottom = 8.dp))
            Spacer(Modifier.width(16.dp))
            Text("$visitedCount", style = type.countNumber, color = colors.count)
            Spacer(Modifier.width(12.dp))
            Text("/ $total", style = type.countDenominator, color = colors.textSub, modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

// ---------- 대기: 여행지 추천 ----------

@Composable
private fun RecommendPanel(c: AppContainer, visited: Set<RegionId>, onRegionPicked: (RegionId) -> Unit) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    val energy by c.vehicle.energy.collectAsStateWithLifecycle()
    val location by c.navi.currentLocation.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<String?>(null) }
    val range = energy.rangeKm

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("주행 가능 거리", style = type.body, color = colors.textSub)
        Spacer(Modifier.weight(1f))
        // 거리를 받지 못하면 수치를 숨긴다(가이드: 제공되지 않은 값은 숨김).
        Text(range?.let { "${it.toInt()} km" } ?: "확인 중", style = type.placeName, color = colors.text)
    }
    HorizontalDivider(color = colors.divider)

    // [룰렛 2가지 모드] 로직 담당: 3번
    fun pick(mode: RouletteMode) {
        val origin = location ?: GeoPoint(37.5663, 126.9779)
        val candidates = c.roulette.candidates(c.regions.regions, visited, origin, energy, mode, RouletteFilter())
        val picked = c.roulette.pick(candidates)
        if (picked == null) {
            message = "조건에 맞는 지역이 없어요"
        } else {
            message = if (picked.needsRecharge) "중간에 충전이 필요해요" else null
            c.trip.plan(picked.region, c.curation.defaultDestination(picked.region))
            onRegionPicked(picked.region.id)
        }
    }
    RecommendRow(
        title = "지금 갈 수 있는 곳",
        sub = if (range != null) "현재 주행 가능 거리 안에서 추천" else "주행 가능 거리를 받으면 추천할 수 있어요",
        primary = true,
        enabled = range != null,
    ) { pick(RouletteMode.REACHABLE) }
    RecommendRow(title = "전체 지역에서 추천", sub = "서울·대전·부산 46개 구·군에서 추천", primary = false) { pick(RouletteMode.ALL) }
    message?.let { Body(it) }
}

@Composable
private fun RecommendRow(title: String, sub: String, primary: Boolean, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    val bg = if (primary && enabled) colors.primary else colors.card
    val fg = if (primary && enabled) colors.onPrimary else if (enabled) colors.text else colors.textSub
    Row(
        Modifier
            .fillMaxWidth()
            .height(99.dp)
            .background(bg, RoundedCornerShape(18.dp))
            .then(if (primary && enabled) Modifier else Modifier.border(1.dp, colors.outline, RoundedCornerShape(18.dp)))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = type.button, color = fg)
            Text(sub, style = type.placeAddress, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("→", style = type.panelTitle, color = fg)
    }
}

// ---------- 여행지 선택: 경유지 ----------

@Composable
private fun ColumnScope.PlannedPanel(c: AppContainer, s: TripState.Planned, restricted: Boolean) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    // 코스·테마 상세에서 미리 담아 온 경유지(s.waypoints)를 목록 맨 위에 두고 선택된 채로 시작한다.
    val places = remember(s.region.id, s.destination.point, s.waypoints) {
        (s.waypoints + c.curation.placesOf(s.region.id)).filter { it.point != s.destination.point }.distinctBy { it.name to it.point }
    }
    val selected = remember(s.region.id, s.destination.point, s.waypoints) { mutableStateListOf<Place>().apply { addAll(s.waypoints) } }

    Title(s.region.shortName)
    Text("목적지  ${s.destination.name}", style = type.body, color = colors.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis)

    if (restricted) {
        Body("주차 후 경유지를 고르고 출발해 주세요.")
        return
    }

    var selectedCategory by rememberSaveable(s.region.id.code) { mutableStateOf<String?>(null) }
    // 장소명·주소 검색(정차 중에만 보임, 담당: 3번)
    var keyword by rememberSaveable(s.region.id.code) { mutableStateOf("") }
    var startError by remember(s.region.id) { mutableStateOf<String?>(null) }
    val query = keyword.trim()
    val filteredPlaces = places.filter { place ->
        (selectedCategory == null || place.category.name == selectedCategory ||
            (selectedCategory == PlaceCategory.SIGHT.name && place.category == PlaceCategory.LANDMARK)) &&
            (query.isEmpty() || place.name.contains(query, ignoreCase = true) || place.address.orEmpty().contains(query))
    }

    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(null to "전체", PlaceCategory.SIGHT.name to "관광지", PlaceCategory.FOOD.name to "음식점").forEach { (cat, label) ->
            ChipTab(label, selectedCategory == cat, Modifier.size(width = 146.dp, height = 58.dp)) { selectedCategory = cat }
        }
    }
    OutlinedTextField(
        value = keyword,
        onValueChange = { keyword = it },
        label = { Text("장소명 또는 주소 검색", style = type.placeAddress) },
        textStyle = type.placeAddress,
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    val maxWaypoints = NaviRepository.MAX_WAYPOINTS
    Row(Modifier.fillMaxWidth()) {
        Text("경유지 선택", style = type.placeName, color = colors.text)
        Spacer(Modifier.weight(1f))
        Text("${selected.size} / $maxWaypoints", style = type.placeName, color = colors.count)
    }
    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (filteredPlaces.isEmpty()) item { Body("검색 조건에 맞는 장소가 없어요.") }
        items(filteredPlaces) { place ->
            val order = selected.indexOf(place).takeIf { it >= 0 }?.plus(1)
            PlaceCard(place, order, full = selected.size >= maxWaypoints) {
                if (order != null) selected -= place
                else if (selected.size < maxWaypoints) selected += place
            }
        }
    }
    startError?.let { Text(it, style = type.placeAddress, color = colors.error) }
    HorizontalDivider(color = colors.divider)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SecondaryButton("다시 고르기", Modifier.weight(1f)) { c.trip.cancel() }
        PrimaryButton("출발", Modifier.weight(2f)) {
            c.trip.plan(s.region, s.destination, selected.toList())
            startError = c.trip.start().exceptionOrNull()?.let { "경로를 시작하지 못했어요. 내비 연결을 확인하고 다시 시도해주세요." }
        }
    }
}

/** 장소 카드: 두 줄 텍스트, 썸네일 없음. 카드 전체 터치, 오른쪽 원에 경유 순서(1·2). */
@Composable
private fun PlaceCard(place: Place, order: Int?, full: Boolean, onToggle: () -> Unit) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    val chosen = order != null
    val blocked = full && !chosen
    val detail = if (blocked) "경유지는 최대 2곳까지 고를 수 있어요" else (place.address ?: place.description).orEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .height(88.dp)
            .background(if (chosen) colors.waypointRow else colors.window, RoundedCornerShape(14.dp))
            .border(1.dp, colors.divider, RoundedCornerShape(14.dp))
            .clickable(role = Role.Checkbox, onClick = onToggle)
            .semantics(mergeDescendants = true) {
                contentDescription = place.name + (if (chosen) ", 경유지 ${order}번" else "") + ", " + detail
                selected = chosen
            }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(place.name, style = type.placeName, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(detail, style = type.placeAddress, color = colors.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(
            Modifier
                .size(40.dp)
                .then(if (chosen) Modifier.background(colors.primary, CircleShape) else Modifier.border(2.dp, colors.outline, CircleShape)),
            contentAlignment = Alignment.Center,
        ) { if (chosen) Text("$order", style = type.placeAddress, color = colors.onPrimary) }
    }
}

// ---------- 안내 중 ----------

/** 남은 시간 표기: 1분 미만 / n분 / n시간 m분. */
internal fun formatRemainTime(seconds: Int): String {
    val minutes = seconds / 60
    return when {
        minutes < 1 -> "1분 미만"
        minutes < 60 -> "${minutes}분"
        minutes % 60 == 0 -> "${minutes / 60}시간"
        else -> "${minutes / 60}시간 ${minutes % 60}분"
    }
}

// ---------- 완료 ----------

@Composable
private fun CompletedPanel(c: AppContainer, s: TripState.Completed, visitedCount: Int, total: Int, visited: Set<RegionId>) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    val scope = rememberCoroutineScope()
    val visit by remember(s.visitId) { c.visits.observe(s.visitId) }.collectAsStateWithLifecycle(null)
    // 설정 "자동 방문 기록"이 꺼져 있으면 기록 전 상태로 저장돼 있다 → 남길지 묻는다.
    if (visit?.confirmed == false) {
        Kicker("여행 완료")
        Title("${s.region.name}에 다녀왔어요")
        Body("이번 방문을 내 지도에 남길까요?")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("기록 안 함", Modifier.weight(1f), height = 88.dp) {
                scope.launch { s.allVisitIds.forEach { c.visits.delete(it) } }
                c.trip.cancel()
            }
            PrimaryButton("기록하기", Modifier.weight(2f), height = 88.dp) { scope.launch { s.allVisitIds.forEach { c.visits.confirm(it) } } }
        }
        return
    }
    Kicker("여행 완료")
    Title("${s.region.name}${s.region.name.objectParticle()} 채웠어요")
    Body("이번 여행이 방문 기록에 추가됐어요.")
    Row(
        Modifier.fillMaxWidth().background(colors.waypointRow, RoundedCornerShape(14.dp)).padding(24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(s.region.shortName, style = type.placeName, color = colors.text)
            Text("시범 지역 중 ${visitedCount}곳 방문", style = type.placeAddress, color = colors.textSub)
        }
        Text("$visitedCount / $total", style = type.countNumber, color = colors.count)
    }
    // 도시 완주 배지: 서울 25/25, 대전 5/5, 부산 16/16 일 때만
    val sido = s.region.id.sidoCode
    val done = visited.count { it.sidoCode == sido && PilotScope.isActive(it) }
    if (done >= (cityTotals[sido] ?: Int.MAX_VALUE)) CityBadge(sido)
    PrimaryButton("지도 보기", Modifier.fillMaxWidth()) { c.trip.cancel() }
}

@Composable
internal fun CityBadge(sido: String) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    val res = when (sido) { "11" -> R.drawable.badge_seoul; "30" -> R.drawable.badge_daejeon; else -> R.drawable.badge_busan }
    val name = cityShort[sido].orEmpty()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(BitmapPainter(ImageBitmap.imageResource(res)), contentDescription = null, modifier = Modifier.size(96.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.semantics(mergeDescendants = true) { contentDescription = "$name 완주 배지 획득" }) {
            Text("$name 완주", style = type.placeName, color = colors.text)
            Text("${cityTotals[sido]}개 구·군을 모두 채웠어요", style = type.placeAddress, color = colors.textSub)
        }
    }
}

/** Mock 모드에서만 보이는 시연·테스트용 패널. */
@Composable
private fun DebugPanel(c: AppContainer) {
    val mock = c.vehicle as? MockVehicleRepository ?: return
    val gear by mock.gear.collectAsStateWithLifecycle()
    val colors = LocalChaumColors.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("[Mock] 기어: $gear", color = colors.textSub)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "P" to { mock.setGear(Gear.PARK) }, "D" to { mock.setGear(Gear.DRIVE) },
                "20%" to { mock.setEnergy(20f, 90.0) }, "100%" to { mock.setEnergy(100f, 560.0) },
                "완료" to { c.trip.forceComplete() },
            ).forEach { (label, action) ->
                OutlinedButton(onClick = action, contentPadding = PaddingValues(horizontal = 12.dp)) { Text(label, color = colors.text) }
            }
        }
    }
}
