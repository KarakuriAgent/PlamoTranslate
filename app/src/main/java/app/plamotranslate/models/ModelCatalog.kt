package app.plamotranslate.models

import android.content.Context
import java.io.File

/**
 * アプリが使うモデルのファイル。初回起動時にダウンロードして、外部ファイル置き場の `models/` に置く
 * （2026-11-06、APKを配布するため。それまではPCからadbで置いていた。docs/06_開発環境.md）。
 *
 * [sha256] と [size] は、ダウンロード元から取って確かめた値。ダウンロード後に照合し、違えば使わない。
 *
 * Hugging FaceのURLは `resolve/main/` ではなく、コミットを指定する（2026-11-08）。配布元は公開後もファイルを
 * 差し替える。pfnetは11-06〜11-08にGGUFのチャットテンプレートを4回更新し、そのたびに大きさとハッシュが変わった。
 * 漫画用OCRは置き場所を変え、mainの旧パスは404になった。mainを指すと、照合が合わずにダウンロードが必ず失敗する。
 * 新しい版に上げるときは、内容を確かめてから、コミット・大きさ・ハッシュをまとめて書き換える（docs/06「モデルの版」）。
 */
data class ModelFile(
    /** 画面に出す名前。 */
    val label: String,
    val url: String,
    /** `models/` からの置き場所。 */
    val path: String,
    val size: Long,
    val sha256: String,
) {
    fun file(context: Context): File = File(ModelCatalog.modelsDir(context), path)
}

/**
 * 翻訳モデルの大きさ。既定は0.8Bで、2Bは作者が選んだときだけ追加でダウンロードする
 * （2026-11-06、作者の要望「2Bも選べるようにして、翻訳品質と速度の違いをチェックしたい」）。
 * 量子化はどちらもQ4_K_Mにそろえ、比べるときに大きさの違いだけが効くようにする。
 */
enum class TranslationModel(val label: String, val note: String, val file: ModelFile) {
    SMALL(
        "0.8B",
        "速い。慣用句や韓国語の口語で意味を取り違えやすい",
        ModelFile(
            label = "翻訳モデル（PLaMo 3 Translate 0.8B）",
            url = "https://huggingface.co/pfnet/plamo-3-translate-0.8b-GGUF/resolve/e10c327b7a7ce84ffc427cb4b09360b781429fda/plamo-3-translate-0.8b-Q4_K_M.gguf",
            path = "llm/plamo-3-translate-0.8b-Q4_K_M.gguf",
            size = 573_126_848,
            sha256 = "70891ec70dc7ab4205745a38307867c2881b99239f20327927633abfe4bc9ec6",
        ),
    ),
    LARGE(
        "2B",
        "訳が正確。0.8Bの約2.5倍遅い（短い文で0.3〜1.4秒）",
        ModelFile(
            label = "翻訳モデル（PLaMo 3 Translate 2B）",
            url = "https://huggingface.co/pfnet/plamo-3-translate-2b-GGUF/resolve/d44589ecd5287cd64452e9cd1e2cfdad904fe216/plamo-3-translate-2b-Q4_K_M.gguf",
            path = "llm/plamo-3-translate-2b-Q4_K_M.gguf",
            size = 1_807_202_144,
            sha256 = "2189a8a0b4b359f59443ec36cfb9c9c3f603a678c00dbcee8598b3f7ea7c12fb",
        ),
    ),
    ;

    fun isPresent(context: Context): Boolean = file.file(context).length() == file.size
}

/**
 * 一緒にダウンロードするモデルの組（docs/11「4」、2026-11-09）。初回にそろえる組と、設定の画面で選んだときに足す組がある。
 * 一度に取るのは1組だけ（[ModelDownloads]）。画面は、取っている組・失敗した組をこの型で見分ける。
 */
enum class ModelBundle {
    /** 初回起動時にそろえる（翻訳0.8B・音声認識・漫画用OCR）。そろうまで「モデルの準備」の画面を出す。 */
    REQUIRED,

    /** 翻訳モデル2B。設定の画面で選んだときに足す。 */
    LARGE_TRANSLATION,

    /** 読み上げのモデル（Supertonic 3）。設定の画面で「読み上げ」をSupertonic 3にしたときに足す。 */
    SPEECH,

    /** 音声認識の補助のモデル。設定の画面の「音声認識モデル」から足す（2026-11-09）。 */
    CANARY,
    PARAKEET,
    QWEN3_ASR,
    ;

    val files: List<ModelFile>
        get() = when (this) {
            REQUIRED -> ModelCatalog.files
            LARGE_TRANSLATION -> listOf(TranslationModel.LARGE.file)
            SPEECH -> ModelCatalog.speech
            CANARY -> ModelCatalog.canary
            PARAKEET -> ModelCatalog.parakeet
            QWEN3_ASR -> ModelCatalog.qwen3Asr
        }

    /**
     * まだ置かれていないファイル。大きさだけで見る（毎回ハッシュを計算すると起動が遅くなる）。
     * ダウンロードしたものは置く前にハッシュを照合している。
     */
    fun missing(context: Context): List<ModelFile> = files.filter { it.file(context).length() != it.size }
}

