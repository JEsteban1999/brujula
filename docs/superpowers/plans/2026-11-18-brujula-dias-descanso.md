# Brújula · Días laborales, de descanso y festivos — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que la app distinga días laborales de días de descanso (sábado, domingo y festivos de Colombia): horario propio, sin meta de trabajo, ejercicio opcional con un aviso que pregunta, y hábitos que se conservan.

**Architecture:** Un módulo puro `routine/Holidays.kt` calcula los festivos colombianos de cualquier año y el tipo de día. `Routine` gana horas de descanso y `awakeWindow(day)` pasa a usar las horas del tipo de día, así que hábitos, planificador, Hoy y tema heredan el cambio sin tocar sus firmas. Un nuevo valor de repetición `WORKDAYS` deja el ejercicio fijo solo en días laborales. El planificador agrega un aviso de ejercicio en días de descanso con acciones "Sí, agéndalo" / "Hoy no".

**Tech Stack:** el mismo de la Fase 1 (Kotlin, Compose, Room, DataStore, AlarmManager, JUnit 4, Robolectric, Turbine).

**Spec:** `docs/superpowers/specs/2026-11-18-brujula-design.md` §11 (leer también §4, §5.1, §5.5 y §5.8).

## Global Constraints

- Todo lo de la Fase 1 sigue vigente (paquete `app.brujula`, minSdk 31, español, 24 h, colores y fuentes, día lógico 04:00, recordatorios solo en vigilia, respetar "reducir movimiento").
- Descanso = sábado, domingo o festivo de Colombia, según el **día lógico**.
- Horario de descanso por defecto: levantarse `10:00`, acostarse `01:00`.
- Festivos (18 por año): fijos 1 ene, 1 may, 20 jul, 7 ago, 8 dic, 25 dic; trasladables al lunes siguiente si no caen en lunes: 6 ene, 19 mar, 29 jun, 15 ago, 12 oct, 1 nov, 11 nov; según Pascua P: P−3, P−2 (sin traslado), lunes P+43, P+64, P+71.
- Nombres en español: Año Nuevo, Reyes Magos, San José, Jueves Santo, Viernes Santo, Día del Trabajo, Ascensión del Señor, Corpus Christi, Sagrado Corazón, San Pedro y San Pablo, Día de la Independencia, Batalla de Boyacá, Asunción de la Virgen, Día de la Raza, Todos los Santos, Independencia de Cartagena, Inmaculada Concepción, Navidad.
- Copia: etiqueta `Descanso` / `Festivo · {nombre}`; aviso `¿Te animas a hacer ejercicio hoy?` con acciones `Sí, agéndalo` y `Hoy no`; sección de ajustes `Fines de semana y festivos`.
- Commits con el trailer de dos líneas (`Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` / `Claude-Session: https://claude.ai/code/session_01XN8G1c95yQpddiVFPfCNKU`).

## Review Focus

1. **Festivo entre semana:** un martes festivo es descanso. No muestra meta, no pone el ejercicio de las 18:30 y sí ofrece el aviso de ejercicio. → Task 1, Task 3, Task 4.
2. **Viernes noche → sábado:** a las 00:30 del sábado sigue siendo el viernes lógico, así que el agua de las 23:15 del viernes suena y la vigilia del sábado empieza a las 10:00. → Task 2.
3. **Bálsamo 09:00 en un día de descanso con levantarse a las 10:00:** se corre a las 10:00, no se pierde ni se duplica; si ya hay una hora a las 10:00, queda una sola. → Task 2.
4. **Racha de ejercicio con un fin de semana sin ejercicio:** no se rompe; con ejercicio el sábado, suma. → Task 3.
5. **Teléfono ya sembrado con la Fase 1:** el "Ejercicio en casa" diario existente pasa a días laborales una sola vez, sin tocar actividades que el usuario creó o editó. → Task 3.

---

### Task 1: Festivos de Colombia y tipo de día

**Files:**
- Create: `app/src/main/java/app/brujula/routine/Holidays.kt`
- Test: `app/src/test/java/app/brujula/routine/HolidaysTest.kt`

