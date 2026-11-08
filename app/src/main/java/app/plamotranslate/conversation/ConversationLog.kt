package app.plamotranslate.conversation

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 会話ログをMarkdownのテキストにする（2026-11-06、作者の要望「後から振り返ったり、別のアプリで議事録にしたりしたい」）。
 * 発話ごとに時刻・話した人・原文・訳を並べる。人が読めて、議事録を作るアプリやAIへそのまま渡せる形にする。
 */
object ConversationLog {
    private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")
    private val TIME_SEC = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val FILE = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")

    fun format(turns: List<Turn>, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        if (turns.isEmpty()) return ""
        val first = Instant.ofEpochMilli(turns.first().timeMillis).atZone(zone)
        val last = Instant.ofEpochMilli(turns.last().timeMillis).atZone(zone)
        // 自分の言語は、自分の発話の原文と相手の発話の訳の言語（2026-11-08から日本語以外も選べる）
        val directions = turns.map { it.direction }
        val mine = directions.map { if (it.mine) it.source else it.target }.distinct()
        val partners = directions.map { if (it.mine) it.target else it.source }.distinct() - mine.toSet()
        append("# 会話ログ ").append(DATE.format(first)).append(' ')
            .append(TIME.format(first)).append('〜').append(TIME.format(last)).append("\n\n")
        append("- 言語：").append(mine.joinToString("・") { it.label }).append(" ⇄ ")
            .append(partners.joinToString("・") { it.label }.ifEmpty { "（なし）" }).append('\n')
        append("- 発話：").append(turns.size).append("件\n")
        append("- PLaMo翻訳の端末内の音声認識と翻訳による。聞き違い・訳し違いを含むことがある\n")
        for (turn in turns) {
            val time = Instant.ofEpochMilli(turn.timeMillis).atZone(zone)
            val d = turn.direction
            append("\n**").append(TIME_SEC.format(time)).append(' ')
                .append(if (d.mine) "自分" else "相手").append('（').append(d.source.label).append("）**\n")
            append("原文：").append(turn.original.oneLine()).append('\n')
            append("訳（").append(d.target.label).append("）：")
                .append(if (turn.translated) turn.translation.oneLine() else "（翻訳中）").append('\n')
        }
    }

    /** 保存するときの既定のファイル名。 */
    fun fileName(turns: List<Turn>, zone: ZoneId = ZoneId.systemDefault()): String {
        val start = Instant.ofEpochMilli(turns.firstOrNull()?.timeMillis ?: System.currentTimeMillis()).atZone(zone)
        return "会話ログ_${FILE.format(start)}.md"
    }

    private fun String.oneLine() = trim().replace(Regex("\\s*\\n\\s*"), " ")
}
