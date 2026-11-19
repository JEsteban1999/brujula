package app.brujula.today

import app.brujula.activities.Activity
import app.brujula.activities.ActivityType
import app.brujula.activities.OccurrenceOverride
import app.brujula.activities.Repeat
import app.brujula.activities.occurrencesOn
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class OccurrenceSubtitleTest {
    private val day = LocalDate.of(2026, 11, 18)
    private val work = Activity(1, "Trabajo", ActivityType.WORK, day, LocalTime.of(10, 0), 60, Repeat.DAILY)

    @Test fun subtitleUsesSeriesDurationWithoutOverride() {
        assertEquals("Trabajo · 1 h", occurrenceSubtitle(occurrencesOn(day, listOf(work), emptySet()).single()))
    }

    @Test fun subtitleUsesOverriddenDuration() {
        val o = mapOf((1L to day) to OccurrenceOverride(1, day, day.atTime(10, 0), 45, false))
        assertEquals("Trabajo · 45 min", occurrenceSubtitle(occurrencesOn(day, listOf(work), emptySet(), o).single()))
    }
}
