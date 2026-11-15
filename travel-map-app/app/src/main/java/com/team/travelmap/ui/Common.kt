package com.team.travelmap.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.imageResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.team.travelmap.AppContainer
import com.team.travelmap.R
import com.team.travelmap.core.model.PilotScope
import com.team.travelmap.core.model.Region
import com.team.travelmap.core.model.RegionId
import com.team.travelmap.map.MapColors
import com.team.travelmap.map.RegionMapView
import com.team.travelmap.settings.MarkStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 담당: 1번(지도·화면). 탭 화면들이 같이 쓰는 조각. */

internal val mapViews = listOf(null to "전국", "11" to "서울", "30" to "대전", "26" to "부산")
internal val cityShort = mapOf("11" to "서울", "30" to "대전", "26" to "부산")

/** 시범 도시별 구·군 수. 완주 배지 판정에 쓴다. */
internal val cityTotals = mapOf("11" to 25, "30" to 5, "26" to 16)

internal val Region.shortName: String get() = "${cityShort[id.sidoCode] ?: sidoName} $name"

/** 목적격 조사: 마지막 글자에 받침이 있으면 "을", 없으면 "를" (예: 기장군을, 중구를). */
internal fun String.objectParticle(): String {
    val last = lastOrNull() ?: return "를"
    if (last !in '가'..'힣') return "를"
    return if ((last - '가') % 28 != 0) "을" else "를"
}

private val dateFormat = SimpleDateFormat("yyyy년 M월 d일 (E)", Locale.KOREAN)
internal fun formatDate(millis: Long): String = dateFormat.format(Date(millis))

/** 내 지도 칸 색 팔레트(MarkStyle.Tint index). Codex 낭만 톤 팔레트가 오면 교체. */
internal val MarkPalette = listOf(
    Color(0xFFF2A98F), Color(0xFF9CC9E8), Color(0xFFB9A6E0),
    Color(0xFFE8D67A), Color(0xFF8FD3B4), Color(0xFFE9A6C4),
)

internal fun ChaumColors.toMapColors() = MapColors(
    active = landActive, inactive = landInactive, visited = visitedFill, highlight = selectedFill,
    activeBorder = mapBorder, inactiveBorder = mapBorder.copy(alpha = 0.6f), highlightBorder = selectedBorder,
    visitedBorder = visitedBorder, label = districtLabel, mapBackground = mapBg, badge = card, badgeText = text,
)

/**
 * 설정(지도에 다녀온 곳 표시·방문 횟수·기본 표시)과 칸별 표시를 반영한 지도.
 * 여행 탭과 내 지도 탭이 같이 쓴다.
 */
