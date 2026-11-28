package app.mobilemanager.ui.external

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import app.mobilemanager.MainActivity
import app.mobilemanager.data.extras.ExtrasSettingsRepository
import app.mobilemanager.data.network.NetworkPolicyController
import app.mobilemanager.data.network.NetworkPolicyRepository
import app.mobilemanager.data.power.CaffeineService
import app.mobilemanager.data.protection.ProtectionRepository
import app.mobilemanager.data.tracker.TrackerSettingsRepository
import app.mobilemanager.data.tracker.TrackerVpnController
import app.mobilemanager.data.work.WorkScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Phase 4.6: Intent- & Shortcut-Schnittstelle für Tasker, NFC-Tags und Launcher-Shortcuts.
 * Unsichtbar; führt die Aktion aus und beendet sich. Ohne „Externe Steuerung erlauben“ passiert nichts –
 * sonst könnte jede App z. B. den Tracker-Blocker ausschalten.
 *
 * Beispiel: `am start -a app.mobilemanager.action.TRACKER_TOGGLE -n app.mobilemanager/.ui.external.ActionActivity`
 */
@AndroidEntryPoint
class ActionActivity : ComponentActivity() {

    @Inject lateinit var protection: ProtectionRepository
    @Inject lateinit var tracker: TrackerSettingsRepository
    @Inject lateinit var vpn: TrackerVpnController
    @Inject lateinit var extras: ExtrasSettingsRepository
    @Inject lateinit var network: NetworkPolicyRepository
    @Inject lateinit var networkController: NetworkPolicyController
    @Inject lateinit var workScheduler: WorkScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Nur vollständige, bekannte Aktionen – keine Extras, keine Kurzformen.
        val action = intent?.action?.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf { it in ACTIONS }
        lifecycleScope.launch {
            val message = if (!protection.current().externalControl) {
                "Externe Steuerung ist in MobileManager ausgeschaltet (Selbstschutz → Externe Steuerung)"
            } else {
                runCatching { perform(action) }.getOrElse { "Aktion fehlgeschlagen" }
            }
            message?.let { Toast.makeText(applicationContext, it, Toast.LENGTH_SHORT).show() }
            finish()
        }
    }

    /** @return kurze Rückmeldung für den Toast. */
    private suspend fun perform(action: String?): String? = when (action) {
        "TRACKER_ON" -> setTracker(true)
        "TRACKER_OFF" -> setTracker(false)
        "TRACKER_TOGGLE" -> setTracker(!tracker.current().enabled)
        "FOCUS_ON" -> extras.update { it.copy(focusEnabled = true) }.let { "Fokus-Bremse an" }
        "FOCUS_OFF" -> extras.update { it.copy(focusEnabled = false) }.let { "Fokus-Bremse aus" }
        "FIREWALL_ON", "FIREWALL_OFF", "FIREWALL_TOGGLE" -> {
            val target = when (action) {
                "FIREWALL_ON" -> true
                "FIREWALL_OFF" -> false
                else -> !network.current().firewallEnabled
            }
            network.update { it.copy(firewallEnabled = target) }
            val error = networkController.apply()
            error ?: if (target) "Firewall an" else "Firewall aus"
        }
        "STOP_APPS" -> workScheduler.runAppStopperNow().let { "Inaktive Apps werden beendet" }
        "CAFFEINE" -> if (CaffeineService.isActive) {
            CaffeineService.stop(this)
            "Koffein-Modus aus"
        } else if (CaffeineService.start(this, protection.current().caffeineMinutes)) {
            "Koffein-Modus an"
        } else {
            "Koffein braucht die Berechtigung „Über anderen Apps einblenden“"
        }
        else -> "Unbekannte Aktion"
    }

    private suspend fun setTracker(enabled: Boolean): String {
        if (!enabled) {
            tracker.setEnabled(false)
            vpn.stop()
            return "Tracker-Blocker aus"
        }
        if (vpn.prepareIntent() != null || vpn.otherVpnActive()) {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DESTINATION_TRACKER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return "Bitte VPN in MobileManager freigeben"
        }
        tracker.setEnabled(true)
        vpn.start()
        return "Tracker-Blocker an"
    }

    companion object {
        const val PREFIX = "app.mobilemanager.action."
        val ACTIONS = listOf(
            "TRACKER_ON", "TRACKER_OFF", "TRACKER_TOGGLE", "FOCUS_ON", "FOCUS_OFF",
            "FIREWALL_ON", "FIREWALL_OFF", "FIREWALL_TOGGLE", "STOP_APPS", "CAFFEINE",
        )
    }
}
