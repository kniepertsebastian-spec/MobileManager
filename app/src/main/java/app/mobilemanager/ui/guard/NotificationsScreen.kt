package app.mobilemanager.ui.guard

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mobilemanager.data.apps.InstalledAppsRepository
import app.mobilemanager.data.guard.GuardSettings
import app.mobilemanager.data.guard.GuardSettingsRepository
import app.mobilemanager.data.local.NotificationAppCount
import app.mobilemanager.data.notifications.NotificationRepository
import app.mobilemanager.domain.guard.Schedule
import app.mobilemanager.domain.model.ArchivedNotification
import app.mobilemanager.domain.model.LaunchableApp
import app.mobilemanager.ui.components.AppIcon
import app.mobilemanager.ui.components.AppPickerDialog
import app.mobilemanager.ui.components.AppSelectionRow
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class NotificationsViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val guardSettings: GuardSettingsRepository,
    private val installedApps: InstalledAppsRepository,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    // Suche und Filter überstehen Process Death (nur Suchbegriff/Paketname – keine Inhalte im Bundle).
    val query: StateFlow<String> = savedState.getStateFlow(KEY_QUERY, "")
    val packageFilter: StateFlow<String?> = savedState.getStateFlow(KEY_FILTER, null)

    val results: StateFlow<List<ArchivedNotification>> = combine(query, packageFilter) { q, p -> q to p }
        .flatMapLatest { (q, p) -> repository.search(q, p) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val apps: StateFlow<List<NotificationAppCount>> =
        repository.apps.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pendingDigest: StateFlow<List<ArchivedNotification>> =
        repository.pendingDigest.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val settings: StateFlow<GuardSettings> =
        guardSettings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuardSettings())

    private val _launchableApps = MutableStateFlow<List<LaunchableApp>>(emptyList())
    val launchableApps: StateFlow<List<LaunchableApp>> = _launchableApps.asStateFlow()
    private val _listenerEnabled = MutableStateFlow(repository.isListenerEnabled())
    val listenerEnabled: StateFlow<Boolean> = _listenerEnabled.asStateFlow()

    init {
        viewModelScope.launch { _launchableApps.value = installedApps.getLaunchableApps() }
    }

    fun refresh() {
        _listenerEnabled.value = repository.isListenerEnabled()
    }

    fun setQuery(value: String) {
        savedState[KEY_QUERY] = value
    }

    fun setPackageFilter(pkg: String?) {
        savedState[KEY_FILTER] = pkg
    }

    fun setArchiveExcludedApps(packages: Set<String>) = update { it.copy(archiveExcludedApps = packages) }

    fun clearArchive() {
        viewModelScope.launch { repository.clear() }
    }

    fun update(transform: (GuardSettings) -> GuardSettings) {
        viewModelScope.launch { guardSettings.update(transform) }
    }

    fun toggleDigestApp(pkg: String, enabled: Boolean) =
        update { it.copy(digestApps = if (enabled) it.digestApps + pkg else it.digestApps - pkg) }

    fun addKeyword(keyword: String) {
        val k = keyword.trim()
        if (k.isNotEmpty()) update { it.copy(digestKeywords = it.digestKeywords + k) }
    }

    fun removeKeyword(keyword: String) = update { it.copy(digestKeywords = it.digestKeywords - keyword) }

    fun toggleDigestTime(minutes: Int) = update {
        it.copy(digestTimes = if (minutes in it.digestTimes) it.digestTimes - minutes else it.digestTimes + minutes)
    }

    private companion object {
        const val KEY_QUERY = "archive_query"
        const val KEY_FILTER = "archive_package_filter"
    }
}

