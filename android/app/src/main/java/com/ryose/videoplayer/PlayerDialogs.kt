package com.ryose.videoplayer

import android.content.Context
import com.google.android.material.color.MaterialColors
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import org.videolan.libvlc.MediaPlayer
import kotlin.math.roundToInt

/** イコライザーの設定（アプリ内に保存） */
data class EqSettings(val enabled: Boolean, val preset: Int, val preamp: Float, val bands: List<Float>)

object EqualizerPrefs {
    private const val NAME = "equalizer"

    fun load(context: Context): EqSettings {
        val p = context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        val count = MediaPlayer.Equalizer.getBandCount()
        val saved = p.getString("bands", "").orEmpty().split(",").mapNotNull { it.toFloatOrNull() }
        return EqSettings(
            enabled = p.getBoolean("enabled", false),
            preset = p.getInt("preset", -1),
            preamp = p.getFloat("preamp", 0f),
            bands = if (saved.size == count) saved else List(count) { 0f },
        )
    }

    fun save(context: Context, s: EqSettings) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).edit {
            putBoolean("enabled", s.enabled)
            putInt("preset", s.preset)
            putFloat("preamp", s.preamp)
            putString("bands", s.bands.joinToString(","))
        }
    }
}

/** 動画・音楽の再生画面で共通のダイアログ */
object PlayerDialogs {

