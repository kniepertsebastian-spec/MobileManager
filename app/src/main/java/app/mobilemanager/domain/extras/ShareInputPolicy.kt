package app.mobilemanager.domain.extras

/**
 * Prüft geteilte URIs, bevor der EXIF-Entferner sie liest. Nur fremde `content://`-URIs sind erlaubt:
 * `file://` oder URIs auf MobileManagers eigene Provider könnten sonst private App-Dateien
 * (Datenbanken, Schlüssel) über das Teilen-Menü nach außen tragen.
 */
object ShareInputPolicy {
    const val MAX_ITEMS = 30
    const val MAX_BYTES = 64L * 1024 * 1024
    /** Größere Bilder werden beim Neu-Kodieren herunterskaliert (Schutz vor Speicherüberlauf). */
    const val MAX_PIXELS = 48_000_000L

    private val LOSSLESS = setOf("image/jpeg", "image/png", "image/webp")

    fun isAcceptable(scheme: String?, authority: String?, ownPackage: String): Boolean {
        if (!scheme.equals("content", ignoreCase = true)) return false
        val auth = authority?.substringAfterLast('@')?.lowercase() ?: return false
        if (auth.isBlank()) return false
        val own = ownPackage.lowercase()
        return auth != own && !auth.startsWith("$own.")
    }

    /** Nur Bild-Typen; alles andere (auch fehlender Typ) wird abgewiesen. */
    fun isImageType(mimeType: String?): Boolean = mimeType?.lowercase()?.startsWith("image/") == true

    fun isLossless(mimeType: String?): Boolean = mimeType?.lowercase() in LOSSLESS

    fun withinSizeLimit(declaredBytes: Long?): Boolean = declaredBytes == null || declaredBytes in 0..MAX_BYTES

    /** Ganzzahliger Teiler, damit width*height unter [MAX_PIXELS] bleibt. */
    fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width.toLong() / sample * (height.toLong() / sample) > MAX_PIXELS) sample *= 2
        return sample
    }

    fun safeBaseName(displayName: String?, index: Int): String =
        displayName?.substringBeforeLast('.')?.replace(Regex("[^A-Za-z0-9_-]"), "_")?.take(40)?.takeIf { it.isNotBlank() }
            ?: "bild_$index"
}
