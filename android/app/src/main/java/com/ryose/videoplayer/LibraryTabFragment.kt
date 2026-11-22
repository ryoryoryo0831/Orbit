package com.ryose.videoplayer

import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.view.Menu
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip

/**
 * 「動画」「音楽」タブの共通の土台（VideosFragment・MusicFragment で同じだったものをまとめた）：
 * 上の切り替えチップ・検索・端末のファイルの見張り・中に入る一覧（フォルダ・アルバム・アーティスト）と「戻る」。
 * 中に入る前の一覧のスクロール位置を覚えておき、戻ったときに戻す
 */
abstract class LibraryTabFragment : BaseListFragment() {
    /** 中に入る前の一覧のスクロール位置（戻ったときに戻す） */
    private var savedScroll: Parcelable? = null
    /** 開いた・閉じたあと、その一覧を表示したときにすること（[openedId] と、一番上を見せる・前の位置に戻す） */
    private var afterShown: Pair<String?, () -> Unit>? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = leave()
    }

    /** 今開いている一覧の id（一番上なら null） */
    protected abstract fun openedId(): String?

    /** [row] の中（null なら一番上）を開くよう ViewModel に頼む */
    protected abstract fun requestOpen(row: Row.Folder?)

    /** [row] を開いたあとの [openedId]（null なら一番上） */
    protected open fun idOf(row: Row.Folder?): String? = row?.id

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
    }

    override fun onResume() {
        super.onResume()
        backCallback.isEnabled = openedId() != null
    }

    override fun onPause() {
        super.onPause()
        backCallback.isEnabled = false
    }

    /** 一覧を表示した。[opened] は表示した一覧の id。開いた・閉じた直後なら、ここで位置を合わせる */
    protected fun onListShown(opened: String?) {
        backCallback.isEnabled = isResumed && opened != null
        afterShown?.let { (id, action) ->
            if (id == opened) {
                afterShown = null
                action()
            }
        }
    }

    /** 許可が無い・読み込み中など、一覧を出していないとき（「戻る」は開いているかだけで決める） */
    protected fun onListHidden(opened: String?) {
        backCallback.isEnabled = isResumed && opened != null
    }

    /** [row] の中に入る（今の位置を覚えて、中の一覧は一番上から見せる） */
    protected fun enter(row: Row.Folder) {
        savedScroll = list.layoutManager?.onSaveInstanceState()
        navigate(row) { list.scrollToPosition(0) }
    }

    /** 一つ上に戻る（入る前の位置に戻す） */
    private fun leave() {
        val scroll = savedScroll
        savedScroll = null
        navigate(null) { scroll?.let { list.layoutManager?.onRestoreInstanceState(it) } }
    }

    private fun navigate(row: Row.Folder?, after: () -> Unit) {
        // 先に何をするかを決めてから頼む（頼んだとたんに一覧が届くこともあるので）
        afterShown = idOf(row) to after
        requestOpen(row)
    }

    override fun goToTopLevel(): Boolean {
        if (openedId() == null) return false
        savedScroll = null
        navigate(null) { list.scrollToPosition(0) }
        return true
    }

    /** 上の切り替えチップ（テレビのリモコンで選んでいるときは青い枠） */
    protected fun choiceChip(label: String, onClick: () -> Unit) = Chip(requireContext()).apply {
        foreground = ContextCompat.getDrawable(context, R.drawable.fg_focus_ring)
        id = View.generateViewId()
        text = label
        isCheckable = true
        setOnClickListener { onClick() }
    }

    /** メニューの検索欄を用意する（前に入れた言葉があれば開いたまま戻す） */
    protected fun setUpSearch(menu: Menu, hint: String, query: String, onChange: (String) -> Unit) {
        val searchItem = menu.findItem(R.id.action_search) ?: return
        val searchView = searchItem.actionView as SearchView
        searchView.queryHint = hint
        if (query.isNotEmpty()) {
            searchItem.expandActionView()
            searchView.setQuery(query, false)
        }
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(q: String?) = true
            override fun onQueryTextChange(q: String?): Boolean {
                onChange(q.orEmpty())
                return true
            }
        })
    }

    /** 端末の動画・曲が増えた・消えたら [reload] する（画面が出ている間だけ） */
    protected fun watchMedia(uri: Uri, reload: () -> Unit) {
        viewLifecycleOwner.lifecycle.addObserver(MediaWatcher(requireContext().applicationContext, uri, reload))
    }
}
