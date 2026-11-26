package app.mobilemanager.launcher.domain

import java.util.concurrent.TimeUnit

/** Was soll „Homescreen ordnen“ tun? */
data class TidyOptions(
    val collectIntoFolders: Boolean = true,
    val archiveUnused: Boolean = false,
    val archiveMode: ArchiveMode = ArchiveMode.FOLDER,
    val unusedDays: Int = 60,
)

enum class ArchiveMode { FOLDER, REMOVE }

/** Daten zu einer installierten App, die der Planer braucht. */
data class AppMeta(val label: String, val category: AppCategory, val lastUsed: Long?)

/** Ergebnis-Layout (ohne IDs) – das Repository legt daraus neue Datenbankzeilen an. */
sealed interface PlannedEntry {
    val page: Int
    val position: Int

    data class App(val componentKey: String, override val page: Int, override val position: Int) : PlannedEntry

    data class Folder(
        val title: String,
        val children: List<String>,
        override val page: Int,
        override val position: Int,
    ) : PlannedEntry
}

data class TidyPlan(
    val entries: List<PlannedEntry>,
    val foldersCreated: Int,
    val appsMoved: Int,
    val appsArchived: Int,
    val appsRemoved: Int,
    val usageDataMissing: Boolean,
    /** Unterscheidet sich das Ergebnis überhaupt vom aktuellen Homescreen? */
    val changesAnything: Boolean,
) {
    val pages: Int get() = (entries.maxOfOrNull { it.page } ?: 0) + 1
}

/**
 * Meilenstein 3.2: One-Click-Sortierung. Rein funktional und deterministisch:
 * gleiche Eingabe → gleiches Layout. Ein zweiter Durchlauf auf dem Ergebnis ändert nichts mehr.
 * Das Dock bleibt unangetastet.
 */
object TidyPlanner {

    const val ARCHIVE_TITLE = "Archiv"

    /** Ordner erst ab so vielen Apps einer Kategorie – Einzel-Apps bleiben lose liegen. */
    const val MIN_FOLDER_SIZE = 2

    fun plan(
        items: List<LayoutItem>,
        apps: Map<String, AppMeta>,
        options: TidyOptions,
        pageSize: Int,
        now: Long,
    ): TidyPlan {
        require(pageSize > 0)
        val home = items.filter { it.container == Container.HOME }.sortedWith(compareBy({ it.page }, { it.position }))
        val childrenOf = items.filter { it.container == Container.FOLDER }
            .groupBy { it.parentId }
            .mapValues { (_, list) -> list.sortedBy { it.position } }

        // Alle Apps des Homescreens (lose und aus Ordnern) in Anzeigereihenfolge, ohne deinstallierte.
        val homeApps = buildList {
            home.forEach { item ->
                if (item.isFolder) childrenOf[item.id].orEmpty().forEach { child -> child.componentKey?.let(::add) }
                else item.componentKey?.let(::add)
            }
        }.distinct().filter { it in apps }

        // Vorheriger Ordner je App (für "verschoben"-Zählung).
        val previousFolderOf = buildMap {
            home.filter { it.isFolder }.forEach { folder ->
                childrenOf[folder.id].orEmpty().forEach { child -> child.componentKey?.let { put(it, folder.title) } }
            }
        }

        val usageDataMissing = options.archiveUnused && apps.values.none { it.lastUsed != null }
        val cutoff = now - TimeUnit.DAYS.toMillis(options.unusedDays.toLong())
        val unused = if (options.archiveUnused && !usageDataMissing) {
            homeApps.filter { key -> (apps.getValue(key).lastUsed ?: 0L) < cutoff }.toSet()
        } else emptySet()

        val keep = homeApps.filterNot { it in unused }
        val byLabel = compareBy<String>({ apps.getValue(it).label.lowercase() }, { it })

        val sequence = mutableListOf<Pair<String?, List<String>>>() // Titel (null = lose App) → Apps
        if (options.collectIntoFolders) {
            val groups = keep.groupBy { apps.getValue(it).category }
            val loose = mutableListOf<String>()
            AppCategory.entries.forEach { category ->
                val members = groups[category].orEmpty().sortedWith(byLabel)
                when {
                    members.size >= MIN_FOLDER_SIZE -> sequence += category.label to members
                    else -> loose += members
                }
            }
            loose.sortedWith(byLabel).forEach { sequence += null to listOf(it) }
        } else {
            // Ohne Sammeln: vorhandene Struktur behalten, nur archivierte Apps herausnehmen und Lücken schließen.
            home.forEach { item ->
                if (item.isFolder) {
                    val members = childrenOf[item.id].orEmpty().mapNotNull { it.componentKey }.filter { it in apps && it !in unused }
                    if (members.isNotEmpty()) sequence += (item.title ?: "Ordner") to members
                } else if (item.componentKey != null && item.componentKey in apps && item.componentKey !in unused) {
                    sequence += null to listOf(item.componentKey)
                }
            }
        }
        val archived = unused.sortedWith(byLabel)
        if (archived.isNotEmpty() && options.archiveMode == ArchiveMode.FOLDER) sequence += ARCHIVE_TITLE to archived

        val entries = sequence.mapIndexed { index, (title, members) ->
            val page = index / pageSize
            val position = index % pageSize
            if (title == null) PlannedEntry.App(members.single(), page, position)
            else PlannedEntry.Folder(title, members, page, position)
        }

        val previousFolderTitles = home.filter { it.isFolder }.mapNotNull { it.title }.toSet()
        val newFolders = entries.filterIsInstance<PlannedEntry.Folder>().count { it.title !in previousFolderTitles }
        val moved = entries.filterIsInstance<PlannedEntry.Folder>().filter { it.title != ARCHIVE_TITLE }.sumOf { folder ->
            folder.children.count { previousFolderOf[it] != folder.title }
        }
        val current = home.mapNotNull { item ->
            if (item.isFolder) (item.title ?: "Ordner") to childrenOf[item.id].orEmpty().mapNotNull { it.componentKey }
            else item.componentKey?.let { null to listOf(it) }
        }
        val compacted = home.withIndex().all { (index, item) -> item.page == index / pageSize && item.position == index % pageSize }
        return TidyPlan(
            entries = entries,
            foldersCreated = newFolders,
            appsMoved = moved,
            appsArchived = if (options.archiveMode == ArchiveMode.FOLDER) archived.count { previousFolderOf[it] != ARCHIVE_TITLE } else 0,
            appsRemoved = if (options.archiveMode == ArchiveMode.REMOVE) archived.size else 0,
            usageDataMissing = usageDataMissing,
            changesAnything = current != sequence || !compacted,
        )
    }
}
