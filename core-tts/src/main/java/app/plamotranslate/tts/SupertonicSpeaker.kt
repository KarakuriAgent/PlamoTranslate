package app.plamotranslate.tts

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Supertonic 3（Supertone、約99Mパラメーター、int8）をsherpa-onnxで動かす読み上げ（2026-11-08）。
 * 31言語を読めるが、中国語は含まない（[LANGUAGES]）。モデルは [dir] に置いた7つのファイルで、
 * 初めて読むときに読み込み、閉じるまで持っておく。
 *
 * 音は作ったそばから流す（sherpa-onnxは文のまとまりごとにコールバックを呼ぶ）。長い訳文でも最初の音が早く出る。
 */
class SupertonicSpeaker(
    private val dir: File,
    /** 声（voice.bin の何番目の声か）。 */
    var voice: Int = 0,
    /** 読む速さ（1が標準）。 */
    var speed: Float = 1f,
) : Speaker {
    private var tts: OfflineTts? = null

    override fun supports(lang: String): Boolean = lang.substringBefore('-') in LANGUAGES

    /** 声の数。読み込んでいなければ読み込む。 */
    suspend fun voices(): Int = withContext(dispatcher) { engine().numSpeakers() }

    override suspend fun speak(text: String, lang: String) = withContext(dispatcher) {
        val engine = engine()
        val job = currentCoroutineContext().job
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(engine.sampleRate())
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        var frames = 0L
        try {
            track.play()
            val start = System.nanoTime()
            val config = GenerationConfig(
                sid = voice.coerceIn(0, maxOf(0, engine.numSpeakers() - 1)),
                speed = speed,
                numSteps = NUM_STEPS,
                extra = mapOf("lang" to lang.substringBefore('-')),
            )
            engine.generateWithConfigAndCallback(text, config) { samples ->
                if (!job.isActive) return@generateWithConfigAndCallback 0
                if (frames == 0L) Log.i(TAG, "読み上げ: 最初の音まで ${(System.nanoTime() - start) / 1_000_000}ms")
                track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                frames += samples.size
                1
            }
            // 書き終えた分が鳴り終わるまで待つ
            while (job.isActive && track.playbackHeadPosition < frames) delay(30)
        } finally {
            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
                track.pause()
                track.flush()
            }
            track.release()
        }
    }

    /** 推論のスレッドで呼ぶ。 */
    private fun engine(): OfflineTts = tts ?: run {
        val start = System.nanoTime()
        val missing = FILES.filterNot { File(dir, it).exists() }
        if (missing.isNotEmpty()) throw SpeechException("読み上げのモデルがありません（${missing.joinToString()}）")
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = File(dir, "duration_predictor.int8.onnx").path,
                    textEncoder = File(dir, "text_encoder.int8.onnx").path,
                    vectorEstimator = File(dir, "vector_estimator.int8.onnx").path,
                    vocoder = File(dir, "vocoder.int8.onnx").path,
                    ttsJson = File(dir, "tts.json").path,
                    unicodeIndexer = File(dir, "unicode_indexer.bin").path,
                    voiceStyle = File(dir, "voice.bin").path,
                ),
                numThreads = NUM_THREADS,
            ),
        )
        OfflineTts(assetManager = null, config = config).also {
            tts = it
            Log.i(TAG, "読み上げのモデルを読み込みました（${(System.nanoTime() - start) / 1_000_000}ms、声 ${it.numSpeakers()}）")
        }
    }

    override fun close() {
        tts?.release()
        tts = null
    }

    companion object {
        private const val TAG = "PlamoTranslate"

        /** Supertonic 3が読める言語（モデルのREADMEの31言語）。中国語は無い。 */
        val LANGUAGES = setOf(
            "en", "ko", "ja", "ar", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hi", "hr", "hu",
            "id", "it", "lt", "lv", "nl", "pl", "pt", "ro", "ru", "sk", "sl", "sv", "tr", "uk", "vi",
        )

        /** [dir] に置くファイル。 */
        val FILES = listOf(
            "duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx",
            "vocoder.int8.onnx", "tts.json", "unicode_indexer.bin", "voice.bin",
        )

        /** 音を作る段数。sherpa-onnxの例と同じ8。減らすと速く、粗くなる。 */
        private const val NUM_STEPS = 8

        /** 読み上げは翻訳と同時には走らないことが多いが、翻訳のスレッドと取り合わないよう2つにとどめる。 */
        private const val NUM_THREADS = 2

        /** sherpa-onnxのTTSは同時に使えないので、1本のスレッドに寄せる。 */
        private val dispatcher: CoroutineDispatcher =
            Executors.newSingleThreadExecutor { r -> Thread(r, "PlamoSpeech") }.asCoroutineDispatcher()
    }
}
