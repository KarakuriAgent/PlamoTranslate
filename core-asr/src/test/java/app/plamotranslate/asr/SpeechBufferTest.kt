package app.plamotranslate.asr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechBufferTest {
    @Test
    fun 話し始めに直前の音声を頭に足す() {
        val buf = SpeechBuffer(prerollSamples = 3, maxSamples = 100)
        buf.idle(floatArrayOf(1f, 2f, 3f, 4f, 5f))
        buf.speaking(floatArrayOf(6f, 7f))
        assertArrayEquals(floatArrayOf(3f, 4f, 5f, 6f, 7f), buf.snapshot(), 0f)
    }

    @Test
    fun 話し終わったら次の発話は新しくためる() {
        val buf = SpeechBuffer(prerollSamples = 2, maxSamples = 100)
        buf.speaking(floatArrayOf(1f, 2f))
        buf.idle(floatArrayOf(3f))
        assertFalse(buf.active)
        buf.speaking(floatArrayOf(4f))
        assertArrayEquals(floatArrayOf(3f, 4f), buf.snapshot(), 0f)
    }

    @Test
    fun 区切るときは先頭を取り出し残りを途切れずに続ける() {
        val buf = SpeechBuffer(prerollSamples = 2, maxSamples = 100)
        buf.speaking(floatArrayOf(1f, 2f, 3f, 4f))
        assertArrayEquals(floatArrayOf(1f, 2f), buf.take(2), 0f)
        buf.speaking(floatArrayOf(5f))
        assertArrayEquals(floatArrayOf(3f, 4f, 5f), buf.snapshot(), 0f)
        assertTrue(buf.active)
    }

    @Test
    fun 一番静かなところを区切りにする() {
        val buf = SpeechBuffer(prerollSamples = 1, maxSamples = 100)
        // 0〜19は大きな音、20〜29は静か、30〜39は大きな音
        buf.speaking(FloatArray(40) { if (it in 20 until 30) 0.01f else 0.8f })
        val at = buf.quietestPoint(0, 40, frame = 10)
        assertTrue("区切り $at", at in 20..30)
    }

    @Test
    fun 上限より長くはためない() {
        val buf = SpeechBuffer(prerollSamples = 1, maxSamples = 4)
        buf.speaking(FloatArray(10) { it.toFloat() })
        assertEquals(4, buf.size)
    }
}
