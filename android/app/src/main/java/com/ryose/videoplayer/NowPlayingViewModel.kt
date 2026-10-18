package com.ryose.videoplayer

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * ミニプレイヤーの状態と操作。状態は再生サービスの「今再生しているもの」（PlaybackService.nowPlaying）を見るだけ。
 * 操作（再生・一時停止・次へ・止める）は、サービスが動いているときだけつないで頼む（サービスを起こしはしない）。
 * つなぐのは画面が見えている間だけ（[onScreenStart]〜[onScreenStop]）
 */
class NowPlayingViewModel(app: Application) : AndroidViewModel(app) {

    val state: StateFlow<PlaybackService.NowPlaying?> = PlaybackService.nowPlaying

    /**
     * 画面で見ている印。付いている間は、一時停止のままでも再生サービスは 30 分で終わらない
     * （ミニプレイヤーが見ている前で消えないように）
     */
    private val watcher = object : PlaybackService.Listener {}

    private val connection = PlaybackConnection(
        app, autoCreate = false,
        onConnected = { s ->
            s.addListener(watcher)
            // 見ていない間に変わったもの（準備中の一時停止など）があっても、今の状態から出し直す
            s.refreshNowPlaying()
        },
    )

    /**
     * 画面が見えたとき（onStart）。再生サービスが終わるとつながりは切れるので（作り直されても戻らない）、
     * 見えるたびにつなぎ直す
     */
    fun onScreenStart() = connection.bind()

    /** 画面が見えなくなったとき（onStop） */
    fun onScreenStop() {
        connection.service?.removeListener(watcher)
        connection.unbind()
    }

    fun togglePlay() {
        connection.service?.togglePlay()
    }

    fun next() {
        connection.service?.next()
    }

    fun stop() {
        connection.service?.stopPlayback()
    }

    /** 今再生しているものの画面（動画なら再生画面、曲なら音楽の画面）を開く Intent */
    fun screenIntent(): Intent? = connection.service?.screenIntent()

    override fun onCleared() = onScreenStop()
}
