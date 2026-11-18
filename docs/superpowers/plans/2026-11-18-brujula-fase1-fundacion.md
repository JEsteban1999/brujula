# Brújula · Fase 1 (Fundación sin Claude) — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Una app Android instalable en el teléfono del usuario que ya resuelve "se me olvidan las cosas" sin Claude: pantalla Hoy, crear/editar actividades a mano, hábitos con recordatorios exactos que se marcan desde la notificación, rutina configurable, temas claro/oscuro/automático y la apertura animada.

**Architecture:** App de un solo módulo (`:app`) en Kotlin + Jetpack Compose, organizada por funcionalidad. Room guarda actividades, hábitos y registros; DataStore guarda la rutina y ajustes. La lógica de tiempo (ventana de vigilia, ocurrencias, choques, horas de trabajo, planificación de recordatorios) es Kotlin puro y se prueba en JVM. Los recordatorios se programan con `AlarmManager` exacto en una ventana móvil de 24 h que se recalcula al cambiar datos, al arrancar, al reiniciar el teléfono y cada vez que suena una alarma. Inyección de dependencias manual con un `AppContainer`.

**Tech Stack:** Kotlin 2.1.0 · AGP 8.7.3 · Gradle 8.11.1 · Compose BOM 2024.12.01 · Navigation Compose 2.8.5 · Room 2.6.1 (KSP 2.1.0-1.0.29) · DataStore Preferences 1.1.1 · core-splashscreen 1.0.1 · JUnit 4.13.2 · Robolectric 4.14.1 · kotlinx-coroutines-test 1.9.0 · Turbine 1.2.0. (Si al ejecutar hay versiones estables más nuevas y compatibles entre sí, se pueden subir; no bajar.)

**Spec:** `docs/superpowers/specs/2026-11-18-brujula-design.md` (leerla completa antes de empezar). Bocetos: https://claude.ai/artifact/UTuceHbQngnPkec6wo8JQD

## Global Constraints

- Paquete: `app.brujula`. Nombre visible: `Brújula`. Lema: `Hacia dónde va tu día`.
- `minSdk = 31` (Android 12), `compileSdk = 35`, `targetSdk = 35`. Solo Android teléfono.
- Toda la interfaz en español. Formato de hora 24 h (`19:00`), fechas como `Miércoles 18 de noviembre`.
- Sin servidor y sin internet en esta fase. Nada de la API de Claude todavía.
- Tema claro (principal): fondo `#F4F3FF`, superficie `#FFFFFF`, superficie 2 `#E9E7FF`, texto `#14121F`, secundario `#55516E`, acento `#4B3BFF` (texto encima `#FFFFFF`), acento oscuro para texto `#3A2BE0`, acento 2 `#FFB020` (texto encima `#14121F`), acento 2 para texto `#9A6200`, suave `#E3E0FF`, suave 2 `#FFF0D1`, líneas `#DEDBF5`.
- Tema oscuro: fondo `#0F0D1F`, superficie `#1A1733`, superficie 2 `#25214A`, texto `#F1F0FF`, secundario `#A9A5C9`, acento `#7B6CFF` (texto encima `#0F0D1F`), acento para texto `#A79CFF`, acento 2 `#FFB020` (texto encima `#14121F`), acento 2 para texto `#FFC657`, suave `#241F52`, suave 2 `#33280F`, líneas `#2E2A55`.
- Tipografía: Bricolage Grotesque (títulos, cifras grandes; pesos 600/800) y DM Sans (texto; 400/500/700), empaquetadas como fuentes variables en `res/font`.
- Tema Automático: oscuro desde las `21:00` hasta la hora de levantarse.
- Rutina por defecto: levantarse `08:15`, acostarse `00:30`, trabajo preferido `08:00–12:00` y `14:00–18:00`, flexible = sí, meta de trabajo `6–7 h` (360–420 min), aviso de pocas horas por debajo de `5 h` (300 min).
- Hábitos por defecto: Agua cada `90` min en vigilia, meta `8` vasos; Bálsamo labial a las `09:00`, `13:00`, `17:00`, `21:00`, meta `4`. Actividad por defecto: "Ejercicio en casa", tipo Ejercicio, todos los días `18:30`, `30` min.
- El "día lógico" cambia a las `04:00` (algo hecho a las 00:20 cuenta para el día anterior).
- Recordatorios de hábitos solo dentro de la ventana de vigilia (levantarse → acostarse, aunque acostarse sea pasada la medianoche).
- Respetar "reducir movimiento" del sistema en todas las animaciones.
- Apertura animada total ≤ `2000` ms.
- Commits frecuentes, uno por tarea como mínimo. Mensajes en inglés estilo `feat: …`.

## Review Focus

1. **Acostarse pasada la medianoche (00:30):** el aviso de agua de las 23:15 debe sonar, ninguno entre 00:30 y la hora de levantarse, y lo hecho a las 00:20 cuenta para "ayer". → pruebas en Task 2 y Task 4.
2. **Teléfono reiniciado, hora o zona cambiada, app actualizada o cerrada a la fuerza:** los recordatorios deben reprogramarse solos. → prueba en Task 5 (`BootReceiver` dispara la resincronización con cada acción).
3. **Permisos de notificaciones o alarmas exactas negados:** la app no debe fallar en silencio; Hoy muestra un aviso con botón para arreglarlo. → prueba en Task 6.
4. **Tocar "Hecho" dos veces en la misma notificación (o en una vieja):** no debe contar dos vasos. → prueba en Task 3 (índice único por `reminderKey`) y Task 5.
5. **Actividad que cruza la medianoche o termina después de acostarse (23:30 + 1 h):** debe mostrarse en su día, detectar choques con lo del día siguiente y no romper el cálculo de horas. → pruebas en Task 2.

---

## Estructura de archivos

```
settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradle.properties, .gitignore
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/res/font/bricolage_grotesque.ttf, dm_sans.ttf
app/src/main/res/drawable/ic_launcher_foreground.xml, avd_splash_icon.xml
app/src/main/res/values/themes.xml, strings.xml
app/src/main/java/app/brujula/
  BrujulaApp.kt                    Application + AppContainer
  core/time/Clock.kt               reloj inyectable
  core/time/TimeSpan.kt            intervalo [start, end) con LocalDateTime
  core/time/Formatting.kt          formatos en español
  core/theme/Color.kt, Type.kt, Theme.kt, ThemeMode.kt
  routine/Routine.kt               modelo de rutina + ventana de vigilia + día lógico
  routine/RoutineRepository.kt     DataStore
  activities/Activity.kt           modelo, tipos, repetición, ocurrencias
  activities/Conflicts.kt          choques
  activities/WorkHours.kt          horas de trabajo y estado frente a la meta
  activities/ActivityRepository.kt
  activities/form/ActivityFormViewModel.kt, ActivityFormScreen.kt
  habits/Habit.kt                  modelo + horario
  habits/HabitRepository.kt
  habits/Streaks.kt
  habits/HabitsViewModel.kt, HabitsScreen.kt, HabitFormScreen.kt
  data/db/BrujulaDatabase.kt, Entities.kt, Daos.kt, Converters.kt, Seed.kt
  reminders/Reminder.kt, ReminderPlanner.kt, ReminderScheduler.kt,
            AlarmReminderScheduler.kt, ReminderSync.kt, Notifications.kt,
            ReminderReceiver.kt, NotificationActionReceiver.kt, BootReceiver.kt,
            PermissionStatus.kt
  today/TodayViewModel.kt, TodayScreen.kt, TimelineItem.kt
  settings/RoutineViewModel.kt, RoutineScreen.kt
  splash/IntroScreen.kt
  ui/MainActivity.kt, BrujulaNavHost.kt, BottomBar.kt
app/src/test/java/app/brujula/...  (espejo de lo anterior)
```

