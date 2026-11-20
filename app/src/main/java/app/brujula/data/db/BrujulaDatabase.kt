package app.brujula.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [ActivityEntity::class, ActivityCompletionEntity::class, HabitEntity::class, HabitLogEntity::class, WorkClassificationEntity::class,
        ActivityOverrideEntity::class, ProposalEntity::class, SleepRecordEntity::class,
    ],
    version = 6,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4), AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6)],
)
@TypeConverters(Converters::class)
abstract class BrujulaDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao
    abstract fun habitDao(): HabitDao
    abstract fun workClassificationDao(): WorkClassificationDao
    abstract fun proposalDao(): ProposalDao
    abstract fun sleepDao(): SleepDao

    companion object {
        fun create(context: Context): BrujulaDatabase =
            Room.databaseBuilder(context.applicationContext, BrujulaDatabase::class.java, "brujula.db").build()
    }
}
