package app.getknit.knit.mesh

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Why an inbound frame we wanted was dropped — distinct from policy drops (blocked sender / moderation),
 * which are NOT counted here so a staged rollout's drop dashboard stays a pure "couldn't use it" signal.
 * Surfaced in [MeshMetrics.Snapshot.Frames.dropsByReason] for the Diagnostics screen + the periodic metrics log.
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

    /**
     * A v2 DM carrying a peer's `FLAG_RESET` init that our one-minute replacement floor refused, for a root we never held
     * (`RatchetEngine.OpenOutcome.Failed.RESET_FLOORED`, ADR 2026-11.sfms, #251). Before it had its own reason it read as
     * [RATCHET_DUPLICATE] — the reset restarts its sender's numbering, so its `se = 1 n = 0` met the previous reset's
     * consumed row — and nothing ever re-ran it. Never reset-triggering: the peer is not diverged, it asked to re-root
     * inside our floor; a signed one is held (`FlooredResets`) and re-opened when the floor ends.
     */
    RATCHET_RESET_FLOORED,

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

    /**
     * A broadcast-room post over the link's ingress budget ([IngressBudget]) — refused in [MeshRouter] before
     * verify, custody, delivery and relay, and never marked seen, so a custody re-serve can bring it through
     * once the bucket refills. The one deliberate refusal counted here, because a rising count *is* the
     * flood signal Diagnostics should show: one link handing over room posts faster than any room is written.
     */
    INGRESS_REFUSED,

    /**
     * A frame whose id is not the shape its type is minted with (`FrameId.admits`, ADR 2026-10.9qr4) — refused in
     * [MeshRouter] before verify, custody, delivery and relay, and never marked seen. No build mints one, so a count
     * here is a crafted frame or a port that mints a new shape, which every build before it drops.
     */
    NONCANONICAL_ID,

    /**
     * A verified frame whose `payload` wouldn't decode into its type's content: a field of the wrong CBOR type, a
     * missing required one, text that isn't UTF-8. Counted where the pipeline reads the payload, so a regressed
     * encoder on another port shows here instead of vanishing (the interop audit's AND-12).
     */
    PAYLOAD_DECODE_FAILED,

    /**
     * A sealed scope config (`CTL_SCOPE_CONFIG`, docs/SPOOL_PROTOCOL.md §5) that opened and was refused: malformed (no
     * dialable URL, too many, a non-positive bound), sealed into a conversation other than the one it names, from a
     * group sender outside the roster or sealed before their leave, or a version too far past the held winner. Nothing
     * is applied and the chain still advances. Never reset-triggering: the frame decrypted, so it says nothing about the
     * session.
     */
    SCOPE_CONFIG_REFUSED,

    /**
     * A sealed presence key (`CTL_PRESENCE_KEY`, ADR 2026-11.xp6d) that opened and was refused: malformed (a key that is
     * not 32 bytes, a generation that is not positive, half a next pair, a next generation not above the first), from a
     * sender with no peer row or from ourselves, or out of order within the root era it opened under — an equal
     * generation with another key, or one lower than every key held (a new era, opened under the active root, is the one
     * place a lower generation is taken). Nothing is stored and the chain still advances. Never reset-triggering: the
     * frame decrypted, so it says nothing about the session. An equal generation with the same key is no refusal and is
     * not counted (a re-send after a link came back).
     */
    PRESENCE_KEY_REFUSED,

    /**
     * A blob frame (`FrameType.isUnsignedPointToPoint`) marked for relay — refused by `InboundPipeline.verifyInbound`
     * and never flooded by [MeshRouter]. Every build sends the blob traffic point-to-point, so a count here is a crafted
     * frame.
     */
    POINT_TO_POINT_FLOODED,

    /**
     * A blob control frame (`blobreq`, `blobhave`, `blobhas`, `blobprog`, `blobcancel`) that did not arrive on a link
     * (an L2CAP channel, a Wi-Fi Aware data path or a direct link) or whose `senderId` is not that link's peer. They are
     * unsigned, so only the link they came in on says who sent one; a side-channel page, a hop-less fast frame or a LoRa
     * packet names its sender in bytes anyone can write. Every build sends them on links under its own id (ADR
     * 2026-11.264w), so a count here is a crafted frame.
     */
    BLOB_CONTROL_NOT_FROM_LINK,

    /**
     * A compact LoRa DM that opened under its pair key and was refused at delivery (`InboundPipeline.deliverLoraDirect`,
     * ADR 2026-10.x9fa): a plaintext that is not v3 compact content this build reads, or one that is anything but a
     * message, a `CTL_RECEIPT` or a `CTL_REACTION` — a reset, a seed, a profile, a scope config, a transfer, a call
     * or a presence key never ride that door. Never reset-triggering: the compact door is no `WireEnvelope` and never touches a ratchet.
     */
    LORA_DIRECT_REFUSED,

    /**
     * The signed copy of a DM a compact LoRa copy already delivered, whose ratchet-only open (`InboundPipeline`'s
     * `lora_direct_seen` branch, ADR 2026-10.x9fa) failed. Nothing is written and the copy is acked as the exists-gate
     * acks it. Never reset-triggering: the message itself already landed, so the failure says nothing a reset could
     * repair that the peer's next frame will not show again on the ordinary path.
     */
    LORA_DIRECT_RATCHET_MISSED,

    /**
     * A direct link's OFFER that opened but whose stamp is more than `Protocol.MAX_FUTURE_SKEW_MS` from this phone's
     * clock (ADR 2026-10.a5yj, `docs/DIRECT_LINKS.md` L-2-4). Its offerer sees only a missing answer, so a count climbing
     * here is how a pair stuck dark by a drifted clock shows apart from a pair with no UDP path.
     */
    LINK_STALE,
}

/**
 * Why a Bluetooth L2CAP connect attempt to a peer failed — surfaced per-reason in
 * [MeshMetrics.Snapshot.Bluetooth.btConnectFailsByReason] and the failure log, so an intermittent "can link one peer
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
 * `mesh/link/FastFrameCodec`) was discarded. Surfaced in [MeshMetrics.Snapshot.FastPath.fastDropsByReason]:
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
 * What the Bluetooth plane dropped or refused for a blinded advert (ADR 2026-11.xp6d, knit-next #285). Counts only,
 * never a tag or an id. Surfaced in [MeshMetrics.Snapshot.Bluetooth.bleBlindDropsByReason].
 */
enum class BleBlindDrop {
    /** A `FLAG_BLINDED` advert whose bytes 1–16 lack the tag prefix: malformed, dropped on the scan thread. */
    MALFORMED_ADVERT,

    /** A `FLAG_BLINDED` GATT payload: no port serves one, and its tag would be forgotten and re-read at every dial. */
    GATT_BLINDED,

    /** A `TAG_NEXT` from an unproven link, or one whose HELLO lacked `CAP_BLIND_ADVERT`. */
    TAG_NEXT_UNPROVEN,

    /** A blinded sighting a newer one of the same tag replaced while both waited for the resolver's worker. */
    QUEUE_CONFLATED,

    /** A blinded sighting of a new tag past the resolver queue's cap. */
    QUEUE_FULL,

    /** A blinded sighting queued before the adapter went off (an older radio generation), dropped at the worker. */
    STALE_RADIO,

    /** An unresolved form's handle the handle table's cap let go (a flood of tags), its entries with it. */
    HANDLE_EVICTED,

    /** One of my own tags, heard back. */
    OWN_ADVERT,

    /** A tag a contact's key names, heard from an address whose tag dial proved another id: a replay. */
    REPLAYED_ADVERT,

    /** A contact's key listing that gained a key inside its churn window ([app.getknit.knit.mesh.bluetooth.KeyChurnLimiter]): held. */
    KEY_LISTING_HELD,

    /** A held key listing a newer one replaced before its window freed. */
    KEY_LISTING_COALESCED,

    /** The held-link row refused a dialer whose proven link is held and whose `a` names a blinded form of mine. */
    HELD_LINK_REFUSED,
}

/**
 * Why the BLE side channel (`mesh/bluetooth/BleSideChannel`, the extended-advertising page carrier) discarded
 * a frame. Surfaced in [MeshMetrics.Snapshot.Bluetooth.bleSideDropsByReason]: STALE/OVERFLOW climbing means the pages
 * cannot keep up with what is offered (two slots at a ~12 s dwell is the whole capacity); FRAG_TIMEOUT means
 * a part of a multi-part frame was never heard (range edge, a scanner window that missed it); DECODE_FAILED
 * with volume means a framing mismatch, or two senders' fragments with one id were assembled together.
 */
enum class BleSideDrop {
    /** A queued frame outlived its freshness before a slot freed up. */
    STALE,

    /** The bounded queue shed its oldest unstarted frame for a newer one. */
    OVERFLOW,

    /** A page whose first byte is no tag this build knows. */
    UNKNOWN_TAG,

    /** A page (or a reassembled set) failed to decode into a frame. */
    DECODE_FAILED,

    /** A fragment set never completed inside the reassembly window. */
    FRAG_TIMEOUT,

    /** The bounded reassembly store evicted the oldest incomplete entry for a newer one. */
    FRAG_OVERFLOW,

    /** A page carrying our own frame came back (a relay of it) — never delivered to ourselves. */
    OWN_ECHO,
}

/**
 * A [MeshManager] session loop whose round threw ([everyRound], [runRound]). Surfaced in
 * [MeshMetrics.Snapshot.Frames.roundsFailedBy] and the field log's `roundFail_<ROUND>` counters, so a tester's log
 * shows a loop failing without logcat (#206). The loop itself runs on: the next round is on time.
 */
enum class MeshRound {
    /** The 60 s re-offer to linked neighbours: custody digests, key requests, owed ticks, group seeds, blob asks. */
    REOFFER,

    /** The 10 min sweep of expired carried frames, the commons outbox and the local storage bounds. */
    SWEEP,

    /** The 60 s metrics tick, which also banks the contribution ledger and sweeps the indirect-reach table. */
    METRICS,

    /**
     * A pass of the neighbour watcher on a link-set change: the departures' blob step and the newcomers' hooks. Not
     * timed; what a failed pass owed runs on the next change or the next re-offer round.
     */
    NEIGHBORS,
}

