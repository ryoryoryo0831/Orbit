package com.ryose.videoplayer

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 設定・お気に入り・プレイリスト・NAS などの登録・履歴・続きの位置などを、ファイル 1 つに書き出す／読み込む
 * （端末の買い替えや入れ直しのとき用）。
 * NAS などのパスワードは端末の鍵（Android Keystore）で暗号化してあり、ほかの端末では戻せないので書き出さない。
 * 同じ端末に読み込むときは、今あるパスワードを残す
 */
object Backup {
    /** 読み込みは画面を回しても止めないように、画面ではなくアプリの流れで行う */
    val scope = kotlinx.coroutines.MainScope()

    private const val FORMAT = 1
    private const val DEFAULT_PREFS = "<settings>"

    /** 書き出す SharedPreferences（DEFAULT_PREFS は設定画面の設定） */
    private val PREFS = listOf(
        DEFAULT_PREFS, "favorites", "history", "servers", "urls", "player",
        "resume", "resume_title", "ab_repeat", "track_choice", "equalizer",
    )

    /** 書き出すファイル（files の中） */
    private val FILES = listOf("playlists.json")

    /** この端末だけの値（書き出さない・読み込まない） */
    private val SKIP_KEYS = setOf(
        "update_checked_at", "notification_permission_asked", "popup_permission_asked", "tv_hw_decode_restored",
        // お試し版を受け取るのは試す人の端末だけ（スマホから書き出してテレビに読み込んでも、テレビはお試し版にしない）
        "update_prerelease",
        // 自由な小窓の場所・大きさは画面の大きさで変わる（大きさの違う端末に移すと画面の外に出ることがある）
        "popup_x", "popup_y", "popup_size",
    )

    /** 読み込むファイルの大きさの上限（ISO などを間違えて選んだときに、全部読もうとしないように） */
    private const val MAX_BYTES = 20L * 1024 * 1024

    fun fileName(): String = "Orbit-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.JAPAN).format(Date()) + ".json"

    private fun prefsOf(context: Context, name: String) =
        if (name == DEFAULT_PREFS) PreferenceManager.getDefaultSharedPreferences(context)
        else context.getSharedPreferences(name, Context.MODE_PRIVATE)

    /** 書き出す中身（JSON） */
    fun export(context: Context): String {
        val prefs = JSONObject()
        for (name in PREFS) {
            val o = JSONObject()
            for ((key, value) in prefsOf(context, name).all) {
                if (key in SKIP_KEYS || value == null) continue
                val v = if (name == "servers" && key == "list" && value is String) withoutPasswords(value) else value
                o.put(key, encode(v) ?: continue)
            }
            prefs.put(name, o)
        }
        // まだ書いていないプレイリストの変更があれば、書き終わるのを待ってから読む
        FileSaver.flush()
        val files = JSONObject()
        for (name in FILES) {
            val f = File(context.filesDir, name)
            if (f.exists()) files.put(name, f.readText())
        }
        return JSONObject()
            .put("app", "Orbit")
            .put("format", FORMAT)
            .put("version", AppUpdater.versionName(context))
            .put("exportedAt", System.currentTimeMillis())
            .put("prefs", prefs)
            .put("files", files)
            .toString()
    }

    /** 読み込んだ結果 */
    data class Result(val ok: Boolean, val message: String)

    /** 読み込む。今のデータは置き換える（パスワードは同じ端末なら残す）。読み込めたらアプリを起動し直す必要がある */
    fun import(context: Context, text: String): Result {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return Result(false, "Orbit の書き出したファイルではないようです")
        if (root.optString("app") != "Orbit" || root.optInt("format") != FORMAT) {
            return Result(false, "Orbit の書き出したファイルではないようです")
        }
        val prefs = root.optJSONObject("prefs") ?: JSONObject()
        val files = root.optJSONObject("files") ?: JSONObject()
        // 何かを書き換える前に、ファイルの中身が読めるかを確かめる（途中で失敗して、古いデータと新しいデータが混ざらないように）
        for (name in FILES) {
            if (files.has(name) && runCatching { JSONArray(files.getString(name)) }.isFailure) {
                return Result(false, "書き出したファイルが壊れているようです（$name）。今のデータはそのままです")
            }
        }
        // 先に今のプレイリストの書き込みが終わるのを待つ（読み込んだ中身を、古い書き込みで上書きしないように）
        FileSaver.flush()
        // 同じ端末なら、今のパスワード（この端末の鍵で暗号化したもの）を残す
        val oldServers = runCatching { JSONArray(prefsOf(context, "servers").getString("list", "[]")) }.getOrNull()
        var failed = false
        for (name in PREFS) {
            val o = prefs.optJSONObject(name) ?: continue
            val editor = prefsOf(context, name).edit()
            // 設定の中のこの端末だけの値は残し、ほかは置き換える
            for (key in prefsOf(context, name).all.keys) if (key !in SKIP_KEYS) editor.remove(key)
            for (key in o.keys()) {
                val entry = o.optJSONObject(key)
                if (key !in SKIP_KEYS && entry != null) put(editor, name, key, entry, oldServers)
            }
            if (!editor.commit()) {
                failed = true
                PlaybackLog.add("読み込み：$name を保存できませんでした")
            }
        }
        for (name in FILES) {
            if (!files.has(name)) continue
            if (!FileSaver.writeNow(File(context.filesDir, name), files.getString(name))) failed = true
        }
        val from = root.optString("version")
        if (failed) {
            return Result(true, "読み込みましたが、一部を保存できませんでした（端末の空き容量を確かめてください）。アプリを起動し直します。")
        }
        return Result(true,"読み込みました（Orbit $from で書き出したもの）。アプリを起動し直します。")
    }

