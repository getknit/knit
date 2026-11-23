package app.getknit.knit.mesh

import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.data.receipt.ReadMarkEntity
import app.getknit.knit.data.receipt.ReadMarkRepository
import app.getknit.knit.mesh.crypto.ReadMask
import app.getknit.knit.mesh.protocol.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * What a seal site toward an author asks about reads (ADR 2026-11.z6jw): which of its own ids carry a read bit, which owed
 * reads may ride along, and that a frame carrying them went. [ReadSync] is the one implementation; [NONE] is the rigs'
 * default, which reads nothing and rides nothing.
 */
interface ReadRiding {
    /**
     * Of [ids], those read here — told or not, so a re-ack under a new era or over LoRa re-asserts a read for free —
     * or null when the author's pinned capabilities [authorCaps] read no mask, and the frame then carries none at all
     * (byte-identical to one built before read receipts). Empty, never null, with the setting off: toward a capable
     * author every receipt carries a mask, as wide for no read as for any (`ReadMask.encode`), so its length says nothing.
     */
    suspend fun readSet(
        authorCaps: Long?,
        ids: List<String>,
    ): Set<String>?

    /**
     * Up to [limit] reads owed to [authorId], oldest first and none of [exclude], each claimed for this caller until it
     * [told] them or gave them back ([giveBack]). Empty toward an author whose [authorCaps] read no mask, or with the
     * setting off. Only a seal site whose frame is originated `relay = true` (or pushed into the author's spool scope) asks.
     */
    suspend fun takeRiders(
        authorId: String,
        authorCaps: Long?,
        limit: Int,
        exclude: Collection<String>,
    ): List<String>

    /**
     * A `relay = true` frame toward [authorId] that carried [ids] with their read bits set was originated (or pushed into
     * a spool), sealed under [rootTag]: they are told. Called after the hand-off returned, never on a seal alone. Releases
     * any of them this caller claimed. [riders] is how many of them rode (for the count).
     */
    suspend fun told(
        authorId: String,
        ids: Collection<String>,
        rootTag: Long,
        riders: Int,
    )

    /** Riders [takeRiders] handed out that never went: released, still owed. */
    fun giveBack(ids: Collection<String>)

    companion object {
        /** Reads nothing, rides nothing: every rig that wires no [ReadSync]. */
        val NONE: ReadRiding =
            object : ReadRiding {
                override suspend fun readSet(
                    authorCaps: Long?,
                    ids: List<String>,
                ): Set<String>? = null

                override suspend fun takeRiders(
                    authorId: String,
                    authorCaps: Long?,
                    limit: Int,
                    exclude: Collection<String>,
                ): List<String> = emptyList()

                override suspend fun told(
                    authorId: String,
                    ids: Collection<String>,
                    rootTag: Long,
                    riders: Int,
                ) = Unit

                override fun giveBack(ids: Collection<String>) = Unit
            }

        /** Whether an author whose pinned profile carries [caps] reads a read mask: [Protocol.CAP_READ_RECEIPTS] and a ratchet. */
        fun readsMask(caps: Long?): Boolean {
            val bits = caps ?: 0L
            return bits and Protocol.CAP_READ_RECEIPTS != 0L && bits and Protocol.CAP_RATCHET != 0L
        }
    }
}

/**
 * The reader's read marks as [ReadSync] uses them: `ReadMarkRepository` in the app ([of]), an in-memory fake in tests.
 */
interface ReadMarkStore {
    suspend fun mark(marks: List<ReadMarkEntity>)

    suspend fun readAmong(ids: Collection<String>): Set<String>

    suspend fun owedAmong(ids: Collection<String>): Set<String>

    suspend fun owedFor(
        authorId: String,
        limit: Int,
    ): List<String>

    suspend fun owedAuthors(): Set<String>

    suspend fun markTold(
        ids: Collection<String>,
        at: Long,
        rootTag: Long?,
    )

    suspend fun reOweNotUnder(
        authorId: String,
        liveRoot: Long,
        since: Long,
    ): Int

    suspend fun dropOwed()

    suspend fun dropOwedFor(authorId: String)

    suspend fun prune(
        owedBefore: Long,
        toldBefore: Long,
    )

