package app.mobilemanager.data.alerts

import app.mobilemanager.domain.model.AlertChannelType
import app.mobilemanager.domain.model.AlertConfig
import app.mobilemanager.domain.model.AlertMessage
import app.mobilemanager.domain.model.SmtpSecurity
import jakarta.mail.Authenticator
import jakarta.mail.Message
import jakarta.mail.PasswordAuthentication
import jakarta.mail.Session
import jakarta.mail.Transport
import jakarta.mail.internet.InternetAddress
import jakarta.mail.internet.MimeMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Date
import java.util.Properties
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Strategy: ein Versandweg für Alarme. Wirft bei Fehlern eine Exception mit verständlicher Meldung. */
interface AlertChannel {
    suspend fun send(message: AlertMessage)
}

/** Erzeugt den passenden Kanal zur gespeicherten Konfiguration. */
@Singleton
class AlertChannelFactory @Inject constructor() {

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    fun create(config: AlertConfig): AlertChannel? = when (config.channel) {
        AlertChannelType.NONE -> null
        AlertChannelType.SMTP -> SmtpChannel(config)
        AlertChannelType.RESEND -> ResendChannel(config, http)
        AlertChannelType.TELEGRAM -> TelegramChannel(config, http)
        AlertChannelType.NTFY -> NtfyChannel(config, http)
    }
}

/** Option A: klassische E-Mail über Jakarta Mail (Angus Mail), immer verschlüsselt (SSL/TLS oder STARTTLS). */
class SmtpChannel(private val config: AlertConfig) : AlertChannel {
    override suspend fun send(message: AlertMessage) = withContext(Dispatchers.IO) {
        val props = Properties().apply {
            put("mail.transport.protocol", "smtp")
            put("mail.smtp.host", config.smtpHost.trim())
            put("mail.smtp.port", config.smtpPort.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.ssl.checkserveridentity", "true")
            put("mail.smtp.connectiontimeout", TIMEOUT_MS)
            put("mail.smtp.timeout", TIMEOUT_MS)
            put("mail.smtp.writetimeout", TIMEOUT_MS)
            when (config.smtpSecurity) {
                SmtpSecurity.SSL_TLS -> put("mail.smtp.ssl.enable", "true")
                SmtpSecurity.STARTTLS -> {
                    put("mail.smtp.starttls.enable", "true")
                    put("mail.smtp.starttls.required", "true")
                }
            }
        }
        val user = config.smtpUser.trim()
        val session = Session.getInstance(
            props,
            object : Authenticator() {
                override fun getPasswordAuthentication() = PasswordAuthentication(user, config.smtpPassword)
            },
        )
        val mime = MimeMessage(session).apply {
            setFrom(InternetAddress(config.mailFrom.ifBlank { user }.trim()))
            setRecipients(Message.RecipientType.TO, InternetAddress.parse(config.mailTo.trim()))
            setSubject(message.subject, Charsets.UTF_8.name())
            setText(message.body, Charsets.UTF_8.name())
            sentDate = Date()
        }
        // Jakarta Mail lädt Provider/Mailcap über den Context-ClassLoader.
        val thread = Thread.currentThread()
        val previous = thread.contextClassLoader
        thread.contextClassLoader = SmtpChannel::class.java.classLoader
        try {
            Transport.send(mime)
        } finally {
            thread.contextClassLoader = previous
        }
    }

    private companion object {
        const val TIMEOUT_MS = "20000"
    }
}

/** Option B1: Resend-API (HTTP statt SMTP). */
class ResendChannel(private val config: AlertConfig, private val http: OkHttpClient) : AlertChannel {
    override suspend fun send(message: AlertMessage) {
        val json = JSONObject()
            .put("from", config.mailFrom.trim())
            .put("to", JSONArray(config.mailTo.split(',').map { it.trim() }.filter { it.isNotEmpty() }))
            .put("subject", message.subject)
            .put("text", message.body)
        http.execute(
            Request.Builder()
                .url("https://api.resend.com/emails")
                .header("Authorization", "Bearer ${config.resendApiKey.trim()}")
                .post(json.toString().toRequestBody(JSON))
                .build(),
        )
    }
}

/** Option B2: Telegram-Bot (`sendMessage`). */
class TelegramChannel(private val config: AlertConfig, private val http: OkHttpClient) : AlertChannel {
    override suspend fun send(message: AlertMessage) {
        val body = FormBody.Builder()
            .add("chat_id", config.telegramChatId.trim())
            .add("text", "${message.subject}\n\n${message.body}")
            .add("disable_web_page_preview", "true")
            .build()
        http.execute(
            Request.Builder()
                .url("https://api.telegram.org/bot${config.telegramToken.trim()}/sendMessage")
                .post(body)
                .build(),
        )
    }
}

/**
 * Option B3: ntfy (ntfy.sh oder eigener Server). JSON-Publishing, weil HTTP-Header
 * keine Umlaute im Titel erlauben.
 */
class NtfyChannel(private val config: AlertConfig, private val http: OkHttpClient) : AlertChannel {
    override suspend fun send(message: AlertMessage) {
        val json = JSONObject()
            .put("topic", config.ntfyTopic.trim())
            .put("title", message.subject)
            .put("message", message.body)
            .put("priority", 5)
            .put("tags", JSONArray(listOf("rotating_light")))
        // Erst prüfen, dann senden: niemals Token oder Alarm-Inhalt über Klartext-HTTP.
        val url = config.ntfyServer.trim().trimEnd('/').toHttpUrlOrNull()?.takeIf { it.isHttps && it.username.isEmpty() }
            ?: throw IOException("Ungültige ntfy-Server-URL – nur https:// wird unterstützt")
        val request = Request.Builder()
            .url(url)
            .post(json.toString().toRequestBody(JSON))
        if (config.ntfyToken.isNotBlank()) request.header("Authorization", "Bearer ${config.ntfyToken.trim()}")
        http.execute(request.build())
    }
}

private val JSON = "application/json; charset=utf-8".toMediaType()

private suspend fun OkHttpClient.execute(request: Request) = withContext(Dispatchers.IO) {
    newCall(request).execute().use { response ->
        if (!response.isSuccessful) {
            val detail = response.body.string().take(200)
            throw IOException("HTTP ${response.code}: $detail")
        }
    }
}
