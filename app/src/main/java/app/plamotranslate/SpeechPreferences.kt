package app.plamotranslate

import android.content.Context
import app.plamotranslate.tts.SupertonicSpeaker

/** 読み上げに使うもの（設定の画面で選ぶ）。 */
enum class SpeechEngine(val label: String, val note: String) {
    SUPERTONIC("Supertonic 3", "端末内のモデルで読む。自然な声。31言語（中国語は無いので、端末の読み上げで読む）"),
    SYSTEM("端末の読み上げ", "Androidの読み上げエンジン（Googleなど）で読む。追加のダウンロードは要らない"),
}

/**
 * 読み上げの設定（読むもの・声・速さ・段数）。設定の画面（[SpeechCard]）が書き、[ReadAloud] が読むたびに読む
 * （docs/11「3」、2026-11-09に保存を再生から切り出した）。
 */
class SpeechPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("speech", Context.MODE_PRIVATE)

    var engine: SpeechEngine
        get() = prefs.getString("engine", null)?.let { runCatching { SpeechEngine.valueOf(it) }.getOrNull() }
            ?: SpeechEngine.SUPERTONIC
        set(value) = prefs.edit().putString("engine", value.name).apply()

    /** Supertonic 3の声（voice.bin の何番目の声か）。 */
    var voice: Int
        get() = prefs.getInt("voice", 0)
        set(value) = prefs.edit().putInt("voice", value).apply()

    var speed: Float
        get() = prefs.getFloat("speed", 1f)
        set(value) = prefs.edit().putFloat("speed", value).apply()

    /** Supertonic 3の音を作る段数（設定の画面のスライダー）。 */
    var steps: Int
        get() = prefs.getInt("steps", SupertonicSpeaker.DEFAULT_STEPS)
        set(value) = prefs.edit().putInt("steps", value).apply()
}