---

### Task 1: Entorno, proyecto y tema

**Files:**
- Create: archivos Gradle raíz, `app/build.gradle.kts`, `AndroidManifest.xml`, `res/values/*`, `res/font/*`, `res/drawable/ic_launcher_foreground.xml`, `BrujulaApp.kt`, `core/time/Clock.kt`, `core/theme/*`, `ui/MainActivity.kt`, `.gitignore`
- Test: `app/src/test/java/app/brujula/core/theme/ThemeModeTest.kt`

**Interfaces:**
- Produces:
  - `interface Clock { fun now(): LocalDateTime; fun today(): LocalDate }`, `object SystemClock : Clock`, `class FixedClock(var current: LocalDateTime) : Clock` (en `core/time/Clock.kt`; `FixedClock` en el source set principal para usarlo en pruebas de todos los módulos).
  - `enum class ThemeMode { LIGHT, DARK, AUTO }`
  - `fun resolveDark(mode: ThemeMode, now: LocalTime, wake: LocalTime, autoDarkFrom: LocalTime = LocalTime.of(21, 0)): Boolean`
  - `@Composable fun BrujulaTheme(dark: Boolean, content: @Composable () -> Unit)` y `object BrujulaColors` accesible como `BrujulaTheme.colors` con los campos `bg, surface, surface2, text, textMuted, accent, onAccent, accentText, accent2, onAccent2, accent2Text, soft, soft2, line` (valores de Global Constraints). Tipografías `BrujulaTheme.type.display` (Bricolage) y `.body` (DM Sans).
  - `class AppContainer(context: Context)` en `BrujulaApp.kt` (se irá llenando en tareas siguientes) y `val Context.container: AppContainer`.

- [ ] **Step 1: Preparar herramientas (requiere al usuario si falta algo)**

El usuario NO usa Android Studio: todo se compila e instala por línea de comandos. JDK portable Temurin 21 ya instalado en `C:\Users\juesn\tools\jdk-21` (JAVA_HOME de usuario apunta ahí; el JAVA_HOME de sistema apunta a una carpeta inexistente, así que en cada comando PowerShell anteponer `$env:JAVA_HOME="C:\Users\juesn\tools\jdk-21"; $env:Path="$env:JAVA_HOME\bin;$env:Path";`). SDK en `C:\Users\juesn\AppData\Local\Android\Sdk` (cmdline-tools/latest, licencia aceptada, plataforma 36, build-tools 35.0.0 y 36.0.0, adb). Gradle no está instalado: el wrapper se arma descargando `gradle-wrapper.jar` y los scripts `gradlew`/`gradlew.bat` de la versión 8.11.1 (repositorio oficial de Gradle) o descargando una vez la distribución 8.11.1 a una carpeta temporal y ejecutando `gradle wrapper --gradle-version 8.11.1`. Teléfono: por cable USB.
Expected: `java -version` muestra 21.

- [ ] **Step 2: Inicializar git y el wrapper de Gradle**

`git init` en `C:\Proyectos\Agenda`. `.gitignore` con: `.gradle/`, `build/`, `local.properties`, `.idea/`, `*.iml`, `.superpowers/`, `captures/`, `.kotlin/`. Crear el wrapper de Gradle 8.11.1 (descargar la distribución una vez y ejecutar `gradle wrapper --gradle-version 8.11.1`, o generarlo con Android Studio). `local.properties` con `sdk.dir=C\:\\Users\\juesn\\AppData\\Local\\Android\\Sdk`.

- [ ] **Step 3: Escribir la prueba que falla**

```kotlin
class ThemeModeTest {
    private val wake = LocalTime.of(8, 15)
    @Test fun lightAndDarkIgnoreTime() {
        assertFalse(resolveDark(ThemeMode.LIGHT, LocalTime.of(23, 0), wake))
        assertTrue(resolveDark(ThemeMode.DARK, LocalTime.of(12, 0), wake))
    }
    @Test fun autoIsDarkFrom21UntilWake() {
        assertFalse(resolveDark(ThemeMode.AUTO, LocalTime.of(20, 59), wake))
        assertTrue(resolveDark(ThemeMode.AUTO, LocalTime.of(21, 0), wake))
        assertTrue(resolveDark(ThemeMode.AUTO, LocalTime.of(2, 0), wake))
        assertTrue(resolveDark(ThemeMode.AUTO, LocalTime.of(8, 14), wake))
        assertFalse(resolveDark(ThemeMode.AUTO, LocalTime.of(8, 15), wake))
    }
}
```

- [ ] **Step 4: Ejecutar y ver que falla**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "app.brujula.core.theme.ThemeModeTest"`
Expected: FAIL (no compila: `resolveDark` no existe).

- [ ] **Step 5: Implementar proyecto, tema y `resolveDark`**

Proyecto Gradle con `libs.versions.toml` (versiones del Tech Stack), plugins `com.android.application`, `org.jetbrains.kotlin.android`, `org.jetbrains.kotlin.plugin.compose`, `com.google.devtools.ksp`. `testOptions.unitTests.isIncludeAndroidResources = true` (Robolectric). Descargar las fuentes variables a `res/font/`: `bricolage_grotesque.ttf` (de `google/fonts`, `ofl/bricolagegrotesque/BricolageGrotesque[opsz,wdth,wght].ttf`) y `dm_sans.ttf` (`ofl/dmsans/DMSans[opsz,wght].ttf`); en `Type.kt` usar `Font(R.font.x, variationSettings = FontVariation.Settings(FontVariation.weight(n)))`. Ícono adaptativo con el diseño de la spec §6 (cuadrado `#4B3BFF`, anillo blanco, aguja norte `#FFB020` y sur blanca). `MainActivity` muestra un texto "Brújula" dentro de `BrujulaTheme`.

- [ ] **Step 6: Ejecutar pruebas y compilar**

Run: `.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug`
Expected: BUILD SUCCESSFUL, ThemeModeTest en verde.

- [ ] **Step 7: Instalar en el teléfono**

