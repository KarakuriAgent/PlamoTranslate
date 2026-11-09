package app.plamotranslate

import app.plamotranslate.models.ModelCatalog

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Bundle
import android.os.SystemClock
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.activity.ComponentActivity
import app.plamotranslate.ocr.MultiScriptOcr
import app.plamotranslate.ocr.OcrLanguage
import app.plamotranslate.screen.BatchTranslator
import app.plamotranslate.screen.OverlaySlot
import app.plamotranslate.screen.OverlayStyle
import app.plamotranslate.screen.SCREEN_TARGET
import app.plamotranslate.screen.ScreenTranslateService
import app.plamotranslate.screen.Tategaki
import app.plamotranslate.screen.VerticalTextView
import app.plamotranslate.screen.selectTargets
import app.plamotranslate.screen.sourceLanguage
import app.plamotranslate.screen.spToPx
import app.plamotranslate.screen.dp
import app.plamotranslate.screen.statusBarHeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 画面翻訳の評価用（デバッグビルドだけ）。保存したスクリーンショットに、画面翻訳と同じ
 * 文字認識→訳す対象の選別（selectTargets）→まとめ訳しをかけ、所要時間と訳をJSON Linesに、重ね表示の見た目をPNGに書き出す。
 *
 * ふだんは tools/run-bench.ps1 から動かす（画像の転送・起動・取り出し・採点まで行う。docs/05_画面翻訳.md「評価」）。
 * 原文の言語を指定するときは `--es lang CHINESE` のように渡す（既定は自動）。
 */
