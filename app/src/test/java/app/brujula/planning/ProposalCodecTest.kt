package app.brujula.planning

import app.brujula.activities.Activity
import app.brujula.activities.ActivityOrigin
import app.brujula.activities.ActivityType
import app.brujula.activities.OccurrenceOverride
import app.brujula.activities.Repeat
import app.brujula.core.time.TimeSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime

/** org.json es de Android: corre con Robolectric, como PendingDictationsTest. */
@RunWith(RobolectricTestRunner::class)
@Config(application = app.brujula.TestBrujulaApp::class)
class ProposalCodecTest {
    private val fri = LocalDate.of(2026, 11, 20)
    private fun span(h: Int, m: Int, minutes: Long) = fri.atTime(h, m).let { TimeSpan(it, it.plusMinutes(minutes)) }

    private val activity = Activity(
        id = 0, title = "Trabajo profundo \"Q4\"\ncon salto", type = ActivityType.WORK, date = fri, start = LocalTime.of(9, 0),
        durationMin = 120, repeat = Repeat.NONE, remindBeforeMin = null, copyToGoogle = false, googleEventId = null,
        origin = ActivityOrigin.PLAN,
    )

    private val proposal = Proposal(
        id = 4, day = fri, createdAt = fri.minusDays(1).atTime(21, 5), request = "arma mi viernes", summary = "Te armé el viernes.",
        ops = listOf(
            PlanOp.Create(activity),
            PlanOp.Move("A3", 3, fri, "Llamar al banco", span(15, 0, 60), span(16, 0, 45), oneOff = true),
            PlanOp.Move("A5-2", 5, fri.minusDays(1), "Gimnasio", fri.minusDays(1).atTime(23, 30).let { TimeSpan(it, it.plusMinutes(60)) }, span(0, 30, 60), oneOff = false),
            PlanOp.Skip("A7", 7, fri, "Leer", span(21, 0, 30), oneOff = false),
        ),
        rejected = listOf("Reunión: no toco eventos de tu calendario externo."),
        preview = listOf(
            PreviewBlock("Trabajo profundo", span(9, 0, 120), BlockTag.NUEVO, "9:00 – 11:00"),
            PreviewBlock("Leer", span(21, 0, 30), BlockTag.QUITADO, "21:00 – 21:30"),
        ),
        totals = PlanTotals(workMinutes = 360, exerciseMinutes = 60, sleepMinutes = 480, bedtime = fri.atTime(23, 45)),
    )

    @Test fun roundTrip() {
        val body = ProposalCodec.decode(ProposalCodec.encode(proposal))
        assertEquals(ProposalBody(proposal.ops, proposal.rejected, proposal.preview, proposal.totals), body)

        val undo = UndoData(
            createdIds = listOf(11, 12),
            oneOffBefore = listOf(activity.copy(id = 3, copyToGoogle = true, googleEventId = 500, remindBeforeMin = 15, origin = ActivityOrigin.DICTATION)),
            oneOffAfter = listOf(activity.copy(id = 3, start = LocalTime.of(16, 0), copyToGoogle = true, googleEventId = 500)),
            deleted = listOf(activity.copy(id = 9, type = ActivityType.APPOINTMENT, repeat = Repeat.NONE, googleEventId = 501)),
            overrideBefore = listOf(
                (5L to fri) to OccurrenceOverride(5, fri, fri.atTime(18, 30), 45, skipped = false),
                (7L to fri) to null,
            ),
            overrideAfter = listOf(
                OccurrenceOverride(5, fri, fri.atTime(20, 0), 45, skipped = false),
                OccurrenceOverride(7, fri, null, null, skipped = true),
            ),
        )
        assertEquals(undo, ProposalCodec.decodeUndo(ProposalCodec.encodeUndo(undo)))
        val empty = UndoData(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        assertEquals(empty, ProposalCodec.decodeUndo(ProposalCodec.encodeUndo(empty)))
    }

    @Test fun damagedPayloadIsNull() {
        val good = ProposalCodec.encode(proposal)
        listOf(
            "",
            "{",
            "no es json",
            "[]",
            good.substring(0, good.length / 2),
            good.replace("\"NUEVO\"", "\"INVENTADO\""),
            good.replace("2026-11-20T09:00", "2026-13-20T09:00"),
            good.replace("\"ops\"", "\"opz\""),
            good.replace("\"v\":1", "\"v\":2"),
        ).forEach { assertNull("payload: $it", ProposalCodec.decode(it)) }

        val undo = ProposalCodec.encodeUndo(
            UndoData(listOf(1), emptyList(), emptyList(), listOf(activity.copy(id = 9)), emptyList(), emptyList()),
        )
        listOf("", "{}", "null", undo.substring(0, undo.length - 3), undo.replace("\"WORK\"", "\"NADA\""))
            .forEach { assertNull("undo: $it", ProposalCodec.decodeUndo(it)) }
    }
}
