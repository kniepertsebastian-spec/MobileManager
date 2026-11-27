package app.mobilemanager

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.mobilemanager.data.backup.EncryptedBackup
import app.mobilemanager.data.network.NetworkPolicyController
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

    @Inject lateinit var networkPolicy: NetworkPolicyController

    override fun onCreate() {
        // Phase 4.6: vorbereitete Wiederherstellung einspielen, bevor DataStore/Room die Dateien öffnen.
        EncryptedBackup.applyPendingRestore(this)
        super.onCreate()
        shizukuManager.init()
        notifications.createChannels()
        // Phase 4.1: Firewall-Regeln nach Prozessstart (z. B. nach Neustart) wiederherstellen.
        networkPolicy.start()
    }
}
