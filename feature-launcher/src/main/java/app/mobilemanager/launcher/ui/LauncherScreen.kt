package app.mobilemanager.launcher.ui

import android.content.Intent
import android.graphics.Rect
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SpaceDashboard
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.ViewWeek
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mobilemanager.launcher.data.LauncherApp
import app.mobilemanager.launcher.domain.LayoutItem
import kotlinx.coroutines.launch

private val OnWallpaper = TextStyle(
    color = Color.White,
    fontSize = 12.sp,
    shadow = Shadow(Color.Black.copy(alpha = 0.7f), Offset(0f, 1.5f), 6f),
)

private data class MenuAction(val label: String, val icon: ImageVector, val onClick: () -> Unit)

/** Homescreen (Seiten + Dock), App-Drawer, Ordner und Menüs – Meilenstein 3.1.3 & 3.1.4. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LauncherScreen(
    viewModel: LauncherViewModel,
    onStartIntent: (Intent) -> Unit,
    onOpenSettings: (String) -> Unit,
    onMessage: (String) -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var drawerOpen by rememberSaveable { mutableStateOf(false) }
    var openFolderId by rememberSaveable { mutableStateOf<Long?>(null) }
    var homeMenu by remember { mutableStateOf(false) }
    var moveTarget by remember { mutableStateOf<LayoutItem?>(null) }
    val pagerState = rememberPagerState { state.pages.size }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val swipeThreshold = with(density) { 72.dp.toPx() }

    LaunchedEffect(Unit) {
        viewModel.homeEvents.collect {
            drawerOpen = false
            openFolderId = null
            homeMenu = false
            if (pagerState.currentPage != 0) pagerState.animateScrollToPage(0)
        }
    }
    BackHandler(enabled = drawerOpen || openFolderId != null) {
        if (openFolderId != null) openFolderId = null else drawerOpen = false
    }

    val actions = remember(viewModel) {
        CellActions(
            launch = { app, bounds -> if (!viewModel.launch(app, bounds)) onMessage("App lässt sich nicht starten") },
            info = viewModel::openAppInfo,
            uninstall = { app -> viewModel.uninstallIntent(app)?.let(onStartIntent) },
        )
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                var total = 0f
                detectVerticalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (total < -swipeThreshold) drawerOpen = true },
                    onVerticalDrag = { change, dy ->
                        total += dy
                        change.consume()
                    },
                )
            },
    ) {
        // Leichte Abdunklung oben/unten für lesbare Beschriftungen auf hellen Hintergründen.
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.25f),
                    0.2f to Color.Transparent,
                    0.8f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.3f),
                ),
            ),
        )
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            ProfileHeader(state, onClick = { homeMenu = true })
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
                    onLongPressEmpty = { homeMenu = true },
                    onOpenFolder = { openFolderId = it.id },
                    onMoveToFolder = { moveTarget = it },
                )
            }
            if (state.pages.size > 1) PageIndicator(state.pages.size, pagerState.currentPage)
            if (state.dock.isNotEmpty()) {
                Dock(state.dock, viewModel, actions, onOpenFolder = { openFolderId = it.id })
            } else {
                Spacer(Modifier.height(16.dp))
            }
        }

        AnimatedVisibility(
            visible = drawerOpen,
            enter = slideInVertically { it / 3 } + fadeIn(),
            exit = slideOutVertically { it / 3 } + fadeOut(),
        ) {
            AppDrawer(
                state = state,
                viewModel = viewModel,
                actions = actions,
                onClose = { drawerOpen = false },
                onAddToHome = { app ->
                    viewModel.addToHome(app, pagerState.currentPage)
                    onMessage("${app.label} zum Startbildschirm hinzugefügt")
                },
                onAddToDock = { app -> viewModel.addToDock(app) { onMessage("Das Dock ist voll (max. 5 Apps)") } },
            )
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

    if (homeMenu) {
        ModalBottomSheet(onDismissRequest = { homeMenu = false }) {
            Text(
                "Startbildschirm",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            SheetItem(Icons.AutoMirrored.Filled.Sort, "Homescreen ordnen", "Apps automatisch in Ordner sortieren und aufräumen") {
                homeMenu = false
                onOpenSettings(LauncherSettingsActivity.SCREEN_TIDY)
            }
            SheetItem(Icons.Default.Wallpaper, "Hintergrund ändern", null) {
                homeMenu = false
                onStartIntent(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "Hintergrund wählen"))
            }
            SheetItem(Icons.Default.Settings, "Launcher-Einstellungen", "Raster, Profile und Wechselregeln") {
                homeMenu = false
                onOpenSettings(LauncherSettingsActivity.SCREEN_OVERVIEW)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                "Profil",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            state.profiles.forEach { profile ->
                SheetItem(
                    icon = if (profile.id == state.profile?.id) Icons.Default.Check else Icons.Default.SpaceDashboard,
                    title = profile.name,
                    subtitle = if (profile.id == state.profile?.id && state.autoRule != null) "Automatisch aktiv" else null,
                ) {
                    homeMenu = false
                    viewModel.selectBaseProfile(profile.id)
                    scope.launch { pagerState.scrollToPage(0) }
                }
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
private fun ProfileHeader(state: LauncherUiState, onClick: () -> Unit) {
    val profile = state.profile ?: return
    if (state.profiles.size <= 1 && state.autoRule == null) return
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Surface(
            onClick = onClick,
            shape = CircleShape,
            color = Color.Black.copy(alpha = 0.25f),
        ) {
            Text(
                profile.name + if (state.autoRule != null) " · automatisch" else "",
                style = OnWallpaper.copy(fontSize = 13.sp),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/** Callbacks, die jede App-Zelle braucht. */
