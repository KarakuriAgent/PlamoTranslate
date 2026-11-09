package app.plamotranslate.asr

/**
 * 1回の認識の結果。[text] は認識した文字で、中身が無ければ（雑音の聞き違いなど）空。
 * [lang] は言語のコード（"ja"・"ko"・"en"・"zh"・"yue"・"de" など）。判定できない・中身が無ければ null。
 */
data class Transcript(val text: String, val lang: String?)

/**
 * 認識器：16kHz・モノラルの音声を、まとめて1回で文字と言語にする（発話単位の一括認識。docs/04_会話翻訳.md「音声認識」）。
 * 発話の区切りや途中結果は [SegmentingAsr] が受け持ち、これを繰り返し呼ぶ。モデルごとの設定と後処理はこの実装の中に閉じる。
 * 呼び出しはすべて同じ1本のスレッドから来る。
 */
interface Transcriber : AutoCloseable {
    fun transcribe(samples: FloatArray): Transcript

    /**
     * 言語だけを判定する。区切り（話し手の交代の見張り）で、短い音声の言語を何度も調べるときに使う。
     * 文字にするより安く言語が分かるモデルは、上書きして速くする。
     */
    fun language(samples: FloatArray): String? = transcribe(samples).lang
}
