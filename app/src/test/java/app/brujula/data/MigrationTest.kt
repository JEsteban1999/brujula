package app.brujula.data

import android.content.ContentValues
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.platform.app.InstrumentationRegistry
import app.brujula.data.db.BrujulaDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    private fun insertV1Rows(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.insert("habits", 0, ContentValues().apply {
            put("id", 1); put("name", "Agua"); put("icon", "WATER"); put("schedule", "every:90")
            put("dailyTarget", 8); put("days", "1,2,3,4,5,6,7"); put("active", 1)
        })
        db.insert("habit_logs", 0, ContentValues().apply {
            put("id", 1); put("habitId", 1); put("at", "2026-11-18T11:15"); put("logicalDay", "2026-11-18")
            put("skipped", 0); put("reminderKey", "k1")
        })
        db.insert("activities", 0, ContentValues().apply {
            put("id", 1); put("title", "Dentista"); put("type", "APPOINTMENT"); put("date", "2026-11-20")
            put("start", "16:45"); put("durationMin", 60); put("repeat", "NONE"); put("copyToGoogle", 1)
        })
        db.insert("activity_completions", 0, ContentValues().apply {
            put("activityId", 1); put("day", "2026-11-20")
        })
    }

    private fun assertV3Data(db: androidx.sqlite.db.SupportSQLiteDatabase, insertWorkRow: Boolean = true) {
        db.query("SELECT name, groupName FROM habits").use { c ->
            assertEquals(1, c.count); c.moveToFirst(); assertEquals("Agua", c.getString(0)); assertEquals("ESSENTIAL", c.getString(1))
        }
        db.query("SELECT reminderKey FROM habit_logs").use { c ->
            assertEquals(1, c.count); c.moveToFirst(); assertEquals("k1", c.getString(0))
        }
        db.query("SELECT title, copyToGoogle, googleEventId FROM activities").use { c ->
            assertEquals(1, c.count); c.moveToFirst()
            assertEquals("Dentista", c.getString(0)); assertEquals(1, c.getInt(1)); assertTrue(c.isNull(2))
        }
        db.query("SELECT day FROM activity_completions WHERE activityId = 1").use { c ->
            assertEquals(1, c.count); c.moveToFirst(); assertEquals("2026-11-20", c.getString(0))
        }
        if (insertWorkRow) db.query("SELECT COUNT(*) FROM work_classifications").use { c ->
            c.moveToFirst(); assertEquals(0, c.getInt(0))
        }
        if (insertWorkRow) db.execSQL("INSERT INTO work_classifications(`key`, isWork) VALUES ('x', 1)")
    }

    @Test fun v2ToV3KeepsEverythingAndLeavesGoogleEventIdNull() {
        helper.createDatabase("mig23.db", 2).apply { insertV1Rows(this); close() }
        assertV3Data(helper.runMigrationsAndValidate("mig23.db", 3, true))
    }

    @Test fun v1ToV3ChainedKeepsEverything() {
        helper.createDatabase("mig13.db", 1).apply { insertV1Rows(this); close() }
        assertV3Data(helper.runMigrationsAndValidate("mig13.db", 3, true))
    }

    private fun assertV4Data(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        assertV3Data(db, insertWorkRow = false)
        db.query("SELECT `key`, isWork, source FROM work_classifications ORDER BY `key`").use { c ->
            assertEquals(2, c.count)
            c.moveToFirst()
            assertEquals("reunion", c.getString(0)); assertEquals(1, c.getInt(1)); assertEquals("USER", c.getString(2))
            c.moveToNext()
            assertEquals("yoga", c.getString(0)); assertEquals(0, c.getInt(1)); assertEquals("USER", c.getString(2))
        }
        db.execSQL("INSERT INTO work_classifications(`key`, isWork, source) VALUES ('z', 1, 'AUTO')")
        db.query("SELECT source FROM work_classifications WHERE `key` = 'z'").use { c ->
            c.moveToFirst(); assertEquals("AUTO", c.getString(0))
        }
    }

    private fun insertV3Marks(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("INSERT INTO work_classifications(`key`, isWork) VALUES ('reunion', 1)")
        db.execSQL("INSERT INTO work_classifications(`key`, isWork) VALUES ('yoga', 0)")
    }

    @Test fun v3ToV4MarksExistingRowsAsUser() {
        helper.createDatabase("mig34.db", 3).apply { insertV1Rows(this); insertV3Marks(this); close() }
        assertV4Data(helper.runMigrationsAndValidate("mig34.db", 4, true))
    }

    @Test fun v1ToV4ChainedKeepsEverything() {
        helper.createDatabase("mig14.db", 1).apply { insertV1Rows(this); close() }
        val mid = helper.runMigrationsAndValidate("mig14.db", 3, true)
        insertV3Marks(mid)
        mid.close()
        assertV4Data(helper.runMigrationsAndValidate("mig14.db", 4, true))
    }

    private fun insertV4Rows(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        insertV1Rows(db)
        db.execSQL("INSERT INTO work_classifications(`key`, isWork, source) VALUES ('reunion', 1, 'USER')")
        db.execSQL("UPDATE activities SET googleEventId = 77 WHERE id = 1")
    }

    private fun assertV5Data(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.query("SELECT title, origin, googleEventId, copyToGoogle FROM activities").use { c ->
            assertEquals(1, c.count); c.moveToFirst()
            assertEquals("Dentista", c.getString(0)); assertEquals("UNKNOWN", c.getString(1))
            assertEquals(77, c.getInt(2)); assertEquals(1, c.getInt(3))
        }
        db.query("SELECT name FROM habits").use { c -> assertEquals(1, c.count) }
        db.query("SELECT reminderKey FROM habit_logs").use { c -> assertEquals(1, c.count); c.moveToFirst(); assertEquals("k1", c.getString(0)) }
        db.query("SELECT day FROM activity_completions WHERE activityId = 1").use { c -> assertEquals(1, c.count) }
        db.query("SELECT `key`, source FROM work_classifications").use { c ->
            assertEquals(1, c.count); c.moveToFirst(); assertEquals("reunion", c.getString(0)); assertEquals("USER", c.getString(1))
        }
        db.execSQL("INSERT INTO activity_overrides(activityId, day, start, durationMin, skipped) VALUES (1, '2026-11-20', '2026-11-20T18:00', 30, 0)")
        db.execSQL(
            "INSERT INTO proposals(day, createdAt, status, request, summary, payload) " +
                "VALUES ('2026-11-20', '2026-11-19T22:00', 'PENDING', NULL, 'resumen', '{}')",
        )
        db.query("SELECT status, undo, acceptedAt FROM proposals").use { c ->
            assertEquals(1, c.count); c.moveToFirst()
            assertEquals("PENDING", c.getString(0)); assertTrue(c.isNull(1)); assertTrue(c.isNull(2))
        }
        db.query("SELECT COUNT(*) FROM activity_overrides").use { c -> c.moveToFirst(); assertEquals(1, c.getInt(0)) }
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM activities WHERE id = 1")
        db.query("SELECT COUNT(*) FROM activity_overrides").use { c -> c.moveToFirst(); assertEquals(0, c.getInt(0)) }
    }

    @Test fun v4ToV5KeepsActivitiesWithUnknownOrigin() {
        helper.createDatabase("mig45.db", 4).apply { insertV4Rows(this); close() }
        assertV5Data(helper.runMigrationsAndValidate("mig45.db", 5, true))
    }

    @Test fun v1ToV5ChainedKeepsEverything() {
        helper.createDatabase("mig15.db", 1).apply { insertV1Rows(this); close() }
        val mid = helper.runMigrationsAndValidate("mig15.db", 4, true)
        mid.execSQL("INSERT INTO work_classifications(`key`, isWork, source) VALUES ('reunion', 1, 'USER')")
        mid.execSQL("UPDATE activities SET googleEventId = 77 WHERE id = 1")
        mid.close()
        assertV5Data(helper.runMigrationsAndValidate("mig15.db", 5, true))
    }
}
