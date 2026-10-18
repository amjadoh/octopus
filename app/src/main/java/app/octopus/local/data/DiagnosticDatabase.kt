package app.octopus.local.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "diagnostic_reports")
data class DiagnosticReport(
    @PrimaryKey val id: String,
    val provider: String,
    val providerVersion: String,
    val adapterVersion: String,
    val capturedAt: Long,
    val outcome: String,
    val candidateTotalHalala: Long?,
    val fieldsJson: String,
    val visibleNodes: Int,
    val addressVerified: Boolean = false,
    val cartVerified: Boolean = false,
    val finalPriceVerified: Boolean = false,
)

@Dao
interface DiagnosticDao {
    @Query("SELECT * FROM diagnostic_reports ORDER BY capturedAt DESC LIMIT 100")
    fun observe(): Flow<List<DiagnosticReport>>
    @Query("SELECT * FROM diagnostic_reports ORDER BY capturedAt DESC LIMIT 100")
    suspend fun all(): List<DiagnosticReport>
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(report: DiagnosticReport)
    @Query("DELETE FROM diagnostic_reports WHERE id NOT IN (SELECT id FROM diagnostic_reports ORDER BY capturedAt DESC LIMIT 100)")
    suspend fun trim()
    @Query("DELETE FROM diagnostic_reports") suspend fun deleteAll()
}

@Database(entities = [DiagnosticReport::class], version = 1, exportSchema = false)
abstract class DiagnosticDatabase : RoomDatabase() { abstract fun reports(): DiagnosticDao }
