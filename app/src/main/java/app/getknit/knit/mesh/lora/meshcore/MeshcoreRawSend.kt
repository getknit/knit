package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.lora.AirPurpose
import app.getknit.knit.mesh.lora.Ceiling
import app.getknit.knit.mesh.lora.FragmentOutcome
import app.getknit.knit.mesh.lora.OutboundFrame
import app.getknit.knit.mesh.lora.PacketPrice
import app.getknit.knit.mesh.lora.PlaneCore
import app.getknit.knit.mesh.lora.SendPurpose
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * The debug bridge's raw send on a MeshCore board (`…debug.LORATX --es kind meshcore`, ADR 2026-11.gsqu's Stage 7 amendment;
 * lora L3, security N5): one payload at a time, **through the plane's own path, never a write beside it**. The companion
 * protocol has no correlation ids, so a write outside the session's actor could take a DM flight's `RESP_SENT`; and MeshCore
 * firmware enforces no duty, so a send the core never saw is air the ledger never booked and the law sees only at the next
 * poll. So the payload is queued through the core ([PlaneCore.enqueueDebug]: the pacer, the ledger — priced and booked as a
 * room post on the slot, under [Ceiling.FLOOD] — and the band's law, which holds it as it holds every frame), and written by
 * the session's actor as [SendPurpose.Debug], which asks the consent again at the write. Knit's slot only, never Public.
 *
 * Refused before the queue when the board is not Ready, without consent, while the law holds, on no Knit slot, past the
 * window, or while another waits. The answer names the outcome, the slot, the milliseconds booked and the law; never the
 * payload, which no line carries either. Made by [MeshcoreHooks], beside the Public room's half.
 */
internal class MeshcoreRawSend(
    private val session: MeshcoreBoardOps,
    private val core: PlaneCore,
    private val clock: () -> Long,
    private val wallClock: () -> Long,
    private val log: (String) -> Unit,
    /** This phone's consent for the Ready board, as the hooks last applied it. */
    private val consent: () -> Boolean?,
    /** The band's law as it stands now ([MeshcoreHooks.lawState]). */
    private val law: () -> MeshcoreLawState,
) {
    private val lock = Any()

    /** The one payload waiting for its write — its frame's label and what its caller awaits — or null. Under [lock]. */
    private var pending: Pair<String, CompletableDeferred<String>>? = null

    /** Numbers each payload's label, so a write that outlived its caller's wait completes nobody else's. */
    private val seq = AtomicLong()

    /** Queues [payload] and waits up to [WAIT_MS] for its write; the answer is its outcome, or why it never queued. */
    suspend fun send(payload: ByteArray): MeshcoreDebugSend {
        val slot = session.ready?.channel
        val verdict = law()

        fun answer(
            result: String,
            bookedMs: Long? = null,
        ) = MeshcoreDebugSend(result, slot, bookedMs, verdict.holding, verdict.trailingTxSecs, verdict.limitSecs)
        val price = core.airtime.price(AirPurpose.ROOM_FLOOD)
        refusal(payload, slot, verdict, price)?.let { return answer(it) }
        val booked = core.airtime.timeOnAirMs(payload.size, price)
        val waiting = CompletableDeferred<String>()
        val label = "$LABEL:${seq.incrementAndGet()}"
        synchronized(lock) {
            if (pending != null) return answer(BUSY)
            pending = label to waiting
        }
        try {
            if (!core.enqueueDebug(payload, label, wallClock() + WAIT_MS)) return answer(QUEUE_FULL)
            log("lora debug tx queued ${payload.size}B slot=$slot bookedMs=$booked")
            val outcome = withTimeoutOrNull(WAIT_MS) { waiting.await() } ?: QUEUED
            return answer(outcome, booked.takeIf { outcome == FragmentOutcome.SENT.name })
        } finally {
            synchronized(lock) { if (pending?.first == label) pending = null }
        }
    }

    /** Why [payload] may not be queued now, or null. */
    private fun refusal(
        payload: ByteArray,
        slot: Int?,
        verdict: MeshcoreLawState,
        price: PacketPrice,
    ): String? =
        when {
            session.ready?.detail !is MeshcoreDetail -> NOT_READY
            consent() != true -> NO_CONSENT
            verdict.holding -> LAW_HOLDS
            slot == null || slot == MeshcoreSession.PUBLIC_SLOT -> NO_SLOT
            payload.isEmpty() || payload.size > MeshcoreProto.CHANNEL_DATA_CAP -> TOO_LARGE
            core.airtime.refusal(Ceiling.FLOOD, listOf(payload.size), clock(), price) != null -> NO_AIR
            else -> null
        }

    /**
     * The payload's write, on the pacer ([MeshcoreHooks.sendKindFrame]): never past its freshness nor off the Knit slot, and
     * its outcome handed to the caller still waiting for it. Owed (a busy board, a session gone under it) is back on the queue.
     */
    suspend fun write(
        frame: OutboundFrame,
        send: suspend (ByteArray, SendPurpose) -> FragmentOutcome,
    ) {
        val message = frame.messages.firstOrNull() ?: return
        val slot = session.ready?.channel
        val freshUntil = frame.gate.freshUntil
        val outcome =
            when {
                freshUntil != null && wallClock() > freshUntil -> STALE
                slot == null || slot == MeshcoreSession.PUBLIC_SLOT -> NO_SLOT
                else -> send(message, SendPurpose.Debug(slot)).name
            }
        if (outcome == FragmentOutcome.OWED.name) return
        log("lora debug tx $outcome")
        synchronized(lock) { pending?.takeIf { it.first == frame.label } }?.second?.complete(outcome)
    }

    companion object {
        /**
         * How long [send] waits for the write before it answers [QUEUED]; the frame is never written past it. Under the
         * bridge's `goAsync()` broadcast limit (about a minute, lora-reviewer, Stage 7a), so the trial's reply is never lost.
         */
        const val WAIT_MS = 45_000L
        const val LABEL = "debug:raw"
        const val NOT_READY = "NotReady"
        const val NO_CONSENT = "NoConsent"
        const val LAW_HOLDS = "LawHolds"
        const val NO_SLOT = "NoKnitSlot"
        const val TOO_LARGE = "TooLarge"
        const val NO_AIR = "NoAir"
        const val BUSY = "Busy"
        const val QUEUE_FULL = "QueueFull"
        const val QUEUED = "Queued"
        const val STALE = "Stale"
    }
}

/**
 * What a raw debug send came to ([MeshcoreRawSend.send], Stage 7): [result] (`SENT`, `ENDED`, `Stale`, `Queued` past the wait,
 * or the refusal), the Knit [slot], the milliseconds the ledger booked for it (null unless it went), and the law's verdict
 * when it was asked. Never the payload.
 */
internal data class MeshcoreDebugSend(
    val result: String,
    val slot: Int?,
    val bookedMs: Long?,
    val lawHolding: Boolean,
    val trailingTxSecs: Long?,
    val limitSecs: Double?,
)
