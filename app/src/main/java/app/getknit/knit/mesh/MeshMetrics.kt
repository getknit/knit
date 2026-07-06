package app.getknit.knit.mesh

import java.util.concurrent.atomic.AtomicLong

/**
 * Why an inbound frame we wanted was dropped — distinct from policy drops (blocked sender / moderation),
 * which are NOT counted here so a staged rollout's drop dashboard stays a pure "couldn't use it" signal.
 * Surfaced in [MeshMetrics.Snapshot.dropsByReason] for the Diagnostics screen + the periodic metrics log.
 */
enum class DropReason {
    /** Bytes that wouldn't decode into a frame (malformed, or an unknown wrapper/envelope shape). */
    DECODE_FAILED,

    /** A payload from an endpoint we have no node-id mapping for. */
    UNKNOWN_ENDPOINT,

    /** The frame signature didn't verify against the sender's key. */
    SIG_INVALID,

    /** No pinned key for the sender yet (their profile hasn't arrived), so we can't authenticate it. */
    NO_SENDER_KEY,

    /** The verifying key doesn't derive to the claimed senderId (self-cert failure / stale pin). */
    KEY_NODEID_MISMATCH,

    /** Signature verification threw unexpectedly. */
    VERIFY_ERROR,

    /** An encrypted message we couldn't decrypt (no wrapped key for us, or AEAD failure). */
    DECRYPT_FAILED,

    /** A relayed message we declined to carry for store-and-forward (unauthenticated). */
    CARRY_REFUSED,

    /** An encrypted envelope whose crypto-scheme version this build doesn't support. */
    UNKNOWN_ENVELOPE_VERSION,

    /** A decrypted payload whose content-schema version this build doesn't support. */
    UNKNOWN_CONTENT_VERSION,

    /** An inbound profile whose key differs from the sender's already-pinned key — a pinned-key change
     *  is only reachable via a nodeId hash collision (impersonation), so we refuse it and keep the pin. */
    PIN_CHANGE_REFUSED,
}

/**
 * Why a Bluetooth L2CAP connect attempt to a peer failed — surfaced per-reason in
 * [MeshMetrics.Snapshot.btConnectFailsByReason] and the failure log, so an intermittent "can link one peer
 * but not the second" (the suspected BLE ↔ A2DP-audio radio contention) is *attributable* rather than a
 * silent retry. Best-effort buckets classified from the (otherwise-discarded) connect exception; the raw
 * throwable is always logged alongside.
 */
enum class ConnectFailReason {
    /** The blocking connect() (or HELLO exchange) didn't complete before the watchdog closed the socket. */
    TIMEOUT,

    /** The Bluetooth stack reported a radio/resource error — the radio-saturation signature. */
    RADIO,

    /** The peer refused or reset the channel (advertised a PSM but isn't accepting on it). */
    REFUSED,

    /** The socket connected but the identity (HELLO) exchange failed. */
    HANDSHAKE,

    /** Any other/unclassified failure — see the logged throwable. */
    OTHER,
}

/**
 * Thread-safe counters for mesh transmission, so the effect of flood suppression and the CBOR wire
 * format is measurable in the field. Pure JVM (no Android dependencies) so it can live in [mesh] and
 * be asserted from the same unit tests as [MeshRouter].
 *
 * The ratio of [Snapshot.framesSuppressed] to [Snapshot.framesRelayed] shows how much redundant
 * rebroadcasting the overhear suppression eliminates; [Snapshot.bytesSent] tracks the CBOR win;
 * [Snapshot.dropsByReason] makes the otherwise-silent inbound drops visible during a rollout.
 */
class MeshMetrics {
    private val framesOriginated = AtomicLong()
    private val framesDelivered = AtomicLong()
    private val framesRelayed = AtomicLong()
    private val framesSuppressed = AtomicLong()
    private val framesDeduped = AtomicLong()
    private val bytesSent = AtomicLong()
    private val keyRequestsSent = AtomicLong()
    private val keysServed = AtomicLong()
    private val keysRecovered = AtomicLong()
    private val framesHeld = AtomicLong()
    private val framesReplayed = AtomicLong()
    private val receiptsResent = AtomicLong()

