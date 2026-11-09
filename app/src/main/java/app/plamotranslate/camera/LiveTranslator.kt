package app.plamotranslate.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import app.plamotranslate.EngineHolder
import app.plamotranslate.camera.TextSimilarity.key
import app.plamotranslate.camera.TextSimilarity.similar
import app.plamotranslate.llm.Language
import app.plamotranslate.ocr.MultiScriptOcr
import app.plamotranslate.ocr.OcrLanguage
import app.plamotranslate.overlay.BatchTranslator
import app.plamotranslate.overlay.BoxColors
import app.plamotranslate.overlay.selectTargets
import app.plamotranslate.screen.SCREEN_TARGET
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * カメラの1コマの訳の枠。[rect] は正立した画像（[LiveFrame.rotation]、[CameraGeometry]）の座標。
 * [translation] が null ならまだ訳していない。
 */
data class LiveBox(
    val rect: Rect,
    val source: String,
    val colors: BoxColors,
    val translation: String?,
    /** 画面に見えている範囲に入っているか（文字認識の画像は4:3で、縦長の画面からは左右がはみ出す）。 */
    val onScreen: Boolean = true,
)

/**
 * 表示する1コマ：縦持ちの向きの画像（一時停止のときにそのまま出す）と、その上の訳の枠。
 * [rotation] は端末の傾き（時計回り、0・90・180・270）。文字認識と枠の座標は、[bitmap] をこの角度だけ回した正立した画像のもの
 * （2026-11-06、作者の要望「横向きでも読みたい」）。
 */
class LiveFrame(
    val bitmap: Bitmap,
    val boxes: List<LiveBox>,
    val ocrMillis: Long,
    val rotation: Int = 0,
    /** 表・メニューのモードか（訳の枠を広げずに描く）。 */
    val table: Boolean = false,
) {
    fun with(boxes: List<LiveBox>, table: Boolean = this.table) = LiveFrame(bitmap, boxes, ocrMillis, rotation, table)

    /** 正立した画像の幅と高さ。 */
    val uprightWidth: Int get() = if (rotation % 180 == 0) bitmap.width else bitmap.height
    val uprightHeight: Int get() = if (rotation % 180 == 0) bitmap.height else bitmap.width
}

/**
 * カメラ翻訳の中身（2026-11-06、docs/10_カメラ翻訳.md）。部品を組み合わせて、コマごとの読み取りと翻訳の順番を回す。
 *
 * 1. 文字認識：コマを正立させて1行ずつ読む（[MultiScriptOcr] の groupLines=false）。
 * 2. 追跡と多数決：行をコマをまたいで追い、読み取りを安定させる（[LineTracker]）。
 * 3. 段落：安定した行を、文章／表・メニューのモードに合わせてまとめる（[Paragraphs]）。表示と翻訳はこの単位。
 * 4. 翻訳：読み取りがそろった段落を、画面に見えているもの・中央に近いものから訳す（[translateLoop]）。
 *    訳は原文のキー（[TextSimilarity.key]）で控え、ほぼ同じ文にも使い回す。翻訳（1文0.1〜1.4秒）は文字認識
 *    （1コマ0.1〜0.5秒）より遅いので、同じ文を訳し直さない。
 *
 * 状態はすべてメインスレッドで触る。
 */
class LiveTranslator(private val context: Context, scope: CoroutineScope) {
    // 1行ずつ読む（段落にまとめると読み揺れで揃わない）。漫画モデルは縦書きの漫画用で遅いので使わない
    private val ocr = MultiScriptOcr(mangaDir = null, groupLines = false)
    private val tracker = LineTracker()
    private val _frame = MutableStateFlow<LiveFrame?>(null)
    val frame: StateFlow<LiveFrame?> = _frame.asStateFlow()

    /** 原文の言語。null は自動（すべての認識器で読み、PLaMoが原文の言語を判定する）。 */
    var source: Language? = null
        set(value) {
            if (field != value) {
                field = value
                forgetTranslations()
                tracker.clear()
            }
        }

    /** 訳す先の言語（2026-11-08、作者の要望で日本語以外も選べるようにした）。変えたら前の言語の訳は捨てる。 */
    var target: Language = SCREEN_TARGET
        set(value) {
            if (field != value) {
                field = value
                forgetTranslations()
                refresh()
                if (paused) translateAll()
            }
        }

    /** 動かす認識器。原文の言語から決める（CameraLanguage.kt）。 */
    private val recognizers: OcrLanguage get() = source?.ocrLanguage ?: OcrLanguage.AUTO

