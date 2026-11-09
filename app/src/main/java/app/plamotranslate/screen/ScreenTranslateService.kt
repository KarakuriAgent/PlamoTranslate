package app.plamotranslate.screen

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import app.plamotranslate.EngineHolder
import app.plamotranslate.models.ModelCatalog
import app.plamotranslate.ocr.MultiScriptOcr
import app.plamotranslate.ocr.OcrLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 画面翻訳のフォアグラウンドサービス（docs/05_画面翻訳.md）。
 * 浮遊ボタンを押すと1枚撮影し、文字を読んで日本語に訳し、元の文字の位置に重ねて出す。
 * 長押しで原文の言語の選択と終了のメニューを出す。
 *
 * 1回の翻訳の流れ（[translateScreen]）：撮影 → 文字認識（[MultiScriptOcr]）→ 訳す対象と枠の色の選別（[selectTargets]）
 * → まとめ訳し（[BatchTranslator]）→ 重ね表示（[TranslationOverlay]）。
 */
class ScreenTranslateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var projection: MediaProjection? = null
    private var capturer: ScreenCapturer? = null
    private var button: FloatingButton? = null
    private var overlay: TranslationOverlay? = null
    private var menu: LanguageMenu? = null
    private val languagePreference by lazy { LanguagePreference(this) }
    private val ocr by lazy { MultiScriptOcr(ModelCatalog.mangaDir(this)) }
    private var job: Job? = null
    private var batchTranslator: BatchTranslator? = null
    private var debugReceiver: BroadcastReceiver? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        // Android 14以降は、getMediaProjection より前にフォアグラウンドへ上げておく必要がある
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val mp = data?.let { getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, it) }
        if (mp == null) {
            Toast.makeText(this, "画面の撮影を開始できませんでした", Toast.LENGTH_SHORT).show()
            stopSelf()
            return START_NOT_STICKY
        }
        // Android 14以降は createVirtualDisplay の前にコールバックの登録が必須
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = stopSelf()
        }, Handler(Looper.getMainLooper()))
        projection = mp
        capturer = ScreenCapturer(this, mp)

        val wm = getSystemService(WindowManager::class.java)
        overlay = TranslationOverlay(this, wm)
        menu = LanguageMenu(this, wm)
        button = FloatingButton(this, wm, onTap = ::translateScreen, onLongPress = ::showLanguageMenu).also {
            it.setLabel(languagePreference.value.buttonLabel)
            it.show()
        }
        EngineHolder.ensureLoaded(this)
        registerDebugReceiver()
        _running.value = true
        return START_NOT_STICKY
    }

    private fun showLanguageMenu() {
        val (x, y) = button?.position ?: return
        val languages = OcrLanguage.entries
        val items = languages.map { lang ->
            lang.menuName to {
                languagePreference.value = lang
                button?.setLabel(lang.buttonLabel)
                Unit
            }
        } + ("画面翻訳を終了" to { stopSelf() })
        menu?.show(x, y, items, selected = languages.indexOf(languagePreference.value))
    }

    private fun translateScreen() {
        menu?.dismiss()
        job?.cancel()
        job = scope.launch {
            val overlay = overlay ?: return@launch
            val capturer = capturer ?: return@launch
            val language = languagePreference.value

            // 自分のボタンと前の訳が写らないよう、消してから撮る
            val hiddenAt = SystemClock.uptimeMillis()
            button?.setVisible(false)
            overlay.clear()
            delay(HIDE_SETTLE_MS)
            // 消した後に描かれた画面（1フレーム＝約16ms後以降に届いたもの）を使う
            val bitmap = capturer.capture(since = hiddenAt + 16)
            button?.setVisible(true)
            if (bitmap == null) {
                overlay.showStatus("画面を撮影できませんでした")
                return@launch
            }

            overlay.showStatus("文字を読んでいます…")
            val t0 = SystemClock.uptimeMillis()
            val blocks = try {
                ocr.recognize(bitmap, language)
            } catch (e: Exception) {
                bitmap.recycle()
                if (e is CancellationException) throw e
                overlay.showStatus("文字を読めませんでした: ${e.message}")
                return@launch
            }
            val ocrMillis = SystemClock.uptimeMillis() - t0
            val targets = selectTargets(blocks, bitmap, statusBarHeight())
            bitmap.recycle()
            if (targets.isEmpty()) {
                overlay.showStatus("訳す文字が見つかりませんでした（文字認識 ${ocrMillis}ms）")
                return@launch
            }
            overlay.show(targets.map { it.slot }, "${targets.size}件を翻訳中…")

            val engine = EngineHolder.awaitEngine(this@ScreenTranslateService)
            if (engine == null) {
                overlay.showStatus("翻訳モデルを読めません。アプリを開いて確認してください")
                return@launch
            }
            val t1 = SystemClock.uptimeMillis()
            // 訳の控えは原文の言語ごと。言語を変えたら作り直す
            val source = language.sourceLanguage
            val translator = BatchTranslator.reuseOrCreate(batchTranslator, engine, SCREEN_TARGET, source).also { batchTranslator = it }
            runCatching {
                translator.translate(
                    targets.map { it.sourceText },
                    onDelta = overlay::appendTranslation,
                    onReplace = overlay::setTranslation,
                )
            }.onFailure { e ->
                if (e is CancellationException) throw e
                overlay.showStatus("翻訳に失敗しました: ${e.message}")
                return@launch
            }
            val translateMillis = SystemClock.uptimeMillis() - t1
            overlay.showStatus("${targets.size}件・${"%.1f".format((ocrMillis + translateMillis) / 1000.0)}秒")
            Log.i(TAG, "画面翻訳: ${blocks.size}ブロック中${targets.size}件、文字認識 ${ocrMillis}ms、翻訳 ${translateMillis}ms、重要度 ${importance()}")
        }
    }

    /**
     * デバッグビルドだけ、PCから adb で撮影と設定変更をできるようにする（速度の計測用）。
     * adb shell am broadcast -a app.plamotranslate.DEBUG_TRANSLATE
     * adb shell am broadcast -a app.plamotranslate.DEBUG_CONFIG --ez boost false --ei threads 6
     */
    private fun registerDebugReceiver() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    DEBUG_TRANSLATE -> translateScreen()
                    DEBUG_CONFIG -> scope.launch {
                        EngineHolder.awaitEngine(this@ScreenTranslateService)?.configure(
                            boost = intent.getBooleanExtra("boost", true),
                            threads = intent.getIntExtra("threads", 0),
                        )
                    }
                }
            }
        }
        registerReceiver(receiver, IntentFilter().apply {
            addAction(DEBUG_TRANSLATE)
            addAction(DEBUG_CONFIG)
        }, RECEIVER_EXPORTED)
        debugReceiver = receiver
    }

    override fun onDestroy() {
        debugReceiver?.let { unregisterReceiver(it) }
        scope.cancel()
        button?.remove()
        overlay?.clear()
        menu?.dismiss()
        capturer?.release()
        projection?.stop()
        // 撮影を始められなかったときは認識器を作っていないので、閉じない（lazy を初めて触ると作ってしまう）
        if (projection != null) ocr.close()
        projection = null
        _running.value = false
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "画面翻訳", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ScreenTranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("画面翻訳中")
            .setContentText("浮遊ボタンで翻訳。長押しで言語の選択と終了")
            .addAction(Notification.Action.Builder(null, "終了", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "PlamoTranslate"
        private const val CHANNEL_ID = "screen_translate"
        private const val NOTIFICATION_ID = 1
        private const val HIDE_SETTLE_MS = 50L
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "app.plamotranslate.STOP_SCREEN_TRANSLATE"
        private const val DEBUG_TRANSLATE = "app.plamotranslate.DEBUG_TRANSLATE"
        private const val DEBUG_CONFIG = "app.plamotranslate.DEBUG_CONFIG"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun start(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, ScreenTranslateService::class.java)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_DATA, data),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenTranslateService::class.java).setAction(ACTION_STOP))
        }
    }
}

/** 診断用：自分のプロセスの重要度（100=前面、125=フォアグラウンドサービス）。 */
private fun importance(): Int =
    ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
