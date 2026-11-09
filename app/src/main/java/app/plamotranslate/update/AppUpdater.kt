package app.plamotranslate.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import app.plamotranslate.models.ModelDownloads
import app.plamotranslate.models.ModelFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** GitHubのリリース1件。[apkSha256] はGitHubがアセットに付けるSHA-256（`digest`）。 */
data class ReleaseInfo(
    val tag: String,
    val pageUrl: String,
    val notes: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    val apkSha256: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val latestTag: String) : UpdateState
    data class Available(val release: ReleaseInfo) : UpdateState
    data class Downloading(val release: ReleaseInfo, val done: Long) : UpdateState

    /** インストールの確認画面を出した（作者が「更新」を押すのを待っている）。 */
    data class Installing(val release: ReleaseInfo) : UpdateState

    /** 「不明なアプリのインストール」の許可が要る。許可して戻ったら、もう一度押してもらう。 */
    data class NeedsPermission(val release: ReleaseInfo) : UpdateState
    data class Failed(val message: String, val release: ReleaseInfo?) : UpdateState
}

/**
 * GitHubの最新リリースから新しい版のAPKを取って入れる（2026-11-09、作者の要望。docs/06_開発環境.md「アプリの更新」）。
 *
 * - 通信するのは、設定の画面で「更新を確認」「ダウンロードして更新」を押したときだけ（起動時に勝手に確かめない）。
 * - 確認：公開リポジトリのAPI `releases/latest` を読み、タグとアプリの版を比べる（[AppVersion]）。認証は要らない。
 * - ダウンロード：モデルと同じ部品（[ModelDownloads.download]：続きから取る、SHA-256で照合）で、アプリのキャッシュへ取る。
 * - 照合：取ったAPKのパッケージ名が同じで、版が新しく、署名の鍵が入っているアプリと同じことを確かめてから入れる。
 *   開発版（デバッグ鍵）や他人が作ったAPKは、ここで止める。
 * - インストール：PackageInstallerのセッションで入れる。Androidが確認画面を出し、作者が「更新」を押すと入れ替わる。
 *   同じ鍵の上書きなので、設定もモデルも消えない。
 *
 * Google Playで配る版では、Playの規約でアプリ自身の更新が禁じられているので、この機能を外す（docs/11）。
 */
object AppUpdater {
    private const val TAG = "PlamoTranslate"
    const val LATEST_API = "https://api.github.com/repos/kagerouttepaso/PlamoTranslate/releases/latest"
    const val RELEASES_PAGE = "https://github.com/kagerouttepaso/PlamoTranslate/releases"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private var job: Job? = null

    fun currentVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""

