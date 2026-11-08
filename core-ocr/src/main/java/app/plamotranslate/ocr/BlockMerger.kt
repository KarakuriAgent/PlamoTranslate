package app.plamotranslate.ocr

import android.graphics.Rect

/** 1つの認識器が返した1行（縦書きなら1列）。[bounds] は撮影画像の座標。 */
internal data class Candidate(val text: String, val bounds: Rect)

/**
 * 複数の認識器（漫画モデルと、ML Kitの中国語・日本語・韓国語・英字）の結果を、行ごとに文字種に合うものを選んでまとめる。
 *
 * 処理の順番：
 * 1. 縦書きの行を決める。漫画モデルがあればその行を採り、無ければML Kitの1文字ずつの位置から列を組み直す（[VerticalColumns]）。
 *    その範囲に重なるML Kitの行（縦書きを横に読み違えた行）は捨てる。
 * 2. 残りの行を、位置が重なるものどうしで突き合わせ（[LineGroup]）、文字種の数から1つを選ぶ（[LineGroup.pick]）。
 * 3. 選んだ行を、位置関係から吹き出し・段落のまとまりに束ね（[LineClusterer]）、読み順に並べる。
 *
 * 段落ごとに選ぶと、日本語の解説に韓国語の引用が混ざった段落で、片方の言語が崩れた文字列になった
 * （2026-11-05実機）。そのため行ごとに選び、まとまりは後から組み直す。
 */
internal object BlockMerger {
    private const val SAME_LINE_IOU = 0.4f

    /** 同じ行とみなす上端のずれ（px）。読み順を上から下、左から右にそろえる。 */
    private const val ROW_TOLERANCE_PX = 24

    /** 行を読んだ認識器。 */
    private enum class Source { MANGA, ZH, JA, KO, LATIN }

    /** 漫画モデルの結果をそのまま採る確信度（2026-11-05、PCで中国語の行は中央値0.99、韓国語の行は90%点でも0.75）。 */
    private const val MANGA_CONFIDENT = 0.8f

    /** 同じ位置の行を、認識器ごとに読んだ版。 */
    private class LineGroup(val bounds: Rect) {
        val versions = mutableMapOf<Source, Candidate>()

        fun pick(): Candidate {
            // 漫画モデルが中国語・日本語として読んだ行は、それを採る（採る行は merge で選んである）
            versions[Source.MANGA]?.let { return it }
            fun ok(c: Candidate?) = c != null && !TextQuality.looksGarbled(c.text)
            val ko = versions[Source.KO]
            val ja = versions[Source.JA]
            val zh = versions[Source.ZH]
            // 各認識器が自分の文字をいくつ読めたかで、その行の言語を決める。
            // 日本語の文に韓国語の引用が混ざる行は、仮名の数がハングル以上なら日本語の行とみなす。
            // 縦書きの中国語は韓国語の認識器がハングルに読み違える（「째요또터」）ので、
            // 中国語の認識器が読んだ漢字の数がハングル以上なら中国語の行とみなす（2026-11-05評価）。
            val kana = ja?.text?.count { ScriptDetector.hasKana(it.toString()) } ?: 0
            val hangul = ko?.text?.count { ScriptDetector.hasHangul(it.toString()) } ?: 0
            val han = zh?.text?.count { ScriptDetector.hasHan(it.toString()) } ?: 0
            // 英語（ラテン文字だけの行）：どの認識器もラテン文字を読めるので、いちばん多く読めて崩れていないものを採る
            // （2026-11-05、作者の依頼で英語を1つの言語として扱う）
            // 英字専用の認識器が読めていれば、それを優先する（2026-11-06）
            if (kana == 0 && hangul == 0 && han == 0) {
                versions[Source.LATIN]?.takeIf { ok(it) && ScriptDetector.hasLatin(it.text) }?.let { return it }
                val latin = versions.values.filter { ok(it) && ScriptDetector.hasLatin(it.text) }
                    .maxByOrNull { c -> c.text.count { it in 'A'..'Z' || it in 'a'..'z' } }
                if (latin != null) return latin
            }
            return when {
                ja != null && kana >= 2 && kana >= hangul && ok(ja) -> ja
                ko != null && hangul > 0 && hangul > han && ok(ko) -> ko
                zh != null && han > 0 && ok(zh) -> zh
                ja != null && kana > 0 && ok(ja) -> ja
                ok(zh) -> zh!!
                ok(ja) -> ja!!
                ok(ko) -> ko!!
                // どれも崩れている場合は、後段（訳す対象の判定）で落とす
                // 漫画モデルか英字専用の認識器だけが読んだ行（崩れた英字など）もここに来る（2026-11-06、カメラで落ちた）
                else -> zh ?: ja ?: ko ?: versions.values.first()
            }
        }
    }

