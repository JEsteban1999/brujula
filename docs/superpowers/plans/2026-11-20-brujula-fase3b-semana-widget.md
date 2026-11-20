# Brújula · Fase 3B (semana, sueño, widget y sugerencias) — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ver y tocar la semana completa (horas de trabajo frente a la meta, ejercicio, sueño y los eventos de cada día, editables), registrar el sueño a partir de la rutina, tener un widget de inicio con bloque actual, contadores y dictado en una ventanita flotante, y recibir de Claude sugerencias de hábitos que el usuario acepta o rechaza con deshacer.

**Architecture:** Semana reutiliza las reglas de Hoy: la lógica de `DaySnapshotProvider` pasa a una función pura `buildDaySnapshot` y `buildWeek` la aplica a los siete días con una sola lectura de datos y de calendarios. El sueño es una tabla nueva `sleep_records` (DB v6) que solo guarda los ajustes reales ("Ya me acuesto" y la primera apertura de la mañana); lo demás se estima con la rutina. El widget usa `RemoteViews` sin dependencias nuevas, con un modelo puro (`buildWidgetModel`) que se refresca cuando cambian los datos (proceso vivo) y con una alarma inexacta que no despierta el teléfono en la próxima frontera (fin de bloque, cambio de tema, 04:00). La ventanita es una actividad translúcida que reutiliza `VoiceViewModel`, `ActionExecutor` y la cola de pendientes de la 2B. Las sugerencias son una llamada semanal a Claude Haiku 5.5 con conteos agregados; la app valida el cambio (intervalo, horas o meta de un hábito existente), lo guarda en DataStore y lo aplica solo con `Sí, porfa`, con deshacer.

**Tech Stack:** el de las fases anteriores (Kotlin, Compose, Room, DataStore, AlarmManager, `com.anthropic:anthropic-java:3.9.0`, `SpeechRecognizer`) más `AppWidgetProvider` + `RemoteViews` del framework. Sin dependencias nuevas.

**Spec:** `docs/superpowers/specs/2026-11-18-brujula-design.md`. Secciones: §5.5 (última viñeta: sugerencias), §5.6 (Semana), §5.7 (widget), §6 (colores y tema), §8, §9, §10 (sueño), §11 (días de descanso y festivos) y §12.

**Bocetos (texto y disposición):** `Semana.dc.html`, `SemanaOscuro.dc.html`, `Inicio.dc.html`, `InicioOscuro.dc.html` y `Habitos.dc.html` (tarjeta `Sí, porfa` / `Así está bien`), todos en `C:/Users/juesn/AppData/Local/Temp/claude/05a7d0fa803c1f3a/739e5df5-de06-4286-b8a7-cdc6e26ed341/scratchpad/agenda-design/project/`.

**Referencia de API:** antes de tocar el SDK, leer la skill `claude-api` (`java/claude-api/README.md` y `tool-use.md` § Structured Output) en `C:/Users/juesn/AppData/Local/Temp/claude/bundled-skills/2.1.328/8f50340ae15c0c2a8e15b5f0e8a724ad/claude-api/`. Copiar el patrón de `ClassificationCall` en `claude/AnthropicClaudeGateway.kt` (endpoint no beta, Haiku) y no adivinar nombres.

**Rutas:** el código vive en `app/src/main/java/app/brujula/` y las pruebas en `app/src/test/java/app/brujula/`; los recursos en `app/src/main/res/`.

## Decisiones de diseño (tomadas por el planificador)

- **D1 · Sueño en la DB v6.** Tabla `sleep_records(night PK, bedAt?, wakeAt?)` con `AutoMigration(from = 5, to = 6)`. *Por qué:* es un registro diario con historial para semanas pasadas (§8 "registros"), y agregar una tabla migra sola.
- **D2 · Cómo se ajusta el sueño (§10).** Lo planeado sale de la rutina: acostarse = fin de la vigilia de la noche, levantarse = hora de levantarse del día siguiente. Dos ajustes reales: el botón `Ya me acuesto` del aviso para dormir guarda `bedAt = ahora`, y la primera vez que se abre la app entre las 04:00 y 2 h después de la hora de levantarse guarda `wakeAt = ahora` para la noche anterior (una sola vez). Un valor absurdo (despertar antes de acostarse, más de 16 h) se ignora y se usa la rutina. *Por qué:* es literal a §10, sin sensores, y nunca deja un promedio roto.
- **D3 · Semana con las mismas reglas de Hoy.** `buildDaySnapshot` (pura) es la única cuenta de minutos de trabajo y ejercicio: bloques `WORK` propios con overrides + externos clasificados como trabajo, recortados al día lógico. Días pasados = lo que quedó; hoy = el mismo número de la barra de Hoy; días futuros = lo planeado (mismo cálculo, otro estilo). *Por qué:* Hoy, la Propuesta y Semana nunca pueden dar números distintos.
- **D4 · Semana mostrada.** Lunes a domingo del día lógico, con flechas a la semana anterior y siguiente, de −26 a +4 semanas. Al entrar se selecciona hoy (o el lunes en otra semana). *Por qué:* el boceto trae las flechas; el límite evita consultas absurdas al calendario.
- **D5 · Totales.**
  - **Promedio de trabajo:** solo días laborales (§11) hasta hoy inclusive; en una semana futura, los laborales planeados (subtítulo `Planeado`). Festivos y fines de semana muestran su barra pero no entran al promedio. Subtítulo según `workStatus`: `Muy por debajo` / `Debajo de tu meta` / `En tu meta` / `Sobre tu meta`.
  - **Ejercicio:** `{hechas} / {agendadas}` sesiones `EXERCISE` de la semana completa, incluidas las futuras.
  - **Sueño:** promedio de las noches cuya mañana cae en la semana y ya llegó (la de hoy cuenta desde la hora de levantarse o si ya hay `wakeAt`); sin noches → `–`.
  - *Por qué:* §11 quita la meta en descanso, y el boceto muestra "3 / 5" como hechas sobre agendadas.
- **D6 · Detalle del día.**
  - Eventos del día lógico con punto de color por origen: Brújula (acento), Google (acento 2), Outlook (texto); otra cuenta va en gris con su nombre. Los de todo el día van primero con `Todo el día`.
  - Tocar una actividad propia de una sola vez abre el formulario de siempre (editar o borrar).
  - Una recurrente de hoy en adelante abre una hoja con `Editar la serie` y `Quitar solo este día`. Quitar escribe un override `skipped` y da `Deshacer`; no aparece si la serie se copia a Google (D4 de la 3A).
  - Un externo abre `ExternalEventSheet` (corregir si cuenta como trabajo).
  - Botones `Agregar a este día` (formulario con la primera hora libre) y `Que Claude arme este día` (solo de hoy a hoy + 6).
  - *Por qué:* es la necesidad real de editar días futuros, y todo reutiliza piezas probadas.
- **D7 · Widget con `RemoteViews`, sin Glance.** *Por qué:* Glance arrastra WorkManager y su propia capa de sesiones. El widget es un solo layout fijo con cuatro botones, el APK ya pesa 109 MB, y un modelo puro + un render delgado se prueban mejor.
- **D8 · Forma del widget.** Un solo tamaño 4×2 (mín. 250×110 dp, se estira a lo ancho), como `Inicio.dc.html`:
  - fila 1: ícono, `Ahora · hasta 10:30` + título, y a la derecha `Siguiente` + `Ejercicio 10:30`;
  - fila 2: los mismos dos hábitos que muestra Hoy (por defecto agua y bálsamo) con `{x} / {y}` y "+", y el micrófono redondo.

  Sigue el tema **de la app** (Claro / Oscuro / Automático desde las 21:00), no el del sistema; los colores se aplican en código sobre un solo layout. *Por qué:* §5.7 dice "sigue el tema claro/oscuro", y el de la app es el que el usuario eligió.
- **D9 · Cuándo se refresca el widget.**
  - Cuando cambian los datos, con el proceso vivo: rutina, actividades, hechos, overrides, hábitos y cualquier registro; debounce de 1 s y solo si hay widgets puestos.
  - Una sola alarma inexacta `RTC` (no despierta) en la próxima frontera: fin del bloque actual, inicio del siguiente, cambio de tema, 04:00, y como máximo 60 min.
  - También al reiniciar o cambiar la hora; `updatePeriodMillis = 0`.

  *Por qué:* sin sondeo y sin despertar el teléfono; con la pantalla apagada nadie mira el widget.
- **D10 · Ventanita de dictado.** `QuickVoiceActivity`: translúcida, no exportada, fuera de Recientes, con su propia tarea. Reutiliza `VoiceViewModel` + `ActionExecutor` + `PendingDictations`.
  - **Permiso:** se pide en la propia ventanita. Si se niega, `Sin permiso de micrófono.` + `Abrir Brújula` (pantalla de voz).
  - **Sin internet:** se guarda en la cola de la 2B: `Lo guardé. Lo agendo cuando vuelva internet.`
  - **Sin clave o clave inválida:** también se guarda (nuevo `queueWithoutKey`): `Lo guardé. Agrega tu clave de Claude en Tu rutina y lo agendo.`
  - **Resultado:** `Agendado` + `Deshacer`, y se cierra sola a los 4 s (1,5 s después de `Deshecho`, 3 s después de guardar).
  - **Pedido de plan:** `Ver propuesta` abre la app por `DeepLinks.intent` con el secreto.
  - **Pregunta de Claude:** botón `Responder` (vuelve a escuchar).
  - **Tocar fuera, Atrás o Inicio:** cierra; si Claude estaba entendiendo, el dictado pasa a la cola (lógica actual).

  *Por qué:* el dictado nunca se pierde, y la ventana nunca se queda abierta esperando.
