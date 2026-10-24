package app.getknit.knit

import app.getknit.knit.mesh.bluetooth.AwareCandidateTracker
import app.getknit.knit.mesh.bluetooth.AwareCandidateTracker.LinkFacts
import app.getknit.knit.mesh.bluetooth.BlePresenceTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AwareCandidateTracker] (GitHub #44): who this phone has positive evidence runs Wi-Fi Aware, near enough to wake a
 * dormant Aware plane and heard enough to keep it attached, on a virtual clock with hand-built presence snapshots.
 */
class AwareCandidateTrackerTest {
    private val linger = 90_000L
    private val tracker = AwareCandidateTracker(lingerMs = linger)

    private fun snap(
        id: String,
        rssi1m: Double? = -60.0,
        rssiCoded: Double? = null,
        codedLagMs: Long? = null,
    ) = BlePresenceTracker.Snapshot(
        nodeId = id,
        protoVersion = 1,
        capabilities = 0L,
        psm = 1,
        digestCue = 0,
        smoothedRssi = rssi1m ?: (rssiCoded ?: -100.0),
        dwellMs = 0,
        lastSeenAgoMs = 0,
        rssi1m = rssi1m,
        rssiCoded = rssiCoded,
        codedLagMs = codedLagMs,
    )

    private fun sets(
        now: Long,
        snaps: List<BlePresenceTracker.Snapshot> = emptyList(),
        links: Map<String, LinkFacts> = emptyMap(),
    ) = tracker.sets(now, snaps, links)

    @Test
    fun aClearBitSightedNearIsACandidateInBothSets() {
        tracker.note("a", noAware = false, oneM = true, now = 0)
        val s = sets(0, listOf(snap("a")))
        assertEquals(setOf("a"), s.near)
        assertEquals(setOf("a"), s.heard)
    }

    @Test
    fun aSetBitExcludesThePeerAtOnce() {
        tracker.note("a", noAware = false, oneM = true, now = 0)
        tracker.note("a", noAware = true, oneM = true, now = 1)
        val s = sets(1, listOf(snap("a")), mapOf("a" to LinkFacts(phyNear = true, doorbell = false)))
        assertTrue("the latest service-data sighting decides, linked or not", s.near.isEmpty() && s.heard.isEmpty())
    }

    @Test
    fun noSightingNoCandidateWhateverTheLinkSays() {
        // An inbound link from a peer the scan never sighted: no evidence, so not a candidate.
        val s = sets(0, links = mapOf("inbound" to LinkFacts(phyNear = true, doorbell = false)))
        assertTrue(s.near.isEmpty() && s.heard.isEmpty())
    }

    @Test
    fun aGattSightingRemovesTheRecord() {
        tracker.note("a", noAware = false, oneM = true, now = 0)
        tracker.forget("a") // its latest sighting came through a GATT payload (an iPhone)
        assertTrue(sets(0, listOf(snap("a"))).heard.isEmpty())
    }

    @Test
    fun aDoorbellLinkIsNeverACandidate() {
        tracker.note("a", noAware = false, oneM = true, now = 0)
        val s = sets(0, listOf(snap("a")), mapOf("a" to LinkFacts(phyNear = true, doorbell = true)))
        assertTrue(s.near.isEmpty() && s.heard.isEmpty())
    }

    @Test
    fun aWeakSightingIsHeardButNotNear() {
        tracker.note("a", noAware = false, oneM = true, now = 0)
        val s = sets(0, listOf(snap("a", rssi1m = -95.0)))
        assertTrue("under the −90 floor: no wake", s.near.isEmpty())
        assertEquals("but it keeps a waking plane attached", setOf("a"), s.heard)
    }

    @Test
    fun aCodedOnlySightingIsNeitherNearNorHeard() {
        tracker.note("a", noAware = false, oneM = false, now = 0)
        val s = sets(0, listOf(snap("a", rssi1m = null, rssiCoded = -100.0, codedLagMs = 60_000)))
        assertTrue(s.near.isEmpty() && s.heard.isEmpty())
    }

    @Test
    fun aLinkCountsOnlyOnAPhyReadBackAs1mOr2m() {
        tracker.note("a", noAware = false, oneM = true, now = 0)
        val later = linger * 10 // presence has long pruned it: a floored scan in a settled clique
        tracker.onWindow(startedAt = 1, countsAsMiss = true)
        tracker.onWindow(startedAt = 2, countsAsMiss = true)
        assertEquals(setOf("a"), sets(later, links = mapOf("a" to LinkFacts(phyNear = true, doorbell = false))).near)
        val coded = sets(later, links = mapOf("a" to LinkFacts(phyNear = false, doorbell = false)))
        assertTrue("a Coded link, or one never read back, is no evidence", coded.near.isEmpty() && coded.heard.isEmpty())
    }

    @Test
    fun aCodedLinkFallsBackOnItsPeersOwnSightings() {
        tracker.note("a", noAware = false, oneM = true, now = 0)
        val s = sets(0, listOf(snap("a", rssi1m = -95.0)), mapOf("a" to LinkFacts(phyNear = false, doorbell = false)))
        assertTrue("never near by the link", s.near.isEmpty())
        assertEquals("heard by its 1M advert, as an unlinked peer would be", setOf("a"), s.heard)
    }

    @Test
    fun aPeerHeardInEveryWindowNeverLeavesTheHeardSetWhateverTheGap() {
        // A dark phone's settled gap, stretched by suspend, runs far past the 90 s presence linger.
        val gap = 20 * 60_000L
        var t = 0L
        repeat(5) {
            tracker.note("a", noAware = false, oneM = true, now = t)
            tracker.onWindow(startedAt = t - 5_000, countsAsMiss = true) // the window that heard it ends
            assertEquals(setOf("a"), sets(t + gap - 1).heard) // the whole gap, presence long pruned
            t += gap
        }
    }

    @Test
    fun theHeardSetLapsesOnlyAfterTheLingerAndTwoWholeWindowsMissed() {
        tracker.note("a", noAware = false, oneM = true, now = 0)
        assertEquals("missed no window yet, past the linger", setOf("a"), sets(linger * 5).heard)
        tracker.onWindow(startedAt = 10_000, countsAsMiss = true)
        assertEquals("one window missed, past the linger: a dark window can miss a peer that is there", setOf("a"), sets(linger * 5).heard)
        tracker.onWindow(startedAt = 20_000, countsAsMiss = true)
        assertEquals("two windows missed, inside the linger", setOf("a"), sets(linger - 1).heard)
        assertTrue("two windows missed and the linger passed", sets(linger).heard.isEmpty())
    }

    @Test
    fun aSightingBetweenTwoMissesStartsTheCountAgain() {
        tracker.note("a", noAware = false, oneM = true, now = 0)
        tracker.onWindow(startedAt = 10_000, countsAsMiss = true)
        tracker.note("a", noAware = false, oneM = true, now = 30_000)
        tracker.onWindow(startedAt = 25_000, countsAsMiss = true) // the window that heard it
        tracker.onWindow(startedAt = 40_000, countsAsMiss = true) // one miss since
        assertEquals("only one miss in a row", setOf("a"), sets(linger * 5).heard)
    }

    @Test
    fun aWindowThatCouldNotHearItIsNoMiss() {
        tracker.note("a", noAware = false, oneM = true, now = 0)
        tracker.onWindow(startedAt = 10_000, countsAsMiss = false) // Coded-only, cut short, or a failed scan
        assertEquals(setOf("a"), sets(linger * 5).heard)
    }

    @Test
    fun aWindowThatBeganBeforeTheSightingIsNoMiss() {
        tracker.note("a", noAware = false, oneM = true, now = 10_000)
        tracker.onWindow(startedAt = 5_000, countsAsMiss = true) // it heard it
        assertEquals(setOf("a"), sets(linger * 5).heard)
    }

    @Test
    fun aCountedLinksEndRestampsThePeer() {
        tracker.note("a", noAware = false, oneM = true, now = 0)
        tracker.onWindow(startedAt = 1, countsAsMiss = true)
        val end = linger * 10
        tracker.onLinkEnded("a", counted = true, now = end)
        assertEquals("heard from the link's end", setOf("a"), sets(end + linger * 3).heard)
        tracker.onWindow(startedAt = end + 1, countsAsMiss = true)
        tracker.onWindow(startedAt = end + 2, countsAsMiss = true)
        assertTrue(sets(end + linger).heard.isEmpty())
    }

    @Test
    fun aCodedLinksEndRestampsNothing() {
        tracker.note("a", noAware = false, oneM = true, now = 0)
        tracker.onWindow(startedAt = 1, countsAsMiss = true)
        tracker.onWindow(startedAt = 2, countsAsMiss = true)
        tracker.onLinkEnded("a", counted = false, now = linger * 10)
        assertTrue(sets(linger * 10).heard.isEmpty())
    }

    @Test
    fun nearIsAlwaysWithinHeard() {
        // Near on a fresh raw Coded reading ≥ −90 (ADR 2026-09.9dnk), never heard on 1M.
        tracker.note("a", noAware = false, oneM = false, now = 0)
        val s = sets(0, listOf(snap("a", rssi1m = null, rssiCoded = -80.0, codedLagMs = 0)))
        assertEquals(setOf("a"), s.near)
        assertEquals(setOf("a"), s.heard)
    }

    @Test
    fun clearForgetsEveryone() {
        tracker.note("a", noAware = false, oneM = true, now = 0)
        tracker.clear()
        assertTrue(sets(0, listOf(snap("a"))).heard.isEmpty())
    }
}
