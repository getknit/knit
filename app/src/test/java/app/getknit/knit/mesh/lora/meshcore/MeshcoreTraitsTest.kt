package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.lora.AirPurpose
import app.getknit.knit.mesh.lora.BoardKind
import app.getknit.knit.mesh.lora.BoardPacket
import app.getknit.knit.mesh.lora.Ceiling
import app.getknit.knit.mesh.lora.LoraAirtime
import app.getknit.knit.mesh.lora.LoraBand
import app.getknit.knit.mesh.lora.PacketOutcome
import app.getknit.knit.mesh.lora.PacketPrice
import app.getknit.knit.mesh.lora.RadioParams
import app.getknit.knit.mesh.lora.RefusalClass
import app.getknit.knit.mesh.lora.RoutingError
import app.getknit.knit.mesh.lora.SendResult
import app.getknit.knit.mesh.lora.SignaturePolicy
import app.getknit.knit.mesh.lora.SigningFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [MeshcoreTraits]: the price hook by purpose, the airtime term, the band's duty and the compact DM's numbers (Stage 5a-1). */
class MeshcoreTraitsTest {
    private val traits = MeshcoreTraits

    private fun phy(
        purpose: AirPurpose,
        payload: Int,
    ): Int = traits.phyBytes(payload, traits.price(purpose, SigningFacts(), primaryAead = false, knitAead = false))

    @Test
    fun aRoomFloodIsItsHeaderTransportCodesHashMacAndTheAesBlock() {
        // Q15: two 161-B parts aired as 181 B each with no scope set; a scoped flood adds the 4 B of transport codes.
        assertEquals(181 + 4, phy(AirPurpose.ROOM_FLOOD, 161))
        // The plaintext is data type (2) + length (1) + payload, zero-padded to 16: 15 and 16 fill one block, 17 takes two.
        assertEquals("3 + 12 = 15: one block", 9 + 16, phy(AirPurpose.ROOM_FLOOD, 12))
        assertEquals("3 + 13 = 16: one block, no extra one", 9 + 16, phy(AirPurpose.ROOM_FLOOD, 13))
        assertEquals("3 + 14 = 17: two blocks", 9 + 32, phy(AirPurpose.ROOM_FLOOD, 14))
        assertEquals(
            "a price with no purpose is a room flood",
            phy(AirPurpose.ROOM_FLOOD, 100),
            traits.phyBytes(100, PacketPrice(0, 0, 164)),
        )
    }

    @Test
    fun aZeroHopDmAndAnAckCarryNoTransportCodes() {
        // TXT_MSG direct at zero hops: header, path length, two hashes, MAC, AES over timestamp + flags + COBS(payload).
        assertEquals("5 + 1 + 9 = 15: one block", 6 + 16, phy(AirPurpose.DIRECT_DM, 9))
        assertEquals("5 + 1 + 10 = 16: one block", 6 + 16, phy(AirPurpose.DIRECT_DM, 10))
        assertEquals("5 + 1 + 11 = 17: two blocks", 6 + 32, phy(AirPurpose.DIRECT_DM, 11))
        assertEquals("the largest seal: COBS 157 B in 160 of AES", 6 + 176, phy(AirPurpose.DIRECT_DM, MeshcoreProto.DIRECT_SEAL_CAP))
        assertEquals("an ack: header, path length and the code", 2 + 4, phy(AirPurpose.ACK, 4))
        assertEquals("a multi-ack's five", 2 + MeshcoreTraits.ackPayloadBytes, phy(AirPurpose.ACK, MeshcoreTraits.ackPayloadBytes))
        // A public post and a room flood share the flood's head; a DM's is the 2-B direct head, its 4 codes never added.
        assertEquals(1 + 4 + 1 + 1 + 2, MeshcoreTraits.packetOverheadBytes)
    }

