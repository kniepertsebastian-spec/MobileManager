package app.mobilemanager.ui.guard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mobilemanager.data.battery.BatteryMonitor
import app.mobilemanager.data.guard.GuardSettings
import app.mobilemanager.data.guard.GuardSettingsRepository
import app.mobilemanager.domain.guard.HeatPolicy
import app.mobilemanager.domain.guard.Schedule
import app.mobilemanager.domain.model.BatteryStatus
import app.mobilemanager.domain.model.NightReport
import app.mobilemanager.ui.components.SectionCard
import app.mobilemanager.ui.theme.StatusColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class BatteryViewModel @Inject constructor(
    monitor: BatteryMonitor,
    private val guardSettings: GuardSettingsRepository,
) : ViewModel() {
    val battery: StateFlow<BatteryStatus?> = monitor.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val settings: StateFlow<GuardSettings> =
        guardSettings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuardSettings())
    val nightReport: StateFlow<NightReport?> =
        guardSettings.nightReport.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun update(transform: (GuardSettings) -> GuardSettings) {
        viewModelScope.launch { guardSettings.update(transform) }
    }
}

@Composable
fun BatteryScreen(onBack: () -> Unit, viewModel: BatteryViewModel = hiltViewModel()) {
    val battery by viewModel.battery.collectAsStateWithLifecycle()
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val report by viewModel.nightReport.collectAsStateWithLifecycle()

    GuardSubScreen(title = "Akku & Temperatur", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard {
                val b = battery
                if (b == null) {
                    Text("Akkuwerte werden gelesen …")
                } else {
                    Row {
                        Column(Modifier.weight(1f)) {
                            Text("Ladestand", style = MaterialTheme.typography.labelLarge)
                            Text("${b.levelPercent} %", style = MaterialTheme.typography.headlineMedium)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Temperatur", style = MaterialTheme.typography.labelLarge)
                            Text(
                                "${"%.1f".format(b.temperatureCelsius)} °C",
                                style = MaterialTheme.typography.headlineMedium,
                                color = if (b.temperatureCelsius >= s.heatThresholdCelsius) StatusColors.Denied else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                    LinearProgressIndicator(
                        progress = { b.levelPercent / 100f },
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    )
                    Text(if (b.isPlugged) "Ladegerät angeschlossen" else "Nicht am Ladegerät", style = MaterialTheme.typography.bodySmall)
                }
            }

            SectionCard {
                SwitchRow(
                    title = "Hitzewarnung",
                    subtitle = "Hochpriorisierte Warnung ab ${s.heatThresholdCelsius} °C – erneut erst nach Abkühlung unter " +
                        "${s.heatThresholdCelsius - HeatPolicy.HYSTERESIS_CELSIUS.toInt()} °C.",
                    checked = s.heatAlertEnabled,
                    onCheckedChange = { v -> viewModel.update { it.copy(heatAlertEnabled = v) } },
                )
                ChoiceChips(
                    options = listOf(40 to "40 °C", 42 to "42 °C", 45 to "45 °C"),
                    selected = s.heatThresholdCelsius,
                    onSelect = { v -> viewModel.update { it.copy(heatThresholdCelsius = v) } },
                    enabled = s.heatAlertEnabled,
                )
                SwitchRow(
                    title = "Ladelimit-Hinweis",
                    subtitle = "Einmaliger Hinweiston, wenn beim Laden ${s.chargeLimitPercent} % erreicht sind.",
                    checked = s.chargeAlertEnabled,
                    onCheckedChange = { v -> viewModel.update { it.copy(chargeAlertEnabled = v) } },
                )
                ChoiceChips(
                    options = listOf(80 to "80 %", 85 to "85 %", 90 to "90 %"),
                    selected = s.chargeLimitPercent,
                    onSelect = { v -> viewModel.update { it.copy(chargeLimitPercent = v) } },
                    enabled = s.chargeAlertEnabled,
                )
                SwitchRow(
                    title = "Echtzeit-Überwachung",
                    subtitle = "Prüft Akku und neue Apps sofort statt alle 15 Minuten. Zeigt dafür eine dauerhafte, " +
                        "stille Benachrichtigung an.",
                    checked = s.realtimeServiceEnabled,
                    onCheckedChange = { v -> viewModel.update { it.copy(realtimeServiceEnabled = v) } },
                )
            }

            SectionCard {
                SwitchRow(
                    title = "Nacht-Standby-Bericht",
                    subtitle = "Vergleicht den Akkustand zwischen ${Schedule.format(s.nightStartMinutes)} und " +
                        "${Schedule.format(s.nightEndMinutes)} Uhr (nur wenn das Gerät nicht lädt).",
                    checked = s.nightReportEnabled,
                    onCheckedChange = { v -> viewModel.update { it.copy(nightReportEnabled = v) } },
                )
                Text("Nachtruhe ab", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(
                    options = listOf(21, 22, 23, 0).map { it * 60 to Schedule.format(it * 60) },
                    selected = s.nightStartMinutes,
                    onSelect = { v -> viewModel.update { it.copy(nightStartMinutes = v) } },
                    enabled = s.nightReportEnabled,
                )
                Text("bis", style = MaterialTheme.typography.labelLarge)
                ChoiceChips(
                    options = listOf(5, 6, 7, 8).map { it * 60 to Schedule.format(it * 60) },
                    selected = s.nightEndMinutes,
                    onSelect = { v -> viewModel.update { it.copy(nightEndMinutes = v) } },
                    enabled = s.nightReportEnabled,
                )
                val r = report
                if (r != null) {
                    val format = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
                    Text(
                        "Letzter Bericht: −${r.dropPercent} % in ${"%.1f".format(r.hours)} h " +
                            "(${r.startLevel} % → ${r.endLevel} %)",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Text(
                        "${format.format(Date(r.startTime))} – ${format.format(Date(r.endTime))}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text("Noch kein Bericht vorhanden.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
    }
}
