package com.ryose.videoplayer

import android.content.Context
import androidx.core.content.edit

/**
 * 動画ごとに、変えた再生速度と画面の明るさを覚えておく（次にその動画を開いたときに戻す）。
 * 覚えていない動画は 1 倍・端末の明るさで始める。1 倍に戻したら速度は忘れる
 */
class PerVideoStore(private val context: Context) {
    // 再生サービスを作っている途中では読めないので、初めて使うときに開く
    private val prefs by lazy { context.applicationContext.getSharedPreferences("per_video", Context.MODE_PRIVATE) }

    fun speed(key: String): Float? = prefs.getFloat("r|$key", NONE).takeIf { it > 0f }

    fun setSpeed(key: String, rate: Float) {
        if (rate == 1f) {
            if (prefs.contains("r|$key")) prefs.edit { remove("r|$key") }
        } else if (prefs.getFloat("r|$key", NONE) != rate) {
            prefs.edit { putFloat("r|$key", rate) }
        }
    }

    /** 覚えている明るさ（0〜1）。覚えていなければ null（端末の明るさ） */
    fun brightness(key: String): Float? = prefs.getFloat("b|$key", NONE).takeIf { it > 0f }

    fun setBrightness(key: String, value: Float) {
        if (value > 0f) prefs.edit { putFloat("b|$key", value.coerceIn(0.01f, 1f)) }
    }

    private companion object {
        const val NONE = -1f
    }
}
