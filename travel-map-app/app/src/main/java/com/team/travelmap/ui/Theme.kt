package com.team.travelmap.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import com.team.travelmap.R
import com.team.travelmap.settings.ThemeMode
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 담당: 1번(지도·화면). Codex「마실 디자인 확정」(2026-11-15, approved-green) 기준 색 토큰.
 * 밝은 오프화이트 바탕, 얇은 테두리, 둥근 카드. 초록은 주 버튼·활성 메뉴·경로·방문 완료·토글에만 쓴다.
 * 주간이 기본이고, 시스템(또는 설정)이 야간이면 야간 토큰으로 바꾼다.
 */
@Immutable
data class ChaumColors(
    val window: Color,
    val card: Color,
    val mapBg: Color,
    val text: Color,
    val textSub: Color,
    val primary: Color,
    val onPrimary: Color,
    val count: Color,
    val waypointRow: Color,
    val divider: Color,
    val outline: Color,
    val landActive: Color,
    val landInactive: Color,
    val mapBorder: Color,
    val selectedFill: Color,
    val selectedBorder: Color,
    val visitedFill: Color,
    val visitedBorder: Color,
    val districtLabel: Color,
    val error: Color,
    val isNight: Boolean,
    /** 선택된 칩·탭·카드 바탕(연한 초록). */
    val selectedBg: Color,
    /** 보조 표면: 설정 묶음, 이야기 카드 등. */
    val surface2: Color,
)

val DayColors = ChaumColors(
    window = Color(0xFFF7F8F4), card = Color(0xFFFFFFFF), mapBg = Color(0xFFEEF3EC),
    text = Color(0xFF24302C), textSub = Color(0xFF5B6862),
    primary = Color(0xFF2F6B4F), onPrimary = Color(0xFFFFFFFF),
    count = Color(0xFF2F6B4F), waypointRow = Color(0xFFE4EFE6),
    divider = Color(0xFFCBD4CC), outline = Color(0xFFCBD4CC),
    landActive = Color(0xFFFFFFFF), landInactive = Color(0xFFDDE3DC),
    mapBorder = Color(0xFF9DAAA1), selectedFill = Color(0xFFF2D79A), selectedBorder = Color(0xFF8C6A1F),
    visitedFill = Color(0xFF9FCDA9), visitedBorder = Color(0xFF2F6B4F),
    districtLabel = Color(0xFF24302C), error = Color(0xFFB3261E), isNight = false,
    selectedBg = Color(0xFFE4EFE6), surface2 = Color(0xFFEEF3EC),
)

val NightColors = ChaumColors(
    window = Color(0xFF111714), card = Color(0xFF19221E), mapBg = Color(0xFF1F2B25),
    text = Color(0xFFE9F0EB), textSub = Color(0xFFAAB8B0),
    primary = Color(0xFF7FC39C), onPrimary = Color(0xFF0F2A1C),
    count = Color(0xFF7FC39C), waypointRow = Color(0xFF23392C),
    divider = Color(0xFF34443B), outline = Color(0xFF34443B),
    landActive = Color(0xFFDDE6DF), landInactive = Color(0xFF4A5A51),
    mapBorder = Color(0xFF7D8F84), selectedFill = Color(0xFFE1C17A), selectedBorder = Color(0xFF7A5A16),
    visitedFill = Color(0xFF6FAE85), visitedBorder = Color(0xFF173A28),
    districtLabel = Color(0xFF24302C), error = Color(0xFFFFB4AB), isNight = true,
    selectedBg = Color(0xFF23392C), surface2 = Color(0xFF1F2B25),
)

/**
 * 창 크기별 글자 크기(sp). 가이드 표: 분할(841×1210) / 가로(1682×1210).
 * 크기는 sp, 줄 높이는 최소 1.5배 근처(가이드 값).
 */
