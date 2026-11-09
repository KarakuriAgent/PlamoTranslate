package app.plamotranslate.conversation

import app.plamotranslate.asr.AsrModel
import app.plamotranslate.asr.SpeakerLanguages
import app.plamotranslate.llm.Language
import app.plamotranslate.llm.isChinese
import app.plamotranslate.llm.sameAs

/**
 * 会話で使える言語。音声認識モデルが聞き分けられる言語と、PLaMoが訳せる言語の共通部分で決まる
 * （翻訳モデルは53言語を訳せるが、ほかの言語は聞き取れない）。2026-11-08、作者の要望で中国語を相手に足し、
 * 自分の言語も日本語以外を選べるようにした。2026-11-09、ドイツ語の会話のために音声認識モデルを選べるようにし、
 * 選べる言語をモデルごとにした（SenseVoiceは日本語・韓国語・英語・中国語・広東語、Qwen3-ASRはドイツ語なども）。
 * 広東語はPLaMoの言語に無いので、繁体字の中国語として訳す（話し言葉の広東語ではなく書き言葉の中国語になる）。
 * 同じ日、SenseVoiceを主軸にして聞けない言語だけ補助のモデルに流す形にし、自分の言語はどの組でもSenseVoiceの言語だけにした
 * （自分の発話かどうかをSenseVoiceの言語タグで見分けるため。docs/04_会話翻訳.md「会話で選べる言語」）。
 *
 * [all] は相手に選べる言語で、並びは [Language] の順（よく使う5つが先）。[selves] は自分に選べる言語。
 * [autoPartners] は自動の相手の候補。中国語は自動判定に入れない（漢字の多い日本語の聞き違いで、自分の発話が
 * 中国語として扱われるため）。言語を判定できない補助のモデル（Canary・Parakeet）の言語も入れない。
 */
data class ConversationLanguages(
    val all: List<Language>,
    val autoPartners: List<Language>,
    val selves: List<Language> = all,
) {
    /** 相手を固定したときだけ選べる言語（自分にも自動の相手にもできない）。設定の画面の説明に出す。 */
    val fixedOnly: List<Language>
        get() = all.filter { it !in selves && it !in autoPartners && !it.isChinese }

    companion object {
        fun of(model: AsrModel): ConversationLanguages {
            val all = Language.entries.filter { it.asrCode in model.languages }
            return ConversationLanguages(
                all = all,
                autoPartners = all.filter { it.asrCode in model.autoLanguages && !it.isChinese },
                selves = all.filter { it.asrCode in model.selfLanguages },
            )
        }

        /** SenseVoice（既定のモデル）の言語。日本語・韓国語・英語・中国語（簡体字・繁体字）。自動の相手は日本語・韓国語・英語。 */
        val SENSE_VOICE = of(AsrModel.SENSE_VOICE)
    }
}

/**
 * 音声認識が言語を表すコード。PLaMoのコードと違うものだけ書き換える。
 * 繁体字の中国語は広東語の話し手として聞き取る（SenseVoice・Qwen3-ASRの "yue"）。音声認識に会話の言語を伝えるときにも使う
 * （[LanguageRouter.speakers]）。
 * タガログ語は、Qwen3-ASRのフィリピン語（"fil"。タガログ語を元にした標準語）として聞き取る。
 */
internal val Language.asrCode: String
    get() = when (this) {
        Language.ZH -> "zh"
        Language.ZH_HANT -> "yue"
        Language.TL -> "fil"
        else -> code
    }

/** ラテン文字で書く言語。言語の判定が無いとき、ラテン文字の文をどの言語とみなすかに使う（[LanguageRouter.spokenLanguage]）。 */
private val LATIN_SCRIPT = setOf(
    Language.EN, Language.IT, Language.ID, Language.UZ, Language.ET, Language.NL, Language.CA, Language.HR,
    Language.JV, Language.ES, Language.SK, Language.SL, Language.SW, Language.CEB, Language.TL, Language.DE,
    Language.HU, Language.EU, Language.FR, Language.VI, Language.PL, Language.MS, Language.LV, Language.LT, Language.AZ,
)

/** 会話の画面に出す言語名。中国語は繁体字に広東語も含むことを示す。 */
val Language.conversationLabel: String
    get() = when (this) {
        Language.ZH_HANT -> "中国語（繁体字・広東語）"
        else -> label
    }

/**
 * 相手の言語の決め方。自動は、相手が話した言語を覚えて、自分の発話をその言語へ訳す。
 * [fixed] が null なら自動。
 */
data class PartnerMode(val fixed: Language?) {
    val auto: Boolean get() = fixed == null

    /** 自動で相手とみなす言語。自分の言語は除く。 */
    fun autoCandidates(self: Language, languages: ConversationLanguages = ConversationLanguages.SENSE_VOICE): List<Language> =
        languages.autoPartners.filterNot { it.sameAs(self) }

    /** メニューに出す名前。自動の候補が多いとき（Qwen3-ASR）は並べ切れないので、まとめて書く。 */
    fun label(self: Language, languages: ConversationLanguages = ConversationLanguages.SENSE_VOICE): String {
        fixed?.let { return it.conversationLabel }
        val candidates = autoCandidates(self, languages)
        return if (candidates.size <= MAX_LISTED_CANDIDATES) "自動（${candidates.joinToString("・") { it.label }}）"
        else "自動（話した言語。最初は${candidates.first().label}）"
    }

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

        /** 自動の候補を名前に並べる上限。 */
        private const val MAX_LISTED_CANDIDATES = 3

        /** 自分が [self] のときに選べる相手。自分と同じ言語は除く。 */
        fun choices(self: Language, languages: ConversationLanguages = ConversationLanguages.SENSE_VOICE): List<PartnerMode> =
            listOf(AUTO) + languages.all.filterNot { it.sameAs(self) }.map(::PartnerMode)
    }
}

