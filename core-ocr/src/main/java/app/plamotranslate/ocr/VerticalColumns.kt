package app.plamotranslate.ocr

import android.graphics.Rect
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max

/** 認識器が読んだ1文字と、その位置。 */
internal data class Glyph(val text: String, val bounds: Rect)

/**
 * 縦書きの列を、1文字ずつの位置から組み直す。
 *
 * ML Kitは縦書きの吹き出しを、隣り合う列をまたいで横に読むことがある（「今晚父母…」が「晚父今/回母」になる）。
 * 文字そのものはおおむね正しく、並びだけが崩れるので、文字の位置で列を作り直す（2026-11-05、作者の指摘）。
 *
 * - 同じ位置に重なる字（縦に正しく読んだ行と、横に読み違えた行の両方に出た字）は1つにする。
 * - 上下に詰まって並び（字間が字の大きさの0.45倍未満）、左右の中心がそろう字を同じ列とみなす。
 * - 上下のつながりが左右のつながりより多い字だけを列に入れる。横書きの行が上下に詰まって並ぶ所
 *   （スマホ画面のメッセージなど）を縦の列と取り違えないため。
 * - 列の中では上から読み、縦書きの約物を横書きの形に戻してから訳へ渡す（[normalize]）。
 */
internal object VerticalColumns {
    private const val V_GAP = 0.45f
    private const val H_GAP = 0.3f

    fun build(glyphs: List<Glyph>): List<Candidate> {
        val cjk = dedupe(glyphs.filter { g ->
            g.text.isNotBlank() && !ScriptDetector.hasHangul(g.text) && !ScriptDetector.hasLatin(g.text) && g.bounds.height() > 0
        })
        if (cjk.size < 2) return emptyList()

        val below = IntArray(cjk.size) { -1 }
        val verticalLinks = IntArray(cjk.size)
        val horizontalLinks = IntArray(cjk.size)
        for (i in cjk.indices) {
            val a = cjk[i].bounds
            for (j in cjk.indices) {
                if (i == j) continue
                val b = cjk[j].bounds
                val size = max(max(a.width(), a.height()), max(b.width(), b.height()))
                // 下に詰まって続く字
                val gapY = b.top - a.bottom
                if (gapY > -size * 0.3f && gapY < size * V_GAP && abs(a.centerX() - b.centerX()) < size * 0.4f) {
                    if (below[i] < 0 || b.top < cjk[below[i]].bounds.top) below[i] = j
                    verticalLinks[i]++
                    verticalLinks[j]++
                }
                // 右に詰まって続く字（横書き）
                val gapX = b.left - a.right
                if (gapX > -size * 0.3f && gapX < size * H_GAP && abs(a.centerY() - b.centerY()) < size * 0.35f) {
                    horizontalLinks[i]++
                    horizontalLinks[j]++
                }
            }
        }

        val inColumn = BooleanArray(cjk.size) { verticalLinks[it] > horizontalLinks[it] }
        val parent = IntArray(cjk.size) { it }
        fun find(x: Int): Int { var r = x; while (parent[r] != r) { parent[r] = parent[parent[r]]; r = parent[r] }; return r }
        for (i in cjk.indices) {
            val j = below[i]
            if (j >= 0 && inColumn[i] && inColumn[j]) parent[find(i)] = find(j)
        }

        return cjk.indices.filter { inColumn[it] }.groupBy(::find).values
            .filter { it.size >= 2 }
            .mapNotNull { members ->
                val sorted = members.map { cjk[it] }.sortedBy { it.bounds.top }
                val bounds = Rect(sorted.first().bounds).apply { sorted.drop(1).forEach { union(it.bounds) } }
                if (bounds.height() < bounds.width() * 1.5f) return@mapNotNull null
                // 字の大きさの目安（漢字の高さの中央値）。約物の読み違えを見分けるのに使う
                val charHeight = sorted.filter { ScriptDetector.hasHan(it.text) }.map { it.bounds.height() }
                    .sorted().let { if (it.isEmpty()) bounds.width() else it[it.size / 2] }
                Candidate(sorted.joinToString("") { normalize(it, charHeight) }, bounds)
            }
    }

    /** 同じ位置に重なる字を1つにする（漢字を優先）。 */
    private fun dedupe(glyphs: List<Glyph>): List<Glyph> {
        val kept = mutableListOf<Glyph>()
        for (g in glyphs.sortedByDescending { ScriptDetector.hasHan(it.text) }) {
            if (kept.none { Geometry.iou(it.bounds, g.bounds) > 0.5f }) kept += g
        }
        return kept
    }

    /**
     * 縦書きの約物を横書きの形に戻す。訳には横書きの形で渡し、縦に描くときに改めて縦の形にする（Tategaki）。
     *
     * 根拠：
     * - 縦書き用の表示形（Unicode U+FE10〜FE19、U+FE30〜FE4F）は、NFKCで横書きの文字に戻る
     *   （例：U+FE31 → U+2014「—」、U+FE41 → U+300C「「」、U+FE19 → 「...」。UCD 16.0で確認）。
     * - 中国語の縦書きでは、破折号（——）と省略号（……）は字面の中央に縦に置く（W3C clreq 5.1・5.4.1、GB/T 15834—2011に基づく）。
     *   横書き用のOCRは、縦の破折号を「|」、縦の省略号（縦に並ぶ点）を「:」に読む（2026-11-05の評価画像で、
     *   「打领带吧——」→「吧|」、「也就是说……」→「说:」を確認）。
     * - 本物のコロン・縦線と区別するため、1字分の高さ（漢字の高さの6割以上）がある字だけを置き換える。
     */
    private fun normalize(g: Glyph, charHeight: Int): String {
        var s = g.text
        if (s.any { it in '︐'..'﹏' }) s = Normalizer.normalize(s, Normalizer.Form.NFKC)
        if (s == "..." || s == "..") return "…"
        val tall = g.bounds.height() >= charHeight * 0.6f
        return when {
            tall && s in setOf("|", "｜", "丨", "︱") -> "—"
            tall && s in setOf(":", "：", "⋮", "︙") -> "…"
            else -> s
        }
    }
}