/**
 * Thread-safe counters for mesh transmission, so the effect of flood suppression and the CBOR wire
 * format is measurable in the field. Pure JVM (no Android dependencies) so it can live in [mesh] and
 * be asserted from the same unit tests as [MeshRouter].
 *
 * The ratio of [Snapshot.Frames.framesSuppressed] to [Snapshot.Frames.framesRelayed] shows how much redundant
 * rebroadcasting the overhear suppression eliminates; [Snapshot.Frames.bytesSent] tracks the CBOR win;
 * [Snapshot.Frames.dropsByReason] makes the otherwise-silent inbound drops visible during a rollout.
 *
 * Both size suppressions below say the same thing: this is a flat registry whose length tracks the *number
 * of metrics*, not any complexity — one field and one tiny increment each. Splitting it would scatter the
 * counters and break the single-[Snapshot] read every consumer (Diagnostics, the debug bridge, the metrics
 * log line) depends on.
 */
@Suppress("TooManyFunctions", "LargeClass")
class MeshMetrics {
    private val framesOriginated = AtomicLong()
    private val framesDelivered = AtomicLong()
    private val framesRelayed = AtomicLong()
    private val framesHandedOn = AtomicLong()
    private val framesSuppressed = AtomicLong()
    private val framesDeduped = AtomicLong()
    private val bytesSent = AtomicLong()
    private val keyRequestsSent = AtomicLong()
    private val introsSent = AtomicLong()
    private val introsAnswered = AtomicLong()
    private val ratchetSwappedBack = AtomicLong()
    private val presenceKeysSent = AtomicLong()
    private val presenceKeysStored = AtomicLong()
    private val presenceKeysReplaced = AtomicLong()
    private val presenceKeysExcluded = AtomicLong()
    private val presenceKeyRotations = AtomicLong()
    private val presenceKeyGenerationClamped = AtomicLong()
    private val linkOwedResetsSent = AtomicLong()
    private val linkOwedResetsDeclined = AtomicLong()
    private val linkOwedResetsSuperseded = AtomicLong()
    private val presenceLinkPassesScheduled = AtomicLong()
    private val presenceLinkPassesCompleted = AtomicLong()
    private val keysServed = AtomicLong()
    private val keysRecovered = AtomicLong()
    private val framesHeld = AtomicLong()
    private val framesReplayed = AtomicLong()
    private val healsCompleted = AtomicLong()
    private val blobAsksHandled = AtomicLong()
    private val carrierPullsDeferred = AtomicLong()
    private val requestHolds = AtomicLong()
    private val acceptanceRearms = AtomicLong()
    private val carrierPullsOverBudget = AtomicLong()
    private val filesResumedOut = AtomicLong()
    private val filesResumedIn = AtomicLong()
    private val splicesRefused = AtomicLong()

    // The holder query (ADR 2026-10.6e5t): one counter per [BlobExchange.BlobEvent].
    private val blobEvents = BlobExchange.BlobEvent.entries.associateWith { AtomicLong() }
    private val receiptsResent = AtomicLong()
    private val dmSealedV2 = AtomicLong()
    private val dmSealedV3 = AtomicLong()
    private val ticksUnsigned = AtomicLong()
    private val dmSealedV1Fallback = AtomicLong()
    private val receiptsSealed = AtomicLong()
    private val receiptsSealedFallback = AtomicLong()
    private val receiptsReAckSkipped = AtomicLong()
    private val receiptsCustodied = AtomicLong()
    private val receiptsCoalesced = AtomicLong()
    private val receiptsRidden = AtomicLong()
    private val ridesUnrouted = AtomicLong()
    private val receiptsHeld = AtomicLong()
    private val receiptsSpooled = AtomicLong()
    private val reactionsSealed = AtomicLong()
    private val reactionsSealedFallback = AtomicLong()
    private val groupSealedRatchet = AtomicLong()
    private val groupSealedV1Fallback = AtomicLong()
    private val groupSeedsSent = AtomicLong()
    private val groupSeedsAdopted = AtomicLong()
    private val groupSeedsHeld = AtomicLong()
    private val groupSeedsReplayed = AtomicLong()
    private val groupKeyRequestsSent = AtomicLong()
    private val groupRootsMinted = AtomicLong()
    private val groupRootsAdopted = AtomicLong()
    private val groupCustodyRefused = AtomicLong()

    // Fixed key set → no allocation on the hot path, every reason always present.
    private val drops: Map<DropReason, AtomicLong> = DropReason.entries.associateWith { AtomicLong() }
    private val roundsFailed: Map<MeshRound, AtomicLong> = MeshRound.entries.associateWith { AtomicLong() }
    private val connectFails: Map<ConnectFailReason, AtomicLong> =
        ConnectFailReason.entries.associateWith { AtomicLong() }
    private val btLinksEstablished = AtomicLong()
    private val nanServesPeak = AtomicLong()
    private val nanOwedNoLinkPeakMs = AtomicLong()
    private val nanAcceptsRefused = AtomicLong()
    private val nanIcmKeepaliveFailed = AtomicLong()
    private val nanOffScreenRefusals = AtomicLong()
    private val nanMsgsAcked = AtomicLong()
    private val nanMsgSendsFailed = AtomicLong()
    private val nanMsgPlaneStalledPeakMs = AtomicLong()
    private val nanMsgPlaneCycles = AtomicLong()
    private val filesSentNan = AtomicLong()
    private val filesSentBt = AtomicLong()
    private val nanBulkGraceTimeouts = AtomicLong()
    private val fastOnlyDropped = AtomicLong()
    private val fastCompactSent = AtomicLong()
    private val fastLegacySent = AtomicLong()
    private val fastFragSent = AtomicLong()
    private val fastReassembled = AtomicLong()
    private val fastTooBig = AtomicLong()
    private val fastTranscodedSent = AtomicLong()
    private val transcodeFallbacks = AtomicLong()
    private val fastDrops: Map<FastPathDrop, AtomicLong> = FastPathDrop.entries.associateWith { AtomicLong() }
    private val bleSideOffered = AtomicLong()
    private val bleSidePartsAired = AtomicLong()
    private val bleSideTooBig = AtomicLong()
    private val bleSideHeard = AtomicLong()
    private val bleSideReassembled = AtomicLong()
    private val bleSideDeduped = AtomicLong()
    private val bleSideDrops: Map<BleSideDrop, AtomicLong> = BleSideDrop.entries.associateWith { AtomicLong() }
    private val bleBlindDrops: Map<BleBlindDrop, AtomicLong> = BleBlindDrop.entries.associateWith { AtomicLong() }
    private val bleLinkDupSkipped = AtomicLong()
    private val bleCodedSightings = AtomicLong()
    private val bleCodedDials = AtomicLong()
    private val blePhyStepsDown = AtomicLong()
    private val blePhyStepsUp = AtomicLong()
    private val blePhyGiveUps = AtomicLong()
    private val bleCodedAdvertDark = AtomicLong()
    private val digestsReplaced = AtomicLong()
    private val custodyOversizeRefused = AtomicLong()
    private val profileOversizeRefused = AtomicLong()
    private val linksProven = AtomicLong()
    private val linkProofFailed = AtomicLong()
    private val linkClaimRefused = AtomicLong()
    private val photosOverCeiling = AtomicLong()
    private val avatarsOverCeiling = AtomicLong()
    private val spoolTablesDerived = AtomicLong()
    private val spoolPushed = AtomicLong()
    private val spoolPulled = AtomicLong()
    private val spoolBridged = AtomicLong()
    private val spoolInvalid = AtomicLong()
    private val spoolAccounted = AtomicLong()
    private val spoolErrors = AtomicLong()
    private val spoolAttachPushed = AtomicLong()
    private val spoolAttachPulled = AtomicLong()
    private val spoolAttachDeferred = AtomicLong()
    private val spoolAttachProved = AtomicLong()
    private val spoolAttachRefused = AtomicLong()
    private val spoolAttachLocked = AtomicLong()
    private val loraSent = AtomicLong()
    private val loraFragSent = AtomicLong()
    private val loraTranscoded = AtomicLong()
    private val loraPadded = AtomicLong()
    private val loraReceived = AtomicLong()
    private val loraReassembled = AtomicLong()
    private val loraTooBig = AtomicLong()
    private val loraDroppedQueue = AtomicLong()
    private val loraAirtimeHeld = AtomicLong()
    private val loraBandBraked = AtomicLong()
    private val loraSuppressed = AtomicLong()
    private val loraNak = AtomicLong()
    private val loraNakByReason = ConcurrentHashMap<String, AtomicLong>()
    private val loraSessionUps = AtomicLong()
    private val loraDmReceived = AtomicLong()
    private val loraReprobed = AtomicLong()
    private val loraRoomTooLong = AtomicLong()
    private val loraRetiredCtl = AtomicLong()
    private val loraPreIdRefused = AtomicLong()
    private val loraBoardHoldersFailed = AtomicLong()
    private val loraSkippedLinked = AtomicLong()
    private val loraSkippedInternet = AtomicLong()
    private val loraStaleAtSend = AtomicLong()
    private val loraStaleAtSendByReason = ConcurrentHashMap<String, AtomicLong>()
    private val loraTickDeferred = AtomicLong()

    // Compact DMs (ADR 2026-10.x9fa): one pair-sealed unicast per DM, acked by the destination's board.
    private val loraDirectSent = AtomicLong()
    private val loraDirectAcked = AtomicLong()
    private val loraDirectImplicitAck = AtomicLong()
    private val loraDirectUnreachable = AtomicLong()
    private val loraDirectNoOutcome = AtomicLong()
    private val loraDirectHeld = AtomicLong()
    private val loraDirectTooLong = AtomicLong()
    private val loraDirectSlotZero = AtomicLong()
    private val loraDirectStranger = AtomicLong()
    private val loraDirectAuthFailed = AtomicLong()
    private val loraDirectClockRefused = AtomicLong()
    private val loraDirectDelivered = AtomicLong()
    private val loraDirectResent = AtomicLong()

    // Firmware 2.8 PKI-encrypts every compact DM (ADR 2026-10.aajj): what a missing key on either board cost.
    private val loraDirectNoKey = AtomicLong()
    private val loraDirectContactAdded = AtomicLong()
    private val loraDirectPeerLacksOurKey = AtomicLong()
    private val loraDirectBoardKeyConflict = AtomicLong()

    // The Meshtastic room's inbound half: how much chat the board's primary channel actually carries, how
    // much of it arrives off an MQTT uplink, and what the filters turn away. Nothing here transmits, so none
    // of it touches the airtime ledger.
    private val meshPostHeard = AtomicLong()
    private val meshPostIngested = AtomicLong()
    private val meshPostViaMqtt = AtomicLong()
    private val meshPostMatched = AtomicLong()
    private val meshPostVerified = AtomicLong()
    private val meshPostBoardVerified = AtomicLong()
    private val meshPostSignatureMismatch = AtomicLong()
    private val meshPostRefusedByReason = ConcurrentHashMap<String, AtomicLong>()

