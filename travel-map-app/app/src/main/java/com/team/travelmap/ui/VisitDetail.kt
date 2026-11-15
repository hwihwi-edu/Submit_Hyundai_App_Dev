package com.team.travelmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.team.travelmap.AppContainer
import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.core.model.Place
import com.team.travelmap.core.model.PlaceCategory
import com.team.travelmap.core.model.RegionId
import com.team.travelmap.visit.ParkingAnswer
import com.team.travelmap.visit.ReviewEntity
import com.team.travelmap.visit.VisitEntity
import com.team.travelmap.visit.WaitingAnswer
import com.team.travelmap.visit.parkingAnswer
import com.team.travelmap.visit.waitingAnswer
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 담당: 1번(화면). 기록 상세(Codex history-detail 시안 01~04).
 * 사진·출처 → 장소·지역·상태 → 여행 날짜 / 도착·걸린 시간 / 이동 거리 / 방문 횟수 → 리뷰(주차·웨이팅·재방문·한 줄) → 나만의 메모.
 * 아래 고정: 이 기록 지우기(확인 후) / 다시 여기로 떠나기. 분할(841)은 한 줄 스크롤, 가로(1682)는 왼쪽 사진·정보 + 오른쪽 리뷰·메모.
 * 저장값이 없으면 "기록 없음"(추정해 채우지 않음). 별점·내 사진 넣기 없음.
 * 주행 중(restricted)에는 키보드를 닫고 리뷰·메모·저장·지우기·다시 출발을 잠근다(편집 중 내용은 그대로 둔다).
 */
