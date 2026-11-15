package com.team.travelmap.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Place
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.ArrowForward
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.team.travelmap.AppContainer
import com.team.travelmap.core.model.CourseRecommendations
import com.team.travelmap.core.model.CourseRouteCriterion
import com.team.travelmap.core.model.CourseWarning
import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.core.model.ThemeCourse
import com.team.travelmap.core.model.TravelTheme
import com.team.travelmap.curation.ThemeRecommendation
import com.team.travelmap.curation.ThemeRecommender
import com.team.travelmap.curation.weather.CityPrecipitation
import com.team.travelmap.curation.weather.CourseWeatherResult
import com.team.travelmap.curation.weather.CourseWeatherStatus
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val CITIES = listOf("11" to "서울", "30" to "대전", "26" to "부산")

/**
 * 담당: 1번. 여행 탭 대기 화면(Codex 확정 시안 01: 코스 선택).
 * 시작 질문(누구랑·얼마나·어디로) + 테마 → 3번 ThemeCoursePlanner 로 코스 3~5개 → 하나 고르기,
 * 마음에 든 곳 남기고 나머지 다시 추천(이슈 #9 결정), 이 코스로 떠나기. 아래에 기존 룰렛("우연히 떠나기").
 * 시간·거리는 CourseEstimates 추정값이며 화면에 "추정"으로 표시한다.
 */
