package com.ryose.videoplayer

import android.app.Application
import android.text.format.DateUtils
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File

/** 一覧だけの画面（履歴・プレイリスト）の状態。rows が null の間は読み込み中 */
data class ListUiState(val rows: List<Row>? = null)

/**
 * 「履歴」タブの状態と操作（HistoryFragment から分けた）。
 * 履歴は再生サービスも書き換えるので、HistoryStore.changes を見張って、どこで変わっても一覧を作り直す
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val store = HistoryStore(app)
    /** 「5 分前」などの表示を作り直すため（画面に戻ってきたとき） */
    private val tick = MutableStateFlow(0)

    val state: StateFlow<ListUiState> = combine(HistoryStore.changes, tick) { _, _ -> }
        .mapLatest { ListUiState(buildRows()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListUiState())

    private suspend fun buildRows(): List<Row> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        // 以前のバージョンで記録された音楽は出さない
        store.all().filterNot { it.item.isAudio }.map { e ->
            val ago = DateUtils.getRelativeTimeSpanString(e.playedAt, now, DateUtils.MINUTE_IN_MILLIS)
            val folder = e.item.path?.let { File(it).parentFile?.name }
            Row.Media(e.item, listOfNotNull(ago.toString(), folder).joinToString(" · "))
        }
    }

    fun refresh() = tick.update { it + 1 }

    /** すべて消す。「元に戻す」で使う、消す前の記録を返す */
    fun clearAll(): List<HistoryStore.Entry> = store.all().also { store.clear() }

    /** 1 件消す。「元に戻す」で使う、消した記録を返す */
    fun remove(key: String): List<HistoryStore.Entry> =
        store.all().filter { it.item.key == key }.also { store.remove(key) }

    fun restore(entries: List<HistoryStore.Entry>) = store.restore(entries)
}

/**
 * 「プレイリスト」タブの状態と操作（PlaylistsFragment から分けた）。
 * プレイリストはいろいろな画面（長押しの「プレイリストに追加」・お気に入り・プレイリストの画面）が書き換えるので、
 * PlaylistStore.changes を見張って、どこで変わっても一覧を作り直す
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistsViewModel(app: Application) : AndroidViewModel(app) {
    private val store = PlaylistStore(app)

    val state: StateFlow<ListUiState> = PlaylistStore.changes
        .mapLatest { ListUiState(buildRows()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ListUiState())

    private suspend fun buildRows(): List<Row> = withContext(Dispatchers.Default) {
        // お気に入りはいつも一番上に
        store.all().sortedBy { if (it.id == FavoriteMedia.PLAYLIST_ID) 0 else 1 }.map { p ->
            val total = p.items.sumOf { it.durationMs }
            // 全部が曲なら「曲」、全部が動画なら「本」、混ざっていれば「件」で数える
            val unit = when {
                p.items.isNotEmpty() && p.items.all { it.isAudio } -> "曲"
                p.items.none { it.isAudio } -> "本"
                else -> "件"
            }
            val info = "${p.items.size} $unit" + if (total > 0) " · ${formatTime(total)}" else ""
            val icon = if (p.id == FavoriteMedia.PLAYLIST_ID) R.drawable.ic_star else R.drawable.ic_playlist
            Row.Folder(p.name, info, icon, id = p.id)
        }
    }

    fun get(id: String): SavedPlaylist? = store.get(id)

    fun create(name: String) {
        store.create(name)
    }

    fun rename(id: String, name: String) = store.rename(id, name)

    /** 消す。「元に戻す」で使う、消したプレイリストと場所を返す */
    fun delete(id: String): Pair<SavedPlaylist, Int>? {
        val list = store.all()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return null
        val p = list[index]
        store.delete(id)
        return p to index
    }

    fun restore(p: SavedPlaylist, index: Int) = store.restore(p, index)
}

/**
 * プレイリストの画面（PlaylistActivity）の状態と操作。開いているプレイリストの id は画面を開いたときの Intent から
 * （SavedStateHandle に入ってくる）。PlaylistStore.changes を見張るので、ほかで変わっても表示が合う
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaylistViewModel(app: Application, saved: androidx.lifecycle.SavedStateHandle) : AndroidViewModel(app) {

    data class UiState(
        /** まだ読んでいない */
        val loaded: Boolean = false,
        /** プレイリストが無くなった（削除された）。画面を閉じる */
        val missing: Boolean = false,
        val name: String = "",
        val subtitle: String = "",
        val rows: List<Row> = emptyList(),
        val isFavorites: Boolean = false,
    )

    val playlistId: String? = saved.get<String>(PlaylistActivity.EXTRA_ID)
    private val store = PlaylistStore(app)

    val state: StateFlow<UiState> = PlaylistStore.changes
        .mapLatest { build() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    private suspend fun build(): UiState = withContext(Dispatchers.Default) {
        val p = playlistId?.let { store.get(it) } ?: return@withContext UiState(loaded = true, missing = true)
        // 全部が曲なら「曲」、全部が動画なら「本」、混ざっていれば「件」で数える
        val unit = when {
            p.items.isNotEmpty() && p.items.all { it.isAudio } -> "曲"
            p.items.none { it.isAudio } -> "本"
            else -> "件"
        }
        UiState(
            loaded = true,
            name = p.name,
            subtitle = "${p.items.size} $unit",
            rows = p.items.map { Row.Media(it, it.path?.let { path -> File(path).parentFile?.name }.orEmpty()) },
            isFavorites = p.id == FavoriteMedia.PLAYLIST_ID,
        )
    }

    /** 並べ替えた順番で保存する */
    fun setItems(items: List<PlaylistItem>) {
        playlistId?.let { store.setItems(it, items) }
    }

    /** 1 件外す。「元に戻す」で使う、外す前の一覧を返す */
    fun removeAt(items: List<PlaylistItem>, position: Int): List<PlaylistItem> {
        setItems(items.filterIndexed { i, _ -> i != position })
        return items
    }

    fun rename(name: String) {
        playlistId?.let { store.rename(it, name) }
    }

    fun delete() {
        playlistId?.let { store.delete(it) }
    }
}
