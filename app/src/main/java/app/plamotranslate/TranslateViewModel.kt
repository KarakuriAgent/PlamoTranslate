package app.plamotranslate

import android.Manifest
import android.app.Application
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.plamotranslate.asr.AsrEngine
import app.plamotranslate.asr.AsrEvent
import app.plamotranslate.asr.SenseVoiceAsr
import app.plamotranslate.conversation.ConversationViewModel
import app.plamotranslate.conversation.MicRecorder
import app.plamotranslate.conversation.isChinese
import app.plamotranslate.llm.Language
import app.plamotranslate.llm.TranslationEvent
import app.plamotranslate.llm.TranslationRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

data class TranslateUiState(
    val input: String = "",
    /** null は自動判定。既定は自動判定（2026-11-06、作者の要望）。 */
    val source: Language? = null,
    val target: Language = Language.JA,
    val output: String = "",
    val translating: Boolean = false,
    /** 直近の翻訳の所要時間など。 */
    val stats: String = "",
    val error: String? = null,
    val voice: VoiceState = VoiceState.Idle,
)

/** 原文の音声入力の状態。 */
enum class VoiceState { Idle, Loading, Listening }

/** テキスト翻訳画面の状態。モデルは [EngineHolder] がアプリ全体で1つ持つ。 */
class TranslateViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(TranslateUiState())
    val state: StateFlow<TranslateUiState> = _state.asStateFlow()
    val model: StateFlow<ModelState> = EngineHolder.state

    private var translateJob: Job? = null

    /** 音声入力の音声認識。初めてマイクを押したときに読み込む（会話翻訳と同じモデル）。 */
    private var asr: AsrEngine? = null
    private var voiceJob: Job? = null

    /** 止めるボタンが押されたか。押されたら、話しかけの分も確定させてから止める。 */
    @Volatile private var stopVoice = false

    init {
        EngineHolder.ensureLoaded(app)
    }

    fun onInputChange(text: String) = _state.update { it.copy(input = text) }
    fun onSourceChange(lang: Language?) = _state.update { it.copy(source = lang) }
    fun onTargetChange(lang: Language) = _state.update { it.copy(target = lang) }

    /** 原文と訳文、訳す方向を入れ替える。自動判定のときは入れ替えない。 */
    fun swap() = _state.update {
        val src = it.source ?: return@update it
        it.copy(source = it.target, target = src, input = it.output.ifBlank { it.input }, output = "")
    }

    fun clear() {
        translateJob?.cancel()
        _state.update { it.copy(input = "", output = "", stats = "", error = null, translating = false) }
    }

    /**
     * 音声で原文を入れる（2026-11-08、作者の要望）。1回押すと1つの発話を聞き取り、話し終わったら止まって訳す。
     * 聞き取り中は途中の文字を原文の欄に出し、すでに書いてある原文の後ろに足す。
     * 音声認識（SenseVoice）が聞き取れるのは日本語・韓国語・英語・中国語・広東語だけ。
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startVoice() {
        if (voiceJob?.isActive == true) return
        stopVoice = false
        voiceJob = viewModelScope.launch {
            val engine = asr ?: loadAsr() ?: return@launch
            val base = _state.value.input
            _state.update { it.copy(voice = VoiceState.Listening, error = null) }
            var heard: String? = null
            try {
                withContext(asrDispatcher) {
                    engine.separateChinese = _state.value.source?.isChinese == true
                    engine.reset()
                    var silentChunks = 0
                    var spoke = false
                    try {
                        MicRecorder.stream().takeWhile { heard == null && !stopVoice && (spoke || silentChunks < WAIT_CHUNKS) }
                            .collect { chunk ->
                                silentChunks++
                                for (event in engine.accept(chunk)) when (event) {
                                    is AsrEvent.Speaking -> if (event.speaking) spoke = true
                                    is AsrEvent.Partial -> _state.update { it.copy(input = joinSpoken(base, event.text)) }
                                    is AsrEvent.Final -> heard = event.utterance.text
                                }
                            }
                    } finally {
                        // 止めるボタンで止めたときも、話しかけだった分を確定させる
                        if (heard == null) {
                            heard = engine.flush().filterIsInstance<AsrEvent.Final>().firstOrNull()?.utterance?.text
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _state.update { it.copy(voice = VoiceState.Idle) }
            }
            val text = heard?.trim().orEmpty()
            _state.update { it.copy(input = joinSpoken(base, text)) }
            if (text.isNotEmpty()) translate()
        }
    }

    fun stopVoice() {
        stopVoice = true
    }

    private suspend fun loadAsr(): AsrEngine? {
        val dir = ConversationViewModel.modelDir(app)
        if (SenseVoiceAsr.missingFiles(dir).isNotEmpty()) {
            _state.update { it.copy(error = "音声認識のモデルがありません。トップ画面の「モデルの準備」でダウンロードしてください") }
            return null
        }
        _state.update { it.copy(voice = VoiceState.Loading, error = null) }
        val result = withContext(asrDispatcher) { runCatching { SenseVoiceAsr.load(dir) } }
        _state.update { it.copy(voice = VoiceState.Idle) }
        return result.fold(
            { it.also { engine -> asr = engine } },
            { e -> _state.update { it.copy(error = "音声認識を読み込めませんでした：${e.message}") }; null },
        )
    }

    override fun onCleared() {
        voiceJob?.cancel()
        val engine = asr
        asr = null
        // 認識中のスレッドが使い終わってから解放する
        asrExecutor.execute { engine?.close() }
    }

    fun translate() {
        val engine = (model.value as? ModelState.Ready)?.engine ?: return
        val s = _state.value
        if (s.input.isBlank()) return
        // 前の翻訳が走っていれば止めて、最新の入力だけを訳す
        translateJob?.cancel()
        translateJob = viewModelScope.launch {
            _state.update { it.copy(output = "", translating = true, error = null, stats = "") }
            runCatching {
                engine.translate(TranslationRequest(s.input, s.source, s.target)).collect { event ->
                    when (event) {
                        is TranslationEvent.Delta -> _state.update { it.copy(output = it.output + event.text) }
                        is TranslationEvent.Done -> _state.update {
                            it.copy(stats = "${event.tokens}トークン・${"%.2f".format(event.millis / 1000.0)}秒")
                        }
                    }
                }
            }.onFailure { e ->
                if (e !is CancellationException) _state.update { it.copy(error = e.message ?: e.toString()) }
            }
            _state.update { it.copy(translating = false) }
        }
    }

    companion object {
        /** 話し始めずにこれだけ（0.1秒単位で8秒）たったら、聞き取りをやめる。 */
        private const val WAIT_CHUNKS = 80

        /** 音声認識（sherpa-onnx）は同じスレッドから順に呼ぶ。 */
        private val asrExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "PlamoVoiceInput") }
        private val asrDispatcher = asrExecutor.asCoroutineDispatcher()

        /**
         * 聞き取った文を、すでに書いてある原文の後ろに足す。日本語・中国語どうしは詰め、それ以外（韓国語・英語）は空白を挟む。
         */
        internal fun joinSpoken(base: String, spoken: String): String {
            val text = spoken.trim()
            if (text.isEmpty()) return base
            if (base.isBlank()) return text
            val last = base.last()
            val sep = if (last.isWhitespace() || (last.isCjk() && text.first().isCjk())) "" else " "
            return base + sep + text
        }

        private fun Char.isCjk() = this in '\u3000'..'\u9fff' || this in '\uff00'..'\uffef'
    }
}