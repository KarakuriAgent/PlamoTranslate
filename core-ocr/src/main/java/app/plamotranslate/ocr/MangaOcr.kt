package app.plamotranslate.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 漫画モデルが読んだ1行。[confidence] は採用した字の確率の平均（0〜1）。 */
internal data class MangaLine(val text: String, val bounds: Rect, val confidence: Float)

/**
 * 縦書きを読める漫画向けの文字認識（PP-OCRv6 manga、Hugging Face `Kellenok/PP-OCRv6_manga`、Apache-2.0）。
 * 中国語・日本語を読む。韓国語は読めない（[BlockMerger] がML Kitの結果と使い分ける）。
 *
 * 前処理と復号はモデルカードと例のコードに合わせた（docs/05_画面翻訳.md「判断の記録」）。
 * - 検出：長辺960px（32の倍数）、BGRで (x/255 − mean)/std。確率が0.3を超える画素のまとまりを1行とし、
 *   平均確率0.6未満は捨てる。DBの後処理の広げ幅（面積×1.5÷周長）で枠を広げる。
 * - 認識：高さ48px、幅は縦横比のまま。縦長（高さ≧幅×1.5）の行は90度左に回して横書きとして読む。
 *   BGRで (x/255 − 0.5)/0.5。CTCを貪欲に復号する（0番は空白記号、辞書の後ろに半角空白）。
 *
 * OpenCVは使わず、後処理はこのクラスで行う（APKを大きくしないため）。
 */
