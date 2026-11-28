package app.mobilemanager.data.work

import androidx.work.ListenableWorker.Result
import kotlinx.coroutines.CancellationException
import java.io.IOException

/**
 * Einheitliche Fehlerbehandlung für alle Worker: Ein entzogenes Recht ([SecurityException]) ist dauerhaft →
 * `failure()`, ein I/O-Fehler meist vorübergehend → `retry()` (höchstens [maxRetries]-mal). Abbrüche durch
 * WorkManager werden durchgereicht, sonstige Fehler beenden den Lauf ohne Absturz des Prozesses.
 */
internal inline fun guardedWork(runAttemptCount: Int, maxRetries: Int = 3, block: () -> Result): Result = try {
    block()
} catch (e: CancellationException) {
    throw e
} catch (_: SecurityException) {
    Result.failure()
} catch (_: IOException) {
    if (runAttemptCount < maxRetries) Result.retry() else Result.failure()
} catch (_: Exception) {
    Result.failure()
}
