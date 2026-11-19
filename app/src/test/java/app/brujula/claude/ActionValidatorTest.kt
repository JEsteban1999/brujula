package app.brujula.claude

import app.brujula.activities.ActivityType
import app.brujula.activities.Repeat
import app.brujula.habits.HabitGroup
import app.brujula.habits.HabitIcon
import app.brujula.habits.HabitSchedule
import app.brujula.habits.HabitWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ActionValidatorTest {
    private val wed = LocalDate.of(2026, 11, 18)
    private val ctx = dictationCtx(now = wed.atTime(10, 0))

    private fun activity(
        date: String? = "2026-11-19",
        start: String? = "15:00",
        durationMin: Int? = 60,
        activityType: String? = "APPOINTMENT",
        repeat: String? = null,
        copyToGoogle: Boolean? = null,
        title: String? = "Dentista",
    ) = DictationAction(
        type = "create_activity", title = title, activityType = activityType, date = date, start = start,
        durationMin = durationMin, repeat = repeat, copyToGoogle = copyToGoogle,
    )

    private fun habit(mode: String?, intervalMin: Int? = null, times: List<String>? = null, beforeBedMin: Int? = null, workOnly: Boolean? = null, title: String? = "Tomar agua") =
        DictationAction(type = "create_habit", title = title, habitMode = mode, intervalMin = intervalMin, times = times, beforeBedMin = beforeBedMin, workOnly = workOnly)

    private fun assertInvalid(a: DictationAction, c: DictationContext = ctx) {
        val v = ActionValidator.validate(a, c)
        assertTrue("Se esperaba Invalid y fue $v", v is Validated.Invalid)
        assertTrue((v as Validated.Invalid).reason.isNotBlank())
    }

    @Test fun queuedOneOffInThePastIsInvalidAgainstTheCurrentTimeNotTheDictationTime() {
        val mon = LocalDate.of(2026, 11, 23)
        // Dictado el lunes 23:00, procesado el martes 08:00: "hoy a las 23:30" (lunes) ya pasó.
        val queued = dictationCtx(now = mon.plusDays(1).atTime(8, 0), dictatedAt = mon.atTime(23, 0))
        assertInvalid(activity(date = "2026-11-23", start = "23:30"), queued)
        // Lo que cae hacia adelante sigue siendo válido: "mañana" (martes) a las 09:00.
        assertTrue(ActionValidator.validate(activity(date = "2026-11-24", start = "09:00"), queued) is Validated.Activity)
    }

    @Test fun validAppointmentBecomesActivityWithGoogleCopy() {
        val v = ActionValidator.validate(activity(), ctx) as Validated.Activity
        val a = v.activity
        assertEquals("Dentista", a.title)
        assertEquals(ActivityType.APPOINTMENT, a.type)
        assertEquals(LocalDate.of(2026, 11, 19), a.date)
        assertEquals(LocalTime.of(15, 0), a.start)
        assertEquals(60, a.durationMin)
        assertEquals(Repeat.NONE, a.repeat)
        assertTrue(a.copyToGoogle)
        assertEquals(0L, a.id)
        assertEquals(15, a.remindBeforeMin)
    }

    @Test fun personalActivityDoesNotCopyToGoogleByDefault() {
        val a = (ActionValidator.validate(activity(activityType = "PERSONAL", repeat = "WEEKLY"), ctx) as Validated.Activity).activity
        assertFalse(a.copyToGoogle)
        assertEquals(Repeat.WEEKLY, a.repeat)
    }

    @Test fun missingDurationDefaultsToAnHour() {
        val a = (ActionValidator.validate(activity(durationMin = null), ctx) as Validated.Activity).activity
        assertEquals(60, a.durationMin)
    }

    @Test fun pastDateIsInvalid() = assertInvalid(activity(date = "2026-11-17"))

    @Test fun timeAlreadyPassedTodayIsInvalid() = assertInvalid(activity(date = "2026-11-18", start = "08:00"))

    @Test fun dateBeyondAYearIsInvalid() = assertInvalid(activity(date = "2027-11-19"))

    @Test fun malformedDateIsInvalid() = assertInvalid(activity(date = "19/11/2026"))

    @Test fun zeroDurationIsInvalid() = assertInvalid(activity(durationMin = 0))

    @Test fun durationOver720IsInvalid() = assertInvalid(activity(durationMin = 800))

    @Test fun timeOutside24hIsInvalid() {
        assertInvalid(activity(start = "24:00"))
        assertInvalid(activity(start = "7:75"))
        assertInvalid(activity(start = null))
    }

    @Test fun unknownActivityTypeIsInvalid() = assertInvalid(activity(activityType = "MEETING"))

    @Test fun unknownRepeatIsInvalid() = assertInvalid(activity(repeat = "MONTHLY"))

    @Test fun blankTitleIsInvalid() = assertInvalid(activity(title = "  "))

    @Test fun unknownActionTypeIsInvalid() = assertInvalid(DictationAction(type = "delete_activity", title = "Dentista"))

    @Test fun afterMidnightTomorrowsEarlyHourIsValid() {
        // Miércoles 23:00 (día lógico miércoles): "a las 12:30 de la noche" es el jueves 00:30.
        val late = dictationCtx(now = wed.atTime(23, 0))
        val a = (ActionValidator.validate(activity(date = "2026-11-19", start = "00:30", activityType = "PERSONAL"), late) as Validated.Activity).activity
        assertEquals(LocalTime.of(0, 30), a.start)
    }

    @Test fun at0030TheLogicalDayStillAcceptsTomorrowMorning() {
        // Jueves 00:30 (día lógico miércoles): "mañana a las 10" es el jueves 19 a las 10:00.
        val late = dictationCtx(now = wed.plusDays(1).atTime(0, 30))
        assertTrue(ActionValidator.validate(activity(date = "2026-11-19", start = "10:00"), late) is Validated.Activity)
    }

    @Test fun habitIntervalOf5IsInvalid() = assertInvalid(habit("EVERY", intervalMin = 5))

    @Test fun habitIntervalOver480IsInvalid() = assertInvalid(habit("EVERY", intervalMin = 600))

    @Test fun unknownHabitModeIsInvalid() = assertInvalid(habit("SOMETIMES"))

    @Test fun habitWithBadFixedTimeIsInvalid() = assertInvalid(habit("AT_TIMES", times = listOf("08:00", "25:10")))

    @Test fun habitWithoutFixedTimesIsInvalid() = assertInvalid(habit("AT_TIMES", times = emptyList()))

    @Test fun everyHabitGetsAutoTargetAndIcon() {
        val h = (ActionValidator.validate(habit("EVERY", intervalMin = 60), ctx) as Validated.Habit).habit
        assertEquals(HabitSchedule.Every(60), h.schedule)
        assertTrue(h.dailyTarget > 1)
        assertEquals(HabitIcon.WATER, h.icon)
        assertEquals(HabitWindow.AWAKE, h.window)
        assertTrue(h.active)
    }

    @Test fun fixedTimesHabitIsSortedAndCountsEachTime() {
        val h = (ActionValidator.validate(habit("AT_TIMES", times = listOf("20:00", "08:00", "08:00"), title = "Tomar la pastilla"), ctx) as Validated.Habit).habit
        assertEquals(HabitSchedule.AtTimes(listOf(LocalTime.of(8, 0), LocalTime.of(20, 0))), h.schedule)
        assertEquals(2, h.dailyTarget)
        assertEquals(HabitIcon.PILL, h.icon)
    }

    @Test fun beforeBedHabitIsOncePerDay() {
        val h = (ActionValidator.validate(habit("BEFORE_BED", beforeBedMin = 30, workOnly = true, title = "Leer"), ctx) as Validated.Habit).habit
        assertEquals(HabitSchedule.BeforeBed(30), h.schedule)
        assertEquals(1, h.dailyTarget)
        assertEquals(HabitWindow.AWAKE, h.window)
    }

    @Test fun workOnlyHabitUsesWorkWindow() {
        val h = (ActionValidator.validate(habit("EVERY", intervalMin = 45, workOnly = true, title = "Estirar"), ctx) as Validated.Habit).habit
        assertEquals(HabitWindow.WORK, h.window)
        assertEquals(HabitGroup.WELLNESS, h.group)
    }

    @Test fun nonWorkHabitIsEssential() {
        val h = (ActionValidator.validate(habit("EVERY", intervalMin = 45, workOnly = false, title = "Estirar"), ctx) as Validated.Habit).habit
        assertEquals(HabitWindow.AWAKE, h.window)
        assertEquals(HabitGroup.ESSENTIAL, h.group)
        val bed = (ActionValidator.validate(habit("BEFORE_BED", beforeBedMin = 30, workOnly = true, title = "Leer"), ctx) as Validated.Habit).habit
        assertEquals(HabitGroup.ESSENTIAL, bed.group)
    }

    @Test fun moreFixedTimesThanTheDailyMaximumIsInvalid() {
        val hundred = (0 until 100).map { "%02d:%02d".format(it / 60, it % 60) }
        assertInvalid(habit("AT_TIMES", times = hundred))
        assertTrue(ActionValidator.validate(habit("AT_TIMES", times = hundred.take(99)), ctx) is Validated.Habit)
    }

    @Test fun beforeBedOutOfRangeIsInvalid() = assertInvalid(habit("BEFORE_BED", beforeBedMin = 5))

    @Test fun questionAndUnsupportedPassThrough() {
        assertEquals(
            Validated.Question("¿A qué hora es la cita?"),
            ActionValidator.validate(DictationAction(type = "need_more_info", question = " ¿A qué hora es la cita? "), ctx),
        )
        val u = ActionValidator.validate(DictationAction(type = "unsupported", question = "Por ahora no puedo mover cosas."), ctx)
        assertEquals(Validated.Unsupported("Por ahora no puedo mover cosas."), u)
        val noText = ActionValidator.validate(DictationAction(type = "unsupported"), ctx) as Validated.Unsupported
        assertTrue(noText.text.isNotBlank())
    }

    @Test fun questionWithoutTextIsInvalid() = assertInvalid(DictationAction(type = "need_more_info"))

    @Test fun validActivityComesOutWithDictationOrigin() {
        val v = ActionValidator.validate(activity(), ctx) as Validated.Activity
        assertEquals(app.brujula.activities.ActivityOrigin.DICTATION, v.activity.origin)
    }
}
