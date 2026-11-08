package app.plamotranslate.ocr

/**
 * 英語の漫画のセリフを、翻訳に渡す前に整える（2026-11-06、英語の吹き出しの訳が崩れた件）。
 *
 * - 行末のハイフンで切れた語をつなぐ（「COM-」「PARED」→「COMPARED」）。
 * - 漫画の写植はすべて大文字で書くことが多い。大文字だけの文を渡すと、翻訳が固有名詞や略語とみなして
 *   英語のまま残すことがあった（「FINE.」→「FINE.」）。8割以上が大文字なら、文頭だけ大文字の普通の文に直す。
 */
object EnglishText {
    private val HONORIFIC = Regex("^(chan|kun|san|sama|senpai|sensei|dono|tan)\\b", RegexOption.IGNORE_CASE)

    fun normalize(lines: List<String>): String {
        val sb = StringBuilder()
        for (raw in lines.map { it.trim() }.filter { it.isNotEmpty() }) {
            when {
                sb.isEmpty() -> sb.append(raw)
                // 行末がハイフンで、次の行が英字で始まるなら、ハイフンを外して詰めてつなぐ
                // 敬称（NATORI-CHAN など）のハイフンは残して詰める
                sb.endsWith("-") && HONORIFIC.containsMatchIn(raw) -> sb.append(raw)
                sb.endsWith("-") && raw.first().isLetter() -> { sb.setLength(sb.length - 1); sb.append(raw) }
                else -> sb.append(' ').append(raw)
            }
        }
        val text = sb.toString()
        val letters = text.filter { it in 'A'..'Z' || it in 'a'..'z' }
        if (letters.length < 2 || letters.count { it.isUpperCase() } < letters.length * 0.8) return text
        return toSentenceCase(text)
    }

    private fun toSentenceCase(text: String): String {
        val lower = text.lowercase()
        val out = StringBuilder(lower.length)
        var capitalizeNext = true
        for ((i, ch) in lower.withIndex()) {
            val prev = lower.getOrNull(i - 1)
            val next = lower.getOrNull(i + 1)
            val standaloneI = ch == 'i' && (prev == null || !prev.isLetter()) && (next == null || !next.isLetter())
            out.append(if ((capitalizeNext && ch.isLetter()) || standaloneI) ch.uppercaseChar() else ch)
            if (ch.isLetter()) capitalizeNext = false
            if (ch == '.' || ch == '!' || ch == '?') capitalizeNext = true
        }
        return out.toString()
    }
}