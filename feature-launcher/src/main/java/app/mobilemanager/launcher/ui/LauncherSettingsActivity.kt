package app.mobilemanager.launcher.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mobilemanager.launcher.LauncherComponents
import app.mobilemanager.launcher.domain.AppCategory
import app.mobilemanager.launcher.domain.ArchiveMode
import app.mobilemanager.launcher.domain.LauncherProfile
import app.mobilemanager.launcher.domain.ProfileKind
import app.mobilemanager.launcher.domain.ProfileRule
import app.mobilemanager.launcher.domain.RuleType
import app.mobilemanager.launcher.domain.TidyOptions
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/** Einstellungen des Launchers: Raster, „Ordnen“, Profile und Wechselregeln (Meilenstein 3.2). */
@AndroidEntryPoint
class LauncherSettingsActivity : ComponentActivity() {

    private val viewModel: LauncherSettingsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val start = intent.getStringExtra(EXTRA_SCREEN) ?: SCREEN_OVERVIEW
        setContent {
            LauncherTheme {
                var screen by rememberSaveable { mutableStateOf(start) }
                val back: () -> Unit = { if (screen == SCREEN_OVERVIEW || screen == start) finish() else screen = SCREEN_OVERVIEW }
                BackHandler(onBack = back)
                when (screen) {
                    SCREEN_TIDY -> TidyScreen(viewModel, onBack = back)
                    SCREEN_PROFILES -> ProfilesScreen(viewModel, onBack = back)
                    else -> OverviewScreen(
                        viewModel = viewModel,
                        onBack = back,
                        onOpen = { screen = it },
                        onStartIntent = ::startSafely,
                    )
                }
            }
        }
    }

    private fun startSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Einstellung nicht gefunden", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
        const val SCREEN_OVERVIEW = "overview"
        const val SCREEN_TIDY = "tidy"
        const val SCREEN_PROFILES = "profiles"

        fun intent(context: Context, screen: String = SCREEN_OVERVIEW): Intent =
            Intent(context, LauncherSettingsActivity::class.java).putExtra(EXTRA_SCREEN, screen)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScaffold(
    title: String,
    onBack: () -> Unit,
    snackbar: SnackbarHostState? = null,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
                },
            )
        },
        snackbarHost = { snackbar?.let { SnackbarHost(it) } },
        content = content,
    )
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
private fun SwitchLine(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun <T> Chips(options: List<Pair<T, String>>, selected: (T) -> Boolean, onClick: (T) -> Unit, enabled: Boolean = true) {
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(selected = selected(value), onClick = { onClick(value) }, label = { Text(label) }, enabled = enabled)
        }
    }
}

// ------------------------------------------------------------------------------------- Übersicht

@Composable
private fun OverviewScreen(
    viewModel: LauncherSettingsViewModel,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onStartIntent: (Intent) -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshStatus() }
    val context = androidx.compose.ui.platform.LocalContext.current

    SettingsScaffold("Launcher", onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard {
                Text(
                    when {
                        !status.enabled -> "Launcher ist ausgeschaltet"
                        status.isDefault -> "MobileManager ist dein Startbildschirm"
                        else -> "Launcher ist bereit, aber nicht als Standard festgelegt"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "Der vorinstallierte Pixel Launcher lässt keine Änderungen von außen zu. Für Ordnen und Profile " +
                        "muss MobileManager selbst der Startbildschirm sein. Zurück geht es jederzeit über denselben Dialog.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                if (status.enabled && !status.isDefault) {
                    Button(onClick = { onStartIntent(LauncherComponents.requestDefaultHomeIntent(context)) }) {
                        Text("Als Standard-Startbildschirm festlegen")
                    }
                } else if (status.isDefault) {
                    OutlinedButton(onClick = { onStartIntent(Intent(android.provider.Settings.ACTION_HOME_SETTINGS)) }) {
                        Text("Anderen Startbildschirm wählen")
                    }
                }
            }

            SectionCard {
                Text("Raster", style = MaterialTheme.typography.titleSmall)
                Chips(
                    options = listOf(4 to 5, 4 to 6, 5 to 5, 5 to 6).map { it to "${it.first} × ${it.second}" },
                    selected = { it.first == settings.columns && it.second == settings.rows },
                    onClick = { (c, r) -> viewModel.updateSettings { s -> s.copy(columns = c, rows = r) } },
                )
                SwitchLine("App-Namen anzeigen", null, settings.showLabels) { v -> viewModel.updateSettings { it.copy(showLabels = v) } }
                SwitchLine(
                    "Automatischer Profilwechsel",
                    "Wechselt je nach Bluetooth-Gerät, WLAN oder Uhrzeit das Profil und danach wieder zurück.",
                    settings.autoSwitch,
                ) { v -> viewModel.updateSettings { it.copy(autoSwitch = v) } }
            }

            NavCard(Icons.AutoMirrored.Filled.Sort, "Homescreen ordnen", "Apps per Knopfdruck in Ordner sortieren, Ungenutztes archivieren") {
                onOpen(LauncherSettingsActivity.SCREEN_TIDY)
            }
            NavCard(Icons.Default.SpaceDashboard, "Profile & Wechselregeln", "Standard, Arbeit, Auto, Fokus – eigene Layouts je Situation") {
                onOpen(LauncherSettingsActivity.SCREEN_PROFILES)
            }
        }
    }
}

