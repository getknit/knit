package app.getknit.knit

import app.getknit.knit.mesh.CustodyProgress
import app.getknit.knit.mesh.DigestTracker
import app.getknit.knit.mesh.wifiaware.NanSyncPolicy
import app.getknit.knit.mesh.wifiaware.NanSyncPolicy.PeerFacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [NanSyncPolicy] — the pure "is a NAN sync owed?" folds, and the digest/bulk split they guard. */
class NanSyncPolicyTest {
    /** A [PeerFacts] with every axis off; each test flips only what it exercises. */
    private fun peer(
        initiator: Boolean = false,
        linked: Boolean = false,
        discovered: Boolean = false,
        digestWanted: Boolean = false,
        bulkWanted: Boolean = false,
        lingerReachable: Boolean = false,
        corroborated: Boolean = false,
    ) = PeerFacts(initiator, linked, discovered, digestWanted, bulkWanted, lingerReachable, corroborated)

    @Test
    fun aBulkOnlyMarkRaisesAnInitiateButTripsNoRecoverySite() {
        // The load-bearing invariant: a pending image (bytes ride the BLE fallback) is worth an initiate but
        // must never run the wedge clock or churn subscribe re-arm.
        val f = listOf(peer(initiator = true, bulkWanted = true, lingerReachable = true, corroborated = true))
        assertTrue("admission is bulk-aware", NanSyncPolicy.initiateOwed(f))
        assertTrue(NanSyncPolicy.initiateOwedToReachable(f))
        assertFalse("Tier-1 owed clock is digest-pure", NanSyncPolicy.anyReachableSyncOwed(f))
        assertFalse("Tier-2 owed clock is digest-pure", NanSyncPolicy.anySyncOwed(f))
        assertFalse("subscribe re-arm is digest-pure", NanSyncPolicy.needsRediscovery(f))
        assertFalse("ICM relight is digest-pure", NanSyncPolicy.needsIcmRelight(f))
        assertFalse("fast-tick cadence is digest-pure", NanSyncPolicy.anyInitiatorDigestOwed(f))
    }

    @Test
    fun aDigestMarkReachesBothAdmissionAndRecovery() {
        val f = listOf(peer(initiator = true, digestWanted = true, lingerReachable = true, corroborated = true))
        assertTrue(NanSyncPolicy.initiateOwed(f))
        assertTrue(NanSyncPolicy.anyReachableSyncOwed(f))
        assertTrue(NanSyncPolicy.anySyncOwed(f))
        assertTrue(NanSyncPolicy.anyInitiatorDigestOwed(f))
    }

    @Test
    fun tier2NeedsCorroborationButTier1DoesNot() {
        val uncorroborated = listOf(peer(digestWanted = true, lingerReachable = true, corroborated = false))
        assertTrue("Tier-1 fires uncorroborated", NanSyncPolicy.anyReachableSyncOwed(uncorroborated))
        assertFalse("Tier-2 needs corroboration", NanSyncPolicy.anySyncOwed(uncorroborated))
    }

    @Test
    fun aWalkedAwayPeerIsStillInitiateOwedButRunsNoWedgeClock() {
        // digest differs but not reachable → the watchdog must NOT count it (the 180 s self-kill with no peer
        // present bug), yet an initiate is still nominally owed (a cheap no-op if it turns out gone).
        val f = listOf(peer(initiator = true, digestWanted = true, lingerReachable = false, corroborated = false))
        assertFalse("owed clock excludes the unreachable peer", NanSyncPolicy.anyReachableSyncOwed(f))
        assertTrue(NanSyncPolicy.initiateOwed(f))
        assertFalse("the expensive session-cycle gate needs reachability", NanSyncPolicy.initiateOwedToReachable(f))
    }

    @Test
    fun needsRediscoveryIsTrueWithNoCueTargetsAtAll() {
        // Load-bearing for NanLonelyPolicy: a lonely node re-arms on every eligible tick, so the lonely
        // cadence and cooldown are what bound how often it relights Instant Communication Mode.
        assertTrue(NanSyncPolicy.needsRediscovery(emptyList()))
    }

    @Test
    fun needsRediscoveryIsTrueWhenBlindAndForAnUndiscoveredOwedInitiatorPeer() {
        assertTrue("blind (no cue targets) re-fires discovery", NanSyncPolicy.needsRediscovery(emptyList()))
        assertTrue(NanSyncPolicy.needsRediscovery(listOf(peer(initiator = true, digestWanted = true))))
        assertFalse(
            "a discovered peer needs no re-arm",
            NanSyncPolicy.needsRediscovery(listOf(peer(initiator = true, digestWanted = true, discovered = true))),
        )
        assertFalse(
            "a linked peer needs no re-arm",
            NanSyncPolicy.needsRediscovery(listOf(peer(initiator = true, digestWanted = true, linked = true))),
        )
    }

