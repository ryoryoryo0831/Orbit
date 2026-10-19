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

    /** 端にしまったときに見えている帯の幅（dp）。popup_player.xml の popupStashTab と同じ */
    const val STASH_VISIBLE_DP = 28
    /** しまった小窓を戻すときに、画面の端から離す幅（dp） */
    const val STASH_MARGIN_DP = 8

    /**
     * 指を離したときに、小窓の半分より多くが画面の左右の外に出ていれば、その側の端にしまう
     * （-1：左の端、1：右の端、0：しまわない）
     */
    fun stashSide(x: Int, w: Int, screenW: Int): Int = when {
        x + w / 2 < 0 -> -1
        x + w / 2 > screenW -> 1
        else -> 0
    }

    /** 端にしまったときの x（[visible] の幅だけ画面に残す） */
    fun stashedX(side: Int, w: Int, screenW: Int, visible: Int): Int = if (side < 0) visible - w else screenW - visible

    /** しまった小窓を戻すときの x（端から [margin] 離す） */
    fun unstashedX(side: Int, w: Int, screenW: Int, margin: Int): Int = if (side < 0) margin else screenW - w - margin

    /** 真ん中のボタンの大きさ（dp）。popup_player.xml と同じ */
    private const val BUTTON_DP = 40
    private const val PLAY_BUTTON_DP = 48
    /** 右上のボタン（閉じる・全画面。横に 2 つ並ぶ）の大きさ（dp） */
    private const val CORNER_DP = 36
    /** 右下のつまみの大きさ（dp） */
    private const val RESIZE_DP = 24

    /**
     * 小窓の大きさに合わせて出す、真ん中のボタンの数
     * （5：戻る・前へ・再生・次へ・進む／3：戻る・再生・進む／1：再生だけ／0：なし。ダブルタップで再生・一時停止）。
     * 右上のボタン・右下のつまみ・下のバーと重ならない数だけ出す
     */
    fun buttonCount(widthDp: Float, heightDp: Float): Int =
        listOf(5, 3, 1).firstOrNull { rowCenterY(widthDp, heightDp, it) != null } ?: 0

    /**
     * 真ん中のボタンの列の、縦の中心（dp。窓の上から）。ふつうは窓の真ん中で、右上のボタンの下に
     * かかるときは、その下まで下げる。どこに置いても重なるなら null
     */
    fun rowCenterY(widthDp: Float, heightDp: Float, count: Int): Float? {
        if (count <= 0) return null
        val row = (count - 1) * BUTTON_DP + PLAY_BUTTON_DP
        if (widthDp < row + 8) return null
        // 列の右の端と、いちばん右のボタンの高さの半分
        val right = (widthDp + row) / 2
        val rightHalf = (if (count == 1) PLAY_BUTTON_DP else BUTTON_DP) / 2f
        val cornersLeft = widthDp - 2 * CORNER_DP
        var cy = heightDp / 2
        if (right + 4 > cornersLeft) {
            // 右上のボタンの下に入る。真ん中の再生ボタンまでかかるなら、再生ボタンの高さで避ける
            val playRight = widthDp / 2 + PLAY_BUTTON_DP / 2
            val half = if (playRight + 4 > cornersLeft) PLAY_BUTTON_DP / 2f else rightHalf
            cy = maxOf(cy, CORNER_DP + 2 + half)
        }
        // 下のバー（3dp）にかからない
        if (cy + PLAY_BUTTON_DP / 2 > heightDp - 4) return null
        // 右下のつまみにかからない
        if (right > widthDp - RESIZE_DP && cy + rightHalf > heightDp - RESIZE_DP) return null
        return cy
    }
}
