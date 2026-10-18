package com.ryose.videoplayer

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Process
import androidx.core.content.edit
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * 落ちたときの記録（設定 →「困ったときに」→「落ちたときの記録」で見る）。
 * - Kotlin・Java の例外で落ちた：自分で受け取って、アプリの中のファイル（files/crashes）に書いておく
 * - VLC の中（ネイティブ）で落ちた・固まった（ANR）：アプリでは受け取れないので、Android 11 以降は
 *   OS が覚えている記録（ActivityManager.getHistoricalProcessExitReasons）を読む
 * 記録は端末の中だけに置き、どこにも送らない（ユーザーが「共有」を押したときだけ、選んだアプリに渡す）
 */
object CrashLog {
    data class Record(val time: Long, val kind: String, val summary: String, val detail: String)

    private const val DIR = "crashes"
    /** 残しておく数（古いものから消す） */
    private const val KEEP = 20
    /** 固まった（ANR）ときの記録は全部のスレッドが入っていて長いので、頭のほうだけ読む */
    private const val TRACE_LIMIT = 64 * 1024
    private const val PREFS = "crash_log"
    /** 「すべて消す」を押した日時（OS の記録は消せないので、これより前のものは出さない） */
    private const val KEY_CLEARED_AT = "cleared_at"
    private const val SUMMARY = "内容: "

    /** アプリの起動時に一度だけ呼ぶ（App.onCreate） */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            // 記録に失敗しても、いつもどおり落ちる（OS の「問題が発生しました」など）ようにする
            runCatching { write(app, thread, e) }
            if (previous != null) {
                previous.uncaughtException(thread, e)
            } else {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            }
        }
    }

    private fun write(context: Context, thread: Thread, e: Throwable) {
        val dir = File(context.filesDir, DIR).apply { mkdirs() }
        val now = System.currentTimeMillis()
        val trace = StringWriter().also { e.printStackTrace(PrintWriter(it)) }.toString()
        val text = buildString {
            append(header(context, now))
            append("スレッド: ").append(thread.name).append('\n')
            append(SUMMARY).append(trace.lineSequence().firstOrNull().orEmpty()).append("\n\n")
            append(trace)
        }
        File(dir, "crash-$now.txt").writeText(text)
        dir.listFiles()?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
    }

    private fun header(context: Context, time: Long, versionNote: String = ""): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        return "日時: ${formatTime(time)}\n" +
            "アプリ: Orbit $version$versionNote\n" +
            "Android: ${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）\n" +
            "端末: ${Build.MANUFACTURER} ${Build.MODEL}（${Build.SUPPORTED_ABIS.joinToString()}）\n"
    }

    fun formatTime(time: Long): String = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN).format(Date(time))

    /** 記録の一覧（新しい順）。ファイルを読むので、画面の処理の中では呼ばない */
    fun records(context: Context): List<Record> {
        val list = mutableListOf<Record>()
        File(context.filesDir, DIR).listFiles()?.forEach { f ->
            val time = f.name.removePrefix("crash-").removeSuffix(".txt").toLongOrNull() ?: return@forEach
            val text = runCatching { f.readText() }.getOrNull() ?: return@forEach
            val summary = text.lineSequence().firstOrNull { it.startsWith(SUMMARY) }?.removePrefix(SUMMARY).orEmpty()
            list += Record(time, "アプリが落ちた", summary, text)
        }
        if (Build.VERSION.SDK_INT >= 30) list += systemRecords(context)
        return list.sortedByDescending { it.time }
    }

    /**
     * OS が覚えている、アプリが終わった理由のうち、VLC の中（ネイティブ）で落ちた・固まった（ANR）もの。
     * Kotlin・Java の例外で落ちたもの（REASON_CRASH）は自分のファイルと重なるので出さない
     */
    @androidx.annotation.RequiresApi(30)
    private fun systemRecords(context: Context): List<Record> {
        val am = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val clearedAt = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_CLEARED_AT, 0L)
        val infos = runCatching { am.getHistoricalProcessExitReasons(context.packageName, 0, KEEP) }.getOrNull() ?: return emptyList()
        return infos.filter { it.timestamp > clearedAt }.mapNotNull { info ->
            val kind = when (info.reason) {
                ApplicationExitInfo.REASON_ANR -> "固まった（ANR）"
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "VLC など（ネイティブ）で落ちた"
                else -> return@mapNotNull null
            }
            val summary = info.description?.takeIf { it.isNotBlank() } ?: kind
            val detail = buildString {
                // OS の記録には、そのときのアプリの版が無い
                append(header(context, info.timestamp, "（今の版。落ちたときの版は分からない）"))
                append("種類: ").append(kind).append('\n')
                append(SUMMARY).append(summary).append('\n')
                append("プロセス: ").append(info.processName).append('\n')
                append("メモリ: ").append(info.pss / 1024).append(" MB（PSS）\n")
                // ANR のときは、そのときの全部のスレッドの様子（テキスト）が付いている。
                // ネイティブで落ちたときの記録（tombstone）は Android 12 以降はバイナリなので読まない
                if (info.reason == ApplicationExitInfo.REASON_ANR) {
                    val trace = runCatching {
                        info.traceInputStream?.use { input ->
                            val buf = ByteArray(TRACE_LIMIT)
                            var n = 0
                            while (n < buf.size) {
                                val r = input.read(buf, n, buf.size - n)
                                if (r < 0) break
                                n += r
                            }
                            String(buf, 0, n, Charsets.UTF_8)
                        }
                    }.getOrNull()
                    if (!trace.isNullOrBlank()) append('\n').append(trace)
                }
            }
            Record(info.timestamp, kind, summary, detail)
        }
    }

    /** 記録をすべて消す（OS の記録は消せないので、今より前のものを出さないようにする） */
    fun clear(context: Context) {
        File(context.filesDir, DIR).listFiles()?.forEach { it.delete() }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            putLong(KEY_CLEARED_AT, System.currentTimeMillis())
        }
    }
}
