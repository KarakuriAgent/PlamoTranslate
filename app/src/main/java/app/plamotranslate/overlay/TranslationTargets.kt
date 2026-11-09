package app.plamotranslate.overlay

import android.graphics.Bitmap
import android.graphics.Rect
import app.plamotranslate.llm.Language
import app.plamotranslate.ocr.EnglishText
import app.plamotranslate.ocr.OcrBlock
import app.plamotranslate.ocr.Script
import app.plamotranslate.ocr.TextQuality

/** 訳の枠1つ：撮影画像の座標での位置、縦書きか、元の画面から読み取った背景色と文字色。 */
data class OverlaySlot(val rect: Rect, val vertical: Boolean, val colors: BoxColors = BoxColors.DEFAULT)

/** 訳す1件：認識したまとまり、訳の枠（位置・縦書き・色）、翻訳に渡す原文。 */
internal class TranslationTarget(val block: OcrBlock, val slot: OverlaySlot) {
    val sourceText: String = block.joinedText()
}

/**
 * 認識結果から訳す対象を選び、訳の枠の色を撮影画像から読み取る。
 * 画面翻訳（ScreenTranslateService）とカメラ翻訳（LiveTranslator）が使い、評価（BenchActivity）もここを通して同じ処理を測る。
 * [target] は訳す先。仮名やハングルを訳すかは訳す先で変わるので、呼ぶ側が自分の訳す先を渡す。
 *
 * 状態バー（時刻・通信速度など）にかかるまとまりと、訳す必要のないまとまり（[needsTranslation]）は除く。
 * 色を読み取るので、撮影画像を捨てる前に呼ぶ。
 */
internal fun selectTargets(
    blocks: List<OcrBlock>,
    bitmap: Bitmap,
    statusBarHeight: Int,
    target: Language,
): List<TranslationTarget> = blocks
    .filter { it.bounds.top >= statusBarHeight && it.needsTranslation(target) }
    .map { TranslationTarget(it, OverlaySlot(it.bounds, it.vertical, ColorSampler.sample(bitmap, it.bounds))) }

/**
 * 訳す必要がある文字か。訳先が日本語なら、仮名を含む（日本語の）ブロックと、
 * 文字を含まないブロック（数字・記号だけ）は飛ばす。欧文は1文字だけのブロックも飛ばすが、
 * 漢字・ハングルは1文字でも意味を持つので訳す（漫画の「咦」「嘟」、2026-11-05評価）。
 * 崩れた認識結果（[TextQuality]）も訳さない。訳しても意味がなく、生成の時間だけを使う。
 */
internal fun OcrBlock.needsTranslation(target: Language): Boolean {
    val letters = text.count { it.isLetter() }
    val minLetters = if (script == Script.HAN || script == Script.HANGUL) 1 else 2
    if (letters < minLetters) return false
    if (TextQuality.looksGarbled(text)) return false
    return when (script) {
        Script.OTHER -> false
        Script.KANA -> target != Language.JA
        Script.HANGUL -> target != Language.KO
        else -> true
    }
}

/**
 * 翻訳に渡す原文。行の折り返しをつなぐ。韓国語・欧文は単語の間で折り返すので空白、
 * 日本語・中国語は詰めてつなぐ。英文は大文字だけの文やハイフンの折り返しを整える（[EnglishText]）。
 */
internal fun OcrBlock.joinedText(): String {
    if (script == Script.LATIN) return EnglishText.normalize(text.lines())
    val separator = if (script == Script.KANA || script == Script.HAN) "" else " "
    return text.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(separator)
}