Con depuración USB activada: `adb devices` (debe listar el teléfono) y `.\gradlew.bat :app:installDebug`. Expected: aparece "Brújula" con su ícono y abre.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "chore: scaffold Brújula Android project with theme and design spec"
```

---

### Task 2: Modelo de tiempo, rutina y actividades (Kotlin puro)

**Files:**
- Create: `core/time/TimeSpan.kt`, `core/time/Formatting.kt`, `routine/Routine.kt`, `activities/Activity.kt`, `activities/Conflicts.kt`, `activities/WorkHours.kt`
- Test: `routine/RoutineTest.kt`, `activities/ActivityTest.kt`, `activities/ConflictsTest.kt`, `activities/WorkHoursTest.kt`, `core/time/FormattingTest.kt`

**Interfaces:**
- Produces:
  - `data class TimeSpan(val start: LocalDateTime, val end: LocalDateTime) { fun overlaps(other: TimeSpan): Boolean; operator fun contains(t: LocalDateTime): Boolean; val minutes: Long }` — intervalo semiabierto `[start, end)`.
  - `data class Routine(val wake: LocalTime = 08:15, val bed: LocalTime = 00:30, val workBlocks: List<Pair<LocalTime, LocalTime>> = [08:00–12:00, 14:00–18:00], val flexibleWork: Boolean = true, val workGoalMin: IntRange = 360..420, val themeMode: ThemeMode = ThemeMode.AUTO)`
  - `fun Routine.awakeWindow(day: LocalDate): TimeSpan` (si `bed <= wake`, `bed` es del día siguiente), `fun Routine.isAwake(at: LocalDateTime): Boolean`, `fun logicalDay(at: LocalDateTime): LocalDate` (top-level; frontera `04:00`).
  - `enum class ActivityType { WORK, EXERCISE, APPOINTMENT, PERSONAL }` con `labelEs` = `Trabajo, Ejercicio, Cita, Personal`.
  - `enum class Repeat { NONE, DAILY, WEEKDAYS, WEEKLY }` con `labelEs` = `No se repite, Todos los días, Lunes a viernes, Cada semana`.
  - `data class Activity(val id: Long = 0, val title: String, val type: ActivityType, val date: LocalDate, val start: LocalTime, val durationMin: Int, val repeat: Repeat = Repeat.NONE, val remindBeforeMin: Int? = 15, val copyToGoogle: Boolean = type == ActivityType.APPOINTMENT)`
  - `fun Activity.occursOn(day: LocalDate): Boolean`
  - `data class Occurrence(val activity: Activity, val day: LocalDate, val span: TimeSpan, val done: Boolean)`
  - `fun occurrencesOn(day: LocalDate, activities: List<Activity>, doneKeys: Set<Pair<Long, LocalDate>>): List<Occurrence>` — ordenadas por inicio.
  - `fun findConflict(candidate: TimeSpan, busy: List<Occurrence>, ignoreActivityId: Long? = null): Occurrence?`
  - `enum class WorkStatus { LOW, BELOW_GOAL, IN_GOAL, ABOVE_GOAL }`, `fun workMinutes(occurrences: List<Occurrence>): Int` (suma tipo `WORK`), `fun workStatus(minutes: Int, goal: IntRange, lowThreshold: Int = 300): WorkStatus`.
  - `fun formatDayEs(day: LocalDate): String` → `"Miércoles 18 de noviembre"`; `fun formatTime(t: LocalTime): String` → `"9:05"` / `"19:00"`; `fun formatMinutes(min: Int): String` → `"2 h 30"`, `"45 min"`, `"6 h"`.

- [ ] **Step 1: Escribir las pruebas que fallan**

```kotlin
class RoutineTest {
    private val r = Routine()
    private val d = LocalDate.of(2026, 11, 18)
    @Test fun awakeWindowCrossesMidnight() {
        assertEquals(TimeSpan(d.atTime(8, 15), d.plusDays(1).atTime(0, 30)), r.awakeWindow(d))
    }
    @Test fun isAwakeAroundMidnight() {
        assertTrue(r.isAwake(d.atTime(23, 50)))
        assertTrue(r.isAwake(d.plusDays(1).atTime(0, 20)))
        assertFalse(r.isAwake(d.plusDays(1).atTime(0, 31)))
        assertFalse(r.isAwake(d.plusDays(1).atTime(8, 0)))
    }
    @Test fun logicalDaySwitchesAt4am() {
        assertEquals(d, logicalDay(d.plusDays(1).atTime(0, 20)))
        assertEquals(d, logicalDay(d.plusDays(1).atTime(3, 59)))
        assertEquals(d.plusDays(1), logicalDay(d.plusDays(1).atTime(4, 0)))
    }
}

class ActivityTest {
    private val wed = LocalDate.of(2026, 11, 18)
    private fun act(repeat: Repeat) = Activity(1, "X", ActivityType.WORK, wed, LocalTime.of(9, 0), 60, repeat)
    @Test fun repeatRules() {
        assertTrue(act(Repeat.NONE).occursOn(wed)); assertFalse(act(Repeat.NONE).occursOn(wed.plusDays(1)))
        assertTrue(act(Repeat.DAILY).occursOn(wed.plusDays(4)))
        assertFalse(act(Repeat.WEEKDAYS).occursOn(LocalDate.of(2026, 11, 21))) // sábado
        assertTrue(act(Repeat.WEEKLY).occursOn(wed.plusDays(7))); assertFalse(act(Repeat.WEEKLY).occursOn(wed.plusDays(6)))
        assertFalse(act(Repeat.DAILY).occursOn(wed.minusDays(1))) // no antes de su fecha
    }
    @Test fun occurrenceCrossingMidnightStaysOnItsDay() {
        val late = Activity(2, "Película", ActivityType.PERSONAL, wed, LocalTime.of(23, 30), 60)
        val occ = occurrencesOn(wed, listOf(late), emptySet()).single()
        assertEquals(wed.plusDays(1).atTime(0, 30), occ.span.end)
    }
    @Test fun doneIsPerDay() {
        val occ = occurrencesOn(wed.plusDays(1), listOf(act(Repeat.DAILY)), setOf(1L to wed)).single()
        assertFalse(occ.done)
    }
    @Test fun appointmentCopiesToGoogleByDefault() {
        assertTrue(Activity(title = "Dentista", type = ActivityType.APPOINTMENT, date = wed, start = LocalTime.NOON, durationMin = 30).copyToGoogle)
        assertFalse(Activity(title = "Leer", type = ActivityType.PERSONAL, date = wed, start = LocalTime.NOON, durationMin = 30).copyToGoogle)
    }
}

class ConflictsTest {
    private val d = LocalDate.of(2026, 11, 18)
    private fun occ(id: Long, h: Int, m: Int, dur: Long, day: LocalDate = d) =
        Occurrence(Activity(id, "A$id", ActivityType.WORK, day, LocalTime.of(h, m), dur.toInt()), day,
            TimeSpan(day.atTime(h, m), day.atTime(h, m).plusMinutes(dur)), false)
    @Test fun touchingEdgesDoNotConflict() {
        assertNull(findConflict(TimeSpan(d.atTime(10, 0), d.atTime(11, 0)), listOf(occ(1, 9, 0, 60))))
    }
    @Test fun overlapIsDetected() {
        assertEquals(1L, findConflict(TimeSpan(d.atTime(9, 30), d.atTime(10, 30)), listOf(occ(1, 9, 0, 60)))?.activity?.id)
    }
    @Test fun lateActivityConflictsWithNextDay() {
        val nextMorning = occ(2, 0, 0, 30, d.plusDays(1))
        assertNotNull(findConflict(TimeSpan(d.atTime(23, 30), d.plusDays(1).atTime(0, 30)), listOf(nextMorning)))
    }
    @Test fun editingIgnoresItself() {
        assertNull(findConflict(TimeSpan(d.atTime(9, 0), d.atTime(10, 0)), listOf(occ(1, 9, 0, 60)), ignoreActivityId = 1))
    }
}

