package com.ryose.videoplayer

import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.roundToInt

/**
 * 動画の再生画面のジェスチャー（PlayerActivity から分けた）。
 * - タップ：操作パネルの表示・非表示
 * - 左半分の上下：明るさ／右半分の上下：音量／左右：位置の移動
 * - ダブルタップ：左は戻る、右は進む、真ん中は再生・一時停止（秒数は設定の「スキップする秒数」）
 * - 長押し：押している間だけ 2 倍速
 * - ピンチ：拡大（1〜4 倍）
 * 何をするかの判断は GestureRules（テストできる部分）、画面の表示や音量・明るさは Host（PlayerActivity）に任せる
 */
class PlayerGestures(
    private val context: Context,
    /** タッチを受け取る、画面全体の View */
    private val touchLayer: View,
    /** ピンチで拡大する映像の View */
    private val zoomTarget: View,
    private val host: Host,
) {

    /** 再生画面から借りるもの */
    interface Host {
        val service: PlaybackService?
        /** 画面のロック中（タッチを受け付けず、ロック解除のボタンだけ出す） */
        val locked: Boolean
        val lengthMs: Long
        fun toggleControls()
        fun showUnlockBriefly()
        fun showInfo(text: String, autoHide: Boolean)
        /** お知らせを [delayMs] 後に消す */
        fun hideInfoLater(delayMs: Long)
        fun currentBrightness(): Float
        fun setBrightness(value: Float)
        fun currentVolumePercent(): Int
        fun applyVolumePercent(percent: Int)
        fun volumeText(percent: Int): String
    }

    // 設定（画面を開くたびに読み直す。readSettings）
    private var brightnessOn = true
    private var volumeOn = true
    private var seekOn = true
    private var doubleTapOn = true
    private var longPressOn = true
    private var doubleTapMs = 10_000L

    private var gesture = GestureRules.Kind.NONE
    private var gestureStartValue = 0f
    private var seekStartPos = 0L
    private var seekTarget = 0L

    /** ピンチで拡大した倍率（1 で元どおり） */
    private var zoom = 1f
    private var pinching = false

    /** 長押しで 2 倍速にしている間の、元の速さ */
    private var longPressRate: Float? = null

    fun readSettings() {
        brightnessOn = AppSettings.gestureBrightness(context)
        volumeOn = AppSettings.gestureVolume(context)
        seekOn = AppSettings.gestureSeek(context)
        doubleTapOn = AppSettings.gestureDoubleTap(context)
        longPressOn = AppSettings.gestureLongPress(context)
        doubleTapMs = AppSettings.doubleTapMs(context)
    }

    /** 拡大を元に戻す（画面サイズを変えたとき・小窓にしたとき） */
    fun resetZoom() = setZoom(1f)

    private fun setZoom(z: Float) {
        zoom = GestureRules.clampZoom(z)
        zoomTarget.scaleX = zoom
        zoomTarget.scaleY = zoom
    }

    // ---------- 長押しで 2 倍速 ----------

    private fun startLongPressSpeed() {
        val s = host.service ?: return
        longPressRate = s.rate
        s.setPlaybackRate(2f)
        touchLayer.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        host.showInfo("2倍速で再生中", autoHide = false)
    }

    /** 指を離したら（画面を離れたときも）元の速さに戻す */
    fun endLongPressSpeed() {
        val r = longPressRate ?: return
        longPressRate = null
        host.service?.setPlaybackRate(r)
        host.hideInfoLater(0)
    }

    // ---------- タッチ ----------

    @SuppressLint("ClickableViewAccessibility")
    fun attach() {
        val edge = 48 * context.resources.displayMetrics.density
        val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                pinching = true
                gesture = GestureRules.Kind.IGNORE
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setZoom(zoom * detector.scaleFactor)
                host.showInfo(if (zoom <= 1.01f) "ズーム：元のサイズ" else "ズーム  %.1f 倍".format(zoom), autoHide = false)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (zoom < GestureRules.ZOOM_SNAP) setZoom(1f)
                host.hideInfoLater(600)
            }
        })
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            // ダブルタップを使わない設定なら、ダブルタップ待ちをせずにすぐ反応する
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (!doubleTapOn) host.toggleControls()
                return false
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                if (doubleTapOn) host.toggleControls()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                val s = host.service ?: return true
                if (!doubleTapOn) return false
                val sec = doubleTapMs / 1000
                when (GestureRules.doubleTap(e.x, touchLayer.width.toFloat())) {
                    GestureRules.DoubleTap.REWIND -> { s.seekBy(-doubleTapMs); host.showInfo("${sec}秒戻る", autoHide = true) }
                    GestureRules.DoubleTap.FORWARD -> { s.seekBy(doubleTapMs); host.showInfo("${sec}秒進む", autoHide = true) }
                    GestureRules.DoubleTap.TOGGLE_PLAY -> s.togglePlay()
                }
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val s = host.service ?: return
                if (!longPressOn || pinching || gesture != GestureRules.Kind.NONE || !s.isPlaying) return
                // 指を離すまで、ほかのジェスチャーは受け付けない
                gesture = GestureRules.Kind.IGNORE
                startLongPressSpeed()
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                val s = host.service ?: return false
                if (e1 == null) return false
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                val w = touchLayer.width.toFloat()
                val h = touchLayer.height.toFloat()
                if (gesture == GestureRules.Kind.NONE) {
                    gesture = GestureRules.classify(e1.x, e1.y, dx, dy, w, h, edge, brightnessOn, volumeOn, seekOn)
                    gestureStartValue = when (gesture) {
                        GestureRules.Kind.BRIGHTNESS -> host.currentBrightness()
                        GestureRules.Kind.VOLUME -> host.currentVolumePercent().toFloat()
                        else -> 0f
                    }
                    seekStartPos = s.position
                    seekTarget = seekStartPos
                }
                when (gesture) {
                    GestureRules.Kind.BRIGHTNESS -> {
                        val v = GestureRules.brightness(gestureStartValue, dy, h)
                        host.setBrightness(v)
                        host.showInfo("明るさ ${(v * 100).roundToInt()}%", autoHide = false)
                    }
                    GestureRules.Kind.VOLUME -> {
                        // 画面の高さ 1.5 倍分で 0〜100%。ブーストがオンなら 200% まで
                        val limit = if (AppSettings.audioBoost(context)) 200 else 100
                        val v = GestureRules.volume(gestureStartValue, dy, h, limit)
                        host.applyVolumePercent(v)
                        host.showInfo(host.volumeText(v), autoHide = false)
                    }
                    GestureRules.Kind.SEEK -> {
                        val len = host.lengthMs
                        if (len > 0) {
                            seekTarget = GestureRules.seekTarget(seekStartPos, dx, w, len)
                            val diff = (seekTarget - seekStartPos) / 1000
                            val sign = if (diff >= 0) "+" else ""
                            host.showInfo("${formatTime(seekTarget)} / ${formatTime(len)}\n($sign${diff}秒)", autoHide = false)
                        }
                    }
                    else -> {}
                }
                return true
            }
        })

        // TalkBack などのユーザー補助から「クリック」されたとき（普通のタップは下の OnTouchListener が受け取るので、ここには来ない）
        touchLayer.setOnClickListener {
            if (host.locked) host.showUnlockBriefly() else host.toggleControls()
        }
        touchLayer.setOnTouchListener { _, e ->
            if (host.locked) {
                if (e.actionMasked == MotionEvent.ACTION_UP) host.showUnlockBriefly()
                return@setOnTouchListener true
            }
            scaleDetector.onTouchEvent(e)
            // 2 本指で拡大している間は、ほかのジェスチャーは受け付けない
            if (!pinching && e.pointerCount == 1) detector.onTouchEvent(e)
            val action = e.actionMasked
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                pinching = false
                endLongPressSpeed()
                val was = gesture
                gesture = GestureRules.Kind.NONE
                if (was == GestureRules.Kind.SEEK && action == MotionEvent.ACTION_UP) host.service?.seekTo(seekTarget)
                if (was != GestureRules.Kind.NONE && was != GestureRules.Kind.IGNORE) host.hideInfoLater(600)
            }
            true
        }
    }
}
