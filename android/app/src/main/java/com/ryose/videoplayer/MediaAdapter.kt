package com.ryose.videoplayer

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import coil.dispose
import coil.load
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File

/** 一覧の1行 */
sealed class Row {
    /** フォルダ・ストレージ・プレイリストなど、中に入っていく行 */
    data class Folder(
        val name: String,
        val info: String,
        val icon: Int = R.drawable.ic_folder,
        val dir: File? = null,
        val id: String? = null,
        val isStorage: Boolean = false,
        /** ジャケット画像を出すときの曲のファイル（アルバム・アーティストの行） */
        val art: String? = null,
        /** 「探しています…」のような案内の行（押せない・くるくるを出す） */
        val busy: Boolean = false,
    ) : Row()

    /** 動画の行 */
    data class Media(val item: PlaylistItem, val meta: String) : Row()

    /** 見出し（「ストレージ」「ネットワーク」など） */
    data class Header(val title: String) : Row()
}

class MediaAdapter(
    private val resume: ResumeStore,
    private val onClick: (position: Int) -> Unit,
    private val onLongClick: (position: Int) -> Unit = {},
    /** 行に︙ボタン（長押しと同じメニュー）を出すか */
    private val hasMenu: (Row) -> Boolean = { it is Row.Media },
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var data = mutableListOf<Row>()

    /**
     * 一覧の中身。変わった行だけ描き直す（再生から戻るたびに全部の行とサムネイルを描き直さないように）
     */
    var rows: List<Row>
        get() = data
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            val old = data
            val new = value.toMutableList()
            data = new
            // 中身が同じなら何もしない（再生から戻ったときなど。再生位置のバーは refreshProgress で直す）
            if (!favoritesChanged && old == new) return
            // お気に入りの ★ が変わったときや、行が多いときは全部描き直す。
            // 描き直すのは画面に見えている十数行だけなので速いが、何百行もの差分を比べるのは画面の処理を止めてしまう
            // （並べ替え・検索で、1000 本を超える一覧が一瞬固まっていた）
            if (favoritesChanged || old.isEmpty() || new.isEmpty() || old.size + new.size > MAX_DIFF_ROWS) {
                favoritesChanged = false
                notifyDataSetChanged()
                return
            }
            // 並べ替え（行の移動）は探さない（探すと行数の 2 乗の時間がかかる）
            DiffUtil.calculateDiff(object : DiffUtil.Callback() {
                override fun getOldListSize() = old.size
                override fun getNewListSize() = new.size
                override fun areItemsTheSame(o: Int, n: Int) = identity(old[o]) == identity(new[n])
                override fun areContentsTheSame(o: Int, n: Int) = old[o] == new[n]
            }, false).dispatchUpdatesTo(this)
        }

    private fun identity(row: Row): String = when (row) {
        is Row.Folder -> "F|${row.id ?: row.dir?.path ?: row.name}"
        is Row.Media -> "M|${row.item.key}"
        is Row.Header -> "H|${row.title}"
    }

    private var favoritesChanged = false

    /** グリッド表示（動画の行を大きなサムネイルのマスで表示する） */
    var grid = false
        @SuppressLint("NotifyDataSetChanged")
        set(value) {
            if (field == value) return
            field = value
            notifyDataSetChanged()
        }

    /** お気に入りの動画・曲（行に ★ を付ける） */
    var favoriteKeys: Set<String> = emptySet()
        set(value) {
            if (value != field) favoritesChanged = true
            field = value
        }

    /** 設定するとつまみを表示し、触ったときに呼ばれる（プレイリストの並べ替え用） */
    var dragListener: ((RecyclerView.ViewHolder) -> Unit)? = null

    fun move(from: Int, to: Int) {
        if (from !in data.indices || to !in data.indices) return
        data.add(to, data.removeAt(from))
        notifyItemMoved(from, to)
    }

    class FolderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        private val iconPadding = icon.paddingLeft
        private val iconTint = icon.imageTintList

        /** いつもの丸いアイコン */
        fun showIcon(res: Int) {
            icon.dispose()
            icon.imageTintList = iconTint
            icon.setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
            icon.scaleType = ImageView.ScaleType.FIT_CENTER
            icon.clipToOutline = false
            icon.setImageResource(res)
        }

        /** ジャケット画像（無ければいつものアイコン） */
        fun showArt(path: String, fallbackRes: Int) {
            showIcon(fallbackRes)
            icon.load(AudioArt(path)) {
                // 読み込み中も、いつものアイコンを出しておく
                placeholder(fallbackRes)
                error(fallbackRes)
                listener(
                    onSuccess = { _, _ ->
                        icon.imageTintList = null
                        icon.setPadding(0, 0, 0, 0)
                        icon.scaleType = ImageView.ScaleType.CENTER_CROP
                        icon.clipToOutline = true
                    },
                    onError = { _, _ -> showIcon(fallbackRes) },
                )
            }
        }
        val name: TextView = view.findViewById(R.id.name)
        val info: TextView = view.findViewById(R.id.info)
        val busy: View = view.findViewById(R.id.busy)
        val more: View = view.findViewById(R.id.moreButton)
    }

    class MediaHolder(view: View) : RecyclerView.ViewHolder(view) {
        val thumb: ImageView = view.findViewById(R.id.thumb)
        private val fallbackTint = android.content.res.ColorStateList.valueOf(
            androidx.core.content.ContextCompat.getColor(view.context, R.color.text_muted)
        )

        /** サムネイル・ジャケットが無いときのアイコン（枠いっぱいに引き伸ばすと切れて見えるので、真ん中に） */
        fun showFallback(res: Int) {
            thumb.scaleType = ImageView.ScaleType.CENTER
            thumb.imageTintList = fallbackTint
            thumb.setImageResource(res)
        }

        /** 読み込めた画像は枠いっぱいに */
        fun showImage() {
            thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            thumb.imageTintList = null
        }
        val duration: TextView = view.findViewById(R.id.duration)
        val progress: LinearProgressIndicator = view.findViewById(R.id.progress)
        val title: TextView = view.findViewById(R.id.title)
        val meta: TextView = view.findViewById(R.id.meta)
        val dragHandle: ImageView = view.findViewById(R.id.dragHandle)
        val more: View = view.findViewById(R.id.moreButton)
    }

    class HeaderHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(R.id.header)

        init {
            // 読み上げ（TalkBack）で見出しとして扱い、見出しごとに移動できるように
            ViewCompat.setAccessibilityHeading(title, true)
        }
    }

    override fun getItemViewType(position: Int) = when (data[position]) {
        is Row.Folder -> 0
        is Row.Media -> when {
            grid -> 3
            // 曲は正方形の小さなジャケットの行
            (data[position] as Row.Media).item.isAudio -> 4
            else -> 1
        }
        is Row.Header -> 2
    }

    override fun getItemCount() = data.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> FolderHolder(inflater.inflate(R.layout.item_folder, parent, false))
            2 -> HeaderHolder(inflater.inflate(R.layout.item_header, parent, false))
            3 -> MediaHolder(inflater.inflate(R.layout.item_video_grid, parent, false))
            4 -> MediaHolder(inflater.inflate(R.layout.item_song, parent, false))
            else -> MediaHolder(inflater.inflate(R.layout.item_video, parent, false))
        }
    }

    /** 視聴位置のバーだけを更新する（サムネイルなどは読み込み直さない） */
    fun refreshProgress() = notifyItemRangeChanged(0, itemCount, PAYLOAD_PROGRESS)

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
        val row = data.getOrNull(position)
        if (payloads.isNotEmpty() && payloads.all { it == PAYLOAD_PROGRESS }) {
            if (holder is MediaHolder && row is Row.Media) bindProgress(holder, row.item)
            return
        }
        onBindViewHolder(holder, position)
    }

    private fun bindProgress(holder: MediaHolder, item: PlaylistItem) {
        val pos = resume.get(item.key)
        if (pos > 0 && item.durationMs > 0) {
            holder.progress.visibility = View.VISIBLE
            holder.progress.progress = (pos * 1000 / item.durationMs).toInt().coerceIn(0, 1000)
        } else {
            holder.progress.visibility = View.GONE
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        holder.itemView.setOnClickListener { onClick(holder.bindingAdapterPosition) }
        holder.itemView.setOnLongClickListener {
            onLongClick(holder.bindingAdapterPosition)
            true
        }
        val row0 = data[position]
        val more = when (holder) {
            is FolderHolder -> holder.more
            is MediaHolder -> holder.more
            else -> null
        }
        more?.let { b ->
            val show = !(row0 is Row.Folder && row0.busy) && hasMenu(row0)
            b.isVisible = show
            b.setOnClickListener { onLongClick(holder.bindingAdapterPosition) }
            // 読み上げで、どの行のメニューか分かるように
            b.contentDescription = when (row0) {
                is Row.Media -> "${row0.item.title}のメニュー"
                is Row.Folder -> "${row0.name}のメニュー"
                else -> null
            }
        }
        when (val row = row0) {
            is Row.Header -> {
                (holder as HeaderHolder).title.text = row.title
                holder.itemView.setOnClickListener(null)
                holder.itemView.setOnLongClickListener(null)
                // setOnClickListener(null) でも「押せる」扱いになり、テレビのリモコンで見出しが選ばれて
                // （枠が無いので）どこを選んでいるか分からなくなっていた。見出しは押せない・選べないようにする
                holder.itemView.isClickable = false
                holder.itemView.isLongClickable = false
                holder.itemView.isFocusable = false
            }
            is Row.Folder -> {
                holder as FolderHolder
                val art = row.art
                if (art != null) holder.showArt(art, row.icon) else holder.showIcon(row.icon)
                holder.name.text = row.name
                holder.info.text = row.info
                holder.info.visibility = if (row.info.isEmpty()) View.GONE else View.VISIBLE
                holder.busy.isVisible = row.busy
                // 案内の行はリモコンでも選べないように（ほかの行に使い回されたら選べるように戻す）
                holder.itemView.isFocusable = !row.busy
                if (row.busy) {
                    // 案内の行は押せないようにする（押したときの波紋も出さない）
                    holder.itemView.setOnClickListener(null)
                    holder.itemView.setOnLongClickListener(null)
                    holder.itemView.isClickable = false
                    holder.itemView.isLongClickable = false
                }
            }
            is Row.Media -> {
                holder as MediaHolder
                val item = row.item
                holder.title.text = item.title
                val fav = item.key in favoriteKeys
                holder.meta.text = if (fav) {
                    // お気に入りは先頭に色付きの ★
                    android.text.SpannableString("★ " + row.meta).apply {
                        setSpan(
                            android.text.style.ForegroundColorSpan(androidx.core.content.ContextCompat.getColor(holder.itemView.context, R.color.accent)),
                            0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                        )
                    }
                } else row.meta
                // 読み上げでは記号の「★」ではなく「お気に入り」と読む
                holder.meta.contentDescription = if (fav) "お気に入り、${row.meta}" else null
                holder.meta.visibility = if (row.meta.isEmpty() && !fav) View.GONE else View.VISIBLE
                holder.duration.text = formatTime(item.durationMs)
                holder.duration.isVisible = item.durationMs > 0
                val fallbackIcon = when {
                    item.isAudio -> R.drawable.ic_music_note
                    // DVD・Blu-ray のディスクイメージは円盤のアイコン
                    item.isDiscImage -> R.drawable.ic_album
                    else -> R.drawable.ic_movie
                }
                if (item.isNetwork && (item.isAudio || item.isDiscImage)) {
                    // ネットワーク上の音楽・ディスクイメージはサムネイルを作らない
                    holder.thumb.dispose()
                    holder.showFallback(fallbackIcon)
                } else {
                    // 読み込むまで・画像が無いときは、アイコンを引き伸ばさずに真ん中に小さく出す。
                    // ネットワークの動画は VLC で 1 コマ取り出して覚える（App.kt の VideoThumbFetcher）
                    holder.showFallback(fallbackIcon)
                    val data: Any? = if (item.isAudio) item.path?.let { AudioArt(it) } else VideoThumb(item.path, item.uri)
                    holder.thumb.load(data) {
                        placeholder(fallbackIcon)
                        error(fallbackIcon)
                        fallback(fallbackIcon)
                        listener(onSuccess = { _, _ -> holder.showImage() })
                    }
                }

                bindProgress(holder, item)

                val drag = dragListener
                holder.dragHandle.isVisible = drag != null
                holder.dragHandle.setOnTouchListener { _, e ->
                    if (e.actionMasked == MotionEvent.ACTION_DOWN) drag?.invoke(holder)
                    false
                }
            }
        }
    }

    private companion object {
        const val PAYLOAD_PROGRESS = "progress"
        /** これより多い行の一覧は、差分を比べずに全部描き直す */
        const val MAX_DIFF_ROWS = 600
    }
}
