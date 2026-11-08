package app.plamotranslate.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class Pfml3PromptBuilderTest {
    @Test
    fun 韓国語から日本語は丁寧体を付ける() {
        val prompt = Pfml3PromptBuilder.build(TranslationRequest("내일 몇 시에 만날까요?", Language.KO, Language.JA))
        assertEquals(
            "<|plamo:tag|>dataset<|plamo:msg|>translation" +
                "<|plamo:tag|>input<|plamo:key|>lang<|plamo:val|>ko<|plamo:msg|>내일 몇 시에 만날까요?" +
                "<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>ja<|plamo:key|>form<|plamo:val|>polite<|plamo:msg|>",
            prompt,
        )
    }

    @Test
    fun 原文の言語が不明ならlangを書かない() {
        val prompt = Pfml3PromptBuilder.build(TranslationRequest("hello", null, Language.KO))
        assertEquals(
            "<|plamo:tag|>dataset<|plamo:msg|>translation" +
                "<|plamo:tag|>input<|plamo:msg|>hello" +
                "<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>ko<|plamo:msg|>",
            prompt,
        )
    }

    @Test
    fun 背景はdatasetの直後にcontextとして書く() {
        val prompt = Pfml3PromptBuilder.build(TranslationRequest("정지", Language.KO, Language.JA, context = "機器のボタン"))
        assertEquals(
            "<|plamo:tag|>dataset<|plamo:msg|>translation" +
                "<|plamo:tag|>context<|plamo:msg|>機器のボタン" +
                "<|plamo:tag|>input<|plamo:key|>lang<|plamo:val|>ko<|plamo:msg|>정지" +
                "<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>ja<|plamo:key|>form<|plamo:val|>polite<|plamo:msg|>",
            prompt,
        )
    }

    @Test
    fun 言語はモデルカードの53言語で重複しない() {
        assertEquals(53, Language.entries.size)
        assertEquals(53, Language.entries.map { it.code }.distinct().size)
        assertEquals(Language.ZH_HANT, Language.ofCode("zh-hant"))
        assertEquals(null, Language.ofCode("yue"))
    }

    @Test
    fun 区切り文字列を取り除く() {
        assertEquals("abtag|>c", Pfml3PromptBuilder.sanitize(" ab<|plamo:tag|>c "))
    }
}
