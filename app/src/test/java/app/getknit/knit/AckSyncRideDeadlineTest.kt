package app.getknit.knit

import app.getknit.knit.mesh.AckSync
import app.getknit.knit.mesh.CompositeMeshTransport
import app.getknit.knit.mesh.FakeLoopTransport
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.protocol.FrameType
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The ride deadline (ADR 2026-09.y5f3): a room tick parked for a ride nobody took is sealed once and sent
 * the cheapest way there is — pushed into the author's spool scope, fast-sent over a plane that reaches
 * them, or kept waiting — and what each of those leaves owed. The rig is [AckSyncRig.kt]'s.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AckSyncRideDeadlineTest {
    @Test
    fun aRideNobodyCarriesIsPushedToTheSpoolWhenItsAuthorIsPresentThere() =
        runTest(UnconfinedTestDispatcher()) {
            // The field day: the author's DMs came off the spool, so no carrier ever consulted the hold.
            // Past the deadline the batch is sealed once and pushed straight into the author's scope —
            // nothing on the air, nothing owed, and a re-serve of the post re-owes to a no-op.
            var clock = 0L
            val spooled = CopyOnWriteArrayList<List<String>>()
            val recorder = FastSendRecorder(FakeLoopTransport("recip"))
            val ack =
                ackSyncOn(
                    recorder,
                    "recip",
                    clock = { clock },
                    canSeal = { true },
                    spoolPresent = { true },
                    spoolTick = { a, ids ->
                        spooled.add(ids)
                        AckSync.SpoolTick(sealedWire("recip", a, ids), pushed = true)
                    },
                )

            ack.owe("m1", "author")
            ack.owe("m2", "author")
            clock += AckSync.RIDE_HOLD_MS - 1
            ack.retryPending()
            assertTrue("nothing before the deadline", spooled.isEmpty())

            clock += 2
            ack.retryPending()

            assertEquals("one push covering the batch", listOf(listOf("m1", "m2")), spooled)
            assertTrue("and nothing on the air", recorder.fastSent.isEmpty())
            assertEquals(0, ack.ridingFor("author"))
            ack.owe("m1", "author")
            assertEquals("a custody re-serve does not park it again", 0, ack.ridingFor("author"))
            clock += AckSync.RETRY_BASE_MS
            ack.retryPending()
            assertTrue("and nothing is owed for a retry — the spool converges it", recorder.fastSent.isEmpty())
        }

    @Test
    fun aRideNobodyCarriesGoesOverTheFastPlaneWhenItsAuthorIsOnlyReachable() =
        runTest(UnconfinedTestDispatcher()) {
            // aa27's own baseline, restored: an author heard over the board and nowhere else gets the
            // targeted tick — one seal for the batch, one owed entry as the retry vehicle, the other id in
            // the escalated ledger so its re-serve re-owes to nothing.
            var clock = 0L
            val sealed = CopyOnWriteArrayList<List<String>>()
            val recorder = FastSendRecorder(FakeLoopTransport("recip"))
            recorder.sighted.value = setOf(Peer("author"))
            val ack =
                ackSyncOn(
                    recorder,
                    "recip",
                    clock = { clock },
                    canSeal = { true },
                    sealTick = { a, ids ->
                        sealed.add(ids)
                        sealedWire("recip", a, ids)
                    },
                )

            ack.owe("m1", "author")
            ack.owe("m2", "author")
            clock += AckSync.RIDE_HOLD_MS + 1
            ack.retryPending()

            assertEquals(listOf(listOf("m1", "m2")), sealed)
            assertEquals("one targeted send", 1, recorder.fastSent.size)
            assertEquals(0, ack.ridingFor("author"))
            ack.owe("m2", "author")
            ack.owe("m1", "author")
            assertEquals("neither id is parked again", 0, ack.ridingFor("author"))

            ack.retryPending()
            assertEquals("inside the backoff nothing re-sends", 1, recorder.fastSent.size)
            clock += AckSync.RETRY_BASE_MS
            ack.retryPending()
            assertEquals("past it the same bytes go again", 2, recorder.fastSent.size)
            assertArrayEquals(recorder.fastSent[0].signed, recorder.fastSent[1].signed)
            assertEquals("still one seal", 1, sealed.size)
        }

    /**
     * ADR 2026-10.rp3s: the air route reads short-range reach only. An author the LoRa board alone has heard is not
     * "reachable over the air" for a room tick any more — LoRa's `fastSend` refuses every tick — so the ride stays
     * parked for a link or the spool rather than being sealed into an owed entry nothing can carry.
     */
    @Test
    fun aRideToAnAuthorOnlyTheLoraPlaneHearsStaysParked() =
        runTest(UnconfinedTestDispatcher()) {
            var clock = 0L
            val sealed = CopyOnWriteArrayList<List<String>>()
            val recorder = FastSendRecorder(FakeLoopTransport("recip"))
            val board =
                object : MeshTransport by FakeLoopTransport("recip-board") {
                    override val shortRange = false
                    override val reachable: StateFlow<Set<Peer>> = MutableStateFlow(setOf(Peer("author")))
                }
            val composite = CompositeMeshTransport(listOf(recorder, board), backgroundScope)
            val ack =
                ackSyncOn(composite, "recip", clock = { clock }, canSeal = { true }) { a, ids ->
                    sealed.add(ids)
                    sealedWire("recip", a, ids)
                }

            ack.owe("m1", "author")
            clock += AckSync.RIDE_HOLD_MS + 1
            ack.retryPending()

            assertTrue("the composite still reaches the author over the board", composite.reachable.value.any { it.nodeId == "author" })
            assertTrue("nothing sealed", sealed.isEmpty())
            assertTrue("nothing sent", recorder.fastSent.isEmpty())
            assertEquals("still parked for a link or the spool", 1, ack.ridingFor("author"))

            // A short-range plane that does reach the author is still the air route (Wi-Fi Aware's cue-only peers).
            recorder.sighted.value = setOf(Peer("author"))
            clock += 1
            ack.retryPending()
            assertEquals("sealed once for the air route", listOf(listOf("m1")), sealed)
            assertEquals("and no longer parked", 0, ack.ridingFor("author"))
        }

    @Test
    fun aFastPlaneRideIsDroppedOnceALinkCarriesIt() =
        runTest(UnconfinedTestDispatcher()) {
            var clock = 0L
            val author = Author("author")
            val inner = FakeLoopTransport("recip")
            val recorder = FastSendRecorder(inner)
            recorder.sighted.value = setOf(Peer("author"))
            author.start(backgroundScope)
            val ack =
                ackSyncOn(recorder, "recip", clock = { clock }, canSeal = { true }, sealTick = {
                    a,
                    ids,
                    ->
                    sealedWire("recip", a, ids)
                })

            ack.owe("m1", "author")
            clock += AckSync.RIDE_HOLD_MS + 1
            ack.retryPending()
            assertEquals(1, recorder.fastSent.size)

            inner.connect(author.transport)
            ack.onNeighborAdded(Peer("author"))
            assertEquals("the link is the reliable path home", 1, author.received().count { it.envelope.type == FrameType.CHAT })
            clock += AckSync.RETRY_CAP_MS
            ack.retryPending()
            assertEquals("and the entry is gone", 1, recorder.fastSent.size)
        }

    @Test
    fun theSpoolIsPreferredOverTheAirWhenBothCouldReachTheAuthor() =
        runTest(UnconfinedTestDispatcher()) {
            var clock = 0L
            val spooled = CopyOnWriteArrayList<List<String>>()
            val recorder = FastSendRecorder(FakeLoopTransport("recip"))
            recorder.sighted.value = setOf(Peer("author"))
            val ack =
                ackSyncOn(
                    recorder,
                    "recip",
                    clock = { clock },
                    canSeal = { true },
                    sealTick = { a, ids -> sealedWire("recip", a, ids) },
                    spoolPresent = { true },
                    spoolTick = { a, ids ->
                        spooled.add(ids)
                        AckSync.SpoolTick(sealedWire("recip", a, ids), pushed = true)
                    },
                )

            ack.owe("m1", "author")
            clock += AckSync.RIDE_HOLD_MS + 1
            ack.retryPending()

            assertEquals(1, spooled.size)
            assertTrue("the spool is free; the board is not", recorder.fastSent.isEmpty())
        }

    @Test
    fun aSpoolPushThatFailsAfterSealingKeepsTheSignedTickAsAnOwedBatch() =
        runTest(UnconfinedTestDispatcher()) {
            // The route is chosen before the single seal; a spool that then refuses the push must not
            // cost a second chain key. The signed wire rides every other path as one owed entry.
            var clock = 0L
            val recorder = FastSendRecorder(FakeLoopTransport("recip"))
            val ack =
                ackSyncOn(
                    recorder,
                    "recip",
                    clock = { clock },
                    canSeal = { true },
                    spoolPresent = { true },
                    spoolTick = { a, ids -> AckSync.SpoolTick(sealedWire("recip", a, ids), pushed = false) },
                )

            ack.owe("m1", "author")
            ack.owe("m2", "author")
            clock += AckSync.RIDE_HOLD_MS + 1
            ack.retryPending()

            assertEquals("the sealed wire goes best-effort at once", 1, recorder.fastSent.size)
            clock += AckSync.RETRY_BASE_MS
            ack.retryPending()
            assertEquals("and again on the backoff", 2, recorder.fastSent.size)
            assertArrayEquals(recorder.fastSent[0].signed, recorder.fastSent[1].signed)
        }

    @Test
    fun aRideNobodyCarriesKeepsWaitingWhenItsAuthorIsNowhere() =
        runTest(UnconfinedTestDispatcher()) {
            // aa27's rule survives the deadline: no spool, no plane, no seal. The next sighting is what
            // ends the wait, and it does so at once rather than at the next heartbeat.
            var clock = 0L
            val sealed = CopyOnWriteArrayList<List<String>>()
            val recorder = FastSendRecorder(FakeLoopTransport("recip"))
            val ack =
                ackSyncOn(
                    recorder,
                    "recip",
                    clock = { clock },
                    canSeal = { true },
                    sealTick = { a, ids ->
                        sealed.add(ids)
                        sealedWire("recip", a, ids)
                    },
                )

            ack.owe("m1", "author")
            ack.owe("m2", "author")
            clock += AckSync.RIDE_HOLD_MS + 1
            ack.retryPending()

            assertTrue("no chain key is spent on nobody", sealed.isEmpty())
            assertEquals("both still wait", 2, ack.ridingFor("author"))

            recorder.sighted.value = setOf(Peer("author"))
            ack.onReachable(Peer("author"))

            assertEquals(listOf(listOf("m1", "m2")), sealed)
            assertEquals(1, recorder.fastSent.size)
            assertEquals(0, ack.ridingFor("author"))
        }

    @Test
    fun theRideDeadlineWakesWithoutAHeal() =
        runTest {
            // Like the group debounce: the flushScope wake is the primary trigger and the heartbeat only
            // the backstop. Virtual time drives the delay while the injected clock advances in lockstep.
            var clock = 0L
            val recorder = FastSendRecorder(FakeLoopTransport("recip"))
            recorder.sighted.value = setOf(Peer("author"))
            val ack =
                ackSyncOn(
                    recorder,
                    "recip",
                    clock = { clock },
                    canSeal = { true },
                    sealTick = { a, ids -> sealedWire("recip", a, ids) },
                    flushScope = { backgroundScope },
                )

            ack.owe("m1", "author")
            assertTrue(recorder.fastSent.isEmpty())

            clock += AckSync.RIDE_HOLD_MS + 1
            testScheduler.advanceTimeBy(AckSync.RIDE_HOLD_MS + 1)
            testScheduler.runCurrent()

            assertEquals(1, recorder.fastSent.size)
        }

    @Test
    fun aRideGivenBackKeepsItsOriginalStampSoTheDeadlineDoesNotMove() =
        runTest(UnconfinedTestDispatcher()) {
            // A carrier took the id and its seal fell through just before the deadline. Re-stamping the
            // give-back at `now()` would restart the wait every time that happened.
            var clock = 0L
            val recorder = FastSendRecorder(FakeLoopTransport("recip"))
            recorder.sighted.value = setOf(Peer("author"))
            val ack =
                ackSyncOn(recorder, "recip", clock = { clock }, canSeal = { true }, sealTick = {
                    a,
                    ids,
                    ->
                    sealedWire("recip", a, ids)
                })

            ack.owe("m1", "author")
            clock += AckSync.RIDE_HOLD_MS - 1
            assertEquals(listOf("m1"), ack.takeRiding("author", 4))
            ack.giveBackRiding("author", listOf("m1"))
            clock += 2
            ack.retryPending()

            assertEquals("due on the original stamp", 1, recorder.fastSent.size)
        }

    @Test
    fun anIdACarrierRodeIsNeverReHeld() =
        runTest(UnconfinedTestDispatcher()) {
            val recorder = FastSendRecorder(FakeLoopTransport("recip"))
            val ack = ackSyncOn(recorder, "recip", canSeal = { true }, sealTick = { a, ids -> sealedWire("recip", a, ids) })

            ack.owe("m1", "author")
            assertEquals(listOf("m1"), ack.takeRiding("author", 4))
            ack.rode(listOf("m1"))
            ack.owe("m1", "author")

            assertEquals("the author has it; a re-serve parks nothing", 0, ack.ridingFor("author"))
        }

    @Test
    fun aRideWiderThanOneTickIsSentInChunks() =
        runTest(UnconfinedTestDispatcher()) {
            var clock = 0L
            val sealed = CopyOnWriteArrayList<List<String>>()
            val recorder = FastSendRecorder(FakeLoopTransport("recip"))
            recorder.sighted.value = setOf(Peer("author"))
            val ack =
                ackSyncOn(
                    recorder,
                    "recip",
                    clock = { clock },
                    canSeal = { true },
                    sealTick = { a, ids ->
                        sealed.add(ids)
                        sealedWire("recip", a, ids)
                    },
                )

            repeat(AckSync.MAX_BATCH_ACKS + 1) { ack.owe("m$it", "author") }
            clock += AckSync.RIDE_HOLD_MS + 1
            ack.retryPending()

            assertEquals(listOf(AckSync.MAX_BATCH_ACKS, 1), sealed.map { it.size })
            assertEquals(2, recorder.fastSent.size)
        }
}
