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
        val separated = run(speech("ja", 2.0), silence(0.8), speech("zh", 2.0), silence(3.0), languages = SpeakerLanguages("ja", "zh"))
        assertEquals(listOf("ja", "zh"), finals(separated).map { it.lang })
    }

    @Test
    fun 揺れやすい言語の組ではカードを切らない() {
        val events = run(speech("es", 2.0), silence(0.8), speech("pt", 2.0), silence(3.0))
        assertEquals(1, finals(events).size)
    }

    @Test
    fun 長く話し続けたら14秒までに区切る() {
        val events = run(speech("es", 20.0), silence(3.0))
        val cards = finals(events)
        assertTrue("カード ${cards.size}", cards.size >= 2)
        assertTrue(cards.all { it.durationMs <= 14_000 })
        assertTrue(cards.all { it.lang == "es" })
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

    @Test
    fun 補助のモデルがあっても自分の言語はSenseVoiceの文字を使い補助を読み込まない() {
        val helper = FakeHelper()
        val events = run(speech("ja", 3.0), silence(3.0), helper = HelperModel.CANARY, fake = helper, languages = SpeakerLanguages("ja", "de"))
        assertEquals(listOf("sv"), finals(events).map { it.text.substringBefore(' ') })
        assertEquals(0, helper.loads)
    }

    @Test
    fun 相手がSenseVoiceの言語なら補助を使わない() {
        val helper = FakeHelper()
        val events = run(
            speech("ja", 2.0), silence(0.8), speech("ko", 2.0), silence(3.0),
            helper = HelperModel.QWEN3_ASR, fake = helper, languages = SpeakerLanguages("ja", "ko"),
        )
        assertEquals(listOf("ja", "ko"), finals(events).map { it.lang })
        assertTrue(finals(events).all { it.text.startsWith("sv") })
        assertEquals(0, helper.loads)
    }

    @Test
    fun SenseVoiceに無い固定の相手は補助の文字で言語は相手にする() {
        val helper = FakeHelper()
        val events = run(
            speech("ja", 2.0), silence(0.8), german(5.0), silence(3.0),
            helper = HelperModel.CANARY, fake = helper, languages = SpeakerLanguages("ja", "de"),
        )
        // ドイツ語の発話の中でSenseVoiceの判定が英語・韓国語と揺れても、カードは切れない
        val cards = finals(events)
        assertEquals(listOf("ja", "de"), cards.map { it.lang })
        assertEquals(listOf("sv", "helper"), cards.map { it.text.substringBefore(' ') })
        // 相手を固定したときは、言語を指定して認識する（補助に言語を判定させない）
        assertEquals(0, helper.detects)
        assertEquals(setOf("de"), helper.requested)
        assertEquals(1, helper.loads)
        // 途中結果も補助のモデルで認識し直す
        assertTrue(events.filterIsInstance<AsrEvent.Partial>().any { it.text.startsWith("helper") && it.lang == "de" })
    }

    @Test
    fun 補助を使う会話ではSenseVoiceが空でも補助で聞き直す() {
        val helper = FakeHelper()
        val events = run(speech("es-dot", 3.0), silence(3.0), helper = HelperModel.CANARY, fake = helper, languages = SpeakerLanguages("ja", "es"))
        assertEquals(listOf("es"), finals(events).map { it.lang })
        assertEquals(listOf("helper"), finals(events).map { it.text.substringBefore(' ') })
    }

    @Test
    fun SenseVoiceだけならドイツ語の判定の揺れでカードが切れる() {
        // 補助のモデルを使う会話で、自分以外の言語をまとめる理由（上のテストとの対比）
        val events = run(speech("ja", 2.0), silence(0.8), german(5.0), silence(3.0), languages = SpeakerLanguages("ja", "de"))
        assertTrue(finals(events).size > 2)
    }

    @Test
    fun 自動の相手でQwen3_ASRなら自分の言語でない発話を補助で認識し言語も補助が決める() {
        val helper = FakeHelper(detected = "fr")
        val events = run(
            speech("ja", 2.0), silence(0.8), german(5.0), silence(3.0),
            helper = HelperModel.QWEN3_ASR, fake = helper, languages = SpeakerLanguages("ja", null),
        )
        val cards = finals(events)
        assertEquals(listOf("ja", "fr"), cards.map { it.lang })
        assertEquals(listOf("sv", "helper"), cards.map { it.text.substringBefore(' ') })
        assertTrue(helper.detects > 0)
    }

    @Test
    fun 自動の相手で言語を判定できない補助は使わない() {
        val helper = FakeHelper()
        val events = run(speech("ko", 3.0), silence(3.0), helper = HelperModel.CANARY, fake = helper, languages = SpeakerLanguages("ja", null))
        assertEquals(listOf("ko"), finals(events).map { it.lang })
        assertEquals(0, helper.loads)
    }

    @Test
    fun 補助を使うかの決め方() {
        assertEquals(false, SegmentingAsr.usesHelper(null, SpeakerLanguages("ja", "de")))
        assertEquals(true, SegmentingAsr.usesHelper(HelperModel.CANARY, SpeakerLanguages("ja", "de")))
        assertEquals(true, SegmentingAsr.usesHelper(HelperModel.PARAKEET, SpeakerLanguages("ja", "pl")))
        // Canaryはポーランド語を聞けない。相手がSenseVoiceの言語なら補助は要らない
        assertEquals(false, SegmentingAsr.usesHelper(HelperModel.CANARY, SpeakerLanguages("ja", "pl")))
        assertEquals(false, SegmentingAsr.usesHelper(HelperModel.QWEN3_ASR, SpeakerLanguages("ja", "en")))
        assertEquals(false, SegmentingAsr.usesHelper(HelperModel.QWEN3_ASR, SpeakerLanguages("ja", "yue")))
        // 自動の相手は、言語を判定できる補助（Qwen3-ASR）だけ
        assertEquals(true, SegmentingAsr.usesHelper(HelperModel.QWEN3_ASR, SpeakerLanguages("ja", null)))
        assertEquals(false, SegmentingAsr.usesHelper(HelperModel.CANARY, SpeakerLanguages("ja", null)))
    }

    @Test
    fun 補助を使う会話では自分以外の言語を相手の言語にまとめる() {
        val de = SpeakerLanguages("ja", "de")
        assertEquals("de", SegmentingAsr.group("en", de, helperUsed = true))
        assertEquals("de", SegmentingAsr.group("ko", de, helperUsed = true))
        assertEquals("ja", SegmentingAsr.group("ja", de, helperUsed = true))
        // 中国語の相手でなければ、中国語の判定は日本語（自分）の聞き違いのまま
        assertEquals("ja", SegmentingAsr.group("zh", de, helperUsed = true))
        assertEquals(null, SegmentingAsr.group(null, de, helperUsed = true))
        // 自動の相手は1つの印にまとめる
        assertEquals(SegmentingAsr.PARTNER, SegmentingAsr.group("ko", SpeakerLanguages("ja", null), helperUsed = true))
        // 自分が英語なら、英語の判定は自分
        assertEquals("en", SegmentingAsr.group("en", SpeakerLanguages("en", "fr"), helperUsed = true))
        // 補助を使わない会話は今までどおり
        assertEquals("ko", SegmentingAsr.group("ko", SpeakerLanguages("ja", null), helperUsed = false))
    }

    private fun run(
        vararg parts: FloatArray,
        languages: SpeakerLanguages = SpeakerLanguages(),
        helper: HelperModel? = null,
        fake: FakeHelper = FakeHelper(),
    ): List<AsrEvent> {
        val asr = SegmentingAsr(FakeVad(), FakeTranscriber(), helper) { fake.also { it.loads++ } }
        asr.languages = languages
        return feed(asr, *parts)
    }

    private fun feed(asr: SegmentingAsr, vararg parts: FloatArray): List<AsrEvent> {
        val all = parts.reduce { a, b -> a + b }
        val chunk = AsrEngine.SAMPLE_RATE / 10
        return (all.indices step chunk).flatMap { asr.accept(all.copyOfRange(it, minOf(it + chunk, all.size))) }
    }

    private fun finals(events: List<AsrEvent>) = events.filterIsInstance<AsrEvent.Final>().map { it.utterance }

    private fun speech(lang: String, seconds: Double) = FloatArray((seconds * AsrEngine.SAMPLE_RATE).toInt()) { LEVELS.getValue(lang) }

    /** ドイツ語の発話。SenseVoiceの代わりは前半を英語、後半を韓国語と聞く。 */
    private fun german(seconds: Double) = speech("de", seconds / 2) + speech("de2", seconds / 2)

    private fun silence(seconds: Double) = FloatArray((seconds * AsrEngine.SAMPLE_RATE).toInt())

    private companion object {
        /** 言語ごとの音の大きさ。代わりの認識器はこの値で言語を見分ける。 */
        val LEVELS = mapOf("ja" to 0.3f, "ko" to 0.4f, "zh" to 0.5f, "es" to 0.6f, "pt" to 0.7f, "de" to 0.8f, "de2" to 0.9f, "es-dot" to 0.95f)
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

    /**
     * SenseVoiceの代わり：一番長く続く音の大きさから言語を返す認識器。文字は「sv 言語 長さ」。
     * ドイツ語（SenseVoiceに無い）は、前半を英語、後半を韓国語と取り違える（[german]。実際のSenseVoiceの判定の揺れをまねる）。
     */
    private class FakeTranscriber : Transcriber {
        override fun transcribe(samples: FloatArray): Transcript {
            val counts = samples.filter { it > 0.05f }.groupingBy { it }.eachCount()
            val top = counts.maxByOrNull { it.value } ?: return Transcript("", null)
            val lang = when (val heard = LEVELS.entries.first { it.value == top.key }.key) {
                "de" -> "en"
                "de2" -> "ko"
                // スペイン語の発話を「.」とだけ聞いた（中身が無い）ときのまね
                "es-dot" -> return Transcript("", null)
                else -> heard
            }
            return Transcript("sv $lang ${counts.values.sum()}", lang)
        }

        override fun close() = Unit
    }

    /** 補助のモデルの代わり。文字は「helper 長さ」。言語を判定させると [detected] を返す。呼ばれ方を数える。 */
    private class FakeHelper(private val detected: String? = null) : Transcriber {
        var loads = 0
        var detects = 0
        val requested = mutableSetOf<String>()

        override fun transcribe(samples: FloatArray): Transcript {
            detects++
            return Transcript("helper ${samples.size}", detected)
        }

        override fun transcribe(samples: FloatArray, lang: String): Transcript {
            requested += lang
            return Transcript("helper ${samples.size}", lang)
        }

        override fun close() = Unit
    }
}
