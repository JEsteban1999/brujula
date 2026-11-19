package app.brujula.planning

import app.brujula.activities.Activity
import app.brujula.activities.ActivityOrigin
import app.brujula.activities.ActivityType
import app.brujula.activities.OccurrenceOverride
import app.brujula.activities.Repeat
import app.brujula.core.time.TimeSpan
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Lo que se guarda en `proposals.payload`; el día, el estado, el pedido y el resumen van en columnas propias. */
data class ProposalBody(
    val ops: List<PlanOp>,
    val rejected: List<String>,
    val preview: List<PreviewBlock>,
    val totals: PlanTotals,
)

/**
 * Lo que hizo `Aceptar plan`, capturado en la misma transacción para que deshacer nunca adivine:
 * - [createdIds]: actividades creadas, en el orden de los `Create` de la propuesta;
 * - [oneOffBefore] / [oneOffAfter]: filas de una sola vez movidas, antes y después (mismo índice);
 * - [deleted]: filas de una sola vez quitadas, completas (con su vínculo de Google) para volver a crearlas con el mismo id;
 * - [overrideBefore] / [overrideAfter]: overrides de ocurrencias recurrentes, el previo (o `null` si no había) y el que
 *   dejó el plan (mismo índice).
 */
data class UndoData(
    val createdIds: List<Long>,
    val oneOffBefore: List<Activity>,
    val oneOffAfter: List<Activity>,
    val deleted: List<Activity>,
    val overrideBefore: List<Pair<Pair<Long, LocalDate>, OccurrenceOverride?>>,
    val overrideAfter: List<OccurrenceOverride>,
)

/** JSON con `org.json` (como PendingDictations): solo cadenas ISO y números. Cualquier payload dañado → `null`. */
object ProposalCodec {
    private const val VERSION = 1

    fun encode(p: Proposal): String = JSONObject()
        .put("v", VERSION)
        .put("ops", JSONArray(p.ops.map { op(it) }))
        .put("rejected", JSONArray(p.rejected))
        .put("preview", JSONArray(p.preview.map { preview(it) }))
        .put("totals", totals(p.totals))
        .toString()

    fun decode(payload: String): ProposalBody? = parse {
        val j = JSONObject(payload)
        require(j.getInt("v") in 1..VERSION)
        ProposalBody(
            ops = j.getJSONArray("ops").objects().map { op(it) },
            rejected = j.getJSONArray("rejected").let { a -> (0 until a.length()).map { a.getString(it) } },
            preview = j.getJSONArray("preview").objects().map { preview(it) },
            totals = totals(j.getJSONObject("totals")),
        )
    }

    fun encodeUndo(u: UndoData): String = JSONObject()
        .put("v", VERSION)
        .put("created", JSONArray(u.createdIds))
        .put("oneOffBefore", JSONArray(u.oneOffBefore.map { activity(it) }))
        .put("oneOffAfter", JSONArray(u.oneOffAfter.map { activity(it) }))
        .put("deleted", JSONArray(u.deleted.map { activity(it) }))
        .put(
            "overrideBefore",
            JSONArray(
                u.overrideBefore.map { (key, before) ->
                    JSONObject().put("id", key.first).put("day", key.second.toString()).put("before", before?.let { override(it) } ?: JSONObject.NULL)
                },
            ),
        )
        .put("overrideAfter", JSONArray(u.overrideAfter.map { override(it) }))
        .toString()

    fun decodeUndo(s: String): UndoData? = parse {
        val j = JSONObject(s)
        require(j.getInt("v") in 1..VERSION)
        val oneOffBefore = j.getJSONArray("oneOffBefore").objects().map { activity(it) }
        val oneOffAfter = j.getJSONArray("oneOffAfter").objects().map { activity(it) }
        val overrideBefore = j.getJSONArray("overrideBefore").objects().map {
            (it.getLong("id") to LocalDate.parse(it.getString("day"))) to (if (it.isNull("before")) null else override(it.getJSONObject("before")))
        }
        val overrideAfter = j.getJSONArray("overrideAfter").objects().map { override(it) }
        require(oneOffBefore.size == oneOffAfter.size && overrideBefore.size == overrideAfter.size)
        UndoData(
            createdIds = j.getJSONArray("created").let { a -> (0 until a.length()).map { a.getLong(it) } },
            oneOffBefore = oneOffBefore,
            oneOffAfter = oneOffAfter,
            deleted = j.getJSONArray("deleted").objects().map { activity(it) },
            overrideBefore = overrideBefore,
            overrideAfter = overrideAfter,
        )
    }

