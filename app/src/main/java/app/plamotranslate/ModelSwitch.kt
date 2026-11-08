package app.plamotranslate

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.plamotranslate.models.TranslationModel

/**
 * 翻訳モデルの小さな切り替えボタン（2026-11-06、作者の要望「会話・カメラ・テキストの画面から設定ボタンでモデルを切り替えたい」）。
 * 今のモデルと状態（GPU／CPU、読み込み中）を出し、押すと0.8B／2Bを選べる。端末にないモデルは選べない
 * （ダウンロードは設定の画面から）。切り替えは [EngineHolder.select] で、アプリ全体のモデルが変わる。
 */
@Composable
fun ModelSwitch(color: Color = Color.Unspecified) {
    val context = LocalContext.current
    val model by EngineHolder.state.collectAsStateWithLifecycle()
    var open by remember { mutableStateOf(false) }
    val current = (model as? ModelState.Ready)?.model ?: EngineHolder.selected(context)
    val label = when (val m = model) {
        is ModelState.Ready -> "${m.model.label}・${if (m.engine.usesGpu) "GPU" else "CPU"} ⚙"
        ModelState.Loading -> "${current.label} 読込中"
        is ModelState.Missing -> "モデルなし ⚙"
        is ModelState.Failed -> "読み込み失敗 ⚙"
    }
    Box {
        TextButton(onClick = { open = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (model is ModelState.Loading) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = color.takeOrElse())
                    Spacer(Modifier.width(6.dp))
                }
                Text(label, color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1, softWrap = false)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TranslationModel.entries.forEach { m ->
                val present = remember(open) { m.isPresent(context) }
                DropdownMenuItem(
                    text = {
                        Text(
                            (if (m == current) "● " else "　") + m.label +
                                (if (present) "　" + m.note else "　未ダウンロード（設定の画面から）"),
                        )
                    },
                    enabled = present && model !is ModelState.Loading,
                    onClick = {
                        open = false
                        if (m != current) EngineHolder.select(context, m)
                    },
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun Color.takeOrElse(): Color = if (this == Color.Unspecified) MaterialTheme.colorScheme.primary else this
