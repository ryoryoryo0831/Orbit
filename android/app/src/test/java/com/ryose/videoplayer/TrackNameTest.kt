package com.ryose.videoplayer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackNameTest {
    @Test
    fun 日本語の音声トラック() {
        // VLC が付ける名前（言語のコードを英語の名前に）
        assertTrue(MediaFiles.isJapaneseTrack("Track 1 - [Japanese]"))
        assertTrue(MediaFiles.isJapaneseTrack("Dolby Digital 5.1 - [Japanese]"))
        // 知らないコードはそのまま
        assertTrue(MediaFiles.isJapaneseTrack("Track 2 - [jp]"))
        assertTrue(MediaFiles.isJapaneseTrack("Track 2 - [JPN]"))
        // MKV などのトラック名
        assertTrue(MediaFiles.isJapaneseTrack("日本語 5.1ch"))
    }

    @Test
    fun 日本語ではない音声トラック() {
        assertFalse(MediaFiles.isJapaneseTrack("Track 1 - [English]"))
        assertFalse(MediaFiles.isJapaneseTrack("Track 1"))
        assertFalse(MediaFiles.isJapaneseTrack("無効"))
        assertFalse(MediaFiles.isJapaneseTrack(null))
    }

    @Test
    fun 音声解説() {
        assertTrue(MediaFiles.isCommentaryTrack("Commentary - [Japanese]"))
        assertTrue(MediaFiles.isCommentaryTrack("監督による音声解説"))
        assertFalse(MediaFiles.isCommentaryTrack("Track 1 - [Japanese]"))
    }
}