private class CellActions(
    val launch: (LauncherApp, Rect?) -> Unit,
    val info: (LauncherApp) -> Unit,
    val uninstall: (LauncherApp) -> Unit,
)

@Composable
private fun HomePage(
    cells: List<HomeCell>,
    state: LauncherUiState,
    viewModel: LauncherViewModel,
    actions: CellActions,
    onLongPressEmpty: () -> Unit,
    onOpenFolder: (LayoutItem) -> Unit,
    onMoveToFolder: (LayoutItem) -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    val byPosition = remember(cells) { cells.associateBy { it.item.position } }
    val iconSize = if (state.largeIcons) 96.dp else 56.dp
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    onLongPressEmpty()
                })
            },
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
}

@Composable
private fun Dock(cells: List<HomeCell>, viewModel: LauncherViewModel, actions: CellActions, onOpenFolder: (LayoutItem) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        shape = RoundedCornerShape(28.dp),
        color = Color.White.copy(alpha = 0.18f),
    ) {
        Row(
            Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            cells.forEach { cell ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    val app = cell.app
                    if (cell.isFolder) {
                        FolderTile(cell, 52.dp, false, viewModel, onClick = { onOpenFolder(cell.item) }, menu = emptyList())
                    } else if (app != null) {
                        AppTile(
                            app = app,
                            iconSize = 52.dp,
                            showLabel = false,
                            large = false,
                            viewModel = viewModel,
                            onClick = { bounds -> actions.launch(app, bounds) },
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
}

@Composable
private fun PageIndicator(count: Int, current: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
        repeat(count) { index ->
            Box(
                Modifier
                    .padding(horizontal = 3.dp)
                    .size(if (index == current) 8.dp else 6.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = if (index == current) 0.95f else 0.5f)),
            )
        }
    }
}

@Composable
private fun AppIconImage(key: String, size: Dp, viewModel: LauncherViewModel) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val icon by produceState(initialValue = viewModel.cachedIcon(key, px), key, px) {
        if (value == null) value = viewModel.icon(key, px)
    }
    val bitmap = icon
    if (bitmap != null) {
        Image(bitmap, contentDescription = null, modifier = Modifier.size(size))
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
    labelStyle: TextStyle = OnWallpaper,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val bounds = remember { arrayOfNulls<Rect>(1) }
    val haptic = LocalHapticFeedback.current
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
                    modifier = Modifier.width(iconSize + 20.dp).padding(top = 4.dp),
                )
            }
        }
        CellMenu(menuOpen, menu, onDismiss = { menuOpen = false })
    }
}

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
            Box(
                Modifier.size(iconSize).clip(RoundedCornerShape(iconSize / 4)).background(Color.White.copy(alpha = 0.3f)).padding(iconSize / 10),
            ) {
                val mini = (iconSize - iconSize / 5) / 2
                Column {
                    cell.children.take(4).chunked(2).forEach { rowItems ->
                        Row {
                            rowItems.forEach { child -> AppIconImage(child.app.key, mini, viewModel) }
                        }
                    }
                }
            }
            if (showLabel) {
                Text(
                    cell.item.title ?: "Ordner",
                    style = OnWallpaper,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(iconSize + 20.dp).padding(top = 4.dp),
                )
            }
        }
        CellMenu(menuOpen, menu, onDismiss = { menuOpen = false })
    }
}

