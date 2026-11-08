package app.plamotranslate.camera

import android.graphics.Matrix
import android.graphics.RectF

/**
 * カメラ翻訳の座標の変換（docs/10_カメラ翻訳.md「仕組み」）。描画（[CameraOverlayView]）と訳す順番（[LiveTranslator]）が
 * 同じ計算を使うよう1か所にまとめた。
 *
 * - 縦持ちの画像：カメラのコマを、画面を立てた向きに回したもの（幅 [w]・高さ [h]）。一時停止のときにそのまま出す。
 * - 正立した画像：縦持ちの画像を端末の傾き [rotation]（時計回り、0・90・180・270）だけ時計回りに回したもの。
 *   持っている人から見て文字が正立する。文字認識と、訳の枠の座標はこちら。
 * - 画面：映像（PreviewView）と一時停止の画像は、どちらも縦持ちの画像を「中央を切り抜いて全体を埋める」表示（大きさ [vw]×[vh]）。
 */
internal object CameraGeometry {
    /** 縦持ちの画像 → 正立した画像（Bitmap.createBitmap で回したときと同じ。回した後に左上を原点へ寄せる）。 */
    fun portraitToUpright(w: Int, h: Int, rotation: Int): Matrix {
        val m = Matrix().apply { postRotate(rotation.toFloat()) }
        val moved = RectF(0f, 0f, w.toFloat(), h.toFloat()).also { m.mapRect(it) }
        m.postTranslate(-moved.left, -moved.top)
        return m
    }

    /** 縦持ちの画像を画面に「中央を切り抜いて全体を埋める」ときの拡大率。 */
    fun scale(w: Int, h: Int, vw: Float, vh: Float): Float = maxOf(vw / w, vh / h)

    /** 正立した画像 → 画面。 */
    fun uprightToView(w: Int, h: Int, rotation: Int, vw: Float, vh: Float): Matrix {
        val m = Matrix().also { portraitToUpright(w, h, rotation).invert(it) }
        val s = scale(w, h, vw, vh)
        m.postScale(s, s)
        m.postTranslate((vw - w * s) / 2, (vh - h * s) / 2)
        return m
    }

    /** 画面に見えている範囲を、正立した画像の座標で返す。画面の大きさが分からなければ null。 */
    fun visibleRect(w: Int, h: Int, rotation: Int, vw: Float, vh: Float): RectF? {
        if (vw <= 0 || vh <= 0) return null
        val toView = uprightToView(w, h, rotation, vw, vh)
        val toUpright = Matrix().also { toView.invert(it) }
        return RectF(0f, 0f, vw, vh).also { toUpright.mapRect(it) }
    }
}
