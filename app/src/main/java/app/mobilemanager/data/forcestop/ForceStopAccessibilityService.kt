package app.mobilemanager.data.forcestop

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Optionaler Fallback ohne Shizuku: Tippt auf der geöffneten App-Info-Seite auf
 * „Beenden erzwingen“ und bestätigt den Dialog mit „OK“.
 *
 * Der Dienst ist auf com.android.settings beschränkt (siehe accessibility_service_config.xml)
 * und reagiert nur, solange ein Auftrag im [AccessibilityForceStopCoordinator] aktiv ist.
 */
class ForceStopAccessibilityService : AccessibilityService() {

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Doppelte Absicherung zur packageNames-Einschränkung in der Konfiguration.
        if (event?.packageName?.toString() != SETTINGS_PACKAGE) return
        val request = AccessibilityForceStopCoordinator.active() ?: return
        val root = rootInActiveWindow ?: return

        if (!request.forceStopClicked) {
            val button = findClickable(root, FORCE_STOP_IDS, FORCE_STOP_TEXTS) ?: return
            if (!button.isEnabled) {
                // Bereits gestoppt – "Beenden erzwingen" ist ausgegraut.
                done(request, success = true)
                return
            }
            if (button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                request.forceStopClicked = true
            }
        } else {
            val ok = findClickable(root, CONFIRM_IDS, CONFIRM_TEXTS) ?: return
            if (ok.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                done(request, success = true)
            }
        }
    }

    private fun done(request: AccessibilityForceStopCoordinator.Request, success: Boolean) {
        AccessibilityForceStopCoordinator.finish(request, success)
        performGlobalAction(GLOBAL_ACTION_BACK)
    }

    private fun findClickable(
        root: AccessibilityNodeInfo,
        ids: List<String>,
        texts: List<String>,
    ): AccessibilityNodeInfo? {
        ids.forEach { id ->
            root.findAccessibilityNodeInfosByViewId(id).firstOrNull()?.let { return clickableAncestor(it) }
        }
        texts.forEach { text ->
            root.findAccessibilityNodeInfosByText(text)
                .firstOrNull { it.text?.toString().equals(text, ignoreCase = true) }
                ?.let { return clickableAncestor(it) }
        }
        return null
    }

    private fun clickableAncestor(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var current: AccessibilityNodeInfo? = node
        while (current != null && !current.isClickable) current = current.parent
        return current ?: node
    }

    override fun onInterrupt() = Unit

    private companion object {
        const val SETTINGS_PACKAGE = "com.android.settings"
        val FORCE_STOP_IDS = listOf(
            "com.android.settings:id/force_stop_button",
            "com.android.settings:id/button3",
        )
        val FORCE_STOP_TEXTS = listOf("Beenden erzwingen", "Force stop", "Stopp erzwingen")
        val CONFIRM_IDS = listOf("android:id/button1")
        val CONFIRM_TEXTS = listOf("OK", "Beenden erzwingen", "Force stop")
    }
}
