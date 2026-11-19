# Brújula · Fase 3A (planificación con Claude) — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que Claude proponga y organice el día ("arma mi día", "pasa el gym a la tarde", "hoy no voy a entrenar") y que el usuario lo acepte de una vez con deshacer exacto. Esto incluye el ritual nocturno "¿Armamos mañana?" con hora sugerida para dormir y el resumen de la mañana. Todo respeta la regla C: Claude propone primero y nunca toca eventos externos.

**Architecture:** Un `DayPlanner` hace una sola llamada a Claude Opus 5.5 con salida estructurada y un schema escrito a mano. Claude devuelve solo **cambios** (`create` / `move` / `skip`) referidos a ids cortos del contexto (`A12` = actividad propia, `E3` = evento externo). La app valida localmente cada cambio contra el día real y construye una `Proposal`, con vista previa etiquetada y totales. La guarda en la tabla `proposals` (DB v5), así sobrevive a la muerte del proceso. `ProposalExecutor.accept` aplica todo en una transacción Room y guarda en la misma fila los datos para deshacer. Una actividad recurrente nunca se edita como serie: se mueve o se salta solo esa ocurrencia, con una fila en `activity_overrides` que respetan Hoy, choques, recordatorios y el contexto de Claude. El aviso nocturno, el resumen de la mañana y el aviso para dormir son tres `ReminderKind` nuevos del planificador de recordatorios que ya existe (horizonte de 24 h y alarma de reprogramación cada 12 h).

**Tech Stack:** el de las fases anteriores (Kotlin, Compose, Room, DataStore, AlarmManager, `com.anthropic:anthropic-java:3.9.0`, `SpeechRecognizer`). Sin dependencias nuevas.

**Spec:** `docs/superpowers/specs/2026-11-18-brujula-design.md`. Secciones: §4 (regla C), §5.1 (tarjeta de Claude), §5.2 (cambios por voz), §5.2b (pedir otro horario, "mejor dictarlo"), §5.3 (Propuesta), §5.4 (planificación nocturna), §5.5 (ejercicio: moverlo, no borrarlo), §5.8 (hora del aviso nocturno), §8, §9, §10, §11 y §12.

**Bocetos (texto y disposición):** `Propuesta.dc.html`, `ArmarManana.dc.html`, `HoyNoche.dc.html`, `HoyAmanecer.dc.html` y `Avisos.dc.html`, todos en `C:/Users/juesn/AppData/Local/Temp/claude/05a7d0fa803c1f3a/739e5df5-de06-4286-b8a7-cdc6e26ed341/scratchpad/agenda-design/project/`.

**Referencia de API:** antes de tocar el SDK, leer la skill `claude-api` (`java/claude-api/README.md` y `tool-use.md` § Structured Output) en `C:/Users/juesn/AppData/Local/Temp/claude/bundled-skills/2.1.327/2ea7d793618a426ddd4f1cd73d09d2e1/claude-api/`. Copiar el patrón de `DictationCall` en `claude/AnthropicClaudeGateway.kt` y no adivinar nombres.

## Decisiones de diseño (tomadas por el planificador)

- **D1 · Propuestas en la base de datos.** Tabla `proposals` en la DB v5, con estado, payload JSON y datos para deshacer. *Por qué:* §8 pide guardar "propuestas y su historial", y una fila pendiente sobrevive a la muerte del proceso.
- **D2 · Claude devuelve solo cambios con referencias.** La vista previa, las etiquetas y los totales se calculan localmente. *Por qué:* la app nunca confía en Claude para saber qué es externo o qué cambió, y la regla C se impone en código.
- **D3 · Mover o saltar una recurrente afecta solo esa ocurrencia.** Se guarda en `activity_overrides(activityId, day, start?, durationMin?, skipped)` y la serie nunca se edita. Una actividad de una sola vez se edita en su fila, y su copia en Google se actualiza con `GoogleCopySync.afterSave`. *Por qué:* "pasa el gym a la tarde" es de ese día; la copia de Google sigue sola en el caso de una sola vez.
- **D4 · Una recurrente con copia a Google se trata como fija.** No se puede mover ni saltar en 3A. *Por qué:* su copia es un solo evento con RRULE, y una excepción por instancia en Google queda fuera de alcance (→ 3B).
- **D5 · Columna `activities.origin`** (`UNKNOWN | DICTATION | PLAN`). Las filas existentes quedan `UNKNOWN`, sin etiqueta. *Por qué:* §5.3 exige la etiqueta "Lo dictaste" y hoy no se sabe de dónde vino cada actividad.
- **D6 · Etiquetas.** `Nuevo` (create), `Movido` (move), `Quitado` (skip, tachado), `Fijo` (externo o almuerzo), `Lo dictaste` (sin cambios y con origen DICTATION); el resto no lleva etiqueta. *Por qué:* "hoy no voy a entrenar" necesita un estado visible que la spec no nombra.
- **D7 · Almuerzo.** En días laborales, el hueco entre los dos primeros bloques de trabajo de la rutina (por defecto 12:00–14:00) es "Almuerzo y pausa", `Fijo`. Claude no puede poner nada ahí y no se guarda como actividad. *Por qué:* §5.3 lo lista como Fijo, pero no existe ninguna entidad de almuerzo.
- **D8 · Modelo de planificación.** `ClaudeModels.PLANNING = "claude-opus-5-5"`, esfuerzo `MEDIUM` (el predeterminado de Opus 5.5), `maxTokens` 16000 y tiempo de 90 s por petición. *Por qué:* armar un día equilibra varias restricciones a la vez (meta de trabajo, huecos, ejercicio, almuerzo, sueño). `LOW` basta para leer un dictado pero recorta ese razonamiento, y `HIGH` duplicaría una espera que el usuario mira en pantalla.
- **D9 · Ajustar.** Es una llamada nueva con los cambios de la propuesta anterior más el ajuste dictado o escrito en `<ajuste>`. La nueva propuesta reemplaza a la anterior (`SUPERSEDED`), y el dictado del ajuste ocurre dentro de la pantalla Propuesta. *Por qué:* la llamada no tiene estado, y anclar a lo que el usuario vio evita que Claude rehaga el día entero.
- **D10 · Días que se pueden armar:** de hoy (día lógico) a hoy + 6. Si el día es hoy, nada se mueve al pasado ni se toca lo que ya empezó o está hecho. *Por qué:* cubre "mañana", "el viernes" y el formulario sin abrir la semana completa (Semana es 3B).
- **D11 · Bloques que crea un plan.** Llevan `copyToGoogle = false`. Los de `WORK` no tienen aviso; `EXERCISE` y `PERSONAL` avisan `REMIND_BEFORE_MIN` (15) antes. Un plan no crea `APPOINTMENT`. *Por qué:* las citas involucran a otras personas y solo se agendan dictándolas, y un aviso por cada bloque de trabajo sería una lluvia de notificaciones.
- **D12 · Hora sugerida para dormir.** Se toma la hora de levantarse del día siguiente; si lo primero fijo de ese día empieza antes de esa hora + 30 min, se usa ese inicio − 30 min, nunca antes de las 05:00. La hora de dormir es la más temprana entre la de acostarse de la rutina y esa hora de levantarse − 8 h, redondeada hacia abajo a 15 min. El aviso es 30 min antes. *Por qué:* coincide con el boceto ("Para dormir 8 h, acuéstate a las 12:00" / "avisarme a las 23:30") y nunca pide acostarse más tarde de lo habitual.
- **D13 · Avisos de planificación.** Son tres `ReminderKind` nuevos en el `ReminderPlanner` y `AlarmReminderScheduler` actuales, en un canal nuevo `planificacion` ("Planificación", `IMPORTANCE_DEFAULT`). Al tocarlos se abre la ruta correcta mediante el extra `DeepLinks.EXTRA_ROUTE`, con lista blanca de rutas. *Por qué:* reutiliza la reprogramación probada (arranque, cambio de hora, alarma de reprogramación a las 12 h) y no suena encima de lo que el usuario está haciendo por la noche.
- **D14 · Resumen de la mañana.** Llega a la hora de levantarse, solo los días con una propuesta `ACCEPTED`. El texto se arma al entregarlo, a partir del día real. *Por qué:* la spec habla del "día ya armado", y los calendarios externos pueden cambiar después de planificar.
- **D15 · Tarjeta de Claude en Hoy.** La alimenta `ClaudeNotes` (DataStore: texto, día, `proposalId?`). La escriben el dictado (respuesta de Claude) y aceptar o deshacer un plan. Muestra `Deshacer` mientras el plan esté `ACCEPTED` y su día no haya pasado. Si no hay nota del día, ofrece `Que Claude arme mi día`. *Por qué:* §5.1, y da una entrada "a pedido" a la Propuesta sin depender de la voz.
- **D16 · Deshacer revierte solo lo que sigue como lo dejó el plan.** Lo que el usuario editó después se conserva y se informa. Deshacer devuelve la propuesta a `PENDING`, así se puede volver a aceptar ("Volver a la propuesta"). *Por qué:* deshacer nunca debe pisar un cambio posterior del usuario.

