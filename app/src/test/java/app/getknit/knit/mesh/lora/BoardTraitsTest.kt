package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.LORA_DIRECT_PLAINTEXT_BYTES
import app.getknit.knit.mesh.LORA_DIRECT_TICK_ACKS
import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.MessageContentV2
import app.getknit.knit.mesh.crypto.ReadMask
import app.getknit.knit.mesh.protocol.FrameId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The board-kind seams of ADR 2026-11.gsqu's Stage 1a: the Meshtastic traits are the constants they always were, the
 * compact budget and its tick count are derived from them, and one classifier says what every refusal means.
 */
class BoardTraitsTest {
    private val traits = BoardTraits.MESHTASTIC

    @Test
    fun meshtasticTraitsAreTheConstantsTheyReplace() {
        assertEquals(LoraMeshTransport.PRE_READY_PAYLOAD, traits.floorPayload)
        assertEquals(MeshtasticProto.MAX_PAYLOAD, traits.maxPayload)
        assertEquals(LoraDirectPolicy.AIRINGS, traits.airings)
        assertEquals(LoraDirectPolicy.MAX_FLIGHTS, traits.maxFlights)
        assertEquals(LoraDirectPolicy.CW_MIN, traits.cwMin)
        assertEquals(LoraDirectPolicy.CW_MAX, traits.cwMax)
        assertEquals(LoraDirectPolicy.ACK_PAYLOAD_BYTES, traits.ackPayloadBytes)
        assertEquals(24, traits.packetOverheadBytes)
        assertEquals(20.25, traits.preambleSymbols, 0.0)
        assertEquals(3_000L, traits.minGapMs)
        assertEquals("an own room post rides in one packet (ADR 2026-10.rp3s)", 1, traits.roomPartLimit)
        for (payload in listOf(64, 200, 220, 228, 231)) {
            assertEquals(LoraDirectPolicy.payloadCap(payload), traits.directPayloadCap(payload))
        }
        for (air in listOf(100L, 1_665L, 2_156L)) {
            for (slot in listOf(16.8, 44.464)) {
                assertEquals(LoraDirectPolicy.deadlineMs(air, slot), traits.deadlineMs(air, slot))
                for (cw in LoraDirectPolicy.CW_MIN..LoraDirectPolicy.CW_MAX) {
                    assertEquals(LoraDirectPolicy.retransmitMs(air, slot, cw), traits.retransmitMs(air, slot, cw))
                }
            }
        }
        for (elapsed in listOf(0L, 7_000L, 40_000L, 400_000L)) {
            assertEquals(LoraDirectPolicy.retriesBefore(elapsed, 9_000L), traits.retriesBefore(elapsed, 9_000L))
        }
    }

    @Test
    fun theContentionSlotIsTheFirmwaresAtEveryPreset() {
        val longFast = traits.slotTimeMs(MeshtasticTraits.radioParams(ModemPreset.LONG_FAST))
        assertEquals("≈ 44.5 ms at LongFast", 44.464, longFast, 1e-9)
        // SF12 / 125 kHz: a 32.768 ms symbol, so 4.5 × 32.768 + 7.6 by the firmware's `computeSlotTimeMsec`.
        val longSlow = traits.slotTimeMs(MeshtasticTraits.radioParams(ModemPreset.LONG_SLOW))
        assertEquals("≈ 155 ms at LongSlow", 155.056, longSlow, 1e-9)
    }

    @Test
    fun theDutyIsTheRegionsNeverABandsAndTheOverrideLiftsIt() {
        for (region in LoraRegion.entries) {
            val duty = MeshtasticTraits.dutyPolicy(LoraRadioConfig(true, ModemPreset.LONG_FAST, region, 3, false))
            assertEquals(region.dutyCyclePercent, duty.dutyCyclePercent, 0.0)
            assertFalse(duty.dedicatedSlot)
            assertEquals(
                100.0,
                MeshtasticTraits.dutyPolicy(LoraRadioConfig(true, ModemPreset.LONG_FAST, region, 3, true)).dutyCyclePercent,
                0.0,
            )
        }
        val slot = checkNotNull(LoraSlot.forRegion(LoraRegion.US, ModemPreset.LONG_FAST))
        assertTrue(MeshtasticTraits.dutyPolicy(LoraRadioConfig(true, ModemPreset.LONG_FAST, LoraRegion.US, 3, false, slot)).dedicatedSlot)
    }

