package app.mobilemanager.launcher.ui

import android.content.Intent
import android.graphics.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.mobilemanager.launcher.data.ActiveProfile
import app.mobilemanager.launcher.data.LauncherApp
import app.mobilemanager.launcher.data.LauncherAppsRepository
import app.mobilemanager.launcher.data.LauncherContextRepository
import app.mobilemanager.launcher.data.LauncherHostBridge
import app.mobilemanager.launcher.data.LauncherLayoutRepository
import app.mobilemanager.launcher.data.LauncherPreferences
import app.mobilemanager.launcher.data.LauncherSettings
import app.mobilemanager.launcher.domain.Container
import app.mobilemanager.launcher.domain.LauncherProfile
import app.mobilemanager.launcher.domain.LayoutItem
import app.mobilemanager.launcher.domain.ProfileRule
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Eine Zelle auf dem Homescreen/Dock: App oder Ordner mit (sichtbaren) Kindern. */
data class HomeCell(
    val item: LayoutItem,
    val app: LauncherApp?,
    val children: List<FolderChild> = emptyList(),
) {
    val isFolder get() = item.isFolder
}

data class FolderChild(val item: LayoutItem, val app: LauncherApp)

data class LauncherUiState(
    val loading: Boolean = true,
    val profile: LauncherProfile? = null,
    val profiles: List<LauncherProfile> = emptyList(),
    val autoRule: ProfileRule? = null,
    val columns: Int = 4,
    val rows: Int = 5,
    val showLabels: Boolean = true,
    val largeIcons: Boolean = false,
    val pages: List<List<HomeCell>> = listOf(emptyList()),
    val dock: List<HomeCell> = emptyList(),
    val folders: List<HomeCell> = emptyList(),
    val drawerApps: List<LauncherApp> = emptyList(),
    val hiddenCount: Int = 0,
) {
    val pageSize: Int get() = columns * rows
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LauncherViewModel @Inject constructor(
    private val appsRepository: LauncherAppsRepository,
    private val layout: LauncherLayoutRepository,
    private val prefs: LauncherPreferences,
    contextRepository: LauncherContextRepository,
    private val bridge: LauncherHostBridge,
) : ViewModel() {

    private val _homeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** Home-Taste, während der Launcher schon sichtbar ist: Drawer/Ordner schließen, zur ersten Seite. */
    val homeEvents: SharedFlow<Unit> = _homeEvents.asSharedFlow()

    private val active = contextRepository.activeProfile
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val state: StateFlow<LauncherUiState> = combine(
        appsRepository.apps.filterNotNull(),
        prefs.settings,
        active.filterNotNull(),
        layout.profiles,
    ) { apps, settings, activeProfile, profiles -> Inputs(apps, settings, activeProfile, profiles) }
        .flatMapLatest { input ->
            val profile = input.profiles.firstOrNull { it.id == input.active.profileId } ?: input.profiles.firstOrNull()
            layout.items(profile?.id ?: -1L).map { items -> buildState(input, profile, items) }
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LauncherUiState())

    private data class Inputs(
        val apps: List<LauncherApp>,
        val settings: LauncherSettings,
        val active: ActiveProfile,
        val profiles: List<LauncherProfile>,
    )

    init {
        appsRepository.start()
        viewModelScope.launch { runCatching { bridge.refreshConnectedBluetooth() } }
        viewModelScope.launch {
            appsRepository.apps.filterNotNull().collect { apps ->
                layout.ensureInitialized(apps, prefs.current().pageSize)
                layout.pruneUninstalled(apps.map { it.key }.toSet(), appsRepository.mainUserSuffix)
            }
        }
    }

    private fun buildState(input: Inputs, profile: LauncherProfile?, items: List<LayoutItem>): LauncherUiState {
        val hidden = profile?.hiddenCategories.orEmpty()
        val byKey = input.apps.associateBy { it.key }
        fun visibleApp(key: String?): LauncherApp? = key?.let(byKey::get)?.takeIf { it.category !in hidden }

        val childrenByFolder = items.filter { it.container == Container.FOLDER }.groupBy { it.parentId }
        fun cellOf(item: LayoutItem): HomeCell? = if (item.isFolder) {
            val children = childrenByFolder[item.id].orEmpty().sortedBy { it.position }
                .mapNotNull { child -> visibleApp(child.componentKey)?.let { FolderChild(child, it) } }
            if (children.isEmpty()) null else HomeCell(item, null, children)
        } else {
            visibleApp(item.componentKey)?.let { HomeCell(item, it) }
        }

        val large = profile?.largeIcons == true
        val columns = if (large) LauncherLayoutRepository.CAR_COLUMNS else input.settings.columns
        val rows = if (large) LauncherLayoutRepository.CAR_ROWS else input.settings.rows
        val pageSize = columns * rows
        val homeCells = items.filter { it.container == Container.HOME && it.position < pageSize }.mapNotNull(::cellOf)
        val pageCount = (homeCells.maxOfOrNull { it.item.page } ?: 0) + 1
        val pages = (0 until pageCount).map { page -> homeCells.filter { it.item.page == page } }
        val dock = if (large) emptyList() else items.filter { it.container == Container.DOCK }.sortedBy { it.position }.mapNotNull(::cellOf)

        return LauncherUiState(
            loading = false,
            profile = profile,
            profiles = input.profiles,
            autoRule = input.active.rule,
            columns = columns,
            rows = rows,
            showLabels = input.settings.showLabels,
            largeIcons = large,
            pages = pages,
            dock = dock,
            folders = homeCells.filter { it.isFolder },
            drawerApps = input.apps.filter { it.category !in hidden },
            hiddenCount = input.apps.count { it.category in hidden },
        )
    }

    fun onHomePressed() {
        _homeEvents.tryEmit(Unit)
    }

    fun cachedIcon(key: String, sizePx: Int): ImageBitmap? = appsRepository.cachedIcon(key, sizePx)

    suspend fun icon(key: String, sizePx: Int): ImageBitmap? = appsRepository.icon(key, sizePx)

    fun launch(app: LauncherApp, bounds: Rect?) = appsRepository.launch(app, bounds)

    fun openAppInfo(app: LauncherApp) = appsRepository.openAppInfo(app)

    fun uninstallIntent(app: LauncherApp): Intent? = appsRepository.uninstallIntent(app)

    private fun withProfile(block: suspend (profileId: Long, pageSize: Int) -> Unit) {
        val current = state.value
        val profileId = current.profile?.id ?: return
        viewModelScope.launch { block(profileId, current.pageSize) }
    }

    fun addToHome(app: LauncherApp, page: Int = 0) = withProfile { id, size -> layout.addToHome(id, app.key, size, page) }

    fun addToDock(app: LauncherApp, onFull: () -> Unit) = withProfile { id, _ -> if (!layout.addToDock(id, app.key)) onFull() }

    fun remove(item: LayoutItem) = withProfile { _, _ -> layout.removeItem(item.id) }

    fun moveToFolder(item: LayoutItem, folderId: Long) = withProfile { id, _ -> layout.moveToFolder(id, item.id, folderId) }

    fun createFolder(item: LayoutItem, title: String) = withProfile { id, size -> layout.createFolderWith(id, item.id, title, size) }

    fun removeFromFolder(item: LayoutItem) = withProfile { id, size -> layout.removeFromFolder(id, item.id, size) }

    fun dissolveFolder(folder: LayoutItem) = withProfile { id, size -> layout.dissolveFolder(id, folder.id, size) }

    fun renameFolder(folder: LayoutItem, title: String) {
        viewModelScope.launch { layout.renameFolder(folder.id, title) }
    }

    /** Manuelle Profilwahl = neues Basisprofil (Regeln können es weiterhin zeitweise überschreiben). */
    fun selectBaseProfile(profileId: Long) {
        viewModelScope.launch { prefs.update { it.copy(baseProfileId = profileId) } }
    }

    suspend fun ensureLoaded() {
        appsRepository.apps.filterNotNull().first()
    }
}
