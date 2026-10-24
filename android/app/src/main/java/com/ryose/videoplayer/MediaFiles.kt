package com.ryose.videoplayer

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import java.io.File

/** 端末内の動画ファイルの検索やフォルダの一覧 */
object MediaFiles {
    val VIDEO_EXT = setOf(
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "wmv", "flv", "f4v", "mpg", "mpeg", "m2v",
        "ts", "m2ts", "mts", "3gp", "3g2", "ogv", "vob", "rm", "rmvb", "asf", "divx", "xvid",
        // ビデオカメラ（AVCHD・HDV・JVC/Panasonic の古い形式）、QuickTime・DV・OGM・MXF
        "m2t", "mod", "tod", "mpe", "qt", "dv", "ogm", "mxf",
        // DVD・Blu-ray のディスクイメージ（libVLC の dvdnav・libbluray で再生する）
        "iso",
    )

    val AUDIO_EXT = setOf(
        "mp3", "m4a", "m4b", "aac", "flac", "wav", "ogg", "oga", "opus", "wma", "ape", "aif", "aiff",
        "mka", "wv", "tta", "dsf", "dff", "ac3", "dts", "amr", "mpc",
    )

    fun isVideo(f: File) = f.isFile && f.extension.lowercase() in VIDEO_EXT

    fun isAudio(f: File) = f.isFile && f.extension.lowercase() in AUDIO_EXT

    fun isMedia(f: File) = isVideo(f) || isAudio(f)

    /**
     * テレビ録画・DVD・ビデオカメラの映像（MPEG-2・AVCHD など）。インターレース（横縞）のことが多い形式
     */
    private val INTERLACED_EXT = setOf("mpg", "mpeg", "mpe", "m2v", "vob", "ts", "m2ts", "mts", "m2t", "mod", "tod", "dv", "iso")

    fun isLikelyInterlacedName(name: String) = name.substringAfterLast('.', "").lowercase() in INTERLACED_EXT

    /**
     * ハードウェアデコードの設定がオンでも、ソフトウェアで再生する形式（WMV・ASF・ISO・AVI）。
     * テレビ（Fire TV・MediaTek）のハードウェアの WMV デコーダー（c2.mtk.wmv3.decoder）では、
     * NAS の WMV9 が真っ黒になった（デコードのエラーが数百回。2026-10-17 に実機で確認）。
     * ソフトウェアなら正しく映り、CPU もほとんど使わない（約 0.15 コア）。動画ごとには︙の「デコード」で切り替えられる。
     * DVD の ISO も同じ：テレビのハードウェアの MPEG-2 デコーダー（c2.mtk.mpeg2.decoder）は、1 コマだけの静止画
     * （著作権の注意・静止画のメニュー）を出さず、真っ黒のまま進んだ（2026-10-17 に実機で確認。ソフトウェアなら映り、CPU は約 6%）。
     * AVI は、スマホのハードウェアデコードだとカクついた（2026-10-17、ユーザーの報告）。AVI の中身は MPEG-4（DivX・Xvid）や
     * Motion JPEG などの SD が多く、ソフトウェアでも軽い
     */
    private val SOFTWARE_DECODE_EXT = setOf("wmv", "asf", "iso", "avi")

    fun prefersSoftwareDecoding(name: String) = name.substringAfterLast('.', "").lowercase() in SOFTWARE_DECODE_EXT

    fun isAudioName(name: String) = name.substringAfterLast('.', "").lowercase() in AUDIO_EXT

    private fun visibleChildren(dir: File): List<File> =
        dir.listFiles()?.filter { !it.name.startsWith(".") } ?: emptyList()

    data class Root(val dir: File, val name: String)

    /** 内部ストレージ・SDカードなど */
    fun roots(context: Context): List<Root> {
        val sm = context.getSystemService(StorageManager::class.java)
        if (Build.VERSION.SDK_INT >= 30) {
            return sm.storageVolumes
                .filter { it.state == Environment.MEDIA_MOUNTED }
                .mapNotNull { v -> v.directory?.let { Root(it, v.getDescription(context)) } }
        }
        return context.getExternalFilesDirs(null).filterNotNull().mapIndexed { i, f ->
            Root(File(f.path.substringBefore("/Android/data")), if (i == 0) "内部ストレージ" else "SDカード")
        }
    }

    data class Folder(val dir: File, val folderCount: Int, val videoCount: Int, val audioCount: Int)
    data class Listing(val folders: List<Folder>, val media: List<PlaylistItem>)

    fun list(context: Context, dir: File): Listing {
        val children = visibleChildren(dir)
        val folders = children.filter { it.isDirectory }
            .sortedWith(compareBy(NaturalOrder) { it.name })
            .map { d -> folderInfo(d) }
        return Listing(folders, mediaIn(context, dir, children))
    }

