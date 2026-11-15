package com.team.travelmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.team.travelmap.AppContainer
import com.team.travelmap.core.model.PilotScope
import com.team.travelmap.core.model.RegionId
import com.team.travelmap.settings.MarkStyle
import kotlinx.coroutines.launch

/**
 * 담당: 1번(지도·화면). 내 지도 탭: 칸을 눌러 방문 기록(날짜·횟수)을 보고,
 * 표시(무늬/색/이모지)를 고르고, 직접 체크하거나 지운다. 여행 계획은 여행 탭에서만 한다.
 */
@Composable
internal fun MyMapTab(c: AppContainer, wide: Boolean, side: androidx.compose.ui.unit.Dp, onOpenVisit: (Long) -> Unit = {}) {
    val colors = LocalChaumColors.current
    var focusSido by rememberSaveable { mutableStateOf<String?>(null) } // 처음엔 전국(다녀온 곳 한눈에)
    var selectedCode by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = selectedCode?.let(::RegionId)
    val visited by remember { c.visits.visitedRegions }.collectAsStateWithLifecycle(emptySet())
    // 다녀온 칸은 고른 표시(색·이모지)가 보이도록 선택색으로 덮지 않는다.
    val highlight = selected?.takeIf { it !in visited }

    val settings by c.settings.state.collectAsStateWithLifecycle()
    val type = LocalChaumType.current
    val activeCount = remember { c.regions.shapes.count { PilotScope.isActive(it.region.id) } }
    // 확정 시안 04: 제목 + 채운 수 배지, "방문 표시" 스위치, 지도 아래 도시 고르기.
    val mapArea: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("다녀온 만큼, 나의 지도", style = type.panelTitle, color = colors.text, modifier = Modifier.weight(1f), maxLines = 1)
                Pill("${visited.count(PilotScope::isActive)} / $activeCount")
                Spacer(Modifier.width(16.dp))
                Row(
                    Modifier.height(64.dp).toggleable(value = settings.showVisitedOnMap, role = Role.Switch) { v -> c.settings.update { it.copy(showVisitedOnMap = v) } },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("방문 표시", style = type.placeAddress, color = colors.textSub)
                    Spacer(Modifier.width(8.dp))
                    MasilSwitch(settings.showVisitedOnMap)
                }
            }
            Spacer(Modifier.height(12.dp))
            // 처음엔 큰 전국 지도. 전국에서 누르면 그 도시 상세 지도로 들어가고, 도시 지도에서 칸을 누르면 그 칸 기록.
            ChaumMap(
                c, focusSido, highlight,
                title = mapViews.first { it.first == focusSido }.second,
                onRegionTap = { id ->
                    if (focusSido == null) focusSido = id.sidoCode
                    else { focusSido = id.sidoCode; selectedCode = id.code }
                },
                modifier = Modifier.weight(1f).fillMaxWidth(),
                photoMarkers = true,
                selected = selected,
                // 사진·이름을 누르면 그 구를 고르고 아래에 방문 카드(전국 보기에서도 확대 없이).
                onMarkerTap = { id -> selectedCode = id.code },
            )
            Spacer(Modifier.height(16.dp))
            CityTabs(focusSido) { focusSido = it; selectedCode = null }
        }
    }
    var manage by remember { mutableStateOf(false) }
    val panel: @Composable (Modifier) -> Unit = { m ->
        Column(
            m.background(colors.card, RoundedCornerShape(22.dp)).border(1.dp, colors.outline, RoundedCornerShape(22.dp)).padding(side).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (selected == null) MyMapSummary(c) { focusSido = it }
            else {
                RegionCompactCard(c, selected, onOpenVisit, onManage = { manage = true }) { selectedCode = null }
                RegionRecord(c, selected, onOpenVisit, showLatest = false) { selectedCode = null }
            }
        }
    }
    if (wide) {
        Row(Modifier.fillMaxSize()) {
            mapArea(Modifier.weight(953f).fillMaxHeight())
            Spacer(Modifier.width(20.dp))
            panel(Modifier.weight(645f).fillMaxHeight())
        }
    } else {
        // 분할 창: 지도가 주인공. 아래는 고정 높이 카드 하나(고른 구: 작은 사진·이름·방문 정보·기록 보기 / 안 고름: 도시별 채운 수).
        // 칸 표시·직접 체크·기록 지우기는 "관리"를 눌러 창으로 연다. 고를 때 지도가 줄어들지 않는다.
        Column(Modifier.fillMaxSize()) {
            mapArea(Modifier.weight(1f).fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth().height(148.dp)) {
                if (selected == null) CityCountsRow(c) { focusSido = it }
                else RegionCompactCard(c, selected, onOpenVisit, onManage = { manage = true }) { selectedCode = null }
            }
        }
    }
    if (manage && selected != null) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { manage = false }, properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)) {
            Column(
                Modifier.fillMaxWidth(0.9f).heightIn(max = 900.dp).background(colors.card, RoundedCornerShape(24.dp))
                    .border(1.dp, colors.outline, RoundedCornerShape(24.dp)).padding(28.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) { RegionRecord(c, selected, onOpenVisit, showLatest = false) { manage = false } }
        }
    }
}

