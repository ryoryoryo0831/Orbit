package com.ryose.videoplayer

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.videolan.libvlc.MediaPlayer
import kotlin.math.abs

/**
 * 再生画面の字幕・音声・映像トラックのメニューと、字幕・音声のタイミング調整（PlayerActivity から分けた）。
 * 選んだものは再生サービスに頼む（そのときの MediaPlayer に対して行われる。開いている間に次の動画へ進んでも大丈夫）
 *
 * @param pickSubtitle 「字幕ファイルを追加」でファイルを選ぶ画面を開く
 * @param showInfo 再生画面の真ん中のお知らせ
 */
class TrackMenus(
    private val context: Context,
    private val service: () -> PlaybackService?,
    private val pickSubtitle: () -> Unit,
    private val showInfo: (String) -> Unit,
) {
    fun showSubtitles() {
        val s = service() ?: return
        ActionSheet.show(
            context, "字幕",
            trackItems(s.spuTracks(), s.spuTrack, R.drawable.ic_subtitles) { service()?.selectSpuTrack(it) } + listOf(
                SheetItem(R.drawable.ic_add, "字幕ファイルを追加") { pickSubtitle() },
                SheetItem(R.drawable.ic_timer, "タイミング調整", "現在 ${formatDelay(s.spuDelay)}") { showDelayDialog(subtitle = true) },
            ) + listOfNotNull(
                // 隣にある字幕ファイルが文字化けしたとき
                if (s.currentItem?.path != null) SheetItem(
                    R.drawable.ic_edit, "文字化けを直す（文字コード）",
                    Subtitles.CHARSETS.firstOrNull { it.second == s.subtitleCharset() }?.first ?: "自動",
                ) { showCharsets() } else null,
            ),
        )
    }

    fun showAudio() {
        val s = service() ?: return
        ActionSheet.show(
            context, "音声トラック",
            trackItems(s.audioTracks(), s.audioTrack, R.drawable.ic_audiotrack) { service()?.selectAudioTrack(it) } +
                SheetItem(R.drawable.ic_timer, "タイミング調整", "現在 ${formatDelay(s.audioDelay)}") { showDelayDialog(subtitle = false) },
        )
    }

    fun showVideoTracks() {
        val s = service() ?: return
        val tracks = s.videoTracks()
        if (tracks.isEmpty()) return
        ActionSheet.show(context, "映像トラック", trackItems(tracks, s.videoTrack, R.drawable.ic_movie) { service()?.selectVideoTrack(it) })
    }

    private fun showCharsets() {
        val s = service() ?: return
        val current = s.subtitleCharset()
        val options: List<Pair<String, String?>> = listOf<Pair<String, String?>>("自動（おすすめ）" to null) + Subtitles.CHARSETS
        ActionSheet.show(context, "字幕の文字コード", options.map { (label, name) ->
            SheetItem(if (name == current) R.drawable.ic_check else R.drawable.ic_subtitles, label, active = name == current) {
                s.setSubtitleCharset(name)
                showInfo("字幕の文字コード：${label.substringBefore('（')}")
            }
        })
    }

    /** トラックの一覧（選ばれているものにチェック） */
    private fun trackItems(
        tracks: Array<MediaPlayer.TrackDescription>, current: Int, icon: Int, select: (Int) -> Unit,
    ): List<SheetItem> = tracks.map { t ->
        val name = if (t.id == -1) "オフ" else t.name ?: "トラック ${t.id}"
        val on = t.id == current
        SheetItem(if (on) R.drawable.ic_check else icon, name, if (on) "選択中" else null, active = on) { select(t.id) }
    }

    private fun formatDelay(us: Long) = "%+.1f 秒".format(us / 1_000_000.0)

    /** 字幕・音声のずれを 0.1 秒単位で調整するダイアログ */
    private fun showDelayDialog(subtitle: Boolean) {
        if (service() == null) return
        val dp = context.resources.displayMetrics.density
        fun current(): Long {
            val s = service() ?: return 0L
            return if (subtitle) s.spuDelay else s.audioDelay
        }
        fun apply(us: Long) {
            val s = service() ?: return
            if (subtitle) s.setSpuDelay(us) else s.setAudioDelay(us)
        }

        val value = TextView(context).apply {
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, (12 * dp).toInt())
        }
        fun refresh() {
            value.text = formatDelay(current())
        }

        val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(-1000L, -100L, 100L, 1000L).forEach { ms ->
            row.addView(
                MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = (if (ms > 0) "+" else "−") + "%.1f".format(abs(ms) / 1000.0)
                    setPadding(0, 0, 0, 0)
                    setOnClickListener {
                        apply(current() + ms * 1000)
                        refresh()
                    }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (3 * dp).toInt()
                    marginEnd = (3 * dp).toInt()
                }
            )
        }
        val hint = TextView(context).apply {
            text = if (subtitle) "＋：字幕を遅らせる　−：字幕を早める" else "＋：音声を遅らせる　−：音声を早める"
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.LTGRAY)
            setPadding(0, (8 * dp).toInt(), 0, 0)
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (16 * dp).toInt(), (20 * dp).toInt(), 0)
            addView(value)
            addView(row)
            addView(hint)
        }
        refresh()
        MaterialAlertDialogBuilder(context)
            .setTitle(if (subtitle) "字幕のタイミング" else "音声のタイミング")
            .setView(box)
            .setPositiveButton("閉じる", null)
            .setNeutralButton("リセット") { _, _ -> apply(0) }
            .show()
    }
}
