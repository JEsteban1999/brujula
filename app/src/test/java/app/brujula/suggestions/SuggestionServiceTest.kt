package app.brujula.suggestions

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.brujula.claude.DictationInterpreter
import app.brujula.claude.GatewayResult
import app.brujula.data.db.BrujulaDatabase
import app.brujula.habits.FasterStretch
import app.brujula.habits.HabitSchedule
import kotlinx.coroutines.flow.MutableStateFlow
import app.cash.turbine.test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertFalse
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalTime

@RunWith(RobolectricTestRunner::class)
class SuggestionServiceTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var db: BrujulaDatabase

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), BrujulaDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() { db.close() }

    private fun TestScope.harness() = SuggestionHarness(backgroundScope, tmp.root, db)
    private val now = SuggestionFixtures.now
    private val stretch = FasterStretch(LocalTime.of(15, 0), 60)

    /** Hábito de agua con 7 días de registros, 8 días desde la última corrida y 2 desde el último intento. */
    private suspend fun SuggestionHarness.due(): Long {
        val id = water()
        seedLogs(id, 7)
        store.markAttempt(now.minusDays(8), success = true)
        store.markAttempt(now.minusDays(2), success = false)
        answer(faster(id))
        return id
    }

    @Test fun autoRunsWhenDue() = runTest {
        val h = harness()
        h.due()
        h.service.maybeRunAuto()!!.join()
        assertEquals(1, h.gateway.suggestCalls)
        val entries = h.store.entries.first()
        assertEquals(listOf(SuggestionStatus.PENDING), entries.map { it.status })
        assertEquals(now, h.store.lastRun())
        assertEquals(now, h.store.lastAttempt())
        val card = h.service.card().first()!!
        assertEquals(false, card.accepted)
        assertEquals(entries.single().suggestion.message, card.message)
    }

    @Test fun autoSkipsWhenRecentNoKeyOfflineOrLittleData() = runTest {
        val h = harness()
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.service.running.collect { seen += it } }
        h.due()
        h.store.markAttempt(now.minusDays(3), success = true) // corrida reciente (y último intento hace 3 días)
        h.service.maybeRunAuto()!!.join()
        assertEquals("corrida hace 3 días", 0, h.gateway.suggestCalls)

        h.store.markAttempt(now.minusDays(9), success = true)
        h.key = false
        h.service.maybeRunAuto()!!.join()
        assertEquals("sin clave", 0, h.gateway.suggestCalls)

        h.key = true
        h.online = false
        h.service.maybeRunAuto()!!.join()
        assertEquals("sin red", 0, h.gateway.suggestCalls)

        // Poca información: solo 6 días con registros (se borra el de hace 7).
        h.online = true
        db.openHelper.writableDatabase.execSQL(
            "DELETE FROM habit_logs WHERE logicalDay = ?", arrayOf(now.toLocalDate().minusDays(7).toString()),
        )
        h.service.maybeRunAuto()!!.join()
        assertEquals("pocos datos", 0, h.gateway.suggestCalls)
        assertEquals(emptyList<StoredSuggestion>(), h.store.entries.first())
        // Sin llamada real no cuenta como intento.
        assertEquals(now.minusDays(9), h.store.lastAttempt())
        assertEquals("si una condición lo salta, nunca se anuncia 'mirando'", listOf(false), seen)
    }

    @Test fun runningOnlyDuringTheCall() = runTest {
        val h = harness()
        val seen = mutableListOf<Boolean>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.service.running.collect { seen += it } }
        h.due()
        h.service.maybeRunAuto()!!.join()
        assertEquals(listOf(false, true, false), seen)
    }

    @Test fun futureMarksDoNotBlockAuto() = runTest {
        val h = harness()
        val id = h.water()
        h.seedLogs(id, 7)
        h.answer(h.faster(id))
        h.store.markAttempt(now.plusDays(20), success = true) // reloj movido hacia atrás
        h.service.maybeRunAuto()!!.join()
        assertEquals(1, h.gateway.suggestCalls)
        assertEquals(now, h.store.lastRun())
    }

    @Test fun acceptedCardDropsAtRolloverWithoutStoreEmission() = runTest {
        val h = harness()
        val (_, sid) = h.pending()
        h.service.accept(sid)
        h.clock.current = now.toLocalDate().plusDays(1).atTime(3, 59)
        val ticks = MutableStateFlow(h.clock.current)
        h.service.card(ticks).test {
            assertTrue(awaitItem()!!.accepted)
            h.clock.current = now.toLocalDate().plusDays(1).atTime(4, 0)
            ticks.value = h.clock.current
            assertNull(awaitItem())
        }
    }

    @Test fun undoOnDeletedHabitKeepsAndWritesNothing() = runTest {
        val h = harness()
        val (habitId, sid) = h.pending()
        h.service.accept(sid)
        h.habits.delete(habitId)
        assertEquals(SuggestionService.KEPT, h.service.undo(sid))
        assertEquals(emptyList<app.brujula.habits.Habit>(), h.habits.observeHabits().first())
        assertEquals(1, h.resyncs)
    }

    @Test fun autoFailureRetriesNextDayOnly() = runTest {
        val h = harness()
        h.due()
        h.gateway.suggestion = GatewayResult.Error(retryable = true)
        h.service.maybeRunAuto()!!.join()
        assertEquals(1, h.gateway.suggestCalls)
        assertEquals("el fallo no mueve la última corrida", now.minusDays(8), h.store.lastRun())
        assertEquals(now, h.store.lastAttempt())

        h.service.maybeRunAuto()?.join()
        h.clock.current = now.plusHours(23)
        h.service.maybeRunAuto()?.join()
        assertEquals("mismo día: nada", 1, h.gateway.suggestCalls)

        h.clock.current = now.plusDays(1)
        h.service.maybeRunAuto()?.join()
        assertEquals("al día siguiente reintenta una vez", 2, h.gateway.suggestCalls)
        h.service.maybeRunAuto()?.join()
        assertEquals(2, h.gateway.suggestCalls)
    }

    @Test fun requestNowAfterWaitingForTheAutoRunDoesNotCallClaudeAgain() = runTest {
        val h = harness()
        h.due()
        val gate = CompletableDeferred<Unit>()
        val calls = AtomicInteger()
        // La primera comprobación de clave (la de la corrida automática) espera; las demás pasan.
        val service = SuggestionService(
            h.store, h.suggester, h.habits, resync = {}, clock = h.clock, syncScope = backgroundScope,
            hasKey = { if (calls.incrementAndGet() == 1) gate.await(); true }, isOnline = { true },
        )
        val auto = service.maybeRunAuto()!!
        runCurrent()
        val request = async { service.requestNow() }
        runCurrent()
        assertFalse("espera el turno de la automática", request.isCompleted)
        gate.complete(Unit)
        auto.join()
        assertEquals(SuggestRequestResult.Shown, request.await())
        assertEquals("una sola llamada a Claude", 1, h.gateway.suggestCalls)
    }

    @Test fun autoIsSingleFlight() = runTest {
        val h = harness()
        h.due()
        val first = h.service.maybeRunAuto()
        val second = h.service.maybeRunAuto()
        assertNotNull(first)
        assertNull("con una corrida en curso no arranca otra", second)
        first!!.join()
        assertEquals(1, h.gateway.suggestCalls)
        // Terminada la corrida (con la semana ya cumplida), no vuelve a llamar.
        h.service.maybeRunAuto()?.join()
        assertEquals(1, h.gateway.suggestCalls)
    }

    /** Un hábito de agua con una sugerencia pendiente (por `requestNow`); devuelve (id del hábito, id de la sugerencia). */
    private suspend fun SuggestionHarness.pending(): Pair<Long, Long> {
        val id = water()
        seedLogs(id, 3)
        answer(faster(id))
        assertEquals(SuggestRequestResult.Shown, service.requestNow())
        return id to store.entries.first().single().id
    }

    @Test fun acceptWritesHabitAndResyncs() = runTest {
        val h = harness()
        val (habitId, sid) = h.pending()
        assertNull(h.service.accept(sid))
        val habit = h.habits.observeHabits().first().single { it.id == habitId }
        assertEquals(HabitSchedule.Every(90, stretch), habit.schedule)
        assertEquals(8, habit.dailyTarget)
        assertEquals(1, h.resyncs)
        val card = h.service.card().first()!!
        assertTrue(card.accepted)
        assertEquals("Listo: desde las 15:00 te aviso cada 60 min.", card.doneText)
        assertEquals(SuggestionStatus.ACCEPTED, h.store.entries.first().single().status)
        // Aceptar otra vez no vuelve a escribir.
        assertNull(h.service.accept(sid))
        assertEquals(1, h.resyncs)
    }

    @Test fun acceptRefusesWhenHabitChanged() = runTest {
        val h = harness()
        val (habitId, sid) = h.pending()
        val edited = h.habits.observeHabits().first().single().copy(schedule = HabitSchedule.Every(60))
        h.habits.upsert(edited)
        assertEquals(SuggestionService.CHANGED, h.service.accept(sid))
        assertEquals(edited, h.habits.observeHabits().first().single { it.id == habitId })
        assertEquals(0, h.resyncs)
        assertEquals(SuggestionStatus.STALE, h.store.entries.first().single().status)
        assertNull("una sugerencia vieja ya no se muestra", h.service.card().first())
    }

    @Test fun acceptRefusesWhenHabitDeleted() = runTest {
        val h = harness()
        val (habitId, sid) = h.pending()
        h.habits.delete(habitId)
        assertEquals(SuggestionService.CHANGED, h.service.accept(sid))
        assertEquals(0, h.resyncs)
    }

    @Test fun undoRestoresWhenUnchanged() = runTest {
        val h = harness()
        val (habitId, sid) = h.pending()
        val original = h.habits.observeHabits().first().single()
        h.service.accept(sid)
        assertEquals(SuggestionService.UNDONE_TEXT, h.service.undo(sid))
        assertEquals(original, h.habits.observeHabits().first().single { it.id == habitId })
        assertEquals(2, h.resyncs)
        assertEquals(SuggestionStatus.UNDONE, h.store.entries.first().single().status)
        assertNull(h.service.card().first())
    }

    @Test fun undoKeepsLaterEdit() = runTest {
        val h = harness()
        val (habitId, sid) = h.pending()
        h.service.accept(sid)
        val later = h.habits.observeHabits().first().single().copy(dailyTarget = 12)
        h.habits.upsert(later)
        assertEquals(SuggestionService.KEPT, h.service.undo(sid))
        assertEquals(later, h.habits.observeHabits().first().single { it.id == habitId })
        assertEquals(1, h.resyncs)
        assertEquals(SuggestionStatus.STALE, h.store.entries.first().single().status)
    }

    @Test fun acceptedCardOnlyShowsUntilEndOfLogicalDay() = runTest {
        val h = harness()
        val (_, sid) = h.pending()
        h.service.accept(sid)
        h.clock.current = now.toLocalDate().plusDays(1).atTime(3, 30) // todavía el mismo día lógico
        assertNotNull(h.service.card().first())
        h.clock.current = now.toLocalDate().plusDays(1).atTime(4, 0)
        assertNull(h.service.card().first())
    }

    @Test fun dismissedNotSuggestedAgainFor30Days() = runTest {
        val h = harness()
        val (habitId, sid) = h.pending()
        h.service.dismiss(sid)
        assertNull(h.service.card().first())
        assertEquals(setOf(habitId to SuggestionKind.INTERVAL), h.provider.current().rejected)
        // Claude la repite: se descarta (y se le dijo que no la repita).
        h.answer(h.faster(habitId))
        assertEquals(SuggestRequestResult.Message(HabitSuggester.NONE), h.service.requestNow())
        assertTrue(h.store.entries.first().none { it.status == SuggestionStatus.PENDING })
        assertTrue(h.gateway.lastSuggestUser!!.contains("Ya rechazaste (no repitas): H$habitId interval"))
        // A los 31 días ya no.
        h.clock.current = now.plusDays(31)
        h.seedLogs(habitId, 3, today = h.clock.current.toLocalDate())
        assertEquals(emptySet<Pair<Long, SuggestionKind>>(), h.provider.current().rejected)
        assertEquals(SuggestRequestResult.Shown, h.service.requestNow())
    }

    @Test fun requestNowMessages() = runTest {
        val h = harness()
        val id = h.water()
        h.seedLogs(id, 2)
        assertEquals(SuggestRequestResult.Message(HabitSuggester.NOT_ENOUGH), h.service.requestNow())
        h.seedLogs(id, 3)
        h.answer()
        assertEquals(SuggestRequestResult.Message(HabitSuggester.NONE), h.service.requestNow())
        h.online = false
        assertEquals(SuggestRequestResult.Message(HabitSuggester.OFFLINE), h.service.requestNow())
        h.online = true
        h.key = false
        assertEquals(SuggestRequestResult.Message(HabitSuggester.NO_KEY), h.service.requestNow())
        h.key = true
        h.gateway.suggestion = GatewayResult.InvalidKey
        assertEquals(SuggestRequestResult.Message(DictationInterpreter.INVALID_KEY), h.service.requestNow())
        h.gateway.suggestion = GatewayResult.Refused
        assertEquals(SuggestRequestResult.Message(DictationInterpreter.UNAVAILABLE), h.service.requestNow())
        h.gateway.suggestion = GatewayResult.Error(retryable = true)
        assertEquals(SuggestRequestResult.Message(DictationInterpreter.UNAVAILABLE), h.service.requestNow())
        // Sin red ni clave no llaman a Claude: NONE (1) + InvalidKey + Refused + Error = 4 llamadas.
        assertEquals(4, h.gateway.suggestCalls)
    }

    @Test fun requestNowIgnoresWeeklyLimit() = runTest {
        val h = harness()
        val id = h.water()
        h.seedLogs(id, 3)
        h.store.markAttempt(now.minusHours(1), success = true)
        h.answer(h.faster(id))
        assertEquals(SuggestRequestResult.Shown, h.service.requestNow())
        assertEquals(1, h.gateway.suggestCalls)
    }
}
