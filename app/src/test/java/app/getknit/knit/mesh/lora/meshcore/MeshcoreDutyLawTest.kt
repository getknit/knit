package app.getknit.knit.mesh.lora.meshcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MeshcoreDutyLaw]: the board's own trailing-hour `tx_air`, as Knit observed it rise, against the band's duty — the
 * window, the resets on a reboot, and no hold on air nobody saw (ADR 2026-11.gsqu's Stage 5a-1 amendment, as the
 * maintainer amended it on 2026-11-24).
 */
class MeshcoreDutyLawTest {
    /** The phone's wall clock when the board booted. */
    private val boot = 1_790_000_000_000L

    private fun MeshcoreDutyLaw.at(
        uptimeSecs: Long,
        txAirSecs: Long,
        bootWall: Long = boot,
    ) = onSample(bootWall + uptimeSecs * 1_000, uptimeSecs, txAirSecs)

    @Test
    fun aLivePreBootTermAnswersAloneBeforeAnySampleOfThisBoot() {
        // A restore that kept only the pre-boot term (no usable ring) is not "nothing known" while that hour is spent.
        val law = MeshcoreDutyLaw()
        val until = boot + 3_600_000L
        law.restore(MeshcoreDutyLaw.State(emptyList(), listOf(MeshcoreDutyLaw.PreBoot(txSecs = 100, untilWallMs = until))))
        val held = law.verdict(1.0, wallNowMs = boot)!!
        assertTrue(held.holding)
        assertEquals(3_600L, held.releaseInSecs)
        assertNotNull("under the duty it still answers", law.verdict(10.0, wallNowMs = boot))
        assertFalse(law.verdict(10.0, wallNowMs = boot)!!.holding)
        assertFalse("a band with no duty limit never holds on it", law.verdict(100.0, wallNowMs = boot)!!.holding)
        assertNull("past its deadline, nothing is known", law.verdict(1.0, wallNowMs = until))
        assertNull("no wall clock, no term", law.verdict(1.0))
    }

    @Test
    fun nothingIsKnownBeforeASample() {
        val law = MeshcoreDutyLaw()
        assertNull(law.trailingTxSecs())
        assertNull(law.verdict(1.0))
    }

    /** Regression (b): a first connection to a board up and transmitting for hours starts at zero, never at its counter. */
    @Test
    fun aFirstSampleCountsNothingWhateverTheCounterSinceBootReads() {
        val law = MeshcoreDutyLaw()
        law.at(uptimeSecs = 6 * 3_600, txAirSecs = 9_000)
        assertEquals("nothing observed yet", 0L, law.trailingTxSecs())
        assertFalse(law.verdict(0.1)!!.holding)
        assertFalse(law.verdict(1.0)!!.holding)
        // Only what it is then seen to rise counts.
        law.at(uptimeSecs = 6 * 3_600 + 60, txAirSecs = 9_010)
        assertEquals("the 10 s seen, and a second for the truncation", 11L, law.trailingTxSecs())
        assertFalse(law.verdict(1.0)!!.holding)
    }

    @Test
    fun withinTheFirstHourOfABootOnlyTheObservedRiseIsTheWindow() {
        val law = MeshcoreDutyLaw()
        law.at(uptimeSecs = 600, txAirSecs = 20)
        assertEquals("the 20 s before our first sample are not ours to count", 0L, law.trailingTxSecs())
        val verdict = law.verdict(1.0)!!
        assertEquals(36.0, verdict.limitSecs, 0.0)
        assertFalse(verdict.holding)
        law.at(uptimeSecs = 900, txAirSecs = 60)
        assertEquals(41L, law.trailingTxSecs())
        assertTrue("41 s seen of a 36-s hour: every Knit frame held", law.verdict(1.0)!!.holding)
        assertFalse("a 10 % band has room", law.verdict(10.0)!!.holding)
    }

    /** Regression (c): a 1 % band holds once the observed hour reaches 36 s, and lifts as that air leaves the window. */
    @Test
    fun aOnePercentBandHoldsOnObservedAirAndLiftsAsItLeavesTheWindow() {
        val law = MeshcoreDutyLaw()
        law.at(uptimeSecs = 5_000, txAirSecs = 1_000)
        // 35 s seen over ten minutes: with the truncation second, exactly the 36-s limit.
        for (minute in 1..10L) law.at(uptimeSecs = 5_000 + minute * 60, txAirSecs = 1_000 + minute * 35 / 10)
        assertEquals(36L, law.trailingTxSecs())
        val held = law.verdict(1.0)!!
        assertTrue(held.holding)
        assertNotNull(held.releaseInSecs)
        // Quiet from then on: still held while the burst is inside the hour…
        for (minute in 11..60L) law.at(uptimeSecs = 5_000 + minute * 60, txAirSecs = 1_035)
        assertTrue("the burst still inside the hour", law.verdict(1.0)!!.holding)
        // …and lifted once enough of it has left the window.
        for (minute in 61..70L) law.at(uptimeSecs = 5_000 + minute * 60, txAirSecs = 1_035)
        assertFalse(law.verdict(1.0)!!.holding)
        assertEquals("quiet for an hour: only the truncation second", 1L, law.trailingTxSecs())
    }

