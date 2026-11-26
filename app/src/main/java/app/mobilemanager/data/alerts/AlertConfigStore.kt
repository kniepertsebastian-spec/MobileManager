package app.mobilemanager.data.alerts

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.mobilemanager.domain.model.AlertChannelType
import app.mobilemanager.domain.model.AlertConfig
import app.mobilemanager.domain.model.SmtpSecurity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

private val Context.secureStore: DataStore<Preferences> by preferencesDataStore(name = "secure_store")

/**
 * AES-256-GCM mit einem nicht exportierbaren Schlüssel im Android Keystore.
 *
 * Ersetzt das inzwischen abgekündigte `EncryptedSharedPreferences` (androidx.security-crypto)
 * durch denselben Mechanismus ohne veraltete APIs.
 */
@Singleton
class KeystoreCipher @Inject constructor() {

    fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    fun decrypt(encoded: String): String? = runCatching {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, bytes, 0, IV_BYTES))
        String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }.getOrNull()

    @Synchronized
    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "mobilemanager_alert_credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128
    }
}

/** Speichert die komplette [AlertConfig] (inkl. Empfänger-Adresse) als ein verschlüsseltes JSON-Blob. */
@Singleton
class AlertConfigStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: KeystoreCipher,
) {
    private val key = stringPreferencesKey("alert_config")

    val config: Flow<AlertConfig> = context.secureStore.data.map { prefs ->
        prefs[key]?.let(cipher::decrypt)?.let(::fromJson) ?: AlertConfig()
    }

    suspend fun current(): AlertConfig = config.first()

    suspend fun save(config: AlertConfig) {
        val encrypted = cipher.encrypt(toJson(config))
        context.secureStore.edit { it[key] = encrypted }
    }

    private fun toJson(c: AlertConfig): String = JSONObject()
        .put("channel", c.channel.name)
        .put("smtpHost", c.smtpHost)
        .put("smtpPort", c.smtpPort)
        .put("smtpSecurity", c.smtpSecurity.name)
        .put("smtpUser", c.smtpUser)
        .put("smtpPassword", c.smtpPassword)
        .put("mailFrom", c.mailFrom)
        .put("mailTo", c.mailTo)
        .put("resendApiKey", c.resendApiKey)
        .put("telegramToken", c.telegramToken)
        .put("telegramChatId", c.telegramChatId)
        .put("ntfyServer", c.ntfyServer)
        .put("ntfyTopic", c.ntfyTopic)
        .put("ntfyToken", c.ntfyToken)
        .toString()

    private fun fromJson(json: String): AlertConfig? = runCatching {
        val o = JSONObject(json)
        val d = AlertConfig()
        AlertConfig(
            channel = enumValueOrNull<AlertChannelType>(o.optString("channel")) ?: d.channel,
            smtpHost = o.optString("smtpHost", d.smtpHost),
            smtpPort = o.optInt("smtpPort", d.smtpPort),
            smtpSecurity = enumValueOrNull<SmtpSecurity>(o.optString("smtpSecurity")) ?: d.smtpSecurity,
            smtpUser = o.optString("smtpUser", d.smtpUser),
            smtpPassword = o.optString("smtpPassword", d.smtpPassword),
            mailFrom = o.optString("mailFrom", d.mailFrom),
            mailTo = o.optString("mailTo", d.mailTo),
            resendApiKey = o.optString("resendApiKey", d.resendApiKey),
            telegramToken = o.optString("telegramToken", d.telegramToken),
            telegramChatId = o.optString("telegramChatId", d.telegramChatId),
            ntfyServer = o.optString("ntfyServer", d.ntfyServer),
            ntfyTopic = o.optString("ntfyTopic", d.ntfyTopic),
            ntfyToken = o.optString("ntfyToken", d.ntfyToken),
        )
    }.getOrNull()

    private inline fun <reified T : Enum<T>> enumValueOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }
}
