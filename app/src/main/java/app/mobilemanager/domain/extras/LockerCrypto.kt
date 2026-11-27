package app.mobilemanager.domain.extras

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Phase 4.2: Kryptografie der App-Sperre – rein lokal, ohne Android-Abhängigkeiten (unit-testbar).
 * PINs und NFC-Kennungen werden nur gesalzen und mit PBKDF2 gehasht gespeichert, nie im Klartext.
 */
object LockerCrypto {
    private const val PBKDF2_ITERATIONS = 120_000
    private const val HASH_BITS = 256
    private val random = SecureRandom()

    fun newSalt(bytes: Int = 16): ByteArray = ByteArray(bytes).also(random::nextBytes)

    fun pbkdf2(secret: CharArray, salt: ByteArray, iterations: Int = PBKDF2_ITERATIONS): ByteArray {
        val spec = PBEKeySpec(secret, salt, iterations, HASH_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Vergleich in konstanter Zeit. */
    fun matches(secret: CharArray, salt: ByteArray, expected: ByteArray): Boolean =
        MessageDigest.isEqual(pbkdf2(secret, salt), expected)

    fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

    /* ---------- TOTP (RFC 6238, HMAC-SHA1, 30 s, 6 Stellen) ---------- */

    fun newTotpSecret(): ByteArray = ByteArray(20).also(random::nextBytes)

    fun totp(secret: ByteArray, timeMillis: Long, stepSeconds: Long = 30, digits: Int = 6): String {
        val counter = timeMillis / 1000 / stepSeconds
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret, "HmacSHA1")) }
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        var mod = 1
        repeat(digits) { mod *= 10 }
        return (binary % mod).toString().padStart(digits, '0')
    }

    /** Erlaubt ±1 Zeitschritt Uhrabweichung. */
    fun verifyTotp(secret: ByteArray, code: String, timeMillis: Long): Boolean {
        val clean = code.filter(Char::isDigit)
        if (clean.length != 6) return false
        return (-1..1).any { step ->
            MessageDigest.isEqual(totp(secret, timeMillis + step * 30_000L).toByteArray(), clean.toByteArray())
        }
    }

    /** otpauth-URI für Authenticator-Apps (Aegis, Google Authenticator …). */
    fun otpauthUri(secret: ByteArray, account: String = "App-Sperre", issuer: String = "MobileManager"): String =
        "otpauth://totp/$issuer:${account.replace(" ", "%20")}?secret=${base32(secret)}&issuer=$issuer&digits=6&period=30"

    /* ---------- Base32 (RFC 4648, ohne Padding) ---------- */

    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun base32(data: ByteArray): String = buildString {
        var buffer = 0
        var bits = 0
        for (b in data) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) append(ALPHABET[(buffer shl (5 - bits)) and 31])
    }

    fun base32Decode(text: String): ByteArray? {
        val clean = text.uppercase().filter { it != '=' && !it.isWhitespace() && it != '-' }
        val out = java.io.ByteArrayOutputStream()
        var buffer = 0
        var bits = 0
        for (c in clean) {
            val value = ALPHABET.indexOf(c).takeIf { it >= 0 } ?: return null
            buffer = (buffer shl 5) or value
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    /* ---------- Recovery-Codes ---------- */

    private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    /** Fünf Einmal-Codes im Format XXXXX-XXXXX (je ca. 50 Bit Zufall). */
    fun newRecoveryCodes(count: Int = 5): List<String> = List(count) {
        val chars = CharArray(10) { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }
        "${String(chars, 0, 5)}-${String(chars, 5, 5)}"
    }

    fun normalizeRecoveryCode(code: String): String = code.uppercase().filter { it in CODE_ALPHABET }

    fun recoveryHash(code: String): String = sha256(normalizeRecoveryCode(code)).joinToString("") { "%02x".format(it) }
}

/** Zweiter Faktor der App-Sperre (zusätzlich zur System-Biometrie). */
enum class SecondFactor(val label: String) {
    NONE("Kein zweiter Faktor"),
    PIN("Eigene App-PIN"),
    TOTP("Einmalcode (TOTP)"),
    NFC("NFC-Tag / YubiKey"),
}

/** Phase 4.2: Entscheidet, ob eine App gesperrt werden muss und welche Faktoren nötig sind. */
object LockerPolicy {
    val SYSTEM_SCREENS = setOf(
        "com.android.settings",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
    )

    fun lockedPackages(apps: Set<String>, lockSystemScreens: Boolean): Set<String> =
        if (lockSystemScreens) apps + SYSTEM_SCREENS else apps

    fun needsLock(pkg: String, locked: Set<String>, unlockedUntil: Long?, now: Long): Boolean =
        pkg in locked && (unlockedUntil == null || unlockedUntil < now)

    /** Im vertrauten Kontext (verbundenes Bluetooth-Gerät) reicht Faktor 1. */
    fun needsSecondFactor(factor: SecondFactor, trusted: Set<String>, connected: Set<String>): Boolean =
        factor != SecondFactor.NONE && (trusted intersect connected).isEmpty()

    /** Sperrzeit nach Fehlversuchen: ab 3 Fehlern 30 s, verdoppelt je weiterem Fehler (max. 15 min). */
    fun lockoutMillis(failures: Int): Long {
        if (failures < 3) return 0
        val factor = 1L shl (failures - 3).coerceAtMost(5)
        return (30_000L * factor).coerceAtMost(15 * 60_000L)
    }

    /** Strict Mode: Ausschalten erst nach Ablauf der Wartezeit, dann 10 Minuten lang möglich. */
    fun canDisable(cooldownMinutes: Int, requestedAt: Long, now: Long): Boolean {
        if (cooldownMinutes <= 0) return true
        if (requestedAt <= 0) return false
        val readyAt = requestedAt + cooldownMinutes * 60_000L
        return now in readyAt..(readyAt + 10 * 60_000L)
    }
}

/** Phase 4.5: Unterschied der kritischen Berechtigungen zwischen zwei Ständen einer App. */
object PermissionDiffPolicy {
    fun added(before: Map<String, Set<String>>, after: Map<String, Set<String>>): Map<String, Set<String>> =
        after.mapNotNull { (pkg, perms) ->
            val old = before[pkg] ?: return@mapNotNull null // neue Apps meldet bereits der Installations-Wächter
            (perms - old).takeIf { it.isNotEmpty() }?.let { pkg to it }
        }.toMap()
}
