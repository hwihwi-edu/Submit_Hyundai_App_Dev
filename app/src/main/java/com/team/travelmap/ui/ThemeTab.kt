package com.team.travelmap.ui

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Celebration
import androidx.compose.material.icons.outlined.Eco
import androidx.compose.material.icons.outlined.Landscape
import androidx.compose.material.icons.outlined.LocalCafe
import androidx.compose.material.icons.outlined.Restaurant
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.team.travelmap.AppContainer
import com.team.travelmap.core.model.ThemePlaceCard
import com.team.travelmap.core.model.TravelTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 한 번에 보여 줄 사진 카드 수. 나머지는 "다른 곳 보기"·테마 다시 누르기로 바뀐다. */
private const val THEME_SHOWN = 3

/** 시범 도시 이름(시·도 코드 → 이름). */
private val CITY_NAMES = mapOf("11" to "서울", "30" to "대전", "26" to "부산")

/** 확정 시안 02의 선 아이콘. */
private fun TravelTheme.icon(): ImageVector = when (this) {
    TravelTheme.FESTIVAL -> Icons.Outlined.Celebration
    TravelTheme.HISTORY -> Icons.Outlined.AccountBalance
    TravelTheme.FOOD -> Icons.Outlined.Restaurant
    TravelTheme.CAFE -> Icons.Outlined.LocalCafe
    TravelTheme.SEASONAL -> Icons.Outlined.Eco
    TravelTheme.HEALING -> Icons.Outlined.Landscape
}

/**
 * 담당: 1번(지도·화면). 테마 탭. Codex「마실 디자인 확정」시안 02(테마·취향 탐색) 기준.
 * 테마 6개 아이콘 타일 → 3번 테마 데이터(ThemeCurationRepository)의 실제 관광지 사진 카드.
 * 사진은 한국관광공사 원본 그대로(자르지 않음, ContentScale.Fit) 출처를 함께 보인다.
 * 주행 중에는 사진 없이 테마 타일만 보인다.
 * "코스 보기": 이 테마(와 도시)로 여행 탭 코스 추천을 연다. 코스 짜기·첫 장소 실제 거리(2번 tripCost)는 여행 탭이 한다.
 */
@Composable
internal fun ThemeTab(
    c: AppContainer, restricted: Boolean, theme: TravelTheme, onTheme: (TravelTheme) -> Unit,
    /** 여행 탭 코스 추천으로 이동. 인자는 시·도 코드("11"·"26"·"30"). */
    onCourses: (String) -> Unit,
    onPlanned: () -> Unit,
) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    val hotStats by c.hot.stats.collectAsStateWithLifecycle()
    val settingsState by c.settings.state.collectAsStateWithLifecycle()
    val cards by produceState<List<ThemePlaceCard>?>(null, theme) {
        value = null
        value = withContext(Dispatchers.IO) { runCatching { c.themeCuration.cards(theme) }.getOrDefault(emptyList()) }
    }
    // 상세로 연 카드와 거기서 담은 주변 장소(경유지). 테마를 바꾸면 닫는다.
    var detailId by rememberSaveable(theme) { mutableStateOf<String?>(null) }
    // 테마를 누를 때마다(같은 테마도) 순서를 섞어 다른 곳을 먼저 보여 준다.
    var shuffle by rememberSaveable { androidx.compose.runtime.mutableIntStateOf(0) }
    var picked by rememberSaveable(detailId) { mutableStateOf(listOf<String>()) }
    val detail = cards?.firstOrNull { it.contentId == detailId }

    fun go(card: ThemePlaceCard, waypointIds: List<String>) {
        val region = card.place.regionId?.let(c.regions::find) ?: return
        val waypoints = waypointIds.mapNotNull { id -> c.tourism.all.firstOrNull { it.contentId == id }?.asPlace() }
        c.trip.plan(region, card.place, waypoints)
        onPlanned()
    }

    LazyColumn(
        Modifier
            .fillMaxSize()
            .background(colors.card, RoundedCornerShape(22.dp))
            .border(1.dp, colors.outline, RoundedCornerShape(22.dp))
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (detail != null && !restricted) {
            placeDetail(
                c, detail, theme, picked,
                onTogglePick = { id -> picked = if (id in picked) picked - id else picked + id },
                onBack = { detailId = null },
                onGo = { go(detail, picked) },
            )
            detail.place.regionId?.sidoCode?.let { sido ->
                item {
                    SecondaryButton("${CITY_NAMES[sido] ?: "이 도시"}에서 ${theme.displayName} 코스 보기  →", Modifier.fillMaxWidth().padding(bottom = 24.dp), height = 72.dp) {
                        onCourses(sido)
                    }
                }
            }
            return@LazyColumn
        }
        item { Spacer(Modifier.height(8.dp)); Title("마음이 가는 곳부터") }
        item { ThemeTiles(theme, tall = restricted, onPick = { onTheme(it); shuffle++ }) }
        if (restricted) {
            item { Body("주행 중에는 사진 없이 테마만 고를 수 있어요. 정차하면 장소 사진이 보여요.") }
            return@LazyColumn
        }
        val list = cards
        when {
            list == null -> item { Body("${theme.displayName} 장소를 불러오는 중이에요.") }
            list.isEmpty() -> item { Body("${theme.displayName} 테마로 고른 장소가 아직 없어요.") }
            else -> {
                val shown = list.shuffled(kotlin.random.Random(shuffle * 31 + theme.ordinal)).take(THEME_SHOWN)
                items(shown, key = { it.contentId }) { card ->
                    PlaceCard(card, theme, onOpen = { detailId = card.contentId }, hot = hotStats[card.contentId], showHot = settingsState.hotBadge) { go(card, emptyList()) }
                }
                if (list.size > THEME_SHOWN) item {
                    SecondaryButton("다른 곳 보기  (${list.size}곳 중 ${THEME_SHOWN}곳)", Modifier.fillMaxWidth(), height = 72.dp) { shuffle++ }
                }
                // 코스 보기: 이 테마 장소가 가장 많은 도시부터. 도시마다 몇 곳인지 함께 보여 준다.
                val byCity = list.groupBy { it.place.regionId?.sidoCode }.filterKeys { it in CITY_NAMES }
                    .toList().sortedByDescending { it.second.size }
                if (byCity.isNotEmpty()) item {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("${theme.displayName} 코스로 떠나 볼까요?", style = type.placeName, color = colors.text, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            byCity.forEach { (sido, cs) ->
                                val label = "${CITY_NAMES[sido]} 코스  ${cs.size}곳"
                                if (sido == byCity.first().first) PrimaryButton(label, Modifier.weight(1f), height = 72.dp) { onCourses(sido!!) }
                                else SecondaryButton(label, Modifier.weight(1f), height = 72.dp) { onCourses(sido!!) }
                            }
                        }
                    }
                }
            }
        }
        item {
            Text(
                "사진·장소: 한국관광공사 TourAPI(공공누리). 테마 태그는 원본 소개문 근거로 골랐어요.",
                style = type.placeAddress, color = colors.textSub, modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun ThemeTiles(theme: TravelTheme, tall: Boolean, onPick: (TravelTheme) -> Unit) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TravelTheme.entries.chunked(3).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { t ->
                    val on = t == theme
                    Column(
                        Modifier
                            .weight(1f)
                            .height(if (tall) 120.dp else 104.dp)
                            .background(if (on) colors.selectedBg else colors.card, RoundedCornerShape(18.dp))
                            .border(if (on) 2.dp else 1.dp, if (on) colors.primary else colors.outline, RoundedCornerShape(18.dp))
                            .clickable(role = Role.RadioButton) { onPick(t) }
                            .semantics { selected = on },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(t.icon(), contentDescription = null, tint = if (on) colors.primary else colors.text, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.height(6.dp))
                        Text(t.displayName, style = type.placeAddress, color = if (on) colors.primary else colors.text)
                    }
                }
            }
        }
    }
}

