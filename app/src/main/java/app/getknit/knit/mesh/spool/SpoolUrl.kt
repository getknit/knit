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

    /** Whether this URL's scheme is usable in this build. `wss://` always; `ws://` only when [allowCleartext]. */
    fun isAcceptable(
        url: String,
        allowCleartext: Boolean,
    ): Boolean =
        when (schemeOf(url)) {
            WSS_SCHEME -> true
            WS_SCHEME -> allowCleartext
            else -> false
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
        val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
        val authority = rest.substring(0, end)
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
     * The host[:port] for a compact settings row, with the scheme, path and token dropped. Falls back to
     * the redacted URL when the shape is unexpected, so a row never renders empty.
     */
    fun host(url: String): String {
        val withoutToken = redact(url)
        val afterScheme = withoutToken.substringAfter("://", missingDelimiterValue = "")
        val authority = afterScheme.substringBefore('/')
        return authority.ifEmpty { withoutToken }
    }

    /**
     * The host name alone, lowercased: no scheme, user info, port, path or token. What an entitlement ticket's `aud`
     * must equal (docs/VOICE_CALLS.md §2.6) and the key a held ticket is stored under — a spool's audience is a
     * lowercase host name (knit-spool's `SPOOL_TICKET_AUDIENCE`), never a port. Null when [url] carries none.
     */
    fun hostname(url: String): String? {
        val authority = redact(url).substringAfter("://", missingDelimiterValue = "").substringBefore('/').substringAfterLast('@')
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
        val authority = afterScheme.substringBefore('/').ifEmpty { return null }
        val path = afterScheme.removePrefix(authority).trimEnd('/')
        val mountedAt = if (path.endsWith(SPOOL_PATH)) path.removeSuffix(SPOOL_PATH) else ""
        return "$scheme$authority$mountedAt/source"
    }
}
