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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.plamotranslate.llm.Language
import app.plamotranslate.models.DownloadState
import app.plamotranslate.models.ModelBundle
import app.plamotranslate.models.ModelCatalog
import app.plamotranslate.models.ModelDownloads
import app.plamotranslate.tts.SupertonicSpeaker
import kotlin.math.roundToInt

/*
 * 読み上げの設定（2026-11-08、作者の要望「読み上げもモデルを切り替えできたら嬉しい」）。
 * 読むもの（Supertonic 3・端末の読み上げ）、Supertonic 3のダウンロード、声と速さ、試しに聞くボタン。
 */

private val SPEEDS = listOf(0.8f, 1f, 1.2f, 1.4f)
private const val SAMPLE_KEY = "settings-sample"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeechCard() {
    val context = LocalContext.current
    val download by ModelDownloads.state.collectAsStateWithLifecycle()
    val failure by ModelDownloads.failure.collectAsStateWithLifecycle()
    val speaking by ReadAloud.speaking.collectAsStateWithLifecycle()
    val preferences = remember { SpeechPreferences(context) }
    var engine by remember { mutableStateOf(preferences.engine) }
    var voice by remember { mutableStateOf(preferences.voice) }
    var speed by remember { mutableStateOf(preferences.speed) }
    var steps by remember { mutableStateOf(preferences.steps) }
    var refresh by remember { mutableStateOf(0) }
    val present = remember(refresh, download) { ModelCatalog.speechPresent(context) }
    // 声の数はモデルを読み込むと分かる。Supertonic 3を選んでいてモデルがあれば、開いたときに読み込む
    var voices by remember { mutableStateOf(0) }
    LaunchedEffect(present, engine) {
        if (present && engine == SpeechEngine.SUPERTONIC) {
            voices = runCatching { ReadAloud.supertonicVoices(context) }.getOrDefault(0)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("読み上げ", style = MaterialTheme.typography.titleMedium)
            Text(
                "会話翻訳のカードの訳文をタップしたとき、テキスト翻訳の🔊を押したときに読みます。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpeechEngine.entries.forEach { e ->
                    FilterChip(
                        selected = engine == e,
                        onClick = {
                            engine = e
                            preferences.engine = e
                        },
                        label = { Text(e.label) },
                    )
                }
            }
            SpeechEngine.entries.forEach {
                Text("${it.label}：${it.note}", style = MaterialTheme.typography.bodySmall)
            }

            if (engine == SpeechEngine.SUPERTONIC) {
                val files = ModelBundle.SPEECH.files
                val state = download
                if (state is DownloadState.Running && state.bundle == ModelBundle.SPEECH) {
                    val progress = if (state.total > 0) state.done.toFloat() / state.total else 0f
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${state.done / 1_000_000} / ${state.total / 1_000_000} MB　ダウンロード中はアプリを開いたままにしてください",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    OutlinedButton(onClick = ModelDownloads::cancel) { Text("中止") }
                } else if (!present) {
                    Text(
                        "Supertonic 3は使う前に約${files.sumOf { it.size } / 1_000_000}MBをダウンロードします。" +
                            "ダウンロードするまでは端末の読み上げで読みます。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // 一度に取るのは1組だけ。2Bを取っている間は押せなくし、理由を出す
                    val busy = state is DownloadState.Running
                    OutlinedButton(enabled = !busy, onClick = {
                        ModelDownloads.start(context, ModelBundle.SPEECH, onCompleted = { refresh++ })
                    }) { Text("Supertonic 3をダウンロード") }
                    if (busy) Text("ほかのモデルのダウンロードが終わると押せます", style = MaterialTheme.typography.bodySmall)
                }
                failure?.takeIf { it.bundle == ModelBundle.SPEECH }?.let {
                    Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (present) {
                    Text("音質（段数 $steps）", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = steps.toFloat(),
                        onValueChange = { steps = it.roundToInt() },
                        onValueChangeFinished = { preferences.steps = steps },
                        valueRange = SupertonicSpeaker.STEPS_RANGE.first.toFloat()..SupertonicSpeaker.STEPS_RANGE.last.toFloat(),
                        steps = SupertonicSpeaker.STEPS_RANGE.last - SupertonicSpeaker.STEPS_RANGE.first - 1,
                    )
                    Text(
                        "増やすと話している最中のザラつきが減り、読み始めるまでが長くなります（既定 ${SupertonicSpeaker.DEFAULT_STEPS}）。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (present && voices > 0) {
                    Text("声", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (0 until voices).forEach { v ->
                            FilterChip(
                                selected = voice == v,
                                onClick = {
                                    voice = v
                                    preferences.voice = v
                                },
                                label = { Text("${v + 1}") },
                            )
                        }
                    }
                }
            }

            Text("速さ", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SPEEDS.forEach { s ->
                    FilterChip(
                        selected = speed == s,
                        onClick = {
                            speed = s
                            preferences.speed = s
                        },
                        label = { Text("×$s") },
                    )
                }
            }
            if (engine == SpeechEngine.SYSTEM) {
                Text("端末の読み上げの速さと声は、Androidの設定の「テキスト読み上げの出力」で変えます。", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Language.JA to "こんにちは。駅までの行き方を教えてください。",
                    Language.KO to "안녕하세요. 역까지 가는 방법을 알려 주세요.",
                    Language.EN to "Hello. Could you tell me how to get to the station?",
                ).forEach { (lang, text) ->
                    val key = "$SAMPLE_KEY-${lang.code}"
                    OutlinedButton(onClick = { ReadAloud.toggle(context, key, text, lang) }) {
                        Text(if (speaking == key) "■ 止める" else "▶ ${lang.label}")
                    }
                }
            }
        }
    }
}
