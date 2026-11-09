package app.plamotranslate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.plamotranslate.asr.AsrModel
import app.plamotranslate.conversation.ConversationLanguages
import app.plamotranslate.conversation.conversationLabel
import app.plamotranslate.models.DownloadState
import app.plamotranslate.models.ModelBundle
import app.plamotranslate.models.ModelDownloads

/**
 * 音声認識モデルの選択（2026-11-09、ドイツ語の会話のためにQwen3-ASRを足した。docs/04_会話翻訳.md「音声認識」）。
 * 会話翻訳とテキスト翻訳の音声入力が使う。Qwen3-ASRは、翻訳の2Bと同じく、ここから追加でダウンロードしてから選べる。
 */
@Composable
fun AsrCard() {
    val context = LocalContext.current
    val download by ModelDownloads.state.collectAsStateWithLifecycle()
    val failure by ModelDownloads.failure.collectAsStateWithLifecycle()
    val preferences = remember { AsrPreferences(context) }
    var refresh by remember { mutableStateOf(0) }
    var current by remember { mutableStateOf(preferences.selected()) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("音声認識モデル", style = MaterialTheme.typography.titleMedium)
            Text(
                "会話翻訳とテキスト翻訳の音声入力で使います。会話翻訳で選べる言語は、このモデルが聞き取れる言語で決まります。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AsrModel.entries.forEach { m ->
                    val present = remember(refresh, download) { m.bundle?.missing(context)?.isEmpty() ?: true }
                    FilterChip(
                        selected = current == m,
                        enabled = present,
                        onClick = {
                            preferences.model = m
                            current = preferences.selected()
                        },
                        label = { Text(m.label) },
                    )
                }
            }
            AsrModel.entries.forEach {
                Text("${it.label}：${it.note}", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                "会話で使える言語：${ConversationLanguages.of(current).all.joinToString("・") { it.conversationLabel }}",
                style = MaterialTheme.typography.bodySmall,
            )

            val qwen = AsrModel.QWEN3_ASR
            val bundle = ModelBundle.QWEN3_ASR
            val qwenPresent = remember(refresh, download) { bundle.missing(context).isEmpty() }
            val state = download
            if (state is DownloadState.Running && state.bundle == bundle) {
                val progress = if (state.total > 0) state.done.toFloat() / state.total else 0f
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${state.done / 1_000_000} / ${state.total / 1_000_000} MB　ダウンロード中はアプリを開いたままにしてください",
                    style = MaterialTheme.typography.labelSmall,
                )
                OutlinedButton(onClick = ModelDownloads::cancel) { Text("中止") }
            } else if (!qwenPresent) {
                Text(
                    "${qwen.label}は使う前に約${bundle.files.sumOf { it.size } / 1_000_000}MBをダウンロードします（Wi-Fiでの実行をおすすめします）。" +
                        "ダウンロードするまではSenseVoiceを使います。",
                    style = MaterialTheme.typography.bodySmall,
                )
                // 一度に取るのは1組だけ。ほかのモデルを取っている間は押せなくし、理由を出す
                val busy = state is DownloadState.Running
                OutlinedButton(enabled = !busy, onClick = {
                    ModelDownloads.start(context, bundle, onCompleted = { refresh++ })
                }) { Text("${qwen.label}をダウンロード") }
                if (busy) Text("ほかのモデルのダウンロードが終わると押せます", style = MaterialTheme.typography.bodySmall)
            }
            failure?.takeIf { it.bundle == bundle }?.let {
                Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