@Immutable
data class ChaumType(
    val panelTitle: TextStyle,
    val body: TextStyle,
    val destination: TextStyle,
    val button: TextStyle,
    val cityTab: TextStyle,
    val countNumber: TextStyle,
    val countDenominator: TextStyle,
    val label: TextStyle,
    val guideNumber: TextStyle,
    val placeName: TextStyle,
    val placeAddress: TextStyle,
    val districtSp: Float,
)

/**
 * Asta Sans(42dot, SIL OFL 1.1, licenses/AstaSans-OFL.txt). 가변 글꼴 하나로 가이드의 굵기(400~775)를 모두 낸다.
 */
@OptIn(ExperimentalTextApi::class)
val AstaSans = FontFamily(
    listOf(400, 450, 500, 600, 675, 700, 775, 800).map { w ->
        Font(R.font.asta_sans, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    },
)

private fun style(size: Int, line: Int, weight: Int) =
    TextStyle(fontFamily = AstaSans, fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight(weight))

val SplitType = ChaumType(
    panelTitle = style(39, 59, 775), body = style(25, 38, 450), destination = style(29, 44, 500),
    button = style(27, 41, 675), cityTab = style(24, 36, 675),
    countNumber = style(39, 59, 775), countDenominator = style(25, 38, 500), label = style(22, 33, 500),
    guideNumber = style(41, 62, 775), placeName = style(27, 41, 675), placeAddress = style(21, 32, 400),
    districtSp = 24f,
)

val WideType = SplitType.copy(
    panelTitle = style(45, 68, 775), destination = style(29, 44, 500), button = style(24, 36, 675),
    guideNumber = style(46, 69, 775), districtSp = 28f,
)

val LocalChaumColors = staticCompositionLocalOf { DayColors }
val LocalChaumType = staticCompositionLocalOf { SplitType }

@Composable
fun TravelMapTheme(mode: ThemeMode = ThemeMode.AUTO, content: @Composable () -> Unit) {
    val night = when (mode) {
        ThemeMode.AUTO -> isSystemInDarkTheme()
        ThemeMode.DAY -> false
        ThemeMode.NIGHT -> true
    }
    val chaum = if (night) NightColors else DayColors
    val scheme = if (night) {
        darkColorScheme(
            primary = chaum.primary, onPrimary = chaum.onPrimary, background = chaum.window, surface = chaum.card,
            onBackground = chaum.text, onSurface = chaum.text, outline = chaum.outline, error = chaum.error,
        )
    } else {
        lightColorScheme(
            primary = chaum.primary, onPrimary = chaum.onPrimary, background = chaum.window, surface = chaum.card,
            onBackground = chaum.text, onSurface = chaum.text, outline = chaum.outline, error = chaum.error,
        )
    }
    CompositionLocalProvider(LocalChaumColors provides chaum) {
        // Material 기본 글자(입력창·Mock 패널 등)도 Asta Sans 로
        val base = Typography()
        val typography = Typography(
            displayLarge = base.displayLarge.copy(fontFamily = AstaSans), displayMedium = base.displayMedium.copy(fontFamily = AstaSans),
            displaySmall = base.displaySmall.copy(fontFamily = AstaSans), headlineLarge = base.headlineLarge.copy(fontFamily = AstaSans),
            headlineMedium = base.headlineMedium.copy(fontFamily = AstaSans), headlineSmall = base.headlineSmall.copy(fontFamily = AstaSans),
            titleLarge = base.titleLarge.copy(fontFamily = AstaSans), titleMedium = base.titleMedium.copy(fontFamily = AstaSans),
            titleSmall = base.titleSmall.copy(fontFamily = AstaSans), bodyLarge = base.bodyLarge.copy(fontFamily = AstaSans),
            bodyMedium = base.bodyMedium.copy(fontFamily = AstaSans), bodySmall = base.bodySmall.copy(fontFamily = AstaSans),
            labelLarge = base.labelLarge.copy(fontFamily = AstaSans), labelMedium = base.labelMedium.copy(fontFamily = AstaSans),
            labelSmall = base.labelSmall.copy(fontFamily = AstaSans),
        )
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
