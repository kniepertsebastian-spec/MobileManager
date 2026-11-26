package app.mobilemanager.launcher

import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import app.mobilemanager.launcher.ui.LauncherActivity
import app.mobilemanager.launcher.ui.LauncherSettingsActivity

/**
 * Öffentliche Schnittstelle des Moduls für die Haupt-App.
 * Der Launcher ist im Manifest deaktiviert und wird erst per Schalter eingeschaltet – im
 * ausgeschalteten Zustand taucht er nicht einmal in der Liste der Startbildschirm-Apps auf.
 */
object LauncherComponents {

    private fun component(context: Context) = ComponentName(context, LauncherActivity::class.java)

    fun isEnabled(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(component(context)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun setEnabled(context: Context, enabled: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            component(context),
            if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
            PackageManager.DONT_KILL_APP,
        )
    }

    fun isDefaultHome(context: Context): Boolean =
        context.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_HOME) == true

    /** System-Dialog „Standard-Startbildschirm-App“ (Fallback: Einstellungsseite). */
    fun requestDefaultHomeIntent(context: Context): Intent {
        val roles = context.getSystemService(RoleManager::class.java)
        return if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
            roles.createRequestRoleIntent(RoleManager.ROLE_HOME)
        } else {
            Intent(Settings.ACTION_HOME_SETTINGS)
        }
    }

    fun settingsIntent(context: Context, screen: String = LauncherSettingsActivity.SCREEN_OVERVIEW): Intent =
        LauncherSettingsActivity.intent(context, screen)
}
