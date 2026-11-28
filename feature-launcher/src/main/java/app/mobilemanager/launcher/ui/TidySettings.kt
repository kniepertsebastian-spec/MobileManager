package app.mobilemanager.launcher.ui

import androidx.compose.ui.res.pluralStringResource
import android.content.res.Resources
import androidx.compose.ui.platform.LocalResources
import app.mobilemanager.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mobilemanager.launcher.data.LauncherApp
import app.mobilemanager.launcher.domain.AppMeta
import app.mobilemanager.launcher.domain.ArchiveMode
import app.mobilemanager.launcher.domain.CustomFolderRule
import kotlinx.coroutines.launch

@Composable
internal fun TidyScreen(viewModel: LauncherSettingsViewModel, onBack: () -> Unit, onStartIntent: (Intent) -> Unit) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val profileId by viewModel.tidyProfileId.collectAsStateWithLifecycle()
    val options by viewModel.options.collectAsStateWithLifecycle()
    val plan by viewModel.plan.collectAsStateWithLifecycle()
    val snapshot by viewModel.latestSnapshot.collectAsStateWithLifecycle()
    val res = LocalResources.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    SettingsScaffold(stringResource(R.string.launcher_homescreen_ordnen), onBack, snackbar) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.launcher_ordnen_sortiert_den_startbildschirm_des) +
                    stringResource(R.string.launcher_einem_ordner_lange_nicht_genutzte) +
                    stringResource(R.string.launcher_waehle_unten_welchen_du_ordnen),
                style = MaterialTheme.typography.bodySmall,
            )
            LauncherStatusHint(viewModel, onStartIntent)
            SectionCard {
                Text(stringResource(R.string.launcher_welches_profil_ordnen), style = MaterialTheme.typography.titleSmall)
                Chips(profiles.map { it.id to it.name }, selected = { it == profileId }, onClick = viewModel::selectTidyProfile)
            }
            SectionCard {
                SwitchLine(
                    stringResource(R.string.launcher_in_ordner_sammeln),
                    stringResource(R.string.launcher_ein_ordner_pro_kategorie_spiele),
                    options.collectIntoFolders,
                ) { viewModel.setOptions(options.copy(collectIntoFolders = it)) }
                SwitchLine(
                    stringResource(R.string.launcher_aufraeumen),
                    pluralStringResource(R.plurals.launcher_unused_days, options.unusedDays, options.unusedDays) +
                        stringResource(R.string.launcher_im_app_drawer_bleiben_sie),
                    options.archiveUnused,
                ) { viewModel.setOptions(options.copy(archiveUnused = it)) }
                if (options.archiveUnused) {
                    Chips(
                        options = listOf(ArchiveMode.FOLDER to stringResource(R.string.launcher_in_ordner_archiv), ArchiveMode.REMOVE to stringResource(R.string.launcher_nur_entfernen)),
                        selected = { it == options.archiveMode },
                        onClick = { viewModel.setOptions(options.copy(archiveMode = it)) },
                    )
                    // Die Nutzungsdaten reichen 60 Tage zurück – längere Zeiträume wären nicht belastbar.
                    Chips(
                        options = listOf(30, 60).map { it to stringResource(R.string.launcher_1_s_tage, it) },
                        selected = { it == options.unusedDays },
                        onClick = { viewModel.setOptions(options.copy(unusedDays = it)) },
                    )
                }
            }
            CustomFoldersSection(viewModel)
            SectionCard {
                Text(stringResource(R.string.launcher_vorschau), style = MaterialTheme.typography.titleSmall)
                val p = plan
                when {
                    p == null -> Text(stringResource(R.string.launcher_tidy_calculating))
                    p.homeAppCount == 0 && !p.changesAnything -> Text(
                        stringResource(R.string.launcher_auf_dem_startbildschirm_dieses_profils) +
                            stringResource(R.string.launcher_startbildschirm_nach_oben_wischen_eine),
                    )
                    !p.changesAnything -> Text(stringResource(R.string.launcher_der_startbildschirm_ist_bereits_aufgeraeumt))
                    else -> {
                        Text(pluralStringResource(R.plurals.launcher_plan_new_folders, p.foldersCreated, p.foldersCreated))
                        Text(pluralStringResource(R.plurals.launcher_plan_apps_moved, p.appsMoved, p.appsMoved))
                        if (p.appsAddedFromDrawer > 0) Text(pluralStringResource(R.plurals.launcher_plan_apps_from_drawer, p.appsAddedFromDrawer, p.appsAddedFromDrawer))
                        if (options.archiveUnused) {
                            if (options.archiveMode == ArchiveMode.FOLDER) Text(pluralStringResource(R.plurals.launcher_plan_apps_archived, p.appsArchived, p.appsArchived))
                            else Text(pluralStringResource(R.plurals.launcher_plan_apps_removed, p.appsRemoved, p.appsRemoved))
                        }
                        Text(pluralStringResource(R.plurals.launcher_plan_pages, p.pages, p.pages))
                    }
                }
                if (p?.usageDataMissing == true) {
                    Text(
                        stringResource(R.string.launcher_ohne_nutzungszugriff_kann_mobilemanager_nicht),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = p?.changesAnything == true,
                        onClick = { viewModel.applyTidy { scope.launch { snackbar.showSnackbar(res.getString(R.string.launcher_msg_tidied)) } } },
                    ) { Text(stringResource(R.string.launcher_anwenden)) }
                    OutlinedButton(
                        enabled = snapshot != null,
                        onClick = {
                            viewModel.undo { ok ->
                                scope.launch { snackbar.showSnackbar(res.getString(if (ok) R.string.launcher_msg_restored else R.string.launcher_msg_nothing_to_undo)) }
                            }
                        },
                    ) { Text(stringResource(R.string.launcher_rueckgaengig)) }
                }
                snapshot?.let {
                    Text(
                        stringResource(
                            R.string.launcher_tidy_snapshot_from,
                            java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).format(java.util.Date(it.createdAt)),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------- Eigene Ordner

@Composable
internal fun CustomFoldersSection(viewModel: LauncherSettingsViewModel) {
    val folders by viewModel.customFolders.collectAsStateWithLifecycle()
    val apps by viewModel.installedApps.collectAsStateWithLifecycle()
    val sideloaded by viewModel.sideloadedPackages.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<CustomFolderRule?>(null) }

    SectionCard {
        Text(stringResource(R.string.launcher_eigene_ordner), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(R.string.launcher_eigene_regeln_gehen_vor_den) +
                stringResource(R.string.launcher_apps_die_bisher_nur_im),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 4.dp),
        )
        folders.forEach { folder ->
            val count = apps.count { app ->
                folder.matches(app.key, AppMeta(app.label, app.category, null, app.packageName, app.packageName in sideloaded))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = folder.enabled, onCheckedChange = { viewModel.saveCustomFolder(folder.copy(enabled = it)) })
                Column(Modifier.weight(1f).padding(horizontal = 12.dp).clickable { editing = folder }) {
                    Text(folder.title, style = MaterialTheme.typography.titleSmall)
                    Text(describeFolder(LocalResources.current, folder, count), style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { editing = folder }) { Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.launcher_bearbeiten)) }
                IconButton(onClick = { viewModel.deleteCustomFolder(folder.id) }) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.launcher_loeschen))
                }
            }
        }
        OutlinedButton(onClick = { editing = CustomFolderRule(title = "") }, modifier = Modifier.padding(top = 4.dp)) {
            Icon(Icons.Default.CreateNewFolder, contentDescription = null)
            Text(stringResource(R.string.launcher_eigenen_ordner_anlegen), Modifier.padding(start = 8.dp))
        }
    }

    editing?.let { rule ->
        CustomFolderDialog(
            initial = rule,
            apps = apps,
            sideloaded = sideloaded,
            onDismiss = { editing = null },
            onSave = {
                viewModel.saveCustomFolder(it)
                editing = null
            },
        )
    }
}