class WorkHoursTest {
    @Test fun statusAgainstGoal() {
        assertEquals(WorkStatus.LOW, workStatus(299, 360..420))
        assertEquals(WorkStatus.BELOW_GOAL, workStatus(300, 360..420))
        assertEquals(WorkStatus.IN_GOAL, workStatus(360, 360..420))
        assertEquals(WorkStatus.IN_GOAL, workStatus(420, 360..420))
        assertEquals(WorkStatus.ABOVE_GOAL, workStatus(421, 360..420))
    }
    // + workMinutesCountsOnlyWork: 2 WORK de 90 y 60 min + 1 EXERCISE de 30 → 150
}

class FormattingTest {
    @Test fun spanish() {
        assertEquals("Miércoles 18 de noviembre", formatDayEs(LocalDate.of(2026, 11, 18)))
        assertEquals("2 h 30", formatMinutes(150)); assertEquals("45 min", formatMinutes(45)); assertEquals("6 h", formatMinutes(360))
        assertEquals("9:05", formatTime(LocalTime.of(9, 5))); assertEquals("19:00", formatTime(LocalTime.of(19, 0)))
    }
}
```

- [ ] **Step 2: Ejecutar y ver que fallan**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "app.brujula.routine.*" --tests "app.brujula.activities.*" --tests "app.brujula.core.time.*"`
Expected: FAIL (no compila).

- [ ] **Step 3: Implementar los modelos y funciones de Interfaces**

`WEEKLY` = mismo día de la semana que `date`; `WEEKDAYS` = lunes a viernes. Ninguna repetición ocurre antes de `date`.

- [ ] **Step 4: Ejecutar y ver que pasan**

Mismo comando. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: time, routine and activity domain model"
```

---

### Task 3: Persistencia (Room + DataStore) y datos iniciales

**Files:**
- Create: `data/db/Entities.kt`, `Daos.kt`, `Converters.kt`, `BrujulaDatabase.kt`, `Seed.kt`, `activities/ActivityRepository.kt`, `habits/Habit.kt`, `habits/HabitRepository.kt`, `routine/RoutineRepository.kt`
- Modify: `BrujulaApp.kt` (AppContainer expone repositorios)
- Test: `data/ActivityRepositoryTest.kt`, `data/HabitRepositoryTest.kt`, `data/RoutineRepositoryTest.kt` (Robolectric, Room en memoria)

**Interfaces:**
- Consumes: modelos de Task 2.
- Produces:
  - `sealed interface HabitSchedule { data class Every(val intervalMin: Int) : HabitSchedule; data class AtTimes(val times: List<LocalTime>) : HabitSchedule }`
  - `enum class HabitIcon { WATER, LIP_BALM, STRETCH, GENERIC }`
  - `data class Habit(val id: Long = 0, val name: String, val icon: HabitIcon, val schedule: HabitSchedule, val dailyTarget: Int, val days: Set<DayOfWeek> = DayOfWeek.entries.toSet(), val active: Boolean = true)`
  - `class ActivityRepository(dao)`: `fun observeAll(): Flow<List<Activity>>`, `fun observeDoneKeys(): Flow<Set<Pair<Long, LocalDate>>>`, `suspend fun get(id: Long): Activity?`, `suspend fun upsert(a: Activity): Long`, `suspend fun delete(id: Long)`, `suspend fun setDone(id: Long, day: LocalDate, done: Boolean)`
  - `class HabitRepository(dao)`: `fun observeHabits(): Flow<List<Habit>>`, `suspend fun upsert(h: Habit): Long`, `suspend fun delete(id: Long)`, `fun observeCountsFor(day: LocalDate): Flow<Map<Long, Int>>` (por día lógico), `suspend fun logDone(habitId: Long, at: LocalDateTime, reminderKey: String? = null): Boolean` (devuelve `false` si ese `reminderKey` ya existía), `suspend fun logSkip(habitId: Long, at: LocalDateTime, reminderKey: String)`, `suspend fun undoLast(habitId: Long, day: LocalDate)`, `fun observeHandledKeys(): Flow<Set<String>>` (hechos o saltados).
  - `class RoutineRepository(dataStore)`: `val routine: Flow<Routine>`, `suspend fun update(transform: (Routine) -> Routine)`.
  - `object Seed { suspend fun ensureDefaults(activities: ActivityRepository, habits: HabitRepository, today: LocalDate) }` — inserta los valores por defecto de Global Constraints solo si las tablas están vacías.

- [ ] **Step 1: Escribir las pruebas que fallan**

```kotlin
@RunWith(RobolectricTestRunner::class)
class HabitRepositoryTest {
    // db = Room.inMemoryDatabaseBuilder(...).allowMainThreadQueries().build()
    @Test fun sameReminderKeyCountsOnce() = runTest {
        val id = repo.upsert(Habit(name = "Agua", icon = HabitIcon.WATER, schedule = HabitSchedule.Every(90), dailyTarget = 8))
        val at = LocalDateTime.of(2026, 11, 18, 11, 15)
        assertTrue(repo.logDone(id, at, "habit:$id:202611181115"))
        assertFalse(repo.logDone(id, at, "habit:$id:202611181115"))
        assertEquals(1, repo.observeCountsFor(at.toLocalDate()).first()[id])
    }
    @Test fun afterMidnightCountsForPreviousDay() = runTest {
        val id = repo.upsert(Habit(name = "Agua", icon = HabitIcon.WATER, schedule = HabitSchedule.Every(90), dailyTarget = 8))
        repo.logDone(id, LocalDateTime.of(2026, 11, 19, 0, 20))
        assertEquals(1, repo.observeCountsFor(LocalDate.of(2026, 11, 18)).first()[id])
    }
    @Test fun schedulesRoundTrip() = runTest { /* Every(90) y AtTimes(09:00,13:00,17:00,21:00) se leen iguales */ }
}
// ActivityRepositoryTest: upsert+get igual; setDone(id, día, true) aparece en observeDoneKeys y no afecta otro día.
// RoutineRepositoryTest: valor por defecto == Routine(); update cambia wake y persiste.
// SeedTest: ensureDefaults dos veces deja exactamente 2 hábitos y 1 actividad.
```

- [ ] **Step 2: Ejecutar y ver que fallan**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "app.brujula.data.*"`
Expected: FAIL.

- [ ] **Step 3: Implementar entidades, DAOs, convertidores y repositorios**

`HabitLogEntity(id, habitId, at: LocalDateTime, logicalDay: LocalDate, skipped: Boolean, reminderKey: String?)` con índice único en `reminderKey` (Room permite varios NULL) e inserción `OnConflictStrategy.IGNORE`. `HabitSchedule` se guarda como texto (`"every:90"`, `"at:09:00,13:00"`). `ActivityCompletionEntity(activityId, day)` con clave primaria compuesta. Rutina en DataStore Preferences (horas como `"HH:mm"`). `Seed.ensureDefaults` se llama desde `BrujulaApp.onCreate` en una corrutina de aplicación.

- [ ] **Step 4: Ejecutar y ver que pasan**

