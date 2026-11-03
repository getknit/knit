package app.getknit.knit

import app.getknit.knit.mesh.ConnectFailReason
import app.getknit.knit.mesh.bluetooth.AdvertOrder
import app.getknit.knit.mesh.bluetooth.BlePresenceTracker
import app.getknit.knit.mesh.bluetooth.CodedPhyPolicy
import app.getknit.knit.mesh.bluetooth.DialPlan
import app.getknit.knit.mesh.bluetooth.LonelyDialPolicy
import app.getknit.knit.mesh.bluetooth.PromotionConfig
import app.getknit.knit.mesh.bluetooth.PromotionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for [DialPlan]: which peers the connection engine hands its policies, and how their answers combine. */
class DialPlanTest {
    private val mine = AdvertOrder.Mine.clear("mmm")

    /** A blinding phone's forms (ADR 2026-11.xp6d): what the writer will set; this build never does. */
    private val blinding = AdvertOrder.Mine(AdvertOrder.Form(TAG_MINE, blinded = true))

    private fun snap(
        nodeId: String,
        rssi: Double = -60.0,
        psm: Int = 100,
        dwell: Long = 15_000,
        tag: String? = null,
    ): BlePresenceTracker.Snapshot =
        BlePresenceTracker.Snapshot(
            nodeId = nodeId,
            protoVersion = 1,
            capabilities = 0L,
            psm = psm,
            digestCue = 0,
            smoothedRssi = rssi,
            dwellMs = dwell,
            lastSeenAgoMs = 0,
            advertised = tag ?: nodeId,
            blinded = tag != null,
        )

    @Test
    fun theTieBreakDialsOnlySmallerIdsNotLinkedAndNotInFlight() {
        val snaps = listOf(snap("aaa"), snap("bbb"), snap("ccc"), snap("zzz"))
        val got = DialPlan.ordinaryCandidates(mine, snaps, linked = setOf("bbb"), inFlight = setOf("ccc")).map { it.nodeId }
        assertEquals("a larger id is the other side's to dial", listOf("aaa"), got)
    }

    @Test
    fun aPeerIsScanWorkOnlyWhenWeDialItItIsCloseEnoughAndNotBackingOff() {
        val floor = -90.0

        fun promotable(
            nodeId: String,
            rssi: Double?,
            linked: Set<String> = emptySet(),
            backoff: Set<String> = emptySet(),
        ) = DialPlan.promotable(mine, AdvertOrder.Form.clear(nodeId), nodeId, rssi, linked, floor) { it in backoff }
        assertTrue(promotable("aaa", -70.0))
        assertTrue("the floor itself promotes", promotable("aaa", -90.0))
        assertFalse("below the floor", promotable("aaa", -91.0))
        assertFalse("never sighted, no RSSI", promotable("aaa", null))
        assertFalse("a larger id", promotable("zzz", -70.0))
        assertFalse("our own id", promotable("mmm", -70.0))
        assertFalse("linked", promotable("aaa", -70.0, linked = setOf("aaa")))
        assertFalse("backing off", promotable("aaa", -70.0, backoff = setOf("aaa")))
    }

    @Test
    fun theBackoffIsReadOnlyForAPeerThatPassesEveryOtherGate() {
        // It takes the transport's lock, on every sighting.
        var reads = 0
        DialPlan.promotable(mine, AdvertOrder.Form.clear("zzz"), "zzz", -70.0, emptySet(), -90.0) { reads++ == 0 && false }
        DialPlan.promotable(mine, AdvertOrder.Form.clear("aaa"), "aaa", -95.0, emptySet(), -90.0) { reads++ == 0 && false }
        assertEquals(0, reads)
        DialPlan.promotable(mine, AdvertOrder.Form.clear("aaa"), "aaa", -70.0, emptySet(), -90.0) { reads++ == 0 && false }
        assertEquals(1, reads)
    }

    @Test
    fun theScanCandidatesAreTheSightingsThatPassTheSameGate() {
        val snaps = listOf(snap("aaa", -70.0), snap("bbb", -95.0), snap("ccc", -70.0), snap("ddd", -70.0), snap("zzz", -50.0))
        val got = DialPlan.scanCandidates(mine, snaps, linked = setOf("ccc"), backoff = setOf("ddd"), rssiFloorDbm = -90.0)
        assertEquals(setOf("aaa"), got)
    }

