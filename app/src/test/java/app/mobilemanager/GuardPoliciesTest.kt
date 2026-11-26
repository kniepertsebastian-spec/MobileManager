package app.mobilemanager

import app.mobilemanager.domain.guard.AuditPolicy
import app.mobilemanager.domain.guard.ChargePolicy
import app.mobilemanager.domain.guard.CooldownPolicy
import app.mobilemanager.domain.guard.DigestPolicy
import app.mobilemanager.domain.guard.FailedUnlockPolicy
import app.mobilemanager.domain.guard.HeatPolicy
import app.mobilemanager.domain.guard.NightReportPolicy
import app.mobilemanager.domain.guard.Schedule
import app.mobilemanager.domain.guard.SimPolicy
import app.mobilemanager.domain.guard.SimSnapshot
import app.mobilemanager.domain.model.AuditRisk
import app.mobilemanager.domain.model.CriticalPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

class GuardPoliciesTest {

    @Test
    fun `failed unlock fires at threshold and its multiples only`() {
        val fired = (1..10).filter { FailedUnlockPolicy.shouldAlert(it, 3) }
        assertEquals(listOf(3, 6, 9), fired)
        assertFalse(FailedUnlockPolicy.shouldAlert(5, 0))
    }

    @Test
    fun `cooldown blocks repeated alerts within window`() {
        val now = TimeUnit.HOURS.toMillis(10)
        assertTrue(CooldownPolicy.canSend(null, now, 10))
        assertFalse(CooldownPolicy.canSend(now - TimeUnit.MINUTES.toMillis(5), now, 10))
        assertTrue(CooldownPolicy.canSend(now - TimeUnit.MINUTES.toMillis(10), now, 10))
        assertTrue(CooldownPolicy.canSend(now, now, 0))
    }

    @Test
    fun `heat alert fires once and re-arms only after cooling below hysteresis`() {
        var alerted = false
        val fires = listOf(40f, 42.5f, 43f, 41f, 39.5f, 38.9f, 42f).map { temp ->
            val r = HeatPolicy.evaluate(temp, threshold = 42f, alreadyAlerted = alerted)
            alerted = r.alerted
            r.fire
        }
        // 42,5 → Alarm; 43/41/39,5 → still (noch über 39 °C); 38,9 → scharf; 42 → erneut Alarm
        assertEquals(listOf(false, true, false, false, false, false, true), fires)
    }

    @Test
    fun `charge alert fires once per charging session`() {
        var alerted = false
        fun step(level: Int, plugged: Boolean): Boolean {
            val r = ChargePolicy.evaluate(level, plugged, limit = 80, alreadyAlerted = alerted)
            alerted = r.alerted
            return r.fire
        }
        assertFalse(step(79, true))
        assertTrue(step(80, true))
        assertFalse(step(81, true))
        assertFalse(step(82, false)) // abgesteckt → zurückgesetzt
        assertTrue(step(82, true))
    }

    @Test
    fun `sim change detection ignores transient states`() {
        val baseline = SimSnapshot(SimSnapshot.STATE_READY, carrierId = 1911, subscriptionId = 1)
        val loading = SimSnapshot(6, SimSnapshot.UNKNOWN_CARRIER, -1)
        val removed = SimSnapshot(SimSnapshot.STATE_ABSENT, SimSnapshot.UNKNOWN_CARRIER, -1)
        val swapped = SimSnapshot(SimSnapshot.STATE_READY, carrierId = 2032, subscriptionId = 2)

        assertFalse(SimPolicy.isSuspicious(baseline, baseline))
        assertFalse(SimPolicy.isSuspicious(baseline, loading))
        assertTrue(SimPolicy.isSuspicious(baseline, removed))
        assertTrue(SimPolicy.isSuspicious(baseline, swapped))
        assertEquals(baseline, SimSnapshot.decode(baseline.encode()))
        assertNull(SimSnapshot.decode("kaputt"))
    }

    @Test
    fun `digest matches apps and keywords case-insensitively`() {
        val apps = setOf("com.shop")
        val keywords = setOf("Rabatt", " ")
        assertTrue(DigestPolicy.matches("com.shop", "Hallo", "", apps, keywords))
        assertTrue(DigestPolicy.matches("com.news", "Heute 20 % RABATT", "", apps, keywords))
        assertFalse(DigestPolicy.matches("com.whatsapp", "Anna", "Bis gleich", apps, keywords))
    }

    @Test
    fun `night report is skipped when charged overnight`() {
        val start = 0L
        val end = TimeUnit.HOURS.toMillis(8)
        val report = NightReportPolicy.build(90, start, 86, end, isPluggedNow = false)!!
        assertEquals(4, report.dropPercent)
        assertEquals(8.0, report.hours, 0.001)
        assertNull(NightReportPolicy.build(60, start, 100, end, isPluggedNow = false))
        assertNull(NightReportPolicy.build(90, start, 86, end, isPluggedNow = true))
    }

    @Test
    fun `audit marks unused apps with critical permissions as high risk`() {
        val now = TimeUnit.DAYS.toMillis(100)
        val recent = now - TimeUnit.DAYS.toMillis(1)
        val old = now - TimeUnit.DAYS.toMillis(45)
        val camera = listOf(CriticalPermission.CAMERA)
        assertNull(AuditPolicy.classify(emptyList(), old, now))
        assertEquals(AuditRisk.HIGH, AuditPolicy.classify(camera, old, now))
        assertEquals(AuditRisk.HIGH, AuditPolicy.classify(camera, null, now))
        assertEquals(AuditRisk.LOW, AuditPolicy.classify(camera, recent, now))
        assertEquals(
            AuditRisk.MEDIUM,
            AuditPolicy.classify(listOf(CriticalPermission.BACKGROUND_LOCATION), recent, now),
        )
    }

    @Test
    fun `next occurrence picks the closest future time`() {
        val now = LocalDateTime.of(2026, 11, 26, 13, 0)
        val times = setOf(12 * 60, 19 * 60)
        assertEquals(LocalDateTime.of(2026, 11, 26, 19, 0), Schedule.nextOccurrence(times, now))
        assertEquals(
            LocalDateTime.of(2026, 11, 27, 12, 0),
            Schedule.nextOccurrence(times, LocalDateTime.of(2026, 11, 26, 19, 0)),
        )
        assertNull(Schedule.nextOccurrence(emptySet(), now))
        assertEquals("07:05", Schedule.format(7 * 60 + 5))
    }
}
