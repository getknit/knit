package app.getknit.knit.mesh.spool

/**
 * A TURN server's **entry**: the `host:port` of its UDP listener (docs/SPOOL_PROTOCOL.md §7.6, "Several TURN servers").
 * It is what a spool lists in HELLO `turnHosts` (S-7.6-48), what a `turnreq` names in `host` (S-7.6-49), what the phone
 * measures (C-7.6-52), and how it tells which server a credential is for (C-7.6-51): the entry of the credential's own
 * UDP URL. Pure string work, so the spool's list, a credential and the probe all read one rule.
 *
 * A server's UDP URL is the first of its URLs whose scheme is `turn:` and whose `transport` is `udp` or absent (RFC 7065
 * §3: UDP is the default). Its entry is that URI's host, lowercased, with an IPv6 literal keeping its brackets, then
 * `:` and the port as written, or [DEFAULT_PORT] where the URI leaves it out. Every side must derive it alike, so this
 * reads a URI exactly as knit-spool's `TurnUris` does (its :protocol module): `turn:203.0.113.5:03478` is
 * `203.0.113.5:03478`, and a URI with no readable host has no entry.
 */
object TurnHosts {
    /** The most servers one spool names (S-7.6-48); a longer list is read to this many. */
    const val MAX = 4

    /** RFC 7065's default for a `turn:` URI with no port. */
    const val DEFAULT_PORT = "3478"

    // A DNS name is at most 253 characters; with brackets, a colon and five digits an entry is never past this.
    private const val MAX_ENTRY = 262
    private const val MAX_PORT = 65_535

    // RFC 3986's `port` is a run of digits; five is the most a port number needs.
    private val PORT = Regex("[0-9]{1,5}")

    // What no `host:port` holds: blanks, controls, a path or a user part.
    private val NOT_IN_AN_ENTRY = Regex("[\\s\\p{Cntrl}/@]")

    /** One parsed entry: [host] without brackets, ready to resolve, and [port]. */
    class Entry(
        val host: String,
        val port: Int,
    )

    /** The entry of the server whose URLs are [urls]: its first UDP URL's, or null when it has none or it names no host. */
    fun entryOf(urls: List<String>): String? = urls.firstOrNull(::isUdp)?.let(::udpEntry)

    /** Whether [url] is a UDP URL: `turn:`, not `turns:`, with a `transport` of `udp` (any case) or none. */
    fun isUdp(url: String): Boolean {
        if (!url.startsWith("turn:")) return false
        val transport =
            url
                .substringAfter('?', missingDelimiterValue = "")
                .split('&')
                .firstOrNull { it.startsWith("transport=", ignoreCase = true) }
                ?.substringAfter('=')
        return transport == null || transport.equals("udp", ignoreCase = true)
    }

    /** [url]'s entry when it is a UDP URL that names a host, else null. */
    fun udpEntry(url: String): String? {
        if (!isUdp(url)) return null
        return authority(url)?.let { (host, port) -> "$host:${port ?: DEFAULT_PORT}" }
    }

    /**
     * [entry] as a `host:port` a probe can dial, or null when it is not one: the form S-7.6-48 sends (a lowercase host,
     * an IPv6 literal in brackets, the port written out) with a port a socket can take. What a connection reads a
     * HELLO's list through, so a hostile list holds nothing a probe could misread.
     */
    fun parse(entry: String): Entry? {
        if (entry.length > MAX_ENTRY || NOT_IN_AN_ENTRY.containsMatchIn(entry)) return null
        val (host, port) = authority("turn:$entry") ?: return null
        if (port == null || "$host:$port" != entry) return null
        val number = port.toInt().takeIf { it in 1..MAX_PORT } ?: return null
        return Entry(host = host.removePrefix("[").removeSuffix("]"), port = number)
    }

    /**
     * The host of a `turn:` or `turns:` URI, lowercased with an IPv6 literal's brackets kept, whatever its transport:
     * what tells which server a refusal came from (C-7.6-54), or null when [url] names none.
     */
    fun urlHost(url: String): String? = authority(url)?.first

    // `TurnUris.authority`: the lowercased host and the port as written (null when absent), or null with no host.
    private fun authority(url: String): Pair<String, String?>? {
        val rest =
            when {
                url.startsWith("turns:") -> url.removePrefix("turns:")
                url.startsWith("turn:") -> url.removePrefix("turn:")
                else -> return null
            }.substringBefore('?')
        val (host, tail) =
            if (rest.startsWith('[')) {
                val close = rest.indexOf(']')
                if (close < 0) return null
                rest.substring(0, close + 1) to rest.substring(close + 1)
            } else {
                rest.substringBefore(':') to rest.substring(rest.substringBefore(':').length)
            }
        if (host.isEmpty() || host == "[]") return null
        val port =
            when {
                tail.isEmpty() -> null
                tail.startsWith(':') && PORT.matches(tail.substring(1)) -> tail.substring(1)
                else -> return null
            }
        return host.lowercase() to port
    }
}
