package com.ryose.videoplayer

import android.os.Handler
import org.videolan.libvlc.MediaPlayer

/**
 * 音声・字幕のトラックを選ぶ（PlaybackService から分けた）。
 * 日本語の音声があれば日本語・字幕オフ、手で選んだものは優先して開き直しても戻す。
 * 何を選ぶかの判断は TrackRules（テストできる部分）、VLC の操作は Host（PlaybackService）に任せる
 */
class TrackSelector(private val handler: Handler, private val host: Host) {

    /** 再生サービスから借りるもの */
    interface Host {
        /**
         * 今トラックを選んでよい動画のキー。選ばないとき（曲・準備中・DVD のメニューの間）は null。
         * DVD のメニューのボタン（選んでいる所の色）は字幕の仕組みで出しているので、メニューの間は触らない
         */
        fun trackTarget(): String?
        fun audioTracks(): Array<MediaPlayer.TrackDescription>
        fun spuTracks(): Array<MediaPlayer.TrackDescription>
        val audioTrack: Int
        val spuTrack: Int
        fun setAudioTrack(id: Int)
        fun setSpuTrack(id: Int)
        /** 「字幕ファイルを追加」で自分で足した字幕がある */
        fun hasAddedSubtitles(key: String): Boolean
    }

    /** 動画ごとに手で選んだ音声・字幕のトラック（アプリを閉じるまで覚えておき、開き直したとき＝デコードの切り替えなどに戻す） */
    private val chosenAudio = mutableMapOf<String, Int>()
    private val chosenSpu = mutableMapOf<String, Int>()

    /** 再生画面のメニューから音声を選んだ */
    fun selectAudio(key: String?, id: Int) {
        key?.let { chosenAudio[it] = id }
        host.setAudioTrack(id)
    }

    /** 再生画面のメニューから字幕を選んだ（-1 でオフ） */
    fun selectSpu(key: String?, id: Int) {
        key?.let { chosenSpu[it] = id }
        host.setSpuTrack(id)
    }

    /** 「字幕ファイルを追加」した：前に字幕を「オフ」にしていても、追加した字幕を出す */
    fun onSubtitleAdded(key: String) {
        chosenSpu.remove(key)
    }

    private val rule = Runnable { apply() }

    /** トラックが増えたり選ばれたりするたびに呼ばれるので、まとめてから選ぶ */
    fun schedule() {
        handler.removeCallbacks(rule)
        handler.postDelayed(rule, 300)
    }

    /** 字幕はあとから増える（隣の字幕ファイル・DVD の本編に移ったとき）ので、そのたびに見直す */
    private fun apply() {
        val key = host.trackTarget() ?: return
        val choice = TrackRules.decide(
            audio = host.audioTracks().map { TrackRules.Track(it.id, it.name) },
            currentAudio = host.audioTrack,
            spu = host.spuTracks().map { TrackRules.Track(it.id, it.name) },
            currentSpu = host.spuTrack,
            chosenAudio = chosenAudio[key],
            chosenSpu = chosenSpu[key],
            keepSubtitles = host.hasAddedSubtitles(key),
        )
        choice.audio?.let { host.setAudioTrack(it) }
        choice.spu?.let { host.setSpuTrack(it) }
    }
}
