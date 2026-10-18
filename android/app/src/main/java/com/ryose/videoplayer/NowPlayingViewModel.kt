package com.ryose.videoplayer

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow

/**
 * ミニプレイヤーの状態と操作。状態は再生サービスの「今再生しているもの」（PlaybackService.nowPlaying）を見るだけ。
 * 操作（再生・一時停止・次へ・止める）は、サービスが動いているときだけつないで頼む（サービスを起こしはしない）
 */
class NowPlayingViewModel(app: Application) : AndroidViewModel(app) {

    val state: StateFlow<PlaybackService.NowPlaying?> = PlaybackService.nowPlaying

    private val connection = PlaybackConnection(app, autoCreate = false, onConnected = {}).also { it.bind() }

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

    override fun onCleared() {
        connection.unbind()
    }
}
