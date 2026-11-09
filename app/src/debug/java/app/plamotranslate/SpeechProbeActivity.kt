package app.plamotranslate

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.ModelDownloadListener
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import app.plamotranslate.asr.AsrEngine
import app.plamotranslate.asr.WavFile
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Android標準の端末内音声認識（`SpeechRecognizer.createOnDeviceSpeechRecognizer`）の試し（デバッグ版だけ。2026-11-09）。
 *
 * 会話翻訳でドイツ語を速く聞き取るため、端末内の認識で ja-JP・de-DE が使えるか、言語の判定と切り替え
 * （Android 14以上の `EXTRA_ENABLE_LANGUAGE_DETECTION`・`EXTRA_ENABLE_LANGUAGE_SWITCH`）が効くか、
 * 途中の文字と確定がいつ出るかを確かめる。クラウドの認識は使わない（要件：端末内だけで動く）。
 * 出来事は流し始めからの経過ミリ秒つきで画面と logcat（`音声試し: `）に出す。docs/04_会話翻訳.md「音声認識の候補」。
 *
 * adb shell am start -S -n app.plamotranslate/.SpeechProbeActivity \
 *   --ez autostart true --es source wav --es wav /sdcard/Android/data/app.plamotranslate/files/probe/ja-de-gap.wav \
 *   --es lang ja-JP --ez detect true --es switch balanced --ez realtime true --ez segmented true
 *
 * intent の extra（どれも省略できる。画面でも選べる）：
 * - `lang`：最初の言語（ja-JP・de-DE。既定 ja-JP）
 * - `detect`：言語の判定（既定 true）。`switch`：言語の切り替え（off・precision・balanced・quick。既定 balanced）
 * - `segmented`：黙っても止めない `EXTRA_SEGMENTED_SESSION`（既定 true）。マイクで止まったら始め直す
 * - `source`：mic・wav（既定 wav）。`wav`：WAVのパス（既定は外部ファイル置き場の `probe/ja-de-gap.wav`）
 * - `realtime`：WAVを0.1秒ずつ実時間に合わせて書くか、一気に書くか（既定 true）
 * - `autostart`：開いたらすぐ始める（既定 false）
 */
class SpeechProbeActivity : Activity() {
    private lateinit var logView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var langGroup: RadioGroup
    private lateinit var switchGroup: RadioGroup
    private lateinit var detectBox: CheckBox
    private lateinit var segmentedBox: CheckBox
    private lateinit var realtimeBox: CheckBox
    private lateinit var wavPath: EditText

    private var recognizer: SpeechRecognizer? = null

    /** 経過ミリ秒の起点。startListening で置き、WAVを書き始めたら置き直す。 */
    @Volatile private var t0 = SystemClock.elapsedRealtime()

    /** マイクで聞いている間 true。止まったら始め直す。 */
    private var micRunning = false
    private var pipeRead: ParcelFileDescriptor? = null
    private var writer: Thread? = null

    /** onReadyForSpeech を待ってからWAVを書き始めるための合図。セッションごとに作り直す。 */
    @Volatile private var ready = CountDownLatch(1)