    @Test
    fun theLonelyDialChoosesAmongDialableUnlinkedPeersWithAPsmAndMarksTheBackedOff() {
        val snaps = listOf(snap("aaa"), snap("bbb", psm = 0), snap("ccc"), snap("ddd"), snap("zzz", rssi = -40.0, dwell = 9_000))
        val got =
            DialPlan.lonelyCandidates(snaps, linked = setOf("ccc"), backoff = setOf("zzz")) { it != "ddd" }
        assertEquals(
            listOf(
                LonelyDialPolicy.Candidate("aaa", -60.0, 15_000, backedOff = false),
                LonelyDialPolicy.Candidate("zzz", -40.0, 9_000, backedOff = true),
            ),
            got,
        )
    }

    @Test
    fun aDialIsLonelyByTheKindItRecordedWhenItStartedNeverByTheIdOrder() {
        val ordinary = DialPlan.DialKind.ORDINARY
        val lonely = DialPlan.DialKind.LONELY
        assertFalse(DialPlan.lonelyDialInFlight(mapOf("aaa" to ordinary, "bbb" to ordinary)))
        assertTrue(DialPlan.lonelyDialInFlight(mapOf("aaa" to ordinary, "zzz" to lonely)))
        assertFalse(DialPlan.lonelyDialInFlight(emptyMap()))
        // A blinding phone ordinary-dials a larger clear id, and a lonely dial may go to a tag below it (xp6d).
        assertFalse("a larger id dialed ordinarily", DialPlan.lonelyDialInFlight(mapOf("zzz" to ordinary)))
        assertTrue("a smaller form dialed lonely", DialPlan.lonelyDialInFlight(mapOf("aaa" to lonely)))
    }

    @Test
    fun aClearPhoneNeverOrdinaryDialsABlindedAdvertWhereverItsTagSorts() {
        val snaps = listOf(snap("aaa"), snap("bbb", tag = TAG_LOW), snap("ccc", tag = TAG_HIGH))
        assertEquals(listOf("aaa"), DialPlan.ordinaryCandidates(mine, snaps, emptySet(), emptySet()).map { it.nodeId })
        assertEquals(setOf("aaa"), DialPlan.scanCandidates(mine, snaps, emptySet(), emptySet(), -90.0))
        assertFalse(
            "a tag sorting below my id is still the blinding side's to dial",
            DialPlan.promotable(mine, AdvertOrder.Form(TAG_LOW, blinded = true), "bbb", -60.0, emptySet(), -90.0) { false },
        )
    }

    @Test
    fun aClearPhoneMayLonelyDialABlindedAdvertAndItsCandidateCarriesTheForm() {
        val snaps = listOf(snap("bbb", tag = TAG_LOW))
        val candidates = DialPlan.lonelyCandidates(snaps, emptySet(), emptySet()) { true }
        assertEquals(listOf(AdvertOrder.Form(TAG_LOW, blinded = true)), candidates.map { it.form })
        val picked = LonelyDialPolicy.pick(mine, linkCount = 0, aloneForMs = Long.MAX_VALUE, candidates, lonelyDialInFlight = false)
        assertEquals("the blinding side would not dial me", "bbb", picked?.nodeId)
    }

    @Test
    fun aBlindingPhoneOrdinaryDialsEveryClearAdvertAndTheLargerOfTwoTags() {
        val snaps = listOf(snap("aaa"), snap("zzz"), snap("bbb", tag = TAG_LOW), snap("ccc", tag = TAG_HIGH))
        assertEquals(
            listOf("aaa", "zzz", "bbb"),
            DialPlan.ordinaryCandidates(blinding, snaps, emptySet(), emptySet()).map { it.nodeId },
        )
    }

    @Test
    fun theLonelyDialRidesAfterThePromotionsWithNoCap() {
        val ordinary = PromotionPolicy.Decision(promote = listOf("aaa"), evict = listOf("bbb"))
        val lonely = LonelyDialPolicy.Candidate("zzz", -60.0, 15_000, backedOff = false)
        assertEquals(
            PromotionPolicy.Decision(listOf("aaa", "zzz"), listOf("bbb")),
            DialPlan.decision(ordinary, lonely, cap = null, linkCount = 7, inFlightCount = { 7 }),
        )
        assertEquals(ordinary, DialPlan.decision(ordinary, null, cap = null, linkCount = 7, inFlightCount = { 7 }))
    }

