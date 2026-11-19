package app.brujula.splash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplashGeometryTest {
    @Test fun tileMatchesSplashIconScale() {
        assertEquals(160f, SplashGeometry.TILE_DP, 0.5f)
    }

    @Test fun introOnlyOnColdStart() {
        assertTrue(shouldPlayIntro(alreadyShownInProcess = false, restoredFromSavedState = false))
        assertFalse(shouldPlayIntro(alreadyShownInProcess = true, restoredFromSavedState = false))
        assertFalse(shouldPlayIntro(alreadyShownInProcess = false, restoredFromSavedState = true))
        assertFalse(shouldPlayIntro(alreadyShownInProcess = true, restoredFromSavedState = true))
    }
}
