package app.mobilemanager.ui.extras

import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSliderState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mobilemanager.data.apps.InstalledAppsRepository
import app.mobilemanager.data.extras.DetectionMode
import app.mobilemanager.data.extras.ExtrasCoordinator
import app.mobilemanager.data.extras.ExtrasSettings
import app.mobilemanager.data.extras.ExtrasSettingsRepository
import app.mobilemanager.data.extras.PerAppVolumeController
import app.mobilemanager.data.local.AppVolumeRuleEntity
import app.mobilemanager.data.permissions.PermissionChecker
import app.mobilemanager.domain.extras.PerAppVolumePolicy
import app.mobilemanager.domain.model.LaunchableApp
import app.mobilemanager.domain.model.PermissionKind
import app.mobilemanager.ui.components.AppIcon
import app.mobilemanager.ui.components.SectionCard
import app.mobilemanager.ui.guard.ChoiceChips
import app.mobilemanager.ui.guard.GuardSubScreen
import app.mobilemanager.ui.guard.SwitchRow
import app.mobilemanager.ui.guard.startActivitySafely
import app.mobilemanager.ui.theme.StatusColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

data class DetectionStatus(
    val mode: DetectionMode = DetectionMode.NONE,
    val canDrawOverlays: Boolean = true,
)

@HiltViewModel
class FocusVolumeViewModel @Inject constructor(
    private val settingsRepository: ExtrasSettingsRepository,
    private val coordinator: ExtrasCoordinator,
    private val permissions: PermissionChecker,
    private val appsRepository: InstalledAppsRepository,
    private val volume: PerAppVolumeController,
) : ViewModel() {

    val settings: StateFlow<ExtrasSettings> =
        settingsRepository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ExtrasSettings())
    val volumeRules: StateFlow<List<AppVolumeRuleEntity>> =
        volume.observeRules.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _apps = MutableStateFlow<List<LaunchableApp>>(emptyList())
    val apps: StateFlow<List<LaunchableApp>> = _apps.asStateFlow()
    private val _status = MutableStateFlow(DetectionStatus())
    val status: StateFlow<DetectionStatus> = _status.asStateFlow()

    init {
        viewModelScope.launch { _apps.value = appsRepository.getLaunchableApps() }
    }

    fun refresh() {
        _status.value = DetectionStatus(coordinator.detectionMode(), permissions.canDrawOverlays())
        coordinator.sync()
    }

    fun update(transform: (ExtrasSettings) -> ExtrasSettings) {
        viewModelScope.launch { settingsRepository.update(transform) }
    }

    fun toggleFocusApp(packageName: String) = update { s ->
        val next = if (packageName in s.focusPackages) s.focusPackages - packageName else s.focusPackages + packageName
        s.copy(focusPackages = next)
    }

    fun saveVolume(packageName: String, percent: Int) {
        viewModelScope.launch { volume.save(packageName, percent) }
    }

    fun deleteVolume(packageName: String) {
        viewModelScope.launch { volume.delete(packageName) }
    }

    fun labelOf(packageName: String): String = _apps.value.firstOrNull { it.packageName == packageName }?.label ?: appsRepository.labelOf(packageName)

    fun settingsIntent(kind: PermissionKind): Intent? = permissions.settingsIntent(kind)
}

