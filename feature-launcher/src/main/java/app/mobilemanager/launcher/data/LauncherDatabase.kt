package app.mobilemanager.launcher.data

import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kind: String,
    val hiddenCategories: String = "",
    val largeIcons: Boolean = false,
    val sortOrder: Int = 0,
)

@Entity(tableName = "items", indices = [Index("profileId"), Index("parentId")])
data class ItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val container: Int,
    val page: Int,
    val position: Int,
    val componentKey: String?,
    val title: String? = null,
    val parentId: Long? = null,
)

@Entity(tableName = "snapshots", indices = [Index("profileId")])
data class SnapshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val createdAt: Long,
    val label: String,
)

/** Kopie eines Layouts vor „Ordnen“ – Grundlage für „Rückgängig“. Behält die Original-IDs. */
@Entity(tableName = "snapshot_items", primaryKeys = ["snapshotId", "originalId"])
data class SnapshotItemEntity(
    val snapshotId: Long,
    val originalId: Long,
    val container: Int,
    val page: Int,
    val position: Int,
    val componentKey: String?,
    val title: String?,
    val parentId: Long?,
)

@Entity(tableName = "rules", indices = [Index("profileId")])
data class RuleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val profileId: Long,
    val type: String,
    val value: String,
    val label: String,
    val daysMask: Int,
    val startMinutes: Int,
    val endMinutes: Int,
    val enabled: Boolean,
)

@Dao
interface LauncherDao {
    @Query("SELECT * FROM profiles ORDER BY sortOrder, id")
    fun observeProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles ORDER BY sortOrder, id")
    suspend fun profiles(): List<ProfileEntity>

    @Insert
    suspend fun insertProfile(profile: ProfileEntity): Long

    @Upsert
    suspend fun upsertProfile(profile: ProfileEntity)

    @Query("DELETE FROM profiles WHERE id = :id")
    suspend fun deleteProfile(id: Long)

    @Query("SELECT * FROM items WHERE profileId = :profileId")
    fun observeItems(profileId: Long): Flow<List<ItemEntity>>

    @Query("SELECT * FROM items WHERE profileId = :profileId")
    suspend fun items(profileId: Long): List<ItemEntity>

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun item(id: Long): ItemEntity?

    @Insert
    suspend fun insertItem(item: ItemEntity): Long

    @Insert
    suspend fun insertItems(items: List<ItemEntity>)

    @Upsert
    suspend fun upsertItems(items: List<ItemEntity>)

    @Query("DELETE FROM items WHERE id = :id OR parentId = :id")
    suspend fun deleteItemAndChildren(id: Long)

    @Query("DELETE FROM items WHERE profileId = :profileId")
    suspend fun deleteItemsOf(profileId: Long)

    @Query("DELETE FROM items WHERE componentKey IN (:keys)")
    suspend fun deleteByKeys(keys: List<String>)

    @Query("SELECT DISTINCT componentKey FROM items WHERE componentKey IS NOT NULL")
    suspend fun allComponentKeys(): List<String>

    @Query("UPDATE items SET title = :title WHERE id = :id")
    suspend fun renameFolder(id: Long, title: String)

    @Query("SELECT * FROM snapshots WHERE profileId = :profileId ORDER BY createdAt DESC LIMIT 1")
    fun observeLatestSnapshot(profileId: Long): Flow<SnapshotEntity?>

    @Query("SELECT * FROM snapshots WHERE profileId = :profileId ORDER BY createdAt DESC")
    suspend fun snapshots(profileId: Long): List<SnapshotEntity>

    @Insert
    suspend fun insertSnapshot(snapshot: SnapshotEntity): Long

    @Insert
    suspend fun insertSnapshotItems(items: List<SnapshotItemEntity>)

    @Query("SELECT * FROM snapshot_items WHERE snapshotId = :snapshotId")
    suspend fun snapshotItems(snapshotId: Long): List<SnapshotItemEntity>

    @Query("DELETE FROM snapshot_items WHERE snapshotId = :snapshotId")
    suspend fun deleteSnapshotItems(snapshotId: Long)

    @Query("DELETE FROM snapshots WHERE id = :snapshotId")
    suspend fun deleteSnapshotRow(snapshotId: Long)

    @Transaction
    suspend fun deleteSnapshot(snapshotId: Long) {
        deleteSnapshotItems(snapshotId)
        deleteSnapshotRow(snapshotId)
    }

    @Query("SELECT * FROM rules ORDER BY id")
    fun observeRules(): Flow<List<RuleEntity>>

    @Upsert
    suspend fun upsertRule(rule: RuleEntity)

    @Query("DELETE FROM rules WHERE id = :id")
    suspend fun deleteRule(id: Long)

    @Query("DELETE FROM rules WHERE profileId = :profileId")
    suspend fun deleteRulesOf(profileId: Long)

    @Query("SELECT * FROM tidy_folders ORDER BY sortOrder, id")
    fun observeTidyFolders(): Flow<List<TidyFolderEntity>>

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM tidy_folders")
    suspend fun maxTidyFolderOrder(): Int

    @Upsert
    suspend fun upsertTidyFolder(folder: TidyFolderEntity)

    @Query("DELETE FROM tidy_folders WHERE id = :id")
    suspend fun deleteTidyFolder(id: Long)
}

/** Eigene Ordner-Regel fürs „Ordnen“. Listen sind zeilenweise gespeichert (Paket-/Klassennamen enthalten keinen Umbruch). */
@Entity(tableName = "tidy_folders")
data class TidyFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val componentKeys: String = "",
    val packagePrefixes: String = "",
    val includeSideloaded: Boolean = false,
    val includeDrawerApps: Boolean = true,
    val enabled: Boolean = true,
    val sortOrder: Int = 0,
)

@Database(
    entities = [
        ProfileEntity::class, ItemEntity::class, SnapshotEntity::class, SnapshotItemEntity::class, RuleEntity::class,
        TidyFolderEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class LauncherDatabase : RoomDatabase() {
    abstract fun dao(): LauncherDao

    companion object {
        const val NAME = "launcher.db"
    }
}
