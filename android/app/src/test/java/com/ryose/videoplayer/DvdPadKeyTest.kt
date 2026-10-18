package com.ryose.videoplayer

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.videolan.libvlc.MediaPlayer

class DvdPadKeyTest {
    @Test
    fun リモコンのキーをDVDのメニューの操作に() {
        assertEquals(MediaPlayer.Navigate.Up, DvdPad.navigationFor(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(MediaPlayer.Navigate.Down, DvdPad.navigationFor(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(MediaPlayer.Navigate.Left, DvdPad.navigationFor(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(MediaPlayer.Navigate.Right, DvdPad.navigationFor(KeyEvent.KEYCODE_DPAD_RIGHT))
        // 決定キー（テレビのリモコン）とキーボードの Enter はどちらも「決定」
        assertEquals(MediaPlayer.Navigate.Activate, DvdPad.navigationFor(KeyEvent.KEYCODE_DPAD_CENTER))
        assertEquals(MediaPlayer.Navigate.Activate, DvdPad.navigationFor(KeyEvent.KEYCODE_ENTER))
    }

    @Test
    fun ほかのキーはメニューに送らない() {
        // 戻る・メニュー・再生などは、再生画面がいつもどおり受け取る
        assertNull(DvdPad.navigationFor(KeyEvent.KEYCODE_BACK))
        assertNull(DvdPad.navigationFor(KeyEvent.KEYCODE_MENU))
        assertNull(DvdPad.navigationFor(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE))
    }
}
