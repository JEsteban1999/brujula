# Brújula — Diseño de la app

*Fecha: 18 de noviembre de 2026 · Estado: borrador para aprobación*
*Bocetos visuales: https://claude.ai/artifact/UTuceHbQngnPkec6wo8JQD (lienzo de Claude Design)*

## 1. Qué es y por qué

Brújula es una app de Android **de uso personal** (una sola persona, su propio teléfono, sin publicar en tiendas) que organiza el día y recuerda hábitos saludables, con Claude como asistente que interpreta lo que se le dicta por voz y propone cómo repartir el tiempo.

Resuelve tres problemas del usuario, con el mismo peso:

1. **Se le olvidan las cosas** → recordatorios confiables de bloques del día y de hábitos pequeños (tomar agua, bálsamo labial, y los que vaya agregando).
2. **Agendar le da pereza** → agregar algo debe costar casi nada: hablarle a la app ("mañana a las 7 de la tarde ejercicio una hora") y que quede agendado.
3. **Le cuesta repartir el tiempo** → Claude no solo anota: propone cómo armar el día según calendarios, rutina, metas y hábitos.

Frase guía: *un asistente que te arma el día, más que un calendario con voz.*

Contexto del usuario: está empezando a hacer ejercicio en casa y quiere usar la app para volverlo costumbre.

## 2. Metas, no-metas y anti-metas

**Metas**
- Recordatorios de hábitos que suenan a su hora aunque la app esté cerrada, solo en horas de vigilia, y que se marcan como hechos desde la propia notificación.
- Dictado por voz desde la app y desde un widget de pantalla de inicio, interpretado por Claude (forma principal de agregar cosas), más un formulario para crear o editar actividades a mano.
- Traer a Brújula lo que el usuario ya tiene en Google Calendar y Outlook (solo lectura), para que Claude organice alrededor de eso. Lo que se crea en Brújula vive en Brújula; solo las citas dictadas se duplican en Google Calendar (sección 8).
- Que Claude proponga el día (la noche anterior o a pedido) y el usuario lo acepte o ajuste.
- Seguimiento simple: vasos de agua, veces de bálsamo, racha de ejercicio, horas de trabajo frente a la meta.

**No-metas (fuera de esta versión)**
- iPhone, web o escritorio.
- Varios usuarios, cuentas, servidor propio o sincronización entre dispositivos (más allá de lo que ya sincroniza Google Calendar).
- Publicar en Google Play.
- Integración con apps de tareas (Todoist, Notion, TickTick, Google Tasks). Se diseña para poder sumarlas después.
- "Ok Google, dile a Brújula…" (Android lo restringe y no es confiable).
- Botón de dictado en la barra de accesos rápidos (posible más adelante).

**Anti-metas (sería un fracaso aunque "funcione")**
- Recordatorios que no suenan, suenan tarde o suenan de madrugada.
- Que Claude mueva o borre algo importante sin preguntar, sobre todo eventos de Google/Outlook que ven otras personas.
- Que agregar algo por voz requiera más pasos o correcciones que escribirlo a mano.
- Que la app se sienta lenta al abrir (la apertura animada no debe pasar de ~2 s).

## 3. El usuario y su rutina (datos de partida)

- Trabaja desde casa (se levanta casi a la hora de empezar). *Deducido; el usuario no lo corrigió.*
- Trabajo preferido: 8:00–12:00 y 14:00–18:00, **flexible**: no siempre cumple el horario, hace otras cosas en medio y podría hacer ejercicio dentro de ese horario.
- **Meta de trabajo: 6–7 horas al día.** Es un rango, no un mínimo estricto: a veces compensa dejando a Claude trabajando en su PC. Solo avisar si el día va claramente por debajo (p. ej. < 5 h).
- Duerme aprox. 00:00–01:00 → 08:00–08:30.
- Calendarios en uso: Google Calendar y Outlook (ambos ya agregados en el teléfono, o por agregar). Podría usar otros después. **Ninguno de los dos es específicamente de trabajo**: en ambos puede haber reuniones y entrevistas, y también cosas personales.
- Teléfono Android. Desarrollo en una PC con Windows.

Todos estos valores son configurables en la pantalla "Tu rutina".

## 4. Cómo se comporta Claude (regla "C" acordada)