    /**
     * サブフォルダの中身の数。フォルダの中身が変わっていなければ（更新日時が同じなら）前回数えたものを使う
     * （フォルダを開くたびに、全部のサブフォルダの中身を調べ直さないように）
     */
    private fun folderInfo(d: File): Folder {
        val modified = d.lastModified()
        synchronized(folderCache) {
            folderCache[d.path]?.let { (m, f) -> if (m == modified) return f }
        }
        val sub = visibleChildren(d)
        val f = Folder(d, sub.count { it.isDirectory }, sub.count { isVideo(it) }, sub.count { isAudio(it) })
        synchronized(folderCache) { folderCache[d.path] = modified to f }
        return f
    }

    /** 最近数えたフォルダ（古いものから捨てる） */
    private val folderCache = object : LinkedHashMap<String, Pair<Long, Folder>>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, Folder>>?) = size > 2000
    }

    /** フォルダ直下の動画・音楽（名前順） */
    fun mediaIn(context: Context, dir: File, children: List<File> = visibleChildren(dir)): List<PlaylistItem> {
        val durations = durationsIn(context, dir, MediaStore.Video.Media.EXTERNAL_CONTENT_URI) +
            durationsIn(context, dir, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        return children.filter { isMedia(it) }
            .sortedWith(compareBy(NaturalOrder) { it.name })
            .map { PlaylistItem.fromFile(it, durations[it.path] ?: 0) }
    }

    /** サブフォルダがあるか（隠しフォルダは数えない） */
    fun hasSubfolders(dir: File): Boolean = dir.listFiles()?.any { it.isDirectory && !it.name.startsWith(".") } == true

    /**
     * フォルダの中と、その中のすべてのサブフォルダの中の動画・音楽。
     * 並びはファイルの一覧と同じ（フォルダ直下のファイルを名前順に、続けてサブフォルダを名前順に、その中も同じように）。
     * 隠しフォルダと「.nomedia」のあるフォルダ（アプリのキャッシュなど）は入らない。
     * 多すぎるときは [limit] 件まで（再生キューが重くなりすぎないように）
     */
    fun mediaInTree(context: Context, dir: File, limit: Int = 5000): List<PlaylistItem> {
        val durations = durationsInTree(context, dir, MediaStore.Video.Media.EXTERNAL_CONTENT_URI) +
            durationsInTree(context, dir, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)
        val result = ArrayList<PlaylistItem>()
        // 同じフォルダを二度たどらない（リンクで輪になっている場合など）
        val visited = HashSet<String>()
        fun walk(d: File, depth: Int) {
            if (result.size >= limit || depth > 32) return
            val key = runCatching { d.canonicalPath }.getOrDefault(d.path)
            if (!visited.add(key)) return
            val children = visibleChildren(d)
            if (depth > 0 && children.any { it.name == ".nomedia" }) return
            children.filter { isMedia(it) }
                .sortedWith(compareBy(NaturalOrder) { it.name })
                .forEach { if (result.size < limit) result += PlaylistItem.fromFile(it, durations[it.path] ?: 0) }
            children.filter { it.isDirectory }
                .sortedWith(compareBy(NaturalOrder) { it.name })
                .forEach { walk(it, depth + 1) }
        }
        walk(dir, 0)
        return result
    }

    /** サブフォルダの中まで含めて、MediaStore に登録されているファイルの長さを取ってくる */
    @Suppress("DEPRECATION")
    private fun durationsInTree(context: Context, dir: File, collection: Uri): Map<String, Long> = try {
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DURATION),
            "${MediaStore.MediaColumns.DATA} LIKE ? ESCAPE '\\'",
            arrayOf("${escapeLike(dir.path)}/%"),
            null,
        )?.use { c ->
            buildMap {
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    put(path, c.getLong(1))
                }
            }
        } ?: emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    /** MediaStore に登録されているファイルなら長さが分かるので取ってくる */
    @Suppress("DEPRECATION")
    private fun durationsIn(context: Context, dir: File, collection: Uri): Map<String, Long> = try {
        context.contentResolver.query(
            collection,
            arrayOf(MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DURATION),
            // このフォルダの直下だけ（サブフォルダの中は含めない）。% と _ はそのままの文字として扱う
            "${MediaStore.MediaColumns.DATA} LIKE ? ESCAPE '\\' AND ${MediaStore.MediaColumns.DATA} NOT LIKE ? ESCAPE '\\'",
            arrayOf("${escapeLike(dir.path)}/%", "${escapeLike(dir.path)}/%/%"),
            null,
        )?.use { c ->
            buildMap {
                while (c.moveToNext()) {
                    val path = c.getString(0) ?: continue
                    put(path, c.getLong(1))
                }
            }
        } ?: emptyMap()
    } catch (_: Exception) {
        emptyMap()
    }

    private fun escapeLike(s: String) = s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** 音楽ライブラリの1曲 */
    data class Song(
        val item: PlaylistItem,
        val title: String,
        val artist: String,
        val album: String,
        val albumId: Long,
        val track: Int,
    )

    /** 端末内のすべての曲（MediaStore から。着信音などは除く） */
    @Suppress("DEPRECATION")
    fun queryAllSongs(context: Context): List<Song> {
        val collection: Uri =
            if (Build.VERSION.SDK_INT >= 29) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DURATION,
        )
        val result = mutableListOf<Song>()
        try {
            context.contentResolver.query(
                collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, null,
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                while (c.moveToNext()) {
                    val path = c.getString(dataCol)
                    // タグが Shift_JIS のままの曲は、文字化けしているので読み直す
                    val title = TagText.fix(c.getString(titleCol))?.takeIf { it.isNotBlank() }
                        ?: path?.let { File(it).nameWithoutExtension } ?: "(名前なし)"
                    val artist = TagText.fix(c.getString(artistCol))?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "不明なアーティスト"
                    val album = TagText.fix(c.getString(albumCol))?.takeIf { it.isNotBlank() } ?: "不明なアルバム"
                    val uri = ContentUris.withAppendedId(collection, c.getLong(idCol))
                    result += Song(
                        item = PlaylistItem(uri, title, path, c.getLong(durCol)),
                        title = title,
                        artist = artist,
                        album = album,
                        albumId = c.getLong(albumIdCol),
                        // TRACK は「ディスク番号×1000＋曲番号」の形式
                        track = c.getInt(trackCol) % 1000,
                    )
                }
            }
        } catch (_: Exception) {
        }
        return result
    }

    /** 端末内のすべての動画（MediaStore から） */
    @Suppress("DEPRECATION")
    fun queryAllVideos(context: Context, includeDiscImages: Boolean = true): List<Video> {
        val collection: Uri =
            if (Build.VERSION.SDK_INT >= 29) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
            else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED,
        )
        val result = mutableListOf<Video>()
        try {
            context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val dataCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val folderCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
                val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                while (c.moveToNext()) {
                    result += Video(
                        uri = ContentUris.withAppendedId(collection, c.getLong(idCol)),
                        path = c.getString(dataCol),
                        title = c.getString(nameCol) ?: "(名前なし)",
                        durationMs = c.getLong(durCol),
                        size = c.getLong(sizeCol),
                        folder = c.getString(folderCol) ?: "",
                        dateAdded = c.getLong(dateCol),
                    )
                }
            }
        } catch (_: Exception) {
        }
        // ISO（DVD・Blu-ray のディスクイメージ）は動画として登録されないので、ファイルの一覧から探して加える
        if (includeDiscImages) result += discImagesNotIn(context, result)
        return result
    }

    /**
     * [videos] に入っていない ISO。端末の全ファイルから探すので時間がかかる（「動画」タブは、動画の一覧を先に出してから足す）
     */
    fun discImagesNotIn(context: Context, videos: List<Video>): List<Video> {
        val known = videos.mapNotNullTo(HashSet()) { it.path }
        return queryDiscImages(context).filter { it.path !in known }
    }

    /**
     * 端末内の ISO ファイル。MediaStore では「動画」ではないので、ファイル全体の一覧から拡張子で探す
     * （「すべてのファイルへのアクセス」が無いと見つからないことがある）
     */
    @Suppress("DEPRECATION")
    private fun queryDiscImages(context: Context): List<Video> {
        val collection = MediaStore.Files.getContentUri("external")
        val result = mutableListOf<Video>()
        try {
            context.contentResolver.query(
                collection,
                arrayOf(
                    MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA, MediaStore.MediaColumns.DISPLAY_NAME,
                    MediaStore.MediaColumns.SIZE, MediaStore.MediaColumns.DATE_ADDED,
                ),
                "${MediaStore.MediaColumns.DATA} LIKE ?",
                arrayOf("%.iso"),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val path = c.getString(1) ?: continue
                    val file = File(path)
                    result += Video(
                        uri = ContentUris.withAppendedId(collection, c.getLong(0)),
                        path = path,
                        title = c.getString(2) ?: file.name,
                        durationMs = 0,
                        size = c.getLong(3),
                        folder = file.parentFile?.name.orEmpty(),
                        dateAdded = c.getLong(4),
                    )
                }
            }
        } catch (_: Exception) {
        }
        return result
    }
}

/** 「2話」が「10話」より前に来るように、数字を数値として比べる並び順（数千件を並べ替えても重くならないよう、文字を順に見て比べる） */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isAsciiDigit() && cb.isAsciiDigit()) {
                // 数字の並びどうし：先頭の 0 を飛ばして、桁数→各桁の順に比べる
                var si = i
                while (si < a.length && a[si] == '0') si++
                var sj = j
                while (sj < b.length && b[sj] == '0') sj++
                var ei = si
                while (ei < a.length && a[ei].isAsciiDigit()) ei++
                var ej = sj
                while (ej < b.length && b[ej].isAsciiDigit()) ej++
                val lenDiff = (ei - si) - (ej - sj)
                if (lenDiff != 0) return lenDiff
                for (k in 0 until ei - si) {
                    val d = a[si + k] - b[sj + k]
                    if (d != 0) return d
                }
                i = ei
                j = ej
            } else {
                val d = ca.lowercaseChar() - cb.lowercaseChar()
                if (d != 0) return d
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }

    private fun Char.isAsciiDigit() = this in '0'..'9'
}
