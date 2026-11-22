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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 新しい版の知らせと更新（ストアに出さないので、GitHub の Releases の最新版を見に行く）。
 * ふつうは latest（確かめてからみんなに配った版）だけ、設定「お試し版も受け取る」ならお試し版（プレリリース）も。
 * 起動したとき（12 時間に 1 回まで）と、設定の「アップデートを確認」から確かめる。
 * 「更新する」でこの端末に合う APK（テレビは 32 ビット版）をダウンロードし、Android のインストールの画面を出す。
 * 同じ鍵で署名してあるので上書きでき、お気に入りや設定は消えない。どの版にするかの判断は UpdateRules
 */
object AppUpdater {
    private const val LATEST_URL = "https://api.github.com/repos/ryoryoryo0831/Orbit/releases/latest"

    /**
     * お試し版も調べるときの一覧。GitHub の並びは新しい順とは限らない（1.0.141 は 13 番目に来た）ので、
     * 1 ページ 100 件で、最後のページ（[LIST_MAX_PAGES] ページまで）までめくって全部から選ぶ。iPhone 版も混ざる
     */
    private const val LIST_URL = "https://api.github.com/repos/ryoryoryo0831/Orbit/releases?per_page=100&page="
    private const val LIST_MAX_PAGES = 5

    /** 起動したとき：12 時間に 1 回まで確かめ、新しい版があれば知らせる（無ければ何も出さない） */
    fun checkOnStart(activity: AppCompatActivity) {
        if (!AppSettings.updateCheckOnStart(activity)) return
        val now = System.currentTimeMillis()
        if (!UpdateRules.shouldCheck(AppSettings.updateCheckedAt(activity), now)) return
        activity.lifecycleScope.launch {
            val update = try {
                fetchLatest(AppSettings.updateIncludePrerelease(activity))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // 確かめられなかった（テレビの電源を入れた直後でまだつながっていない、など）：次に開いたときにまた確かめる
                return@launch
            }
            AppSettings.setUpdateCheckedAt(activity, now)
            if (update != null && UpdateRules.isNewer(update, currentVersionCode(activity)) && alive(activity)) offer(activity, update)
        }
    }

