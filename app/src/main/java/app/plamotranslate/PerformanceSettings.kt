package app.plamotranslate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.plamotranslate.llm.CpuOptions
import app.plamotranslate.llm.TranslationEngine
import kotlinx.coroutines.launch

/*
 * パフォーマンスの設定（2026-11-08）。15T Pro（MediaTek Dimensity 9400+）で、アプリ内の翻訳の生成が
 * 0.3〜0.8トークン/秒しか出なかった（同じ端末のCLIでは52〜65トークン/秒）。原因の候補を切り替えて試せるようにし、
 * Snapdragon以外の端末の調整にも使えるよう、正式な設定として残す（作者の判断）。docs/03_翻訳コア.md「パフォーマンスの設定」。
 */

private val THREAD_CHOICES = listOf(0, 1, 2, 4, 6, 8)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PerformanceCard(model: ModelState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val libDir = context.applicationInfo.nativeLibraryDir
    val variants = remember { TranslationEngine.cpuVariants(libDir) }
    // 起動時の値。版と「CPUだけ」は起動し直すまで効かないので、変えたら知らせる
    val startVariant = remember { EngineHolder.cpuOptions(context).variant }
    val startCpuOnly = remember { EngineHolder.cpuOnly(context) }
    var options by remember { mutableStateOf(EngineHolder.cpuOptions(context)) }
    var cpuOnly by remember { mutableStateOf(EngineHolder.cpuOnly(context)) }
    var result by remember { mutableStateOf<String?>(null) }
    var measuring by remember { mutableStateOf(false) }
    val busy = model is ModelState.Loading || measuring

    fun apply(next: CpuOptions = options, nextCpuOnly: Boolean = cpuOnly) {
        options = next
        cpuOnly = nextCpuOnly
        result = null
        EngineHolder.saveCpu(context, next, nextCpuOnly)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("パフォーマンス", style = MaterialTheme.typography.titleMedium)
            Text(
                "翻訳が遅いときに、端末に合う組み合わせを探すための設定です。変えるとモデルを読み直します。" +
                    "「速さを測る」で比べてください。",
                style = MaterialTheme.typography.bodySmall,
            )
            val ready = model as? ModelState.Ready
            val backend = remember(ready) { TranslationEngine.cpuBackend() }
            Text(
                "今：${if (ready?.engine?.usesGpu == true) "GPU" else "CPU"}で動作" +
                    (if (backend.isNotEmpty()) "（${backend.removePrefix("libggml-cpu-").removeSuffix(".so")}）" else ""),
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(enabled = !busy && ready != null, onClick = {
                    measuring = true
                    result = null
                    scope.launch {
                        result = runCatching { EngineHolder.bench(context) }.fold(
                            onSuccess = { b ->
                                b?.let {
                                    "最初の文字まで ${it.firstMillis}ms、生成 ${"%.1f".format(it.tokensPerSecond)} トークン/秒" +
                                        "（${it.tokens}トークン、全体 ${it.totalMillis}ms）"
                                } ?: "測れませんでした"
                            },
                            onFailure = { it.message ?: it.toString() },
                        )
                        measuring = false
                    }
                }) { Text(if (measuring) "測っています…" else "速さを測る") }
                TextButton(enabled = !busy, onClick = { apply(CpuOptions(), false) }) { Text("既定に戻す") }
            }
            result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            Text("スレッド数", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                THREAD_CHOICES.forEach { n ->
                    FilterChip(
                        selected = options.threads == n,
                        enabled = !busy,
                        onClick = { apply(options.copy(threads = n)) },
                        label = { Text(if (n == 0) "自動（${TranslationEngine.defaultThreads()}）" else "$n") },
                    )
                }
            }
            SettingSwitch("性能ヒント", "OSにCPUの周波数・配置を上げてもらう（ADPF）", options.boost, !busy) {
                apply(options.copy(boost = it))
            }
            SettingSwitch("回して待つ", "オフにすると、スレッドが仕事を待つ間は眠る", options.spinWait, !busy) {
                apply(options.copy(spinWait = it))
            }
            SettingSwitch("スレッドを常駐させる", "オフにすると、llama.cppが推論のたびにスレッドを用意する", options.threadPool, !busy) {
                apply(options.copy(threadPool = it))
            }
            SettingSwitch("ファイルの写像で読む（mmap）", "オフにすると、モデルをメモリへ全部読み込む", options.mmap, !busy) {
                apply(options.copy(mmap = it))
            }
            SettingSwitch("CPUだけで動かす", "Snapdragonの端末でもGPUを使わない（起動し直すと効く）", cpuOnly, !busy) {
                apply(nextCpuOnly = it)
            }

            if (variants.isNotEmpty()) {
                Text("CPUの計算部分の版（起動し直すと効く）", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = options.variant.isEmpty(),
                        enabled = !busy,
                        onClick = { apply(options.copy(variant = "")) },
                        label = { Text("自動") },
                    )
                    variants.forEach { v ->
                        FilterChip(
                            selected = options.variant == v,
                            enabled = !busy,
                            onClick = { apply(options.copy(variant = v)) },
                            label = { Text(v.removePrefix("libggml-cpu-").removeSuffix(".so")) },
                        )
                    }
                }
            }
            if (options.variant != startVariant || cpuOnly != startCpuOnly) {
                Text(
                    "版・「CPUだけ」の変更は、アプリを起動し直すと効きます。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { restartApp(context) }) { Text("アプリを起動し直す") }
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, note: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

/** トップ画面を開き直してからプロセスを終える。計算部分のライブラリは起動ごとに1回だけ読み込むため。 */
private fun restartApp(context: android.content.Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(intent)
    android.os.Process.killProcess(android.os.Process.myPid())
}
