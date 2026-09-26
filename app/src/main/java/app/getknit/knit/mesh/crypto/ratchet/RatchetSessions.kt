package app.getknit.knit.mesh.crypto.ratchet

import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.RatchetHeader
import app.getknit.knit.mesh.protocol.RatchetInit
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The session service for the DM epoch ratchet (crypto scheme v2): composes the pure [RatchetEngine]
 * with the persistent [RatchetStore] and the identity material, and owns the concurrency contract.
 * Android-free — identity access is lambda-mediated (the `KeyExchange`/`ForwardSync` style), so the
 * whole service drives under plain-JVM tests.
 *
 * **Concurrency contract.** All session mutations serialize on one [mutex], and every critical section
 * takes the Room transaction FIRST via [transact] (transaction-outer, mutex-inner — one global order,
 * no inversion). That order is enforced *here* rather than asked of callers: every locked block below
 * touches the store, Room serves this app through a single connection, and a caller that took the lock
 * without a transaction would deadlock against the decrypt path that takes them the other way round
 * (see [SessionTransactor] for the full account). Because the engine is pure,
 * decrypt is two-phase: [peekOpen] runs lock-free against a snapshot (its plaintext feeds moderation
 * and row-building, which must not sit under the lock — the text classifier can cold-load for
 * seconds), then [commitOpen] re-runs the engine on FRESH state under the lock and persists the delta
 * atomically with the caller's row write. A state change between the phases (a concurrent send, a
 * duplicate delivery on another link) simply makes the commit re-derive or report false — never a
 * lost update, never a double-spent chain step.
 */
