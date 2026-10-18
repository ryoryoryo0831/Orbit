package com.ryose.videoplayer

import android.net.Uri
import android.os.Bundle
import android.os.Parcelable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.ryose.videoplayer.FoldersViewModel.Companion.ACTION_ADD_SERVER
import com.ryose.videoplayer.FoldersViewModel.Companion.ACTION_GRANT
import com.ryose.videoplayer.FoldersViewModel.Companion.ACTION_OPEN_URL
import com.ryose.videoplayer.FoldersViewModel.Companion.ROOT_KEY
import com.ryose.videoplayer.FoldersViewModel.Loc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 「フォルダ」タブ：端末のストレージと、ネットワーク（NAS・DLNA サーバー・登録したサーバー）をたどる。
 * 今いる場所・一覧・ネットワークの閲覧は FoldersViewModel にあり、ここは表示と、画面が要るもの
 * （ダイアログ・ログインの確認・スクロール位置・フォルダを再生するときの進み具合）だけ
 */
class FoldersFragment : BaseListFragment() {

    private lateinit var vm: FoldersViewModel
    /** 場所ごとのスクロール位置（戻ったときに元の位置に戻すため） */
    private val scrollStates = mutableMapOf<String, Parcelable?>()
    /** 移動したあと、その場所の一覧を表示したときに戻すスクロール位置（null なら一番上） */
    private var pendingScroll: Pair<String, Parcelable?>? = null
    private var shownLocKey: String? = null

