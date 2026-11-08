package app.plamotranslate.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class SentencesTest {
    private fun split(text: String) = SupertonicSpeaker.sentences(text)

    @Test
    fun 日本語は句点で分ける() {
        assertEquals(
            listOf("こんにちは。", "駅までの行き方を教えてください。"),
            split("こんにちは。駅までの行き方を教えてください。"),
        )
    }

    @Test
    fun 韓国語と英語は記号と空白で分ける() {
        assertEquals(listOf("안녕하세요.", "역까지 가는 방법을 알려 주세요."), split("안녕하세요. 역까지 가는 방법을 알려 주세요."))
        assertEquals(listOf("Hello!", "Is it far?", "OK"), split("Hello! Is it far? OK"))
    }

    @Test
    fun 小数点や略語の点では分けない() {
        assertEquals(listOf("It costs 3.5 dollars."), split("It costs 3.5 dollars."))
    }

    @Test
    fun 改行で分けて空の文は捨てる() {
        assertEquals(listOf("一行目", "二行目。"), split("一行目\n\n二行目。\n"))
        assertEquals(emptyList<String>(), split("  "))
    }
}
