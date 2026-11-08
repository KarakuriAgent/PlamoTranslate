package app.plamotranslate

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context

/**
 * 文字をクリップボードへ写す。Android 13以降は、写したことをシステムが画面下に知らせるので、
 * アプリからは知らせない。
 */
fun Context.copyToClipboard(label: String, text: String) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
}
