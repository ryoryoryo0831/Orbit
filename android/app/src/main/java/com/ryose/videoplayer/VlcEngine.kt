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

    private var thumbnailInstance: LibVLC? = null

    /**
     * サムネイル作り専用の LibVLC（VlcThumbnailer）。ダイアログ（エラーのお知らせ・ログインの確認）を受け取らないので、
     * 読めないファイルがあっても、一覧を見ているだけでエラーが表示されることはない。設定が変わっても作り直さない
     */
    fun forThumbnails(context: Context): LibVLC = synchronized(lock) {
        thumbnailInstance ?: LibVLC(context.applicationContext, ArrayList(AppSettings.BASE_VLC_OPTIONS + "--no-audio"))
            .also { thumbnailInstance = it }
    }

    /**
     * 今ある LibVLC を返す（無ければ今の設定で作る）。作り直しはしない
     * （サムネイル作りなどで使う。設定が変わっていても、再生中のものを片付けてしまわないように）
     */
    fun current(context: Context): LibVLC = synchronized(lock) {
        instance ?: obtain(context, AppSettings.vlcOptions(context))
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
