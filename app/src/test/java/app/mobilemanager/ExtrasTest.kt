package app.mobilemanager

import app.mobilemanager.domain.extras.DnsMessages
import app.mobilemanager.domain.extras.DomainMatcher
import app.mobilemanager.domain.extras.FocusBrakePolicy
import app.mobilemanager.domain.extras.ForegroundFilter
import app.mobilemanager.domain.extras.HostsParser
import app.mobilemanager.domain.extras.IpPackets
import app.mobilemanager.domain.extras.PerAppVolumePolicy
import app.mobilemanager.domain.extras.SineWave
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ExtrasTest {

    /** DNS-Anfrage für [name] (Typ A) mit ID 0x1234 und gesetztem RD-Bit. */
    private fun dnsQuery(name: String): ByteArray {
        val header = byteArrayOf(0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0)
        val qname = name.split('.').flatMap { label -> listOf(label.length.toByte()) + label.toByteArray().toList() } + 0.toByte()
        return header + qname.toByteArray() + byteArrayOf(0, 1, 0, 1)
    }

    private val client = byteArrayOf(10, 111, 222.toByte(), 1)
    private val vpnDns = byteArrayOf(10, 111, 222.toByte(), 2)

    @Test
    fun `udp packets round trip with valid checksums`() {
        val query = dnsQuery("ads.example.com")
        val packet = IpPackets.buildUdp4(client, vpnDns, 40000, 53, query)
        assertEquals(0, IpPackets.checksum(packet, 0, 20, 0)) // IPv4-Header gültig
        val parsed = IpPackets.parseUdp4(packet)!!
        assertArrayEquals(client, parsed.sourceIp)
        assertArrayEquals(vpnDns, parsed.destinationIp)
        assertEquals(40000, parsed.sourcePort)
        assertEquals(53, parsed.destinationPort)
        assertArrayEquals(query, parsed.payload)
        // UDP-Prüfsumme inkl. Pseudo-Header ergibt 0
        var pseudo = 0L
        for (i in 0 until 4 step 2) pseudo += IpPackets.u16(client, i) + IpPackets.u16(vpnDns, i)
        pseudo += 17 + 8 + query.size
        assertEquals(0, IpPackets.checksum(packet, 20, packet.size - 20, pseudo))
    }

    @Test
    fun `non udp, ipv6 and truncated packets are ignored`() {
        val packet = IpPackets.buildUdp4(client, vpnDns, 1, 53, dnsQuery("a.de"))
        assertNull(IpPackets.parseUdp4(packet.copyOf(10)))
        assertNull(IpPackets.parseUdp4(packet.copyOf().also { it[9] = 6 })) // TCP
        assertNull(IpPackets.parseUdp4(packet.copyOf().also { it[0] = 0x60 })) // IPv6
    }

    @Test
    fun `dns question is parsed and nxdomain response mirrors the query`() {
        val query = dnsQuery("Tracker.Example.COM")
        val question = DnsMessages.parseQuestion(query)!!
        assertEquals("tracker.example.com", question.name)
        assertEquals(1, question.type)

        val response = DnsMessages.errorResponse(query)!!
        assertEquals(0x1234, IpPackets.u16(response, 0))
        val flags = IpPackets.u16(response, 2)
        assertTrue(flags and 0x8000 != 0) // Antwort
        assertTrue(flags and 0x0100 != 0) // RD übernommen
        assertEquals(DnsMessages.RCODE_NXDOMAIN, flags and 0x0F)
        assertEquals(1, IpPackets.u16(response, 4))
        assertEquals(0, IpPackets.u16(response, 6))
        assertEquals(query.size, response.size)
        assertNull(DnsMessages.parseQuestion(response)) // ist keine Anfrage mehr
    }

    @Test
    fun `hosts parser understands hosts, plain and adblock formats`() {
        val parsed = HostsParser.parse(
            sequenceOf(
                "# Kommentar",
                "0.0.0.0 doubleclick.net",
                "127.0.0.1   tracker.example.com  ads.example.com # inline",
                "0.0.0.0 localhost",
                "plain-domain.de",
                "||adserver.example^",
                ":: ipv6.tracker.io",
                "kein gültiger eintrag!",
                "",
            ),
        )
        assertEquals(
            setOf("doubleclick.net", "tracker.example.com", "ads.example.com", "plain-domain.de", "adserver.example", "ipv6.tracker.io"),
            parsed,
        )
    }

    @Test
    fun `domain matcher blocks subdomains and honours the allowlist`() {
        val matcher = DomainMatcher(setOf("doubleclick.net", "tracker.io"), allowed = setOf("ok.tracker.io"))
        assertTrue(matcher.isBlocked("doubleclick.net"))
        assertTrue(matcher.isBlocked("ad.g.DoubleClick.net."))
        assertFalse(matcher.isBlocked("notdoubleclick.net"))
        assertFalse(matcher.isBlocked("ok.tracker.io"))
        assertFalse(matcher.isBlocked("cdn.ok.tracker.io"))
        assertTrue(matcher.isBlocked("bad.tracker.io"))
        assertFalse(matcher.isBlocked(""))
    }

    @Test
    fun `per app volume sets, chains and restores the previous level`() {
        val rules = mapOf("game" to 20, "podcast" to 70)
        var saved: Int? = null
        fun open(pkg: String, current: Int): Int? {
            val d = PerAppVolumePolicy.onForeground(pkg, rules, saved, current, min = 0, max = 15)
            saved = d.savedIndex
            return d.targetIndex
        }
        assertEquals(3, open("game", current = 10)) // 20 % von 15
        assertEquals(10, saved)
        assertEquals(11, open("podcast", current = 3)) // 70 % → 10,5 → 11, Original bleibt gemerkt
        assertEquals(10, saved)
        assertEquals(10, open("browser", current = 11)) // zurück auf den Wert vor der ersten Regel
        assertNull(saved)
        assertNull(open("mail", current = 10))
        assertEquals(50, PerAppVolumePolicy.indexToPercent(5, 0, 10))
    }

    @Test
    fun `focus brake fires only on entry and respects the grace period`() {
        val flagged = setOf("com.instagram.android")
        assertTrue(FocusBrakePolicy.shouldBrake("com.instagram.android", "launcher", flagged, null, 1000))
        assertFalse(FocusBrakePolicy.shouldBrake("com.instagram.android", "com.instagram.android", flagged, null, 1000))
        assertFalse(FocusBrakePolicy.shouldBrake("com.instagram.android", "launcher", flagged, graceUntil = 2000, now = 1000))
        assertTrue(FocusBrakePolicy.shouldBrake("com.instagram.android", "launcher", flagged, graceUntil = 999, now = 1000))
        assertFalse(FocusBrakePolicy.shouldBrake("com.whatsapp", "launcher", flagged, null, 1000))
    }

    @Test
    fun `foreground filter ignores system ui, keyboards and own overlays`() {
        val own = "app.mobilemanager"
        assertFalse(ForegroundFilter.isRelevant("com.android.systemui", null, own, emptySet()))
        assertFalse(ForegroundFilter.isRelevant("com.google.android.inputmethod.latin", null, own, setOf("com.google.android.inputmethod.latin")))
        assertFalse(ForegroundFilter.isRelevant(own, "android.widget.FrameLayout", own, emptySet()))
        assertTrue(ForegroundFilter.isRelevant(own, "app.mobilemanager.launcher.ui.LauncherActivity", own, emptySet()))
        assertTrue(ForegroundFilter.isRelevant("com.spotify.music", null, own, emptySet()))
    }

    @Test
    fun `sine wave has the right length, frequency and soft edges`() {
        val rate = 8000
        val wave = SineWave.generate(frequency = 100.0, seconds = 1, sampleRate = rate, fadeMillis = 100)
        assertEquals(rate, wave.size)
        assertEquals(0, wave.first().toInt())
        assertTrue(abs(wave.last().toInt()) < 200)
        // Nulldurchgänge von - nach + : 100 Hz → etwa 100 pro Sekunde
        val crossings = (1 until wave.size).count { wave[it - 1] < 0 && wave[it] >= 0 }
        assertTrue(crossings in 98..101)
        assertNotNull(wave.maxOrNull())
        assertTrue(wave.max() > Short.MAX_VALUE * 0.9)
    }
}
