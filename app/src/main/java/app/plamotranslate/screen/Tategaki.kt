package app.plamotranslate.screen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.view.View
import app.plamotranslate.overlay.spToPx

/**
 * 日本語を縦書きで描く（2026-11-05、作者の要望「縦書きの原文には縦書きの日本語で」）。
 * 右の列から左へ、各列は上から下へ1文字ずつ置く。枠に収まる最大の文字の大きさを選ぶ。
 */
object Tategaki {
    /** 縦書きでは90度回して描く記号（長音・ダッシュ・波線・三点リーダーなど）。 */
    private const val ROTATE = "ー－-—―‐~～〜…‥=＝"

    /** 縦書き用の字形に置き換える括弧類。 */
    private val VERTICAL_FORMS = mapOf(
        '「' to '﹁', '」' to '﹂', '『' to '﹃', '』' to '﹄',
        '（' to '︵', '）' to '︶', '(' to '︵', ')' to '︶',
        '【' to '︻', '】' to '︼', '〔' to '︹', '〕' to '︺',
    )

    /** 字の右上に寄せて描く句読点と小書きの仮名。 */
    private const val SHIFT = "、。，．,."
    private const val SMALL = "ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ"

    private const val LINE = 1.05f   // 字送り（字の大きさに対する比）
    private const val COLUMN = 1.2f  // 列送り

    /** [width]×[height] に [text] が収まる最大の文字の大きさ（px）を探す。収まらなければ最小を返す。 */
    fun fit(text: String, width: Float, height: Float, minPx: Float, maxPx: Float): Pair<Float, Boolean> {
        val n = chars(text).size.coerceAtLeast(1)
        var size = maxPx
        while (size > minPx) {
            if (fits(n, size, width, height)) return size to true
            size -= 1f
        }
        return minPx to fits(n, minPx, width, height)
    }

    private fun fits(n: Int, size: Float, width: Float, height: Float): Boolean {
        val perColumn = (height / (size * LINE)).toInt()
        if (perColumn < 1) return false
        val columns = (n + perColumn - 1) / perColumn
        return columns * size * COLUMN <= width
    }

    /** [box] の中に縦書きで描く。文字の大きさは [paint] の textSize を使う。 */
    fun draw(canvas: Canvas, text: String, box: RectF, paint: TextPaint) {
        val size = paint.textSize
        val perColumn = (box.height() / (size * LINE)).toInt().coerceAtLeast(1)
        val metrics = paint.fontMetrics
        chars(text).forEachIndexed { i, ch ->
            val col = i / perColumn
            val row = i % perColumn
            val cx = box.right - (col + 0.5f) * size * COLUMN
            val top = box.top + row * size * LINE
            // 字の中心が (cx, top + size/2) に来るようにする
            val baseline = top + size / 2 - (metrics.ascent + metrics.descent) / 2
            val s = ch.toString()
            when {
                ch in ROTATE -> {
                    canvas.save()
                    canvas.rotate(90f, cx, top + size / 2)
                    canvas.drawText(s, cx - paint.measureText(s) / 2, baseline, paint)
                    canvas.restore()
                }
                ch in SHIFT -> canvas.drawText(s, cx - paint.measureText(s) / 2 + size * 0.55f, baseline - size * 0.55f, paint)
                ch in SMALL -> canvas.drawText(s, cx - paint.measureText(s) / 2 + size * 0.12f, baseline - size * 0.12f, paint)
                else -> {
                    val v = VERTICAL_FORMS[ch]?.toString() ?: s
                    canvas.drawText(v, cx - paint.measureText(v) / 2, baseline, paint)
                }
            }
        }
    }

    /** 改行と空白を除いた1文字ずつの並び。 */
    private fun chars(text: String): List<Char> = text.filterNot { it == '\n' || it == '\r' || it == ' ' || it == '　' }.toList()
}

/** 重ね表示で使う縦書きの訳の枠。文字は枠に合わせて [OverlayStyle] の範囲で自動で縮める。 */
class VerticalTextView(context: Context) : View(context) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    private val density = context.spToPx(1f)
    private val box = RectF()

    var textColor: Int
        get() = paint.color
        set(value) {
            paint.color = value
            invalidate()
        }

    var text: String = ""
        set(value) {
            field = value
            invalidate()
        }

    fun append(delta: String) {
        text += delta
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = PADDING_DP * resources.displayMetrics.density
        box.set(pad, pad, width - pad, height - pad)
        paint.textSize = Tategaki.fit(text, box.width(), box.height(), OverlayStyle.MIN_SP * density, OverlayStyle.MAX_SP * density).first
        Tategaki.draw(canvas, text, box, paint)
    }

    companion object {
        /** 枠の内側の余白（dp）。評価の描画（BenchActivity）も同じ余白で描く。 */
        const val PADDING_DP = 2
    }
}
