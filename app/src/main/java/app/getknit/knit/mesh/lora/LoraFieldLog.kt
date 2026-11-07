package app.getknit.knit.mesh.lora

import app.getknit.knit.fieldlog.FieldEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Why the board's link ended, or an attempt at it failed, as the field log spells it (ADR 2026-10.a239): the words
 * [MeshtasticSession] puts in a [LinkState.Disconnected] reason, and the states that end a link without one, folded
 * onto constants, since the log takes no string. [of] is the one place the words are read, and it reads them through
 * the session's own constants, so a renamed word fails `LoraFieldLogTest` rather than turning a log to [OTHER].
 */
internal enum class LoraLinkEnd {
    /** The board's GATT link went away under a session (`gatt disconnect <status>`). */
    GATT_DROP,

    /** The dial failed at a phase (`dial <phase> <status>`). */
    DIAL_FAILED,

    /** A direct dial's connect window ran out. */
    DIAL_TIMEOUT,

    /** The config handshake failed or timed out. */
    HANDSHAKE,

    /** A read or write on a ready link failed (the drain, a send, the heartbeat), or a dial or a session threw. */
    IO,

    /** The board rebooted under a ready session. */
    REBOOTED,

    /** A setup or restore was committed, and the board reboots to apply it. */
    PROVISIONED,

    /** The board is not paired (a bond refusal on an unbonded board ends here); retries stop. */
    NEEDS_PAIRING,

    /** The stack keeps refusing a bond it holds; retries stop. */
    STALE_BOND,

    /** The Bluetooth adapter is off or absent. */
    ADAPTER_OFF,

    /**
     * The plane stopped the link: the board was unpaired, or the plane quiesced inside a running session. A mesh stop
     * rarely writes it — the session that follows the status is cancelled as the transport publishes Idle — so a
     * `mesh_off` closes an open LoRa link in the log.
     */
    STOPPED,

    /** Words this build does not know, or a state that ends a link with none (a conflated snapshot). */
    OTHER,
    ;

    companion object {
        private val STATUS = Regex("""status=(-?\d+)""")

        /** [state]'s end and the status it carried, where it carried one. */
        fun of(state: LinkState): Pair<LoraLinkEnd, Int?> =
            when (state) {
                is LinkState.Disconnected -> of(state.reason)
                is LinkState.NeedsPairing -> NEEDS_PAIRING to null
                is LinkState.StaleBond -> STALE_BOND to null
                LinkState.Unavailable -> ADAPTER_OFF to null
                LinkState.Idle -> STOPPED to null
                LinkState.Connecting, LinkState.Bonding, is LinkState.Handshaking, is LinkState.Ready -> OTHER to null
            }

        /**
         * A reason's end and status: the status is a `GattResult`'s `status=` where the reason carries one, else a
         * trailing integer (`gatt disconnect 8`, `dial connect 133`). The words that are never published — the bond
         * refusal and a cancel, whose sessions end terminal — and the background wait are [OTHER].
         */
        fun of(reason: String): Pair<LoraLinkEnd, Int?> {
            val end =
                with(MeshtasticSession) {
                    when {
                        reason == REASON_DIAL_TIMEOUT -> DIAL_TIMEOUT
                        reason.startsWith("$REASON_GATT_DISCONNECT ") -> GATT_DROP
                        reason.startsWith("$REASON_DIAL ") -> DIAL_FAILED
                        reason == REASON_HANDSHAKE_TIMEOUT || reason.startsWith("$REASON_HANDSHAKE_READ ") -> HANDSHAKE
                        IO_WORDS.any { reason.startsWith("$it ") } -> IO
                        reason == REASON_REBOOTED -> REBOOTED
                        reason == REASON_PROVISIONED -> PROVISIONED
                        reason == REASON_ADAPTER_OFF -> ADAPTER_OFF
                        else -> OTHER
                    }
                }
            return end to statusOf(reason)
        }

        private val IO_WORDS =
            listOf(
                MeshtasticSession.REASON_DRAIN_READ,
                MeshtasticSession.REASON_SEND_WRITE,
                MeshtasticSession.REASON_HEARTBEAT_WRITE,
                MeshtasticSession.REASON_THREW,
            )

        private fun statusOf(reason: String): Int? =
            STATUS
                .find(reason)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull()
                ?: reason.substringAfterLast(' ', missingDelimiterValue = "").toIntOrNull()
    }
}

/**
 * The field log's LoRa edges (ADR 2026-10.a239's 2026-10-18 amendment): [status] — the plane's published
 * [LoraStatus], a `StateFlow` — followed for one mesh session and turned into `lora_up`, `lora_down` and `lora_fail`
 * lines (`lora_role` went with the gateway election, ADR 2026-10.rp3s). Reads the snapshots only; never asks the link or the session anything. A fresh [LoraFieldLog]
 * per collection, so each session starts over.
 *
 * [clock] must be the session's own monotonic base (`SystemClock.elapsedRealtime` in the app, as `MeshModule` hands
 * [MeshtasticSession]), because a [LinkState.Disconnected]'s `retryAtMs` is on it and `lora_fail`'s wait is read
 * against it; the dial and life times use it too.
 */
internal fun loraFieldEdges(
    status: Flow<LoraStatus>,
    clock: () -> Long,
): Flow<FieldEvent> =
    flow {
        val follower = LoraFieldLog(clock)
        status.collect { snapshot -> follower.next(snapshot).forEach { emit(it) } }
    }

