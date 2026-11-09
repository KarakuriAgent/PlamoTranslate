package app.plamotranslate.overlay

import android.content.Context
import android.util.TypedValue
/** 文字の大きさ（sp）を画素数にする。端末の文字の大きさの設定に従う。 */
internal fun Context.spToPx(value: Float): Float =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, resources.displayMetrics)

internal fun Context.dp(value: Int): Int =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()
