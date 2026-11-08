package app.plamotranslate.conversation

import app.plamotranslate.llm.Language
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
}
