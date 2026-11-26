package app.mobilemanager.data.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Prüft Bilder lokal per ML Kit auf Gesichter. Bilder werden vorab auf max. [MAX_EDGE_PX]
 * herunterskaliert, damit der Scan schnell bleibt und keinen OutOfMemoryError auslöst.
 */
@Singleton
class FaceAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val detector: FaceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(MIN_FACE_SIZE)
                .build(),
        )
    }

    /** @return `true` wenn mind. ein Gesicht erkannt wurde, `null` wenn das Bild nicht lesbar ist. */
    suspend fun hasFaces(uri: Uri): Boolean? {
        val bitmap = decodeScaled(uri) ?: return null
        return try {
            val faces = detector.process(InputImage.fromBitmap(bitmap, 0)).await()
            faces.isNotEmpty()
        } catch (e: Exception) {
            null
        } finally {
            bitmap.recycle()
        }
    }

    private fun decodeScaled(uri: Uri): Bitmap? = runCatching {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val longest = max(info.size.width, info.size.height)
            if (longest > MAX_EDGE_PX) {
                val scale = MAX_EDGE_PX.toFloat() / longest
                decoder.setTargetSize(
                    (info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1),
                )
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }.getOrNull()

    private companion object {
        const val MAX_EDGE_PX = 640
        const val MIN_FACE_SIZE = 0.08f
    }
}
