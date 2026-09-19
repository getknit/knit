package app.getknit.knit

import app.getknit.knit.mesh.wifiaware.NanInitiatorPolicy
import app.getknit.knit.mesh.wifiaware.NanInitiatorPolicy.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [NanInitiatorPolicy] — the failsafe that stops a phone initiating Wi-Fi Aware data paths when
 * its own Wi-Fi drops each time it tries (work item #78, the Pixel 3). The assertions are about evidence: what
 * a Wi-Fi loss has to look like to count, what it is charged to, and the one thing that refunds it.
 */
class NanInitiatorPolicyTest {
    // Two clocks, as in the transport: a monotonic one for the windows and a wall clock for the daily probe.
    private var elapsed = 1_000_000L
    private var wall = 1_700_000_000_000L

    private fun policy() = NanInitiatorPolicy(now = { elapsed }, wallNow = { wall })

    private fun tick(ms: Long) {
        elapsed += ms
        wall += ms
    }

    /** The field shape: an initiate, the firmware's reject 30–100 s later, the STA down and back in ~4 s. */
    private fun NanInitiatorPolicy.dropAfterInitiate(
        rejectAfterMs: Long,
        backAfterMs: Long = 4_000,
    ): Verdict {
        noteInitiate()
        tick(rejectAfterMs)
        noteWifiLost()
        tick(backAfterMs)
        return noteWifiAvailable()
    }

    @Test
    fun thePixel3NightLatchesAtTheThirdDrop() {
        val p = policy()
        // 01:48, 01:53, 02:06: each drop 30–100 s after an initiate, each back within four seconds.
        assertEquals(Verdict.Strike, p.dropAfterInitiate(rejectAfterMs = 31_000))
        assertEquals(1, p.strikes)
        tick(5 * 60_000)
        assertEquals(Verdict.Strike, p.dropAfterInitiate(rejectAfterMs = 45_000))
        assertEquals(2, p.strikes)
        tick(13 * 60_000)
        assertEquals(Verdict.Latched, p.dropAfterInitiate(rejectAfterMs = 100_000))
        assertTrue(p.latched)
        // 03:46 — a fourth, under the hold (a probe went out): judged, not counted twice.
        tick(100 * 60_000)
        assertEquals(Verdict.AlreadyHeld, p.dropAfterInitiate(rejectAfterMs = 40_000))
        assertEquals(3, p.strikes)
        assertTrue(p.latched)
    }

    @Test
    fun walkingOutOfRangeIsNotABlip() {
        val p = policy()
        p.noteInitiate()
        tick(30_000)
        p.noteWifiLost()
        tick(NanInitiatorPolicy.BLIP_MAX_MS + 5_000) // the AP is out of range; it comes back much later
        assertEquals(Verdict.None, p.noteWifiAvailable())
        assertEquals(0, p.strikes)
    }

    @Test
    fun aBlipWithNoInitiateBehindItIsNotEvidence() {
        val p = policy()
        p.noteWifiLost()
        tick(3_000)
        assertEquals("no initiate at all", Verdict.None, p.noteWifiAvailable())

        p.noteInitiate()
        tick(NanInitiatorPolicy.COINCIDENCE_WINDOW_MS + 1_000)
        p.noteWifiLost()
        tick(3_000)
        assertEquals("the initiate is outside the window", Verdict.None, p.noteWifiAvailable())
        assertEquals(0, p.strikes)
    }

    @Test
    fun anInitiateAfterTheLossIsNotChargedForIt() {
        val p = policy()
        p.noteWifiLost()
        tick(1_000)
        p.noteInitiate() // filed into the outage
        tick(2_000)
        assertEquals(Verdict.None, p.noteWifiAvailable())
        assertEquals(0, p.strikes)
    }

    @Test
    fun oneInitiateEarnsAtMostOneStrike() {
        val p = policy()
        assertEquals(Verdict.Strike, p.dropAfterInitiate(rejectAfterMs = 30_000))
        // A second blip 20 s later, no initiate between: the first one already paid for this initiate.
        tick(20_000)
        p.noteWifiLost()
        tick(3_000)
        assertEquals(Verdict.None, p.noteWifiAvailable())
        assertEquals(1, p.strikes)
    }

    @Test
    fun anInitiatorLinkRefundsEverything() {
        val p = policy()
        assertEquals(Verdict.Strike, p.dropAfterInitiate(rejectAfterMs = 30_000))
        assertEquals(Verdict.Strike, p.dropAfterInitiate(rejectAfterMs = 30_000))
        assertEquals(2, p.strikes)
        p.noteInitiate()
        tick(5_000)
        assertFalse("not held, so nothing to release", p.noteInitiatorLink())
        assertEquals(0, p.strikes)
        // A blip now has no unlinked initiate behind it.
        tick(20_000)
        p.noteWifiLost()
        tick(3_000)
        assertEquals(Verdict.None, p.noteWifiAvailable())
        assertEquals(0, p.strikes)
    }

    @Test
    fun theWedgedResponderStreakNeverLatches() {
        // The P7/P8/P9 fleet against a wedged responder: an initiate a minute, every one failing, no STA harm.
        val p = policy()
        repeat(60) {
            p.noteInitiate()
            tick(60_000)
        }
        assertEquals(0, p.strikes)
        assertFalse(p.latched)
        assertFalse(p.probeDue())
    }

    @Test
    fun theDailyProbeIsOneInitiateADay() {
        val p = policy()
        repeat(3) { p.dropAfterInitiate(rejectAfterMs = 30_000) }
        assertTrue(p.latched)
        assertFalse("the first probe is a day out, not on the next tick", p.probeDue())
        tick(NanInitiatorPolicy.PROBE_INTERVAL_MS - 60_000)
        assertFalse(p.probeDue())
        tick(60_000)
        assertTrue(p.probeDue())
        assertTrue("the initiate is the probe", p.noteInitiate())
        assertFalse("and it is consumed", p.probeDue())
        tick(NanInitiatorPolicy.PROBE_INTERVAL_MS)
        assertTrue(p.probeDue())
    }

    @Test
    fun aClockThatWentBackStillProbes() {
        val p = policy()
        repeat(3) { p.dropAfterInitiate(rejectAfterMs = 30_000) }
        wall -= 2 * NanInitiatorPolicy.PROBE_INTERVAL_MS // the phone's clock was set back two days
        assertTrue(p.probeDue())
    }

    @Test
    fun aProbeThatLinksReleasesTheHold() {
        val p = policy()
        repeat(3) { p.dropAfterInitiate(rejectAfterMs = 30_000) }
        tick(NanInitiatorPolicy.PROBE_INTERVAL_MS)
        assertTrue(p.noteInitiate())
        tick(5_000)
        assertTrue("released — the caller clears the journal", p.noteInitiatorLink())
        assertFalse(p.latched)
        assertEquals(0, p.strikes)
        assertFalse(p.noteInitiate())
    }

    @Test
    fun aProbeThatDropsTheWifiAgainStaysHeldForAnotherDay() {
        val p = policy()
        repeat(3) { p.dropAfterInitiate(rejectAfterMs = 30_000) }
        tick(NanInitiatorPolicy.PROBE_INTERVAL_MS)
        assertEquals(Verdict.AlreadyHeld, p.dropAfterInitiate(rejectAfterMs = 40_000))
        assertTrue(p.latched)
        assertFalse(p.probeDue())
        tick(NanInitiatorPolicy.PROBE_INTERVAL_MS - 44_000)
        assertTrue(p.probeDue())
    }

    @Test
    fun theRegistrationEchoIsIgnored() {
        // registerNetworkCallback fires onAvailable at once for the Wi-Fi already up; no loss preceded it.
        val p = policy()
        p.noteInitiate()
        assertEquals(Verdict.None, p.noteWifiAvailable())
        assertEquals(0, p.strikes)
    }

    @Test
    fun aBlipCannotSpanARestart() {
        val p = policy()
        p.noteInitiate()
        tick(30_000)
        p.noteWifiLost()
        p.noteWatchStopped() // stop(): the watch unregisters
        tick(2_000)
        assertEquals(Verdict.None, p.noteWifiAvailable()) // the echo of the fresh start's registration
        assertEquals(0, p.strikes)
    }

    @Test
    fun stopKeepsTheStrikesAndTheLatch() {
        val p = policy()
        repeat(2) { p.dropAfterInitiate(rejectAfterMs = 30_000) }
        p.noteWatchStopped()
        assertEquals(2, p.strikes)
        p.dropAfterInitiate(rejectAfterMs = 30_000)
        assertTrue(p.latched)
        p.noteWatchStopped()
        assertTrue(p.latched)
    }

    @Test
    fun ourOwnRadioOffVoidsTheInitiateInFlight() {
        // pause() for a Wi-Fi Direct group, or Aware going unavailable on a Wi-Fi toggle: the STA blip that
        // follows is ours or the user's, not the firmware's.
        val p = policy()
        p.noteInitiate()
        tick(10_000)
        p.noteRadioOff()
        p.noteWifiLost()
        tick(3_000)
        assertEquals(Verdict.None, p.noteWifiAvailable())
        assertEquals(0, p.strikes)
    }

    @Test
    fun restoreThenReset() {
        val p = policy()
        p.restore(probedAt = wall - 60_000)
        assertTrue(p.latched)
        assertFalse(p.probeDue())
        assertTrue("held", p.snapshot().toString().startsWith("held"))
        p.reset()
        assertFalse(p.latched)
        assertEquals(0, p.strikes)
        assertFalse(p.probeDue())
        assertEquals("0/3", p.snapshot().toString())
    }

    @Test
    fun restoreWithAStaleProbeIsDueAtOnce() {
        val p = policy()
        p.restore(probedAt = wall - NanInitiatorPolicy.PROBE_INTERVAL_MS)
        assertTrue(p.probeDue())
    }

    @Test
    fun theWindowOutlivesTheHandshakeTimeout() {
        // The firmware's negotiation ran 30–100 s past the request; the transport gives up on the handshake at
        // 15 s (+3 s watchdog). The window has to cover the field's whole spread, with margin.
        assertTrue(NanInitiatorPolicy.COINCIDENCE_WINDOW_MS >= 100_000)
        assertTrue(NanInitiatorPolicy.BLIP_MAX_MS < NanInitiatorPolicy.COINCIDENCE_WINDOW_MS)
    }
}
