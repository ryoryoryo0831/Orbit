package com.ryose.videoplayer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 曲のファイルから直接読んだタグ（[TagText.fixTags]）を覚えておく。ファイルの更新日時と大きさが同じなら読み直さない。
 * 1.0.160 までは「音楽」タブを読み込むたびに（曲が 1 つ増えただけでも）全曲のファイルを開いてタグを読み直していた。
 * 中身は cacheDir に置く（消されても作り直すだけ。書き出し・読み込みには含めない）
 */
object TagCache {
    private class Entry(val modified: Long, val size: Long, val tags: Id3Tags.Tags?)

    private val lock = Any()
    private val map = HashMap<String, Entry>()
    private var loaded = false
    private var dirty = false

    private fun file(context: Context) = File(context.applicationContext.cacheDir, "tag_cache.json")

    /** 覚えているものを読み込む（初めの 1 回だけ。裏のスレッドで呼ぶ） */
    fun load(context: Context) {
        synchronized(lock) {
            if (loaded) return
            loaded = true
        }
        val o = runCatching { JSONObject(file(context).readText()) }.getOrNull() ?: return
        synchronized(lock) {
            for (path in o.keys()) {
                val a = o.optJSONArray(path) ?: continue
                fun str(i: Int) = if (a.isNull(i)) null else a.optString(i)
                val tags = if (a.length() > 2 && !a.isNull(2)) Id3Tags.Tags(str(2), str(3), str(4)) else null
                map.putIfAbsent(path, Entry(a.optLong(0), a.optLong(1), tags))
            }
        }
    }

    /** ファイルのタグ。前に読んだときから変わっていなければ、覚えているものを返す（裏のスレッドで呼ぶ） */
    fun read(path: String): Id3Tags.Tags? {
        val f = File(path)
        val modified = f.lastModified()
        val size = f.length()
        if (modified > 0) {
            synchronized(lock) {
                map[path]?.takeIf { it.modified == modified && it.size == size }?.let { return it.tags }
            }
        }
        val tags = AudioTags.read(path)
        if (modified > 0) synchronized(lock) {
            map[path] = Entry(modified, size, tags)
            dirty = true
        }
        return tags
    }

    /** 覚えたものを保存する。[keep] があれば、それ以外（消えた曲）は忘れる */
    fun save(context: Context, keep: Set<String>? = null) {
        val text = synchronized(lock) {
            if (keep != null && map.keys.retainAll(keep)) dirty = true
            if (!dirty) return
            dirty = false
            val o = JSONObject()
            for ((path, e) in map) {
                val a = JSONArray().put(e.modified).put(e.size)
                e.tags?.let { t ->
                    a.put(t.title ?: JSONObject.NULL).put(t.artist ?: JSONObject.NULL).put(t.album ?: JSONObject.NULL)
                } ?: a.put(JSONObject.NULL)
                o.put(path, a)
            }
            o.toString()
        }
        FileSaver.save(file(context)) { text }
    }
}
