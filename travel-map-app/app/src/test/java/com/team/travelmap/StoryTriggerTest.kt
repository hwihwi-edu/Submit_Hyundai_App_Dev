package com.team.travelmap

import com.team.travelmap.core.model.GeoPoint
import com.team.travelmap.story.StoryPoint
import com.team.travelmap.story.StoryTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StoryTriggerTest {
    private val gongsan = StoryPoint("gongsan", "공산성", GeoPoint(36.4646, 127.1247), "a.mp3", 3_000.0)
    private val near = GeoPoint(36.4700, 127.1247) // 약 600m

    @Test fun `반경 안이면 재생`() =
        assertEquals(gongsan, StoryTrigger.next(near, listOf(gongsan), emptySet(), isPlaying = false, cautionActive = false))

    @Test fun `이미 들은 이야기는 다시 안 함`() =
        assertNull(StoryTrigger.next(near, listOf(gongsan), setOf("gongsan"), false, false))

    @Test fun `재생 중이거나 주의 구간이면 미룬다`() {
        assertNull(StoryTrigger.next(near, listOf(gongsan), emptySet(), isPlaying = true, cautionActive = false))
        assertNull(StoryTrigger.next(near, listOf(gongsan), emptySet(), isPlaying = false, cautionActive = true))
    }

    @Test fun `반경 밖이면 재생 안 함`() =
        assertNull(StoryTrigger.next(GeoPoint(36.60, 127.1247), listOf(gongsan), emptySet(), false, false))

    @Test fun `자동 재생을 끄면 반경 안에서도 시작하지 않고 다시 켜면 후보로 남는다`() {
        val played = emptySet<String>()
        assertNull(StoryTrigger.next(near, listOf(gongsan), played, false, false, autoplayEnabled = false))
        assertEquals(gongsan, StoryTrigger.next(near, listOf(gongsan), played, false, false, autoplayEnabled = true))
    }

    @Test fun `서울→대전 경로 축에서 먼 이야기는 미리 제외`() {
        val seoul = GeoPoint(37.5663, 126.9779)
        val daejeon = GeoPoint(36.2805, 127.3417)
        val cheonan = StoryPoint("cheonan", "천안삼거리", GeoPoint(36.7955, 127.1717), "a.wav", 10_000.0)
        val gangneung = StoryPoint("gn", "강릉", GeoPoint(37.7519, 128.8761), "b.wav")
        assertEquals(listOf(cheonan), StoryTrigger.alongRoute(seoul, daejeon, listOf(cheonan, gangneung)))
    }
}
