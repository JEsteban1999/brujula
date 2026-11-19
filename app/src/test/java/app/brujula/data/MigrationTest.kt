package app.brujula.data

import android.content.ContentValues
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import app.brujula.data.db.BrujulaDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BrujulaDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory(),
    )

    @Test fun v1ToV2KeepsHabitsAndLogs() {
        helper.createDatabase("mig.db", 1).apply {
            insert("habits", 0, ContentValues().apply {
                put("id", 1); put("name", "Agua"); put("icon", "WATER"); put("schedule", "every:90")
                put("dailyTarget", 8); put("days", "1,2,3,4,5,6,7"); put("active", 1)
            })
            insert("habits", 0, ContentValues().apply {
                put("id", 2); put("name", "Bálsamo labial"); put("icon", "LIP_BALM"); put("schedule", "at:09:00,13:00")
                put("dailyTarget", 4); put("days", "1,2,3,4,5,6,7"); put("active", 1)
            })
            insert("habit_logs", 0, ContentValues().apply {
                put("id", 1); put("habitId", 1); put("at", "2026-11-18T11:15"); put("logicalDay", "2026-11-18")
                put("skipped", 0); put("reminderKey", "k1")
            })
            close()
        }
        val db = helper.runMigrationsAndValidate("mig.db", 2, true)
        db.query("SELECT id, name, icon, schedule, dailyTarget, groupName, `window` FROM habits ORDER BY id").use { c ->
            assertEquals(2, c.count)
            c.moveToFirst()
            assertEquals("Agua", c.getString(1)); assertEquals("every:90", c.getString(3)); assertEquals(8, c.getInt(4))
            assertEquals("ESSENTIAL", c.getString(5)); assertEquals("AWAKE", c.getString(6))
            c.moveToNext()
            assertEquals("Bálsamo labial", c.getString(1))
            assertEquals("ESSENTIAL", c.getString(5)); assertEquals("AWAKE", c.getString(6))
        }
        db.query("SELECT habitId, logicalDay, reminderKey FROM habit_logs").use { c ->
            assertEquals(1, c.count)
            c.moveToFirst()
            assertEquals(1, c.getInt(0)); assertEquals("2026-11-18", c.getString(1)); assertEquals("k1", c.getString(2))
        }
    }
}