- **D11 · Modelo de sugerencias.** `ClaudeModels.SUGGESTIONS = "claude-haiku-5-5"`, esfuerzo `MEDIUM` (el predeterminado de Haiku 5.5), endpoint no beta, `maxTokens` 4000 y 60 s de espera, sin `fallbacks` (Haiku 5.5 los rechaza con 400). *Por qué:* leer una tabla de conteos y proponer un cambio acotado es una tarea simple; corre en segundo plano sin nadie esperando; §9 pide el modelo económico cuando basta; y la app valida cada cambio, así que un error de criterio no se aplica solo.
- **D12 · Cuándo se piden.**
  - **Automático:** al abrir Hábitos, si pasaron ≥ 7 días desde la última corrida exitosa, ≥ 1 día desde el último intento, hay ≥ 7 días lógicos con registros en los últimos 14, hay clave y hay red.
  - **A pedido:** botón `Pedir una sugerencia a Claude`, con ≥ 3 días de registros y un solo vuelo.
  - Se muestra una sugerencia a la vez.

  *Por qué:* como mucho una llamada automática por semana (y un reintento por día), nunca en bucle y nunca sin clave ni red.
- **D13 · Qué puede cambiar una sugerencia.** Solo un hábito existente, y solo una de tres cosas:
  - **intervalo** de un hábito "cada N min", con un `desde` opcional que crea un tramo más seguido a partir de esa hora;
  - **horas** de un hábito de horas fijas;
  - **meta** diaria.

  Nada de crear, borrar, desactivar ni cambiar días o ventana. El tramo es `HabitSchedule.Every.faster: FasterStretch?`, guardado en el mismo texto del horario (`every:90|15:00/60`), sin migración. *Por qué:* el ejemplo de la spec ("¿te aviso más seguido después de las 3 pm?") no cabía en el modelo de un solo intervalo.
- **D14 · Sugerencias en DataStore.** JSON con `org.json` como `PendingDictations`: la sugerencia actual, las últimas 20 y las marcas de última corrida e intento. *Por qué:* son raras y pequeñas y no necesitan consultas; así la v6 tiene una sola tabla.
- **D15 · Aceptar, deshacer y rechazar.**
  - `Sí, porfa` escribe el hábito y resincroniza. La tarjeta pasa a `Listo: …` + `Deshacer` hasta que termina el día lógico.
  - Deshacer restaura lo anterior solo si el hábito sigue como lo dejó la sugerencia; si no, `Ya cambiaste ese hábito; no lo toqué.`.
  - Aceptar una sugerencia cuyo hábito cambió o se borró → `Ese hábito cambió desde la sugerencia. No lo toqué.`.
  - `Así está bien` la descarta: el mismo hábito y tipo de cambio no se vuelve a sugerir en 30 días (se le dice a Claude y además se filtra localmente).

  *Por qué:* es la regla C para hábitos, y deshacer nunca pisa un cambio posterior (como D16 de la 3A).
- **D16 · Lo que queda fuera, y lo barato que sí entra.**
  - Fuera: mover o saltar ocurrencias de recurrentes **con** copia a Google (exige excepciones por instancia en Google; no es barato), editar series desde una propuesta, y planificar más allá de hoy + 6.
  - Entra porque cuesta casi nada: `Quitar solo este día` en Semana para recurrentes **sin** copia (overrides de la 3A), y `Que Claude arme este día` dentro del rango ya soportado.

## Global Constraints

- Todo lo de las fases anteriores sigue vigente. En particular:
  - Los eventos de Google y Outlook son de solo lectura.
  - El día lógico cambia a las 04:00 (también para la semana, el sueño y el widget).
  - Hábitos y avisos solo en vigilia.
  - La app funciona sin internet para todo lo que no sea Claude: Semana, widget y "+1" del widget incluidos.
- **Salida estructurada.** NUNCA usar el schema que el SDK deriva de la clase (`outputConfig(Class)` / `StructuredOutputConfig.format(Class)`): revienta en Android (ART no tiene `Method.getAnnotatedReturnType`).
  - Usar schemas escritos a mano en `claude/Schemas.kt` y enviarlos con `Schemas.format(...)` (endpoint no beta).
  - Leer la respuesta con `StructuredJson.parse` (Jackson).
  - Cada clase de salida nueva lleva su prueba de deriva en `SchemasTest` contra el schema derivado por el SDK, solo en la JVM.
  - Una propiedad Boolean de Kotlin con prefijo `is` necesita `@get:JsonProperty` y `@field:JsonProperty`. Las clases de esta fase no usan ese prefijo.
- **Llamada de sugerencias.**
  - Modelo `ClaudeModels.SUGGESTIONS` (`claude-haiku-5-5`), `OutputConfig.Effort.MEDIUM`, `maxTokens` 4000 y `RequestOptions` de 60 s.
  - Sin `fallbacks`, `thinking`, `temperature` ni prefill.
  - Revisar `stopReason` antes de leer el contenido, como en `ClassificationCall.read`.
- **Datos que se envían a Claude para sugerir.** Solo esto, y nada más:
  - fecha de hoy y cuántos de los últimos 14 días (sin hoy) tienen registros;
  - rutina: horas de levantarse y acostarse (laboral y descanso) y bloques de trabajo;
  - por cada hábito activo: `H{id}`, nombre (pasado por `PromptBuilder.sanitize`), resumen del horario (`Habit.summary()`), ventana, meta diaria, hechos por día (14 números), y hechos, saltados y avisos por hora del día (solo horas con valor);
  - los cambios rechazados en los últimos 30 días, como `H{id} {tipo}`.

  Nunca títulos de actividades o eventos, dictados ni notas.
- **Sin llamadas sin motivo.** Sugerencias: como mucho una automática cada 7 días y un reintento por día (D12), solo con clave y red. Ningún otro código nuevo llama a Claude. Semana, widget y sueño nunca llaman a Claude.
- **Widget.**
  - `RemoteViews` sin bibliotecas nuevas.
  - "+1" registra directo en Room, sin red.
  - Sin sondeo: solo el refresco por cambio de datos y la alarma `RTC` inexacta de D9; nunca `RTC_WAKEUP`, alarmas exactas ni `updatePeriodMillis > 0`.
  - Sin widgets puestos no se calcula nada.
- **Enlaces e intents.** No hay rutas nuevas en `DeepLinks`. El widget y la ventanita solo usan `DeepLinks.intent` con rutas ya en la lista blanca (`today`, `voice`, `proposal?day=…&request=…`, este último con el secreto). `QuickVoiceActivity`, `WidgetActionReceiver` y la alarma del widget no se exportan. El único receptor exportado nuevo es el `AppWidgetProvider` (lo exige el sistema), y solo atiende `APPWIDGET_UPDATE` / `APPWIDGET_ENABLED` / `APPWIDGET_DISABLED` / `APPWIDGET_DELETED` y su `ACTION_REFRESH`, que solo vuelve a pintar.
- **Concurrencia.**
  - Toda resincronización de avisos después de escribir corre en `withContext(NonCancellable)`.
  - Lo que sobrevive a una pantalla (sugerencias automáticas, registrar el despertar) corre en `container.syncScope`.
  - Ningún camino nuevo escribe copias de Google. Editar o borrar desde Semana pasa por el formulario existente (diario y `GoogleCopySync`); `Quitar solo este día` solo existe sin copia; el dictado de la ventanita usa `ActionExecutor` tal cual.
  - Aceptar y deshacer una sugerencia van con `Mutex` (un solo vuelo).
- **Copia de pantalla (español, tuteo, sin emojis).** Literal donde la spec o los bocetos la fijan:
  - Semana: `Tu semana`, `16 – 22 de noviembre`, `Semana anterior` / `Semana siguiente` (accesibles), `Horas de trabajo`, `Meta: 6–7 h por día`, `META`, `Promedio`, `En tu meta`, `Ejercicio`, `3 / 5`, `sesiones`, `Sueño`, `promedio`, `Brújula`, `Google`, `Outlook`, `Hoy, miércoles 18`.
  - Widget: `Ahora · hasta 10:30`, `Siguiente`, `Ejercicio 10:30`, `Agua`, `Bálsamo`, `3 / 8`, y los textos accesibles `Sumar un vaso de agua`, `Marcar bálsamo labial` y `Dictar con la voz`.
  - Ventanita: `Escuchando… di lo que vas a hacer`, `Claude está entendiendo…`, `Agendado`, `Deshacer`.
  - Hábitos: el texto de Claude, `Sí, porfa`, `Así está bien`.
- **Build en una máquina con poca memoria.** Siempre `--max-workers=2`, con el prefijo `$env:JAVA_HOME="C:\Users\juesn\tools\jdk-21"; $env:Path="$env:JAVA_HOME\bin;$env:Path";`. Ninguna API por encima de minSdk 31. El arnés no deja que los subagentes escriban archivos `.md` de reporte: los implementadores devuelven su reporte como texto.
- **Commits** con el trailer de dos líneas (`Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` / `Claude-Session: https://claude.ai/code/session_01XN8G1c95yQpddiVFPfCNKU`).

En adelante, `GRADLE` = `$env:JAVA_HOME="C:\Users\juesn\tools\jdk-21"; $env:Path="$env:JAVA_HOME\bin;$env:Path"; .\gradlew.bat --max-workers=2`.

## Review Focus

1. **Medianoche y día lógico.** `Ya me acuesto` a las 00:40 cuenta para la noche del día lógico anterior; abrir la app a las 02:00 no es "despertar"; el domingo a la 01:30 sigue en la misma semana; el widget a las 03:50 sigue mostrando el día lógico anterior y se refresca a las 04:00. → Task 1 (`bedtimeAfterMidnightBelongsToLogicalDay`, `wakeNightForMorningOpen`), Task 2 (`logicalSundayAt0130StillSameWeek`), Task 6 (`nextRefreshIsEarliestBoundary`).
2. **Widget que cambia sin abrir la app.** "+1" desde una notificación, un dictado, Tema Oscuro en Tu rutina o un bloque que termina: el widget lo muestra en ~1 s (datos) o en la frontera (hora). Sin widgets puestos no corre nada, y "+1" sin red funciona. → Task 6 (`changesSkippedWithoutWidgets`, `habitLogChangeEmits`, `addHabitLogsOffline`).
3. **Ventanita sin permiso, sin red, sin clave, o cerrada a mitad.** El dictado nunca se pierde (va a la cola) y la ventana nunca queda abierta esperando. → Task 7 (`noKeyQueuesWhenQueueWithoutKey`, `autoCloseDelays`) y la lógica de cancelación existente de `VoiceViewModel`.
4. **Sugerencia vieja o mala.** El hábito se editó o borró después de sugerir, Claude devuelve un cambio inválido o repite uno rechazado: no se aplica nada, y deshacer no pisa una edición posterior. → Task 4 (`HabitSuggesterTest`) y Task 5 (`acceptRefusesWhenHabitChanged`, `undoKeepsLaterEdit`, `dismissedNotSuggestedAgainFor30Days`).
5. **Semana con calendarios que fallan o días raros.** Sin permiso, el proveedor lanza, eventos de todo el día o que cruzan la medianoche, un festivo un lunes, o editar desde el detalle y volver: se ve lo propio, los números cuadran con Hoy y se actualizan solos. → Task 2 (`buildDaySnapshotMatchesProvider`, `holidayMondayIsRest`) y Task 3 (`calendarFailureStillShowsOwn`, `editInRepositoryRefreshesWeek`).

