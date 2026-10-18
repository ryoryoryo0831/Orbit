package com.ryose.videoplayer

/**
 * A-B リピート（PlaybackService から分けた）。A・B を持ち、ファイルごとに覚え（AbStore）、B を過ぎたら A へ戻す。
 * 何をするかの判断は AbRules（テストできる部分）、VLC の操作は Host（PlaybackService）に任せる
 */
class AbRepeater(private val store: AbStore, private val host: Host) {

    /** 再生サービスから借りるもの */
    interface Host {
        /** 今の再生位置（VLC の位置） */
        val timeMs: Long
        val lengthMs: Long
        /** 今の曲・動画の key（覚えておくときに使う） */
        val itemKey: String?
        fun seek(ms: Long)
        /** A・B が変わった（画面に伝える） */
        fun onChanged()
    }

    var range = AbRules.Range()
        private set

    /** ファイルを開いたときに、覚えていた A-B リピートを戻したお知らせ（画面が一度だけ受け取って出す） */
    private var restoredMessage: String? = null

    fun takeRestoredMessage(): String? = restoredMessage.also { restoredMessage = null }

    /**
     * 新しい曲・動画を開くとき：A-B リピートを解除し、前に使っていたファイルならその区間を戻す。
     * 戻した区間の外から始まるときは A から始めるので、始める位置を返す
     */
    fun onItemOpened(key: String, startMs: Long): Long {
        restoredMessage = null
        val saved = store.get(key)?.let { (a, b) -> AbRules.Range(a, b) }
        range = saved ?: AbRules.Range()
        if (saved == null) return startMs
        restoredMessage = "前回の A-B リピート（${formatTime(saved.a)} 〜 ${formatTime(saved.b)}）を戻しました"
        return AbRules.restoredStart(saved, startMs)
    }

    /** A を今の位置にする。結果を文章で返す */
    fun setA(): String = apply(AbRules.setA(range, host.timeMs))

    /** B を今の位置にして、A に戻ってくり返しを始める。結果を文章で返す */
    fun setB(): String = apply(AbRules.setB(range, host.timeMs))

    /** A か B を少しずらす（[deltaMs] がマイナスなら前へ）。ずらせないときは null */
    fun nudge(isA: Boolean, deltaMs: Long): String? =
        AbRules.nudge(range, isA, deltaMs, host.lengthMs)?.let { apply(it) }

    fun clear() {
        range = AbRules.Range()
        remember()
        host.onChanged()
    }

    fun label(): String? = AbRules.label(range)

    /** 再生位置が進んだとき（TimeChanged）：B を過ぎたら A に戻る */
    fun onTime(t: Long) {
        AbRules.loopBackTo(range, t)?.let { host.seek(it) }
    }

    private fun apply(result: AbRules.Result): String {
        if (!result.changed) return result.message
        range = result.range
        result.seekTo?.let { host.seek(it) }
        remember()
        host.onChanged()
        return result.message
    }

    /** 今の A・B を覚える（両方決まっているときだけ。そうでなければ忘れる） */
    private fun remember() {
        val key = host.itemKey ?: return
        if (range.isLoop) store.save(key, range.a, range.b) else store.clear(key)
    }
}
