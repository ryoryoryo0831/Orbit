package com.ryose.videoplayer

import android.content.Context
import android.os.Handler
import androidx.core.content.edit
import org.videolan.libvlc.MediaPlayer

/**
 * 動画ごとに手で選んだ音声・字幕のトラックを覚えておく（アプリが OS に終了させられたあとも、開き直したときに戻す）。
 * 字幕の -1 は「オフ」
 */
class TrackChoiceStore(private val context: Context) {
    // 再生サービスを作っている途中では読めないので、初めて使うときに開く
    private val prefs by lazy { context.getSharedPreferences("track_choice", Context.MODE_PRIVATE) }

    fun audio(key: String): Int? = prefs.getInt("a|$key", NONE).takeIf { it != NONE }
    fun spu(key: String): Int? = prefs.getInt("s|$key", NONE).takeIf { it != NONE }
    fun setAudio(key: String, id: Int) = prefs.edit { putInt("a|$key", id) }
    fun setSpu(key: String, id: Int) = prefs.edit { putInt("s|$key", id) }
    fun clearSpu(key: String) = prefs.edit { remove("s|$key") }

    private companion object {
        const val NONE = Int.MIN_VALUE
    }
}

/**
 * 音声・字幕のトラックを選ぶ（PlaybackService から分けた）。
 * 日本語の音声があれば日本語・字幕オフ、手で選んだものは優先して開き直しても戻す。
 * 何を選ぶかの判断は TrackRules（テストできる部分）、VLC の操作は Host（PlaybackService）に任せる
 */
class TrackSelector(private val handler: Handler, private val store: TrackChoiceStore, private val host: Host) {

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

    // 動画ごとに手で選んだ音声・字幕のトラックは [store] に覚えておき、開き直したとき
    // （デコードの切り替え・アプリが OS に終了させられたあとなど）に戻す

    /** 再生画面のメニューから音声を選んだ */
    fun selectAudio(key: String?, id: Int) {
        key?.let { store.setAudio(it, id) }
        host.setAudioTrack(id)
    }

    /** 再生画面のメニューから字幕を選んだ（-1 でオフ） */
    fun selectSpu(key: String?, id: Int) {
        key?.let { store.setSpu(it, id) }
        host.setSpuTrack(id)
    }

    /** 「字幕ファイルを追加」した：前に字幕を「オフ」にしていても、追加した字幕を出す */
    fun onSubtitleAdded(key: String) {
        store.clearSpu(key)
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
        // 音声トラックがまだ無い（VLC の一覧にはいつも「オフ」があるので、空なら分かっていない）ときは選ばない
        val audio = host.audioTracks()
        if (audio.isEmpty()) return
        val choice = TrackRules.decide(
            audio = audio.map { TrackRules.Track(it.id, it.name) },
            currentAudio = host.audioTrack,
            spu = host.spuTracks().map { TrackRules.Track(it.id, it.name) },
            currentSpu = host.spuTrack,
            chosenAudio = store.audio(key),
            chosenSpu = store.spu(key),
            keepSubtitles = host.hasAddedSubtitles(key),
        )
        choice.audio?.let { host.setAudioTrack(it) }
        choice.spu?.let { host.setSpuTrack(it) }
    }
}
