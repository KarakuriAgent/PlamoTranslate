package app.plamotranslate.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log

/**
 * PackageInstallerのセッションの結果を受け取る（[AppUpdater]）。
 * 「作者の確認が要る」なら、Androidの確認画面を開く。成功するとアプリは入れ替わって終わる。
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> AppUpdater.onInstallFinished(true, null)
            // 確認画面で「キャンセル」を押した
            PackageInstaller.STATUS_FAILURE_ABORTED -> AppUpdater.onInstallFinished(false, null)
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.w("PlamoTranslate", "更新のインストールに失敗しました: $status $message")
                AppUpdater.onInstallFinished(false, message ?: "コード $status")
            }
        }
    }
}