object ModelCatalog {
    private const val HF = "https://huggingface.co"
    private const val SENSE_VOICE = "$HF/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07"
    private const val MANGA = "$HF/Kellenok/PP-OCRv6_manga/resolve/de0e793da4e213f4b13c738204fe2a832f16d57c"

    /** 初回起動時にそろえるファイル。翻訳モデルは0.8Bだけで、2Bは選んだときに足す。 */
    val files = listOf(
        TranslationModel.SMALL.file,
        ModelFile(
            label = "音声認識（SenseVoice Small）",
            url = "$SENSE_VOICE/model.int8.onnx",
            path = "asr/sense-voice/model.int8.onnx",
            size = 239_233_841,
            sha256 = "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
        ),
        ModelFile(
            label = "音声認識の文字の表",
            url = "$SENSE_VOICE/tokens.txt",
            path = "asr/sense-voice/tokens.txt",
            size = 315_894,
            sha256 = "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
        ),
        ModelFile(
            label = "話しているかの判定（Silero VAD）",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            path = "asr/silero_vad.onnx",
            size = 643_854,
            sha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
        ),
        ModelFile(
            label = "漫画の文字の検出（PP-OCRv6 manga）",
            url = "$MANGA/det/manga_det_v0.2.onnx",
            path = "ocr/manga/det.onnx",
            size = 1_816_954,
            sha256 = "d132078c46e292b226fb5a2ca52a7612ad319262dfdf493e7d8e3be435295978",
        ),
        ModelFile(
            label = "漫画の文字の認識（PP-OCRv6 manga）",
            url = "$MANGA/rec/manga_rec_v0.2.onnx",
            path = "ocr/manga/rec.onnx",
            size = 21_167_540,
            sha256 = "de12c84c63e62c80339e882e675983d886670dcb6f0147e1ed041afd6fa81888",
        ),
        ModelFile(
            label = "漫画の文字の表",
            url = "$MANGA/ppocrv6_dict.txt",
            path = "ocr/manga/dict.txt",
            size = 74_947,
            sha256 = "b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d",
        ),
    )

    /**
     * 読み上げのモデル（Supertonic 3、約220MB）。設定の画面で「読み上げ」をSupertonic 3にしたときだけ取る
     * （2026-11-08、作者の要望）。sherpa-onnx向けのint8版を使い、vocoderだけ公式のfp32版にする。
     * int8のvocoderは話している最中にザラつきが乗り、段数を増やしても消えなかったため（作者が聞いて指摘、docs/04「読み上げ」）。
     * sherpa-onnxのint8版のonnxは公式版をそのまま量子化したもので、公式のvocoderと差し替えて動く。
     * モデルのライセンスはOpenRAIL-M（用途の制限つき）。
     */
    private const val SUPERTONIC = "$HF/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/resolve/cca5a0e6c96e1d2c720986bf7e75fcc81dee3ae4"
    private const val SUPERTONIC_OFFICIAL = "$HF/Supertone/supertonic-3/resolve/3cadd1ee6394adea1bd021217a0e650ede09a323/onnx"
    const val SPEECH_DIR = "tts/supertonic-3"
    val speech = listOf(
        speechFile(SUPERTONIC, "duration_predictor.int8.onnx", 3_700_147L, "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db"),
        speechFile(SUPERTONIC, "text_encoder.int8.onnx", 36_416_150L, "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff"),
        speechFile(SUPERTONIC, "vector_estimator.int8.onnx", 78_400_833L, "20cd86fa5c6effedfda0e7cffe5b0569ca401c440a0c3a1d72bf39286c0db3fd"),
        speechFile(SUPERTONIC_OFFICIAL, "vocoder.onnx", 101_424_195L, "085de76dd8e8d5836d6ca66826601f615939218f90e519f70ee8a36ed2a4c4ba"),
        speechFile(SUPERTONIC, "tts.json", 8_253L, "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09"),
        speechFile(SUPERTONIC, "unicode_indexer.bin", 262_144L, "8402ca48e5189a8950138580b0fff64db6f072f24ac07cd54ba8b2fbb9883b30"),
        speechFile(SUPERTONIC, "voice.bin", 517_168L, "67d5209b0ee8ce6c74105ffbe12fe6a7628aea3b4ba2fcb308a4a67938a93ce8"),
    )

    private fun speechFile(base: String, name: String, size: Long, sha256: String) =
        ModelFile(label = "読み上げ（Supertonic 3）$name", url = "$base/$name", path = "$SPEECH_DIR/$name", size = size, sha256 = sha256)

    /*
     * 音声認識の補助のモデル（2026-11-09）。設定の画面で選んだときだけ取る。SenseVoiceが聞けない相手の言語
     * （ドイツ語など）の発話だけを文字にする。どれもsherpa-onnxの作者が書き出したint8版で、VADはSenseVoiceと同じ
     * `asr/silero_vad.onnx` を使う。置き場所は `core-asr` の NemoTranscriber・Qwen3AsrTranscriber の FILES と合わせる。
     */