---

### Task 1: DB v6 y sueño (estimado por la rutina, ajustado al acostarse y al despertar)

**Files:**
- Create:
  - `sleep/Sleep.kt`: `SleepRecord`, `sleepSpan`, `sleepMinutes`, `wakeNightFor`, `bedtimeNightFor`;
  - `sleep/SleepRepository.kt`.
- Modify:
  - `data/db/Entities.kt`: `SleepRecordEntity` y mapeos;
  - `data/db/Daos.kt`: `SleepDao`;
  - `data/db/BrujulaDatabase.kt`: versión 6, `AutoMigration(from = 5, to = 6)`, `sleepDao()`, y exporta `app/schemas/app.brujula.data.db.BrujulaDatabase/6.json`;
  - `BrujulaApp.kt`: `sleepRepository` y `recordWakeIfMorning()`;
  - `reminders/Notifications.kt`: acción `Ya me acuesto` en `BEDTIME`;
  - `reminders/NotificationActionReceiver.kt`: `ACTION_SLEEP_NOW`;
  - `ui/MainActivity.kt`: `onStart`.
- Test: `data/MigrationTest.kt`, `sleep/SleepTest.kt`, `data/SleepRepositoryTest.kt`, `reminders/ReceiversTest.kt`.

**Interfaces:**
- Produces:
  - `data class SleepRecord(val night: LocalDate, val bedAt: LocalDateTime?, val wakeAt: LocalDateTime?)`. `night` es el día lógico de esa noche (la noche del jueves = jueves, aunque se acueste el viernes a la 00:30).
  - `@Entity(tableName = "sleep_records") data class SleepRecordEntity(@PrimaryKey val night: LocalDate, val bedAt: LocalDateTime?, val wakeAt: LocalDateTime?)`.
  - `SleepDao`:
    - `observeRange(from: LocalDate, to: LocalDate): Flow<List<SleepRecordEntity>>` (ambos inclusive);
    - `get(night)`;
    - `@Insert(onConflict = IGNORE) insertIgnore(e)`;
    - `UPDATE … SET bedAt = :at WHERE night = :night`;
    - `UPDATE … SET wakeAt = :at WHERE night = :night AND wakeAt IS NULL` (devuelve filas cambiadas);
    - `@Transaction setBedAt(night, at)` y `@Transaction setWakeIfAbsent(night, at): Boolean` (insertIgnore + update).
  - `class SleepRepository(dao: SleepDao)`:
    - `fun observeRange(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, SleepRecord>>`;
    - `suspend fun confirmBedtime(night: LocalDate, at: LocalDateTime)`;
    - `suspend fun recordWake(night: LocalDate, at: LocalDateTime): Boolean` (false si esa noche ya tenía despertar).
  - `fun sleepSpan(night: LocalDate, routine: Routine, record: SleepRecord?): TimeSpan` y `fun sleepMinutes(night, routine, record): Int = sleepSpan(...).minutes.toInt()` (D2):
    - planeado: `bed0 = routine.awakeWindow(night).end`, `wake0 = night.plusDays(1).atTime(routine.wakeFor(night.plusDays(1)))`;
    - `bed = record.bedAt` si está en `[bed0 − 6 h, wake0)`, si no `bed0`;
    - `wake = record.wakeAt` si está en `(bed, bed + 16 h]`, si no `wake0`;
    - si `wake <= bed` → `TimeSpan(bed0, wake0)`.
  - `const val WAKE_GRACE_MIN = 120`. `fun wakeNightFor(at: LocalDateTime, routine: Routine): LocalDate?`: con `d = logicalDay(at)`, devuelve `d − 1` si `at` está en `[d 04:00, d.atTime(wakeFor(d)) + 120 min]`; si no, `null`.
  - `fun bedtimeNightFor(at: LocalDateTime, reminderDay: LocalDate): LocalDate? = logicalDay(at).takeIf { it == reminderDay }`.
  - `AppContainer.sleepRepository` y `suspend fun AppContainer.recordWakeIfMorning()`: rutina actual + `clock.now()` → `wakeNightFor` → `recordWake`.
  - `NotificationActionReceiver.ACTION_SLEEP_NOW = "app.brujula.action.SLEEP_NOW"` (agregado a `ACTIONS`). Con un `reminder` de tipo `BEDTIME` → `bedtimeNightFor(now, reminder.day)?.let { sleepRepository.confirmBedtime(it, now) }`; fuera del día lógico se ignora.
  - `Notifications.showPlanning`: el `BEDTIME` gana la acción `Ya me acuesto` (`ACTION_SLEEP_NOW`). El toque sigue abriendo la app.
  - `MainActivity.onStart`: `container.syncScope.launch { runCatching { container.recordWakeIfMorning() } }`.

- [ ] **Step 1: Pruebas que fallan**
  - `MigrationTest.v5ToV6AddsSleepRecords`: las filas v5 de `activities` y `proposals` siguen iguales y se puede insertar en `sleep_records`.
  - `SleepTest`:
    - `plannedSleepFromRoutine`: noche del jueves 19-nov-2026 con la rutina por defecto → 00:30 a 08:15 del viernes, 465 min;
    - `fridayNightUsesRestWake`: noche del viernes → 00:30 a 10:00 del sábado, 570 min;
    - `confirmedBedtimeShortensSleep`: `bedAt` el viernes a la 01:30 → 405;
    - `recordedWakeUsed`: `wakeAt` a las 07:30 → 420;
    - `implausibleValuesFallBack`: `wakeAt` antes de `bed` → planeado; `bedAt` dos días antes → planeado;
    - `wakeNightForMorningOpen`: viernes 08:40 → jueves; viernes 10:30 → `null` (08:15 + 2 h = 10:15); viernes 02:00 → `null`; sábado 11:00 → viernes (10:00 + 2 h);
    - `bedtimeAfterMidnightBelongsToLogicalDay`: `bedtimeNightFor(vie 00:40, jue) == jue` y `bedtimeNightFor(vie 05:00, jue) == null`.
  - `SleepRepositoryTest`:
    - `confirmBedtimeUpserts`;
    - `recordWakeOnlyFirstTime`: la segunda llamada devuelve false y el valor no cambia;
    - `observeRangeMaps`.
  - `ReceiversTest`:
    - `sleepNowActionRecordsBedtime`: aviso `BEDTIME` del jueves, reloj viernes 00:20 → `bedAt` de la noche del jueves = 00:20;
    - `bedtimeNotificationOffersSleepAction`: la notificación publicada tiene una acción `Ya me acuesto`.
- [ ] **Step 2:** `GRADLE :app:testDebugUnitTest --tests "app.brujula.data.*" --tests "app.brujula.sleep.*" --tests "app.brujula.reminders.*"` → FAIL (no compila).
- [ ] **Step 3: Implementar.**
- [ ] **Step 4:** `GRADLE :app:testDebugUnitTest :app:assembleDebug` → PASS, con `6.json` generado y agregado.
- [ ] **Step 5:** Commit `feat: sleep records (DB v6) adjusted by bedtime confirmation and morning open`.

### Task 2: Semana — datos con las reglas de Hoy

**Files:**
- Create:
  - `week/WeekModel.kt`: modelos y `buildWeek`;
  - `week/WeekFormat.kt`: textos.
- Modify:
  - `planning/DaySnapshot.kt`: extraer `SnapshotInputs` y `buildDaySnapshot`; `DaySnapshotProvider.snapshot` pasa a usarlos sin cambiar su resultado;
  - `today/DayItems.kt`: `selectedEventsBetweenOrEmpty`.
- Test: `week/WeekModelTest.kt`, `planning/DaySnapshotProviderTest.kt`.

