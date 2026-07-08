package app.getknit.knit.mesh.protocol

/**
 * The mesh protocol version and capability registry, plus the codec for the endpoint-info advert string.
 *
 * A peer advertises `nodeId|version|capabilitiesHex` in its discovery advert ([advertise]) — the Wi-Fi
 * Aware `serviceSpecificInfo` or the BLE service-data payload; a peer
 * that only knows the bare nodeId (a legacy/unknown build) parses to version 0 / no capabilities
 * ([parse], which never throws). This is the *unauthenticated* connection-time hint — known before a
 * single frame flows — used only as a routing/degradation signal, never for trust (trust stays on the
 * signature path). The same [VERSION]/[capabilities][LOCAL_CAPABILITIES] also ride, authenticated, on
 * [ProfileContent] so a peer reachable only via relay still learns them.
 *
 * Pure (no Android), so it is JVM-unit-testable alongside the other `mesh/protocol` logic.
 *
 * Append-only: [CAP_*] bit positions are never reused and [VERSION] only increases.
 */
object Protocol {
    /** This build's protocol/handshake version, advertised in endpoint-info and [ProfileContent]. */
    const val VERSION = 1 // launch baseline; bump on a breaking wire change — see docs/WIRE_COMPAT.md

    /** Lowest peer version we still interoperate with (reserved for future route-around; unused today). */
    const val MIN_SUPPORTED = 1

    /**
     * How far a peer's signed `sentAt` may lead our local clock before we treat it as bogus future-dating
     * rather than honest clock skew. `sentAt` is self-attested and unverifiable, yet it is *also* the
     * frame-global custody eviction key and the local sort key, so an unbounded future value is a weapon:
     * a custody frame future-dated past this window is refused at store time
     * ([app.getknit.knit.data.forward.ForwardRepository.store]) so it can't become un-sweepable and win
     * every oldest-by-`sentAt` eviction (a handful of Sybil identities would otherwise displace all honest
     * custody mesh-wide), and an inbound chat's stored `sentAt` is clamped to it
     * ([app.getknit.knit.mesh.InboundPipeline.deliverChat]) so a future-dated frame can't pin itself to the
     * top of a conversation forever. 5 min tolerates an unsynced device without giving an attacker a usable
     * window. Every node compares against its own `now`, exactly like the dead-on-arrival lower bound, so an
     * honest frame (`sentAt ≈ now`) passes on every node and only the attacker's window closes.
     */
    const val MAX_FUTURE_SKEW_MS = 5 * 60_000L

    /** Capability bits (append-only — never recycle a position). */
    const val CAP_E2E = 0x1L
    const val CAP_GROUPS = 0x2L
    const val CAP_REACTIONS = 0x4L
    const val CAP_STORE_FORWARD = 0x8L

    /** This build's advertised capability bitfield. */
    val LOCAL_CAPABILITIES: Long = CAP_E2E or CAP_GROUPS or CAP_REACTIONS or CAP_STORE_FORWARD

    private const val SEP = '|'

    /** The endpoint-info string a peer advertises: its [nodeId] plus this build's version + capabilities. */
    fun advertise(nodeId: String): String = "$nodeId$SEP$VERSION$SEP${LOCAL_CAPABILITIES.toString(RADIX)}"

    /** A neighbor's advertised identity parsed from its endpoint name. */
    data class PeerWire(
        val nodeId: String,
        val protoVersion: Int,
        val capabilities: Long,
    )

    /**
     * Parses an endpoint name. The first segment is always the nodeId (robust to any future suffix); a
     * missing version/capabilities (a bare legacy nodeId) yields version 0 / no capabilities. Never
     * throws — an unparseable segment degrades to "unknown".
     */
    fun parse(endpointName: String): PeerWire {
        val parts = endpointName.split(SEP)
        return PeerWire(
            nodeId = parts[0],
            protoVersion = parts.getOrNull(1)?.toIntOrNull() ?: 0,
            capabilities = parts.getOrNull(2)?.toLongOrNull(RADIX) ?: 0L,
        )
    }

    private const val RADIX = 16
}
