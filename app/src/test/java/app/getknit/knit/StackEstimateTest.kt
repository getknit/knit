package app.getknit.knit

import app.getknit.knit.mesh.LinkOutbound
import app.getknit.knit.mesh.bluetooth.StackDrainPolicy
import app.getknit.knit.mesh.bluetooth.StackEstimate
import app.getknit.knit.mesh.bluetooth.stackOutbound
import app.getknit.knit.mesh.link.FramedLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Bluetooth plane's estimate of what its stack buffer still holds of custody's counted bytes ([StackEstimate]) and
 * the rate it drains at ([StackDrainPolicy]): the custody-carry-policy contract's §2, revision 14, "the estimate".
 */
class StackEstimateTest {
    @Test
    fun nothingHandedDownIsNothingBelow() {
        assertEquals(0L, StackEstimate(0L).below(handedDown = 0L, bytesPerSec = RATE, now = 5_000L))
    }

    @Test
    fun whatWasHandedDownDrainsAtTheRateAndNeverBelowZero() {
        val e = StackEstimate(0L)
        assertEquals(ROUND, e.below(ROUND, RATE, 0L))
        assertEquals(ROUND / 2, e.below(ROUND, RATE, 2_000L)) // 4 KiB/s for 2 s of 16 KiB
        assertEquals(0L, e.below(ROUND, RATE, 4_000L))
        assertEquals("a quiet link holds nothing, not less", 0L, e.below(ROUND, RATE, 60_000L))
        // Handed down after the bucket emptied: no credit carried over from the quiet.
        assertEquals(ROUND, e.below(2 * ROUND, RATE, 60_000L))
    }

    @Test
    fun bytesHandedDownMidDrainAddToWhatIsLeft() {
        val e = StackEstimate(0L)
        e.below(ROUND, RATE, 0L)
        assertEquals(ROUND / 2 + ROUND, e.below(2 * ROUND, RATE, 2_000L))
    }

    @Test
    fun aFasterOrSlowerRateMovesTheNextRoundsTimeInProportion() {
        // What custody's look reads: when the estimate first falls under one round, two rounds having gone down at 0.
        fun underARoundAt(rate: Long): Long {
            val e = StackEstimate(0L)
            e.below(2 * ROUND, rate, 0L)
            var t = 0L
            while (e.below(2 * ROUND, rate, t) >= ROUND) t += 1
            return t
        }
        val base = underARoundAt(RATE)
        assertEquals(4_001L, base)
        assertEquals("twice the rate, half the wait", 2_001L, underARoundAt(2 * RATE))
        assertEquals("half the rate, twice the wait", 8_001L, underARoundAt(RATE / 2))
    }

    @Test
    fun cuttingOnlyUnderARoundNeverPutsTwoRoundsBelow() {
        // Custody's rule over this estimate: a round is cut when what the link holds is under one round, on a look every
        // 100 ms. Whatever the first pick weighed, the buffer then holds under one round plus the round just cut.
        val e = StackEstimate(0L)
        var handed = 0L
        var most = 0L
        for (t in 0L..120_000L step 100L) {
            if (e.below(handed, RATE, t) < ROUND) handed += ROUND
            most = maxOf(most, e.below(handed, RATE, t))
        }
        assertTrue("held at most $most", most < 2 * ROUND)
        // And the link was kept fed: about what 4 KiB/s carries in 120 s, within a round.
        assertTrue("handed $handed", handed >= RATE * 120 - ROUND)
    }

    @Test
    fun noRateNeverDrains() {
        val e = StackEstimate(0L)
        e.below(ROUND, 0L, 0L)
        assertEquals(ROUND, e.below(ROUND, 0L, 600_000L))
    }

    @Test
    fun aReaderWithAnOlderCounterOrClockMovesNothingBack() {
        val e = StackEstimate(0L)
        assertEquals(ROUND, e.below(ROUND, RATE, 1_000L))
        assertEquals("an older read adds and drains nothing", ROUND, e.below(ROUND / 2, RATE, 500L))
        assertEquals(ROUND / 2, e.below(ROUND, RATE, 3_000L))
    }

    @Test
    fun aLongQuietDoesNotOverflow() {
        val e = StackEstimate(0L)
        e.below(ROUND, Long.MAX_VALUE / 4, 0L)
        assertEquals(0L, e.below(ROUND, Long.MAX_VALUE / 4, Long.MAX_VALUE / 2))
        assertEquals(Long.MAX_VALUE, StackEstimate.drained(Long.MAX_VALUE, 2L))
    }

    @Test
    fun aLinkThatCanSayReadsItsQueuePlusTheStacksEstimateWithItsRateAndFile() {
        val e = StackEstimate(0L)
        val out = stackOutbound(queued = 300L, handedDown = ROUND, rate = RATE, estimate = e, now = 0L, fileMoving = true)!!
        assertEquals("the queue and what the stack still holds", 300L + ROUND, out.unwritten)
        assertEquals(RATE, out.drainBytesPerSec)
        assertTrue(out.fileMoving)
        val later = stackOutbound(queued = 0L, handedDown = ROUND, rate = RATE, estimate = e, now = 2_000L, fileMoving = false)!!
        assertEquals(ROUND / 2, later.unwritten)
        assertFalse(later.fileMoving)
    }

