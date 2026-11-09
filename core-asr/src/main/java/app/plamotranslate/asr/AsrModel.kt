package app.plamotranslate.asr

import java.io.File

/**
 * 選べる音声認識モデルの組（docs/04_会話翻訳.md「音声認識」）。どの組もSenseVoiceを主軸にし、Silero VADで区切り
 * （[SegmentingAsr]）、SenseVoiceの言語タグで話し手の交代を見張る。SenseVoiceが聞けない相手の言語の発話だけ、
 * 補助のモデル（[helper]）で文字にする。
 *
 * 2026-11-09、ドイツ語の会話のためにQwen3-ASRを足し、はじめは全部をQwen3-ASRで認識していた。作者が「ちょっと遅い」と言い、
 * SenseVoiceを主軸にする元の設計を残して、聞けない言語だけ補助に流す形にした（同じ日）。補助には軽いCanary・Parakeetも足した。
 * Qwen3-ASRの組は、保存した選択を読めるよう名前を変えていない。
 */
enum class AsrModel(val helper: HelperModel?) {
    /** SenseVoice Smallだけ。軽く速い。日本語・韓国語・英語・中国語・広東語。 */
    SENSE_VOICE(null),

    /** SenseVoice＋Canary-180M-flash。ドイツ語・スペイン語・フランス語を、相手を固定したときだけ聞く。 */
    CANARY(HelperModel.CANARY),

    /** SenseVoice＋Parakeet TDT 0.6B v3。欧州の言語を、相手を固定したときだけ聞く。 */
    PARAKEET(HelperModel.PARAKEET),

    /** SenseVoice＋Qwen3-ASR-0.6B。30言語。言語を判定するので、自動の相手にもなる。重い。 */
    QWEN3_ASR(HelperModel.QWEN3_ASR),
    ;

    /**
     * 自分の言語にできる言語。どの組でもSenseVoiceの言語だけ。自分の発話かどうかをSenseVoiceの言語タグで見分けるので、
     * SenseVoiceが判定できない言語（ドイツ語など）を自分にすると、自分の発話と相手の発話を分けられない。
     */
    val selfLanguages: Set<String> get() = SenseVoiceTranscriber.LANGUAGES

    /** 聞ける言語（固定の相手にできる言語）。会話で選べる言語はこれとPLaMoの言語の共通部分になる。 */
    val languages: Set<String> get() = SenseVoiceTranscriber.LANGUAGES + helper?.languages.orEmpty()

    /** 自動の相手として聞ける言語。補助のモデルが言語を判定できなければ（Canary・Parakeet）、SenseVoiceの言語だけ。 */
    val autoLanguages: Set<String>
        get() = if (helper?.detectsLanguage == true) languages else SenseVoiceTranscriber.LANGUAGES

    /** [modelDir] に置くファイル（SenseVoice・VAD・補助のモデル）。 */
    private val files: List<String>
        get() = SenseVoiceTranscriber.FILES + SileroVad.FILE + helper?.files.orEmpty()

    /** モデルが揃っていなければ、足りないファイルのパスを返す。 */
    fun missingFiles(modelDir: File): List<String> =
        files.map { File(modelDir, it) }.filterNot { it.canRead() }.map { it.path }

    /**
     * モデルを読み込む。数秒かかるので、画面を開いたときやマイクを初めて押したときに1回だけ呼ぶ。
     * 補助のモデルは、[languages] の会話で要るときだけ読み込む（要らなければ、後で要るようになるまで読み込まない）。
     */
    fun load(modelDir: File, languages: SpeakerLanguages = SpeakerLanguages()): AsrEngine {
        val missing = missingFiles(modelDir)
        require(missing.isEmpty()) { "音声認識のモデルがありません: ${missing.joinToString()}" }
        val vad = SileroVad.load(modelDir)
        val senseVoice = runCatching { SenseVoiceTranscriber.load(modelDir) }.getOrElse { e ->
            vad.close()
            throw e
        }
        val engine = SegmentingAsr(vad, senseVoice, helper) { helper?.load(modelDir) ?: error("補助のモデルがありません") }
        engine.languages = languages
        runCatching { engine.prepare() }.onFailure { e ->
            engine.close()
            throw e
        }
        return engine
    }
}

/**
 * 補助のモデル（2026-11-09）。SenseVoiceが聞けない相手の言語の発話だけを文字にする。モデルごとの違いはこの表に持つ。
 * [languages] は聞ける言語のコード。[detectsLanguage] は言語を判定できるか（できなければ、相手を固定したときだけ使い、
 * 言語は固定した相手の言語とする）。
 */
enum class HelperModel(val languages: Set<String>, val detectsLanguage: Boolean) {
    /** NVIDIA Canary-180M-flash（int8、約207MB）。英語・ドイツ語・スペイン語・フランス語。言語は指定して認識する。 */
    CANARY(NemoTranscriber.CANARY_LANGUAGES, detectsLanguage = false),

    /** NVIDIA Parakeet TDT 0.6B v3（int8、約670MB）。欧州の25言語。言語は指定しない（自動で聞き分けて文字にする）。 */
    PARAKEET(NemoTranscriber.PARAKEET_LANGUAGES, detectsLanguage = false),

    /** Qwen3-ASR-0.6B（int8、約990MB）。30言語。言語を判定できる。 */
    QWEN3_ASR(Qwen3AsrTranscriber.LANGUAGES.keys, detectsLanguage = true),
    ;

    internal val files: List<String>
        get() = when (this) {
            CANARY -> NemoTranscriber.CANARY_FILES
            PARAKEET -> NemoTranscriber.PARAKEET_FILES
            QWEN3_ASR -> Qwen3AsrTranscriber.FILES
        }

    internal fun load(modelDir: File): Transcriber = when (this) {
        CANARY -> NemoTranscriber.loadCanary(modelDir)
        PARAKEET -> NemoTranscriber.loadParakeet(modelDir)
        QWEN3_ASR -> Qwen3AsrTranscriber.load(modelDir)
    }
}
