package com.ryose.videoplayer

import android.app.Application
import android.content.Context
import android.net.Uri
import android.net.wifi.WifiManager
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.libvlc.Media
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.MediaBrowser
import java.io.File

/**
 * 「フォルダ」タブの状態と操作（FoldersFragment から分けた）。
 * 今いる場所（たどってきた道順）、ストレージ・サーバーの一覧、ネットワークのフォルダの中身と自動検出を持つ。
 * 画面は [state] を表示するだけ。画面が回転しても、今いる場所と読み込んだネットワークのフォルダは残る。
 * ネットワークの閲覧には、アプリで 1 つの VLC の本体（VlcEngine）を使う（再生サービスにつなぐ必要はない）
 */
class FoldersViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {

    /** 場所。端末内のフォルダか、ネットワーク上の場所 */
    sealed class Loc {
        abstract val key: String

        data class Local(val dir: File) : Loc() {
            override val key get() = "L|${dir.path}"
        }

        data class Net(val uri: Uri, val title: String) : Loc() {
            override val key get() = "N|$uri|$title"
        }

        companion object {
            fun parse(s: String): Loc? = when {
                s.startsWith("L|") -> Local(File(s.removePrefix("L|")))
                s.startsWith("N|") -> s.removePrefix("N|").split("|", limit = 2)
                    .takeIf { it.size == 2 }?.let { Net(it[0].toUri(), it[1]) }
                else -> null
            }
        }
    }

    data class UiState(
        /** たどってきた場所（空ならトップ） */
        val stack: List<Loc> = emptyList(),
        /** 場所の表示（「トップ › 内部共有ストレージ › Movies」の「トップ」より後ろ） */
        val labels: List<String> = emptyList(),
        /** 行がどの場所のものか（スクロール位置を戻すときに使う） */
        val locKey: String = ROOT_KEY,
        val rows: List<Row> = emptyList(),
        /** まだ読み込んでいる（ネットワークのフォルダなど）。true で行が空なら、空のお知らせは出さない */
        val loading: Boolean = true,
        val emptyMessage: String = "",
        val emptyIcon: Int = R.drawable.ic_folder,
        /** 空のときに「再試行」を出す（ネットワークのフォルダ） */
        val retry: Boolean = false,
        /** 端末内のフォルダを見る許可が無い */
        val needPermission: Boolean = false,
        /** 今いる場所がお気に入りか */
        val favorite: Boolean = false,
    ) {
        val current: Loc? get() = stack.lastOrNull()
    }

    private val context: Context get() = getApplication()

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** たどってきた場所。アプリが OS に終了させられて戻ってきたときも戻す */
    private var stack: List<Loc> = saved.get<ArrayList<String>>(KEY_STACK)?.mapNotNull { Loc.parse(it) }.orEmpty()
        set(value) {
            field = value
            saved[KEY_STACK] = ArrayList(value.map { it.key })
        }
    private val current get() = stack.lastOrNull()

    @Volatile
    private var roots: List<MediaFiles.Root> = emptyList()
    /** 読み込み終わったネットワークのフォルダの中身（場所ごと）。戻ってきたときに待たずに表示する */
    private val netCache = mutableMapOf<String, List<Row>>()
    /** 自動で見つかったネットワーク上のサーバー（uri → 行） */
    private val discovered = linkedMapOf<String, Row.Folder>()
    /** 今読み込んでいるネットワークのフォルダの中身 */
    private val netRows = mutableListOf<Row>()

    /** ネットワークのフォルダの中身を読む MediaBrowser */
    private var browser: MediaBrowser? = null
    /** browser が読んでいる場所（タブを離れて戻ってきたとき、読み込みの途中から続けるため） */
    private var browsingLoc: Loc.Net? = null
    /** 機器を自動で探す MediaBrowser（閲覧とは別。フォルダに入っても止めない） */
    private var discoveryBrowser: MediaBrowser? = null
    /** 止めた MediaBrowser から遅れて届いた知らせを捨てるための番号 */
    private var browseGen = 0
    private var discoveryGen = 0
    private var multicastLock: WifiManager.MulticastLock? = null
    private var loadJob: Job? = null
    private var renderNetJob: Job? = null
    private var renderSortJob: Job? = null
    private var netTimeoutJob: Job? = null
    /** 他のタブを見ている間、少しして検出・閲覧を止める */
    private var hideStopJob: Job? = null
    /** 画面に表示されている */
    private var visible = false

