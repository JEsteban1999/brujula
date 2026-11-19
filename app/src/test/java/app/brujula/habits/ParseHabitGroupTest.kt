package app.brujula.habits

import org.junit.Assert.assertEquals
import org.junit.Test

class ParseHabitGroupTest {
    @Test fun parsesKnownAndFallsBackToEssential() {
        assertEquals(HabitGroup.WELLNESS, parseHabitGroup("WELLNESS"))
        assertEquals(HabitGroup.ESSENTIAL, parseHabitGroup("ESSENTIAL"))
        assertEquals(HabitGroup.ESSENTIAL, parseHabitGroup(null))
        assertEquals(HabitGroup.ESSENTIAL, parseHabitGroup("basura"))
    }
}