class RatchetSessions(
    private val store: RatchetStore,
    private val dhIdentityPriv: () -> ByteArray,
    private val spkPrivFor: (Int) -> ByteArray?,
    private val engine: RatchetEngine = RatchetEngine(),
    // THE ratchet lock — shared with GroupRatchetSessions (seed adoption runs inside a DM commit, so
    // two locks would nest DM→group; one instance makes the order question vanish by construction).
    private val mutex: Mutex = Mutex(),
    // Opens the DB transaction that must enclose the lock. Shared with GroupRatchetSessions for the
    // same reason the mutex is.
    private val transact: SessionTransactor = SessionTransactor.None,
) {
    /**
     * One critical section, in the one global order: transaction OUTER, [mutex] INNER. Every locked
     * block in this class goes through here — including the ones whose caller already opened a
     * transaction, since Room's is reentrant per coroutine and uniformity is what keeps the rule
     * un-forgettable.
     */
    private suspend fun <T> locked(block: suspend () -> T): T = transact.transact { mutex.withLock { block() } }

    private val rootChangesFlow =
        MutableSharedFlow<String>(extraBufferCapacity = ROOT_CHANGES_BUFFER, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * A peer whose [exportedRoots] entry just appeared, changed or vanished — the spool plane's cue to
     * re-derive its scope table now rather than on its poll. Emitted after the critical section that made
     * the change, never under [mutex]; the collector runs on its own coroutine and reads through [locked],
     * so what it sees is the state the enclosing write transaction committed. Every writer of the session
     * row reports here except [sealDm] (an initiation is unconfirmed, and a seal never moves the root — the
     * initiator confirms in [commitOpen], when the peer's reply seals against one of our epochs) and
     * [sweep] (a drain window lapsing is the calendar's, which the poll covers). The emitted value is the
     * peer id; a collector that only wants "something moved" may ignore it.
     */
    val rootChanges: SharedFlow<String> = rootChangesFlow.asSharedFlow()

    /**
     * What [exportedRoots] would report for one session: null when there is none or it is unconfirmed,
     * else the roots the scope table keys on. Comparing before and after a commit is what keeps
     * [rootChanges] honest — a re-delivered frame, a chain step or a presentation update leaves it equal.
     */
    private class ScopeView(
        val root: ByteArray,
        val prevRoot: ByteArray?,
        val prevRootExpiresAt: Long,
    ) {
        fun sameAs(other: ScopeView?): Boolean =
            other != null &&
                root.contentEquals(other.root) &&
                (prevRoot?.contentEquals(other.prevRoot) ?: (other.prevRoot == null)) &&
                prevRootExpiresAt == other.prevRootExpiresAt
    }

    private fun scopeView(state: RatchetEngine.SessionState?): ScopeView? =
        state?.takeIf { it.confirmed }?.let { ScopeView(it.root, it.prevRoot, it.prevRootExpiresAt) }

    /** Reports [peerId] on [rootChanges] when its scope view moved between [before] and [after]. */
    private fun reportRootChange(
        peerId: String,
        before: ScopeView?,
        after: ScopeView?,
    ) {
        val same = before?.sameAs(after) ?: (after == null)
        if (!same) rootChangesFlow.tryEmit(peerId)
    }

    /** Per-peer reset heuristic state: the distinct undecryptable frame ids seen (bounded LRU). */
    private val undecryptable = HashMap<String, LinkedHashSet<String>>()

    /** In-memory outbound rate-limit fallback for peers with no session row yet (NO_SESSION resets). */
    private val lastResetSentAt = HashMap<String, Long>()

    /** Inbound session-replacement rate limit (per peer): last accepted replacement/adoption. */
    private val lastReplacementAt = HashMap<String, Long>()

    /**
     * When a session we initiated confirmed (per peer, in memory): the peer adopted that init no later than it
     * sealed the frame that confirmed it, so its replacement floor runs until at least a minute past this.
     * [sealResetDm] reads it; a restart forgets it, which only costs the floor we had before it existed.
     */
    private val confirmedAt = HashMap<String, Long>()

    /** Maps the wire header DTO to the engine's wire-agnostic mirror. */
    private fun headerOf(r: RatchetHeader): RatchetEngine.FrameHeader =
        RatchetEngine.FrameHeader(
            se = r.se,
            ek = r.ek,
            pe = r.pe,
            n = r.n,
            init = r.init?.let { RatchetEngine.InitPayload(eph = it.eph, pkid = it.pkid, at = it.at) },
            flags = r.flags,
        )

    private suspend fun contextFor(
        selfNodeId: String,
        peerId: String,
        peerIkPub: ByteArray,
        header: RatchetEngine.FrameHeader,
        now: Long,
    ): RatchetEngine.OpenContext =
        RatchetEngine.OpenContext(
            selfNodeId = selfNodeId,
            peerId = peerId,
            session = store.session(peerId),
            recvEpoch = store.recvEpoch(peerId, header.se),
            skippedMsgKey = store.skippedKey(peerId, header.se, header.n),
            ownBasePriv = if (header.pe >= 1) store.localEpochPriv(peerId, header.pe) else null,
            ownIkPriv = dhIdentityPriv(),
            peerIkPub = peerIkPub,
            spkPrivForInit = header.init?.let { spkPrivFor(it.pkid) },
            resetRequested = header.flags and RatchetHeader.FLAG_RESET != 0,
            // An explicit reset request gets a far shorter floor than an incidental init. `FLAG_RESET` was
            // minted for this and, until ADR 023, was written on the wire and never read — so a peer that
            // had waited out its own 6 h reset floor could still be refused here for another 60 minutes,
            // silently, and the pair stayed wedged. The sender's floor is the real rate limit and is 6×
            // stricter; a peer ignoring it is buggy or hostile, and since it is a pinned contact the only
            // conversation it can churn is the one it is already party to. The short floor remains so that
            // even then the cost is bounded.
            allowReplacement =
                synchronized(lastReplacementAt) {
                    val floor =
                        if (header.flags and RatchetHeader.FLAG_RESET != 0) {
                            RESET_REPLACEMENT_MIN_INTERVAL_MS
                        } else {
                            REPLACEMENT_MIN_INTERVAL_MS
                        }
                    now - (lastReplacementAt[peerId] ?: 0L) >= floor
                },
        )

    /**
     * Phase one of a v2 decrypt: opens the frame against a state snapshot WITHOUT persisting anything.
     * The plaintext (on [RatchetEngine.OpenOutcome.Opened]) is safe to hand to moderation/row-building;
     * the delta inside the outcome must be ignored — [commitOpen] re-derives it.
     */
    suspend fun peekOpen(
        selfNodeId: String,
        peerId: String,
        peerIkPub: ByteArray,
        wireHeader: RatchetHeader,
        nonce: ByteArray?,
        ct: ByteArray,
        aad: ByteArray,
        now: Long,
    ): RatchetEngine.OpenOutcome {
        val header = headerOf(wireHeader)
        return engine.open(contextFor(selfNodeId, peerId, peerIkPub, header, now), header, nonce, ct, aad, now)
    }

    /**
     * Phase two: re-opens on fresh state under the session lock and, on success, persists the ratchet
     * delta and runs [onOpened] (the caller's row write) in the same enclosing Room transaction —
     * callers MUST wrap this call in `db.withWriteTransaction { }` when they persist anything alongside it.
     * Returns false when the frame no longer opens (a concurrent delivery already consumed it, or
     * state moved on) — benign; the caller's exists/isNew gates make the visible outcome idempotent.
     */
    suspend fun commitOpen(
        selfNodeId: String,
        peerId: String,
        peerIkPub: ByteArray,
        wireHeader: RatchetHeader,
        nonce: ByteArray?,
        ct: ByteArray,
        aad: ByteArray,
        now: Long,
        onOpened: suspend () -> Unit,
    ): Boolean {
        var before: ScopeView? = null
        var after: ScopeView? = null
        val committed =
            locked {
                val header = headerOf(wireHeader)
                val ctx = contextFor(selfNodeId, peerId, peerIkPub, header, now)
                val outcome = engine.open(ctx, header, nonce, ct, aad, now)
                if (outcome !is RatchetEngine.OpenOutcome.Opened) return@locked false
                store.applyOpen(peerId, outcome.delta, headerSe = header.se, headerN = header.n)
                if (outcome.delta.purgePeerRecvState) {
                    // A replacement was adopted: start its rate-limit window and clear the reset heuristic —
                    // the session is fresh, old failures are moot.
                    synchronized(lastReplacementAt) { lastReplacementAt[peerId] = now }
                    synchronized(undecryptable) { undecryptable.remove(peerId) }
                }
                if (answeredOurInit(ctx.session, outcome.delta.session)) synchronized(confirmedAt) { confirmedAt[peerId] = now }
                onOpened()
                before = scopeView(ctx.session)
                after = scopeView(outcome.delta.session)
                true
            }
        if (committed) reportRootChange(peerId, before, after)
        return committed
    }

    /**
     * Seals one outbound DM under the peer's session, creating it (X3DH against [peerSpk]) on first
     * use and advancing epochs per the engine's rules. Runs read → seal → persist atomically under the
     * session lock and returns the finished [EncEnvelope] — v2, or v3 when [scheme] says the peer reads it
     * (the caller decides that from the pinned profile, and [plaintext] must already be laid out for the
     * scheme it names — `MessageContent.sealBytes`); the caller floods it and saves its own
     * plaintext row afterwards (a crash between this commit and the flood is just a chain hole the
     * receiver's skipped-key path absorbs — nothing received can be lost, unlike the open side).
     *
     * Returns null when no epoch base exists — an established session whose peer has since cleared its
     * prekey and contributed no epoch, or a first send with [peerSpk] null. Callers treat null as
     * "fall back to v1", which the peer can always read.
     */
    suspend fun sealDm(
        peerId: String,
        peerIkPub: ByteArray,
        peerSpk: RatchetEngine.PeerPrekey?,
        plaintext: ByteArray,
        aad: ByteArray,
        now: Long,
        scheme: Int = EncEnvelope.VERSION_RATCHET,
    ): EncEnvelope? =
        locked {
            require(EncEnvelope.isDmRatchetVersion(scheme)) { "not a DM ratchet scheme: $scheme" }
            val existing = store.session(peerId)
            val initiation =
                if (existing == null) {
                    peerSpk ?: return@locked null
                    engine.initiate(peerId, dhIdentityPriv(), peerIkPub, peerSpk, now)
                } else {
                    null
                }
            val session = initiation?.session ?: existing ?: return@locked null
            val sealed =
                engine.seal(session, plaintext, aad, peerSpk?.pub, now, v3 = scheme == EncEnvelope.VERSION_DM_V3)
                    ?: return@locked null
            store.commitSend(sealed.session, initiation?.epoch ?: sealed.newLocalEpoch)
            val h = sealed.header
            EncEnvelope(
                v = scheme,
                // v3 derives its nonce; the field still rides, empty, so a pre-v3 build decodes (and carries) the frame.
                nonce = sealed.nonce ?: ByteArray(0),
                ct = sealed.ct,
                keys = emptyList(),
                r =
                    RatchetHeader(
                        se = h.se,
                        ek = h.ek,
                        pe = h.pe,
                        n = h.n,
                        init = h.init?.let { RatchetInit(eph = it.eph, pkid = it.pkid, at = it.at) },
                        flags = h.flags,
                    ),
            )
        }

    /**
     * Records an undecryptable v2 frame ([RatchetEngine.OpenOutcome.Failed.NO_SESSION] /
     * [RatchetEngine.OpenOutcome.Failed.EPOCH_GONE] / [RatchetEngine.OpenOutcome.Failed.AEAD_FAIL]) from a
     * pinned peer and decides whether a session reset is due: at least [RESET_DISTINCT_FRAMES] **distinct**
     * frame ids (custody re-serves the same frame endlessly — one stuck frame must not trigger anything),
     * and not more often than [RESET_MIN_INTERVAL_MS] per peer (persisted on the session row where one
     * exists, so restarts don't bypass it; the in-memory fallback covers the no-session case).
     *
     * `AEAD_FAIL` is the split-brain case — both sides hold a session and the roots disagree — and unlike
     * the other two it never resolves on its own, so it must be able to trigger a reset like they do.
     *
     * The distinct-id rule bounds one stuck frame, not a stuck *era*: a re-served backlog is many distinct
     * ids and walks straight through it. Callers must therefore gate on liveness first — see
     * `InboundPipeline.isLiveEvidence` — so frames that were unreadable before they arrived never reach
     * this counter at all.
     */
    suspend fun noteUndecryptable(
        peerId: String,
        frameId: String,
        now: Long,
    ): Boolean {
        val distinct =
            synchronized(undecryptable) {
                val ids = undecryptable.getOrPut(peerId) { LinkedHashSet() }
                ids.add(frameId)
                while (ids.size > RESET_TRACKED_FRAMES) ids.remove(ids.first())
                ids.size
            }
        if (distinct < RESET_DISTINCT_FRAMES) return false
        val persisted = store.session(peerId)?.lastResetSentAt ?: 0L
        val inMemory = synchronized(lastResetSentAt) { lastResetSentAt[peerId] ?: 0L }
        return now - maxOf(persisted, inMemory) >= RESET_MIN_INTERVAL_MS
    }

    /**
     * Seals a session **reset request** toward [peerId]: the `ctl` reset marker in [plaintext], which makes the
     * peer re-seal its recent unacked DMs to us and re-send its group seeds. Stamps the outbound rate limit
     * whenever it seals. Usually that is a fresh X3DH initiation replacing any local session, carrying
     * [RatchetHeader.FLAG_RESET] (the old root drains via prevRoot; our epoch numbering restarts, and the peer's
     * replacement handling purges its stale rows).
     *
     * **A reset the peer would refuse is never sealed** (ADR 2026-09.qerd). The peer adopts a `FLAG_RESET`
     * init only a minute after the last replacement it adopted from us (`RESET_REPLACEMENT_MIN_INTERVAL_MS`),
     * and one it refuses is worse than none: we have purged the root it is still on, it drops ours as a
     * duplicate, and our own heuristic is floored for six hours. So, against the session we hold:
     *
     * - **our own init, not yet answered, against the peer's current prekey:** a plain one (a first `sealDm`)
     *   is *marked*, whatever its age — the peer may be adopting it from custody right now, and a second root
     *   would land inside the floor that adoption starts. The marker is sealed under it with its header
     *   flagged, so the peer resolves the same init idempotently or adopts it under the short floor. One that
     *   already is a reset (or was marked) is declined for a minute, then re-rooted as usual: only a fresh
     *   init outlives a peer that judges this one's `at` stale.
     * - **our own init, answered under a minute ago, on the heuristic's word ([ResetCause.UNREADABLE]):**
     *   declined. The frames that failed to open prove the peer held another session with us, so taking our
     *   init was a replacement and started its floor — no later than it sealed the answer. The heuristic fires
     *   again on the next failure. An on-demand reset has no such evidence (on first contact the peer
     *   *established*, which starts no floor) and goes out.
     * - **[ResetCause.AFTER_WIPE]** (the first start after a backup restore, whose ratchet tables came back
     *   empty): any session present was made since the wipe, so it only needs the marker — declined when a
     *   reset already went on it, marked otherwise, re-rooted only past a pending init against a prekey the
     *   peer has left.
     *
     * A marker seals v2 like every reset: the flag is not bound into a v2 frame's AEAD, and it goes on the
     * header only while an init rides there (the only place a receiver reads it).
     *
     * A re-root purges **our own** receive state too ([RatchetStore.purgePeerRecvState]) — the half this used
     * to leave behind. Abandoning a root era is symmetric: the peer drops its stale rows when it adopts this
     * init, and we must drop ours, or its post-replacement epochs meet a surviving chain index from the dead era.
     */
    suspend fun sealResetDm(
        peerId: String,
        peerIkPub: ByteArray,
        peerSpk: RatchetEngine.PeerPrekey?,
        plaintext: ByteArray,
        aad: ByteArray,
        now: Long,
        cause: ResetCause = ResetCause.ON_DEMAND,
    ): ResetSeal {
        var before: ScopeView? = null
        var reRooted = false
        val result =
            locked {
                peerSpk ?: return@locked ResetSeal.NoPrekey
                val old = store.session(peerId)
                when (val plan = resetPlan(peerId, old, peerSpk, now, cause)) {
                    is ResetPlan.Decline -> {
                        ResetSeal.Declined(plan.reason)
                    }

                    ResetPlan.Mark -> {
                        markReset(checkNotNull(old), peerSpk, plaintext, aad, now)
                    }

                    ResetPlan.ReRoot -> {
                        before = scopeView(old)
                        reRooted = true
                        reRoot(peerId, old, peerIkPub, peerSpk, plaintext, aad, now)
                    }
                }
            }
        // The replacement we just minted is unconfirmed, so the peer's scopes leave the table until it
        // answers — a view change like any other. A marker leaves the root where it was.
        if (reRooted && result is ResetSeal.Sealed) reportRootChange(peerId, before, after = null)
        return result
    }

    /** Our own init answered — not a race lost to the peer's, which also confirms us but as responder. */
    private fun answeredOurInit(
        was: RatchetEngine.SessionState?,
        next: RatchetEngine.SessionState,
    ): Boolean = was?.weAreInitiator == true && !was.confirmed && next.confirmed && next.weAreInitiator

    /** What [sealResetDm] should do against [old]; see its KDoc for the rules. Runs under the lock. */
    private fun resetPlan(
        peerId: String,
        old: RatchetEngine.SessionState?,
        peerSpk: RatchetEngine.PeerPrekey,
        now: Long,
        cause: ResetCause,
    ): ResetPlan {
        if (old == null) return ResetPlan.ReRoot
        val pendingInit = old.weAreInitiator && !old.confirmed
        // An init against a prekey the peer has since replaced may never be adoptable: only a fresh one heals it.
        if (pendingInit && old.initPkid != peerSpk.id) return ResetPlan.ReRoot
        if (cause == ResetCause.AFTER_WIPE) {
            return if (old.lastResetSentAt > 0L) ResetPlan.Decline("a reset already went since the restore") else ResetPlan.Mark
        }
        if (pendingInit) {
            // initiate() stamps establishedAt with the same `now` a reset stamps; a plain sealDm init leaves 0.
            if (old.lastResetSentAt < old.establishedAt) return ResetPlan.Mark
            return if (now - old.lastResetSentAt < RESET_REPLACEMENT_MIN_INTERVAL_MS) {
                ResetPlan.Decline("our reset is still on its way")
            } else {
                ResetPlan.ReRoot
            }
        }
        val answeredAt = synchronized(confirmedAt) { confirmedAt[peerId] }
        val insideFloor = answeredAt != null && now - answeredAt < RESET_REPLACEMENT_MIN_INTERVAL_MS
        if (cause == ResetCause.UNREADABLE && old.weAreInitiator && insideFloor) {
            return ResetPlan.Decline("the peer adopted our init under a minute ago")
        }
        return ResetPlan.ReRoot
    }

    /** The reset marker under [old], its root untouched: stamped like any reset, never purged. Runs under the lock. */
    private suspend fun markReset(
        old: RatchetEngine.SessionState,
        peerSpk: RatchetEngine.PeerPrekey,
        plaintext: ByteArray,
        aad: ByteArray,
        now: Long,
    ): ResetSeal {
        val sealed = engine.seal(old, plaintext, aad, peerSpk.pub, now) ?: return ResetSeal.NoPrekey
        store.commitSend(sealed.session.copy(lastResetSentAt = now), sealed.newLocalEpoch)
        synchronized(lastResetSentAt) { lastResetSentAt[old.peerId] = now }
        synchronized(undecryptable) { undecryptable.remove(old.peerId) }
        val flags = if (sealed.header.init != null) RatchetHeader.FLAG_RESET else 0
        return ResetSeal.Sealed(resetEnvelope(sealed, flags), reRooted = false)
    }

    /** A fresh initiation replacing [old]: the reset as it always was. Runs under the lock. */
    private suspend fun reRoot(
        peerId: String,
        old: RatchetEngine.SessionState?,
        peerIkPub: ByteArray,
        peerSpk: RatchetEngine.PeerPrekey,
        plaintext: ByteArray,
        aad: ByteArray,
        now: Long,
    ): ResetSeal {
        val initiation = engine.initiate(peerId, dhIdentityPriv(), peerIkPub, peerSpk, now)
        val session =
            initiation.session.copy(
                prevRoot = old?.root,
                prevRootWeAreInitiator = old?.weAreInitiator ?: false,
                prevRootExpiresAt = if (old != null) now + RatchetEngine.PREV_ROOT_TTL_MS else 0L,
                lastResetSentAt = now,
            )
        val sealed = engine.seal(session, plaintext, aad, peerSpk.pub, now) ?: return ResetSeal.NoPrekey
        // Abandon our receive side along with the root. The peer purges its stale rows when it adopts
        // this init; nothing was doing the same for ours, so a recv epoch from the dead era survived and
        // the peer's post-replacement frames — whose epoch numbers may reuse the old ones — were judged
        // against its stale chain index and dropped as DUPLICATE. That is unrecoverable by construction:
        // a duplicate is benign, so it drives no reset, and the pair deadlocks in the one direction.
        store.purgePeerRecvState(peerId)
        store.commitSend(sealed.session, initiation.epoch)
        synchronized(lastResetSentAt) { lastResetSentAt[peerId] = now }
        synchronized(undecryptable) { undecryptable.remove(peerId) }
        synchronized(confirmedAt) { confirmedAt.remove(peerId) }
        return ResetSeal.Sealed(resetEnvelope(sealed, RatchetHeader.FLAG_RESET), reRooted = true)
    }

    /** A reset always seals v2 — the most compatible form toward a peer that may have reinstalled. */
    private fun resetEnvelope(
        sealed: RatchetEngine.SealResult,
        flags: Int,
    ): EncEnvelope {
        val h = sealed.header
        return EncEnvelope(
            v = EncEnvelope.VERSION_RATCHET,
            nonce = checkNotNull(sealed.nonce),
            ct = sealed.ct,
            keys = emptyList(),
            r =
                RatchetHeader(
                    se = h.se,
                    ek = h.ek,
                    pe = h.pe,
                    n = h.n,
                    init = h.init?.let { RatchetInit(eph = it.eph, pkid = it.pkid, at = it.at) },
                    flags = flags,
                ),
        )
    }

    private sealed interface ResetPlan {
        data object ReRoot : ResetPlan

        data object Mark : ResetPlan

        class Decline(
            val reason: String,
        ) : ResetPlan
    }

    /** Why [sealResetDm] is asked for a reset: what the caller knows about the session it would replace. */
    enum class ResetCause {
        /** Asked for directly (the debug bridge): nothing is known about the peer's state. */
        ON_DEMAND,

        /** The heuristic: the peer's frames failed to open, so it held a session with us other than ours. */
        UNREADABLE,

        /** The first start after a backup restore: every session present was made since the ratchet tables emptied. */
        AFTER_WIPE,
    }

    /** What [sealResetDm] did. */
    sealed interface ResetSeal {
        /** A reset to flood: a fresh root when [reRooted], else the marker under the session we already hold. */
        class Sealed(
            val envelope: EncEnvelope,
            val reRooted: Boolean,
        ) : ResetSeal

        /** Nothing sealed: the peer would refuse a new root now, or already has the reset it needs. */
        class Declined(
            val reason: String,
        ) : ResetSeal

        /** The peer has no usable prekey. */
        data object NoPrekey : ResetSeal
    }

    /**
     * Read-only snapshot of one peer's session row (no mutation). Drives the bridge's ratchet
     * diagnostics and, since ADR 024, the inbound path's `InboundPipeline.isLiveEvidence` gate — so it
     * is on the decrypt-failure hot path, not debug-only.
     */
    suspend fun sessionFor(peerId: String): RatchetEngine.SessionState? = locked { store.session(peerId) }

    /** Debug-bridge ground truth for `EPOCH_GONE`: which of our epoch privs for [peerId] actually survive. */
    suspend fun debugLocalEpochs(peerId: String): List<Pair<Int, Long>> = locked { store.debugLocalEpochs(peerId) }

    /** Retention GC passthrough (wired into the existing sweep loops). */
    suspend fun sweep(now: Long) = locked { store.sweep(now) }

    /**
     * Drops every ratchet row held for [peerId] — the session and our epoch privs, their recv epochs and
     * skipped keys — and says whether there was a session to drop. Not a reset: nothing is sent, and the
     * next frame either way starts from nothing. Exists for the one peer id that can never be a peer, our
     * own: builds before 2026-09-13 opened a session with themselves off a self-pinned `peers` row, and
     * `PeerRepository.forgetSelf` takes the row but not the session behind it.
     */
    suspend fun forget(peerId: String): Boolean {
        var before: ScopeView? = null
        val had =
            locked {
                val state = store.session(peerId)
                before = scopeView(state)
                if (state != null) store.deletePeer(peerId)
                synchronized(confirmedAt) { confirmedAt.remove(peerId) }
                state != null
            }
        if (had) reportRootChange(peerId, before, after = null)
        return had
    }

    /**
     * The spool plane's key material for every confirmed session: `pairwiseRoot` exports, never raw
     * session roots. Deliberately exported here rather than letting the plane read [RatchetStore], so
     * session secrets stay behind this facade and its mutex — the plane only ever sees the one-way
     * derivation `docs/SPOOL_PROTOCOL.md` §3.1 names.
     *
     * Unconfirmed sessions are skipped: the two sides may still be resolving a replacement race, and a
     * scope derived from a root that is about to be discarded is churn with no continuity value.
     */
    suspend fun exportedRoots(): List<ExportedRoots> =
        locked {
            store.sessionPeerIds().mapNotNull { peerId ->
                val state = store.session(peerId)?.takeIf { it.confirmed } ?: return@mapNotNull null
                ExportedRoots(
                    peerId = peerId,
                    pairwiseRoot = RatchetCrypto.exportRoot(state.root),
                    prevPairwiseRoot = state.prevRoot?.let { RatchetCrypto.exportRoot(it) },
                    prevRootExpiresAt = state.prevRootExpiresAt,
                )
            }
        }

    /**
     * One peer's exported scope roots: the active one plus, until [prevRootExpiresAt], the retiring
     * session's — the drain window that keeps a replaced session's blobs reachable.
     */
    class ExportedRoots(
        val peerId: String,
        val pairwiseRoot: ByteArray,
        val prevPairwiseRoot: ByteArray?,
        val prevRootExpiresAt: Long,
    )

    companion object {
        /** Distinct undecryptable frames from one peer before a reset request fires. */
        const val RESET_DISTINCT_FRAMES = 3

        /** Bound on the per-peer undecryptable-id LRU. */
        const val RESET_TRACKED_FRAMES = 8

        /** Outbound reset floor per peer (persisted on the session row). */
        const val RESET_MIN_INTERVAL_MS = 6 * 60 * 60_000L

        /**
         * How long a tail sealed under an era we have left can still be re-served at us: the mesh
         * custody TTL (`ForwardRepository.DEFAULT_TTL_MS`, 24 h) doubled — which is also the spool's own
         * default scope retention (`ScopeRegistry.DEFAULT_TTL_MS`) and the margin behind
         * `RatchetRepository.SKIPPED_TTL_MS` / `RECV_EPOCH_TTL_MS`. Past it nothing from the old era
         * survives anywhere to be re-served, so an unreadable frame cannot be a remnant of it.
         *
         * `InboundPipeline.isLiveEvidence` uses this as the escape hatch that keeps clock skew from
         * disabling the heuristic outright — see ADR 026.
         */
        const val STRANDED_TAIL_MS = 48 * 60 * 60_000L

        /** Inbound session-replacement floor per peer (in-memory). */
        const val REPLACEMENT_MIN_INTERVAL_MS = 60 * 60_000L

        /**
         * The same floor for an init carrying [RatchetHeader.FLAG_RESET] — a peer explicitly asking to
         * re-establish, not an incidental init. Short enough that genuine recovery is never refused (the
         * sender's own 6 h floor already rate-limits it), long enough to bound the churn a peer ignoring
         * that floor can cost us.
         */
        const val RESET_REPLACEMENT_MIN_INTERVAL_MS = 60_000L

        /** Pending [rootChanges] a slow collector may lag by before the oldest is dropped; it only needs the newest. */
        private const val ROOT_CHANGES_BUFFER = 64
    }
}