**Interfaces:**
- Consumes: `SleepRecord`, `sleepMinutes` (Task 1).
- Produces:
  - `data class SnapshotInputs(val routine: Routine, val activities: List<Activity>, val done: Set<Pair<Long, LocalDate>>, val overrides: Overrides, val external: List<ExternalEvent> /* ya sin copias de Brújula */, val isWork: (ExternalEvent) -> Boolean)`.
  - `fun buildDaySnapshot(day: LocalDate, inputs: SnapshotInputs, water: Habit? = null, waterCount: Int? = null): DaySnapshot`, con exactamente las reglas actuales de `DaySnapshotProvider` (spill + lógicas con overrides, `eventsOnLogicalDay`, `workMinutes` con `logicalSpan`, ejercicio).
  - `suspend fun CalendarSource.selectedEventsBetweenOrEmpty(selectedIds: Flow<Set<Long>?>, from: LocalDateTime, to: LocalDateTime): List<ExternalEvent>`, con las mismas reglas de error y permiso que `selectedEventsAroundOrEmpty`.
  - `fun mondayOf(day: LocalDate): LocalDate`.
  - `enum class WeekDayState { PAST, TODAY, PLANNED }`.
  - `enum class EventOrigin { BRUJULA, GOOGLE, OUTLOOK, OTHER }` y `fun originOf(source: String): EventOrigin` (`"Google"` / `"Outlook"` / lo demás).
  - `sealed interface WeekEvent`, con `title`, `timeLabel` (`"8:00"` o `"Todo el día"`), `origin` y `sourceLabel`:
    - `data class Own(val occurrence: Occurrence, val canSkip: Boolean)`, donde `canSkip = repeat != NONE && !copyToGoogle && day >= hoy`;
    - `data class External(val event: ExternalEvent, val countsAsWork: Boolean)`.
  - `data class WeekDay(val day: LocalDate, val letter: String /* L M X J V S D */, val state: WeekDayState, val rest: Boolean, val workMinutes: Int, val barLabel: String, val events: List<WeekEvent>, val exerciseDone: Int, val exerciseTotal: Int, val a11y: String)`. Los eventos del detalle son las ocurrencias lógicas (sin spill) + los externos del día lógico, con los de todo el día primero.
  - `data class WeekTotals(val workAverage: Int?, val workLabel: String, val exerciseDone: Int, val exerciseTotal: Int, val sleepAverage: Int?)`.
  - `data class WeekData(val monday: LocalDate, val title: String, val rangeLabel: String, val goal: IntRange, val goalLabel: String, val days: List<WeekDay>, val totals: WeekTotals)`.
  - `fun buildWeek(monday: LocalDate, now: LocalDateTime, inputs: SnapshotInputs, sleep: Map<LocalDate, SleepRecord>): WeekData` (D3, D5).
  - `WeekFormat.kt`:
    - `weekRangeLabel(monday)`: `16 – 22 de noviembre`, `30 de noviembre – 6 de diciembre`, `28 de diciembre de 2026 – 3 de enero de 2027`;
    - `weekTitle(monday, currentMonday)`: `Tu semana` / `Semana pasada` / `Próxima semana` / `Semana`;
    - `barLabel(minutes)`: una cifra decimal con punto como el boceto, sin `.0`, y `–` en 0 (390 → `6.5h`, 432 → `7.2h`, 360 → `6h`);
    - `goalLabel(range)`: `Meta: 6–7 h por día`;
    - `dayDetailTitle(day, today)`: `Hoy, miércoles 18` / `Jueves 19`;
    - `workStatusLabel(WorkStatus)`: los cuatro de D5.
  - Texto accesible de cada barra: `Jueves 19: 6 h 30 de trabajo planeado` (pasado sin `planeado`; en 0, `sin trabajo`).

- [ ] **Step 1: Pruebas que fallan.** Semana del lunes 16-nov-2026 (festivo: Independencia de Cartagena trasladado), reloj miércoles 18 a las 10:24.
  - `statesPastTodayPlanned`: 16 y 17 `PAST`, 18 `TODAY`, 19–22 `PLANNED`.
  - `holidayMondayIsRest`: 16, 21 y 22 con `rest`, y el 16 queda fuera del promedio.
  - `workCountsOwnWorkAndExternalWorkWithOverrides`: martes con `WORK` 8:00–12:00 + externo de trabajo 15:00–16:00 = 300. Un override que lo mueve deja 300; uno `skipped` deja 60.
  - `averageOnlyWorkdaysUpToToday`: martes 420 + miércoles 150 → 285, `Muy por debajo`.
  - `futureWeekAveragesPlanned`: semana del 23 → subtítulo `Planeado`.
  - `exerciseDoneOverScheduled`: ejercicio `WORKDAYS` 18:30 hecho el martes → `1 / 4` (el lunes es festivo).
  - `sleepAverageNightsWithMorningUpToToday`:
    - noches del 15, 16 y 17 → (540 + 435 + 465) / 3 = 480;
    - con `bedAt` el 18 a la 01:30 en la noche del 17 → 460;
    - con el reloj el miércoles a las 07:00 → solo dos noches.
  - `eventsColoredByOrigin`: propio → `BRUJULA`, `Google`, `Outlook`, `Exchange` → `OTHER`; el de todo el día va primero con `Todo el día`.
  - `canSkipOnlyRecurringWithoutCopyFromToday`.
  - `labelsMatchMockup`: todos los textos de `WeekFormat.kt` de arriba.
  - `logicalSundayAt0130StillSameWeek`: `mondayOf(logicalDay(lun 23 a la 01:30)) == lun 16`.
  - `DaySnapshotProviderTest.buildDaySnapshotMatchesProvider`: para el fixture existente, el proveedor y `buildDaySnapshot` dan el mismo `DaySnapshot`.
- [ ] **Step 2:** `GRADLE :app:testDebugUnitTest --tests "app.brujula.week.*" --tests "app.brujula.planning.*"` → FAIL.
- [ ] **Step 3: Implementar.** `buildWeek` llama `buildDaySnapshot` una vez por día con los mismos `inputs`; el agua no se usa en Semana.
- [ ] **Step 4:** Suite PASS (incluidas todas las pruebas de `planning.*` y `today.*` que ya existían).
- [ ] **Step 5:** Commit `feat: week model built from the same day snapshot rules as Today`.

### Task 3: Semana — pantalla, detalle del día y pestaña

**Files:**
- Create: `week/WeekViewModel.kt`, `week/WeekScreen.kt` (según `Semana.dc.html` / `SemanaOscuro.dc.html`, con la hoja de recurrentes).
- Modify:
  - `ui/BrujulaNavHost.kt`: `Routes.WEEK = "week"` en `TopLevel`, y el destino con `WeekViewModel`;
  - `ui/BottomBar.kt`: `onWeek: () -> Unit` y la pestaña `Semana` activa (sin `Pronto`).
- Test: `week/WeekViewModelTest.kt`.

**Interfaces:**
- Consumes: `buildWeek`, `WeekData`, `WeekEvent`, `mondayOf`, `selectedEventsBetweenOrEmpty` (Task 2); `SleepRepository.observeRange` (Task 1).
- Produces:
  - `WeekViewModel(clock: Clock, routineRepository: RoutineRepository, activityRepository: ActivityRepository, calendarSource: CalendarSource, classifications: WorkClassificationRepository, sleepRepository: SleepRepository, resync: ReminderResync, scopeOverride: CoroutineScope? = null, tickMs: Long = 30_000L)`.
    - Combina rutina, actividades, hechos, overrides, ids de Google enlazados, clasificaciones, sueño de la semana, el tictac (día lógico) y la semana elegida.
    - Los calendarios se leen en IO para `[lunes − 1 día 04:00, lunes + 8 días 04:00)`, como `externalRaw` de Hoy, con `refreshExternal()` al volver a la pantalla.
  - `data class WeekUiState(val data: WeekData?, val selected: LocalDate, val canGoBack: Boolean, val canGoForward: Boolean, val message: WeekMessage?)`.
  - `data class WeekMessage(val text: String, val undo: SkipUndo?)` y `data class SkipUndo(val activityId: Long, val day: LocalDate, val before: OccurrenceOverride?)`.
  - `const val MIN_WEEK_OFFSET = -26` y `const val MAX_WEEK_OFFSET = 4`.
  - Funciones: `previousWeek()`, `nextWeek()`, `select(day: LocalDate)`, `skipOccurrence(e: WeekEvent.Own)`, `undoSkip()`, `dismissMessage()`, `setCountsAsWork(event: ExternalEvent, isWork: Boolean)`, `refreshExternal()`, `canAskClaude(day: LocalDate): Boolean` (hoy..hoy + 6) y `addStartFor(day: LocalDate): LocalDateTime?`.
    - `addStartFor`: `null` si `day` es hoy (el formulario pone la hora actual); si no, el inicio del primer `freeSlots(awakeWindow(day), ocupados)` de 30 min o más, o la hora de levantarse.
  - En la semana actual, al cambiar el día lógico la vista pasa sola a la nueva semana actual.
  - `skipOccurrence`:
    - solo si `canSkip`;
    - guarda `before = getOverride(id, occ.day)` y escribe `OccurrenceOverride(id, occ.day, null, null, skipped = true)`;
    - resincroniza en `NonCancellable`;
    - mensaje `Quité «{título}» del {jueves 19}.` con `undo`.

    `undoSkip` restaura `before` (o borra el override si no había) y resincroniza.
  - `WeekScreen(viewModel, onEditActivity: (Long) -> Unit, onAddAt: (LocalDateTime?) -> Unit, onAskClaude: (LocalDate) -> Unit)`. En el NavHost:
    - `onEditActivity` → `Routes.activityForm(id)`;
    - `onAddAt` → `Routes.activityForm(start = it)`;
    - `onAskClaude` → `Routes.proposal(day)`.
  - Pantalla según el boceto:
    - encabezado con el rango, el título y las flechas;
    - tarjeta `Horas de trabajo` con la banda `META` de la meta y siete barras. Colores: pasado = acento 2; hoy = acento; planeado = suave 2 con borde punteado de acento 2; descanso en 0 = superficie 2;
    - tres tarjetas de totales (`Promedio`, `Ejercicio` `{x} / {y}` `sesiones`, `Sueño` `promedio`);
    - el detalle del día elegido con la leyenda `Brújula` / `Google` / `Outlook`;
    - las barras crecen con resorte y respetan "reducir movimiento" (`ui/Motion.kt`).
  - Detalle vacío: `Nada agendado este día.`. Botones al pie del detalle: `Agregar a este día` y, si `canAskClaude`, `Que Claude arme este día`.
  - Hoja de una recurrente con `canSkip`: título, `Se repite: {repeat.labelEs}`, `Editar la serie` y `Quitar solo este día`. Una recurrente sin `canSkip` abre el formulario directo.
  - Un externo abre `ExternalEventSheet` con `setCountsAsWork`. El mensaje sale en un snackbar con `Deshacer` cuando hay `undo`.

- [ ] **Step 1: Pruebas que fallan** (`WeekViewModelTest`, con Room en memoria y `FakeCalendarSource`, reloj miércoles 18-nov-2026 a las 10:24):
  - `startsOnCurrentWeekWithTodaySelected`;
  - `navigationLimits`: en −26, `canGoBack == false`; en +4, `canGoForward == false`;
  - `selectingOtherWeekSelectsMonday`;
  - `editInRepositoryRefreshesWeek`: un `upsert` de un `WORK` el jueves cambia `workMinutes` del jueves sin recrear el VM;
  - `skipRecurringOnlyThatDayAndUndo`: la serie sigue el viernes, hay un resync, y `undoSkip` restaura el override previo (un movido);
  - `skipRefusedForGoogleCopy`: no se escribe ningún override;
  - `calendarFailureStillShowsOwn`: el calendario lanza y lo propio sigue;
  - `askClaudeOnlyTodayToSixDays`: el 18 y el 24 sí; el 17 y el 25 no;
  - `addStartForFutureDayIsFirstFreeSlot`: jueves con `WORK` 8:00–12:00 y levantarse 08:15 → 12:00; viernes vacío → 08:15; hoy → `null`.
