package com.ryose.videoplayer

import kotlin.math.roundToInt

/**
 * 自由な小窓（ポップアップ）の大きさと位置の計算。
 * 大きさは「長い方の辺の長さ」で表し、縦横の比率は動画に合わせる
 */
object PopupGeometry {

    /** 小窓の長い方の辺の最小（dp）。OS の小窓（多くの端末で 108dp ほど）より小さくできる */
    const val MIN_SIZE_DP = 96
    /** 初めて出すときの大きさ（dp） */
    const val DEFAULT_SIZE_DP = 220
    /** 画面の端に寄せたときに、最低でも見えている幅（dp） */
    const val KEEP_VISIBLE_DP = 40

    /** 縦横の比率（高さ ÷ 幅）。極端に細長い動画は小窓が使いにくくなるので抑える */
    fun aspect(videoW: Int, videoH: Int): Float {
        if (videoW <= 0 || videoH <= 0) return 9f / 16f
        return (videoH.toFloat() / videoW).coerceIn(1 / 2.39f, 2.39f)
    }

    /** 長い方の辺の長さから、小窓の幅と高さを求める */
    fun windowSize(size: Int, aspect: Float): Pair<Int, Int> =
        if (aspect <= 1f) size to (size * aspect).roundToInt()
        else (size / aspect).roundToInt() to size

    /** 画面からはみ出さない、長い方の辺の最大 */
    fun maxSize(screenW: Int, screenH: Int, aspect: Float): Int =
        if (aspect <= 1f) minOf(screenW, (screenH / aspect).toInt())
        else minOf(screenH, (screenW * aspect).toInt())

    fun clampSize(size: Int, minSize: Int, screenW: Int, screenH: Int, aspect: Float): Int {
        val max = maxSize(screenW, screenH, aspect)
        return size.coerceIn(minOf(minSize, max), max)
    }

    /**
     * 小窓の位置。画面の外へはみ出してもよいが、端に寄せても [keep] だけは見えているようにする
     * （端に隠しておいて、つまんで戻せるように）
     */
    fun clampPosition(x: Int, y: Int, w: Int, h: Int, screenW: Int, screenH: Int, keep: Int): Pair<Int, Int> {
        val kx = minOf(keep, w)
        val ky = minOf(keep, h)
        return x.coerceIn(kx - w, screenW - kx) to y.coerceIn(ky - h, screenH - ky)
    }

    /** 真ん中のボタンの大きさ（dp）。popup_player.xml と同じ */
    private const val BUTTON_DP = 40
    private const val PLAY_BUTTON_DP = 48
    /** 左上（閉じる）・右上（全画面）のボタンの大きさ（dp） */
    private const val CORNER_DP = 36

    /**
     * 小窓の大きさに合わせて出す、真ん中のボタンの数
     * （5：戻る・前へ・再生・次へ・進む／3：戻る・再生・進む／1：再生だけ／0：なし。ダブルタップで再生・一時停止）。
     * 左上・右上のボタンと重ならない数だけ出す（横に避けられるか、窓の高さがあって上下に避けられるとき）
     */
    fun buttonCount(widthDp: Float, heightDp: Float): Int {
        fun fits(count: Int): Boolean {
            val row = (count - 1) * BUTTON_DP + PLAY_BUTTON_DP
            if (widthDp < row + 8) return false
            val besideCorners = widthDp >= row + 2 * CORNER_DP + 8
            val belowCorners = heightDp / 2 - PLAY_BUTTON_DP / 2 >= CORNER_DP + 2
            return besideCorners || belowCorners
        }
        return listOf(5, 3, 1).firstOrNull { fits(it) } ?: 0
    }
}
