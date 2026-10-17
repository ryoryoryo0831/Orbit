package com.ryose.videoplayer

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * drawable の XML の書き方の確認。
 * selector などの中で、テーマの値を画像として指す（android:drawable="?attr/..."）と、
 * Android 11 では読めずにアプリが起動しなくなった（1.0.82 のテレビ）。ビルドの段階で見つける
 */
class DrawableResourcesTest {

    private fun resDir(): File {
        // テストは app のフォルダで動くが、念のためリポジトリの上からも探す
        return listOf(File("src/main/res"), File("app/src/main/res"), File("android/app/src/main/res"))
            .firstOrNull { it.isDirectory } ?: error("res のフォルダが見つかりません")
    }

    @Test
    fun drawableの中でテーマの値を画像として指していない() {
        val bad = resDir().listFiles { f -> f.isDirectory && f.name.startsWith("drawable") }.orEmpty()
            .flatMap { dir -> dir.listFiles { f -> f.extension == "xml" }.orEmpty().toList() }
            .filter { f -> Regex("""android:drawable\s*=\s*"\?""").containsMatchIn(f.readText()) }
            .map { it.path }
        assertTrue("テーマの値を画像として指している drawable：$bad", bad.isEmpty())
    }
}
