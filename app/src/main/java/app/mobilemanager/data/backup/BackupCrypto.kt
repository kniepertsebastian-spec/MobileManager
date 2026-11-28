package app.mobilemanager.data.backup

import app.mobilemanager.domain.extras.LockerCrypto
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Verschlüsselung der `.mmbackup`-Dateien – ohne Android-Abhängigkeiten, daher als JVM-Test prüfbar.
 *
 * Format v2: „MMBK2“ + Iterationen (Int, big endian) + Salt (16) + IV (12) + AES-256-GCM(ZIP).
 * Kopf (Magic + Iterationen) ist als AAD authentifiziert; Salt und IV sind bei jedem Export frisch
 * (SecureRandom). Schlüssel per PBKDF2-HMAC-SHA256 aus dem Passwort.
 * Format v1 („MMBK1“ + Salt + IV + GCM, 200 000 Iterationen) wird weiterhin gelesen.
 */
object BackupCrypto {
    const val ITERATIONS = 600_000
    private const val LEGACY_ITERATIONS = 200_000
    private const val MIN_ITERATIONS = 100_000
    private const val MAX_ITERATIONS = 5_000_000
    private val MAGIC_V1 = "MMBK1".toByteArray(Charsets.US_ASCII)
    private val MAGIC_V2 = "MMBK2".toByteArray(Charsets.US_ASCII)
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private val random = SecureRandom()

    class BackupFormatException(message: String) : Exception(message)

    fun seal(plain: ByteArray, password: CharArray, iterations: Int = ITERATIONS): ByteArray {
        val header = ByteBuffer.allocate(MAGIC_V2.size + Int.SIZE_BYTES).put(MAGIC_V2).putInt(iterations).array()
        val salt = LockerCrypto.newSalt(SALT_BYTES)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(header)
        val encrypted = cipher.doFinal(plain)
        return header + salt + iv + encrypted
    }

    /** @return Klartext, oder ein Fehler mit verständlicher Meldung (falsches Passwort, beschädigt, fremd). */
    fun open(bytes: ByteArray, password: CharArray): Result<ByteArray> = runCatching {
        val magic = bytes.copyOfRange(0, minOf(bytes.size, MAGIC_V2.size))
        val (header, iterations) = when {
            magic.contentEquals(MAGIC_V2) -> {
                if (bytes.size < MAGIC_V2.size + Int.SIZE_BYTES) throw BackupFormatException("Sicherung ist beschädigt")
                val header = bytes.copyOfRange(0, MAGIC_V2.size + Int.SIZE_BYTES)
                val iterations = ByteBuffer.wrap(header, MAGIC_V2.size, Int.SIZE_BYTES).int
                if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw BackupFormatException("Sicherung ist beschädigt")
                header to iterations
            }
            magic.contentEquals(MAGIC_V1) -> MAGIC_V1 to LEGACY_ITERATIONS
            else -> throw BackupFormatException("Keine MobileManager-Sicherung")
        }
        val bodyStart = header.size + SALT_BYTES + IV_BYTES
        if (bytes.size < bodyStart + TAG_BITS / 8) throw BackupFormatException("Sicherung ist beschädigt")
        val salt = bytes.copyOfRange(header.size, header.size + SALT_BYTES)
        val iv = bytes.copyOfRange(header.size + SALT_BYTES, bodyStart)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(password, salt, iterations), GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD(header)
        try {
            cipher.doFinal(bytes, bodyStart, bytes.size - bodyStart)
        } catch (e: AEADBadTagException) {
            throw BackupFormatException("Falsches Passwort oder beschädigte Datei")
        }
    }

    private fun key(password: CharArray, salt: ByteArray, iterations: Int) =
        SecretKeySpec(LockerCrypto.pbkdf2(password, salt, iterations), "AES")
}
