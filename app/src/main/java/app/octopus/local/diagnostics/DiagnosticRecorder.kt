package app.octopus.local.diagnostics

import app.octopus.local.data.DiagnosticDao
import app.octopus.local.data.DiagnosticReport
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes recording, export snapshots, and deletion, including a stop-triggered report. */
class DiagnosticRecorder(private val dao: DiagnosticDao) {
    private val lock = Mutex()
    suspend fun record(report: DiagnosticReport) = lock.withLock { dao.insert(report); dao.trim() }
    suspend fun snapshot(): List<DiagnosticReport> = lock.withLock { dao.all() }
    suspend fun clear() = lock.withLock { dao.deleteAll() }
}
