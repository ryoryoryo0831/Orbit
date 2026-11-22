package com.ryose.videoplayer

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt

/**
 * 長い一覧の右端に出す「早送りスクロール」のつまみ。つかんで動かすと一覧が一気に動き、今いる場所の頭文字を吹き出しで出す。
 * RecyclerView 付属のものは、つまみの長さが件数に反比例して（790 曲で約 20dp）指で持てないので自前で描く。
 * 一覧を動かしたときだけ出て、しばらくすると消える。テレビ（D パッド）では選ばれない（十字キーの操作は今まで通り）
 */
@SuppressLint("ViewConstructor")
class FastScrollBar private constructor(
    context: Context,
    private val list: RecyclerView,
    private val label: (Int) -> String?,
) : View(context) {

    private val dp = resources.displayMetrics.density
    private val touchWidth = 44 * dp
    private val thumbWidth = 6 * dp
    private val thumbHeight = 48 * dp
    private val thumbMargin = 6 * dp
    private val bubbleSize = 64 * dp

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.accent) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 28 * dp
        textAlign = Paint.Align.CENTER
    }
    private val rect = RectF()

    private var dragging = false
    private var bubbleText: String? = null
    private val hide = Runnable { if (!dragging) animate().alpha(0f).setDuration(250).start() }

    init {
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        alpha = 0f
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                if (dy != 0 && longEnough()) show()
                invalidate()
            }
        })
    }

    /** つまみを出すほど長い一覧か（画面 4 枚分より長い） */
    private fun longEnough(): Boolean =
        list.computeVerticalScrollRange() > list.computeVerticalScrollExtent() * 4

    private fun show() {
        removeCallbacks(hide)
        if (alpha < 1f) animate().cancel().also { alpha = 1f }
        postDelayed(hide, 1500)
    }

    private val trackTop get() = list.paddingTop + thumbMargin
    private val trackLength get() = (height - list.paddingTop - list.paddingBottom - thumbMargin * 2 - thumbHeight).coerceAtLeast(1f)

    /** 今のつまみの上端（一覧の進み具合から） */
    private fun thumbTop(): Float {
        val range = list.computeVerticalScrollRange() - list.computeVerticalScrollExtent()
        val fraction = if (range > 0) list.computeVerticalScrollOffset().toFloat() / range else 0f
        return trackTop + fraction.coerceIn(0f, 1f) * trackLength
    }

    override fun onDraw(canvas: Canvas) {
        if (alpha <= 0f) return
        val top = thumbTop()
        val right = width - thumbMargin
        thumbPaint.color = ContextCompat.getColor(context, if (dragging) R.color.accent else R.color.text_muted)
        rect.set(right - thumbWidth, top, right, top + thumbHeight)
        canvas.drawRoundRect(rect, thumbWidth / 2, thumbWidth / 2, thumbPaint)
        val text = bubbleText
        if (dragging && text != null) {
            val cx = right - touchWidth - bubbleSize / 2
            val cy = (top + thumbHeight / 2).coerceIn(bubbleSize / 2, height - bubbleSize / 2)
            canvas.drawCircle(cx, cy, bubbleSize / 2, bubblePaint)
            canvas.drawText(text, cx, cy - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 出ているときに、右端の帯（つまみのあたり）を触ったときだけ受け取る。ほかは一覧にそのまま渡す
                if (alpha < 0.5f || e.x < width - touchWidth) return false
                val top = thumbTop()
                if (e.y < top - thumbHeight || e.y > top + thumbHeight * 2) return false
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                list.stopScroll()
                scrollTo(e.y)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                scrollTo(e.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (dragging) {
                dragging = false
                bubbleText = null
                show()
                invalidate()
                return true
            }
        }
        return false
    }

    /** 指の位置 [y] に合わせて一覧を動かす */
    private fun scrollTo(y: Float) {
        val count = list.adapter?.itemCount ?: return
        if (count == 0) return
        val fraction = ((y - thumbHeight / 2 - trackTop) / trackLength).coerceIn(0f, 1f)
        val position = (fraction * (count - 1)).roundToInt()
        (list.layoutManager as? LinearLayoutManager)?.scrollToPositionWithOffset(position, 0)
        bubbleText = label(position)
        show()
        invalidate()
    }

    companion object {
        /** [list] の上に重ねる（[list] の親が FrameLayout のときだけ）。[label] は位置ごとの頭文字（無ければ null） */
        fun attach(list: RecyclerView, label: (Int) -> String?) {
            val parent = list.parent as? FrameLayout ?: return
            val bar = FastScrollBar(list.context, list, label)
            parent.addView(bar, parent.indexOfChild(list) + 1, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT,
            ))
        }

        /** 吹き出しに出す頭文字（数字・記号は「#」、ひらがな・カタカナはそのまま、英字は大文字） */
        fun initialOf(name: String?): String? {
            val c = name?.trim()?.firstOrNull() ?: return null
            return when {
                c.isDigit() -> "#"
                c.isLetter() -> c.uppercaseChar().toString()
                else -> "#"
            }
        }
    }
}
