package com.ryose.videoplayer

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ProgressBar
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 自由な小窓（ポップアップ再生）。ほかのアプリの上に重ねて表示する、Orbit 自身の小窓。
 * OS の小窓（ピクチャーインピクチャー）と違い、ボタンの数・大きさ・場所（画面の端まで）を自由にできる。
 * 「他のアプリの上に重ねて表示」の許可が必要
 */
@SuppressLint("ClickableViewAccessibility")
class PopupPlayer(private val service: PlaybackService) : PlaybackService.Listener {

    private val ctx = ContextThemeWrapper(service, R.style.Theme_VideoPlayer_Player)
    private val wm = service.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val density get() = ctx.resources.displayMetrics.density

    private var root: View? = null
    private lateinit var params: WindowManager.LayoutParams
    private lateinit var videoFrame: View
    /** 小窓の大きさが変わったら、映像を描く大きさを VLC に伝え直す */
    private val videoSizeListener = View.OnLayoutChangeListener { v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
        if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) setVoutSize(v.width, v.height)
    }
    /**
     * VLC が、実際に描く映像の大きさを知らせてくる。小窓の縦横の比をこれに合わせる
     * （トラックの情報だけでは、大きさが分からない・回転や画素の比が違うことがあり、黒い帯が出ていた）
     */
    private val voutLayoutListener = IVLCVout.OnNewVideoLayoutListener { _, _, _, visibleWidth, visibleHeight, sarNum, sarDen ->
        if (visibleWidth <= 0 || visibleHeight <= 0) return@OnNewVideoLayoutListener
        var w = visibleWidth.toDouble()
        if (sarNum > 0 && sarDen > 0) w = w * sarNum / sarDen
        val a = PopupGeometry.aspect(w.roundToInt(), visibleHeight)
        handler.post {
            layoutAspect = a
            fitAspect(a)
        }
    }
    /** VLC から知らされた映像の比（まだなら null。曲・動画が変わったら知らされ直す） */
    private var layoutAspect: Float? = null

    // 映像が出たかどうかの記録（出なかったときに、どこで止まったかを小窓に出す）
    private var surfacesReady = false
    private var voutShown = false
    private lateinit var statusView: android.widget.TextView
    private val checkVideoTask = Runnable {
        if (root == null || voutShown || service.currentItem?.isAudio != false) return@Runnable
        statusView.text = "映像を出せませんでした\n出し先:" + (if (surfacesReady) "OK" else "未") +
            " 大きさ:${params.width}x${params.height}" +
            " トラック:${service.videoTrack}/${service.videoTracksCount}" +
            " 再生中:${service.isPlaying}"
        statusView.visibility = View.VISIBLE
    }

    /**
     * 映像の出し先（SurfaceView）の準備ができたら、映像を出す。
     * 準備ができる前に映像を出すように頼むと、VLC は「出し先が無い」として映像を出すのをあきらめ、
     * あとで出し先ができてもやり直さない（libVLC の android/display.c と MediaPlayer の作り）。
     * 新しく作った窓の出し先はすぐには準備できないので、小窓では映像が映らなかった
     */
    private val surfacesCallback = object : IVLCVout.Callback {
        override fun onSurfacesCreated(vlcVout: IVLCVout) {
            if (root == null) return
            surfacesReady = true
            handler.removeCallbacks(checkVideoTask)
            handler.postDelayed(checkVideoTask, 4000)
            // いったん映像を止めてから出し直す（準備前に頼んで失敗していても、ここで出し直される）
            service.setVideoEnabled(false)
            service.restoreVideo()
        }

        override fun onSurfacesDestroyed(vlcVout: IVLCVout) {}
    }
    private lateinit var controls: View
    private lateinit var playButton: ImageButton
    private lateinit var prevButton: View
    private lateinit var nextButton: View
    private lateinit var rewindButton: View
    private lateinit var forwardButton: View
    private lateinit var progress: ProgressBar

    /** 長い方の辺の長さ（px） */
    private var size = 0
    /** 縦横の比率（高さ ÷ 幅） */
    private var aspect = 9f / 16f
    private var controlsVisible = false
    private val hideControlsTask = Runnable { setControlsVisible(false) }

    val isShowing: Boolean get() = root != null

    /** 小窓を出す。許可が無いなどで出せなければ false */
    fun show(): Boolean {
        if (root != null) return true
        if (!Settings.canDrawOverlays(service)) return false
        val view = LayoutInflater.from(ctx).inflate(R.layout.popup_player, null)
        videoFrame = view.findViewById(R.id.popupVideo)
        statusView = view.findViewById(R.id.popupStatus)
        surfacesReady = false
        voutShown = false
        controls = view.findViewById(R.id.popupControls)
        playButton = view.findViewById(R.id.popupPlay)
        prevButton = view.findViewById(R.id.popupPrev)
        nextButton = view.findViewById(R.id.popupNext)
        rewindButton = view.findViewById(R.id.popupRewind)
        forwardButton = view.findViewById(R.id.popupForward)
        progress = view.findViewById(R.id.popupProgress)
        setupButtons(view)
        setupTouch(view.findViewById(R.id.popupTouch))
        setupResizeHandle(view.findViewById(R.id.popupResize))

        aspect = currentAspect()
        val (sw, sh) = screenSize()
        size = PopupGeometry.clampSize(
            (AppSettings.popupSizeDp(service) * density).roundToInt(), minSize(), sw, sh, aspect,
        )
        val (w, h) = PopupGeometry.windowSize(size, aspect)
        // 初めてなら右下に出す
        val (x0, y0) = AppSettings.popupPosition(service)
            ?: ((sw - w - (16 * density).toInt()) to (sh - h - (120 * density).toInt()))
        val (x, y) = PopupGeometry.clampPosition(x0, y0, w, h, sw, sh, keepVisible())
        params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // 画面の外にはみ出せるようにして、端に寄せられるようにする。
            // ハードウェアでの描画を有効にする（サービスから出す窓は自動では有効にならない）
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x
            this.y = y
        }
        try {
            wm.addView(view, params)
        } catch (_: Exception) {
            return false
        }
        root = view
        view.keepScreenOn = service.isPlaying
        // 窓の中身（ボタンの後ろの暗い幕など）を、角の丸い形に切り抜く
        view.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
        view.clipToOutline = true

        val subtitles = view.findViewById<android.view.SurfaceView>(R.id.popupSubtitles)
        subtitles.setZOrderMediaOverlay(true)
        subtitles.holder.setFormat(PixelFormat.TRANSLUCENT)
        attachVout(view, w, h)
        videoFrame.addOnLayoutChangeListener(videoSizeListener)
        service.videoUiAttached = true
        service.addListener(this)
        // 映像は、出し先の準備ができたとき（surfacesCallback）に出す
        updateButtons()
        updatePlay()
        updateProgress()
        setControlsVisible(true)
        return true
    }

    /** 映像の出し先（SurfaceView）を VLC に直接つなぐ（PlaybackService.attachPopupVideo） */
    private fun attachVout(view: View, width: Int, height: Int) {
        service.attachPopupVideo(
            view.findViewById(R.id.popupSurface), view.findViewById(R.id.popupSubtitles),
            surfacesCallback, voutLayoutListener, width, height,
        )
    }

    /** 再生サービスが MediaPlayer を取り替えた（次の動画など）：映像の出し先を新しいものにつなぎ直す */
    override fun onPlayerReplaced() {
        val view = root ?: return
        attachVout(view, videoFrame.width.takeIf { it > 0 } ?: params.width, videoFrame.height.takeIf { it > 0 } ?: params.height)
    }

    /** 小窓を閉じる（再生を止めるかどうかは呼び出した側で決める） */
    fun dismiss() {
        val view = root ?: return
        root = null
        layoutAspect = null
        handler.removeCallbacksAndMessages(null)
        service.removeListener(this)
        savePlacement()
        // 映像の出し先が無くなるので映像を止めておく（全画面に戻ったら、そちらで戻す）
        runCatching { service.setVideoEnabled(false) }
        videoFrame.removeOnLayoutChangeListener(videoSizeListener)
        service.detachPopupVideo(surfacesCallback)
        service.videoUiAttached = false
        runCatching { wm.removeView(view) }
    }

    /** 画面が回転したときなど：はみ出しすぎないように置き直す */
    fun onScreenChanged() {
        if (root == null) return
        applySize(size, keepCenter = false)
    }

    // ---------- 再生サービスからの知らせ ----------

    override fun onPlayerEvent(e: MediaPlayer.Event) {
        when (e.type) {
            MediaPlayer.Event.Playing -> {
                updatePlay()
                root?.keepScreenOn = true
                scheduleHide()
            }
            MediaPlayer.Event.Paused, MediaPlayer.Event.Stopped -> {
                updatePlay()
                root?.keepScreenOn = false
                setControlsVisible(true)
            }
            MediaPlayer.Event.LengthChanged, MediaPlayer.Event.TimeChanged -> updateProgress()
            MediaPlayer.Event.Vout -> if (e.voutCount > 0) {
                voutShown = true
                handler.removeCallbacks(checkVideoTask)
                statusView.visibility = View.GONE
                // VLC から大きさを知らされていなければ、トラックの情報から求める
                fitAspect(layoutAspect ?: currentAspect())
            }
        }
    }

    override fun onItemChanged() {
        layoutAspect = null
        // 音楽に切り替わったら小窓は閉じる（音楽はそのまま再生を続ける）
        if (service.currentItem?.isAudio == true) dismiss() else updateButtons()
    }

    override fun onModesChanged() = updateButtons()

    override fun onPlaybackStopped() = dismiss()

    // ---------- ボタン ----------

    private fun setupButtons(view: View) {
        fun View.onTap(action: () -> Unit) = setOnClickListener {
            action()
            scheduleHide()
        }
        val skipMs = AppSettings.doubleTapMs(service)
        view.findViewById<View>(R.id.popupClose).setOnClickListener { service.stopPlayback() }
        view.findViewById<View>(R.id.popupExpand).setOnClickListener { expand() }
        playButton.onTap { service.togglePlay() }
        prevButton.onTap { service.previous() }
        nextButton.onTap { service.next() }
        rewindButton.onTap { service.seekBy(-skipMs) }
        forwardButton.onTap { service.seekBy(skipMs) }
    }

    /** 全画面の再生画面に戻る（再生画面が開いたら、そちらが小窓を閉じる） */
    private fun expand() {
        try {
            service.startActivity(service.screenIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
            dismiss()
        }
    }

    private fun updatePlay() {
        if (root == null) return
        playButton.setImageResource(if (service.isPlaying) R.drawable.ic_pause else R.drawable.ic_play)
    }

    private fun updateProgress() {
        if (root == null) return
        val len = service.lengthMs
        if (len <= 0) {
            progress.visibility = View.INVISIBLE
            return
        }
        progress.visibility = View.VISIBLE
        progress.max = 1000
        progress.progress = (service.position * 1000 / len).toInt()
    }

    /** 小窓の幅に合わせて、出すボタンを増やしたり減らしたりする */
    private fun updateButtons() {
        if (root == null) return
        val count = PopupGeometry.buttonCount(params.width / density, params.height / density)
        playButton.visibility = if (count >= 1) View.VISIBLE else View.GONE
        rewindButton.visibility = if (count >= 3) View.VISIBLE else View.GONE
        forwardButton.visibility = if (count >= 3) View.VISIBLE else View.GONE
        prevButton.visibility = if (count >= 5) View.VISIBLE else View.GONE
        nextButton.visibility = if (count >= 5) View.VISIBLE else View.GONE
        nextButton.alpha = if (service.hasNext()) 1f else 0.4f
    }

    private fun setControlsVisible(visible: Boolean) {
        if (root == null) return
        controlsVisible = visible
        controls.animate().cancel()
        if (visible) {
            controls.visibility = View.VISIBLE
            controls.animate().alpha(1f).setDuration(120).start()
            scheduleHide()
        } else {
            handler.removeCallbacks(hideControlsTask)
            controls.animate().alpha(0f).setDuration(180).withEndAction { controls.visibility = View.GONE }.start()
        }
    }

    private fun scheduleHide() {
        handler.removeCallbacks(hideControlsTask)
        if (controlsVisible && service.isPlaying) handler.postDelayed(hideControlsTask, 3000)
    }

    // ---------- 移動と大きさ ----------
    // ドラッグ：移動（画面の端に寄せられる）／ピンチ・右下のつまみ：大きさ
    // タップ：ボタンを出す・隠す／ダブルタップ：再生・一時停止

    private fun setupTouch(touch: View) {
        val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var scaling = false

        val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaling = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                applySize((size * detector.scaleFactor).roundToInt(), keepCenter = true)
                return true
            }
        })
        val tapDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                setControlsVisible(!controlsVisible)
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                service.togglePlay()
                return true
            }
        })

        touch.setOnTouchListener { _, e ->
            // 指を置いたところで前の操作の状態を消す（先に消さないと、ピンチ直後の 1 回目のタップが効かない）
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                downX = e.rawX
                downY = e.rawY
                startX = params.x
                startY = params.y
                dragging = false
                scaling = false
            }
            scaleDetector.onTouchEvent(e)
            if (!scaling) tapDetector.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (!scaling && e.pointerCount == 1) {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) dragging = true
                    if (dragging) moveTo(startX + dx.roundToInt(), startY + dy.roundToInt())
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging || scaling) savePlacement()
                    dragging = false
                }
            }
            true
        }
    }

    /** 右下のつまみ：左上を動かさずに大きさを変える */
    private fun setupResizeHandle(handle: View) {
        var downX = 0f
        var downY = 0f
        var startSize = 0
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startSize = size
                    handler.removeCallbacks(hideControlsTask)
                }
                MotionEvent.ACTION_MOVE -> {
                    // 斜めに引っぱった量を、長い方の辺の長さに換算する
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    val alongW = if (aspect <= 1f) dx else dx * aspect
                    val alongH = if (aspect <= 1f) dy / aspect else dy
                    val delta = if (abs(alongW) >= abs(alongH)) alongW else alongH
                    applySize(startSize + delta.roundToInt(), keepCenter = false)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    savePlacement()
                    scheduleHide()
                }
            }
            true
        }
    }

    private fun applySize(newSize: Int, keepCenter: Boolean) {
        val view = root ?: return
        val (sw, sh) = screenSize()
        val oldW = params.width
        val oldH = params.height
        size = PopupGeometry.clampSize(newSize, minSize(), sw, sh, aspect)
        val (w, h) = PopupGeometry.windowSize(size, aspect)
        var x = params.x
        var y = params.y
        if (keepCenter) {
            x += (oldW - w) / 2
            y += (oldH - h) / 2
        }
        params.width = w
        params.height = h
        val (cx, cy) = PopupGeometry.clampPosition(x, y, w, h, sw, sh, keepVisible())
        params.x = cx
        params.y = cy
        runCatching { wm.updateViewLayout(view, params) }
        updateButtons()
    }

    /** 小窓の縦横の比を、動画に合わせる（大きさは長い方の辺のまま、真ん中の位置を保つ） */
    private fun fitAspect(a: Float) {
        if (root == null || abs(a - aspect) <= 0.01f) return
        aspect = a
        applySize(size, keepCenter = true)
    }

    private fun moveTo(x: Int, y: Int) {
        val view = root ?: return
        val (sw, sh) = screenSize()
        val (cx, cy) = PopupGeometry.clampPosition(x, y, params.width, params.height, sw, sh, keepVisible())
        params.x = cx
        params.y = cy
        runCatching { wm.updateViewLayout(view, params) }
    }

    private fun savePlacement() {
        if (!::params.isInitialized) return
        AppSettings.setPopupPlacement(service, (size / density).roundToInt(), params.x, params.y)
    }

    private fun setVoutSize(width: Int, height: Int) {
        service.setPopupVideoSize(width, height)
    }

    private fun minSize() = (PopupGeometry.MIN_SIZE_DP * density).roundToInt()

    private fun keepVisible() = (PopupGeometry.KEEP_VISIBLE_DP * density).roundToInt()

    private fun currentAspect(): Float {
        val vt = service.currentVideoTrack() ?: return aspect
        var w = vt.width
        var h = vt.height
        if (vt.sarNum > 0 && vt.sarDen > 0) w = w * vt.sarNum / vt.sarDen
        // スマホで縦に撮った動画などは回転情報を考慮する
        if (vt.orientation >= 4) w = h.also { h = w }
        if (w <= 0 || h <= 0) return aspect
        return PopupGeometry.aspect(w, h)
    }

    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            return b.width() to b.height()
        }
        val m = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(m)
        return m.widthPixels to m.heightPixels
    }
}
