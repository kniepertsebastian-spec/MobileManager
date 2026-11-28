package app.mobilemanager

import android.app.Application
import android.os.StrictMode
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import app.mobilemanager.data.backup.EncryptedBackup
import app.mobilemanager.data.extras.ExifCleaner
import app.mobilemanager.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    override fun onCreate() {
        // Phase 4.6: vorbereitete Wiederherstellung einspielen, bevor DataStore/Room die Dateien öffnen.
        EncryptedBackup.applyPendingRestore(this)
        if (BuildConfig.DEBUG) enableStrictMode()
        super.onCreate()
        shizukuManager.init()
        notifications.createChannels()
        // Phase 4.1: Firewall-Regeln nach Prozessstart (z. B. nach Neustart) wiederherstellen.
        networkPolicy.start()
        // Roadmap v2 A.2: übrig gebliebene EXIF-bereinigte Kopien abseits des Main-Threads aufräumen.
        appScope.launch { ExifCleaner.purgeStale(this@MobileManagerApp) }
    }

    /**
     * Roadmap v2 C.3: Debug-Builds melden Platten-/Netzwerkzugriffe auf dem Main-Thread und geleakte
     * Ressourcen im Logcat (Tag „StrictMode“). Nur Log, kein Absturz – Release-Builds sind nicht betroffen.
     */
    private fun enableStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .detectCustomSlowCalls()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .detectActivityLeaks()
                .detectFileUriExposure()
                .detectContentUriWithoutPermission()
                .penaltyLog()
                .build(),
        )
    }
}
