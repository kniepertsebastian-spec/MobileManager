package app.mobilemanager.launcher.ui

import androidx.compose.ui.platform.LocalResources
import app.mobilemanager.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.app.SearchManager
import android.content.Intent
import androidx.compose.material.icons.filled.LockOpen
import app.mobilemanager.launcher.data.GestureAction
import android.speech.RecognizerIntent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mobilemanager.launcher.domain.LayoutItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Geschwindigkeit (Anteil der Bildschirmhöhe pro Sekunde), ab der ein Wisch den Drawer sicher öffnet/schließt. */
internal const val FLING_PROGRESS_PER_SECOND = 1.5f

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
    val res = LocalResources.current
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
                onMessage(res.getString(R.string.launcher_msg_gesture_needs_accessibility))
        }
    }
    val swipeDownAction by rememberUpdatedState(state.swipeDownAction)
    val launcherActivity = LocalActivity.current
    fun openVault() {
        val activity = launcherActivity ?: return
        authenticate(activity, res.getString(R.string.launcher_vault_open)) { vaultOpen = true }
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
            launch = { app, bounds -> if (!viewModel.launch(app, bounds)) onMessage(res.getString(R.string.launcher_msg_app_start_failed)) },
            shortcut = { shortcut, bounds -> if (!viewModel.startShortcut(shortcut, bounds)) onMessage(res.getString(R.string.launcher_msg_shortcut_failed)) },
            info = viewModel::openAppInfo,
            uninstall = { app -> viewModel.uninstallIntent(app)?.let(onStartIntent) },
        )
    }
    val homeMenu = buildList {
        add(MenuAction(stringResource(R.string.launcher_menu_wallpaper), Icons.Default.Wallpaper) { onStartIntent(Intent(Intent.ACTION_SET_WALLPAPER)) })
        add(MenuAction(stringResource(R.string.launcher_homescreen_ordnen), Icons.AutoMirrored.Filled.Sort) { onOpenSettings(LauncherSettingsActivity.SCREEN_TIDY) })
        if (state.profiles.size > 1) add(MenuAction(stringResource(R.string.launcher_profil_wechseln), Icons.Default.SpaceDashboard) { profileSheet = true })
        add(MenuAction(stringResource(R.string.launcher_startbildschirm_einstellungen), Icons.Default.Settings) { onOpenSettings(LauncherSettingsActivity.SCREEN_OVERVIEW) })
    }
    val profileLabel = state.profile?.takeIf { state.profiles.size > 1 || state.autoRule != null }
        ?.let { if (state.autoRule != null) stringResource(R.string.launcher_profile_auto_suffix, it.name) else it.name }

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
                        onMessage(res.getString(R.string.launcher_msg_added_home, app.label))
                    },
                    onAddToDock = { app -> viewModel.addToDock(app) { onMessage(res.getString(R.string.launcher_msg_dock_full, 5)) } },
                    onAddToVault = { app ->
                        viewModel.addToVault(app)
                        onMessage(res.getString(R.string.launcher_msg_in_vault, app.label))
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
            Text(stringResource(R.string.launcher_tresor), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp))
            if (state.vaultApps.isEmpty()) {
                Text(
                    stringResource(R.string.launcher_leer_apps_im_drawer_lange),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
            }
            state.vaultApps.forEach { app ->
                ListItem(
                    leadingContent = { AppIconImage(app.key, 40.dp, viewModel) },
                    trailingContent = {
                        IconButton(onClick = { viewModel.removeFromVault(app) }) {
                            Icon(Icons.Default.LockOpen, contentDescription = stringResource(R.string.launcher_aus_dem_tresor_nehmen))
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
                stringResource(R.string.launcher_profil_wechseln),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            state.profiles.forEach { profile ->
                SheetItem(
                    icon = if (profile.id == state.profile?.id) Icons.Default.Check else Icons.Default.SpaceDashboard,
                    title = profile.name,
                    subtitle = if (profile.id == state.profile?.id && state.autoRule != null) stringResource(R.string.launcher_profile_auto_active) else null,
                ) {
                    profileSheet = false
                    viewModel.selectBaseProfile(profile.id)
                    scope.launch { pagerState.scrollToPage(0) }
                }
            }
            SheetItem(Icons.Default.Settings, stringResource(R.string.launcher_profile_verwalten), stringResource(R.string.launcher_anlegen_umbenennen_und_wechselregeln)) {
                profileSheet = false
                onOpenSettings(LauncherSettingsActivity.SCREEN_PROFILES)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
internal fun SheetItem(icon: ImageVector, title: String, subtitle: String?, onClick: () -> Unit) {
    ListItem(
        supportingContent = subtitle?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    ) { Text(title) }
}