    @Test
    fun needsIcmRelightIsTheResponderSideMirror() {
        assertTrue(
            "smaller-side (responder) owed peer relights ICM",
            NanSyncPolicy.needsIcmRelight(listOf(peer(initiator = false, digestWanted = true))),
        )
        assertFalse(
            "the initiator side is handled by needsRediscovery",
            NanSyncPolicy.needsIcmRelight(listOf(peer(initiator = true, digestWanted = true))),
        )
    }

    @Test
    fun anyInitiatorDigestOwedHasNoLinkedGate() {
        // Faithful to the inline rediscoverDelayMs fold: it gates only on initiator + digestWanted, not !linked.
        assertTrue(NanSyncPolicy.anyInitiatorDigestOwed(listOf(peer(initiator = true, digestWanted = true, linked = true))))
        assertFalse(NanSyncPolicy.anyInitiatorDigestOwed(listOf(peer(initiator = false, digestWanted = true))))
    }

    @Test
    fun reachableButNotNearIsNotCorroboratedWhileAForeignPlaneExists() {
        // The P9 incident (2026-10-20): a walk-away peer held reachable by one Coded advert at −101 dBm, not near, not
        // linked. Bluetooth exists, so its silence on "near" is evidence the peer is out of NDP range: no kill.
        assertFalse(NanSyncPolicy.corroborated(hasForeignPlane = true, linked = false, near = false))
        assertFalse(NanSyncPolicy.evidence(linked = false, near = false))
    }

    @Test
    fun aNanLinkOrANearSightingCorroborates() {
        assertTrue(
            "a live NAN link is its own corroboration",
            NanSyncPolicy.corroborated(hasForeignPlane = true, linked = true, near = false),
        )
        assertTrue("near on Bluetooth corroborates", NanSyncPolicy.corroborated(hasForeignPlane = true, linked = false, near = true))
        assertTrue(NanSyncPolicy.evidence(linked = true, near = false))
        assertTrue(NanSyncPolicy.evidence(linked = false, near = true))
    }

    @Test
    fun aNanOnlyDeviceFallsBackToCorroborated() {
        // No corroborating plane: corroboration falls back to yes, but the log line's evidence filter still names nobody.
        assertTrue(NanSyncPolicy.corroborated(hasForeignPlane = false, linked = false, near = false))
        assertFalse(NanSyncPolicy.evidence(linked = false, near = false))
    }

    /**
     * The responder's side of the hold-off (custody-carry-policy step 8): it keeps none, and trusts the link instead, for
     * `DigestTracker.RESPONDER_TRUST_MS` after the last non-convergent path with the larger peer or the plane's start. Its
     * digest-pure gate (what `snapshotPeerFacts` builds `digestWanted` from, with our role) then reads no want on divergence
     * alone, so no watchdog episode and no ICM relight run against the larger's hold-off. [TwoNodes] is one larger peer and
     * this phone, the smaller, on one clock: the larger's own tracker decides when it dials.
     */
    private class TwoNodes {
        var now = 1_000_000L

        // The larger's custody: its peer's digest crosses every path, and nothing ever moves (two full stores).
        var largerDigests = 0L
        val larger =
            DigestTracker(
                object : CustodyProgress {
                    override fun digestSeen(peer: String) = largerDigests
                },
            ) { now }
        val smaller = DigestTracker { now }

        init {
            larger.onCue("smaller", 2L)
            smaller.onCue("larger", 1L) // the digests differ for good
        }

        fun facts() =
            listOf(
                PeerFacts(
                    initiator = false, // the peer is larger: it initiates, we serve
                    linked = false,
                    discovered = false,
                    digestWanted = smaller.reconcileWanted("larger", 500L, initiator = false),
                    bulkWanted = false,
                    lingerReachable = true,
                    corroborated = true,
                ),
            )

        fun owed() = NanSyncPolicy.anyReachableSyncOwed(facts()) || NanSyncPolicy.needsIcmRelight(facts())

        /** One path, judged at both ends at its close, the digests still different. */
        fun path() {
            val opened = larger.open("smaller", digestWanted = true)
            largerDigests++
            larger.onReconciled("smaller", 600L, opened)
            smaller.onReconciled("larger", 500L, smaller.open("larger", digestWanted = true), initiator = false)
        }

