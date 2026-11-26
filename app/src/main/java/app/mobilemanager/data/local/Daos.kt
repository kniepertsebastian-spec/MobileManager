package app.mobilemanager.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface WhitelistDao {
    @Query("SELECT * FROM whitelisted_apps")
    fun observeAll(): Flow<List<WhitelistedAppEntity>>

    @Query("SELECT packageName FROM whitelisted_apps")
    suspend fun getPackageNames(): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: WhitelistedAppEntity)

    @Query("DELETE FROM whitelisted_apps WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}

@Dao
interface BluetoothRuleDao {
    @Query("SELECT * FROM bluetooth_rules ORDER BY deviceName")
    fun observeAll(): Flow<List<BluetoothRuleEntity>>

    @Query("SELECT * FROM bluetooth_rules WHERE deviceAddress = :address AND isEnabled = 1")
    suspend fun getEnabledForDevice(address: String): List<BluetoothRuleEntity>

    @Upsert
    suspend fun upsert(rule: BluetoothRuleEntity): Long

    @Query("UPDATE bluetooth_rules SET isEnabled = :enabled WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean)

    @Delete
    suspend fun delete(rule: BluetoothRuleEntity)
}

@Dao
interface CleanupHistoryDao {
    @Insert
    suspend fun insert(entry: CleanupHistoryEntity)

    @Query("SELECT * FROM cleanup_history WHERE freedBytes > 0 ORDER BY timestamp DESC LIMIT 1")
    fun observeLastStorageCleanup(): Flow<CleanupHistoryEntity?>

    @Query("SELECT * FROM cleanup_history ORDER BY timestamp DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<CleanupHistoryEntity>>
}

@Dao
interface ImageAnalysisDao {
    @Query("SELECT * FROM image_analysis")
    suspend fun getAll(): List<ImageAnalysisEntity>

    @Upsert
    suspend fun upsertAll(entities: List<ImageAnalysisEntity>)

    @Query("UPDATE image_analysis SET decision = :decision, updatedAt = :now WHERE mediaId IN (:ids)")
    suspend fun setDecision(ids: List<Long>, decision: Int, now: Long)

    @Query("DELETE FROM image_analysis WHERE mediaId IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

}
