# Brújula · Fase 2B (Claude y voz) — Plan de implementación

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Que el usuario le dicte a Brújula ("mañana a las 7 de la tarde ejercicio una hora", "recuérdame estirarme cada dos horas", "dentista el jueves a las 3") y Claude lo convierta en actividades o hábitos que se agendan directo con opción de deshacer; además, que Claude clasifique solo los eventos importados como "trabajo" o "no trabajo".

**Architecture:** La clave de la API de Claude se guarda cifrada con Android Keystore. Un `ClaudeGateway` (SDK oficial `com.anthropic:anthropic-java`) hace una sola llamada por dictado con **salida estructurada** (`outputConfig(Clase)`), recibiendo una lista de acciones tipadas. Un `PromptBuilder` puro arma el contexto (fecha/hora, tipo de día, rutina, ocupado de hoy y mañana, hábitos). Un `ActionExecutor` valida y aplica las acciones según la regla C de la spec §4 (lo dictado se agenda directo con deshacer; nada externo se toca). La pantalla de dictado usa `SpeechRecognizer` de Android en español, con un campo de texto como alternativa. Sin internet, el texto queda pendiente y se interpreta al volver la conexión.

**Tech Stack:** el de las fases anteriores + `com.anthropic:anthropic-java` (versión actual estable; la skill `claude-api` documenta 3.3.0) + `android.speech.SpeechRecognizer` + Android Keystore (`AES/GCM/NoPadding`).

**Spec:** `docs/superpowers/specs/2026-11-18-brujula-design.md` — §4 (regla C), §5.2 (dictado), §8 (Claude: acciones estructuradas, clave cifrada), §9, §10 (sin internet; clasificación de trabajo).

**Referencia de API:** antes de escribir código del SDK, leer la skill `claude-api` (`java/claude-api/README.md` y `tool-use.md` § Structured Output) en `C:/Users/juesn/AppData/Local/Temp/claude/bundled-skills/2.1.327/2ea7d793618a426ddd4f1cd73d09d2e1/claude-api/`. No adivinar nombres del SDK.

## Global Constraints

- Todo lo de las fases anteriores sigue vigente (incluida la Fase 2A: eventos externos de solo lectura).
- **Modelos (elegidos por el usuario: "el más adecuado" para cada tarea):**
  - Dictado → `claude-opus-5-5` (la precisión con fechas y horas es lo crítico; pocas llamadas). No enviar `thinking` (siempre activo; `disabled` da 400). Esfuerzo `OutputConfig.Effort.LOW`.
  - Clasificación de eventos importados → `claude-haiku-5-5` (sí/no en lote, barato y rápido; un error se corrige con un toque). Esfuerzo `LOW` (su predeterminado es `medium`). Haiku 5.5 no tiene fallback del lado del servidor: un rechazo se trata como "sin clasificar".
  - Ambos: `maxTokens` 4096, sin prefill, sin `temperature`. Los ids de modelo van en una sola constante por uso (`ClaudeModels.DICTATION`, `ClaudeModels.CLASSIFICATION`).
- **Fallbacks ante rechazo (dictado con Opus 5.5):** si la versión del SDK expone el parámetro `fallbacks` del endpoint beta (beta `server-side-fallback-2026-07-01`, forma `"default"`), usarlo; si no, tratar `stopReason == REFUSAL` mostrando `No pude interpretar eso. Prueba diciéndolo de otra forma.` Nunca leer el contenido sin revisar `stopReason`.
- **La clave nunca va en el código, en git ni en logs.** Se guarda cifrada (Keystore) y solo se descifra para construir el cliente.
- **Datos que se envían a Claude:** el texto dictado, la fecha/hora local y zona, tipo de día de hoy y mañana, rutina (horas), títulos y horas de lo ocupado hoy y mañana (propio y externo), nombres de hábitos. Nada más.
- Acciones que Claude puede devolver en esta fase: `create_activity`, `create_habit`, `need_more_info`, `unsupported`. Reorganizar el día o mover cosas existentes es Fase 3 → `unsupported` con mensaje.
- Validación local de toda acción antes de aplicarla: fechas entre hoy y +365 días, hora válida, duración 5–720 min, intervalo de hábito 15–480, horas fijas válidas; lo inválido se descarta y se informa.
- Copia (español): botón central = dictar; mantener presionado = formulario. Estados: `Escuchando…`, `Claude está entendiendo…`, `Agendado`, botón `Deshacer`, `Listo`. Sin clave: `Para dictar, agrega tu clave de Claude en Tu rutina.` Sin internet: `Sin conexión. Lo interpreto en cuanto vuelva internet.` Sección en Tu rutina: `Claude` con `Clave de la API`, `Probar`, estado `Conectado` / `La clave no funciona`.
- Commits con el trailer de dos líneas (`Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>` / `Claude-Session: https://claude.ai/code/session_01XN8G1c95yQpddiVFPfCNKU`).

