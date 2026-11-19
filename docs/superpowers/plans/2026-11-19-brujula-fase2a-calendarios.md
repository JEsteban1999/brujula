# Brújula · Fase 2A (Calendarios) — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Traer a Brújula, en solo lectura, los eventos de Google Calendar y Outlook que ya están en el calendario del teléfono; mostrarlos en Hoy como "Fijo", tenerlos en cuenta para huecos libres, choques y horas de trabajo; y copiar a Google Calendar las citas que el usuario marque "Copiar a Google Calendar".

**Architecture:** Una interfaz `CalendarSource` envuelve `CalendarContract` (lectura de calendarios e instancias en un rango, con recurrencias resueltas por el sistema) y una `GoogleCalendarWriter` envuelve la escritura en el calendario principal de la cuenta Google. Las preferencias (calendarios elegidos) van en DataStore; la relación actividad↔evento de Google y la clasificación "cuenta como trabajo" van en Room (v3). Hoy y el formulario combinan las ocurrencias de Brújula con los eventos externos a través de un tipo común de bloque de línea de tiempo. Nada de esto usa a Claude: la clasificación automática llega en la Fase 2B; aquí el usuario marca a mano qué eventos son de trabajo.

**Tech Stack:** el de las fases anteriores (Kotlin, Compose, Room, DataStore, AlarmManager, JUnit 4, Robolectric, Turbine). APIs de Android: `CalendarContract.Calendars`, `CalendarContract.Instances`, `CalendarContract.Events`.

**Spec:** `docs/superpowers/specs/2026-11-18-brujula-design.md` — §2, §4 (regla de solo lectura), §5.1, §5.2b, §5.6, §8 (Calendarios: entrada, fuente de verdad, salida), §10 (horas de trabajo y marca de citas).

## Global Constraints

- Todo lo de las fases anteriores sigue vigente (paquete `app.brujula`, minSdk 31, español, 24 h, colores/fuentes, día lógico 04:00, tipos de día, avisos solo en vigilia).
- **Google y Outlook son de solo lectura para Brújula:** nunca se mueve, edita ni borra un evento importado. La única escritura permitida es crear/actualizar/borrar la **copia en Google** de una actividad de Brújula con `copyToGoogle = true`.
- **Nunca se escribe en Outlook** ni en un calendario que no sea el principal de una cuenta Google (`ACCOUNT_TYPE = "com.google"`, `IS_PRIMARY = 1`; si no hay columna/valor, el calendario cuyo `OWNER_ACCOUNT == ACCOUNT_NAME`).
- Las copias en Google se marcan con `Events.CUSTOM_APP_PACKAGE = "app.brujula"` y `Events.CUSTOM_APP_URI = "brujula://activity/{id}"`, sin recordatorios propios (Brújula ya avisa); la importación **excluye** cualquier evento con esa marca o cuyo id esté en la tabla de relación.
- Los eventos importados **no generan recordatorios de Brújula** (la app de calendario ya avisa) y **no se pueden marcar como hechos**.
- Permisos: `READ_CALENDAR` y `WRITE_CALENDAR` en tiempo de ejecución, pedidos desde "Tu rutina" (no al abrir la app). Sin permiso, la app funciona igual que hoy.
- Copia (español): sección `Calendarios`; botón `Conectar calendarios`; subtítulo sin permiso `Brújula lee tus calendarios de Google y Outlook para saber qué huecos tienes libres. Nunca los modifica.`; etiqueta de bloque `Fijo · {nombre del calendario}`; hoja de evento con `Cuenta como trabajo` y `Abrir en el calendario`; estado de copia en el formulario `Copiado a Google Calendar` / `No se pudo copiar: falta el permiso de calendario`.
- Eventos de todo el día: se muestran arriba de la línea de tiempo como fila compacta (`Todo el día · {título}`), no ocupan huecos ni cuentan como trabajo, no generan choques.
- Commits con el trailer de dos líneas (`Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` / `Claude-Session: https://claude.ai/code/session_01XN8G1c95yQpddiVFPfCNKU`).

## Review Focus

