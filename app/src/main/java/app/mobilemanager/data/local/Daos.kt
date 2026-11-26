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

@Dao
interface SecurityEventDao {
    @Insert
    suspend fun insert(event: SecurityEventEntity): Long

    @Query("SELECT * FROM security_events ORDER BY timestamp DESC LIMIT 300")
    fun observeRecent(): Flow<List<SecurityEventEntity>>

    @Query("SELECT * FROM security_events WHERE id = :id")
    suspend fun get(id: Long): SecurityEventEntity?

    @Query("UPDATE security_events SET alertState = :state WHERE id = :id")
    suspend fun setAlertState(id: Long, state: String)

    @Query("UPDATE security_events SET latitude = :latitude, longitude = :longitude WHERE id = :id")
    suspend fun setLocation(id: Long, latitude: Double, longitude: Double)

    @Query("SELECT COUNT(*) FROM security_events WHERE seen = 0")
    fun observeUnseenCount(): Flow<Int>

    @Query("UPDATE security_events SET seen = 1 WHERE seen = 0")
    suspend fun markAllSeen()

    @Query("DELETE FROM security_events")
    suspend fun clear()
}

data class NotificationAppCount(val packageName: String, val appLabel: String, val count: Int)

@Dao
interface NotificationEntryDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entry: NotificationEntryEntity): Long

    /** Volltextsuche über App-Name, Titel und Text; [packageName] = null → alle Apps. */
    @Query(
        """
        SELECT * FROM notification_entries
        WHERE (:packageName IS NULL OR packageName = :packageName)
          AND (:query = '' OR title LIKE '%' || :query || '%' OR text LIKE '%' || :query || '%'
               OR appLabel LIKE '%' || :query || '%')
        ORDER BY postedAt DESC
        LIMIT 500
        """,
    )
    fun search(query: String, packageName: String?): Flow<List<NotificationEntryEntity>>

    @Query(
        """
        SELECT packageName, MAX(appLabel) AS appLabel, COUNT(*) AS count
        FROM notification_entries GROUP BY packageName ORDER BY count DESC
        """,
    )
    fun observeApps(): Flow<List<NotificationAppCount>>

    @Query("SELECT * FROM notification_entries WHERE digestState = 1 ORDER BY postedAt")
    suspend fun pendingDigest(): List<NotificationEntryEntity>

    @Query("SELECT * FROM notification_entries WHERE digestState = 1 ORDER BY postedAt DESC")
    fun observePendingDigest(): Flow<List<NotificationEntryEntity>>

    @Query("UPDATE notification_entries SET digestState = 2 WHERE id IN (:ids)")
    suspend fun markDelivered(ids: List<Long>)

    @Query("DELETE FROM notification_entries WHERE postedAt < :cutoff")
    suspend fun deleteOlderThan(cutoff: Long): Int

    @Query("DELETE FROM notification_entries")
    suspend fun clear()
}

@Dao
interface KeptExpiredFileDao {
    @Query("SELECT mediaId FROM kept_expired_files")
    suspend fun getIds(): List<Long>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: KeptExpiredFileEntity)

    @Query("DELETE FROM kept_expired_files WHERE mediaId = :mediaId")
    suspend fun delete(mediaId: Long)

    @Query("DELETE FROM kept_expired_files WHERE mediaId IN (:ids)")
    suspend fun deleteAll(ids: List<Long>)
}

/** Ergebnis einer Ranglisten-Abfrage (Paketname oder Domain → Anzahl). */
data class TrackerCount(val name: String, val blocked: Int)

@Dao
interface TrackerStatsDao {
    @Query(
        "INSERT INTO tracker_app_stats(day, packageName, blocked) VALUES(:day, :packageName, :count) " +
            "ON CONFLICT(day, packageName) DO UPDATE SET blocked = blocked + :count",
    )
    suspend fun addApp(day: Int, packageName: String, count: Int)

    @Query(
        "INSERT INTO tracker_domain_stats(day, domain, blocked) VALUES(:day, :domain, :count) " +
            "ON CONFLICT(day, domain) DO UPDATE SET blocked = blocked + :count",
    )
    suspend fun addDomain(day: Int, domain: String, count: Int)

    @Query(
        "SELECT packageName AS name, SUM(blocked) AS blocked FROM tracker_app_stats WHERE day >= :fromDay " +
            "GROUP BY packageName ORDER BY blocked DESC LIMIT :limit",
    )
    fun topApps(fromDay: Int, limit: Int): Flow<List<TrackerCount>>

    @Query(
        "SELECT domain AS name, SUM(blocked) AS blocked FROM tracker_domain_stats WHERE day >= :fromDay " +
            "GROUP BY domain ORDER BY blocked DESC LIMIT :limit",
    )
    fun topDomains(fromDay: Int, limit: Int): Flow<List<TrackerCount>>

    @Query("SELECT COALESCE(SUM(blocked), 0) FROM tracker_app_stats WHERE day >= :fromDay")
    fun totalSince(fromDay: Int): Flow<Int>

    @Query("DELETE FROM tracker_app_stats WHERE day < :cutoffDay")
    suspend fun deleteAppsBefore(cutoffDay: Int)

    @Query("DELETE FROM tracker_domain_stats WHERE day < :cutoffDay")
    suspend fun deleteDomainsBefore(cutoffDay: Int)

    @Query("DELETE FROM tracker_app_stats")
    suspend fun clearApps()

    @Query("DELETE FROM tracker_domain_stats")
    suspend fun clearDomains()
}

@Dao
interface AppVolumeRuleDao {
    @Query("SELECT * FROM app_volume_rules ORDER BY packageName")
    fun observeAll(): Flow<List<AppVolumeRuleEntity>>

    @Upsert
    suspend fun upsert(rule: AppVolumeRuleEntity)

    @Query("DELETE FROM app_volume_rules WHERE packageName = :packageName")
    suspend fun delete(packageName: String)
}