    /** 今いる場所がネットワークのフォルダか（ログインの確認を出せるようになるまで待つ必要がある） */
    val inNetwork: Boolean get() = current is Loc.Net

    // ---------- 画面から ----------

    /** 画面が表示された（タブを開いた・戻ってきた）：今の場所を読み込み直す */
    fun onVisible() {
        visible = true
        hideStopJob?.cancel()
        // 読み込みの途中でタブを離れていた（動画を再生した、など）：止めていないので、続きから表示する
        val loc = current
        if (loc is Loc.Net && browsingLoc == loc && browser != null) {
            renderNet(loc, finished = false)
            return
        }
        load()
    }

    /**
     * 画面が隠れた：ネットワークの閲覧・検出は少しして止める。
     * すぐ止めると、タブを行き来するたび・動画を再生するたびに、止める・作り直す処理（UPnP・mDNS の終了待ちなど）で画面が止まっていた
     */
    fun onHidden() {
        visible = false
        hideStopJob?.cancel()
        hideStopJob = viewModelScope.launch {
            delay(HIDDEN_STOP_MS)
            if (visible) return@launch
            stopBrowser()
            stopDiscovery()
        }
    }

    /** 今の場所を読み込み直す（ファイルの名前を変えた・サーバーを登録したなど） */
    fun reload() = load()

    /** 今いるネットワークのフォルダを、覚えていた中身を使わずに読み込み直す */
    fun reloadNet() {
        val loc = current as? Loc.Net ?: return
        netCache.remove(loc.key)
        load()
    }

    /** 中に入る。入った場所の key を返す */
    fun push(loc: Loc): String {
        stack = stack + loc
        load()
        return loc.key
    }

    /** トップへ戻る */
    fun goTop() {
        stack = emptyList()
        load()
    }

    /** 一つ上へ。上がった先の key を返す（上がれなければ null） */
    fun goUp(): String? {
        if (stack.isEmpty()) return null
        val leaving = stack.last()
        var next = stack.dropLast(1)
        // 端末内のフォルダは、親フォルダへ（ストレージの一番上ならトップへ）
        if (leaving is Loc.Local && next.isEmpty() && roots.none { it.dir.path == leaving.dir.path }) {
            leaving.dir.parentFile?.let { next = listOf(Loc.Local(it)) }
        }
        stack = next
        load()
        return current?.key ?: ROOT_KEY
    }

    /** 場所の表示をタップした：その階層まで戻る（level 0 がトップ）。戻った先の key を返す */
    fun popTo(level: Int): String? {
        if (level >= stack.size) return null
        stack = stack.take(level)
        load()
        return current?.key ?: ROOT_KEY
    }

    /** お気に入りを開く。端末内のフォルダは、ストレージの一番上からの道順も積んでおく（戻るで一つ上へ） */
    fun openFavorite(entry: FavoriteFolders.Entry) {
        if (entry.isLocal) {
            val dir = File(entry.uri.path ?: return)
            val root = roots.firstOrNull { dir.path == it.dir.path || dir.path.startsWith(it.dir.path + "/") }
            stack = if (root != null) {
                val chain = mutableListOf<File>()
                var d: File? = dir
                while (d != null && d.path != root.dir.path) {
                    chain += d
                    d = d.parentFile
                }
                listOf(Loc.Local(root.dir)) + chain.asReversed().map { Loc.Local(it) }
            } else {
                listOf(Loc.Local(dir))
            }
            load()
        } else {
            push(Loc.Net(entry.uri, entry.title))
        }
    }

    /** お気に入りに追加・削除する。追加したら true と、「元に戻す」で使う変える前の一覧を返す */
    fun toggleFavorite(uri: Uri, title: String): Pair<Boolean, List<FavoriteFolders.Entry>> {
        val favorites = FavoriteFolders(context)
        val before = favorites.all()
        val added = favorites.toggle(uri, title)
        refreshFavorites()
        return added to before
    }

    fun restoreFavorites(before: List<FavoriteFolders.Entry>) {
        FavoriteFolders(context).restoreAll(before)
        refreshFavorites()
    }

    fun renameFavorite(uri: Uri, name: String) {
        FavoriteFolders(context).rename(uri, name)
        refreshFavorites()
    }

    fun isFavorite(uri: Uri) = FavoriteFolders(context).contains(uri)

    fun uriOf(loc: Loc): Uri = when (loc) {
        is Loc.Local -> Uri.fromFile(loc.dir)
        is Loc.Net -> loc.uri
    }

