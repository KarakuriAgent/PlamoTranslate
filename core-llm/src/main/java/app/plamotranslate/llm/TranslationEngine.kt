package app.plamotranslate.llm

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/** 翻訳中に流れてくる出来事。 */
sealed interface TranslationEvent {
    /** 訳文の続き。 */
    data class Delta(val text: String) : TranslationEvent

    /** 生成が終わった。[tokens] は生成トークン数、[millis] は依頼から完了までの時間。 */
    data class Done(val tokens: Int, val millis: Long) : TranslationEvent
}

class TranslationException(message: String) : Exception(message)

/**
 * CPUで動かすときの細かい設定（2026-11-08、15T Pro（MediaTek）でアプリ内の生成がCLIの約200分の1に落ちたため、
 * 原因の候補を設定の画面で切り替えて試せるようにした。docs/03_翻訳コア.md「CPUの詳細設定」）。既定は今までの動き。
 */
data class CpuOptions(
    /** 0なら自動（コア数−2、最大6）。 */
    val threads: Int = 0,
    /** 性能ヒント（ADPF）で周波数・配置を上げてもらう。 */
    val boost: Boolean = true,
    /** 仕事を待つ間に回して待つ（false なら眠って待つ）。 */
    val spinWait: Boolean = true,
    /** スレッドプールを常駐させる（false ならllama.cppが推論のたびに用意する）。 */
    val threadPool: Boolean = true,
    /** モデルをファイルの写像（mmap）で読む（false ならメモリへ全部読み込む）。 */
    val mmap: Boolean = true,
    /** CPUの計算部分の版（libggml-cpu-*.so）。空なら端末に合う一番新しい版。アプリを起動し直すと効く。 */
    val variant: String = "",
)

/**
 * PLaMo 3 Translateを1つ保持し、翻訳を1件ずつ実行する。
 * llama.cppのコンテキストは同時に使えないため、すべての呼び出しを1本のスレッドへ寄せる。
 */
