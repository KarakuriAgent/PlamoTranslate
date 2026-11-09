package app.plamotranslate.conversation

import android.Manifest
import android.app.Application
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.plamotranslate.AsrPreferences
import app.plamotranslate.EngineHolder
import app.plamotranslate.ModelState
import app.plamotranslate.ReadAloud
import app.plamotranslate.audio.MicRecorder
import app.plamotranslate.asr.AsrEngine
import app.plamotranslate.asr.AsrEvent
import app.plamotranslate.asr.AsrModel
import app.plamotranslate.asr.Utterance
import app.plamotranslate.asr.WavFile
import app.plamotranslate.llm.Language
import app.plamotranslate.llm.TranslationEvent
import app.plamotranslate.llm.TranslationRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 音声認識モデルの状態。 */
sealed interface AsrState {
    data object Loading : AsrState
    data object Ready : AsrState
    /** モデルのファイルが無い。トップ画面の「モデルの準備」でダウンロードしてもらう（docs/06_開発環境.md）。 */
    data class Missing(val paths: List<String>) : AsrState
    data class Failed(val message: String) : AsrState
}

/** 会話の1発話。原文は話した言語、訳文は訳した先の言語。 */
data class Turn(
    val id: Long,
    val direction: Direction,
    val original: String,
    val translation: String = "",
    val translated: Boolean = false,
    /** 認識と翻訳にかかった時間。 */
    val stats: String = "",
    /** 発話を確定した時刻（会話ログに出す）。 */
    val timeMillis: Long = System.currentTimeMillis(),
)

/**
 * 話している最中の聞き取り。話し終わると [Turn] に置き換わる。
 * [translation] は途中の文字の訳（途中訳）。新しい途中の文字を訳し終わるまで、前の訳を出しておく。
 */
data class LiveSpeech(val text: String, val direction: Direction?, val translation: String = "")

/** 途中訳の依頼。[generation] が今の発話と違えば、もう要らないので訳さない。 */
private data class PartialRequest(val generation: Long, val text: String, val direction: Direction)

/** 画面の並べ方。対面は上下2分割で上半分を相手向けに回す。1画面は2人で同じ一覧を見る。 */
enum class ViewMode(val label: String) { FACE_TO_FACE("対面"), SHARED("1画面") }

data class ConversationUiState(
    val asr: AsrState = AsrState.Loading,
    /** 使っている音声認識モデル（設定の画面で選ぶ。2026-11-09）。 */
    val asrModel: AsrModel = AsrModel.SENSE_VOICE,
    /** 自分・相手に選べる言語。音声認識モデルで決まる。 */
    val languages: ConversationLanguages = ConversationLanguages.SENSE_VOICE,
    val listening: Boolean = false,
    val mode: PartnerMode = PartnerMode.AUTO,
    val viewMode: ViewMode = ViewMode.FACE_TO_FACE,
    /** 自分の言語（2026-11-08から日本語以外も選べる）。 */
    val self: Language = Language.JA,
    /** 今、自分の発話を訳す先の言語。 */
    val partner: Language = Language.KO,
    val turns: List<Turn> = emptyList(),
    /** マイクの音量（0〜1）。聞いている間だけ動く。 */
    val level: Float = 0f,
    /** 話している最中なら、ここまでの聞き取り（まだ文字になっていなければ空）。 */
    val live: LiveSpeech? = null,
    val error: String? = null,
)

/**
 * 会話翻訳。マイクの音声を発話ごとに認識し、話している間は聞き取りの途中結果とその訳（途中訳）を、
 * 話し終わるたびに確定訳を出す。翻訳モデルは画面翻訳と同じ [EngineHolder] のものを使う。
 *
 * 翻訳エンジンは1本のスレッドで1件ずつ動くので、確定訳を優先する（docs/04_会話翻訳.md「翻訳への渡し方」）。
 * - 途中訳は、訳している間に届いた途中の文字のうち最新のものだけを、終わってから訳す（古いものは捨てる）。
 * - 確定した発話が来たら、走っている途中訳を取り消す。確定訳が残っている間は途中訳を始めない。
 */
class ConversationViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(ConversationUiState())
    val state: StateFlow<ConversationUiState> = _state.asStateFlow()
    val model: StateFlow<ModelState> = EngineHolder.state

    private val router = LanguageRouter()

    /** 表示モードと自分の言語は次に開いたときも同じにする。init より前に置く（init で読む）。 */
    private val preferences = ConversationPreferences(app)

    private val asrPreferences = AsrPreferences(app)
    private var asr: AsrEngine? = null

    /** 読み込んだ（または読み込み中の）音声認識モデル。設定の画面で変わったら読み直す（[refreshAsr]）。 */
    private var asrModel = AsrModel.SENSE_VOICE

    /** 音声認識の読み込みの世代。読み込むたびに進め、終わったときに今の読み込みかを見分ける（メインスレッドだけで触る）。 */
    private var asrLoadGeneration = 0L
    private var listenJob: Job? = null
    private var nextId = 0L

    /** 認識した発話を、話した順に1件ずつ訳す。翻訳エンジンは1本のスレッドで順に動くので、並べても速くならない。 */
    private val pending = Channel<Turn>(Channel.UNLIMITED)

    /** 訳し終わっていない確定した発話の数。1件でもあれば途中訳を始めない。 */
    private val finalsInFlight = AtomicInteger(0)

    /** 途中訳の依頼。最新の1件だけを持つ（CONFLATED）ので、訳している間に届いた古い依頼は上書きされて消える。 */
    private val partialRequests = Channel<PartialRequest>(Channel.CONFLATED)

    /** 発話の区切りごとに進める番号。途中訳が古い発話のものか見分ける。 */
    private val generation = AtomicLong(0)

    /** 音声認識のスレッドから取り消すので volatile にする。 */
    @Volatile
    private var partialJob: Job? = null

    init {
        preferences.viewMode?.let { mode -> _state.update { it.copy(viewMode = mode) } }
        // 保存した自分の言語が今の音声認識モデルで選べるかは、loadAsr で確かめる
        preferences.self?.let { router.self = it }
        EngineHolder.ensureLoaded(app)
        loadAsr()
        viewModelScope.launch {
            for (turn in pending) {
                translate(turn)
                finalsInFlight.decrementAndGet()
            }
        }
        viewModelScope.launch {
            for (request in partialRequests) {
                if (finalsInFlight.get() > 0 || request.generation != generation.get()) continue
                partialJob = launch { translatePartial(request) }
                partialJob?.join()
            }
        }
    }

    /** 設定の画面で選んだ音声認識モデルを読み込む。選んだモデルが端末に無ければSenseVoice（[AsrPreferences.selected]）。 */
    fun loadAsr() {
        val model = asrPreferences.selected()
        useLanguagesOf(model)
        val missing = asrPreferences.missingFiles(model)
        if (missing.isNotEmpty()) {
            _state.update { it.copy(asr = AsrState.Missing(missing)) }
            return
        }
        _state.update { it.copy(asr = AsrState.Loading) }
        val generation = ++asrLoadGeneration
        viewModelScope.launch {
            // 読み込みの途中で画面が閉じられても、できたエンジンを受け取って解放できるよう、取り消さずに最後まで走らせる
            val result = withContext(asrDispatcher + NonCancellable) { runCatching { asrPreferences.load(model) } }
            if (!isActive || generation != asrLoadGeneration) {
                // 画面が閉じられた、または読み込んでいる間に次の読み込みが始まった（A→B→Aと替えたときの先のAなど）。こちらは使わない
                result.onSuccess { engine -> asrExecutor.execute { engine.close() } }
                return@launch
            }
            result.onSuccess { engine ->
                engine.separateChinese = router.chinese
                val old = asr
                asr = engine
                // 念のため、前のエンジンが残っていれば解放する（認識中のスレッドが使い終わってから）
                if (old != null && old !== engine) asrExecutor.execute { old.close() }
            }
            _state.update {
                it.copy(asr = result.fold({ AsrState.Ready }, { e -> AsrState.Failed(e.message ?: e.toString()) }))
            }
        }
    }

    /**
     * 設定の画面で音声認識モデルを変えて戻ってきたら、読み直す（画面を開くたびに呼ぶ）。
     * 聞いている途中なら止める。前のエンジンは、認識中のスレッドが使い終わってから解放する。
     */
    fun refreshAsr() {
        if (asrPreferences.selected() == asrModel) return
        stop()
        val old = asr
        asr = null
        asrExecutor.execute { old?.close() }
        loadAsr()
    }

    /**
     * 選べる言語を [model] のものにする。保存した自分の言語や固定した相手の言語が選べなければ、日本語・自動に戻す
     * （2026-11-09。Qwen3-ASRでドイツ語などを選んだ後にSenseVoiceへ戻したとき）。
     */
    private fun useLanguagesOf(model: AsrModel) {
        asrModel = model
        val languages = ConversationLanguages.of(model)
        val selfBefore = router.self
        router.languages = languages
        if (router.self != selfBefore) preferences.self = null
        asr?.separateChinese = router.chinese
        _state.update {
            it.copy(asrModel = model, languages = languages, self = router.self, mode = router.mode, partner = router.partner)
        }
    }

    fun setMode(mode: PartnerMode) {
        router.mode = mode
        updateLanguages()
    }

    /** 自分の言語を変える。相手が同じ言語なら、相手は自動に戻る。 */
    fun setSelf(self: Language) {
        router.self = self
        preferences.self = self
        updateLanguages()
    }

    private fun updateLanguages() {
        // 自分か相手が中国語のときだけ、音声認識が中国語と日本語を別の話し手として区切る
        asr?.separateChinese = router.chinese
        _state.update { it.copy(self = router.self, mode = router.mode, partner = router.partner) }
    }

    fun setViewMode(mode: ViewMode) {
        preferences.viewMode = mode
        _state.update { it.copy(viewMode = mode) }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() = listen { engine ->
        MicRecorder.stream().collect { chunk -> feed(engine, chunk) }
    }

    /**
     * 動作確認用：マイクの代わりに音声ファイルを流す（デバッグ版で intent の wav に渡す）。
     * 途中結果の出方も確かめられるよう、実際の話す速さで流す。
     */
    fun startFromWav(path: String) = listen { engine ->
        val samples = WavFile.read(File(path))
        val chunk = AsrEngine.SAMPLE_RATE / 10
        // 末尾に、発話の終わりとみなす無音より長い無音を足し、話し終わりをVADに検出させる
        val silence = ((AsrEngine.END_SILENCE_SECONDS + 1) * AsrEngine.SAMPLE_RATE).toInt()
        val padded = samples + FloatArray(silence)
        for (from in padded.indices step chunk) {
            val begin = System.nanoTime()
            feed(engine, padded.copyOfRange(from, minOf(from + chunk, padded.size)))
            val spentMs = (System.nanoTime() - begin) / 1_000_000
            Thread.sleep((100 - spentMs).coerceAtLeast(0))
        }
        log("音声ファイルの認識が終わりました: ${File(path).name}")
    }

    private fun feed(engine: AsrEngine, raw: FloatArray) {
        // 訳文を読み上げている間とその直後は、マイクの音を無音に置き換える。読み上げの声を発話として拾わないため（2026-11-08）
        val chunk = if (ReadAloud.isAudible()) FloatArray(raw.size) else raw
        _state.update { it.copy(level = level(chunk)) }
        engine.accept(chunk).forEach(::onEvent)
    }

    fun stop() {
        listenJob?.cancel()
    }

    fun clear() = _state.update { it.copy(turns = emptyList(), error = null) }

    private fun listen(block: suspend (AsrEngine) -> Unit) {
        val engine = asr ?: return
        if (listenJob?.isActive == true) return
        listenJob = viewModelScope.launch {
            _state.update { it.copy(listening = true, error = null) }
            try {
                withContext(asrDispatcher) {
                    engine.reset()
                    try {
                        block(engine)
                    } finally {
                        // 止めたときに話しかけだった分も訳す
                        engine.flush().forEach(::onEvent)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _state.update { it.copy(listening = false, level = 0f, live = null) }
            }
        }
    }

    private fun onEvent(event: AsrEvent) {
        when (event) {
            is AsrEvent.Speaking -> {
                generation.incrementAndGet()
                _state.update { it.copy(live = if (event.speaking) LiveSpeech("", null) else null) }
            }
            is AsrEvent.Partial -> {
                log("途中 [${event.lang}] ${event.text}")
                val direction = router.preview(event.text, event.lang)
                _state.update {
                    // 前の途中訳は、新しい途中の文字を訳し終わるまで出しておく（ちらつかせない）
                    val keep = it.live?.takeIf { live -> live.direction == direction }?.translation.orEmpty()
                    it.copy(live = LiveSpeech(event.text, direction, keep))
                }
                partialRequests.trySend(PartialRequest(generation.get(), event.text, direction))
            }
            is AsrEvent.Final -> {
                // 確定訳を優先する：古い発話の途中訳は要らないので止める
                generation.incrementAndGet()
                partialJob?.cancel()
                onUtterance(event.utterance)
            }
        }
    }

    /** 途中の文字を訳し、訳し終わったときにまだ同じ発話なら聞き取り中のカードに出す。 */
    private suspend fun translatePartial(request: PartialRequest) {
        val engine = (model.value as? ModelState.Ready)?.engine ?: return
        val start = System.nanoTime()
        val out = StringBuilder()
        runCatching {
            engine.translate(
                TranslationRequest(request.text, request.direction.source, request.direction.target),
                maxTokens = PARTIAL_MAX_TOKENS,
            ).collect { event -> if (event is TranslationEvent.Delta) out.append(event.text) }
        }.onFailure { e ->
            if (e is CancellationException) throw e
            return
        }
        if (request.generation != generation.get()) return
        val text = out.toString().trim()
        log("途中訳 ${request.text} → $text ${(System.nanoTime() - start) / 1_000_000}ms")
        _state.update { s ->
            val live = s.live ?: return@update s
            s.copy(live = live.copy(translation = text))
        }
    }

    private fun onUtterance(utterance: Utterance) {
        val direction = router.route(utterance.text, utterance.lang)
        val turn = Turn(
            id = nextId++,
            direction = direction,
            original = utterance.text,
            stats = "認識${utterance.recognizeMs}ms（発話${"%.1f".format(utterance.durationMs / 1000.0)}秒）",
        )
        log("認識 [${utterance.lang}→${direction.source.code}] ${utterance.text} ${turn.stats}")
        // 話し続けていれば次の途中結果が来るので、ここでは聞き取り中の表示だけ空にする
        _state.update {
            it.copy(turns = it.turns + turn, partner = router.partner, live = it.live?.let { LiveSpeech("", null) })
        }
        finalsInFlight.incrementAndGet()
        pending.trySend(turn)
    }

    private suspend fun translate(turn: Turn) {
        val engine = EngineHolder.awaitEngine(app) ?: return
        val request = TranslationRequest(turn.original, turn.direction.source, turn.direction.target)
        runCatching {
            engine.translate(request).collect { event ->
                when (event) {
                    is TranslationEvent.Delta -> updateTurn(turn.id) { it.copy(translation = it.translation + event.text) }
                    is TranslationEvent.Done -> updateTurn(turn.id) {
                        it.copy(translated = true, stats = "${it.stats}・翻訳${event.millis}ms")
                    }
                }
            }
        }.onFailure { e ->
            if (e is CancellationException) throw e
            updateTurn(turn.id) { it.copy(translated = true, translation = "（翻訳に失敗しました：${e.message}）") }
        }
        _state.value.turns.firstOrNull { it.id == turn.id }?.let {
            log("翻訳 [${it.direction.source.code}→${it.direction.target.code}] ${it.original} → ${it.translation} ${it.stats}")
        }
    }

    private fun updateTurn(id: Long, change: (Turn) -> Turn) =
        _state.update { s -> s.copy(turns = s.turns.map { if (it.id == id) change(it) else it }) }

    override fun onCleared() {
        listenJob?.cancel()
        pending.close()
        val engine = asr
        asr = null
        // 認識中のスレッドが使い終わってから解放する
        asrExecutor.execute { engine?.close() }
    }

    private fun log(message: String) = Log.i(TAG, "会話: $message")

    /** 音量の目安。-55dBFS（静かな部屋）〜-15dBFS（近くで話す）を0〜1に割り当てる。 */
    private fun level(chunk: FloatArray): Float {
        if (chunk.isEmpty()) return 0f
        var sum = 0.0
        for (s in chunk) sum += s * s
        val db = 10 * kotlin.math.log10(sum / chunk.size + 1e-10)
        return ((db + 55) / 40).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        private const val TAG = "PlamoTranslate"

        /** 途中訳は話している途中の文なので短い。長く生成して確定訳を待たせないよう上限を低くする。 */
        private const val PARTIAL_MAX_TOKENS = 160

        /** 音声認識（sherpa-onnx）は同じスレッドから順に呼ぶ。 */
        private val asrExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "PlamoAsr") }
        private val asrDispatcher = asrExecutor.asCoroutineDispatcher()
    }
}