    @Test
    fun meshtasticsCompactBudgetIsTheManagersOneHundredSixtyEightAndNine() {
        assertEquals(LoraDirectBudget(168, 9), traits.directBudget())
        assertEquals(LoraDirectBudget(168, 9), LoraDirectBudget.MESHTASTIC)
        assertEquals(168, LORA_DIRECT_PLAINTEXT_BYTES)
        assertEquals(9, LORA_DIRECT_TICK_ACKS)
        assertEquals(traits.directPayloadCap(traits.floorPayload) - LORA_DIRECT_OVERHEAD, traits.directBudget().plaintextBytes)
    }

    /** For each budget, the derived count is the largest whose tick fits, encoded as the size budget test encodes one. */
    @Test
    fun theTickCountIsTheLargestThatFitsAtEveryBudget() {
        for (budget in 0..320) {
            val n = compactTickAcks(budget)
            if (n > 0) assertTrue("$n ids fit $budget B", tick(n) <= budget)
            assertTrue("${n + 1} ids do not fit $budget B", tick(n + 1) > budget)
            assertEquals(n, LoraDirectBudget.forPlaintext(budget).tickAcks)
        }
    }

    @Test
    fun theDefaultSinkBudgetIsMeshtasticsAndEverySourceTakesTheBudget() =
        kotlinx.coroutines.test.runTest {
            val sink =
                object : LoraDirectSink {
                    override fun offer(
                        recipientId: String,
                        frameId: String,
                        sentAtMs: Long,
                        plaintext: ByteArray,
                        kind: DirectKind,
                    ) = Unit
                }
            assertEquals(LoraDirectBudget(168, 9), sink.budgetFor("bob"))
            // Stage 5c-1 closed the gap a defaulted four-argument form left (a forward sized every board at its own 168 B):
            // a source must take the budget, and the three-argument reader asks it at Meshtastic's.
            val asked = mutableListOf<Int>()
            val source =
                object : LoraDirectSource {
                    override suspend fun unackedTo(
                        peerId: String,
                        limit: Int,
                        untakenOnly: Boolean,
                        maxBytes: Int,
                    ): List<DirectDm> {
                        asked += maxBytes
                        return emptyList()
                    }

                    override suspend fun isUnacked(frameId: String) = false
                }
            source.unackedTo("bob", 3, untakenOnly = true, maxBytes = 105)
            source.unackedTo("bob", 3)
            assertEquals("the plane's budget, then Meshtastic's", listOf(105, LoraDirectBudget.MESHTASTIC.plaintextBytes), asked)
        }

    @Test
    fun everyRefusalHasOneMeaning() {
        for (reason in RoutingError.entries) {
            for (byDestination in listOf(false, true)) {
                val expected =
                    when {
                        reason == RoutingError.PKI_SEND_FAIL_PUBLIC_KEY && !byDestination -> RefusalClass.OWN_NO_KEY
                        reason == RoutingError.PKI_UNKNOWN_PUBKEY && byDestination -> RefusalClass.PEER_LACKS_KEY
                        byDestination -> RefusalClass.DEST_REFUSED
                        reason == RoutingError.DUTY_CYCLE_LIMIT || reason == RoutingError.RATE_LIMIT_EXCEEDED -> RefusalClass.PACE
                        else -> RefusalClass.OTHER
                    }
                assertEquals(
                    "$reason byDestination=$byDestination",
                    expected,
                    traits.refusalOf(PacketOutcome.Refused(1u, reason, byDestination)),
                )
                if (!byDestination) assertEquals(expected, traits.refusalOf(SendResult.Nak(1u, reason)))
            }
        }
        assertEquals("our board's 39 on the queueStatus", RefusalClass.OWN_NO_KEY, traits.refusalOf(SendResult.Rejected(1u, 39)))
        assertEquals(RefusalClass.OTHER, traits.refusalOf(SendResult.Rejected(1u, 7)))
        val queue = QueueInfo(free = 3, maxlen = 16, atMs = 0)
        for (result in listOf(
            SendResult.Queued(1u, queue),
            SendResult.Busy,
            SendResult.TooLarge,
            SendResult.Timeout(1u),
            SendResult.NotReady(LinkState.Idle),
        )) {
            assertNull("$result refused nothing", traits.refusalOf(result))
        }
    }

