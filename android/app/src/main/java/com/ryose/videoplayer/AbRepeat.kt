package com.ryose.videoplayer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.widget.ImageButton
import android.widget.SeekBar
import androidx.core.content.edit
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors

/**
 * ファイルごとに A-B リピートの区間を覚えておく（次にそのファイルを開いたときに戻す）。
 * A と B の両方が決まった区間だけを覚え、✕ で解除したら忘れる
 */
class AbStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ab_repeat", Context.MODE_PRIVATE)

    fun get(key: String): Pair<Long, Long>? {
        val parts = prefs.getString(key, null)?.split(',') ?: return null
        val a = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val b = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return if (a >= 0 && b > a) a to b else null
    }

    fun save(key: String, a: Long, b: Long) {
        if (a < 0 || b <= a) return
        prefs.edit { putString(key, "$a,$b") }
    }

    fun clear(key: String) {
        if (prefs.contains(key)) prefs.edit { remove(key) }
    }
}

/**
 * シークバーの後ろに、A-B リピートの区間（帯）と A・B の位置（縦の線）を描く。
 * シークバーと同じ大きさで重ねて置き、[seekBar] の左右の余白に合わせて位置を計算する
 */
class AbRangeView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var seekBar: SeekBar? = null
    private var a = -1L
    private var b = -1L
    private var length = 0L

    private val dp = resources.displayMetrics.density
    private val accent = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, Color.BLUE)
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (accent and 0x00FFFFFF) or 0x66000000 }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val rect = RectF()

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun set(a: Long, b: Long, length: Long) {
        if (a == this.a && b == this.b && length == this.length) return
        this.a = a
        this.b = b
        this.length = length
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val bar = seekBar ?: return
        if (a < 0 || length <= 0) return
        val left = bar.paddingLeft.toFloat()
        val track = (width - bar.paddingLeft - bar.paddingRight).toFloat()
        if (track <= 0) return
        fun x(ms: Long) = left + track * (ms.coerceIn(0, length).toFloat() / length)
        val cy = height / 2f
        if (b > a) {
            val band = 6 * dp
            rect.set(x(a), cy - band, x(b), cy + band)
            canvas.drawRoundRect(rect, 3 * dp, 3 * dp, bandPaint)
        }
        val half = 9 * dp
        val w = 1.5f * dp
        listOf(a, b).filter { it >= 0 }.forEach { t ->
            rect.set(x(t) - w, cy - half, x(t) + w, cy + half)
            canvas.drawRoundRect(rect, w, w, markPaint)
        }
    }
}

/**
 * A-B リピートの操作パネル（view_ab_repeat.xml）。
 * 「A」「B」を押すとその瞬間の位置を設定し、両側の −・＋ で 0.5 秒ずつずらせる。✕ で解除して閉じる。
 * 動画と音楽の再生画面で共通
 */
class AbRepeatBar(
    private val panel: View,
    private val range: AbRangeView,
    /** お知らせを出す（動画は画面の真ん中、音楽はトースト） */
    private val onMessage: (String) -> Unit,
    /** 押されたとき（動画の画面では、操作パネルを隠すまでの時間を延ばす） */
    private val onUsed: () -> Unit = {},
) {
    var svc: PlaybackService? = null

    /** パネルを開いているか（A も B も決めていなくても、メニューから開いたら出しておく） */
    var open = false
        private set

    private val aButton: MaterialButton = panel.findViewById(R.id.abA)
    private val bButton: MaterialButton = panel.findViewById(R.id.abB)
    private val aMinus: ImageButton = panel.findViewById(R.id.abAMinus)
    private val aPlus: ImageButton = panel.findViewById(R.id.abAPlus)
    private val bMinus: ImageButton = panel.findViewById(R.id.abBMinus)
    private val bPlus: ImageButton = panel.findViewById(R.id.abBPlus)

    private val ctx = panel.context
    private val container = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorPrimaryContainer, Color.BLUE)
    private val onContainer = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnPrimaryContainer, Color.WHITE)
    private val onSurface = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)

    init {
        fun View.onTap(action: () -> Unit) = setOnClickListener {
            action()
            onUsed()
        }
        aButton.onTap { svc?.let { onMessage(it.setAbA()) } }
        bButton.onTap { svc?.let { onMessage(it.setAbB()) } }
        aMinus.onTap { nudge(true, -STEP_MS) }
        aPlus.onTap { nudge(true, STEP_MS) }
        bMinus.onTap { nudge(false, -STEP_MS) }
        bPlus.onTap { nudge(false, STEP_MS) }
        panel.findViewById<View>(R.id.abClose).onTap {
            val s = svc
            val wasOn = s != null && s.abA >= 0
            s?.clearAb()
            open = false
            refresh()
            if (wasOn) onMessage("A-Bリピートを解除しました")
        }
    }

    private fun nudge(isA: Boolean, delta: Long) {
        svc?.nudgeAb(isA, delta)?.let(onMessage)
    }

    /** メニューの「A-Bリピート」から開く */
    fun openPanel() {
        open = true
        refresh()
    }

    /** 今の A・B に合わせて表示を直す（モードが変わったとき・長さが分かったときに呼ぶ） */
    fun refresh() {
        val s = svc
        val a = s?.abA ?: -1
        val b = s?.abB ?: -1
        if (a >= 0) open = true
        panel.isVisible = open && s != null
        range.set(a, b, s?.lengthMs ?: 0)
        style(aButton, if (a >= 0) "A  ${formatAbTime(a)}" else "A を設定", a >= 0)
        style(bButton, if (b >= 0) "B  ${formatAbTime(b)}" else "B を設定", b >= 0)
        aButton.contentDescription =
            if (a >= 0) "A（くり返しの始め）、${formatAbTime(a)}。押すと今の位置に設定" else "A（くり返しの始め）を今の位置に設定"
        bButton.contentDescription =
            if (b >= 0) "B（くり返しの終わり）、${formatAbTime(b)}。押すと今の位置に設定" else "B（くり返しの終わり）を今の位置に設定"
        enable(aMinus, a >= 0)
        enable(aPlus, a >= 0)
        enable(bMinus, b >= 0)
        enable(bPlus, b >= 0)
    }

    /** 決めた点は塗りつぶし、まだの点は枠だけにする */
    private fun style(button: MaterialButton, text: String, set: Boolean) {
        button.text = text
        button.backgroundTintList = ColorStateList.valueOf(if (set) container else Color.TRANSPARENT)
        button.setTextColor(if (set) onContainer else onSurface)
    }

    private fun enable(button: View, on: Boolean) {
        button.isEnabled = on
        button.alpha = if (on) 1f else 0.35f
    }

    private companion object {
        /** −・＋で動かす幅（ミリ秒） */
        const val STEP_MS = 500L
    }
}
