package com.ryose.videoplayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileRecordsTest {
    private fun prefixesOf(name: String) = FileRecords.STORES.first { it.first == name }.second

    @Test
    fun 印の付いた保存場所は印を外した動画の場所を返す() {
        val p = prefixesOf("per_video")
        assertEquals("/storage/emulated/0/a.mp4", FileRecords.ownerOf("r|/storage/emulated/0/a.mp4", p))
        assertEquals("/storage/emulated/0/a.mp4", FileRecords.ownerOf("b|/storage/emulated/0/a.mp4", p))
        assertNull(FileRecords.ownerOf("x|/storage/emulated/0/a.mp4", p))
    }

    @Test
    fun 印の無い保存場所はキーそのもの() {
        assertEquals("/storage/emulated/0/a.mp4", FileRecords.ownerOf("/storage/emulated/0/a.mp4", prefixesOf("resume")))
        assertEquals("/storage/emulated/0/a.mp4", FileRecords.ownerOf("/storage/emulated/0/a.mp4", prefixesOf("ab_repeat")))
    }

    @Test
    fun 動画ごとの記録の保存場所をすべて含む() {
        val names = FileRecords.STORES.map { it.first }.toSet()
        assertEquals(setOf("resume", "resume_title", "per_video", "track_choice", "ab_repeat"), names)
    }
}
