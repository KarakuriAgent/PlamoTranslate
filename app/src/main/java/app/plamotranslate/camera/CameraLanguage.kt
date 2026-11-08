package app.plamotranslate.camera

import android.content.Context
import app.plamotranslate.llm.Language
import app.plamotranslate.ocr.OcrLanguage

/*
 * カメラ翻訳の原文の言語（2026-11-08、作者の要望「ライブラリとモデルで対応できる言語は全て選べるように」）。
 * 翻訳（PLaMo 3 Translate）は53言語を訳せるので、選べる言語は文字認識（ML Kit）が読める文字で決まる。
 * 同梱している認識器はラテン文字・中国語・日本語・韓国語の4つ（docs/10_カメラ翻訳.md「原文の言語」）。
 * アラビア文字・キリル文字・タイ文字・デーヴァナーガリーなどの言語は、読める認識器が無いので出さない。
 */

/** ラテン文字で書く言語。ML Kitの英字の認識器で読む。セルビア語は看板がキリル文字のことが多いので入れない。 */
private val LATIN_SCRIPT = setOf(
    Language.EN, Language.AZ, Language.IT, Language.ID, Language.UZ, Language.ET, Language.NL, Language.CA,
    Language.HR, Language.JV, Language.ES, Language.SK, Language.SL, Language.SW, Language.CEB, Language.TL,
    Language.DE, Language.HU, Language.EU, Language.FR, Language.VI, Language.PL, Language.MS, Language.LV,
    Language.LT,
)

/** その言語の原文を読むのに動かす認識器。読める認識器が無ければ null。
 * 日本語も null にして原文の候補に出さない。日本語の原文（訳す先を日本語以外にしたとき）は、自動ですべての認識器を動かして読む。 */
internal val Language.ocrLanguage: OcrLanguage?
    get() = when (this) {
        Language.KO -> OcrLanguage.KOREAN
        Language.ZH, Language.ZH_HANT -> OcrLanguage.CHINESE
        in LATIN_SCRIPT -> OcrLanguage.LATIN
        else -> null
    }

/** 訳す先に選べる言語。PLaMoはどの言語へも訳せるので、全53言語。 */
internal val CAMERA_TARGETS: List<Language> = Language.entries

/** 選べる原文の言語（自動を除く）。並びは [Language] の順（よく使う言語が先、残りは五十音順）。 */
internal val CAMERA_SOURCES: List<Language> = Language.entries.filter { it.ocrLanguage != null }

/** 下の欄にチップで並べる言語。ほかの言語は「他の言語」のメニューから選ぶ。 */
internal val CAMERA_QUICK_SOURCES: List<Language> = listOf(Language.KO, Language.EN, Language.ZH)

/** チップに出す短い名前。 */
internal val Language.chipName: String
    get() = if (this == Language.ZH) "中国語" else label

/**
 * 選んだ原文の言語を端末に残し、次に始めたときも引き継ぐ。null は自動。
 * 以前は認識器の種類（OcrLanguage）を "language" に保存していたので、新しい値が無ければそれを読み替える。
 */
internal class CameraSourcePreference(context: Context) {
    private val prefs = context.getSharedPreferences("camera_translate", Context.MODE_PRIVATE)

    var value: Language?
        get() {
            val code = prefs.getString(KEY, null) ?: return legacy()
            return Language.ofCode(code)?.takeIf { it in CAMERA_SOURCES }
        }
        set(language) = prefs.edit().putString(KEY, language?.code ?: AUTO).apply()

    private fun legacy(): Language? = when (OcrLanguage.parse(prefs.getString(LEGACY_KEY, null))) {
        OcrLanguage.AUTO -> null
        OcrLanguage.CHINESE -> Language.ZH
        OcrLanguage.KOREAN -> Language.KO
        OcrLanguage.LATIN -> Language.EN
    }

    private companion object {
        const val KEY = "source"
        const val LEGACY_KEY = "language"
        const val AUTO = "auto"
    }
}

/** 選んだ訳す先の言語を端末に残す。既定は日本語。 */
internal class CameraTargetPreference(context: Context) {
    private val prefs = context.getSharedPreferences("camera_translate", Context.MODE_PRIVATE)

    var value: Language
        get() = Language.ofCode(prefs.getString(KEY, null).orEmpty()) ?: Language.JA
        set(language) = prefs.edit().putString(KEY, language.code).apply()

    private companion object {
        const val KEY = "target"
    }
}