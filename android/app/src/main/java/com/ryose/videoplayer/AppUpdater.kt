package com.ryose.videoplayer

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 新しい版の知らせと更新（ストアに出さないので、GitHub の Releases の最新版を見に行く）。
 * 起動したとき（12 時間に 1 回まで）と、設定の「アップデートを確認」から確かめる。
 * 「更新する」でこの端末に合う APK（テレビは 32 ビット版）をダウンロードし、Android のインストールの画面を出す。
 * 同じ鍵で署名してあるので上書きでき、お気に入りや設定は消えない。どの版にするかの判断は UpdateRules
 */
object AppUpdater {
    private const val LATEST_URL = "https://api.github.com/repos/ryoryoryo0831/video-player/releases/latest"

    /** 起動したとき：12 時間に 1 回まで確かめ、新しい版があれば知らせる（無ければ何も出さない） */
    fun checkOnStart(activity: AppCompatActivity) {
        if (!AppSettings.updateCheckOnStart(activity)) return
        val now = System.currentTimeMillis()
        if (!UpdateRules.shouldCheck(AppSettings.updateCheckedAt(activity), now)) return
        AppSettings.setUpdateCheckedAt(activity, now)
        activity.lifecycleScope.launch {
            val update = runCatching { fetchLatest() }.getOrNull() ?: return@launch
            if (UpdateRules.isNewer(update, currentVersionCode(activity)) && !activity.isFinishing) offer(activity, update)
        }
    }

    /** 設定の「アップデートを確認」：結果をいつも知らせる */
    fun checkNow(activity: AppCompatActivity) {
        AppSettings.setUpdateCheckedAt(activity, System.currentTimeMillis())
        Toast.makeText(activity, "新しい版を確かめています…", Toast.LENGTH_SHORT).show()
        activity.lifecycleScope.launch {
            val result = runCatching { fetchLatest() }
            if (activity.isFinishing) return@launch
            val update = result.getOrNull()
            when {
                result.isFailure -> message(activity, "確かめられませんでした", "インターネットにつながっているか確かめてください。\n（${result.exceptionOrNull()?.message.orEmpty()}）")
                update == null -> message(activity, "確かめられませんでした", "この端末に合う版が見つかりませんでした。")
                UpdateRules.isNewer(update, currentVersionCode(activity)) -> offer(activity, update)
                else -> message(activity, "最新の版です", "今の版（${versionName(activity)}）がいちばん新しい版です。")
            }
        }
    }

    fun currentVersionCode(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    fun versionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    /** アプリが 64 ビットで動いているか（テレビは CPU が 64 ビットでも 32 ビット版で動いている） */
    private fun is64Bit(): Boolean = android.os.Process.is64Bit()

    /** GitHub の最新の公開版を調べる（裏で） */
    private suspend fun fetchLatest(): UpdateRules.Update? = withContext(Dispatchers.IO) {
        val conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Orbit")
        }
        try {
            if (conn.responseCode != 200) throw IllegalStateException("GitHub の返事 ${conn.responseCode}")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val arr = json.optJSONArray("assets")
            val assets = (0 until (arr?.length() ?: 0)).map { i ->
                val a = arr!!.getJSONObject(i)
                UpdateRules.Asset(a.optString("name"), a.optString("browser_download_url"), a.optLong("size"))
            }
            UpdateRules.pick(json.optString("tag_name"), assets, is64Bit())
        } finally {
            conn.disconnect()
        }
    }

    private fun message(activity: AppCompatActivity, title: String, text: String) {
        MaterialAlertDialogBuilder(activity).setTitle(title).setMessage(text).setPositiveButton("閉じる", null).show()
    }

    /** 新しい版があることを知らせる */
    private fun offer(activity: AppCompatActivity, update: UpdateRules.Update) {
        val mb = update.apkSize / (1024 * 1024)
        MaterialAlertDialogBuilder(activity)
            .setTitle("新しい版があります")
            .setMessage(
                "Orbit ${update.versionName} を入れられます（今の版：${versionName(activity)}）。\n\n" +
                    "ダウンロードは約 $mb MB です。お気に入りや設定はそのまま残ります。",
            )
            .setPositiveButton("更新する") { _, _ -> download(activity, update) }
            .setNegativeButton("あとで", null)
            .show()
    }

