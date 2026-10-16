package com.ryose.videoplayer

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import org.videolan.libvlc.Media
import org.videolan.libvlc.interfaces.ILibVLC
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.MediaBrowser

/**
 * ネットワーク上のフォルダ（NAS の共有フォルダ・DLNA など）の中の動画・音楽を、サブフォルダの中までたどって集める。
 * MediaBrowser で 1 フォルダずつ順番に読む（一度に読むとサーバーに負担がかかり、ログインの確認も重なるため）。
 * 並びは端末内のフォルダと同じ（直下のファイルを名前順に、続けてサブフォルダを名前順に、その中も同じように）。
 * MediaBrowser は画面の処理の中（メインスレッド）で使う決まりなので、ここもメインスレッドから使う
 */
class NetTreeCollector(
    private val context: Context,
    private val libVLC: ILibVLC,
    root: Uri,
    /** false ならそのフォルダの直下だけ */
    private val includeSubfolders: Boolean,
    /** 読み進めるたびに（読んだフォルダの数・見つかった件数） */
    private val onProgress: (folders: Int, found: Int) -> Unit,
    /** 終わったとき（集めたもの・件数の上限で打ち切ったか） */
    private val onDone: (items: List<PlaylistItem>, truncated: Boolean) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val pending = ArrayDeque<Uri>().apply { add(root) }
    private val visited = HashSet<String>()
    private val result = ArrayList<PlaylistItem>()
    private var folders = 0
    private var browser: MediaBrowser? = null
    private var finished = false

    /** 今読んでいるフォルダの中身 */
    private val dirs = ArrayList<Pair<String, Uri>>()
    private val files = ArrayList<PlaylistItem>()

    /** 返事の無いフォルダは飛ばして次へ */
    private val timeoutTask = Runnable { endFolder() }

    fun start() = next()

    /** 途中でやめる（画面を閉じた・キャンセルを押した） */
    fun cancel() {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        browser?.release()
        browser = null
    }

    private fun next() {
        if (finished) return
        if (result.size >= LIMIT || folders >= MAX_FOLDERS) return finish(truncated = true)
        val uri = pending.removeFirstOrNull() ?: return finish(truncated = false)
        if (!visited.add(uri.toString())) return next()
        dirs.clear()
        files.clear()
        val b = browser ?: MediaBrowser(libVLC, listener).also { browser = it }
        val media = Media(libVLC, uri)
        ServerStore(context).optionsFor(uri).forEach { media.addOption(it) }
        handler.postDelayed(timeoutTask, FOLDER_TIMEOUT_MS)
        b.browse(media, MediaBrowser.Flag.Interact or MediaBrowser.Flag.NoSlavesAutodetect)
        media.release()
    }

    private val listener = object : MediaBrowser.EventListener {
        override fun onMediaAdded(index: Int, media: IMedia) {
            val uri = media.uri ?: return
            val name = media.getMeta(IMedia.Meta.Title)?.takeIf { it.isNotBlank() }
                ?: Uri.decode(uri.lastPathSegment ?: uri.toString())
            if (media.type == IMedia.Type.Directory) {
                dirs += name to uri
            } else if (isPlayable(name, uri)) {
                files += PlaylistItem(uri, name, durationMs = media.duration.coerceAtLeast(0))
            }
        }

        override fun onMediaRemoved(index: Int, media: IMedia) {}

        override fun onBrowseEnd() = endFolder()
    }

    private fun endFolder() {
        if (finished) return
        handler.removeCallbacks(timeoutTask)
        folders++
        result += files.sortedWith(compareBy(NaturalOrder) { it.title })
        if (includeSubfolders) {
            // サブフォルダは、名前順に前へ積む（このフォルダの中を先に、深くたどる）
            val subs = dirs.sortedWith(compareBy(NaturalOrder) { it.first }).map { it.second }
            subs.asReversed().forEach { pending.addFirst(it) }
        }
        onProgress(folders, result.size)
        // MediaBrowser の知らせの中から次を読み始めないように、いったん返してから
        handler.post { next() }
    }

    private fun finish(truncated: Boolean) {
        if (finished) return
        finished = true
        handler.removeCallbacksAndMessages(null)
        browser?.release()
        browser = null
        onDone(result.take(LIMIT), truncated)
    }

    /**
     * 再生できそうなファイルか。拡張子が動画・音楽のもの、または拡張子が無いもの（DLNA は名前に拡張子が無いことが多い）。
     * 画像・字幕・説明のファイルなどは入れない
     */
    private fun isPlayable(name: String, uri: Uri): Boolean {
        val ext = (uri.lastPathSegment ?: name).substringAfterLast('/').let {
            if ('.' in it) it.substringAfterLast('.').lowercase() else ""
        }
        val nameExt = if ('.' in name) name.substringAfterLast('.').lowercase() else ""
        return ext.isEmpty() && nameExt.isEmpty() ||
            ext in MediaFiles.VIDEO_EXT || ext in MediaFiles.AUDIO_EXT ||
            nameExt in MediaFiles.VIDEO_EXT || nameExt in MediaFiles.AUDIO_EXT
    }

    companion object {
        /** 集める件数の上限（端末内のフォルダと同じ） */
        const val LIMIT = 5000
        /** たどるフォルダの数の上限（ネットワークの全部をたどり続けないように） */
        private const val MAX_FOLDERS = 2000
        private const val FOLDER_TIMEOUT_MS = 20_000L
    }
}
