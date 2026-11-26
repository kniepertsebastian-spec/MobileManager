package app.mobilemanager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Statusfarben für Berechtigungs-Checkliste & Co. */
object StatusColors {
    val Granted = Color(0xFF2E7D32)
    val Partial = Color(0xFFF9A825)
    val Denied = Color(0xFFC62828)
}

private val FallbackLight = lightColorScheme(primary = Color(0xFF2E6B30), secondary = Color(0xFF52634F))
private val FallbackDark = darkColorScheme(primary = Color(0xFF93D58E), secondary = Color(0xFFB9CCB4))

/** Material You: auf Android 12+ (minSdk 33) immer Dynamic Color. */
@Composable
fun MobileManagerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> FallbackDark
        else -> FallbackLight
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}
