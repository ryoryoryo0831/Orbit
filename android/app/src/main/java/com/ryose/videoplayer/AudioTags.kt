package com.ryose.videoplayer

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 曲のタグ（タイトル・アーティスト・アルバム）を、文字コードを変換する前の生のバイトで読む。
 * 端末が読んだ文字列が化けて見える曲を、ファイルから読み直して直すために使う。
 * MP3（ID3）・m4a/mp4・FLAC・Ogg（Vorbis/Opus）・WMA に対応。見つからなければ null
 */
object AudioTags {
    fun read(path: String): Id3Tags.Tags? = runCatching {
        // ID3 は MP3 以外（FLAC・AAC・WAV など）の先頭にも付くことがある
        Id3Tags.read(path)?.let { return@runCatching it }
        when (File(path).extension.lowercase()) {
            "m4a", "m4b", "mp4", "m4v", "aac", "3gp", "3g2" -> readMp4(path)
            "flac" -> readFlac(path)
            "ogg", "oga", "opus" -> readOgg(path)
            "wma", "asf" -> readAsf(path)
            else -> null
        }
    }.getOrNull()

    // ---------- m4a / mp4 ----------

    private fun readMp4(path: String): Id3Tags.Tags? = RandomAccessFile(path, "r").use { f ->
        // 先頭から箱（size, type）をたどって moov を探す
        var pos = 0L
        val len = f.length()
        while (pos + 8 <= len) {
            f.seek(pos)
            val h = ByteArray(16)
            val n = f.read(h)
            if (n < 8) break
            var size = u32(h, 0)
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var headLen = 8
            if (size == 1L && n >= 16) {
                size = ByteBuffer.wrap(h, 8, 8).long
                headLen = 16
            } else if (size == 0L) size = len - pos
            if (size < headLen) break
            if (type == "moov") {
                val bodyLen = size - headLen
                if (bodyLen > 16_000_000) return null
                val body = ByteArray(bodyLen.toInt())
                f.seek(pos + headLen)
                f.readFully(body)
                return parseMp4(body, 0, body.size)
            }
            pos += size
        }
        null
    }

    private fun parseMp4(b: ByteArray, from: Int, to: Int): Id3Tags.Tags? {
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        fun walk(start: Int, end: Int) {
            var p = start
            while (p + 8 <= end) {
                val size = u32(b, p).toInt()
                if (size < 8 || p + size > end) return
                val type = String(b, p + 4, 4, Charsets.ISO_8859_1)
                when (type) {
                    "moov", "udta", "ilst" -> walk(p + 8, p + size)
                    "meta" -> walk(p + 12, p + size) // meta は版・フラグの 4 バイトが先に付く
                    "©nam", "©ART", "©alb" -> {
                        val text = mp4Data(b, p + 8, p + size)
                        when (type) {
                            "©nam" -> title = text
                            "©ART" -> artist = text
                            else -> album = text
                        }
                    }
                }
                p += size
            }
        }
        walk(from, to)
        return if (title == null && artist == null && album == null) null else Id3Tags.Tags(title, artist, album)
    }

    /** 項目の中の data 箱（版・種類 4 バイト、場所 4 バイトのあとに文字） */
    private fun mp4Data(b: ByteArray, start: Int, end: Int): String? {
        var p = start
        while (p + 16 <= end) {
            val size = u32(b, p).toInt()
            if (size < 16 || p + size > end) return null
            if (String(b, p + 4, 4, Charsets.ISO_8859_1) == "data") {
                val type = u32(b, p + 8).toInt() and 0xFFFFFF
                val bytes = b.copyOfRange(p + 16, p + size)
                return if (type == 2) String(bytes, Charsets.UTF_16BE).takeIf { it.isNotBlank() }
                else Id3Tags.decodeLegacy(bytes)?.takeIf { it.isNotBlank() }
            }
            p += size
        }
        return null
    }

    // ---------- FLAC ----------

    private fun readFlac(path: String): Id3Tags.Tags? = RandomAccessFile(path, "r").use { f ->
        f.seek(0)
        val first = ByteArray(10)
        f.readFully(first)
        var pos = 0L
        // 先頭に ID3 が付いているものは、その後ろから
        if (first[0] == 'I'.code.toByte() && first[1] == 'D'.code.toByte() && first[2] == '3'.code.toByte()) {
            pos = 10L + (((first[6].toInt() and 0x7F) shl 21) or ((first[7].toInt() and 0x7F) shl 14) or
                ((first[8].toInt() and 0x7F) shl 7) or (first[9].toInt() and 0x7F))
        }
        f.seek(pos)
        val magic = ByteArray(4)
        f.readFully(magic)
        if (String(magic, Charsets.ISO_8859_1) != "fLaC") return null
        pos += 4
        while (pos + 4 <= f.length()) {
            f.seek(pos)
            val h = ByteArray(4)
            f.readFully(h)
            val last = h[0].toInt() and 0x80 != 0
            val type = h[0].toInt() and 0x7F
            val len = ((h[1].toInt() and 0xFF) shl 16) or ((h[2].toInt() and 0xFF) shl 8) or (h[3].toInt() and 0xFF)
            if (type == 4) {
                if (len > 8_000_000) return null
                val body = ByteArray(len)
                f.readFully(body)
                return parseVorbisComment(body, 0)
            }
            if (last) break
            pos += 4 + len
        }
        null
    }

    // ---------- Ogg（Vorbis / Opus） ----------