@Composable
internal fun ColumnScope.CoursePanel(
    c: AppContainer, initialTheme: TravelTheme, onTheme: (TravelTheme) -> Unit,
    /** 테마 탭 "코스 보기"에서 넘어온 도시(시·도 코드). null 이면 대전. */
    initialCity: String? = null,
    roulette: @Composable () -> Unit,
) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    val settings by c.settings.state.collectAsStateWithLifecycle()
    val hotStats by c.hot.stats.collectAsStateWithLifecycle()
    // 검색으로 정해지는 값. 테마 탭과 별개(여행 탭은 검색 결과에 맞는 추천 테마를 보여 준다).
    var query by rememberSaveable { mutableStateOf("") }
    var searched by rememberSaveable { mutableStateOf(false) }
    var theme by rememberSaveable { mutableStateOf(initialTheme) }
    var companion by rememberSaveable { mutableStateOf(Companion.TWO) }
    var length by rememberSaveable { mutableStateOf(TripLength.HALF_DAY) }
    var days by rememberSaveable { mutableIntStateOf(1) }
    var city by rememberSaveable(initialCity) { mutableStateOf(initialCity ?: "30") }
    // 검색할 때마다 늘어난다. 검색어에 테마가 없으면 추천 테마 중 하나를 알아서 고른다(3번 점수).
    var searchCount by rememberSaveable { mutableIntStateOf(0) }
    var queryTheme by rememberSaveable { mutableStateOf<TravelTheme?>(null) }
    // 도시 가운데의 여행 시간대 비·눈 예보(테마 순위용). 꺼짐·키 없음·실패면 null 로 두고 반영하지 않는다.
    val cityRain = remember(c) { CityPrecipitation(c.weather::load) }
    val rain by produceState<Boolean?>(null, city, length, days, settings.weatherAware) {
        value = null
        val cards = withContext(Dispatchers.Default) {
            runCatching { c.themeCuration.cards().filter { it.place.regionId?.sidoCode == city } }.getOrDefault(emptyList())
        }
        if (cards.isEmpty()) return@produceState
        val center = GeoPoint(cards.map { it.place.point.latitude }.average(), cards.map { it.place.point.longitude }.average())
        val start = LocalDateTime.now(ZoneId.of("Asia/Seoul")).withNano(0)
        val hours = if (days >= 2) 10L else length.minutes / 60L
        value = cityRain.expected(center, start, start.plusHours(hours), settings.weatherAware)
    }
    var index by rememberSaveable(theme, length, city) { mutableIntStateOf(0) }
    var locked by rememberSaveable(theme, length, city) { mutableStateOf(listOf<String>()) }
    var reroll by rememberSaveable(theme, length, city) { mutableIntStateOf(0) }
    var routeCriterion by rememberSaveable(theme, length, city) { mutableStateOf(CourseRouteCriterion.FASTEST) }
    var previous by remember(theme, length, city) { mutableStateOf<ThemeCourse?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var planningFailed by remember { mutableStateOf(false) }
    var departureAt by remember(theme, length, city, reroll) { mutableStateOf<LocalDateTime?>(null) }
    // 기준·날씨 설정만 바꾸면 같은 장소 구성과 출발 시각을 재사용한다.
    var basePlan by remember(theme, length, city, reroll) { mutableStateOf<CourseRecommendations?>(null) }
    var weatherLoading by remember { mutableStateOf(false) }
    var weatherResult by remember { mutableStateOf<CourseWeatherResult?>(null) }
    // 코스 안 장소를 누르면 그 장소 상세(사진·소개·운영 정보·위치)를 연다.
    var openId by rememberSaveable { mutableStateOf<String?>(null) }

    val result by produceState<CourseRecommendations?>(null, theme, length, city, reroll, settings.weatherAware, routeCriterion) {
        value = null
        planningFailed = false
        weatherLoading = false
        weatherResult = null
        try {
            val base = basePlan ?: run {
                val start = departureAt ?: LocalDateTime.now(ZoneId.of("Asia/Seoul")).withNano(0)
                departureAt = start
                withContext(Dispatchers.Default) { plan(c, theme, length, city, start, previous, locked.toSet()) }
                    .also { basePlan = it }
            }
            val selectedRoutes = base.forRoute(routeCriterion)
            weatherLoading = settings.weatherAware && selectedRoutes.courses.isNotEmpty()
            val report = c.courseWeather.apply(selectedRoutes, locked.toSet(), settings.weatherAware)
            weatherResult = report
            value = report.recommendations
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            planningFailed = true
        } finally {
            weatherLoading = false
        }
        index = 0
    }

    val scroll = rememberScrollState()
    // 상세를 열고 닫을 때 맨 위로(돌아가기 버튼이 보이게).
    androidx.compose.runtime.LaunchedEffect(openId) { scroll.scrollTo(0) }
    Column(Modifier.weight(1f).verticalScroll(scroll), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(
            buildAnnotatedString {
                append("오늘의 ")
                withStyle(SpanStyle(background = colors.selectedBg, color = colors.primary)) { append("빈칸") }
                append("을\n어디로 채워볼까요?")
            },
            style = type.panelTitle, color = colors.text,
        )
        // 시작 질문은 타이핑: 누구랑·얼마나·어디로를 한 문장으로. 지금은 단어 규칙, 출시 때 AI 해석기로 교체.
        fun search() {
            val q = RuleTripQueryParser.parse(query)
            q.companion?.let { companion = it }
            q.cityCode?.let { city = it }
            q.days?.let { days = it }
            q.length?.let { length = it }
            q.theme?.let { theme = it }
            queryTheme = q.theme
            searchCount++
            searched = true
        }
        TripSearchBox(query, onQuery = { query = it }, onSearch = ::search, onExample = { query = it; search() })
        // 노트북에서 보낸 글자(개발자 모드, tools/dev_text.sh)를 검색창에 넣고 바로 추천.
        val external by DevFlags.externalText.collectAsStateWithLifecycle()
        androidx.compose.runtime.LaunchedEffect(external) {
            external?.let { query = it; search(); DevFlags.externalText.value = null }
        }
        if (searched) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Pill(companion.label)
                Pill(if (days >= 2) "${days - 1}박 ${days}일" else length.label)
                Pill(CITIES.first { it.first == city }.second)
            }
        }
        // 검색 조건에 맞는 추천 테마 여러 개(3번 ThemeRecommender: 누구랑·계절·날씨·장소 수). 고르면 그 테마 코스.
        RecommendedThemes(c, city, companion, rain, theme, searchCount, queryTheme) { theme = it }
        if (days < 2) Choice(
            if (routeCriterion == CourseRouteCriterion.FASTEST) "이동시간 우선 · 추정" else "이동거리 우선 · 추정",
            listOf("이동시간 우선 · 추정", "이동거리 우선 · 추정"), Modifier.fillMaxWidth(),
        ) { routeCriterion = CourseRouteCriterion.entries[it] }

        val r = result
        if (days >= 2) MultiDayCourses(c, theme, city, days) else when {
            planningFailed -> {
                Body("코스 정보를 불러오지 못했어요. 다시 시도해 주세요.")
                SecondaryButton("다시 추천", Modifier.fillMaxWidth(), height = 72.dp) { reroll++ }
            }
            r == null -> Body(if (weatherLoading) "도착 시간대의 날씨를 확인하고 있어요." else "코스를 짜는 중이에요.")
            r.courses.isEmpty() -> {
                Body(emptyReason(r, theme, city))
                SecondaryButton("다시 추천", Modifier.fillMaxWidth(), height = 72.dp) { reroll++ }
            }
            else -> {
                val course = r.courses[index.coerceIn(0, r.courses.lastIndex)]
                val open = course.stops.firstOrNull { it.card.contentId == openId }?.card
                if (open != null) {
                    CourseStopDetail(c, open, theme) { openId = null }
                    return@Column
                }
                CourseCard(course, theme, index, r.courses.size, locked,
                    hot = hotStats, showHot = settings.hotBadge,
                    onPrev = { if (index > 0) index-- }, onNext = { if (index < r.courses.lastIndex) index++ },
                    onToggleLock = { id -> locked = if (id in locked) locked - id else locked + id },
                    onOpen = { id -> openId = id },
                    firstLeg = c.navi::tripCost,
                    summary = "${CITIES.first { it.first == city }.second} · ${theme.displayName} · ${length.label}",
                )
                val lockedNames = course.stops.filter { it.card.contentId in locked }.map { it.card.place.name }
                if (lockedNames.isNotEmpty()) LockedNotice(lockedNames)
                if (CourseWarning.NOT_ENOUGH_COURSES in r.warnings) {
                    Text("이 조건으로는 코스가 ${r.courses.size}개만 나와요. 장소 데이터가 더 들어오면 늘어나요.", style = type.placeAddress, color = colors.textSub)
                }
                if (CourseWarning.NO_NEW_ALTERNATIVE in r.warnings) {
                    Text("남긴 곳 외에 새로 바꿀 장소가 없어요.", style = type.placeAddress, color = colors.textSub)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    SecondaryButton(if (locked.isEmpty()) "다른 곳 찾아보기" else "나머지 다시 추천", Modifier.weight(1f), height = 80.dp) {
                        previous = course; reroll++
                    }
                    PrimaryButton("이 코스로 떠나기", Modifier.weight(1f), height = 80.dp) {
                        note = startCourse(c, course)
                    }
                }
                note?.let { Text(it, style = type.placeAddress, color = colors.textSub) }
            }
        }
        departureAt?.let { start ->
            Text("선택한 도시 안에서 ${start.format(DateTimeFormatter.ofPattern("HH:mm"))} 출발하는 일정이에요. 도시까지 오는 시간은 별도예요.",
                style = type.placeAddress, color = colors.textSub)
        }
        weatherResult?.takeIf { r?.courses?.isNotEmpty() == true }?.let { report ->
            val wet = report.courses.any { row -> row.visits.any { it.precipitationExpected == true } }
            val indoors = report.courses.any { it.indoorPriority }
            val notice = when (report.status) {
                CourseWeatherStatus.DISABLED -> "날씨 반영이 꺼져 있어요."
                CourseWeatherStatus.UNAVAILABLE -> "날씨를 확인하지 못해 기본 추천을 보여드려요."
                CourseWeatherStatus.PARTIAL -> "확인된 시간대의 예보만 반영했어요. 일부 장소는 날씨 확인이 필요해요."
                CourseWeatherStatus.AVAILABLE -> when {
                    wet && indoors -> "비·눈 예보를 반영해 실내 공간이 확인된 코스를 먼저 보여드려요."
                    wet -> "비·눈 예보가 있어요. 실내 공간이 확인된 보조 장소가 부족해 기본 순서를 유지했어요."
                    else -> "도착 전후 시간대 예보를 확인했어요."
                }
            }
            Text(notice, style = type.placeAddress, color = colors.textSub)
            report.snapshots.firstOrNull()?.let { snapshot ->
                Text("기상청 예보 · ${snapshot.issuedAt.format(DateTimeFormatter.ofPattern("MM/dd HH:mm"))} 발표",
                    style = type.placeAddress, color = colors.textSub)
            }
            if (report.courses.any { row -> row.visits.any { it.userSelected && it.precipitationExpected == true } }) {
                Text(if (indoors) "직접 남긴 장소는 유지하고 보조 장소에 실내 우선을 반영했어요."
                    else "비·눈 예보가 있는 직접 선택 장소는 유지했어요. 방문 전 날씨를 확인해 주세요.",
                    style = type.placeAddress, color = colors.textSub)
            }
        }
        Text("시간·거리는 직선거리로 계산한 추정이에요. 실제 경로 시간은 출발하면 내비가 알려 줘요.", style = type.placeAddress, color = colors.textSub)
        HorizontalDivider(color = colors.divider)
        Text("우연히 떠나기", style = type.placeName, color = colors.text, fontWeight = FontWeight.Bold)
        roulette()
    }
}

