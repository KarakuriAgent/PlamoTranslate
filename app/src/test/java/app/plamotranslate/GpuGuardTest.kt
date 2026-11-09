package app.plamotranslate

import android.app.ApplicationExitInfo
import app.plamotranslate.GpuGuard.Decision
import app.plamotranslate.GpuGuard.ExitKind
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * GPUで落ちたときにCPUへ切り替える判定（[GpuGuard]）。2026-11-09、ROG Phone（Snapdragon 8 Gen 3）で
 * llama.cppのOpenCLが `exit(1)` し、読み込みのたびにアプリが再起動を繰り返した報告から。
 */
class GpuGuardTest {
    @Test
    fun exitDuringGpuLoadIsAbnormal() {
        // ggml-opencl のカーネルの組み立ての失敗は exit(1)
        assertEquals(ExitKind.ABNORMAL, GpuGuard.classify(ApplicationExitInfo.REASON_EXIT_SELF, 1))
        assertEquals(ExitKind.ABNORMAL, GpuGuard.classify(ApplicationExitInfo.REASON_CRASH_NATIVE, 6))
        assertEquals(ExitKind.ABNORMAL, GpuGuard.classify(ApplicationExitInfo.REASON_SIGNALED, 6))
        assertEquals(ExitKind.ABNORMAL, GpuGuard.classify(ApplicationExitInfo.REASON_ANR, 0))
    }

    @Test
    fun stopsByUserOrSystemAreNotAbnormal() {
        assertEquals(ExitKind.OTHER, GpuGuard.classify(ApplicationExitInfo.REASON_USER_REQUESTED, 0))
        assertEquals(ExitKind.OTHER, GpuGuard.classify(ApplicationExitInfo.REASON_LOW_MEMORY, 0))
        assertEquals(ExitKind.OTHER, GpuGuard.classify(ApplicationExitInfo.REASON_SIGNALED, 9))
        assertEquals(ExitKind.OTHER, GpuGuard.classify(ApplicationExitInfo.REASON_EXIT_SELF, 0))
    }

    @Test
    fun abnormalExitDisablesGpuAtOnce() {
        assertEquals(Decision.Disable("落ちた"), GpuGuard.decide(pending = true, interruptions = 0, exit = ExitKind.ABNORMAL, exitLabel = "落ちた"))
    }

    @Test
    fun otherStopsDisableGpuOnlyWhenRepeated() {
        assertEquals(Decision.Retry(1), GpuGuard.decide(pending = true, interruptions = 0, exit = ExitKind.OTHER, exitLabel = "強制停止"))
        assertEquals(Decision.Retry(1), GpuGuard.decide(pending = true, interruptions = 0, exit = null, exitLabel = "記録なし"))
        val second = GpuGuard.decide(pending = true, interruptions = 1, exit = ExitKind.OTHER, exitLabel = "強制停止")
        assertEquals(true, second is Decision.Disable)
    }

    @Test
    fun nothingPendingKeepsGpu() {
        assertEquals(Decision.Normal, GpuGuard.decide(pending = false, interruptions = 1, exit = ExitKind.ABNORMAL, exitLabel = ""))
    }
}