    @Test
    fun aDebugCapCountsLinksAndDialsInFlightAndFreesASlotPerEviction() {
        val ordinary = PromotionPolicy.Decision(promote = listOf("aaa", "bbb", "ccc"), evict = emptyList())
        // Cap 4, two links, one dial in flight: one slot.
        assertEquals(listOf("aaa"), DialPlan.decision(ordinary, null, cap = 4, linkCount = 2, inFlightCount = { 1 }).promote)
        // An eviction frees one more.
        val evicting = ordinary.copy(evict = listOf("old"))
        assertEquals(listOf("aaa", "bbb"), DialPlan.decision(evicting, null, cap = 4, linkCount = 2, inFlightCount = { 1 }).promote)
        // Over the cap already: none, never a negative take.
        assertEquals(emptyList<String>(), DialPlan.decision(ordinary, null, cap = 2, linkCount = 3, inFlightCount = { 1 }).promote)
        // The lonely dial is held to the cap too, last in line.
        val lonely = LonelyDialPolicy.Candidate("zzz", -60.0, 15_000, backedOff = false)
        val two = PromotionPolicy.Decision(listOf("aaa"), emptyList())
        assertEquals(listOf("aaa"), DialPlan.decision(two, lonely, cap = 1, linkCount = 0, inFlightCount = { 0 }).promote)
        assertEquals(listOf("aaa", "zzz"), DialPlan.decision(two, lonely, cap = 2, linkCount = 0, inFlightCount = { 0 }).promote)
    }

    @Test
    fun aPeerHeardOnCodedAloneIsDialedAtItsCodedAddressOnly() {
        assertEquals("coded" to true, DialPlan.target(viaCoded = true, oneM = "1m", coded = "coded"))
        assertNull("its 1M address is not a fallback", DialPlan.target(viaCoded = true, oneM = "1m", coded = null))
    }

    @Test
    fun anyOtherPeerIsDialedAt1mElseAsACodedDialAtItsCodedAddress() {
        assertEquals("1m" to false, DialPlan.target(viaCoded = false, oneM = "1m", coded = "coded"))
        // #148: the flag follows the address, so the fallback gets Coded's watchdog, count and via=.
        assertEquals("coded" to true, DialPlan.target(viaCoded = false, oneM = null, coded = "coded"))
        assertEquals(
            CodedPhyPolicy.CODED_CONNECT_TIMEOUT_MS,
            CodedPhyPolicy.connectTimeoutMs(DialPlan.target(viaCoded = false, oneM = null, coded = "c")!!.second),
        )
        assertNull(DialPlan.target<String>(viaCoded = false, oneM = null, coded = null))
    }

    @Test
    fun theInitiatingLineNamesItsPhyOnlyWhileCodedIsOn() {
        assertEquals("", DialPlan.via(codedOn = false, viaCoded = false))
        assertEquals("", DialPlan.via(codedOn = false, viaCoded = true))
        assertEquals(" via=coded", DialPlan.via(codedOn = true, viaCoded = true))
        assertEquals(" via=1m", DialPlan.via(codedOn = true, viaCoded = false))
    }

    @Test
    fun theDialsInFlightAreCountedOnlyUnderACap() {
        // The count takes the transport's lock, on every promotion tick.
        var reads = 0
        val ordinary = PromotionPolicy.Decision(listOf("aaa"), emptyList())
        DialPlan.decision(ordinary, null, cap = null, linkCount = 0) { reads++ }
        assertEquals(0, reads)
        DialPlan.decision(ordinary, null, cap = 3, linkCount = 0) { reads++ }
        assertEquals(1, reads)
    }

    @Test
    fun onlyAPeerWeDialIsWorthAChase() {
        assertEquals(setOf("aaa"), DialPlan.initiable("mmm", mine, setOf("aaa", "mmm", "zzz")))
        assertEquals(emptySet<String>(), DialPlan.initiable("mmm", mine, emptySet()))
        assertEquals(
            "a blinding phone dials every clear advert",
            setOf("aaa", "zzz"),
            DialPlan.initiable("mmm", blinding, setOf("aaa", "mmm", "zzz")),
        )
    }

    @Test
    fun aGattReadWaitsForTheRadioAndNeverTouchesALinkedAddress() {
        assertTrue(DialPlan.mayReadGatt("A", listOf("B"), arbiterBusy = false) { false })
        assertFalse("a link holds it", DialPlan.mayReadGatt("B", listOf("B"), arbiterBusy = false) { false })
        assertFalse("a dial is in flight", DialPlan.mayReadGatt("A", listOf("B"), arbiterBusy = false) { true })
        assertFalse("another connect holds the arbiter", DialPlan.mayReadGatt("A", listOf("B"), arbiterBusy = true) { false })
    }