## Global Constraints

- Todo lo de las fases anteriores sigue vigente. En particular:
  - Los eventos de Google y Outlook son de solo lectura.
  - El día lógico cambia a las 04:00.
  - Hábitos y avisos solo en vigilia.
  - La app funciona sin internet para todo lo que no sea Claude.
- **Regla C (§4), impuesta en código.** Ningún cambio de una propuesta puede apuntar a un evento externo (`E…`) ni moverse encima de uno. Si un choque solo se resuelve moviendo un evento externo, Claude lo dice como texto en `summary` ("podrías mover la llamada de las 3 a las 4 en tu Outlook"). Nada de una propuesta se aplica sin `Aceptar plan`.
- **Salida estructurada.** NUNCA usar el schema que el SDK deriva de la clase (`outputConfig(Class)` / `StructuredOutputConfig.format(Class)`): revienta en Android (ART no tiene `Method.getAnnotatedReturnType`).
  - Usar schemas escritos a mano en `claude/Schemas.kt`, enviados con `Schemas.betaFormat(...)`.
  - Leer la respuesta con `StructuredJson.parse` (Jackson).
  - Cada clase de salida nueva o cambiada lleva su prueba de deriva en `SchemasTest` contra el schema derivado por el SDK, solo en la JVM.
  - Una propiedad Boolean de Kotlin con prefijo `is` necesita `@get:JsonProperty` y `@field:JsonProperty`. En las clases de esta fase no se usa ese prefijo.
- **Llamada de planificación.**
  - Modelo `ClaudeModels.PLANNING` (`claude-opus-5-5`), esfuerzo `BetaOutputConfig.Effort.MEDIUM`, `maxTokens` 16000.
  - Endpoint beta con `addBeta(AnthropicBeta.SERVER_SIDE_FALLBACK_2026_07_01)` + `fallbacksDefault()`, system cacheable (`cacheControl` efímero).
  - `RequestOptions` con tiempo de 90 s. Sin `thinking`, `temperature` ni prefill.
  - Revisar `stopReason` antes de leer el contenido, como en `DictationCall.read`.
- **Datos que se envían a Claude al planificar.** Solo esto, y nada más:
  - fecha y hora local, zona, día lógico, el día a armar y su tipo;
  - rutina: horas, bloques de trabajo, flexible, meta;
  - títulos y horas de lo que hay ese día (propio y externo) y la primera hora ocupada del día siguiente;
  - nombres de hábitos;
  - resumen de hoy: minutos de trabajo, vasos de agua x/meta, ejercicio hecho / pendiente / no tocaba;
  - el texto pedido o ajustado.

  Los títulos ajenos pasan por `PromptBuilder.sanitize`.
- **Concurrencia.**
  - Aceptar es atómico (una transacción Room) y de un solo vuelo (`Mutex` + revisión de estado `PENDING` dentro de la transacción). Deshacer también.
  - La resincronización de avisos corre en `withContext(NonCancellable)` después del commit. Las copias de Google corren en `container.syncScope`, nunca en el ámbito de la pantalla.
  - Mover una actividad propia de una sola vez con copia → `GoogleCopySync.afterSave`; saltarla (se borra) → `afterDelete`. Deshacer lo revierte con los mismos métodos.
  - Ningún camino llama a `GoogleCalendarWriter` para un evento que no sea copia de Brújula.
- **Copia de pantalla (español, tuteo, sin emojis).** Literal donde la spec o los bocetos la fijan:
  - Propuesta: `Propuesta de Claude`, `Tu jueves 19`, `Trabajo`, `Ejercicio`, `Sueño`, `Ajustar`, `Aceptar plan`, `Mañana está listo`, `Volver a la propuesta`.
  - Noche: `¿Armamos mañana?`, `Así te fue hoy`, `Mañana ya tienes`, `Que Claude arme mi día`, `Agregar algo`, `Mañana lo veo`.
  - Avisos: `Tu día está listo`, `Ver mi día`, `Ajustar`.
- **Build en una máquina con poca memoria.** Siempre `--max-workers=2`, con el prefijo `$env:JAVA_HOME="C:\Users\juesn\tools\jdk-21"; $env:Path="$env:JAVA_HOME\bin;$env:Path";`. Ninguna API por encima de minSdk 31.
- **Commits** con el trailer de dos líneas (`Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` / `Claude-Session: https://claude.ai/code/session_01XN8G1c95yQpddiVFPfCNKU`).

En adelante, `GRADLE` = `$env:JAVA_HOME="C:\Users\juesn\tools\jdk-21"; $env:Path="$env:JAVA_HOME\bin;$env:Path"; .\gradlew.bat --max-workers=2`.

## Review Focus

1. **Aceptar una propuesta vieja.** El usuario editó o borró una actividad referida después de recibir la propuesta, un bloque de hoy ya empezó, o el día ya pasó. No se aplica nada, el usuario ve `Tu agenda cambió desde esta propuesta.` con `Pedir una nueva`. → Task 4 (`acceptRefusesStaleProposal`, `acceptRefusesPastOps`) y Task 5.
2. **Deshacer después de editar algo que el plan creó o movió.** Se revierte solo lo que sigue igual, lo editado se conserva y se informa `Dejé 1 cambio que hiciste después.`. → Task 4 (`undoKeepsItemsEditedAfterAccept`).
3. **Claude se sale de las reglas.** Apunta a un evento externo, inventa una referencia, mueve algo al pasado o fuera del día, o lo pone encima de un evento fijo o del almuerzo. Ese cambio se descarta y aparece en `No pude aplicar:`, y el resto de la propuesta sigue. → Task 3.
4. **Cerca de la medianoche.** Armar "mañana" a las 00:30, un bloque movido a las 00:30 del día de calendario siguiente, la hora de dormir después de las 00:00 y el aviso nocturno con la hora de acostarse antes de las 22:45. Todo cuenta por día lógico. → Tasks 1, 3 y 7.
5. **Doble toque en `Aceptar plan`, salir a mitad de camino o matar el proceso.** Se aplica una sola vez; la propuesta pendiente reaparece al volver; la copia de Google y la resincronización terminan aunque se cierre la pantalla. → Task 4 (`concurrentAcceptAppliesOnce`) y Task 5 (`pendingProposalSurvivesNewViewModel`).

---

### Task 1: DB v5 — ocurrencias movidas o saltadas, origen de actividades, tabla de propuestas

**Files:**
- Modify:
  - `activities/Activity.kt`: `ActivityOrigin`, `Activity.origin`, `OccurrenceOverride`, `Overrides`, `occurrencesOn(..., overrides)`;
  - `activities/ActivityRepository.kt`;
  - `data/db/Entities.kt`: `ActivityEntity.origin`, `ActivityOverrideEntity`, `ProposalEntity`, mapeos;
  - `data/db/Daos.kt`: consultas de overrides, `ProposalDao`, `upsertKeepingGoogleLink` también conserva `origin`;
  - `data/db/BrujulaDatabase.kt`: versión 5, `AutoMigration(from = 4, to = 5)`, `proposalDao()`, y exporta `schemas/.../5.json`;
  - `today/DayItems.kt`, `today/TodayViewModel.kt`;
  - `claude/DictationContextProvider.kt`, `claude/ActionValidator.kt`: origen `DICTATION`;
  - `reminders/ReminderPlanner.kt`, `reminders/ReminderSync.kt`, `reminders/ResyncTrigger.kt`, `reminders/NotificationActionReceiver.kt`;
  - `activities/form/ActivityFormViewModel.kt`.
- Test:
  - `data/MigrationTest.kt`, `data/ActivityRepositoryTest.kt`;
  - `activities/ActivityTest.kt`;
  - `reminders/ReminderPlannerTest.kt`;
  - `today/TodayViewModelTest.kt`;
  - `activities/form/ActivityFormViewModelTest.kt`;
  - `claude/ActionValidatorTest.kt`, `claude/DictationContextProviderTest.kt`.