    @Test
    fun aPathReturnFloodsAndAPublicPostToo() {
        // createPathReturn at zero hops: path length + extra type + the 4-B ack = 6 B of plaintext, one block.
        assertEquals(6, MeshcoreTraits.pathReturnPlaintext(0))
        assertEquals(1 + 4 + 1 + 2 + 2 + 16, phy(AirPurpose.PATH_RETURN, MeshcoreTraits.pathReturnPlaintext(0)))
        assertEquals(
            "ten more hashes: still one block",
            1 + 4 + 1 + 2 + 2 + 16,
            phy(AirPurpose.PATH_RETURN, MeshcoreTraits.pathReturnPlaintext(10)),
        )
        assertEquals("eleven: two", 1 + 4 + 1 + 2 + 2 + 32, phy(AirPurpose.PATH_RETURN, MeshcoreTraits.pathReturnPlaintext(11)))
        assertEquals("GRP_TXT: timestamp + flags + text, flooded", 9 + 16, phy(AirPurpose.PUBLIC_POST, 11))
    }

    @Test
    fun theAirtimeTermIsTheFormulaAtTheFirmwaresPreambleAndTheFixedTerm() {
        assertEquals(36.25, MeshcoreTraits.preambleSymbolsAt(7), 0.0)
        assertEquals(36.25, MeshcoreTraits.preambleSymbolsAt(8), 0.0)
        assertEquals(20.25, MeshcoreTraits.preambleSymbolsAt(9), 0.0)
        assertEquals(20.25, MeshcoreTraits.preambleSymbolsAt(12), 0.0)
        // Q5: 181 B at SF7 / BW 62.5 / CR 4/5 measured 633 ± 17 ms. With the firmware's 32-symbol preamble the formula is
        // 309.25 symbols × 2.048 ms = 633 ms; the fixed term puts the price at the measurement's upper sigma.
        val us = RadioParams(7, 62_500, 5)
        assertEquals(633L, LoraAirtime.formulaMs(181, us, MeshcoreTraits.preambleSymbolsAt(7)))
        assertEquals(633L + MeshcoreTraits.FIXED_MS, traits.airMs(181, us))
        assertTrue("never under the measurement", traits.airMs(181, us) >= 633)
        assertTrue("within its sigma above it", traits.airMs(181, us) <= 633 + 17)
        // A small packet: the fixed term is a sixth of a 6-B ack's price, which a ratio would have missed.
        assertEquals(LoraAirtime.formulaMs(6, us, 36.25) + MeshcoreTraits.FIXED_MS, traits.airMs(6, us))
    }

    @Test
    fun aMeshcoreLedgerNeverCarriesMeshtasticSigningFactsOrLongFast() {
        val signing =
            traits.price(
                AirPurpose.ROOM_FLOOD,
                SigningFacts("2.8.1.abc", true, SignaturePolicy.STRICT),
                primaryAead = true,
                knitAead = true,
            )
        assertEquals("the facts change nothing", traits.price(AirPurpose.ROOM_FLOOD, SigningFacts(), false, false), signing)
        assertEquals(LoraAirtime.UNSIGNED, signing.signedUpTo)
        assertEquals(0, signing.extraBytes)
        val air = LoraAirtime(traits = MeshcoreTraits)
        air.onBoard(SigningFacts()) // the expensive unknown, on Meshtastic
        val snap = air.snapshot(0)
        assertFalse("no signature is charged", snap.signing)
        assertFalse("no radio known yet", snap.known)
        // Priced at the companion's own default before a radio is reported, never LongFast.
        assertEquals(traits.airMs(phy(AirPurpose.ROOM_FLOOD, 100), RadioParams(8, 62_500, 5)), air.timeOnAirMs(100))
        // The strictest duty of any band before one is known: 0.1 % × 0.5 of 15 minutes.
        assertEquals((LoraAirtime.WINDOW_MS * LoraBand.STRICTEST_PERCENT / 100 * LoraAirtime.SAFETY).toLong(), air.allowanceMs())
        air.onRadio(RadioParams(7, 62_500, 5), MeshcoreTraits.dutyPolicy(910_525, 62_500))
        assertTrue(air.snapshot(0).known)
        assertEquals("US: the politeness ceiling binds", (LoraAirtime.WINDOW_MS * 0.10 * LoraAirtime.SAFETY).toLong(), air.allowanceMs())
        assertEquals("a two-part post's room under the flood ceiling", (air.allowanceMs() * 0.15).toLong(), air.floodCeilingMs())
        assertEquals("nothing to pad: no cliff", 100, air.padTo(100, MeshcoreProto.CHANNEL_DATA_CAP))
    }

