package com.ryose.videoplayer

import com.ryose.videoplayer.PlaybackService.Repeat
import com.ryose.videoplayer.QueueRules.EndAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class QueueRulesTest {
    @Test
    fun 順番はシャッフルなら今のものが先頭で_全部1回ずつ() {
        assertEquals(listOf(0, 1, 2, 3), QueueRules.buildOrder(4, 2, shuffle = false))
        repeat(20) { seed ->
            val o = QueueRules.buildOrder(5, 3, shuffle = true, random = Random(seed))
            assertEquals(3, o.first())
            assertEquals((0 until 5).toList(), o.sorted())
        }
    }

    @Test
    fun 次へ() {
        val order = listOf(0, 1, 2)
        assertEquals(QueueRules.Step(order, 1), QueueRules.next(order, 0, 0, 3, Repeat.OFF, shuffle = false))
        // 最後でリピートなしなら進めない
        assertNull(QueueRules.next(order, 2, 2, 3, Repeat.OFF, shuffle = false))
        // 1 曲リピートでも「次へ」ボタンは最後で止まる
        assertNull(QueueRules.next(order, 2, 2, 3, Repeat.ONE, shuffle = false))
        // 全曲リピートなら最初から
        assertEquals(QueueRules.Step(order, 0), QueueRules.next(order, 2, 2, 3, Repeat.ALL, shuffle = false))
    }

    @Test
    fun 全曲リピートでシャッフルなら並べ直し_今の曲は2回続かない() {
        repeat(30) { seed ->
            val step = QueueRules.next(listOf(2, 0, 1), 2, 1, 3, Repeat.ALL, shuffle = true, random = Random(seed))!!
            assertEquals(0, step.orderPos)
            assertEquals(listOf(0, 1, 2), step.order.sorted())
            assertNotEquals(1, step.order.first())
        }
    }

    @Test
    fun 次があるか() {
        assertTrue(QueueRules.hasNext(listOf(0, 1), 0, Repeat.OFF, 2))
        assertFalse(QueueRules.hasNext(listOf(0, 1), 1, Repeat.OFF, 2))
        assertTrue(QueueRules.hasNext(listOf(0, 1), 1, Repeat.ALL, 2))
        // 1 つしか無ければ全曲リピートでも「次」は無い
        assertFalse(QueueRules.hasNext(listOf(0), 0, Repeat.ALL, 1))
    }

    @Test
    fun 前へ() {
        val order = listOf(0, 1, 2)
        // 3 秒より後まで再生していたら今の曲の頭へ
        assertNull(QueueRules.previous(order, 1, 3_001, Repeat.OFF))
        assertEquals(0, QueueRules.previous(order, 1, 3_000, Repeat.OFF))
        // 最初の曲なら頭へ。全曲リピートなら最後の曲へ
        assertNull(QueueRules.previous(order, 0, 0, Repeat.OFF))
        assertEquals(2, QueueRules.previous(order, 0, 0, Repeat.ALL))
    }

    @Test
    fun 終わったとき() {
        assertEquals(EndAction.REPEAT_ONE, QueueRules.onEnded(Repeat.ONE, hasNext = true, autoAdvance = false, isAudio = false))
        assertEquals(EndAction.NEXT, QueueRules.onEnded(Repeat.OFF, hasNext = true, autoAdvance = true, isAudio = false))
        // 「終わったら次の動画を再生」がオフの動画は止める。曲はいつも次へ
        assertEquals(EndAction.STOP, QueueRules.onEnded(Repeat.OFF, hasNext = true, autoAdvance = false, isAudio = false))
        assertEquals(EndAction.NEXT, QueueRules.onEnded(Repeat.OFF, hasNext = true, autoAdvance = false, isAudio = true))
        assertEquals(EndAction.STOP, QueueRules.onEnded(Repeat.OFF, hasNext = false, autoAdvance = true, isAudio = true))
    }

    @Test
    fun 再生できなかったとき() {
        assertTrue(QueueRules.skipOnError(hasNext = true, errorStreak = 1, size = 3, autoAdvance = true, isAudio = false))
        // 全部だめだったら止める
        assertFalse(QueueRules.skipOnError(hasNext = true, errorStreak = 3, size = 3, autoAdvance = true, isAudio = false))
        assertFalse(QueueRules.skipOnError(hasNext = true, errorStreak = 0, size = 3, autoAdvance = false, isAudio = false))
        assertFalse(QueueRules.skipOnError(hasNext = false, errorStreak = 0, size = 3, autoAdvance = true, isAudio = true))
    }

    @Test
    fun キューの編集() {
        // 今（位置 1）の次に 2 つ加える／最後に加える
        assertEquals(listOf(0, 1, 3, 4, 2), QueueRules.enqueue(listOf(0, 1, 2), 1, 3, 2, next = true))
        assertEquals(listOf(0, 1, 2, 3), QueueRules.enqueue(listOf(0, 1, 2), 1, 3, 1, next = false))
        // 外すと、後ろの番号を詰める
        assertEquals(listOf(2, 0, 1), QueueRules.remove(listOf(3, 2, 0, 1), 0))
        assertEquals(listOf(1, 0), QueueRules.remove(listOf(2, 1, 0), 1))
        // 入れ替え
        assertEquals(listOf(1, 2, 0), QueueRules.move(listOf(0, 1, 2), 0, 2))
        assertEquals(1, QueueRules.positionOf(listOf(2, 0, 1), 0))
        assertEquals(0, QueueRules.positionOf(listOf(2, 0, 1), 9))
    }
}
