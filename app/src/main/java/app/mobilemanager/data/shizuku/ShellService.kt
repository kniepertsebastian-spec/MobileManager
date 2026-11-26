package app.mobilemanager.data.shizuku

import androidx.annotation.Keep
import kotlin.system.exitProcess

/**
 * Shizuku-UserService. Läuft in einem eigenen Prozess mit Shell-Rechten (uid 2000),
 * daher darf er `am force-stop` ausführen. Wird von Shizuku per Reflection
 * über den parameterlosen Konstruktor erzeugt.
 */
@Keep
class ShellService() : IShellService.Stub() {

    override fun destroy() {
        exitProcess(0)
    }

    override fun forceStop(packageName: String): Int {
        // Paketnamen strikt validieren – nie ungeprüfte Strings an die Shell geben.
        if (!PACKAGE_NAME_REGEX.matches(packageName)) return -2
        return try {
            val process = ProcessBuilder("am", "force-stop", packageName)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()
        } catch (e: Exception) {
            -1
        }
    }

    private companion object {
        val PACKAGE_NAME_REGEX = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    }
}
