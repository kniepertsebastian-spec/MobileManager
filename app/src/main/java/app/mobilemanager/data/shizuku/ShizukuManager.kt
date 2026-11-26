package app.mobilemanager.data.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import app.mobilemanager.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

enum class ShizukuState {
    /** Shizuku-App ist nicht installiert. */
    NOT_INSTALLED,
    /** Installiert, aber der Dienst läuft nicht (z. B. nach einem Neustart). */
    NOT_RUNNING,
    /** Dienst läuft, MobileManager hat aber noch keine Berechtigung. */
    NO_PERMISSION,
    READY,
}

/**
 * Kapselt sämtliche Shizuku-Aufrufe. Jeder Aufruf ist abgesichert, denn die Shizuku-API
 * wirft Exceptions, solange kein Binder vorliegt – die App darf dadurch nie abstürzen.
 */
@Singleton
class ShizukuManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val _state = MutableStateFlow(computeState())
    val state: StateFlow<ShizukuState> = _state.asStateFlow()

    private val bindMutex = Mutex()
    @Volatile private var shellService: IShellService? = null
    private var pendingConnection: CompletableDeferred<IShellService?>? = null

    private val userServiceArgs by lazy {
        Shizuku.UserServiceArgs(ComponentName(context.packageName, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(BuildConfig.VERSION_CODE)
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = if (binder != null && binder.pingBinder()) IShellService.Stub.asInterface(binder) else null
            shellService = service
            pendingConnection?.complete(service)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
        }
    }

    private val binderReceived = Shizuku.OnBinderReceivedListener { refresh() }
    private val binderDead = Shizuku.OnBinderDeadListener {
        shellService = null
        refresh()
    }
    private val permissionResult = Shizuku.OnRequestPermissionResultListener { _, _ -> refresh() }

    fun init() {
        Shizuku.addBinderReceivedListenerSticky(binderReceived)
        Shizuku.addBinderDeadListener(binderDead)
        Shizuku.addRequestPermissionResultListener(permissionResult)
    }

    fun refresh() {
        _state.value = computeState()
    }

    private fun computeState(): ShizukuState {
        if (!isShizukuInstalled()) return ShizukuState.NOT_INSTALLED
        val alive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
        if (!alive) return ShizukuState.NOT_RUNNING
        val granted = runCatching {
            !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
        return if (granted) ShizukuState.READY else ShizukuState.NO_PERMISSION
    }

    private fun isShizukuInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrDefault(false)

    /** Fragt die Shizuku-Berechtigung an (zeigt den Shizuku-Dialog). */
    fun requestPermission() {
        runCatching {
            if (Shizuku.pingBinder() && !Shizuku.shouldShowRequestPermissionRationale()) {
                Shizuku.requestPermission(REQUEST_CODE)
            }
        }.onFailure { Log.w(TAG, "Shizuku-Berechtigung konnte nicht angefragt werden", it) }
    }

    fun launchShizukuAppIntent(): Intent? =
        context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Führt `am force-stop` über den Shizuku-UserService aus.
     * @return `null` bei Erfolg, sonst eine Fehlermeldung.
     */
    suspend fun forceStop(packageName: String): String? {
        refresh()
        if (_state.value != ShizukuState.READY) return "Shizuku ist nicht aktiv"
        val service = obtainService() ?: return "Shizuku-Dienst nicht erreichbar"
        return try {
            when (val code = service.forceStop(packageName)) {
                0 -> null
                -2 -> "Ungültiger Paketname"
                else -> "am force-stop fehlgeschlagen (Code $code)"
            }
        } catch (e: Exception) {
            shellService = null
            "Shizuku-Fehler: ${e.message}"
        }
    }

    private suspend fun obtainService(): IShellService? = bindMutex.withLock {
        shellService?.takeIf { it.asBinder().pingBinder() }?.let { return it }
        val deferred = CompletableDeferred<IShellService?>()
        pendingConnection = deferred
        val bound = runCatching { Shizuku.bindUserService(userServiceArgs, connection) }
            .onFailure { Log.w(TAG, "bindUserService fehlgeschlagen", it) }
            .isSuccess
        if (!bound) return null
        withTimeoutOrNull(BIND_TIMEOUT_MS) { deferred.await() }.also { pendingConnection = null }
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val SHIZUKU_DOWNLOAD_URL = "https://shizuku.rikka.app/download/"
        private const val REQUEST_CODE = 4711
        private const val BIND_TIMEOUT_MS = 8_000L
        private const val TAG = "ShizukuManager"
    }
}
