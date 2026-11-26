package app.mobilemanager.domain.extras

import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

/** Meilenstein 3.4.2: Lautstärke pro App als reine Zustandsmaschine. */
object PerAppVolumePolicy {

    data class Decision(
        /** Neue Lautstärke (Index) oder `null` = nichts ändern. */
        val targetIndex: Int?,
        /** Lautstärke vor der ersten geregelten App (zum Zurücksetzen), `null` = keine Regel aktiv. */
        val savedIndex: Int?,
    )

    fun percentToIndex(percent: Int, min: Int, max: Int): Int =
        (min + (max - min) * percent.coerceIn(0, 100) / 100.0).roundToInt().coerceIn(min, max)

    fun indexToPercent(index: Int, min: Int, max: Int): Int =
        if (max <= min) 0 else ((index - min) * 100.0 / (max - min)).roundToInt().coerceIn(0, 100)

    /**
     * Wechsel in eine App mit Regel → deren Lautstärke setzen und den vorherigen Wert merken
     * (beim Wechsel zwischen zwei geregelten Apps bleibt der ursprüngliche Wert gemerkt).
     * Wechsel in eine App ohne Regel → gemerkten Wert wiederherstellen.
     */
    fun onForeground(packageName: String, rules: Map<String, Int>, savedIndex: Int?, currentIndex: Int, min: Int, max: Int): Decision {
        val percent = rules[packageName]
        return when {
            percent != null -> Decision(percentToIndex(percent, min, max), savedIndex ?: currentIndex)
            savedIndex != null -> Decision(savedIndex, null)
            else -> Decision(null, null)
        }
    }
}

/** Meilenstein 3.4.1: Wann legt die Fokus-Bremse ihr Overlay über eine App? */
object FocusBrakePolicy {
    fun shouldBrake(packageName: String, previousPackage: String?, flagged: Set<String>, graceUntil: Long?, now: Long): Boolean =
        packageName in flagged && packageName != previousPackage && (graceUntil ?: 0L) <= now
}

/** Welche Vordergrund-Wechsel zählen? Benachrichtigungsleiste, Tastatur und eigene Overlays nicht. */
object ForegroundFilter {
    val SYSTEM_IGNORED = setOf("com.android.systemui", "android")

    fun isRelevant(packageName: String, className: String?, ownPackage: String, ignored: Set<String>): Boolean = when {
        packageName in SYSTEM_IGNORED || packageName in ignored -> false
        // Eigene Overlays (Fokus-Bremse) sind keine App-Wechsel – eigene Activities (z. B. Launcher) schon.
        packageName == ownPackage -> className?.endsWith("Activity") == true
        else -> true
    }
}

/** Meilenstein 3.4.3: Sinuston für den Lautsprecher-Reiniger, mit weichem Ein-/Ausblenden gegen Knackser. */
object SineWave {
    fun generate(frequency: Double, seconds: Int, sampleRate: Int, amplitude: Double = 0.95, fadeMillis: Int = 200): ShortArray {
        val total = seconds * sampleRate
        val fade = (sampleRate * fadeMillis / 1000).coerceAtMost(total / 2)
        return ShortArray(total) { i ->
            val envelope = when {
                fade == 0 -> 1.0
                i < fade -> i.toDouble() / fade
                i >= total - fade -> (total - 1 - i).toDouble() / fade
                else -> 1.0
            }
            (sin(2 * PI * frequency * i / sampleRate) * amplitude * envelope * Short.MAX_VALUE).roundToInt().toShort()
        }
    }
}