    private fun readOgg(path: String): Id3Tags.Tags? = RandomAccessFile(path, "r").use { f ->
        val buf = ByteArray(minOf(f.length(), 262_144L).toInt())
        f.readFully(buf)
        val vorbis = indexOf(buf, byteArrayOf(3, 'v'.code.toByte(), 'o'.code.toByte(), 'r'.code.toByte(), 'b'.code.toByte(), 'i'.code.toByte(), 's'.code.toByte()))
        if (vorbis >= 0) return parseVorbisComment(buf, vorbis + 7)
        val opus = indexOf(buf, "OpusTags".toByteArray(Charsets.ISO_8859_1))
        if (opus >= 0) return parseVorbisComment(buf, opus + 8)
        null
    }

    private fun indexOf(b: ByteArray, key: ByteArray): Int {
        outer@ for (i in 0..b.size - key.size) {
            for (j in key.indices) if (b[i + j] != key[j]) continue@outer
            return i
        }
        return -1
    }

    /** Vorbis コメント（ベンダー名、件数、"KEY=値" の並び。長さはどれも 4 バイトのリトルエンディアン） */
    private fun parseVorbisComment(b: ByteArray, from: Int): Id3Tags.Tags? {
        var p = from
        fun int(): Int {
            if (p + 4 > b.size) return -1
            val v = ByteBuffer.wrap(b, p, 4).order(ByteOrder.LITTLE_ENDIAN).int
            p += 4
            return v
        }
        val vendor = int()
        if (vendor < 0 || p + vendor > b.size) return null
        p += vendor
        val count = int()
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var i = 0
        while (i < count && i < 500) {
            val len = int()
            if (len < 0 || p + len > b.size) break
            val entry = b.copyOfRange(p, p + len)
            p += len
            val eq = entry.indexOf('='.code.toByte())
            if (eq > 0) {
                val key = String(entry, 0, eq, Charsets.ISO_8859_1).uppercase()
                if (key == "TITLE" || key == "ARTIST" || key == "ALBUM") {
                    val value = Id3Tags.decodeLegacy(entry.copyOfRange(eq + 1, entry.size))?.takeIf { it.isNotBlank() }
                    when (key) {
                        "TITLE" -> title = value
                        "ARTIST" -> artist = value
                        else -> album = value
                    }
                }
            }
            i++
        }
        return if (title == null && artist == null && album == null) null else Id3Tags.Tags(title, artist, album)
    }

    // ---------- WMA（ASF） ----------

    private val CONTENT_DESCRIPTION = guid("75B22633-668E-11CF-A6D9-00AA0062CE6C")
    private val EXTENDED_CONTENT = guid("D2D0A440-E307-11D2-97F0-00A0C95EA850")

    private fun readAsf(path: String): Id3Tags.Tags? = RandomAccessFile(path, "r").use { f ->
        val buf = ByteArray(minOf(f.length(), 1_048_576L).toInt())
        f.readFully(buf)
        var title: String? = null
        var artist: String? = null
        var album: String? = null
        var p = 30 // ヘッダー（GUID 16、大きさ 8、個数 4、予約 2）
        while (p + 24 <= buf.size) {
            val id = buf.copyOfRange(p, p + 16)
            val size = ByteBuffer.wrap(buf, p + 16, 8).order(ByteOrder.LITTLE_ENDIAN).long
            if (size < 24 || p + size > buf.size) break
            val body = p + 24
            if (id.contentEquals(CONTENT_DESCRIPTION)) {
                val bb = ByteBuffer.wrap(buf, body, 10).order(ByteOrder.LITTLE_ENDIAN)
                val titleLen = bb.short.toInt() and 0xFFFF
                val authorLen = bb.short.toInt() and 0xFFFF
                title = utf16(buf, body + 10, titleLen)
                artist = utf16(buf, body + 10 + titleLen, authorLen)
            } else if (id.contentEquals(EXTENDED_CONTENT)) {
                var q = body + 2
                val count = ByteBuffer.wrap(buf, body, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                var i = 0
                while (i < count && q + 2 <= p + size) {
                    val nameLen = le16(buf, q)
                    val name = utf16(buf, q + 2, nameLen)
                    val type = le16(buf, q + 2 + nameLen)
                    val valueLen = le16(buf, q + 4 + nameLen)
                    if (type == 0 && name == "WM/AlbumTitle") album = utf16(buf, q + 6 + nameLen, valueLen)
                    q += 6 + nameLen + valueLen
                    i++
                }
            }
            p += size.toInt()
        }
        if (title == null && artist == null && album == null) null else Id3Tags.Tags(title, artist, album)
    }

    private fun utf16(b: ByteArray, from: Int, len: Int): String? {
        if (len <= 0 || from + len > b.size) return null
        return String(b, from, len, Charsets.UTF_16LE).trimEnd('\u0000').takeIf { it.isNotBlank() }
    }

    private fun le16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    /** GUID の文字列を、ファイルに並ぶバイトの順（前の 3 つのかたまりは逆順）に */
    private fun guid(s: String): ByteArray {
        val h = s.replace("-", "")
        fun byte(i: Int) = h.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        return ByteArray(16).also { out ->
            intArrayOf(3, 2, 1, 0, 5, 4, 7, 6, 8, 9, 10, 11, 12, 13, 14, 15).forEachIndexed { i, src -> out[i] = byte(src) }
        }
    }
}
