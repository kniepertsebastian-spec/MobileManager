package app.mobilemanager.data.forcestop

import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.atomic.AtomicReference

/**
 * Brücke zwischen [ForceStopEngine] und [ForceStopAccessibilityService]:
 * Der Dienst handelt nur, wenn hier ein vom Nutzer angestoßener Auftrag hinterlegt ist.
 */
object AccessibilityForceStopCoordinator {

    class Request(val packageName: String) {
        val result = CompletableDeferred<Boolean>()
        @Volatile var forceStopClicked = false
    }

    private val current = AtomicReference<Request?>(null)

    fun begin(packageName: String): Request = Request(packageName).also { current.set(it) }

    fun active(): Request? = current.get()

    fun finish(request: Request, success: Boolean) {
        current.compareAndSet(request, null)
        request.result.complete(success)
    }
}
