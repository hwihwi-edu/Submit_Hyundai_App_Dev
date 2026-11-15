package com.team.travelmap.curation.companion

/**
 * 담당: 3번. 동행 질문("잠깐 들렀다 갈까요?")에 운전자가 말로 한 대답.
 * 운전 중에는 화면을 누르기 어려우므로 질문을 읽은 뒤 잠깐 듣고 이것으로 나눈다(2026-11-15 사용자 결정).
 */
enum class VoiceAnswer { YES, NO, STORY }

object VoiceAnswerParser {
    // 순서가 중요하다: "안 들를래"는 '들를'이 있어도 거절, "이야기 들려줘"는 '들'이 있어도 이야기.
    private val NO = listOf("아니", "괜찮", "됐어", "됐다", "싫어", "안가", "안갈", "안들", "그냥가", "패스", "다음에", "넘어가", "노노", "필요없", "안좋")
    private val STORY = listOf("이야기", "얘기", "설명", "자세히", "더들", "들려", "뭐하는", "어떤곳", "어떤데")
    private val YES = listOf("그래", "좋아", "좋지", "가자", "가볼", "가봐", "들러", "들를", "들르", "들렀", "갈래", "갈게", "갈께", "가요", "오케이", "콜", "당연", "그럼")
    /** 짧은 말은 다른 말 속에 섞이기 쉬워서 문장 맨 앞에 있을 때만 대답으로 본다. */
    private val YES_SHORT = listOf("응", "어", "네", "예", "웅", "ok")

    /** 알아들을 수 없으면 null(카드는 그대로, 버튼이나 시간이 지나 닫힘). */
    fun parse(text: String?): VoiceAnswer? {
        val t = text?.lowercase()?.replace(Regex("""[\s.,!?~…]"""), "") ?: return null
        if (t.isEmpty()) return null
        return when {
            NO.any { it in t } -> VoiceAnswer.NO
            STORY.any { it in t } -> VoiceAnswer.STORY
            YES.any { it in t } || YES_SHORT.any { t.startsWith(it) } -> VoiceAnswer.YES
            else -> null
        }
    }
}

/**
 * 담당: 3번. 말로 한 대답을 듣는 자리. 앱에서는 Pleos SpeechToText([PleosAnswerListener]),
 * 테스트·음성 인식이 없는 기기에서는 [NoAnswerListener](듣지 않음 → 버튼으로만 대답).
 */
interface AnswerListener {
    /**
     * [timeoutMs] 동안 듣고 들은 말을 [onResult]로 한 번 돌려준다(못 들었으면 null). 메인 스레드에서 부른다.
     * 들을 수 없는 상태(권한 없음·서비스 없음)면 false 를 돌려주고 [onResult]는 부르지 않는다.
     */
    fun listen(timeoutMs: Long, onResult: (String?) -> Unit): Boolean
    /** 듣던 것을 그만둔다. [onResult]는 부르지 않는다. */
    fun cancel()
}

object NoAnswerListener : AnswerListener {
    override fun listen(timeoutMs: Long, onResult: (String?) -> Unit): Boolean = false
    override fun cancel() = Unit
}