@Composable
internal fun ChaumMap(
    c: AppContainer,
    focusSido: String?,
    highlighted: RegionId?,
    title: String,
    onRegionTap: ((RegionId) -> Unit)?,
    modifier: Modifier,
    markers: List<com.team.travelmap.core.model.GeoPoint> = emptyList(),
    /** 내 지도: 방문한 구에 실제로 간 장소 사진 마커(사진 아래 구 이름 항상). */
    photoMarkers: Boolean = false,
    selected: RegionId? = null,
    onMarkerTap: ((RegionId) -> Unit)? = null,
) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    val settings by c.settings.state.collectAsStateWithLifecycle()
    val visited by remember { c.visits.visitedRegions }.collectAsStateWithLifecycle(emptySet())
    val counts by remember { c.visits.visitCounts }.collectAsStateWithLifecycle(emptyMap())
    val marks by remember { c.visits.marks }.collectAsStateWithLifecycle(emptyMap())
    val shapes = remember(focusSido) { focusSido?.let { c.regions.cityShapesOf(it) } ?: c.regions.shapes }
    val patterns = mapOf(
        "11" to ImageBitmap.imageResource(R.drawable.visited_seoul),
        "30" to ImageBitmap.imageResource(R.drawable.visited_daejeon),
        "26" to ImageBitmap.imageResource(R.drawable.visited_busan),
    )
    val shown = if (settings.showVisitedOnMap) visited else emptySet()
    // 전국 보기: 칸이 작아 이름표가 없으므로, 이모지를 고른 방문 칸 가운데에 배지를 단다.
    val badges = if (focusSido != null || photoMarkers) emptyList() else shown.mapNotNull { id ->
        val m = marks[id] ?: settings.defaultMark
        if (m is MarkStyle.Emoji) c.regions.find(id)?.let { it.center to m.symbol } else null
    }
    val fills = HashMap<RegionId, Color>()
    val decor = HashMap<RegionId, Pair<String, String>>()
    shown.forEach { id ->
        var pre = ""
        when (val m = marks[id] ?: settings.defaultMark) {
            MarkStyle.Pattern -> Unit
            is MarkStyle.Tint -> fills[id] = MarkPalette.getOrElse(m.index) { colors.visitedFill }
            is MarkStyle.Emoji -> { fills[id] = colors.visitedFill; pre = "${m.symbol} " }
        }
        val n = counts[id] ?: 0
        val post = if (settings.showVisitCount && n >= 2) " ×$n" else ""
        // 사진 마커가 있으면 사진이 표시를 대신하므로 이름 앞뒤 장식(이모지·×n)은 붙이지 않는다(이름 가로 한 줄 유지).
        if (!photoMarkers && (pre.isNotEmpty() || post.isNotEmpty())) decor[id] = pre to post
    }
    // 사진 마커: 보이는 지도 안의 방문 구마다 가장 최근 기록 하나. 사진은 간 장소 사진(없으면 그림 기호).
    val allVisits by remember { c.visits.visits }.collectAsStateWithLifecycle(emptyList())
    val latest = if (!photoMarkers) emptyList() else allVisits
        .filter { RegionId(it.regionCode) in shown && PilotScope.isActive(RegionId(it.regionCode)) && (focusSido == null || it.regionCode.startsWith(focusSido)) }
        .groupBy { it.regionCode }.map { (_, vs) -> vs.maxBy { it.visitedAt } }
    val context = androidx.compose.ui.platform.LocalContext.current
    val photos by androidx.compose.runtime.produceState<Map<Long, VisitPhoto?>>(emptyMap(), latest.map { it.id to it.placeName }) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            latest.associate { v -> v.id to runCatching { visitedPlacePhoto(c, context, v) }.getOrNull() }
        }
    }
    val photoMarkerList = latest.mapNotNull { v ->
        val region = c.regions.find(RegionId(v.regionCode)) ?: return@mapNotNull null
        val anchor = if (v.placeLat != null && v.placeLng != null) com.team.travelmap.core.model.GeoPoint(v.placeLat, v.placeLng) else region.center
        val p = photos[v.id]
        com.team.travelmap.map.PhotoMarker(region.id, anchor, region.name, p?.bitmap, p?.crop ?: true, region.id == selected)
    }
    RegionMapView(
        shapes = shapes,
        visited = shown,
        highlighted = highlighted,
        isActive = PilotScope::isActive,
        onRegionTap = onRegionTap,
        modifier = modifier,
        showLabels = focusSido != null,
        title = title,
        colors = colors.toMapColors(),
        labelBaseSp = type.districtSp,
        visitedPattern = { patterns[it] },
        fontFamily = AstaSans,
        regionFills = fills,
        labelDecor = decor,
        markers = markers,
        pointBadges = badges,
        nearestOnMiss = focusSido == null,
        photoMarkers = photoMarkerList,
        areaLabels = if (focusSido == null) SidoLabels.all(type.districtSp) else emptyList(),
        onMarkerTap = onMarkerTap,
        markerColor = colors.primary,
    )
}

@Composable
internal fun CityTabs(focus: String?, onSelect: (String?) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        mapViews.forEach { (sido, label) ->
            ChipTab(label, focus == sido, Modifier.size(width = 146.dp, height = 66.dp)) { onSelect(sido) }
        }
    }
}

