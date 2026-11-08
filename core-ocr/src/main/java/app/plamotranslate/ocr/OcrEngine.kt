package app.plamotranslate.ocr

import android.graphics.Bitmap
import android.graphics.Rect

/** 文字の種類。どの認識器の結果を採るかと、訳す必要があるかの判断に使う。 */
enum class Script { HANGUL, KANA, HAN, LATIN, OTHER }

/** 画面上の文字のまとまり（段落・吹き出しなど）。[bounds] は撮影画像の座標。[vertical] は縦書き。 */
data class OcrBlock(
    val text: String,
    val bounds: Rect,
    val script: Script,
    val vertical: Boolean = false,
)

/**
 * 原文の文字の種類。どの認識器を動かすかを決める（画面翻訳は浮遊ボタンの長押し、カメラ翻訳は言語の選択から決まる）。
 * AUTO はすべての認識器を動かして行ごとに選ぶ。決めると要らない認識器を動かさないので速い。
 * LATIN は英語に加え、フランス語・ベトナム語などラテン文字で書く言語（2026-11-08、カメラ翻訳の言語を広げたときに ENGLISH から改名）。
 */
enum class OcrLanguage {
    AUTO, CHINESE, KOREAN, LATIN;

    companion object {
        /** 保存した名前から戻す。改名前の ENGLISH も読む。読めなければ自動。 */
        fun parse(name: String?): OcrLanguage = if (name == "ENGLISH") LATIN else entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/** 文字認識の差し替え口（CLAUDE.md「差し替え可能に作る」）。 */
interface OcrEngine {
    /** 読み順（上から下、左から右）に並べて返す。[language] を決めると、その言語に要る認識器だけを動かす。 */
    suspend fun recognize(bitmap: Bitmap, language: OcrLanguage = OcrLanguage.AUTO): List<OcrBlock>
    fun close()
}