@Composable
fun NotificationsScreen(
    onBack: () -> Unit,
    onOpenPermissions: () -> Unit,
    viewModel: NotificationsViewModel = hiltViewModel(),
) {
    val listenerEnabled by viewModel.listenerEnabled.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    GuardSubScreen(
        title = "Benachrichtigungen",
        onBack = onBack,
        actions = {
            if (tab == 0) {
                IconButton(onClick = { confirmClear = true }) { Icon(Icons.Default.DeleteSweep, contentDescription = "Archiv leeren") }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (!listenerEnabled) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Benachrichtigungszugriff fehlt – Archiv und Digest sind inaktiv.")
                        TextButton(onClick = onOpenPermissions, modifier = Modifier.align(Alignment.End)) { Text("Berechtigen") }
                    }
                }
            }
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Archiv (7 Tage)") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Digest") })
            }
            when (tab) {
                0 -> ArchiveTab(viewModel)
                else -> DigestTab(viewModel)
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Archiv leeren?") },
            text = { Text("Alle archivierten Benachrichtigungen werden endgültig gelöscht.") },
            confirmButton = {
                Button(onClick = {
                    viewModel.clearArchive()
                    confirmClear = false
                }) { Text("Leeren") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun ArchiveTab(viewModel: NotificationsViewModel) {
    val query by viewModel.query.collectAsStateWithLifecycle()
    val filter by viewModel.packageFilter.collectAsStateWithLifecycle()
    val results by viewModel.results.collectAsStateWithLifecycle()
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val launchable by viewModel.launchableApps.collectAsStateWithLifecycle()
    var confirmEnable by remember { mutableStateOf(false) }
    var pickExcluded by remember { mutableStateOf(false) }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            SwitchRow(
                title = "Archiv aktiv",
                subtitle = "Speichert Titel und Text aller Benachrichtigungen 7 Tage lang – nur lokal auf diesem Gerät.",
                checked = settings.notificationArchiveEnabled,
                onCheckedChange = { v ->
                    // Beim Einschalten erst den Datenschutzhinweis bestätigen lassen.
                    if (v) confirmEnable = true else viewModel.update { it.copy(notificationArchiveEnabled = false) }
                },
            )
            if (settings.notificationArchiveEnabled) {
                SwitchRow(
                    title = "Sensible Apps ausschließen",
                    subtitle = "Banking, Bezahl-Apps, Authenticator/2FA und Passwortmanager werden nicht archiviert.",
                    checked = settings.archiveExcludeSensitive,
                    onCheckedChange = { v -> viewModel.update { it.copy(archiveExcludeSensitive = v) } },
                )
                AppSelectionRow(
                    title = "Weitere Apps ausschließen",
                    count = settings.archiveExcludedApps.size,
                    onClick = { pickExcluded = true },
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::setQuery,
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { viewModel.setQuery("") }) { Icon(Icons.Default.Close, contentDescription = "Suche leeren") }
                    }
                },
                placeholder = { Text("Volltextsuche") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (apps.isNotEmpty()) {
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { FilterChip(selected = filter == null, onClick = { viewModel.setPackageFilter(null) }, label = { Text("Alle") }) }
                    items(apps, key = { it.packageName }) { app ->
                        FilterChip(
                            selected = filter == app.packageName,
                            onClick = { viewModel.setPackageFilter(app.packageName) },
                            label = { Text("${app.appLabel} (${app.count})") },
                        )
                    }
                }
            }
        }
        if (results.isEmpty()) {
            item { Text("Keine Benachrichtigungen gefunden.", style = MaterialTheme.typography.bodyMedium) }
        }
        items(results, key = { it.id }) { NotificationRow(it) }
    }

    if (confirmEnable) {
        AlertDialog(
            onDismissRequest = { confirmEnable = false },
            title = { Text("Benachrichtigungen archivieren?") },
            text = {
                Text(
                    "MobileManager speichert dann Titel und Text jeder Benachrichtigung 7 Tage lang – auch private Nachrichten. " +
                        "Die Inhalte werden mit einem Schlüssel aus dem Android Keystore verschlüsselt, bleiben nur auf diesem Gerät " +
                        "und werden nie übertragen oder gesichert. Banking-, 2FA- und Passwort-Apps sind standardmäßig ausgenommen.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.update { it.copy(notificationArchiveEnabled = true) }
                    confirmEnable = false
                }) { Text("Archiv einschalten") }
            },
            dismissButton = { TextButton(onClick = { confirmEnable = false }) { Text("Abbrechen") } },
        )
    }
    if (pickExcluded) {
        AppPickerDialog(
            title = "Vom Archiv ausschließen",
            apps = launchable,
            selected = settings.archiveExcludedApps,
            onDismiss = { pickExcluded = false },
            onConfirm = {
                viewModel.setArchiveExcludedApps(it)
                pickExcluded = false
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DigestTab(viewModel: NotificationsViewModel) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val pending by viewModel.pendingDigest.collectAsStateWithLifecycle()
    val apps by viewModel.launchableApps.collectAsStateWithLifecycle()
    var keyword by rememberSaveable { mutableStateOf("") }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            SwitchRow(
                title = "Digest-Modus",
                subtitle = "Meldungen der gewählten Apps oder mit Stichwörtern werden sofort ausgeblendet und gesammelt " +
                    "zu festen Uhrzeiten angezeigt. Laufende Meldungen (Musik, Timer, Navi) bleiben unberührt.",
                checked = settings.digestEnabled,
                onCheckedChange = { v -> viewModel.update { it.copy(digestEnabled = v) } },
            )
            Text("Uhrzeiten", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(8, 12, 17, 19, 21).map { it * 60 }.forEach { minutes ->
                    FilterChip(
                        selected = minutes in settings.digestTimes,
                        onClick = { viewModel.toggleDigestTime(minutes) },
                        label = { Text(Schedule.format(minutes)) },
                    )
                }
            }
        }
        item {
            Text("Stichwörter", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = { keyword = it },
                    placeholder = { Text("z. B. Rabatt, Sale") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = {
                    viewModel.addKeyword(keyword)
                    keyword = ""
                }) { Icon(Icons.Default.Add, contentDescription = "Stichwort hinzufügen") }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                settings.digestKeywords.sorted().forEach { k ->
                    InputChip(
                        selected = false,
                        onClick = { viewModel.removeKeyword(k) },
                        label = { Text(k) },
                        trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Entfernen") },
                    )
                }
            }
        }
        if (pending.isNotEmpty()) {
            item { Text("Im nächsten Digest (${pending.size})", style = MaterialTheme.typography.titleSmall) }
            items(pending.take(20), key = { "pending_${it.id}" }) { NotificationRow(it) }
        }
        item {
            Text("Digest-Apps", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Text(
                "Hinweis: Android spielt Ton/Vibration ab, bevor MobileManager die Meldung ausblenden kann. " +
                    "Für völlige Ruhe zusätzlich „Ton aus“ wählen.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        items(apps, key = { "app_${it.packageName}" }) { app ->
            val checked = app.packageName in settings.digestApps
            Row(
                Modifier.fillMaxWidth().clickable { viewModel.toggleDigestApp(app.packageName, !checked) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = checked, onCheckedChange = { viewModel.toggleDigestApp(app.packageName, it) })
                AppIcon(app.packageName, size = 28.dp)
                Text(app.label, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                if (checked) {
                    TextButton(onClick = {
                        context.startActivitySafely(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName),
                        )
                    }) { Text("Ton aus") }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(n: ArchivedNotification) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Row(Modifier.padding(12.dp)) {
            AppIcon(n.packageName, size = 32.dp)
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(
                    "${n.appLabel} · ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(n.postedAt))}" +
                        if (n.isDigest) " · Digest" else "",
                    style = MaterialTheme.typography.labelMedium,
                )
                if (n.title.isNotBlank()) Text(n.title, style = MaterialTheme.typography.titleSmall)
                if (n.text.isNotBlank()) Text(n.text, style = MaterialTheme.typography.bodySmall, maxLines = 6)
            }
        }
    }
}
