package app.plamotranslate.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {
    @Test
    fun parsesTagAndVersionName() {
        assertEquals(listOf(0, 4, 0), AppVersion.parse("v0.4.0"))
        assertEquals(listOf(0, 4, 0), AppVersion.parse("0.4.0"))
        assertEquals(listOf(1, 2), AppVersion.parse("v1.2-beta"))
        assertNull(AppVersion.parse("latest"))
    }

    @Test
    fun comparesNumbersNotText() {
        assertTrue(AppVersion.isNewer("v0.4.1", "0.4.0"))
        assertTrue(AppVersion.isNewer("v0.10.0", "0.9.9"))
        assertTrue(AppVersion.isNewer("v1.0", "0.9.9"))
        assertFalse(AppVersion.isNewer("v0.4.0", "0.4.0"))
        assertFalse(AppVersion.isNewer("v0.4", "0.4.0"))
        assertFalse(AppVersion.isNewer("v0.3.9", "0.4.0"))
    }

    @Test
    fun unreadableVersionIsNeverNewer() {
        assertFalse(AppVersion.isNewer("nightly", "0.4.0"))
        assertFalse(AppVersion.isNewer("v0.5.0", ""))
    }
}
