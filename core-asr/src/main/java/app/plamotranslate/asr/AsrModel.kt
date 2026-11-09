package app.plamotranslate.asr

import java.io.File

/**
 * 選べる音声認識モデル（2026-11-09、ドイツ語の会話のためにQwen3-ASRを足した。docs/04_会話翻訳.md「音声認識」）。
 * どれもSilero VADで区切り（[SegmentingAsr]）、モデルごとの認識器（[Transcriber]）で文字にする。
 * [languages] はモデルが聞き分けられる言語のコード。会話で選べる言語はこれとPLaMoの言語の共通部分になる。
 */
enum class AsrModel(val languages: Set<String>) {
    /** SenseVoice Small。軽く速い。日本語・韓国語・英語・中国語・広東語。 */
    SENSE_VOICE(SenseVoiceTranscriber.LANGUAGES),

    /** Qwen3-ASR-0.6B。30言語（ドイツ語・フランス語・スペイン語など）。重い。 */
    QWEN3_ASR(Qwen3AsrTranscriber.LANGUAGES.keys),
    ;

    /** [modelDir] に置くファイル（VADを含む）。 */
    private val files: List<String>
        get() = when (this) {
            SENSE_VOICE -> SenseVoiceTranscriber.FILES
            QWEN3_ASR -> Qwen3AsrTranscriber.FILES
        } + SileroVad.FILE

    /** モデルが揃っていなければ、足りないファイルのパスを返す。 */
    fun missingFiles(modelDir: File): List<String> =
        files.map { File(modelDir, it) }.filterNot { it.canRead() }.map { it.path }

    /** モデルを読み込む。数秒かかるので、画面を開いたときやマイクを初めて押したときに1回だけ呼ぶ。 */
    fun load(modelDir: File): AsrEngine {
        val missing = missingFiles(modelDir)
        require(missing.isEmpty()) { "音声認識のモデルがありません: ${missing.joinToString()}" }
        val vad = SileroVad.load(modelDir)
        val transcriber = runCatching {
            when (this) {
                SENSE_VOICE -> SenseVoiceTranscriber.load(modelDir)
                QWEN3_ASR -> Qwen3AsrTranscriber.load(modelDir)
            }
        }.getOrElse { e ->
            vad.close()
            throw e
        }
        return SegmentingAsr(vad, transcriber)
    }
}