**Interfaces:**
- Produces:
  - `enum class ActivityOrigin { UNKNOWN, DICTATION, PLAN }` y `Activity.origin: ActivityOrigin = ActivityOrigin.UNKNOWN` (último parámetro).
  - `data class OccurrenceOverride(val activityId: Long, val day: LocalDate, val start: LocalDateTime?, val durationMin: Int?, val skipped: Boolean)`. `day` es la fecha de la ocurrencia en la serie, la misma clave que los hechos.
  - `typealias Overrides = Map<Pair<Long, LocalDate>, OccurrenceOverride>`.
  - `occurrencesOn(day, activities, doneKeys, overrides: Overrides = emptyMap())`: `skipped` la excluye; `start` cambia el inicio y `durationMin ?: activity.durationMin` la duración. `Occurrence.day` sigue siendo la fecha de la serie, así "hecho" y la clave del aviso `act:{id}:{yyyyMMdd}` no cambian.
  - `logicalOccurrences(day, activities, done, overrides = emptyMap())` y `spillOccurrences(day, activities, done, window, overrides = emptyMap())`.
  - `ActivityRepository.observeOverrides(): Flow<Overrides>`, `getOverride(activityId: Long, day: LocalDate): OccurrenceOverride?`, `setOverride(o: OccurrenceOverride)` y `clearOverride(activityId: Long, day: LocalDate)`.
  - `ActivityOverrideEntity(activityId, day, start: LocalDateTime?, durationMin: Int?, skipped: Boolean)`: PK `(activityId, day)`, FK a `activities` con `CASCADE`.
  - `ProposalEntity(id autogen, day: LocalDate, createdAt: LocalDateTime, status: String, request: String?, summary: String, payload: String, undo: String? = null, acceptedAt: LocalDateTime? = null)`, con índices en `day` y `status`.
  - `ProposalDao`: `insert`, `get(id)`, `latestByStatus(day, status)`, `setStatus(id, status)`, `markAccepted(id, undo, at)`, `observeAcceptedDays(): Flow<List<LocalDate>>`, `observeAcceptedIds(): Flow<List<Long>>` y `supersedePending(day)`.
  - `ActivityEntity.origin` con `@ColumnInfo(defaultValue = "UNKNOWN")`.
  - `ReminderPlanner.plan(..., overrides: Overrides = emptyMap())`. `ReminderSync` y `reminderChanges` leen y observan `observeOverrides()`.
- Todos los que hoy llaman a `occurrencesOn`, `logicalOccurrences` o `spillOccurrences` pasan los overrides: Hoy, el contexto de dictado, `ReminderPlanner`, `exerciseYes` y los choques del formulario.

- [ ] **Step 1: Pruebas que fallan**
  - `MigrationTest.v4ToV5KeepsActivitiesWithUnknownOrigin`: una actividad v4 queda con `origin = 'UNKNOWN'`; se pueden insertar filas en `activity_overrides` y `proposals`; borrar la actividad borra su override (cascade).
  - `ActivityTest.skippedOverrideHidesOnlyThatDay`: la serie diaria sigue el día anterior y el siguiente.
  - `ActivityTest.movedOverrideKeepsSeriesDay`: `occ.day == día de la serie`, `occ.span.start == override.start`, y el hecho `(id, día)` sigue aplicando.
  - `ActivityTest.movedPastMidnightStaysInLogicalDay`: un override a las 00:30 del día siguiente aparece en `logicalOccurrences(día)`.
  - `ReminderPlannerTest.movedOccurrenceRemindsAtNewTime`: aviso a las 19:15 para un inicio movido a 19:30, título `Ejercicio en casa a las 19:30`, clave sin cambio.
  - `ReminderPlannerTest.skippedOccurrenceHasNoReminder`.
  - `TodayViewModelTest.overrideMovesBlockInTimeline`.
  - `ActivityFormViewModelTest.conflictUsesMovedOccurrence`.
  - `ActivityRepositoryTest.upsertKeepsOriginWhenIncomingUnknown`.
  - `ActivityRepositoryTest.overridesRoundTrip`.
  - `ActionValidatorTest`: una actividad válida sale con `origin == DICTATION`.
  - `DictationContextProviderTest.skippedOccurrenceIsNotBusy`.
- [ ] **Step 2:** `GRADLE :app:testDebugUnitTest --tests "app.brujula.data.*" --tests "app.brujula.activities.*" --tests "app.brujula.reminders.*"` → FAIL (no compila).
- [ ] **Step 3: Implementar.** Corregir también el título del aviso, que hoy usa `occ.activity.start`: debe usar `occ.span.start`.
- [ ] **Step 4:** `GRADLE :app:testDebugUnitTest :app:assembleDebug` → PASS. `app/schemas/app.brujula.data.db.BrujulaDatabase/5.json` queda generado y agregado.
- [ ] **Step 5:** Commit `feat: per-occurrence overrides, activity origin and DB v5`.

### Task 2: Contexto del día y llamada de planificación a Claude

**Files:**
- Create:
  - `planning/DaySnapshot.kt`;
  - `claude/PlanActions.kt`: clases de salida estructurada;
  - `claude/PlanContext.kt`: contexto y su proveedor;
  - `claude/PlanPrompt.kt`.
- Modify:
  - `claude/Schemas.kt`: `PLAN`;
  - `claude/ClaudeModels.kt`: `PLANNING`;
  - `claude/ClaudeGateway.kt`: `plan`;
  - `claude/AnthropicClaudeGateway.kt`: `PlanCall` y la lectura común extraída de `DictationCall.read`;
  - `claude/EventClassifier.kt`: `DelegatingGateway.plan`;
  - `claude/PromptBuilder.kt`: `sanitize` pasa a `internal` compartido, como ya está.
- Test:
  - `claude/SchemasTest.kt`, `claude/PlanCallTest.kt`, `claude/PlanPromptTest.kt`, `claude/PlanContextProviderTest.kt`;
  - `planning/DaySnapshotProviderTest.kt`;
  - `claude/FakeClaude.kt`: `var plan`, `planCalls`, `lastPlanUser`.

**Interfaces:**
- Produces:
  - `enum class ExerciseState { DONE, PENDING, NONE }`.
  - `data class DaySummary(val workMinutes: Int, val workGoal: IntRange?, val waterCount: Int?, val waterTarget: Int?, val exercise: ExerciseState)`. El agua sale del primer hábito activo con ícono `WATER`; el ejercicio dice si hay una ocurrencia `EXERCISE` en el día lógico y si está hecha.
  - `data class FixedEvent(val event: ExternalEvent, val countsAsWork: Boolean)`.
  - `data class DaySnapshot(val day: LocalDate, val own: List<Occurrence> /* spill + lógicas, con overrides */, val external: List<FixedEvent> /* con hora, sin copias de Brújula */, val summary: DaySummary)`.
  - `class DaySnapshotProvider(routineRepository, activityRepository, habitRepository, calendarSource, classifications: WorkClassificationRepository) { suspend fun snapshot(day: LocalDate): DaySnapshot }`. Usa las mismas reglas que Hoy (`eventsOnLogicalDay`, `workMinutes` con `logicalSpan`). Si los calendarios fallan, sigue sin externos.
  - `data class PlanResult(val summary: String = "", val changes: List<PlanChange> = emptyList())`.
  - `data class PlanChange(val op: String = "" /* create|move|skip */, val ref: String? = null, val title: String? = null, val activityType: String? = null /* WORK|EXERCISE|PERSONAL */, val date: String? = null /* yyyy-MM-dd */, val start: String? = null /* HH:mm */, val durationMin: Int? = null)`. Las anotaciones `@JsonPropertyDescription` son idénticas a las del schema a mano.
  - `Schemas.PLAN`: todas las propiedades obligatorias, nulables con `["tipo","null"]` y `additionalProperties: false`, igual que `DICTATION`.
  - `data class PlanItemRef(val ref: String, val title: String, val span: TimeSpan, val external: Boolean, val type: ActivityType?, val movable: Boolean, val done: Boolean, val countsAsWork: Boolean, val activityId: Long?, val occurrenceDay: LocalDate?, val oneOff: Boolean, val origin: ActivityOrigin?, val sourceLabel: String?)`.
    - Los ids son `A{activityId}` para lo propio y `E{n}` (1..n, por hora) para lo externo.
    - `movable` = propio, no hecho, de este día lógico (no spill), aún no empezado (`span.start >= now`) y no (recurrente con `copyToGoogle`) (D4).
  - `data class PlanContext(val now: LocalDateTime, val zone: ZoneId, val logicalToday: LocalDate, val day: LocalDate, val dayType: DayType, val routine: Routine, val awake: TimeSpan, val lunch: TimeSpan?, val items: List<PlanItemRef>, val nextDayFirstStart: LocalDateTime?, val habitNames: List<String>, val today: DaySummary)`. `lunch` sigue D7 (null en descanso o con menos de dos bloques de trabajo).
  - `class PlanContextProvider(clock, snapshots: DaySnapshotProvider, habitRepository, routineRepository, zone: () -> ZoneId = { ZoneId.systemDefault() }) { suspend fun forDay(day: LocalDate): PlanContext }`.
  - `object PlanPrompt { fun system(): String; fun user(ctx: PlanContext, request: String?, previous: List<PlanChange>?, adjustment: String?): String }`.
    - `system()` es fijo y cacheable. Contiene:
      - rol;
      - regla C: nunca cambiar `E…`, sugerirlo solo como texto en `summary`;
      - operaciones `create` / `move` / `skip` con sus campos;
      - "moves y skips antes que creates";
      - sin choques con lo fijo ni con el almuerzo, dentro de la vigilia del día y nunca en el pasado;
      - meta de trabajo en días laborales y ninguna en descanso;
      - el ejercicio fijo se mueve ese día y se salta solo si el usuario lo pidió (§5.5);
      - "arma mi día" = completar huecos con bloques de `WORK` hacia la meta y respetar hábitos;
      - nada de `APPOINTMENT`;
      - si no hace falta cambiar nada, `changes` vacío;
      - `summary` en 1–3 frases, español colombiano, tuteo, sin emojis, que explique el porqué.
    - `user()` lleva:
      - reloj y día lógico, con el mismo texto de fechas que `PromptBuilder`;
      - `Día a armar: {día largo} ({iso}), {tipo}`;
      - rutina y meta;
      - la lista `A…`/`E…` con `(movible)` / `(no se puede mover)` / `(calendario externo, fijo)`;
      - `Almuerzo: 12:00–14:00 (fijo)`;
      - primera hora ocupada de mañana;
      - hábitos;
      - `Hoy llevas {h} de trabajo, {x} de {y} vasos de agua, ejercicio {hecho|pendiente|no tocaba}.`;
      - `<pedido>…</pedido>` si hay pedido, y la propuesta anterior + `<ajuste>…</ajuste>` si es un ajuste.
    - Las etiquetas `pedido` y `ajuste` se quitan de cualquier texto ajeno.
  - `ClaudeModels.PLANNING = "claude-opus-5-5"`. `ClaudeGateway.plan(system: String, user: String): GatewayResult<PlanResult>`, que nunca lanza salvo por cancelación.
  - `internal object PlanCall { fun params(system, user): BetaMessageCreateParams; val TIMEOUT: Duration /* 90 s */; fun requestOptions(); fun read(response: BetaMessage): GatewayResult<PlanResult> }`. Los fallos se clasifican con `DictationCall.failureOf(e, "Plan")` / `incompatible(e, "Plan")`.

