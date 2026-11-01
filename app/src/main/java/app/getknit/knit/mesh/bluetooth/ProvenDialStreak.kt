package app.getknit.knit.mesh.bluetooth

import java.util.concurrent.atomic.AtomicInteger

/**
 * When a dialed link's success may reset the peer's redial streak (ADR 2026-11.264w, knit-next #232).
 *
 * On a link where both HELLOs carry `CAP_LINK_PROOF`, the dialer registers once the responder's PROOF verifies and its
 * own is written, but the responder has not yet verified that one. If it fails it, the responder closes, and the dialer
 * sees an end on a link it just registered. Reset at registration, that end would be a flap on a streak of zero: a 10 s
 * redial forever to a responder that refuses every proof (the bluetooth-reviewer's design pass). So a dialed proven link
 * leaves the streak as the dial found it until the responder's first record comes in ([confirm]): it writes nothing
 * before it verified the proof, so any record says the proof held. An end before that ([end]) is a failed handshake
 * on top of the saved streak, unless the link outlived [CONFIRM_BY_AGE_MS], which the responder's 5 s PROOF wait and
 * its close never take: that link was kept, and ends as it does today (reset, then a flap).
 *
 * Every other link (accepted, or dialed without a proof) is [pending] false and settles at registration, as before.
 * One per link. The reader's [confirm] and either loop's [end] race; a compare-and-set settles which counted.
 */
internal class ProvenDialStreak(
    pending: Boolean,
) {
    private val state = AtomicInteger(if (pending) PENDING else SETTLED)

    /** How a link's end counts on the peer's streak. */
    enum class End {
        /** As today: the streak was settled, and a flap bumps it. */
        FLAP,

        /** The responder never confirmed our proof: a failed dial, bumping the streak the dial found. */
        HANDSHAKE,

        /** Unconfirmed but long-lived, so the responder kept it: reset the streak, then count the flap. */
        RESET_THEN_FLAP,
    }

    /** Whether the link still waits for the responder's first record. */
    val pending: Boolean get() = state.get() == PENDING

    /** The responder's first record came in: true when the caller resets the streak now (once, and only if not ended). */
    fun confirm(): Boolean = state.compareAndSet(PENDING, SETTLED)

    /** The link ended after [lifetimeMs]: how that end counts. Later calls, and any after [confirm], read [End.FLAP]. */
    fun end(lifetimeMs: Long): End =
        when {
            !state.compareAndSet(PENDING, ENDED) -> End.FLAP
            lifetimeMs < CONFIRM_BY_AGE_MS -> End.HANDSHAKE
            else -> End.RESET_THEN_FLAP
        }

    companion object {
        /**
         * A dialed proven link older than this was kept by its responder, record or not: the responder waits at most
         * 5 s for our PROOF and closes as soon as it fails, and an L2CAP close reaches us well inside the rest.
         */
        const val CONFIRM_BY_AGE_MS = 15_000L

        private const val PENDING = 0
        private const val SETTLED = 1
        private const val ENDED = 2
    }
}
