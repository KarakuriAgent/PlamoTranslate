package app.plamotranslate.llm

import org.junit.Assert.assertEquals
import org.junit.Test

/** CPUのスレッド数の既定（[TranslationEngine.threadsFor]）。周波数はkHz。 */
class ThreadCountTest {
    @Test
    fun 小さいコアが半分なら大きいコアの数にする() {
        // 15T Pro（Dimensity 9400+）：2.4GHz×4、3.3GHz×3、3.73GHz×1
        val freqs = List(4) { 2_400_000L } + List(3) { 3_300_000L } + 3_730_000L
        assertEquals(4, TranslationEngine.threadsFor(freqs))
    }

    @Test
    fun 小さいコアが半分より多ければコア数から2を引く() {
        // REDMAGIC 11 Pro（SM8850）：3.6GHz×6、4.6GHz×2
        val freqs = List(6) { 3_600_000L } + List(2) { 4_600_000L }
        assertEquals(6, TranslationEngine.threadsFor(freqs))
    }

    @Test
    fun 周波数が読めなければコア数から2を引く() {
        assertEquals(6, TranslationEngine.threadsFor(List(8) { 0L }))
        assertEquals(6, TranslationEngine.threadsFor(List(7) { 2_000_000L } + 0L))
    }

    @Test
    fun 全部同じコアならコア数から2を引く() {
        assertEquals(6, TranslationEngine.threadsFor(List(8) { 2_000_000L }))
        assertEquals(2, TranslationEngine.threadsFor(List(4) { 2_000_000L }))
    }

    @Test
    fun 大きいコアが少なくても2スレッドは使う() {
        // 小さいコア6、大きいコア2：小さいコアが半分より多いのでコア数−2（上限6）
        assertEquals(6, TranslationEngine.threadsFor(List(6) { 1_800_000L } + List(2) { 2_800_000L }))
        // 小さいコア1、大きいコア1：大きいコアの数は1だが、2を下回らない
        assertEquals(2, TranslationEngine.threadsFor(listOf(1_800_000L, 3_000_000L)))
    }
}