- [ ] **Step 1: Pruebas que fallan**
  - `SchemasTest.planSchemaMatchesTheSdkDerivedOne`.
  - `PlanCallTest`: `model == "claude-opus-5-5"`; esfuerzo `MEDIUM`; beta de fallback presente y `fallbacks` = default; `maxTokens == 16000`; `TIMEOUT == 90 s`. Respuesta enlatada: refusal → `Refused`; `max_tokens` → `Error(retryable = true)`; JSON con dos cambios → `Ok` con dos `PlanChange`. Un `NoSuchMethodError` en `params` → `Error(config = true)`, igual que `AnthropicClaudeGatewayTest`.
  - `PlanPromptTest`:
    - las líneas de día a armar, refs, almuerzo y resumen de hoy salen con los textos de arriba;
    - el pedido va dentro de `<pedido>`;
    - un título de evento con `</pedido>` sale limpio;
    - a las 00:30 del jueves, "hoy (día lógico) es miércoles";
    - un ajuste lista los cambios previos y el texto en `<ajuste>`.
  - `DaySnapshotProviderTest`:
    - el trabajo suma `WORK` propio + externo marcado como trabajo;
    - agua 3/8;
    - ejercicio `DONE` / `PENDING` / `NONE`;
    - si el calendario lanza, no hay externos.
  - `PlanContextProviderTest`:
    - son no movibles: lo hecho, una recurrente con copia, lo que ya empezó a las 15:00 y lo que viene de ayer (spill);
    - los externos son `E1`, `E2` por hora;
    - almuerzo 12:00–14:00 un jueves y `null` un sábado.
- [ ] **Step 2:** `GRADLE :app:testDebugUnitTest --tests "app.brujula.claude.*" --tests "app.brujula.planning.*"` → FAIL.
- [ ] **Step 3: Implementar.** El parse comparte la lectura con `DictationCall.read`: extraer `readStructured(response: BetaMessage, type: Class<T>, what: String)` y usarla en ambos.
- [ ] **Step 4:** Suite PASS.
- [ ] **Step 5:** Commit `feat: Claude planning call with hand-written schema and day context`.

### Task 3: Validar el plan → Propuesta con vista previa, totales y hora de dormir

**Files:**
- Create: `planning/Proposal.kt`, `planning/PlanValidator.kt`, `planning/Bedtime.kt`, `claude/DayPlanner.kt`.
- Test: `planning/PlanValidatorTest.kt`, `planning/BedtimeTest.kt`, `claude/DayPlannerTest.kt`.

**Interfaces:**
- Consumes: `PlanContext`, `PlanItemRef`, `PlanResult` (Task 2) y `ActivityOrigin` (Task 1).
- Produces:
  - `sealed interface PlanOp`:
    - `data class Create(val activity: Activity /* origin PLAN, D11 */)`;
    - `data class Move(val ref: String, val activityId: Long, val occurrenceDay: LocalDate, val title: String, val from: TimeSpan, val to: TimeSpan, val oneOff: Boolean)`;
    - `data class Skip(val ref: String, val activityId: Long, val occurrenceDay: LocalDate, val title: String, val from: TimeSpan, val oneOff: Boolean)`.
  - `enum class BlockTag { NUEVO, MOVIDO, QUITADO, FIJO, LO_DICTASTE, NONE }`. `data class PreviewBlock(val title: String, val span: TimeSpan, val tag: BlockTag, val sub: String)`. Formatos de `sub`:
    - propio: `8:15 – 12:00`;
    - movido: `8:15 – 12:00 · antes 18:30`;
    - externo: `{source} · 15:00 – 15:30`;
    - almuerzo: `Almuerzo y pausa`, con `12:00 – 14:00`;
    - fila final `A dormir` con `NONE`: `Para dormir 8 h`.
  - `data class PlanTotals(val workMinutes: Int, val exerciseMinutes: Int, val sleepMinutes: Int, val bedtime: LocalDateTime)`.
  - `enum class ProposalStatus { PENDING, ACCEPTED, SUPERSEDED, DISCARDED }`.
  - `data class Proposal(val id: Long = 0, val day: LocalDate, val createdAt: LocalDateTime, val request: String?, val summary: String, val ops: List<PlanOp>, val rejected: List<String>, val preview: List<PreviewBlock>, val totals: PlanTotals, val status: ProposalStatus = ProposalStatus.PENDING)`.
  - `data class ValidatedPlan(val ops: List<PlanOp>, val rejected: List<String>)`. `object PlanValidator { const val MAX_CHANGES = 20; fun validate(result: PlanResult, ctx: PlanContext): ValidatedPlan; fun preview(ctx: PlanContext, ops: List<PlanOp>): List<PreviewBlock>; fun totals(ctx: PlanContext, ops: List<PlanOp>): PlanTotals }`.
  - `data class BedtimeSuggestion(val bedtime: LocalDateTime, val sleepMinutes: Int, val remindAt: LocalDateTime)`. `fun suggestBedtime(day: LocalDate, routine: Routine, nextDayFirstStart: LocalDateTime?, notBefore: LocalDateTime? = null): BedtimeSuggestion` (D12; `notBefore` corre la hora a ese instante redondeado hacia arriba a 15 min).
  - `sealed interface PlanOutcome { data class Ok(val proposal: Proposal); data object NoKey; data object InvalidKey; data object Offline; data class Refused(val message: String); data class Failed(val message: String, val retryable: Boolean) }`.
  - `class DayPlanner(gateway: ClaudeGateway, contextProvider: suspend (LocalDate) -> PlanContext, isOnline: suspend () -> Boolean = { true }) { suspend fun propose(day: LocalDate, request: String?, previous: Proposal? = null, adjustment: String? = null): PlanOutcome }`. Mapea `GatewayResult` igual que `DictationInterpreter`, con estas copias:
    - `OFFLINE = "Sin conexión. Claude necesita internet para armar tu día."`;
    - `NO_KEY = "Para que Claude arme tu día, agrega tu clave en Tu rutina."`;
    - `OUT_OF_RANGE = "Por ahora puedo armar hasta 6 días adelante."`.
