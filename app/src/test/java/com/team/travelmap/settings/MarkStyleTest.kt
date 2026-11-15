package com.team.travelmap.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MarkStyleTest {
    @Test
    fun 저장한_표시를_그대로_읽는다() {
        val all = listOf(MarkStyle.Pattern) + (0 until MarkStyle.TINT_COUNT).map(MarkStyle::Tint) + MarkStyle.EMOJIS.map(MarkStyle::Emoji)
        all.forEach { assertEquals(it, MarkStyle.parse(it.serialize())) }
    }

    @Test
    fun 알수없는_값은_null() {
        listOf(null, "", "tint:9", "tint:x", "emoji:", "photo").forEach { assertNull(MarkStyle.parse(it)) }
    }
}
