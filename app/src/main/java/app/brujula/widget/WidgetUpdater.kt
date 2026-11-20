package app.brujula.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.View
import android.widget.RemoteViews
import app.brujula.R
import app.brujula.container
import app.brujula.habits.HabitIcon
import app.brujula.routine.logicalDay
import app.brujula.today.HabitChip
import app.brujula.today.todayHabitChips
import app.brujula.ui.DeepLinks
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.time.ZoneId

/** Colores de `Inicio.dc.html` (claro y oscuro); se aplican en código sobre un solo layout. */
private class WidgetPalette(
    val text: Int,
    val secondary: Int,
    val accent: Int,
    val onAccent: Int,
    val accentDark: Int,
    val accent2Dark: Int,
    val card: Int,
    val chip1: Int,
    val chip2: Int,
    val mic: Int,
)

private val LIGHT = WidgetPalette(
    text = Color.parseColor("#14121F"), secondary = Color.parseColor("#55516E"), accent = Color.parseColor("#4B3BFF"),
    onAccent = Color.parseColor("#FFFFFF"), accentDark = Color.parseColor("#3A2BE0"), accent2Dark = Color.parseColor("#9A6200"),
    card = R.drawable.widget_card_light, chip1 = R.drawable.widget_chip1_light, chip2 = R.drawable.widget_chip2_light,
    mic = R.drawable.widget_mic_light,
)

private val DARK = WidgetPalette(
    text = Color.parseColor("#F1F0FF"), secondary = Color.parseColor("#A9A5C9"), accent = Color.parseColor("#7B6CFF"),
    onAccent = Color.parseColor("#0F0D1F"), accentDark = Color.parseColor("#A79CFF"), accent2Dark = Color.parseColor("#FFC657"),
    card = R.drawable.widget_card_dark, chip1 = R.drawable.widget_chip1_dark, chip2 = R.drawable.widget_chip2_dark,
    mic = R.drawable.widget_mic_dark,
)

private class HabitSlot(val box: Int, val icon: Int, val name: Int, val count: Int, val plus: Int)

private val SLOTS = listOf(
    HabitSlot(R.id.widget_habit1, R.id.widget_habit1_icon, R.id.widget_habit1_name, R.id.widget_habit1_count, R.id.widget_habit1_plus),
    HabitSlot(R.id.widget_habit2, R.id.widget_habit2_icon, R.id.widget_habit2_name, R.id.widget_habit2_count, R.id.widget_habit2_plus),
)

private const val REQ_BODY = 1
private const val REQ_MIC = 2
private const val REQ_ALARM = 3
private const val REQ_HABIT_BASE = 1_000
private const val REQ_HABIT_RANGE = 100_000

/** Pinta el widget y programa la alarma de su próxima frontera. */
object WidgetUpdater {
    const val ACTION_REFRESH = "app.brujula.widget.REFRESH"

    private fun component(context: Context) = ComponentName(context, BrujulaWidgetProvider::class.java)

    fun hasWidgets(context: Context): Boolean =
        AppWidgetManager.getInstance(context).getAppWidgetIds(component(context)).isNotEmpty()

    /** Sin widgets puestos no calcula nada. Lee Room y DataStore (no llamar desde el hilo principal). */
    suspend fun updateAll(context: Context) {
        val app = context.applicationContext
        val manager = AppWidgetManager.getInstance(app)
        val ids = manager.getAppWidgetIds(component(app))
        if (ids.isEmpty()) return
        val c = app.container
        val now = c.clock.now()
        val day = logicalDay(now)
        val snapshot = c.daySnapshots.snapshot(day)
        val habits = c.habitRepository.observeHabits().first()
        val counts = c.habitRepository.observeCountsFor(day).first()
        val routine = c.routineRepository.routine.first()
        val model = buildWidgetModel(now, snapshot, todayHabitChips(habits, counts, day), routine)
        manager.updateAppWidget(ids, render(app, model))
        scheduleNext(app, model.nextRefresh)
    }