/** 코스 카드(시안 01): 오늘의 추천 n/전체, 첫 장소 사진, 제목·시간·거리(추정), 장소 목록 + 남기기 표시. */
@Composable
private fun CourseCard(
    course: ThemeCourse, theme: TravelTheme, index: Int, total: Int, locked: List<String>,
    onPrev: () -> Unit, onNext: () -> Unit, onToggleLock: (String) -> Unit, onOpen: (String) -> Unit,
    lockable: Boolean = true, badge: String = "오늘의 추천",
    /** 3번 "핫" 통계(contentId → 통계)와 설정. 비어 있으면 배지 없음. */
    hot: Map<String, com.team.travelmap.curation.hot.HotPlaces.HotStat> = emptyMap(), showHot: Boolean = false,
    /** 사진 위 요약 칩(예: "대전 · 힐링 · 반나절"). */
    summary: String? = null,
    /** 지금 위치 → 첫 장소 실제 경로 비용(2번 NaviRepository.tripCost). null 이면 줄을 숨긴다(2일차 이후 등). */
    firstLeg: (suspend (com.team.travelmap.core.model.Place) -> com.team.travelmap.core.model.TripCost?)? = null,
) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    val first = course.stops.firstOrNull()?.card?.place
    val leg by produceState<com.team.travelmap.core.model.TripCost?>(null, first, firstLeg) {
        value = if (first != null && firstLeg != null) runCatching { firstLeg(first) }.getOrNull() else null
    }
    MasilCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(badge)
            Spacer(Modifier.weight(1f))
            if (total > 1) ArrowButton(Icons.AutoMirrored.Outlined.ArrowBack, "이전 코스", enabled = index > 0, onClick = onPrev)
            if (total > 1) Text("%02d / %02d".format(index + 1, total), style = type.placeName, color = colors.text, modifier = Modifier.padding(horizontal = 8.dp))
            if (total > 1) ArrowButton(Icons.AutoMirrored.Outlined.ArrowForward, "다음 코스", enabled = index < total - 1, onClick = onNext)
        }
        Spacer(Modifier.height(12.dp))
        summary?.let {
            Row(
                Modifier.background(colors.selectedBg, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Place, null, tint = colors.primary, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(6.dp))
                Text(it, style = type.placeAddress, color = colors.primary)
            }
            Spacer(Modifier.height(12.dp))
        }
        ThemePhoto(leadStop(course, theme).card, ratio = 2f, sourceLabel = true)
        Spacer(Modifier.height(16.dp))
        Text(courseTitle(course, theme), style = type.placeName, color = colors.text, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        // 일정(머무는 시간 + 이동 추정)과 코스 거리(추정)를 따로
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            InfoBox(Icons.Outlined.CalendarMonth, "일정", "약 ${formatMinutes(course.totalMinutes.toInt())}", Modifier.weight(1f))
            InfoBox(Icons.Outlined.Route, "코스 거리", "추정 ${"%.0f".format(course.distanceKm)} km", Modifier.weight(1f))
        }
        Text(
            "머무는 시간 ${formatMinutes(course.stayMinutes)} + 이동 ${formatMinutes(course.travelMinutes.toInt())}(추정) · 거리는 직선 기준 추정값",
            style = type.placeAddress, color = colors.textSub, modifier = Modifier.padding(top = 8.dp),
        )
        // 출발 전 실제 값: 지금 위치에서 첫 장소까지(코스 안 이동·머무는 시간은 위 추정값)
        leg?.let { l ->
            Text(
                "지금 위치에서 첫 장소까지 ${formatMinutes((l.durationSeconds + 59) / 60)} · ${formatKm(l.distanceMeters / 1000.0)}" +
                    if (l.estimated) "  ·  추정" else "  ·  내비 경로",
                style = type.placeAddress, color = if (l.estimated) colors.textSub else colors.primary,
            )
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider(color = colors.divider)
        course.stops.forEachIndexed { i, stop ->
            val id = stop.card.contentId
            val on = id in locked
            val last = i == course.stops.lastIndex
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Min)
                    .clickable(role = Role.Button, onClickLabel = "${stop.card.place.name} 자세히") { onOpen(id) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 번호 원과 이어지는 세로선(타임라인)
                Box(Modifier.width(44.dp).fillMaxHeight(), contentAlignment = Alignment.Center) {
                    Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.weight(1f).width(2.dp).background(if (i == 0) colors.card.copy(alpha = 0f) else colors.primary.copy(alpha = 0.35f)))
                        Box(Modifier.weight(1f).width(2.dp).background(if (last) colors.card.copy(alpha = 0f) else colors.primary.copy(alpha = 0.35f)))
                    }
                    Box(
                        Modifier.size(36.dp).background(if (i == 0) colors.primary else colors.card, CircleShape)
                            .border(2.dp, colors.primary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text("${i + 1}", style = type.placeAddress, color = if (i == 0) colors.onPrimary else colors.primary, fontWeight = FontWeight.Bold) }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(stop.card.place.name, style = type.placeName, color = colors.text, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        HotBadge(hot[stop.card.contentId], showHot, Modifier.padding(start = 8.dp))
                    }
                    Text("머무는 시간 ${stop.stayMinutes}분 · ${stop.card.themes.joinToString(" ") { it.displayName }}", style = type.placeAddress, color = colors.textSub, maxLines = 1)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (on) Text(
                            "남긴 곳", style = type.placeAddress, color = colors.primary,
                            modifier = Modifier.background(colors.selectedBg, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                        )
                        // 책갈피만 따로 눌러 남기기(줄을 누르면 상세)
                        if (lockable) Box(
                            Modifier.size(72.dp).clickable(role = Role.Checkbox, onClickLabel = if (on) "남기기 해제" else "이 곳 남기기") { onToggleLock(id) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (on) Icons.Filled.Bookmark else Icons.Outlined.BookmarkBorder, contentDescription = null,
                                tint = if (on) colors.primary else colors.textSub, modifier = Modifier.size(36.dp),
                            )
                        }
                    }
                    Text("자세히 ›", style = type.placeAddress, color = colors.textSub, modifier = Modifier.padding(end = 12.dp, bottom = 8.dp))
                }
            }
            if (!last) HorizontalDivider(color = colors.divider, modifier = Modifier.padding(start = 56.dp))
        }
        Text(if (lockable) "장소를 누르면 자세히, 책갈피를 누르면 남겨요." else "장소를 누르면 자세히 볼 수 있어요.", style = type.placeAddress, color = colors.textSub, modifier = Modifier.padding(top = 8.dp))
    }
}

