package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.power.ElapsedWait

/**
 * The bond state read once it has settled. After a GATT op that started unbonded ends `Closed`, the bond read decides
 * between a pairing that never happened (terminal) and an ordinary drop (a back-off and a redial). The bond-state
 * broadcast and the GATT disconnect are not ordered, so a read straight after the close can still say [BondState.BONDING]
 * for a pairing the user just cancelled: read as a drop, it redials into a second passkey prompt. So the read polls until
 * the state is anything but `BONDING`, at most [SETTLE_MS] on the elapsed clock (ADR 2026-10.pj9w), and gives back what it
 * last read when that runs out. Pure: the session and the MeshCore dialer each pass their own clock.
 */
internal object BondSettle {
    /** The longest the read waits for a pairing to finish moving. */
    const val SETTLE_MS = 2_000L

    /** How often it looks in between. */
    const val POLL_MS = 200L

    suspend fun settled(
        waits: ElapsedWait,
        now: () -> Long,
        read: () -> BondState,
    ): BondState {
        val deadline = now() + SETTLE_MS
        while (true) {
            val bond = read()
            val left = deadline - now()
            if (bond != BondState.BONDING || left <= 0) return bond
            waits.sleep(minOf(POLL_MS, left))
        }
    }
}