    /** 設定の「アップデートを確認」：結果をいつも知らせる */
    fun checkNow(activity: AppCompatActivity) {
        AppSettings.setUpdateCheckedAt(activity, System.currentTimeMillis())
        Toast.makeText(activity, "新しい版を確かめています…", Toast.LENGTH_SHORT).show()
        val includePrerelease = AppSettings.updateIncludePrerelease(activity)
        activity.lifecycleScope.launch {
            val result = try {
                Result.success(fetchLatest(includePrerelease))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (!alive(activity)) return@launch
            val update = result.getOrNull()
            when {
                result.isFailure -> message(activity, "確かめられませんでした", "インターネットにつながっているか確かめてください。\n（${result.exceptionOrNull()?.message.orEmpty()}）")
                update == null -> message(activity, "確かめられませんでした", "この端末に合う版が見つかりませんでした。")
                UpdateRules.isNewer(update, currentVersionCode(activity)) -> offer(activity, update)
                else -> message(
                    activity, "最新の版です",
                    "今の版（${versionName(activity)}）がいちばん新しい版です" + (if (includePrerelease) "（お試し版も含めて）" else "") + "。",
                )
            }
        }
    }

    /** ダイアログを出してよいか（閉じられた・作り直された画面には出さない） */
    private fun alive(activity: AppCompatActivity) = !activity.isFinishing && !activity.isDestroyed

    fun currentVersionCode(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    fun versionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty()

    /** アプリが 64 ビットで動いているか（テレビは CPU が 64 ビットでも 32 ビット版で動いている） */
    private fun is64Bit(): Boolean = android.os.Process.is64Bit()

    /** GitHub のいちばん新しい版を調べる（裏で）。[includePrerelease] ならお試し版も */
    private suspend fun fetchLatest(includePrerelease: Boolean): UpdateRules.Update? = withContext(Dispatchers.IO) {
        if (includePrerelease) {
            val releases = mutableListOf<UpdateRules.Release>()
            for (page in 1..LIST_MAX_PAGES) {
                val arr = JSONArray(getJson(LIST_URL + page))
                for (i in 0 until arr.length()) {
                    val r = arr.getJSONObject(i)
                    releases += UpdateRules.Release(r.optString("tag_name"), r.optBoolean("prerelease"), r.optBoolean("draft"), assetsOf(r))
                }
                if (arr.length() < 100) break
            }
            UpdateRules.newest(releases, includePrerelease = true, is64Bit = is64Bit())
        } else {
            // latest はお試し版にならない（GitHub がそう決めている）
            val json = JSONObject(getJson(LATEST_URL))
            UpdateRules.pick(json.optString("tag_name"), assetsOf(json), is64Bit())
        }
    }

    private fun getJson(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Orbit")
        }
        try {
            if (conn.responseCode != 200) throw IllegalStateException("GitHub の返事 ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun assetsOf(release: JSONObject): List<UpdateRules.Asset> {
        val arr = release.optJSONArray("assets") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val a = arr.getJSONObject(i)
            UpdateRules.Asset(
                a.optString("name"), a.optString("browser_download_url"), a.optLong("size"),
                UpdateRules.sha256Of(a.optString("digest")),
            )
        }
    }

    private fun message(activity: AppCompatActivity, title: String, text: String) {
        MaterialAlertDialogBuilder(activity).setTitle(title).setMessage(text).setPositiveButton("閉じる", null).show()
    }

    /** 新しい版があることを知らせる */
    private fun offer(activity: AppCompatActivity, update: UpdateRules.Update) {
        val mb = update.apkSize / (1024 * 1024)
        MaterialAlertDialogBuilder(activity)
            .setTitle(if (update.prerelease) "新しいお試し版があります" else "新しい版があります")
            .setMessage(
                "Orbit ${update.versionName}${if (update.prerelease) "（お試し版）" else ""} を入れられます（今の版：${versionName(activity)}）。\n\n" +
                    (if (update.prerelease) "まだみんなには配っていない版です。\n" else "") +
                    "ダウンロードは約 $mb MB です。お気に入りや設定はそのまま残ります。",
            )
            .setPositiveButton("更新する") { _, _ -> download(activity, update) }
            .setNegativeButton("あとで", null)
            .show()
    }

    /** ダウンロードした APK の置き場所（アプリのキャッシュ。file_paths.xml の update） */
    private fun apkFile(context: Context, update: UpdateRules.Update) = File(File(context.cacheDir, "update"), update.apkName)

    /** ダウンロードの進み具合（アプリ全体で 1 つ。画面を回して作り直しても止めずに続ける） */
    private sealed interface DownloadState {
        /** [percent] が -1 のときは、まだ分からない */
        data class Running(val update: UpdateRules.Update, val percent: Int) : DownloadState
        /** 終わった。[error] が null なら成功で [file] を入れる */
        data class Done(val update: UpdateRules.Update, val file: File?, val error: String?) : DownloadState
    }

    private val downloadState = MutableStateFlow<DownloadState?>(null)
    private var downloadJob: Job? = null
    // 画面ではなくアプリ全体で動かす（1.0.160 までは画面に結び付いていて、回すとダウンロードが黙って止まっていた）
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** 進み具合を出している画面（同じ画面に二重に出さない） */
    private val showingIn = java.util.Collections.newSetFromMap(java.util.WeakHashMap<AppCompatActivity, Boolean>())

    /** 画面が表に出たときに呼ぶ：ダウンロード中なら進み具合を出し直し、終わっていればインストールへ進む */
    fun attach(activity: AppCompatActivity) {
        if (downloadState.value != null) showProgress(activity)
    }

    private fun download(activity: AppCompatActivity, update: UpdateRules.Update) {
        if (downloadJob?.isActive == true) {
            Toast.makeText(activity, "ダウンロードしています。少し待ってください", Toast.LENGTH_SHORT).show()
            return
        }
        val file = apkFile(activity, update)
        // 前にダウンロードし終えていれば（許可を出しに行って戻ってきたときなど）、そのまま使う
        if (file.exists() && file.length() == update.apkSize) {
            install(activity, file)
            return
        }
        downloadState.value = DownloadState.Running(update, -1)
        downloadJob = appScope.launch {
            downloadState.value = try {
                withContext(Dispatchers.IO) {
                    fetchApk(update, file) { percent -> downloadState.value = DownloadState.Running(update, percent) }
                }
                DownloadState.Done(update, file, null)
            } catch (e: CancellationException) {
                // キャンセルを押した
                file.delete()
                downloadState.value = null
                throw e
            } catch (e: Exception) {
                file.delete()
                PlaybackLog.add("更新をダウンロードできなかった：${e.javaClass.simpleName}: ${e.message}")
                DownloadState.Done(update, null, e.message.orEmpty())
            }
        }
        showProgress(activity)
    }

    /** ダウンロードの進み具合を出し、終わったらインストール（失敗なら理由）へ進む */
    private fun showProgress(activity: AppCompatActivity) {
        val update = when (val s = downloadState.value) {
            is DownloadState.Running -> s.update
            is DownloadState.Done -> s.update
            null -> return
        }
        if (!alive(activity) || !showingIn.add(activity)) return
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
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Orbit ${update.versionName}")
            .setView(box)
            .setCancelable(false)
            .setNegativeButton("キャンセル") { _, _ -> downloadJob?.cancel() }
            .show()
        activity.lifecycleScope.launch {
            try {
                val end = downloadState
                    .onEach { s ->
                        if (s is DownloadState.Running && s.percent >= 0) {
                            bar.isIndeterminate = false
                            bar.setProgressCompat(s.percent, true)
                            label.text = "ダウンロードしています… ${s.percent}%"
                        }
                    }
                    .first { it !is DownloadState.Running }
                dialog.dismiss()
                if (end !is DownloadState.Done || !alive(activity)) return@launch
                // 結果を受け取ったのはこの画面（ほかの画面で二重にインストールの画面を出さない）
                downloadState.value = null
                if (end.error != null || end.file == null) {
                    message(activity, "ダウンロードできませんでした", "インターネットにつながっているか確かめてください。\n（${end.error.orEmpty()}）")
                } else {
                    install(activity, end.file)
                }
            } finally {
                // 画面が閉じられた（回したときなど）：ダイアログだけ片付ける。ダウンロードは続き、次の画面が引き継ぐ
                showingIn.remove(activity)
                if (dialog.isShowing) dialog.dismiss()
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
            // 中身が公開したものと同じか確かめるため、読みながら SHA-256 を計算する
            val sha = java.security.MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        sha.update(buf, 0, n)
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
            if (update.apkSha256.isNotEmpty()) {
                val got = sha.digest().joinToString("") { "%02x".format(it) }
                if (got != update.apkSha256) throw IllegalStateException("ダウンロードしたファイルが公開したものと違います（壊れている可能性があります）")
            }
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
