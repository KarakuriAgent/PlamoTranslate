package app.plamotranslate.models

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** モデルのダウンロードの状態。 */
sealed interface DownloadState {
    /** 何もしていない。[missing] はまだ置かれていないファイル。 */
    data class Idle(val missing: List<ModelFile>) : DownloadState

    /** [bundle] をダウンロード中。[done] は全体で受け取ったバイト数、[total] は残っていたファイルの合計。 */
    data class Running(val bundle: ModelBundle, val current: ModelFile, val done: Long, val total: Long) : DownloadState

    /** 初回にそろえる組（[ModelBundle.REQUIRED]）が失敗した。[missing] はまだ置かれていないファイル。 */
    data class Failed(val message: String, val missing: List<ModelFile>) : DownloadState

    data object Completed : DownloadState
}

/**
 * 初回の準備がまだか（トップ画面の代わりに「モデルの準備」の画面を出すか）。
 * 設定の画面から足す組（2B・読み上げ）を取っている間は、準備済みとして扱う
 * （2026-11-09、読み上げのモデルを取っている間にトップ画面へ戻ると、準備の画面が出ていた）。
 */
fun DownloadState.needsSetup(): Boolean = when (this) {
    DownloadState.Completed -> false
    is DownloadState.Running -> bundle == ModelBundle.REQUIRED
    is DownloadState.Idle -> missing.isNotEmpty()
    is DownloadState.Failed -> missing.isNotEmpty()
}

/** 設定の画面で足す組（2B・読み上げ）のダウンロードの失敗。その組の欄に出す。 */
data class BundleFailure(val bundle: ModelBundle, val message: String)

/**
 * 初回起動時にモデルをダウンロードする（2026-11-06、APKを配布するため）。アプリ全体で1つだけ動かす。
 * 途中で切れたら、次は続きから取る（HTTPのRange）。取り終えたらハッシュを照合し、合えば置き場へ移す。
 * 翻訳・認識の処理そのものは通信しない。通信するのはこのダウンロードだけ。
 */
object ModelDownloads {
    private const val TAG = "PlamoTranslate"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<DownloadState>(DownloadState.Idle(emptyList()))
    val state: StateFlow<DownloadState> = _state.asStateFlow()
    private var job: Job? = null

    /** 置かれていないファイルを数え直す。ダウンロード中なら何もしない。 */
    fun refresh(context: Context) {
        if (job?.isActive == true) return
        val missing = ModelBundle.REQUIRED.missing(context)
        _state.value = if (missing.isEmpty()) DownloadState.Completed else DownloadState.Idle(missing)
    }

    /** [bundle] のダウンロードを始める。置き済みのファイルは飛ばす。ほかの組を取っている間は何もしない。 */
    fun start(context: Context, bundle: ModelBundle, onCompleted: () -> Unit) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        val extra = bundle != ModelBundle.REQUIRED
        if (extra) _failure.value = null
        job = scope.launch {
            val missing = bundle.missing(app)
            val total = missing.sumOf { it.size }
            var doneBefore = 0L
            try {
                for (model in missing) {
                    _state.value = DownloadState.Running(bundle, model, doneBefore, total)
                    download(model, model.file(app)) { got ->
                        _state.update { DownloadState.Running(bundle, model, doneBefore + got, total) }
                    }
                    doneBefore += model.size
                }
                _state.value = DownloadState.Completed
                onCompleted()
            } catch (e: CancellationException) {
                refreshAfter(app)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "モデルのダウンロードに失敗しました", e)
                val message = e.message ?: e.toString()
                val required = ModelBundle.REQUIRED.missing(app)
                // 初回のファイルがそろっていれば、足す組の失敗は「モデルの準備」の欄には出さない
                _state.value = if (extra && required.isEmpty()) DownloadState.Completed
                else DownloadState.Failed(message, required)
                if (extra) _failure.value = BundleFailure(bundle, message)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    private val _failure = MutableStateFlow<BundleFailure?>(null)

    /** 足す組（2B・読み上げ）の最後の失敗。次に足す組のどれかを始めると消える。 */
    val failure: StateFlow<BundleFailure?> = _failure.asStateFlow()

    private fun refreshAfter(app: Context) {
        val missing = ModelBundle.REQUIRED.missing(app)
        _state.value = if (missing.isEmpty()) DownloadState.Completed else DownloadState.Idle(missing)
    }

    /**
     * [model] を [target] へダウンロードする。途中のファイル（`.part`）があれば続きから取る。
     * [onProgress] にはこのファイルで受け取った合計バイト数を渡す。
     */
    internal suspend fun download(model: ModelFile, target: File, onProgress: (Long) -> Unit) {
        target.parentFile?.mkdirs()
        val part = File(target.path + ".part")
        if (part.length() > model.size) part.delete()
        val resumed = part.length() > 0
        if (part.length() < model.size) fetch(model.url, part, model.size, onProgress)
        onProgress(part.length())
        var actual = sha256(part)
        if (resumed && !actual.equals(model.sha256, ignoreCase = true)) {
            // 続きから取った前半が別の版のファイルだった（版を上げる前の `.part` が残っていた）。最初から取り直す
            Log.w(TAG, "途中のファイルが合わないので最初から取り直します: ${target.path}")
            part.delete()
            fetch(model.url, part, model.size, onProgress)
            actual = sha256(part)
        }
        if (!actual.equals(model.sha256, ignoreCase = true)) {
            part.delete()
            throw IOException("${model.label} が壊れていました（ハッシュが一致しません）。もう一度ダウンロードしてください")
        }
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) throw IOException("${model.label} を置けませんでした: ${target.path}")
        Log.i(TAG, "モデルを置きました: ${target.path}")
    }

    private suspend fun fetch(url: String, part: File, size: Long, onProgress: (Long) -> Unit) {
        val offset = part.length()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 60_000
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            val code = conn.responseCode
            // 206なら続きから。200なら最初から送られてくるので、途中のファイルを捨てて書き直す
            val append = when (code) {
                HttpURLConnection.HTTP_PARTIAL -> true
                HttpURLConnection.HTTP_OK -> false
                else -> throw IOException("ダウンロードに失敗しました（HTTP $code）: $url")
            }
            var got = if (append) offset else 0L
            conn.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    val buf = ByteArray(1 shl 16)
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        got += n
                        if (got - lastReport >= 1 shl 20) {
                            onProgress(got)
                            lastReport = got
                        }
                    }
                }
            }
            if (got > size) {
                // 配布元のファイルが差し替わっている。続きから取っても合わないので捨てる
                part.delete()
                throw IOException("ダウンロード元のファイルが想定と違います（$got / $size バイト）。アプリを新しい版にしてください")
            }
            if (got < size) throw IOException("ダウンロードが途中で切れました（$got / $size バイト）。もう一度押すと続きから取ります")
        } finally {
            conn.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