Mismo comando. Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A && git commit -m "feat: Room/DataStore persistence with default habits and routine"
```

---

### Task 4: Horario de hábitos y rachas

**Files:**
- Create: `habits/HabitTimes.kt` (en `Habit.kt` si queda corto), `habits/Streaks.kt`
- Test: `habits/HabitTimesTest.kt`, `habits/StreaksTest.kt`

**Interfaces:**
- Consumes: `Habit`, `HabitSchedule`, `Routine.awakeWindow`.
- Produces:
  - `fun Habit.timesFor(day: LocalDate, routine: Routine): List<LocalDateTime>` — `Every(n)`: desde `wake + n` cada `n` min mientras sea `< bed` (ventana de vigilia, puede cruzar medianoche); `AtTimes`: esas horas en `day` que caigan dentro de la vigilia. Vacío si `day.dayOfWeek !in days` o `!active`.
  - `fun exerciseStreak(today: LocalDate, doneDays: Set<LocalDate>): Int` — días seguidos con ejercicio hecho terminando hoy, o ayer si hoy aún no se hizo.

- [ ] **Step 1: Escribir las pruebas que fallan**

```kotlin
class HabitTimesTest {
    private val d = LocalDate.of(2026, 11, 18)
    private val water = Habit(1, "Agua", HabitIcon.WATER, HabitSchedule.Every(90), 8)
    @Test fun waterEvery90WithinAwakeWindow() {
        val t = water.timesFor(d, Routine())
        assertEquals(d.atTime(9, 45), t.first())
        assertEquals(d.atTime(23, 15), t.last())
        assertEquals(10, t.size)
        assertTrue(t.none { it.toLocalTime() in LocalTime.of(0, 31)..LocalTime.of(8, 14) })
    }
    @Test fun earlyBedtimeCutsReminders() {
        assertEquals(d.atTime(21, 45), water.timesFor(d, Routine(bed = LocalTime.of(22, 0))).last())
    }
    @Test fun balmFixedTimes() {
        val balm = Habit(2, "Bálsamo labial", HabitIcon.LIP_BALM, HabitSchedule.AtTimes(listOf(9, 13, 17, 21).map { LocalTime.of(it, 0) }), 4)
        assertEquals(listOf(9, 13, 17, 21), balm.timesFor(d, Routine()).map { it.hour })
        assertEquals(listOf(13, 17, 21), balm.timesFor(d, Routine(wake = LocalTime.of(10, 0))).map { it.hour })
    }
    @Test fun respectsDaysAndActive() { /* days = {MONDAY} → vacío el miércoles; active=false → vacío */ }
}
class StreaksTest {
    private val wed = LocalDate.of(2026, 11, 18)
    @Test fun streaks() {
        assertEquals(3, exerciseStreak(wed, setOf(wed, wed.minusDays(1), wed.minusDays(2))))
        assertEquals(2, exerciseStreak(wed, setOf(wed.minusDays(1), wed.minusDays(2)))) // hoy aún no
        assertEquals(0, exerciseStreak(wed, setOf(wed.minusDays(2))))
    }
}
```

- [ ] **Step 2: Ejecutar y ver que fallan** — `.\gradlew.bat :app:testDebugUnitTest --tests "app.brujula.habits.*"` → FAIL.
- [ ] **Step 3: Implementar `timesFor` y `exerciseStreak`.**
- [ ] **Step 4: Ejecutar y ver que pasan** — mismo comando → PASS.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat: habit schedules within awake window and exercise streak"`

---

### Task 5: Motor de recordatorios

**Files:**
- Create: `reminders/Reminder.kt`, `ReminderPlanner.kt`, `ReminderScheduler.kt`, `AlarmReminderScheduler.kt`, `ReminderSync.kt`, `Notifications.kt`, `ReminderReceiver.kt`, `NotificationActionReceiver.kt`, `BootReceiver.kt`, `PermissionStatus.kt`
- Modify: `AndroidManifest.xml`, `BrujulaApp.kt`
- Test: `reminders/ReminderPlannerTest.kt`, `reminders/ReceiversTest.kt` (Robolectric)

**Interfaces:**
- Consumes: repositorios de Task 3, `timesFor` de Task 4, `occurrencesOn` de Task 2, `Clock`.
- Produces:
  - `enum class ReminderKind { HABIT, ACTIVITY }`
  - `data class Reminder(val key: String, val at: LocalDateTime, val kind: ReminderKind, val title: String, val body: String, val habitId: Long? = null, val activityId: Long? = null, val day: LocalDate)` — claves `"habit:{id}:{yyyyMMddHHmm}"` y `"act:{id}:{yyyyMMdd}"`.
  - `object ReminderPlanner { fun plan(now: LocalDateTime, routine: Routine, habits: List<Habit>, habitCounts: Map<LocalDate, Map<Long, Int>>, handledKeys: Set<String>, activities: List<Activity>, doneKeys: Set<Pair<Long, LocalDate>>, horizonHours: Long = 24): List<Reminder> }`
  - `interface ReminderScheduler { fun replaceAll(reminders: List<Reminder>); fun scheduleOne(reminder: Reminder) }`
  - `class ReminderSync(clock, routineRepo, activityRepo, habitRepo, scheduler) { suspend fun resync() }` — expuesta en `AppContainer`.
  - `data class PermissionStatus(val notifications: Boolean, val exactAlarms: Boolean) { val ok get() = notifications && exactAlarms }` y `fun readPermissionStatus(context: Context): PermissionStatus`.
  - Acciones de notificación: `ACTION_DONE`, `ACTION_SNOOZE_15`, `ACTION_SKIP` (constantes en `NotificationActionReceiver`), extras `EXTRA_KEY`, `EXTRA_HABIT_ID`, `EXTRA_ACTIVITY_ID`, `EXTRA_DAY`.

- [ ] **Step 1: Escribir las pruebas que fallan**

```kotlin
class ReminderPlannerTest {
    private val d = LocalDate.of(2026, 11, 18)
    private val water = Habit(1, "Agua", HabitIcon.WATER, HabitSchedule.Every(90), 8)
    private fun plan(now: LocalDateTime, counts: Map<LocalDate, Map<Long, Int>> = emptyMap(), handled: Set<String> = emptySet(),
                     acts: List<Activity> = emptyList(), done: Set<Pair<Long, LocalDate>> = emptySet()) =
        ReminderPlanner.plan(now, Routine(), listOf(water), counts, handled, acts, done)

    @Test fun onlyFutureWithin24h() {
        val r = plan(d.atTime(12, 0)).filter { it.kind == ReminderKind.HABIT }
        assertEquals(d.atTime(12, 45), r.first().at)
        assertTrue(r.all { it.at > d.atTime(12, 0) && it.at <= d.atTime(12, 0).plusHours(24) })
    }
    @Test fun lateNightReminderFiresBeforeBed() {
        assertTrue(plan(d.atTime(23, 0)).any { it.at == d.atTime(23, 15) })
    }
    @Test fun handledKeysAndReachedTargetAreSkipped() {
        assertTrue(plan(d.atTime(12, 0), handled = setOf("habit:1:202611181245")).none { it.key == "habit:1:202611181245" })
        assertTrue(plan(d.atTime(12, 0), counts = mapOf(d to mapOf(1L to 8))).none { it.day == d && it.habitId == 1L })
    }
    @Test fun activityReminderBeforeStartUnlessDone() {
        val gym = Activity(5, "Ejercicio en casa", ActivityType.EXERCISE, d, LocalTime.of(18, 30), 30, Repeat.DAILY, remindBeforeMin = 15)
        assertTrue(plan(d.atTime(12, 0), acts = listOf(gym)).any { it.key == "act:5:20261118" && it.at == d.atTime(18, 15) })
        assertTrue(plan(d.atTime(12, 0), acts = listOf(gym), done = setOf(5L to d)).none { it.key == "act:5:20261118" })
    }
}

@RunWith(RobolectricTestRunner::class)
class ReceiversTest {
    @Test fun doneActionTwiceLogsOnce() { /* enviar ACTION_DONE dos veces con el mismo EXTRA_KEY → count == 1, notificación cancelada */ }
    @Test fun snoozeSchedulesOneIn15Minutes() { /* FakeScheduler.scheduleOne recibe at == now + 15 min y misma key con sufijo ":s" */ }
    @Test fun bootAndTimeChangesTriggerResync() {
        listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED, Intent.ACTION_MY_PACKAGE_REPLACED)
            .forEach { /* BootReceiver.onReceive → FakeReminderSync.calls se incrementa */ }
    }
}
```

