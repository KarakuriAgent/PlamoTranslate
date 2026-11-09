package app.plamotranslate.llm

/**
 * PLaMo 3 Translateが扱う53言語（モデルカード「Supported Languages」、2026-11-08確認）。
 * [code] はPFML3の lang 属性に書く値（docs/03_翻訳コア.md）。
 * 並びは選択肢に出す順：よく使う5つを先に、残りは五十音順。
 * どの機能でどの言語を選べるかは、前段の認識器が読める・聞き分けられる言語で決まる（カメラ翻訳・会話翻訳）。
 * `app` の中では、カメラ翻訳・会話翻訳・読み上げの言語もこの型で持つ（画面翻訳の原文は認識器の種類 OcrLanguage で持ち、
 * 翻訳へ渡すときにこの型にする）。`core-asr`・`core-tts` は言語をコード（文字列）で、`core-ocr` は OcrLanguage で受けるので、
 * 境界で変換する（docs/11「9」、2026-11-09に独立したファイルへ移した）。
 */
enum class Language(val code: String, val label: String) {
    JA("ja", "日本語"),
    KO("ko", "韓国語"),
    EN("en", "英語"),
    ZH("zh-hans", "中国語（簡体字）"),
    ZH_HANT("zh-hant", "中国語（繁体字）"),
    AZ("az", "アゼルバイジャン語"),
    AR("ar", "アラビア語"),
    HY("hy", "アルメニア語"),
    IT("it", "イタリア語"),
    ID("id", "インドネシア語"),
    UZ("uz", "ウズベク語"),
    UR("ur", "ウルドゥー語"),
    ET("et", "エストニア語"),
    NL("nl", "オランダ語"),
    KK("kk", "カザフ語"),
    CA("ca", "カタルーニャ語"),
    KM("km", "クメール語"),
    GU("gu", "グジャラート語"),
    HR("hr", "クロアチア語"),
    JV("jv", "ジャワ語"),
    KA("ka", "ジョージア語"),
    SI("si", "シンハラ語"),
    ES("es", "スペイン語"),
    SK("sk", "スロバキア語"),
    SL("sl", "スロベニア語"),
    SW("sw", "スワヒリ語"),
    CEB("ceb", "セブアノ語"),
    SR("sr", "セルビア語"),
    TH("th", "タイ語"),
    TL("tl", "タガログ語"),
    TA("ta", "タミル語"),
    TE("te", "テルグ語"),
    DE("de", "ドイツ語"),
    NE("ne", "ネパール語"),
    HU("hu", "ハンガリー語"),
    EU("eu", "バスク語"),
    PA("pa", "パンジャブ語"),
    HI("hi", "ヒンディー語"),
    MY("my", "ビルマ語"),
    FR("fr", "フランス語"),
    BG("bg", "ブルガリア語"),
    HE("he", "ヘブライ語"),
    VI("vi", "ベトナム語"),
    FA("fa", "ペルシア語"),
    BE("be", "ベラルーシ語"),
    BN("bn", "ベンガル語"),
    PL("pl", "ポーランド語"),
    MR("mr", "マラーティー語"),
    ML("ml", "マラヤーラム語"),
    MS("ms", "マレー語"),
    LV("lv", "ラトビア語"),
    LT("lt", "リトアニア語"),
    RU("ru", "ロシア語"),
    ;

    companion object {
        /** PFML3の言語コードから引く。知らないコードなら null。 */
        fun ofCode(code: String): Language? = entries.firstOrNull { it.code == code }
    }
}

/** 中国語（簡体字・繁体字）か。 */
val Language.isChinese: Boolean get() = this == Language.ZH || this == Language.ZH_HANT

/** 同じ話し手の言語とみなすか。簡体字と繁体字（広東語）は、どちらも同じ中国語の話し手として扱う。 */
fun Language.sameAs(other: Language): Boolean = this == other || (isChinese && other.isChinese)
