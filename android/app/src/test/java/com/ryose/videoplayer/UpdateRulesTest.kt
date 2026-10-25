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

    private fun release(code: Int, prerelease: Boolean, draft: Boolean = false) = UpdateRules.Release(
        "build-$code", prerelease, draft,
        listOf(Asset("Orbit-1.0.$code-32bit.apk", "https://example/$code/32", 1), Asset("Orbit-1.0.$code.apk", "https://example/$code/64", 2)),
    )

    @Test
    fun お試し版も含めていちばん新しい版() {
        // GitHub の一覧は新しい順とは限らないので、番号で選ぶ
        val releases = listOf(
            release(136, prerelease = false),
            release(142, prerelease = true),
            UpdateRules.Release("ios-build-3", true, false, emptyList()),
            release(140, prerelease = true),
            release(144, prerelease = true, draft = true),
        )
        val beta = UpdateRules.newest(releases, includePrerelease = true, is64Bit = true)!!
        assertEquals(142, beta.versionCode)
        assertTrue(beta.prerelease)
        assertEquals("https://example/142/64", beta.apkUrl)
        // お試し版を受け取らない端末は、配った版だけ
        val stable = UpdateRules.newest(releases, includePrerelease = false, is64Bit = false)!!
        assertEquals(136, stable.versionCode)
        assertFalse(stable.prerelease)
        assertEquals("https://example/136/32", stable.apkUrl)
        // 配った版の方が新しければそちら（お試し版を latest にしたあと）
        val promoted = UpdateRules.newest(listOf(release(142, false), release(140, true)), includePrerelease = true, is64Bit = true)!!
        assertEquals(142, promoted.versionCode)
        assertFalse(promoted.prerelease)
        // 合う版が無ければ null
        assertNull(UpdateRules.newest(listOf(release(150, true, draft = true)), includePrerelease = true, is64Bit = true))
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

    @Test
    fun ダウンロードを確かめるSHA256() {
        val hex = "731a952335756c81df61fd9731f1c8c1b675397f9c06e6764520de19bb3bebe3"
        assertEquals(hex, UpdateRules.sha256Of("sha256:${hex.uppercase()}"))
        // 無い・ほかの方式・長さが違うときは確かめない（空）
        assertEquals("", UpdateRules.sha256Of(""))
        assertEquals("", UpdateRules.sha256Of("sha1:abc"))
        assertEquals("", UpdateRules.sha256Of("sha256:abc"))
        val withSha = listOf(Asset("Orbit-1.0.129.apk", "https://example/64", 30_000_000, hex))
        assertEquals(hex, UpdateRules.pick("build-129", withSha, is64Bit = true)!!.apkSha256)
    }
}