class TranslationEngine private constructor(
    private val handle: Long,
    /** GPU（OpenCL）で動いているか。false ならCPUだけ。 */
    val usesGpu: Boolean,
) {

    /**
     * 訳文をトークンごとに流す。収集側がキャンセルすると、次のトークンで生成を止める。
     */
    fun translate(request: TranslationRequest, maxTokens: Int = DEFAULT_MAX_TOKENS): Flow<TranslationEvent> =
        callbackFlow {
            val prompt = Pfml3PromptBuilder.build(request)
            val job = launch(dispatcher) {
                // モデルを切り替えて閉じた後に順番が回ってきた依頼は、解放済みのモデルを触らずに失敗させる
                if (closed) {
                    close(TranslationException("翻訳モデルを切り替えたため、この翻訳は中止しました"))
                    return@launch
                }
                val start = System.nanoTime()
                val scope = currentCoroutineContext()
                val result = LlamaNative.nativeTranslate(handle, prompt, maxTokens) { bytes ->
                    trySend(TranslationEvent.Delta(String(bytes, Charsets.UTF_8)))
                    scope.isActive
                }
                if (result < 0) {
                    close(TranslationException(errorMessage(result)))
                } else {
                    send(TranslationEvent.Done(result, (System.nanoTime() - start) / 1_000_000))
                    close()
                }
            }
            awaitClose { job.cancel() }
        }

    /** 推論のスレッドでだけ読み書きする。閉じた後の翻訳を止めるための印。 */
    private var closed = false

    /**
     * モデルを解放する。推論のスレッドで順番に行うので、走っている翻訳が終わってから解放される。
     * 解放した後に届いた翻訳の依頼は [TranslationException] で失敗する（翻訳モデルの切り替え、2026-11-06）。
     */
    suspend fun close() = withContext(dispatcher) {
        if (!closed) {
            closed = true
            LlamaNative.nativeFree(handle)
        }
    }

    /** 計測用：性能ヒントの有無とスレッド数（0なら変えない）を切り替える。 */
    suspend fun configure(boost: Boolean, threads: Int = 0) =
        withContext(dispatcher) { LlamaNative.nativeConfigure(handle, boost, threads) }

    companion object {
        const val DEFAULT_CONTEXT = 2048
        const val DEFAULT_MAX_TOKENS = 1024

        /**
         * 全層をGPU（OpenCL、Adreno 840）に載せる。2026-11-05の実測で、プロンプト処理はCPUの約5倍（約1170トークン/秒）、
         * 生成はCPUと同程度（約80トークン/秒）だが、REDMAGICのCPUクロック上限の影響を受けない（docs/00_現在地.md）。
         */
        const val DEFAULT_GPU_LAYERS = 99

        /**
         * 2026-11-05のREDMAGIC 11 Proでの実測。CLI単体では8スレッドが最速だったが、アプリでは画面描画と
         * CPUを取り合い、8スレッドは生成35トークン/秒、6スレッドは64トークン/秒だった（docs/00_現在地.md）。
         */
        const val DEFAULT_THREADS = 6

        /**
         * 推論は常に同じ1本のスレッドで行う。性能ヒントの対象スレッドを固定するため、
         * スレッドが入れ替わりうる Dispatchers.IO.limitedParallelism(1) は使わない。
         */
        private val dispatcher: CoroutineDispatcher =
            Executors.newSingleThreadExecutor { r -> Thread(r, "PlamoInference") }.asCoroutineDispatcher()

        /**
         * モデルを読み込む。数秒かかるので起動時に1回だけ呼ぶ。
         *
         * [nativeLibDir] は計算部分のライブラリ（libggml-opencl.so、libggml-cpu-*.so）の置き場
         * （`applicationInfo.nativeLibraryDir`）。[preferGpu] が true なら、GPU（OpenCL）が使えれば全層を載せる。
         * GPUが無い・GPUでの読み込みに失敗したときは、CPUだけで読み込み直す（2026-11-06、APKを配布するため）。
         */
        suspend fun load(
            model: File,
            nativeLibDir: String,
            preferGpu: Boolean,
            nCtx: Int = DEFAULT_CONTEXT,
            cpu: CpuOptions = CpuOptions(),
        ): TranslationEngine = withContext(dispatcher) {
            if (!model.canRead()) throw TranslationException("モデルが読めません: ${model.path}")
            val hasGpu = LlamaNative.nativeInit(nativeLibDir, preferGpu, cpu.variant)
            var gpu = preferGpu && hasGpu
            val threads = if (cpu.threads > 0) cpu.threads else defaultThreads()
            fun nativeLoad(layers: Int) = LlamaNative.nativeLoad(
                model.path, nCtx, threads, layers,
                if (cpu.spinWait) DEFAULT_POLL else 0, cpu.threadPool, cpu.mmap, cpu.boost,
            )
            var handle = nativeLoad(if (gpu) DEFAULT_GPU_LAYERS else 0)
            if (handle == 0L && gpu) {
                // GPUのドライバーが合わないなど。CPUでやり直す
                gpu = false
                handle = nativeLoad(0)
            }
            if (handle == 0L) throw TranslationException("モデルの読み込みに失敗しました: ${model.name}")
            TranslationEngine(handle, gpu)
        }

        /** CPUのスレッド数の既定。端末のコアの最高周波数から決める（[threadsFor]）。 */
        fun defaultThreads(): Int = threadsFor(coreMaxFrequencies())

        /**
         * 最高周波数が一番低いまとまり（小さいコア）がコアの半分以下なら、それ以外のコア（大きいコア）の数にする。
         * 小さいコアに仕事が回ると、ほかのスレッドが待たされて全体が遅くなる（2026-11-08、15T Pro（2.4GHz×4、
         * 3.3GHz×3、3.73GHz×1）で6スレッドは0.3〜0.8トークン/秒、4スレッドは56〜62トークン/秒）。
         * 小さいコアが半分より多い、または周波数が読めない端末は、今までどおり「コア数−2」にする
         * （REDMAGIC 11 Pro（4.6GHz×2、3.6GHz×6）では6が最速だった。docs/00_現在地.md）。
         * どちらも2〜[DEFAULT_THREADS]に収める。
         */
        internal fun threadsFor(maxFrequencies: List<Long>): Int {
            val known = maxFrequencies.filter { it > 0 }
            val lowest = known.minOrNull()
            val little = known.count { it == lowest }
            val threads = if (known.size == maxFrequencies.size && known.isNotEmpty() && little < known.size && little * 2 <= known.size) {
                known.size - little
            } else {
                maxFrequencies.size - 2
            }
            return threads.coerceIn(2, DEFAULT_THREADS)
        }

        /** 各コアの最高周波数（kHz）。読めないコアは0。 */
        private fun coreMaxFrequencies(): List<Long> =
            (0 until Runtime.getRuntime().availableProcessors()).map { i ->
                runCatching {
                    File("/sys/devices/system/cpu/cpu$i/cpufreq/cpuinfo_max_freq").readText().trim().toLong()
                }.getOrDefault(0L)
            }

        /** llama.cppの既定。仕事を待つ間、しばらく回して待ってから眠る。 */
        private const val DEFAULT_POLL = 50

        /** 端末に入っているCPUの計算部分の版（ファイル名）。 */
        fun cpuVariants(nativeLibDir: String): List<String> =
            File(nativeLibDir).list()?.filter { it.startsWith("libggml-cpu") }?.sorted().orEmpty()

        /** 読み込んだCPUの計算部分の版。まだ読み込んでいない、またはGPUの端末で自動選択したときは空。 */
        fun cpuBackend(): String = LlamaNative.nativeCpuBackend()

        private fun errorMessage(code: Int) = when (code) {
            -1 -> "文をトークンに分けられませんでした"
            -2 -> "文が長すぎます（${DEFAULT_CONTEXT}トークンまで）"
            -3 -> "推論に失敗しました"
            else -> "翻訳に失敗しました（$code）"
        }
    }
}
