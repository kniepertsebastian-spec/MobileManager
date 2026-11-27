package app.mobilemanager

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mobilemanager.data.local.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 4.0: prüft die Auto-Migrationen der App-Datenbank anhand der exportierten Schemas
 * (app/schemas). Jede neue Version braucht hier einen Schritt.
 */
@RunWith(AndroidJUnit4::class)
class AppDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test
    fun migrate1To2KeepsWhitelist() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL("INSERT INTO whitelisted_apps (packageName, addedAt) VALUES ('com.example.keep', 1)")
        }
        helper.runMigrationsAndValidate(TEST_DB, 2, true).use { db ->
            db.query("SELECT COUNT(*) FROM whitelisted_apps").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
        }
    }

    @Test
    fun migrateAllVersionsToLatest() {
        helper.createDatabase(TEST_DB, 1).close()
        helper.runMigrationsAndValidate(TEST_DB, LATEST, true).close()
        // Danach muss Room die Datenbank ohne Fehler öffnen können.
        Room.databaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, AppDatabase::class.java, TEST_DB)
            .build()
            .apply { openHelper.writableDatabase.close() }
            .close()
    }

    private companion object {
        const val TEST_DB = "migration-test.db"
        const val LATEST = 3
    }
}
