package com.ryose.videoplayer

import android.os.Bundle
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.launch

/** 「音楽」タブ：曲・アルバム・アーティスト。状態と操作は MusicViewModel にあり、ここは表示するだけ */
class MusicFragment : BaseListFragment() {

    private lateinit var vm: MusicViewModel
    private val chipsByMode = mutableMapOf<MusicViewModel.Mode, Chip>()
    /** アルバムなどに入る前の一覧のスクロール位置（戻ったときに戻す） */
    private var savedScroll: android.os.Parcelable? = null
    /** 開いた・閉じたあと、その一覧を表示したときにすること（一番上を見せる・前の位置に戻す） */
    private var afterShown: Pair<String?, () -> Unit>? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeGroup()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = ViewModelProvider(this)[MusicViewModel::class.java]
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val chips = view.findViewById<ChipGroup>(R.id.chips)
        chips.visibility = View.VISIBLE
        MusicViewModel.Mode.entries.forEach { m ->
            chips.addView(Chip(requireContext()).apply {
                // テレビのリモコンで選んでいるときは青い枠
                foreground = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.fg_focus_ring)
                id = View.generateViewId()
                text = m.label
                isCheckable = true
                setOnClickListener {
                    vm.setMode(m)
                    updateChips(vm.state.value)
                }
            }.also { chipsByMode[m] = it })
        }
        updateChips(vm.state.value)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        requireActivity().addMenuProvider(menuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED)
        // 曲が増えた・消えたら自動で読み込み直す
        viewLifecycleOwner.lifecycle.addObserver(
            MediaWatcher(requireContext().applicationContext, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI) {
                if (vm.state.value.loaded) vm.load()
            }
        )

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect(::render)
            }
        }
    }

    private fun render(s: MusicViewModel.UiState) {
        updateChips(s)
        backCallback.isEnabled = isResumed && s.opened != null
        when {
            !s.hasAccess -> showNeedPermission()
            !s.loaded -> loading.visibility = View.VISIBLE
            else -> {
                showRows(s.rows, s.emptyMessage, R.drawable.ic_music_note)
                // 開いた・閉じた直後なら、その一覧が出たところで位置を合わせる
                afterShown?.let { (id, action) ->
                    if (id == s.opened?.id) {
                        afterShown = null
                        action()
                    }
                }
            }
        }
    }

    private fun updateChips(s: MusicViewModel.UiState) {
        chipsByMode.forEach { (m, chip) -> chip.isChecked = m == s.mode }
    }

    private val menuProvider = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            menuInflater.inflate(R.menu.music_menu, menu)
            val searchItem = menu.findItem(R.id.action_search)
            val searchView = searchItem.actionView as SearchView
            searchView.queryHint = "曲名・アーティスト・アルバムで検索"
            val query = vm.state.value.query
            if (query.isNotEmpty()) {
                searchItem.expandActionView()
                searchView.setQuery(query, false)
            }
            searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(q: String?) = true
                override fun onQueryTextChange(q: String?): Boolean {
                    vm.setQuery(q.orEmpty())
                    return true
                }
            })
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
            if (menuItem.itemId != R.id.action_refresh) return false
            vm.load()
            return true
        }
    }

    override fun onResume() {
        super.onResume()
        backCallback.isEnabled = vm.state.value.opened != null
        vm.refreshIfNeeded()
    }

    override fun onPause() {
        super.onPause()
        backCallback.isEnabled = false
    }

    override fun subtitle(): String? = vm.state.value.subtitle

    override fun onFilesChanged() = vm.load()

    private fun open(row: Row.Folder?, after: () -> Unit) {
        afterShown = row?.id to after
        vm.open(row)
    }

    override fun goToTopLevel(): Boolean {
        if (vm.state.value.opened == null) return false
        savedScroll = null
        open(null) { list.scrollToPosition(0) }
        return true
    }

    private fun closeGroup() {
        val scroll = savedScroll
        savedScroll = null
        open(null) { scroll?.let { list.layoutManager?.onRestoreInstanceState(it) } }
    }

    override fun onRowClick(position: Int) {
        when (val row = adapter.rows.getOrNull(position)) {
            is Row.Folder -> {
                savedScroll = list.layoutManager?.onSaveInstanceState()
                open(row) { list.scrollToPosition(0) }
            }
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
