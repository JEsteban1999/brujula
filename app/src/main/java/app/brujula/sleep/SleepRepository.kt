package app.brujula.sleep

import app.brujula.data.db.SleepDao
import app.brujula.data.db.SleepRecordEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalDateTime

class SleepRepository(private val dao: SleepDao) {
    fun observeRange(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, SleepRecord>> =
        dao.observeRange(from, to).map { rows -> rows.associate { it.night to SleepRecord(it.night, it.bedAt, it.wakeAt) } }

    suspend fun confirmBedtime(night: LocalDate, at: LocalDateTime) = dao.setBedAt(night, at)

    /** `false` si esa noche ya tenía despertar. */
    suspend fun recordWake(night: LocalDate, at: LocalDateTime): Boolean = dao.setWakeIfAbsent(night, at)
}
