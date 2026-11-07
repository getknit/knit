package app.getknit.knit

import app.getknit.knit.mesh.ConnectFailReason
import app.getknit.knit.mesh.bluetooth.AdvertOrder
import app.getknit.knit.mesh.bluetooth.BackoffConfig
import app.getknit.knit.mesh.bluetooth.BlePresenceTracker
import app.getknit.knit.mesh.bluetooth.CodedPhyPolicy
import app.getknit.knit.mesh.bluetooth.ConnectBackoffEntry
import app.getknit.knit.mesh.bluetooth.ConnectBackoffs
import app.getknit.knit.mesh.bluetooth.DialKeys
import app.getknit.knit.mesh.bluetooth.DialPlan
import app.getknit.knit.mesh.bluetooth.FormKeys
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
    private val noHeldDial: (BlePresenceTracker.Snapshot) -> Boolean = { false }

    private val mine = AdvertOrder.Mine.clear("mmm")

    /** A blinding phone's forms (ADR 2026-11.xp6d): what the writer will set; this build never does. */
    private val blinding = AdvertOrder.Mine(AdvertOrder.Form(TAG_MINE, blinded = true))

    private fun snap(
        nodeId: String,
        rssi: Double = -60.0,
        psm: Int = 100,
        dwell: Long = 15_000,
        tag: String? = null,
        suppressor: BlePresenceTracker.Suppressor = BlePresenceTracker.Suppressor.Linked,
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
            suppressor = suppressor,
        )

    @Test
    fun theTieBreakDialsOnlySmallerIdsNotLinkedAndNotInFlight() {
        val snaps = listOf(snap("aaa"), snap("bbb"), snap("ccc"), snap("zzz"))
        val got =
            DialPlan
                .ordinaryCandidates(
                    mine,
                    snaps,
                    linked = setOf("bbb"),
                    inFlight = setOf("ccc"),
                    dialHeld = noHeldDial,
                ).map { it.nodeId }
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
        val got =
            DialPlan.scanCandidates(
                mine,
                snaps,
                linked = setOf("ccc"),
                backoff = setOf("ddd"),
                rssiFloorDbm = -90.0,
                dialHeld = noHeldDial,
            )
        assertEquals(setOf("aaa"), got)
    }

    @Test
    fun theLonelyDialChoosesAmongDialableUnlinkedPeersWithAPsmAndMarksTheBackedOff() {
        val snaps = listOf(snap("aaa"), snap("bbb", psm = 0), snap("ccc"), snap("ddd"), snap("zzz", rssi = -40.0, dwell = 9_000))
        val got =
            DialPlan.lonelyCandidates(snaps, linked = setOf("ccc"), backoff = setOf("zzz")) { it.nodeId != "ddd" }
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
        // My clear id sorts between the two tags: a plan comparing advertised strings without the blinded rule would
        // dial TAG_LOW (unit-test review of part 1).
        val between = AdvertOrder.Mine.clear(BETWEEN)
        val snaps = listOf(snap("aaa"), snap("bbb", tag = TAG_LOW), snap("ccc", tag = TAG_HIGH))
        assertEquals(listOf("aaa"), DialPlan.ordinaryCandidates(between, snaps, emptySet(), emptySet(), noHeldDial).map { it.nodeId })
        assertEquals(setOf("aaa"), DialPlan.scanCandidates(between, snaps, emptySet(), emptySet(), -90.0, noHeldDial))
        assertFalse(
            "a tag sorting below my id is still the blinding side's to dial",
            DialPlan.promotable(between, AdvertOrder.Form(TAG_LOW, blinded = true), "bbb", -60.0, emptySet(), -90.0) { false },
        )
    }

    @Test
    fun aClearPhoneMayLonelyDialABlindedAdvertBelowItAndItsCandidateCarriesTheForm() {
        val between = AdvertOrder.Mine.clear(BETWEEN)
        val snaps = listOf(snap("bbb", tag = TAG_LOW))
        val candidates = DialPlan.lonelyCandidates(snaps, emptySet(), emptySet()) { true }
        assertEquals(listOf(AdvertOrder.Form(TAG_LOW, blinded = true)), candidates.map { it.form })
        // TAG_LOW sorts below my id: the shipped "lonely dials a larger id" on strings would refuse it.
        val picked = LonelyDialPolicy.pick(between, linkCount = 0, aloneForMs = Long.MAX_VALUE, candidates, lonelyDialInFlight = false)
        assertEquals("the blinding side would not dial me", "bbb", picked?.nodeId)
    }

    @Test
    fun aBlindingPhoneOrdinaryDialsEveryClearAdvertAndTheLargerOfTwoTags() {
        val snaps =
            listOf(snap("aaa"), snap("zzz"), snap(ABOVE_MINE), snap("bbb", tag = TAG_LOW), snap("ccc", tag = TAG_HIGH))
        assertEquals(
            "a clear id sorting above my tag is dialed too",
            listOf("aaa", "zzz", ABOVE_MINE, "bbb"),
            DialPlan.ordinaryCandidates(blinding, snaps, emptySet(), emptySet(), noHeldDial).map { it.nodeId },
        )
    }

    @Test
    fun aClearAdvertsDialKeyIsItsIdAndABlindedOnesIsItsHandleAndAddressWithNoTag() {
        val keys = FormKeys()
        assertEquals("aaa", DialKeys.dialKey(snap("aaa"), keys::keyFor, "AA:BB"))
        val tagged = DialKeys.dialKey(snap("bbb", tag = TAG_LOW), keys::keyFor, "AA:BB")
        assertEquals("${keys.keyFor(TAG_LOW)}@AA:BB", tagged)
        assertFalse("no tag in a dial key", TAG_LOW in tagged)
        assertTrue("a namespace no id takes", FormKeys.isForm(tagged))
        assertTrue(
            "another address is another dial key",
            DialKeys.dialKey(snap("bbb", tag = TAG_LOW), keys::keyFor, "CC:DD") != tagged,
        )
    }

    @Test
    fun aRefusedLonelyTagDialToAKeyResolvedContactBacksOffAndItsStreakGrows() {
        // A blinding contact sighted me and will not promote me: it refuses my lonely tag dial every time.
        val keys = FormKeys()
        val contact = snap("bbb", tag = TAG_LOW)
        val dialKey = DialKeys.dialKey(contact, keys::keyFor, "AA:BB")
        val dialKeyOf: (BlePresenceTracker.Snapshot) -> String = { DialKeys.dialKey(it, keys::keyFor, "AA:BB") }
        val entries = HashMap<String, ConnectBackoffEntry>()
        val config = BackoffConfig(baseMs = 10_000, maxMs = 180_000)
        var now = 0L
        val record = DialPlan.InFlight(DialPlan.DialKind.LONELY, peerKey = "bbb", address = "AA:BB", linkedAtStart = false)
        repeat(3) { round ->
            val linked = DialKeys.linkedNow(record, dialKey, linked = emptySet())
            val (streak, nextAt) = ConnectBackoffs.onDialFailed(entries, dialKey, linked, now, config) { 0.5 }
            assertEquals("the streak grows", round + 1, streak)
            val active = ConnectBackoffs.active(entries, now)
            val backoff = DialKeys.backedOff(listOf(contact), active, dialKeyOf)
            assertTrue("the contact's candidate reads it", "bbb" in backoff)
            val candidates = DialPlan.lonelyCandidates(listOf(contact), emptySet(), backoff) { true }
            assertNull(LonelyDialPolicy.pick(mine, 0, Long.MAX_VALUE, candidates, lonelyDialInFlight = false))
            // Present, so the prune keeps the streak.
            ConnectBackoffs.prune(entries, nextAt) { it == dialKey }
            now = nextAt
        }
        assertFalse("never booked under the resolved id", "bbb" in entries)
    }

    @Test
    fun aFailedTagDialNeverBacksOffTheIdItsReplyNamed() {
        val keys = FormKeys()
        val dialKey = DialKeys.dialKey(snap(FormKeys.MARK + "x", tag = TAG_LOW), keys::keyFor, "AA:BB")
        val entries = HashMap<String, ConnectBackoffEntry>()
        val record = DialPlan.InFlight(DialPlan.DialKind.LONELY, peerKey = keys.keyFor(TAG_LOW), address = "AA:BB", linkedAtStart = false)
        // The reply named "ccc" and failed its proof: nothing is booked for "ccc", and an unresolved form is never linked.
        ConnectBackoffs.onDialFailed(entries, dialKey, DialKeys.linkedNow(record, dialKey, setOf("ccc")), 0, BackoffConfig()) { 0.5 }
        assertEquals(setOf(dialKey), entries.keys)
        assertFalse("ccc" in DialKeys.backedOff(listOf(snap("ccc")), ConnectBackoffs.active(entries, 0)) { it.nodeId })
    }

    @Test
    fun aDialWhosePeerLinkedMeanwhileIsNoFailure() {
        val record = DialPlan.InFlight(DialPlan.DialKind.LONELY, peerKey = "bbb", address = "AA:BB", linkedAtStart = false)
        assertTrue("its resolved id linked: a crossing dial", DialKeys.linkedNow(record, "~zzzz…0@AA:BB", setOf("bbb")))
        assertFalse(DialKeys.linkedNow(record, "~zzzz…0@AA:BB", setOf("ccc")))
        assertTrue("a clear dial with no record reads its key", DialKeys.linkedNow(null, "bbb", setOf("bbb")))
        assertFalse("no record, nothing linked", DialKeys.linkedNow(null, "bbb", setOf("ccc")))
    }

    @Test
    fun aDialThatBeganWithItsPeerHeldCrossedNothing() {
        val record = DialPlan.InFlight(DialPlan.DialKind.ORDINARY, peerKey = "bbb", address = "AA:BB", linkedAtStart = true)
        assertFalse(DialKeys.linkedNow(record, "~zzzz…0@AA:BB", setOf("bbb")))
    }

    @Test
    fun aTagAndAddressAHeldDialReachedGivesTheScanNoWork() {
        val keys = FormKeys()
        val peer = snap("bbb", tag = TAG_LOW, suppressor = BlePresenceTracker.Suppressor.None)
        val keyOf: (BlePresenceTracker.Snapshot) -> String = { DialKeys.dialKey(it, keys::keyFor, "AA:BB") }
        val held = setOf(keyOf(peer))
        val free = DialPlan.scanCandidates(blinding, listOf(peer), emptySet(), emptySet(), -90.0, noHeldDial)
        assertEquals(setOf("bbb"), free)
        val got =
            DialPlan.scanCandidates(
                blinding,
                listOf(peer),
                emptySet(),
                emptySet(),
                -90.0,
                dialHeld = { DialKeys.dialHeld(it, held, keyOf) },
            )
        assertEquals(emptySet<String>(), got)
    }

    @Test
    fun aClearAdvertIsNeverReadAgainstTheHeldDials() {
        val clear = snap("aaa")
        assertFalse(DialKeys.dialHeld(clear, setOf("aaa")) { error("no key is built for a clear advert") })
        assertFalse(DialKeys.dialHeld(snap("bbb", tag = TAG_LOW), emptySet()) { error("nor with nothing held") })
    }

    @Test
    fun aKeyResolvedPeerSightedUnlinkedStopsBeingACandidateOnceItsIdHoldsAProvenLinkWithNoNewSighting() {
        // The bluetooth review of step 5a: suppression is read when the dial is decided, never frozen at the sighting.
        val contact = snap("bbb", tag = TAG_LOW, suppressor = BlePresenceTracker.Suppressor.Proven)
        val snaps = listOf(contact)
        assertEquals(
            listOf("bbb"),
            DialPlan.ordinaryCandidates(blinding, snaps, emptySet(), emptySet(), noHeldDial, emptySet()).map { it.nodeId },
        )
        // My tag dial proved bbb: the same snapshot, no new sighting.
        assertEquals(
            emptyList<String>(),
            DialPlan.ordinaryCandidates(blinding, snaps, setOf("bbb"), emptySet(), noHeldDial, setOf("bbb")).map { it.nodeId },
        )
        assertEquals(0, DialPlan.lonelyCandidates(snaps, setOf("bbb"), emptySet(), setOf("bbb")) { true }.size)
        // A link to bbb that never proved itself suppresses nothing on a key's answer.
        assertEquals(
            listOf("bbb"),
            DialPlan.ordinaryCandidates(blinding, snaps, setOf("bbb"), emptySet(), noHeldDial, emptySet()).map {
                it.nodeId
            },
        )
        assertTrue(DialPlan.suppressed(BlePresenceTracker.Suppressor.Linked, "bbb", setOf("bbb"), emptySet()))
        assertFalse(DialPlan.suppressed(BlePresenceTracker.Suppressor.None, "bbb", setOf("bbb"), setOf("bbb")))
    }

    @Test
    fun anAirerChangingItsTagFromOneAddressStillBacksOff() {
        // The bluetooth review of step 5a: every fresh tag is a new dial key, the address's streak carries on.
        val keys = FormKeys()
        val entries = HashMap<String, ConnectBackoffEntry>()
        var now = 0L
        listOf(TAG_LOW, TAG_MINE, TAG_HIGH).forEachIndexed { round, tag ->
            val unresolved = snap(keys.keyFor(tag), tag = tag)
            val dialKey = DialKeys.dialKey(unresolved, keys::keyFor, "AA:BB")
            if (round > 0) {
                val backoff = DialKeys.backedOff(listOf(unresolved), ConnectBackoffs.active(entries, now)) { dialKey }
                assertTrue("a fresh tag at a backed-off address", unresolved.nodeId in backoff)
            }
            val address = DialKeys.addressKey(dialKey)!!
            val (streak, nextAt) = ConnectBackoffs.onDialFailed(entries, address, false, now, BackoffConfig()) { 0.5 }
            assertEquals("the address's streak grows", round + 1, streak)
            ConnectBackoffs.onDialFailed(entries, dialKey, false, now, BackoffConfig()) { 0.5 }
            now = nextAt - 1
        }
        // A contact a key resolves at that address is not held back by the airer's streak, read through backedOff.
        val contact = snap("bbb", tag = TAG_LOW, suppressor = BlePresenceTracker.Suppressor.Proven)
        val contactKey = DialKeys.dialKey(contact, keys::keyFor, "AA:BB")
        assertFalse("bbb" in DialKeys.backedOff(listOf(contact), ConnectBackoffs.active(entries, now)) { contactKey })
        assertNull("a clear dial key has no address streak", DialKeys.addressKey("aaa"))
    }

    @Test
    fun oneUnresolvedTagDialedAtItsTwoSetsAddressesKeepsOneGrowingStreak() {
        // The bluetooth review of step 5a, round 2: its 1M and Coded sets air from two random addresses.
        val keys = FormKeys()
        val handle = keys.keyFor(TAG_LOW)
        val unresolved = snap(handle, tag = TAG_LOW)
        val entries = HashMap<String, ConnectBackoffEntry>()
        var now = 0L
        listOf("AA:BB", "CC:DD", "AA:BB", "CC:DD").forEachIndexed { round, address ->
            val dialKey = DialKeys.dialKey(unresolved, keys::keyFor, address)
            if (round > 0) {
                val backoff = DialKeys.backedOff(listOf(unresolved), ConnectBackoffs.active(entries, now)) { dialKey }
                assertTrue("the other set's address backs off too", handle in backoff)
            }
            val (streak, nextAt) = ConnectBackoffs.onDialFailed(entries, DialKeys.handleKey(dialKey)!!, false, now, BackoffConfig()) { 0.5 }
            assertEquals("one streak for the handle", round + 1, streak)
            now = nextAt - 1
        }
        assertEquals(handle, DialKeys.handleKey(DialKeys.dialKey(unresolved, keys::keyFor, "AA:BB")))
        assertNull("a resolved peer's dial keeps no handle streak", DialKeys.handleKey("~zzzz…0@AA:BB", unresolved = false))
    }

    @Test
    fun aClaimSuppressesOnlyWhileItsClaimantsOwnLinkIsHeld() {
        // ADR 2026-11.xp6d: a claim suppresses our dial only while its claimant's link is held.
        val claimant = Any()
        val claimed = snap("bbb", tag = TAG_LOW, suppressor = BlePresenceTracker.Suppressor.Claim(claimant))
        val snaps = listOf(claimed)
        val linked = setOf("bbb")
        assertEquals(
            "the claimant's link held: suppressed",
            emptyList<String>(),
            DialPlan.ordinaryCandidates(blinding, snaps, linked, emptySet(), noHeldDial, linked) { claimant }.map { it.nodeId },
        )
        // Its link ended, and the claimant relinked, proven: another link, so my dial to the claimed tag goes ahead.
        val relinked = Any()
        assertEquals(
            listOf("bbb"),
            DialPlan.ordinaryCandidates(blinding, snaps, linked, emptySet(), noHeldDial, linked) { relinked }.map { it.nodeId },
        )
        assertEquals(listOf("bbb"), DialPlan.lonelyCandidates(snaps, linked, emptySet(), linked, { relinked }) { true }.map { it.nodeId })
    }

    @Test
    fun aLinkedPeerStaysACandidateOnlyWhenItsAnswerMayNotSuppressADial() {
        val snaps = listOf(snap("aaa", suppressor = BlePresenceTracker.Suppressor.None), snap("bbb"))
        val linked = setOf("aaa", "bbb")
        assertEquals(
            "a hint or an unproven key answer leaves the dial standing",
            listOf("aaa"),
            DialPlan.ordinaryCandidates(blinding, snaps, linked, emptySet(), noHeldDial).map { it.nodeId },
        )
        assertEquals(listOf("aaa"), DialPlan.lonelyCandidates(snaps, linked, emptySet()) { true }.map { it.nodeId })
        assertTrue(
            DialPlan.promotable(
                blinding,
                AdvertOrder.Form.clear("aaa"),
                "aaa",
                -60.0,
                linked,
                -90.0,
                BlePresenceTracker.Suppressor.None,
            ) { false },
        )
        assertFalse(DialPlan.promotable(blinding, AdvertOrder.Form.clear("bbb"), "bbb", -60.0, linked, -90.0) { false })
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

        // A clear id above TAG_LOW and below TAG_HIGH, and one above TAG_MINE.
        const val BETWEEN = "zzzzzzzzzz"
        const val ABOVE_MINE = "zzzzzzzzzzzzzz"
    }

    @Test
    fun aFailedDialToASupersededFormNeverBacksOffThePeersNewForm() {
        // Step 5b's W6: a dial to the form a peer aired before its rotation fails (the set is gone). It was booked under
        // that form's handle and address, so the snapshot under the new form, at its new address, is not backed off,
        // and no failed tag dial ever books the resolved id.
        val handles = mapOf("zzzzold" to "~zzzz…1", "zzzznew" to "~zzzz…2")
        val newer =
            BlePresenceTracker.Snapshot(
                nodeId = "bob",
                protoVersion = 1,
                capabilities = 0L,
                psm = 0x81,
                digestCue = 0,
                smoothedRssi = -60.0,
                dwellMs = 20_000,
                lastSeenAgoMs = 0,
                advertised = "zzzznew",
                blinded = true,
            )
        val oldDialKey = DialKeys.dialKey(newer.copy(advertised = "zzzzold"), { handles.getValue(it) }, "AA:AA")
        val active = setOf(oldDialKey)
        val backed = DialKeys.backedOff(listOf(newer), active) { DialKeys.dialKey(it, { f -> handles.getValue(f) }, "BB:BB") }
        assertFalse("bob" in backed)
        assertFalse(oldDialKey == "bob")
    }
}