- [ ] **Step 2:** `GRADLE :app:testDebugUnitTest --tests "app.brujula.week.*"` → FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4:** `GRADLE :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` → PASS.
- [ ] **Step 5:** Commit `feat: week screen with day detail, edit from any day and skip one occurrence`.

### Task 4: Sugerencias — tramo "más seguido desde", datos agregados y llamada a Claude Haiku

**Files:**
- Create:
  - `claude/SuggestionActions.kt`: clases de salida estructurada;
  - `suggestions/HabitSuggestion.kt`: `SuggestionKind`, `HabitChange`, `HabitSuggestion`, `doneText()`;
  - `suggestions/HabitStats.kt`: `HabitLog`, `HabitStat`, `SuggestionContext`, `habitStats`, `SuggestionContextProvider`;
  - `suggestions/SuggestionPrompt.kt`;
  - `suggestions/HabitSuggester.kt`: llamada + validación.
- Modify:
  - `habits/Habit.kt`: `Every(val intervalMin: Int, val faster: FasterStretch? = null)` y `data class FasterStretch(val from: LocalTime, val intervalMin: Int)`;
  - `data/db/Converters.kt`: `every:90|15:00/60`; sin `|` se lee como hoy;
  - `habits/HabitTimes.kt`: tramo en la ventana `AWAKE` (en `WORK` se ignora);
  - `habits/HabitsViewModel.kt`: `Habit.summary()`;
  - `habits/HabitsScreen.kt`: texto de la tarjeta;
  - `habits/HabitFormViewModel.kt` y `habits/HabitFormScreen.kt`: conservar el tramo y `Quitar`;
  - `data/db/Daos.kt`: `HabitDao.logsBetween`;
  - `habits/HabitRepository.kt`: `logsBetween`;
  - `claude/ClaudeModels.kt`: `SUGGESTIONS`;
  - `claude/Schemas.kt`: `SUGGESTION`;
  - `claude/ClaudeGateway.kt`: `suggest`;
  - `claude/AnthropicClaudeGateway.kt`: `SuggestionCall` y `suggest`;
  - `claude/EventClassifier.kt`: `DelegatingGateway.suggest`.
- Test:
  - `habits/HabitTimesTest.kt`, `data/HabitRepositoryTest.kt`, `habits/HabitFormViewModelTest.kt`;
  - `claude/SchemasTest.kt`, `claude/SuggestionCallTest.kt`;
  - `suggestions/HabitStatsTest.kt`, `suggestions/SuggestionPromptTest.kt`, `suggestions/HabitSuggesterTest.kt`;
  - `claude/FakeClaude.kt`: `var suggestion`, `suggestCalls`, `lastSuggestUser`.

**Interfaces:**
- Produces:
  - **Tramo en `timesFor`** (ventana `AWAKE`). `fromAt` es `from` llevado al día igual que en `AtTimes` (antes de levantarse → levantarse; pasada la medianoche si la vigilia la cruza):
    ```
    base   = inicio + intervalo, + intervalo, …  mientras < min(fin, fromAt)
    rápido = fromAt, fromAt + f, fromAt + 2f, … mientras < fin
    si (fromAt − última base) < f / 2 → se quita esa última base
    ```
    `Habit.summary()` agrega ` · desde las 15:00 cada 60 min`, y la tarjeta de Hábitos dice `Cada 90 min mientras estás despierto; desde las 15:00, cada 60 min.`.
  - `HabitFormState.faster: FasterStretch?`. Al editar se conserva tal cual; `removeFaster()` lo quita; pasar a otro modo lo quita. En pantalla aparece la fila `Más seguido desde las 15:00: cada 60 min` con `Quitar`.
  - `data class HabitLog(val habitId: Long, val at: LocalDateTime, val logicalDay: LocalDate, val skipped: Boolean)`. `HabitRepository.logsBetween(from: LocalDate, to: LocalDate): List<HabitLog>`, con DAO `SELECT * FROM habit_logs WHERE logicalDay BETWEEN :from AND :to`.
  - `data class HabitStat(val ref: String /* "H{id}" */, val habit: Habit, val daily: List<Int> /* 14, del más viejo al más nuevo */, val doneByHour: Map<Int, Int>, val skippedByHour: Map<Int, Int>, val remindersByHour: Map<Int, Int>)`. Los avisos por hora salen de `timesFor` sobre los mismos 14 días.
  - `fun habitStats(today: LocalDate, habits: List<Habit>, logs: List<HabitLog>, routine: Routine, days: Int = 14): List<HabitStat>`. Solo hábitos activos, días `today − 14 .. today − 1`.
  - `enum class SuggestionKind { INTERVAL, TIMES, TARGET }`. `data class HabitChange(val schedule: HabitSchedule, val dailyTarget: Int)`.
  - `data class HabitSuggestion(val habitId: Long, val habitName: String, val kind: SuggestionKind, val message: String, val before: HabitChange, val after: HabitChange, val createdAt: LocalDateTime)`. `fun HabitSuggestion.doneText(): String`:
    - `Listo: desde las 15:00 te aviso cada 60 min.`;
    - `Listo: te aviso cada 60 min.`;
    - `Listo: te aviso a las 9:00, 13:00 y 17:00.`;
    - `Listo: tu meta ahora es 10 al día.`.
  - `data class SuggestionContext(val now: LocalDateTime, val today: LocalDate, val daysWithLogs: Int, val routine: Routine, val stats: List<HabitStat>, val rejected: Set<Pair<Long, SuggestionKind>>)`.
  - `class SuggestionContextProvider(clock, habitRepository, routineRepository, rejected: suspend () -> Set<Pair<Long, SuggestionKind>> = { emptySet() }) { suspend fun current(): SuggestionContext }`. Task 5 conecta `rejected`.
  - Salida:
    - `data class SuggestionResult(val suggestions: List<SuggestionItem> = emptyList())`;
    - `data class SuggestionItem(val habitRef: String = "", val message: String = "", val change: String = "" /* interval | times | target */, val intervalMin: Int? = null, val fromTime: String? = null /* HH:mm */, val times: List<String>? = null, val dailyTarget: Int? = null)`.

    Las `@JsonPropertyDescription` son idénticas a las de `Schemas.SUGGESTION`: todas obligatorias, nulables con `["tipo","null"]`, `additionalProperties: false`, igual que `CLASSIFICATION`.
  - `ClaudeModels.SUGGESTIONS = "claude-haiku-5-5"`. `ClaudeGateway.suggest(system: String, user: String): GatewayResult<SuggestionResult>` en todas las implementaciones (`AnthropicClaudeGateway`, `DelegatingGateway`, `FakeClaudeGateway`).
  - `internal object SuggestionCall { fun params(system, user): MessageCreateParams; val TIMEOUT: Duration /* 60 s */; fun requestOptions(); fun read(response: Message): GatewayResult<SuggestionResult> }`. Los fallos se clasifican con `DictationCall.failureOf(e, "Sugerencia")`.
  - `object SuggestionPrompt { fun system(): String; fun user(ctx: SuggestionContext): String }`.
    - `system()` contiene:
      - rol: asistente de hábitos de Brújula, que solo ve conteos agregados;
      - como mucho **una** sugerencia, o lista vacía si no hay un patrón claro;
      - solo los tres cambios de D13 (`interval` solo para "cada N min", con `fromTime` opcional; `times` solo para horas fijas; `target`);
      - nunca crear, borrar ni desactivar;
      - horas dentro de la vigilia;
      - no repetir lo rechazado;
      - `message` en 1–2 frases, español colombiano, tuteo, sin emojis, que termina en pregunta, con el ejemplo `Por las tardes casi no tomas agua. ¿Te aviso más seguido después de las 3 pm?`.
    - `user()`:
      - `Hoy: {jueves 19 de noviembre} ({iso}). De los últimos 14 días (sin hoy), {n} tienen registros.`;
      - la rutina;
      - un bloque por hábito: `H3 · Agua · Cada 90 min · meta 8 al día`, `Hechos por día: …`, `Hechos por hora: 9 h: 2, 15 h: 0, …`, `Avisos por hora: …`, `Saltados por hora: …`;
      - `Ya rechazaste (no repitas): H3 interval`.
  - `sealed interface SuggestOutcome { data class Ok(val suggestion: HabitSuggestion?); data object NotEnoughData; data object NoKey; data object InvalidKey; data object Offline; data class Refused(val message: String); data class Failed(val message: String, val retryable: Boolean) }`.
  - `class HabitSuggester(gateway: ClaudeGateway, contextProvider: suspend () -> SuggestionContext, isOnline: suspend () -> Boolean = { true }) { suspend fun suggest(minDaysWithLogs: Int): SuggestOutcome }`. Constantes `MIN_DAYS_AUTO = 7` y `MIN_DAYS_ON_DEMAND = 3`. Con menos días → `NotEnoughData`, sin llamar.
  - `fun validate(item: SuggestionItem, ctx: SuggestionContext): HabitSuggestion?`. `Ok` lleva la primera válida de la lista, o `null`.
    - `habitRef` debe existir en `ctx.stats`.
    - `interval`: el horario es `Every`; 15 ≤ `intervalMin` ≤ 240. Con `fromTime`: hábito `AWAKE`, hora válida dentro de `awakeWindow` de un día laboral; `after = Every(base, FasterStretch(from, intervalMin))`. Sin `fromTime`: `Every(intervalMin, faster actual)`.
    - `times`: el horario es `AtTimes`; de 1 a 12 horas válidas y distintas, ordenadas.
    - `target`: de 1 a 20.
    - `after != before`; `(habitId, kind)` no está en `ctx.rejected`; `message` no vacío, recortado a 300.
  - Copias: `NOT_ENOUGH = "Necesito al menos 3 días de registros para sugerirte algo."`, `OFFLINE = "Sin conexión. Claude necesita internet para sugerirte cambios."`, `NO_KEY = "Para pedir sugerencias, agrega tu clave de Claude en Tu rutina."`, `NONE = "Por ahora no tengo sugerencias. Vas bien."`. Clave inválida y fallo usan `DictationInterpreter.INVALID_KEY` y `UNAVAILABLE`.

