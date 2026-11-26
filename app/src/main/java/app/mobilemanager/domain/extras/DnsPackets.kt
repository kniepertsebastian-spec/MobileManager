package app.mobilemanager.domain.extras

/** Ein UDP-Paket über IPv4, wie es aus dem TUN-Interface des VPN gelesen wird. */
class UdpPacket(
    val sourceIp: ByteArray,
    val destinationIp: ByteArray,
    val sourcePort: Int,
    val destinationPort: Int,
    val payload: ByteArray,
)

/** Minimaler IPv4/UDP-Parser und -Builder für den DNS-Verkehr des lokalen VPN (Meilenstein 3.3.1). */
object IpPackets {
    private const val PROTOCOL_UDP = 17
    private const val IPV4_HEADER = 20
    private const val UDP_HEADER = 8

    fun parseUdp4(buffer: ByteArray, length: Int = buffer.size): UdpPacket? {
        if (length < IPV4_HEADER + UDP_HEADER) return null
        val version = (buffer[0].toInt() shr 4) and 0x0F
        if (version != 4) return null
        val headerLength = (buffer[0].toInt() and 0x0F) * 4
        if (headerLength < IPV4_HEADER || length < headerLength + UDP_HEADER) return null
        val totalLength = u16(buffer, 2)
        if (totalLength > length || totalLength < headerLength + UDP_HEADER) return null
        // Fragmente (MF-Flag oder Offset) ignorieren – DNS-Anfragen sind praktisch nie fragmentiert.
        if (u16(buffer, 6) and 0x3FFF != 0) return null
        if (buffer[9].toInt() and 0xFF != PROTOCOL_UDP) return null
        val udpLength = u16(buffer, headerLength + 4)
        val payloadEnd = minOf(headerLength + udpLength, totalLength)
        if (payloadEnd < headerLength + UDP_HEADER) return null
        return UdpPacket(
            sourceIp = buffer.copyOfRange(12, 16),
            destinationIp = buffer.copyOfRange(16, 20),
            sourcePort = u16(buffer, headerLength),
            destinationPort = u16(buffer, headerLength + 2),
            payload = buffer.copyOfRange(headerLength + UDP_HEADER, payloadEnd),
        )
    }

    fun buildUdp4(sourceIp: ByteArray, destinationIp: ByteArray, sourcePort: Int, destinationPort: Int, payload: ByteArray): ByteArray {
        val total = IPV4_HEADER + UDP_HEADER + payload.size
        val packet = ByteArray(total)
        packet[0] = 0x45
        put16(packet, 2, total)
        put16(packet, 6, 0x4000) // Don't Fragment
        packet[8] = 64 // TTL
        packet[9] = PROTOCOL_UDP.toByte()
        sourceIp.copyInto(packet, 12)
        destinationIp.copyInto(packet, 16)
        put16(packet, 10, checksum(packet, 0, IPV4_HEADER, 0))

        val udp = IPV4_HEADER
        put16(packet, udp, sourcePort)
        put16(packet, udp + 2, destinationPort)
        put16(packet, udp + 4, UDP_HEADER + payload.size)
        payload.copyInto(packet, udp + UDP_HEADER)
        // Pseudo-Header: Quell-/Ziel-IP, Protokoll, UDP-Länge
        var pseudo = 0L
        for (i in 0 until 4 step 2) {
            pseudo += u16(sourceIp, i) + u16(destinationIp, i)
        }
        pseudo += PROTOCOL_UDP + UDP_HEADER + payload.size
        val udpChecksum = checksum(packet, udp, UDP_HEADER + payload.size, pseudo)
        put16(packet, udp + 6, if (udpChecksum == 0) 0xFFFF else udpChecksum)
        return packet
    }

    /** Internet-Prüfsumme (RFC 1071). Ein gültiges Paket ergibt über sich selbst 0. */
    fun checksum(data: ByteArray, offset: Int, length: Int, initial: Long): Int {
        var sum = initial
        var i = offset
        val end = offset + length
        while (i + 1 < end) {
            sum += u16(data, i)
            i += 2
        }
        if (i < end) sum += (data[i].toInt() and 0xFF) shl 8
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)
        return (sum.inv() and 0xFFFF).toInt()
    }

    fun u16(data: ByteArray, offset: Int): Int = ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun put16(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value shr 8).toByte()
        data[offset + 1] = value.toByte()
    }
}

data class DnsQuestion(val name: String, val type: Int, val endOffset: Int)

/** DNS-Nachrichten: Frage lesen, Sperr-/Fehlerantwort bauen (Meilenstein 3.3.2). */
object DnsMessages {
    private const val HEADER = 12
    const val RCODE_SERVFAIL = 2
    const val RCODE_NXDOMAIN = 3

    fun parseQuestion(message: ByteArray): DnsQuestion? {
        if (message.size < HEADER + 5) return null
        val flags = IpPackets.u16(message, 2)
        if (flags and 0x8000 != 0) return null // keine Anfrage
        if (IpPackets.u16(message, 4) < 1) return null
        val labels = mutableListOf<String>()
        var i = HEADER
        while (true) {
            if (i >= message.size) return null
            val len = message[i].toInt() and 0xFF
            if (len == 0) {
                i++
                break
            }
            if (len and 0xC0 != 0 || len > 63) return null // Kompression in Fragen nicht erwartet
            if (i + 1 + len > message.size) return null
            labels += String(message, i + 1, len, Charsets.US_ASCII)
            i += 1 + len
            if (labels.size > 127) return null
        }
        if (i + 4 > message.size) return null
        return DnsQuestion(labels.joinToString(".").lowercase(), IpPackets.u16(message, i), i + 4)
    }

    /** Antwort mit gleicher ID und Frage, aber ohne Einträge – NXDOMAIN für gesperrte Domains. */
    fun errorResponse(query: ByteArray, rcode: Int = RCODE_NXDOMAIN): ByteArray? {
        val question = parseQuestion(query) ?: return null
        val response = query.copyOfRange(0, question.endOffset)
        val queryFlags = IpPackets.u16(query, 2)
        val opcodeAndRd = queryFlags and 0x7900 // Opcode + RD übernehmen
        val flags = 0x8000 or opcodeAndRd or 0x0080 or (rcode and 0x0F) // QR, RA, RCODE
        response[2] = (flags shr 8).toByte()
        response[3] = flags.toByte()
        // QDCOUNT = 1, AN/NS/AR = 0
        response[4] = 0; response[5] = 1
        for (k in 6 until 12) response[k] = 0
        return response
    }
}
