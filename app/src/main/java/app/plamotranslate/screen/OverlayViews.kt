package app.plamotranslate.screen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.SystemClock
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import app.plamotranslate.overlay.OverlaySlot
import app.plamotranslate.overlay.dp
import kotlin.math.abs

/** 状態バーの高さ（px）。撮影画像の座標は画面の左上が原点なので、この高さより上は状態バー。 */
internal fun Context.statusBarHeight(): Int {
    val id = resources.getIdentifier("status_bar_height", "dimen", "android")
    return if (id > 0) resources.getDimensionPixelSize(id) else 0
}

/** 訳の枠の見た目。画面の重ね表示と評価の描画（BenchActivity）でそろえる。 */
internal object OverlayStyle {
    /** 訳の文字の大きさの範囲（sp）。枠に収まる最大の大きさを選ぶ。 */
    const val MIN_SP = 7
    const val MAX_SP = 22

    /** 横書きの訳の枠の、左右の内側の余白（dp）。 */
    const val H_PADDING_DP = 2

    /** 認識した枠は文字にぴったりなので、少し広げて原文の端が見えないようにする。 */
    fun padded(context: Context, rect: Rect): Rect = Rect(rect).apply { inset(-context.dp(4), -context.dp(2)) }
}

private fun overlayParams(width: Int, height: Int, gravity: Int) = WindowManager.LayoutParams(
    width, height,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
    PixelFormat.TRANSLUCENT,
).apply { this.gravity = gravity }

/** 画面の端に浮かぶ丸いボタン。ドラッグで動かせる。タップで翻訳、長押しで言語のメニュー（終了もここ）。 */
@SuppressLint("ClickableViewAccessibility")
class FloatingButton(
    private val context: Context,
    private val windowManager: WindowManager,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    private val size = context.dp(56)
    private val params = overlayParams(size, size, Gravity.TOP or Gravity.START).apply {
        x = context.resources.displayMetrics.widthPixels - size - context.dp(8)
        y = context.resources.displayMetrics.heightPixels / 3
    }
    private val view = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(220, 98, 0, 238))
        }
        elevation = context.dp(6).toFloat()
    }

    init {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var longPressed = false
        val longPress = Runnable { longPressed = true; onLongPress() }
        view.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y
                    dragging = false; longPressed = false
                    view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        view.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        windowManager.updateViewLayout(view, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(longPress)
                    if (!dragging && !longPressed) onTap()
                }
                MotionEvent.ACTION_CANCEL -> view.removeCallbacks(longPress)
            }
            true
        }
    }

    /** ボタンの文字。自動なら「訳」、言語を決めたら「訳」の下に言語を小さく出す。 */
    fun setLabel(label: String) {
        view.text = label
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length > 1) 13f else 20f)
    }

    /** メニューを出す位置の目安（ボタンの左上）。 */
    val position: Pair<Int, Int> get() = params.x to params.y

    fun show() = windowManager.addView(view, params)
    fun remove() = runCatching { windowManager.removeView(view) }
    fun setVisible(visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }
}

/** 撮影した画面の座標そのままに置くための窓の設定。状態バーや切り欠きの分ずらされないようにする。 */
private fun absoluteParams(rect: Rect, flags: Int) = WindowManager.LayoutParams(
    rect.width(), rect.height(),
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    PixelFormat.TRANSLUCENT,
).apply {
    gravity = Gravity.TOP or Gravity.START
    x = rect.left
    y = rect.top
    fitInsetsTypes = 0
    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
}

/**
 * 訳を元の文字の位置に重ねる（docs/05_画面翻訳.md「訳文の重ね表示」）。
 * 訳1つにつき小さな窓を1つ置く。全画面の窓にすると下のアプリを操作できなくなるため。
 * 訳をタップすると原文（下の画面）が見えるよう半透明に切り替える。
 * 訳以外の場所に触れたら（スクロールなどで画面が変わるので）重ね表示を消す。
 */
class TranslationOverlay(private val context: Context, private val windowManager: WindowManager) {
    // 横書きは TextView、縦書きは VerticalTextView。訳の追記・置き換えだけ共通に扱う
    private val boxes = mutableListOf<View>()
    private var statusView: TextView? = null

    // 訳の枠に触れた時刻。自分の枠に触れたときも「外側の操作」として通知されるため、それと区別する
    private var lastBoxTouch = 0L

