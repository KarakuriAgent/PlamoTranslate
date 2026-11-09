package app.plamotranslate.llm

/** 日本語へ訳すときの文体。 */
enum class JapaneseForm(val code: String) { PLAIN("plain"), POLITE("polite") }

data class TranslationRequest(
    val text: String,
    /** null なら原文の言語をモデルに判定させる。 */
    val source: Language?,
    val target: Language,
    val form: JapaneseForm = JapaneseForm.POLITE,
    /**
     * 原文の背景（PFML3の context）。短い語だけでは意味が決まらないとき（機器のボタンの「정지」を停止と訳すなど）に渡す。
     * null なら書かない（docs/03_翻訳コア.md「PFML3プロンプト」）。
     */
    val context: String? = null,
)

/** PFML3形式のプロンプトを組み立てる。改行は入れず1行につなぐ。 */
object Pfml3PromptBuilder {
    private const val MARK = "<|plamo:"

    fun build(request: TranslationRequest): String = buildString {
        append("<|plamo:tag|>dataset<|plamo:msg|>translation")
        request.context?.takeIf { it.isNotBlank() }?.let { append("<|plamo:tag|>context<|plamo:msg|>").append(sanitize(it)) }
        append("<|plamo:tag|>input")
        request.source?.let { append("<|plamo:key|>lang<|plamo:val|>").append(it.code) }
        append("<|plamo:msg|>").append(sanitize(request.text))
        append("<|plamo:tag|>output<|plamo:key|>lang<|plamo:val|>").append(request.target.code)
        if (request.target == Language.JA) {
            append("<|plamo:key|>form<|plamo:val|>").append(request.form.code)
        }
        append("<|plamo:msg|>")
    }

    /** 原文に区切り文字列が混ざるとプロンプトが壊れるので取り除く。 */
    internal fun sanitize(text: String): String = text.replace(MARK, "").trim()
}
