package app.getknit.knit.mesh.inet

/**
 * One address a direct link may be reached at (ADR 2026-10.a5yj): [address] is 4 bytes (IPv4) or 16 (IPv6), [port]
 * 1..65535, and [kind] says where it came from — which is also what a field-log line names instead of the address.
 */
class LinkCandidate(
    val address: ByteArray,
    val port: Int,
    val kind: Kind,
) {
    /** Where a candidate came from, in the order a punch tries them. The codes are the wire's and never move. */
    @Suppress("MagicNumber") // the literals ARE the frozen wire codes; naming each one would only restate it
    enum class Kind(
        val code: Int,
    ) {
        /** An IPv6 temporary (privacy) global address: preferred, since it rotates and names no interface. */
        V6_TEMPORARY(0),

        /** Any other IPv6 global unicast address. */
        V6(1),

        /** An IPv4 address on the phone's own network (RFC 1918 or CGNAT space): two phones on one LAN meet here. */
        HOST(2),

        /** The IPv4 address and port a STUN server saw the link's own socket send from. */
        V4_REFLEXIVE(3),
        ;

        companion object {
            fun fromCode(code: Int): Kind? = entries.firstOrNull { it.code == code }
        }
    }

    val v6: Boolean get() = address.size == V6_BYTES

    override fun equals(other: Any?): Boolean = other is LinkCandidate && other.port == port && other.address.contentEquals(address)

    override fun hashCode(): Int = address.contentHashCode() * PRIME + port

    internal companion object {
        const val V4_BYTES = 4
        const val V6_BYTES = 16
        private const val PRIME = 31
    }
}

/**
 * How a direct link's punch runs (ADR 2026-10.a5yj): which of a peer's candidates are sent to and in what order, how
 * often a probe goes out, when the attempt gives up, and how long a contact waits before the next one. Both ends send to
 * every target at once — IPv6 first, since sending at the same time opens both stateful firewalls — and the first
 * target that authenticates both ways carries the link. No TURN: an attempt that has not answered in [GIVE_UP_MS] gives
 * up and the frames keep riding the spool. Pure, so `PunchPolicyTest` pins it.
 *
 * The peer names the addresses this phone sends to, so the list is checked before a datagram leaves: only the kind's
 * own address space (a `HOST` is private, a `V4_REFLEXIVE` public, a `V6` global unicast), never a loopback, multicast or
 * link-local address, and at most [MAX_TARGETS] of them. A contact cannot aim this phone's probes at an arbitrary
 * host or make it spray: the probes are small, few and over in ten seconds.
 */
object PunchPolicy {
    /** How long an attempt probes before it gives up. */
    const val GIVE_UP_MS = 10_000L

    /** Most targets one attempt sends to, whatever the peer listed. */
    const val MAX_TARGETS = 8

    /**
     * How long after a link comes up on a path that leaves this phone's own network a path that does not — a
     * [preferred] target, probed on through this window — may still take it (L-4-3): the LAN answer and a router's
     * hairpin go out in one round and come back within milliseconds of each other, so whichever came first is chance.
     */
    const val UPGRADE_WINDOW_MS = 1_000L

    /**
     * How long a link on a [preferred] path keeps it while the peer's datagrams come from elsewhere: about the round trip
     * its peer needs to follow, and past it — nothing heard on the preferred path — the link follows the peer again.
     */
    const val STICKY_MS = 3_000L

    /** The first wait before a contact is tried again after an attempt failed; it doubles per failure. */
    const val FIRST_BACKOFF_MS = 60_000L

    /**
     * The longest a contact waits between attempts: half an hour, so a photo no spool takes is offered for about fifty
     * times over its day (`LinkDemandPolicy.FILE_WINDOW_MS`) at most, and a contact back online waits no longer.
     */
    const val MAX_BACKOFF_MS = 30 * 60_000L

    private const val FAST_PROBE_MS = 200L
    private const val SLOW_PROBE_MS = 500L
    private const val FAST_PHASE_MS = 2_000L
    private const val MAX_PORT = 65_535

    /**
     * The peer's [remote] candidates this phone sends to, in order — IPv6 only where this phone holds an IPv6 address
     * ([localV6]), IPv4 only where it holds an IPv4 one ([localV4]) — after dropping every candidate [sane] refuses and
     * every duplicate, and capped at [MAX_TARGETS].
     */
    fun targets(
        remote: List<LinkCandidate>,
        localV6: Boolean,
        localV4: Boolean,
    ): List<LinkCandidate> =
        remote
            .asSequence()
            .filter { sane(it) && if (it.v6) localV6 else localV4 }
            .distinct()
            .sortedBy { it.kind.ordinal }
            .take(MAX_TARGETS)
            .toList()

