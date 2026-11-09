package app.plamotranslate.screen

import android.content.Context
import app.plamotranslate.ocr.OcrLanguage

/**
 * 画面翻訳の設定（原文の言語）。端末に残し、次に始めたときも引き継ぐ。
 * カメラ翻訳は言語そのものを選ぶので別に持つ（camera/CameraPreferences.kt）。
 */
internal class ScreenPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("screen_translate", Context.MODE_PRIVATE)

    /** 浮遊ボタンの長押しで選んだ原文の言語。読めなければ自動。 */
    var language: OcrLanguage
        get() = OcrLanguage.parse(prefs.getString(KEY_LANGUAGE, null))
        set(language) = prefs.edit().putString(KEY_LANGUAGE, language.name).apply()

    private companion object {
        const val KEY_LANGUAGE = "language"
    }
}
