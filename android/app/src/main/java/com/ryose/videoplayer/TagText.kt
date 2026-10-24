package com.ryose.videoplayer

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 曲のタグ（タイトル・アーティスト・アルバム）の文字化けを直す。
 *
 * 日本の古い曲は、タグが Shift_JIS のままなのに「ISO-8859-1」と書かれていることが多く、
 * そのまま読むと「ƒTƒ“ƒvƒ‹」のような欧文の文字の並びになる。
 * そこで、欧文（Latin-1 / Windows-1252）として読んだ文字を元のバイトに戻し、
 * 日本語（Shift_JIS）として正しく読めて、日本語の文字が出てきたときだけ置き換える。
 * 普通の欧文のタグ（é や ü など）は、Shift_JIS として読むと意味をなさないので、そのまま。
 */
object TagText {
    private val sjis: Charset? = runCatching { Charset.forName("windows-31j") }.getOrNull()
        ?: runCatching { Charset.forName("Shift_JIS") }.getOrNull()
    private val cp1252: Charset? = runCatching { Charset.forName("windows-1252") }.getOrNull()

    /** まだ文字化けして見えるか（日本語が無く、欧文の記号や読み取れなかった印が混ざっている） */
    private fun looksBroken(text: String?): Boolean =
        text != null && text.none { Id3Tags.isJapanese(it) } &&
            text.any { it == '�' || it.code in 0x80..0xFF || (it.code in 0x100..0x2FF) || it.code in 0x2000..0x21FF }

    /**
     * 曲の 3 つのタグを直す。文字を置き換えるだけで直るものは [fix] で。
     * それでも化けて見えるもの（端末が読んだ時点で文字が壊れている）は、MP3 ならファイルのタグを読み直す
     */
    fun fixTags(path: String?, title: String?, artist: String?, album: String?): Id3Tags.Tags {
        val t = fix(title)
        val a = fix(artist)
        val l = fix(album)
        if (path == null) return Id3Tags.Tags(t, a, l)
        // 端末は文字コードを推測して読むので、Shift_JIS のタグを中国語・韓国語などと取り違えて、
        // 漢字だらけの別の文字列にすることもある（欧文の化けと違って、見ただけでは分からない）。
        // そのため、ファイルのタグを直接読めるものは、いつも読んで比べる
        val raw = AudioTags.read(path) ?: return Id3Tags.Tags(t, a, l)
        val result = Id3Tags.Tags(pick(t, raw.title), pick(a, raw.artist), pick(l, raw.album))
        if (result != Id3Tags.Tags(t, a, l)) PlaybackLog.add("タグを読み直した：端末「$t」→ファイル「${result.title}」")
        return result
    }

    /** 端末が読んだ文字列 [cur] と、ファイルから直接読んだ [raw] のどちらを使うか */
    private fun pick(cur: String?, raw: String?): String? = when {
        raw.isNullOrBlank() -> cur
        cur.isNullOrBlank() || looksBroken(cur) -> raw
        // ファイルのほうが日本語として読めて、端末の読みと違うなら、ファイルのほう（端末の取り違えを直す）
        raw != cur && raw.any { Id3Tags.isJapanese(it) } -> raw
        else -> cur
    }

    fun fix(text: String?): String? {
        if (text.isNullOrEmpty()) return text
        // 欧文として読んだ文字だけで、欧文でない文字（日本語など）が混ざっていないものが対象
        if (text.none { it.code >= 0x80 } || text.any { it.code > 0xFF && toCp1252Byte(it) == null }) return text
        val bytes = toBytes(text) ?: return text
        val decoded = decodeStrict(bytes, sjis) ?: return text
        return if (decoded.any { isJapanese(it) }) decoded else text
    }

    /** 文字を Windows-1252 のバイトに戻す。戻せなければ null */
    private fun toBytes(text: String): ByteArray? {
        val out = ByteArray(text.length)
        for ((i, c) in text.withIndex()) {
            out[i] = (if (c.code <= 0xFF) c.code else toCp1252Byte(c) ?: return null).toByte()
        }
        return out
    }

    /** 0x80〜0x9F の範囲にある Windows-1252 の文字（ƒ や ‚ など）のバイト。無ければ null */
    private fun toCp1252Byte(c: Char): Int? {
        val cs = cp1252 ?: return null
        val b = cs.encode(c.toString())
        return if (b.remaining() == 1) b.get().toInt() and 0xFF else null
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset?): String? {
        charset ?: return null
        return try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    private fun isJapanese(c: Char): Boolean = when (Character.UnicodeBlock.of(c)) {
        Character.UnicodeBlock.HIRAGANA,
        Character.UnicodeBlock.KATAKANA,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS,
        -> true
        else -> false
    }
}
