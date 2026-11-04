package app.getknit.knit.mesh.bluetooth

import app.getknit.knit.mesh.OwnPresenceKey
import app.getknit.knit.mesh.crypto.BlindTag
import app.getknit.knit.mesh.power.ElapsedWait
import app.getknit.knit.mesh.runRound
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield

/**
 * When a blinding phone rotates its advert (ADR 2026-11.xp6d, "Rotation"; step 5b's W4): the slot rotation's one turn,
 * on the wall clock. Pure: no Android, the clocks passed in, single-threaded with its owner (the transport's confined
 * worker).
 *
 * **The turn.** A tag aired in slot `s` rotates at the first wake at or after the next slot edge `E` (the start of
 * `s + 1`, every phone's edge at once, so edge timing links nothing in a crowd). The wiring waits for [Step.Wait]'s
 * wall-clock instant on an `ElapsedWait` over the wall clock: a coroutine `delay` stops while the CPU sleeps, so a
 * 15-minute slot would last 150 minutes of wall time on a pocketed phone awake a tenth of the time (the bluetooth design
 * pass of 2026-11-03). Such a wait ends within `ElapsedWait.CHECK_MS` of the first CPU wake after it falls due, and adds
 * no wakes of its own: a lone dozing phone's longest tag life is therefore the gap between its Doze maintenance windows.
 *
 *  - **Announce** ([Step.Announce]) at the first wake at or after `E − leadMs`: the wiring reserves the coming slot's tag
 *    (`TagSchedule.reserve`) to send in `TAG_NEXT`. [leadMs] is 30 s plus a linked dozing phone's longest wake gap (a
 *    peer's re-offer stretches to a median of ~110 s, `LinkSilence`), so a linked peer that sleeps through most of the
 *    lead still holds the tag before it airs.
 *  - **Rotate** ([Step.Rotate]) at the first wake at or after `max(E, announcedAt + minLeadMs)`: the announced tag airs
 *    exactly ([Step.Rotate.announced], `TagSchedule.take`). A wake that comes after the edge with nothing announced, or
 *    a slot or more late, rotates at once and unannounced (`next()`); the wiring then sends the current form once to each
 *    eligible link instead, never a lead announcement as well (the receiver keeps one `TAG_NEXT` per link per 2 min).
 *  - **The hold** is bounded from the due time `T`: a rotation due at `T` waits while [next]'s `holding` is true (a fast
 *    Coded window, a range or cold hunt, a dial of mine in flight) or an ACL edge settles, but never past `T + holdMs`,
 *    whatever windows open after `T`. Measured per window instead, a peer that links weakly and drops under fresh ids
 *    every couple of minutes would keep one tag on the air for as long as it stayed (the security design pass).
 *  - **A clock set back** below the slot on air rotates at once: the schedule's mark then gives a random tag, and the
 *    phone keeps rotating every slot rather than airing one tag until the clock catches up.
 *
 * A key change, a bring-up and a switch flip are not this class's turns: the wiring rotates at once for each and tells
 * [onRotated]. A key change is never held (the feed cannot tell a fresh pair, which a removed contact's key must not
 * outlive, from a weekly advance). Every rotation the wiring makes in step 5b's B1 is unannounced on the air: the
 * announcement's reservation is made here, but `TAG_NEXT` itself is B2's.
 */
