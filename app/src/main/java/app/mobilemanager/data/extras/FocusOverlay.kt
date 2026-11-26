package app.mobilemanager.data.extras

import android.content.Context
import android.graphics.PixelFormat
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.mobilemanager.ui.theme.MobileManagerTheme
import kotlinx.coroutines.delay

/**
 * Meilenstein 3.4.1: ruhiges Vollbild-Overlay (SYSTEM_ALERT_WINDOW) mit Countdown, bevor eine
 * markierte App nutzbar wird. Läuft komplett auf dem Main-Thread.
 */
class FocusOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var root: View? = null
    private var owner: OverlayLifecycleOwner? = null

    var packageName: String? = null
        private set

    val isShowing: Boolean get() = root != null

    fun show(packageName: String, seconds: Int, onCancel: () -> Unit, onOpen: () -> Unit) {
        dismiss()
        val pm = context.packageManager
        val info = runCatching { pm.getApplicationInfo(packageName, android.content.pm.PackageManager.ApplicationInfoFlags.of(0)) }.getOrNull()
        val label = info?.loadLabel(pm)?.toString() ?: packageName
        val icon = info?.let { runCatching { it.loadIcon(pm).toBitmap(192, 192).asImageBitmap() }.getOrNull() }

        val lifecycleOwner = OverlayLifecycleOwner().also { it.create() }
        val composeView = ComposeView(context).apply {
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent {
                MobileManagerTheme {
                    FocusBrakeContent(label, icon, seconds, onCancel, onOpen)
                }
            }
        }
        val container = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) onCancel()
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }
        container.addView(composeView)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        )
        val added = runCatching { windowManager.addView(container, params) }.isSuccess
        if (!added) {
            lifecycleOwner.destroy()
            return
        }
        lifecycleOwner.resume()
        root = container
        owner = lifecycleOwner
        this.packageName = packageName
    }

    fun dismiss() {
        root?.let { runCatching { windowManager.removeView(it) } }
        owner?.destroy()
        root = null
        owner = null
        packageName = null
    }
}

@Composable
private fun FocusBrakeContent(
    label: String,
    icon: androidx.compose.ui.graphics.ImageBitmap?,
    seconds: Int,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
) {
    var remaining by remember { mutableIntStateOf(seconds) }
    LaunchedEffect(Unit) {
        while (remaining > 0) {
            delay(1_000)
            remaining--
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.88f)), contentAlignment = Alignment.Center) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.padding(32.dp)) {
            Column(
                Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(64.dp)) }
                Text("Atempause", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "Du öffnest gerade $label. Wirklich öffnen?",
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
                Box(contentAlignment = Alignment.Center, modifier = Modifier.size(72.dp)) {
                    CircularProgressIndicator(
                        progress = { if (seconds == 0) 1f else 1f - remaining / seconds.toFloat() },
                        modifier = Modifier.size(72.dp),
                    )
                    Text(if (remaining > 0) "$remaining" else "✓", style = MaterialTheme.typography.titleLarge)
                }
                Button(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Abbrechen") }
                OutlinedButton(onClick = onOpen, enabled = remaining == 0, modifier = Modifier.fillMaxWidth()) {
                    Text(if (remaining > 0) "Öffnen in $remaining s" else "Trotzdem öffnen")
                }
            }
        }
    }
}

/** Minimaler Lifecycle für eine ComposeView außerhalb einer Activity. */
private class OverlayLifecycleOwner : SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    fun create() {
        savedState.performAttach()
        savedState.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    fun resume() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        if (registry.currentState == Lifecycle.State.INITIALIZED) return
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
