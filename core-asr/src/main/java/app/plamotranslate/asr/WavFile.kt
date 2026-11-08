package app.plamotranslate.asr

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 動作確認で、マイクの代わりに流す音声ファイルを読む。16kHz・モノラル・16bit PCMのWAVだけ扱う。 */
object WavFile {
    fun read(file: File): FloatArray = parse(file.readBytes(), file.name)

    internal fun parse(bytes: ByteArray, name: String = "WAV"): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12 && buf.getInt(0) == RIFF && buf.getInt(8) == WAVE) { "WAVではありません: $name" }
        var pos = 12
        var channels = 0
        var rate = 0
        var bits = 0
        while (pos + 8 <= bytes.size) {
            val id = buf.getInt(pos)
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                FMT -> {
                    channels = buf.getShort(body + 2).toInt()
                    rate = buf.getInt(body + 4)
                    bits = buf.getShort(body + 14).toInt()
                }
                DATA -> {
                    require(channels == 1 && rate == AsrEngine.SAMPLE_RATE && bits == 16) {
                        "16kHz・モノラル・16bitのWAVだけ読めます（$name：${rate}Hz・${channels}ch・${bits}bit）"
                    }
                    val n = minOf(size, bytes.size - body) / 2
                    return FloatArray(n) { buf.getShort(body + it * 2) / 32768f }
                }
            }
            pos = body + size + (size and 1)
        }
        error("WAVに音声データがありません: $name")
    }

    // チャンクIDをリトルエンディアンのIntとして読んだ値
    private const val RIFF = 0x46464952 // "RIFF"
    private const val WAVE = 0x45564157 // "WAVE"
    private const val FMT = 0x20746d66 // "fmt "
    private const val DATA = 0x61746164 // "data"
}