    internal fun render(context: Context, m: WidgetModel): RemoteViews {
        val p = if (m.dark) DARK else LIGHT
        val v = RemoteViews(context.packageName, R.layout.widget_brujula)
        v.setInt(R.id.widget_root, "setBackgroundResource", p.card)
        v.setTextViewText(R.id.widget_now_label, m.nowLabel)
        v.setTextViewText(R.id.widget_now_title, m.nowTitle)
        v.setTextViewText(R.id.widget_next_text, m.nextText)
        v.setTextColor(R.id.widget_now_label, p.secondary)
        v.setTextColor(R.id.widget_now_title, p.text)
        v.setTextColor(R.id.widget_next_label, p.secondary)
        v.setTextColor(R.id.widget_next_text, p.accentDark)

        SLOTS.forEachIndexed { i, slot ->
            val chip = m.habits.getOrNull(i)
            if (chip == null) {
                v.setViewVisibility(slot.box, View.GONE)
            } else {
                bindHabit(context, v, slot, chip, if (i == 0) p.chip1 else p.chip2, if (i == 0) p.accent2Dark else p.accentDark, p)
            }
        }
        v.setViewVisibility(R.id.widget_habit_spacer, if (m.habits.isEmpty()) View.VISIBLE else View.GONE)

        v.setInt(R.id.widget_mic, "setBackgroundResource", p.mic)
        v.setInt(R.id.widget_mic, "setColorFilter", p.onAccent)
        v.setContentDescription(R.id.widget_mic, "Dictar con la voz")

        val body = activity(context, REQ_BODY, DeepLinks.TODAY)
        v.setOnClickPendingIntent(R.id.widget_root, body)
        v.setOnClickPendingIntent(R.id.widget_top, body)
        // Task 7 cambia esto por la ventanita de dictado.
        v.setOnClickPendingIntent(R.id.widget_mic, activity(context, REQ_MIC, DeepLinks.VOICE))
        return v
    }

    private fun bindHabit(context: Context, v: RemoteViews, slot: HabitSlot, chip: HabitChip, bg: Int, tint: Int, p: WidgetPalette) {
        v.setViewVisibility(slot.box, View.VISIBLE)
        v.setInt(slot.box, "setBackgroundResource", bg)
        v.setImageViewResource(
            slot.icon,
            when (chip.icon) {
                HabitIcon.WATER -> R.drawable.ic_widget_water
                HabitIcon.LIP_BALM -> R.drawable.ic_widget_balm
                else -> R.drawable.ic_widget_habit
            },
        )
        v.setInt(slot.icon, "setColorFilter", tint)
        v.setInt(slot.plus, "setColorFilter", tint)
        v.setTextViewText(slot.name, chip.name)
        v.setTextColor(slot.name, p.secondary)
        v.setTextViewText(slot.count, "${chip.count} / ${chip.target}")
        v.setTextColor(slot.count, p.text)
        v.setContentDescription(
            slot.box,
            when (chip.icon) {
                HabitIcon.WATER -> "Sumar un vaso de agua"
                HabitIcon.LIP_BALM -> "Marcar bálsamo labial"
                else -> "Sumar uno a ${chip.name}"
            },
        )
        val add = Intent(context, WidgetActionReceiver::class.java)
            .setAction(WidgetActionReceiver.ACTION_ADD_HABIT)
            .setData(Uri.parse("brujula://widget/habit/${chip.habitId}"))
            .putExtra(WidgetActionReceiver.EXTRA_HABIT_ID, chip.habitId)
        val request = REQ_HABIT_BASE + (chip.habitId % REQ_HABIT_RANGE).toInt()
        v.setOnClickPendingIntent(
            slot.box,
            PendingIntent.getBroadcast(context, request, add, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
        )
    }

    /** Siempre por [DeepLinks.intent]; el código de petición distinto evita que el cuerpo y el micrófono se confundan (los extras no cuentan). */
    private fun activity(context: Context, request: Int, route: String): PendingIntent =
        PendingIntent.getActivity(
            context, request, DeepLinks.intent(context, route), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun refreshIntent(context: Context): PendingIntent =
        PendingIntent.getBroadcast(
            context, REQ_ALARM,
            Intent(context, BrujulaWidgetProvider::class.java).setAction(ACTION_REFRESH),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Una sola alarma `RTC` inexacta (no despierta el teléfono); reemplaza a la anterior. */
    fun scheduleNext(context: Context, at: LocalDateTime) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val millis = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarms.set(AlarmManager.RTC, millis, refreshIntent(context))
    }

    fun cancelRefresh(context: Context) {
        context.getSystemService(AlarmManager::class.java)?.cancel(refreshIntent(context))
    }
}
