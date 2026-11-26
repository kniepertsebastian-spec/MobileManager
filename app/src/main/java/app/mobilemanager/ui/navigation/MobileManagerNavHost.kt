package app.mobilemanager.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import app.mobilemanager.ui.appstopper.AppStopperScreen
import app.mobilemanager.ui.automation.AutomationScreen
import app.mobilemanager.ui.cleaner.CleanerScreen
import app.mobilemanager.ui.cleaner.SwipeReviewScreen
import app.mobilemanager.ui.dashboard.DashboardScreen
import app.mobilemanager.ui.guard.AlertSettingsScreen
import app.mobilemanager.ui.guard.AuditScreen
import app.mobilemanager.ui.guard.BatteryScreen
import app.mobilemanager.ui.guard.GuardHubScreen
import app.mobilemanager.ui.guard.NotificationsScreen
import app.mobilemanager.ui.guard.SecurityScreen
import app.mobilemanager.ui.permissions.PermissionsScreen
import app.mobilemanager.ui.settings.SettingsScreen

object Routes {
    const val DASHBOARD = "dashboard"
    const val APP_STOPPER = "app_stopper"
    const val CLEANER = "cleaner"
    const val CLEANER_PATTERN = "cleaner?scanToken={scanToken}"
    const val REVIEW_PATTERN = "cleaner/review?mode={mode}"
    const val REVIEW_MODE_PHOTOS = "photos"
    const val REVIEW_MODE_EXPIRED = "expired"
    const val AUTOMATION = "automation"
    const val PERMISSIONS = "permissions"
    const val ONBOARDING = "onboarding"
    const val SETTINGS = "settings"
    const val GUARD = "guard"
    const val GUARD_SECURITY = "guard/security"
    const val GUARD_ALERTS = "guard/alerts"
    const val GUARD_AUDIT = "guard/audit"
    const val GUARD_BATTERY = "guard/battery"
    const val GUARD_NOTIFICATIONS = "guard/notifications"

    fun review(mode: String = REVIEW_MODE_PHOTOS) = "cleaner/review?mode=$mode"

    /** [scanToken] != 0 startet beim Öffnen einmalig einen Scan (z. B. vom Dashboard). */
    fun cleaner(scanToken: Long = 0L) = "cleaner?scanToken=$scanToken"
}

private enum class TopLevel(val route: String, val pattern: String, val label: String, val icon: ImageVector) {
    DASHBOARD(Routes.DASHBOARD, Routes.DASHBOARD, "Dashboard", Icons.Default.Dashboard),
    APP_STOPPER(Routes.APP_STOPPER, Routes.APP_STOPPER, "App-Stopper", Icons.Default.StopCircle),
    CLEANER(Routes.cleaner(), Routes.CLEANER_PATTERN, "Cleaner", Icons.Default.CleaningServices),
    AUTOMATION(Routes.AUTOMATION, Routes.AUTOMATION, "Automatik", Icons.Default.AutoMode),
    GUARD(Routes.GUARD, Routes.GUARD, "Wächter", Icons.Default.Shield),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MobileManagerNavHost(
    startOnboarding: Boolean,
    externalDestination: String?,
    onExternalDestinationHandled: () -> Unit,
    navController: NavHostController = rememberNavController(),
) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = backStackEntry?.destination
    val currentTopLevel = TopLevel.entries.firstOrNull { tab ->
        currentDestination?.hierarchy?.any { it.route == tab.pattern } == true
    }

    // Ziel aus einer Benachrichtigung öffnen
    LaunchedEffect(externalDestination) {
        val destination = externalDestination ?: return@LaunchedEffect
        if (!startOnboarding) navigateTopLevel(navController, destination)
        onExternalDestinationHandled()
    }

