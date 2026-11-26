package app.mobilemanager.ui.cleaner

import android.app.Activity
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mobilemanager.domain.model.MediaImage
import app.mobilemanager.ui.components.formatBytes
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.math.abs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeReviewScreen(
    onBack: () -> Unit,
    viewModel: ReviewViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val deleteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        viewModel.onDeleteResult(it.resultCode == Activity.RESULT_OK)
    }
    fun launchDelete() {
        val request = viewModel.buildDeleteRequest()
        if (request != null) {
            deleteLauncher.launch(IntentSenderRequest.Builder(request).build())
        } else {
            viewModel.deleteWithoutDialog()
        }
    }

    LaunchedEffect(state.finished) { if (state.finished) onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (state.isExpiredMode) "Alte Screenshots & Downloads" else "Fotos ohne Personen") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
                },
                actions = {
                    IconButton(onClick = viewModel::undo, enabled = state.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Rückgängig")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Noch ${state.remaining} · ${state.toDelete.size} zum Löschen · ${state.keptCount} behalten",
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                "← Wischen = löschen   ·   Wischen → = behalten",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 12.dp),
            )

            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val current = state.current
                if (state.loading) {
                    Text("Lade …")
                } else if (current == null) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            if (state.isExpiredMode) "Alle Dateien bewertet!" else "Alle Fotos bewertet!",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        if (state.toDelete.isNotEmpty()) {
                            Text(
                                "${state.toDelete.size} Dateien (${formatBytes(context, state.toDelete.sumOf { it.sizeBytes })}) " +
                                    "werden nach Bestätigung im Android-Dialog gelöscht.",
                                modifier = Modifier.padding(16.dp),
                            )
                            Button(onClick = ::launchDelete) { Text("Jetzt löschen") }
                        }
                    }
                } else {
                    state.next?.let {
                        PhotoCard(it, viewModel.kindLabel(it), Modifier.graphicsLayer { scaleX = 0.95f; scaleY = 0.95f })
                    }
                    SwipeableCard(
                        image = current,
                        kindLabel = viewModel.kindLabel(current),
                        onSwipedLeft = viewModel::swipeLeft,
                        onSwipedRight = viewModel::swipeRight,
                    )
                }
            }

            if (state.current != null) {
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LargeFloatingActionButton(
                        onClick = viewModel::swipeLeft,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ) { Icon(Icons.Default.Delete, contentDescription = "Löschen") }
                    FilledTonalIconButton(onClick = viewModel::undo, enabled = state.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = "Rückgängig")
                    }
                    LargeFloatingActionButton(
                        onClick = viewModel::swipeRight,
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                    ) { Icon(Icons.Default.Favorite, contentDescription = "Behalten") }
                }
                if (state.toDelete.isNotEmpty()) {
                    Button(onClick = ::launchDelete, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Sitzung beenden & ${state.toDelete.size} löschen")
                    }
                }
            }
        }
    }
}

@Composable
private fun SwipeableCard(image: MediaImage, kindLabel: String?, onSwipedLeft: () -> Unit, onSwipedRight: () -> Unit) {
    val scope = rememberCoroutineScope()
    var screenWidthPx by remember { mutableFloatStateOf(1_000f) }
    val threshold = screenWidthPx * 0.3f
    val offsetX = remember(image.id) { Animatable(0f) }

    PhotoCard(
        image = image,
        kindLabel = kindLabel,
        overlayAlpha = (abs(offsetX.value) / threshold).coerceIn(0f, 1f),
        overlayDelete = offsetX.value < 0,
        modifier = Modifier
            .onSizeChanged { if (it.width > 0) screenWidthPx = it.width.toFloat() }
            .graphicsLayer {
                translationX = offsetX.value
                rotationZ = offsetX.value / screenWidthPx * 15f
            }
            .pointerInput(image.id, screenWidthPx) {
                detectDragGestures(
                    onDragEnd = {
                        scope.launch {
                            when {
                                offsetX.value < -threshold -> {
                                    offsetX.animateTo(-screenWidthPx * 1.5f, tween(200))
                                    onSwipedLeft()
                                }
                                offsetX.value > threshold -> {
                                    offsetX.animateTo(screenWidthPx * 1.5f, tween(200))
                                    onSwipedRight()
                                }
                                else -> offsetX.animateTo(0f)
                            }
                        }
                    },
                    onDragCancel = { scope.launch { offsetX.animateTo(0f) } },
                    onDrag = { change, drag ->
                        change.consume()
                        scope.launch { offsetX.snapTo(offsetX.value + drag.x) }
                    },
                )
            },
    )
}

@Composable
private fun PhotoCard(
    image: MediaImage,
    kindLabel: String?,
    modifier: Modifier = Modifier,
    overlayAlpha: Float = 0f,
    overlayDelete: Boolean = false,
) {
    val context = LocalContext.current
    Card(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
            if (image.isImage) {
                AsyncImage(
                    model = image.uri,
                    contentDescription = image.displayName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                // PDFs & Co.: Dateisymbol statt Vorschau
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Default.Description,
                        contentDescription = null,
                        modifier = Modifier.size(96.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(image.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
                    image.mimeType?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            if (overlayAlpha > 0f) {
                val tint = if (overlayDelete) Color(0xFFC62828) else Color(0xFF2E7D32)
                Box(Modifier.fillMaxSize().background(tint.copy(alpha = overlayAlpha * 0.5f)))
            }
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(12.dp),
            ) {
                val date = DateFormat.getMediumDateFormat(context).format(Date(image.dateTaken))
                Text(
                    listOfNotNull(kindLabel, date, formatBytes(context, image.sizeBytes)).joinToString(" · "),
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                )
                (image.bucket ?: image.relativePath)?.let {
                    Text(it, color = Color.White, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
