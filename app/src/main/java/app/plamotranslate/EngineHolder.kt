package app.plamotranslate

import android.content.Context
import app.plamotranslate.llm.Language
import app.plamotranslate.llm.TranslationEngine
import app.plamotranslate.llm.TranslationRequest
import app.plamotranslate.models.TranslationModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

sealed interface ModelState {
    data object Loading : ModelState
    data class Ready(val engine: TranslationEngine, val model: TranslationModel, val loadMillis: Long) : ModelState
    /** モデルのファイルが無い。アプリを開くと出る「モデルの準備」の画面（MainActivity の SetupScreen）でダウンロードしてもらう。 */
    data class Missing(val expectedPath: String) : ModelState
    data class Failed(val message: String) : ModelState
}

/**
 * 翻訳モデルをアプリ全体で1つだけ持つ（docs/02_アーキテクチャ.md）。
 * テキスト翻訳画面・画面翻訳サービス・会話翻訳のすべてから同じエンジンを使う。
 * 0.8Bと2Bのどちらを使うかは作者が選び、次に起動したときも同じものを使う（2026-11-06）。
 * 設定（使うモデル・CPUの詳細）の保存は [TranslationPreferences]、速さの計測は [EngineBenchmark] が持つ。
 */
object EngineHolder {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _state = MutableStateFlow<ModelState>(ModelState.Loading)
    val state: StateFlow<ModelState> = _state.asStateFlow()

    /** 選ばれている翻訳モデル。選んだモデルのファイルが無ければ0.8Bを使う。 */
    fun selected(context: Context): TranslationModel {
        val model = TranslationPreferences(context).model
        return if (model.isPresent(context)) model else TranslationModel.SMALL
    }

    fun modelFile(context: Context): File = selected(context).file.file(context)

    /** まだ読んでいなければ読み込みを始める。読み込み済み・読み込み中なら何もしない。 */
    @Synchronized
    fun ensureLoaded(context: Context) {
        val current = _state.value
        if (current is ModelState.Ready) return
        if (current is ModelState.Loading && loading) return
        load(context.applicationContext, selected(context.applicationContext))
    }

    /**
     * 翻訳モデルを切り替える。今のエンジンを閉じてから、選んだモデルを読み込む。
     * 閉じるのは推論のスレッドで順番に行うので、走っている翻訳が終わってから閉じる。
     */
    @Synchronized
    fun select(context: Context, model: TranslationModel) {
        val app = context.applicationContext
        TranslationPreferences(app).model = model
        val current = _state.value
        if (current is ModelState.Ready && current.model == model) return
        if (loading) return
        val old = (current as? ModelState.Ready)?.engine
        _state.value = ModelState.Loading
        loading = true
        scope.launch {
            // loading は立てたまま読み込みへ進む（その間に ensureLoaded が二重に読み込まないように）
            old?.close()
            load(app, model)
        }
    }

    @Synchronized
    private fun load(app: Context, model: TranslationModel) {
        val file = model.file.file(app)
        if (!file.exists()) {
            _state.value = ModelState.Missing(file.path)
            return
        }
        loading = true
        _state.value = ModelState.Loading
        // OpenCLのカーネルのコンパイル結果を残す場所。既定の一時フォルダーはアプリから書けない
        val clCache = File(app.cacheDir, "cl-cache").apply { mkdirs() }
        android.system.Os.setenv("GGML_OPENCL_KERNEL_CACHE_DIR", clCache.path, true)
        val libDir = app.applicationInfo.nativeLibraryDir
        val preferences = TranslationPreferences(app)
        val gpu = preferGpu(preferences.cpuOnly)
        val cpu = preferences.cpuOptions
        scope.launch {
            val start = System.nanoTime()
            _state.value = runCatching { TranslationEngine.load(file, libDir, gpu, cpu = cpu).also { warmUp(it) } }.fold(
                onSuccess = { ModelState.Ready(it, model, (System.nanoTime() - start) / 1_000_000) },
                onFailure = { ModelState.Failed(it.message ?: it.toString()) },
            )
            loading = false
        }
    }

    @Volatile
    private var loading = false

    /**
     * GPU（OpenCL）を使うか。llama.cppのOpenCLはQualcomm（Snapdragon）のAdrenoに合わせて作られているので、
     * Qualcommのチップの端末だけGPUを使い、ほかはCPUで動かす（2026-11-06、APKを配布するため）。
     * GPUでの読み込みに失敗したときは [TranslationEngine.load] がCPUでやり直す。
     * 設定の「CPUの詳細」で「CPUだけ」を選ぶと、Snapdragonの端末でもCPUで動く（CPUの速さを確かめるため）。
     */
    private fun preferGpu(cpuOnly: Boolean): Boolean {
        if (cpuOnly) return false
        val soc = android.os.Build.SOC_MANUFACTURER
        return soc.equals("QTI", ignoreCase = true) || soc.contains("Qualcomm", ignoreCase = true) ||
            android.os.Build.HARDWARE.equals("qcom", ignoreCase = true)
    }

    /**
     * GPU（OpenCL）は初回の推論でカーネルを準備するため4秒ほどかかる（2026-11-05実測）。
     * 読み込み直後に短い文を1回訳しておき、作者の最初の翻訳を待たせない。
     */
    private suspend fun warmUp(engine: TranslationEngine) {
        runCatching {
            engine.translate(TranslationRequest("안녕하세요", Language.KO, Language.JA), maxTokens = 8).collect { }
        }
    }

    /** 今の翻訳モデルを閉じて読み直す（CPUの詳細設定を変えたとき。設定は先に [TranslationPreferences.saveCpu] で保存する）。 */
    @Synchronized
    fun reload(context: Context) {
        val app = context.applicationContext
        if (loading) return
        val old = (_state.value as? ModelState.Ready)?.engine
        _state.value = ModelState.Loading
        loading = true
        scope.launch {
            old?.close()
            load(app, selected(app))
        }
    }

    /** 読み込みが終わるまで待ってエンジンを返す。失敗・未配置なら null。 */
    suspend fun awaitEngine(context: Context): TranslationEngine? {
        ensureLoaded(context)
        val settled = state.first { it !is ModelState.Loading }
        return (settled as? ModelState.Ready)?.engine
    }
}
