package com.ryose.videoplayer

import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 保存しているデータ（お気に入り・プレイリスト・履歴・NAS の登録）が読めなかったことを、画面に知らせる。
 * 1.0.160 までは、読めないと黙って空にしていたので、「全部消えた」ように見えて理由が分からなかった。
 * 元の中身は、それぞれの保存場所が別の名前で残している（上書きしない）
 */
object DataProblems {
    private val pending = LinkedHashSet<String>()

    /** [what]（「プレイリスト」など）が読めなかった。どのスレッドからでもよい */
    fun report(what: String) {
        synchronized(pending) { pending += what }
    }

    /** 知らせていないものがあれば、1 回だけダイアログで知らせる（メイン画面が表に出たときに呼ぶ） */
    fun showIfAny(activity: AppCompatActivity) {
        val list = synchronized(pending) { pending.toList().also { pending.clear() } }
        if (list.isEmpty() || activity.isFinishing || activity.isDestroyed) return
        MaterialAlertDialogBuilder(activity)
            .setTitle("保存していたデータを読めませんでした")
            .setMessage(
                list.joinToString("・") + " を読めなかったので、空の状態で始めています。\n\n" +
                    "元の中身は消さずに残してあります。書き出したバックアップがあれば、" +
                    "「設定 → 読み込む」で戻せます。続くときは「設定 → 困ったときに → 再生の記録」を送ってください。",
            )
            .setPositiveButton("閉じる", null)
            .show()
    }
}
