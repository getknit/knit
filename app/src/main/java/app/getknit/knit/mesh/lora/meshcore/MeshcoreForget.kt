package app.getknit.knit.mesh.lora.meshcore

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Forgetting the bound MeshCore board (ADR 2026-11.gsqu's Stage 7 amendment; lora L2 and L4, security B2, B3, N6 and N7): the
 * one function the radio screen and the debug bridge both call (`…debug.LORA --es kind meshcore --es prov forget`), so there
 * is no second path. Pure: every effect is a seam, wired in `di/LoraWiring.meshcoreForget`.
 *
 * **On the application's scope, never the caller's** ([scope]): the unbind may wait long on the session's actor (a setup
 * erasing many contacts runs first), and a screen left meanwhile must not skip the clear that follows it. A caller cancelled
 * while it waits gets nothing back; the Forget runs to its end all the same. One at a time ([lock]).
 *
 * The unbind runs first — the board's own Restore when it is up, else its record marked pending — then its answer decides:
 * - [MeshcoreUnbind.CLEARED], [MeshcoreUnbind.NOTHING]: the whole binding goes in one edit, the plane switched off
 *   ([MeshcoreForgetResult.CLEARED]).
 * - [MeshcoreUnbind.PENDING] (the board away, or its Restore unfinished): **the binding is kept** so the next connection runs
 *   the cleanup, and the published claim goes now in one edit, the plane running or not ([MeshcoreForgetResult.PENDING]). With
 *   [anyway] the binding goes too, the record left pending for a later binding of that board
 *   ([MeshcoreForgetResult.FORGOTTEN_PENDING]).
 * - [MeshcoreUnbind.MISMATCH] (a board at the address that is not the one this phone set up): Knit cannot clean it, so there
 *   is nothing to wait for; the binding goes as for [MeshcoreUnbind.CLEARED] ([MeshcoreForgetResult.MISMATCH]).
 * - [MeshcoreUnbind.FAILED]: nothing of the Forget's own is written ([MeshcoreForgetResult.FAILED]); the screen asks again.
 *   A settings edit that throws after an unbind that did withdraw also answers FAILED, the binding untouched, and owes the
 *   claim's withdrawal, retried with a backoff until it lands ([oweWithdrawal]).
 *
 * Every edit runs through [fenceClaim] (`MeshcoreHooks.forgetClaim`): under the hooks' claim lock, after any claim edit
 * already running, with a fence that refuses every grant made before it — so once a Forget answers [MeshcoreForgetResult.CLEARED]
 * or [MeshcoreForgetResult.PENDING] no `meshcoreKey` claim is published, a stalled or retried claim edit, a late consent
 * collector and a plane never started alike.
 */
