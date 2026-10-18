package com.ryose.videoplayer

import com.ryose.videoplayer.AbRules.Range
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AbRulesTest {
    @Test
    fun Aを決めてからBを決めるとAに戻ってくり返す() {
        val a = AbRules.setA(Range(), 10_000)
        assertEquals(Range(10_000, -1), a.range)
        assertNull(a.seekTo)
        val b = AbRules.setB(a.range, 20_000)
        assertEquals(Range(10_000, 20_000), b.range)
        assertEquals(10_000L, b.seekTo)
        assertTrue(b.range.isLoop)
    }

    @Test
    fun Aが無い_Aより前ではBを決めない() {
        val none = AbRules.setB(Range(), 5_000)
        assertFalse(none.changed)
        assertEquals(Range(), none.range)
        // A から 0.5 秒以内も決めない
        val tooClose = AbRules.setB(Range(10_000, -1), 10_400)
        assertFalse(tooClose.changed)
        assertEquals(Range(10_000, -1), tooClose.range)
    }

    @Test
    fun AをBより後にするとBは外す() {
        val r = AbRules.setA(Range(10_000, 20_000), 19_800)
        assertEquals(Range(19_800, -1), r.range)
        // B より十分前なら B はそのまま
        assertEquals(Range(5_000, 20_000), AbRules.setA(Range(10_000, 20_000), 5_000).range)
    }

    @Test
    fun AとBを少しずらす() {
        val r = Range(10_000, 20_000)
        // A はその位置から再生
        val a = AbRules.nudge(r, isA = true, deltaMs = -500, lengthMs = 60_000)!!
        assertEquals(Range(9_500, 20_000), a.range)
        assertEquals(9_500L, a.seekTo)
        // B はその 2 秒前から再生
        val b = AbRules.nudge(r, isA = false, deltaMs = 500, lengthMs = 60_000)!!
        assertEquals(Range(10_000, 20_500), b.range)
        assertEquals(18_500L, b.seekTo)
        // A は B に近づきすぎない・0 より前にしない
        assertEquals(19_500L, AbRules.nudge(r, true, 50_000, 60_000)!!.range.a)
        assertEquals(0L, AbRules.nudge(Range(300, 20_000), true, -500, 60_000)!!.range.a)
        // B は長さより後にしない。動かなければ null
        assertNull(AbRules.nudge(Range(10_000, 60_000), false, 500, 60_000))
        assertNull(AbRules.nudge(Range(), true, 500, 60_000))
    }

    @Test
    fun 覚えていた区間の外から始まるときはAから() {
        val saved = Range(10_000, 20_000)
        assertEquals(10_000L, AbRules.restoredStart(saved, 0))
        assertEquals(10_000L, AbRules.restoredStart(saved, 25_000))
        assertEquals(15_000L, AbRules.restoredStart(saved, 15_000))
    }

    @Test
    fun Bを過ぎたらAへ戻る() {
        val r = Range(10_000, 20_000)
        assertNull(AbRules.loopBackTo(r, 19_999))
        assertEquals(10_000L, AbRules.loopBackTo(r, 20_000))
        // A だけのときは戻らない
        assertNull(AbRules.loopBackTo(Range(10_000, -1), 30_000))
    }

    @Test
    fun メニューの表示() {
        assertEquals("0:10〜0:20", AbRules.label(Range(10_000, 20_000)))
        assertEquals("A 0:10", AbRules.label(Range(10_000, -1)))
        assertNull(AbRules.label(Range()))
    }
}
