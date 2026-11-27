package app.mobilemanager.launcher

import app.mobilemanager.launcher.domain.AppCategorizer
import app.mobilemanager.launcher.domain.AppCategory
import app.mobilemanager.launcher.domain.AppMeta
import app.mobilemanager.launcher.domain.AppSuggestions
import app.mobilemanager.launcher.domain.ArchiveMode
import app.mobilemanager.launcher.domain.Container
import app.mobilemanager.launcher.domain.CustomFolderRule
import app.mobilemanager.launcher.domain.LauncherContext
import app.mobilemanager.launcher.domain.LayoutItem
import app.mobilemanager.launcher.domain.PlannedEntry
import app.mobilemanager.launcher.domain.ProfileResolver
import app.mobilemanager.launcher.domain.ProfileRule
import app.mobilemanager.launcher.domain.RuleType
import app.mobilemanager.launcher.domain.TidyOptions
import app.mobilemanager.launcher.domain.TidyPlan
import app.mobilemanager.launcher.domain.TidyPlanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

class LauncherDomainTest {

    @Test
    fun `categorizer prefers finance list, then android category, then keywords`() {
        assertEquals(AppCategory.FINANCE, AppCategorizer.categorize(AppCategorizer.CATEGORY_PRODUCTIVITY, "com.starfinanz.smob.android.sfinanzstatus", "Sparkasse"))
        assertEquals(AppCategory.GAMES, AppCategorizer.categorize(AppCategorizer.CATEGORY_GAME, "com.king.candy", "Candy"))
        assertEquals(AppCategory.SOCIAL, AppCategorizer.categorize(AppCategorizer.CATEGORY_SOCIAL, "com.instagram.android", "Instagram"))
        assertEquals(AppCategory.COMMUNICATION, AppCategorizer.categorize(AppCategorizer.CATEGORY_UNDEFINED, "org.mozilla.firefox", "Firefox"))
        assertEquals(AppCategory.COMMUNICATION, AppCategorizer.categorize(AppCategorizer.CATEGORY_UNDEFINED, "com.google.android.gm", "Gmail"))
        assertEquals(AppCategory.TOOLS, AppCategorizer.categorize(AppCategorizer.CATEGORY_UNDEFINED, "com.example.flashlight", "Taschenlampe"))
    }

    private val now = TimeUnit.DAYS.toMillis(1000)
    private val recent = now - TimeUnit.DAYS.toMillis(2)
    private val old = now - TimeUnit.DAYS.toMillis(90)

    private val apps = mapOf(
        "game1" to AppMeta("Zelda", AppCategory.GAMES, recent),
        "game2" to AppMeta("Angry", AppCategory.GAMES, recent),
        "insta" to AppMeta("Instagram", AppCategory.SOCIAL, recent),
        "bank" to AppMeta("Sparkasse", AppCategory.FINANCE, recent),
        "paypal" to AppMeta("PayPal", AppCategory.FINANCE, old),
        "torch" to AppMeta("Taschenlampe", AppCategory.TOOLS, old),
    )

    private fun messyLayout(): List<LayoutItem> = listOf(
        LayoutItem(1, Container.HOME, 0, 3, "torch"),
        LayoutItem(2, Container.HOME, 0, 0, "game1"),
        LayoutItem(3, Container.HOME, 1, 7, "bank"),
        LayoutItem(4, Container.HOME, 0, 9, "insta"),
        LayoutItem(5, Container.HOME, 2, 1, "paypal"),
        LayoutItem(6, Container.HOME, 0, 5, "game2"),
        LayoutItem(7, Container.HOME, 0, 6, "uninstalled"),
        LayoutItem(8, Container.DOCK, 0, 0, "insta"),
    )

    /** Wandelt einen Plan zurück in Layout-Einträge (wie es das Repository tut). */
    private fun materialize(plan: TidyPlan, dock: List<LayoutItem>): List<LayoutItem> {
        var nextId = 100L
        val result = dock.toMutableList()
        plan.entries.forEach { entry ->
            when (entry) {
                is PlannedEntry.App -> result += LayoutItem(nextId++, Container.HOME, entry.page, entry.position, entry.componentKey)
                is PlannedEntry.Folder -> {
                    val folderId = nextId++
                    result += LayoutItem(folderId, Container.HOME, entry.page, entry.position, null, entry.title)
                    entry.children.forEachIndexed { i, key -> result += LayoutItem(nextId++, Container.FOLDER, 0, i, key, parentId = folderId) }
                }
            }
        }
        return result
    }

    @Test
    fun `tidy collects categories into folders and keeps singles loose`() {
        val plan = TidyPlanner.plan(messyLayout(), apps, TidyOptions(), pageSize = 20, now = now)
        val folders = plan.entries.filterIsInstance<PlannedEntry.Folder>()
        assertEquals(listOf("Finanzen", "Spiele"), folders.map { it.title })
        assertEquals(listOf("paypal", "bank"), folders.first { it.title == "Finanzen" }.children)
        assertEquals(listOf("game2", "game1"), folders.first { it.title == "Spiele" }.children) // alphabetisch: Angry, Zelda
        assertEquals(listOf("insta", "torch"), plan.entries.filterIsInstance<PlannedEntry.App>().map { it.componentKey })
        assertEquals(2, plan.foldersCreated)
        assertEquals(4, plan.appsMoved)
        assertTrue(plan.changesAnything)
    }

