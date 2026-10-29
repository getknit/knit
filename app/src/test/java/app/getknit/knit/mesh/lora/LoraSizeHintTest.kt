package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Test

/** The composer hint's arithmetic; the budgets themselves are pinned against real frames in `CoordinationPlaneSizeBudgetTest`. */
class LoraSizeHintTest {
    @Test
    fun `utf8Length counts bytes, not chars`() {
        assertEquals(0, LoraSizeHint.utf8Length(""))
        assertEquals(5, LoraSizeHint.utf8Length("hello"))
        assertEquals(2, LoraSizeHint.utf8Length("é"))
        assertEquals(3, LoraSizeHint.utf8Length("€"))
        assertEquals(4, LoraSizeHint.utf8Length("🗺"))
        assertEquals("🗺️ map".toByteArray(Charsets.UTF_8).size, LoraSizeHint.utf8Length("🗺️ map"))
        // A lone surrogate encodes as the 3-byte replacement — counted, never thrown on.
        assertEquals(3, LoraSizeHint.utf8Length("\uD83D"))
    }

    @Test
    fun `a reply and an attachment each take their reserve off the budget, never below zero`() {
        assertEquals(LoraSizeHint.DM_BODY_BYTES, LoraSizeHint.budget(LoraSizeHint.DM_BODY_BYTES, replying = false, attached = false))
        assertEquals(
            LoraSizeHint.DM_BODY_BYTES - LoraSizeHint.REPLY_RESERVE_BYTES,
            LoraSizeHint.budget(LoraSizeHint.DM_BODY_BYTES, replying = true, attached = false),
        )
        assertEquals(
            LoraSizeHint.DM_BODY_BYTES - LoraSizeHint.ATTACHMENT_RESERVE_BYTES,
            LoraSizeHint.budget(LoraSizeHint.DM_BODY_BYTES, replying = false, attached = true),
        )
        // The room's one-packet budget (ADR 2026-10.rp3s) is smaller than either reserve: nothing left, never negative.
        assertEquals(0, LoraSizeHint.budget(LoraSizeHint.ROOM_BODY_BYTES, replying = false, attached = true))
        assertEquals(0, LoraSizeHint.budget(LoraSizeHint.DM_BODY_BYTES, replying = true, attached = true))
    }
}
