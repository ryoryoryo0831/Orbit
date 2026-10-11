package com.ryose.videoplayer

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.color.MaterialColors

/** メニューの 1 項目（アイコン・名前・今の状態など） */
data class SheetItem(
    val icon: Int,
    val label: String,
    val detail: String? = null,
    /** オンになっている項目（お気に入り・シャッフルなど）はアイコンを強調する */
    val active: Boolean = false,
    /** 行の右に︙ボタンを出し、押したとき（行の長押しでも）に呼ぶ。メニューは閉じない */
    val menu: (() -> Unit)? = null,
    /** false の行は押せない（「探しています…」のような案内の行） */
    val enabled: Boolean = true,
    val action: () -> Unit,
)

/**
 * 画面の下から出てくる、アイコン付きのメニュー（大きめの行で押しやすく）。
 * よく使う項目は [tiles] として、上にアイコンのマス目で並べられる（メニューが縦に長くなりすぎないように）。
 * 行は RecyclerView で並べるので、再生キューのように何千行あっても重くならない
 */
class ActionSheet private constructor(
    private val context: Context,
    private val title: String?,
    private var subtitle: String?,
    private val tiles: List<SheetItem>,
    private var items: List<SheetItem>,
) {
    private val dialog = BottomSheetDialog(context)
    private val dp = context.resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()

    private val onSurface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
    private val muted = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
    private val accent = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, Color.BLUE)
    private val tileBg = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainerHighest, Color.DKGRAY)
    private val tileBgActive = MaterialColors.getColor(context, com.google.android.material.R.attr.colorPrimaryContainer, Color.BLUE)
    private val onTileActive = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnPrimaryContainer, Color.WHITE)
    private val ripple = TypedValue().also {
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
    }.resourceId
    private val rippleBorderless = TypedValue().also {
        context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, it, true)
    }.resourceId

    private val list = RecyclerView(context).apply {
        layoutManager = LinearLayoutManager(context)
        clipToPadding = false
        setPadding(0, px(4), 0, px(16))
        // 行を出し入れするときの動き（再生キューを作り直したときにちらつかないように）は使わない
        itemAnimator = null
    }
    private val adapter = Adapter()

    /** 一覧の中身。見出し・マス目・行を、上から順に並べたもの */
    private sealed class Entry {
        data class Title(val text: String, val hasSubtitle: Boolean) : Entry()
        data class Subtitle(val text: String) : Entry()
        data class Tiles(val tiles: List<SheetItem>) : Entry()
        data class Row(val item: SheetItem) : Entry()
    }

    private var entries: List<Entry> = emptyList()

    private fun buildEntries() {
        entries = buildList {
            if (title != null) add(Entry.Title(title, subtitle != null))
            subtitle?.let { add(Entry.Subtitle(it)) }
            if (tiles.isNotEmpty()) add(Entry.Tiles(tiles))
            items.forEach { add(Entry.Row(it)) }
        }
    }

    /** 行だけを差し替える（キャストの機器が見つかったときなど） */
    @android.annotation.SuppressLint("NotifyDataSetChanged")
    fun update(items: List<SheetItem>, subtitle: String? = this.subtitle) {
        this.items = items
        this.subtitle = subtitle
        buildEntries()
        adapter.notifyDataSetChanged()
    }

    fun dismiss() = dialog.dismiss()

    private inner class Adapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = entries.size

        override fun getItemViewType(position: Int) = when (entries[position]) {
            is Entry.Title -> 0
            is Entry.Subtitle -> 1
            is Entry.Tiles -> 2
            is Entry.Row -> 3
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val view = when (viewType) {
                0 -> TextView(context).apply {
                    setTextColor(onSurface)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                    paint.isFakeBoldText = true
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    androidx.core.view.ViewCompat.setAccessibilityHeading(this, true)
                }
                1 -> TextView(context).apply {
                    setTextColor(muted)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(px(24), px(2), px(24), px(8))
                }
                2 -> LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(px(12), px(4), px(12), px(8))
                }
                else -> RowView()
            }
            view.layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val e = entries[position]) {
                is Entry.Title -> (holder.itemView as TextView).apply {
                    text = e.text
                    setPadding(px(24), px(8), px(24), if (e.hasSubtitle) 0 else px(8))
                }
                is Entry.Subtitle -> (holder.itemView as TextView).text = e.text
                is Entry.Tiles -> bindTiles(holder.itemView as LinearLayout, e.tiles)
                is Entry.Row -> (holder.itemView as RowView).bind(e.item)
            }
        }
    }

    /** 大きめの行：アイコン・名前・今の状態・（あれば）︙ボタン */
    private inner class RowView : LinearLayout(context) {
        private val icon = ImageView(context).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        private val label = TextView(context).apply {
            setTextColor(onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }
        private val detail = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        private val more = ImageButton(context).apply {
            setImageResource(R.drawable.ic_more)
            imageTintList = ColorStateList.valueOf(muted)
            setBackgroundResource(rippleBorderless)
        }

        init {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = px(56)
            setPadding(px(24), px(6), px(12), px(6))
            isFocusable = true
            addView(icon, LinearLayout.LayoutParams(px(24), px(24)))
            val texts = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(px(20), 0, px(12), 0)
                addView(label)
                addView(detail)
            }
            addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(more, LinearLayout.LayoutParams(px(44), px(48)))
        }

        fun bind(item: SheetItem) {
            icon.setImageResource(item.icon)
            icon.imageTintList = ColorStateList.valueOf(if (item.active) accent else muted)
            label.text = item.label
            detail.text = item.detail
            detail.setTextColor(if (item.active) accent else muted)
            detail.visibility = if (item.detail.isNullOrEmpty()) View.GONE else View.VISIBLE
            if (item.enabled) {
                setBackgroundResource(ripple)
                setOnClickListener {
                    dialog.dismiss()
                    item.action()
                }
            } else {
                background = null
                setOnClickListener(null)
                isClickable = false
            }
            val menu = item.menu
            more.visibility = if (menu != null) View.VISIBLE else View.GONE
            more.contentDescription = "${item.label}のメニュー"
            more.setOnClickListener { menu?.invoke() }
            if (menu != null) {
                setOnLongClickListener {
                    menu()
                    true
                }
            } else {
                setOnLongClickListener(null)
                isLongClickable = false
            }
        }
    }

    /** よく使う項目のマス目（1 行に 4 つ。丸いアイコンの下に短い名前） */
    private fun bindTiles(box: LinearLayout, tiles: List<SheetItem>) {
        box.removeAllViews()
        tiles.chunked(COLUMNS).forEach { chunk ->
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            chunk.forEach { row.addView(tileView(it), LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
            // 最後の行が 4 つに足りないときも、マスの幅をそろえる
            repeat(COLUMNS - chunk.size) { row.addView(View(context), LinearLayout.LayoutParams(0, 0, 1f)) }
            box.addView(row)
        }
    }

    private fun tileView(item: SheetItem): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(px(2), px(8), px(2), px(8))
        setBackgroundResource(ripple)
        isFocusable = true
        contentDescription = listOfNotNull(item.label, item.detail).joinToString("、")
        setOnClickListener {
            dialog.dismiss()
            item.action()
        }
        addView(ImageView(context).apply {
            setImageResource(item.icon)
            imageTintList = ColorStateList.valueOf(if (item.active) onTileActive else onSurface)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (item.active) tileBgActive else tileBg)
            }
            setPadding(px(13), px(13), px(13), px(13))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(px(50), px(50)))
        addView(TextView(context).apply {
            text = item.label
            setTextColor(onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, px(6), 0, 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        if (!item.detail.isNullOrEmpty()) addView(TextView(context).apply {
            text = item.detail
            setTextColor(if (item.active) accent else muted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
    }

    private fun show(scrollTo: Int) {
        buildEntries()
        list.adapter = adapter
        if (scrollTo >= 0) {
            // scrollTo は行の番号（見出しとマス目の分をずらす）
            val first = entries.indexOfFirst { it is Entry.Row }.coerceAtLeast(0)
            (list.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(first + scrollTo, 0)
        }
        dialog.setContentView(list)
        // 横向きでも全部見えるように、最初から全体を開く
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.behavior.skipCollapsed = true
        // メニューの背景（Material 3 の下から出るシートは colorSurfaceContainerLow）をナビゲーションバーの下まで見せる
        dialog.clearNavigationBarScrim(
            MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainerLow, Color.BLACK)
        )
        dialog.show()
    }

    companion object {
        private const val COLUMNS = 4

        /**
         * メニューを出す。[tiles] は上のマス目に並べる項目、[scrollTo] は最初に見せる行の番号。
         * 何も無ければ出さずに null を返す
         */
        fun show(
            context: Context,
            title: String?,
            items: List<SheetItem>,
            subtitle: String? = null,
            tiles: List<SheetItem> = emptyList(),
            scrollTo: Int = -1,
            onDismiss: (() -> Unit)? = null,
        ): ActionSheet? {
            if (items.isEmpty() && tiles.isEmpty()) return null
            return ActionSheet(context, title, subtitle, tiles, items).also { sheet ->
                onDismiss?.let { f -> sheet.dialog.setOnDismissListener { f() } }
                sheet.show(scrollTo)
            }
        }
    }
}
