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