    companion object {
        /** The app's store: the durable `read_marks` table. */
        fun of(repo: ReadMarkRepository): ReadMarkStore =
            object : ReadMarkStore {
                override suspend fun mark(marks: List<ReadMarkEntity>) = repo.mark(marks)

                override suspend fun readAmong(ids: Collection<String>) = repo.readAmong(ids)

                override suspend fun owedAmong(ids: Collection<String>) = repo.owedAmong(ids)

                override suspend fun owedFor(
                    authorId: String,
                    limit: Int,
                ) = repo.owedFor(authorId, limit)

                override suspend fun owedAuthors() = repo.owedAuthors()

                override suspend fun markTold(
                    ids: Collection<String>,
                    at: Long,
                    rootTag: Long?,
                ) = repo.markTold(ids, at, rootTag)

                override suspend fun reOweNotUnder(
                    authorId: String,
                    liveRoot: Long,
                    since: Long,
                ) = repo.reOweNotUnder(authorId, liveRoot, since)

                override suspend fun dropOwed() = repo.dropOwed()

                override suspend fun dropOwedFor(authorId: String) {
                    repo.dropOwedFor(authorId)
                }

                override suspend fun prune(
                    owedBefore: Long,
                    toldBefore: Long,
                ) = repo.prune(owedBefore, toldBefore)
            }
    }
}

/**
 * How a read leaves this phone for its author (ADR 2026-11.z6jw) — the reader's half. The marks are durable
 * ([ReadMarkStore], `read_marks`): a read has no heal path, since the exists-gate re-acks a re-delivered message and never
 * a read. Everything else here is in memory and rebuilt at [restore].
 *
 * **Told.** A mark is told only once a frame carrying its id with the read bit set was handed off `relay = true`
 * (`originateSigned` and its siblings returned; custody's own verdict does not matter, a refusal is frame-global and a
 * re-seal could not change it, so nothing here ever re-seals), or pushed into the author's spool scope (`pushed = true`).
 * Never on a seal alone, a live-link `relay = false` send, the coordination plane, an owed entry or sealed-bytes cache,
 * the compact LoRa bit, a declined answer, an impossible seal, a v1 fallback or anything cleartext. Originate first and
 * tell after: a process death between them costs one duplicate read, which the author's fill-null-only `readAt` absorbs.
 *
 * **Bits are free, riders are not.** Every seal site sets the bit for each id in its own list that holds any mark
 * ([readSet]); only a `relay = true` site adds owed reads as extra ids ([takeRiders]), up to its own cap.
 *
 * **Standalone, when nothing rides.** One form: a `CTL_RECEIPT` of owed ids with every bit set, sealed as an answer (never
 * an X3DH init) and originated `relay = true` — flooded, custodied, spool-eligible, ADR 033's escalated tick — through
 * [originate]; or, with a connected spool that heard from the author within `SPOOL_COVER_MS`, pushed there ([spoolPush],
 * ADR 2026-09.y5f3) with no custody row. Never a `relay = false` read form, never LoRa (`offerLoraDirect` is not reachable
 * from here, and the coalescer is never fed). Due [linkedDeadlineMs] after the oldest owed read toward an author on a live
 * data path ([linked], `transport.neighbors`), else [absentBaseMs] doubling per author on ADR 053's curve to
 * [ABSENT_CAP_MS], the doubling reset by a ride or a link. At most [budget] custodied ones in any rolling 24 h; past it
 * the marks wait for a ride or the spool. A decline leaves the marks owed for [onRootChanged] or the heal.
 *
 * **Quick, while the author is near.** The deadline above is too slow for someone watching a thread across the room
 * (device trial, 2026-11-16: the read waited out the 45 s for a ride). So [quickDelayMs] after the first read of a burst
 * toward an author [nearby] (a live link or a short-range fast plane), the owed reads not yet sent this way go once more
 * in the same sealed form, `relay = false`, over [quick]: one hop, no flood, no custody row, no spool, never LoRa. Best
 * effort, so it tells nothing: the marks stay owed and the standalone form still runs on its deadline unless a ride tells
 * them first, and a duplicate lands on the author's fill-null-only `readAt`. Toward an author a connected spool covers
 * ([spoolPresent]) the same wake also pushes the owed reads there ([spoolPush]): the push the deadline would make anyway,
 * made now, so an author reachable only through the Internet sees the read in seconds rather than 15 min (the maintainer,
 * 2026-11-16). It tells as the deadline's push does, and a push no spool took leaves the marks to the deadline, which
 * may then fall to custody as before. Never custody from this wake: no budget is spent and no doubling moves.
 *
 * **Eras.** A root change toward an author owes again every read of the last 24 h told under another root
 * ([ReadMarkStore.reOweNotUnder]): a reset or a restore leaves the author unable to open what told it.
 *
 * Pure: the store, the routes, the clock and the wake scope are injected; JVM-tested in `ReadSyncTest`. No `Mutex`: a
 * claim set guarded by `synchronized(this)` (the `DmReAckLedger.claim` shape), so nothing here is ever held across a seal.
 */
