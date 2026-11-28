package app.mobilemanager.launcher.domain

import androidx.annotation.StringRes
import app.mobilemanager.launcher.R

/** Wo ein Element liegt. */
enum class Container(val code: Int) {
    HOME(0), DOCK(1), FOLDER(2);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code } ?: HOME
    }
}

/** Ein Element im gespeicherten Layout eines Profils (App oder Ordner). */
data class LayoutItem(
    val id: Long,
    val container: Container,
    val page: Int,
    val position: Int,
    /** Bei Apps: `paket/klasse#nutzerSerial`, bei Ordnern `null`. */
    val componentKey: String?,
    /** Bei Ordnern der Name. */
    val title: String? = null,
    /** Ordner-ID, wenn [container] == FOLDER. */
    val parentId: Long? = null,
) {
    val isFolder get() = componentKey == null
}

enum class ProfileKind { STANDARD, WORK, CAR, FOCUS, CUSTOM }

data class LauncherProfile(
    val id: Long,
    val name: String,
    val kind: ProfileKind,
    val hiddenCategories: Set<AppCategory>,
    val largeIcons: Boolean,
)

enum class RuleType(@StringRes val labelRes: Int) {
    BLUETOOTH(R.string.launcher_rule_type_bluetooth),
    WIFI(R.string.launcher_rule_type_wifi),
    TIME(R.string.launcher_rule_type_time),
}

/**
 * Regel für den automatischen Profilwechsel.
 * [daysMask]: Bit 0 = Montag … Bit 6 = Sonntag. [startMinutes]/[endMinutes] in Minuten ab Mitternacht.
 */
data class ProfileRule(
    val id: Long,
    val profileId: Long,
    val type: RuleType,
    val value: String = "",
    val label: String = "",
    val daysMask: Int = WEEKDAYS,
    val startMinutes: Int = 8 * 60,
    val endMinutes: Int = 17 * 60,
    val enabled: Boolean = true,
) {
    companion object {
        const val WEEKDAYS = 0b0011111
        const val ALL_DAYS = 0b1111111
    }
}
