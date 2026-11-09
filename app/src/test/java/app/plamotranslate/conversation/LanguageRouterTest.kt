package app.plamotranslate.conversation

import app.plamotranslate.asr.AsrModel
import app.plamotranslate.asr.SpeakerLanguages
import app.plamotranslate.llm.Language
import app.plamotranslate.llm.isChinese
import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageRouterTest {
    @Test
    fun 自動では相手が話した言語へ自分の発話を訳す() {
        val router = LanguageRouter()
        assertEquals(Direction(Language.JA, Language.KO), router.route("こんにちは", "ja")) // 初期値は韓国語
        assertEquals(Direction(Language.EN, Language.JA), router.route("Hello", "en"))
        assertEquals(Direction(Language.JA, Language.EN), router.route("元気です", "ja"))
    }

    @Test
    fun 途中結果では相手の言語を覚えない() {
        val router = LanguageRouter()
        assertEquals(Direction(Language.EN, Language.JA), router.preview("Hello", "en"))
        assertEquals(Language.KO, router.partner)
    }

    @Test
    fun 手動では判定が違っても固定した言語として扱う() {
        val router = LanguageRouter(PartnerMode.KOREAN)
        assertEquals(Direction(Language.KO, Language.JA), router.route("Hello", "en"))
        assertEquals(Direction(Language.JA, Language.KO), router.route("はい", "ja"))
    }

    @Test
    fun 中国語や判定なしは文字の種類で決める() {
        assertEquals(Language.JA, LanguageRouter.spokenLanguage("今日は晴れ", "zh"))
        assertEquals(Language.JA, LanguageRouter.spokenLanguage("天気", "yue"))
        assertEquals(Language.KO, LanguageRouter.spokenLanguage("안녕하세요", null))
        assertEquals(Language.EN, LanguageRouter.spokenLanguage("Thank you", null))
    }

    @Test
    fun 自動では中国語の判定を相手の言語にしない() {
        val router = LanguageRouter()
        assertEquals(Direction(Language.JA, Language.KO), router.route("天気", "zh"))
        assertEquals(Language.KO, router.partner)
    }

    @Test
    fun 中国語の相手では中国語と広東語の判定を相手の発話とする() {
        val router = LanguageRouter(PartnerMode.CHINESE)
        assertEquals(Direction(Language.ZH, Language.JA), router.route("你好", "zh"))
        assertEquals(Direction(Language.ZH_HANT, Language.JA), router.route("你好嗎", "yue"))
        assertEquals(Direction(Language.JA, Language.ZH), router.route("こんにちは", "ja"))
        // 仮名を含めば、中国語と判定されても日本語の聞き違いとみなす
        assertEquals(Direction(Language.JA, Language.ZH), router.route("今日は晴れ", "zh"))
    }

    @Test
    fun 繁体字の相手には自分の発話を繁体字へ訳す() {
        val router = LanguageRouter(PartnerMode.CHINESE_TRADITIONAL)
        assertEquals(Direction(Language.JA, Language.ZH_HANT), router.route("ありがとう", "ja"))
        assertEquals(Direction(Language.ZH_HANT, Language.JA), router.route("多謝", "yue"))
    }

    @Test
    fun 自分が韓国語なら日本語の相手へ訳す() {
        val router = LanguageRouter(self = Language.KO)
        assertEquals(Language.JA, router.partner) // 自動の初期値は候補の先頭（自分を除く）
        assertEquals(Direction(Language.KO, Language.JA, mine = true), router.route("안녕하세요", "ko"))
        assertEquals(Direction(Language.EN, Language.KO, mine = false), router.route("Hello", "en"))
        assertEquals(Direction(Language.KO, Language.EN, mine = true), router.route("감사합니다", "ko"))
    }

    @Test
    fun 自分と同じ言語は相手に選べず固定していたら自動に戻る() {
        assertEquals(false, PartnerMode.choices(Language.EN).contains(PartnerMode.ENGLISH))
        assertEquals(false, PartnerMode.choices(Language.ZH).any { it.fixed?.isChinese == true })
        val router = LanguageRouter(PartnerMode.ENGLISH)
        router.self = Language.EN
        assertEquals(PartnerMode.AUTO, router.mode)
        assertEquals("自動（日本語・韓国語）", router.mode.label(Language.EN))
    }

    @Test
    fun 自分が中国語なら中国語の判定を自分の発話とする() {
        val router = LanguageRouter(PartnerMode.KOREAN, self = Language.ZH)
        assertEquals(true, router.chinese)
        assertEquals(Direction(Language.ZH, Language.KO, mine = true), router.route("你好", "zh"))
        assertEquals(Direction(Language.ZH_HANT, Language.KO, mine = true), router.route("多謝", "yue"))
        assertEquals(Direction(Language.KO, Language.ZH, mine = false), router.route("안녕", "ko"))
    }

    @Test
    fun SenseVoiceで選べる言語は今までと同じ5つ() {
        val languages = ConversationLanguages.of(AsrModel.SENSE_VOICE)
        assertEquals(listOf(Language.JA, Language.KO, Language.EN, Language.ZH, Language.ZH_HANT), languages.all)
        assertEquals(listOf(Language.JA, Language.KO, Language.EN), languages.autoPartners)
        assertEquals("自動（韓国語・英語）", PartnerMode.AUTO.label(Language.JA, languages))
    }

    @Test
    fun Qwen3_ASRではドイツ語などを選べる() {
        val languages = ConversationLanguages.of(AsrModel.QWEN3_ASR)
        assertEquals(listOf(Language.JA, Language.KO, Language.EN, Language.ZH, Language.ZH_HANT), languages.all.take(5))
        assertEquals(true, Language.DE in languages.all)
        assertEquals(true, Language.TL in languages.all) // フィリピン語として聞き取る
        // PLaMoに無いポルトガル語などは出ない。中国語は自動の候補に入れない
        assertEquals(21, languages.all.size)
        assertEquals(false, languages.autoPartners.any { it.isChinese })
        assertEquals(true, PartnerMode.choices(Language.JA, languages).contains(PartnerMode(Language.DE)))
    }

    @Test
    fun Qwen3_ASRではドイツ語の判定を言語のコードで決める() {
        val router = LanguageRouter(PartnerMode(Language.DE), languages = ConversationLanguages.of(AsrModel.QWEN3_ASR))
        assertEquals(Direction(Language.DE, Language.JA), router.route("Guten Tag", "de"))
        assertEquals(Direction(Language.JA, Language.DE), router.route("こんにちは", "ja"))
        // 判定が無いラテン文字の文は、英語ではなくドイツ語（ラテン文字の相手）とみなす
        assertEquals(Language.DE, LanguageRouter.spokenLanguage("Guten Tag", null, latin = Language.DE))
        assertEquals(Direction(Language.DE, Language.JA), router.route("Guten Tag", null))
    }

    @Test
    fun Qwen3_ASRの自動では話した言語を相手として覚える() {
        val router = LanguageRouter(languages = ConversationLanguages.of(AsrModel.QWEN3_ASR))
        assertEquals(Direction(Language.FR, Language.JA), router.route("Bonjour", "fr"))
        assertEquals(Direction(Language.JA, Language.FR), router.route("こんにちは", "ja"))
        // PLaMoに無い言語の判定（ポルトガル語）は、文字の種類で決める
        assertEquals(Language.FR, LanguageRouter.spokenLanguage("Olá", "pt", latin = Language.FR))
    }

    @Test
    fun SenseVoiceに戻したら選べない言語は日本語と自動に戻す() {
        val router = LanguageRouter(PartnerMode(Language.DE), self = Language.FR, languages = ConversationLanguages.of(AsrModel.QWEN3_ASR))
        router.languages = ConversationLanguages.SENSE_VOICE
        assertEquals(Language.JA, router.self)
        assertEquals(PartnerMode.AUTO, router.mode)
        assertEquals(Language.KO, router.partner)
    }

    @Test
    fun SenseVoiceに戻しても選べる言語はそのまま() {
        val router = LanguageRouter(PartnerMode.ENGLISH, self = Language.KO, languages = ConversationLanguages.of(AsrModel.QWEN3_ASR))
        router.languages = ConversationLanguages.SENSE_VOICE
        assertEquals(Language.KO, router.self)
        assertEquals(PartnerMode.ENGLISH, router.mode)
    }

    @Test
    fun Canaryの言語は相手を固定したときだけ選べる() {
        val languages = ConversationLanguages.of(AsrModel.CANARY)
        val senseVoice = ConversationLanguages.SENSE_VOICE
        assertEquals(senseVoice.all + listOf(Language.ES, Language.DE, Language.FR), languages.all)
        assertEquals(listOf(Language.ES, Language.DE, Language.FR), languages.fixedOnly)
        // 自分の言語と自動の相手はSenseVoiceのときと同じ
        assertEquals(senseVoice.all, languages.selves)
        assertEquals(senseVoice.autoPartners, languages.autoPartners)
        assertEquals(true, PartnerMode.choices(Language.JA, languages).contains(PartnerMode(Language.DE)))
    }

    @Test
    fun Parakeetの言語は欧州の15言語を足す() {
        val languages = ConversationLanguages.of(AsrModel.PARAKEET)
        assertEquals(20, languages.all.size)
        assertEquals(15, languages.fixedOnly.size)
        assertEquals(true, Language.PL in languages.fixedOnly && Language.RU in languages.fixedOnly)
        assertEquals(ConversationLanguages.SENSE_VOICE.all, languages.selves)
    }

    @Test
    fun Qwen3_ASRでも自分の言語はSenseVoiceの言語だけ() {
        val languages = ConversationLanguages.of(AsrModel.QWEN3_ASR)
        assertEquals(ConversationLanguages.SENSE_VOICE.all, languages.selves)
        assertEquals(true, Language.DE in languages.autoPartners)
        assertEquals(emptyList<Language>(), languages.fixedOnly)
        val router = LanguageRouter(self = Language.KO, languages = languages)
        router.languages = ConversationLanguages.of(AsrModel.QWEN3_ASR)
        assertEquals(Language.KO, router.self)
    }

    @Test
    fun 音声認識に伝える会話の言語() {
        val router = LanguageRouter(PartnerMode(Language.DE), languages = ConversationLanguages.of(AsrModel.CANARY))
        assertEquals(SpeakerLanguages("ja", "de"), router.speakers)
        router.mode = PartnerMode.CHINESE_TRADITIONAL
        assertEquals(SpeakerLanguages("ja", "yue"), router.speakers)
        assertEquals(true, router.speakers.separateChinese)
        router.mode = PartnerMode.AUTO
        assertEquals(SpeakerLanguages("ja", null), router.speakers)
    }
}
