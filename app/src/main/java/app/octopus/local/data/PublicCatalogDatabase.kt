package app.octopus.local.data

import androidx.room.*
import app.octopus.local.catalog.*

@Entity(tableName = "public_catalog_pages")
data class PublicCatalogRecord(@PrimaryKey val url: String, val html: String, val observedAt: Long)

@Dao
interface PublicCatalogDao {
    @Query("SELECT * FROM public_catalog_pages WHERE url = :url") suspend fun load(url: String): PublicCatalogRecord?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: PublicCatalogRecord)
    @Query("DELETE FROM public_catalog_pages WHERE url NOT IN (SELECT url FROM public_catalog_pages ORDER BY observedAt DESC LIMIT 30)") suspend fun prune()
    @Query("DELETE FROM public_catalog_pages") suspend fun clear()
    @Transaction suspend fun save(row: PublicCatalogRecord) { put(row); prune() }
}
@Database(entities=[PublicCatalogRecord::class], version=1, exportSchema=false)
abstract class PublicCatalogDatabase : RoomDatabase() { abstract fun pages(): PublicCatalogDao }

class RoomPublicCatalogCache(private val dao: PublicCatalogDao) : PublicCatalogCache {
    override suspend fun load(url: String): PublicCatalogPage? = dao.load(url)?.let { PublicCatalogPage(it.url,it.html,it.observedAt) }
    override suspend fun save(page: PublicCatalogPage) {
        require(PublicCatalogUrls.allowed(page.url) && page.observedAt >= 0 &&
            page.html.toByteArray(Charsets.UTF_8).size <= HungerStationPublicParser.MAX_HTML_BYTES)
        dao.save(PublicCatalogRecord(page.url,page.html,page.observedAt))
    }
    override suspend fun clear() = dao.clear()
}
