package app.brujula.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class DeepLinksTest {
    @Test fun allowsKnownRoutes() {
        for (r in listOf("today", "voice", "plan_tomorrow", "proposal?day=2026-11-20", "proposal?day=2026-11-20&request=arma%20mi%20d%C3%ADa")) {
            assertEquals(r, DeepLinks.parse(r))
        }
        assertEquals("proposal?day=2026-11-20&request=x", DeepLinks.toNavRoute("proposal?day=2026-11-20&request=x", LocalDate.of(2026, 11, 20)))
        assertEquals("proposal?day=2026-11-20", DeepLinks.toNavRoute("plan_tomorrow", LocalDate.of(2026, 11, 20)))
        assertEquals("today", DeepLinks.toNavRoute("today", LocalDate.of(2026, 11, 20)))
        assertEquals("voice", DeepLinks.toNavRoute("voice", LocalDate.of(2026, 11, 20)))
    }

    @Test fun rejectsUnknownAndMalformed() {
        val bad = listOf(
            null, "", " ", "habits", "routine", "activity?id=1", "TODAY", "today ", "today/../voice", "https://evil.example/x",
            "proposal", "proposal?", "proposal?day=", "proposal?day=mañana", "proposal?day=2026-13-40", "proposal?request=x",
            "proposal?day=2026-11-20&other=1", "proposal?day=2026-11-20&request=%ZZ", "proposal?day=2026-11-20&request=a&request=b",
            "proposal?day=2026-11-20&", "voice?x=1",
        )
        for (r in bad) assertNull("debía rechazar «$r»", DeepLinks.parse(r))
    }

    @Test fun capsRequestAt500() {
        val ok = "a".repeat(500)
        assertEquals("proposal?day=2026-11-20&request=$ok", DeepLinks.parse("proposal?day=2026-11-20&request=$ok"))
        assertNull(DeepLinks.parse("proposal?day=2026-11-20&request=${"a".repeat(501)}"))
    }
}
