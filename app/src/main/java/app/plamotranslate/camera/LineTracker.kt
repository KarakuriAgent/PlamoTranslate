package app.plamotranslate.camera

import android.graphics.Rect
import app.plamotranslate.camera.TextSimilarity.dice
import app.plamotranslate.camera.TextSimilarity.key
import app.plamotranslate.camera.TextSimilarity.similar
import app.plamotranslate.ocr.Geometry
import app.plamotranslate.screen.BoxColors
import kotlin.math.abs

/** 1コマで読めた1行（正立した画像の座標）。 */
internal class Reading(val rect: Rect, val text: String, val colors: BoxColors)

/**
 * コマをまたいで同じ行を追い、多数決で読み取りを安定させる（2026-11-06、作者の要望「連続読み取りの特性を活かしてロバストに」。
 * docs/10_カメラ翻訳.md「追跡と多数決」）。
 *
 * - 各コマの行を、前のコマの位置が重なる行（IoU [MATCH_IOU] 以上）、なければ近くにある同じ内容の行に結び付ける。
 * - 行ごとに直近 [READINGS] 回の読み取りを持ち、ほかの読み取りといちばん似ているものを原文にする（[Track.consensus]）。
 * - 読めなかったコマが [KEEP_MISSED] 回までなら行を残す（1コマの読み落としで枠がちらつかないように）。
 *   ただし、そのコマで読めた行に重なる古い行は、カメラが動いてずれた枠なので消す。
 */
internal class LineTracker {
    private val tracks = mutableListOf<Track>()

    fun clear() = tracks.clear()

    /** このコマの読み取りを、前のコマまでの行に結び付ける。 */
    fun update(readings: List<Reading>) {
        tracks.forEach { it.visible = false }
        val free = tracks.toMutableList()
        for (r in readings) {
            val k = key(r.text)
            val match = free.maxByOrNull { Geometry.iou(it.rect, r.rect) }?.takeIf { Geometry.iou(it.rect, r.rect) >= MATCH_IOU }
                ?: free.firstOrNull { similar(key(it.consensus()), k) && near(it.rect, r.rect) }
            val track = match?.also { free.remove(it) } ?: Track().also { tracks += it }
            track.add(r)
        }
        for (t in free) t.missed++
        val seen = tracks.filter { it.missed == 0 }
        tracks.removeAll { t -> t.missed > KEEP_MISSED || (t.missed > 0 && seen.any { Rect.intersects(it.rect, t.rect) }) }
        tracks.filter { it.missed in 1..KEEP_MISSED }.forEach { it.visible = true }
    }

    /**
     * 今見えている行を、上から順に返す。[includeUnsure] が false なら、似た読み取りが [AGREE] 回そろった行だけ。
     * 同じ行が二重に読めた枠（小さい方の5割以上が重なる。認識器ごとに少しずれた枠ができる）は、
     * 読み取りが確かな方（このコマで読めた・多数決で同じ読み取りが多い）だけを残す。
     */
    fun lines(includeUnsure: Boolean): List<Track> {
        val kept = mutableListOf<Track>()
        tracks.filter { it.visible && (includeUnsure || it.count >= AGREE) }
            .sortedWith(compareBy<Track> { it.missed }.thenByDescending { it.agreement() }.thenByDescending { it.count })
            .forEach { t -> if (kept.none { Geometry.overlaps(it.rect, t.rect, 0.5f) }) kept += t }
        return kept.sortedBy { it.rect.top }
    }

    /** コマをまたいで追う1行。直近の読み取りを持ち、多数決で原文を決める。 */
    class Track {
        var rect = Rect()
            private set
        var colors = BoxColors.DEFAULT
            private set
        var visible = false
        var missed = 0
        var count = 0
            private set
        private val readings = ArrayDeque<String>()
        private var cached: String? = null

        fun add(r: Reading) {
            rect = Rect(r.rect)
            colors = r.colors
            visible = true
            missed = 0
            count++
            readings.addLast(r.text)
            if (readings.size > READINGS) readings.removeFirst()
            cached = null
        }

        /** ほかの読み取りといちばん似ている読み取り（同点なら新しいもの）。 */
        fun consensus(): String = cached ?: run {
            val keys = readings.map(::key)
            val best = readings.indices.maxByOrNull { i ->
                keys.indices.sumOf { j -> if (i == j) 0.0 else dice(keys[i], keys[j]) } + i * 1e-6
            } ?: 0
            readings[best]
        }.also { cached = it }

        /** 多数決で採った読み取りと、ほぼ同じ読み取りの数（自分を含む）。 */
        fun agreement(): Int {
            val c = key(consensus())
            return readings.count { similar(key(it), c) }
        }
    }

    companion object {
        /** 行ごとに持つ読み取りの数。 */
        const val READINGS = 8

        /** 訳す・表示するのに要る、ほぼ同じ読み取りの数。 */
        const val AGREE = 2

        /** 読めなかったコマが、これを超えたら行を捨てる。 */
        const val KEEP_MISSED = 2

        /** 前のコマの行と同じとみなす重なり。 */
        const val MATCH_IOU = 0.3f

        /** 中心の距離が近いか（縦は行の高さの3倍、横はその2倍以内。カメラを少し動かしたとき）。 */
        private fun near(a: Rect, b: Rect): Boolean {
            val limit = maxOf(a.height(), b.height()) * 3
            return abs(a.centerX() - b.centerX()) <= limit * 2 && abs(a.centerY() - b.centerY()) <= limit
        }
    }
}
