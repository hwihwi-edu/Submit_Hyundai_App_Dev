package com.team.travelmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.team.travelmap.AppContainer
import com.team.travelmap.core.model.RegionId
import com.team.travelmap.visit.VisitEntity
import com.team.travelmap.visit.parkingAnswer
import com.team.travelmap.visit.waitingAnswer
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.launch

/**
 * 담당: 1번(지도·화면). 기록 탭(시안 04 "사진으로 남은 여행").
 * 가장 최근 기록은 큰 사진 카드, 나머지는 왼쪽 사진 + 오른쪽 글 카드. 사진은 실제로 간 장소의 사진(원본 비율·출처 표시).
 * 아래에 경유지에서 남긴 리뷰(주차·웨이팅·재방문 선택 항목, 별점 없음).
 */
@Composable
internal fun HistoryTab(
    c: AppContainer, wide: Boolean,
    restricted: Boolean = false,
    /** 내 지도 "방문 기록 보기"로 들어올 때 바로 열 기록. */
    openVisitId: Long? = null,
    onOpened: () -> Unit = {},
    onPlanned: () -> Unit = {},
) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    val scope = rememberCoroutineScope()
    val visits by remember { c.visits.visits }.collectAsStateWithLifecycle(emptyList())
    var pendingDelete by remember { mutableStateOf<VisitEntity?>(null) }
    val reviews by remember { c.reviews.reviews }.collectAsStateWithLifecycle(emptyList())
    var pendingReview by remember { mutableStateOf<com.team.travelmap.visit.ReviewEntity?>(null) }
    // 카드를 누르면 기록 상세(Codex history-detail 시안).
    var detailId by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Long?>(null) }
    androidx.compose.runtime.LaunchedEffect(openVisitId) { openVisitId?.let { detailId = it; onOpened() } }
    detailId?.let { id ->
        VisitDetail(c, id, wide, restricted, onBack = { detailId = null }, onPlanned = onPlanned)
        return
    }

    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            Column {
                Title("다시 꺼내 보는 여행")
                Text("${visits.size}번의 여행", style = type.body, color = colors.textSub)
            }
        }
        if (visits.isEmpty()) item { Body("아직 기록이 없어요. 여행을 마치면 여기에 한 장씩 쌓여요.") }
        visits.forEachIndexed { i, v ->
            item(key = "v${v.id}") {
                if (i == 0) VisitHeroCard(c, v, { detailId = v.id }) { pendingDelete = v } else VisitRowCard(c, v, { detailId = v.id }) { pendingDelete = v }
            }
        }
        if (reviews.isNotEmpty()) {
            item { Text("남긴 리뷰 ${reviews.size}", style = type.placeName, color = colors.text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp)) }
            reviews.forEach { r -> item(key = "r${r.id}") { ReviewCard(r) { pendingReview = r } } }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
    pendingReview?.let { r ->
        ConfirmDialog(
            title = "이 리뷰를 지울까요?", message = "${r.placeName} 리뷰가 지워지고 되돌릴 수 없어요.", confirm = "지우기",
            onConfirm = { scope.launch { c.reviews.delete(r.id) } }, onDismiss = { pendingReview = null },
        )
    }
    pendingDelete?.let { v ->
        ConfirmDialog(
            title = "이 기록을 지울까요?",
            message = "${formatDate(v.visitedAt)} 기록이 지워지고 되돌릴 수 없어요.",
            confirm = "지우기",
            onConfirm = { scope.launch { c.visits.delete(v.id) } },
            onDismiss = { pendingDelete = null },
        )
    }
}

private val shortDate = java.text.SimpleDateFormat("M.d", java.util.Locale.KOREA)

/** 카드 글: 제목(간 장소, 모르면 지역), 날짜·지역, 상태 칩. */
private fun visitTexts(c: AppContainer, v: VisitEntity): Triple<String, String, String> {
    val region = c.regions.find(RegionId(v.regionCode))
    val regionName = region?.fullName ?: v.regionCode
    val title = v.placeName ?: regionName
    val sub = listOfNotNull(
        shortDate.format(java.util.Date(v.visitedAt)), regionName,
        v.distanceKm?.let { "%.0f km".format(it) }, v.durationMinutes?.let { "${it}분" },
    ).joinToString(" · ")
    val chip = if (v.manual) "직접 체크" else "방문 완료"
    return Triple(title, sub, chip)
}

