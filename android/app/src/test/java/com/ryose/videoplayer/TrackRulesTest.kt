package com.ryose.videoplayer

import com.ryose.videoplayer.TrackRules.Choice
import com.ryose.videoplayer.TrackRules.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackRulesTest {
    // VLC が付けるトラック名（ドクター・ストレンジの DVD で実際に出たもの）
    private val off = Track(-1, "無効")
    private val english = Track(1, "Track 1 - [English]")
    private val japanese = Track(2, "Track 2 - [Japanese]")
    private val audio = listOf(off, english, japanese)
    private val spu = listOf(off, Track(3, "Track 1 - [Japanese]"), Track(4, "Track 2 - [English]"))

    private fun decide(
        audio: List<Track> = this.audio, currentAudio: Int = 1,
        spu: List<Track> = this.spu, currentSpu: Int = 3,
        chosenAudio: Int? = null, chosenSpu: Int? = null, keepSubtitles: Boolean = false,
    ) = TrackRules.decide(audio, currentAudio, spu, currentSpu, chosenAudio, chosenSpu, keepSubtitles)

    @Test
    fun 日本語の音声トラック名() {
        // VLC が付ける名前（言語のコードを英語の名前に）
        assertTrue(TrackRules.isJapanese("Track 1 - [Japanese]"))
        assertTrue(TrackRules.isJapanese("Dolby Digital 5.1 - [Japanese]"))
        // 知らないコードはそのまま
        assertTrue(TrackRules.isJapanese("Track 2 - [jp]"))
        assertTrue(TrackRules.isJapanese("Track 2 - [JPN]"))
        // MKV などのトラック名
        assertTrue(TrackRules.isJapanese("日本語 5.1ch"))
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

    @Test
    fun 日本語の音声があれば日本語にして字幕を消す() {
        assertEquals(Choice(audio = 2, spu = -1), decide())
    }

    @Test
    fun もう日本語なら字幕だけ消す() {
        assertEquals(Choice(audio = null, spu = -1), decide(currentAudio = 2))
        // 字幕も最初からオフなら何もしない
        assertEquals(Choice(), decide(currentAudio = 2, currentSpu = -1))
    }

    @Test
    fun 日本語の音声が無ければ何もしない() {
        assertEquals(Choice(), decide(audio = listOf(off, english)))
        assertEquals(Choice(), decide(audio = emptyList()))
    }

    @Test
    fun 音声解説より本編の日本語を選ぶ() {
        val withCommentary = listOf(off, english, Track(5, "Commentary - [Japanese]"), japanese)
        assertEquals(2, decide(audio = withCommentary).audio)
    }

    @Test
    fun 手で選んだものを優先する() {
        // 英語の音声を手で選んでいたら、日本語に戻さない（字幕は自動で消す）
        assertEquals(Choice(audio = null, spu = -1), decide(currentAudio = 1, chosenAudio = 1))
        // 開き直して VLC が別の音声にしたら、手で選んだものに戻す
        assertEquals(1, decide(currentAudio = 2, chosenAudio = 1).audio)
        // 字幕を手で選んでいたら消さない
        assertEquals(Choice(audio = 2, spu = null), decide(currentSpu = 3, chosenSpu = 3))
        // 字幕を手でオフにしていたら、オフに戻す
        assertEquals(-1, decide(currentSpu = 4, chosenSpu = -1).spu)
        // 手で選んだトラックが無くなっていたら（別の動画など）触らない
        assertEquals(Choice(audio = 2, spu = null), decide(currentSpu = 4, chosenSpu = 99))
    }

    @Test
    fun 自分で足した字幕は消さない() {
        assertEquals(Choice(audio = 2, spu = null), decide(keepSubtitles = true))
    }
}
