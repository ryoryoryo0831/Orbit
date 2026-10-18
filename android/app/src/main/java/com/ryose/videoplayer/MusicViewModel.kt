package com.ryose.videoplayer

import android.app.Application
import android.content.Context
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

/** 端末内の曲（データの層）。MediaStore から読む */
class SongRepository(private val context: Context) {
    suspend fun all(): List<MediaFiles.Song> = withContext(Dispatchers.IO) { MediaFiles.queryAllSongs(context) }
}

/**
 * 「音楽」タブの状態と操作（MusicFragment から分けた）。
 * 画面は [state] を表示するだけで、操作（表示の切り替え・検索・アルバムを開くなど）はここに頼む。
 * 画面が回転して作り直されても、読み込んだ曲・検索の文字・開いているアルバムは残る
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MusicViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {

    enum class Mode(val label: String) { SONGS("曲"), ALBUMS("アルバム"), ARTISTS("アーティスト") }

    /** 開いているアルバム・アーティスト（行の id と名前） */
    data class Opened(val id: String, val title: String)

    data class UiState(
        val loaded: Boolean = false,
        val hasAccess: Boolean = true,
        val rows: List<Row> = emptyList(),
        val emptyMessage: String = "",
        val subtitle: String? = null,
        val mode: Mode = Mode.SONGS,
        val opened: Opened? = null,
        val query: String = "",
    )

    private val context: Context get() = getApplication()
    private val repository = SongRepository(app)

    private val songs = MutableStateFlow<List<MediaFiles.Song>?>(null)
    private val query = MutableStateFlow("")
    private val mode = saved.getStateFlow(KEY_MODE, AppSettings.musicMode(app))
    private val openedId = saved.getStateFlow<String?>(KEY_OPENED_ID, null)
    private val openedTitle = saved.getStateFlow<String?>(KEY_OPENED_TITLE, null)
    private val hasAccess = MutableStateFlow(true)

    private data class Input(
        val songs: List<MediaFiles.Song>?, val query: String, val mode: Mode, val opened: Opened?, val hasAccess: Boolean,
    )

    private val _state = MutableStateFlow(UiState(mode = modeOf(mode.value)))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var lastQuery = ""

    init {
        viewModelScope.launch {
            combine(songs, query, mode, openedId, openedTitle) { s, q, m, id, title ->
                Input(s, q, modeOf(m), if (id != null) Opened(id, title.orEmpty()) else null, true)
            }
                .combine(hasAccess) { input, a -> input.copy(hasAccess = a) }
                .mapLatest { input ->
                    // 検索の入力中は、入力が止まるまで少し待つ（曲が多くても入力がもたつかないように）
                    if (input.query != lastQuery) delay(200)
                    lastQuery = input.query
                    buildState(input)
                }
                .collect { _state.value = it }
        }
    }

    private fun modeOf(name: String) = runCatching { Mode.valueOf(name) }.getOrDefault(Mode.SONGS)

    /** 許可が変わった・読み込んでいないときに読み込む（画面に戻ってきたとき） */
    fun refreshIfNeeded() {
        if (songs.value == null || context.hasMediaAccess(audio = true) != hasAccess.value) load()
    }

    fun load() {
        val has = context.hasMediaAccess(audio = true)
        hasAccess.value = has
        if (!has) return
        viewModelScope.launch { songs.value = repository.all() }
    }

    fun setQuery(q: String) {
        query.value = q
    }

    fun setMode(m: Mode) {
        if (modeOf(mode.value) == m) return
        AppSettings.setMusicMode(context, m.name)
        open(null)
        saved[KEY_MODE] = m.name
    }

    /** アルバム・アーティストを開く（null で閉じる） */
    fun open(row: Row.Folder?) {
        // id があるときだけ開いている扱いなので、開くときは名前を先に、閉じるときは id を先に変える
        if (row != null) {
            saved[KEY_OPENED_TITLE] = row.name
            saved[KEY_OPENED_ID] = row.id
        } else {
            saved[KEY_OPENED_ID] = null
            saved[KEY_OPENED_TITLE] = null
        }
    }

    /** アルバム・アーティストの行に含まれる曲（アルバムは曲順、アーティストはアルバムごとに曲順） */
    fun songsOf(id: String?): List<MediaFiles.Song> = songsOf(id, songs.value.orEmpty())

    private fun songsOf(id: String?, songs: List<MediaFiles.Song>): List<MediaFiles.Song> {
        id ?: return emptyList()
        return when {
            id.startsWith("album:") -> {
                val albumId = id.removePrefix("album:").toLongOrNull()
                songs.filter { it.albumId == albumId }
                    .sortedWith(compareBy<MediaFiles.Song> { it.track }.thenBy(NaturalOrder) { it.title })
            }
            id.startsWith("artist:") -> {
                val artist = id.removePrefix("artist:")
                songs.filter { it.artist == artist }
                    .sortedWith(compareBy(NaturalOrder) { s: MediaFiles.Song -> s.album }.thenBy { it.track }.thenBy(NaturalOrder) { it.title })
            }
            else -> emptyList()
        }
    }

    private fun songMeta(s: MediaFiles.Song) = "${s.artist} · ${s.album}"

    private fun matches(s: MediaFiles.Song, q: String) =
        q.isBlank() || s.title.contains(q, true) || s.artist.contains(q, true) || s.album.contains(q, true)

    private suspend fun buildState(input: Input): UiState = withContext(Dispatchers.Default) {
        val base = UiState(
            loaded = input.songs != null,
            hasAccess = input.hasAccess,
            mode = input.mode,
            opened = input.opened,
            query = input.query,
        )
        val all = input.songs ?: return@withContext base
        val q = input.query
        val rows = when {
            input.opened != null -> songsOf(input.opened.id, all).filter { matches(it, q) }.map { Row.Media(it.item, songMeta(it)) }
            else -> buildTopRows(all.filter { matches(it, q) }, input.mode)
        }
        base.copy(
            rows = rows,
            emptyMessage = if (q.isBlank()) "端末内に音楽が見つかりませんでした。" else "「$q」に一致する曲はありません。",
            subtitle = input.opened?.title ?: if (all.isEmpty()) null else "${all.size} 曲",
        )
    }

    private fun buildTopRows(songs: List<MediaFiles.Song>, mode: Mode): List<Row> = when (mode) {
        Mode.SONGS -> songs.sortedWith(compareBy(NaturalOrder) { it.title }).map { Row.Media(it.item, songMeta(it)) }
        Mode.ALBUMS -> songs.groupBy { it.albumId }.values
            .sortedWith(compareBy(NaturalOrder) { it.first().album })
            .map { list ->
                val artists = list.map { it.artist }.distinct()
                val artist = if (artists.size == 1) artists[0] else "さまざまなアーティスト"
                Row.Folder(
                    list.first().album, "$artist · ${list.size} 曲", R.drawable.ic_album,
                    id = "album:${list.first().albumId}", art = list.firstNotNullOfOrNull { it.item.path },
                )
            }
        Mode.ARTISTS -> songs.groupBy { it.artist }.entries
            .sortedWith(compareBy(NaturalOrder) { it.key })
            .map { (artist, list) ->
                val albums = list.map { it.albumId }.distinct().size
                Row.Folder(
                    artist, "$albums 枚のアルバム · ${list.size} 曲", R.drawable.ic_person,
                    id = "artist:$artist", art = list.firstNotNullOfOrNull { it.item.path },
                )
            }
    }

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_OPENED_ID = "opened_id"
        const val KEY_OPENED_TITLE = "opened_title"
    }
}
