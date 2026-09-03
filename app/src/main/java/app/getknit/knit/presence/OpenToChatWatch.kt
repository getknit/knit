package app.getknit.knit.presence

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Drives [OpenToChatPolicy] off the live inputs and posts the cue. Owned and started by `MeshManager` on
 * its per-session scope (a sibling of `watchReachable`), so a mesh stop tears it and its hold timer down.
 *
 * The inputs are joined here rather than in the transport: [neighborIds] (the short-range reachable set)
 * carries no profile data and a peer can sit in it before its profile row exists, so the trigger has to be
 * the join of all five flows, re-evaluated whenever any of them moves — a row arriving after the sighting
 * still counts, and a peer we reply to drops out of [acquainted] the moment the message lands. Each input
 * is `distinctUntilChanged` (the mesh re-emits its set on an advert's capability bits, a DataStore write
 * re-emits every flow in the store, and the `messages` table re-runs its queries on every insert) and the
 * fold is idempotent anyway.
 *
 * Persisted state ([Persisted]) is read **once** at start and written through after every post — never
 * collected, since the write would re-emit it and re-fold. Two coroutines touch the state (the collector and
 * the hold timer), so both go through [mutex]. Pure but for the coroutines: [now] is injected and the JVM
 * tests drive it on a virtual clock.
 */
class OpenToChatWatch(
    private val ownFlag: Flow<Boolean>,
    private val neighborIds: Flow<Set<String>>,
    private val openIds: Flow<Set<String>>,
    private val blocked: Flow<Set<String>>,
    /** Peers we have already exchanged messages with — no introduction needed (see [OpenToChatPolicy]). */
    private val acquainted: Flow<Set<String>>,
    private val loadState: suspend () -> Persisted,
    private val persist: suspend (Persisted) -> Unit,
    /** Resolves names and posts one cue naming [peerIds] (arrival order); runs outside the lock. */
    private val post: suspend (peerIds: List<String>) -> Unit,
    /** Cancels a posted cue — the own flag went off. */
    private val clear: () -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val log: (String) -> Unit = {},
) {
    /** The durable half of [OpenToChatPolicy.State]: the per-peer cue stamps and the last post time. */
    class Persisted(
        val named: Map<String, List<Long>>,
        val lastPostAt: Long,
    )

    private val mutex = Mutex()
    private var state = OpenToChatPolicy.State()
    private var timer: Job? = null
    private var ownWasOn = false

    fun start(session: CoroutineScope) {
        session.launch {
            val loaded = loadState()
            mutex.withLock {
                state = OpenToChatPolicy.State(named = OpenToChatPolicy.prune(loaded.named, now()), lastPostAt = loaded.lastPostAt)
            }
            combine(
                ownFlag.distinctUntilChanged(),
                neighborIds.distinctUntilChanged(),
                openIds.distinctUntilChanged(),
                blocked.distinctUntilChanged(),
                acquainted.distinctUntilChanged(),
            ) { own, near, open, blk, met ->
                own to OpenToChatPolicy.qualifying(own, near, open, blk, met)
            }.distinctUntilChanged().collect { (own, qualifying) -> onInputs(session, own, qualifying) }
        }
    }

    private suspend fun onInputs(
        session: CoroutineScope,
        own: Boolean,
        qualifying: Set<String>,
    ) {
        mutex.withLock {
            if (ownWasOn && !own) clear()
            ownWasOn = own
            val before = state
            state = OpenToChatPolicy.fold(before, qualifying, now())
            if (state.pending.keys != before.pending.keys) {
                log("open-to-chat qualifying=${qualifying.size} pending=${state.pending.keys}")
            }
            if (state.pending.isNotEmpty()) arm(session)
        }
    }

    /** Call under [mutex]: one timer at a time, aimed at the batch's due time. */
    private fun arm(session: CoroutineScope) {
        if (timer?.isActive == true) return
        val due = OpenToChatPolicy.dueAt(state) ?: return
        timer =
            session.launch {
                delay((due - now()).coerceAtLeast(0L))
                flushDue(session)
            }
    }

    private suspend fun flushDue(session: CoroutineScope) {
        val show =
            mutex.withLock {
                timer = null
                val due = OpenToChatPolicy.dueAt(state) ?: return@withLock null
                if (due > now()) {
                    // The oldest arrival left mid-hold, or the hourly gap moved: re-aim, don't post.
                    arm(session)
                    return@withLock null
                }
                val flush = OpenToChatPolicy.flush(state, now())
                state = flush.state
                persist(Persisted(state.named, state.lastPostAt))
                flush.show
            } ?: return
        log("open-to-chat cue names=${show.size}")
        post(show)
    }
}
