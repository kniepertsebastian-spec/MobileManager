package app.mobilemanager.ui.extras

import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mobilemanager.data.extras.ApkBackupRepository
import app.mobilemanager.data.extras.BackupApp
import app.mobilemanager.data.extras.SpeakerCleaner
import app.mobilemanager.ui.components.AppIcon
import app.mobilemanager.ui.components.SectionCard
import app.mobilemanager.ui.components.formatBytes
import app.mobilemanager.ui.guard.GuardSubScreen
import app.mobilemanager.ui.theme.StatusColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(private val cleaner: SpeakerCleaner) : ViewModel() {
    private val _progress = MutableStateFlow<Float?>(null)

    /** `null` = läuft nicht. */
    val progress: StateFlow<Float?> = _progress.asStateFlow()
    private var job: Job? = null

    fun headphonesConnected() = cleaner.headphonesConnected()

    fun startCleaner() {
        if (job?.isActive == true) return
        job = viewModelScope.launch {
            try {
                cleaner.run { _progress.value = it }
            } finally {
                _progress.value = null
            }
        }
    }

    fun stopCleaner() {
        job?.cancel()
    }
}

@Composable
fun DiagnosticsScreen(onBack: () -> Unit, onOpenDisplayTest: () -> Unit, viewModel: DiagnosticsViewModel = hiltViewModel()) {
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    var headphones by remember { mutableStateOf(false) }

    GuardSubScreen(title = "Hardware-Schnellcheck", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard {
                Text("Lautsprecher-Reiniger", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Spielt 15 Sekunden lang einen tiefen 165-Hz-Ton auf voller Lautstärke, um Staub und Wasser aus dem " +
                        "Lautsprechergitter zu lösen. Danach wird die vorherige Lautstärke wiederhergestellt. " +
                        "Lautsprecher dabei nach unten halten.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                val p = progress
                if (p != null) {
                    LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                    OutlinedButton(onClick = viewModel::stopCleaner) { Text("Abbrechen") }
                } else {
                    if (headphones) {
                        Text(
                            "Kopfhörer sind verbunden – bitte zuerst trennen, sonst kommt der laute Ton direkt im Ohr an.",
                            color = StatusColors.Denied,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Button(onClick = {
                        headphones = viewModel.headphonesConnected()
                        if (!headphones) viewModel.startCleaner()
                    }) { Text("Reinigung starten") }
                }
            }
            SectionCard {
                Text("Display-Test", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Zeigt nacheinander Rot, Grün, Blau, Weiß und Schwarz im Vollbild, um Pixelfehler und Einbrennen zu erkennen. " +
                        "Tippen wechselt zur nächsten Farbe.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Button(onClick = onOpenDisplayTest) { Text("Test starten") }
            }
        }
    }
}

private val TEST_COLORS = listOf(Color.Red, Color.Green, Color.Blue, Color.White, Color.Black)

/** Vollbild-Farbtest ohne System-Leisten. */
@Composable
fun DisplayTestScreen(onDone: () -> Unit) {
    val view = LocalView.current
    val activity = LocalActivity.current
    var index by remember { mutableIntStateOf(0) }
    var hint by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        val window = activity?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { controller?.show(WindowInsetsCompat.Type.systemBars()) }
    }
    LaunchedEffect(Unit) {
        delay(2_500)
        hint = false
    }
    Box(
        Modifier.fillMaxSize().background(TEST_COLORS[index]).clickable {
            if (index < TEST_COLORS.lastIndex) index++ else onDone()
        },
        contentAlignment = Alignment.Center,
    ) {
        if (hint) {
            Text(
                "Tippen für die nächste Farbe (${index + 1}/${TEST_COLORS.size})",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.5f)).padding(12.dp),
            )
        }
    }
}

@HiltViewModel
class ApkBackupViewModel @Inject constructor(private val repository: ApkBackupRepository) : ViewModel() {
    private val _apps = MutableStateFlow<List<BackupApp>?>(null)
    val apps: StateFlow<List<BackupApp>?> = _apps.asStateFlow()
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    init {
        viewModelScope.launch { _apps.value = repository.installedApps() }
    }

    fun backup(app: BackupApp, onResult: (String) -> Unit) {
        if (_busy.value != null) return
        _busy.value = app.packageName
        viewModelScope.launch {
            repository.backup(app.packageName)
                .onSuccess { path ->
                    onResult(
                        "${app.label} gesichert in $path" +
                            if (app.splitCount > 0) " (${app.splitCount + 1} Teile – Installation mit einem Split-APK-Installer)" else "",
                    )
                }
                .onFailure { onResult("Sicherung fehlgeschlagen: ${it.message}") }
            _busy.value = null
        }
    }
}

@Composable
fun ApkBackupScreen(onBack: () -> Unit, viewModel: ApkBackupViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val apps by viewModel.apps.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var query by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            message = null
        }
    }

    GuardSubScreen(title = "APK-Backup", onBack = onBack) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Text(
                        "Kopiert die Installationsdatei einer App nach Documents/${ApkBackupRepository.BACKUP_DIR}. " +
                            "So kannst du nach einem schlechten Update zur gesicherten Version zurück. " +
                            "App-Daten werden nicht gesichert.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
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
                val list = apps
                if (list == null) {
                    item { CircularProgressIndicator(Modifier.padding(16.dp)) }
                } else {
                    items(list.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) }, key = { it.packageName }) { app ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            AppIcon(app.packageName, size = 36.dp)
                            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${app.versionName} · ${formatBytes(context, app.sizeBytes)}" +
                                        if (app.splitCount > 0) " · ${app.splitCount + 1} Teile" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            if (busy == app.packageName) {
                                CircularProgressIndicator(Modifier.size(24.dp))
                            } else {
                                OutlinedButton(enabled = busy == null, onClick = { viewModel.backup(app) { message = it } }) { Text("Sichern") }
                            }
                        }
                    }
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
    }
}