- [ ] **Step 1: Pruebas que fallan**
  - `HabitTimesTest`:
    - `fasterStretchFromThreePm`: levantarse 08:15, acostarse 00:30, cada 90 con tramo 15:00/60 → 9:45, 11:15, 12:45, 14:15, 15:00, 16:00 … 00:00 (14 avisos);
    - `fasterStretchDropsCloseBase`: con tramo desde 14:30 se quita el de 14:15;
    - `fasterIgnoredInWorkWindow`.
  - `HabitRepositoryTest`:
    - `scheduleWithFasterRoundTrip`;
    - `oldEveryTextStillReads` (`every:90`);
    - `logsBetweenFiltersByLogicalDay`.
  - `HabitFormViewModelTest`:
    - `editKeepsFasterStretch`;
    - `removeFasterSavesPlainEvery`.
  - `SchemasTest.suggestionSchemaMatchesTheSdkDerivedOne` (no beta, como `classificationSchemaMatchesTheSdkDerivedOne`).
  - `SuggestionCallTest`: `model == "claude-haiku-5-5"`; esfuerzo `MEDIUM`; `maxTokens == 4000`; sin `fallbacks`; `TIMEOUT == 60 s`. Respuesta enlatada: refusal → `Refused`; `max_tokens` → `Error(retryable = true)`; JSON con una sugerencia → `Ok`.
  - `HabitStatsTest`:
    - `dailyAndHourlyCounts` (14 números, sin hoy);
    - `skippedSeparated`;
    - `remindersByHourFromSchedule`;
    - `inactiveHabitsLeftOut`.
  - `SuggestionPromptTest`:
    - `userListsHabitsWithRefsAndCounts`;
    - `noActivityOrEventTitlesSent` (el texto no contiene el título de una actividad del fixture);
    - `rejectedListed`;
    - `habitNameSanitized`.
  - `HabitSuggesterTest`:
    - `notEnoughDataSkipsCall`;
    - `offlineSkipsCall`;
    - `validIntervalWithFromTime`;
    - `intervalOnAtTimesRejected`;
    - `unknownRefRejected`;
    - `sameAsCurrentRejected`;
    - `rejectedRepeatDropped`;
    - `outOfRangeValuesRejected` (intervalo 10, 13 horas, meta 0, `fromTime` 03:00);
    - `firstValidOfSeveral`;
    - `emptyListIsOkNull`.
- [ ] **Step 2:** `GRADLE :app:testDebugUnitTest --tests "app.brujula.habits.*" --tests "app.brujula.data.*" --tests "app.brujula.claude.*" --tests "app.brujula.suggestions.*"` → FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4:** Suite PASS.
- [ ] **Step 5:** Commit `feat: habit suggestion call (Haiku) on aggregated counts, with faster-afternoon schedules`.

### Task 5: Sugerencias — guardar, aceptar con deshacer y tarjeta en Hábitos

**Files:**
- Create: `suggestions/SuggestionStore.kt`, `suggestions/SuggestionService.kt`.
- Modify:
  - `habits/HabitsViewModel.kt` y `habits/HabitsScreen.kt` (tarjeta según `Habitos.dc.html`);
  - `BrujulaApp.kt`: `suggestionStore`, `suggestionContextProvider`, `habitSuggester` y `suggestionService`;
  - `ui/BrujulaNavHost.kt`.
- Test: `suggestions/SuggestionStoreTest.kt`, `suggestions/SuggestionServiceTest.kt`, `habits/HabitsViewModelTest.kt`.

**Interfaces:**
- Consumes: `HabitSuggester`, `HabitSuggestion`, `SuggestOutcome`, `MIN_DAYS_AUTO`, `MIN_DAYS_ON_DEMAND`, `doneText()` (Task 4).
- Produces:
  - `enum class SuggestionStatus { PENDING, ACCEPTED, DISMISSED, UNDONE, STALE }`.
  - `data class StoredSuggestion(val id: Long, val suggestion: HabitSuggestion, val status: SuggestionStatus, val decidedAt: LocalDateTime?)`.
  - `class SuggestionStore(dataStore: DataStore<Preferences>)`, con la clave `habit_suggestions` en el DataStore `routine`:
    - `val entries: Flow<List<StoredSuggestion>>`, la más nueva primero;
    - `suspend fun savePending(s: HabitSuggestion, id: Long): StoredSuggestion`: la `PENDING` anterior pasa a `STALE`, y se guardan como máximo 20;
    - `suspend fun setStatus(id: Long, status: SuggestionStatus, at: LocalDateTime)`;
    - `suspend fun lastRun(): LocalDateTime?` y `suspend fun lastAttempt(): LocalDateTime?`;
    - `suspend fun markAttempt(at: LocalDateTime, success: Boolean)`;
    - `suspend fun rejected(now: LocalDateTime): Set<Pair<Long, SuggestionKind>>`: las `DISMISSED` o `UNDONE` decididas en los últimos 30 días.

    Un JSON dañado se lee como lista vacía y nunca lanza.
  - `data class SuggestionCard(val id: Long, val message: String, val accepted: Boolean, val doneText: String?)`.
  - `sealed interface SuggestRequestResult { data object Shown; data class Message(val text: String) }`.
  - `class SuggestionService(store: SuggestionStore, suggester: HabitSuggester, habitRepository: HabitRepository, resync: ReminderResync, clock: Clock, syncScope: CoroutineScope, hasKey: suspend () -> Boolean, isOnline: suspend () -> Boolean)`:
    - `fun card(): Flow<SuggestionCard?>`: la `PENDING` más nueva, o la `ACCEPTED` decidida en el día lógico de hoy;
    - `fun maybeRunAuto(): Job?` (D12), en `syncScope` y de un solo vuelo. Cada intento llama `markAttempt`, con `success = true` cuando Claude respondió (haya o no sugerencia);
    - `suspend fun requestNow(): SuggestRequestResult`, de un solo vuelo y con `MIN_DAYS_ON_DEMAND`;
    - `suspend fun accept(id: Long): String?`, con `Mutex`: relee el hábito; si no existe o `(schedule, dailyTarget) != before` → `STALE` y devuelve `CHANGED`; si no, `upsert` con `after`, `ACCEPTED`, resync en `NonCancellable` y devuelve `null`;
    - `suspend fun undo(id: Long): String`, con `Mutex`: si el hábito sigue igual a `after` → restaura `before`, `UNDONE`, resync, `UNDONE_TEXT`; si no → `STALE` y `KEPT`;
    - `suspend fun dismiss(id: Long)` → `DISMISSED`.
  - Copias: `CHANGED = "Ese hábito cambió desde la sugerencia. No lo toqué."`, `KEPT = "Ya cambiaste ese hábito; no lo toqué."`, `UNDONE_TEXT = "Deshice el cambio."`, `THINKING = "Claude está mirando tus hábitos…"`.
  - `requestNow` traduce `Refused` y `Failed` a `DictationInterpreter.UNAVAILABLE`, e `InvalidKey` a `DictationInterpreter.INVALID_KEY`.
  - AppContainer conecta `SuggestionContextProvider(rejected = { suggestionStore.rejected(clock.now()) })`, `hasKey = { claudeKeyStore tiene clave }` e `isOnline(context)`. No hay ciclo: el proveedor lee el store, no el servicio.
  - `HabitsViewModel(..., suggestions: SuggestionService? = null)`:
    - `HabitsUiState` gana `suggestion: SuggestionCard?`, `suggestionBusy: Boolean` y `suggestionMessage: String?`;
    - funciones `acceptSuggestion()`, `dismissSuggestion()`, `undoSuggestion()`, `requestSuggestion()` y `dismissSuggestionMessage()`;
    - en `init`, `suggestions?.maybeRunAuto()`.
  - Tarjeta, debajo de la racha:
    - pendiente → texto de Claude, `Sí, porfa` (primario) y `Así está bien`;
    - aceptada → `doneText` + `Deshacer`.

    Sin tarjeta, al final va el botón de texto `Pedir una sugerencia a Claude`; mientras corre muestra `THINKING`, y el resultado sale como mensaje.

- [ ] **Step 1: Pruebas que fallan**
  - `SuggestionStoreTest`:
    - `savePendingSupersedesPrevious`;
    - `historyCappedAt20`;
    - `damagedJsonIsEmpty`;
    - `lastRunAndAttemptPersist`.
  - `SuggestionServiceTest` (`FakeClaudeGateway`, Room en memoria, `FixedClock`):
    - `autoRunsWhenDue`: última corrida hace 8 días, intento hace 2, clave, red y 7 días de registros → una llamada y una `PENDING`;
    - `autoSkipsWhenRecentNoKeyOfflineOrLittleData`: en cada caso, ninguna llamada;
    - `autoFailureRetriesNextDayOnly`;
    - `autoIsSingleFlight`;
    - `acceptWritesHabitAndResyncs`: `Every(90)` → `Every(90, FasterStretch(15:00, 60))`, un solo resync, y la tarjeta aceptada con `Listo: desde las 15:00 te aviso cada 60 min.`;
    - `acceptRefusesWhenHabitChanged`: devuelve `CHANGED`, no escribe nada y queda `STALE`;
    - `undoRestoresWhenUnchanged`;
    - `undoKeepsLaterEdit`: devuelve `KEPT` y deja intacta la edición;
    - `dismissedNotSuggestedAgainFor30Days`: el contexto la trae en `rejected` y un `SuggestionItem` igual se descarta; a los 31 días ya no;
    - `requestNowMessages`: `NotEnoughData` → `NOT_ENOUGH`; `Ok(null)` → `NONE`; `Offline` → `OFFLINE`.
  - `HabitsViewModelTest`:
    - `suggestionCardFlowsToState`;
    - `acceptAndDismissCallService`;
    - `openingHabitsTriggersAuto`.
- [ ] **Step 2:** `GRADLE :app:testDebugUnitTest --tests "app.brujula.suggestions.*" --tests "app.brujula.habits.*"` → FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4:** `GRADLE :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` → PASS.
- [ ] **Step 5:** Commit `feat: habit suggestions card with accept, undo and 30-day dismissal`.

