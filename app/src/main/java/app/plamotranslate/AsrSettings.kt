package app.plamotranslate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import app.plamotranslate.models.ModelDownloads

/**
 * 音声認識モデルの選択（2026-11-09、ドイツ語の会話のために足した。docs/04_会話翻訳.md「音声認識」）。
 * 会話翻訳とテキスト翻訳の音声入力が使う。どれもSenseVoiceを主軸にし、補助のモデル（Canary・Parakeet・Qwen3-ASR）は、
 * 翻訳の2Bと同じく、ここから追加でダウンロードしてから選べる。
 */
@OptIn(ExperimentalLayoutApi::class)
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
                "会話翻訳とテキスト翻訳の音声入力で使います。いつもSenseVoiceで聞き、SenseVoiceが聞き取れない相手の言語だけを" +
                    "補助のモデルで聞き取ります。会話翻訳で選べる言語は、この組で決まります。",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
            val languages = ConversationLanguages.of(current)
            Text(
                "会話で使える言語：${languages.all.joinToString("・") { it.conversationLabel }}",
                style = MaterialTheme.typography.bodySmall,
            )
            if (languages.fixedOnly.isNotEmpty()) {
                Text(
                    "${languages.fixedOnly.joinToString("・") { it.label }}は、相手をその言語に固定したときだけ使えます" +
                        "（自分の言語や自動の相手にはできません）。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (languages.selves.size < languages.all.size) {
                Text(
                    "自分の言語にできるのは、SenseVoiceが聞き取れる${languages.selves.joinToString("・") { it.conversationLabel }}です。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // 補助のモデルのダウンロード。一度に取るのは1組だけ
            val state = download
            val missing = remember(refresh, download) {
                AsrModel.entries.filter { m -> m.bundle?.missing(context)?.isNotEmpty() ?: false }
            }
            if (missing.isNotEmpty()) {
                Text(
                    "補助のモデルは使う前にダウンロードします（Wi-Fiでの実行をおすすめします）。ダウンロードするまでは選べません。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            missing.forEach { m ->
                val bundle = m.bundle ?: return@forEach
                if (state is DownloadState.Running && state.bundle == bundle) {
                    val progress = if (state.total > 0) state.done.toFloat() / state.total else 0f
                    Text("${m.helperLabel}をダウンロード中", style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${state.done / 1_000_000} / ${state.total / 1_000_000} MB　ダウンロード中はアプリを開いたままにしてください",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    OutlinedButton(onClick = ModelDownloads::cancel) { Text("中止") }
                } else {
                    // ほかのモデルを取っている間は押せなくする
                    OutlinedButton(enabled = state !is DownloadState.Running, onClick = {
                        ModelDownloads.start(context, bundle, onCompleted = { refresh++ })
                    }) { Text("${m.helperLabel}をダウンロード（約${bundle.files.sumOf { it.size } / 1_000_000}MB）") }
                }
                failure?.takeIf { it.bundle == bundle }?.let {
                    Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (state is DownloadState.Running && missing.isNotEmpty() && missing.none { it.bundle == state.bundle }) {
                Text("ほかのモデルのダウンロードが終わると押せます", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
