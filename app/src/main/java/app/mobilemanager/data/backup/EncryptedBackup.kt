package app.mobilemanager.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import app.mobilemanager.data.local.AppDatabase
import app.mobilemanager.domain.extras.LockerCrypto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 4.6: verschlüsseltes Komplett-Backup (`.mmbackup`) aller Einstellungen, Regeln, Launcher-Layouts,
 * Blocklisten und Protokolle. Format: „MMBK1“ + Salt (16) + IV (12) + AES-256-GCM(ZIP); Schlüssel per PBKDF2
 * aus dem Passwort. Die Geheimnisse der App-Sperre bleiben bewusst draußen (gerätegebunden).
 *
 * Wiederherstellen läuft zweistufig: Dateien werden entschlüsselt und geprüft bereitgelegt, die App beendet sich,
 * und beim nächsten Start ersetzt [applyPendingRestore] die Daten – bevor DataStore oder Room sie öffnen.
 */
@Singleton
class EncryptedBackup @Inject constructor(@ApplicationContext private val context: Context) {

    suspend fun export(target: Uri, password: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            require(password.length >= MIN_PASSWORD) { "Passwort muss mindestens $MIN_PASSWORD Zeichen haben" }
            checkpoint(AppDatabase.NAME)
            checkpoint(LAUNCHER_DB)
            val zipBytes = ByteArrayOutputStream()
            var count = 0
            ZipOutputStream(zipBytes).use { zip ->
                backupFiles(context).forEach { (name, file) ->
                    zip.putNextEntry(ZipEntry(name))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    count++
                }
            }
            val salt = LockerCrypto.newSalt()
            val iv = ByteArray(IV_BYTES).also(SecureRandom()::nextBytes)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key(password, salt), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(MAGIC)
            val encrypted = cipher.doFinal(zipBytes.toByteArray())
            context.contentResolver.openOutputStream(target, "wt")?.use { out ->
                out.write(MAGIC)
                out.write(salt)
                out.write(iv)
                out.write(encrypted)
            } ?: error("Zieldatei kann nicht geschrieben werden")
            count
        }
    }

    /** Entschlüsselt und legt die Wiederherstellung für den nächsten Start bereit. */
    suspend fun prepareRestore(source: Uri, password: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = context.contentResolver.openInputStream(source)?.use { it.readBytes() } ?: error("Datei kann nicht gelesen werden")
            require(bytes.size > MAGIC.size + 16 + IV_BYTES && bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
                "Keine MobileManager-Sicherung"
            }
            val salt = bytes.copyOfRange(MAGIC.size, MAGIC.size + 16)
            val iv = bytes.copyOfRange(MAGIC.size + 16, MAGIC.size + 16 + IV_BYTES)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(password, salt), GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(MAGIC)
            val zip = runCatching { cipher.doFinal(bytes, MAGIC.size + 16 + IV_BYTES, bytes.size - MAGIC.size - 16 - IV_BYTES) }
                .getOrElse { error("Falsches Passwort oder beschädigte Datei") }
            val staging = File(context.filesDir, STAGING).apply { deleteRecursively(); mkdirs() }
            var count = 0
            ZipInputStream(ByteArrayInputStream(zip)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    // Nur bekannte Ziele, keine Pfad-Tricks („../“).
                    require(isAllowed(entry.name)) { "Unerwarteter Inhalt: ${entry.name}" }
                    val out = File(staging, entry.name)
                    out.parentFile?.mkdirs()
                    out.outputStream().use { input.copyTo(it) }
                    count++
                }
            }
            require(count > 0) { "Sicherung ist leer" }
            File(staging, READY_MARKER).createNewFile()
            count
        }
    }

    private fun checkpoint(name: String) {
        val path = context.getDatabasePath(name)
        if (!path.exists()) return
        runCatching {
            SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
                db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            }
        }
    }

    private fun key(password: String, salt: ByteArray) =
        SecretKeySpec(LockerCrypto.pbkdf2(password.toCharArray(), salt, KDF_ITERATIONS), "AES")

    companion object {
        private val MAGIC = "MMBK1".toByteArray()
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
        private const val KDF_ITERATIONS = 200_000
        const val MIN_PASSWORD = 8
        private const val STAGING = "restore_pending"
        private const val READY_MARKER = ".ready"
        private const val LAUNCHER_DB = "launcher.db"
        private val EXCLUDED_STORES = setOf("locker_settings.preferences_pb")

        /** Name in der Sicherung → Datei auf dem Gerät. */
        private fun backupFiles(context: Context): List<Pair<String, File>> = buildList {
            File(context.filesDir, "datastore").listFiles()
                ?.filter { it.isFile && it.name.endsWith(".preferences_pb") && it.name !in EXCLUDED_STORES }
                ?.forEach { add("datastore/${it.name}" to it) }
            listOf(AppDatabase.NAME, LAUNCHER_DB).forEach { name ->
                context.getDatabasePath(name).takeIf(File::exists)?.let { add("databases/$name" to it) }
            }
            File(context.filesDir, "tracker_import.txt").takeIf(File::exists)?.let { add("files/tracker_import.txt" to it) }
            File(context.filesDir, "blocklists").listFiles()?.filter(File::isFile)?.forEach { add("files/blocklists/${it.name}" to it) }
        }

        private fun isAllowed(name: String): Boolean {
            if (name.contains("..") || name.startsWith("/")) return false
            return (name.startsWith("datastore/") && name.endsWith(".preferences_pb") && name.substringAfter('/') !in EXCLUDED_STORES) ||
                name == "databases/${AppDatabase.NAME}" || name == "databases/$LAUNCHER_DB" ||
                name == "files/tracker_import.txt" || (name.startsWith("files/blocklists/") && name.count { it == '/' } == 2)
        }

        /** Beim App-Start vor allem anderen aufrufen: ersetzt die Daten durch eine vorbereitete Sicherung. */
        fun applyPendingRestore(context: Context) {
            val staging = File(context.filesDir, STAGING)
            if (!File(staging, READY_MARKER).exists()) {
                if (staging.exists()) staging.deleteRecursively()
                return
            }
            runCatching {
                staging.walkTopDown().filter { it.isFile && it.name != READY_MARKER }.forEach { file ->
                    val name = file.relativeTo(staging).path
                    if (!isAllowed(name)) return@forEach
                    val target = when {
                        name.startsWith("datastore/") -> File(context.filesDir, name)
                        name.startsWith("databases/") -> context.getDatabasePath(name.substringAfter('/')).also { db ->
                            File(db.path + "-wal").delete()
                            File(db.path + "-shm").delete()
                        }
                        else -> File(context.filesDir, name.removePrefix("files/"))
                    }
                    target.parentFile?.mkdirs()
                    file.copyTo(target, overwrite = true)
                }
            }
            staging.deleteRecursively()
        }
    }
}
