package app.brujula.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
@Config(application = app.brujula.TestBrujulaApp::class)
class DeepLinksTest {
    @Test fun allowsKnownRoutes() {
        for (r in listOf("today", "voice", "plan_tomorrow", "proposal?day=2026-11-20", "proposal?day=2026-11-20&request=arma%20mi%20d%C3%ADa")) {
            assertEquals(r, DeepLinks.parse(r))
        }
        assertEquals("proposal?day=2026-11-20&request=x", DeepLinks.toNavRoute("proposal?day=2026-11-20&request=x", LocalDate.of(2026, 11, 20)))
        assertEquals(Routes.PLAN_TOMORROW, DeepLinks.toNavRoute("plan_tomorrow", LocalDate.of(2026, 11, 20)))
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

    // ---- M11: el pedido solo vale con el secreto de la app ----

    @Test fun requestNeedsTheInAppToken() {
        val secret = "s3cr3t"
        val withRequest = "proposal?day=2026-11-20&request=arma%20mi%20d%C3%ADa"
        // Nuestras notificaciones llevan el secreto: el pedido se respeta.
        assertEquals(withRequest, DeepLinks.fromIntent(withRequest, secret, secret))
        // Otra app (sin secreto o con uno falso): se abre el día, sin pedir nada a Claude.
        assertEquals("proposal?day=2026-11-20", DeepLinks.fromIntent(withRequest, null, secret))
        assertEquals("proposal?day=2026-11-20", DeepLinks.fromIntent(withRequest, "otro", secret))
        // Lo que no lleva pedido no cambia, con o sin secreto; lo que no está en la lista blanca sigue fuera.
        assertEquals("proposal?day=2026-11-20", DeepLinks.fromIntent("proposal?day=2026-11-20", null, secret))
        assertEquals("plan_tomorrow", DeepLinks.fromIntent("plan_tomorrow", null, secret))
        assertNull(DeepLinks.fromIntent("habits", secret, secret))
    }

    @Test fun ownIntentsCarryAStableSecret() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val token = DeepLinks.token(ctx)
        assertTrue(token.length >= 32)
        assertEquals(token, DeepLinks.token(ctx))
        val intent = DeepLinks.intent(ctx, Routes.proposal(LocalDate.of(2026, 11, 20), "arma mi día"))
        assertEquals(token, intent.getStringExtra(DeepLinks.EXTRA_TOKEN))
        val route = intent.getStringExtra(DeepLinks.EXTRA_ROUTE)
        assertEquals(route, DeepLinks.fromIntent(route, intent.getStringExtra(DeepLinks.EXTRA_TOKEN), DeepLinks.token(ctx)))
        assertNotEquals(route, DeepLinks.fromIntent(route, null, DeepLinks.token(ctx)))
    }

    // ---- I3: Listo tras aceptar desde el formulario ----

    @Test fun acceptedDoneAlsoPopsTheClashingFormDraft() {
        assertEquals(2, Routes.popsAfterAccept(Routes.ACTIVITY_FORM))
        assertEquals(1, Routes.popsAfterAccept(Routes.TODAY))
        assertEquals(1, Routes.popsAfterAccept(Routes.VOICE))
        assertEquals(1, Routes.popsAfterAccept(null))
    }
}
