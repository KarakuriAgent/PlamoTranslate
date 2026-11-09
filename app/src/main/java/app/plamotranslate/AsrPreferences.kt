package app.plamotranslate

import android.content.Context
import app.plamotranslate.asr.AsrEngine
import app.plamotranslate.asr.AsrModel
import app.plamotranslate.models.ModelBundle
import app.plamotranslate.models.ModelCatalog

/** 設定の画面に出す名前。 */
val AsrModel.label: String
    get() = when (this) {
        AsrModel.SENSE_VOICE -> "SenseVoice"
        AsrModel.QWEN3_ASR -> "Qwen3-ASR"
    }

/** 設定の画面に出す説明。 */
val AsrModel.note: String
    get() = when (this) {
        AsrModel.SENSE_VOICE -> "軽く速い。日本語・韓国語・英語・中国語・広東語を聞き取る"
        AsrModel.QWEN3_ASR -> "ドイツ語・フランス語・スペイン語・ロシア語なども聞き取る。SenseVoiceより重く、聞き取りの表示が遅れる"
    }

/** 設定の画面から追加でダウンロードする組。SenseVoiceは初回にそろえるので null。 */
val AsrModel.bundle: ModelBundle?
    get() = when (this) {
        AsrModel.SENSE_VOICE -> null
        AsrModel.QWEN3_ASR -> ModelBundle.QWEN3_ASR
    }

/**
 * 音声認識モデルの選択（2026-11-09、ドイツ語の会話のためにQwen3-ASRを選べるようにした。docs/04_会話翻訳.md「音声認識」）。
 * 設定の画面が書き、会話翻訳とテキスト翻訳の音声入力が読み込むときに読む。音声認識のエンジンはここでだけ作る。
 */
class AsrPreferences(private val context: Context) {
    private val prefs = context.getSharedPreferences("asr", Context.MODE_PRIVATE)

    /** 選んだモデル。読めなければSenseVoice。ファイルがあるかは見ない（[selected] が見る）。 */
    var model: AsrModel
        get() = prefs.getString(KEY_MODEL, null)?.let { name -> AsrModel.entries.firstOrNull { it.name == name } }
            ?: AsrModel.SENSE_VOICE
        set(value) = prefs.edit().putString(KEY_MODEL, value.name).apply()

    /**
     * 使うモデル。選んだモデルのファイルがそろっていなければ（まだダウンロードしていない・消えた）SenseVoiceを使う。
     * 翻訳モデルの2Bと同じく、ダウンロードが済むまでは選べないので、普通はここで替わることはない。
     */
    fun selected(): AsrModel {
        val chosen = model
        return if (chosen.missingFiles(dir).isEmpty()) chosen else AsrModel.SENSE_VOICE
    }

    /** [model] に足りないファイル。 */
    fun missingFiles(model: AsrModel): List<String> = model.missingFiles(dir)

    /** [model] を読み込む。数秒かかるので、音声認識のスレッドで呼ぶ。 */
    fun load(model: AsrModel): AsrEngine = model.load(dir)

    private val dir get() = ModelCatalog.asrDir(context)

    private companion object {
        const val KEY_MODEL = "model"
    }
}
