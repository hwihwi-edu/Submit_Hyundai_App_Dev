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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.team.travelmap.AppContainer
import com.team.travelmap.core.geo.Geo
import com.team.travelmap.core.model.Place
import com.team.travelmap.curation.companion.PlaceNarration
import com.team.travelmap.curation.companion.RouteCompanion
import com.team.travelmap.core.trip.TripState

/** 경유지를 지난 것으로 보는 거리. 경유지 도착 이벤트(2번)가 생기면 그것으로 바꾼다. */
private const val PASSED_RADIUS_M = 300.0

/**
 * 담당: 1번(지도·화면). 주행 중(안내 중) 패널. Codex「마실 디자인 확정」시안 03(주행 동행) 기준.
 *  - 다음에 들를 곳(경유지 n/전체 또는 목적지) + 남은 시간
 *  - 오늘의 여정: 남은 경유지·목적지
 *  - 길 위 이야기: 지금 재생 중이거나 경로에서 가장 가까운 이야기, 큰 버튼 하나
 * 주행 중이라 글을 읽는 정보는 줄이고, 누르는 것은 88dp 이상 버튼만 둔다.
 */
@Composable
internal fun ColumnScope.DrivingPanel(c: AppContainer, s: TripState.Guiding) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    val g by c.navi.guidance.collectAsStateWithLifecycle()
    val here by c.navi.currentLocation.collectAsStateWithLifecycle()

    // 지난 경유지 수. 위치가 경유지 반경에 들어오면 하나씩 넘긴다(되돌아가지 않음).
    // 주행 중 경유지가 추가돼도(3번 가는 길 동행) 초기화되지 않게 목적지만 키로 쓰고, 순서표(legs) 진행보다 뒤로 가지 않는다.
    var nearPassed by rememberSaveable(s.destination.name) { mutableStateOf(0) }
    LaunchedEffect(here, s.waypoints) {
        val p = here ?: return@LaunchedEffect
        while (nearPassed < s.waypoints.size && Geo.distanceMeters(p, s.waypoints[nearPassed].point) <= PASSED_RADIUS_M) nearPassed++
    }
    val passed = maxOf(nearPassed, s.legs.nextIndex).coerceAtMost(s.waypoints.size)
    val stops: List<Pair<Place, String>> = s.waypoints.mapIndexed { i, w -> w to "경유지 ${i + 1} / ${s.waypoints.size}" } +
        (s.destination to "목적지")
    val next = stops[passed.coerceAtMost(stops.lastIndex)]
    val rest = stops.drop(passed + 1)
    val nextIsDestination = rest.isEmpty()

    // 시안 02: 다음 경유지 이름을 크게, 그 아래 "다음 경유지까지"(추정 또는 내비 계산)와 "최종 목적지까지"(내비 남은 값)를 따로.
    Column {
        Text(if (nextIsDestination) "도착할 곳" else "다음 경유지", style = type.label, color = colors.primary)
        Text(next.first.name, style = type.guideNumber, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (!nextIsDestination) Text(next.second, style = type.placeAddress, color = colors.textSub)
    }
    // 다음 경유지까지: 1km 넘게 움직일 때마다 2번 tripCost 로 다시 계산(내비 남은 값은 최종 목적지 기준이라 따로 구한다).
    var legAnchor by androidx.compose.runtime.remember(next.first) { mutableStateOf(here) }
    here?.let { h -> if (legAnchor == null || Geo.distanceMeters(legAnchor!!, h) > 1000) legAnchor = h }
    val legCost by androidx.compose.runtime.produceState<com.team.travelmap.core.model.TripCost?>(null, next.first, legAnchor) {
        if (!nextIsDestination) value = runCatching { c.navi.tripCost(next.first) }.getOrNull()
    }
    val time = g.remainTimeSeconds?.takeIf { it > 0 }
    val km = g.remainDistanceMeters?.takeIf { it > 0 }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!nextIsDestination) RemainCard(
            "다음 경유지까지" + if (legCost?.estimated == true) " · 추정" else "",
            legCost?.durationSeconds, legCost?.distanceMeters, Modifier.weight(1f),
        )
        RemainCard(if (nextIsDestination) "목적지까지" else "최종 목적지까지", time, km, Modifier.weight(1f))
    }

    // 여행 진행: 가로 단계 표시(지남 ✓ / 지금 / 남음)
    if (stops.size > 1) Progress(stops.map { it.first.name }, passed)

    // 동행 제안이 떠 있으면 좁은 분할 창(약 841px)에서 잘리지 않게 이야기·주변 카드는 잠시 숨긴다.
    val companionShown by c.companion.suggestion.collectAsStateWithLifecycle()
    val approachShown by c.approach.notice.collectAsStateWithLifecycle()
    ApproachCard(c)
    CompanionCard(c)
    if (companionShown == null && approachShown == null) {
        StoryCard(c)
        NearbyWhileDriving(c, here)
    }

    Spacer(Modifier.weight(1f))
    Text("장소 정보는 정차 후 볼 수 있어요", style = type.body, color = colors.textSub)
    SecondaryButton("여행 취소", Modifier.fillMaxWidth(), height = 88.dp) { c.trip.cancel() }
}