**Interfaces:**
- Produces:
  - `fun easterSunday(year: Int): LocalDate` (algoritmo gregoriano anónimo).
  - `fun colombianHolidays(year: Int): Map<LocalDate, String>` (18 entradas, nombres de Global Constraints; si dos coinciden en fecha, conservar ambos nombres unidos con " y ").
  - `enum class DayKind { WORKDAY, WEEKEND, HOLIDAY }`, `data class DayType(val kind: DayKind, val holidayName: String? = null) { val isRest: Boolean get() = kind != DayKind.WORKDAY }`
  - `fun dayTypeOf(day: LocalDate): DayType` (festivo gana sobre fin de semana; con caché por año).

- [ ] **Step 1: Pruebas que fallan**

```kotlin
class HolidaysTest {
    @Test fun easter() {
        assertEquals(LocalDate.of(2025, 4, 20), easterSunday(2025))
        assertEquals(LocalDate.of(2026, 4, 5), easterSunday(2026))
        assertEquals(LocalDate.of(2027, 3, 28), easterSunday(2027))
    }
    @Test fun holidays2026() {
        val h = colombianHolidays(2026)
        assertEquals(18, h.size)
        val expected = listOf("01-01","01-12","03-23","04-02","04-03","05-01","05-18","06-08","06-15",
            "06-29","07-20","08-07","08-17","10-12","11-02","11-16","12-08","12-25")
            .map { LocalDate.parse("2026-$it") }
        assertEquals(expected.toSet(), h.keys)
        assertEquals("Reyes Magos", h[LocalDate.of(2026, 1, 12)])
        assertEquals("Día de la Independencia", h[LocalDate.of(2026, 7, 20)])
    }
    @Test fun dayTypes() {
        assertEquals(DayKind.WORKDAY, dayTypeOf(LocalDate.of(2026, 11, 18)).kind)        // miércoles
        assertEquals(DayKind.WEEKEND, dayTypeOf(LocalDate.of(2026, 11, 21)).kind)        // sábado
        val tue = dayTypeOf(LocalDate.of(2026, 12, 8))                                    // martes festivo
        assertEquals(DayKind.HOLIDAY, tue.kind); assertEquals("Inmaculada Concepción", tue.holidayName); assertTrue(tue.isRest)
        assertEquals(DayKind.HOLIDAY, dayTypeOf(LocalDate.of(2026, 11, 1).plusDays(1)).kind) // lunes trasladado
    }
}
```

- [ ] **Step 2:** `.\gradlew.bat :app:testDebugUnitTest --tests "app.brujula.routine.HolidaysTest"` → FAIL (no compila).
- [ ] **Step 3:** Implementar `Holidays.kt`.
- [ ] **Step 4:** Mismo comando → PASS.
- [ ] **Step 5:** Commit `feat: Colombian holidays and day type`.

---

### Task 2: Horario de descanso en la rutina y la vigilia

**Files:**
- Modify: `routine/Routine.kt`, `routine/RoutineRepository.kt`, `habits/HabitTimes.kt`, `core/theme/ThemeState.kt`
- Test: `routine/RoutineTest.kt`, `routine/RoutineRepositoryTest.kt` (o el archivo de pruebas existente del repositorio), `habits/HabitTimesTest.kt`, `core/theme/ThemeStateTest.kt`

**Interfaces:**
- Consumes: `dayTypeOf` (Task 1).
- Produces:
  - `Routine` gana `val restWake: LocalTime = LocalTime.of(10, 0)` y `val restBed: LocalTime = LocalTime.of(1, 0)`.
  - `fun Routine.wakeFor(day: LocalDate): LocalTime`, `fun Routine.bedFor(day: LocalDate): LocalTime` (descanso → rest*, laboral → wake/bed).
  - `Routine.awakeWindow(day)` usa `wakeFor(day)`/`bedFor(day)` (firma igual). `isAwake` sigue usando las ventanas de `day` y `day-1`.
  - `RoutineRepository` persiste `restWake`/`restBed` (claves nuevas, con valor por defecto si faltan o están dañadas).
  - `timesFor` (`AtTimes`): con `w = wakeFor(day)` y `b = bedFor(day)`, una hora `t` se ubica así: si la vigilia cruza la medianoche y `t < b` → `day+1` a las `t`; si no, si `t < w` → `day` a las `w`; si no → `day` a las `t`. Luego filtrar por la ventana, quitar duplicados y ordenar.
  - Tema AUTO: `resolveDark(mode, now, wake = r.wakeFor(logicalDay(clock.now())))`.

