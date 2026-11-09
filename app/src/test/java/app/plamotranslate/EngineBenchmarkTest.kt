package app.plamotranslate

import org.junit.Assert.assertEquals
import org.junit.Test

/** 速さの計測の計算（設定の「速さを測る」）。 */
class EngineBenchmarkTest {
    @Test
    fun speedExcludesTimeToFirstToken() {
        // 最初の文字まで 500ms、その後の 10 トークンに 1000ms → 10 トークン/秒
        assertEquals(10.0, EngineBenchmark.Result(firstMillis = 500, tokens = 11, totalMillis = 1500).tokensPerSecond, 1e-9)
    }

    @Test
    fun speedIsZeroWhenNothingWasGeneratedAfterFirstToken() {
        assertEquals(0.0, EngineBenchmark.Result(firstMillis = 500, tokens = 1, totalMillis = 500).tokensPerSecond, 0.0)
    }
}
