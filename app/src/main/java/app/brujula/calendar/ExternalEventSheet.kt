package app.brujula.calendar

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.brujula.core.theme.BrujulaTheme
import app.brujula.core.time.formatTime

/** Intent para ver el evento en la app de calendario (nunca lo modifica). */
fun openInCalendarIntent(event: ExternalEvent): Intent =
    Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, event.eventId))
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.instanceBegin)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.instanceBegin + (event.span.minutes * 60_000L))

/** Abre el evento; si no hay una app que lo atienda, avisa en vez de fallar. */
fun openInCalendar(context: Context, event: ExternalEvent): Boolean = try {
    context.startActivity(openInCalendarIntent(event))
    true
} catch (_: ActivityNotFoundException) {
    Toast.makeText(context, "No hay una app de calendario para abrir este evento", Toast.LENGTH_LONG).show()
    false
} catch (_: SecurityException) {
    Toast.makeText(context, "No se pudo abrir el evento en el calendario", Toast.LENGTH_LONG).show()
    false
}

/** Hoja al tocar un evento importado: detalles, interruptor "Cuenta como trabajo" y abrir en el calendario. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExternalEventSheet(
    event: ExternalEvent,
    countsAsWork: Boolean,
    onCountsAsWork: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = BrujulaTheme.colors
    val context = LocalContext.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface,
        contentColor = c.text,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(event.title, color = c.text, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text(
                if (event.allDay) "Todo el día" else
                    "${formatTime(event.span.start.toLocalTime())} – ${formatTime(event.span.end.toLocalTime())}",
                color = c.textMuted, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            )
            Text("${event.source} · ${event.calendarName}", color = c.textMuted, fontSize = 14.sp)
            // Sin título no hay con qué recordar la marca; los de todo el día nunca cuentan.
            if (!event.allDay && classificationKey(event).isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(value = countsAsWork, role = Role.Switch, onValueChange = onCountsAsWork),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Cuenta como trabajo", color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("Se recuerda para eventos con el mismo título.", color = c.textMuted, fontSize = 12.sp)
                    }
                    Switch(checked = countsAsWork, onCheckedChange = null)
                }
            }
            OutlinedButton(
                onClick = { openInCalendar(context, event) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) { Text("Abrir en el calendario") }
        }
    }
}
