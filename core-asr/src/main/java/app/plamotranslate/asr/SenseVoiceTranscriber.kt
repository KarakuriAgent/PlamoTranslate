package app.plamotranslate.asr

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import java.io.File

/**
 * SenseVoice Smallの認識器（docs/04_会話翻訳.md）。発話単位の一括認識で、言語タグ（日・韓・英・中・広東語）も返す。
 * 区切りは [SegmentingAsr] が受け持つ（2026-11-09に SenseVoiceAsr から区切りを切り出し、SenseVoice固有の設定と後処理だけ残した）。
 *
 * [AsrModel.SENSE_VOICE] の置き場に `sense-voice/model.int8.onnx`、`sense-voice/tokens.txt` を置く（docs/06_開発環境.md「モデルの置き場」）。
 */
internal class SenseVoiceTranscriber private constructor(private val recognizer: OfflineRecognizer) : Transcriber {
    override fun transcribe(samples: FloatArray): Transcript {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, AsrEngine.SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val parsed = parseLang(result.lang)
            val text = dropFillers(removeCjkSpaces(result.text.trim(), parsed), parsed)
            // 中身のない結果（雑音の聞き違い）の言語は使わない。使うと話し手の交代と誤ってカードが切れる
            val lang = if (text.isEmpty()) null else parsed
            return Transcript(text, lang)
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()

    companion object {
        /**
         * SenseVoiceは非自己回帰で軽い。翻訳はGPUで動くので、CPUは認識に4スレッド使う
         * （REDMAGICのCPUクロック上限は docs/00_現在地.md）。
         */
        private const val THREADS = 4

        /** SenseVoiceが聞き分けられる言語。 */
        val LANGUAGES = setOf("ja", "ko", "en", "zh", "yue")

        internal val FILES = listOf("sense-voice/model.int8.onnx", "sense-voice/tokens.txt")

        fun load(modelDir: File): SenseVoiceTranscriber = SenseVoiceTranscriber(
            OfflineRecognizer(
                assetManager = null,
                config = OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = File(modelDir, "sense-voice/model.int8.onnx").path,
                            language = "auto",
                            // 句読点を付け、数字を書き言葉にする。翻訳に渡す文として自然になる
                            useInverseTextNormalization = true,
                        ),
                        tokens = File(modelDir, "sense-voice/tokens.txt").path,
                        numThreads = THREADS,
                    ),
                ),
            ),
        )

        /** SenseVoiceの言語タグ（例 "<|ja|>"）からコードを取り出す。判定なし（"<|nospeech|>" など）は null。 */
        internal fun parseLang(tag: String?): String? {
            val code = tag?.removePrefix("<|")?.removeSuffix("|>")?.trim()?.lowercase()
            return code?.takeIf { it in LANGUAGES }
        }

        /**
         * SenseVoiceは語の区切りに空白を入れることがある（「うち の 中学 は」。2026-11-06、途中結果でよく出た）。
         * 日本語・中国語は語の間に空白を置かないので、英数字どうしの間の空白だけ残して取り除く。
         * 韓国語は語の間の空白（分かち書き）が正しい場合もあるので触らない。
         */
        internal fun removeCjkSpaces(text: String, lang: String?): String {
            if (lang !in setOf("ja", "zh", "yue")) return text
            return SPACES.replace(text) { m ->
                val before = text.getOrNull(m.range.first - 1)
                val after = text.getOrNull(m.range.last + 1)
                if (before.isAsciiWord() && after.isAsciiWord()) " " else ""
            }
        }

        private val SPACES = Regex("\\s+")

        /**
         * 雑音や息をSenseVoiceが聞き違えた結果を取り除く。中身が残らなければ空文字を返す。
         * 2026-11-06、作者の指摘「Yeah. や . や I. のような幻覚で翻訳が切られるのが不快」。
         * こうした結果は、それだけでカードになるうえ、英語と判定されて日本語の文の間に挟まると、
         * 話し手の交代とみなされてカードが途中で切れていた。
         * - 句読点だけ、またはあいづち・つなぎの語（[FILLERS]）だけの結果（「Yeah. Yeah.」も）は、何も話していないとみなす。
         * - 日本語・韓国語・中国語の文の頭と末尾に付いた英語のあいづちは取り除く（文の間の雑音が混ざったもの）。
         * 「Yes」「OK」「No」は相手の本当の返事として多いので消さない。英語で「Yeah」だけ言ったカードは消えるが、
         * 会話の中身を運ばないので、誤って切られる害のほうを重く見た。
         */
        internal fun dropFillers(text: String, lang: String?): String {
            var t = text
            if (lang != "en") {
                t = LEADING_FILLERS.replace(t, "")
                t = TRAILING_FILLERS.replace(t, "")
            }
            val words = t.lowercase().split(NON_WORD).filter { it.isNotEmpty() }
            if (words.all { it in FILLERS }) return ""
            return t.trim()
        }

        private val FILLERS = setOf(
            "yeah", "i", "oh", "uh", "um", "umm", "hmm", "hm", "ah", "mm", "mhm", "the", "you", "so", "and",
            "嗯", "啊", "呃", "哦",
        )
        /** 文の頭と末尾から取り除く英語のあいづち。「you」「so」などは英語の文の一部（thank you）のこともあるので含めない。 */
        private const val EN_FILLERS = "yeah|i|oh|uh|um|umm|hmm|hm|ah|mm|mhm"
        private val LEADING_FILLERS =
            Regex("^(?:\\s*(?i:$EN_FILLERS)(?![A-Za-z])[\\p{Punct}、。]*\\s*)+")
        private val TRAILING_FILLERS =
            Regex("(?:(?<![A-Za-z])(?i:$EN_FILLERS)(?![A-Za-z])[\\p{Punct}、。]*\\s*)+$")

        /** 語に分けるときの区切り（文字・数字以外）。 */
        private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")

        private fun Char?.isAsciiWord() = this != null && (this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9')
    }
}