    @Test
    fun `tidy is reproducible and idempotent`() {
        val first = TidyPlanner.plan(messyLayout(), apps, TidyOptions(archiveUnused = true), pageSize = 20, now = now)
        val again = TidyPlanner.plan(messyLayout().shuffled(), apps, TidyOptions(archiveUnused = true), pageSize = 20, now = now)
        assertEquals(first.entries, again.entries)

        val applied = materialize(first, messyLayout().filter { it.container == Container.DOCK })
        val second = TidyPlanner.plan(applied, apps, TidyOptions(archiveUnused = true), pageSize = 20, now = now)
        assertFalse(second.changesAnything)
        assertEquals(0, second.foldersCreated)
        assertEquals(0, second.appsMoved)
        assertEquals(0, second.appsArchived)
    }

    @Test
    fun `archive moves long unused apps into archive folder or removes them`() {
        val folder = TidyPlanner.plan(messyLayout(), apps, TidyOptions(collectIntoFolders = false, archiveUnused = true), 20, now)
        val archive = folder.entries.filterIsInstance<PlannedEntry.Folder>().single()
        assertEquals(TidyPlanner.ARCHIVE_TITLE, archive.title)
        assertEquals(listOf("paypal", "torch"), archive.children)
        assertEquals(2, folder.appsArchived)

        val removed = TidyPlanner.plan(
            messyLayout(), apps, TidyOptions(collectIntoFolders = false, archiveUnused = true, archiveMode = ArchiveMode.REMOVE), 20, now,
        )
        assertEquals(2, removed.appsRemoved)
        assertTrue(removed.entries.none { it is PlannedEntry.Folder })
        // Lücken geschlossen, Reihenfolge beibehalten
        assertEquals(listOf("game1", "game2", "insta", "bank"), removed.entries.map { (it as PlannedEntry.App).componentKey })
        assertEquals(listOf(0, 1, 2, 3), removed.entries.map { it.position })
    }

    @Test
    fun `archive is skipped without usage data and pages wrap`() {
        val noUsage = apps.mapValues { it.value.copy(lastUsed = null) }
        val plan = TidyPlanner.plan(messyLayout(), noUsage, TidyOptions(collectIntoFolders = false, archiveUnused = true), pageSize = 4, now = now)
        assertTrue(plan.usageDataMissing)
        assertEquals(0, plan.appsArchived)
        assertEquals(2, plan.pages)
        assertEquals(1 to 1, plan.entries.last().let { it.page to it.position })
    }

    /** Eigene Apps: zwei per Paketname (eine nur im Drawer), eine selbst installiert, eine fest ausgewählt. */
    private val withOwnApps = apps.mapValues { it.value.copy(packageName = "com.store.${it.key}") } + mapOf(
        "mine1" to AppMeta("MobileManager", AppCategory.TOOLS, old, packageName = "de.me.mobilemanager"),
        "mine2" to AppMeta("Notizen", AppCategory.PRODUCTIVITY, recent, packageName = "de.me.notes"),
        "apk" to AppMeta("Testapp", AppCategory.TOOLS, recent, packageName = "org.test", sideloaded = true),
    )

    private fun layoutWithOwnApps() = messyLayout() + LayoutItem(9, Container.HOME, 0, 11, "mine1")

    @Test
    fun `custom folder rules win over categories, pull drawer apps and are never archived`() {
        val rule = CustomFolderRule(id = 1, title = "Meine Apps", packagePrefixes = listOf("de.me."), includeSideloaded = true, componentKeys = setOf("torch"))
        val plan = TidyPlanner.plan(layoutWithOwnApps(), withOwnApps, TidyOptions(archiveUnused = true), 20, now, listOf(rule))
        val first = plan.entries.first() as PlannedEntry.Folder
        assertEquals("Meine Apps", first.title)
        // alphabetisch: MobileManager, Notizen, Taschenlampe, Testapp
        assertEquals(listOf("mine1", "mine2", "torch", "apk"), first.children)
        assertEquals(2, plan.appsAddedFromDrawer) // Notizen und Testapp lagen nur im Drawer
        // mine1 und torch sind alt, landen aber wegen der Regel nicht im Archiv
        val archive = plan.entries.filterIsInstance<PlannedEntry.Folder>().single { it.title == TidyPlanner.ARCHIVE_TITLE }
        assertEquals(listOf("paypal"), archive.children)

        val applied = materialize(plan, messyLayout().filter { it.container == Container.DOCK })
        assertFalse(TidyPlanner.plan(applied, withOwnApps, TidyOptions(archiveUnused = true), 20, now, listOf(rule)).changesAnything)
    }

