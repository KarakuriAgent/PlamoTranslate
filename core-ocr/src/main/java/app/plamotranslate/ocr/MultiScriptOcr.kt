package app.plamotranslate.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import java.io.File
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 複数の文字認識器を並列に動かし、行ごとに文字種に合う結果を採る（docs/05_画面翻訳.md「文字認識」）。
 *
 * - ML Kitの中国語・日本語・韓国語・英字の認識器（横書き）。
 * - [mangaDir] に漫画モデル（[MangaOcr]）があれば、それも動かす。縦書きの中国語・日本語を読める。
 *
 * 結果の選び方とまとめ方は [BlockMerger]。原文の言語を決めると、要らない認識器を動かさない（[Recognizers]）。
 *
 * [groupLines] が false なら、行を段落・吹き出しにまとめずに1行ずつ返す（カメラ翻訳。2026-11-06）。
 * カメラはコマごとに読み揺れるので、大きなまとまりにすると同じ文として揃わず、訳が出ないため。
 */
class MultiScriptOcr(mangaDir: File? = null, private val groupLines: Boolean = true) : OcrEngine {
    private val manga: MangaOcr? = mangaDir?.let { runCatching { MangaOcr.load(it) }.getOrNull() }

    /** 漫画モデルを読み込めたか（評価・ログ用）。 */
    val hasMangaModel: Boolean get() = manga != null

    private val japanese = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
    private val korean = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
    private val chinese = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    private val latin = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    override suspend fun recognize(bitmap: Bitmap, language: OcrLanguage): List<OcrBlock> = coroutineScope {
        val use = Recognizers.of(language)
        val image = InputImage.fromBitmap(bitmap, 0)
        val ja = if (use.japanese) async { japanese.read(image) } else null
        val ko = if (use.korean) async { korean.read(image) } else null
        val zh = if (use.chinese) async { chinese.read(image) } else null
        val en = if (use.latin) async { latin.read(image) } else null
        val mangaLines = manga?.takeIf { use.manga }?.let { model -> async(Dispatchers.Default) { model.read(bitmap) } }
        val zhText = zh?.await()
        BlockMerger.merge(
            ja = ja?.await()?.toCandidates().orEmpty(),
            ko = ko?.await()?.toCandidates().orEmpty(),
            zh = zhText?.toCandidates().orEmpty(),
            zhGlyphs = zhText?.toGlyphs().orEmpty(),
            manga = mangaLines?.await(),
            latin = en?.await()?.toCandidates().orEmpty(),
            groupLines = groupLines,
        )
    }

    override fun close() {
        japanese.close()
        korean.close()
        chinese.close()
        latin.close()
        manga?.close()
    }

    private suspend fun TextRecognizer.read(image: InputImage): Text = process(image).await()

    /** 1文字ずつの位置。縦書きの列を組み直すのに使う（[VerticalColumns]）。 */
    private fun Text.toGlyphs(): List<Glyph> = textBlocks.flatMap { block ->
        block.lines.flatMap { line ->
            line.elements.flatMap { element ->
                element.symbols.mapNotNull { s -> s.boundingBox?.let { Glyph(s.text, Rect(it)) } }
            }
        }
    }

    private fun Text.toCandidates(): List<Candidate> = textBlocks.flatMap { block ->
        block.lines.mapNotNull { line -> line.boundingBox?.let { Candidate(line.text, Rect(it)) } }
    }
}

/**
 * 原文の言語ごとに動かす認識器。言語を決めると要らない認識器を止めるので速い（韓国語の漫画1ページ4.0秒→2.0秒、2026-11-05）。
 *
 * - 中国語：漫画モデル（縦書き）＋中国語の認識器（横書き・ラテン文字）。
 * - 韓国語：韓国語の認識器だけ（漫画モデルは韓国語を読めない）。
 * - ラテン文字（英語・フランス語・ベトナム語など）：英字専用の認識器だけ（英語の漫画の手書き風の大文字を、中国語の認識器は読み違えた。2026-11-06評価）。
 * - 自動：すべて。日本語の認識器は、日本語の行を見分けて訳さないために使う。
 */
internal data class Recognizers(
    val japanese: Boolean,
    val korean: Boolean,
    val chinese: Boolean,
    val latin: Boolean,
    val manga: Boolean,
) {
    companion object {
        fun of(language: OcrLanguage): Recognizers = when (language) {
            OcrLanguage.AUTO -> Recognizers(japanese = true, korean = true, chinese = true, latin = true, manga = true)
            OcrLanguage.CHINESE -> Recognizers(japanese = false, korean = false, chinese = true, latin = false, manga = true)
            OcrLanguage.KOREAN -> Recognizers(japanese = false, korean = true, chinese = false, latin = false, manga = false)
            OcrLanguage.LATIN -> Recognizers(japanese = false, korean = false, chinese = false, latin = true, manga = false)
        }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
