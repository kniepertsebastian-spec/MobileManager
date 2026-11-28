package app.mobilemanager.launcher.ui

import android.content.res.Resources
import androidx.compose.ui.platform.LocalResources
import app.mobilemanager.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mobilemanager.launcher.domain.AppCategory
import app.mobilemanager.launcher.domain.LauncherProfile
import app.mobilemanager.launcher.domain.ProfileKind
import app.mobilemanager.launcher.domain.ProfileRule
import app.mobilemanager.launcher.domain.RuleType
import kotlinx.coroutines.launch

internal val DAY_LABELS = listOf(
    R.string.launcher_day_mo, R.string.launcher_day_di, R.string.launcher_day_mi, R.string.launcher_day_do,
    R.string.launcher_day_fr, R.string.launcher_day_sa, R.string.launcher_day_so,
)

internal fun formatMinutes(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

internal fun describeRule(res: Resources, rule: ProfileRule): String = when (rule.type) {
    RuleType.BLUETOOTH -> res.getString(R.string.launcher_rule_bluetooth, rule.label.ifBlank { rule.value })
    RuleType.WIFI -> res.getString(R.string.launcher_rule_wifi, rule.value)
    RuleType.TIME -> {
        val days = DAY_LABELS.filterIndexed { i, _ -> rule.daysMask and (1 shl i) != 0 }.map(res::getString)
        val dayText = when (rule.daysMask) {
            ProfileRule.WEEKDAYS -> res.getString(R.string.launcher_rule_weekdays)
            ProfileRule.ALL_DAYS -> res.getString(R.string.launcher_rule_daily)
            else -> days.joinToString(", ")
        }
        "$dayText ${formatMinutes(rule.startMinutes)}–${formatMinutes(rule.endMinutes)}"
    }
}

@Composable
internal fun ProfilesScreen(viewModel: LauncherSettingsViewModel, onBack: () -> Unit, onStartIntent: (Intent) -> Unit) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val activeId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    var ruleFor by remember { mutableStateOf<Long?>(null) }
    var newProfile by remember { mutableStateOf(false) }

    SettingsScaffold(stringResource(R.string.launcher_profile_wechselregeln), onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionCard {
                Text(stringResource(R.string.launcher_so_funktionieren_profile), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.launcher_jedes_profil_ist_ein_eigener) +
                        stringResource(R.string.launcher_wenn_mobilemanager_dein_startbildschirm_ist) +
                        stringResource(R.string.launcher_wechseln_kreis_links_neben_dem) +
                        stringResource(R.string.launcher_stelle_druecken_bzw_oben_auf) +
                        stringResource(R.string.launcher_automatisch_regeln_schalten_voruebergehend_um) +
                        stringResource(R.string.launcher_kommt_das_ausgewaehlte_profil_zurueck),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            LauncherStatusHint(viewModel, onStartIntent)
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
                Text(stringResource(R.string.launcher_neues_profil), Modifier.padding(start = 8.dp))
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
            title = { Text(stringResource(R.string.launcher_neues_profil)) },
            text = {
                Column {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.launcher_name)) }, singleLine = true)
                    Text(stringResource(R.string.launcher_startet_mit_einer_kopie_des), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.createProfile(name, profiles.firstOrNull { it.kind == ProfileKind.STANDARD }?.id)
                    newProfile = false
                }) { Text(stringResource(R.string.launcher_anlegen)) }
            },
            dismissButton = { TextButton(onClick = { newProfile = false }) { Text(stringResource(R.string.launcher_abbrechen)) } },
        )
    }
}

@Composable
internal fun ProfileCard(
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
                    listOfNotNull(if (isBase) stringResource(R.string.launcher_ausgewaehlt) else null, if (isActive) stringResource(R.string.launcher_gerade_auf_dem_startbildschirm) else null).joinToString(" · ")
                        .ifBlank { stringResource(R.string.launcher_kreis_antippen_zum_auswaehlen_name) },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (profile.kind != ProfileKind.STANDARD) {
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.launcher_profil_loeschen)) }
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
        SwitchLine(stringResource(R.string.launcher_grosse_icons_auto_modus), stringResource(R.string.launcher_2_3_raster_ohne_dock), profile.largeIcons) {
            onUpdate(profile.copy(largeIcons = it))
        }
        Text(stringResource(R.string.launcher_ausgeblendete_kategorien), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
        Chips(
            options = AppCategory.entries.map { it to it.label },
            selected = { it in profile.hiddenCategories },
            onClick = { category ->
                val next = if (category in profile.hiddenCategories) profile.hiddenCategories - category else profile.hiddenCategories + category
                onUpdate(profile.copy(hiddenCategories = next))
            },
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        Text(stringResource(R.string.launcher_automatisch_aktivieren_wenn), style = MaterialTheme.typography.labelLarge)
        if (rules.isEmpty()) Text(stringResource(R.string.launcher_keine_regeln), style = MaterialTheme.typography.bodySmall)
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
                Text(describeRule(LocalResources.current, rule), Modifier.weight(1f).padding(horizontal = 8.dp))
                Switch(checked = rule.enabled, onCheckedChange = { onToggleRule(rule, it) })
                IconButton(onClick = { onDeleteRule(rule) }) { Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.launcher_regel_loeschen)) }
            }
        }
        TextButton(onClick = onAddRule) {
            Icon(Icons.Default.Add, contentDescription = null)
            Text(stringResource(R.string.launcher_regel_hinzufuegen), Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
internal fun RuleDialog(
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
        title = { Text(stringResource(R.string.launcher_regel_hinzufuegen)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Chips(RuleType.entries.map { it to stringResource(it.labelRes) }, selected = { it == type }, onClick = {
                    type = it
                    value = ""
                    label = ""
                })
                when (type) {
                    RuleType.BLUETOOTH -> {
                        if (paired.isEmpty()) {
                            Text(stringResource(R.string.launcher_keine_gekoppelten_geraete_gefunden_bluetooth), style = MaterialTheme.typography.bodySmall)
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
                        OutlinedTextField(value = value, onValueChange = { value = it }, label = { Text(stringResource(R.string.launcher_wlan_name_ssid)) }, singleLine = true)
                        if (!locationGranted) {
                            Text(
                                stringResource(R.string.launcher_android_verraet_den_wlan_namen),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                            TextButton(onClick = { locationRequest.launch(Manifest.permission.ACCESS_FINE_LOCATION) }) { Text(stringResource(R.string.launcher_standort_erlauben)) }
                        } else if (currentSsid != null) {
                            TextButton(onClick = { value = currentSsid.orEmpty() }) { Text(stringResource(R.string.launcher_rule_take_wifi, currentSsid.orEmpty())) }
                        }
                    }
                    RuleType.TIME -> {
                        Text(stringResource(R.string.launcher_tage), style = MaterialTheme.typography.labelLarge)
                        Chips(DAY_LABELS.indices.map { it to stringResource(DAY_LABELS[it]) }, selected = { days and (1 shl it) != 0 }, onClick = { bit ->
                            days = days xor (1 shl bit)
                        })
                        Text(stringResource(R.string.launcher_von), style = MaterialTheme.typography.labelLarge)
                        Chips((5..22).map { it * 60 to formatMinutes(it * 60) }, selected = { it == start }, onClick = { start = it })
                        Text(stringResource(R.string.launcher_bis), style = MaterialTheme.typography.labelLarge)
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
            }) { Text(stringResource(R.string.launcher_speichern)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.launcher_abbrechen)) } },
    )
}
