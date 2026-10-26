package app.octopus.local.data

import android.util.AtomicFile
import app.octopus.local.automation.BasketJournalStore
import java.io.File
import java.util.Properties

/** App-private and excluded from backups. Contains hashes only, never addresses or basket text. */
class AtomicBasketJournalStore(file: File) : BasketJournalStore {
    private val atomic = AtomicFile(file)
    override fun read(): Map<String, String> {
        val properties = Properties()
        try { atomic.openRead().use { properties.load(it) } }
        catch (missing: java.io.FileNotFoundException) {
            check(!atomic.baseFile.exists()) { "تعذر قراءة سجل تعديل السلة؛ لم أكرر الإجراء." }
        }
        if (!atomic.baseFile.exists()) return emptyMap()
        check(properties.getProperty("schema") == "1") { "سجل تعديل السلة غير مقروء؛ لم أكرر الإجراء." }
        return properties.stringPropertyNames().filter { it != "schema" }.associateWith { properties.getProperty(it) }
    }
    override fun write(values: Map<String, String>) {
        val stream = atomic.startWrite()
        try {
            Properties().apply { putAll(values); setProperty("schema", "1") }.store(stream, null)
            atomic.finishWrite(stream)
        } catch (failure: Exception) { atomic.failWrite(stream); throw failure }
    }
}