/**
 * The state machine behind [loraFieldEdges]: one [next] per status snapshot, returning the edges it crossed.
 *
 * - **Up.** The link reaching [LinkState.Ready] writes one [FieldEvent.LoraUp]. Its dial runs from the last attempt's
 *   start — the last [LinkState.Connecting] or background wait ([MeshtasticSession.WAITING_FOR_BOARD]) armed — not
 *   from the drop: the log's `relink` already holds the outage. Its tries are the [FieldEvent.LoraFail] lines this
 *   follower wrote since the last up or down, counted here because the session's `streak` carries over unhealthy
 *   sessions and the drop itself counts one. A first snapshot that is already ready writes an up with no dial, so
 *   every down has its up. A ready link re-published with its radio config filled in (the session's
 *   `ready.copy(radio = …)`) is the same up.
 * - **Down.** Ready left writes one [FieldEvent.LoraDown], its reason from the state that replaced it ([LoraLinkEnd]).
 *   A [LinkState.Disconnected] straight after ready is that down's reason, not a failure as well.
 * - **Fail.** An attempt that ends before ready — a failure's [LinkState.Disconnected], [LinkState.NeedsPairing],
 *   [LinkState.StaleBond] — writes one [FieldEvent.LoraFail], deduplicated on the retry time, since the transport
 *   re-publishes the same state on every status change. Its streak is the session's backoff position, the attempt
 *   that failed included (for a terminal state, which carries none, one past the last one published). The background
 *   wait is a dial mode, never a failure. A first snapshot already waiting out a failure writes nothing: an earlier
 *   session's follower wrote it.
 *
 * What conflation hides: the status is a `StateFlow`, so a link ready for only milliseconds can be read as
 * handshaking then disconnected — a `lora_fail` with no up or down. Nothing here can recover it.
 */
internal class LoraFieldLog(
    private val clock: () -> Long,
) {
    private var first = true
    private var previous: LinkState? = null

    /** When the link last became ready; null while it is not. */
    private var upAt: Long? = null

    /** When the attempt now in progress began; null when this follower never saw it begin. */
    private var attemptAt: Long? = null

    /** Failures written since the last up or down. */
    private var tries = 0

    /** The retry time of the last failure counted (or absorbed), so a re-published one is counted once. */
    private var countedRetryAt: Long? = null

    /** The backoff position the session last published. */
    private var streak = 0

    @Synchronized
    fun next(status: LoraStatus): List<FieldEvent> {
        val now = clock()
        val state = status.state
        val edges = mutableListOf<FieldEvent>()
        if (state != previous) link(state, now, edges)
        previous = state
        first = false
        return edges
    }

    private fun link(
        state: LinkState,
        now: Long,
        edges: MutableList<FieldEvent>,
    ) {
        (state as? LinkState.Disconnected)?.let { streak = it.streak }
        val up = upAt
        when {
            state is LinkState.Ready -> {
                if (up == null) edges += upOf(state, now)
            }

            up != null -> {
                val (reason, code) = LoraLinkEnd.of(state)
                edges += FieldEvent.LoraDown(reason = reason, lifeMs = now - up, status = code)
                upAt = null
                tries = 0
                attemptAt = null
                (state as? LinkState.Disconnected)?.let { countedRetryAt = it.retryAtMs }
            }

            else -> {
                attempt(state, now, edges)
            }
        }
    }

    private fun upOf(
        state: LinkState.Ready,
        now: Long,
    ): FieldEvent.LoraUp {
        val radio = state.radio
        val up =
            FieldEvent.LoraUp(
                dialMs = attemptAt?.let { now - it },
                tries = tries,
                mtu = state.mtu,
                region = radio?.region,
                preset = radio?.takeIf { it.usePreset }?.modemPreset,
                dedicatedSlot = radio?.dedicatedSlot,
                hopLimit = radio?.hopLimit,
                xeddsa = state.board.hasXeddsa,
            )
        upAt = now
        tries = 0
        attemptAt = null
        return up
    }

    private fun attempt(
        state: LinkState,
        now: Long,
        edges: MutableList<FieldEvent>,
    ) {
        when (state) {
            // A first one began before this follower: its dial is unknown, not this follower's start.
            LinkState.Connecting -> if (!first) attemptAt = now

            is LinkState.Disconnected -> disconnected(state, now, edges)

            is LinkState.NeedsPairing, is LinkState.StaleBond -> if (!first) edges += failOf(state, streak + 1, waitMs = null)

            else -> Unit // bonding, handshaking, the adapter off, stopped: no attempt began or ended
        }
    }

    private fun disconnected(
        state: LinkState.Disconnected,
        now: Long,
        edges: MutableList<FieldEvent>,
    ) {
        if (state.retryAtMs == countedRetryAt) return
        countedRetryAt = state.retryAtMs
        when {
            state.reason == MeshtasticSession.WAITING_FOR_BOARD -> if (!first) attemptAt = now

            first -> Unit

            // an earlier session's follower wrote it
            else -> edges += failOf(state, state.streak, (state.retryAtMs - now).coerceAtLeast(0))
        }
    }

    private fun failOf(
        state: LinkState,
        position: Int,
        waitMs: Long?,
    ): FieldEvent.LoraFail {
        val (reason, code) = LoraLinkEnd.of(state)
        tries++
        // The attempt is over: a snapshot conflated away must not let the next up's dial run from this one's start.
        attemptAt = null
        return FieldEvent.LoraFail(reason = reason, streak = position, status = code, waitMs = waitMs)
    }
}
