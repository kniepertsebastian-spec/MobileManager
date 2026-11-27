package app.mobilemanager.launcher.ui

import android.app.AlarmManager
import android.app.WallpaperColors
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

/** Schriftfarbe auf dem Hintergrundbild: hell mit Schatten oder – bei hellen Bildern – dunkel wie beim Pixel. */
data class WallpaperText(val darkText: Boolean) {
    val color: Color get() = if (darkText) Color(0xFF1F1F1F) else Color.White

    fun style(size: Float = 12f) = TextStyle(
        color = color,
        fontSize = size.sp,
        shadow = if (darkText) null else Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1.5f), 6f),
    )
}

val LocalWallpaperText = staticCompositionLocalOf { WallpaperText(darkText = false) }

/** Liest den Hinweis „unterstützt dunkle Schrift“ aus den Farben des Hintergrundbilds (ohne Berechtigung). */
@Composable
fun rememberWallpaperText(): WallpaperText {
    val context = LocalContext.current
    val manager = remember { context.getSystemService(WallpaperManager::class.java) }
    var darkText by remember { mutableStateOf(false) }
    DisposableEffect(manager) {
        val listener = WallpaperManager.OnColorsChangedListener { colors, which ->
            if (which and WallpaperManager.FLAG_SYSTEM != 0) darkText = colors.supportsDarkText()
        }
        darkText = runCatching { manager.getWallpaperColors(WallpaperManager.FLAG_SYSTEM).supportsDarkText() }.getOrDefault(false)
        manager.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))
        onDispose { manager.removeOnColorsChangedListener(listener) }
    }
    return WallpaperText(darkText)
}

private fun WallpaperColors?.supportsDarkText() = this != null && colorHints and WallpaperColors.HINT_SUPPORTS_DARK_TEXT != 0

private data class GlanceInfo(val date: String = "", val alarm: String? = null, val charging: String? = null)

/**
 * „Auf einen Blick“ wie beim Pixel Launcher – komplett lokal: Datum (öffnet den Kalender),
 * nächster Wecker in den kommenden 12 Stunden und Ladezustand. Wetter gibt es bewusst nicht,
 * weil dafür ein Online-Dienst nötig wäre.
 */
@Composable
fun AtAGlance(
    profileLabel: String?,
    onStartIntent: (Intent) -> Unit,
    onProfileClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val text = LocalWallpaperText.current
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    val info by produceState(GlanceInfo(), resumes) {
        while (true) {
            value = withContext(Dispatchers.Default) { glanceNow(context) }
            delay(MINUTE_MS - System.currentTimeMillis() % MINUTE_MS + 50)
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            info.date,
            style = text.style(24f),
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .clickable {
                    onStartIntent(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_CALENDAR))
                }
                .padding(horizontal = 4.dp, vertical = 2.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            info.alarm?.let { alarm ->
                GlanceChip(Icons.Default.Alarm, alarm, text) { onStartIntent(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }
            }
            info.charging?.let { charging -> GlanceChip(Icons.Default.BatteryChargingFull, charging, text, onClick = null) }
            profileLabel?.let { label -> GlanceChip(Icons.Default.SpaceDashboard, label, text, onProfileClick) }
        }
    }
}

@Composable
private fun GlanceChip(icon: ImageVector, label: String, text: WallpaperText, onClick: (() -> Unit)?) {
    Row(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = text.color, modifier = Modifier.size(16.dp).padding(end = 2.dp))
        Text(label, style = text.style(14f), modifier = Modifier.padding(start = 2.dp))
    }
}

private fun glanceNow(context: Context): GlanceInfo {
    val locale = Locale.getDefault()
    val now = System.currentTimeMillis()
    val date = DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "EEEEMMMd"), locale).format(LocalDate.now())
    val alarm = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.triggerTime
        ?.takeIf { it - now in 0..ALARM_WINDOW_MS }
        ?.let { DateFormat.getTimeFormat(context).format(Date(it)) }
    val battery = ContextCompat.registerReceiver(
        context, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED,
    )
    val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
    val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
    val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
    val charging = if (plugged && level >= 0 && scale > 0) {
        val percent = level * 100 / scale
        if (percent >= 100) "Vollständig geladen" else "Lädt · $percent %"
    } else {
        null
    }
    return GlanceInfo(date, alarm, charging)
}

private const val MINUTE_MS = 60_000L
private const val ALARM_WINDOW_MS = 12 * 60 * 60_000L
