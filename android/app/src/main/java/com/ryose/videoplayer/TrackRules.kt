package com.ryose.videoplayer

/**
 * 音声・字幕のトラックの選び方（日本語の音声があれば日本語・字幕オフ。ユーザーの希望）。
 * Android や VLC に依存しないように分けてあるので、単体テストで確かめられる（TrackRulesTest）。
 * 実際に VLC で選ぶのは TrackSelector
 */
object TrackRules {
    /** VLC のトラック（MediaPlayer.TrackDescription）のうち、判断に使うもの。id が -1 なら「オフ」 */
    data class Track(val id: Int, val name: String?)

    /** 選び直す内容（null なら今のまま） */
    data class Choice(val audio: Int? = null, val spu: Int? = null)

    /**
     * VLC のトラック名が日本語のものか。VLC は言語のコードを英語の名前にして「Track 1 - [Japanese]」のように付ける
     * （src/input/es_out.c の LanguageGetName。知らないコードはそのまま「[jp]」）。MKV などのトラック名に「日本語」と書かれていることもある
     */
    fun isJapanese(name: String?) = name != null && JAPANESE.containsMatchIn(name)

    fun isCommentary(name: String?) = name != null && COMMENTARY.containsMatchIn(name)

    private val JAPANESE = Regex("""japanese|日本語|\[(ja|jp|jpn|jap)]""", RegexOption.IGNORE_CASE)
    private val COMMENTARY = Regex("""commentary|コメンタリー|解説""", RegexOption.IGNORE_CASE)

    /**
     * @param chosenAudio・chosenSpu 再生画面のメニューで手で選んだもの（無ければ null）。こちらを優先して戻す
     * @param keepSubtitles 「字幕ファイルを追加」で自分で足した字幕がある（消さない）
     */
    fun decide(
        audio: List<Track>,
        currentAudio: Int,
        spu: List<Track>,
        currentSpu: Int,
        chosenAudio: Int?,
        chosenSpu: Int?,
        keepSubtitles: Boolean,
    ): Choice {
        var newAudio: Int? = null
        var newSpu: Int? = null
        if (chosenAudio != null && currentAudio != chosenAudio && audio.any { it.id == chosenAudio }) newAudio = chosenAudio
        if (chosenSpu != null && currentSpu != chosenSpu && (chosenSpu == -1 || spu.any { it.id == chosenSpu })) newSpu = chosenSpu
        // 音声解説（コメンタリー）は後回し
        val japanese = audio.filter { it.id != -1 && isJapanese(it.name) }.sortedBy { isCommentary(it.name) }
        if (japanese.isNotEmpty()) {
            if (chosenAudio == null && japanese.none { it.id == currentAudio }) newAudio = japanese.first().id
            if (chosenSpu == null && !keepSubtitles && currentSpu != -1) newSpu = -1
        }
        return Choice(newAudio, newSpu)
    }
}
