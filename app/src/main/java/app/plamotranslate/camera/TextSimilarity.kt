package app.plamotranslate.camera

/**
 * カメラのコマごとに揺れる読み取りを、同じ文として扱うための比べ方（docs/10_カメラ翻訳.md「追跡と多数決」）。
 * 行の多数決（[LineTracker]）と訳の控え（[LiveTranslator]）で共通に使う。
 */
internal object TextSimilarity {
    /** 控えや比較のキー。文字と数字だけを残して小文字にする（コマごとの空白・記号の読み揺れを吸収する）。 */
    fun key(text: String): String = text.filter { it.isLetterOrDigit() }.lowercase()

    /** 2文字ずつの組の重なり（Dice係数、0〜1）。 */
    fun dice(a: String, b: String): Double {
        if (a == b) return 1.0
        if (a.length < 2 || b.length < 2) return 0.0
        val ga = a.windowed(2).groupingBy { it }.eachCount()
        val gb = b.windowed(2).groupingBy { it }.eachCount()
        val common = ga.entries.sumOf { (g, n) -> minOf(n, gb[g] ?: 0) }
        return common * 2.0 / (a.length - 1 + b.length - 1)
    }

    /**
     * ほぼ同じ文か（キーどうしで比べる）。Dice係数が8割以上で、長さの差が3割未満。
     * 1〜2文字の文は完全一致だけ（短い語は1文字違えば別の語なので）。
     */
    fun similar(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length < 3 || b.length < 3) return false
        if (minOf(a.length, b.length) * 10 < maxOf(a.length, b.length) * 7) return false
        return dice(a, b) >= 0.8
    }
}