internal class AdvertRotation(
    private val leadMs: Long = LEAD_MS,
    private val minLeadMs: Long = MIN_LEAD_MS,
    private val recheckMs: Long = HOLD_RECHECK_MS,
) {
    /** What the turn does now. */
    sealed interface Step {
        /** Nothing until [atWallMs] (or an earlier poke: a key change, a clock change, a bring-up, a hold's end). */
        data class Wait(
            val atWallMs: Long,
        ) : Step

        /** Reserve the coming slot's tag and announce it. */
        data object Announce : Step

        /**
         * Rotate now: [announced] when the tag announced for this slot is the one to air (`TagSchedule.take`); [held] when
         * the turn waited on a hold or a settle first, [bounded] when it rotates at the bound with one still on.
         */
        data class Rotate(
            val announced: Boolean,
            val held: Boolean = false,
            val bounded: Boolean = false,
        ) : Step
    }

    /** Why a rotation ran: counted (`bleRotations`), never logged beside a time, a generation or a slot. */
    enum class Cause { SLOT, BRINGUP, FRESH, FLIP }

    // The slot of the tag on the air, or null while no blinded form is (the writer off, the radio down).
    private var airedSlot: Long? = null
    private var announcedAt: Long? = null
    private var announcedSlot: Long? = null

    // T, the time the slot rotation now pending first came due; null until it is due. [held]: it has waited on a hold.
    private var dueAt: Long? = null
    private var held = false

    /** A blinded form went on the air at [wallMs] (any cause): the next edge is the end of its slot. */
    fun onRotated(wallMs: Long) {
        airedSlot = BlindTag.slotOf(wallMs)
        announcedAt = null
        announcedSlot = null
        dueAt = null
        held = false
    }

    /** No blinded form is on the air any more (the writer switched off, the radio went down): nothing to turn. */
    fun onStopped() {
        airedSlot = null
        announcedAt = null
        announcedSlot = null
        dueAt = null
        held = false
    }

    /** The coming slot's tag was reserved and announced at [wallMs]. */
    fun onAnnounced(wallMs: Long) {
        announcedAt = wallMs
        announcedSlot = BlindTag.slotOf(wallMs) + 1
    }

    /** The reservation was dropped (a key change, adapter OFF): what was announced will not air. */
    fun dropAnnouncement() {
        announcedAt = null
        announcedSlot = null
    }

    /**
     * When the next announcement is due (wall clock): the coming edge less the lead, while it has not been made; null
     * with nothing on the air or the announcement already made. A resend keeps out of the receiver's limit before it.
     */
    fun announceDueAt(): Long? {
        val aired = airedSlot ?: return null
        if (announcedAt != null && announcedSlot == aired + 1) return null
        return (aired + 1) * BlindTag.SLOT_MS - leadMs
    }

    /** Whether a blinded form is on the air, so the turn has an edge to wait for. */
    val turning: Boolean get() = airedSlot != null

    /**
     * The turn's next step at [wallMs]. [holding] is whether a hold applies now (a fast Coded window, a hunt, a dial of
     * mine); [settleLeftMs] how long an ACL edge still settles (9utz: a restart's enables stay out of the stack's pause and
     * resume around a connection); [holdMs] the bound on both, from the due time.
     */
    fun next(
        wallMs: Long,
        holding: Boolean,
        settleLeftMs: Long,
        holdMs: Long,
    ): Step {
        val aired = airedSlot ?: return Step.Wait(Long.MAX_VALUE)
        if (BlindTag.slotOf(wallMs) < aired) return Step.Rotate(announced = false) // the clock went back below the slot
        val edge = (aired + 1) * BlindTag.SLOT_MS
        // When the announced tag may air: the edge, or 30 s after the announcement if that came later; null: none.
        val announcedDue = announcedAt?.takeIf { announcedSlot == aired + 1 }?.let { maxOf(edge, it + minLeadMs) }
        return when {
            wallMs >= edge -> pastEdge(wallMs, announcedDue ?: edge, announcedDue != null, holding, settleLeftMs, holdMs)
            announcedDue != null -> Step.Wait(announcedDue)
            wallMs >= edge - leadMs -> Step.Announce
            else -> Step.Wait(edge - leadMs)
        }
    }

    /** [next] once the edge has passed: the rotation is due at [due], held at most [holdMs] from then. */
    @Suppress("LongParameterList") // the turn's inputs, each a different source in the wiring
    private fun pastEdge(
        wallMs: Long,
        due: Long,
        announced: Boolean,
        holding: Boolean,
        settleLeftMs: Long,
        holdMs: Long,
    ): Step {
        if (wallMs < due) return Step.Wait(due)
        val bound = (dueAt ?: due.also { dueAt = it }) + holdMs
        val holds = holding || settleLeftMs > 0
        if (wallMs < bound && holds) {
            held = true
            val recheck = if (holding) recheckMs else settleLeftMs
            return Step.Wait(minOf(bound, wallMs + recheck))
        }
        val onTime = announced && BlindTag.slotOf(wallMs) == announcedSlot
        return Step.Rotate(announced = onTime, held = held, bounded = held && holds)
    }

    companion object {
        /** The announcement's lead before the edge: ≥ [MIN_LEAD_MS] plus a linked dozing phone's longest wake gap. */
        const val LEAD_MS = 210_000L

        /** The least time between an announcement and the rotation it names (ADR 2026-11.xp6d, "Rotation is announced"). */
        const val MIN_LEAD_MS = 30_000L

        /**
         * How often a held rotation looks again whether its hold still applies, on the elapsed-aware wall wait: a hold's
         * end (a fast window running out, a dial ending) pokes nothing, and the bound from the due time is the backstop.
         * Only while a rotation is due and held, so at most `fastHoldMs / HOLD_RECHECK_MS` looks per slot.
         */
        const val HOLD_RECHECK_MS = 15_000L

        /**
         * Why a new own key ([incoming]) restarts the advert, or null when it does not: equal to the key [held]
         * ([OwnPresenceKey]'s equality), or the generation already on the air ([airedGeneration], a bring-up that used it).
         * The session's first key ([sessionHadKey] false) after a bring-up that aired a random tag for want of one completes
         * that bring-up ([Cause.BRINGUP]); every other change, a null among them (a fresh pair the engine could not settle),
         * is [Cause.FRESH] and is never held.
         */
        fun causeOfKeyChange(
            held: OwnPresenceKey?,
            incoming: OwnPresenceKey?,
            airedGeneration: Long?,
            sessionHadKey: Boolean,
        ): Cause? =
            when {
                incoming == held -> null
                incoming != null && airedGeneration == incoming.generation -> null
                incoming != null && held == null && !sessionHadKey -> Cause.BRINGUP
                else -> Cause.FRESH
            }
    }
}

