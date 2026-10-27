package com.ryose.videoplayer

import android.app.Application
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.core.graphics.scale
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.request.Options
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath
import java.io.File

/** 音楽ファイルに埋め込まれたジャケット画像（一覧のサムネイル用） */
data class AudioArt(val path: String)

/** 動画のサムネイル（一覧用）。uri は MediaStore のものなら Android が作ったサムネイルを使える */
data class VideoThumb(val path: String?, val uri: Uri)

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        // 落ちたときに記録を残す（設定の「落ちたときの記録」で見られる）。ほかの処理で落ちても残るように最初に
        CrashLog.install(this)
        // 再生の記録（設定の「再生の記録」で見られる）
        PlaybackLog.init(this)
        AppSettings.applyTheme(this)
        // 古いデータの片付け（起動を遅くしないように裏で。最初の一覧を出す・VLC を用意するのと CPU を取り合わないよう、少し後で）
        CoroutineScope(Dispatchers.IO).launch {
            kotlinx.coroutines.delay(8_000)
            runCatching { ThumbCache.trim(this@App) }
            runCatching { ResumeStore(this@App).prune() }
        }
        // 登録したサーバーのパスワードを戻す処理（鍵保管庫）は重いので、画面で使う前に裏で済ませておく
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { ServerStore(this@App).preload() }
        }
        // VLC の本体（大きなライブラリの読み込みを含む）と、プレイリストのファイルも先に裏で用意しておく
        // （動画を開いたとき・一覧を最初に出すときに、画面が止まらないように）
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { VlcEngine.warmUp(this@App) }
        }
        CoroutineScope(Dispatchers.IO).launch {
            runCatching { PlaylistStore(this@App).all() }
        }
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .components {
                add(VideoThumbFetcher.Factory()) // 動画のサムネイル
                add(AudioArtFetcher.Factory())   // 音楽のジャケット
                add(Keyer<AudioArt> { data, _ -> "audioart:${data.path}" })
                add(Keyer<VideoThumb> { data, _ -> "thumb:${data.path ?: data.uri}" })
            }
            .crossfade(true)
            // サムネイル・ジャケットを作る処理（動画から場面を取り出すなど）は重いので、同時に 3 つまでにする。
            // 速くスクロールしたときに何十も同時に走って、端末全体（再生も）が重くなっていた
            .fetcherDispatcher(Dispatchers.IO.limitedParallelism(3))
            .build()
}

/**
 * 作ったサムネイルをファイルに保存しておく場所。
 * 毎回動画から画像を作ると重いので、一度作ったら次からはこのファイルを使う（元のファイルが更新されたら作り直す）
 */
object ThumbCache {
    private const val MAX_FILES = 4000
    private const val SIZE = 384

    /** サムネイルの作り方の版（変えたら、前の作り方のものは最初に使うときに一度だけ全部消す） */
    private const val VERSION = 2
    @Volatile private var checked = false

    private fun dir(context: Context): File {
        val dir = File(context.cacheDir, "thumbs").apply { mkdirs() }
        if (!checked) synchronized(this) {
            if (!checked) {
                // 2：サムネイルの場面を、冒頭から長さの 1 割あたりに変えた
                val mark = File(dir, ".v$VERSION")
                if (!mark.exists()) {
                    dir.listFiles()?.forEach { it.delete() }
                    runCatching { mark.createNewFile() }
                }
                checked = true
            }
        }
        return dir
    }

    fun fileFor(context: Context, key: String, modified: Long): File =
        File(dir(context), "${key.hashCode().toUInt().toString(16)}_${modified.toString(16)}.jpg")

    /** 作れなかったもの（アプリを閉じるまで覚えておき、表示のたびに作り直そうとしない） */
    private val failed = java.util.Collections.synchronizedSet(HashSet<String>())

    /** キャッシュにあればそれを返し、無ければ make で作って保存する */
    fun getOrCreate(context: Context, key: String, modified: Long, rememberFailure: Boolean = true, make: () -> Bitmap?): File? {
        val file = fileFor(context, key, modified)
        if (file.length() > 0) {
            // 使った印（増えすぎたときに古いものから消すため）。毎回書くと重いので、1 日に 1 回まで
            val now = System.currentTimeMillis()
            if (now - file.lastModified() > 24 * 60 * 60 * 1000L) file.setLastModified(now)
            return file
        }
        if (file.name in failed) return null
        val bitmap = make() ?: run {
            if (rememberFailure) failed += file.name
            return null
        }
        val scaled = scaleDown(bitmap)
        // 同じサムネイルを同時に作っても混ざらないよう、書き込みごとに別の一時ファイルを使う
        var tmp: File? = null
        val ok = try {
            val t = File.createTempFile("thumb", ".tmp", file.parentFile)
            tmp = t
            t.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, 85, it) } &&
                (t.renameTo(file) || file.length() > 0)
        } catch (_: Exception) {
            false
        } finally {
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            // 置き換えに使われなかった一時ファイルは消す
            tmp?.takeIf { it.exists() }?.delete()
        }
        return if (ok && file.length() > 0) file else null
    }

    private fun scaleDown(b: Bitmap): Bitmap {
        val longSide = maxOf(b.width, b.height)
        if (longSide <= SIZE) return b
        val ratio = SIZE.toFloat() / longSide
        return b.scale((b.width * ratio).toInt().coerceAtLeast(1), (b.height * ratio).toInt().coerceAtLeast(1))
    }

    /** 増えすぎたら、しばらく使っていないものから消す */
    fun trim(context: Context) {
        val files = dir(context).listFiles()?.filter { it.name.endsWith(".jpg") } ?: return
        if (files.size <= MAX_FILES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_FILES * 3 / 4).forEach { it.delete() }
    }

    fun source(file: File) = SourceResult(
        source = ImageSource(file.toOkioPath()),
        mimeType = "image/jpeg",
        dataSource = DataSource.DISK,
    )
}

