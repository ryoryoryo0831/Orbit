package com.ryose.videoplayer

import com.ryose.videoplayer.UpdateRules.Asset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateRulesTest {
    private val assets = listOf(
        Asset("Orbit-1.0.129-32bit.apk", "https://example/32", 27_000_000),
        Asset("Orbit-1.0.129.apk", "https://example/64", 30_000_000),
    )

    @Test
    fun タグから版の番号() {
        assertEquals(129, UpdateRules.versionCodeOf("build-129"))
        // iPhone 版のタグは Android の版ではない
        assertNull(UpdateRules.versionCodeOf("ios-build-2"))
        assertNull(UpdateRules.versionCodeOf("build-x"))
    }

    @Test
    fun 端末に合うAPKを選ぶ() {
        val phone = UpdateRules.pick("build-129", assets, is64Bit = true)!!
        assertEquals("https://example/64", phone.apkUrl)
        assertEquals("1.0.129", phone.versionName)
        // テレビは 32 ビット版
        val tv = UpdateRules.pick("build-129", assets, is64Bit = false)!!
        assertEquals("https://example/32", tv.apkUrl)
        assertEquals(27_000_000L, tv.apkSize)
        // 合う APK が無ければ null
        assertNull(UpdateRules.pick("build-130", assets, is64Bit = true))
    }

    @Test
    fun 新しいか_確かめる間隔() {
        val u = UpdateRules.pick("build-129", assets, true)!!
        assertTrue(UpdateRules.isNewer(u, 127))
        assertFalse(UpdateRules.isNewer(u, 129))
        assertTrue(UpdateRules.shouldCheck(lastCheckedAt = 0, now = UpdateRules.CHECK_INTERVAL_MS))
        assertFalse(UpdateRules.shouldCheck(lastCheckedAt = 1_000, now = 1_000 + UpdateRules.CHECK_INTERVAL_MS - 1))
        // 端末の時計が戻ったときも確かめる
        assertTrue(UpdateRules.shouldCheck(lastCheckedAt = 5_000, now = 1_000))
    }
}
