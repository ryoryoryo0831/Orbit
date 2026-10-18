package com.ryose.videoplayer

import android.os.Handler

/**
 * スリープタイマー（PlaybackService から分けた）。「○分後に一時停止」と「この曲・動画の終わりで停止」。
 * 止める操作は [onExpired]（一時停止）と、再生が終わったときの [consumeAtEnd] で再生サービスが行う
 */
class SleepTimer(
    private val handler: Handler,
    /** 時間になった（一時停止する） */
    private val onExpired: () -> Unit,
    /** 設定が変わった（画面に伝える） */
    private val onChanged: () -> Unit,
) {
    /** 止める時刻（0 なら未設定） */
    var stopAt = 0L
        private set

    /** 今の曲・動画が終わったら止める */
    var atEnd = false
        private set

    private val task = Runnable {
        stopAt = 0
        onExpired()
        onChanged()
    }

    /** [minutes] 分後に一時停止する */
    fun set(minutes: Int) {
        clear()
        stopAt = System.currentTimeMillis() + minutes * 60_000L
        handler.postDelayed(task, minutes * 60_000L)
        onChanged()
    }

    /** 今の曲・動画の終わりで止める */
    fun setAtEnd() {
        clear()
        atEnd = true
        onChanged()
    }

    fun cancel() {
        clear()
        onChanged()
    }

    /** 新しいリストで再生を始めるとき：「終わりで停止」だけ外す（○分後はそのまま） */
    fun resetAtEnd() {
        atEnd = false
    }

    /** 曲・動画が終わったとき：「終わりで停止」なら外して true（再生サービスが止める） */
    fun consumeAtEnd(): Boolean {
        if (!atEnd) return false
        atEnd = false
        onChanged()
        return true
    }

    private fun clear() {
        handler.removeCallbacks(task)
        stopAt = 0
        atEnd = false
    }

    companion object {
        /** 止めるまでの残りの分（切り上げ、最低 1 分）。画面の「あと ○ 分で停止」に使う */
        fun remainingMinutes(stopAt: Long, now: Long): Int =
            kotlin.math.ceil((stopAt - now) / 60_000.0).toInt().coerceAtLeast(1)
    }
}