- **Reglas de validación**, en el orden de Claude, sobre un estado en curso: lo propio + lo fijo + el almuerzo, actualizado tras cada cambio aceptado.
  - Se procesan como máximo `MAX_CHANGES`; el resto va a `rejected` como `Claude propuso demasiados cambios; apliqué los primeros 20.`.
  - `ref` debe existir en `ctx.items`. Un `E…` → `«{título}»: no toco eventos de tu calendario externo.`.
  - `move` y `skip` exigen `movable`.
  - `create` y `move`: inicio y fin dentro de `ctx.awake`; si el día es hoy, `start >= now`; duración de 5 a 720.
  - Sin solapes con lo fijo, el almuerzo ni otro bloque propio vigente (un `skip` libera su espacio).
  - `create` exige título de 1 a 80 caracteres y tipo `WORK|EXERCISE|PERSONAL` (`APPOINTMENT` → `Las citas se agendan dictándolas.`).
  - Cada rechazo es una frase legible con el título.
  - `summary` se recorta a 400 caracteres.

- [ ] **Step 1: Pruebas que fallan**
  - `PlanValidatorTest`:
    - `createWorkHasNoReminderAndPlanOrigin`;
    - `createExerciseReminds15`;
    - `appointmentRejected`;
    - `externalRefRejectedWithRuleCMessage`;
    - `unknownRefRejected`;
    - `moveToPastRejected` (hoy, 15:00 → 14:00);
    - `moveOutsideAwakeRejected`;
    - `moveOntoExternalRejected`;
    - `createInLunchRejectedOnWorkday` y `lunchFreeOnSaturday`;
    - `moveThenCreateInFreedSlotBothAccepted`;
    - `secondOverlappingCreateRejected`;
    - `skipDoneRejected`;
    - `skipRecurringIsNotOneOff`;
    - `onlyFirst20Changes`.
  - Vista previa `previewTagsAndOrder`: `NUEVO`, `MOVIDO` con `antes 18:30`, `FIJO` (externo y almuerzo), `LO_DICTASTE` (sin cambios, origen DICTATION), `QUITADO` y una sin etiqueta. Todo ordenado por hora, con la fila final `A dormir`.
  - Totales `totalsCountCreatesAndExternalWorkNotSkipped`.
  - `BedtimeTest` (D12):
    - jueves→viernes con rutina por defecto: 00:15, 480 min, aviso 23:45;
    - viernes→sábado: 00:30, 570 min;
    - viernes con evento a las 07:00: 22:30, 480 min;
    - `notBefore` 01:10 → 01:15.
  - `DayPlannerTest`:
    - día +7 → `Failed(OUT_OF_RANGE, false)` sin llamar al gateway;
    - offline → `Offline`;
    - `Ok` lleva ops, rechazos, preview y totales;
    - un ajuste envía los cambios previos y `<ajuste>` (el fake captura `lastPlanUser`);
    - refusal → `Refused`;
    - sin clave → `NoKey`.
- [ ] **Step 2:** FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4:** `GRADLE :app:testDebugUnitTest --tests "app.brujula.planning.*" --tests "app.brujula.claude.*"` → PASS.
- [ ] **Step 5:** Commit `feat: validate Claude day plans into proposals with preview and totals`.

### Task 4: Guardar, aceptar y deshacer propuestas (atómico)

**Files:**
- Create: `planning/ProposalCodec.kt`, `planning/ProposalRepository.kt`, `planning/ProposalExecutor.kt`, `claude/ClaudeNotes.kt`.
- Modify: `BrujulaApp.kt` (AppContainer: `proposalRepository`, `daySnapshots`, `planContext(day)`, `dayPlanner`, `proposalExecutor`, `claudeNotes`; la conexión con `ReminderSync` llega en Task 7).
- Test: `planning/ProposalCodecTest.kt`, `planning/ProposalRepositoryTest.kt`, `planning/ProposalExecutorTest.kt` (Robolectric con Room en memoria, como `ActionExecutorTest`), `claude/ClaudeNotesTest.kt`.

**Interfaces:**
- Consumes: `Proposal` y `PlanOp` (Task 3); overrides y `ProposalDao` (Task 1).
- Produces:
  - `object ProposalCodec`: `encode(p: Proposal): String`, `decode(payload: String): ProposalBody?` y `encodeUndo(u: UndoData)` / `decodeUndo(s): UndoData?`. Usa `org.json`, como `PendingDictations`: solo cadenas ISO y números, sin módulos de Jackson. Un payload dañado → `null`.
  - `data class UndoData(val createdIds: List<Long>, val oneOffBefore: List<Activity>, val oneOffAfter: List<Activity>, val deleted: List<Activity>, val overrideBefore: List<Pair<Pair<Long, LocalDate>, OccurrenceOverride?>>, val overrideAfter: List<OccurrenceOverride>)`.
  - `class ProposalRepository(dao: ProposalDao)`:
    - `suspend fun savePending(p: Proposal): Long`: en una transacción pasa a `SUPERSEDED` las `PENDING` del mismo día;
    - `suspend fun get(id: Long): Proposal?`: un payload ilegible → la marca `DISCARDED` y devuelve `null`;
    - `suspend fun pendingFor(day: LocalDate): Proposal?`;
    - `suspend fun discard(id: Long)`;
    - `fun observeAcceptedDays(): Flow<Set<LocalDate>>`;
    - `suspend fun acceptedDays(): Set<LocalDate>`;
    - `fun observeAcceptedIds(): Flow<Set<Long>>`.
  - `sealed interface AcceptResult { data class Accepted(val proposal: Proposal); data object AlreadyAccepted; data class Stale(val message: String); data class Expired(val message: String); data class Failed(val message: String) }`. Copias:
    - `STALE = "Tu agenda cambió desde esta propuesta."`;
    - `EXPIRED = "Ya pasó parte de esta propuesta."`;
    - `FAILED = "No pude aplicar el plan. No cambié nada."`.
  - `sealed interface UndoResult { data class Undone(val kept: Int); data object NotAccepted; data class Failed(val message: String) }`. Con `kept > 0` la copia es `Dejé {kept} cambio(s) que hiciste después.`.
  - `class ProposalExecutor(database: RoomDatabase, activityRepo, proposals: ProposalRepository, googleCopySync, reminderSync: ReminderResync, notes: ClaudeNotes, clock: Clock, syncScope: CoroutineScope) { suspend fun accept(id: Long): AcceptResult; suspend fun undo(id: Long): UndoResult; internal var afterApply: (suspend () -> Unit)? }`.
  - `data class ClaudeNote(val text: String, val day: LocalDate, val proposalId: Long?)`. `class ClaudeNotes(dataStore) { val note: Flow<ClaudeNote?>; suspend fun set(n: ClaudeNote) }`.
- **`accept`**, con `Mutex` y todo dentro de `database.withTransaction`:
  1. Relee la fila. Si no es `PENDING` → `AlreadyAccepted`.
  2. Expirada si `day < logicalDay(now)`, o si el día es hoy y algún `from.start` o `to.start` / inicio de un create ya pasó (`< now`).
  3. Desactualizada si alguna actividad de un `Move`/`Skip` no existe o su ocurrencia actual (`occurrencesOn` con su override) no coincide con `from`.
  4. Aplica según el tipo de cambio:
     - create → `upsert`;
     - move de una sola vez → `upsert` con la fecha y hora nuevas;
     - move recurrente → `setOverride(start, durationMin)`;
     - skip recurrente → `setOverride(skipped = true)`;
     - skip de una sola vez → `delete`.
  5. `afterApply?.invoke()`.
  6. `markAccepted(id, encodeUndo(...), now)`.

  Después del commit:
  - `NonCancellable` → `resync`;
  - `syncScope.launch` → `afterSave` de cada actividad de una sola vez movida con copia y `afterDelete(antes)` de cada una saltada con copia;
  - `notes.set(ClaudeNote(summary, day, id))`.

  Cualquier excepción dentro de la transacción → `Failed`, sin ningún cambio.
- **`undo`**, solo si está `ACCEPTED` y `day >= logicalDay(now)`; en una transacción y por cada elemento de `UndoData`, actúa solo si el estado actual es igual al "después" (si no, `kept++`):
  - borra los creados;
  - restaura las actividades de una sola vez a "antes", conservando el vínculo de Google;
  - restaura los overrides a "antes" (borra el override si antes no había);
  - vuelve a insertar las borradas con el mismo id y `googleEventId = null`.

  Luego estado `PENDING` y `undo = null`; después del commit, resync y `afterSave` de las restauradas o reinsertadas con copia. La nota pasa a `Deshice los cambios del plan.` con `proposalId = null`.

