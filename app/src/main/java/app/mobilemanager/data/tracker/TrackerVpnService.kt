package app.mobilemanager.data.tracker

import android.app.ForegroundServiceStartNotAllowedException
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.mobilemanager.data.work.AppNotifications
import app.mobilemanager.di.AppCoroutines
import app.mobilemanager.di.ApplicationScope
import app.mobilemanager.domain.extras.DnsMessages
import app.mobilemanager.domain.extras.DomainMatcher
import app.mobilemanager.domain.extras.IpPackets
import app.mobilemanager.domain.extras.UdpPacket
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Laufzeitstatus des Tracker-Blockers (nur im App-Prozess, nicht persistent). */
object TrackerVpnState {
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()
    private val _sessionBlocked = MutableStateFlow(0)
    val sessionBlocked: StateFlow<Int> = _sessionBlocked.asStateFlow()
    private val _sessionQueries = MutableStateFlow(0)
    val sessionQueries: StateFlow<Int> = _sessionQueries.asStateFlow()

    internal fun setRunning(value: Boolean) {
        _running.value = value
        if (value) {
            _sessionBlocked.value = 0
            _sessionQueries.value = 0
            _dohAnswered.value = 0
            _dohFallbacks.value = 0
        }
    }

    /** Phase 4.3: „Durchgehend aktives VPN“ und „Verbindungen ohne VPN blockieren“ (nur im laufenden Dienst lesbar). */
    private val _alwaysOn = MutableStateFlow(false to false)
    val alwaysOn: StateFlow<Pair<Boolean, Boolean>> = _alwaysOn.asStateFlow()

    internal fun setAlwaysOn(alwaysOn: Boolean, lockdown: Boolean) {
        _alwaysOn.value = alwaysOn to lockdown
    }

    /** Phase 4.1: Anfragen, die per DNS-over-HTTPS beantwortet wurden bzw. auf den System-Resolver zurückfielen. */
    private val _dohAnswered = MutableStateFlow(0)
    val dohAnswered: StateFlow<Int> = _dohAnswered.asStateFlow()
    private val _dohFallbacks = MutableStateFlow(0)
    val dohFallbacks: StateFlow<Int> = _dohFallbacks.asStateFlow()

    // Aufrufe kommen parallel aus Lese-Thread und Weiterleitungs-Coroutines → atomar erhöhen.
    internal fun countDoh(ok: Boolean) {
        if (ok) _dohAnswered.update { it + 1 } else _dohFallbacks.update { it + 1 }
    }

    internal fun countQuery(blocked: Boolean) {
        _sessionQueries.update { it + 1 }
        if (blocked) _sessionBlocked.update { it + 1 }
    }
}

/** Start/Stopp und Systemzustand rund um das lokale VPN. */
@Singleton
class TrackerVpnController @Inject constructor(@ApplicationContext private val context: Context) {

    /** `null`, wenn die VPN-Zustimmung bereits erteilt ist. */
    fun prepareIntent(): Intent? = VpnService.prepare(context)

    /** @return `false`, wenn die VPN-Zustimmung fehlt oder Android den Vordergrunddienst nicht starten lässt. */
    fun start(): Boolean {
        if (prepareIntent() != null) return false
        return try {
            ContextCompat.startForegroundService(context, Intent(context, TrackerVpnService::class.java))
            true
        } catch (_: ForegroundServiceStartNotAllowedException) {
            false
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    fun stop() {
        if (!TrackerVpnState.running.value) return
        runCatching { context.startService(Intent(context, TrackerVpnService::class.java).setAction(TrackerVpnService.ACTION_STOP)) }
    }

    /** Läuft gerade ein fremdes VPN (WireGuard, Firmen-VPN …)? Android erlaubt nur eines. */
    fun otherVpnActive(): Boolean {
        if (TrackerVpnState.running.value) return false
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
    }

    /** „Privates DNS“ mit festem Anbieter umgeht das VPN-DNS komplett. */
    fun privateDnsHostname(): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        return cm.getLinkProperties(cm.activeNetwork)?.privateDnsServerName
    }
}

/**
 * Meilenstein 3.3: Rein lokales VPN, das nur den DNS-Verkehr umleitet. Das VPN-Interface bekommt eine
 * virtuelle DNS-Adresse; Anfragen an gesperrte Domains werden sofort mit NXDOMAIN beantwortet, alle anderen
 * unverändert an den DNS-Server des aktuellen Netzes weitergereicht. Kein fremder Server, kein Logging einzelner Anfragen.
 */
@AndroidEntryPoint
class TrackerVpnService : VpnService() {

