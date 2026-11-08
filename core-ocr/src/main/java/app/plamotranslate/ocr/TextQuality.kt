package app.plamotranslate.ocr

/**
 * 文字認識の結果が崩れているかを見分ける。崩れた文字列は訳しても意味がなく、生成の時間だけを使う
 * （2026-11-05、日韓が混ざる行を韓国語の認識器が読むと「Otohi그 7>지가igr<그DE가」のようになった）。
 */
object TextQuality {
    // 「~」は漫画の語尾（말이야~）で普通に使うので含めない（2026-11-05評価）
    private const val SYMBOLS = "<>#\$*^|\\{}_=@"

    fun looksGarbled(text: String): Boolean {
        val compact = text.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return true

        // 普通の文では出にくい記号が多い
        val symbols = compact.count { it in SYMBOLS }
        if (symbols >= 2 && symbols.toDouble() / compact.length > 0.04) return true

        // 1語の中でハングルとラテン文字が混ざる語が多い（「Otohi그」「7>지가igr」など）。
        // 数字とハングルの組み合わせ（「2학년」「3일간」）は普通なので数えない
        val words = text.split(Regex("\\s+")).filter { it.length >= 2 }
        if (words.size >= 2) {
            val mixed = words.count { w ->
                ScriptDetector.hasHangul(w) && w.any { it in 'A'..'Z' || it in 'a'..'z' }
            }
            if (mixed.toDouble() / words.size >= 0.2) return true
        }
        return false
    }
}
