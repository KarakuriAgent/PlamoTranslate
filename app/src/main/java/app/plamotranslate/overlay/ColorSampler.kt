package app.plamotranslate.overlay

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 訳の枠に使う背景色と文字色。 */
data class BoxColors(val background: Int, val text: Int) {
    companion object {
        /** 読み取れなかったときの既定（今までの暗い枠と白い文字）。 */
        val DEFAULT = BoxColors(Color.rgb(28, 28, 34), Color.WHITE)
    }
}

/**
 * 撮影した画面から、訳の枠の背景色と文字色を読み取る（2026-11-05、作者の要望「吹き出しの置き換えの背景と文字色を元の画面とそろえたい」）。
 *
 * - 背景色：枠を少し広げた外周の画素を、各色32段階にまとめて最も多い色にする。文字が外周にかかっても多数決で背景が残る。
 * - 文字色：枠の中で背景色から十分に離れた画素の中央値にする。
 * - 文字色が背景色に近すぎて読みにくいときは、背景の明るさに応じて黒か白にする。
 */
object ColorSampler {
    private const val RING = 3
    private const val TEXT_DISTANCE = 90.0
    private const val MIN_CONTRAST = 3.0

    fun sample(bitmap: Bitmap, rect: Rect): BoxColors {
        val outer = Rect(rect).apply { inset(-RING * 2, -RING * 2) }
        if (!outer.intersect(0, 0, bitmap.width, bitmap.height) || outer.width() < 4 || outer.height() < 4) {
            return BoxColors.DEFAULT
        }
        val pixels = IntArray(outer.width() * outer.height())
        bitmap.getPixels(pixels, 0, outer.width(), outer.left, outer.top, outer.width(), outer.height())
        fun at(x: Int, y: Int) = pixels[(y - outer.top) * outer.width() + (x - outer.left)]

        // 背景：外周の帯（幅RING）の画素の最頻色
        val counts = HashMap<Int, Int>()
        val sums = HashMap<Int, LongArray>()
        for (y in outer.top until outer.bottom) {
            for (x in outer.left until outer.right) {
                val onRing = x < outer.left + RING || x >= outer.right - RING || y < outer.top + RING || y >= outer.bottom - RING
                if (!onRing) continue
                val c = at(x, y)
                val key = (Color.red(c) shr 3 shl 10) or (Color.green(c) shr 3 shl 5) or (Color.blue(c) shr 3)
                counts[key] = (counts[key] ?: 0) + 1
                sums.getOrPut(key) { LongArray(3) }.let {
                    it[0] += Color.red(c).toLong(); it[1] += Color.green(c).toLong(); it[2] += Color.blue(c).toLong()
                }
            }
        }
        val bgKey = counts.maxByOrNull { it.value }?.key ?: return BoxColors.DEFAULT
        val n = counts.getValue(bgKey)
        val s = sums.getValue(bgKey)
        val background = Color.rgb((s[0] / n).toInt(), (s[1] / n).toInt(), (s[2] / n).toInt())

        // 文字：枠の中で背景から十分に離れた画素の中央値（各色ごと）
        val inner = Rect(rect).apply { intersect(outer) }
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        for (y in inner.top until inner.bottom) {
            for (x in inner.left until inner.right) {
                val c = at(x, y)
                if (distance(c, background) >= TEXT_DISTANCE) {
                    rs += Color.red(c); gs += Color.green(c); bs += Color.blue(c)
                }
            }
        }
        var text = if (rs.size >= 8) {
            Color.rgb(rs.sorted()[rs.size / 2], gs.sorted()[gs.size / 2], bs.sorted()[bs.size / 2])
        } else {
            fallbackText(background)
        }
        if (contrast(text, background) < MIN_CONTRAST) text = fallbackText(background)
        return BoxColors(background, text)
    }

    private fun fallbackText(background: Int) = if (luminance(background) > 0.4) Color.BLACK else Color.WHITE

    private fun distance(a: Int, b: Int): Double {
        val dr = (Color.red(a) - Color.red(b)).toDouble()
        val dg = (Color.green(a) - Color.green(b)).toDouble()
        val db = (Color.blue(a) - Color.blue(b)).toDouble()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    /** WCAGの相対輝度。 */
    private fun luminance(c: Int): Double {
        fun ch(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(Color.red(c)) + 0.7152 * ch(Color.green(c)) + 0.0722 * ch(Color.blue(c))
    }

    /** WCAGのコントラスト比（1〜21）。3未満は読みにくい。 */
    private fun contrast(a: Int, b: Int): Double {
        val la = luminance(a); val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }
}
