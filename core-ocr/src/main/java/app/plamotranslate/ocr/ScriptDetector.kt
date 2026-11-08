package app.plamotranslate.ocr

/** 文字列に含まれる文字種を判定する。 */
object ScriptDetector {
    fun hasHangul(text: String) = text.any { it in '가'..'힣' || it in 'ᄀ'..'ᇿ' || it in '㄰'..'㆏' }

    fun hasKana(text: String) = text.any { it in '぀'..'ヿ' || it in 'ｦ'..'ﾟ' }

    fun hasHan(text: String) = text.any { it in '一'..'鿿' || it in '㐀'..'䶿' }

    fun hasLatin(text: String) = text.any { it in 'A'..'Z' || it in 'a'..'z' }

    /** 代表の文字種。ハングル→仮名→漢字→ラテン文字の順に優先する。 */
    fun detect(text: String): Script = when {
        hasHangul(text) -> Script.HANGUL
        hasKana(text) -> Script.KANA
        hasHan(text) -> Script.HAN
        hasLatin(text) -> Script.LATIN
        else -> Script.OTHER
    }
}