    /** Regression (d): a band with no duty limit never holds, however much air is seen. */
    @Test
    fun aHundredPercentBandNeverHoldsEvenOnHeavyObservedAir() {
        val law = MeshcoreDutyLaw()
        law.at(uptimeSecs = 100, txAirSecs = 0)
        law.at(uptimeSecs = 3_700, txAirSecs = 3_600) // the radio never stopped
        val verdict = law.verdict(100.0)!!
        assertEquals(3_601L, verdict.trailingTxSecs)
        assertEquals(3_600.0, verdict.limitSecs, 0.0)
        assertFalse(verdict.holding)
        assertNull(verdict.releaseInSecs)
        assertTrue("the same air on a 10 % band holds", law.verdict(10.0)!!.holding)
    }

    @Test
    fun theWindowTrailsTheBoardsUptime() {
        val law = MeshcoreDutyLaw()
        // Two hours of polls a minute apart, a second of air each minute: the trailing hour holds 60 s of it.
        for (minute in 0..120L) law.at(uptimeSecs = minute * 60, txAirSecs = minute)
        assertEquals(61L, law.trailingTxSecs())
        // A burst of 30 s, then quiet: it stays in the window for the hour and falls out after.
        law.at(uptimeSecs = 121 * 60, txAirSecs = 121 + 30)
        assertTrue(law.verdict(10.0)!!.trailingTxSecs >= 90)
        for (minute in 122..180L) law.at(uptimeSecs = minute * 60, txAirSecs = 151)
        assertTrue("the burst still inside the hour", law.trailingTxSecs()!! >= 31)
        for (minute in 181..190L) law.at(uptimeSecs = minute * 60, txAirSecs = 151)
        assertEquals("quiet for over an hour: only the truncation second", 1L, law.trailingTxSecs())
        assertFalse(law.verdict(0.1)!!.holding)
        assertNull("nothing held, nothing to release", law.verdict(0.1)!!.releaseInSecs)
    }

    @Test
    fun aHoldSaysWhenTheWindowCanFallByItself() {
        val law = MeshcoreDutyLaw()
        law.at(uptimeSecs = 0, txAirSecs = 0)
        law.at(uptimeSecs = 3_600, txAirSecs = 0)
        law.at(uptimeSecs = 3_660, txAirSecs = 40) // a minute of 40 s: at the 1 % band's 36
        law.at(uptimeSecs = 3_720, txAirSecs = 40)
        val verdict = law.verdict(1.0)!!
        assertTrue(verdict.holding)
        // The 40 s rose between 3,600 and 3,660: once the window's start is 6 s past 3,600, at least 34 of it is inside.
        assertEquals("the least of the burst left inside the window falls under 36 s", 3_486L, verdict.releaseInSecs)
    }

    /**
     * A reboot zeroes `tx_air` and starts a new ring. What the old boot was seen to spend inside its last hour rides along
     * on the wall clock as a pre-boot term until an hour after our last sample of it; the stretch after that sample, which
     * nobody saw, adds nothing.
     */
    @Test
    fun aRebootCarriesOnlyTheOldBootsObservedHourOnTheWallClock() {
        val law = MeshcoreDutyLaw()
        for (minute in 0..90L) law.at(uptimeSecs = minute * 60, txAirSecs = 100 + minute)
        assertEquals("the old boot's hour: 60 s seen", 61L, law.trailingTxSecs())
        val lastWall = boot + 90 * 60_000L
        val rebootWall = boot + 91 * 60_000L
        law.at(uptimeSecs = 30, txAirSecs = 2, bootWall = rebootWall) // the board rebooted
        assertEquals("a new ring", 1, law.snapshot().samples.size)
        assertEquals(
            "the old boot's 61 alone, an hour past our last sample of it",
            listOf(MeshcoreDutyLaw.PreBoot(61, lastWall + 60 * 60_000L)),
            law.snapshot().preBoots,
        )
        assertEquals("this boot's first sample counts nothing; the carried 61 does", 61L, law.trailingTxSecs())
        assertTrue("held on a 1 % band, as it was before the reboot", law.verdict(1.0)!!.holding)
        assertEquals("held until the term lapses", 60 * 60L - 90, law.verdict(1.0)!!.releaseInSecs)
        law.at(uptimeSecs = 30 + 58 * 60, txAirSecs = 2, bootWall = rebootWall)
        assertTrue("short of the hour: still carried", law.trailingTxSecs()!! > 60)
        law.at(uptimeSecs = 30 + 59 * 60, txAirSecs = 2, bootWall = rebootWall)
        assertEquals("an hour past the last sample of the old boot: gone", 1L, law.trailingTxSecs())
        assertFalse(law.verdict(1.0)!!.holding)

        val again = MeshcoreDutyLaw()
        again.at(uptimeSecs = 4_000, txAirSecs = 500)
        again.at(uptimeSecs = 4_060, txAirSecs = 499) // a counter backwards: a different boot
        assertEquals(1, again.snapshot().samples.size)
        assertEquals(
            "one sample of the old boot saw nothing rise: nothing carried",
            emptyList<MeshcoreDutyLaw.PreBoot>(),
            again.snapshot().preBoots,
        )
    }

