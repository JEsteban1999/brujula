package app.brujula.suggestions

import app.brujula.claude.DictationInterpreter
import app.brujula.claude.FakeClaudeGateway
import app.brujula.claude.GatewayResult
import app.brujula.claude.SuggestionItem
import app.brujula.claude.SuggestionResult
import app.brujula.habits.FasterStretch
import app.brujula.habits.HabitSchedule
import app.brujula.habits.HabitWindow
import app.brujula.suggestions.SuggestionFixtures.balm
import app.brujula.suggestions.SuggestionFixtures.ctx
import app.brujula.suggestions.SuggestionFixtures.now
import app.brujula.suggestions.SuggestionFixtures.water
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class HabitSuggesterTest {
    private val gateway = FakeClaudeGateway()
    private val msg = "Por las tardes casi no tomas agua. ¿Te aviso más seguido después de las 3 pm?"

    private fun suggester(c: SuggestionContext = ctx(), online: Boolean = true) =
        HabitSuggester(gateway, contextProvider = { c }, isOnline = { online })

    private fun interval(ref: String = "H3", min: Int? = 60, from: String? = "15:00") =
        SuggestionItem(habitRef = ref, message = msg, change = "interval", intervalMin = min, fromTime = from)

    private fun times(ref: String, vararg t: String) =
        SuggestionItem(habitRef = ref, message = "¿Te muevo el bálsamo?", change = "times", times = t.toList())

    private fun target(ref: String = "H3", n: Int? = 10) =
        SuggestionItem(habitRef = ref, message = "¿Subimos la meta?", change = "target", dailyTarget = n)

    private fun answer(vararg items: SuggestionItem) {
        gateway.suggestion = GatewayResult.Ok(SuggestionResult(items.toList()))
    }

    @Test fun notEnoughDataSkipsCall() = runTest {
        assertEquals(SuggestOutcome.NotEnoughData, suggester(ctx(daysWithLogs = 2)).suggest(HabitSuggester.MIN_DAYS_ON_DEMAND))
        assertEquals(SuggestOutcome.NotEnoughData, suggester(ctx(daysWithLogs = 6)).suggest(HabitSuggester.MIN_DAYS_AUTO))
        assertEquals(0, gateway.suggestCalls)
        answer()
        assertEquals(SuggestOutcome.Ok(null), suggester(ctx(daysWithLogs = 3)).suggest(HabitSuggester.MIN_DAYS_ON_DEMAND))
        assertEquals(1, gateway.suggestCalls)
    }

    @Test fun offlineSkipsCall() = runTest {
        assertEquals(SuggestOutcome.Offline, suggester(online = false).suggest(HabitSuggester.MIN_DAYS_ON_DEMAND))
        assertEquals(0, gateway.suggestCalls)
    }

    @Test fun validIntervalWithFromTime() = runTest {
        answer(interval())
        val ok = suggester().suggest(HabitSuggester.MIN_DAYS_AUTO) as SuggestOutcome.Ok
        val s = ok.suggestion!!
        assertEquals(3L, s.habitId)
        assertEquals("Agua", s.habitName)
        assertEquals(SuggestionKind.INTERVAL, s.kind)
        assertEquals(msg, s.message)
        assertEquals(HabitChange(HabitSchedule.Every(90), 8), s.before)
        assertEquals(HabitChange(HabitSchedule.Every(90, FasterStretch(LocalTime.of(15, 0), 60)), 8), s.after)
        assertEquals(now, s.createdAt)
        assertEquals("Listo: desde las 15:00 te aviso cada 60 min.", s.doneText())
        // Lo que se envió: el system fijo y el contexto agregado.
        assertEquals(SuggestionPrompt.system(), gateway.lastSuggestSystem)
        assertTrue(gateway.lastSuggestUser!!.contains("H3 · Agua"))
    }

    @Test fun intervalWithoutFromTimeKeepsCurrentStretch() {
        val stretch = FasterStretch(LocalTime.of(15, 0), 60)
        val c = ctx(habits = listOf(water.copy(schedule = HabitSchedule.Every(90, stretch))))
        val s = validate(interval(min = 75, from = null), c)!!
        assertEquals(HabitSchedule.Every(75, stretch), s.after.schedule)
        assertEquals("Listo: te aviso cada 75 min.", s.doneText())
    }

    @Test fun intervalWithoutFromTimeThatReachesTheStretchDropsItAndSaysSo() {
        val stretch = FasterStretch(LocalTime.of(15, 0), 60)
        val c = ctx(habits = listOf(water.copy(schedule = HabitSchedule.Every(90, stretch))))
        val s = validate(interval(min = 60, from = null), c)!!
        assertEquals(HabitSchedule.Every(60), s.after.schedule)
        assertTrue(s.message, s.message.contains("Quito el tramo"))
    }

    @Test fun intervalOnAtTimesRejected() {
        assertNull(validate(interval(ref = "H4"), ctx()))
        assertNull(validate(interval(ref = "H4", from = null), ctx()))
    }

    @Test fun fromTimeNeedsAwakeWindow() {
        val workOnly = water.copy(window = HabitWindow.WORK)
        assertNull(validate(interval(), ctx(habits = listOf(workOnly))))
        assertNotNull("Sin desde sí se puede", validate(interval(from = null), ctx(habits = listOf(workOnly))))
    }

    @Test fun stretchIntervalMustBeStrictlyFasterThanBase() {
        assertNull("igual al base", validate(interval(min = 90), ctx()))
        assertNull("más lento que el base", validate(interval(min = 120), ctx()))
        assertNotNull(validate(interval(min = 75), ctx()))
        // Sin desde, cambia todo el intervalo y puede ser mayor.
        assertNotNull(validate(interval(min = 120, from = null), ctx()))
        // La referencia es el intervalo base, no el del tramo actual.
        val slowBase = water.copy(schedule = HabitSchedule.Every(60, FasterStretch(LocalTime.of(17, 0), 30)))
        assertNull(validate(interval(min = 60), ctx(habits = listOf(slowBase))))
        assertNotNull(validate(interval(min = 45), ctx(habits = listOf(slowBase))))
    }

    @Test fun suggestedTimesMustFitTheHabitWindow() {
        // AWAKE: de levantarse (8:15) a acostarse (0:30).
        assertNull(validate(times("H4", "09:00", "06:00"), ctx()))
        assertNull(validate(times("H4", "03:00"), ctx()))
        assertNull(validate(times("H4", "08:00"), ctx()))
        assertNotNull(validate(times("H4", "08:30", "23:45", "00:15"), ctx()))
        // WORK: dentro de los bloques de trabajo (8–12 y 14–18) y de la vigilia.
        val work = balm.copy(window = HabitWindow.WORK, schedule = HabitSchedule.AtTimes(listOf(LocalTime.of(10, 0))), dailyTarget = 1)
        val c = ctx(habits = listOf(work))
        assertNotNull(validate(times("H4", "09:00", "14:00", "17:30"), c))
        assertNull("entre bloques", validate(times("H4", "09:00", "13:00"), c))
        assertNull("fuera del trabajo", validate(times("H4", "19:00"), c))
        assertNull("antes de levantarse aunque sea hora de trabajo", validate(times("H4", "08:05"), c))
        assertNull("el fin del bloque no cuenta", validate(times("H4", "12:00"), c))
    }

    @Test fun unknownRefRejected() {
        assertNull(validate(interval(ref = "H99"), ctx()))
        assertNull(validate(interval(ref = "A3"), ctx()))
        assertNull(validate(interval(ref = ""), ctx()))
    }

    @Test fun sameAsCurrentRejected() {
        assertNull(validate(interval(min = 90, from = null), ctx()))
        assertNull(validate(times("H4", "09:00", "13:00", "17:00", "21:00"), ctx()))
        assertNull(validate(target(n = 8), ctx()))
    }

    @Test fun rejectedRepeatDropped() {
        val c = ctx(rejected = setOf(3L to SuggestionKind.INTERVAL))
        assertNull(validate(interval(), c))
        assertNotNull("Otro tipo de cambio del mismo hábito sí", validate(target(), c))
    }

    @Test fun outOfRangeValuesRejected() {
        val c = ctx()
        assertNull(validate(interval(min = 10), c))
        assertNull(validate(interval(min = 241), c))
        assertNull(validate(interval(min = null), c))
        assertNull(validate(times("H4", *(8..20).map { "%02d:00".format(it) }.toTypedArray()), c))
        assertNull(validate(times("H4"), c))
        assertNull(validate(times("H4", "9 am"), c))
        assertNull(validate(target(n = 0), c))
        assertNull(validate(target(n = 21), c))
        assertNull(validate(target(n = null), c))
        assertNull(validate(interval(from = "03:00"), c))
        assertNull(validate(interval(from = "25:00"), c))
        assertNull(validate(interval(from = "tarde"), c))
        assertNull(validate(interval().copy(change = "delete"), c))
        assertNull(validate(interval().copy(message = "   "), c))
        // Pasada la medianoche pero antes de acostarse (00:30) sí vale.
        assertNotNull(validate(interval(from = "00:15"), c))
        val twelve = listOf("9:00", "13:00", "17:00", "20:00", "22:00", "11:00", "15:00", "19:00", "10:00", "12:00", "14:00", "16:00")
        assertNotNull(validate(times("H4", *twelve.toTypedArray()), c))
    }

    @Test fun timesAreSortedDistinctAndSetTheTarget() {
        val s = validate(times("H4", "17:00", "9:00", "13:00", "09:00"), ctx())!!
        val at = listOf(9, 13, 17).map { LocalTime.of(it, 0) }
        assertEquals(HabitChange(HabitSchedule.AtTimes(at), 3), s.after)
        assertEquals(HabitChange(balm.schedule, 4), s.before)
        assertEquals("Listo: te aviso a las 9:00, 13:00 y 17:00.", s.doneText())
    }

    @Test fun targetChangeAndMessageTrimmed() {
        val s = validate(target(n = 10).copy(message = "  " + "a".repeat(400) + "  "), ctx())!!
        assertEquals(HabitChange(HabitSchedule.Every(90), 10), s.after)
        assertEquals(300, s.message.length)
        assertEquals("Listo: tu meta ahora es 10 al día.", s.doneText())
        assertNull("La meta solo cambia en hábitos cada N min", validate(target(ref = "H4", n = 6), ctx()))
    }

    @Test fun firstValidOfSeveral() = runTest {
        answer(interval(ref = "H99"), target(n = 12), times("H4", "10:00"))
        val ok = suggester().suggest(HabitSuggester.MIN_DAYS_AUTO) as SuggestOutcome.Ok
        assertEquals(SuggestionKind.TARGET, ok.suggestion!!.kind)
        assertEquals(12, ok.suggestion!!.after.dailyTarget)
    }

    @Test fun emptyListIsOkNull() = runTest {
        answer()
        assertEquals(SuggestOutcome.Ok(null), suggester().suggest(HabitSuggester.MIN_DAYS_AUTO))
        answer(interval(ref = "H99"))
        assertEquals(SuggestOutcome.Ok(null), suggester().suggest(HabitSuggester.MIN_DAYS_AUTO))
    }

    @Test fun failuresAreMapped() = runTest {
        suspend fun outcome(r: GatewayResult<SuggestionResult>): SuggestOutcome {
            gateway.suggestion = r
            return suggester().suggest(HabitSuggester.MIN_DAYS_AUTO)
        }
        // Haiku 5.5 no tiene respaldo en el servidor: un rechazo es "sin sugerencias".
        assertEquals(SuggestOutcome.Refused(HabitSuggester.NONE), outcome(GatewayResult.Refused))
        assertEquals(SuggestOutcome.NoKey, outcome(GatewayResult.NoKey))
        assertEquals(SuggestOutcome.InvalidKey, outcome(GatewayResult.InvalidKey))
        assertEquals(SuggestOutcome.Offline, outcome(GatewayResult.Network))
        assertEquals(SuggestOutcome.Failed(DictationInterpreter.UNAVAILABLE, true), outcome(GatewayResult.Timeout))
        assertEquals(SuggestOutcome.Failed(DictationInterpreter.UNAVAILABLE, true), outcome(GatewayResult.Error(retryable = true)))
        assertEquals(
            SuggestOutcome.Failed(DictationInterpreter.UNAVAILABLE, false),
            outcome(GatewayResult.Error(retryable = false, config = true)),
        )
    }

    @Test fun contextFailureIsARetryableFailureWithoutCalling() = runTest {
        val s = HabitSuggester(gateway, contextProvider = { error("db") })
        assertEquals(SuggestOutcome.Failed(DictationInterpreter.UNAVAILABLE, true), s.suggest(HabitSuggester.MIN_DAYS_AUTO))
        assertEquals(0, gateway.suggestCalls)
    }

    @Test fun copy() {
        assertEquals("Necesito al menos 3 días de registros para sugerirte algo.", HabitSuggester.NOT_ENOUGH)
        assertEquals("Sin conexión. Claude necesita internet para sugerirte cambios.", HabitSuggester.OFFLINE)
        assertEquals("Para pedir sugerencias, agrega tu clave de Claude en Tu rutina.", HabitSuggester.NO_KEY)
        assertEquals("Por ahora no tengo sugerencias. Vas bien.", HabitSuggester.NONE)
    }
}
