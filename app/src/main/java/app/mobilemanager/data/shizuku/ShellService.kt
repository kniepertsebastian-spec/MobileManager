package app.mobilemanager.data.shizuku

import androidx.annotation.Keep
import app.mobilemanager.domain.extras.ShellCommandPolicy
import java.util.concurrent.TimeUnit
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
        if (!ShellCommandPolicy.isPackage(packageName)) return -2
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

    /** Kein Shell-Interpreter: Argumente gehen einzeln an ProcessBuilder, nur Befehle aus der Erlaubtliste. */
    override fun exec(argv: Array<String>): String {
        val args = argv.toList()
        if (!ShellCommandPolicy.isAllowed(args)) return "-2\nBefehl nicht erlaubt"
        return try {
            val process = ProcessBuilder(args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText().take(MAX_OUTPUT) }
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            "${if (finished) process.exitValue() else -3}\n$output"
        } catch (e: Exception) {
            "-1\n${e.message}"
        }
    }

    private companion object {
        const val MAX_OUTPUT = 512 * 1024
        const val TIMEOUT_SECONDS = 60L
    }
}