internal class MeshcoreForget(
    private val scope: CoroutineScope,
    /** The bound MeshCore board's address (`SettingsStore.meshcoreDeviceAddress`), or null with none. */
    private val boundAddress: suspend () -> String?,
    /** `MeshcoreHooks.unbind`: the session's unbind of [address]. */
    private val unbind: suspend (address: String) -> MeshcoreUnbind,
    /** `MeshcoreHooks.forgetClaim`: runs the edit under the claim lock, behind a fence for [address]. */
    private val fenceClaim: suspend (address: String, edit: suspend () -> Unit) -> Unit,
    /** `SettingsStore.forgetMeshcoreBoard`: one edit, the identity gone and the plane off, while [address] is bound. */
    private val clearBinding: suspend (address: String) -> Unit,
    /** `SettingsStore.withdrawMeshcoreClaim`: one edit, the claimed key and its bind gone, the binding kept. */
    private val withdrawClaim: suspend (address: String) -> Unit,
    private val log: (String) -> Unit = {},
    /**
     * `MeshcoreHooks.forgetClaimUpTo`: an owed withdrawal's retry, fenced at the seq its Forget took and skipped (false) once a
     * grant made after that has landed for the address.
     */
    private val fenceClaimUpTo: suspend (address: String, upTo: Long, edit: suspend () -> Unit) -> Boolean = { address, _, edit ->
        fenceClaim(address, edit)
        true
    },
    /** The latest consent made so far ([MeshcoreConsent.latestSeq]): what a failed Forget's owed withdrawal is fenced at. */
    private val latestSeq: () -> Long = MeshcoreConsent::latestSeq,
) {
    private val lock = Mutex()

    /** The claim withdrawal a failed edit left owed ([oweWithdrawal]), retried until it lands; a new Forget replaces it. */
    private var retry: Job? = null

    /**
     * Forgets the bound MeshCore board; [anyway] (the screen's "Forget anyway" after [MeshcoreForgetResult.PENDING]) clears the
     * binding even while its cleanup is owed. Runs on the application's scope and returns its answer.
     */
    suspend fun forget(anyway: Boolean = false): MeshcoreForgetResult = scope.async { lock.withLock { run(anyway) } }.await()

    private suspend fun run(anyway: Boolean): MeshcoreForgetResult {
        val address = boundAddress() ?: return MeshcoreForgetResult.NOT_BOUND
        retry?.cancel()
        var unbound = false
        val result =
            try {
                val unbind = unbind(address)
                unbound = unbind != MeshcoreUnbind.FAILED
                when (unbind) {
                    MeshcoreUnbind.CLEARED, MeshcoreUnbind.NOTHING -> {
                        fenceClaim(address) { clearBinding(address) }
                        MeshcoreForgetResult.CLEARED
                    }

                    MeshcoreUnbind.MISMATCH -> {
                        fenceClaim(address) { clearBinding(address) }
                        MeshcoreForgetResult.MISMATCH
                    }

                    MeshcoreUnbind.PENDING -> {
                        if (anyway) {
                            fenceClaim(address) { clearBinding(address) }
                            MeshcoreForgetResult.FORGOTTEN_PENDING
                        } else {
                            fenceClaim(address) { withdrawClaim(address) }
                            MeshcoreForgetResult.PENDING
                        }
                    }

                    MeshcoreUnbind.FAILED -> {
                        MeshcoreForgetResult.FAILED
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // A settings edit that threw (a DataStore I/O error): the fence stands, the screen asks again — and the claim
                // the unbind withdrew is owed, retried until it lands (security N2: a withdrawal is never lost).
                log("lora forget kind=meshcore threw ${e.javaClass.simpleName}")
                if (unbound) oweWithdrawal(address, latestSeq())
                MeshcoreForgetResult.FAILED
            }
        log("lora forget kind=meshcore result=$result anyway=$anyway")
        return result
    }

    /**
     * The unbind withdrew the board's consent but the Forget's own edit threw (security-reviewer, Stage 7a): with the plane off,
     * or the board not the session's bound one, nothing else would ever withdraw the published claim. So the claim alone —
     * never the binding, which a FAILED answer leaves to the user — is withdrawn behind the fence, on the claim edits' backoff,
     * until it lands; the edit's own address check makes it a no-op once another board is bound. Fenced at [upTo], the latest
     * consent made when the Forget failed, never a later one: a setup of the same board after it publishes a claim the retry
     * must leave standing, so a retry that finds such a grant landed ends without writing (both reviewers, Stage 7a). Owed in
     * memory only: a process death before it lands leaves the claim to the next Forget or the plane's next start.
     */
    private fun oweWithdrawal(
        address: String,
        upTo: Long,
    ) {
        retry =
            scope.launch {
                var wait = MeshcoreHooks.CLAIM_RETRY_FIRST_MS
                while (true) {
                    delay(wait)
                    val ran =
                        runCatching { fenceClaimUpTo(address, upTo) { withdrawClaim(address) } }
                            .onFailure { if (it is CancellationException) throw it }
                    if (ran.isSuccess) {
                        log(
                            if (ran.getOrDefault(false)) {
                                "lora forget kind=meshcore claim withdrawn on retry"
                            } else {
                                "lora forget kind=meshcore retry dropped: the board was set up again"
                            },
                        )
                        return@launch
                    }
                    wait = (wait * 2).coerceAtMost(MeshcoreHooks.CLAIM_RETRY_MAX_MS)
                }
            }
    }
}

/** What a [MeshcoreForget] did, for the screen and the bridge. */
internal enum class MeshcoreForgetResult {
    /** The board was put back (or had nothing of Knit's), and the binding is gone, the plane off. */
    CLEARED,

    /**
     * The board is away or its Restore did not finish: the binding is kept, so the next connection runs the cleanup, and the
     * claim is withdrawn. The screen offers Keep (the default) and "Forget anyway".
     */
    PENDING,

    /** "Forget anyway": the binding is gone and the plane off, the cleanup still owed to a later binding of that board. */
    FORGOTTEN_PENDING,

    /** The board at the address is not the one this phone set up: Knit cannot clean it. The binding is gone, the plane off. */
    MISMATCH,

    /** The record could not be marked, or an edit failed: nothing of the Forget's own was written. Ask again. */
    FAILED,

    /** No MeshCore board is bound. */
    NOT_BOUND,
}
