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

    /**
     * The ratchet schemes — crypto scheme v2's DM form (docs/FORWARD_SECRECY_RATCHET.md) AND its group
     * sender-key form (docs/GROUP_FORWARD_SECRECY.md); one bit because both ship in the same release
     * (the group form folded into the unreleased v2 bump — a build with one always has both). Unlike
     * the bits above this one is a send-time input: outbound v2 requires the pinned profile to carry
     * it AND a valid `ProfileContent.prekey` (they arrive on one signed frame — the stale-capability
     * mitigation); a group send requires that of EVERY other member (the epoch seeds ride the
     * pairwise DM sessions).
     */
    const val CAP_RATCHET = 0x10L

    /**
     * The compact coordination-plane framing (`mesh/link/FastFrameCodec`): this build accepts the
     * `0x03` compact / `0x04` fragment fast-path message tags. Gates only the *encoding* a sender
     * picks toward this peer (compact vs the legacy `0x01` CBOR framing) — a transport-local routing
     * hint like every advert bit, consumed from the SSI advert copy, never a trust input. Receivers
     * accept all tags unconditionally, so a stale bit degrades to a lost best-effort message at worst.
     */
    const val CAP_FAST_COMPACT = 0x20L

    /**
     * Inline delivery acks (ADR 054): this build applies `MessageContent.acks` on a **plain** sealed DM chat,
     * not only on a `CTL_RECEIPT`, so a reply can carry the receipts its author owes the recipient in place
     * of a standalone tick (over LoRa a tick costs as much air as the message). A send-time input like
     * [CAP_RATCHET]: a sender attaches acks only toward a pinned profile carrying this bit, and an older
     * receiver — which reads the field only on a ctl frame — is never sent one, so its ✓✓ still arrives as
     * a tick. Additive per docs/WIRE_COMPAT.md: an existing field populated in a new case, gated on a bit.
     */
    const val CAP_INLINE_ACK = 0x40L

    /**
     * The transcoded fast-path framing (`mesh/link/FrameTranscoder`, ADR 060): this build decodes the `0x05`
     * tag — a schema-aware re-encoding of a frame's signed bytes that the receiver rebuilds byte-exact before
     * verifying the signature. Gates only the *encoding* a sender picks toward this peer, exactly like
     * [CAP_FAST_COMPACT]: consumed from the SSI advert copy, never a trust input, and receivers accept every
     * tag unconditionally. This is the last capability bit a BLE advert carries (`BleAdvertPayload.CAP_MASK`),
     * spent here because the adverts are where a transport-local encoding choice is read; bits from 0x100 up
     * ride the pinned profile and the Wi-Fi Aware advert only.
     */
    const val CAP_FRAME_TRANSCODE = 0x80L

    /**
     * Crypto scheme v3 (ADR 059): this build opens a `EncEnvelope.v = 3` DM — derived nonce, labeled
     * compact plaintext — and accepts the **unsigned** `relay = false` sealed delivery tick that form makes
     * possible. A send-time input exactly like [CAP_RATCHET]: a sender seals v3 only toward a pinned profile
     * carrying this bit **and** [CAP_RATCHET] (one signed frame carries both plus the prekey — no stale
     * window between them), never on the advert copy, and a peer without it keeps receiving v2 and signed
     * ticks. Additive per docs/WIRE_COMPAT.md: a new scheme number behind a new bit.
     */
    const val CAP_CRYPTO_V3 = 0x100L

    /**
     * Arbitrary-file attachments: this build understands
     * [app.getknit.knit.mesh.crypto.MessageContent.attachmentName]/`attachmentSize` and renders a
     * non-image, non-audio attachment as a named file rather than handing it to the image loader.
     *
     * A send-time input like [CAP_RATCHET] — the composer offers "File" only toward a pinned profile
     * carrying this bit (every member's, for a group). Without it the two new fields are dropped by
     * `ignoreUnknownKeys` and the recipient gets an attachment it cannot name or open, which is worse
     * than not offering the send. Deliberately *not* a privacy control: those must never be gated on a
     * peer's own unauthenticated claim (`.agents/memory/roadmap.md`, the `FileHeaderWire.mime` entry).
     */
    const val CAP_FILES = 0x200L

    /**
     * Link-preview cards: this build renders an attachment typed [LinkPreviewBlob.MIME] as the card its sender
     * fetched (title, description, picture) rather than handing the container to the image loader.
     *
     * A send-time input like [CAP_FILES] — a card is attached to a DM or group message only when every
     * recipient's pinned profile carries this bit, since a build without it shows the message with a
     * spinner where the card should be, which is worse than the plain link. The Nearby room is the deliberate
     * exception: its listeners cannot be enumerated, so a room message carries its card regardless, and a
     * pre-card build degrades there (ADR: link previews). Not a privacy control, for [CAP_FILES]'s reason.
     */
    const val CAP_LINK_PREVIEW = 0x400L

    /** This build's advertised capability bitfield. */
    const val LOCAL_CAPABILITIES: Long =
        CAP_E2E or CAP_GROUPS or CAP_REACTIONS or CAP_STORE_FORWARD or CAP_RATCHET or CAP_FAST_COMPACT or
            CAP_INLINE_ACK or CAP_FRAME_TRANSCODE or CAP_CRYPTO_V3 or CAP_FILES or CAP_LINK_PREVIEW

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
