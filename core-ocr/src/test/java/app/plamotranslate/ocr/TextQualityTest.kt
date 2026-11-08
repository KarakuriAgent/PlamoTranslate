package app.plamotranslate.ocr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextQualityTest {
    @Test
    fun 実機で出た崩れた文字列を見分ける() {
        // 2026-11-05、Claudeアプリの日韓混在の画面で実際に出た認識結果
        assertTrue(TextQuality.looksGarbled("9 EalO7기O플0정춘 LttAtao Otohi그 7>지가igr<그DE가 Rt산는 존O그7>지O 5#U준 30t조tUt, 준3니5A"))
        assertTrue(TextQuality.looksGarbled("ㅎㅎ바0 얼굴 보기 힘드 : 후주LI• mte\$ao E한토증9D했LU£ OREhtT."))
        assertTrue(TextQuality.looksGarbled("후후프품E #C표국수하드#깨(훗태K반말 gb챕치, 환수, [시원하다] S*U hERT 훈#, 정 눈"))
    }

    @Test
    fun 普通の文は残す() {
        assertFalse(TextQuality.looksGarbled("퇴근길에 지수 언니한테서 문자가 왔다. \"요즘 많이 바쁜가 봐? 얼굴 보기 힘드네ㅎㅎ\""))
        assertFalse(TextQuality.looksGarbled("오늘 SALE 50% 할인"))
        assertFalse(TextQuality.looksGarbled("I'm sorry, but this seat is reserved."))
        assertFalse(TextQuality.looksGarbled("这个菜太辣了，可以换一个不辣的吗？"))
        assertFalse(TextQuality.looksGarbled("iPhone 17 Pro 출시 기념 이벤트"))
        // 2026-11-05の漫画の評価で、誤って崩れと判定していた吹き出し
        assertFalse(TextQuality.looksGarbled("신경 쓰이기\n시작한 건\n2학년?\n무렵부터였나…"))
        assertFalse(TextQuality.looksGarbled("말이야~"))
    }
}
