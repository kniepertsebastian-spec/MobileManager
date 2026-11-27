package app.mobilemanager.launcher.data

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import app.mobilemanager.launcher.domain.AppCategorizer
import app.mobilemanager.launcher.domain.AppCategory
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.Collator
import javax.inject.Inject
import javax.inject.Singleton

data class LauncherApp(
    val key: String,
    val packageName: String,
    val component: ComponentName,
    val user: UserHandle,
    val label: String,
    val category: AppCategory,
    val isWorkProfile: Boolean,
)

/** App-Shortcut für das Long-Press-Menü (wie im Pixel Launcher). */
data class LauncherShortcut(val info: ShortcutInfo, val label: String, val icon: ImageBitmap?)

/**
 * Meilenstein 3.1.2: App-Liste über die `LauncherApps`-API (Arbeitsprofile, adaptive Icons mit Badge).
 * Icons werden asynchron geladen und in einem LRU-Cache gehalten.
 */
@Singleton
class LauncherAppsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val launcherApps: LauncherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager: UserManager = context.getSystemService(UserManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val loadMutex = Mutex()

    private val _apps = MutableStateFlow<List<LauncherApp>?>(null)

    /** `null`, solange noch nicht geladen. */
    val apps: StateFlow<List<LauncherApp>?> = _apps.asStateFlow()

    @Volatile private var infos: Map<String, LauncherActivityInfo> = emptyMap()

    /** Etwa 24 MB für Icons; Schlüssel = App-Schlüssel + Größe. */
    private val iconCache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 4
    }

    private var callbackRegistered = false

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed(packageName)
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            packageNames.forEach(::changed)
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) =
            packageNames.forEach(::changed)
        override fun onPackagesSuspended(packageNames: Array<out String>, user: UserHandle) = packageNames.forEach(::changed)
        override fun onPackagesUnsuspended(packageNames: Array<out String>, user: UserHandle) = packageNames.forEach(::changed)
    }

    /** Vom Launcher beim ersten Start aufgerufen; danach hält der Callback die Liste aktuell. */
    fun start() {
        synchronized(this) {
            if (!callbackRegistered) {
                launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
                callbackRegistered = true
            }
        }
        if (_apps.value == null) refresh()
    }

    fun refresh() {
        scope.launch { loadMutex.withLock { load() } }
    }

    private fun changed(packageName: String) {
        iconCache.snapshot().keys.filter { it.startsWith("$packageName/") }.forEach { iconCache.remove(it) }
        refresh()
    }

    private fun load() {
        val collator = Collator.getInstance()
        val me = Process.myUserHandle()
        val loaded = mutableMapOf<String, LauncherActivityInfo>()
        val list = launcherApps.profiles.flatMap { user ->
            runCatching { launcherApps.getActivityList(null, user) }.getOrDefault(emptyList()).map { info ->
                val key = keyOf(info.componentName, user)
                loaded[key] = info
                val label = info.label?.toString()?.ifBlank { null } ?: info.componentName.packageName
                LauncherApp(
                    key = key,
                    packageName = info.componentName.packageName,
                    component = info.componentName,
                    user = user,
                    label = label,
                    category = AppCategorizer.categorize(info.applicationInfo.category, info.componentName.packageName, label),
                    isWorkProfile = user != me,
                )
            }
        }.sortedWith { a, b -> collator.compare(a.label, b.label) }
        infos = loaded
        _apps.value = list
    }

    fun keyOf(component: ComponentName, user: UserHandle): String =
        "${component.flattenToShortString()}#${userManager.getSerialNumberForUser(user)}"

    val mainUserSuffix: String get() = "#${userManager.getSerialNumberForUser(Process.myUserHandle())}"

    private fun cacheKey(key: String, sizePx: Int, themed: Boolean) =
        if (themed) "$key@$sizePx#${themedColors().first}" else "$key@$sizePx"

    fun cachedIcon(key: String, sizePx: Int, themed: Boolean = false): ImageBitmap? = iconCache.get(cacheKey(key, sizePx, themed))

    /**
     * Lädt das Icon. Mit [themed] wird – wie bei den Pixel-„Designsymbolen“ – die einfarbige Ebene des
     * adaptiven Icons in den Material-You-Farben gezeichnet; Apps ohne diese Ebene behalten ihr Icon.
     */
    suspend fun icon(key: String, sizePx: Int, themed: Boolean = false): ImageBitmap? {
        cachedIcon(key, sizePx, themed)?.let { return it }
        val info = infos[key] ?: return null
        val cacheKey = cacheKey(key, sizePx, themed)
        return withContext(Dispatchers.IO) {
            runCatching {
                val themedIcon = if (themed && info.user == Process.myUserHandle()) themedIcon(info.getIcon(0)) else null
                (themedIcon ?: info.getBadgedIcon(0)).toBitmap(sizePx, sizePx).asImageBitmap()
            }.getOrNull()?.also { iconCache.put(cacheKey, it) }
        }
    }

    /** Hintergrund-/Vordergrundfarbe der Designsymbole wie beim Pixel Launcher (hell/dunkel). */
    private fun themedColors(): Pair<Int, Int> {
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        return if (dark) {
            context.getColor(android.R.color.system_neutral1_800) to context.getColor(android.R.color.system_accent1_100)
        } else {
            context.getColor(android.R.color.system_accent1_100) to context.getColor(android.R.color.system_neutral2_700)
        }
    }

    private fun themedIcon(drawable: Drawable): Drawable? {
        val adaptive = drawable as? AdaptiveIconDrawable ?: return null
        val mono = adaptive.monochrome?.constantState?.newDrawable(context.resources)?.mutate() ?: return null
        val (background, foreground) = themedColors()
        mono.setTint(foreground)
        return AdaptiveIconDrawable(background.toDrawable(), mono)
    }

    /** Statische und dynamische Shortcuts einer App – nur möglich, solange wir der Standard-Launcher sind. */
    suspend fun shortcuts(app: LauncherApp, iconSizePx: Int): List<LauncherShortcut> = withContext(Dispatchers.IO) {
        runCatching {
            if (!launcherApps.hasShortcutHostPermission()) return@runCatching emptyList()
            val query = LauncherApps.ShortcutQuery()
                .setPackage(app.packageName)
                .setActivity(app.component)
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC)
            launcherApps.getShortcuts(query, app.user).orEmpty()
                .filter { it.isEnabled }
                .sortedWith(compareBy<ShortcutInfo>({ !it.isDeclaredInManifest }, { it.rank }))
                .take(MAX_SHORTCUTS)
                .map { info ->
                    val icon = runCatching {
                        launcherApps.getShortcutIconDrawable(info, context.resources.displayMetrics.densityDpi)
                            ?.toBitmap(iconSizePx, iconSizePx)?.asImageBitmap()
                    }.getOrNull()
                    LauncherShortcut(info, (info.shortLabel ?: info.longLabel ?: "").toString(), icon)
                }
                .filter { it.label.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    fun startShortcut(shortcut: LauncherShortcut, bounds: Rect?): Boolean = try {
        launcherApps.startShortcut(shortcut.info, bounds, null)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: IllegalStateException) {
        false
    } catch (_: SecurityException) {
        false
    }

    fun launch(app: LauncherApp, bounds: Rect?): Boolean = try {
        launcherApps.startMainActivity(app.component, app.user, bounds, null)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }

    fun openAppInfo(app: LauncherApp) {
        runCatching { launcherApps.startAppDetailsActivity(app.component, app.user, null, null) }
    }

    /** Deinstallations-Dialog des Systems (nur für Apps im Hauptprofil). */
    fun uninstallIntent(app: LauncherApp): Intent? =
        if (app.isWorkProfile) null
        else Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private companion object {
        const val MAX_SHORTCUTS = 4
    }
}
