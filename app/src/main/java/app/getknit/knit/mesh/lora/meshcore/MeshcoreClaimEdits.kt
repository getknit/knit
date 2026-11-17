package app.getknit.knit.mesh.lora.meshcore

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The sealed profile's MeshCore claim as [MeshcoreHooks] edits it (ADR 2026-11.gsqu's Stage 5b-2 and 5d amendments; the
 * Forget's fence since Stage 7): one edit at a time under [mutex], the latest winning, an edit that throws owed and retried
 * with a backoff until it lands or a newer one replaces it, so a withdrawal is never lost (security N2) and a stale retry
 * never lands after a newer edit (the 5b-2 tail).
 *
 * **A Forget fences it** ([forget], Stage 7, security B2/B3): under the same lock — after any edit already running, a stalled
 * DataStore write or node-id read, and before any that starts later — the Forget's own settings edit runs, and every grant
 * from a consent made at or before the latest one so far ([MeshcoreConsent.seq]) is refused from then on: a retry, a
 * collector that applies an old consent late. A grant from a later consent (a new setup) is the user's again.
 */
internal class MeshcoreClaimEdits(
    private val scope: CoroutineScope,
    private val counters: MeshcorePlaneCounters,
    /** `MeshcoreClaimStore.setBoardKey` under the binding rule: null key withdraws the key and its bind. */
    private val onBoardKey: suspend (key: ByteArray?, sig: ByteArray?, dialled: String?) -> Unit,
) {
    /** The claim edit owed, the latest winning, retried until it lands ([claim]). */
    private class Owed(
        val address: String,
        val key: ByteArray?,
        val sig: ByteArray?,
        /** The consent it came from ([MeshcoreConsent.seq]): a grant at or under a Forget's fence never lands. */
        val seq: Long,
    )

    private val mutex = Mutex()
    private val lock = Any()

    @Volatile
    private var owed: Owed? = null

    /** Each address's Forget fence: the highest consent seq a Forget of it fenced. Under [mutex]. */
    private val fences = HashMap<String, Long>()

    /** The newest grant that landed, per address, by its consent seq: what a late owed withdrawal must not undo. Under [mutex]. */
    private val granted = HashMap<String, Long>()

    private var retry: Job? = null

    /**
     * Publishes ([key], with its bind [sig]) or withdraws (null) the claim for [address], from the consent made [seq]-th; an
     * edit that throws is owed and retried with a backoff until it lands or a newer one replaces it.
     */
    suspend fun claim(
        address: String,
        key: ByteArray?,
        sig: ByteArray?,
        seq: Long,
    ) {
        val edit = Owed(address, key, sig?.takeIf { key != null }, seq)
        owed = edit
        if (tryClaim(edit)) return
        synchronized(lock) {
            retry?.cancel()
            retry =
                scope.launch {
                    var wait = MeshcoreHooks.CLAIM_RETRY_FIRST_MS
                    // The one check of whether [edit] is still the edit owed is inside [tryClaim], under the mutex.
                    while (true) {
                        delay(wait)
                        if (tryClaim(edit)) return@launch
                        wait = (wait * 2).coerceAtMost(MeshcoreHooks.CLAIM_RETRY_MAX_MS)
                    }
                }
        }
    }

    /**
     * One attempt at [edit], under [mutex], re-checked inside it: true when it landed, when a newer edit replaced it, or when a
     * Forget fenced it off (nothing left to do for this one).
     */
    private suspend fun tryClaim(edit: Owed): Boolean {
        val landed =
            mutex.withLock {
                if (owed !== edit) return true
                // A grant made before a Forget of its address (Stage 7): never written, however late its collector or retry runs.
                if (edit.key != null && fences[edit.address]?.let { edit.seq <= it } == true) {
                    owed = null
                    counters.claimsFenced.incrementAndGet()
                    return true
                }
                runCatching { onBoardKey(edit.key, edit.sig, edit.address) }
                    .onFailure { if (it is CancellationException) throw it }
                    .isSuccess
                    .also { ok ->
                        if (ok && edit.key != null) granted[edit.address] = maxOf(granted[edit.address] ?: 0, edit.seq)
                        if (ok) counters.claimEdits.incrementAndGet()
                    }
            }
        if (landed) {
            if (owed === edit) owed = null
        } else {
            counters.consentApplyFailures.incrementAndGet()
        }
        return landed
    }

    /**
     * A Forget's claim edit (`MeshcoreForget`): under [mutex], a fence for [address] at [upTo] — for the Forget itself (null),
     * [MeshcoreConsent.latestSeq] read inside the lock — any grant owed for it at or under that dropped, then [edit]: the
     * Forget's own `DataStore.edit`, which writes the settings whether or not the plane runs. A throw from [edit] is the
     * caller's; the fence stands either way.
     *
     * An owed withdrawal retried later passes the [upTo] its Forget took when it failed (both reviewers, Stage 7a): when a grant
     * from a consent made after it has landed for [address] since — the user set the same board up again — the edit is skipped
     * and false answered, so the retry never withdraws the new setup's claim nor fences its grant. True when [edit] ran.
     */
    suspend fun forget(
        address: String,
        upTo: Long? = null,
        edit: suspend () -> Unit,
    ): Boolean =
        mutex.withLock {
            // A Forget's own edit (no [upTo]) is fenced at the latest consent read here, inside the lock, and never skipped:
            // a grant that landed while it waited is one it withdraws (lora-reviewer, Stage 7a). Only an owed retry skips.
            if (upTo != null && (granted[address] ?: 0) > upTo) return@withLock false
            val fenceAt = upTo ?: MeshcoreConsent.latestSeq()
            fences[address] = maxOf(fences[address] ?: 0, fenceAt)
            if (owed?.let { it.address == address && it.key != null && it.seq <= fenceAt } == true) owed = null
            edit()
            true
        }
}
