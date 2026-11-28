package app.mobilemanager.data.locker

import app.mobilemanager.di.AppCoroutines
import android.content.BroadcastReceiver
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.mobilemanager.data.alerts.KeystoreCipher
import app.mobilemanager.data.security.SecurityEventRepository
import app.mobilemanager.data.settings.SettingsRepository
import app.mobilemanager.domain.extras.LockerCrypto
import app.mobilemanager.domain.extras.LockerPolicy
import app.mobilemanager.domain.extras.SecondFactor
import app.mobilemanager.domain.model.SecurityEventType
import app.mobilemanager.ui.locker.LockActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private val Context.lockerStore by preferencesDataStore(name = "locker_settings")

/** Phase 4.2/4.3: Einstellungen der App-Sperre (ohne Geheimnisse). */
data class LockerSettings(
    val enabled: Boolean = false,
    val packages: Set<String> = emptySet(),
    val lockSystemScreens: Boolean = false,
    val lockSelf: Boolean = false,
    val secondFactor: SecondFactor = SecondFactor.NONE,
    /** 0 = bis das Display ausgeht. */
    val unlockMinutes: Int = 5,
    val trustedBluetooth: Set<String> = emptySet(),
    val hideNotifications: Boolean = false,
    val checkAllWindows: Boolean = false,
    val clearClipboardSeconds: Int = 0,
    val cooldownMinutes: Int = 0,
    val disableRequestedAt: Long = 0,
    val alertAfterFailures: Int = 3,
    val hasPin: Boolean = false,
    val hasTotp: Boolean = false,
    val hasNfc: Boolean = false,
    val recoveryCodesLeft: Int = 0,
) {
    val lockedPackages: Set<String> get() = if (enabled) LockerPolicy.lockedPackages(packages, lockSystemScreens) else emptySet()

    /** Ist der gewählte zweite Faktor eingerichtet? */
    val secondFactorReady: Boolean
        get() = when (secondFactor) {
            SecondFactor.NONE -> true
            SecondFactor.PIN -> hasPin
            SecondFactor.TOTP -> hasTotp
            SecondFactor.NFC -> hasNfc
        }

    val active: Boolean get() = enabled && (lockedPackages.isNotEmpty() || lockSelf)
}

