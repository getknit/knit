package app.getknit.knit.mesh.bluetooth

import android.util.Log
import app.getknit.knit.mesh.power.ElapsedWait
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Runs [LinkBoostPolicy] for one Bluetooth link (#120): asks the link for a short connection interval while a file
 * moves over it either way and the screen is on, lets go once neither direction has streamed for the linger, and keeps
 * [earned] — whether the link reported an interval that carries the larger file budget ([CodedPhyPolicy.pace]).
 *
 * Made by the transport at the link's first file either way, so a link that never carries one never touches GATT, and
 * closed with the link. A client is attached only where the link can be listened to ([observes]: not on Coded, not with
 * the boost switched off), dark screen or not: a sender in a pocket earns the receiver's ask by hearing it. It asks
 * through the link's one [BleLinkTuner] ([tuner] hands back the live one, or makes one when told it may), which stays
 * attached for the link's life: Android reports the interval only to a client attached when it changes. A tuner that
 * never attached costs a try from [DoorbellPolicy.Lookups]' budget, which says when another may be made.
 *
 * One loop reads the link ([feeding], [receiving]) and [eligible] on every [poke] — the link's file edges, the screen,
 * the PHY — and at the policy's next due time, waited on the elapsed clock (ADR 2026-10.pj9w). A report from the
 * tuner arrives on a binder thread ([onParams]); any ask it owes is run by the loop. Asks are queued on the tuner
 * without waiting for their reports ([BleLinkTuner.enqueue]), so a release never waits behind an earlier ask.
 */
internal class BleLinkBoost(
    private val nodeId: String,
    private val feeding: () -> Boolean,
    private val receiving: () -> Boolean,
    private val observes: () -> Boolean,
    private val eligible: () -> Boolean,
    private val tuner: (make: Boolean) -> BleLinkTuner?,
    private val scope: CoroutineScope,
    private val wait: ElapsedWait,
    private val now: () -> Long,
) {
    private val policy = LinkBoostPolicy.Boost()
    private val pokes = Channel<Unit>(Channel.CONFLATED)
    private val owed = AtomicReference<LinkBoostPolicy.Step?>(null)
    private val lookups = DoorbellPolicy.Lookups()
    private var job: Job? = null

    /** The link reported an interval that earns the larger budget, and nothing slower since (nor a release). */
    val earned: Boolean get() = policy.earned

    // When the link last reported an interval that earns the boost (elapsed time; 0 never).
    @Volatile private var earnedAt = 0L

    /** Whether the link earned the boost within the last [ms]: its peer, or this side, asks for it (ADR 2026-10.nsr7). */
    fun earnedWithin(ms: Long): Boolean = earned || (earnedAt != 0L && now() - earnedAt <= ms)

    /** This side asked for the short interval and has not let go. */
    val boosting: Boolean get() = policy.boosting

    fun start() {
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    /** The link's state may have changed: a file edge, the screen, the PHY. */
    fun poke() {
        pokes.trySend(Unit)
    }

    /** The link is gone: no release is asked, the ACL takes the parameters with it. */
    fun close() {
        job?.cancel()
    }

    /** An update the link's tuner heard, whoever asked for it (binder thread). */
    fun onParams(
        params: LinkParams,
        ok: Boolean,
    ) {
        val before = policy.earned
        val step = policy.onParams(params.intervalUs, ok, now())
        val after = policy.earned
        // Stamped on the way out too: the stack reports an update only when one happens, so a link that earned at the
        // start of a long stream and lost it at the release would otherwise read as earned minutes before (5e9f267d's
        // shape, the bluetooth-reviewer's finding).
        if (before || after) earnedAt = now()
        if (ok && before != after) {
            Log.i(TAG, "bt boost $nodeId ${if (after) "earned" else "unearned"} interval=${params.intervalUs}us")
        }
        if (step.ask != null) owed.set(step)
        poke()
    }

    /** The link's tuner stopped; one that never attached spends a try, and what any of them heard no longer counts. */
    fun onTunerEnded(attached: Boolean) {
        if (!attached) lookups.failed(now())
        forget()
    }

    /** The client that heard the link is gone (ended, or let go by `…debug.LINKTUNE --ez close`): unearned until heard again. */
    fun forget() {
        if (policy.earned) earnedAt = now()
        policy.forget()
        poke()
    }

    private suspend fun loop() {
        while (true) {
            // Attach on the first file either way, to hear the interval whoever asks for it.
            if ((feeding() || receiving()) && observes()) usableTuner()
            owed.getAndSet(null)?.let(::run)
            run(policy.onState(feeding(), receiving(), eligible(), now()))
            val due = policy.dueAt()
            if (due == null) {
                pokes.receive()
            } else {
                wait.receiveWithin(pokes, (due - now()).coerceAtLeast(1))
            }
        }
    }

    private fun usableTuner(): BleLinkTuner? = tuner(lookups.mayTry(now()))

    private fun run(step: LinkBoostPolicy.Step) {
        val ask = step.ask ?: return
        val priority = if (ask == LinkBoostPolicy.Ask.HIGH) TunePriority.HIGH else TunePriority.BALANCED
        val mtu = if (step.mtu) LinkBoostPolicy.MTU else null
        val sent = usableTuner()?.enqueue(LinkTuneRequest(node = nodeId, priority = priority, mtu = mtu)) == true
        val what = if (ask == LinkBoostPolicy.Ask.HIGH) "high" else "release"
        Log.i(TAG, "bt boost $nodeId $what${mtu?.let { " mtu=$it" }.orEmpty()}${if (sent) "" else " (no tuner)"}")
    }

    private companion object {
        const val TAG = BluetoothMeshTransport.TAG
    }
}
