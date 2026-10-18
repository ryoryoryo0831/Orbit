package com.ryose.videoplayer

/**
 * A-B リピートの決め方（PlaybackService から分けた）。
 * Android や VLC に依存しないように分けてあるので、単体テストで確かめられる（AbRulesTest）。
 * 実際に VLC の位置を動かす・ファイルごとに覚えておくのは AbRepeater
 */
object AbRules {
    /** A と B の最小の間隔（ミリ秒） */
    const val MIN_GAP_MS = 500L

    /** B をずらしたときは、ずらしたところが聞けるように、その何ミリ秒前から再生するか */
    const val B_PREVIEW_MS = 2000L

    /** A・B の位置（決まっていなければ -1） */
    data class Range(val a: Long = -1, val b: Long = -1) {
        /** 両方決まっていて、くり返している */
        val isLoop: Boolean get() = a >= 0 && b > a
    }

    /** 操作の結果。[seekTo] があればその位置へ移る。[changed] が false なら A・B は今のまま（お知らせだけ） */
    data class Result(val range: Range, val message: String, val seekTo: Long? = null, val changed: Boolean = true)

    /** A を今の位置 [t] にする。B より後になったら B は外す */
    fun setA(r: Range, t: Long): Result {
        val time = t.coerceAtLeast(0)
        return when {
            r.b >= 0 && r.b <= time + MIN_GAP_MS ->
                Result(Range(time, -1), "A を ${formatAbTime(time)} にしました（B は A より後にもう一度決めてください）")
            r.b > time -> Result(Range(time, r.b), "A を ${formatAbTime(time)} にしました")
            else -> Result(Range(time, r.b), "A を ${formatAbTime(time)} にしました。終わりの位置で B を押してください")
        }
    }

    /** B を今の位置 [t] にして、A に戻ってくり返しを始める。A が無い・A より前なら設定しない */
    fun setB(r: Range, t: Long): Result {
        val time = t.coerceAtLeast(0)
        return when {
            r.a < 0 -> Result(r, "先に、くり返しの始めの位置で A を押してください", changed = false)
            time <= r.a + MIN_GAP_MS -> Result(r, "B は A（${formatAbTime(r.a)}）より後の位置で押してください", changed = false)
            else -> Result(Range(r.a, time), "${formatAbTime(r.a)} 〜 ${formatAbTime(time)} をくり返します", seekTo = r.a)
        }
    }

    /**
     * A か B を少しずらす（[deltaMs] がマイナスなら前へ）。ずらしたところが聞けるように、
     * A ならその位置から、B ならその 2 秒前から再生する。ずらせないときは null
     * @param lengthMs 曲・動画の長さ（分からなければ 0 以下）
     */
    fun nudge(r: Range, isA: Boolean, deltaMs: Long, lengthMs: Long): Result? {
        val len = lengthMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        return if (isA) {
            if (r.a < 0) return null
            val max = if (r.b >= 0) r.b - MIN_GAP_MS else len
            val v = maxOf(0L, minOf(r.a + deltaMs, max))
            if (v == r.a) return null
            Result(Range(v, r.b), "A：${formatAbTime(v)}", seekTo = v)
        } else {
            if (r.b < 0) return null
            val v = maxOf(r.a + MIN_GAP_MS, minOf(r.b + deltaMs, len))
            if (v == r.b) return null
            Result(Range(r.a, v), "B：${formatAbTime(v)}", seekTo = maxOf(r.a, v - B_PREVIEW_MS))
        }
    }

    /** 覚えていた区間 [saved] を戻すときに、どこから始めるか（区間の外から始まるときは A から） */
    fun restoredStart(saved: Range, startMs: Long): Long =
        if (saved.isLoop && (startMs < saved.a || startMs >= saved.b)) saved.a else startMs

    /** 再生位置が [t] になったときに戻る位置（B を過ぎたら A へ。くり返していなければ null） */
    fun loopBackTo(r: Range, t: Long): Long? = if (r.isLoop && t >= r.b) r.a else null

    /** メニューのマス目に出す短い表示（「1:23〜2:45」「A 1:23」）。使っていなければ null */
    fun label(r: Range): String? = when {
        r.isLoop -> "${formatTime(r.a)}〜${formatTime(r.b)}"
        r.a >= 0 -> "A ${formatTime(r.a)}"
        else -> null
    }
}
