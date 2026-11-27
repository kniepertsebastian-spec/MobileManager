package app.mobilemanager.ui.locker

import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.CancellationSignal
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.lifecycleScope
import app.mobilemanager.data.locker.LockerController
import app.mobilemanager.data.locker.LockerRepository
import app.mobilemanager.domain.extras.SecondFactor
import app.mobilemanager.ui.components.SecretField
import app.mobilemanager.ui.theme.MobileManagerTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Phase 4.2: Sperrbildschirm der App-Sperre. Faktor 1 ist die System-Biometrie (mit Geräte-PIN als Ausweg),
 * Faktor 2 je nach Einstellung App-PIN, TOTP-Code oder NFC-Tag. FLAG_SECURE hält ihn aus Screenshots und
 * der Übersicht der letzten Apps heraus.
 */
@AndroidEntryPoint
class LockActivity : ComponentActivity() {

    @Inject lateinit var controller: LockerController
    @Inject lateinit var repository: LockerRepository

    private val target = MutableStateFlow("")
    private val stage = MutableStateFlow(Stage.BIOMETRIC)
    private val nfcResult = MutableStateFlow<Boolean?>(null)
    private var biometricCancel: CancellationSignal? = null

    private enum class Stage { BIOMETRIC, SECOND_FACTOR, DONE }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        setRecentsScreenshotEnabled(false)
        target.value = intent.getStringExtra(EXTRA_TARGET).orEmpty()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = abort()
        })
        setContent {
            MobileManagerTheme {
                val pkg by target.collectAsStateValue()
                val currentStage by stage.collectAsStateValue()
                LockContent(pkg, currentStage)
            }
        }
        if (savedInstanceState == null) startBiometric()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val newTarget = intent.getStringExtra(EXTRA_TARGET).orEmpty()
        if (newTarget != target.value) {
            target.value = newTarget
            stage.value = Stage.BIOMETRIC
            startBiometric()
        }
    }

    override fun onResume() {
        super.onResume()
        controller.onLockScreenVisible()
        if (stage.value == Stage.SECOND_FACTOR && repositoryFactor() == SecondFactor.NFC) enableNfc()
    }

    override fun onPause() {
        super.onPause()
        NfcAdapter.getDefaultAdapter(this)?.disableReaderMode(this)
    }

    private fun repositoryFactor(): SecondFactor = controller.settings.value?.secondFactor ?: SecondFactor.NONE

    private fun startBiometric() {
        val authenticators = Authenticators.BIOMETRIC_WEAK or Authenticators.DEVICE_CREDENTIAL
        val manager = getSystemService(BiometricManager::class.java)
        if (manager?.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            // Ohne Displaysperre gibt es keinen ersten Faktor – dann zählt allein der zweite.
            afterFirstFactor()
            return
        }
        biometricCancel?.cancel()
        val cancel = CancellationSignal().also { biometricCancel = it }
        val prompt = BiometricPrompt.Builder(this)
            .setTitle("${controller.label(target.value)} entsperren")
            .setSubtitle("MobileManager App-Sperre")
            .setAllowedAuthenticators(authenticators)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(cancel, mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = afterFirstFactor()

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Abbruch durch Nutzer bleibt auf dem Sperrbildschirm; erneuter Versuch über den Button.
            }
        })
    }

    private fun afterFirstFactor() {
        if (controller.needsSecondFactor()) {
            stage.value = Stage.SECOND_FACTOR
            if (repositoryFactor() == SecondFactor.NFC) enableNfc()
        } else {
            succeed()
        }
    }

    private fun enableNfc() {
        val adapter = NfcAdapter.getDefaultAdapter(this) ?: return
        val flags = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_NFC_F or
            NfcAdapter.FLAG_READER_NFC_V or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        adapter.enableReaderMode(this, { tag ->
            lifecycleScope.launch {
                val ok = repository.verifyNfcTag(tag.id)
                nfcResult.value = ok
                if (ok) succeed() else controller.recordFailure(target.value, "NFC-Tag")
            }
        }, flags, null)
    }

    private fun succeed() {
        stage.value = Stage.DONE
        controller.unlock(target.value)
        finish()
    }

    /** Abbruch: zurück zum Startbildschirm, die gesperrte App bleibt verdeckt. */
    private fun abort() {
        biometricCancel?.cancel()
        controller.cancel()
        if (target.value != LockerController.SETTINGS_KEY) {
            runCatching {
                startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        finish()
    }

    @Composable
    private fun LockContent(pkg: String, currentStage: Stage) {
        val icon: ImageBitmap? = remember(pkg) {
            runCatching { packageManager.getApplicationIcon(pkg).toBitmap(160, 160).asImageBitmap() }.getOrNull()
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(
                    Modifier.widthIn(max = 420.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    if (icon != null) {
                        Image(icon, contentDescription = null, modifier = Modifier.size(72.dp))
                    } else {
                        Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(56.dp))
                    }
                    Text(controller.label(pkg), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    when (currentStage) {
                        Stage.BIOMETRIC -> {
                            Text("Gesperrt durch MobileManager", style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = ::startBiometric, modifier = Modifier.fillMaxWidth()) { Text("Entsperren") }
                        }
                        Stage.SECOND_FACTOR -> SecondFactorContent(repositoryFactor())
                        Stage.DONE -> Unit
                    }
                    TextButton(onClick = ::abort) { Text("Abbrechen") }
                }
            }
        }
    }

    @Composable
    private fun SecondFactorContent(factor: SecondFactor) {
        val scope = rememberCoroutineScope()
        var input by remember { mutableStateOf("") }
        var recovery by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf<String?>(null) }
        var lockedOut by remember { mutableLongStateOf(0L) }
        var attempt by remember { mutableStateOf(0) }
        LaunchedEffect(lockedOut) {
            while (lockedOut > 0) {
                delay(1_000)
                lockedOut = ((controller.lockedOutUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
            }
        }
        val nfc by nfcResult.collectAsStateValue()

        fun submit() {
            val now = System.currentTimeMillis()
            if (controller.lockedOutUntil > now) {
                lockedOut = (controller.lockedOutUntil - now) / 1000
                return
            }
            scope.launch {
                val ok = when {
                    recovery -> repository.useRecoveryCode(input)
                    factor == SecondFactor.PIN -> repository.verifyPin(input)
                    factor == SecondFactor.TOTP -> repository.verifyTotp(input)
                    else -> false
                }
                if (ok) {
                    succeed()
                } else {
                    controller.recordFailure(target.value, if (recovery) "Recovery-Code" else factor.label)
                    input = ""
                    attempt++
                    error = "Falsch – bitte erneut versuchen"
                    lockedOut = ((controller.lockedOutUntil - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
                }
            }
        }

        Text("Zweiter Faktor", style = MaterialTheme.typography.titleMedium)
        if (factor == SecondFactor.NFC && !recovery) {
            Icon(Icons.Default.Nfc, contentDescription = null, modifier = Modifier.size(48.dp))
            Text(
                when (nfc) {
                    false -> "Unbekannter Tag – bitte den eingerichteten Tag verwenden."
                    else -> "Halte deinen NFC-Tag oder YubiKey an die Rückseite."
                },
                textAlign = TextAlign.Center,
            )
        } else {
            val label = when {
                recovery -> "Recovery-Code (XXXXX-XXXXX)"
                factor == SecondFactor.PIN -> "App-PIN"
                else -> "6-stelliger Code aus der Authenticator-App"
            }
            if (factor == SecondFactor.PIN && !recovery) {
                SecretField(label, { input = it }, digitsOnly = true, maxLength = 12, resetKey = attempt.takeIf { it > 0 }, onDone = ::submit)
            } else {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.take(32) },
                    label = { Text(label) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = if (recovery) KeyboardType.Ascii else KeyboardType.Number,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Button(onClick = ::submit, enabled = input.isNotBlank() && lockedOut == 0L, modifier = Modifier.fillMaxWidth()) {
                Text(if (lockedOut > 0) "Gesperrt für $lockedOut s" else "Bestätigen")
            }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        TextButton(onClick = {
            recovery = !recovery
            input = ""
            error = null
        }) { Text(if (recovery) "Zurück zum zweiten Faktor" else "Recovery-Code verwenden") }
    }

    companion object {
        private const val EXTRA_TARGET = "target"

        fun intent(context: Context, target: String): Intent =
            Intent(context, LockActivity::class.java)
                .putExtra(EXTRA_TARGET, target)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
                )
    }
}

@Composable
private fun <T> StateFlow<T>.collectAsStateValue() = collectAsState()
