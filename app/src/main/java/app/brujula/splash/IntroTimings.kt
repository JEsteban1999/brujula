package app.brujula.splash

/** Presupuesto de tiempo de la apertura: splash del sistema + intro en Compose <= 2000 ms. */
object IntroTimings {
    const val SYSTEM_MS = 800

    /** Intro completa: ondas 0-800, texto 250-850, reposo hasta 1200. */
    const val COMPOSE_MS = 1200

    /** Con reducir movimiento: solo el estado final, ~400 ms. */
    const val COMPOSE_REDUCED_MS = 400

    fun composeMs(reduceMotion: Boolean): Int = if (reduceMotion) COMPOSE_REDUCED_MS else COMPOSE_MS

    fun totalMs(reduceMotion: Boolean): Int = SYSTEM_MS + composeMs(reduceMotion)
}

/** Decide si se reproduce la intro: solo en arranque en frío (ni recreación ni proceso vivo). */
fun shouldPlayIntro(alreadyShownInProcess: Boolean, restoredFromSavedState: Boolean): Boolean =
    !alreadyShownInProcess && !restoredFromSavedState

/**
 * Geometría compartida entre avd_splash_icon.xml y IntroScreen. Sin windowSplashScreenIconBackgroundColor
 * el sistema pinta el ícono animado a 288 dp (encajado en un círculo de 192 dp); ver la guía de
 * splash screens de Android. El lienzo del AVD es de 108 unidades y la baldosa mide 60.
 */
object SplashGeometry {
    const val SPLASH_ICON_DP = 288f
    const val ICON_CANVAS_UNITS = 108f
    const val TILE_UNITS = 60f
    const val TILE_DP = SPLASH_ICON_DP * TILE_UNITS / ICON_CANVAS_UNITS
}
