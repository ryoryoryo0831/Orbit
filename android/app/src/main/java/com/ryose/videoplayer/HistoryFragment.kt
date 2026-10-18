package com.ryose.videoplayer

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch

/** 「履歴」タブ：最近再生した動画。状態と操作は HistoryViewModel にあり、ここは表示するだけ */
class HistoryFragment : BaseListFragment() {

    private lateinit var vm: HistoryViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = ViewModelProvider(this)[HistoryViewModel::class.java]
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(object : MenuProvider {
            override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
                menuInflater.inflate(R.menu.history_menu, menu)
            }

            override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
                if (menuItem.itemId != R.id.action_clear_history) return false
                PlaylistDialogs.confirm(requireContext(), "再生履歴をすべて削除しますか？", "削除") {
                    val before = vm.clearAll()
                    requireActivity().showUndo("再生履歴を削除しました") { vm.restore(before) }
                }
                return true
            }
        }, viewLifecycleOwner, Lifecycle.State.RESUMED)

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect(::render)
            }
        }
    }

    private fun render(s: ListUiState) {
        val rows = s.rows ?: return
        showRows(rows, "再生した動画がここに表示されます。", R.drawable.ic_history)
    }

    override fun onResume() {
        super.onResume()
        // 「5 分前」などを今の時刻で作り直す
        vm.refresh()
    }

    // 履歴の一覧は HistoryStore が変わると作り直されるので、名前の変更・削除のあとに何もしなくてよい
    override fun onFilesChanged() {}

    /** 履歴はその動画だけを再生する */
    override fun onRowClick(position: Int) {
        val row = adapter.rows.getOrNull(position) as? Row.Media ?: return
        requireActivity().playItems(listOf(row.item), 0)
    }

    override fun extraActions(row: Row): List<SheetItem> {
        if (row !is Row.Media) return emptyList()
        return listOf(SheetItem(R.drawable.ic_history, "履歴から削除") {
            val removed = vm.remove(row.item.key)
            requireActivity().showUndo("履歴から削除しました") { vm.restore(removed) }
        })
    }
}
