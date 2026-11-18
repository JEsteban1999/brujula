package app.brujula.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [ActivityEntity::class, ActivityCompletionEntity::class, HabitEntity::class, HabitLogEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class BrujulaDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao
    abstract fun habitDao(): HabitDao

    companion object {
        fun create(context: Context): BrujulaDatabase =
            Room.databaseBuilder(context.applicationContext, BrujulaDatabase::class.java, "brujula.db").build()
    }
}
