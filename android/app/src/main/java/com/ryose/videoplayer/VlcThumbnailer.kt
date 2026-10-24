package com.ryose.videoplayer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.net.Uri
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.view.Surface
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.interfaces.IVLCVout
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * libVLC で動画の 1 コマを取り出してサムネイルにする。
 * Android のサムネイル作り（MediaMetadataRetriever）が読めない形式（WMV・FLV・一部の AVI など）に使う。
 *
 * libVLC 3 の Java には 1 コマを画像にする機能が無いので、画面に出さない再生を始めて、
 * 映像を SurfaceTexture（画面ではない描き先）に描かせ、それを OpenGL で読み取る。
 * 時間がかかるので、裏のスレッドから呼ぶこと（メインスレッドから呼ぶと何もしない）
 */
object VlcThumbnailer {
    /** 1 本ずつ作る（同時にいくつも再生すると重い） */
    private val lock = Any()

    fun frame(context: Context, path: String?, uri: Uri, longSide: Int = 384): Bitmap? {
        if (Looper.myLooper() == Looper.getMainLooper()) return null
        return synchronized(lock) {
            runCatching { Job(context.applicationContext, path, uri, longSide).run() }.getOrNull()
        }
    }

    private class Job(
        private val context: Context,
        private val path: String?,
        private val uri: Uri,
        private val longSide: Int,
    ) {
        private val main = Handler(Looper.getMainLooper())
        private val glThread = HandlerThread("vlc-thumbnail").apply { start() }
        private val gl = Handler(glThread.looper)
        private val done = CountDownLatch(1)

        @Volatile private var result: Bitmap? = null
        @Volatile private var finished = false
        /** VLC が知らせてくる映像の大きさ（画素の比も入れた幅・高さ） */
        @Volatile private var videoW = 0
        @Volatile private var videoH = 0

        // OpenGL（gl のスレッドだけで使う）
        private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
        private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        private var program = 0
        private var oesTexture = 0
        private var surfaceTexture: SurfaceTexture? = null
        private var surface: Surface? = null
        private var frames = 0
        private val texMatrix = FloatArray(16)

        private var player: MediaPlayer? = null
        private var pfd: ParcelFileDescriptor? = null

        fun run(): Bitmap? {
            try {
                if (!onThread(gl) { setupGl() }) return null
                val s = surface ?: return null
                val lib = VlcEngine.forThumbnails(context)
                val mp = MediaPlayer(lib)
                player = mp
                // 映像の出し先をつなぐのはメインスレッドで（libVLC の決まり）
                val attached = onThread(main) {
                    val vout = mp.vlcVout
                    vout.setVideoSurface(s, null)
                    vout.attachViews(IVLCVout.OnNewVideoLayoutListener { _, _, _, visibleW, visibleH, sarNum, sarDen ->
                        if (visibleW > 0 && visibleH > 0) {
                            videoW = if (sarNum > 0 && sarDen > 0) visibleW * sarNum / sarDen else visibleW
                            videoH = visibleH
                        }
                    })
                    vout.setWindowSize(DEFAULT_W, DEFAULT_H)
                }
                if (!attached) return null
                mp.setEventListener(object : MediaPlayer.EventListener {
                    override fun onEvent(event: MediaPlayer.Event) {
                        if (event.type == MediaPlayer.Event.EncounteredError || event.type == MediaPlayer.Event.EndReached) finish(null)
                    }
                })
                val media = openMedia(lib) ?: return null
                // 音は出さず、字幕も付けない。デコードはソフトウェアで（再生中の動画とハードウェアを取り合わないように）
                media.setHWDecoderEnabled(false, false)
                media.addOption(":no-audio")
                media.addOption(":no-spu")
                // Android のサムネイルと同じく、1 秒あたりの場面
                media.addOption(":start-time=1")
                mp.media = media
                media.release()
                mp.play()
                done.await(if (isNetwork) NETWORK_TIMEOUT_MS else TIMEOUT_MS, TimeUnit.MILLISECONDS)
                finished = true
                return result
            } finally {
                cleanup()
            }
        }

        /** NAS・DLNA・URL の動画か（端末内のファイルではない） */
        private val isNetwork: Boolean get() = path == null && uri.scheme?.lowercase() in NETWORK_SCHEMES

        private fun openMedia(lib: org.videolan.libvlc.interfaces.ILibVLC): Media? = when {
            path != null && File(path).canRead() -> Media(lib, path)
            uri.scheme == "content" -> runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
                ?.let { p -> pfd = p; Media(lib, p.fileDescriptor) }
            // ネットワークの動画は、再生と同じログイン情報で開く。読み込みは先頭と 1 秒あたりだけなので、先読みは短く
            isNetwork -> Media(lib, uri).also { m ->
                ServerStore(context).optionsFor(uri).forEach { m.addOption(it) }
                m.addOption(":network-caching=1500")
            }
            else -> null
        }

        private fun finish(bitmap: Bitmap?) {
            if (finished) {
                bitmap?.recycle()
                return
            }
            finished = true
            result = bitmap
            done.countDown()
        }

        /** 別のスレッドで処理して、終わるまで待つ（2 秒まで） */
        private fun onThread(handler: Handler, block: () -> Unit): Boolean {
            val latch = CountDownLatch(1)
            var ok = false
            handler.post {
                ok = runCatching(block).isSuccess
                latch.countDown()
            }
            return latch.await(2, TimeUnit.SECONDS) && ok
        }

        private fun cleanup() {
            finished = true
            player?.let { mp ->
                mp.setEventListener(null as MediaPlayer.EventListener?)
                runCatching { mp.stop() }
                onThread(main) { mp.vlcVout.detachViews() }
                runCatching { mp.release() }
            }
            player = null
            runCatching { pfd?.close() }
            onThread(gl) { releaseGl() }
            glThread.quitSafely()
        }

        // ---------- OpenGL ----------

        private fun setupGl() {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            val version = IntArray(2)
            check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1))
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            EGL14.eglChooseConfig(
                eglDisplay,
                intArrayOf(
                    EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                    EGL14.EGL_NONE,
                ),
                0, configs, 0, 1, count, 0,
            )
            val config = configs[0] ?: error("EGL の設定が見つからない")
            eglContext = EGL14.eglCreateContext(
                eglDisplay, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0,
            )
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
            check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext))

            program = buildProgram()
            val tex = IntArray(1)
            GLES20.glGenTextures(1, tex, 0)
            oesTexture = tex[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val st = SurfaceTexture(oesTexture)
            st.setDefaultBufferSize(DEFAULT_W, DEFAULT_H)
            st.setOnFrameAvailableListener({ onFrame() }, gl)
            surfaceTexture = st
            surface = Surface(st)
        }

        /** 映像が 1 コマ届いた。暗すぎるコマ（始まりの黒い画面など）は飛ばして、次のコマを待つ */
        private fun onFrame() {
            val st = surfaceTexture ?: return
            runCatching { st.updateTexImage() }.onFailure { return }
            if (finished) return
            frames++
            // 大きさがまだ分からなければ、次のコマを待つ（来なければ 16:9 で）
            if ((videoW <= 0 || videoH <= 0) && frames < 5) return
            st.getTransformMatrix(texMatrix)
            val bitmap = runCatching { render() }.getOrNull() ?: return finish(null)
            if (isMostlyDark(bitmap) && frames < MAX_FRAMES) {
                bitmap.recycle()
                return
            }
            finish(bitmap)
        }

        /** 届いたコマを、長い辺 [longSide] の大きさで描いて読み取る */
        private fun render(): Bitmap {
            val vw = if (videoW > 0) videoW else 16
            val vh = if (videoH > 0) videoH else 9
            val (w, h) = if (vw >= vh) longSide to (longSide * vh.toFloat() / vw).roundToInt().coerceAtLeast(1)
            else (longSide * vw.toFloat() / vh).roundToInt().coerceAtLeast(1) to longSide

            // 読み取り用の描き先（フレームバッファ）
            val ids = IntArray(2)
            GLES20.glGenTextures(1, ids, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            GLES20.glGenFramebuffers(1, ids, 1)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, ids[1])
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, ids[0], 0)
            try {
                GLES20.glViewport(0, 0, w, h)
                GLES20.glUseProgram(program)
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTexture)
                GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uTexMatrix"), 1, false, texMatrix, 0)
                GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "sTex"), 0)
                val aPos = GLES20.glGetAttribLocation(program, "aPos")
                val aTex = GLES20.glGetAttribLocation(program, "aTex")
                GLES20.glEnableVertexAttribArray(aPos)
                GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 0, POSITIONS)
                GLES20.glEnableVertexAttribArray(aTex)
                GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 0, TEX_COORDS)
                GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

                val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
                GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                buf.rewind()
                return createBitmap(w, h).apply { copyPixelsFromBuffer(buf) }
            } finally {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glDeleteFramebuffers(1, ids, 1)
                GLES20.glDeleteTextures(1, ids, 0)
            }
        }

        /** ほとんど真っ黒なコマか（ところどころの点の明るさで見る） */
        private fun isMostlyDark(b: Bitmap): Boolean {
            var sum = 0L
            var n = 0
            val stepX = (b.width / 16).coerceAtLeast(1)
            val stepY = (b.height / 16).coerceAtLeast(1)
            var y = stepY / 2
            while (y < b.height) {
                var x = stepX / 2
                while (x < b.width) {
                    val c = b[x, y]
                    sum += ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
                    n++
                    x += stepX
                }
                y += stepY
            }
            return n > 0 && sum / n < 16
        }

        private fun buildProgram(): Int {
            fun shader(type: Int, src: String): Int = GLES20.glCreateShader(type).also {
                GLES20.glShaderSource(it, src)
                GLES20.glCompileShader(it)
            }
            val p = GLES20.glCreateProgram()
            GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER))
            GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER))
            GLES20.glLinkProgram(p)
            return p
        }

        private fun releaseGl() {
            runCatching { surface?.release() }
            runCatching { surfaceTexture?.release() }
            surface = null
            surfaceTexture = null
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                if (program != 0) GLES20.glDeleteProgram(program)
                if (oesTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(oesTexture), 0)
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
                // 表示（EGLDisplay）はアプリ全体で共通なので、終了（eglTerminate）はしない
                EGL14.eglReleaseThread()
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }

    private const val DEFAULT_W = 640
    private const val DEFAULT_H = 360
    private const val TIMEOUT_MS = 8_000L
    /** ネットワークの動画は、つなぐ時間があるので長めに */
    private const val NETWORK_TIMEOUT_MS = 15_000L
    private val NETWORK_SCHEMES = setOf("smb", "ftp", "ftps", "ftpes", "sftp", "http", "https", "nfs", "upnp")
    /** 暗いコマを飛ばすのは、ここまで（約 2 秒分） */
    private const val MAX_FRAMES = 60

    private const val VERTEX_SHADER = """
        attribute vec4 aPos;
        attribute vec4 aTex;
        uniform mat4 uTexMatrix;
        varying vec2 vTex;
        void main() {
            gl_Position = aPos;
            vTex = (uTexMatrix * aTex).xy;
        }
    """

    private const val FRAGMENT_SHADER = """#extension GL_OES_EGL_image_external : require
        precision mediump float;
        varying vec2 vTex;
        uniform samplerExternalOES sTex;
        void main() {
            gl_FragColor = texture2D(sTex, vTex);
        }
    """

    /**
     * 四角の頂点。上下を逆に描いておくと、glReadPixels（下の行から読む）の結果が、そのまま上の行からの画像になる
     */
    private val POSITIONS = floatBuffer(-1f, 1f, 1f, 1f, -1f, -1f, 1f, -1f)
    private val TEX_COORDS = floatBuffer(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

    private fun floatBuffer(vararg v: Float) =
        ByteBuffer.allocateDirect(v.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(v)
            rewind()
        }
}
