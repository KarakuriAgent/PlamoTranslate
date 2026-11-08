package app.plamotranslate.screen

import app.plamotranslate.llm.Language
import app.plamotranslate.llm.TranslationEngine
import app.plamotranslate.llm.TranslationEvent
import app.plamotranslate.llm.TranslationRequest

/**
 * 画面のブロックを数行ずつまとめて1回で訳す。
 * 1ブロックずつ訳すと毎回プロンプト処理の固定費がかかり、1画面（20〜30ブロック）で20秒を超えた（2026-11-05実測）。
 * PLaMoは改行を保ったまま訳すので、1ブロック＝1行にして送り、訳文を改行で分けて各ブロックへ戻す。
 * 行数が合わないときは、そのまとまりだけ1ブロックずつ訳し直す。
 *
 * [source] は原文の言語。null なら PLaMo に判定させる（自動）。訳の控えは原文の言語を前提にするので、
 * 言語を変えたら作り直す。
 */
class BatchTranslator(
    /** 訳すエンジン。モデルを切り替えるとエンジンが変わるので、呼ぶ側はこれが同じかを見て作り直す。 */
    val engine: TranslationEngine,
    val target: Language,
    val source: Language? = null,
    /** 原文の背景（PFML3の context）。カメラの表・メニューのモードで、短い語の意味を決める手がかりに渡す。 */
    val context: String? = null,
) {

    /** 同じ文を何度も訳さないための控え。画面を撮り直したときに効く。 */
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CACHE_SIZE
    }

    /**
     * [texts] を訳す。[onDelta] は訳文の続きを、[onReplace] は確定した訳文の全体を、それぞれブロック番号付きで受け取る。
     */
    suspend fun translate(
        texts: List<String>,
        onDelta: (index: Int, text: String) -> Unit,
        onReplace: (index: Int, text: String) -> Unit,
    ) {
        val pending = mutableListOf<Int>()
        for (i in texts.indices) {
            val cached = cache[texts[i]]
            if (cached != null) onReplace(i, cached) else pending += i
        }
        for (chunk in chunked(pending, texts)) {
            translateChunk(chunk, texts, onDelta, onReplace)
        }
    }

    private suspend fun translateChunk(
        chunk: List<Int>,
        texts: List<String>,
        onDelta: (Int, String) -> Unit,
        onReplace: (Int, String) -> Unit,
    ) {
        val joined = chunk.joinToString("\n") { texts[it] }
        val output = StringBuilder()
        var line = 0
        engine.translate(TranslationRequest(joined, source = source, target = target, context = context)).collect { event ->
            if (event !is TranslationEvent.Delta) return@collect
            output.append(event.text)
            // 改行のたびに次のブロックへ移る
            val parts = event.text.split('\n')
            parts.forEachIndexed { k, part ->
                if (k > 0) line++
                if (part.isNotEmpty()) chunk.getOrNull(line)?.let { onDelta(it, part) }
            }
        }
        val lines = output.toString().trim().split('\n').map { it.trim() }
        if (lines.size == chunk.size) {
            chunk.forEachIndexed { k, i ->
                cache[texts[i]] = lines[k]
                onReplace(i, lines[k])
            }
            return
        }
        // 行がずれたら、このまとまりは1つずつ訳し直す
        for (i in chunk) {
            val single = StringBuilder()
            onReplace(i, "")
            engine.translate(TranslationRequest(texts[i], source = source, target = target, context = context)).collect { event ->
                if (event is TranslationEvent.Delta) {
                    single.append(event.text)
                    onDelta(i, event.text)
                }
            }
            cache[texts[i]] = single.toString().trim()
        }
    }

    private fun chunked(indices: List<Int>, texts: List<String>): List<List<Int>> {
        val chunks = mutableListOf<MutableList<Int>>()
        var chars = 0
        for (i in indices) {
            val current = chunks.lastOrNull()
            if (current == null || current.size >= MAX_LINES || chars + texts[i].length > MAX_CHARS) {
                chunks += mutableListOf(i)
                chars = texts[i].length
            } else {
                current += i
                chars += texts[i].length
            }
        }
        return chunks
    }

    companion object {
        /**
         * [current] が同じエンジン・原文の言語・背景なら使い回し（訳の控えを生かす）、違えば作り直す。
         * モデルを切り替えるとエンジンが変わり、古いエンジンは閉じられて訳せないので、エンジンも必ず見る
         * （2026-11-06、見ずに使い回して画面翻訳・カメラ翻訳の訳が失敗し続けた）。
         */
        fun reuseOrCreate(
            current: BatchTranslator?,
            engine: TranslationEngine,
            target: Language,
            source: Language?,
            context: String? = null,
        ): BatchTranslator = current?.takeIf {
            it.engine === engine && it.target == target && it.source == source && it.context == context
        } ?: BatchTranslator(engine, target, source, context)

        private const val MAX_LINES = 8
        private const val MAX_CHARS = 400
        private const val CACHE_SIZE = 500
    }
}
