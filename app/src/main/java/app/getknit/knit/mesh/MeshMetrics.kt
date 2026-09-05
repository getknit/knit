package app.getknit.knit.mesh

import java.util.concurrent.ConcurrentHashMap
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

    /** A v2 (ratchet) DM with no session and no attached init — the peer assumes state we don't have. */
    RATCHET_NO_SESSION,

    /** A v2 DM referencing an own epoch or prekey private we no longer hold (retired/pruned/wiped). */
    RATCHET_EPOCH_GONE,

    /** A v2 DM whose chain index was already consumed and no skipped key is held — a benign re-delivery. */
    RATCHET_DUPLICATE,

    /** A v2 envelope with a structurally invalid ratchet header (missing `r`, bound violations, group-addressed). */
    RATCHET_BAD_HEADER,

    /**
     * A v2 DM whose key material resolved but whose AEAD refused — the peer holds a session with us and we
     * with them, and the two roots disagree (a "split brain"). Broken out of the generic [DECRYPT_FAILED],
     * which also covers the v1 path, because this is the one ratchet failure that means *both* sides have
     * state: [RATCHET_NO_SESSION] and [RATCHET_EPOCH_GONE] say we are missing something, and both already
     * drive the reset heuristic. Without its own reason this deadlocked silently — neither side could
     * decrypt, neither asked for a reset, and the pair re-served the same undecryptable custody forever.
     */
    RATCHET_AEAD_FAIL,

    /** A group frame CLAIMING US whose roster failed vetting (unverifiable founding set, smuggled
     *  member, non-founding sender, oversized roster) — see `InboundPipeline.vetRoster`. Frames for
     *  groups we simply aren't in are refused silently (that is every relayed foreign-group frame). */
    GROUP_ROSTER_REFUSED,

    /** A group ratchet frame with no adopted seed for its (sender, epoch) — the seed DM hasn't arrived/was lost. */
    GROUP_RATCHET_NO_KEY,

    /** A group ratchet frame whose chain index was already consumed — a benign custody re-delivery. */
    GROUP_RATCHET_DUPLICATE,

    /** A group-form envelope that is header-less, not group-addressed, or bound-violating — malformed by construction. */
    GROUP_RATCHET_BAD_HEADER,

    /** Group key material present but wrong (stale/foreign mint era) — the post-wipe signal, never tamper. */
    GROUP_RATCHET_AEAD_FAIL,

    /**
     * An unsigned frame that was not the one shape the unsigned door admits — a `relay = false` v3 DM-form
     * `CTL_RECEIPT` addressed to us (ADR 059) — or one that was, but did not open. Its own reason, kept
     * apart from [SIG_INVALID], because an old build overhearing a v3 tick counts the latter and this must
     * stay a clean "nothing legitimate looks like this" signal.
     */
    UNSIGNED_REFUSED,

    /**
     * An inbound reaction, cleartext or sealed, whose emoji is blank or longer than `TextLimits.REACTION`
     * — nothing is applied locally, while the frame is still custodied and relayed (cleartext) or its
     * ratchet chain still advances (sealed): a size gate is a delivery gate, never a relay gate.
     */
    REACTION_REFUSED,
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
 * Why an inbound coordination-plane fast-path message (compact `0x03` / fragment `0x04`,
 * `mesh/link/FastFrameCodec`) was discarded. Surfaced in [MeshMetrics.Snapshot.fastDropsByReason]:
 * TIMEOUT/OVERFLOW climbing means fragments are being lost mid-frame (range edge, tx-queue pressure);
 * UNKNOWN_TAG means a newer peer is emitting a tag this build doesn't know; DECODE_FAILED with any
 * volume means a framing/dictionary mismatch (should be ~0 — the flood copy self-heals either way).
 */
enum class FastPathDrop {
    /** A fragment set never completed inside the reassembly window. */
    FRAG_TIMEOUT,

    /** The bounded reassembly store evicted the oldest incomplete entry for a newer one. */
    FRAG_OVERFLOW,

    /** A non-printable first byte that is no known tag (a future peer, or garbage). */
    UNKNOWN_TAG,

