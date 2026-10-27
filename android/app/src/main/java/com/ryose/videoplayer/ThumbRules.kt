package com.ryose.videoplayer

/**
 * サムネイルにする場面の決め方（Android・VLC に依存しない）。
 * 冒頭はタイトル・ロゴ・黒い画面が多く、何の動画か分からないので、長さの 1 割あたりの場面にする
 */
object ThumbRules {
    /** 長さが分からないときの場面（以前と同じ 1 秒あたり） */
    const val DEFAULT_MS = 1_000L
    /** 長い映画でも、ここより後ろまでは取りに行かない（取り出しに時間がかからないように） */
    const val MAX_MS = 10 * 60_000L

    /** 長さ [durationMs] の動画で、サムネイルにする場面の位置 */
    fun positionMs(durationMs: Long): Long {
        if (durationMs <= 0) return DEFAULT_MS
        return (durationMs / 10).coerceIn(DEFAULT_MS, MAX_MS).coerceAtMost(durationMs / 2)
    }
}
