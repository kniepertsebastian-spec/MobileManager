package app.mobilemanager.launcher.ui

import androidx.compose.ui.semantics.Role
import app.mobilemanager.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.mobilemanager.launcher.domain.LayoutItem
import kotlinx.coroutines.launch

/** Geöffneter Ordner wie beim Pixel: abgerundete Karte, Name unten (antippen zum Umbenennen). */
@Composable
internal fun FolderDialog(folder: HomeCell, viewModel: LauncherViewModel, actions: CellActions, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    val defaultFolderName = stringResource(R.string.launcher_folder_default)
    var title by remember(folder.item.id) { mutableStateOf(folder.item.title ?: defaultFolderName) }
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(
                        when {
                            folder.children.size <= 4 -> 2
                            folder.children.size <= 9 -> 3
                            else -> 4
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(folder.children, key = { it.item.id }) { child ->
                        Box(contentAlignment = Alignment.Center) {
                            AppTile(
                                app = child.app,
                                iconSize = 52.dp,
                                showLabel = true,
                                large = false,
                                viewModel = viewModel,
                                labelStyle = labelStyle,
                                onClick = { bounds ->
                                    actions.launch(child.app, bounds)
                                    onDismiss()
                                },
                                onShortcut = { shortcut, bounds ->
                                    actions.shortcut(shortcut, bounds)
                                    onDismiss()
                                },
                                menu = buildList {
                                    add(MenuAction(stringResource(R.string.launcher_app_info), Icons.Default.Info) { actions.info(child.app) })
                                    add(MenuAction(stringResource(R.string.launcher_aus_dem_ordner_nehmen), Icons.Default.RemoveCircleOutline) { viewModel.removeFromFolder(child.item) })
                                    add(MenuAction(stringResource(R.string.launcher_entfernen), Icons.Default.Delete) { viewModel.remove(child.item) })
                                    if (!child.app.isWorkProfile) add(MenuAction(stringResource(R.string.launcher_deinstallieren), Icons.Default.Delete) { actions.uninstall(child.app) })
                                },
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (editing) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            singleLine = true,
                            shape = CircleShape,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            viewModel.renameFolder(folder.item, title)
                            editing = false
                        }) { Icon(Icons.Default.Check, contentDescription = stringResource(R.string.launcher_speichern)) }
                    } else {
                        Text(
                            folder.item.title ?: stringResource(R.string.launcher_ordner),
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable(role = Role.Button, onClickLabel = stringResource(R.string.launcher_a11y_rename)) { editing = true }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun MoveToFolderDialog(
    folders: List<LayoutItem>,
    onDismiss: () -> Unit,
    onMove: (Long) -> Unit,
    onCreate: (String) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    val fallbackName = stringResource(R.string.launcher_folder_default)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.launcher_in_ordner_verschieben)) },
        text = {
            Column {
                folders.forEach { folder ->
                    ListItem(
                        leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onMove(folder.id) },
                    ) { Text(folder.title ?: stringResource(R.string.launcher_ordner)) }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.launcher_neuer_ordner)) },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(newName.ifBlank { fallbackName }) }) { Text(stringResource(R.string.launcher_folder_create)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.launcher_abbrechen)) } },
    )
}