- [ ] **Step 2: Ejecutar y ver que fallan** — `.\gradlew.bat :app:testDebugUnitTest --tests "app.brujula.reminders.*"` → FAIL.

- [ ] **Step 3: Implementar planificador, programador y receptores**

`AlarmReminderScheduler` usa `AlarmManager.setExactAndAllowWhileIdle(RTC_WAKEUP, …)` con `PendingIntent` hacia `ReminderReceiver` (request code = `key.hashCode()`), y guarda en `SharedPreferences` las claves programadas para cancelar las que sobran en `replaceAll`. Si `canScheduleExactAlarms()` es falso, usa `setAndAllowWhileIdle` y `PermissionStatus.exactAlarms = false`. `ReminderReceiver` muestra la notificación (canales `habitos` y `actividades`; hábitos con acciones "Hecho", "En 15 min", "Saltar"; actividades con "Hecho" y "En 15 min") y luego llama `ReminderSync.resync()` vía `goAsync()`. Textos: hábito agua → título `Hora de tomar agua`, cuerpo `Vas {n} de {meta} vasos.`; bálsamo → `Bálsamo labial`, `Vez {n+1} de {meta} del día.`; genérico → `{nombre}`; actividad → `{título} a las {hora}`. Manifest: `POST_NOTIFICATIONS`, `USE_EXACT_ALARM`, `SCHEDULE_EXACT_ALARM` (`maxSdkVersion="32"`), `RECEIVE_BOOT_COMPLETED`; `BootReceiver` exportado con los cuatro intents. `ReminderSync.resync()` también se llama al iniciar la app y cada vez que cambian rutina, actividades, hábitos o registros (colector en `BrujulaApp`, con `debounce(500)`).

- [ ] **Step 4: Ejecutar y ver que pasan** — mismo comando → PASS.

- [ ] **Step 5: Probar en el teléfono**

Instalar, crear (temporalmente, desde código de depuración o ajustando la hora del hábito) un recordatorio a 2 min, bloquear el teléfono. Expected: suena a su hora; "Hecho" suma 1 sin abrir la app; reiniciar el teléfono y comprobar que el siguiente aviso sigue programado (`adb shell dumpsys alarm | findstr app.brujula`).

- [ ] **Step 6: Commit** — `git add -A && git commit -m "feat: exact-alarm reminder engine with notification actions and boot resync"`

---

### Task 6: Navegación y pantalla Hoy

**Files:**
- Create: `today/TodayViewModel.kt`, `today/TodayScreen.kt`, `today/TimelineItem.kt`, `ui/BrujulaNavHost.kt`, `ui/BottomBar.kt`
- Modify: `ui/MainActivity.kt`
- Test: `today/TodayViewModelTest.kt`

**Interfaces:**
- Consumes: repositorios (Task 3), `occurrencesOn`, `workMinutes`, `workStatus` (Task 2), `readPermissionStatus` (Task 5), `Clock`.
- Produces:
  - Rutas: `Routes.TODAY = "today"`, `Routes.HABITS = "habits"`, `Routes.ROUTINE = "routine"`, `Routes.ACTIVITY_FORM = "activity?id={id}&start={start}"` (ambos opcionales; `start` ISO `LocalDateTime`), `Routes.HABIT_FORM = "habit?id={id}"`.
  - `data class HabitChip(val habitId: Long, val name: String, val icon: HabitIcon, val count: Int, val target: Int)`
  - `data class TimelineEntry(val occurrence: Occurrence, val isNow: Boolean)`
  - `data class TodayUiState(val dayLabel: String, val workMinutes: Int, val workGoal: IntRange, val workStatus: WorkStatus, val habits: List<HabitChip>, val timeline: List<TimelineEntry>, val freeSlots: List<TimeSpan>, val permissions: PermissionStatus)`
  - `class TodayViewModel(...)`: `val state: StateFlow<TodayUiState>`, `fun addHabit(habitId: Long)`, `fun toggleDone(activityId: Long)`, `fun refreshPermissions()`.

- [ ] **Step 1: Escribir las pruebas que fallan**

```kotlin
class TodayViewModelTest {
    // FixedClock(2026-11-18T10:40), repos en memoria (fakes simples o Room en memoria con Robolectric)
    @Test fun nowEntryAndWorkMinutes() = runTest {
        // actividades: Trabajo 08:00–10:30 (hecha), Ejercicio 10:30–11:00, Trabajo 11:00–12:30
        val s = vm.state.first { it.timeline.isNotEmpty() }
        assertTrue(s.timeline.single { it.isNow }.occurrence.activity.type == ActivityType.EXERCISE)
        assertEquals(240, s.workMinutes) // 150 + 90
        assertEquals("Miércoles 18 de noviembre", s.dayLabel)
    }
    @Test fun addHabitIncrementsCount() = runTest { /* addHabit(agua) → chip agua count 4 si había 3 */ }
    @Test fun toggleDoneMarksOnlyToday() = runTest { /* toggleDone(id) → done en hoy, no en mañana */ }
    @Test fun freeSlotsInsideAwakeWindow() = runTest { /* hueco 12:30–18:30 aparece; ninguno antes de 08:15 ni después de 00:30 */ }
    @Test fun missingPermissionsAreExposed() = runTest { /* con permisos falsos → state.permissions.ok == false */ }
}
```

- [ ] **Step 2: Ejecutar y ver que fallan** — `--tests "app.brujula.today.*"` → FAIL.

- [ ] **Step 3: Implementar ViewModel, pantalla y navegación**

Pantalla según el tablero "Hoy · Brújula claro" del lienzo: encabezado (día, `Hoy`, barra de trabajo `x / 6–7 h`), tarjetas de agua y bálsamo con "+" (hábitos activos con `dailyTarget > 0`, máximo 2 visibles; el resto en Hábitos), línea de tiempo con bloques (hora inicio/fin, riel de color por tipo, `AHORA` en el actual con fondo acento, círculo para marcar hecho, texto tachado si está hecho), huecos libres de ≥ 30 min como filas punteadas "Libre · tocar para agregar" que abren el formulario con `start` prellenado. Tocar el círculo → `toggleDone`; tocar el resto del bloque → formulario de edición. Si `permissions.ok == false`, banner arriba: `Los recordatorios no pueden sonar. Toca para activarlos.` que abre los ajustes de notificaciones o de alarmas exactas según falte. Barra inferior: Hoy · Semana (deshabilitada, "Pronto") · botón central · Hábitos · Ajustes. **En esta fase el botón central abre el formulario** (la voz llega en la Fase 2). Animaciones: entrada escalonada de bloques, escala 0.95 al presionar, barras con resorte; desactivarlas si el sistema pide reducir movimiento. En la primera apertura, pedir `POST_NOTIFICATIONS`.

