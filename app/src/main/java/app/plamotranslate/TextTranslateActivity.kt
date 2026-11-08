package app.plamotranslate

import android.Manifest
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.plamotranslate.llm.Language

/**
 * テキスト翻訳（2026-11-06、トップ画面から分けた）。原文の言語の既定は自動判定。
 * 原文の欄に、クリップボードからの貼り付け（貼ったらすぐ訳す）と消去のボタンを置く（作者の要望）。
 * 原文は音声でも入れられる（2026-11-08、作者の要望。欄の右端のマイク、[TranslateViewModel.startVoice]）。
 * 訳文は右端の🔊で読み上げる（2026-11-08、作者の要望、[ReadAloud]）。
 */
class TextTranslateActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { TextTranslateScreen(onBack = ::finish) } }
    }

    override fun onStop() {
        super.onStop()
        ReadAloud.stop()
    }
}

private const val SPEECH_KEY = "text-output"

@Composable
private fun TextTranslateScreen(onBack: () -> Unit, vm: TranslateViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val model by vm.model.collectAsStateWithLifecycle()
    val speaking by ReadAloud.speaking.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        @Suppress("MissingPermission")
        if (granted) vm.startVoice()
    }
    val toggleVoice = {
        when {
            state.voice == VoiceState.Listening -> vm.stopVoice()
            context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED ->
                micPermission.launch(Manifest.permission.RECORD_AUDIO)
            else -> @Suppress("MissingPermission") vm.startVoice()
        }
    }
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("←", fontSize = 22.sp) }
                Text("テキスト翻訳", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                // 翻訳モデルの状態と切り替え（2026-11-06、作者の要望）
                ModelSwitch()
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LanguagePicker(selected = state.source, options = listOf(null) + Language.entries, onSelect = vm::onSourceChange)
                TextButton(onClick = vm::swap, enabled = state.source != null) { Text("⇄") }
                LanguagePicker(selected = state.target, options = Language.entries, onSelect = { it?.let(vm::onTargetChange) })
            }

            OutlinedTextField(
                value = state.input,
                onValueChange = vm::onInputChange,
                modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                label = {
                    Text(
                        when (state.voice) {
                            VoiceState.Loading -> "原文（音声認識を準備中…）"
                            VoiceState.Listening -> "原文（聞き取り中…話し終わると止まって訳します）"
                            VoiceState.Idle -> "原文"
                        },
                    )
                },
                trailingIcon = {
                    TextButton(onClick = toggleVoice, enabled = state.voice != VoiceState.Loading) {
                        Text(if (state.voice == VoiceState.Listening) "■" else "🎤", fontSize = 22.sp)
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { vm.translate() }),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(
                    onClick = vm::translate,
                    enabled = model is ModelState.Ready && state.input.isNotBlank(),
                ) { Text(if (state.translating) "訳し直す" else "翻訳") }
                OutlinedButton(onClick = {
                    val clip = context.getSystemService(ClipboardManager::class.java).primaryClip
                    val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                    if (!text.isNullOrBlank()) {
                        vm.onInputChange(text)
                        vm.translate()
                    }
                }) { Text("📋 貼り付け") }
                OutlinedButton(onClick = vm::clear, enabled = state.input.isNotEmpty() || state.output.isNotEmpty()) {
                    Text("✕ 消去")
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // 訳文の右端の中央に読み上げ（2026-11-08、作者の要望。原文の欄の右端のマイクと対にする）
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SelectionContainer(Modifier.weight(1f)) {
                            Text(
                                text = state.output.ifEmpty { if (state.translating) "…" else "" },
                                fontSize = 20.sp,
                                modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp),
                            )
                        }
                        TextButton(
                            onClick = { ReadAloud.toggle(context, SPEECH_KEY, state.output, state.target) },
                            enabled = state.output.isNotBlank() && !state.translating,
                        ) { Text(if (speaking == SPEECH_KEY) "■" else "🔊", fontSize = 22.sp) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(state.stats, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
                        TextButton(
                            onClick = { context.copyToClipboard("原文", state.input) },
                            enabled = state.input.isNotBlank(),
                        ) { Text("原文をコピー") }
                        TextButton(
                            onClick = { context.copyToClipboard("訳文", state.output) },
                            enabled = state.output.isNotBlank(),
                        ) { Text("訳をコピー") }
                    }
                }
            }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun LanguagePicker(selected: Language?, options: List<Language?>, onSelect: (Language?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }) { Text(selected.labelOrAuto()) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { lang ->
                DropdownMenuItem(
                    text = { Text(lang.labelOrAuto()) },
                    onClick = {
                        onSelect(lang)
                        open = false
                    },
                )
            }
        }
    }
}

private fun Language?.labelOrAuto() = this?.label ?: "自動判定"