@Singleton
class LockerRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: KeystoreCipher,
) {
    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val PACKAGES = stringSetPreferencesKey("packages")
        val SYSTEM = booleanPreferencesKey("lock_system")
        val SELF = booleanPreferencesKey("lock_self")
        val FACTOR = stringPreferencesKey("second_factor")
        val UNLOCK_MINUTES = intPreferencesKey("unlock_minutes")
        val TRUSTED_BT = stringSetPreferencesKey("trusted_bt")
        val HIDE_NOTIFICATIONS = booleanPreferencesKey("hide_notifications")
        val ALL_WINDOWS = booleanPreferencesKey("all_windows")
        val CLIPBOARD = intPreferencesKey("clear_clipboard_seconds")
        val COOLDOWN = intPreferencesKey("cooldown_minutes")
        val DISABLE_REQUESTED = longPreferencesKey("disable_requested_at")
        val ALERT_AFTER = intPreferencesKey("alert_after")
        val PIN_HASH = stringPreferencesKey("pin_hash")
        val PIN_SALT = stringPreferencesKey("pin_salt")
        val TOTP = stringPreferencesKey("totp_secret_enc")
        val NFC_HASH = stringPreferencesKey("nfc_hash")
        val NFC_SALT = stringPreferencesKey("nfc_salt")
        val RECOVERY = stringSetPreferencesKey("recovery_hashes")
        val FAILURES = intPreferencesKey("failures")
        val LOCKOUT_UNTIL = longPreferencesKey("lockout_until")
    }

    /** Fehlversuche überdauern einen Prozess-Neustart – sonst ließe sich die Sperrzeit per „Beenden erzwingen“ umgehen. */
    suspend fun failureState(): Pair<Int, Long> {
        val p = context.lockerStore.data.first()
        return (p[Keys.FAILURES] ?: 0) to (p[Keys.LOCKOUT_UNTIL] ?: 0L)
    }

    suspend fun saveFailureState(failures: Int, lockoutUntil: Long) {
        context.lockerStore.edit {
            it[Keys.FAILURES] = failures
            it[Keys.LOCKOUT_UNTIL] = lockoutUntil
        }
    }

    val settings: Flow<LockerSettings> = context.lockerStore.data.map(::fromPrefs)

    suspend fun current(): LockerSettings = settings.first()

    suspend fun update(transform: (LockerSettings) -> LockerSettings) {
        context.lockerStore.edit { p ->
            val n = transform(fromPrefs(p))
            p[Keys.ENABLED] = n.enabled
            p[Keys.PACKAGES] = n.packages
            p[Keys.SYSTEM] = n.lockSystemScreens
            p[Keys.SELF] = n.lockSelf
            p[Keys.FACTOR] = n.secondFactor.name
            p[Keys.UNLOCK_MINUTES] = n.unlockMinutes
            p[Keys.TRUSTED_BT] = n.trustedBluetooth
            p[Keys.HIDE_NOTIFICATIONS] = n.hideNotifications
            p[Keys.ALL_WINDOWS] = n.checkAllWindows
            p[Keys.CLIPBOARD] = n.clearClipboardSeconds
            p[Keys.COOLDOWN] = n.cooldownMinutes
            p[Keys.DISABLE_REQUESTED] = n.disableRequestedAt
            p[Keys.ALERT_AFTER] = n.alertAfterFailures
        }
    }

    /* ---------- Geheimnisse ---------- */

    suspend fun setPin(pin: String) {
        val salt = LockerCrypto.newSalt()
        val hash = LockerCrypto.pbkdf2(pin.toCharArray(), salt)
        context.lockerStore.edit {
            it[Keys.PIN_SALT] = salt.b64()
            it[Keys.PIN_HASH] = hash.b64()
        }
    }

    suspend fun verifyPin(pin: String): Boolean {
        val p = context.lockerStore.data.first()
        val salt = p[Keys.PIN_SALT]?.unb64() ?: return false
        val hash = p[Keys.PIN_HASH]?.unb64() ?: return false
        return LockerCrypto.matches(pin.toCharArray(), salt, hash)
    }

    /** Das TOTP-Geheimnis liegt AES-GCM-verschlüsselt (Schlüssel im Android Keystore) in DataStore. */
    suspend fun setTotpSecret(secret: ByteArray) = context.lockerStore.edit { it[Keys.TOTP] = cipher.encrypt(secret.b64()) }

    suspend fun verifyTotp(code: String): Boolean {
        val enc = context.lockerStore.data.first()[Keys.TOTP] ?: return false
        val secret = cipher.decrypt(enc)?.unb64() ?: return false
        return LockerCrypto.verifyTotp(secret, code, System.currentTimeMillis())
    }

    suspend fun setNfcTag(tagId: ByteArray) {
        val salt = LockerCrypto.newSalt()
        context.lockerStore.edit {
            it[Keys.NFC_SALT] = salt.b64()
            it[Keys.NFC_HASH] = LockerCrypto.pbkdf2(tagId.hex().toCharArray(), salt).b64()
        }
    }

    suspend fun verifyNfcTag(tagId: ByteArray): Boolean {
        val p = context.lockerStore.data.first()
        val salt = p[Keys.NFC_SALT]?.unb64() ?: return false
        val hash = p[Keys.NFC_HASH]?.unb64() ?: return false
        return LockerCrypto.matches(tagId.hex().toCharArray(), salt, hash)
    }

    /** Erzeugt neue Recovery-Codes (alte verfallen) und gibt sie einmalig im Klartext zurück. */
    suspend fun newRecoveryCodes(): List<String> {
        val codes = LockerCrypto.newRecoveryCodes()
        context.lockerStore.edit { it[Keys.RECOVERY] = codes.map(LockerCrypto::recoveryHash).toSet() }
        return codes
    }

    /** Verbraucht einen Recovery-Code. */
    suspend fun useRecoveryCode(code: String): Boolean {
        val hash = LockerCrypto.recoveryHash(code)
        var ok = false
        context.lockerStore.edit { p ->
            val set = p[Keys.RECOVERY] ?: emptySet()
            if (hash in set) {
                ok = true
                p[Keys.RECOVERY] = set - hash
            }
        }
        return ok
    }

    private fun fromPrefs(p: Preferences) = LockerSettings(
        enabled = p[Keys.ENABLED] ?: false,
        packages = p[Keys.PACKAGES] ?: emptySet(),
        lockSystemScreens = p[Keys.SYSTEM] ?: false,
        lockSelf = p[Keys.SELF] ?: false,
        secondFactor = SecondFactor.entries.firstOrNull { it.name == p[Keys.FACTOR] } ?: SecondFactor.NONE,
        unlockMinutes = p[Keys.UNLOCK_MINUTES] ?: 5,
        trustedBluetooth = p[Keys.TRUSTED_BT] ?: emptySet(),
        hideNotifications = p[Keys.HIDE_NOTIFICATIONS] ?: false,
        checkAllWindows = p[Keys.ALL_WINDOWS] ?: false,
        clearClipboardSeconds = p[Keys.CLIPBOARD] ?: 0,
        cooldownMinutes = p[Keys.COOLDOWN] ?: 0,
        disableRequestedAt = p[Keys.DISABLE_REQUESTED] ?: 0,
        alertAfterFailures = p[Keys.ALERT_AFTER] ?: 3,
        hasPin = p[Keys.PIN_HASH] != null,
        hasTotp = p[Keys.TOTP] != null,
        hasNfc = p[Keys.NFC_HASH] != null,
        recoveryCodesLeft = p[Keys.RECOVERY]?.size ?: 0,
    )

    private fun ByteArray.b64() = Base64.encodeToString(this, Base64.NO_WRAP)
    private fun String.unb64(): ByteArray? = runCatching { Base64.decode(this, Base64.NO_WRAP) }.getOrNull()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
}