    /** Canary-180M-flash（約207MB）。元のモデルは `nvidia/canary-180m-flash`（CC-BY-4.0）。 */
    private const val CANARY = "$HF/csukuangfj/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8/resolve/9077164e0d3dd1d5353743e89ceaa1d3a770838c"
    val canary = listOf(
        asrFile("Canary", CANARY, "canary-180m-flash", "encoder.int8.onnx", 132_678_643L, "7a75b4e2a5857a6dcc0819503bbe3fad66943db4a3ccf21d3f27c633667d303f"),
        asrFile("Canary", CANARY, "canary-180m-flash", "decoder.int8.onnx", 74_437_848L, "e41a2ab9c0c2fe81a1e8ade5a45fb02a74bc4db7d1f91b89a54a25e2cf79cba2"),
        asrFile("Canary", CANARY, "canary-180m-flash", "tokens.txt", 53_555L, "2dae6fc7815f9640645e0c765522b278ee0cef49b482d91f6913e334628d3e77"),
    )

    /** Parakeet TDT 0.6B v3（約670MB）。元のモデルは `nvidia/parakeet-tdt-0.6b-v3`（CC-BY-4.0）。 */
    private const val PARAKEET = "$HF/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/resolve/ed5d745623472f97cfc2e6c2916ee248798fc5a8"
    val parakeet = listOf(
        asrFile("Parakeet", PARAKEET, "parakeet-tdt-0.6b-v3", "encoder.int8.onnx", 652_282_294L, "1afbe69c1ffdabee94755f42e4daf1d93549131a3da11356bda84fcef0f2c479"),
        asrFile("Parakeet", PARAKEET, "parakeet-tdt-0.6b-v3", "decoder.int8.onnx", 11_845_274L, "0222a86147299d4dee90edf95b6d13827edb42ad8b7c9cf0b2582f030937c0b5"),
        asrFile("Parakeet", PARAKEET, "parakeet-tdt-0.6b-v3", "joiner.int8.onnx", 6_355_277L, "cbd02f3696e9944970111fb439d9c3fd365a80374565f13f2b9c7007d253fc98"),
        asrFile("Parakeet", PARAKEET, "parakeet-tdt-0.6b-v3", "tokens.txt", 93_939L, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
    )

    /**
     * Qwen3-ASR-0.6B（約990MB）。2026-03-25版（sherpa-onnxのKotlin APIの例が指す版）。
     * 元のモデルは `Qwen/Qwen3-ASR-0.6B`（Apache-2.0）。
     */
    private const val QWEN3_ASR = "$HF/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/68818b2313fe77bd06f6a7c5068ff3ef59d02b8a"
    val qwen3Asr = listOf(
        asrFile("Qwen3-ASR", QWEN3_ASR, "qwen3-asr", "conv_frontend.onnx", 44_148_281L, "d22dc4423e0940e49884e903d2ea2f7e5567c14fc1aed97e4e26d6b8f208ef9e"),
        asrFile("Qwen3-ASR", QWEN3_ASR, "qwen3-asr", "encoder.int8.onnx", 182_491_662L, "60748d3e6744a57c9c91e1b17424a6c2990567e8adceb0783940c03ed98fa9d9"),
        asrFile("Qwen3-ASR", QWEN3_ASR, "qwen3-asr", "decoder.int8.onnx", 755_914_231L, "4f6885be5959ae26af3089d38ee7972c5fafbeeb1cf8d5e76eab6d8b61ca5771"),
        asrFile("Qwen3-ASR", QWEN3_ASR, "qwen3-asr", "tokenizer/vocab.json", 2_776_833L, "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910"),
        asrFile("Qwen3-ASR", QWEN3_ASR, "qwen3-asr", "tokenizer/merges.txt", 1_671_853L, "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5"),
        asrFile("Qwen3-ASR", QWEN3_ASR, "qwen3-asr", "tokenizer/tokenizer_config.json", 12_487L, "4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c"),
    )

    /** 補助のモデルの1ファイル。[base] の [name] を `asr/[dir]/[name]` に置く。 */
    private fun asrFile(model: String, base: String, dir: String, name: String, size: Long, sha256: String) =
        ModelFile(label = "音声認識（$model）$name", url = "$base/$name", path = "asr/$dir/$name", size = size, sha256 = sha256)

    fun speechDir(context: Context): File = File(modelsDir(context), SPEECH_DIR)

    fun speechPresent(context: Context): Boolean = ModelBundle.SPEECH.missing(context).isEmpty()

    fun modelsDir(context: Context): File = File(context.getExternalFilesDir(null), "models")

    /** 音声認識（SenseVoice・補助のモデル・Silero VAD）の置き場。会話翻訳とテキスト翻訳の音声入力が使う。 */
    fun asrDir(context: Context): File = File(modelsDir(context), "asr")

    /** 漫画用OCRの置き場。無ければML Kitだけで動く。 */
    fun mangaDir(context: Context): File = File(modelsDir(context), "ocr/manga")

    val totalSize: Long get() = files.sumOf { it.size }
}