- [ ] **Step 1: Pruebas que fallan**
  - `RoutineTest`: sábado 21 nov 2026 → ventana `10:00 → 01:00 (dom)`; miércoles igual que antes; viernes 20 nov a las 00:30 del sábado → `isAwake` verdadero (vigilia del viernes hasta 00:30); sábado 08:00 → falso; martes festivo 8 dic → ventana de descanso.
  - `HabitTimesTest`: bálsamo `AtTimes(09:00,13:00,17:00,21:00)` el sábado → `[10:00,13:00,17:00,21:00]`; con `AtTimes(09:00,10:00)` el sábado → `[10:00]` una sola vez; agua `Every(90)` el sábado: primera `11:30`, última `23:30` (la siguiente sería 01:00, que no es `< 01:00`), 9 avisos; el miércoles sigue `09:45 … 23:15`.
  - `RoutineRepositoryTest`: `restWake/restBed` por defecto `10:00/01:00`; `update` los persiste.
  - `ThemeStateTest`: AUTO el sábado a las 09:30 → oscuro (aún no se levanta), a las 10:00 → claro.
- [ ] **Step 2:** Ejecutar esas clases → FAIL.
- [ ] **Step 3:** Implementar.
- [ ] **Step 4:** Toda la suite → PASS (las pruebas existentes usan fechas miércoles 18 nov; si alguna usa un fin de semana, ajustar su fecha a un día laboral sin cambiar su intención y anotarlo en el reporte).
- [ ] **Step 5:** Commit `feat: rest-day hours drive the awake window`.

---

### Task 3: Ejercicio fijo solo en días laborales, migración y racha

**Files:**
- Modify: `activities/Activity.kt` (`Repeat`), `data/db/Seed.kt`, `BrujulaApp.kt` (migración al arrancar), `routine/RoutineRepository.kt` (bandera de migración), `habits/Streaks.kt`, `habits/HabitsViewModel.kt`, `habits/HabitsScreen.kt`, `activities/form/ActivityFormScreen.kt` (la opción nueva aparece en "Repetir" por estar en el enum; verificar el texto)
- Test: `activities/ActivityTest.kt`, `data/SeedTest.kt`, `habits/StreaksTest.kt`, `habits/HabitsViewModelTest.kt`

**Interfaces:**
- Consumes: `dayTypeOf`.
- Produces:
  - `Repeat.WORKDAYS("Días laborales")`: ocurre cuando `!dayTypeOf(day).isRest` (y nunca antes de `date`).
  - `Seed` crea "Ejercicio en casa" con `Repeat.WORKDAYS`.
  - `object Migrations { suspend fun exerciseToWorkdays(db: BrujulaDatabase, routine: RoutineRepository) }`: una sola vez (bandera `exercise_workdays_migrated` en DataStore), cambia a `WORKDAYS` solo la actividad con título exacto `Ejercicio en casa`, tipo `EXERCISE`, `Repeat.DAILY`, inicio `18:30` y `30` min; nada más. Se llama en `startBackgroundWork()` después de `Seed`.
  - `fun exerciseStreak(today: LocalDate, doneDays: Set<LocalDate>, isRest: (LocalDate) -> Boolean = { dayTypeOf(it).isRest }): Int`: hacia atrás desde hoy (o ayer si hoy no está hecho): día hecho → suma; descanso sin hacer → se salta sin romper; laboral sin hacer → corta. Máximo 366 días de búsqueda.
  - `HabitsUiState` gana `val weekRest: List<Boolean>` (lunes→domingo); la pantalla dibuja los días de descanso no hechos con un círculo punteado en vez de vacío.

- [ ] **Step 1: Pruebas que fallan**
  - `ActivityTest`: `WORKDAYS` ocurre el miércoles 18 nov, no el sábado 21 nov, no el martes 8 dic (festivo).
  - `SeedTest`: la actividad sembrada tiene `WORKDAYS`; `Migrations.exerciseToWorkdays` cambia la sembrada DAILY de Fase 1, deja intacta una "Ejercicio en casa" con otra hora, y no hace nada la segunda vez aunque el usuario la vuelva a poner en DAILY.
  - `StreaksTest`: hechos lun 16–vie 20 nov, sábado y domingo no, consulta el lunes 23 sin hacer → 5; con sábado hecho → 6; hechos solo lun 16–jue 19 (viernes 20 sin hacer, fin de semana sin hacer), consulta el lunes 23 sin hacer → 0.
  - `HabitsViewModelTest`: `weekRest` del 16–22 nov = `[f,f,f,f,f,t,t]`.
