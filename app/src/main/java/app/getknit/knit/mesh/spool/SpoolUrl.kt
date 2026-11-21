package app.getknit.knit.mesh.spool

/**
 * The pure rules about a spool URL's *text*, shared by the dialer and the settings UI.
 *
 * [isAcceptable] exists here rather than inside [OkHttpSpoolDialer] because it is a security rule with
 * two callers: the dialer refuses a bad scheme at dial time (ADR 019 — a release APK must not be
 * pointable at a plaintext relay however its settings are edited), and the relay editor refuses to
 * *store* one so the user is told at the point of entry instead of watching a row sit unreachable
 * forever. Two copies of that rule would eventually disagree, and the copy that drifts is the one that
 * lets a `ws://` URL into a release build.
 *
 * A scheme is read through [schemeOf] or not at all — never a bare `startsWith(WSS_SCHEME)`. It is
 * case-insensitive (RFC 3986 §3.1), and [sourceUrl] picks https-or-http off the same read, so a
 * case-sensitive test there would GET a `WSS://` relay's build document in the clear. ADR 2026-09.66cw.
 *
 * The same rule covers the URL's *shape* ([isWellFormed], #340): its authority is a bare `host[:port]`, the whole URL
 * is printable ASCII, and nothing in it is a `\` or a `#`. A relay invite, a card's hint and a scope config all hand
 * over a URL someone else typed, and OkHttp reads its authority as a browser does: `a@b` dials `b`, and `b\@a` or
 * `b#@a` dial `b` too. A label read off the text by hand would name `a` while the socket reaches `b`. So the
 * authority is split here the way the socket splits it ([authorityOf]), every door refuses the forms where the two
 * could still differ, and the dialer refuses them again, so a URL stored before the rule never dials.
 */
object SpoolUrl {
    private const val WSS_SCHEME = "wss://"
    private const val WS_SCHEME = "ws://"

    /** The record layer's own path (spec B-7.1-1), which a URL carries and the HTTP routes beside it do not. */
    private const val SPOOL_PATH = "/spool/v1"

    /**
     * Which of our two schemes [url] carries, in lowercase, or null for any other. The single place that
     * reads a scheme: a scheme is case-insensitive (RFC 3986 §3.1) and OkHttp normalises one before it
     * dials, so a rule of ours that read `WSS://` as "not wss" would be a rule that disagrees with the
     * socket — which is the whole reason [isAcceptable] lives here rather than in the dialer.
     */
    private fun schemeOf(url: String): String? =
        when {
            url.startsWith(WSS_SCHEME, ignoreCase = true) -> WSS_SCHEME
            url.startsWith(WS_SCHEME, ignoreCase = true) -> WS_SCHEME
            else -> null
        }

    /**
     * Whether this URL is usable in this build: a scheme it may dial (`wss://` always, `ws://` only when
     * [allowCleartext]) on a URL [isWellFormed] passes.
     */
    fun isAcceptable(
        url: String,
        allowCleartext: Boolean,
    ): Boolean {
        val scheme =
            when (schemeOf(url)) {
                WSS_SCHEME -> true
                WS_SCHEME -> allowCleartext
                else -> false
            }
        return scheme && isWellFormed(url)
    }

    /**
     * Whether [url] has the one shape a spool URL takes, whatever its scheme: `scheme://host[:port]` then nothing, a
     * path or a query, all printable ASCII, with no `\` and no `#` anywhere. The host is dot-separated labels of
     * letters, digits, `-` and `_` or a bracketed IPv6 literal; a port is 1..65535 with no leading zero. A host the
     * resolver would read as a number (every label digits or `0x` hex) must be four plain decimal octets, because
     * `inet_aton` reads `010` as octal and `3232235777` as one address. No user info, no percent-escape and no non-ASCII
     * letter can reach the host, so what [host] shows is what the socket dials (#340, ADR 2026-11.ut8g).
     */
    fun isWellFormed(url: String): Boolean {
        val match = SHAPE.matchEntire(url) ?: return false
        val (host, port) = match.destructured
        val numeric = host.split('.').all { NUMERIC_LABEL.matches(it) }
        return (!numeric || DOTTED_QUAD.matches(host)) && (port.isEmpty() || port.toInt() <= MAX_PORT)
    }

    private val SHAPE =
        Regex(
            "^[A-Za-z]+://" +
                "([A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*|\\[[0-9A-Fa-f:.]+])" +
                "(?::([1-9][0-9]{0,4}))?" +
                // Printable ASCII but `#` (0x23) and `\` (0x5C).
                "(?:[/?][\\x21\\x22\\x24-\\x5B\\x5D-\\x7E]*)?$",
        )

    /** A label `inet_aton` would read as part of a number: decimal, octal or `0x` hex. */
    private val NUMERIC_LABEL = Regex("[0-9]+|0[xX][0-9A-Fa-f]*")

    /** The one numeric host whose text is the address dialled: four octets 0..255, no leading zero. */
    private val DOTTED_QUAD = Regex("(?:$OCTET\\.){3}$OCTET")

    private const val OCTET = "(?:25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])"

    private const val MAX_PORT = 65_535

    /** What ends an authority when OkHttp reads one: a `\` is a `/` to it, as to a browser. */
    private const val AUTHORITY_END = "/\\?#"

    /** [afterScheme]'s authority, user info included, ended where the socket ends it. */
    private fun authorityOf(afterScheme: String): String {
        val end = afterScheme.indexOfFirst { it in AUTHORITY_END }
        return if (end < 0) afterScheme else afterScheme.substring(0, end)
    }

