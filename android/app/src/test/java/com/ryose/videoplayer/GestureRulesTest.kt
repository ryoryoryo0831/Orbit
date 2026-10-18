package com.ryose.videoplayer

import com.ryose.videoplayer.GestureRules.DoubleTap
import com.ryose.videoplayer.GestureRules.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class GestureRulesTest {
    private fun classify(x: Float, y: Float, dx: Float, dy: Float, brightness: Boolean = true, volume: Boolean = true, seek: Boolean = true) =
        GestureRules.classify(x, y, dx, dy, width = 1000f, height = 500f, edge = 50f, brightness = brightness, volume = volume, seek = seek)

    @Test
    fun 上下は左が明るさ_右が音量_左右は移動() {
        assertEquals(Kind.BRIGHTNESS, classify(100f, 250f, 5f, -40f))
        assertEquals(Kind.VOLUME, classify(900f, 250f, 5f, 40f))
        assertEquals(Kind.SEEK, classify(500f, 250f, 60f, 10f))
    }

    @Test
    fun 画面の上下の端と_設定でオフのものは受け付けない() {
        assertEquals(Kind.IGNORE, classify(100f, 20f, 0f, 40f))
        assertEquals(Kind.IGNORE, classify(100f, 480f, 0f, -40f))
        assertEquals(Kind.IGNORE, classify(100f, 250f, 0f, 40f, brightness = false))
        assertEquals(Kind.IGNORE, classify(900f, 250f, 0f, 40f, volume = false))
        assertEquals(Kind.IGNORE, classify(500f, 250f, 60f, 0f, seek = false))
    }

    @Test
    fun ダブルタップの場所() {
        assertEquals(DoubleTap.REWIND, GestureRules.doubleTap(100f, 900f))
        assertEquals(DoubleTap.TOGGLE_PLAY, GestureRules.doubleTap(450f, 900f))
        assertEquals(DoubleTap.FORWARD, GestureRules.doubleTap(800f, 900f))
    }

    @Test
    fun 明るさ_音量_移動先() {
        // 画面の高さ分、上へ動かすと 1 段（明るさは 0.01〜1）
        assertEquals(0.75f, GestureRules.brightness(0.5f, -125f, 500f), 0.001f)
        assertEquals(0.01f, GestureRules.brightness(0.5f, 1000f, 500f), 0.001f)
        // 音量は画面の高さで 150%。上限を超えない
        assertEquals(80, GestureRules.volume(50f, -100f, 500f, 100))
        assertEquals(100, GestureRules.volume(50f, -500f, 500f, 100))
        assertEquals(200, GestureRules.volume(150f, -500f, 500f, 200))
        // 横幅いっぱいで 90 秒。0〜長さ
        assertEquals(55_000L, GestureRules.seekTarget(10_000, 500f, 1000f, 600_000))
        assertEquals(0L, GestureRules.seekTarget(10_000, -500f, 1000f, 600_000))
        assertEquals(60_000L, GestureRules.seekTarget(50_000, 500f, 1000f, 60_000))
    }

    @Test
    fun 拡大は1倍から4倍() {
        assertEquals(1f, GestureRules.clampZoom(0.5f))
        assertEquals(4f, GestureRules.clampZoom(9f))
        assertEquals(2f, GestureRules.clampZoom(2f))
    }
}
