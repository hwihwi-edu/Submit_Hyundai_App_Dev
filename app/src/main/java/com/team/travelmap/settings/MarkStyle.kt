package com.team.travelmap.settings

/**
 * 담당: 1번(지도·화면). 내 지도에서 다녀온 칸을 어떻게 표시할지.
 *  - Pattern : 도시별 방문 무늬(Codex visited-*.png)
 *  - Tint    : 색 팔레트 중 하나(index, 색 값은 ui 쪽 MarkPalette)
 *  - Emoji   : 칸 이름 앞에 붙는 이모지(칸은 방문 단색)
 * 저장 형식: "pattern" / "tint:2" / "emoji:🌊"
 */
sealed interface MarkStyle {
    data object Pattern : MarkStyle
    data class Tint(val index: Int) : MarkStyle
    data class Emoji(val symbol: String) : MarkStyle

    fun serialize(): String = when (this) {
        Pattern -> "pattern"
        is Tint -> "tint:$index"
        is Emoji -> "emoji:$symbol"
    }

    companion object {
        /** 고를 수 있는 이모지. Codex 이모지 세트가 오면 이미지로 바꾼다. */
        val EMOJIS = listOf("🌊", "🍜", "☕", "📷", "❤️", "🌸", "🏯", "⭐")
        const val TINT_COUNT = 6

        fun parse(raw: String?): MarkStyle? = when {
            raw == null -> null
            raw == "pattern" -> Pattern
            raw.startsWith("tint:") -> raw.removePrefix("tint:").toIntOrNull()?.takeIf { it in 0 until TINT_COUNT }?.let(::Tint)
            raw.startsWith("emoji:") -> raw.removePrefix("emoji:").takeIf { it.isNotEmpty() }?.let(::Emoji)
            else -> null
        }
    }
}