/** 가로 진행 표시: 점과 선. 지난 곳은 채운 초록 + ✓, 다음 곳은 굵은 테두리, 남은 곳은 회색. */
@Composable
private fun Progress(names: List<String>, passed: Int) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        names.forEachIndexed { i, name ->
            val done = i < passed
            val now = i == passed
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(name, style = type.placeAddress, color = if (done || now) colors.text else colors.textSub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(32.dp), contentAlignment = Alignment.Center) {
                    // 점 사이 선(첫 점 왼쪽·마지막 점 오른쪽은 비움)
                    Row(Modifier.fillMaxWidth()) {
                        Box(Modifier.weight(1f).height(3.dp).background(if (i == 0) colors.card.copy(alpha = 0f) else if (i <= passed) colors.primary else colors.divider))
                        Box(Modifier.weight(1f).height(3.dp).background(if (i == names.lastIndex) colors.card.copy(alpha = 0f) else if (i < passed) colors.primary else colors.divider))
                    }
                    Box(
                        Modifier
                            .size(if (now) 30.dp else 26.dp)
                            .background(if (done) colors.primary else if (now) colors.selectedBg else colors.card, CircleShape)
                            .border(if (now) 3.dp else 2.dp, if (done || now) colors.primary else colors.divider, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { if (done) Text("✓", style = type.placeAddress, color = colors.onPrimary) }
                }
            }
        }
    }
}

/** 길 위 이야기: 재생 중이면 일시정지·그만 듣기, 아니면 가장 가까운 이야기와 둥근 재생 버튼. 둘 다 없으면 숨긴다. */
@Composable
private fun StoryCard(c: AppContainer) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    val playing by c.storyPlayer.nowPlaying.collectAsStateWithLifecycle()
    val upcoming by c.stories.upcoming.collectAsStateWithLifecycle()
    val story = playing ?: upcoming ?: return

    MasilCard(Modifier.fillMaxWidth(), fill = colors.surface2) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Headphones, null, tint = colors.primary, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(8.dp))
            Text(if (playing != null) "이 풍경의 이야기 · 듣는 중" else "이 풍경의 이야기", style = type.label, color = colors.primary)
        }
        Spacer(Modifier.height(8.dp))
        // 제목 앞의 출처 꼬리표("[오디 원본·2025년 자료] ")는 주행 중 화면에서 뺀다. 출처는 설정 > 정보에 있다.
        Text(story.title.replace(Regex("""^\[[^\]]*\]\s*"""), ""), style = type.panelTitle, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (playing == null) {
                StoryButton(Icons.Filled.PlayArrow, "이야기 듣기", Modifier.weight(1f), filled = true) { c.stories.playNow(story) }
            } else {
                StoryButton(Icons.Filled.Pause, "일시정지", Modifier.weight(1f)) { c.storyPlayer.pauseOrResume() }
                StoryButton(Icons.Filled.Stop, "종료", Modifier.weight(1f)) { c.storyPlayer.stop() }
            }
        }
    }
}

/**
 * 3번 가는 길 동행 카드: 장소 이름 · 관광지는 한 줄 소개 · "길에서 n분 · 둘러보기 30분 · 잠깐 들렀다 갈까요?"
 * + 이야기 더 듣기(소개가 더 있는 관광지만) / 잠깐 들르기 / 괜찮아요. 주행 중에도 큰 버튼(88dp)만.
 * 같은 내용을 음성으로도 묻는다(c.companion). 음식점은 소개 없이 묻기만 한다.
 * 45초 동안 답이 없으면 저절로 닫힌다(c.companion).
 */