    /** A tagged message that failed to parse/inflate back into a frame. */
    DECODE_FAILED,

    /**
     * A transcoded (`0x05`, ADR 060) message would not decode — most likely its body did not rebuild: the
     * sender's transcoder and this build's rebuild disagree about a frame. Should be ~0; the flood copy heals it.
     */
    TRANSCODE_FAILED,
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
@Suppress("TooManyFunctions") // a flat counter registry: one tiny increment per metric, so the count tracks the metrics
class MeshMetrics {
    private val framesOriginated = AtomicLong()
    private val framesDelivered = AtomicLong()
    private val framesRelayed = AtomicLong()
    private val framesSuppressed = AtomicLong()
    private val framesDeduped = AtomicLong()
    private val bytesSent = AtomicLong()
    private val keyRequestsSent = AtomicLong()
    private val introsSent = AtomicLong()
    private val introsAnswered = AtomicLong()
    private val keysServed = AtomicLong()
    private val keysRecovered = AtomicLong()
    private val framesHeld = AtomicLong()
    private val framesReplayed = AtomicLong()
    private val receiptsResent = AtomicLong()
    private val dmSealedV2 = AtomicLong()
    private val dmSealedV3 = AtomicLong()
    private val ticksUnsigned = AtomicLong()
    private val dmSealedV1Fallback = AtomicLong()
    private val receiptsSealed = AtomicLong()
    private val receiptsSealedFallback = AtomicLong()
    private val receiptsCustodied = AtomicLong()
    private val receiptsCoalesced = AtomicLong()
    private val reactionsSealed = AtomicLong()
    private val reactionsSealedFallback = AtomicLong()
    private val groupSealedRatchet = AtomicLong()
    private val groupSealedV1Fallback = AtomicLong()
    private val groupSeedsSent = AtomicLong()
    private val groupSeedsAdopted = AtomicLong()
    private val groupRootsMinted = AtomicLong()
    private val groupRootsAdopted = AtomicLong()

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
    private val fastCompactSent = AtomicLong()
    private val fastLegacySent = AtomicLong()
    private val fastFragSent = AtomicLong()
    private val fastReassembled = AtomicLong()
    private val fastTooBig = AtomicLong()
    private val fastTranscodedSent = AtomicLong()
    private val transcodeFallbacks = AtomicLong()
    private val fastDrops: Map<FastPathDrop, AtomicLong> = FastPathDrop.entries.associateWith { AtomicLong() }
    private val spoolPushed = AtomicLong()
    private val spoolPulled = AtomicLong()
    private val spoolBridged = AtomicLong()
    private val spoolInvalid = AtomicLong()
    private val spoolAccounted = AtomicLong()
    private val spoolErrors = AtomicLong()
    private val spoolAttachPushed = AtomicLong()
    private val spoolAttachPulled = AtomicLong()
    private val spoolAttachDeferred = AtomicLong()
    private val loraSent = AtomicLong()
    private val loraFragSent = AtomicLong()
    private val loraTranscoded = AtomicLong()
    private val loraPadded = AtomicLong()
    private val loraReceived = AtomicLong()
    private val loraReassembled = AtomicLong()
    private val loraTooBig = AtomicLong()
    private val loraDroppedQueue = AtomicLong()
    private val loraAirtimeHeld = AtomicLong()
    private val loraAirtimeHeldByBucket = ConcurrentHashMap<String, AtomicLong>()
    private val loraSuppressed = AtomicLong()
    private val loraNak = AtomicLong()
    private val loraNakByReason = ConcurrentHashMap<String, AtomicLong>()
    private val loraSessionUps = AtomicLong()
    private val loraDmSent = AtomicLong()
    private val loraDmReceived = AtomicLong()
    private val loraReoffered = AtomicLong()
    private val loraProfileRefanSkipped = AtomicLong()
    private val loraOfferSent = AtomicLong()
    private val loraOfferReceived = AtomicLong()
    private val loraBridged = AtomicLong()
    private val loraBridgeRefused = AtomicLong()
    private val loraPassive = AtomicLong()
    private val loraSkippedLinked = AtomicLong()
    private val loraTickDeferred = AtomicLong()

