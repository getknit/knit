package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.RelayEnvelope

/**
 * Who this phone believes holds each frame custody holds, and what it released to each peer that the peer did not keep:
 * the custody-carry-policy contract's §3 (holders) and §6 (the not-kept memo). One instance per process, written by
 * [ForwardSync] and read by `ForwardRepository` through [CarryReader] (only the global cap's choice of row reads it).
 * Pure: no Android, no Room, no clock of its own (every call that judges time is handed `now`), and every method is
 * short and synchronised, so the repository may call the [CarryReader] events under its mutex.
 *
 * **Only a proven link feeds it** (§3, §6). A peer counts when its link proved its node id: both ends carry the link
 * proof, the link is sealed, or it is a contact's direct link. An unproven link's node id is only a claim, so its digest
 * is answered and leaves nothing here, no holder, no pending id and no mark: otherwise a link that claims another node's
 * id could have that node's mail marked not kept, or push the real peers' memos out of the bounded table. The caller
 * enforces that ([ForwardSync] calls the digest and release side only for a proven digest, and passes a source only for
 * a proven arrival).
 *
 * **Holders.** Per held id at most [spreadMax] distinct peers, a further one not recorded; `spread` is their count. A peer
 * joins when its digest lists the id (*seen*), when the frame arrived from it (*source*, counted in the frame's own write
 * through [snapshot] and recorded by [onKept] only if the row is held after it), and when a hand-off to it is credited
 * (*handed*, [handed]). A digest is the peer's latest word: it leaves the holders of every held id the digest lacks,
 * except an id still pending and not judged. Only ids in the held set have an entry, and an entry dies with its row
 * ([retainOnly], [onLeft]), so the ledger holds at most custody's row cap. Our own node never appears: no proven link
 * carries it.
 *
 * **The memo** (§6), per peer, [maxPeers] peers least recently written out first. A frame addressed to the peer is never
 * pending and never marked: a recipient's own mail is never withheld on a memo's word. Any other release is *pending*
 * until a digest judges it, and is not released to that peer again meanwhile. A digest judges each pending id released
 * at least [judgeAfterMs] before it arrived (a digest that crossed the release in flight would judge it wrongly):
 * listed, it was kept; lacking and still held, it is *not kept*, stamped then; no longer held, it just leaves. A not-kept
 * id is not released to that peer until [lapseMs] after its mark, and a later digest neither refreshes nor clears the
 * mark. A pending id stops being pending unjudged [lapseMs] after its release, or when the Bluetooth or direct link that
 * carried it ends (its link is no longer that plane's live link to the peer, whatever other plane still holds it). A
 * relink on Bluetooth or a direct link (a new link on that plane) clears the peer's not-kept ids. A Wi-Fi Aware exchange
 * is a new data path every time, so a new path clears nothing, and a release on a path that closed at quiescence stays
 * pending for the next path's digest to judge; one whose path was cut with an answer still live ends by [answerCut].
 *
 * **A contact's direct-link digest is additive** (Android's own): it lists only the pair's view, so what it lists adds
 * holders and judges pending ids kept, and what it lacks judges only releases a view-filtered answer made. It never
 * replaces what a radio digest said.
 *
 * What it costs a peer to lie: holders can be faked over a proven link, and that only picks which row of a largest
 * sender bucket the cap takes (`CapEviction`); what eviction reads, a peer can read back from our digest, so nothing but
 * who holds a frame belongs here. In memory only, empty after a restart: the cap then orders by `(sentAt, id)` alone.
 */