        /**
         * Advances to when the larger's path is up, and returns how long that took. Free, it dials at once (a cue or a
         * link's end wakes its loop); held off, nothing wakes it at the hold-off's end, so it waits out a full idle tick
         * after it (the worst lag). Either way the handshake.
         */
        fun untilTheLargerDials(check: () -> Unit): Long {
            val from = now
            val held = !larger.reconcileWanted("smaller", 600L)
            // Bounded: the hold-off is at most COOLDOWN_MAX_MS, so twice that without a dial is a hold that never ends.
            var steps = 0
            while (!larger.reconcileWanted("smaller", 600L)) {
                kotlin.check(++steps <= 2 * DigestTracker.COOLDOWN_MAX_MS / STEP_MS) { "the larger never dialed in ${now - from} ms" }
                step(check)
            }
            val lag = (if (held) DIAL_TICK_MS else 0L) + HANDSHAKE_MS
            repeat((lag / STEP_MS).toInt()) { step(check) }
            return now - from
        }

        fun step(check: () -> Unit) {
            check()
            now += STEP_MS
        }
    }

    @Test
    fun theResponderIsNeverOwedForAnInitiatorAtItsCeilingWithARealDialLag() {
        val p = TwoNodes()
        p.smaller.onPlaneStarted()
        var gap = 0L
        repeat(12) { i ->
            gap = p.untilTheLargerDials { assertFalse("owed between the larger's dials (path $i)", p.owed()) }
            p.path()
        }
        assertEquals("the larger reached its 600 s step, dialing a tick late", COOLDOWN_GAP_MS, gap)
    }

    @Test
    fun aRestartedResponderIsNeverOwedInsideItsGraceAndItsFirstPathExtendsTheTrust() {
        val p = TwoNodes()
        repeat(6) {
            // the larger engaged and doubling
            p.untilTheLargerDials {}
            p.path()
        }
        val restarted = DigestTracker { p.now } // a restart (or a Tier-2 kill): nothing survives but the grace
        restarted.onCue("larger", 1L)
        restarted.onPlaneStarted()
        val owed = { restarted.reconcileWanted("larger", 500L, initiator = false) }
        val start = p.now
        while (p.now - start < DigestTracker.RESPONDER_TRUST_MS - STEP_MS) {
            assertFalse("never owed inside the grace", owed())
            p.now += STEP_MS
        }
        restarted.onReconciled("larger", 500L, restarted.open("larger", true), initiator = false) // the larger dialed
        p.now += DigestTracker.RESPONDER_TRUST_MS - 1
        assertFalse("the first path after the restart extends the trust past the grace", owed())
        p.now += 1
        assertTrue(owed())
    }

    @Test
    fun aConvergedLastPathLeavesANewDivergenceOwedAtOnce() {
        var now = 0L
        val smaller = DigestTracker { now }
        smaller.onPlaneStarted() // as production runs it: the restart grace is live, and convergence ends it
        smaller.onCue("larger", 500L)
        smaller.onReconciled("larger", 500L, smaller.open("larger", true), initiator = false) // converged
        smaller.onCue("larger", 7L) // a new message on the larger
        now += 1_000
        assertTrue("owed at once, as before", smaller.reconcileWanted("larger", 500L, initiator = false))
    }

    @Test
    fun aBusyInitiatorsLateDialKeepsTheTrustFromThePathItBringsUp() {
        // The larger's one NDI was held by another peer's file past the arithmetic's free-NDI gap: its dial lands 1,150 s
        // after the last close, and the path it brings up stays 300 s on a transfer before it closes.
        val p = TwoNodes()
        p.path()
        val closedAt = p.now
        while (p.now - closedAt < 1_150_000L) p.step { assertFalse("owed while the NDI was busy", p.owed()) }
        p.smaller.onPathUp("larger") // the dial landed: registerConn on the served path
        repeat((300_000L / STEP_MS).toInt()) { p.step { assertFalse("owed with the path up", p.owed()) } }
        p.path()
        assertFalse(p.owed())
    }

    @Test
    fun theTrustLapsesWithNoPathAndTheWedgeDetectionComesBack() {
        val p = TwoNodes()
        p.path() // the last path that came up; then the larger wedges and never dials again
        p.now += DigestTracker.RESPONDER_TRUST_MS - 1
        assertFalse(p.owed())
        p.now += 1
        assertTrue("owed: the watchdog's episode starts", NanSyncPolicy.anyReachableSyncOwed(p.facts()))
        assertTrue("and the relight runs", NanSyncPolicy.needsIcmRelight(p.facts()))
    }

    private companion object {
        // The larger's worst idle tick (REDISCOVER_IDLE_MS doubled, 240 s), the handshake timeout, and the test's step.
        const val DIAL_TICK_MS = 240_000L
        const val HANDSHAKE_MS = 15_000L
        const val STEP_MS = 5_000L

        // The larger's gap between dials at its ceiling: 600 s, the idle tick and the handshake.
        const val COOLDOWN_GAP_MS = DigestTracker.COOLDOWN_MAX_MS + DIAL_TICK_MS + HANDSHAKE_MS
    }
}
