package app.brujula.data

import java.time.LocalDateTime
import java.time.LocalDate
import org.junit.Assert.assertNull
import app.brujula.routine.BedtimeChoice
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.brujula.core.theme.ThemeMode
import app.brujula.routine.Routine
import app.brujula.routine.RoutineRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalTime

class RoutineRepositoryTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun TestScope.repo(file: File = File(tmp.root, "routine.preferences_pb")) =
        RoutineRepository(PreferenceDataStoreFactory.create(scope = backgroundScope) { file })

    @Test fun defaultIsRoutine() = runTest {
        assertEquals(Routine(), repo().routine.first())
    }

    @Test fun updateChangesWakeAndPersists() = runTest {
        val file = File(tmp.root, "r2.preferences_pb")
        val r = repo(file)
        r.update {
            it.copy(wake = LocalTime.of(7, 45), themeMode = ThemeMode.DARK, flexibleWork = false, workGoalMin = 300..360)
        }
        val read = r.routine.first()
        assertEquals(LocalTime.of(7, 45), read.wake)
        assertEquals(ThemeMode.DARK, read.themeMode)
        assertEquals(false, read.flexibleWork)
        assertEquals(300..360, read.workGoalMin)
        assertEquals(Routine().workBlocks, read.workBlocks)
    }

    @Test fun workBlocksRoundTrip() = runTest {
        val r = repo()
        val blocks = listOf(LocalTime.of(9, 0) to LocalTime.of(13, 30))
        r.update { it.copy(workBlocks = blocks) }
        assertEquals(blocks, r.routine.first().workBlocks)
        r.update { it.copy(workBlocks = emptyList()) }
        assertEquals(emptyList<Pair<LocalTime, LocalTime>>(), r.routine.first().workBlocks)
    }

    @Test fun restHoursDefaultAndPersist() = runTest {
        val r = repo()
        assertEquals(LocalTime.of(10, 0), r.routine.first().restWake)
        assertEquals(LocalTime.of(1, 0), r.routine.first().restBed)
        r.update { it.copy(restWake = LocalTime.of(11, 0), restBed = LocalTime.of(2, 0)) }
        assertEquals(LocalTime.of(11, 0), r.routine.first().restWake)
        assertEquals(LocalTime.of(2, 0), r.routine.first().restBed)
    }

    @Test fun nightNoticeDefaultsTo2245AndPersists() = runTest {
        val file = File(tmp.root, "night.preferences_pb")
        val r = repo(file)
        assertEquals(LocalTime.of(22, 45), r.routine.first().nightNotice)
        r.update { it.copy(nightNotice = LocalTime.of(21, 30)) }
        assertEquals(LocalTime.of(21, 30), r.routine.first().nightNotice)
        // Otros cambios de la rutina no lo pisan.
        r.update { it.copy(wake = LocalTime.of(7, 0)) }
        assertEquals(LocalTime.of(21, 30), r.routine.first().nightNotice)
    }

    @Test fun bedtimeChoiceRoundTrip() = runTest {
        val r = repo()
        assertNull(r.bedtimeChoice.first())
        val c = BedtimeChoice(LocalDate.of(2026, 11, 19), LocalDateTime.of(2026, 11, 20, 0, 15), 480, remind = true)
        r.setBedtimeChoice(c)
        assertEquals(c, r.bedtimeChoice.first())
        assertEquals(LocalDateTime.of(2026, 11, 19, 23, 45), r.bedtimeChoice.first()?.remindAt)
        r.setBedtimeChoice(c.copy(remind = false))
        assertEquals(false, r.bedtimeChoice.first()?.remind)
    }
}
