package app.plamotranslate.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.WindowManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * MediaProjectionの画面を常にImageReaderで受け、最新の1枚だけを持っておく（docs/05_画面翻訳.md）。
 * Android 14以降は1つの許可で仮想ディスプレイを1回しか作れないため、サービスの間ずっと使い続ける。
 */
class ScreenCapturer(context: Context, projection: MediaProjection) {
    private val thread = HandlerThread("ScreenCapturer").apply { start() }
    private val handler = Handler(thread.looper)
    private val reader: ImageReader
    private val display: VirtualDisplay

    // handler のスレッドだけが触る
    private var latest: Image? = null
    private var latestAt = 0L

    init {
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val dpi = context.resources.displayMetrics.densityDpi
        reader = ImageReader.newInstance(bounds.width(), bounds.height(), PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ r ->
            val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
            latest?.close()
            latest = image
            latestAt = SystemClock.uptimeMillis()
        }, handler)
        display = projection.createVirtualDisplay(
            "PlamoTranslate", bounds.width(), bounds.height(), dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler,
        ) ?: error("仮想ディスプレイを作れませんでした")
    }

    /**
     * [since]（uptimeMillis）より後に届いた画面を1枚返す。自分の表示を消してから呼ぶと、消えた後の画面が取れる。
     * 画面が止まっていて新しい1枚が来ない場合は、待ち時間の上限で手元の最新を使う。
     */
    suspend fun capture(since: Long, timeoutMillis: Long = 500): Bitmap? {
        val deadline = SystemClock.uptimeMillis() + timeoutMillis
        while (true) {
            val bitmap = onHandler {
                val image = latest ?: return@onHandler null
                if (latestAt <= since && SystemClock.uptimeMillis() < deadline) null else image.toBitmap()
            }
            if (bitmap != null) return bitmap
            if (SystemClock.uptimeMillis() >= deadline && onHandler { latest == null }) return null
            delay(16)
        }
    }

    fun release() {
        handler.post {
            display.release()
            latest?.close()
            latest = null
            reader.close()
            thread.quitSafely()
        }
    }

    private suspend fun <T> onHandler(block: () -> T): T = suspendCancellableCoroutine { cont ->
        handler.post { cont.resume(block()) }
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes[0]
        // 行の末尾に詰め物があるので、行幅で作ってから切り出す
        val rowWidth = plane.rowStride / plane.pixelStride
        val padded = Bitmap.createBitmap(rowWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer.rewind())
        return if (rowWidth == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
    }
}
