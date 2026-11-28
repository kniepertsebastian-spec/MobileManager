package app.mobilemanager

import android.util.Base64
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mobilemanager.data.alerts.AlertConfigStore
import app.mobilemanager.data.alerts.KeystoreCipher
import app.mobilemanager.data.local.AppDatabase
import app.mobilemanager.data.local.NotificationEntryEntity
import app.mobilemanager.data.notifications.NotificationCipher
import app.mobilemanager.data.notifications.NotificationRepository
import app.mobilemanager.domain.model.AlertChannelType
import app.mobilemanager.domain.model.AlertConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Roadmap 4.2: Keystore-Verschlüsselung, Alarm-Zugangsdaten und verschlüsseltes Benachrichtigungs-Archiv auf echtem Android. */
@RunWith(AndroidJUnit4::class)
class SecurityInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun keystoreCipherRoundTripAndTamperDetection() {
        val cipher = KeystoreCipher()
        val sealed = cipher.encrypt("geheim äöü")
        assertNotEquals("geheim äöü", sealed)
        assertEquals("geheim äöü", cipher.decrypt(sealed))
        // Zufälliger IV: gleicher Klartext ergibt unterschiedliche Chiffrate.
        assertNotEquals(sealed, cipher.encrypt("geheim äöü"))
        // GCM erkennt jede Manipulation.
        val bytes = Base64.decode(sealed, Base64.NO_WRAP).also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertNull(cipher.decrypt(Base64.encodeToString(bytes, Base64.NO_WRAP)))
    }

    @Test
    fun alertConfigStoreRoundTrip() = runBlocking {
        val store = AlertConfigStore(context, KeystoreCipher())
        val config = AlertConfig(channel = AlertChannelType.NTFY, ntfyServer = "https://ntfy.sh", ntfyTopic = "t-123", ntfyToken = "tk")
        store.save(config)
        val loaded = store.current()
        assertEquals("t-123", loaded.ntfyTopic)
        assertEquals("tk", loaded.ntfyToken)
        store.save(AlertConfig())
    }

    @Test
    fun notificationCipherSealsAndKeepsLegacyPlaintextReadable() {
        val cipher = NotificationCipher(context)
        val sealed = cipher.seal("TAN 123456")
        assertTrue(cipher.isSealed(sealed))
        assertFalse(sealed.contains("123456"))
        assertEquals("TAN 123456", cipher.open(sealed))
        assertEquals("", cipher.open(cipher.seal("")))
        assertEquals("alter Klartext", cipher.open("alter Klartext"))
    }

    @Test
    fun archiveStoresCiphertextAndSearchesDecrypted() = runBlocking {
        val repository = NotificationRepository(context, db.notificationEntryDao(), NotificationCipher(context))
        repository.insert(entry("k1", "Max", "Treffen um 18 Uhr"))
        repository.insert(entry("k2", "Paket", "Zustellung heute"))

        db.query("SELECT title, text FROM notification_entries", null).use { c ->
            while (c.moveToNext()) {
                assertFalse(c.getString(0).contains("Max") || c.getString(0).contains("Paket"))
                assertFalse(c.getString(1).contains("18 Uhr") || c.getString(1).contains("Zustellung"))
            }
        }
        val hits = repository.search("18 uhr", null).first()
        assertEquals(listOf("Max"), hits.map { it.title })
        assertEquals(2, repository.search("", null).first().size)
    }

    @Test
    fun legacyPlaintextRowsAreSealedLater() = runBlocking {
        val dao = db.notificationEntryDao()
        dao.insert(entry("legacy", "Alt", "Klartext von früher"))
        val repository = NotificationRepository(context, dao, NotificationCipher(context))
        repository.sealLegacyEntries()
        db.query("SELECT text FROM notification_entries", null).use { c ->
            c.moveToFirst()
            assertFalse(c.getString(0).contains("Klartext"))
        }
        assertEquals("Klartext von früher", repository.search("früher", null).first().single().text)
    }

    private fun entry(key: String, title: String, text: String) = NotificationEntryEntity(
        notificationKey = key, packageName = "com.example", appLabel = "Example", title = title, text = text,
        postedAt = System.currentTimeMillis(),
    )
}