    // The Meshtastic room's inbound half: how much chat the board's primary channel actually carries, how
    // much of it arrives off an MQTT uplink, and what the filters turn away. Nothing here transmits, so none
    // of it touches the airtime ledger.
    private val meshPostHeard = AtomicLong()
    private val meshPostIngested = AtomicLong()
    private val meshPostViaMqtt = AtomicLong()
    private val meshPostMatched = AtomicLong()
    private val meshPostRefusedByReason = ConcurrentHashMap<String, AtomicLong>()

    // The Meshtastic room's outbound half. Unlike the four above these DO spend airtime, so `publicPostSent`
    // read against the PUBLIC bucket in `…debug.LORA` is what says whether the quota is set right; a
    // refusal is one the composer showed the user.
    private val publicPostSent = AtomicLong()
    private val publicPostRefusedByReason = ConcurrentHashMap<String, AtomicLong>()

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

    /** A contact-card intro we originated to a pending peer (see [IntroSync]) — a sealed `CTL_PROFILE` DM. */
    fun onIntroSent() {
        introsSent.incrementAndGet()
    }

    /** A sealed frame we sent to confirm a still-unconfirmed peer's session (the intro driver's answer). */
    fun onIntroAnswered() {
        introsAnswered.incrementAndGet()
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

    /** An outbound DM sealed under the v2 epoch ratchet (forward-secret). */
    fun onDmSealedV2() {
        dmSealedV2.incrementAndGet()
    }

    /** An outbound DM-form frame sealed under crypto scheme v3 (derived nonce, compact plaintext — ADR 059). */
    fun onDmSealedV3() {
        dmSealedV3.incrementAndGet()
    }

    /** A live-link delivery tick sent unsigned (v3, `relay = false`) — the one-packet form on every fast plane. */
    fun onTickUnsigned() {
        ticksUnsigned.incrementAndGet()
    }

    /** A v2-eligible DM that fell back to the v1 static wrap (peer downgraded / no epoch base) — should
     *  trend to zero as the installed base upgrades; a persistent count is the signal to investigate. */
    fun onDmSealedV1Fallback() {
        dmSealedV1Fallback.incrementAndGet()
    }

    /** A delivery receipt sealed as a v2 ctl DM (no vaccine-purge — the sealed-era custody contract). */
    fun onReceiptSealed() {
        receiptsSealed.incrementAndGet()
    }

    /** A seal-eligible receipt whose sealDm failed (no session + no usable prekey) — sent cleartext instead. */
    fun onReceiptSealedFallback() {
        receiptsSealedFallback.incrementAndGet()
    }

    /** A batched group tick originated into custody (one frame, however many acks it carries). */
    fun onReceiptCustodied() {
        receiptsCustodied.incrementAndGet()
    }

    /**
     * DM receipts that rode together instead of alone (ADR 054): `ids − 1` per coalesced tick, plus every ack
     * a reply carried inline. The field oracle for the LoRa airtime saving — each one is a ~3 s frame not sent.
     */
    fun onReceiptCoalesced(count: Int) {
        receiptsCoalesced.addAndGet(count.toLong())
    }

    /** A reaction sealed as a v2 ctl frame (DM or group form). */
    fun onReactionSealed() {
        reactionsSealed.incrementAndGet()
    }

    /** A DM/group-target reaction that went out as the legacy cleartext frame (incapable peer/group or a
     *  failed seal) — the "still walking naked in a private context" residual; broadcast never counts. */
    fun onReactionSealedFallback() {
        reactionsSealedFallback.incrementAndGet()
    }

    /** One outbound group message sealed under the sender-key chain (v2, group form). */
    fun onGroupSealedRatchet() {
        groupSealedRatchet.incrementAndGet()
    }

    /** A ratchet-eligible group message that fell back to the v1 per-member wrap (seal returned null). */
    fun onGroupSealedV1Fallback() {
        groupSealedV1Fallback.incrementAndGet()
    }

    /** One epoch-seed ctl DM originated toward a member (distributions + re-sends). */
    fun onGroupSeedSent() {
        groupSeedsSent.incrementAndGet()
    }

    /** One fresh recv chain adopted from a member's seed distribution. */
    fun onGroupSeedAdopted() {
        groupSeedsAdopted.incrementAndGet()
    }

    /** We minted a group's shared spool root — version 1, or a departure re-mint (SPOOL_PROTOCOL §3.2). */
    fun onGroupRootMinted() {
        groupRootsMinted.incrementAndGet()
    }

    /**
     * A strictly-newer gossiped root replaced ours. Read together with [onGroupRootMinted]: a steady
     * trickle of adoptions with no local mint is healthy gossip; repeated mint/adopt alternation on one
     * device is a lineage that is not collapsing.
     */
    fun onGroupRootAdopted() {
        groupRootsAdopted.incrementAndGet()
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

    /** A fast frame left in the compact (`0x03`, single-message) encoding toward one target. */
    fun onFastCompactSent() {
        fastCompactSent.incrementAndGet()
    }

    /** A fast frame left in the legacy (`0x01`) encoding toward one target (peer lacks the cap bit). */
    fun onFastLegacySent() {
        fastLegacySent.incrementAndGet()
    }

    /** A fast frame left fragmented (`0x04`) toward one target — dwarfed by [onFastCompactSent] is healthy. */
    fun onFastFragSent() {
        fastFragSent.incrementAndGet()
    }

    /** A fragmented fast frame reassembled completely on receive. */
    fun onFastReassembled() {
        fastReassembled.incrementAndGet()
    }

    /** A target was skipped because no encoding fit the message cap — the frame rides flood/custody only. */
    fun onFastTooBig() {
        fastTooBig.incrementAndGet()
    }

    /** A fast frame left in the transcoded (`0x05`, ADR 060) encoding toward one target, fragmented or not. */
    fun onFastTranscodedSent() {
        fastTranscodedSent.incrementAndGet()
    }

    /**
     * A frame asked for the transcoded form could not be reproduced by the transcoder and rode `0x03` instead
     * (any plane). Should be ~0: with volume, some build emits an encoding `FrameTranscoder` does not model.
     */
    fun onTranscodeFallback() {
        transcodeFallbacks.incrementAndGet()
    }

    /** An inbound fast-path message was discarded — see [FastPathDrop] for how to read each reason. */
    fun onFastDropped(reason: FastPathDrop) {
        fastDrops.getValue(reason).incrementAndGet()
    }

    /**
     * A file (avatar/attachment) was accepted onto a live link on [transport]'s plane — the per-radio split
     * that shows whether large blobs are riding the NAN fast path or falling back to BLE.
     */
    fun onFileSent(transport: TransportKind) {
        when (transport) {
            TransportKind.WifiAware -> filesSentNan.incrementAndGet()

            TransportKind.Bluetooth -> filesSentBt.incrementAndGet()

            TransportKind.LoRa -> Unit

            // the LoRa plane carries no files
            TransportKind.Other -> Unit
        }
    }

    /** An armed bulk-transfer NDP didn't come up within the composite's grace — the blob fell back to BLE.
     *  Climbing far faster than [Snapshot.filesSentNan] means we're arming ghosts (see BulkWantTracker). */
    fun onBulkGraceTimeout() {
        nanBulkGraceTimeouts.incrementAndGet()
    }

    /** A sealed custody frame was accepted by a spool (the Internet plane's outbound work). */
    fun onSpoolPushed() {
        spoolPushed.incrementAndGet()
    }

    /** A blob pulled or evented from a spool passed the §4.4 unseal/validate pipeline. */
    fun onSpoolPulled() {
        spoolPulled.incrementAndGet()
    }

    /** A validated spool blob re-entered mesh delivery — the island bridge actually firing. */
    fun onSpoolBridged() {
        spoolBridged.incrementAndGet()
    }

    /** A blob failed hash/AEAD/signature/frame-set and was quarantined (spec §9.3). Should stay at 0. */
    fun onSpoolInvalid() {
        spoolInvalid.incrementAndGet()
    }

    /**
     * A bridged blob that local custody did not keep entered the accounted set (spec §9.6) — it is folded
     * into our digest as held and never pulled again. Climbing slowly is normal: it is the 24–48 h band
     * between the mesh custody TTL and the scope TTL. Climbing in step with [onSpoolPulled] round after
     * round is the ADR 062 regression — the band being re-pulled instead of accounted.
     */
    fun onSpoolAccounted() {
        spoolAccounted.incrementAndGet()
    }

    /** A spool answered `err`, or refused a scope at SUB. */
    fun onSpoolError() {
        spoolErrors.incrementAndGet()
    }

    /** One sealed attachment chunk was accepted by a spool (spec §9.5's push half). */
    fun onSpoolAttachmentPushed() {
        spoolAttachPushed.incrementAndGet()
    }

    /** A whole attachment was reassembled from a spool and verified against the hash its frame named. */
    fun onSpoolAttachmentPulled() {
        spoolAttachPulled.incrementAndGet()
    }

    /**
     * An attachment we hold was held back from a spool this round because the radios are still carrying
     * it (`AttachmentDeferPolicy`). Counted because a deferral and a broken upload are otherwise
     * indistinguishable in the field — a silent gate reads exactly like a bug.
     */
    fun onSpoolAttachmentDeferred() {
        spoolAttachDeferred.incrementAndGet()
    }

    /** One frame sent over the LoRa plane (a whole frame, however many fragments it split into). */
    fun onLoraSent() {
        loraSent.incrementAndGet()
    }

    /** A LoRa frame that had to be split across fragments (dwarfed by [onLoraSent] is healthy). */
    fun onLoraFragSent() {
        loraFragSent.incrementAndGet()
    }

    /** A LoRa frame encoded in the transcoded (`0x05`, ADR 060) form — the one-packet form for a signed tick. */
    fun onLoraTranscoded() {
        loraTranscoded.incrementAndGet()
    }

    /**
     * A LoRa frame whose last packet was padded past the firmware's signature cliff (ADR 2026-09.mhs5), trading a
     * 66-byte signature the board would have added for a few bytes of pad. Counted because the saving is
     * otherwise invisible: a padded frame and an unpadded one look the same everywhere but the air.
     */
    fun onLoraPadded() {
        loraPadded.incrementAndGet()
    }

    /** A frame received over the LoRa plane (after reassembly + decode). */
    fun onLoraReceived() {
        loraReceived.incrementAndGet()
    }

    /** A fragmented LoRa frame reassembled completely on receive. */
    fun onLoraReassembled() {
        loraReassembled.incrementAndGet()
    }

    /** A frame no LoRa encoding could carry (> ~687 B compact); it rides the radios/custody instead. */
    fun onLoraTooBig() {
        loraTooBig.incrementAndGet()
    }

    /** A LoRa frame shed because the outbound pace queue was full (oldest whole frame of the lowest class, or the newcomer). */
    fun onLoraDroppedQueue() {
        loraDroppedQueue.incrementAndGet()
    }

    /** A LoRa fan-out suppressed: already sent/received over LoRa within the dedup window, or a stale custody re-serve. */
    fun onLoraSuppressed() {
        loraSuppressed.incrementAndGet()
    }

    /**
     * A routing NAK from the board, by its `Routing.error_reason` name (rate/duty-cycle/no-channel/too-large…);
     * a rising count means airtime pressure — or, per reason, something structural (a `TOO_LARGE` says the
     * payload cap is wrong, a `NO_CHANNEL` that the bound slot is gone). The total was all the field ever
     * saw until a lab session logged ten NAKs nobody could attribute (2026-08-29).
     */
    fun onLoraNak(reason: String = "UNKNOWN") {
        loraNak.incrementAndGet()
        loraNakByReason.getOrPut(reason) { AtomicLong() }.incrementAndGet()
    }

    /**
     * A queued LoRa frame the airtime budget made wait, by the [AirBucket] name it spends from. **Held, not
     * dropped** — it stays queued for a later window — which is exactly why it needed a counter of its own:
     * `loraDroppedQueue` only ever sees such a frame if the queue later fills and sheds it, so a plane whose
     * bucket is spent reads perfectly healthy right up until it overflows (field-observed 2026-09-04, a 99 %
     * BRIDGE bucket with `loraBridgeRefused` at zero throughout).
     *
     * Counted once per frame, not once per pacer wake, so this measures congestion rather than the clock.
     */
    fun onLoraAirtimeHeld(bucket: String) {
        loraAirtimeHeld.incrementAndGet()
        loraAirtimeHeldByBucket.getOrPut(bucket) { AtomicLong() }.incrementAndGet()
    }

    /** The LoRa board session reached Ready — context for the received/sent counts (how often it links). */
    fun onLoraSessionUp() {
        loraSessionUps.incrementAndGet()
    }

    /**
     * A **DM-form** frame sent over LoRa (ADR 039) — a sealed chat addressed to one peer. The transport cannot
     * read it, so this counts real DMs and their sealed receipts/reactions/ctl frames alike.
     */
    fun onLoraDmSent() {
        loraDmSent.incrementAndGet()
    }

    /** A DM-form frame received over LoRa — same opacity caveat as [onLoraDmSent]. */
    fun onLoraDmReceived() {
        loraDmReceived.incrementAndGet()
    }

    /** A carried DM-form frame re-offered over LoRa to a peer just heard for the first time (ADR 039). */
    fun onLoraReoffered() {
        loraReoffered.incrementAndGet()
    }

    /**
     * A relayed `profile` the fan-out declined to put on the air again: the same publish already rode this
     * plane inside the re-fan window (ADR 057). Against [loraSent] this is the measure of the redundancy
     * that made profiles 79 % of one lab gateway's LoRa traffic.
     */
    fun onLoraProfileRefanSkipped() {
        loraProfileRefanSkipped.incrementAndGet()
    }

    /**
     * A gossip OFFER that **reached the air** — one packet naming the custody window this gateway holds
     * (ADR 044). Counted at transmit and not at enqueue, because an offer that never flies is the one failure
     * this counter has to be able to show: it read 7 against two transmissions in the field while the far
     * pocket heard none at all. It is also what makes `loraSent − loraDmSent − loraOfferSent` the profile +
     * room count the debug bridge documents.
     */
    fun onLoraOfferSent() {
        loraOfferSent.incrementAndGet()
    }

    /** A gossip OFFER heard from another gateway, co-pocket or far. */
    fun onLoraOfferReceived() {
        loraOfferReceived.incrementAndGet()
    }

    /** Frames served across the bridge because a far gateway's OFFER showed it lacked them. */
    fun onLoraBridged(count: Int) {
        loraBridged.addAndGet(count.toLong())
    }

    /** A backfill request refused outright — the publisher had already spent its hourly serve allowance. */
    fun onLoraBridgeRefused() {
        loraBridgeRefused.incrementAndGet()
    }

    /**
     * A transmission suppressed because another board in this pocket is the gateway. Healthy and expected on
     * a spare board; climbing on a phone that should be bridging means the election picked someone else.
     */
    fun onLoraPassive() {
        loraPassive.incrementAndGet()
    }

    /**
     * A DM-form frame kept off LoRa because its recipient is us or a peer a higher-preference plane holds a
     * live link to (ADR 054) — the link carries it. Climbing while texting a pocket-mate is the gate working;
     * climbing while a far peer starves means the link set is wrong.
     */
    fun onLoraSkippedLinked() {
        loraSkippedLinked.incrementAndGet()
    }

    /** A DM arrived over the board and its ✓✓ was held for the coalescer instead of sealed at once (ADR 054). */
    fun onLoraTickDeferred() {
        loraTickDeferred.incrementAndGet()
    }

    /** A chat packet on the board's public primary — every post in earshot, before any filter runs. */
    fun onMeshPostHeard() {
        meshPostHeard.incrementAndGet()
    }

    /** A post accepted into the Meshtastic room; [viaMqtt] counts the ones off somebody's uplink. */
    fun onMeshPostIngested(viaMqtt: Boolean) {
        meshPostIngested.incrementAndGet()
        if (viaMqtt) meshPostViaMqtt.incrementAndGet()
    }

    /** A heard post whose speaker's board a contact's profile claims — attributed to that contact at ingest. */
    fun onMeshPostMatched() {
        meshPostMatched.incrementAndGet()
    }

    /** A primary-channel packet the filters turned away, by `PublicChannelPolicy.Refusal`. */
    fun onMeshPostRefused(reason: String) {
        meshPostRefusedByReason.computeIfAbsent(reason) { AtomicLong() }.incrementAndGet()
    }

    /** One post this phone put on its board's primary channel. The only counter here that cost airtime. */
    fun onPublicPostSent() {
        publicPostSent.incrementAndGet()
    }

    /** A post this device did not put on the air, by `PublicPostRefusal`. */
    fun onPublicPostRefused(reason: String) {
        publicPostRefusedByReason.computeIfAbsent(reason) { AtomicLong() }.incrementAndGet()
    }

    @Suppress("LongMethod") // a flat field-by-field copy — one line per counter; splitting it would only scatter it
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
            introsSent = introsSent.get(),
            introsAnswered = introsAnswered.get(),
            keysServed = keysServed.get(),
            keysRecovered = keysRecovered.get(),
            framesHeld = framesHeld.get(),
            framesReplayed = framesReplayed.get(),
            receiptsResent = receiptsResent.get(),
            dmSealedV2 = dmSealedV2.get(),
            dmSealedV3 = dmSealedV3.get(),
            ticksUnsigned = ticksUnsigned.get(),
            dmSealedV1Fallback = dmSealedV1Fallback.get(),
            receiptsSealed = receiptsSealed.get(),
            receiptsSealedFallback = receiptsSealedFallback.get(),
            receiptsCustodied = receiptsCustodied.get(),
            receiptsCoalesced = receiptsCoalesced.get(),
            reactionsSealed = reactionsSealed.get(),
            reactionsSealedFallback = reactionsSealedFallback.get(),
            groupSealedRatchet = groupSealedRatchet.get(),
            groupSealedV1Fallback = groupSealedV1Fallback.get(),
            groupSeedsSent = groupSeedsSent.get(),
            groupSeedsAdopted = groupSeedsAdopted.get(),
            groupRootsMinted = groupRootsMinted.get(),
            groupRootsAdopted = groupRootsAdopted.get(),
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
            fastCompactSent = fastCompactSent.get(),
            fastLegacySent = fastLegacySent.get(),
            fastFragSent = fastFragSent.get(),
            fastReassembled = fastReassembled.get(),
            fastTooBig = fastTooBig.get(),
            fastTranscodedSent = fastTranscodedSent.get(),
            transcodeFallbacks = transcodeFallbacks.get(),
            fastDropsByReason = fastDrops.mapValues { it.value.get() }.filterValues { it > 0 },
            spoolPushed = spoolPushed.get(),
            spoolPulled = spoolPulled.get(),
            spoolBridged = spoolBridged.get(),
            spoolInvalid = spoolInvalid.get(),
            spoolAccounted = spoolAccounted.get(),
            spoolErrors = spoolErrors.get(),
            spoolAttachPushed = spoolAttachPushed.get(),
            spoolAttachPulled = spoolAttachPulled.get(),
            spoolAttachDeferred = spoolAttachDeferred.get(),
            loraSent = loraSent.get(),
            loraFragSent = loraFragSent.get(),
            loraTranscoded = loraTranscoded.get(),
            loraPadded = loraPadded.get(),
            loraReceived = loraReceived.get(),
            loraReassembled = loraReassembled.get(),
            loraTooBig = loraTooBig.get(),
            loraDroppedQueue = loraDroppedQueue.get(),
            loraAirtimeHeld = loraAirtimeHeld.get(),
            loraAirtimeHeldByBucket = loraAirtimeHeldByBucket.mapValues { it.value.get() },
            loraSuppressed = loraSuppressed.get(),
            loraNak = loraNak.get(),
            loraNakByReason = loraNakByReason.mapValues { it.value.get() },
            loraSessionUps = loraSessionUps.get(),
            loraDmSent = loraDmSent.get(),
            loraDmReceived = loraDmReceived.get(),
            loraReoffered = loraReoffered.get(),
            loraProfileRefanSkipped = loraProfileRefanSkipped.get(),
            loraOfferSent = loraOfferSent.get(),
            loraOfferReceived = loraOfferReceived.get(),
            loraBridged = loraBridged.get(),
            loraBridgeRefused = loraBridgeRefused.get(),
            loraPassive = loraPassive.get(),
            loraSkippedLinked = loraSkippedLinked.get(),
            loraTickDeferred = loraTickDeferred.get(),
            meshPostHeard = meshPostHeard.get(),
            meshPostIngested = meshPostIngested.get(),
            meshPostViaMqtt = meshPostViaMqtt.get(),
            meshPostMatched = meshPostMatched.get(),
            meshPostRefusedByReason = meshPostRefusedByReason.mapValues { it.value.get() },
            publicPostSent = publicPostSent.get(),
            publicPostRefusedByReason = publicPostRefusedByReason.mapValues { it.value.get() },
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
        val introsSent: Long = 0,
        val introsAnswered: Long = 0,
        val keysServed: Long = 0,
        val keysRecovered: Long = 0,
        val framesHeld: Long = 0,
        val framesReplayed: Long = 0,
        val receiptsResent: Long = 0,
        val dmSealedV2: Long = 0,
        val dmSealedV3: Long = 0,
        val ticksUnsigned: Long = 0,
        val dmSealedV1Fallback: Long = 0,
        val receiptsSealed: Long = 0,
        val receiptsSealedFallback: Long = 0,
        val receiptsCustodied: Long = 0,
        val receiptsCoalesced: Long = 0,
        val reactionsSealed: Long = 0,
        val reactionsSealedFallback: Long = 0,
        val groupSealedRatchet: Long = 0,
        val groupSealedV1Fallback: Long = 0,
        val groupSeedsSent: Long = 0,
        val groupSeedsAdopted: Long = 0,
        val groupRootsMinted: Long = 0,
        val groupRootsAdopted: Long = 0,
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
        val fastCompactSent: Long = 0,
        val fastLegacySent: Long = 0,
        val fastFragSent: Long = 0,
        val fastReassembled: Long = 0,
        val fastTooBig: Long = 0,
        val fastTranscodedSent: Long = 0,
        val transcodeFallbacks: Long = 0,
        val fastDropsByReason: Map<FastPathDrop, Long> = emptyMap(),
        val spoolPushed: Long = 0,
        val spoolPulled: Long = 0,
        val spoolBridged: Long = 0,
        val spoolInvalid: Long = 0,
        val spoolAccounted: Long = 0,
        val spoolErrors: Long = 0,
        val spoolAttachPushed: Long = 0,
        val spoolAttachPulled: Long = 0,
        val spoolAttachDeferred: Long = 0,
        val loraSent: Long = 0,
        val loraFragSent: Long = 0,
        val loraTranscoded: Long = 0,
        val loraPadded: Long = 0,
        val loraReceived: Long = 0,
        val loraReassembled: Long = 0,
        val loraTooBig: Long = 0,
        val loraDroppedQueue: Long = 0,
        val loraAirtimeHeld: Long = 0,
        val loraAirtimeHeldByBucket: Map<String, Long> = emptyMap(),
        val loraSuppressed: Long = 0,
        val loraNak: Long = 0,
        val loraNakByReason: Map<String, Long> = emptyMap(),
        val loraSessionUps: Long = 0,
        val loraDmSent: Long = 0,
        val loraDmReceived: Long = 0,
        val loraReoffered: Long = 0,
        val loraProfileRefanSkipped: Long = 0,
        val loraOfferSent: Long = 0,
        val loraOfferReceived: Long = 0,
        val loraBridged: Long = 0,
        val loraBridgeRefused: Long = 0,
        val loraPassive: Long = 0,
        val loraSkippedLinked: Long = 0,
        val loraTickDeferred: Long = 0,
        val meshPostHeard: Long = 0,
        val meshPostIngested: Long = 0,
        val meshPostViaMqtt: Long = 0,
        val meshPostMatched: Long = 0,
        val meshPostRefusedByReason: Map<String, Long> = emptyMap(),
        val publicPostSent: Long = 0,
        val publicPostRefusedByReason: Map<String, Long> = emptyMap(),
    )
}
