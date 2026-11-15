package com.team.travelmap.ui

import androidx.compose.foundation.background
import com.team.travelmap.visit.WaitingAnswer
import com.team.travelmap.visit.ParkingAnswer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.team.travelmap.AppContainer
import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.core.model.Place
import com.team.travelmap.core.model.PlaceCategory
import com.team.travelmap.core.trip.ResumeRequest
import com.team.travelmap.core.trip.TripResume
import com.team.travelmap.core.trip.WaypointReviewRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** 개발자 화면에서 띄우는 미리보기(실제 주차·시동 없이 화면 확인). 저장은 실제 DB 에 된다. */
internal object PromptPreview {
    val review = MutableStateFlow<WaypointReviewRequest?>(null)
    val resume = MutableStateFlow<ResumeRequest?>(null)
    val samplePlace = Place("대전시민천문대", GeoPoint(36.3755, 127.3637), PlaceCategory.SIGHT)
    fun sampleResume() = ResumeRequest(
        next = Place("비빔가", GeoPoint(36.3584, 127.3446), PlaceCategory.FOOD),
        toDestination = true, remainingWaypoints = 0,
        autoContinueAtMillis = System.currentTimeMillis() + TripResume.AUTO_CONTINUE_MS,
    )
}

/**
 * 담당: 1번(화면). 2번 신호에 맞춰 뜨는 두 화면. 어느 탭에서든 위에 뜬다.
 *  - 경유지 리뷰: 경유지에 주차(P)하면 [com.team.travelmap.core.trip.TripCoordinator.reviewRequest] → 주차·웨이팅·재방문·한 줄
 *  - 여행을 계속할까요: 경유지에서 시동을 껐다 켜면 resumeRequest → 계속 / 여행 끝내기(이슈 #21, 10초 뒤 자동 계속)
 * 둘 다 있으면 "계속할까요"가 먼저(차를 움직이기 전 결정이 필요).
 */
@Composable
internal fun TripPrompts(c: AppContainer) {
    val realReview by c.trip.reviewRequest.collectAsStateWithLifecycle()
    val realResume by c.trip.resumeRequest.collectAsStateWithLifecycle()
    val previewReview by PromptPreview.review.collectAsStateWithLifecycle()
    val previewResume by PromptPreview.resume.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    val resume = realResume ?: previewResume
    val review = realReview ?: previewReview
    when {
        resume != null -> ResumePrompt(resume, autoClose = realResume == null) { go ->
            if (realResume != null) c.trip.answerResume(go) else PromptPreview.resume.value = null
        }
        review != null -> ReviewPrompt(review,
            onSave = { parking, waiting, revisit, comment ->
                scope.launch { c.reviews.save(review.place, parking, waiting, revisit, comment) }
                if (realReview != null) c.trip.dismissReview() else PromptPreview.review.value = null
            },
            onSkip = { if (realReview != null) c.trip.dismissReview() else PromptPreview.review.value = null },
        )
    }
}

@Composable
private fun PromptCard(content: @Composable () -> Unit) {
    val colors = LocalChaumColors.current
    Dialog(onDismissRequest = {}, properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false, usePlatformDefaultWidth = false)) {
        Column(
            Modifier.widthIn(max = 760.dp).fillMaxWidth(0.92f)
                .background(colors.card, RoundedCornerShape(28.dp))
                .border(1.dp, colors.outline, RoundedCornerShape(28.dp))
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(32.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) { content() }
    }
}

/**
 * 경유지 리뷰(시안 03, 기획 2차 8번): 주차 / 웨이팅 / 재방문 3문항 선택형 + 한 줄(선택). 별점 없음.
 * 아무것도 고르지 않으면 저장 대신 건너뛰기. 주차 중에만 뜨므로 입력 허용.
 */
