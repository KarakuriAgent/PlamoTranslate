package app.plamotranslate.update

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.plamotranslate.openUrl

/** 設定の画面の「このアプリについて」に置く、更新の欄（[AppUpdater]）。押したときだけ通信する。 */
@Composable
fun UpdateSection() {
    val context = LocalContext.current
    val state by AppUpdater.state.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (val s = state) {
            UpdateState.Idle -> OutlinedButton(onClick = { AppUpdater.check(context) }) { Text("更新を確認") }
            UpdateState.Checking -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(18.dp).padding(end = 4.dp), strokeWidth = 2.dp)
                Text("  最新の版を確かめています…", style = MaterialTheme.typography.bodySmall)
            }
            is UpdateState.UpToDate -> {
                Text("最新の版です（${s.latestTag}）", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { AppUpdater.check(context) }) { Text("もう一度確認") }
            }
            is UpdateState.Available -> {
                Text(
                    "新しい版 ${s.release.tag} があります（約${s.release.apkSize / 1_000_000}MB）。設定とモデルはそのまま残ります。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { AppUpdater.downloadAndInstall(context, s.release) }) { Text("ダウンロードして更新") }
                    TextButton(onClick = { openUrl(context, s.release.pageUrl) }) { Text("変更の内容") }
                }
            }
            is UpdateState.Downloading -> {
                val total = s.release.apkSize
                LinearProgressIndicator(
                    progress = { if (total > 0) s.done.toFloat() / total else 0f },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("${s.done / 1_000_000} / ${total / 1_000_000} MB", style = MaterialTheme.typography.labelSmall)
                OutlinedButton(onClick = AppUpdater::cancel) { Text("中止") }
            }
            is UpdateState.Installing -> {
                Text("インストールの確認画面で「更新」を押してください。", style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { AppUpdater.downloadAndInstall(context, s.release) }) { Text("確認画面をもう一度出す") }
            }
            is UpdateState.NeedsPermission -> {
                Text(
                    "更新を入れるには、このアプリに「不明なアプリのインストール」を許可してください。許可して戻ったら、もう一度押してください。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = { AppUpdater.downloadAndInstall(context, s.release) }) { Text("ダウンロードして更新") }
            }
            is UpdateState.Failed -> {
                Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val release = s.release
                    if (release != null) {
                        OutlinedButton(onClick = { AppUpdater.downloadAndInstall(context, release) }) { Text("もう一度") }
                    } else {
                        OutlinedButton(onClick = { AppUpdater.check(context) }) { Text("もう一度確認") }
                    }
                    TextButton(onClick = { openUrl(context, AppUpdater.RELEASES_PAGE) }) { Text("リリースのページ") }
                }
            }
        }
    }
}