### Task 6: Widget de inicio (RemoteViews)

**Files:**
- Create:
  - `widget/WidgetModel.kt`: `WidgetModel` y `buildWidgetModel`;
  - `widget/WidgetUpdater.kt`: render, `updateAll`, `hasWidgets` y la alarma;
  - `widget/BrujulaWidgetProvider.kt`;
  - `widget/WidgetActionReceiver.kt`;
  - `widget/WidgetRefresh.kt`: `widgetChanges` y `startWidgetRefresh`;
  - `res/layout/widget_brujula.xml` y `res/xml/widget_brujula_info.xml`;
  - en `res/drawable/`: `widget_card_{light,dark}.xml`, `widget_chip1_{light,dark}.xml` (suave 2), `widget_chip2_{light,dark}.xml` (suave), `widget_mic_{light,dark}.xml` (acento), `ic_widget_water.xml`, `ic_widget_balm.xml`, `ic_widget_habit.xml`, `ic_widget_plus.xml` e `ic_widget_mic.xml`.
- Modify:
  - `today/TodayViewModel.kt`: extraer `todayHabitChips`;
  - `core/theme/ThemeState.kt`: `Routine.isDarkAt`, que `darkFlow` pasa a usar;
  - `data/db/Daos.kt` y `habits/HabitRepository.kt`: `observeLogCount`;
  - `BrujulaApp.kt`: `startWidgetRefresh` en `startBackgroundWork`;
  - `reminders/BootReceiver.kt`: también `WidgetUpdater.updateAll`;
  - `res/values/strings.xml`: `widget_description` = `Tu bloque actual, tus hábitos y el dictado`;
  - `AndroidManifest.xml`.
- Test: `widget/WidgetModelTest.kt`, `widget/WidgetActionReceiverTest.kt` (Robolectric, como `ReceiversTest`), `widget/WidgetRefreshTest.kt`, `today/TodayViewModelTest.kt` (sin cambios, debe seguir verde).

**Interfaces:**
- Consumes: `DaySnapshotProvider.snapshot` (`container.daySnapshots`), `HabitChip`, `resolveDark`.
- Produces:
  - `fun todayHabitChips(habits: List<Habit>, counts: Map<Long, Int>, day: LocalDate): List<HabitChip>`: la misma regla de hoy en `TodayViewModel.build` (activos, `ESSENTIAL`, meta > 0, día incluido, los dos primeros). `TodayViewModel` la usa.
  - `fun Routine.isDarkAt(now: LocalDateTime): Boolean = resolveDark(themeMode, now.toLocalTime(), wakeFor(logicalDay(now)))`.
  - `HabitDao.observeLogCount(): Flow<Int>` (`SELECT COUNT(*) FROM habit_logs`) y `HabitRepository.observeLogCount()`.
  - `data class WidgetModel(val nowLabel: String, val nowTitle: String, val nextText: String, val habits: List<HabitChip>, val dark: Boolean, val nextRefresh: LocalDateTime)`:
    - `nowLabel`: `Ahora · hasta 10:30`; libre con algo después → `Ahora · hasta 15:00`; libre sin nada → `Ahora`;
    - `nowTitle`: el título del bloque o `Libre`;
    - `nextText`: `Ejercicio 10:30` o `Nada más por hoy`.
  - `const val WIDGET_MAX_REFRESH_MIN = 60L`. `fun buildWidgetModel(now: LocalDateTime, snapshot: DaySnapshot, chips: List<HabitChip>, routine: Routine): WidgetModel`:
    - bloques = `snapshot.own` + `snapshot.external` (título = `displayTitle`);
    - actual = el de inicio más tardío con `now in span` (si empatan, el propio);
    - siguiente = el primero con `start > now`;
    - `nextRefresh = min(fin del actual, inicio del siguiente, próximo cambio de tema (21:00 o la hora de levantarse, solo en AUTO), (día + 1) a las 04:00, now + 60 min)`, contando solo instantes `> now`.
  - `object WidgetUpdater`:
    - `const val ACTION_REFRESH = "app.brujula.widget.REFRESH"`;
    - `fun hasWidgets(context: Context): Boolean`;
    - `suspend fun updateAll(context: Context)`: si no hay widgets no hace nada. Si hay: `snapshot(logicalDay(now))`, chips, rutina → modelo → render → `AppWidgetManager.updateAppWidget`, y programa `scheduleNext(model.nextRefresh)`;
    - `internal fun render(context: Context, m: WidgetModel): RemoteViews`;
    - `fun scheduleNext(context: Context, at: LocalDateTime)`: `AlarmManager.set(AlarmManager.RTC, …)` con un broadcast explícito a `BrujulaWidgetProvider` con `ACTION_REFRESH` (`FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT`);
    - `fun cancelRefresh(context: Context)`.
  - Render, con un solo layout y los colores de `Inicio.dc.html`:
    - claro: tarjeta `#FFFFFF`, texto `#14121F`, secundario `#55516E`, suave `#E3E0FF`, suave 2 `#FFF0D1`, acento `#4B3BFF`, texto sobre acento `#FFFFFF`, acento oscuro `#3A2BE0`, acento 2 oscuro `#9A6200`;
    - oscuro: `#1A1733`, `#F1F0FF`, `#A9A5C9`, `#241F52`, `#33280F`, `#7B6CFF`, `#0F0D1F`, `#A79CFF`, `#FFC657`;
    - se aplican con `setInt(id, "setBackgroundResource", …)` (drawables claro/oscuro), `setTextColor` y `setInt(icon, "setColorFilter", …)`;
    - con 0 o 1 hábito se ocultan los botones que sobran.

    Intents:
    - cuerpo → `PendingIntent.getActivity(DeepLinks.intent(context, DeepLinks.TODAY))`;
    - cada "+" → broadcast explícito a `WidgetActionReceiver` con `ACTION_ADD_HABIT`, el extra `habitId` y el dato único `brujula://widget/habit/{id}`;
    - micrófono → `DeepLinks.intent(context, DeepLinks.VOICE)` en esta tarea (Task 7 lo cambia a la ventanita).

    Textos accesibles: `Sumar un vaso de agua` / `Marcar bálsamo labial` por ícono (otros hábitos: `Sumar uno a {nombre}`) y `Dictar con la voz`.
  - `class BrujulaWidgetProvider : AppWidgetProvider()`: `onUpdate` y `ACTION_REFRESH` → `launchAsync { WidgetUpdater.updateAll(app) }`; `onDisabled` → `cancelRefresh`.
  - `widget_brujula_info.xml`: `minWidth 250dp`, `minHeight 110dp`, `targetCellWidth 4`, `targetCellHeight 2`, `resizeMode horizontal`, `updatePeriodMillis 0`, `widgetCategory home_screen`, `previewLayout @layout/widget_brujula` y `description @string/widget_description`.
  - `class WidgetActionReceiver : BroadcastReceiver()`:
    - `ACTION_ADD_HABIT = "app.brujula.widget.ADD_HABIT"`;
    - con `launchAsync`: si el hábito existe y está activo → `habitRepository.logDone(id, clock.now())`; después `WidgetUpdater.updateAll`;
    - un id desconocido o inactivo se ignora.
  - `fun widgetChanges(routineRepository, activityRepository, habitRepository, classifications: WorkClassificationRepository): Flow<Unit>`: combina rutina, actividades, hechos, overrides, hábitos, `observeLogCount()`, ids de Google enlazados y clasificaciones.
  - `const val WIDGET_DEBOUNCE_MS = 1_000L`. `fun startWidgetRefresh(scope: CoroutineScope, changes: Flow<*>, hasWidgets: () -> Boolean, update: suspend () -> Unit, debounceMs: Long = WIDGET_DEBOUNCE_MS): Job`: debounce; llama `update` solo si `hasWidgets()`; un error se registra y el recolector sigue, como `startReminderResync`.
  - Manifest:
    - `BrujulaWidgetProvider` `exported="true"` con `intent-filter` `APPWIDGET_UPDATE` y `meta-data android.appwidget.provider`;
    - `WidgetActionReceiver` `exported="false"`.

- [ ] **Step 1: Pruebas que fallan**
  - `WidgetModelTest`:
    - `currentAndNextFromTimeline`: 10:24 con `Trabajo` 8:00–10:30 y `Ejercicio` 10:30 → `Ahora · hasta 10:30`, `Trabajo`, `Ejercicio 10:30`;
    - `freeUntilNext`: `Ahora · hasta 15:00` y `Libre`;
    - `nothingLeft`: `Nada más por hoy`;
    - `externalEventsCount`;
    - `overlappingPicksLatestStart`;
    - `nextRefreshIsEarliestBoundary`: fin de bloque 10:30; 21:00 en AUTO; tope de 60 min; a las 03:50 → 04:00;
    - `darkFollowsAppThemeNotSystem`: AUTO a las 21:30 → oscuro; LIGHT a las 23:00 → claro;
    - `chipsMatchToday`: igual que `todayHabitChips`.
  - `WidgetActionReceiverTest`:
    - `addHabitLogsOffline`: el conteo del día sube en 1 sin red;
    - `unknownOrInactiveHabitIgnored`.
  - `WidgetRefreshTest`:
    - `changesSkippedWithoutWidgets`;
    - `habitLogChangeEmits`;
    - `burstDebouncedToOneUpdate`;
    - `errorKeepsCollecting`.
- [ ] **Step 2:** `GRADLE :app:testDebugUnitTest --tests "app.brujula.widget.*" --tests "app.brujula.today.*" --tests "app.brujula.core.*"` → FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4:** `GRADLE :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` → PASS.
- [ ] **Step 5:** Commit `feat: home-screen widget with current block, habit counters and theme`.

### Task 7: Ventanita flotante de dictado desde el widget

**Files:**
- Create: `voice/QuickVoiceActivity.kt`, `voice/QuickVoiceScreen.kt` (tarjeta Compose al pie, como la franja de escucha de `Inicio.dc.html`) y `voice/QuickVoicePolicy.kt`.
- Modify:
  - `voice/VoiceViewModel.kt`: `queueWithoutKey` y `VoiceState.SavedNeedsKey`;
  - `voice/VoiceScreen.kt`: rama `SavedNeedsKey` (no se usa con el valor por defecto, pero el `when` debe cubrirla);
  - `res/values/themes.xml`: `Theme.Brujula.Floating`;
  - `AndroidManifest.xml`;
  - `widget/WidgetUpdater.kt`: el micrófono abre `QuickVoiceActivity`.