    /** [slots] の位置に空の訳の枠を並べる。[status] は画面上部の小さな表示。 */
    fun show(slots: List<OverlaySlot>, status: String) {
        clear()
        for (slot in slots) {
            val box = if (slot.vertical) VerticalTextView(context) else horizontalBox()
            // 元の画面の背景色と文字色にそろえる（2026-11-05、作者の要望）。透けると読みにくいので不透明
            box.setBackgroundColor(slot.colors.background)
            when (box) {
                is VerticalTextView -> box.textColor = slot.colors.text
                is TextView -> box.setTextColor(slot.colors.text)
            }
            watchTap(box)
            windowManager.addView(box, absoluteParams(OverlayStyle.padded(context, slot.rect), 0))
            boxes += box
        }
        showStatus(status)
    }

    private fun horizontalBox() = TextView(context).apply {
        setPadding(context.dp(OverlayStyle.H_PADDING_DP), 0, context.dp(OverlayStyle.H_PADDING_DP), 0)
        gravity = Gravity.CENTER_VERTICAL
        includeFontPadding = false
        // 枠に収まる大きさまで文字を自動で縮める
        setAutoSizeTextTypeUniformWithConfiguration(OverlayStyle.MIN_SP, OverlayStyle.MAX_SP, 1, TypedValue.COMPLEX_UNIT_SP)
    }

    /** 訳の枠をタップしたら原文と切り替える。触れた時刻は、外側の操作と区別するために控える。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun watchTap(box: View) {
        box.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) lastBoxTouch = SystemClock.uptimeMillis()
            false
        }
        // 半透明にして下の原文を透かす
        box.setOnClickListener { box.alpha = if (box.alpha > 0.5f) 0.08f else 1f }
    }

    fun appendTranslation(index: Int, delta: String) {
        when (val box = boxes.getOrNull(index)) {
            is VerticalTextView -> box.append(delta)
            is TextView -> box.append(delta)
        }
    }

    fun setTranslation(index: Int, text: String) {
        when (val box = boxes.getOrNull(index)) {
            is VerticalTextView -> box.text = text
            is TextView -> box.text = text
        }
    }

    /** 画面上部の中央に、件数・所要時間などを小さく出す。訳以外に触れると全体を消す合図にも使う。 */
    @SuppressLint("ClickableViewAccessibility")
    fun showStatus(text: String) {
        val view = statusView ?: TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(context.dp(10), context.dp(4), context.dp(10), context.dp(4))
            background = GradientDrawable().apply {
                setColor(Color.argb(200, 98, 0, 238))
                cornerRadius = context.dp(12).toFloat()
            }
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    // 訳の枠へのタップ（原文との切り替え）なら消さない
                    postDelayed({ if (SystemClock.uptimeMillis() - lastBoxTouch > 300) clear() }, 80)
                }
                if (e.actionMasked == MotionEvent.ACTION_UP) clear()
                true
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = context.dp(4)
            }
            windowManager.addView(this, params)
            statusView = this
        }
        view.text = "$text　（タップで消す）"
    }

    fun clear() {
        boxes.forEach { runCatching { windowManager.removeView(it) } }
        boxes.clear()
        statusView?.let { runCatching { windowManager.removeView(it) } }
        statusView = null
    }
}

/**
 * 浮遊ボタンの長押しで出すメニュー（2026-11-05、作者の要望）。原文の言語（自動・中国語・韓国語・英語）と終了を選ぶ。
 * メニューの外に触れたら閉じる。
 */
class LanguageMenu(private val context: Context, private val windowManager: WindowManager) {
    private var root: View? = null

    @SuppressLint("ClickableViewAccessibility")
    fun show(anchorX: Int, anchorY: Int, items: List<Pair<String, () -> Unit>>, selected: Int) {
        dismiss()
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(6), context.dp(6), context.dp(6), context.dp(6))
            background = GradientDrawable().apply {
                setColor(Color.argb(240, 32, 32, 40))
                cornerRadius = context.dp(12).toFloat()
            }
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) dismiss()
                false
            }
        }
        items.forEachIndexed { i, (label, action) ->
            list.addView(TextView(context).apply {
                text = if (i == selected) "● $label" else "　 $label"
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(context.dp(14), context.dp(10), context.dp(20), context.dp(10))
                setOnClickListener {
                    dismiss()
                    action()
                }
            })
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // ボタンの左に出す（画面の右端に寄せたボタンでも見切れないように）
            x = (anchorX - context.dp(170)).coerceAtLeast(context.dp(8))
            y = anchorY
        }
        windowManager.addView(list, params)
        root = list
    }

    fun dismiss() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
    }
}