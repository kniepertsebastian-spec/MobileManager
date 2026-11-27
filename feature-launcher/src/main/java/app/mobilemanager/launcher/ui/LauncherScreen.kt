package app.mobilemanager.launcher.ui

import android.app.SearchManager
import android.content.Intent
import android.graphics.Rect
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import app.mobilemanager.launcher.data.GestureAction
import app.mobilemanager.launcher.data.QuickAction
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mobilemanager.launcher.data.LauncherApp
import app.mobilemanager.launcher.data.LauncherShortcut
import app.mobilemanager.launcher.domain.LayoutItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private data class MenuAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** Callbacks, die jede App-Zelle braucht. */
private class CellActions(
    val launch: (LauncherApp, Rect?) -> Unit,
    val shortcut: (LauncherShortcut, Rect?) -> Unit,
    val info: (LauncherApp) -> Unit,
    val uninstall: (LauncherApp) -> Unit,
)

/** Geschwindigkeit (Anteil der Bildschirmhöhe pro Sekunde), ab der ein Wisch den Drawer sicher öffnet/schließt. */
private const val FLING_PROGRESS_PER_SECOND = 1.5f

/**
 * Startbildschirm im Stil des Pixel Launchers: „Auf einen Blick“ oben, Seiten mit Raster, Dock ohne
 * Hintergrund und Suchleiste unten. Wischen nach oben zieht den App-Drawer mit dem Finger hoch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen(
    viewModel: LauncherViewModel,
    onStartIntent: (Intent) -> Unit,
    onOpenSettings: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var openFolderId by rememberSaveable { mutableStateOf<Long?>(null) }
    var profileSheet by remember { mutableStateOf(false) }
    var moveTarget by remember { mutableStateOf<LayoutItem?>(null) }
    var focusSearch by remember { mutableStateOf(false) }
    var vaultOpen by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState { state.pages.size }
    val scope = rememberCoroutineScope()
    val wallpaperText = rememberWallpaperText()

    // Drawer-Fortschritt: 0 = zu, 1 = offen. Folgt beim Wischen dem Finger.
    var progress by rememberSaveable { mutableFloatStateOf(0f) }
    var height by remember { mutableFloatStateOf(1f) }
    var settleJob by remember { mutableStateOf<Job?>(null) }
    fun settle(target: Float, velocity: Float = 0f) {
        settleJob?.cancel()
        settleJob = scope.launch {
            animate(progress, target, velocity, spring(stiffness = Spring.StiffnessMediumLow)) { value, _ -> progress = value }
        }
    }
    fun dragBy(dy: Float) {
        settleJob?.cancel()
        progress = (progress - dy / height).coerceIn(0f, 1f)
    }
    /** [velocityY] in px/s, positiv = nach unten. */
    fun release(velocityY: Float) {
        val v = -velocityY / height
        val open = when {
            v > FLING_PROGRESS_PER_SECOND -> true
            v < -FLING_PROGRESS_PER_SECOND -> false
            else -> progress > 0.5f
        }
        settle(if (open) 1f else 0f, v)
    }
    val drawerOpen = progress > 0f
    fun openDrawer(withSearch: Boolean) {
        focusSearch = withSearch
        settle(1f)
    }

    // Phase 4.7: frei belegbare Gesten (Doppeltippen, Wischen nach unten).
    fun runGesture(action: GestureAction) {
        when {
            action == GestureAction.SEARCH -> openDrawer(withSearch = true)
            !viewModel.performGesture(action) ->
                onMessage("Für diese Geste die Bedienungshilfe „MobileManager – App-Sperre, Fokus-Bremse & Gesten“ aktivieren")
        }
    }
    val swipeDownAction by rememberUpdatedState(state.swipeDownAction)
    val launcherActivity = LocalActivity.current
    fun openVault() {
        val activity = launcherActivity ?: return
        authenticate(activity, "Tresor öffnen") { vaultOpen = true }
    }

    LaunchedEffect(Unit) {
        viewModel.homeEvents.collect {
            settle(0f)
            openFolderId = null
            profileSheet = false
            if (pagerState.currentPage != 0) pagerState.animateScrollToPage(0)
        }
    }
    BackHandler(enabled = drawerOpen || openFolderId != null) {
        if (openFolderId != null) openFolderId = null else settle(0f)
    }

    // Statusleisten-Symbole dunkel auf hellem Hintergrund bzw. passend zum offenen Drawer.
    val activity = LocalActivity.current
    val lightBars = if (progress > 0.5f) !isSystemInDarkTheme() else wallpaperText.darkText
    SideEffect {
        activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = lightBars
                isAppearanceLightNavigationBars = lightBars
            }
        }
    }

    val actions = remember(viewModel) {
        CellActions(
            launch = { app, bounds -> if (!viewModel.launch(app, bounds)) onMessage("App lässt sich nicht starten") },
            shortcut = { shortcut, bounds -> if (!viewModel.startShortcut(shortcut, bounds)) onMessage("Shortcut lässt sich nicht öffnen") },
            info = viewModel::openAppInfo,
            uninstall = { app -> viewModel.uninstallIntent(app)?.let(onStartIntent) },
        )
    }
    val homeMenu = buildList {
        add(MenuAction("Hintergrund & Stil", Icons.Default.Wallpaper) { onStartIntent(Intent(Intent.ACTION_SET_WALLPAPER)) })
        add(MenuAction("Homescreen ordnen", Icons.AutoMirrored.Filled.Sort) { onOpenSettings(LauncherSettingsActivity.SCREEN_TIDY) })
        if (state.profiles.size > 1) add(MenuAction("Profil wechseln", Icons.Default.SpaceDashboard) { profileSheet = true })
        add(MenuAction("Startbildschirm-Einstellungen", Icons.Default.Settings) { onOpenSettings(LauncherSettingsActivity.SCREEN_OVERVIEW) })
    }
    val profileLabel = state.profile?.takeIf { state.profiles.size > 1 || state.autoRule != null }
        ?.let { it.name + if (state.autoRule != null) " · automatisch" else "" }

    CompositionLocalProvider(LocalWallpaperText provides wallpaperText) {
        Box(Modifier.fillMaxSize().onSizeChanged { height = it.height.toFloat().coerceAtLeast(1f) }) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = 1f - progress
                        translationY = -progress * size.height * 0.08f
                    }
                    .pointerInput(Unit) {
                        val tracker = VelocityTracker()
                        var pulledDown = 0f
                        detectVerticalDragGestures(
                            onDragStart = {
                                tracker.resetTracking()
                                focusSearch = false
                                pulledDown = 0f
                            },
                            onDragEnd = {
                                val swipeDown = pulledDown > SWIPE_DOWN_THRESHOLD_PX && progress == 0f
                                release(tracker.calculateVelocity().y)
                                if (swipeDown && swipeDownAction != GestureAction.NONE) runGesture(swipeDownAction)
                            },
                            onDragCancel = { release(0f) },
                            onVerticalDrag = { change, dy ->
                                tracker.addPosition(change.uptimeMillis, change.position)
                                if (progress == 0f && dy > 0f) pulledDown += dy else if (dy < 0f) pulledDown = 0f
                                dragBy(dy)
                                change.consume()
                            },
                        )
                    },
            ) {
                // Dezente Abdunklung hinter Status- und Navigationsleiste wie beim Pixel Launcher.
                if (!wallpaperText.darkText) {
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(
                                0f to Color.Black.copy(alpha = 0.18f),
                                0.12f to Color.Transparent,
                                0.85f to Color.Transparent,
                                1f to Color.Black.copy(alpha = 0.18f),
                            ),
                        ),
                    )
                }
                Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    AtAGlance(
                        profileLabel = profileLabel,
                        onStartIntent = onStartIntent,
                        onProfileClick = { profileSheet = true },
                        modifier = Modifier
                            .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp)
                            .graphicsLayer {
                                // Wie das Pixel-Widget nur auf der ersten Seite sichtbar.
                                alpha = (1f - abs(pagerState.currentPage + pagerState.currentPageOffsetFraction)).coerceIn(0f, 1f)
                            },
                    )
                    if (state.textOnly) {
                        TextHome(
                            state = state,
                            actions = actions,
                            homeMenu = homeMenu,
                            onDoubleTap = { runGesture(state.doubleTapAction) },
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                        )
                    } else {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.weight(1f).fillMaxWidth(),
                            key = { it },
                        ) { page ->
                            HomePage(
                                cells = state.pages.getOrElse(page) { emptyList() },
                                state = state,
                                viewModel = viewModel,
                                actions = actions,
                                homeMenu = homeMenu,
                                onOpenFolder = { openFolderId = it.id },
                                onMoveToFolder = { moveTarget = it },
                                onDoubleTap = { runGesture(state.doubleTapAction) },
                            )
                        }
                        if (state.pages.size > 1) PageIndicator(state.pages.size, pagerState.currentPage)
                        if (state.dock.isNotEmpty()) {
                            Dock(state.dock, viewModel, actions, onOpenFolder = { openFolderId = it.id })
                        }
                    }
                    SearchPill(
                        onSearch = { openDrawer(withSearch = true) },
                        onVoice = { onStartIntent(Intent(RecognizerIntent.ACTION_WEB_SEARCH)) },
                    )
                }
            }

            if (drawerOpen) {
                AppDrawer(
                    state = state,
                    viewModel = viewModel,
                    actions = actions,
                    focusSearch = focusSearch,
                    progress = { progress },
                    onDrag = ::dragBy,
                    onRelease = ::release,
                    onClose = { settle(0f) },
                    onWebSearch = { query ->
                        onStartIntent(Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query))
                        settle(0f)
                    },
                    onAddToHome = { app ->
                        viewModel.addToHome(app, pagerState.currentPage)
                        onMessage("${app.label} zum Startbildschirm hinzugefügt")
                    },
                    onAddToDock = { app -> viewModel.addToDock(app) { onMessage("Das Dock ist voll (max. 5 Apps)") } },
                    onAddToVault = { app ->
                        viewModel.addToVault(app)
                        onMessage("${app.label} liegt jetzt im Tresor – Suche „tresor“ öffnet ihn")
                    },
                    onOpenVault = ::openVault,
                    onQuickAction = { action ->
                        settle(0f)
                        if (action.intent != null) onStartIntent(action.intent) else viewModel.runQuickAction(action, onMessage)
                    },
                    onStartIntent = { intent ->
                        onStartIntent(intent)
                        settle(0f)
                    },
                    modifier = Modifier.graphicsLayer {
                        translationY = (1f - progress) * size.height
                        alpha = (progress * 3f).coerceAtMost(1f)
                    },
                )
            }
        }
    }

    val folder = state.folders.firstOrNull { it.item.id == openFolderId }
    LaunchedEffect(folder == null, state.loading) {
        if (openFolderId != null && folder == null && !state.loading) openFolderId = null
    }
    if (folder != null) {
        FolderDialog(
            folder = folder,
            viewModel = viewModel,
            actions = actions,
            onDismiss = { openFolderId = null },
        )
    }

    moveTarget?.let { target ->
        MoveToFolderDialog(
            folders = state.folders.map { it.item },
            onDismiss = { moveTarget = null },
            onMove = { folderId ->
                viewModel.moveToFolder(target, folderId)
                moveTarget = null
            },
            onCreate = { title ->
                viewModel.createFolder(target, title)
                moveTarget = null
            },
        )
    }

    if (vaultOpen) {
        ModalBottomSheet(onDismissRequest = { vaultOpen = false }) {
            Text("Tresor", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            if (state.vaultApps.isEmpty()) {
                Text(
                    "Leer. Apps im Drawer lange drücken → „In den Tresor“.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            state.vaultApps.forEach { app ->
                ListItem(
                    leadingContent = { AppIconImage(app.key, 40.dp, viewModel) },
                    trailingContent = {
                        IconButton(onClick = { viewModel.removeFromVault(app) }) {
                            Icon(Icons.Default.LockOpen, contentDescription = "Aus dem Tresor nehmen")
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable {
                        vaultOpen = false
                        actions.launch(app, null)
                    },
                ) { Text(app.label) }
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (profileSheet) {
        ModalBottomSheet(onDismissRequest = { profileSheet = false }) {
            Text(
                "Profil wechseln",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            state.profiles.forEach { profile ->
                SheetItem(
                    icon = if (profile.id == state.profile?.id) Icons.Default.Check else Icons.Default.SpaceDashboard,
                    title = profile.name,
                    subtitle = if (profile.id == state.profile?.id && state.autoRule != null) "Automatisch aktiv" else null,
                ) {
                    profileSheet = false
                    viewModel.selectBaseProfile(profile.id)
                    scope.launch { pagerState.scrollToPage(0) }
                }
            }
            SheetItem(Icons.Default.Settings, "Profile verwalten", "Anlegen, umbenennen und Wechselregeln") {
                profileSheet = false
                onOpenSettings(LauncherSettingsActivity.SCREEN_PROFILES)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SheetItem(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    ListItem(
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    ) { Text(title) }
}

@Composable
private fun HomePage(
    cells: List<HomeCell>,
    state: LauncherUiState,
    viewModel: LauncherViewModel,
    actions: CellActions,
    homeMenu: List<MenuAction>,
    onOpenFolder: (LayoutItem) -> Unit,
    onMoveToFolder: (LayoutItem) -> Unit,
    onDoubleTap: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val byPosition = remember(cells) { cells.associateBy { it.item.position } }
    val iconSize = if (state.largeIcons) 96.dp else 56.dp
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { onDoubleTap() },
                        onLongPress = { at ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuAt = at
                        },
                    )
                }
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.SpaceEvenly,
        ) {
            repeat(state.rows) { row ->
                Row(Modifier.fillMaxWidth().weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    repeat(state.columns) { column ->
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            val cell = byPosition[row * state.columns + column]
                            if (cell != null) {
                                if (cell.isFolder) {
                                    FolderTile(
                                        cell = cell,
                                        iconSize = iconSize,
                                        showLabel = state.showLabels,
                                        viewModel = viewModel,
                                        onClick = { onOpenFolder(cell.item) },
                                        menu = listOf(
                                            MenuAction("Öffnen", Icons.Default.Folder) { onOpenFolder(cell.item) },
                                            MenuAction("Ordner auflösen", Icons.Default.FolderOff) { viewModel.dissolveFolder(cell.item) },
                                            MenuAction("Ordner entfernen", Icons.Default.Delete) { viewModel.remove(cell.item) },
                                        ),
                                    )
                                } else if (cell.app != null) {
                                    val app = cell.app
                                    AppTile(
                                        app = app,
                                        iconSize = iconSize,
                                        showLabel = state.showLabels || state.largeIcons,
                                        large = state.largeIcons,
                                        viewModel = viewModel,
                                        onClick = { bounds -> actions.launch(app, bounds) },
                                        onShortcut = actions.shortcut,
                                        menu = buildList {
                                            add(MenuAction("App-Info", Icons.Default.Info) { actions.info(app) })
                                            add(MenuAction("In Ordner verschieben", Icons.Default.CreateNewFolder) { onMoveToFolder(cell.item) })
                                            add(MenuAction("Vom Startbildschirm entfernen", Icons.Default.RemoveCircleOutline) { viewModel.remove(cell.item) })
                                            if (!app.isWorkProfile) add(MenuAction("Deinstallieren", Icons.Default.Delete) { actions.uninstall(app) })
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        menuAt?.let { at ->
            Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }) {
                CellMenu(expanded = true, actions = homeMenu, onDismiss = { menuAt = null })
            }
        }
    }
}

/** Dock wie beim Pixel: eine Reihe Icons ohne Hintergrund und ohne Namen. */
@Composable
private fun Dock(cells: List<HomeCell>, viewModel: LauncherViewModel, actions: CellActions, onOpenFolder: (LayoutItem) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        cells.forEach { cell ->
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                val app = cell.app
                if (cell.isFolder) {
                    FolderTile(cell, 56.dp, false, viewModel, onClick = { onOpenFolder(cell.item) }, menu = emptyList())
                } else if (app != null) {
                    AppTile(
                        app = app,
                        iconSize = 56.dp,
                        showLabel = false,
                        large = false,
                        viewModel = viewModel,
                        onClick = { bounds -> actions.launch(app, bounds) },
                        onShortcut = actions.shortcut,
                        menu = buildList {
                            add(MenuAction("App-Info", Icons.Default.Info) { actions.info(app) })
                            add(MenuAction("Aus dem Dock entfernen", Icons.Default.RemoveCircleOutline) { viewModel.remove(cell.item) })
                            if (!app.isWorkProfile) add(MenuAction("Deinstallieren", Icons.Default.Delete) { actions.uninstall(app) })
                        },
                    )
                }
            }
        }
    }
}

/** Suchleiste unter dem Dock: tippen = Apps/Web durchsuchen, Mikrofon = Sprachsuche. */
@Composable
private fun SearchPill(onSearch: () -> Unit, onVoice: () -> Unit) {
    Surface(
        onClick = onSearch,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp).height(52.dp),
    ) {
        Row(Modifier.padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, contentDescription = "Suchen", tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onVoice) {
                Icon(Icons.Default.Mic, contentDescription = "Sprachsuche", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PageIndicator(count: Int, current: Int) {
    val color = LocalWallpaperText.current.color
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.Center) {
        repeat(count) { index ->
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(color.copy(alpha = if (index == current) 0.95f else 0.4f)),
            )
        }
    }
}

@Composable
private fun AppIconImage(key: String, size: Dp, viewModel: LauncherViewModel) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val uiState = viewModel.state.collectAsStateWithLifecycle().value
    val themed = uiState.themedIcons
    val icon by produceState(initialValue = viewModel.cachedIcon(key, px), key, px, themed) {
        value = viewModel.cachedIcon(key, px) ?: viewModel.icon(key, px)
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(
            bitmap,
            contentDescription = null,
            modifier = Modifier.size(size),
            colorFilter = if (uiState.grayscale) GRAYSCALE_FILTER else null,
        )
    } else {
        Box(Modifier.size(size).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)))
    }
}

@Composable
private fun AppTile(
    app: LauncherApp,
    iconSize: Dp,
    showLabel: Boolean,
    large: Boolean,
    viewModel: LauncherViewModel,
    onClick: (Rect?) -> Unit,
    menu: List<MenuAction>,
    onShortcut: ((LauncherShortcut, Rect?) -> Unit)? = null,
    labelStyle: TextStyle = LocalWallpaperText.current.style(),
) {
    var menuOpen by remember { mutableStateOf(false) }
    val bounds = remember { arrayOfNulls<Rect>(1) }
    val haptic = LocalHapticFeedback.current
    val shortcutIconPx = with(LocalDensity.current) { 24.dp.roundToPx() }
    val shortcuts by produceState(emptyList<LauncherShortcut>(), menuOpen, app.key) {
        value = if (menuOpen && onShortcut != null) viewModel.shortcuts(app, shortcutIconPx) else emptyList()
    }
    Box {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .onGloballyPositioned { coords ->
                    val r = coords.boundsInWindow()
                    bounds[0] = Rect(r.left.toInt(), r.top.toInt(), r.right.toInt(), r.bottom.toInt())
                }
                .combinedClickable(
                    onClick = { onClick(bounds[0]) },
                    onLongClick = if (menu.isEmpty()) null else {
                        {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuOpen = true
                        }
                    },
                )
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppIconImage(app.key, iconSize, viewModel)
            if (showLabel) {
                Text(
                    app.label,
                    style = if (large) labelStyle.copy(fontSize = 16.sp) else labelStyle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(iconSize + 20.dp).padding(top = 6.dp),
                )
            }
        }
        CellMenu(
            expanded = menuOpen,
            actions = menu,
            onDismiss = { menuOpen = false },
            shortcuts = shortcuts,
            onShortcut = { shortcut -> onShortcut?.invoke(shortcut, bounds[0]) },
        )
    }
}

/** Ordner wie beim Pixel: runde, helle Fläche mit bis zu vier Mini-Icons. */
@Composable
private fun FolderTile(
    cell: HomeCell,
    iconSize: Dp,
    showLabel: Boolean,
    viewModel: LauncherViewModel,
    onClick: () -> Unit,
    menu: List<MenuAction>,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Box {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (menu.isEmpty()) null else {
                        {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            menuOpen = true
                        }
                    },
                )
                .padding(4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val mini = iconSize * 0.34f
            Box(
                Modifier.size(iconSize).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(iconSize * 0.04f)) {
                    cell.children.take(4).chunked(2).forEach { rowItems ->
                        Row(horizontalArrangement = Arrangement.spacedBy(iconSize * 0.04f)) {
                            rowItems.forEach { child -> AppIconImage(child.app.key, mini, viewModel) }
                        }
                    }
                }
            }
            if (showLabel) {
                Text(
                    cell.item.title ?: "Ordner",
                    style = LocalWallpaperText.current.style(),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(iconSize + 20.dp).padding(top = 6.dp),
                )
            }
        }
        CellMenu(menuOpen, menu, onDismiss = { menuOpen = false })
    }
}

@Composable
private fun CellMenu(
    expanded: Boolean,
    actions: List<MenuAction>,
    onDismiss: () -> Unit,
    shortcuts: List<LauncherShortcut> = emptyList(),
    onShortcut: (LauncherShortcut) -> Unit = {},
) {
    DropdownMenu(
        expanded = expanded && (actions.isNotEmpty() || shortcuts.isNotEmpty()),
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        shortcuts.forEach { shortcut ->
            DropdownMenuItem(
                text = { Text(shortcut.label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingIcon = {
                    val icon = shortcut.icon
                    if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(24.dp))
                    else Box(Modifier.size(24.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant))
                },
                onClick = {
                    onDismiss()
                    onShortcut(shortcut)
                },
            )
        }
        if (shortcuts.isNotEmpty() && actions.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = 4.dp))
        actions.forEach { action ->
            DropdownMenuItem(
                text = { Text(action.label) },
                leadingIcon = { Icon(action.icon, contentDescription = null) },
                onClick = {
                    onDismiss()
                    action.onClick()
                },
            )
        }
    }
}

/**
 * App-Drawer im Pixel-Stil: Suchleiste oben, eine Reihe App-Vorschläge, dann alle Apps alphabetisch.
 * Wird oben weiter nach unten gezogen, folgt er dem Finger und schließt sich.
 */
@Composable
private fun AppDrawer(
    state: LauncherUiState,
    viewModel: LauncherViewModel,
    actions: CellActions,
    focusSearch: Boolean,
    progress: () -> Float,
    onDrag: (Float) -> Unit,
    onRelease: (Float) -> Unit,
    onClose: () -> Unit,
    onWebSearch: (String) -> Unit,
    onAddToHome: (LauncherApp) -> Unit,
    onAddToDock: (LauncherApp) -> Unit,
    onAddToVault: (LauncherApp) -> Unit,
    onOpenVault: () -> Unit,
    onQuickAction: (QuickAction) -> Unit,
    onStartIntent: (Intent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val queryState = rememberTextFieldState()
    val query = queryState.text.toString()
    val trimmed = query.trim()
    val filtered = remember(state.drawerApps, trimmed) {
        if (trimmed.isEmpty()) state.drawerApps
        else state.drawerApps.filter { it.label.contains(trimmed, ignoreCase = true) || it.packageName.contains(trimmed, ignoreCase = true) }
    }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(focusSearch) {
        if (focusSearch) {
            focusRequester.requestFocus()
            keyboard?.show()
        }
    }
    DisposableEffect(Unit) { onDispose { keyboard?.hide() } }

    val currentDrag by rememberUpdatedState(onDrag)
    val currentRelease by rememberUpdatedState(onRelease)
    val currentProgress by rememberUpdatedState(progress)
    val pullToClose = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.UserInput && available.y < 0f && currentProgress() < 1f) {
                    currentDrag(available.y)
                    Offset(0f, available.y)
                } else {
                    Offset.Zero
                }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    currentDrag(available.y)
                    Offset(0f, available.y)
                } else {
                    Offset.Zero
                }

            override suspend fun onPreFling(available: Velocity): Velocity =
                if (currentProgress() < 1f) {
                    currentRelease(available.y)
                    available
                } else {
                    Velocity.Zero
                }
        }
    }
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
    val columns = if (state.largeIcons) 4 else state.columns
    val fullSpan: LazyGridItemSpanScope.() -> GridItemSpan = { GridItemSpan(maxLineSpan) }

    fun menuFor(app: LauncherApp) = buildList {
        add(MenuAction("Zum Startbildschirm", Icons.Default.Home) { onAddToHome(app) })
        if (!state.largeIcons) add(MenuAction("Ins Dock", Icons.Default.ViewWeek) { onAddToDock(app) })
        add(MenuAction("App-Info", Icons.Default.Info) { actions.info(app) })
        add(MenuAction("In den Tresor", Icons.Default.Lock) { onAddToVault(app) })
        if (!app.isWorkProfile) add(MenuAction("Deinstallieren", Icons.Default.Delete) { actions.uninstall(app) })
    }

    // Phase 4.7: Universal-Suche – Aktionen der Haupt-App und Android-Einstellungen.
    val quickActions = remember { viewModel.quickActions() }
    val actionMatches = remember(trimmed) { UniversalSearch.matchActions(quickActions, trimmed) }
    val settingsMatches = remember(trimmed) { UniversalSearch.matchSettings(trimmed) }

    @Composable
    fun DrawerTile(app: LauncherApp) {
        Box(contentAlignment = Alignment.Center) {
            AppTile(
                app = app,
                iconSize = 52.dp,
                showLabel = true,
                large = false,
                viewModel = viewModel,
                labelStyle = labelStyle,
                onClick = { bounds ->
                    actions.launch(app, bounds)
                    onClose()
                },
                onShortcut = { shortcut, bounds ->
                    actions.shortcut(shortcut, bounds)
                    onClose()
                },
                menu = menuFor(app),
            )
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        val tracker = VelocityTracker()
                        detectVerticalDragGestures(
                            onDragStart = { tracker.resetTracking() },
                            onDragEnd = { currentRelease(tracker.calculateVelocity().y) },
                            onDragCancel = { currentRelease(0f) },
                            onVerticalDrag = { change, dy ->
                                tracker.addPosition(change.uptimeMillis, change.position)
                                currentDrag(dy)
                                change.consume()
                            },
                        )
                    }
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Row(Modifier.padding(start = 18.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Box(Modifier.weight(1f).padding(start = 14.dp), contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) {
                                Text("Apps und Web durchsuchen", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            BasicTextField(
                                state = queryState,
                                lineLimits = TextFieldLineLimits.SingleLine,
                                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                onKeyboardAction = {
                                    val first = filtered.firstOrNull()
                                    when {
                                        trimmed.isEmpty() -> Unit
                                        first != null -> {
                                            actions.launch(first, null)
                                            onClose()
                                        }
                                        else -> onWebSearch(trimmed)
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                            )
                        }
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { queryState.edit { replace(0, length, "") } }) { Icon(Icons.Default.Close, contentDescription = "Suche löschen") }
                        } else {
                            Spacer(Modifier.width(12.dp))
                        }
                    }
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier.fillMaxSize().nestedScroll(pullToClose),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (trimmed.isEmpty() && state.suggestions.isNotEmpty()) {
                    items(state.suggestions.take(columns), key = { "suggest:" + it.key }) { app -> DrawerTile(app) }
                    item(key = "suggest-divider", span = fullSpan) {
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
                if (trimmed.isEmpty() && state.hiddenCount > 0 && state.profile != null) {
                    item(key = "hidden-hint", span = fullSpan) {
                        Text(
                            "${state.hiddenCount} Apps sind im Profil „${state.profile.name}“ ausgeblendet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
                items(filtered, key = { it.key }) { app -> DrawerTile(app) }
                if (trimmed.isNotEmpty()) {
                    if (trimmed.equals(VAULT_KEYWORD, ignoreCase = true)) {
                        item(key = "vault", span = fullSpan) {
                            SearchResult(Icons.Default.Lock, "Tresor öffnen") { onOpenVault() }
                        }
                    }
                    actionMatches.forEach { action ->
                        item(key = "action:" + action.id, span = fullSpan) {
                            SearchResult(Icons.Default.Bolt, action.label) { onQuickAction(action) }
                        }
                    }
                    settingsMatches.forEach { entry ->
                        item(key = "setting:" + entry.label, span = fullSpan) {
                            SearchResult(Icons.Default.Settings, entry.label, "Einstellungen") { onStartIntent(Intent(entry.action)) }
                        }
                    }
                    if (filtered.isEmpty()) {
                        item(key = "no-apps", span = fullSpan) {
                            Text(
                                "Keine Apps gefunden",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
                        }
                    }
                    item(key = "web-search", span = fullSpan) {
                        ListItem(
                            leadingContent = { Icon(Icons.Default.Public, contentDescription = null) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable { onWebSearch(trimmed) },
                        ) { Text("Im Web nach „$trimmed“ suchen") }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResult(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        supportingContent = subtitle?.let { { Text(it) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    ) { Text(title) }
}

/** Phase 4.7: ablenkungsfreie Textliste statt Icon-Raster (Homescreen- und Dock-Apps). */
@Composable
private fun TextHome(
    state: LauncherUiState,
    actions: CellActions,
    homeMenu: List<MenuAction>,
    onDoubleTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptic = LocalHapticFeedback.current
    var menuAt by remember { mutableStateOf<Offset?>(null) }
    val apps = remember(state.pages, state.dock) {
        (state.pages.flatten() + state.dock).flatMap { cell -> cell.app?.let(::listOf) ?: cell.children.map { it.app } }
            .distinctBy { it.key }
    }
    val text = LocalWallpaperText.current
    Box(
        modifier.pointerInput(Unit) {
            detectTapGestures(
                onDoubleTap = { onDoubleTap() },
                onLongPress = { at ->
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    menuAt = at
                },
            )
        },
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            apps.forEach { app ->
                Text(
                    app.label,
                    style = text.style(26f),
                    modifier = Modifier.clickable { actions.launch(app, null) },
                )
            }
        }
        menuAt?.let { at ->
            Box(Modifier.offset { IntOffset(at.x.roundToInt(), at.y.roundToInt()) }) {
                CellMenu(expanded = true, actions = homeMenu, onDismiss = { menuAt = null })
            }
        }
    }
}

/** Biometrie bzw. Geräte-PIN des Systems, z. B. für den Tresor. */
private fun authenticate(activity: android.app.Activity, title: String, onSuccess: () -> Unit) {
    val authenticators = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (activity.getSystemService(BiometricManager::class.java)?.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
        // Ohne Displaysperre gibt es nichts zu prüfen.
        onSuccess()
        return
    }
    BiometricPrompt.Builder(activity)
        .setTitle(title)
        .setAllowedAuthenticators(authenticators)
        .build()
        .authenticate(CancellationSignal(), activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
        })
}

private val GRAYSCALE_FILTER = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })
private const val SWIPE_DOWN_THRESHOLD_PX = 150f
private const val VAULT_KEYWORD = "tresor"

/** Geöffneter Ordner wie beim Pixel: abgerundete Karte, Name unten (antippen zum Umbenennen). */
@Composable
private fun FolderDialog(folder: HomeCell, viewModel: LauncherViewModel, actions: CellActions, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var title by remember(folder.item.id) { mutableStateOf(folder.item.title ?: "Ordner") }
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurface, fontSize = 12.sp)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 16.dp)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(
                        when {
                            folder.children.size <= 4 -> 2
                            folder.children.size <= 9 -> 3
                            else -> 4
                        },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(folder.children, key = { it.item.id }) { child ->
                        Box(contentAlignment = Alignment.Center) {
                            AppTile(
                                app = child.app,
                                iconSize = 52.dp,
                                showLabel = true,
                                large = false,
                                viewModel = viewModel,
                                labelStyle = labelStyle,
                                onClick = { bounds ->
                                    actions.launch(child.app, bounds)
                                    onDismiss()
                                },
                                onShortcut = { shortcut, bounds ->
                                    actions.shortcut(shortcut, bounds)
                                    onDismiss()
                                },
                                menu = buildList {
                                    add(MenuAction("App-Info", Icons.Default.Info) { actions.info(child.app) })
                                    add(MenuAction("Aus dem Ordner nehmen", Icons.Default.RemoveCircleOutline) { viewModel.removeFromFolder(child.item) })
                                    add(MenuAction("Entfernen", Icons.Default.Delete) { viewModel.remove(child.item) })
                                    if (!child.app.isWorkProfile) add(MenuAction("Deinstallieren", Icons.Default.Delete) { actions.uninstall(child.app) })
                                },
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (editing) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            singleLine = true,
                            shape = CircleShape,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            viewModel.renameFolder(folder.item, title)
                            editing = false
                        }) { Icon(Icons.Default.Check, contentDescription = "Speichern") }
                    } else {
                        Text(
                            folder.item.title ?: "Ordner",
                            style = MaterialTheme.typography.titleLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { editing = true }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoveToFolderDialog(
    folders: List<LayoutItem>,
    onDismiss: () -> Unit,
    onMove: (Long) -> Unit,
    onCreate: (String) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("In Ordner verschieben") },
        text = {
            Column {
                folders.forEach { folder ->
                    ListItem(
                        leadingContent = { Icon(Icons.Default.Folder, contentDescription = null) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onMove(folder.id) },
                    ) { Text(folder.title ?: "Ordner") }
                }
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("Neuer Ordner") },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(newName.ifBlank { "Ordner" }) }) { Text("Neuen Ordner anlegen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