    /** 再生速度：スライダーで 0.25〜4 倍を 0.05 刻み、よく使う速度はボタンで */
    fun showSpeed(context: Context, svc: PlaybackService, onChanged: () -> Unit) {
        val dp = context.resources.displayMetrics.density
        val value = TextView(context).apply {
            textSize = 26f
            gravity = Gravity.CENTER
        }
        val slider = com.google.android.material.slider.Slider(context).apply {
            valueFrom = 0.25f
            valueTo = 4f
            stepSize = 0.05f
            this.value = (Math.round(svc.rate * 20) / 20f).coerceIn(0.25f, 4f)
            setLabelFormatter { formatRate(it) }
        }
        fun apply(r: Float) {
            svc.setPlaybackRate(r)
            value.text = formatRate(r)
            onChanged()
        }
        value.text = formatRate(svc.rate)
        slider.addOnChangeListener { _, v, fromUser -> if (fromUser) apply(v) }
        val presets = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf(0.5f, 1f, 1.25f, 1.5f, 2f).forEach { r ->
            presets.addView(
                MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                    text = if (r == 1f) "標準" else formatRate(r)
                    setPadding(0, 0, 0, 0)
                    setOnClickListener {
                        slider.value = r
                        apply(r)
                    }
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = (2 * dp).toInt()
                    marginEnd = (2 * dp).toInt()
                },
            )
        }
        val box = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), 0)
            addView(value)
            addView(slider)
            addView(presets)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("再生速度")
            .setView(box)
            .setPositiveButton("閉じる", null)
            .show()
    }

    fun sleepLabel(svc: PlaybackService): String? = when {
        svc.sleepAtEnd -> "この曲・動画の終わりで停止"
        svc.sleepAt > 0 -> "あと ${SleepTimer.remainingMinutes(svc.sleepAt, System.currentTimeMillis())} 分で停止"
        else -> null
    }

    /** マス目に出す短い表示（「あと 12 分」「終わりで停止」）。オフなら null */
    fun sleepShortLabel(svc: PlaybackService): String? = when {
        svc.sleepAtEnd -> "終わりで停止"
        svc.sleepAt > 0 -> sleepLabel(svc)?.removeSuffix("で停止")
        else -> null
    }

    fun showSleepTimer(context: Context, svc: PlaybackService) {
        val minutes = listOf(15, 30, 45, 60, 90, 120)
        val on = sleepLabel(svc) != null
        ActionSheet.show(
            context, "スリープタイマー",
            listOf(SheetItem(if (!on) R.drawable.ic_check else R.drawable.ic_close, "オフ", active = !on) { svc.cancelSleepTimer() }) +
                minutes.map { m -> SheetItem(R.drawable.ic_timer, "$m 分後に停止") { svc.setSleepTimer(m) } } +
                SheetItem(
                    if (svc.sleepAtEnd) R.drawable.ic_check else R.drawable.ic_timer, "この曲・動画の終わりで停止", active = svc.sleepAtEnd,
                ) { svc.setSleepAtEnd() },
            subtitle = sleepLabel(svc) ?: "オフ",
        )
    }

    /** キャスト：同じネットワークの Chromecast などを探して、そこで再生する（見つかった機器から順に行が増える） */
    fun showCast(context: Context, svc: PlaybackService) {
        svc.startRendererDiscovery()

        fun castTo(target: org.videolan.libvlc.RendererItem?) {
            svc.castTo(target)
            val msg = if (target == null) "この端末での再生に戻しました" else "${target.displayName ?: target.name} で再生します"
            android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        }

        fun items(): List<SheetItem> = buildList {
            if (svc.renderer != null) add(SheetItem(R.drawable.ic_close, "キャストをやめる", "この端末で再生します") { castTo(null) })
            svc.renderers.values.forEach { r ->
                val on = r == svc.renderer
                add(SheetItem(
                    if (on) R.drawable.ic_cast_connected else R.drawable.ic_cast, r.displayName ?: r.name,
                    if (on) "再生中" else null, active = on,
                ) { castTo(r) })
            }
            // 探している間の案内（押せない行）
            if (svc.renderers.isEmpty()) add(SheetItem(
                R.drawable.ic_cast, "探しています…", "同じ Wi-Fi の Chromecast などがここに出ます", enabled = false,
            ) {})
        }

        var listener: PlaybackService.Listener? = null
        val sheet = ActionSheet.show(context, "キャスト", items(), subtitle = "テレビ・スピーカーで再生", onDismiss = {
            listener?.let { svc.removeListener(it) }
            svc.stopRendererDiscovery()
        }) ?: return
        listener = object : PlaybackService.Listener {
            override fun onRenderersChanged() = sheet.update(items())
        }.also { svc.addListener(it) }
    }

    /** 再生キュー：これから再生する順番の一覧。タップでその曲へ、︙（長押し）で並べ替え・キューから外す */
    fun showQueue(context: Context, svc: PlaybackService, scrollTo: Int = svc.orderPos) {
        val order = svc.order
        if (order.isEmpty()) return
        var sheet: ActionSheet? = null
        val items = order.mapIndexed { pos, i ->
            val item = svc.items[i]
            val current = pos == svc.orderPos
            SheetItem(
                when {
                    current -> R.drawable.ic_play
                    item.isAudio -> R.drawable.ic_music_note
                    else -> R.drawable.ic_movie
                },
                item.title,
                if (current) "再生中" else item.durationMs.takeIf { it > 0 }?.let { formatTime(it) },
                active = current,
                // 1 曲だけ（今の曲だけ）のときは、並べ替えも外すこともできないので︙を出さない
                menu = if (order.size > 1) {
                    {
                        showQueueItemMenu(context, svc, pos, onPlay = { sheet?.dismiss() }) { newPos ->
                            sheet?.dismiss()
                            showQueue(context, svc, newPos)
                        }
                    }
                } else null,
            ) { if (!current) svc.playAt(pos) }
        }
        sheet = ActionSheet.show(
            context, "再生キュー（${order.size}）", items,
            subtitle = if (order.size > 1) "︙ で順番の入れ替え・キューから外す" else null,
            // 今の曲（並べ替えたあとはその曲）の少し上から見せる
            scrollTo = if (scrollTo > 3) scrollTo - 2 else -1,
        )
    }

    /** 再生キューの 1 曲を長押ししたときのメニュー。変えたら新しい位置を onChanged に渡す */
    private fun showQueueItemMenu(
        context: Context, svc: PlaybackService, pos: Int, onPlay: () -> Unit, onChanged: (Int) -> Unit,
    ) {
        val item = svc.order.getOrNull(pos)?.let { svc.items.getOrNull(it) } ?: return
        val isCurrent = pos == svc.orderPos
        val last = svc.order.lastIndex
        val actions = buildList {
            if (!isCurrent) add(SheetItem(R.drawable.ic_play, "今すぐ再生") {
                svc.playAt(pos)
                onPlay()
            })
            if (!isCurrent && pos != svc.orderPos + 1) add(SheetItem(R.drawable.ic_skip_next, "次に再生") {
                val to = if (pos < svc.orderPos) svc.orderPos else svc.orderPos + 1
                svc.moveInQueue(pos, to)
                onChanged(to)
            })
            if (pos > 0) add(SheetItem(R.drawable.ic_expand_less, "1 つ上へ") {
                svc.moveInQueue(pos, pos - 1)
                onChanged(pos - 1)
            })
            if (pos < last) add(SheetItem(R.drawable.ic_expand_more, "1 つ下へ") {
                svc.moveInQueue(pos, pos + 1)
                onChanged(pos + 1)
            })
            if (!isCurrent) add(SheetItem(R.drawable.ic_delete, "キューから外す") {
                svc.removeFromQueue(pos)
                onChanged(pos.coerceAtMost(svc.order.lastIndex))
            })
        }
        if (actions.isEmpty()) return
        ActionSheet.show(context, item.title, actions)
    }

    /** イコライザー（VLC と同じプリセット・10バンド） */
    fun showEqualizer(context: Context, svc: PlaybackService) {
        val dp = context.resources.displayMetrics.density
        // 動画の画面（いつも暗い）と音楽の画面（明るいこともある）の両方で読めるよう、画面のテーマの色を使う
        val muted = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.GRAY)
        val textColor = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, Color.WHITE)
        val bandCount = MediaPlayer.Equalizer.getBandCount()
        var s = EqualizerPrefs.load(context)

        fun presetName(i: Int) = if (i < 0) "カスタム" else japanesePresetName(MediaPlayer.Equalizer.getPresetName(i))

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (8 * dp).toInt(), (20 * dp).toInt(), 0)
        }
        val enable = MaterialSwitch(context).apply {
            text = "イコライザーを使う"
            isChecked = s.enabled
        }
        root.addView(enable)

        val presetButton = MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
        root.addView(presetButton, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // スライダーは 0.1dB 単位、-20〜+20dB
        fun toProgress(db: Float) = ((db + 20f) * 10).roundToInt()
        fun toDb(progress: Int) = progress / 10f - 20f
        fun fmtDb(db: Float) = "%+.1f dB".format(db)

        val sliders = mutableListOf<Pair<SeekBar, TextView>>()
        fun addSlider(label: String, value: Float, onChange: (Float) -> Unit) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val name = TextView(context).apply {
                text = label
                setTextColor(textColor)
                textSize = 13f
                minWidth = (64 * dp).toInt()
            }
            val valueText = TextView(context).apply {
                text = fmtDb(value)
                setTextColor(muted)
                textSize = 12f
                minWidth = (64 * dp).toInt()
                gravity = Gravity.END
            }
            val bar = SeekBar(context).apply {
                max = 400
                progress = toProgress(value)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                        valueText.text = fmtDb(toDb(p))
                        if (fromUser) onChange(toDb(p))
                    }
                    override fun onStartTrackingTouch(sb: SeekBar) {}
                    override fun onStopTrackingTouch(sb: SeekBar) {}
                })
            }
            row.addView(name)
            row.addView(bar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(valueText)
            root.addView(row)
            sliders += bar to valueText
        }

        fun applyAndSave(new: EqSettings) {
            s = new
            EqualizerPrefs.save(context, s)
            svc.applyEqualizer()
            presetButton.text = "プリセット：${presetName(s.preset)}"
        }

        addSlider("プリアンプ", s.preamp) { db -> applyAndSave(s.copy(preamp = db, enabled = true).also { enable.isChecked = true }) }
        for (i in 0 until bandCount) {
            val f = MediaPlayer.Equalizer.getBandFrequency(i)
            val label = if (f < 1000) "${f.roundToInt()} Hz" else "${(f / 1000).let { if (it % 1f == 0f) it.toInt().toString() else "%.1f".format(it) }} kHz"
            addSlider(label, s.bands.getOrElse(i) { 0f }) { db ->
                val bands = s.bands.toMutableList().also { it[i] = db }
                applyAndSave(s.copy(bands = bands, preset = -1, enabled = true).also { enable.isChecked = true })
            }
        }

        fun refreshSliders() {
            sliders[0].first.progress = toProgress(s.preamp)
            s.bands.forEachIndexed { i, v -> sliders.getOrNull(i + 1)?.first?.progress = toProgress(v) }
        }

        presetButton.text = "プリセット：${presetName(s.preset)}"
        presetButton.setOnClickListener {
            val count = MediaPlayer.Equalizer.getPresetCount()
            val items = (0 until count).map { which ->
                SheetItem(
                    if (which == s.preset) R.drawable.ic_check else R.drawable.ic_equalizer, presetName(which), active = which == s.preset,
                ) {
                    val eq = MediaPlayer.Equalizer.createFromPreset(which)
                    applyAndSave(
                        EqSettings(
                            enabled = true,
                            preset = which,
                            preamp = eq.preAmp,
                            bands = (0 until bandCount).map { eq.getAmp(it) },
                        )
                    )
                    enable.isChecked = true
                    refreshSliders()
                }
            }
            ActionSheet.show(context, "プリセット", items, scrollTo = if (s.preset > 3) s.preset - 2 else -1)
        }
        enable.setOnCheckedChangeListener { _, checked ->
            if (checked != s.enabled) applyAndSave(s.copy(enabled = checked))
        }

        val scroll = ScrollView(context).apply { addView(root) }
        MaterialAlertDialogBuilder(context)
            .setTitle("イコライザー")
            .setView(scroll)
            .setPositiveButton("閉じる", null)
            .setNeutralButton("リセット") { _, _ ->
                applyAndSave(EqSettings(false, -1, 0f, List(bandCount) { 0f }))
            }
            .show()
    }

    /** VLC のプリセット名（英語）を日本語にする。知らない名前はそのまま */
    private fun japanesePresetName(name: String): String = when (name.trim().lowercase()) {
        "flat" -> "フラット"
        "classical" -> "クラシック"
        "club" -> "クラブ"
        "dance" -> "ダンス"
        "full bass" -> "低音を強く"
        "full bass and treble" -> "低音と高音を強く"
        "full treble" -> "高音を強く"
        "headphones" -> "ヘッドホン"
        "large hall" -> "大きなホール"
        "live" -> "ライブ"
        "party" -> "パーティー"
        "pop" -> "ポップ"
        "reggae" -> "レゲエ"
        "rock" -> "ロック"
        "ska" -> "スカ"
        "soft" -> "ソフト"
        "soft rock" -> "ソフトロック"
        "techno" -> "テクノ"
        else -> name
    }
}
