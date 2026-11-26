package app.mobilemanager.ui.cleaner

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mobilemanager.domain.model.DuplicateGroup
import app.mobilemanager.domain.model.GrantState
import app.mobilemanager.domain.model.MediaImage
import app.mobilemanager.ui.components.ScanProgressView
import app.mobilemanager.ui.components.SectionCard
import app.mobilemanager.ui.components.formatBytes
import coil.compose.AsyncImage

private val AGE_OPTIONS = listOf(3, 6, 12, 24)

@Composable
fun CleanerScreen(
    scanToken: Long,
    onOpenPermissions: () -> Unit,
    onOpenReview: () -> Unit,
    viewModel: CleanerViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LaunchedEffect(scanToken) { viewModel.onScanRequested(scanToken) }

    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.onDeleteResult(it.resultCode == Activity.RESULT_OK)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.mediaAccess == GrantState.DENIED) {
            item {
                SectionCard {
                    Text("Zugriff auf Fotos fehlt", style = MaterialTheme.typography.titleMedium)
                    Text("Ohne diese Berechtigung kann der Speicher-Cleaner keine Bilder prüfen.")
                    Button(onClick = onOpenPermissions, modifier = Modifier.padding(top = 8.dp)) { Text("Berechtigen") }
                }
            }
            return@LazyColumn
        }
        if (state.mediaAccess == GrantState.PARTIAL) {
            item {
                Text(
                    "Hinweis: Du hast nur ausgewählte Fotos freigegeben – der Scan sieht nicht alle Bilder.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        item {
            SectionCard {
                Text("Speicher-Scan", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Sucht exakte Duplikate (Dateigröße + SHA-256) und alte Fotos ohne Personen (ML Kit, lokal).",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text("Fotos ohne Personen: älter als", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AGE_OPTIONS.forEach { months ->
                        FilterChip(
                            selected = state.photoAgeMonths == months,
                            onClick = { viewModel.setPhotoAgeMonths(months) },
                            label = { Text("$months Monate") },
                            enabled = !state.scanning,
                        )
                    }
                }
                val progress = state.progress
                if (state.scanning && progress != null) {
                    ScanProgressView(progress, Modifier.padding(top = 12.dp))
                    OutlinedButton(onClick = viewModel::cancelScan, modifier = Modifier.padding(top = 8.dp)) { Text("Abbrechen") }
                } else {
                    Button(onClick = viewModel::startScan, modifier = Modifier.padding(top = 8.dp)) { Text("Scan starten") }
                }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }

        item {
            SectionCard {
                Text("Alte Fotos ohne Personen", style = MaterialTheme.typography.titleMedium)
                val count = state.withoutPeopleCount
                Text(
                    when (count) {
                        null -> "Noch nicht gescannt."
                        0 -> "Keine Kandidaten – alles aufgeräumt."
                        else -> "$count Fotos warten auf deine Entscheidung."
                    },
                )
                Button(
                    onClick = onOpenReview,
                    enabled = (count ?: 0) > 0 && !state.scanning,
                    modifier = Modifier.padding(top = 8.dp),
                ) { Text("Swipe-Review starten") }
            }
        }

        val groups = state.duplicateGroups
        if (groups != null) {
            item {
                val selected = groups.flatMap { it.all }.filter { it.id in state.selectedDuplicateIds }
                Column {
                    Text(
                        if (groups.isEmpty()) "Keine exakten Duplikate gefunden." else "${groups.size} Duplikat-Gruppen",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    if (selected.isNotEmpty()) {
                        Button(
                            onClick = {
                                viewModel.buildDuplicateDeleteRequest()?.let {
                                    deleteLauncher.launch(IntentSenderRequest.Builder(it).build())
                                }
                            },
                            modifier = Modifier.padding(top = 8.dp),
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = null)
                            Text(
                                " ${selected.size} Duplikate löschen (${formatBytes(context, selected.sumOf { it.sizeBytes })})",
                            )
                        }
                    }
                }
            }
            items(groups, key = { it.sha256 }) { group ->
                DuplicateGroupRow(group, state.selectedDuplicateIds) { viewModel.toggleDuplicate(group, it) }
            }
        }
    }
}

@Composable
private fun DuplicateGroupRow(group: DuplicateGroup, selectedIds: Set<Long>, onToggle: (MediaImage) -> Unit) {
    val context = LocalContext.current
    SectionCard {
        Text(
            "${group.all.size} identische Bilder · ${formatBytes(context, group.keep.sizeBytes)} je Datei",
            style = MaterialTheme.typography.labelLarge,
        )
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            group.all.forEach { image ->
                val selected = image.id in selectedIds
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(96.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(
                                BorderStroke(
                                    3.dp,
                                    if (selected) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                                ),
                                RoundedCornerShape(12.dp),
                            )
                            .clickable { onToggle(image) },
                    ) {
                        AsyncImage(
                            model = image.uri,
                            contentDescription = image.displayName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Icon(
                            if (selected) Icons.Default.Delete else if (image.id == group.keep.id) Icons.Default.Lock else Icons.Default.CheckCircle,
                            contentDescription = if (selected) "Wird gelöscht" else "Bleibt erhalten",
                            tint = Color.White,
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                        )
                    }
                    Text(
                        image.bucket ?: image.relativePath ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(96.dp),
                    )
                }
            }
        }
    }
}
