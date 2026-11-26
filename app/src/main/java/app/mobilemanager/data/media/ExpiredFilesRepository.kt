package app.mobilemanager.data.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import app.mobilemanager.data.local.CleanupHistoryDao
import app.mobilemanager.data.local.CleanupHistoryEntity
import app.mobilemanager.data.local.KeptExpiredFileDao
import app.mobilemanager.data.local.KeptExpiredFileEntity
import app.mobilemanager.domain.model.ExpiredFileKind
import app.mobilemanager.domain.model.MediaImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Meilenstein 2.3.2: Screenshots und Downloads mit Verfallsdatum.
 *
 * Screenshots werden über ihren Ordner (`Pictures/Screenshots`, `DCIM/Screenshots`) erkannt und so
 * sauber von Kamerafotos getrennt. Mit „Fotos & Bilder“ sind nur Bilder sichtbar; PDFs & Co. im
 * Download-Ordner zeigt Android nur mit dem optionalen Recht „Zugriff auf alle Dateien“.
 */
@Singleton
class ExpiredFilesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keptDao: KeptExpiredFileDao,
    private val historyDao: CleanupHistoryDao,
) {
    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    fun kindOf(file: MediaImage): ExpiredFileKind =
        if (file.relativePath?.contains("Screenshots/", ignoreCase = true) == true) ExpiredFileKind.SCREENSHOT
        else ExpiredFileKind.DOWNLOAD

    /** Alte Dateien, die der Nutzer noch nicht behalten hat (älteste zuerst). */
    suspend fun findExpired(ageDays: Int): List<MediaImage> = withContext(Dispatchers.IO) {
        val kept = keptDao.getIds().toSet()
        val cutoffSeconds = (System.currentTimeMillis() - TimeUnit.DAYS.toMillis(ageDays.toLong())) / 1000
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_ADDED,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.MEDIA_TYPE,
        )
        val selection = buildString {
            append("${MediaStore.Files.FileColumns.IS_PENDING} = 0")
            append(" AND ${MediaStore.Files.FileColumns.IS_TRASHED} = 0")
            append(" AND ${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL")
            append(" AND ${MediaStore.Files.FileColumns.DATE_ADDED} < ?")
            append(" AND (")
            append(FOLDERS.joinToString(" OR ") { "${MediaStore.Files.FileColumns.RELATIVE_PATH} LIKE ?" })
            append(")")
        }
        val args = arrayOf(cutoffSeconds.toString()) + FOLDERS.map { "$it%" }
        val result = ArrayList<MediaImage>()
        context.contentResolver.query(
            collection, projection, selection, args,
            "${MediaStore.Files.FileColumns.DATE_ADDED} ASC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_ADDED)
            val modifiedCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
            val typeCol = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MEDIA_TYPE)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                if (id in kept) continue
                val path = c.getString(pathCol)
                result += MediaImage(
                    id = id,
                    uri = contentUri(c.getInt(typeCol), id),
                    displayName = c.getString(nameCol) ?: "",
                    sizeBytes = c.getLong(sizeCol),
                    dateTaken = c.getLong(addedCol) * 1000,
                    dateModified = c.getLong(modifiedCol),
                    bucket = path?.trimEnd('/')?.substringAfterLast('/'),
                    relativePath = path,
                    mimeType = c.getString(mimeCol),
                )
            }
        }
        result
    }

    suspend fun markKeep(id: Long) = keptDao.insert(KeptExpiredFileEntity(id, System.currentTimeMillis()))
    suspend fun undoKeep(id: Long) = keptDao.delete(id)

    /**
     * Löscht Nicht-Medien-Dateien (nur mit "Alle Dateien"-Zugriff sichtbar) direkt.
     * Bilder laufen über den Android-Löschdialog (`createDeleteRequest`).
     */
    suspend fun deleteDirectly(files: List<MediaImage>): List<MediaImage> = withContext(Dispatchers.IO) {
        files.filter { file -> runCatching { context.contentResolver.delete(file.uri, null, null) > 0 }.getOrDefault(false) }
    }

    suspend fun onDeleted(files: List<MediaImage>) {
        if (files.isEmpty()) return
        keptDao.deleteAll(files.map { it.id })
        historyDao.insert(
            CleanupHistoryEntity(
                timestamp = System.currentTimeMillis(),
                freedBytes = files.sumOf { it.sizeBytes },
                stoppedAppsCount = 0,
            ),
        )
    }

    private fun contentUri(mediaType: Int, id: Long): Uri = when (mediaType) {
        MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE ->
            ContentUris.withAppendedId(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), id)
        MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO ->
            ContentUris.withAppendedId(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), id)
        else -> ContentUris.withAppendedId(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), id)
    }

    private companion object {
        val FOLDERS = listOf("Pictures/Screenshots/", "DCIM/Screenshots/", "Download/")
    }
}