internal fun describeFolder(res: Resources, folder: CustomFolderRule, matching: Int): String = buildList {
    add(res.getQuantityString(R.plurals.launcher_folder_matching, matching, matching))
    if (folder.componentKeys.isNotEmpty()) add(res.getQuantityString(R.plurals.launcher_folder_selected, folder.componentKeys.size, folder.componentKeys.size))
    if (folder.packagePrefixes.isNotEmpty()) add(res.getString(R.string.launcher_folder_package, folder.packagePrefixes.joinToString()))
    if (folder.includeSideloaded) add(res.getString(R.string.launcher_folder_sideloaded))
}.joinToString(" · ")

@Composable
internal fun CustomFolderDialog(
    initial: CustomFolderRule,
    apps: List<LauncherApp>,
    sideloaded: Set<String>,
    onDismiss: () -> Unit,
    onSave: (CustomFolderRule) -> Unit,
) {
    var title by remember { mutableStateOf(initial.title) }
    var prefixes by remember { mutableStateOf(initial.packagePrefixes.joinToString(", ")) }
    var includeSideloaded by remember { mutableStateOf(initial.includeSideloaded) }
    var includeDrawer by remember { mutableStateOf(initial.includeDrawerApps) }
    var selected by remember { mutableStateOf(initial.componentKeys) }
    var query by remember { mutableStateOf("") }

    fun current() = initial.copy(
        title = title.trim(),
        componentKeys = selected,
        packagePrefixes = prefixes.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        includeSideloaded = includeSideloaded,
        includeDrawerApps = includeDrawer,
    )
    val rule = current()
    // Apps, die über Paketname oder „selbst installiert“ ohnehin passen, sind vorab markiert.
    fun matchesAutomatically(app: LauncherApp) = rule.copy(componentKeys = emptySet())
        .matches(app.key, AppMeta(app.label, app.category, null, app.packageName, app.packageName in sideloaded))
    val visible = apps.filter {
        query.isBlank() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial.id == 0L) R.string.launcher_folder_custom_new else R.string.launcher_folder_custom_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text(stringResource(R.string.launcher_ordnername)) }, singleLine = true)
                SwitchLine(stringResource(R.string.launcher_selbst_installierte_apps), stringResource(R.string.launcher_alle_per_apk_datei_oder), includeSideloaded) {
                    includeSideloaded = it
                }
                OutlinedTextField(
                    value = prefixes,
                    onValueChange = { prefixes = it },
                    label = { Text(stringResource(R.string.launcher_paketname_beginnt_mit_optional)) },
                    supportingText = { Text(stringResource(R.string.launcher_z_b_de_meinname_erfasst)) },
                    singleLine = true,
                )
                SwitchLine(stringResource(R.string.launcher_auch_apps_aus_dem_app), stringResource(R.string.launcher_sonst_nur_apps_die_schon), includeDrawer) {
                    includeDrawer = it
                }
                Text(
                    stringResource(R.string.launcher_apps_auswaehlen_1_s_im, apps.count { it.key in selected || matchesAutomatically(it) }),
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
                OutlinedTextField(value = query, onValueChange = { query = it }, label = { Text(stringResource(R.string.launcher_suchen)) }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(visible, key = { it.key }) { app ->
                        val auto = matchesAutomatically(app)
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = !auto) {
                                selected = if (app.key in selected) selected - app.key else selected + app.key
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = auto || app.key in selected,
                                enabled = !auto,
                                onCheckedChange = { on -> selected = if (on) selected + app.key else selected - app.key },
                            )
                            Column {
                                Text(app.label, style = MaterialTheme.typography.bodyMedium)
                                Text(app.packageName, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = title.isNotBlank(), onClick = { onSave(current()) }) { Text(stringResource(R.string.launcher_speichern)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.launcher_abbrechen)) } },
    )
}

// ------------------------------------------------------------------------------------- Profile