@Composable
private fun CompanionCard(c: AppContainer) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    val s by c.companion.suggestion.collectAsStateWithLifecycle()
    val storyOpen by c.companion.storyOpen.collectAsStateWithLifecycle()
    val listening by c.companion.listening.collectAsStateWithLifecycle()
    val suggestion = s ?: return
    var note by androidx.compose.runtime.remember(suggestion.candidate.id) { mutableStateOf<String?>(null) }
    val n = suggestion.narration
    MasilCard(Modifier.fillMaxWidth(), fill = colors.selectedBg) {
        Text("가는 길 동행 · ${"%.1f".format(suggestion.aheadKm)}km 앞 · ${categoryLabel(suggestion.candidate.category)}", style = type.label, color = colors.primary)
        Spacer(Modifier.height(6.dp))
        Text(n.headline, style = type.panelTitle, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        // 관광지는 한 줄 소개(음식점은 없음). "이야기 더 듣기"를 누르면 더 긴 소개를 펼친다.
        val body = if (storyOpen) n.story else n.brief
        body?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, style = type.body, color = colors.text, maxLines = if (storyOpen) 4 else 2, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(6.dp))
        Text(PlaceNarration.detourLabel(suggestion.detourDriveMinutes, RouteCompanion.SHORT_STOP_MINUTES) + " · 잠깐 들렀다 갈까요?",
            style = type.placeAddress, color = colors.textSub)
        if (listening) {
            Spacer(Modifier.height(6.dp))
            Text("🎤 듣고 있어요 · \"들를래\"  \"괜찮아\"" + if (n.hasMoreStory) "  \"이야기\"" else "",
                style = type.body, color = colors.primary)
        }
        note?.let { Text(it, style = type.placeAddress, color = colors.textSub) }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!storyOpen && n.hasMoreStory) StoryButton(Icons.Filled.PlayArrow, "이야기 더 듣기", Modifier.weight(1f)) {
                c.companion.openStory()
                // 녹음된 이야기(오디)가 있으면 그 음성으로. 없으면 c.companion 이 소개 글을 Pleos TTS 로 읽는다.
                n.audioStoryId?.let { id -> c.stories.storyById(id)?.let { c.stories.playNow(it) } }
            }
            StoryButton(Icons.Outlined.Place, "잠깐 들르기", Modifier.weight(1f), filled = true) {
                // 경유지 추가가 된 뒤에만 제안을 닫는다(실패하면 카드에 안내).
                c.trip.addStopNow(c.companion.placeOf(suggestion))
                    .onSuccess { c.companion.accept() }
                    .onFailure { note = "지금은 경유지를 추가할 수 없어요. 다시 출발한 뒤에 시도해 주세요." }
            }
        }
        Spacer(Modifier.height(12.dp))
        SecondaryButton("괜찮아요", Modifier.fillMaxWidth(), height = 88.dp) { c.companion.decline() }
    }
}

private fun categoryLabel(c: com.team.travelmap.core.model.PlaceCategory): String = when (c) {
    com.team.travelmap.core.model.PlaceCategory.FOOD -> "음식점"
    com.team.travelmap.core.model.PlaceCategory.FESTIVAL -> "축제·행사"
    else -> "관광지"
}

/**
 * 3번 곧 도착 안내 카드: 다음 경유지·목적지 2km 안에서 "곧 경유지 ○○에 도착해요" + 소개 앞부분(c.approach).
 * 음성 해설이 켜져 있으면 같은 내용을 읽는다. 40초 뒤 또는 "닫기"로 닫힌다.
 */
@Composable
private fun ApproachCard(c: AppContainer) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    val n by c.approach.notice.collectAsStateWithLifecycle()
    val notice = n ?: return
    MasilCard(Modifier.fillMaxWidth(), fill = colors.selectedBg) {
        Text(if (notice.isDestination) "곧 도착 · 목적지" else "곧 도착 · 경유지", style = type.label, color = colors.primary)
        Spacer(Modifier.height(6.dp))
        Text(notice.headline, style = type.panelTitle, color = colors.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
        notice.story?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, style = type.body, color = colors.text, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.height(12.dp))
        SecondaryButton("닫기", Modifier.fillMaxWidth(), height = 88.dp) { c.approach.dismiss() }
    }
}

