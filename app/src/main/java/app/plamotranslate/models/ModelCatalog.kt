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
     * 読み上げのモデル（Supertonic 3、int8、約145MB）。設定の画面で「読み上げ」をSupertonic 3にしたときだけ取る
     * （2026-11-08、作者の要望）。sherpa-onnx向けに変換したもの。モデルのライセンスはOpenRAIL-M（用途の制限つき）。
     */
    private const val SUPERTONIC = "$HF/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/resolve/cca5a0e6c96e1d2c720986bf7e75fcc81dee3ae4"
    const val SPEECH_DIR = "tts/supertonic-3"
    val speech = listOf(
        Triple("duration_predictor.int8.onnx", 3_700_147L, "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db"),
        Triple("text_encoder.int8.onnx", 36_416_150L, "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff"),
        Triple("vector_estimator.int8.onnx", 78_400_833L, "20cd86fa5c6effedfda0e7cffe5b0569ca401c440a0c3a1d72bf39286c0db3fd"),
        Triple("vocoder.int8.onnx", 25_991_073L, "e923d60f53f95eb1ce235f1dc33ec56d9c057823c96fa6f8acf98f32b0da6152"),
        Triple("tts.json", 8_253L, "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09"),
        Triple("unicode_indexer.bin", 262_144L, "8402ca48e5189a8950138580b0fff64db6f072f24ac07cd54ba8b2fbb9883b30"),
        Triple("voice.bin", 517_168L, "67d5209b0ee8ce6c74105ffbe12fe6a7628aea3b4ba2fcb308a4a67938a93ce8"),
    ).map { (name, size, sha) ->
        ModelFile(label = "読み上げ（Supertonic 3）$name", url = "$SUPERTONIC/$name", path = "$SPEECH_DIR/$name", size = size, sha256 = sha)
    }

    fun speechDir(context: Context): File = File(modelsDir(context), SPEECH_DIR)

    fun speechPresent(context: Context): Boolean = speech.all { it.file(context).length() == it.size }

    fun modelsDir(context: Context): File = File(context.getExternalFilesDir(null), "models")

    /**
     * まだ置かれていないファイル。大きさだけで見る（毎回ハッシュを計算すると起動が遅くなる）。
     * ダウンロードしたものは置く前にハッシュを照合している。
     */
    fun missing(context: Context): List<ModelFile> = files.filter { it.file(context).length() != it.size }

    val totalSize: Long get() = files.sumOf { it.size }
}