/** 코스 요약 칸(시안 01): 아이콘 + 작은 제목 + 값. */
@Composable
private fun InfoBox(icon: ImageVector, label: String, value: String, modifier: Modifier) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    Row(
        modifier.border(1.dp, colors.outline, RoundedCornerShape(14.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = colors.primary, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(label, style = type.placeAddress, color = colors.textSub)
            Text(value, style = type.placeName, color = colors.text, fontWeight = FontWeight.Bold, maxLines = 1)
        }
    }
}

/** 남긴 곳 안내(시안 01): 다시 추천해도 남긴 장소는 그대로. */
@Composable
private fun LockedNotice(names: List<String>) {
    val colors = LocalChaumColors.current
    Row(
        Modifier.fillMaxWidth().background(colors.selectedBg, RoundedCornerShape(14.dp)).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Outlined.BookmarkBorder, null, tint = colors.primary, modifier = Modifier.size(26.dp))
        Spacer(Modifier.width(10.dp))
        Text("${names.joinToString(", ")}은(는) 남겨두고 다시 추천해요", style = LocalChaumType.current.placeAddress, color = colors.primary)
    }
}

@Composable
private fun ArrowButton(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalChaumColors.current
    Box(
        Modifier.size(64.dp).clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = if (enabled) colors.text else colors.divider) }
}