class BenchActivity : ComponentActivity() {
    private lateinit var log: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        log = TextView(this).apply { setPadding(32, 96, 32, 32); textSize = 13f }
        setContentView(log)
        lifecycleScope.launch { run() }
    }

    private fun say(text: String) {
        Log.i(TAG, text)
        log.append(text + "\n")
    }

    private suspend fun run() {
        val root = File(getExternalFilesDir(null), "bench")
        val input = File(root, "in")
        val output = File(root, "out").apply { mkdirs() }
        val images = input.listFiles { f -> f.extension.lowercase() in setOf("jpg", "jpeg", "png") }?.sortedBy { it.name }
        if (images.isNullOrEmpty()) {
            say("ベンチ: 画像がありません: ${input.path}")
            return
        }
        val engine = EngineHolder.awaitEngine(this)
        if (engine == null) {
            say("ベンチ: 翻訳モデルを読めません")
            return
        }
        val language = OcrLanguage.parse(intent.getStringExtra("lang"))
        say("ベンチ: 原文の言語 $language")
        val ocr = MultiScriptOcr(ModelCatalog.mangaDir(this))
        say("ベンチ: 漫画モデル ${if (ocr.hasMangaModel) "あり" else "なし（ML Kitだけ）"}")
        val statusBar = statusBarHeight()
        val results = File(output, "results.jsonl")
        results.writeText("")
        say("ベンチ: ${images.size}枚を処理します")

        for ((n, image) in images.withIndex()) {
            val bitmap = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(image.path, BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                })
            } ?: continue
            val t0 = SystemClock.uptimeMillis()
            val blocks = ocr.recognize(bitmap, language)
            val ocrMillis = SystemClock.uptimeMillis() - t0
            val targets = selectTargets(blocks, bitmap, statusBar)

            // 画像ごとに新しく作り、前の画像の訳の控えを使わない
            val translator = BatchTranslator(engine, SCREEN_TARGET, language.sourceLanguage)
            val translations = Array(targets.size) { StringBuilder() }
            val t1 = SystemClock.uptimeMillis()
            translator.translate(
                targets.map { it.sourceText },
                onDelta = { i, d -> translations[i].append(d) },
                onReplace = { i, t -> translations[i].setLength(0); translations[i].append(t) },
            )
            val translateMillis = SystemClock.uptimeMillis() - t1

            val fits = withContext(Dispatchers.IO) {
                render(bitmap, targets.map { it.slot }, translations.map { it.toString() },
                    File(output, image.nameWithoutExtension + ".png"))
            }
            val json = JSONObject()
                .put("image", image.name)
                .put("ocrMs", ocrMillis)
                .put("translateMs", translateMillis)
                .put("blocks", blocks.size)
                .put("targets", JSONArray().apply {
                    targets.forEachIndexed { i, target ->
                        val b = target.block
                        put(JSONObject()
                            .put("script", b.script.name)
                            .put("vertical", b.vertical)
                            .put("bounds", JSONArray(listOf(b.bounds.left, b.bounds.top, b.bounds.right, b.bounds.bottom)))
                            .put("source", b.text)
                            .put("translation", translations[i].toString())
                            .put("textSp", fits[i].first)
                            .put("fitted", fits[i].second))
                    }
                })
                .put("skipped", JSONArray().apply {
                    val picked = targets.map { it.block }.toSet()
                    blocks.filter { it !in picked }.forEach { b ->
                        put(JSONObject().put("source", b.text)
                            .put("bounds", JSONArray(listOf(b.bounds.left, b.bounds.top, b.bounds.right, b.bounds.bottom))))
                    }
                })
            withContext(Dispatchers.IO) { results.appendText(json.toString() + "\n") }
            bitmap.recycle()
            say("ベンチ: ${n + 1}/${images.size} ${image.name} 認識${blocks.size}件→訳${targets.size}件 文字認識${ocrMillis}ms 翻訳${translateMillis}ms")
        }
        ocr.close()
        say("ベンチ: 完了 ${output.path}")
    }

    /**
     * 重ね表示と同じ枠の広げ方・文字の大きさの範囲（OverlayStyle）で、訳の枠を元の位置に描いた画像を半分の大きさで保存する。
     * 枠ごとに（使った文字の大きさsp, 最小の大きさでも枠に収まったか）を返す。
     */
    private fun render(source: Bitmap, slots: List<OverlaySlot>, texts: List<String>, file: File): List<Pair<Float, Boolean>> {
        val fits = mutableListOf<Pair<Float, Boolean>>()
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val fill = Paint()
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG)
        val density = spToPx(1f)
        val innerPad = dp(OverlayStyle.H_PADDING_DP)
        slots.forEachIndexed { i, slot ->
            val box = OverlayStyle.padded(this, slot.rect)
            fill.color = slot.colors.background
            paint.color = slot.colors.text
            canvas.drawRect(box, fill)
            if (slot.vertical) {
                // 縦書きの原文には縦書きで描く（画面の重ね表示と同じ Tategaki）
                val pad = VerticalTextView.PADDING_DP * resources.displayMetrics.density
                val area = RectF(box).apply { inset(pad, pad) }
                val (px, fitted) = Tategaki.fit(texts[i], area.width(), area.height(), OverlayStyle.MIN_SP * density, OverlayStyle.MAX_SP * density)
                paint.textSize = px
                Tategaki.draw(canvas, texts[i], area, paint)
                fits += px / density to fitted
                return@forEachIndexed
            }
            // 枠に収まるまで文字を小さくする
            var size = OverlayStyle.MAX_SP.toFloat()
            var layout: StaticLayout
            while (true) {
                paint.textSize = size * density
                layout = StaticLayout.Builder.obtain(texts[i], 0, texts[i].length, paint, (box.width() - 2 * innerPad).coerceAtLeast(1))
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
                if (layout.height <= box.height() || size <= OverlayStyle.MIN_SP) break
                size -= 1f
            }
            fits += size to (layout.height <= box.height())
            canvas.save()
            canvas.translate((box.left + innerPad).toFloat(), box.top.toFloat())
            canvas.clipRect(0, 0, box.width() - 2 * innerPad, box.height())
            layout.draw(canvas)
            canvas.restore()
        }
        val small = Bitmap.createScaledBitmap(out, out.width / 2, out.height / 2, true)
        file.outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
        small.recycle()
        out.recycle()
        return fits
    }

    private companion object {
        const val TAG = "PlamoTranslate"
    }
}