    @Test
    fun aLinkWithNoRateSaysNothingRatherThanReadAsDrained() {
        assertNull(stackOutbound(queued = 0L, handedDown = ROUND, rate = null, estimate = StackEstimate(0L), now = 0L, fileMoving = false))
    }

    @Test
    fun anEndedWriterReadsNotDrainingWhateverElseItSays() {
        val ended = FramedLink.NOT_DRAINING
        val out = stackOutbound(ended, handedDown = 0L, rate = RATE, estimate = StackEstimate(0L), now = 0L, fileMoving = true)
        assertEquals(LinkOutbound.NOT_DRAINING, out!!.unwritten)
        assertNull(out.drainBytesPerSec)
        assertEquals(
            "with no rate too",
            LinkOutbound.NOT_DRAINING,
            stackOutbound(FramedLink.NOT_DRAINING, 0L, rate = null, estimate = null, now = 0L, fileMoving = false)!!.unwritten,
        )
    }

    @Test
    fun aLinkTornDownBetweenItsReadsReadsNotDraining() {
        val out = stackOutbound(queued = 0L, handedDown = 0L, rate = RATE, estimate = null, now = 0L, fileMoving = false)
        assertEquals(LinkOutbound.NOT_DRAINING, out!!.unwritten)
    }

    @Test
    fun codedTakesItsOwnPace() {
        assertEquals(1_024L, rate(codedPace = 1_024, drained = 30_000, windowed = true, earned = true))
    }

    @Test
    fun aMeasuredWindowedDrainIsTheRateSharedWithTheOtherWindowedFeeds() {
        assertEquals(20_000L, rate(drained = 20_000, windowed = true, earned = true))
        assertEquals(10_000L, rate(drained = 20_000, windowed = true, earned = true, others = 1))
    }

    @Test
    fun aDrainPerhapsMeasuredBoostedIsReadNoFasterThanThePacedShareOnceOffTheBoost() {
        // Back at BALANCED after a boosted photo: what the link measured then says nothing of now.
        assertEquals(PACED.toLong(), rate(drained = 20_000, windowed = true, boostedOnce = true, earned = false))
        // A slower measured drain stands: it was the link's own, and the paced share would overstate it.
        assertEquals(3_000L, rate(drained = 3_000, windowed = true, boostedOnce = true, earned = false))
        // Still holding the boost: the measured drain stands.
        assertEquals(20_000L, rate(drained = 20_000, windowed = true, boostedOnce = true, earned = true))
        // A link that never had a boost (an iPhone's, or a build without the flag) measured its drain at rest: it stands.
        assertEquals(20_000L, rate(drained = 20_000, windowed = true, boostedOnce = false, earned = false))
    }

    @Test
    fun aLinkThatCanNoLongerEarnTheBoostIsStillClampedOffIt() {
        // The bluetooth-reviewer's case: a photo streamed boosted (~18 KiB/s), then A2DP started. The link can't earn the
        // boost now (audio), but it has had one, and its 18 KiB/s drain must not stand at BALANCED beside the music.
        assertEquals(PACED.toLong(), rate(drained = 18_432, windowed = true, boostedOnce = true, earned = false))
    }

    @Test
    fun aFileTheLinkStreamsItselfTakesItsShareOfTheStack() {
        assertEquals("Coded, beside its own file", 512L, rate(codedPace = 1_024, ownFeed = true))
        assertEquals(
            "a windowed drain, beside its own file",
            10_000L,
            rate(drained = 20_000, windowed = true, earned = true, ownFeed = true),
        )
        assertEquals(
            "beside its own file and another windowed feed",
            6_666L,
            rate(drained = 20_000, windowed = true, earned = true, others = 1, ownFeed = true),
        )
    }

    @Test
    fun beforeAnyMeasureTheRateIsTheFeedsConfiguredPace() {
        assertEquals(PACED.toLong(), rate())
        assertEquals("a link that drained short of its pace", PACED / 2L, rate(drainRatio = 0.5))
        assertEquals(BOOSTED.toLong(), rate(boostedOnce = true, earned = true))
        assertEquals("a drain measured on a paced feed isn't windowed", PACED.toLong(), rate(drained = 20_000))
    }

    @Test
    fun noRateAtAllIsNoEstimate() {
        assertNull(rate(paced = 0))
        assertNull(rate(earned = true, boosted = 0))
    }

    @Suppress("LongParameterList") // the policy's inputs, each with the plain link's default
    private fun rate(
        codedPace: Int? = null,
        drained: Int = 0,
        windowed: Boolean = false,
        others: Int = 0,
        ownFeed: Boolean = false,
        drainRatio: Double = 1.0,
        boostedOnce: Boolean = false,
        earned: Boolean = false,
        boosted: Int = BOOSTED,
        paced: Int = PACED,
    ): Long? =
        StackDrainPolicy.rate(
            codedPace = codedPace,
            drained = drained,
            windowed = windowed,
            otherWindowedFeeds = { others },
            ownFeed = ownFeed,
            drainRatio = drainRatio,
            mayHaveBoosted = boostedOnce,
            earned = earned,
            boosted = { boosted },
            pacedShare = { paced },
        )

    private companion object {
        const val ROUND = 16_384L
        const val RATE = 4_096L
        const val PACED = 4_096
        const val BOOSTED = 18_432
    }
}
