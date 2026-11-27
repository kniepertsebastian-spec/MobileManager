package app.mobilemanager.launcher.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.MediaStore
import androidx.core.net.toUri
import androidx.room.withTransaction
import app.mobilemanager.launcher.domain.AppCategory
import app.mobilemanager.launcher.domain.Container
import app.mobilemanager.launcher.domain.CustomFolderRule
import app.mobilemanager.launcher.domain.LauncherProfile
import app.mobilemanager.launcher.domain.LayoutItem
import app.mobilemanager.launcher.domain.PlannedEntry
import app.mobilemanager.launcher.domain.ProfileKind
import app.mobilemanager.launcher.domain.ProfileRule
import app.mobilemanager.launcher.domain.RuleType
import app.mobilemanager.launcher.domain.TidyPlan
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

data class SnapshotInfo(val id: Long, val createdAt: Long, val label: String)

/** Verwaltet Profile, Layouts, Snapshots und Wechselregeln in der Launcher-Datenbank. */
@Singleton
class LauncherLayoutRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: LauncherDatabase,
    private val dao: LauncherDao,
    private val prefs: LauncherPreferences,
) {
    private val initMutex = Mutex()

    val profiles: Flow<List<LauncherProfile>> = dao.observeProfiles().map { list -> list.map { it.toDomain() } }

    val rules: Flow<List<ProfileRule>> = dao.observeRules().map { list -> list.map { it.toDomain() } }

    val customFolders: Flow<List<CustomFolderRule>> = dao.observeTidyFolders().map { list -> list.map { it.toDomain() } }

    fun items(profileId: Long): Flow<List<LayoutItem>> = dao.observeItems(profileId).map { list -> list.map { it.toDomain() } }

    fun latestSnapshot(profileId: Long): Flow<SnapshotInfo?> =
        dao.observeLatestSnapshot(profileId).map { it?.let { s -> SnapshotInfo(s.id, s.createdAt, s.label) } }

    suspend fun currentItems(profileId: Long): List<LayoutItem> = dao.items(profileId).map { it.toDomain() }

    // ---------------------------------------------------------------- Ersteinrichtung

    /**
     * Legt beim ersten Start die vier Standardprofile an und befüllt sie mit den installierten Apps.
     * Profile, die vorher schon in den Einstellungen angelegt wurden, sind noch leer – sie bekommen das Standard-Layout.
     */
    suspend fun ensureInitialized(apps: List<LauncherApp>, pageSize: Int) = initMutex.withLock {
        if (prefs.current().initialized || apps.isEmpty()) return@withLock
        val earlier = dao.profiles()
        val mainApps = apps.filterNot { it.isWorkProfile }
        val defaults = defaultPackages()
        val dockApps = defaults.mapNotNull { pkg -> mainApps.firstOrNull { it.packageName == pkg } }.distinct().take(DOCK_SIZE)
        val homeApps = apps.filterNot { it in dockApps }

        db.withTransaction {
            val standard = dao.insertProfile(ProfileEntity(name = "Standard", kind = ProfileKind.STANDARD.name, sortOrder = 0))
            insertSimpleLayout(standard, dockApps.map { it.key }, homeApps.map { it.key }, pageSize)

            val work = dao.insertProfile(
                ProfileEntity(
                    name = "Arbeit", kind = ProfileKind.WORK.name, sortOrder = 1,
                    hiddenCategories = AppCategory.toCsv(setOf(AppCategory.GAMES, AppCategory.SOCIAL)),
                ),
            )
            insertSimpleLayout(work, dockApps.map { it.key }, homeApps.map { it.key }, pageSize)

            val car = dao.insertProfile(ProfileEntity(name = "Auto", kind = ProfileKind.CAR.name, sortOrder = 2, largeIcons = true))
            val carApps = (dockApps.filter { it.packageName == defaults.firstOrNull() } +
                mainApps.filter { it.category == AppCategory.NAVIGATION } +
                mainApps.filter { it.category == AppCategory.MUSIC }).distinct().take(CAR_GRID)
            insertSimpleLayout(car, emptyList(), carApps.map { it.key }, CAR_GRID)

            val focus = dao.insertProfile(
                ProfileEntity(
                    name = "Fokus", kind = ProfileKind.FOCUS.name, sortOrder = 3,
                    hiddenCategories = AppCategory.toCsv(
                        setOf(AppCategory.GAMES, AppCategory.SOCIAL, AppCategory.VIDEO, AppCategory.SHOPPING, AppCategory.NEWS),
                    ),
                ),
            )
            insertSimpleLayout(focus, dockApps.map { it.key }, homeApps.map { it.key }, pageSize)

            // Beispielregel (deaktiviert): Arbeitszeit Mo–Fr 08–17 Uhr.
            dao.upsertRule(
                RuleEntity(
                    profileId = work, type = RuleType.TIME.name, value = "", label = "Mo–Fr 08:00–17:00",
                    daysMask = ProfileRule.WEEKDAYS, startMinutes = 8 * 60, endMinutes = 17 * 60, enabled = false,
                ),
            )
            earlier.forEachIndexed { index, profile ->
                if (dao.items(profile.id).isEmpty()) copyLayout(standard, profile.id)
                dao.upsertProfile(profile.copy(sortOrder = DEFAULT_PROFILE_COUNT + index))
            }
            prefs.update { current ->
                val base = current.baseProfileId.takeIf { id -> earlier.any { it.id == id } } ?: standard
                current.copy(baseProfileId = base, initialized = true)
            }
        }
    }

    private suspend fun insertSimpleLayout(profileId: Long, dock: List<String>, home: List<String>, pageSize: Int) {
        val items = dock.mapIndexed { i, key -> ItemEntity(profileId = profileId, container = Container.DOCK.code, page = 0, position = i, componentKey = key) } +
            home.mapIndexed { i, key ->
                ItemEntity(profileId = profileId, container = Container.HOME.code, page = i / pageSize, position = i % pageSize, componentKey = key)
            }
        dao.insertItems(items)
    }

    /** Telefon, SMS, Browser, Kamera – jeweils die Standard-App, falls eine festgelegt ist. */
    private fun defaultPackages(): List<String> {
        val pm = context.packageManager
        val intents = listOf(
            Intent(Intent.ACTION_DIAL),
            Intent(Intent.ACTION_SENDTO, "smsto:".toUri()),
            Intent(Intent.ACTION_VIEW, "https://example.org".toUri()).addCategory(Intent.CATEGORY_BROWSABLE),
            Intent(MediaStore.ACTION_IMAGE_CAPTURE),
        )
        return intents.mapNotNull { intent ->
            pm.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
                ?.activityInfo?.packageName?.takeIf { it != "android" }
        }
    }

    // ---------------------------------------------------------------- Bearbeiten

    /** Legt die App auf den ersten freien Platz (ab [preferredPage]). */
    suspend fun addToHome(profileId: Long, key: String, pageSize: Int, preferredPage: Int = 0) {
        val items = dao.items(profileId)
        if (items.any { it.container == Container.HOME.code && it.componentKey == key }) return
        val (page, position) = firstFreeSlot(items, pageSize, preferredPage)
        dao.insertItem(ItemEntity(profileId = profileId, container = Container.HOME.code, page = page, position = position, componentKey = key))
    }

    /** @return `false`, wenn das Dock voll ist. */
    suspend fun addToDock(profileId: Long, key: String): Boolean {
        val dock = dao.items(profileId).filter { it.container == Container.DOCK.code }
        if (dock.any { it.componentKey == key }) return true
        if (dock.size >= DOCK_SIZE) return false
        dao.insertItem(
            ItemEntity(profileId = profileId, container = Container.DOCK.code, page = 0, position = (dock.maxOfOrNull { it.position } ?: -1) + 1, componentKey = key),
        )
        return true
    }

    suspend fun removeItem(itemId: Long) = db.withTransaction {
        val item = dao.item(itemId) ?: return@withTransaction
        dao.deleteItemAndChildren(itemId)
        item.parentId?.let { deleteFolderIfEmpty(item.profileId, it) }
    }

    suspend fun moveToFolder(profileId: Long, itemId: Long, folderId: Long) = db.withTransaction {
        val items = dao.items(profileId)
        val item = items.firstOrNull { it.id == itemId } ?: return@withTransaction
        if (item.componentKey == null || item.parentId == folderId) return@withTransaction
        // Schon im Ordner vorhanden? Dann nur das Original entfernen.
        if (items.any { it.parentId == folderId && it.componentKey == item.componentKey }) {
            dao.deleteItemAndChildren(itemId)
        } else {
            val position = (items.filter { it.parentId == folderId }.maxOfOrNull { it.position } ?: -1) + 1
            dao.upsertItems(listOf(item.copy(container = Container.FOLDER.code, page = 0, position = position, parentId = folderId)))
        }
        item.parentId?.let { deleteFolderIfEmpty(profileId, it) }
    }

    /** Neuer Ordner an der Stelle der App (bzw. auf dem ersten freien Platz) mit der App darin. */
    suspend fun createFolderWith(profileId: Long, itemId: Long, title: String, pageSize: Int) = db.withTransaction {
        val items = dao.items(profileId)
        val item = items.firstOrNull { it.id == itemId } ?: return@withTransaction
        val (page, position) = if (item.container == Container.HOME.code) item.page to item.position else firstFreeSlot(items, pageSize, 0)
        val folderId = dao.insertItem(
            ItemEntity(profileId = profileId, container = Container.HOME.code, page = page, position = position, componentKey = null, title = title.ifBlank { "Ordner" }),
        )
        dao.upsertItems(listOf(item.copy(container = Container.FOLDER.code, page = 0, position = 0, parentId = folderId)))
        item.parentId?.let { deleteFolderIfEmpty(profileId, it) }
    }

    suspend fun removeFromFolder(profileId: Long, itemId: Long, pageSize: Int) = db.withTransaction {
        val items = dao.items(profileId)
        val item = items.firstOrNull { it.id == itemId } ?: return@withTransaction
        val folderId = item.parentId ?: return@withTransaction
        val (page, position) = firstFreeSlot(items, pageSize, 0)
        dao.upsertItems(listOf(item.copy(container = Container.HOME.code, page = page, position = position, parentId = null)))
        deleteFolderIfEmpty(profileId, folderId)
    }

    /** Ordner auflösen: Inhalt auf freie Plätze legen, Ordner löschen. */
    suspend fun dissolveFolder(profileId: Long, folderId: Long, pageSize: Int) = db.withTransaction {
        var items = dao.items(profileId)
        items.filter { it.parentId == folderId }.sortedBy { it.position }.forEach { child ->
            val (page, position) = firstFreeSlot(items.filterNot { it.id == folderId }, pageSize, 0)
            val moved = child.copy(container = Container.HOME.code, page = page, position = position, parentId = null)
            dao.upsertItems(listOf(moved))
            items = items.map { if (it.id == child.id) moved else it }
        }
        dao.deleteItemAndChildren(folderId)
    }

    suspend fun renameFolder(folderId: Long, title: String) = dao.renameFolder(folderId, title.ifBlank { "Ordner" })

    /** Entfernt Layout-Einträge deinstallierter Apps (nur Hauptprofil – Arbeitsprofile können pausiert sein). */
    suspend fun pruneUninstalled(installedKeys: Set<String>, mainUserSuffix: String) {
        if (installedKeys.isEmpty()) return
        val orphaned = dao.allComponentKeys().filter { it.endsWith(mainUserSuffix) && it !in installedKeys }
        if (orphaned.isEmpty()) return
        db.withTransaction {
            dao.deleteByKeys(orphaned)
            dao.profiles().forEach { profile ->
                val items = dao.items(profile.id)
                items.filter { it.componentKey == null && items.none { child -> child.parentId == it.id } }
                    .forEach { dao.deleteItemAndChildren(it.id) }
            }
        }
    }

    private suspend fun deleteFolderIfEmpty(profileId: Long, folderId: Long) {
        if (dao.items(profileId).none { it.parentId == folderId }) dao.deleteItemAndChildren(folderId)
    }

    private fun firstFreeSlot(items: List<ItemEntity>, pageSize: Int, preferredPage: Int): Pair<Int, Int> {
        val occupied = items.filter { it.container == Container.HOME.code }.map { it.page to it.position }.toSet()
        val lastPage = maxOf(preferredPage, items.filter { it.container == Container.HOME.code }.maxOfOrNull { it.page } ?: 0)
        val pages = (preferredPage..lastPage) + (0 until preferredPage)
        pages.forEach { page ->
            (0 until pageSize).forEach { pos -> if (page to pos !in occupied) return page to pos }
        }
        return lastPage + 1 to 0
    }

    // ---------------------------------------------------------------- Ordnen & Rückgängig

    suspend fun applyTidy(profileId: Long, plan: TidyPlan) = db.withTransaction {
        snapshot(profileId, "Vor dem Ordnen")
        val current = dao.items(profileId)
        current.filter { it.container != Container.DOCK.code }.forEach { dao.deleteItemAndChildren(it.id) }
        plan.entries.forEach { entry ->
            when (entry) {
                is PlannedEntry.App -> dao.insertItem(
                    ItemEntity(profileId = profileId, container = Container.HOME.code, page = entry.page, position = entry.position, componentKey = entry.componentKey),
                )
                is PlannedEntry.Folder -> {
                    val folderId = dao.insertItem(
                        ItemEntity(profileId = profileId, container = Container.HOME.code, page = entry.page, position = entry.position, componentKey = null, title = entry.title),
                    )
                    dao.insertItems(
                        entry.children.mapIndexed { i, key ->
                            ItemEntity(profileId = profileId, container = Container.FOLDER.code, page = 0, position = i, componentKey = key, parentId = folderId)
                        },
                    )
                }
            }
        }
        dao.snapshots(profileId).drop(MAX_SNAPSHOTS).forEach { dao.deleteSnapshot(it.id) }
    }

    private suspend fun snapshot(profileId: Long, label: String) {
        val snapshotId = dao.insertSnapshot(SnapshotEntity(profileId = profileId, createdAt = System.currentTimeMillis(), label = label))
        dao.insertSnapshotItems(
            dao.items(profileId).map {
                SnapshotItemEntity(snapshotId, it.id, it.container, it.page, it.position, it.componentKey, it.title, it.parentId)
            },
        )
    }

    /** Stellt das Layout vor dem letzten „Ordnen“ wieder her. */
    suspend fun undoLast(profileId: Long): Boolean = db.withTransaction {
        val snapshot = dao.snapshots(profileId).firstOrNull() ?: return@withTransaction false
        val items = dao.snapshotItems(snapshot.id)
        dao.deleteItemsOf(profileId)
        dao.insertItems(
            items.map { ItemEntity(it.originalId, profileId, it.container, it.page, it.position, it.componentKey, it.title, it.parentId) },
        )
        dao.deleteSnapshot(snapshot.id)
        true
    }

    // ---------------------------------------------------------------- Profile & Regeln

    suspend fun createProfile(name: String, copyFrom: Long?): Long = db.withTransaction {
        val order = (dao.profiles().maxOfOrNull { it.sortOrder } ?: 0) + 1
        val source = copyFrom?.let { id -> dao.profiles().firstOrNull { it.id == id } }
        val id = dao.insertProfile(
            ProfileEntity(
                name = name.ifBlank { "Profil" }, kind = ProfileKind.CUSTOM.name, sortOrder = order,
                hiddenCategories = source?.hiddenCategories ?: "", largeIcons = source?.largeIcons ?: false,
            ),
        )
        if (copyFrom != null) copyLayout(copyFrom, id)
        id
    }

    private suspend fun copyLayout(from: Long, to: Long) {
        val source = dao.items(from)
        val folderMap = mutableMapOf<Long, Long>()
        source.filter { it.componentKey == null }.forEach { folder ->
            folderMap[folder.id] = dao.insertItem(folder.copy(id = 0, profileId = to))
        }
        dao.insertItems(
            source.filter { it.componentKey != null }.map { item ->
                item.copy(id = 0, profileId = to, parentId = item.parentId?.let { folderMap[it] })
            }.filter { it.container != Container.FOLDER.code || it.parentId != null },
        )
    }

    suspend fun updateProfile(profile: LauncherProfile) {
        val existing = dao.profiles().firstOrNull { it.id == profile.id } ?: return
        dao.upsertProfile(
            existing.copy(name = profile.name.ifBlank { existing.name }, hiddenCategories = AppCategory.toCsv(profile.hiddenCategories), largeIcons = profile.largeIcons),
        )
    }

    /** Das Standardprofil kann nicht gelöscht werden. */
    suspend fun deleteProfile(profileId: Long) = db.withTransaction {
        val profile = dao.profiles().firstOrNull { it.id == profileId } ?: return@withTransaction
        if (profile.kind == ProfileKind.STANDARD.name) return@withTransaction
        dao.deleteItemsOf(profileId)
        dao.deleteRulesOf(profileId)
        dao.snapshots(profileId).forEach { dao.deleteSnapshot(it.id) }
        dao.deleteProfile(profileId)
        val standard = dao.profiles().firstOrNull { it.kind == ProfileKind.STANDARD.name }?.id ?: 0L
        prefs.update { if (it.baseProfileId == profileId) it.copy(baseProfileId = standard) else it }
    }

    suspend fun saveRule(rule: ProfileRule) = dao.upsertRule(
        RuleEntity(rule.id, rule.profileId, rule.type.name, rule.value, rule.label, rule.daysMask, rule.startMinutes, rule.endMinutes, rule.enabled),
    )

    suspend fun deleteRule(id: Long) = dao.deleteRule(id)

    suspend fun saveCustomFolder(rule: CustomFolderRule) {
        val order = if (rule.id == 0L) dao.maxTidyFolderOrder() + 1 else rule.sortOrder
        dao.upsertTidyFolder(
            TidyFolderEntity(
                id = rule.id,
                title = rule.title.trim().ifBlank { "Ordner" },
                componentKeys = rule.componentKeys.joinToString("\n"),
                packagePrefixes = rule.packagePrefixes.map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n"),
                includeSideloaded = rule.includeSideloaded,
                includeDrawerApps = rule.includeDrawerApps,
                enabled = rule.enabled,
                sortOrder = order,
            ),
        )
    }

    suspend fun deleteCustomFolder(id: Long) = dao.deleteTidyFolder(id)

    private fun TidyFolderEntity.toDomain() = CustomFolderRule(
        id = id,
        title = title,
        componentKeys = componentKeys.lines().filter { it.isNotBlank() }.toSet(),
        packagePrefixes = packagePrefixes.lines().filter { it.isNotBlank() },
        includeSideloaded = includeSideloaded,
        includeDrawerApps = includeDrawerApps,
        enabled = enabled,
        sortOrder = sortOrder,
    )

    private fun ProfileEntity.toDomain() = LauncherProfile(
        id = id,
        name = name,
        kind = runCatching { ProfileKind.valueOf(kind) }.getOrDefault(ProfileKind.CUSTOM),
        hiddenCategories = AppCategory.parseSet(hiddenCategories),
        largeIcons = largeIcons,
    )

    private fun ItemEntity.toDomain() = LayoutItem(id, Container.of(container), page, position, componentKey, title, parentId)

    private fun RuleEntity.toDomain() = ProfileRule(
        id = id,
        profileId = profileId,
        type = runCatching { RuleType.valueOf(type) }.getOrDefault(RuleType.TIME),
        value = value,
        label = label,
        daysMask = daysMask,
        startMinutes = startMinutes,
        endMinutes = endMinutes,
        enabled = enabled,
    )

    companion object {
        const val DOCK_SIZE = 5
        /** Auto-Profil: 2 Spalten × 3 Reihen große Icons. */
        const val CAR_COLUMNS = 2
        const val CAR_ROWS = 3
        const val CAR_GRID = CAR_COLUMNS * CAR_ROWS
        private const val MAX_SNAPSHOTS = 5
        private const val DEFAULT_PROFILE_COUNT = 4
    }
}
