package app.getknit.knit.ui.chat

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.relay.RelayReach
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.lora.LoraFacts
import app.getknit.knit.mesh.lora.LoraPlane
import app.getknit.knit.mesh.lora.LoraSizeHint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The LoRa-only notice, the composer's carry form, and its budget — pure rules over the plane facts. */
class LoraReachTest {
    private val live = LoraFacts(LoraPlane.Live, dms = true)
    private val boardOnly = setOf(TransportKind.LoRa)

    @Test
    fun `a DM whose peer only the board has heard reads LoRa-only`() {
        assertEquals(LoraReach.LoraOnly, loraReachFor("ana", live, boardOnly, RelayReach.Silent))
        assertEquals(LoraReach.LoraOnly, loraReachFor("ana", live, boardOnly, RelayReach.Pending))
    }

    @Test
    fun `the notice stays quiet whenever a better plane has the peer, or there is no board`() {
        // The room is addressed to no one, so it never reaches the DM rule — it has loraRoomReachFor.
        assertEquals(LoraReach.Silent, loraReachFor(Conversations.NEARBY, live, boardOnly, RelayReach.Room))
        // A peer another radio reaches needs no ornament.
        assertEquals(LoraReach.Silent, loraReachFor("ana", live, setOf(TransportKind.LoRa, TransportKind.Bluetooth), RelayReach.Silent))
        assertEquals(LoraReach.Silent, loraReachFor("ana", live, setOf(TransportKind.WifiAware), RelayReach.Silent))
        // Not reachable over anything: the existing offline behaviour speaks, not this notice.
        assertEquals(LoraReach.Silent, loraReachFor("ana", live, null, RelayReach.Silent))
        // A relay-covered thread has a carrier that beats the board.
        assertEquals(LoraReach.Silent, loraReachFor("ana", live, boardOnly, RelayReach.Covered))
        // The board is down (the header glyph already says so).
        assertEquals(LoraReach.Silent, loraReachFor("ana", LoraFacts(LoraPlane.Down, dms = true), boardOnly, RelayReach.Silent))
        assertEquals(LoraReach.Silent, loraReachFor("ana", LoraFacts(), boardOnly, RelayReach.Silent))
    }

    @Test
    fun `with private messages kept off LoRa the notice says nothing reaches them`() {
        assertEquals(LoraReach.LoraOnlyDmsOff, loraReachFor("ana", LoraFacts(LoraPlane.Live, dms = false), boardOnly, RelayReach.Silent))
    }

    @Test
    fun `with the airtime window spent a LoRa-only DM is told it will wait`() {
        val spent = LoraFacts(LoraPlane.Live, dms = true, airtimeSpent = true)
        assertEquals(LoraReach.LoraOnlySaturated, loraReachFor("ana", spent, boardOnly, RelayReach.Silent))
        // Only where the LoRa-only notice would have shown: a better carrier still says nothing…
        assertEquals(LoraReach.Silent, loraReachFor("ana", spent, setOf(TransportKind.LoRa, TransportKind.Bluetooth), RelayReach.Silent))
        // …and the room never comes through this rule at all, spent or not.
        assertEquals(LoraReach.Silent, loraReachFor(Conversations.NEARBY, spent, boardOnly, RelayReach.Room))
        // …and the DMs-off notice outranks it (nothing is going out at all, spent or not).
        assertEquals(LoraReach.LoraOnlyDmsOff, loraReachFor("ana", spent.copy(dms = false), boardOnly, RelayReach.Silent))
    }

    @Test
    fun `the room says so once the window is spent and somebody is behind the board`() {
        val spent = live.copy(airtimeSpent = true)
        assertEquals(LoraReach.RoomSaturated, loraRoomReachFor(spent, loraOnlyPeer = true))
        // The room rides LoRa whatever the DM switch says, so a spent window still speaks with DMs off.
        assertEquals(LoraReach.RoomSaturated, loraRoomReachFor(spent.copy(dms = false), loraOnlyPeer = true))
    }

    @Test
    fun `the room stays quiet when a spent window delays nobody, or when there is air left`() {
        val spent = live.copy(airtimeSpent = true)
        // Everyone we have heard is on a phone radio: the queue holds nothing anyone is waiting for.
        assertEquals(LoraReach.Silent, loraRoomReachFor(spent, loraOnlyPeer = false))
        // Somebody is out there, but the window has room — posts are not delayed yet.
        assertEquals(LoraReach.Silent, loraRoomReachFor(live, loraOnlyPeer = true))
        // No board, or one that is down: the header glyph is what speaks.
        assertEquals(LoraReach.Silent, loraRoomReachFor(LoraFacts(LoraPlane.Down, airtimeSpent = true), true))
        assertEquals(LoraReach.Silent, loraRoomReachFor(LoraFacts(airtimeSpent = true), loraOnlyPeer = true))
    }

