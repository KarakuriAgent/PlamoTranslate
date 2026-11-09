package app.plamotranslate.asr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AsrTest {
    @Test
    fun 言語タグからコードを取り出す() {
        assertEquals("ja", SenseVoiceTranscriber.parseLang("<|ja|>"))
        assertEquals("ko", SenseVoiceTranscriber.parseLang("<|ko|>"))
        assertEquals("en", SenseVoiceTranscriber.parseLang("en"))
    }

    @Test
    fun 判定なしはnull() {
        assertNull(SenseVoiceTranscriber.parseLang("<|nospeech|>"))
        assertNull(SenseVoiceTranscriber.parseLang(""))
        assertNull(SenseVoiceTranscriber.parseLang(null))
    }

    @Test
    fun 中国語の相手でなければ中国語の判定は日本語と同じ話し手とみなす() {
        assertEquals("ja", SegmentingAsr.sameGroup("zh", separateChinese = false))
        assertEquals("ja", SegmentingAsr.sameGroup("yue", separateChinese = false))
        assertEquals("zh", SegmentingAsr.sameGroup("zh", separateChinese = true))
        assertEquals("zh", SegmentingAsr.sameGroup("yue", separateChinese = true))
        assertEquals("ko", SegmentingAsr.sameGroup("ko", separateChinese = true))
    }

    @Test
    fun 日本語の語の間の空白を取る() {
        assertEquals("うちの中学は弁当制で", SenseVoiceTranscriber.removeCjkSpaces("うち の 中学 は 弁当 制で", "ja"))
        assertEquals("場合は、50円の学校", SenseVoiceTranscriber.removeCjkSpaces("場 合は、50円 の学校", "ja"))
        assertEquals("iPhone 16を買う", SenseVoiceTranscriber.removeCjkSpaces("iPhone 16 を 買う", "ja"))
    }

    @Test
    fun 韓国語と英語の空白は残す() {
        assertEquals("훨씬 편할 거야", SenseVoiceTranscriber.removeCjkSpaces("훨씬 편할 거야", "ko"))
        assertEquals("Thank you", SenseVoiceTranscriber.removeCjkSpaces("Thank you", "en"))
    }

    @Test
    fun 中身のない聞き違いは空にする() {
        assertEquals("", SenseVoiceTranscriber.dropFillers(".", "en"))
        assertEquals("", SenseVoiceTranscriber.dropFillers("Yeah.", "en"))
        assertEquals("", SenseVoiceTranscriber.dropFillers("I.", "en"))
        assertEquals("", SenseVoiceTranscriber.dropFillers("。", "ja"))
        assertEquals("", SenseVoiceTranscriber.dropFillers("嗯。", "zh"))
        assertEquals("", SenseVoiceTranscriber.dropFillers("Yeah. Yeah.", "en"))
    }

    @Test
    fun 本当の返事と文は残す() {
        assertEquals("Yes.", SenseVoiceTranscriber.dropFillers("Yes.", "en"))
        assertEquals("OK.", SenseVoiceTranscriber.dropFillers("OK.", "en"))
        assertEquals("I see.", SenseVoiceTranscriber.dropFillers("I see.", "en"))
        assertEquals("Yeah, I think so.", SenseVoiceTranscriber.dropFillers("Yeah, I think so.", "en"))
        assertEquals("はい。", SenseVoiceTranscriber.dropFillers("はい。", "ja"))
        assertEquals("Thank you", SenseVoiceTranscriber.dropFillers("Thank you", "en"))
    }

    @Test
    fun 日本語と韓国語の頭と末尾の英語のあいづちを取る() {
        assertEquals("明日は雨らしいです。", SenseVoiceTranscriber.dropFillers("明日は雨らしいです。Yeah.", "ja"))
        assertEquals("明日は雨らしいです。", SenseVoiceTranscriber.dropFillers("I. 明日は雨らしいです。", "ja"))
        assertEquals("내일 비 온대요.", SenseVoiceTranscriber.dropFillers("내일 비 온대요. Yeah.", "ko"))
        assertEquals("iPhoneを買った。", SenseVoiceTranscriber.dropFillers("iPhoneを買った。", "ja"))
    }

    @Test
    fun Qwen3_ASRの言語名からコードを引く() {
        assertEquals("de", Qwen3AsrTranscriber.parseLanguage("language German"))
        assertEquals("ja", Qwen3AsrTranscriber.parseLanguage("language Japanese"))
        // 2トークンの言語名は、2つ目のトークンの手前で切れる（2026-11-09、PCで確かめた）
        assertEquals("yue", Qwen3AsrTranscriber.parseLanguage("language Canton"))
        assertEquals("yue", Qwen3AsrTranscriber.parseLanguage("language Cantonese"))
        assertEquals("fil", Qwen3AsrTranscriber.parseLanguage("language Filipino"))
    }

    @Test
    fun Qwen3_ASRの判定なしと形の違う文字はnull() {
        assertNull(Qwen3AsrTranscriber.parseLanguage("language None"))
        assertNull(Qwen3AsrTranscriber.parseLanguage(""))
        assertNull(Qwen3AsrTranscriber.parseLanguage("language"))
        assertNull(Qwen3AsrTranscriber.parseLanguage("Guten Tag"))
    }

    @Test
    fun Qwen3_ASRの記号だけの結果は空にする() {
        assertEquals("", Qwen3AsrTranscriber.cleanText(" 。 "))
        assertEquals("Guten Tag.", Qwen3AsrTranscriber.cleanText(" Guten Tag. "))
    }

    @Test
    fun モデルごとに聞ける言語() {
        assertEquals(setOf("ja", "ko", "en", "zh", "yue"), AsrModel.SENSE_VOICE.languages)
        assertEquals(30, AsrModel.QWEN3_ASR.languages.size)
        assertTrue("de" in AsrModel.QWEN3_ASR.languages)
    }

    @Test
    fun WAVの音声を読む() {
        val samples = shortArrayOf(0, 16384, -32768)
        val wav = wav(samples)
        assertArrayEquals(floatArrayOf(0f, 0.5f, -1f), WavFile.parse(wav), 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun 形式が違うWAVは読まない() {
        WavFile.parse(wav(shortArrayOf(0), rate = 44_100))
    }

    private fun wav(samples: ShortArray, rate: Int = 16_000): ByteArray {
        val data = samples.size * 2
        return ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2)
            putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data)
            samples.forEach { putShort(it) }
        }.array()
    }
}