/** 도시별 채운 수(분할 창 아래 고정 카드). 누르면 그 도시 지도. */
@Composable
private fun CityCountsRow(c: AppContainer, onCity: (String) -> Unit) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    val visited by remember { c.visits.visitedRegions }.collectAsStateWithLifecycle(emptySet())
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        cityTotals.forEach { (sido, total) ->
            val done = visited.count { it.sidoCode == sido && PilotScope.isActive(it) }
            Column(
                Modifier.weight(1f).fillMaxHeight()
                    .background(if (done > 0) colors.selectedBg else colors.card, RoundedCornerShape(18.dp))
                    .border(1.dp, colors.outline, RoundedCornerShape(18.dp))
                    .clickable(role = Role.Button, onClickLabel = "${cityShort[sido]} 지도 보기") { onCity(sido) }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(cityShort[sido].orEmpty(), style = type.placeName, color = colors.text)
                Text("$done / $total", style = type.panelTitle, color = colors.count)
            }
        }
    }
}

/**
 * 고른 구의 고정 높이 카드(148dp): 작은 사진, 구 이름, 간 장소·날짜·횟수, "기록 보기", 관리·닫기.
 * 다녀온 적 없는 구면 "직접 체크" 버튼.
 */
@Composable
private fun RegionCompactCard(c: AppContainer, id: RegionId, onOpenVisit: (Long) -> Unit, onManage: () -> Unit, onClose: () -> Unit) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    val scope = rememberCoroutineScope()
    val region = remember(id) { c.regions.find(id) }
    val all by remember { c.visits.visits }.collectAsStateWithLifecycle(emptyList())
    val records = all.filter { it.regionCode == id.code }
    val latest = records.maxByOrNull { it.visitedAt }
    Row(
        Modifier.fillMaxWidth().height(148.dp).background(colors.card, RoundedCornerShape(20.dp)).border(1.dp, colors.outline, RoundedCornerShape(20.dp)).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (latest != null) {
            val photo = rememberVisitPhoto(c, latest)
            Box(Modifier.size(width = 150.dp, height = 118.dp).clip(RoundedCornerShape(14.dp)).background(colors.surface2), contentAlignment = Alignment.Center) {
                when (photo) {
                    is PhotoState.Ready -> androidx.compose.foundation.Image(
                        photo.photo.bitmap, contentDescription = null,
                        contentScale = if (photo.photo.crop) androidx.compose.ui.layout.ContentScale.Crop else androidx.compose.ui.layout.ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    PhotoState.Loading -> Text("불러오는 중", style = type.placeAddress, color = colors.textSub)
                    PhotoState.None -> Text("사진 없음", style = type.placeAddress, color = colors.textSub)
                }
            }
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(region?.name ?: id.code, style = type.panelTitle, color = colors.text, maxLines = 1)
            if (latest != null) {
                Text(latest.placeName ?: "간 장소 기록 없음", style = type.body, color = if (latest.placeName == null) colors.textSub else colors.text, maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text("${java.text.SimpleDateFormat("M.d", java.util.Locale.KOREA).format(java.util.Date(latest.visitedAt))} 방문 · ${records.size}회", style = type.placeAddress, color = colors.textSub)
            } else Text(if (PilotScope.isActive(id)) "아직 다녀온 기록이 없어요" else "시범 지역 밖이에요", style = type.body, color = colors.textSub)
        }
        if (latest != null) {
            PrimaryButton("기록 보기", Modifier.width(150.dp), height = 72.dp) { onOpenVisit(latest.id) }
            Box(Modifier.size(64.dp).clickable(role = Role.Button, onClickLabel = "칸 표시·기록 관리", onClick = onManage), contentAlignment = Alignment.Center) {
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.MoreVert, contentDescription = "관리", tint = colors.textSub)
            }
        } else if (PilotScope.isActive(id)) {
            PrimaryButton("직접 체크", Modifier.width(150.dp), height = 72.dp) { scope.launch { c.visits.checkManually(id) } }
        }
        Box(Modifier.size(64.dp).clickable(role = Role.Button, onClickLabel = "닫기", onClick = onClose), contentAlignment = Alignment.Center) {
            Text("✕", style = type.placeName, color = colors.textSub)
        }
    }
}