## Review Focus

1. **Fechas relativas cerca de la medianoche:** "mañana" dicho a las 00:30 es el día lógico siguiente al actual (el día lógico cambia a las 04:00). El prompt da el día lógico explícito. → Task 2.
2. **Claude devuelve algo raro** (JSON fuera de rango, fecha pasada, duración 0, tipo desconocido): no se agenda nada inválido y el usuario ve qué pasó. → Task 2 y Task 3.
3. **Sin clave, clave inválida, sin internet o error 429/500:** la app no se cae; el texto no se pierde (queda pendiente o se puede reintentar). → Tasks 1, 2 y 4.
4. **Deshacer** borra exactamente lo que creó ese dictado (incluida la copia en Google si hubo). → Task 3.
5. **Clasificación automática nunca pisa una marca hecha por el usuario.** → Task 5.

---

### Task 1: Clave de Claude cifrada y sección "Claude" en Tu rutina

**Files:** Create `claude/SecretCipher.kt` (interfaz + `KeystoreSecretCipher`), `claude/ClaudeKeyStore.kt`, `claude/ClaudeGateway.kt` (interfaz + `fun testKey(): KeyTestResult`), `claude/AnthropicClaudeGateway.kt` (SDK); Modify `app/build.gradle.kts` (dependencia), `BrujulaApp.kt` (AppContainer: `claudeKeyStore`, `claudeGateway` reemplazables), `settings/RoutineViewModel.kt`, `settings/RoutineScreen.kt`. Test: `claude/ClaudeKeyStoreTest.kt` (con `FakeSecretCipher`), `settings/RoutineViewModelTest.kt`.

**Interfaces:** `interface SecretCipher { fun encrypt(plain: String): String; fun decrypt(cipherText: String): String? }`; `class ClaudeKeyStore(dataStore, cipher) { val hasKey: Flow<Boolean>; suspend fun save(key: String); suspend fun clear(); suspend fun read(): String? }` (guarda solo el texto cifrado en Base64; si el descifrado falla → `null` y se borra). `enum class KeyTestResult { OK, INVALID, NETWORK, ERROR }`; `testKey()` hace una petición mínima (`maxTokens` 16, texto `ok`) y mapea `AuthenticationException`/401 → INVALID, sin red → NETWORK. RoutineViewModel: `fun saveClaudeKey(key: String)` (recorta espacios; vacío = borrar), `fun testClaudeKey()`, estado `claude: ClaudeSectionState(hasKey, status)`. Pantalla: campo de clave enmascarado (mostrar solo los últimos 4 caracteres tras guardar), botón `Probar`, enlace de texto `Consigue tu clave en platform.claude.com` (sin abrir navegador en pruebas).

- [ ] Pruebas que fallan: `ClaudeKeyStoreTest` (guardar/leer/borrar; lo almacenado no contiene la clave en claro; descifrado fallido → null y limpia); `RoutineViewModelTest` (guardar con espacios, vacío borra, probar con `FakeClaudeGateway` OK/INVALID/NETWORK → estado).
- [ ] FAIL → implementar → suite + `:app:assembleDebug` PASS → commit `feat: encrypted Claude API key and settings section`.

### Task 2: Interpretar un dictado (prompt, llamada y validación)

**Files:** Create `claude/DictationContext.kt`, `claude/PromptBuilder.kt`, `claude/DictationActions.kt` (clases de salida estructurada + dominio), `claude/DictationInterpreter.kt`, `claude/ActionValidator.kt`; Modify `claude/AnthropicClaudeGateway.kt`. Test: `claude/PromptBuilderTest.kt`, `claude/ActionValidatorTest.kt`, `claude/DictationInterpreterTest.kt` (gateway falso con respuestas enlatadas).

