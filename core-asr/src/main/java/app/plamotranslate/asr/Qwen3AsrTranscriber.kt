package app.plamotranslate.asr

import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File

/**
 * Qwen3-ASR-0.6B（int8、sherpa-onnx）の認識器（2026-11-09、ドイツ語の会話のために足した。docs/04_会話翻訳.md「音声認識」）。
 * 30言語を聞き分ける。発話単位の一括認識で、デコーダーは自己回帰（0.6BのLLM）なのでSenseVoiceより重い。
 *
 * **言語の取り方**：Qwen3-ASRは文字の前に「language German<asr_text>」の形で言語を出すが、sherpa-onnx 2.0.1はこの接頭辞を
 * 取り除き、認識結果の言語（`lang`）も空のまま返す（`offline-recognizer-qwen3-asr-impl.cc`。masterでも同じ、2026-11-09確認）。
 * そこで、生成するトークンを2つに絞って（`max_new_tokens=2`）もう一度認識させる。接頭辞の `<asr_text>` まで届かないので
 * 取り除かれず、「language German」が文字として返る。言語名の多くは1トークンで、2トークンの名前は途中で切れる
 * （「language Canton」）ので、名前の頭で引く。PCで試験音声を流して、ドイツ語・日本語・フランス語・スペイン語・ロシア語・
 * 広東語で正しく取れ、無音と雑音では「language None」になることを確かめた（2026-11-09）。
 * このときsherpa-onnxが「Result is truncated」をエラーとしてログに出すが、意図したものなので害は無い。
 *
 * [AsrModel.QWEN3_ASR] の置き場に [FILES] を置く（docs/06_開発環境.md「モデルの置き場」）。
 */
internal class Qwen3AsrTranscriber private constructor(private val recognizer: OfflineRecognizer) : Transcriber {
    override fun transcribe(samples: FloatArray): Transcript {
        val text = cleanText(decode(samples, maxNewTokens = null))
        // 中身のない結果の言語は使わない（話し手の交代と誤ってカードを切らない）。言語を調べる分の認識も省ける
        if (text.isEmpty()) return Transcript("", null)
        return Transcript(text, language(samples))
    }

    /**
     * 言語だけを判定する（文字にするより安い。PCで5秒の音声が、文字にすると約1秒、言語だけなら約0.4秒）。
     * 長い発話は頭の [LANGUAGE_MAX_SAMPLES] だけで判定する。カードの言語は話し始めで決まり、長く渡しても時間が延びるだけのため。
     */
    override fun language(samples: FloatArray): String? {
        val head = if (samples.size > LANGUAGE_MAX_SAMPLES) samples.copyOf(LANGUAGE_MAX_SAMPLES) else samples
        return parseLanguage(decode(head, maxNewTokens = LANGUAGE_TOKENS))
    }

    private fun decode(samples: FloatArray, maxNewTokens: Int?): String {
        val stream = recognizer.createStream()
        try {
            maxNewTokens?.let { stream.setOption("max_new_tokens", it.toString()) }
            stream.acceptWaveform(samples, AsrEngine.SAMPLE_RATE)
            recognizer.decode(stream)
            return recognizer.getResult(stream).text
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()

    companion object {
        /** SenseVoiceと同じく、翻訳はGPUで動くので、CPUは認識に4スレッド使う。端末での速さは測っていない。 */
        private const val THREADS = 4

        /** 言語だけを調べるときに生成するトークンの数。「language」と言語名の1つ目のトークンまで。 */
        private const val LANGUAGE_TOKENS = 2

        /** 言語を判定するのに渡す長さの上限（6秒）。 */
        private const val LANGUAGE_MAX_SAMPLES = AsrEngine.SAMPLE_RATE * 6

        /**
         * Qwen3-ASR-0.6Bが聞ける30言語（モデルカード「Supported Languages」、2026-11-09確認）。
         * 値はQwen3-ASRが言語を出すときの名前（「language German」の German）。キーは言語のコード。
         * フィリピン語はモデルカードの表記どおり "fil" にする（会話では、PLaMoのタガログ語に当てる）。
         */
        val LANGUAGES = mapOf(
            "zh" to "Chinese", "en" to "English", "yue" to "Cantonese", "ar" to "Arabic", "de" to "German",
            "fr" to "French", "es" to "Spanish", "pt" to "Portuguese", "id" to "Indonesian", "it" to "Italian",
            "ko" to "Korean", "ru" to "Russian", "th" to "Thai", "vi" to "Vietnamese", "ja" to "Japanese",
            "tr" to "Turkish", "hi" to "Hindi", "ms" to "Malay", "nl" to "Dutch", "sv" to "Swedish",
            "da" to "Danish", "fi" to "Finnish", "pl" to "Polish", "cs" to "Czech", "fil" to "Filipino",
            "fa" to "Persian", "el" to "Greek", "hu" to "Hungarian", "mk" to "Macedonian", "ro" to "Romanian",
        )

        /** sherpa-onnx向けに書き出したint8版（2026-03-25版）のファイル。 */
        internal val FILES = listOf(
            "qwen3-asr/conv_frontend.onnx",
            "qwen3-asr/encoder.int8.onnx",
            "qwen3-asr/decoder.int8.onnx",
            "qwen3-asr/tokenizer/vocab.json",
            "qwen3-asr/tokenizer/merges.txt",
            "qwen3-asr/tokenizer/tokenizer_config.json",
        )

        fun load(modelDir: File): Qwen3AsrTranscriber = Qwen3AsrTranscriber(
            OfflineRecognizer(
                assetManager = null,
                config = OfflineRecognizerConfig(
                    // 特徴量（128次元のlog-mel）はsherpa-onnxがQwen3-ASR用に決めるので、featConfig は既定のままでよい
                    modelConfig = OfflineModelConfig(
                        qwen3Asr = OfflineQwen3AsrModelConfig(
                            convFrontend = File(modelDir, "qwen3-asr/conv_frontend.onnx").path,
                            encoder = File(modelDir, "qwen3-asr/encoder.int8.onnx").path,
                            decoder = File(modelDir, "qwen3-asr/decoder.int8.onnx").path,
                            tokenizer = File(modelDir, "qwen3-asr/tokenizer").path,
                        ),
                        tokens = "",
                        numThreads = THREADS,
                    ),
                ),
            ),
        )

        /**
         * 「language German」から言語のコードを引く。言語名が2トークンで途中で切れたもの（「language Canton」）は、名前の頭で引く。
         * 判定なし（「language None」）や、形の違う文字は null。
         */
        internal fun parseLanguage(text: String): String? {
            val t = text.trim()
            if (!t.startsWith("language")) return null
            val name = t.removePrefix("language").trim()
            if (name.length < 3) return null
            return LANGUAGES.entries.firstOrNull { it.value.equals(name, ignoreCase = true) }?.key
                ?: LANGUAGES.entries.firstOrNull { it.value.startsWith(name, ignoreCase = true) }?.key
        }

        /** 句読点や記号だけの結果は、何も話していないものとして空にする。 */
        internal fun cleanText(text: String): String {
            val t = text.trim()
            return if (t.any { it.isLetterOrDigit() }) t else ""
        }
    }
}
