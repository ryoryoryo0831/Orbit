package com.ryose.videoplayer

/**
 * 新しい版があるかの決め方（AppUpdater から分けた。Android に依存しないので単体テストで確かめられる：UpdateRulesTest）。
 * 公開版は GitHub の Releases に、タグ「build-N」、APK「Orbit-1.0.N.apk」（64 ビット）・「Orbit-1.0.N-32bit.apk」で置いてある。
 * N はアプリの versionCode と同じ（.github/workflows/android.yml）
 */
object UpdateRules {
    /** Releases の添付ファイル */
    data class Asset(val name: String, val url: String, val size: Long)

    /** 入れられる新しい版 */
    data class Update(val versionCode: Int, val versionName: String, val apkName: String, val apkUrl: String, val apkSize: Long)

    /** タグ「build-129」から版の番号（129）。Android 版のタグでなければ null（iPhone 版は「ios-build-N」） */
    fun versionCodeOf(tag: String): Int? = if (tag.startsWith("build-")) tag.removePrefix("build-").toIntOrNull() else null

    /**
     * 公開版の中から、この端末に合う APK を選ぶ。[is64Bit] はアプリが 64 ビットで動いているか
     * （テレビは CPU が 64 ビットでも 32 ビット版しか動かないので、端末ではなく今のアプリで決める）
     */
    fun pick(tag: String, assets: List<Asset>, is64Bit: Boolean): Update? {
        val code = versionCodeOf(tag) ?: return null
        val name = "1.0.$code"
        val want = if (is64Bit) "Orbit-$name.apk" else "Orbit-$name-32bit.apk"
        val asset = assets.find { it.name == want } ?: return null
        return Update(code, name, asset.name, asset.url, asset.size)
    }

    /** 今の版（[currentVersionCode]）より新しいか */
    fun isNewer(update: Update, currentVersionCode: Long): Boolean = update.versionCode > currentVersionCode

    /** 起動したときに確かめる間隔（GitHub に何度も聞かないように） */
    const val CHECK_INTERVAL_MS = 12 * 60 * 60 * 1000L

    fun shouldCheck(lastCheckedAt: Long, now: Long): Boolean = now - lastCheckedAt >= CHECK_INTERVAL_MS || now < lastCheckedAt
}
