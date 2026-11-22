package com.ryose.videoplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FastScrollBarTest {
    @Test
    fun 頭文字は英字を大文字に_かなと漢字はそのまま() {
        assertEquals("A", FastScrollBar.initialOf("abc.mp4"))
        assertEquals("あ", FastScrollBar.initialOf("あいう"))
        assertEquals("ガ", FastScrollBar.initialOf("ガンダム"))
        assertEquals("機", FastScrollBar.initialOf("機動戦士"))
    }

    @Test
    fun 数字と記号はシャープ_空は無し() {
        assertEquals("#", FastScrollBar.initialOf("01 北の国から"))
        assertEquals("#", FastScrollBar.initialOf("(BD)らんま"))
        assertEquals("#", FastScrollBar.initialOf("-inter mission-"))
        assertNull(FastScrollBar.initialOf("  "))
        assertNull(FastScrollBar.initialOf(null))
    }
}
