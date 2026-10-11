package com.ryose.videoplayer

import android.app.Activity
import android.view.View
import com.google.android.material.snackbar.Snackbar

/**
 * 「元に戻す」ボタン付きのお知らせを画面の下に出す（履歴から削除・プレイリストから外す などのあと）。
 * メインの画面では、ミニプレイヤーや下のタブに重ならないよう、その上に出す
 */
fun Activity.showUndo(message: String, undo: () -> Unit) {
    val root = findViewById<View>(android.R.id.content) ?: return
    Snackbar.make(root, message, Snackbar.LENGTH_LONG)
        .setAction("元に戻す") { undo() }
        .apply { (this@showUndo as? MainActivity)?.snackbarAnchor()?.let { anchorView = it } }
        .show()
}