@Composable
private fun NavCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        ListItem(
            supportingContent = { Text(subtitle) },
            leadingContent = { Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        ) { Text(title) }
    }
}

// ------------------------------------------------------------------------------------- Ordnen

@Composable
private fun TidyScreen(viewModel: LauncherSettingsViewModel, onBack: () -> Unit) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val profileId by viewModel.tidyProfileId.collectAsStateWithLifecycle()
    val options by viewModel.options.collectAsStateWithLifecycle()
    val plan by viewModel.plan.collectAsStateWithLifecycle()
    val snapshot by viewModel.latestSnapshot.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    SettingsScaffold("Homescreen ordnen", onBack, snackbar) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard {
                Text("Profil", style = MaterialTheme.typography.titleSmall)
                Chips(profiles.map { it.id to it.name }, selected = { it == profileId }, onClick = viewModel::selectTidyProfile)
            }
            SectionCard {
                SwitchLine(
                    "In Ordner sammeln",
                    "Ein Ordner pro Kategorie (Spiele, Soziale Netzwerke, Finanzen …). Einzelne Apps bleiben lose liegen.",
                    options.collectIntoFolders,
                ) { viewModel.setOptions(options.copy(collectIntoFolders = it)) }
                SwitchLine(
                    "Aufräumen",
                    "Apps, die länger als ${options.unusedDays} Tage nicht genutzt wurden, kommen weg vom Startbildschirm. " +
                        "Im App-Drawer bleiben sie erhalten.",
                    options.archiveUnused,
                ) { viewModel.setOptions(options.copy(archiveUnused = it)) }
                if (options.archiveUnused) {
                    Chips(
                        options = listOf(ArchiveMode.FOLDER to "In Ordner „Archiv“", ArchiveMode.REMOVE to "Nur entfernen"),
                        selected = { it == options.archiveMode },
                        onClick = { viewModel.setOptions(options.copy(archiveMode = it)) },
                    )
                    // Die Nutzungsdaten reichen 60 Tage zurück – längere Zeiträume wären nicht belastbar.
                    Chips(
                        options = listOf(30, 60).map { it to "$it Tage" },
                        selected = { it == options.unusedDays },
                        onClick = { viewModel.setOptions(options.copy(unusedDays = it)) },
                    )
                }
            }
            SectionCard {
                Text("Vorschau", style = MaterialTheme.typography.titleSmall)
                val p = plan
                when {
                    p == null -> Text("Wird berechnet …")
                    !p.changesAnything -> Text("Der Startbildschirm ist bereits aufgeräumt – es würde sich nichts ändern.")
                    else -> {
                        Text("• ${p.foldersCreated} neue Ordner")
                        Text("• ${p.appsMoved} Apps werden in Ordner verschoben")
                        if (options.archiveUnused) {
                            if (options.archiveMode == ArchiveMode.FOLDER) Text("• ${p.appsArchived} Apps ins Archiv")
                            else Text("• ${p.appsRemoved} Apps vom Startbildschirm entfernt")
                        }
                        Text("• Ergebnis: ${p.pages} ${if (p.pages == 1) "Seite" else "Seiten"}, das Dock bleibt unverändert")
                    }
                }
                if (p?.usageDataMissing == true) {
                    Text(
                        "Ohne Nutzungszugriff kann MobileManager nicht erkennen, welche Apps ungenutzt sind – Aufräumen wird übersprungen.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = p?.changesAnything == true,
                        onClick = { viewModel.applyTidy { scope.launch { snackbar.showSnackbar("Startbildschirm geordnet") } } },
                    ) { Text("Anwenden") }
                    OutlinedButton(
                        enabled = snapshot != null,
                        onClick = {
                            viewModel.undo { ok ->
                                scope.launch { snackbar.showSnackbar(if (ok) "Vorheriges Layout wiederhergestellt" else "Nichts zum Rückgängigmachen") }
                            }
                        },
                    ) { Text("Rückgängig") }
                }
                snapshot?.let {
                    Text(
                        "Sicherung vom ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(it.createdAt))}",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------- Profile

private val DAY_LABELS = listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So")

private fun formatMinutes(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

private fun describeRule(rule: ProfileRule): String = when (rule.type) {
    RuleType.BLUETOOTH -> "Bluetooth: ${rule.label.ifBlank { rule.value }}"
    RuleType.WIFI -> "WLAN: ${rule.value}"
    RuleType.TIME -> {
        val days = DAY_LABELS.filterIndexed { i, _ -> rule.daysMask and (1 shl i) != 0 }
        val dayText = when (rule.daysMask) {
            ProfileRule.WEEKDAYS -> "Mo–Fr"
            ProfileRule.ALL_DAYS -> "Täglich"
            else -> days.joinToString(", ")
        }
        "$dayText ${formatMinutes(rule.startMinutes)}–${formatMinutes(rule.endMinutes)}"
    }
}

@Composable
private fun ProfilesScreen(viewModel: LauncherSettingsViewModel, onBack: () -> Unit) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val activeId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    var ruleFor by remember { mutableStateOf<Long?>(null) }
    var newProfile by remember { mutableStateOf(false) }

    SettingsScaffold("Profile & Wechselregeln", onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Jedes Profil hat ein eigenes Layout. Regeln schalten automatisch um (Bluetooth vor WLAN vor Uhrzeit); " +
                    "endet der Auslöser, kehrt der Startbildschirm zum gewählten Basisprofil zurück.",
                style = MaterialTheme.typography.bodySmall,
            )
            profiles.forEach { profile ->
                ProfileCard(
                    profile = profile,
                    isBase = profile.id == settings.baseProfileId,
                    isActive = profile.id == activeId,
                    rules = rules.filter { it.profileId == profile.id },
                    onUpdate = viewModel::updateProfile,
                    onDelete = { viewModel.deleteProfile(profile.id) },
                    onMakeBase = { viewModel.updateSettings { it.copy(baseProfileId = profile.id) } },
                    onAddRule = { ruleFor = profile.id },
                    onToggleRule = { rule, enabled -> viewModel.saveRule(rule.copy(enabled = enabled)) },
                    onDeleteRule = { viewModel.deleteRule(it.id) },
                )
            }
            OutlinedButton(onClick = { newProfile = true }) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("Neues Profil", Modifier.padding(start = 8.dp))
            }
        }
    }

    ruleFor?.let { profileId ->
        RuleDialog(viewModel, profileId, onDismiss = { ruleFor = null }, onSave = {
            viewModel.saveRule(it)
            ruleFor = null
        })
    }
    if (newProfile) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newProfile = false },
            title = { Text("Neues Profil") },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                    Text("Startet mit einer Kopie des Standard-Layouts.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.createProfile(name, profiles.firstOrNull { it.kind == ProfileKind.STANDARD }?.id)
                    newProfile = false
                }) { Text("Anlegen") }
            },
            dismissButton = { TextButton(onClick = { newProfile = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun ProfileCard(
    profile: LauncherProfile,
    isBase: Boolean,
    isActive: Boolean,
    rules: List<ProfileRule>,
    onUpdate: (LauncherProfile) -> Unit,
    onDelete: () -> Unit,
    onMakeBase: () -> Unit,
    onAddRule: () -> Unit,
    onToggleRule: (ProfileRule, Boolean) -> Unit,
    onDeleteRule: (ProfileRule) -> Unit,
) {
    var renaming by remember { mutableStateOf(false) }
    var name by remember(profile.name) { mutableStateOf(profile.name) }
    SectionCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = isBase, onClick = onMakeBase)
            Column(Modifier.weight(1f).clickable { renaming = true }) {
                Text(profile.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(if (isBase) "Basisprofil" else null, if (isActive) "gerade aktiv" else null).joinToString(" · ")
                        .ifBlank { "Tippen zum Umbenennen" },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (profile.kind != ProfileKind.STANDARD) {
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = "Profil löschen") }
            }
        }
        if (renaming) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    onUpdate(profile.copy(name = name))
                    renaming = false
                }) { Text("OK") }
            }
        }
        SwitchLine("Große Icons (Auto-Modus)", "2 × 3 Raster ohne Dock – gut bedienbar im Auto.", profile.largeIcons) {
            onUpdate(profile.copy(largeIcons = it))
        }
        Text("Ausgeblendete Kategorien", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
        Chips(
            options = AppCategory.entries.map { it to it.label },
            selected = { it in profile.hiddenCategories },
            onClick = { category ->
                val next = if (category in profile.hiddenCategories) profile.hiddenCategories - category else profile.hiddenCategories + category
                onUpdate(profile.copy(hiddenCategories = next))
            },
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text("Automatisch aktivieren, wenn …", style = MaterialTheme.typography.labelLarge)
        if (rules.isEmpty()) Text("Keine Regeln", style = MaterialTheme.typography.bodySmall)
        rules.forEach { rule ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when (rule.type) {
                        RuleType.BLUETOOTH -> Icons.Default.Bluetooth
                        RuleType.WIFI -> Icons.Default.Wifi
                        RuleType.TIME -> Icons.Default.Schedule
                    },
                    contentDescription = null,
                )
                Text(describeRule(rule), Modifier.weight(1f).padding(horizontal = 8.dp))
                Switch(checked = rule.enabled, onCheckedChange = { onToggleRule(rule, it) })
                IconButton(onClick = { onDeleteRule(rule) }) { Icon(Icons.Default.Delete, contentDescription = "Regel löschen") }
            }
        }
        TextButton(onClick = onAddRule) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text("Regel hinzufügen", Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun RuleDialog(
    viewModel: LauncherSettingsViewModel,
    profileId: Long,
    onDismiss: () -> Unit,
    onSave: (ProfileRule) -> Unit,
) {
    var type by remember { mutableStateOf(RuleType.BLUETOOTH) }
    var value by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var days by remember { mutableIntStateOf(ProfileRule.WEEKDAYS) }
    var start by remember { mutableIntStateOf(8 * 60) }
    var end by remember { mutableIntStateOf(17 * 60) }
    val paired = remember { viewModel.pairedDevices() }
    var locationGranted by remember { mutableStateOf(viewModel.hasLocationPermission()) }
    val locationRequest = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { locationGranted = it }
    val currentSsid by produceState<String?>(null, type, locationGranted) {
        if (type == RuleType.WIFI && locationGranted) viewModel.currentSsid().collect { this.value = it }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Regel hinzufügen") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Chips(RuleType.entries.map { it to it.label }, selected = { it == type }, onClick = {
                    type = it
                    value = ""
                    label = ""
                })
                when (type) {
                    RuleType.BLUETOOTH -> {
                        if (paired.isEmpty()) {
                            Text("Keine gekoppelten Geräte gefunden (Bluetooth-Berechtigung erteilt?).", style = MaterialTheme.typography.bodySmall)
                        }
                        paired.forEach { (address, name) ->
                            Row(Modifier.fillMaxWidth().clickable {
                                value = address
                                label = name
                            }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = value == address, onClick = {
                                    value = address
                                    label = name
                                })
                                Text(name)
                            }
                        }
                    }
                    RuleType.WIFI -> {
                        OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text("WLAN-Name (SSID)") }, singleLine = true)
                        if (!locationGranted) {
                            Text(
                                "Android verrät den WLAN-Namen nur mit Standortberechtigung.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            TextButton(onClick = { locationRequest.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) { Text("Standort erlauben") }
                        } else if (currentSsid != null) {
                            TextButton(onClick = { value = currentSsid.orEmpty() }) { Text("Aktuelles WLAN übernehmen: $currentSsid") }
                        }
                    }
                    RuleType.TIME -> {
                        Text("Tage", style = MaterialTheme.typography.labelLarge)
                        Chips(DAY_LABELS.indices.map { it to DAY_LABELS[it] }, selected = { days and (1 shl it) != 0 }, onClick = { bit ->
                            days = days xor (1 shl bit)
                        })
                        Text("Von", style = MaterialTheme.typography.labelLarge)
                        Chips((5..22).map { it * 60 to formatMinutes(it * 60) }, selected = { it == start }, onClick = { start = it })
                        Text("Bis", style = MaterialTheme.typography.labelLarge)
                        Chips((6..23).map { it * 60 to formatMinutes(it * 60) } + (0 to "00:00"), selected = { it == end }, onClick = { end = it })
                    }
                }
            }
        },
        confirmButton = {
            val valid = when (type) {
                RuleType.BLUETOOTH, RuleType.WIFI -> value.isNotBlank()
                RuleType.TIME -> days != 0 && start != end
            }
            TextButton(enabled = valid, onClick = {
                onSave(
                    ProfileRule(
                        id = 0, profileId = profileId, type = type, value = value.trim(),
                        label = if (type == RuleType.WIFI) value.trim() else label,
                        daysMask = days, startMinutes = start, endMinutes = end, enabled = true,
                    ),
                )
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
