package app.plamotranslate.conversation

import app.plamotranslate.llm.Language
import app.plamotranslate.llm.isChinese
import app.plamotranslate.llm.sameAs

/**
 * 会話で使える言語。音声認識（SenseVoice）が聞き分けられる日本語・韓国語・英語・中国語・広東語で決まる
 * （翻訳モデルは53言語を訳せるが、ほかの言語は聞き取れない）。2026-11-08、作者の要望で中国語を相手に足し、
 * 自分の言語も日本語以外を選べるようにした。広東語はPLaMoの言語に無いので、繁体字の中国語として訳す
 * （話し言葉の広東語ではなく書き言葉の中国語になる）。
 */
val CONVERSATION_LANGUAGES = listOf(Language.JA, Language.KO, Language.EN, Language.ZH, Language.ZH_HANT)

/** 会話の画面に出す言語名。中国語は繁体字に広東語も含むことを示す。 */
val Language.conversationLabel: String
    get() = when (this) {
        Language.ZH_HANT -> "中国語（繁体字・広東語）"
        else -> label
    }

/** 自動の相手の候補。中国語は自動判定に入れない（漢字の多い日本語の聞き違いで、自分の発話が中国語として扱われるため）。 */
private val AUTO_PARTNERS = listOf(Language.JA, Language.KO, Language.EN)

/**
 * 相手の言語の決め方。自動は、相手が話した言語を覚えて、自分の発話をその言語へ訳す。
 * [fixed] が null なら自動。
 */
data class PartnerMode(val fixed: Language?) {
    val auto: Boolean get() = fixed == null

    /** 自動で相手とみなす言語。自分の言語は除く。 */
    fun autoCandidates(self: Language): List<Language> = AUTO_PARTNERS.filterNot { it.sameAs(self) }

    fun label(self: Language): String =
        fixed?.conversationLabel ?: "自動（${autoCandidates(self).joinToString("・") { it.label }}）"

    fun short(): String = when (fixed) {
        null -> "自動"
        Language.ZH -> "中国語・簡"
        Language.ZH_HANT -> "中国語・繁"
        else -> fixed.label
    }

    companion object {
        val AUTO = PartnerMode(null)
        val KOREAN = PartnerMode(Language.KO)
        val ENGLISH = PartnerMode(Language.EN)
        val CHINESE = PartnerMode(Language.ZH)
        val CHINESE_TRADITIONAL = PartnerMode(Language.ZH_HANT)

        /** 自分が [self] のときに選べる相手。自分と同じ言語は除く。 */
        fun choices(self: Language): List<PartnerMode> =
            listOf(AUTO) + CONVERSATION_LANGUAGES.filterNot { it.sameAs(self) }.map(::PartnerMode)
    }
}

/** 1つの発話を、どの言語からどの言語へ訳すか。[mine] は自分の発話。 */
data class Direction(val source: Language, val target: Language, val mine: Boolean = source == Language.JA)

/**
 * 発話ごとに訳す方向を決める（docs/04_会話翻訳.md「言語の判定と訳す方向」）。
 * 自分の言語の発話は相手の言語へ、それ以外は自分の言語へ訳す。
 */
class LanguageRouter(mode: PartnerMode = PartnerMode.AUTO, self: Language = Language.JA) {
    /** 自分の言語。変えたとき、相手が同じ言語なら自動に戻す。 */
    var self: Language = self
        set(value) {
            field = value
            if (mode.fixed?.sameAs(value) == true) mode = PartnerMode.AUTO
        }

    var mode: PartnerMode = mode

    /** 自動のときに覚えている相手の言語。 */
    private var lastPartner: Language? = null

    /** 今、自分の発話を訳す先の言語。自動でまだ相手が話していなければ、候補の先頭（日本語の自分なら韓国語）。 */
    val partner: Language
        get() = mode.fixed ?: lastPartner?.takeUnless { it.sameAs(self) } ?: mode.autoCandidates(self).first()

    /**
     * 中国語を日本語の聞き違いとみなさず、中国語として扱うか。自分か相手が中国語のときだけ。
     * 音声認識が話し手の交代で区切るときも、このときだけ中国語を日本語と別に扱う。
     */
    val chinese: Boolean get() = self.isChinese || mode.fixed?.isChinese == true

    /** 確定した発話の方向を決め、自動なら相手の言語を覚える。 */
    fun route(text: String, langCode: String?): Direction = preview(text, langCode).also {
        if (mode.auto && !it.mine) lastPartner = it.source
    }

    /** 話している最中の途中結果の方向。相手の言語は覚えない（判定が揺れやすいため）。 */
    fun preview(text: String, langCode: String?): Direction {
        val spoken = spokenLanguage(text, langCode, chinese)
        // 中国語なら、簡体字か繁体字（広東語）かは聞き取った言語に従う
        if (spoken.sameAs(self)) return Direction(if (self.isChinese) spoken else self, partner, mine = true)
        val fixed = mode.fixed
        val source = if (fixed == null || (fixed.isChinese && spoken.isChinese)) spoken else fixed
        return Direction(source, self, mine = false)
    }

    companion object {
        /**
         * 認識器の言語判定を、会話で扱う言語に絞る。
         * 中国語・広東語の判定は、自分か相手が中国語のとき（[chinese]）だけ中国語とみなす。仮名を含めば日本語の聞き違いとする。
         * そうでなければ、漢字の多い日本語の聞き違いとして、判定なしと同じく認識した文字の種類で決める（漢字だけなら日本語）。
         */
        internal fun spokenLanguage(text: String, langCode: String?, chinese: Boolean = false): Language = when (langCode) {
            "ja" -> Language.JA
            "ko" -> Language.KO
            "en" -> Language.EN
            "zh" -> if (chinese && !hasKana(text)) Language.ZH else byScript(text)
            "yue" -> if (chinese && !hasKana(text)) Language.ZH_HANT else byScript(text)
            else -> byScript(text)
        }

        private fun hasKana(text: String) = text.any { it in '぀'..'ヿ' }

        private fun byScript(text: String): Language {
            var hangul = 0
            var latin = 0
            var cjk = 0
            for (c in text) {
                when {
                    c in '가'..'힣' || c in '㄰'..'㆏' -> hangul++
                    c in 'A'..'Z' || c in 'a'..'z' -> latin++
                    c in '぀'..'ヿ' || c in '一'..'鿿' -> cjk++
                }
            }
            return when {
                hangul > 0 && hangul >= cjk -> Language.KO
                latin > cjk -> Language.EN
                else -> Language.JA
            }
        }
    }
}
