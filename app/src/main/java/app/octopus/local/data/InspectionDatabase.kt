package app.octopus.local.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "screen_inspections")
data class ScreenInspectionReport(
    @PrimaryKey val id: String,
    val provider: String,
    val providerVersion: String,
    val stage: String,
    val capturedAt: Long,
    val nodesJson: String,
    val truncated: Boolean,
    val nodeCount: Int,
    val observationJson: String = "{}",
)

@Dao
interface InspectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insert(report: ScreenInspectionReport)
    @Query("SELECT * FROM screen_inspections ORDER BY capturedAt DESC LIMIT 12") suspend fun all(): List<ScreenInspectionReport>
    @Query("SELECT * FROM screen_inspections ORDER BY capturedAt DESC LIMIT 12") fun observe(): Flow<List<ScreenInspectionReport>>
    @Query("DELETE FROM screen_inspections WHERE id NOT IN (SELECT id FROM screen_inspections ORDER BY capturedAt DESC LIMIT 12)") suspend fun trim()
    @Query("DELETE FROM screen_inspections") suspend fun clear()
}

// A separate store avoids destructive migration of the existing diagnostic history.
@Database(entities = [ScreenInspectionReport::class], version = 1, exportSchema = false)
abstract class InspectionDatabase : RoomDatabase() { abstract fun inspections(): InspectionDao }
