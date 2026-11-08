package app.plamotranslate

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import app.plamotranslate.llm.JapaneseForm
import app.plamotranslate.llm.Language
import app.plamotranslate.llm.TranslationEngine
import app.plamotranslate.llm.TranslationEvent
import app.plamotranslate.llm.TranslationRequest
import app.plamotranslate.models.TranslationModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 翻訳モデル（0.8B・2B）の比べ（デバッグ版だけ。2026-11-06、作者の要望「翻訳品質と速度の違いをチェックしたい」）。
 *
 * 外部ファイル置き場の `translate-bench.tsv`（1行に「原文の言語 TAB 訳先の言語 TAB 原文」）を、
 * intent の `model`（SMALL・LARGE）で選んだモデルで1文ずつ訳し、時間と訳文を logcat と
 * `translate-bench-<model>.tsv` に書く。アプリの翻訳と同じ読み込み方（GPU優先）で、ウォームアップを1回してから測る。
 *
 * adb shell am start -S -n app.plamotranslate/.TranslateBenchActivity --es model LARGE
 */
class TranslateBenchActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply { textSize = 14f; setPadding(32, 32, 32, 32) }
        setContentView(view)
        val model = TranslationModel.entries.firstOrNull { it.name == intent.getStringExtra("model") } ?: TranslationModel.SMALL
        val set = intent.getStringExtra("set") ?: "translate-bench"
        val context = intent.getStringExtra("context")
        scope.launch {
            val report = runCatching { withContext(Dispatchers.Default) { run(model, set, context) } }
                .getOrElse { "失敗: ${it.message}" }
            view.text = report
            log("終わり")
        }
    }

    private suspend fun run(model: TranslationModel, set: String, context: String?): String {
        val dir = getExternalFilesDir(null)!!
        val input = File(dir, "$set.tsv")
        require(input.exists()) { "原文の一覧がありません: ${input.path}" }
        val rows = input.readLines(Charsets.UTF_8).filter { it.isNotBlank() && !it.startsWith("#") }.map { it.split('\t') }
        // アプリのエンジンと同時に読み込まないよう、アプリ側のエンジンは使わずに単独で読み込む
        val clCache = File(cacheDir, "cl-cache").apply { mkdirs() }
        android.system.Os.setenv("GGML_OPENCL_KERNEL_CACHE_DIR", clCache.path, true)
        val loadStart = System.nanoTime()
        val engine = TranslationEngine.load(model.file.file(this), applicationInfo.nativeLibraryDir, preferGpu = true)
        val loadMs = (System.nanoTime() - loadStart) / 1_000_000
        engine.translate(TranslationRequest("안녕하세요", Language.KO, Language.JA), maxTokens = 8).collect { }
        val out = StringBuilder("model\tsrc\ttgt\tms\ttokens\ttok_per_s\tsource\ttranslation\n")
        log("${model.label}: 読込 ${loadMs}ms（${if (engine.usesGpu) "GPU" else "CPU"}）")
        var totalMs = 0L
        var totalTokens = 0
        for (row in rows) {
            val src = Language.entries.first { it.code.startsWith(row[0]) }
            val tgt = Language.entries.first { it.code.startsWith(row[1]) }
            // 段落のある長文は、TSVの1行に「\n」と書いて改行を表す（アプリのテキスト翻訳へ貼ったときと同じ入力にする）
            val text = row[2].replace("\\n", "\n")
            val sb = StringBuilder()
            var tokens = 0
            var ms = 0L
            engine.translate(TranslationRequest(text, src, tgt, JapaneseForm.POLITE, context = context)).collect { e ->
                when (e) {
                    is TranslationEvent.Delta -> sb.append(e.text)
                    is TranslationEvent.Done -> { tokens = e.tokens; ms = e.millis }
                }
            }
            totalMs += ms
            totalTokens += tokens
            val translation = sb.toString().trim().replace("\n", "\\n")
            val tps = if (ms > 0) tokens * 1000.0 / ms else 0.0
            log("${model.label} ${row[0]}→${row[1]} ${ms}ms ${tokens}tok ${"%.0f".format(tps)}tok/s | ${row[2].take(40)} → ${translation.take(80)}")
            out.append("${model.label}\t${row[0]}\t${row[1]}\t$ms\t$tokens\t${"%.1f".format(tps)}\t${row[2]}\t$translation\n")
        }
        engine.close()
        val summary = "${model.label}: 読込 ${loadMs}ms、${rows.size}文で翻訳 合計${totalMs}ms・${totalTokens}トークン" +
            "（${"%.0f".format(totalTokens * 1000.0 / totalMs.coerceAtLeast(1))}トークン/秒）"
        log(summary)
        File(dir, "$set-${model.name}${if (context != null) "-ctx" else ""}.tsv").writeText(out.toString(), Charsets.UTF_8)
        return summary
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun log(message: String) = Log.i("PlamoTranslate", "訳比較: $message")
}
