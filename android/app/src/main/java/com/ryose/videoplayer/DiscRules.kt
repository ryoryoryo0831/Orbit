package com.ryose.videoplayer

/**
 * DVD・Blu-ray の ISO で、どのタイトルを再生するか・メニューへ移るかの判断。
 * Android や VLC に依存しないように分けてあるので、単体テストで確かめられる（DiscRulesTest）。
 * 実際にタイトルを切り替えたり待ったりするのは DiscController
 */
object DiscRules {
    /** VLC のタイトル（MediaPlayer.Title）のうち、判断に使うものだけ */
    data class Title(val isMenu: Boolean, val durationMs: Long)

    /** メニューのタイトル（VLC ではタイトル 0「DVD Menu」。無ければ -1） */
    fun menuIndex(titles: List<Title>): Int = titles.indexOfFirst { it.isMenu }

    fun isInMenu(titles: List<Title>, current: Int): Boolean = current in titles.indices && titles[current].isMenu

    /** 本編（メニューではない、いちばん長いタイトル。無ければ null） */
    fun mainTitle(titles: List<Title>): Int? = titles.indices.filter { !titles[it].isMenu }.maxByOrNull { titles[it].durationMs }

    /** メニューより先に流れる宣伝などを飛ばす処理の、次の一手 */
    enum class MenuJump {
        /** まだ待つ（ディスクを開いている・メニューの中＝著作権の注意など） */
        WAIT,
        /** メニュー以外のタイトルが流れているので、メニューへ移る */
        JUMP,
        /** やめる（メニューへ移れた・何度試しても移れない） */
        DONE,
    }

    /** 宣伝などを飛ばしてメニューへ移ろうとする回数 */
    const val MAX_MENU_JUMPS = 3

    /**
     * @param jumps これまでにメニューへ移ろうとした回数
     */
    fun menuJumpStep(titles: List<Title>, current: Int, jumps: Int): MenuJump {
        if (current !in titles.indices) return MenuJump.WAIT
        val inTitle = menuIndex(titles) >= 0 && !titles[current].isMenu
        return when {
            // 移ろうとしたあとにメニューになった＝移れた
            !inTitle && jumps > 0 -> MenuJump.DONE
            inTitle && jumps >= MAX_MENU_JUMPS -> MenuJump.DONE
            inTitle -> MenuJump.JUMP
            else -> MenuJump.WAIT
        }
    }

    /** メニューに映像が無いまま、この秒数たったら本編にする */
    const val EMPTY_MENU_SECONDS = 6

    /**
     * メニューに映像が無い ISO（本編だけを取り出したものなど）を見分けるための、1 秒ごとの数え方。
     * @return 映像が無いままメニューにいる秒数。映像が出てきたら null（見るのをやめる）
     */
    fun emptyMenuSeconds(inMenu: Boolean, videoTracks: Int, previous: Int): Int? = when {
        videoTracks > 0 -> null
        // ディスクを開くまで（NAS では CSS の鍵の取得に 15 秒ほどかかることがある）はメニューかどうか分からないので数えない
        inMenu -> previous + 1
        else -> 0
    }
}
