package app.mobilemanager.data.tracker

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.mobilemanager.data.local.TrackerCount
import app.mobilemanager.data.local.TrackerStatsDao
import app.mobilemanager.domain.extras.DomainMatcher
import app.mobilemanager.domain.extras.HostsParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

private val Context.trackerStore by preferencesDataStore(name = "tracker_settings")

data class TrackerSettings(
    val enabled: Boolean = false,
    val customBlocked: Set<String> = emptySet(),
    val allowed: Set<String> = emptySet(),
    val importedName: String? = null,
    val importedCount: Int = 0,
    /** Ändert sich bei jedem Import, damit die Blockliste neu geladen wird. */
    val importVersion: Long = 0,
    /** Phase 4.1: abonnierte Blocklisten (HTTPS-URLs), wöchentlich aktualisiert. */
    val subscriptions: Set<String> = emptySet(),
    val subscriptionCount: Int = 0,
    val subscriptionUpdated: Long = 0,
    val subscriptionError: String? = null,
    /** Phase 4.1: DNS-over-HTTPS-Endpunkt; leer = System-Resolver des Netzes. */
    val dohUrl: String = "",
)

@Singleton
class TrackerSettingsRepository @Inject constructor(@ApplicationContext private val context: Context) {

    private object Keys {
        val ENABLED = booleanPreferencesKey("enabled")
        val CUSTOM = stringSetPreferencesKey("custom_blocked")
        val ALLOWED = stringSetPreferencesKey("allowed")
        val IMPORTED_NAME = stringPreferencesKey("imported_name")
        val IMPORTED_COUNT = intPreferencesKey("imported_count")
        val IMPORT_VERSION = longPreferencesKey("import_version")
        val SUBSCRIPTIONS = stringSetPreferencesKey("subscriptions")
        val SUB_COUNT = intPreferencesKey("subscription_count")
        val SUB_UPDATED = longPreferencesKey("subscription_updated")
        val SUB_ERROR = stringPreferencesKey("subscription_error")
        val DOH_URL = stringPreferencesKey("doh_url")
    }

    val settings: Flow<TrackerSettings> = context.trackerStore.data.map(::fromPrefs)

    suspend fun current(): TrackerSettings = settings.first()

    suspend fun setEnabled(enabled: Boolean) = context.trackerStore.edit { it[Keys.ENABLED] = enabled }

    suspend fun addCustom(domain: String) = context.trackerStore.edit { p ->
        p[Keys.CUSTOM] = (p[Keys.CUSTOM] ?: emptySet()) + domain
        p[Keys.ALLOWED] = (p[Keys.ALLOWED] ?: emptySet()) - domain
    }

    suspend fun removeCustom(domain: String) = context.trackerStore.edit { p -> p[Keys.CUSTOM] = (p[Keys.CUSTOM] ?: emptySet()) - domain }

    suspend fun addAllowed(domain: String) = context.trackerStore.edit { p ->
        p[Keys.ALLOWED] = (p[Keys.ALLOWED] ?: emptySet()) + domain
        p[Keys.CUSTOM] = (p[Keys.CUSTOM] ?: emptySet()) - domain
    }

    suspend fun removeAllowed(domain: String) = context.trackerStore.edit { p -> p[Keys.ALLOWED] = (p[Keys.ALLOWED] ?: emptySet()) - domain }

    suspend fun setImport(name: String?, count: Int) = context.trackerStore.edit { p ->
        if (name == null) p.remove(Keys.IMPORTED_NAME) else p[Keys.IMPORTED_NAME] = name
        p[Keys.IMPORTED_COUNT] = count
        p[Keys.IMPORT_VERSION] = System.currentTimeMillis()
    }

    suspend fun setSubscriptions(urls: Set<String>) = context.trackerStore.edit { it[Keys.SUBSCRIPTIONS] = urls }

    suspend fun setSubscriptionResult(count: Int, error: String?) = context.trackerStore.edit { p ->
        p[Keys.SUB_COUNT] = count
        p[Keys.SUB_UPDATED] = System.currentTimeMillis()
        if (error == null) p.remove(Keys.SUB_ERROR) else p[Keys.SUB_ERROR] = error
    }

    suspend fun setDohUrl(url: String) = context.trackerStore.edit { it[Keys.DOH_URL] = url.trim() }

    private fun fromPrefs(p: Preferences) = TrackerSettings(
        enabled = p[Keys.ENABLED] ?: false,
        customBlocked = p[Keys.CUSTOM] ?: emptySet(),
        allowed = p[Keys.ALLOWED] ?: emptySet(),
        importedName = p[Keys.IMPORTED_NAME],
        importedCount = p[Keys.IMPORTED_COUNT] ?: 0,
        importVersion = p[Keys.IMPORT_VERSION] ?: 0,
        subscriptions = p[Keys.SUBSCRIPTIONS] ?: emptySet(),
        subscriptionCount = p[Keys.SUB_COUNT] ?: 0,
        subscriptionUpdated = p[Keys.SUB_UPDATED] ?: 0,
        subscriptionError = p[Keys.SUB_ERROR],
        dohUrl = p[Keys.DOH_URL].orEmpty(),
    )
}

