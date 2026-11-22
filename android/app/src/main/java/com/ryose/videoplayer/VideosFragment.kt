package com.ryose.videoplayer

import android.os.Bundle
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.core.view.MenuProvider
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import android.provider.MediaStore
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.launch

/**
 * 「動画」タブ：端末内のすべての動画（すべて／フォルダ別、リスト／グリッド）。
 * 状態と操作は VideosViewModel にあり、ここは表示するだけ
 */
class VideosFragment : LibraryTabFragment() {

    private lateinit var vm: VideosViewModel
    private lateinit var allChip: Chip
    private lateinit var folderChip: Chip
    /** 「一部の動画だけを表示しています」の帯 */
    private lateinit var limitedBanner: View
    /** 「選び直す」で許可の画面を出したので、戻ってきたら読み込み直す */
    private var reloadOnResume = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = ViewModelProvider(this)[VideosViewModel::class.java]
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(menuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED)
        // 動画が増えた・消えたら自動で読み込み直す
        watchMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI) { if (vm.state.value.loaded) vm.load() }

        // 「すべて」「フォルダ別」の切り替え
        val chips = view.findViewById<ChipGroup>(R.id.chips)
        chips.visibility = View.VISIBLE
        fun chip(label: String, byFolder: Boolean) = choiceChip(label) {
            vm.setByFolder(byFolder)
            updateChips(vm.state.value)
        }
        allChip = chip("すべて", false)
        folderChip = chip("フォルダ別", true)
        chips.addView(allChip)
        chips.addView(folderChip)
        updateChips(vm.state.value)

        limitedBanner = view.findViewById(R.id.limitedBanner)
        view.findViewById<View>(R.id.limitedReselect).setOnClickListener {
            // もう一度許可を求めると、Android が見せる動画を選び直す画面を出す
            reloadOnResume = true
            (activity as? MainActivity)?.requestMediaAccess()
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect(::render)
            }
        }
    }

    private fun render(s: VideosViewModel.UiState) {
        updateChips(s)
        limitedBanner.isVisible = s.hasAccess && s.limited
        when {
            !s.hasAccess -> {
                showNeedPermission()
                onListHidden(s.openedFolder)
            }
            !s.loaded -> {
                loading.visibility = View.VISIBLE
                onListHidden(s.openedFolder)
            }
            else -> {
                showRows(s.rows, s.emptyMessage)
                // フォルダを開いた・閉じた直後なら、その一覧が出たところで位置を合わせる
                onListShown(s.openedFolder)
            }
        }
    }

    private fun updateChips(s: VideosViewModel.UiState) {
        allChip.isChecked = !s.byFolder
        folderChip.isChecked = s.byFolder
    }

    override fun onResume() {
        super.onResume()
        applyLayout()
        vm.refreshIfNeeded(force = reloadOnResume)
        reloadOnResume = false
        // 再生画面などから戻ったとき、お気に入りの ★ を付け直す
        render(vm.state.value)
    }

    override fun subtitle() = vm.state.value.subtitle

    /** リスト／グリッドの切り替え（グリッドでは見出しやフォルダの行は横いっぱいに） */
    private fun applyLayout() {
        val grid = AppSettings.videosGrid(requireContext())
        adapter.grid = grid
        if (grid) {
            // 幅の広い画面ではタブが左にあるので、その分を引いて考える
            val w = resources.configuration.screenWidthDp
            val widthDp = if (w >= 600) w - RAIL_WIDTH_DP else w
            val span = (widthDp / 180).coerceAtLeast(2)
            if ((list.layoutManager as? GridLayoutManager)?.spanCount != span) {
                list.layoutManager = GridLayoutManager(requireContext(), span).apply {
                    spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                        override fun getSpanSize(position: Int) =
                            if (adapter.rows.getOrNull(position) is Row.Media) 1 else span
                    }
                }
            }
        } else {
            // リスト表示：タブレットなど幅の広い画面では 2〜3 列に並べる
            setColumns(listColumns())
        }
    }

    override fun onFilesChanged() = vm.load()

    override fun openedId(): String? = vm.state.value.openedFolder

    // フォルダの行の id は「vfolder:フォルダの場所」、開いたあとの openedFolder はフォルダの場所
    override fun idOf(row: Row.Folder?): String? = row?.id?.removePrefix("vfolder:")

    override fun requestOpen(row: Row.Folder?) = vm.openFolder(idOf(row))

    override fun onRowClick(position: Int) {
        when (val row = adapter.rows.getOrNull(position)) {
            is Row.Folder -> if (row.id?.startsWith("vfolder:") == true) enter(row)
            // 「すべて」の一覧は関係ない動画が並ぶので、終わったら次へ進むかは設定しだい（初期設定は進まない）。
            // フォルダを開いて再生したときは、いつも次へ進む
            is Row.Media -> playMediaAt(
                position,
                advance = vm.state.value.openedFolder != null || AppSettings.videosAutoNext(requireContext()),
            )
            else -> {}
        }
    }

    // フォルダの行には必ず動画がある（毎回すべての動画から探すと重いので、ここでは調べない）
    override fun hasExtraActions(row: Row) = (row as? Row.Folder)?.id?.startsWith("vfolder:") == true

    override fun extraActions(row: Row): List<SheetItem> {
        val dir = (row as? Row.Folder)?.id?.removePrefix("vfolder:") ?: return emptyList()
        val items = vm.videosIn(dir)
        if (items.isEmpty()) return emptyList()
        return listOf(
            SheetItem(R.drawable.ic_play, "再生") { requireActivity().playItems(items, 0) },
            SheetItem(R.drawable.ic_shuffle, "シャッフル再生") { requireActivity().playItems(items, items.indices.random(), shuffle = true) },
            SheetItem(R.drawable.ic_playlist_add, "プレイリストに追加") { PlaylistDialogs.addToPlaylist(requireContext(), items) },
        )
    }

    private val menuProvider = object : MenuProvider {
        override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {
            menuInflater.inflate(R.menu.videos_menu, menu)
            setUpSearch(menu, getString(R.string.search), vm.state.value.query, vm::setQuery)
        }

        override fun onPrepareMenu(menu: Menu) {
            val id = when (vm.state.value.sort) {
                VideosViewModel.Sort.DATE -> R.id.sort_date
                VideosViewModel.Sort.NAME -> R.id.sort_name
                VideosViewModel.Sort.DURATION -> R.id.sort_duration
            }
            menu.findItem(id)?.isChecked = true
            menu.findItem(R.id.action_grid)?.isChecked = AppSettings.videosGrid(requireContext())
        }

        override fun onMenuItemSelected(menuItem: MenuItem): Boolean {
            when (menuItem.itemId) {
                R.id.action_refresh -> vm.load()
                R.id.sort_date -> setSort(VideosViewModel.Sort.DATE)
                R.id.sort_name -> setSort(VideosViewModel.Sort.NAME)
                R.id.sort_duration -> setSort(VideosViewModel.Sort.DURATION)
                R.id.action_grid -> {
                    AppSettings.setVideosGrid(requireContext(), !AppSettings.videosGrid(requireContext()))
                    requireActivity().invalidateOptionsMenu()
                    applyLayout()
                }
                else -> return false
            }
            return true
        }
    }

    private fun setSort(s: VideosViewModel.Sort) {
        vm.setSort(s)
        requireActivity().invalidateOptionsMenu()
    }
}