class ReadSync(
    private val marks: ReadMarkStore,
    private val enabled: suspend () -> Boolean,
    private val linked: (authorId: String) -> Boolean,
    private val spoolPresent: (authorId: String) -> Boolean,
    // Seals owed ids as one answer-only `CTL_RECEIPT` with every bit set and originates it `relay = true`.
    private val originate: suspend (authorId: String, ids: List<String>) -> Sent,
    // The same, signed `relay = false` and pushed straight into the author's spool scope (`ScopeSync.pushDirect`).
    private val spoolPush: suspend (authorId: String, ids: List<String>) -> Sent,
    private val now: () -> Long,
    // Whether a quick copy can reach the author now: a live link or a short-range fast plane that hears them.
    private val nearby: (authorId: String) -> Boolean = { false },
    // The same receipt sealed and sent `relay = false` over that link or plane, best effort; false when nothing was sealed.
    private val quick: suspend (authorId: String, ids: List<String>) -> Boolean = { _, _ -> false },
    // The root the author's session holds now (`RatchetSessions.rootTagFor`), read again after a tell: a reset that
    // committed while the hand-off was suspended leaves the tell under a dead root (review round 1, B2).
    private val liveRoot: suspend (authorId: String) -> Long? = { null },
    private val wakeScope: () -> CoroutineScope? = { null },
    private val metrics: MeshMetrics = MeshMetrics(),
    private val linkedDeadlineMs: Long = LINKED_DEADLINE_MS,
    private val absentBaseMs: Long = ABSENT_BASE_MS,
    private val budget: Int = DAILY_BUDGET,
    private val quickDelayMs: Long = QUICK_DELAY_MS,
) : ReadRiding {
    /** What a standalone route did with one batch. */
    sealed interface Sent {
        /** Handed off (originated, or taken by a spool), sealed under [rootTag]: the ids are told. */
        class Told(
            val rootTag: Long,
        ) : Sent

        /** Sealed, but no spool took it: nothing told. */
        data object NotPushed : Sent

        /** No session the author holds: nothing sealed, retried when the root moves or on the heal. */
        data object Declined : Sent

        /** The author cannot read a sealed receipt (no pin, no ratchet): nothing sealed. */
        data object CannotSeal : Sent
    }

    // Everything below is guarded by `synchronized(this)` and never held across a suspension.

    // Ids a seal in flight holds, each with its author: never handed to a second caller until told or given back.
    private val claimed = HashMap<String, String>()

    // Authors whose due batch found every owed read held by another caller: sent when that caller lets go (#336).
    private val deferred = HashSet<String>()

    // Bumped by every [schedule]: a batch that read nothing owed drops the deadline only if no read was owed since.
    private var schedules = 0L

    // Author → when its oldest still-owed read became owed here (our clock): the deadline counts from it.
    private val owedSince = HashMap<String, Long>()

    // Author → standalone custodied sends since its last ride or link: the absent deadline's doubling exponent.
    private val steps = HashMap<String, Int>()

    // Authors whose last standalone attempt was declined (no session they hold): retried on a root change or the heal.
    private val declined = HashSet<String>()

    // Authors with a wake armed, and when it fires.
    private val armedAt = HashMap<String, Long>()

    // Our clock at each standalone custodied send in the last 24 h: the per-reader budget.
    private val custodied = ArrayDeque<Long>()

    // Author → the token of its armed quick wake: the wake disarms only its own, however it ends.
    private val quickArmed = HashMap<String, Any>()

    // Ids already sent once by the quick path, oldest first, at most [QUICK_SENT_CAP]: a later burst sends only new reads.
    private val quickSent = LinkedHashSet<String>()

    override suspend fun readSet(
        authorCaps: Long?,
        ids: List<String>,
    ): Set<String>? {
        if (!ReadRiding.readsMask(authorCaps)) return null
        if (ids.isEmpty() || !enabled()) return emptySet()
        return marks.readAmong(ids)
    }

    override suspend fun takeRiders(
        authorId: String,
        authorCaps: Long?,
        limit: Int,
        exclude: Collection<String>,
    ): List<String> {
        if (limit <= 0 || !ReadRiding.readsMask(authorCaps) || !enabled()) return emptyList()
        val skip = exclude.toHashSet()
        val held = synchronized(this) { claimed.size }
        val owed = marks.owedFor(authorId, limit + skip.size + held)
        val taken =
            synchronized(this) {
                owed
                    .filter { it !in skip && it !in claimed }
                    .take(limit)
                    .onEach { claimed[it] = authorId }
            }
        return stillOwed(taken)
    }

    /**
     * Of ids just claimed, those still owed, re-read **after** the claim: another caller tells (markTold) before it lets
     * go, so a read listed before that tell and claimed after its release is caught here instead of sent twice. The rest
     * go back.
     */
    private suspend fun stillOwed(taken: List<String>): List<String> {
        if (taken.isEmpty()) return taken
        var read: Set<String>? = null
        try {
            read = marks.owedAmong(taken)
        } finally {
            if (read == null) giveBack(taken)
        }
        val owed: Set<String> = read
        val gone = taken.filterNot { it in owed }
        if (gone.isNotEmpty()) giveBack(gone)
        return taken.filter { it in owed }
    }

    override suspend fun told(
        authorId: String,
        ids: Collection<String>,
        rootTag: Long,
        riders: Int,
    ) {
        if (ids.isEmpty()) return
        try {
            marks.markTold(ids, now(), rootTag)
        } finally {
            giveBack(ids)
        }
        reOweIfRootMoved(authorId, rootTag)
        // A ride is the cheap path home working: the absent doubling starts over.
        synchronized(this) { steps.remove(authorId) }
        metrics.onReadsSealed(ids.size)
        if (riders > 0) metrics.onReadsRidden(riders)
    }

    /**
     * Releases [ids]. An author whose due batch found them held ([deferred]) gets that batch now, on the wake scope: what
     * the holder did not tell is still owed and already past its deadline. Without a wake scope it waits for the next due
     * pass, its deadline kept.
     */
    override fun giveBack(ids: Collection<String>) {
        val due =
            synchronized(this) {
                val authors = ids.mapNotNullTo(HashSet()) { claimed.remove(it) }
                authors.filter { deferred.remove(it) }
            }
        if (due.isEmpty()) return
        val scope = wakeScope() ?: return
        due.forEach { author -> scope.launch { flush(author) } }
    }

    /**
     * The person read [reads] (already filtered by the caller: the setting on, someone else's conversational message in a
     * DM or a private group, an author who reads a mask): recorded durably, and each author's deadline armed.
     */
    suspend fun onRead(reads: List<ReadMarkEntity>) {
        if (reads.isEmpty() || !enabled()) return
        marks.mark(reads)
        reads.map { it.authorId }.distinct().forEach {
            schedule(it)
            armQuick(it)
        }
    }

    /**
     * [authorId]'s session root moved to [liveRoot] (`RatchetSessions.rootChanges`; null when it has none now). Reads of
     * the last 24 h told under another root are owed again — the author cannot open what told them — and re-batched; a
     * batch an earlier root move found nothing to answer under is tried now. One frame per change, at most; nothing
     * here feeds a heuristic.
     */
    suspend fun onRootChanged(
        authorId: String,
        liveRoot: Long?,
    ) {
        if (!enabled()) return
        if (liveRoot != null) {
            val back = marks.reOweNotUnder(authorId, liveRoot, now() - REOWE_WINDOW_MS)
            if (back > 0) {
                metrics.onReadsReOwed(back)
                schedule(authorId)
            }
        }
        val retry = synchronized(this) { declined.remove(authorId) }
        if (retry) flush(authorId)
    }

    /**
     * After a tell under [rootTag]: the author's root read again, and if it moved meanwhile, everything told under another
     * root owed again. The race this closes: a seal under R1, the hand-off suspends, the author's reset commits and
     * [onRootChanged] runs — finding nothing told yet, since the tell had not landed — and then the tell files the read
     * under R1, which the author can no longer open. Ordered after the tell, so one of the two always sees the other.
     */
    private suspend fun reOweIfRootMoved(
        authorId: String,
        rootTag: Long,
    ) {
        val live = liveRoot(authorId) ?: return
        if (live == rootTag) return
        val back = marks.reOweNotUnder(authorId, live, now() - REOWE_WINDOW_MS)
        if (back > 0) {
            metrics.onReadsReOwed(back)
            schedule(authorId)
        }
    }

    /** A live data path to [authorId] came up: the absent doubling starts over, and an owed batch is due on the linked deadline. */
    fun onLinked(authorId: String) {
        val owed =
            synchronized(this) {
                steps.remove(authorId)
                owedSince[authorId]
            } ?: return
        arm(authorId, owed + linkedDeadlineMs)
    }

    /** The setting turned off: every owed read is forgotten and nothing is scheduled. On, nothing is retroactive. */
    suspend fun onSettingChanged(on: Boolean) {
        if (on) return
        synchronized(this) {
            owedSince.clear()
            steps.clear()
            declined.clear()
            deferred.clear()
            armedAt.clear()
            quickSent.clear()
        }
        marks.dropOwed()
    }

    /**
     * [authorId] was blocked or removed (review round 1, N2): every read still owed to them is forgotten and nothing is
     * scheduled for them. A read is never owed, so withholding it reveals nothing (ADR 010's posture is about ✓✓ and
     * seeds); reads already told stay told.
     */
    suspend fun onAuthorGone(authorId: String) {
        synchronized(this) {
            owedSince.remove(authorId)
            steps.remove(authorId)
            declined.remove(authorId)
            deferred.remove(authorId)
            armedAt.remove(authorId)
            quickArmed.remove(authorId)
        }
        marks.dropOwedFor(authorId)
    }

    /** The session started: every author still owed a read is re-batched, counting from now. */
    suspend fun restore() {
        if (!enabled()) return
        marks.owedAuthors().forEach { schedule(it) }
    }

    /**
     * Sends every batch whose deadline has passed — the wake's work and the heal's backstop — and retries each batch a
     * decline left (the session may be answered by now).
     */
    suspend fun flushDue() {
        val t = now()
        // Snapshot the declines first: a batch declined in this pass waits for the next root move or heal, not a second try now.
        val retry = synchronized(this) { declined.toList().also { declined.clear() } }
        val due =
            synchronized(this) {
                owedSince.filter { (author, since) -> author !in retry && since + deadlineMs(author) <= t }.keys.toList()
            }
        (retry + due).forEach { flush(it) }
    }

    /** Drops owed marks past 24 h, told ones past 48 h (the spool's retention), and orphans. */
    suspend fun prune() {
        val t = now()
        marks.prune(owedBefore = t - OWED_TTL_MS, toldBefore = t - TOLD_TTL_MS)
    }

    private fun schedule(authorId: String) {
        val since =
            synchronized(this) {
                schedules++
                owedSince.getOrPut(authorId) { now() }
            }
        arm(authorId, since + deadlineMs(authorId))
    }

    private fun deadlineMs(authorId: String): Long =
        if (linked(authorId)) {
            linkedDeadlineMs
        } else {
            val step = synchronized(this) { steps[authorId] ?: 0 }
            (absentBaseMs shl step.coerceIn(0, MAX_SHIFT)).coerceAtMost(ABSENT_CAP_MS)
        }

    /** A best-effort wake at [at] for [authorId]; [flushDue] on the heal is the backstop. One armed wake per author. */
    private fun arm(
        authorId: String,
        at: Long,
    ) {
        val scope = wakeScope() ?: return
        val fresh =
            synchronized(this) {
                val armed = armedAt[authorId]
                if (armed != null && armed <= at) return@synchronized false
                armedAt[authorId] = at
                true
            }
        if (!fresh) return
        scope.launch {
            delay((at - now()).coerceAtLeast(0))
            synchronized(this@ReadSync) { if (armedAt[authorId] == at) armedAt.remove(authorId) }
            val due = synchronized(this@ReadSync) { owedSince[authorId] }?.let { it + deadlineMs(authorId) <= now() } == true
            if (due) {
                flush(authorId)
            } else {
                synchronized(this@ReadSync) { owedSince[authorId] }?.let { arm(authorId, it + deadlineMs(authorId)) }
            }
        }
    }

    /**
     * The quick copy's wake toward [authorId] (the class doc's "Quick"): one per author, armed only while they are near.
     * Disarmed before the send, so a read landing while it seals arms the next burst, and again however the wake ends: a
     * session stopped inside the delay (or before the wake ever ran) must not leave the author armed for the process's life.
     */
    private fun armQuick(authorId: String) {
        if (!nearby(authorId) && !spoolPresent(authorId)) return
        val scope = wakeScope() ?: return
        val token = Any()
        if (!synchronized(this) { quickArmed.putIfAbsent(authorId, token) == null }) return
        val disarm = { synchronized(this) { quickArmed.remove(authorId, token) } }
        scope
            .launch {
                delay(quickDelayMs)
                disarm()
                quickFlush(authorId)
            }.invokeOnCompletion { disarm() }
    }

    /** The quick wake's work: the best-effort copy toward a near author, then the spool push toward a covered one. */
    private suspend fun quickFlush(authorId: String) {
        if (!enabled()) return
        if (nearby(authorId)) quickCopy(authorId)
        if (spoolPresent(authorId)) quickSpool(authorId)
    }

    /**
     * Owed reads toward [authorId] not yet sent quick, up to [ReadMask.MAX_IDS], sent once over [quick]. Nothing is claimed
     * and nothing is told: a ride or the standalone form carrying the same ids meanwhile costs one duplicate read.
     */
    private suspend fun quickCopy(authorId: String) {
        val seen = synchronized(this) { quickSent.size }
        val owed = marks.owedFor(authorId, ReadMask.MAX_IDS + seen)
        val ids = synchronized(this) { owed.filter { it !in quickSent }.take(ReadMask.MAX_IDS) }
        if (ids.isEmpty() || !quick(authorId, ids)) return
        synchronized(this) {
            quickSent += ids
            while (quickSent.size > QUICK_SENT_CAP) quickSent.remove(quickSent.first())
        }
        metrics.onReadsQuick()
    }

    /**
     * The owed reads toward [authorId], claimed as [flush] claims them, pushed into the author's spool scope now: told
     * when a spool took them ([settle], no custody, so no doubling). Anything else leaves them owed for the deadline.
     */
    private suspend fun quickSpool(authorId: String) {
        val ids = takeOwed(authorId)
        if (ids.isEmpty()) return
        try {
            val pushed = spoolPush(authorId, ids)
            if (pushed is Sent.Told) {
                metrics.onReadsSpooled()
                settle(authorId, ids, pushed, viaCustody = false)
            }
        } finally {
            giveBack(ids)
        }
    }

    /**
     * One standalone batch toward [authorId]: up to [ReadMask.MAX_IDS] owed ids nobody else holds, to the spool when it
     * covers the author, else custody inside the budget. A batch that told leaves the rest (if any) due at once. A batch
     * that took nothing is not nothing owed ([tookNothing], #336).
     */
    private suspend fun flush(authorId: String) {
        if (!enabled()) return
        val ids = takeOwed(authorId)
        if (ids.isEmpty()) {
            tookNothing(authorId)
            return
        }
        synchronized(this) { deferred.remove(authorId) }
        try {
            val (outcome, viaCustody) = route(authorId, ids)
            settle(authorId, ids, outcome, viaCustody)
        } finally {
            giveBack(ids)
        }
    }

    /** One batch's route: the spool when it covers the author, else custody inside the budget. Null: the budget held it. */
    private suspend fun route(
        authorId: String,
        ids: List<String>,
    ): Pair<Sent?, Boolean> {
        if (spoolPresent(authorId)) {
            val pushed = spoolPush(authorId, ids)
            if (pushed is Sent.Told) {
                metrics.onReadsSpooled()
                return pushed to false
            }
            if (pushed != Sent.NotPushed) return pushed to false
        }
        if (!takeBudget()) {
            metrics.onReadsBudgetHeld()
            return null to false
        }
        val sent = originate(authorId, ids)
        if (sent is Sent.Told) metrics.onReadsCustodied() else refundBudget()
        return sent to true
    }

    /** What a batch's [outcome] leaves: told marks and the next deadline, a decline to retry, or the marks still owed. */
    private suspend fun settle(
        authorId: String,
        ids: List<String>,
        outcome: Sent?,
        viaCustody: Boolean,
    ) {
        when (outcome) {
            is Sent.Told -> {
                marks.markTold(ids, now(), outcome.rootTag)
                metrics.onReadsSealed(ids.size)
                reOweIfRootMoved(authorId, outcome.rootTag)
                synchronized(this) {
                    // Only a custody row doubles the wait: a spool push costs the mesh nothing.
                    if (viaCustody && !linked(authorId)) steps[authorId] = (steps[authorId] ?: 0) + 1
                    owedSince.remove(authorId)
                }
                // More than one batch owed: the next counts from now, on the (doubled) deadline.
                if (marks.owedFor(authorId, 1).isNotEmpty()) schedule(authorId)
            }

            // Not told: a batch deferred to this one's release would only try again at once, so it waits as this one does.
            Sent.Declined -> {
                synchronized(this) {
                    declined += authorId
                    deferred.remove(authorId)
                }
            }

            // The budget held it, or no seal is possible: owed until a ride, a link or the next due pass.
            Sent.CannotSeal, Sent.NotPushed, null -> {
                synchronized(this) {
                    owedSince[authorId] = now()
                    deferred.remove(authorId)
                }
            }
        }
    }

    private suspend fun takeOwed(authorId: String): List<String> {
        val held = synchronized(this) { claimed.size }
        val owed = marks.owedFor(authorId, ReadMask.MAX_IDS + held)
        val taken = synchronized(this) { owed.filter { it !in claimed }.take(ReadMask.MAX_IDS).onEach { claimed[it] = authorId } }
        return stillOwed(taken)
    }

    /**
     * A due batch toward [authorId] that took nothing, decided from what is owed **after** the claim (what it listed may
     * have been told since, beside a read another caller still holds). Nothing owed: the deadline goes. A read another
     * caller holds (a batch still settling, a seal carrying it as a rider): the batch is [deferred] to that caller's
     * release, under the lock the release takes, so the release always sees it. A read owed and free by now (its holder
     * let go meanwhile): the deadline stays, past, and a wake sends it.
     */
    private suspend fun tookNothing(authorId: String) {
        val (held, seen) = synchronized(this) { claimed.size to schedules }
        val owed = marks.owedFor(authorId, ReadMask.MAX_IDS + held)
        val free =
            synchronized(this) {
                when {
                    owed.isEmpty() -> {
                        deferred.remove(authorId)
                        // A read owed again since the store was read (a root move) keeps the deadline it scheduled.
                        if (schedules == seen) owedSince.remove(authorId)
                        false
                    }

                    owed.any { it in claimed } -> {
                        deferred += authorId
                        false
                    }

                    else -> {
                        true
                    }
                }
            }
        if (free) schedule(authorId)
    }

    private fun takeBudget(): Boolean =
        synchronized(this) {
            val t = now()
            while (custodied.isNotEmpty() && custodied.first() <= t - BUDGET_WINDOW_MS) custodied.removeFirst()
            if (custodied.size >= budget) return@synchronized false
            custodied.addLast(t)
            true
        }

    private fun refundBudget() {
        synchronized(this) { custodied.removeLastOrNull() }
    }

    companion object {
        /** An author on a live data path: the read goes 45 s after the oldest owed one, AckSync's batch window. */
        const val LINKED_DEADLINE_MS = AckSync.TICK_BATCH_DEBOUNCE_MS

        /** An absent author: 15 min, doubling per standalone send (ADR 053's curve). */
        const val ABSENT_BASE_MS = 15 * 60_000L

        /** The doubling's ceiling, ADR 053's. */
        const val ABSENT_CAP_MS = AckSync.RETRY_CAP_MS

        /** Standalone custodied read frames a reader may originate in any rolling 24 h: a quarter of a sender's custody bucket. */
        const val DAILY_BUDGET = ForwardRepository.DEFAULT_MAX_PER_SENDER / 4

        /** The rolling window [DAILY_BUDGET] counts over. */
        const val BUDGET_WINDOW_MS = 24 * 60 * 60_000L

        /** How far back a root change owes reads again: custody's TTL, past which nothing told would still be in flight. */
        const val REOWE_WINDOW_MS = 24 * 60 * 60_000L

        /** An owed mark's life: a read nobody could tell in a day is dropped. */
        const val OWED_TTL_MS = 24 * 60 * 60_000L

        /** A told mark's life: the spool's retention, so a re-ack within it can still carry the bit. */
        const val TOLD_TTL_MS = 48 * 60 * 60_000L

        /** A quick copy goes this long after the first read of a burst toward a near author, so a scroll's reads share it. */
        const val QUICK_DELAY_MS = 1_000L

        /** Ids remembered as sent quick: a few screens' worth, past which an old id may go quick a second time. */
        private const val QUICK_SENT_CAP = 512

        private const val MAX_SHIFT = 10
    }
}