    // ログインの確認などのダイアログと、ネットワークのフォルダをまとめて再生するときは、再生サービスの VLC を使う
    private var svc: PlaybackService? = null
    private lateinit var connection: PlaybackConnection
    private val dialogHandler by lazy { VlcDialogHandler(requireActivity()) { (vm.state.value.current as? Loc.Net)?.uri } }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = goUp()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        vm = ViewModelProvider(this)[FoldersViewModel::class.java]
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
        requireActivity().addMenuProvider(menuProvider, viewLifecycleOwner, Lifecycle.State.RESUMED)
        connection = PlaybackConnection(requireContext(), autoCreate = true, onConnected = { s ->
            svc = s
            if (isResumed) s.setDialogCallbacks(dialogHandler)
        }, onDisconnected = { svc = null })

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.state.collect(::render)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        connection.bind()
    }

    override fun onStop() {
        super.onStop()
        connection.unbind()
        svc = null
    }

    override fun onResume() {
        super.onResume()
        svc?.setDialogCallbacks(dialogHandler)
        // 再生から戻ってきたときなどは、スクロール位置を保ったまま読み込み直す
        // （ネットワークのフォルダは覚えておいた中身を出すので、待たされない）
        keepScroll()
        vm.onVisible()
    }

    override fun onPause() {
        super.onPause()
        // 他のタブを見ているときは「戻る」でフォルダを上がらない
        backCallback.isEnabled = false
        vm.onHidden()
        svc?.setDialogCallbacks(null)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        netCollector?.cancel()
        netCollector = null
        netDialog?.dismiss()
        netDialog = null
    }

    // ---------- 表示 ----------

    private fun render(s: FoldersViewModel.UiState) {
        backCallback.isEnabled = isResumed && s.stack.isNotEmpty()
        if (shownLocKey != s.locKey) {
            shownLocKey = s.locKey
            activity?.invalidateOptionsMenu()
        }
        renderPath(s)
        when {
            s.needPermission -> showNeedPermission()
            // ネットワークのフォルダを読み込み始めたところ：空のお知らせは出さずに、くるくるだけ
            s.loading && s.rows.isEmpty() -> {
                adapter.rows = emptyList()
                emptyView.visibility = View.GONE
                list.visibility = View.VISIBLE
                loading.visibility = View.VISIBLE
                updateSubtitle()
            }
            else -> {
                showRows(
                    s.rows, s.emptyMessage, s.emptyIcon,
                    actionLabel = if (s.retry) "再試行" else null,
                    action = if (s.retry) vm::reloadNet else null,
                )
                if (s.loading) loading.visibility = View.VISIBLE
            }
        }
        // 移動した先の一覧が出たら、スクロール位置を戻す
        val pending = pendingScroll
        if (pending != null && pending.first == s.locKey && (s.rows.isNotEmpty() || !s.loading)) {
            pendingScroll = null
            val state = pending.second
            if (state != null) list.layoutManager?.onRestoreInstanceState(state) else list.scrollToPosition(0)
        }
    }

    // 中に入っているときは、上のバーに場所を表示する
    override fun subtitle(): String? = if (vm.state.value.stack.isEmpty()) "ストレージとネットワーク" else null

    /** 「トップ › 内部共有ストレージ › Movies」のような場所の表示。タップでその階層へ戻る */
    private fun renderPath(s: FoldersViewModel.UiState) {
        val view = view ?: return
        val scroll = view.findViewById<HorizontalScrollView>(R.id.pathScroll)
        val bar = view.findViewById<LinearLayout>(R.id.pathBar)
        bar.removeAllViews()
        if (s.stack.isEmpty()) {
            scroll.visibility = View.GONE
            return
        }
        scroll.visibility = View.VISIBLE
        val ctx = requireContext()
        val pad = (8 * resources.displayMetrics.density).toInt()
        fun segment(text: String, level: Int, isLast: Boolean) {
            if (level > 0) {
                bar.addView(TextView(ctx).apply {
                    this.text = "›"
                    setTextColor(ContextCompat.getColor(ctx, R.color.text_muted))
                    textSize = 16f
                })
            }
            bar.addView(TextView(ctx).apply {
                this.text = text
                textSize = 15f
                minHeight = (48 * resources.displayMetrics.density).toInt()
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                maxWidth = (240 * resources.displayMetrics.density).toInt()
                gravity = Gravity.CENTER_VERTICAL
                setPadding(pad, 0, pad, 0)
                setTextColor(ContextCompat.getColor(ctx, if (isLast) R.color.text_primary else R.color.accent))
                if (isLast) paint.isFakeBoldText = true
                if (!isLast) {
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    setOnClickListener { popTo(level) }
                } else if (s.current is Loc.Net) {
                    // 今いるネットワークのフォルダの名前をタップすると、読み込み直す
                    setBackgroundResource(android.R.drawable.list_selector_background)
                    setOnClickListener { vm.reloadNet() }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        }
        segment("トップ", 0, false)
        s.labels.forEachIndexed { i, label -> segment(label, i + 1, i == s.labels.lastIndex) }
        // 一番奥（今いる場所）が見えるように右端までスクロール
        scroll.post { scroll.fullScroll(View.FOCUS_RIGHT) }
    }

    // ---------- 移動 ----------

    private val currentKey get() = vm.state.value.current?.key ?: ROOT_KEY

    /** 読み込み直したあとも、今のスクロール位置のまま */
    private fun keepScroll() {
        pendingScroll = currentKey to list.layoutManager?.onSaveInstanceState()
    }

    private fun push(loc: Loc) {
        scrollStates[currentKey] = list.layoutManager?.onSaveInstanceState()
        pendingScroll = vm.push(loc) to null
    }

    private fun popTo(level: Int) {
        val key = vm.popTo(level) ?: return
        pendingScroll = key to scrollStates[key]
    }

    override fun goToTopLevel(): Boolean {
        if (vm.state.value.stack.isEmpty()) return false
        pendingScroll = ROOT_KEY to null
        vm.goTop()
        return true
    }

    private fun goUp() {
        val key = vm.goUp() ?: return
        pendingScroll = key to scrollStates.remove(key)
    }

    private fun openFavorite(entry: FavoriteFolders.Entry) {
        scrollStates[ROOT_KEY] = list.layoutManager?.onSaveInstanceState()
        vm.openFavorite(entry)
        pendingScroll = currentKey to null
    }

    override fun onFilesChanged() {
        keepScroll()
        vm.reload()
    }

    private fun reloadKeepingScroll() {
        keepScroll()
        vm.reload()
    }

    override fun onGrantClick() {
        (activity as? MainActivity)?.requestStorageAccess()
    }

    override val needPermissionText: Int get() = R.string.need_permission

    // ---------- お気に入り ----------

    /** 上のバーの星：今いる場所をお気に入りに追加・削除する */
    private val menuProvider = object : MenuProvider {
        override fun onCreateMenu(menu: android.view.Menu, menuInflater: android.view.MenuInflater) {
            menuInflater.inflate(R.menu.folders_menu, menu)
        }

        override fun onPrepareMenu(menu: android.view.Menu) {
            val s = vm.state.value
            menu.findItem(R.id.action_play_folder)?.isVisible = s.current != null
            val item = menu.findItem(R.id.action_favorite) ?: return
            item.isVisible = s.current != null
            item.setIcon(if (s.favorite) R.drawable.ic_star else R.drawable.ic_star_border)
            item.title = if (s.favorite) "お気に入りから外す" else "お気に入りに追加"
        }

        override fun onMenuItemSelected(menuItem: android.view.MenuItem): Boolean {
            when (menuItem.itemId) {
                R.id.action_play_folder -> when (val loc = vm.state.value.current) {
                    is Loc.Local -> ActionSheet.show(requireContext(), vm.labelOf(loc), playFolderItems(loc.dir))
                    is Loc.Net -> ActionSheet.show(requireContext(), vm.labelOf(loc), playNetItems(loc.uri, vm.labelOf(loc)))
                    null -> {}
                }
                R.id.action_favorite -> vm.state.value.current?.let { toggleFavorite(vm.uriOf(it), vm.labelOf(it)) }
                else -> return false
            }
            return true
        }
    }

    private fun toggleFavorite(uri: Uri, title: String) {
        val (added, before) = vm.toggleFavorite(uri, title)
        if (added) {
            Toast.makeText(requireContext(), "「$title」をお気に入りに追加しました", Toast.LENGTH_SHORT).show()
        } else {
            // 外したときは「元に戻す」で、元の順番のまま戻せる
            requireActivity().showUndo("「$title」をお気に入りから外しました") {
                vm.restoreFavorites(before)
                activity?.invalidateOptionsMenu()
            }
        }
        requireActivity().invalidateOptionsMenu()
    }

    private fun favoriteItem(uri: Uri, title: String): SheetItem {
        val fav = vm.isFavorite(uri)
        return SheetItem(
            if (fav) R.drawable.ic_star else R.drawable.ic_star_border,
            if (fav) "お気に入りから外す" else "お気に入りに追加",
            active = fav,
        ) { toggleFavorite(uri, title) }
    }

    // ---------- 行 ----------

    override fun onRowClick(position: Int) {
        when (val row = adapter.rows.getOrNull(position)) {
            is Row.Folder -> {
                val id = row.id
                when {
                    row.dir != null -> push(Loc.Local(row.dir))
                    id == ACTION_GRANT -> (activity as? MainActivity)?.requestStorageAccess()
                    id == ACTION_ADD_SERVER -> NetworkDialogs.editServer(requireActivity(), null) { reloadKeepingScroll() }
                    id == ACTION_OPEN_URL -> NetworkDialogs.openUrl(requireActivity())
                    id != null && id.startsWith("server:") -> ServerStore(requireContext()).get(id.removePrefix("server:"))
                        ?.let { push(Loc.Net(it.uri, it.name)) }
                    id != null && id.startsWith("net:") -> push(Loc.Net(id.removePrefix("net:").toUri(), row.name))
                    id != null && id.startsWith("fav:") -> FavoriteFolders(requireContext()).all()
                        .find { it.uri.toString() == id.removePrefix("fav:") }?.let { openFavorite(it) }
                }
            }
            is Row.Media -> playMediaAt(position)
            else -> {}
        }
    }

    // メニューがある行（フォルダ・お気に入り・サーバー・ネットワークの場所）に︙ボタンを出す
    override fun hasExtraActions(row: Row): Boolean {
        if (row !is Row.Folder) return false
        val id = row.id
        return row.dir != null || (id != null && (id.startsWith("fav:") || id.startsWith("server:") || id.startsWith("net:")))
    }

    override fun extraActions(row: Row): List<SheetItem> {
        if (row !is Row.Folder) return emptyList()
        val id = row.id
        val dir = row.dir
        return when {
            dir != null -> buildList {
                if (!row.isStorage) {
                    addAll(playFolderItems(dir))
                    add(SheetItem(R.drawable.ic_playlist_add, "プレイリストに追加") {
                        withFolderMedia(dir) { PlaylistDialogs.addToPlaylist(requireContext(), it) }
                    })
                }
                add(favoriteItem(Uri.fromFile(dir), row.name))
            }
            id != null && id.startsWith("fav:") -> {
                val uri = id.removePrefix("fav:").toUri()
                // ここから再生もできる（端末内のフォルダも、ネットワークの場所も）
                val localDir = uri.takeIf { it.scheme == "file" }?.path?.let { File(it) }?.takeIf { it.isDirectory }
                val play = when {
                    localDir != null -> playFolderItems(localDir)
                    uri.scheme != "file" -> playNetItems(uri, row.name)
                    else -> emptyList()
                }
                play + listOf(
                    SheetItem(R.drawable.ic_edit, "表示名を変更") {
                        PlaylistDialogs.promptName(requireContext(), "表示名を変更", row.name) { name -> vm.renameFavorite(uri, name) }
                    },
                    SheetItem(R.drawable.ic_star_border, "お気に入りから外す") { toggleFavorite(uri, row.name) },
                )
            }
            id != null && id.startsWith("server:") -> {
                val store = ServerStore(requireContext())
                val server = store.get(id.removePrefix("server:")) ?: return emptyList()
                playNetItems(server.uri, server.name) + listOf(
                    favoriteItem(server.uri, server.name),
                    SheetItem(R.drawable.ic_edit, "編集") { NetworkDialogs.editServer(requireActivity(), server) { reloadKeepingScroll() } },
                    SheetItem(R.drawable.ic_delete, "削除") {
                        PlaylistDialogs.confirm(requireContext(), "「${server.name}」の登録を削除しますか？", "削除") {
                            store.remove(server.id)
                            reloadKeepingScroll()
                        }
                    },
                )
            }
            id != null && id.startsWith("net:") -> buildList {
                val uri = id.removePrefix("net:").toUri()
                addAll(playNetItems(uri, row.name))
                add(favoriteItem(uri, row.name))
                // 見つかった共有フォルダを、ログイン情報付きで登録する
                if (vm.state.value.current == null && uri.scheme?.lowercase() in Server.PROTOCOLS.keys) {
                    add(SheetItem(R.drawable.ic_login, "ログイン情報を設定して登録") {
                        NetworkDialogs.editServer(requireActivity(), null, uri) { reloadKeepingScroll() }
                    })
                }
            }
            else -> emptyList()
        }
    }

    /**
     * フォルダを再生するメニューの項目。サブフォルダがあれば、その中まで全部をまとめて再生する項目も出す
     */
    private fun playFolderItems(dir: File): List<SheetItem> = buildList {
        add(SheetItem(R.drawable.ic_play, "このフォルダを再生") { withFolderMedia(dir) { requireActivity().playItems(it, 0) } })
        add(SheetItem(R.drawable.ic_shuffle, "シャッフル再生") {
            withFolderMedia(dir) { requireActivity().playItems(it, it.indices.random(), shuffle = true) }
        })
        if (MediaFiles.hasSubfolders(dir)) {
            add(SheetItem(R.drawable.ic_folder_open, "サブフォルダも含めて再生", "中のフォルダの動画・音楽も、すべてまとめて") {
                withFolderTree(dir) { requireActivity().playItems(it, 0) }
            })
            add(SheetItem(R.drawable.ic_shuffle, "サブフォルダも含めてシャッフル再生") {
                withFolderTree(dir) { requireActivity().playItems(it, it.indices.random(), shuffle = true) }
            })
        }
    }

    /** フォルダの中と、そのすべてのサブフォルダの中の動画・音楽を集めてから action に渡す（数が多いと時間がかかる） */
    private fun withFolderTree(dir: File, action: (List<PlaylistItem>) -> Unit) {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            // すぐに終わらないときだけ、調べていることを知らせる
            val notice = launch {
                kotlinx.coroutines.delay(600)
                Toast.makeText(ctx, "フォルダの中を調べています…", Toast.LENGTH_SHORT).show()
            }
            val media = withContext(Dispatchers.IO) { MediaFiles.mediaInTree(ctx, dir) }
            notice.cancel()
            if (media.isEmpty()) {
                Toast.makeText(ctx, "このフォルダとサブフォルダには動画や音楽がありません", Toast.LENGTH_SHORT).show()
            } else {
                if (media.size >= 5000) Toast.makeText(ctx, "多すぎるので、最初の 5000 件を再生します", Toast.LENGTH_LONG).show()
                action(media)
            }
        }
    }

    /** ネットワークのフォルダ（NAS・DLNA など）を再生するメニューの項目。サブフォルダがあるかは読んでみないと分からないので、いつも出す */
    private fun playNetItems(uri: Uri, title: String): List<SheetItem> = listOf(
        SheetItem(R.drawable.ic_play, "このフォルダを再生") {
            withNetFolder(uri, title, includeSubfolders = false) { requireActivity().playItems(it, 0) }
        },
        SheetItem(R.drawable.ic_shuffle, "シャッフル再生") {
            withNetFolder(uri, title, includeSubfolders = false) { requireActivity().playItems(it, it.indices.random(), shuffle = true) }
        },
        SheetItem(R.drawable.ic_folder_open, "サブフォルダも含めて再生", "中のフォルダの動画・音楽も、すべてまとめて") {
            withNetFolder(uri, title, includeSubfolders = true) { requireActivity().playItems(it, 0) }
        },
        SheetItem(R.drawable.ic_shuffle, "サブフォルダも含めてシャッフル再生") {
            withNetFolder(uri, title, includeSubfolders = true) { requireActivity().playItems(it, it.indices.random(), shuffle = true) }
        },
    )

    /** ネットワークのフォルダの中身を集めている途中のもの（画面を閉じたら止める） */
    private var netCollector: NetTreeCollector? = null
    private var netDialog: androidx.appcompat.app.AlertDialog? = null

    /**
     * ネットワークのフォルダの動画・音楽を（[includeSubfolders] ならサブフォルダの中まで）集めてから action に渡す。
     * ネットワーク越しで時間がかかるので、進み具合とキャンセルのボタンを出す
     */
    private fun withNetFolder(uri: Uri, title: String, includeSubfolders: Boolean, action: (List<PlaylistItem>) -> Unit) {
        val s = svc
        if (s == null) {
            Toast.makeText(requireContext(), "準備中です。少し待ってからもう一度お試しください", Toast.LENGTH_SHORT).show()
            return
        }
        netCollector?.cancel()
        netDialog?.dismiss()
        val ctx = requireContext()
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
            .setTitle("「$title」を調べています")
            .setMessage("ネットワークのフォルダを読んでいます…")
            .setNegativeButton("キャンセル", null)
            .create()
        val collector = NetTreeCollector(
            ctx, s.libVLC, uri, includeSubfolders,
            onProgress = { folders, found ->
                dialog.setMessage(
                    if (includeSubfolders) "$folders 個のフォルダを読みました\n見つかった動画・音楽：$found 件" else "見つかった動画・音楽：$found 件"
                )
            },
            onDone = { items, truncated ->
                netCollector = null
                netDialog = null
                dialog.dismiss()
                if (view != null) {
                    when {
                        items.isEmpty() -> Toast.makeText(ctx, "動画や音楽が見つかりませんでした", Toast.LENGTH_SHORT).show()
                        else -> {
                            if (truncated) Toast.makeText(ctx, "多すぎるので、見つかった ${items.size} 件を再生します", Toast.LENGTH_LONG).show()
                            action(items)
                        }
                    }
                }
            },
        )
        // キャンセル・戻るで閉じたら、読むのをやめる
        dialog.setOnDismissListener {
            if (netCollector === collector) {
                collector.cancel()
                netCollector = null
                netDialog = null
            }
        }
        netCollector = collector
        netDialog = dialog
        dialog.show()
        collector.start()
    }

    /** フォルダ直下の動画・音楽を読み込んでから処理する（空なら知らせる） */
    private fun withFolderMedia(dir: File, action: (List<PlaylistItem>) -> Unit) {
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val media = withContext(Dispatchers.IO) { MediaFiles.mediaIn(ctx, dir) }
            if (media.isEmpty()) {
                Toast.makeText(ctx, "このフォルダの直下には動画や音楽がありません", Toast.LENGTH_SHORT).show()
            } else {
                action(media)
            }
        }
    }
}