- [ ] **Step 4: Ejecutar y ver que pasan** — mismo comando → PASS.
- [ ] **Step 5: Revisar en el teléfono** — instalar; comparar con el tablero del lienzo en claro y oscuro. Expected: se ve el día con el ejercicio por defecto a las 18:30 y los contadores funcionan.
- [ ] **Step 6: Commit** — `git add -A && git commit -m "feat: Today screen with timeline, habit counters and navigation shell"`

---

### Task 7: Formulario de actividad (crear y editar)

**Files:**
- Create: `activities/form/ActivityFormViewModel.kt`, `activities/form/ActivityFormScreen.kt`
- Modify: `ui/BrujulaNavHost.kt`
- Test: `activities/form/ActivityFormViewModelTest.kt`

**Interfaces:**
- Consumes: `ActivityRepository`, `RoutineRepository`, `findConflict`, `occurrencesOn`, `Clock`.
- Produces:
  - `enum class DateChoice { TODAY, TOMORROW, OTHER }`
  - `data class ActivityFormState(val title: String = "", val type: ActivityType = ActivityType.PERSONAL, val dateChoice: DateChoice = DateChoice.TODAY, val date: LocalDate, val start: LocalTime, val durationMin: Int = 60, val repeat: Repeat = Repeat.NONE, val remind: Boolean = true, val copyToGoogle: Boolean = false, val copyToGoogleTouched: Boolean = false, val conflict: Occurrence? = null, val titleError: String? = null, val isEdit: Boolean = false, val saved: Boolean = false)`
  - `class ActivityFormViewModel(activityId: Long?, prefillStart: LocalDateTime?, ...)`: `val state: StateFlow<ActivityFormState>`, `fun setTitle(s: String)`, `fun setType(t: ActivityType)`, `fun setDateChoice(c: DateChoice)`, `fun setDate(d: LocalDate)`, `fun shiftStart(minutes: Int)`, `fun setDuration(min: Int)`, `fun setRepeat(r: Repeat)`, `fun setRemind(on: Boolean)`, `fun setCopyToGoogle(on: Boolean)`, `fun save()`, `fun delete()`.

- [ ] **Step 1: Escribir las pruebas que fallan**

```kotlin
class ActivityFormViewModelTest {
    // FixedClock(2026-11-18T10:40); mañana hay "Ejercicio en casa" 19:00–20:00
    @Test fun newFormStartsAtNextQuarterHour() { assertEquals(LocalTime.of(10, 45), newVm().state.value.start) }
    @Test fun prefillFromFreeSlot() { assertEquals(LocalTime.of(15, 0), newVm(prefill = LocalDateTime.of(2026, 11, 18, 15, 0)).state.value.start) }
    @Test fun appointmentTurnsGoogleCopyOnUnlessTouched() {
        val vm = newVm(); vm.setType(ActivityType.APPOINTMENT); assertTrue(vm.state.value.copyToGoogle)
        vm.setCopyToGoogle(false); vm.setType(ActivityType.WORK); vm.setType(ActivityType.APPOINTMENT)
        assertFalse(vm.state.value.copyToGoogle)
    }
    @Test fun liveConflict() = runTest {
        val vm = newVm(); vm.setDateChoice(DateChoice.TOMORROW); vm.shiftStart(/* hasta 19:00 */ 495); vm.setDuration(60)
        assertEquals("Ejercicio en casa", vm.state.first { it.conflict != null }.conflict!!.activity.title)
    }
    @Test fun emptyTitleBlocksSave() { val vm = newVm(); vm.save(); assertEquals("Escribe qué vas a hacer", vm.state.value.titleError); assertFalse(vm.state.value.saved) }
    @Test fun saveAndEditRoundTrip() = runTest { /* guardar "Dentista" Cita mañana 15:00 30 min → repo lo tiene; abrir con su id → isEdit y campos iguales; conflicto no se reporta contra sí mismo */ }
    @Test fun saveWithConflictIsAllowed() = runTest { /* con conflicto, save() guarda igual */ }
}
```

- [ ] **Step 2: Ejecutar y ver que fallan** — `--tests "app.brujula.activities.form.*"` → FAIL.

- [ ] **Step 3: Implementar ViewModel y pantalla**

Pantalla según el tablero "Nueva actividad (manual)": título `Nueva actividad` (o `Editar actividad`), campo `¿Qué vas a hacer?` con placeholder `Ej. Dentista con la Dra. Ruiz`, chips de tipo (`Trabajo, Ejercicio, Cita, Personal, Hábito`; elegir `Hábito` navega a `Routes.HABIT_FORM`), selector `Hoy / Mañana · {día} / Otro día` (este abre un `DatePicker`), inicio con −/+ de 15 min, duración `30 min, 1 h, 1 h 30, 2 h, Otra`, línea `Termina a las {hora} · Repetir: {repetición}`, estado de choque en vivo (`Libre a esa hora. No choca con nada.` / `Choca con {título}.`), interruptores `Avisarme 15 min antes` y `Copiar a Google Calendar` (con subtítulo `Las citas se copian por defecto` o `Los bloques de rutina se quedan en Brújula`; en esta fase solo se guarda la preferencia: la copia real llega en la Fase 2), botón `Guardar actividad`; en edición, también `Eliminar`. El botón de micrófono de arriba a la derecha queda oculto en esta fase.

- [ ] **Step 4: Ejecutar y ver que pasan** — mismo comando → PASS.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat: manual activity form with live conflict check"`

---

### Task 8: Hábitos (pantalla y formulario)

**Files:**
- Create: `habits/HabitsViewModel.kt`, `habits/HabitsScreen.kt`, `habits/HabitFormScreen.kt` (+ `HabitFormViewModel.kt`)
- Test: `habits/HabitsViewModelTest.kt`, `habits/HabitFormViewModelTest.kt`

**Interfaces:**
- Consumes: `HabitRepository`, `ActivityRepository`, `RoutineRepository`, `timesFor`, `exerciseStreak`, `Clock`.
- Produces:
  - `data class HabitCard(val habit: Habit, val count: Int, val nextAt: LocalDateTime?, val slots: List<Pair<LocalTime, Boolean>>)` (`slots` solo para `AtTimes`: hora y si ya se hizo esa vez)
  - `data class HabitsUiState(val cards: List<HabitCard>, val exerciseStreak: Int, val weekDone: List<Boolean>)` (`weekDone` lunes→domingo de la semana actual)
  - `HabitsViewModel`: `fun add(habitId: Long)`, `fun undo(habitId: Long)`
  - `HabitFormViewModel(habitId: Long?)`: estado con `name, icon, mode (EVERY | AT_TIMES), intervalMin, times, dailyTarget, days`; `fun save()`; validaciones: nombre vacío → `Ponle un nombre al hábito`; `intervalMin` entre 15 y 480; al menos una hora en `AT_TIMES`; al menos un día.

- [ ] **Step 1: Escribir las pruebas que fallan**