- [ ] **Step 1: Pruebas que fallan**
  - `ProposalCodecTest.roundTrip`, `damagedPayloadIsNull`.
  - `ProposalRepositoryTest.savePendingSupersedesSameDayOnly`, `unreadablePayloadIsDiscarded`.
  - `ProposalExecutorTest`:
    - `acceptAppliesAllOpsInOneTransaction`: un create, una de una sola vez movida con copia, una recurrente movida, una recurrente saltada y una de una sola vez saltada con copia. Resultado: `ACCEPTED`, un solo resync, `FakeGoogleCalendarWriter` con un update de la movida y un delete de la saltada, ninguna escritura a ids que no son de Brújula, y la nota con `proposalId`.
    - `failureMidTransactionChangesNothing` (`afterApply` lanza → todo igual, sigue `PENDING`, `Failed`).
    - `acceptRefusesStaleProposal` (actividad editada después).
    - `acceptRefusesPastOps` (hoy, cambio a las 14:00, reloj a las 14:05 → `Expired`).
    - `acceptRefusesPastDay`.
    - `concurrentAcceptAppliesOnce` (dos `accept` en paralelo → un `Accepted` y un `AlreadyAccepted`, un solo create).
    - `undoRestoresExactly`: creados borrados; hora de una sola vez restaurada con `afterSave` a la hora vieja; override previo restaurado y override nuevo borrado; la saltada reinsertada con el mismo id; estado `PENDING`.
    - `undoKeepsItemsEditedAfterAccept` (`Undone(kept = 1)`, ese elemento intacto).
    - `acceptUndoAcceptAgain`.
  - `ClaudeNotesTest.setAndRead`.
- [ ] **Step 2:** FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4:** `GRADLE :app:testDebugUnitTest --tests "app.brujula.planning.*" --tests "app.brujula.claude.*"` → PASS.
- [ ] **Step 5:** Commit `feat: accept and undo Claude proposals atomically`.

### Task 5: Pantalla Propuesta, enlaces desde notificaciones y tarjeta de Claude en Hoy

**Files:**
- Create: `planning/ProposalViewModel.kt`, `planning/ProposalScreen.kt` (según `Propuesta.dc.html`), `ui/DeepLinks.kt`.
- Modify:
  - `ui/BrujulaNavHost.kt`: ruta `proposal?day={day}&request={request}`, `Routes.proposal(day: LocalDate, request: String? = null)` y `pendingRoute: StateFlow<String?>` consumido al componer;
  - `ui/MainActivity.kt`: `onNewIntent` y entrega de la ruta del intent;
  - `AndroidManifest.xml`: `android:launchMode="singleTop"` en `MainActivity`;
  - `today/TodayViewModel.kt`, `today/TodayScreen.kt`, `today/TodayHeader.kt`: tarjeta;
  - `claude/ActionExecutor.kt`: `apply(items, reply)` escribe la nota;
  - `voice/VoiceViewModel.kt` y `claude/PendingDictations.kt`: pasan `outcome.reply`.
- Test:
  - `planning/ProposalViewModelTest.kt`, `ui/DeepLinksTest.kt`;
  - `today/TodayViewModelTest.kt`, `claude/ActionExecutorTest.kt`;
  - `voice/VoiceFakes.kt`: firma nueva de `FakeExecutor.apply`.

**Interfaces:**
- Consumes: `DayPlanner.propose`, `ProposalRepository`, `ProposalExecutor.accept/undo`, `ClaudeNotes` y `SpeechInput`.
- Produces:
  - `ActionApplier.apply(items: List<Validated>, reply: String = ""): ApplyResult`. Si creó algo, escribe `ClaudeNote(reply.ifBlank { "Agendé " + etiquetas.joinToString("; ") }, logicalDay(now), null)`. `ActionExecutor` recibe `notes: ClaudeNotes? = null` y `today: () -> LocalDate`.
  - `object DeepLinks { const val EXTRA_ROUTE = "app.brujula.route"; fun parse(raw: String?): String?; fun intent(context: Context, route: String): Intent }`.
    - Lista blanca: `today`, `voice`, `plan_tomorrow`, `proposal?day=…[&request=…]`, con `day` ISO válido y `request` de hasta 500 caracteres. Todo lo demás → `null`.
    - `DeepLinks.intent` crea el intent explícito a `MainActivity` con `FLAG_ACTIVITY_SINGLE_TOP | FLAG_ACTIVITY_CLEAR_TOP`.
  - `ProposalViewModel(day: LocalDate, request: String?, planner: DayPlanner, proposals: ProposalRepository, executor: ProposalExecutor, speech: SpeechInput, clock: Clock, wakeFor: suspend (LocalDate) -> LocalTime, bedtimeRemindAt: suspend () -> LocalDateTime? = { null }, scopeOverride: CoroutineScope? = null)`.
    - `bedtimeRemindAt` queda conectado en Task 7.
    - Estado `ProposalUiState`:
      - `Loading` (`Claude está armando tu día…`);
      - `Showing(proposal: Proposal, dayTitle: String /* "Tu jueves 19" */, stale: Boolean, message: String?, adjusting: AdjustState?)`;
      - `Accepted(title: String, body: String)`;
      - `Error(message: String, retry: Boolean)`;
      - `Closed`.
    - Funciones: `accept()`, `undo()` (`Volver a la propuesta`), `openAdjust()`, `adjustVoice()`, `adjust(text: String)`, `discard()`, `retry()` y `requestNew()`.
    - Al iniciar: sin `request` y con una `pendingFor(day)` que no está desactualizada, la muestra sin llamar a Claude. Si no, llama a `propose` y guarda con `savePending`.
    - `Accepted.title`: `Hoy está listo` / `Mañana está listo` / `Tu {jueves 19} está listo`.
    - `Accepted.body`: `Lo puse en tu agenda.`, más ` A las {wakeFor(day)} te llega el resumen.` si `day > hoy`, más ` y a las {h} el aviso para ir a dormir.` si `bedtimeRemindAt()` es futuro.
    - Sin cambios válidos: botón `Listo` y texto `No hace falta cambiar nada.`.
    - `rejected` se muestra bajo `No pude aplicar:`.
  - Tarjeta de Hoy:
    - `TodayViewModel(..., claudeNotes: Flow<ClaudeNote?> = flowOf(null), acceptedProposalIds: Flow<Set<Long>> = flowOf(emptySet()), undoPlan: suspend (Long) -> UndoResult = { UndoResult.NotAccepted })`.
    - `TodayUiState.claude: ClaudeCardState?` con `data class ClaudeCardState(val text: String?, val proposalId: Long?, val canUndo: Boolean)`. `text == null` significa estado vacío: `¿Te armo el día?` + `Que Claude arme mi día` → `Routes.proposal(hoy)`.
    - `fun undoPlan()` y el mensaje resultante.
  - Pantalla: botones `Ajustar` (secundario) y `Aceptar plan` (primario, con la animación de confirmación de los bocetos y respeto a "reducir movimiento"), más el enlace `Descartar propuesta`.
    - El panel de ajuste tiene título `¿Qué quieres cambiar?`, ejemplos `Pasa el gym a la tarde` y `Hoy no voy a entrenar`, el micrófono y el campo `Escribir el ajuste`.
    - Si la propuesta está desactualizada: `Tu agenda cambió desde esta propuesta.` + `Pedir una nueva`.

- [ ] **Step 1: Pruebas que fallan**
  - `ProposalViewModelTest`:
    - `showsStoredPendingWithoutCallingClaude`;
    - `pendingProposalSurvivesNewViewModel`;
    - `requestGeneratesAndSaves`;
    - `offlineShowsErrorWithRetry`;
    - `acceptShowsTomorrowConfirmation` (`Mañana está listo`, cuerpo con `A las 8:15 te llega el resumen.`);
    - `staleAcceptShowsBanner`;
    - `undoReturnsToProposal`;
    - `adjustSupersedesPrevious`;
    - `failedAdjustKeepsCurrent`;
    - `discardCloses`;
    - `doubleTapAcceptCallsExecutorOnce`.
  - `DeepLinksTest`:
    - `allowsKnownRoutes`;
    - `rejectsUnknownAndMalformed`;
    - `capsRequestAt500`.
  - `TodayViewModelTest`:
    - `cardShowsTodaysNote`;
    - `cardEmptyStateWhenNoteIsFromAnotherDay`;
    - `canUndoOnlyWhenAccepted`;
    - `undoPlanCallsExecutor`.
  - `ActionExecutorTest`:
    - `applyWritesNoteWithReply`;
    - `applyNoteFallsBackToLabels`.
- [ ] **Step 2:** FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4:** `GRADLE :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` → PASS.
- [ ] **Step 5:** Commit `feat: proposal screen, deep links and Claude card on Today`.

### Task 6: Cambios por voz como propuestas, cola sin conexión y formulario

