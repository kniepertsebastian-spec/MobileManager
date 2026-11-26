package app.mobilemanager

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.mobilemanager.data.shizuku.ShizukuManager
import app.mobilemanager.data.work.AppNotifications
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class MobileManagerApp : Application(), Configuration.Provider {

    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var shizukuManager: ShizukuManager
    @Inject lateinit var notifications: AppNotifications

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        shizukuManager.init()
        notifications.createChannels()
    }
}
