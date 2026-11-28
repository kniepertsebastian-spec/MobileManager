package app.mobilemanager.ui.guard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.mobilemanager.data.alerts.AlertConfigStore
import app.mobilemanager.data.guard.GuardSettings
import app.mobilemanager.data.guard.GuardSettingsRepository
import app.mobilemanager.data.security.SecurityEventRepository
import app.mobilemanager.domain.model.AlertChannelType
import app.mobilemanager.domain.model.AlertConfig
import app.mobilemanager.domain.model.SmtpSecurity
import app.mobilemanager.ui.components.SectionCard
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlertSettingsUiState(
    val loaded: Boolean = false,
    val draft: AlertConfig = AlertConfig(),
    val dirty: Boolean = false,
    val testing: Boolean = false,
    /** Ergebnis des letzten Tests bzw. Speicherns. */
    val message: String? = null,
    val messageIsError: Boolean = false,
)

@HiltViewModel
class AlertSettingsViewModel @Inject constructor(
    private val store: AlertConfigStore,
    private val guardSettings: GuardSettingsRepository,
    private val securityEvents: SecurityEventRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AlertSettingsUiState())
    val state: StateFlow<AlertSettingsUiState> = _state.asStateFlow()
    val settings: StateFlow<GuardSettings> =
        guardSettings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GuardSettings())

    init {
        viewModelScope.launch {
            // Keystore-Fehler (z. B. nach Geräte-Wiederherstellung) dürfen den Bildschirm nicht abstürzen lassen.
            _state.value = runCatching { store.current() }.fold(
                onSuccess = { AlertSettingsUiState(loaded = true, draft = it) },
                onFailure = { e ->
                    AlertSettingsUiState(loaded = true, message = "Gespeicherte Zugangsdaten nicht lesbar: ${e.describe()}", messageIsError = true)
                },
            )
        }
    }

    fun edit(transform: (AlertConfig) -> AlertConfig) =
        _state.update { it.copy(draft = transform(it.draft), dirty = true, message = null) }

    fun save() {
        val draft = _state.value.draft
        if (!draft.isSavable) return
        viewModelScope.launch {
            runCatching { store.save(draft) }.fold(
                onSuccess = { _state.update { it.copy(dirty = false, message = "Verschlüsselt gespeichert", messageIsError = false) } },
                onFailure = { e -> _state.update { it.copy(message = "Speichern fehlgeschlagen: ${e.describe()}", messageIsError = true) } },
            )
        }
    }

    /** Speichert und sendet sofort einen Test-Alarm. */
    fun sendTest() {
        if (_state.value.testing) return
        val draft = _state.value.draft
        if (!draft.isSavable) return
        viewModelScope.launch {
            val saved = runCatching { store.save(draft) }
            if (saved.isFailure) {
                _state.update { it.copy(message = "Speichern fehlgeschlagen: ${saved.exceptionOrNull()!!.describe()}", messageIsError = true) }
                return@launch
            }
            _state.update { it.copy(dirty = false, testing = true, message = null) }
            val result = securityEvents.sendTest()
            _state.update {
                it.copy(
                    testing = false,
                    message = result.fold(
                        onSuccess = { "Test-Alarm gesendet – bitte Posteingang bzw. App prüfen." },
                        onFailure = { e -> "Fehler: ${e.describe()}" },
                    ),
                    messageIsError = result.isFailure,
                )
            }
        }
    }

    fun setCooldown(minutes: Int) {
        viewModelScope.launch { guardSettings.update { it.copy(alertCooldownMinutes = minutes) } }
    }

    private fun Throwable.describe() = message ?: javaClass.simpleName
}

