package com.team.travelmap.curation.companion

import com.team.travelmap.core.model.PlaceCategory
import kotlin.math.roundToInt

/**
 * 담당: 3번. 가는 길 동행 카드·음성에 쓰는 문장(2026-11-15 사용자 결정).
 * 운전자는 화면을 보기 어려우므로 소리로 들어도 무엇인지 알 수 있게 말한다.
 *  - 관광지·축제 등: "약 2킬로미터 앞에 ○○이 있어요. (한 줄 소개). 1분만 돌아가면 돼요. 잠깐 들렀다 갈까요?"
 *  - 음식점: 소개 없이 "약 2킬로미터 앞에 음식점 ○○이 있어요. 1분만 돌아가면 돼요. 잠깐 들렀다 갈까요?"
 * 소개가 더 있는 관광지만 "이야기 더 듣기"로 [story]를 들려준다.
 */
data class PlaceNarration(
    /** 카드 큰 글씨: 장소 이름. */
    val headline: String,
    /** "약 2킬로미터 앞에 음식점 신화수산이 있어요" */
    val intro: String,
    /** 관광지 한 줄 소개. 음식점·소개 없음은 null. */
    val brief: String?,
    /** "1분만 돌아가면 돼요. 잠깐 들렀다 갈까요?" */
    val question: String,
    /** "이야기 더 듣기"로 들려줄 소개(문장 단위로 [MAX_STORY_CHARS] 안). 음식점은 빈 문자열. */
    val story: String,
    /** 녹음된 이야기가 있으면 그 id(StoryPlayer 로 재생). 음식점은 null. */
    val audioStoryId: String?,
) {
    /** 카드가 뜰 때 소리로 하는 말. */
    val spokenAsk: String get() = listOfNotNull(intro, brief, question).joinToString(" ") { sentence(it) }

    /** "이야기 더 듣기" 버튼을 보일지: 녹음 이야기가 있거나, 한 줄 소개보다 긴 소개가 있을 때(관광지만). */
    val hasMoreStory: Boolean get() = audioStoryId != null || story.length > (brief?.length ?: 0) + 20

    companion object {
        const val MAX_STORY_CHARS = 220
        const val MAX_BRIEF_CHARS = 80

        fun of(c: CompanionCandidate, aheadKm: Double = 0.0, driveMinutes: Int = 0): PlaceNarration {
            val food = c.category == PlaceCategory.FOOD
            val overview = cleanOverview(c.overview)
            val where = if (aheadKm < 1.0) "바로 앞에" else "약 ${aheadKm.roundToInt()}킬로미터 앞에"
            val what = if (food) "음식점 ${c.name}" else c.name
            return PlaceNarration(
                headline = c.name,
                intro = "$where $what${subjectParticle(c.name)} 있어요",
                brief = if (food) null else firstSentences(overview, MAX_BRIEF_CHARS).takeIf { it.isNotBlank() },
                question = question(driveMinutes),
                story = if (food) "" else firstSentences(overview, MAX_STORY_CHARS),
                audioStoryId = if (food) null else c.audioStoryId,
            )
        }

        /** "1분만 돌아가면 돼요. 잠깐 들렀다 갈까요?"(거의 안 돌아가면 "가는 길이에요."). */
        fun question(driveMinutes: Int): String =
            (if (driveMinutes <= 0) "가는 길이에요." else "${driveMinutes}분만 돌아가면 돼요.") + " 잠깐 들렀다 갈까요?"

        /** "길에서 1분 · 둘러보기 30분"(우회가 거의 없으면 "길은 그대로 · 둘러보기 30분"). */
        fun detourLabel(driveMinutes: Int, stayMinutes: Int): String =
            (if (driveMinutes <= 0) "길은 그대로" else "길에서 ${driveMinutes}분") + " · 둘러보기 ${stayMinutes}분"

        /** 문장 끝에 마침표가 없으면 붙인다(소리로 읽을 때 쉼). */
        private fun sentence(s: String): String = s.trimEnd().let { if (it.endsWith(".") || it.endsWith("?") || it.endsWith("!") || it.endsWith("…")) it else "$it." }

        /** 받침이 있으면 "이", 없으면 "가". 한글이 아니면 "이(가)". */
        fun subjectParticle(word: String): String = particle(word, "이", "가", "이(가)")

        /**
         * 받침이 있으면 "을", 없으면 "를". 끝의 괄호 설명("내원사(대전)")은 빼고 본다.
         * 한글이 아니면(영문·숫자 끝) "을(를)".
         */
        fun objectParticle(word: String): String = particle(word, "을", "를", "을(를)")

        private fun particle(word: String, withFinal: String, withoutFinal: String, unknown: String): String {
            val base = word.trim().replace(Regex("""\s*[(\[][^)\]]*[)\]]$"""), "")
            val last = base.trimEnd().lastOrNull() ?: return unknown
            if (last !in '가'..'힣') return unknown
            return if ((last - '가') % 28 != 0) withFinal else withoutFinal
        }

        /** 문장(. ! ? 。 다음 공백)으로 나눠 [max] 글자 안에서 앞에서부터 담는다. 첫 문장이 너무 길면 잘라 "…"을 붙인다. */
        fun firstSentences(text: String, max: Int): String {
            if (text.length <= max) return text
            val sentences = Regex("""(?<=[.!?。])\s+""").split(text)
            val out = StringBuilder()
            for (s in sentences) {
                if (out.length + s.length + (if (out.isEmpty()) 0 else 1) > max) break
                if (out.isNotEmpty()) out.append(' ')
                out.append(s)
            }
            return if (out.isNotEmpty()) out.toString() else text.take(max - 1).trimEnd() + "…"
        }
    }
}

/**
 * 담당: 3번. 문장을 소리로 읽어 주는 자리. 앱에서는 Pleos TTS([PleosPlaceNarrator]), 테스트·TTS 가 없는 기기에서는 [TextOnlyNarrator].
 * 동행 카드 질문·이야기와 경유지·목적지 접근 안내가 함께 쓴다(새 문장이 오면 이전 문장은 끊는다).
 */
interface PlaceNarrator {
    /**
     * 소리로 읽기 시작했으면(또는 준비가 끝나는 대로 읽을 예정이면) true. false 면 화면이 글로만 보여 준다.
     * [onDone]: 끝까지 읽었을 때 메인 스레드에서 한 번(중간에 멈추거나 다른 문장으로 바뀌면 부르지 않음).
     */
    fun speak(text: String, onDone: (() -> Unit)? = null): Boolean
    /** 읽던 문장을 멈춘다. 읽는 중이 아니면 아무것도 하지 않는다. */
    fun stop()
}

object TextOnlyNarrator : PlaceNarrator {
    override fun speak(text: String, onDone: (() -> Unit)?): Boolean = false
    override fun stop() = Unit
}