    @Test
    fun theCompactDmsNumbers() {
        assertEquals(BoardKind.MESHCORE, traits.kind)
        assertEquals(3, traits.airings)
        assertEquals("eight attempts are two whole flights of three", 2, traits.maxFlights)
        assertEquals(MeshcoreAckTable.EXPECTED_ACK_TABLE_SIZE / traits.airings, traits.maxFlights)
        assertEquals(Ceiling.FLOOD, traits.roomCeiling)
        assertEquals(2, traits.roomPartLimit)
        assertEquals(0L, traits.minGapMs)
        assertEquals(MeshcoreProto.DIRECT_SEAL_CAP, traits.directPayloadCap(255))
        assertEquals(MeshcoreProto.CHANNEL_DATA_CAP, traits.floorPayload)
        for (air in listOf(1L, 300L, 5_000L, 60_000L)) {
            assertTrue("the backstop never under the session's own cap", traits.deadlineMs(air, 4.0) >= MeshcoreSession.MAX_FLIGHT_MS)
            assertEquals(2, traits.retriesBefore(3_600_000L, traits.retransmitMs(air, 4.0, 0)))
        }
        // The direct est_timeout at zero hops (500 + 6 × air + 250) and the session's retry slack.
        assertEquals(500L + 6 * 300 + 250 + 1_000, traits.retransmitMs(300, 4.0, 0))
    }

    @Test
    fun aHearingIsALiveZeroHopChannelPostAndNeverAContactText() {
        fun packet(
            type: MeshcorePacket.Type,
            pathLen: Int,
            live: Boolean = true,
        ) = BoardPacket(
            from = null,
            toUs = false,
            channel = 1,
            payload = byteArrayOf(1),
            rxSnr = null,
            rxRssi = null,
            hopsAway = null,
            rxTimeMs = null,
            kind = MeshcorePacket(type, 1, pathLen, null, null, null, live, cobs = false, text = null),
        )
        assertTrue(traits.heardLive(packet(MeshcorePacket.Type.CHANNEL_DATA, 0), 0))
        assertFalse("through a repeater", traits.heardLive(packet(MeshcorePacket.Type.CHANNEL_DATA, 1), 0))
        assertFalse("0xFF reads direct through a repeater too", traits.heardLive(packet(MeshcorePacket.Type.CHANNEL_DATA, 0xFF), 0))
        assertFalse("the offline queue replayed", traits.heardLive(packet(MeshcorePacket.Type.CHANNEL_DATA, 0, live = false), 0))
        assertFalse("a sender prefix is a claim", traits.heardLive(packet(MeshcorePacket.Type.CONTACT_TEXT, 0xFF), 0))
    }

    @Test
    fun everyRefusalReadsThroughTheSessionsClassifier() {
        assertEquals(RefusalClass.OWN_NO_KEY, traits.refusalOf(SendResult.Rejected(1u, MeshcoreRefusal.NO_RECORD)))
        assertEquals(RefusalClass.OTHER, traits.refusalOf(PacketOutcome.Refused(1u, RoutingError.NO_CHANNEL, byDestination = true)))
        assertEquals(null, traits.refusalOf(SendResult.Busy))
    }

    @Test
    fun theBandsDutyIsTheAllowancesAndNoDedicatedSlot() {
        assertEquals(10.0, MeshcoreTraits.dutyPolicy(869_618, 62_500).dutyCyclePercent, 0.0)
        assertEquals(1.0, MeshcoreTraits.dutyPolicy(868_100, 125_000).dutyCyclePercent, 0.0)
        assertEquals(LoraBand.STRICTEST_PERCENT, MeshcoreTraits.dutyPolicy(869_300, 62_500).dutyCyclePercent, 0.0)
        assertFalse(MeshcoreTraits.dutyPolicy(910_525, 62_500).dedicatedSlot)
        // The allowance on the strictest band refuses even one compact DM's three airings: nothing waits on it.
        val air =
            LoraAirtime(
                traits = MeshcoreTraits,
            ).apply { onRadio(RadioParams(7, 62_500, 5), MeshcoreTraits.dutyPolicy(868_900, 62_500)) }
        val price = air.price(AirPurpose.DIRECT_DM)
        assertEquals(LoraAirtime.Refusal.NEVER, air.refusal(Ceiling.FULL, List(3) { 120 }, 0, price))
    }
}