@Composable
fun AlertSettingsScreen(onBack: () -> Unit, viewModel: AlertSettingsViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val c = state.draft

    GuardSubScreen(title = "Alarm-Versand", onBack = onBack) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Bei einem Sicherheitsereignis sendet MobileManager lautlos eine Nachricht an dich. " +
                    "Ist das Gerät gerade offline (z. B. Flugmodus), wird automatisch nachgesendet, sobald wieder Internet besteht. " +
                    "Alle Zugangsdaten werden mit einem Schlüssel aus dem Android Keystore verschlüsselt gespeichert.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!state.loaded) return@Column

            SectionCard {
                Text("Kanal", style = MaterialTheme.typography.titleMedium)
                ChoiceChips(
                    options = AlertChannelType.entries.map { it to it.label },
                    selected = c.channel,
                    onSelect = { type -> viewModel.edit { it.copy(channel = type) } },
                )
                when (c.channel) {
                    AlertChannelType.NONE -> Text("Alarme werden nur im Security-Log protokolliert.", style = MaterialTheme.typography.bodySmall)
                    AlertChannelType.SMTP -> SmtpFields(c, viewModel::edit)
                    AlertChannelType.RESEND -> {
                        Hint("API-Key unter resend.com anlegen; die Absender-Domain muss dort verifiziert sein.")
                        SecretField("API-Key", c.resendApiKey) { v -> viewModel.edit { it.copy(resendApiKey = v) } }
                        Field("Absender (z. B. alarm@deine-domain.de)", c.mailFrom, KeyboardType.Email) { v -> viewModel.edit { it.copy(mailFrom = v) } }
                        Field("Empfänger", c.mailTo, KeyboardType.Email) { v -> viewModel.edit { it.copy(mailTo = v) } }
                    }
                    AlertChannelType.TELEGRAM -> {
                        Hint("Bot bei @BotFather erstellen, dem Bot eine Nachricht schreiben und die Chat-ID z. B. über @userinfobot herausfinden.")
                        SecretField("Bot-Token", c.telegramToken) { v -> viewModel.edit { it.copy(telegramToken = v) } }
                        Field("Chat-ID", c.telegramChatId, KeyboardType.Number) { v -> viewModel.edit { it.copy(telegramChatId = v) } }
                    }
                    AlertChannelType.NTFY -> {
                        Hint("Wähle ein schwer zu erratendes Topic und abonniere es in der ntfy-App auf einem anderen Gerät.")
                        Field("Server", c.ntfyServer, KeyboardType.Uri) { v -> viewModel.edit { it.copy(ntfyServer = v) } }
                        Field("Topic", c.ntfyTopic) { v -> viewModel.edit { it.copy(ntfyTopic = v) } }
                        SecretField("Access-Token (optional)", c.ntfyToken) { v -> viewModel.edit { it.copy(ntfyToken = v) } }
                        if (!c.ntfyServerValid) {
                            Text(
                                "Nur gültige HTTPS-Adressen (https://…) werden unterstützt – Speichern ist bis dahin gesperrt.",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = viewModel::save, enabled = state.dirty && c.isSavable) { Text("Speichern") }
                OutlinedButton(onClick = viewModel::sendTest, enabled = c.isConfigured && c.isSavable && !state.testing) {
                    Text("Test-Alarm senden")
                }
                if (state.testing) CircularProgressIndicator(Modifier.size(24.dp))
            }
            state.message?.let {
                Text(
                    it,
                    color = if (state.messageIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            SectionCard {
                Text("Sperrzeit pro Ereignistyp", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Verhindert, dass du dich beim eigenen Vertippen selbst zuspammst. Ereignisse werden trotzdem protokolliert.",
                    style = MaterialTheme.typography.bodySmall,
                )
                ChoiceChips(
                    options = listOf(0 to "Keine", 5 to "5 min", 10 to "10 min", 30 to "30 min", 60 to "1 h"),
                    selected = settings.alertCooldownMinutes,
                    onSelect = viewModel::setCooldown,
                )
            }
        }
    }
}

@Composable
private fun SmtpFields(c: AlertConfig, edit: ((AlertConfig) -> AlertConfig) -> Unit) {
    Hint("Gmail: smtp.gmail.com, SSL/TLS 465, App-Passwort (nicht dein Google-Passwort). Posteo: posteo.de, SSL/TLS 465.")
    Field("SMTP-Server", c.smtpHost, KeyboardType.Uri) { v -> edit { it.copy(smtpHost = v) } }
    ChoiceChips(
        options = SmtpSecurity.entries.map { it to "${it.label} (${it.defaultPort})" },
        selected = c.smtpSecurity,
        onSelect = { sec -> edit { it.copy(smtpSecurity = sec, smtpPort = sec.defaultPort) } },
    )
    Field("Port", c.smtpPort.toString(), KeyboardType.Number) { v ->
        edit { it.copy(smtpPort = v.filter(Char::isDigit).take(5).toIntOrNull() ?: 0) }
    }
    Field("Benutzername", c.smtpUser, KeyboardType.Email) { v -> edit { it.copy(smtpUser = v) } }
    SecretField("Passwort / App-Passwort", c.smtpPassword) { v -> edit { it.copy(smtpPassword = v) } }
    Field("Absender (leer = Benutzername)", c.mailFrom, KeyboardType.Email) { v -> edit { it.copy(mailFrom = v) } }
    Field("Empfänger", c.mailTo, KeyboardType.Email) { v -> edit { it.copy(mailTo = v) } }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 4.dp))
}

@Composable
private fun Field(label: String, value: String, keyboard: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    )
}

/** Verdeckte Eingabe ohne Kopieren (OutlinedSecureTextField statt der abgekündigten VisualTransformation). */
@Composable
private fun SecretField(label: String, value: String, onChange: (String) -> Unit) {
    app.mobilemanager.ui.components.SecretField(
        label = label,
        onValueChange = onChange,
        initialValue = value,
        maxLength = 512,
        modifier = Modifier.padding(vertical = 2.dp),
    )
}
