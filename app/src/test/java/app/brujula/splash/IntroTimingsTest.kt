package app.brujula.splash

import org.junit.Assert.assertTrue
import org.junit.Test

class IntroTimingsTest {
    @Test fun withinBudget() {
        assertTrue(IntroTimings.totalMs(reduceMotion = false) <= 2000)
        assertTrue(IntroTimings.totalMs(reduceMotion = true) <= 1200)
    }

    @Test fun systemSplashWithinLimit() {
        assertTrue(IntroTimings.SYSTEM_MS <= 800)
    }
}
