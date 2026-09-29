package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.protocol.isStorable

/**
 * Store-and-forward custody for chat messages — 1:1 DMs, group messages, and the broadcast room. The
 * mesh floods a frame once and drops it, so a message whose recipient (or a path to them) isn't
 * connected at that instant never arrives. [ForwardSync] persists the messages a node originates or
 * relays ([onSeen]) and, when a neighbor joins ([onNeighborAdded]), unicasts the carried ones to it:
 *
 * - **DMs** are offered to any newcomer: if it's the recipient it delivers + acks; otherwise its normal
 *   relay floods the frame onward through the new topology. A cleartext delivery receipt from the addressed
 *   recipient purges the carried copy mesh-wide ([onAck]), and a DM that lands after its own receipt is
 *   refused on arrival ([onSeen]).
 * - **Group messages** are offered only to a roster member (the member set rides in cleartext on the
 *   frame); once any member receives it, the normal flood re-distributes it to the rest. A group has no
 *   single recipient and no reliable per-member ack, so it is never vaccine-purged — the TTL/cap sweep
 *   is its only bound.
 * - **Broadcast-room** messages are offered to every newcomer (no destination to target), so two phones
 *   that meet only briefly backfill each other's ambient history. Like a group they have no ack, so the
 *   (shorter) TTL/cap sweep is their only bound.
 *
 * Pure (no Android/Room): the transport and store are injected and authentication is a lambda, so the
 * whole flow is unit-testable with [FakeLoopTransport] and a fake [ForwardStore].
 */
