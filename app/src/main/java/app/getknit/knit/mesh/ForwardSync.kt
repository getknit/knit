package app.getknit.knit.mesh

import app.getknit.knit.data.forward.CapEviction
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.protocol.isStorable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

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
 * - **Group messages** are offered to any newcomer that lacks them, member or not, as a room post is (ADR
 *   2026-10.hr9f). Member-only offers left a non-member that missed a member's posts live with a smaller bucket
 *   for that member, so it kept the older frames every member's quota had evicted and re-served them at every
 *   exchange, each evicted again in the write that stored it (#176, #235). A group has no single recipient and
 *   no reliable per-member ack, so it is never vaccine-purged — the TTL/cap sweep is its only bound.
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
    // keyed to the frame's lifetime, asking the neighbour the frame came from (null for our own) among the likeliest
    // holders (ADR 2026-10.f7gg). Defaulted to a no-op so the pure tests and non-blob call sites are unaffected.
    private val onCarried: suspend (RelayEnvelope, String?) -> Unit = { _, _ -> },
    // Invoked once per carried frame [onDigest] actually sends, with the peer it went to — the fact behind the
    // Your mesh screen's "passed along" / "handed straight to" numbers (`ContributionLedger` decides whether
    // it counts). Defaulted to a no-op so the pure tests are unaffected.
    private val onServed: suspend (RelayEnvelope, toNodeId: String) -> Unit = { _, _ -> },
    // A sender's key as it would go to a peer (their signed profile, point to point), or null if none is held —
    // `KeyExchange.keyFor`, over the pin store (ADR 2026-09.g64k): read before the round's budget is checked, since a key
    // ahead is charged to its round (the custody-carry-policy contract's §1). Defaulted to none for the pure tests.
    private val keyFor: suspend (senderId: String) -> WireEnvelope? = { null },
    // Sends one key [keyFor] answered — `KeyExchange.sendKey`, which counts it served.
    private val sendKey: suspend (key: WireEnvelope, to: Peer) -> Unit = { key, to -> transport.send(key, to) },
    // Who holds what, and what each proven peer was released and did not keep (the custody-carry-policy contract's §3 and
    // §6): one per process, the same instance the repository reads `spread` from. A fresh one by default, for the rigs.
    private val carry: CarryLedger = CarryLedger(),
    // The round each carrying plane's answers are cut to (§2): the contract's table in production, unpaced in the lab's
    // scenarios that are not about the pace.
    private val pace: CustodyPace = CustodyPace.CONTRACT,
    // Counts the rounds, the answers cut short and the re-offers held (§8); null in the rigs that do not read them.
    private val metrics: MeshMetrics? = null,
    // A digest dropped because custody's index could not be read: the caller logs it. The answer under way stays as it was.
    private val onDigestDropped: (Exception) -> Unit = {},
    // One peer's round that threw in [serveDue]: the caller logs and counts it. That peer's next round is an interval on.
    private val onRoundFailed: (Exception) -> Unit = {},
    // Whether the tick's custody offer backs off while offers draw nothing (§7). Always in production; off in the lab's
    // scenarios that are not about the back-off, whose short real-time ticks would otherwise sit out its 30 s tolerance.
    private val reofferBackoff: Boolean = true,
) {
    // Ids of DMs purged by a delivery receipt: a short-lived tombstone (≤ carry TTL) so a still-
    // circulating copy from an unvaccinated peer isn't re-stored after we've already delivered it.
    private val acked = SeenSet(ttlMillis = ACK_TOMBSTONE_TTL_MS, clock = clock)

    // Receipts that named a DM we did not hold yet, keyed (ackId, acker): the DM that lands after its own receipt
    // — a back-fill served newest first, or the recipient's relay behind its instant receipt — is vaccinated on
    // arrival instead of carried to expiry, so custody doesn't depend on the order the two arrived in (#100).
    private val earlyAcks = SeenSet(ttlMillis = ACK_TOMBSTONE_TTL_MS, clock = clock)

    // Whether a plane still holds the link a release went over: a pending release ends with its own link (§6).
    private val links = CarryLedger.Links { plane, peer, link -> transport.carries(plane, peer, link) }

    /**
     * One peer's paced answer (§2): cut fresh from custody's index each round, from the ids its latest digest lists that
     * we hold. Every set is trimmed to custody's live ids at every cut, so an answer a peer keeps alive with a digest a
     * minute remembers no more than custody holds, however long it lives. Fields under [lock]; cut under [serving].
     */
    private class Answer(
        var link: Boolean,
        var proven: Boolean,
        var digestAt: Long,
        var dueAt: Long,
    ) {
        // The peer's latest digest ∩ the ids held then.
        val digest = HashSet<String>()

        // What this answer released and when, over which plane: remembered only until a digest can speak to it (contract
        // revision 7, §2: [forgetJudged]). The senders whose key it sent, what arrived from the peer since its digest, and
        // the candidates a direct link's view refused or that never loaded, read once.
        val released = HashMap<String, Released>()
        val keysHanded = HashSet<String>()
        val arrived = HashSet<String>()
        val refused = HashSet<String>()

        // The link and plane the last round went over: a different link on a plane that relinks is the peer linking anew,
        // and what went over the old one may have died with it; the plane says whose path a cut answer ends (§6).
        var carrierLink: Any? = null
        var carrierKind: TransportKind? = null

        // The ledger's Aware cut mark this answer last read ([CarryLedger.awareCutMark]): a higher one is a path cut with
        // the answer live, and the releases it carried are owed again.
        var awareCutSeen = 0L

        // A digest arrived at [now]: every release at least the judging delay of its plane old is forgotten, so the digest
        // and the memo decide again: listed, it is not owed; addressed to the peer or unproven, it goes again; any other
        // waits out its mark (§2, §6, revision 7).
        fun forgetJudged(now: Long) {
            released.values.removeAll { now - it.at >= CarryLedger.judgeAfterMs(it.plane) }
        }

        fun trim(
            held: Set<String>,
            senders: Set<String>,
        ) {
            digest.retainAll(held)
            released.keys.retainAll(held)
            arrived.retainAll(held)
            refused.retainAll(held)
            keysHanded.retainAll(senders)
        }
    }

    /** When an answer released a frame, and over which plane: what a later digest's judging delay is read against. */
    private class Released(
        val at: Long,
        val plane: TransportKind,
    )

    /** A round's releases to [peer], credited as handed one interval of its plane later if [link] still carries it (§3). */
    private class Credit(
        val peer: String,
        val ids: List<String>,
        val kind: TransportKind,
        val link: Any?,
        val at: Long,
    )

    /** One neighbour's re-offer back-off (§7): when we last offered, the wait before the next, and whether it drew since. */
    private class Offer(
        var lastAt: Long,
        var intervalMs: Long,
        var drew: Boolean = false,
    )

    private val lock = Any()

    // One round cut at a time: the digest collector's first round and the serve loop's later ones touch the same answers.
    private val serving = Mutex()

    // Its keys mirrored, under [lock], into the ledger's [CarryLedger.answering]: what a plane reads lock-free to hold a link
    // not idle while an answer it carries is live ([CustodyProgress.answerCarrier]).
    private val answers = HashMap<String, Answer>()
    private val credits = ArrayList<Credit>()

    // Bounded by the neighbours: created at a link-up, forgotten when the tick no longer sees the peer ([reoffer]).
    private val offers = HashMap<String, Offer>()

    // A digest arrived: the serve loop re-reads when the next round is due.
    private val wakes = Channel<Unit>(Channel.CONFLATED)

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
        // The neighbour a relayed frame came from, handed to [onCarried]; null for our own.
        from: String? = null,
        // [from]'s link proved its node id ([InboundFrame.proven]): only then is it the frame's source in custody's carry
        // ledger (the custody-carry-policy contract's §3). A LoRa packet, a side-channel page and a spool pull never are.
        fromProven: Boolean = false,
        // It came in on [from]'s link ([InboundFrame.onLink]): kept, it is not served back to [from] by an answer under way
        // (§2), and it says our last offer to [from] drew something (§7). A replay off the key-wait park never is: the hop
        // it came over was judged when it was parked, and the park is not the link.
        fromLink: Boolean = false,
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
        // we now hold something they may want to pull. A frame the store refused (over the byte cap, ADR 2026-10.mqzv;
        // dead on arrival — a skewed-clock peer re-serving what everyone has swept — or a group frame whose roster is not
        // its own) or evicted in its own
        // write (the quotas keep it nowhere, ADR 2026-10.7mqm) is one we do not hold, so we must not custody its blob.
        val source = from?.takeIf { fromProven }
        if (!store.store(CarriedFrame(envelope, wire.sig, wire.signed), origin, clock(), source)) return
        // Asked again once the row is written: a receipt [onAck] handled while the write was in flight missed the
        // row, and the check above missed its memo. Each side writes before it reads, so one of them sees the other.
        if (recipient != null && earlyAcks.contains(earlyAckKey(envelope.id, recipient))) {
            store.remove(envelope.id)
            acked.add(envelope.id)
            return
        }
        // Tier 3 of the serve order reads the frame's own roster, read here once and never again per answer (§1).
        envelope.group?.let { carry.noteRoster(envelope.id, it.members) }
        if (from != null && fromLink) arrivedFrom(from, envelope.id, fromProven)
        // Now custody any blob the frame references (e.g. an image), so a late joiner can still pull it from us.
        onCarried(envelope, from)
    }

    // Custody kept [id], which came in on [from]'s link: an answer to [from] under way does not serve it back (§2), and
    // our offer to [from] drew something, so its re-offer interval is back to [REOFFER_MS] at once (§7).
    // Only a proven arrival names what the answer holds back: a link claiming [from]'s id must not keep frames from it.
    // The offer's draw is ours to judge (§7), proven or not: it only offers sooner.
    private fun arrivedFrom(
        from: String,
        id: String,
        proven: Boolean,
    ) = synchronized(lock) {
        if (proven) answers[from]?.arrived?.add(id)
        offers[from]?.let {
            it.drew = true
            it.intervalMs = REOFFER_MS
        }
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
     *
     * A contact's direct link needs nothing here (ADR 2026-10.a5yj): its own `sendDigest` filters these ids by the
     * link's rule over their custody envelopes, so the peer is told of the pair's own frames only.
     */
    suspend fun onNeighborAdded(peer: Peer) {
        val now = clock()
        // A neighbour linking anew starts the back-off over, and this offer counts as one (§7): never held back.
        synchronized(lock) { offers[peer.nodeId] = Offer(lastAt = now, intervalMs = REOFFER_MS) }
        transport.sendDigest(peer, store.liveIds(now))
    }

    /**
     * The 60 s tick's custody offer to a linked [peer], held back while the last offers drew nothing (the custody-carry-
     * policy contract's §7, #64): due when `now − last offer ≥ interval − REOFFER_MS / 2`, the half tick absorbing a tick
     * that lands early. A due offer judges the one before it, the interval doubling up to [REOFFER_MAX_MS] when it drew
     * nothing, and goes; a held one counts `custodyReoffersHeld`. A frame custody kept off the peer's link resets the
     * interval at once ([onSeen]). [force] offers now whatever the back-off says (the lab's "offer now"), and still counts
     * as an offer. Returns whether the digest went. Only the custody digest is held: the tick's key asks, owed ticks,
     * group seeds and blob re-asks keep their own rules.
     */
    suspend fun reoffer(
        peer: Peer,
        force: Boolean = false,
    ): Boolean {
        val now = clock()
        val due =
            synchronized(lock) {
                val offer = offers[peer.nodeId]
                when {
                    // A neighbour whose link-up pass never stamped it (a pass that failed part-way) is offered as at one.
                    offer == null -> {
                        offers[peer.nodeId] = Offer(lastAt = now, intervalMs = REOFFER_MS)
                        true
                    }

                    // An offer stamped further out than the longest wait was stamped before the wall clock went back.
                    force || !reofferBackoff || offer.lastAt - now > REOFFER_MAX_MS ||
                        now - offer.lastAt >= offer.intervalMs - REOFFER_TOLERANCE_MS -> {
                        offer.intervalMs = if (offer.drew) REOFFER_MS else minOf(offer.intervalMs * 2, REOFFER_MAX_MS)
                        offer.drew = false
                        offer.lastAt = now
                        true
                    }

                    else -> {
                        false
                    }
                }
            }
        if (!due) {
            metrics?.onCustodyReofferHeld()
            return false
        }
        transport.sendDigest(peer, store.liveIds(now))
        return true
    }

    /** The tick's neighbours are exactly [linked]: every other neighbour's back-off is forgotten, as a link-up resets it. */
    fun retainOffers(linked: Set<String>) {
        synchronized(lock) { offers.keys.retainAll(linked) }
    }

    /** [peer]'s re-offer interval now, or null when it has none (tests). */
    internal fun reofferIntervalMs(peer: String): Long? = synchronized(lock) { offers[peer]?.intervalMs }

    /**
     * [peerIds] just linked, or linked again, over a contact's direct link: a release answered to an earlier link of theirs
     * may have died with it, so their not-kept marks clear and what was pending on that link ends ([CarryLedger.relinked]).
     */
    fun linked(peerIds: Collection<String>) {
        // An answer to an earlier link's digest is that link's: it ends with it, and the new link's digest starts another.
        synchronized(lock) {
            peerIds.forEach { id ->
                if (answers[id]?.link == true) {
                    answers.remove(id)
                    carry.answerEnded(id)
                }
            }
        }
        peerIds.forEach { carry.relinked(it, TransportKind.DirectLink) }
    }

    /**
     * A neighbor advertised the custody ids it holds ([theirIds]): it is owed every carried frame **it lacks** — the set
     * difference that replaces pushing the whole store. No targeting: a DM, a group message and a room post all go to any
     * peer whose digest lacks them (ADR 2026-10.hr9f). The per-sender and per-group buckets keep the same frames on two
     * nodes only when the two hold the same set, so a group frame withheld from a non-member left it holding what the
     * members had evicted, re-served to them at every exchange (#176). We DO re-serve a peer frames it authored when the
     * diff shows it no longer holds them (custody wiped with the DB, e.g. the DatabaseKey unrecoverable-wrap path) — that
     * is how a node's own sends reconverge, and it authenticates them against its own identity bundle ([MeshManager]).
     * Each frame is re-wrapped in a fresh [WireEnvelope] (full ttl, hops 0) around its verbatim signed blob; a duplicate
     * that races in is still dropped by the receiver's SeenSet.
     *
     * **The answer goes out in paced rounds** (the custody-carry-policy contract's §2, #276, #352): a sidewalk pass holds
     * a link for seconds, and a whole difference queued at once died with it, the peer's own mail behind everyone
     * else's. One answer per peer; its first round is cut when the digest arrives, and each next one when it is due, by
     * the serve loop ([serveDue]), never by a newer digest, which replaces the digest the rounds are cut from and
     * restarts the answer's age but never brings a round forward. Each round is cut fresh from custody's index
     * ([ForwardStore.liveIndex]; it loads only what it sends), so a DM that arrives mid-answer goes next: the tiers of §1
     * over what the digest lacks, less what this answer released, less what arrived from the peer since its digest, less
     * the memo's pending and not-kept ids. The budget is the carrying plane's ([CustodyPace]; Bluetooth's whenever a
     * Bluetooth link holds the peer, [MeshTransport.carrier]); the first pick always goes, and the round stops at the first
     * later pick that would pass the budget, never skipping one. The answer ends when nothing is left, when the peer is no
     * longer linked (a radio answer when no radio holds it, a direct link's when its view is gone), or [ANSWER_MAX_AGE_MS]
     * after its latest digest; what is left waits for the next digest.
     *
     * **The tiers** (§1): profiles, newest received first (ADR 2026-09.9xuu: a peer meeting a sender first refused the
     * whole backlog for want of a key it was about to be handed); then frames addressed to the peer, then group frames
     * whose own roster lists it, each oldest first by `(sentAt, id)` (the key is frame-global, a ratchet reads in sending
     * order, and a group's seed lands before its posts); then the rest, newest received first. A roster is read once,
     * when custody keeps the frame ([CarryLedger.noteRoster]); a row not read yet orders in tier 4. A tier only orders.
     *
     * **And when custody holds no profile, the pin's does** (ADR 2026-09.g64k). The per-sender quota evicts a chatty
     * sender's profile, as do the global cap and the TTL, so a carrier often holds frames whose author's profile it no
     * longer holds. It still pinned their key to carry them: a sender none of whose frames the peer's digest lists and
     * whose profile custody does not hold has their key sent ahead of the round's frames, charged to the round, once per
     * answer (the peer's digest never shows a key).
     *
     * **A digest that crossed a contact's direct link ([via] = [TransportKind.DirectLink], ADR 2026-10.a5yj)** is
     * answered inside that link's rule ([MeshTransport.pairView]): only frames its view admits, and a key only for a
     * sender whose profile it would admit; a link gone since (no view) is answered with nothing. Decided by the plane the
     * digest crossed: the peer's digest is filtered by the same rule at its end, so an unfiltered answer would push every
     * frame outside it as a gap. Such a digest lists only the pair's view, so it never replaces a live radio answer's
     * digest: it merges into it.
     *
     * **A digest whose link proved its node id ([proven], and every direct link) feeds custody's carry ledger and has a
     * memo** ([CarryLedger], §3 and §6; #235 for the direct link it generalises): a frame released to that peer is not
     * released again while it is pending or after a later digest showed it was not kept, until the mark lapses or the peer
     * links anew, and a round's releases are credited to the peer's holders one interval later if the same link still
     * carries it. A frame addressed to the peer is released whenever its digest lacks it and this answer has not. A digest
     * whose link proved nothing is answered at the same pace and leaves nothing behind: its node id is only a claim. A
     * round with nothing to release sends nothing, a key included. A digest that comes while custody's index cannot be read
     * is dropped ([onDigestDropped]), and the answer under way stays as it was: answering it from nothing would re-serve
     * the peer everything.
     */
    suspend fun onDigest(
        fromNodeId: String,
        theirIds: List<String>,
        via: TransportKind = TransportKind.Other,
        proven: Boolean = false,
        // The link the digest crossed ([ReceivedDigest.link]): a different one on the same plane is the peer linking anew.
        link: Any? = null,
    ) {
        val now = clock()
        val index = readIndex(now) ?: return
        val isLink = via == TransportKind.DirectLink
        // A contact's direct link proves its peer by its lane, whichever child stamped the digest.
        val memo = proven || isLink
        if (memo) carry.onDigest(fromNodeId, theirIds, via, link, additive = isLink, now, links)
        serving.withLock {
            val byId = index.associateBy { it.id }
            val listed = theirIds.filterTo(HashSet()) { it in byId }
            val existing = synchronized(lock) { answers[fromNodeId] }
            // A link that proved nothing never overwrites a proven peer's answer: its node id is only a claim, and a claim
            // must not move what the real peer is owed. Within one answer, proof only rises.
            if (!memo && existing?.proven == true) return
            // A direct link's digest with the link gone since is answered with nothing; one beside a radio answer merges.
            if (isLink && existing?.link != false && transport.pairView(fromNodeId) == null) return
            val (answer, fresh) = synchronized(lock) { adopt(fromNodeId, existing, listed, isLink, memo, now) }
            if (answer.dueAt <= now) cut(fromNodeId, answer, now, index, byId, first = fresh)
        }
        wakes.trySend(Unit)
    }

    /**
     * [peer]'s digest listing [listed] (of what we hold) becomes its answer's word, under [lock]: a new answer when none is
     * live; merged into a radio answer when it crossed a contact's direct link, whose digest lists only the pair's view
     * and so forgets nothing; else it replaces the answer's digest, restarts its age and forgets every release old enough
     * for this digest to speak to (revision 7). Returns the answer and whether it is new.
     */
    @Suppress("LongParameterList") // one digest's facts
    private fun adopt(
        peer: String,
        existing: Answer?,
        listed: Set<String>,
        isLink: Boolean,
        memo: Boolean,
        now: Long,
    ): Pair<Answer, Boolean> {
        // [existing] was read in an earlier lock: `linked` or `resetSession` may have ended it since. An answer no longer
        // live is no answer to update, and this digest starts a new one.
        val live = existing?.takeIf { answers[peer] === it }
        if (live == null) {
            val created = Answer(link = isLink, proven = memo, digestAt = now, dueAt = now)
            created.awareCutSeen = carry.awareCutMark(peer)
            created.digest += listed
            answers[peer] = created
            // The plane holding the peer as its digest came: the first round's carrier, until a round says otherwise.
            carry.answering(peer, transport.carrier(peer)?.kind ?: TransportKind.Other)
            return created to true
        }
        if (isLink && !live.link) {
            live.digest += listed
            return live to false
        }
        live.forgetJudged(now)
        live.digest.clear()
        live.digest += listed
        live.arrived.clear()
        if (live.link != isLink) live.refused.clear()
        live.link = isLink
        live.proven = memo
        live.digestAt = now
        return live to false
    }

    /**
     * Cuts every round due by [now], and credits every hand-off due by then (§2, §3): what the serve loop runs when
     * [nextDueAt] comes, or a digest wakes it ([awaitWake]).
     */
    suspend fun serveDue(now: Long = clock()) {
        serving.withLock {
            val credited = synchronized(lock) { credits.filter { due(it.at, now) }.also { credits.removeAll(it.toSet()) } }
            // Asked of the transport with every lock released: the ledger's rule for the transport's reads.
            credited.forEach { c -> if (transport.carries(c.kind, c.peer, c.link)) carry.handed(c.peer, c.ids) }
            val due = synchronized(lock) { answers.filter { (_, a) -> due(a.dueAt, now) }.toList() }
            if (due.isEmpty()) return
            val index = store.liveIndex(now)
            val byId = index.associateBy { it.id }
            due.forEach { (peer, answer) ->
                // One peer's failed round (a key read, a load) costs that peer one interval, never the others' rounds.
                try {
                    cut(peer, answer, now, index, byId, first = false)
                } catch (e: CancellationException) {
                    throw e
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception, // any failure costs this peer's round alone
                ) {
                    synchronized(lock) { answer.dueAt = now + pace.bluetooth.intervalMs }
                    onRoundFailed(e)
                }
            }
        }
    }

    /**
     * When the next round or credit is due, or null when no answer is live and nothing waits to be credited. Never further
     * out than [MAX_ROUND_GAP_MS]: a wall clock set back leaves no answer asleep for the size of the jump.
     */
    fun nextDueAt(): Long? =
        synchronized(lock) {
            val round = answers.values.minOfOrNull { it.dueAt }
            val credit = credits.minOfOrNull { it.at }
            listOfNotNull(round, credit).minOrNull()?.let { minOf(it, clock() + MAX_ROUND_GAP_MS) }
        }

    // Whether a round or credit stamped [at] is due by [now]. One further out than any round or credit can be was stamped
    // before the wall clock went back: it is due now, never after the jump.
    private fun due(
        at: Long,
        now: Long,
    ): Boolean = at <= now || at - now > MAX_ROUND_GAP_MS

    /** Waits until a digest arrives or [timeoutMs] passes, whichever is first: the serve loop's sleep. */
    suspend fun awaitWake(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs.coerceAtLeast(0)) { wakes.receive() }
    }

    /**
     * A mesh start: every answer, every credit owed and every re-offer back-off goes, and the ledger's memos with them
     * (§6: in memory only). The holders and the rosters stay: they describe the store, not a session.
     */
    fun resetSession() {
        synchronized(lock) {
            answers.clear()
            carry.answeringCleared()
            credits.clear()
            offers.clear()
        }
        carry.clearMemos()
    }

    /**
     * Reads the roster of every held group frame not read yet ([CarryLedger.noteRoster]): after a restart the ledger is
     * empty and every group frame orders in tier 4 until this has run. Off the request path, at its own pace: the index
     * names the group rows, and only those are loaded, [ROSTER_CHUNK] at a time.
     */
    suspend fun readRosters(now: Long = clock()) {
        val unread = store.liveIndex(now).filter { it.groupId != null && !carry.rosterRead(it.id) }.map { it.id }
        unread.chunked(ROSTER_CHUNK).forEach { chunk ->
            store.liveFramesByIds(chunk, now).forEach { f -> f.envelope.group?.let { carry.noteRoster(f.envelope.id, it.members) } }
            yield()
        }
    }

    /** How many ids each set of [peer]'s live answer holds (digest, released, keys, arrived, refused), or null (tests). */
    internal fun answerSizes(peer: String): List<Int>? =
        synchronized(lock) {
            answers[peer]?.let { listOf(it.digest.size, it.released.size, it.keysHanded.size, it.arrived.size, it.refused.size) }
        }

    // Custody's index, or null when it cannot be read: the digest is dropped and nothing it would have changed moves.
    private suspend fun readIndex(now: Long): List<IndexedFrame>? =
        try {
            store.liveIndex(now)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception, // a SQLite error: this digest is dropped, the next one answered
        ) {
            onDigestDropped(e)
            null
        }

    /**
     * One round of [peer]'s [answer] at [now], over custody's [index]: ends the answer when it is aged, unlinked or has
     * nothing left, else releases the round and schedules the next. [first] is a new answer's round at its digest, which
     * goes whatever the links say, as an answer always has.
     */
    @Suppress("LongParameterList") // one round's inputs, read once by the caller for every answer it cuts
    private suspend fun cut(
        peer: String,
        answer: Answer,
        now: Long,
        index: List<IndexedFrame>,
        byId: Map<String, IndexedFrame>,
        first: Boolean,
    ) {
        val view = if (answer.link) transport.pairView(peer) else null
        val carrier = transport.carrier(peer)
        // The plane this round goes over is the one a plane's link hold reads ([CustodyProgress.answerCarrier]).
        carrier?.let { c -> synchronized(lock) { if (answers[peer] === answer) carry.answering(peer, c.kind) } }
        // A wall clock set back would otherwise leave the answer's age negative, and the answer alive for the jump.
        synchronized(lock) { if (answer.digestAt > now) answer.digestAt = now }
        val owed = synchronized(lock) { candidatesOf(peer, answer, index, byId.keys, carrier) }
        val candidates = owed.filterNot { blocked(peer, answer, it, now) }
        // A new answer's first round goes whatever the links say, as an answer always has; later ones end with the link.
        val linked = first || if (answer.link) view != null else transport.radioLinked(peer)
        val aged = !first && now - answer.digestAt >= ANSWER_MAX_AGE_MS
        val plan = pace.plan(carrier?.kind ?: TransportKind.Bluetooth)
        when {
            !linked || aged -> end(peer, answer, cutShort = candidates.isNotEmpty(), byLink = !linked)

            candidates.isEmpty() -> end(peer, answer, cutShort = false, byLink = false)

            // Nothing holds the peer now: a round would go nowhere, so none is cut and nothing is recorded as released.
            // The next is due an interval on, unless the link check ends the answer first.
            carrier == null -> synchronized(lock) { answer.dueAt = now + plan.intervalMs }

            else -> releaseRound(peer, answer, candidates, index, byId, view, carrier, plan, now)
        }
    }

    // Picks and releases [peer]'s round over [carrier]; one that loaded nothing it could send ends the answer when the view
    // refused all that was left, else waits an interval (every pick left custody between the index and its load).
    @Suppress("LongParameterList") // one round's inputs
    private suspend fun releaseRound(
        peer: String,
        answer: Answer,
        candidates: List<IndexedFrame>,
        index: List<IndexedFrame>,
        byId: Map<String, IndexedFrame>,
        view: PairView?,
        carrier: LinkCarrier,
        plan: RoundPlan,
        now: Long,
    ) {
        val round = pick(peer, answer, candidates, index, byId, view, plan, now)
        when {
            round.frames.isNotEmpty() -> release(peer, answer, round, carrier, view != null, plan, now)
            round.exhausted -> end(peer, answer, cutShort = false, byLink = false)
            else -> synchronized(lock) { answer.dueAt = now + plan.intervalMs }
        }
    }

    // The ids [peer]'s answer still owes, in serve order, before the memo: trims every set the answer keeps to custody's
    // live ids first (§2), and owes again what went over a link that is gone ([forgetLostLinks]). Under [lock].
    private fun candidatesOf(
        peer: String,
        answer: Answer,
        index: List<IndexedFrame>,
        held: Set<String>,
        carrier: LinkCarrier?,
    ): List<IndexedFrame> {
        answer.trim(held, index.mapTo(HashSet()) { it.senderId })
        forgetLostLinks(peer, answer, carrier)
        return index
            .filter { e ->
                e.id !in answer.digest &&
                    e.id !in answer.released &&
                    e.id !in answer.arrived &&
                    e.id !in answer.refused &&
                    // The Nearby room never crosses a contact's link (ADR 2026-10.a5yj): its posts are not even decoded.
                    !(answer.link && e.type == FrameType.CHAT && e.recipientId == null && e.groupId == null)
            }.sortedWith(serveOrder(peer))
    }

    // What [answer] released over a link that is gone is owed again, under [lock]: on a plane that relinks, a new link since
    // the last round is the peer linking anew, and what went over the old one may have died with it; on Wi-Fi Aware, a path
    // cut with this answer live (the plane's [CustodyProgress.answerCut]) took its pending entries, and with them this
    // answer's memory of what that path carried, which may never have crossed.
    private fun forgetLostLinks(
        peer: String,
        answer: Answer,
        carrier: LinkCarrier?,
    ) {
        val link = carrier?.link
        if (carrier != null && carrier.kind != TransportKind.WifiAware && link != null) {
            val before = answer.carrierLink
            if (before != null && before !== link) {
                answer.released.clear()
                answer.keysHanded.clear()
            }
            answer.carrierLink = link
        }
        if (carrier != null) answer.carrierKind = carrier.kind
        val cut = carry.awareCutMark(peer)
        if (cut > answer.awareCutSeen) {
            answer.released.values.removeAll { it.plane == TransportKind.WifiAware }
            answer.awareCutSeen = cut
        }
    }

    // Whether the memo holds [entry] back from [peer]: only a proven answer has one (§6).
    private fun blocked(
        peer: String,
        answer: Answer,
        entry: IndexedFrame,
        now: Long,
    ): Boolean = synchronized(lock) { answer.proven } && carry.blocked(peer, entry.id, entry.recipientId, now, links)

    /** The tiers of §1 for an answer to [peer]: profiles, addressed to it, its groups, the rest. */
    private fun serveOrder(peer: String): Comparator<IndexedFrame> {
        val tiers = HashMap<String, Int>()
        val tierOf = { e: IndexedFrame ->
            tiers.getOrPut(e.id) {
                when {
                    e.type == FrameType.PROFILE -> TIER_PROFILES
                    e.recipientId == peer -> TIER_ADDRESSED
                    e.groupId != null && carry.rosterLists(e.id, peer) -> TIER_GROUPS
                    else -> TIER_REST
                }
            }
        }
        return Comparator { a, b ->
            val ta = tierOf(a)
            val tb = tierOf(b)
            when {
                ta != tb -> {
                    ta - tb
                }

                ta == TIER_ADDRESSED || ta == TIER_GROUPS -> {
                    if (a.sentAt !=
                        b.sentAt
                    ) {
                        a.sentAt.compareTo(b.sentAt)
                    } else {
                        CapEviction.compareBytes(a.id, b.id)
                    }
                }

                a.receivedAt != b.receivedAt -> {
                    b.receivedAt.compareTo(a.receivedAt)
                }

                else -> {
                    CapEviction.compareBytes(a.id, b.id)
                }
            }
        }
    }

    /** One round's picks, loaded: the frames in serve order, the keys ahead of them, its bytes, and who lacked a key. */
    private class Round(
        val frames: List<CarriedFrame>,
        val keys: Map<String, WireEnvelope>,
        val bytes: Long,
        val keyless: Int,
        // Every candidate was read and none goes: a direct link's view refused what was left.
        val exhausted: Boolean,
    )

    /**
     * Picks [peer]'s round from [candidates] under [plan]'s budget, keys ahead charged to it: the first pick always goes,
     * and the round stops at the first later one that would pass the budget. A radio answer picks off the index and loads
     * only its picks; a direct link's decodes its candidates in batches to ask the view, remembering what it refused.
     */
    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod") // one round's budget walk, read in one place
    private suspend fun pick(
        peer: String,
        answer: Answer,
        candidates: List<IndexedFrame>,
        index: List<IndexedFrame>,
        byId: Map<String, IndexedFrame>,
        view: PairView?,
        plan: RoundPlan,
        now: Long,
    ): Round {
        // A peer holds a sender's key when its digest lists one of their frames (it verified it to keep it), and is handed
        // it with their profile when custody holds one (tier 1 goes first). Not the peer's own.
        val known =
            synchronized(lock) {
                val k = HashSet<String>(answer.keysHanded)
                answer.digest.forEach { id -> byId[id]?.let { k += it.senderId } }
                k
            }
        index.forEach { if (it.type == FrameType.PROFILE) known += it.senderId }
        val keys = LinkedHashMap<String, WireEnvelope>()
        val noKey = HashSet<String>()
        val picks = ArrayList<IndexedFrame>()
        var bytes = 0L
        var keyless = 0

        // Takes [e] into the round with the key its sender needs ahead, if one is held; false when it would pass the budget.
        suspend fun take(e: IndexedFrame): Boolean {
            val sender = e.senderId
            var key: WireEnvelope? = null
            val needs = sender != peer && sender !in known && sender !in keys
            if (needs && keyMayGo(sender, noKey, view)) {
                key = keyFor(sender)
                if (key == null) noKey += sender
            }
            val cost = e.cost + (key?.let { IndexedFrame.wireCost(it.sig.size, it.signed.size) } ?: 0)
            if (picks.isNotEmpty() && bytes + cost > plan.bytes) return false
            picks += e
            bytes += cost
            if (key != null) keys[sender] = key
            if (needs && sender in noKey) keyless++
            return true
        }
        if (view == null) {
            for (e in candidates) if (!take(e)) break
            val loaded = store.liveFramesByIds(picks.map { it.id }, now).associateBy { it.envelope.id }
            refuseUnloaded(answer, picks, loaded)
            val frames = picks.mapNotNull { loaded[it.id] }
            return Round(frames, keys.filterKeys { s -> frames.any { it.envelope.senderId == s } }, bytes, keyless, exhausted = false)
        }
        val frames = ArrayList<CarriedFrame>()
        var read = 0
        var stopped = false
        while (read < candidates.size && !stopped) {
            val batch = candidates.subList(read, minOf(read + LINK_DECODE_BATCH, candidates.size))
            read += batch.size
            val loaded = store.liveFramesByIds(batch.map { it.id }, now).associateBy { it.envelope.id }
            refuseUnloaded(answer, batch, loaded)
            for ((e, frame) in batch.mapNotNull { e -> loaded[e.id]?.let { e to it } }) {
                when {
                    !view.admits(frame.envelope) -> {
                        synchronized(lock) { answer.refused += e.id }
                    }

                    take(e) -> {
                        frames += frame
                    }

                    else -> {
                        stopped = true
                        break
                    }
                }
            }
        }
        val exhausted = frames.isEmpty() && read >= candidates.size
        return Round(frames, keys.filterKeys { s -> frames.any { it.envelope.senderId == s } }, bytes, keyless, exhausted)
    }

    // A row the index names that did not load (it left custody since, or its bytes no longer decode) is refused for the
    // rest of the answer, so it is never the first pick of every round; one that left is trimmed at the next cut.
    private fun refuseUnloaded(
        answer: Answer,
        wanted: List<IndexedFrame>,
        loaded: Map<String, CarriedFrame>,
    ) {
        val missing = wanted.filter { it.id !in loaded }
        if (missing.isNotEmpty()) synchronized(lock) { missing.forEach { answer.refused += it.id } }
    }

    // Whether a key for [sender] may be read for this round: none was found already, and a direct link's view would carry
    // their profile.
    private fun keyMayGo(
        sender: String,
        noKey: Set<String>,
        view: PairView?,
    ): Boolean = sender !in noKey && (view == null || view.admits(profileOf(sender)))

    // Sends [round] to [peer]: its keys first, then its frames in serve order; records what went, schedules the next round,
    // and, for a proven answer, tells the memo what is pending and owes the hand-off its credit one interval on (§3, §6).
    @Suppress("LongParameterList") // one released round and where it went
    private suspend fun release(
        peer: String,
        answer: Answer,
        round: Round,
        carrier: LinkCarrier,
        viewFiltered: Boolean,
        plan: RoundPlan,
        now: Long,
    ) {
        val to = transport.neighbors.value.find { it.nodeId == peer } ?: Peer(peer)
        round.keys.values.forEach { sendKey(it, to) }
        round.frames.forEach { carried ->
            transport.send(WireEnvelope(sig = carried.sig, signed = carried.signed), to)
            onServed(carried.envelope, peer)
        }
        val ids = round.frames.map { it.envelope.id }
        // The memo and the credit follow proof twice over: the answer's digest proved the peer, and so does the link the
        // round goes over. Either one unproven, and the round leaves nothing behind (§3, §6).
        val memo =
            synchronized(lock) {
                ids.forEach { answer.released[it] = Released(now, carrier.kind) }
                answer.keysHanded += round.keys.keys
                answer.dueAt = plan.nextDue(now, round.bytes)
                val memo = answer.proven && carrier.proven
                if (memo) credits += Credit(peer, ids, carrier.kind, carrier.link, now + plan.intervalMs)
                memo
            }
        if (memo) carry.released(peer, round.frames.map { it.envelope }, carrier, viewFiltered, now)
        metrics?.onCustodyRoundCut(keyless = round.keyless)
    }

    // [peer]'s [answer] ends (§2). Cut short by a lost link or by age with something left to release counts, and on Wi-Fi
    // Aware a path cut with the answer live ends what was pending on it unjudged (§6): the next path's digest cannot judge
    // what never crossed.
    private fun end(
        peer: String,
        answer: Answer,
        cutShort: Boolean,
        byLink: Boolean,
    ) {
        synchronized(lock) {
            if (answers[peer] === answer) {
                answers.remove(peer)
                carry.answerEnded(peer)
            }
        }
        if (!cutShort) return
        metrics?.onCustodyAnswerCutShort()
        // Only an answer whose rounds went over Aware: a Bluetooth answer cut short leaves Aware's pending to its own path.
        if (byLink && synchronized(lock) { answer.carrierKind } == TransportKind.WifiAware) carry.answerCut(peer, TransportKind.WifiAware)
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

    // A stand-in for [senderId]'s profile frame: what a link's view reads to say whether it would carry their key.
    private fun profileOf(senderId: String) = RelayEnvelope(type = FrameType.PROFILE, id = "", senderId = senderId, payload = ByteArray(0))

    private fun earlyAckKey(
        ackId: String,
        ackerId: String,
    ): String = "$ackId|$ackerId"

    companion object {
        /** How long a delivered id stays tombstoned — matches the carry TTL so it can't outlive a copy. */
        private const val ACK_TOMBSTONE_TTL_MS = 24 * 60 * 60_000L

        /** How long an answer lives after its latest digest (§2): what is left then waits for the next one. */
        const val ANSWER_MAX_AGE_MS = 120_000L

        /** The re-offer interval a neighbour starts at, and returns to when an offer draws something (§7). */
        const val REOFFER_MS = 60_000L

        /** The longest the re-offer interval doubles to while offers draw nothing (§7). */
        const val REOFFER_MAX_MS = 600_000L

        // No round or credit is ever due further out than an answer lives: one that is was stamped before the wall clock
        // went back, and is due now.
        private const val MAX_ROUND_GAP_MS = ANSWER_MAX_AGE_MS

        /** How early a tick may land and still find a neighbour due (§7): half of one. */
        const val REOFFER_TOLERANCE_MS = REOFFER_MS / 2

        // The serve order's tiers (§1).
        private const val TIER_PROFILES = 0
        private const val TIER_ADDRESSED = 1
        private const val TIER_GROUPS = 2
        private const val TIER_REST = 3

        // Candidates a direct link's round decodes at a time to ask the view: a round's worth, not the store.
        private const val LINK_DECODE_BATCH = 16

        // Group rows a restart's roster rebuild loads at a time.
        private const val ROSTER_CHUNK = 64
    }
}
