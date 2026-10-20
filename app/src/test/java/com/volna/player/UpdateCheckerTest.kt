package com.volna.player

import com.volna.player.update.UpdateChecker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    @Test
    fun versionComparison() {
        // BuildConfig.VERSION_NAME is 1.1 in this release.
        assertFalse(UpdateChecker.isNewer("1.0"))
        assertFalse(UpdateChecker.isNewer("1.1"))
        assertTrue(UpdateChecker.isNewer("1.2"))
        assertTrue(UpdateChecker.isNewer("2.0"))
        assertTrue(UpdateChecker.isNewer("1.1.1"))
    }
}
