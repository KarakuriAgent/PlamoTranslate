package app.plamotranslate

import android.content.Context
import app.plamotranslate.llm.CpuOptions
import app.plamotranslate.models.TranslationModel

/**
 * 翻訳モデルの設定（使うモデル・CPUの詳細）。端末に残し、次に起動したときも同じものを使う。
 * 設定の画面が書き、[EngineHolder] が読み込むときに読む（docs/11「1」、2026-11-09に保存だけを切り出した）。
 */
class TranslationPreferences(context: Context) {
    private val translation = context.getSharedPreferences("translation", Context.MODE_PRIVATE)
    private val cpu = context.getSharedPreferences("cpu", Context.MODE_PRIVATE)

    /** 選んだ翻訳モデル。読めなければ0.8B。ファイルがあるかは見ない（[EngineHolder.selected] が見る）。 */
    var model: TranslationModel
        get() = translation.getString(KEY_MODEL, null)
            ?.let { name -> TranslationModel.entries.firstOrNull { it.name == name } }
            ?: TranslationModel.SMALL
        set(value) = translation.edit().putString(KEY_MODEL, value.name).apply()

    /** CPUの詳細設定（2026-11-08、15T Proで翻訳が極端に遅かったため。docs/03_翻訳コア.md「CPUの詳細設定」）。 */
    val cpuOptions: CpuOptions
        get() {
            val d = CpuOptions()
            return CpuOptions(
                threads = cpu.getInt("threads", d.threads),
                boost = cpu.getBoolean("boost", d.boost),
                spinWait = cpu.getBoolean("spinWait", d.spinWait),
                threadPool = cpu.getBoolean("threadPool", d.threadPool),
                mmap = cpu.getBoolean("mmap", d.mmap),
                variant = cpu.getString("variant", d.variant) ?: "",
            )
        }

    /** Snapdragonの端末でもGPUを使わず、CPUだけで動かすか（CPUの速さを確かめるため）。 */
    val cpuOnly: Boolean get() = cpu.getBoolean("cpuOnly", false)

    /**
     * CPUの詳細設定を保存する。効かせるには翻訳モデルを読み直す（[EngineHolder.reload]）。
     * 計算部分の版（[CpuOptions.variant]）と「CPUだけ」は、計算部分のライブラリを読み込むのがアプリの起動ごとに
     * 1回だけなので、アプリを起動し直すと効く。
     */
    fun saveCpu(options: CpuOptions, cpuOnly: Boolean) {
        cpu.edit()
            .putInt("threads", options.threads)
            .putBoolean("boost", options.boost)
            .putBoolean("spinWait", options.spinWait)
            .putBoolean("threadPool", options.threadPool)
            .putBoolean("mmap", options.mmap)
            .putString("variant", options.variant)
            .putBoolean("cpuOnly", cpuOnly)
            .apply()
    }

    private companion object {
        const val KEY_MODEL = "model"
    }
}
