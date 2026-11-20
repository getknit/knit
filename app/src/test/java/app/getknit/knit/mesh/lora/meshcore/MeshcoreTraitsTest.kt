package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.lora.AirPurpose
import app.getknit.knit.mesh.lora.BoardKind
import app.getknit.knit.mesh.lora.BoardPacket
import app.getknit.knit.mesh.lora.BoardTraits
import app.getknit.knit.mesh.lora.Ceiling
import app.getknit.knit.mesh.lora.Destination
import app.getknit.knit.mesh.lora.LoraAirtime
import app.getknit.knit.mesh.lora.LoraBand
import app.getknit.knit.mesh.lora.OutboundFrame
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
        assertEquals("a plain text's multi-ack: its count and 6-B code", 2 + 7, phy(AirPurpose.ACK, MeshcoreTraits.ackPayloadBytes))
        // At most one multi-ack whatever `multi_acks` reads, then the ack: 6 B for a plain text, 4 for a signed one.
        assertEquals(listOf(7, 6), MeshcoreTraits.boardAckPayloads(3, MeshcoreProto.TXT_TYPE_PLAIN))
        assertEquals(listOf(5, 4), MeshcoreTraits.boardAckPayloads(1, MeshcoreProto.TXT_TYPE_SIGNED_PLAIN))
        assertEquals(listOf(6), MeshcoreTraits.boardAckPayloads(0, MeshcoreProto.TXT_TYPE_PLAIN))
        assertEquals("a flooded ack carries the transport codes", 1 + 4 + 1 + 6, MeshcoreTraits.ackPhyBytes(6, flooded = true))
        assertEquals(2 + 6, MeshcoreTraits.ackPhyBytes(6, flooded = false))
        // A public post and a room flood share the flood's head; a DM's is the 2-B direct head, its 4 codes never added.
        assertEquals(1 + 4 + 1 + 1 + 2, MeshcoreTraits.packetOverheadBytes)
    }

    @Test
    fun aPathReturnFloodsAndAPublicPostToo() {
        val signed = MeshcoreProto.TXT_TYPE_SIGNED_PLAIN
        val plain = MeshcoreProto.TXT_TYPE_PLAIN
        // createPathReturn at zero hops: path length + extra type + the 4-B signed ack = 6 B of plaintext, one block.
        assertEquals(6, MeshcoreTraits.pathReturnPlaintext(0, signed))
        assertEquals("a plain text's 6-B ack", 8, MeshcoreTraits.pathReturnPlaintext(0, plain))
        assertEquals(1 + 4 + 1 + 2 + 2 + 16, phy(AirPurpose.PATH_RETURN, MeshcoreTraits.pathReturnPlaintext(0, signed)))
        assertEquals(
            "ten more hashes: still one block",
            1 + 4 + 1 + 2 + 2 + 16,
            phy(AirPurpose.PATH_RETURN, MeshcoreTraits.pathReturnPlaintext(10, signed)),
        )
        assertEquals("eleven: two", 1 + 4 + 1 + 2 + 2 + 32, phy(AirPurpose.PATH_RETURN, MeshcoreTraits.pathReturnPlaintext(11, signed)))
        // The hash size rides path_len's top bits: three 2-byte hashes are six path bytes.
        assertEquals(1 + 6 + 1 + 6, MeshcoreTraits.pathReturnPlaintext((1 shl 6) or 3, plain))
        // Stage 6: the board writes its `<name>: ` in front of the text (BaseChatMesh.cpp:523), priced too: 11 B under Knit's
        // default name, the name's own length since ADR 2026-11.f4d3.
        assertEquals("GRP_TXT: timestamp + flags + prefix + 11 B = 27, two blocks, flooded", 9 + 32, publicPhy(9, 11))
        assertEquals("5 + 11 + 16 = 32: still two blocks", 9 + 32, publicPhy(9, 16))
        assertEquals("the largest post, 149 B: 165 of plaintext in 176 of AES, 185 on the air", 185, publicPhy(9, 149))
        assertEquals("no name known: the longest name's price, 5 + 33 + 1 = 39, three blocks", 9 + 48, phy(AirPurpose.PUBLIC_POST, 1))
    }

    private fun publicPhy(
        nameBytes: Int?,
        payload: Int,
    ): Int = traits.phyBytes(payload, traits.publicPrice(traits.price(AirPurpose.PUBLIC_POST, SigningFacts(), false, false), nameBytes))

    /**
     * ADR 2026-11.f4d3: the prefix is the board's name and `": "`, so the cap and the price follow the name's length — at 1, 9
     * (`Knit abcd`) and 31 B (the firmware's longest) — and the largest post is 185 B on the air whatever the name. The cap is
     * the encoder's own at that name; no name known prices the longest, the composer's floor of 127.
     */
    @Test
    fun aPublicPostsPrefixCapAndPriceFollowTheBoardsNameLength() {
        for ((nameBytes, cap) in listOf(1 to 157, 9 to 149, 31 to 127)) {
            assertEquals("prefix at $nameBytes B", nameBytes + 2, MeshcoreTraits.publicPrefixBytes(nameBytes))
            assertEquals("cap at $nameBytes B", cap, MeshcoreTraits.publicTextCap(nameBytes))
            assertEquals("the encoder agrees at $nameBytes B", cap, MeshcoreProto.channelTextCap(ByteArray(nameBytes) { 0x41 }))
            val price = traits.publicPrice(traits.price(AirPurpose.PUBLIC_POST, SigningFacts(), false, false), nameBytes)
            assertEquals(cap, price.cap)
            assertEquals("the largest post at $nameBytes B", 185, publicPhy(nameBytes, cap))
            assertEquals("one byte of text at $nameBytes B", 9 + aes(5 + nameBytes + 2 + 1), publicPhy(nameBytes, 1))
        }
        assertEquals(127, MeshcoreTraits.PUBLIC_TEXT_CAP)
        assertEquals(MeshcoreTraits.publicTextCap(null), MeshcoreTraits.PUBLIC_TEXT_CAP)
        assertEquals(MeshcoreTraits.PUBLIC_TEXT_CAP, traits.price(AirPurpose.PUBLIC_POST, SigningFacts(), false, false).cap)
        assertEquals(
            "any other purpose is left as it is",
            traits.price(AirPurpose.ROOM_FLOOD, SigningFacts(), false, false),
            traits.publicPrice(traits.price(AirPurpose.ROOM_FLOOD, SigningFacts(), false, false), 9),
        )
    }

    private fun aes(plain: Int): Int = (plain + 15) / 16 * 16

    /**
     * A queued post is priced from the name its own frame carries, never the board's name now (ADR 2026-11.f4d3): a rename
     * while it waits moves the ledger's live name, and admission and the booking at `onTaken` still read one figure.
     */
    @Test
    fun aQueuedPostIsPricedFromItsOwnNameWhateverTheBoardIsCalledNow() {
        val air = LoraAirtime(traits = MeshcoreTraits)
        air.onRadio(RadioParams(8, 62_500, 5), MeshcoreTraits.dutyPolicy(869_525, 62_500))
        val long = MeshcoreHandle(ByteArray(31) { 0x41 })
        val frame =
            OutboundFrame(
                listOf(ByteArray(100) { 0x42 }),
                "public",
                ceiling = Ceiling.FLOOD,
                destination = Destination.Public,
                timestampSecs = 1L,
                publicName = long,
            )
        air.onPublicName(9)
        val admitted = frame.price(air)
        assertEquals("the frame's own name, not the board's new one", air.publicPrice(31), admitted)
        assertTrue("a longer name costs more", air.timeOnAirMs(100, admitted) > air.timeOnAirMs(100, air.publicPrice(9)))
        air.onPublicName(1)
        assertEquals("booking reads what admission read", admitted, frame.price(air))
        air.record(100, 0, frame.price(air), frame.ceiling)
        assertEquals(air.timeOnAirMs(100, admitted), air.snapshot(0).floodUsedMs)
    }

    /**
     * Stage 6 (lora B1 / N3): under Knit's default name the prefix is 11 B and the cap 160 less it, the encoder's own refusal
     * at that name; admitted under the flood ceiling, never `HALF`.
     */
    @Test
    fun aPublicPostIsPricedWithTheKnitPrefixCappedAt149AndAdmittedAsAFlood() {
        val name = MeshcoreNames.forKey(ByteArray(32) { 0x5A })
        assertEquals(MeshcoreTraits.publicPrefixBytes(9), "$name: ".encodeToByteArray().size)
        assertEquals(149, MeshcoreTraits.publicTextCap(9))
        assertEquals(
            "the encoder's own cap at Knit's name",
            MeshcoreTraits.publicTextCap(9),
            MeshcoreProto.channelTextCap(name.encodeToByteArray()),
        )
        assertEquals(
            "a room flood keeps channel data's cap",
            MeshcoreProto.CHANNEL_DATA_CAP,
            traits.price(AirPurpose.ROOM_FLOOD, SigningFacts(), false, false).cap,
        )
        assertEquals(Ceiling.FLOOD, MeshcoreTraits.publicCeiling)
        assertEquals("the Meshtastic room keeps its half", Ceiling.HALF, BoardTraits.MESHTASTIC.publicCeiling)
    }

    /**
     * What a Public post can ever carry ([LoraAirtime.publicPostBytes], the composer's budget): the whole 149 B on a 10 % band,
     * less on a 1 % one where the flood ceiling of an empty window cannot carry that much — the very `NEVER` the enqueue asks —
     * and the cap while no duty is known.
     */
    @Test
    fun whatAPublicPostCanEverCarryIsTheCapLessWhatTheFloodCeilingNeverAdmits() {
        val air = LoraAirtime(traits = MeshcoreTraits)
        assertEquals("no name known: the floor under the longest name", 127, air.publicPostBytes())
        air.onPublicName(9)
        assertEquals("no duty known: the cap", 149, air.publicPostBytes())
        val radio = RadioParams(8, 62_500, 5)
        air.onRadio(radio, MeshcoreTraits.dutyPolicy(869_525, 62_500))
        assertEquals("a 10 % band carries the whole post", 149, air.publicPostBytes())
        assertEquals(149, air.snapshot(0).publicPostBytes)
        air.onPublicName(31)
        assertEquals("a 31-B name leaves 127", 127, air.snapshot(0).publicPostBytes)
        air.onPublicName(9)
        air.onRadio(radio, MeshcoreTraits.dutyPolicy(868_100, 62_500))
        val fits = air.publicPostBytes()
        assertTrue("a 1 % band carries less: $fits", fits in 1 until 149)
        val price = air.publicPrice(9)
        assertEquals(null, air.refusal(Ceiling.FLOOD, listOf(fits), 0, price))
        assertEquals(LoraAirtime.Refusal.NEVER, air.refusal(Ceiling.FLOOD, listOf(fits + 1), 0, price))
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
        // Two elided packets since Stage 5c-1 brought the reassembler a two-part post on the public key needs (5a-2 staged one).
        assertEquals(2, MeshcoreTraits.ROOM_PART_LIMIT)
        assertEquals(2, traits.roomPartLimit)
        assertTrue("a MeshCore room post elides", traits.elidesRoom)
        assertEquals(0L, traits.minGapMs)
        assertEquals(MeshcoreProto.DIRECT_SEAL_CAP, traits.directPayloadCap(255))
        assertEquals(MeshcoreProto.CHANNEL_DATA_CAP, traits.floorPayload)
        for (air in listOf(1L, 300L, 5_000L, 60_000L)) {
            assertTrue("the backstop never under the session's own cap", traits.deadlineMs(air, 4.0) >= MeshcoreSession.MAX_FLIGHT_MS)
            assertEquals(2, traits.retriesBefore(3_600_000L, traits.retransmitMs(air, 4.0, 0)))
        }
        // The direct est_timeout at zero hops (500 + 6 × air + 250) and the session's retry slack: the core's fallback guess.
        assertEquals(500L + 6 * 300 + 250 + 1_000, traits.retransmitMs(300, 4.0, 0))
    }

    @Test
    fun aRoutedDmIsPricedByItsOwnPathAndAFloodByItsTransportCodes() {
        // ADR 2026-11.gsqu's 2026-11-19 amendment: our own board's airing only, never a repeater's.
        val zero = MeshcoreTraits.contactTextPhyBytes(40, flooded = false)
        assertEquals("the same packet as the price books at send", phy(AirPurpose.DIRECT_DM, 39), zero)
        assertEquals(
            "three one-byte hops ride the packet",
            zero + 3,
            MeshcoreTraits.contactTextPhyBytes(40, flooded = false, pathBytes = 3),
        )
        assertEquals("a flood: the transport codes, no path", zero + 4, MeshcoreTraits.contactTextPhyBytes(40, flooded = true))
        assertEquals(2 + 2 + 6, MeshcoreTraits.ackPhyBytes(6, flooded = false, pathBytes = 2))
        assertEquals(
            "a flooded ack carries no path as our board airs it",
            6 + 6,
            MeshcoreTraits.ackPhyBytes(6, flooded = true, pathBytes = 2),
        )
        // The reciprocal PATH: header, path length, the route, two hashes, MAC, AES over path length + path + extra type.
        assertEquals(2 + 2 + 2 + 2 + 16, MeshcoreTraits.reciprocalPathPhyBytes(2))
        assertEquals(2, MeshcoreTraits.pathBytesOf(2))
        assertEquals("two 2-byte hashes", 4, MeshcoreTraits.pathBytesOf(0x42))
        assertEquals(2, MeshcoreTraits.hashSizeOf(0x42))
    }

    @Test
    fun aDirectSendsHopsComeBackOutOfItsEstTimeout() {
        // `calcDirectTimeoutMillisFor` (MyMesh.cpp:891): 500 + (6 × air + 250) × (hops + 1), air by `getEstAirtimeFor`.
        val radio = RadioParams(spreadFactor = 8, bandwidthHz = 62_500, codingRate = 5)
        for (hops in 0..5) {
            val air =
                LoraAirtime.formulaMs(
                    MeshcoreTraits.contactTextPhyBytes(60, flooded = false, pathBytes = hops),
                    radio,
                    MeshcoreTraits.preambleSymbolsAt(8),
                )
            val est = 500 + (6 * air + 250) * (hops + 1)
            assertEquals(hops, MeshcoreTraits.directHopsOf(est, 60, radio))
            assertEquals("a few ms off the firmware's integer airtime still reads", hops, MeshcoreTraits.directHopsOf(est + 40, 60, radio))
        }
        assertEquals("far from every hop count", null, MeshcoreTraits.directHopsOf(500 + (6 * 400 + 250) / 2, 60, radio))
    }

    @Test
    fun aHearingIsALiveZeroHopReceptionAndNeverKeyedOnTheSender() {
        fun packet(
            type: MeshcorePacket.Type,
            pathLen: Int,
            live: Boolean = true,
            route: Int? = null,
        ) = BoardPacket(
            from = null,
            toUs = false,
            channel = 1,
            payload = byteArrayOf(1),
            rxSnr = null,
            rxRssi = null,
            hopsAway = null,
            rxTimeMs = null,
            kind = MeshcorePacket(type, 1, pathLen, null, null, null, live, cobs = false, text = null, senderRoute = route),
        )
        assertTrue(traits.heardLive(packet(MeshcorePacket.Type.CHANNEL_DATA, 0), 0))
        assertFalse("through a repeater", traits.heardLive(packet(MeshcorePacket.Type.CHANNEL_DATA, 1), 0))
        assertFalse("0xFF reads direct through a repeater too", traits.heardLive(packet(MeshcorePacket.Type.CHANNEL_DATA, 0xFF), 0))
        assertFalse("the offline queue replayed", traits.heardLive(packet(MeshcorePacket.Type.CHANNEL_DATA, 0, live = false), 0))
        // Live is any path (the cover's lift, #294); a hearing also needs zero hops.
        assertTrue(traits.live(packet(MeshcorePacket.Type.CHANNEL_DATA, 2), 0))
        assertFalse(traits.live(packet(MeshcorePacket.Type.CHANNEL_DATA, 0, live = false), 0))
        // A direct DM lands 0xFF on any path (ADR 2026-11.gsqu's 2026-11-19 amendment), and our record of its sender is our own
        // outbound route, a zero there perhaps our own write (lora NB4): never zero hops, whatever the record reads.
        assertFalse("no route read is not zero hops", traits.heardZeroHop(packet(MeshcorePacket.Type.CONTACT_TEXT, 0xFF)))
        assertFalse("nor is our own zero-hop record", traits.heardZeroHop(packet(MeshcorePacket.Type.CONTACT_TEXT, 0xFF, route = 0)))
        assertFalse("a learned path", traits.heardZeroHop(packet(MeshcorePacket.Type.CONTACT_TEXT, 0xFF, route = 2)))
        assertFalse("a reset route", traits.heardZeroHop(packet(MeshcorePacket.Type.CONTACT_TEXT, 0xFF, route = 0xFF)))
        assertTrue(traits.heardZeroHop(packet(MeshcorePacket.Type.CONTACT_TEXT, 0)))
        assertFalse(traits.heardZeroHop(packet(MeshcorePacket.Type.CONTACT_TEXT, 2)))
        assertFalse(traits.heardZeroHop(packet(MeshcorePacket.Type.CONTACT_TEXT, 0xFF, live = false, route = 0)))
        // What lifts a mark is any live hop count, a DM or a post alike; a replay never.
        assertTrue(traits.heardRoute(packet(MeshcorePacket.Type.CONTACT_TEXT, 0xFF)))
        assertTrue(traits.heardRoute(packet(MeshcorePacket.Type.CHANNEL_DATA, 3)))
        assertFalse(traits.heardRoute(packet(MeshcorePacket.Type.CHANNEL_DATA, 0, live = false)))
        assertTrue("Meshtastic reads no hop count", BoardTraits.MESHTASTIC.heardRoute(packet(MeshcorePacket.Type.CHANNEL_DATA, 3)))
        // Never keyed on the sender: a DM's prefix is a claim, channel data names none (the Stage 5 decisions).
        assertFalse("a sender prefix is a claim", traits.hearsSender)
        assertTrue(BoardTraits.MESHTASTIC.hearsSender)
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