1. **Evento recurrente o que cruza la medianoche en Google/Outlook** (p. ej. una reunión semanal, o algo de 23:00 a 01:00): aparece en el día lógico correcto, una sola vez, y bloquea el hueco. → Task 1 (instancias) y Task 4.
2. **La copia de Brújula en Google no vuelve como evento externo duplicado.** → Task 5.
3. **Permiso de calendario negado o revocado después:** Hoy, el formulario y los recordatorios siguen funcionando; la sección de calendarios lo explica y ofrece arreglarlo. → Task 2 y Task 5.
4. **Un calendario elegido desaparece** (cuenta quitada del teléfono): no hay crash; se ignora. → Task 1 y Task 2.
5. **Editar o borrar una cita copiada:** la copia en Google se actualiza o se borra; si se desmarca "Copiar a Google Calendar", se borra la copia. → Task 5.

---

### Task 1: Fuente de calendarios del teléfono

**Files:**
- Create: `calendar/ExternalCalendar.kt` (modelos), `calendar/CalendarSource.kt` (interfaz), `calendar/DeviceCalendarSource.kt` (CalendarContract), `calendar/CalendarCursors.kt` (mapeo cursor→modelo, puro)
- Modify: `app/src/main/AndroidManifest.xml` (`READ_CALENDAR`, `WRITE_CALENDAR`), `BrujulaApp.kt` (AppContainer: `calendarSource`)
- Test: `calendar/CalendarCursorsTest.kt` (con `MatrixCursor`), `calendar/ExternalEventsTest.kt`

**Interfaces:**
- Produces:
  - `data class DeviceCalendar(val id: Long, val name: String, val accountName: String, val accountType: String, val color: Int, val isPrimaryGoogle: Boolean)` y `val DeviceCalendar.sourceLabel: String` (`Google` si `accountType == "com.google"`, `Outlook` si contiene `outlook` o `microsoft` (sin distinguir mayúsculas), si no `accountName`).
  - `data class ExternalEvent(val eventId: Long, val instanceBegin: Long, val calendarId: Long, val calendarName: String, val source: String, val title: String, val span: TimeSpan, val allDay: Boolean, val organizer: String?)` con `val key: String = "$eventId:$instanceBegin"`.
  - `interface CalendarSource { fun hasReadPermission(): Boolean; suspend fun calendars(): List<DeviceCalendar>; suspend fun events(calendarIds: Set<Long>, from: LocalDateTime, to: LocalDateTime): List<ExternalEvent> }` — `events` consulta `Instances` en el rango, excluye eventos con `CUSTOM_APP_PACKAGE == "app.brujula"`, eventos cancelados (`STATUS_CANCELED`) y los que el usuario rechazó (`SELF_ATTENDEE_STATUS == DECLINED`). Sin permiso o con un calendario inexistente devuelve lista vacía/filtrada, nunca lanza.
  - `fun eventsOnLogicalDay(day: LocalDate, events: List<ExternalEvent>): List<ExternalEvent>` (puro): los no de-todo-el-día cuyo `logicalDay(span.start) == day`, más los que empezaron el día anterior y se solapan con la vigilia de `day` (misma regla que las ocurrencias de Brújula); los de todo el día cuya fecha es `day`. Sin duplicados (por `key`).

- [ ] **Step 1: Pruebas que fallan**
  - `CalendarCursorsTest`: un `MatrixCursor` con columnas de `Instances` (2 filas, una de todo el día en UTC) → 2 `ExternalEvent` con horas locales correctas (zona `America/Bogota`), `allDay` correcto y el de todo el día en su fecha local; un cursor de `Calendars` con una cuenta Google primaria y una de Outlook → `isPrimaryGoogle` y `sourceLabel` (`Google`, `Outlook`).
  - `ExternalEventsTest`: evento miércoles 23:00–01:00 → aparece el miércoles, no el jueves; evento jueves 00:30–01:30 → cuenta para el miércoles lógico; evento de todo el día del jueves → solo jueves; mismas instancias repetidas en la entrada → una sola salida.
- [ ] **Step 2:** FAIL. **Step 3:** Implementar (el mapeo de cursores en funciones puras; `DeviceCalendarSource` solo arma la consulta y llama al mapeo). **Step 4:** Suite → PASS.
- [ ] **Step 5:** Commit `feat: read-only device calendar source`.

---

### Task 2: Elegir calendarios y pedir permiso (Tu rutina)

**Files:**
- Modify: `routine/RoutineRepository.kt` (o un `calendar/CalendarPrefs.kt` sobre el mismo DataStore), `settings/RoutineViewModel.kt`, `settings/RoutineScreen.kt`, `ui/MainActivity.kt` o la pantalla (lanzador de permisos)
- Test: `settings/RoutineViewModelTest.kt`, `calendar/CalendarPrefsTest.kt`

