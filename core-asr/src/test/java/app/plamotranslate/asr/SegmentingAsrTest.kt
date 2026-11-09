package app.plamotranslate.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 区切り（[SegmentingAsr]）の規則を、VADと認識器の代わりを使って確かめる（2026-11-09、認識器を差し替えられるようにしたときに足した）。
 * 音声は言語ごとに決まった大きさの値で作り、代わりの認識器はその値から言語を返す。
 */
class SegmentingAsrTest {
    @Test
    fun 話し終わって2秒黙るとカードが1枚確定する() {
        val events = run(speech("ja", 3.0), silence(3.0))
        assertEquals(listOf("ja"), finals(events).map { it.lang })
        assertEquals(AsrEvent.Speaking(false), events.last())
        assertTrue(events.any { it is AsrEvent.Partial })
    }

    @Test
    fun 言いよどみの間ではカードを切らない() {
        val events = run(speech("ja", 2.0), silence(1.0), speech("ja", 2.0), silence(3.0))
        assertEquals(listOf("ja"), finals(events).map { it.lang })
    }

    @Test
    fun 間を空けて話し手が代わったら2枚に分ける() {
        val events = run(speech("ja", 2.0), silence(0.8), speech("ko", 2.0), silence(3.0))
        assertEquals(listOf("ja", "ko"), finals(events).map { it.lang })
    }

    @Test
    fun 間を空けずに話し手が代わっても2枚に分ける() {
        val events = run(speech("ja", 3.0), speech("ko", 4.0), silence(3.0))
        assertEquals(listOf("ja", "ko"), finals(events).map { it.lang })
    }

    @Test
    fun 中国語の相手でなければ中国語の判定で日本語のカードを切らない() {
        val events = run(speech("ja", 2.0), silence(0.8), speech("zh", 2.0), silence(3.0))
        assertEquals(1, finals(events).size)
        val separated = run(speech("ja", 2.0), silence(0.8), speech("zh", 2.0), silence(3.0), separateChinese = true)
        assertEquals(listOf("ja", "zh"), finals(separated).map { it.lang })
    }

    @Test
    fun 揺れやすい言語の組ではカードを切らない() {
        val events = run(speech("es", 2.0), silence(0.8), speech("pt", 2.0), silence(3.0))
        assertEquals(1, finals(events).size)
    }

    @Test
    fun 長く話し続けたら14秒までに区切る() {
        val events = run(speech("de", 20.0), silence(3.0))
        val cards = finals(events)
        assertTrue("カード ${cards.size}", cards.size >= 2)
        assertTrue(cards.all { it.durationMs <= 14_000 })
        assertTrue(cards.all { it.lang == "de" })
    }

    @Test
    fun 止めたときは話しかけの分も確定させる() {
        val asr = SegmentingAsr(FakeVad(), FakeTranscriber())
        val events = feed(asr, speech("ja", 2.0)) + asr.flush()
        assertEquals(listOf("ja"), finals(events).map { it.lang })
        assertEquals(AsrEvent.Speaking(false), events.last())
    }

    @Test
    fun 言語の比べ方() {
        assertEquals("ja", SegmentingAsr.sameGroup("zh", separateChinese = false))
        assertEquals("ja", SegmentingAsr.sameGroup("yue", separateChinese = false))
        assertEquals("zh", SegmentingAsr.sameGroup("yue", separateChinese = true))
        assertEquals("es", SegmentingAsr.sameGroup("pt", separateChinese = false))
        assertEquals("de", SegmentingAsr.sameGroup("de", separateChinese = false))
        assertEquals(null, SegmentingAsr.sameGroup(null, separateChinese = false))
    }

    private fun run(vararg parts: FloatArray, separateChinese: Boolean = false): List<AsrEvent> {
        val asr = SegmentingAsr(FakeVad(), FakeTranscriber())
        asr.separateChinese = separateChinese
        return feed(asr, *parts)
    }

    private fun feed(asr: SegmentingAsr, vararg parts: FloatArray): List<AsrEvent> {
        val all = parts.reduce { a, b -> a + b }
        val chunk = AsrEngine.SAMPLE_RATE / 10
        return (all.indices step chunk).flatMap { asr.accept(all.copyOfRange(it, minOf(it + chunk, all.size))) }
    }

    private fun finals(events: List<AsrEvent>) = events.filterIsInstance<AsrEvent.Final>().map { it.utterance }

    private fun speech(lang: String, seconds: Double) = FloatArray((seconds * AsrEngine.SAMPLE_RATE).toInt()) { LEVELS.getValue(lang) }

    private fun silence(seconds: Double) = FloatArray((seconds * AsrEngine.SAMPLE_RATE).toInt())

    private companion object {
        /** 言語ごとの音の大きさ。代わりの認識器はこの値で言語を見分ける。 */
        val LEVELS = mapOf("ja" to 0.3f, "ko" to 0.4f, "zh" to 0.5f, "es" to 0.6f, "pt" to 0.7f, "de" to 0.8f)
    }

    /** 音があれば話している、0.6秒黙ったら文の終わり、とするVAD。 */
    private class FakeVad : VoiceDetector {
        private var silent = 0
        private var inSpeech = false

        override fun accept(samples: FloatArray): VoiceActivity {
            val loud = samples.any { kotlin.math.abs(it) > 0.05f }
            var ended = false
            if (loud) {
                inSpeech = true
                silent = 0
            } else if (inSpeech) {
                silent += samples.size
                if (silent >= (SegmentingAsr.PHRASE_SILENCE_SECONDS * AsrEngine.SAMPLE_RATE).toInt()) {
                    ended = true
                    inSpeech = false
                }
            }
            return VoiceActivity(speaking = inSpeech, phraseEnded = ended)
        }

        override fun flush() = Unit
        override fun reset() {
            silent = 0
            inSpeech = false
        }
        override fun close() = Unit
    }

    /** 一番長く続く音の大きさから言語を返す認識器。 */
    private class FakeTranscriber : Transcriber {
        override fun transcribe(samples: FloatArray): Transcript {
            val counts = samples.filter { it > 0.05f }.groupingBy { it }.eachCount()
            val top = counts.maxByOrNull { it.value } ?: return Transcript("", null)
            val lang = LEVELS.entries.first { it.value == top.key }.key
            return Transcript("$lang ${counts.values.sum()}", lang)
        }

        override fun close() = Unit
    }
}