    /** 場所の表示名（ストレージの一番上なら「内部共有ストレージ」など） */
    fun labelOf(loc: Loc): String = when (loc) {
        is Loc.Local -> roots.find { it.dir.path == loc.dir.path }?.name ?: loc.dir.name
        is Loc.Net -> loc.title
    }

    // ---------- 読み込み ----------

    private fun load() {
        loadJob?.cancel()
        stopBrowser()
        when (val loc = current) {
            null -> loadTop()
            is Loc.Local -> loadLocal(loc.dir)
            is Loc.Net -> loadNet(loc)
        }
    }

    private fun publish(
        rows: List<Row>, loading: Boolean = false, emptyMessage: String = "", emptyIcon: Int = R.drawable.ic_folder,
        retry: Boolean = false, needPermission: Boolean = false,
    ) {
        val loc = current
        _state.value = UiState(
            stack = stack,
            labels = stack.map(::labelOf),
            locKey = loc?.key ?: ROOT_KEY,
            rows = rows,
            loading = loading,
            emptyMessage = emptyMessage,
            emptyIcon = emptyIcon,
            retry = retry,
            needPermission = needPermission,
            favorite = loc != null && isFavorite(uriOf(loc)),
        )
    }

    /** お気に入りが変わった：トップにいれば作り直し、そうでなければ ★ の表示だけ直す */
    private fun refreshFavorites() {
        if (current == null) renderTop() else _state.value = _state.value.copy(favorite = isFavorite(uriOf(current!!)))
    }

    /** トップ：お気に入り・ストレージ・登録したサーバー・見つかったサーバー */
    private fun loadTop() {
        // ストレージの一覧が分かっていれば先に出してから読み直す（初めてのときは、読んでから出す。
        // テレビで最初に選ぶ行がストレージになるように）
        if (roots.isNotEmpty()) renderTop()
        loadJob = viewModelScope.launch {
            roots = withContext(Dispatchers.IO) { MediaFiles.roots(context) }
            renderTop()
        }
        startDiscovery()
    }

    private fun renderTop() {
        if (current != null) return
        val rows = mutableListOf<Row>()
        val favorites = FavoriteFolders(context).all()
        if (favorites.isNotEmpty()) {
            rows += Row.Header("お気に入り")
            favorites.forEach { f ->
                val info = if (f.isLocal) f.uri.path.orEmpty() else "${kindLabel(f.uri)} · ${f.uri.host.orEmpty()}${f.uri.path.orEmpty()}"
                rows += Row.Folder(f.title, info, R.drawable.ic_star, id = "fav:${f.uri}")
            }
        }
        rows += Row.Header("ストレージ")
        if (context.hasStorageAccess()) {
            roots.forEach { rows += Row.Folder(it.name, it.dir.path, R.drawable.ic_storage, it.dir, isStorage = true) }
        } else {
            rows += Row.Folder("端末内のファイルを見る", "タップして「すべてのファイルへのアクセス」を許可", R.drawable.ic_storage, id = ACTION_GRANT)
        }
        rows += Row.Header("ネットワーク")
        ServerStore(context).all().forEach { rows += Row.Folder(it.name, it.label, R.drawable.ic_server, id = "server:${it.id}") }
        rows += discovered.values
        rows += Row.Folder("サーバーを追加", "NAS（SMB）・FTP・SFTP・NFS", R.drawable.ic_add, id = ACTION_ADD_SERVER)
        rows += Row.Folder("URLを開く", "http・https・rtsp などのストリーミング", R.drawable.ic_link, id = ACTION_OPEN_URL)
        if (discovered.isEmpty()) {
            // 押せる項目ではないので、押せない行にして、探している最中だと分かるようにくるくるを出す
            rows += Row.Folder(
                "同じネットワークの機器を探しています…", "DLNA サーバーや共有フォルダが見つかるとここに出ます", R.drawable.ic_lan,
                id = ROW_SEARCHING, busy = true,
            )
        }
        publish(rows)
    }