- **Lo que el usuario dicta se agenda directo.** Llega una confirmación corta con opción de **Deshacer**.
- **Cuando Claude quiere reorganizar** (mover cosas existentes, armar el día completo, resolver choques) **propone primero**; el usuario acepta, pide ajustes o descarta.
- **Los eventos que vienen de Google/Outlook son de solo lectura para Brújula: nunca los mueve, edita ni borra.** Claude los usa para saber qué huecos hay. Si un choque solo se resuelve moviendo uno de ellos, Claude lo sugiere como texto ("podrías mover la llamada de las 3 a las 4 en tu Outlook") y el usuario decide si lo hace él mismo.
- Las citas que Brújula duplicó en Google Calendar siguen siendo de Brújula: se editan desde Brújula y la copia en Google se actualiza.
- Si lo dictado es ambiguo (p. ej. "el jueves" sin hora), Claude **propone** un horario razonable en lugar de preguntar; el usuario corrige si no le sirve. Si la frase queda incompleta ("recuérdame llevar…"), Claude agenda lo que entendió y pide completar lo que falta.

## 5. Funcionalidades

### 5.1 Hoy (pantalla principal)
- Fecha, horas de trabajo del día frente a la meta (barra), tarjeta con el último comentario/cambio de Claude.
- Contadores rápidos de agua (x/8) y bálsamo (x/4) con botón "+".
- Línea de tiempo del día: bloques propios y eventos de calendarios, con su origen ("Desde Outlook", "Desde Google Calendar"), bloque actual resaltado ("AHORA"), tocar un bloque lo marca como hecho.
- Barra inferior: Hoy · Semana · **botón de micrófono central** · Hábitos · Ajustes.

### 5.2 Dictado por voz
- Desde el botón central de la app y desde el widget.
- Estados: escuchando (onda animada + transcripción en vivo) → interpretando ("Claude está entendiendo…", mostrando cómo leyó fecha/hora/duración) → resultado ("Agendado", con Deshacer).
- El reconocimiento de voz lo hace Android (gratis, buen español); el texto resultante se envía a Claude.
- Ejemplos que debe entender: crear evento ("mañana a las 7 ejercicio una hora"), crear hábito ("recuérdame estirarme cada 2 horas"), pedir plan ("arma mi día de mañana"), cambios ("hoy no voy a entrenar").

### 5.2b Crear actividad a mano (formulario)
La voz es la forma principal de agregar cosas; el formulario es la alternativa para cuando no se puede o no se quiere hablar, o para ajustar detalles. No usa a Claude (funciona sin internet).
- **Cómo se llega:** desde la pantalla de dictado ("Prefiero escribir"), manteniendo presionado el botón de micrófono, o tocando un hueco vacío de la línea de tiempo en Hoy (abre el formulario con esa hora ya puesta).
- **Campos:** qué vas a hacer (texto); tipo (Trabajo, Ejercicio, Cita, Personal, Hábito); cuándo (Hoy / Mañana / Otro día con selector de fecha); hora de inicio (pasos de 15 min) y duración (30 min, 1 h, 1 h 30, 2 h u otra); repetir (no, todos los días, lunes a viernes, cada semana, personalizado); avisarme N min antes; **copiar a Google Calendar** (activado por defecto solo si el tipo es Cita, según la sección 8).
- **Revisión de choques en vivo:** mientras se llena, el formulario indica "Libre a esa hora" o "Choca con [evento] (origen)". Si choca, se puede guardar igual o pedir a Claude que proponga otro horario.
- Si el tipo es Hábito, el formulario cambia a los campos de frecuencia del hábito (cada N horas, N veces al día, días de la semana).
- Botón con acceso rápido al dictado ("mejor dictarlo") arriba a la derecha.
- Las actividades creadas a mano se comportan igual que las dictadas: aparecen en Hoy, generan recordatorios y se pueden editar o borrar tocándolas (el mismo formulario sirve para editar).
- Boceto: tableros "Nueva actividad (manual)" claro y oscuro.

### 5.3 Propuesta de Claude ("arma mi día")
- Muestra el día propuesto con totales (trabajo, ejercicio, sueño), una explicación corta del porqué, y cada bloque etiquetado: **Nuevo**, **Movido**, **Fijo** (calendario externo o almuerzo), **Lo dictaste**.
- Botones: **Aceptar plan** (aplica todo, animación de confirmación) y **Ajustar** (abre el dictado para pedir cambios: "pasa el gym a la tarde").
- Aceptar un plan solo cambia cosas de Brújula; los eventos de Google/Outlook aparecen como **Fijo** y nunca se tocan (ver sección 4).
- Claude respeta: eventos externos fijos, la meta de 6–7 h de trabajo, horas de sueño, el horario fijo de ejercicio (ver 5.5) y los hábitos.

