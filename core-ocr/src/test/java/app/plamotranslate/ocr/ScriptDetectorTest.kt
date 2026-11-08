package app.plamotranslate.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptDetectorTest {
    @Test
    fun 文字種を優先順に判定する() {
        assertEquals(Script.HANGUL, ScriptDetector.detect("오늘 SALE 50%"))
        assertEquals(Script.KANA, ScriptDetector.detect("設定を開く"))
        assertEquals(Script.HAN, ScriptDetector.detect("这个菜太辣了"))
        assertEquals(Script.LATIN, ScriptDetector.detect("Sign in"))
        assertEquals(Script.OTHER, ScriptDetector.detect("12:34"))
    }
}
