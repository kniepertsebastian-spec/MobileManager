package app.mobilemanager.data.media

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImageHasher @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** SHA-256 über den kompletten Dateistrom, `null` wenn die Datei nicht lesbar ist. */
    fun sha256(uri: Uri): String? = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        context.contentResolver.openInputStream(uri)?.use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        } ?: return null
        digest.digest().joinToString("") { "%02x".format(it) }
    }.getOrNull()

    private companion object {
        const val BUFFER_SIZE = 64 * 1024
    }
}