### 5.4 Planificación nocturna ("¿Armamos mañana?")
- Aviso diario por la noche (por defecto 22:45, configurable).
- Pantalla con: resumen de cómo fue el día (horas de trabajo, agua, ejercicio hecho/no), lo que ya hay mañana, y sugerencia de hora para dormir con interruptor "avisarme a las 23:30".
- Botones: "Que Claude arme mi día" (→ Propuesta), "Agregar algo" (→ voz), "Mañana lo veo".
- A la mañana siguiente, al despertar, notificación con el resumen del día ya armado.

### 5.5 Hábitos y seguimiento
- **Agua:** recordatorio cada ~90 min solo entre la hora de levantarse y la de acostarse; anillo de progreso x/8; "+1 vaso".
- **Bálsamo labial:** 4 veces al día en horarios fijos (por defecto 9:00, 13:00, 17:00, 21:00).
- **Ejercicio en casa:** horario fijo por defecto en la tarde (consistencia antes que "el hueco perfecto"); si un día se complica, Claude propone moverlo ese día, sin borrarlo. Racha de días seguidos y semana L–D.
- **Hábitos nuevos** se agregan por voz o a mano, con frecuencia (cada N horas, N veces al día, días de la semana).
- Recordar **y marcar**: cada aviso trae botones en la notificación ("Hecho", "En 15 min" / "Saltar") que funcionan sin abrir la app.
- Claude puede sugerir ajustes basados en los datos ("por las tardes casi no tomas agua, ¿te aviso más seguido después de las 3 pm?"), siempre como propuesta.

### 5.6 Semana
- Barras de horas trabajadas por día frente a la banda de meta (6–7 h); días pasados, hoy y planificados se distinguen.
- Totales: promedio de trabajo, sesiones de ejercicio, promedio de sueño.
- Tocar un día muestra sus eventos con color por origen (Brújula / Google / Outlook).

### 5.7 Widget de pantalla de inicio
- Muestra el bloque actual y el siguiente, contadores de agua y bálsamo con "+", y botón de micrófono que dicta sin abrir la app completa: aparece una **ventanita flotante pequeña** sobre la pantalla de inicio que escucha, muestra "Agendado" (con Deshacer) y se cierra sola. (Android no permite grabar voz sin algo visible en pantalla.)
- Sigue el tema claro/oscuro.

### 5.8 Tu rutina (configuración)
- Hora de levantarse y de acostarse, horario de trabajo preferido + interruptor "flexible", meta de horas (5–6 / 6–7 / 7–8), calendarios conectados, hábitos, hora del aviso nocturno, tema.
- Clave de la API de Claude (se pide al primer uso y se guarda cifrada).

### 5.9 Apertura de la app
- Al tocar el ícono: fondo oscuro `#0F0D1F`; el ícono nace como un punto que crece hasta el cuadrado redondeado, salen dos ondas (morada y amarilla), la aguja da una vuelta y se asienta; luego suben "Brújula" y "Hacia dónde va tu día". Duración total ≤ 2 s (tablero "ELEGIDA · Onda expansiva sobre fondo oscuro").
- Restricción de Android 12+: la pantalla de arranque del sistema solo muestra el ícono (animación ≤ ~1 s) sobre un color; el nombre y el lema los muestra la propia app en una pantalla que continúa la animación sin corte.

## 6. Identidad visual (decidida)