- Test: `voice/VoiceViewModelTest.kt`, `voice/QuickVoicePolicyTest.kt`.

**Interfaces:**
- Consumes: `VoiceViewModel`, `container.interpreter` / `executor` / `pendingDictations` / `drainPendingDictations`, `DeepLinks.intent`, `Routes.proposal`, `darkFlow` (o `Routine.isDarkAt`, Task 6).
- Produces:
  - `VoiceViewModel(..., queueWithoutKey: Boolean = false)`. Con `true`, `NoKey` e `InvalidKey` → `pending.add(full, now())` en `NonCancellable` y estado `VoiceState.SavedNeedsKey` (sin pedir drenado). Con `false` todo queda como hoy.
  - `object QuickVoiceCopy { LISTENING = "Escuchando… di lo que vas a hacer"; SAVED_OFFLINE = "Lo guardé. Lo agendo cuando vuelva internet."; SAVED_NO_KEY = "Lo guardé. Agrega tu clave de Claude en Tu rutina y lo agendo."; MIC_DENIED = "Sin permiso de micrófono."; OPEN_APP = "Abrir Brújula"; ANSWER = "Responder" }`. Lo demás reutiliza `VoiceCopy` (`Claude está entendiendo…`, `Agendado`, `Deshacer`, `Deshecho`, `Ver propuesta`).
  - `object QuickVoicePolicy { const val CLOSE_AFTER_RESULT_MS = 4_000L; const val CLOSE_AFTER_UNDO_MS = 1_500L; const val CLOSE_AFTER_SAVED_MS = 3_000L; fun autoCloseDelay(state: VoiceState): Long? }`:
    - `Result` sin `proposal` → 4000; con `proposal` → `null`;
    - `Undone` → 1500;
    - `SavedOffline` y `SavedNeedsKey` → 3000;
    - el resto → `null`.
  - `class QuickVoiceActivity : ComponentActivity()`:
    - El VM se arma con `queueWithoutKey = true` y el mismo `requestDrain` que el NavHost.
    - **Al crear:** con permiso de micrófono → `vm.start()`; sin permiso → `RequestPermission`. Si se niega → `vm.onMicDenied()` y la tarjeta muestra `MIC_DENIED` + `OPEN_APP` (→ `DeepLinks.intent(context, DeepLinks.VOICE)`, y cierra).
    - **Cierre:** tocar fuera de la tarjeta, Atrás, u `onStop` sin rotación ni permiso en curso → `finish()`. Si estaba escuchando se cancela; si Claude estaba entendiendo, la lógica actual lo encola.
    - **Estados:**
      - `OpenProposal(day, request)` → `startActivity(DeepLinks.intent(this, Routes.proposal(day, request.take(DeepLinks.MAX_REQUEST))))` y `finish()`;
      - `Result` con `proposal` → botón `Ver propuesta` (lo mismo);
      - `NeedsMore` → la pregunta + `Responder` (`vm.start()`);
      - `Error` → mensaje + `Abrir Brújula` (voz);
      - `autoCloseDelay` no nulo → cierra tras ese tiempo; el temporizador se reinicia si cambia el estado.
    - Tema: `BrujulaTheme(dark = …)` con el tema de la app.
  - Manifest: `.voice.QuickVoiceActivity` con `exported="false"`, `theme="@style/Theme.Brujula.Floating"` (`windowIsTranslucent`, fondo transparente, `windowNoTitle`, atenuado), `excludeFromRecents="true"`, `taskAffinity="app.brujula.quickvoice"` y `launchMode="singleTask"`.
  - Widget: el micrófono pasa a `PendingIntent.getActivity(Intent(context, QuickVoiceActivity::class.java).addFlags(FLAG_ACTIVITY_NEW_TASK))`.

- [ ] **Step 1: Pruebas que fallan**
  - `VoiceViewModelTest`:
    - `noKeyQueuesWhenQueueWithoutKey`: queda `SavedNeedsKey` y el texto está en `pending`;
    - `invalidKeyQueuesWhenQueueWithoutKey`;
    - `noKeyKeepsErrorByDefault` (comportamiento actual).
  - `QuickVoicePolicyTest.autoCloseDelays`: cada estado con su valor de arriba.
- [ ] **Step 2:** `GRADLE :app:testDebugUnitTest --tests "app.brujula.voice.*"` → FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4:** `GRADLE :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` → PASS.
- [ ] **Step 5:** Commit `feat: floating quick-dictation window from the widget`.

### Task 8: Verificación en el teléfono

- [ ] **Step 1: Respaldo antes de instalar** (la DB cambia a v6). `adb exec-out run-as app.brujula tar -cf - databases files shared_prefs > .superpowers/backups/fase3b-antes.tar`. Verificar que el archivo pesa más de 0 bytes.
- [ ] **Step 2: Compilar e instalar.** `GRADLE :app:testDebugUnitTest :app:lintDebug :app:installDebug` → verde e instalado como actualización. La migración 5→6 conserva todo: Hoy, Hábitos y las propuestas aceptadas se ven igual que antes.
- [ ] **Step 3: Semana.**
  - La pestaña `Semana` abre la semana actual con hoy elegido, y las barras de hoy coinciden con la de Hoy.
  - El lunes 16 (festivo) sale como descanso y fuera del promedio.
  - Ir a la semana siguiente; abrir un día futuro; tocar una actividad de una sola vez → formulario → editar la hora → volver: la barra cambia.
  - En una recurrente sin copia, `Quitar solo este día` → desaparece ese día y no los demás → `Deshacer`.
  - `Que Claude arme este día` en mañana abre la Propuesta.
  - Un evento de Outlook sale con su color y su hoja.
- [ ] **Step 4: Sueño.** Esa noche, en el aviso `Hora de ir a dormir`, tocar `Ya me acuesto`. A la mañana siguiente, abrir la app antes de 2 h después de la hora de levantarse. En Semana, el promedio de sueño cambió respecto del estimado (anotar ambos valores). *Se verifica al día siguiente; no bloquea los demás pasos.*
- [ ] **Step 5: Widget.**
  - Agregarlo a la pantalla de inicio: bloque actual y siguiente correctos.
  - En modo avión, `+` de agua → `x / 8` sube al instante y Hoy lo refleja.
  - En Tu rutina, Tema Oscuro → el widget cambia en ~1 s; volver a Automático.
  - Crear un bloque que termina en 2 min y dejar la pantalla encendida → el widget pasa al siguiente en la frontera.
  - `adb shell dumpsys alarm | findstr brujula` muestra la alarma del widget como `RTC` (no `RTC_WAKEUP`).
- [ ] **Step 6: Ventanita.**
  - Micrófono del widget → la ventanita escucha sobre la pantalla de inicio → "mañana a las 7 de la tarde ejercicio una hora" → `Agendado` + `Deshacer` → se cierra sola, y el widget o Hoy lo muestran.
  - Repetir y tocar `Deshacer` → `Deshecho` y se cierra.
  - En modo avión → `Lo guardé. Lo agendo cuando vuelva internet.`; al volver la red llega la notificación de agendado.
  - "Arma mi día de mañana" → `Ver propuesta` abre la app en la Propuesta.
  - Quitar el permiso de micrófono en Ajustes → la ventanita lo pide; negar → `Sin permiso de micrófono.` + `Abrir Brújula`.
- [ ] **Step 7: Sugerencias.**
  - Con al menos 3 días de registros, en Hábitos tocar `Pedir una sugerencia a Claude` → llega una tarjeta en menos de 60 s (o `Por ahora no tengo sugerencias. Vas bien.`).
  - `Sí, porfa` → el horario del hábito cambia (lo dice su tarjeta) y `adb shell dumpsys alarm` refleja los avisos nuevos.
  - `Deshacer` lo devuelve.
  - Pedir otra y tocar `Así está bien` → no reaparece al volver a pedir.
  - Sin red, el botón muestra `Sin conexión. Claude necesita internet para sugerirte cambios.`.
- [ ] **Step 8:** Commit de ajustes si los hubo.

---

## Fuera de alcance (después de la Fase 3B)

- Mover o saltar ocurrencias de recurrentes con copia a Google (D4 de la 3A): requiere excepciones por instancia en Google Calendar.
- Editar series completas desde una propuesta.
- Planificar más allá de hoy + 6.
- Sueño real (Health Connect, reloj) y la rutina guardada por fecha: el sueño planeado de semanas pasadas usa la rutina actual.
- Más de un tamaño de widget, y widget en la pantalla de bloqueo.
- Sugerencias que crean, borran o desactivan hábitos, o que cambian días o ventana.

## Decisiones para confirmar con el usuario

1. **Promedio de trabajo en Semana (D5):** solo cuenta días laborales hasta hoy. Festivos y fines de semana muestran su barra pero no bajan el promedio. *Recomiendo así (es la misma regla de §11: sin meta en descanso).*
2. **Sueño (D2):** sale de tu rutina y se ajusta con `Ya me acuesto` en el aviso para dormir y con la primera vez que abres la app entre las 4:00 y 2 h después de tu hora de levantarte. *Recomiendo así; abrir la app a las 10:30 un día laboral no cuenta como despertar.*
3. **Widget (D8):** un solo tamaño (4×2) con los mismos dos hábitos que ves en Hoy, y sigue el tema que elegiste en la app (no el del sistema). *Recomiendo así.*
4. **Ventanita (D10):** sin internet o sin clave guarda el dictado y lo agenda después avisándote; después de `Agendado` se cierra sola a los 4 s. *Recomiendo así, para que nada se pierda y no tengas que cerrarla.*
5. **Sugerencias (D11–D15):** Claude Haiku 5.5, una vez por semana al abrir Hábitos (o cuando la pidas). Solo cambia el intervalo, las horas o la meta de un hábito que ya existe, y puede crear un tramo "más seguido desde las 3 pm". `Así está bien` la silencia 30 días. *Recomiendo así.*
