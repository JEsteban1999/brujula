package app.brujula.claude

import app.brujula.activities.Activity
import app.brujula.activities.ActivityOrigin
import app.brujula.activities.ActivityType
import app.brujula.core.time.FixedClock
import app.brujula.core.time.TimeSpan
import app.brujula.planning.BlockTag
import app.brujula.planning.PlanFixtures
import app.brujula.planning.PlanFixtures.THU
import app.brujula.planning.PlanFixtures.WED
import app.brujula.planning.PlanOp
import app.brujula.planning.PlanTotals
import app.brujula.planning.Proposal
import app.brujula.planning.ProposalStatus
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class DayPlannerTest {
    private val gateway = FakeClaudeGateway()
    private val clock = FixedClock(WED.atTime(21, 0))
    private var now: LocalDateTime
        get() = clock.current
        set(value) { clock.current = value }
    private val requestedDays = mutableListOf<LocalDate>()
    private val items = listOf(
        PlanFixtures.own(2, "Gimnasio", THU.atTime(18, 30), THU.atTime(19, 30), ActivityType.EXERCISE, oneOff = false),
        PlanFixtures.ext(1, "Reunión de equipo", THU.atTime(9, 0), THU.atTime(10, 0), work = true),
    )

    private fun planner(online: Boolean = true, failContext: Boolean = false, gw: ClaudeGateway = gateway) = DayPlanner(
        gw,
        contextProvider = { day ->
            requestedDays += day
            if (failContext) error("DB cerrada")
            PlanFixtures.ctx(now, day, if (day == THU) items else emptyList())
        },
        isOnline = { online },
        clock = clock,
    )

    private fun ok(summary: String, vararg changes: PlanChange) = GatewayResult.Ok(PlanResult(summary, changes.toList()))

    @Test fun dayPlusSevenIsOutOfRangeWithoutCallingClaude() = runTest {
        val r = planner().propose(WED.plusDays(7), request = null)
        assertEquals(PlanOutcome.Failed(DayPlanner.OUT_OF_RANGE, retryable = false), r)
        assertEquals(0, gateway.planCalls)
        assertEquals("Ni siquiera se arma el contexto", emptyList<LocalDate>(), requestedDays)
        // Aunque leer el contexto fallara, la respuesta es la del rango.
        assertEquals(PlanOutcome.Failed(DayPlanner.OUT_OF_RANGE, retryable = false), planner(failContext = true).propose(WED.plusDays(7), null))
        assertEquals(emptyList<LocalDate>(), requestedDays)
        assertEquals("Por ahora puedo armar hasta 6 días adelante.", DayPlanner.OUT_OF_RANGE)
    }

    @Test fun pastDayIsRefusedAndTodayPlusSixIsAllowed() = runTest {
        val past = planner().propose(WED.minusDays(1), request = null)
        assertEquals(PlanOutcome.Failed(DayPlanner.PAST_DAY, retryable = false), past)
        assertEquals(0, gateway.planCalls)
        assertEquals(emptyList<LocalDate>(), requestedDays)
        gateway.plan = ok("Nada que cambiar.")
        assertTrue(planner().propose(WED.plusDays(6), request = null) is PlanOutcome.Ok)
        assertEquals(1, gateway.planCalls)
    }

    @Test fun tomorrowAtHalfPastMidnightCountsByLogicalDay() = runTest {
        // 00:30 del jueves en el calendario = miércoles lógico: "mañana" sigue siendo el jueves.
        now = THU.atTime(0, 30)
        gateway.plan = ok("Listo.")
        val r = planner().propose(THU, request = "arma mañana")
        assertTrue(r is PlanOutcome.Ok)
        assertTrue(gateway.lastPlanUser!!.contains("- Es mañana."))
        // Y desde el jueves lógico (8:00) el jueves es hoy, el miércoles ya pasó.
        now = THU.atTime(8, 0)
        assertTrue(planner().propose(WED, request = null) is PlanOutcome.Failed)
    }

    @Test fun offlineSkipsTheCall() = runTest {
        assertEquals(PlanOutcome.Offline, planner(online = false).propose(THU, request = null))
        assertEquals(0, gateway.planCalls)
        assertEquals("Sin conexión. Claude necesita internet para armar tu día.", DayPlanner.OFFLINE)
    }

    @Test fun contextFailureIsRetryableAndSkipsTheCall() = runTest {
        val r = planner(failContext = true).propose(THU, request = null)
        assertTrue(r is PlanOutcome.Failed && r.retryable)
        assertEquals(0, gateway.planCalls)
    }

    @Test fun okCarriesOpsRejectionsPreviewAndTotals() = runTest {
        gateway.plan = ok(
            "  Moví el gimnasio a las 4 y agregué un bloque de trabajo.  ",
            PlanChange(op = "move", ref = "A2", date = "2026-11-19", start = "16:00"),
            PlanChange(op = "skip", ref = "E1"),
            PlanChange(op = "create", title = "Bloque de trabajo", activityType = "WORK", date = "2026-11-19", start = "14:00", durationMin = 60),
        )
        val r = planner().propose(THU, request = "pasa el gym a la tarde")
        assertTrue(r.toString(), r is PlanOutcome.Ok)
        val p = (r as PlanOutcome.Ok).proposal
        assertEquals(THU, p.day)
        assertEquals(now, p.createdAt)
        assertEquals("pasa el gym a la tarde", p.request)
        assertEquals("Moví el gimnasio a las 4 y agregué un bloque de trabajo.", p.summary)
        assertEquals(ProposalStatus.PENDING, p.status)
        assertEquals(0L, p.id)
        assertEquals(2, p.ops.size)
        assertEquals(listOf("«Reunión de equipo»: no toco eventos de tu calendario externo."), p.rejected)
        assertEquals(
            listOf(BlockTag.FIJO, BlockTag.FIJO, BlockTag.NUEVO, BlockTag.MOVIDO, BlockTag.NONE),
            p.preview.map { it.tag },
        )
        assertEquals("A dormir", p.preview.last().title)
        assertEquals(PlanTotals(workMinutes = 60 + 60, exerciseMinutes = 60, sleepMinutes = 480, bedtime = THU.plusDays(1).atTime(0, 15)), p.totals)
        assertTrue(gateway.lastPlanUser!!.contains("<pedido>\npasa el gym a la tarde\n</pedido>"))
        assertEquals(PlanPrompt.system(), gateway.lastPlanSystem)
    }

    @Test fun validatesWithTheTimeTheAnswerArrives() = runTest {
        // Hoy (jueves) a las 9:58 se pide el día; Claude tarda y responde a las 10:05 con un bloque a las 10:00.
        now = THU.atTime(9, 58)
        gateway.plan = ok(
            "Listo.",
            PlanChange(op = "create", title = "Bloque de trabajo", activityType = "WORK", date = "2026-11-19", start = "10:00", durationMin = 60),
            PlanChange(op = "create", title = "Leer", activityType = "PERSONAL", date = "2026-11-19", start = "11:00", durationMin = 30),
        )
        val slow = object : ClaudeGateway by gateway {
            override suspend fun plan(system: String, user: String): GatewayResult<PlanResult> {
                val r = gateway.plan(system, user)
                now = THU.atTime(10, 5)
                return r
            }
        }
        val p = (planner(gw = slow).propose(THU, request = null) as PlanOutcome.Ok).proposal
        assertEquals(listOf("Leer"), p.ops.map { (it as PlanOp.Create).activity.title })
        assertEquals(1, p.rejected.size)
        assertTrue(p.rejected[0], p.rejected[0].startsWith("«Bloque de trabajo»") && p.rejected[0].contains("ya pasó"))
        assertEquals(THU.atTime(10, 5), p.createdAt)
    }

    @Test fun bedtimeIsNeverBeforeTheTimeTheAnswerArrives() = runTest {
        // Jueves lógico, 00:10 del viernes: la sugerida (00:15) queda; si Claude responde a las 00:20, pasa a las 00:30.
        now = THU.plusDays(1).atTime(0, 10)
        gateway.plan = ok("Nada que cambiar.")
        val slow = object : ClaudeGateway by gateway {
            override suspend fun plan(system: String, user: String): GatewayResult<PlanResult> {
                val r = gateway.plan(system, user)
                now = THU.plusDays(1).atTime(0, 20)
                return r
            }
        }
        val p = (planner(gw = slow).propose(THU, request = null) as PlanOutcome.Ok).proposal
        assertEquals(THU.plusDays(1).atTime(0, 30), p.totals.bedtime)
    }

    @Test fun summaryIsCappedAt400() = runTest {
        gateway.plan = ok("a".repeat(500))
        val p = (planner().propose(THU, request = null) as PlanOutcome.Ok).proposal
        assertEquals(400, p.summary.length)
    }

    @Test fun adjustmentSendsPreviousChangesAndAjuste() = runTest {
        val previous = Proposal(
            id = 7, day = THU, createdAt = now, request = "arma mi día", summary = "Antes.",
            ops = listOf(
                PlanOp.Move("A2", 2, THU, "Gimnasio", TimeSpan(THU.atTime(18, 30), THU.atTime(19, 30)), TimeSpan(THU.atTime(16, 0), THU.atTime(17, 0)), oneOff = false),
                PlanOp.Skip("A4", 4, THU, "Leer", TimeSpan(THU.atTime(21, 0), THU.atTime(21, 30)), oneOff = true),
                PlanOp.Create(
                    Activity(
                        title = "Bloque de trabajo", type = ActivityType.WORK, date = THU, start = LocalTime.of(14, 0), durationMin = 60,
                        remindBeforeMin = null, copyToGoogle = false, origin = ActivityOrigin.PLAN,
                    ),
                ),
            ),
            rejected = emptyList(), preview = emptyList(),
            totals = PlanTotals(0, 0, 480, THU.plusDays(1).atTime(0, 15)),
        )
        gateway.plan = ok("Ajustado.")
        val r = planner().propose(THU, request = null, previous = previous, adjustment = "mejor el gym a las 7")
        assertTrue(r is PlanOutcome.Ok)
        assertEquals("arma mi día", (r as PlanOutcome.Ok).proposal.request)
        val user = gateway.lastPlanUser!!
        assertTrue(user, user.contains("PROPUESTA ANTERIOR"))
        assertTrue(user, user.contains("- move A2 → 2026-11-19 16:00, 60 min"))
        assertTrue(user, user.contains("- skip A4"))
        assertTrue(user, user.contains("- create WORK «Bloque de trabajo» 2026-11-19 14:00, 60 min"))
        assertTrue(user, user.contains("<ajuste>\nmejor el gym a las 7\n</ajuste>"))
        assertTrue(user, user.contains("<pedido>\narma mi día\n</pedido>"))
    }

    @Test fun refusalMapsToRefused() = runTest {
        gateway.plan = GatewayResult.Refused
        assertEquals(PlanOutcome.Refused(DayPlanner.REFUSED), planner().propose(THU, request = null))
    }

    @Test fun noKeyAndInvalidKey() = runTest {
        gateway.plan = GatewayResult.NoKey
        assertEquals(PlanOutcome.NoKey, planner().propose(THU, request = null))
        assertEquals("Para que Claude arme tu día, agrega tu clave en Tu rutina.", DayPlanner.NO_KEY)
        gateway.plan = GatewayResult.InvalidKey
        assertEquals(PlanOutcome.InvalidKey, planner().propose(THU, request = null))
    }

    @Test fun failuresMapLikeDictation() = runTest {
        gateway.plan = GatewayResult.Network
        assertEquals(PlanOutcome.Failed(DayPlanner.OFFLINE, retryable = true), planner().propose(THU, null))
        gateway.plan = GatewayResult.Timeout
        assertEquals(PlanOutcome.Failed(DayPlanner.TOO_SLOW, retryable = true), planner().propose(THU, null))
        gateway.plan = GatewayResult.Error(retryable = true)
        assertEquals(PlanOutcome.Failed(DayPlanner.UNAVAILABLE, retryable = true), planner().propose(THU, null))
        gateway.plan = GatewayResult.Error(retryable = false, config = true)
        assertEquals(PlanOutcome.Failed(DayPlanner.CONFIG_ERROR, retryable = false), planner().propose(THU, null))
        gateway.plan = GatewayResult.Error(retryable = false)
        assertEquals(PlanOutcome.Failed(DayPlanner.REFUSED, retryable = false), planner().propose(THU, null))
    }
}
