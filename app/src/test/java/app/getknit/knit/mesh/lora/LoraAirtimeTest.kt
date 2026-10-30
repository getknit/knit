package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    // --- One ledger, one total, and the foreign audience's half (ADR 2026-10.ug4a). ---

    @Test
    fun everyFrameOfOursSpendsOneTotalAgainstTheWholeAllowance() {
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        val packet = listOf(MeshtasticProto.MAX_PAYLOAD)
        air.record(MeshtasticProto.MAX_PAYLOAD, 0)
        air.record(
            PublicPostPolicy.MAX_ON_AIR_BYTES,
            0,
            air.price(AirSlot.PRIMARY, PacketKind.BROADCAST, MeshtasticProto.PORT_TEXT_MESSAGE),
        )
        val both =
            air.timeOnAirMs(MeshtasticProto.MAX_PAYLOAD) +
                air.timeOnAirMs(PublicPostPolicy.MAX_ON_AIR_BYTES, MeshtasticProto.maxSignedPayload(MeshtasticProto.PORT_TEXT_MESSAGE))
        assertEquals("a Knit frame and a Meshtastic-room post land in the same ledger", both, air.usedMs(0))
        val snap = air.snapshot(0)
        assertEquals(both, snap.usedMs)
        assertEquals(air.allowanceMs(), snap.allowanceMs)
        assertEquals((air.allowanceMs() * LoraAirtime.PUBLIC_CEILING).toLong(), snap.publicCeilingMs)
        var now = 0L
        while (air.admits(Ceiling.FULL, packet, now)) {
            air.record(MeshtasticProto.MAX_PAYLOAD, now)
            now += 3_000
        }
        assertTrue(
            "a full frame spends to the whole allowance",
            air.usedMs(now) > air.allowanceMs() - air.timeOnAirMs(MeshtasticProto.MAX_PAYLOAD),
        )
        assertEquals(LoraAirtime.Refusal.TOTAL, air.refusal(Ceiling.FULL, packet, now))
    }

    @Test
    fun theForeignAudienceStopsAtHalfTheWindowWithItsOwnCostIncluded() {
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        val post = listOf(PublicPostPolicy.MAX_ON_AIR_BYTES)
        var now = 0L
        var admitted = 0
        while (air.admits(Ceiling.HALF, post, now)) {
            air.record(PublicPostPolicy.MAX_ON_AIR_BYTES, now)
            admitted++
            now += 30_000
            assertTrue("the ceiling is bounded", admitted < 100)
        }
        assertTrue("some public traffic always fits", admitted > 0)
        val cost = air.timeOnAirMs(PublicPostPolicy.MAX_ON_AIR_BYTES)
        assertTrue("its own cost is counted: the next one would cross half", air.usedMs(now) + cost > air.publicCeilingMs())
        assertTrue("and it spent up to it, not short of it", air.usedMs(now) + 2 * cost > air.publicCeilingMs())
        assertEquals(LoraAirtime.Refusal.CEILING, air.refusal(Ceiling.HALF, post, now))
        assertNull("a Knit frame still has the other half", air.refusal(Ceiling.FULL, listOf(MeshtasticProto.MAX_PAYLOAD), now))
    }

    @Test
    fun theHalfIsOnTheTotalSoKnitsOwnSpendLeavesTheRoomLess() {
        // No share of its own to be judged by: a window Knit has spent past half refuses a Meshtastic-room post, however
        // little the room itself spent.
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        var now = 0L
        while (air.usedMs(now) <= air.publicCeilingMs()) {
            air.record(MeshtasticProto.MAX_PAYLOAD, now)
            now += 3_000
        }
        assertEquals(LoraAirtime.Refusal.CEILING, air.refusal(Ceiling.HALF, listOf(40), now))
        assertNull(air.refusal(Ceiling.FULL, listOf(40), now))
    }

    @Test
    fun aSpentWindowRefusesAPublicPostOnTheTotal() {
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        val packet = listOf(MeshtasticProto.MAX_PAYLOAD)
        var now = 0L
        while (air.admits(Ceiling.FULL, packet, now)) {
            air.record(MeshtasticProto.MAX_PAYLOAD, now)
            now += 3_000
        }
        assertEquals(LoraAirtime.Refusal.TOTAL, air.refusal(Ceiling.HALF, listOf(PublicPostPolicy.MAX_ON_AIR_BYTES), now))
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
                onBoard(SigningFacts("2.7.26"))
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
    fun aFrameNearlyTheSizeOfWhatIsLeftIsAdmittedWithNoTailHeldBack() {
        // ADR 054's tick tail is gone (ADR 2026-10.ug4a): the last of the window is anybody's, a ✓✓'s included.
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        val packet = listOf(60)
        var now = 0L
        while (air.admits(Ceiling.FULL, packet, now, PacketPrice.UNSIGNED)) {
            air.record(60, now, PacketPrice.UNSIGNED)
            now += 3_000
        }
        assertTrue(
            "spent to within one packet of the whole allowance",
            air.allowanceMs() - air.usedMs(now) < air.timeOnAirMs(60, LoraAirtime.UNSIGNED),
        )
    }

    @Test
    fun spendingAgesOutOfTheRollingWindow() {
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        air.record(MeshtasticProto.MAX_PAYLOAD, 0)
        assertTrue(air.usedMs(0) > 0)
        assertEquals(0L, air.usedMs(LoraAirtime.WINDOW_MS))
    }

    @Test
    fun aWholeFragmentedFrameIsAdmittedOrRefusedTogether() {
        val air = LoraAirtime().apply { onRadioConfig(radio()) }
        val one = listOf(MeshtasticProto.MAX_PAYLOAD)
        val three = List(3) { MeshtasticProto.MAX_PAYLOAD }
        var now = 0L
        // Spend down to where one packet fits but three do not.
        while (air.admits(Ceiling.FULL, three, now)) {
            air.record(MeshtasticProto.MAX_PAYLOAD, now)
            now += 3_000
        }
        assertFalse(air.admits(Ceiling.FULL, three, now))
        assertTrue(air.admits(Ceiling.FULL, one, now))
    }

    // --- ADR 067: a dedicated RF slot lifts the politeness ceiling, never the law ---

    private val knitUsSlot = LoraSlot.forRegion(LoraRegion.US, ModemPreset.LONG_FAST)!!

    @Test
    fun aDedicatedSlotIsInertUnlessTheGovernorUnlocksIt() {
        val locked = LoraAirtime().apply { onRadioConfig(radio(region = LoraRegion.US, channelNum = knitUsSlot)) }
        val shared = LoraAirtime().apply { onRadioConfig(radio(region = LoraRegion.US)) }
        assertEquals(shared.allowanceMs(), locked.allowanceMs())
        assertFalse(locked.dedicated())
    }

    @Test
    fun aDedicatedSlotInAHundredPercentRegionSpendsTheWholeDutyCycle() {
        val unlocked = LoraAirtime(dedicatedUnlocksDuty = true)
        unlocked.onRadioConfig(radio(region = LoraRegion.US, channelNum = knitUsSlot))
        assertTrue(unlocked.dedicated())
        // 100 % duty x the 0.5 safety factor = half the window, against a tenth of that when sharing.
        assertEquals(LoraAirtime.WINDOW_MS / 2, unlocked.allowanceMs())
        val shared = LoraAirtime(dedicatedUnlocksDuty = true).apply { onRadioConfig(radio(region = LoraRegion.US)) }
        assertEquals(10L, unlocked.allowanceMs() / shared.allowanceMs())
    }

    @Test
    fun aSlotPinnedByHandOffKnitsKeepsThePolitenessCeiling() {
        // ADR 067's 2026-10-22 amendment: a slot the user pinned in the Meshtastic app may be a community's,
        // so only the slot Knit's own setup derives lifts the ceiling.
        val pinned = LoraAirtime(dedicatedUnlocksDuty = true)
        val other = if (knitUsSlot == 20) 21 else 20
        pinned.onRadioConfig(radio(region = LoraRegion.US, channelNum = other))
        val shared = LoraAirtime(dedicatedUnlocksDuty = true).apply { onRadioConfig(radio(region = LoraRegion.US)) }
        assertFalse(pinned.dedicated())
        assertEquals(shared.allowanceMs(), pinned.allowanceMs())
    }

    @Test
    fun aPinnedSlotInADutyLimitedRegionIsNeverDedicated() {
        // EU_868's 10 % is law, not manners. It holds because LoraSlot places no slot in a region it has no
        // band for (LoraSlotTest pins that), so a pinned board there is never dedicated and budgets as shared.
        val pinned = LoraAirtime(dedicatedUnlocksDuty = true)
        pinned.onRadioConfig(radio(region = LoraRegion.EU_868, channelNum = 3))
        val shared = LoraAirtime(dedicatedUnlocksDuty = true).apply { onRadioConfig(radio(region = LoraRegion.EU_868)) }
        assertFalse(pinned.dedicated())
        assertEquals(shared.allowanceMs(), pinned.allowanceMs())
    }

    @Test
    fun theSnapshotReportsWhetherTheBudgetIsRunningDedicated() {
        val air = LoraAirtime(dedicatedUnlocksDuty = true)
        air.onRadioConfig(radio(region = LoraRegion.US, channelNum = knitUsSlot))
        assertTrue(air.snapshot(0L).dedicated)
        assertFalse(LoraAirtime().apply { onRadioConfig(radio(region = LoraRegion.US)) }.snapshot(0L).dedicated)
    }

    // --- #243: what the firmware signs, by version, policy, channel, packet class and ham mode ---

    private val knit = MeshtasticProto.PORT_PRIVATE_APP
    private val text = MeshtasticProto.PORT_TEXT_MESSAGE
    private val knitCliff = MeshtasticProto.MAX_SIGNED_PAYLOAD
    private val textCliff = MeshtasticProto.maxSignedPayload(MeshtasticProto.PORT_TEXT_MESSAGE)
    private val u = LoraAirtime.UNSIGNED

    private fun cliff(
        firmware: String?,
        policy: SignaturePolicy?,
        kind: PacketKind = PacketKind.BROADCAST,
        aead: Boolean = false,
        licensed: Boolean = false,
        hasXeddsa: Boolean? = true,
        portnum: Int = knit,
    ): Int = LoraAirtime.price(SigningFacts(firmware, hasXeddsa, policy, licensed), aead, kind, portnum).signedUpTo

    @Test
    fun aBroadcastIsSignedByVersionAndPolicy() {
        // Rows: firmware. Columns: COMPATIBLE, BALANCED, STRICT, a future value, and no security config streamed at all.
        val policies =
            listOf(SignaturePolicy.COMPATIBLE, SignaturePolicy.BALANCED, SignaturePolicy.STRICT, SignaturePolicy.UNRECOGNISED, null)
        val table =
            mapOf(
                // Pre-2.8 never signs; `has_xeddsa` is absent on it.
                "2.7.26.b246bcd" to listOf(u, u, u, u, u),
                // Tag 2.8.1 ignores the policy on the send path and signs every broadcast.
                "2.8.1.8e6a88d" to listOf(knitCliff, knitCliff, knitCliff, knitCliff, knitCliff),
                "2.8.0.47db0e3" to listOf(knitCliff, knitCliff, knitCliff, knitCliff, knitCliff),
                // 2.8.2 (upstream 34cb8d582): COMPATIBLE never signs; an unknown or unrecognised policy reads as signing.
                "2.8.2.34cb8d5" to listOf(u, knitCliff, knitCliff, knitCliff, knitCliff),
                "2.9.0.abcdef0" to listOf(u, knitCliff, knitCliff, knitCliff, knitCliff),
            )
        for ((firmware, row) in table) {
            for ((policy, want) in policies.zip(row)) {
                val xeddsa = if (firmware.startsWith("2.7")) null else true
                assertEquals("$firmware under $policy", want, cliff(firmware, policy, hasXeddsa = xeddsa))
            }
        }
    }

    @Test
    fun aBuildThatCannotSignOrAVersionThatCannotBeReadIsPricedTheExpensiveWay() {
        assertEquals("has_xeddsa false never signs", u, cliff("2.8.1.8e6a88d", SignaturePolicy.STRICT, hasXeddsa = false))
        assertEquals(u, cliff("2.8.2.34cb8d5", SignaturePolicy.BALANCED, hasXeddsa = false))
        // Unknown has to be the expensive reading: under-charging against firmware that signs spends air the budget never
        // allowed for, and in a duty-limited region that is law rather than manners.
        for (firmware in listOf(null, "", "unknown", "2", "2.8")) {
            assertEquals("'$firmware' even under COMPATIBLE", knitCliff, cliff(firmware, SignaturePolicy.COMPATIBLE, hasXeddsa = null))
        }
        assertEquals("a pre-2.8 version that says it can sign", knitCliff, cliff("2.7.9", SignaturePolicy.COMPATIBLE, hasXeddsa = true))
        assertEquals("and the version decides when the flag is absent", u, cliff("2.7.26.b246bcd", null, hasXeddsa = null))
    }

    @Test
    fun anAeadChannelIsUnsignedOn282ButSigned12BytesShortOn281AndAlwaysPays12Bytes() {
        // 2.8.1's signedDataFits ignores the AEAD tag and its AEAD path then refuses what overflows (Router.cpp:1177-1185,
        // 1377-1379): only a payload 12 B under the usual edge leaves signed. 34cb8d582 never signs on an AEAD channel.
        assertEquals(154, cliff("2.8.1.8e6a88d", SignaturePolicy.COMPATIBLE, aead = true, portnum = text))
        assertEquals(153, cliff("2.8.1.8e6a88d", SignaturePolicy.STRICT, aead = true))
        assertEquals(u, cliff("2.8.2.34cb8d5", SignaturePolicy.STRICT, aead = true, portnum = text))
        assertEquals(u, cliff("2.8.2.34cb8d5", null, aead = true))
        for (firmware in listOf("2.8.1.8e6a88d", "2.8.2.34cb8d5")) {
            val price = LoraAirtime.price(SigningFacts(firmware, true, SignaturePolicy.BALANCED), aead = true, PacketKind.BROADCAST, knit)
            assertEquals(MeshtasticProto.AEAD_OVERHEAD_BYTES, price.extraBytes)
            assertEquals(219, price.cap)
        }
        val plain = LoraAirtime.price(SigningFacts("2.8.1.8e6a88d", true), aead = false, PacketKind.BROADCAST, text)
        assertEquals(PacketPrice(textCliff, 0, MeshtasticProto.MAX_PAYLOAD + 1), plain)
    }

    @Test
    fun aDataUnicastIsAPkiUnicastUnlessTheBoardIsInHamMode() {
        // Not ham: PKI, whatever the version, the policy or the channel — unsigned, +12 B, 219 B of room.
        for (firmware in listOf("2.7.26.b246bcd", "2.8.1.8e6a88d", "2.8.2.34cb8d5", null)) {
            for (aead in listOf(false, true)) {
                val price = LoraAirtime.price(SigningFacts(firmware, policy = SignaturePolicy.STRICT), aead, PacketKind.DATA_UNICAST, knit)
                assertEquals("$firmware aead=$aead", PacketPrice(u, MeshtasticProto.PKC_OVERHEAD_BYTES, 219), price)
            }
        }
        // Ham: no PKI, so the channel prices it and the firmware's rule signs it — 2.8.1 always, 2.8.2 by policy.
        assertEquals(knitCliff, cliff("2.8.1.8e6a88d", SignaturePolicy.COMPATIBLE, PacketKind.DATA_UNICAST, licensed = true))
        assertEquals(u, cliff("2.8.2.34cb8d5", SignaturePolicy.COMPATIBLE, PacketKind.DATA_UNICAST, licensed = true))
        assertEquals(knitCliff, cliff("2.8.2.34cb8d5", SignaturePolicy.BALANCED, PacketKind.DATA_UNICAST, licensed = true))
        assertEquals(u, cliff("2.7.26.b246bcd", null, PacketKind.DATA_UNICAST, licensed = true, hasXeddsa = null))
        assertEquals(
            0,
            LoraAirtime.price(SigningFacts("2.8.1.8e6a88d", true, licensed = true), false, PacketKind.DATA_UNICAST, knit).extraBytes,
        )
    }

    @Test
    fun aNodeInfoUnicastIsSignedUnder282sSigningPoliciesWhateverTheHamFlag() {
        val nodeInfo = MeshtasticProto.PORT_NODEINFO
        for (licensed in listOf(false, true)) {
            assertEquals(
                textCliff,
                cliff("2.8.2.34cb8d5", SignaturePolicy.BALANCED, PacketKind.NODEINFO_UNICAST, licensed = licensed, portnum = nodeInfo),
            )
            assertEquals(
                textCliff,
                cliff("2.8.2.34cb8d5", SignaturePolicy.STRICT, PacketKind.NODEINFO_UNICAST, licensed = licensed, portnum = nodeInfo),
            )
            assertEquals(
                u,
                cliff("2.8.2.34cb8d5", SignaturePolicy.COMPATIBLE, PacketKind.NODEINFO_UNICAST, licensed = licensed, portnum = nodeInfo),
            )
        }
        // 2.8.1 signs a unicast only in ham mode.
        assertEquals(u, cliff("2.8.1.8e6a88d", SignaturePolicy.STRICT, PacketKind.NODEINFO_UNICAST, portnum = nodeInfo))
        assertEquals(
            textCliff,
            cliff("2.8.1.8e6a88d", SignaturePolicy.COMPATIBLE, PacketKind.NODEINFO_UNICAST, licensed = true, portnum = nodeInfo),
        )
        assertEquals(
            "never PKI",
            0,
            LoraAirtime.price(SigningFacts("2.8.2.34cb8d5"), false, PacketKind.NODEINFO_UNICAST, nodeInfo).extraBytes,
        )
    }

    @Test
    fun aRoutingUnicastIsSignedOnlyBy281InHamMode() {
        val routing = MeshtasticProto.PORT_ROUTING
        assertEquals(
            textCliff,
            cliff("2.8.1.8e6a88d", SignaturePolicy.COMPATIBLE, PacketKind.ROUTING_UNICAST, licensed = true, portnum = routing),
        )
        assertEquals(u, cliff("2.8.1.8e6a88d", SignaturePolicy.STRICT, PacketKind.ROUTING_UNICAST, portnum = routing))
        for (policy in listOf(SignaturePolicy.COMPATIBLE, SignaturePolicy.BALANCED, SignaturePolicy.STRICT, null)) {
            for (licensed in listOf(false, true)) {
                assertEquals(
                    "2.8.2 $policy ham=$licensed",
                    u,
                    cliff("2.8.2.34cb8d5", policy, PacketKind.ROUTING_UNICAST, licensed = licensed, portnum = routing),
                )
            }
        }
        // An ACK on an AEAD slot 0 still pays the tag.
        assertEquals(
            MeshtasticProto.AEAD_OVERHEAD_BYTES,
            LoraAirtime.price(SigningFacts("2.8.2.34cb8d5"), true, PacketKind.ROUTING_UNICAST, routing).extraBytes,
        )
    }

    @Test
    fun theLedgerReadsTheSlotItsPacketIsWrittenTo() {
        // Slot 0 AEAD, the Knit slot plain, on 2.8.1: a room post's cliff moves, a Knit frame's does not.
        val air = LoraAirtime().apply { onBoard(SigningFacts("2.8.1.8e6a88d", true, SignaturePolicy.COMPATIBLE), primaryAead = true) }
        assertEquals(154, air.price(AirSlot.PRIMARY, PacketKind.BROADCAST, text).signedUpTo)
        assertEquals(knitCliff, air.price(AirSlot.KNIT, PacketKind.BROADCAST, knit).signedUpTo)
        assertEquals(154, air.snapshot(0L).roomSignedUpTo)
        assertEquals(knitCliff, air.snapshot(0L).knitSignedUpTo)
        // A 154 B post is signed air and 12 B of tag; a 160 B one prices unsigned (the composer never sends it: it would
        // draw TOO_LARGE).
        val room = air.price(AirSlot.PRIMARY, PacketKind.BROADCAST, text)
        assertEquals(air.timeOnAirMs(154 + 12 + MeshtasticProto.XEDDSA_SIGNATURE_FIELD, u), air.timeOnAirMs(154, room))
        assertEquals(air.timeOnAirMs(155 + 12, u), air.timeOnAirMs(155, room))
        // 2.8.2 under COMPATIBLE: neither slot signs, so the codec has nothing to pad past and the snapshot says so.
        val compatible = LoraAirtime().apply { onBoard(SigningFacts("2.8.2.34cb8d5", true, SignaturePolicy.COMPATIBLE)) }
        assertFalse(compatible.snapshot(0L).signing)
        assertEquals(u, compatible.snapshot(0L).roomSignedUpTo)
        assertEquals(knitCliff, compatible.padTo(knitCliff, MeshtasticProto.MAX_PAYLOAD))
        assertEquals(compatible.timeOnAirMs(157, u), compatible.timeOnAirMs(157))
        // A new handshake replaces the snapshot whole.
        compatible.onBoard(SigningFacts("2.8.2.34cb8d5", true, SignaturePolicy.BALANCED))
        assertTrue(compatible.snapshot(0L).signing)
        assertEquals(knitCliff + 1, compatible.padTo(knitCliff, MeshtasticProto.MAX_PAYLOAD))
    }

    @Test
    fun a28BoardIsChargedForTheSignatureItAddsToOurSmallPackets() {
        val old =
            LoraAirtime().apply {
                onRadioConfig(radio())
                onBoard(SigningFacts("2.7.26.b246bcd"))
            }
        val new =
            LoraAirtime().apply {
                onRadioConfig(radio())
                onBoard(SigningFacts("2.8.0.7239fe8"))
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
                onBoard(SigningFacts("2.7.26.b246bcd"))
            }
        val new =
            LoraAirtime().apply {
                onRadioConfig(radio())
                onBoard(SigningFacts("2.8.0.7239fe8"))
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
            onBoard(SigningFacts("2.8.0.7239fe8"))
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
                onBoard(SigningFacts("2.7.26.b246bcd"))
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
                    onBoard(SigningFacts(firmware))
                }
            var n = 0
            while (air.admits(Ceiling.FULL, listOf(157), 0L)) {
                air.record(157, 0L)
                n++
            }
            return n
        }
        assertTrue(ticksPerWindow("2.8.0.7239fe8") < ticksPerWindow("2.7.26.b246bcd"))
    }

    @Test
    fun aTextPostAtItsOwnCliffIsPricedAsSignedAir() {
        // The room's TEXT_MESSAGE_APP posts sign one byte further than a Knit frame (166 against 165): a
        // post at the cap must be booked with the signature the board adds, not as the unsigned packet a
        // Knit frame of that size would be.
        val air =
            LoraAirtime().apply {
                onRadioConfig(radio())
                onBoard(SigningFacts("2.8.0.47db0e3"))
            }
        val textCliff = MeshtasticProto.maxSignedPayload(MeshtasticProto.PORT_TEXT_MESSAGE)
        assertEquals(166, textCliff)
        assertEquals(air.timeOnAirMs(166 + MeshtasticProto.XEDDSA_SIGNATURE_FIELD), air.timeOnAirMs(166, signedUpTo = textCliff))
        assertTrue(air.timeOnAirMs(166, signedUpTo = textCliff) > air.timeOnAirMs(166))
        // One byte past the text cliff is unsigned on either reading.
        assertEquals(air.timeOnAirMs(167), air.timeOnAirMs(167, signedUpTo = textCliff))
    }

    @Test
    fun theBoardsOwnCapabilityFlagOutranksTheVersionParse() {
        // `DeviceMetadata.has_xeddsa` is the build's own word; the version is the fallback for firmware too
        // old to carry the field.
        assertFalse(LoraAirtime().apply { onBoard(SigningFacts("2.8.0.47db0e3", hasXeddsa = false)) }.snapshot(0L).signing)
        assertTrue(LoraAirtime().apply { onBoard(SigningFacts("2.7.9", hasXeddsa = true)) }.snapshot(0L).signing)
        assertTrue(LoraAirtime().apply { onBoard(SigningFacts("2.8.0.47db0e3", hasXeddsa = null)) }.snapshot(0L).signing)
        assertFalse(LoraAirtime().apply { onBoard(SigningFacts("2.7.26.b246bcd", hasXeddsa = null)) }.snapshot(0L).signing)
    }

    @Test
    fun theSnapshotReportsWhetherTheSignatureIsBeingChargedFor() {
        assertTrue(LoraAirtime().snapshot(0L).signing)
        assertFalse(LoraAirtime().apply { onBoard(SigningFacts("2.7.26.b246bcd")) }.snapshot(0L).signing)
    }

    @Test
    fun aRefusalSaysWhichLimitSaidNo() {
        val air = LoraAirtime()
        val packet = listOf(MeshtasticProto.MAX_PAYLOAD)
        assertNull(air.refusal(Ceiling.FULL, packet, now = 0))
        var now = 0L
        while (air.admits(Ceiling.HALF, packet, now)) {
            air.record(MeshtasticProto.MAX_PAYLOAD, now)
            now += 3_000
        }
        assertEquals("the foreign audience's half", LoraAirtime.Refusal.CEILING, air.refusal(Ceiling.HALF, packet, now))
        assertNull("a Knit frame still has the rest of the total", air.refusal(Ceiling.FULL, packet, now))
        while (air.admits(Ceiling.FULL, packet, now)) {
            air.record(MeshtasticProto.MAX_PAYLOAD, now)
            now += 3_000
        }
        assertEquals(LoraAirtime.Refusal.TOTAL, air.refusal(Ceiling.FULL, packet, now))
        assertEquals("the total outranks the ceiling", LoraAirtime.Refusal.TOTAL, air.refusal(Ceiling.HALF, packet, now))
        val tooBig = List((air.allowanceMs() / air.timeOnAirMs(MeshtasticProto.MAX_PAYLOAD) + 1).toInt()) { MeshtasticProto.MAX_PAYLOAD }
        assertEquals(LoraAirtime.Refusal.NEVER, air.refusal(Ceiling.FULL, tooBig, now = 0))
        assertEquals(LoraAirtime.Refusal.NEVER, air.refusal(Ceiling.HALF, tooBig, now = 0))
    }

    @Test
    fun aRefundGivesBackOnlyTheExactBookingsAndNoMoreThanAsked() {
        // A compact DM is booked for every airing at send; its ack refunds the unflown ones (ADR 2026-10.x9fa), matched on
        // when and how much with no bucket to tell them apart (ADR 2026-10.ug4a).
        val air = LoraAirtime()
        val ms = air.timeOnAirMs(60, LoraAirtime.UNSIGNED)
        repeat(5) { air.record(60, 1_000, PacketPrice.UNSIGNED) }
        air.record(60, 2_000, PacketPrice.UNSIGNED) // another send's: not this one's to give back
        air.record(120, 1_000, PacketPrice.UNSIGNED) // same instant, another cost: not this one's either
        val other = air.timeOnAirMs(120, LoraAirtime.UNSIGNED)
        assertEquals(3, air.refund(1_000, ms, 3))
        assertEquals(3 * ms + other, air.snapshot(2_000).usedMs)
        assertEquals("only two of its own were left", 2, air.refund(1_000, ms, 9))
        assertEquals(ms + other, air.snapshot(2_000).usedMs)
    }

    @Test
    fun theContentionSlotIsTheFirmwaresAtThePreset() {
        // `RadioInterface::computeSlotTimeMsec`: 4.5 symbols of an SX126x CAD plus 7.6 ms; LongFast's symbol is 8.192 ms.
        assertEquals(4.5 * 8.192 + 7.6, LoraAirtime().slotTimeMs(), 0.001)
    }
}
