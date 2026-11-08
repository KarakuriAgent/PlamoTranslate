package app.plamotranslate.camera

import android.graphics.Rect
import app.plamotranslate.camera.LineTracker.Track
import kotlin.math.abs

/** 表示と翻訳の単位。安定した行（[Track]）を1つ以上まとめたもの。 */
internal class Paragraph(val lines: List<Track>) {
    val rect = Rect(lines.first().rect).apply { lines.drop(1).forEach { union(it.rect) } }

    /** 行をつないだ原文。 */
    val text: String = Paragraphs.join(lines.map { it.consensus() })

    /** すべての行の読み取りがそろったか。 */
    val settled: Boolean get() = lines.all { it.agreement() >= LineTracker.AGREE }
}

/**
 * 安定した行を段落にまとめる（docs/10_カメラ翻訳.md「段落にまとめる」）。読み取りの安定は行ごと、表示と翻訳は段落ごと。
 *
 * - 文章のモード（2026-11-06、作者の提案「重なる枠は1段落として統合した方が欠落が無くなる」）：
 *   すぐ下に続く行（行間が行の高さの0.8倍以内、横にも重なる、高さが1.6倍以内）は同じ段落にする。
 *   番号・記号で始まる行（[ITEM_START]）は新しい段落にし、段落は [PARAGRAPH_LINES] 行まで。
 *   番号付きの注意書きが1つの巨大な段落になると、訳が遅く、1行の読み揺れで全体を訳し直すため。
 * - 表・メニューのモード（作者の指摘「表を読むときは質が下がった」）：1行（1セル）ずつ。
 *   ただし、ボタンの中の2段書き（「바람／세기」）は1つにまとめる。1語ずつだと「세기」が「世紀」、2語なら「風の強さ」と訳せた。
 *   行間がほとんどなく（行の高さの0.3倍以内）、中央がそろい、幅が近い2行だけ。表の行どうしはふつう行間が空いている。
 */
internal object Paragraphs {
    /** 1段落の行数の上限。 */
    const val PARAGRAPH_LINES = 6

    /**
     * 新しい段落を始める行の頭：「1.」「1)」「1 」（文字認識が点を落とす）「①」「・」「-」「※」「*」など。
     * 「2인분」「500ml」のように数字のすぐ後に文字が続く行は含めない。
     */
    val ITEM_START = Regex("""^(\d{1,2}(?:[.)．]|\s)|[①-⑳]|[・•\-※*▪■□●○◆◇])""")

    /** [lines] は上から順に並んだ行。 */
    fun group(lines: List<Track>, table: Boolean): List<Paragraph> =
        if (table) groupTable(lines) else groupText(lines)

    /** 行をつなぐ。韓国語・欧文は単語の間で折り返すので空白、日本語・中国語は詰める。 */
    fun join(parts: List<String>): String {
        val trimmed = parts.map { it.trim() }
        val spaced = trimmed.any { p -> p.any { it in '가'..'힣' || it in 'A'..'Z' || it in 'a'..'z' } }
        return trimmed.joinToString(if (spaced) " " else "")
    }

    private fun groupText(lines: List<Track>): List<Paragraph> {
        val paragraphs = mutableListOf<MutableList<Track>>()
        for (line in lines) {
            val target = if (ITEM_START.containsMatchIn(line.consensus().trimStart())) null else paragraphs.lastOrNull { p ->
                val last = p.last().rect
                val r = line.rect
                p.size < PARAGRAPH_LINES &&
                    r.left < last.right && r.right > last.left &&
                    r.top - last.bottom <= maxOf(last.height(), r.height()) * 0.8f &&
                    maxOf(last.height(), r.height()) <= minOf(last.height(), r.height()) * 1.6f
            }
            if (target != null) target += line else paragraphs += mutableListOf(line)
        }
        return paragraphs.map(::Paragraph)
    }

    private fun groupTable(lines: List<Track>): List<Paragraph> {
        val used = mutableSetOf<Track>()
        val result = mutableListOf<Paragraph>()
        for (line in lines) {
            if (line in used) continue
            val r = line.rect
            val pair = lines.firstOrNull { o ->
                o !== line && o !in used && o.rect.top >= r.top &&
                    o.rect.top - r.bottom <= maxOf(r.height(), o.rect.height()) * 0.3f &&
                    abs(o.rect.centerX() - r.centerX()) <= maxOf(r.width(), o.rect.width()) * 0.2f &&
                    maxOf(r.width(), o.rect.width()) <= minOf(r.width(), o.rect.width()) * 2.5f
            }
            used += line
            if (pair != null) used += pair
            result += Paragraph(listOfNotNull(line, pair))
        }
        return result
    }
}
