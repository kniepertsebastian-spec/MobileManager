package app.mobilemanager.launcher.ui

import androidx.compose.ui.res.pluralStringResource
import app.mobilemanager.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mobilemanager.launcher.LauncherComponents
import app.mobilemanager.launcher.data.GestureAction

@Composable
internal fun OverviewScreen(
    viewModel: LauncherSettingsViewModel,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onStartIntent: (Intent) -> Unit,
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshStatus() }
    val context = androidx.compose.ui.platform.LocalContext.current

    SettingsScaffold(stringResource(R.string.launcher_launcher), onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard {
                Text(
                    when {
                        !status.enabled -> stringResource(R.string.launcher_launcher_ist_ausgeschaltet)
                        status.isDefault -> stringResource(R.string.launcher_mobilemanager_ist_dein_startbildschirm)
                        else -> stringResource(R.string.launcher_launcher_ist_bereit_aber_nicht)
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(R.string.launcher_der_vorinstallierte_pixel_launcher_laesst) +
                        stringResource(R.string.launcher_muss_mobilemanager_selbst_der_startbildschirm),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                if (status.enabled && !status.isDefault) {
                    Button(onClick = { onStartIntent(LauncherComponents.requestDefaultHomeIntent(context)) }) {
                        Text(stringResource(R.string.launcher_als_standard_startbildschirm_festlegen))
                    }
                } else if (status.isDefault) {
                    OutlinedButton(onClick = { onStartIntent(Intent(android.provider.Settings.ACTION_HOME_SETTINGS)) }) {
                        Text(stringResource(R.string.launcher_anderen_startbildschirm_waehlen))
                    }
                }
            }

            SectionCard {
                Text(stringResource(R.string.launcher_raster), style = MaterialTheme.typography.titleSmall)
                Chips(
                    options = listOf(4 to 5, 4 to 6, 5 to 5, 5 to 6).map { it to "${it.first} × ${it.second}" },
                    selected = { it.first == settings.columns && it.second == settings.rows },
                    onClick = { (c, r) -> viewModel.updateSettings { s -> s.copy(columns = c, rows = r) } },
                )
                SwitchLine(stringResource(R.string.launcher_app_namen_anzeigen), null, settings.showLabels) { v -> viewModel.updateSettings { it.copy(showLabels = v) } }
                SwitchLine(
                    stringResource(R.string.launcher_designsymbole),
                    stringResource(R.string.launcher_wie_beim_pixel_einfarbige_icons),
                    settings.themedIcons,
                ) { v -> viewModel.updateSettings { it.copy(themedIcons = v) } }
                SwitchLine(
                    stringResource(R.string.launcher_automatischer_profilwechsel),
                    stringResource(R.string.launcher_wechselt_je_nach_bluetooth_geraet),
                    settings.autoSwitch,
                ) { v -> viewModel.updateSettings { it.copy(autoSwitch = v) } }
            }

            // Phase 4.7: Fokus-Darstellung, Gesten und Tresor
            SectionCard {
                Text(stringResource(R.string.launcher_fokus_gesten), style = MaterialTheme.typography.titleSmall)
                SwitchLine(stringResource(R.string.launcher_graustufen), stringResource(R.string.launcher_farblose_icons_nehmen_apps_den), settings.grayscale) { v ->
                    viewModel.updateSettings { it.copy(grayscale = v) }
                }
                SwitchLine(stringResource(R.string.launcher_nur_text), stringResource(R.string.launcher_startbildschirm_als_ruhige_textliste_statt), settings.textOnly) { v ->
                    viewModel.updateSettings { it.copy(textOnly = v) }
                }
                SwitchLine(
                    stringResource(R.string.launcher_automatisch_bei_fokus_bremse),
                    stringResource(R.string.launcher_graustufen_und_textliste_solange_die),
                    settings.minimalInFocus,
                ) { v -> viewModel.updateSettings { it.copy(minimalInFocus = v) } }
                Text(stringResource(R.string.launcher_doppelt_tippen), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Chips(
                    options = GestureAction.entries.map { it to stringResource(it.labelRes) },
                    selected = { it == settings.doubleTapAction },
                    onClick = { a -> viewModel.updateSettings { it.copy(doubleTapAction = a) } },
                )
                Text(stringResource(R.string.launcher_nach_unten_wischen), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Chips(
                    options = GestureAction.entries.map { it to stringResource(it.labelRes) },
                    selected = { it == settings.swipeDownAction },
                    onClick = { a -> viewModel.updateSettings { it.copy(swipeDownAction = a) } },
                )
                Text(
                    stringResource(R.string.launcher_sperren_benachrichtigungen_und_schnelleinstellungen_brauchen) +
                        stringResource(R.string.launcher_mobilemanager_app_sperre_fokus_bremse),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    stringResource(R.string.launcher_tresor_apps_im_drawer_lange) +
                        pluralStringResource(R.plurals.launcher_vault_hint_count, settings.vault.size, settings.vault.size),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            NavCard(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.launcher_homescreen_ordnen), stringResource(R.string.launcher_apps_per_knopfdruck_in_ordner)) {
                onOpen(LauncherSettingsActivity.SCREEN_TIDY)
            }
            NavCard(Icons.Default.SpaceDashboard, stringResource(R.string.launcher_profile_wechselregeln), stringResource(R.string.launcher_standard_arbeit_auto_fokus_eigene)) {
                onOpen(LauncherSettingsActivity.SCREEN_PROFILES)
            }
        }
    }
}

/** Hinweis mit Knopf, solange MobileManager nicht der Startbildschirm ist – sonst sieht man von Ordnen und Profilen nichts. */
@Composable
internal fun LauncherStatusHint(viewModel: LauncherSettingsViewModel, onStartIntent: (Intent) -> Unit) {
    val status by viewModel.status.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refreshStatus() }
    if (status.isDefault) return
    val context = androidx.compose.ui.platform.LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.launcher_mobilemanager_ist_nicht_dein_startbildschirm), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.launcher_aenderungen_hier_wirken_sich_erst),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            if (status.enabled) {
                Button(onClick = { onStartIntent(LauncherComponents.requestDefaultHomeIntent(context)) }) {
                    Text(stringResource(R.string.launcher_als_startbildschirm_festlegen))
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------- Ordnen
