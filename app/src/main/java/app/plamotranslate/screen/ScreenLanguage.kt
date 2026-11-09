package app.plamotranslate.screen

import app.plamotranslate.llm.Language
import app.plamotranslate.ocr.OcrLanguage

/*
 * 画面翻訳の原文の言語（浮遊ボタンの長押しで選ぶ。2026-11-05、作者の要望）。
 * 言語を足すときは、OcrLanguage（core-ocr）と Recognizers（使う認識器）と、ここの3つの表をそろえる。
 */

/** 画面翻訳の訳す先。今は日本語に固定する。 */
internal val SCREEN_TARGET = Language.JA

/** 長押しメニューに出す名前。 */
internal val OcrLanguage.menuName: String
    get() = when (this) {
        OcrLanguage.AUTO -> "自動"
        OcrLanguage.CHINESE -> "中国語"
        OcrLanguage.KOREAN -> "韓国語"
        OcrLanguage.LATIN -> "英語"
    }

/** 浮遊ボタンの文字。自動は「訳」、言語を決めたら「訳」の下にその言語を出す。 */
internal val OcrLanguage.buttonLabel: String
    get() = when (this) {
        OcrLanguage.AUTO -> "訳"
        OcrLanguage.CHINESE -> "訳\n中"
        OcrLanguage.KOREAN -> "訳\n韓"
        OcrLanguage.LATIN -> "訳\n英"
    }

/** 翻訳に渡す原文の言語。自動なら null で、PLaMoが原文から判定する（docs/03_翻訳コア.md）。 */
internal val OcrLanguage.sourceLanguage: Language?
    get() = when (this) {
        OcrLanguage.AUTO -> null
        OcrLanguage.CHINESE -> Language.ZH
        OcrLanguage.KOREAN -> Language.KO
        OcrLanguage.LATIN -> Language.EN
    }
