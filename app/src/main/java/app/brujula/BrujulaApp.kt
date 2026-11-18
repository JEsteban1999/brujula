package app.brujula

import android.app.Application
import android.content.Context

/** Contenedor de dependencias; se irá llenando en tareas siguientes. */
class AppContainer(val context: Context)

class BrujulaApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}

val Context.container: AppContainer
    get() = (applicationContext as BrujulaApp).container