    @Test
    fun `a group says its messages do not travel over LoRa while a member is behind the board`() {
        assertEquals(LoraReach.GroupUnsupported, loraGroupReachFor(live, loraOnlyMember = true, RelayReach.Silent))
        assertEquals(LoraReach.GroupUnsupported, loraGroupReachFor(live, loraOnlyMember = true, RelayReach.Pending))
        // Nothing about this is congestion: a spent window and the DM switch are both beside the point,
        // because the plane refuses group-form frames whatever the ledger says.
        val spent = live.copy(airtimeSpent = true, dms = false)
        assertEquals(LoraReach.GroupUnsupported, loraGroupReachFor(spent, loraOnlyMember = true, RelayReach.Silent))
    }

    @Test
    fun `a group stays quiet with every member on a phone radio, no board, or a relay carrying it`() {
        assertEquals(LoraReach.Silent, loraGroupReachFor(live, loraOnlyMember = false, RelayReach.Silent))
        // A group scope *is* scope-eligible, unlike the room — a covered thread has a carrier.
        assertEquals(LoraReach.Silent, loraGroupReachFor(live, loraOnlyMember = true, RelayReach.Covered))
        assertEquals(LoraReach.Silent, loraGroupReachFor(LoraFacts(LoraPlane.Down), true, RelayReach.Silent))
        assertEquals(LoraReach.Silent, loraGroupReachFor(LoraFacts(), loraOnlyMember = true, RelayReach.Silent))
    }

    @Test
    fun `LoRa-only is the board and nothing else`() {
        assertTrue(isLoraOnly(boardOnly))
        assertFalse(isLoraOnly(setOf(TransportKind.LoRa, TransportKind.Bluetooth)))
        assertFalse(isLoraOnly(setOf(TransportKind.WifiAware)))
        assertFalse(isLoraOnly(emptySet()))
        assertFalse(isLoraOnly(null))
    }

    @Test
    fun `a draft rides LoRa as a room post or a DM, never in a group or with the switch off`() {
        assertEquals(LoraCarry.Room, loraCarryFor(Conversations.NEARBY, isGroup = false, facts = live))
        assertEquals(LoraCarry.Dm, loraCarryFor("ana", isGroup = false, facts = live))
        assertEquals(LoraCarry.None, loraCarryFor("g-1", isGroup = true, facts = live))
        assertEquals(LoraCarry.None, loraCarryFor("ana", isGroup = false, facts = LoraFacts(LoraPlane.Live, dms = false)))
        assertEquals(LoraCarry.None, loraCarryFor("ana", isGroup = false, facts = LoraFacts(LoraPlane.Down, dms = true)))
        // The room still rides with private messages off — the switch is about DMs only.
        assertEquals(LoraCarry.Room, loraCarryFor(Conversations.NEARBY, isGroup = false, facts = LoraFacts(LoraPlane.Live, dms = false)))
    }

    @Test
    fun `the room's composer is gated on a live board that can post, and nothing else is gated at all`() {
        assertEquals(PublicPostGate.NoRadio, publicPostGateFor(Conversations.MESHTASTIC, LoraFacts()))
        assertEquals(PublicPostGate.RadioDown, publicPostGateFor(Conversations.MESHTASTIC, LoraFacts(LoraPlane.Down)))
        assertEquals(PublicPostGate.ChannelUnusable, publicPostGateFor(Conversations.MESHTASTIC, LoraFacts(LoraPlane.Live)))
        assertEquals(PublicPostGate.Open, publicPostGateFor(Conversations.MESHTASTIC, LoraFacts(LoraPlane.Live, canPost = true)))
        // Every other thread is open whatever the radio is doing — the gate is about the room's own path.
        assertEquals(PublicPostGate.Open, publicPostGateFor(Conversations.NEARBY, LoraFacts()))
        assertEquals(PublicPostGate.Open, publicPostGateFor("ana", LoraFacts(LoraPlane.Down)))
    }

    @Test
    fun `the bridged room carries nothing here — its length rule is a hard cap of its own`() {
        // Not None because the plane is down: a bridged post is capped to a Meshtastic frame in the composer
        // whatever this plane is doing. Left as Dm it would take the DM's 320-byte hint and hang a soft "may
        // not reach people over LoRa" under a field that has already refused the 201st byte, and it would
        // follow the private-messages-over-LoRa switch, which governs nothing in this room.
        assertEquals(LoraCarry.None, loraCarryFor(Conversations.MESHTASTIC, isGroup = false, facts = live))
        assertEquals(
            LoraCarry.None,
            loraCarryFor(Conversations.MESHTASTIC, isGroup = false, facts = LoraFacts(LoraPlane.Live, dms = false)),
        )
    }

    @Test
    fun `the budget follows the carry form and what rides along`() {
        assertNull(loraBudgetFor(LoraCarry.None, replying = false, attached = false))
        assertEquals(LoraSizeHint.ROOM_BODY_BYTES, loraBudgetFor(LoraCarry.Room, replying = false, attached = false))
        assertEquals(LoraSizeHint.DM_BODY_BYTES, loraBudgetFor(LoraCarry.Dm, replying = false, attached = false))
        assertEquals(
            LoraSizeHint.DM_BODY_BYTES - LoraSizeHint.REPLY_RESERVE_BYTES,
            loraBudgetFor(LoraCarry.Dm, replying = true, attached = false),
        )
    }
}
