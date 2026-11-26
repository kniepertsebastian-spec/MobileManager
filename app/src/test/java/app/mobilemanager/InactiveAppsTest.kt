package app.mobilemanager

import app.mobilemanager.domain.model.InstalledApp
import app.mobilemanager.domain.model.ProtectionReason
import app.mobilemanager.domain.usecase.GetInactiveAppsUseCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class InactiveAppsTest {

    private val now = 1_000_000_000_000L
    private val threshold = TimeUnit.HOURS.toMillis(24)

    private fun app(lastUsedHoursAgo: Long?, protection: ProtectionReason? = null, whitelisted: Boolean = false) =
        InstalledApp(
            packageName = "com.example",
            label = "Example",
            isSystemApp = false,
            lastUsed = lastUsedHoursAgo?.let { now - TimeUnit.HOURS.toMillis(it) },
            isStopped = false,
            autoProtection = protection,
            isWhitelisted = whitelisted,
        )

    @Test
    fun `app older than threshold is inactive`() {
        assertTrue(GetInactiveAppsUseCase.isInactive(app(30), now, threshold))
    }

    @Test
    fun `recently used app is active`() {
        assertFalse(GetInactiveAppsUseCase.isInactive(app(2), now, threshold))
    }

    @Test
    fun `app without usage data counts as inactive`() {
        assertTrue(GetInactiveAppsUseCase.isInactive(app(null), now, threshold))
    }

    @Test
    fun `auto protection wins over whitelist`() {
        val protectedApp = app(100, ProtectionReason.LAUNCHER, whitelisted = true)
        assertTrue(protectedApp.isProtected)
        assertEquals(ProtectionReason.LAUNCHER, protectedApp.protectionReason)
        assertEquals(ProtectionReason.WHITELIST, app(100, whitelisted = true).protectionReason)
    }
}