/** 사진(원본 그대로, Type3는 자르지 않음). 카드·상세 공통. */
@Composable
internal fun ThemePhoto(card: ThemePlaceCard, modifier: Modifier = Modifier, ratio: Float = 16f / 9f, sourceLabel: Boolean = false) {
    val colors = LocalChaumColors.current
    val context = LocalContext.current
    // 앱에 든 사진 → 없으면 관광공사 사진 주소를 받아 캐시(3번: 자동 분류 장소는 앱에 사진 파일이 없음).
    var loading by androidx.compose.runtime.remember(card.contentId) { androidx.compose.runtime.mutableStateOf(true) }
    val bitmap by produceState<ImageBitmap?>(null, card.contentId) {
        value = withContext(Dispatchers.IO) {
            card.localImageAsset?.let { path -> loadAsset(context, path) }
                ?: loadRemotePhoto(context, card.contentId, card.imageUrl)
        }
        loading = false
    }
    // 공공누리 1유형은 잘라도 되고, 3유형(변경 금지)·미확인은 자르지 않는다.
    val crop = card.imageAllowsCropping || (card.localImageAsset == null && card.imageLicense == "Type1")
    Box(modifier.fillMaxWidth().aspectRatio(ratio).background(colors.surface2, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
        bitmap?.let {
            Image(it, contentDescription = card.place.name, contentScale = if (crop) ContentScale.Crop else ContentScale.Fit, modifier = Modifier.fillMaxSize())
            // 사진 출처(시안: 사진 왼쪽 아래 작은 라벨). 예시 사진이 아니라 실제 장소 사진임을 알린다.
            if (sourceLabel) Text(
                "${card.place.name} · 한국관광공사", style = LocalChaumType.current.placeAddress, color = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
                    .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        } ?: Text(if (loading) "사진 불러오는 중" else "사진 없음", style = LocalChaumType.current.placeAddress, color = colors.textSub)
    }
}

/** 사진 카드: 누르면 상세. 이름·테마 근거·출처 + "여기로 떠나기". */
@Composable
private fun PlaceCard(
    card: ThemePlaceCard, theme: TravelTheme, onOpen: () -> Unit,
    hot: com.team.travelmap.curation.hot.HotPlaces.HotStat? = null, showHot: Boolean = false, onGo: () -> Unit,
) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, colors.outline, RoundedCornerShape(20.dp))
            .clip(RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClickLabel = "${card.place.name} 자세히", onClick = onOpen),
    ) {
        ThemePhoto(card)
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(card.place.name, style = type.placeName, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    HotBadge(hot, showHot, Modifier.padding(start = 8.dp))
                }
                Text("자세히 ›", style = type.placeAddress, color = colors.primary)
            }
            card.themeReasons[theme]?.let { Text(it, style = type.placeAddress, color = colors.textSub, maxLines = 2, overflow = TextOverflow.Ellipsis) }
            if (card.imageAttribution.isNotBlank()) {
                Text(card.imageAttribution, style = type.placeAddress, color = colors.textSub.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(6.dp))
            PrimaryButton("여기로 떠나기  →", Modifier.fillMaxWidth(), height = 72.dp, onClick = onGo)
        }
    }
}

/** 큰 원본을 화면 폭에 맞게 줄여 읽는다(원본 파일은 바꾸지 않음). */
internal fun loadAsset(context: Context, path: String): ImageBitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 900) sample *= 2
    context.assets.open(path).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        ?.asImageBitmap()
}.getOrNull()
