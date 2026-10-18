package com.ryose.videoplayer

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 設定の「再生の記録」：見る・コピー・共有・消す（PlaybackLog） */
object PlaybackLogScreen {
    /** 画面に出す行の数（多すぎると開くのが遅い。コピー・共有はもっと多く） */
    private const val SHOW_LINES = 400
    private const val SEND_LINES = 1500

    fun summary(): String {
        val n = PlaybackLog.snapshot().size
        return if (n == 0) "記録はありません" else "$n 行。音声・字幕・DVD などの不具合のときに送ってください"
    }

    fun show(context: Context, onCleared: () -> Unit) {
        val lines = PlaybackLog.snapshot()
        if (lines.isEmpty()) {
            MaterialAlertDialogBuilder(context)
                .setTitle("再生の記録")
                .setMessage("記録はまだありません。\n\n動画や曲を再生すると、開いた・音声や字幕を選んだ・DVD のタイトルが変わった、などがここに残ります。記録は端末の中だけに置き、どこにも送りません。")
                .setPositiveButton("閉じる", null)
                .show()
            return
        }
        ActionSheet.show(
            context, "再生の記録（${lines.size} 行）",
            listOf(
                SheetItem(R.drawable.ic_info, "記録を見る", "新しい $SHOW_LINES 行") { showText(context) },
                SheetItem(R.drawable.ic_info, "コピー", "新しい $SEND_LINES 行と、この端末の情報") { copy(context) },
                SheetItem(R.drawable.ic_share, "共有", "メールやメッセージのアプリに渡します") { share(context) },
                SheetItem(R.drawable.ic_delete, "記録を消す") { confirmClear(context, onCleared) },
            ),
            subtitle = "記録は端末の中だけにあり、どこにも送りません。「共有」を押したときだけ、選んだアプリに渡します",
        )
    }

    private fun header(context: Context): String =
        "Orbit ${AppUpdater.versionName(context)}／${Build.MANUFACTURER} ${Build.MODEL}／Android ${Build.VERSION.RELEASE}"

    private fun text(context: Context, max: Int): String =
        header(context) + "\n" + PlaybackLog.snapshot(max).joinToString("\n")

    private fun showText(context: Context) {
        val dp = context.resources.displayMetrics.density
        val view = TextView(context).apply {
            text = PlaybackLog.snapshot(SHOW_LINES).asReversed().joinToString("\n")
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("再生の記録（新しい順）")
            .setView(ScrollView(context).apply { addView(view) })
            .setPositiveButton("閉じる", null)
            .setNeutralButton("コピー") { _, _ -> copy(context) }
            .show()
    }

    private fun copy(context: Context) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Orbit の再生の記録", text(context, SEND_LINES)))
        Toast.makeText(context, "コピーしました", Toast.LENGTH_SHORT).show()
    }

    private fun share(context: Context) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Orbit の再生の記録")
            .putExtra(Intent.EXTRA_TEXT, text(context, SEND_LINES))
        try {
            context.startActivity(Intent.createChooser(send, "記録を共有"))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "共有できるアプリがありません。「コピー」をお使いください", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmClear(context: Context, onCleared: () -> Unit) {
        MaterialAlertDialogBuilder(context)
            .setTitle("再生の記録を消しますか？")
            .setPositiveButton("消す") { _, _ ->
                PlaybackLog.clear()
                onCleared()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }
}
