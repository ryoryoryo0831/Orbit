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
                // 大きくなったら、ファイルの新しい半分だけを残す（毎回書き直さないように、上限よりずっと小さくする）
                if (f.length() > MAX_FILE_BYTES) {
                    val keep = f.readLines().takeLast(MAX_LINES / 2)
                    f.writeText(keep.joinToString("\n", postfix = "\n"))
                }
            }
        }
    }

    /** 直前に [addOnce] で足したもの */
    private var lastOnce: String? = null

    /**
     * 直前と同じ内容なら足さない（1 秒ごとに見直す処理などで、うまくいかないときに同じ行が増え続けないように）
     */
    fun addOnce(message: String) {
        synchronized(lines) {
            if (message == lastOnce) return
            lastOnce = message
        }
        add(message)
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