/** 남은 시간·거리 칸(시안 02): 작은 제목 + 시계·시간 | 핀·거리. 값이 없으면 "계산 중". */
@Composable
private fun RemainCard(title: String, seconds: Int?, meters: Int?, modifier: Modifier) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    Column(
        modifier.background(colors.card, RoundedCornerShape(18.dp)).border(1.dp, colors.outline, RoundedCornerShape(18.dp)).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, style = type.label, color = colors.textSub, maxLines = 1)
        if (seconds == null && meters == null) {
            Text("계산 중", style = type.placeName, color = colors.textSub)
        } else {
            // 좁은 분할 창(841)에서도 잘리지 않게 시간·거리를 두 줄로.
            seconds?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Schedule, null, tint = colors.textSub, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(formatRemainTime(it), style = type.panelTitle, color = colors.text, maxLines = 1)
                }
            }
            meters?.let {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Place, null, tint = colors.textSub, modifier = Modifier.size(26.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("%.1f km".format(it / 1000.0), style = type.placeName, color = colors.text, maxLines = 1)
                }
            }
        }
    }
}

/** 이야기 버튼(88dp, 아이콘 + 글자). */
@Composable
private fun StoryButton(icon: ImageVector, label: String, modifier: Modifier, filled: Boolean = false, onClick: () -> Unit) {
    val colors = LocalChaumColors.current
    Row(
        modifier.height(88.dp)
            .background(if (filled) colors.primary else colors.card, RoundedCornerShape(16.dp))
            .border(1.dp, if (filled) colors.primary else colors.outline, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (filled) colors.onPrimary else colors.primary, modifier = Modifier.size(32.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = LocalChaumType.current.placeName, color = if (filled) colors.onPrimary else colors.text)
    }
}

/**
 * 주행 중 주변 추천(이슈 #9·사용자 결정: 국도·일반 도로에서 지나는 곳 주변, 조수석에서 볼 수 있게).
 * 설정 "주변 장소 추천"이 켜져 있을 때만. 현재 위치 3km 안 관광지·음식점 2곳을 이름·직선거리로만 보여 준다(누르는 것 없음).
 * 고속도로 위에서는 숨기고, IC 를 나오면 그 자리 기준 3km 안만 보인다(trip.onHighway).
 */
@Composable
private fun NearbyWhileDriving(c: AppContainer, here: com.team.travelmap.core.model.GeoPoint?) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    val settings by c.settings.state.collectAsStateWithLifecycle()
    val onHighway by c.trip.onHighway.collectAsStateWithLifecycle()
    // 고속도로 위에서는 들를 수 없으니 숨긴다. IC 를 나오면 그 자리(= IC) 기준 3km 안만 다시 보여 준다.
    var icExit by androidx.compose.runtime.remember { mutableStateOf<com.team.travelmap.core.model.GeoPoint?>(null) }
    var wasHighway by androidx.compose.runtime.remember { mutableStateOf(onHighway) }
    if (wasHighway != onHighway) {
        if (wasHighway && !onHighway) icExit = here
        wasHighway = onHighway
    }
    if (!settings.nearbyCards || here == null || onHighway) return
    // 1km 넘게 움직였을 때만 다시 찾는다.
    var anchor by androidx.compose.runtime.remember { mutableStateOf(here) }
    if (Geo.distanceMeters(anchor, here) > 1000) anchor = here
    val list by androidx.compose.runtime.produceState<List<Nearby>>(emptyList(), anchor, icExit) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            val found = c.tourism.nearby(anchor, 3.0, 2)
            val ic = icExit
            // IC 를 나온 뒤에는 IC 3km 안 장소만(멀어지면 IC 기준을 풀고 평소처럼)
            if (ic != null && Geo.distanceMeters(ic, anchor) <= 3000) found.filter { Geo.distanceMeters(ic, com.team.travelmap.core.model.GeoPoint(it.entry.lat, it.entry.lng)) <= 3000 }
            else found
        }
    }
    if (list.isEmpty()) return
    MasilCard(Modifier.fillMaxWidth()) {
        Text(if (icExit != null && Geo.distanceMeters(icExit!!, here) <= 3000) "IC 나와서 3km 안" else "지나는 길 주변", style = type.placeName, color = colors.text, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
        list.forEach { n ->
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(n.entry.name, style = type.placeName, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("${n.kind} · 직선 ${formatKm(n.km)}", style = type.placeAddress, color = colors.textSub)
            }
        }
    }
}
