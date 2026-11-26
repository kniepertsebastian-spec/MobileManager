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
import app.mobilemanager.domain.guard.DigestPolicy
import app.mobilemanager.domain.model.ArchivedNotification
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: NotificationEntryDao,
) {
    fun search(query: String, packageName: String?): Flow<List<ArchivedNotification>> =
        dao.search(query.trim(), packageName).map { list -> list.map { it.toDomain() } }

    val apps = dao.observeApps()

    val pendingDigest: Flow<List<ArchivedNotification>> =
        dao.observePendingDigest().map { list -> list.map { it.toDomain() } }

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
        title = title,
        text = text,
        postedAt = postedAt,
        isDigest = digestState != NotificationEntryEntity.DIGEST_NONE,
    )

    companion object {
        const val ARCHIVE_DAYS = 7L
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

    @Inject lateinit var dao: NotificationEntryDao
    @Inject lateinit var settings: GuardSettingsRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
            if (isGroupSummary || (title.isBlank() && text.isBlank())) return@launch
            if (!s.notificationArchiveEnabled && !isDigest) return@launch
            dao.insert(
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
