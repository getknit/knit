package app.getknit.knit

import app.getknit.knit.mesh.AckSync
import app.getknit.knit.mesh.FakeLoopTransport
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.ReceiptContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Exercises [AckSync]'s delay-tolerant broadcast/group delivery tick on the JVM with [FakeLoopTransport].
 * The message author is a plain transport that just records the receipts it receives; the recipient runs the
 * [AckSync]. Note [FakeLoopTransport] inherits the interface's no-op [app.getknit.knit.mesh.MeshTransport.fastSend],
 * so a best-effort coordination-plane tick to a non-neighbor author is (correctly) not observed — which is
 * exactly the lost-tick case these tests then recover once the author becomes a live neighbor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AckSyncTest {
    /** The message author: records every frame it receives, exposing the delivery-receipt ack ids. */
    private class Author(
        val id: String,
    ) {
        val transport = FakeLoopTransport(id)
        private val received = CopyOnWriteArrayList<InboundFrame>()

        fun start(scope: CoroutineScope) {
            scope.launch { transport.inbound.collect { received.add(it) } }
        }

        fun received(): List<InboundFrame> = received.toList()

        fun receipts(): List<InboundFrame> = received.filter { it.envelope.type == FrameType.RECEIPT }

        fun ackIds(): List<String> = receipts().mapNotNull { WireCodec.decodePayload<ReceiptContent>(it.envelope.payload)?.ackId }
    }

    private fun ackSyncOn(
        transport: MeshTransport,
        id: String,
        clock: () -> Long = { 0L },
        canSeal: suspend (String) -> Boolean = { false },
        originateTick: suspend (String, List<String>) -> Boolean = { _, _ -> false },
        flushScope: () -> CoroutineScope? = { null },
        sealTick: suspend (String, List<String>) -> WireEnvelope? = { _, _ -> null },
    ) = AckSync(
        transport = transport,
        selfId = { id },
        signRaw = { byteArrayOf(SIG_MARKER) },
        now = clock,
        sealTick = sealTick,
        canSeal = canSeal,
        originateTick = originateTick,
        flushScope = flushScope,
    )

    /** A stand-in sealed tick: a signed CHAT-shaped wire whose bytes identify the (author, first-ackId) seal. */
    private fun sealedWire(
        me: String,
        authorId: String,
        ackIds: List<String>,
    ): WireEnvelope {
        val env =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = "sealed-${ackIds.first()}",
                senderId = me,
                sentAt = 1L,
                recipientId = authorId,
                payload = WireCodec.encodePayload(ReceiptContent(ackIds.joinToString("+"))),
            )
        val signed = WireCodec.encodeEnvelope(env)
        return WireEnvelope(relay = false, sig = byteArrayOf(SIG_MARKER), signed = signed)
    }

    /** Records coordination-plane [MeshTransport.fastSend] attempts, delegating everything else. */
    private class FastSendRecorder(
        inner: FakeLoopTransport,
    ) : MeshTransport by inner {
        val fastSent = CopyOnWriteArrayList<WireEnvelope>()

        override fun fastSend(
            wire: WireEnvelope,
            to: Peer,
        ) {
            fastSent.add(wire)
        }
    }

    @Test
    fun tickReachesAuthorWhenAlreadyALiveNeighbor() =
        runTest(UnconfinedTestDispatcher()) {
            val author = Author("author")
            val recip = FakeLoopTransport("recip")
            recip.connect(author.transport)
            author.start(backgroundScope)
            val ack = ackSyncOn(recip, "recip")

            ack.owe("m1", "author")

            assertEquals(listOf("m1"), author.ackIds())
            val receipt = author.receipts().first()
            assertFalse("a delivery receipt must never be flooded", receipt.wire.relay)
            assertArrayEquals("a delivery receipt must be signed", byteArrayOf(SIG_MARKER), receipt.wire.sig)
        }

    @Test
    fun tickIsHeldWhileAuthorUnreachableThenDeliveredOnReconnect() =
        runTest(UnconfinedTestDispatcher()) {
            // The field case: the author was out of range when we delivered its broadcast/group message, so the
            // one-shot best-effort tick had nowhere to go — it must land once the author comes back.
            val author = Author("author")
            val recip = FakeLoopTransport("recip")
            author.start(backgroundScope)
            val ack = ackSyncOn(recip, "recip")

            ack.owe("m1", "author") // not connected → best-effort fast-send no-ops, the tick is remembered
            assertTrue("nothing delivered while the author is out of range", author.ackIds().isEmpty())

            recip.connect(author.transport)
            ack.onNeighborAdded(Peer("author")) // author reconnected as a live neighbor

            assertEquals(listOf("m1"), author.ackIds())
        }

    @Test
    fun retryPendingResendsToAReconnectedAuthor() =
        runTest(UnconfinedTestDispatcher()) {
            val author = Author("author")
            val recip = FakeLoopTransport("recip")
            author.start(backgroundScope)
            val ack = ackSyncOn(recip, "recip")

            ack.owe("m1", "author")
            assertTrue(author.ackIds().isEmpty())

            recip.connect(author.transport)
            ack.retryPending()

            assertEquals(listOf("m1"), author.ackIds())
        }

    @Test
    fun tickDeliveredOverALiveLinkIsNotResentForever() =
        runTest(UnconfinedTestDispatcher()) {
            val author = Author("author")
            val recip = FakeLoopTransport("recip")
            recip.connect(author.transport)
            author.start(backgroundScope)
            val ack = ackSyncOn(recip, "recip")

            ack.owe("m1", "author") // sent over the live link → dropped, nothing left to retry
            ack.retryPending()

            assertEquals("one tick, no perpetual resend once it has a live path home", listOf("m1"), author.ackIds())
        }

    @Test
    fun neverAcksOurOwnMessage() =
        runTest(UnconfinedTestDispatcher()) {
            val author = Author("author")
            val recip = FakeLoopTransport("recip")
            recip.connect(author.transport)
            author.start(backgroundScope)
            val ack = ackSyncOn(recip, "recip")

            ack.owe("m1", "recip") // author == us: never ack our own send
            ack.retryPending()

            assertTrue(author.ackIds().isEmpty())
        }

    @Test
    fun agedOutTickIsSweptNotResent() =
        runTest(UnconfinedTestDispatcher()) {
            var clock = 0L
            val author = Author("author")
            val recip = FakeLoopTransport("recip")
            author.start(backgroundScope)
            val ack = ackSyncOn(recip, "recip", clock = { clock })

            ack.owe("m1", "author") // held while the author is unreachable
            clock += 25L * 60 * 60_000 // past the 24h owed TTL
            recip.connect(author.transport)
            ack.retryPending() // sweep drops it before any resend

            assertTrue("an aged-out tick is swept, not resent", author.ackIds().isEmpty())
        }

    @Test
    fun aSealedTickIsSealedOnceAndResentVerbatim() =
        runTest(UnconfinedTestDispatcher()) {
            // Sealing consumes a ratchet chain key, so the tick is sealed at owe() time and every retry
            // re-sends the cached bytes verbatim — never one key per heartbeat toward an offline author.
            // canSeal defaults false here, so this is the non-escalated form (the raced/legacy path: the
            // author was judged not escalation-eligible even though the seal itself succeeds).
            var seals = 0
            val author = Author("author")
            val recip = FakeLoopTransport("recip")
            author.start(backgroundScope)
            val ack =
                ackSyncOn(recip, "recip") { authorId, ackIds ->
                    seals++
                    sealedWire("recip", authorId, ackIds)
                }

            ack.owe("m1", "author") // unreachable: sealed once, held
            ack.retryPending() // still unreachable: fastSend no-ops, entry kept
            ack.retryPending()
            ack.owe("m1", "author") // a re-delivery re-owes: the cached seal must survive, not re-seal
            recip.connect(author.transport)
            ack.retryPending() // live link: the cached bytes go home and the entry drops
            ack.retryPending()

            assertEquals("one seal per owed tick, however many retries", 1, seals)
            val chats = author.received().filter { it.envelope.type == FrameType.CHAT }
            assertEquals(1, chats.size)
            assertFalse("the sealed tick stays point-to-point", chats.single().wire.relay)
            assertArrayEquals(
                "retries re-send the sealed bytes verbatim",
                sealedWire("recip", "author", listOf("m1")).signed,
                chats.single().wire.signed,
            )
            assertTrue("no cleartext receipt when the tick sealed", author.receipts().isEmpty())
        }

    @Test
    fun aNullSealFallsBackToTheCleartextReceipt() =
        runTest(UnconfinedTestDispatcher()) {
            val author = Author("author")
            val recip = FakeLoopTransport("recip")
            recip.connect(author.transport)
            author.start(backgroundScope)
            val ack = ackSyncOn(recip, "recip") { _, _ -> null } // author can't read a sealed tick

            ack.owe("m1", "author")

            assertEquals(listOf("m1"), author.ackIds())
            assertTrue(author.received().none { it.envelope.type == FrameType.CHAT })
        }

    @Test
    fun retriesDrainOldestFirst() =
        runTest(UnconfinedTestDispatcher()) {
            var clock = 0L
            val author = Author("author")
            val recip = FakeLoopTransport("recip")
            author.start(backgroundScope)
            val ack = ackSyncOn(recip, "recip", clock = { clock })

            ack.owe("m-old", "author")
            clock += 1_000
            ack.owe("m-new", "author")
            recip.connect(author.transport)
            ack.retryPending()

            assertEquals("the entry closest to its TTL retries first", listOf("m-old", "m-new"), author.ackIds())
        }

    @Test
    fun anAbsentCapableAuthorsTicksBatchAndOriginateOnce() =
        runTest(UnconfinedTestDispatcher()) {
            // The custody escalation: acks toward an absent capable author accumulate, then one batched
            // tick is originated (relay = true → custodied). A later re-owe of an escalated id (the
            // exists-gate re-fires on every custody re-serve) must no-op, not re-seal.
            var clock = 0L
            val originated = CopyOnWriteArrayList<List<String>>()
            val author = Author("author")
            val recip = FakeLoopTransport("recip")
            author.start(backgroundScope)
            val ack =
                ackSyncOn(
                    recip,
                    "recip",
                    clock = { clock },
                    canSeal = { true },
                    originateTick = { _, ids ->
                        originated.add(ids)
                        true
                    },
                )

            ack.owe("m1", "author", escalatable = true)
            ack.owe("m2", "author", escalatable = true)
            ack.owe("m3", "author", escalatable = true)
            assertTrue("nothing sent while the batch debounces", author.received().isEmpty())
            assertTrue("nothing originated before the debounce", originated.isEmpty())

            clock += AckSync.TICK_BATCH_DEBOUNCE_MS + 1
            ack.retryPending() // the heal backstop flushes the due batch

            assertEquals(listOf(listOf("m1", "m2", "m3")), originated)

            ack.owe("m1", "author", escalatable = true) // custody re-serve re-acks an escalated id
            clock += AckSync.TICK_BATCH_DEBOUNCE_MS + 1
            ack.retryPending()
            assertEquals("an escalated id never re-seals or re-originates", 1, originated.size)
        }

    @Test
    fun aRoomTickTowardAnAbsentAuthorWaitsForARideInsteadOfSealing() =
        runTest(UnconfinedTestDispatcher()) {
            // ADR 2026-09.aa27. The room never escalates, so an absent author's tick used to seal a
            // standalone frame with nowhere to go — a chain key spent up front, then those same bytes
            // re-sent on a backoff until the author reappeared. It now waits for a carrier instead.
            val sealed = CopyOnWriteArrayList<List<String>>()
            val author = Author("author")
            val recip = FakeLoopTransport("recip")
            author.start(backgroundScope)
            val ack =
                ackSyncOn(
                    recip,
                    "recip",
                    canSeal = { true },
                    sealTick = { a, ids ->
                        sealed.add(ids)
                        sealedWire("recip", a, ids)
                    },
                )

            ack.owe("m1", "author")
            ack.owe("m2", "author")

            assertTrue("no chain key is spent on a tick with nowhere to go", sealed.isEmpty())
            assertTrue("and nothing goes on the air", author.received().isEmpty())
            assertEquals("both wait for a ride", 2, ack.ridingFor("author"))
        }

    @Test
    fun aWaitingRoomTickIsHandedToTheNextFrameGoingThatWay() =
        runTest(UnconfinedTestDispatcher()) {
            val author = Author("author")
            val recip = FakeLoopTransport("recip")
            author.start(backgroundScope)
            val ack = ackSyncOn(recip, "recip", canSeal = { true }, sealTick = { a, ids -> sealedWire("recip", a, ids) })

            ack.owe("m1", "author")
            ack.owe("m2", "author")
            ack.owe("m3", "author")

            assertEquals("oldest first, and only what the carrier has room for", listOf("m1", "m2"), ack.takeRiding("author", 2))
            assertEquals(1, ack.ridingFor("author"))

            // The frame fell back to a form that cannot carry them: they go back to the hold, never onward.
            ack.giveBackRiding("author", listOf("m1", "m2"))
            assertEquals(3, ack.ridingFor("author"))
            assertTrue("a ride is never a send of its own", author.received().isEmpty())
        }

    @Test
    fun aRoomTickWaitingForARideIsNotReHeldByACustodyReServe() =
        runTest(UnconfinedTestDispatcher()) {
            // A room post re-serves routinely, and the deliver path re-owes it every time. Without the
            // re-owe no-op the hold would grow a duplicate on each pass.
            val recip = FakeLoopTransport("recip")
            val ack = ackSyncOn(recip, "recip", canSeal = { true }, sealTick = { a, ids -> sealedWire("recip", a, ids) })

            ack.owe("m1", "author")
            ack.owe("m1", "author")
            ack.owe("m1", "author")

            assertEquals("one entry however often custody re-serves it", 1, ack.ridingFor("author"))
        }

    @Test
    fun aLinkEndsTheWaitAsOneBatchedTick() =
        runTest(UnconfinedTestDispatcher()) {
            // No carrier ever came, but the author is back: the reliable path home ends the wait, and it
            // costs one chain key for the whole batch rather than the one-per-message the room used to
            // spend before it had anything to send them on.
            val sealed = CopyOnWriteArrayList<List<String>>()
            val author = Author("author")
            val recip = FakeLoopTransport("recip")
            author.start(backgroundScope)
            val ack =
                ackSyncOn(
                    recip,
                    "recip",
                    canSeal = { true },
                    sealTick = { a, ids ->
                        sealed.add(ids)
                        sealedWire("recip", a, ids)
                    },
                )

            ack.owe("m1", "author")
            ack.owe("m2", "author")
            recip.connect(author.transport)
            ack.onNeighborAdded(Peer("author"))

            assertEquals("one seal covering the batch", listOf(listOf("m1", "m2")), sealed)
            assertEquals("and nothing left waiting", 0, ack.ridingFor("author"))
        }

    @Test
    fun aLegacyAuthorsRoomTickKeepsTheCleartextBestEffortForm() =
        runTest(UnconfinedTestDispatcher()) {
            // A ride only exists inside a frame sealed to that author, so an author that cannot read one
            // keeps today's path — the same reason canSeal gates escalation.
            val author = Author("author")
            val recip = FakeLoopTransport("recip")
            author.start(backgroundScope)
            val ack = ackSyncOn(recip, "recip", canSeal = { false })

            ack.owe("m1", "author")
            assertEquals("nothing waits for a ride it could never take", 0, ack.ridingFor("author"))

            recip.connect(author.transport)
            ack.onNeighborAdded(Peer("author"))
            assertEquals(listOf("m1"), author.ackIds())
        }

    @Test
    fun theDebounceWakeFlushesWithoutAHeal() =
        runTest {
            // The flushScope wake is the primary trigger; retryPending/heal is only the backstop. Virtual
            // time drives the delay while the injected clock advances in lockstep.
            var clock = 0L
            val originated = CopyOnWriteArrayList<List<String>>()
            val recip = FakeLoopTransport("recip")
            val ack =
                ackSyncOn(
                    recip,
                    "recip",
                    clock = { clock },
                    canSeal = { true },
                    originateTick = { _, ids ->
                        originated.add(ids)
                        true
                    },
                    flushScope = { backgroundScope },
                )

            ack.owe("m1", "author", escalatable = true)
            assertTrue(originated.isEmpty())

            clock += AckSync.TICK_BATCH_DEBOUNCE_MS + 1
            testScheduler.advanceTimeBy(AckSync.TICK_BATCH_DEBOUNCE_MS + 1)
            testScheduler.runCurrent()

            assertEquals(listOf(listOf("m1")), originated)
        }

    @Test
    fun anEarlyNeighborJoinFlushesTheBatchOverTheLinkNotCustody() =
        runTest(UnconfinedTestDispatcher()) {
            // The author linked while its batch was debouncing: the whole batch goes over the live link
            // as one relay = false tick — reliable, and zero custody rows.
            val originated = CopyOnWriteArrayList<List<String>>()
            val author = Author("author")
            val recip = FakeLoopTransport("recip")
            author.start(backgroundScope)
            val ack =
                ackSyncOn(
                    recip,
                    "recip",
                    canSeal = { true },
                    originateTick = { _, ids ->
                        originated.add(ids)
                        true
                    },
                ) { authorId, ackIds -> sealedWire("recip", authorId, ackIds) }

            ack.owe("m1", "author", escalatable = true)
            ack.owe("m2", "author", escalatable = true)
            recip.connect(author.transport)
            ack.onNeighborAdded(Peer("author"))

            val chats = author.received().filter { it.envelope.type == FrameType.CHAT }
            assertEquals("one batched tick over the link", 1, chats.size)
            assertFalse("the live-link batch stays point-to-point", chats.single().wire.relay)
            assertArrayEquals(sealedWire("recip", "author", listOf("m1", "m2")).signed, chats.single().wire.signed)
            assertTrue("nothing escalated into custody", originated.isEmpty())
        }

    @Test
    fun aFailedEscalationFallsBackToPerIdCleartextTicks() =
        runTest(UnconfinedTestDispatcher()) {
            // The author was judged capable at owe() time but the seal fails at flush (unpinned meanwhile):
            // the ids re-materialize as today's per-id cleartext entries and land once a link exists.
            var clock = 0L
            val author = Author("author")
            val recip = FakeLoopTransport("recip")
            author.start(backgroundScope)
            val ack =
                ackSyncOn(
                    recip,
                    "recip",
                    clock = { clock },
                    canSeal = { true },
                    originateTick = { _, _ -> false },
                )

            ack.owe("m1", "author", escalatable = true)
            ack.owe("m2", "author", escalatable = true)
            clock += AckSync.TICK_BATCH_DEBOUNCE_MS + 1
            ack.retryPending() // flush fails → cleartext entries, still unreachable
            recip.connect(author.transport)
            ack.retryPending()

            assertEquals(listOf("m1", "m2"), author.ackIds().sorted())
        }

    @Test
    fun aFullBatchFlushesImmediately() =
        runTest(UnconfinedTestDispatcher()) {
            // Overflow: the 64th pending ack escalates the batch without waiting out the debounce; the
            // next ack opens a fresh batch.
            val originated = CopyOnWriteArrayList<List<String>>()
            val recip = FakeLoopTransport("recip")
            val ack =
                ackSyncOn(
                    recip,
                    "recip",
                    canSeal = { true },
                    originateTick = { _, ids ->
                        originated.add(ids)
                        true
                    },
                )

            repeat(AckSync.MAX_BATCH_ACKS) { ack.owe("m$it", "author", escalatable = true) }
            assertEquals("a full batch flushes without any clock advance", 1, originated.size)
            assertEquals(AckSync.MAX_BATCH_ACKS, originated.single().size)

            ack.owe("m-next", "author", escalatable = true)
            assertEquals("the overflow ack opens a fresh (not-yet-due) batch", 1, originated.size)
        }

    @Test
    fun batchedTicksNeverTakeTheCoordinationPlane() =
        runTest(UnconfinedTestDispatcher()) {
            // Structural pin: a pending batch is never fastSent (a batched tick outgrows even the compact
            // fragment budget — see CoordinationPlaneSizeBudgetTest); it waits for the debounce (custody)
            // or a live link. Only legacy cleartext/single-sealed owed entries ride fastSend.
            val recorder = FastSendRecorder(FakeLoopTransport("recip"))
            val ack = ackSyncOn(recorder, "recip", canSeal = { true }, originateTick = { _, _ -> true })

            ack.owe("m1", "author", escalatable = true)
            ack.owe("m2", "author", escalatable = true)
            ack.retryPending() // before the debounce: the batch is not due and must not fast-send

            assertTrue("a pending batch never rides the coordination plane", recorder.fastSent.isEmpty())
        }

    @Test
    fun aLiveCapableAuthorKeepsTheImmediateSealedSingleTick() =
        runTest(UnconfinedTestDispatcher()) {
            // Escalation is only for absent authors: a live-linked capable author still gets the
            // immediate single-ack sealed tick over the link, and nothing batches.
            val originated = CopyOnWriteArrayList<List<String>>()
            val author = Author("author")
            val recip = FakeLoopTransport("recip")
            recip.connect(author.transport)
            author.start(backgroundScope)
            val ack =
                ackSyncOn(
                    recip,
                    "recip",
                    canSeal = { true },
                    originateTick = { _, ids ->
                        originated.add(ids)
                        true
                    },
                ) { authorId, ackIds -> sealedWire("recip", authorId, ackIds) }

            ack.owe("m1", "author", escalatable = true)

            val chats = author.received().filter { it.envelope.type == FrameType.CHAT }
            assertEquals(1, chats.size)
            assertArrayEquals(sealedWire("recip", "author", listOf("m1")).signed, chats.single().wire.signed)
            assertTrue("no escalation for a live author", originated.isEmpty())
        }

    @Test
    fun batchedIdsAgeOutWithTheOwedTtl() =
        runTest(UnconfinedTestDispatcher()) {
            var clock = 0L
            val originated = CopyOnWriteArrayList<List<String>>()
            val recip = FakeLoopTransport("recip")
            val ack =
                ackSyncOn(
                    recip,
                    "recip",
                    clock = { clock },
                    canSeal = { true },
                    originateTick = { _, ids ->
                        originated.add(ids)
                        true
                    },
                )

            ack.owe("m1", "author", escalatable = true)
            clock += 25L * 60 * 60_000 // past the 24h owed TTL
            ack.retryPending() // sweep drops the aged batch before the due check can flush it

            assertTrue("an aged-out batch is swept, not escalated", originated.isEmpty())
        }

    @Test
    fun aSealedTickBacksOffInsteadOfResendingEveryHeartbeat() =
        runTest(UnconfinedTestDispatcher()) {
            // The RATCHET_DUPLICATE fix. The sealed form re-sends ONE frame id verbatim, and the router's
            // SeenSet only suppresses a repeat for 10 min while the heal heartbeat runs every 15 — so a flat
            // retry cleared the window every single time and landed on a consumed ratchet chain index. Due
            // times double from one heartbeat: 0, 15 m, 45 m, 1 h45, 3 h45, 7 h45, 15 h45, then the 8 h cap
            // at 23 h45 — 8 best-effort sends across the 24 h TTL where the flat loop made 97.
            var clock = 0L
            val recorder = FastSendRecorder(FakeLoopTransport("recip"))
            val ack =
                ackSyncOn(recorder, "recip", clock = { clock }) { authorId, ackIds ->
                    sealedWire("recip", authorId, ackIds)
                }

            ack.owe("m1", "author") // absent author: sealed, fast-sent once, held
            assertEquals(1, recorder.fastSent.size)

            clock += AckSync.RETRY_BASE_MS // +15m: the first step is due
            ack.retryPending()
            assertEquals(2, recorder.fastSent.size)

            clock += AckSync.RETRY_BASE_MS // +30m: the next step is 30m out, not due
            ack.retryPending()
            assertEquals("a heartbeat inside the backoff must not re-send", 2, recorder.fastSent.size)

            clock += AckSync.RETRY_BASE_MS // +45m: due again
            ack.retryPending()
            assertEquals(3, recorder.fastSent.size)

            // Drive the remaining heartbeats out to the 24 h TTL.
            while (clock < AckSync.OWED_TTL_MS) {
                clock += AckSync.RETRY_BASE_MS
                ack.retryPending()
            }
            assertEquals("the whole 24h horizon costs 8 re-sends, not 97", 8, recorder.fastSent.size)
            recorder.fastSent.forEach {
                assertArrayEquals(
                    "every re-send is the same sealed bytes",
                    sealedWire("recip", "author", listOf("m1")).signed,
                    it.signed,
                )
            }
        }

    @Test
    fun aBackedOffTickStillGoesHomeTheMomentALinkExists() =
        runTest(UnconfinedTestDispatcher()) {
            // The backoff throttles the best-effort coordination-plane re-send only: a live link is the
            // reliable path home and is exactly what the schedule is waiting for, so it never waits.
            var clock = 0L
            val author = Author("author")
            val recip = FakeLoopTransport("recip")
            author.start(backgroundScope)
            val ack =
                ackSyncOn(recip, "recip", clock = { clock }) { authorId, ackIds ->
                    sealedWire("recip", authorId, ackIds)
                }

            ack.owe("m1", "author") // absent: sealed, held, next step 15m out
            clock += 60_000 // one minute later — deep inside the backoff
            recip.connect(author.transport)
            ack.retryPending()

            val chats = author.received().filter { it.envelope.type == FrameType.CHAT }
            assertEquals("a live link overrides the backoff", 1, chats.size)
            ack.retryPending()
            assertEquals("and the entry is dropped, not re-sent", 1, chats.size)
        }

    @Test
    fun aCleartextTickIsNotBackedOff() =
        runTest(UnconfinedTestDispatcher()) {
            // Only the sealed form costs the author a decrypt. The cleartext form is rebuilt with a fresh
            // id per attempt, so a retry is a SeenSet dedup — it keeps today's every-heartbeat cadence.
            var clock = 0L
            val recorder = FastSendRecorder(FakeLoopTransport("recip"))
            val ack = ackSyncOn(recorder, "recip", clock = { clock }) // sealTick defaults to null

            ack.owe("m1", "author")
            repeat(3) {
                clock += AckSync.RETRY_BASE_MS
                ack.retryPending()
            }

            assertEquals("a legacy author's tick still retries every heartbeat", 4, recorder.fastSent.size)
        }

    private companion object {
        const val SIG_MARKER: Byte = 0x5A
    }
}
