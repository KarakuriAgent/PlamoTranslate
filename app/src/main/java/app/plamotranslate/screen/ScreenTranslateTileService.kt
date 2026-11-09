package app.plamotranslate.screen

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.plamotranslate.screenTranslateIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * クイック設定（通知欄を下ろしたところ）の「画面翻訳」タイル（2026-11-06）。アプリを開かずに始め・止められる。
 * 始めるときは画面共有の許可が要るので、トップ画面を経由して許可を取る。止めるときはその場で止める。
 */
class ScreenTranslateTileService : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main).also { scope = it }
        s.launch { ScreenTranslateService.running.collect { update(it) } }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
    }

    override fun onClick() {
        if (ScreenTranslateService.running.value) {
            ScreenTranslateService.stop(this)
            return
        }
        val intent = screenTranslateIntent(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            // Android 13（最低版）では PendingIntent 版が無いので、Intent 版を使う
            @Suppress("DEPRECATION")
            @SuppressLint("StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }

    private fun update(running: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (running) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = if (running) "動作中" else null
        tile.updateTile()
    }
}
