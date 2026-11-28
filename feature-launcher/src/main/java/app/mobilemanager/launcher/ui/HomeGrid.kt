package app.mobilemanager.launcher.ui

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import app.mobilemanager.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.graphics.Rect
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mobilemanager.launcher.data.LauncherApp
import app.mobilemanager.launcher.data.LauncherShortcut
import app.mobilemanager.launcher.domain.LayoutItem
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
internal fun HomePage(
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
                                            MenuAction(stringResource(R.string.launcher_oeffnen), Icons.Default.Folder) { onOpenFolder(cell.item) },
                                            MenuAction(stringResource(R.string.launcher_ordner_aufloesen), Icons.Default.FolderOff) { viewModel.dissolveFolder(cell.item) },
                                            MenuAction(stringResource(R.string.launcher_ordner_entfernen), Icons.Default.Delete) { viewModel.remove(cell.item) },
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
                                            add(MenuAction(stringResource(R.string.launcher_app_info), Icons.Default.Info) { actions.info(app) })
                                            add(MenuAction(stringResource(R.string.launcher_in_ordner_verschieben), Icons.Default.CreateNewFolder) { onMoveToFolder(cell.item) })
                                            add(MenuAction(stringResource(R.string.launcher_vom_startbildschirm_entfernen), Icons.Default.RemoveCircleOutline) { viewModel.remove(cell.item) })
                                            if (!app.isWorkProfile) add(MenuAction(stringResource(R.string.launcher_deinstallieren), Icons.Default.Delete) { actions.uninstall(app) })
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
internal fun Dock(cells: List<HomeCell>, viewModel: LauncherViewModel, actions: CellActions, onOpenFolder: (LayoutItem) -> Unit) {
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
                            add(MenuAction(stringResource(R.string.launcher_app_info), Icons.Default.Info) { actions.info(app) })
                            add(MenuAction(stringResource(R.string.launcher_aus_dem_dock_entfernen), Icons.Default.RemoveCircleOutline) { viewModel.remove(cell.item) })
                            if (!app.isWorkProfile) add(MenuAction(stringResource(R.string.launcher_deinstallieren), Icons.Default.Delete) { actions.uninstall(app) })
                        },
                    )
                }
            }
        }
    }
}

/** Suchleiste unter dem Dock: tippen = Apps/Web durchsuchen, Mikrofon = Sprachsuche. */
@Composable
internal fun SearchPill(onSearch: () -> Unit, onVoice: () -> Unit) {
    Surface(
        onClick = onSearch,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.94f),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp).height(52.dp),
    ) {
        Row(Modifier.padding(start = 18.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Search, contentDescription = stringResource(R.string.launcher_suchen), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onVoice) {
                Icon(Icons.Default.Mic, contentDescription = stringResource(R.string.launcher_sprachsuche), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun PageIndicator(count: Int, current: Int) {
    val color = LocalWallpaperText.current.color
    val description = stringResource(R.string.launcher_a11y_page, current + 1, count)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.Center,
    ) {
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
internal fun AppIconImage(key: String, size: Dp, viewModel: LauncherViewModel) {
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
internal fun AppTile(
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
    val openLabel = stringResource(R.string.launcher_a11y_open)
    val optionsLabel = stringResource(R.string.launcher_a11y_options)
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
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics(mergeDescendants = true) { if (!showLabel) contentDescription = app.label }
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = openLabel,
                    onLongClickLabel = if (menu.isEmpty()) null else optionsLabel,
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
internal fun FolderTile(
    cell: HomeCell,
    iconSize: Dp,
    showLabel: Boolean,
    viewModel: LauncherViewModel,
    onClick: () -> Unit,
    menu: List<MenuAction>,
) {
    var menuOpen by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    val folderName = cell.item.title ?: stringResource(R.string.launcher_ordner)
    val folderDescription = pluralStringResource(R.plurals.launcher_a11y_folder, cell.children.size, folderName, cell.children.size)
    val openFolderLabel = stringResource(R.string.launcher_a11y_open_folder)
    val optionsLabel = stringResource(R.string.launcher_a11y_options)
    Box {
        Column(
            Modifier
                .clip(RoundedCornerShape(16.dp))
                .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                .semantics(mergeDescendants = true) { contentDescription = folderDescription }
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = openFolderLabel,
                    onLongClickLabel = if (menu.isEmpty()) null else optionsLabel,
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
                    cell.item.title ?: stringResource(R.string.launcher_ordner),
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

/** Phase 4.7: ablenkungsfreie Textliste statt Icon-Raster (Homescreen- und Dock-Apps). */
@Composable
internal fun TextHome(
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
