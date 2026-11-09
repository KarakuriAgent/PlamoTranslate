package app.plamotranslate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.plamotranslate.models.DownloadState
import app.plamotranslate.models.ModelBundle
import app.plamotranslate.models.ModelDownloads
import app.plamotranslate.models.TranslationModel

/** 設定（2026-11-06、トップ画面から分けた）。翻訳モデルの選択・2Bのダウンロード・今の状態・読み上げ・パフォーマンス・このアプリについて（2026-11-08）。 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { SettingsScreen(onBack = ::finish) } }
    }

    override fun onStop() {
        super.onStop()
        // 試し聞きを、画面を離れても鳴らし続けない
        ReadAloud.stop()
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val model by EngineHolder.state.collectAsStateWithLifecycle()
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("←", fontSize = 22.sp) }
                Text("設定", style = MaterialTheme.typography.titleLarge)
            }
            ModelChooser(model)
            ModelStatus(model, onRetry = { EngineHolder.ensureLoaded(context.applicationContext) })
            SpeechCard()
            PerformanceCard(model)
            val version = remember {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
            }
            AboutCard(version)
        }
    }
}

/**
 * 翻訳モデルの選択（2026-11-06、作者の要望「2Bも選べるようにして、翻訳品質と速度の違いをチェックしたい」）。
 * 2Bが端末に無ければ、ここからダウンロードする。
 */
@Composable
private fun ModelChooser(model: ModelState) {
    val context = LocalContext.current
    val download by ModelDownloads.state.collectAsStateWithLifecycle()
    val failure by ModelDownloads.failure.collectAsStateWithLifecycle()
    val current = (model as? ModelState.Ready)?.model ?: EngineHolder.selected(context)
    var refresh by remember { mutableStateOf(0) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("翻訳モデル", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TranslationModel.entries.forEach { m ->
                    val present = remember(refresh, download) { m.isPresent(context) }
                    FilterChip(
                        selected = current == m,
                        enabled = present && model !is ModelState.Loading,
                        onClick = { EngineHolder.select(context, m) },
                        label = { Text(m.label) },
                    )
                }
            }
            TranslationModel.entries.forEach {
                Text("${it.label}：${it.note}", style = MaterialTheme.typography.bodySmall)
            }
            val large = TranslationModel.LARGE
            val largePresent = remember(refresh, download) { large.isPresent(context) }
            val state = download
            if (state is DownloadState.Running && state.bundle == ModelBundle.LARGE_TRANSLATION) {
                val progress = if (state.total > 0) state.done.toFloat() / state.total else 0f
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${state.done / 1_000_000} / ${state.total / 1_000_000} MB　ダウンロード中はアプリを開いたままにしてください",
                    style = MaterialTheme.typography.labelSmall,
                )
                OutlinedButton(onClick = ModelDownloads::cancel) { Text("中止") }
            } else if (!largePresent) {
                Text(
                    "2Bは使う前に約${large.file.size / 1_000_000}MBをダウンロードします（Wi-Fiでの実行をおすすめします）。",
                    style = MaterialTheme.typography.bodySmall,
                )
                // 一度に取るのは1組だけ。読み上げのモデルを取っている間は押せなくし、理由を出す
                val busy = state is DownloadState.Running
                OutlinedButton(enabled = !busy, onClick = {
                    ModelDownloads.start(context, ModelBundle.LARGE_TRANSLATION, onCompleted = { refresh++ })
                }) { Text("2Bをダウンロード") }
                if (busy) Text("ほかのモデルのダウンロードが終わると押せます", style = MaterialTheme.typography.bodySmall)
            }
            failure?.takeIf { it.bundle == ModelBundle.LARGE_TRANSLATION }?.let {
                Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ModelStatus(model: ModelState, onRetry: () -> Unit) {
    when (model) {
        ModelState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.padding(end = 8.dp))
            Text("モデルを読み込み中…")
        }
        is ModelState.Ready -> Text(
            "今の翻訳モデル：${model.model.label}（${if (model.engine.usesGpu) "GPU" else "CPU"}で動作、" +
                "読込 ${"%.1f".format(model.loadMillis / 1000.0)}秒）",
            style = MaterialTheme.typography.bodyMedium,
        )
        is ModelState.Missing -> Text("翻訳モデルが端末にありません", color = MaterialTheme.colorScheme.error)
        is ModelState.Failed -> Column {
            Text(model.message, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text("読み直す") }
        }
    }
}