    private inline fun <T> parse(block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        // JSONException, fechas o enums inválidos, campos que faltan: todo cuenta como dañado.
        null
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private fun op(o: PlanOp): JSONObject = when (o) {
        is PlanOp.Create -> JSONObject().put("kind", "create").put("activity", activity(o.activity))
        is PlanOp.Move -> JSONObject().put("kind", "move").put("ref", o.ref).put("id", o.activityId).put("day", o.occurrenceDay.toString())
            .put("title", o.title).put("from", span(o.from)).put("to", span(o.to)).put("oneOff", o.oneOff)
        is PlanOp.Skip -> JSONObject().put("kind", "skip").put("ref", o.ref).put("id", o.activityId).put("day", o.occurrenceDay.toString())
            .put("title", o.title).put("from", span(o.from)).put("oneOff", o.oneOff)
    }

    private fun op(j: JSONObject): PlanOp = when (val kind = j.getString("kind")) {
        "create" -> PlanOp.Create(activity(j.getJSONObject("activity")))
        "move" -> PlanOp.Move(
            ref = j.getString("ref"), activityId = j.getLong("id"), occurrenceDay = LocalDate.parse(j.getString("day")),
            title = j.getString("title"), from = span(j.getJSONObject("from")), to = span(j.getJSONObject("to")), oneOff = j.getBoolean("oneOff"),
        )
        "skip" -> PlanOp.Skip(
            ref = j.getString("ref"), activityId = j.getLong("id"), occurrenceDay = LocalDate.parse(j.getString("day")),
            title = j.getString("title"), from = span(j.getJSONObject("from")), oneOff = j.getBoolean("oneOff"),
        )
        else -> throw IllegalArgumentException("cambio desconocido: $kind")
    }

    private fun span(s: TimeSpan) = JSONObject().put("start", s.start.toString()).put("end", s.end.toString())

    private fun span(j: JSONObject): TimeSpan {
        val s = TimeSpan(LocalDateTime.parse(j.getString("start")), LocalDateTime.parse(j.getString("end")))
        require(s.end >= s.start)
        return s
    }

    private fun preview(b: PreviewBlock) =
        JSONObject().put("title", b.title).put("span", span(b.span)).put("tag", b.tag.name).put("sub", b.sub)

    private fun preview(j: JSONObject) = PreviewBlock(
        title = j.getString("title"), span = span(j.getJSONObject("span")), tag = BlockTag.valueOf(j.getString("tag")), sub = j.getString("sub"),
    )

    private fun totals(t: PlanTotals) = JSONObject().put("work", t.workMinutes).put("exercise", t.exerciseMinutes)
        .put("sleep", t.sleepMinutes).put("bedtime", t.bedtime.toString())

    private fun totals(j: JSONObject) = PlanTotals(
        workMinutes = j.getInt("work"), exerciseMinutes = j.getInt("exercise"), sleepMinutes = j.getInt("sleep"),
        bedtime = LocalDateTime.parse(j.getString("bedtime")),
    )

    private fun activity(a: Activity): JSONObject = JSONObject()
        .put("id", a.id).put("title", a.title).put("type", a.type.name).put("date", a.date.toString())
        .put("start", a.start.toString()).put("durationMin", a.durationMin).put("repeat", a.repeat.name)
        .put("remindBeforeMin", a.remindBeforeMin ?: JSONObject.NULL).put("copyToGoogle", a.copyToGoogle)
        .put("googleEventId", a.googleEventId ?: JSONObject.NULL).put("origin", a.origin.name)

    private fun activity(j: JSONObject) = Activity(
        id = j.getLong("id"),
        title = j.getString("title"),
        type = ActivityType.valueOf(j.getString("type")),
        date = LocalDate.parse(j.getString("date")),
        start = LocalTime.parse(j.getString("start")),
        durationMin = j.getInt("durationMin"),
        repeat = Repeat.valueOf(j.getString("repeat")),
        remindBeforeMin = if (j.isNull("remindBeforeMin")) null else j.getInt("remindBeforeMin"),
        copyToGoogle = j.getBoolean("copyToGoogle"),
        googleEventId = if (j.isNull("googleEventId")) null else j.getLong("googleEventId"),
        origin = ActivityOrigin.valueOf(j.getString("origin")),
    )

    private fun override(o: OccurrenceOverride): JSONObject = JSONObject()
        .put("id", o.activityId).put("day", o.day.toString())
        .put("start", o.start?.toString() ?: JSONObject.NULL).put("durationMin", o.durationMin ?: JSONObject.NULL)
        .put("skipped", o.skipped)

    private fun override(j: JSONObject) = OccurrenceOverride(
        activityId = j.getLong("id"),
        day = LocalDate.parse(j.getString("day")),
        start = if (j.isNull("start")) null else LocalDateTime.parse(j.getString("start")),
        durationMin = if (j.isNull("durationMin")) null else j.getInt("durationMin"),
        skipped = j.getBoolean("skipped"),
    )
}
