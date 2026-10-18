package com.ryose.videoplayer

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 再生の記録（不具合の手がかり）。開いた・音声や字幕を選んだ（なぜ選んだか）・DVD のタイトルが変わった・エラーなどを、
 * 新しいものから [MAX_LINES] 行まで端末の中（files/playback_log.txt）に残す。どこにも送らない。
 * 設定の「困ったときに」→「再生の記録」から見る・コピー・共有できる（PlaybackLogScreen）。
 * スマホは adb でつないでいないので、起きたことを確かめるにはこれを送ってもらう
 */
object PlaybackLog {
    private const val MAX_LINES = 2000
    /** ファイルがこれより大きくなったら、新しい [MAX_LINES] 行だけにして書き直す */
    private const val MAX_FILE_BYTES = 400 * 1024L

    private val lines = ArrayDeque<String>()
    private var file: File? = null
    private val writer = Executors.newSingleThreadExecutor()
    private val timeFormat = SimpleDateFormat("MM/dd HH:mm:ss.SSS", Locale.JAPAN)

    /** アプリの起動のはじめ（App.onCreate）に呼ぶ。前回までの記録は裏で読む */
    fun init(context: Context) {
        val f = File(context.applicationContext.filesDir, "playback_log.txt")
        file = f
        writer.execute {
            val old = runCatching { if (f.exists()) f.readLines() else emptyList() }.getOrDefault(emptyList())
            synchronized(lines) {
                // 読んでいる間に足された行は後ろに残す
                val added = lines.toList()
                lines.clear()
                old.takeLast(MAX_LINES).forEach { lines.addLast(it) }
                added.forEach { lines.addLast(it) }
                while (lines.size > MAX_LINES) lines.removeFirst()
            }
        }
    }

    /** 1 行足す（どのスレッドからでもよい） */
    fun add(message: String) {
        val line = synchronized(timeFormat) { timeFormat.format(Date()) } + "  " + message.replace('\n', ' ')
        synchronized(lines) {
            lines.addLast(line)
            while (lines.size > MAX_LINES) lines.removeFirst()
        }
        val f = file ?: return
        writer.execute {
            runCatching {
                f.appendText(line + "\n")
                if (f.length() > MAX_FILE_BYTES) f.writeText(snapshot(MAX_LINES).joinToString("\n", postfix = "\n"))
            }
        }
    }

    /** 新しい [max] 行（古い順） */
    fun snapshot(max: Int = MAX_LINES): List<String> = synchronized(lines) { lines.toList().takeLast(max) }

    fun clear() {
        synchronized(lines) { lines.clear() }
        val f = file ?: return
        writer.execute { runCatching { f.delete() } }
    }

    /** 記録に書く時間（「12:34」「1:02:03」） */
    fun time(ms: Long): String = formatTime(ms)
}
