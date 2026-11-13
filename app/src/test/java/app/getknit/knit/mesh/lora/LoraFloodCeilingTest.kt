package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.lora.meshcore.MeshcoreProto
import app.getknit.knit.mesh.lora.meshcore.MeshcoreTraits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [Ceiling.FLOOD] (ADR 2026-11.gsqu §4, the maintainer's Stage 5 decisions, built in Stage 5a-1): 0.15 of the allowance,
 * counted over flood bookings alone, under the one total; a frame over its own ceiling on an empty window is
 * [LoraAirtime.Refusal.NEVER], dropped by the pacer and never waited on.
 */
class LoraFloodCeilingTest {
    /** A MeshCore ledger on the US preset at 10 % politeness: a 45 s allowance and a 6.75 s flood ceiling. */
    private fun ledger(dutyKhz: Long = 910_525): LoraAirtime =
        LoraAirtime(traits = MeshcoreTraits).apply { onRadio(RadioParams(7, 62_500, 5), MeshcoreTraits.dutyPolicy(dutyKhz, 62_500)) }

    private val post = listOf(MeshcoreProto.CHANNEL_DATA_CAP)

    @Test
    fun theFloodCeilingCountsFloodsAloneUnderTheOneTotal() {
        val air = ledger()
        assertEquals(45_000L, air.allowanceMs())
        assertEquals(6_750L, air.floodCeilingMs())
        val flood = air.price(AirPurpose.ROOM_FLOOD)
        val dm = air.price(AirPurpose.DIRECT_DM)
        // DMs booked for every airing take half the window: the floods' share is untouched by them.
        var now = 0L
        while (air.usedMs(now) < air.allowanceMs() / 2) {
            air.record(150, now, dm, Ceiling.FULL)
            now += 100
        }
        assertNull("the floods have spent nothing yet", air.refusal(Ceiling.FLOOD, post, now, flood))
        // Floods up to their ceiling: the next is refused on it, and holds nothing.
        while (air.refusal(Ceiling.FLOOD, post, now, flood) == null) {
            air.record(post.single(), now, flood, Ceiling.FLOOD)
            now += 100
        }
        assertEquals(LoraAirtime.Refusal.CEILING, air.refusal(Ceiling.FLOOD, post, now, flood))
        assertTrue(air.snapshot(now).floodUsedMs <= air.floodCeilingMs())
        assertNull("a DM still has the rest of the total", air.refusal(Ceiling.FULL, List(3) { 150 }, now, dm))
    }

    @Test
    fun theTotalStillCapsAFloodWhoseShareIsEmpty() {
        val air = ledger()
        val flood = air.price(AirPurpose.ROOM_FLOOD)
        val dm = air.price(AirPurpose.DIRECT_DM)
        var now = 0L
        while (air.refusal(Ceiling.FULL, listOf(150), now, dm) == null) {
            air.record(150, now, dm, Ceiling.FULL)
            now += 100
        }
        assertEquals(0L, air.snapshot(now).floodUsedMs)
        assertEquals(LoraAirtime.Refusal.TOTAL, air.refusal(Ceiling.FLOOD, post, now, flood))
    }

    @Test
    fun aFrameOverItsOwnCeilingOnAnEmptyWindowIsNever() {
        // A 1 % band: a 4.5 s allowance and a 0.675 s flood ceiling. A two-part post (two ~0.4 s packets at SF7) fits the
        // allowance and never the flood ceiling: NEVER, judged against its own ceiling, not the whole allowance.
        val air = ledger(dutyKhz = 868_300)
        val flood = air.price(AirPurpose.ROOM_FLOOD)
        val twoParts = List(2) { MeshcoreProto.CHANNEL_DATA_CAP }
        val cost = twoParts.sumOf { air.timeOnAirMs(it, flood) }
        assertTrue("under the allowance: $cost of ${air.allowanceMs()}", cost < air.allowanceMs())
        assertTrue("over the flood ceiling: $cost of ${air.floodCeilingMs()}", cost > air.floodCeilingMs())
        assertEquals(LoraAirtime.Refusal.NEVER, air.refusal(Ceiling.FLOOD, twoParts, 0, flood))
        assertNull("the same packets as a Knit frame at the full ceiling fit", air.refusal(Ceiling.FULL, twoParts, 0, flood))
    }

    @Test
    fun aFloodBookingRolledOffOrRefundedLeavesTheFloodsShare() {
        val air = ledger()
        val flood = air.price(AirPurpose.ROOM_FLOOD)
        air.record(100, 0, flood, Ceiling.FLOOD)
        val ms = air.timeOnAirMs(100, flood)
        assertEquals(ms, air.snapshot(0).floodUsedMs)
        assertEquals(1, air.refund(0, ms, 1))
        assertEquals(0L, air.snapshot(0).floodUsedMs)
        air.record(100, 1_000, flood, Ceiling.FLOOD)
        assertEquals(0L, air.snapshot(1_000 + LoraAirtime.WINDOW_MS).floodUsedMs)
        assertEquals(0L, air.usedMs(1_000 + LoraAirtime.WINDOW_MS))
    }

    @Test
    fun thePacerDropsANeverFrameAndIsNotLeftDueOnItsIdleTick() {
        val air = ledger(dutyKhz = 868_300)
        val pace = LoraPacePolicy(minGapMs = 0, airtime = air)
        val twoParts = List(2) { ByteArray(MeshcoreProto.CHANNEL_DATA_CAP) }
        pace.enqueue(OutboundFrame(twoParts, "room:long", FrameClass.ROOM, Ceiling.FLOOD))
        assertNull(pace.take(1_000))
        assertEquals("dropped, not waited on", 0, pace.pending)
        assertEquals(listOf("room:long"), pace.drainNever().map { it.label })
        assertTrue("handed out once", pace.drainNever().isEmpty())
        assertEquals("no air deferral to wake for", 0L, pace.nextDueAt())
    }

    @Test
    fun aNeverFrameBesideAFloodTheCeilingHoldsLeavesThePacerAsleepToTheNextRelease() {
        val air = ledger(dutyKhz = 868_300)
        val pace = LoraPacePolicy(minGapMs = 0, airtime = air)
        val flood = air.price(AirPurpose.ROOM_FLOOD)
        air.record(MeshcoreProto.CHANNEL_DATA_CAP, 0, flood, Ceiling.FLOOD) // one part spent of a ceiling that holds one
        pace.enqueue(OutboundFrame(List(2) { ByteArray(MeshcoreProto.CHANNEL_DATA_CAP) }, "room:long", FrameClass.ROOM, Ceiling.FLOOD))
        pace.enqueue(OutboundFrame(listOf(ByteArray(MeshcoreProto.CHANNEL_DATA_CAP)), "room:short", FrameClass.ROOM, Ceiling.FLOOD))
        pace.enqueue(
            OutboundFrame(
                listOf(ByteArray(100)),
                "dm",
                FrameClass.DIRECT,
                direct = DirectSend("bob", BoardAddr(2u), "f", DirectKind.MESSAGE, airings = 1),
            ),
        )
        assertEquals("the DM goes; the floods wait or drop", "dm", pace.take(1_000)?.label)
        assertEquals(listOf("room:long"), pace.drainNever().map { it.label })
        assertNull(pace.take(2_000))
        assertEquals("the short post waits for the flood booking to roll off", air.nextReleaseAt(2_000), pace.nextDueAt())
        assertEquals(1, pace.pending)
    }

    /**
     * Before a board reports its band the allowance is MeshCore's 0.1 % fallback, under which a one-part post and a
     * three-airing DM read NEVER though the real band carries them: they are skipped, never dropped, until the duty lands.
     */
    @Test
    fun nothingIsDroppedForNeverBeforeTheDutyIsKnown() {
        val air = LoraAirtime(traits = MeshcoreTraits)
        val pace = LoraPacePolicy(minGapMs = 0, airtime = air)
        val post = OutboundFrame(listOf(ByteArray(MeshcoreProto.CHANNEL_DATA_CAP)), "room", FrameClass.ROOM, Ceiling.FLOOD)
        val dm =
            OutboundFrame(
                listOf(ByteArray(150)),
                "dm",
                FrameClass.DIRECT,
                direct = DirectSend("bob", BoardAddr(2u), "f", DirectKind.MESSAGE, airings = 3),
            )
        pace.enqueue(post)
        pace.enqueue(dm)
        assertEquals(LoraAirtime.Refusal.NEVER, air.refusal(Ceiling.FLOOD, post.admissionSizes(), 0, post.price(air)))
        assertEquals(LoraAirtime.Refusal.NEVER, air.refusal(Ceiling.FULL, dm.admissionSizes(), 0, dm.price(air)))
        assertNull(pace.take(1_000))
        assertEquals("skipped, not dropped", 2, pace.pending)
        assertTrue(pace.drainNever().isEmpty())
        air.onRadio(RadioParams(8, 62_500, 5), MeshcoreTraits.dutyPolicy(869_618, 62_500))
        assertEquals("the band carries the DM first", "dm", pace.take(2_000)?.label)
        assertEquals("then the post", "room", pace.take(3_000)?.label)
        assertTrue(pace.drainNever().isEmpty())
    }

    /**
     * A Meshtastic-room post over half the allowance reads NEVER against its own (half) ceiling since ADR 2026-11.gsqu's
     * Stage 5a-1 amendment: skipped while the duty is unknown, dropped into [LoraPacePolicy.drainNever] once it is.
     */
    @Test
    fun aRoomPostOverTheHalfCeilingIsSkippedUntilTheDutyIsKnownThenDropped() {
        val air = LoraAirtime(windowMs = 60_000)
        val pace = LoraPacePolicy(minGapMs = 0, airtime = air)
        val post =
            OutboundFrame(listOf(ByteArray(MeshtasticProto.MAX_PAYLOAD)), "public", FrameClass.ROOM, Ceiling.HALF, Destination.Public)
        pace.enqueue(post)
        assertNull(pace.take(1_000))
        assertEquals("skipped while the duty is unknown", 1, pace.pending)
        assertTrue(pace.drainNever().isEmpty())
        air.onRadioConfig(LoraRadioConfig(true, ModemPreset.LONG_FAST, LoraRegion.US, 3, false))
        val cost = air.timeOnAirMs(MeshtasticProto.MAX_PAYLOAD, post.price(air))
        assertTrue(
            "over half ($cost of ${air.publicCeilingMs()}), within the whole",
            cost > air.publicCeilingMs() && cost <= air.allowanceMs(),
        )
        assertNull(pace.take(2_000))
        assertEquals("dropped once the duty is known", 0, pace.pending)
        assertEquals(listOf("public"), pace.drainNever().map { it.label })
    }

    @Test
    fun meshtasticNeverUsesTheFloodCeiling() {
        assertEquals(Ceiling.FULL, MeshtasticTraits.roomCeiling)
        assertEquals(Ceiling.FLOOD, MeshcoreTraits.roomCeiling)
        assertEquals(0.15, LoraAirtime.FLOOD_CEILING, 0.0)
    }
}
