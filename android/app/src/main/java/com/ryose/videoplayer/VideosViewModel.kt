package com.ryose.videoplayer

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 端末内の動画（データの層）。MediaStore から読み、以前の版で保存した「続きから再生」の位置を引き継ぐ */
class VideoRepository(private val context: Context) {
    /** 動画（ISO は入れない。すぐ出せるように） */
    suspend fun videos(): List<Video> = withContext(Dispatchers.IO) {
        val resume = ResumeStore(context)
        MediaFiles.queryAllVideos(context, includeDiscImages = false).also { videos ->
            videos.forEach { v -> v.path?.let { resume.migrate(v.uri.toString(), it) } }
        }
    }

    /** [videos] に入っていない ISO（端末の全ファイルから探すので時間がかかる） */
    suspend fun discImages(videos: List<Video>): List<Video> = withContext(Dispatchers.IO) {
        MediaFiles.discImagesNotIn(context, videos)
    }
}

/**
 * 「動画」タブの状態と操作（VideosFragment から分けた）。
 * 画面は [state] を表示するだけで、操作（検索・並べ替え・フォルダを開くなど）はここに頼む（単方向のデータの流れ）。
 * 画面が回転して作り直されても、読み込んだ一覧・検索の文字・開いているフォルダは残る
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideosViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {

    enum class Sort { DATE, NAME, DURATION }

    data class UiState(
        /** 一度でも読み込んだか（読み込み中のくるくるを出すか） */
        val loaded: Boolean = false,
        /** 動画の一覧を読む許可があるか */
        val hasAccess: Boolean = true,
        /** Android 14 以降で「選択した写真と動画」だけを許可されている */
        val limited: Boolean = false,
        val rows: List<Row> = emptyList(),
        val emptyMessage: String = "",
        val subtitle: String? = null,
        /** フォルダ別表示で開いているフォルダ（null ならフォルダの一覧） */
        val openedFolder: String? = null,
        val byFolder: Boolean = false,
        val sort: Sort = Sort.DATE,
        val query: String = "",
    )

    private val context: Context get() = getApplication()
    private val repository = VideoRepository(app)

    private val videos = MutableStateFlow<List<Video>?>(null)
    private val query = MutableStateFlow("")
    private val sort = MutableStateFlow(runCatching { Sort.valueOf(AppSettings.videosSort(app)) }.getOrDefault(Sort.DATE))
    private val byFolder = MutableStateFlow(AppSettings.videosByFolder(app))
    // 開いているフォルダは、アプリが OS に終了させられて戻ってきたときも戻す
    private val openedFolder = saved.getStateFlow<String?>(KEY_FOLDER, null)
    private val access = MutableStateFlow(Access())

    private data class Access(val hasAccess: Boolean = true, val limited: Boolean = false)
    private data class Input(
        val videos: List<Video>?, val query: String, val sort: Sort, val folder: String?, val byFolder: Boolean, val access: Access,
    )

    private val _state = MutableStateFlow(UiState(sort = sort.value, byFolder = byFolder.value))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** 一覧に出していた検索の文字（入力中だけ少し待ってから絞り込むため） */
    private var lastQuery = ""
    /** 読み込み中のもの（新しく読み込み始めたら、古いほうの結果は使わない） */
    private var loadJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch {
            combine(videos, query, sort, openedFolder, byFolder) { v, q, s, f, b -> Input(v, q, s, f, b, Access()) }
                .combine(access) { input, a -> input.copy(access = a) }
                .mapLatest { input ->
                    // 検索の入力中は、入力が止まるまで少し待つ（数千本あっても入力がもたつかないように）
                    if (input.query != lastQuery) delay(200)
                    lastQuery = input.query
                    buildState(input)
                }
                .collect { _state.value = it }
        }
    }

    /** 許可が変わった・読み込んでいないなどで、読み込み直したほうがよいとき（画面に戻ってきたとき） */
    fun refreshIfNeeded(force: Boolean = false) {
        val has = context.hasMediaAccess(audio = false)
        if (force || videos.value == null || has != access.value.hasAccess) load()
        // 設定が変わっていたら合わせる
        byFolder.value = AppSettings.videosByFolder(context)
    }

    fun load() {
        val has = context.hasMediaAccess(audio = false)
        access.value = Access(has, has && isLimitedAccess(context))
        if (!has) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            // 動画を先に出し、ISO はあとから足す（ISO を探すのは端末の全ファイルを調べるので、一覧が出るのが遅れていた）
            val started = android.os.SystemClock.elapsedRealtime()
            val list = repository.videos()
            videos.value = list
            val listed = android.os.SystemClock.elapsedRealtime()
            val discs = repository.discImages(list)
            if (discs.isNotEmpty()) videos.value = list + discs
            PlaybackLog.add(
                "「動画」タブを読み込む：動画 ${list.size} 本 ${listed - started} ms、ISO ${discs.size} 本 ${android.os.SystemClock.elapsedRealtime() - listed} ms",
            )
        }
    }

    fun setQuery(q: String) {
        query.value = q
    }

    fun setSort(s: Sort) {
        AppSettings.setVideosSort(context, s.name)
        sort.value = s
    }

    fun setByFolder(value: Boolean) {
        if (byFolder.value == value) return
        AppSettings.setVideosByFolder(context, value)
        saved[KEY_FOLDER] = null
        byFolder.value = value
        // 切り替えのボタンはすぐに合わせる（一覧を作り直すのは少しあとになる）
        _state.value = _state.value.copy(byFolder = value)
    }

    fun openFolder(dir: String?) {
        saved[KEY_FOLDER] = dir
    }

    /** フォルダの中の動画（名前順）。フォルダの行の︙で使う */
    fun videosIn(dir: String): List<PlaylistItem> =
        videos.value.orEmpty().filter { folderOf(it) == dir }.sortedWith(compareBy(NaturalOrder) { it.title }).map { it.toItem() }

    private fun folderOf(v: Video) = v.path?.let { File(it).parent } ?: v.folder

    private suspend fun buildState(input: Input): UiState = withContext(Dispatchers.Default) {
        val base = UiState(
            loaded = input.videos != null,
            hasAccess = input.access.hasAccess,
            limited = input.access.limited,
            openedFolder = input.folder,
            byFolder = input.byFolder,
            sort = input.sort,
            query = input.query,
        )
        val all = input.videos ?: return@withContext base
        val rows = buildRows(all, input)
        base.copy(
            rows = rows,
            // 動画が 1 本も無いのか、検索に当てはまるものが無いのかを分けて伝える
            emptyMessage = if (input.query.isBlank() || all.isEmpty()) context.getString(R.string.no_videos)
            else "「${input.query}」に一致する動画はありません。",
            subtitle = when {
                input.folder != null -> "${File(input.folder).name}（${rows.size} 本）"
                all.isEmpty() -> null
                input.byFolder -> "${rows.size} フォルダ"
                else -> "${rows.size} 本の動画"
            },
        )
    }

    private fun buildRows(all: List<Video>, input: Input): List<Row> {
        val q = input.query
        val matched = all.filter { q.isBlank() || it.title.contains(q, ignoreCase = true) || it.folder.contains(q, ignoreCase = true) }
        fun sorted(list: List<Video>) = when (input.sort) {
            Sort.DATE -> list.sortedByDescending { it.dateAdded }
            Sort.NAME -> list.sortedWith(compareBy(NaturalOrder) { it.title })
            Sort.DURATION -> list.sortedByDescending { it.durationMs }
        }
        fun mediaRow(v: Video) = Row.Media(v.toItem(), "${v.folder} · ${Formatter.formatShortFileSize(context, v.size)}")

        return when {
            input.folder != null -> sorted(matched.filter { folderOf(it) == input.folder }).map(::mediaRow)
            input.byFolder -> matched.groupBy { folderOf(it) }.entries
                .sortedWith(compareBy(NaturalOrder) { File(it.key).name })
                .map { (dir, videos) ->
                    val total = videos.sumOf { it.durationMs }
                    val info = "${videos.size} 本" + if (total > 0) " · ${formatTime(total)}" else ""
                    Row.Folder(File(dir).name.ifEmpty { dir }, info, R.drawable.ic_folder, id = "vfolder:$dir")
                }
            else -> sorted(matched).map(::mediaRow)
        }
    }

    /**
     * Android 14 以降で「選択した写真と動画」だけを許可されているか
     * （「すべて許可」や「すべてのファイルへのアクセス」があれば false）
     */
    private fun isLimitedAccess(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT < 34 || ctx.hasStorageAccess()) return false
        fun granted(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        return granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) && !granted(Manifest.permission.READ_MEDIA_VIDEO)
    }

    private companion object {
        const val KEY_FOLDER = "folder"
    }
}
