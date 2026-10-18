package com.ryose.videoplayer

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** 設定の「落ちたときの記録」：記録の一覧と中身（コピー・共有できる） */
object CrashLogScreen {
    /** 設定の項目の下に出す説明 */
    fun summary(records: List<CrashLog.Record>): String =
        if (records.isEmpty()) "記録はありません"
        else "${records.size} 件（いちばん新しいもの：${CrashLog.formatTime(records.first().time)}）"

    /** @param onCleared 「記録をすべて消す」を押したあと（設定の説明を直す） */
    fun showList(context: Context, records: List<CrashLog.Record>, onCleared: () -> Unit) {
        if (records.isEmpty()) {
            MaterialAlertDialogBuilder(context)
                .setTitle("落ちたときの記録")
                .setMessage(
                    "落ちた記録はありません。\n\n" +
                        "アプリが落ちたり固まったりしたら、ここに記録が残ります。" +
                        "記録は端末の中だけに置き、どこにも送りません。",
                )
                .setPositiveButton("閉じる", null)
                .show()
            return
        }
        ActionSheet.show(
            context, "落ちたときの記録（${records.size}）",
            records.map { r ->
                SheetItem(R.drawable.ic_info, "${CrashLog.formatTime(r.time)}　${r.kind}", r.summary.take(120)) { showDetail(context, r) }
            } + SheetItem(R.drawable.ic_delete, "記録をすべて消す") { confirmClear(context, onCleared) },
            subtitle = "記録は端末の中だけにあり、どこにも送りません。「共有」を押したときだけ、選んだアプリに渡します",
        )
    }

    private fun showDetail(context: Context, r: CrashLog.Record) {
        val dp = context.resources.displayMetrics.density
        val text = TextView(context).apply {
            text = r.detail
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setTextIsSelectable(true)
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(r.kind)
            .setView(ScrollView(context).apply { addView(text) })
            .setPositiveButton("閉じる", null)
            .setNeutralButton("コピー") { _, _ -> copy(context, r.detail) }
            .setNegativeButton("共有") { _, _ -> share(context, r.detail) }
            .show()
    }

    private fun copy(context: Context, text: String) {
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("Orbit の記録", text))
        Toast.makeText(context, "コピーしました", Toast.LENGTH_SHORT).show()
    }

    private fun share(context: Context, text: String) {
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Orbit の落ちたときの記録")
            .putExtra(Intent.EXTRA_TEXT, text)
        try {
            context.startActivity(Intent.createChooser(send, "記録を共有"))
        } catch (_: ActivityNotFoundException) {
            // 共有できるアプリが無い（テレビなど）
            Toast.makeText(context, "共有できるアプリがありません。「コピー」をお使いください", Toast.LENGTH_LONG).show()
        }
    }

    private fun confirmClear(context: Context, onCleared: () -> Unit) {
        MaterialAlertDialogBuilder(context)
            .setTitle("記録をすべて消しますか？")
            .setPositiveButton("消す") { _, _ ->
                CrashLog.clear(context)
                onCleared()
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }
}
