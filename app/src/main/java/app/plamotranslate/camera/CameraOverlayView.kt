package app.plamotranslate.camera

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import app.plamotranslate.screen.spToPx
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * カメラの映像の上に訳の枠を描く。表・メニューのモード（[LiveFrame.table]）では枠を広げず、元の文字の範囲に収めて描く
 * （広げると隣のボタンやセルを覆った。2026-11-06、作者の指摘「四角形の範囲が大きすぎる」）。映像（PreviewView）と一時停止の画像はどちらも「中央を切り抜いて全体を埋める」
 * 表示なので、同じ計算で画像の座標を画面の座標へ移す。
 * まだ訳していない文は、読めたことだけ分かるよう枠線だけ描く。
 *
 * 枠の座標は、端末の傾きに合わせて正立させた画像のもの（[LiveFrame.rotation]）。配置の計算と文字の描画は
 * その正立した座標で行い、最後に画面の座標へ回す（[toScreen]）。横向きに持っても訳が正立して読める。
 *
 * 日本語の訳は韓国語・英語の原文より長くなりやすく、1行の高さの枠では文字が小さくなりすぎて下の行にはみ出す
 * （2026-11-06、作者の指摘「四角形が折り重なって読めない」）。そこで訳の枠を、右は同じ段落の右端まで、
 * 下は次の行の手前まで広げ（[available]）、その中に収まる大きさで描く。収まらない分は切って、ほかの行に被せない。
 */
class CameraOverlayView(context: Context) : View(context) {
    var frame: LiveFrame? = null
        set(value) {
            field = value
            invalidate()
        }

    private val density = context.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(200, 255, 255, 255)
    }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val minPx = context.spToPx(7f)
    private val maxPx = context.spToPx(26f)

    override fun onDraw(canvas: Canvas) {
        val f = frame ?: return
        val (matrix, scale) = toScreen(f)
        // 正立した画像の座標で描く。画面上の大きさ（文字・余白・線）は画像の画素に直す
        val pad = density * 2 / scale
        outline.strokeWidth = density * 1.5f / scale
        val bounds = RectF(0f, 0f, f.uprightWidth.toFloat(), f.uprightHeight.toFloat())
        val rects = f.boxes.map { RectF(it.rect) }
        canvas.save()
        canvas.concat(matrix)
        // 先に訳のない枠線、後から訳を描く（訳の下に枠線が透けないように）
        f.boxes.forEachIndexed { i, box ->
            if (box.translation == null) canvas.drawRoundRect(rects[i], pad, pad, outline)
        }
        f.boxes.forEachIndexed { i, box ->
            val translation = box.translation ?: return@forEachIndexed
            val area = if (f.table) RectF(rects[i]).apply { inset(-pad, -pad) } else available(i, rects, bounds, pad)
            fill.color = box.colors.background
            fill.alpha = 240
            canvas.drawRoundRect(area, pad, pad, fill)
            text.color = box.colors.text
            canvas.save()
            canvas.clipRect(area)
            drawFitted(canvas, translation, area, pad, scale)
            canvas.restore()
        }
        canvas.restore()
    }

    /** 正立した画像の座標から画面の座標への変換（[CameraGeometry]）と、その拡大率。 */
    private fun toScreen(f: LiveFrame): Pair<Matrix, Float> {
        val vw = width.toFloat()
        val vh = height.toFloat()
        return CameraGeometry.uprightToView(f.bitmap.width, f.bitmap.height, f.rotation, vw, vh) to
            CameraGeometry.scale(f.bitmap.width, f.bitmap.height, vw, vh)
    }
    /**
     * [i] 番目の訳に使ってよい範囲（正立した画像の座標）。右は同じ段落（上下の近い行）の右端まで、
     * ただし右隣の別の文字の手前まで。下は次の行の上端の手前まで（下に行がなければ、行の高さの1倍まで）。
     */
    private fun available(i: Int, rects: List<RectF>, bounds: RectF, pad: Float): RectF {
        val r = rects[i]
        val h = r.height()
        val others = rects.filterIndexed { j, _ -> j != i }
        // 同じ段落：横に重なり、上下に行の高さの4倍以内にある行
        val column = others.filter { it.left < r.right && it.right > r.left && abs(it.centerY() - r.centerY()) < h * 4 }
        // 広げるのは元の幅の半分まで（大きすぎる枠がほかの物を覆わないように。2026-11-06、作者の指摘）
        var right = min(max(r.right, column.maxOfOrNull { it.right } ?: r.right), r.right + r.width() / 2)
        // 右隣（縦に重なり、右にある）の手前で止める
        others.filter { it.top < r.bottom && it.bottom > r.top && it.left >= r.right - h / 2 }
            .minOfOrNull { it.left }?.let { right = max(r.right, min(right, it - pad)) }
        right = min(right, bounds.right)
        // 次の行（横に重なり、下にある）の手前まで
        val below = others.filter { it.left < right && it.right > r.left && it.top >= r.centerY() }
            .minOfOrNull { it.top }
        // 下に行がなければ、行の高さの半分まで（背の高い枠が倍の大きさにならないように）
        val bottom = if (below != null) max(r.bottom, min(below - pad / 2, r.bottom + h)) else r.bottom + h / 2
        return RectF(r.left - pad, r.top - pad, right + pad, min(bottom, bounds.bottom))
    }

    /**
     * 範囲に収まる最大の文字の大きさで描く。大きさは画面上の 7〜26sp を、画像の画素（÷[scale]）に直して使う。
     * 最小の大きさでも収まらなければ、はみ出す分は切られる（呼ぶ側で clip）。
     */
    private fun drawFitted(canvas: Canvas, s: String, area: RectF, pad: Float, scale: Float) {
        val w = (area.width() - pad * 2).toInt().coerceAtLeast(1)
        val minSize = minPx / scale
        var size = min(maxPx / scale, area.height() * 0.8f).coerceAtLeast(minSize)
        var layout: StaticLayout
        while (true) {
            text.textSize = size
            layout = StaticLayout.Builder.obtain(s, 0, s.length, text, w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 0.95f)
                .build()
            if (layout.height <= area.height() - pad || size <= minSize) break
            size -= 1f / scale
        }
        canvas.translate(area.left + pad, area.top + ((area.height() - layout.height) / 2).coerceAtLeast(0f))
        layout.draw(canvas)
    }
}
