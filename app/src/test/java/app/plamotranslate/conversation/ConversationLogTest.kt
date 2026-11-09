package app.plamotranslate.conversation

import app.plamotranslate.llm.Language
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class ConversationLogTest {
    private val zone = ZoneId.of("Asia/Tokyo")
    private fun at(h: Int, m: Int, s: Int) =
        LocalDateTime.of(2026, 11, 6, h, m, s).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun 発話ごとに時刻と話した人と原文と訳を並べる() {
        val turns = listOf(
            Turn(0, Direction(Language.KO, Language.JA), "안녕하세요", "こんにちは", true, timeMillis = at(14, 3, 5)),
            Turn(1, Direction(Language.JA, Language.KO), "元気です\n", "잘 지내요", true, timeMillis = at(14, 3, 12)),
            Turn(2, Direction(Language.KO, Language.JA), "감사합니다", "", false, timeMillis = at(14, 10, 0)),
        )
        val expected = """
            |# 会話ログ 2026-11-06 14:03〜14:10
            |
            |- 言語：日本語 ⇄ 韓国語
            |- 発話：3件
            |- PLaMo翻訳の端末内の音声認識と翻訳による。聞き違い・訳し違いを含むことがある
            |
            |**14:03:05 相手（韓国語）**
            |原文：안녕하세요
            |訳（日本語）：こんにちは
            |
            |**14:03:12 自分（日本語）**
            |原文：元気です
            |訳（韓国語）：잘 지내요
            |
            |**14:10:00 相手（韓国語）**
            |原文：감사합니다
            |訳（日本語）：（翻訳中）
            |""".trimMargin()
        assertEquals(expected, ConversationLog.format(turns, zone))
        assertEquals("会話ログ_2026-11-06_1403.md", ConversationLog.fileName(turns, zone))
    }

    @Test
    fun ドイツ語の相手でも言語名で並べる() {
        // 2026-11-09、Qwen3-ASRでドイツ語などを選べるようにした
        val turns = listOf(
            Turn(0, Direction(Language.DE, Language.JA, mine = false), "Guten Tag", "こんにちは", true, timeMillis = at(9, 0, 0)),
            Turn(1, Direction(Language.JA, Language.DE, mine = true), "はじめまして", "Freut mich", true, timeMillis = at(9, 0, 5)),
        )
        val log = ConversationLog.format(turns, zone)
        assertEquals(true, log.contains("- 言語：日本語 ⇄ ドイツ語\n"))
        assertEquals(true, log.contains("**09:00:00 相手（ドイツ語）**\n原文：Guten Tag\n訳（日本語）：こんにちは\n"))
    }

    @Test
    fun 発話が無ければ空() {
        assertEquals("", ConversationLog.format(emptyList(), zone))
    }
}