/** Meilenstein 3.3.2: lokale Blockliste aus mitgelieferter Liste, optionalem Import und eigenen Einträgen. */
@Singleton
class TrackerBlocklistRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: TrackerSettingsRepository,
) {
    private val importFile get() = File(context.filesDir, "tracker_import.txt")

    private val builtIn: Set<String> by lazy {
        context.assets.open(ASSET).bufferedReader().useLines { HostsParser.parse(it) }
    }

    val builtInCount: Int get() = builtIn.size

    private val subscriptionDir get() = File(context.filesDir, "blocklists")

    val matcher: Flow<DomainMatcher> = settings.settings
        .map { listOf(it.customBlocked, it.allowed, it.importVersion, it.subscriptions, it.subscriptionUpdated) }
        .distinctUntilChanged()
        .map {
            val s = settings.current()
            val imported = if (importFile.exists()) importFile.readLines().toSet() else emptySet()
            val subscribed = s.subscriptions.flatMap { url ->
                subscriptionFile(url).takeIf(File::exists)?.readLines().orEmpty()
            }
            DomainMatcher(builtIn + imported + subscribed + s.customBlocked, s.allowed)
        }
        .flowOn(Dispatchers.IO)

    private fun subscriptionFile(url: String) = File(subscriptionDir, "${url.hashCode().toUInt().toString(16)}.txt")

    /**
     * Phase 4.1: lädt alle abonnierten Listen (nur HTTPS) und speichert sie lokal. Einzelne Fehler
     * behalten die letzte gute Version. @return Gesamtzahl Domains.
     */
    suspend fun updateSubscriptions(http: okhttp3.OkHttpClient): Result<Int> = withContext(Dispatchers.IO) {
        val s = settings.current()
        subscriptionDir.mkdirs()
        val errors = mutableListOf<String>()
        var total = 0
        for (url in s.subscriptions) {
            val result = runCatching {
                require(url.startsWith("https://")) { "nur HTTPS erlaubt" }
                http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
                    check(response.isSuccessful) { "HTTP ${response.code}" }
                    val domains = response.body.charStream().buffered().useLines { HostsParser.parse(it) }
                    require(domains.isNotEmpty()) { "keine Domains gefunden" }
                    require(domains.size <= MAX_SUBSCRIPTION) { "Liste zu groß" }
                    val target = subscriptionFile(url)
                    val tmp = File(subscriptionDir, target.name + ".tmp")
                    tmp.writeText(domains.joinToString("\n"))
                    tmp.renameTo(target)
                    domains.size
                }
            }
            result.onSuccess { total += it }.onFailure { e ->
                errors += "${url.substringAfter("://").substringBefore('/')}: ${e.message}"
                subscriptionFile(url).takeIf(File::exists)?.let { total += it.readLines().size }
            }
        }
        // Dateien abbestellter Listen entfernen.
        val keep = s.subscriptions.map { subscriptionFile(it).name }.toSet()
        subscriptionDir.listFiles()?.filter { it.name !in keep }?.forEach { it.delete() }
        settings.setSubscriptionResult(total, errors.takeIf { it.isNotEmpty() }?.joinToString("; "))
        if (errors.isEmpty() || total > 0) Result.success(total) else Result.failure(IllegalStateException(errors.joinToString("; ")))
    }

    /** Hosts-Datei einlesen (lokal, z. B. aus dem Download-Ordner). @return Anzahl Domains. */
    suspend fun import(uri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val domains = context.contentResolver.openInputStream(uri)?.bufferedReader()?.useLines { HostsParser.parse(it) }
                ?: error("Datei kann nicht gelesen werden")
            require(domains.isNotEmpty()) { "Keine Domains im Hosts-Format gefunden" }
            require(domains.size <= MAX_IMPORT) { "Liste zu groß (max. ${MAX_IMPORT / 1000}k Einträge)" }
            importFile.writeText(domains.sorted().joinToString("\n"))
            settings.setImport(displayName(uri), domains.size)
            domains.size
        }
    }

    suspend fun clearImport() = withContext(Dispatchers.IO) {
        importFile.delete()
        settings.setImport(null, 0)
    }

    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: "Importierte Liste"

    companion object {
        private const val ASSET = "tracker_blocklist.txt"
        const val MAX_IMPORT = 300_000
        const val MAX_SUBSCRIPTION = 500_000

        /** Bekannte, gepflegte Listen als Vorschläge (Domain-Format). */
        val PRESETS = listOf(
            "HaGeZi Light" to "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/domains/light.txt",
            "HaGeZi Pro" to "https://cdn.jsdelivr.net/gh/hagezi/dns-blocklists@latest/domains/pro.txt",
            "OISD Small" to "https://small.oisd.nl/domainswild2",
        )
    }
}

/** Meilenstein 3.3.3: aggregierte Statistik (pro App/Domain und Tag), 30 Tage Aufbewahrung. */
@Singleton
class TrackerStatsRepository @Inject constructor(private val dao: TrackerStatsDao) {

    fun topAppsToday(limit: Int = 10): Flow<List<TrackerCount>> = dao.topApps(today(), limit)
    fun topAppsWeek(limit: Int = 10): Flow<List<TrackerCount>> = dao.topApps(daysAgo(6), limit)
    fun topDomainsWeek(limit: Int = 10): Flow<List<TrackerCount>> = dao.topDomains(daysAgo(6), limit)
    val blockedToday: Flow<Int> get() = dao.totalSince(today())
    val blockedWeek: Flow<Int> get() = dao.totalSince(daysAgo(6))

    suspend fun add(apps: Map<String, Int>, domains: Map<String, Int>) {
        dao.addBatch(today(), apps, domains, daysAgo(RETENTION_DAYS))
    }

    suspend fun clear() {
        dao.clearApps()
        dao.clearDomains()
    }

    companion object {
        const val UNKNOWN_APP = "unknown"
        private const val RETENTION_DAYS = 30L
        private val FORMAT = DateTimeFormatter.BASIC_ISO_DATE

        fun today(): Int = LocalDate.now().format(FORMAT).toInt()
        fun daysAgo(days: Long): Int = LocalDate.now().minusDays(days).format(FORMAT).toInt()
    }
}
