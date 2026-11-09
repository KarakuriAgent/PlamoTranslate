package app.plamotranslate.models

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 「モデルの準備」の画面を出すかの判定（MainActivity）。 */
class DownloadStateTest {
    private val file = ModelFile(label = "x", url = "https://example.invalid/x", path = "x", size = 1, sha256 = "0".repeat(64))

    @Test
    fun downloadingRequiredBundleNeedsSetup() {
        assertTrue(DownloadState.Running(ModelBundle.REQUIRED, file, 0, 1).needsSetup())
    }

    @Test
    fun downloadingBundlesFromSettingsKeepsHomeScreen() {
        // 2026-11-09：読み上げのモデルを取っている間にトップ画面へ戻ると、準備の画面が出ていた
        assertFalse(DownloadState.Running(ModelBundle.SPEECH, file, 0, 1).needsSetup())
        assertFalse(DownloadState.Running(ModelBundle.LARGE_TRANSLATION, file, 0, 1).needsSetup())
    }

    @Test
    fun missingRequiredFilesNeedSetup() {
        assertTrue(DownloadState.Idle(listOf(file)).needsSetup())
        assertTrue(DownloadState.Failed("切れました", listOf(file)).needsSetup())
        assertFalse(DownloadState.Idle(emptyList()).needsSetup())
        assertFalse(DownloadState.Completed.needsSetup())
    }
}