- [ ] **Step 2:** FAIL. **Step 3:** Implementar. **Step 4:** Suite → PASS.
- [ ] **Step 5:** Commit `feat: workday-only exercise, one-time migration and rest-aware streak`.

---

### Task 4: Aviso de ejercicio en días de descanso

**Files:**
- Create: `activities/FreeSlots.kt` (mover aquí `freeSlots` desde `today/TodayViewModel.kt`, que pasa a usarlo)
- Modify: `reminders/Reminder.kt`, `reminders/ReminderPlanner.kt`, `reminders/ReminderSync.kt`, `reminders/Notifications.kt`, `reminders/NotificationActionReceiver.kt`, `routine/RoutineRepository.kt` (días respondidos), `today/TodayViewModel.kt`
- Test: `reminders/ReminderPlannerTest.kt`, `reminders/ReceiversTest.kt`, `activities/FreeSlotsTest.kt`

**Interfaces:**
- Consumes: `dayTypeOf`, `Routine.wakeFor`, `occurrencesOn`, `findConflict`.
- Produces:
  - `fun freeSlots(window: TimeSpan, busy: List<TimeSpan>, minMinutes: Long = 30): List<TimeSpan>` (comportamiento actual de Hoy, ahora público y compartido).
  - `ReminderKind.EXERCISE_PROMPT`; clave `"exprompt:{yyyyMMdd}"`.
  - `ReminderPlanner.plan(..., exercisePromptAnswered: Set<LocalDate> = emptySet())`: para cada día del horizonte que sea descanso, si no hay una ocurrencia de tipo `EXERCISE` ese día y el día no está en `exercisePromptAnswered`, planifica un aviso a `wakeFor(day) + 2 h` (si cae dentro de la vigilia y en el futuro).
  - `RoutineRepository`: `val exercisePromptAnswered: Flow<Set<LocalDate>>`, `suspend fun markExercisePromptAnswered(day: LocalDate)` (guarda solo los últimos 14 días).
  - Notificación: título `¿Te animas a hacer ejercicio hoy?`, cuerpo `Si te sientes con ganas, te busco un hueco de 30 min.`, acciones `Sí, agéndalo` (`ACTION_EXERCISE_YES`) y `Hoy no` (`ACTION_EXERCISE_NO`). Canal `actividades`.
  - `ACTION_EXERCISE_YES`: busca el primer hueco libre de ≥ 30 min en la vigilia del día desde `ahora` redondeado al siguiente cuarto de hora (usando `occurrencesOn` de día−1, día y día+1 como ocupados), crea `Activity("Ejercicio en casa", EXERCISE, …, 30 min, Repeat.NONE, remindBeforeMin = 15)`, marca el día como respondido y resincroniza. Sin hueco: marca respondido y muestra una notificación `No encontré un hueco libre hoy para ejercicio.`
  - `ACTION_EXERCISE_NO`: marca el día como respondido y resincroniza.

- [ ] **Step 1: Pruebas que fallan**
  - `ReminderPlannerTest`: sábado 21 nov a las 09:00 → hay `exprompt:20261121` a las 12:00; el miércoles no; el sábado con una actividad EXERCISE ese día → no; con el día en `exercisePromptAnswered` → no; martes festivo 8 dic → sí.
  - `ReceiversTest`: `YES` a las 12:00 del sábado con ocupado 12:00–13:00 → crea ejercicio a las 13:00 de 30 min y el día queda respondido; `NO` → solo marca respondido; `YES` sin huecos → no crea nada, marca respondido, muestra el aviso de "No encontré".
  - `FreeSlotsTest`: los casos que hoy cubre Hoy siguen iguales tras moverlo.
- [ ] **Step 2:** FAIL. **Step 3:** Implementar (ReminderSync lee `exercisePromptAnswered`). **Step 4:** Suite → PASS.
- [ ] **Step 5:** Commit `feat: rest-day exercise prompt with schedule/skip actions`.

---

