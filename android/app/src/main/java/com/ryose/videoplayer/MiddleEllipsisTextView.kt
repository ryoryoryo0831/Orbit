package com.ryose.videoplayer

import android.content.Context
import android.text.StaticLayout
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * 長い名前がはみ出すときは、途中を「…」にして頭と末尾の両方を見せる TextView（maxLines 行に収める）。
 * ファイル名の末尾には、話数・日付・「Disc 2」・拡張子など見分けに大事なものが多いのに、
 * 普通の ellipsize="end" だとそこが消えてしまう。ellipsize="middle" は 1 行のときしか効かないので、自分で縮める
 */
class MiddleEllipsisTextView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.textViewStyle,
) : AppCompatTextView(context, attrs, defStyleAttr) {

    /** 縮める前の文字列（text は縮めたあとのもの） */
    var fullText: CharSequence = ""
        private set
    private var fitting = false
    /** 縮めたときの幅（同じ幅なら計算し直さない） */
    private var fittedWidth = -1

    override fun setText(text: CharSequence?, type: BufferType?) {
        if (!fitting) {
            fullText = text ?: ""
            fittedWidth = -1
        }
        super.setText(text, type)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec) - compoundPaddingLeft - compoundPaddingRight
        if (MeasureSpec.getMode(widthMeasureSpec) != MeasureSpec.UNSPECIFIED && width > 0 && width != fittedWidth) {
            fittedWidth = width
            val fitted = fit(fullText.toString(), width)
            if (fitted != text.toString()) {
                fitting = true
                super.setText(fitted, BufferType.NORMAL)
                fitting = false
            }
            // TalkBack では縮める前の名前を読む
            contentDescription = if (fitted.length < fullText.length) fullText else null
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun fit(s: String, width: Int): String {
        val lines = maxLines
        if (lines !in 1..20 || s.length < 2 || lineCount(s, width) <= lines) return s
        // 残す文字数を二分探索で、収まるいちばん多い数に
        var lo = 1
        var hi = s.length - 1
        var best = ELLIPSIS
        while (lo <= hi) {
            val keep = (lo + hi) / 2
            val candidate = cut(s, keep)
            if (lineCount(candidate, width) <= lines) {
                best = candidate
                lo = keep + 1
            } else {
                hi = keep - 1
            }
        }
        return best
    }

    /** keep 文字を残す（頭に 6 割・末尾に 4 割） */
    private fun cut(s: String, keep: Int): String {
        var head = keep - keep * 2 / 5
        var tailStart = s.length - keep * 2 / 5
        // サロゲートペア（絵文字など）の途中で切らない
        if (head in 1 until s.length && Character.isLowSurrogate(s[head])) head--
        if (tailStart in 1 until s.length && Character.isLowSurrogate(s[tailStart])) tailStart++
        return s.substring(0, head).trimEnd() + ELLIPSIS + s.substring(tailStart).trimStart()
    }

    private fun lineCount(s: String, width: Int): Int =
        StaticLayout.Builder.obtain(s, 0, s.length, paint, width)
            .setIncludePad(includeFontPadding)
            .setLineSpacing(lineSpacingExtra, lineSpacingMultiplier)
            .setBreakStrategy(breakStrategy)
            .setHyphenationFrequency(hyphenationFrequency)
            .build()
            .lineCount

    private companion object {
        const val ELLIPSIS = "…"
    }
}