**Files:**
- Modify:
  - `claude/DictationActions.kt` y `claude/Schemas.kt`: tipo `plan_request`, con las descripciones idénticas en ambos;
  - `claude/PromptBuilder.kt`: reglas;
  - `claude/ActionValidator.kt`, `claude/DictationInterpreter.kt`, `claude/ActionExecutor.kt`;
  - `claude/PendingDictations.kt`: `PendingNotifier.planRequested`;
  - `BrujulaApp.kt`: notificación con enlace;
  - `voice/VoiceViewModel.kt`, `voice/VoiceScreen.kt`;
  - `activities/form/ActivityFormViewModel.kt`, `activities/form/ActivityFormScreen.kt`;
  - `ui/BrujulaNavHost.kt`.
- Test: `claude/SchemasTest.kt`, `claude/PromptBuilderTest.kt`, `claude/ActionValidatorTest.kt`, `claude/DictationInterpreterTest.kt`, `voice/VoiceViewModelTest.kt`, `claude/PendingDictationsTest.kt`, `activities/form/ActivityFormViewModelTest.kt`.

**Interfaces:**
- Consumes: `Routes.proposal(day, request)` y `DeepLinks.intent` (Task 5).
- Produces:
  - `Validated.PlanRequest(val day: LocalDate, val text: String)`. `ActionValidator.validate(a, ctx, dictated: String = "")`:
    - sin `date` → día lógico de hoy;
    - fecha de hoy a hoy + 6 → `PlanRequest(day, dictated)`;
    - fuera de ese rango → `Unsupported("Por ahora puedo reorganizar hasta 6 días adelante.")`.

    `DictationInterpreter` pasa el texto limpio.
  - `PromptBuilder.system()`. La viñeta `unsupported` pierde mover, cambiar, cancelar y reorganizar. Aparece `plan_request`: "mover, cambiar, cancelar o saltar algo que ya existe, armar, acomodar o reorganizar un día ("arma mi día de mañana", "hoy no voy a entrenar", "pasa el gym a la tarde"); `date` = el día afectado". Consultar la agenda y lo que no es de agenda siguen siendo `unsupported`.
  - `ActionExecutor.apply` ignora `PlanRequest`: no crea nada ni da mensaje.
  - `VoiceState.OpenProposal(day: LocalDate, request: String)` cuando el resultado solo trae `PlanRequest`; en ese caso no se llama al executor. `VoiceState.Result` gana `proposal: Validated.PlanRequest?` (botón `Ver propuesta`). `VoiceScreen(..., onOpenProposal: (LocalDate, String) -> Unit)` navega a la propuesta quitando la pantalla de voz.
  - `PendingNotifier.planRequested(day: LocalDate, text: String) = Unit` (por defecto). En producción es `Notifications.showText`, con título `Claude puede reorganizar tu día`, texto `Toca para ver la propuesta: «{texto}»` y `contentIntent` = `DeepLinks.intent(Routes.proposal(day, texto))`. En `handleOk` un `PlanRequest` cuenta como trabajo atendido: sale de la cola sin `dropped`.
  - `ActivityFormViewModel.askClaudeRequest(): Pair<LocalDate, String>?`. Devuelve `null` sin choque, en edición, con `repeat != NONE` o con el día lógico fuera de hoy..hoy+6. Si no, devuelve `(díaLógico, "Agenda «{título}» ({tipo}, {duración}) el {día}. A las {hora} choca con {conflicto}; búscale otro horario.")`.
  - `ActivityFormScreen(..., onDictate: () -> Unit, onAskClaude: (LocalDate, String) -> Unit)`. Botón `Mejor dictarlo` arriba a la derecha (→ voz, quitando el formulario) y botón `Pedir a Claude otro horario` en la fila del choque.

- [ ] **Step 1: Pruebas que fallan**
  - `SchemasTest.dictationSchemaMatchesTheSdkDerivedOne`, con la descripción nueva.
  - `PromptBuilderTest.systemRoutesChangesToPlanRequest`.
  - `ActionValidatorTest`:
    - `planRequestTomorrow`;
    - `planRequestWithoutDateIsToday`;
    - `planRequestBeyondSixDaysUnsupported`;
    - `planRequestPastUnsupported`.
  - `DictationInterpreterTest.planRequestCarriesDictatedText`.
  - `VoiceViewModelTest`:
    - `planOnlyOpensProposalWithoutApplying`;
    - `mixedAppliesCreatesAndOffersProposal`.
  - `PendingDictationsTest`:
    - `planRequestNotifiesAndLeavesQueue`;
    - `mixedSchedulesAndNotifiesPlan`.
  - `ActivityFormViewModelTest`:
    - `askClaudeNullWithoutConflict`;
    - `askClaudeNullWhenEditingOrRepeating`;
    - `askClaudeRequestMentionsTitleConflictAndTime`.
- [ ] **Step 2:** FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4:** `GRADLE :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` → PASS.
- [ ] **Step 5:** Commit `feat: voice changes become proposals; ask Claude from the form`.

### Task 7: Planificación nocturna, resumen de la mañana y aviso para dormir

**Files:**
- Create: `planning/NightViewModel.kt`, `planning/NightScreen.kt` (según `ArmarManana.dc.html`), `planning/MorningSummary.kt`.
- Modify:
  - `routine/Routine.kt`: `nightNotice`;
  - `routine/RoutineRepository.kt`: clave `night_notice`, `BedtimeChoice`;
  - `settings/RoutineViewModel.kt`, `settings/RoutineScreen.kt`;
  - `reminders/Reminder.kt`, `reminders/ReminderPlanner.kt`, `reminders/ReminderSync.kt`, `reminders/ResyncTrigger.kt`;
  - `reminders/Notifications.kt`, `reminders/ReminderReceiver.kt`, `reminders/NotificationActionReceiver.kt`;
  - `planning/ProposalViewModel.kt`: `bedtimeRemindAt`;
  - `ui/BrujulaNavHost.kt`: `Routes.PLAN_TOMORROW = "plan_tomorrow"`;
  - `BrujulaApp.kt`.
- Test: `data/RoutineRepositoryTest.kt`, `settings/RoutineViewModelTest.kt`, `reminders/ReminderPlannerTest.kt`, `reminders/ReceiversTest.kt`, `planning/MorningSummaryTest.kt`, `planning/NightViewModelTest.kt`.

