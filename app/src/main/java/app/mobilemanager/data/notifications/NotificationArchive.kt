package app.mobilemanager.data.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.mobilemanager.data.guard.GuardSettingsRepository
import app.mobilemanager.data.local.NotificationEntryDao
import app.mobilemanager.data.local.NotificationEntryEntity
import app.mobilemanager.di.AppCoroutines
import app.mobilemanager.domain.guard.DigestPolicy
import app.mobilemanager.domain.guard.SensitiveApps
import app.mobilemanager.domain.model.ArchivedNotification
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: NotificationEntryDao,
    private val cipher: NotificationCipher,
) {
    /** Volltextsuche über App-Name, Titel und Text – nach dem Entschlüsseln im Speicher über das 7-Tage-Fenster. */
    fun search(query: String, packageName: String?): Flow<List<ArchivedNotification>> {
        val q = query.trim()
        return dao.observeWindow(packageName, WINDOW_LIMIT).map { list ->
            list.asSequence()
                .map { it.toDomain() }
                .filter { q.isEmpty() || it.title.contains(q, true) || it.text.contains(q, true) || it.appLabel.contains(q, true) }
                .take(RESULT_LIMIT)
                .toList()
        }.flowOn(Dispatchers.Default)
    }

    val apps = dao.observeApps()

    val pendingDigest: Flow<List<ArchivedNotification>> =
        dao.observePendingDigest().map { list -> list.map { it.toDomain() } }.flowOn(Dispatchers.Default)

    /** Für den Digest-Worker: entschlüsselte, noch nicht zugestellte Meldungen. */
    suspend fun pendingDigestOnce(): List<ArchivedNotification> = dao.pendingDigest().map { it.toDomain() }

    suspend fun markDelivered(ids: List<Long>) = dao.markDelivered(ids)

    suspend fun insert(entry: NotificationEntryEntity) =
        dao.insert(entry.copy(title = cipher.seal(entry.title), text = cipher.seal(entry.text)))

    /** Einmalig: Einträge aus der Zeit vor der Verschlüsselung nachträglich verschlüsseln. */
    suspend fun sealLegacyEntries() {
        dao.unsealed(SEALED_PREFIX).forEach { e ->
            val title = if (cipher.isSealed(e.title)) e.title else cipher.seal(e.title)
            val text = if (cipher.isSealed(e.text)) e.text else cipher.seal(e.text)
            dao.updateContent(e.id, title, text)
        }
    }

    suspend fun clear() = dao.clear()

    suspend fun purgeOld(): Int =
        dao.deleteOlderThan(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(ARCHIVE_DAYS))

    fun isListenerEnabled(): Boolean =
        context.getSystemService(NotificationManager::class.java)
            ?.isNotificationListenerAccessGranted(ComponentName(context, NotificationArchiveService::class.java)) == true

    private fun NotificationEntryEntity.toDomain() = ArchivedNotification(
        id = id,
        packageName = packageName,
        appLabel = appLabel,
        title = cipher.open(title) ?: UNREADABLE,
        text = cipher.open(text) ?: "",
        postedAt = postedAt,
        isDigest = digestState != NotificationEntryEntity.DIGEST_NONE,
    )

    companion object {
        const val ARCHIVE_DAYS = 7L
        private const val WINDOW_LIMIT = 20_000
        private const val RESULT_LIMIT = 500
        private const val SEALED_PREFIX = "§e1§"
        private const val UNREADABLE = "(nicht mehr lesbar)"
    }
}

/**
 * Meilenstein 2.5: 7-Tage-Archiv und Digest-Modus.
 *
 * - Laufende Benachrichtigungen (Musik, Timer, Navi, Vordergrunddienste) werden nie angefasst.
 * - Gruppen-Summaries werden nicht archiviert (die Einzelmeldungen kommen separat), damit nichts
 *   doppelt landet.
 */
@AndroidEntryPoint
class NotificationArchiveService : NotificationListenerService() {

    @Inject lateinit var repository: NotificationRepository
    @Inject lateinit var settings: GuardSettingsRepository
    @Inject lateinit var locker: app.mobilemanager.data.locker.LockerRepository

    private val scope = AppCoroutines.scope("NotificationArchive", Dispatchers.IO)

    override fun onListenerConnected() {
        super.onListenerConnected()
        scope.launch { repository.sealLegacyEntries() }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val notification = sbn.notification ?: return
        val isOngoing = sbn.isOngoing ||
            notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0 ||
            notification.flags and Notification.FLAG_ONGOING_EVENT != 0
        if (isOngoing) return
        val isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0

        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: extras.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString().orEmpty()

        scope.launch {
            val s = settings.current()
            val isDigest = s.digestEnabled &&
                DigestPolicy.matches(sbn.packageName, title, text, s.digestApps, s.digestKeywords)
            if (isDigest) {
                // Sofort stumm abbrechen; die Meldung erscheint gesammelt zur Digest-Uhrzeit.
                runCatching { cancelNotification(sbn.key) }
            }
            // Phase 4.3: Benachrichtigungen gesperrter Apps ausblenden – lesbar bleiben sie im (gesperrten) Archiv.
            val lock = locker.current()
            val hidden = lock.enabled && lock.hideNotifications && sbn.packageName in lock.packages
            if (hidden) runCatching { cancelNotification(sbn.key) }
            if (isGroupSummary || (title.isBlank() && text.isBlank())) return@launch
            if (!isDigest && !hidden) {
                if (!s.notificationArchiveEnabled) return@launch
                // Phase 2.2: TANs, 2FA-Codes und Tresor-Meldungen nicht aufheben.
                if (SensitiveApps.excluded(sbn.packageName, s.archiveExcludeSensitive, s.archiveExcludedApps)) return@launch
            }
            repository.insert(
                NotificationEntryEntity(
                    notificationKey = sbn.key,
                    packageName = sbn.packageName,
                    appLabel = labelOf(sbn.packageName),
                    title = title,
                    text = text,
                    postedAt = sbn.postTime,
                    digestState = if (isDigest) NotificationEntryEntity.DIGEST_PENDING else NotificationEntryEntity.DIGEST_NONE,
                ),
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun labelOf(pkg: String): String = runCatching {
        packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0)).loadLabel(packageManager).toString()
    }.getOrDefault(pkg)
}
