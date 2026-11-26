package app.mobilemanager.data.work

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.mobilemanager.MainActivity
import app.mobilemanager.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppNotifications @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun createChannels() {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULTS, "Aufräum-Ergebnisse", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Ergebnisse automatischer Scans und App-Stopps"
            },
        )
    }

    fun showAppsStopped(count: Int) = show(
        ID_APP_STOPPER,
        "App-Stopper",
        "$count inaktive Apps wurden beendet.",
        MainActivity.DESTINATION_APP_STOPPER,
    )

    fun showShizukuUnavailable() = show(
        ID_APP_STOPPER,
        "App-Stopper pausiert",
        "Shizuku läuft nicht (z. B. nach einem Neustart). Bitte Shizuku starten.",
        MainActivity.DESTINATION_PERMISSIONS,
    )

    fun showScanResult(duplicates: Int, withoutPeople: Int) = show(
        ID_SCAN,
        "Speicher-Scan abgeschlossen",
        "$duplicates Duplikate und $withoutPeople alte Fotos ohne Personen gefunden – jetzt prüfen?",
        MainActivity.DESTINATION_CLEANER,
    )

    private fun show(id: Int, title: String, text: String, destination: String) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            return
        }
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_DESTINATION, destination)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private companion object {
        const val CHANNEL_RESULTS = "results"
        const val ID_APP_STOPPER = 1001
        const val ID_SCAN = 1002
    }
}
