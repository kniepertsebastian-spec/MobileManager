package app.mobilemanager.data.media

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import app.mobilemanager.domain.model.MediaImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** Liest alle Bilder des internen Speichers über den MediaStore. */
@Singleton
class MediaScanner @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun queryImages(): List<MediaImage> = withContext(Dispatchers.IO) {
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.RELATIVE_PATH,
        )
        val result = ArrayList<MediaImage>()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.Images.Media.IS_PENDING} = 0 AND ${MediaStore.Images.Media.IS_TRASHED} = 0",
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val addedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val takenCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val modifiedCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val bucketCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val pathCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.RELATIVE_PATH)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val addedMs = c.getLong(addedCol) * 1000
                val takenMs = if (c.isNull(takenCol)) 0L else c.getLong(takenCol)
                result += MediaImage(
                    id = id,
                    uri = ContentUris.withAppendedId(collection, id),
                    displayName = c.getString(nameCol) ?: "",
                    sizeBytes = c.getLong(sizeCol),
                    dateTaken = if (takenMs > 0) takenMs else addedMs,
                    dateModified = c.getLong(modifiedCol),
                    bucket = c.getString(bucketCol),
                    relativePath = c.getString(pathCol),
                )
            }
        }
        result
    }
}