### Task 5: Hoy y Tu rutina muestran los días de descanso

**Files:**
- Modify: `today/TodayViewModel.kt`, `today/TodayHeader.kt`, `settings/RoutineViewModel.kt`, `settings/RoutineScreen.kt`
- Test: `today/TodayViewModelTest.kt`, `settings/RoutineViewModelTest.kt`

**Interfaces:**
- Consumes: `dayTypeOf`, `Routine.restWake/restBed`.
- Produces:
  - `TodayUiState` gana `val dayType: DayType`; `workGoal: IntRange?` y `workStatus: WorkStatus?` son `null` en descanso.
  - Encabezado: etiqueta junto a la fecha `Descanso` o `Festivo · {nombre}` (píldora con fondo `soft2`, texto `accent2Text`); en descanso, en vez de `x / 6–7 h` muestra `Trabajo hoy` con los minutos solo si son > 0, sin barra.
  - `RoutineViewModel`: `fun shiftRestWake(min: Int)`, `fun shiftRestBed(min: Int)` (con resincronización, igual que `shiftWake`/`shiftBed`).
  - `RoutineScreen`: sección `Fines de semana y festivos` con `Me levanto` / `Me acuesto` (mismos `StepperRow` de `ui/Controls.kt`) y el texto `Sin meta de trabajo. El ejercicio es opcional: te pregunto si te animas.`

- [ ] **Step 1: Pruebas que fallan**
  - `TodayViewModelTest`: sábado 21 nov 11:00 → `dayType.kind == WEEKEND`, `workGoal == null`, el timeline no tiene el ejercicio de las 18:30, los huecos libres empiezan a las 10:00; martes 8 dic → `holidayName == "Inmaculada Concepción"`; miércoles sin cambios.
  - `RoutineViewModelTest`: `shiftRestWake(+15)` → `10:15` persiste y resincroniza; `shiftRestBed(-15)` desde `01:00` → `00:45`.
- [ ] **Step 2:** FAIL. **Step 3:** Implementar. **Step 4:** Suite + `:app:assembleDebug :app:lintDebug` → PASS, 0 errores de lint.
- [ ] **Step 5:** Commit `feat: rest-day label on Today and weekend/holiday settings`.

---

### Task 6: Modelo de bienestar — grupos, ventana de trabajo, "antes de dormir" y migración de base de datos

(Spec §12. Se ejecuta después de la Task 5.)

**Files:**
- Modify: `habits/Habit.kt`, `habits/HabitTimes.kt`, `data/db/Entities.kt`, `data/db/Converters.kt`, `data/db/BrujulaDatabase.kt`, `habits/HabitRepository.kt`, `app/build.gradle.kts` (room-testing para la prueba de migración si falta)
- Create: `app/schemas/app.brujula.data.db.BrujulaDatabase/2.json` (generado)
- Test: `habits/HabitTimesTest.kt`, `data/HabitRepositoryTest.kt`, `data/MigrationTest.kt`

**Interfaces:**
- Consumes: `Routine.wakeFor/bedFor/awakeWindow`, `dayTypeOf` (Tasks 1–2).
- Produces:
  - `enum class HabitGroup { ESSENTIAL, WELLNESS }`, `enum class HabitWindow { AWAKE, WORK }`.
  - `Habit` gana `val group: HabitGroup = HabitGroup.ESSENTIAL` y `val window: HabitWindow = HabitWindow.AWAKE`.
  - `HabitSchedule.BeforeBed(val minutes: Int)`; codificación `"bed:45"`.
  - `HabitIcon` gana `EYE, WALK, POSTURE, FOOD, SCREEN_OFF, PILL, SUN, BOOK` (el `STRETCH` ya existe).
  - `timesFor(day, routine)`: con `WORK`, en días de descanso → vacío; en laborales, `Every(n)` genera desde el inicio de cada bloque de trabajo + n mientras sea `< fin del bloque` (cada bloque por separado), y `AtTimes` conserva solo las horas dentro de algún bloque. `BeforeBed(m)` → una hora: `fin de la vigilia − m`, si cae dentro de la vigilia.
  - Room `version = 2` con `AutoMigration(from = 1, to = 2)`; columnas nuevas `groupName` y `window` con `@ColumnInfo(defaultValue = "ESSENTIAL")` / `"AWAKE"`. Los hábitos existentes quedan como básicos y "despierto".