class ForwardSync(
    private val transport: MeshTransport,
    private val store: ForwardStore,
    private val clock: () -> Long = { System.currentTimeMillis() },
    // Authenticates a relayed frame before we carry it (sender pinned + signature valid — never the block
    // list, ADR 2026-09.bts9), so a node never stores unauthenticated junk. Our own sends skip this
    // (trivially authentic).
    private val authenticate: suspend (WireEnvelope, RelayEnvelope) -> Boolean = { _, _ -> true },
    // Invoked once when a frame is actually persisted, so the orchestrator can custody any out-of-band blob it
    // references (an image): the frame carries only a content hash, so the carrier eager-pulls + holds the bytes
    // keyed to the frame's lifetime. Defaulted to a no-op so the pure tests and non-blob call sites are unaffected.
    private val onCarried: suspend (RelayEnvelope) -> Unit = {},
    // Invoked once per carried frame [onDigest] actually sends, with the peer it went to — the fact behind the
    // Your mesh screen's "passed along" / "handed straight to" numbers (`ContributionLedger` decides whether
    // it counts). Defaulted to a no-op so the pure tests are unaffected.
    private val onServed: suspend (RelayEnvelope, toNodeId: String) -> Unit = { _, _ -> },
    // Hands a peer the key of a sender (their signed profile, point to point) if one is held — `KeyExchange.serveKey`,
    // over the pin store (ADR 2026-09.g64k). Defaulted to a no-op so the pure tests are unaffected.
    private val serveKey: suspend (senderId: String, to: Peer) -> Unit = { _, _ -> },
) {
    // Ids of DMs purged by a delivery receipt: a short-lived tombstone (≤ carry TTL) so a still-
    // circulating copy from an unvaccinated peer isn't re-stored after we've already delivered it.
    private val acked = SeenSet(ttlMillis = ACK_TOMBSTONE_TTL_MS, clock = clock)

    // Receipts that named a DM we did not hold yet, keyed (ackId, acker): the DM that lands after its own receipt
    // — a back-fill served newest first, or the recipient's relay behind its instant receipt — is vaccinated on
    // arrival instead of carried to expiry, so custody doesn't depend on the order the two arrived in (#100).
    private val earlyAcks = SeenSet(ttlMillis = ACK_TOMBSTONE_TTL_MS, clock = clock)

    /**
     * Capture a chat frame we originated ([ForwardStore.ORIGIN_SELF]) or relayed
     * ([ForwardStore.ORIGIN_RELAY]) into the carry store — a DM, group, or broadcast-room message (all
     * [isStorable]). No-op for a non-chat frame, an already-carried/already-acked id, or a relayed frame
     * that fails authentication. Only the immutable signed blob + signature are stored — a fresh wrapper
     * is stamped on re-serve, so the frame re-floods with a full hop budget (the signature covers neither
     * ttl nor hops).
     */
    suspend fun onSeen(
        wire: WireEnvelope,
        envelope: RelayEnvelope,
        origin: Int,
    ) {
        if (!envelope.isStorable()) return
        if (acked.contains(envelope.id) || store.has(envelope.id)) return
        // Its receipt came first ([onAck]): the purge we could not apply then applies now, on the same
        // recipient-authenticated terms, and the id is tombstoned exactly as a purge would leave it.
        val recipient = envelope.recipientId
        if (recipient != null && earlyAcks.contains(earlyAckKey(envelope.id, recipient))) {
            acked.add(envelope.id)
            return
        }
        if (origin == ForwardStore.ORIGIN_RELAY && !authenticate(wire, envelope)) return
        // The store impl folds the new id into the StoreDigest, whose version change re-cues neighbors that
        // we now hold something they may want to pull. A dead-on-arrival frame (past its frame-global expiry —
        // a skewed-clock peer re-serving what everyone has swept) is refused, so we must not custody its blob.
        if (!store.store(CarriedFrame(envelope, wire.sig, wire.signed), origin, clock())) return
        // Asked again once the row is written: a receipt [onAck] handled while the write was in flight missed the
        // row, and the check above missed its memo. Each side writes before it reads, so one of them sees the other.
        if (recipient != null && earlyAcks.contains(earlyAckKey(envelope.id, recipient))) {
            store.remove(envelope.id)
            acked.add(envelope.id)
            return
        }
        // Now custody any blob the frame references (e.g. an image), so a late joiner can still pull it from us.
        onCarried(envelope)
    }

    /**
     * A neighbor joined: **advertise the ids we hold** so it replies (over [MeshTransport.incomingDigests] →
     * [onDigest]) with only the frames we lack — and it does the same to us. A sync then transfers the set
     * *difference*, not the whole store (the data-path id-diff; see `docs/DIGEST_PULL_REATTACH.md`).
     *
     * Re-advertises on **every** join, deliberately. The digest cue plane brings a data-path link up only when
     * the two stores differ ([DigestTracker.reconcileWanted]'s identical-skip), so a link forming at all means
     * there is something to reconcile — and re-advertising is what lets an offer lost to an ephemeral link's
     * teardown self-heal on the *next* contact (seconds) rather than stalling for a timer. It is now cheap: the
     * peer's reply is only the diff, and a duplicate that did land is dropped by the receiver's SeenSet.
     */
    suspend fun onNeighborAdded(peer: Peer) {
        transport.sendDigest(peer, store.liveIds(clock()))
    }

    /**
     * A neighbor advertised the custody ids it holds ([theirIds]): unicast it every carried frame **it lacks** —
     * the set difference that replaces pushing the whole store. Targeting: offer a group message only to a roster
     * member (a DM/broadcast goes to any newcomer). We DO re-serve a peer frames it authored when the diff shows
     * it no longer holds them (custody wiped with the DB, e.g. the DatabaseKey unrecoverable-wrap path) — that is how a node's own sends
     * reconverge, and it authenticates them against its own identity bundle ([MeshManager]). Each frame is
     * re-wrapped in a fresh [WireEnvelope] (full ttl, hops 0) around its verbatim signed blob; a duplicate that
     * races in is still dropped by the receiver's SeenSet, so a stale digest only ever costs bytes, never
     * correctness.
     *
     * **Profiles go first** (ADR 2026-09.9xuu). The store reads newest-received first, which put a sender's
     * profile — usually the oldest row we hold of theirs — behind all of their chat, so a peer meeting that
     * sender for the first time refused the whole backlog for want of a key it was about to be handed. It parks
     * what it refuses ([PendingInbound]) and replays it on the pin, but a park is a bound, and anything it turns
     * away is deduped for the router's ten-minute window: the key first is what makes the park the exception.
     *
     * **And when custody holds no profile, the pin's does** (ADR 2026-09.g64k). The per-sender quota evicts a
     * chatty sender's profile, and the TTL takes a departed sender's up to a republish period before their last
     * posts, so a carrier often holds frames whose author's profile it no longer holds. It still pinned their
     * key to carry them, and it keeps the signed frame that pin came from: [keylessSenders] names each sender
     * this reply would hand the peer a backlog it has no key for, and their key goes first, point to point.
     */
    suspend fun onDigest(
        fromNodeId: String,
        theirIds: List<String>,
    ) {
        val peer = transport.neighbors.value.find { it.nodeId == fromNodeId } ?: Peer(fromNodeId)
        val have = theirIds.toHashSet()
        val live = store.liveFrames(clock()).sortedBy { it.envelope.type != FrameType.PROFILE }
        val outgoing =
            live.filter { carried ->
                val env = carried.envelope
                // The diff: skip frames the peer already holds. No author-skip: the `have` diff already elides
                // any frame the peer still holds, so the only peer-authored frame reaching here is one it authored
                // but no longer has — its custody wiped by a DB wipe. Re-serving it is exactly how a node
                // re-carries its own sends so the content digest reconverges; it authenticates the frame against
                // its own identity bundle (see MeshManager.verifierBundle). The old `senderId == fromNodeId` skip
                // assumed the author always has its own frame, which a wipe breaks — leaving each node permanently
                // short its own sends.
                val members = env.group?.members
                env.id !in have && (members == null || fromNodeId in members) // group: members only (DM/broadcast: anyone)
            }
        keylessSenders(live, outgoing, have, fromNodeId).forEach { serveKey(it, peer) }
        outgoing.forEach { carried ->
            transport.send(WireEnvelope(sig = carried.sig, signed = carried.signed), peer)
            onServed(carried.envelope, fromNodeId)
        }
    }

    /**
     * The senders of [outgoing] whose key the peer [fromNodeId] may not hold and this reply does not carry: not
     * the peer itself, no live `profile` of theirs in our custody (that goes first already, or the peer holds it
     * — the diff skipped it), and none of our live frames of theirs in the peer's digest ([have]). A peer that
     * custodies a frame verified it, so it holds that sender's key; the last rule is what keeps two carriers of
     * the same chatty sender from trading their key on every reconcile.
     */
    private fun keylessSenders(
        live: List<CarriedFrame>,
        outgoing: List<CarriedFrame>,
        have: Set<String>,
        fromNodeId: String,
    ): List<String> {
        val known = HashSet<String>()
        live.forEach { carried ->
            val env = carried.envelope
            if (env.type == FrameType.PROFILE || env.id in have) known += env.senderId
        }
        return outgoing
            .asSequence()
            .map { it.envelope.senderId }
            .filter { it != fromNodeId && it !in known }
            .distinct()
            .toList()
    }

    /**
     * A delivery receipt arrived ([ackId] acked by [senderId]): drop the carried DM it acks — but only if
     * [senderId] is that DM's addressed recipient (the caller has already verified the receipt's
     * signature). This makes the purge recipient-authenticated, so a forged receipt can't evict an
     * undelivered message. The id is tombstoned so a copy still circulating from an unvaccinated peer
     * isn't re-accepted by [onSeen].
     *
     * Every receipt is also remembered with its acker for the tombstone's lifetime, for the one that names a
     * frame we don't hold yet: the router marks it seen, so it is never applied again, and the DM behind it would
     * otherwise be carried to expiry here while every neighbour that heard the two the other way round refuses it
     * (#100). [onSeen] drops that DM on arrival if its recipient is the acker. The DM's own expiry always ends
     * first (ADR 2026-09.adpz).
     */
    suspend fun onAck(
        ackId: String,
        senderId: String,
    ) {
        // Remembered before the store is read, whether or not the DM is held: an [onSeen] of it racing on another
        // dispatcher then finds either this memo or a row this purge removes. A memo that never matches (a held
        // group or room frame, a non-recipient acker) costs one slot in a bounded set.
        earlyAcks.add(earlyAckKey(ackId, senderId))
        if (store.recipientOf(ackId) != senderId) return
        store.remove(ackId)
        acked.add(ackId)
    }

    /** Reclaims carried DMs whose TTL has elapsed (startup, periodic, and heartbeat sweeps). */
    suspend fun sweepExpired() {
        store.sweepExpired(clock())
    }

    private fun earlyAckKey(
        ackId: String,
        ackerId: String,
    ): String = "$ackId|$ackerId"

    private companion object {
        /** How long a delivered id stays tombstoned — matches the carry TTL so it can't outlive a copy. */
        const val ACK_TOMBSTONE_TTL_MS = 24 * 60 * 60_000L
    }
}
