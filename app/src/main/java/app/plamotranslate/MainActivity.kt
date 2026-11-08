package app.plamotranslate

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.plamotranslate.camera.CameraTranslateActivity
import app.plamotranslate.conversation.ConversationActivity
import app.plamotranslate.models.DownloadState
import app.plamotranslate.models.ModelDownloads
import app.plamotranslate.models.TranslationModel
import app.plamotranslate.screen.ScreenTranslateService


/**
 * トップ画面（2026-11-06に整理）。使う頻度の順（作者：会話→カメラ→画面→テキスト）に、
 * よく使う機能ほど大きいタイルにする。モデルの選択は設定の画面、テキスト翻訳は別の画面に分けた。
 * 初回だけ、モデルのダウンロードの画面を全体に出す。
 */
class MainActivity : ComponentActivity() {
    private val captureConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            ScreenTranslateService.start(this, result.resultCode, data)
            // 翻訳したい画面へ移ってもらうため、自分は背面へ下がる
            moveTaskToBack(true)
        } else {
            Toast.makeText(this, "画面共有が許可されませんでした", Toast.LENGTH_SHORT).show()
        }
    }
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // ファイルの大きさを見るだけなので、画面を出す前に数え直す（準備画面が一瞬出ないように）
        ModelDownloads.refresh(this)
        setContent {
            AppTheme {
                val download by ModelDownloads.state.collectAsStateWithLifecycle()
                if (!download.needsSetup()) {
                    LaunchedEffect(Unit) { EngineHolder.ensureLoaded(applicationContext) }
                    HomeScreen(onToggleScreenTranslate = ::toggleScreenTranslate)
                } else {
                    SetupScreen(download, onCompleted = { EngineHolder.ensureLoaded(applicationContext) })
                }
            }
        }
        if (savedInstanceState == null) handleShortcut(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShortcut(intent)
    }

    /** アイコンの長押しメニューとクイック設定のタイルから「画面翻訳」で来たとき。 */
    private fun handleShortcut(intent: Intent?) {
        if (intent?.action != ACTION_SCREEN_TRANSLATE) return
        if (ModelDownloads.state.value.needsSetup()) return
        toggleScreenTranslate()
    }

    private fun toggleScreenTranslate() {
        if (ScreenTranslateService.running.value) ScreenTranslateService.stop(this) else startScreenTranslate()
    }

    /** 重ね表示の許可→（通知の許可）→画面共有の許可の順に取り、サービスを始める。 */
    private fun startScreenTranslate() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "「他のアプリの上に重ねて表示」を許可してから戻ってください", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // Android 14以降は「画面全体」を既定にする（アプリ単位の共有だと別アプリへ移ったとき写らない）
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        captureConsent.launch(intent)
    }

    companion object {
        const val ACTION_SCREEN_TRANSLATE = "app.plamotranslate.action.SCREEN_TRANSLATE"
    }
}

@Composable
private fun HomeScreen(onToggleScreenTranslate: () -> Unit) {
    val context = LocalContext.current
    val screenRunning by ScreenTranslateService.running.collectAsStateWithLifecycle()
    val model by EngineHolder.state.collectAsStateWithLifecycle()
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PLaMo翻訳", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                AssistChip(
                    onClick = { context.startActivity(Intent(context, SettingsActivity::class.java)) },
                    label = { Text("${model.shortLabel()}　⚙") },
                )
            }

            val scheme = MaterialTheme.colorScheme
            Tile(
                icon = "🗣",
                title = "会話翻訳",
                note = "2人の間に置いて話す",
                container = scheme.primaryContainer,
                content = scheme.onPrimaryContainer,
                large = true,
                modifier = Modifier.fillMaxWidth().weight(4f),
                onClick = { context.startActivity(Intent(context, ConversationActivity::class.java)) },
            )
            Row(Modifier.fillMaxWidth().weight(2.6f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(
                    icon = "📷",
                    title = "カメラ",
                    note = "看板・メニューにかざす",
                    container = scheme.secondaryContainer,
                    content = scheme.onSecondaryContainer,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { context.startActivity(Intent(context, CameraTranslateActivity::class.java)) },
                )
                Tile(
                    icon = "📱",
                    title = "画面翻訳",
                    note = if (screenRunning) "● 動作中　押すと終了" else "浮遊ボタン「訳」で今の画面を訳す",
                    container = if (screenRunning) scheme.tertiary else scheme.tertiaryContainer,
                    content = if (screenRunning) scheme.onTertiary else scheme.onTertiaryContainer,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = onToggleScreenTranslate,
                )
            }
            Tile(
                icon = "✏",
                title = "テキスト翻訳",
                note = null,
                container = scheme.surfaceVariant,
                content = scheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                row = true,
                onClick = { context.startActivity(Intent(context, TextTranslateActivity::class.java)) },
            )
            // PLaMo Community License 第4条の表示（AboutApp.kt）
            BuiltWithPlamoLabel(Modifier.align(Alignment.CenterHorizontally))
        }
    }
}