/** 작은 선택 칩: 누르면 목록이 펼쳐진다(정차 중 화면). */
@Composable
private fun Choice(value: String, options: List<String>, modifier: Modifier, onPick: (Int) -> Unit) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().height(64.dp)
                .background(colors.card, RoundedCornerShape(16.dp))
                .border(1.dp, colors.outline, RoundedCornerShape(16.dp))
                .clickable(role = Role.DropdownList) { open = true }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, style = type.placeAddress, color = colors.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Icon(Icons.Outlined.KeyboardArrowDown, contentDescription = null, tint = colors.textSub)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, label ->
                DropdownMenuItem(
                    text = { Text(label, style = type.body, color = colors.text) },
                    onClick = { open = false; onPick(i) },
                    modifier = Modifier.heightIn(min = 64.dp),
                )
            }
        }
    }
}

/** 코스 대표 장소: 고른 테마에 맞는 첫 장소(없으면 첫 장소). 사진·제목에 쓴다. */
private fun leadStop(course: ThemeCourse, theme: TravelTheme) = course.stops.firstOrNull { theme in it.card.themes } ?: course.stops.first()

private fun courseTitle(course: ThemeCourse, theme: TravelTheme): String {
    val lead = leadStop(course, theme).card.place.name
    return if (course.stops.size == 1) lead else "$lead 중심 ${course.stops.size}곳 코스"
}

