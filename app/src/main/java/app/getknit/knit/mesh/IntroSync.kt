package app.getknit.knit.mesh

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/** Where a contact-card intro stands with one peer, for the profile/chat status line. */
enum class IntroState {
    /** The peer is pinned but its prekey has not arrived yet, so nothing can be sealed to it. */
    AWAITING_PREKEY,

    /** Our intro is out (flooded, custodied, pushed to the pair scope); the peer's reply hasn't confirmed us. */
    SENT,

    /** The ratchet session confirmed — the ordinary DM scope exists and the pair scope is in its grace window. */
    CONNECTED,
}

/**
 * The intro driver's durable state — a handful of peer ids, kept in the settings store beside the accepted
 * set rather than in Room (the ADR 028/037 posture for state that is ids, not rows). [pending] maps a peer
 * to when its intro was registered (oldest evicted at the cap); [grace] maps a confirmed peer to when its
 * pair scope may be dropped.
 */
interface IntroStore {
    suspend fun pending(): Map<String, Long>

    suspend fun grace(): Map<String, Long>

    /** Replaces both maps in one write, so a transition can never leave them disagreeing. */
    suspend fun write(
        pending: Map<String, Long>,
        grace: Map<String, Long>,
    )
}

/**
 * Drives the **contact-card intro**: the handshake that turns a peer pinned from an out-of-band card into
 * a confirmed DM ratchet session — which is the moment the ordinary spool DM scope exists on both sides
 * (`RatchetSessions.exportedRoots`) and the moment `IntroState.CONNECTED` means anything. No new frame:
 * the intro is a sealed `CTL_PROFILE` DM (`MeshManager.sendIntroTo`), whose X3DH init rides every copy
 * until the peer answers, exactly like a first message would. Plane-agnostic by construction — the frame
 * floods, custodies, rides LoRa (DM-form) and the pair scope (spec §3.5); the driver only decides *when*.
 *
 * Three rules, each closing a way the handshake can otherwise stall:
 *
 * - **Send as soon as sealing is possible, then re-send on a floor while unconfirmed.** The peer's prekey
 *   may land from any plane at any time ([onProfilePinned]); once it has, one intro goes out, and while
 *   the session stays unconfirmed a fresh copy goes out every [resendFloorMs] (shorter than the 24 h custody
 *   TTL and the 48 h spool TTL, so a copy always exists somewhere the peer can pull it from). A re-send
 *   carries the same init, which the peer's engine treats as already resolved — idempotent.
 * - **Answer an unconfirmed peer.** A frame whose ratchet header still carries the X3DH init is proof its
 *   sender has not seen a frame of ours yet (an initiator attaches the init until confirmed). Answering with
 *   one sealed frame ([onPeerFrameOpened]) — once per init, and again only after [answerFloorMs] — is what
 *   confirms *their* side, whether the intro came from a card, a beacon, a wipe-and-reinstall or a session
 *   reset. `broadcastSealedProfile` would also do it, but only once per profile version, which is the "wiped
 *   initiator stays unconfirmed" gap.
 * - **Grace after confirmation.** The pair scope is what carries our confirming frame to a peer that has
 *   no DM scope yet, so it stays subscribed for [graceMs] after *our* session confirms, then goes away
 *   ([pairPeers] is what `ScopeRegistry` derives pair scopes from).
 *
 * Pure and Android-free: the store, the seal/send/confirm probes, the metrics and the clock are injected,
 * so the whole state machine drives under plain-JVM tests (`IntroSyncTest`). Every send happens outside
 * [lock] — a send takes the ratchet mutex and the DB, and this class must never hold its own lock across
 * either (`rules/mesh.md`, the transaction-outer/mutex-inner order).
 */
