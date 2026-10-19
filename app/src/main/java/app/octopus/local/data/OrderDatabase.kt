package app.octopus.local.data

import androidx.room.*
import app.octopus.local.domain.CanonicalCart
import java.util.UUID

@Entity(tableName = "saved_orders")
data class SavedOrder(@PrimaryKey val id: String, val payload: ByteArray, val updatedAtMillis: Long)

@Dao
interface SavedOrderDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(order: SavedOrder)
    @Query("SELECT * FROM saved_orders WHERE id = :id LIMIT 1") suspend fun get(id: String): SavedOrder?
    @Query("DELETE FROM saved_orders") suspend fun clear()
}

@Database(entities = [SavedOrder::class], version = 1, exportSchema = false)
abstract class OrderDatabase : RoomDatabase() { abstract fun orders(): SavedOrderDao }

class OrderRepository(private val dao: SavedOrderDao) {
    suspend fun save(cart: CanonicalCart, id: String = UUID.randomUUID().toString()): String {
        require(id.length in 1..100)
        dao.put(SavedOrder(id, CanonicalCartCodec.encode(cart), System.currentTimeMillis()))
        return id
    }
    suspend fun load(id: String): CanonicalCart? = dao.get(id)?.let { CanonicalCartCodec.decode(it.payload) }
    suspend fun clear() = dao.clear()
}
