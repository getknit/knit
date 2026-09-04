package app.getknit.knit.linkpreview

import app.getknit.knit.mesh.crypto.ContactCard
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import app.getknit.knit.ui.chat.findUrls
import java.net.IDN

/**
 * Which link in a draft gets a card, and what a link has to look like before this phone will fetch it.
 *
 * The rules are deliberately narrower than "a URL": the fetch is a socket this phone opens toward an address
 * somebody else typed, so anything that could point it at the wrong place — a non-standard port (an admin
 * panel), an IP literal or a single-label name (the LAN), a `.local`/`.onion` name (a lookup that leaks), a
 * user-info prefix (a spoofed host) — is refused here, before a resolver or a socket is involved, and again
 * for every redirect hop. A Knit contact link is refused too: it is a card of its own, not a web page.
 *
 * [normalize] is also the equality the receiver uses ([sameUrl]) to check that a card describes a link that
 * is actually in the message body, so a sender cannot attach a bank's card to a phishing link.
 *
 * Pure Kotlin, no Android and no HTTP library (the `okhttp3` fence in `rules/mesh.md` holds here too).
 */
object LinkPreviewPolicy {
    /** How many links at the head of a body are tried before giving up on a card for it. */
    const val CANDIDATES = 3

    /** The first link in [body] that passes [normalize], normalized, or null when none of the first [CANDIDATES] do. */
    fun firstEligible(body: String): String? = findUrls(body).take(CANDIDATES).firstNotNullOfOrNull { normalize(it.url) }

    /**
     * [url] in the one canonical form the fetcher, the memo and the receiver's spoof check all agree on, or null
     * when it is not a link this phone will fetch: `https` (a typed `http` is upgraded — the fetch is
     * https-only, and the body keeps the link as typed), the default port only, a public multi-label host
     * (lower-cased, IDN as punycode), the fragment dropped and the path and query kept as they were.
     */
    fun normalize(url: String): String? {
        val trimmed = url.trim()
        if (trimmed.isEmpty() || trimmed.length > LinkPreviewBlob.MAX_URL_LENGTH || ContactCard.looksLikeCard(trimmed)) return null
        val match = URL.matchEntire(trimmed) ?: return null
        val (_, authority, rest) = match.destructured
        val host = acceptableHost(authority) ?: return null
        val path = rest.substringBefore('#').ifEmpty { "/" }
        return "https://$host$path"
    }

    /** Whether [a] and [b] are the same link once normalized; false when either is not a fetchable link at all. */
    fun sameUrl(
        a: String,
        b: String,
    ): Boolean {
        val left = normalize(a) ?: return false
        return left == normalize(b)
    }

    /** The host a card is labelled with, derived from its URL: lower-case, `www.` dropped. */
    fun displayHost(url: String): String = LinkPreviewBlob.hostOf(url)

    /**
     * The host part of [authority] when it is one this phone will fetch from, else null: no user-info, no
     * bracketed IPv6 or dotted IPv4 literal, an explicit port only when it is 443, at least two labels, and
     * none of the suffixes that name a local or onion network.
     */
    private fun acceptableHost(authority: String): String? {
        if ('@' in authority || authority.startsWith("[")) return null
        val hostPart = authority.substringBefore(':')
        val port = authority.substringAfter(':', missingDelimiterValue = "")
        if (port.isNotEmpty() && port != HTTPS_PORT) return null
        val ascii = runCatching { IDN.toASCII(hostPart, IDN.ALLOW_UNASSIGNED) }.getOrNull()?.lowercase() ?: return null
        if (!HOST.matches(ascii) || ascii.all { it.isDigit() || it == '.' }) return null
        if (LOCAL_SUFFIXES.any { ascii == it.removePrefix(".") || ascii.endsWith(it) }) return null
        return ascii
    }

    private const val HTTPS_PORT = "443"

    /** scheme, authority, everything after the authority (path + query + fragment). */
    private val URL = Regex("""(?i)^(https?)://([^/?#\s]+)(\S*)$""")

    /** Two or more DNS labels of letters, digits and hyphens (punycode already applied). */
    private val HOST = Regex("""^[a-z0-9-]{1,63}(\.[a-z0-9-]{1,63})+$""")

    /** Names that resolve locally, not on the Internet — and `.onion`, whose lookup would leak until Tor lands. */
    private val LOCAL_SUFFIXES = listOf(".local", ".localhost", ".internal", ".home.arpa", ".arpa", ".onion", ".lan", ".home")
}
