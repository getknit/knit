package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoraAirtimeTest {
    private fun radio(
        preset: ModemPreset = ModemPreset.LONG_FAST,
        region: LoraRegion = LoraRegion.OTHER,
        override: Boolean = false,
        channelNum: Int = 0,
    ) = LoraRadioConfig(
        usePreset = true,
        modemPreset = preset,
        region = region,
        hopLimit = 3,
        overrideDutyCycle = override,
        channelNum = channelNum,
    )

    @Test
    fun aFullPacketAtLongFastIsAboutTwoSecondsOnAir() {
        val air = LoraAirtime()
        air.onRadioConfig(radio())
        // SF11 / BW250 / CR4-5, 233-byte payload + framing: the ~2 s figure the plane's docs quote.
        val ms = air.timeOnAirMs(MeshtasticProto.MAX_PAYLOAD)
        assertTrue("expected 1.8-2.6 s, got $ms ms", ms in 1_800..2_600)
    }

    @Test
    fun aSlowerPresetCostsMoreAirAndAFasterOneCostsLess() {
        val long = LoraAirtime().apply { onRadioConfig(radio(ModemPreset.LONG_FAST)) }.timeOnAirMs(200)
        val slow = LoraAirtime().apply { onRadioConfig(radio(ModemPreset.LONG_SLOW)) }.timeOnAirMs(200)
        val fast = LoraAirtime().apply { onRadioConfig(radio(ModemPreset.SHORT_TURBO)) }.timeOnAirMs(200)
        assertTrue("LONG_SLOW ($slow) must cost more than LONG_FAST ($long)", slow > long)
        assertTrue("SHORT_TURBO ($fast) must cost less than LONG_FAST ($long)", fast < long)
    }

    @Test
    fun aSmallPacketCostsLessThanAFullOne() {
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        assertTrue(air.timeOnAirMs(40) < air.timeOnAirMs(MeshtasticProto.MAX_PAYLOAD))
    }

    @Test
    fun withNoBoardConfigTheAllowanceIsTheConservativeFallback() {
        val air = LoraAirtime()
        val expected = (LoraAirtime.WINDOW_MS * LoraAirtime.FALLBACK_PERCENT / 100 * LoraAirtime.SAFETY).toLong()
        assertEquals(expected, air.allowanceMs())
        assertFalse(air.snapshot(0).known)
    }

    @Test
    fun theRegionsDutyCycleCapsTheAllowanceAndThePolitenessCeilingCapsTheRest() {
        val eu = LoraAirtime().apply { onRadioConfig(radio(region = LoraRegion.EU_868)) }
        val us = LoraAirtime().apply { onRadioConfig(radio(region = LoraRegion.OTHER)) }
        // EU_868 runs at 10 %, which is also our politeness ceiling, so the two allowances coincide —
        // a 100 %-duty region is limited by politeness, not by law.
        assertEquals(eu.allowanceMs(), us.allowanceMs())
        val ceiling = (LoraAirtime.WINDOW_MS * LoraAirtime.POLITE_CEILING_PERCENT / 100 * LoraAirtime.SAFETY).toLong()
        assertEquals(ceiling, us.allowanceMs())
    }

    @Test
    fun aDutyCycleOverrideDropsTheRegionalCapButKeepsThePolitenessCeiling() {
        val strict = LoraAirtime().apply { onRadioConfig(radio(region = LoraRegion.EU_433)) }
        val overridden = LoraAirtime().apply { onRadioConfig(radio(region = LoraRegion.EU_433, override = true)) }
        assertTrue(overridden.allowanceMs() >= strict.allowanceMs())
        val ceiling = (LoraAirtime.WINDOW_MS * LoraAirtime.POLITE_CEILING_PERCENT / 100 * LoraAirtime.SAFETY).toLong()
        assertEquals(ceiling, overridden.allowanceMs())
    }

    @Test
    fun theBridgeBudgetIsAShareOfTheWholeAllowanceNotASecondOne() {
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        assertEquals((air.allowanceMs() * LoraAirtime.BRIDGE_SHARE).toLong(), air.budgetMs(AirBucket.BRIDGE))
        assertEquals(air.allowanceMs(), air.budgetMs(AirBucket.LIVE))
    }

    @Test
    fun bridgeIsRefusedAtItsShareWhileLiveStillGoes() {
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        val packet = listOf(MeshtasticProto.MAX_PAYLOAD)
        // Spend the bridge share and nothing else.
        var now = 0L
        while (air.admits(AirBucket.BRIDGE, FrameClass.ROOM, packet, now)) {
            air.record(AirBucket.BRIDGE, MeshtasticProto.MAX_PAYLOAD, now)
            now += 3_000
        }
        assertFalse("bridge is spent", air.admits(AirBucket.BRIDGE, FrameClass.ROOM, packet, now))
        assertTrue("live still has the rest of the allowance", air.admits(AirBucket.LIVE, FrameClass.ROOM, packet, now))
        assertTrue(air.usedMs(AirBucket.BRIDGE, now) <= air.budgetMs(AirBucket.BRIDGE))
    }

    @Test
    fun aGossipOfferRidesABridgeBudgetThatServingHasSpent() {
        // The failure this exists for: an OFFER shares BRIDGE with the backfill it drives, so a gateway busy
        // serving a far pocket starved its own offers — and an offer is the only thing that makes the far
        // pocket able to serve back. Serving is what should shed under pressure, not the packet that unlocks
        // the reverse direction.
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        val offer = listOf(LoraCtl.HEADER_BYTES + LoraCtl.MAX_PREFIXES * LoraCtl.PREFIX_BYTES)
        var now = 0L
        while (air.admits(AirBucket.BRIDGE, FrameClass.ROOM, offer, now)) {
            air.record(AirBucket.BRIDGE, MeshtasticProto.MAX_PAYLOAD, now)
            now += 3_000
        }
        assertFalse("serving is refused at its share", air.admits(AirBucket.BRIDGE, FrameClass.ROOM, offer, now))
        assertTrue("the offer still rides", air.admits(AirBucket.BRIDGE, FrameClass.GOSSIP, offer, now))
    }

    @Test
    fun aGossipOfferStillStopsAtTheWindowTotal() {
        // The exemption is from the BRIDGE share, not from the allowance: live chat still outranks gossip,
        // and a window spent on messages somebody typed repairs the bridge in the next one.
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        val packet = listOf(MeshtasticProto.MAX_PAYLOAD)
        var now = 0L
        while (air.admits(AirBucket.LIVE, FrameClass.ROOM, packet, now)) {
            air.record(AirBucket.LIVE, MeshtasticProto.MAX_PAYLOAD, now)
            now += 3_000
        }
        assertFalse("no air left at all", air.admits(AirBucket.BRIDGE, FrameClass.GOSSIP, packet, now))
    }

    @Test
    fun gossipSpendingCostsServingItsHeadroom() {
        // It books BRIDGE even though it is not judged against it, so a chatty gossip timer degrades the
        // backfill rather than the reverse — which is the direction the whole split is arguing for.
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        val before = air.usedMs(AirBucket.BRIDGE, 0L)
        air.record(AirBucket.BRIDGE, MeshtasticProto.MAX_PAYLOAD, 0L)
        assertTrue("the offer's air is on the bridge ledger", air.usedMs(AirBucket.BRIDGE, 0L) > before)
    }

    @Test
    fun aBootstrapFrameStillRidesWithTheRestOfTheBudgetSpent() {
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        val packet = listOf(MeshtasticProto.MAX_PAYLOAD)
        var now = 0L
        while (air.admits(AirBucket.LIVE, FrameClass.ROOM, packet, now)) {
            air.record(AirBucket.LIVE, MeshtasticProto.MAX_PAYLOAD, now)
            now += 3_000
        }
        assertFalse(air.admits(AirBucket.LIVE, FrameClass.ROOM, packet, now))
        assertTrue(
            "nothing verifies without the author's profile, so a spent window must not silence it",
            air.admits(AirBucket.BOOTSTRAP, FrameClass.BOOTSTRAP, packet, now),
        )
    }

    @Test
    fun theBootstrapIsRefusedAtItsOwnShareSoItCanNeverBlankThePlane() {
        // ADR 056. Before it, BOOTSTRAP returned true unconditionally *and* was recorded, so a relayed
        // profile re-fanned every 10 min (the sig dedup's TTL) could take the whole allowance and leave the
        // plane refusing everything a human had typed. On the lab gateway it took 79 % of all frames sent.
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        val packet = listOf(MeshtasticProto.MAX_PAYLOAD)
        var now = 0L
        var admitted = 0
        while (air.admits(AirBucket.BOOTSTRAP, FrameClass.BOOTSTRAP, packet, now)) {
            air.record(AirBucket.BOOTSTRAP, MeshtasticProto.MAX_PAYLOAD, now)
            admitted++
            now += 3_000
            assertTrue("the exemption is bounded, not unbounded", admitted < 100)
        }
        assertTrue("some bootstrap always fits", admitted > 0)
        assertTrue(
            "spending stays inside the bootstrap share",
            air.usedMs(AirBucket.BOOTSTRAP, now) <= air.budgetMs(AirBucket.BOOTSTRAP),
        )
        assertEquals(
            (air.allowanceMs() * LoraAirtime.BOOTSTRAP_SHARE).toLong(),
            air.budgetMs(AirBucket.BOOTSTRAP),
        )
        // And what it did spend is real air: content sees a window that much smaller, not a fresh one.
        val left = air.allowanceMs() - air.usedMs(AirBucket.BOOTSTRAP, now)
        assertTrue("the bootstrap's air is charged to the total too", left < air.allowanceMs())
        assertTrue("three quarters of the window survives it", left >= air.allowanceMs() * 3 / 4)
    }

    @Test
    fun aSpentBootstrapShareDoesNotStopContentGoingOut() {
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        val packet = listOf(MeshtasticProto.MAX_PAYLOAD)
        var now = 0L
        while (air.admits(AirBucket.BOOTSTRAP, FrameClass.BOOTSTRAP, packet, now)) {
            air.record(AirBucket.BOOTSTRAP, MeshtasticProto.MAX_PAYLOAD, now)
            now += 3_000
        }
        assertFalse(air.admits(AirBucket.BOOTSTRAP, FrameClass.BOOTSTRAP, packet, now))
        assertTrue("a DM still has the rest of the window", air.admits(AirBucket.LIVE, FrameClass.DM, packet, now))
    }

    @Test
    fun thePublicChannelIsCappedInBothDirections() {
        // Its own share, so a busy public room cannot crowd the pocket's own chat off the band; charged
        // against the total too, so it is a share of the one allowance and not a second one beside it.
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        val post = listOf(PublicPostPolicy.MAX_ON_AIR_BYTES)
        var now = 0L
        var admitted = 0
        while (air.admits(AirBucket.PUBLIC, FrameClass.ROOM, post, now)) {
            air.record(AirBucket.PUBLIC, PublicPostPolicy.MAX_ON_AIR_BYTES, now)
            admitted++
            now += 30_000
            assertTrue("the share is bounded", admitted < 100)
        }
        assertTrue("some public traffic always fits", admitted > 0)
        assertEquals((air.allowanceMs() * LoraAirtime.PUBLIC_SHARE).toLong(), air.budgetMs(AirBucket.PUBLIC))
        assertTrue(
            "the pocket's own chat still has most of the window",
            air.admits(AirBucket.LIVE, FrameClass.ROOM, listOf(MeshtasticProto.MAX_PAYLOAD), now),
        )
        val left = air.allowanceMs() - air.usedMs(AirBucket.PUBLIC, now)
        assertTrue("and what it spent is real air, charged to the total", left < air.allowanceMs())
    }

    @Test
    fun aSpentWindowRefusesAPublicPostToo() {
        // The other direction: nothing here is exempt from the total the way the key bootstrap is. A window
        // spent on Knit's own traffic is spent, and a post waits for the next one.
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        val packet = listOf(MeshtasticProto.MAX_PAYLOAD)
        var now = 0L
        while (air.admits(AirBucket.LIVE, FrameClass.ROOM, packet, now)) {
            air.record(AirBucket.LIVE, MeshtasticProto.MAX_PAYLOAD, now)
            now += 3_000
        }
        assertFalse(air.admits(AirBucket.PUBLIC, FrameClass.ROOM, listOf(PublicPostPolicy.MAX_ON_AIR_BYTES), now))
    }

    @Test
    fun aPublicPostIsPricedWithTheSignatureTheFirmwareWillAddToIt() {
        // A 200-byte text body sits under the 2.8 firmware's 165-byte signing cliff plus its own framing, so
        // the board attaches 66 bytes Knit never asked for. ADR 2026-09.mhs5 pads Knit's own frames *past*
        // that cliff to dodge it; a human-readable post cannot be padded, so the budget pays for it instead.
        val signing = LoraAirtime().apply { onRadioConfig(radio()) }
        val notSigning =
            LoraAirtime().apply {
                onRadioConfig(radio())
                onFirmware("2.7.26")
            }
        val short = 120
        assertTrue(
            "the surcharge is priced, not assumed away",
            signing.timeOnAirMs(short) > notSigning.timeOnAirMs(short),
        )
    }

    @Test
    fun theWindowIsFifteenMinutesSoAWorstCaseHourStaysUnderTheEuRefusalPoint() {
        assertEquals(15 * 60_000L, LoraAirtime.WINDOW_MS)
        val air = LoraAirtime().apply { onRadioConfig(radio(region = LoraRegion.EU_868)) }
        // Rolling windows straddle an hour, so five can partly overlap it: 5/4 of the nominal allowance.
        val worstHourPercent = air.allowanceMs() * 5 / 4 * 100.0 / (60 * 60_000L)
        assertTrue(
            "worst hour $worstHourPercent % must stay under the firmware's 10 %",
            worstHourPercent < LoraRegion.EU_868.dutyCyclePercent,
        )
    }

    @Test
    fun aTickNeverSpendsTheTailOfAWindowButContentStillDoes() {
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        val packet = listOf(MeshtasticProto.MAX_PAYLOAD)
        var now = 0L
        while (air.admits(AirBucket.LIVE, FrameClass.TICK, packet, now)) {
            air.record(AirBucket.LIVE, MeshtasticProto.MAX_PAYLOAD, now)
            now += 3_000
        }
        val tail = (air.budgetMs(AirBucket.LIVE) * LoraAirtime.TICK_TAIL_SHARE).toLong()
        assertTrue(
            "ticks stop at the tail",
            air.usedMs(AirBucket.LIVE, now) >= air.budgetMs(AirBucket.LIVE) - tail - air.timeOnAirMs(MeshtasticProto.MAX_PAYLOAD),
        )
        assertFalse(air.admits(AirBucket.LIVE, FrameClass.TICK, packet, now))
        assertTrue("a DM still has the tail", air.admits(AirBucket.LIVE, FrameClass.DM, packet, now))
        assertTrue("so does the room", air.admits(AirBucket.LIVE, FrameClass.ROOM, packet, now))
    }

    @Test
    fun spendingAgesOutOfTheRollingWindow() {
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        air.record(AirBucket.LIVE, MeshtasticProto.MAX_PAYLOAD, 0)
        assertTrue(air.usedMs(AirBucket.LIVE, 0) > 0)
        assertEquals(0L, air.usedMs(AirBucket.LIVE, LoraAirtime.WINDOW_MS))
    }

    @Test
    fun aWholeFragmentedFrameIsAdmittedOrRefusedTogether() {
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        val one = listOf(MeshtasticProto.MAX_PAYLOAD)
        val three = List(3) { MeshtasticProto.MAX_PAYLOAD }
        var now = 0L
        // Spend down to where one packet fits but three do not.
        while (air.admits(AirBucket.LIVE, FrameClass.ROOM, three, now)) {
            air.record(AirBucket.LIVE, MeshtasticProto.MAX_PAYLOAD, now)
            now += 3_000
        }
        assertFalse(air.admits(AirBucket.LIVE, FrameClass.ROOM, three, now))
        assertTrue(air.admits(AirBucket.LIVE, FrameClass.ROOM, one, now))
    }

    // --- ADR 067: a dedicated RF slot lifts the politeness ceiling, never the law ---

    @Test
    fun aDedicatedSlotIsInertUnlessTheBuildUnlocksIt() {
        // The default — every release build. A board somebody pinned by hand in the Meshtastic app must
        // budget exactly as a shared-frequency one does.
        val locked = LoraAirtime().apply { onRadioConfig(radio(region = LoraRegion.US, channelNum = 37)) }
        val shared = LoraAirtime().apply { onRadioConfig(radio(region = LoraRegion.US)) }
        assertEquals(shared.allowanceMs(), locked.allowanceMs())
        assertFalse(locked.dedicated())
    }

    @Test
    fun aDedicatedSlotInAHundredPercentRegionSpendsTheWholeDutyCycle() {
        val unlocked = LoraAirtime(dedicatedUnlocksDuty = true)
        unlocked.onRadioConfig(radio(region = LoraRegion.US, channelNum = 37))
        assertTrue(unlocked.dedicated())
        // 100 % duty x the 0.5 safety factor = half the window, against a tenth of that when sharing.
        assertEquals(LoraAirtime.WINDOW_MS / 2, unlocked.allowanceMs())
        val shared = LoraAirtime(dedicatedUnlocksDuty = true).apply { onRadioConfig(radio(region = LoraRegion.US)) }
        assertEquals(10L, unlocked.allowanceMs() / shared.allowanceMs())
    }

    @Test
    fun aDedicatedSlotNeverLiftsARegionsLegalDutyCycle() {
        // EU_868's 10 % is law, not manners: a dedicated slot there must budget exactly as a shared one.
        val dedicated = LoraAirtime(dedicatedUnlocksDuty = true)
        dedicated.onRadioConfig(radio(region = LoraRegion.EU_868, channelNum = 3))
        val shared = LoraAirtime(dedicatedUnlocksDuty = true).apply { onRadioConfig(radio(region = LoraRegion.EU_868)) }
        assertEquals(shared.allowanceMs(), dedicated.allowanceMs())
    }

    @Test
    fun theSnapshotReportsWhetherTheBudgetIsRunningDedicated() {
        val air = LoraAirtime(dedicatedUnlocksDuty = true)
        air.onRadioConfig(radio(region = LoraRegion.US, channelNum = 12))
        assertTrue(air.snapshot(0L).dedicated)
        assertFalse(LoraAirtime().apply { onRadioConfig(radio(region = LoraRegion.US)) }.snapshot(0L).dedicated)
    }

    @Test
    fun signingIsFirmware28AndAnythingItCannotReadIsChargedForAnyway() {
        assertTrue(LoraAirtime.signsPackets("2.8.0.7239fe8"))
        assertTrue(LoraAirtime.signsPackets("2.9.1.abc"))
        assertTrue(LoraAirtime.signsPackets("3.0.0"))
        assertFalse(LoraAirtime.signsPackets("2.7.26.b246bcd"))
        assertFalse(LoraAirtime.signsPackets("2.5.0"))
        assertFalse(LoraAirtime.signsPackets("1.9.9"))
        // Unknown has to be the expensive reading: under-charging against firmware that signs spends air the
        // budget never allowed for, and in a duty-limited region that is law rather than manners.
        assertTrue(LoraAirtime.signsPackets(null))
        assertTrue(LoraAirtime.signsPackets(""))
        assertTrue(LoraAirtime.signsPackets("unknown"))
        assertTrue(LoraAirtime.signsPackets("2"))
    }

    @Test
    fun a28BoardIsChargedForTheSignatureItAddsToOurSmallPackets() {
        val old =
            LoraAirtime().apply {
                onRadioConfig(radio())
                onFirmware("2.7.26.b246bcd")
            }
        val new =
            LoraAirtime().apply {
                onRadioConfig(radio())
                onFirmware("2.8.0.7239fe8")
            }
        // ADR 060's transcoded room tick: one packet either way, but 66 bytes more of it on 2.8.
        val tick = 157
        assertTrue(
            "2.8 must cost more air for a $tick B tick (${new.timeOnAirMs(tick)} vs ${old.timeOnAirMs(tick)})",
            new.timeOnAirMs(tick) > old.timeOnAirMs(tick),
        )
        assertEquals(old.timeOnAirMs(tick + MeshtasticProto.XEDDSA_SIGNATURE_FIELD), new.timeOnAirMs(tick))
    }

    @Test
    fun aPacketTooBigToSignCostsTheSameOnEitherFirmware() {
        val old =
            LoraAirtime().apply {
                onRadioConfig(radio())
                onFirmware("2.7.26.b246bcd")
            }
        val new =
            LoraAirtime().apply {
                onRadioConfig(radio())
                onFirmware("2.8.0.7239fe8")
            }
        // Above the cliff the firmware sends it unsigned exactly as 2.7 did, so nothing is owed.
        for (size in listOf(MeshtasticProto.MAX_SIGNED_PAYLOAD + 1, 221, MeshtasticProto.MAX_PAYLOAD)) {
            assertEquals("$size B must cost the same", old.timeOnAirMs(size), new.timeOnAirMs(size))
        }
        // And at the cliff itself it is still signed.
        assertTrue(new.timeOnAirMs(MeshtasticProto.MAX_SIGNED_PAYLOAD) > old.timeOnAirMs(MeshtasticProto.MAX_SIGNED_PAYLOAD))
    }

    // --- ADR 2026-09.mhs5: padding past the cliff ---

    private fun air28(preset: ModemPreset = ModemPreset.LONG_FAST) =
        LoraAirtime().apply {
            onRadioConfig(radio(preset))
            onFirmware("2.8.0.7239fe8")
        }

    @Test
    fun aPacketAtTheCliffIsPaddedOneBytePastItAndGetsCheaper() {
        val cliff = MeshtasticProto.MAX_SIGNED_PAYLOAD
        for (preset in listOf(ModemPreset.LONG_FAST, ModemPreset.LONG_TURBO, ModemPreset.MEDIUM_FAST)) {
            val air = air28(preset)
            assertEquals("$preset must pad to just past the cliff", cliff + 1, air.padTo(cliff, MeshtasticProto.MAX_PAYLOAD))
            assertTrue(
                "$preset: padding must actually cost less air (${air.timeOnAirMs(cliff + 1)} vs ${air.timeOnAirMs(cliff)})",
                air.timeOnAirMs(cliff + 1) < air.timeOnAirMs(cliff),
            )
        }
    }

    @Test
    fun aPacketFarBelowTheCliffIsLeftAloneBecauseThePadWouldCostMoreThanTheSignature() {
        val air = air28()
        // A 66-byte signature buys back at most 66 bytes of pad, so anything needing more must stay put.
        assertEquals(40, air.padTo(40, MeshtasticProto.MAX_PAYLOAD))
        assertEquals(80, air.padTo(80, MeshtasticProto.MAX_PAYLOAD))
        assertTrue("...and the band that does pay reaches well below the cliff", air.padTo(120, MeshtasticProto.MAX_PAYLOAD) > 120)
    }

    @Test
    fun nothingIsPaddedWhenThereIsNoSignatureToDodge() {
        val old =
            LoraAirtime().apply {
                onRadioConfig(radio())
                onFirmware("2.7.26.b246bcd")
            }
        val cliff = MeshtasticProto.MAX_SIGNED_PAYLOAD
        assertEquals("a pre-2.8 board signs nothing, so a pad is pure loss", cliff, old.padTo(cliff, MeshtasticProto.MAX_PAYLOAD))
        val air = air28()
        assertEquals("already past the cliff", cliff + 1, air.padTo(cliff + 1, MeshtasticProto.MAX_PAYLOAD))
        assertEquals(
            "and a full packet stays full",
            MeshtasticProto.MAX_PAYLOAD,
            air.padTo(MeshtasticProto.MAX_PAYLOAD, MeshtasticProto.MAX_PAYLOAD),
        )
    }

    @Test
    fun aCapTooSmallToClearTheCliffRefusesToPad() {
        // A board whose MTU sizes the payload cap below the cliff has nowhere to pad to; growing to the cap
        // would spend bytes and still be signed.
        val air = air28()
        assertEquals(140, air.padTo(140, MeshtasticProto.MAX_SIGNED_PAYLOAD))
        assertEquals(140, air.padTo(140, 120))
    }

    @Test
    fun theCliffDodgeIsWorthTheFigureTheBenchMeasured() {
        // Heltec V4 / 2.8.0.7239fe8, 2026-08-31: 165 B measured 1655 ms and 166 B measured 1262 ms at
        // LongTurbo. That 393 ms is the whole reason padding exists, so pin it rather than only claiming it.
        val air = air28(ModemPreset.LONG_TURBO)
        val cliff = MeshtasticProto.MAX_SIGNED_PAYLOAD
        assertEquals(1_655, air.timeOnAirMs(cliff))
        assertEquals(1_262, air.timeOnAirMs(air.padTo(cliff, MeshtasticProto.MAX_PAYLOAD)))
    }

    @Test
    fun theSurchargeComesOutOfTheWindowRatherThanBesideIt() {
        // The point of charging for it at all: a 2.8 board must fit fewer ticks in a window, not the same
        // number plus a note.
        fun ticksPerWindow(firmware: String): Int {
            val air =
                LoraAirtime().apply {
                    onRadioConfig(radio())
                    onFirmware(firmware)
                }
            var n = 0
            while (air.admits(AirBucket.LIVE, FrameClass.ROOM, listOf(157), 0L)) {
                air.record(AirBucket.LIVE, 157, 0L)
                n++
            }
            return n
        }
        assertTrue(ticksPerWindow("2.8.0.7239fe8") < ticksPerWindow("2.7.26.b246bcd"))
    }

    @Test
    fun theSnapshotReportsWhetherTheSignatureIsBeingChargedFor() {
        assertTrue(LoraAirtime().snapshot(0L).signing)
        assertFalse(LoraAirtime().apply { onFirmware("2.7.26.b246bcd") }.snapshot(0L).signing)
    }
}
