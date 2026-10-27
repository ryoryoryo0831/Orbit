package com.ryose.videoplayer

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbRulesTest {
    @Test
    fun 長さの1割の場面() {
        assertEquals(144_000L, ThumbRules.positionMs(24 * 60_000L))
        assertEquals(30_000L, ThumbRules.positionMs(5 * 60_000L))
    }

    @Test
    fun 長い動画は10分まで() {
        assertEquals(10 * 60_000L, ThumbRules.positionMs(2 * 60 * 60_000L))
    }

    @Test
    fun 短い動画は1秒_ただし半分より後ろにはしない() {
        assertEquals(1_000L, ThumbRules.positionMs(5_000L))
        assertEquals(500L, ThumbRules.positionMs(1_000L))
    }

    @Test
    fun 長さが分からなければ1秒() {
        assertEquals(1_000L, ThumbRules.positionMs(0))
        assertEquals(1_000L, ThumbRules.positionMs(-1))
    }
}