    /**
     * Regression (a), the field case: a US 915 board's battery ran flat long after our last sample, and it was replugged.
     * Nothing about the gap is evidence, so nothing is carried, and a 100 % band would never hold anyway.
     */
    @Test
    fun aRebootAfterALongUnobservedGapHoldsNothing() {
        val last = MeshcoreDutyLaw()
        for (minute in 0..10L) last.at(uptimeSecs = 20_000 + minute * 60, txAirSecs = 3_000)
        val saved = last.snapshot()
        val lastWall = boot + (20_000 + 600) * 1_000L

        val law = MeshcoreDutyLaw()
        law.restore(saved)
        val rebootWall = lastWall + 84 * 60_000L // flat at 15:06, booted 16:30
        law.at(uptimeSecs = 60, txAirSecs = 1, bootWall = rebootWall)
        assertEquals("a quiet old boot and an unseen gap carry nothing", emptyList<MeshcoreDutyLaw.PreBoot>(), law.snapshot().preBoots)
        assertEquals(0L, law.trailingTxSecs())
        assertFalse(law.verdict(100.0)!!.holding)
        assertFalse("not even a 0.1 % band holds on air nobody saw", law.verdict(0.1)!!.holding)
    }

    @Test
    fun anAppRestartKeepsTheObservedBaselineAndABareStartCountsOnlyWhatItSees() {
        // The last process watched the board for two hours: 40 s of air in the last ten minutes of it.
        val last = MeshcoreDutyLaw()
        for (minute in 0..110L) last.at(uptimeSecs = minute * 60, txAirSecs = 1_000)
        for (minute in 111..120L) last.at(uptimeSecs = minute * 60, txAirSecs = 1_000 + (minute - 110) * 4)
        assertTrue(last.verdict(1.0)!!.holding)
        val saved = last.snapshot()

        // A new process with nothing restored saw none of it: nothing is held on the counter since boot.
        val bare = MeshcoreDutyLaw()
        bare.at(uptimeSecs = 121 * 60, txAirSecs = 1_040)
        assertFalse(bare.verdict(1.0)!!.holding)
        assertEquals(0L, bare.trailingTxSecs())

        // With the ring restored the baseline is back: two of our samples of one boot, across the restart, are evidence.
        val restored = MeshcoreDutyLaw()
        restored.restore(saved)
        restored.at(uptimeSecs = 121 * 60, txAirSecs = 1_040)
        assertEquals(41L, restored.trailingTxSecs())
        assertTrue(restored.verdict(1.0)!!.holding)

        // A ring from another boot (the board rebooted while the app was away) is dropped at the first sample.
        val stale = MeshcoreDutyLaw()
        stale.restore(saved)
        stale.at(uptimeSecs = 121 * 60, txAirSecs = 1_040, bootWall = boot + 10 * 60_000)
        assertEquals(1, stale.snapshot().samples.size)
        assertEquals(
            "the restored ring's observed hour carried past the other boot",
            41L,
            stale
                .snapshot()
                .preBoots
                .single()
                .txSecs,
        )
        assertEquals(
            boot + 120 * 60_000L + 60 * 60_000L,
            stale
                .snapshot()
                .preBoots
                .single()
                .untilWallMs,
        )
    }

