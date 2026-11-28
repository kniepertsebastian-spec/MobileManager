package app.mobilemanager.data.apps

import android.content.Context
import android.content.pm.PackageManager
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gemeinsamer Cache für App-Icons und -Namen (Listen, Overlays, Dialoge). Geladen wird immer auf
 * [Dispatchers.IO] – der PackageManager liest dafür APKs und Ressourcen von der Platte.
 */
object AppVisuals {

    /** Größe in Bytes (Bitmap-Speicher), damit große Listen den Heap nicht fluten. */
    private val icons = object : LruCache<String, ImageBitmap>(ICON_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }
    private val labels = LruCache<String, String>(LABEL_CACHE_ENTRIES)

    /** Nur aus dem Cache – für den ersten Frame ohne Platzhalter-Flackern. */
    fun peekIcon(packageName: String, sizePx: Int): ImageBitmap? = icons.get(key(packageName, sizePx))

    suspend fun icon(context: Context, packageName: String, sizePx: Int): ImageBitmap? {
        val key = key(packageName, sizePx)
        icons.get(key)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(packageName).toBitmap(sizePx, sizePx).asImageBitmap() }
                .getOrNull()
                ?.also { icons.put(key, it) }
        }
    }

    suspend fun label(context: Context, packageName: String): String {
        labels.get(packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            val pm = context.packageManager
            runCatching { pm.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0)).loadLabel(pm).toString() }
                .getOrNull()
                ?.also { labels.put(packageName, it) }
                ?: packageName
        }
    }

    /** Nach Update/Deinstallation einer App veraltete Einträge verwerfen. */
    fun invalidate(packageName: String) {
        labels.remove(packageName)
        icons.snapshot().keys.filter { it.startsWith("$packageName@") }.forEach(icons::remove)
    }

    private fun key(packageName: String, sizePx: Int) = "$packageName@$sizePx"

    private const val ICON_CACHE_BYTES = 8 * 1024 * 1024
    private const val LABEL_CACHE_ENTRIES = 500
}
