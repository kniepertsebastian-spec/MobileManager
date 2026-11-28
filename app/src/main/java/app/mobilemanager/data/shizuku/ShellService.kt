package app.mobilemanager.data.shizuku

import android.content.Context
import android.os.Binder
import androidx.annotation.Keep
import app.mobilemanager.domain.extras.ShellCommandPolicy
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * Shizuku-UserService. Läuft in einem eigenen Prozess mit Shell-Rechten (uid 2000),
 * daher darf er `am force-stop` ausführen. Wird von Shizuku (ab v13) per Reflection über den
 * Konstruktor mit [Context] erzeugt – der Kontext gehört zu MobileManager und dient der
 * Aufrufer-Prüfung: Nur MobileManager selbst darf Befehle auslösen.
 */
@Keep
class ShellService(private val context: Context) : IShellService.Stub() {

    private val trustedUids = ConcurrentHashMap<Int, Boolean>()

    /** Prüft den Binder-Aufrufer; muss im Binder-Thread laufen, bevor etwas ausgeführt wird. */
    private fun callerTrusted(): Boolean {
        val uid = Binder.getCallingUid()
        return trustedUids.getOrPut(uid) {
            ShellCommandPolicy.isTrustedCaller(uid, context.packageName) { context.packageManager.getPackagesForUid(it) }
        }
    }

    override fun destroy() {
        // Shizuku selbst (root/shell) oder MobileManager dürfen den Prozess beenden.
        val uid = Binder.getCallingUid()
        if (uid != ROOT_UID && uid != SHELL_UID && !callerTrusted()) return
        exitProcess(0)
    }

    override fun forceStop(packageName: String): Int {
        if (!callerTrusted()) return DENIED
        // Paketnamen strikt validieren – nie ungeprüfte Strings an die Shell geben.
        if (!ShellCommandPolicy.isPackage(packageName)) return DENIED
        return try {
            val process = ProcessBuilder("am", "force-stop", packageName)
                .redirectErrorStream(true)
                .start()
            process.inputStream.bufferedReader().use { it.readText().take(MAX_OUTPUT) }
            if (process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) process.exitValue() else {
                process.destroyForcibly()
                TIMEOUT
            }
        } catch (e: Exception) {
            -1
        }
    }

    /** Kein Shell-Interpreter: Argumente gehen einzeln an ProcessBuilder, nur Befehle aus der Erlaubtliste. */
    override fun exec(argv: Array<String>?): String {
        if (!callerTrusted()) return "$DENIED\nAufrufer nicht erlaubt"
        val args = argv?.toList().orEmpty()
        if (!ShellCommandPolicy.isAllowed(args)) return "$DENIED\nBefehl nicht erlaubt"
        return try {
            val process = ProcessBuilder(args).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().use { it.readText().take(MAX_OUTPUT) }
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            "${if (finished) process.exitValue() else TIMEOUT}\n$output"
        } catch (e: Exception) {
            "-1\n${e.javaClass.simpleName}"
        }
    }

    private companion object {
        const val MAX_OUTPUT = 512 * 1024
        const val TIMEOUT_SECONDS = 60L
        const val DENIED = -2
        const val TIMEOUT = -3
        const val ROOT_UID = 0
        const val SHELL_UID = 2000
    }
}
