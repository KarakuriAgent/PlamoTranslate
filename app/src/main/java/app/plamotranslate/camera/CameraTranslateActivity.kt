package app.plamotranslate.camera

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Size
import android.view.OrientationEventListener
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import app.plamotranslate.AppTheme
import app.plamotranslate.EngineHolder
import app.plamotranslate.ModelState
import app.plamotranslate.ModelSwitch
import app.plamotranslate.llm.Language
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * カメラ翻訳（2026-11-06、作者の要望「カメラ画像のリアルタイム翻訳」。docs/10_カメラ翻訳.md）。
 * 看板・メニューにカメラを向けると、読めた文字の上に日本語の訳を重ねる。
 * 画面をタップすると一時停止し、そのコマの画像と訳を止めて読める。もう一度タップで再開。
 */
class CameraTranslateActivity : ComponentActivity() {
    private lateinit var translator: LiveTranslator
    private lateinit var previewView: PreviewView
    private lateinit var analysisExecutor: ExecutorService
    private val busy = AtomicBoolean(false)
    private val preferences by lazy { CameraPreferences(this) }
    private var camera: Camera? = null

    /**
     * 端末の傾き（時計回り、0・90・180・270）。画面は縦向きに固定したまま、文字認識と訳の描画だけを
     * 持っている向きに合わせる（2026-11-06、作者の要望「横向きで文章を読むこともある」）。
     */
    @Volatile private var deviceRotation = 0
    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                // 今の向きから60°を超えて傾けたときだけ切り替える（斜め45°付近でパタパタ切り替わらないように）
                val diff = abs(((orientation - deviceRotation + 540) % 360) - 180)
                if (diff > 60) deviceRotation = ((orientation + 45) / 90 * 90) % 360
            }
        }
    }

    private var paused by mutableStateOf(false)
    private var torch by mutableStateOf(false)
    /** 原文の言語。null は自動。 */
    private var source by mutableStateOf<Language?>(null)
    /** 訳す先の言語。 */
    private var target by mutableStateOf(Language.JA)

    /** 表のモード（1行ずつ訳す）か。文章のモードは行を段落にまとめて訳す。選んだ方を次も使う。 */
    private var tableMode by mutableStateOf(false)

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "カメラの使用が許可されませんでした", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        EngineHolder.ensureLoaded(applicationContext)
        translator = LiveTranslator(applicationContext, lifecycleScope)
        source = preferences.source
        translator.source = source
        target = preferences.target
        translator.target = target
        tableMode = preferences.tableMode
        translator.groupParagraphs = !tableMode
        analysisExecutor = Executors.newSingleThreadExecutor()
        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        // 画面に見えている範囲の計算に、表示の大きさを渡す
        previewView.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            translator.viewWidth = v.width
            translator.viewHeight = v.height
        }
        setContent { AppTheme { CameraScreen() } }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            // 映像と文字認識の画像を同じ4:3にそろえ、枠の位置の計算を合わせる
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(
                    ResolutionStrategy(Size(1280, 960), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                )
                .build()
            val preview = Preview.Builder().setResolutionSelector(selector).build()
                .also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysis.setAnalyzer(analysisExecutor, ::analyze)
            provider.unbindAll()
            camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    /** 文字認識が終わるまで次のコマは捨てる（最新のコマだけを読む）。一時停止中も捨てる。 */
    private fun analyze(image: ImageProxy) {
        if (paused || !busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        val bitmap = try {
            image.toUprightBitmap()
        } finally {
            image.close()
        }
        lifecycleScope.launch {
            try {
                if (!paused) translator.process(bitmap, deviceRotation)
            } finally {
                busy.set(false)
            }
        }
    }

    /** 一時停止したら、そのコマに映っている文を全部訳す。 */
    private fun togglePause() {
        paused = !paused
        translator.paused = paused
        if (paused) translator.translateAll()
    }

    private fun chooseTableMode(table: Boolean) {
        tableMode = table
        preferences.tableMode = table
        translator.groupParagraphs = !table
    }

    private fun chooseSource(lang: Language?) {
        source = lang
        preferences.source = lang
        translator.source = lang
    }

    private fun chooseTarget(lang: Language) {
        target = lang
        preferences.target = lang
        translator.target = lang
    }

    private fun switchTorch(on: Boolean) {
        torch = on
        camera?.cameraControl?.enableTorch(on)
    }

    override fun onResume() {
        super.onResume()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
    }

    override fun onPause() {
        orientationListener.disable()
        super.onPause()
    }

    override fun onDestroy() {
        analysisExecutor.shutdown()
        translator.close()
        super.onDestroy()
    }

    @Composable
    private fun CameraScreen() {
        val frame by translator.frame.collectAsStateWithLifecycle()
        val model by EngineHolder.state.collectAsStateWithLifecycle()
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            val still = frame
            if (paused && still != null) {
                Image(
                    still.bitmap.asImageBitmap(), contentDescription = null,
                    contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                )
            }
            AndroidView(
                factory = { CameraOverlayView(it) },
                update = { it.frame = frame },
                modifier = Modifier.fillMaxSize().clickable { togglePause() },
            )

            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(
                    Modifier.fillMaxWidth().background(Color(0x99000000)).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = ::finish) { Text("←", fontSize = 22.sp, color = Color.White) }
                    Text(
                        statusText(frame),
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    ModelSwitch(color = Color.White)
                    TextButton(onClick = { switchTorch(!torch) }) {
                        Text(if (torch) "💡 消す" else "💡 ライト", color = Color.White)
                    }
                }
                ModelBanner(model)
                Box(Modifier.weight(1f))
                Column(
                    Modifier.fillMaxWidth().background(Color(0x99000000)).padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        FilterChip(
                            selected = !tableMode,
                            onClick = { chooseTableMode(false) },
                            label = { Text("文章") },
                            colors = FilterChipDefaults.filterChipColors(labelColor = Color.White),
                        )
                        FilterChip(
                            selected = tableMode,
                            onClick = { chooseTableMode(true) },
                            label = { Text("表・メニュー") },
                            colors = FilterChipDefaults.filterChipColors(labelColor = Color.White),
                        )
                        TargetPicker()
                    }
                    SourcePicker()
                    Text(
                        if (paused) "一時停止中　タップで再開" else "画面をタップで一時停止",
                        color = Color.White,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.clickable { togglePause() },
                    )
                }
            }
        }
    }

    /**
     * 原文の言語の選択。自動とよく使う言語はチップで並べ、ほかの言語（ラテン文字の20以上の言語と繁体字）は
     * 「他の言語」のメニューから選ぶ（2026-11-08、選べる言語を認識器が読めるすべての言語に広げた。CameraLanguage.kt）。
     */
    @Composable
    private fun SourcePicker() {
        val chipColors = FilterChipDefaults.filterChipColors(labelColor = Color.White)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilterChip(selected = source == null, onClick = { chooseSource(null) }, label = { Text("自動") }, colors = chipColors)
            CAMERA_QUICK_SOURCES.forEach { lang ->
                FilterChip(selected = source == lang, onClick = { chooseSource(lang) }, label = { Text(lang.chipName) }, colors = chipColors)
            }
            var open by remember { mutableStateOf(false) }
            val other = source?.takeIf { it !in CAMERA_QUICK_SOURCES }
            Box {
                FilterChip(
                    selected = other != null,
                    onClick = { open = true },
                    label = { Text("${other?.label ?: "他の言語"} ▾") },
                    colors = chipColors,
                )
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    CAMERA_SOURCES.filter { it !in CAMERA_QUICK_SOURCES }.forEach { lang ->
                        DropdownMenuItem(
                            text = { Text(lang.label) },
                            onClick = { open = false; chooseSource(lang) },
                        )
                    }
                }
            }
        }
    }

    /**
     * 訳す先の言語の選択（2026-11-08、作者の要望）。PLaMoはどの言語へも訳せるので、53言語をメニューに並べる。
     * 日本語以外を選んだときは目立つよう、選んだ状態のチップにする。
     */
    @Composable
    private fun TargetPicker() {
        var open by remember { mutableStateOf(false) }
        Box {
            FilterChip(
                selected = target != Language.JA,
                onClick = { open = true },
                label = { Text("訳：${target.chipName} ▾") },
                colors = FilterChipDefaults.filterChipColors(labelColor = Color.White),
            )
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                CAMERA_TARGETS.forEach { lang ->
                    DropdownMenuItem(
                        text = { Text(if (lang == target) "✓ ${lang.label}" else lang.label) },
                        onClick = { open = false; chooseTarget(lang) },
                    )
                }
            }
        }
    }

    /**
     * 翻訳モデルの状態（2026-11-06、作者の要望「モデル読み込み中かどうか判断つくように」）。
     * 読み込み中・失敗のときだけ上のバーの下に出す。文字の枠は出ているのに訳が出ない理由が分かるように。
     */
    @Composable
    private fun ModelBanner(model: ModelState) {
        val text = when (model) {
            is ModelState.Ready -> return
            ModelState.Loading -> "翻訳モデル（${EngineHolder.selected(this).label}）を読み込み中…　読み込みが終わると訳が出ます"
            is ModelState.Missing -> "翻訳モデルが端末にありません。トップ画面に戻ると出る「モデルの準備」か、設定の画面でダウンロードしてください"
            is ModelState.Failed -> "翻訳モデルを読み込めません：${model.message}"
        }
        Row(
            Modifier.fillMaxWidth().background(if (model is ModelState.Loading) Color(0xCC1565C0) else Color(0xCCB71C1C))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (model is ModelState.Loading) CircularProgressIndicator(Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
            Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge)
        }
    }

    private fun statusText(frame: LiveFrame?): String {
        val f = frame ?: return "文字を探しています…"
        if (f.boxes.isEmpty()) return "文字が見つかりません（読み取り ${f.ocrMillis}ms）"
        val waiting = f.boxes.count { it.translation == null && it.onScreen }
        val hint = if (waiting > 0 && !paused) "　止めて読むならタップ" else ""
        return "${f.boxes.count { it.onScreen }}件" + (if (waiting > 0) "・訳待ち$waiting" else "") + "（読み取り ${f.ocrMillis}ms）$hint"
    }
}

/** カメラのコマを、端末を立てた向きの画像にする。 */
private fun ImageProxy.toUprightBitmap(): Bitmap {
    val raw = toBitmap()
    val degrees = imageInfo.rotationDegrees
    if (degrees == 0) return raw
    val rotated = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(degrees.toFloat()) }, true)
    raw.recycle()
    return rotated
}
