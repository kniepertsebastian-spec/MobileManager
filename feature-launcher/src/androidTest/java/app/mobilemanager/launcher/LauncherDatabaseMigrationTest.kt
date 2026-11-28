package app.mobilemanager.launcher

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.mobilemanager.launcher.data.LauncherDatabase
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Roadmap v2 B.1: Upgrade der Launcher-Datenbank 1 → 2 (neue Tabelle `tidy_folders`). Bestehende Profile,
 * Startbildschirm-Layouts, Ordner, Snapshots und Regeln müssen dabei unverändert erhalten bleiben.
 */
@RunWith(AndroidJUnit4::class)
class LauncherDatabaseMigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), LauncherDatabase::class.java)

    @Test
    fun migrate1To2KeepsLayoutsFoldersAndRules() {
        helper.createDatabase(TEST_DB, 1).use { db ->
            db.execSQL("INSERT INTO profiles (id, name, kind, hiddenCategories, largeIcons, sortOrder) VALUES (1, 'Arbeit', 'WORK', 'GAMES', 1, 0)")
            // Ordner (container 0, ohne componentKey) mit einem App-Kind auf Seite 1.
            db.execSQL("INSERT INTO items (id, profileId, container, page, position, componentKey, title, parentId) VALUES (10, 1, 0, 1, 3, NULL, 'Büro', NULL)")
            db.execSQL("INSERT INTO items (id, profileId, container, page, position, componentKey, title, parentId) VALUES (11, 1, 0, 1, 0, 'com.example/.Main', NULL, 10)")
            db.execSQL("INSERT INTO items (id, profileId, container, page, position, componentKey, title, parentId) VALUES (12, 1, 1, 0, 2, 'com.example.dock/.Main', NULL, NULL)")
            db.execSQL("INSERT INTO snapshots (id, profileId, createdAt, label) VALUES (5, 1, 1700000000000, 'Vorher')")
            db.execSQL("INSERT INTO snapshot_items (snapshotId, originalId, container, page, position, componentKey, title, parentId) VALUES (5, 11, 0, 1, 0, 'com.example/.Main', NULL, 10)")
            db.execSQL("INSERT INTO rules (id, profileId, type, value, label, daysMask, startMinutes, endMinutes, enabled) VALUES (7, 1, 'TIME', '', 'Werktags', 31, 480, 1020, 1)")
        }

        helper.runMigrationsAndValidate(TEST_DB, 2, true).use { db ->
            db.query("SELECT name, kind, hiddenCategories, largeIcons FROM profiles WHERE id = 1").use { c ->
                c.moveToFirst()
                assertEquals("Arbeit", c.getString(0))
                assertEquals("WORK", c.getString(1))
                assertEquals("GAMES", c.getString(2))
                assertEquals(1, c.getInt(3))
            }
            db.query("SELECT id, page, position, componentKey, title, parentId FROM items ORDER BY id").use { c ->
                assertEquals(3, c.count)
                c.moveToFirst()
                assertEquals("Büro", c.getString(4))
                c.moveToNext()
                assertEquals("com.example/.Main", c.getString(3))
                assertEquals(10L, c.getLong(5))
            }
            db.query("SELECT COUNT(*) FROM snapshot_items WHERE snapshotId = 5").use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
            db.query("SELECT label, daysMask, startMinutes, endMinutes, enabled FROM rules WHERE id = 7").use { c ->
                c.moveToFirst()
                assertEquals("Werktags", c.getString(0))
                assertEquals(31, c.getInt(1))
                assertEquals(1020, c.getInt(3))
            }
            // Neue Tabelle ist leer und nutzbar.
            db.query("SELECT COUNT(*) FROM tidy_folders").use { c ->
                c.moveToFirst()
                assertEquals(0, c.getInt(0))
            }
            db.execSQL(
                "INSERT INTO tidy_folders (title, componentKeys, packagePrefixes, includeSideloaded, includeDrawerApps, enabled, sortOrder) " +
                    "VALUES ('Spiele', 'a/.B', '', 0, 1, 1, 0)",
            )
        }

        // Danach muss Room die migrierte Datenbank selbst öffnen können.
        Room.databaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext, LauncherDatabase::class.java, TEST_DB)
            .build()
            .apply { openHelper.writableDatabase.close() }
            .close()
    }

    private companion object {
        const val TEST_DB = "launcher-migration-test"
    }
}
