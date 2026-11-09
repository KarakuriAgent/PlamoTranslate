package app.plamotranslate.asr

/**
 * 話し終わった1つの発話（docs/04_会話翻訳.md）。
 * [lang] は認識器が判定した言語のコード（"ja"・"ko"・"en"・"zh" など）。判定できなければ null。
 */
data class Utterance(
    val text: String,
    val lang: String?,
    /** 会話の開始からの発話の始まり（ミリ秒）。 */
    val startMs: Long,
    val durationMs: Long,
    /** 発話の終わりを検出してから認識が終わるまでの時間。 */
    val recognizeMs: Long,
)

/** 音声認識から届く出来事。 */
sealed interface AsrEvent {
    /** 話している最中の聞き取り。同じ発話の間は、届くたびに前の途中結果を置き換える。 */
    data class Partial(val text: String, val lang: String?) : AsrEvent

    /** 話し終わった発話の確定結果。直前の途中結果を置き換える。 */
    data class Final(val utterance: Utterance) : AsrEvent

    /** 話し始めたとき（[speaking] = true）と、話し終わったか音声が止んだとき（false）。 */
    data class Speaking(val speaking: Boolean) : AsrEvent
}

/**
 * 音声認識の差し替え口（CLAUDE.md「差し替え可能に作る」）。
 * 呼び出し側は1本のスレッドから順に音声を渡し、返ってきた出来事を順に処理する。
 * 作るのは [AsrModel.load]。今の実装はどのモデルも、VADで区切って認識器で文字にする [SegmentingAsr]（2026-11-09）。
 */
interface AsrEngine : AutoCloseable {
    /**
     * 中国語・広東語を、日本語と別の話し手の言語として区別するか。会話の相手を中国語にしたときだけ true にする
     * （2026-11-08。false なら漢字の多い日本語の聞き違いとして日本語と同じに扱い、話し手の交代とみなさない）。
     * 聞いている最中に別のスレッドから変えてよい。
     */
    var separateChinese: Boolean

    /** 16kHz・モノラル・-1〜1のfloat。 */
    fun accept(samples: FloatArray): List<AsrEvent>

    /** 残っている音声を発話として確定させる（停止時・音声ファイルの終わり）。 */
    fun flush(): List<AsrEvent>

    /** 途中の音声を捨てて、新しい会話として始め直す。 */
    fun reset()

    companion object {
        const val SAMPLE_RATE = 16_000

        /**
         * 話し終わりとみなす無音の長さ（秒）。2026-11-06、作者の要望で0.5秒から2秒にした
         * （途中訳が見えるので確定を急がず、言いよどみや息継ぎでカードが切れないほうを選んだ）。
         */
        const val END_SILENCE_SECONDS = 2.0f
    }
}