    private fun loadLocal(dir: File) {
        if (!context.hasStorageAccess()) {
            publish(emptyList(), needPermission = true)
            return
        }
        // 読み込み終わるまでは、前の行のまま場所の表示だけ変える（以前と同じ）
        _state.value = _state.value.copy(stack = stack, labels = stack.map(::labelOf))
        loadJob = viewModelScope.launch {
            val rows = withContext(Dispatchers.IO) {
                if (roots.isEmpty()) roots = MediaFiles.roots(context)
                val listing = MediaFiles.list(context, dir)
                listing.folders.map { Row.Folder(it.dir.name, folderInfo(it), dir = it.dir) } +
                    listing.media.map { Row.Media(it, "") }
            }
            if ((current as? Loc.Local)?.dir != dir) return@launch
            publish(rows, emptyMessage = "このフォルダには動画や音楽がありません。", emptyIcon = R.drawable.ic_folder)
        }
    }

    private fun folderInfo(f: MediaFiles.Folder): String {
        val parts = mutableListOf<String>()
        if (f.folderCount > 0) parts += "${f.folderCount} フォルダ"
        if (f.videoCount > 0) parts += "${f.videoCount} 本の動画"
        if (f.audioCount > 0) parts += "${f.audioCount} 曲"
        return if (parts.isEmpty()) "空" else parts.joinToString(" · ")
    }

    // ---------- ネットワーク ----------

    /** VLC の本体（作るのに時間がかかることがあるので、画面の処理の中では作らない） */
    private suspend fun engine() = withContext(Dispatchers.IO) { VlcEngine.current(context) }

    /** 同じネットワーク上の DLNA サーバーや共有フォルダを自動で探す */
    private fun startDiscovery() {
        if (!visible || discoveryBrowser != null) return
        viewModelScope.launch {
            val libVLC = engine()
            if (!visible || current != null || discoveryBrowser != null) return@launch
            acquireMulticast()
            val gen = ++discoveryGen
            val started = android.os.SystemClock.elapsedRealtime()
            discoveryBrowser = MediaBrowser(libVLC, object : MediaBrowser.EventListener {
                override fun onMediaAdded(index: Int, media: IMedia) {
                    if (gen != discoveryGen) return
                    val uri = media.uri ?: return
                    val title = media.getMeta(IMedia.Meta.Title)?.takeIf { it.isNotBlank() } ?: uri.host ?: uri.toString()
                    discovered[uri.toString()] = Row.Folder(title, kindLabel(uri), R.drawable.ic_lan, id = "net:$uri")
                    renderTop()
                }

                override fun onMediaRemoved(index: Int, media: IMedia) {
                    // 止めたときに届く「消えた」は無視する（戻ってきたときに一覧が空にならないように）
                    if (gen != discoveryGen) return
                    media.uri?.let { discovered.remove(it.toString()) }
                    renderTop()
                }

                override fun onBrowseEnd() {}
            }).also { it.discoverNetworkShares() }
            PlaybackLog.add("ネットワークの機器を探し始める（${android.os.SystemClock.elapsedRealtime() - started} ms）")
        }
    }

    private fun stopDiscovery() {
        val b = discoveryBrowser ?: return
        discoveryGen++
        discoveryBrowser = null
        val started = android.os.SystemClock.elapsedRealtime()
        b.release()
        PlaybackLog.add("ネットワークの機器を探すのをやめる（${android.os.SystemClock.elapsedRealtime() - started} ms）")
        if (browser == null) releaseMulticast()
    }

    fun kindLabel(uri: Uri) = when (uri.scheme?.lowercase()) {
        "upnp" -> "DLNA / UPnP メディアサーバー"
        "smb" -> "共有フォルダ（SMB）"
        "ftp", "ftps" -> "FTP"
        "sftp" -> "SFTP"
        "nfs" -> "NFS"
        else -> uri.scheme.orEmpty()
    }

