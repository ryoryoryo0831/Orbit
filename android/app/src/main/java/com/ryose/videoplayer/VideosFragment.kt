package com.ryose.videoplayer

import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SearchView
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
class VideosFragment : BaseListFragment() {

    private lateinit var vm: VideosViewModel
    private lateinit var allChip: Chip
    private lateinit var folderChip: Chip
    /** 「一部の動画だけを表示しています」の帯 */
    private lateinit var limitedBanner: View
    /** 「選び直す」で許可の画面を出したので、戻ってきたら読み込み直す */
    private var reloadOnResume = false
    /** フォルダに入る前の一覧のスクロール位置（戻ったときに戻す） */
    private var savedScroll: Parcelable? = null
    /** フォルダを開いた・閉じたあと、その一覧を表示したときにすること（一番上を見せる・前の位置に戻す） */
    private var afterShown: Pair<String?, () -> Unit>? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = closeFolder()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = ViewModelProvider(this)[VideosViewModel::class.java]
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().addMenuProvider(menuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        // 動画が増えた・消えたら自動で読み込み直す
        viewLifecycleOwner.lifecycle.addObserver(
            MediaWatcher(requireContext().applicationContext, MediaStore.Video.Media.EXTERNAL_CONTENT_URI) {
                if (vm.state.value.loaded) vm.load()
            }
        )

        // 「すべて」「フォルダ別」の切り替え
        val chips = view.findViewById<ChipGroup>(R.id.chips)
        chips.visibility = View.VISIBLE
        fun chip(label: String, byFolder: Boolean) = Chip(requireContext()).apply {
            // テレビのリモコンで選んでいるときは青い枠
            foreground = androidx.core.content.ContextCompat.getDrawable(context, R.drawable.fg_focus_ring)
            id = View.generateViewId()
            text = label
            isCheckable = true
            setOnClickListener {
                vm.setByFolder(byFolder)
                updateChips(vm.state.value)
            }
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
        backCallback.isEnabled = isResumed && s.openedFolder != null
        when {
            !s.hasAccess -> showNeedPermission()
            !s.loaded -> loading.visibility = View.VISIBLE
            else -> {
                showRows(s.rows, s.emptyMessage)
                // フォルダを開いた・閉じた直後なら、その一覧が出たところで位置を合わせる
                afterShown?.let { (folder, action) ->
                    if (folder == s.openedFolder) {
                        afterShown = null
                        action()
                    }
                }
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

    override fun onPause() {
        super.onPause()
        backCallback.isEnabled = false
    }

    override fun subtitle() = vm.state.value.subtitle

    /** リスト／グリッドの切り替え（グリッドでは見出しやフォルダの行は横いっぱいに） */
    private fun applyLayout() {
        val grid = AppSettings.videosGrid(requireContext())
        adapter.grid = grid
        if (grid) {
            // 幅の広い画面ではタブが左にあるので、その分を引いて考える
            val w = resources.configuration.screenWidthDp
            val widthDp = if (w >= 600) w - 80 else w
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

    private fun openFolder(dir: String?, after: () -> Unit) {
        afterShown = dir to after
        vm.openFolder(dir)
    }

    override fun goToTopLevel(): Boolean {
        if (vm.state.value.openedFolder == null) return false
        savedScroll = null
        openFolder(null) { list.scrollToPosition(0) }
        return true
    }

    private fun closeFolder() {
        val scroll = savedScroll
        savedScroll = null
        openFolder(null) { scroll?.let { list.layoutManager?.onRestoreInstanceState(it) } }
    }

    override fun onRowClick(position: Int) {
        when (val row = adapter.rows.getOrNull(position)) {
            is Row.Folder -> row.id?.removePrefix("vfolder:")?.let { dir ->
                savedScroll = list.layoutManager?.onSaveInstanceState()
                openFolder(dir) { list.scrollToPosition(0) }
            }
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
            val searchItem = menu.findItem(R.id.action_search)
            val searchView = searchItem.actionView as SearchView
            searchView.queryHint = getString(R.string.search)
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
