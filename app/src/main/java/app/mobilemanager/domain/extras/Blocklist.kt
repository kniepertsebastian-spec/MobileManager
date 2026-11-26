package app.mobilemanager.domain.extras

/** Liest Blocklisten im Hosts-Format („0.0.0.0 tracker.example“), als reine Domainliste oder im Adblock-Stil („||domain^“). */
object HostsParser {
    private val IGNORED = setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "ip6-localhost", "ip6-loopback", "0.0.0.0")
    private val DOMAIN = Regex("^[a-z0-9_]([a-z0-9_-]{0,62})(\\.[a-z0-9_]([a-z0-9_-]{0,62}))+$")

    fun parse(lines: Sequence<String>): Set<String> = buildSet {
        lines.forEach { raw ->
            val line = raw.substringBefore('#').substringBefore('!').trim()
            if (line.isEmpty()) return@forEach
            if (line.startsWith("||")) {
                normalize(line.removePrefix("||").substringBefore('^').substringBefore('$'))?.let(::add)
                return@forEach
            }
            val tokens = line.split(Regex("\\s+"))
            val candidates = if (tokens.size >= 2 && looksLikeIp(tokens[0])) tokens.drop(1) else tokens.take(1)
            candidates.forEach { token -> normalize(token)?.let(::add) }
        }
    }

    fun normalize(token: String): String? {
        val domain = token.trim().trimEnd('.').lowercase()
        if (domain in IGNORED || !DOMAIN.matches(domain)) return null
        return domain
    }

    private fun looksLikeIp(token: String) = token.contains(':') || token.all { it.isDigit() || it == '.' }
}

/**
 * Prüft Domains gegen Block- und Erlaubtliste. Ein Eintrag sperrt auch alle Subdomains
 * („doubleclick.net“ sperrt „ad.doubleclick.net“). Die Erlaubtliste hat Vorrang.
 */
class DomainMatcher(private val blocked: Set<String>, private val allowed: Set<String> = emptySet()) {

    val size: Int get() = blocked.size

    fun isBlocked(name: String): Boolean {
        val domain = name.trimEnd('.').lowercase()
        if (domain.isEmpty()) return false
        if (suffixes(domain).any { it in allowed }) return false
        return suffixes(domain).any { it in blocked }
    }

    private fun suffixes(domain: String): Sequence<String> = generateSequence(domain) { current ->
        val dot = current.indexOf('.')
        if (dot < 0) null else current.substring(dot + 1)
    }
}
