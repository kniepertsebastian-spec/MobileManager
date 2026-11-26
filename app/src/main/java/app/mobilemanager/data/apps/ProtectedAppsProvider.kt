package app.mobilemanager.data.apps

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import app.mobilemanager.data.shizuku.ShizukuManager
import app.mobilemanager.domain.model.ProtectionReason
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ermittelt Apps, die der App-Stopper niemals beenden darf – unabhängig von der Whitelist.
 */
@Singleton
class ProtectedAppsProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val pm: PackageManager get() = context.packageManager

    fun resolve(): Map<String, ProtectionReason> = buildMap {
        // Reihenfolge = Priorität des angezeigten Grundes
        MESSENGERS.forEach { put(it, ProtectionReason.MESSENGER) }
        put(ShizukuManager.SHIZUKU_PACKAGE, ProtectionReason.SHIZUKU)
        defaultKeyboard()?.let { put(it, ProtectionReason.KEYBOARD) }
        alarmApps().forEach { put(it, ProtectionReason.ALARM) }
        defaultSms()?.let { put(it, ProtectionReason.SMS) }
        defaultDialer()?.let { put(it, ProtectionReason.DIALER) }
        defaultLauncher()?.let { put(it, ProtectionReason.LAUNCHER) }
        put(context.packageName, ProtectionReason.SELF)
    }

    private fun defaultLauncher(): String? = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
            ?.activityInfo?.packageName
            ?.takeIf { it != "android" } // "android" = Auswahldialog, kein Standard gesetzt
    }.getOrNull()

    private fun defaultDialer(): String? = runCatching {
        context.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
    }.getOrNull()

    private fun defaultSms(): String? = runCatching {
        Telephony.Sms.getDefaultSmsPackage(context)
    }.getOrNull()

    private fun alarmApps(): Set<String> = runCatching {
        listOf(AlarmClock.ACTION_SET_ALARM, AlarmClock.ACTION_SHOW_ALARMS).flatMap { action ->
            pm.queryIntentActivities(Intent(action), PackageManager.ResolveInfoFlags.of(0))
                .map { it.activityInfo.packageName }
        }.toSet()
    }.getOrDefault(emptySet())

    private fun defaultKeyboard(): String? = runCatching {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?.substringBefore('/')
    }.getOrNull()

    companion object {
        val MESSENGERS = setOf(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.thoughtcrime.securesms", // Signal
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "ch.threema.app",
            "ch.threema.app.libre",
            "com.google.android.apps.messaging",
            "com.facebook.orca",
            "im.vector.app", // Element
            "com.discord",
            "com.microsoft.teams",
            "com.Slack",
        )
    }
}