/** 1つの発話を、どの言語からどの言語へ訳すか。[mine] は自分の発話。 */
data class Direction(val source: Language, val target: Language, val mine: Boolean = source == Language.JA)

/**
 * 発話ごとに訳す方向を決める（docs/04_会話翻訳.md「言語の判定と訳す方向」）。
 * 自分の言語の発話は相手の言語へ、それ以外は自分の言語へ訳す。
 */
class LanguageRouter(
    mode: PartnerMode = PartnerMode.AUTO,
    self: Language = Language.JA,
    languages: ConversationLanguages = ConversationLanguages.SENSE_VOICE,
) {
    /** 自分の言語。変えたとき、相手が同じ言語なら自動に戻す。 */
    var self: Language = self
        set(value) {
            field = value
            if (mode.fixed?.sameAs(value) == true) mode = PartnerMode.AUTO
        }

    var mode: PartnerMode = mode

    /** 自動のときに覚えている相手の言語。 */
    private var lastPartner: Language? = null

    /**
     * 選べる言語（音声認識モデルで決まる）。変えたとき、自分の言語が選べなければ日本語に、固定した相手の言語が
     * 選べなければ自動に戻す（2026-11-09。補助のモデルでドイツ語を選んだ後にSenseVoiceへ戻したとき）。
     */
    var languages: ConversationLanguages = languages
        set(value) {
            field = value
            if (self !in value.selves) self = Language.JA
            if (mode.fixed?.let { it !in value.all } == true) mode = PartnerMode.AUTO
            if (lastPartner?.let { it !in value.all } == true) lastPartner = null
        }

    /** 今、自分の発話を訳す先の言語。自動でまだ相手が話していなければ、候補の先頭（日本語の自分なら韓国語）。 */
    val partner: Language
        get() = mode.fixed ?: lastPartner?.takeUnless { it.sameAs(self) } ?: mode.autoCandidates(self, languages).first()

    /**
     * 中国語を日本語の聞き違いとみなさず、中国語として扱うか。自分か相手が中国語のときだけ。
     * 音声認識が話し手の交代で区切るときも、このときだけ中国語を日本語と別に扱う。
     */
    val chinese: Boolean get() = self.isChinese || mode.fixed?.isChinese == true

    /**
     * 音声認識に伝える会話の言語（2026-11-09）。音声認識は、これで話し手の交代の比べ方と、補助のモデルへの振り分けを決める。
     * 中国語を日本語と別に扱うか（[chinese]）もここから決まる。
     */
    val speakers: SpeakerLanguages get() = SpeakerLanguages(self.asrCode, mode.fixed?.asrCode)

    /** 確定した発話の方向を決め、自動なら相手の言語を覚える。 */
    fun route(text: String, langCode: String?): Direction = preview(text, langCode).also {
        if (mode.auto && !it.mine) lastPartner = it.source
    }

    /** 話している最中の途中結果の方向。相手の言語は覚えない（判定が揺れやすいため）。 */
    fun preview(text: String, langCode: String?): Direction {
        // 言語の判定が無いラテン文字の文は、自分か相手のうちラテン文字の言語とみなす（どちらも違えば英語）
        val latin = listOf(self, partner).firstOrNull { it in LATIN_SCRIPT } ?: Language.EN
        val spoken = spokenLanguage(text, langCode, chinese, latin)
        // 中国語なら、簡体字か繁体字（広東語）かは聞き取った言語に従う
        if (spoken.sameAs(self)) return Direction(if (self.isChinese) spoken else self, partner, mine = true)
        val fixed = mode.fixed
        val source = if (fixed == null || (fixed.isChinese && spoken.isChinese)) spoken else fixed
        return Direction(source, self, mine = false)
    }

    companion object {
        /**
         * 認識器の言語判定を、会話で扱う言語に絞る。判定はSenseVoiceの言語タグか、Qwen3-ASRの言語名から作ったコード。
         * 中国語・広東語の判定は、自分か相手が中国語のとき（[chinese]）だけ中国語とみなす。仮名を含めば日本語の聞き違いとする。
         * そうでなければ、漢字の多い日本語の聞き違いとして、判定なしと同じく認識した文字の種類で決める（漢字だけなら日本語）。
         * 判定なしと、PLaMoに無い言語（Qwen3-ASRのポルトガル語など）も文字の種類で決める。ラテン文字の文は [latin] とみなす。
         */
        internal fun spokenLanguage(
            text: String,
            langCode: String?,
            chinese: Boolean = false,
            latin: Language = Language.EN,
        ): Language = when (langCode) {
            "zh" -> if (chinese && !hasKana(text)) Language.ZH else byScript(text, latin)
            "yue" -> if (chinese && !hasKana(text)) Language.ZH_HANT else byScript(text, latin)
            null -> byScript(text, latin)
            else -> Language.entries.firstOrNull { !it.isChinese && it.asrCode == langCode } ?: byScript(text, latin)
        }

        private fun hasKana(text: String) = text.any { it in '぀'..'ヿ' }

        private fun byScript(text: String, latin: Language): Language {
            var hangul = 0
            var letters = 0
            var cjk = 0
            for (c in text) {
                when {
                    c in '가'..'힣' || c in '㄰'..'㆏' -> hangul++
                    c in 'A'..'Z' || c in 'a'..'z' -> letters++
                    c in '぀'..'ヿ' || c in '一'..'鿿' -> cjk++
                }
            }
            return when {
                hangul > 0 && hangul >= cjk -> Language.KO
                letters > cjk -> latin
                else -> Language.JA
            }
        }
    }
}