    /** ダウンロードした APK の置き場所（アプリのキャッシュ。file_paths.xml の update） */
    private fun apkFile(context: Context, update: UpdateRules.Update) = File(File(context.cacheDir, "update"), update.apkName)

    private fun download(activity: AppCompatActivity, update: UpdateRules.Update) {
        val file = apkFile(activity, update)
        // 前にダウンロードし終えていれば（許可を出しに行って戻ってきたときなど）、そのまま使う
        if (file.exists() && file.length() == update.apkSize) {
            install(activity, file)
            return
        }
        val dp = activity.resources.displayMetrics.density
        val bar = LinearProgressIndicator(activity).apply { max = 100; isIndeterminate = true }
        val label = TextView(activity).apply { text = "ダウンロードしています…" }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (24 * dp).toInt()
            setPadding(pad, (8 * dp).toInt(), pad, 0)
            addView(label)
            addView(bar)
        }
        var job: Job? = null
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Orbit ${update.versionName}")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("キャンセル") { _, _ -> job?.cancel() }
            .show()
        job = activity.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    fetchApk(update, file) { percent ->
                        bar.post {
                            bar.isIndeterminate = false
                            bar.setProgressCompat(percent, true)
                            label.text = "ダウンロードしています… $percent%"
                        }
                    }
                }
            }
            dialog.dismiss()
            val error = result.exceptionOrNull()
            when {
                error is CancellationException -> file.delete()
                error != null -> {
                    file.delete()
                    message(activity, "ダウンロードできませんでした", "インターネットにつながっているか確かめてください。\n（${error.message.orEmpty()}）")
                }
                else -> install(activity, file)
            }
        }
    }

    /** APK をダウンロードする（GitHub の置き場所へ転送されるのを追いかける） */
    private suspend fun fetchApk(update: UpdateRules.Update, file: File, onProgress: (Int) -> Unit) {
        file.parentFile?.let { dir ->
            dir.mkdirs()
            // 前の版の APK は消しておく
            dir.listFiles()?.filter { it.name != file.name }?.forEach { it.delete() }
        }
        val tmp = File(file.path + ".part")
        val conn = (URL(update.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "Orbit")
        }
        try {
            if (conn.responseCode != 200) throw IllegalStateException("サーバーの返事 ${conn.responseCode}")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: update.apkSize
            var done = 0L
            var lastPercent = -1
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val percent = if (total > 0) (done * 100 / total).toInt().coerceIn(0, 100) else 0
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }
            if (update.apkSize > 0 && tmp.length() != update.apkSize) throw IllegalStateException("ダウンロードが途中で切れました")
            if (!tmp.renameTo(file)) throw IllegalStateException("保存できませんでした")
        } finally {
            conn.disconnect()
            tmp.delete()
        }
    }

    /** Android のインストールの画面を出す。初めては「このアプリからのインストール」の許可が要る */
    private fun install(activity: AppCompatActivity, file: File) {
        if (!activity.packageManager.canRequestPackageInstalls()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("インストールの許可が要ります")
                .setMessage(
                    "Orbit から新しい版を入れるには、「不明なアプリのインストール」で Orbit を許可してください。" +
                        "許可したら、もう一度「更新する」を押してください（ダウンロードはやり直しません）。",
                )
                .setPositiveButton("許可しに行く") { _, _ ->
                    try {
                        activity.startActivity(
                            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${activity.packageName}".toUri()),
                        )
                    } catch (_: ActivityNotFoundException) {
                        // テレビなど、この設定の画面が無い端末
                        message(
                            activity, "設定の画面を開けませんでした",
                            "端末の設定の「開発者オプション」や「セキュリティ」にある「不明なアプリのインストール」で、Orbit を許可してください。",
                        )
                    }
                }
                .setNegativeButton("キャンセル", null)
                .show()
            return
        }
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            message(activity, "インストールの画面を開けませんでした", "GitHub の Releases から Orbit ${file.name} を入れてください。")
        }
    }
}