    /**
     * A house relay's former host name → its current one (knit-spool-coordinator ADR 2026-10.ftp9): the same spool,
     * answering both names until the former is retired. Matched exactly, never as a suffix — `lab.lax.spool.getknit.app`
     * is the lab relay, a different spool.
     */
    private val RENAMED_HOSTS = mapOf("lax.spool.getknit.app" to "public-1.spool.getknit.app")

    /** The current name of a renamed house relay, or [host] itself. Exact and case-insensitive. */
    fun currentHost(host: String): String = RENAMED_HOSTS[host.lowercase()] ?: host

    /** The former name of a renamed house relay, or [host] itself. Exact and case-insensitive. */
    fun formerHost(host: String): String = RENAMED_HOSTS.entries.firstOrNull { it.value == host.lowercase() }?.key ?: host

    /**
     * The one *stored* form of [url]: the scheme lowercased, a renamed house relay's former host name
     * replaced by its current one, and nothing else touched. Every door that writes a relay into settings
     * puts it through this, because relays are matched downstream by exact string (`RelayInviteApplier`
     * compares [redact]ed URLs, `SettingsStore` holds a `Set<String>`) — so without it a hand-typed
     * `WSS://relay/spool/v1` and an invite's `wss://relay/spool/v1?k=…` would be two rows for one host, and
     * the untokened one would be refused `4001` forever. The rename keeps an old card or invite from bringing
     * the former name back once `SettingsStore.renameRelays` has moved a phone off it.
     *
     * Only the scheme and a renamed host. The authority is case-insensitive too, but lowercasing it would
     * rewrite what the user typed for no gain here, and a path and a bearer token are both case-*sensitive*.
     * A port, user info, path and token survive the rename as they were.
     */
    fun canonical(url: String): String {
        val scheme = schemeOf(url) ?: return url
        val rest = url.substring(scheme.length)
        val authority = authorityOf(rest)
        val end = authority.length
        val userInfo = authority.substringBeforeLast('@', missingDelimiterValue = "")
        val hostPort = authority.substringAfterLast('@')
        // An IPv6 literal is never a renamed relay.
        if (hostPort.startsWith('[')) return scheme + rest
        val host = hostPort.substringBefore(':')
        val renamed = RENAMED_HOSTS[host.lowercase()] ?: return scheme + rest
        val prefix = if (authority.contains('@')) "$userInfo@" else ""
        return scheme + prefix + renamed + hostPort.substring(host.length) + rest.substring(end)
    }

    /**
     * Strips any `?k=` bearer token. Every rendering of a spool URL — a log line, a settings row, a
     * diagnostics dump — goes through this: the token is the whole access control for a private spool
     * (spec §7.1), so it must not reach a screenshot, a bug report, or logcat.
     */
    fun redact(url: String): String = url.substringBefore('?')

    /**
     * The host[:port] for a compact settings row and the invite sheet's title, with the scheme, user info, path and
     * token dropped: the host the socket dials, read off the same split ([authorityOf]). Falls back to the redacted
     * URL when the shape is unexpected, so a row never renders empty.
     */
    fun host(url: String): String {
        val withoutToken = redact(url)
        val afterScheme = withoutToken.substringAfter("://", missingDelimiterValue = "")
        return authorityOf(afterScheme).substringAfterLast('@').ifEmpty { withoutToken }
    }

    /**
     * The host name alone, lowercased: no scheme, user info, port, path or token. What an entitlement ticket's `aud`
     * must equal (docs/VOICE_CALLS.md §2.6) and the key a held ticket is stored under — a spool's audience is a
     * lowercase host name (knit-spool's `SPOOL_TICKET_AUDIENCE`), never a port. Null when [url] carries none.
     */
    fun hostname(url: String): String? {
        val authority = authorityOf(redact(url).substringAfter("://", missingDelimiterValue = "")).substringAfterLast('@')
        val name = if (authority.startsWith('[')) authority.substringBefore(']') + "]" else authority.substringBefore(':')
        return name.lowercase().ifEmpty { null }
    }

    /**
     * Where this spool publishes what it is running: its `GET /source` document, derived from the URL we
     * dial by swapping the scheme and replacing [SPOOL_PATH]. Null when the URL is not one this build
     * would dial at all, so the plaintext rule [isAcceptable] enforces covers this fetch too — a release
     * APK must no more read a relay's build over cleartext than sync with it.
     *
     * The path is replaced rather than the whole URL discarded because the daemon serves `/source` beside
     * the socket: a relay mounted at a proxy prefix answers at that prefix, and only a URL that does not
     * carry the spec'd path falls back to the origin.
     */
    fun sourceUrl(
        url: String,
        allowCleartext: Boolean,
    ): String? {
        if (!isAcceptable(url, allowCleartext)) return null
        val withoutToken = redact(url)
        val scheme = if (schemeOf(withoutToken) == WSS_SCHEME) "https://" else "http://"
        val afterScheme = withoutToken.substringAfter("://", missingDelimiterValue = "")
        val authority = authorityOf(afterScheme).ifEmpty { return null }
        val path = afterScheme.removePrefix(authority).trimEnd('/')
        val mountedAt = if (path.endsWith(SPOOL_PATH)) path.removeSuffix(SPOOL_PATH) else ""
        return "$scheme$authority$mountedAt/source"
    }
}
