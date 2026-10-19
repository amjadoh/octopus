package app.octopus.local.diagnostics

import app.octopus.local.data.InspectionDao
import app.octopus.local.data.ScreenInspectionReport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/** A stopped session's queued write must not recreate records after the user deletes them. */
class InspectionRecorder(private val dao: InspectionDao) {
    private val lock = Mutex()
    private val epoch = AtomicLong(0)
    fun generation(): Long = epoch.get()
    suspend fun record(reports: List<ScreenInspectionReport>, generation: Long) = lock.withLock {
        if (generation != epoch.get()) return@withLock
        reports.forEach { dao.insert(it) }
        dao.trim()
    }
    suspend fun clear() = lock.withLock {
        epoch.incrementAndGet()
        dao.clear()
    }
}
