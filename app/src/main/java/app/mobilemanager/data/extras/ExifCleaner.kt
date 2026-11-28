package app.mobilemanager.data.extras

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import app.mobilemanager.domain.extras.ShareInputPolicy
import java.io.File
import java.io.IOException

/**
 * Phase 4.8: entfernt Metadaten aus geteilten Bildern. JPEG/PNG/WebP werden verlustfrei über ExifInterface
 * bereinigt, andere Formate (HEIC …) als JPEG neu kodiert. Alles läuft im privaten Cache `exif_clean/`,
 * den nur der FileProvider (und nur dieses Verzeichnis) freigibt.
 */
class ExifCleaner(private val context: Context) {

    val directory: File get() = File(context.cacheDir, DIR)

    /** Neue Runde: alte bereinigte Kopien verwerfen (die Empfänger-App hatte ihre Zeit). */
    fun freshDirectory(): File = directory.apply { deleteRecursively(); mkdirs() }

    /** Prüft Schema, Herkunft, Typ und Größe. Nur geprüfte URIs werden gelesen. */
    fun accepts(uri: Uri): Boolean {
        if (!ShareInputPolicy.isAcceptable(uri.scheme, uri.authority, context.packageName)) return false
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
        return ShareInputPolicy.isImageType(type) && ShareInputPolicy.withinSizeLimit(declaredSize(uri))
    }

    /** Ohne Herkunftsprüfung – [accepts] muss vorher aufgerufen worden sein (Tests nutzen file://). */
    fun clean(uri: Uri, dir: File, index: Int): File {
        val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            ?: if (uri.scheme == "file") guessType(uri) else null
        val baseName = ShareInputPolicy.safeBaseName(displayName(uri), index)
        if (ShareInputPolicy.isLossless(type)) {
            val ext = when (type) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            val out = File(dir, "${baseName}_$index.$ext")
            try {
                copyLimited(uri, out)
                val exif = ExifInterface(out)
                SENSITIVE_TAGS.forEach { tag -> exif.setAttribute(tag, null) }
                exif.saveAttributes()
            } catch (e: Exception) {
                out.delete()
                throw e
            }
            return out
        }
        // Neu kodieren entfernt sämtliche Metadaten; ImageDecoder wendet die Ausrichtung bereits an.
        val source = if (uri.scheme == "file") ImageDecoder.createSource(File(requireNotNull(uri.path))) else ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.setTargetSampleSize(ShareInputPolicy.sampleSize(info.size.width, info.size.height))
        }
        val out = File(dir, "${baseName}_$index.jpg")
        try {
            out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        } finally {
            bitmap.recycle()
        }
        return out
    }

    private fun copyLimited(uri: Uri, out: File) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("unlesbar")
        input.use { source ->
            out.outputStream().use { sink ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = source.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > ShareInputPolicy.MAX_BYTES) throw IOException("Datei zu groß")
                    sink.write(buffer, 0, read)
                }
            }
        }
    }

    private fun guessType(uri: Uri): String? = when (uri.lastPathSegment?.substringAfterLast('.', "")?.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> null
    }

    private fun declaredSize(uri: Uri): Long? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null
        }
    }.getOrNull()

    private fun displayName(uri: Uri): String? = runCatching {
        if (uri.scheme == "file") return@runCatching uri.lastPathSegment
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    companion object {
        const val DIR = "exif_clean"

        /** Beim App-Start: übrig gebliebene Kopien älter als eine Stunde löschen. */
        fun purgeStale(context: Context, maxAgeMillis: Long = 60 * 60_000L) {
            val now = System.currentTimeMillis()
            File(context.cacheDir, DIR).listFiles()?.forEach { if (now - it.lastModified() > maxAgeMillis) it.delete() }
        }

        val SENSITIVE_TAGS = listOf(
            ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF, ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF, ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_PROCESSING_METHOD,
            ExifInterface.TAG_GPS_AREA_INFORMATION, ExifInterface.TAG_GPS_IMG_DIRECTION, ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
            ExifInterface.TAG_GPS_SPEED, ExifInterface.TAG_GPS_SPEED_REF, ExifInterface.TAG_GPS_TRACK, ExifInterface.TAG_GPS_TRACK_REF,
            ExifInterface.TAG_GPS_DEST_LATITUDE, ExifInterface.TAG_GPS_DEST_LATITUDE_REF, ExifInterface.TAG_GPS_DEST_LONGITUDE,
            ExifInterface.TAG_GPS_DEST_LONGITUDE_REF, ExifInterface.TAG_GPS_DEST_BEARING, ExifInterface.TAG_GPS_DEST_BEARING_REF,
            ExifInterface.TAG_GPS_DEST_DISTANCE, ExifInterface.TAG_GPS_DEST_DISTANCE_REF, ExifInterface.TAG_GPS_MAP_DATUM,
            ExifInterface.TAG_GPS_SATELLITES, ExifInterface.TAG_GPS_STATUS, ExifInterface.TAG_GPS_MEASURE_MODE,
            ExifInterface.TAG_GPS_DOP, ExifInterface.TAG_GPS_DIFFERENTIAL, ExifInterface.TAG_GPS_H_POSITIONING_ERROR,
            ExifInterface.TAG_GPS_VERSION_ID,
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_SOFTWARE,
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_BODY_SERIAL_NUMBER, ExifInterface.TAG_CAMERA_OWNER_NAME, ExifInterface.TAG_LENS_MAKE,
            ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_SERIAL_NUMBER, ExifInterface.TAG_IMAGE_UNIQUE_ID,
            ExifInterface.TAG_USER_COMMENT, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_IMAGE_DESCRIPTION, ExifInterface.TAG_MAKER_NOTE, ExifInterface.TAG_XMP,
        )
    }
}
