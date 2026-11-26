package app.mobilemanager.data.extras

import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.mobilemanager.data.local.AppVolumeRuleDao
import app.mobilemanager.data.local.AppVolumeRuleEntity
import app.mobilemanager.data.permissions.PermissionChecker
import app.mobilemanager.data.work.AppNotifications
import app.mobilemanager.domain.extras.FocusBrakePolicy
import app.mobilemanager.domain.extras.ForegroundFilter
import app.mobilemanager.domain.extras.PerAppVolumePolicy
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Zentrale Stelle für App-Wechsel – gespeist vom UsageStats-Dienst (ca. 1 s Verzögerung).
 * Verteilt an Fokus-Bremse und Lautstärke pro App. Bewusst ohne Bedienungshilfe, damit die einzige
 * Bedienungshilfe der App auf die Android-Einstellungen beschränkt bleibt (Play-Protect-Härtung).
 */
@Singleton
class ForegroundAppTracker @Inject constructor(
    @ApplicationContext private val context: Context,
    settingsRepository: ExtrasSettingsRepository,
    private val focusBrake: FocusBrakeController,
    private val volume: PerAppVolumeController,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    val settings: StateFlow<ExtrasSettings?> = settingsRepository.settings.stateIn(scope, SharingStarted.Eagerly, null)
    private var lastPackage: String? = null

    private val keyboards: Set<String> by lazy {
        context.getSystemService(InputMethodManager::class.java)?.enabledInputMethodList?.map { it.packageName }?.toSet().orEmpty()
    }

    fun onForeground(packageName: String?, className: String?) {
        val pkg = packageName ?: return
        val s = settings.value ?: return
        if (!s.needsForegroundApp) return
        if (!ForegroundFilter.isRelevant(pkg, className, context.packageName, keyboards)) return
        main.post {
            if (pkg == lastPackage) return@post
            val previous = lastPackage
            lastPackage = pkg
            if (s.focusEnabled) focusBrake.onAppChanged(pkg, previous, s)
            if (s.volumeEnabled) volume.onAppChanged(pkg)
        }
    }

    /** Beim Ausschalten eines Moduls gemerkte Lautstärke zurücksetzen. */
    fun reset() {
        main.post {
            lastPackage = null
            volume.restore()
        }
    }
}

@Singleton
class FocusBrakeController @Inject constructor(@ApplicationContext private val context: Context) {
    private val graceUntil = mutableMapOf<String, Long>()
    private val overlay by lazy { FocusOverlay(context) }

    /** Läuft auf dem Main-Thread. */
    fun onAppChanged(packageName: String, previous: String?, settings: ExtrasSettings) {
        val now = System.currentTimeMillis()
        if (!FocusBrakePolicy.shouldBrake(packageName, previous, settings.focusPackages, graceUntil[packageName], now)) {
            if (overlay.isShowing && packageName != overlay.packageName) overlay.dismiss()
            return
        }
        if (!Settings.canDrawOverlays(context)) return
        overlay.show(
            packageName = packageName,
            seconds = settings.focusCountdownSeconds,
            onCancel = {
                overlay.dismiss()
                goHome()
            },
            onOpen = {
                graceUntil[packageName] = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(settings.focusGraceMinutes.toLong())
                overlay.dismiss()
            },
        )
    }

    private fun goHome() {
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}

/** Meilenstein 3.4.2: setzt beim App-Wechsel die gespeicherte Medienlautstärke und stellt sie danach wieder her. */
@Singleton
class PerAppVolumeController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: AppVolumeRuleDao,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val rules: StateFlow<Map<String, Int>> = dao.observeAll()
        .map { list -> list.associate { it.packageName to it.volumePercent } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())
    private var savedIndex: Int? = null
    private val audio get() = context.getSystemService(AudioManager::class.java)

    val observeRules: Flow<List<AppVolumeRuleEntity>> get() = dao.observeAll()

