package app.mobilemanager.util

import android.util.Log
import app.mobilemanager.BuildConfig

/** Warnungen nur im Debug-Build loggen – Release-Builds schreiben nichts ins Logcat (zusätzlich entfernt R8 die Aufrufe). */
fun logW(tag: String, message: String, error: Throwable? = null) {
    if (BuildConfig.DEBUG) Log.w(tag, message, error)
}
