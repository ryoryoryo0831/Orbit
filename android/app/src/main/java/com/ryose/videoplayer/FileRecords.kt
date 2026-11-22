package com.ryose.videoplayer

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 動画・曲ごとに覚えている記録（キーはファイルの場所など [PlaylistItem.key]）を、まとめて移す・消す・片付ける。
 * 名前の変更・削除・ほかのアプリのアドレスからファイルへの置き換えで、一部の記録だけが取り残されないように、
 * 記録を足したときはここの [STORES] にも足す（1.0.160 までは、名前を変えると速度・明るさ・音声と字幕の選択・A-B が外れていた）
 */
object FileRecords {
    /** 保存場所（SharedPreferences の名前）と、キーの前に付ける印 */
    val STORES: List<Pair<String, List<String>>> = listOf(
        "resume" to listOf(""),            // 続きの位置（ResumeStore）
        "resume_title" to listOf(""),      // DVD の続きのタイトル（ResumeStore）
        "per_video" to listOf("r|", "b|"), // 速度・明るさ（PerVideoStore）
        "track_choice" to listOf("a|", "s|"), // 音声・字幕の選択（TrackChoiceStore）
        "ab_repeat" to listOf(""),         // A-B リピートの区間（AbStore）
    )

    /** 保存のキーから、どの動画の記録か（印を外したもの）。この保存場所の記録でなければ null */
    fun ownerOf(prefKey: String, prefixes: List<String>): String? =
        prefixes.filter { it.isNotEmpty() }.firstOrNull { prefKey.startsWith(it) }?.let { prefKey.removePrefix(it) }
            ?: prefKey.takeIf { "" in prefixes }

    private fun prefs(context: Context, name: String): SharedPreferences =
        context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** [from] の記録を [to] へ移す。[to] にすでに記録があれば、そちらを残す */
    fun move(context: Context, from: String, to: String) {
        if (from == to) return
        for ((name, prefixes) in STORES) {
            val p = prefs(context, name)
            val all = p.all
            if (prefixes.none { all.containsKey(it + from) }) continue
            p.edit {
                for (prefix in prefixes) {
                    val value = all[prefix + from] ?: continue
                    remove(prefix + from)
                    if (!all.containsKey(prefix + to)) putAny(prefix + to, value)
                }
            }
        }
    }

    /** 消したファイルの記録を全部消す */
    fun forget(context: Context, key: String) {
        for ((name, prefixes) in STORES) {
            val p = prefs(context, name)
            if (prefixes.none { p.contains(it + key) }) continue
            p.edit { prefixes.forEach { remove(it + key) } }
        }
    }

    /** [isGone] が true を返す動画の記録を全部消す（消えたファイルの片付け）。消した数を返す */
    fun prune(context: Context, isGone: (String) -> Boolean): Int {
        var count = 0
        for ((name, prefixes) in STORES) {
            val p = prefs(context, name)
            val gone = p.all.keys.filter { k -> ownerOf(k, prefixes)?.let(isGone) == true }
            if (gone.isEmpty()) continue
            p.edit { gone.forEach { remove(it) } }
            count += gone.size
        }
        return count
    }

    private fun SharedPreferences.Editor.putAny(key: String, value: Any) {
        when (value) {
            is Long -> putLong(key, value)
            is Int -> putInt(key, value)
            is Float -> putFloat(key, value)
            is Boolean -> putBoolean(key, value)
            is String -> putString(key, value)
        }
    }
}