    /** ネットワーク上のフォルダの中身を読み込む */
    private fun loadNet(loc: Loc.Net) {
        netCache[loc.key]?.let { cached ->
            showNet(cached, finished = true)
            return
        }
        netRows.clear()
        publish(emptyList(), loading = true, emptyIcon = R.drawable.ic_lan)
        if (!visible) return
        // サーバーが応答しないまま待ち続けないように、しばらく何も届かなければあきらめる
        netTimeoutJob = viewModelScope.launch {
            delay(NET_TIMEOUT_MS)
            if (current == loc && netRows.isEmpty() && browser != null) {
                stopBrowser()
                publish(
                    emptyList(),
                    emptyMessage = "サーバーから応答がありません。\n\nサーバーの電源やネットワークを確認して、もう一度お試しください。",
                    emptyIcon = R.drawable.ic_lan,
                    retry = true,
                )
            }
        }
        loadJob = viewModelScope.launch {
            val libVLC = engine()
            if (current != loc || !visible) return@launch
            val gen = ++browseGen
            browsingLoc = loc
            acquireMulticast()
            browser = MediaBrowser(libVLC, object : MediaBrowser.EventListener {
                override fun onMediaAdded(index: Int, media: IMedia) {
                    if (gen != browseGen) return
                    val uri = media.uri ?: return
                    val name = media.getMeta(IMedia.Meta.Title)?.takeIf { it.isNotBlank() }
                        ?: Uri.decode(uri.lastPathSegment ?: uri.toString())
                    netRows += if (media.type == IMedia.Type.Directory) {
                        Row.Folder(name, "", R.drawable.ic_folder, id = "net:$uri")
                    } else {
                        Row.Media(PlaylistItem(uri, name, durationMs = media.duration.coerceAtLeast(0)), "")
                    }
                    // まとめて表示を更新する（届くたびに全部を並べ替えないように、間をあける）
                    if (renderNetJob?.isActive != true) {
                        renderNetJob = viewModelScope.launch {
                            delay(400)
                            renderNet(loc, finished = false)
                        }
                    }
                }

                override fun onMediaRemoved(index: Int, media: IMedia) {}

                override fun onBrowseEnd() {
                    if (gen != browseGen) return
                    browsingLoc = null
                    netTimeoutJob?.cancel()
                    renderNetJob?.cancel()
                    renderNet(loc, finished = true)
                }
            }).also { b ->
                val media = Media(libVLC, loc.uri)
                ServerStore(context).optionsFor(loc.uri).forEach { media.addOption(it) }
                b.browse(media, MediaBrowser.Flag.Interact or MediaBrowser.Flag.NoSlavesAutodetect)
                media.release()
            }
        }
    }

    private fun renderNet(loc: Loc.Net, finished: Boolean) {
        if (current != loc) return
        val snapshot = netRows.toList()
        // 並べ替えは裏で（数千件あると、画面の処理が止まっていた）。前の並べ替えは捨てる
        renderSortJob?.cancel()
        renderSortJob = viewModelScope.launch {
            // フォルダを先に、それぞれ名前順
            val sorted = withContext(Dispatchers.Default) {
                snapshot.filterIsInstance<Row.Folder>().sortedWith(compareBy(NaturalOrder) { it.name }) +
                    snapshot.filterIsInstance<Row.Media>().sortedWith(compareBy(NaturalOrder) { it.item.title })
            }
            if (current != loc) return@launch
            if (sorted.isEmpty() && !finished) return@launch
            showNet(sorted, finished)
            // 最後まで読めた中身は覚えておく（空のときは接続の失敗かもしれないので覚えない）
            if (finished && sorted.isNotEmpty()) netCache[loc.key] = sorted
        }
    }

    private fun showNet(rows: List<Row>, finished: Boolean) {
        publish(
            rows,
            loading = !finished,
            emptyMessage = "何も見つかりませんでした。\n\n空のフォルダか、接続できなかった可能性があります。",
            emptyIcon = R.drawable.ic_lan,
            retry = true,
        )
    }

    private fun stopBrowser() {
        renderNetJob?.cancel()
        renderSortJob?.cancel()
        netTimeoutJob?.cancel()
        browseGen++
        browsingLoc = null
        browser?.release()
        browser = null
        if (discoveryBrowser == null) releaseMulticast()
    }

    /** 機器の自動検出（mDNS・SSDP）には Wi-Fi のマルチキャスト受信を有効にする必要がある */
    private fun acquireMulticast() {
        if (multicastLock?.isHeld == true) return
        multicastLock = runCatching {
            context.getSystemService(WifiManager::class.java)
                .createMulticastLock("VideoPlayer").apply {
                    setReferenceCounted(false)
                    acquire()
                }
        }.getOrNull()
    }

    private fun releaseMulticast() {
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    override fun onCleared() {
        hideStopJob?.cancel()
        stopBrowser()
        stopDiscovery()
    }

    companion object {
        const val ROOT_KEY = "<root>"
        /** 他のタブを見ている間、これだけたったらネットワークの検出・閲覧を止める */
        const val HIDDEN_STOP_MS = 60_000L
        const val ACTION_GRANT = "action:grant"
        const val ACTION_ADD_SERVER = "action:add_server"
        const val ACTION_OPEN_URL = "action:open_url"
        const val ROW_SEARCHING = "info:searching"
        private const val KEY_STACK = "stack"
        private const val NET_TIMEOUT_MS = 20_000L
    }
}