- [ ] **Step 1: Pruebas que fallan**
  - `HabitTimesTest`: `Every(30)` WORK el miércoles → 08:30…11:30 y 14:30…17:30 (14 horas: 7 + 7); el sábado → vacío; `Every(60)` WORK → 09:00,10:00,11:00,15:00,16:00,17:00; `AtTimes(10:15,16:15)` WORK miércoles → ambas, sábado → vacío; `BeforeBed(45)` miércoles → 23:45, sábado → 00:15 del domingo.
  - `HabitRepositoryTest`: `group`, `window` y `BeforeBed` sobreviven ida y vuelta.
  - `MigrationTest` (MigrationTestHelper con el esquema 1 exportado): una base v1 con agua y bálsamo migra a v2 y los hábitos quedan `ESSENTIAL`/`AWAKE` con sus registros intactos.
- [ ] **Step 2:** FAIL. **Step 3:** Implementar (generar y commitear `2.json`). **Step 4:** Suite → PASS.
- [ ] **Step 5:** Commit `feat: wellness habit groups, work-hours window and before-bed schedule`.

---

### Task 7: Los nueve hábitos de bienestar y sus avisos agrupados

**Files:**
- Modify: `data/db/Seed.kt` (o un `Migrations.seedWellness`), `BrujulaApp.kt`, `routine/RoutineRepository.kt` (bandera), `reminders/Reminder.kt`, `reminders/ReminderPlanner.kt`, `reminders/Notifications.kt`, `reminders/NotificationActionReceiver.kt`
- Test: `data/SeedTest.kt`, `reminders/ReminderPlannerTest.kt`, `reminders/ReceiversTest.kt`

**Interfaces:**
- Consumes: Task 6.
- Produces:
  - `Migrations.seedWellness(db, routine)`: una sola vez (bandera `wellness_seeded`), en una transacción, crea los nueve hábitos de spec §12 con `group = WELLNESS`, su ventana, horario, ícono y `active` (Vitaminas inactivo). `dailyTarget` = número de horas que `timesFor` da para un miércoles con la rutina por defecto (mínimo 1). Instalaciones nuevas: `Seed` también los crea (misma función, misma bandera).
  - Nombres y copia de los avisos (título → cuerpo corto): `Descanso visual 20-20-20` → `Mira algo lejano 20 segundos.`; `Pararte y moverte` → `Párate y camina 2 minutos.`; `Estiramientos` → `Estira cuello, espalda y muñecas.`; `Postura` → `Endereza la espalda y relaja los hombros.`; `Comer a horas` → `Hora de comer algo.`; `Desconectar pantallas` → `Deja las pantallas: en 45 min te acuestas.`; `Vitaminas` → `Tómate las vitaminas.`; `Protector solar` → `Ponte protector solar.`; `Lectura sin pantalla` → `Un rato de lectura sin pantalla.`
  - `Reminder` gana `val habitIds: List<Long> = emptyList()` (para agrupados; `habitId` sigue siendo el primero). El planificador une los recordatorios de hábitos `WELLNESS` que caen en el mismo minuto en uno solo: clave `"wellness:{yyyyMMddHHmm}"`, título `Pausa`, cuerpo con los cuerpos cortos unidos por ` · ` si son 2 o más (si es uno solo, queda como un recordatorio normal de hábito).
  - Canal `pausas` ("Pausas y bienestar", `IMPORTANCE_DEFAULT`) para todos los avisos `WELLNESS`; acciones `Hecho` y `En 15 min` (sin "Saltar" en agrupados).
  - `ACTION_DONE` en un agrupado registra uno por cada `habitId` con la clave de cada hábito (`habit:{id}:{minuto}`), de modo que repetir el toque no duplica.

- [ ] **Step 1: Pruebas que fallan**
  - `SeedTest`: tras `seedWellness`, hay 9 hábitos `WELLNESS` con los nombres exactos, Vitaminas inactivo; llamarla otra vez no duplica; borrar uno y volver a llamarla no lo recrea.
  - `ReminderPlannerTest`: miércoles a las 08:00 con 20-20-20 y Pararte activos → a las 09:00 hay un solo recordatorio `wellness:202611180900` con dos `habitIds` y cuerpo `Mira algo lejano 20 segundos. · Párate y camina 2 minutos.`; a las 08:30 uno normal solo de 20-20-20; el sábado ninguno de los de ventana de trabajo.
  - `ReceiversTest`: `ACTION_DONE` sobre el agrupado de las 09:00 suma 1 a cada hábito; repetirlo no cambia nada.
