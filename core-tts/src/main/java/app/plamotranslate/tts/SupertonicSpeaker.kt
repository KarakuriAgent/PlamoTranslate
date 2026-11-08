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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * Supertonic 3（Supertone、約99Mパラメーター、int8）をsherpa-onnxで動かす読み上げ（2026-11-08）。
 * 31言語を読めるが、中国語は含まない（[LANGUAGES]）。モデルは [dir] に置いた7つのファイルで、
 * 初めて読むときに読み込み、閉じるまで持っておく。
 *
 * sherpa-onnxは渡した文をまとめて作り終えてから、コールバックを1回だけ呼ぶ（実機で確認）。
 * 長い訳文でも最初の音を早く出すため、文に分けて1文ずつ作り、鳴らしている間に次の文を作る。
 */
class SupertonicSpeaker(
    private val dir: File,
    /** 声（voice.bin の何番目の声か）。 */
    var voice: Int = 0,
    /** 読む速さ（1が標準）。 */
    var speed: Float = 1f,
    /** 音を作る段数（[STEPS_RANGE]）。増やすと話している最中のザラつきが減り、そのぶん遅くなる。 */
    var steps: Int = DEFAULT_STEPS,
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
        val config = GenerationConfig(
            sid = voice.coerceIn(0, maxOf(0, engine.numSpeakers() - 1)),
            speed = speed,
            numSteps = steps.coerceIn(STEPS_RANGE),
            extra = mapOf("lang" to lang.substringBefore('-')),
        )
        // 作った音を鳴らす側へ渡す。鳴らす側は書き込みで待つので、作る側（このスレッド）は止めない
        val chunks = Channel<FloatArray>(Channel.UNLIMITED)
        // ネイティブ側はコールバックの invoke(float[]): Integer を名前と型で探して呼ぶ。
        // ラムダで渡すとinvokedynamicで作られ、そのメソッドが無く NoSuchMethodError で落ちる（2026-11-08、実機）。
        // 型を明示したクラスにして、invoke(float[]): Integer を必ず持たせる
        val callback = object : (FloatArray) -> Int {
            override fun invoke(samples: FloatArray): Int {
                if (!job.isActive) return 0
                chunks.trySend(samples.copyOf())
                return 1
            }
        }
        val start = System.nanoTime()
        try {
            coroutineScope {
                launch(Dispatchers.IO) {
                    var frames = 0L
                    track.play()
                    for (samples in chunks) {
                        if (frames == 0L) Log.i(TAG, "読み上げ: 最初の音まで ${(System.nanoTime() - start) / 1_000_000}ms")
                        track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
                        frames += samples.size
                    }
                    // 書き終えた分が鳴り終わるまで待つ
                    while (isActive && track.playbackHeadPosition < frames) delay(30)
                }
                try {
                    for (sentence in sentences(text)) {
                        if (!job.isActive) break
                        engine.generateWithConfigAndCallback(sentence, config, callback)
                    }
                } finally {
                    chunks.close()
                }
            }
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
                    vocoder = File(dir, "vocoder.onnx").path,
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

        /** [dir] に置くファイル。vocoderだけ公式のfp32版（int8版はザラつきが乗る。docs/04「読み上げ」）。 */
        val FILES = listOf(
            "duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx",
            "vocoder.onnx", "tts.json", "unicode_indexer.bin", "voice.bin",
        )

        /**
         * 音を作る段数の範囲と既定。作る時間は段数にほぼ比例する（REDMAGIC 11 Proで10秒分の日本語が
         * 5段3.6秒・8段6.1秒・16段9.9秒）。ザラつきは段数を増やすほど減る（2026-11-08、docs/04「読み上げ」）。
         * 作者が5段ではザラつくと言ったので、既定は8。設定の画面のスライダーで変える。
         */
        val STEPS_RANGE = 3..16
        const val DEFAULT_STEPS = 8

        /** 2から4で約1割速くなり、6にしても変わらない（2026-11-08、実機）。翻訳と同時には走らないので4。 */
        private const val NUM_THREADS = 4

        /** 文の終わりの記号（日本語・中国語の句点、全角の！？、英語などの .!?）。 */
        private val SENTENCE_END = Regex("(?<=[。！？!?]|\\.(?=\\s))\\s*|\\n+")

        /** [text] を文に分ける。最初の音を早く出すため、文ごとに作って鳴らす。 */
        fun sentences(text: String): List<String> =
            text.split(SENTENCE_END).map { it.trim() }.filter { it.isNotEmpty() }

        /** sherpa-onnxのTTSは同時に使えないので、1本のスレッドに寄せる。 */
        private val dispatcher: CoroutineDispatcher =
            Executors.newSingleThreadExecutor { r -> Thread(r, "PlamoSpeech") }.asCoroutineDispatcher()
    }
}
