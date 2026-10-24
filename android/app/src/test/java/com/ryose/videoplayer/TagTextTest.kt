package com.ryose.videoplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TagTextTest {
    /** Shift_JIS のタグを、Latin-1 として読んだときの文字化け（Android が出すもの）を作る */
    private fun mojibake(text: String, read: String = "ISO-8859-1") =
        String(text.toByteArray(charset("windows-31j")), charset(read))

    @Test
    fun ShiftJISのタグをLatin1で読んだ文字化けを直す() {
        assertEquals("サンプル曲", TagText.fix(mojibake("サンプル曲")))
        assertEquals("夜に駆ける", TagText.fix(mojibake("夜に駆ける")))
    }

    @Test
    fun Windows1252で読んだ文字化けも直す() {
        // 0x83 0x54 などは Windows-1252 では ƒT のようになる
        assertEquals("サンプル", TagText.fix(mojibake("サンプル", "windows-1252")))
    }

    @Test
    fun 欧文のタグはそのまま() {
        assertEquals("Café del Mar", TagText.fix("Café del Mar"))
        assertEquals("Björk", TagText.fix("Björk"))
        assertEquals("Hello", TagText.fix("Hello"))
    }

    @Test
    fun 日本語として正しいタグはそのまま() {
        assertEquals("サンプル曲", TagText.fix("サンプル曲"))
    }

    @Test
    fun 壊れて戻せないタグは使わない() {
        // 2026-10-24 の再生の記録にあった実物（端末は「锟斤拷」、ファイルのタグには「�」がそのまま入っていた）
        assertNull(TagText.fixTags(null, "5. 锟斤拷锟桔傦拷锟桔欙拷锟斤拷锟介壒", null, null).title)
        assertNull(TagText.fixTags(null, "5. ���ۂ�", null, null).title)
        // 壊れていなければそのまま
        assertEquals("夜に駆ける", TagText.fixTags(null, "夜に駆ける", null, null).title)
        assertEquals("Café del Mar", TagText.fixTags(null, "Café del Mar", null, null).title)
    }

    @Test
    fun 空やnullはそのまま() {
        assertNull(TagText.fix(null))
        assertEquals("", TagText.fix(""))
    }
}
