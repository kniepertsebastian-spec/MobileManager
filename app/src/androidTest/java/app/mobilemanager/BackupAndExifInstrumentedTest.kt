package app.mobilemanager

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mobilemanager.data.backup.EncryptedBackup
import app.mobilemanager.data.extras.ExifCleaner
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Roadmap v2 D.1: verschlüsseltes Backup (echte Dateien) und EXIF-Entferner auf dem Gerät. */
@RunWith(AndroidJUnit4::class)
class BackupAndExifInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var work: File

    @Before
    fun setUp() {
        work = File(context.cacheDir, "instrumented-${System.nanoTime()}").apply { mkdirs() }
    }

    @After
    fun tearDown() {
        work.deleteRecursively()
        // Nie eine vorbereitete Wiederherstellung liegen lassen – sie würde beim nächsten App-Start eingespielt.
        EncryptedBackup(context).cancelPendingRestore()
        File(context.cacheDir, ExifCleaner.DIR).deleteRecursively()
    }

    /* ---------- Backup ---------- */

    @Test
    fun backupExportAndPrepareRestoreRoundTrip() = runBlocking {
        val backup = EncryptedBackup(context)
        val file = File(work, "test.mmbackup")
        val exported = backup.export(Uri.fromFile(file), "richtig-123").getOrThrow()
        assertTrue("Sicherung enthält Dateien", exported > 0)
        assertTrue(file.readBytes().copyOfRange(0, 5).contentEquals("MMBK2".toByteArray()))
        assertEquals(exported, backup.prepareRestore(Uri.fromFile(file), "richtig-123").getOrThrow())
        assertTrue(File(context.filesDir, "restore_pending/.ready").exists())
        backup.cancelPendingRestore()
        assertFalse(File(context.filesDir, "restore_pending").exists())
    }

    @Test
    fun backupRejectsWrongPasswordAndCorruptFiles() = runBlocking {
        val backup = EncryptedBackup(context)
        val file = File(work, "test.mmbackup")
        backup.export(Uri.fromFile(file), "richtig-123").getOrThrow()

        val wrong = backup.prepareRestore(Uri.fromFile(file), "falsch-1234")
        assertEquals("Falsches Passwort oder beschädigte Datei", wrong.exceptionOrNull()?.message)

        val corrupt = File(work, "corrupt.mmbackup").apply {
            writeBytes(file.readBytes().also { it[it.size / 2] = (it[it.size / 2].toInt() xor 0x40).toByte() })
        }
        assertTrue(backup.prepareRestore(Uri.fromFile(corrupt), "richtig-123").isFailure)

        val foreign = File(work, "foreign.mmbackup").apply { writeText("Kein Backup") }
        assertEquals("Keine MobileManager-Sicherung", backup.prepareRestore(Uri.fromFile(foreign), "richtig-123").exceptionOrNull()?.message)

        val tooShort = backup.export(Uri.fromFile(file), "kurz")
        assertTrue("Passwort unter 8 Zeichen wird abgelehnt", tooShort.isFailure)
        assertFalse("Fehlschläge hinterlassen nichts", File(context.filesDir, "restore_pending/.ready").exists())
    }

    /* ---------- EXIF ---------- */

    @Test
    fun exifCleanerRemovesGpsAndCameraTags() {
        val source = File(work, "foto.jpg")
        Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }.let { bitmap ->
            source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
        }
        ExifInterface(source).apply {
            setLatLong(52.5200, 13.4050)
            setAttribute(ExifInterface.TAG_MAKE, "TestKamera")
            setAttribute(ExifInterface.TAG_MODEL, "Modell X")
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:28 10:00:00")
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
        assertTrue(ExifInterface(source).latLong != null)

        val cleaner = ExifCleaner(context)
        val out = cleaner.clean(Uri.fromFile(source), cleaner.freshDirectory(), 0)
        val exif = ExifInterface(out)
        assertNull(exif.latLong)
        assertNull(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE))
        assertNull(exif.getAttribute(ExifInterface.TAG_MAKE))
        assertNull(exif.getAttribute(ExifInterface.TAG_MODEL))
        assertNull(exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        // Ausrichtung bleibt erhalten, sonst stünde das Bild nach dem Teilen auf dem Kopf.
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertTrue(out.parentFile?.name == ExifCleaner.DIR)
    }

    @Test
    fun exifCleanerRejectsNonImagesAndOwnFiles() {
        val cleaner = ExifCleaner(context)
        // Eigene private Dateien (file:// bzw. eigener Provider) werden gar nicht erst angenommen.
        assertFalse(cleaner.accepts(Uri.fromFile(File(context.filesDir, "datastore/settings.preferences_pb"))))
        assertFalse(cleaner.accepts(Uri.parse("content://${context.packageName}.files/exif_clean/x.jpg")))
        // Getarnte Nicht-Bilder scheitern beim Bereinigen, ohne Reste zu hinterlassen.
        val fake = File(work, "keinbild.jpg").apply { writeText("das ist kein JPEG") }
        val dir = cleaner.freshDirectory()
        assertTrue(runCatching { cleaner.clean(Uri.fromFile(fake), dir, 0) }.isFailure)
        assertEquals(0, dir.listFiles()?.size ?: 0)
    }
}
