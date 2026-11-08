package app.plamotranslate.tts

/**
 * 読み上げ（2026-11-08、作者の要望。docs/04_会話翻訳.md「読み上げ」）。会話翻訳の確定したカードの訳文と、
 * テキスト翻訳の訳文を、押したときだけ読む。自動では読まない。
 *
 * 実装は差し替えられるようにする：Supertonic 3（[SupertonicSpeaker]、端末内のモデル）と、
 * Androidの読み上げエンジン（[SystemSpeaker]）。
 */
interface Speaker {
    /** [lang]（PLaMoの言語コード。`ja`・`ko`・`zh-hans` など）を読めるか。 */
    fun supports(lang: String): Boolean

    /** [text] を読み終わるまで待つ。呼び出し元のコルーチンを取り消すと、その場で止める。 */
    suspend fun speak(text: String, lang: String)

    fun close()
}

class SpeechException(message: String) : Exception(message)
