package com.ryose.videoplayer

import android.os.Handler
import org.videolan.libvlc.MediaPlayer

/**
 * DVD・Blu-ray の ISO の再生の段取り（PlaybackService から分けた）。
 * 本編を選ぶ・メニューより先に流れる宣伝を飛ばす・映像の無いメニューから本編へ移る・メニューから出たことに気づく。
 * 何をするかの判断は DiscRules（テストできる部分）、VLC の操作は Host（PlaybackService）に任せる
 */
class DiscController(private val handler: Handler, private val host: Host) {

    /** 再生サービスから借りるもの */
    interface Host {
        /** 今の再生の番号（別の曲・動画に変わったら、待っている処理を捨てる） */
        val generation: Int
        /** 今の曲・動画が ISO か */
        val isDiscItem: Boolean
        /** 画面が無い間に始まった（映像を出していないので、映像の有無で判断できない） */
        val startedWithoutVideo: Boolean
        val videoTracksCount: Int
        fun vlcTitles(): Array<MediaPlayer.Title>
        fun vlcTitle(): Int
        fun setVlcTitle(index: Int)
        fun vlcNavigate(direction: Int)
        fun hasMedia(): Boolean
        fun setVlcTime(ms: Long)
        /** タイトルが変わった（画面に伝える） */
        fun onTitleChanged()
        /** 本編などを再生している（メニューではない）。1 秒ごとに呼ぶので、音声・字幕を見直す */
        fun onPlayingContent()
    }

    /** 本編を選ぶのを待っている再生（generation の番号。無ければ -1） */
    private var titlePending = -1
    /** 本編を選んだあとに移る位置（続きから再生） */
    private var startMs = 0L
    /** 本編の代わりに選ぶタイトル（前回の続きで、見ていたタイトル。無ければ null） */
    private var resumeTitle: Int? = null
    /** メニューから始めた DVD で、メニューに映像があるかを見る再生 */
    private var menuWatch = -1
    /** メニューから始めた DVD で、メニューより先に流れる宣伝などを飛ばす再生（始めるのを待っている／見ている間） */
    private var jumpPending = -1
    private var jumpGen = -1

    /** DVD・Blu-ray のタイトル（本編・特典映像・メニューなど） */
    fun titles(): Array<MediaPlayer.Title> = host.vlcTitles()

    fun currentTitle(): Int = host.vlcTitle()

    private fun rules(): List<DiscRules.Title> = host.vlcTitles().map { DiscRules.Title(it.isMenu, it.duration) }

    /** 再生画面からタイトル（本編・特典・メニュー）を選ぶ */
    fun playTitle(index: Int) {
        // 自分で選んだので、宣伝を飛ばしてメニューへ移す処理はやめる
        jumpGen = -1
        host.setVlcTitle(index)
        host.onTitleChanged()
    }

    /** DVD のメニューを表示しているか（今のタイトルがメニュー） */
    fun isInMenu(): Boolean = host.isDiscItem && DiscRules.isInMenu(rules(), host.vlcTitle())

    /** DVD のメニューのタイトル（無ければ -1） */
    fun menuTitle(): Int = if (host.isDiscItem) DiscRules.menuIndex(rules()) else -1

    /** DVD のメニューを開く。開けなければ false */
    fun openMenu(): Boolean {
        val i = menuTitle()
        if (i < 0) return false
        playTitle(i)
        return true
    }

    /** 記録（PlaybackLog）に残すための、前に見たタイトル */
    private var lastTitle = -1

    /** DVD のメニューの操作（MediaPlayer.Navigate の上・下・左・右・決定） */
    fun navigate(direction: Int) {
        // メニューを操作したら、そのあと本編などへ進んでもメニューへ戻さない
        jumpGen = -1
        host.vlcNavigate(direction)
    }

    /**
     * ISO を開く前に、どう始めるかを決める。
     * メニューから始めないときは、再生が始まってから本編（いちばん長いタイトル）を選び、続きの位置へ移る。
     * [resumeTitle] があれば、本編の代わりにそのタイトル（前回の続きで見ていた話）を選ぶ
     */
    fun prepare(gen: Int, startInMenu: Boolean, startMs: Long, resumeTitle: Int? = null) {
        this.startMs = startMs
        lastTitle = -1
        this.resumeTitle = resumeTitle
        if (startInMenu) {
            menuWatch = gen
            jumpPending = gen
            jumpGen = gen
        } else {
            titlePending = gen
        }
    }

    /** 再生が始まった（MediaPlayer.Event.Playing。一時停止からの再開でも来る） */
    fun onPlaying(gen: Int) {
        if (titlePending == gen) {
            titlePending = -1
            scheduleMainTitle(gen, 0)
        }
        if (menuWatch == gen) {
            menuWatch = -1
            watchEmptyMenu(gen, 0, 0)
        }
        if (jumpPending == gen) {
            jumpPending = -1
            jumpToMenu(gen, 0, 0)
        }
        if (host.isDiscItem) {
            handler.removeCallbacks(menuPoll)
            handler.post(menuPoll)
        }
    }

