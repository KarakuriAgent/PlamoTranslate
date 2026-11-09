package app.plamotranslate.asr

import com.k2fsa.sherpa.onnx.OfflineCanaryModelConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.io.File

/**
 * NVIDIAのNeMoの認識モデル（Canary-180M-flash、Parakeet TDT 0.6B v3。どちらもsherpa-onnxのint8版）の認識器
 * （2026-11-09、補助のモデルとして足した。docs/04_会話翻訳.md「音声認識」）。
 * どちらも言語を返さないので、相手を固定したときだけ使い、言語は呼び出し側が渡す（[transcribe] の lang）。
 * 発話単位の一括認識で、句読点と大文字・小文字の付いた文を返す。
 *
 * - Canaryは、認識する言語を `src_lang`・`tgt_lang` で指定する（認識なので同じ言語）。違う言語を指定すると訳してしまう
 *   （ドイツ語の音声に en を指定すると英訳が返った。2026-11-09、PCで確かめた）。言語はストリームごとに指定できる。
 *   句読点は `use_pnc` で付ける。句読点の前に空白が入る（「zu sein .」）ので取り除く。
 * - Parakeetは言語を指定しない（25言語を自分で聞き分ける）。
 *
 * 特徴量の次元（どちらも128）は、sherpa-onnxがモデルから読むので、featConfig は既定のままでよい（PCで80を渡しても同じ結果）。
 */
internal class NemoTranscriber private constructor(
    private val recognizer: OfflineRecognizer,
    /** 言語を指定できる言語（Canary）。指定しないモデル（Parakeet）は空。 */
    private val selectable: Set<String>,
) : Transcriber {
    override fun transcribe(samples: FloatArray): Transcript = decode(samples, null)

    override fun transcribe(samples: FloatArray, lang: String): Transcript = decode(samples, lang)

    private fun decode(samples: FloatArray, lang: String?): Transcript {
        val stream = recognizer.createStream()
        try {
            if (lang != null && lang in selectable) {
                stream.setOption("src_lang", lang)
                stream.setOption("tgt_lang", lang)
            }
            stream.acceptWaveform(samples, AsrEngine.SAMPLE_RATE)
            recognizer.decode(stream)
            val text = cleanText(recognizer.getResult(stream).text)
            return Transcript(text, if (text.isEmpty()) null else lang)
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()

    companion object {
        /** SenseVoiceと同じく、翻訳はGPUで動くので、CPUは認識に4スレッド使う。端末での速さは測っていない。 */
        private const val THREADS = 4

        /** Canary-180M-flashが聞ける言語（モデルカード、2026-11-09確認）。 */
        val CANARY_LANGUAGES = setOf("en", "de", "es", "fr")

        /** Parakeet TDT 0.6B v3が聞ける欧州の25言語（モデルカード、2026-11-09確認）。 */
        val PARAKEET_LANGUAGES = setOf(
            "bg", "hr", "cs", "da", "nl", "en", "et", "fi", "fr", "de", "el", "hu", "it",
            "lv", "lt", "mt", "pl", "pt", "ro", "sk", "sl", "es", "sv", "ru", "uk",
        )

        private const val CANARY_DIR = "canary-180m-flash"
        private const val PARAKEET_DIR = "parakeet-tdt-0.6b-v3"

        /** sherpa-onnxの作者が書き出したint8版のファイル。置き場所はアプリの ModelCatalog と合わせる。 */
        internal val CANARY_FILES = listOf("encoder.int8.onnx", "decoder.int8.onnx", "tokens.txt").map { "$CANARY_DIR/$it" }
        internal val PARAKEET_FILES =
            listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt").map { "$PARAKEET_DIR/$it" }

        fun loadCanary(modelDir: File): NemoTranscriber {
            val dir = File(modelDir, CANARY_DIR)
            return NemoTranscriber(
                OfflineRecognizer(
                    assetManager = null,
                    config = OfflineRecognizerConfig(
                        modelConfig = OfflineModelConfig(
                            canary = OfflineCanaryModelConfig(
                                encoder = File(dir, "encoder.int8.onnx").path,
                                decoder = File(dir, "decoder.int8.onnx").path,
                                // 既定の言語。実際はストリームごとに相手の言語を指定する
                                srcLang = "en",
                                tgtLang = "en",
                                usePnc = true,
                            ),
                            tokens = File(dir, "tokens.txt").path,
                            numThreads = THREADS,
                        ),
                    ),
                ),
                CANARY_LANGUAGES,
            )
        }

        fun loadParakeet(modelDir: File): NemoTranscriber {
            val dir = File(modelDir, PARAKEET_DIR)
            return NemoTranscriber(
                OfflineRecognizer(
                    assetManager = null,
                    config = OfflineRecognizerConfig(
                        modelConfig = OfflineModelConfig(
                            transducer = OfflineTransducerModelConfig(
                                encoder = File(dir, "encoder.int8.onnx").path,
                                decoder = File(dir, "decoder.int8.onnx").path,
                                joiner = File(dir, "joiner.int8.onnx").path,
                            ),
                            tokens = File(dir, "tokens.txt").path,
                            numThreads = THREADS,
                            // TDT（token-and-duration transducer）はNeMoの形として読ませる
                            modelType = "nemo_transducer",
                        ),
                    ),
                ),
                emptySet(),
            )
        }

        /** 句読点の前の空白を取り、句読点や記号だけの結果は何も話していないものとして空にする。 */
        internal fun cleanText(text: String): String {
            val t = SPACE_BEFORE_PUNCTUATION.replace(text.trim(), "$1")
            return if (t.any { it.isLetterOrDigit() }) t else ""
        }

        private val SPACE_BEFORE_PUNCTUATION = Regex("\\s+([.,!?;:])")
    }
}
