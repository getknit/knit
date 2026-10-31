package app.getknit.knit.mesh

import app.getknit.knit.mesh.protocol.BlobCancelContent
import app.getknit.knit.mesh.protocol.BlobHasContent
import app.getknit.knit.mesh.protocol.BlobHasItem
import app.getknit.knit.mesh.protocol.BlobHaveContent
import app.getknit.knit.mesh.protocol.BlobProgressContent
import app.getknit.knit.mesh.protocol.BlobReqContent
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Demand-driven, content-addressed image fetch over the mesh. A node that needs a blob it lacks asks
 * its direct neighbors ([want]); a neighbor that holds it serves it back over the file channel, and
 * one that doesn't pulls it on the asker's behalf ([onRequest] → [want]) so the asker's *next* ask finds
 * a holder one hop closer. The blob therefore walks hop-by-hop to any requester using only direct-neighbor
 * file transfer — no file-relay-by-destination is needed. The re-ask is the 60 s neighbor re-offer
 * ([reask], re-armed from the database by `MeshManager.rewantMissingBlobs`, ADR 2026-09.ptv8);
 * nothing is ever pushed to a peer that did not just ask, because the serving side cannot tell a peer
 * that still lacks the bytes from one whose copy is already on the way from somebody else — a clique
 * that pushed bought every recipient a copy per neighbor (work item #79, ADR 2026-09.4tx5).
 *
 * A blob whose bytes are already streaming in on a link (a key of [MeshTransport.arrivingFiles]) is neither
 * wanted nor re-asked for; a re-ask for one already queued toward that peer ([MeshTransport.fileInFlightTo])
 * ships nothing. Both are reads of the link, not memos here — a torn link clears its own state. The Bluetooth
 * stack's buffer sits between those two reads, so a peer asked before is not asked again while its file is
 * streaming in ([MeshTransport.fileArrivingFrom]), and a holder counts a fed file as in flight while its link goes on
 * feeding that peer (#121). A file stays arriving past its end until its consumer has taken it
 * ([MeshTransport.fileTaken], here in [onReceived]), and every read takes the bytes' path backwards — the link, the
 * store's own mark, then the store — so a blob that moves on between two reads is caught by the later one (#138).
 *
 * The `blobreq` that drives [onRequest] is **unsigned** (see `MeshManager.verifyInbound`), so, like
 * [KeyExchange]/[PendingInbound], the bookkeeping is **bounded**: [fetching] is capped (oldest-first
 * eviction) and TTL-swept ([sweepExpired]); the [recentlyServed] serve-memo keeps its 45 s TTL plus a size
 * cap. A peer flooding requests for hashes we don't hold therefore costs bounded memory and work.
 *
 * **Who is asked (ADR 2026-10.6e5t).** A neighbour whose link advertises [Protocol.CAP_BLOB_HAVE] is first *queried*
 * — a `blobhave` it answers with a `blobhas` ([onHaveQuery]): which of the blobs it holds, is already sending us, or is
 * fetching itself. Once every queried neighbour has answered, or [HolderQuery.waitMs] has passed, the best holder by
 * plane and load is asked alone ([tierOf]); one that brings nothing within its grace is queried again once, and then
 * the next best is asked. Answers live for one cycle ([CYCLE_MS]); the next cycle queries everyone again, since
 * phones move. A query is not an ask: it serves nothing, pulls nothing, and stamps no memo.
 *
 * An ask to a neighbour that said it holds the blob stands until its link goes, its serve memo's floor
 * ([SERVE_MEMO_MS]) runs out, or it says it no longer holds it — never on silence alone (work item #140). Nothing on
 * the wire withdraws an ask and every holder serves a fresh one, so a second holder asked while the first one's copy
 * sits in a backed-up link costs a whole second copy. Silence is what a backlog of our own looks like, so each wait
 * on a neighbour's answer is also stretched to the round trip its last answer took ([queryWaitFor]).
 *
 * Every neighbour without the bit — the iOS port today, an older Android build, every neighbour while
 * [HolderQuery.enabled] is off — and a queried one still silent past the wait gets the round of ADR 2026-10.f7gg,
 * unchanged: one blind ask of the likeliest holder (author, then the Wi-Fi Aware plane, then the hop the frame came
 * from), and every other at once once its grace has passed, never one at a time without a negative answer. When
 * nobody in reach holds it or is fetching it, the source hop is asked to pull it through, and every neighbour that
 * said it lacks it from the next empty cycle on; a neighbour asked to pull it is queried again shortly, a few times,
 * since it holds the blob the moment its own fetch lands. A cycle in which every neighbour said it lacks the blob
 * doubles the wait for the next one ([Want.cycleGap]): a blob nobody in reach holds costs a query and a pull a few
 * times an hour, not every tick, and past a day of that a query a few times a day, while a neighbour that links is
 * still queried at once.
 *
 * **A pull-through carries a hop budget and a deadline (work item #195, ADR 2026-10.2drc).** Every `blobreq` says how
 * many phones deep the pull may still go, the receiver counted ([BlobReqContent.pull]), and until when a pull-through is
 * worth making at all ([BlobReqContent.until], on the frame's own clock). A want of our own mints [PULL_BUDGET]; a
 * neighbour that lacks the blob and is asked with `pull = n` wants it with `n − 1` and passes that on, so the pull
 * reaches [PULL_BUDGET] rings past our neighbours and stops; at 0 it only queries and fetches, and an ask with no field
 * (an older build, the iOS port today) counts as 1. A want is kept alive by an ask only when that ask carries at least
 * its own budget ([Want.budget]), so only a root mints liveness and every hop spends some: no loop between echoes, no
 * link that keeps re-forming and no stream of newcomers can keep a want alive once its root stops. Past [Want.until] —
 * a DM's custody window, `PullThroughGate` — nobody pulls it through or makes an echo of it; a holder still serves it.
 * A pull never needed a carrier (a phone in the middle fetches by hash from whoever says HAVE), so the deadline costs
 * reach to a DM's far end two or more hops away past a day; it is the DM's own, the window the mesh carries it in.
 * Past its first lead-less cycles a cycle whose blind asks brought nothing backs off too ([Want.dead]).
 *
 * Any wait on a holder counts its grace from the first look that finds it no longer streaming to us: our copy may sit
 * behind that stream in the stack's buffer (#121), so a grace that ran during the stream would ask past it.
 *
 * **Fast-only (ADR 2026-10.js2p).** An attachment declared over the user's "Ask before large Bluetooth downloads" limit,
 * and not tapped, is wanted *fast-only*: only a neighbour whose copy would ride Wi-Fi Aware — linked over it, or
 * reachable on it so the holder raises the link ([BulkRoute]) — is queried, asked or asked to pull it through, its
 * `blobreq` says so ([BlobReqContent.fastOnly]) and the holder never hands it to Bluetooth (`CompositeMeshTransport`).
 * With no such neighbour nothing is asked and nothing stamped: the want waits for one, a link-up or the tick stepping it
 * again. The flag is the latest local want's ([want], [wantAll]) — the database re-arm re-derives it every tick, and the
 * user's tap re-wants it plain, which steps at once — never a memo of its own.
 *
 * **Proof of a hold (ADR 2026-10.vy27).** A DM photo its recipient holds never goes up to a spool, and the sender's proof
 * is that recipient's own HAVE: [confirmHeld] sends it the same holder query, and the answer to it reaches [onPeerHolds]
 * and nothing else. It asks, serves, wants and stamps nothing.
 *
 * A transfer a link drop cut off resumes rather than restarts (work item #116, ADR 2026-10.wtyc): the link keeps
 * the prefix in [PartialBlobs], every ask names its length as the `blobreq` offset, a holder streams only the
 * rest ([onRequest]), and a spliced file that fails its hash check drops the prefix and asks from byte 0
 * ([onReceived]). A holder that predates the field serves the whole file, which lands as before.
 *
 * **A profile photo's ceiling (#234).** A want made only for a profile photo ([want] with a `ceiling`) is fetched under
 * the photo ceiling: the per-hash cap rides on [PartialBlobs] to whichever link receives the stream, which keeps no byte
 * past it. A holder whose stream passes it, or whose `blobhas` size does, is marked for the hash for the session
 * ([PartialBlobs.markHolder]) and passed over ([passOver]): its ask withdrawn, never queried, asked or asked to pull it
 * again by this want or a later one, while the others are asked as for any failed holder. Neither signal can be checked
 * against the hash before the file is whole, so it speaks for that holder alone; only the photo's author ([Want.author])
 * speaks for the photo, and its signal refuses the hash for the session ([PartialBlobs.refuse]): the want ends
 * ([endRefused]), every ask is withdrawn, and a later photo want for it makes none. The wire cannot stop a feed already
 * running, so each holder's one copy may still cross, and is discarded. Any other want of our own for the same hash — an
 * attachment, a carried frame, a group photo — lifts the cap: the larger ceiling wins. A neighbour's ask moves no ceiling.
 *
 * Pure (no Android/Room): the transport, blob storage, and identity are injected, so the recursion
 * can be unit-tested with [FakeLoopTransport] and a fake [BlobStore].
 */
@Suppress("TooManyFunctions", "LargeClass", "LongParameterList") // one fetch state machine and its two wire doors
class BlobExchange(
    private val transport: MeshTransport,
    private val store: BlobStore,
    private val selfId: suspend () -> String,
    private val onObtained: suspend (hash: String, path: String) -> Unit,
    // The prefixes of transfers a link drop cut off (#116): an ask names how many bytes of the blob we hold.
    private val partials: PartialBlobs,
    // A file spliced onto a kept prefix failed its hash check (the owner counts it).
    private val onSpliceRefused: () -> Unit = {},
    private val newRequestId: () -> String = { FrameId.new() },
    private val now: () -> Long = System::currentTimeMillis,
    // Bounds (overridable so tests can exercise eviction with small values).
    private val maxFetching: Int = MAX_FETCHING,
    private val maxServeMemo: Int = MAX_SERVE_MEMO,
    private val fetchTtlMs: Long = FETCH_TTL_MS,
    // How long an ask has to bring a header in before the next holder is tried ([FIRST_ASK_GRACE_MS]). Injectable so
    // `mesh/lab` can hold one open past any wall-clock stall of its own; production takes the default.
    private val firstAskGraceMs: Long = FIRST_ASK_GRACE_MS,
    // Whether this build queries neighbours for the holders of a blob (ADR 2026-10.6e5t; `BuildConfig.BLOB_QUERY`),
    // and how long it waits for their answers. Off, every neighbour gets ADR 2026-10.f7gg's blind round. Answering is
    // never gated: [onHaveQuery] runs on every build.
    private val holderQuery: HolderQuery = HolderQuery(),
    // Whether this build carries and keeps the pull budget and deadline (ADR 2026-10.2drc). Every build does; off, it plays
    // the iOS port and the builds before #195 for `mesh/lab` — no fields sent, every ask refreshes, every echo pulls.
    private val pullBudgets: Boolean = true,
    // What happened, for the owner's counters (`MeshMetrics.onBlobEvent`).
    private val onEvent: (BlobEvent) -> Unit = {},
    // Where an ask's grace is timed (ADR 2026-10.f7gg): the mesh session's scope, read at each arm. Null (no
    // session, or a rig) leaves the next step to the next want, answer, link-up or tick.
    private val timers: () -> CoroutineScope? = { null },
    // Whom the conversations naming a blob are addressed to, all of them ([addresseesAcross]) — a group's members, a DM's
    // two ends — or null when no message we hold names it (a relay's blob, an avatar) or one naming it is a room's: a
    // holder serves members ahead of carriers (ADR 2026-10.nsr7's member-first amendment).
    private val addressees: suspend (hash: String) -> Set<String>? = { null },
    // Injected logger (the class stays Android-free): DI passes android.util.Log; tests default silent.
    private val log: (String) -> Unit = {},
    // [peer] said, over a short-range link and in answer to a proof query of ours ([confirmHeld]), that it holds [hash]
    // whole: the sender's end of the spool deferral's proof (docs/SPOOL_PROTOCOL.md §9.5, ADR 2026-10.vy27).
    private val onPeerHolds: suspend (hash: String, peer: String) -> Unit = { _, _ -> },
) {
    /**
     * Whether this node queries capable neighbours before it asks ([enabled]), how long it waits for their answers, and
     * when it queries again a neighbour that said it is fetching the blob, or that it asked to pull it through. The
     * waits run on wall time, so `mesh/lab` lengthens them past any stall of its own and moves them with a clock jump;
     * production takes the defaults.
     */
    data class HolderQuery(
        val enabled: Boolean = false,
        val waitMs: Long = QUERY_WAIT_MS,
        val fetchingRecheckMs: Long = FETCHING_RECHECK_MS,
    )

    // Guards fetching + recentlyServed + the query books (all mutated across coroutines); held only for short map
    // ops, never across a suspend send (the send-outside-the-lock methods below).
    private val lock = Any()

    // hash -> the want and where its fetch stands ([Want]) — dedups outbound requests and orders eviction.
    // Insertion-ordered so eviction is oldest-wanted-first; TTL-swept on the last want so a never-arriving fetch is
    // reclaimed. Guarded by [lock].
    private val fetching =
        object : LinkedHashMap<String, Want>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Want>): Boolean =
                (size > maxFetching).also { evicted -> if (evicted && eldest.value.ceiling != null) partials.cap(eldest.key, null) }
        }

    // "hash|nodeId" -> when we last enqueued that blob to that peer. The bound on an unsigned request flood
    // for a blob we hold, and the cover for the composite's fast-link grace, when a serve is held for the
    // NDP and sits on no link yet. A copy already queued or streaming on the link is refused by
    // [MeshTransport.fileInFlightTo] instead, whatever its age. The memo TTL is deliberately shorter than
    // the 60 s re-offer so a serve whose bytes were lost (link died mid-stream) still retries on the next
    // round; a size cap bounds it against a request flood between prunes. Guarded by [lock].
    private val recentlyServed =
        object : LinkedHashMap<String, Long>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = size > maxServeMemo
        }

    // Queries a step decided on and the next [flushQueries] sends: peer -> hashes, so one frame per peer carries every
    // hash a batch of steps queued for it. Empty between entry points. Guarded by [lock].
    private val outbox = LinkedHashMap<String, LinkedHashSet<String>>()

    // Queries sent, by envelope id: whom, when, and which hashes — an answer counts only for a hash its query named
    // (`re`). Capped and pruned at a cycle's age. Guarded by [lock].
    private val sentQueries =
        object : LinkedHashMap<String, SentQuery>(64, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SentQuery>): Boolean = size > MAX_SENT_QUERIES
        }

    // Per-peer answer budget: the bound on an unsigned query flood ([onHaveQuery]). Guarded by [lock].
    private val answerBudget =
        object : LinkedHashMap<String, Bucket>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bucket>): Boolean = size > MAX_ANSWER_BUCKETS
        }

    // Neighbour -> how long its latest answer took to come back (#140): our own outgoing backlog on that link, which
    // is what a wait for its answer, or for an ask's header, is stretched by ([queryWaitFor], [rttOf]). Forgotten when
    // the neighbour links again — a new link, a new queue. Guarded by [lock].
    private val answerRtt =
        object : LinkedHashMap<String, Long>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = size > MAX_ANSWER_BUCKETS
        }

    // Asks for a blob we hold that came in while another Bluetooth copy of it was being fed (ADR 2026-10.g24x): hash ->
    // asker -> its ask. Each blob's fastest is served when no live copy of it is fed on a shared-budget link to anyone
    // else — the feed ended, stalled, or ran past the deadline its first measurement set (ADR 2026-10.nsr7) — and an ask
    // is dropped when its asker unlinks or stops renewing it ([ParkedAsk.keptUntil]). Capped by hash. Guarded by [lock].
    private val parked =
        object : LinkedHashMap<String, LinkedHashMap<String, ParkedAsk>>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, LinkedHashMap<String, ParkedAsk>>): Boolean =
                size > MAX_PARKED_BLOBS
        }

    // The loop that serves [parked] asks as feeds end; null or done when nothing is parked. Guarded by [lock].
    private var parkPoll: Job? = null

    // "hash|peer" -> when a proof query naming it last went to that peer ([confirmHeld]): the floor that keeps a caller's
    // every round from costing a frame. Forgotten when the peer links again. Capped, pruned at the floor. Guarded by [lock].
    private val proofAsked =
        object : LinkedHashMap<String, Long>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>): Boolean = size > MAX_PROOF_QUERIES
        }

    // Proof queries sent, by envelope id: whom, when, and the hashes not yet answered. Apart from [sentQueries], so a
    // proof's answer never steps a want and a want's answer never counts as a proof. Capped, pruned at a cycle's age.
    // Guarded by [lock].
    private val proofQueries =
        object : LinkedHashMap<String, ProofQuery>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ProofQuery>): Boolean = size > MAX_PROOF_QUERIES
        }

    /**
     * Asks for [hash] unless we already hold it or its bytes are streaming in or landing on a link or going into the
     * store right now. A repeat want moves the same fetch on, never a second one. An arriving hash is not even marked:
     * if the transfer dies the database re-arms it on the next tick (`rewantMissingBlobs`), and the memo is never the
     * source.
     *
     * [author] is who posted the frame that names the blob, the one holder we can name; [via] is the neighbour that
     * frame came from; both are ranking hints for neighbours that cannot be queried, kept from the first want that
     * gave them, and [via] is the first asked to pull it through when nobody in reach holds it. [asker] is a neighbour
     * we are pulling it for ([onRequest]): it lacks the bytes, so this cycle neither queries nor asks it, but a later
     * one does — it may be the one path the bytes have to us by then.
     *
     * [fastOnly] keeps the fetch to neighbours whose copy would ride Wi-Fi Aware (ADR 2026-10.js2p, see the class KDoc).
     * A want of our own sets it, whichever way: the latest one wins, so the user's tap (false) lifts a hold at once. An
     * [asker]'s sets it only on a want it creates — a neighbour's ask never widens or narrows a fetch of our own.
     * [approved] is the user's tap: the want is plain from then on, whatever a later want read before the tap says.
     *
     * A want of our own (no [asker]) has [PULL_BUDGET]. Its deadline is [until] when [untilKnown] — the caller read every
     * row naming the hash, so a null there is "no deadline" — or when [until] names one; otherwise it keeps the one the
     * re-arm set ([wantAll]). An [asker]'s carries the budget its ask allows ([budgetOf] of [pull]) and the ask's [until]
     * (#195).
     *
     * [ceiling] is set by a want made only for a profile photo (#234): the most bytes its stream may bring. Such a want of
     * a hash refused as a photo this session ([PartialBlobs.isRefused]) makes nothing, and one of a hash with holders
     * marked over it asks only the others; a want of our own without one lifts it (see the class KDoc).
     */
    suspend fun want(
        hash: String,
        author: String? = null,
        via: String? = null,
        asker: String? = null,
        fastOnly: Boolean = false,
        approved: Boolean = false,
        pull: Int? = null,
        until: Long? = null,
        untilKnown: Boolean = false,
        ceiling: Long? = null,
    ) {
        if (ceiling != null && partials.isRefused(hash)) {
            log("blob refuse ${hash.take(LOG_HASH_CHARS)}…: over its ceiling of ${ceiling}B this session, not wanted")
            return
        }
        // The tap sticks to a want whose bytes are on the way too: if that copy is cut, the next step asks plain.
        if (approved) synchronized(lock) { fetching[hash]?.let { setFastOnly(it.also { w -> w.approved = true }, false, now()) } }
        if (onTheWayOrHeld(hash)) return
        val budget = if (asker == null) PULL_BUDGET else budgetOf(pull)
        val reach =
            Reach(
                budget,
                until.takeIf { pullBudgets },
                rederived = untilKnown && asker == null,
                fieldless =
                    asker != null && pull == null,
            )
        recordWant(hash, now(), author, via, asker, fastOnly, approved, reach, ceiling)
        stepping { step(hash, linkedNow()) }
    }

    /**
     * [want] for each of [wanted] (hash → author), stepped together so their queries share one frame per neighbour;
     * those in [fastOnly] are wanted fast-only, every other plain (ADR 2026-10.js2p), and those in [until] are pulled
     * through only until then ([Want.until], `PullThroughGate`, #195). The database re-arm
     * (`MeshManager.rewantMissingBlobs`) runs at every link-up, ahead of [reask], and is what re-derives both.
     */
    suspend fun wantAll(
        wanted: List<Pair<String, String?>>,
        fastOnly: Set<String> = emptySet(),
        until: Map<String, Long> = emptyMap(),
    ) {
        if (wanted.isEmpty()) return
        val linked = linkedNow()
        stepping { stepEach(wanted, linked, fastOnly, until) }
    }

    /** [wantAll]'s body: each of [wanted] recorded and stepped, the queries left for the caller to flush. */
    private suspend fun stepEach(
        wanted: List<Pair<String, String?>>,
        linked: List<Candidate>,
        fastOnly: Set<String>,
        until: Map<String, Long>,
    ) {
        for ((hash, author) in wanted) {
            if (onTheWayOrHeld(hash)) continue
            recordWant(
                hash,
                now(),
                author,
                via = null,
                asker = null,
                fastOnly = hash in fastOnly,
                reach =
                    Reach(
                        PULL_BUDGET,
                        until[hash]
                            .takeIf {
                                pullBudgets
                            },
                        rederived = true,
                    ),
            )
            step(hash, linked)
        }
    }

    /**
     * A neighbor linked, or the 60 s re-offer tick came round: every blob we still lack takes its next step — the
     * query owed to a neighbour new to this cycle, the next holder once an ask's grace is up, the widened blind ask, or
     * the next cycle once [CYCLE_MS] has passed (handles late-joining holders, and is the re-ask that makes the
     * hop-by-hop walk move).
     *
     * A neighbour streaming a file in to us is not asked again for a blob it was asked for before (#121). A link
     * delivers in order, so whatever that peer has already fed us sits behind that file in the stack's buffer, and a
     * holder whose feed outpaces the drain can have fed a whole photo there: it no longer counts that serve as in
     * flight, so a second ask buys a second copy. Every serve it would make now queues behind that stream anyway.
     */
    suspend fun reask() {
        // Drop the marks whose bytes we have since obtained, then step the rest. [onReceived] clears its own, but the
        // mesh is not the only plane that can satisfy a want: the spool saves an attachment and fires
        // `onAttachmentObtained` (`ScopeSync.fetchAttachment`), and a direct avatar push is ingested by
        // `InboundPipeline.onAvatarReceived` — neither routes through [onReceived], so without this the blob is
        // re-requested on every link-up for the whole [FETCH_TTL_MS] window and the neighbor re-serves bytes we
        // already hold. A hash whose bytes are on the way stays in the memo (the transfer may still die) but is not
        // asked for: the re-ask against a slow BLE transfer is what bought a second copy from every holder (#79).
        // "On the way" includes the store's own last step: past the link's end, a blob is screened before it is held
        // (#109), and a tick in that classification would otherwise ask for it again.
        val wanted = snapshotFetching()
        if (wanted.isEmpty()) return
        val linked = linkedNow()
        stepping { wanted.forEach { step(it, linked) } }
    }

    /**
     * Neighbours that just linked, or linked again: none of them holds an ask or a query of ours, since one sent
     * before may have died with the old link. Each is open to every cycle again — queried at once if it can be, its
     * old answer and failure forgotten — and a fetch or blind first ask that went to one of them starts over. The
     * next [want] or [reask] queries or asks them — before #136 every link-up re-asked its newcomer at once, and this
     * keeps that (ADR 2026-10.f7gg). Call it ahead of the link-up's wants and re-ask.
     */
    fun linked(nodeIds: Collection<String>) {
        if (nodeIds.isEmpty()) return
        val ids = nodeIds.toSet()
        synchronized(lock) {
            answerRtt.keys.removeAll(ids)
            // A proof query sent on the old link may have died with it: the next confirmHeld queries the new one at once.
            proofAsked.keys.removeAll { it.substringAfterLast('|') in ids }
            fetching.values.forEach { want ->
                want.pullers.removeAll(ids)
                want.blindAsked.removeAll(ids)
                // A newcomer that lacks it is asked to pull it through at once, once per link-up, in this cycle (#195).
                want.freshLinks += ids
                want.pulled.removeAll(ids)
                want.pullRechecks.keys.removeAll(ids)
                want.failed.removeAll(ids)
                want.reasked.removeAll(ids)
                ids.forEach {
                    want.queried.remove(it)
                    want.answers.remove(it)
                }
                if (want.fetch?.peer in ids) want.fetch = null
            }
        }
    }

    /**
     * Neighbours whose links went down: every blob whose ask or hold is on one of them takes its next step now, so the
     * next holder is asked at once rather than at the next want, answer, timer or tick (#140). Nothing else moves.
     */
    suspend fun unlinked(nodeIds: Collection<String>) {
        if (nodeIds.isEmpty()) return
        val ids = nodeIds.toSet()
        val affected =
            synchronized(lock) {
                // A link-up that went again pulls nobody later: the newcomer is one only while its link stands.
                fetching.values.forEach { it.freshLinks.removeAll(ids) }
                fetching.filterValues { it.fetch?.peer in ids }.keys.toList()
            }
        if (affected.isEmpty()) return
        val linked = linkedNow()
        stepping { affected.forEach { step(it, linked) } }
    }

    /**
     * The Wi-Fi Aware plane linked [nodeIds] — neighbours already linked over Bluetooth among them, whose gaining the
     * fast link changes no neighbour set and so never reaches [linked] (`MeshManager.watchFastLinks`). Every fast-only
     * want takes its next step now, which asks the newly linked holders at once ([fastLinkSeen]) rather than at the
     * tick: an ephemeral data path may be gone by then (ADR 2026-10.js2p's review). Nothing else moves.
     */
    suspend fun fastLinkUp(nodeIds: Collection<String>) {
        if (nodeIds.isEmpty()) return
        // Handed to each want as edges of their own: a link that went down and up again between two steps leaves no
        // difference between their snapshots, and would be missed (round 4 of the review).
        val held =
            synchronized(lock) {
                fetching
                    .filterValues { it.fastOnly }
                    .onEach { (_, w) -> w.fastEdges += nodeIds }
                    .keys
                    .toList()
            }
        if (held.isEmpty()) return
        val linked = linkedNow()
        stepping { held.forEach { step(it, linked) } }
    }

    /**
     * One step of [hash]'s fetch against the [linked] neighbours: the queries [decide] owes go into the outbox (the
     * caller flushes them), its asks go out, and its next wake is timed so a grace, a wait for answers or a stream's
     * end is acted on without waiting for the tick.
     */
    private suspend fun step(
        hash: String,
        linked: List<Candidate>,
    ) {
        val passed = passOver(hash)
        if (endRefused(hash)) return
        if (onTheWay(hash)) return
        if (store.has(hash)) {
            clearFetching(hash)?.let { withdrawAsks(hash, it, from = null) }
            partials.drop(hash) // obtained off-mesh: nothing left to resume
            return
        }
        val kept = partials.length(hash)
        // A marked holder is not a holder for this want: gone, as far as it is concerned, so an ask on it fails over.
        val pool = if (passed.isEmpty()) linked else linked.filterNot { it.id in passed }
        val plan = synchronized(lock) { fetching[hash]?.let { decideMarking(it, pool, kept) }?.also { queue(hash, it) } } ?: return
        if (plan.asks.isNotEmpty()) {
            val req = blobRequest(selfId(), hash, plan.fastOnly, plan.pull, plan.until)
            for (ask in plan.asks) {
                // A holder serving a large attachment arms the Wi-Fi Aware link itself; only the larger node id of a
                // pair initiates, so our half of the mark is what brings it up when that is us.
                if (ask.arm) transport.expectBulkTransfer(ask.to.nodeId)
                transport.send(req, ask.to) // outside the lock
            }
            log(askLine(hash, plan))
        }
        plan.cancels.forEach { withdrawAsk(hash, it) }
        plan.events.forEach(onEvent)
        plan.wake?.let { armTimer(hash, it) }
    }

    /**
     * The holders [hash]'s want passes over (#234): when only a profile photo wants it, those marked for it this session
     * ([PartialBlobs.markHolder]) — a link aborted or skipped their stream past the ceiling, or they answered a size past
     * it ([recordAnswers]). A newly marked one with our ask on it gets a `blobcancel` (its queued copy is dropped; a feed
     * already running ends on its own, discarded). When the want's author is among them the hash itself is refused, and
     * [endRefused] ends the want. The want's own [Want.passedOver] is returned with them, so a mark the session's bounded
     * set has since dropped never lets this want ask that holder again. Empty for every other want.
     */
    private suspend fun passOver(hash: String): Set<String> {
        val (marked, fresh, withdrawn) =
            synchronized(lock) {
                val w = fetching[hash]?.takeIf { it.ceiling != null } ?: return emptySet()
                val marked = partials.markedHolders(hash)
                if (marked.isEmpty() && w.passedOver.isEmpty()) return emptySet()
                if (w.author in marked) partials.refuse(hash)
                val fresh = marked.filter { it !in w.passedOver && w.passedOver.size < MAX_PASSED_OVER && w.passedOver.add(it) }
                // Withdrawn whatever it answered: unlike a holder that said HAVE after our ask, its copy is unwanted.
                val withdrawn =
                    fresh.filter { id ->
                        val asked = w.askedAt[id] ?: return@filter false
                        (w.withdrawnAt[id] != asked).also { w.withdrawnAt[id] = asked }
                    }
                Triple(marked + w.passedOver, fresh, withdrawn)
            }
        for (holder in fresh) {
            onEvent(BlobEvent.HolderOverCeiling)
            log("blob pass ${hash.take(LOG_HASH_CHARS)}… $holder: over its ceiling → next")
        }
        withdrawn.forEach { withdrawAsk(hash, it) }
        return marked
    }

    /**
     * Ends [hash]'s want when only a profile photo wants it and the hash was refused over its ceiling (#234): its author
     * streamed or answered past it ([passOver]), or a held copy was found over it (`InboundPipeline`). Every neighbour
     * still asked gets a `blobcancel`, and nobody else is asked; a feed already running ends on its own, its bytes
     * discarded. True when it ended the want. Checked at every step, so the step that would widen to the next holder
     * ends it instead.
     */
    private suspend fun endRefused(hash: String): Boolean {
        val ended =
            synchronized(lock) {
                val w = fetching[hash]?.takeIf { it.ceiling != null && partials.isRefused(hash) } ?: return false
                fetching.remove(hash)
                partials.cap(hash, null)
                w
            }
        partials.drop(hash) // a prefix of it is no use to anyone now
        withdrawAsks(hash, ended, from = null)
        onEvent(BlobEvent.OverCeiling)
        log("blob refuse ${ended.hash8}… over its ceiling of ${ended.ceiling}B: want ended")
        return true
    }

    /**
     * [decide] at the clock's now, noting a fast-only ask in [w]'s cycle: a cycle that asked for a fast-only copy and
     * still wants the blob at its end is dead ([Want.dead]), and backs off. Under [lock].
     */
    private fun decideMarking(
        w: Want,
        linked: List<Candidate>,
        kept: Long,
    ): Plan = decide(w, linked, kept, now()).also { if (w.fastOnly && it.asks.isNotEmpty()) w.fastAsked = true }

    /** Moves the queries [plan] owes into the outbox. Under [lock]. */
    private fun queue(
        hash: String,
        plan: Plan,
    ) = plan.query.forEach { outbox.getOrPut(it) { LinkedHashSet() } += hash }

    /**
     * Where [w]'s fetch stands at [t], against the [linked] neighbours, and the one mutation that moves it on — the
     * queries and asks it decides on are marked here, under [lock], so two steps racing never send the same one twice.
     * Pure: no sends, no suspends. [kept] is the prefix we hold of the blob, so the plane is chosen by what is left.
     */
    private fun decide(
        w: Want,
        linked: List<Candidate>,
        kept: Long,
        t: Long,
    ): Plan {
        val p = Plan(fastOnly = w.fastOnly, pull = w.budget, until = w.until)
        // A fast-only want sees only the neighbours whose copy would ride Wi-Fi Aware (ADR 2026-10.js2p).
        val eligible = if (w.fastOnly) linked.filter { it.route != BulkRoute.None } else linked
        // Ahead of the fetch's wait, so an ask outstanding on another holder never consumes the edge.
        if (w.fastOnly) fastLinkSeen(w, eligible)
        w.fetch?.let { f ->
            // An ask made before the want turned fast-only, on a neighbour that would send over Bluetooth: let it go.
            if (eligible.none { it.id == f.peer } && linked.any { it.id == f.peer }) {
                w.over(f, "fast-only", p)
            } else {
                // Queries go out mid-fetch too (a newcomer, a fetching neighbour's recheck): fresh answers for the fallback.
                owedQueries(w, capableOf(eligible.filter { it.id !in w.pullers }), t, p, stuckOn = stuckOn(f, eligible))
                fetchWait(w, f, eligible, kept, t, p)?.let { return p.waitFor(it) }
            }
        }
        // A cycle closes only with no ask outstanding, so an ask's grace always runs out inside the cycle it began in.
        if (w.cycleDue(t)) openCycle(w, t)
        val live = eligible.filter { it.id !in w.pullers }
        if (w.fastOnly && live.isEmpty()) {
            // Nobody to ask over Wi-Fi Aware: ask nobody and stamp nothing. A link-up, a route coming up (the tick
            // steps it) or the user's tap moves it on; Bluetooth holders are never asked blind in its place.
            p.events += BlobEvent.FastOnlyHeld
            if (!w.fastHeld) log("blob fast-only ${w.hash8}… held: no Wi-Fi Aware holder among ${linked.size} linked")
            w.fastHeld = true
            return p
        }
        w.fastHeld = false
        val capable = capableOf(live)
        owedQueries(w, capable, t, p)
        if (!askHolder(w, capable, kept, t, p) && !askBlind(w, live, capable, t, p)) pullThrough(w, capable, t, p)
        return p
    }

    /**
     * A Wi-Fi Aware link coming up to an [eligible] neighbour, per neighbour: the change a fast-only want's backoff waits
     * for ([Want.dead]). Each newly linked one is forgotten as this cycle knew it — its failure, its answer, its query,
     * its blind ask — so it is queried or asked again at once over the link, whatever else is outstanding. Nothing else
     * moves: no fresh cycle and no cleared dead count, because Wi-Fi Aware links flap on every custody sync, and a reset
     * per sync re-queried and re-armed every never-linking reachable holder at the sync rate (round 3 of ADR
     * 2026-10.js2p's review). The holder our own ask is on is not an edge either: its data path coming up is that ask
     * being served, and forgetting it would query or widen past it once. A neighbour already linked over Bluetooth that
     * gains the fast link changes no neighbour set, so [linked] never sees it: this is how it is seen, on the next step —
     * the tick, or [fastLinkUp] at the edge itself.
     */
    private fun fastLinkSeen(
        w: Want,
        eligible: List<Candidate>,
    ) {
        val linkedNow = eligible.filter { it.route == BulkRoute.Linked }.mapTo(HashSet()) { it.id }
        // The first look is a baseline, not an edge: what a fresh want finds linked it never knew otherwise. The edges
        // [fastLinkUp] handed over count too, for the neighbours linked now: a flap between two steps is still an edge.
        val seen = w.fastLinked?.let { linkedNow - it }.orEmpty()
        val gained = (seen + (w.fastEdges intersect linkedNow)) - setOfNotNull(w.fetch?.peer)
        w.fastEdges.clear()
        w.fastLinked = linkedNow
        if (gained.isEmpty()) return
        w.failed.removeAll(gained)
        w.blindAsked.removeAll(gained)
        w.reasked.removeAll(gained)
        w.pulled.removeAll(gained)
        gained.forEach {
            w.answers.remove(it)
            w.queried.remove(it)
        }
    }

    /**
     * Whether an ask of [c] for [w]'s blob arms the Wi-Fi Aware link on our side too: a fast-only ask of a reachable
     * neighbour, which has no Bluetooth copy to fall back on (ADR 2026-10.js2p). The first ask and a holder's fetch arm
     * by their own rule.
     */
    private fun armsFastOnly(
        w: Want,
        c: Candidate,
    ): Boolean = w.fastOnly && c.route == BulkRoute.Reachable

    /** The neighbours among [live] this build queries: those whose link says they answer, while querying is on. */
    private fun capableOf(live: List<Candidate>): List<Candidate> = if (holderQuery.enabled) live.filter { it.capable } else emptyList()

    /**
     * The holder [f] waits on while what it streams us has more than [STREAM_SWITCH_MS] left, or null: the one wait in
     * which another neighbour's fresh HAVE moves the fetch ([idleHolderPast]).
     */
    private fun stuckOn(
        f: Fetch,
        linked: List<Candidate>,
    ): String? =
        linked
            .firstOrNull { it.id == f.peer }
            ?.takeIf { c -> c.streaming && (c.streamLeftMs ?: 0L) > STREAM_SWITCH_MS }
            ?.id

    /**
     * The queries [w] owes this cycle: each capable neighbour once, and again once its recheck is due ([recheckAt]) — a
     * recheck of one we asked to pull it through is counted against [PULL_RECHECKS]. [stuckOn] is the holder a fetch
     * waits on behind a long stream ([stuckOn]), while it is one.
     */
    private fun owedQueries(
        w: Want,
        capable: List<Candidate>,
        t: Long,
        p: Plan,
        stuckOn: String? = null,
    ) {
        // A recheck due within the timer's coalescing window goes now: the timer stepped it early to share this frame.
        val owed =
            capable.filter { c ->
                c.id !in w.queried || recheckAt(w, c.id, stuckOn)?.let { t + TIMER_COALESCE_MS >= it } == true
            }
        for (c in owed) {
            val recheck = c.id in w.queried
            val counted = w.answers[c.id]?.state != BlobHasItem.FETCHING && !lackedWhileStuck(w, c.id, stuckOn)
            if (recheck && counted) w.pullRechecks.merge(c.id, 1, Int::plus)
            w.queried[c.id] = t
            p.query += c.id
        }
    }

    /**
     * When neighbour [id] is owed another query this cycle, or null if it is not: one that said it is fetching the blob
     * [HolderQuery.fetchingRecheckMs] after it said so, and one we asked to pull it through, that has not said it holds
     * it, as long after the pull or its last query, at most [PULL_RECHECKS] times — it holds the blob the moment its own
     * fetch lands, and the next cycle may be minutes off ([Want.cycleGap]). A trial's line waited 36 s on the tick. Only
     * in the first [PULL_RECHECK_DEAD_CYCLES] cycles of a dead streak, where a pull through more than one hop lands: past
     * them the blob is one nobody reaches, and its rechecks were most of what it cost (the 2026-10-05 re-run). And,
     * while the fetch waits on [stuckOn] behind a long stream, one that said it lacked the blob, as long after it said so
     * ([lackedWhileStuck]).
     */
    private fun recheckAt(
        w: Want,
        id: String,
        stuckOn: String? = null,
    ): Long? {
        val at = w.queried[id] ?: return null
        val state = w.answers[id]?.state
        val pulling =
            id in w.pulled && id !in w.failed && state !in HELD && w.deadCycles < PULL_RECHECK_DEAD_CYCLES &&
                w.pullRechecks.getOrDefault(id, 0) < PULL_RECHECKS
        return when {
            state == BlobHasItem.FETCHING -> at + holderQuery.fetchingRecheckMs
            lackedWhileStuck(w, id, stuckOn) -> at + holderQuery.fetchingRecheckMs
            pulling -> maxOf(at, w.askedAt[id] ?: at) + holderQuery.fetchingRecheckMs
            else -> null
        }
    }

    /*
     * A NONE is otherwise final for the fetch: a cycle opens only with no ask outstanding, so it is never asked again
     * while one is. But a neighbour that lacked the blob when we asked often holds it a minute later: two members fetch
     * a new post at once, and the one queued behind a long stream never learns the other's copy landed (the iPhone smoke
     * of 2026-10-07: the P8 sat 8 min behind a DM file while the P9 held the photo idle, answering NONE from 0.4 s before
     * its own fetch began). Only while stuck, since only then would its HAVE move the fetch.
     */

    /** Whether [id] is rechecked as one that lacked the blob while the fetch waits on [stuckOn] — see above. */
    private fun lackedWhileStuck(
        w: Want,
        id: String,
        stuckOn: String?,
    ): Boolean = stuckOn != null && id != stuckOn && id !in w.failed && w.answers[id]?.state == BlobHasItem.NONE

    /**
     * How long the outstanding fetch [f] still has, or null once it is over: failed, or cleared so the holder is asked
     * again. [c] is its holder as linked now, null once the link is gone (it can serve nothing on it). While the holder
     * streams anything to us, our copy may be queued behind it, so the grace counts from the first quiet look — never
     * from the last look during the stream, which once asked past a header 2 s from landing. A queried holder whose
     * grace is up is queried once more: still sending, it gets another grace; answering it holds the blob to a query
     * sent after our ask, our ask never reached it and it is asked again, once; silent, or still sending after
     * [MAX_CONFIRMS], it is held to its floor ([hold]); otherwise the next holder is tried. A blind first ask whose pick
     * said it holds the blob is confirmed the same way rather than widened (#140): it is a holder with our ask on it.
     */
    private fun fetchWait(
        w: Want,
        f: Fetch,
        linked: List<Candidate>,
        kept: Long,
        t: Long,
        p: Plan,
    ): Long? {
        val c = linked.firstOrNull { it.id == f.peer } ?: return w.over(f, if (f.peer in w.passedOver) "passed over" else "link gone")
        if (c.streaming) return streamWait(w, f, c, linked, p)
        val quiet = f.quietSince ?: t.also { f.quietSince = it }
        (quiet + f.graceMs - t).takeIf { it > 0 }?.let { return it }
        return graceUp(w, f, c, kept, t, p)
    }

    /*
     * While [c] streams another file to us, [fetchWait] waits it out — our copy may be queued behind it — except in two
     * cases (the cell (d) device run of 2026-10-07: an asker sat 12 min on a neighbour feeding it an older carrier copy at
     * about 2 KB/s, while another held the blob and fed nothing).
     */

    /** [fetchWait] while [c] streams another file to us: wait it out, or let [c] go — see above. */
    private fun streamWait(
        w: Want,
        f: Fetch,
        c: Candidate,
        linked: List<Candidate>,
        p: Plan,
    ): Long? {
        if (askedLacking(w, c)) {
            // Not a fallback: it may hold the blob soon, and its HAVE asks it again, this time for real.
            w.fetch = null
            log("blob drop ${f.hash8}… ask on ${c.id}: it lacked the blob, nothing of ours is queued")
            return null
        }
        idleHolderPast(w, c, linked)?.let { why -> return w.over(f, why, p).also { w.failed += c.id } }
        f.quietSince = null
        return minOf(STREAM_POLL_MS, f.graceMs)
    }

    /**
     * Whether our ask found [c] lacking the blob: an answer saying so came in after the ask. A neighbour that lacks a blob
     * remembers no asker (`onRequest` pulls it through, and the asker asks again), so nothing of ours is queued behind
     * what it streams.
     */
    private fun askedLacking(
        w: Want,
        c: Candidate,
    ): Boolean {
        val a = w.answers[c.id] ?: return false
        val asked = w.askedAt[c.id] ?: return false
        return a.state !in HELD && a.receivedAt >= asked
    }

    /**
     * Why [w]'s fetch leaves [c], or null to wait: what [c] streams has more than [STREAM_SWITCH_MS] left, and another
     * neighbour holds the blob and feeds nothing. Our copy, queued or not, waits that long here and none there; one
     * queued here may still come, a second copy a long wait is worth.
     */
    private fun idleHolderPast(
        w: Want,
        c: Candidate,
        linked: List<Candidate>,
    ): String? {
        val left = c.streamLeftMs?.takeIf { it > STREAM_SWITCH_MS } ?: return null
        val idle =
            linked.firstOrNull { o ->
                val a = w.answers[o.id]
                o.id != c.id && !o.streaming && o.id !in w.failed && a?.state == BlobHasItem.HAVE && a.load == 0
            } ?: return null
        return "streaming another file ~$left ms, ${idle.id} idle"
    }

    /** [fetchWait] once [f]'s grace is up and [c] streams nothing to us: confirm, hold, or end it — see there. */
    private fun graceUp(
        w: Want,
        f: Fetch,
        c: Candidate,
        kept: Long,
        t: Long,
        p: Plan,
    ): Long? {
        val queryable = c.capable && holderQuery.enabled
        return when {
            f.tier == Tier.Pending -> {
                w.over(f, "floor", p)
            }

            f.tier == Tier.Blind && !(queryable && w.answers[c.id]?.state in HELD) -> {
                w.over(f, null, p)
            }

            f.tier == Tier.Blind -> {
                // The round is spent on it: a fallback from here asks the next holder, not a second first pick.
                w.blindWidened = true
                f.tier = tierOf(w, c, kept)
                confirmWait(w, f, c, t, p)
            }

            !queryable -> {
                w.over(f, "grace", p)
            }

            f.confirms >= MAX_CONFIRMS -> {
                hold(w, c, "sending", t) ?: w.over(f, "grace", p)
            }

            else -> {
                confirmWait(w, f, c, t, p)
            }
        }
    }

    /**
     * Holds [w]'s fetch on [c] until our last ask's serve-memo floor, when it was asked inside it, or until the copy a
     * SENDING answer said should land by then has had its slack (ADR 2026-10.nsr7): asking the next holder now buys a
     * second copy whenever the first one's is only late (#140). A `blobcancel` reaches only a holder that reads one, and
     * never stops a copy already streaming, so the floor stays (shortening it is a decision of its own). The hold ends
     * early when [c]'s link goes ([fetchWait]'s "link gone") or the bytes arrive; at the floor the next holder is asked.
     * Null when there is no floor left to hold on.
     */
    private fun hold(
        w: Want,
        c: Candidate,
        why: String,
        t: Long,
    ): Long? {
        // A SENDING answer that said when our copy should land holds us that long too (ADR 2026-10.nsr7).
        val promised = w.answers[c.id]?.etaAt?.let { it + HOLD_SLACK_MS - t }
        val floor = listOfNotNull(w.askedAt[c.id]?.let { it + SERVE_MEMO_MS - t }, promised).maxOrNull()?.takeIf { it > 0 } ?: return null
        w.fetch = Fetch(c.id, w.hash8, Tier.Pending, t, floor)
        log("blob hold ${w.hash8}… on ${c.id} ($why), floor $floor ms")
        return floor
    }

    /** [fetchWait] once [f]'s grace is up: query its holder again, or read what it answered — see there. */
    private fun confirmWait(
        w: Want,
        f: Fetch,
        c: Candidate,
        t: Long,
        p: Plan,
    ): Long? {
        val sent =
            f.confirmAt ?: run {
                f.confirmAt = t
                f.confirms++
                w.answers.remove(c.id)
                w.queried[c.id] = t
                p.query += c.id
                p.events += BlobEvent.Confirm
                log("blob confirm ${f.hash8}… → ${c.id}")
                return queryWaitFor(c.id)
            }
        val a = w.answers[c.id]
        val wait = queryWaitFor(c.id)
        return when {
            a == null && t - sent < wait -> {
                sent + wait - t
            }

            a?.state == BlobHasItem.SENDING -> {
                f.quietSince = t
                f.confirmAt = null
                f.graceMs
            }

            a?.state == BlobHasItem.HAVE && c.id !in w.reasked -> {
                w.reasked += c.id
                w.fetch = null
                null
            }

            // Silence on a link that is still up is a late answer, not a no: hold on the ask we made (#140).
            a == null -> {
                hold(w, c, "confirm silent", t) ?: w.over(f, "confirm silent", p)
            }

            else -> {
                w.over(f, "confirm ${stateLabel(a.state)}", p)
            }
        }
    }

    /**
     * Asks the best capable neighbour that said it holds the blob, or waits on one already sending it — true when [w]
     * is decided for this step (asked, or waiting for answers still due, a holder's memo floor, or a blind widening's
     * grace). The choice waits for every answer unless the best so far rides a live Wi-Fi Aware link or is the author, and
     * never asks past a holder whose ask of ours still stands inside its floor (#140): that one's copy may only be late.
     */
    private fun askHolder(
        w: Want,
        capable: List<Candidate>,
        kept: Long,
        t: Long,
        p: Plan,
    ): Boolean {
        val holders = capable.filter { it.id !in w.failed && w.answers[it.id]?.state in HELD }
        holders.firstOrNull { w.answers.getValue(it.id).state == BlobHasItem.SENDING }?.let { c ->
            val f = Fetch(c.id, w.hash8, Tier.Sending, t, graceFor(w, c, kept))
            w.fetch = f
            log("blob wait ${w.hash8}… on ${c.id} (sending), grace ${f.graceMs} ms")
            p.waitFor(f.graceMs)
            return true
        }
        val answering = capable.filter { it.id !in w.answers && t - w.queried.getValue(it.id) < queryWaitFor(it.id) }
        val widenGate = w.blindWidenedAt?.let { it + firstAskGraceMs - t }?.takeIf { it > 0 }
        val (askableHolders, asked) = holders.partition { askable(w, it, t) }
        // A holder our ask of still stands inside its floor holds the choice: its copy may only be late (#140).
        val best = askableHolders.minWithOrNull(byTier(w, kept))?.takeIf { asked.isEmpty() }
        if (best != null && widenGate == null && answersIn(w, best, kept, answering)) {
            val tier = tierOf(w, best, kept)
            w.fetch = Fetch(best.id, w.hash8, tier, t, graceFor(w, best, kept))
            w.askedAt[best.id] = t
            p.ask(best.peer, arm = tier == Tier.FastArm, line = "fetch")
            p.why = "${tier.label}, load ${w.answers.getValue(best.id).load}"
            p.events += BlobEvent.Fetch
            p.waitFor(w.fetch!!.graceMs)
            return true
        }
        if (holders.isEmpty() && answering.isEmpty()) return false
        val floors = holders.mapNotNull { c -> w.askedAt[c.id]?.let { it + SERVE_MEMO_MS - t } }
        val answersDue = answering.map { w.queried.getValue(it.id) + queryWaitFor(it.id) - t }
        (floors + answersDue + listOfNotNull(widenGate)).filter { it > 0 }.minOrNull()?.let { p.waitFor(it) }
        return true
    }

    /**
     * ADR 2026-10.f7gg's round over the neighbours nobody can query — no [Protocol.CAP_BLOB_HAVE], or queried and still
     * silent past the wait: one blind ask of the likeliest, then every other at once once its grace is up. Never one by
     * one: without a negative answer, every non-holder ahead of a holder would cost a whole grace. True when it asked.
     */
    private fun askBlind(
        w: Want,
        live: List<Candidate>,
        capable: List<Candidate>,
        t: Long,
        p: Plan,
    ): Boolean {
        // A capable neighbour that answered said where it stands; one still silent here is past its wait.
        val answered = capable.mapTo(HashSet()) { it.id }.filterTo(HashSet()) { it in w.answers }
        // A blind ask carries the want's budget and deadline like any other (#195): to a neighbour that lacks the blob it
        // is a pull, and one with no budget left or past its deadline is a fetch only.
        val blind = live.filter { it.id !in answered && it.id !in w.blindAsked && !(it.streaming && it.id in w.askedAt) }
        if (blind.isEmpty()) return false
        if (!w.blindWidened) {
            val pick = blind.minWith(compareBy({ rankOf(w, it) }, { it.id }))
            // A holder that can raise the Wi-Fi Aware link waits for it before it falls back to Bluetooth.
            val grace =
                firstAskGraceMs + rttOf(pick.id) + if (pick.route == BulkRoute.Reachable) CompositeMeshTransport.BULK_GRACE_MS else 0L
            w.blindAsked += pick.id
            w.askedAt[pick.id] = t
            w.fetch = Fetch(pick.id, w.hash8, Tier.Blind, t, grace)
            p.ask(pick.peer, arm = pick.route == BulkRoute.Reachable, line = "first")
            p.why = rankOf(w, pick).label
            p.events += BlobEvent.BlindAsk
            p.waitFor(grace)
            return true
        }
        w.blindWidenedAt = t
        blind.forEach {
            w.blindAsked += it.id
            w.askedAt[it.id] = t
            p.ask(it.peer, arm = armsFastOnly(w, it), line = "widened")
        }
        p.events += BlobEvent.BlindAsk
        return true
    }

    /**
     * Nobody in reach holds it. Unless a neighbour is fetching it, the neighbours that said they lack it are asked to
     * pull it through — the hop the frame came from first, every one of them from the next empty cycle on, or at once
     * when the hop is not among them. Either is queried again shortly ([recheckAt]), and the next cycle (the tick's)
     * queries everyone again.
     *
     * Nobody is asked ([withholdPull]) when the want has no budget left ([Want.budget]) or is past its deadline
     * ([Want.until], #195). The cycle is dead all the same, and backs off; the wait for a FETCHING neighbour's recheck
     * stands. A neighbour that linked in this cycle is pulled at once, mid-cycle, by any want that may pull
     * ([pullNewcomers]).
     */
    private fun pullThrough(
        w: Want,
        capable: List<Candidate>,
        t: Long,
        p: Plan,
    ) {
        val fetching = capable.any { w.answers[it.id]?.state == BlobHasItem.FETCHING }
        val lacking = capable.filter { c -> w.answers[c.id]?.state.let { it != null && it !in HELD && it != BlobHasItem.FETCHING } }
        if (!fetching) pullOrWithhold(w, lacking, t, p)
        capable.mapNotNull { recheckAt(w, it.id) }.minOrNull()?.let { p.waitFor((it - t).coerceAtLeast(1)) }
        // No timer for the next cycle: the 60 s tick ([reask]) or a link-up opens it, every hash in one step and its
        // queries in one frame per neighbour. A timer per hash came due a few ms apart and sent one-hash queries that
        // tripped the holder's budget (the bluetooth-reviewer's finding).
    }

    /**
     * [pullThrough] with no neighbour fetching the blob: every one of [lacking] asked to pull it through, once a cycle
     * when the want may; else the newcomers among them ([pullNewcomers]); else the pull withheld and said so.
     */
    private fun pullOrWithhold(
        w: Want,
        lacking: List<Candidate>,
        t: Long,
        p: Plan,
    ) {
        val due = w.pulled.isEmpty() && lacking.isNotEmpty()
        val withheld = w.pullWithheldWhy(t, pullBudgets)
        when {
            withheld != null -> if (due) withholdPull(w, lacking, withheld, p)
            due -> askToPull(w, lacking, t, p)
            else -> pullNewcomers(w, lacking, t, p)
        }
    }

    /**
     * [pullThrough]'s pull of the neighbours among [lacking] that linked in this cycle and are not pulled in it
     * ([Want.freshLinks], #195) — at once, so a want deep in its backoff does not leave a newcomer for hours. Once per
     * link-up; a link that went since, or a cycle that closed, pulls nobody later. Whatever it asks spends a hop of the
     * want's budget, so a newcomer's ask back can never keep the want alive.
     */
    private fun pullNewcomers(
        w: Want,
        lacking: List<Candidate>,
        t: Long,
        p: Plan,
    ) {
        val fresh = lacking.filter { it.id in w.freshLinks && it.id !in w.pulled }
        if (fresh.isNotEmpty()) askToPull(w, fresh, t, p, newcomers = true)
    }

    /**
     * [pullThrough]'s asks: the hop the frame came from alone in the first empty cycle, else every one of [lacking].
     * [newcomers] asks are [pullNewcomers]': they leave the cycle's schedule as it was.
     */
    private fun askToPull(
        w: Want,
        lacking: List<Candidate>,
        t: Long,
        p: Plan,
        newcomers: Boolean = false,
    ) {
        val hop = lacking.firstOrNull { it.id == w.via }
        val to = if (hop != null && w.emptyCycles == 0 && !newcomers) listOf(hop) else lacking
        // The cycle's first pull: its rechecks run out inside the cycle. A newcomer's pull leaves the cycle where it was.
        if (w.pulledAt == null && !newcomers) w.pulledAt = t
        to.forEach {
            w.pulled += it.id
            w.freshLinks -= it.id
            w.askedAt[it.id] = t
            p.ask(it.peer, arm = armsFastOnly(w, it), line = "pull")
        }
        p.why =
            if (newcomers) {
                "newcomer"
            } else if (to.size == 1 && to[0] === hop) {
                "via hop"
            } else {
                "wide"
            }
        p.events += BlobEvent.PullThrough
    }

    /**
     * [pullThrough] when [w] may not ask [lacking] to pull its blob through (#195): nobody is asked, and the cycle, in
     * which every neighbour said it lacks the blob, is dead and backs off as one that pulled would. Counted and logged
     * once a cycle — never as `blob pull`, the line the trials count pulls by.
     */
    private fun withholdPull(
        w: Want,
        lacking: List<Candidate>,
        why: String,
        p: Plan,
    ) {
        if (w.pullWithheld) return
        w.pullWithheld = true
        p.events += BlobEvent.PullWithheld
        log("blob no-pull ${w.hash8}… → nobody of ${lacking.joinToString { it.id }} ($why)")
    }

    /**
     * Whether the choice of [best] may be made: every answer is in, [best] rides a live Wi-Fi Aware link, or [best] is
     * the frame's author and no neighbour still to answer could ride a better plane (ADR 2026-10.g24x). A linked author
     * that says it holds the blob is the holder the rest would only relay for: the 2026-10-06 trace waited 5 s on a
     * carrier's NONE after the author's HAVE came back in 124 ms. An author on a Coded link still waits for the others.
     */
    private fun answersIn(
        w: Want,
        best: Candidate,
        kept: Long,
        answering: List<Candidate>,
    ): Boolean {
        val tier = tierOf(w, best, kept)
        return answering.isEmpty() ||
            tier == Tier.FastLink ||
            (best.id == w.author && answering.none { bestTierFor(it).ordinal < tier.ordinal })
    }

    /** The best plane [c] could offer a copy over, before it has said what it holds: [tierOf] with the largest blob. */
    private fun bestTierFor(c: Candidate): Tier =
        when {
            c.route == BulkRoute.Linked -> Tier.FastLink
            c.route == BulkRoute.Reachable -> Tier.FastArm
            !c.slow && !c.streaming -> Tier.Link
            else -> Tier.SlowLink
        }

    /** Whether [c] may be asked now: never before, or its answer is newer than our last ask, or the memo has lapsed. */
    private fun askable(
        w: Want,
        c: Candidate,
        t: Long,
    ): Boolean {
        val at = w.askedAt[c.id] ?: return true
        return w.answers.getValue(c.id).querySentAt > at || t - at >= SERVE_MEMO_MS
    }

    /**
     * The plane a holder's copy would come over, best first: a live Wi-Fi Aware link, one we can raise for a blob big
     * enough to be worth it (what is left of it past our kept prefix, against the composite's own arm gate), a
     * full-speed Bluetooth link with nothing streaming to us, then a slow (Coded) or busy one.
     */
    private fun tierOf(
        w: Want,
        c: Candidate,
        kept: Long,
    ): Tier {
        val left = w.answers[c.id]?.size?.let { it - kept }
        // A fast-only copy is one the holder raises the link for whatever its size (ADR 2026-10.js2p).
        val large = w.fastOnly || (left != null && left >= CompositeMeshTransport.BULK_MIN_BYTES)
        return when {
            c.route == BulkRoute.Linked -> Tier.FastLink
            c.route == BulkRoute.Reachable && large -> Tier.FastArm
            !c.slow && !c.streaming -> Tier.Link
            else -> Tier.SlowLink
        }
    }

    /** Best holder first: by plane, then by how many files it is feeding (a Bluetooth holder splits its pace), then id. */
    private fun byTier(
        w: Want,
        kept: Long,
    ): Comparator<Candidate> = compareBy({ tierOf(w, it, kept).ordinal }, { w.answers[it.id]?.load ?: 0 }, { it.id })

    /**
     * An ask's grace: the plain one, plus the round trip [c]'s link last took ([rttOf]), plus the composite's link wait
     * when the holder may raise Wi-Fi Aware for it.
     */
    private fun graceFor(
        w: Want,
        c: Candidate,
        kept: Long,
    ): Long {
        val left = w.answers[c.id]?.size?.let { it - kept }
        val linkWait = c.route == BulkRoute.Reachable && (w.fastOnly || left == null || left >= CompositeMeshTransport.BULK_MIN_BYTES)
        return firstAskGraceMs + rttOf(c.id) + if (linkWait) CompositeMeshTransport.BULK_GRACE_MS else 0L
    }

    /**
     * How long an answer from [id] may take before it reads as silence: [HolderQuery.waitMs], or the round trip its last
     * answer took plus that wait when longer, at most [QUERY_WAIT_CAP_MS] (#140). An asker whose own frames are queued
     * on a link would otherwise read its lag as the neighbour's silence. Under [lock].
     */
    private fun queryWaitFor(id: String): Long {
        val rtt = answerRtt[id] ?: return holderQuery.waitMs
        return maxOf(holderQuery.waitMs, minOf(rtt + holderQuery.waitMs, QUERY_WAIT_CAP_MS))
    }

    /** The round trip [id]'s last answer took, at most [QUERY_WAIT_CAP_MS]; 0 when none came back yet. Under [lock]. */
    private fun rttOf(id: String): Long = answerRtt[id]?.let { minOf(it, QUERY_WAIT_CAP_MS) } ?: 0L

    /**
     * How likely [c] is to hold [want]'s blob when it cannot say, best first: its author (the one holder we can name), a
     * neighbour whose copy would ride the high-throughput plane (linked over it, then reachable on it), the neighbour the
     * frame came from (a carrier pulls what it carries), then anyone.
     */
    private fun rankOf(
        want: Want,
        c: Candidate,
    ): Rank =
        when {
            c.id == want.author -> Rank.Author
            c.route == BulkRoute.Linked -> Rank.FastLink
            c.route == BulkRoute.Reachable -> Rank.FastReach
            c.id == want.via -> Rank.SourceHop
            else -> Rank.Neighbour
        }

    /** The log line for [plan]'s asks of [hash]; ADR 2026-10.f7gg's two lines are kept word for word for the blind round. */
    private fun askLine(
        hash: String,
        plan: Plan,
    ): String {
        val h = "${hash.take(LOG_HASH_CHARS)}…"
        val to = plan.asks.joinToString { it.to.nodeId }
        val line =
            when (plan.asks.first().line) {
                "first" -> "blob ask $h → $to first (${plan.why}), widen in ${plan.wake} ms"
                "widened" -> "blob ask $h → $to widened"
                "pull" -> "blob pull $h → $to (${plan.why})"
                else -> "blob fetch $h → $to (${plan.why}), grace ${plan.wake} ms${if (plan.asks.first().arm) ", armed" else ""}"
            }
        // Appended, never woven in: the trials grep the lines above as they stand (`first (…)`, `widened`). The budget
        // and the deadline every ask carries (#195) go last.
        val reach = " [pull ${plan.pull}${plan.until?.let { ", until $it" } ?: ""}]"
        return (if (plan.fastOnly) "$line [fast-only]" else line) + reach
    }

    /**
     * The bytes of [hash] are coming: streaming in or landing on a link ([MeshTransport.arrivingFiles]), or going into
     * the store ([BlobStore.storing]). Read in that order, the path's own backwards — a file joins the next stage
     * before it leaves the last, so a blob moving on between the two reads is seen by the second (#138).
     */
    private suspend fun onTheWay(hash: String): Boolean = hash in transport.arrivingFiles() || store.storing(hash)

    /** [onTheWay], or already held: read last, for the same reason. */
    private suspend fun onTheWayOrHeld(hash: String): Boolean = onTheWay(hash) || store.has(hash)

    /**
     * The linked neighbours as a step reads them: each one's plane for a file, whether it is streaming to us, whether
     * its link says it answers a query, and whether that link is a slow one.
     */
    private fun linkedNow(): List<Candidate> =
        transport.neighbors.value.map {
            Candidate(
                peer = it,
                route = transport.bulkRouteTo(it.nodeId),
                streaming = transport.fileArrivingFrom(it.nodeId),
                capable = it.capabilities and Protocol.CAP_BLOB_HAVE != 0L,
                slow = transport.slowLinkTo(it.nodeId),
                streamLeftMs = transport.fileArrivingEtaMs(it.nodeId),
            )
        }

    /**
     * Steps [hash] again after [ms]: when an ask's grace is up, answers are due, or a holder's stream may have ended.
     * The earliest wake wins — a later step that needs a sooner one (a stream to poll, a wait for answers) arms it, and
     * a superseded timer does nothing. A timer that fires steps every want due within [TIMER_COALESCE_MS] of it and
     * flushes once, so wants armed together (a batch's FETCHING rechecks) query together, one frame per neighbour. With
     * no session there is none, and the next want, answer, link-up or tick steps it instead.
     */
    private fun armTimer(
        hash: String,
        ms: Long,
    ) {
        // A stopped session's scope would never run the timer's body; the next want, link-up or tick steps it instead.
        val scope = timers()?.takeIf { it.isActive } ?: return
        val at = System.nanoTime() / NANOS_PER_MS + ms
        val token =
            synchronized(lock) {
                val want = fetching[hash] ?: return
                if (want.timerDue <= at) return
                want.timerDue = at
                ++want.timerToken
            }
        scope
            .launch {
                delay(ms)
                val due = takeDue(hash, token, at) // cleared before the steps, which may arm the next
                if (due.isEmpty()) return@launch
                val linked = linkedNow()
                stepping { due.forEach { step(it, linked) } }
            }.invokeOnCompletion { cause ->
                // Cancelled with the session it ran on: the next session's first step arms afresh.
                if (cause != null) clearTimer(hash, token)
            }
    }

    private fun clearTimer(
        hash: String,
        token: Int,
    ) = synchronized(lock) {
        fetching[hash]?.takeIf { it.timerToken == token }?.timerDue = Long.MAX_VALUE
    }

    /**
     * The wants a timer for [hash] (armed as [token], due [at]) steps: none when a sooner timer superseded it, else every
     * want due within [TIMER_COALESCE_MS] of it — their own timers are disarmed (a new token), so each is stepped once.
     * Due times are compared with each other, never with the clock, so this holds on a virtual one too.
     */
    private fun takeDue(
        hash: String,
        token: Int,
        at: Long,
    ): List<String> =
        synchronized(lock) {
            if (fetching[hash]?.timerToken != token) return@synchronized emptyList()
            fetching.entries
                .filter { it.value.timerDue <= at + TIMER_COALESCE_MS }
                .map { (key, w) ->
                    w.timerDue = Long.MAX_VALUE
                    w.timerToken++
                    key
                }
        }

    /**
     * Runs [steps], then [flushQueries]: a step queues its holder queries into [outbox] and marks them asked, and only a
     * flush sends them. A step that throws still has the queries the steps before it queued flushed — left in the
     * outbox, they would stand as asked with nothing sent until some later entry point flushed — and its own throw is
     * the one the caller sees. Not while this coroutine is cancelled: a stopped session sends nothing, and a `finally`
     * would have its suspend sends run during the cancellation. A [CancellationException] the steps let out while the
     * coroutine is still active (a leaked timeout) is a failure like any other, and flushes.
     */
    private suspend inline fun stepping(steps: () -> Unit) {
        try {
            steps()
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception, // rethrown below: only the flush is added
        ) {
            if (currentCoroutineContext().isActive) {
                try {
                    flushQueries()
                } catch (
                    @Suppress("TooGenericExceptionCaught") flush: Exception, // the step's throw is the one to report
                ) {
                    e.addSuppressed(flush)
                }
            }
            throw e
        }
        flushQueries()
    }

    /** Sends the queries the steps since the last flush queued: one frame per neighbour, at most [MAX_QUERY_HASHES] each. */
    private suspend fun flushQueries() {
        val batches =
            synchronized(lock) {
                if (outbox.isEmpty()) return
                outbox.map { (peer, hashes) -> peer to hashes.toList() }.also { outbox.clear() }
            }
        val me = selfId()
        for ((peer, hashes) in batches) {
            for (chunk in hashes.chunked(MAX_QUERY_HASHES)) {
                val id = newRequestId()
                val t = now()
                synchronized(lock) {
                    sentQueries.entries.removeAll { t - it.value.at >= CYCLE_MS }
                    sentQueries[id] = SentQuery(peer, t, chunk.toSet())
                }
                transport.send(holderQueryFrame(me, id, chunk), Peer(peer))
                onEvent(BlobEvent.QuerySent)
                log("blob query → $peer ${chunk.size} hashes #${id.take(LOG_ID_CHARS)} [${chunk.joinToString { it.take(LOG_HASH_CHARS) }}]")
            }
        }
    }

    /**
     * A neighbour asked which of [hashes] we hold ([FrameType.BLOB_HAVE], ADR 2026-10.6e5t): answer each on the link it
     * came in on. Read once, with no side effect — nothing is served, pulled or remembered, so a query costs a store
     * lookup and one small frame, and the per-peer budget bounds an unsigned flood of them.
     *
     * Each hash is [BlobHasItem.SENDING] when a copy is already on the link to [from], was enqueued to it inside the
     * serve memo (a `blobreq` now would be refused: wait), or is parked for it behind another feed ([parked]),
     * [BlobHasItem.HAVE] when one would be served, with its length, [BlobHasItem.FETCHING] when its bytes are on their
     * way to us, and [BlobHasItem.NONE] otherwise. The reads run the
     * bytes' path backwards — the link, the store's mark, the store — so a blob landing mid-answer is never NONE (#138).
     */
    suspend fun onHaveQuery(
        hashes: List<String>,
        from: String,
        queryId: String,
    ) {
        try {
            val asked =
                hashes
                    .asSequence()
                    .filter { it.isNotEmpty() && it.length <= MAX_HASH_CHARS }
                    .distinct()
                    .take(MAX_QUERY_HASHES)
                    .toList()
            if (asked.isEmpty()) return
            if (!admitQuery(from)) {
                onEvent(BlobEvent.QueryRefused)
                return
            }
            val sending = asked.filterTo(HashSet()) { transport.fileInFlightTo(from, it) || memoHolds(it, from) || isParked(it, from) }
            val arriving = transport.arrivingFiles().keys
            val storing = asked.filterTo(HashSet()) { store.storing(it) }
            val held = store.servable(asked)
            val items =
                asked.map { h ->
                    val size = held[h]
                    when {
                        size == null -> BlobHasItem(h, if (h in arriving || h in storing) BlobHasItem.FETCHING else BlobHasItem.NONE)
                        h in sending -> BlobHasItem(h, BlobHasItem.SENDING, size, eta = etaFor(h, from, size))
                        else -> BlobHasItem(h, BlobHasItem.HAVE, size)
                    }
                }
            val load = transport.feedingLoad(from)
            transport.send(holderAnswerFrame(selfId(), BlobHasContent(items, load, queryId)), Peer(from))
            onEvent(BlobEvent.QueryAnswered)
            val shown = items.filter { it.state != BlobHasItem.NONE }.joinToString { itemLabel(it) }
            val listed = if (shown.isEmpty()) "" else ": $shown"
            log("blob has → $from #${queryId.take(LOG_ID_CHARS)} ${held.size}/${items.size} held$listed load=$load")
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception, // an inbound handler never throws (rules/mesh.md)
        ) {
            log("blob has → $from failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * [from] answered a query of ours ([FrameType.BLOB_HAS]). Only a hash we still want, from a neighbour queried for it
     * this cycle, and named by the query the answer names, counts; an answer to a query older than the latest one sent
     * that neighbour for it is stale. Every hash answered takes its next step at once, so a late answer still counts.
     *
     * An answer to a proof query ([confirmHeld]) is that query's alone: it steps no want ([takeProof]). [shortRange] is
     * whether it came in on a phone-to-phone link (not LoRa, not a spool); only such an answer proves a hold.
     */
    suspend fun onHaveAnswer(
        content: BlobHasContent,
        from: String,
        shortRange: Boolean = true,
    ) {
        val proof = synchronized(lock) { takeProof(content, from, shortRange) }
        if (proof != null) {
            onProofAnswer(proof, content, from)
            return
        }
        val t = now()
        val (touched, latency) = synchronized(lock) { recordAnswers(content, from, t) }
        if (touched.isEmpty()) {
            onEvent(BlobEvent.AnswerIgnored)
            return
        }
        val idPart = content.re?.let { " #${it.take(LOG_ID_CHARS)}" } ?: ""
        val inMs = latency?.let { " in $it ms" } ?: ""
        log("blob answer ← $from$idPart$inMs: ${touched.joinToString { itemLabel(it) }} load=${content.load ?: 0}")
        val linked = linkedNow()
        stepping { touched.forEach { step(it.hash, linked) } }
        // Counted once the answer's steps have run, so a count read means what they decided has been sent too.
        onEvent(BlobEvent.AnswerTaken)
    }

    /**
     * Asks [peer] whether it holds [hash] — [confirmHeld] for one.
     */
    suspend fun confirmHeld(
        hash: String,
        peer: String,
    ) = confirmHeld(listOf(hash), peer)

    /**
     * Asks [peer] whether it holds [hashes], whole: the sender's end of the proof that a DM's recipient has a photo's
     * bytes, so the photo need not go up to a spool (docs/SPOOL_PROTOCOL.md §9.5, ADR 2026-10.vy27). A plain holder query
     * ([FrameType.BLOB_HAVE], ADR 2026-10.6e5t), whose HAVE is the responder's serve predicate ([BlobStore.servable]):
     * bytes that were hash-checked and screened before they were held. What we enqueued, the link's feed end and a
     * `blobprog`'s `held` are byte counts and prove nothing of the kind; a message ack less still.
     *
     * Sent only to a live short-range link — a neighbour ([MeshTransport.neighbors]; a board's set is always empty) whose
     * link carries [Protocol.CAP_BLOB_HAVE] — and at most once per (hash, peer) per [PROOF_FLOOR_MS], just under the
     * caller's re-ask cadence, so calling it every deferred round costs one frame a round per peer at most; pass a round's hashes
     * for one peer together and they share it. A proof query is not an ask, and not a want's query: it creates or steps
     * no want, stamps no serve memo, parks nothing, and its answer goes to [onPeerHolds] alone ([onHaveAnswer]). No
     * answer is no proof — a neighbour without the bit or a silent one leaves the caller's own deadline to decide.
     */
    suspend fun confirmHeld(
        hashes: Collection<String>,
        peer: String,
    ) {
        val link = transport.neighbors.value.firstOrNull { it.nodeId == peer } ?: return
        if (link.capabilities and Protocol.CAP_BLOB_HAVE == 0L) return
        val t = now()
        // Chosen and stamped under one lock, so two rounds racing never send the same proof query twice.
        val batches =
            synchronized(lock) {
                proofAsked.entries.removeAll { t - it.value >= PROOF_FLOOR_MS }
                proofQueries.entries.removeAll { t - it.value.at >= CYCLE_MS }
                hashes
                    .asSequence()
                    .filter { it.isNotEmpty() && it.length <= MAX_HASH_CHARS }
                    .distinct()
                    .filter { "$it|$peer" !in proofAsked }
                    .toList()
                    .chunked(MAX_QUERY_HASHES)
                    .map { chunk ->
                        val id = newRequestId()
                        proofQueries[id] = ProofQuery(peer, t, chunk.toMutableSet())
                        chunk.forEach { proofAsked["$it|$peer"] = t }
                        id to chunk
                    }
            }
        if (batches.isEmpty()) return
        val me = selfId()
        for ((id, chunk) in batches) {
            try {
                transport.send(holderQueryFrame(me, id, chunk), Peer(peer))
                onEvent(BlobEvent.ProofQuerySent)
                log("blob proof → $peer ${chunk.size} hashes #${id.take(LOG_ID_CHARS)} [${chunk.joinToString { it.take(LOG_HASH_CHARS) }}]")
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception, // a proof query never throws into its caller's round
            ) {
                // Nothing went out: the next round may ask again at once.
                synchronized(lock) {
                    proofQueries.remove(id)
                    chunk.forEach { proofAsked.remove("$it|$peer") }
                }
                log("blob proof → $peer failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /**
     * The items of [content] that answer a proof query of ours ([confirmHeld]), each taken off that query so it counts once;
     * null when its `re` names no proof query (a want's answer, [recordAnswers]). Empty when it names one but proves
     * nothing: another peer's answer to it, one that came in on no short-range link, or hashes that query did not ask or
     * that were answered already. Under [lock].
     */
    private fun takeProof(
        content: BlobHasContent,
        from: String,
        shortRange: Boolean,
    ): List<BlobHasItem>? {
        val re = content.re ?: return null
        val q = proofQueries[re] ?: return null
        if (q.peer != from || !shortRange) return emptyList()
        val taken = content.items.take(MAX_QUERY_HASHES).filter { q.open.remove(it.hash) }
        if (q.open.isEmpty()) proofQueries.remove(re)
        return taken
    }

    /** [from]'s answer to a proof query: each hash it says it holds whole (HAVE, or SENDING us a copy of it) is proved. */
    private suspend fun onProofAnswer(
        items: List<BlobHasItem>,
        content: BlobHasContent,
        from: String,
    ) {
        if (items.isEmpty()) {
            onEvent(BlobEvent.AnswerIgnored)
            return
        }
        onEvent(BlobEvent.ProofAnswerTaken)
        val idPart = content.re?.let { " #${it.take(LOG_ID_CHARS)}" } ?: ""
        log("blob proof ← $from$idPart: ${items.joinToString { itemLabel(it) }}")
        // An unknown state is never HAVE (ADR 2026-10.6e5t); FETCHING is bytes still on their way in, unchecked.
        for ((hash) in items.filter { it.state in HELD }) {
            try {
                onPeerHolds(hash, from)
                onEvent(BlobEvent.ProofHeld)
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception, // one hash's hook never costs the others theirs
            ) {
                log("blob proof ${hash.take(LOG_HASH_CHARS)}… ← $from: hook failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** [onHaveAnswer]'s bookkeeping, under [lock]: the items that count, and how long their query took to answer. */
    private fun recordAnswers(
        content: BlobHasContent,
        from: String,
        t: Long,
    ): Pair<List<BlobHasItem>, Long?> {
        val sent = content.re?.let { sentQueries[it] }?.takeIf { it.peer == from }
        sent?.let { answerRtt[from] = (t - it.at).coerceAtLeast(0) }
        val touched =
            content.items.take(MAX_QUERY_HASHES).filter { item ->
                val w = fetching[item.hash] ?: return@filter false
                val queriedAt = w.queried[from] ?: return@filter false
                // Named by the query it answers, and that query no older than the latest we queued this neighbour.
                if (sent != null && (item.hash !in sent.hashes || sent.at < queriedAt)) return@filter false
                w.answers[from] =
                    Answer(
                        item.state,
                        item.size?.takeIf { it > 0 },
                        (content.load ?: 0).coerceAtLeast(0),
                        sent?.at ?: queriedAt,
                        etaAt(item, t),
                        receivedAt = t,
                    )
                if (item.state in HELD || item.state == BlobHasItem.FETCHING) w.sawLead(t)
                markOverCeiling(w, item, from)
                true
            }
        return touched to sent?.let { t - it.at }
    }

    /**
     * A holder's size over a photo want's ceiling (#234) passes it over before a byte is asked of it; its author's ends
     * the want ([passOver]). The size is the holder's claim, so it speaks for [from] alone. Under [lock].
     */
    private fun markOverCeiling(
        w: Want,
        item: BlobHasItem,
        from: String,
    ) {
        val ceiling = w.ceiling ?: return
        if (item.state in HELD && (item.size ?: 0L) > ceiling) partials.markHolder(item.hash, from)
    }

    /** When a SENDING [item] answered at [t] says our copy should have landed, at most [HOLD_CAP_MS] on (ADR 2026-10.nsr7). */
    private fun etaAt(
        item: BlobHasItem,
        t: Long,
    ): Long? = item.eta?.takeIf { item.state == BlobHasItem.SENDING && it >= 0 }?.let { t + minOf(it, HOLD_CAP_MS) }

    /** Whether [from] may have another answer now: a token bucket per neighbour, [ANSWER_BURST] deep. */
    private fun admitQuery(from: String): Boolean =
        synchronized(lock) {
            val t = now()
            val b = answerBudget.getOrPut(from) { Bucket(ANSWER_BURST.toDouble(), t) }
            b.tokens = minOf(ANSWER_BURST.toDouble(), b.tokens + (t - b.at).coerceAtLeast(0) / ANSWER_REFILL_MS.toDouble())
            b.at = t
            if (b.tokens < 1.0) return@synchronized false
            b.tokens -= 1.0
            true
        }

    /** Whether [hash] was enqueued to [peer] inside the serve memo — a peek: nothing stamped, nothing pruned. */
    private fun memoHolds(
        hash: String,
        peer: String,
    ): Boolean = synchronized(lock) { recentlyServed["$hash|$peer"]?.let { now() - it < SERVE_MEMO_MS } == true }

    /**
     * A point-to-point, unsigned blob request wrapped for the transport: `relay = false` so the router
     * never floods it (it propagates hop-by-hop via [onRequest]), and an empty signature (blob requests
     * are unsigned by design — see `MeshManager.verifyInbound`).
     */
    private fun blobRequest(
        me: String,
        hash: String,
        fastOnly: Boolean,
        pull: Int,
        until: Long?,
    ): WireEnvelope {
        // A cut transfer's kept prefix makes the ask a resume: a holder that knows the field streams only the rest,
        // and one that doesn't sends the whole file, which lands as it always has (#116).
        val offset = partials.length(hash).takeIf { it > 0 }
        // Absent unless set, so a plain ask is the bytes it always was (ADR 2026-10.js2p). The budget and the deadline
        // ride every ask (#195): to a neighbour that lacks the blob, an ask is a pull, whatever made it.
        val content =
            BlobReqContent(
                hash,
                offset,
                fastOnly = true.takeIf { fastOnly },
                pull = pull.takeIf { pullBudgets },
                until = until.takeIf { pullBudgets },
            )
        return unsignedPointToPoint(FrameType.BLOB_REQ, newRequestId(), me, WireCodec.encodePayload(content))
    }

    /** A holder query for [hashes], under the envelope id [id] the answer will name. Unsigned and point-to-point. */
    private fun holderQueryFrame(
        me: String,
        id: String,
        hashes: List<String>,
    ): WireEnvelope = unsignedPointToPoint(FrameType.BLOB_HAVE, id, me, WireCodec.encodePayload(BlobHaveContent(hashes)))

    /** The answer to a holder query, under a fresh id of its own (the router's seen-set dedups by id). */
    private fun holderAnswerFrame(
        me: String,
        content: BlobHasContent,
    ): WireEnvelope = unsignedPointToPoint(FrameType.BLOB_HAS, newRequestId(), me, WireCodec.encodePayload(content))

    private fun unsignedPointToPoint(
        type: String,
        id: String,
        me: String,
        payload: ByteArray,
    ): WireEnvelope {
        val env = RelayEnvelope(type = type, id = id, senderId = me, payload = payload)
        return WireEnvelope(relay = false, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(env))
    }

    /**
     * A neighbor asked us for [hash]: serve it if held, else pull it ourselves so its next ask finds us
     * holding it. Nothing is remembered about the asker — it asks again on its own 60 s tick while it
     * still lacks the bytes, and stops the moment they arrive from anyone. [offset] is how many bytes of it the
     * asker kept from a cut transfer; only the rest is streamed (#116).
     *
     * One shared-budget Bluetooth copy of a blob is fed at a time (ADR 2026-10.g24x): an ask whose copy would ride such
     * a link while another one is being fed to someone else is parked ([parkBehindFeed]) and served when that feed
     * ends. A Bluetooth phone splits one pace budget over every link it feeds (ADR 2026-10.8jwn), so four copies at
     * once all landed at the end; one at a time, the first lands in a quarter of that, and its receiver holds it — a
     * second source for the rest.
     *
     * A [fastOnly] ask (ADR 2026-10.js2p) is served only over Wi-Fi Aware: refused at once when the asker is neither
     * linked nor reachable on it, never parked behind a Bluetooth feed, and handed to the transport marked so it is
     * never sent over Bluetooth. Not holding it, we pull it through fast-only too.
     *
     * Not holding it, we want it with one hop less of the ask's [pull] budget ([budgetOf]: an older build's ask, with
     * none, is a fetch only) and its [until] (#195). An ask with no budget left, or past its deadline, makes no want
     * at all: it is served if we hold the blob, and that is all.
     */
    suspend fun onRequest(
        hash: String,
        fromNodeId: String,
        offset: Long? = null,
        fastOnly: Boolean = false,
        pull: Int? = null,
        until: Long? = null,
    ) = serveOrWant(hash, fromNodeId, offset, mayPark = true, fastOnly = fastOnly, pull = pull, until = until)

    /** [onRequest], where [mayPark] false serves at once whatever else is being fed (a park that waited its longest). */
    private suspend fun serveOrWant(
        hash: String,
        fromNodeId: String,
        offset: Long?,
        mayPark: Boolean,
        fastOnly: Boolean = false,
        pull: Int? = null,
        until: Long? = null,
    ) {
        val peer = Peer(fromNodeId)
        // Nothing reaches it over Wi-Fi Aware: refuse before the store is read, and stamp no memo, so a SENDING answer
        // never covers a copy that went nowhere and its next ask, once a route is up, is served at once.
        if (fastOnly && transport.bulkRouteTo(fromNodeId) == BulkRoute.None) {
            onEvent(BlobEvent.FastOnlyRefused)
            log("blob refuse ${hash.take(LOG_HASH_CHARS)}… → $fromNodeId: fast-only, no Wi-Fi Aware route")
            return
        }
        val file = store.fileFor(hash)
        if (file != null) {
            // Holding the bytes is what decides a serve; a missing mime row only costs us the type.
            val mime = store.mimeFor(hash) ?: FALLBACK_MIME
            // A resume streams from the asker's offset: up to the whole length, so a prefix that is already complete
            // gets an empty tail and its end. Anything else (the peer's claim) is a whole file; the link checks the
            // offset again against the bytes it opens (FramedLink.streamFile).
            val from = offset?.takeIf { it in 1..file.length() } ?: 0L
            // Its copy is still on the link (queued or streaming) — an older build's 60 s re-ask, or one
            // whose serve waits behind a multi-minute blob to the same peer — or was enqueued inside the
            // memo: don't ship a second. Parked behind another copy's feed: served when that one ends.
            if (transport.fileInFlightTo(fromNodeId, hash)) return
            // A fast-only copy never rides a Bluetooth budget, so it never waits behind a Bluetooth feed either.
            if (mayPark && !fastOnly &&
                parkBehindFeed(hash, fromNodeId, Reached(offset, pull, until), file.length() - from, addressees(hash))
            ) {
                return
            }
            if (servedRecently(hash, fromNodeId)) return
            // A fast-only copy the transport gives up on after accepting it (no Wi-Fi Aware link inside its grace) goes
            // nowhere: forget it as a refused send is forgotten.
            val dropped: (() -> Unit)? = if (fastOnly) ({ forgetServed(hash, fromNodeId) }) else null
            val meta = FileMeta(FileKind.ATTACHMENT, hash, mime, offset = from, fastOnly = fastOnly, onDropped = dropped)
            if (!transport.sendFile(file, peer, meta)) {
                forgetServed(hash, fromNodeId) // nothing went out — let the next ask retry at once
            }
            return
        }
        echoFor(hash, fromNodeId, fastOnly, pull, until)
    }

    /** [serveOrWant] for a blob we lack: want it for [asker], unless its ask allows no echo here (#195). */
    private suspend fun echoFor(
        hash: String,
        asker: String,
        fastOnly: Boolean,
        pull: Int?,
        until: Long?,
    ) {
        val refused = echoRefused(pull, until) ?: return want(hash, asker = asker, fastOnly = fastOnly, pull = pull, until = until)
        onEvent(BlobEvent.EchoDeclined)
        log("blob no-echo ${hash.take(LOG_HASH_CHARS)}… for $asker ($refused)")
    }

    /** Why an ask with [pull] and [until] makes no want here, or null when it does (#195). */
    private fun echoRefused(
        pull: Int?,
        until: Long?,
    ): String? =
        when {
            !pullBudgets -> null
            budgetOf(pull) < 0 -> "fetch only"
            until != null && now() >= until -> "past its deadline"
            else -> null
        }

    /**
     * Parks [asker]'s ask for [hash] when its copy of [bytes] would ride a shared-budget Bluetooth link and waits behind
     * another copy of the blob fed live on one ([waitsBehindFeed], ADR 2026-10.g24x, 2026-10.nsr7) — true when parked,
     * and a re-ask while parked only refreshes it. Never with no session to serve it later, never for a copy the serve
     * memo already covers, and never for one that rides Wi-Fi Aware: a live link, or a large blob the composite will
     * raise one for.
     */
    private fun parkBehindFeed(
        hash: String,
        asker: String,
        ask: Reached,
        bytes: Long,
        to: Set<String>?,
    ): Boolean {
        val scope = timers()?.takeIf { it.isActive } ?: return false
        if (memoHolds(hash, asker) || !ridesSharedBudget(asker, bytes) || !waitsBehindFeed(hash, asker, bytes, to)) return false
        val fresh =
            synchronized(lock) {
                val asks = parked.getOrPut(hash) { LinkedHashMap() }
                val t = now()
                val was = asks[asker]
                (was == null).also { asks[asker] = ParkedAsk(ask, bytes, was?.since ?: t, t, was?.until ?: 0L) }
            }
        if (fresh) {
            log("blob park ${hash.take(LOG_HASH_CHARS)}… for $asker behind a feed${if (isCarrier(asker, to)) " (carrier)" else ""}")
            onEvent(BlobEvent.Park)
        }
        // Kept as long as its turn is expected to take, not only the memo's 45 s: an asker held elsewhere (a carrier
        // answering FETCHING) may not query us again before the feed ends (device cell (c), 2026-10-07: dropped 4 s
        // before its turn, it came back 10 s late on its own).
        etaFor(hash, asker, bytes)
        pollParked(scope)
        return true
    }

    /**
     * Whether a copy of [bytes] to [asker] would ride a Bluetooth link whose feeds share the budget: not a live Wi-Fi
     * Aware link, not one the composite raises for a large blob ([CompositeMeshTransport.BULK_MIN_BYTES]), and not a
     * Coded link, whose pace sits outside the split.
     */
    private fun ridesSharedBudget(
        asker: String,
        bytes: Long,
    ): Boolean =
        when (transport.bulkRouteTo(asker)) {
            BulkRoute.Linked -> false
            BulkRoute.Reachable -> bytes < CompositeMeshTransport.BULK_MIN_BYTES && !transport.slowLinkTo(asker)
            BulkRoute.None -> !transport.slowLinkTo(asker)
        }

    /**
     * Whether [asker]'s copy of [bytes] waits behind the copies of [hash] fed live to other neighbours (ADR 2026-10.nsr7):
     * yes while any is, however long it takes — split budgets make two copies at once each take twice as long, so one
     * at a time lands the first sooner and the second no later. The exception is an asker whose own copy, at the rate
     * its link is expected to take ([MeshTransport.expectedFileRate]), would end before every one of them does: behind
     * an iPhone's base-pace feed, a link that earns the boost goes beside it, and every slower asker still waits. A feed
     * that stalled or ran past its deadline is not live ([FileFeed.live]) and holds nobody.
     *
     * Going beside costs the feeds already running: the budget splits one more way while the asker's copy runs, so each
     * of the `n` feeds on the plane ([MeshTransport.feedingLoad]) ends about own ÷ (n + 1) later. The asker goes only
     * when what it saves beats that: the last of the blob's `k` feeds' end, plus its own copy at the share it would then
     * get, against its copy now — `max ETA − own × k ÷ (n + 1) > own × n ÷ (n + 1)`. With one feed that is the same as
     * ending first; behind two or more it asks for more.
     *
     * Members first ([to], the conversation's addressees): a member waits only behind members' copies and goes beside a
     * carrier's, which is a spare; a carrier waits behind any copy and never goes beside one (cell (d), 2026-10-07: a
     * member parked 287 s behind a carrier's lapsed pull). Unknown addressees treat everyone as a member.
     */
    private fun waitsBehindFeed(
        hash: String,
        asker: String,
        bytes: Long,
        to: Set<String>?,
    ): Boolean {
        val carrier = isCarrier(asker, to)
        val feeds = liveFeeds(hash, asker, among = to.takeUnless { carrier })
        if (feeds.isEmpty()) return false
        if (carrier) return true
        val own = ownCopyMs(asker, bytes) ?: return true
        val etas = feeds.map { it.etaMs ?: return true }
        if (etas.any { own >= it }) return true
        val k = feeds.size.toLong()
        val n = maxOf(transport.feedingLoad(asker).toLong(), k)
        val saved = etas.max() - own * k / (n + 1)
        val cost = own * n / (n + 1)
        if (saved <= cost) return true
        val theirs = etas.joinToString { "$it ms" }
        val trade = "saves ~$saved ms for ~$cost ms of theirs"
        log("blob beside ${hash.take(LOG_HASH_CHARS)}… → $asker: its copy ~$own ms, the feeds' $theirs; $trade")
        return false
    }

    /** How long a copy of [bytes] to [asker] would take at its link's expected rate; null when that is not known. */
    private fun ownCopyMs(
        asker: String,
        bytes: Long,
    ): Long? = transport.expectedFileRate(asker).takeIf { it > 0 }?.let { bytes * MS_PER_SECOND / it }

    /**
     * The live copies of [hash] fed on shared-budget links to neighbours other than [asker] ([MeshTransport.fileFeed]),
     * only those to neighbours [among] when it is given.
     */
    private fun liveFeeds(
        hash: String,
        asker: String,
        among: Set<String>? = null,
    ): List<FileFeed> =
        transport.neighbors.value.mapNotNull { n ->
            if (n.nodeId == asker || (among != null && n.nodeId !in among)) null else transport.fileFeed(n.nodeId, hash)?.takeIf { it.live }
        }

    /** Whether [asker] is a carrier of a blob whose conversation is addressed to [to]: known addressees, not among them. */
    private fun isCarrier(
        asker: String,
        to: Set<String>?,
    ): Boolean = to != null && asker !in to

    /**
     * A link's feed changed (`MeshTransport.fileFeedsChanged`): it ended, its pace settled or moved, or its first report
     * measured it. Re-read the parked asks now — the ones whose feed ended are served, and one whose copy would now end
     * first goes beside — on the CPU the write woke: the poll is a `delay`, which stops while the CPU sleeps, and it wakes
     * only for a stall or a deadline.
     */
    suspend fun feedsChanged() {
        if (synchronized(lock) { parked.isEmpty() }) return
        serveParked(fromPoll = false)
    }

    /** Whether [asker]'s ask for [hash] is parked ([parked]) — a peek: nothing pruned. */
    private fun isParked(
        hash: String,
        asker: String,
    ): Boolean = synchronized(lock) { parked[hash]?.containsKey(asker) == true }

    /**
     * Starts the loop that serves [parked] asks on [scope], unless one is running. It wakes when the soonest live feed
     * ahead of a parked ask can next stall or pass its deadline ([FileFeed.recheckMs]), between [PARK_POLL_MS] and
     * [PARK_POLL_MAX_MS]: a park lasts as long as the feed, minutes for a big file, and a feed's end is served on its
     * edge anyway ([feedEnded]).
     */
    private fun pollParked(scope: CoroutineScope) {
        synchronized(lock) {
            if (parkPoll?.isActive == true) return
            parkPoll =
                scope.launch {
                    var wait = PARK_POLL_MS
                    while (true) {
                        delay(wait)
                        wait = serveParked(fromPoll = true) ?: break
                    }
                }
        }
    }

    /**
     * One pass over [parked]. First it drops the asks whose asker unlinked, or that nobody renewed (by asking or
     * querying) for [SERVE_MEMO_MS] past what a SENDING answer promised it ([ParkedAsk.until]). Then, for each blob, it
     * serves the ask whose link is expected to feed fastest (the oldest of equals) once that ask no longer waits behind
     * a live feed ([waitsBehindFeed]): the feed ended, stalled or ran past its deadline, or the ask's own copy would end
     * first. Returns how long the loop may sleep before the next pass while anything stays parked, or null when nothing
     * does — and then the loop is let go in the same critical section, so a park racing the end starts a new one.
     */
    private suspend fun serveParked(fromPoll: Boolean): Long? {
        val t = now()
        val linked = transport.neighbors.value.mapTo(HashSet()) { it.nodeId }
        val parkedNow =
            synchronized(lock) {
                parked.values.forEach { asks -> asks.entries.removeAll { (peer, a) -> peer !in linked || t >= a.keptUntil() } }
                parked.entries.removeAll { it.value.isEmpty() }
                parked.map { (hash, asks) -> hash to asks.entries.map { it.key to it.value } }
            }
        var next = PARK_POLL_MAX_MS
        for ((hash, asks) in parkedNow) {
            val to = addressees(hash)
            val (asker, ask) = first(asks, to) ?: continue
            // A blob with nothing live ahead of its asks is served this pass, one ask at a time: come back soon.
            val feeds = liveFeeds(hash, asker)
            next = minOf(next, feeds.minOfOrNull { it.recheckMs ?: PARK_POLL_MS } ?: PARK_POLL_MS)
            // The feed-end edge and the poll can both read this ask: only the pass that takes it serves it.
            if (!waitsBehindFeed(hash, asker, ask.bytes, to) && takeParked(hash, asker)) {
                log("blob unpark ${hash.take(LOG_HASH_CHARS)}… → $asker (waited ${t - ask.since} ms)")
                // Its budget and deadline with it: served, unless the blob went meanwhile, when it echoes as it asked.
                serveOrWant(hash, asker, ask.ask.offset, mayPark = false, pull = ask.ask.pull, until = ask.ask.until)
            }
        }
        // Only the loop lets itself go: an edge's pass ending empty leaves a running loop to find that at its next poll.
        val any = synchronized(lock) { parked.isNotEmpty().also { if (!it && fromPoll) parkPoll = null } }
        return if (any) next.coerceIn(PARK_POLL_MS, PARK_POLL_MAX_MS) else null
    }

    /**
     * Of [asks], the one served next: a member of [to] before a carrier, then the one whose link is expected to feed
     * fastest ([MeshTransport.expectedFileRate]), then the oldest.
     */
    private fun first(
        asks: List<Pair<String, ParkedAsk>>,
        to: Set<String>?,
    ): Pair<String, ParkedAsk>? =
        asks.maxWithOrNull(
            compareBy<Pair<String, ParkedAsk>> { !isCarrier(it.first, to) }
                .thenBy { transport.expectedFileRate(it.first) }
                .thenByDescending { it.second.since },
        )

    /** Takes [asker]'s parked ask for [hash] off [parked]: false when another pass took it first. */
    private fun takeParked(
        hash: String,
        asker: String,
    ): Boolean =
        synchronized(lock) {
            val asks = parked[hash] ?: return@synchronized false
            (asks.remove(asker) != null).also { if (asks.isEmpty()) parked.remove(hash) }
        }

    /**
     * The ms until [from]'s copy of [hash] ([bytes] of it) should have landed, for a SENDING answer (ADR 2026-10.nsr7):
     * its own feed's ETA while its copy is streaming; parked, the soonest live feed's end plus its own copy, which the
     * parked ask is then kept for ([ParkedAsk.until]) — set at the park too, so an asker that never queries us again
     * keeps its turn. A query from a parked asker renews its ask. Null when nobody can tell, and the asker waits as it
     * did before.
     */
    private fun etaFor(
        hash: String,
        from: String,
        bytes: Long,
    ): Long? {
        transport.fileFeed(from, hash)?.let { return it.etaMs }
        val t = now()
        val renewed = synchronized(lock) { parked[hash]?.get(from)?.also { it.at = t } } ?: return null
        val wait = liveFeeds(hash, from).mapNotNull { it.etaMs }.minOrNull()
        val eta = wait?.let { w -> ownCopyMs(from, renewed.bytes)?.let { w + it } } ?: return null
        synchronized(lock) { renewed.until = maxOf(renewed.until, t + eta + HOLD_SLACK_MS) }
        return eta
    }

    /** A report a sender can take: counts that cannot be negative, and a key no longer than a hash. */
    private fun plausible(c: BlobProgressContent): Boolean =
        c.held >= 0 && c.ms >= 0 && c.hash.isNotEmpty() && c.hash.length <= MAX_HASH_CHARS

    /**
     * A file streaming in from a neighbour reached [p] ([MeshTransport.fileProgress]): tell its sender how much of it we
     * hold, so it measures the drain its stack's queue hides and times the asks it parked behind the feed by it (ADR
     * 2026-10.nsr7). Only to a neighbour whose link carries [Protocol.CAP_BLOB_HAVE], an iPhone included now its port
     * takes the type; an older build refuses the unsigned frame and loses nothing.
     */
    suspend fun onFileProgress(p: FileProgress) {
        val peer = transport.neighbors.value.firstOrNull { it.nodeId == p.from } ?: return
        val capable = peer.capabilities and Protocol.CAP_BLOB_HAVE != 0L
        if (!capable || p.key.isEmpty() || p.key.length > MAX_HASH_CHARS) return
        try {
            val content = BlobProgressContent(p.key, p.held, p.ms)
            transport.send(
                unsignedPointToPoint(FrameType.BLOB_PROGRESS, newRequestId(), selfId(), WireCodec.encodePayload(content)),
                Peer(p.from),
            )
            onEvent(BlobEvent.ProgressSent)
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception, // an outbound report never throws into the collector
        ) {
            log("blob progress → ${p.from} failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * [from] said how much of a file we feed it holds ([FrameType.BLOB_PROGRESS], ADR 2026-10.nsr7): the link streaming
     * that key to it measures its drain by it. Its parked asks are judged on the next pass.
     */
    fun onProgressReport(
        content: BlobProgressContent,
        from: String,
    ) {
        if (!plausible(content)) return
        transport.onFileProgress(from, content.hash, content.held, content.ms)
        onEvent(BlobEvent.ProgressTaken)
    }

    /**
     * A blob we wanted arrived over a link: persist it and notify. [resumedFrom] is the byte its stream started at
     * when the link spliced it onto a prefix we kept (#116). [from] is the neighbour whose link delivered it: once the
     * store has answered, the file is taken off that link's arriving ones ([MeshTransport.fileTaken]) — held now, or
     * refused, and a refused splice's own landing must not keep its from-0 ask quiet.
     *
     * True when the store took the bytes — checked against [hash] and held — and false when it refused them: the
     * recipient's end of the spool deferral's proof (ADR 2026-10.vy27) is a true here, never the file's arrival alone.
     */
    suspend fun onReceived(
        hash: String,
        mime: String,
        srcPath: String,
        resumedFrom: Long = 0,
        from: String? = null,
    ): Boolean {
        val stored = store.saveIncoming(hash, mime, srcPath)
        if (stored == null) {
            from?.let { transport.fileTaken(it, hash) }
            // A whole file that fails its hash is the holder's bytes alone: a kept prefix stays, and the tick asks
            // again as it always has. A spliced one may have failed on the prefix.
            if (resumedFrom > 0) reaskFromZero(hash, from)
            return false
        }
        partials.drop(hash) // nothing left to resume
        // The want goes before the file leaves the link's landing ones: a step in between would find the blob held and
        // no longer arriving, take the want itself, and withdraw from everyone — the holder that just served it too.
        val want = clearFetching(hash)
        from?.let { transport.fileTaken(it, hash) }
        want?.let { withdrawAsks(hash, it, from) }
        onObtained(hash, stored.absolutePath)
        return true
    }

    /**
     * A file spliced onto [hash]'s kept prefix failed its hash check: drop the prefix (it may be what was wrong) and
     * open a new cycle for the whole blob now, rather than resume onto it again (#116). Only when the drop removed a
     * live prefix — so a peer cannot trigger the ask without first planting one — and never while the bytes are already
     * arriving on a link (ADR 2026-09.4tx5). The holder that sent the tail, [from], is passed over this cycle: its
     * serve memo still stands, and other holders answer at once.
     */
    private suspend fun reaskFromZero(
        hash: String,
        from: String?,
    ) {
        onSpliceRefused()
        if (!partials.drop(hash)) return
        if (onTheWayOrHeld(hash)) return
        val t = now()
        synchronized(lock) {
            // An existing want keeps its fast-only flag. A fresh one (the want was swept or evicted while its prefix was
            // kept) cannot know it, so it is fast-only until the tick's database re-arm re-derives it, within one round:
            // a gated file never starts over Bluetooth on a guess, and a plain one waits a tick at most (ADR 2026-10.js2p).
            val w =
                fetching.getOrPut(hash) {
                    Want(hash, t, null, null, null).also {
                        it.fastOnly = true
                        it.ownWant = true // the prefix was ours to resume: an attachment's ceiling, as before
                    }
                }
            w.wantedAt = t
            w.openCycle(t, natural = false)
            from?.let { w.failed += it }
        }
        stepping { step(hash, linkedNow()) }
    }

    /** Drops fetches whose last-want time has aged past the TTL — a never-arriving blob is reclaimed and
     *  re-armed from the database on the next tick. The cap, not this, is the security bound. Returns the
     *  number reclaimed. */
    fun sweepExpired(): Int = sweepExpiredHashes().size

    /** [sweepExpired], naming the hashes it reclaimed: `mesh/lab` counts a want's generations by them (#195). */
    internal fun sweepExpiredHashes(): Set<String> =
        synchronized(lock) {
            val cutoff = now() - fetchTtlMs
            val iterator = fetching.entries.iterator()
            val removed = HashSet<String>()
            while (iterator.hasNext()) {
                val (hash, want) = iterator.next()
                if (want.wantedAt < cutoff) {
                    iterator.remove()
                    if (want.ceiling != null) partials.cap(hash, null)
                    removed += hash
                }
            }
            removed
        }

    // --- Guarded bookkeeping (short, non-suspend; the suspend methods above send outside these) ---

    /** Records a want for [hash] (insert, or refresh its time and fill in hints it lacked; oldest-first eviction). */
    private fun recordWant(
        hash: String,
        nowMs: Long,
        author: String?,
        via: String?,
        asker: String?,
        fastOnly: Boolean,
        approved: Boolean = false,
        reach: Reach = Reach(PULL_BUDGET, null),
        ceiling: Long? = null,
    ) = synchronized(lock) {
        val want = fetching[hash]
        if (want == null) {
            fetching[hash] =
                Want(hash, nowMs, author, via, asker).also {
                    it.approved = approved
                    it.fastOnly = fastOnly && !approved
                    it.budget = reach.budget
                    it.until = reach.until
                    it.ceiling = ceiling
                    it.ownWant = asker == null
                }
            partials.cap(hash, ceiling)
        } else {
            if (asker == null) {
                mergeCeiling(want, hash, ceiling)
                want.ownWant = true
            }
            // Our own want says what we will take now (ADR 2026-10.js2p); a neighbour's ask leaves our fetch as it is.
            // The tap is sticky: a want that read its rows before the tap and lands after it never re-holds the fetch.
            if (approved) want.approved = true
            if (asker == null) {
                setFastOnly(want, fastOnly && !want.approved, nowMs)
                // Our own want is a root, re-derived every time (#195): the whole budget, and the rows' deadline. A caller
                // that read every row naming the hash — the re-arm, a delivery, the tap ([Reach.rederived]) — sets it, none
                // included; one that did not (a carried frame, an avatar, a group photo) keeps it unless it names one, or a
                // DM delivered past its window would echo for hours.
                want.wantedAt = nowMs // mutated in place, so insertion order (the eviction order) is kept
                want.budget = PULL_BUDGET
                if (reach.rederived || reach.until != null) want.until = reach.until
            }
            if (want.author == null) want.author = author
            if (want.via == null) want.via = via
            asker?.let { noteAsker(want, it, reach, nowMs) }
        }
    }

    /**
     * The larger ceiling wins (#234): a want of our own without one — an attachment, a carried frame, a group photo —
     * lifts a photo's for good, and two photo wants keep the larger. Only our own wants call it: a neighbour's ask is not
     * a want of ours and moves no ceiling, or a holder passed over could lift it by asking us for the hash. For the same
     * reason a want only neighbours' asks made so far has no ceiling of ours to keep: our first want sets its own. Under
     * [lock].
     */
    private fun mergeCeiling(
        w: Want,
        hash: String,
        ceiling: Long?,
    ) {
        val was = w.ceiling
        w.ceiling =
            when {
                !w.ownWant -> ceiling
                was == null || ceiling == null -> null
                else -> maxOf(was, ceiling)
            }
        if (w.ceiling != was) partials.cap(hash, w.ceiling)
    }

    /**
     * [recordWant]'s bookkeeping for [asker], a neighbour that asked us at [t] to pull [w]'s blob through, with the budget
     * and deadline its ask allows us ([reach]). Under [lock].
     *
     * The ask keeps the want alive only when it carries at least the want's own budget (#195): only a root mints budget,
     * and every hop spends one, so an echo's ask back, a newcomer's, or one from a link that keeps re-forming can never
     * keep a want alive once the want's own source stops. One that carries more raises it, and its deadline with it (no
     * deadline is the widest). The asker is a puller for this cycle either way.
     */
    private fun noteAsker(
        w: Want,
        asker: String,
        reach: Reach,
        t: Long,
    ) {
        // It lacks the bytes: not this cycle. Its ask may be what opens the next one, so open it first — opened after, it
        // would forget the mark and query the asker back, and a dead blob's pulls would ping-pong.
        if (w.cycleDue(t)) openCycle(w, t)
        w.pullers += asker
        // A build that predates the budget (the iOS port today) refreshes on any ask and echoes without bound: an ask of
        // its, with no field, may make a want here but never keep one alive, so each of ours lives one TTL at most
        // against it (the blobs-reviewer's mixed-fleet finding). The loop itself ends only with the iOS companion.
        if (pullBudgets && (reach.fieldless || reach.budget < w.budget)) return
        w.wantedAt = t
        when {
            reach.budget > w.budget -> {
                w.budget = reach.budget
                w.until = reach.until
            }

            w.until != null -> {
                w.until = reach.until?.let { maxOf(it, w.until!!) }
            }
        }
    }

    /**
     * Sets [w]'s fast-only flag. Lifted (the tap, a limit set to Never, a row that turned out small), the fetch starts
     * over as a plain one at once: the cycle that held it is not counted dead, its backoff is forgotten, and holders it
     * gave up on for Wi-Fi Aware's sake — a reachable one that never raised the link — are asked again, now over
     * whatever plane carries them. An ask still outstanding is left to run. Under [lock].
     */
    private fun setFastOnly(
        w: Want,
        fastOnly: Boolean,
        t: Long,
    ) {
        val lifted = w.fastOnly && !fastOnly
        w.fastOnly = fastOnly
        if (!lifted) return
        w.endDeadStreak()
        if (w.fetch == null) w.openCycle(t, natural = false)
    }

    /**
     * Whether [hash] was refused as a profile photo this session (#234): its author's stream or size passed the photo
     * ceiling, or a held copy was found over it ([refuseAsPhoto]). A photo want of it makes nothing.
     */
    fun refusedAsPhoto(hash: String): Boolean = partials.isRefused(hash)

    /**
     * Refuses [hash] as a profile photo for the rest of the session (#234): a held copy was found over the photo ceiling
     * and released, and a photo want must not fetch it again. A want of it still standing ends at its next step.
     */
    fun refuseAsPhoto(hash: String) = partials.refuse(hash)

    /** The hashes wanted now, whoever wanted them — for `mesh/lab`, which asserts a want is gone (#195). */
    internal fun wantedHashes(): Set<String> = synchronized(lock) { fetching.keys.toSet() }

    /**
     * The budget a want made for an ask that allowed [pull] has: one hop less, an ask no newer than this build's own
     * [PULL_BUDGET] counted at that, and an ask with no field — an older build's, the iOS port's today — counted as 1, a
     * fetch only (#195). Negative when the ask allowed none: such an ask makes no want.
     */
    private fun budgetOf(pull: Int?): Int = if (pullBudgets) minOf(pull ?: 1, PULL_BUDGET) - 1 else PULL_BUDGET

    /** Oldest-wanted-first snapshot of the fetching hashes, copied under the lock so callers iterate safely. */
    private fun snapshotFetching(): List<String> = synchronized(lock) { fetching.keys.toList() }

    /** Removes [hash] from the fetching set (its blob arrived). */
    private fun clearFetching(hash: String): Want? =
        synchronized(lock) { fetching.remove(hash)?.also { if (it.ceiling != null) partials.cap(hash, null) } }

    /**
     * [hash] is held now: every other neighbour [w] asked for it gets a `blobcancel`, so a copy one queued or parked for
     * us is not sent as a second ([from] brought it).
     */
    private suspend fun withdrawAsks(
        hash: String,
        w: Want,
        from: String?,
    ) {
        val asked = synchronized(lock) { w.askedAt.keys.filter { it != from && w.withdraw(it) } }
        asked.forEach { withdrawAsk(hash, it) }
    }

    /**
     * Withdraws our ask for [hash] from [to] (a `blobcancel`, ADR 2026-10.nsr7): the holder drops it if it is parked, and
     * a copy queued and not yet streaming. Only to a neighbour whose link carries [Protocol.CAP_BLOB_HAVE]; an iPhone
     * ([Protocol.CAP_DOORBELL]) gets one too, since its port takes the type (knit-ios ADR 2026-10.ds45).
     */
    private suspend fun withdrawAsk(
        hash: String,
        to: String,
    ) {
        val peer = transport.neighbors.value.firstOrNull { it.nodeId == to } ?: return
        if (peer.capabilities and Protocol.CAP_BLOB_HAVE == 0L) return
        try {
            transport.send(
                unsignedPointToPoint(FrameType.BLOB_CANCEL, newRequestId(), selfId(), WireCodec.encodePayload(BlobCancelContent(hash))),
                Peer(to),
            )
            onEvent(BlobEvent.CancelSent)
            log("blob cancel ${hash.take(LOG_HASH_CHARS)}… → $to")
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception, // a withdrawal never throws into a step
        ) {
            log("blob cancel → $to failed: ${e.javaClass.simpleName}")
        }
    }

    /**
     * [from] withdrew its ask for [hash] (a `blobcancel`, ADR 2026-10.nsr7): drop it if it is parked here, and take a copy
     * queued to it off its link if that copy has not started. A copy already streaming goes on.
     */
    fun onCancel(
        hash: String,
        from: String,
    ) {
        if (hash.isEmpty() || hash.length > MAX_HASH_CHARS) return
        val parkedOne = takeParked(hash, from)
        val queued = transport.cancelQueuedFile(from, hash)
        // A later ask of its own is a fresh one: served, not refused as a copy already sent.
        if (queued) forgetServed(hash, from)
        if (!parkedOne && !queued) {
            // Logged too: a withdrawal that came after the copy began, or after it ended, is otherwise no trace at all.
            log("blob cancel ← $from ${hash.take(LOG_HASH_CHARS)}…: nothing queued")
            return
        }
        onEvent(BlobEvent.CancelTaken)
        log("blob cancel ← $from ${hash.take(LOG_HASH_CHARS)}…: ${if (parkedOne) "parked ask dropped" else "queued copy withdrawn"}")
    }

    /**
     * True if we already enqueued [hash] to [nodeId] within [SERVE_MEMO_MS]; otherwise stamps the pair and
     * returns false (the caller serves). Prunes expired pairs first; the map's size cap bounds it against a
     * flood between prunes.
     */
    private fun servedRecently(
        hash: String,
        nodeId: String,
    ): Boolean =
        synchronized(lock) {
            val t = now()
            recentlyServed.entries.removeAll { t - it.value >= SERVE_MEMO_MS }
            val key = "$hash|$nodeId"
            if (recentlyServed.containsKey(key)) return@synchronized true
            recentlyServed[key] = t // triggers the size-cap eviction if over maxServeMemo
            false
        }

    /** Forgets a serve stamp (its send failed) so the next ask retries at once. */
    private fun forgetServed(
        hash: String,
        nodeId: String,
    ) = synchronized(lock) { recentlyServed.remove("$hash|$nodeId") }

    /** Opens [w]'s next cycle at [t] ([CYCLE_MS] or more since the last), and logs it when the last one was dead. */
    private fun openCycle(
        w: Want,
        t: Long,
    ) {
        w.openCycle(t, natural = true)
        if (w.deadCycles > 0) log("blob cycle ${w.hash8}… after ${w.deadCycles} dead, next if dead in ${w.cycleGap(t, dead = true)} ms")
    }

    /**
     * Ends [f] — the holder brought nothing — and logs why; a blind first ask's end widens its round instead, and keeps
     * its ask: widening adds asks and gives up on nobody, and the first pick (often the author) may have parked or
     * queued the closest copy (device rerun of 2026-10-07: withdrawn at the widening, an author's parked ask was lost
     * and the asker came back 28 s late). With [p], the holder still up, a fallback's ask is withdrawn ([Plan.cancels]):
     * a copy it queued would come as a second.
     */
    private fun Want.over(
        f: Fetch,
        why: String?,
        p: Plan? = null,
    ): Long? {
        fetch = null
        if (f.tier == Tier.Blind) {
            blindWidened = true
            return null
        }
        if (p != null && withdraw(f.peer)) p.cancels += f.peer
        failed += f.peer
        onEvent(BlobEvent.Fallback)
        log("blob fallback ${f.hash8}… ${f.peer} (${why ?: "grace"}) → next")
        return null
    }

    /**
     * A blob we lack and how far its fetch has got (ADR 2026-10.6e5t). A *cycle* queries every capable neighbour once
     * ([queried], [answers]); one ask at a time is outstanding ([fetch]); a holder that brought nothing is [failed] for
     * the cycle. The neighbours nobody can query get ADR 2026-10.f7gg's round ([blindAsked], [blindWidened]). A cycle
     * ends no sooner than [cycleGap], and only with no ask outstanding. Whether it pulls through is bounded by its frame's
     * deadline ([until]) and by the hops its budget still allows ([budget], #195).
     * Mutated under [lock] only.
     */
    private class Want(
        hash: String,
        var wantedAt: Long,
        var author: String?,
        var via: String?,
        puller: String?,
    ) {
        val hash8 = hash.take(LOG_HASH_CHARS)
        var cycleAt = wantedAt

        // Asked for over Wi-Fi Aware only (ADR 2026-10.js2p): set by the latest want of our own ([recordWant]). And
        // whether the last step found nobody to ask over it — the log names the hold once, not every tick.
        var fastOnly = false
        var fastHeld = false

        // The user's tap reached this want: plain for good ([recordWant]). Whether this cycle asked anyone for a
        // fast-only copy ([dead]), and the neighbours the last step saw linked over Wi-Fi Aware ([fastLinkSeen]).
        var approved = false
        var fastAsked = false
        var fastLinked: Set<String>? = null

        // Fast links [fastLinkUp] saw come up since the last step, whatever the snapshots say ([fastLinkSeen]).
        val fastEdges = HashSet<String>()

        // Whether this cycle heard of a holder or a fetcher; cycles in a row that did not widen the pull-through.
        var cycleSawLead = false
        var emptyCycles = 0

        // Dead cycles in a row before this one ([dead]): each doubles the wait for the next ([cycleGap]). When the
        // streak began, and how many dead cycles it has opened since it went [longDead] (#195).
        var deadCycles = 0
        var deadSince: Long? = null
        var longDeadCycles = 0

        // How many phones deeper this want may ask to pull its blob, counting the one asked ([PULL_BUDGET] for our own,
        // one less than the ask allowed for an echo), and until when, on the frame's clock: null for no deadline (#195).
        // And whether this cycle already counted and logged a pull it did not make.
        // 0 until whatever records the want says otherwise: a site that forgets mints no root (a refused splice's want
        // re-made after a sweep, which the next re-arm turns back into one within a tick if a row names the hash).
        var budget = 0
        var until: Long? = null
        var pullWithheld = false

        // The most bytes its stream may bring when only a profile photo wants it (#234), mirrored on [PartialBlobs.cap]
        // for the link that receives it; null for every other want, which takes the attachment ceiling. And the marked
        // holders this want has already passed over and logged ([passOver]): never more than [PartialBlobs]' cap per hash.
        var ceiling: Long? = null
        val passedOver = HashSet<String>()

        // Whether a want of our own ([want] or [wantAll] without an asker) recorded on it: until one did, only neighbours'
        // asks made it, and our first want's ceiling is the one it takes ([mergeCeiling]).
        var ownWant = false

        // Neighbours that linked in this cycle and have not been pulled since: pulled once at once ([pullNewcomers]).
        val freshLinks = HashSet<String>()

        /**
         * Why this want asks nobody to pull its blob through at [t], or null when it may (#195). With [budgets] off (the
         * iOS port's rules, `mesh/lab` only) no budget holds a pull back.
         */
        fun pullWithheldWhy(
            t: Long,
            budgets: Boolean,
        ): String? =
            when {
                until?.let { t >= it } == true -> "past its deadline"
                budget < 1 && budgets -> "fetch only"
                else -> null
            }

        // Neighbours we are pulling it for: they lack it, so this cycle neither queries nor asks them.
        val pullers = HashSet<String>().apply { puller?.let(::add) }

        // Capable neighbour -> when its query for this blob was queued, and its latest answer, this cycle.
        val queried = HashMap<String, Long>()
        val answers = HashMap<String, Answer>()

        // Holders that brought nothing this cycle, and those asked again on a confirm that said they hold it.
        val failed = HashSet<String>()
        val reasked = HashSet<String>()
        var fetch: Fetch? = null

        // Every ask, any cycle: the memo floor on asking a holder again, and #121's streaming rule.
        val askedAt = HashMap<String, Long>()

        // neighbour -> the ask of ours on it that a `blobcancel` already withdrew: one cancel per ask.
        val withdrawnAt = HashMap<String, Long>()

        /**
         * Whether our last ask on [peer] is still to be withdrawn; marks it withdrawn. Only an ask that may have queued
         * or parked a copy: one on a neighbour whose last answer said it lacked the blob (a pull-through) left nothing,
         * since a neighbour that lacks a blob remembers no asker (device rerun of 2026-10-07: a dead want's every cycle
         * sent a cancel). Under the exchange's lock.
         */
        fun withdraw(peer: String): Boolean {
            val asked = askedAt[peer] ?: return false
            return answers[peer]?.let { it.state !in HELD && it.receivedAt >= asked } != true &&
                (withdrawnAt[peer] != asked).also { withdrawnAt[peer] = asked }
        }

        // ADR 2026-10.f7gg's round over the neighbours nobody can query, and the neighbours asked to pull it through.
        val blindAsked = HashSet<String>()
        var blindWidened = false
        var blindWidenedAt: Long? = null
        val pulled = HashSet<String>()

        // How many times each neighbour asked to pull it through was queried again since ([PULL_RECHECKS]), and when
        // this cycle first asked one to.
        val pullRechecks = HashMap<String, Int>()
        var pulledAt: Long? = null

        // The armed timer's due time (monotonic ms) and token: the earliest wake wins.
        var timerDue = Long.MAX_VALUE
        var timerToken = 0

        /**
         * Whether this cycle is dead: every neighbour asked said it lacks the blob — at least one answered, none said it
         * holds or is fetching it, and nobody was asked blind (that one may hold it: ADR 2026-10.f7gg's round, and every
         * cycle with querying off, keeps its pace). Only for the first [PULL_RECHECK_DEAD_CYCLES] cycles without a lead,
         * though: past them a cycle whose blind asks brought nothing is dead too, and backs off with the rest (#195). A
         * blind ask is still made every cycle it opens — an iPhone holder can only be found that way — but an iPhone in
         * reach no longer keeps a blob nobody holds at the 45 s pace for good.
         *
         * A fast-only cycle that asked anyone is dead too, its blob still wanted at its end: its holders answer HAVE every
         * cycle but are only reachable, and a link that will not come up would otherwise cost a query, an armed ask and a
         * data-path attempt on each side every 45 s until the tap (ADR 2026-10.js2p's review). It backs off like any dead
         * cycle, and a Wi-Fi Aware link coming up re-asks that holder alone ([fastLinkSeen]), resetting nothing.
         */
        fun dead(): Boolean {
            if (fastOnly && fastAsked) return true
            val blindSpent = blindAsked.isEmpty() || emptyCycles >= PULL_RECHECK_DEAD_CYCLES
            return !cycleSawLead && (answers.isNotEmpty() || blindAsked.isNotEmpty()) && blindSpent
        }

        /**
         * How long this cycle stands before the next opens at [t]: [CYCLE_MS], doubled for each dead cycle in a row
         * before this one while this one is dead too, at most [MAX_BACKOFF_SHIFT] times (45 s, 90 s, 3 min, then 6 min).
         * The second cycle, which widens the pull-through, keeps its pace. A [longDead] streak doubles on with each
         * further dead cycle, to [MAX_LONG_BACKOFF_SHIFT] (12, 24, 48, 96 min, then about 3.2 h): a blob nobody has held
         * for a day, or one whose frame's deadline has passed, costs a query a few times a day, not ten an hour (#195).
         * A neighbour that links is still queried at once, and any lead ends the streak. Not a fast-only want: its dead
         * cycle is a Wi-Fi Aware link that did not come up, not a blob nobody holds, and it keeps the 6 min ceiling ADR
         * 2026-10.js2p set.
         */
        fun cycleGap(
            t: Long,
            dead: Boolean = dead(),
        ): Long {
            if (!dead) return CYCLE_MS
            val extra = if (longDead(t)) longDeadCycles else 0
            return CYCLE_MS shl minOf(minOf(deadCycles, MAX_BACKOFF_SHIFT) + extra, MAX_LONG_BACKOFF_SHIFT)
        }

        /**
         * Whether this want's dead streak backs off past the 6 min ceiling at [t]: it has reached the ceiling
         * ([MAX_BACKOFF_SHIFT] dead cycles), and it has been dead for [LONG_DEAD_MS] or its frame's deadline ([until],
         * on the frame's wall clock, as [pullWithheldWhy] reads it) has passed. The deadline is the trigger a restart
         * cannot forget: the streak is in memory, and a lab phone reinstalled more than daily never reached a day of it,
         * so a card past its window cycled at the ceiling for days (device log of 2026-10-19, ADR 2026-10.2drc's
         * amendment). Requiring the ceiling keeps the ramp: 45 s, 90 s, 3 min, 6 min, then 12 min onwards. Never a
         * fast-only want ([cycleGap]).
         */
        fun longDead(t: Long): Boolean =
            !fastOnly &&
                deadCycles >= MAX_BACKOFF_SHIFT &&
                (deadSince?.let { t - it >= LONG_DEAD_MS } == true || until?.let { t >= it } == true)

        /** A lead, a lifted fast-only hold or a cycle that was not dead: the backoff starts over. */
        fun endDeadStreak() {
            deadCycles = 0
            deadSince = null
            longDeadCycles = 0
        }

        /**
         * Whether the next cycle may open at [t]: no ask outstanding, and [cycleGap] since the cycle opened — or since its
         * first pull, when it pulled. A pull late in a cycle (behind a blind round, a slow answer) once let its own
         * recheck open the next cycle 10 s on and pull again; counted from the pull, its rechecks run out first. Only the
         * first pull counts, so a link that keeps flapping (a newcomer is pulled again) cannot hold a cycle open.
         */
        fun cycleDue(t: Long): Boolean = fetch == null && t - maxOf(cycleAt, pulledAt ?: cycleAt) >= cycleGap(t)

        /**
         * A neighbour said it holds the blob or is fetching it. A backed-off cycle starts over from [t], its answers kept:
         * the backoff is over, and the next cycle is due one cycle after the lead — not at once, which would forget it.
         */
        fun sawLead(t: Long) {
            // Not for a fast-only want: its holders answer HAVE every cycle while the link they need never comes up.
            if (!cycleSawLead && deadCycles > 0 && !fastOnly) {
                endDeadStreak()
                cycleAt = t
            }
            cycleSawLead = true
        }

        fun openCycle(
            t: Long,
            natural: Boolean,
        ) {
            if (natural) {
                if (dead()) {
                    // The streak's start is the first dead cycle's; once it is long dead, each one more doubles on (#195).
                    // Read before the count moves, so the 6 min cycle is kept. A deadline a re-arm lifted (a group row, a
                    // newer DM's) forgets the long count, so one restored later ramps from 12 min again.
                    if (deadCycles == 0) deadSince = cycleAt
                    if (longDead(t)) longDeadCycles++ else longDeadCycles = 0
                    deadCycles++
                } else {
                    endDeadStreak()
                }
                emptyCycles = if (cycleSawLead) 0 else emptyCycles + 1
            }
            cycleAt = t
            fetch = null // a natural close has none; a forced one (a refused splice) starts its ask over
            cycleSawLead = false
            fastAsked = false
            pullWithheld = false
            freshLinks.clear() // a link-up pulls in its own cycle or not at all
            pullers.clear()
            queried.clear()
            answers.clear()
            failed.clear()
            reasked.clear()
            blindAsked.clear()
            blindWidened = false
            blindWidenedAt = null
            pulled.clear()
            pullRechecks.clear()
            pulledAt = null
        }
    }

    /** A neighbour's answer for one blob: its state, the size it gave, its load, and when the query it answers went. */
    private class Answer(
        val state: Int,
        val size: Long?,
        val load: Int,
        val querySentAt: Long,
        // When a SENDING answer said our copy should have landed (ADR 2026-10.nsr7): [hold] waits on it that long.
        val etaAt: Long? = null,
        // When it reached us: one saying the neighbour lacks the blob, read after our ask, says the ask found it lacking.
        val receivedAt: Long = 0L,
    )

    /**
     * The one ask outstanding for a blob — to [peer], on [tier]'s plane — or a wait on a holder already sending it. Its
     * grace counts from [quietSince], the first look that found [peer] streaming nothing to us; [confirmAt] is when it
     * was queried again once that grace ran out. A blind first ask whose pick said it holds the blob is re-tiered as a
     * holder's ([fetchWait]); a [Tier.Pending] one is a hold on an ask already made, its grace running to the floor.
     */
    private class Fetch(
        val peer: String,
        val hash8: String,
        var tier: Tier,
        at: Long,
        val graceMs: Long,
    ) {
        var quietSince: Long? = at
        var confirms = 0
        var confirmAt: Long? = null
    }

    /** What an ask said beyond its hash: the prefix it named, and its budget and deadline as it carried them (#195). */
    private class Reached(
        val offset: Long?,
        val pull: Int?,
        val until: Long?,
    )

    /** The budget and deadline a want is recorded with ([recordWant], #195). */
    private class Reach(
        val budget: Int,
        val until: Long?,
        // A want whose caller read every row naming the hash (the re-arm, a delivery, the tap): its deadline replaces
        // the want's, null included.
        val rederived: Boolean = false,
        // An ask with no `pull` field: it makes a want when none exists, and keeps none alive.
        val fieldless: Boolean = false,
    )

    /** A query on its way: to whom, when, and which hashes it named. */
    private class SentQuery(
        val peer: String,
        val at: Long,
        val hashes: Set<String>,
    )

    /** A proof query on its way ([confirmHeld]): to whom, when, and the hashes it named that no answer has taken yet. */
    private class ProofQuery(
        val peer: String,
        val at: Long,
        val open: MutableSet<String>,
    )

    /** An ask parked behind another copy's feed ([parked]): the prefix it named, when it was parked, and when it last asked. */
    private class ParkedAsk(
        // What the ask said: the prefix it named, and its budget and deadline (#195).
        val ask: Reached,
        // The copy's length (the rest past [offset]): how long it would take decides whom a parked pass serves first.
        val bytes: Long,
        val since: Long,
        // When its asker last asked or queried for it.
        var at: Long,
        // How long a SENDING answer promised the asker its copy would take, and its slack: kept at least that long.
        var until: Long,
    ) {
        fun keptUntil(): Long = maxOf(at + SERVE_MEMO_MS, until)
    }

    /** One neighbour's answer budget. */
    private class Bucket(
        var tokens: Double,
        var at: Long,
    )

    /** A linked neighbour as a step reads it ([linkedNow]). */
    private class Candidate(
        val peer: Peer,
        val route: BulkRoute,
        val streaming: Boolean,
        val capable: Boolean,
        val slow: Boolean,
        // How long the file it is streaming to us has left, by its rate since its header; null when not known.
        val streamLeftMs: Long? = null,
    ) {
        val id: String get() = peer.nodeId
    }

    /**
     * What a step decided: the queries it owes, the asks it makes, and when it wants to be stepped again. [fastOnly] is
     * the want's flag as the step read it under the lock, so the asks it sends say the same (ADR 2026-10.js2p).
     */
    private class Plan(
        val fastOnly: Boolean = false,
        // The want's budget and deadline as the step read them, which every ask it sends carries (#195).
        val pull: Int = PULL_BUDGET,
        val until: Long? = null,
    ) {
        val query = mutableListOf<String>()
        val asks = mutableListOf<Ask>()
        var wake: Long? = null
        var why: String = ""
        val events = mutableListOf<BlobEvent>()

        // Neighbours whose ask of ours this step withdraws (a `blobcancel` each, sent after the step's asks).
        val cancels = mutableListOf<String>()

        fun ask(
            to: Peer,
            arm: Boolean,
            line: String,
        ) {
            asks += Ask(to, arm, line)
        }

        fun waitFor(ms: Long): Plan {
            if (ms > 0) wake = wake?.let { minOf(it, ms) } ?: ms
            return this
        }
    }

    private class Ask(
        val to: Peer,
        val arm: Boolean,
        val line: String,
    )

    /** The plane a holder's copy would ride, best first ([tierOf]); the [label] is the log's. */
    private enum class Tier(
        val label: String,
    ) {
        FastLink("fast link"),
        FastArm("fast reach"),
        Link("link"),
        SlowLink("slow link"),
        Sending("sending"),
        Blind("blind"),
        Pending("held"),
    }

    /** What put a neighbour first in a blind round ([rankOf]), best first; the [label] is the log's. */
    private enum class Rank(
        val label: String,
    ) {
        Author("author"),
        FastLink("fast link"),
        FastReach("fast reach"),
        SourceHop("source hop"),
        Neighbour("neighbour"),
    }

    /** What a step or a query did, for the owner's counters. */
    enum class BlobEvent {
        QuerySent,
        QueryAnswered,
        QueryRefused,
        AnswerTaken,
        AnswerIgnored,
        Fetch,
        Confirm,
        Fallback,
        BlindAsk,
        PullThrough,

        // An ask parked behind another copy's feed (ADR 2026-10.g24x).
        Park,

        // A progress report sent back to a file's sender, and one taken from a file's receiver (ADR 2026-10.nsr7).
        ProgressSent,
        ProgressTaken,
        CancelSent,
        CancelTaken,

        // A fast-only want's step with no neighbour reachable over Wi-Fi Aware, and a fast-only ask refused for want of
        // a Wi-Fi Aware route to its asker (ADR 2026-10.js2p).
        FastOnlyHeld,
        FastOnlyRefused,

        // An ask for a blob we lack that made no want here: it allowed no budget, or was past its deadline (#195).
        EchoDeclined,

        // A cycle that would have asked its NONE neighbours to pull the blob through and asked nobody: the frame naming
        // it is past its custody window, or the want is only a neighbour's pull, echoed past its first cycles (#195).
        PullWithheld,

        // A proof query sent ([confirmHeld]), an answer to one taken, and a hash it proved held (ADR 2026-10.vy27).
        ProofQuerySent,
        ProofAnswerTaken,
        ProofHeld,

        // A want made only for a profile photo: a holder passed over because its stream or its size passed the photo
        // ceiling, and a want ended because its author's did, or a held copy did (#234).
        HolderOverCeiling,
        OverCeiling,
    }

    companion object {
        /**
         * The addressees of a blob named by several conversations ([conversations], one entry per conversation naming
         * it, null where that conversation's are unknown — a room's, or a group this phone does not hold): their union,
         * so a member of *any* of them is a member at the holder. One sealed picture is named by several when it is
         * forwarded under its identity (ADR 2026-10.mptm): from a group G into a DM with D, where D is not in G, reading
         * the first row classed D — the DM's real recipient — a carrier behind every live feed, or G's members carriers
         * behind D's, by row order. Unknown anywhere, or no conversation at all, is null: everyone a member, as one room's
         * row always made it.
         */
        fun addresseesAcross(conversations: List<Set<String>?>): Set<String>? =
            if (conversations.isEmpty() || conversations.any { it == null }) null else conversations.flatMapTo(HashSet()) { it.orEmpty() }

        // How long an ask has to bring a header in before the next holder is tried. A holder over a live link answers
        // well inside it: the ask is one frame, and the serve reads the blob from the store and queues it. A holder that
        // can raise the Wi-Fi Aware link waits up to CompositeMeshTransport.BULK_GRACE_MS for it before it falls back to
        // Bluetooth, so an ask of a reachable one gets that on top.
        const val FIRST_ASK_GRACE_MS = 5_000L

        // How long a fetch waits for the queried neighbours' answers before it chooses among those in, and before the
        // neighbours still silent are asked blind. An answer is one small frame, but a link feeding a file interleaves
        // it only between chunks (FramedLink, ~2 s of feed each) and behind the stack's buffer (#121). A late answer
        // still counts. The 2026-10-05 trial's `blob answer … in <ms> ms` lines: p50 0.25 s, p90 2.0 s, 12 of 197
        // past 3 s and 4 past 5 s, every slow one from a holder feeding two files; at 3 s, three were asked blind.
        const val QUERY_WAIT_MS = 5_000L

        // The most a neighbour's measured round trip stretches the wait for its answer, or adds to an ask's grace (#140).
        // The 2026-10-06 trace had queries reach neighbours 13, 29 and 44 s late from an asker whose own frames were
        // queued; past this a neighbour reads as silent anyway, and an ask to a holder still stands to its floor.
        const val QUERY_WAIT_CAP_MS = 30_000L

        // While the holder we wait on streams another file to us, how often we look for that stream's end.
        const val STREAM_POLL_MS = 2_000L

        // How long a neighbour's stream of another file to us may still have before an idle holder is asked instead.
        const val STREAM_SWITCH_MS = 30_000L

        // A neighbour that said it is fetching the blob itself is queried again this long after, and one asked to pull
        // it through this long after the pull, at most PULL_RECHECKS times a cycle.
        const val FETCHING_RECHECK_MS = 10_000L
        const val PULL_RECHECKS = 4

        // A neighbour asked to pull is rechecked only in this many cycles of a dead streak (Want.deadCycles).
        const val PULL_RECHECK_DEAD_CYCLES = 2

        // How many times a dead cycle in a row doubles the wait for the next (Want.cycleGap): 6 min at most.
        private const val MAX_BACKOFF_SHIFT = 3

        // A dead streak this old doubles on past MAX_BACKOFF_SHIFT, to this many doublings at most: about 3.2 h (#195).
        // So does one past its frame's deadline (Want.until, Want.longDead), the trigger a restart cannot forget; this
        // one is for a want with no deadline (a group's picture), where a blob nobody in reach held all day is one nobody
        // is bringing.
        const val LONG_DEAD_MS = 24 * 60 * 60_000L
        const val MAX_LONG_BACKOFF_SHIFT = 8

        // How many times a holder whose grace ran out is queried again before the next holder is asked.
        const val MAX_CONFIRMS = 2

        // How many hashes one query names, and how long a hash may be; the rest of a longer query is ignored.
        const val MAX_QUERY_HASHES = 32
        private const val MAX_HASH_CHARS = 128

        // A neighbour's answer budget: this many at once, then one per refill.
        private const val ANSWER_BURST = 16
        private const val ANSWER_REFILL_MS = 1_000L
        private const val MAX_ANSWER_BUCKETS = 64
        private const val MAX_SENT_QUERIES = 256

        // How long a proof query for one (hash, peer) stands before [confirmHeld] sends another. Under the cadence its
        // caller re-asks a deferred attachment at (`ScopeSync.ATTACHMENT_RETRY_MS`, 15 s), so a round that comes a little
        // early still asks, and a caller that calls more often than that still costs a frame per 10 s at most.
        const val PROOF_FLOOR_MS = 10_000L

        // Cap on the proof-query books ([proofAsked], [proofQueries]): the caller's deferred attachments, with room.
        private const val MAX_PROOF_QUERIES = 256

        // How much of a hash, and of a query id, a log line names.
        private const val LOG_HASH_CHARS = 8
        private const val LOG_ID_CHARS = 6
        private const val NANOS_PER_MS = 1_000_000L

        // A firing timer also steps the wants due this soon after it, and a recheck due this soon goes in its frame, so
        // a batch armed together wakes and queries together. Two hashes pulled 0.5 s apart rechecked in a frame each.
        private const val TIMER_COALESCE_MS = 1_000L

        // Shorter than MeshManager's 60 s neighbor re-offer, so the periodic re-ask always passes the memo
        // and a serve whose bytes were lost (link died mid-stream) is retried on the next round.
        const val SERVE_MEMO_MS = 45_000L

        // The shortest gap between two rounds of asks for one blob. The serve memo's length, so the next round's
        // fresh ask always passes it; the 60 s tick opens a round when one is due.
        const val ROUND_MS = SERVE_MEMO_MS

        // How long a cycle's answers stand before every capable neighbour is queried again — phones move. One round.
        const val CYCLE_MS = ROUND_MS

        // Cap on distinct outstanding fetches — the memory bound on unsigned-blobreq-driven growth.
        private const val MAX_FETCHING = 256

        // Holders one photo want remembers passing over (#234): far more than a phone links in a session; past it the
        // session's own marks still filter them while they last.
        private const val MAX_PASSED_OVER = 64

        // Size cap on the in-flight serve memo (on top of its 45 s TTL).
        private const val MAX_SERVE_MEMO = 512

        // How often parked asks look for their blob's other feed to have ended, and how many blobs may have asks
        // parked at once (an unsigned blobreq can park one; the eldest goes past the cap, and its askers ask again).
        const val PARK_POLL_MS = 1_000L

        // The longest the loop sleeps between passes: a parked ask's fastest-first and beside checks move with the
        // feeds' measured rates, which a report may change at any time.
        const val PARK_POLL_MAX_MS = 10_000L

        // How long past a SENDING answer's ETA the asker holds on that holder, and the holder keeps its parked ask
        // (ADR 2026-10.nsr7), and the longest an ETA holds anyone: a holder's lie costs at most that.
        const val HOLD_SLACK_MS = 30_000L
        const val HOLD_CAP_MS = 15 * 60_000L
        private const val MS_PER_SECOND = 1_000L
        private const val MAX_PARKED_BLOBS = 64

        // A never-arriving fetch ages out of [fetching] after this (hygiene; the cap is the bound). Generous
        // so a slow-but-live transfer isn't reclaimed, since blobs can be large.
        const val FETCH_TTL_MS = 30 * 60_000L

        // How many phones deep a pull-through goes past our own neighbours, counting the first (#195). A want of ours asks
        // with this; each phone that lacks the blob wants it with one less and passes that on, and at 0 only queries
        // and fetches. 3 reaches a holder four links away — a DM's author behind the hop the frame came from and two more
        // phones that carry nothing for it — and bounds the echoes one root can make to its first three rings.
        const val PULL_BUDGET = 3

        // What a served blob is called when our own store names no type for it. Matches MeshBlobStore.fileFor,
        // which materializes the temp file under the same fallback.
        private const val FALLBACK_MIME = "image/jpeg"

        // The states that say a neighbour holds the whole blob.
        private val HELD = setOf(BlobHasItem.HAVE, BlobHasItem.SENDING)

        private fun stateLabel(state: Int): String =
            when (state) {
                BlobHasItem.NONE -> "NONE"
                BlobHasItem.HAVE -> "HAVE"
                BlobHasItem.SENDING -> "SENDING"
                BlobHasItem.FETCHING -> "FETCHING"
                else -> "state $state"
            }

        private fun itemLabel(item: BlobHasItem): String =
            "${item.hash.take(LOG_HASH_CHARS)} ${stateLabel(item.state)}${item.size?.let { " ${it}B" } ?: ""}"
    }
}
