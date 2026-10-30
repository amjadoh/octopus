package app.octopus.local.data

import androidx.room.*
import app.octopus.local.providers.CatalogLibrary
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Entity(tableName = "catalog_library")
data class CatalogLibraryRecord(@PrimaryKey val id: Int = 1, val payload: ByteArray)

@Dao
interface CatalogLibraryDao {
    @Query("SELECT * FROM catalog_library WHERE id = 1 LIMIT 1") suspend fun get(): CatalogLibraryRecord?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(record: CatalogLibraryRecord)
    @Query("DELETE FROM catalog_library") suspend fun clear()
}

/** Separate database preserves the existing diagnostics and saved-order schemas. */
@Database(entities=[CatalogLibraryRecord::class],version=1,exportSchema=false)
abstract class CatalogLibraryDatabase : RoomDatabase() { abstract fun library(): CatalogLibraryDao }

class CatalogLibraryRepository(private val dao: CatalogLibraryDao) {
    private val mutex=Mutex()
    suspend fun load(): CatalogLibrary = mutex.withLock { read() }
    suspend fun save(library: CatalogLibrary) = mutex.withLock {
        dao.put(CatalogLibraryRecord(payload=CatalogLibraryCodec.encode(library)))
    }
    suspend fun clear() = mutex.withLock { dao.clear() }
    /** Serialize read-merge-write so two completed captures cannot overwrite one another. */
    suspend fun update(transform: (CatalogLibrary)->CatalogLibrary): CatalogLibrary = mutex.withLock {
        val next=transform(read())
        dao.put(CatalogLibraryRecord(payload=CatalogLibraryCodec.encode(next)))
        next
    }
    private suspend fun read(): CatalogLibrary = dao.get()?.let { CatalogLibraryCodec.decode(it.payload) } ?: CatalogLibrary()
}
