package app.mobilemanager.ui.components

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.mobilemanager.data.apps.AppVisuals
import app.mobilemanager.domain.model.ScanProgress
import java.util.concurrent.TimeUnit

/** Asynchron geladenes App-Icon aus dem gemeinsamen Cache ([AppVisuals]). */
@Composable
fun AppIcon(packageName: String, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val context = LocalContext.current
    var bitmap by remember(packageName) { mutableStateOf(AppVisuals.peekIcon(packageName, ICON_PX)) }
    LaunchedEffect(packageName) {
        if (bitmap == null) bitmap = AppVisuals.icon(context, packageName, ICON_PX)
    }
    Box(modifier.size(size)) {
        val current = bitmap
        if (current != null) {
            Image(bitmap = current, contentDescription = null, modifier = Modifier.size(size))
        } else {
            Icon(Icons.Default.Android, contentDescription = null, modifier = Modifier.size(size))
        }
    }
}

private const val ICON_PX = 96

@Composable
fun SectionCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
fun ScanProgressView(progress: ScanProgress, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        val label = if (progress.total > 0) "${progress.phase} (${progress.processed}/${progress.total})" else progress.phase
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (progress.total > 0) {
            LinearProgressIndicator(
                progress = { progress.processed.toFloat() / progress.total },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
}

fun formatBytes(context: android.content.Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)

/** "vor 5 Stunden", "vor 3 Tagen" … */
fun formatAgo(lastUsed: Long?, now: Long = System.currentTimeMillis()): String {
    if (lastUsed == null) return "Seit über 60 Tagen nicht genutzt"
    val diff = (now - lastUsed).coerceAtLeast(0)
    val hours = TimeUnit.MILLISECONDS.toHours(diff)
    val days = TimeUnit.MILLISECONDS.toDays(diff)
    return when {
        hours < 1 -> "Zuletzt genutzt vor weniger als 1 Stunde"
        hours < 48 -> "Zuletzt genutzt vor $hours ${if (hours == 1L) "Stunde" else "Stunden"}"
        else -> "Zuletzt genutzt vor $days Tagen"
    }
}
