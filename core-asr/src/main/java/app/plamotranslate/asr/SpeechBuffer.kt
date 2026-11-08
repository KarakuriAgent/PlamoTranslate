package app.plamotranslate.asr

import kotlin.math.sqrt

/**
 * 話している最中の音声をためる。途中結果と確定結果は、どちらもここにためた音声から認識する。
 * VADは話し始めを少し遅れて検出するので、直前の [prerollSamples] 分の音声を常に残しておき、頭に足す。
 * 長い発話を区切るときは先頭から取り出し（[take]）、残りは次の発話の頭として続けてためる。
 */
internal class SpeechBuffer(private val prerollSamples: Int, private val maxSamples: Int) {
    private val preroll = FloatArray(prerollSamples)
    private var prerollFilled = 0
    private var prerollPos = 0

    private var speech = FloatArray(AsrEngine.SAMPLE_RATE * 2)
    private var speechSize = 0

    var active = false
        private set

    val size: Int get() = speechSize

    /** 話し始めたときに頭へ足す、直前の音声の長さ（サンプル数）。 */
    val prerollSize: Int get() = prerollFilled

    /** 話していない間の音声。話し始めの頭に足すために、直近の分だけ残す。 */
    fun idle(samples: FloatArray) {
        active = false
        speechSize = 0
        for (s in samples) {
            preroll[prerollPos] = s
            prerollPos = (prerollPos + 1) % prerollSamples
            if (prerollFilled < prerollSamples) prerollFilled++
        }
    }

    /** 話している間の音声。話し始めなら、残しておいた直前の音声を頭に置く。 */
    fun speaking(samples: FloatArray) {
        if (!active) {
            active = true
            speechSize = 0
            val start = (prerollPos - prerollFilled + prerollSamples) % prerollSamples
            for (i in 0 until prerollFilled) append(preroll[(start + i) % prerollSamples])
            prerollFilled = 0
        }
        for (s in samples) append(s)
    }

    fun snapshot(): FloatArray = speech.copyOf(speechSize)

    /** [from]〜[to] の音声の写し。 */
    fun slice(from: Int, to: Int): FloatArray = speech.copyOfRange(from.coerceIn(0, speechSize), to.coerceIn(0, speechSize).coerceAtLeast(from.coerceIn(0, speechSize)))

    /** 先頭から [count] サンプルを取り出し、残りを先頭へ詰める。話している状態は続ける。 */
    fun take(count: Int): FloatArray {
        val n = count.coerceIn(0, speechSize)
        val head = speech.copyOf(n)
        speech.copyInto(speech, 0, n, speechSize)
        speechSize -= n
        return head
    }

    /**
     * [from]〜[to] の範囲で、[frame] サンプルごとの音量（二乗平均平方根）が一番小さい区間の中央を返す。
     * 長い発話を区切る位置に使う（息継ぎや語の切れ目は音が小さい）。
     */
    fun quietestPoint(from: Int, to: Int, frame: Int): Int {
        val start = from.coerceIn(0, speechSize)
        val end = to.coerceIn(start, speechSize)
        if (end - start < frame) return end
        var best = start + frame / 2
        var bestRms = Double.MAX_VALUE
        var i = start
        while (i + frame <= end) {
            var sum = 0.0
            for (j in i until i + frame) sum += speech[j] * speech[j]
            val rms = sqrt(sum / frame)
            if (rms < bestRms) {
                bestRms = rms
                best = i + frame / 2
            }
            i += frame / 2
        }
        return best
    }

    fun clear() {
        active = false
        speechSize = 0
        prerollFilled = 0
    }

    private fun append(s: Float) {
        // 区切りより長くはためない（念のための上限）
        if (speechSize >= maxSamples) return
        if (speechSize == speech.size) speech = speech.copyOf(minOf(speech.size * 2, maxSamples))
        speech[speechSize++] = s
    }
}
