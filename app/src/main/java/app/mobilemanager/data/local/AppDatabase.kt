package app.mobilemanager.data.local

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        WhitelistedAppEntity::class,
        BluetoothRuleEntity::class,
        CleanupHistoryEntity::class,
        ImageAnalysisEntity::class,
        SecurityEventEntity::class,
        NotificationEntryEntity::class,
        KeptExpiredFileEntity::class,
        TrackerAppStatEntity::class,
        TrackerDomainStatEntity::class,
        AppVolumeRuleEntity::class,
    ],
    version = 3,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun whitelistDao(): WhitelistDao
    abstract fun bluetoothRuleDao(): BluetoothRuleDao
    abstract fun cleanupHistoryDao(): CleanupHistoryDao
    abstract fun imageAnalysisDao(): ImageAnalysisDao
    abstract fun securityEventDao(): SecurityEventDao
    abstract fun notificationEntryDao(): NotificationEntryDao
    abstract fun keptExpiredFileDao(): KeptExpiredFileDao
    abstract fun trackerStatsDao(): TrackerStatsDao
    abstract fun appVolumeRuleDao(): AppVolumeRuleDao

    companion object {
        const val NAME = "mobilemanager.db"
    }
}
