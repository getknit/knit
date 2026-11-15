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
        val base = 400
        assertEquals(base, LoraSizeHint.budget(base, replying = false, attached = false))
        assertEquals(base - LoraSizeHint.REPLY_RESERVE_BYTES, LoraSizeHint.budget(base, replying = true, attached = false))
        assertEquals(base - LoraSizeHint.ATTACHMENT_RESERVE_BYTES, LoraSizeHint.budget(base, replying = false, attached = true))
        assertEquals(LoraSizeHint.DM_BODY_BYTES, LoraSizeHint.budget(LoraSizeHint.DM_BODY_BYTES, replying = false, attached = false))
        // Both one-packet budgets (ADR 2026-10.rp3s, ADR 2026-10.x9fa) are smaller than either reserve: nothing left,
        // never negative — a reply or an attachment never rides LoRa.
        assertEquals(0, LoraSizeHint.budget(LoraSizeHint.ROOM_BODY_BYTES, replying = false, attached = true))
        assertEquals(0, LoraSizeHint.budget(LoraSizeHint.DM_BODY_BYTES, replying = true, attached = false))
        assertEquals(0, LoraSizeHint.budget(LoraSizeHint.DM_BODY_BYTES, replying = false, attached = true))
    }

    /**
     * The 5c-1 reviews' N4: the MeshCore composer's budget follows what the band's flood ceiling can ever admit — two
     * full packets on the companion's 10 % sub-band, none on a 1 % one, where a post would be dropped as `loraAirNever` —
     * and the full budget until the board reports its duty. Meshtastic's never moves.
     */
    @Test
    fun `the MeshCore room budget is what the band's flood ceiling can ever admit`() {
        val air = LoraAirtime(dedicatedUnlocksDuty = true, traits = app.getknit.knit.mesh.lora.meshcore.MeshcoreTraits)
        assertEquals("no duty yet", null, air.snapshot(0).roomPacketsFit)
        val radio = RadioParams(8, 62_500, 5)
        air.onRadio(radio, DutyPolicy(10.0, dedicatedSlot = false))
        assertEquals(2, air.snapshot(0).roomPacketsFit)
        air.onRadio(radio, DutyPolicy(1.0, dedicatedSlot = false))
        assertEquals(0, air.snapshot(0).roomPacketsFit)
        assertEquals(LoraSizeHint.MESHCORE_ROOM_BODY_BYTES, LoraSizeHint.roomBodyBytes(BoardKind.MESHCORE, null))
        assertEquals(LoraSizeHint.MESHCORE_ROOM_BODY_BYTES, LoraSizeHint.roomBodyBytes(BoardKind.MESHCORE, 2))
        assertEquals(LoraSizeHint.MESHCORE_ONE_PACKET_ROOM_BODY_BYTES, LoraSizeHint.roomBodyBytes(BoardKind.MESHCORE, 1))
        assertEquals(0, LoraSizeHint.roomBodyBytes(BoardKind.MESHCORE, 0))
        assertEquals(LoraSizeHint.ROOM_BODY_BYTES, LoraSizeHint.roomBodyBytes(BoardKind.MESHTASTIC, 0))
    }
}
