package com.ryose.videoplayer

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * MP3 のタグ（ID3v2・ID3v1）を、文字コードを変換する前の生のバイトで読む。
 * 端末が読んだ文字列は、Shift_JIS のタグだと文字が壊れて戻せないことがあるので、
 * 文字化けして見える曲だけ、ファイルから読み直して自分で文字コードを決める。
 */
object Id3Tags {
    data class Tags(val title: String?, val artist: String?, val album: String?)

    private val sjis: Charset? = runCatching { Charset.forName("windows-31j") }.getOrNull()
        ?: runCatching { Charset.forName("Shift_JIS") }.getOrNull()

    fun read(path: String): Tags? {
        return runCatching {
            RandomAccessFile(File(path), "r").use { f -> readV2(f) ?: readV1(f) }
        }.getOrNull()
    }

    private fun readV2(f: RandomAccessFile): Tags? {
        val head = ByteArray(10)
        f.seek(0)
        if (f.read(head) != 10 || head[0] != 'I'.code.toByte() || head[1] != 'D'.code.toByte() || head[2] != '3'.code.toByte()) return null
        val version = head[3].toInt()
        if (version !in 2..4) return null
        val size = synchsafe(head, 6)
        if (size <= 0 || size > 4_000_000) return null
        val body = ByteArray(size)
        f.readFully(body)
        val idLen = if (version == 2) 3 else 4
        val headLen = if (version == 2) 6 else 10
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var p = 0
        while (p + headLen < body.size) {
            if (body[p].toInt() == 0) break
            val id = String(body, p, idLen, Charsets.ISO_8859_1)
            val len = when (version) {
                2 -> (u(body[p + 3]) shl 16) or (u(body[p + 4]) shl 8) or u(body[p + 5])
                3 -> (u(body[p + 4]) shl 24) or (u(body[p + 5]) shl 16) or (u(body[p + 6]) shl 8) or u(body[p + 7])
                else -> synchsafe(body, p + 4)
            }
            val start = p + headLen
            if (len <= 0 || start + len > body.size) break
            val value = { decodeFrame(body, start, len) }
            when (id) {
                "TIT2", "TT2" -> title = value()
                "TPE1", "TP1" -> artist = value()
                "TALB", "TAL" -> album = value()
            }
            p = start + len
        }
        return if (title == null && artist == null && album == null) null else Tags(title, artist, album)
    }

    private fun readV1(f: RandomAccessFile): Tags? {
        if (f.length() < 128) return null
        val b = ByteArray(128)
        f.seek(f.length() - 128)
        f.readFully(b)
        if (b[0] != 'T'.code.toByte() || b[1] != 'A'.code.toByte() || b[2] != 'G'.code.toByte()) return null
        fun field(from: Int): String? {
            var end = from + 30
            while (end > from && (b[end - 1].toInt() == 0 || b[end - 1].toInt() == 0x20)) end--
            return if (end > from) decodeLegacy(b.copyOfRange(from, end)) else null
        }
        return Tags(field(3), field(33), field(63))
    }

    /** フレームの中身（先頭 1 バイトが文字コードの種類） */
    private fun decodeFrame(b: ByteArray, start: Int, len: Int): String? {
        val enc = b[start].toInt()
        val data = b.copyOfRange(start + 1, start + len)
        val text = when (enc) {
            1 -> String(data, Charsets.UTF_16)
            2 -> String(data, Charsets.UTF_16BE)
            3 -> String(data, Charsets.UTF_8)
            else -> decodeLegacy(data.trimTrailingZero())
        }
        return text?.trimEnd('\u0000')?.takeIf { it.isNotBlank() }
    }

    private fun ByteArray.trimTrailingZero(): ByteArray {
        var end = size
        while (end > 0 && this[end - 1].toInt() == 0) end--
        return copyOf(end)
    }

    /** 文字コードが決まっていないバイト列：UTF-8 → Shift_JIS（日本語が出るとき）→ Latin-1 の順に */
    fun decodeLegacy(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        if (bytes.all { it >= 0 }) return String(bytes, Charsets.US_ASCII)
        decodeStrict(bytes, Charsets.UTF_8)?.let { return it }
        decodeStrict(bytes, sjis)?.let { s -> if (s.any { isJapanese(it) }) return s }
        return String(bytes, Charsets.ISO_8859_1)
    }

    private fun decodeStrict(bytes: ByteArray, charset: Charset?): String? {
        charset ?: return null
        return try {
            charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    fun isJapanese(c: Char): Boolean = when (Character.UnicodeBlock.of(c)) {
        Character.UnicodeBlock.HIRAGANA,
        Character.UnicodeBlock.KATAKANA,
        Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS,
        Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS,
        -> true
        else -> false
    }

    private fun u(b: Byte) = b.toInt() and 0xFF

    private fun synchsafe(b: ByteArray, at: Int) =
        ((u(b[at]) and 0x7F) shl 21) or ((u(b[at + 1]) and 0x7F) shl 14) or ((u(b[at + 2]) and 0x7F) shl 7) or (u(b[at + 3]) and 0x7F)
}
