package app.getknit.knit.mesh

import app.getknit.knit.data.RoomDbTest
import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CarryLedger]: who holds each carried frame (the custody-carry-policy contract's §3) and what each proven peer was
 * released and did not keep (§6). A Room test only for the two cases that read the repository's own events; the rest
 * drive the ledger directly, with a hand-held clock and a table of which links are up.
 */
@Suppress("LargeClass") // the contract's §3 and §6 bullet by bullet, and the progress the Aware plane reads
class CarryLedgerTest : RoomDbTest() {
    private val bt1 = Any()
    private val bt2 = Any()
    private val aware1 = Any()
    private val aware2 = Any()
    private val direct1 = Any()

    /** Which link each plane holds to each peer now: what [CarryLedger.Links] answers from. */
    private class Air : CarryLedger.Links {
        val up = HashMap<Pair<String, TransportKind>, Any?>()

        override fun carries(
            plane: TransportKind,
            peer: String,
            link: Any?,
        ): Boolean = up.containsKey(peer to plane) && up[peer to plane] === link
    }

    private val air = Air()
    private val metrics = MeshMetrics()

    private fun frame(
        id: String,
        recipient: String? = null,
    ) = RelayEnvelope(type = FrameType.CHAT, id = id, senderId = "s", recipientId = recipient, payload = ByteArray(0))

    private fun ledger(vararg held: String) = CarryLedger(metrics = metrics).apply { held.forEach { onKept(it, source = null) } }

    private fun CarryLedger.digest(
        peer: String,
        ids: List<String>,
        now: Long,
        plane: TransportKind = TransportKind.Bluetooth,
        link: Any? = bt1,
        additive: Boolean = false,
    ) = onDigest(peer, ids, plane, link, additive, now, air)

    private fun CarryLedger.release(
        peer: String,
        ids: List<String>,
        now: Long,
        plane: TransportKind = TransportKind.Bluetooth,
        link: Any? = bt1,
        viewFiltered: Boolean = false,
        recipient: String? = null,
    ) = released(peer, ids.map { frame(it, recipient) }, LinkCarrier(plane, link), viewFiltered, now)

    private fun CarryLedger.blocked(
        peer: String,
        id: String,
        now: Long,
        recipient: String? = null,
    ) = blocked(peer, frame(id, recipient), now, air)

    private fun notKept() = metrics.frames().custodyNotKept

    // --- the ledger (§3) ---

    @Test
    fun aDigestListsItsPeerAmongTheHoldersOfEveryHeldIdItNames() {
        val carry = ledger("a")
        carry.digest("p", listOf("a", "unheld"), now = 0L)
        assertEquals(1, carry.spread("a"))
        assertEquals("an id we do not hold gets no entry", 0, carry.spread("unheld"))
        carry.onKept("unheld", source = null)
        assertEquals("nor once it is held: the digest came before", 0, carry.spread("unheld"))
    }

    @Test
    fun aDigestOfAHundredThousandJunkIdsKeepsNoneOfThem() {
        val carry = ledger("a")
        carry.digest("p", (1..100_000).map { "junk$it" } + "a", now = 0L)
        assertEquals(1, carry.spread("a"))
        val snapshot = carry.snapshot("n", source = null)
        assertEquals(0, (1..100_000).count { snapshot.spread("junk$it") > 0 })
    }

    @Test
    fun theSourceCountsInTheFramesOwnWriteAndIsRecordedOnlyIfTheRowIsKept() {
        val carry = ledger("a")
        carry.digest("q", listOf("a"), now = 0L)
        assertEquals("the snapshot counts the source", 1, carry.snapshot("n", source = "p").spread("n"))
        assertEquals("and the rows already held", 1, carry.snapshot("n", source = "p").spread("a"))
        assertEquals("but records nothing", 0, carry.spread("n"))
        carry.onKept("n", source = "p")
        assertEquals("recorded once the row is held", 1, carry.spread("n"))
        assertEquals("a source already among the holders counts once", 1, carry.snapshot("n", source = "p").spread("n"))
    }

    @Test
    fun aCreditedHandOffAddsAHolderAndABareReleaseNone() {
        val carry = ledger("a", "b")
        carry.release("p", listOf("a", "b"), now = 0L)
        assertEquals("released is not held", 0, carry.spread("a"))
        carry.handed("p", listOf("a"))
        assertEquals(1, carry.spread("a"))
        assertEquals("a round whose link dropped first credits nothing", 0, carry.spread("b"))
    }

    @Test
    fun spreadStopsAtSpreadMaxAndANinthHolderIsNotRecorded() {
        val carry = ledger("a")
        (1..CarryLedger.SPREAD_MAX + 1).forEach { carry.digest("p$it", listOf("a"), now = 0L) }
        assertEquals(CarryLedger.SPREAD_MAX, carry.spread("a"))
        assertEquals("a source past the cap reads the cap", CarryLedger.SPREAD_MAX, carry.snapshot("a", source = "p99").spread("a"))
        carry.digest("p1", emptyList(), now = 0L)
        assertEquals("one left: the ninth was never recorded", CarryLedger.SPREAD_MAX - 1, carry.spread("a"))
    }

    @Test
    fun aLaterDigestIsThePeersLatestWord() {
        val carry = ledger("a", "b")
        carry.digest("p", listOf("a", "b"), now = 0L)
        carry.digest("p", listOf("a"), now = 1L)
        assertEquals(1, carry.spread("a"))
        assertEquals("a held id the digest lacks loses the peer", 0, carry.spread("b"))
    }

    @Test
    fun aPendingIdNotYetJudgedKeepsItsHolderThroughALackingDigest() {
        air.up["p" to TransportKind.Bluetooth] = bt1
        val carry = ledger("a")
        carry.digest("p", listOf("a"), now = 0L)
        carry.release("p", listOf("a"), now = 0L)
        carry.digest("p", emptyList(), now = 1_000L)
        assertEquals("inside the judging delay: still a holder", 1, carry.spread("a"))
    }

    @Test
    fun holdersDieWithTheirRow() {
        val carry = ledger("a", "b", "c")
        carry.digest("p", listOf("a", "b", "c"), now = 0L)
        carry.onLeft("a")
        carry.retainOnly(setOf("c"))
        assertEquals(listOf(0, 0, 1), listOf("a", "b", "c").map(carry::spread))
        carry.onKept("a", source = null)
        assertEquals("held again, it starts from none", 0, carry.spread("a"))
    }

    @Test
    fun anEvictedRowLosesItsHoldersThroughTheRepositorysOwnEvents() =
        runTest {
            val carry = CarryLedger()
            val repo = ForwardRepository(db.forwardDao(), StoreDigest(), db, maxRows = 2, carry = carry)
            assertTrue(repo.store(dm("a", sentAt = 1L), ForwardStore.ORIGIN_RELAY, now = 0L))
            assertTrue(repo.store(dm("b", sentAt = 2L), ForwardStore.ORIGIN_RELAY, now = 0L))
            carry.digest("p", listOf("a", "b"), now = 0L)
            carry.digest("q", listOf("a"), now = 0L)
            assertEquals(listOf(2, 1), listOf(carry.spread("a"), carry.spread("b")))

            // At the cap the row more neighbours carry goes first (§5), and with it its holders.
            assertTrue(repo.store(dm("c", sentAt = 3L), ForwardStore.ORIGIN_RELAY, now = 0L))
            assertFalse(repo.has("a"))
            assertEquals(listOf(0, 1, 0), listOf("a", "b", "c").map(carry::spread))
            repo.remove("b")
            assertEquals("a receipt's purge takes them too", 0, carry.spread("b"))
            assertTrue(repo.store(dm("a", sentAt = 1L), ForwardStore.ORIGIN_RELAY, now = 0L, source = "r"))
            assertEquals("stored again, only its new source holds it", 1, carry.spread("a"))
        }

    @Test
    fun aNewcomerItsOwnWriteEvictedRecordsNoSource() =
        runTest {
            val carry = CarryLedger()
            val repo = ForwardRepository(db.forwardDao(), StoreDigest(), db, maxRows = 1, carry = carry)
            assertTrue(repo.store(dm("held", sentAt = 2L), ForwardStore.ORIGIN_RELAY, now = 0L))
            // The source makes the older newcomer the one to go: it evicts itself.
            assertFalse(repo.store(dm("back", sentAt = 1L), ForwardStore.ORIGIN_RELAY, now = 0L, source = "p"))
            assertEquals(0, carry.spread("back"))
            assertTrue(repo.has("held"))
        }

    // --- the memo (§6) ---

    @Test
    fun aPendingIdIsNotReleasedAgain() {
        air.up["p" to TransportKind.Bluetooth] = bt1
        val carry = ledger("a")
        carry.release("p", listOf("a"), now = 0L)
        assertTrue(carry.blocked("p", "a", now = 1L))
        assertFalse("another peer's memo is its own", carry.blocked("q", "a", now = 1L))
    }

    @Test
    fun aDigestInsideTheJudgingDelayJudgesNothing() {
        air.up["p" to TransportKind.Bluetooth] = bt1
        air.up["p" to TransportKind.WifiAware] = aware1
        val carry = ledger("a", "b")
        carry.release("p", listOf("a"), now = 0L)
        carry.release("p", listOf("b"), now = 0L, plane = TransportKind.WifiAware, link = aware1)
        carry.digest("p", emptyList(), now = CarryLedger.JUDGE_AFTER_BLUETOOTH_MS - 1)
        assertEquals("Aware's delay passed: b judged", 1L, notKept())
        carry.digest("p", emptyList(), now = CarryLedger.JUDGE_AFTER_BLUETOOTH_MS)
        assertEquals("Bluetooth's passed: a judged", 2L, notKept())
    }

    @Test
    fun aDigestJudgesKeptNotKeptAndNoLongerHeld() {
        air.up["p" to TransportKind.Bluetooth] = bt1
        val carry = ledger("a", "b", "c")
        carry.release("p", listOf("a", "b", "c"), now = 0L)
        carry.onLeft("c")
        val at = CarryLedger.JUDGE_AFTER_BLUETOOTH_MS
        carry.digest("p", listOf("a"), now = at)
        assertFalse("listed: kept, and it leaves", carry.blocked("p", "a", now = at))
        assertTrue("lacking and held: not kept", carry.blocked("p", "b", now = at))
        assertEquals("one mark, not two", 1L, notKept())
        carry.onKept("c", source = null)
        assertFalse("no longer held: it just left", carry.blocked("p", "c", now = at))
    }

    @Test
    fun aNotKeptPeerLeavesThatFramesHolders() {
        air.up["p" to TransportKind.Bluetooth] = bt1
        val carry = ledger("a")
        carry.handed("p", listOf("a"))
        carry.release("p", listOf("a"), now = 0L)
        carry.digest("p", emptyList(), now = CarryLedger.JUDGE_AFTER_BLUETOOTH_MS)
        assertEquals(0, carry.spread("a"))
    }

    @Test
    fun aMarkLapsesAndALaterDigestNeverRefreshesIt() {
        air.up["p" to TransportKind.Bluetooth] = bt1
        val carry = ledger("a")
        carry.release("p", listOf("a"), now = 0L)
        val marked = CarryLedger.JUDGE_AFTER_BLUETOOTH_MS
        carry.digest("p", emptyList(), now = marked)
        carry.digest("p", emptyList(), now = marked + CarryLedger.NOT_KEPT_LAPSE_MS - 1)
        assertTrue(carry.blocked("p", "a", now = marked + CarryLedger.NOT_KEPT_LAPSE_MS - 1))
        carry.digest("p", listOf("a"), now = marked + 2)
        assertTrue("a digest that shows it does not clear it", carry.blocked("p", "a", now = marked + 3))
        assertFalse(carry.blocked("p", "a", now = marked + CarryLedger.NOT_KEPT_LAPSE_MS))
    }

    @Test
    fun aPendingIdLapsesUnjudged() {
        val carry = ledger("a")
        carry.release("p", listOf("a"), now = 0L, plane = TransportKind.WifiAware, link = aware1)
        assertTrue(carry.blocked("p", "a", now = CarryLedger.NOT_KEPT_LAPSE_MS - 1))
        // The digest meets the lapsed id first, as `ForwardSync.onDigest` runs it: its own lapse must drop it unjudged.
        carry.digest("p", emptyList(), now = CarryLedger.NOT_KEPT_LAPSE_MS, plane = TransportKind.WifiAware, link = aware2)
        assertEquals("never judged", 0L, notKept())
        assertFalse(carry.blocked("p", "a", now = CarryLedger.NOT_KEPT_LAPSE_MS))
    }

    @Test
    fun aReleaseWhoseBluetoothLinkEndedIsNotJudgedThoughAnotherPlaneHoldsThePeer() {
        air.up["p" to TransportKind.Bluetooth] = bt1
        val carry = ledger("a")
        carry.release("p", listOf("a"), now = 0L)
        air.up.remove("p" to TransportKind.Bluetooth)
        air.up["p" to TransportKind.WifiAware] = aware1
        val at = CarryLedger.JUDGE_AFTER_BLUETOOTH_MS
        // The digest meets the pending id first, as `ForwardSync.onDigest` runs it: its own ended-link check must drop it.
        carry.digest("p", emptyList(), now = at, plane = TransportKind.WifiAware, link = aware1)
        assertEquals("pending ended with its link, never judged", 0L, notKept())
        assertFalse(carry.blocked("p", "a", now = at))
    }

    @Test
    fun aBluetoothRelinkClearsThePeersMarks() {
        air.up["p" to TransportKind.Bluetooth] = bt1
        val carry = ledger("a", "b")
        carry.release("p", listOf("a", "b"), now = 0L)
        val at = CarryLedger.JUDGE_AFTER_BLUETOOTH_MS
        carry.digest("p", emptyList(), now = at)
        assertTrue(carry.blocked("p", "a", now = at))
        air.up["p" to TransportKind.Bluetooth] = bt2
        carry.digest("p", emptyList(), now = at + 1, link = bt2)
        assertFalse("a new Bluetooth link: the marks clear", carry.blocked("p", "a", now = at + 1))

        carry.release("p", listOf("a"), now = at + 1, link = bt2)
        carry.digest("p", emptyList(), now = 2 * at + 1, link = bt2)
        assertTrue(carry.blocked("p", "a", now = 2 * at + 1))
        carry.relinked("p", TransportKind.Bluetooth)
        assertFalse("the plane's own relink report clears them too", carry.blocked("p", "a", now = 2 * at + 1))
    }

    @Test
    fun aNewAwarePathClearsNothingAndItsDigestJudgesTheLastPathsReleases() {
        val carry = ledger("a", "b")
        carry.release("p", listOf("a"), now = 0L, plane = TransportKind.WifiAware, link = aware1)
        // The path closed at quiescence; the next one's digest judges what the last one released.
        val at = CarryLedger.JUDGE_AFTER_FAST_MS
        assertTrue("pending survives the path", carry.blocked("p", "a", now = at))
        carry.digest("p", emptyList(), now = at, plane = TransportKind.WifiAware, link = aware2)
        assertEquals(1L, notKept())
        carry.digest("p", emptyList(), now = at + 1, plane = TransportKind.WifiAware, link = Any())
        assertTrue("a new path clears no mark", carry.blocked("p", "a", now = at + 1))

        carry.release("p", listOf("b"), now = at, plane = TransportKind.WifiAware, link = aware2)
        carry.answerCut("p", TransportKind.WifiAware)
        assertFalse("a path cut with an answer still live ends its pending", carry.blocked("p", "b", now = at + 1))
        carry.digest("p", emptyList(), now = 2 * at + 1, plane = TransportKind.WifiAware, link = Any())
        assertEquals("unjudged", 1L, notKept())
    }

    @Test
    fun aFrameAddressedToThePeerIsNeverPendingNorMarked() {
        air.up["p" to TransportKind.Bluetooth] = bt1
        val carry = ledger("dm")
        carry.release("p", listOf("dm"), now = 0L, recipient = "p")
        assertFalse(carry.blocked("p", "dm", now = 1L, recipient = "p"))
        carry.digest("p", emptyList(), now = CarryLedger.JUDGE_AFTER_BLUETOOTH_MS)
        assertEquals(0L, notKept())
        assertFalse("released again at its next digest", carry.blocked("p", "dm", now = 1L, recipient = "p"))
    }

    @Test
    fun theMemoKeepsSixtyFourPeersTheLeastRecentlyWrittenOutFirst() {
        val carry = ledger("a")
        (0..CarryLedger.MEMO_MAX_PEERS).forEach {
            carry.release("p$it", listOf("a"), now = 0L, plane = TransportKind.WifiAware, link = aware1)
        }
        assertFalse("the first peer's memo went", carry.blocked("p0", "a", now = 1L))
        assertTrue((1..CarryLedger.MEMO_MAX_PEERS).all { carry.blocked("p$it", "a", now = 1L) })
    }

    @Test
    fun aDirectLinkDigestAddsHoldersAndJudgesOnlyWhatItsViewWasAnswered() {
        air.up["p" to TransportKind.Bluetooth] = bt1
        air.up["p" to TransportKind.DirectLink] = direct1
        val carry = ledger("radio", "view", "shown", "held")
        carry.digest("p", listOf("held"), now = 0L)
        carry.release("p", listOf("radio"), now = 0L)
        carry.release("p", listOf("view"), now = 0L, viewFiltered = true)
        carry.release("p", listOf("shown"), now = 0L)
        val at = CarryLedger.JUDGE_AFTER_BLUETOOTH_MS
        carry.digest("p", listOf("shown"), now = at, plane = TransportKind.DirectLink, link = direct1, additive = true)

        assertTrue("a radio release its lack cannot judge stays pending", carry.blocked("p", "radio", now = at))
        assertEquals("the view's own release is judged", 1L, notKept())
        assertFalse("what it lists is kept", carry.blocked("p", "shown", now = at))
        assertEquals("and held by the peer", 1, carry.spread("shown"))
        assertEquals("it replaces no radio digest's word", 1, carry.spread("held"))
    }

    @Test
    fun aProvenPeerDigestingForHoursLeavesNoPerPeerSetLargerThanCustodysLiveIds() {
        // Custody turns over (one new frame a minute, the oldest out past [cap]) and the peer keeps none of what it is
        // released: every per-peer set stays inside custody's live ids, whatever judging does or does not happen.
        val carry = ledger()
        val cap = 50
        val live = ArrayDeque<String>()
        var worst = 0
        (0 until 6 * 60).forEach { minute ->
            val now = minute * 60_000L
            val id = "f$minute"
            carry.onKept(id, source = null)
            live.addLast(id)
            if (live.size > cap) {
                live.removeFirst()
                carry.retainOnly(live.toSet())
            }
            val plane = if (minute % 2 == 0) TransportKind.WifiAware else TransportKind.DirectLink
            carry.digest("p", live.filter { it.hashCode() % 3 == 0 } + "junk$minute", now, plane, link = Any(), additive = minute % 4 == 1)
            val owed = live.filterNot { carry.blocked("p", it, now) }
            carry.release("p", owed, now, plane = TransportKind.WifiAware, link = aware1, viewFiltered = minute % 4 == 1)
            worst = maxOf(worst, carry.memoSize("p"))
            assertTrue("minute $minute: ${carry.memoSize("p")} > ${live.size}", carry.memoSize("p") <= live.size)
        }
        assertTrue("the memo was exercised", worst > 0)
    }

    @Test
    fun theLedgerAsksTheTransportsLinksWithItsOwnLockReleased() {
        // A plane that answers `carries` under its own lock may read custody's progress under that lock elsewhere: were
        // the ledger's lock held across the ask, the two would deadlock. Another thread reads the ledger from inside it.
        val carry = ledger("a")
        var reads = 0
        val probing =
            CarryLedger.Links { _, peer, _ ->
                val reader = Thread { carry.progressMark(peer) }
                reader.start()
                reader.join(LOCK_PROBE_MS)
                assertFalse("the ledger's lock was held across the ask", reader.isAlive)
                reads++
                true
            }
        carry.release("p", listOf("a"), now = 0L)
        assertTrue(carry.blocked("p", frame("a"), now = 1L, links = probing))
        carry.onDigest("p", emptyList(), TransportKind.Bluetooth, bt1, additive = false, now = 2L, links = probing)
        assertEquals(2, reads)
    }

    // --- custody progress, for the Wi-Fi Aware hold-off ---

    @Test
    fun progressMovesOnAKeptArrivalAndAReleaseJudgedKeptNeverOnABareRelease() {
        val carry = ledger("a")
        val before = carry.progressMark("p")
        carry.release("p", listOf("a"), now = 0L, plane = TransportKind.WifiAware, link = aware1)
        assertEquals("a bare release", before, carry.progressMark("p"))
        carry.digest("p", listOf("a"), now = CarryLedger.JUDGE_AFTER_FAST_MS, plane = TransportKind.WifiAware, link = aware2)
        val judged = carry.progressMark("p")
        assertTrue(judged > before)
        carry.onKept("n", source = "p")
        assertTrue(carry.progressMark("p") > judged)
        val arrived = carry.progressMark("p")
        carry.onKept("n", source = "p")
        assertEquals("a frame already held moves nothing", arrived, carry.progressMark("p"))
    }

    @Test
    fun freshTurnsTrueOnlyForANewFrameThePeersLastDigestLackedThatDidNotComeFromIt() {
        val carry = ledger("a")
        carry.digest("p", listOf("a"), now = 0L, plane = TransportKind.WifiAware, link = aware1)
        carry.watchFresh("p")
        carry.onKept("a", source = null)
        carry.onKept("fromPeer", source = "p")
        assertFalse("held already, or the peer's own", carry.fresh("p"))
        carry.onKept("new", source = "q")
        assertTrue(carry.fresh("p"))
        carry.watchFresh("p")
        assertFalse("a new watch starts unseen", carry.fresh("p"))
        carry.watchFresh("stranger")
        carry.onKept("newer", source = null)
        assertFalse("a peer custody keeps no word for is never fresh", carry.fresh("stranger"))
    }

    @Test
    fun theDigestMarkMovesWithEveryProvenDigest() {
        val carry = ledger()
        assertEquals(0L, carry.digestSeen("p"))
        carry.digest("p", emptyList(), now = 0L, plane = TransportKind.WifiAware, link = aware1)
        val first = carry.digestSeen("p")
        carry.digest("p", emptyList(), now = 1L, plane = TransportKind.WifiAware, link = aware1)
        assertTrue(first > 0 && carry.digestSeen("p") > first)
    }

    @Test
    fun theDefaultProgressIsTodaysThrottle() {
        val none = CustodyProgress.NONE
        none.watchFresh("p")
        assertEquals(listOf(0L, 0L), listOf(none.progressMark("p"), none.digestSeen("p")))
        assertFalse(none.fresh("p"))
    }

    // --- the contract's constants ---

    @Test
    fun theConstantsAreTheContracts() {
        assertEquals(8, CarryLedger.SPREAD_MAX)
        assertEquals(64, CarryLedger.MEMO_MAX_PEERS)
        assertEquals(600_000L, CarryLedger.NOT_KEPT_LAPSE_MS)
        assertEquals(20_000L, CarryLedger.JUDGE_AFTER_BLUETOOTH_MS)
        assertEquals(5_000L, CarryLedger.JUDGE_AFTER_FAST_MS)
        assertEquals(
            listOf(20_000L, 5_000L, 5_000L, 20_000L),
            listOf(TransportKind.Bluetooth, TransportKind.WifiAware, TransportKind.DirectLink, TransportKind.Other)
                .map(CarryLedger::judgeAfterMs),
        )
    }

    private fun dm(
        id: String,
        sentAt: Long,
    ): CarriedFrame {
        val env =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = "sender",
                sentAt = sentAt,
                recipientId = "peer",
                payload = WireCodec.encodePayload(ChatContent(body = "")),
            )
        return CarriedFrame(env, ByteArray(0), WireCodec.encodeEnvelope(env))
    }

    private companion object {
        // Long enough for an unblocked read on a loaded runner; a held lock never lets it through.
        const val LOCK_PROBE_MS = 5_000L
    }
}
