package app.mobilemanager.data.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import app.mobilemanager.data.local.AppDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 4.6: verschlüsseltes Komplett-Backup (`.mmbackup`) aller Einstellungen, Regeln, Launcher-Layouts,
 * Blocklisten und Protokolle. Verschlüsselung siehe [BackupCrypto]. Die Geheimnisse der App-Sperre bleiben
 * bewusst draußen (gerätegebunden).
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
            val sealed = BackupCrypto.seal(zipBytes.toByteArray(), password.toCharArray())
            context.contentResolver.openOutputStream(target, "wt")?.use { out -> out.write(sealed) } ?: error("Zieldatei kann nicht geschrieben werden")
            count
        }
    }

    /** Entschlüsselt und legt die Wiederherstellung für den nächsten Start bereit. */
    suspend fun prepareRestore(source: Uri, password: String): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = readLimited(source)
            val zip = BackupCrypto.open(bytes, password.toCharArray()).getOrElse { throw it }
            val staging = File(context.filesDir, STAGING).apply { deleteRecursively(); mkdirs() }
            val count = runCatching { unpack(zip, staging) }.getOrElse {
                staging.deleteRecursively()
                throw it
            }
            File(staging, READY_MARKER).createNewFile()
            count
        }
    }

    /** Verwirft eine vorbereitete, noch nicht eingespielte Wiederherstellung. */
    fun cancelPendingRestore() {
        File(context.filesDir, STAGING).deleteRecursively()
    }

    private fun readLimited(source: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(source) ?: error("Datei kann nicht gelesen werden")
        return input.use {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                total += read
                require(total <= MAX_BACKUP_BYTES) { "Datei ist zu groß für eine MobileManager-Sicherung" }
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }

    /** Entpackt nur bekannte Ziele, prüft Pfade und begrenzt die entpackte Größe (Zip-Bombe). */
    private fun unpack(zip: ByteArray, staging: File): Int {
        val root = staging.canonicalFile
        var count = 0
        var total = 0L
        ZipInputStream(ByteArrayInputStream(zip)).use { input ->
            while (true) {
                val entry = input.nextEntry ?: break
                require(!entry.isDirectory && isAllowed(entry.name)) { "Unerwarteter Inhalt: ${entry.name.take(80)}" }
                require(++count <= MAX_ENTRIES) { "Sicherung enthält zu viele Dateien" }
                val out = File(root, entry.name).canonicalFile
                require(out.path.startsWith(root.path + File.separator)) { "Unerwarteter Pfad: ${entry.name.take(80)}" }
                out.parentFile?.mkdirs()
                out.outputStream().use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        require(total <= MAX_UNPACKED_BYTES) { "Sicherung ist zu groß" }
                        sink.write(buffer, 0, read)
                    }
                }
                if (entry.name.startsWith("databases/")) {
                    require(isSqlite(out)) { "Beschädigte Datenbank in der Sicherung" }
                }
            }
        }
        require(count > 0) { "Sicherung ist leer" }
        return count
    }

    private fun isSqlite(file: File): Boolean = file.inputStream().use { input ->
        val header = ByteArray(SQLITE_HEADER.size)
        input.read(header) == header.size && header.contentEquals(SQLITE_HEADER)
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

    companion object {
        const val MIN_PASSWORD = 8
        private const val MAX_BACKUP_BYTES = 256L * 1024 * 1024
        private const val MAX_UNPACKED_BYTES = 512L * 1024 * 1024
        private const val MAX_ENTRIES = 512
        private val SQLITE_HEADER = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
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

        internal fun isAllowed(name: String): Boolean {
            if (name.contains("..") || name.startsWith("/") || name.contains('\\') || name.any(Char::isISOControl)) return false
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