/** 탭 모양 버튼(도시 탭·메뉴 탭·필터 탭 공통). */
@Composable
internal fun ChipTab(label: String, on: Boolean, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    Box(
        modifier
            .background(if (on) colors.selectedBg else colors.card, RoundedCornerShape(16.dp))
            .border(if (on) 2.dp else 1.dp, if (on) colors.primary else colors.outline.copy(alpha = if (enabled) 1f else 0.4f), RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, role = Role.Tab, onClick = onClick)
            .semantics { selected = on },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, style = type.cityTab,
            color = when { on -> colors.primary; enabled -> colors.text; else -> colors.textSub.copy(alpha = 0.5f) },
        )
    }
}

@Composable
internal fun Kicker(text: String) {
    Text(text, style = LocalChaumType.current.label, color = LocalChaumColors.current.primary)
}

@Composable
internal fun Title(text: String) {
    Text(text, style = LocalChaumType.current.panelTitle, color = LocalChaumColors.current.text, maxLines = 2)
}

@Composable
internal fun Body(text: String, sub: Boolean = true) {
    val colors = LocalChaumColors.current
    Text(text, style = LocalChaumType.current.body, color = if (sub) colors.textSub else colors.text)
}

@Composable
internal fun Notice(text: String) {
    Title("정차 후 이용해 주세요")
    Body(text)
}

@Composable
internal fun PrimaryButton(text: String, modifier: Modifier = Modifier, height: Dp = 75.dp, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = LocalChaumColors.current
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = colors.primary, contentColor = colors.onPrimary),
    ) { Text(text, style = LocalChaumType.current.button) }
}

@Composable
internal fun SecondaryButton(text: String, modifier: Modifier = Modifier, height: Dp = 75.dp, onClick: () -> Unit) {
    val colors = LocalChaumColors.current
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.height(height),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, colors.outline),
    ) { Text(text, style = LocalChaumType.current.button, color = colors.text) }
}

/** 지우기처럼 되돌릴 수 없는 동작 전 확인. */
@Composable
internal fun ConfirmDialog(
    title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit,
    /** 두 번째 버튼 글자(기본 "취소"). onDismissAction 을 주면 그 동작을 한다(예: "버리기"). 바깥을 누르면 그냥 닫힌다. */
    dismissLabel: String = "취소", onDismissAction: (() -> Unit)? = null,
) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.card,
        title = { Text(title, style = type.placeName, color = colors.text) },
        text = { Text(message, style = type.body, color = colors.textSub) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }, modifier = Modifier.height(64.dp)) {
                Text(confirm, style = type.button, color = colors.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissAction ?: onDismiss, modifier = Modifier.height(64.dp).padding(end = 8.dp)) {
                Text(dismissLabel, style = type.button, color = colors.text)
            }
        },
    )
}

/** 둥근 카드(흰 바탕 + 얇은 테두리). 마실 확정 디자인의 기본 표면. */
@Composable
internal fun MasilCard(modifier: Modifier = Modifier, fill: androidx.compose.ui.graphics.Color? = null, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    val colors = LocalChaumColors.current
    androidx.compose.foundation.layout.Column(
        modifier
            .background(fill ?: colors.card, RoundedCornerShape(20.dp))
            .border(1.dp, colors.outline, RoundedCornerShape(20.dp))
            .padding(24.dp),
        content = content,
    )
}

/** 작은 알약 표시(예: "7 / 46", "주행 중"). */
@Composable
internal fun Pill(text: String, modifier: Modifier = Modifier) {
    val colors = LocalChaumColors.current
    Text(
        text, style = LocalChaumType.current.label, color = colors.primary,
        modifier = modifier.background(colors.selectedBg, RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 6.dp),
    )
}

