package app.plamotranslate

import android.content.Context
import app.plamotranslate.llm.Language
import app.plamotranslate.llm.TranslationEvent
import app.plamotranslate.llm.TranslationRequest

/** 翻訳の速さを測る（設定の「速さを測る」）。決まった文を訳し、最初の文字までの時間と生成の速さを出す。 */
object EngineBenchmark {
    /** 測った結果。[firstMillis] は依頼から最初の文字まで（プロンプトの処理を含む）。 */
    data class Result(val firstMillis: Long, val tokens: Int, val totalMillis: Long) {
        /** 生成の速さ（最初の文字の後）。 */
        val tokensPerSecond: Double
            get() = if (tokens > 1 && totalMillis > firstMillis) (tokens - 1) * 1000.0 / (totalMillis - firstMillis) else 0.0
    }

    /** 今の翻訳モデルで測る。モデルが読めない・訳が終わらなければ null。 */
    suspend fun run(context: Context): Result? {
        val engine = EngineHolder.awaitEngine(context) ?: return null
        val request = TranslationRequest(
            "明日の朝は九時に駅の北口で待ち合わせて、そのまま美術館へ向かいましょう。昼ご飯は近くの店を予約してあります。",
            Language.JA, Language.EN,
        )
        val start = System.nanoTime()
        var first = 0L
        var done: TranslationEvent.Done? = null
        engine.translate(request, maxTokens = 64).collect { e ->
            when (e) {
                is TranslationEvent.Delta -> if (first == 0L) first = System.nanoTime() - start
                is TranslationEvent.Done -> done = e
            }
        }
        val d = done ?: return null
        return Result(first / 1_000_000, d.tokens, d.millis)
    }
}
