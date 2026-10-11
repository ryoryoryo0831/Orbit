package com.ryose.videoplayer

import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.graphics.ColorUtils
import androidx.core.view.WindowCompat

/**
 * 画面を端まで広げ、上下のシステムのバーを透明にして、画面の色をそのまま見せる。
 *
 * SystemBarStyle.auto だと、3 ボタンのナビゲーションでは OS が半透明の幕を重ねる
 * （isNavigationBarContrastEnforced が true になる）ので、画面のいちばん下が茶色っぽい帯に見えていた。
 * 明るい・暗いをはっきり指定すると幕は重ならない。ボタンの色はテーマに合わせて黒・白にする。
 * テーマの設定（明るい・暗い・端末に合わせる）は AppCompat が先に反映しているので、ここで見る uiMode はアプリの設定どおり。
 */
fun ComponentActivity.enableEdgeToEdgeWithoutScrim() {
    val dark = (window.decorView.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
    else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
    enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
}

/**
 * 下から出るメニュー（BottomSheetDialog）の背景を、ナビゲーションバーの下まで見せる。OS の幕も重ねない。
 * 幕が無くなるぶん、ナビゲーションバーのボタンの色は背景の明るさに合わせる。
 * show() より前に呼ぶ（BottomSheetDialog は窓に付いたときに、ナビゲーションバーが透明なら下まで広げる）。
 */
fun Dialog.clearNavigationBarScrim(backgroundColor: Int) {
    val window = window ?: return
    // Android 14 以前はテーマの不透明な色のままだと下まで広がらない（15 以降はもともと透明）
    @Suppress("DEPRECATION")
    window.navigationBarColor = Color.TRANSPARENT
    if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars =
        ColorUtils.calculateLuminance(backgroundColor) > 0.5
}
