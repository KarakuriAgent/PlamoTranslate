package app.plamotranslate.camera

import android.content.Context
import app.plamotranslate.llm.Language
import app.plamotranslate.ocr.OcrLanguage

/**
 * カメラ翻訳の設定（原文の言語・訳す先・表のモード）。端末に残し、次に開いたときも引き継ぐ
 * （docs/11「6」、2026-11-09に1つにまとめた）。
 */
internal class CameraPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("camera_translate", Context.MODE_PRIVATE)

    /**
     * 原文の言語。null は自動。
     * 以前は認識器の種類（OcrLanguage）を "language" に保存していたので、新しい値が無ければそれを読み替える。
     */
    var source: Language?
        get() {
            val code = prefs.getString(KEY_SOURCE, null) ?: return legacySource()
            return Language.ofCode(code)?.takeIf { it in CAMERA_SOURCES }
        }
        set(language) = prefs.edit().putString(KEY_SOURCE, language?.code ?: AUTO).apply()

    /** 訳す先。既定は日本語。 */
    var target: Language
        get() = Language.ofCode(prefs.getString(KEY_TARGET, null).orEmpty()) ?: Language.JA
        set(language) = prefs.edit().putString(KEY_TARGET, language.code).apply()

    /** 表のモード（1行ずつ訳す）か。文章のモードは行を段落にまとめて訳す。 */
    var tableMode: Boolean
        get() = prefs.getBoolean(KEY_TABLE, false)
        set(table) = prefs.edit().putBoolean(KEY_TABLE, table).apply()

    private fun legacySource(): Language? = when (OcrLanguage.parse(prefs.getString(KEY_LEGACY_SOURCE, null))) {
        OcrLanguage.AUTO -> null
        OcrLanguage.CHINESE -> Language.ZH
        OcrLanguage.KOREAN -> Language.KO
        OcrLanguage.LATIN -> Language.EN
    }

    private companion object {
        const val KEY_SOURCE = "source"
        const val KEY_LEGACY_SOURCE = "language"
        const val KEY_TARGET = "target"
        const val KEY_TABLE = "table"
        const val AUTO = "auto"
    }
}