/**
 * One pass request per key generation for a stale mark (ADR 2026-11.xp6d's "A clock pushed ahead"; step 5b's W2):
 * `TagSchedule.Next.stale` repeats on every rotation for as long as the key engine's once-a-day floor refuses the MARK
 * advance, so asking on each would ask every slot. [offer] answers true once per generation, the first time a stale
 * answer comes under it; the latch clears when the generation changes. Pure, single-threaded with its owner.
 */
internal class StaleLatch {
    private var latched: Long? = null

    /** Whether a stale answer ([stale]) under [generation] should ask the engine for a pass now. */
    fun offer(
        generation: Long,
        stale: Boolean,
    ): Boolean {
        if (latched != null && latched != generation) latched = null
        if (!stale || latched == generation) return false
        latched = generation
        return true
    }
}

/**
 * The slot turn's loop (step 5b's W4), run on the transport's confined worker: [AdvertRotation] decides, this waits.
 * Every pass suspends (the worker is `limitedParallelism(1)`, so a pass that never suspended would starve every other
 * worker task: the adapter-off reset, a key change, a `stop()`'s `withContext`), and a pass that achieves nothing never
 * repeats at once:
 *  - nothing on the air ([AdvertRotation.turning] false): wait for a [wake] with no timeout (no tick while the writer is
 *    off, the radio is down or a build never blinds);
 *  - a wait: on the wall clock through [wallWait] (elapsed-aware: a privacy bound, never a `delay`);
 *  - a rotation that did not install ([rotate] false): with the radio down, the turn stops ([AdvertRotation.onStopped]);
 *    with it up, it looks again [AdvertRotation.HOLD_RECHECK_MS] later;
 *  - a pass that throws: [onFailure], then the same recheck wait.
 * Pure but for coroutines; `RotationTurnTest` drives it on a virtual clock.
 */
internal class RotationTurn(
    private val rotation: AdvertRotation,
    private val wake: ReceiveChannel<Unit>,
    private val wallWait: ElapsedWait,
    private val wallMs: () -> Long,
    private val holding: () -> Boolean,
    private val settleLeftMs: () -> Long,
    private val holdMs: () -> Long,
    private val radioUp: () -> Boolean,
    private val announce: () -> Unit,
    private val rotate: (AdvertRotation.Step.Rotate) -> Boolean,
    private val onFailure: (Exception) -> Unit,
) {
    suspend fun run() {
        while (true) {
            currentCoroutineContext().ensureActive()
            val wait = pass()
            when {
                wait == null -> wake.receive()
                wait > 0 -> wallWait.receiveWithin(wake, wait)
                else -> yield()
            }
        }
    }

    /** One pass: how long to wait after it (null: until a wake; 0: look again at once, after a yield). */
    private suspend fun pass(): Long? {
        var wait: Long? = AdvertRotation.HOLD_RECHECK_MS
        runRound({
            onFailure(it)
            wait = AdvertRotation.HOLD_RECHECK_MS
        }) {
            wait = step()
        }
        return wait
    }

    private fun step(): Long? {
        if (!rotation.turning) return null
        val wall = wallMs()
        return when (val step = rotation.next(wall, holding(), settleLeftMs(), holdMs())) {
            is AdvertRotation.Step.Wait -> {
                (step.atWallMs - wall).coerceIn(1L, MAX_WAIT_MS)
            }

            AdvertRotation.Step.Announce -> {
                // Marked announced whatever [announce] managed: a turn that could not reserve must not ask again at once.
                try {
                    announce()
                } finally {
                    rotation.onAnnounced(wall)
                }
                0L
            }

            is AdvertRotation.Step.Rotate -> {
                when {
                    rotate(step) -> {
                        0L
                    }

                    radioUp() -> {
                        AdvertRotation.HOLD_RECHECK_MS
                    }

                    else -> {
                        rotation.onStopped()
                        null
                    }
                }
            }
        }
    }

    companion object {
        /** The longest single wait: a bound on the arithmetic, not a timer; the turn recomputes and waits again. */
        const val MAX_WAIT_MS = 3_600_000L
    }
}