@Composable
private fun ReviewPrompt(
    req: WaypointReviewRequest,
    onSave: (ParkingAnswer?, WaitingAnswer?, Boolean?, String?) -> Unit,
    onSkip: () -> Unit,
) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    var parking by remember(req) { mutableStateOf<ParkingAnswer?>(null) }
    var waiting by remember(req) { mutableStateOf<WaitingAnswer?>(null) }
    var revisit by remember(req) { mutableStateOf<Boolean?>(null) }
    var comment by remember(req) { mutableStateOf("") }
    val answered = parking != null || waiting != null || revisit != null || comment.isNotBlank()
    PromptCard {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                "경유지 ${req.index + 1} · 잠깐 쉬는 중", style = type.label, color = colors.primary,
                modifier = Modifier.border(1.5.dp, colors.primary, RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Text("${req.place.name},\n어땠어요?", style = type.panelTitle, color = colors.text, textAlign = TextAlign.Center)
            Text("잠깐의 느낌만 남겨도 좋아요", style = type.placeAddress, color = colors.textSub)
        }
        HorizontalDivider(color = colors.divider)
        Question("주차는 어땠어요?") {
            ParkingAnswer.entries.forEach { a -> OptionChip(a.label, parking == a, Modifier.weight(1f)) { parking = if (parking == a) null else a } }
        }
        Question("웨이팅은요?") {
            WaitingAnswer.entries.forEach { a -> OptionChip(a.label, waiting == a, Modifier.weight(1f)) { waiting = if (waiting == a) null else a } }
        }
        Question("다시 오고 싶어요?") {
            OptionChip("또 올래요", revisit == true, Modifier.weight(1f)) { revisit = if (revisit == true) null else true }
            OptionChip("한 번이면 충분", revisit == false, Modifier.weight(1f)) { revisit = if (revisit == false) null else false }
        }
        OutlinedTextField(
            value = comment, onValueChange = { comment = it.take(80) }, singleLine = true, textStyle = type.body,
            placeholder = { Text("한 줄 남기기 (선택)", style = type.body, color = colors.textSub) },
            shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("건너뛰기", Modifier.weight(1f), height = 88.dp, onClick = onSkip)
            PrimaryButton(if (answered) "저장" else "하나라도 골라 주세요", Modifier.weight(1.4f), height = 88.dp, enabled = answered) {
                onSave(parking, waiting, revisit, comment)
            }
        }
        Text(
            "리뷰 요청은 설정에서 끌 수 있어요. 다시 출발하면 자동으로 닫혀요.", style = type.placeAddress, color = colors.textSub,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Question(title: String, options: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    val colors = LocalChaumColors.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = LocalChaumType.current.placeName, color = colors.text, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), content = options)
    }
}

/** 여행을 계속할까요: 큰 버튼 2개 + 자동 계속 카운트다운. 시동 직후라 움직일 수 있으므로 글자 입력은 없다. */
@Composable
private fun ResumePrompt(req: ResumeRequest, autoClose: Boolean, onAnswer: (Boolean) -> Unit) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    var now by remember(req) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(req) {
        while (true) { delay(250); now = System.currentTimeMillis() }
    }
    val left = ((req.autoContinueAtMillis - now + 999) / 1000).coerceAtLeast(0)
    // 실제 신호는 2번 타이머가 자동으로 계속한다. 미리보기만 화면에서 닫는다.
    LaunchedEffect(left) { if (autoClose && left == 0L) onAnswer(true) }
    PromptCard {
        Text("여행을 계속할까요?", style = type.panelTitle, color = colors.text)
        Text(
            if (req.toDestination) "다음은 목적지 ${req.next.name}이에요."
            else "다음은 ${req.next.name}, 남은 경유지 ${req.remainingWaypoints}곳이에요.",
            style = type.body, color = colors.text,
        )
        Text("${left}초 뒤 자동으로 계속해요.", style = type.placeAddress, color = colors.textSub)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton("여행 끝내기", Modifier.weight(1f), height = 96.dp) { onAnswer(false) }
            PrimaryButton("계속하기", Modifier.weight(1.4f), height = 96.dp) { onAnswer(true) }
        }
        Text("\"여행 끝내기\"를 고르면 다음 곳으로 가지 않고 여행을 끝내요. 이번 여행은 방문 기록에 남지 않아요.", style = type.placeAddress, color = colors.textSub)
    }
}
