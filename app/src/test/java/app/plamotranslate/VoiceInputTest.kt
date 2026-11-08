package app.plamotranslate

import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceInputTest {
    @Test
    fun 聞き取った文は原文の後ろに足す() {
        assertEquals("こんにちは", TranslateViewModel.joinSpoken("", " こんにちは "))
        assertEquals("今日は晴れ。明日は雨。", TranslateViewModel.joinSpoken("今日は晴れ。", "明日は雨。"))
        assertEquals("Hello. How are you?", TranslateViewModel.joinSpoken("Hello.", "How are you?"))
        assertEquals("안녕하세요 감사합니다", TranslateViewModel.joinSpoken("안녕하세요", "감사합니다"))
        assertEquals("メモ\nHello", TranslateViewModel.joinSpoken("メモ\n", "Hello"))
    }

    @Test
    fun 何も聞き取れなければ原文を変えない() {
        assertEquals("元の文", TranslateViewModel.joinSpoken("元の文", "  "))
    }
}
