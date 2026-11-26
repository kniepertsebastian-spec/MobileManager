package app.mobilemanager.ui.permissions

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.mobilemanager.data.shizuku.ShizukuManager
import app.mobilemanager.data.shizuku.ShizukuState
import app.mobilemanager.domain.model.GrantState
import app.mobilemanager.domain.model.PermissionKind
import app.mobilemanager.domain.model.PermissionStatus
import app.mobilemanager.ui.components.SectionCard
import app.mobilemanager.ui.theme.StatusColors

/**
 * Berechtigungs-Assistent. [isOnboarding] = erster Start (mit "Weiter"-Button, ohne Zurück-Pfeil).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PermissionsScreen(
    isOnboarding: Boolean,
    onBack: () -> Unit,
    onOnboardingFinished: () -> Unit,
    viewModel: PermissionsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()

    // Änderungen sofort nach Rückkehr aus den Android-Einstellungen erkennen.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    val runtimeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refresh()
    }

    fun startSafely(primary: Intent?, fallback: Intent?) {
        val candidates = listOfNotNull(primary, fallback)
        for (intent in candidates) {
            try {
                context.startActivity(intent)
                return
            } catch (_: ActivityNotFoundException) {
                // nächsten Intent probieren
            } catch (_: SecurityException) {
            }
        }
    }

    fun onClick(status: PermissionStatus) {
        when (status.kind) {
            PermissionKind.READ_MEDIA_IMAGES -> requestRuntime(
                context as? Activity,
                mediaPermissions(),
                Manifest.permission.READ_MEDIA_IMAGES,
                status,
                launch = { runtimeLauncher.launch(it) },
                openSettings = { startSafely(viewModel.settingsIntent(status.kind), null) },
            )
            PermissionKind.BLUETOOTH_CONNECT -> requestRuntime(
                context as? Activity,
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                Manifest.permission.BLUETOOTH_CONNECT,
                status,
                launch = { runtimeLauncher.launch(it) },
                openSettings = { startSafely(viewModel.settingsIntent(status.kind), null) },
            )
            PermissionKind.POST_NOTIFICATIONS -> requestRuntime(
                context as? Activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                Manifest.permission.POST_NOTIFICATIONS,
                status,
                launch = { runtimeLauncher.launch(it) },
                openSettings = { startSafely(viewModel.settingsIntent(status.kind), null) },
            )
            PermissionKind.SHIZUKU -> when (viewModel.shizukuState.value) {
                ShizukuState.NOT_INSTALLED -> startSafely(
                    Intent(Intent.ACTION_VIEW, ShizukuManager.SHIZUKU_DOWNLOAD_URL.toUri()),
                    null,
                )
                ShizukuState.NOT_RUNNING -> startSafely(viewModel.shizukuLaunchIntent(), null)
                ShizukuState.NO_PERMISSION -> viewModel.requestShizukuPermission()
                ShizukuState.READY -> Unit
            }
            else -> if (status.state != GrantState.GRANTED) {
                startSafely(viewModel.settingsIntent(status.kind), viewModel.fallbackIntent(status.kind))
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isOnboarding) "Willkommen" else "Berechtigungen") },
                navigationIcon = {
                    if (!isOnboarding) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    if (isOnboarding) {
                        "MobileManager braucht einige Systemrechte, die Android nicht per Pop-up vergibt. " +
                            "Tippe auf einen Eintrag, um direkt in die passende Einstellung zu springen. " +
                            "Alles bleibt zu 100 % lokal auf deinem Gerät."
                    } else {
                        "Tippe auf einen roten Eintrag, um die Berechtigung zu erteilen. " +
                            "Der Status aktualisiert sich automatisch, sobald du zurückkehrst."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            items(statuses, key = { it.kind }) { status ->
                PermissionRow(status, onClick = { onClick(status) })
            }
            if (isOnboarding) {
                item {
                    Spacer(Modifier.size(8.dp))
                    Button(
                        onClick = { viewModel.completeOnboarding(onOnboardingFinished) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        val missing = statuses.count { it.required && it.state != GrantState.GRANTED }
                        Text(if (missing == 0) "Los geht's" else "Weiter ($missing fehlen noch – später möglich)")
                    }
                }
            }
        }
    }
}

/** Ab Android 14 zusätzlich "Nur ausgewählte Fotos" anbieten. */
private fun mediaPermissions(): Array<String> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    } else {
        arrayOf(Manifest.permission.READ_MEDIA_IMAGES)
    }

private fun requestRuntime(
    activity: Activity?,
    permissions: Array<String>,
    primary: String,
    status: PermissionStatus,
    launch: (Array<String>) -> Unit,
    openSettings: () -> Unit,
) {
    if (status.state == GrantState.GRANTED) return
    // Nach zweimaliger Ablehnung zeigt Android keinen Dialog mehr – dann in die App-Infos.
    val permanentlyDenied = status.state == GrantState.DENIED &&
        activity != null && !activity.shouldShowRequestPermissionRationale(primary) &&
        PermissionPrefs.wasRequested(activity, primary)
    if (permanentlyDenied) {
        openSettings()
    } else {
        activity?.let { PermissionPrefs.markRequested(it, primary) }
        launch(permissions)
    }
}

/** Merkt sich, ob eine Runtime-Berechtigung schon einmal angefragt wurde. */
private object PermissionPrefs {
    private const val NAME = "permission_requests"
    fun wasRequested(activity: Activity, permission: String) =
        activity.getSharedPreferences(NAME, Activity.MODE_PRIVATE).getBoolean(permission, false)

    fun markRequested(activity: Activity, permission: String) {
        activity.getSharedPreferences(NAME, Activity.MODE_PRIVATE).edit { putBoolean(permission, true) }
    }
}

@Composable
private fun PermissionRow(status: PermissionStatus, onClick: () -> Unit) {
    SectionCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (icon, color) = when (status.state) {
                GrantState.GRANTED -> Icons.Default.CheckCircle to StatusColors.Granted
                GrantState.PARTIAL -> Icons.Default.RemoveCircle to StatusColors.Partial
                GrantState.DENIED -> Icons.Default.Error to
                    if (status.required) StatusColors.Denied else MaterialTheme.colorScheme.outline
            }
            Icon(icon, contentDescription = status.stateLabel, tint = color, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(status.title, style = MaterialTheme.typography.titleMedium)
                Text(status.description, style = MaterialTheme.typography.bodySmall)
                Text(status.stateLabel, style = MaterialTheme.typography.labelMedium, color = color)
            }
        }
    }
}
