package com.ryose.videoplayer

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/** 設定画面で変更できる設定（キーは res/xml/preferences.xml と同じ） */
object AppSettings {

    private fun prefs(c: Context): SharedPreferences = PreferenceManager.getDefaultSharedPreferences(c)

    private fun bool(c: Context, key: String, def: Boolean) = prefs(c).getBoolean(key, def)

    private fun string(c: Context, key: String, def: String) = prefs(c).getString(key, def) ?: def

    /** VLC のエンジンに必ず渡すオプション */
    val BASE_VLC_OPTIONS = listOf(
        "--audio-time-stretch",     // 速度を変えても声の高さを変えない
        // AVI の目次（インデックス）が壊れている・無いときは、聞かずに作り直す
        // （初期設定では英語の確認が出て再生が止まり、作らないとシークがおかしくなる）
        "--avi-index=3",
        "--no-sub-autodetect-file", // 字幕の自動読み込みはアプリ側で行う（文字コード変換のため）
        "--http-reconnect",
    )

    /** 字幕の見た目などを含めた、VLC のエンジンに渡すオプション */
    fun vlcOptions(c: Context): List<String> = buildList {
        addAll(BASE_VLC_OPTIONS)
        add("--deinterlace=${string(c, "deinterlace", "-1")}")
        add("--deinterlace-mode=blend")
        add("--freetype-rel-fontsize=${string(c, "sub_size", "16")}")
        add("--freetype-color=${string(c, "sub_color", "16777215")}")
        add("--freetype-outline-thickness=${string(c, "sub_outline", "4")}")
        if (bool(c, "sub_bold", false)) add("--freetype-bold")
        if (bool(c, "sub_background", false)) {
            add("--freetype-background-opacity=128")
            add("--freetype-background-color=0")
        } else {
            add("--freetype-background-opacity=0")
        }
    }

    // 再生
    fun resume(c: Context) = bool(c, "resume", true)
    /**
     * ハードウェアデコード。初期値はオン（スマホもテレビも）。
     * 1.0.82〜1.0.84 ではテレビの初期値をオフにしたが、テレビ（Fire TV・MediaTek・32 ビット）の実機で確かめると、
     * ソフトウェアでは 4K の HEVC が追いつかずに崩れ、ハードウェアなら正しく再生でき、閉じても固まらなかった
     * （止める処理は MediaPlayer の取り替えで裏に回してある）ので、オンに戻した
     */
    fun hwDecoding(c: Context): Boolean {
        ensureHwDecodingDefault(c)
        return bool(c, KEY_HW_DECODING, true)
    }

    /**
     * ハードウェアデコードの初期値を書いておく（設定画面を開く前・再生の前に呼ぶ）。
     * 1.0.76 までの「hw_decoding」は、設定画面を開いただけでオンが保存されているので、名前を変えた（前の選択を引き継ぐ）。
     * テレビで 1.0.82〜1.0.84 のときに書いたオフは、一度だけオンに戻す
     */
    fun ensureHwDecodingDefault(c: Context) {
        val p = prefs(c)
        if (!p.contains(KEY_HW_DECODING)) {
            p.edit { putBoolean(KEY_HW_DECODING, if (isTv(c)) true else p.getBoolean("hw_decoding", true)) }
        }
        if (isTv(c) && !p.getBoolean(KEY_TV_HW_RESTORED, false)) {
            p.edit { putBoolean(KEY_HW_DECODING, true); putBoolean(KEY_TV_HW_RESTORED, true) }
        }
    }

    private const val KEY_TV_HW_RESTORED = "tv_hw_decode_restored"

    const val KEY_HW_DECODING = "hw_decode"

    @Volatile
    private var tv: Boolean? = null

