package app.mobilemanager.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.mobilemanager.data.shizuku.ShizukuState
import app.mobilemanager.domain.model.LaunchableApp

/** Phase 4: Mehrfachauswahl von Apps (Vollbild-Dialog mit Suche). */
@Composable
fun AppPickerDialog(
    title: String,
    apps: List<LaunchableApp>,
    selected: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (Set<String>) -> Unit,
) {
    var current by remember { mutableStateOf(selected) }
    var query by remember { mutableStateOf("") }
    val sorted = remember(apps, query) {
        apps.filter { query.isBlank() || it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }
            .sortedWith(compareByDescending<LaunchableApp> { it.packageName in selected }.thenBy { it.label.lowercase() })
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(title, style = MaterialTheme.typography.titleLarge)
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text("Apps durchsuchen") },
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.weight(1f)) {
                    items(sorted, key = { it.packageName }) { app ->
                        val checked = app.packageName in current
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable { current = if (checked) current - app.packageName else current + app.packageName }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AppIcon(app.packageName, size = 36.dp)
                            Text(app.label, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Checkbox(
                                checked = checked,
                                onCheckedChange = { current = if (it) current + app.packageName else current - app.packageName },
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = onDismiss) { Text("Abbrechen") }
                    Button(onClick = { onConfirm(current) }) { Text("Übernehmen (${current.size})") }
                }
            }
        }
    }
}

/** Zeile „Apps auswählen“ mit Anzahl und Pfeil. */
@Composable
fun AppSelectionRow(title: String, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(if (count == 0) "Keine Apps gewählt" else "$count Apps", style = MaterialTheme.typography.bodySmall)
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
    }
}

/** Hinweis, wenn eine Funktion Shizuku braucht, mit passender Aktion. */
@Composable
fun ShizukuRequirement(state: ShizukuState, onRequestPermission: () -> Unit, onOpenShizuku: () -> Unit) {
    if (state == ShizukuState.READY) return
    SectionCard {
        Text("Shizuku nötig", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.error)
        Text(
            when (state) {
                ShizukuState.NOT_INSTALLED -> "Diese Funktion braucht Shizuku (Shell-Rechte ohne Root). Installiere Shizuku und starte es über „Kabelloses Debugging“."
                ShizukuState.NOT_RUNNING -> "Shizuku läuft nicht – nach jedem Neustart einmal in der Shizuku-App starten (Kabelloses Debugging)."
                ShizukuState.NO_PERMISSION -> "MobileManager hat noch keine Shizuku-Berechtigung."
                ShizukuState.READY -> ""
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
            if (state == ShizukuState.NO_PERMISSION) Button(onClick = onRequestPermission) { Text("Berechtigung erteilen") }
            if (state == ShizukuState.NOT_RUNNING) OutlinedButton(onClick = onOpenShizuku) { Text("Shizuku öffnen") }
        }
    }
}

/**
 * Passwort-/PIN-Feld auf Basis von [androidx.compose.material3.OutlinedSecureTextField] (verdeckte Eingabe,
 * kein Kopieren). [resetKey] leert das Feld, z. B. nach einer falschen Eingabe.
 */
@Composable
fun SecretField(
    label: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    digitsOnly: Boolean = false,
    maxLength: Int = 64,
    resetKey: Any? = null,
    onDone: (() -> Unit)? = null,
) {
    val state = androidx.compose.foundation.text.input.rememberTextFieldState()
    androidx.compose.runtime.LaunchedEffect(resetKey) { if (resetKey != null) state.edit { replace(0, length, "") } }
    val currentOnChange by androidx.compose.runtime.rememberUpdatedState(onValueChange)
    androidx.compose.runtime.LaunchedEffect(state) {
        androidx.compose.runtime.snapshotFlow { state.text.toString() }.collect { currentOnChange(it) }
    }
    androidx.compose.material3.OutlinedSecureTextField(
        state = state,
        label = { Text(label) },
        inputTransformation = androidx.compose.foundation.text.input.InputTransformation {
            if (length > maxLength || (digitsOnly && !asCharSequence().all(Char::isDigit))) revertAllChanges()
        },
        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
            keyboardType = if (digitsOnly) androidx.compose.ui.text.input.KeyboardType.NumberPassword else androidx.compose.ui.text.input.KeyboardType.Password,
            imeAction = androidx.compose.ui.text.input.ImeAction.Done,
        ),
        onKeyboardAction = { onDone?.invoke() },
        modifier = modifier.fillMaxWidth(),
    )
}
