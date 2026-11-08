package app.plamotranslate.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class EnglishTextTest {
    @Test
    fun 大文字だけの写植を普通の文に直す() {
        assertEquals("It's fine.", EnglishText.normalize(listOf("IT'S", "FINE.")))
        assertEquals("I still have fun just the way we are.", EnglishText.normalize(listOf("I STILL", "HAVE FUN JUST", "THE WAY WE", "ARE.")))
        assertEquals("Huh? Didn't I say so? I'm sorry.", EnglishText.normalize(listOf("HUH? DIDN'T I", "SAY SO? I'M SORRY.")))
    }

    @Test
    fun ハイフンで切れた語をつなぐ() {
        assertEquals("Compared to him...", EnglishText.normalize(listOf("COM-", "PARED", "TO HIM...")))
        // 敬称のハイフンは残す
        assertEquals("Join us for once too, natori-chan?", EnglishText.normalize(listOf("JOIN US FOR", "ONCE TOO, NATORI-", "CHAN?")))
    }

    @Test
    fun 普通の大小文字の文はそのまま() {
        assertEquals("Are you coming to the party tonight?", EnglishText.normalize(listOf("Are you coming to", "the party tonight?")))
    }
}