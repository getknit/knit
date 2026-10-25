package app.getknit.knit.call

import app.getknit.knit.mesh.spool.TurnHosts

/**
 * The spool seam of a voice call: what the connected spools offer a call's ICE gathering (docs/VOICE_CALLS.md §2).
 *
 * Two questions, both abstract on purpose. This was a `fun interface` with [turnServers] alone; STUN is a second
 * question with a different shape — no peer, no wait, nothing issued, just what the hellos already said — and a
 * default for it would let an implementation that forgot it hand every call no STUN, silently, which is the very
 * failure C-7.6-20 exists to close (ADR 2026-10.xkp4). As a plain interface, every implementation answers both.
 */
interface TurnSource {
    /**
     * TURN credentials from the spools that would issue one for a call with [peerId]: those carrying its DM scope that
     * advertise `turn` (C-7.6-12), and any where this phone holds a ticket (C-7.6-21). Empty when none can — the call
     * then dials on host and server-reflexive candidates alone. Never asks a spool that did not advertise it, and
     * never waits long: a call is waiting on the answer.
     */
    suspend fun turnServers(peerId: String): List<TurnServer>

    /**
     * [turnServers], with each spool that names its TURN servers (HELLO `turnHosts`) asked for the one [pick] ranks
     * best of that spool's list, in its order (C-7.6-51): what [CallManager] asks. A source with no spools behind it
     * has no list to pick from, and answers as [turnServers] does.
     */
    suspend fun turnServers(
        peerId: String,
        pick: (List<String>) -> String?,
    ): List<TurnServer> = turnServers(peerId)

    /**
     * One credential for the TURN server whose entry is [entry] (C-7.6-54's next server), from a spool that would issue
     * a call with [peerId] TURN and names that server, or null when none does or none answers in time. A source with no
     * spools behind it has none, so a call there never moves to another server.
     */
    suspend fun turnServerFor(
        peerId: String,
        entry: String,
    ): TurnServer? = null

    /**
     * The credential-free STUN URIs every connected spool advertised in its HELLO (C-7.6-20), unfiltered: the call
     * holds them to `StunServers`' rule. Read on every call, with or without TURN — a phone with no STUN offers only
     * private addresses, which a paying peer's relay cannot permit.
     */
    fun stunUrls(): List<String>

    /**
     * Whether some source would issue this phone TURN now: what "Relay all calls" needs (C-3.7-3), since a relay-only
     * call with no TURN server gathers nothing. Read as a call is placed and as an offer lands, so it asks nothing and
     * never waits. A source that cannot tell says yes, and the call learns otherwise from [turnServers].
     */
    fun turnAvailable(): Boolean = true

    /** A build that takes no live calls (`LIVE_CALLS_WIRED` off): nothing ever asks it, and it has nothing to give. */
    object None : TurnSource {
        override suspend fun turnServers(peerId: String): List<TurnServer> = emptyList()

        override fun stunUrls(): List<String> = emptyList()
    }
}

/**
 * One ICE server for a call: a short-lived TURN credential (coturn's REST scheme), whose [urls] are `turn:`/`turns:`
 * URIs, or — with an empty [user] and [pass] — credential-free `stun:` URIs. A plain class, and its [toString] names
 * a count and a slot only, so neither [pass] nor a server's host can reach a log line. Never sent to the peer or into
 * any frame (C-7.6-11).
 *
 * A credential from a spool that names its servers carries that spool's list, [spoolHosts] (HELLO `turnHosts`, in
 * server order), and where the spool came in the call's asking order, [asked] (ticketed first, C-7.6-12): what
 * [TurnRanking] chooses the call's one server by (C-7.6-53). Which server it is for is [entry], read from its own UDP
 * URL (C-7.6-51), and [slot] is that server's place in its spool's list, the only name a log gives it.
 */
class TurnServer(
    val urls: List<String>,
    val user: String,
    val pass: String,
    val spoolHosts: List<String> = emptyList(),
    val asked: Int = 0,
) {
    /** A relay a call can allocate on, not the credential-free STUN entry. */
    val isTurn: Boolean get() = user.isNotEmpty()

    /** The entry of this credential's server (`host:port` of its UDP URL), or null when it has no UDP URL. */
    val entry: String? = TurnHosts.entryOf(urls)

    /** From a spool that names its TURN servers: a credential C-7.6-53 hands over alone. */
    val ranked: Boolean get() = isTurn && spoolHosts.isNotEmpty()

    /** This server's 1-based place in its spool's [spoolHosts], or 0 when it is not in the list. */
    val slot: Int get() = entry?.let { spoolHosts.indexOf(it) + 1 } ?: 0

    /** "slot 2 of 3" for a ranked credential's log line, else its URL count. */
    fun describe(): String = if (ranked) "slot $slot of ${spoolHosts.size}" else "${urls.size} url(s)"

    override fun toString(): String = "TurnServer(${describe()})"
}

/** "1 TURN, 1 STUN" for a log line: the credential-free STUN entry is not a relay, so it is counted apart. */
fun List<TurnServer>.iceSummary(): String {
    val stun = count { it.user.isEmpty() }
    return "${size - stun} TURN, $stun STUN"
}