    /** 許可を待っている間の操作。 */
    private var pendingStart: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        applyExtras(intent)
        log("端末 ${Build.MANUFACTURER} ${Build.MODEL}・API ${Build.VERSION.SDK_INT}")
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(this)) {
            log("端末内の音声認識が使えません（isOnDeviceRecognitionAvailable = false）")
            return
        }
        recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this).apply { setRecognitionListener(listener) }
        checkSupport()
        if (intent.getBooleanExtra("autostart", false)) {
            if (intent.getStringExtra("source") == "mic") startMic() else startWav()
        }
    }

    // ---- 画面 ----

    private fun buildUi() {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 96, 32, 32) }
        fun row(vararg views: android.view.View) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            views.forEach { addView(it) }
            column.addView(this)
        }
        fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; isAllCaps = false; setOnClickListener { action() } }
        fun radios(vararg labels: String) = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            labels.forEachIndexed { i, l -> addView(RadioButton(context).apply { text = l; id = i + 1 }) }
            check(1)
        }

        langGroup = radios(*LANGS.toTypedArray())
        column.addView(TextView(this).apply { text = "最初の言語（EXTRA_LANGUAGE）" })
        column.addView(langGroup)
        detectBox = CheckBox(this).apply { text = "言語の判定"; isChecked = true }
        segmentedBox = CheckBox(this).apply { text = "区切りのセッション"; isChecked = true }
        row(detectBox, segmentedBox)
        column.addView(TextView(this).apply { text = "言語の切り替え（EXTRA_ENABLE_LANGUAGE_SWITCH）" })
        switchGroup = radios(*SWITCH_MODES.map { it.first }.toTypedArray()).apply { check(3) }
        column.addView(switchGroup)
        wavPath = EditText(this).apply { textSize = 12f; setText(File(getExternalFilesDir("probe"), "ja-de-gap.wav").path) }
        column.addView(wavPath, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        realtimeBox = CheckBox(this).apply { text = "実時間で流す"; isChecked = true }
        row(realtimeBox)
        row(button("対応を調べる") { checkSupport() }, button("ja-JPを取る") { download("ja-JP") }, button("de-DEを取る") { download("de-DE") })
        row(button("マイクで聞く") { startMic() }, button("WAVを流す") { startWav() }, button("止める") { stop() })

        logView = TextView(this).apply { textSize = 12f; setTextIsSelectable(true) }
        scroll = ScrollView(this).apply { addView(logView) }
        column.addView(scroll, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        setContentView(column)
    }

    private fun applyExtras(intent: Intent) {
        intent.getStringExtra("lang")?.let { lang -> LANGS.indexOf(lang).takeIf { it >= 0 }?.let { langGroup.check(it + 1) } }
        intent.getStringExtra("switch")?.let { mode ->
            SWITCH_MODES.indexOfFirst { it.first == mode }.takeIf { it >= 0 }?.let { switchGroup.check(it + 1) }
        }
        if (intent.hasExtra("detect")) detectBox.isChecked = intent.getBooleanExtra("detect", true)
        if (intent.hasExtra("segmented")) segmentedBox.isChecked = intent.getBooleanExtra("segmented", true)
        if (intent.hasExtra("realtime")) realtimeBox.isChecked = intent.getBooleanExtra("realtime", true)
        intent.getStringExtra("wav")?.let { wavPath.setText(it) }
    }

    private val language get() = LANGS[langGroup.checkedRadioButtonId - 1]
    private val switchMode get() = SWITCH_MODES[switchGroup.checkedRadioButtonId - 1]

    // ---- 対応とモデル ----

    private fun checkSupport() {
        val r = recognizer ?: return
        r.checkRecognitionSupport(recognizeIntent(), mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                log("対応: 端末内で使える ${support.supportedOnDeviceLanguages}")
                log("対応: 入っている ${support.installedOnDeviceLanguages}")
                log("対応: 取っている途中 ${support.pendingOnDeviceLanguages}")
                log("対応: クラウド（使わない） ${support.onlineLanguages}")
                for (lang in LANGS) {
                    val state = when (lang) {
                        in support.installedOnDeviceLanguages -> "入っている"
                        in support.pendingOnDeviceLanguages -> "取っている途中"
                        in support.supportedOnDeviceLanguages -> "使えるが入っていない（取るボタンで取る）"
                        else -> "端末内では使えない"
                    }
                    log("対応: $lang は$state")
                }
            }

            override fun onError(error: Int) = log("対応: 調べられません ${errorName(error)}")
        })
    }

    private fun download(lang: String) {
        val r = recognizer ?: return
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            r.triggerModelDownload(intent, mainExecutor, object : ModelDownloadListener {
                override fun onProgress(completedPercent: Int) = log("モデル $lang: $completedPercent%")
                override fun onSuccess() = log("モデル $lang: 取り終わった")
                override fun onScheduled() = log("モデル $lang: 予約された（Wi-Fiなどの条件で後から取る）")
                override fun onError(error: Int) = log("モデル $lang: 失敗 ${errorName(error)}")
            })
        } else {
            r.triggerModelDownload(intent)
        }
        log("モデル $lang: triggerModelDownload を呼んだ")
    }

    // ---- 認識 ----

    /** 端末内の認識に渡す Intent。言語の判定・切り替えはAndroid 14（API 34）から。 */
    private fun recognizeIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (detectBox.isChecked) {
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, ArrayList(LANGS))
            }
            switchMode.second?.let { mode ->
                putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, mode)
                putStringArrayListExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, ArrayList(LANGS))
            }
        }
    }

    private fun describe(intent: Intent) = intent.extras?.dump() ?: ""

    private fun startMic() {
        if (!ensurePermission { startMic() }) return
        val r = recognizer ?: return log("端末内の音声認識が使えません")
        stop()
        micRunning = true
        startMicSession(r)
    }

    private fun startMicSession(r: SpeechRecognizer) {
        val intent = recognizeIntent().apply {
            if (segmentedBox.isChecked) {
                // マイクでは「最短の長さ」を区切りのセッションの種類にし、その間は黙っても止めない
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MIC_SESSION_MS)
            }
        }
        t0 = SystemClock.elapsedRealtime()
        ready = CountDownLatch(1)
        log("マイク: 開始 ${describe(intent)}")
        r.startListening(intent)
    }

    private fun startWav() {
        if (!ensurePermission { startWav() }) return
        val r = recognizer ?: return log("端末内の音声認識が使えません")
        stop()
        val file = File(wavPath.text.toString().trim())
        val pcm = runCatching { toPcm16(WavFile.read(file)) }.getOrElse { return log("WAV: 読めません ${it.message}") }
        val realtime = realtimeBox.isChecked
        val (read, write) = ParcelFileDescriptor.createPipe()
        pipeRead = read
        val intent = recognizeIntent().apply {
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, AsrEngine.SAMPLE_RATE)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            // WAVでは音声の入口が閉じるまでを1つのセッションにする
            if (segmentedBox.isChecked) putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }
        t0 = SystemClock.elapsedRealtime()
        val latch = CountDownLatch(1).also { ready = it }
        log("WAV: ${file.name} ${pcm.size / 2 / AsrEngine.SAMPLE_RATE.toFloat()}秒、${if (realtime) "実時間" else "一気"}に流す ${describe(intent)}")
        r.startListening(intent)
        writer = Thread({ writePcm(write, pcm, realtime, latch) }, "speech-probe-writer").apply { start() }
    }

    /** onReadyForSpeech を待ってから（最長3秒）パイプへ書く。書き始めを経過ミリ秒の起点にする。 */
    private fun writePcm(write: ParcelFileDescriptor, pcm: ByteArray, realtime: Boolean, latch: CountDownLatch) {
        val readyCame = try {
            latch.await(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            runCatching { write.close() }
            return
        }
        val start = SystemClock.elapsedRealtime()
        val note = if (readyCame) "onReadyForSpeech などの後" else "onReadyForSpeech が来ないまま${READY_TIMEOUT_MS}ms待って"
        runOnUiThread { log("WAV: 書き始め（開始から +${start - t0}ms、$note。ここから経過ミリ秒を数え直す）") }
        t0 = start
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(write).use { out ->
                var offset = 0
                var chunk = 0
                while (offset < pcm.size && !Thread.currentThread().isInterrupted) {
                    val n = minOf(CHUNK_BYTES, pcm.size - offset)
                    out.write(pcm, offset, n)
                    offset += n
                    chunk++
                    if (realtime) {
                        val due = start + chunk * CHUNK_MS
                        val wait = due - SystemClock.elapsedRealtime()
                        if (wait > 0) Thread.sleep(wait)
                    }
                }
                out.flush()
            }
            runOnUiThread { log("WAV: 書き終わり（パイプを閉じた）") }
        } catch (e: InterruptedException) {
            runCatching { write.close() }
            runOnUiThread { log("WAV: 書くのを止めた") }
        } catch (e: IOException) {
            runOnUiThread { log("WAV: 書けません ${e.message}") }
        }
    }

    private fun stop() {
        micRunning = false
        writer?.interrupt()
        writer = null
        recognizer?.cancel()
        closePipe()
    }

    private fun closePipe() {
        pipeRead?.let { runCatching { it.close() } }
        pipeRead = null
    }

    /** セッションが終わった。マイクで聞いている間は始め直す（区切りのセッションが効かないときの代わり）。 */
    private fun sessionEnded() {
        closePipe()
        val r = recognizer ?: return
        if (micRunning) {
            log("マイク: 止まったので始め直す")
            startMicSession(r)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            log("onReadyForSpeech ${params?.dump().orEmpty()}")
            ready.countDown()
        }

        override fun onBeginningOfSpeech() = log("onBeginningOfSpeech")
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = log("onEndOfSpeech")
        override fun onEvent(eventType: Int, params: Bundle?) = log("onEvent $eventType ${params?.dump().orEmpty()}")
        override fun onPartialResults(partialResults: Bundle) = log("onPartialResults ${partialResults.dump()}")
        override fun onSegmentResults(segmentResults: Bundle) = log("onSegmentResults ${segmentResults.dump()}")
        override fun onLanguageDetection(results: Bundle) = log("onLanguageDetection ${results.dump()}")

        override fun onResults(results: Bundle) {
            log("onResults ${results.dump()}")
            sessionEnded()
        }

        override fun onEndOfSegmentedSession() {
            log("onEndOfSegmentedSession")
            sessionEnded()
        }

        override fun onError(error: Int) {
            log("onError ${errorName(error)}")
            ready.countDown()
            // 黙っていて止まったときだけ始め直す。それ以外の失敗で回り続けないよう、マイクも止める
            if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) micRunning = false
            sessionEnded()
        }
    }

    // ---- 許可 ----

    private fun ensurePermission(retry: () -> Unit): Boolean {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) return true
        pendingStart = retry
        log("マイクの許可を求める（adbなら pm grant app.plamotranslate android.permission.RECORD_AUDIO）")
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD)
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD) return
        val retry = pendingStart
        pendingStart = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) retry?.invoke() else log("マイクの許可がありません")
    }

    override fun onDestroy() {
        stop()
        recognizer?.destroy()
        recognizer = null
        super.onDestroy()
    }

    // ---- 表示 ----

    private fun log(message: String) {
        val line = "+${SystemClock.elapsedRealtime() - t0}ms $message"
        Log.i("PlamoTranslate", "音声試し: $line")
        logView.append(line + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private companion object {
        val LANGS = listOf("ja-JP", "de-DE")

        /** 画面とadbでの名前と、EXTRA_ENABLE_LANGUAGE_SWITCH に渡す値（null は付けない）。 */
        val SWITCH_MODES = listOf(
            "off" to null,
            "precision" to RecognizerIntent.LANGUAGE_SWITCH_HIGH_PRECISION,
            "balanced" to RecognizerIntent.LANGUAGE_SWITCH_BALANCED,
            "quick" to RecognizerIntent.LANGUAGE_SWITCH_QUICK_RESPONSE,
        )

        const val REQUEST_RECORD = 1
        const val CHUNK_MS = 100L
        const val CHUNK_BYTES = (AsrEngine.SAMPLE_RATE / 10) * 2
        const val READY_TIMEOUT_MS = 3_000L

        /** マイクの区切りのセッションを続ける長さ（10分）。 */
        const val MIC_SESSION_MS = 10 * 60_000L

        fun toPcm16(samples: FloatArray): ByteArray {
            val out = ByteArray(samples.size * 2)
            samples.forEachIndexed { i, s ->
                val v = (s * 32768f).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                out[i * 2] = v.toByte()
                out[i * 2 + 1] = (v shr 8).toByte()
            }
            return out
        }

        @Suppress("DEPRECATION")
        fun Bundle.dump(): String = keySet().sorted().joinToString(", ", "{", "}") { key ->
            val value = when (val v = get(key)) {
                is FloatArray -> v.contentToString()
                is IntArray -> v.contentToString()
                is Array<*> -> v.contentDeepToString()
                is Bundle -> v.dump()
                else -> v.toString()
            }
            "$key=$value"
        }

        fun errorName(error: Int): String = when (error) {
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "ERROR_NETWORK_TIMEOUT"
            SpeechRecognizer.ERROR_NETWORK -> "ERROR_NETWORK"
            SpeechRecognizer.ERROR_AUDIO -> "ERROR_AUDIO"
            SpeechRecognizer.ERROR_SERVER -> "ERROR_SERVER"
            SpeechRecognizer.ERROR_CLIENT -> "ERROR_CLIENT"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "ERROR_SPEECH_TIMEOUT"
            SpeechRecognizer.ERROR_NO_MATCH -> "ERROR_NO_MATCH"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "ERROR_RECOGNIZER_BUSY"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "ERROR_INSUFFICIENT_PERMISSIONS"
            SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "ERROR_TOO_MANY_REQUESTS"
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "ERROR_SERVER_DISCONNECTED"
            SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "ERROR_LANGUAGE_NOT_SUPPORTED"
            SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "ERROR_LANGUAGE_UNAVAILABLE"
            SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT -> "ERROR_CANNOT_CHECK_SUPPORT"
            SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS -> "ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS"
            else -> "ERROR_$error"
        }
    }
}