    @Test
    fun onlyPaceRefusalsBackThePacerOff() {
        for (reason in RoutingError.entries) {
            val pace = LoraPacePolicy(minGapMs = 0)
            pace.onNak(reason, now = 1_000)
            val paced = reason == RoutingError.DUTY_CYCLE_LIMIT || reason == RoutingError.RATE_LIMIT_EXCEEDED
            assertEquals("$reason", if (paced) 61_000L else 0L, pace.nextDueAt())
        }
        val pace = LoraPacePolicy(minGapMs = 0)
        pace.onPaceRefusal(now = 5_000)
        assertEquals(65_000L, pace.nextDueAt())
    }

    @Test
    fun aHearingIsLiveOnlyWithAnRssiOffTheAirAndFresh() {
        val now = 10_000_000L

        fun packet(
            rssi: Int?,
            rxTime: UInt? = null,
            mqtt: Boolean = false,
        ): BoardPacket {
            val raw =
                ReceivedPacket(
                    7u,
                    9u,
                    1u,
                    1,
                    MeshtasticProto.PORT_PRIVATE_APP,
                    byteArrayOf(1),
                    5f,
                    rssi,
                    0,
                    viaMqtt = mqtt,
                    rxTime = rxTime,
                )
            return BoardPacket(BoardAddr(7u), false, 1, raw.payload, 5f, rssi, 0, rxTime?.let { it.toLong() * 1_000 }, raw)
        }
        assertTrue(traits.heardLive(packet(rssi = -90), now))
        assertFalse("the NodeDB replay has no RSSI", traits.heardLive(packet(rssi = null), now))
        assertFalse(traits.heardLive(packet(rssi = 0), now))
        assertFalse("off MQTT", traits.heardLive(packet(rssi = -90, mqtt = true), now))
        val fresh = ((now - LoraMeshTransport.HEARING_MAX_AGE_MS) / 1_000).toUInt()
        assertTrue(traits.heardLive(packet(rssi = -90, rxTime = fresh), now))
        assertFalse("the to-phone queue replayed", traits.heardLive(packet(rssi = -90, rxTime = fresh - 1u), now))
    }

    @Test
    fun aFrameIsAdmittedAndCarriesTheAiringsItsKindGaveIt() {
        val send = DirectSend("bob", BoardAddr(2u), "f", DirectKind.MESSAGE)
        assertEquals(LoraDirectPolicy.AIRINGS, send.airings)
        val frame = OutboundFrame(listOf(ByteArray(120)), "dm", FrameClass.DIRECT, direct = send)
        assertEquals(List(LoraDirectPolicy.AIRINGS) { 120 }, frame.admissionSizes())
        val twice =
            OutboundFrame(
                listOf(ByteArray(120)),
                "dm",
                FrameClass.DIRECT,
                direct = DirectSend("bob", BoardAddr(2u), "f", DirectKind.MESSAGE, airings = 2),
            )
        assertEquals("admitted against the airings it carries", listOf(120, 120), twice.admissionSizes())
        assertEquals(listOf(120), OutboundFrame(listOf(ByteArray(120)), "room").admissionSizes())
    }

    @Test
    fun aFrameAddressedByNodeNumberIsTheSameFrameAddressedByBoard() {
        assertEquals(BoardAddr(9u), OutboundFrame(listOf(byteArrayOf(1)), "reply", destination = Destination.Reply, to = 9u).to)
        assertNull("the broadcast number is the channel", OutboundFrame(listOf(byteArrayOf(1)), "room", to = MeshtasticProto.BROADCAST).to)
        assertNull(OutboundFrame(listOf(byteArrayOf(1)), "room").to)
    }

    // A tick toward an author who reads a mask (ADR 2026-11.z6jw) carries one whose width the id count alone sets.
    private fun tick(ids: Int): Int {
        val acks = List(ids) { FrameId.new() }
        val rm = ReadMask.encode(acks, emptySet())
        val content =
            if (ids == 1) {
                MessageContent(body = "", ctl = MessageContent.CTL_RECEIPT, ack = acks.single(), rm = rm)
            } else {
                MessageContent(body = "", ctl = MessageContent.CTL_RECEIPT, acks = acks, rm = rm)
            }
        return checkNotNull(MessageContentV2.encodeOrNull(content)).size
    }
}