@Composable
private fun DetectionCard(status: DetectionStatus, needsOverlay: Boolean, viewModel: FocusVolumeViewModel) {
    val context = LocalContext.current
    SectionCard {
        Text("App-Erkennung", style = MaterialTheme.typography.titleSmall)
        Text(
            when (status.mode) {
                DetectionMode.ACCESSIBILITY -> "Über die Bedienungshilfe – sofort und ohne Dauer-Benachrichtigung."
                DetectionMode.USAGE_STATS -> "Über den Nutzungszugriff (ca. 1 s Verzögerung, stille Dauer-Benachrichtigung). " +
                    "Schneller geht es mit der Bedienungshilfe."
                DetectionMode.NONE -> "Keine Erkennung möglich – Bedienungshilfe oder Nutzungszugriff erteilen."
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (status.mode == DetectionMode.NONE) StatusColors.Denied else MaterialTheme.colorScheme.onSurface,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            if (status.mode != DetectionMode.ACCESSIBILITY) {
                OutlinedButton(onClick = { context.startActivitySafely(viewModel.settingsIntent(PermissionKind.APP_SWITCH_ACCESSIBILITY)) }) {
                    Text("Bedienungshilfe")
                }
            }
            if (status.mode == DetectionMode.NONE) {
                OutlinedButton(onClick = { context.startActivitySafely(viewModel.settingsIntent(PermissionKind.USAGE_ACCESS)) }) {
                    Text("Nutzungszugriff")
                }
            }
        }
        if (needsOverlay && !status.canDrawOverlays) {
            Text(
                "Für das Atempause-Overlay fehlt „Über anderen Apps einblenden“.",
                color = StatusColors.Denied,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            OutlinedButton(onClick = {
                context.startActivitySafely(
                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.fromParts("package", context.packageName, null)),
                )
            }) { Text("Overlay erlauben") }
        }
    }
}

@Composable
fun FocusBrakeScreen(onBack: () -> Unit, viewModel: FocusVolumeViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    var query by rememberSaveable { mutableStateOf("") }
    val sorted = remember(apps, settings.focusPackages, query) {
        apps.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }
            .sortedWith(compareByDescending<LaunchableApp> { it.packageName in settings.focusPackages }.thenBy { it.label.lowercase() })
    }

    GuardSubScreen(title = "Fokus-Bremse", onBack = onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SectionCard {
                    SwitchRow(
                        title = "Fokus-Bremse",
                        subtitle = "Beim Öffnen einer markierten App erscheint zuerst eine ruhige Atempause mit Countdown.",
                        checked = settings.focusEnabled,
                        onCheckedChange = { v -> viewModel.update { it.copy(focusEnabled = v) } },
                    )
                    Text("Countdown", style = MaterialTheme.typography.labelLarge)
                    ChoiceChips(
                        options = listOf(3 to "3 s", 5 to "5 s", 10 to "10 s"),
                        selected = settings.focusCountdownSeconds,
                        onSelect = { v -> viewModel.update { it.copy(focusCountdownSeconds = v) } },
                    )
                    Text("Nach „Trotzdem öffnen“ Ruhe für", style = MaterialTheme.typography.labelLarge)
                    ChoiceChips(
                        options = listOf(1 to "1 min", 5 to "5 min", 15 to "15 min", 30 to "30 min"),
                        selected = settings.focusGraceMinutes,
                        onSelect = { v -> viewModel.update { it.copy(focusGraceMinutes = v) } },
                    )
                }
            }
            item { DetectionCard(status, needsOverlay = true, viewModel = viewModel) }
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text("Apps durchsuchen") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            items(sorted, key = { it.packageName }) { app ->
                Row(
                    Modifier.fillMaxWidth().clickable { viewModel.toggleFocusApp(app.packageName) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppIcon(app.packageName, size = 36.dp)
                    Text(app.label, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Checkbox(checked = app.packageName in settings.focusPackages, onCheckedChange = { viewModel.toggleFocusApp(app.packageName) })
                }
            }
        }
    }
}

@Composable
fun AppVolumeScreen(onBack: () -> Unit, viewModel: FocusVolumeViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val rules by viewModel.volumeRules.collectAsStateWithLifecycle()
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    var picking by remember { mutableStateOf(false) }

    GuardSubScreen(title = "Lautstärke pro App", onBack = onBack) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                SectionCard {
                    SwitchRow(
                        title = "Lautstärke pro App",
                        subtitle = "Setzt beim Öffnen einer App deren Medienlautstärke und stellt beim Verlassen den vorherigen Wert wieder her.",
                        checked = settings.volumeEnabled,
                        onCheckedChange = { v -> viewModel.update { it.copy(volumeEnabled = v) } },
                    )
                }
            }
            item { DetectionCard(status, needsOverlay = false, viewModel = viewModel) }
            items(rules, key = { it.packageName }) { rule ->
                SectionCard {
                    // value, steps (5-%-Schritte), valueRange
                    val sliderState = rememberSliderState(rule.volumePercent.toFloat(), 19, 0f..100f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(rule.packageName, size = 36.dp)
                        Text(viewModel.labelOf(rule.packageName), Modifier.weight(1f).padding(horizontal = 12.dp))
                        Text("${sliderState.value.roundToInt()} %", style = MaterialTheme.typography.titleSmall)
                        IconButton(onClick = { viewModel.deleteVolume(rule.packageName) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Regel löschen")
                        }
                    }
                    Slider(
                        state = sliderState,
                        onValueChange = { sliderState.value = it },
                        onValueChangeFinished = { viewModel.saveVolume(rule.packageName, sliderState.value.roundToInt()) },
                    )
                }
            }
            item {
                OutlinedButton(onClick = { picking = true }) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("App hinzufügen", Modifier.padding(start = 8.dp))
                }
            }
        }
    }

    if (picking) {
        var query by rememberSaveable { mutableStateOf("") }
        val existing = rules.map { it.packageName }.toSet()
        val filtered = apps.filter { it.packageName !in existing && (query.isBlank() || it.label.contains(query, ignoreCase = true)) }
        AlertDialog(
            onDismissRequest = { picking = false },
            title = { Text("App wählen") },
            text = {
                Column {
                    OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, placeholder = { Text("Suchen") })
                    LazyColumn(Modifier.heightIn(max = 360.dp).padding(top = 8.dp)) {
                        items(filtered, key = { it.packageName }) { app ->
                            Row(
                                Modifier.fillMaxWidth().clickable {
                                    val am = context.getSystemService(AudioManager::class.java)
                                    val current = am?.let {
                                        PerAppVolumePolicy.indexToPercent(
                                            it.getStreamVolume(AudioManager.STREAM_MUSIC),
                                            it.getStreamMinVolume(AudioManager.STREAM_MUSIC),
                                            it.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
                                        )
                                    } ?: 50
                                    viewModel.saveVolume(app.packageName, current)
                                    picking = false
                                }.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppIcon(app.packageName, size = 32.dp)
                                Text(app.label, Modifier.padding(start = 12.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Abbrechen") } },
        )
    }
}