- **Nombre:** Brújula. **Lema:** "Hacia dónde va tu día".
- **Ícono:** cuadrado redondeado índigo `#4B3BFF`, anillo blanco, aguja con punta norte amarilla `#FFB020` y sur blanca. Variante oscura: fondo `#1A1733`, anillo `#7B6CFF`, sur `#F1F0FF`.
- **Tipografía:** Bricolage Grotesque (títulos, cifras grandes) + DM Sans (texto).
- **Tema claro (principal):** fondo `#F4F3FF`, superficie `#FFFFFF`, texto `#14121F`, secundario `#55516E`, acento `#4B3BFF`, acento 2 `#FFB020`, suaves `#E3E0FF` / `#FFF0D1`, líneas `#DEDBF5`.
- **Tema oscuro:** fondo `#0F0D1F`, superficie `#1A1733`, superficie 2 `#25214A`, texto `#F1F0FF`, secundario `#A9A5C9`, acento `#7B6CFF` (texto oscuro encima), acento 2 `#FFB020`, suaves `#241F52` / `#33280F`, líneas `#2E2A55`.
- **Cambio de tema:** opciones Claro / Oscuro / Automático; en Automático la app pasa a oscuro de noche (por defecto desde las 21:00) y vuelve a claro al levantarse.
- **Movimiento:** botones con respuesta al toque (escala al presionar, rebote suave), entradas escalonadas de listas, micrófono con halo pulsante, anillos y barras que crecen con resorte. Respetar "reducir movimiento" del sistema.
- Referencia de todas las pantallas (claro y oscuro): el lienzo de Claude Design enlazado arriba. Los textos y eventos de ejemplo de los bocetos (cena con Ana, llamada con cliente…) son ilustrativos.

## 7. Enfoques considerados

| Enfoque | A favor | En contra | Decisión |
|---|---|---|---|
| **A. Android nativo — Kotlin + Jetpack Compose** | Alarmas exactas, widgets, acciones en notificaciones, calendario del sistema y reconocimiento de voz son nativos y confiables; Compose maneja bien las animaciones del diseño | Solo Android | **Elegido** |
| B. React Native + Expo (TypeScript) | Un código para Android y en el futuro iPhone | Widget, dictado y alarmas exactas dependen de módulos de terceros: justo las piezas más delicadas | Descartado |
| C. Flutter | Buenas animaciones | Widget y alarmas igual requieren código nativo: doble complejidad | Descartado |
| App web (PWA) | — | Sin widget, sin calendario del sistema, recordatorios poco confiables | Descartado |

Razón: la app es personal, en Android, y lo más importante (no olvidar nada) depende de recordatorios y widget muy confiables. Al usuario le da igual el lenguaje.

## 8. Arquitectura (decidida en lo esencial)

- **Plataforma:** Kotlin + Jetpack Compose, una sola app Android, instalada directamente en el teléfono del usuario (sin tienda). Desarrollo en Windows con Android Studio.
- **Datos locales:** base de datos en el teléfono para hábitos, registros (vasos, veces, sesiones), rutina, bloques propios, propuestas y su historial. Sin servidor.
- **Calendarios (dirección del flujo, decidida por el usuario):**
  - **Entrada:** los eventos de Google y Outlook se leen del calendario del sistema de Android (donde conviven las cuentas del teléfono) y se muestran en Brújula. Solo lectura. (Para Outlook, la app de Outlook debe tener activada la sincronización de calendarios con el teléfono.) El usuario elige qué calendarios importar.
  - **Fuente de verdad:** todo lo que se crea en Brújula (bloques de rutina, trabajo, ejercicio, almuerzo, hábitos, planes aceptados) vive en la base de datos de Brújula.
  - **Salida (única):** las citas y compromisos que el usuario dicta ("dentista el jueves a las 3", "entrevista el lunes a las 10") se duplican en el calendario principal de su cuenta de **Google Calendar**, marcados como creados por Brújula para no reimportarlos como externos. Los bloques de rutina **no** se copian. Nunca se escribe en Outlook. Se puede desactivar en "Tu rutina".
  - Si Claude no está seguro de si algo dictado es cita (se duplica) o bloque de rutina (no se duplica), decide según el tipo de frase y lo muestra en la confirmación, donde el usuario puede cambiarlo.
- **Claude:** la app llama directo a la API de Claude con la clave del usuario, guardada cifrada en el teléfono. Claude recibe el texto dictado más contexto (fecha/hora actual, rutina, eventos relevantes, hábitos y su estado) y devuelve **acciones estructuradas** (crear evento, crear/editar hábito, proponer día, mover bloque, pedir dato faltante). La app valida y ejecuta esas acciones aplicando la regla de la sección 4. Claude nunca ejecuta nada por sí mismo.
- **Recordatorios:** alarmas exactas del sistema para bloques y hábitos; se reprograman al reiniciar el teléfono y cuando cambian rutina, hábitos o calendario. Notificaciones con acciones ("Hecho", "En 15 min", "Saltar").
- **Voz:** reconocimiento de voz de Android en español, desde la app y desde el widget.
- **Widget:** widget de pantalla de inicio con acciones directas (sumar agua/bálsamo, dictar).