```kotlin
class HabitsViewModelTest {
    @Test fun nextReminderAndCounts() = runTest { /* 10:40 con agua 3/8 → nextAt == 11:15, count == 3 */ }
    @Test fun balmSlotsMarkedInOrder() = runTest { /* 1 registro de bálsamo → slots [(9:00,true),(13:00,false),(17:00,false),(21:00,false)] */ }
    @Test fun streakFromExerciseCompletions() = runTest { /* ejercicio hecho lun, mar, mié → exerciseStreak == 3, weekDone == [t,t,t,f,f,f,f] */ }
}
class HabitFormViewModelTest {
    @Test fun validations() { /* nombre vacío, intervalo 10, sin horas, sin días → errores; caso válido guarda Habit con schedule correcto */ }
}
```

- [ ] **Step 2: Ejecutar y ver que fallan** — `--tests "app.brujula.habits.*"` → FAIL.
- [ ] **Step 3: Implementar** — pantalla según el tablero "Hábitos y rachas": anillo de agua animado con `+1 vaso` y texto `Próximo aviso a las {hora}. Cada {n} min mientras estás despierto.`; bálsamo con botones por horario; tarjeta de racha `{n} días seguidos` con la semana L–D; botón `Nuevo hábito` → formulario. (La sugerencia de Claude del boceto queda para la Fase 3.) Mantener presionado un hábito → editar o eliminar.
- [ ] **Step 4: Ejecutar y ver que pasan** — mismo comando → PASS.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat: habits screen, habit editor and exercise streak"`

---

### Task 9: Tu rutina (ajustes) y tema aplicado

**Files:**
- Create: `settings/RoutineViewModel.kt`, `settings/RoutineScreen.kt`
- Modify: `ui/MainActivity.kt` (tema según `resolveDark` y la rutina, recalculado cada minuto)
- Test: `settings/RoutineViewModelTest.kt`

**Interfaces:**
- Consumes: `RoutineRepository`, `resolveDark`, `ReminderSync`.
- Produces: `RoutineViewModel`: `val routine: StateFlow<Routine>`, `fun shiftWake(min: Int)`, `fun shiftBed(min: Int)`, `fun setFlexible(on: Boolean)`, `fun setGoal(range: IntRange)`, `fun setThemeMode(m: ThemeMode)`.

- [ ] **Step 1: Escribir las pruebas que fallan**

```kotlin
class RoutineViewModelTest {
    @Test fun wakeAndBedWrapAroundMidnight() = runTest { /* shiftBed(+15) desde 23:45 → 00:00; shiftBed(-15) desde 00:00 → 23:45 */ }
    @Test fun goalOptions() = runTest { /* setGoal(300..360) persiste; opciones válidas: 300..360, 360..420, 420..480 */ }
    @Test fun changesTriggerResync() = runTest { /* FakeReminderSync.calls aumenta tras shiftWake */ }
}
```

- [ ] **Step 2: Ejecutar y ver que fallan** — `--tests "app.brujula.settings.*"` → FAIL.
- [ ] **Step 3: Implementar** — pantalla según el tablero "Tu rutina": `Me levanto` / `Me acuesto` con −/+, `Trabajo flexible` (subtítulo `Preferido: 8–12 y 2–6`), meta `5–6 h · 6–7 h · 7–8 h`, sección `Tema de Brújula` con `Claro · Oscuro · Automático` (el cambio se ve al instante). Las secciones de calendarios y clave de Claude llegan en la Fase 2.
- [ ] **Step 4: Ejecutar y ver que pasan** — mismo comando → PASS.
- [ ] **Step 5: Commit** — `git add -A && git commit -m "feat: routine settings and light/dark/auto theme"`

---

### Task 10: Apertura animada

**Files:**
- Create: `res/drawable/avd_splash_icon.xml`, `splash/IntroScreen.kt`
- Modify: `res/values/themes.xml` (`Theme.Brujula.Starting`), `AndroidManifest.xml`, `ui/MainActivity.kt`
- Test: `splash/IntroTimingsTest.kt`

**Interfaces:**
- Produces: `object IntroTimings { const val SYSTEM_MS = 800; fun composeMs(reduceMotion: Boolean): Int; fun totalMs(reduceMotion: Boolean): Int = SYSTEM_MS + composeMs(reduceMotion) }`; `@Composable fun IntroScreen(reduceMotion: Boolean, onFinished: () -> Unit)`.

- [ ] **Step 1: Escribir la prueba que falla**

```kotlin
class IntroTimingsTest {
    @Test fun withinBudget() {
        assertTrue(IntroTimings.totalMs(reduceMotion = false) <= 2000)
        assertTrue(IntroTimings.totalMs(reduceMotion = true) <= 1200)
    }
}
```

- [ ] **Step 2: Ejecutar y ver que falla** — `--tests "app.brujula.splash.*"` → FAIL.
- [ ] **Step 3: Implementar** — según el tablero "ELEGIDA · Onda expansiva sobre fondo oscuro" y la spec §5.9: tema de arranque con `windowSplashScreenBackground = #0F0D1F`, ícono animado (AVD ≤ 800 ms: el cuadrado `#4B3BFF` crece desde un punto con rebote y la aguja gira 360°), `postSplashScreenTheme` al tema normal. `IntroScreen` continúa sin corte sobre `#0F0D1F`: dos ondas (`#7B6CFF` y `#FFB020`) que se expanden y desvanecen, la aguja se asienta, y suben `Brújula` (Bricolage 46 sp, 800, `#F1F0FF`) y `Hacia dónde va tu día` (DM Sans 17 sp, `#A9A5C9`) desde detrás de una máscara. Con reducir movimiento: sin animación, se muestra el estado final ~400 ms. Siempre sobre fondo oscuro, sin importar el tema.
- [ ] **Step 4: Ejecutar y ver que pasa** — mismo comando → PASS.
- [ ] **Step 5: Revisar en el teléfono** — abrir en frío varias veces. Expected: se ve como una sola animación, sin parpadeo blanco, y no tarda más de ~2 s.
- [ ] **Step 6: Commit** — `git add -A && git commit -m "feat: animated launch with system splash and intro continuation"`

---

### Task 11: Verificación final de la fase

- [ ] **Step 1:** `.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → todo en verde, sin errores de lint.
- [ ] **Step 2:** En el teléfono, recorrido completo: apertura → Hoy → crear "Dentista" mañana 15:00 desde un hueco → aparece y avisa 15 min antes → marcar agua desde una notificación con el teléfono bloqueado → cambiar a oscuro y a automático → reiniciar el teléfono y confirmar que el siguiente aviso suena.
- [ ] **Step 3:** Commit de cualquier ajuste y etiqueta `git tag fase-1`.

---

## Fases siguientes (planes aparte, se escriben al terminar esta)

- **Fase 2 · Calendarios + Claude + voz:** importar Google y Outlook (solo lectura, elegir calendarios) y mostrarlos en Hoy como "Fijo"; copiar citas a Google Calendar con marca de Brújula; clave de Claude cifrada; cliente de la API con acciones estructuradas; clasificación de eventos como trabajo o no (con corrección que se recuerda); pantalla de dictado (Android SpeechRecognizer); botón central = voz y mantener presionado = formulario; textos pendientes sin internet.
- **Fase 3 · Que Claude arme el día:** propuesta del día con etiquetas Nuevo/Movido/Fijo/Lo dictaste y Aceptar/Ajustar; "¿Armamos mañana?" con aviso nocturno (22:45) y resumen matutino; aviso para dormir; pantalla Semana; widget con ventanita flotante de dictado; sugerencias de Claude en Hábitos.