    // Fixed key set → no allocation on the hot path, every reason always present.
    private val drops: Map<DropReason, AtomicLong> = DropReason.entries.associateWith { AtomicLong() }
    private val connectFails: Map<ConnectFailReason, AtomicLong> =
        ConnectFailReason.entries.associateWith { AtomicLong() }
    private val btLinksEstablished = AtomicLong()
    private val nanServesPeak = AtomicLong()
    private val nanAcceptsRefused = AtomicLong()
    private val nanIcmKeepaliveFailed = AtomicLong()
    private val nanMsgsAcked = AtomicLong()
    private val nanMsgSendsFailed = AtomicLong()
    private val filesSentNan = AtomicLong()
    private val filesSentBt = AtomicLong()
    private val nanBulkGraceTimeouts = AtomicLong()

    /** A frame this device authored and injected into the mesh. */
    fun onOriginated() {
        framesOriginated.incrementAndGet()
    }

    /** A newly-seen frame delivered to the app layer. */
    fun onDelivered() {
        framesDelivered.incrementAndGet()
    }

    /** A pending relay that fired (we forwarded the frame onward). */
    fun onRelayed() {
        framesRelayed.incrementAndGet()
    }

    /** A pending relay we cancelled because a neighbor was overheard relaying the same frame. */
    fun onSuppressed() {
        framesSuppressed.incrementAndGet()
    }

    /** A duplicate of an already-seen frame, dropped without re-delivery. */
    fun onDeduped() {
        framesDeduped.incrementAndGet()
    }

    /** [bytes] put on the wire (counted once per target endpoint a payload is sent to). */
    fun onBytesSent(bytes: Long) {
        bytesSent.addAndGet(bytes)
    }

    /** An inbound frame we wanted was dropped for [reason] (not a policy drop — see [DropReason]). */
    fun onDropped(reason: DropReason) {
        drops.getValue(reason).incrementAndGet()
    }

    /** A key-request frame we sent to recover a peer's missing profile/key (see [KeyExchange]). */
    fun onKeyRequested() {
        keyRequestsSent.incrementAndGet()
    }

    /** A cached peer profile we re-served in answer to another node's key request. */
    fun onKeyServed() {
        keysServed.incrementAndGet()
    }

    /** A previously-missing peer key we recovered (a frame that was dropping NO_SENDER_KEY can now verify). */
    fun onKeyRecovered() {
        keysRecovered.incrementAndGet()
    }

    /** A frame dropped for a missing sender key that we parked to replay once the key arrives (see [PendingInbound]). */
    fun onFrameHeld() {
        framesHeld.incrementAndGet()
    }

    /** A parked frame we replayed through the deliver path after its sender's key was pinned. */
    fun onFrameReplayed() {
        framesReplayed.incrementAndGet()
    }

    /** A broadcast/group delivery receipt we re-sent to its author because the first best-effort tick may not
     *  have landed (delay-tolerant recovery, see [AckSync]) — a rising count means ticks are being recovered. */
    fun onReceiptResent() {
        receiptsResent.incrementAndGet()
    }

    /** A Bluetooth L2CAP connect attempt to a peer failed for [reason] (see [ConnectFailReason]). */
    fun onBtConnectFailed(reason: ConnectFailReason) {
        connectFails.getValue(reason).incrementAndGet()
    }

    /** A Bluetooth L2CAP link came up — context for the connect-failure counts (success vs failure rate). */
    fun onBtLinkEstablished() {
        btLinksEstablished.incrementAndGet()
    }

    /** Record the current count of concurrent inbound NAN serves; keeps the session peak (P1 observability). */
    fun onNanServes(concurrent: Long) {
        nanServesPeak.accumulateAndGet(concurrent) { a, b -> maxOf(a, b) }
    }

    /** An inbound NAN accept was refused by the serve policy (cap reached / initiator handshake in flight). */
    fun onNanAcceptRefused() {
        nanAcceptsRefused.incrementAndGet()
    }

