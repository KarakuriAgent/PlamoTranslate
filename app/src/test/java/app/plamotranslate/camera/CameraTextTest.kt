package app.plamotranslate.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** カメラ翻訳の、文字列だけで決まる判定（docs/10_カメラ翻訳.md）。枠の位置を使う判定は Rect が要るので実機で確かめる。 */
class CameraTextTest {
    @Test
    fun keyKeepsOnlyLettersAndDigits() {
        assertEquals("1이제품의품질보증기간은", TextSimilarity.key("1. 이 제품의 품질보증기간은,"))
        assertEquals("coldbrew", TextSimilarity.key("COLD  BREW"))
    }

    @Test
    fun similarAcceptsSmallReadingNoise() {
        val a = TextSimilarity.key("이 제품의 품질보증기간은 구입일로부터 1년이며")
        val b = TextSimilarity.key("이 제품의 품질보증기간은 구입일로부터 1년이머")
        assertTrue(TextSimilarity.similar(a, b))
    }

    @Test
    fun similarRejectsDifferentOrShortWords() {
        // 短い語は1文字違えば別の語
        assertFalse(TextSimilarity.similar(TextSimilarity.key("정지"), TextSimilarity.key("정자")))
        assertFalse(TextSimilarity.similar(TextSimilarity.key("냉방 운전"), TextSimilarity.key("난방 운전 예약 설정")))
        assertTrue(TextSimilarity.similar("세기", "세기"))
    }

    @Test
    fun itemStartsNewParagraph() {
        listOf("1. 제품의 임의 분해", "2) 제품 위로", "3 모서리에 부딪쳐", "① 주의", "・注意", "※ 안내", "- item").forEach {
            assertTrue(it, Paragraphs.ITEM_START.containsMatchIn(it))
        }
        // 数字のすぐ後に文字が続くのは項目の番号ではない
        listOf("2인분부터 주문 가능해요", "500ml 80kcal", "이 제품은", "COLD BREW").forEach {
            assertFalse(it, Paragraphs.ITEM_START.containsMatchIn(it))
        }
    }

    @Test
    fun joinUsesSpacesOnlyForKoreanAndLatin() {
        assertEquals("바람 세기", Paragraphs.join(listOf("바람", " 세기 ")))
        assertEquals("Do not use near fire", Paragraphs.join(listOf("Do not use", "near fire")))
        assertEquals("製品の上に登らないでください", Paragraphs.join(listOf("製品の上に登らないで", "ください")))
    }
}
