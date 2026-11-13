package app.getknit.knit.ui.chat

import app.getknit.knit.data.relay.RelayReach
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.lora.BoardKind
import app.getknit.knit.mesh.lora.LoraFacts
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.mesh.lora.LoraSizeHint
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The face of a MeshCore plane (ADR 2026-11.gsqu's Stage 5a-2 amendment, lora-reviewer finding 11): a DM reaches direct
 * neighbours alone, so the LoRa-only notice reads the per-board direct mark and never the flood-fed reachable set; the
 * room's saturated notice reads the flood share; and the room composer's budget is the kind's one packet.
 */
class MeshcoreLoraReachTest {
    private val boardOnly = setOf(TransportKind.LoRa)
    private val meshcore = LoraFacts(LoraPlane.Live, dms = true, board = BoardKind.MESHCORE, directReach = setOf("ana"))

    @Test
    fun aMeshcoreDmIsLoraOnlyByTheDirectMarkNeverTheFloodFedReachableSet() {
        assertEquals(LoraReach.LoraOnly, loraReachFor("ana", meshcore, boardOnly, RelayReach.Silent))
        // Heard through repeaters (reachable), but no zero-hop mark: a DM would not reach them.
        assertEquals(LoraReach.Silent, loraReachFor("bo", meshcore, boardOnly, RelayReach.Silent))
        // Marked, but another radio carries them.
        assertEquals(LoraReach.Silent, loraReachFor("ana", meshcore, setOf(TransportKind.LoRa, TransportKind.Bluetooth), RelayReach.Silent))
        // Marked, and not in the reachable set at all: the mark is what a zero-hop DM rides on.
        assertEquals(LoraReach.LoraOnly, loraReachFor("ana", meshcore, null, RelayReach.Silent))
    }

    @Test
    fun theRoomIsSaturatedWhenItsFloodShareIsSpentLongBeforeTheTotal() {
        assertEquals(LoraReach.RoomSaturated, loraRoomReachFor(meshcore.copy(floodSpent = true), loraOnlyPeer = true))
        assertEquals(LoraReach.Silent, loraRoomReachFor(meshcore.copy(floodSpent = true), loraOnlyPeer = false))
        assertEquals(LoraReach.Silent, loraRoomReachFor(meshcore, loraOnlyPeer = true))
    }

    @Test
    fun aLawHoldReadsAsASpentWindowInTheRoomAndTheDm() {
        // The band's law can hold every frame for up to an hour (lora-reviewer N7): both notices say messages wait for air.
        val held = meshcore.copy(lawHeld = true)
        assertEquals(LoraReach.RoomSaturated, loraRoomReachFor(held, loraOnlyPeer = true))
        assertEquals(LoraReach.LoraOnlySaturated, loraReachFor("ana", held, boardOnly, RelayReach.Silent))
        assertEquals("not where nothing rides the board", LoraReach.Silent, loraReachFor("bo", held, boardOnly, RelayReach.Silent))
    }

    @Test
    fun theRoomComposersBudgetIsTheKindsOnePacket() {
        assertEquals(LoraSizeHint.MESHCORE_ROOM_BODY_BYTES, LoraSizeHint.roomBodyBytes(BoardKind.MESHCORE))
        assertEquals(LoraSizeHint.ROOM_BODY_BYTES, LoraSizeHint.roomBodyBytes(BoardKind.MESHTASTIC))
        assertEquals(
            LoraSizeHint.MESHCORE_ROOM_BODY_BYTES,
            loraBudgetFor(LoraCarry.Room, replying = false, attached = false, room = LoraSizeHint.MESHCORE_ROOM_BODY_BYTES),
        )
        assertEquals(
            "a reply never fits",
            0,
            loraBudgetFor(LoraCarry.Room, replying = true, attached = false, room = LoraSizeHint.MESHCORE_ROOM_BODY_BYTES),
        )
    }
}
