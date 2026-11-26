package app.mobilemanager.launcher.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import dagger.hilt.android.AndroidEntryPoint

/**
 * Meilenstein 3.1.1: Startbildschirm (ACTION_MAIN + CATEGORY_HOME, singleTask).
 * Das Fenster ist transparent und zeigt das System-Hintergrundbild (Theme.Launcher).
 */
@AndroidEntryPoint
class LauncherActivity : ComponentActivity() {

    private val viewModel: LauncherViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        setContent {
            LauncherTheme {
                LauncherScreen(
                    viewModel = viewModel,
                    onStartIntent = ::startSafely,
                    onOpenSettings = { screen ->
                        startSafely(LauncherSettingsActivity.intent(this, screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    },
                    onMessage = { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home-Geste, während der Launcher schon vorne ist.
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_HOME)) viewModel.onHomePressed()
    }

    private fun startSafely(intent: Intent) {
        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Keine passende App gefunden", Toast.LENGTH_SHORT).show()
        } catch (_: SecurityException) {
            Toast.makeText(this, "Aktion nicht erlaubt", Toast.LENGTH_SHORT).show()
        }
    }
}
