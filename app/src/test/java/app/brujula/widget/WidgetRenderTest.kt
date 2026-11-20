package app.brujula.widget

import android.app.AlarmManager
import android.content.Context
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import app.brujula.R
import app.brujula.habits.HabitIcon
import app.brujula.today.HabitChip
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
class WidgetRenderTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val at = LocalDateTime.of(2026, 11, 18, 10, 30)

    private fun model(habits: List<HabitChip>, dark: Boolean = false) =
        WidgetModel("Ahora · hasta 10:30", "Trabajo", "Ejercicio 10:30", habits, dark, at)

    private fun inflate(m: WidgetModel): View = WidgetUpdater.render(ctx, m).apply(ctx, FrameLayout(ctx))

    @Test fun rendersTextAndAccessibleButtons() {
        val v = inflate(model(listOf(HabitChip(1, "Agua", HabitIcon.WATER, 3, 8), HabitChip(2, "Bálsamo", HabitIcon.LIP_BALM, 1, 4))))
        assertEquals("Trabajo", v.findViewById<TextView>(R.id.widget_now_title).text)
        assertEquals("3 / 8", v.findViewById<TextView>(R.id.widget_habit1_count).text)
        assertEquals("Sumar un vaso de agua", v.findViewById<View>(R.id.widget_habit1).contentDescription)
        assertEquals("Marcar bálsamo labial", v.findViewById<View>(R.id.widget_habit2).contentDescription)
        assertEquals("Dictar con la voz", v.findViewById<View>(R.id.widget_mic).contentDescription)
    }

    @Test fun hidesUnusedHabitButtons() {
        val one = inflate(model(listOf(HabitChip(1, "Agua", HabitIcon.WATER, 0, 8)), dark = true))
        assertEquals(View.VISIBLE, one.findViewById<View>(R.id.widget_habit1).visibility)
        assertEquals(View.GONE, one.findViewById<View>(R.id.widget_habit2).visibility)
        val none = inflate(model(emptyList()))
        assertEquals(View.GONE, none.findViewById<View>(R.id.widget_habit1).visibility)
        assertEquals(View.VISIBLE, none.findViewById<View>(R.id.widget_habit_spacer).visibility)
    }

    @Test fun scheduleNextSetsOneNonWakeupRtcAlarm() {
        WidgetUpdater.scheduleNext(ctx, at)
        WidgetUpdater.scheduleNext(ctx, at.plusMinutes(5))
        val alarms = shadowOf(ctx.getSystemService(AlarmManager::class.java)).scheduledAlarms
        assertEquals(1, alarms.size)
        assertEquals(AlarmManager.RTC, alarms[0].type)
    }
}
