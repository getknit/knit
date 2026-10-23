package app.getknit.knit.mesh.spool

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/**
 * One entry of Knit's public relay directory (`docs/SPOOL_PROTOCOL.md` §5.1, knit-spool-coordinator ADR 2026-10.txjf,
 * ADR 2026-10.8kfx): a `public-N` relay, whether it takes new homes ([state]), and its share of them ([weight]).
 */
data class PublicSpool(
    val host: String,
    val state: String,
    val weight: Int,
) {
    /** Whether a phone may pick this relay as its home. Only `open`; an unknown state is neither picked nor fled. */
    val open: Boolean get() = state == OPEN

    /** The one URL a directory relay is dialled at: the directory carries the host alone. */
    val url: String get() = PublicSpools.urlOf(host)

    companion object {
        const val OPEN = "open"
        const val DRAINING = "draining"
    }
}

/**
 * The rules about Knit's public relays, pure so both platforms' vectors pin them (ADR 2026-10.8kfx): which hosts count
 * as one, how the directory document is read, and which relay a phone picks as its **home**.
 */
object PublicSpools {
    /** A directory relay's host, and nothing else: the directory never names a relay outside it. */
    private val HOST = Regex("^public-[1-9][0-9]*\\.spool\\.getknit\\.app$")

    const val SCHEMA = 1
    const val MIN_WEIGHT = 1
    const val MAX_WEIGHT = 64
    private const val HOME_LABEL = "knit/home/v1"
    private const val PATH = "/spool/v1"

    /** The house relay every legacy peer is on, and every install that predates homes keeps (txjf's legacy rule). */
    const val LEGACY_HOST = "public-1.spool.getknit.app"
    val LEGACY_URL: String = urlOf(LEGACY_HOST)

    /** The directory this build ships, for a phone that has never read one: [LEGACY_HOST], open, weight 1. */
    val BAKED: List<PublicSpool> = listOf(PublicSpool(LEGACY_HOST, PublicSpool.OPEN, MIN_WEIGHT))

    private val json = Json { ignoreUnknownKeys = true }

    /** Whether [host] is a directory relay's name. Exact and case-sensitive: a host is stored lowercase. */
    fun isDirectoryHost(host: String): Boolean = HOST.matches(host)

    /** Whether [url] is a directory relay's URL, the one form it is dialled at (no port, no token, the spool path). */
    fun isDirectoryUrl(url: String): Boolean {
        val host = SpoolUrl.hostname(url) ?: return false
        return isDirectoryHost(host) && url == urlOf(host)
    }

    fun urlOf(host: String): String = "wss://$host$PATH"

    @Serializable
    private class Document(
        val v: Int,
        val spools: List<Entry> = emptyList(),
    )

    @Serializable
    private class Entry(
        val host: String,
        val state: String,
        val weight: Int,
    )

    /**
     * The entries of a directory document [text], or null when it is not one this build reads: unparseable, or a
     * schema other than [SCHEMA] (the caller keeps the copy it holds). An entry whose host is not a directory host or
     * whose weight is out of range is dropped; a host listed twice keeps its first entry.
     */
    fun parse(text: String): List<PublicSpool>? {
        val doc = runCatching { json.decodeFromString<Document>(text) }.getOrNull() ?: return null
        if (doc.v != SCHEMA) return null
        return doc.spools
            .filter { isDirectoryHost(it.host) && it.weight in MIN_WEIGHT..MAX_WEIGHT }
            .distinctBy { it.host }
            .map { PublicSpool(it.host, it.state, it.weight) }
    }

    /** [entries] as a directory document, for the cache: what [parse] reads back. */
    fun format(entries: List<PublicSpool>): String =
        json.encodeToString(Document.serializer(), Document(SCHEMA, entries.map { Entry(it.host, it.state, it.weight) }))

    /**
     * The home [nodeId] picks from [entries] (txjf), or null when none is open. Weighted rendezvous over slots, in
     * integers only: for each open host and each `i` in `0 until weight`, `SHA-256("knit/home/v1" ‖ nodeId ‖ "|" ‖ host
     * ‖ "|" ‖ decimal i)`; the host owning the largest digest (unsigned, all 32 bytes) wins, and on equal digests the
     * smaller host (unreachable in practice, stated so the rule is closed). Each slot is equally likely to be the
     * largest, so a host is picked with probability weight / total. [nodeId] is the canonical id string a contact card
     * carries.
     */
    fun pickHome(
        nodeId: String,
        entries: List<PublicSpool>,
    ): String? {
        var best: ByteArray? = null
        var bestHost: String? = null
        // An entry out of range is skipped as if it were not open, never clamped (txjf).
        for (entry in entries.filter { it.open && isDirectoryHost(it.host) && it.weight in MIN_WEIGHT..MAX_WEIGHT }) {
            repeat(entry.weight) { i ->
                val digest = slotDigest(nodeId, entry.host, i)
                val order = best?.let { compareUnsigned(digest, it) } ?: 1
                if (order > 0 || (order == 0 && entry.host < bestHost!!)) {
                    best = digest
                    bestHost = entry.host
                }
            }
        }
        return bestHost
    }

    /**
     * Whether a phone homed at [home] must pick again (txjf): only when its home is absent from [entries] or exactly
     * `draining`. An unknown state keeps it.
     */
    fun mustRehome(
        home: String,
        entries: List<PublicSpool>,
    ): Boolean {
        val entry = entries.firstOrNull { it.host == home } ?: return true
        return entry.state == PublicSpool.DRAINING
    }

    /** One slot's digest; public for the vectors. */
    fun slotDigest(
        nodeId: String,
        host: String,
        slot: Int,
    ): ByteArray =
        MessageDigest
            .getInstance("SHA-256")
            .digest("$HOME_LABEL$nodeId|$host|$slot".toByteArray(Charsets.UTF_8))

    private fun compareUnsigned(
        a: ByteArray,
        b: ByteArray,
    ): Int {
        for (i in a.indices) {
            val d = (a[i].toInt() and BYTE_MASK) - (b[i].toInt() and BYTE_MASK)
            if (d != 0) return d
        }
        return 0
    }

    private const val BYTE_MASK = 0xff
}
