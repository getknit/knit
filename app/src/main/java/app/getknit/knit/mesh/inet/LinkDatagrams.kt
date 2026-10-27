package app.getknit.knit.mesh.inet

import java.io.Closeable

/**
 * The UDP a direct link rides (ADR 2026-10.a5yj), behind a seam so everything above it — the punch, the lane, the
 * stream — stays pure and runs against an in-process fake (`FakeLinkNet`) in unit tests and the lab. The Android side
 * (`net/AndroidLinkDatagrams`) binds every socket to the validated default network the Internet gate hands out, never
 * the process default and never the Wi-Fi Aware link, as `UdpTurnProbe` does.
 */
interface LinkDatagrams {
    /** One fresh UDP port on the validated default network, or null when there is none. */
    fun open(): LinkPort?

    /** The kind of that network now, as [app.getknit.knit.mesh.protocol.LinkSignal]'s `nt` names it. */
    fun networkKind(): Int
}

/**
 * One bound UDP socket. [candidates] are this phone's own addresses on its network at [localPort] — its IPv6 global
 * addresses and its LAN IPv4 one — and never the reflexive address, which only a STUN answer on this same socket can
 * tell ([StunMapped]). [receive] waits up to its timeout, and [close] ends the wait.
 */
interface LinkPort : Closeable {
    val localPort: Int

    val candidates: List<LinkCandidate>

    /** Sends [data] to [address] (4 or 16 bytes) at [port]; false when the network refused it. Never throws. */
    fun send(
        address: ByteArray,
        port: Int,
        data: ByteArray,
    ): Boolean

    /** The next datagram, or null when none came within [timeoutMs] or the port is closed. */
    suspend fun receive(timeoutMs: Int): Datagram?

    /** [host]'s public IPv4 addresses, resolved through this port's network; empty when it names none. */
    fun resolveV4(host: String): List<ByteArray>
}

/** One datagram in: its bytes and where it came from. */
class Datagram(
    val data: ByteArray,
    val address: ByteArray,
    val port: Int,
)

/** A STUN server's host and port, read off a URL `call/StunServers` already accepted. */
object StunUrl {
    private const val DEFAULT_PORT = 3478
    private val URL = Regex("""^stuns?:(\[[0-9A-Fa-f:.]{2,45}]|[A-Za-z0-9.-]{1,253})(?::(\d{1,5}))?$""")

    /** The host (brackets dropped from an IPv6 literal) and port of [url], or null when it is not a STUN URL. */
    fun parse(url: String): Pair<String, Int>? {
        val match = URL.matchEntire(url) ?: return null
        val host = match.groupValues[1].removePrefix("[").removeSuffix("]")
        val port = match.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull() ?: DEFAULT_PORT
        return (host to port).takeIf { port in 1..MAX_PORT }
    }

    private const val MAX_PORT = 65_535
}
