package app.plamotranslate.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Androidの読み上げエンジン（Googleの音声サービスなど）で読む（2026-11-08）。追加のダウンロードは要らない。
 * Supertonic 3が読めない中国語は、こちらで読む。オフラインで読めるかは、端末に入っている音声データしだい。
 */
class SystemSpeaker(context: Context) : Speaker {
    private val ready = CompletableDeferred<Boolean>()
    private val tts = TextToSpeech(context.applicationContext) { status -> ready.complete(status == TextToSpeech.SUCCESS) }

    override fun supports(lang: String): Boolean = true

    override suspend fun speak(text: String, lang: String) {
        if (!ready.await()) throw SpeechException("端末の読み上げエンジンを使えません")
        if (tts.setLanguage(locale(lang)) < TextToSpeech.LANG_AVAILABLE) {
            throw SpeechException("端末の読み上げに、この言語の音声がありません（設定の「テキスト読み上げの出力」で追加できます）")
        }
        val id = UUID.randomUUID().toString()
        suspendCancellableCoroutine { cont ->
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String) {}
                override fun onDone(utteranceId: String) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
                override fun onStop(utteranceId: String, interrupted: Boolean) {
                    if (utteranceId == id && cont.isActive) cont.resume(Unit)
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String) {
                    if (utteranceId == id && cont.isActive) cont.resumeWithException(SpeechException("読み上げに失敗しました"))
                }
            })
            cont.invokeOnCancellation { tts.stop() }
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS) {
                cont.resumeWithException(SpeechException("読み上げを始められませんでした"))
            }
        }
    }

    override fun close() {
        tts.stop()
        tts.shutdown()
    }

    private fun locale(lang: String): Locale = when (lang) {
        "zh-hans" -> Locale.SIMPLIFIED_CHINESE
        "zh-hant" -> Locale.TRADITIONAL_CHINESE
        else -> Locale.forLanguageTag(lang)
    }
}