    suspend fun save(packageName: String, percent: Int) = dao.upsert(AppVolumeRuleEntity(packageName, percent.coerceIn(0, 100)))

    suspend fun delete(packageName: String) = dao.delete(packageName)

    fun onAppChanged(packageName: String) {
        val am = audio ?: return
        val stream = AudioManager.STREAM_MUSIC
        val decision = PerAppVolumePolicy.onForeground(
            packageName, rules.value, savedIndex, am.getStreamVolume(stream), am.getStreamMinVolume(stream), am.getStreamMaxVolume(stream),
        )
        savedIndex = decision.savedIndex
        decision.targetIndex?.let { runCatching { am.setStreamVolume(stream, it, 0) } }
    }

    fun restore() {
        val index = savedIndex ?: return
        savedIndex = null
        runCatching { audio?.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0) }
    }
}

/**
 * Vordergrunddienst, der bei eingeschaltetem Display jede Sekunde die UsageStats-Ereignisse liest.
 * Bei ausgeschaltetem Display ruht er komplett.
 */
@AndroidEntryPoint
class ForegroundMonitorService : Service() {

    @Inject lateinit var tracker: ForegroundAppTracker
    @Inject lateinit var notifications: AppNotifications
    @Inject lateinit var coordinator: ExtrasCoordinator

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this,
            AppNotifications.ID_FOREGROUND_MONITOR,
            notifications.foregroundMonitorNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        scope.launch { interactive().collectLatest { on -> if (on) poll() } }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    private suspend fun poll() {
        val usm = getSystemService(UsageStatsManager::class.java) ?: return
        var since = System.currentTimeMillis() - INITIAL_LOOKBACK_MS
        var checks = 0
        while (scope.isActive) {
            val now = System.currentTimeMillis()
            val events = runCatching { usm.queryEvents(since, now) }.getOrNull()
            var latest: Pair<String, String?>? = null
            val event = UsageEvents.Event()
            while (events != null && events.getNextEvent(event)) {
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED) latest = event.packageName to event.className
            }
            since = now
            latest?.let { (pkg, cls) -> tracker.onForeground(pkg, cls) }
            // Gelegentlich prüfen, ob der Dienst noch gebraucht wird (z. B. Nutzungszugriff entzogen).
            if (++checks % 30 == 0) coordinator.sync()
            delay(POLL_INTERVAL_MS)
        }
    }

    private fun interactive(): Flow<Boolean> = callbackFlow {
        val power = getSystemService(PowerManager::class.java)
        trySend(power?.isInteractive != false)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                trySend(intent.action == Intent.ACTION_SCREEN_ON)
            }
        }
        ContextCompat.registerReceiver(
            this@ForegroundMonitorService,
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        awaitClose { unregisterReceiver(receiver) }
    }.distinctUntilChanged()

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 1_000L
        const val INITIAL_LOOKBACK_MS = 10_000L
    }
}

/**
 * Startet den UsageStats-Dienst nur, wenn ein Extra ihn wirklich braucht.
 * Ausgeschaltete Module verbrauchen damit keinerlei Hintergrundressourcen.
 */
@Singleton
class ExtrasCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: ExtrasSettingsRepository,
    private val permissions: PermissionChecker,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun sync() {
        scope.launch { apply(settings.current()) }
    }

    fun apply(s: ExtrasSettings) {
        val needsMonitor = s.needsForegroundApp && permissions.hasUsageAccess()
        val intent = Intent(context, ForegroundMonitorService::class.java)
        if (needsMonitor) {
            runCatching { ContextCompat.startForegroundService(context, intent) }
        } else {
            context.stopService(intent)
        }
    }

    fun detectionMode(): DetectionMode = if (permissions.hasUsageAccess()) DetectionMode.USAGE_STATS else DetectionMode.NONE
}

enum class DetectionMode { USAGE_STATS, NONE }