/**
 * Phase 4.2: Herz der App-Sperre. Bekommt App-Wechsel von der Bedienungshilfe (bzw. dem UsageStats-Dienst),
 * legt sofort ein blickdichtes Overlay über gesperrte Apps und öffnet den Sperrbildschirm.
 * Freigaben gelten pro App für die eingestellte Zeit und verfallen immer, wenn das Display ausgeht.
 */
@Singleton
class LockerController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: LockerRepository,
    private val securityEvents: SecurityEventRepository,
    appSettings: SettingsRepository,
) {
    private val scope = AppCoroutines.scope("Locker", Dispatchers.Default)
    private val main = Handler(Looper.getMainLooper())
    val settings: StateFlow<LockerSettings?> = repository.settings.stateIn(scope, SharingStarted.Eagerly, null)
    private val connectedBluetooth: StateFlow<Set<String>> =
        appSettings.connectedBluetoothDevices.map { it.keys }.stateIn(scope, SharingStarted.Eagerly, emptySet())

    private val unlockedUntil = ConcurrentHashMap<String, Long>()
    private val shield by lazy { LockShield(context) }
    @Volatile private var foreground: String? = null
    @Volatile var failures = 0
        private set
    @Volatile var lockedOutUntil = 0L
        private set
    private var clipboardJob: Runnable? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    unlockedUntil.clear()
                    main.post { shield.dismiss() }
                }
                // Nach dem Entsperren des Geräts kommt kein Fensterwechsel – die App im Vordergrund erneut prüfen.
                Intent.ACTION_USER_PRESENT, Intent.ACTION_SCREEN_ON -> foreground?.let { check(it, force = true) }
            }
        }
    }

    init {
        scope.launch {
            val (savedFailures, savedUntil) = runCatching { repository.failureState() }.getOrDefault(0 to 0L)
            if (savedFailures > failures) {
                failures = savedFailures
                // Uhr zurückgestellt? Höchstens die maximale Sperrzeit ab jetzt.
                lockedOutUntil = maxOf(lockedOutUntil, LockerPolicy.clampLockout(savedUntil, System.currentTimeMillis()))
            }
        }
        ContextCompat.registerReceiver(
            context,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    val active: Boolean get() = settings.value?.active == true

    /** Vom ForegroundAppTracker – für jedes Fenster, auch ohne Paketwechsel. */
    fun onForeground(packageName: String) {
        val previous = foreground
        foreground = packageName
        if (previous != null && previous != packageName) onLeft(previous)
        check(packageName, force = false)
    }

    /** Split-Screen & Pop-up-Fenster: alle sichtbaren App-Fenster prüfen. */
    fun onVisibleWindows(packages: Set<String>) {
        val s = settings.value ?: return
        if (!s.active || !s.checkAllWindows) return
        packages.firstOrNull { needsLock(it, s) }?.let { check(it, force = true) }
    }

    private fun check(pkg: String, force: Boolean) {
        val s = settings.value ?: return
        if (!needsLock(pkg, s)) {
            if (shield.isShowing && shield.packageName != pkg && pkg != context.packageName) main.post { shield.dismiss() }
            return
        }
        if (!force && shield.isShowing && shield.packageName == pkg) return
        main.post { lock(pkg) }
    }

    private fun needsLock(pkg: String, s: LockerSettings): Boolean =
        s.active && LockerPolicy.needsLock(pkg, s.lockedPackages, unlockedUntil[pkg], System.currentTimeMillis())

    /** Main-Thread: Overlay sofort, dann der Sperrbildschirm darüber. */
    private fun lock(pkg: String) {
        val intent = LockActivity.intent(context, pkg)
        if (Settings.canDrawOverlays(context)) shield.show(pkg) { startLockActivity(intent) }
        startLockActivity(intent)
    }

    private fun startLockActivity(intent: Intent) {
        runCatching { context.startActivity(intent) }
    }

    fun isUnlocked(key: String): Boolean = (unlockedUntil[key] ?: 0) > System.currentTimeMillis()

    /** Braucht MobileManager selbst eine Entsperrung (Option „MobileManager mitsperren“)? */
    suspend fun selfNeedsUnlock(): Boolean {
        val s = settings.value ?: repository.current()
        return s.enabled && s.lockSelf && !isUnlocked(context.packageName)
    }

    fun needsSecondFactor(): Boolean {
        val s = settings.value ?: return false
        return LockerPolicy.needsSecondFactor(s.secondFactor, s.trustedBluetooth, connectedBluetooth.value) && s.secondFactorReady
    }

    fun unlock(pkg: String) {
        val s = settings.value
        val minutes = s?.unlockMinutes ?: 5
        // 0 = bis Display aus → praktisch unbegrenzt, der Screen-Off-Receiver räumt auf.
        val until = if (minutes <= 0) Long.MAX_VALUE else System.currentTimeMillis() + minutes * 60_000L
        unlockedUntil[pkg] = until
        // Systembildschirme werden gemeinsam freigegeben (Einstellungen öffnen oft den Installer und umgekehrt).
        if (pkg in LockerPolicy.SYSTEM_SCREENS) LockerPolicy.SYSTEM_SCREENS.forEach { unlockedUntil[it] = until }
        if (failures != 0 || lockedOutUntil != 0L) {
            failures = 0
            lockedOutUntil = 0
            scope.launch { runCatching { repository.saveFailureState(0, 0L) } }
        }
        main.post { shield.dismiss() }
    }

    fun cancel() {
        main.post { shield.dismiss() }
    }

    /** Der Sperrbildschirm liegt jetzt oben – das Overlay würde ihn sonst verdecken. */
    fun onLockScreenVisible() {
        main.post { shield.dismiss() }
    }

    /** Fehlversuch beim zweiten Faktor: Sperrzeit und ggf. Eintrag im Manipulations-Protokoll. */
    fun recordFailure(pkg: String, factor: String) {
        failures++
        lockedOutUntil = System.currentTimeMillis() + LockerPolicy.lockoutMillis(failures)
        val (count, until) = failures to lockedOutUntil
        scope.launch { runCatching { repository.saveFailureState(count, until) } }
        val threshold = settings.value?.alertAfterFailures ?: 3
        if (threshold > 0 && failures == threshold) {
            scope.launch {
                securityEvents.record(
                    SecurityEventType.LOCK_FAILED,
                    "App-Sperre: $failures Fehlversuche",
                    "Falsche Eingabe ($factor) beim Entsperren von ${label(pkg)}.",
                )
            }
        }
    }

    /** Beim Verlassen einer gesperrten App optional die Zwischenablage leeren (Passwörter, TANs). */
    private fun onLeft(pkg: String) {
        val s = settings.value ?: return
        if (!s.enabled || s.clearClipboardSeconds <= 0 || pkg !in s.lockedPackages) return
        clipboardJob?.let(main::removeCallbacks)
        val job = Runnable { clearClipboard(context) }
        clipboardJob = job
        main.postDelayed(job, s.clearClipboardSeconds * 1000L)
    }

    fun label(pkg: String): String = when (pkg) {
        SETTINGS_KEY -> "App-Sperre (Einstellungen)"
        else -> runCatching {
            val pm = context.packageManager
            pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
        }.getOrDefault(pkg)
    }

    companion object {
        /** Pseudo-Paket: Zugriff auf die Einstellungen der App-Sperre selbst. */
        const val SETTINGS_KEY = "app.mobilemanager#locker"

        fun clearClipboard(context: Context) {
            runCatching { context.getSystemService(ClipboardManager::class.java)?.clearPrimaryClip() }
        }
    }
}

/**
 * Blickdichtes Overlay, das gesperrte Inhalte sofort verdeckt, bis der Sperrbildschirm geöffnet ist.
 * Falls Android den Start aus dem Hintergrund verweigert, öffnet ein Tipp auf „Entsperren“ ihn.
 */
private class LockShield(private val context: Context) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    var packageName: String? = null
        private set

    val isShowing: Boolean get() = view != null

    fun show(pkg: String, onUnlock: () -> Unit) {
        if (view != null) {
            packageName = pkg
            return
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            addView(TextView(context).apply {
                setText(app.mobilemanager.R.string.lock_shield_title)
                setTextColor(Color.WHITE)
                textSize = 22f
                gravity = Gravity.CENTER
            })
            addView(Button(context).apply {
                setText(app.mobilemanager.R.string.lock_shield_unlock)
                setOnClickListener { onUnlock() }
            })
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_SECURE,
            PixelFormat.OPAQUE,
        )
        if (runCatching { windowManager.addView(layout, params) }.isSuccess) {
            view = layout
            packageName = pkg
        }
    }

    fun dismiss() {
        view?.let { runCatching { windowManager.removeView(it) } }
        view = null
        packageName = null
    }
}
