package app.getknit.knit.transfer

import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.TransferPhase
import app.getknit.knit.data.message.TransferRecord
import app.getknit.knit.mesh.protocol.TransferPayload
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Collections
import kotlin.random.Random

/**
 * Two managers wired back-to-back through fake seams: Alice offers, Bob answers, and the bytes cross a real
 * loopback socket pair. Timers are shrunk through [TransferTimings] and everything runs in real time on
 * [Dispatchers.Default], so each case waits on the rows the managers write rather than on virtual time.
 */
class TransferManagerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = { System.currentTimeMillis() }
    private val sides = Collections.synchronizedList(mutableListOf<Side>())
    private val fast =
        TransferTimings(
            offerTtlMs = 400,
            readyWaitMs = 400,
            groupUpMs = 200,
            hostWindowMs = 1_500,
            joinStartDelayMs = 20,
            joinWindowMs = 600,
            joinAttemptMs = 50,
            joinRetryDelayMs = 30,
            readyGraceMs = 20,
            tcpConnectMs = 500,
            tcpConnectTries = 5,
            tcpConnectRetryMs = 50,
            soTimeoutMs = 2_000,
            verdictWaitMs = 2_000,
            progressEveryMs = 0,
        )

    private class Side(
        val name: String,
        val log: SideLog,
        val wifi: FakeDirectWifi,
        val files: FakeTransferFiles,
        val signals: FakeTransferSignals,
        val rows: MutableList<MessageEntity>,
        val manager: TransferManager,
        /** The manager's own log lines, kept apart from [log] so the ordering assertions on it stay exact. */
        val trace: MutableList<String>,
    ) {
        var nearby = true

        /** A snapshot: the managers append from their own threads while a case scans for a phase. */
        private fun snapshot(): List<MessageEntity> = synchronized(rows) { rows.toList() }

        fun record(id: String): TransferRecord? =
            snapshot().lastOrNull { it.id == TransferRecord.rowId(id) }?.let { TransferRecord.decode(it.body) }

        fun phases(id: String): List<TransferPhase> =
            snapshot()
                .filter {
                    it.id == TransferRecord.rowId(id)
                }.mapNotNull { TransferRecord.decode(it.body)?.phase }
    }

    private fun side(
        name: String,
        timings: TransferTimings = fast,
    ): Side {
        val log = SideLog(name)
        val trace = Collections.synchronizedList(mutableListOf<String>())
        val wifi = FakeDirectWifi(log)
        val files = FakeTransferFiles()
        val signals = FakeTransferSignals(log, scope, clock)
        val rows = Collections.synchronizedList(mutableListOf<MessageEntity>())
        val messages = mockk<MessageRepository>()
        coEvery { messages.save(any()) } answers { rows += firstArg<MessageEntity>() }
        lateinit var side: Side
        val manager =
            TransferManager(
                messages = messages,
                signals = signals,
                wifi = wifi,
                files = files,
                scope = scope,
                selfId = { name },
                peerNearby = { side.nearby },
                timings = timings,
                clock = clock,
                log = { trace += "${clock()} $it" },
            )
        side = Side(name, log, wifi, files, signals, rows, manager, trace)
        sides += side
        return side
    }

    private fun pair(
        timingsA: TransferTimings = fast,
        timingsB: TransferTimings = fast,
    ): Pair<Side, Side> {
        val a = side("alice", timingsA)
        val b = side("bob", timingsB)
        a.signals.peer = b.manager
        b.signals.peer = a.manager
        return a to b
    }

    private val payload = Random(7).nextBytes(TransferStream.CHUNK_BYTES * 3 + 321)

    private fun Side.withClip(failAfter: Int = -1) {
        files.sources["uri:clip"] = FakeTransferFiles.Entry("clip.mp4", payload, "video/mp4", failAfter)
    }

    private suspend fun await(
        what: String,
        timeoutMs: Long = 8_000,
        condition: () -> Boolean,
    ) {
        val met =
            withTimeoutOrNull(timeoutMs) {
                while (!condition()) delay(10)
                true
            }
        if (met == null) fail("$what (timed out after $timeoutMs ms)\n${dump()}")
    }

    /** Everything both sides did, for a case that timed out on CI where only the report survives. */
    private fun dump(): String =
        sides.toList().joinToString("\n") { s ->
            val rows = synchronized(s.rows) { s.rows.mapNotNull { TransferRecord.decode(it.body)?.phase } }
            val trace = synchronized(s.trace) { s.trace.joinToString("\n    ") }
            "${s.name}: rows=$rows events=${synchronized(s.log.events) { s.log.events.toList() }}\n    $trace"
        }

    private suspend fun awaitPhase(
        side: Side,
        id: String,
        phase: TransferPhase,
    ) = await("${side.name} reaches $phase, saw ${side.phases(id)}") { side.record(id)?.phase == phase }

    /** Bob has both admitted the offer (live) and written its row — the row lands just after the live state. */
    private suspend fun awaitIncoming(
        b: Side,
        id: String,
    ) = await("bob records the offer") {
        b.record(id)?.phase == TransferPhase.Offered &&
            b.manager.states.value
                .containsKey(id)
    }

    private suspend fun offer(a: Side): String = (a.manager.offer("bob", "uri:clip") as OfferOutcome.Started).id

    @After
    fun tearDown() = scope.cancel()

    @Test
    fun aFileCrossesFromAliceToBobAndBothRecordIt() =
        runBlocking {
            val (a, b) = pair()
            a.withClip()
            val id = offer(a)
            assertEquals(TransferPhase.Offered, a.record(id)?.phase)
            assertTrue(a.record(id)!!.outgoing)
            awaitIncoming(b, id)
            assertFalse(b.record(id)!!.outgoing)
            assertEquals("clip.mp4", b.record(id)!!.name)
            assertNull(b.manager.accept(id))
            awaitPhase(a, id, TransferPhase.Done)
            awaitPhase(b, id, TransferPhase.Done)
            val sink = b.files.sinks.single()
            assertArrayEquals(payload, sink.buffer.toByteArray())
            assertTrue(sink.committed)
            assertEquals(sink.uri, b.record(id)!!.savedUri)
            assertEquals(
                listOf(TransferPhase.Offered, TransferPhase.Connecting, TransferPhase.Transferring, TransferPhase.Done),
                a.phases(id),
            )
            assertEquals(
                listOf(TransferPhase.Offered, TransferPhase.Connecting, TransferPhase.Transferring, TransferPhase.Done),
                b.phases(id),
            )
            // READY leaves before the radio is taken: the offer, then READY, then host, then release. The radio is
            // handed back in finish(), after Done is written, so wait for that event rather than the phase.
            await("both sides hand the radio back") { a.wifi.released.isNotEmpty() && b.wifi.released.isNotEmpty() }
            assertEquals(listOf("signal:1", "signal:5", "host", "release"), a.log.events)
            assertEquals(listOf("signal:2", "join", "release"), b.log.events)
            await("terminal transfers leave the live map") {
                a.manager.states.value
                    .isEmpty() &&
                    b.manager.states.value
                        .isEmpty()
            }
            // Credentials never reach a row on either side.
            val ready = a.signals.sent.first { it.phase == TransferPayload.PHASE_READY }
            (a.rows + b.rows).forEach { row ->
                assertFalse(row.body.contains(ready.passphrase!!))
                assertFalse(row.body.contains(ready.key!!))
            }
        }

    @Test
    fun aDeclineEndsItOnBothSides() =
        runBlocking {
            val (a, b) = pair()
            a.withClip()
            val id = offer(a)
            awaitIncoming(b, id)
            b.manager.decline(id)
            awaitPhase(a, id, TransferPhase.Declined)
            assertEquals(TransferPayload.REASON_USER, a.record(id)!!.reason)
            assertEquals(TransferPhase.Declined, b.record(id)!!.phase)
            assertTrue(a.log.events.none { it == "host" })
            assertTrue(a.wifi.released.isEmpty())
        }

    @Test
    fun anUnansweredOfferExpiresAndALateAcceptIsCancelledWithoutHosting() =
        runBlocking {
            val (a, b) = pair()
            a.withClip()
            val id = offer(a)
            awaitIncoming(b, id)
            awaitPhase(a, id, TransferPhase.Expired)
            awaitPhase(b, id, TransferPhase.Expired)
            assertEquals(TransferRefusal.Gone, b.manager.accept(id))
            a.manager.onSignal("bob", TransferPayload(id = id, phase = TransferPayload.PHASE_ACCEPT), clock())
            await("alice answers the late accept with a cancel") {
                a.signals.sent.any { it.phase == TransferPayload.PHASE_CANCEL && it.id == id }
            }
            assertTrue(a.log.events.none { it == "host" })
        }

    @Test
    fun anAcceptFromAPeerNoLongerNearbyIsCancelled() =
        runBlocking {
            val (a, b) = pair()
            a.withClip()
            val id = offer(a)
            awaitIncoming(b, id)
            a.nearby = false
            assertNull(b.manager.accept(id))
            awaitPhase(b, id, TransferPhase.Failed)
            assertEquals(TransferPayload.REASON_TIMEOUT, b.record(id)!!.reason)
            assertTrue(a.log.events.none { it == "host" })
            assertEquals(TransferPhase.Offered, a.record(id)!!.phase)
        }

    @Test
    fun anAcceptHandsTheCollectorBackBeforeTheReadyGraceAndACancelDuringItNeverHosts() =
        runBlocking {
            val grace = 1_000L
            val (a, b) = pair(timingsA = fast.copy(readyGraceMs = grace))
            a.withClip()
            val id = offer(a)
            awaitIncoming(b, id)
            // The ACCEPT lands on the inbound pipeline's own coroutine: the grace must not be served there.
            val acceptedAt = clock()
            a.manager.onSignal("bob", TransferPayload(id = id, phase = TransferPayload.PHASE_ACCEPT), clock())
            val heldMs = clock() - acceptedAt
            assertTrue("the accept held its caller for $heldMs ms", heldMs < grace)
            assertEquals(TransferPhase.Connecting, a.record(id)!!.phase)
            assertTrue(a.signals.sent.any { it.phase == TransferPayload.PHASE_READY && it.id == id })
            assertTrue(a.log.events.none { it == "host" })
            // Cancelled while the grace is still running: the host job ends without ever taking the radio.
            a.manager.cancel(id)
            awaitPhase(a, id, TransferPhase.Cancelled)
            await("alice releases the radio") { a.wifi.released.isNotEmpty() }
            delay(grace)
            assertTrue(a.log.events.none { it == "host" })
        }

    @Test
    fun aLostReadyFailsTheReceiverAndCancelsTheSender() =
        runBlocking {
            val (a, b) = pair()
            a.withClip()
            a.signals.drop = { it.phase == TransferPayload.PHASE_READY }
            val id = offer(a)
            awaitIncoming(b, id)
            assertNull(b.manager.accept(id))
            awaitPhase(b, id, TransferPhase.Failed)
            assertEquals(TransferPayload.REASON_TIMEOUT, b.record(id)!!.reason)
            awaitPhase(a, id, TransferPhase.Failed)
            assertEquals(TransferPayload.REASON_TIMEOUT, a.record(id)!!.reason)
            await("alice releases the radio") { a.wifi.released.isNotEmpty() }
            assertTrue(b.log.events.none { it == "join" })
        }

    @Test
    fun aJoinThatNeverFormsFailsTheReceiverAndCancelsTheSender() =
        runBlocking {
            val (a, b) = pair()
            a.withClip()
            b.wifi.joinFailures = Int.MAX_VALUE
            val id = offer(a)
            awaitIncoming(b, id)
            assertNull(b.manager.accept(id))
            awaitPhase(b, id, TransferPhase.Failed)
            assertEquals(TransferPayload.REASON_JOIN_FAILED, b.record(id)!!.reason)
            assertTrue("retried the join", b.log.events.count { it == "join" } > 1)
            awaitPhase(a, id, TransferPhase.Failed)
            assertEquals(TransferPayload.REASON_JOIN_FAILED, a.record(id)!!.reason)
            await("both release") { a.wifi.released.isNotEmpty() && b.wifi.released.isNotEmpty() }
        }

    @Test
    fun aJoinThatSucceedsOnRetryStillDelivers() =
        runBlocking {
            val (a, b) = pair()
            a.withClip()
            b.wifi.joinFailures = 2
            val id = offer(a)
            awaitIncoming(b, id)
            assertNull(b.manager.accept(id))
            awaitPhase(b, id, TransferPhase.Done)
            assertEquals(3, b.log.events.count { it == "join" })
        }

    @Test
    fun aHostWindowWithNoClientFailsTheSenderAndCancelsTheReceiver() =
        runBlocking {
            val (a, b) = pair(timingsB = fast.copy(joinWindowMs = 10_000, joinAttemptMs = 500, joinRetryDelayMs = 100))
            a.withClip()
            b.wifi.joinFailures = Int.MAX_VALUE
            val id = offer(a)
            awaitIncoming(b, id)
            assertNull(b.manager.accept(id))
            awaitPhase(a, id, TransferPhase.Failed)
            assertEquals(TransferPayload.REASON_TIMEOUT, a.record(id)!!.reason)
            awaitPhase(b, id, TransferPhase.Failed)
            await("both release") { a.wifi.released.isNotEmpty() && b.wifi.released.isNotEmpty() }
        }

    @Test
    fun aLinkThatDiesMidStreamFailsBothAndDiscardsTheSink() =
        runBlocking {
            val (a, b) = pair()
            a.withClip(failAfter = TransferStream.CHUNK_BYTES + 5)
            val id = offer(a)
            awaitIncoming(b, id)
            assertNull(b.manager.accept(id))
            awaitPhase(a, id, TransferPhase.Failed)
            awaitPhase(b, id, TransferPhase.Failed)
            assertEquals(TransferPayload.REASON_CONNECTION, a.record(id)!!.reason)
            assertEquals(TransferPayload.REASON_CONNECTION, b.record(id)!!.reason)
            val sink = b.files.sinks.single()
            // The row flips on the peer's CANCEL a beat before the blocked read unwinds and throws the file away.
            await("the half-written file is thrown away") { sink.discarded }
            assertFalse(sink.committed)
            await("both release") { a.wifi.released.isNotEmpty() && b.wifi.released.isNotEmpty() }
        }

    @Test
    fun theSenderCancellingMidWayEndsItOnBothSides() =
        runBlocking {
            val (a, b) = pair()
            a.withClip()
            b.wifi.joinDelayMs = 300
            val id = offer(a)
            awaitIncoming(b, id)
            assertNull(b.manager.accept(id))
            await("alice is hosting") { a.log.events.contains("host") }
            a.manager.cancel(id)
            awaitPhase(a, id, TransferPhase.Cancelled)
            awaitPhase(b, id, TransferPhase.Cancelled)
            await("both release") { a.wifi.released.isNotEmpty() && b.wifi.released.isNotEmpty() }
        }

    @Test
    fun onlyTheClientWhoseProofNamesTheTransferGetsTheBytes() =
        runBlocking {
            val a = side("alice")
            a.withClip()
            val id = offer(a)
            a.manager.onSignal("bob", TransferPayload(id = id, phase = TransferPayload.PHASE_ACCEPT), clock())
            await("alice sends READY") { a.signals.sent.any { it.phase == TransferPayload.PHASE_READY } }
            val ready = a.signals.sent.first { it.phase == TransferPayload.PHASE_READY }
            await("alice is hosting") { a.log.events.contains("host") }
            delay(50)
            val key =
                java.util.Base64
                    .getDecoder()
                    .decode(ready.key)
            val wrongKey = ByteArray(TransferStream.KEY_BYTES) { 9 }
            val port = ready.port!!
            // A stray connection with a wrong proof is dropped without a byte.
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 500)
                s.getOutputStream().write(TransferStream.clientProof(wrongKey, id))
                s.getOutputStream().flush()
                assertEquals(-1, s.getInputStream().read())
            }
            // The right proof gets the file.
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 500)
                s.getOutputStream().write(TransferStream.clientProof(key, id))
                s.getOutputStream().flush()
                val got = ByteArrayOutputStream()
                assertTrue(TransferStream.receive(s.getInputStream(), got, id, payload.size.toLong(), key) {})
                assertArrayEquals(payload, got.toByteArray())
                TransferStream.writeVerdict(s.getOutputStream(), true)
            }
            awaitPhase(a, id, TransferPhase.Done)
        }

    @Test
    fun aReceiverThatArrivesOverIpv6StillGetsTheFile() =
        runBlocking {
            assumeTrue("this JVM cannot listen on ::1", FakeDirectWifi.ipv6LoopbackUsable())
            val (a, b) = pair()
            a.withClip()
            // A receiver on Android 13+ may join with IPv6 link-local provisioning and hold no IPv4 at all;
            // the host binds every address its group interface carries, so it turns up on the other listener.
            b.wifi.joinOverIpv6 = true
            val id = offer(a)
            awaitIncoming(b, id)
            assertNull(b.manager.accept(id))
            awaitPhase(a, id, TransferPhase.Done)
            awaitPhase(b, id, TransferPhase.Done)
            assertArrayEquals(
                payload,
                b.files.sinks
                    .single()
                    .buffer
                    .toByteArray(),
            )
        }

    @Test
    fun aGroupRefusedForBeingOffScreenIsRecordedAsSuch() =
        runBlocking {
            val (a, b) = pair()
            a.withClip()
            a.wifi.hostFails = true
            a.wifi.hostRefusal = TransferRefusal.Background
            val id = offer(a)
            awaitIncoming(b, id)
            assertNull(b.manager.accept(id))
            awaitPhase(a, id, TransferPhase.Failed)
            // Not "this phone can't do Wi-Fi Direct": the user can fix this one by opening Knit.
            assertEquals(TransferPayload.REASON_FOREGROUND, a.record(id)!!.reason)
            await("alice hands the radio back") { a.wifi.released.isNotEmpty() }
        }

    @Test
    fun aSecondOfferIsRefusedAsBusyAndRefusalsNeverWriteARow() =
        runBlocking {
            val (a, b) = pair()
            a.withClip()
            val id = offer(a)
            assertEquals(OfferOutcome.Refused(TransferRefusal.Busy), a.manager.offer("bob", "uri:clip"))
            assertEquals(OfferOutcome.Refused(TransferRefusal.Unreadable), a.manager.offer("bob", "uri:missing"))
            a.wifi.refusal = TransferRefusal.WifiOff
            assertEquals(OfferOutcome.Refused(TransferRefusal.WifiOff), a.manager.offer("bob", "uri:clip"))
            a.wifi.refusal = null
            assertEquals(1, a.rows.size)
            awaitIncoming(b, id)
            // A busy receiver declines a second offer for the peer.
            b.manager.onSignal("carol", TransferPayload(id = "other", phase = TransferPayload.PHASE_OFFER, name = "x", size = 5), clock())
            assertTrue(b.signals.sent.any { it.phase == TransferPayload.PHASE_DECLINE && it.reason == TransferPayload.REASON_BUSY })
        }

    @Test
    fun malformedOrStaleOffersAreDroppedWithoutTrace() =
        runBlocking {
            val b = side("bob")
            val now = clock()
            assertFalse(b.manager.onSignal("alice", TransferPayload(id = "t1", phase = TransferPayload.PHASE_OFFER, size = 5), now))
            assertFalse(
                b.manager.onSignal("alice", TransferPayload(id = "t2", phase = TransferPayload.PHASE_OFFER, name = "x", size = 0), now),
            )
            assertFalse(
                b.manager.onSignal(
                    "alice",
                    TransferPayload(
                        id = "t3",
                        phase = TransferPayload.PHASE_OFFER,
                        name = "x",
                        size =
                            TransferManager.MAX_TRANSFER_BYTES + 1,
                    ),
                    now,
                ),
            )
            assertFalse(
                b.manager.onSignal(
                    "alice",
                    TransferPayload(id = "t4", phase = TransferPayload.PHASE_OFFER, name = "x", size = 5),
                    now - TransferManager.STALE_OFFER_MS - 1,
                ),
            )
            assertFalse(
                b.manager.onSignal(
                    "alice",
                    TransferPayload(id = "t5", phase = TransferPayload.PHASE_READY, ssid = "DIRECT-ab", passphrase = "12345678"),
                    now,
                ),
            )
            assertTrue(b.rows.isEmpty())
            assertTrue(
                b.manager.states.value
                    .isEmpty(),
            )
            assertTrue(b.signals.sent.isEmpty())
        }

    @Test
    fun anOfferThatCannotBeSealedIsRefusedAndLeavesNothingBehind() =
        runBlocking {
            val a = side("alice")
            a.withClip()
            a.signals.fail = true
            assertEquals(OfferOutcome.Refused(TransferRefusal.NoSession), a.manager.offer("bob", "uri:clip"))
            assertTrue(a.rows.isEmpty())
            assertTrue(
                a.manager.states.value
                    .isEmpty(),
            )
            a.nearby = false
            assertEquals(OfferOutcome.Refused(TransferRefusal.NotNearby), a.manager.offer("bob", "uri:clip"))
        }

    @Test
    fun aReceiverWithoutRoomRefusesBeforeAnswering() =
        runBlocking {
            val (a, b) = pair()
            a.withClip()
            b.files.free = 10
            val id = offer(a)
            awaitIncoming(b, id)
            assertEquals(TransferRefusal.NoSpace, b.manager.accept(id))
            assertTrue(b.signals.sent.none { it.phase == TransferPayload.PHASE_ACCEPT })
            assertEquals(
                TransferPhase.Offered,
                b.manager.states
                    .first()[id]
                    ?.phase,
            )
        }
}
