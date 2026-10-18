package com.ryose.videoplayer

import kotlin.random.Random

/**
 * 再生キューの決め方（PlaybackService から分けた）。次へ・前へ・シャッフル・リピート・終わったときにどうするか、キューの編集。
 * Android や VLC に依存しないように、曲・動画は items の番号（インデックス）だけで扱う。単体テストで確かめられる（QueueRulesTest）。
 * [order] は再生する順番（items の番号の並び）、[orderPos] は今そのどこを再生しているか
 */
object QueueRules {

    /** 「前へ」で、これより後まで再生していたら前の曲ではなく今の曲の頭に戻る */
    const val RESTART_AFTER_MS = 3000L

    /** 再生する順番を作る。シャッフルなら今のもの（[index]）を先頭にして、残りを混ぜる */
    fun buildOrder(size: Int, index: Int, shuffle: Boolean, random: Random = Random.Default): List<Int> =
        if (shuffle) listOf(index) + (0 until size).filter { it != index }.shuffled(random) else (0 until size).toList()

    /** 順番の中で [index] がどこにあるか（無ければ先頭） */
    fun positionOf(order: List<Int>, index: Int): Int = order.indexOf(index).coerceAtLeast(0)

    /** 次があるか（最後でも、全曲リピートで 2 つ以上あれば最初に戻れる） */
    fun hasNext(order: List<Int>, orderPos: Int, repeat: PlaybackService.Repeat, size: Int): Boolean =
        orderPos < order.lastIndex || (repeat == PlaybackService.Repeat.ALL && size > 1)

    /** 次へ進むときの、新しい順番と再生する位置 */
    data class Step(val order: List<Int>, val orderPos: Int)

    /**
     * 次へ。最後まで来たら、全曲リピートなら最初から（シャッフル中は並べ直す。今の曲がまた最初に来て 2 回続かないように）。
     * 進めなければ null
     */
    fun next(
        order: List<Int>, orderPos: Int, index: Int, size: Int,
        repeat: PlaybackService.Repeat, shuffle: Boolean, random: Random = Random.Default,
    ): Step? = when {
        orderPos < order.lastIndex -> Step(order, orderPos + 1)
        repeat == PlaybackService.Repeat.ALL && size > 0 -> {
            val newOrder = if (shuffle) {
                (0 until size).shuffled(random).let { o -> if (o.size > 1 && o.first() == index) o.drop(1) + o.first() else o }
            } else order
            Step(newOrder, 0)
        }
        else -> null
    }

    /**
     * 前へ。3 秒より後まで再生していた・最初の曲（全曲リピートでない）なら、今の曲の頭に戻る（null を返す）。
     * そうでなければ前の位置（最初の曲で全曲リピートなら最後）
     */
    fun previous(order: List<Int>, orderPos: Int, timeMs: Long, repeat: PlaybackService.Repeat): Int? = when {
        timeMs > RESTART_AFTER_MS || (orderPos == 0 && repeat != PlaybackService.Repeat.ALL) -> null
        orderPos > 0 -> orderPos - 1
        else -> order.lastIndex
    }

    /** 曲・動画が終わったときにすること */
    enum class EndAction { REPEAT_ONE, NEXT, STOP }

    /**
     * 曲・動画が終わったとき。1 曲リピートならもう一度、次があって進んでよいなら次へ、そうでなければ止める。
     * 動画は「終わったら次へ進む」設定（[autoAdvance]）がオフなら止める。曲はいつも次へ
     */
    fun onEnded(repeat: PlaybackService.Repeat, hasNext: Boolean, autoAdvance: Boolean, isAudio: Boolean): EndAction = when {
        repeat == PlaybackService.Repeat.ONE -> EndAction.REPEAT_ONE
        hasNext && (autoAdvance || isAudio) -> EndAction.NEXT
        else -> EndAction.STOP
    }

    /** 再生できなかったとき、次へ飛ばしてよいか（全部だめなら止める。終わったら止まる設定の動画は、関係ない次の動画へ飛ばない） */
    fun skipOnError(hasNext: Boolean, errorStreak: Int, size: Int, autoAdvance: Boolean, isAudio: Boolean): Boolean =
        hasNext && errorStreak < size && (autoAdvance || isAudio)

    /** キューに加える（items の番号 [start] から [count] 個）。[next] なら今の次に、そうでなければ最後に */
    fun enqueue(order: List<Int>, orderPos: Int, start: Int, count: Int, next: Boolean): List<Int> {
        val added = (start until start + count).toList()
        return if (next) order.take(orderPos + 1) + added + order.drop(orderPos + 1) else order + added
    }

    /** キューの [pos] 番目を外したあとの順番（外した items の番号より後ろは 1 つ詰める） */
    fun remove(order: List<Int>, pos: Int): List<Int> {
        val removed = order[pos]
        return order.filterIndexed { p, _ -> p != pos }.map { if (it > removed) it - 1 else it }
    }

    /** キューの順番を入れ替える */
    fun move(order: List<Int>, from: Int, to: Int): List<Int> = order.toMutableList().apply { add(to, removeAt(from)) }
}
