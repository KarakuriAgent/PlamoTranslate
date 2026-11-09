package app.plamotranslate.asr

/**
 * VADで話し始めと文の終わりを見つけ、発話を区切って認識器（[Transcriber]）で1発話ずつ認識する（docs/04_会話翻訳.md）。
 * 話している最中は、0.5秒ごとに話し始めからの音声を認識し直して途中結果を出す（疑似ストリーミング）。
 * 区切り方はどの認識モデルでも同じで、モデルの違いは [transcriber] の中に閉じる（2026-11-09、Qwen3-ASRを足すときに
 * SenseVoiceAsrから切り出した）。
 */
internal class SegmentingAsr(
    private val vad: VoiceDetector,
    private val transcriber: Transcriber,
) : AsrEngine {
    private val buffer = SpeechBuffer(PREROLL_SAMPLES, MAX_SPEECH_SAMPLES)

    @Volatile override var separateChinese = false

    /** 言語の比較用にまとめる（[sameGroup]）。中国語の相手でなければ、中国語・広東語は日本語と同じに扱う。 */
    private fun sameGroup(lang: String?): String? = sameGroup(lang, separateChinese)

    /** 前の途中結果を出してから、話している音声がどれだけ増えたか。 */
    private var sinceLastPartial = 0

    /** 途中結果を出す間隔。認識にかかった時間の3倍より短くはしない（認識で音声の処理が遅れないように）。 */
    private var partialInterval = MIN_PARTIAL_INTERVAL

    /** 会話の始めから受け取った音声のサンプル数。発話の始まりの時刻に使う。 */
    private var received = 0L

    /** ためている音声の先頭が、会話の始めから何サンプル目か。 */
    private var bufferStart = 0L

    // 文（VADが0.6秒の無音で区切る単位）の位置。どれも buffer の中の位置（サンプル）
    /** 今の文の始まり。 */
    private var phraseStart = 0

    /** 直前に終わった文の終わり。まだ無ければ -1。 */
    private var lastPhraseEnd = -1

    /** 直前に終わった文の言語。 */
    private var lastPhraseLang: String? = null

    /** 文が終わってから次の文が始まるまでの間（無音）にいるか。 */
    private var inGap = false
    private var gapSamples = 0

    /** 今の文の言語を、前の文と比べ終えたか。 */
    private var switchChecked = true

    /**
     * 発話を2段で区切る。VADは0.6秒の無音で「文」を区切り、カードは次のどれかで確定する。
     * - 文が終わってから2秒（[AsrEngine.END_SILENCE_SECONDS]）たっても次の文が始まらない（話し終わり）
     * - 次の文が前の文と違う言語で始まった（話し手が代わった。2秒を待たずに区切る）
     * - 14秒話し続けた（直近の一番静かなところで区切り、後ろの音声は次のカードの頭に残す）
     *
     * 2026-11-06、作者の要望で話し終わりを2秒にしたところ、話し手が2秒以内に代わると2つの言語が1枚にまとまり、
     * SenseVoiceが読めなくなった。言いよどみで同じ人の話が切れないことと、話し手の交代で分かれることの両方のため、
     * 区切りを文とカードの2段にした。
     *
     * VADは「話しているか」と「文の終わり」の判定だけに使い、認識する音声は自分でためた [buffer] から取る。
     * 途中結果と確定結果が同じ音声から作られ、区切っても音声が途切れない
     * （作者の指摘「15秒の区切りで、次のカードの話し始めが気持ち悪い」。VADの最長で区切ると、話し始めを検出し直す間の音声が抜けた）。
     */
    override fun accept(samples: FloatArray): List<AsrEvent> {
        if (closed) return emptyList()
        val out = mutableListOf<AsrEvent>()
        val (speaking, phraseEnded) = vad.accept(samples)
        received += samples.size

        if (!buffer.active) {
            if (!speaking && !phraseEnded) {
                buffer.idle(samples)
                return out
            }
            // 話し始め。VADの検出の遅れを補うため、直前の音声も頭に足す
            bufferStart = received - samples.size - buffer.prerollSize
            buffer.speaking(samples)
            out += AsrEvent.Speaking(true)
            startCard()
        } else {
            buffer.speaking(samples)
        }

        if (phraseEnded) {
            // 文の終わり：区切りの無音を少しだけ残した位置を、文の終わりとして覚える
            val end = (buffer.size - PHRASE_TAIL_DROP).coerceAtLeast(phraseStart)
            // 中身のない文（雑音の聞き違い）は言語が null になる。その場合は前の文の言語を引き継ぎ、
            // 雑音を挟んだ後の本当の話し手の交代も見分けられるようにする
            lastPhraseLang = (if (phraseStart == 0 && lastPartialLang != null) lastPartialLang else langOf(phraseStart, end))
                ?: lastPhraseLang
            lastPhraseEnd = end
            inGap = true
            gapSamples = 0
        } else if (inGap) {
            if (speaking) {
                // 次の文が始まった。1秒たまったら、前の文と言語が同じか確かめる
                inGap = false
                phraseStart = lastPhraseEnd
                switchChecked = false
            } else {
                gapSamples += samples.size
                if (gapSamples >= CARD_END_GAP_SAMPLES) {
                    // 話し終わり：最後の文の終わりまでを確定させる
                    finalOf(buffer.take(lastPhraseEnd))?.let(out::add)
                    buffer.clear()
                    out += AsrEvent.Speaking(false)
                    return out
                }
            }
        }

        if (!switchChecked && buffer.size - phraseStart >= SWITCH_CHECK_SAMPLES) {
            switchChecked = true
            val lang = sameGroup(langOf(phraseStart, buffer.size))
            if (lang != null && lastPhraseLang != null && lang != sameGroup(lastPhraseLang)) {
                // 話し手が代わった：前の文までを確定させ、今の文から新しいカードにする
                cut(lastPhraseEnd)?.let(out::add)
                cardLang = lang
            }
        }

        if (buffer.size >= SPLIT_AFTER_SAMPLES) {
            // 長く話し続けている：直近の一番静かなところで区切り、後ろの音声は次のカードの頭として残す
            val at = buffer.quietestPoint(buffer.size - SPLIT_SEARCH_SAMPLES, buffer.size - MIN_TAIL_SAMPLES, FRAME_SAMPLES)
            cut(at)?.let(out::add)
        } else {
            sinceLastPartial += samples.size
            if (sinceLastPartial >= partialInterval && buffer.size >= MIN_PARTIAL_SAMPLES) {
                sinceLastPartial = 0
                watchLanguage()?.let(out::add)
                partial()?.let(out::add)
            }
        }
        return out
    }

    /** このカードの言語（最初の2秒で決める）。言語の交代を見張る基準。 */
    private var cardLang: String? = null

    /** 直近の音声の言語が、カードの言語と続けて何回違ったか。 */
    private var mismatches = 0

    /**
     * 間を空けずに話し手が代わったときのため、話している最中も言語を見張る（2026-11-06、作者の要望
     * 「話している最中に言語の切り替わりを検知したら、音声の受け付けは続けたまま、カードを区切って訳す向きを切り替えて」）。
     * 直近1.5秒の言語が2回続けてカードの言語と違えば、話し手が代わったとみなし、直近の一番静かなところで区切る。
     * 1回だけで区切らないのは、外来語や固有名詞で言語の判定が一瞬揺れるため。
     */
    private fun watchLanguage(): AsrEvent.Final? {
        if (cardLang == null) {
            if (buffer.size >= CARD_LANG_SAMPLES) cardLang = sameGroup(langOf(0, CARD_LANG_SAMPLES))
            return null
        }
        if (buffer.size < CARD_LANG_SAMPLES + LANG_WINDOW_SAMPLES) return null
        val recent = sameGroup(langOf(buffer.size - LANG_WINDOW_SAMPLES, buffer.size))
        if (recent == null || recent == cardLang) {
            mismatches = 0
            return null
        }
        if (++mismatches < 2) return null
        val at = switchPoint(buffer.size - SWITCH_SEARCH_SAMPLES, buffer.size - SWITCH_SEARCH_END_SAMPLES, recent)
        val final = cut(at)
        cardLang = recent
        return final
    }

    /**
     * 言語が代わった位置を探す。[from]〜[to] を0.25秒ずつずらしながら1秒ずつ言語を判定し、最初に [newLang] に
     * なったところを継ぎ目とみなして、その前後の一番静かなところを返す。
     * 静かさだけで探すと、話し手の継ぎ目に間が無いとき、新しい言語の文の中の小さな間（「조금만」と「생각」の間など）を選び、
     * 新しい言語の最初の語が前のカードに入って消えた（2026-11-06）。
     */
    private fun switchPoint(from: Int, to: Int, newLang: String): Int {
        val start = from.coerceAtLeast(0)
        var s = start
        while (s <= to) {
            if (sameGroup(langOf(s, s + LANG_PROBE_SAMPLES)) == newLang) {
                return buffer.quietestPoint(s - FRAME_SAMPLES * 2, s + FRAME_SAMPLES * 6, FRAME_SAMPLES)
            }
            s += LANG_PROBE_STEP_SAMPLES
        }
        return buffer.quietestPoint(start, to, FRAME_SAMPLES)
    }

    /** 新しいカードを始めるときに、文の位置と途中結果をまっさらにする。 */
    private fun startCard() {
        sinceLastPartial = 0
        lastPartialText = ""
        lastPartialLang = null
        phraseStart = 0
        lastPhraseEnd = -1
        lastPhraseLang = null
        inGap = false
        gapSamples = 0
        switchChecked = true
        cardLang = null
        mismatches = 0
    }

    /** 話し続けたまま [at] で区切る。前を確定させ、後ろは次のカードの頭として残す。 */
    private fun cut(at: Int): AsrEvent.Final? {
        val final = finalOf(buffer.take(at))
        mismatches = 0
        // 区切った後ろは別の話し手の可能性があるので、カードの言語は決め直す（呼び出し側が分かっていれば上書きする）
        cardLang = null
        phraseStart = (phraseStart - at).coerceAtLeast(0)
        lastPhraseEnd = if (lastPhraseEnd > at) lastPhraseEnd - at else -1
        if (lastPhraseEnd < 0) lastPhraseLang = null
        sinceLastPartial = 0
        lastPartialText = ""
        lastPartialLang = null
        return final
    }

    /** ためた音声の [from]〜[to] の言語。短すぎて分からなければ null。 */
    private fun langOf(from: Int, to: Int): String? {
        if (to - from < MIN_PARTIAL_SAMPLES) return null
        return transcriber.language(buffer.slice(from, to))
    }

    override fun flush(): List<AsrEvent> {
        if (closed) return emptyList()
        vad.flush()
        if (!buffer.active) return emptyList()
        val out = mutableListOf<AsrEvent>()
        finalOf(buffer.snapshot())?.let(out::add)
        buffer.clear()
        out += AsrEvent.Speaking(false)
        return out
    }

    override fun reset() {
        if (closed) return
        vad.reset()
        buffer.clear()
        partialInterval = MIN_PARTIAL_INTERVAL
        received = 0
        startCard()
    }

    /** ためた音声の先頭 [samples] を確定した発話として認識する。 */
    private fun finalOf(samples: FloatArray): AsrEvent.Final? {
        val startedAt = bufferStart
        bufferStart += samples.size
        if (samples.size < MIN_PARTIAL_SAMPLES) return null
        val start = System.nanoTime()
        val (text, lang) = transcriber.transcribe(samples)
        if (text.isEmpty()) return null
        return AsrEvent.Final(
            Utterance(
                text = text,
                lang = lang,
                startMs = startedAt.coerceAtLeast(0) * 1000 / AsrEngine.SAMPLE_RATE,
                durationMs = samples.size * 1000L / AsrEngine.SAMPLE_RATE,
                recognizeMs = (System.nanoTime() - start) / 1_000_000,
            ),
        )
    }

    /** 話し始めからここまでの音声を認識し直す。 */
    private fun partial(): AsrEvent.Partial? {
        val start = System.nanoTime()
        val (text, lang) = transcriber.transcribe(buffer.snapshot())
        val costSamples = ((System.nanoTime() - start) / 1_000_000 * AsrEngine.SAMPLE_RATE / 1000).toInt()
        partialInterval = maxOf(MIN_PARTIAL_INTERVAL, costSamples * 3)
        lastPartialLang = lang
        // 話し終わってから区切るまでの無音の間は同じ文字が続くので、変わったときだけ知らせる
        if (text.isEmpty() || text == lastPartialText) return null
        lastPartialText = text
        return AsrEvent.Partial(text, lang)
    }

    /** 直前に出した途中結果。同じ文字を続けて出さないために持つ。 */
    private var lastPartialText = ""

    /** 直前の途中結果の言語。カードの最初の文の言語として使い、認識し直しを省く。 */
    private var lastPartialLang: String? = null

    /**
     * 解放した後に届いた呼び出しは何もしない（2026-11-08、15T Proで会話の画面を閉じたときに落ちた）。
     * 画面を閉じると、聞き取りを止めた後始末の [flush] が、解放より後に音声認識のスレッドへ回ってくることがある。
     * 呼び出しはすべて同じ1本のスレッドから来る前提なので、印は同期しなくてよい。
     */
    private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        vad.close()
        transcriber.close()
    }

    companion object {
        /** 文の終わりとみなす無音（VADの区切り）。言いよどみより長く、話し手の交代を拾える長さ。 */
        internal const val PHRASE_SILENCE_SECONDS = 0.6f

        /** 途中結果は0.5秒ごとに出す（2026-11-06、作者の要望「入力の状況が見えるように」）。 */
        private const val MIN_PARTIAL_INTERVAL = AsrEngine.SAMPLE_RATE / 2

        /** 短すぎる音声は認識しても空になりやすいので、0.4秒たまってから出す。 */
        private const val MIN_PARTIAL_SAMPLES = AsrEngine.SAMPLE_RATE * 4 / 10

        /** VADが話し始めを検出するまでの遅れを補う、直前の音声の長さ。 */
        private const val PREROLL_SAMPLES = AsrEngine.SAMPLE_RATE / 2

        /** 文の終わりで捨てる無音。0.2秒だけ残して語尾を切らないようにする。 */
        private val PHRASE_TAIL_DROP = ((PHRASE_SILENCE_SECONDS - 0.2f) * AsrEngine.SAMPLE_RATE).toInt()

        /** 文の終わりから、カードを確定させるまでに待つ無音。文の区切りと合わせて [AsrEngine.END_SILENCE_SECONDS] になる。 */
        private val CARD_END_GAP_SAMPLES = ((AsrEngine.END_SILENCE_SECONDS - PHRASE_SILENCE_SECONDS) * AsrEngine.SAMPLE_RATE).toInt()

        /** 次の文の言語を判定するのに使う長さ。短いと言語の判定が揺れる。 */
        private const val SWITCH_CHECK_SAMPLES = AsrEngine.SAMPLE_RATE

        /** カードの言語を決める、カードの最初の音声の長さ。 */
        private const val CARD_LANG_SAMPLES = AsrEngine.SAMPLE_RATE * 2

        /** 話している最中の言語の見張りに使う、直近の音声の長さ。 */
        private const val LANG_WINDOW_SAMPLES = AsrEngine.SAMPLE_RATE * 3 / 2

        /** 言語が代わったと分かったときに、区切る位置を探す範囲（今から何サンプル前〜何サンプル前）。 */
        private const val SWITCH_SEARCH_SAMPLES = AsrEngine.SAMPLE_RATE * 7 / 2
        private const val SWITCH_SEARCH_END_SAMPLES = AsrEngine.SAMPLE_RATE

        /** 言語の継ぎ目を探すときに、1回に判定する長さと、ずらす幅。 */
        private const val LANG_PROBE_SAMPLES = AsrEngine.SAMPLE_RATE
        private const val LANG_PROBE_STEP_SAMPLES = AsrEngine.SAMPLE_RATE / 4

        /**
         * 言語の比較用にまとめる。同じ話し手でも認識器の判定が揺れやすい言語の組を、1つの言語として扱う。
         * - 中国語と広東語は1つにまとめる（同じ人の話で判定が中国語と広東語の間で揺れても、カードを切らない）。
         *   さらに会話の相手が中国語でなければ（[separateChinese] が false）、日本語の聞き違い（漢字の多い日本語）として
         *   日本語と同じに扱う。
         * - 近い言語の組（[CONFUSABLE]）も1つにまとめる。Qwen3-ASRは1秒ほどの音声だと、スペイン語をポルトガル語、
         *   日本語を中国語と判定することがある（2026-11-09、PCで試験音声を流して確かめた）。
         * 表に無い言語はそのまま比べるので、認識器の言語が増えても、この表は揺れやすい組を足すだけでよい。
         */
        internal fun sameGroup(lang: String?, separateChinese: Boolean): String? = when (val group = CONFUSABLE[lang] ?: lang) {
            "zh" -> if (separateChinese) "zh" else "ja"
            else -> group
        }

        /** 判定が揺れやすい言語から、まとめる先の言語へ。 */
        private val CONFUSABLE = mapOf(
            "yue" to "zh",
            // ポルトガル語はPLaMoに無く会話では選べないが、スペイン語の聞き違いとして出る
            "pt" to "es",
            "ms" to "id",
        )

        /**
         * 長く話し続けたら、14秒たまった時点で区切る。区切る位置は直近4秒のうち一番静かな0.1秒
         * （息継ぎや語の切れ目）にし、その後ろの音声は次の発話の頭として残す。
         */
        private const val SPLIT_AFTER_SAMPLES = AsrEngine.SAMPLE_RATE * 14
        private const val SPLIT_SEARCH_SAMPLES = AsrEngine.SAMPLE_RATE * 4
        private const val MIN_TAIL_SAMPLES = AsrEngine.SAMPLE_RATE * 3 / 10
        private const val FRAME_SAMPLES = AsrEngine.SAMPLE_RATE / 10

        /** ためる音声の上限（念のため）。区切りより長くしておく。 */
        private const val MAX_SPEECH_SAMPLES = AsrEngine.SAMPLE_RATE * 20
    }
}