**Interfaces:**
- `data class DictationContext(val now: LocalDateTime, val zone: ZoneId, val logicalToday: LocalDate, val todayType: DayType, val tomorrowType: DayType, val routine: Routine, val busyToday: List<BusyItem>, val busyTomorrow: List<BusyItem>, val habitNames: List<String>)`, `data class BusyItem(val title: String, val start: LocalDateTime, val end: LocalDateTime, val external: Boolean)`.
- `object PromptBuilder { fun system(): String; fun user(ctx: DictationContext, text: String): String }` — `system()` es estable (cacheable): rol, reglas (agendar lo dicho; si falta la hora, proponer una razonable dentro de la vigilia y sin chocar; "cita"/"reunión"/"dentista"/"médico" → tipo Cita con copia a Google; nunca mover ni borrar nada; responder en español; frases incompletas → `need_more_info` con la pregunta; reorganizar el día → `unsupported`). `user()` lleva el contexto variable y el texto entre etiquetas `<dictado>…</dictado>`.
- Salida estructurada (clase Kotlin/Java con `@JsonPropertyDescription`): `DictationResult(actions: List<DictationAction>, reply: String)`, `DictationAction(type: String /* create_activity|create_habit|need_more_info|unsupported */, title: String?, activityType: String? /* WORK|EXERCISE|APPOINTMENT|PERSONAL */, date: String? /* yyyy-MM-dd */, start: String? /* HH:mm */, durationMin: Int?, repeat: String? /* NONE|DAILY|WEEKDAYS|WORKDAYS|WEEKLY */, copyToGoogle: Boolean?, habitMode: String? /* EVERY|AT_TIMES|BEFORE_BED */, intervalMin: Int?, times: List<String>?, beforeBedMin: Int?, workOnly: Boolean?, question: String?)`.
- `ActionValidator.validate(a: DictationAction, ctx): Validated` → `sealed interface Validated { data class Activity(...) ; data class Habit(...) ; data class Question(val text: String); data class Unsupported(val text: String); data class Invalid(val reason: String) }`.
- `class DictationInterpreter(gateway: ClaudeGateway, contextProvider: suspend () -> DictationContext) { suspend fun interpret(text: String): InterpretOutcome }`; `sealed interface InterpretOutcome { data class Ok(val items: List<Validated>, val reply: String); object NoKey; object Offline; data class Refused(val message: String); data class Failed(val message: String, val retryable: Boolean) }`. Mapear excepciones del SDK por tipo (no por texto): 401 → NoKey/clave inválida, 429/5xx/conexión → Failed(retryable=true).
- [ ] Pruebas que fallan: `PromptBuilderTest` (el user prompt incluye el día lógico explícito, el tipo de día y lo ocupado con horas 24 h; a las 00:30 del jueves dice "hoy (día lógico) es miércoles 18 de noviembre"); `ActionValidatorTest` (fecha pasada, duración 0, 800 min, intervalo 5, tipo desconocido → Invalid; cita válida → Activity con copyToGoogle); `DictationInterpreterTest` (respuesta enlatada con dos acciones → Ok con dos Validated; sin clave → NoKey; excepción de conexión → Failed retryable; stopReason refusal → Refused).
- [ ] FAIL → implementar (leer la skill para el builder de salida estructurada y el manejo de `stopReason`) → suite PASS → commit `feat: interpret dictation with Claude structured output`.

### Task 3: Aplicar acciones con deshacer

**Files:** Create `claude/ActionExecutor.kt`; Modify `activities/ActivityRepository.kt` / `habits/HabitRepository.kt` si hace falta. Test: `claude/ActionExecutorTest.kt`.

**Interfaces:** `class ActionExecutor(activityRepo, habitRepo, googleCopySync, reminderSync) { suspend fun apply(items: List<Validated>): ApplyResult; suspend fun undo(token: UndoToken) }`; `data class ApplyResult(val created: List<CreatedItem>, val messages: List<String>, val undo: UndoToken?)`; `data class CreatedItem(val label: String /* "Ejercicio · jue 19 nov · 19:00–20:00" */, val activityId: Long?, val habitId: Long?)`. `apply` crea en una transacción, luego copia a Google (si corresponde) y resincroniza recordatorios; `undo` borra exactamente lo creado (y la copia en Google). Hábitos creados por dictado: grupo `ESSENTIAL` salvo que el texto los ubique como pausa (`workOnly = true` → `WELLNESS`).
- [ ] Pruebas que fallan: aplicar una actividad y un hábito → ambos existen, resync llamado una vez, etiquetas en español; deshacer → ambos desaparecen y la copia de Google se borra (fake); `Question`/`Unsupported`/`Invalid` no crean nada y pasan su mensaje.
- [ ] FAIL → implementar → suite PASS → commit `feat: apply dictated actions with undo`.