    // The Meshtastic room's outbound half. Unlike the four above these DO spend airtime, so `publicPostSent`
    // read against `airtime.usedMs` / `publicCeilingMs` in `…debug.LORA` says whether the ceiling is set right; a
    // refusal is one the composer showed the user.
    private val publicPostSent = AtomicLong()
    private val publicPostRefusedByReason = ConcurrentHashMap<String, AtomicLong>()

    // The DM auto-reply (`mesh/lora/DmAutoReplyPolicy`): `heard` is every text addressed to the board, `sent`
    // the ones answered — airtime, like `publicPostSent` — and the gap is itemised by refusal, the policy's
    // caps and the transport's gates in one map.
    private val autoReplyHeard = AtomicLong()
    private val autoReplySent = AtomicLong()
    private val autoReplyRefusedByReason = ConcurrentHashMap<String, AtomicLong>()

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

    /**
     * A point-to-point frame handed its last hop to an addressee we hold a live link to (`MeshRouter.handOn`) —
     * the flood's counterpart for a frame the flood never carries, and in practice a far pocket's ✓✓ reaching a
     * board-less author behind this phone's board.
     */
    fun onHandedOn() {
        framesHandedOn.incrementAndGet()
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

    /** A session loop's round threw; the loop runs the next one on time ([everyRound], #206). */
    fun onRoundFailed(round: MeshRound) {
        roundsFailed.getValue(round).incrementAndGet()
    }

    /** A contact-card intro we originated to a pending peer (see [IntroSync]) — a sealed `CTL_PROFILE` DM. */
    fun onIntroSent() {
        introsSent.incrementAndGet()
    }

    /** A sealed frame we sent to confirm a still-unconfirmed peer's session (the intro driver's answer). */
    fun onIntroAnswered() {
        introsAnswered.incrementAndGet()
    }

    /**
     * A DM session moved back onto our own init, lost to a race, because the peer sealed under it as responder (rule C,
     * ADR 2026-10.4mjm, `RatchetSessions`). Non-zero means a pair that would have sat on two roots met on one.
     */
    fun onRatchetReclaimed() {
        ratchetSwappedBack.incrementAndGet()
    }

    /** A `CTL_PRESENCE_KEY` sealed to a linked direct contact and handed to its link (ADR 2026-11.xp6d). */
    fun onPresenceKeySent() {
        presenceKeysSent.incrementAndGet()
    }

    /** A peer's `CTL_PRESENCE_KEY` stored a generation we did not hold, in the era it opened under. */
    fun onPresenceKeyStored() {
        presenceKeysStored.incrementAndGet()
    }

    /** A peer's `CTL_PRESENCE_KEY` opened under a new active root and replaced every key held for it (a new era). */
    fun onPresenceKeyReplaced() {
        presenceKeysReplaced.incrementAndGet()
    }

    /** A stored key left out of the listing: byte-equal to another listed contact's or to one of ours. */
    fun onPresenceKeysExcluded(count: Int) {
        if (count > 0) presenceKeysExcluded.addAndGet(count.toLong())
    }

    /** Our own presence keys moved: a mint, a fresh pair, an advance or a next key minted. */
    fun onPresenceKeyRotated() {
        presenceKeyRotations.incrementAndGet()
    }

    /** A restored `presence_key_last_generation` more than a year past the clock, clamped to the clock. */
    fun onPresenceKeyGenerationClamped() {
        presenceKeyGenerationClamped.incrementAndGet()
    }

    /** A restore's session reset to a direct contact no flooded reset covers, sent point to point over a link. */
    fun onLinkOwedResetSent() {
        linkOwedResetsSent.incrementAndGet()
    }

    /** A restore's link-owed reset the facade declined to seal: dropped with nothing sent (another reset already went). */
    fun onLinkOwedResetDeclined() {
        linkOwedResetsDeclined.incrementAndGet()
    }

    /** A restore's kept link-owed reset another reset of ours replaced: dropped, never resent. */
    fun onLinkOwedResetSuperseded() {
        linkOwedResetsSuperseded.incrementAndGet()
    }

    /** A link-up's presence-key pass was scheduled (it runs after the link-up wait). */
    fun onPresenceLinkPassScheduled() {
        presenceLinkPassesScheduled.incrementAndGet()
    }

    /** A link-up's presence-key pass ran to its end (or failed): `mesh/lab` waits for these to match the scheduled ones. */
    fun onPresenceLinkPassCompleted() {
        presenceLinkPassesCompleted.incrementAndGet()
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

    /**
     * The heartbeat basket ([app.getknit.knit.mesh.MeshManager.heal]) ran to its end: the sweeps, the retries,
     * the profile republish and the sealed profile updates. `heal()` itself returns the moment the basket is
     * launched, so this is the one signal that it finished — what the mesh-in-a-box lab waits on before it
     * re-links a node, because a profile the basket republishes is seeded into custody, never flooded, and a
     * link-up whose digest exchange runs before that row exists serves it only on the 60 s re-offer.
     */
    fun onHealCompleted() {
        healsCompleted.incrementAndGet()
    }

    /**
     * A neighbor's `blobreq` has been answered to its end — served, refused as already in flight, or turned
     * into a want of our own. Frames are handled one at a time, so an ask can sit behind a slow frame for a
     * while; this is what the mesh-in-a-box lab waits on before it lands bytes the ask would otherwise find.
     */
    fun onBlobAskHandled() {
        blobAsksHandled.incrementAndGet()
    }

    /**
     * A carrier left a carried DM's picture to its linked recipient instead of pulling it now (ADR 2026-10.g24x) — what
     * the mesh-in-a-box lab waits on, since custody holds the frame before the pull is decided.
     */
    fun onCarrierPullDeferred() {
        carrierPullsDeferred.incrementAndGet()
    }

    /**
     * An attachment of a message request not accepted yet was left unfetched — at its delivery, at the custody of its
     * frame, or as a group's photo (#282, `RequestHold`) — what the mesh-in-a-box lab waits on before it says nothing came.
     */
    fun onRequestHeld() {
        requestHolds.incrementAndGet()
    }

    /**
     * The acceptance collector's database re-arm returned (#282): an acceptance, a verification or the collector's first
     * value has been acted on — what the mesh-in-a-box lab waits on before it says a want was decided after one.
     */
    fun onAcceptanceRearm() {
        acceptanceRearms.incrementAndGet()
    }

    /** A carried attachment was not pulled because the carrier budget is spent (request-only bytes included, #282). */
    fun onCarrierPullOverBudget() {
        carrierPullsOverBudget.incrementAndGet()
    }

    /** We streamed a neighbor only the rest of a blob, from the offset its ask named: a cut transfer resumed (#116). */
    fun onFileResumedOut() {
        filesResumedOut.incrementAndGet()
    }

    /** A link took the rest of a blob onto the prefix we kept from a cut transfer, instead of the whole file (#116). */
    fun onFileResumedIn() {
        filesResumedIn.incrementAndGet()
    }

    /**
     * A file spliced from a kept prefix and a holder's tail failed its hash check, so the prefix was dropped and the
     * blob asked for from byte 0 (#116). Anything but zero is a holder or a prefix that was wrong.
     */
    fun onSpliceRefused() {
        splicesRefused.incrementAndGet()
    }

    /**
     * What the blob fetch did (ADR 2026-10.6e5t): a holder query sent, answered, or refused over budget; an answer
     * taken (what the mesh-in-a-box lab waits on before it moves the clock) or ignored; an ask of a holder that said it
     * holds the blob, a confirm query, a fallback to the next holder, a blind ask of a neighbour that cannot be queried,
     * and a pull-through ask.
     */
    fun onBlobEvent(event: BlobExchange.BlobEvent) {
        blobEvents.getValue(event).incrementAndGet()
        // `photosOverCeiling`, knit-ios's name and meaning: one per holder passed over on a photo-only want, for a stream
        // past the ceiling or a `blobhas` size over it. A pushed avatar the link aborted mid-stream is not counted.
        if (event == BlobExchange.BlobEvent.HolderOverCeiling) photosOverCeiling.incrementAndGet()
    }

    /**
     * A profile photo refused at the photo ceiling (`FileIntake.MAX_AVATAR_BYTES`, #234) from bytes this phone held, not
     * from a holder's claim: a pushed avatar over it that reached the pipeline, a held copy found over it before
     * adoption, and a face over it the local sweep cleared. Android's own count. `photosOverCeiling`, the one knit-ios
     * reports too, counts only the holders passed over ([onBlobEvent]).
     */
    fun onAvatarOverCeiling(count: Int = 1) {
        avatarsOverCeiling.addAndGet(count.toLong())
    }

    /**
     * A copy served to an asker that takes it over Wi-Fi Aware only (ADR 2026-10.js2p) went nowhere: the fast plane
     * would not arm, refused the enqueue, or did not link inside the grace — never handed to Bluetooth instead.
     */
    fun onFastOnlyDropped() {
        fastOnlyDropped.incrementAndGet()
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

    /**
     * A re-delivered DM to us that drew no new sealed receipt: ours, sealed in the session's current era inside its
     * re-arm floor, is still live in our custody (#176, ADR 2026-10.8hn6). Each one is a receipt not minted.
     */
    fun onReAckSkipped() {
        receiptsReAckSkipped.incrementAndGet()
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

    /**
     * Broadcast-room delivery ticks that rode a frame already going to their author instead of waiting for a
     * link (ADR 2026-09.aa27). Each one is a room tick that reached its author for no frame and no custody
     * row of its own; the gap between this and the ticks still held is what the ride does not cover.
     */
    fun onReceiptRidden(count: Int) {
        receiptsRidden.addAndGet(count.toLong())
    }

    /**
     * A due room-tick hold that found no route — no link, no spool, no short-range plane reaching its author — and
     * stayed parked (`AckSync.escalateRide`, ADR 2026-10.rp3s). Counted per pass, so it climbs while an author stays out
     * of reach: the signal that the hold was judged, not merely not yet due.
     */
    fun onRideUnrouted() {
        ridesUnrouted.incrementAndGet()
    }

    /**
     * A DM ✓✓ the pipeline declined as an answer — no session the author holds yet (ADR 2026-10.x9fa) — and handed to
     * MeshManager's hold, to be sealed once one is. Counted after the hand-off: the signal that a re-ack was judged.
     */
    fun onReceiptHeld() {
        receiptsHeld.incrementAndGet()
    }

    /**
     * A room tick batch that waited out its ride hold and was pushed straight into its author's spool scope
     * (ADR 2026-09.y5f3): sealed once, no frame on the radios, no custody row here. Beside [onReceiptRidden]
     * and `receiptsResent`, this is the third way a room ✓✓ leaves a phone with no link to its author.
     */
    fun onReceiptSpooled() {
        receiptsSpooled.incrementAndGet()
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

    /**
     * One `CTL_GROUP_KEY` parked because it named a group we did not hold yet ([PendingGroupKeys]) — the
     * seed that outran its group's first frame and carried no roster of its own (an older build's), or one
     * whose roster was refused. Paired with [onGroupSeedReplayed]: a held count that never turns into a
     * replay is a member that heard a seed and then never saw the roster.
     */
    fun onGroupSeedHeld() {
        groupSeedsHeld.incrementAndGet()
    }

    /** One parked `CTL_GROUP_KEY` re-run through the deliver path once its group was reconciled. */
    fun onGroupSeedReplayed() {
        groupSeedsReplayed.incrementAndGet()
    }

    /**
     * One `CTL_GROUP_KEY_REQ` originated — we asked a member to re-seal the seeds we are missing. The
     * request leg of the recovery ADR 017 describes, and the only one that used to be unmetered: with
     * `groupSeedsSent`/`groupSeedsAdopted` covering the answer and the adoption, a node stuck on
     * `GROUP_RATCHET_NO_KEY` still could not be told apart from one whose heuristic never fired, except by
     * a logcat line (unreadable on a lab Pixel that had not restarted — three-Pixel capture, 2026-09-07).
     */
    fun onGroupKeyRequested() {
        groupKeyRequestsSent.incrementAndGet()
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

    /**
     * Custody refused a frame naming a group whose roster does not derive to the group's id, or whose sender is not
     * one of its members (ADR 2026-10.7mqm). No current build sends one (knit-ios neither); a pre-2.3.0 build after a
     * departure does, and a forged one fills somebody else's group quota. The frame is still delivered and relayed.
     */
    fun onGroupCustodyRefused() {
        groupCustodyRefused.incrementAndGet()
    }

    /**
     * Custody refused a frame whose `signed` bytes are longer than its byte cap
     * (`ForwardRepository.DEFAULT_MAX_SIGNED_BYTES`, #227). No honest build authors one; the frame is still delivered
     * and relayed.
     */
    fun onCustodyOversizeRefused() {
        custodyOversizeRefused.incrementAndGet()
    }

    /**
     * A `profile` frame was refused because its `signed` bytes are longer than the profile cap
     * (`ForwardRepository.DEFAULT_MAX_PROFILE_SIGNED_BYTES`, #234): at the door (`InboundPipeline.verifyInbound`, so it
     * never reaches custody) or by custody itself. Counted once per refusal; the frame is still relayed.
     */
    fun onProfileOversizeRefused() {
        profileOversizeRefused.incrementAndGet()
    }

    /** A link was registered as proven: its peer proved the node id its HELLO claims (`LinkClaims.proven`). */
    fun onLinkProven() {
        linksProven.incrementAndGet()
    }

    /** A link's proof failed on this side: missing, malformed, another id's key, or a bad signature (`LinkClaims`). */
    fun onLinkProofFailed() {
        linkProofFailed.incrementAndGet()
    }

    /**
     * An unproven link claiming a proof-capable node id was refused at its handshake (R1), or a live one was closed because
     * its id proved itself or became proof-capable (R3, R4), including one a proven link replaced on its own plane. Each
     * link counts once (`LinkClaims`).
     */
    fun onLinkClaimRefused() {
        linkClaimRefused.incrementAndGet()
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

    /**
     * High-water mark of the **owed-with-no-link episode** — how long a sync has stayed owed to a reachable
     * peer while no data path formed (`WifiAwareTransport.syncOwedSince`, the clock the wedge watchdog runs
     * on). Zero on a healthy mesh: an idle converged mesh owes nothing, and a normal sync clears the episode
     * the moment a link forms.
     *
     * This exists because the coordination plane can mask a dead data path. Once sealed DM-form chat rides
     * `fastSend` ([shouldFastSend]), a node whose NDP never forms still delivers messages — it just stops
     * converging custody, quietly. Before that, a wedge announced itself by breaking DMs (three-Pixel
     * capture, 2026-09-07); after it, this gauge is what is left to notice one. A few seconds is ordinary
     * handshake latency; minutes means the data plane is not forming and only the best-effort planes are
     * carrying anything.
     */
    fun onNanSyncOwed(owedForMs: Long) {
        nanOwedNoLinkPeakMs.accumulateAndGet(owedForMs) { a, b -> maxOf(a, b) }
    }

    /** An inbound NAN accept was refused by the serve policy (cap reached / initiator handshake in flight). */
    fun onNanAcceptRefused() {
        nanAcceptsRefused.incrementAndGet()
    }

    /** A live publish session's updatePublish failed — the ICM relight fell back to the subscribe re-arm. */
    fun onNanIcmKeepaliveFailed() {
        nanIcmKeepaliveFailed.incrementAndGet()
    }

    /**
     * A publish/subscribe was refused for the foreground-only location app-op with Knit off screen (API 29-32,
     * ADR 2026-09.535d) — once per latch, not per attempt. Non-zero after the app has been opened once means the
     * service's `location` foreground type is not taking on this device.
     */
    fun onNanOffScreenRefused() {
        nanOffScreenRefusals.incrementAndGet()
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
     * The coordination-plane watchdog (ADR 2026-09.jjhg) holds a stalled episode open: no ack since it began, sends
     * swallowed or failing while peers are still sighted. Peak length, like [onNanSyncOwed]'s — a plane that never
     * stalls reads 0, and one the cycle cured reads how long it took.
     */
    fun onNanMsgPlaneStalled(stalledForMs: Long) {
        nanMsgPlaneStalledPeakMs.accumulateAndGet(stalledForMs) { a, b -> maxOf(a, b) }
    }

    /** The coordination-plane watchdog cycled the Aware session (a NAN restart) to cure a stalled plane. */
    fun onNanMsgPlaneCycled() {
        nanMsgPlaneCycles.incrementAndGet()
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

    /** A fast frame was accepted onto the BLE side channel's queue (`BleSideChannel.offer`). */
    fun onBleSideOffered() {
        bleSideOffered.incrementAndGet()
    }

    /** A page (one frame, or one fragment of one) went on air on a side-channel advertising set. */
    fun onBleSidePartAired() {
        bleSidePartsAired.incrementAndGet()
    }

    /** A frame offered to the side channel fit no page even fragmented — it rides the links and the flood only. */
    fun onBleSideTooBig() {
        bleSideTooBig.incrementAndGet()
    }

    /** A frame was heard off a side-channel page and handed to the router (after the channel's own dedup). */
    fun onBleSideHeard() {
        bleSideHeard.incrementAndGet()
    }

    /** A multi-part side-channel frame reassembled completely. */
    fun onBleSideReassembled() {
        bleSideReassembled.incrementAndGet()
    }

    /** A page or frame the channel had already heard inside its window (the same part airs for its whole dwell). */
    fun onBleSideDeduped() {
        bleSideDeduped.incrementAndGet()
    }

    /**
     * A frame already on its way over a Bluetooth link (or just in off it) was handed to that link again and
     * skipped — the router's flood copy after the fast path's, or a re-fan back toward its hop (`LinkCrossings`).
     * Climbs about one per room frame per link on a two-radio mesh; the far end's SeenSet used to eat these.
     */
    fun onBleLinkDupSkipped() {
        bleLinkDupSkipped.incrementAndGet()
    }

    /** A presence advert was heard on the Coded PHY (the experiment, ADR 2026-10.yvn6) — one per scan callback. */
    fun onBleCodedSighting() {
        bleCodedSightings.incrementAndGet()
    }

    /** A dial went to a peer's Coded address: no fresh 1M advert of it was heard, so the link opens on Coded. */
    fun onBleCodedDial() {
        bleCodedDials.incrementAndGet()
    }

    /** A link's PHY changed under the experiment: down to Coded ([toCoded]) or back up to 1M/2M. */
    fun onBlePhyStep(toCoded: Boolean) {
        (if (toCoded) blePhyStepsDown else blePhyStepsUp).incrementAndGet()
    }

    /** A link's PHY request went unanswered or was answered with another PHY: that link is left where it is. */
    fun onBlePhyGiveUp() {
        blePhyGiveUps.incrementAndGet()
    }

    /** The controller refused the Coded advert set (too many sets, or no feature): dark until the next bring-up. */
    fun onBleCodedAdvertDark() {
        bleCodedAdvertDark.incrementAndGet()
    }

    /**
     * A custody digest was handed to a link while an older one still waited there unwritten, and took its id
     * set instead of queueing behind it (`FramedLink.sendDigest`, ADR 2026-09.tjfb). Zero on an idle link;
     * about one a minute per link while a back-fill or a file holds the writer.
     */
    fun onDigestReplaced() {
        digestsReplaced.incrementAndGet()
    }

    /** The Bluetooth plane dropped or refused something for a blinded advert — see [BleBlindDrop]. */
    fun onBleBlindDropped(reason: BleBlindDrop) {
        bleBlindDrops.getValue(reason).incrementAndGet()
    }

    /** The side channel discarded a frame — see [BleSideDrop] for how to read each reason. */
    fun onBleSideDropped(reason: BleSideDrop) {
        bleSideDrops.getValue(reason).incrementAndGet()
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

            // A contact's direct link: not a radio, so not part of the NAN-vs-BLE split this counts.
            TransportKind.DirectLink -> Unit
        }
    }

    /** An armed bulk-transfer NDP didn't come up within the composite's grace — the blob fell back to BLE.
     *  Climbing far faster than [Snapshot.Files.filesSentNan] means we're arming ghosts (see BulkWantTracker). */
    fun onBulkGraceTimeout() {
        nanBulkGraceTimeouts.incrementAndGet()
    }

    /**
     * The spool plane re-derived its scope table (ADR 2026-09.dcah): once per input event — a session
     * confirmed, a pair peer named or pinned, a root minted or adopted, a room joined — and once a minute as
     * the net. The device oracle for the event hooks: a burst of events is a few, an idle hour is sixty.
     */
    fun onSpoolTableDerived() {
        spoolTablesDerived.incrementAndGet()
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

    /**
     * A DM photo we hold was settled for good without asking a spool, because its recipient provably holds the bytes
     * over a short-range radio (`AttachmentDeferPolicy.proved`, ADR 2026-10.vy27). Counted per round that passes it, like
     * a deferral, so an upload that never happens on purpose never reads like a broken one.
     */
    fun onSpoolAttachmentProved() {
        spoolAttachProved.incrementAndGet()
    }

    /**
     * An attachment of ours was settled without asking a spool because that connection takes none of this phone's
     * uploads (C-7.6-24: a files gate and no files ticket here, or the ticket's day spent). Counted for the same
     * reason as a deferral: a locked upload and a broken one look alike otherwise.
     */
    fun onSpoolAttachmentLocked() {
        spoolAttachLocked.incrementAndGet()
    }

    /**
     * A spool attachment this device's own screening refused (bytes dropped, verdict kept) was not fetched
     * again (#109). Counted for the same reason as a deferral: a skip and a stuck fetch look alike otherwise.
     */
    fun onSpoolAttachmentRefused() {
        spoolAttachRefused.incrementAndGet()
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
     * A queued LoRa frame the airtime budget made wait. **Held, not dropped** — it stays queued for a later window —
     * which is exactly why it needed a counter of its own: `loraDroppedQueue` only ever sees such a frame if the queue
     * later fills and sheds it, so a plane whose window is spent reads perfectly healthy right up until it overflows
     * (field-observed 2026-09-04, a 99 % spent budget with every drop counter at zero throughout). One ledger since ADR
     * 2026-10.ug4a, so no longer split by bucket.
     *
     * Counted once per frame, not once per pacer wake, so this measures congestion rather than the clock.
     */
    fun onLoraAirtimeHeld() {
        loraAirtimeHeld.incrementAndGet()
    }

    /**
     * A Knit room post the shared-band brake kept queued: the board reported its channel 40 % busy or more (ADR
     * 2026-10.ug4a). Held, not dropped, like [onLoraAirtimeHeld], and counted once per frame the same way. A post still
     * braked past `FRESH_MS` from its send is then dropped at send as `STALE`.
     */
    fun onLoraBandBraked() {
        loraBandBraked.incrementAndGet()
    }

    /** The LoRa board session reached Ready — context for the received/sent counts (how often it links). */
    fun onLoraSessionUp() {
        loraSessionUps.incrementAndGet()
    }

    /**
     * A DM-form mesh frame received over LoRa: an older build's board still airs its DMs that way (ADR 039). This build
     * sends none — its DMs ride compact ([onLoraDirectSent], ADR 2026-10.x9fa).
     */
    fun onLoraDmReceived() {
        loraDmReceived.incrementAndGet()
    }

    /**
     * The newest of our DMs still without its ✓✓ re-aired compact to a peer whose schedule fell due (ADR 2026-10.rp3s,
     * re-keyed by ADR 2026-10.x9fa, `LoraReprobe`): one per peer per backed-off gap, whatever their board said.
     */
    fun onLoraReprobed() {
        loraReprobed.incrementAndGet()
    }

    /**
     * A compact DM, DM reaction or ✓✓ the board took for the air (ADR 2026-10.x9fa): one pair-sealed unicast with
     * `want_ack`, its retries the firmware's. Against [onLoraDirectAcked] it is the share that reached a board.
     */
    fun onLoraDirectSent() {
        loraDirectSent.incrementAndGet()
    }

    /** The destination's own board acked a compact DM: it reached that radio — never evidence its phone read it. */
    fun onLoraDirectAcked() {
        loraDirectAcked.incrementAndGet()
    }

    /**
     * An implicit ack for a compact DM: a relay, or our own board, heard it repeated. Counted, never trusted — it ends
     * no wait and marks nobody reachable.
     */
    fun onLoraDirectImplicitAck() {
        loraDirectImplicitAck.incrementAndGet()
    }

    /** The firmware gave up retrying a compact DM (`MAX_RETRANSMIT` / `NO_RESPONSE`): its board is marked unreachable. */
    fun onLoraDirectUnreachable() {
        loraDirectUnreachable.incrementAndGet()
    }

    /** A compact DM nothing answered before its deadline: booked and marked as if the firmware had given up. */
    fun onLoraDirectNoOutcome() {
        loraDirectNoOutcome.incrementAndGet()
    }

    /** A compact DM offered to a peer marked unreachable or refused, held for the next hearing or re-probe. */
    fun onLoraDirectHeld() {
        loraDirectHeld.incrementAndGet()
    }

    /** A compact DM past one packet: not carried over LoRa; the signed copy still travels the other planes. */
    fun onLoraDirectTooLong() {
        loraDirectTooLong.incrementAndGet()
    }

    /** A compact DM refused because the plane is bound to slot 0: Knit's frames belong on the Knit channel. */
    fun onLoraDirectSlotZero() {
        loraDirectSlotZero.incrementAndGet()
    }

    /** A compact DM addressed to our board whose pairTag matches no pinned peer: dropped. */
    fun onLoraDirectStranger() {
        loraDirectStranger.incrementAndGet()
    }

    /** A compact DM whose pairTag matched and whose seal did not authenticate: forged, damaged or reflected. */
    fun onLoraDirectAuthFailed() {
        loraDirectAuthFailed.incrementAndGet()
    }

    /** An authenticated compact DM stamped outside the replay window (older than custody's TTL, or in the future). */
    fun onLoraDirectClockRefused() {
        loraDirectClockRefused.incrementAndGet()
    }

    /** A compact DM opened and handed to the pipeline. */
    fun onLoraDirectDelivered() {
        loraDirectDelivered.incrementAndGet()
    }

    /** Our own board refused a compact DM before it aired: it holds no key for the destination's board (`39`). */
    fun onLoraDirectNoKey() {
        loraDirectNoKey.incrementAndGet()
    }

    /** Our board took an `add_contact` teaching it a pinned contact's key after a `39` (ADR 2026-10.aajj). */
    fun onLoraDirectContactAdded() {
        loraDirectContactAdded.incrementAndGet()
    }

    /** The destination's board could not decrypt a compact DM: it holds no key for ours (`PKI_UNKNOWN_PUBKEY`, 35). */
    fun onLoraDirectPeerLacksOurKey() {
        loraDirectPeerLacksOurKey.incrementAndGet()
    }

    /** Our board's NodeInfo stream showed a key for a contact's board other than their profile's: nothing written. */
    fun onLoraDirectBoardKeyConflict() {
        loraDirectBoardKeyConflict.incrementAndGet()
    }

    /** A compact DM re-aired: after its board was heard again, or by the re-probe. */
    fun onLoraDirectResent() {
        loraDirectResent.incrementAndGet()
    }

    /**
     * An own Nearby-room post kept off LoRa because it needs more than one packet (ADR 2026-10.rp3s). The radios
     * and the spool still carry it. Apart from `loraTooBig`, which is a frame past the plane's three packets.
     */
    fun onLoraRoomTooLong() {
        loraRoomTooLong.incrementAndGet()
    }

    /**
     * An older build's gossip OFFER (first byte `0x10`) heard and dropped (ADR 2026-10.rp3s): the bridge is gone,
     * and counting it apart keeps `UNKNOWN_TAG` meaning a tag nobody assigned. Climbing says an old build's board
     * is still in range.
     */
    fun onLoraRetiredCtl() {
        loraRetiredCtl.incrementAndGet()
    }

    /**
     * A frame fanned before the plane had read our own id, refused because the few admitted then were spent
     * (`LoraMeshTransport.PRE_ID_ADMISSIONS`, ADR 2026-10.rp3s). Climbing says the id read is stalling.
     */
    fun onLoraPreIdRefused() {
        loraPreIdRefused.incrementAndGet()
    }

    /**
     * The board map's source failed (ADR 2026-10.rp3s, ADR 2026-10.x9fa). The last map it gave stays in force and the
     * source is asked again every few minutes; with none, no compact DM goes and the re-probe only defers. Non-zero and
     * climbing says DMs may have stopped riding LoRa while the row reads Ready.
     */
    fun onLoraBoardHoldersFailed() {
        loraBoardHoldersFailed.incrementAndGet()
    }

    /**
     * A compact DM kept off LoRa because its recipient is us or a peer a higher-preference plane holds a live link to
     * (ADR 054) — the link carries the signed copy. Climbing while texting a pocket-mate is the gate working; climbing
     * while a far peer starves means the link set is wrong.
     */
    fun onLoraSkippedLinked() {
        loraSkippedLinked.incrementAndGet()
    }

    /**
     * A compact DM kept off LoRa because its recipient was seen on a connected spool within `SPOOL_COVER_MS` (ADR
     * 2026-09.y5f3) — the spool carries the signed copy, for free. Split from [onLoraSkippedLinked] on purpose:
     * climbing here while the phones are far apart is airtime saved, climbing there is a link.
     */
    fun onLoraSkippedInternet() {
        loraSkippedInternet.incrementAndGet()
    }

    /**
     * A queued frame was refused at the moment it reached the air, because an enqueue-time gate had since
     * changed its answer (`StaleAtSend`: the addressee linked on a better plane or a spool, a compact DM's own gates
     * moved — not a direct board holder, held, acked meanwhile, slot 0 — the frame aged past the freshness window, or it
     * turned out not to be ours).
     *
     * Deliberately not folded into [onLoraSkippedLinked] or [onLoraSuppressed]: those say the gate refused a
     * frame before it ever queued, this says the queue held it until the answer moved, and only the split
     * measures the race. Climbing under `LINKED` while the phones are in Wi-Fi Aware range is airtime saved;
     * climbing while they are far apart means the link set is lying.
     */
    fun onLoraStaleAtSend(reason: String) {
        loraStaleAtSend.incrementAndGet()
        loraStaleAtSendByReason.getOrPut(reason) { AtomicLong() }.incrementAndGet()
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

    /** A heard post whose signature verified on this phone under the key the matched contact's profile advertises. */
    fun onMeshPostVerified() {
        meshPostVerified.incrementAndGet()
    }

    /** A heard post our own board vouched for (`xeddsa_signed`), with no contact key on this phone to check it against. */
    fun onMeshPostBoardVerified() {
        meshPostBoardVerified.incrementAndGet()
    }

    /** A signed post on a contact's number that failed under their advertised key — shown as a stranger, never as them. */
    fun onMeshPostSignatureMismatch() {
        meshPostSignatureMismatch.incrementAndGet()
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

    /** A text addressed to the board itself — a Meshtastic user's DM — before any gate runs. */
    fun onAutoReplyHeard() {
        autoReplyHeard.incrementAndGet()
    }

    /** One auto-reply the board put on the air. Costs airtime, under the same half ceiling as a post (ADR 2026-10.ug4a). */
    fun onAutoReplySent() {
        autoReplySent.incrementAndGet()
    }

    /** A DM not answered, by `DmAutoReplyPolicy.Refusal` or `AutoReplyRefusal`. */
    fun onAutoReplyRefused(reason: String) {
        autoReplyRefusedByReason.computeIfAbsent(reason) { AtomicLong() }.incrementAndGet()
    }

    /** Every counter, read at once ([Snapshot]). One group's counters alone come from [frames], [lora] and the rest. */
    fun snapshot(): Snapshot =
        Snapshot(
            frames = frames(),
            keys = keys(),
            seals = seals(),
            receipts = receipts(),
            groups = groups(),
            files = files(),
            bluetooth = bluetooth(),
            nan = nan(),
            fast = fast(),
            spool = spool(),
            lora = lora(),
            meshtastic = meshtastic(),
        )

    /** The [Snapshot.Frames] counters alone, read now. */
    fun frames(): Snapshot.Frames {
        val byReason = drops.mapValues { it.value.get() }
        return Snapshot.Frames(
            framesOriginated = framesOriginated.get(),
            framesDelivered = framesDelivered.get(),
            framesRelayed = framesRelayed.get(),
            framesHandedOn = framesHandedOn.get(),
            framesSuppressed = framesSuppressed.get(),
            framesDeduped = framesDeduped.get(),
            bytesSent = bytesSent.get(),
            framesDropped = byReason.values.sum(),
            dropsByReason = byReason.filterValues { it > 0 },
            digestsReplaced = digestsReplaced.get(),
            custodyOversizeRefused = custodyOversizeRefused.get(),
            profileOversizeRefused = profileOversizeRefused.get(),
            linksProven = linksProven.get(),
            linkProofFailed = linkProofFailed.get(),
            linkClaimRefused = linkClaimRefused.get(),
            healsCompleted = healsCompleted.get(),
            roundsFailedBy = roundsFailed.mapValues { it.value.get() }.filterValues { it > 0 },
        )
    }

    /** The [Snapshot.Keys] counters alone, read now. */
    fun keys(): Snapshot.Keys =
        Snapshot.Keys(
            keyRequestsSent = keyRequestsSent.get(),
            keysServed = keysServed.get(),
            keysRecovered = keysRecovered.get(),
            framesHeld = framesHeld.get(),
            framesReplayed = framesReplayed.get(),
            introsSent = introsSent.get(),
            introsAnswered = introsAnswered.get(),
            ratchetSwappedBack = ratchetSwappedBack.get(),
            presenceKeysSent = presenceKeysSent.get(),
            presenceKeysStored = presenceKeysStored.get(),
            presenceKeysReplaced = presenceKeysReplaced.get(),
            presenceKeysExcluded = presenceKeysExcluded.get(),
            presenceKeyRotations = presenceKeyRotations.get(),
            presenceKeyGenerationClamped = presenceKeyGenerationClamped.get(),
            linkOwedResetsSent = linkOwedResetsSent.get(),
            linkOwedResetsDeclined = linkOwedResetsDeclined.get(),
            linkOwedResetsSuperseded = linkOwedResetsSuperseded.get(),
            presenceLinkPassesScheduled = presenceLinkPassesScheduled.get(),
            presenceLinkPassesCompleted = presenceLinkPassesCompleted.get(),
        )

    /** The [Snapshot.Seals] counters alone, read now. */
    fun seals(): Snapshot.Seals =
        Snapshot.Seals(
            dmSealedV2 = dmSealedV2.get(),
            dmSealedV3 = dmSealedV3.get(),
            dmSealedV1Fallback = dmSealedV1Fallback.get(),
            reactionsSealed = reactionsSealed.get(),
            reactionsSealedFallback = reactionsSealedFallback.get(),
        )

    /** The [Snapshot.Receipts] counters alone, read now. */
    fun receipts(): Snapshot.Receipts =
        Snapshot.Receipts(
            receiptsResent = receiptsResent.get(),
            receiptsSealed = receiptsSealed.get(),
            receiptsSealedFallback = receiptsSealedFallback.get(),
            receiptsReAckSkipped = receiptsReAckSkipped.get(),
            receiptsCustodied = receiptsCustodied.get(),
            receiptsCoalesced = receiptsCoalesced.get(),
            receiptsRidden = receiptsRidden.get(),
            ridesUnrouted = ridesUnrouted.get(),
            receiptsHeld = receiptsHeld.get(),
            receiptsSpooled = receiptsSpooled.get(),
            ticksUnsigned = ticksUnsigned.get(),
        )

    /** The [Snapshot.Groups] counters alone, read now. */
    fun groups(): Snapshot.Groups =
        Snapshot.Groups(
            groupSealedRatchet = groupSealedRatchet.get(),
            groupSealedV1Fallback = groupSealedV1Fallback.get(),
            groupSeedsSent = groupSeedsSent.get(),
            groupSeedsAdopted = groupSeedsAdopted.get(),
            groupSeedsHeld = groupSeedsHeld.get(),
            groupSeedsReplayed = groupSeedsReplayed.get(),
            groupKeyRequestsSent = groupKeyRequestsSent.get(),
            groupRootsMinted = groupRootsMinted.get(),
            groupRootsAdopted = groupRootsAdopted.get(),
            groupCustodyRefused = groupCustodyRefused.get(),
        )

    /** The [Snapshot.Files] counters alone, read now. */
    fun files(): Snapshot.Files =
        Snapshot.Files(
            filesSentNan = filesSentNan.get(),
            filesSentBt = filesSentBt.get(),
            blobAsksHandled = blobAsksHandled.get(),
            carrierPullsDeferred = carrierPullsDeferred.get(),
            requestHolds = requestHolds.get(),
            acceptanceRearms = acceptanceRearms.get(),
            carrierPullsOverBudget = carrierPullsOverBudget.get(),
            filesResumedOut = filesResumedOut.get(),
            filesResumedIn = filesResumedIn.get(),
            splicesRefused = splicesRefused.get(),
            blobQueriesSent = blob(BlobExchange.BlobEvent.QuerySent),
            blobQueriesAnswered = blob(BlobExchange.BlobEvent.QueryAnswered),
            blobQueriesRefused = blob(BlobExchange.BlobEvent.QueryRefused),
            blobAnswersTaken = blob(BlobExchange.BlobEvent.AnswerTaken),
            blobAnswersIgnored = blob(BlobExchange.BlobEvent.AnswerIgnored),
            blobFetches = blob(BlobExchange.BlobEvent.Fetch),
            blobConfirms = blob(BlobExchange.BlobEvent.Confirm),
            blobFallbacks = blob(BlobExchange.BlobEvent.Fallback),
            blobBlindAsks = blob(BlobExchange.BlobEvent.BlindAsk),
            blobPullThroughs = blob(BlobExchange.BlobEvent.PullThrough),
            blobAsksParked = blob(BlobExchange.BlobEvent.Park),
            blobProgressSent = blob(BlobExchange.BlobEvent.ProgressSent),
            blobProgressTaken = blob(BlobExchange.BlobEvent.ProgressTaken),
            blobCancelsSent = blob(BlobExchange.BlobEvent.CancelSent),
            blobCancelsTaken = blob(BlobExchange.BlobEvent.CancelTaken),
            blobFastOnlyHeld = blob(BlobExchange.BlobEvent.FastOnlyHeld),
            blobFastOnlyRefused = blob(BlobExchange.BlobEvent.FastOnlyRefused),
            blobPullsWithheld = blob(BlobExchange.BlobEvent.PullWithheld),
            blobEchoesDeclined = blob(BlobExchange.BlobEvent.EchoDeclined),
            blobProofQueriesSent = blob(BlobExchange.BlobEvent.ProofQuerySent),
            blobProofAnswersTaken = blob(BlobExchange.BlobEvent.ProofAnswerTaken),
            blobProofsHeld = blob(BlobExchange.BlobEvent.ProofHeld),
            blobHoldersOverCeiling = blob(BlobExchange.BlobEvent.HolderOverCeiling),
            blobOverCeiling = blob(BlobExchange.BlobEvent.OverCeiling),
            photosOverCeiling = photosOverCeiling.get(),
            avatarsOverCeiling = avatarsOverCeiling.get(),
            blobLinkRefused = blob(BlobExchange.BlobEvent.LinkRefused),
            fastOnlyDropped = fastOnlyDropped.get(),
        )

    private fun blob(event: BlobExchange.BlobEvent): Long = blobEvents.getValue(event).get()

    /** The [Snapshot.Bluetooth] counters alone, read now. */
    fun bluetooth(): Snapshot.Bluetooth {
        val connectByReason = connectFails.mapValues { it.value.get() }
        return Snapshot.Bluetooth(
            btConnectFails = connectByReason.values.sum(),
            btConnectFailsByReason = connectByReason.filterValues { it > 0 },
            btLinksEstablished = btLinksEstablished.get(),
            bleLinkDupSkipped = bleLinkDupSkipped.get(),
            bleCodedSightings = bleCodedSightings.get(),
            bleCodedDials = bleCodedDials.get(),
            blePhyStepsDown = blePhyStepsDown.get(),
            blePhyStepsUp = blePhyStepsUp.get(),
            blePhyGiveUps = blePhyGiveUps.get(),
            bleCodedAdvertDark = bleCodedAdvertDark.get(),
            bleSideOffered = bleSideOffered.get(),
            bleSidePartsAired = bleSidePartsAired.get(),
            bleSideTooBig = bleSideTooBig.get(),
            bleSideHeard = bleSideHeard.get(),
            bleSideReassembled = bleSideReassembled.get(),
            bleSideDeduped = bleSideDeduped.get(),
            bleSideDropsByReason = bleSideDrops.mapValues { it.value.get() }.filterValues { it > 0 },
            bleBlindDropsByReason = bleBlindDrops.mapValues { it.value.get() }.filterValues { it > 0 },
        )
    }

    /** The [Snapshot.Nan] counters alone, read now. */
    fun nan(): Snapshot.Nan =
        Snapshot.Nan(
            nanServesPeak = nanServesPeak.get(),
            nanOwedNoLinkPeakMs = nanOwedNoLinkPeakMs.get(),
            nanAcceptsRefused = nanAcceptsRefused.get(),
            nanIcmKeepaliveFailed = nanIcmKeepaliveFailed.get(),
            nanOffScreenRefusals = nanOffScreenRefusals.get(),
            nanMsgsAcked = nanMsgsAcked.get(),
            nanMsgSendsFailed = nanMsgSendsFailed.get(),
            nanMsgPlaneStalledPeakMs = nanMsgPlaneStalledPeakMs.get(),
            nanMsgPlaneCycles = nanMsgPlaneCycles.get(),
            nanBulkGraceTimeouts = nanBulkGraceTimeouts.get(),
        )

    /** The [Snapshot.FastPath] counters alone, read now. */
    fun fast(): Snapshot.FastPath =
        Snapshot.FastPath(
            fastCompactSent = fastCompactSent.get(),
            fastLegacySent = fastLegacySent.get(),
            fastFragSent = fastFragSent.get(),
            fastReassembled = fastReassembled.get(),
            fastTooBig = fastTooBig.get(),
            fastTranscodedSent = fastTranscodedSent.get(),
            transcodeFallbacks = transcodeFallbacks.get(),
            fastDropsByReason = fastDrops.mapValues { it.value.get() }.filterValues { it > 0 },
        )

    /** The [Snapshot.Spool] counters alone, read now. */
    fun spool(): Snapshot.Spool =
        Snapshot.Spool(
            spoolTablesDerived = spoolTablesDerived.get(),
            spoolPushed = spoolPushed.get(),
            spoolPulled = spoolPulled.get(),
            spoolBridged = spoolBridged.get(),
            spoolInvalid = spoolInvalid.get(),
            spoolAccounted = spoolAccounted.get(),
            spoolErrors = spoolErrors.get(),
            spoolAttachPushed = spoolAttachPushed.get(),
            spoolAttachPulled = spoolAttachPulled.get(),
            spoolAttachDeferred = spoolAttachDeferred.get(),
            spoolAttachProved = spoolAttachProved.get(),
            spoolAttachRefused = spoolAttachRefused.get(),
            spoolAttachLocked = spoolAttachLocked.get(),
        )

    /** The [Snapshot.Lora] counters alone, read now. */
    fun lora(): Snapshot.Lora =
        Snapshot.Lora(
            loraSent = loraSent.get(),
            loraFragSent = loraFragSent.get(),
            loraTranscoded = loraTranscoded.get(),
            loraPadded = loraPadded.get(),
            loraReceived = loraReceived.get(),
            loraReassembled = loraReassembled.get(),
            loraTooBig = loraTooBig.get(),
            loraDroppedQueue = loraDroppedQueue.get(),
            loraAirtimeHeld = loraAirtimeHeld.get(),
            loraBandBraked = loraBandBraked.get(),
            loraSuppressed = loraSuppressed.get(),
            loraNak = loraNak.get(),
            loraNakByReason = loraNakByReason.mapValues { it.value.get() },
            loraSessionUps = loraSessionUps.get(),
            loraDmReceived = loraDmReceived.get(),
            loraReprobed = loraReprobed.get(),
            loraRoomTooLong = loraRoomTooLong.get(),
            loraRetiredCtl = loraRetiredCtl.get(),
            loraPreIdRefused = loraPreIdRefused.get(),
            loraBoardHoldersFailed = loraBoardHoldersFailed.get(),
            loraSkippedLinked = loraSkippedLinked.get(),
            loraSkippedInternet = loraSkippedInternet.get(),
            loraStaleAtSend = loraStaleAtSend.get(),
            loraStaleAtSendByReason = loraStaleAtSendByReason.mapValues { it.value.get() },
            loraTickDeferred = loraTickDeferred.get(),
            loraDirectSent = loraDirectSent.get(),
            loraDirectAcked = loraDirectAcked.get(),
            loraDirectImplicitAck = loraDirectImplicitAck.get(),
            loraDirectUnreachable = loraDirectUnreachable.get(),
            loraDirectNoOutcome = loraDirectNoOutcome.get(),
            loraDirectHeld = loraDirectHeld.get(),
            loraDirectTooLong = loraDirectTooLong.get(),
            loraDirectSlotZero = loraDirectSlotZero.get(),
            loraDirectStranger = loraDirectStranger.get(),
            loraDirectAuthFailed = loraDirectAuthFailed.get(),
            loraDirectClockRefused = loraDirectClockRefused.get(),
            loraDirectDelivered = loraDirectDelivered.get(),
            loraDirectResent = loraDirectResent.get(),
            loraDirectNoKey = loraDirectNoKey.get(),
            loraDirectContactAdded = loraDirectContactAdded.get(),
            loraDirectPeerLacksOurKey = loraDirectPeerLacksOurKey.get(),
            loraDirectBoardKeyConflict = loraDirectBoardKeyConflict.get(),
        )

    /** The [Snapshot.Meshtastic] counters alone, read now. */
    fun meshtastic(): Snapshot.Meshtastic =
        Snapshot.Meshtastic(
            meshPostHeard = meshPostHeard.get(),
            meshPostIngested = meshPostIngested.get(),
            meshPostViaMqtt = meshPostViaMqtt.get(),
            meshPostMatched = meshPostMatched.get(),
            meshPostVerified = meshPostVerified.get(),
            meshPostBoardVerified = meshPostBoardVerified.get(),
            meshPostSignatureMismatch = meshPostSignatureMismatch.get(),
            meshPostRefusedByReason = meshPostRefusedByReason.mapValues { it.value.get() },
            publicPostSent = publicPostSent.get(),
            publicPostRefusedByReason = publicPostRefusedByReason.mapValues { it.value.get() },
            autoReplyHeard = autoReplyHeard.get(),
            autoReplySent = autoReplySent.get(),
            autoReplyRefusedByReason = autoReplyRefusedByReason.mapValues { it.value.get() },
        )

    /**
     * Every counter, read at once, in groups. The groups are load-bearing: a data class's synthetic default
     * constructor takes two JVM parameter slots for each `Long`, and a method may take at most 255. A flat snapshot
     * reached that at 129 counters, and one more fails at class load (`ClassFormatError: Too many arguments in method
     * signature`), not at compile time, crashing every test that touches the class. A new counter goes into its group,
     * and `MeshMetricsTest` fails a group that nears the limit. A counter keeps its full name inside its group, so
     * `snapshot().lora.loraSent` reads the same as the debug bridge's `loraSent` key.
     */
    data class Snapshot(
        val frames: Frames = Frames(),
        val keys: Keys = Keys(),
        val seals: Seals = Seals(),
        val receipts: Receipts = Receipts(),
        val groups: Groups = Groups(),
        val files: Files = Files(),
        val bluetooth: Bluetooth = Bluetooth(),
        val nan: Nan = Nan(),
        val fast: FastPath = FastPath(),
        val spool: Spool = Spool(),
        val lora: Lora = Lora(),
        val meshtastic: Meshtastic = Meshtastic(),
    ) {
        /** What the router did with frames — authored, delivered, relayed, handed on, suppressed, deduped, dropped
         * (by [DropReason]) — the bytes it put on the wire, and the sync rounds around them: heal passes, digests a
         * newer one replaced in a link's queue, frames custody refused for their size, profiles refused for theirs, and
         * session-loop rounds that threw (by [MeshRound]). */
        data class Frames(
            val framesOriginated: Long = 0,
            val framesDelivered: Long = 0,
            val framesRelayed: Long = 0,
            val framesHandedOn: Long = 0,
            val framesSuppressed: Long = 0,
            val framesDeduped: Long = 0,
            val bytesSent: Long = 0,
            val framesDropped: Long = 0,
            val dropsByReason: Map<DropReason, Long> = emptyMap(),
            val digestsReplaced: Long = 0,
            // #227: a frame custody would not carry because its `signed` bytes are over the byte cap.
            val custodyOversizeRefused: Long = 0,
            // #234: a profile refused at the door or by custody because its `signed` bytes are over the profile cap.
            val profileOversizeRefused: Long = 0,
            // ADR 2026-11.264w: links registered as proven, failed proofs, and unproven claims refused or closed.
            val linksProven: Long = 0,
            val linkProofFailed: Long = 0,
            val linkClaimRefused: Long = 0,
            val healsCompleted: Long = 0,
            val roundsFailedBy: Map<MeshRound, Long> = emptyMap(),
        )

        /**
         * Recovering a sender's missing key: key requests, keys served and recovered, frames parked for a key and
         * replayed, and the contact-card intros ([IntroSync]).
         */
        data class Keys(
            val keyRequestsSent: Long = 0,
            val keysServed: Long = 0,
            val keysRecovered: Long = 0,
            val framesHeld: Long = 0,
            val framesReplayed: Long = 0,
            val introsSent: Long = 0,
            val introsAnswered: Long = 0,
            // ADR 2026-10.4mjm: a race loser moved back onto its own init, which the peer had answered.
            val ratchetSwappedBack: Long = 0,
            // ADR 2026-11.xp6d: presence keys sent and stored, eras replaced, keys left out of the listing, our own
            // rotations, a restored generation clamped, and a restore's resets sent over a link.
            val presenceKeysSent: Long = 0,
            val presenceKeysStored: Long = 0,
            val presenceKeysReplaced: Long = 0,
            val presenceKeysExcluded: Long = 0,
            val presenceKeyRotations: Long = 0,
            val presenceKeyGenerationClamped: Long = 0,
            val linkOwedResetsSent: Long = 0,
            val linkOwedResetsDeclined: Long = 0,
            val linkOwedResetsSuperseded: Long = 0,
            val presenceLinkPassesScheduled: Long = 0,
            val presenceLinkPassesCompleted: Long = 0,
        )

        /** How DMs and reactions were sealed, and how many fell back to an older form. */
        data class Seals(
            val dmSealedV2: Long = 0,
            val dmSealedV3: Long = 0,
            val dmSealedV1Fallback: Long = 0,
            val reactionsSealed: Long = 0,
            val reactionsSealedFallback: Long = 0,
        )

        /**
         * Delivery ticks: sealed or sent cleartext, custodied, coalesced, ridden, spooled, re-sent, and the unsigned
         * live-link form.
         */
        data class Receipts(
            val receiptsResent: Long = 0,
            val receiptsSealed: Long = 0,
            val receiptsSealedFallback: Long = 0,
            val receiptsReAckSkipped: Long = 0,
            val receiptsCustodied: Long = 0,
            val receiptsCoalesced: Long = 0,
            val receiptsRidden: Long = 0,
            val ridesUnrouted: Long = 0,
            val receiptsHeld: Long = 0,
            val receiptsSpooled: Long = 0,
            val ticksUnsigned: Long = 0,
        )

        /** Group messages, the seeds their keys travel in, key requests, and group roots. */
        data class Groups(
            val groupSealedRatchet: Long = 0,
            val groupSealedV1Fallback: Long = 0,
            val groupSeedsSent: Long = 0,
            val groupSeedsAdopted: Long = 0,
            val groupSeedsHeld: Long = 0,
            val groupSeedsReplayed: Long = 0,
            val groupKeyRequestsSent: Long = 0,
            val groupRootsMinted: Long = 0,
            val groupRootsAdopted: Long = 0,
            // ADR 2026-10.7mqm: a group frame custody would not carry (a roster that does not derive, a non-member).
            val groupCustodyRefused: Long = 0,
        )

        /** Blobs over the radio links: files sent on each plane, blob asks handled, and cut transfers resumed (#116). */
        data class Files(
            val filesSentNan: Long = 0,
            val filesSentBt: Long = 0,
            val blobAsksHandled: Long = 0,
            val carrierPullsDeferred: Long = 0,
            // #282: a message request's attachment left unfetched until the request is accepted (`RequestHold`).
            val requestHolds: Long = 0,
            // #282: the acceptance collector's re-arms that returned, and carried pulls skipped for the carrier budget.
            val acceptanceRearms: Long = 0,
            val carrierPullsOverBudget: Long = 0,
            val filesResumedOut: Long = 0,
            val filesResumedIn: Long = 0,
            val splicesRefused: Long = 0,
            val blobQueriesSent: Long = 0,
            val blobQueriesAnswered: Long = 0,
            val blobQueriesRefused: Long = 0,
            val blobAnswersTaken: Long = 0,
            val blobAnswersIgnored: Long = 0,
            val blobFetches: Long = 0,
            val blobConfirms: Long = 0,
            val blobFallbacks: Long = 0,
            val blobBlindAsks: Long = 0,
            val blobPullThroughs: Long = 0,
            val blobAsksParked: Long = 0,
            // Progress reports sent back to a file's sender, and taken from a file's receiver (ADR 2026-10.nsr7).
            val blobProgressSent: Long = 0,
            val blobProgressTaken: Long = 0,
            // Asks withdrawn by a `blobcancel`, and withdrawals that dropped a parked ask or a queued copy (ADR 2026-10.nsr7).
            val blobCancelsSent: Long = 0,
            val blobCancelsTaken: Long = 0,
            // ADR 2026-10.js2p: a fast-only want's step that found no neighbour reachable over Wi-Fi Aware and asked
            // nobody, a fast-only ask this holder refused for want of a Wi-Fi Aware route to its asker, and a fast-only
            // copy the composite gave up on rather than hand to Bluetooth.
            val blobFastOnlyHeld: Long = 0,
            val blobFastOnlyRefused: Long = 0,
            // The proof that a DM photo's recipient holds its bytes (ADR 2026-10.vy27): holder queries sent for a
            // photo of ours, answers to them taken, and the hashes they proved held.
            val blobProofQueriesSent: Long = 0,
            val blobProofAnswersTaken: Long = 0,
            val blobProofsHeld: Long = 0,
            val fastOnlyDropped: Long = 0,
            // #195: a cycle that asked nobody to pull a blob through — past its deadline, or with no budget left — and
            // an ask for a blob this phone lacks that made no want here, for the same two reasons.
            val blobPullsWithheld: Long = 0,
            val blobEchoesDeclined: Long = 0,
            // #234: a want made only for a profile photo — a holder passed over because its stream or its size passed
            // the photo ceiling, and a want ended because its author's did or a held copy did.
            val blobHoldersOverCeiling: Long = 0,
            val blobOverCeiling: Long = 0,
            // #234: a holder passed over on a photo-only want for a stream or a size past the photo ceiling; knit-ios's
            // name and meaning. And a photo refused from bytes held over it ([onAvatarOverCeiling]), Android's own.
            val photosOverCeiling: Long = 0,
            val avatarsOverCeiling: Long = 0,
            // ADR 2026-10.a5yj: an ask over a contact's direct link for a blob the link does not carry, neither served
            // nor echoed.
            val blobLinkRefused: Long = 0,
        )

        /**
         * The BLE links — connect failures by [ConnectFailReason], links made, Coded PHY steps — and the side
         * channel's pages ([BleSideDrop]).
         */
        data class Bluetooth(
            val btConnectFails: Long = 0,
            val btConnectFailsByReason: Map<ConnectFailReason, Long> = emptyMap(),
            val btLinksEstablished: Long = 0,
            val bleLinkDupSkipped: Long = 0,
            val bleCodedSightings: Long = 0,
            val bleCodedDials: Long = 0,
            val blePhyStepsDown: Long = 0,
            val blePhyStepsUp: Long = 0,
            val blePhyGiveUps: Long = 0,
            val bleCodedAdvertDark: Long = 0,
            val bleSideOffered: Long = 0,
            val bleSidePartsAired: Long = 0,
            val bleSideTooBig: Long = 0,
            val bleSideHeard: Long = 0,
            val bleSideReassembled: Long = 0,
            val bleSideDeduped: Long = 0,
            val bleSideDropsByReason: Map<BleSideDrop, Long> = emptyMap(),
            // ADR 2026-11.xp6d: blinded adverts, GATT payloads and TAG_NEXT records dropped, and held-link refusals.
            val bleBlindDropsByReason: Map<BleBlindDrop, Long> = emptyMap(),
        )

        /** Wi-Fi Aware: serves, owed links, refused accepts, the message plane, and bulk-preference timeouts. */
        data class Nan(
            val nanServesPeak: Long = 0,
            val nanOwedNoLinkPeakMs: Long = 0,
            val nanAcceptsRefused: Long = 0,
            val nanIcmKeepaliveFailed: Long = 0,
            val nanOffScreenRefusals: Long = 0,
            val nanMsgsAcked: Long = 0,
            val nanMsgSendsFailed: Long = 0,
            val nanMsgPlaneStalledPeakMs: Long = 0,
            val nanMsgPlaneCycles: Long = 0,
            val nanBulkGraceTimeouts: Long = 0,
        )

        /**
         * The fast plane: frames sent compact, legacy, fragmented or transcoded, reassembled, too big, and dropped
         * ([FastPathDrop]).
         */
        data class FastPath(
            val fastCompactSent: Long = 0,
            val fastLegacySent: Long = 0,
            val fastFragSent: Long = 0,
            val fastReassembled: Long = 0,
            val fastTooBig: Long = 0,
            val fastTranscodedSent: Long = 0,
            val transcodeFallbacks: Long = 0,
            val fastDropsByReason: Map<FastPathDrop, Long> = emptyMap(),
        )

        /** The Internet (spool) plane. */
        data class Spool(
            val spoolTablesDerived: Long = 0,
            val spoolPushed: Long = 0,
            val spoolPulled: Long = 0,
            val spoolBridged: Long = 0,
            val spoolInvalid: Long = 0,
            val spoolAccounted: Long = 0,
            val spoolErrors: Long = 0,
            val spoolAttachPushed: Long = 0,
            val spoolAttachPulled: Long = 0,
            val spoolAttachDeferred: Long = 0,
            val spoolAttachProved: Long = 0,
            val spoolAttachRefused: Long = 0,
            val spoolAttachLocked: Long = 0,
        )

        /** The LoRa plane. */
        data class Lora(
            val loraSent: Long = 0,
            val loraFragSent: Long = 0,
            val loraTranscoded: Long = 0,
            val loraPadded: Long = 0,
            val loraReceived: Long = 0,
            val loraReassembled: Long = 0,
            val loraTooBig: Long = 0,
            val loraDroppedQueue: Long = 0,
            val loraAirtimeHeld: Long = 0,
            val loraBandBraked: Long = 0,
            val loraSuppressed: Long = 0,
            val loraNak: Long = 0,
            val loraNakByReason: Map<String, Long> = emptyMap(),
            val loraSessionUps: Long = 0,
            val loraDmReceived: Long = 0,
            val loraReprobed: Long = 0,
            val loraRoomTooLong: Long = 0,
            val loraRetiredCtl: Long = 0,
            val loraPreIdRefused: Long = 0,
            val loraBoardHoldersFailed: Long = 0,
            val loraSkippedLinked: Long = 0,
            val loraSkippedInternet: Long = 0,
            val loraStaleAtSend: Long = 0,
            val loraStaleAtSendByReason: Map<String, Long> = emptyMap(),
            val loraTickDeferred: Long = 0,
            val loraDirectSent: Long = 0,
            val loraDirectAcked: Long = 0,
            val loraDirectImplicitAck: Long = 0,
            val loraDirectUnreachable: Long = 0,
            val loraDirectNoOutcome: Long = 0,
            val loraDirectHeld: Long = 0,
            val loraDirectTooLong: Long = 0,
            val loraDirectSlotZero: Long = 0,
            val loraDirectStranger: Long = 0,
            val loraDirectAuthFailed: Long = 0,
            val loraDirectClockRefused: Long = 0,
            val loraDirectDelivered: Long = 0,
            val loraDirectResent: Long = 0,
            val loraDirectNoKey: Long = 0,
            val loraDirectContactAdded: Long = 0,
            val loraDirectPeerLacksOurKey: Long = 0,
            val loraDirectBoardKeyConflict: Long = 0,
        )

        /**
         * The Meshtastic room: posts heard and ingested, what their signatures proved, posts sent, and the DM
         * auto-reply.
         */
        data class Meshtastic(
            val meshPostHeard: Long = 0,
            val meshPostIngested: Long = 0,
            val meshPostViaMqtt: Long = 0,
            val meshPostMatched: Long = 0,
            val meshPostVerified: Long = 0,
            val meshPostBoardVerified: Long = 0,
            val meshPostSignatureMismatch: Long = 0,
            val meshPostRefusedByReason: Map<String, Long> = emptyMap(),
            val publicPostSent: Long = 0,
            val publicPostRefusedByReason: Map<String, Long> = emptyMap(),
            val autoReplyHeard: Long = 0,
            val autoReplySent: Long = 0,
            val autoReplyRefusedByReason: Map<String, Long> = emptyMap(),
        )
    }
}
