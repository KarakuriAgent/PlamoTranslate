package app.plamotranslate.update

/**
 * 版（`0.4.0`、リリースのタグ `v0.4.0`）を比べる。数字の部分を左から順に比べ、足りない桁は0とみなす
 * （`0.4` と `0.4.0` は同じ）。数字でない後ろの部分（`-beta` など）は比べない。
 */
object AppVersion {
    /** `v0.4.0` → [0, 4, 0]。数字が1つも無ければ null。 */
    fun parse(text: String): List<Int>? {
        val core = text.trim().removePrefix("v").removePrefix("V").takeWhile { it.isDigit() || it == '.' }
        val parts = core.split('.').filter { it.isNotEmpty() }.map { it.toIntOrNull() ?: return null }
        return parts.ifEmpty { null }
    }

    /** [candidate] が [current] より新しいか。どちらかが読めなければ false（更新を勧めない）。 */
    fun isNewer(candidate: String, current: String): Boolean {
        val a = parse(candidate) ?: return false
        val b = parse(current) ?: return false
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }
}