- [ ] **Step 2:** FAIL. **Step 3:** Implementar. **Step 4:** Suite → PASS.
- [ ] **Step 5:** Commit `feat: seed wellness habits and group same-minute wellness reminders`.

---

### Task 8: Sección "Pausas y bienestar" en Hábitos y formulario

**Files:**
- Modify: `habits/HabitsViewModel.kt`, `habits/HabitsScreen.kt` (o un nuevo `habits/WellnessSection.kt`), `habits/HabitFormViewModel.kt`, `habits/HabitFormScreen.kt`, `ui/BrujulaNavHost.kt` (ruta del formulario con grupo), `today/TodayViewModel.kt` (tarjetas solo `ESSENTIAL`)
- Test: `habits/HabitsViewModelTest.kt`, `habits/HabitFormViewModelTest.kt`, `today/TodayViewModelTest.kt`

**Interfaces:**
- Produces:
  - `HabitsUiState` gana `val wellness: List<WellnessRow>`; `data class WellnessRow(val habit: Habit, val count: Int, val target: Int, val summary: String)`. `cards` queda solo con `ESSENTIAL`.
  - `summary` en español: `Cada 30 min en horario de trabajo`, `Cada 2 h en horario de trabajo`, `10:15 y 16:15 en horario de trabajo`, `12:30 y 16:00`, `45 min antes de dormir`, `9:00`.
  - `HabitsViewModel`: `fun setActive(habitId: Long, active: Boolean)` (resincroniza); `add`/`undo` ya existentes sirven para el "+".
  - Pantalla: sección `Pausas y bienestar` bajo la racha; fila compacta por hábito (ícono, nombre, resumen, `x de y`, botón "+" de 48 dp, `Switch` con etiqueta accesible), inactivos atenuados; al final `Agregar a pausas y bienestar` abre el formulario con `group = WELLNESS`.
  - Formulario: interruptor `Solo en horario de trabajo` (window) y modo de horario `Antes de dormir` con pasos de 15 min (15–180); la ruta `habit?id={id}&group={group}`.
  - Hoy: las tarjetas de hábito usan solo `ESSENTIAL` activos.

- [ ] **Step 1: Pruebas que fallan**
  - `HabitsViewModelTest`: con los 9 sembrados, `wellness` tiene 9 filas en el orden de spec §12 y `cards` sigue con agua y bálsamo; `setActive(vitaminas, true)` lo activa y resincroniza; los resúmenes coinciden con los textos de arriba.
  - `HabitFormViewModelTest`: crear con `group = WELLNESS`, `window = WORK` y `BeforeBed(60)` guarda esos valores.
  - `TodayViewModelTest`: con hábitos de bienestar activos, las tarjetas de Hoy siguen siendo agua y bálsamo.
- [ ] **Step 2:** FAIL. **Step 3:** Implementar. **Step 4:** Suite + `:app:assembleDebug :app:lintDebug` → PASS, 0 errores de lint.
- [ ] **Step 5:** Commit `feat: wellness section in Habits and work-hours/before-bed options in habit form`.

---

### Task 9: Verificación en el teléfono

- [ ] **Step 1:** `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:installDebug` → todo verde, instalado en el Samsung (`adb devices` lo lista). La instalación es una actualización: los datos del usuario y la migración de base de datos 1→2 deben conservar sus registros.
- [ ] **Step 2:** En el teléfono (capturas con `adb exec-out screencap`): Ajustes muestra `Fines de semana y festivos` con 10:00 / 1:00; Hábitos muestra la sección `Pausas y bienestar` con los 9 (Vitaminas apagado); `adb shell dumpsys alarm` muestra el aviso de ejercicio del sábado 21 nov a las 12:00, el bálsamo del sábado a las 10:00 (no a las 09:00), el ejercicio de las 18:30 del jueves y un aviso agrupado de pausa a las 09:00 del jueves.
- [ ] **Step 3:** Commit de ajustes si los hubo.