class IntroSync(
    private val store: IntroStore,
    // Whether a sealed DM to the peer can be built right now: pinned bundle + CAP_RATCHET + a prekey.
    private val canSeal: suspend (String) -> Boolean,
    // Seals and originates one intro (a CTL_PROFILE DM) to the peer; false when the seal was refused.
    private val sendIntro: suspend (String) -> Boolean,
    // Whether our ratchet session with the peer is confirmed — the "connected" fact this driver reports.
    private val sessionConfirmed: suspend (String) -> Boolean,
    // The pair-scope inputs moved: the set [pairPeers] names differs from the last publish, or a pending
    // peer's bundle was just pinned (the one pair input this driver does not hold — `ScopeRegistry` derives
    // a pair scope only once the peer's key is known). The spool plane re-derives its table on it instead
    // of on its poll. May fire under [lock], so the callback must not block or call back into this driver.
    private val onPairsChanged: () -> Unit = {},
    private val metrics: MeshMetrics = MeshMetrics(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val resendFloorMs: Long = RESEND_FLOOR_MS,
    private val answerFloorMs: Long = ANSWER_FLOOR_MS,
    private val graceMs: Long = GRACE_MS,
    private val maxPending: Int = MAX_PENDING,
) {
    /** Serializes the read-modify-write on the store; never held across a send. */
    private val lock = Mutex()

    // In-memory floors: a restart re-sends once, which the receiver absorbs idempotently.
    private val lastSentAt = ConcurrentHashMap<String, Long>()
    private val lastAnswered = ConcurrentHashMap<String, Answered>()

    /** The init we last answered for a peer (its X3DH ephemeral) and when. */
    private class Answered(
        val initEph: ByteArray,
        val at: Long,
        val resetFlagged: Boolean,
    )

    /** The UI's view — re-published after every transition so `state(peer)` reacts without polling the store. */
    private val states = MutableStateFlow<Map<String, IntroState>>(emptyMap())

    /** The [pairPeers] set as of the last publish; null until [prime] seeds it, so a restart fires nothing. */
    @Volatile
    private var lastPairs: Set<String>? = null

    /**
     * Registers an intro with [peerId] (the import seam). Idempotent; a peer whose session is already
     * confirmed needs no intro and is ignored. Beyond [maxPending] the oldest registration is dropped.
     * Sends immediately when the peer's prekey is already pinned (a LoRa beacon or a radio flood may
     * have delivered it long before the card arrived).
     */
    suspend fun want(peerId: String) {
        if (sessionConfirmed(peerId)) return
        val now = clock()
        lock.withLock {
            val pending = store.pending().toMutableMap()
            val grace = store.grace().toMutableMap()
            if (peerId in pending) return
            grace.remove(peerId)
            pending[peerId] = now
            while (pending.size > maxPending) {
                val oldest = pending.minByOrNull { it.value }?.key ?: break
                pending.remove(oldest)
                lastSentAt.remove(oldest)
            }
            store.write(pending, grace)
            publish(pending, grace)
        }
        trySend(peerId, now)
    }

    /**
     * [want], for a caller with no urgency behind it: registers the intro only while there is room under
     * [maxPending], and never evicts one the user asked for. The commons' member sweep uses it — a room of
     * thirty connects its pairs eight at a time as sessions confirm, rather than churning the set.
     */
    suspend fun wantIfRoom(peerId: String) {
        if (sessionConfirmed(peerId)) return
        val room = lock.withLock { store.pending().let { peerId in it || it.size < maxPending } }
        if (room) want(peerId)
    }

    /** A profile for [peerId] was pinned on some plane: if an intro to it is pending, it can be sealed now. */
    suspend fun onProfilePinned(peerId: String) {
        if (peerId !in lock.withLock { store.pending() }) return
        // A pair scope needs the bundle the pipeline just pinned; the table can derive it now.
        onPairsChanged()
        if (!settle(peerId)) trySend(peerId, clock())
    }

    /**
     * A v2 frame from [peerId] opened. A non-null [initEph] is the X3DH init its header still carried — the
     * peer is unconfirmed — so answer it: at once for an init we have not answered, and for the same init
     * again only after [answerFloorMs] (a peer re-floods its init on every frame until it confirms). Either
     * way, check whether *our* session with a pending peer just confirmed and move it into grace.
     *
     * The floor is per init, not per peer, because a session reset is a new init that needs its own answer:
     * the resetter confirms only on a frame the peer seals under the new root, and after a reset the peer
     * often has nothing else to send — its re-seals reuse ids we already hold, so they never reach the
     * ratchet. A per-peer floor left the resetter unconfirmed, with no spool scope for the pair, for up to an
     * hour after any earlier answer (#87). A new init only gets here by opening, and every way one opens is
     * rate-limited by the ratchet's replacement floors or is one answer per frame the peer sent.
     *
     * [resetFlagged] earns the same init one more answer. A peer marks the init it is waiting on as a reset by
     * sealing the reset under it (ADR 2026-09.qerd); if we first read that init read-only, as a race remnant,
     * our answer went out under the old session and the marked frame is the one we actually adopt. Only one
     * marked frame reaches here per mark — its re-serves end as duplicates in the ratchet.
     */
    suspend fun onPeerFrameOpened(
        peerId: String,
        initEph: ByteArray?,
        resetFlagged: Boolean = false,
    ) {
        settle(peerId)
        if (initEph == null) return
        val now = clock()
        val answered = lastAnswered[peerId]?.takeIf { it.initEph.contentEquals(initEph) && (it.resetFlagged || !resetFlagged) }
        if (answered != null && now - answered.at < answerFloorMs) return
        if (!canSeal(peerId)) return
        lastAnswered[peerId] = Answered(initEph, now, resetFlagged)
        if (sendIntro(peerId)) metrics.onIntroAnswered() else lastAnswered.remove(peerId)
    }

    /** The heal hook: settle confirmed peers, re-send stale pending intros, and expire finished grace windows. */
    suspend fun retry() {
        val now = clock()
        val pending = lock.withLock { store.pending() }
        for (peerId in pending.keys) {
            if (!settle(peerId)) trySend(peerId, now)
        }
        lock.withLock {
            val grace = store.grace()
            val live = grace.filterValues { it > now }
            if (live.size != grace.size) {
                val current = store.pending()
                store.write(current, live)
                publish(current, live)
            }
        }
    }

    /** The peers a pair scope exists for: every pending intro plus every confirmed one still in grace. */
    suspend fun pairPeers(): Set<String> = lock.withLock { pairKeys(store.pending(), store.grace()) }

    /** Where the intro with [peerId] stands, or null when none is pending or recently confirmed. */
    fun state(peerId: String): Flow<IntroState?> = states.map { it[peerId] }.distinctUntilChanged()

    /**
     * Loads the store into the published view — call once after construction so the UI sees restarts. Seeds
     * the pair set silently: the plane's own start derives the table, so the first publish is not a change.
     */
    suspend fun prime() =
        lock.withLock {
            val pending = store.pending()
            val grace = store.grace()
            lastPairs = pairKeys(pending, grace)
            publish(pending, grace)
        }

    /**
     * Moves [peerId] from pending into grace when our session with it has confirmed. Returns true when
     * the peer is (now) settled, i.e. no intro needs sending.
     */
    private suspend fun settle(peerId: String): Boolean {
        if (!sessionConfirmed(peerId)) return false
        lock.withLock {
            val pending = store.pending().toMutableMap()
            if (pending.remove(peerId) == null) return true
            val grace = store.grace().toMutableMap()
            grace[peerId] = clock() + graceMs
            store.write(pending, grace)
            publish(pending, grace)
        }
        lastSentAt.remove(peerId)
        return true
    }

    /**
     * One intro to a pending peer, outside the lock: the first send goes out as soon as sealing is
     * possible, a repeat only once [resendFloorMs] has elapsed; the floor is recorded on success only, so
     * a refused seal is retried on the next cue.
     */
    private suspend fun trySend(
        peerId: String,
        now: Long,
    ) {
        val last = lastSentAt[peerId]
        if (last != null && now - last < resendFloorMs) return
        if (!canSeal(peerId)) return
        if (!sendIntro(peerId)) return
        lastSentAt[peerId] = now
        metrics.onIntroSent()
        publish(lock.withLock { store.pending() }, lock.withLock { store.grace() })
    }

    private fun publish(
        pending: Map<String, Long>,
        grace: Map<String, Long>,
    ) {
        val now = clock()
        val view = HashMap<String, IntroState>()
        for (peerId in pending.keys) view[peerId] = if (lastSentAt.containsKey(peerId)) IntroState.SENT else IntroState.AWAITING_PREKEY
        for ((peerId, until) in grace) if (until > now) view[peerId] = IntroState.CONNECTED
        states.value = view
        // A registration, an eviction or a lapsed grace moves the pair set; a settle (pending → grace) and a
        // send do not, and neither is a reason to re-derive the table.
        val pairs = pairKeys(pending, grace)
        val last = lastPairs
        if (last != null && pairs != last) onPairsChanged()
        if (last != null) lastPairs = pairs
    }

    /** The [pairPeers] rule over one snapshot of the two maps. */
    private fun pairKeys(
        pending: Map<String, Long>,
        grace: Map<String, Long>,
    ): Set<String> {
        val now = clock()
        return pending.keys + grace.filterValues { it > now }.keys
    }

    companion object {
        /** Re-send an unconfirmed intro this often — under the 24 h custody TTL, so a copy is always live. */
        const val RESEND_FLOOR_MS = 20 * 60 * 60_000L

        /** Answer one init at most this often — bounds a peer that keeps re-flooding its init. */
        const val ANSWER_FLOOR_MS = 60 * 60_000L

        /** How long the pair scope outlives our own confirmation, so the peer can still pull our reply from it. */
        const val GRACE_MS = 48 * 60 * 60_000L

        /** Pending intros at once — headroom under a spool's suggested `maxScopes` of 64 (spec §12). */
        const val MAX_PENDING = 8
    }
}
