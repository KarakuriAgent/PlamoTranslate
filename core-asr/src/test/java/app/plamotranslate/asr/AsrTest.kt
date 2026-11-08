package app.plamotranslate.asr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AsrTest {
    @Test
    fun 言語タグからコードを取り出す() {
        assertEquals("ja", SenseVoiceAsr.parseLang("<|ja|>"))
        assertEquals("ko", SenseVoiceAsr.parseLang("<|ko|>"))
        assertEquals("en", SenseVoiceAsr.parseLang("en"))
    }

    @Test
    fun 判定なしはnull() {
        assertNull(SenseVoiceAsr.parseLang("<|nospeech|>"))
        assertNull(SenseVoiceAsr.parseLang(""))
        assertNull(SenseVoiceAsr.parseLang(null))
    }

    @Test
    fun 中国語の相手でなければ中国語の判定は日本語と同じ話し手とみなす() {
        assertEquals("ja", SenseVoiceAsr.sameGroup("zh", separateChinese = false))
        assertEquals("ja", SenseVoiceAsr.sameGroup("yue", separateChinese = false))
        assertEquals("zh", SenseVoiceAsr.sameGroup("zh", separateChinese = true))
        assertEquals("zh", SenseVoiceAsr.sameGroup("yue", separateChinese = true))
        assertEquals("ko", SenseVoiceAsr.sameGroup("ko", separateChinese = true))
    }

    @Test
    fun 日本語の語の間の空白を取る() {
        assertEquals("うちの中学は弁当制で", SenseVoiceAsr.removeCjkSpaces("うち の 中学 は 弁当 制で", "ja"))
        assertEquals("場合は、50円の学校", SenseVoiceAsr.removeCjkSpaces("場 合は、50円 の学校", "ja"))
        assertEquals("iPhone 16を買う", SenseVoiceAsr.removeCjkSpaces("iPhone 16 を 買う", "ja"))
    }

    @Test
    fun 韓国語と英語の空白は残す() {
        assertEquals("훨씬 편할 거야", SenseVoiceAsr.removeCjkSpaces("훨씬 편할 거야", "ko"))
        assertEquals("Thank you", SenseVoiceAsr.removeCjkSpaces("Thank you", "en"))
    }

    @Test
    fun 中身のない聞き違いは空にする() {
        assertEquals("", SenseVoiceAsr.dropFillers(".", "en"))
        assertEquals("", SenseVoiceAsr.dropFillers("Yeah.", "en"))
        assertEquals("", SenseVoiceAsr.dropFillers("I.", "en"))
        assertEquals("", SenseVoiceAsr.dropFillers("。", "ja"))
        assertEquals("", SenseVoiceAsr.dropFillers("嗯。", "zh"))
        assertEquals("", SenseVoiceAsr.dropFillers("Yeah. Yeah.", "en"))
    }

    @Test
    fun 本当の返事と文は残す() {
        assertEquals("Yes.", SenseVoiceAsr.dropFillers("Yes.", "en"))
        assertEquals("OK.", SenseVoiceAsr.dropFillers("OK.", "en"))
        assertEquals("I see.", SenseVoiceAsr.dropFillers("I see.", "en"))
        assertEquals("Yeah, I think so.", SenseVoiceAsr.dropFillers("Yeah, I think so.", "en"))
        assertEquals("はい。", SenseVoiceAsr.dropFillers("はい。", "ja"))
        assertEquals("Thank you", SenseVoiceAsr.dropFillers("Thank you", "en"))
    }

    @Test
    fun 日本語と韓国語の頭と末尾の英語のあいづちを取る() {
        assertEquals("明日は雨らしいです。", SenseVoiceAsr.dropFillers("明日は雨らしいです。Yeah.", "ja"))
        assertEquals("明日は雨らしいです。", SenseVoiceAsr.dropFillers("I. 明日は雨らしいです。", "ja"))
        assertEquals("내일 비 온대요.", SenseVoiceAsr.dropFillers("내일 비 온대요. Yeah.", "ko"))
        assertEquals("iPhoneを買った。", SenseVoiceAsr.dropFillers("iPhoneを買った。", "ja"))
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
