package app.plamotranslate.ocr

import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min

/**
 * 認識した行・文字の枠どうしの重なりを測る。行の突き合わせ（[BlockMerger]）、まとまり（[LineClusterer]）、列（[VerticalColumns]）、
 * カメラ翻訳の行の追跡（app の LineTracker）で共通に使う。
 */
object Geometry {
    /** 重なりの面積 ÷ 2つを合わせた面積（0〜1）。同じ行を別の認識器が読んだかの判定に使う。 */
    fun iou(a: Rect, b: Rect): Float {
        val inter = Rect()
        if (!inter.setIntersect(a, b)) return 0f
        val i = inter.width().toFloat() * inter.height()
        val u = a.width().toFloat() * a.height() + b.width().toFloat() * b.height() - i
        return if (u <= 0f) 0f else i / u
    }

    /** [a] の面積のうち [b] と重なる割合（0〜1）。大きさの違う枠どうし（列と1文字など）の包含の判定に使う。 */
    fun overlapRatio(a: Rect, b: Rect): Float {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (w <= 0 || h <= 0) return 0f
        val area = a.width().toFloat() * a.height()
        return if (area <= 0f) 0f else w.toFloat() * h / area
    }

    /** どちらかの面積の [ratio] 以上が、もう一方に重なるか。 */
    fun overlaps(a: Rect, b: Rect, ratio: Float): Boolean =
        overlapRatio(a, b) >= ratio || overlapRatio(b, a) >= ratio
}
