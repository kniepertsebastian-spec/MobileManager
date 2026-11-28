package app.mobilemanager.data.notifications

import android.content.Context
import android.util.Base64
import app.mobilemanager.data.alerts.AesKeystoreCipher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Feldverschlüsselung für das Benachrichtigungs-Archiv (Titel und Text), AES-256-GCM.
 *
 * Umschlagverfahren: Ein zufälliger Datenschlüssel verschlüsselt die Felder in Software (schnell genug für die
 * Volltextsuche über das ganze 7-Tage-Fenster); er selbst liegt nur mit einem nicht exportierbaren Keystore-Schlüssel
 * verschlüsselt in `noBackupFilesDir`. Ohne diesen Keystore-Schlüssel (z. B. nach dem Kopieren der Datenbank auf ein
 * anderes Gerät) sind die Einträge nicht lesbar.
 */
@Singleton
class NotificationCipher @Inject constructor(@ApplicationContext private val context: Context) {

    private val wrapper = AesKeystoreCipher(KEYSTORE_ALIAS)
    private val random = SecureRandom()
    @Volatile private var dataKey: SecretKey? = null

    fun seal(plain: String): String {
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return PREFIX + Base64.encodeToString(iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }

    /** Klartext; ältere unverschlüsselte Einträge kommen unverändert zurück, unlesbare als `null`. */
    fun open(stored: String): String? {
        if (!isSealed(stored)) return stored
        return runCatching {
            val bytes = Base64.decode(stored.substring(PREFIX.length), Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
            String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
        }.getOrNull()
    }

    fun isSealed(stored: String) = stored.startsWith(PREFIX)

    @Synchronized
    private fun key(): SecretKey {
        dataKey?.let { return it }
        val file = File(context.noBackupFilesDir, KEY_FILE)
        val existing = file.takeIf { it.exists() }?.readBytes()?.let(wrapper::decryptBytes)
        val raw = existing ?: ByteArray(KEY_BYTES).also { random.nextBytes(it) }.also { fresh ->
            // Neu oder nicht mehr entschlüsselbar: alte Einträge sind damit ohnehin verloren.
            file.writeBytes(wrapper.encryptBytes(fresh))
        }
        return SecretKeySpec(raw, "AES").also { dataKey = it }
    }

    private companion object {
        const val PREFIX = "§e1§"
        const val KEYSTORE_ALIAS = "mobilemanager_notification_archive"
        const val KEY_FILE = "notification_archive.key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_BYTES = 32
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}
