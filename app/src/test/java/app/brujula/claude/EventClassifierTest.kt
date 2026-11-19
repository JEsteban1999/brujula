package app.brujula.claude

import app.brujula.calendar.normalizeTitle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventClassifierTest {
    private val gateway = FakeClaudeGateway()
    private val classifier = EventClassifier(gateway)

    private fun ok(vararg m: Pair<String, Boolean>) =
        GatewayResult.Ok(ClassificationResult(m.map { TitleMark(it.first, it.second) }))

    @Test fun mapsResultsBackByNormalizedKeyNotPosition() = runTest {
        // Claude devuelve en otro orden y con otras mayúsculas/tildes.
        gateway.classification = ok("YOGA" to false, "Reunión de equipo" to true)
        val out = classifier.classify(listOf("Reunión de equipo", "Yoga"))
        assertEquals(mapOf("reunion de equipo" to true, "yoga" to false), out)
    }

    @Test fun ignoresTitlesNotSent() = runTest {
        gateway.classification = ok("Reunión" to true, "Inventado" to true)
        assertEquals(mapOf("reunion" to true), classifier.classify(listOf("Reunión")))
    }

    @Test fun blankTitlesAreNeverSentAndDuplicatesCollapse() = runTest {
        gateway.classification = ok("Standup" to true)
        classifier.classify(listOf("  ", "", "Standup", "standup "))
        assertEquals(1, gateway.classifyCalls)
        assertEquals(1, "Standup".toRegex().findAll(gateway.classifyUsers.single()).count() )
    }

    @Test fun onlyBlankTitlesMakeNoCall() = runTest {
        assertTrue(classifier.classify(listOf("", " ")).isEmpty())
        assertEquals(0, gateway.classifyCalls)
    }

    @Test fun batchesOfThirty() = runTest {
        gateway.classifyWith = { user ->
            GatewayResult.Ok(ClassificationResult(Regex("t[0-9]+").findAll(user).map { TitleMark(it.value, true) }.toList()))
        }
        val titles = (1..65).map { "t$it" }
        val out = classifier.classify(titles)
        assertEquals(3, gateway.classifyCalls)
        assertEquals(65, out.size)
    }

    @Test fun gatewayFailuresNeverThrowAndReturnNothing() = runTest {
        for (f in listOf(GatewayResult.Refused, GatewayResult.NoKey, GatewayResult.InvalidKey, GatewayResult.Network, GatewayResult.Timeout, GatewayResult.Error(true))) {
            gateway.classification = f
            assertTrue(classifier.classify(listOf("Reunión")).isEmpty())
        }
    }

    @Test fun gatewayExceptionNeverThrows() = runTest {
        gateway.classifyWith = { throw IllegalStateException("boom") }
        assertTrue(classifier.classify(listOf("Reunión")).isEmpty())
    }

    @Test fun onlyTitlesGoToClaude() = runTest {
        gateway.classification = ok("Reunión" to true)
        classifier.classify(listOf("Reunión"))
        assertTrue(gateway.classifyUsers.single().contains("Reunión"))
        assertEquals(ClassificationPrompt.system(), gateway.classifySystem)
    }

    @Test fun normalizeMatchesClassificationKey() {
        assertEquals("reunion de equipo", normalizeTitle("  Reunión   de EQUIPO "))
    }
}