    @Inject lateinit var settings: TrackerSettingsRepository
    @Inject lateinit var blocklist: TrackerBlocklistRepository
    @Inject lateinit var stats: TrackerStatsRepository
    @Inject lateinit var notifications: AppNotifications
    /** Überlebt den Dienst – für Einstellungen und den letzten Statistik-Flush nach dem Stopp. */
    @Inject @ApplicationScope lateinit var appScope: CoroutineScope

    private var tun: ParcelFileDescriptor? = null
    private var scope: CoroutineScope? = null
    private var readThread: Thread? = null
    /** Self-Pipe: weckt den Lese-Thread beim Stoppen – so kann poll() ohne Timeout (-1) schlafen. */
    private var wakePipe: Array<FileDescriptor>? = null
    /** Signal „es gibt neue Zähler“: Ohne DNS-Verkehr schläft die Flush-Schleife ohne Timer. */
    private val statsDirty = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var running = false
    @Volatile private var matcher = DomainMatcher(emptySet())
    @Volatile private var dohUrl = ""
    private val doh = DohClient()
    private val upstream by lazy { UpstreamDns(getSystemService(ConnectivityManager::class.java)) }
    private val appCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val domainCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val uidNames = ConcurrentHashMap<Int, String>()
    private val writeLock = Any()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            appScope.launch { settings.setEnabled(false) }
            shutdown()
            return START_NOT_STICKY
        }
        ServiceCompat.startForeground(
            this,
            AppNotifications.ID_TRACKER_SERVICE,
            notifications.trackerServiceNotification(TrackerVpnState.sessionBlocked.value),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        // Vom System gestartet ("Durchgehend aktives VPN") → Schalter in der App mitziehen.
        if (intent?.action == SERVICE_INTERFACE) appScope.launch { settings.setEnabled(true) }
        if (tun == null && !establish()) {
            shutdown()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun establish(): Boolean {
        if (prepare(this) != null) return false
        val builder = Builder()
            .setSession("MobileManager Tracker-Blocker")
            .addAddress(VPN_ADDRESS, 32)
            .addDnsServer(VPN_DNS)
            .addRoute(VPN_DNS, 32)
            // IPv6 nicht über das VPN führen, aber auch nicht blockieren.
            .allowFamily(OsConstants.AF_INET6)
            .setBlocking(false)
            .setMetered(false)
        builder.setConfigureIntent(notifications.trackerConfigureIntent())
        // Eigener Datenverkehr (Weiterleitung an den echten DNS, Alarm-Versand) läuft am VPN vorbei.
        runCatching { builder.addDisallowedApplication(packageName) }
        val fd = runCatching { builder.establish() }.getOrNull() ?: return false
        tun = fd
        running = true
        TrackerVpnState.setRunning(true)
        upstream.start()

        val serviceScope = AppCoroutines.scope("TrackerVpn", Dispatchers.IO)
        scope = serviceScope
        serviceScope.launch { blocklist.matcher.collect { matcher = it } }
        serviceScope.launch { settings.settings.collect { dohUrl = it.dohUrl } }
        TrackerVpnState.setAlwaysOn(isAlwaysOn, isLockdownEnabled)
        serviceScope.launch {
            var shown = -1
            for (signal in statsDirty) {
                // Bündeln: höchstens alle 30 s schreiben, aber nur, wenn es seit dem letzten Mal Anfragen gab.
                delay(FLUSH_INTERVAL_MS)
                flushStats()
                val blocked = TrackerVpnState.sessionBlocked.value
                if (blocked != shown) {
                    notifications.updateTrackerServiceNotification(blocked)
                    shown = blocked
                }
            }
        }
        val pipe = runCatching { Os.pipe() }.getOrNull()
        wakePipe = pipe
        readThread = Thread({ readLoop(fd, pipe?.get(0)) }, "tracker-vpn").also { it.start() }
        return true
    }

    private fun readLoop(fd: ParcelFileDescriptor, wake: FileDescriptor?) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val poll = StructPollfd().apply {
            this.fd = fd.fileDescriptor
            events = OsConstants.POLLIN.toShort()
        }
        val wakePoll = wake?.let { StructPollfd().apply { this.fd = it; events = OsConstants.POLLIN.toShort() } }
        val fds = listOfNotNull(poll, wakePoll).toTypedArray()
        // Ohne Self-Pipe (sehr unwahrscheinlich) bleibt ein Sicherheits-Timeout, damit der Thread endet.
        val timeout = if (wakePoll != null) -1 else FALLBACK_POLL_TIMEOUT_MS
        val buffer = ByteArray(MAX_PACKET)
        try {
            pollLoop(fds, poll, wakePoll, timeout, input, output, buffer)
        } finally {
            wake?.let { runCatching { Os.close(it) } }
        }
    }

    private fun pollLoop(
        fds: Array<StructPollfd>,
        poll: StructPollfd,
        wakePoll: StructPollfd?,
        timeout: Int,
        input: FileInputStream,
        output: FileOutputStream,
        buffer: ByteArray,
    ) {
        while (running && !Thread.currentThread().isInterrupted) {
            fds.forEach { it.revents = 0 }
            val ready = try {
                Os.poll(fds, timeout)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue else break
            }
            if (wakePoll != null && wakePoll.revents.toInt() != 0) break
            if (ready <= 0) continue
            if (poll.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) break
            if (poll.revents.toInt() and OsConstants.POLLIN == 0) continue
            val length = try {
                input.read(buffer)
            } catch (_: IOException) {
                if (!running) break else continue
            }
            if (length > 0) handlePacket(buffer.copyOf(length), output)
        }
    }

    private fun handlePacket(data: ByteArray, output: FileOutputStream) {
        val packet = IpPackets.parseUdp4(data) ?: return
        if (packet.destinationPort != DNS_PORT) return
        val question = DnsMessages.parseQuestion(packet.payload) ?: return
        val blocked = matcher.isBlocked(question.name)
        TrackerVpnState.countQuery(blocked)
        statsDirty.trySend(Unit)
        if (blocked) {
            DnsMessages.errorResponse(packet.payload, DnsMessages.RCODE_NXDOMAIN)?.let { reply(packet, it, output) }
            record(packet, question.name)
        } else {
            scope?.launch(FORWARD_DISPATCHER) { forward(packet, output) }
        }
    }

    /**
     * Weiterleitung an das echte Netz. Zuerst über den System-Resolver auf dem Standardnetz: Der kennt die
     * funktionierenden Server, IPv6-only-Mobilfunk mit DNS64/464XLAT, Wiederholungen, TCP-Fallback und „Privates DNS“.
     * Nur wenn das scheitert, werden die DNS-Server des Netzes direkt per UDP gefragt.
     */
    private suspend fun forward(packet: UdpPacket, output: FileOutputStream) {
        val dohAnswer = dohUrl.takeIf { it.isNotEmpty() }?.let { url ->
            doh.query(url, upstream.network, packet.payload).also { TrackerVpnState.countDoh(it != null) }
        }
        val answer = dohAnswer
            ?: withTimeoutOrNull(SYSTEM_RESOLVER_TIMEOUT_MS) { upstream.systemQuery(packet.payload) }
            ?: socketQuery(packet.payload)
        if (answer != null && answer.size in DNS_HEADER_SIZE..MAX_UDP_PAYLOAD) {
            // Antworten aus dem System-Cache können eine fremde Transaktions-ID tragen.
            answer[0] = packet.payload[0]
            answer[1] = packet.payload[1]
            reply(packet, answer, output)
        } else {
            DnsMessages.errorResponse(packet.payload, DnsMessages.RCODE_SERVFAIL)?.let { reply(packet, it, output) }
        }
    }

    private fun socketQuery(query: ByteArray): ByteArray? {
        val network = upstream.network
        for (server in upstream.servers) {
            try {
                DatagramSocket().use { socket ->
                    protect(socket)
                    network?.let { runCatching { it.bindSocket(socket) } }
                    socket.soTimeout = UPSTREAM_TIMEOUT_MS
                    socket.send(DatagramPacket(query, query.size, InetSocketAddress(server, DNS_PORT)))
                    val buffer = ByteArray(MAX_DNS_RESPONSE)
                    val response = DatagramPacket(buffer, buffer.size)
                    socket.receive(response)
                    return buffer.copyOf(response.length)
                }
            } catch (_: IOException) {
                // nächsten Server versuchen
            }
        }
        return null
    }

    private fun reply(request: UdpPacket, dns: ByteArray, output: FileOutputStream) {
        val packet = IpPackets.buildUdp4(request.destinationIp, request.sourceIp, request.destinationPort, request.sourcePort, dns)
        synchronized(writeLock) {
            if (!running) return
            try {
                output.write(packet)
            } catch (_: IOException) {
            }
        }
    }

    /** Pro-App-Zuordnung über die Socket-Tabelle des Kernels (nur für das aktive VPN erlaubt). */
    private fun record(packet: UdpPacket, domain: String) {
        val uid = runCatching {
            getSystemService(ConnectivityManager::class.java).getConnectionOwnerUid(
                OsConstants.IPPROTO_UDP,
                InetSocketAddress(InetAddress.getByAddress(packet.sourceIp), packet.sourcePort),
                InetSocketAddress(InetAddress.getByAddress(packet.destinationIp), packet.destinationPort),
            )
        }.getOrDefault(Process.INVALID_UID)
        val app = if (uid == Process.INVALID_UID || uid < Process.FIRST_APPLICATION_UID) {
            TrackerStatsRepository.UNKNOWN_APP
        } else {
            uidNames.getOrPut(uid) { packageManager.getPackagesForUid(uid)?.firstOrNull() ?: TrackerStatsRepository.UNKNOWN_APP }
        }
        appCounts.getOrPut(app) { AtomicInteger() }.incrementAndGet()
        domainCounts.getOrPut(domain) { AtomicInteger() }.incrementAndGet()
    }

    /** Nimmt die offenen Zähler als unveränderlichen Snapshot heraus (und setzt sie dabei auf 0). */
    private fun takeStats(): Pair<Map<String, Int>, Map<String, Int>> =
        appCounts.keys.associateWith { appCounts[it]?.getAndSet(0) ?: 0 }.filterValues { it > 0 } to
            domainCounts.keys.associateWith { domainCounts[it]?.getAndSet(0) ?: 0 }.filterValues { it > 0 }

    private suspend fun persist(snapshot: Pair<Map<String, Int>, Map<String, Int>>) {
        if (snapshot.first.isNotEmpty() || snapshot.second.isNotEmpty()) {
            runCatching { stats.add(snapshot.first, snapshot.second) }
        }
    }

    private suspend fun flushStats() = persist(takeStats())

    override fun onRevoke() {
        // Ein anderes VPN wurde gestartet oder die Zustimmung entzogen – Modul bleibt aus.
        appScope.launch { settings.setEnabled(false) }
        notifications.showTrackerRevoked()
        shutdown()
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        val wasRunning = running
        running = false
        scope?.cancel()
        scope = null
        readThread?.interrupt()
        readThread = null
        wakePipe?.let { pipe ->
            // Schreibende schließen → POLLHUP am Leseende weckt poll(); danach beide Enden freigeben.
            // Das Leseende schließt der Lese-Thread selbst, sobald er poll() verlassen hat.
            runCatching { Os.close(pipe[1]) }
        }
        wakePipe = null
        // Kein Blockieren des Main-Threads: Snapshot jetzt ziehen, asynchron im App-Scope schreiben.
        if (wasRunning) {
            val snapshot = takeStats()
            appScope.launch { persist(snapshot) }
        }
        synchronized(writeLock) {
            runCatching { tun?.close() }
            tun = null
        }
        upstream.stop()
        TrackerVpnState.setRunning(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val ACTION_STOP = "app.mobilemanager.tracker.STOP"
        private const val VPN_ADDRESS = "10.111.222.1"
        private const val VPN_DNS = "10.111.222.2"
        private const val DNS_PORT = 53
        private const val MAX_PACKET = 32_767
        private const val MAX_DNS_RESPONSE = 4_096
        private const val FALLBACK_POLL_TIMEOUT_MS = 5_000
        private const val UPSTREAM_TIMEOUT_MS = 2_000
        private const val SYSTEM_RESOLVER_TIMEOUT_MS = 6_000L
        private const val DNS_HEADER_SIZE = 12
        private const val MAX_UDP_PAYLOAD = 65_000
        private const val FLUSH_INTERVAL_MS = 30_000L

        @OptIn(ExperimentalCoroutinesApi::class)
        private val FORWARD_DISPATCHER = Dispatchers.IO.limitedParallelism(32)

        internal fun isVpnDns(address: InetAddress) = address.hostAddress == VPN_DNS
    }
}

/**
 * Phase 4.1: DNS-over-HTTPS (RFC 8484, POST application/dns-message). Der Client ist an das echte Netz gebunden;
 * die eigene App ist ohnehin vom VPN ausgenommen, die Auflösung des DoH-Hosts läuft also nicht im Kreis.
 */
private class DohClient {
    @Volatile private var client: OkHttpClient? = null
    @Volatile private var clientNetwork: Network? = null

    fun query(url: String, network: Network?, query: ByteArray): ByteArray? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header("Accept", DNS_MESSAGE)
            .post(query.toRequestBody(DNS_MESSAGE_TYPE))
            .build()
        clientFor(network).newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body.bytes().takeIf { it.size >= 12 }
        }
    }.getOrNull()