    @Test
    fun `custom rules respect drawer switch, dock, disabled state and merge same-named category`() {
        val homeOnly = CustomFolderRule(id = 1, title = "Spiele", componentKeys = setOf("insta", "mine2"), includeDrawerApps = false)
        val plan = TidyPlanner.plan(layoutWithOwnApps(), withOwnApps, TidyOptions(), 20, now, listOf(homeOnly))
        val folders = plan.entries.filterIsInstance<PlannedEntry.Folder>()
        // Ein einziger Ordner „Spiele“: Instagram aus der Regel plus die Spiele-Kategorie, Notizen (nur Drawer) bleibt draußen.
        assertEquals(1, folders.count { it.title == "Spiele" })
        assertEquals(listOf("game2", "insta", "game1"), folders.first { it.title == "Spiele" }.children)
        assertEquals(0, plan.appsAddedFromDrawer)

        // Apps nur im Dock werden nicht auf den Startbildschirm dupliziert.
        val dockOnly = listOf(LayoutItem(1, Container.DOCK, 0, 0, "mine2"))
        val drawerRule = CustomFolderRule(id = 2, title = "Meine", packagePrefixes = listOf("de.me."))
        val fromDock = TidyPlanner.plan(dockOnly, withOwnApps, TidyOptions(), 20, now, listOf(drawerRule))
        assertEquals(listOf("mine1"), (fromDock.entries.single() as PlannedEntry.Folder).children)

        val disabled = TidyPlanner.plan(messyLayout(), withOwnApps, TidyOptions(), 20, now, listOf(drawerRule.copy(enabled = false)))
        assertEquals(TidyPlanner.plan(messyLayout(), withOwnApps, TidyOptions(), 20, now).entries, disabled.entries)
    }

    private val car = ProfileRule(1, profileId = 3, type = RuleType.BLUETOOTH, value = "AA:BB:CC:DD:EE:FF")
    private val office = ProfileRule(2, profileId = 2, type = RuleType.WIFI, value = "Firma")
    private val workTime = ProfileRule(3, profileId = 2, type = RuleType.TIME, daysMask = ProfileRule.WEEKDAYS, startMinutes = 8 * 60, endMinutes = 17 * 60)
    private val monday10 = LocalDateTime.of(2026, 11, 23, 10, 0)

    @Test
    fun `profile resolver prioritises bluetooth over wifi over time and falls back`() {
        val rules = listOf(workTime, office, car)
        assertEquals(3L, ProfileResolver.resolve(rules, LauncherContext(setOf("aa:bb:cc:dd:ee:ff"), "\"Firma\"", monday10), 1))
        assertEquals(2L, ProfileResolver.resolve(rules, LauncherContext(emptySet(), "\"Firma\"", monday10), 1))
        assertEquals(2L, ProfileResolver.resolve(rules, LauncherContext(emptySet(), null, monday10), 1))
        // Samstag, kein Auslöser → zurück zum Basisprofil
        assertEquals(1L, ProfileResolver.resolve(rules, LauncherContext(emptySet(), null, monday10.plusDays(5)), 1))
        // Deaktivierte Regel greift nicht
        assertEquals(1L, ProfileResolver.resolve(listOf(car.copy(enabled = false)), LauncherContext(setOf(car.value), null, monday10), 1))
    }

    @Test
    fun `time windows handle end exclusive and overnight ranges`() {
        assertFalse(ProfileResolver.inTimeWindow(workTime, monday10.withHour(17)))
        assertTrue(ProfileResolver.inTimeWindow(workTime, monday10.withHour(16).withMinute(59)))
        val night = workTime.copy(daysMask = 0b0010000, startMinutes = 22 * 60, endMinutes = 6 * 60) // nur Freitag
        val friday23 = LocalDateTime.of(2026, 11, 27, 23, 0)
        assertTrue(ProfileResolver.inTimeWindow(night, friday23))
        assertTrue(ProfileResolver.inTimeWindow(night, friday23.plusHours(5))) // Samstag 04:00 gehört zur Freitagnacht
        assertFalse(ProfileResolver.inTimeWindow(night, friday23.plusDays(1))) // Samstag 23:00
    }

    @Test
    fun `suggestions rank frequent and recent apps, skip unavailable ones`() {
        val history = listOf("b", "a", "gone", "a", "c", "a", "b", "d")
        assertEquals(listOf("a", "b", "c"), AppSuggestions.rank(history, setOf("a", "b", "c", "d"), 3))
        // Gleich oft genutzt: der zuletzt gestartete gewinnt.
        assertEquals(listOf("x", "y"), AppSuggestions.rank(listOf("x", "y"), setOf("x", "y"), 5))
        assertTrue(AppSuggestions.rank(emptyList(), setOf("a"), 5).isEmpty())
        assertTrue(AppSuggestions.rank(listOf("gone"), setOf("a"), 5).isEmpty())
    }
}