/** 아무 칸도 고르지 않았을 때: 도시별 채운 수(누르면 그 도시 상세 지도)와 완주 배지. */
@Composable
private fun MyMapSummary(c: AppContainer, onCity: (String) -> Unit) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    val visited by remember { c.visits.visitedRegions }.collectAsStateWithLifecycle(emptySet())
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        cityTotals.forEach { (sido, total) ->
            val done = visited.count { it.sidoCode == sido && PilotScope.isActive(it) }
            Column(
                Modifier.weight(1f)
                    .background(if (done > 0) colors.selectedBg else colors.card, RoundedCornerShape(18.dp))
                    .border(1.dp, colors.outline, RoundedCornerShape(18.dp))
                    .clickable(role = Role.Button, onClickLabel = "${cityShort[sido]} 지도 보기") { onCity(sido) }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
            ) {
                Text(cityShort[sido].orEmpty(), style = type.placeName, color = colors.text)
                Text("$done / $total", style = type.placeName, color = colors.count)
            }
        }
    }
    cityTotals.forEach { (sido, total) ->
        if (visited.count { it.sidoCode == sido && PilotScope.isActive(it) } >= total) CityBadge(sido)
    }
    Body("지도를 누르면 도시 지도로, 도시 지도에서 칸을 누르면 그 칸 기록을 볼 수 있어요.")
}

/** 고른 칸의 방문 기록과 표시 고르기. */
@Composable
private fun RegionRecord(c: AppContainer, id: RegionId, onOpenVisit: (Long) -> Unit, showLatest: Boolean = true, onClose: () -> Unit) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    val scope = rememberCoroutineScope()
    val region = remember(id) { c.regions.find(id) }
    val all by remember { c.visits.visits }.collectAsStateWithLifecycle(emptyList())
    val marks by remember { c.visits.marks }.collectAsStateWithLifecycle(emptyMap())
    val settings by c.settings.state.collectAsStateWithLifecycle()
    val records = all.filter { it.regionCode == id.code }
    var confirmDeleteAll by remember { mutableStateOf(false) }

    // 머리줄: 이름 + 오른쪽에 작은 "삭제"(기록이 있을 때)와 닫기. 다른 앱처럼 지우기는 작게.
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Title(region?.shortName ?: id.code)
        Spacer(Modifier.weight(1f))
        if (records.isNotEmpty()) {
            Text(
                "삭제", style = type.body, color = colors.error,
                modifier = Modifier.heightIn(min = 64.dp).clickable(role = Role.Button, onClickLabel = "이 칸 기록 모두 지우기") { confirmDeleteAll = true }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            )
        }
        Box(
            Modifier.size(64.dp).clickable(role = Role.Button, onClickLabel = "닫기", onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { Text("✕", style = type.placeName, color = colors.textSub) }
    }
    // 지도 사진 마커와 같은 장소: 가장 최근 방문 카드 → 기록 상세.
    if (showLatest) records.maxByOrNull { it.visitedAt }?.let { latest ->
        LatestVisitCard(c, latest, records.size) { onOpenVisit(latest.id) }
        SecondaryButton("방문 기록 보기  →", Modifier.fillMaxWidth(), height = 72.dp) { onOpenVisit(latest.id) }
    }
    Text(
        if (records.isEmpty()) "아직 다녀온 기록이 없어요" else "${records.size}번 다녀왔어요",
        style = type.body, color = if (records.isEmpty()) colors.textSub else colors.count,
    )
    records.forEach { v ->
        Row(
            Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(18.dp)).border(1.dp, colors.outline, RoundedCornerShape(18.dp)).padding(start = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 14.dp)) {
                Text(formatDate(v.visitedAt), style = type.placeName, color = colors.text)
                val detail = if (v.manual) "직접 체크" else listOfNotNull(
                    v.distanceKm?.let { "%.0f km".format(it) }, v.durationMinutes?.let { "${it}분" },
                ).joinToString(" · ").ifEmpty { "여행 기록" }
                Text(detail, style = type.placeAddress, color = colors.textSub)
            }
            Box(
                Modifier.size(72.dp).clickable(role = Role.Button, onClickLabel = "이 기록 지우기") {
                    scope.launch { c.visits.delete(v.id) }
                }.semantics { contentDescription = "${formatDate(v.visitedAt)} 기록 지우기" },
                contentAlignment = Alignment.Center,
            ) { Text("✕", style = type.placeName, color = colors.textSub) }
        }
    }
    if (PilotScope.isActive(id)) {
        PrimaryButton("다녀왔어요 · 직접 체크", Modifier.fillMaxWidth()) { scope.launch { c.visits.checkManually(id) } }
    }

    if (records.isNotEmpty()) {
        HorizontalDivider(color = colors.divider)
        Text("이 칸 표시", style = type.placeName, color = colors.text)
        MarkPicker(current = marks[id], default = settings.defaultMark) { style ->
            scope.launch { c.visits.setMark(id, style) }
        }
    }

    if (confirmDeleteAll) {
        ConfirmDialog(
            title = "${region?.name ?: "이 칸"} 기록을 모두 지울까요?",
            message = "방문 ${records.size}건과 칸 표시가 지워지고 되돌릴 수 없어요.",
            confirm = "모두 지우기",
            onConfirm = { scope.launch { c.visits.deleteRegion(id) } },
            onDismiss = { confirmDeleteAll = false },
        )
    }
}