    @Synchronized
    private fun clientFor(network: Network?): OkHttpClient {
        client?.takeIf { clientNetwork == network }?.let { return it }
        val builder = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
        if (network != null) {
            builder.socketFactory(network.socketFactory)
            builder.dns { host -> network.getAllByName(host).toList() }
        }
        return builder.build().also {
            client = it
            clientNetwork = network
        }
    }

    private companion object {
        const val DNS_MESSAGE = "application/dns-message"
        val DNS_MESSAGE_TYPE = DNS_MESSAGE.toMediaType()
    }
}

/** Beobachtet das Standardnetz (am VPN vorbei, da die eigene App ausgenommen ist) und dessen DNS-Server. */
private class UpstreamDns(private val cm: ConnectivityManager) {
    @Volatile var network: Network? = null
        private set
    @Volatile var servers: List<InetAddress> = emptyList()
        private set
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = update(network, linkProperties)

        override fun onLost(network: Network) {
            if (network == this@UpstreamDns.network) {
                this@UpstreamDns.network = null
                servers = emptyList()
            }
        }
    }

    private fun update(network: Network, lp: LinkProperties) {
        this.network = network
        // Ohne IPv4-Route (IPv6-only-Mobilfunk) wären IPv4-Server nur Zeitverschwendung – dann IPv6 zuerst.
        val hasIpv4Route = lp.routes.any { it.isDefaultRoute && it.destination.address is Inet4Address }
        servers = lp.dnsServers.filterNot(TrackerVpnService::isVpnDns)
            .sortedBy { if ((it is Inet4Address) == hasIpv4Route) 0 else 1 }
    }

    /** Rohe DNS-Anfrage über den System-Resolver des Standardnetzes; `null` bei Fehler. */
    suspend fun systemQuery(query: ByteArray): ByteArray? = suspendCancellableCoroutine { cont ->
        val cancel = CancellationSignal()
        cont.invokeOnCancellation { cancel.cancel() }
        val callback = object : DnsResolver.Callback<ByteArray> {
            override fun onAnswer(answer: ByteArray, rcode: Int) {
                if (cont.isActive) cont.resume(answer)
            }

            override fun onError(error: DnsResolver.DnsException) {
                if (cont.isActive) cont.resume(null)
            }
        }
        runCatching {
            DnsResolver.getInstance().rawQuery(network, query, DnsResolver.FLAG_EMPTY, DIRECT, cancel, callback)
        }.onFailure { if (cont.isActive) cont.resume(null) }
    }

    fun start() {
        if (registered) return
        cm.activeNetwork?.let { n -> cm.getLinkProperties(n)?.let { update(n, it) } }
        runCatching { cm.registerDefaultNetworkCallback(callback) }.onSuccess { registered = true }
    }

    fun stop() {
        if (!registered) return
        runCatching { cm.unregisterNetworkCallback(callback) }
        registered = false
    }

    private companion object {
        val DIRECT = Executor { it.run() }
    }
}

