package app.mobilemanager.data.local

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        WhitelistedAppEntity::class,
        BluetoothRuleEntity::class,
        CleanupHistoryEntity::class,
        ImageAnalysisEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun whitelistDao(): WhitelistDao
    abstract fun bluetoothRuleDao(): BluetoothRuleDao
    abstract fun cleanupHistoryDao(): CleanupHistoryDao
    abstract fun imageAnalysisDao(): ImageAnalysisDao

    companion object {
        const val NAME = "mobilemanager.db"
    }
}