    /** テレビ（Fire TV・Android TV。リモコンで操作する画面がある端末） */
    fun isTv(c: Context): Boolean = tv ?: c.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK).also { tv = it }

    /** テレビでの文字の大きさ（離れたソファーから読めるように、スマホ向けの大きさを何倍にするか） */
    private const val TV_FONT_SCALE = 1.25f

    /**
     * 画面（Activity）の attachBaseContext で使う。テレビなら文字を大きくした Context を返す。
     * 画面の部品ごとに大きさを変えるのではなく、文字の倍率（端末の設定の「文字の大きさ」と同じ仕組み）を上げる
     */
    fun tvScaled(base: Context): Context {
        if (!isTv(base)) return base
        // 文字の倍率だけを上書きする（向きや画面の大きさまで写すと、回したときに古い値のままになる）
        val config = android.content.res.Configuration()
        config.fontScale = base.resources.configuration.fontScale * TV_FONT_SCALE
        return base.createConfigurationContext(config)
    }
    /** インターレース解除の設定（-1：自動、0：オフ、1：オン） */
    fun deinterlace(c: Context) = string(c, "deinterlace", "-1")
    fun audioBoost(c: Context) = bool(c, "audio_boost", true)
    /** 再生画面の向き："auto"（スマホの向きに合わせて自動回転）・"user"（回転ロックに従う）・"video"（動画に合わせる） */
    fun playerOrientation(c: Context) = string(c, "player_orientation", "auto")
    /** 「動画」タブの一覧から再生したとき、終わったら次の動画を再生する */
    fun videosAutoNext(c: Context) = bool(c, "videos_auto_next", false)
    /**
     * DVD（ISO）をメニューから始める（初期はオン。オフなら本編から）。
     * 1.0.72 の「dvd_menu_start」（初期オフ）は、設定画面を開いただけでオフが保存されているので、名前を変えて初期値をオンにした
     */
    fun dvdMenuStart(c: Context) = bool(c, "dvd_start_in_menu", true)
    fun networkCachingMs(c: Context) = string(c, "network_caching", "1500").toIntOrNull() ?: 1500

    /** テーマ（"dark"・"light"・"system"）を反映する */
    fun applyTheme(c: Context, value: String = string(c, "theme", "dark")) {
        androidx.appcompat.app.AppCompatDelegate.setDefaultNightMode(
            when (value) {
                "light" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                "system" -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                else -> androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
            }
        )
    }

    /** 動画の画面を離れたとき："POPUP"（自由な小窓）・"PIP"・"AUDIO"・"PAUSE" */
    fun leaveAction(c: Context): String {
        val p = prefs(c)
        if (p.contains("leave_action")) return p.getString("leave_action", "POPUP") ?: "POPUP"
        // 以前のバージョンで保存した値を引き継ぐ
        return c.getSharedPreferences("player", Context.MODE_PRIVATE).getString("leave_action", null) ?: "POPUP"
    }

    fun setLeaveAction(c: Context, v: String) = prefs(c).edit { putString("leave_action", v) }

    // ジェスチャー
    fun gestureBrightness(c: Context) = bool(c, "gesture_brightness", true)
    fun gestureVolume(c: Context) = bool(c, "gesture_volume", true)
    fun gestureSeek(c: Context) = bool(c, "gesture_seek", true)
    fun gestureDoubleTap(c: Context) = bool(c, "gesture_double_tap", true)
    fun gestureLongPress(c: Context) = bool(c, "gesture_long_press", true)
    fun doubleTapMs(c: Context) = (string(c, "double_tap_seconds", "10").toIntOrNull() ?: 10) * 1000L

    // 自由な小窓の大きさ（長い方の辺、dp）と位置（px）。前回の場所に出す
    fun popupSizeDp(c: Context) = prefs(c).getInt("popup_size", PopupGeometry.DEFAULT_SIZE_DP)
    fun popupPosition(c: Context): Pair<Int, Int>? = prefs(c).takeIf { it.contains("popup_x") }
        ?.let { it.getInt("popup_x", 0) to it.getInt("popup_y", 0) }
    fun setPopupPlacement(c: Context, sizeDp: Int, x: Int, y: Int) =
        prefs(c).edit { putInt("popup_size", sizeDp); putInt("popup_x", x); putInt("popup_y", y) }

    /** 自由な小窓の許可を、再生画面で一度お願いした */
    fun popupPermissionAsked(c: Context) = bool(c, "popup_permission_asked", false)
    fun setPopupPermissionAsked(c: Context) = prefs(c).edit { putBoolean("popup_permission_asked", true) }

    /** 通知の許可（Android 13 以降）を一度聞いたか。断られても何度も聞かないように */
    fun notificationPermissionAsked(c: Context) = bool(c, "notification_permission_asked", false)
    fun setNotificationPermissionAsked(c: Context) = prefs(c).edit { putBoolean("notification_permission_asked", true) }

    // 新しい版の知らせ（AppUpdater）
    fun updateCheckOnStart(c: Context) = bool(c, "update_check", true)
    /** お試し版（まだみんなには配っていない版）も知らせる。試す人の端末だけオンにする */
    fun updateIncludePrerelease(c: Context) = bool(c, "update_prerelease", false)
    fun updateCheckedAt(c: Context) = prefs(c).getLong("update_checked_at", 0L)
    fun setUpdateCheckedAt(c: Context, time: Long) = prefs(c).edit { putLong("update_checked_at", time) }

    // 一覧
    fun videosGrid(c: Context) = bool(c, "videos_grid", false)
    fun setVideosGrid(c: Context, v: Boolean) = prefs(c).edit { putBoolean("videos_grid", v) }
    fun videosByFolder(c: Context) = bool(c, "videos_by_folder", false)
    fun setVideosByFolder(c: Context, v: Boolean) = prefs(c).edit { putBoolean("videos_by_folder", v) }
    /** 動画タブの並び順（"DATE"・"NAME"・"DURATION"） */
    fun videosSort(c: Context) = string(c, "videos_sort", "DATE")
    fun setVideosSort(c: Context, v: String) = prefs(c).edit { putString("videos_sort", v) }
    /** 音楽タブの表示（"SONGS"・"ALBUMS"・"ARTISTS"） */
    fun musicMode(c: Context) = string(c, "music_mode", "SONGS")
    fun setMusicMode(c: Context, v: String) = prefs(c).edit { putString("music_mode", v) }
}
