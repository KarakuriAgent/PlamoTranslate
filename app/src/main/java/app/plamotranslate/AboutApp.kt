package app.plamotranslate

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/*
 * 配布のための表示（2026-11-08、APKを配布するため）。
 * PLaMo Community License 第4条は、本モデル等を第三者に提供するとき「Built with PLaMo」の明示（ユーザーインターフェース等）と、
 * 契約の写しの提供を求める。写しは公式の条文ページへのリンクで渡す（docs/11_配布.md「ライセンス」）。
 */

const val BUILT_WITH_PLAMO = "Built with PLaMo"

const val PLAMO_LICENSE_URL = "https://plamo.preferredai.jp/info/plamo-community-license-ja"

/** アプリが使っているモデルとライブラリ。ライセンスの中身はリンク先を正とする。 */
private data class Notice(val name: String, val license: String, val url: String)

private val NOTICES = listOf(
    Notice("PLaMo 3 Translate（翻訳モデル、Preferred Networks）", "PLaMo Community License", PLAMO_LICENSE_URL),
    Notice("SenseVoice Small（音声認識、FunAudioLLM）", "リンク先のモデルのライセンス", "https://github.com/FunAudioLLM/SenseVoice"),
    Notice("Silero VAD（話しているかの判定）", "MIT", "https://github.com/snakers4/silero-vad"),
    Notice("sherpa-onnx・ONNX Runtime（音声認識の実行）", "Apache-2.0・MIT", "https://github.com/k2-fsa/sherpa-onnx"),
    Notice("llama.cpp（翻訳モデルの実行）", "MIT", "https://github.com/ggml-org/llama.cpp"),
    Notice("PP-OCRv6 manga（漫画の文字の認識）", "リンク先のライセンス", "https://huggingface.co/Kellenok/PP-OCRv6_manga"),
    Notice("ML Kit Text Recognition（文字の認識、Google）", "ML Kit の利用規約", "https://developers.google.com/ml-kit/terms"),
    Notice("Android Jetpack・CameraX（Google）", "Apache-2.0", "https://developer.android.com/jetpack"),
)

fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

/** トップ画面などの下に小さく出す表示。 */
@Composable
fun BuiltWithPlamoLabel(modifier: Modifier = Modifier) {
    Text(
        BUILT_WITH_PLAMO,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** 初回のモデルの準備の画面に出す、ライセンスへの案内。モデルを使うとライセンスに同意したことになる（契約の前文）。 */
@Composable
fun LicenseAgreementNote() {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "翻訳モデルPLaMo 3 Translateは、PLaMo Community Licenseのもとで使えます。" +
                "ダウンロードして使うと、このライセンスに同意したことになります。個人の利用と非商用の利用は無料です。",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { openUrl(context, PLAMO_LICENSE_URL) }) { Text("ライセンスを読む") }
        BuiltWithPlamoLabel()
    }
}

/** 設定の画面の「このアプリについて」。版、Built with PLaMo、使っているモデルとライブラリのライセンス。 */
@Composable
fun AboutCard(version: String) {
    val context = LocalContext.current
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("このアプリについて", style = MaterialTheme.typography.titleMedium)
            Text("PLaMo翻訳 $version", style = MaterialTheme.typography.bodyMedium)
            Text(BUILT_WITH_PLAMO, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(
                "翻訳・音声認識・文字の認識は、すべて端末の中で動きます。通信するのは、モデルのダウンロードのときだけです。" +
                    "訳は機械翻訳で、誤りを含むことがあります。大事な判断には使わないでください。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text("使っているモデルとライブラリ", style = MaterialTheme.typography.titleSmall)
            NOTICES.forEach { n ->
                Column {
                    Text(n.name, style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { openUrl(context, n.url) }, contentPadding = PaddingValues(0.dp)) {
                        Text("ライセンス：${n.license}", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}