    /** A live publish session's updatePublish failed — the ICM relight fell back to the subscribe re-arm. */
    fun onNanIcmKeepaliveFailed() {
        nanIcmKeepaliveFailed.incrementAndGet()
    }

    /** A coordination-plane message (cue/fast-frame) was MAC-acked by its peer. */
    fun onNanMsgAcked() {
        nanMsgsAcked.incrementAndGet()
    }

    /** A coordination-plane message got no ACK (peer dozing/out of range, or the tx queue overflowed). */
    fun onNanMsgSendFailed() {
        nanMsgSendsFailed.incrementAndGet()
    }

    /**
     * A file (avatar/attachment) was accepted onto a live link on [transport]'s plane — the per-radio split
     * that shows whether large blobs are riding the NAN fast path or falling back to BLE.
     */
    fun onFileSent(transport: TransportKind) {
        when (transport) {
            TransportKind.WifiAware -> filesSentNan.incrementAndGet()
            TransportKind.Bluetooth -> filesSentBt.incrementAndGet()
            TransportKind.Other -> Unit
        }
    }

    /** An armed bulk-transfer NDP didn't come up within the composite's grace — the blob fell back to BLE.
     *  Climbing far faster than [Snapshot.filesSentNan] means we're arming ghosts (see BulkWantTracker). */
    fun onBulkGraceTimeout() {
        nanBulkGraceTimeouts.incrementAndGet()
    }

    fun snapshot(): Snapshot {
        val byReason = drops.mapValues { it.value.get() }
        val connectByReason = connectFails.mapValues { it.value.get() }
        return Snapshot(
            framesOriginated = framesOriginated.get(),
            framesDelivered = framesDelivered.get(),
            framesRelayed = framesRelayed.get(),
            framesSuppressed = framesSuppressed.get(),
            framesDeduped = framesDeduped.get(),
            bytesSent = bytesSent.get(),
            framesDropped = byReason.values.sum(),
            dropsByReason = byReason.filterValues { it > 0 },
            keyRequestsSent = keyRequestsSent.get(),
            keysServed = keysServed.get(),
            keysRecovered = keysRecovered.get(),
            framesHeld = framesHeld.get(),
            framesReplayed = framesReplayed.get(),
            receiptsResent = receiptsResent.get(),
            btConnectFails = connectByReason.values.sum(),
            btConnectFailsByReason = connectByReason.filterValues { it > 0 },
            btLinksEstablished = btLinksEstablished.get(),
            nanServesPeak = nanServesPeak.get(),
            nanAcceptsRefused = nanAcceptsRefused.get(),
            nanIcmKeepaliveFailed = nanIcmKeepaliveFailed.get(),
            nanMsgsAcked = nanMsgsAcked.get(),
            nanMsgSendsFailed = nanMsgSendsFailed.get(),
            filesSentNan = filesSentNan.get(),
            filesSentBt = filesSentBt.get(),
            nanBulkGraceTimeouts = nanBulkGraceTimeouts.get(),
        )
    }

    data class Snapshot(
        val framesOriginated: Long,
        val framesDelivered: Long,
        val framesRelayed: Long,
        val framesSuppressed: Long,
        val framesDeduped: Long,
        val bytesSent: Long,
        val framesDropped: Long = 0,
        val dropsByReason: Map<DropReason, Long> = emptyMap(),
        val keyRequestsSent: Long = 0,
        val keysServed: Long = 0,
        val keysRecovered: Long = 0,
        val framesHeld: Long = 0,
        val framesReplayed: Long = 0,
        val receiptsResent: Long = 0,
        val btConnectFails: Long = 0,
        val btConnectFailsByReason: Map<ConnectFailReason, Long> = emptyMap(),
        val btLinksEstablished: Long = 0,
        val nanServesPeak: Long = 0,
        val nanAcceptsRefused: Long = 0,
        val nanIcmKeepaliveFailed: Long = 0,
        val nanMsgsAcked: Long = 0,
        val nanMsgSendsFailed: Long = 0,
        val filesSentNan: Long = 0,
        val filesSentBt: Long = 0,
        val nanBulkGraceTimeouts: Long = 0,
    )
}
