package app.mobilemanager.launcher.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.mobilemanager.launcher.data.LauncherShortcut

@Composable
internal fun CellMenu(
    expanded: Boolean,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
    shortcuts: List<LauncherShortcut> = emptyList(),
    onShortcut: (LauncherShortcut) -> Unit = {},
) {
    DropdownMenu(
        expanded = expanded && (actions.isNotEmpty() || shortcuts.isNotEmpty()),
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        shortcuts.forEach { shortcut ->
            DropdownMenuItem(
                text = { Text(shortcut.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = {
                    val icon = shortcut.icon
                    if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                    else Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
                },
                onClick = {
                    onDismiss()
                    onShortcut(shortcut)
                },
            )
        }
        if (shortcuts.isNotEmpty() && actions.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp))
        actions.forEach { action ->
            DropdownMenuItem(
                text = { Text(action.label) },
                leadingIcon = { Icon(action.icon, contentDescription = null) },
                onClick = {
                    onDismiss()
                    action.onClick()
                },
            )
        }
    }
}
