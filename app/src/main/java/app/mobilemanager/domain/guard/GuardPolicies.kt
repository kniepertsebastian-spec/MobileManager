package app.mobilemanager.domain.guard

import app.mobilemanager.domain.model.AuditRisk
import app.mobilemanager.domain.model.CriticalPermission
import app.mobilemanager.domain.model.NightReport
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/*
 * Reine Entscheidungslogik der Wächter – ohne Android-Abhängigkeiten und daher unit-testbar.
 */

/** Sperrzeit pro Ereignistyp, damit man sich beim eigenen Vertippen nicht selbst zuspammt. */
object CooldownPolicy {
    fun canSend(lastSentAt: Long?, now: Long, cooldownMinutes: Int): Boolean =
        lastSentAt == null || cooldownMinutes <= 0 || now - lastSentAt >= TimeUnit.MINUTES.toMillis(cooldownMinutes.toLong())
}

object FailedUnlockPolicy {
    /** Löst beim Erreichen des Schwellenwerts aus und danach bei jedem weiteren Vielfachen (3, 6, 9 …). */
    fun shouldAlert(failedAttempts: Int, threshold: Int): Boolean =
        threshold > 0 && failedAttempts >= threshold && failedAttempts % threshold == 0
}

/** Ergebnis einer Hysterese-Prüfung: [fire] = jetzt warnen, [alerted] = neuer gespeicherter Zustand. */
data class HysteresisResult(val fire: Boolean, val alerted: Boolean)

object HeatPolicy {
    /** Abstand zwischen Alarm- und Rücksetz-Schwelle (z. B. 42 °C → erst unter 39 °C erneut scharf). */
    const val HYSTERESIS_CELSIUS = 3f

    fun evaluate(temperature: Float, threshold: Float, alreadyAlerted: Boolean): HysteresisResult = when {
        alreadyAlerted -> HysteresisResult(fire = false, alerted = temperature >= threshold - HYSTERESIS_CELSIUS)
        temperature >= threshold -> HysteresisResult(fire = true, alerted = true)
        else -> HysteresisResult(fire = false, alerted = false)
    }
}

object ChargePolicy {
    /** Nach dem Hinweis erst wieder scharf, wenn der Ladestand um so viele Prozent gefallen ist. */
    const val HYSTERESIS_PERCENT = 5

    fun evaluate(level: Int, isPlugged: Boolean, limit: Int, alreadyAlerted: Boolean): HysteresisResult = when {
        !isPlugged -> HysteresisResult(fire = false, alerted = false)
        alreadyAlerted -> HysteresisResult(fire = false, alerted = level >= limit - HYSTERESIS_PERCENT)
        level >= limit -> HysteresisResult(fire = true, alerted = true)
        else -> HysteresisResult(fire = false, alerted = false)
    }
}

/**
 * SIM-Zustand ohne sensible Kennungen (IMSI/ICCID sind ohne Carrier-Rechte nicht lesbar):
 * SIM-Status, Carrier-ID und Standard-Subscription-ID.
 */
data class SimSnapshot(val state: Int, val carrierId: Int, val subscriptionId: Int) {
    fun encode(): String = "$state|$carrierId|$subscriptionId"

    companion object {
        /** Werte aus TelephonyManager (als Konstanten gespiegelt, damit die Logik testbar bleibt). */
        const val STATE_ABSENT = 1
        const val STATE_READY = 5
        const val UNKNOWN_CARRIER = -1

        fun decode(value: String?): SimSnapshot? {
            val parts = value?.split('|') ?: return null
            if (parts.size != 3) return null
            return SimSnapshot(
                parts[0].toIntOrNull() ?: return null,
                parts[1].toIntOrNull() ?: return null,
                parts[2].toIntOrNull() ?: return null,
            )
        }
    }
}

object SimPolicy {
    /** Übergangszustände (z. B. NOT_READY beim Booten) werden ignoriert. */
    fun isStable(snapshot: SimSnapshot): Boolean =
        snapshot.state == SimSnapshot.STATE_ABSENT ||
            (snapshot.state == SimSnapshot.STATE_READY && snapshot.carrierId != SimSnapshot.UNKNOWN_CARRIER)

    fun isSuspicious(baseline: SimSnapshot, current: SimSnapshot): Boolean {
        if (!isStable(current) || current == baseline) return false
        if (current.state != baseline.state) return true
        return current.carrierId != baseline.carrierId || current.subscriptionId != baseline.subscriptionId
    }
}

object DigestPolicy {
    fun matches(
        packageName: String,
        title: String,
        text: String,
        digestApps: Set<String>,
        keywords: Set<String>,
    ): Boolean = packageName in digestApps || keywords.any { keyword ->
        val k = keyword.trim()
        k.isNotEmpty() && (title.contains(k, ignoreCase = true) || text.contains(k, ignoreCase = true))
    }
}

object NightReportPolicy {
    /** Kein Bericht, wenn zwischendurch geladen wurde (Ladestand gestiegen oder Kabel steckt). */
    fun build(startLevel: Int, startTime: Long, endLevel: Int, endTime: Long, isPluggedNow: Boolean): NightReport? {
        if (isPluggedNow || endLevel > startLevel || endTime <= startTime) return null
        return NightReport(startLevel, endLevel, startTime, endTime)
    }
}

object AuditPolicy {
    const val UNUSED_DAYS = 30L

    fun classify(granted: List<CriticalPermission>, lastUsed: Long?, now: Long): AuditRisk? {
        if (granted.isEmpty()) return null
        val unused = lastUsed == null || now - lastUsed > TimeUnit.DAYS.toMillis(UNUSED_DAYS)
        return when {
            unused -> AuditRisk.HIGH
            CriticalPermission.BACKGROUND_LOCATION in granted || granted.size >= 3 -> AuditRisk.MEDIUM
            else -> AuditRisk.LOW
        }
    }
}

object Schedule {
    /** Nächster Zeitpunkt (strikt nach [now]) für eine der Uhrzeiten [minutesOfDay]. */
    fun nextOccurrence(minutesOfDay: Collection<Int>, now: LocalDateTime): LocalDateTime? {
        if (minutesOfDay.isEmpty()) return null
        val today = now.toLocalDate().atStartOfDay()
        return minutesOfDay.flatMap { m ->
            listOf(today.plusMinutes(m.toLong()), today.plusDays(1).plusMinutes(m.toLong()))
        }.filter { it.isAfter(now) }.minOrNull()
    }

    fun format(minutesOfDay: Int): String = "%02d:%02d".format(minutesOfDay / 60, minutesOfDay % 60)
}
