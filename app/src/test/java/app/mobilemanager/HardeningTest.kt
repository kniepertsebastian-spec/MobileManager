package app.mobilemanager

import app.mobilemanager.domain.guard.SensitiveApps
import app.mobilemanager.domain.model.AlertChannelType
import app.mobilemanager.domain.model.AlertConfig
import app.mobilemanager.domain.model.AlertUrls
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Roadmap Phase 2: ntfy-URL-Prüfung und Ausschlussliste für das Benachrichtigungs-Archiv. */
class HardeningTest {

    @Test
    fun onlyHttpsNtfyUrlsAreAccepted() {
        assertTrue(AlertUrls.isHttpsUrl("https://ntfy.sh"))
        assertTrue(AlertUrls.isHttpsUrl("  https://push.example.org:8443/ "))
        assertFalse(AlertUrls.isHttpsUrl("http://ntfy.sh"))
        assertFalse(AlertUrls.isHttpsUrl("ntfy.sh"))
        assertFalse(AlertUrls.isHttpsUrl("https://"))
        assertFalse(AlertUrls.isHttpsUrl("https://user:pass@ntfy.sh"))
        assertFalse(AlertUrls.isHttpsUrl("https://exa mple.org"))
    }

    @Test
    fun insecureNtfyConfigIsNotSavable() {
        val ntfy = AlertConfig(channel = AlertChannelType.NTFY, ntfyTopic = "t")
        assertTrue(ntfy.copy(ntfyServer = "https://ntfy.sh").isSavable)
        assertTrue(ntfy.copy(ntfyServer = "https://ntfy.sh").isConfigured)
        assertFalse(ntfy.copy(ntfyServer = "http://ntfy.sh").isSavable)
        assertFalse(ntfy.copy(ntfyServer = "http://ntfy.sh").isConfigured)
        // Andere Kanäle sind von der ntfy-URL unabhängig.
        assertTrue(AlertConfig(channel = AlertChannelType.TELEGRAM, ntfyServer = "http://x").isSavable)
    }

    @Test
    fun sensitiveAppsAreRecognized() {
        assertTrue(SensitiveApps.isSensitive("com.google.android.apps.authenticator2"))
        assertTrue(SensitiveApps.isSensitive("com.x8bit.bitwarden"))
        assertTrue(SensitiveApps.isSensitive("de.fiducia.smartphone.android.banking.vr"))
        assertTrue(SensitiveApps.isSensitive("com.example.mybank.app"))
        assertTrue(SensitiveApps.isSensitive("de.sparkasse.pushtan"))
        assertFalse(SensitiveApps.isSensitive("org.thoughtcrime.securesms"))
        assertFalse(SensitiveApps.isSensitive("com.whatsapp"))
    }

    @Test
    fun exclusionRespectsSwitchAndCustomList() {
        assertTrue(SensitiveApps.excluded("com.x8bit.bitwarden", excludeSensitive = true, customExcluded = emptySet()))
        assertFalse(SensitiveApps.excluded("com.x8bit.bitwarden", excludeSensitive = false, customExcluded = emptySet()))
        assertTrue(SensitiveApps.excluded("com.whatsapp", excludeSensitive = false, customExcluded = setOf("com.whatsapp")))
    }
}