    /** Whether [candidate] is an address of its kind's own space, on a real port. */
    @Suppress("ReturnCount") // a ladder of refusals
    fun sane(candidate: LinkCandidate): Boolean {
        if (candidate.port !in 1..MAX_PORT) return false
        val a = candidate.address
        return when (candidate.kind) {
            LinkCandidate.Kind.V6_TEMPORARY, LinkCandidate.Kind.V6 -> a.size == LinkCandidate.V6_BYTES && globalV6(a)
            LinkCandidate.Kind.HOST -> a.size == LinkCandidate.V4_BYTES && privateV4(a)
            LinkCandidate.Kind.V4_REFLEXIVE -> a.size == LinkCandidate.V4_BYTES && publicV4(a)
        }
    }

    /**
     * Whether [target] is a path that stays on this phone's own network (L-4-3): a LAN address ([LinkCandidate.Kind.HOST]),
     * or an IPv6 address inside one of the prefixes of the network this phone's port is on ([onLink]) — never a hairpin
     * through a NAT's public address, which may be a carrier's, nor an IPv6 address on another network.
     */
    fun preferred(
        target: LinkCandidate,
        onLink: (ByteArray) -> Boolean,
    ): Boolean = target.kind == LinkCandidate.Kind.HOST || (target.v6 && onLink(target.address))

    /**
     * Whether an up link moves its path to a datagram's source ([fromPreferred]: one of the peer's [preferred] targets),
     * given whether its path is [onPreferred] now and how long since a datagram came from a preferred one: always onto a
     * preferred path, always while off one, and off one only once nothing came from it for [STICKY_MS].
     */
    fun adopts(
        onPreferred: Boolean,
        fromPreferred: Boolean,
        sincePreferredMs: Long,
    ): Boolean = fromPreferred || !onPreferred || sincePreferredMs >= STICKY_MS

    /** Whether [address] lies in [own]'s first [prefixLength] bits, both of one family: on the network [own] is on. */
    fun inPrefix(
        own: ByteArray,
        prefixLength: Int,
        address: ByteArray,
    ): Boolean {
        if (own.size != address.size || prefixLength !in 1..own.size * Byte.SIZE_BITS) return false
        val whole = prefixLength / Byte.SIZE_BITS
        for (i in 0 until whole) if (own[i] != address[i]) return false
        val rest = prefixLength % Byte.SIZE_BITS
        if (rest == 0) return true
        val mask = (BYTE shl (Byte.SIZE_BITS - rest)) and BYTE
        return (own[whole].toInt() and mask) == (address[whole].toInt() and mask)
    }

    /** The wait before the next probe round, [elapsedMs] into an attempt: quick while both ends start, slower after. */
    fun probeIntervalMs(elapsedMs: Long): Long = if (elapsedMs < FAST_PHASE_MS) FAST_PROBE_MS else SLOW_PROBE_MS

    /** Whether an attempt [elapsedMs] old with no authenticated answer gives up. */
    fun givesUp(elapsedMs: Long): Boolean = elapsedMs >= GIVE_UP_MS

    /** How long a contact waits after its [failures]-th failed attempt in a row (1 = the first): 1 min doubling to 30 min. */
    fun backoffMs(failures: Int): Long {
        if (failures <= 0) return 0L
        val doublings = (failures - 1).coerceAtMost(MAX_DOUBLINGS)
        return (FIRST_BACKOFF_MS shl doublings).coerceAtMost(MAX_BACKOFF_MS)
    }

    private const val MAX_DOUBLINGS = 16

    // 2000::/3 — global unicast. Every other IPv6 space (link-local, ULA, multicast, loopback, mapped) is refused.
    private fun globalV6(a: ByteArray): Boolean = (a[0].toInt() and GLOBAL_MASK) == GLOBAL_PREFIX

    private fun privateV4(a: ByteArray): Boolean {
        val b0 = a[0].toInt() and BYTE
        val b1 = a[1].toInt() and BYTE
        return b0 == TEN || (b0 == ONE_SEVENTY_TWO && b1 in SIXTEEN..THIRTY_ONE) || (b0 == ONE_NINETY_TWO && b1 == ONE_SIXTY_EIGHT) ||
            (b0 == HUNDRED && b1 in SIXTY_FOUR..ONE_TWENTY_SEVEN)
    }

    private fun publicV4(a: ByteArray): Boolean {
        val b0 = a[0].toInt() and BYTE
        val b1 = a[1].toInt() and BYTE
        val special = b0 == 0 || b0 == LOOPBACK || b0 >= MULTICAST || (b0 == ONE_SIXTY_NINE && b1 == TWO_FIFTY_FOUR)
        return !special && !privateV4(a)
    }

    private const val BYTE = 0xFF
    private const val GLOBAL_MASK = 0xE0
    private const val GLOBAL_PREFIX = 0x20
    private const val TEN = 10
    private const val LOOPBACK = 127
    private const val HUNDRED = 100
    private const val SIXTY_FOUR = 64
    private const val ONE_TWENTY_SEVEN = 127
    private const val ONE_SEVENTY_TWO = 172
    private const val SIXTEEN = 16
    private const val THIRTY_ONE = 31
    private const val ONE_NINETY_TWO = 192
    private const val ONE_SIXTY_EIGHT = 168
    private const val ONE_SIXTY_NINE = 169
    private const val TWO_FIFTY_FOUR = 254
    private const val MULTICAST = 224
}
