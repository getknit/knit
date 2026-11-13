package app.getknit.knit.mesh.lora.meshcore

/**
 * The phone's model of the board's expected-ack table (ADR 2026-11.gsqu §2; binding rule "sends" 3). The firmware keeps
 * `EXPECTED_ACK_TABLE_SIZE` = 8 codes in a circular table (`MyMesh.h:285`), advanced by **every** ack-expecting send from
 * **any** client (`MyMesh.cpp:1160-1165`) and never freed but by the ack itself (`processAck`, `MyMesh.cpp:417`): a ninth
 * send overwrites the oldest code, confirmed or not, and a confirmation for an overwritten code is never pushed.
 *
 * So the model is the last [size] sends the board has answered `SENT` for — ours, with the handle of the DM they belong
 * to, and another client's, which we see because the board answers every interface. A code of ours is live, and its
 * confirmation ours, while fewer than [size] newer sends have pushed it out; a confirmation names the handle even after
 * the DM ended `Unreachable`, for the late `Acked` the core reads in `onUnawaited`. The board is full ([full]) while all
 * [size] codes are still inside their ack window and unconfirmed: one more send would overwrite a code still owed.
 *
 * Pure, clock-free (the session passes `now`), and every entry point `@Synchronized`, though only the session's actor
 * reaches it.
 */
internal class MeshcoreAckTable(
    private val size: Int = EXPECTED_ACK_TABLE_SIZE,
) {
    private class Entry(
        val code: Int,
        val handle: UInt?,
        val sentAt: Long,
        val windowMs: Long,
    ) {
        var confirmed = false
    }

    /** Oldest first, at most [size]: the board's table in the order it will overwrite it. */
    private val ring = ArrayDeque<Entry>()

    /**
     * The board answered `SENT` for a send expecting [code] — ours for DM [handle], or another client's (null) — at
     * [now], expecting its answer within [windowMs]. Takes the oldest slot, as the firmware's `next_ack_idx` does.
     */
    @Synchronized
    fun onSent(
        code: Int,
        handle: UInt?,
        now: Long,
        windowMs: Long,
    ) {
        if (code == 0) return // a send expecting no ack never takes a slot (`if (expected_ack)`)
        ring.addLast(Entry(code, handle, now, windowMs))
        while (ring.size > size) ring.removeFirst()
    }

    /**
     * `SEND_CONFIRMED` for [code]: the handle of our DM it confirms, or null for another client's, an overwritten code or
     * one already confirmed (the board pushes a repeated ack once per arrival, but clears the slot on the first).
     */
    @Synchronized
    fun onConfirmed(code: Int): UInt? {
        val entry = ring.lastOrNull { it.code == code && !it.confirmed } ?: return null
        entry.confirmed = true
        return entry.handle
    }

    /** Whether [code] still sits in the board's table, unconfirmed. */
    @Synchronized
    fun isLive(code: Int): Boolean = ring.any { it.code == code && !it.confirmed }

    /** Whether one more send would overwrite a code still inside its window: the session answers `Busy` instead. */
    @Synchronized
    fun full(now: Long): Boolean = ring.size >= size && ring.all { !it.confirmed && now < it.sentAt + it.windowMs }

    /** The board rebooted (its uptime went backwards): its table is empty, and no code in it will ever be confirmed. */
    @Synchronized
    fun clear() = ring.clear()

    companion object {
        /** `EXPECTED_ACK_TABLE_SIZE` (`MyMesh.h:285`). */
        const val EXPECTED_ACK_TABLE_SIZE = 8
    }
}