## 9. Restricciones

- Uso personal: una persona, un teléfono Android, sin publicación en tiendas.
- La clave de Claude no puede quedar en texto plano ni en el código.
- La app debe funcionar sin internet para todo lo que no sea Claude: ver el día, recordatorios, marcar hábitos.
- Recordatorios de hábitos solo en horas de vigilia (entre levantarse y acostarse configurados).
- Apertura ≤ 2 s.
- Costo de la API de Claude: uso personal y moderado; preferir un modelo rápido y económico para interpretar dictados y uno más capaz solo si hace falta para armar el día.

## 10. Lo que queda a criterio del constructor

- Versión mínima de Android soportada (el teléfono del usuario es reciente; Android 12+ es razonable salvo que el teléfono indique otra cosa).
- Elección exacta de modelos de Claude, forma del esquema de acciones (uso de herramientas / salida estructurada) y el texto del prompt de sistema.
- Librerías concretas (inyección de dependencias, base de datos, navegación, red).
- Cómo marcar las citas duplicadas en Google para reconocerlas (p. ej. un identificador en la descripción o en propiedades extendidas del evento).
- Qué pasa sin internet al dictar. **Llamada del diseñador:** se guarda el texto como pendiente y se interpreta en cuanto vuelva la conexión, avisando al usuario.
- Cómo se cuentan las horas de trabajo. **Llamada del diseñador:** suman (1) los bloques de trabajo de Brújula del día, salvo que el usuario los desmarque, y (2) los eventos importados que sean de trabajo. Como ni Google ni Outlook son de trabajo por sí mismos, Claude clasifica cada evento importado (reunión/entrevista → trabajo; personal → no) al importarlo; el usuario puede corregir la clasificación tocando el evento y la app recuerda la corrección para eventos parecidos (mismo título u organizador).
- De dónde sale el sueño. **Llamada del diseñador:** en esta versión, del horario de sueño de "Tu rutina", ajustado cuando el usuario confirma el aviso de "a dormir" y abre la app por la mañana. Medir sueño real (Health Connect, reloj) queda fuera de esta versión.
- Orden de construcción por fases (lo define el plan).
- Detalles finos de animación, siguiendo los bocetos.

## 11. Días laborales y de descanso (agregado 18-nov-2026, aprobado)

**Por qué:** el usuario no vive igual los fines de semana y festivos: se levanta tarde, no trabaja (o deja el PC trabajando) y hace ejercicio solo si se siente con ganas. Tratar todos los días igual le exigía una meta de trabajo y un ejercicio fijo que no aplican esos días.

**Tipos de día**
- **Laboral:** lunes a viernes que no sean festivo. Rutina actual, sin cambios.
- **Descanso:** sábados, domingos y **todos los festivos de Colombia**, caigan el día que caigan. Se calculan en la app, sin internet, para cualquier año:
  - Fijos: 1 ene (Año Nuevo), 1 may (Día del Trabajo), 20 jul (Independencia), 7 ago (Batalla de Boyacá), 8 dic (Inmaculada Concepción), 25 dic (Navidad).
  - Trasladables al lunes siguiente si no caen en lunes (Ley Emiliani): 6 ene (Reyes Magos), 19 mar (San José), 29 jun (San Pedro y San Pablo), 15 ago (Asunción), 12 oct (Día de la Raza), 1 nov (Todos los Santos), 11 nov (Independencia de Cartagena).
  - Según la Pascua (domingo de Resurrección = P): Jueves Santo (P−3) y Viernes Santo (P−2), sin traslado; Ascensión (lunes P+43), Corpus Christi (lunes P+64), Sagrado Corazón (lunes P+71).
- El tipo de día se determina por el **día lógico** (cambia a las 04:00).

