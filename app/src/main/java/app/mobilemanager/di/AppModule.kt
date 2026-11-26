package app.mobilemanager.di

import android.content.Context
import androidx.room.Room
import app.mobilemanager.data.local.AppDatabase
import app.mobilemanager.data.local.BluetoothRuleDao
import app.mobilemanager.data.local.CleanupHistoryDao
import app.mobilemanager.data.local.ImageAnalysisDao
import app.mobilemanager.data.local.WhitelistDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.NAME).build()

    @Provides
    fun provideWhitelistDao(db: AppDatabase): WhitelistDao = db.whitelistDao()

    @Provides
    fun provideBluetoothRuleDao(db: AppDatabase): BluetoothRuleDao = db.bluetoothRuleDao()

    @Provides
    fun provideCleanupHistoryDao(db: AppDatabase): CleanupHistoryDao = db.cleanupHistoryDao()

    @Provides
    fun provideImageAnalysisDao(db: AppDatabase): ImageAnalysisDao = db.imageAnalysisDao()
}
