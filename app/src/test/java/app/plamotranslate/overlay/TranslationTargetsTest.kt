package app.plamotranslate.overlay

import android.graphics.Rect
import app.plamotranslate.llm.Language
import app.plamotranslate.ocr.OcrBlock
import app.plamotranslate.ocr.Script
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 訳す必要があるかの判定は訳す先で変わる。呼ぶ側（画面翻訳・カメラ翻訳）は自分の訳す先を渡す
 * （2026-11-09、カメラ翻訳が画面翻訳の訳す先「日本語」で判定し、訳す先を日本語以外にすると日本語の看板を訳さなかった）。
 */
class TranslationTargetsTest {
    private fun block(text: String, script: Script) = OcrBlock(text, Rect(), script)

    @Test
    fun japaneseIsSkippedOnlyWhenTargetIsJapanese() {
        val sign = block("押してください", Script.KANA)
        assertFalse(sign.needsTranslation(Language.JA))
        assertTrue(sign.needsTranslation(Language.EN))
        assertTrue(sign.needsTranslation(Language.KO))
    }

    @Test
    fun koreanIsSkippedOnlyWhenTargetIsKorean() {
        val label = block("송풍", Script.HANGUL)
        assertTrue(label.needsTranslation(Language.JA))
        assertFalse(label.needsTranslation(Language.KO))
    }
}
