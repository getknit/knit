package app.getknit.knit.mesh.lora.meshcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MeshcoreDutyLaw]: the board's own trailing-hour `tx_air` against the band's duty — the window, the resets on a reboot,
 * and never a fresh allowance on an app restart (ADR 2026-11.gsqu's Stage 5a-1 amendment).
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
    fun nothingIsKnownBeforeASample() {
        val law = MeshcoreDutyLaw()
        assertNull(law.trailingTxSecs())
        assertNull(law.verdict(1.0))
    }

    @Test
    fun withinTheFirstHourOfABootTheCounterItselfIsTheWindow() {
        val law = MeshcoreDutyLaw()
        law.at(uptimeSecs = 600, txAirSecs = 20)
        assertEquals("all of it, and a second for the truncation", 21L, law.trailingTxSecs())
        val verdict = law.verdict(1.0)!!
        assertEquals(36.0, verdict.limitSecs, 0.0)
        assertFalse(verdict.holding)
        law.at(uptimeSecs = 900, txAirSecs = 40)
        assertTrue("41 s of a 36-s hour: every Knit frame held", law.verdict(1.0)!!.holding)
        assertFalse("a 10 % band has room", law.verdict(10.0)!!.holding)
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
        assertEquals("the burst leaves the window an hour after it ended", 3_540L, verdict.releaseInSecs)
    }

    /**
     * A reboot zeroes `tx_air` and starts a new ring, but never a fresh hour: the old boot's last-hour bound rides along
     * on the wall clock as a pre-boot term, until an hour after the old boot's last sample.
     */
    @Test
    fun aRebootStartsANewRingAndCarriesTheOldBootsHourOnTheWallClock() {
        val law = MeshcoreDutyLaw()
        for (minute in 0..90L) law.at(uptimeSecs = minute * 60, txAirSecs = 100 + minute)
        assertEquals("the old boot's hour: 60 s", 61L, law.trailingTxSecs())
        val rebootWall = boot + 91 * 60_000L
        law.at(uptimeSecs = 30, txAirSecs = 2, bootWall = rebootWall) // the board rebooted
        assertEquals("a new ring", 1, law.snapshot().samples.size)
        // The old boot's 61, and the 60 s between our last sample of it and the reboot, unseen: an hour past the new boot.
        assertEquals(MeshcoreDutyLaw.PreBoot(61 + 60, rebootWall + 60 * 60_000L), law.snapshot().preBoot)
        assertEquals("this boot's 2 s, the truncation, and the carried 121", 124L, law.trailingTxSecs())
        assertTrue("held on a 1 % band, as it was before the reboot", law.verdict(1.0)!!.holding)
        // Held until the pre-boot term lapses an hour after the reboot (the new boot spent too little to hold it).
        assertEquals(60 * 60L - 30, law.verdict(1.0)!!.releaseInSecs)
        law.at(uptimeSecs = 30 + 58 * 60, txAirSecs = 2, bootWall = rebootWall)
        assertTrue("short of the hour: still carried", law.trailingTxSecs()!! > 60)
        law.at(uptimeSecs = 30 + 60 * 60, txAirSecs = 2, bootWall = rebootWall)
        assertEquals("an hour past the reboot: gone", 1L, law.trailingTxSecs())
        assertFalse(law.verdict(1.0)!!.holding)

        val again = MeshcoreDutyLaw()
        again.at(uptimeSecs = 4_000, txAirSecs = 500)
        again.at(uptimeSecs = 4_060, txAirSecs = 499) // a counter backwards: a different boot
        assertEquals(1, again.snapshot().samples.size)
        assertEquals("the old boot's 500 s, bounded by the hour since boot it can have spent", 501L, again.snapshot().preBoot?.txSecs)
    }

    @Test
    fun anAppRestartNeverHandsOutAFreshAllowance() {
        // The last process watched the board for two hours: 40 s of air in the last ten minutes of it.
        val last = MeshcoreDutyLaw()
        for (minute in 0..110L) last.at(uptimeSecs = minute * 60, txAirSecs = 1_000)
        for (minute in 111..120L) last.at(uptimeSecs = minute * 60, txAirSecs = 1_000 + (minute - 110) * 4)
        assertTrue(last.verdict(1.0)!!.holding)
        val saved = last.snapshot()

        // A new process with nothing restored bounds the hour by what it can know: all of the board's 1,040 s since boot
        // could be inside it (the hour bound, 3,600 s, is the looser), so it holds — never a fresh window.
        val bare = MeshcoreDutyLaw()
        bare.at(uptimeSecs = 121 * 60, txAirSecs = 1_040)
        assertTrue("held, never a fresh window", bare.verdict(1.0)!!.holding)
        assertEquals(1_041L, bare.trailingTxSecs())
        val busy = MeshcoreDutyLaw()
        busy.at(uptimeSecs = 121 * 60, txAirSecs = 9_000)
        assertEquals("a board that sent more than an hour of air since boot: the hour itself bounds it", 3_601L, busy.trailingTxSecs())

        // With the ring restored the baseline is back: the same 41 s as before the restart.
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
        assertEquals("the restored ring's hour carried past the other boot", 41L, stale.snapshot().preBoot?.txSecs)
    }

    /**
     * The app was away while the board stayed busy: our last sample of the old boot at T, the board transmitting until
     * it reboots at T + 50 min, the app back at T + 55. The 50 minutes nobody watched count as possibly spent (the
     * counter rises no faster than the clock), beside the old boot's own last hour.
     */
    @Test
    fun theStretchBetweenTheLastSampleAndARebootCountsAsPossiblySpent() {
        val last = MeshcoreDutyLaw()
        for (minute in 0..120L) last.at(uptimeSecs = minute * 60, txAirSecs = minute / 6) // 10 s in the last hour
        val saved = last.snapshot()
        val lastWall = boot + 120 * 60_000L

        val law = MeshcoreDutyLaw()
        law.restore(saved)
        val rebootWall = lastWall + 50 * 60_000L
        law.at(uptimeSecs = 5 * 60, txAirSecs = 0, bootWall = rebootWall)
        val pre = law.snapshot().preBoot!!
        assertEquals("the old hour's 11 and the 3,000 unseen seconds", 11L + 3_000, pre.txSecs)
        assertEquals("an hour after the reboot", rebootWall + 60 * 60_000L, pre.untilWallMs)
        assertTrue("held on a 10 % band: 3,012 s of a 360-s hour", law.verdict(10.0)!!.holding)
        // A gap longer than the hour is bounded by the hour.
        val longer = MeshcoreDutyLaw()
        longer.restore(saved)
        longer.at(uptimeSecs = 60, txAirSecs = 0, bootWall = lastWall + 5 * 60 * 60_000L)
        assertEquals(11L + 3_600, longer.snapshot().preBoot!!.txSecs)
    }

    @Test
    fun theRingStaysBoundedWhateverThePollRate() {
        val law = MeshcoreDutyLaw()
        for (second in 0..(3 * 3_600L)) law.at(uptimeSecs = second, txAirSecs = second / 10)
        assertTrue("about one a minute for the hour", law.snapshot().samples.size <= 3_600 / 60 + 3)
    }
}