private class VideoThumbFetcher(private val data: VideoThumb, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        // ISO（ディスクイメージ）からは場面を取り出せない（何 GB もあるファイルを調べに行って時間だけかかる）
        if ((data.path ?: data.uri.toString()).lowercase().endsWith(".iso")) throw IllegalStateException("ディスクイメージ")
        val context = options.context
        val modified = data.path?.let { File(it).lastModified() } ?: 0L
        val key = data.path ?: data.uri.toString()
        // ネットワークの動画は Android のサムネイル作りでは開けないので、最初から VLC で（結果は端末に覚えておく）
        val isNetwork = data.path == null && data.uri.scheme?.lowercase() in setOf("smb", "ftp", "ftps", "ftpes", "sftp", "http", "https", "nfs", "upnp")
        val file = (if (isNetwork) null else ThumbCache.getOrCreate(context, key, modified, rememberFailure = false) {
            // Android が作っておいたサムネイルは冒頭の場面なので、取り出せないときだけ使う
            frameFromVideo(context) ?: systemThumbnail(context)
        }) ?: withContext(VLC_DISPATCHER) {
            // Android が読めない形式（WMV・FLV・一部の AVI など）は、VLC で 1 コマを取り出す。
            // 時間がかかるので、ほかのサムネイル作りの枠とは別に 1 本ずつ。スクロールして要らなくなったものは始めない
            coroutineContext.ensureActive()
            ThumbCache.getOrCreate(context, key, modified) { VlcThumbnailer.frame(context, data.path, data.uri) }
        } ?: throw IllegalStateException("サムネイルを作れませんでした")
        return ThumbCache.source(file)
    }

    private companion object {
        val VLC_DISPATCHER = Dispatchers.IO.limitedParallelism(1)
    }

    /**
     * Android が作っておいたサムネイル（MediaStore の動画なら速い）。
     * 「フォルダ」タブの行はファイルの場所（file://）なので、MediaStore に登録されていればその番号を引いて使う
     * （以前は毎回、動画を開いて 1 コマ取り出していた）
     */
    private fun systemThumbnail(context: Context): Bitmap? {
        if (Build.VERSION.SDK_INT < 29) return null
        val uri = when {
            data.uri.scheme == ContentResolver.SCHEME_CONTENT && data.uri.authority == MediaStore.AUTHORITY -> data.uri
            data.path != null -> mediaStoreUriOf(context, data.path)
            else -> null
        } ?: return null
        return runCatching { context.contentResolver.loadThumbnail(uri, Size(512, 512), null) }.getOrNull()
    }

    /** ファイルの場所から、MediaStore の動画の番号（content://）を引く。登録されていなければ null */
    @Suppress("DEPRECATION")
    private fun mediaStoreUriOf(context: Context, path: String): android.net.Uri? = runCatching {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        context.contentResolver.query(
            collection, arrayOf(MediaStore.Video.Media._ID), "${MediaStore.Video.Media.DATA} = ?", arrayOf(path), null,
        )?.use { c -> if (c.moveToFirst()) android.content.ContentUris.withAppendedId(collection, c.getLong(0)) else null }
    }.getOrNull()

    /** 動画の長さの 1 割あたりの場面を取り出す（[ThumbRules]） */
    private fun frameFromVideo(context: Context): Bitmap? {
        val r = MediaMetadataRetriever()
        return try {
            if (data.path != null && File(data.path).canRead()) r.setDataSource(data.path) else r.setDataSource(context, data.uri)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val us = ThumbRules.positionMs(duration) * 1000
            val frame = if (Build.VERSION.SDK_INT >= 27) {
                r.getScaledFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 512, 512)
            } else {
                r.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
            frame ?: r.frameAtTime
        } catch (_: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    class Factory : Fetcher.Factory<VideoThumb> {
        override fun create(data: VideoThumb, options: Options, imageLoader: ImageLoader): Fetcher =
            VideoThumbFetcher(data, options)
    }
}

private class AudioArtFetcher(private val data: AudioArt, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val file = ThumbCache.getOrCreate(options.context, "art:${data.path}", File(data.path).lastModified()) {
            val r = MediaMetadataRetriever()
            val bytes = try {
                r.setDataSource(data.path)
                r.embeddedPicture
            } catch (_: Exception) {
                null
            } finally {
                runCatching { r.release() }
            }
            bytes?.let { decodeSmall(it) }
        } ?: throw IllegalStateException("ジャケット画像がありません")
        return ThumbCache.source(file)
    }

    /** 大きなジャケット画像（3000 ピクセル四方など）を、そのまま読むとメモリと時間を使うので、縮小して読む */
    private fun decodeSmall(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= 384 && bounds.outHeight / (sample * 2) >= 384) sample *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    class Factory : Fetcher.Factory<AudioArt> {
        override fun create(data: AudioArt, options: Options, imageLoader: ImageLoader): Fetcher =
            AudioArtFetcher(data, options)
    }
}