    fun merge(
        ja: List<Candidate>,
        ko: List<Candidate>,
        zh: List<Candidate>,
        zhGlyphs: List<Glyph> = emptyList(),
        manga: List<MangaLine>? = null,
        latin: List<Candidate> = emptyList(),
        groupLines: Boolean = true,
    ): List<OcrBlock> {
        // 漫画モデル（縦書きも読める）が使えるときは、中国語・日本語の行はそれを採る。
        // 韓国語は読めないので、確信度が低く、ML Kitの韓国語の認識器がハングルを読めた範囲はML Kitに任せる。
        val koreanLines = ko.filter { c ->
            c.text.count { ScriptDetector.hasHangul(it.toString()) } >= 2 && !TextQuality.looksGarbled(c.text)
        }
        val mangaLines = manga.orEmpty()
            .filter { ScriptDetector.hasHan(it.text) || ScriptDetector.hasKana(it.text) }
            .filter { m ->
                m.confidence >= MANGA_CONFIDENT || koreanLines.none { overlaps(it.bounds, m.bounds) }
            }
            .map { Candidate(it.text, it.bounds) }
        // 漫画モデルが無いときだけ、ML Kitの1文字ずつの位置から縦書きの列を組み直す
        val columns = if (manga == null) VerticalColumns.build(zhGlyphs) else emptyList()
        val covered = mangaLines + columns
        // 採用した行の範囲に重なるML Kitの行（縦書きを横に読み違えた行など）は捨てる
        fun outside(list: List<Candidate>) = list.filter { c -> covered.none { overlaps(c.bounds, it.bounds) } }

        val groups = mutableListOf<LineGroup>()
        for ((source, list) in listOf(
            Source.MANGA to mangaLines,
            Source.ZH to columns + outside(zh),
            Source.JA to outside(ja),
            Source.KO to outside(ko),
            // 英字専用の認識器は、漢字・仮名・ハングルを読めないので英字だけの行を使う
            Source.LATIN to outside(latin.filter { ScriptDetector.detect(it.text) == Script.LATIN }),
        )) {
            for (c in list) {
                val group = groups.firstOrNull { Geometry.iou(it.bounds, c.bounds) >= SAME_LINE_IOU }
                if (group == null) {
                    groups += LineGroup(Rect(c.bounds)).also { it.versions[source] = c }
                } else if (source !in group.versions) {
                    group.versions[source] = c
                }
            }
        }

        val picked = groups.map { it.pick() }
        return (if (groupLines) LineClusterer.cluster(picked) else picked.map { listOf(it) })
            .map(::toBlock)
            .filter { it.text.isNotBlank() }
            .sortedWith(readingOrder { it.bounds })
    }

    /** どちらかの面積の3割以上が重なるか。 */
    private fun overlaps(a: Rect, b: Rect): Boolean = Geometry.overlaps(a, b, 0.3f)

    private fun toBlock(lines: List<Candidate>): OcrBlock {
        val bounds = Rect(lines.first().bounds)
        lines.drop(1).forEach { bounds.union(it.bounds) }
        val vertical = lines.any { LineClusterer.isVerticalLine(it) } &&
            lines.none { LineClusterer.isHorizontal(it.bounds) }
        // 縦書きは右の列から、横書きは上の行から読む
        val ordered = if (vertical) {
            lines.sortedByDescending { it.bounds.right }
        } else {
            rowOrder(lines)
        }
        val text = ordered.joinToString("\n") { it.text }
        return OcrBlock(text, bounds, ScriptDetector.detect(text), vertical)
    }

    /**
     * 横書きの行を上から、同じ高さの行は左から並べる。上端の数ピクセルの差で左右が逆転しないよう、
     * 行の高さの半分以上重なる行を同じ段とみなす（2026-11-06、英語の吹き出しで「HAVE FUN / I STILL」と逆転した）。
     */
    private fun rowOrder(lines: List<Candidate>): List<Candidate> {
        val rows = mutableListOf<MutableList<Candidate>>()
        for (line in lines.sortedBy { it.bounds.centerY() }) {
            val row = rows.lastOrNull()
            val ref = row?.first()?.bounds
            val overlap = if (ref == null) 0 else minOf(ref.bottom, line.bounds.bottom) - maxOf(ref.top, line.bounds.top)
            if (ref != null && overlap > minOf(ref.height(), line.bounds.height()) / 2) row.add(line) else rows += mutableListOf(line)
        }
        return rows.flatMap { row -> row.sortedBy { it.bounds.left } }
    }

    private fun <T> readingOrder(rect: (T) -> Rect): Comparator<T> =
        compareBy({ rect(it).top / ROW_TOLERANCE_PX }, { rect(it).left })
}