@Composable
private fun CellMenu(expanded: Boolean, actions: List<MenuAction>, onDismiss: () -> Unit) {
    DropdownMenu(expanded = expanded && actions.isNotEmpty(), onDismissRequest = onDismiss) {
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

@Composable
private fun AppDrawer(
    state: LauncherUiState,
    viewModel: LauncherViewModel,
    actions: CellActions,
    onClose: () -> Unit,
    onAddToHome: (LauncherApp) -> Unit,
    onAddToDock: (LauncherApp) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val filtered = remember(state.drawerApps, query) {
        val q = query.trim()
        if (q.isEmpty()) state.drawerApps
        else state.drawerApps.filter { it.label.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
    }
    val density = LocalDensity.current
    val closeThreshold = with(density) { 96.dp.toPx() }
    val closeOnPull = remember(onClose) {
        object : NestedScrollConnection {
            var pulled = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && available.y > 0f) {
                    pulled += available.y
                    if (pulled > closeThreshold) {
                        pulled = 0f
                        onClose()
                    }
                } else if (consumed.y != 0f) {
                    pulled = 0f
                }
                return Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                pulled = 0f
                return Velocity.Zero
            }
        }
    }
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurface)

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    placeholder = { Text("Apps durchsuchen") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    shape = CircleShape,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Schließen") }
            }
            if (state.hiddenCount > 0 && state.profile != null) {
                Text(
                    "${state.hiddenCount} Apps sind im Profil „${state.profile.name}“ ausgeblendet",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(if (state.largeIcons) 4 else state.columns),
                modifier = Modifier.fillMaxSize().nestedScroll(closeOnPull),
                contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(filtered, key = { it.key }) { app ->
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
                            menu = buildList {
                                add(MenuAction("Zum Startbildschirm", Icons.Default.Home) { onAddToHome(app) })
                                if (!state.largeIcons) add(MenuAction("Ins Dock", Icons.Default.ViewWeek) { onAddToDock(app) })
                                add(MenuAction("App-Info", Icons.Default.Info) { actions.info(app) })
                                if (!app.isWorkProfile) add(MenuAction("Deinstallieren", Icons.Default.Delete) { actions.uninstall(app) })
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FolderDialog(folder: HomeCell, viewModel: LauncherViewModel, actions: CellActions, onDismiss: () -> Unit) {
    var editing by remember { mutableStateOf(false) }
    var title by remember(folder.item.id) { mutableStateOf(folder.item.title ?: "Ordner") }
    val labelStyle = MaterialTheme.typography.labelMedium.copy(color = MaterialTheme.colorScheme.onSurface)
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (editing) {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = {
                            viewModel.renameFolder(folder.item, title)
                            editing = false
                        }) { Icon(Icons.Default.Check, contentDescription = "Speichern") }
                    } else {
                        Text(folder.item.title ?: "Ordner", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { editing = true }) { Icon(Icons.Default.Edit, contentDescription = "Umbenennen") }
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(folder.children, key = { it.item.id }) { child ->
                        Box(contentAlignment = Alignment.Center) {
                            AppTile(
                                app = child.app,
                                iconSize = 48.dp,
                                showLabel = true,
                                large = false,
                                viewModel = viewModel,
                                labelStyle = labelStyle,
                                onClick = { bounds ->
                                    actions.launch(child.app, bounds)
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
