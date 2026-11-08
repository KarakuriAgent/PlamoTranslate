package app.plamotranslate

import android.content.Context
import android.util.Log
import android.widget.Toast
import app.plamotranslate.llm.Language
import app.plamotranslate.models.ModelCatalog
import app.plamotranslate.tts.Speaker
import app.plamotranslate.tts.SupertonicSpeaker
import app.plamotranslate.tts.SystemSpeaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 読み上げに使うもの（設定の画面で選ぶ）。 */
enum class SpeechEngine(val label: String, val note: String) {
    SUPERTONIC("Supertonic 3", "端末内のモデルで読む。自然な声。31言語（中国語は無いので、端末の読み上げで読む）"),
    SYSTEM("端末の読み上げ", "Androidの読み上げエンジン（Googleなど）で読む。追加のダウンロードは要らない"),
}

/**
 * 訳文の読み上げ（2026-11-08、作者の要望）。押したときだけ読み、自動では読まない。
 * 一度に読むのは1つだけで、別のものを押すと前のものを止める。同じものをもう一度押すと止める。
 *
 * Supertonic 3を選んでいても、モデルが無い・読めない言語（中国語）のときは、端末の読み上げで読む。
 * 会話翻訳は読んでいる間とその直後、マイクの音を無音に置き換える（[isAudible]）。読み上げの声を相手の発話として拾わないため。
 */
object ReadAloud {
    private const val TAG = "PlamoTranslate"
    private const val PREFS = "speech"

    /** 読み終わってからも、部屋の反響が消えるまでマイクを無音にしておく時間。 */
    private const val ECHO_MILLIS = 400L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var supertonic: SupertonicSpeaker? = null
    private var system: SystemSpeaker? = null
    @Volatile private var endedAt = 0L

    private val _speaking = MutableStateFlow<String?>(null)

    /** 読んでいるものの識別子（[toggle] の key）。読んでいなければ null。 */
    val speaking: StateFlow<String?> = _speaking.asStateFlow()

    /** 読み上げの声が鳴っている、または鳴り終わった直後か。 */
    fun isAudible(): Boolean = _speaking.value != null || System.currentTimeMillis() - endedAt < ECHO_MILLIS

    /** [key] を読んでいれば止め、そうでなければ [text] を [lang] で読む。 */
    fun toggle(context: Context, key: String, text: String, lang: Language) {
        if (_speaking.value == key) stop() else speak(context, key, text, lang)
    }

    fun speak(context: Context, key: String, text: String, lang: Language) {
        if (text.isBlank()) return
        val app = context.applicationContext
        job?.cancel()
        _speaking.value = key
        job = scope.launch {
            try {
                speakerFor(app, lang.code).speak(text, lang.code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "読み上げに失敗しました", e)
                Toast.makeText(app, e.message ?: "読み上げに失敗しました", Toast.LENGTH_LONG).show()
            } finally {
                if (_speaking.value == key) {
                    _speaking.value = null
                    endedAt = System.currentTimeMillis()
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        if (_speaking.value != null) endedAt = System.currentTimeMillis()
        _speaking.value = null
    }

    fun engine(context: Context): SpeechEngine =
        prefs(context).getString("engine", null)?.let { runCatching { SpeechEngine.valueOf(it) }.getOrNull() }
            ?: SpeechEngine.SUPERTONIC

    fun voice(context: Context): Int = prefs(context).getInt("voice", 0)

    fun speed(context: Context): Float = prefs(context).getFloat("speed", 1f)

    fun save(context: Context, engine: SpeechEngine = engine(context), voice: Int = voice(context), speed: Float = speed(context)) {
        prefs(context).edit().putString("engine", engine.name).putInt("voice", voice).putFloat("speed", speed).apply()
        supertonic?.let {
            it.voice = voice
            it.speed = speed
        }
    }

    /** Supertonic 3の声の数（設定の画面の選択肢）。モデルが無ければ0。 */
    suspend fun supertonicVoices(context: Context): Int =
        if (!ModelCatalog.speechPresent(context)) 0 else supertonic(context.applicationContext).voices()

    /** Supertonic 3を選んでいて、モデルがそろっていて、その言語を読めれば使う。それ以外は端末の読み上げ。 */
    private fun speakerFor(app: Context, lang: String): Speaker {
        if (engine(app) == SpeechEngine.SUPERTONIC && ModelCatalog.speechPresent(app)) {
            val s = supertonic(app)
            if (s.supports(lang)) return s
        }
        return system ?: SystemSpeaker(app).also { system = it }
    }

    private fun supertonic(app: Context): SupertonicSpeaker =
        supertonic ?: SupertonicSpeaker(ModelCatalog.speechDir(app), voice(app), speed(app)).also { supertonic = it }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
