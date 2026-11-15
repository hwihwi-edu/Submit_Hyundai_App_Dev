package com.team.travelmap.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.core.model.RegionId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 담당: 1번(지도·화면). 시·군·구 경계를 그리고, 다녀온 곳을 색으로 채운다.
 * 화면 기준: design/chaum-map-preview.html (Codex 시안).
 *
 * 색 규칙 (프로토타입)
 *  - 시범 지역(서울·대전·부산, PilotScope) : 흰색, 선택 가능
 *  - 그 외 지역                             : 회색, 비활성(선택 불가)
 *  - 다녀온 시범 지역                        : visitedColor (사진 채우기는 TODO)
 *  - 지금 고른 여행지                        : highlightColor
 *
 * showLabels = true(도시 확대)면 활성 구·군 이름을 전부 구역 안에 짙은 글자로 놓는다([DistrictLabelLayout]).
 * 이름 글자와 구역 면 어느 쪽을 눌러도 같은 지역이 선택된다. onRegionTap == null 이면(주행 중 등) 선택이 잠긴다.
 *
 * TODO(1번)
 *  - 사진 채우기: 방문 사진을 해당 다각형 Path 로 clip 해서 그리기 (drawImage + clipPath)
 *  - 도시별 달성률(예: 서울 3/25) 표시, 도시 완주 배지
 */
