package app.mobilemanager.ui.external

import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Phase 4.8: EXIF-Metadaten-Entferner im Teilen-Menü. Fotos werden in einen privaten Cache kopiert,
 * GPS-, Kamera- und Zeitangaben entfernt (JPEG/PNG/WebP verlustfrei über ExifInterface, andere Formate
 * wie HEIC werden als JPEG neu kodiert) und dann über das normale Teilen-Menü weitergegeben.
 */
class ExifShareActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uris = when (intent?.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isEmpty()) {
            finish()
            return
        }
        lifecycleScope.launch {
            val cleaned = withContext(Dispatchers.IO) {
                val dir = File(cacheDir, "exif_clean").apply { deleteRecursively(); mkdirs() }
                uris.mapIndexedNotNull { index, uri -> runCatching { clean(uri, dir, index) }.getOrNull() }
            }
            if (cleaned.isEmpty()) {
                Toast.makeText(applicationContext, "Bilder konnten nicht verarbeitet werden", Toast.LENGTH_SHORT).show()
                finish()
                return@launch
            }
            val contentUris = cleaned.map { FileProvider.getUriForFile(this@ExifShareActivity, "$packageName.files", it) }
            val send = if (contentUris.size == 1) {
                Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, contentUris.first())
            } else {
                Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(contentUris))
            }
            send.type = "image/*"
            send.clipData = ClipData.newRawUri(null, contentUris.first()).apply { contentUris.drop(1).forEach { addItem(ClipData.Item(it)) } }
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Toast.makeText(applicationContext, "${cleaned.size} Bild(er) ohne Metadaten", Toast.LENGTH_SHORT).show()
            startActivity(Intent.createChooser(send, "Ohne Metadaten teilen"))
            finish()
        }
    }

    private fun clean(uri: Uri, dir: File, index: Int): File {
        val type = contentResolver.getType(uri).orEmpty()
        val baseName = displayName(uri)?.substringBeforeLast('.')?.replace(Regex("[^A-Za-z0-9_-]"), "_")?.take(40) ?: "bild_$index"
        val lossless = type in setOf("image/jpeg", "image/png", "image/webp")
        if (lossless) {
            val ext = when (type) {
                "image/png" -> "png"
                "image/webp" -> "webp"
                else -> "jpg"
            }
            val out = File(dir, "${baseName}_$index.$ext")
            contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: error("unlesbar")
            val exif = ExifInterface(out)
            SENSITIVE_TAGS.forEach { tag -> exif.setAttribute(tag, null) }
            exif.saveAttributes()
            return out
        }
        // Neu kodieren entfernt sämtliche Metadaten; ImageDecoder wendet die Ausrichtung bereits an.
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(contentResolver, uri))
        val out = File(dir, "${baseName}_$index.jpg")
        out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        bitmap.recycle()
        return out
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    private companion object {
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