/**
 * 표시 고르기: 기본(설정 따름) / 무늬 / 색 6개 / 이모지 8개. current == null 이면 기본.
 * 설정 화면의 "기본 표시"에서도 쓴다(allowDefault = false).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MarkPicker(current: MarkStyle?, default: MarkStyle, allowDefault: Boolean = true, onPick: (MarkStyle?) -> Unit) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (allowDefault) {
            ChipTab("기본", current == null, Modifier.size(width = 110.dp, height = 64.dp)) { onPick(null) }
        }
        ChipTab("무늬", (current ?: if (allowDefault) null else default) == MarkStyle.Pattern, Modifier.size(width = 110.dp, height = 64.dp)) {
            onPick(MarkStyle.Pattern)
        }
        MarkPalette.forEachIndexed { i, color ->
            val on = current == MarkStyle.Tint(i)
            Box(
                Modifier
                    .size(64.dp)
                    .background(color, CircleShape)
                    .border(if (on) 4.dp else 1.dp, if (on) colors.text else colors.outline, CircleShape)
                    .clickable(role = Role.RadioButton) { onPick(MarkStyle.Tint(i)) }
                    .semantics { selected = on; contentDescription = "색 ${i + 1}" },
            )
        }
        MarkStyle.EMOJIS.forEach { e ->
            val on = current == MarkStyle.Emoji(e)
            Box(
                Modifier
                    .size(64.dp)
                    .background(if (on) colors.waypointRow else colors.window, RoundedCornerShape(14.dp))
                    .border(if (on) 3.dp else 1.dp, if (on) colors.primary else colors.divider, RoundedCornerShape(14.dp))
                    .clickable(role = Role.RadioButton) { onPick(MarkStyle.Emoji(e)) }
                    .semantics { selected = on },
                contentAlignment = Alignment.Center,
            ) { Text(e, style = type.placeName) }
        }
    }
}

/** 내 지도 아래 방문 카드(시안): 간 장소 사진, 구 이름, 장소, 날짜 · 횟수, ›. 누르면 기록 상세. */
@Composable
private fun LatestVisitCard(c: AppContainer, v: com.team.travelmap.visit.VisitEntity, count: Int, onOpen: () -> Unit) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    val region = remember(v.regionCode) { c.regions.find(RegionId(v.regionCode)) }
    val photo = rememberVisitPhoto(c, v)
    Row(
        Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(20.dp)).border(1.dp, colors.outline, RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClickLabel = "${v.placeName ?: region?.name} 기록 자세히", onClick = onOpen).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(width = 150.dp, height = 110.dp).background(colors.surface2, RoundedCornerShape(14.dp))
                .then(Modifier.padding(0.dp)),
            contentAlignment = Alignment.Center,
        ) {
            when (photo) {
                is PhotoState.Ready -> androidx.compose.foundation.Image(
                    photo.photo.bitmap, contentDescription = null,
                    contentScale = if (photo.photo.crop) androidx.compose.ui.layout.ContentScale.Crop else androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)),
                )
                PhotoState.Loading -> Text("불러오는 중", style = type.placeAddress, color = colors.textSub)
                PhotoState.None -> Text("사진 없음", style = type.placeAddress, color = colors.textSub)
            }
        }
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f)) {
            Text(region?.name ?: v.regionCode, style = type.panelTitle, color = colors.text, maxLines = 1)
            Text(v.placeName ?: "간 장소 기록 없음", style = type.body, color = if (v.placeName == null) colors.textSub else colors.text, maxLines = 1)
            Text("${java.text.SimpleDateFormat("M.d", java.util.Locale.KOREA).format(java.util.Date(v.visitedAt))} 방문 · ${count}회", style = type.placeAddress, color = colors.textSub)
        }
        Text("›", style = type.panelTitle, color = colors.textSub, modifier = Modifier.padding(horizontal = 8.dp))
    }
}