    /** 文章のモード（行を段落にまとめる）か、表・メニューのモード（1行ずつ）か。 */
    var groupParagraphs = true
        set(value) {
            // 背景（context）の有無で訳が変わるので、前のモードの訳は使い回さない
            if (field != value) forgetTranslations()
            field = value
            _frame.value?.let { _frame.value = it.with(boxes(includeUnsure = paused), table = !value) }
            if (paused) translateAll()
        }

    /** 一時停止中か。一時停止の後に終わったコマの読み取りは捨てる（止めた画面の枠を差し替えないため）。 */
    var paused = false

    /** 画面（映像の表示）の大きさ。画面に見えている範囲の計算に使う。呼ぶ側が表示の大きさが決まったら入れる。 */
    @Volatile var viewWidth = 0
    @Volatile var viewHeight = 0

    private var rotation = 0

    /** 画面に見えている範囲（正立した画像の座標）。分からなければ null。 */
    private var screen: RectF? = null

    /** 訳し終えた文（キー→訳）。 */
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CACHE_SIZE
    }

    /** 訳している途中の文（キー→途中までの訳）。 */
    private val partial = HashMap<String, String>()

    /**
     * 訳したい文（キー→原文）。映している間は、そのコマで読み取りがそろった文で入れ替える（画面から外れた文は訳さない）。
     * 一時停止中は足すだけで消さない（2026-11-06、作者の指摘「一時停止で囲われたまま訳されない枠がある」。
     * 以前は最新の依頼1件だけを持っていて、一時停止の直後に終わったコマの小さな依頼が「全部訳す」を上書きしていた）。
     */
    private val wanted = LinkedHashMap<String, String>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var translator: BatchTranslator? = null

    init {
        scope.launch { translateLoop() }
    }

    /**
     * 1コマを読む。終わるまで次のコマは渡さないこと（呼ぶ側で間引く）。
     * [bitmap] は縦持ちの向きの画像、[rotation] は端末の傾き（時計回り、0・90・180・270）。
     */
    suspend fun process(bitmap: Bitmap, rotation: Int = 0) {
        // 向きが変わったら座標の向きも変わるので、追っていた行は捨てる
        if (rotation != this.rotation) {
            this.rotation = rotation
            tracker.clear()
        }
        val upright = if (rotation == 0) bitmap else Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, CameraGeometry.portraitToUpright(bitmap.width, bitmap.height, rotation), false,
        )
        val t0 = SystemClock.uptimeMillis()
        val readings = try {
            // カメラでは1文字だけの読み取りを捨てる（韓国語のラベルを中国語の認識器が読み違えた「太」「我」などの雑音が多い）
            selectTargets(ocr.recognize(upright, recognizers), upright, statusBarHeight = 0, target = SCREEN_TARGET)
                .filter { t -> t.sourceText.count { it.isLetter() } >= 2 }
                .map { Reading(it.block.bounds, it.sourceText, it.slot.colors) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "カメラ翻訳: 文字認識に失敗", e)
            return
        } finally {
            if (upright !== bitmap) upright.recycle()
        }
        val ocrMillis = SystemClock.uptimeMillis() - t0
        if (paused) return
        screen = CameraGeometry.visibleRect(bitmap.width, bitmap.height, rotation, viewWidth.toFloat(), viewHeight.toFloat())
        tracker.update(readings)
        _frame.value = LiveFrame(bitmap, boxes(), ocrMillis, rotation, table = !groupParagraphs)

        // すべての行の読み取りがそろった段落のうち、まだ訳していないものを訳す
        wanted.clear()
        untranslated(paragraphs(includeUnsure = false).filter { it.settled }).forEach { wanted[key(it)] = it }
        if (wanted.isNotEmpty()) wake.trySend(Unit)
    }

    /** 一時停止したとき：見えている段落を、読み取りがそろうのを待たずに全部訳す。 */
    fun translateAll() {
        // 見えている文を先頭に、すでに頼んでいた文はその後ろに残す
        val before = LinkedHashMap(wanted)
        wanted.clear()
        untranslated(paragraphs(includeUnsure = true)).forEach { wanted[key(it)] = it }
        before.forEach { (k, v) -> wanted.putIfAbsent(k, v) }
        if (wanted.isNotEmpty()) wake.trySend(Unit)
        // 1回しか読めていない行も、一時停止中は出す
        _frame.value?.let { _frame.value = it.with(boxes(includeUnsure = true)) }
    }

    fun close() = ocr.close()

    private fun paragraphs(includeUnsure: Boolean): List<Paragraph> =
        Paragraphs.group(tracker.lines(includeUnsure), table = !groupParagraphs)

    private fun boxes(includeUnsure: Boolean = false): List<LiveBox> = paragraphs(includeUnsure).map { p ->
        LiveBox(p.rect, p.text, p.lines.first().colors, translationOf(key(p.text)), onScreen(p.rect))
    }

    /**
     * まだ訳していない段落の原文を、訳す順に返す：画面に見えている段落を先に、その中では画面の中央に近い順
     * （2026-11-06、作者の要望「翻訳待ちは画面内の文章を優先に」）。画面の外の段落は最後。
     */
    private fun untranslated(ps: List<Paragraph>): List<String> {
        val s = screen
        val ordered = if (s == null) ps else ps.sortedWith(
            compareBy<Paragraph> { if (onScreen(it.rect)) 0 else 1 }
                .thenBy { hypot(it.rect.exactCenterX() - s.centerX(), it.rect.exactCenterY() - s.centerY()) },
        )
        return ordered.map { it.text }
            .filter { translationOf(key(it)) == null && key(it) !in partial }
            .distinctBy(::key)
    }

    private fun onScreen(r: Rect): Boolean = screen?.let { RectF.intersects(it, RectF(r)) } ?: true

    /** 訳を探す。読み取りはコマごとに1〜2文字揺れるので、ほぼ同じ文（[similar]）の訳も使う。 */
    private fun translationOf(key: String): String? {
        cache[key]?.let { return it }
        partial[key]?.takeIf { it.isNotBlank() }?.let { return it }
        return cache.entries.firstOrNull { similar(it.key, key) }?.value
            ?: partial.entries.firstOrNull { it.value.isNotBlank() && similar(it.key, key) }?.value
    }

    private fun forgetTranslations() {
        cache.clear()
        partial.clear()
    }

    /** 今のコマの枠に、訳の進み具合を入れ直す。 */
    private fun refresh() {
        val f = _frame.value ?: return
        _frame.value = f.with(f.boxes.map { it.copy(translation = translationOf(key(it.source))) })
    }

    private suspend fun translateLoop() {
        for (signal in wake) while (wanted.isNotEmpty()) {
            // 一度に訳すのは数件まで。訳している間に一時停止・カメラの移動で、頼む文が変わるため
            val batch = wanted.values.take(BATCH)
            batch.forEach { wanted.remove(key(it)) }
            val pending = batch.filter { translationOf(key(it)) == null && key(it) !in partial }
            if (pending.isEmpty()) continue
            val engine = EngineHolder.awaitEngine(context) ?: continue
            // モデルを切り替えたらエンジンが変わる。前のモデルの訳は捨てる
            if (translator != null && translator?.engine !== engine) cache.clear()
            // 表・メニューのモードは短い語ばかりなので、機器の表示などの短い表記だという背景を渡す
            // （2026-11-06、エアコンのリモコンで「송풍」が宋風→送風になった。効果は限られる）
            val t = BatchTranslator.reuseOrCreate(
                translator, engine, target, source, if (groupParagraphs) null else TABLE_CONTEXT,
            ).also { translator = it }
            val keys = pending.map(::key)
            keys.forEach { partial[it] = "" }
            val t0 = SystemClock.uptimeMillis()
            try {
                t.translate(
                    pending,
                    onDelta = { i, delta -> partial[keys[i]] = (partial[keys[i]] ?: "") + delta; refresh() },
                    onReplace = { i, text -> partial[keys[i]] = text; refresh() },
                )
                // 訳が空でも控える（控えないと、枠が「訳待ち」のまま残り続ける）
                keys.forEach { k -> cache[k] = partial.remove(k)?.trim()?.takeIf { it.isNotEmpty() } ?: "（訳せませんでした）" }
                Log.i(TAG, "カメラ翻訳: ${pending.size}件を${SystemClock.uptimeMillis() - t0}ms")
            } catch (e: Exception) {
                keys.forEach { partial.remove(it) }
                if (e is CancellationException) throw e
                Log.w(TAG, "カメラ翻訳: 翻訳に失敗", e)
            }
            refresh()
        }
    }

    private companion object {
        const val TAG = "PlamoTranslate"
        const val CACHE_SIZE = 300

        /** 1回に訳す段落の数。BatchTranslator が数段落を1回の依頼にまとめて訳すので、隣の段落も文脈としてモデルに見える。 */
        const val BATCH = 4

        /** 表・メニューのモードで翻訳に渡す背景（PFML3の context）。 */
        const val TABLE_CONTEXT = "家電や機器のボタン・表示画面、メニュー、看板、表などに書かれた短い表記。"
    }
}
