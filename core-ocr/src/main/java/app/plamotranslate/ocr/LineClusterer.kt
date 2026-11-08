package app.plamotranslate.ocr

import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 認識した行を、位置の近さで吹き出し・段落のまとまりに束ねる。
 *
 * - 行の形を横長（幅が高さの1.5倍以上）・縦長（高さが幅の1.5倍以上）・正方形に近いものに分ける。
 *   1〜2文字の短い列は正方形に近くなるので、縦長・横長のどちらともつなげる。
 * - 横書きの行は、上下に詰まって並び（行間が行の高さの0.8倍未満）、左右が重なるものを同じまとまりにする。
 * - 縦書きの列は、左右に詰まって並び（列間が列の幅の0.8倍未満）、上下が重なるものを同じまとまりにする。
 * - 範囲が大きく重なる行は、形に関係なく同じまとまりにする。重なったまま別々に訳を描くと読めなくなる
 *   （2026-11-05、作者の指摘）。
 * - 仮名を含む行（日本語）とそれ以外の行はつながない。日本語の行は訳さないため、混ぜると訳す範囲がずれる。
 */
internal object LineClusterer {
    private const val GAP_RATIO = 0.8f

    fun isVertical(r: Rect): Boolean = r.height() >= r.width() * 1.5f && r.height() > 0

    /** 縦書きの行か。英字だけの行は、狭い吹き出しで縦長の枠になっても横書きとして扱う（2026-11-06、英語の漫画）。 */
    fun isVerticalLine(c: Candidate): Boolean = isVertical(c.bounds) && ScriptDetector.detect(c.text) != Script.LATIN
    fun isHorizontal(r: Rect): Boolean = r.width() >= r.height() * 1.5f && r.width() > 0

    fun cluster(lines: List<Candidate>): List<List<Candidate>> {
        val parent = IntArray(lines.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
            return x
        }
        for (i in lines.indices) {
            for (j in i + 1 until lines.size) {
                if (connected(lines[i], lines[j])) parent[find(i)] = find(j)
            }
        }
        return lines.indices.groupBy(::find).values.map { idx -> idx.map { lines[it] } }
    }

    private fun connected(a: Candidate, b: Candidate): Boolean {
        if (ScriptDetector.hasKana(a.text) != ScriptDetector.hasKana(b.text)) return false
        val ra = a.bounds
        val rb = b.bounds
        if (Geometry.overlaps(ra, rb, 0.2f)) return true
        val latin = ScriptDetector.detect(a.text) == Script.LATIN || ScriptDetector.detect(b.text) == Script.LATIN
        val rows = !isVerticalLine(a) && !isVerticalLine(b)
        val columns = !latin && !isHorizontal(ra) && !isHorizontal(rb)
        return (rows && rowsConnected(ra, rb)) || (columns && columnsConnected(ra, rb))
    }

    private fun rowsConnected(ra: Rect, rb: Rect): Boolean {
        val gap = max(ra.top, rb.top) - min(ra.bottom, rb.bottom)
        val lineHeight = min(ra.height(), rb.height())
        val overlapX = min(ra.right, rb.right) - max(ra.left, rb.left)
        // 中央そろえの吹き出しは行の幅が違うので、中心の近さでも同じ列とみなす
        val centerNear = abs(ra.centerX() - rb.centerX()) < max(ra.width(), rb.width()) / 2
        return gap < lineHeight * GAP_RATIO && (overlapX > 0 || centerNear)
    }

    private fun columnsConnected(ra: Rect, rb: Rect): Boolean {
        val gap = max(ra.left, rb.left) - min(ra.right, rb.right)
        val columnWidth = min(ra.width(), rb.width())
        val overlapY = min(ra.bottom, rb.bottom) - max(ra.top, rb.top)
        return gap < columnWidth * GAP_RATIO && overlapY > 0
    }
}
