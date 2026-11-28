package app.mobilemanager.domain.extras

/**
 * Phase 4: Erlaubtliste für Shell-Befehle über Shizuku. Der UserService läuft mit Shell-Rechten,
 * daher darf er nie beliebige Befehle ausführen – nur genau diese Formen mit geprüften Argumenten.
 */
object ShellCommandPolicy {
    private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    private val HOSTNAME = Regex("^(?=.{1,253}$)([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}$")
    private val TRIM_SIZE = Regex("^[0-9]{1,6}[KMG]?$")

    val ANIMATION_KEYS = setOf("window_animation_scale", "transition_animation_scale", "animator_duration_scale")
    val ANIMATION_VALUES = setOf("0", "0.5", "1", "1.5", "2")
    val APP_OPS = setOf("RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND", "WAKE_LOCK", "READ_CLIPBOARD", "VIBRATE", "SYSTEM_ALERT_WINDOW")
    val APP_OP_MODES = setOf("allow", "ignore", "deny", "default")

    /** Laufzeit-Berechtigungen, die „Rechte auf Zeit“ vergeben und wieder entziehen darf. */
    val TEMP_PERMISSIONS = setOf(
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
    )

    fun isPackage(value: String) = PACKAGE.matches(value)

    fun isHostname(value: String) = HOSTNAME.matches(value)

    /**
     * Binder-Aufrufer-Prüfung für den Shizuku-UserService: Nur eine uid, zu der MobileManager selbst
     * gehört, darf Befehle auslösen. Shared-UIDs anderer Pakete oder fremde Apps werden abgewiesen.
     */
    fun isTrustedCaller(callingUid: Int, ownPackage: String, packagesForUid: (Int) -> Array<String>?): Boolean {
        if (callingUid < 0 || callingUid % PER_USER_RANGE < FIRST_APPLICATION_UID) return false
        val packages = runCatching { packagesForUid(callingUid) }.getOrNull() ?: return false
        return packages.size == 1 && packages[0] == ownPackage
    }

    fun isAllowed(argv: List<String>): Boolean {
        if (argv.isEmpty() || argv.any { it.isEmpty() || it.length > 256 || it.any(Char::isISOControl) }) return false
        return when (argv[0]) {
            "cmd" -> cmd(argv.drop(1))
            "pm" -> pm(argv.drop(1))
            "settings" -> settings(argv.drop(1))
            else -> false
        }
    }

    private fun cmd(a: List<String>): Boolean = when (a.getOrNull(0)) {
        "connectivity" -> when (a.getOrNull(1)) {
            "set-chain3-enabled" -> a.size == 3 && a[2] in BOOLEANS
            "get-chain3-enabled" -> a.size == 2
            "set-package-networking-enabled" -> a.size == 4 && a[2] in BOOLEANS && isPackage(a[3])
            "get-package-networking-enabled" -> a.size == 3 && isPackage(a[2])
            else -> false
        }
        "appops" -> when (a.getOrNull(1)) {
            "set" -> a.size == 5 && isPackage(a[2]) && a[3] in APP_OPS && a[4] in APP_OP_MODES
            "get" -> a.size == 4 && isPackage(a[2]) && a[3] in APP_OPS
            else -> false
        }
        "uimode" -> a.size == 3 && a[1] == "night" && a[2] in setOf("yes", "no", "auto")
        else -> false
    }

    private fun pm(a: List<String>): Boolean = when (a.getOrNull(0)) {
        "disable-user" -> a.size == 4 && a[1] == "--user" && a[2] == "0" && isPackage(a[3])
        "enable" -> a.size == 4 && a[1] == "--user" && a[2] == "0" && isPackage(a[3])
        "list" -> a == listOf("list", "packages", "-d") || a == listOf("list", "packages", "-s")
        "grant", "revoke" -> a.size == 3 && isPackage(a[1]) && a[2] in TEMP_PERMISSIONS
        "trim-caches" -> a.size == 2 && TRIM_SIZE.matches(a[1])
        else -> false
    }

    private fun settings(a: List<String>): Boolean {
        if (a.size == 3 && a[0] == "get" && a[1] == "global") return a[2] in ANIMATION_KEYS || a[2] in PRIVATE_DNS_KEYS
        if (a.size != 4 || a[0] != "put" || a[1] != "global") return false
        val (key, value) = a[2] to a[3]
        return when (key) {
            in ANIMATION_KEYS -> value in ANIMATION_VALUES
            "private_dns_mode" -> value in setOf("off", "opportunistic", "hostname")
            "private_dns_specifier" -> isHostname(value)
            else -> false
        }
    }

    private const val FIRST_APPLICATION_UID = 10_000
    private const val PER_USER_RANGE = 100_000
    private val BOOLEANS = setOf("true", "false")
    private val PRIVATE_DNS_KEYS = setOf("private_dns_mode", "private_dns_specifier")
}