/** 초록 토글(확정 시안: 켜짐 = 주색). 줄 전체가 toggleable 이므로 스위치 자체는 표시만 한다. */
@Composable
internal fun MasilSwitch(checked: Boolean) {
    val colors = LocalChaumColors.current
    androidx.compose.material3.Switch(
        checked = checked, onCheckedChange = null,
        colors = androidx.compose.material3.SwitchDefaults.colors(
            checkedTrackColor = colors.primary, checkedThumbColor = colors.onPrimary,
            uncheckedTrackColor = colors.divider, uncheckedThumbColor = colors.card, uncheckedBorderColor = colors.divider,
        ),
    )
}

/**
 * 선택 칸(시안 03): 고르면 연한 초록 면 + 초록 테두리 + 체크 원, 안 고르면 흰 면 + 얇은 테두리 + 빈 원.
 * 다시 누르면 선택 해제. 자동차 터치 크기(높이 72dp 이상).
 */
@Composable
internal fun OptionChip(label: String, on: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    androidx.compose.foundation.layout.Row(
        modifier
            .heightIn(min = 72.dp)
            .background(if (on) colors.selectedBg else colors.card, RoundedCornerShape(16.dp))
            .border(if (on) 2.dp else 1.dp, if (on) colors.primary else colors.outline, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, role = androidx.compose.ui.semantics.Role.RadioButton, onClick = onClick)
            .semantics { selected = on }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        androidx.compose.material3.Icon(
            if (on) androidx.compose.material.icons.Icons.Filled.CheckCircle else androidx.compose.material.icons.Icons.Outlined.Circle,
            contentDescription = null, tint = if (on) colors.primary else colors.outline,
            modifier = Modifier.size(26.dp),
        )
        androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
        Text(label, style = type.placeAddress, color = if (on) colors.primary else colors.text,
            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, maxLines = 2)
    }
}

/**
 * 전국 지도 시·도 이름(regions.json 의 현재 시·도 기준). 자리는 각 시·도 안의 이름 놓기 좋은 지점(경계 데이터가 아니라 글자 위치).
 * 광역시·특별시는 짧게, 도는 정식 이름. 긴 이름은 두 줄.
 */
internal object SidoLabels {
    private data class Row(val code: String, val text: String, val lat: Double, val lng: Double, val metro: Boolean)
    private val rows = listOf(
        Row("11", "서울", 37.57, 126.98, true),
        Row("28", "인천", 37.47, 126.62, true),
        Row("41", "경기도", 37.42, 127.45, false),
        Row("51", "강원\n특별자치도", 37.72, 128.32, false),
        Row("43", "충청북도", 36.78, 127.80, false),
        Row("44", "충청남도", 36.52, 126.80, false),
        Row("36", "세종", 36.58, 127.26, true),
        Row("30", "대전", 36.34, 127.40, true),
        Row("52", "전북\n특별자치도", 35.72, 127.15, false),
        Row("12", "전남광주\n통합특별시", 34.88, 126.92, false),
        Row("47", "경상북도", 36.35, 128.78, false),
        Row("27", "대구", 35.86, 128.58, true),
        Row("48", "경상남도", 35.30, 128.22, false),
        Row("31", "울산", 35.55, 129.28, true),
        Row("26", "부산", 35.17, 129.06, true),
        Row("50", "제주\n특별자치도", 33.38, 126.55, false),
    )

    /** 도 이름은 base, 광역시는 조금 작게. 시범 도시(서울·대전·부산)를 먼저 놓아 자리를 우선 차지하게 한다. */
    fun all(base: Float): List<com.team.travelmap.map.AreaLabel> = rows
        .sortedByDescending { it.code in com.team.travelmap.core.model.PilotScope.ACTIVE_SIDO }
        .map { r ->
            com.team.travelmap.map.AreaLabel(
                com.team.travelmap.core.model.GeoPoint(r.lat, r.lng), r.text,
                if (r.metro) base * 0.95f else base * 1.05f, r.code in com.team.travelmap.core.model.PilotScope.ACTIVE_SIDO,
            )
        }
}