private fun formatMinutes(min: Int): String = when {
    min < 60 -> "${min}분"
    min % 60 == 0 -> "${min / 60}시간"
    else -> "${min / 60}시간 ${min % 60}분"
}

private fun emptyReason(r: CourseRecommendations, theme: TravelTheme, city: String): String =
    courseEmptyReason(r, theme, CITIES.first { it.first == city }.second)

/**
 * 코스 조합(3번 ThemeCoursePlanner). 후보 = 고른 도시의 테마 카드 전체, 고른 테마는 점수 2 그 외 1(±25% 흔들기).
 * 식당·카페는 여행 시간에 따라(이슈 #9): 2시간 없음, 반나절 카페 또는 식당 하나, 하루 둘 다(그 도시에 후보가 있을 때만).
 * 출발 기준은 도시 안 후보들의 가운데(출발지에서 도시까지 오는 시간은 따로 — 이슈 #9 미정).
 */
private fun plan(c: AppContainer, theme: TravelTheme, length: TripLength, city: String,
                 departureAt: LocalDateTime, previous: ThemeCourse?, locked: Set<String>): CourseRecommendations =
    planDemoCourses(
        allCards = c.themeCuration.cards(travelDate = departureAt.toLocalDate()),
        theme = theme, length = length, city = city, departureAt = departureAt,
        visits = c.operatingHours, isDemoReady = c.operatingHours::isDemoReady,
        previous = previous, locked = locked,
        // 코스를 새로 짤 때(다른 곳 찾아보기, 테마·시간·도시 변경)마다 다른 조합이 나오게 장소 점수를 조금씩 흔든다.
        random = Random.Default,
    )

/** 전체 경유지를 TripCoordinator에 전달한다. 실제 내비 요청은 TripLegs가 한 곳씩 나눈다. */
private fun startCourse(c: AppContainer, course: ThemeCourse): String? {
    val plan = course.toTripPlan() ?: return "코스에 장소가 없어요. 다시 추천해 주세요."
    val region = plan.destination.regionId?.let(c.regions::find) ?: return "목적지 구역을 찾지 못했어요."
    c.trip.plan(region, plan.destination, plan.waypoints)
    return null
}