**Qué cambia en un día de descanso**
- **Horario propio:** levantarse **10:00** y acostarse **1:00** por defecto, configurables en "Tu rutina" (sección "Fines de semana y festivos"). La ventana de vigilia de ese día usa estas horas.
- **Sin meta de trabajo:** no se muestra la barra "x / 6–7 h" ni el aviso de pocas horas. Si hay bloques de trabajo, se muestran los minutos sin meta ("Trabajo hoy: 1 h 30").
- **Ejercicio opcional:** el "Ejercicio en casa" por defecto pasa a ser solo de lunes a viernes (18:30). En días de descanso, ~2 h después de levantarse llega un aviso "¿Te animas a hacer ejercicio hoy?" con "Sí, agéndalo" (crea un bloque de Ejercicio de 30 min en el siguiente hueco libre de ≥ 30 min del día) y "Hoy no" (no hace nada más ese día). Los días de descanso sin ejercicio no rompen la racha (la racha cuenta solo días laborales, y suma los de descanso en que sí se hizo).
- **Hábitos iguales:** agua, bálsamo y los demás siguen activos. Los de intervalo empiezan desde la hora de levantarse de ese día. Los de horario fijo que caigan antes de levantarse se corren a la hora de levantarse (sin duplicar si ya hay uno a esa hora) en vez de perderse.
- **Hoy muestra el tipo de día:** etiqueta "Descanso" los fines de semana, o "Festivo · {nombre}" en festivos.

**Fuera de alcance:** festivos de otros países; festivos leídos desde Google Calendar (posible en Fase 2 como complemento); horarios distintos para sábado vs domingo.

## 12. Pausas y bienestar (agregado 18-nov-2026; el usuario pidió agregar todos los hábitos sugeridos en una sección propia y delegó el diseño)

**Por qué:** el usuario trabaja en casa frente al PC muchas horas. Además de agua y bálsamo quiere hábitos de pausa y bienestar, agrupados en una sección aparte para que no se mezclen con lo básico.

**Grupos de hábitos**
- **Básicos:** agua, bálsamo y los que el usuario cree desde "Nuevo hábito" (por defecto). Son los que aparecen en las tarjetas de Hoy.
- **Pausas y bienestar:** los nueve de abajo. Viven en su propia sección en la pantalla Hábitos y no ocupan las tarjetas de Hoy.

**Ventana de cada hábito**
- **Mientras estás despierto** (como hoy).
- **Solo en horario de trabajo:** dentro de los bloques de trabajo preferidos (08:00–12:00 y 14:00–18:00) y solo en días laborales. En días de descanso no suenan.

**Nuevo tipo de horario:** "antes de dormir": una vez al día, N minutos antes de la hora de acostarse de ese día (laboral o descanso).

**Los nueve hábitos de bienestar (se crean una sola vez)**
| Hábito | Horario | Ventana | Activo al crearse |
|---|---|---|---|
| Descanso visual 20-20-20 | cada 30 min | trabajo | sí |
| Pararte y moverte | cada 60 min | trabajo | sí |
| Estiramientos | cada 120 min | trabajo | sí |
| Postura | 10:15 y 16:15 | trabajo | sí |
| Comer a horas | 12:30 (almuerzo) y 16:00 (snack) | despierto | sí |
| Desconectar pantallas | 45 min antes de dormir | despierto | sí |
| Vitaminas | 09:00 | despierto | **no** (se activa con un toque si toma alguna) |
| Protector solar | 09:00 y 12:30 | despierto | sí |
| Lectura sin pantalla | 21:30 | despierto | sí |
La meta diaria de cada uno es el número de avisos que le tocan ese día (como en §5.5); al cumplirla se pausan sus avisos del día.

**Que no sea una lluvia de notificaciones**
- Los avisos de bienestar van en un canal propio, "Pausas y bienestar", con prioridad normal (suenan pero no aparecen encima de lo que estás haciendo). El usuario puede ajustarlo en los ajustes de Android.
- Si varios avisos de bienestar caen en el mismo minuto, llegan **juntos en una sola notificación** ("Pausa: mira lejos 20 s · párate y muévete"), con "Hecho" (marca todos) y "En 15 min".
- Cada hábito de la sección tiene un interruptor para activarlo o desactivarlo sin borrarlo.

**Sección en Hábitos:** debajo de agua, bálsamo y la racha, una sección "Pausas y bienestar" con una fila compacta por hábito: ícono, nombre, resumen del horario ("Cada 30 min en horario de trabajo"), avance de hoy (x de y), botón "+" e interruptor de activo. Mantener presionado → editar o borrar, como los demás.

**Formulario de hábito:** gana el interruptor "Solo en horario de trabajo" y el tipo de horario "Antes de dormir (N min)". Los hábitos creados por el usuario son "Básicos" salvo que los cree desde la sección de bienestar ("Agregar a pausas y bienestar").
