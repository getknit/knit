package app.getknit.knit.moderation

import android.os.Process
import app.getknit.knit.BuildConfig
import app.getknit.knit.crash.ProcessExitEvidence
import app.getknit.knit.data.settings.ModelLoadJournal
import app.getknit.knit.data.settings.ModelLoadState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * Wraps every load of a bundled TFLite model so a **native** crash inside it cannot become an
 * unrecoverable launch loop. [ModelLoadPolicy] holds the reasoning; this holds the three side effects it
 * needs — the durable journal write, reading how the last process died, and a clock.
 *
 * A latched model is simply not loaded, and the moderator degrades exactly as it already does when its
 * assets are missing: `TextVerdict.ALLOWED` / `ImageVerdict.ALLOWED`. What that costs is stated plainly in
 * `docs/CONTENT_MODERATION.md` §8 — the Nearby room keeps its word-list pass, DMs and groups lose text
 * screening entirely, because the ML classifier is the *only* pass they run.
 *
 * **Fails open, always.** [guard] is reached from `MeshManager.onDeliver`'s no-throw inbound path, so a
 * DataStore that cannot be read or written must not take the load down with it — an unreadable journal
 * means "not latched, don't mark", never "assume the worst". Cancellation is the one thing that does
 * propagate: swallowing it would leave work running for a screen the user has already left.
 *
 * **[stamp]** is the app version code and the OS build fingerprint. Both halves are resets, and the OS
 * half is the one that earns its place: getknit/knit#9 is a LineageOS device, and a ROM update is exactly
 * the event that might fix a driver fault — without it a user who updates their ROM stays latched off
 * until Knit ships a new version code. It is passed in rather than read here so this class holds no
 * `android.os.Build` reference and its test needs no Robolectric. [exits] is a function for the same
 * reason — `ProcessExitReasons::lastExit` in production, a value in a test.
 */
class ModelLoadGuard(
    private val journal: ModelLoadJournal,
    private val exits: () -> ProcessExitEvidence?,
    private val stamp: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /**
     * Runs [load] under the poison-pill, returning its result, or `null` when the model is latched off.
     *
     * [load] must cover **everything that can fault natively** — building the interpreter *and* one
     * inference. First-inference tensor allocation and kernel selection is a plausible crash site of its
     * own, so closing the journal entry after the load alone would call success before the risky part had
     * run. It must also swallow its own Java-level failures: this counts process deaths, not exceptions.
     *
     * Every load runs here, not only the first in a process: [ModelLease] releases an engine after ten idle
     * minutes and re-acquires it through the same [load], so a reload that faults latches exactly as a first
     * load does. A `completed` record simply gets a fresh marker.
     */
    suspend fun <T : Any> guard(
        model: String,
        load: () -> T?,
    ): T? {
        val stored = tolerating { journal.modelLoadState(model) } ?: return load()
        val decision = ModelLoadPolicy.decide(stored, stamp, now(), exits())
        if (!decision.load) {
            if (decision.next != stored) tolerating { journal.setModelLoadState(model, decision.next) }
            return null
        }
        return try {
            // Awaited on purpose: this is the durability barrier. DataStore's edit {} fsyncs and renames
            // before it resumes, so a native crash inside load() still finds the marker on disk next launch.
            // Inside the try, also on purpose: a caller cancelled after the write committed must still reach
            // the finally, or the marker outlives this process and the next load — in this process, now
            // that models reload — reads it as an unexplained death and counts it.
            tolerating { journal.setModelLoadState(model, decision.next) }
            injectDebugFaultIfArmed()
            load()
        } finally {
            // In a finally, and NonCancellable, deliberately. The marker must mean "the process died in
            // there" and nothing else — a missing asset (every build without the models), a throw, or a
            // cancelled viewModelScope (back out of a chat while the 17 MB image model is loading) all
            // have to clear it, or ordinary use would latch the model off on a healthy phone.
            withContext(NonCancellable) {
                tolerating { journal.setModelLoadState(model, ModelLoadPolicy.completed(stamp)) }
            }
        }
    }

    /** Whether [model] is latched off right now, as a stream — Diagnostics can reset it while it watches. */
    fun observeLatched(model: String): Flow<Boolean> = journal.observeModelLoad(model).map(::isLatched)

    /**
     * The Diagnostics reset: give [model] another chance. Takes effect on the next process start — a latched
     * model was never loaded, so its moderator has nothing to release and never comes back through [guard]
     * until the process restarts (the attempted-but-empty state in [ModelLease] is permanent by design).
     */
    suspend fun clear(model: String) {
        tolerating { journal.setModelLoadState(model, ModelLoadPolicy.completed(stamp)) }
    }

    /** A record written under a different app/OS build is already spent — [ModelLoadPolicy.decide] discards it. */
    private fun isLatched(state: ModelLoadState): Boolean = state.stamp == stamp && ModelLoadPolicy.latched(state)

    /**
     * Debug-only fault injection for the acceptance test (`-PmodelFaultOnLoad=segv|kill`).
     * [BuildConfig.DEBUG] is a compile-time constant, so R8 folds this call out of release entirely.
     *
     * It sits **after** the journal write and **after** the latch check, which is what makes the loop
     * escapable: once the model latches off, [guard] has already returned and the fault never fires.
     *
     * `segv` and `kill` test opposite things. Only a real fault signal produces the native-crash evidence
     * that latches. `Process.killProcess` sends SIGKILL, which the platform records as `REASON_SIGNALED`
     * status 9 — the same as a force-stop — so `kill` is the **negative control**: it must never latch,
     * however often it fires. (Confirmed on a Pixel 9 / Android 17; see ADR 037.)
     */
    private fun injectDebugFaultIfArmed() {
        if (!BuildConfig.DEBUG) return
        when (BuildConfig.MODEL_FAULT_ON_LOAD) {
            FAULT_SEGV -> Process.sendSignal(Process.myPid(), SIGSEGV)
            FAULT_KILL -> Process.killProcess(Process.myPid())
            else -> Unit
        }
    }

    /**
     * Runs a journal call, reporting failure as `null` rather than throwing — see the fail-open note on
     * the class. `runCatching` is not usable here: it swallows [CancellationException] too.
     */
    private suspend fun <T> tolerating(block: suspend () -> T): T? =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }

    companion object {
        /** The toxicity classifier — warmed on every launch, so the one that can loop a *launch*. */
        const val TOXICITY = "toxicity"

        /**
         * The NSFW image classifier. Loaded on the first image screened, which includes inbound blobs
         * with no user action at all, so a fault here is "Knit dies whenever someone sends you a photo" —
         * a loop in all but name. It deliberately has **no** warm-up: moving a 17 MB load onto the launch
         * path would undo exactly what `5da5601` bought.
         */
        const val NSFW = "nsfw"

        /** Every model under the poison-pill. */
        val ALL = listOf(TOXICITY, NSFW)

        const val FAULT_SEGV = "segv"
        const val FAULT_KILL = "kill"

        private const val SIGSEGV = 11
    }
}

/** The [ModelLoadGuard.stamp] for a running build: `<versionCode>|<fingerprint>`. Pure, so it is testable. */
fun modelGuardStamp(
    versionCode: Int,
    fingerprint: String,
): String = "$versionCode|$fingerprint"
