package com.ryose.videoplayer

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import kotlinx.coroutines.launch

/** プレイリストの中身（再生・並べ替え・削除）。状態と操作は PlaylistViewModel にあり、ここは表示するだけ */
class PlaylistActivity : AppCompatActivity() {

    private lateinit var vm: PlaylistViewModel
    private lateinit var adapter: MediaAdapter
    private lateinit var listView: RecyclerView
    private lateinit var emptyText: TextView
    /** 行をドラッグして並べ替えている途中（途中で前の保存の一覧が届いても、ドラッグ中の行を跳ばさない） */
    private var dragging = false

    private val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0) {
        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            adapter.move(vh.bindingAdapterPosition, target.bindingAdapterPosition)
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

        override fun isLongPressDragEnabled() = false

        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(vh, actionState)
            if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) dragging = true
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            dragging = false
            // 並べ替えが終わったら保存（保存した一覧が届くので、表示はそれで合う）
            vm.setItems(currentItems())
        }
    })

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdgeWithoutScrim()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_playlist)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        // 読み込みが済むまで、アプリ名を出さない
        supportActionBar?.title = ""

        vm = ViewModelProvider(this)[PlaylistViewModel::class.java]
        if (vm.playlistId == null) {
            finish()
            return
        }
        listView = findViewById(R.id.list)
        emptyText = findViewById(R.id.emptyText)

        adapter = MediaAdapter(ResumeStore(this), ::onRowClick, ::onRowLongClick)
        adapter.dragListener = { touchHelper.startDrag(it) }
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = adapter
        touchHelper.attachToRecyclerView(listView)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            // 横向きのときのカメラの切り欠き（ディスプレイカットアウト）にも重ならないように
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right)
            listView.updatePadding(bottom = bars.bottom)
            insets
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect(::render)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // 再生画面から戻ったとき、視聴位置のバーを更新
        if (::adapter.isInitialized) adapter.refreshProgress()
    }

    private fun render(s: PlaylistViewModel.UiState) {
        if (!s.loaded) return
        if (s.missing) {
            finish()
            return
        }
        supportActionBar?.title = s.name
        supportActionBar?.subtitle = s.subtitle
        if (!dragging) adapter.rows = s.rows
        emptyText.isVisible = s.rows.isEmpty()
        if (s.isFavorites) {
            emptyText.text = "お気に入りはまだありません。\n\n動画や曲を長押しして\n「お気に入りに追加」を選ぶと、ここに入ります。"
        }
        invalidateOptionsMenu()
    }

    private fun currentItems() = adapter.rows.filterIsInstance<Row.Media>().map { it.item }

    private fun onRowClick(position: Int) {
        val items = currentItems()
        if (position in items.indices) playItems(items, position)
    }

    private fun onRowLongClick(position: Int) {
        val item = currentItems().getOrNull(position) ?: return
        val isFavorites = vm.state.value.isFavorites
        ActionSheet.show(
            this, item.title,
            listOf(
                SheetItem(R.drawable.ic_play, "ここから再生") { onRowClick(position) },
                SheetItem(
                    if (isFavorites) R.drawable.ic_star_border else R.drawable.ic_delete,
                    if (isFavorites) "お気に入りから外す" else "プレイリストから外す",
                ) {
                    val before = vm.removeAt(currentItems(), position)
                    showUndo(if (isFavorites) "お気に入りから外しました" else "プレイリストから外しました") { vm.setItems(before) }
                },
                SheetItem(R.drawable.ic_playlist_add, "ほかのプレイリストに追加") { PlaylistDialogs.addToPlaylist(this, listOf(item)) },
            ),
        )
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        // お気に入りは名前の変更・削除はできない
        val fixed = vm.playlistId == FavoriteMedia.PLAYLIST_ID
        menu.findItem(R.id.action_rename)?.isVisible = !fixed
        menu.findItem(R.id.action_delete)?.isVisible = !fixed
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.playlist_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val items = currentItems()
        when (item.itemId) {
            R.id.action_play_all -> playItems(items, 0)
            R.id.action_shuffle_play -> if (items.isNotEmpty()) playItems(items, items.indices.random(), shuffle = true)
            R.id.action_rename -> PlaylistDialogs.promptName(this, "名前を変更", vm.state.value.name) { vm.rename(it) }
            R.id.action_delete -> PlaylistDialogs.confirm(this, "このプレイリストを削除しますか？\n（動画ファイルは削除されません）", "削除") {
                vm.delete()
                finish()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    companion object {
        const val EXTRA_ID = "playlist_id"
    }
}