**Interfaces:**
- Produces:
  - `val selectedCalendarIds: Flow<Set<Long>?>` (`null` = el usuario aún no eligió) y `suspend fun setSelectedCalendarIds(ids: Set<Long>)`.
  - Regla: la primera vez que hay permiso y `selectedCalendarIds == null`, se seleccionan todos los calendarios visibles (se guarda ese conjunto). Después se respeta lo que el usuario elija. Ids que ya no existen se ignoran al leer.
  - `RoutineViewModel`: `val calendars: StateFlow<CalendarSectionState>` con `data class CalendarSectionState(val hasPermission: Boolean, val groups: List<CalendarGroup>)`, `data class CalendarGroup(val source: String, val accountName: String, val items: List<CalendarItem>)`, `data class CalendarItem(val calendar: DeviceCalendar, val selected: Boolean)`; `fun toggleCalendar(id: Long)`; `fun onPermissionResult(granted: Boolean)` (refresca y resincroniza recordatorios no, porque los externos no generan recordatorios; sí refresca Hoy vía el flujo).
  - Pantalla: sección `Calendarios` en "Tu rutina". Sin permiso: el subtítulo de Global Constraints y el botón `Conectar calendarios` (pide `READ_CALENDAR` y `WRITE_CALENDAR`). Con permiso: calendarios agrupados por cuenta (`Google · nombre@…`, `Outlook · …`) con un interruptor cada uno (48 dp) y una nota `Para ver Outlook aquí, activa en la app de Outlook la opción de sincronizar calendarios con el teléfono.` Si el permiso se revocó, vuelve al estado sin permiso.
- [ ] **Step 1: Pruebas que fallan** — `CalendarPrefsTest`: por defecto `null`; guardar y leer; `RoutineViewModelTest` con un `FakeCalendarSource`: sin permiso → `hasPermission=false`, grupos vacíos; con permiso y sin selección previa → todos seleccionados y guardados; `toggleCalendar` quita y pone; un id guardado que ya no existe no aparece ni rompe.
- [ ] **Step 2:** FAIL. **Step 3:** Implementar. **Step 4:** Suite + `:app:assembleDebug` → PASS.
- [ ] **Step 5:** Commit `feat: choose device calendars to import`.

---

### Task 3: Base de datos v3 — relación con Google y clasificación de trabajo

**Files:**
- Modify: `data/db/Entities.kt`, `data/db/Daos.kt`, `data/db/BrujulaDatabase.kt` (version 3, `AutoMigration(2, 3)`), `activities/ActivityRepository.kt`
- Create: `calendar/WorkClassificationRepository.kt`, `app/schemas/.../3.json` (generado)
- Test: `data/MigrationTest.kt` (2→3, y 1→3 encadenado), `calendar/WorkClassificationRepositoryTest.kt`

**Interfaces:**
- Produces:
  - `ActivityEntity` gana `googleEventId: Long?` (`@ColumnInfo(defaultValue = "NULL")`); `Activity` gana `val googleEventId: Long? = null`; `ActivityRepository.setGoogleEventId(activityId: Long, eventId: Long?)` y `fun observeGoogleEventIds(): Flow<Set<Long>>`.
  - Tabla `work_classifications(key TEXT PRIMARY KEY, isWork INTEGER NOT NULL)`.
  - `fun classificationKey(e: ExternalEvent): String` = título en minúsculas, sin espacios repetidos ni tildes al comparar (`Normalizer` NFD sin marcas), recortado.
  - `WorkClassificationRepository`: `fun observe(): Flow<Map<String, Boolean>>`, `suspend fun set(key: String, isWork: Boolean)`, `fun isWork(e: ExternalEvent, map: Map<String, Boolean>): Boolean` (sin entrada → `false`).
- [ ] **Step 1: Pruebas que fallan** — `MigrationTest`: base v2 con hábitos, registros y una actividad → v3 conserva todo y `googleEventId` queda `NULL`; 1→3 también. `WorkClassificationRepositoryTest`: "Reunión de equipo" y "reunion  de EQUIPO" dan la misma clave; marcar uno como trabajo hace que el otro también lo sea; sin marca → no es trabajo.
- [ ] **Step 2:** FAIL. **Step 3:** Implementar (generar y commitear `3.json`). **Step 4:** Suite → PASS.
- [ ] **Step 5:** Commit `feat: db v3 with google event link and work classifications`.

