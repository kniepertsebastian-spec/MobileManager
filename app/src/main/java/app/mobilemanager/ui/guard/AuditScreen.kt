package app.mobilemanager.ui.guard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mobilemanager.data.audit.PermissionAuditRepository
import app.mobilemanager.data.permissions.PermissionChecker
import app.mobilemanager.domain.model.AuditRisk
import app.mobilemanager.domain.model.AuditedApp
import app.mobilemanager.ui.components.AppIcon
import app.mobilemanager.ui.components.SectionCard
import app.mobilemanager.ui.components.formatAgo
import app.mobilemanager.ui.theme.StatusColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AuditUiState(
    val loading: Boolean = true,
    val apps: List<AuditedApp> = emptyList(),
    val durationMs: Long = 0,
    val hasUsageAccess: Boolean = true,
)

@HiltViewModel
class AuditViewModel @Inject constructor(
    private val repository: PermissionAuditRepository,
    private val permissions: PermissionChecker,
) : ViewModel() {
    private val _state = MutableStateFlow(AuditUiState())
    val state: StateFlow<AuditUiState> = _state.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = it.apps.isEmpty()) }
            val start = System.currentTimeMillis()
            val apps = repository.audit()
            _state.value = AuditUiState(
                loading = false,
                apps = apps,
                durationMs = System.currentTimeMillis() - start,
                hasUsageAccess = permissions.hasUsageAccess(),
            )
        }
    }

    fun settingsIntent(packageName: String) = repository.appSettingsIntent(packageName)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AuditScreen(onBack: () -> Unit, onOpenPermissions: () -> Unit, viewModel: AuditViewModel = hiltViewModel()) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Nach Rückkehr aus den App-Einstellungen neu laden (Rechte könnten entzogen worden sein).
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.load() }

    GuardSubScreen(
        title = "Berechtigungs-Audit",
        onBack = onBack,
        actions = { IconButton(onClick = viewModel::load) { Icon(Icons.Default.Refresh, contentDescription = "Neu laden") } },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@GuardSubScreen
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Apps mit Zugriff auf Standort im Hintergrund, Kamera, Mikrofon, Kontakte oder alle Dateien. " +
                        "„Hohes Risiko“ = seit über 30 Tagen nicht genutzt. ${state.apps.size} Apps · geprüft in ${state.durationMs} ms.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (!state.hasUsageAccess) {
                    OutlinedButton(onClick = onOpenPermissions, modifier = Modifier.padding(top = 8.dp)) {
                        Text("Nutzungszugriff erlauben (für „zuletzt genutzt“)")
                    }
                }
            }
            if (state.apps.isEmpty()) item { Text("Keine Apps mit kritischen Berechtigungen gefunden.") }
            items(state.apps, key = { it.packageName }) { app ->
                SectionCard {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(app.packageName)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                            Text(app.label, style = MaterialTheme.typography.titleMedium)
                            Text(
                                app.risk.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = when (app.risk) {
                                    AuditRisk.HIGH -> StatusColors.Denied
                                    AuditRisk.MEDIUM -> StatusColors.Partial
                                    AuditRisk.LOW -> MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Text(formatAgo(app.lastUsed), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        app.grantedPermissions.forEach { permission ->
                            SuggestionChip(onClick = {}, label = { Text(permission.label) })
                        }
                    }
                    OutlinedButton(onClick = { context.startActivitySafely(viewModel.settingsIntent(app.packageName)) }) {
                        Text("App-Einstellungen öffnen")
                    }
                }
            }
        }
    }
}
