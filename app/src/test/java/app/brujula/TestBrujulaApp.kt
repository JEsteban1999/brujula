package app.brujula

/** Application de pruebas: sin trabajo de arranque en segundo plano, pero con `container` disponible. */
class TestBrujulaApp : BrujulaApp() {
    override fun startBackgroundWork() = Unit
}