@Composable
fun RegionMapView(
    shapes: List<RegionShape>,
    visited: Set<RegionId>,
    highlighted: RegionId?,
    isActive: (RegionId) -> Boolean,
    onRegionTap: ((RegionId) -> Unit)?,
    modifier: Modifier = Modifier,
    showLabels: Boolean = false,
    title: String? = null,
    colors: MapColors = MapColors(),
    /** 구·군 이름 기본 크기(sp). 가이드: 분할 24, 가로 28. 겹치면 겹치지 않을 때까지 줄인다(최소 12sp). */
    labelBaseSp: Float = 24f,
    /** 시·도 코드 → 방문 구역을 채울 무늬 이미지(Codex visited-*.png). 없으면 방문 단색. */
    visitedPattern: (String) -> ImageBitmap? = { null },
    /** 이름·배지 글꼴(앱은 Asta Sans). null 이면 기본 글꼴. */
    fontFamily: FontFamily? = null,
    /** 칸별 방문 색(내 지도에서 색·이모지를 고른 칸). 여기 있는 칸은 무늬 대신 이 색으로 칠한다. */
    regionFills: Map<RegionId, Color> = emptyMap(),
    /** 칸 이름 앞뒤에 붙일 글자(예: 이모지 "🌊 ", 방문 횟수 " ×2"). */
    labelDecor: Map<RegionId, Pair<String, String>> = emptyMap(),
    /** 장소 위치 핀(위도, 경도). 장소 상세·주변 추천에서 쓴다. */
    markers: List<GeoPoint> = emptyList(),
    /** 지점 위 작은 배지(전국 보기에서 방문 칸 이모지). 칸이 작아 이름표를 못 다는 축척에서 쓴다. */
    pointBadges: List<Pair<GeoPoint, String>> = emptyList(),
    /** true 면 칸 밖을 눌러도 가장 가까운 활성 칸(화면 120dp 안)을 고른다. 칸이 아주 작은 전국 보기용. */
    nearestOnMiss: Boolean = false,
    /** 방문 사진 마커(사진 + 바로 아래 구 이름, 항상 표시). 누르면 [onMarkerTap]. */
    photoMarkers: List<PhotoMarker> = emptyList(),
    onMarkerTap: ((RegionId) -> Unit)? = null,
    /** 마커 테두리·체크 색(앱 초록). */
    markerColor: Color = Color(0xFF2F6B4F),
    /** 전국 보기의 시·도 이름(누를 수 없음, 선택 규칙은 그대로). 사진 마커·서로를 피해 둔다. */
    areaLabels: List<AreaLabel> = emptyList(),
) {
    val density = LocalDensity.current
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val padPx = with(density) { 16.dp.toPx() }
    val bounds = remember(shapes) { Bounds.of(shapes) }
    val proj = remember(bounds, canvasSize, padPx) {
        if (canvasSize == IntSize.Zero) null
        else Projection(bounds, Size(canvasSize.width.toFloat(), canvasSize.height.toFloat()), padPx)
    }
    // 투영한 화면 좌표 고리. 그리기와 터치 판정이 같은 좌표를 쓴다.
    val screen = remember(shapes, proj) {
        if (proj == null) emptyList()
        else shapes.map { s -> s to s.polygons.map { ring -> proj.toScreen(ring) } }
    }
    val paths = remember(screen) {
        screen.map { (s, rings) ->
            s to Path().apply {
                fillType = PathFillType.EvenOdd
                rings.forEach { r ->
                    for (i in 0 until r.size / 2) {
                        if (i == 0) moveTo(r[0], r[1]) else lineTo(r[2 * i], r[2 * i + 1])
                    }
                    close()
                }
            }
        }.sortedBy { (s, _) -> isActive(s.region.id) } // 비활성 지역을 먼저 그려 활성 경계선이 위에 오게 한다
    }
    val selectable = remember(screen) { screen.filter { (s, _) -> isActive(s.region.id) } }
    // 시·도별 방문 구역 묶음의 화면 사각형(무늬 centerCrop 기준)
    val visitedRects = remember(selectable, visited) {
        selectable.filter { (s, _) -> s.region.id in visited }
            .groupBy({ (s, _) -> s.region.id.sidoCode }, { (_, rings) -> rings })
            .mapValues { (_, ringsList) ->
                var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
                ringsList.flatten().forEach { ring ->
                    for (i in 0 until ring.size / 2) {
                        l = minOf(l, ring[2 * i]); r = maxOf(r, ring[2 * i])
                        t = minOf(t, ring[2 * i + 1]); b = maxOf(b, ring[2 * i + 1])
                    }
                }
                Rect(l, t, r, b)
            }
    }
    val tap by rememberUpdatedState(onRegionTap)

    // ---- 사진 마커 배치 ----
    // 마커 상자(사진 + 이름표)를 앵커(간 장소) 주변 후보 자리 중 화면 안·다른 마커와 안 겹치고·다른 구 가운데를 덜 가리는 곳에 둔다.
    // 구 이름 배치는 이 상자를 피해서 한다(reserved). 앵커는 그대로 두고 짧은 연결선으로 잇는다.
    val markerPhotoPx = with(density) { (if (showLabels) 104.dp else 80.dp).toPx() }
    val markerLabelPx = with(density) { (if (showLabels) 44.dp else 38.dp).toPx() }
    val placedMarkers = remember(photoMarkers, proj, selectable, showLabels, title, areaLabels) {
        if (proj == null || photoMarkers.isEmpty()) emptyList() else {
            val w = canvasSize.width.toFloat(); val h = canvasSize.height.toFloat()
            val bw = markerPhotoPx + with(density) { 16.dp.toPx() }
            val bh = markerPhotoPx + markerLabelPx + with(density) { 6.dp.toPx() }
            val gap = with(density) { 22.dp.toPx() }
            val edge = with(density) { 8.dp.toPx() }
            val titleBox = title?.let { with(density) { Rect(0f, 0f, 200.dp.toPx(), 70.dp.toPx()) } }
            val centers = selectable.map { (s, _) -> s.region.id to proj.toScreen(s.region.center) }
            // 전국: 시·도 이름 자리도 가리지 않게(이름이 밀려나 빠지지 않도록)
            val areaSpots = areaLabels.map { proj.toScreen(it.anchor) }
            val placed = ArrayList<PlacedMarker>()
            photoMarkers.sortedBy { proj.toScreen(it.anchor).y }.forEach { m ->
                val a = proj.toScreen(m.anchor)
                val cands = listOf(
                    Offset(a.x, a.y - bh / 2 - gap), Offset(a.x - bw / 2 - gap, a.y - bh / 2 - gap / 2), Offset(a.x + bw / 2 + gap, a.y - bh / 2 - gap / 2),
                    Offset(a.x - bw / 2 - gap, a.y), Offset(a.x + bw / 2 + gap, a.y), Offset(a.x, a.y + bh / 2 + gap),
                    Offset(bw / 2 + edge, a.y), Offset(w - bw / 2 - edge, a.y), // 지도 밖 여백(왼쪽·오른쪽 끝)
                )
                val best = cands.map { c0 ->
                    val c = Offset(c0.x.coerceIn(bw / 2 + edge, w - bw / 2 - edge), c0.y.coerceIn(bh / 2 + edge, h - bh / 2 - edge))
                    val r = Rect(c.x - bw / 2, c.y - bh / 2, c.x + bw / 2, c.y + bh / 2)
                    var penalty = 0f
                    if (placed.any { it.box.overlaps(r) }) penalty += 10_000f
                    if (titleBox != null && titleBox.overlaps(r)) penalty += 5_000f
                    if (r.contains(a)) penalty += 3_000f // 앵커 점을 가리지 않게
                    // 다른 구 가운데(이름 자리)를 가리면 감점, 자기 구 가운데도 조금 감점
                    centers.forEach { (id, p) -> if (r.contains(p)) penalty += if (id == m.id) 400f else 1_500f }
                    areaSpots.forEach { p -> if (r.inflate(with(density) { 24.dp.toPx() }).contains(p)) penalty += 2_000f }
                    // 도시 확대에서는 구 면을 덜 가리는(지도 밖 여백) 자리를 고른다: 상자 안 9점이 구 안에 있으면 감점.
                    if (showLabels) for (fx in listOf(0.1f, 0.5f, 0.9f)) for (fy in listOf(0.1f, 0.5f, 0.9f)) {
                        val px = r.left + r.width * fx; val py = r.top + r.height * fy
                        if (selectable.any { (_, rings) -> DistrictLabelLayout.inside(px, py, rings) }) penalty += 250f
                    }
                    penalty += (c - a).getDistance() * 0.5f
                    r to penalty
                }.minByOrNull { it.second }!!.first
                placed += PlacedMarker(m, best, a)
            }
            placed
        }
    }

    // ---- 시·도 이름 배치(전국) ----
    val areaMeasurer = rememberTextMeasurer()
    val placedAreas = remember(areaLabels, proj, placedMarkers, fontFamily, pointBadges) {
        if (proj == null || areaLabels.isEmpty()) emptyList() else {
            val w = canvasSize.width.toFloat(); val h = canvasSize.height.toFloat()
            // 마커 상자, 앵커 점, 연결선 가운데를 피한다.
            val dot = with(density) { 9.dp.toPx() }
            val taken = (placedMarkers.map { it.box } + placedMarkers.flatMap { pm ->
                val start = Offset(pm.anchor.x.coerceIn(pm.box.left, pm.box.right), pm.anchor.y.coerceIn(pm.box.top, pm.box.bottom))
                listOf(0f, 0.33f, 0.66f, 1f).map { t ->
                    val q = start + (pm.anchor - start) * t
                    Rect(q.x - dot, q.y - dot, q.x + dot, q.y + dot)
                }
            } + pointBadges.map { (pt, _) ->
                // 방문 이모지 배지(34dp)도 피한다
                val q = proj.toScreen(pt); val r0 = with(density) { 19.dp.toPx() }
                Rect(q.x - r0, q.y - r0, q.x + r0, q.y + r0)
            }).toMutableList()
            val out = ArrayList<Pair<AreaLabel, Rect>>()
            areaLabels.forEach { a ->
                val m = areaMeasurer.measure(a.text, TextStyle(fontFamily = fontFamily, fontSize = a.sizeSp.sp, fontWeight = FontWeight.Bold, lineHeight = 1.1.em, textAlign = TextAlign.Center))
                val bw = m.size.width.toFloat() + 8f; val bh = m.size.height.toFloat() + 4f
                val o = proj.toScreen(a.anchor)
                // 이름 자리에서 가까운 순서로 둘레를 넓혀 가며 빈 곳을 찾는다.
                val cands = buildList {
                    add(Offset(0f, 0f))
                    for (k in listOf(0.8f, 1.3f, 1.8f)) {
                        add(Offset(0f, -bh * k)); add(Offset(0f, bh * k)); add(Offset(-bw * 0.65f * k, 0f)); add(Offset(bw * 0.65f * k, 0f))
                        add(Offset(-bw * 0.55f * k, -bh * k)); add(Offset(bw * 0.55f * k, -bh * k)); add(Offset(-bw * 0.55f * k, bh * k)); add(Offset(bw * 0.55f * k, bh * k))
                    }
                }
                val rect = cands.map { d ->
                    val cx = (o.x + d.x).coerceIn(bw / 2, w - bw / 2); val cy = (o.y + d.y).coerceIn(bh / 2, h - bh / 2)
                    Rect(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
                }.firstOrNull { r -> taken.none { it.overlaps(r) } }
                if (rect != null) { taken += rect; out += a to rect } // 끝내 자리가 없으면(아주 좁은 창) 그 이름만 생략
            }
            out
        }
    }

    // ---- 구·군 이름 배치 ----
    val measurer = rememberTextMeasurer()
    val labelStyle = remember(colors, fontFamily) {
        TextStyle(
            fontFamily = fontFamily,
            color = colors.label,
            fontWeight = FontWeight.Bold,
            lineHeight = 1.08.em,
            lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            textAlign = TextAlign.Center,
        )
    }
    val badgeStyle = TextStyle(fontFamily = fontFamily, color = colors.badgeText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
    var labels by remember { mutableStateOf<List<DistrictLabelLayout.Placed<RegionShape>>>(emptyList()) }
    LaunchedEffect(showLabels, selectable, title, labelDecor, placedMarkers, photoMarkers.isEmpty()) {
        labels = emptyList() // 이전 도시·크기의 배치를 남기지 않는다
        if (!showLabels || selectable.isEmpty()) return@LaunchedEffect
        val extra = with(density) { 4.dp.toPx() } // 시안: 글자 크기 + 4px
        // 후보 글자 크기: candidateTexts 는 12/11sp 기준이므로 비율로 키운다.
        fun districtsAt(scale: Float) = selectable.map { (s, rings) ->
            val c = proj!!.toScreen(s.region.center)
            DistrictLabelLayout.District(
                key = s,
                rings = rings,
                centerX = c.x,
                centerY = c.y,
                // 사진 마커가 있는 내 지도에서는 이름을 항상 가로 한 줄로(Codex 지도 시안).
                formats = DistrictLabelLayout.candidateTexts(s.region.name).filter { photoMarkers.isEmpty() || '\n' !in it.first }.map { (name, baseSp) ->
                    val (pre, post) = labelDecor[s.region.id] ?: ("" to "")
                    val text = pre + name + post
                    val sp = baseSp * scale
                    val m = measurer.measure(text, labelStyle.copy(fontSize = sp.sp), softWrap = false)
                    DistrictLabelLayout.Format(text, sp, m.size.width + extra, m.size.height + extra)
                },
            )
        }
        // 왼쪽 위 도시 이름 배지 자리는 비워 둔다.
        val reserved = title?.let {
            val m = measurer.measure(it, badgeStyle)
            with(density) {
                val w = maxOf(m.size.width + 40.dp.toPx(), 116.dp.toPx()); val h = m.size.height + 12.dp.toPx()
                listOf(DistrictLabelLayout.Box(20.dp.toPx() + w / 2, 20.dp.toPx() + h / 2, w, h))
            }
        }.orEmpty() + placedMarkers.map { pm -> DistrictLabelLayout.Box(pm.box.center.x, pm.box.center.y, pm.box.width, pm.box.height) }
        val w = canvasSize.width.toFloat(); val h = canvasSize.height.toFloat()
        val margin = with(density) { 3.dp.toPx() }
        // 가이드 크기(labelBaseSp)부터 시작해 모든 이름이 겹치지 않는 가장 큰 크기를 고른다. 최소 12sp.
        var scale = labelBaseSp / 12f
        var result: List<DistrictLabelLayout.Placed<RegionShape>>
        while (true) {
            val districts = districtsAt(scale)
            result = withContext(Dispatchers.Default) { DistrictLabelLayout.layout(districts, w, h, reserved, margin) }
            if (result.all { it.placedFreely } || scale <= 1f) break
            scale = maxOf(1f, scale * 0.9f)
        }
        labels = result
    }

    Box(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(colors.mapBackground)
            .onSizeChanged { canvasSize = it },
    ) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(selectable, proj, nearestOnMiss) {
                detectTapGestures { o ->
                    val t = tap ?: return@detectTapGestures
                    val hit = selectable.firstOrNull { (_, rings) -> DistrictLabelLayout.inside(o.x, o.y, rings) }?.first
                    val near = if (hit != null || !nearestOnMiss || proj == null) null else {
                        val maxPx = with(density) { 120.dp.toPx() }
                        selectable.map { (s, _) -> s to (proj.toScreen(s.region.center) - o).getDistance() }
                            .filter { it.second <= maxPx }.minByOrNull { it.second }?.first
                    }
                    (hit ?: near)?.let { t(it.region.id) } // 비활성(회색) 지역은 눌러도 반응 없음
                }
            },
        ) {
            drawRect(colors.mapBackground)
            paths.forEach { (shape, path) ->
                val id = shape.region.id
                val active = isActive(id)
                val fill = when {
                    !active -> colors.inactive
                    id == highlighted -> colors.highlight
                    id in visited -> regionFills[id] ?: colors.visited
                    else -> colors.active
                }
                drawPath(path, fill)
                // 이웃 구 경계가 단순화로 살짝 벌어진 곳이 바탕색으로 비치지 않게 같은 색 가는 선으로 메운다.
                if (active) drawPath(path, fill, style = Stroke(width = 1.2.dp.toPx()))
                // 방문 구역: 도시별 무늬를 방문 구역 전체 묶음에 centerCrop 으로 맞춘 뒤 구역 모양으로 잘라 그린다.
                if (active && id in visited && id != highlighted && id !in regionFills) {
                    visitedPattern(id.sidoCode)?.let { img ->
                        visitedRects[id.sidoCode]?.let { r -> clipPath(path) { drawCenterCrop(img, r) } }
                    }
                }
            }
            // 외곽선은 면을 다 칠한 뒤 그려 선택·방문 외곽선이 이웃 면에 가려지지 않게 한다.
            paths.forEach { (shape, path) ->
                val id = shape.region.id
                val active = isActive(id)
                val (stroke, width) = when {
                    !active -> colors.inactiveBorder to 0.55f
                    id == highlighted -> colors.highlightBorder to 2.5f
                    id in visited -> colors.visitedBorder to 2f
                    else -> colors.activeBorder to 1f
                }
                drawPath(path, stroke, style = Stroke(width = width.dp.toPx()))
            }
            paths.filter { (s, _) -> s.region.id == highlighted || s.region.id in visited && isActive(s.region.id) }
                .forEach { (shape, path) ->
                    val id = shape.region.id
                    val (stroke, width) = if (id == highlighted) colors.highlightBorder to 2.5f else colors.visitedBorder to 2f
                    drawPath(path, stroke, style = Stroke(width = width.dp.toPx()))
                }
        }

        labels.forEach { placed ->
            val id = placed.key.region.id
            val box = placed.box
            val locked = onRegionTap == null
            Box(
                Modifier
                    .offset { IntOffset((box.x - box.w / 2).roundToInt(), (box.y - box.h / 2).roundToInt()) }
                    .size(with(density) { box.w.toDp() }, with(density) { box.h.toDp() })
                    .clickable(enabled = !locked, role = Role.Button, onClickLabel = "${placed.key.region.name} 선택") {
                        tap?.invoke(id)
                    }
                    .semantics {
                        contentDescription = placed.key.region.fullName
                        selected = id == highlighted
                        if (locked) disabled()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    placed.format.text,
                    style = labelStyle.copy(
                        fontSize = placed.format.fontSizeSp.sp,
                        shadow = if (placed.needsHalo) Shadow(colors.active, blurRadius = 4f) else null,
                    ),
                    softWrap = false,
                )
            }
        }

        if (proj != null && markers.isNotEmpty()) {
            Canvas(Modifier.fillMaxSize()) {
                markers.forEach { p ->
                    val o = proj.toScreen(p)
                    drawCircle(colors.badge, 15.dp.toPx(), o)
                    drawCircle(colors.visitedBorder, 11.dp.toPx(), o)
                    drawCircle(colors.badge, 4.dp.toPx(), o)
                }
            }
        }

        if (proj != null) {
            pointBadges.forEach { (p, text) ->
                val o = proj.toScreen(p)
                val half = with(density) { 17.dp.toPx() }
                Box(
                    Modifier
                        .offset { IntOffset((o.x - half).roundToInt(), (o.y - half).roundToInt()) }
                        .size(34.dp)
                        .background(colors.badge, androidx.compose.foundation.shape.CircleShape)
                        .border(1.5.dp, colors.visitedBorder, androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center,
                ) { Text(text, fontSize = 18.sp) }
            }
        }

        // 시·도 이름(전국): 지도 위에 굵게, 흰 테두리 그림자로 읽기 쉽게. 누르는 것은 아래 지도가 받는다.
        placedAreas.forEach { (a, r) ->
            Text(
                a.text,
                style = TextStyle(
                    fontFamily = fontFamily, fontSize = a.sizeSp.sp, fontWeight = FontWeight.Bold, lineHeight = 1.1.em,
                    textAlign = TextAlign.Center, color = if (a.active) colors.label else colors.label.copy(alpha = 0.78f),
                    shadow = Shadow(Color.White, blurRadius = 6f),
                ),
                modifier = Modifier.offset { IntOffset(r.left.roundToInt(), r.top.roundToInt()) }
                    .size(with(density) { r.width.toDp() }, with(density) { r.height.toDp() }),
            )
        }

        if (placedMarkers.isNotEmpty()) {
            // 연결선 + 앵커 점
            Canvas(Modifier.fillMaxSize()) {
                placedMarkers.forEach { pm ->
                    val to = pm.anchor
                    // 앵커에서 가장 가까운 카드 테두리 점에서 선을 시작한다.
                    val start = Offset(to.x.coerceIn(pm.box.left, pm.box.right), to.y.coerceIn(pm.box.top, pm.box.bottom))
                    drawLine(markerColor, start, to, strokeWidth = 2.dp.toPx())
                    drawCircle(Color.White, 7.dp.toPx(), to)
                    drawCircle(markerColor, 5.dp.toPx(), to)
                }
            }
            placedMarkers.forEach { pm ->
                MarkerCard(pm, markerPhotoPx, markerLabelPx, markerColor, fontFamily, onMarkerTap ?: onRegionTap)
            }
        }

        if (title != null) {
            Text(
                title,
                style = badgeStyle,
                modifier = Modifier
                    .offset(20.dp, 20.dp)
                    .background(colors.badge, RoundedCornerShape(12.dp))
                    .padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
    }
}

/** 지도 색. 기본값은 Codex 주간 토큰. 화면에서는 ui.ChaumColors 로 주간/야간을 넘긴다. */
data class MapColors(
    val active: Color = Color(0xFFFFFFFF),
    val inactive: Color = Color(0xFFA0ADA9),
    val visited: Color = Color(0xFF7FCAA7),
    val highlight: Color = Color(0xFFF6CB86),
    val activeBorder: Color = Color(0xFF687E74),
    val inactiveBorder: Color = Color(0xFF8C9995),
    val highlightBorder: Color = Color(0xFF946023),
    val visitedBorder: Color = Color(0xFF165B46),
    val label: Color = Color(0xFF172621),
    val mapBackground: Color = Color(0xFFDCE8E5),
    val badge: Color = Color(0xFFFFFFFF),
    val badgeText: Color = Color(0xFF172621),
)

/** 이미지를 사각형 r 에 비율 유지로 꽉 채운다(넘치는 쪽은 가운데 기준으로 잘림). */
private fun DrawScope.drawCenterCrop(img: ImageBitmap, r: Rect) {
    if (r.width <= 0f || r.height <= 0f) return
    val iw = img.width.toFloat(); val ih = img.height.toFloat()
    val scale = maxOf(r.width / iw, r.height / ih)
    val sw = r.width / scale; val sh = r.height / scale
    val sx = (iw - sw) / 2; val sy = (ih - sh) / 2
    drawImage(
        img,
        srcOffset = IntOffset(sx.roundToInt(), sy.roundToInt()),
        srcSize = IntSize(sw.roundToInt().coerceAtLeast(1), sh.roundToInt().coerceAtLeast(1)),
        dstOffset = IntOffset(r.left.roundToInt(), r.top.roundToInt()),
        dstSize = IntSize(r.width.roundToInt(), r.height.roundToInt()),
    )
}

private data class Bounds(val minLat: Double, val maxLat: Double, val minLng: Double, val maxLng: Double) {
    companion object {
        fun of(shapes: List<RegionShape>): Bounds {
            val pts = shapes.flatMap { s -> s.polygons.flatten() }
            if (pts.isEmpty()) return Bounds(33.0, 38.7, 124.5, 131.0)
            return Bounds(pts.minOf { it.latitude }, pts.maxOf { it.latitude },
                pts.minOf { it.longitude }, pts.maxOf { it.longitude })
        }
    }
}

/** 단순 등장방형 투영(위도에 따라 경도 폭 보정). 한반도 크기에서는 충분하다. pad = 가장자리 여백(px). */
private class Projection(private val b: Bounds, size: Size, pad: Float) {
    private val kx = cos(Math.toRadians((b.minLat + b.maxLat) / 2))
    private val w = (b.maxLng - b.minLng) * kx
    private val h = b.maxLat - b.minLat
    private val scale = min((size.width - 2 * pad) / w, (size.height - 2 * pad) / h)
    private val ox = (size.width - w * scale) / 2
    private val oy = (size.height - h * scale) / 2

    fun toScreen(p: GeoPoint) = Offset(
        (ox + (p.longitude - b.minLng) * kx * scale).toFloat(),
        (oy + (b.maxLat - p.latitude) * scale).toFloat(),
    )

    fun toScreen(ring: List<GeoPoint>): FloatArray {
        val out = FloatArray(ring.size * 2)
        ring.forEachIndexed { i, p ->
            out[2 * i] = (ox + (p.longitude - b.minLng) * kx * scale).toFloat()
            out[2 * i + 1] = (oy + (b.maxLat - p.latitude) * scale).toFloat()
        }
        return out
    }
}

/** 지도 위 방문 사진 마커. image == null 이면 사진 대신 그림 기호 + 이름. */
data class PhotoMarker(
    val id: RegionId,
    val anchor: GeoPoint,
    val label: String,
    val image: ImageBitmap?,
    /** false 면 공공누리 3유형 등 변경 금지 사진: 자르지 않고 맞춰 보인다. */
    val crop: Boolean = true,
    val selected: Boolean = false,
)

private class PlacedMarker(val marker: PhotoMarker, val box: Rect, val anchor: Offset)

@Composable
private fun MarkerCard(pm: PlacedMarker, photoPx: Float, labelPx: Float, green: Color, fontFamily: FontFamily?, onTap: ((RegionId) -> Unit)?) {
    val density = LocalDensity.current
    val m = pm.marker
    val photoDp = with(density) { photoPx.toDp() }
    val labelDp = with(density) { labelPx.toDp() }
    val border = if (m.selected) 4.dp else 2.5.dp
    androidx.compose.foundation.layout.Column(
        Modifier
            .offset { IntOffset(pm.box.left.roundToInt(), pm.box.top.roundToInt()) }
            .size(with(density) { pm.box.width.toDp() }, with(density) { pm.box.height.toDp() })
            .clickable(enabled = onTap != null, role = Role.Button, onClickLabel = "${m.label} 방문 기록") { onTap?.invoke(m.id) }
            .semantics { contentDescription = "${m.label} 방문 사진"; selected = m.selected },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(photoDp + 8.dp)) {
            Box(
                Modifier.align(Alignment.BottomStart).size(photoDp)
                    .clip(RoundedCornerShape(14.dp)).background(Color(0xFFEEF3EC))
                    .border(border, green, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                if (m.image != null) androidx.compose.foundation.Image(
                    m.image, contentDescription = null,
                    contentScale = if (m.crop) androidx.compose.ui.layout.ContentScale.Crop else androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().padding(border),
                ) else androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Outlined.Image, contentDescription = null, tint = green,
                    modifier = Modifier.size(photoDp * 0.5f),
                )
            }
            // 오른쪽 위 방문 체크
            Box(
                Modifier.align(Alignment.TopEnd).size(26.dp).background(green, androidx.compose.foundation.shape.CircleShape)
                    .border(2.dp, Color.White, androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(16.dp)) }
        }
        // 사진 바로 아래 구 이름(항상)
        Text(
            m.label,
            style = TextStyle(fontFamily = fontFamily, fontWeight = FontWeight.Bold, fontSize = (labelPx / density.density * 0.45f).sp, color = Color(0xFF24302C)),
            maxLines = 1, softWrap = false,
            modifier = Modifier.offset(y = (-6).dp).height(labelDp - 6.dp)
                .background(Color.White, RoundedCornerShape(12.dp)).border(if (m.selected) 2.5.dp else 1.5.dp, green, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 2.dp),
        )
    }
}

/** 전국 지도의 시·도 이름. anchor = 그 시·도 안 이름 자리(위도, 경도). active = 시범 지역(진한 글자). */
data class AreaLabel(val anchor: GeoPoint, val text: String, val sizeSp: Float, val active: Boolean)