---

### Task 4: Eventos externos en Hoy, huecos, choques y horas de trabajo

**Files:**
- Modify: `today/TodayViewModel.kt`, `today/TimelineItem.kt` (y archivos de composables de Hoy que hagan falta), `activities/form/ActivityFormViewModel.kt`, `activities/Conflicts.kt` (si hace falta un tipo común), `activities/WorkHours.kt`
- Create: `calendar/ExternalEventSheet.kt` (hoja al tocar un evento externo)
- Test: `today/TodayViewModelTest.kt`, `activities/form/ActivityFormViewModelTest.kt`, `activities/WorkHoursTest.kt`

**Interfaces:**
- Consumes: Tasks 1–3.
- Produces:
  - `sealed interface TimelineBlock { val span: TimeSpan; val isNow: Boolean }` con `data class Own(val occurrence: Occurrence, override val isNow: Boolean)` y `data class External(val event: ExternalEvent, val countsAsWork: Boolean, override val isNow: Boolean)`. `TodayUiState.timeline: List<TimelineBlock>` (ordenado por inicio) y `TodayUiState.allDay: List<ExternalEvent>`. Los llamadores de `toggleDone` siguen recibiendo el `Own`.
  - Hoy lee eventos con `calendarSource.events(selectedIds, desde = inicio del día lógico − 1 día, hasta = fin + 1 día)` y aplica `eventsOnLogicalDay`. Se recarga al cambiar el día lógico, al volver a la pantalla (ON_RESUME) y cuando cambian los calendarios elegidos o las clasificaciones.
  - Huecos libres: los eventos externos (no de todo el día) ocupan tiempo igual que los propios.
  - Horas de trabajo: `workMinutes` suma bloques propios de tipo Trabajo (como hoy) **más** eventos externos clasificados como trabajo (solo la parte dentro del día lógico).
  - Bloque externo: etiqueta `Fijo · {calendarName}`, riel con el color del calendario, sin círculo de hecho; al tocarlo abre `ExternalEventSheet` con título, hora, origen, interruptor `Cuenta como trabajo` (guarda con `WorkClassificationRepository.set`) y `Abrir en el calendario` (`Intent.ACTION_VIEW` sobre `ContentUris.withAppendedId(Events.CONTENT_URI, eventId)` con `EXTRA_EVENT_BEGIN_TIME`).
  - Formulario: el conjunto ocupado para choques incluye los eventos externos de día−1/día/día+1; el mensaje de choque usa `Choca con {título} ({source}).`
- [ ] **Step 1: Pruebas que fallan** (con `FakeCalendarSource`)
  - `TodayViewModelTest`: un evento externo 15:00–16:00 aparece como `External` entre los bloques, parte el hueco libre de la tarde y no tiene acción de hecho; uno de todo el día va a `allDay` y no parte huecos; marcar "Cuenta como trabajo" suma 60 min a `workMinutes`; un evento externo del día anterior que cruza la medianoche y se solapa con la vigilia aparece una sola vez.
  - `ActivityFormViewModelTest`: una actividad nueva a las 15:30 choca con el evento externo 15:00–16:00 y el mensaje lo nombra con su origen.
  - `WorkHoursTest`: suma propios + externos marcados, sin contar los de todo el día.
- [ ] **Step 2:** FAIL. **Step 3:** Implementar. **Step 4:** Suite + `:app:assembleDebug :app:lintDebug` → PASS.
- [ ] **Step 5:** Commit `feat: show device calendar events on Today and use them for free time, conflicts and work hours`.

---

### Task 5: Copiar citas a Google Calendar

**Files:**
- Create: `calendar/GoogleCalendarWriter.kt` (interfaz + implementación con `CalendarContract.Events`), `calendar/GoogleCopySync.kt`
- Modify: `activities/form/ActivityFormViewModel.kt` (llamar a la sincronización al guardar/borrar y exponer estado), `activities/form/ActivityFormScreen.kt` (línea de estado), `BrujulaApp.kt` (AppContainer)
- Test: `calendar/GoogleCopySyncTest.kt`, `calendar/RecurrenceRuleTest.kt`, `activities/form/ActivityFormViewModelTest.kt`

