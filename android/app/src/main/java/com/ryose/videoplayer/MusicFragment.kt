package com.ryose.videoplayer

import android.os.Bundle
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.launch

/** 「音楽」タブ：曲・アルバム・アーティスト。状態と操作は MusicViewModel にあり、ここは表示するだけ */
class MusicFragment : LibraryTabFragment() {

    private lateinit var vm: MusicViewModel
    private val chipsByMode = mutableMapOf<MusicViewModel.Mode, Chip>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = ViewModelProvider(this)[MusicViewModel::class.java]
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val chips = view.findViewById<ChipGroup>(R.id.chips)
        chips.visibility = View.VISIBLE
        MusicViewModel.Mode.entries.forEach { m ->
            chips.addView(choiceChip(m.label) {
                vm.setMode(m)
                updateChips(vm.state.value)
            }.also { chipsByMode[m] = it })
        }
        updateChips(vm.state.value)
        requireActivity().addMenuProvider(menuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED)
        // 曲が増えた・消えたら自動で読み込み直す
        watchMedia(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI) { if (vm.state.value.loaded) vm.load() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect(::render)
            }
        }
    }

    private fun render(s: MusicViewModel.UiState) {
        updateChips(s)
        when {
            !s.hasAccess -> {
                showNeedPermission()
                onListHidden(s.opened?.id)
            }
            !s.loaded -> {
                loading.visibility = View.VISIBLE
                onListHidden(s.opened?.id)
            }
            else -> {
                showRows(s.rows, s.emptyMessage, R.drawable.ic_music_note)
                // 開いた・閉じた直後なら、その一覧が出たところで位置を合わせる
                onListShown(s.opened?.id)
            }
        }
    }

    private fun updateChips(s: MusicViewModel.UiState) {
        chipsByMode.forEach { (m, chip) -> chip.isChecked = m == s.mode }
    }

    private val menuProvider = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            menuInflater.inflate(R.menu.music_menu, menu)
            setUpSearch(menu, "曲名・アーティスト・アルバムで検索", vm.state.value.query, vm::setQuery)
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
            if (menuItem.itemId != R.id.action_refresh) return false
            vm.load()
            return true
        }
    }

    override fun onResume() {
        super.onResume()
        vm.refreshIfNeeded()
    }

    override fun subtitle(): String? = vm.state.value.subtitle

    override fun onFilesChanged() = vm.load()

    override fun openedId(): String? = vm.state.value.opened?.id

    override fun requestOpen(row: Row.Folder?) = vm.open(row)

    override fun onRowClick(position: Int) {
        when (val row = adapter.rows.getOrNull(position)) {
            is Row.Folder -> enter(row)
            is Row.Media -> playMediaAt(position)
            else -> {}
        }
    }

    // アルバム・アーティストの行には必ず曲がある（毎回すべての曲から探すと重いので、ここでは調べない）
    override fun hasExtraActions(row: Row) = row is Row.Folder

    override fun extraActions(row: Row): List<SheetItem> {
        if (row !is Row.Folder) return emptyList()
        val items = vm.songsOf(row.id).map { it.item }
        if (items.isEmpty()) return emptyList()
        return listOf(
            SheetItem(R.drawable.ic_play, "再生") { requireActivity().playItems(items, 0) },
            SheetItem(R.drawable.ic_shuffle, "シャッフル再生") { requireActivity().playItems(items, items.indices.random(), shuffle = true) },
            SheetItem(R.drawable.ic_playlist_add, "プレイリストに追加") { PlaylistDialogs.addToPlaylist(requireContext(), items) },
        )
    }
}