    /**
     * 「メニューから始める」とき、ディスクがメニューより先に宣伝・予告編・本編などのタイトルを流したら、メニューへ移る。
     * クリミナル・マインド S3 などは、ディスク自体が最初に「リリース情報」などの宣伝を流す作りで、
     * メニューが最初に来ないとユーザーから言われた（2026-10-17）。
     * 著作権の注意などはメニューの仕組みの中で出ることが多い（ドクター・ストレンジ）ので、メニューの間は待つ。
     * メニューへは VLC のタイトル 0 ＝ libdvdnav のルートメニューの呼び出しで移る（dvdnav.c の DEMUX_SET_TITLE）。
     * 移れないディスクもある（libdvdnav は First Play の間などはメニューを呼べない）ので、3 回まで試す。
     * メニューへ移れたら、またはユーザーがメニューを操作した・タイトルを選んだ・メニューに映像が無くて本編にしたらやめる。1 分たったらやめる
     */
    private fun jumpToMenu(gen: Int, tries: Int, jumps: Int) {
        handler.postDelayed({
            if (gen != host.generation || jumpGen != gen) return@postDelayed
            val titles = rules()
            var n = jumps
            when (DiscRules.menuJumpStep(titles, host.vlcTitle(), jumps)) {
                DiscRules.MenuJump.DONE -> {
                    jumpGen = -1
                    return@postDelayed
                }
                DiscRules.MenuJump.JUMP -> {
                    PlaybackLog.add("DVD：メニューより先に流れたタイトル ${host.vlcTitle()} を飛ばしてメニューへ")
                    host.setVlcTitle(DiscRules.menuIndex(titles))
                    host.onTitleChanged()
                    n++
                }
                DiscRules.MenuJump.WAIT -> {}
            }
            if (tries < 60) jumpToMenu(gen, tries + 1, n) else jumpGen = -1
        }, 1000)
    }

    /**
     * メニューから始めた DVD で、メニューを表示しているのに映像が 6 秒たっても無いときは、本編を再生する。
     * 本編だけを取り出した ISO では、メニューのタイトルに映像が無く、黒い画面のまま止まっていた（2026-10-17 にテレビの実機で確認）。
     * 1 分まで待つ。映像が出てきたら見るのをやめる
     */
    private fun watchEmptyMenu(gen: Int, tries: Int, emptySeconds: Int) {
        handler.postDelayed({
            if (gen != host.generation || host.startedWithoutVideo) return@postDelayed
            val empty = DiscRules.emptyMenuSeconds(isInMenu(), host.videoTracksCount, emptySeconds) ?: return@postDelayed
            when {
                empty >= DiscRules.EMPTY_MENU_SECONDS -> {
                    PlaybackLog.add("DVD：メニューに映像が無いので本編へ")
                    chooseMainTitle(gen, 0)
                }
                tries < 60 -> watchEmptyMenu(gen, tries + 1, empty)
            }
        }, 1000)
    }

    private fun scheduleMainTitle(gen: Int, tries: Int) {
        // 始まりが遅く感じないよう、細かく見て、タイトルの一覧が分かったらすぐ本編へ（以前は 0.4 秒ごと）
        handler.postDelayed({ chooseMainTitle(gen, tries) }, 150)
    }

    /**
     * 本編（メニューではない、いちばん長いタイトル）を再生する。
     * タイトルの一覧は再生が始まってから少しして分かるので、分かるまで少し待つ
     */
    private fun chooseMainTitle(gen: Int, tries: Int) {
        if (gen != host.generation) return
        // 本編にしたので、メニューへ戻さない
        jumpGen = -1
        val titles = rules()
        if (titles.isEmpty()) {
            if (tries < 27) scheduleMainTitle(gen, tries + 1) else seekStart(gen)
            return
        }
        val main = resumeTitle?.takeIf { it in titles.indices && !titles[it].isMenu } ?: DiscRules.mainTitle(titles)
        resumeTitle = null
        PlaybackLog.add("DVD：タイトル $main を選ぶ（${titles.size} 個のうち、$tries 回目）")
        if (main != null && main != host.vlcTitle()) {
            host.setVlcTitle(main)
            host.onTitleChanged()
        }
        seekStart(gen)
    }

    /** 本編に切り替わってから、続きの位置へ移る */
    private fun seekStart(gen: Int) {
        val ms = startMs
        startMs = 0
        if (ms <= 0) return
        handler.postDelayed({ if (gen == host.generation) host.setVlcTime(ms) }, 250)
    }

    /** DVD の本編などに移ったとき（メニューから出たとき）に知らせる。VLC はタイトルが変わったことを知らせないので 1 秒ごとに見る */
    private val menuPoll = object : Runnable {
        override fun run() {
            if (!host.isDiscItem || !host.hasMedia()) return
            val inMenu = isInMenu()
            val title = host.vlcTitle()
            if (title != lastTitle) {
                PlaybackLog.add("DVD のタイトル：$lastTitle → $title${if (inMenu) "（メニュー）" else ""}")
                lastTitle = title
            }
            // メニューから出たとき、と本編などを再生している間は、音声・字幕を見直す
            // （区切りが変わるたびにディスクが音声を 1 本目に戻すことがある。クリミナル・マインドの ISO の命令で確認）
            if (!inMenu) host.onPlayingContent()
            handler.postDelayed(this, 1000)
        }
    }
}