    @Test
    fun theDialsInFlightAreNotReadForALinkedAddress() {
        var reads = 0
        DialPlan.mayReadGatt("B", listOf("B"), arbiterBusy = false) { reads++ > 0 }
        assertEquals(0, reads)
    }

    @Test
    fun onlyAHelloRefusalKeepsTheGattPayload() {
        assertFalse(DialPlan.forgetsGattPayload(ConnectFailReason.HANDSHAKE))
        ConnectFailReason.entries.filter { it != ConnectFailReason.HANDSHAKE }.forEach {
            assertTrue("$it may have used a stale PSM", DialPlan.forgetsGattPayload(it))
        }
    }

    @Test
    fun aGattReadYieldsOnlyToAPromotedPeerWithAnAddressToDial() {
        // #154: a promoted peer initiateTo cannot dial (no address) would hold every read off for its whole linger.
        val addressed = setOf("A")
        assertTrue(DialPlan.dialWaiting(listOf("B", "A")) { it in addressed })
        assertFalse("no address, no dial", DialPlan.dialWaiting(listOf("B")) { it in addressed })
        assertFalse("nothing promoted", DialPlan.dialWaiting(emptyList()) { true })
    }

    // --- the 2026-10-20 amendment ---

    @Test
    fun aPeerHeardOnlyWeaklyOn1mIsDialedAtItsCodedAddress() {
        val t = BlePresenceTracker()
        t.onOneMListening(true, 0)
        for (at in 0L..9_000L step 3_000L) {
            t.onSighting(BlePresenceTracker.Sighting("edge", -95, 1, 0L, 100, 0), at + 1_000)
            t.onSighting(BlePresenceTracker.Sighting("edge", -100, 1, 0L, 100, 0, coded = true), at + 2_000)
        }
        val snap = t.snapshots(11_000).single()
        val (address, viaCoded) = DialPlan.target(CodedPhyPolicy.dialCoded(snap.codedLagMs), oneM = "1m", coded = "coded")!!
        assertEquals("coded", address)
        assertEquals(" via=coded", DialPlan.via(codedOn = true, viaCoded = viaCoded))
    }

    private fun held(
        nodeId: String,
        rssi: Double,
        ageMs: Long = 60_000,
    ) = PromotionPolicy.LinkSnapshot(nodeId, rssi, ageMs, idleMs = 0)

    @Test
    fun aColdHuntNeedsThePromotionToHaveRoomForItsPeer() {
        val config = PromotionConfig(maxLinks = 2)
        val far = snap("far", rssi = -85.0, dwell = 0)
        assertTrue("a free slot, the dwell not yet ripe", DialPlan.promotes(far, listOf(held("x", -60.0)), emptySet(), config))
        val full = listOf(held("x", -60.0), held("y", -70.0))
        assertFalse("at the budget behind stronger links", DialPlan.promotes(far, full, emptySet(), config))
        val weak = listOf(held("x", -60.0), held("y", -94.0))
        assertTrue("it beats the weakest by the hysteresis", DialPlan.promotes(far, weak, emptySet(), config))
        val young = listOf(held("x", -60.0), held("y", -94.0, ageMs = 1_000))
        assertFalse("a link too young to evict", DialPlan.promotes(far, young, emptySet(), config))
        assertFalse("under the floor", DialPlan.promotes(snap("far", rssi = -91.0), emptyList(), emptySet(), config))
        assertFalse("backing off", DialPlan.promotes(far, emptyList(), setOf("far"), config))
        assertTrue("the debug cap is the budget", DialPlan.promotes(far, emptyList(), emptySet(), PromotionConfig(maxLinks = 1)))
        assertFalse(DialPlan.promotes(far, listOf(held("x", -60.0)), emptySet(), PromotionConfig(maxLinks = 1)))
    }

    private companion object {
        // Tags as their 26-char forms: each starts `zzzz` (BlindTag's prefix). TAG_MINE sorts between the two.
        const val TAG_LOW = "zzzzaaaaaaaaaaaaaaaaaaaaaa"
        const val TAG_MINE = "zzzzmmmmmmmmmmmmmmmmmmmmmm"
        const val TAG_HIGH = "zzzzzzzzzzzzzzzzzzzzzzzzzz"
    }
}