/** 검색창: "여자친구랑 1박2일 부산"처럼 한 문장. 정차 중 화면에서만 보인다(주행 중엔 패널 전체가 안내 문구). */
@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
private fun TripSearchBox(query: String, onQuery: (String) -> Unit, onSearch: () -> Unit, onExample: (String) -> Unit) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        androidx.compose.material3.OutlinedTextField(
            value = query, onValueChange = onQuery, singleLine = true,
            textStyle = type.body,
            placeholder = { Text("예: 여자친구랑 1박2일 부산", style = type.body, color = colors.textSub) },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch() }),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.weight(1f),
        )
        PrimaryButton("추천받기", Modifier.width(150.dp), height = 64.dp, onClick = onSearch)
    }
    Text("누구랑·얼마나·어디로를 한 문장으로 적어 주세요. 예시를 누르면 바로 추천해요.", style = type.placeAddress, color = colors.textSub)
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("여자친구랑 1박2일 부산", "가족이랑 반나절 대전 맛집", "친구들이랑 2박 3일 서울 역사").forEach { ex ->
            Text(
                ex, style = type.placeAddress, color = colors.primary,
                modifier = Modifier.heightIn(min = 56.dp).background(colors.selectedBg, RoundedCornerShape(50))
                    .clickable(role = Role.Button) { onExample(ex) }.padding(horizontal = 18.dp, vertical = 14.dp),
            )
        }
    }
}

