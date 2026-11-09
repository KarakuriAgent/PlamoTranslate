package app.plamotranslate.asr

import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * 話しているかの判定（VAD）。[SegmentingAsr] は「話しているか」と「文の終わり」の合図だけに使い、
 * 認識する音声は自分でためた分から取る。単体テストで差し替えられるよう、sherpa-onnxの型を表に出さない。
 */
internal interface VoiceDetector : AutoCloseable {
    /** 音声を渡す。返すのは、今話しているかと、この音声までに文（無音で区切られた単位）が終わったか。 */
    fun accept(samples: FloatArray): VoiceActivity

    /** 残っている音声を文として終わらせる（停止時）。 */
    fun flush()

    fun reset()
}

internal data class VoiceActivity(val speaking: Boolean, val phraseEnded: Boolean)

/**
 * Silero VAD（sherpa-onnx）。どの認識モデルでも同じものを使う。[modelDir] に `silero_vad.onnx` を置く。
 */
internal class SileroVad private constructor(private val vad: Vad) : VoiceDetector {
    override fun accept(samples: FloatArray): VoiceActivity {
        vad.acceptWaveform(samples)
        val speaking = vad.isSpeechDetected()
        val phraseEnded = !vad.empty()
        // VADが切り出した音声は使わない（区切りの合図として読むだけ）
        while (!vad.empty()) vad.pop()
        return VoiceActivity(speaking, phraseEnded)
    }

    override fun flush() {
        vad.flush()
        while (!vad.empty()) vad.pop()
    }

    override fun reset() = vad.reset()

    override fun close() = vad.release()

    companion object {
        const val FILE = "silero_vad.onnx"

        fun load(modelDir: File): SileroVad = SileroVad(
            Vad(
                assetManager = null,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = File(modelDir, FILE).path,
                        threshold = 0.5f,
                        // VADは0.6秒の無音で「文」を区切る。カードの確定（無音2秒、言語の交代）は SegmentingAsr.accept で決める
                        minSilenceDuration = SegmentingAsr.PHRASE_SILENCE_SECONDS,
                        minSpeechDuration = 0.25f,
                        windowSize = 512,
                        // 長い発話はVADでは区切らず、自分で静かなところを探して区切る（SegmentingAsr）。
                        // VADに区切らせると、次の話し始めを検出し直す間の音声が抜けた
                        maxSpeechDuration = 600f,
                    ),
                    sampleRate = AsrEngine.SAMPLE_RATE,
                    numThreads = 1,
                ),
            ),
        )
    }
}
