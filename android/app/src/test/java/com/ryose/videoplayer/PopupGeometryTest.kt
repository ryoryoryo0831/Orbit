package com.ryose.videoplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PopupGeometryTest {

    @Test
    fun 縦横の比率は動画に合わせて極端なものは抑える() {
        assertEquals(9f / 16f, PopupGeometry.aspect(1920, 1080), 0.001f)
        assertEquals(16f / 9f, PopupGeometry.aspect(1080, 1920), 0.001f)
        // 映像の大きさが分からないときは 16:9
        assertEquals(9f / 16f, PopupGeometry.aspect(0, 0), 0.001f)
        // 極端に横長・縦長な動画は 2.39:1 までに抑える
        assertEquals(1 / 2.39f, PopupGeometry.aspect(4000, 100), 0.001f)
        assertEquals(2.39f, PopupGeometry.aspect(100, 4000), 0.001f)
    }

    @Test
    fun 大きさは長い方の辺で決める() {
        assertEquals(320 to 180, PopupGeometry.windowSize(320, 9f / 16f))
        assertEquals(180 to 320, PopupGeometry.windowSize(320, 16f / 9f))
    }

    @Test
    fun 大きさは画面に収まる() {
        val a = 9f / 16f
        // 縦向きの画面（幅 1080）では、横長の小窓は画面の幅まで
        assertEquals(1080, PopupGeometry.clampSize(5000, 300, 1080, 2400, a))
        // 小さすぎるときは最小に
        assertEquals(300, PopupGeometry.clampSize(10, 300, 1080, 2400, a))
        // 横向きの画面（高さ 1080）では、縦長の小窓は画面の高さまで
        assertEquals(1080, PopupGeometry.clampSize(5000, 300, 2400, 1080, 16f / 9f))
    }

    @Test
    fun 端に寄せても一部は見えている() {
        // 左の端に寄せても 40 は見えている
        assertEquals(-260 to 100, PopupGeometry.clampPosition(-1000, 100, 300, 200, 1080, 2400, 40))
        // 右下の端
        assertEquals(1040 to 2360, PopupGeometry.clampPosition(5000, 5000, 300, 200, 1080, 2400, 40))
        // 画面の中ならそのまま
        assertEquals(500 to 600, PopupGeometry.clampPosition(500, 600, 300, 200, 1080, 2400, 40))
    }

    @Test
    fun ボタンの数は幅で変わる() {
        // 横長（16:9）の動画
        assertEquals(5, PopupGeometry.buttonCount(240f, 135f))
        assertEquals(5, PopupGeometry.buttonCount(220f, 124f))
        assertEquals(3, PopupGeometry.buttonCount(210f, 118f))
        // 右上のボタン（閉じる・全画面）の下まで列を下げれば 3 つ並ぶ
        assertEquals(3, PopupGeometry.buttonCount(180f, 101f))
        // 低くて下げられないので、再生ボタンだけを右上のボタンの左に
        assertEquals(1, PopupGeometry.buttonCount(132f, 74f))
        // 縦長の動画は高さがあるので、幅が足りれば並べられる
        assertEquals(3, PopupGeometry.buttonCount(140f, 249f))
        // 一番小さいときは真ん中のボタンを出さない（ダブルタップで再生・一時停止）
        assertEquals(0, PopupGeometry.buttonCount(96f, 54f))
    }

    @Test
    fun ボタンの列は右上のボタンにかかるときだけずらす() {
        // 窓の真ん中のままで重ならない
        assertEquals(110f to 62f, PopupGeometry.rowCenter(220f, 124f, 5))
        assertEquals(70f to 124.5f, PopupGeometry.rowCenter(140f, 249f, 3))
        // 真ん中（50.5）だと右上のボタンにかかるので、その下（36 + 2 + 再生ボタンの半分 24）へ
        assertEquals(90f to 62f, PopupGeometry.rowCenter(180f, 101f, 3))
        // 下げると下のバーにかかるので、再生ボタンを右上のボタン（左端 60）の左へ：60 - 4 - 24
        assertEquals(32f to 37f, PopupGeometry.rowCenter(132f, 74f, 1))
        assertNull(PopupGeometry.rowCenter(96f, 54f, 1))
        assertNull(PopupGeometry.rowCenter(300f, 200f, 0))
    }

    @Test
    fun 半分より多く左右の外に出して離すと端にしまう() {
        // 左：真ん中（-200 + 150 = -50）が画面の外
        assertEquals(-1, PopupGeometry.stashSide(-200, 300, 1080))
        // 真ん中が画面の中ならしまわない
        assertEquals(0, PopupGeometry.stashSide(-100, 300, 1080))
        assertEquals(0, PopupGeometry.stashSide(900, 300, 1080))
        // 右：真ん中（950 + 150 = 1100）が画面の外
        assertEquals(1, PopupGeometry.stashSide(950, 300, 1080))
    }

    @Test
    fun しまうと帯だけ残し戻すと端に寄せる() {
        assertEquals(-272, PopupGeometry.stashedX(-1, 300, 1080, 28))
        assertEquals(1052, PopupGeometry.stashedX(1, 300, 1080, 28))
        assertEquals(8, PopupGeometry.unstashedX(-1, 300, 1080, 8))
        assertEquals(772, PopupGeometry.unstashedX(1, 300, 1080, 8))
        // 画面いっぱいの大きさなら、端から離さずにはみ出さない所へ
        assertEquals(0, PopupGeometry.unstashedX(-1, 1080, 1080, 8))
        assertEquals(0, PopupGeometry.unstashedX(1, 1080, 1080, 8))
    }
}
