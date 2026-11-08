package app.plamotranslate.conversation

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import app.plamotranslate.asr.AsrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * マイクの音声を0.1秒ずつ流す（16kHz・モノラル、-1〜1のfloat）。収集を止めると録音も止まる。
 * 音源は音声認識向け（VOICE_RECOGNITION）で、端末の自動音量調整や雑音処理を音声認識に合わせたものを使う。
 */
object MicRecorder {
    private const val CHUNK = AsrEngine.SAMPLE_RATE / 10

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun stream(): Flow<FloatArray> = flow {
        val minBuffer = AudioRecord.getMinBufferSize(
            AsrEngine.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            AsrEngine.SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, CHUNK * 2 * 4),
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) { "マイクを開けませんでした" }
        val pcm = ShortArray(CHUNK)
        try {
            record.startRecording()
            while (currentCoroutineContext().isActive) {
                val n = record.read(pcm, 0, pcm.size)
                if (n < 0) error("マイクの読み取りに失敗しました（$n）")
                if (n > 0) emit(FloatArray(n) { pcm[it] / 32768f })
            }
        } finally {
            record.stop()
            record.release()
        }
    }.flowOn(Dispatchers.IO)
}
