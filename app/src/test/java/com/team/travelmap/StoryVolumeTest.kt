package com.team.travelmap

import com.team.travelmap.story.normalizedStoryVolume
import org.junit.Assert.assertEquals
import org.junit.Test

class StoryVolumeTest {
    @Test fun `음소거와 사용자 볼륨을 보존하고 범위 밖 값은 제한한다`() {
        assertEquals(0f, normalizedStoryVolume(0f), 0f)
        assertEquals(0.6f, normalizedStoryVolume(0.6f), 0f)
        assertEquals(0f, normalizedStoryVolume(-1f), 0f)
        assertEquals(1f, normalizedStoryVolume(2f), 0f)
    }

    @Test fun `비정상 저장값을 소리 크기로 전달하지 않는다`() {
        assertEquals(0f, normalizedStoryVolume(Float.NaN), 0f)
        assertEquals(0f, normalizedStoryVolume(Float.POSITIVE_INFINITY), 0f)
        assertEquals(0f, normalizedStoryVolume(Float.NEGATIVE_INFINITY), 0f)
    }
}