/**
 * 검색한 조건에서 추천하는 테마들. 순서는 3번 ThemeRecommender(누구랑 우대·지금 계절·비·눈이면 실내·장소 수).
 * 검색어에 테마가 없으면 검색할 때마다 상위 테마 중 하나를 점수 비례로 알아서 고른다(같은 조건에도 매번 다를 수 있음).
 * 지금 테마가 이 도시에 없으면 1위 테마로 바꾼다.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun RecommendedThemes(
    c: AppContainer, city: String, companion: Companion, rain: Boolean?, selected: TravelTheme,
    searchCount: Int, queryTheme: TravelTheme?, onPick: (TravelTheme) -> Unit,
) {
    val colors = LocalChaumColors.current
    val type = LocalChaumType.current
    // 검색 한 번에 한 번만 알아서 고른다(날씨 등으로 순위가 다시 계산돼도 다시 뽑지 않음).
    var handledSearch by rememberSaveable { mutableIntStateOf(0) }
    // 순위에 계산 조건을 함께 담아, 지금 조건(도시·누구랑·날씨)과 같은 순위만 쓴다.
    val key = Triple(city, companion, rain)
    val ranking by produceState<Pair<Triple<String, Companion, Boolean?>, List<ThemeRecommendation>>?>(null, key) {
        value = key to withContext(Dispatchers.Default) {
            runCatching {
                recommendThemes(c.themeCuration.cards(), companion, city,
                    LocalDateTime.now(ZoneId.of("Asia/Seoul")), c.operatingHours::isDemoReady, rain)
            }.getOrDefault(emptyList())
        }
    }
    val list = ranking?.takeIf { it.first == key }?.second ?: return
    Text("${CITIES.first { it.first == city }.second}에서 추천하는 테마", style = type.placeName, color = colors.text, fontWeight = FontWeight.Bold)
    if (list.isEmpty()) { Body("이 도시에는 아직 테마 장소가 없어요."); return }
    androidx.compose.runtime.LaunchedEffect(searchCount, list) {
        val fresh = searchCount != handledSearch
        handledSearch = searchCount
        val wanted = if (fresh) queryTheme else null
        when {
            wanted != null && list.any { it.theme == wanted } -> onPick(wanted)
            // 장소 1곳뿐인 테마는 운영시간에 따라 코스가 비기 쉬워, 2곳 이상인 테마에서 먼저 고른다.
            fresh && searchCount > 0 -> ThemeRecommender.pick(list.filter { it.placeCount >= 2 }.ifEmpty { list }, Random.Default)
                ?.let { onPick(it.theme) }
            list.none { it.theme == selected } -> onPick(list.first().theme)
        }
    }
    if (queryTheme != null && list.none { it.theme == queryTheme }) {
        Body("${CITIES.first { it.first == city }.second}에서는 지금 ${queryTheme.displayName} 코스를 만들 수 없어 다른 테마를 골랐어요.")
    }
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        list.forEach { rec ->
            ChipTab("${rec.theme.displayName}  ${rec.placeCount}", rec.theme == selected, Modifier.heightIn(min = 64.dp).padding(horizontal = 0.dp).width(180.dp)) { onPick(rec.theme) }
        }
    }
    list.firstOrNull { it.theme == selected }?.let { rec ->
        Text(rec.reasons.joinToString(" · "), style = type.placeAddress, color = colors.textSub)
    }
}

/**
 * n일 일정(1박2일 이상): 날마다 하루(8시간) 코스를 하나씩, 앞 날짜에 쓴 장소는 빼고 짠다.
 * 고른 테마 장소가 모자라면 그 도시의 다른 테마로 채운다. TODO(3번): 날짜별 코스 조합·숙소 없는 1박 규칙(이슈 #9) 정식 반영.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MultiDayCourses(c: AppContainer, theme: TravelTheme, city: String, days: Int) {
    val colors = LocalChaumColors.current
    val dayHot by c.hot.stats.collectAsStateWithLifecycle()
    val daySettings by c.settings.state.collectAsStateWithLifecycle()
    val type = LocalChaumType.current
    var day by rememberSaveable(theme, city, days) { mutableIntStateOf(0) }
    var seed by rememberSaveable(theme, city, days) { mutableIntStateOf(0) }
    var openId by rememberSaveable { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    val plans by produceState<List<Pair<TravelTheme, ThemeCourse>?>?>(null, theme, city, days, seed) {
        value = null
        value = withContext(Dispatchers.Default) { runCatching { planDays(c, theme, city, days, seed) }.getOrDefault(emptyList()) }
    }
    val list = plans
    if (list == null) { Body("${days - 1}박 ${days}일 일정을 짜는 중이에요."); return }
    androidx.compose.foundation.layout.FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        (0 until days).forEach { d -> ChipTab("${d + 1}일차", d == day, Modifier.width(120.dp).height(64.dp)) { day = d; openId = null } }
    }
    val today = list.getOrNull(day)
    if (today == null) {
        Body("${day + 1}일차는 이 도시에 남은 장소가 부족해요. 장소 데이터가 더 들어오면 채워져요.")
    } else {
        val (dayTheme, course) = today
        val open = course.stops.firstOrNull { it.card.contentId == openId }?.card
        if (open != null) {
            CourseStopDetail(c, open, dayTheme) { openId = null }
            return
        }
        CourseCard(course, dayTheme, 0, 1, emptyList(), onPrev = {}, onNext = {}, onToggleLock = {}, onOpen = { openId = it },
            hot = dayHot, showHot = daySettings.hotBadge,
            lockable = false, badge = "${day + 1}일차 · ${dayTheme.displayName}",
            firstLeg = if (day == 0) c.navi::tripCost else null)
        SecondaryButton("다른 곳 찾아보기", Modifier.fillMaxWidth(), height = 72.dp) { seed++ }
        PrimaryButton("${day + 1}일차 코스로 떠나기  →", Modifier.fillMaxWidth(), height = 80.dp) { note = startCourse(c, course) }
        note?.let { Text(it, style = type.placeAddress, color = colors.textSub) }
    }
    Text("날짜별 코스는 하루 8시간 기준 추정이에요. 숙소는 추천하지 않아요.", style = type.placeAddress, color = colors.textSub)
}

private fun planDays(c: AppContainer, theme: TravelTheme, city: String, days: Int, seed: Int): List<Pair<TravelTheme, ThemeCourse>?> {
    val start = java.time.LocalDate.now(ZoneId.of("Asia/Seoul"))
    val used = mutableSetOf<String>()
    return (0 until days).map { d ->
        val date = start.plusDays(d.toLong())
        val cards = c.themeCuration.cards(travelDate = date).filter { it.contentId !in used }
        val tryThemes = listOf(theme) + TravelTheme.entries.filter { it != theme }
        tryThemes.firstNotNullOfOrNull { t ->
            val r = planDemoCourses(cards, t, TripLength.FULL_DAY, city, date.atTime(10, 0), c.operatingHours, c.operatingHours::isDemoReady)
            r.courses.takeIf { it.isNotEmpty() }?.let { courses -> t to courses[(seed + d) % courses.size] }
        }?.also { (_, course) -> used += course.stops.map { it.card.contentId } }
    }
}
