package app.brujula.planning

import app.brujula.activities.ActivityOrigin
import app.brujula.activities.ActivityType
import app.brujula.activities.Repeat
import app.brujula.claude.PlanChange
import app.brujula.claude.PlanContext
import app.brujula.claude.PlanResult
import app.brujula.core.time.TimeSpan
import app.brujula.planning.PlanFixtures.FRI
import app.brujula.planning.PlanFixtures.SAT
import app.brujula.planning.PlanFixtures.THU
import app.brujula.planning.PlanFixtures.WED
import app.brujula.planning.PlanFixtures.create
import app.brujula.planning.PlanFixtures.ctx
import app.brujula.planning.PlanFixtures.ext
import app.brujula.planning.PlanFixtures.move
import app.brujula.planning.PlanFixtures.own
import app.brujula.planning.PlanFixtures.skip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class PlanValidatorTest {
    /** Armar el jueves desde el miércoles por la noche. */
    private val evening = WED.atTime(21, 0)

    private fun validate(ctx: PlanContext, vararg changes: PlanChange) =
        PlanValidator.validate(PlanResult("Listo.", changes.toList()), ctx)

    private fun assertRejected(v: ValidatedPlan, vararg parts: String) {
        assertTrue("Esperaba solo rechazos: ${v.ops}", v.ops.isEmpty())
        assertEquals(v.rejected.toString(), 1, v.rejected.size)
        parts.forEach { assertTrue("Falta «$it» en «${v.rejected[0]}»", v.rejected[0].contains(it, ignoreCase = true)) }
    }

    // --- create ---

    @Test fun createWorkHasNoReminderAndPlanOrigin() {
        val v = validate(ctx(evening, THU), create("Bloque de trabajo", "WORK", THU, "15:00", 120))
        assertEquals(emptyList<String>(), v.rejected)
        val a = (v.ops.single() as PlanOp.Create).activity
        assertEquals("Bloque de trabajo", a.title)
        assertEquals(ActivityType.WORK, a.type)
        assertEquals(THU, a.date)
        assertEquals(LocalTime.of(15, 0), a.start)
        assertEquals(120, a.durationMin)
        assertEquals(Repeat.NONE, a.repeat)
        assertNull(a.remindBeforeMin)
        assertFalse(a.copyToGoogle)
        assertEquals(ActivityOrigin.PLAN, a.origin)
        assertEquals(0L, a.id)
    }

    @Test fun createExerciseReminds15() {
        val v = validate(
            ctx(evening, THU),
            create("Gimnasio", "EXERCISE", THU, "18:30", 60),
            create("Leer", "PERSONAL", THU, "20:00", 30),
        )
        assertEquals(emptyList<String>(), v.rejected)
        val (gym, read) = v.ops.map { (it as PlanOp.Create).activity }
        assertEquals(15, gym.remindBeforeMin)
        assertEquals(15, read.remindBeforeMin)
        assertFalse(gym.copyToGoogle)
        assertEquals(ActivityOrigin.PLAN, read.origin)
    }

    @Test fun appointmentRejected() {
        val v = validate(ctx(evening, THU), create("Médico", "APPOINTMENT", THU, "15:00", 60))
        assertRejected(v, "«Médico»", "las citas se agendan dictándolas.")
    }

    @Test fun createWithBadTitleTypeDateTimeOrDurationRejected() {
        val c = ctx(evening, THU)
        assertRejected(validate(c, create("  ", "WORK", THU, "15:00", 60)), "sin nombre")
        assertRejected(validate(c, create("x".repeat(81), "WORK", THU, "15:00", 60)), "nombre")
        assertRejected(validate(c, create("Siesta", "NAP", THU, "15:00", 60)), "«Siesta»", "tipo")
        assertRejected(validate(c, create("Bloque", "WORK", THU, "25:00", 60)), "«Bloque»", "hora")
        assertRejected(validate(c, PlanChange(op = "create", title = "Bloque", activityType = "WORK", date = "2026-13-01", start = "15:00", durationMin = 60)), "fecha")
        assertRejected(validate(c, create("Bloque", "WORK", THU, "15:00", 4)), "duración")
        assertRejected(validate(c, create("Bloque", "WORK", THU, "09:00", 721)), "duración")
        assertRejected(validate(c, create("Bloque", "WORK", THU, "15:00", null)), "duración")
        assertEquals(1, validate(c, create("Bloque", "WORK", THU, "15:00", 5)).ops.size)
    }

    @Test fun createInThePastTodayRejected() {
        val v = validate(ctx(THU.atTime(14, 30), THU), create("Bloque", "WORK", THU, "14:15", 60))
        assertRejected(v, "«Bloque»", "ya pasó")
    }

    // --- referencias ---

    @Test fun externalRefRejectedWithRuleCMessage() {
        val items = listOf(ext(1, "Reunión de equipo", THU.atTime(9, 0), THU.atTime(10, 0)))
        val v = validate(ctx(evening, THU, items), move("E1", THU, "16:00"), skip("E1"))
        assertTrue(v.ops.isEmpty())
        assertEquals(
            listOf(
                "«Reunión de equipo»: no toco eventos de tu calendario externo.",
                "«Reunión de equipo»: no toco eventos de tu calendario externo.",
            ),
            v.rejected,
        )
    }

    @Test fun unknownRefRejected() {
        val items = listOf(own(5, "Leer", THU.atTime(15, 0), THU.atTime(16, 0)))
        assertRejected(validate(ctx(evening, THU, items), move("A99", THU, "17:00")), "A99")
        assertRejected(validate(ctx(evening, THU, items), skip("E7")), "E7")
        assertRejected(validate(ctx(evening, THU, items), PlanChange(op = "skip", ref = null)))
        assertRejected(validate(ctx(evening, THU, items), PlanChange(op = "borrar", ref = "A5")), "borrar")
    }

    @Test fun refsAreResolvedByExactLookupNotByParsing() {
        // A12-2 es la ocurrencia de ayer (spill) de la misma serie que A12: no se puede tocar aunque comparta el id.
        val items = listOf(
            own(12, "Turno", WED.atTime(22, 0), THU.atTime(9, 0), oneOff = false, movable = false, ref = "A12-2"),
            own(12, "Turno", THU.atTime(16, 0), THU.atTime(17, 0), oneOff = false),
        )
        val c = ctx(evening, THU, items)
        assertRejected(validate(c, skip("A12-2")), "«Turno»", "día anterior")
        val m = validate(c, move("A12", THU, "18:00")).ops.single() as PlanOp.Move
        assertEquals("A12", m.ref)
        assertEquals(THU, m.occurrenceDay)
        assertEquals(TimeSpan(THU.atTime(16, 0), THU.atTime(17, 0)), m.from)
    }

    @Test fun sameRefChangedTwiceKeepsOnlyTheFirst() {
        val items = listOf(own(5, "Leer", THU.atTime(15, 0), THU.atTime(16, 0)))
        val v = validate(ctx(evening, THU, items), move("A5", THU, "17:00"), skip("A5"))
        assertEquals(1, v.ops.size)
        assertTrue(v.ops[0] is PlanOp.Move)
        assertEquals(1, v.rejected.size)
        assertTrue(v.rejected[0], v.rejected[0].startsWith("«Leer»"))
    }

    // --- move ---

    @Test fun moveToPastRejected() {
        val items = listOf(own(5, "Llamada a mamá", THU.atTime(15, 0), THU.atTime(16, 0)))
        val v = validate(ctx(THU.atTime(14, 30), THU, items), move("A5", THU, "14:00"))
        assertRejected(v, "«Llamada a mamá»", "ya pasó")
    }

    @Test fun moveOutsideAwakeRejected() {
        val items = listOf(own(5, "Leer", THU.atTime(15, 0), THU.atTime(16, 0)))
        val c = ctx(evening, THU, items)
        // Se levanta a las 8:15 y se acuesta a las 00:30 del viernes.
        assertRejected(validate(c, move("A5", THU, "07:00")), "«Leer»", "despierto")
        assertRejected(validate(c, move("A5", FRI, "00:00")), "«Leer»", "despierto")
        assertEquals(1, validate(c, move("A5", FRI, "00:00", 30)).ops.size)
    }

    @Test fun moveToAnotherLogicalDayRejected() {
        val items = listOf(own(5, "Leer", THU.atTime(15, 0), THU.atTime(16, 0), oneOff = false))
        assertRejected(validate(ctx(evening, THU, items), move("A5", FRI, "10:00")), "«Leer»", "otro día")
    }

    @Test fun moveAfterMidnightStaysInLogicalDay() {
        // Sábado: vigilia de 10:00 a 01:00 del domingo. 00:00 del domingo sigue siendo el sábado lógico.
        val items = listOf(own(9, "Película", SAT.atTime(20, 0), SAT.atTime(21, 0), oneOff = false))
        val v = validate(ctx(FRI.atTime(21, 0), SAT, items), move("A9", SAT.plusDays(1), "00:00"))
        assertEquals(emptyList<String>(), v.rejected)
        val m = v.ops.single() as PlanOp.Move
        assertEquals(SAT, m.occurrenceDay)
        assertEquals(TimeSpan(SAT.plusDays(1).atTime(0, 0), SAT.plusDays(1).atTime(1, 0)), m.to)
        assertFalse(m.oneOff)
    }

    @Test fun moveOntoExternalRejected() {
        val items = listOf(
            own(5, "Leer", THU.atTime(15, 0), THU.atTime(16, 0)),
            ext(1, "Reunión", THU.atTime(16, 0), THU.atTime(17, 0)),
        )
        assertRejected(validate(ctx(evening, THU, items), move("A5", THU, "16:30")), "«Leer»", "«Reunión»", "calendario externo")
    }

    @Test fun moveKeepsDurationUnlessGivenAndCarriesWhatAcceptNeeds() {
        val items = listOf(own(5, "Leer", THU.atTime(15, 0), THU.atTime(16, 0)))
        val c = ctx(evening, THU, items)
        val same = validate(c, move("A5", THU, "17:00")).ops.single() as PlanOp.Move
        assertEquals(PlanOp.Move("A5", 5, THU, "Leer", TimeSpan(THU.atTime(15, 0), THU.atTime(16, 0)), TimeSpan(THU.atTime(17, 0), THU.atTime(18, 0)), oneOff = true), same)
        val longer = validate(c, move("A5", THU, "17:00", 90)).ops.single() as PlanOp.Move
        assertEquals(THU.atTime(18, 30), longer.to.end)
        // Mover a la misma hora no es un cambio.
        val noop = validate(c, move("A5", THU, "15:00"))
        assertTrue(noop.ops.isEmpty())
        assertTrue(noop.rejected.isEmpty())
    }

    // --- almuerzo y solapes ---

    @Test fun createInLunchRejectedOnWorkday() {
        assertRejected(validate(ctx(evening, THU), create("Bloque de trabajo", "WORK", THU, "12:30", 60)), "«Bloque de trabajo»", "almuerzo")
    }

    @Test fun lunchFreeOnSaturday() {
        val v = validate(ctx(FRI.atTime(21, 0), SAT), create("Mercado", "PERSONAL", SAT, "12:30", 60))
        assertEquals(emptyList<String>(), v.rejected)
        assertEquals(1, v.ops.size)
    }

    @Test fun moveThenCreateInFreedSlotBothAccepted() {
        val items = listOf(
            own(5, "Leer", THU.atTime(15, 0), THU.atTime(16, 0)),
            own(6, "Siesta", THU.atTime(16, 0), THU.atTime(17, 0)),
        )
        val v = validate(
            ctx(evening, THU, items),
            move("A5", THU, "17:00"),
            create("Bloque de trabajo", "WORK", THU, "15:00", 60),
            skip("A6"),
            create("Bloque de trabajo", "WORK", THU, "16:00", 60),
        )
        assertEquals(emptyList<String>(), v.rejected)
        assertEquals(4, v.ops.size)
    }

    @Test fun secondOverlappingCreateRejected() {
        val v = validate(
            ctx(evening, THU),
            create("Bloque de trabajo", "WORK", THU, "15:00", 120),
            create("Leer", "PERSONAL", THU, "16:00", 60),
        )
        assertEquals(1, v.ops.size)
        assertEquals(1, v.rejected.size)
        assertTrue(v.rejected[0], v.rejected[0].startsWith("«Leer»"))
        assertTrue(v.rejected[0], v.rejected[0].contains("«Bloque de trabajo»"))
    }

    @Test fun createOverKeptBlockRejected() {
        val items = listOf(own(7, "Gimnasio", THU.atTime(18, 30), THU.atTime(19, 30), ActivityType.EXERCISE, movable = false, done = true))
        assertRejected(validate(ctx(evening, THU, items), create("Leer", "PERSONAL", THU, "19:00", 60)), "«Leer»", "«Gimnasio»")
    }

    // --- skip y lo que no se toca ---

    @Test fun skipDoneRejected() {
        val items = listOf(own(7, "Gimnasio", THU.atTime(18, 30), THU.atTime(19, 30), ActivityType.EXERCISE, movable = false, done = true))
        assertRejected(validate(ctx(evening, THU, items), skip("A7")), "«Gimnasio»", "hecho")
    }

    @Test fun skipRecurringIsNotOneOff() {
        val items = listOf(own(8, "Gimnasio", THU.atTime(18, 30), THU.atTime(19, 30), ActivityType.EXERCISE, oneOff = false))
        val s = validate(ctx(evening, THU, items), skip("A8")).ops.single() as PlanOp.Skip
        assertEquals(PlanOp.Skip("A8", 8, THU, "Gimnasio", TimeSpan(THU.atTime(18, 30), THU.atTime(19, 30)), oneOff = false), s)
    }

    @Test fun doneOrStartedIsNeverTouchedEvenIfFlaggedMovable() {
        val now = THU.atTime(15, 30)
        val items = listOf(
            own(5, "Leer", THU.atTime(15, 0), THU.atTime(16, 0), movable = true),
            own(6, "Siesta", THU.atTime(17, 0), THU.atTime(18, 0), movable = true, done = true),
        )
        assertRejected(validate(ctx(now, THU, items), move("A5", THU, "19:00")), "«Leer»", "ya empezó")
        assertRejected(validate(ctx(now, THU, items), skip("A6")), "«Siesta»", "hecho")
    }

    @Test fun recurringWithGoogleCopyIsFixed() {
        // D4: PlanContextProvider la marca no movible; no está hecha, ni empezada, ni es de ayer.
        val items = listOf(own(4, "Clase de inglés", THU.atTime(18, 0), THU.atTime(19, 0), oneOff = false, movable = false))
        assertRejected(validate(ctx(evening, THU, items), skip("A4")), "«Clase de inglés»", "Google")
        assertRejected(validate(ctx(evening, THU, items), move("A4", THU, "20:00")), "«Clase de inglés»", "Google")
    }

    // --- límites ---

    @Test fun onlyFirst20Changes() {
        val changes = (0 until 25).map { i ->
            val t = THU.atTime(15, 0).plusMinutes(10L * i)
            create("Pausa $i", "PERSONAL", THU, "%02d:%02d".format(t.hour, t.minute), 5)
        }
        val v = validate(ctx(evening, THU), *changes.toTypedArray())
        assertEquals(PlanValidator.MAX_CHANGES, v.ops.size)
        assertEquals(listOf("Claude propuso demasiados cambios; apliqué los primeros 20."), v.rejected)
    }

    @Test fun dayOutsideTheWindowRejectsEverything() {
        val far = validate(ctx(evening, WED.plusDays(7)), create("Bloque", "WORK", WED.plusDays(7), "15:00", 60))
        assertEquals(listOf(PlanValidator.OUT_OF_RANGE), far.rejected)
        assertTrue(far.ops.isEmpty())
        val past = validate(ctx(evening, WED.minusDays(1)), create("Bloque", "WORK", WED.minusDays(1), "15:00", 60))
        assertTrue(past.ops.isEmpty())
        assertEquals(listOf(PlanValidator.PAST_DAY), past.rejected)
        assertEquals(1, validate(ctx(evening, WED.plusDays(6)), create("Bloque", "WORK", WED.plusDays(6), "15:00", 60)).ops.size)
    }

    // --- vista previa y totales ---

    @Test fun previewTagsAndOrder() {
        val items = listOf(
            own(1, "Bloque de trabajo", THU.atTime(8, 15), THU.atTime(12, 0), ActivityType.WORK),
            ext(1, "Reunión de equipo", THU.atTime(15, 0), THU.atTime(15, 30)),
            own(2, "Gimnasio", THU.atTime(18, 30), THU.atTime(19, 30), ActivityType.EXERCISE, oneOff = false),
            own(3, "Llamar a mamá", THU.atTime(20, 0), THU.atTime(20, 30), origin = ActivityOrigin.DICTATION),
            own(4, "Leer", THU.atTime(21, 0), THU.atTime(21, 30), origin = ActivityOrigin.DICTATION),
        )
        val c = ctx(evening, THU, items)
        val v = validate(c, move("A2", THU, "16:00"), skip("A4"), create("Bloque de trabajo", "WORK", THU, "14:00", 60))
        assertEquals(emptyList<String>(), v.rejected)

        val p = PlanValidator.preview(c, v.ops)
        assertEquals(
            listOf(
                PreviewBlock("Bloque de trabajo", TimeSpan(THU.atTime(8, 15), THU.atTime(12, 0)), BlockTag.NONE, "8:15 – 12:00"),
                PreviewBlock("Almuerzo y pausa", TimeSpan(THU.atTime(12, 0), THU.atTime(14, 0)), BlockTag.FIJO, "12:00 – 14:00"),
                PreviewBlock("Bloque de trabajo", TimeSpan(THU.atTime(14, 0), THU.atTime(15, 0)), BlockTag.NUEVO, "14:00 – 15:00"),
                PreviewBlock("Reunión de equipo", TimeSpan(THU.atTime(15, 0), THU.atTime(15, 30)), BlockTag.FIJO, "Outlook · 15:00 – 15:30"),
                PreviewBlock("Gimnasio", TimeSpan(THU.atTime(16, 0), THU.atTime(17, 0)), BlockTag.MOVIDO, "16:00 – 17:00 · antes 18:30"),
                PreviewBlock("Llamar a mamá", TimeSpan(THU.atTime(20, 0), THU.atTime(20, 30)), BlockTag.LO_DICTASTE, "20:00 – 20:30"),
                PreviewBlock("Leer", TimeSpan(THU.atTime(21, 0), THU.atTime(21, 30)), BlockTag.QUITADO, "21:00 – 21:30"),
                PreviewBlock("A dormir", TimeSpan(FRI.atTime(0, 15), FRI.atTime(8, 15)), BlockTag.NONE, "Para dormir 8 h"),
            ),
            p,
        )
    }

    @Test fun previewShowsTimesAfterMidnight() {
        val items = listOf(own(9, "Película", SAT.atTime(20, 0), SAT.atTime(21, 0), oneOff = false))
        val c = ctx(FRI.atTime(21, 0), SAT, items)
        val v = validate(c, move("A9", SAT.plusDays(1), "00:00"))
        val moved = PlanValidator.preview(c, v.ops).first { it.tag == BlockTag.MOVIDO }
        assertEquals("0:00 – 1:00 · antes 20:00", moved.sub)
        val bed = PlanValidator.preview(c, v.ops).last()
        assertEquals("A dormir", bed.title)
        assertEquals("Para dormir 9 h", bed.sub) // domingo: se acuesta a la 1:00 y se levanta a las 10:00
    }

    @Test fun totalsCountCreatesAndExternalWorkNotSkipped() {
        val items = listOf(
            // Spill de ayer: Hoy no lo cuenta en el trabajo de este día.
            own(30, "Turno", WED.atTime(21, 0), THU.atTime(9, 0), ActivityType.WORK, movable = false, oneOff = false, ref = "A30-2"),
            own(1, "Bloque de trabajo", THU.atTime(8, 15), THU.atTime(12, 0), ActivityType.WORK), // 225
            ext(1, "Reunión de equipo", THU.atTime(15, 0), THU.atTime(15, 30), work = true), // 30
            ext(2, "Odontólogo", THU.atTime(16, 0), THU.atTime(16, 30)), // no es trabajo
            own(5, "Bloque de la tarde", THU.atTime(17, 0), THU.atTime(18, 0), ActivityType.WORK), // se salta
            own(2, "Gimnasio", THU.atTime(18, 30), THU.atTime(19, 30), ActivityType.EXERCISE), // 60 → 90 al moverlo
            ext(3, "Soporte", FRI.atTime(3, 30), FRI.atTime(4, 30), work = true), // solo 30 dentro del día lógico
        )
        val c = ctx(evening, THU, items)
        val v = validate(
            c,
            skip("A5"),
            move("A2", THU, "19:00", 90),
            create("Bloque de trabajo", "WORK", THU, "14:00", 60), // 60
            create("Caminata", "EXERCISE", THU, "21:00", 30), // 30
        )
        assertEquals(emptyList<String>(), v.rejected)
        val t = PlanValidator.totals(c, v.ops)
        assertEquals(225 + 30 + 60 + 30, t.workMinutes)
        assertEquals(90 + 30, t.exerciseMinutes)
        assertEquals(480, t.sleepMinutes)
        assertEquals(FRI.atTime(0, 15), t.bedtime)
    }

    @Test fun bedtimeUsesOnlyTomorrowsFirstFixedItem() {
        // Mañana empieza con un bloque propio a las 8:00 (no es fijo): la hora de levantarse sigue siendo la de la rutina.
        val t = PlanValidator.totals(ctx(evening, THU, nextDayFirstStart = FRI.atTime(8, 0), nextDayFirstFixedStart = null), emptyList())
        assertEquals(FRI.atTime(0, 15), t.bedtime)
        assertEquals(480, t.sleepMinutes)
    }

    @Test fun totalsBedtimeNeverBeforeNowWhenPlanningToday() {
        // Hoy (jueves) a las 00:50 del viernes en el calendario: la hora de dormir sugerida (00:15) ya pasó.
        val t = PlanValidator.totals(ctx(FRI.atTime(0, 50), THU), emptyList())
        assertEquals(FRI.atTime(1, 0), t.bedtime)
        assertEquals(435, t.sleepMinutes)
    }
}
