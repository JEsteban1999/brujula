package app.brujula.calendar

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(application = app.brujula.TestBrujulaApp::class)
class DeviceCalendarSourceTest {
    private val app: Context = ApplicationProvider.getApplicationContext()

    class ThrowingProvider : ContentProvider() {
        override fun onCreate() = true
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = throw error!!
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
        companion object { var error: RuntimeException? = null }
    }

    private fun failingContext(e: RuntimeException): Context {
        ThrowingProvider.error = e
        Robolectric.buildContentProvider(ThrowingProvider::class.java).create("com.android.calendar")
        return app
    }

    @Test fun providerFailuresReturnEmptyInsteadOfThrowing() {
        shadowOf(app as android.app.Application).grantPermissions(Manifest.permission.READ_CALENDAR)
        for (e in listOf(IllegalStateException("x"), IllegalArgumentException("y"), SecurityException("z"))) {
            val source = DeviceCalendarSource(failingContext(e))
            runBlocking {
                assertEquals(emptyList<DeviceCalendar>(), source.calendars())
                assertEquals(
                    emptyList<ExternalEvent>(),
                    source.events(setOf(1L), LocalDateTime.of(2026, 11, 18, 0, 0), LocalDateTime.of(2026, 11, 19, 0, 0)),
                )
            }
        }
    }

    @Test fun withoutPermissionReturnsEmpty() {
        val source = DeviceCalendarSource(failingContext(IllegalStateException("x")))
        runBlocking { assertEquals(emptyList<DeviceCalendar>(), source.calendars()) }
    }
}
