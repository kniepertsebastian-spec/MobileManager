package app.mobilemanager.data.bluetooth

import android.media.audiofx.Equalizer
import android.util.Log
import app.mobilemanager.domain.model.EqProfile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Einfacher globaler Equalizer auf Audio-Session 0 (Gesamtausgabe).
 *
 * Hinweis: Session 0 ist von Android offiziell als veraltet markiert und wird nicht von jedem
 * Gerät unterstützt. Der Effekt bleibt nur aktiv, solange der App-Prozess lebt.
 */
@Singleton
class EqualizerController @Inject constructor() {

    private var equalizer: Equalizer? = null

    /** @return `true` wenn das Profil angewendet werden konnte. */
    @Synchronized
    fun apply(profile: EqProfile): Boolean = try {
        val eq = equalizer ?: Equalizer(PRIORITY, GLOBAL_SESSION).also { equalizer = it }
        val range = eq.bandLevelRange // [min, max] in Millibel
        val bandCount = eq.numberOfBands.toInt()
        for (band in 0 until bandCount) {
            val centerHz = eq.getCenterFreq(band.toShort()) / 1000 // mHz -> Hz
            val profileIndex = EqProfile.centerFrequenciesHz.indices.minBy {
                abs(EqProfile.centerFrequenciesHz[it] - centerHz)
            }
            val level = profile.gainsMilliBel[profileIndex].coerceIn(range[0].toInt(), range[1].toInt())
            eq.setBandLevel(band.toShort(), level.toShort())
        }
        eq.enabled = profile != EqProfile.FLAT
        true
    } catch (e: Exception) {
        Log.w(TAG, "Equalizer nicht verfügbar", e)
        release()
        false
    }

    @Synchronized
    fun release() {
        runCatching { equalizer?.release() }
        equalizer = null
    }

    private companion object {
        const val PRIORITY = 0
        const val GLOBAL_SESSION = 0
        const val TAG = "EqualizerController"
    }
}