@Composable
internal fun VisitDetail(
    c: AppContainer, visitId: Long, wide: Boolean, restricted: Boolean,
    onBack: () -> Unit, onPlanned: () -> Unit,
) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    val scope = rememberCoroutineScope()
    val visit by remember(visitId) { c.visits.observe(visitId) }.collectAsStateWithLifecycle(null)
    val all by remember { c.visits.visits }.collectAsStateWithLifecycle(emptyList())
    val reviews by remember { c.reviews.reviews }.collectAsStateWithLifecycle(emptyList())
    val v = visit ?: run {
        // 지운 직후 등 기록이 없으면 목록으로.
        LaunchedEffect(Unit) { kotlinx.coroutines.delay(300); if (visit == null) onBack() }
        return
    }
    // 이 방문에 이어진 리뷰. 없으면 같은 장소에서 경유지 중에 남긴(아직 어디에도 안 이어진) 리뷰.
    val review = reviews.firstOrNull { it.visitId == v.id }
        ?: v.placeName?.let { n -> reviews.firstOrNull { it.visitId == null && it.placeName == n } }

    // 편집 상태(주행으로 잠겨도 내용은 그대로 둔다)
    var editingReview by rememberSaveable(v.id, review?.id) { mutableStateOf(review == null) }
    var editingMemo by rememberSaveable(v.id) { mutableStateOf(v.memo == null) }
    var parking by rememberSaveable(v.id, review?.id) { mutableStateOf(review?.parkingAnswer()) }
    var waiting by rememberSaveable(v.id, review?.id) { mutableStateOf(review?.waitingAnswer()) }
    var revisit by rememberSaveable(v.id, review?.id) { mutableStateOf(review?.wouldRevisit) }
    var comment by rememberSaveable(v.id, review?.id) { mutableStateOf(review?.comment.orEmpty()) }
    var memo by rememberSaveable(v.id) { mutableStateOf(v.memo.orEmpty()) }
    var savedNote by remember(v.id) { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingLeave by remember { mutableStateOf<(() -> Unit)?>(null) }

    val reviewChanged = parking != review?.parkingAnswer() || waiting != review?.waitingAnswer() ||
        revisit != review?.wouldRevisit || comment.trim() != review?.comment.orEmpty()
    val memoChanged = memo.trim() != v.memo.orEmpty()
    val dirty = (editingReview && reviewChanged) || (editingMemo && memoChanged)

    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    LaunchedEffect(restricted) { if (restricted) { keyboard?.hide(); focus.clearFocus() } }

    val region = remember(v.regionCode) { c.regions.find(RegionId(v.regionCode)) }
    val place: Place = remember(v.id, v.placeName) {
        if (v.placeName != null && v.placeLat != null && v.placeLng != null)
            Place(v.placeName, GeoPoint(v.placeLat, v.placeLng), PlaceCategory.SIGHT, RegionId(v.regionCode))
        else region?.let { c.curation.defaultDestination(it) }
            ?: Place(v.regionCode, GeoPoint(0.0, 0.0), PlaceCategory.SIGHT, RegionId(v.regionCode))
    }

    fun save() {
        scope.launch {
            if (editingReview) c.reviews.saveForVisit(review, v.id, place, parking, waiting, revisit, comment)
            if (editingMemo) c.visits.setMemo(v.id, memo)
            editingReview = !(parking != null || waiting != null || revisit != null || comment.isNotBlank()) && review == null
            editingMemo = memo.isBlank()
            savedNote = true
        }
        keyboard?.hide(); focus.clearFocus()
    }
    fun guard(action: () -> Unit) { if (dirty) pendingLeave = action else action() }
    fun goAgain() {
        val r = region ?: return
        c.trip.plan(r, place)
        onPlanned()
    }

    val photo = rememberVisitPhoto(c, v)
    val nth = all.filter { it.regionCode == v.regionCode }.sortedBy { it.visitedAt }.indexOfFirst { it.id == v.id } + 1

    val top: @Composable ColumnScope.() -> Unit = {
        DetailPhoto(photo, v.placeName ?: region?.shortName ?: v.regionCode)
        (photo as? PhotoState.Ready)?.let { Text("사진: ${it.photo.source}", style = type.placeAddress, color = colors.textSub, maxLines = 2) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(v.placeName ?: region?.shortName ?: v.regionCode, style = type.panelTitle, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(region?.fullName ?: v.regionCode, style = type.body, color = colors.textSub)
            }
            Text(
                if (v.manual) "직접 체크" else "방문 완료", style = type.placeAddress, color = colors.primary,
                modifier = Modifier.background(colors.selectedBg, RoundedCornerShape(50)).padding(horizontal = 18.dp, vertical = 8.dp),
            )
        }
        val dateFmt = remember { SimpleDateFormat("yyyy.MM.dd (E)", Locale.KOREA) }
        val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.KOREA) }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCell("여행 날짜", dateFmt.format(Date(v.visitedAt)), Modifier.weight(1f))
            if (v.manual) InfoCell("기록한 시각", "${timeFmt.format(Date(v.visitedAt))} · 직접 체크", Modifier.weight(1f))
            else InfoCell("도착 · 걸린 시간", "${timeFmt.format(Date(v.visitedAt))} · ${v.durationMinutes?.let(::minutesText) ?: "기록 없음"}", Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoCell("이동 거리", v.distanceKm?.let { "%.0f km".format(it) } ?: "기록 없음", Modifier.weight(1f))
            InfoCell("방문 횟수", if (nth > 0) "${nth}번째 방문" else "기록 없음", Modifier.weight(1f))
        }
    }

    val bottom: @Composable ColumnScope.() -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("이곳에서의 시간", style = type.panelTitle, color = colors.text)
            Text("짧은 선택으로 기억을 남겨보세요.", style = type.placeAddress, color = colors.textSub)
        }
        if (restricted) Text("정차 후 쓸 수 있어요.", style = type.body, color = colors.primary)
        if (editingReview) {
            Question("주차는 어땠어요?") {
                ParkingAnswer.entries.forEach { a -> OptionChip(a.label, parking == a, Modifier.weight(1f), enabled = !restricted) { parking = if (parking == a) null else a } }
            }
            Question("웨이팅은요?") {
                WaitingAnswer.entries.forEach { a -> OptionChip(a.label, waiting == a, Modifier.weight(1f), enabled = !restricted) { waiting = if (waiting == a) null else a } }
            }
            Question("다시 오고 싶어요?") {
                OptionChip("또 올래요", revisit == true, Modifier.weight(1f), enabled = !restricted) { revisit = if (revisit == true) null else true }
                OptionChip("한 번이면 충분", revisit == false, Modifier.weight(1f), enabled = !restricted) { revisit = if (revisit == false) null else false }
            }
            OutlinedTextField(
                value = comment, onValueChange = { comment = it.take(80) }, singleLine = true, textStyle = type.body, enabled = !restricted,
                placeholder = { Text("한 줄 남기기 (선택)", style = type.body, color = colors.textSub) },
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            )
        } else review?.let { r -> SavedReview(r, enabled = !restricted) { editingReview = true } }

        Text("나만의 메모", style = type.placeName, color = colors.text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        if (editingMemo) {
            OutlinedTextField(
                value = memo, onValueChange = { memo = it.take(500) }, textStyle = type.body, enabled = !restricted, minLines = 3,
                placeholder = { Text("주차 위치와 다음에 기억할 것들을\n자유롭게 적어두세요.", style = type.body, color = colors.textSub) },
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                v.memo.orEmpty(), style = type.body, color = colors.text,
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).background(colors.card, RoundedCornerShape(16.dp))
                    .border(1.dp, colors.outline, RoundedCornerShape(16.dp)).padding(18.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("저장됨", style = type.placeAddress, color = colors.primary, modifier = Modifier.weight(1f))
                SecondaryButton("메모 고치기", Modifier.width(220.dp), height = 72.dp) { if (!restricted) editingMemo = true }
            }
        }
        if (editingReview || editingMemo) {
            PrimaryButton("리뷰와 메모 저장", Modifier.fillMaxWidth(), height = 76.dp, enabled = !restricted && dirty) { save() }
            if (savedNote && !dirty) Text("저장됨", style = type.placeAddress, color = colors.primary)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SecondaryButton("←  기록으로", Modifier.width(200.dp), height = 72.dp) { guard(onBack) }
            Spacer(Modifier.width(20.dp))
            Text(
                if (wide) "기록 상세" else "${v.placeName ?: region?.shortName ?: ""} · ${if (editingReview || editingMemo) "리뷰와 메모" else "저장한 기록"}",
                style = type.placeName, color = colors.text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(16.dp))
        if (wide) {
            Row(Modifier.weight(1f)) {
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp), content = top)
                Box(Modifier.padding(horizontal = 24.dp).width(1.dp).fillMaxHeight().background(colors.divider))
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp), content = bottom)
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                top()
                HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 6.dp))
                bottom()
                Spacer(Modifier.height(8.dp))
            }
        }
        HorizontalDivider(color = colors.divider, modifier = Modifier.padding(vertical = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier.width(210.dp).height(76.dp).background(colors.card, RoundedCornerShape(16.dp))
                    .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
                    .clickable(enabled = !restricted, role = Role.Button, onClickLabel = "이 기록 지우기") { confirmDelete = true },
                contentAlignment = Alignment.Center,
            ) { Text("이 기록 지우기", style = type.placeName, color = colors.error.copy(alpha = if (restricted) 0.4f else 1f)) }
            PrimaryButton("다시 여기로 떠나기  →", Modifier.weight(1f), height = 76.dp, enabled = !restricted && region != null) { guard(::goAgain) }
        }
    }

    if (confirmDelete) ConfirmDialog(
        title = "이 기록을 지울까요?",
        message = "${v.placeName ?: region?.fullName ?: ""} ${SimpleDateFormat("M월 d일", Locale.KOREA).format(Date(v.visitedAt))} 기록과 메모가 지워지고 되돌릴 수 없어요. 같은 곳의 다른 방문 기록은 그대로예요.",
        confirm = "지우기",
        onConfirm = { scope.launch { c.visits.delete(v.id); onBack() } },
        onDismiss = { confirmDelete = false },
    )
    pendingLeave?.let { leave ->
        ConfirmDialog(
            title = "저장하지 않은 내용이 있어요",
            message = "리뷰나 메모를 저장할까요? \"버리기\"를 누르면 바꾼 내용이 사라져요.",
            confirm = "저장하고 나가기",
            onConfirm = { save(); leave() },
            onDismiss = { pendingLeave = null },
            dismissLabel = "버리기",
            onDismissAction = { pendingLeave = null; leave() },
        )
    }
}