    Scaffold(
        topBar = {
            if (currentTopLevel != null) {
                TopAppBar(
                    title = { Text(currentTopLevel.label) },
                    actions = {
                        IconButton(onClick = { navController.navigate(Routes.PERMISSIONS) }) {
                            Icon(Icons.Default.Security, contentDescription = "Berechtigungen")
                        }
                        IconButton(onClick = { navController.navigate(Routes.SETTINGS) }) {
                            Icon(Icons.Default.Settings, contentDescription = "Einstellungen")
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (currentTopLevel != null) {
                NavigationBar {
                    TopLevel.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = tab == currentTopLevel,
                            onClick = { navigateTopLevel(navController, tab.route) },
                            icon = { Icon(tab.icon, contentDescription = null) },
                            label = { Text(tab.label) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(if (currentTopLevel != null) padding else PaddingValues())) {
            NavHost(
                navController = navController,
                startDestination = if (startOnboarding) Routes.ONBOARDING else Routes.DASHBOARD,
            ) {
                composable(Routes.ONBOARDING) {
                    PermissionsScreen(
                        isOnboarding = true,
                        onBack = {},
                        onOnboardingFinished = {
                            navController.navigate(Routes.DASHBOARD) {
                                popUpTo(navController.graph.id) { inclusive = true }
                            }
                        },
                    )
                }
                composable(Routes.DASHBOARD) {
                    DashboardScreen(
                        onOpenAppStopper = { navigateTopLevel(navController, Routes.APP_STOPPER) },
                        onStartScan = {
                            navigateTopLevel(navController, Routes.cleaner(System.currentTimeMillis()), restore = false)
                        },
                        onOpenAutomation = { navigateTopLevel(navController, Routes.AUTOMATION) },
                        onOpenPermissions = { navController.navigate(Routes.PERMISSIONS) },
                        onOpenGuard = { navigateTopLevel(navController, Routes.GUARD) },
                    )
                }
                composable(Routes.APP_STOPPER) {
                    AppStopperScreen(onOpenPermissions = { navController.navigate(Routes.PERMISSIONS) })
                }
                composable(
                    Routes.CLEANER_PATTERN,
                    arguments = listOf(navArgument("scanToken") { type = NavType.LongType; defaultValue = 0L }),
                ) { entry ->
                    CleanerScreen(
                        scanToken = entry.arguments?.getLong("scanToken") ?: 0L,
                        onOpenPermissions = { navController.navigate(Routes.PERMISSIONS) },
                        onOpenReview = { navController.navigate(Routes.review()) },
                    )
                }
                composable(
                    Routes.REVIEW_PATTERN,
                    arguments = listOf(navArgument("mode") { type = NavType.StringType; defaultValue = Routes.REVIEW_MODE_PHOTOS }),
                ) {
                    SwipeReviewScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.GUARD) {
                    GuardHubScreen(
                        onOpenSecurity = { navController.navigate(Routes.GUARD_SECURITY) },
                        onOpenAlerts = { navController.navigate(Routes.GUARD_ALERTS) },
                        onOpenAudit = { navController.navigate(Routes.GUARD_AUDIT) },
                        onOpenBattery = { navController.navigate(Routes.GUARD_BATTERY) },
                        onOpenNotifications = { navController.navigate(Routes.GUARD_NOTIFICATIONS) },
                        onOpenExpiredReview = { navController.navigate(Routes.review(Routes.REVIEW_MODE_EXPIRED)) },
                    )
                }
                composable(Routes.GUARD_SECURITY) {
                    SecurityScreen(
                        onBack = { navController.popBackStack() },
                        onOpenPermissions = { navController.navigate(Routes.PERMISSIONS) },
                        onOpenAlerts = { navController.navigate(Routes.GUARD_ALERTS) },
                    )
                }
                composable(Routes.GUARD_ALERTS) {
                    AlertSettingsScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.GUARD_AUDIT) {
                    AuditScreen(
                        onBack = { navController.popBackStack() },
                        onOpenPermissions = { navController.navigate(Routes.PERMISSIONS) },
                    )
                }
                composable(Routes.GUARD_BATTERY) {
                    BatteryScreen(onBack = { navController.popBackStack() })
                }
                composable(Routes.GUARD_NOTIFICATIONS) {
                    NotificationsScreen(
                        onBack = { navController.popBackStack() },
                        onOpenPermissions = { navController.navigate(Routes.PERMISSIONS) },
                    )
                }
                composable(Routes.AUTOMATION) {
                    AutomationScreen(onOpenPermissions = { navController.navigate(Routes.PERMISSIONS) })
                }
                composable(Routes.PERMISSIONS) {
                    PermissionsScreen(
                        isOnboarding = false,
                        onBack = { navController.popBackStack() },
                        onOnboardingFinished = {},
                    )
                }
                composable(Routes.SETTINGS) {
                    SettingsScreen(onBack = { navController.popBackStack() })
                }
            }
        }
    }
}

/** Tab-Navigation: Dashboard bleibt unten im Back-Stack, Tab-Zustände werden gesichert. */
private fun navigateTopLevel(navController: NavHostController, route: String, restore: Boolean = true) {
    navController.navigate(route) {
        popUpTo(Routes.DASHBOARD) { saveState = restore }
        launchSingleTop = true
        restoreState = restore
    }
}