@Composable
private fun Tile(
    icon: String,
    title: String,
    note: String?,
    container: Color,
    content: Color,
    modifier: Modifier,
    large: Boolean = false,
    row: Boolean = false,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        if (row) {
            Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(icon, fontSize = 22.sp)
                Spacer(Modifier.padding(6.dp))
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            return@Card
        }
        Column(
            Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.Bottom,
        ) {
            Text(icon, fontSize = if (large) 56.sp else 36.sp)
            Spacer(Modifier.weight(1f))
            Text(
                title,
                fontSize = if (large) 32.sp else 22.sp,
                fontWeight = FontWeight.Bold,
            )
            note?.let { Text(it, style = if (large) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium) }
        }
    }
}

/** 初回の準備がまだか。設定の画面から2Bを取っている間（Running の中身が2B）は、準備済みとして扱う。 */
private fun DownloadState.needsSetup(): Boolean = when (this) {
    DownloadState.Completed -> false
    is DownloadState.Running -> current != TranslationModel.LARGE.file
    is DownloadState.Idle -> missing.isNotEmpty()
    is DownloadState.Failed -> missing.isNotEmpty()
}

/** 右上の小さな表示。押すと設定の画面へ。 */
private fun ModelState.shortLabel(): String = when (this) {
    is ModelState.Ready -> "${model.label}・${if (engine.usesGpu) "GPU" else "CPU"}"
    ModelState.Loading -> "読み込み中…"
    is ModelState.Missing -> "モデルなし"
    is ModelState.Failed -> "読み込み失敗"
}

/** 初回だけ出す、モデルのダウンロードの画面（2026-11-06、APKを配布するため）。 */
@Composable
private fun SetupScreen(state: DownloadState, onCompleted: () -> Unit) {
    val context = LocalContext.current
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("PLaMo翻訳", style = MaterialTheme.typography.headlineMedium)
            Text("最初にモデルを準備します", style = MaterialTheme.typography.titleMedium)
            when (state) {
                is DownloadState.Idle, is DownloadState.Failed -> {
                    val missing = if (state is DownloadState.Idle) state.missing else (state as DownloadState.Failed).missing
                    val mb = missing.sumOf { it.size } / 1_000_000
                    Text(
                        "翻訳と音声・文字の認識に使うモデル（約${mb}MB）を、最初に1回だけダウンロードします。" +
                            "Wi-Fiでの実行をおすすめします。ダウンロードの後は、通信せずに端末の中だけで動きます。",
                    )
                    if (state is DownloadState.Failed) Text(state.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { ModelDownloads.start(context, onCompleted) }, enabled = missing.isNotEmpty()) {
                        Text(if (state is DownloadState.Failed) "続きからダウンロード" else "ダウンロード")
                    }
                    LicenseAgreementNote()
                }
                is DownloadState.Running -> {
                    val progress = if (state.total > 0) state.done.toFloat() / state.total else 0f
                    Text(state.current.label)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${state.done / 1_000_000} / ${state.total / 1_000_000} MB　ダウンロード中はこの画面を開いたままにしてください",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    OutlinedButton(onClick = ModelDownloads::cancel) { Text("中止") }
                }
                DownloadState.Completed -> Unit
            }
        }
    }
}

/** アイコンの長押しメニュー・クイック設定のタイルから画面翻訳を始める・止めるための入口。 */
fun screenTranslateIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(MainActivity.ACTION_SCREEN_TRANSLATE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