**Interfaces:**
- Produces:
  - `interface GoogleCalendarWriter { fun hasWritePermission(): Boolean; suspend fun primaryGoogleCalendarId(): Long?; suspend fun insert(calendarId: Long, e: GoogleEventDraft): Long?; suspend fun update(eventId: Long, e: GoogleEventDraft): Boolean; suspend fun delete(eventId: Long): Boolean }`
  - `data class GoogleEventDraft(val title: String, val start: LocalDateTime, val end: LocalDateTime, val rrule: String?, val activityId: Long)` — la implementación pone `CUSTOM_APP_PACKAGE`, `CUSTOM_APP_URI`, `EVENT_TIMEZONE = ZoneId.systemDefault().id`, `HAS_ALARM = 0`; para repetición usa `RRULE` y `DURATION` en vez de `DTEND`.
  - `fun rruleFor(repeat: Repeat, date: LocalDate): String?`: `NONE` → null; `DAILY` → `FREQ=DAILY`; `WEEKDAYS` y `WORKDAYS` → `FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR` (Google no conoce los festivos; queda documentado en un comentario); `WEEKLY` → `FREQ=WEEKLY;BYDAY={día de date}`.
  - `enum class GoogleCopyStatus { NOT_REQUESTED, COPIED, NO_PERMISSION, NO_GOOGLE_ACCOUNT, FAILED }`.
  - `class GoogleCopySync(writer, activityRepository) { suspend fun afterSave(a: Activity): GoogleCopyStatus; suspend fun afterDelete(a: Activity) }`: si `copyToGoogle` y hay permiso y calendario principal → inserta (si no tiene `googleEventId`) o actualiza (si lo tiene; si la actualización falla porque el evento ya no existe, inserta uno nuevo) y guarda el id; si `copyToGoogle` es falso y tiene `googleEventId` → borra la copia y limpia el id; `afterDelete` borra la copia si existe. Errores → estado, nunca excepción.
  - Formulario: tras guardar, si el estado no es `NOT_REQUESTED/COPIED`, se queda un instante mostrando el aviso (`No se pudo copiar: falta el permiso de calendario` / `No hay una cuenta de Google en el teléfono` / `No se pudo copiar a Google Calendar`) antes de cerrar; la actividad igual se guarda en Brújula.
- [ ] **Step 1: Pruebas que fallan** (con `FakeGoogleCalendarWriter` en memoria)
  - `GoogleCopySyncTest`: guardar una cita con copia → inserta y guarda el id; editarla → actualiza el mismo evento; desmarcar la copia → borra y limpia el id; borrar la actividad → borra la copia; sin permiso → `NO_PERMISSION` y nada escrito; sin cuenta Google → `NO_GOOGLE_ACCOUNT`; actualizar un evento que ya no existe → inserta uno nuevo y guarda el nuevo id.
  - `RecurrenceRuleTest`: las cuatro reglas para un miércoles.
  - `ActivityFormViewModelTest`: con copia y sin permiso, la actividad se guarda y el estado expone `NO_PERMISSION`.
  - Exclusión (Review Focus 2): en `CalendarCursorsTest` o en una prueba de `DeviceCalendarSource` con cursor, una fila con `CUSTOM_APP_PACKAGE = "app.brujula"` no se importa.
- [ ] **Step 2:** FAIL. **Step 3:** Implementar. **Step 4:** Suite + `:app:assembleDebug :app:lintDebug` → PASS.
- [ ] **Step 5:** Commit `feat: copy appointments to the primary Google calendar`.

---

### Task 6: Verificación en el teléfono

- [ ] **Step 1:** Respaldo de datos (`adb exec-out run-as app.brujula tar -cf - databases files shared_prefs`) a `.superpowers/backups/`.
- [ ] **Step 2:** `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:installDebug` → verde e instalado.
- [ ] **Step 3:** En el teléfono, con el usuario: Tu rutina → `Conectar calendarios` → aceptar permisos → aparecen las cuentas de Google (y Outlook si está sincronizado) con sus calendarios; Hoy muestra los eventos de hoy como `Fijo · …`; crear una cita "Prueba Brújula" con "Copiar a Google Calendar" → aparece en Google Calendar y **no** se duplica en Hoy; borrarla → desaparece de Google Calendar.
- [ ] **Step 4:** Commit de ajustes si los hubo.
