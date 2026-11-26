package app.mobilemanager.data.tracker

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.mobilemanager.data.work.AppNotifications
import app.mobilemanager.domain.extras.DnsMessages
import app.mobilemanager.domain.extras.DomainMatcher
import app.mobilemanager.domain.extras.IpPackets
import app.mobilemanager.domain.extras.UdpPacket
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

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
        }
    }

    internal fun countQuery(blocked: Boolean) {
        _sessionQueries.value += 1
        if (blocked) _sessionBlocked.value += 1
    }
}

/** Start/Stopp und Systemzustand rund um das lokale VPN. */
@Singleton
class TrackerVpnController @Inject constructor(@ApplicationContext private val context: Context) {

    /** `null`, wenn die VPN-Zustimmung bereits erteilt ist. */
    fun prepareIntent(): Intent? = VpnService.prepare(context)

    fun start() {
        if (prepareIntent() != null) return
        runCatching { ContextCompat.startForegroundService(context, Intent(context, TrackerVpnService::class.java)) }
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

    private var tun: ParcelFileDescriptor? = null
    private var scope: CoroutineScope? = null
    @Volatile private var running = false
    @Volatile private var matcher = DomainMatcher(emptySet())
    private val upstream by lazy { UpstreamDns(getSystemService(ConnectivityManager::class.java)) }
    private val appCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val domainCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val uidNames = ConcurrentHashMap<Int, String>()
    private val writeLock = Any()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            launchDetached { settings.setEnabled(false) }
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
        if (intent?.action == SERVICE_INTERFACE) launchDetached { settings.setEnabled(true) }
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

        val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = serviceScope
        serviceScope.launch { blocklist.matcher.collect { matcher = it } }
        serviceScope.launch {
            var shown = -1
            while (isActive) {
                delay(FLUSH_INTERVAL_MS)
                flushStats()
                val blocked = TrackerVpnState.sessionBlocked.value
                if (blocked != shown) {
                    notifications.updateTrackerServiceNotification(blocked)
                    shown = blocked
                }
            }
        }
        Thread({ readLoop(fd) }, "tracker-vpn").start()
        return true
    }

    private fun readLoop(fd: ParcelFileDescriptor) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val poll = StructPollfd().apply {
            this.fd = fd.fileDescriptor
            events = OsConstants.POLLIN.toShort()
        }
        val buffer = ByteArray(MAX_PACKET)
        while (running) {
            poll.revents = 0
            val ready = try {
                Os.poll(arrayOf(poll), POLL_TIMEOUT_MS)
            } catch (e: ErrnoException) {
                if (e.errno == OsConstants.EINTR) continue else break
            }
            if (ready <= 0) continue
            if (poll.revents.toInt() and (OsConstants.POLLERR or OsConstants.POLLHUP or OsConstants.POLLNVAL) != 0) break
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
        if (blocked) {
            DnsMessages.errorResponse(packet.payload, DnsMessages.RCODE_NXDOMAIN)?.let { reply(packet, it, output) }
            record(packet, question.name)
        } else {
            scope?.launch(FORWARD_DISPATCHER) { forward(packet, output) }
        }
    }

    private fun forward(packet: UdpPacket, output: FileOutputStream) {
        val servers = upstream.servers
        val network = upstream.network
        for (server in servers) {
            try {
                DatagramSocket().use { socket ->
                    protect(socket)
                    network?.let { runCatching { it.bindSocket(socket) } }
                    socket.soTimeout = UPSTREAM_TIMEOUT_MS
                    socket.send(DatagramPacket(packet.payload, packet.payload.size, InetSocketAddress(server, DNS_PORT)))
                    val buffer = ByteArray(MAX_DNS_RESPONSE)
                    val response = DatagramPacket(buffer, buffer.size)
                    socket.receive(response)
                    reply(packet, buffer.copyOf(response.length), output)
                }
                return
            } catch (_: IOException) {
                // nächsten Server versuchen
            }
        }
        DnsMessages.errorResponse(packet.payload, DnsMessages.RCODE_SERVFAIL)?.let { reply(packet, it, output) }
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

    private suspend fun flushStats() {
        val apps = appCounts.keys.associateWith { appCounts[it]?.getAndSet(0) ?: 0 }
        val domains = domainCounts.keys.associateWith { domainCounts[it]?.getAndSet(0) ?: 0 }
        if (apps.values.any { it > 0 }) runCatching { stats.add(apps, domains) }
    }

    override fun onRevoke() {
        // Ein anderes VPN wurde gestartet oder die Zustimmung entzogen – Modul bleibt aus.
        launchDetached { settings.setEnabled(false) }
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
        if (wasRunning) runBlocking(Dispatchers.IO) { runCatching { flushStats() } }
        synchronized(writeLock) {
            runCatching { tun?.close() }
            tun = null
        }
        upstream.stop()
        TrackerVpnState.setRunning(false)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun launchDetached(block: suspend () -> Unit) {
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { block() }
    }

    companion object {
        const val ACTION_STOP = "app.mobilemanager.tracker.STOP"
        private const val VPN_ADDRESS = "10.111.222.1"
        private const val VPN_DNS = "10.111.222.2"
        private const val DNS_PORT = 53
        private const val MAX_PACKET = 32_767
        private const val MAX_DNS_RESPONSE = 4_096
        private const val POLL_TIMEOUT_MS = 1_000
        private const val UPSTREAM_TIMEOUT_MS = 4_000
        private const val FLUSH_INTERVAL_MS = 30_000L

        @OptIn(ExperimentalCoroutinesApi::class)
        private val FORWARD_DISPATCHER = Dispatchers.IO.limitedParallelism(32)

        internal fun isVpnDns(address: InetAddress) = address.hostAddress == VPN_DNS
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
        // IPv4 zuerst – IPv6-Server sind ohne IPv6-Route manchmal nicht erreichbar.
        servers = lp.dnsServers.filterNot(TrackerVpnService::isVpnDns).sortedBy { if (it is Inet4Address) 0 else 1 }
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
}