    /** 書き出した 1 つの値を戻す */
    private fun put(editor: android.content.SharedPreferences.Editor, name: String, key: String, entry: JSONObject, oldServers: JSONArray?) {
        when (entry.optString("t")) {
            "s" -> {
                var value = entry.optString("v")
                if (name == "servers" && key == "list") value = keepPasswords(value, oldServers)
                editor.putString(key, value)
            }
            "b" -> editor.putBoolean(key, entry.optBoolean("v"))
            "i" -> editor.putInt(key, entry.optInt("v"))
            "l" -> editor.putLong(key, entry.optLong("v"))
            "f" -> editor.putFloat(key, entry.optDouble("v").toFloat())
            "ss" -> entry.optJSONArray("v")?.let { arr ->
                editor.putStringSet(key, (0 until arr.length()).map { arr.getString(it) }.toSet())
            }
        }
    }

    private fun encode(value: Any): JSONObject? = when (value) {
        is String -> JSONObject().put("t", "s").put("v", value)
        is Boolean -> JSONObject().put("t", "b").put("v", value)
        is Int -> JSONObject().put("t", "i").put("v", value)
        is Long -> JSONObject().put("t", "l").put("v", value)
        is Float -> JSONObject().put("t", "f").put("v", value.toDouble())
        is Set<*> -> JSONObject().put("t", "ss").put("v", JSONArray(value.filterIsInstance<String>()))
        else -> null
    }

    /** NAS などの登録からパスワードを外す（ほかの端末では戻せないので） */
    private fun withoutPasswords(list: String): String = runCatching {
        val arr = JSONArray(list)
        for (i in 0 until arr.length()) arr.getJSONObject(i).put("password", "")
        arr.toString()
    }.getOrDefault("[]")

    /** 読み込む登録に、今の端末にある同じ登録（id）のパスワードを戻す */
    private fun keepPasswords(list: String, old: JSONArray?): String = runCatching {
        val arr = JSONArray(list)
        if (old == null) return@runCatching list
        val byId = (0 until old.length()).map { old.getJSONObject(it) }.associateBy { it.optString("id") }
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val pw = byId[o.optString("id")]?.optString("password").orEmpty()
            if (pw.isNotEmpty()) o.put("password", pw)
        }
        arr.toString()
    }.getOrDefault(list)

    // ---------- ファイルを選ぶ画面が無い端末（テレビなど）：「ダウンロード」フォルダに置く ----------

    /** 「ダウンロード/Orbit」に書き出す。書き出した場所の説明を返す（できなければ null） */
    fun exportToDownloads(context: Context, text: String): String? {
        if (Build.VERSION.SDK_INT < 29) return null
        val name = fileName()
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Orbit")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) } ?: return null
        return "ダウンロード/Orbit/$name"
    }

    /**
     * 「ダウンロード」にある書き出したファイル（新しい順。名前と場所）。
     * Android 11 以降、MediaStore で見えるのはこのアプリが書いたものだけ（入れ直すと見えなくなる）なので、
     * 「すべてのファイルへのアクセス」が許可されていれば、フォルダを直接も探す
     */
    fun findInDownloads(context: Context): List<Pair<String, Uri>> {
        if (Build.VERSION.SDK_INT < 29) return emptyList()
        val found = mutableListOf<Pair<String, Uri>>()
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val direct = listOf(File(dir, "Orbit"), dir).flatMap { d ->
            d.listFiles { f -> f.isFile && f.name.startsWith("Orbit-backup-") && f.canRead() }?.toList().orEmpty()
        }.sortedByDescending { it.lastModified() }
        direct.forEach { found += it.name to Uri.fromFile(it) }
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME),
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?", arrayOf("Orbit-backup-%"),
            "${MediaStore.Downloads.DATE_ADDED} DESC",
        )?.use { c ->
            while (c.moveToNext()) {
                val name = c.getString(1)
                if (found.none { it.first == name }) {
                    found += name to android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0))
                }
            }
        }
        return found
    }

    /** 読む。大きすぎる（[MAX_BYTES] を超える）・読めないときは null */
    fun readText(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val bytes = input.readNBytesCompat(MAX_BYTES + 1)
            if (bytes.size > MAX_BYTES) null else String(bytes, Charsets.UTF_8)
        }
    }.getOrNull()

    private fun java.io.InputStream.readNBytesCompat(limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (out.size() <= limit) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun writeText(context: Context, uri: Uri, text: String): Boolean {
        val bytes = text.toByteArray()
        // "wt"（中身を消して書く）に対応していない保存先もあるので、だめなら "w" で
        for (mode in listOf("wt", "w")) {
            val ok = runCatching { context.contentResolver.openOutputStream(uri, mode)?.use { it.write(bytes) } != null }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    /** 読み込んだあと、アプリを起動し直す（覚えている中身を読み直すため。新しいプロセスで最初の画面から開く） */
    fun restartApp(context: Context) {
        context.stopService(Intent(context, PlaybackService::class.java))
        val intent = Intent(context, MainActivity::class.java)
        context.startActivity(Intent.makeRestartActivityTask(intent.component))
        Runtime.getRuntime().exit(0)
    }
}
