package com.ryose.videoplayer

import android.os.Handler
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import org.videolan.libvlc.MediaPlayer

/**
 * 再生画面の DVD のメニュー用の十字キー（PlayerActivity から分けた）。
 * DVD のメニューを表示している間だけ出し、画面の十字キー・リモコン・キーボードの操作をメニューに送る
 *
 * @param pad 十字キーの入れ物（R.id.dvdPad。中に dvdUp・dvdDown・dvdLeft・dvdRight・dvdOk・dvdClose）
 * @param blocked 出さないとき（小窓・画面のロック中）
 */
class DvdPad(
    private val pad: View,
    private val handler: Handler,
    private val service: () -> PlaybackService?,
    private val blocked: () -> Boolean,
) {
    /** 十字キーを ✕ で閉じた（DVD のメニューから出るまでは出さない） */
    private var dismissed = false

    /** DVD（ISO）を再生している間、メニューを表示しているかを見張る（libVLC はタイトルが変わったことを知らせないため） */
    private val menuCheck = object : Runnable {
        override fun run() {
            update()
            handler.postDelayed(this, 700)
        }
    }

    val isShowing: Boolean get() = pad.isVisible

    init {
        fun nav(id: Int, direction: Int) = pad.findViewById<View>(id).setOnClickListener { send(direction) }
        nav(R.id.dvdUp, MediaPlayer.Navigate.Up)
        nav(R.id.dvdDown, MediaPlayer.Navigate.Down)
        nav(R.id.dvdLeft, MediaPlayer.Navigate.Left)
        nav(R.id.dvdRight, MediaPlayer.Navigate.Right)
        nav(R.id.dvdOk, MediaPlayer.Navigate.Activate)
        pad.findViewById<View>(R.id.dvdClose).setOnClickListener {
            dismissed = true
            update()
        }
        // 横向きでナビゲーションバー・切り欠きに重ならないように
        val baseEnd = (pad.layoutParams as ViewGroup.MarginLayoutParams).marginEnd
        ViewCompat.setOnApplyWindowInsetsListener(pad) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updateLayoutParams<ViewGroup.MarginLayoutParams> { marginEnd = baseEnd + bars.right }
            insets
        }
    }

    /** 曲・動画が変わった：DVD（ISO）のときだけ、メニューを表示しているかを見張る */
    fun onItemChanged(isDisc: Boolean) {
        handler.removeCallbacks(menuCheck)
        dismissed = false
        if (isDisc) handler.post(menuCheck) else pad.visibility = View.GONE
    }

    /** 画面が隠れた：見張るのをやめる（戻ってきたら onItemChanged でまた始める） */
    fun stop() {
        handler.removeCallbacks(menuCheck)
    }

    /** DVD のメニューを表示している間だけ十字キーを出す（✕で閉じたら、メニューから出るまで出さない） */
    fun update() {
        val inMenu = service()?.isInDiscMenu() == true
        if (!inMenu) dismissed = false
        pad.isVisible = inMenu && !dismissed && !blocked()
    }

    /** ︙の「DVD メニュー」：メニューを開いて十字キーを出す。メニューが無ければ false */
    fun openMenu(): Boolean {
        val s = service() ?: return false
        if (!s.openDiscMenu()) return false
        dismissed = false
        handler.postDelayed({ update() }, 500)
        return true
    }

    /**
     * 十字キーを出している間は、リモコン・キーボードの十字キーと決定キーをメニューに送る。
     * 再生画面の dispatchKeyEvent から、ほかの View より先に呼ぶ（決定キーは、選ばれている画面全体の touchLayer が
     * 先に「クリック」として取ってしまうため）。受け取ったら true
     */
    fun dispatchKey(event: KeyEvent): Boolean {
        if (!isShowing || service() == null) return false
        val direction = navigationFor(event.keyCode) ?: return false
        if (event.action == KeyEvent.ACTION_DOWN) send(direction)
        return true
    }

    private fun send(direction: Int) {
        service()?.navigate(direction)
        // 決定でメニューから本編に移ったら、十字キーを片付ける
        handler.postDelayed({ update() }, 400)
    }

    companion object {
        /** リモコン・キーボードのキーを、DVD のメニューの操作（MediaPlayer.Navigate）に。メニューの操作でなければ null */
        fun navigationFor(keyCode: Int): Int? = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> MediaPlayer.Navigate.Up
            KeyEvent.KEYCODE_DPAD_DOWN -> MediaPlayer.Navigate.Down
            KeyEvent.KEYCODE_DPAD_LEFT -> MediaPlayer.Navigate.Left
            KeyEvent.KEYCODE_DPAD_RIGHT -> MediaPlayer.Navigate.Right
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> MediaPlayer.Navigate.Activate
            else -> null
        }
    }
}