**Interfaces:**
- Consumes: `DaySnapshotProvider` (Task 2), `suggestBedtime` (Task 3), `ProposalRepository.acceptedDays()` (Task 4), `DeepLinks` (Task 5).
- Produces:
  - `Routine.nightNotice: LocalTime = LocalTime.of(22, 45)`. `RoutineViewModel.shiftNightNotice(min: Int): Job` limita a 18:00–23:45 y resincroniza.
    - En Tu rutina va la fila `Aviso para armar mañana` con `formatTime` y ±15 min, bajo los horarios de días laborales.
  - `data class BedtimeChoice(val day: LocalDate, val bedtime: LocalDateTime, val sleepMinutes: Int, val remind: Boolean)`. Su `remindAt` es `bedtime − 30 min`. `RoutineRepository.bedtimeChoice: Flow<BedtimeChoice?>` y `suspend fun setBedtimeChoice(c: BedtimeChoice)`.
  - `ReminderKind.PLAN_NIGHT, PLAN_MORNING, BEDTIME`. `ReminderPlanner.plan(..., plannedDays: Set<LocalDate> = emptySet(), bedtime: BedtimeChoice? = null)`. Por cada día de la ventana (−1..+1):
    - **Noche:** clave `night:{yyyyMMdd}`. Hora `nightNotice`; si no cae en la vigilia del día, `awakeWindow(día).end − 30 min`. Se omite si `día+1 ∈ plannedDays`. Título `¿Armamos mañana?`, cuerpo `Mira cómo te fue hoy y deja listo tu día de mañana.`.
    - **Mañana:** clave `morning:{yyyyMMdd}`, a la hora `wakeFor(día)`, solo si `día ∈ plannedDays`. Título `Tu día está listo`, cuerpo de respaldo `Toca para ver tu día.`.
    - **Dormir:** clave `bed:{yyyyMMddHHmm}`, a `remindAt`, si `remind` y la hora es futura. Título `Hora de ir a dormir`, cuerpo `Para dormir {formatMinutes(sleep)}, acuéstate a las {formatTime(bedtime)}.`.
  - `ReminderSync` recibe `plannedDays: suspend () -> Set<LocalDate> = { emptySet() }` y `bedtime: suspend () -> BedtimeChoice? = { null }`. AppContainer los conecta con `proposalRepository.acceptedDays()` y `routineRepository.bedtimeChoice.first()`. `reminderChanges` también observa `observeAcceptedDays()` y `bedtimeChoice`.
  - `Notifications.CHANNEL_PLANNING = "planificacion"` ("Planificación", `IMPORTANCE_DEFAULT`, descripción `Aviso nocturno, resumen de la mañana y hora de dormir`). Los tres tipos van a ese canal, sin `Hecho` ni `En 15 min`.
    - Noche: `contentIntent` = `DeepLinks.intent(Routes.PLAN_TOMORROW)` y acción `Mañana lo veo` (`ACTION_LATER`, solo cierra).
    - Mañana: `contentIntent` → `today`, con las acciones `Ver mi día` (PendingIntent de actividad → `today`) y `Ajustar` (→ `voice`).
    - Dormir: abre la app.
  - `ReminderReceiver`: con `PLAN_MORNING` arma el cuerpo dentro de `launchAsync`, con `morningBody(container.daySnapshots.snapshot(day))`, y muestra la notificación; si falla, usa el cuerpo de respaldo. Los demás tipos se siguen mostrando de forma síncrona.
  - `fun morningBody(s: DaySnapshot): String` (D14). Formato: `{formatMinutes(trabajo)} de trabajo` (se omite en un día de descanso con 0 min) · los dos primeros bloques no `WORK` por hora como `{título} a las {formatTime}`, unidos con ` · `. Ejemplo: `6 h 30 de trabajo · Llamada con cliente a las 15:00 · Ejercicio en casa a las 19:00`.
  - `NightViewModel(clock, snapshots: DaySnapshotProvider, routineRepository, resync: ReminderResync, scopeOverride: CoroutineScope? = null)`.
    - `NightUiState(timeLabel: String /* "10:45 pm · Buenas noches" */, today: DaySummary, tomorrow: List<NightItem>, bedtime: BedtimeSuggestion, reminderOn: Boolean, reminderVisible: Boolean)`.
    - `NightItem(title, timeLabel /* "15:00" */, sourceLabel /* source externo · "lo dictaste" · tipo */)`.
    - `fun setReminder(on: Boolean)`.
    - Saludo según la hora: `Buenas noches` de 19:00 a 03:59, `Buenas tardes` de 12:00 a 18:59, `Buenos días` el resto.
    - La primera vez que se abre en un día lógico guarda `BedtimeChoice(remind = true)` (interruptor encendido por defecto, como el boceto) y resincroniza.
    - Si `remindAt` ya pasó, el interruptor se oculta.
  - `NightScreen`. Tarjetas:
    - `Así te fue hoy`: `{h} de trabajo`, `{x} / {y}` con `vasos de agua`, y el ejercicio `Hecho` / `Pendiente` / `No tocaba`;
    - `Mañana ya tienes` (vacío: `Mañana aún no tienes nada.`);
    - `Para dormir {h}, acuéstate a las {hora}` con el interruptor `Avisarme a las {remindAt}` (descripción accesible `Aviso para dormir`).

    Botones: `Que Claude arme mi día` → `Routes.proposal(mañana)`; `Agregar algo` → voz; `Mañana lo veo` → cerrar.

- [ ] **Step 1: Pruebas que fallan**
  - `RoutineRepositoryTest`:
    - `nightNoticeDefaultsTo2245AndPersists`;
    - `bedtimeChoiceRoundTrip`.
  - `RoutineViewModelTest.shiftNightNoticeClampsAndResyncs`.
  - `ReminderPlannerTest`:
    - `nightNoticeAt2245`;
    - `nightNoticeSkippedWhenTomorrowPlanned`;
    - `nightNoticeMovesInsideAwakeWindow` (acostarse 22:00 → 21:30);
    - `morningAtWakeOnlyForPlannedDays` (sábado → 10:00);
    - `bedtimeReminderOnlyWhenOnAndFuture`;
    - `bedtimeAfterMidnightKeepsLogicalDay` (dormir 00:15 → aviso 23:45 del día anterior en el calendario).
  - `ReceiversTest`:
    - `morningNotificationBuildsBodyFromSnapshot`;
    - `laterActionOnlyCancels`;
    - `planningKindsUsePlanningChannel`.
  - `MorningSummaryTest`:
    - `workdayBody`;
    - `restDayWithoutWorkOmitsWork`.
  - `NightViewModelTest`:
    - `stateMatchesMockupCopy` (`10:45 pm · Buenas noches`, fuentes `Outlook` / `lo dictaste`, dormir `0:15`, `Avisarme a las 23:45`);
    - `firstOpenTurnsReminderOn`;
    - `toggleOffPersists`;
    - `pastReminderHidesToggle`.
  - `ProposalViewModelTest.acceptBodyMentionsBedtimeWhenReminderOn`.
- [ ] **Step 2:** FAIL.
- [ ] **Step 3: Implementar.**
- [ ] **Step 4:** `GRADLE :app:testDebugUnitTest :app:assembleDebug :app:lintDebug` → PASS.
- [ ] **Step 5:** Commit `feat: nightly planning screen, morning summary and bedtime reminder`.

### Task 8: Verificación en el teléfono

- [ ] **Step 1: Respaldo antes de instalar.** `adb exec-out run-as app.brujula tar -cf - databases files shared_prefs > .superpowers/backups/fase3a-antes.tar`. Verificar que el archivo pesa más de 0 bytes.
- [ ] **Step 2: Compilar e instalar.** `GRADLE :app:testDebugUnitTest :app:lintDebug :app:installDebug` → verde e instalado como actualización. La migración 4→5 conserva actividades, hábitos y registros: Hoy se ve igual que antes.
- [ ] **Step 3: Propuesta desde Hoy.** Tocar `Que Claude arme mi día`. Llega la propuesta en menos de 90 s, con totales, explicación y etiquetas, y los eventos de Outlook/Google aparecen como `Fijo`. `Aceptar plan` → Hoy cambia. `Volver a la propuesta` deshace exactamente lo aplicado (comparar con una captura previa). Aceptar de nuevo.
- [ ] **Step 4: Cambios por voz.** Dictar "hoy no voy a entrenar" → propuesta con el ejercicio `Quitado`; aceptar → el ejercicio de mañana sigue en su lugar. Dictar "pasa el gym a la tarde" → `Movido`; aceptar → el aviso del ejercicio llega a la hora nueva. Probar `Ajustar` con la voz.
- [ ] **Step 5: Choque en el formulario.** Crear a mano algo que choca → `Pedir a Claude otro horario` → propuesta con un bloque `Nuevo` sin choque. Probar también `Mejor dictarlo`.
- [ ] **Step 6: Aviso nocturno.** En Tu rutina, poner `Aviso para armar mañana` a 2 minutos de la hora actual → llega `¿Armamos mañana?`; tocarlo abre la pantalla con el resumen real de hoy y lo de mañana. Probar el interruptor de dormir y `Que Claude arme mi día` → aceptar. Comprobar con `adb shell dumpsys alarm | findstr brujula` que quedan programados el aviso para dormir y el resumen de la mañana. Al final, devolver el aviso a 22:45.
- [ ] **Step 7: Sin conexión y regla C.** En modo avión, `Que Claude arme mi día` muestra `Sin conexión. Claude necesita internet para armar tu día.` y nada se pierde. En Google Calendar, ningún evento externo cambió.
- [ ] **Step 8:** Commit de ajustes si los hubo.

---

## Fuera de alcance (Fase 3B)

- La pantalla Semana (§5.6).
- El widget (§5.7).
- Las sugerencias de hábitos de Claude (§5.5, última viñeta).
- El sueño real ajustado al confirmar el aviso de "a dormir" (§10).
- Mover o saltar ocurrencias de recurrentes con copia a Google (D4).
- Editar series completas desde una propuesta.
- `Pedir a Claude otro horario` al editar o con repetición.
- Planificar más allá de hoy + 6.

## Decisiones para confirmar con el usuario

1. **Recurrentes (D3/D4):** mover o saltar afecta solo ese día y la serie no se toca. Una recurrente que se copia a Google queda fija en 3A. *Recomiendo así.*
2. **Hora para dormir (D12):** la más temprana entre tu hora de acostarte y 8 h antes de levantarte al día siguiente (más temprano si mañana hay algo muy temprano). Aviso 30 min antes, con el interruptor encendido por defecto. *Recomiendo así.*
3. **Resumen de la mañana (D14):** solo los días que armaste con Claude, a tu hora de levantarte. *Recomiendo así (un resumen diario sin plan sería ruido).*
4. **Almuerzo como Fijo (D7):** el hueco entre tus bloques de trabajo (12:00–14:00) se muestra como `Almuerzo y pausa` y Claude no pone nada ahí en días laborales. *Recomiendo así.*
5. **Bloques de trabajo sin aviso (D11):** los bloques de trabajo que crea un plan no notifican; ejercicio y personales avisan 15 min antes. *Recomiendo así, para no llenarte de avisos.*
