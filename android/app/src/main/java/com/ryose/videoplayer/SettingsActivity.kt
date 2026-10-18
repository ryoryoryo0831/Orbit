package com.ryose.videoplayer

import android.content.ActivityNotFoundException
import android.os.Bundle
import android.text.Spannable
import android.text.method.LinkMovementMethod
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.text.HtmlCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceFragmentCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 設定画面 */
class SettingsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdgeWithoutScrim()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "設定"

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            // 横向きのときのカメラの切り欠き（ディスプレイカットアウト）にも重ならないように
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.updatePadding(left = bars.left, top = bars.top, right = bars.right, bottom = bars.bottom)
            insets
        }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.settingsContainer, SettingsFragment())
                .commit()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        // データの書き出し・読み込み（Backup）。ファイルを選ぶ画面が無い端末（テレビなど）は「ダウンロード/Orbit」を使う
        private val exportLauncher =
            registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { exportTo(it) } }
        private val importLauncher =
            registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { confirmImport(it) } }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            // ハードウェアデコードの初期値は端末で変わる（テレビはオフ）ので、画面に出す前に書いておく
            AppSettings.ensureHwDecodingDefault(requireContext())
            setPreferencesFromResource(R.xml.preferences, rootKey)
            val ctx = requireContext()
            findPreference<androidx.preference.Preference>("version")?.summary =
                ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
            // テーマはすぐに切り替える
            findPreference<androidx.preference.ListPreference>("theme")?.setOnPreferenceChangeListener { _, v ->
                AppSettings.applyTheme(ctx, v as String)
                true
            }
            findPreference<androidx.preference.Preference>("backup_export")?.setOnPreferenceClickListener {
                startExport()
                true
            }
            findPreference<androidx.preference.Preference>("backup_import")?.setOnPreferenceClickListener {
                startImport()
                true
            }
            findPreference<androidx.preference.Preference>("playback_log")?.setOnPreferenceClickListener {
                PlaybackLogScreen.show(requireContext()) { refreshCrashSummary() }
                true
            }
            findPreference<androidx.preference.Preference>("update_now")?.setOnPreferenceClickListener {
                (activity as? AppCompatActivity)?.let { AppUpdater.checkNow(it) }
                true
            }
            findPreference<androidx.preference.Preference>("licenses")?.setOnPreferenceClickListener {
                showLicenses()
                true
            }
            findPreference<androidx.preference.Preference>("crash_log")?.setOnPreferenceClickListener {
                lifecycleScope.launch {
                    val records = withContext(Dispatchers.IO) { CrashLog.records(ctx) }
                    CrashLogScreen.showList(requireContext(), records) { refreshCrashSummary() }
                }
                true
            }
        }

        override fun onResume() {
            super.onResume()
            refreshCrashSummary()
        }

        /** 「落ちたときの記録」の下に件数を出す（記録を読むのは裏で） */
        private fun refreshCrashSummary() {
            val ctx = context ?: return
            lifecycleScope.launch {
                val records = withContext(Dispatchers.IO) { CrashLog.records(ctx) }
                findPreference<androidx.preference.Preference>("crash_log")?.summary = CrashLogScreen.summary(records)
                findPreference<androidx.preference.Preference>("playback_log")?.summary = PlaybackLogScreen.summary()
            }
        }

        private fun startExport() {
            try {
                exportLauncher.launch(Backup.fileName())
            } catch (_: ActivityNotFoundException) {
                // ファイルを選ぶ画面が無い（テレビなど）：「ダウンロード/Orbit」に書き出す
                val ctx = requireContext().applicationContext
                lifecycleScope.launch {
                    val place = withContext(Dispatchers.IO) { runCatching { Backup.exportToDownloads(ctx, Backup.export(ctx)) }.getOrNull() }
                    done(if (place != null) "「$place」に書き出しました。" else "書き出せませんでした。")
                }
            }
        }

        private fun exportTo(uri: android.net.Uri) {
            val ctx = requireContext().applicationContext
            lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { runCatching { Backup.writeText(ctx, uri, Backup.export(ctx)) }.getOrDefault(false) }
                done(if (ok) "書き出しました。NAS などのパスワードは含めていないので、別の端末に読み込んだら入れ直してください。" else "書き出せませんでした。")
            }
        }

        private fun startImport() {
            try {
                importLauncher.launch(arrayOf("application/json", "application/octet-stream", "text/plain"))
            } catch (_: ActivityNotFoundException) {
                // ファイルを選ぶ画面が無い（テレビなど）：「ダウンロード」にある書き出したファイルから選ぶ
                val ctx = requireContext().applicationContext
                lifecycleScope.launch {
                    val found = withContext(Dispatchers.IO) { runCatching { Backup.findInDownloads(ctx) }.getOrDefault(emptyList()) }
                    if (found.isEmpty()) {
                        done("「ダウンロード」フォルダに、書き出したファイル（Orbit-backup-…）が見つかりませんでした。")
                        return@launch
                    }
                    ActionSheet.show(
                        requireContext(), "読み込むファイル",
                        found.map { (name, uri) -> SheetItem(R.drawable.ic_info, name) { confirmImport(uri) } },
                    )
                }
            }
        }

        private fun confirmImport(uri: android.net.Uri) {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("読み込みますか？")
                .setMessage("今の設定・お気に入り・プレイリスト・登録・履歴は、ファイルの中身に置き換わります。読み込んだあと、アプリを起動し直します。")
                .setPositiveButton("読み込む") { _, _ -> importFrom(uri) }
                .setNegativeButton("キャンセル", null)
                .show()
        }

        private fun importFrom(uri: android.net.Uri) {
            val ctx = requireContext().applicationContext
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    val text = Backup.readText(ctx, uri)
                    if (text == null) Backup.Result(false, "ファイルを読めませんでした") else runCatching { Backup.import(ctx, text) }
                        .getOrElse { Backup.Result(false, "読み込めませんでした（${it.message.orEmpty()}）") }
                }
                val activity = activity ?: return@launch
                MaterialAlertDialogBuilder(activity)
                    .setTitle(if (result.ok) "読み込みました" else "読み込めませんでした")
                    .setMessage(result.message)
                    .setCancelable(!result.ok)
                    .setPositiveButton(if (result.ok) "起動し直す" else "閉じる") { _, _ -> if (result.ok) Backup.restartApp(activity) }
                    .show()
            }
        }

        private fun done(message: String) {
            val activity = activity ?: return
            MaterialAlertDialogBuilder(activity).setMessage(message).setPositiveButton("閉じる", null).show()
        }

        /** 使っているオープンソースのソフトウェアと、そのライセンスの一覧 */
        private fun showLicenses() {
            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle("オープンソースライセンス")
                .setMessage(HtmlCompat.fromHtml(LICENSES_HTML, HtmlCompat.FROM_HTML_MODE_LEGACY))
                .setPositiveButton("閉じる", null)
                .show()
            // リンクを押せるようにする（ブラウザが無い端末でも落ちないように）
            dialog.findViewById<TextView>(android.R.id.message)?.movementMethod = SafeLinkMovementMethod
        }
    }

    /** リンクを開けるアプリが無い（Android TV など）ときに、落ちずに知らせる */
    private object SafeLinkMovementMethod : LinkMovementMethod() {
        override fun onTouchEvent(widget: TextView, buffer: Spannable, event: MotionEvent): Boolean = try {
            super.onTouchEvent(widget, buffer, event)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(widget.context, "リンクを開けるアプリがありません", Toast.LENGTH_SHORT).show()
            true
        }

        override fun handleMovementKey(widget: TextView, buffer: Spannable, keyCode: Int, movementMetaState: Int, event: KeyEvent): Boolean = try {
            super.handleMovementKey(widget, buffer, keyCode, movementMetaState, event)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(widget.context, "リンクを開けるアプリがありません", Toast.LENGTH_SHORT).show()
            true
        }
    }

    private companion object {
        val LICENSES_HTML = """
            <b>libVLC</b>（VideoLAN）<br>
            GNU Lesser General Public License v2.1 以降（LGPL-2.1+）<br>
            <a href="https://www.videolan.org/vlc/libvlc.html">https://www.videolan.org/vlc/libvlc.html</a><br>
            ソースコード：<a href="https://code.videolan.org/videolan/vlc-android">https://code.videolan.org/videolan/vlc-android</a><br>
            <br>
            <b>AndroidX</b>（Google）<br>
            Apache License 2.0<br>
            <a href="https://developer.android.com/jetpack/androidx">https://developer.android.com/jetpack/androidx</a><br>
            <br>
            <b>Material Components for Android</b>（Google）<br>
            Apache License 2.0<br>
            <a href="https://github.com/material-components/material-components-android">https://github.com/material-components/material-components-android</a><br>
            <br>
            <b>Kotlin・kotlinx.coroutines</b>（JetBrains）<br>
            Apache License 2.0<br>
            <a href="https://github.com/Kotlin/kotlinx.coroutines">https://github.com/Kotlin/kotlinx.coroutines</a><br>
            <br>
            <b>Coil</b>（Coil Contributors）・<b>Okio</b>（Square）<br>
            Apache License 2.0<br>
            <a href="https://github.com/coil-kt/coil">https://github.com/coil-kt/coil</a><br>
            <br>
            <b>ライセンスの全文</b><br>
            <a href="https://www.apache.org/licenses/LICENSE-2.0">Apache License 2.0</a><br>
            <a href="https://www.gnu.org/licenses/old-licenses/lgpl-2.1.html">GNU LGPL v2.1</a>
        """.trimIndent()
    }
}
