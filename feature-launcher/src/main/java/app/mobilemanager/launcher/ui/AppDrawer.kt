package app.mobilemanager.launcher.ui

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalResources
import app.mobilemanager.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import android.content.Intent
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Lock
import app.mobilemanager.launcher.data.QuickAction
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemSpanScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.mobilemanager.launcher.data.LauncherApp
import kotlinx.coroutines.launch

/**
 * App-Drawer im Pixel-Stil: Suchleiste oben, eine Reihe App-Vorschläge, dann alle Apps alphabetisch.
 * Wird oben weiter nach unten gezogen, folgt er dem Finger und schließt sich.
 */
@Composable
internal fun AppDrawer(
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

    val res = LocalResources.current
    fun menuFor(app: LauncherApp) = buildList {
        add(MenuAction(res.getString(R.string.launcher_menu_add_home), Icons.Default.Home) { onAddToHome(app) })
        if (!state.largeIcons) add(MenuAction(res.getString(R.string.launcher_menu_add_dock), Icons.Default.ViewWeek) { onAddToDock(app) })
        add(MenuAction(res.getString(R.string.launcher_menu_app_info), Icons.Default.Info) { actions.info(app) })
        add(MenuAction(res.getString(R.string.launcher_menu_to_vault), Icons.Default.Lock) { onAddToVault(app) })
        if (!app.isWorkProfile) add(MenuAction(res.getString(R.string.launcher_menu_uninstall), Icons.Default.Delete) { actions.uninstall(app) })
    }

    // Phase 4.7: Universal-Suche – Aktionen der Haupt-App und Android-Einstellungen.
    val quickActions = remember { viewModel.quickActions() }
    val actionMatches = remember(trimmed) { UniversalSearch.matchActions(quickActions, trimmed) }
    val settingsMatches = remember(trimmed, res) { UniversalSearch.matchSettings(trimmed) { res.getString(it) } }

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
                                Text(stringResource(R.string.launcher_apps_und_web_durchsuchen), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                            IconButton(onClick = { queryState.edit { replace(0, length, "") } }) { Icon(Icons.Default.Close, contentDescription = stringResource(R.string.launcher_suche_loeschen)) }
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
                            pluralStringResource(R.plurals.launcher_hidden_apps, state.hiddenCount, state.hiddenCount, state.profile.name),
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
                            SearchResult(Icons.Default.Lock, stringResource(R.string.launcher_tresor_oeffnen)) { onOpenVault() }
                        }
                    }
                    actionMatches.forEach { action ->
                        item(key = "action:" + action.id, span = fullSpan) {
                            SearchResult(Icons.Default.Bolt, action.label) { onQuickAction(action) }
                        }
                    }
                    settingsMatches.forEach { entry ->
                        item(key = "setting:" + entry.action, span = fullSpan) {
                            SearchResult(Icons.Default.Settings, stringResource(entry.labelRes), stringResource(R.string.launcher_settings_category)) { onStartIntent(Intent(entry.action)) }
                        }
                    }
                    if (filtered.isEmpty()) {
                        item(key = "no-apps", span = fullSpan) {
                            Text(
                                stringResource(R.string.launcher_keine_apps_gefunden),
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
                        ) { Text(stringResource(R.string.launcher_im_web_nach_1_s, trimmed)) }
                    }
                }
            }
        }
    }
}

@Composable
internal fun SearchResult(icon: ImageVector, title: String, subtitle: String? = null, onClick: () -> Unit) {
    ListItem(
        leadingContent = { Icon(icon, contentDescription = null) },
        supportingContent = subtitle?.let { { Text(it) } },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick),
    ) { Text(title) }
}
