package com.ryose.videoplayer

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 動画の再生画面のジェスチャーの決め方（PlayerActivity から分けた）。
 * Android に依存しないように分けてあるので、単体テストで確かめられる（GestureRulesTest）。
 * 実際にタッチを受け取って明るさ・音量・位置を変えるのは PlayerGestures
 */
object GestureRules {
    /** 指を動かしたときの操作 */
    enum class Kind { NONE, IGNORE, BRIGHTNESS, VOLUME, SEEK }

    /** ダブルタップした場所でする操作 */
    enum class DoubleTap { REWIND, TOGGLE_PLAY, FORWARD }

    /** 画面の横幅いっぱいに指を動かしたときに移動する長さ */
    const val SEEK_PER_WIDTH_MS = 90_000L

    /** 画面の高さの何 % 分の指の動きで、音量が 0〜100% 変わるか（1.5 倍） */
    const val VOLUME_PER_HEIGHT = 150

    const val MAX_ZOOM = 4f

    /** 指を離したときに、これより小さい倍率なら元のサイズに戻す */
    const val ZOOM_SNAP = 1.05f

    /**
     * 指を動かし始めたときに、何の操作にするか決める。
     * 上下：左半分は明るさ・右半分は音量。左右：位置の移動。画面の上下の端（[edge]）はシステムのジェスチャーに譲る
     */
    fun classify(
        startX: Float, startY: Float, dx: Float, dy: Float,
        width: Float, height: Float, edge: Float,
        brightness: Boolean, volume: Boolean, seek: Boolean,
    ): Kind = when {
        startY < edge || startY > height - edge -> Kind.IGNORE
        abs(dy) > abs(dx) -> when {
            startX < width / 2f -> if (brightness) Kind.BRIGHTNESS else Kind.IGNORE
            else -> if (volume) Kind.VOLUME else Kind.IGNORE
        }
        else -> if (seek) Kind.SEEK else Kind.IGNORE
    }

    /** ダブルタップ：左の 3 分の 1 は戻る、右の 3 分の 1 は進む、真ん中は再生・一時停止 */
    fun doubleTap(x: Float, width: Float): DoubleTap = when {
        x < width / 3f -> DoubleTap.REWIND
        x > width * 2 / 3f -> DoubleTap.FORWARD
        else -> DoubleTap.TOGGLE_PLAY
    }

    /** 明るさ（0.01〜1）。上へ動かすと明るく */
    fun brightness(start: Float, dy: Float, height: Float): Float = (start - dy / height).coerceIn(0.01f, 1f)

    /** 音量（0〜[limit]%）。上へ動かすと大きく */
    fun volume(start: Float, dy: Float, height: Float, limit: Int): Int =
        (start - dy / height * VOLUME_PER_HEIGHT).roundToInt().coerceIn(0, limit)

    /** 移動先（0〜長さ）。右へ動かすと進む */
    fun seekTarget(startMs: Long, dx: Float, width: Float, lengthMs: Long): Long =
        (startMs + (dx / width * SEEK_PER_WIDTH_MS).toLong()).coerceIn(0, lengthMs)

    fun clampZoom(z: Float): Float = z.coerceIn(1f, MAX_ZOOM)
}
