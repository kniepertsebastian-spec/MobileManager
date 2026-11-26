package app.mobilemanager.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mobilemanager.data.settings.AppSettings
import app.mobilemanager.data.settings.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
) : ViewModel() {
    // Die WorkManager-Jobs gleicht MainViewModel bei jeder Änderung automatisch ab.
    val settings: StateFlow<AppSettings> =
        repository.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    fun setAutoStopInterval(hours: Int) {
        viewModelScope.launch { repository.setAutoStopIntervalHours(hours) }
    }

    fun setNightRoutine(enabled: Boolean) {
        viewModelScope.launch { repository.setNightRoutineEnabled(enabled) }
    }
}

private val INTERVAL_OPTIONS = listOf(0 to "Aus", 6 to "Alle 6 h", 12 to "Alle 12 h", 24 to "Täglich")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, viewModel: SettingsViewModel = hiltViewModel()) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Einstellungen") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            ListItem(
                supportingContent = {
                    Column {
                        Text("Stoppt inaktive Apps periodisch im Hintergrund (nur mit Shizuku).")
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            INTERVAL_OPTIONS.forEach { (hours, label) ->
                                FilterChip(
                                    selected = settings.autoStopIntervalHours == hours,
                                    onClick = { viewModel.setAutoStopInterval(hours) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }
                },
            ) { Text("Automatischer App-Stopper") }
            ListItem(
                supportingContent = {
                    Text("Duplikat- und Personen-Scan automatisch ausführen – nur wenn das Gerät lädt und im Leerlauf ist.")
                },
                trailingContent = { Switch(checked = settings.nightRoutineEnabled, onCheckedChange = viewModel::setNightRoutine) },
            ) { Text("Nacht-Routine") }
            Text(
                "Alle Daten bleiben auf diesem Gerät. Keine Cloud, keine Telemetrie. Einzige Ausnahme: " +
                    "Sicherheitsalarme an den Kanal, den du unter Wächter → Alarm-Versand selbst einrichtest.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}
