package com.team.travelmap.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.team.travelmap.AppContainer
import com.team.travelmap.core.model.ThemePlaceCard
import com.team.travelmap.core.model.TravelTheme
import com.team.travelmap.navi.NaviRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 상세 화면의 "함께 들르기 좋은 곳" 범위(직선, 사용자 결정 3km). */
private const val NEARBY_KM = 3.0

/**
 * 담당: 1번. 테마 사진 카드를 누르면 나오는 장소 상세(정차 중에만).
 * 사진·소개·운영 정보(관광공사 원문) → 위치(지도 핀) → 주변 3km 안 함께 들르기 좋은 곳(경유지로 최대 2곳) → 떠나기.
 */
internal fun LazyListScope.placeDetail(
    c: AppContainer,
    card: ThemePlaceCard,
    theme: TravelTheme,
    picked: List<String>,
    onTogglePick: (String) -> Unit,
    onBack: () -> Unit,
    onGo: () -> Unit,
) {
    item {
        Row(
            Modifier.fillMaxWidth().height(64.dp).clickable(role = Role.Button, onClickLabel = "테마 목록으로", onClick = onBack),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, tint = LocalChaumColors.current.text)
            Spacer(Modifier.width(12.dp))
            Text("${theme.displayName} 목록으로", style = LocalChaumType.current.body, color = LocalChaumColors.current.text)
        }
    }
    item { ThemePhoto(card) }
    item { DetailHead(c, card, theme) }
    item { DetailInfo(card) }
    item { DetailLocation(c, card) }
    item { NearbyHeader(picked.size) }
    item { NearbyList(c, card, picked, onTogglePick) }
    item {
        val label = if (picked.isEmpty()) "여기로 떠나기  →" else "${picked.size}곳 들러서 떠나기  →"
        PrimaryButton(label, Modifier.fillMaxWidth().padding(bottom = 24.dp), height = 80.dp, onClick = onGo)
    }
}

@Composable
internal fun DetailHead(c: AppContainer, card: ThemePlaceCard, theme: TravelTheme) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    val region = card.place.regionId?.let { c.regions.find(it) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(card.place.name, style = type.panelTitle, color = colors.text)
        Text(listOfNotNull(region?.name, card.place.address).joinToString("  "), style = type.placeAddress, color = colors.textSub)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            card.themes.sortedBy { it != theme }.forEach { Pill(it.displayName) }
        }
        card.themeReasons[theme]?.let { Text("추천 이유  $it", style = type.body, color = colors.text) }
        if (card.imageAttribution.isNotBlank()) {
            Text(card.imageAttribution, style = type.placeAddress, color = colors.textSub)
        }
    }
}

@Composable
internal fun DetailInfo(card: ThemePlaceCard) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    var expanded by rememberSaveable(card.contentId) { mutableStateOf(false) }
    val rows = OPERATING_LABELS.mapNotNull { (key, label) ->
        card.operatingInfo[key]?.plainText()?.takeIf { it.isNotBlank() }?.let { label to if (key.startsWith("event")) it.koreanDate() else it }
    }.distinctBy { it.first }
    MasilCard(Modifier.fillMaxWidth()) {
        Text("소개", style = type.placeName, color = colors.text, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        val intro = card.introduction.plainText()
        Text(intro, style = type.body, color = colors.text, maxLines = if (expanded) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis)
        if (intro.length > 120) {
            Text(
                if (expanded) "접기" else "더 보기", style = type.body, color = colors.primary,
                modifier = Modifier.heightIn(min = 56.dp).clickable(role = Role.Button) { expanded = !expanded }.padding(top = 12.dp),
            )
        }
        if (rows.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = colors.divider)
            rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Text(label, style = type.placeAddress, color = colors.textSub, modifier = Modifier.width(150.dp))
                    Text(value, style = type.placeAddress, color = colors.text, modifier = Modifier.weight(1f))
                }
            }
            Text("운영 정보는 관광공사 원문이에요. 실제 운영 여부는 방문 전에 확인해 주세요.", style = type.placeAddress, color = colors.textSub)
        }
    }
}

@Composable
internal fun DetailLocation(c: AppContainer, card: ThemePlaceCard) {
    val type = LocalChaumType.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("위치", style = type.placeName, color = LocalChaumColors.current.text, fontWeight = FontWeight.Bold)
        ChaumMap(
            c, card.place.regionId?.sidoCode, card.place.regionId,
            title = card.place.regionId?.let { c.regions.find(it)?.name } ?: "위치",
            onRegionTap = null,
            modifier = Modifier.fillMaxWidth().height(380.dp),
            markers = listOf(card.place.point),
        )
    }
}

@Composable
private fun NearbyHeader(count: Int) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("함께 들르기 좋은 곳", style = type.placeName, color = colors.text, fontWeight = FontWeight.Bold)
            Text("주변 3km 안, 직선거리 순. 누르면 경유지로 담아요.", style = type.placeAddress, color = colors.textSub)
        }
        Pill("$count / ${NaviRepository.MAX_WAYPOINTS}")
    }
}

@Composable
private fun NearbyList(c: AppContainer, card: ThemePlaceCard, picked: List<String>, onTogglePick: (String) -> Unit) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    val list by produceState<List<Nearby>?>(null, card.contentId) {
        value = withContext(Dispatchers.IO) { c.tourism.nearby(card.place.point, NEARBY_KM, 6, excludeId = card.contentId) }
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when {
            list == null -> Body("주변 장소를 찾는 중이에요.")
            list!!.isEmpty() -> Body("3km 안에 관광공사에 등록된 곳이 없어요.")
            else -> list!!.forEach { n ->
                val on = n.entry.contentId in picked
                val full = picked.size >= NaviRepository.MAX_WAYPOINTS && !on
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 88.dp)
                        .background(if (on) colors.selectedBg else colors.card, RoundedCornerShape(18.dp))
                        .border(if (on) 2.dp else 1.dp, if (on) colors.primary else colors.outline, RoundedCornerShape(18.dp))
                        .clickable(enabled = !full, role = Role.Checkbox) { onTogglePick(n.entry.contentId) }
                        .semantics { selected = on }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(n.entry.name, style = type.placeName, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${n.kind}  직선 ${formatKm(n.km)}", style = type.placeAddress, color = colors.textSub)
                    }
                    Box(
                        Modifier.size(36.dp).then(
                            if (on) Modifier.background(colors.primary, CircleShape) else Modifier.border(2.dp, colors.outline, CircleShape),
                        ),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (on) Text("${picked.indexOf(n.entry.contentId) + 1}", style = type.placeAddress, color = colors.onPrimary)
                    }
                }
            }
        }
    }
}

/** 코스 장소 상세(여행 탭). 테마 상세와 같은 사진·소개·운영 정보·위치를 세로 스크롤 안에서 보여 준다. */
@Composable
internal fun CourseStopDetail(c: AppContainer, card: ThemePlaceCard, theme: TravelTheme, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(
            Modifier.fillMaxWidth().height(64.dp).clickable(role = Role.Button, onClickLabel = "코스로 돌아가기", onClick = onBack),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null, tint = LocalChaumColors.current.text)
            Spacer(Modifier.width(12.dp))
            Text("코스로 돌아가기", style = LocalChaumType.current.body, color = LocalChaumColors.current.text)
        }
        ThemePhoto(card)
        DetailHead(c, card, theme)
        DetailInfo(card)
        DetailLocation(c, card)
    }
}
