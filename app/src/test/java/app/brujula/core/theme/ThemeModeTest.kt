package app.brujula.core.theme

import java.time.LocalTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeModeTest {
    private val wake = LocalTime.of(8, 15)

    @Test fun lightAndDarkIgnoreTime() {
        assertFalse(resolveDark(ThemeMode.LIGHT, LocalTime.of(23, 0), wake))
        assertTrue(resolveDark(ThemeMode.DARK, LocalTime.of(12, 0), wake))
    }

    @Test fun autoIsDarkFrom21UntilWake() {
        assertFalse(resolveDark(ThemeMode.AUTO, LocalTime.of(20, 59), wake))
        assertTrue(resolveDark(ThemeMode.AUTO, LocalTime.of(21, 0), wake))
        assertTrue(resolveDark(ThemeMode.AUTO, LocalTime.of(2, 0), wake))
        assertTrue(resolveDark(ThemeMode.AUTO, LocalTime.of(8, 14), wake))
        assertFalse(resolveDark(ThemeMode.AUTO, LocalTime.of(8, 15), wake))
    }
}
