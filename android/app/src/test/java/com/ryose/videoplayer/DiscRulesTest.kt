package com.ryose.videoplayer

import com.ryose.videoplayer.DiscRules.MenuJump
import com.ryose.videoplayer.DiscRules.Title
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscRulesTest {
    /** VLC の DVD のタイトルの並び：0 がメニュー、あとは本編・特典・宣伝など */
    private val dvd = listOf(
        Title(isMenu = true, durationMs = 0),
        Title(isMenu = false, durationMs = 6_892_000), // 本編 1:54:52
        Title(isMenu = false, durationMs = 5_000),
        Title(isMenu = false, durationMs = 91_000),
    )

    @Test
    fun メニューと本編を見分ける() {
        assertEquals(0, DiscRules.menuIndex(dvd))
        assertEquals(1, DiscRules.mainTitle(dvd))
        assertTrue(DiscRules.isInMenu(dvd, 0))
        assertFalse(DiscRules.isInMenu(dvd, 1))
        // まだディスクを開いていない
        assertFalse(DiscRules.isInMenu(emptyList(), 0))
        assertFalse(DiscRules.isInMenu(dvd, -1))
    }

    @Test
    fun メニューの無いディスク() {
        val noMenu = listOf(Title(false, 1_000), Title(false, 3_000))
        assertEquals(-1, DiscRules.menuIndex(noMenu))
        assertEquals(1, DiscRules.mainTitle(noMenu))
        assertNull(DiscRules.mainTitle(listOf(Title(true, 0))))
        assertNull(DiscRules.mainTitle(emptyList()))
    }

    @Test
    fun 宣伝が流れたらメニューへ移る() {
        // メニュー（著作権の注意など）の間は待つ
        assertEquals(MenuJump.WAIT, DiscRules.menuJumpStep(dvd, current = 0, jumps = 0))
        // 宣伝（メニュー以外のタイトル）が流れ始めた
        assertEquals(MenuJump.JUMP, DiscRules.menuJumpStep(dvd, current = 3, jumps = 0))
        // 移ったあとにメニューになった＝移れた
        assertEquals(MenuJump.DONE, DiscRules.menuJumpStep(dvd, current = 0, jumps = 1))
        // まだ移れていなければもう一度
        assertEquals(MenuJump.JUMP, DiscRules.menuJumpStep(dvd, current = 3, jumps = 2))
        // 3 回試してもだめならあきらめる
        assertEquals(MenuJump.DONE, DiscRules.menuJumpStep(dvd, current = 3, jumps = DiscRules.MAX_MENU_JUMPS))
    }

    @Test
    fun ディスクを開く前やメニューの無いディスクでは移らない() {
        assertEquals(MenuJump.WAIT, DiscRules.menuJumpStep(emptyList(), current = -1, jumps = 0))
        assertEquals(MenuJump.WAIT, DiscRules.menuJumpStep(dvd, current = -1, jumps = 0))
        val noMenu = listOf(Title(false, 1_000), Title(false, 3_000))
        assertEquals(MenuJump.WAIT, DiscRules.menuJumpStep(noMenu, current = 1, jumps = 0))
    }

    @Test
    fun 映像の無いメニューを数える() {
        // 映像が出てきたら見るのをやめる
        assertNull(DiscRules.emptyMenuSeconds(inMenu = true, videoTracks = 1, previous = 3))
        // メニューで映像が無いまま 1 秒
        assertEquals(4, DiscRules.emptyMenuSeconds(inMenu = true, videoTracks = 0, previous = 3))
        // ディスクを開いている途中など、メニューかどうか分からない間は数え直し
        assertEquals(0, DiscRules.emptyMenuSeconds(inMenu = false, videoTracks = 0, previous = 3))
        // 6 秒たったら本編へ（PlaybackService が使うしきい値）
        var seconds = 0
        repeat(DiscRules.EMPTY_MENU_SECONDS) { seconds = DiscRules.emptyMenuSeconds(true, 0, seconds)!! }
        assertEquals(DiscRules.EMPTY_MENU_SECONDS, seconds)
    }
}