internal class MangaOcr private constructor(
    private val env: OrtEnvironment,
    private val det: OrtSession,
    private val rec: OrtSession,
    private val vocab: List<String>,
) : AutoCloseable {

    fun read(bitmap: Bitmap): List<MangaLine> = detect(bitmap).mapNotNull { box -> recognize(bitmap, box) }

    private fun detect(bitmap: Bitmap): List<Rect> {
        val scale = DET_LIMIT.toFloat() / max(bitmap.width, bitmap.height)
        val nw = max(32, (bitmap.width * scale / 32).roundToInt() * 32)
        val nh = max(32, (bitmap.height * scale / 32).roundToInt() * 32)
        val resized = Bitmap.createScaledBitmap(bitmap, nw, nh, true)
        val input = toTensor(resized, DET_MEAN, DET_STD)
        if (resized !== bitmap) resized.recycle()
        val prob = det.run(mapOf(det.inputNames.first() to input)).use { out ->
            val buffer = (out[0] as OnnxTensor).floatBuffer
            FloatArray(buffer.remaining()).also { buffer.get(it) }
        }
        input.close()

        val sx = bitmap.width.toFloat() / nw
        val sy = bitmap.height.toFloat() / nh
        val visited = BooleanArray(nw * nh)
        val queue = IntArray(nw * nh)
        val boxes = mutableListOf<Rect>()
        for (start in prob.indices) {
            if (visited[start] || prob[start] <= DET_THRESH) continue
            // 4近傍でつながった画素のまとまりを1行とみなす
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var minX = nw; var minY = nh; var maxX = 0; var maxY = 0
            var sum = 0f
            while (head < tail) {
                val p = queue[head++]
                val x = p % nw
                val y = p / nw
                sum += prob[p]
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                for (q in intArrayOf(p - 1, p + 1, p - nw, p + nw)) {
                    if (q < 0 || q >= prob.size || visited[q] || prob[q] <= DET_THRESH) continue
                    if ((q == p - 1 && x == 0) || (q == p + 1 && x == nw - 1)) continue
                    visited[q] = true
                    queue[tail++] = q
                }
            }
            if (tail < 9 || sum / tail < BOX_THRESH) continue
            val w = maxX - minX + 1
            val h = maxY - minY + 1
            val d = w * h * UNCLIP / (2f * (w + h))
            boxes += Rect(
                ((minX - d) * sx).roundToInt().coerceAtLeast(0),
                ((minY - d) * sy).roundToInt().coerceAtLeast(0),
                ((maxX + 1 + d) * sx).roundToInt().coerceAtMost(bitmap.width),
                ((maxY + 1 + d) * sy).roundToInt().coerceAtMost(bitmap.height),
            )
        }
        return boxes.filter { it.width() >= 2 && it.height() >= 2 }
    }

    private fun recognize(bitmap: Bitmap, box: Rect): MangaLine? {
        var crop = Bitmap.createBitmap(bitmap, box.left, box.top, box.width(), box.height())
        if (crop.height >= crop.width * 1.5f) {
            // 縦書きの行は90度左に回して横書きとして読む
            val rotated = Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height, Matrix().apply { postRotate(-90f) }, true)
            crop.recycle()
            crop = rotated
        }
        val nw = (REC_HEIGHT * crop.width.toFloat() / crop.height).roundToInt().coerceIn(16, 3200)
        val resized = Bitmap.createScaledBitmap(crop, nw, REC_HEIGHT, true)
        crop.recycle()
        val input = toTensor(resized, REC_MEAN, REC_STD)
        resized.recycle()
        val (text, confidence) = rec.run(mapOf(rec.inputNames.first() to input)).use { out ->
            val tensor = out[0] as OnnxTensor
            val shape = tensor.info.shape   // [1, T, V]
            val steps = shape[1].toInt()
            val classes = shape[2].toInt()
            val logits = tensor.floatBuffer
            val sb = StringBuilder()
            var probSum = 0f
            var count = 0
            var prev = -1
            for (t in 0 until steps) {
                var best = 0
                var bestValue = Float.NEGATIVE_INFINITY
                for (v in 0 until classes) {
                    val value = logits.get(t * classes + v)
                    if (value > bestValue) { bestValue = value; best = v }
                }
                if (best != prev && best != 0 && best < vocab.size) {
                    sb.append(vocab[best])
                    probSum += bestValue
                    count++
                }
                prev = best
            }
            sb.toString() to (if (count > 0) probSum / count else 0f)
        }
        input.close()
        if (text.isBlank()) return null
        return MangaLine(text, box, confidence)
    }

    /** ARGBのビットマップを、BGRの順・チャンネル優先（NCHW）で正規化した入力にする。 */
    private fun toTensor(bitmap: Bitmap, mean: FloatArray, std: FloatArray): OnnxTensor {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val plane = w * h
        val data = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val c = pixels[i]
            val r = (c shr 16 and 0xFF) / 255f
            val g = (c shr 8 and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            data[i] = (b - mean[0]) / std[0]
            data[plane + i] = (g - mean[1]) / std[1]
            data[2 * plane + i] = (r - mean[2]) / std[2]
        }
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, h.toLong(), w.toLong()))
    }

    override fun close() {
        det.close()
        rec.close()
    }

    companion object {
        private const val DET_LIMIT = 960
        private const val DET_THRESH = 0.3f
        private const val BOX_THRESH = 0.6f
        private const val UNCLIP = 1.5f
        private const val REC_HEIGHT = 48
        private val DET_MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val DET_STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        private val REC_MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
        private val REC_STD = floatArrayOf(0.5f, 0.5f, 0.5f)

        /**
         * [dir] に det.onnx・rec.onnx・dict.txt があれば読み込む。無ければ null（ML Kitだけで動く）。
         * 置き場は `files/models/ocr/manga/`（docs/06_開発環境.md）。
         */
        fun load(dir: File, threads: Int = 4): MangaOcr? {
            val detFile = File(dir, "det.onnx")
            val recFile = File(dir, "rec.onnx")
            val dictFile = File(dir, "dict.txt")
            if (!detFile.canRead() || !recFile.canRead() || !dictFile.canRead()) return null
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
            val vocab = listOf("<blank>") + dictFile.readLines(Charsets.UTF_8) + listOf(" ")
            return MangaOcr(env, env.createSession(detFile.path, options), env.createSession(recFile.path, options), vocab)
        }
    }
}
