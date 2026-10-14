package com.ryose.videoplayer

import android.content.Context
import org.videolan.libvlc.LibVLC

/**
 * VLC の本体（LibVLC）。作るのに時間がかかる（大きなライブラリの読み込み・部品の一覧づくり）ので、
 * アプリの中で 1 つだけ作り、再生サービスが終わっても捨てずに使い回す。
 * 以前は動画を開くたびに画面の処理の中で作り直していて、開くときに画面が止まっていた
 */
object VlcEngine {
    private val lock = Any()
    private var instance: LibVLC? = null
    private var instanceOptions: List<String>? = null

    /** アプリの起動時に裏で作っておく */
    fun warmUp(context: Context) {
        obtain(context, AppSettings.vlcOptions(context))
    }

    /**
     * [options] で作った LibVLC を返す（同じオプションのものがあれば使い回す）。
     * 設定が変わってオプションが違うときは作り直し、古いものは片付ける（古いものの MediaPlayer は先に片付けておくこと）
     */
    fun obtain(context: Context, options: List<String>): LibVLC = synchronized(lock) {
        instance?.let { if (instanceOptions == options) return it }
        val old = instance
        val created = try {
            LibVLC(context.applicationContext, ArrayList(options))
        } catch (_: Exception) {
            // 万一オプションが受け付けられなかった場合は、基本のオプションだけで作る
            LibVLC(context.applicationContext, ArrayList(AppSettings.BASE_VLC_OPTIONS))
        }
        instance = created
        instanceOptions = options
        old?.let { runCatching { it.release() } }
        created
    }
}