    /** 最新のリリースを確かめる。 */
    fun check(context: Context) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        job = scope.launch {
            _state.value = UpdateState.Checking
            _state.value = try {
                val release = fetchLatest(currentVersion(app))
                if (AppVersion.isNewer(release.tag, currentVersion(app))) UpdateState.Available(release)
                else UpdateState.UpToDate(release.tag)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "更新の確認に失敗しました", e)
                UpdateState.Failed(e.message ?: e.toString(), null)
            }
        }
    }

    /** [release] のAPKを取り、照合して、インストールの確認画面を出す。 */
    fun downloadAndInstall(context: Context, release: ReleaseInfo) {
        if (job?.isActive == true) return
        val app = context.applicationContext
        // 「不明なアプリのインストール」を許可していなければ、先に設定を開く（許可して戻ったらもう一度押してもらう）
        if (!app.packageManager.canRequestPackageInstalls()) {
            _state.value = UpdateState.NeedsPermission(release)
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${app.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        job = scope.launch {
            try {
                val apk = File(updatesDir(app), release.apkName)
                // 前に取った別の版のAPKは消す
                updatesDir(app).listFiles()?.filter { !it.name.startsWith(release.apkName) }?.forEach { it.delete() }
                // 取り終えたAPKは、ハッシュが合ったときだけ置かれる。確認画面を出し直すときは取り直さない
                if (apk.length() != release.apkSize) {
                    _state.value = UpdateState.Downloading(release, 0)
                    val file = ModelFile(
                        label = "PLaMo翻訳 ${release.tag}",
                        url = release.apkUrl,
                        path = release.apkName,
                        size = release.apkSize,
                        sha256 = release.apkSha256,
                    )
                    ModelDownloads.download(file, apk) { got -> _state.value = UpdateState.Downloading(release, got) }
                }
                verify(app, apk)
                _state.value = UpdateState.Installing(release)
                install(app, apk)
            } catch (e: CancellationException) {
                _state.value = UpdateState.Available(release)
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "更新に失敗しました", e)
                _state.value = UpdateState.Failed(e.message ?: e.toString(), release)
            }
        }
    }

    fun cancel() {
        job?.cancel()
    }

    /** インストールの結果（[InstallResultReceiver]）。成功したらアプリは入れ替わって終わるので、ここに来るのは失敗と取り消しだけ。 */
    internal fun onInstallFinished(success: Boolean, message: String?) {
        val release = (state.value as? UpdateState.Installing)?.release
        _state.value = when {
            success -> UpdateState.Idle
            release != null && message.isNullOrBlank() -> UpdateState.Available(release)
            else -> UpdateState.Failed("インストールできませんでした" + (message?.let { "（$it）" } ?: ""), release)
        }
    }

    private fun updatesDir(context: Context) = File(context.cacheDir, "updates").apply { mkdirs() }

    private fun fetchLatest(currentVersion: String): ReleaseInfo {
        val conn = (URL(LATEST_API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "PlamoTranslate/$currentVersion")
        }
        try {
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> Unit
                HttpURLConnection.HTTP_NOT_FOUND -> throw IOException("リリースが見つかりません")
                HttpURLConnection.HTTP_FORBIDDEN, 429 ->
                    throw IOException("GitHubへの問い合わせが多すぎます。しばらく（1時間ほど）待ってからもう一度押してください")
                else -> throw IOException("更新を確かめられませんでした（HTTP $code）")
            }
            return parseRelease(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /** GitHubのリリースのJSONから、APKのアセットを1つ選ぶ。SHA-256（digest）が無ければ照合できないので使わない。 */
    internal fun parseRelease(json: String): ReleaseInfo {
        val o = JSONObject(json)
        val assets = o.optJSONArray("assets") ?: throw IOException("リリースにファイルがありません")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val name = a.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            val digest = a.optString("digest")
            if (!digest.startsWith("sha256:")) throw IOException("APKのハッシュが公開されていないので、照合できません")
            return ReleaseInfo(
                tag = o.optString("tag_name"),
                pageUrl = o.optString("html_url", RELEASES_PAGE),
                notes = o.optString("body"),
                apkName = name,
                apkUrl = a.getString("browser_download_url"),
                apkSize = a.getLong("size"),
                apkSha256 = digest.removePrefix("sha256:"),
            )
        }
        throw IOException("リリースにAPKがありません")
    }

    /** 取ったAPKが、同じアプリの、新しい版で、同じ鍵で署名されているか。 */
    private fun verify(context: Context, apk: File) {
        val pm = context.packageManager
        val flags = PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
        val archive = pm.getPackageArchiveInfo(apk.path, flags) ?: throw IOException("取ったAPKを読めませんでした")
        val installed = pm.getPackageInfo(context.packageName, flags)
        if (archive.packageName != installed.packageName) throw IOException("別のアプリのAPKでした（${archive.packageName}）")
        if (archive.longVersionCode <= installed.longVersionCode) throw IOException("取ったAPKは今の版より新しくありません")
        val mine = signers(installed)
        if (mine.isEmpty() || signers(archive).none { it in mine }) {
            throw IOException("署名の鍵が今のアプリと違うので入れられません（開発版には、正式版で上書きできません）")
        }
    }

    private fun signers(p: PackageInfo): Set<String> {
        val info = p.signingInfo ?: return emptySet()
        val certs = if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        return certs.orEmpty().map { sig ->
            MessageDigest.getInstance("SHA-256").digest(sig.toByteArray()).joinToString("") { "%02x".format(it) }
        }.toSet()
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("update.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            // 結果は InstallResultReceiver に届く。確認画面の Intent を受け取るため、PendingIntent は変更可にする
            val pending = PendingIntent.getBroadcast(
                context, id, Intent(context, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(pending.intentSender)
        }
    }
}
