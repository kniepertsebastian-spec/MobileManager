package app.mobilemanager.launcher.ui

import app.mobilemanager.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import app.mobilemanager.launcher.LauncherComponents
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/** Einstellungen des Launchers: Raster, „Ordnen“, Profile und Wechselregeln (Meilenstein 3.2). */
@AndroidEntryPoint
class LauncherSettingsActivity : ComponentActivity() {

    private val viewModel: LauncherSettingsViewModel by viewModels()

    /**
     * Der Rollen-Dialog „Standard-Startbildschirm“ erscheint nur per startActivityForResult – mit startActivity
     * kennt das System die anfragende App nicht und schließt ihn sofort. Lehnt der Nutzer ab oder unterdrückt
     * Android den Dialog (nach mehrfachem Ablehnen), geht es über die Einstellungsseite weiter.
     */
    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        viewModel.refreshStatus()
        if (!LauncherComponents.isDefaultHome(this)) {
            Toast.makeText(this, getString(R.string.launcher_msg_choose_home), Toast.LENGTH_LONG).show()
            startSafely(Intent(Settings.ACTION_HOME_SETTINGS))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val start = intent.getStringExtra(EXTRA_SCREEN) ?: SCREEN_OVERVIEW
        setContent {
            LauncherTheme {
                var screen by rememberSaveable { mutableStateOf(start) }
                val back: () -> Unit = { if (screen == SCREEN_OVERVIEW || screen == start) finish() else screen = SCREEN_OVERVIEW }
                BackHandler(onBack = back)
                when (screen) {
                    SCREEN_TIDY -> TidyScreen(viewModel, onBack = back, onStartIntent = ::startSafely)
                    SCREEN_PROFILES -> ProfilesScreen(viewModel, onBack = back, onStartIntent = ::startSafely)
                    else -> OverviewScreen(
                        viewModel = viewModel,
                        onBack = back,
                        onOpen = { screen = it },
                        onStartIntent = ::startSafely,
                    )
                }
            }
        }
    }

    private fun startSafely(intent: Intent) {
        try {
            if (intent.action == ACTION_REQUEST_ROLE) roleRequest.launch(intent) else startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.launcher_msg_setting_missing), Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        const val EXTRA_SCREEN = "screen"
        const val SCREEN_OVERVIEW = "overview"
        const val SCREEN_TIDY = "tidy"
        const val SCREEN_PROFILES = "profiles"

        /** Aktion von [android.app.role.RoleManager.createRequestRoleIntent] (die Konstante selbst ist nicht öffentlich). */
        private const val ACTION_REQUEST_ROLE = "android.app.role.action.REQUEST_ROLE"

        fun intent(context: Context, screen: String = SCREEN_OVERVIEW): Intent =
            Intent(context, LauncherSettingsActivity::class.java).putExtra(EXTRA_SCREEN, screen)
    }
}