@Suppress("TooManyFunctions") // one ledger, three faces: the repository's reader, the sync's feeds, the planes' progress
class CarryLedger(
    // Counts the not-kept marks set (`custodyNotKept`); null in rigs that do not read it.
    private val metrics: MeshMetrics? = null,
    private val spreadMax: Int = SPREAD_MAX,
    private val maxPeers: Int = MEMO_MAX_PEERS,
    private val lapseMs: Long = NOT_KEPT_LAPSE_MS,
) : CarryReader,
    CustodyProgress {
    /**
     * Whether a plane still holds a given link to a peer: the transport's answer ([MeshTransport.carries]), asked when a
     * pending id is read, so a release ends with the link that carried it and no event has to reach this class. Always
     * asked with the ledger's lock released, so a transport that reads [CustodyProgress] under its own lock cannot
     * deadlock against it.
     */
    fun interface Links {
        fun carries(
            plane: TransportKind,
            peer: String,
            link: Any?,
        ): Boolean

        companion object {
            /** Every link still up: for a caller that knows of none ending. */
            val ALWAYS: Links = Links { _, _, _ -> true }
        }
    }

    private class Pending(
        val releasedAt: Long,
        val plane: TransportKind,
        val link: Any?,
        // Released by an answer to a direct-link digest, so filtered by the pair's view: only such a release may a
        // direct-link digest's lack judge.
        val viewFiltered: Boolean,
    )

    private class Memo {
        val pending = HashMap<String, Pending>()

        // Not-kept ids, with when each was marked.
        val notKept = HashMap<String, Long>()

        // The link last seen per relinking plane: a different one is the peer linking anew.
        val links = HashMap<TransportKind, Any?>()

        // The peer's last word, its digest ∩ the ids held, as 64-bit hashes: what [fresh] reads. Trimmed with the held set.
        val lastDigest = HashSet<Long>()

        // Whether [lastDigest] holds a word yet: before the peer's first digest, nothing says what it lacks.
        var digested = false

        // An id's frame is fresh for this peer: its last digest lacked it, and it is neither pending nor marked.
        fun lacks(
            id: String,
            hash: Long,
        ): Boolean = digested && hash !in lastDigest && id !in pending && id !in notKept

        var progressAt = 0L
        var digestAt = 0L
        var watching = false
        var fresh = false
    }

    private val lock = Any()

    // The held set: custody's live ids, as the repository's events report them.
    private val held = HashSet<String>()

    // Holders per held id; keys ⊆ [held], every set non-empty and at most [spreadMax].
    private val holders = HashMap<String, LinkedHashSet<String>>()

    // Insertion-ordered, a write moving its peer to the end ([memoFor]): reads leave the order alone, so a plane polling
    // [progressMark] never keeps a peer's memo alive.
    private val memos = LinkedHashMap<String, Memo>()

    // The one counter every mark is stamped from (see [CustodyProgress]).
    private var stamps = 0L

    // --- CarryReader: the repository's side, under its mutex ---

    override fun snapshot(
        newcomer: String,
        source: String?,
    ): CarrySnapshot =
        synchronized(lock) {
            val spreads = HashMap<String, Int>(holders.size + 1)
            holders.forEach { (id, peers) -> spreads[id] = peers.size }
            val known = holders[newcomer]
            val newcomerSpread = (known?.size ?: 0) + if (source != null && known?.contains(source) != true) 1 else 0
            spreads[newcomer] = minOf(newcomerSpread, spreadMax)
            CarrySnapshot { id -> spreads[id] ?: 0 }
        }

    override fun onKept(
        id: String,
        source: String?,
    ) {
        synchronized(lock) {
            val newly = held.add(id)
            if (source != null) {
                addHolder(id, source)
                if (newly) memoFor(source).progressAt = ++stamps
            }
            if (newly) noteFresh(id, source)
        }
    }

    // Every per-peer set is trimmed here, to the held set, whether or not an answer or a digest stream ever ends (the
    // contract's §2, revision 6): what bounds the memo is custody's live ids, never a judging that may not come.
    override fun retainOnly(liveIds: Set<String>) {
        synchronized(lock) {
            held.clear()
            held.addAll(liveIds)
            holders.keys.retainAll(liveIds)
            val liveHashes = liveIds.mapTo(HashSet(liveIds.size)) { hash(it) }
            memos.values.forEach { memo ->
                memo.pending.keys.retainAll(liveIds)
                memo.notKept.keys.retainAll(liveIds)
                memo.lastDigest.retainAll(liveHashes)
            }
        }
    }

    override fun onLeft(id: String) {
        synchronized(lock) {
            held.remove(id)
            holders.remove(id)
            val h = hash(id)
            memos.values.forEach { memo ->
                memo.pending.remove(id)
                memo.notKept.remove(id)
                memo.lastDigest.remove(h)
            }
        }
    }

    /** The largest of [peer]'s per-peer sets (pending, not kept, last digest): what custody's live ids bound. */
    internal fun memoSize(peer: String): Int =
        synchronized(lock) { memos[peer]?.let { maxOf(it.pending.size, it.notKept.size, it.lastDigest.size) } ?: 0 }

    // --- ForwardSync's side ---

    /**
     * A proven digest from [peer] listing [ids] arrived at [now] over [plane] on [link]. Lists the peer among the holders
     * of every held id it names (*seen*), judges the peer's pending ids, and, unless [additive] (a direct-link digest),
     * takes the digest as the peer's latest word. Of [ids] only those held are read, so a digest of junk ids costs nothing
     * here.
     */
    @Suppress("LongParameterList") // one digest's facts, each read by a rule of §3 or §6
    fun onDigest(
        peer: String,
        ids: Collection<String>,
        plane: TransportKind,
        link: Any?,
        additive: Boolean,
        now: Long,
        links: Links,
    ) {
        val ended = endedLinks(peer, links)
        synchronized(lock) {
            val memo = memoFor(peer)
            memo.digestAt = ++stamps
            noteLink(memo, plane, link)
            expire(memo, now, ended)
            val listed = ids.filterTo(HashSet()) { it in held }
            listed.forEach { addHolder(it, peer) }
            judge(memo, peer, listed, additive, now)
            if (!additive) {
                // The latest word: the peer leaves every held id its digest lacks, bar one still pending and unjudged.
                val gone = holders.mapNotNull { (id, peers) -> id.takeIf { peer in peers && it !in listed && it !in memo.pending } }
                gone.forEach { removeHolder(it, peer) }
                memo.lastDigest.clear()
            }
            listed.mapTo(memo.lastDigest) { hash(it) }
            memo.digested = true
        }
    }

    /**
     * Whether [frame] may not go to [peer] at [now]: it is pending or marked not kept for that peer. A frame addressed to
     * the peer never is. Reads the memo only, and never creates one.
     */
    fun blocked(
        peer: String,
        frame: RelayEnvelope,
        now: Long,
        links: Links,
    ): Boolean {
        if (frame.recipientId == peer) return false
        val id = frame.id
        // The link is asked outside the lock (see [Links]); an entry replaced meanwhile was released since, so it counts.
        val asked = synchronized(lock) { memos[peer]?.pending?.get(id) }
        val askedUp = asked == null || !relinks(asked.plane) || links.carries(asked.plane, peer, asked.link)
        synchronized(lock) {
            val memo = memos[peer] ?: return false
            val pending = memo.pending[id]
            if (pending != null) {
                val linkUp = pending !== asked || askedUp
                if (linkUp && now - pending.releasedAt < lapseMs) return true
                memo.pending.remove(id)
            }
            val marked = memo.notKept[id] ?: return false
            if (now - marked < lapseMs) return true
            memo.notKept.remove(id)
            return false
        }
    }

    /**
     * [frames] were released to [peer] at [now] over [carrier]'s link, by an answer filtered by a direct link's view when
     * [viewFiltered]. Each not addressed to the peer is pending until a digest judges it.
     */
    fun released(
        peer: String,
        frames: Collection<RelayEnvelope>,
        carrier: LinkCarrier,
        viewFiltered: Boolean,
        now: Long,
    ) {
        synchronized(lock) {
            val memo = memoFor(peer)
            noteLink(memo, carrier.kind, carrier.link)
            frames.forEach { frame ->
                if (frame.recipientId != peer && frame.id in held) {
                    memo.pending[frame.id] = Pending(now, carrier.kind, carrier.link, viewFiltered)
                }
            }
        }
    }

    /** A hand-off of [ids] to [peer] was credited: the round's link still carried the peer one interval after it went. */
    fun handed(
        peer: String,
        ids: Collection<String>,
    ) {
        synchronized(lock) { ids.forEach { addHolder(it, peer) } }
    }

    /**
     * [peer] linked anew on [plane] (a contact's direct link adopted, or a Bluetooth link the plane itself reports): its
     * not-kept ids clear, and what was pending on that plane ends unjudged, since it may have died with the old link.
     * Wi-Fi Aware never calls this: a new path clears nothing.
     */
    fun relinked(
        peer: String,
        plane: TransportKind,
    ) {
        synchronized(lock) {
            val memo = memos[peer] ?: return
            memo.notKept.clear()
            memo.pending.values.removeAll { it.plane == plane }
            memo.links.remove(plane)
        }
    }

    /**
     * [peer]'s [plane] path was cut with an answer to it still live: what was pending on that plane ends unjudged. The
     * Wi-Fi Aware ending the contract's §6 names; a path that closed at quiescence never calls this, and the next path's
     * digest judges its releases.
     */
    fun answerCut(
        peer: String,
        plane: TransportKind,
    ) {
        synchronized(lock) { memos[peer]?.pending?.values?.removeAll { it.plane == plane } }
    }

    /** Forgets every peer's memo (the holders stay: they describe the store, not a session). */
    fun clearMemos() {
        synchronized(lock) { memos.clear() }
    }

    /** How many peers are believed to hold [id] now, 0 to [spreadMax]. */
    fun spread(id: String): Int = synchronized(lock) { holders[id]?.size ?: 0 }

    // --- CustodyProgress ---

    override fun progressMark(peer: String): Long = synchronized(lock) { memos[peer]?.progressAt ?: 0 }

    override fun watchFresh(peer: String) {
        synchronized(lock) {
            val memo = memos[peer] ?: return
            memo.watching = true
            memo.fresh = false
        }
    }

    override fun fresh(peer: String): Boolean = synchronized(lock) { memos[peer]?.fresh == true }

    override fun digestSeen(peer: String): Long = synchronized(lock) { memos[peer]?.digestAt ?: 0 }

    // --- under [lock] ---

    private fun memoFor(peer: String): Memo {
        val memo = memos.remove(peer) ?: Memo()
        memos[peer] = memo
        if (memos.size > maxPeers) memos.remove(memos.keys.first())
        return memo
    }

    // A different link on a relinking plane than the one last seen is the peer linking anew there. A digest or carrier that
    // names no link says nothing either way.
    private fun noteLink(
        memo: Memo,
        plane: TransportKind,
        link: Any?,
    ) {
        if (!relinks(plane) || link == null) return
        if (memo.links.containsKey(plane) && memo.links[plane] !== link) {
            memo.notKept.clear()
            memo.pending.values.removeAll { it.plane == plane }
        }
        memo.links[plane] = link
    }

    // Pending ids whose release lapsed or whose link is in [ended], and marks that lapsed, leave.
    private fun expire(
        memo: Memo,
        now: Long,
        ended: List<Pair<TransportKind, Any?>>,
    ) {
        memo.pending.values.removeAll { p ->
            now - p.releasedAt >= lapseMs || ended.any { (plane, link) -> plane == p.plane && link === p.link }
        }
        memo.notKept.values.removeAll { now - it >= lapseMs }
    }

    /**
     * The links [peer]'s pending releases went over that [links] says have ended. The links are read with the ledger's
     * lock released: the transport's reads are its own, and a plane that asks custody's progress under its own lock must
     * never meet this lock held the other way round. A release recorded meanwhile on a link found ended ends with it.
     */
    private fun endedLinks(
        peer: String,
        links: Links,
    ): List<Pair<TransportKind, Any?>> {
        val used =
            synchronized(lock) {
                memos[peer]
                    ?.pending
                    ?.values
                    ?.filter { relinks(it.plane) }
                    ?.map { it.plane to it.link }
                    .orEmpty()
            }
        val distinct = ArrayList<Pair<TransportKind, Any?>>()
        used.forEach { u -> if (distinct.none { it.first == u.first && it.second === u.second }) distinct += u }
        return distinct.filterNot { (plane, link) -> links.carries(plane, peer, link) }
    }

    private fun judge(
        memo: Memo,
        peer: String,
        listed: Set<String>,
        additive: Boolean,
        now: Long,
    ) {
        val due = memo.pending.mapNotNull { (id, p) -> (id to p).takeIf { now - p.releasedAt >= judgeAfterMs(p.plane) } }
        due.forEach { (id, p) ->
            if (id in listed) {
                memo.pending.remove(id)
                memo.progressAt = ++stamps
            } else if (!additive || p.viewFiltered) {
                // Lacking. A direct-link digest lists only the pair's view, so its lack judges only that view's releases.
                memo.pending.remove(id)
                if (id in held) {
                    memo.notKept[id] = now
                    metrics?.onCustodyNotKept()
                    removeHolder(id, peer)
                }
            }
        }
    }

    private fun addHolder(
        id: String,
        peer: String,
    ) {
        if (id !in held) return
        val peers = holders.getOrPut(id) { LinkedHashSet() }
        if (peers.size < spreadMax) peers += peer
    }

    private fun removeHolder(
        id: String,
        peer: String,
    ) {
        val peers = holders[id] ?: return
        peers -= peer
        if (peers.isEmpty()) holders.remove(id)
    }

    // A newly held [id] is fresh for every watched peer whose last digest lacked it, that it is neither pending nor
    // marked for, and that it did not arrive from.
    private fun noteFresh(
        id: String,
        source: String?,
    ) {
        val h = hash(id)
        memos.forEach { (peer, memo) ->
            if (memo.watching && peer != source && memo.lacks(id, h)) memo.fresh = true
        }
    }

    companion object {
        /** Holders remembered per frame (§3): a ninth is not recorded, and `spread` is 0 to 8. */
        const val SPREAD_MAX = 8

        /** Peers with a memo at once (§6), least recently written out first. */
        const val MEMO_MAX_PEERS = 64

        /** How long a not-kept mark, and an unjudged release, last (§6): the receiver's seen window. */
        const val NOT_KEPT_LAPSE_MS = 600_000L

        /** How old a Bluetooth release must be before a digest judges it (§6): the link's own buffer may still hold it. */
        const val JUDGE_AFTER_BLUETOOTH_MS = 20_000L

        /** How old a Wi-Fi Aware or direct-link release must be before a digest judges it (§6). */
        const val JUDGE_AFTER_FAST_MS = 5_000L

        /** The judging delay of a release over [plane]; a plane of another kind judges as Bluetooth does. */
        fun judgeAfterMs(plane: TransportKind): Long =
            when (plane) {
                TransportKind.WifiAware, TransportKind.DirectLink -> JUDGE_AFTER_FAST_MS
                else -> JUDGE_AFTER_BLUETOOTH_MS
            }

        // Whether a new link on [plane] is the peer linking anew, and the end of its link ends a release's pending: every
        // plane but Wi-Fi Aware, whose every exchange is a new data path.
        private fun relinks(plane: TransportKind): Boolean = plane != TransportKind.WifiAware

        // FNV-1a over the id's UTF-16 units: 64 bits is collision-free enough for one peer's ≤ maxRows ids.
        private fun hash(id: String): Long {
            var h = FNV_OFFSET
            for (c in id) {
                h = (h xor c.code.toLong()) * FNV_PRIME
            }
            return h
        }

        private const val FNV_OFFSET = -0x340d631b7bdddcdbL
        private const val FNV_PRIME = 0x100000001b3L
    }
}