/** 사진 칸: 사진(원본 비율 유지) + 왼쪽 아래 "방문 장소 사진" 라벨, 불러오는 중/사진 없음 상태. */
@Composable
private fun VisitPhotoBox(state: PhotoState, fallback: String, modifier: Modifier, showLabel: Boolean) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    Box(modifier.background(colors.surface2, RoundedCornerShape(14.dp)).clip(RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) {
        when (state) {
            PhotoState.Loading -> Text("사진 불러오는 중", style = type.placeAddress, color = colors.textSub)
            PhotoState.None -> Text(fallback, style = type.placeName, color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(8.dp))
            is PhotoState.Ready -> {
                androidx.compose.foundation.Image(
                    state.photo.bitmap, contentDescription = "$fallback 사진",
                    contentScale = if (state.photo.crop) androidx.compose.ui.layout.ContentScale.Crop else androidx.compose.ui.layout.ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(),
                )
                if (showLabel) Text(
                    "방문 장소 사진", style = type.placeAddress, color = androidx.compose.ui.graphics.Color.White,
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
                        .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusChip(text: String) {
    val colors = LocalChaumColors.current
    Row(
        Modifier.background(colors.selectedBg, RoundedCornerShape(50)).border(1.dp, colors.primary.copy(alpha = 0.3f), RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Filled.Check, null, tint = colors.primary, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(4.dp))
        Text(text, style = LocalChaumType.current.placeAddress, color = colors.primary)
    }
}

@Composable
private fun DeleteButton(label: String, onDelete: () -> Unit) {
    Box(
        Modifier.size(64.dp).clickable(role = Role.Button, onClickLabel = label, onClick = onDelete),
        contentAlignment = Alignment.Center,
    ) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.Delete, contentDescription = "지우기", tint = LocalChaumColors.current.textSub) }
}

/** 가장 최근 기록: 큰 사진 카드. */
@Composable
private fun VisitHeroCard(c: AppContainer, v: VisitEntity, onOpen: () -> Unit, onDelete: () -> Unit) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    val (title, sub, chip) = visitTexts(c, v)
    val photo = rememberVisitPhoto(c, v)
    MasilCard(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = "$title 기록 자세히", onClick = onOpen)) {
        VisitPhotoBox(photo, title, Modifier.fillMaxWidth().height(300.dp), showLabel = true)
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = type.panelTitle, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, style = type.placeAddress, color = colors.textSub, maxLines = 1)
            }
            StatusChip(chip)
            DeleteButton("이 기록 지우기", onDelete)
        }
        (photo as? PhotoState.Ready)?.let { Text("사진: ${it.photo.source}", style = type.placeAddress, color = colors.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** 나머지 기록: 왼쪽 사진, 오른쪽 글. */
@Composable
private fun VisitRowCard(c: AppContainer, v: VisitEntity, onOpen: () -> Unit, onDelete: () -> Unit) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    val (title, sub, chip) = visitTexts(c, v)
    val photo = rememberVisitPhoto(c, v)
    Row(
        Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(20.dp)).border(1.dp, colors.outline, RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClickLabel = "$title 기록 자세히", onClick = onOpen).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VisitPhotoBox(photo, title, Modifier.width(200.dp).height(130.dp), showLabel = false)
        Spacer(Modifier.width(18.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = type.placeName, color = colors.text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = type.placeAddress, color = colors.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
            StatusChip(chip)
            (photo as? PhotoState.Ready)?.let { Text("사진: ${it.photo.source}", style = type.placeAddress, color = colors.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        DeleteButton("이 기록 지우기", onDelete)
    }
}

/** 리뷰 카드: 장소·고른 항목(주차·웨이팅·재방문)·한 줄·날짜, 작은 휴지통. 별점은 보이지 않는다. */
@Composable
private fun ReviewCard(r: com.team.travelmap.visit.ReviewEntity, onDelete: () -> Unit) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    val answers = listOfNotNull(
        r.parkingAnswer()?.let { "주차 ${it.label}" },
        r.waitingAnswer()?.label,
        r.wouldRevisit?.let { if (it) "또 올래요" else "한 번이면 충분" },
    )
    MasilCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(r.placeName, style = type.placeName, color = colors.text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(formatDate(r.createdAt), style = type.placeAddress, color = colors.textSub)
            DeleteButton("리뷰 지우기", onDelete)
        }
        if (answers.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { answers.forEach { Pill(it) } }
        }
        r.comment?.let { Text(it, style = type.body, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp)) }
        if (answers.isEmpty() && r.comment == null) Text("고른 항목 없음", style = type.placeAddress, color = colors.textSub)
    }
}

