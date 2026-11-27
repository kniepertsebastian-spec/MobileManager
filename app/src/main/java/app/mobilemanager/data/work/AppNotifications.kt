package app.mobilemanager.data.work

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.mobilemanager.MainActivity
import app.mobilemanager.R
import app.mobilemanager.data.tracker.TrackerVpnService
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
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SECURITY, "Sicherheit", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Neue Apps außerhalb des Play Stores und andere Sicherheitsereignisse"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_HEAT, "Akku-Hitzewarnung", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Warnt, wenn der Akku zu heiß wird"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_CHARGE, "Ladelimit erreicht", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Einmaliger Hinweiston, wenn das Ladelimit erreicht ist"
                setSound(
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build(),
                )
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DIGEST, "Benachrichtigungs-Digest", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Gesammelte Meldungen der Digest-Apps"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_GUARD_SERVICE, "Echtzeit-Wächter", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Dauerhafte Benachrichtigung des optionalen Echtzeit-Wächters"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_TRACKER, "Tracker-Blocker", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Dauerhafte Benachrichtigung des lokalen Tracker-Blockers"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_FOREGROUND_MONITOR, "Fokus-Bremse & Lautstärke", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Nur aktiv, wenn Fokus-Bremse oder Lautstärke pro App ohne Bedienungshilfe laufen"
                setShowBadge(false)
            },
        )
    }

    fun showSecurityEvent(title: String, text: String) = show(
        ID_SECURITY, title, text, MainActivity.DESTINATION_SECURITY, CHANNEL_SECURITY,
    )

    fun showHeatWarning(temperature: Float) = show(
        ID_HEAT,
        "Akku zu heiß: ${"%.1f".format(temperature)} °C",
        "Gerät aus der Sonne nehmen, Ladekabel abziehen und rechenintensive Apps schließen.",
        MainActivity.DESTINATION_BATTERY,
        CHANNEL_HEAT,
        priority = NotificationCompat.PRIORITY_HIGH,
    )

    fun cancelHeatWarning() = NotificationManagerCompat.from(context).cancel(ID_HEAT)

    fun showChargeLimitReached(level: Int) = show(
        ID_CHARGE,
        "Ladelimit erreicht: $level %",
        "Zum Schonen des Akkus kannst du das Ladegerät jetzt abziehen.",
        MainActivity.DESTINATION_BATTERY,
        CHANNEL_CHARGE,
    )

    fun showExpiredFiles(screenshots: Int, downloads: Int) {
        val parts = buildList {
            if (screenshots > 0) add("$screenshots alte Screenshots")
            if (downloads > 0) add("$downloads alte Downloads")
        }
        show(
            ID_EXPIRED,
            "Alte Dateien gefunden",
            "${parts.joinToString(" und ")} gefunden – jetzt prüfen?",
            MainActivity.DESTINATION_EXPIRED_REVIEW,
        )
    }

    fun showDigest(lines: List<String>) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val style = NotificationCompat.InboxStyle().setBigContentTitle("${lines.size} gesammelte Meldungen")
        lines.take(MAX_DIGEST_LINES).forEach { style.addLine(it) }
        if (lines.size > MAX_DIGEST_LINES) style.setSummaryText("+${lines.size - MAX_DIGEST_LINES} weitere")
        val notification = NotificationCompat.Builder(context, CHANNEL_DIGEST)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Digest: ${lines.size} Meldungen")
            .setContentText(lines.first())
            .setStyle(style)
            .setContentIntent(pendingIntent(ID_DIGEST, MainActivity.DESTINATION_NOTIFICATIONS))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(ID_DIGEST, notification)
    }

    fun guardServiceNotification(): Notification =
        NotificationCompat.Builder(context, CHANNEL_GUARD_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Echtzeit-Wächter aktiv")
            .setContentText("Überwacht Akku und neue App-Installationen")
            .setContentIntent(pendingIntent(ID_GUARD_SERVICE, MainActivity.DESTINATION_BATTERY))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    // ---------------------------------------------------------------- Phase 3

    fun trackerServiceNotification(blocked: Int): Notification {
        val stop = PendingIntent.getService(
            context, ID_TRACKER_SERVICE,
            Intent(context, TrackerVpnService::class.java).setAction(TrackerVpnService.ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_TRACKER)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Tracker-Blocker aktiv")
            .setContentText(if (blocked == 0) "Filtert DNS-Anfragen lokal auf dem Gerät" else "$blocked Tracker-Anfragen blockiert")
            .setContentIntent(pendingIntent(ID_TRACKER_SERVICE, MainActivity.DESTINATION_TRACKER))
            .addAction(0, "Ausschalten", stop)
            .setOngoing(true)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    fun updateTrackerServiceNotification(blocked: Int) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        NotificationManagerCompat.from(context).notify(ID_TRACKER_SERVICE, trackerServiceNotification(blocked))
    }

    fun trackerConfigureIntent(): PendingIntent = pendingIntent(ID_TRACKER_CONFIGURE, MainActivity.DESTINATION_TRACKER)

    fun showTrackerRevoked() = show(
        ID_TRACKER_REVOKED,
        "Tracker-Blocker beendet",
        "Ein anderes VPN wurde gestartet oder die VPN-Berechtigung entzogen. Android erlaubt nur ein aktives VPN.",
        MainActivity.DESTINATION_TRACKER,
    )

    fun foregroundMonitorNotification(): Notification =
        NotificationCompat.Builder(context, CHANNEL_FOREGROUND_MONITOR)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Fokus-Bremse & Lautstärke aktiv")
            .setContentText("Tipp: Mit der Bedienungshilfe reagiert MobileManager sofort und ohne diese Meldung.")
            .setContentIntent(pendingIntent(ID_FOREGROUND_MONITOR, MainActivity.DESTINATION_EXTRAS))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

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

    /** Phase 4: Hinweise aus Netzwerk-Wächter, App-Sperre und Selbstschutz (Kanal „Sicherheit“). */
    fun showInfo(id: Int, title: String, text: String, destination: String) =
        show(id, title, text, destination, CHANNEL_SECURITY)

    fun cancel(id: Int) = NotificationManagerCompat.from(context).cancel(id)

    /** Dauerhafte, stille Benachrichtigung für Hilfsdienste (z. B. Koffein-Modus). */
    fun serviceNotification(title: String, text: String, destination: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_GUARD_SERVICE)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pendingIntent(ID_SERVICE_TAP, destination))
            .setOngoing(true)
            .setSilent(true)
            .build()

    private fun pendingIntent(id: Int, destination: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_DESTINATION, destination)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun show(
        id: Int,
        title: String,
        text: String,
        destination: String,
        channel: String = CHANNEL_RESULTS,
        priority: Int = NotificationCompat.PRIORITY_DEFAULT,
    ) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent(id, destination))
            .setPriority(priority)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    companion object {
        private const val CHANNEL_RESULTS = "results"
        private const val CHANNEL_SECURITY = "security"
        private const val CHANNEL_HEAT = "battery_heat"
        private const val CHANNEL_CHARGE = "battery_charge"
        private const val CHANNEL_DIGEST = "digest"
        private const val CHANNEL_GUARD_SERVICE = "guard_service"
        private const val ID_APP_STOPPER = 1001
        private const val ID_SCAN = 1002
        private const val ID_SECURITY = 1003
        private const val ID_HEAT = 1004
        private const val ID_CHARGE = 1005
        private const val ID_EXPIRED = 1006
        private const val ID_DIGEST = 1007
        const val ID_GUARD_SERVICE = 1008
        private const val CHANNEL_TRACKER = "tracker_service"
        private const val CHANNEL_FOREGROUND_MONITOR = "foreground_monitor"
        const val ID_TRACKER_SERVICE = 1009
        private const val ID_TRACKER_CONFIGURE = 1010
        private const val ID_TRACKER_REVOKED = 1011
        const val ID_FOREGROUND_MONITOR = 1012
        const val ID_UNSAFE_WIFI = 1013
        const val ID_DATA_LIMIT = 1014
        const val ID_WATCHDOG = 1015
        const val ID_PERMISSION_DIFF = 1016
        const val ID_USB = 1017
        const val ID_LOCKER = 1018
        const val ID_CAFFEINE = 1019
        const val ID_BLOCKLIST = 1020
        const val ID_TEMP_PERMISSION = 1021
        private const val ID_SERVICE_TAP = 1099
        private const val MAX_DIGEST_LINES = 7
    }
}