### Task 4: Pantalla de dictado, botón central y pendientes sin internet

**Files:** Create `voice/SpeechInput.kt` (interfaz + implementación con `SpeechRecognizer`, `es-CO` con respaldo `es`), `voice/VoiceViewModel.kt`, `voice/VoiceScreen.kt`, `claude/PendingDictations.kt`; Modify `ui/BottomBar.kt` (tap = voz, mantener = formulario), `ui/BrujulaNavHost.kt` (ruta `voice`), `AndroidManifest.xml` (`RECORD_AUDIO`, `<queries>` para `RecognitionService`), `BrujulaApp.kt` (procesar pendientes al iniciar y al recuperar red con `ConnectivityManager.registerDefaultNetworkCallback`). Test: `voice/VoiceViewModelTest.kt`, `claude/PendingDictationsTest.kt`.

**Interfaces:** `VoiceViewModel` con estados `Listening(partial)`, `Thinking`, `Result(created, reply, undoAvailable)`, `NeedsMore(question)`, `Error(message, retry)`, `Saved offline`; `fun start()`, `fun stop()`, `fun submitText(text)` (campo `Escribir en vez de hablar`), `fun undo()`, `fun retry()`. `PendingDictations`: cola en DataStore (texto + instante), `add`, `drain(interpreter, executor)` que al terminar publica una notificación `Agendé lo que dictaste sin conexión: {etiquetas}` en el canal `actividades`. Sin permiso de micrófono → pedirlo; negado → solo campo de texto. Diseño según el tablero "Dictar por voz" (`C:/Users/juesn/AppData/Local/Temp/claude/05a7d0fa803c1f3a/739e5df5-de06-4286-b8a7-cdc6e26ed341/scratchpad/agenda-design/project/Voz.dc.html`).
- [ ] Pruebas que fallan (con `FakeSpeechInput`, interpreter y executor falsos): texto final → Thinking → Result con etiquetas y deshacer; `need_more_info` → NeedsMore con la pregunta; sin red → se guarda pendiente y muestra el mensaje; `drain` procesa la cola en orden y la vacía solo si se aplicó; sin clave → mensaje de clave.
- [ ] FAIL → implementar → suite + `:app:assembleDebug :app:lintDebug` PASS → commit `feat: voice dictation screen with offline queue`.

### Task 5: Clasificación automática de eventos importados

**Files:** Modify `data/db/Entities.kt` (+ `source TEXT NOT NULL DEFAULT 'USER'` en `work_classifications`), `data/db/BrujulaDatabase.kt` (v4, AutoMigration 3→4), `calendar/WorkClassificationRepository.kt`, `today/TodayViewModel.kt` (disparar clasificación de claves desconocidas, como máximo una vez por clave). Create `claude/EventClassifier.kt`. Test: `data/MigrationTest.kt` (3→4), `claude/EventClassifierTest.kt`.

**Interfaces:** `class EventClassifier(gateway) { suspend fun classify(titles: List<String>): Map<String, Boolean> }` (una llamada con `claude-haiku-5-5` para hasta 30 títulos, salida estructurada `List<{title, isWork}>`, esfuerzo bajo). `WorkClassificationRepository.setAuto(key, isWork)` solo escribe si no existe una entrada `USER`; `set(...)` (usuario) siempre gana y marca `USER`. Sin clave o sin red: no hace nada (los eventos siguen como "no trabajo" hasta clasificarse).
- [ ] Pruebas que fallan: migración 3→4 conserva datos y `source = USER` en filas previas; `setAuto` no pisa `USER`; el clasificador manda solo claves desconocidas y guarda el resultado; un fallo del gateway no lanza.
- [ ] FAIL → implementar → suite PASS → commit `feat: auto-classify imported events as work with Claude`.

### Task 6: Verificación en el teléfono

- [ ] Respaldo de datos; instalar; el usuario agrega su clave en Tu rutina → `Probar` dice `Conectado`.
- [ ] Dictar "mañana a las 7 de la tarde ejercicio una hora" → `Agendado` con la etiqueta correcta; `Deshacer` lo quita. Dictar "dentista el jueves a las 3" → cita con copia en Google Calendar. Modo avión → dictar → mensaje sin conexión → quitar modo avión → notificación de agendado.
- [ ] Commit de ajustes si los hubo.
