package com.ryose.videoplayer

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/** 「プレイリスト」タブ。状態と操作は PlaylistsViewModel にあり、ここは表示するだけ */
class PlaylistsFragment : BaseListFragment() {

    private lateinit var vm: PlaylistsViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = ViewModelProvider(this)[PlaylistsViewModel::class.java]
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fab.visibility = View.VISIBLE
        fab.setOnClickListener {
            PlaylistDialogs.promptName(requireContext(), getString(R.string.new_playlist), "") { name -> vm.create(name) }
        }
        // 右下のボタンで最後の行が隠れないように
        list.updatePadding(bottom = (88 * resources.displayMetrics.density).toInt())

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect(::render)
            }
        }
    }

    private fun render(s: ListUiState) {
        val rows = s.rows ?: return
        showRows(
            rows,
            "プレイリストはまだありません。\n\n右下の＋で作るか、動画を長押しして\n「プレイリストに追加」から作れます。",
            R.drawable.ic_playlist,
        )
    }

    override fun onRowClick(position: Int) {
        val id = (adapter.rows.getOrNull(position) as? Row.Folder)?.id ?: return
        startActivity(Intent(requireContext(), PlaylistActivity::class.java).putExtra(PlaylistActivity.EXTRA_ID, id))
    }

    override fun extraActions(row: Row): List<SheetItem> {
        val id = (row as? Row.Folder)?.id ?: return emptyList()
        val p = vm.get(id) ?: return emptyList()
        val play = listOf(
            SheetItem(R.drawable.ic_play, "再生") { if (p.items.isNotEmpty()) requireActivity().playItems(p.items, 0) },
            SheetItem(R.drawable.ic_shuffle, "シャッフル再生") {
                if (p.items.isNotEmpty()) requireActivity().playItems(p.items, p.items.indices.random(), shuffle = true)
            },
        )
        // お気に入りは名前の変更・削除はできない
        if (id == FavoriteMedia.PLAYLIST_ID) return play
        return play + listOf(
            SheetItem(R.drawable.ic_edit, "名前を変更") {
                PlaylistDialogs.promptName(requireContext(), "名前を変更", p.name) { name -> vm.rename(id, name) }
            },
            SheetItem(R.drawable.ic_delete, "削除") {
                PlaylistDialogs.confirm(requireContext(), "「${p.name}」を削除しますか？\n（動画ファイルは削除されません）", "削除") {
                    val (deleted, index) = vm.delete(id) ?: return@confirm
                    requireActivity().showUndo("「${p.name}」を削除しました") { vm.restore(deleted, index) }
                }
            },
        )
    }
}