    /**
     * Reboots inside the hour: each boot's observed term keeps its own deadline (lora-reviewer, 2026-11-24), so an earlier
     * boot's air never counts past its own hour by riding a later boot's term.
     */
    @Test
    fun eachRebootsTermKeepsItsOwnDeadline() {
        val law = MeshcoreDutyLaw()
        law.at(uptimeSecs = 1_000, txAirSecs = 0)
        law.at(uptimeSecs = 1_060, txAirSecs = 20)
        val firstLast = boot + 1_060_000L
        val second = firstLast + 60_000L
        law.at(uptimeSecs = 10, txAirSecs = 0, bootWall = second)
        val first = MeshcoreDutyLaw.PreBoot(21, firstLast + 3_600_000L)
        assertEquals(listOf(first), law.snapshot().preBoots)
        // Quiet second boot, then another reboot: nothing added, the first term as it was.
        law.at(uptimeSecs = 70, txAirSecs = 0, bootWall = second)
        val third = second + 120_000L
        law.at(uptimeSecs = 5, txAirSecs = 0, bootWall = third)
        assertEquals(listOf(first), law.snapshot().preBoots)
        // A busy third boot, then a fourth: two terms, each to its own deadline.
        law.at(uptimeSecs = 65, txAirSecs = 10, bootWall = third)
        val fourth = third + 120_000L
        law.at(uptimeSecs = 2, txAirSecs = 0, bootWall = fourth)
        val thirds = MeshcoreDutyLaw.PreBoot(11, third + 65_000L + 3_600_000L)
        assertEquals(listOf(first, thirds), law.snapshot().preBoots)
        assertEquals(32L, law.trailingTxSecs())
        // Past the first boot's hour only the third's 11 s count, though the third's term lives on.
        law.at(uptimeSecs = (firstLast + 3_600_000L - fourth) / 1_000, txAirSecs = 0, bootWall = fourth)
        assertEquals(11L + 1, law.trailingTxSecs())
        // On a 1 % band, 32 s of 36 never held; a term alone at the limit holds only to its own deadline.
        val held = MeshcoreDutyLaw()
        held.restore(
            MeshcoreDutyLaw.State(
                emptyList(),
                listOf(MeshcoreDutyLaw.PreBoot(30, boot + 600_000L), MeshcoreDutyLaw.PreBoot(10, boot + 1_800_000L)),
            ),
        )
        val verdict = held.verdict(1.0, wallNowMs = boot)!!
        assertTrue(verdict.holding)
        assertEquals("the 30-s term's deadline lifts it: 10 s alone are under 36", 600L, verdict.releaseInSecs)
    }

    /**
     * A long gap inside one boot (the app away, the ring persisted): the rise between two of our samples hours apart is
     * placed as early as the clock allows, and only what cannot fit before the window's start counts.
     */
    @Test
    fun aLongGapBetweenTwoSamplesCountsOnlyTheAirThatMustBeInsideTheHour() {
        val law = MeshcoreDutyLaw()
        law.at(uptimeSecs = 1_000, txAirSecs = 0)
        law.at(uptimeSecs = 1_000 + 5 * 3_600, txAirSecs = 100)
        assertEquals("100 s over five hours can all predate the window: only the truncation second", 1L, law.trailingTxSecs())
        assertFalse(law.verdict(1.0)!!.holding)
        val tight = MeshcoreDutyLaw()
        tight.at(uptimeSecs = 1_000, txAirSecs = 0)
        tight.at(uptimeSecs = 1_000 + 3_700, txAirSecs = 200)
        assertEquals("100 s of the 200 can predate the window, the rest is inside it", 101L, tight.trailingTxSecs())
        assertTrue(tight.verdict(1.0)!!.holding)
        assertEquals("it falls under 36 s as the window's start moves 66 s on", 66L, tight.verdict(1.0)!!.releaseInSecs)
    }

    /** A boot loop with air in every boot keeps at most [MeshcoreDutyLaw.MAX_PRE_BOOTS] terms, the latest. */
    @Test
    fun theTermsStayBoundedThroughABootLoop() {
        val law = MeshcoreDutyLaw()
        for (i in 0 until 20L) {
            val bootWall = boot + i * 300_000L
            law.at(uptimeSecs = 10, txAirSecs = 0, bootWall = bootWall)
            law.at(uptimeSecs = 70, txAirSecs = 1, bootWall = bootWall)
        }
        law.at(uptimeSecs = 1, txAirSecs = 0, bootWall = boot + 20 * 300_000L)
        val terms = law.snapshot().preBoots
        assertEquals(MeshcoreDutyLaw.MAX_PRE_BOOTS, terms.size)
        assertEquals(boot + 19 * 300_000L + 70_000L + 3_600_000L, terms.last().untilWallMs)
    }

    @Test
    fun theRingStaysBoundedWhateverThePollRate() {
        val law = MeshcoreDutyLaw()
        for (second in 0..(3 * 3_600L)) law.at(uptimeSecs = second, txAirSecs = second / 10)
        assertTrue("about one a minute for the hour", law.snapshot().samples.size <= 3_600 / 60 + 3)
    }
}
