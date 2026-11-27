package app.mobilemanager

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import app.mobilemanager.ui.locker.LockActivity
import app.mobilemanager.ui.navigation.MobileManagerNavHost
import app.mobilemanager.ui.navigation.Routes
import app.mobilemanager.ui.theme.MobileManagerTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)

        setContent {
            MobileManagerTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val onboardingDone by viewModel.onboardingDone.collectAsStateWithLifecycle()
                    val destination by viewModel.pendingDestination.collectAsStateWithLifecycle()
                    val done = onboardingDone
                    if (done == null) {
                        Box(Modifier.fillMaxSize()) // Einstellungen werden geladen
                    } else {
                        // Startziel nur einmal festlegen, sonst baut NavHost den Graphen nach dem Onboarding neu auf.
                        val startOnboarding = remember { !done }
                        MobileManagerNavHost(
                            startOnboarding = startOnboarding,
                            externalDestination = destination,
                            onExternalDestinationHandled = viewModel::clearPendingDestination,
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
        lifecycleScope.launch {
            if (viewModel.selfNeedsUnlock()) {
                setRecentsScreenshotEnabled(false)
                startActivity(LockActivity.intent(this@MainActivity, packageName))
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val route = when (intent?.getStringExtra(EXTRA_DESTINATION)) {
            DESTINATION_APP_STOPPER -> Routes.APP_STOPPER
            DESTINATION_CLEANER -> Routes.cleaner()
            DESTINATION_PERMISSIONS -> Routes.PERMISSIONS
            DESTINATION_SECURITY -> Routes.GUARD_SECURITY
            DESTINATION_BATTERY -> Routes.GUARD_BATTERY
            DESTINATION_NOTIFICATIONS -> Routes.GUARD_NOTIFICATIONS
            DESTINATION_EXPIRED_REVIEW -> Routes.review(Routes.REVIEW_MODE_EXPIRED)
            DESTINATION_TRACKER -> Routes.EXTRAS_TRACKER
            DESTINATION_EXTRAS -> Routes.EXTRAS
            DESTINATION_NETWORK -> Routes.NETWORK
            DESTINATION_LOCKER -> Routes.LOCKER
            DESTINATION_PROTECTION -> Routes.PROTECTION
            DESTINATION_POWER_TOOLS -> Routes.POWER_TOOLS
            else -> null
        }
        route?.let(viewModel::setPendingDestination)
    }

    companion object {
        const val EXTRA_DESTINATION = "destination"
        const val DESTINATION_APP_STOPPER = "app_stopper"
        const val DESTINATION_CLEANER = "cleaner"
        const val DESTINATION_PERMISSIONS = "permissions"
        const val DESTINATION_SECURITY = "security"
        const val DESTINATION_BATTERY = "battery"
        const val DESTINATION_NOTIFICATIONS = "notifications"
        const val DESTINATION_EXPIRED_REVIEW = "expired_review"
        const val DESTINATION_TRACKER = "tracker"
        const val DESTINATION_EXTRAS = "extras"
        const val DESTINATION_NETWORK = "network"
        const val DESTINATION_LOCKER = "locker"
        const val DESTINATION_PROTECTION = "protection"
        const val DESTINATION_POWER_TOOLS = "power_tools"
    }
}
