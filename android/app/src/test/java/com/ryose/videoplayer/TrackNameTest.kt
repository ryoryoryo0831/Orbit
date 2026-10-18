package com.ryose.videoplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackNameTest {
    @Test
    fun 日本語の音声トラック() {
        // VLC が付ける名前（言語のコードを英語の名前に）
        assertTrue(TrackRules.isJapanese("Track 1 - [Japanese]"))
        assertTrue(TrackRules.isJapanese("Dolby Digital 5.1 - [Japanese]"))
        // 知らないコードはそのまま
        assertTrue(TrackRules.isJapanese("Track 2 - [jp]"))
        assertTrue(TrackRules.isJapanese("Track 2 - [JPN]"))
        // MKV などのトラック名
        assertTrue(TrackRules.isJapanese("日本語 5.1ch"))
    }

    @Test
    fun 日本語ではない音声トラック() {
        assertFalse(TrackRules.isJapanese("Track 1 - [English]"))
        assertFalse(TrackRules.isJapanese("Track 1"))
        assertFalse(TrackRules.isJapanese("無効"))
        assertFalse(TrackRules.isJapanese(null))
    }

    @Test
    fun 音声解説() {
        assertTrue(TrackRules.isCommentary("Commentary - [Japanese]"))
        assertTrue(TrackRules.isCommentary("監督による音声解説"))
        assertFalse(TrackRules.isCommentary("Track 1 - [Japanese]"))
    }
}
