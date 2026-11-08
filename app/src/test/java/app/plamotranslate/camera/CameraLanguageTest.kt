package app.plamotranslate.camera

import app.plamotranslate.llm.Language
import app.plamotranslate.ocr.OcrLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLanguageTest {
    @Test
    fun 読める認識器がある言語だけを選べる() {
        assertEquals(OcrLanguage.KOREAN, Language.KO.ocrLanguage)
        assertEquals(OcrLanguage.CHINESE, Language.ZH_HANT.ocrLanguage)
        assertEquals(OcrLanguage.LATIN, Language.VI.ocrLanguage)
        // キリル文字・タイ文字は読める認識器が無い。訳先の日本語は原文に選ばない
        assertFalse(Language.RU in CAMERA_SOURCES)
        assertFalse(Language.TH in CAMERA_SOURCES)
        assertFalse(Language.JA in CAMERA_SOURCES)
        assertEquals(28, CAMERA_SOURCES.size)
    }

    @Test
    fun チップの言語は選べる言語に含まれる() {
        assertTrue(CAMERA_SOURCES.containsAll(CAMERA_QUICK_SOURCES))
    }
}