private fun minutesText(min: Int): String = when {
    min < 60 -> "${min}분"
    min % 60 == 0 -> "${min / 60}시간"
    else -> "${min / 60}시간 ${min % 60}분"
}

/** 사진 칸: 원본 비율(공공누리 3유형은 Fit), 왼쪽 아래 "방문 장소 사진" 라벨, 불러오는 중/사진 없음. */
@Composable
private fun DetailPhoto(state: PhotoState, name: String) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    Box(
        Modifier.fillMaxWidth().aspectRatio(2.3f).clip(RoundedCornerShape(20.dp)).background(colors.surface2)
            .border(1.dp, colors.outline, RoundedCornerShape(20.dp)),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            PhotoState.Loading -> Text("사진 불러오는 중", style = type.placeAddress, color = colors.textSub)
            PhotoState.None -> Text("$name\n사진 없음", style = type.placeName, color = colors.primary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            is PhotoState.Ready -> {
                androidx.compose.foundation.Image(
                    state.photo.bitmap, contentDescription = "$name 사진",
                    contentScale = if (state.photo.crop) ContentScale.Crop else ContentScale.Fit, modifier = Modifier.fillMaxSize(),
                )
                Text(
                    "방문 장소 사진", style = type.placeAddress, color = colors.text,
                    modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
                        .background(Color.White.copy(alpha = 0.92f), RoundedCornerShape(8.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun InfoCell(label: String, value: String, modifier: Modifier) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    Column(
        modifier.heightIn(min = 100.dp).background(colors.card, RoundedCornerShape(20.dp)).border(1.dp, colors.outline, RoundedCornerShape(20.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(label, style = type.placeAddress, color = colors.textSub)
        Text(value, style = type.placeName, color = if (value.startsWith("기록 없음")) colors.textSub else colors.text, fontWeight = FontWeight.SemiBold, maxLines = 2)
    }
}

@Composable
private fun Question(title: String, options: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val colors = LocalChaumColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = LocalChaumType.current.placeName, color = colors.text, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = options)
    }
}

/** 저장한 리뷰(시안 03): 고른 항목 · 한 줄 + 고치기. */
@Composable
private fun SavedReview(r: ReviewEntity, enabled: Boolean, onEdit: () -> Unit) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    val answers = listOfNotNull(
        r.parkingAnswer()?.let { "주차 ${it.label}" }, r.waitingAnswer()?.label,
        r.wouldRevisit?.let { if (it) "또 올래요" else "한 번이면 충분" },
    )
    Column(
        Modifier.fillMaxWidth().background(colors.card, RoundedCornerShape(20.dp)).border(1.dp, colors.outline, RoundedCornerShape(20.dp)).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(answers.ifEmpty { listOf("고른 항목 기록 없음") }.joinToString("  ·  "), style = type.body, color = if (answers.isEmpty()) colors.textSub else colors.primary)
        r.comment?.let { Text(it, style = type.body, color = colors.text) }
        Row {
            Spacer(Modifier.weight(1f))
            SecondaryButton("고치기", Modifier.width(140.dp), height = 72.dp) { if (enabled) onEdit() }
        }
    }
}
