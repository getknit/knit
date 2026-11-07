package app.getknit.knit.transfer

import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.TransferPhase
import app.getknit.knit.data.message.TransferRecord
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.TransferPayload
import app.getknit.knit.translate.ModelArchive
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
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
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import kotlin.random.Random

/**
 * Two managers wired back-to-back through fake seams: Alice offers, Bob answers, and the bytes cross a real
 * loopback socket pair. Timers are shrunk through [TransferTimings] and everything runs in real time on
 * [Dispatchers.Default], so each case waits on the rows the managers write rather than on virtual time.
 */
@Suppress("LargeClass") // two managers back to back through every phase and failure path; the models cases share the rig
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
        models: FakeTransferModels? = null,
    ): Side {
        val log = SideLog(name)
        val trace = Collections.synchronizedList(mutableListOf<String>())
        val wifi = FakeDirectWifi(log)
        val files = FakeTransferFiles()
        val signals = FakeTransferSignals(log, scope, clock)
        val rows = Collections.synchronizedList(mutableListOf<MessageEntity>())
        val messages = mockk<MessageRepository>()
        coEvery { messages.save(any()) } answers { rows += firstArg<MessageEntity>() }
        every { messages.observeMessage(any()) } answers {
            val rowId = firstArg<String>()
            flowOf(synchronized(rows) { rows.lastOrNull { it.id == rowId } })
        }
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
                newPort = FakeDirectWifi::freeLoopbackPort,
                log = { trace += "${clock()} $it" },
                models = models,
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
    ) = await({ what }, timeoutMs, condition)

    /** As above, with [what] worded at the timeout, so the message says what was seen then, not at the start. */
    private suspend fun await(
        what: () -> String,
        timeoutMs: Long = 8_000,
        condition: () -> Boolean,
    ) {
        val met =
            withTimeoutOrNull(timeoutMs) {
                while (!condition()) delay(10)
                true
            }
        if (met == null) fail("${what()} (timed out after $timeoutMs ms)\n${dump()}")
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
    ) = await({ "${side.name} reaches $phase, saw ${side.phases(id)}" }) { side.record(id)?.phase == phase }

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
    fun tearDown() {
        scope.cancel()
        if (modelRootLazy.isInitialized()) modelRoot.deleteRecursively()
    }

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
    fun aFileThatArrivesButCannotBeSavedFailsOnlyTheReceiver() =
        runBlocking {
            // Every byte crosses and checks out, then Bob's save fails: his card says so, not that the link dropped, and
            // Alice, holding an OK verdict, keeps Done whenever his CANCEL lands. A long offer TTL, as a starved runner
            // can take longer than `fast`'s 400 ms to reach the accept.
            val (a, b) = pair(modelTimings, modelTimings)
            a.withClip()
            b.files.commitFails = true
            val id = offer(a)
            awaitIncoming(b, id)
            assertNull(b.manager.accept(id))
            awaitPhase(a, id, TransferPhase.Done)
            awaitPhase(b, id, TransferPhase.Failed)
            assertEquals(TransferPayload.REASON_NOT_SAVED, b.record(id)!!.reason)
            assertNull(b.record(id)!!.savedUri)
            assertTrue(
                "bob threw the unsaved file away",
                b.files.sinks
                    .single()
                    .discarded,
            )
            await("bob tells alice why") {
                synchronized(b.signals.sent) {
                    b.signals.sent.any { it.phase == TransferPayload.PHASE_CANCEL && it.reason == TransferPayload.REASON_NOT_SAVED }
                }
            }
            await("alice is done with it") { id !in a.manager.states.value }
            assertFalse("the sender heard the verdict and keeps Done", TransferPhase.Failed in a.phases(id))
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
            // Cancelled while the grace is still running: the host job ends without ever taking the radio. Whether it
            // had started by then is the dispatcher's choice (on the one-vCPU runner the cancel lands before its first
            // resume, and a job cancelled before it starts never runs its body, so finish never releases), so the
            // test waits for every job the managers launched to end and reads the one fact both orders share: nothing
            // hosted, and nothing is left running that could.
            a.manager.cancel(id)
            awaitPhase(a, id, TransferPhase.Cancelled)
            await("every job the managers launched ends") {
                scope.coroutineContext.job.children
                    .none()
            }
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

    /**
     * A READY naming a group the platform's builder would refuse (`DIRECT-` with no two alphanumerics after it) is not
     * a READY: before the fix it passed `GroupCredentials.isValid`, the receiver's join threw `IllegalArgumentException`
     * past every `DirectWifiException` catch, and the transfer was left Connecting, never Failed, with the sender told
     * nothing. Now the receiver never joins and fails it as it fails a READY that never came.
     */
    @Test
    fun aReadyNamingAGroupThePlatformRefusesIsIgnoredAndTheTransferFails() =
        runBlocking {
            val (a, b) = pair()
            a.withClip()
            a.signals.rewrite = { if (it.phase == TransferPayload.PHASE_READY) it.copy(ssid = "DIRECT-") else it }
            val id = offer(a)
            awaitIncoming(b, id)
            assertNull(b.manager.accept(id))
            awaitPhase(b, id, TransferPhase.Failed)
            assertEquals(TransferPayload.REASON_TIMEOUT, b.record(id)!!.reason)
            awaitPhase(a, id, TransferPhase.Failed)
            assertTrue(b.log.events.none { it == "join" })
        }

    /**
     * The 2026-10-05 shape: the receiver could not open the first READY (a ratchet collision, #135), and the
     * pair's session healed while the receiver was still waiting. A READY sent again under the healed session
     * gets the file across. Without the re-send this is [aLostReadyFailsTheReceiverAndCancelsTheSender].
     */
    @Test
    fun aFirstReadyThatNeverLandsIsSentAgainAndTheFileStillCrosses() =
        runBlocking {
            val (a, b) = pair(timingsA = fast.copy(readyResendMs = 100))
            a.withClip()
            var readies = 0
            a.signals.drop = { it.phase == TransferPayload.PHASE_READY && ++readies == 1 }
            val id = offer(a)
            awaitIncoming(b, id)
            assertNull(b.manager.accept(id))
            awaitPhase(b, id, TransferPhase.Done)
            awaitPhase(a, id, TransferPhase.Done)
            assertArrayEquals(
                payload,
                b.files.sinks
                    .single()
                    .buffer
                    .toByteArray(),
            )
            val sent = a.signals.sent.filter { it.phase == TransferPayload.PHASE_READY }
            assertTrue("READY was sent again, saw ${sent.size}", sent.size >= 2)
            // Every copy names the same group, port and key: a receiver that caught any one of them joins.
            assertEquals(1, sent.map { listOf(it.ssid, it.passphrase, it.port, it.key) }.distinct().size)
            assertEquals(1, b.log.events.count { it == "join" })
        }

    /** The re-send stops once the receiver has connected: nothing more goes out while the bytes cross, or after. */
    @Test
    fun readyIsNoLongerSentOnceTheReceiverConnects() =
        runBlocking {
            val (a, b) = pair(timingsA = fast.copy(readyResendMs = 100, readyWaitMs = 5_000))
            a.withClip()
            val id = offer(a)
            awaitIncoming(b, id)
            assertNull(b.manager.accept(id))
            awaitPhase(a, id, TransferPhase.Done)
            val readies = a.signals.sent.count { it.phase == TransferPayload.PHASE_READY }
            delay(500)
            assertEquals(readies, a.signals.sent.count { it.phase == TransferPayload.PHASE_READY })
        }

    /**
     * Retry offers the same file again as a new transfer, without the picker. The old card stops offering it,
     * and a Retry on that old card, a finished file or the receiver's own row has nothing to start.
     */
    @Test
    fun aFailedSendIsRetriedAsANewOfferOfTheSameFile() =
        runBlocking {
            val (a, b) = pair()
            a.withClip()
            a.signals.drop = { it.phase == TransferPayload.PHASE_READY }
            val first = offer(a)
            awaitIncoming(b, first)
            assertNull(b.manager.accept(first))
            awaitPhase(a, first, TransferPhase.Failed)
            awaitPhase(b, first, TransferPhase.Failed)
            assertEquals("uri:clip", a.record(first)!!.sourceUri)
            assertTrue(a.record(first)!!.retryable(live = false))
            assertNull("the receiver's row never names a source", b.record(first)!!.sourceUri)
            assertEquals(OfferOutcome.Refused(TransferRefusal.Gone), b.manager.retry(first))

            a.signals.drop = { false }
            // The row lands just before the transfer leaves the live map, and Retry asks the map.
            await("alice drops it from the live map") { first !in a.manager.states.value }
            val second = (a.manager.retry(first) as OfferOutcome.Started).id
            assertTrue(second != first)
            assertTrue(a.record(first)!!.retried)
            assertEquals(TransferPhase.Failed, a.record(first)!!.phase)
            assertFalse(a.record(first)!!.retryable(live = false))
            assertEquals(OfferOutcome.Refused(TransferRefusal.Gone), a.manager.retry(first))
            assertEquals("uri:clip", a.record(second)!!.sourceUri)
            // The old row keeps its place in the thread: rewritten, never moved.
            val firstRows = a.rows.filter { it.id == TransferRecord.rowId(first) }
            assertEquals(1, firstRows.map { it.sentAt }.distinct().size)

            awaitIncoming(b, second)
            assertNull(b.manager.accept(second))
            awaitPhase(a, second, TransferPhase.Done)
            awaitPhase(b, second, TransferPhase.Done)
            assertArrayEquals(
                payload,
                b.files.sinks
                    .single()
                    .buffer
                    .toByteArray(),
            )
            assertEquals(OfferOutcome.Refused(TransferRefusal.Gone), a.manager.retry(second))
        }

    /** A source that is gone refuses exactly as a fresh pick would, and leaves the old card offering Retry. */
    @Test
    fun aRetryWhoseFileIsGoneIsRefusedAndKeepsItsCard() =
        runBlocking {
            val (a, b) = pair()
            a.withClip()
            val id = offer(a)
            awaitIncoming(b, id)
            a.manager.cancel(id)
            awaitPhase(a, id, TransferPhase.Cancelled)
            a.files.sources.clear()
            assertEquals(OfferOutcome.Refused(TransferRefusal.Unreadable), a.manager.retry(id))
            assertFalse(a.record(id)!!.retried)
            assertTrue(a.record(id)!!.retryable(live = false))
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
            b.manager.onSignal(
                "carol",
                TransferPayload(id = FrameId.new(), phase = TransferPayload.PHASE_OFFER, name = "x", size = 5),
                clock(),
            )
            assertTrue(b.signals.sent.any { it.phase == TransferPayload.PHASE_DECLINE && it.reason == TransferPayload.REASON_BUSY })
        }

    @Test
    fun aPhoneThatMayNotHostRefusesToSendAndStillReceives() =
        runBlocking {
            val (a, b) = pair()
            a.withClip()
            b.withClip()
            b.wifi.cannotHost = true
            assertFalse(b.manager.mayOffer())
            assertEquals(OfferOutcome.Refused(TransferRefusal.CannotHost), b.manager.offer("alice", "uri:clip"))
            assertTrue(b.rows.isEmpty())
            assertFalse("host" in b.log.events)
            // The receiver only joins: the same phone takes Alice's file.
            val id = offer(a)
            awaitIncoming(b, id)
            assertNull(b.manager.accept(id))
            awaitPhase(b, id, TransferPhase.Done)
            assertTrue(a.manager.mayOffer())
        }

    @Test
    fun malformedOrStaleOffersAreDroppedWithoutTrace() =
        runBlocking {
            val b = side("bob")
            val now = clock()
            assertNull(b.manager.onSignal("alice", TransferPayload(id = FrameId.new(), phase = TransferPayload.PHASE_OFFER, size = 5), now))
            assertNull(
                b.manager.onSignal(
                    "alice",
                    TransferPayload(id = FrameId.new(), phase = TransferPayload.PHASE_OFFER, name = "x", size = 0),
                    now,
                ),
            )
            assertNull(
                b.manager.onSignal(
                    "alice",
                    TransferPayload(
                        id = FrameId.new(),
                        phase = TransferPayload.PHASE_OFFER,
                        name = "x",
                        size =
                            TransferManager.MAX_TRANSFER_BYTES + 1,
                    ),
                    now,
                ),
            )
            assertNull(
                b.manager.onSignal(
                    "alice",
                    TransferPayload(id = FrameId.new(), phase = TransferPayload.PHASE_OFFER, name = "x", size = 5),
                    now - TransferManager.STALE_OFFER_MS - 1,
                ),
            )
            assertNull(
                b.manager.onSignal(
                    "alice",
                    TransferPayload(id = FrameId.new(), phase = TransferPayload.PHASE_READY, ssid = "DIRECT-ab", passphrase = "12345678"),
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

    // ---- translation models (ADR 2026-11.ejn8) ----

    // Made only by the models cases, and removed after each.
    private val modelRootLazy = lazy { Files.createTempDirectory("models").toFile() }
    private val modelRoot by modelRootLazy
    private val table = mapOf(P1 to FakeTransferModels.direction(1), P2 to FakeTransferModels.direction(2))

    /**
     * The models cases' timings: [fast], with an offer that outlives a throttled runner's gap between the offer and the
     * tap. A 400 ms one expired before Bob accepted under a CPU quota, and the sender answered his ACCEPT with CANCEL.
     */
    private val modelTimings = fast.copy(offerTtlMs = 5_000)

    private fun modelPair(timings: TransferTimings = modelTimings): Triple<Side, Side, Pair<FakeTransferModels, FakeTransferModels>> {
        val am = FakeTransferModels(File(modelRoot, "alice"), table).also { it.held += listOf(P1, P2) }
        val bm = FakeTransferModels(File(modelRoot, "bob"), table)
        val a = side("alice", timings, models = am)
        val b = side("bob", timings, models = bm)
        a.signals.peer = b.manager
        b.signals.peer = a.manager
        return Triple(a, b, am to bm)
    }

    /** What the archive of [pins] costs on the link: both ends work it out from the pins. */
    private fun bytesOf(vararg pins: Long) =
        ModelArchive.size(
            pins.map {
                FakeTransferModels.dirOf(it) to
                    table.getValue(it).map { f -> f.first }
            },
        )

    private suspend fun offerModels(
        a: Side,
        pins: List<Long>,
    ): String = (a.manager.offerModels("bob", pins) as OfferOutcome.Started).id

    @Test
    fun aBundleOfModelsCrossesAndIsInstalledOnlyOnceItsPinsCheck() =
        runBlocking {
            val (a, b, shelves) = modelPair()
            val id = offerModels(a, listOf(P1, P2))
            // The OFFER is a whole file offer, the archive, with the pins beside it; a build that can install them reads
            // names and sizes off its own table instead.
            val sentOffer = a.signals.sent.single { it.phase == TransferPayload.PHASE_OFFER }
            assertEquals(listOf(P1, P2), sentOffer.md)
            assertEquals(ModelArchive.FILE_NAME, sentOffer.name)
            assertEquals(bytesOf(P1, P2), sentOffer.size)
            assertEquals(ModelArchive.MIME, sentOffer.mime)
            awaitIncoming(b, id)
            // The receiver names it in its own words, never the sender's file name.
            assertEquals("models:2", b.record(id)!!.name)
            assertEquals(listOf(P1, P2), b.record(id)!!.models)
            assertEquals(bytesOf(P1, P2), b.record(id)!!.size)
            assertNull(b.manager.accept(id))
            awaitPhase(a, id, TransferPhase.Done)
            awaitPhase(b, id, TransferPhase.Done)
            assertEquals(listOf(P1, P2), shelves.second.installed)
            assertEquals(
                listOf(P1, P2),
                b.signals.sent
                    .single { it.phase == TransferPayload.PHASE_ACCEPT }
                    .md,
            )
            // Installed, not saved: no Downloads entry, nothing for the card to open, and the stage is gone.
            assertTrue(b.files.sinks.isEmpty())
            assertNull(b.record(id)!!.savedUri)
            await("the stage is removed") { shelves.second.stages.none { it.exists() } }
        }

    @Test
    fun aReceiverTakesOnlyWhatItLacksAndTheSenderSendsOnlyThat() =
        runBlocking {
            val (a, b, shelves) = modelPair()
            shelves.second.held += P1
            val id = offerModels(a, listOf(P1, P2))
            awaitIncoming(b, id)
            assertNull(b.manager.accept(id))
            assertEquals(
                listOf(P2),
                b.signals.sent
                    .single { it.phase == TransferPayload.PHASE_ACCEPT }
                    .md,
            )
            awaitPhase(a, id, TransferPhase.Done)
            awaitPhase(b, id, TransferPhase.Done)
            assertEquals(listOf(P2), shelves.second.installed)
            // Both records narrow to what went, sized by the pins.
            assertEquals(listOf(P2), a.record(id)!!.models)
            assertEquals(bytesOf(P2), a.record(id)!!.size)
            assertEquals(listOf(P2), b.record(id)!!.models)
        }

    @Test
    fun aModelsOfferGetsNoAutomaticAnswerEvenWithNothingNew() =
        runBlocking {
            // A phone that holds P1 and does not know P3: the card says so, and nothing goes back until someone taps.
            val b = side("bob", models = FakeTransferModels(File(modelRoot, "bob"), table).also { it.held += P1 })
            val id = FrameId.new()
            val offer = TransferPayload(id = id, phase = TransferPayload.PHASE_OFFER, md = listOf(P1, P3))
            assertNull("nothing new is worth a notification", b.manager.onSignal("alice", offer, clock()))
            assertEquals(listOf(P1, P3), b.record(id)!!.models)
            assertEquals("nothing would cross, so the card shows no size", 0L, b.record(id)!!.size)
            assertTrue(b.signals.sent.isEmpty())
            assertEquals(TransferRefusal.Gone, b.manager.accept(id))
            assertTrue(b.signals.sent.isEmpty())
            // An offer with something new is.
            val fresh = TransferPayload(id = FrameId.new(), phase = TransferPayload.PHASE_OFFER, md = listOf(P2))
            val other = side("bob2", models = FakeTransferModels(File(modelRoot, "b2"), table))
            assertEquals(TransferNotice("models:1", bytesOf(P2)), other.manager.onSignal("alice", fresh, clock()))
        }

    @Test
    fun aModelsOfferFromFarAwayIsDropped() =
        runBlocking {
            val b = side("bob", models = FakeTransferModels(File(modelRoot, "bob"), table))
            b.nearby = false
            val offer =
                TransferPayload(
                    id = FrameId.new(),
                    phase = TransferPayload.PHASE_OFFER,
                    name = ModelArchive.FILE_NAME,
                    size = bytesOf(P1),
                    md = listOf(P1),
                )
            assertNull(b.manager.onSignal("alice", offer, clock()))
            assertTrue(b.rows.isEmpty())
            assertTrue(b.signals.sent.isEmpty())
        }

    @Test
    fun aPhoneThatCannotInstallModelsTakesTheArchiveAsAFile() =
        runBlocking {
            // Bob's build has no models shelf, as an older build or one without translation: the offer is the file it
            // also is, his plain ACCEPT names no pins, and Alice sends him the whole archive.
            val am = FakeTransferModels(File(modelRoot, "alice"), table).also { it.held += listOf(P1, P2) }
            val a = side("alice", modelTimings, models = am)
            val b = side("bob", modelTimings)
            a.signals.peer = b.manager
            b.signals.peer = a.manager
            val id = offerModels(a, listOf(P1, P2))
            awaitIncoming(b, id)
            assertEquals(ModelArchive.FILE_NAME, b.record(id)!!.name)
            assertNull(b.record(id)!!.models)
            assertNull(b.manager.accept(id))
            assertNull(
                b.signals.sent
                    .single { it.phase == TransferPayload.PHASE_ACCEPT }
                    .md,
            )
            awaitPhase(a, id, TransferPhase.Done)
            awaitPhase(b, id, TransferPhase.Done)
            assertArrayEquals(
                am.archive(listOf(P1, P2)),
                b.files.sinks
                    .single()
                    .buffer
                    .toByteArray(),
            )
            assertEquals(listOf(P1, P2), a.record(id)!!.models)
        }

    @Test
    fun malformedPinListsAndPeerWrittenIdsAreDropped() =
        runBlocking {
            val b = side("bob", models = FakeTransferModels(File(modelRoot, "bob"), table))
            val now = clock()
            for (md in listOf(
                emptyList(),
                listOf(P1, P1),
                listOf(-1L),
                listOf(0x1_0000_0000L),
                List(OfferReading.MAX_MODELS + 1) { it.toLong() },
            )) {
                assertNull(
                    "$md",
                    b.manager.onSignal("alice", TransferPayload(id = FrameId.new(), phase = TransferPayload.PHASE_OFFER, md = md), now),
                )
            }
            // An id is a canonical FrameId or nothing, whatever the phase: it keys the row and must never name a path.
            val phases =
                listOf(
                    TransferPayload.PHASE_OFFER,
                    TransferPayload.PHASE_ACCEPT,
                    TransferPayload.PHASE_DECLINE,
                    TransferPayload.PHASE_CANCEL,
                    TransferPayload.PHASE_READY,
                )
            for (id in listOf("../../x", "a/b", "x".repeat(22), FrameId.new() + "=")) {
                assertNull(
                    id,
                    b.manager.onSignal("alice", TransferPayload(id = id, phase = TransferPayload.PHASE_OFFER, md = listOf(P1)), now),
                )
                for (phase in phases) {
                    val signal =
                        TransferPayload(id = id, phase = phase, name = "x", size = 5, ssid = "DIRECT-ab-x", passphrase = "p".repeat(12))
                    val before = synchronized(b.trace) { b.trace.size }
                    assertNull("$id $phase", b.manager.onSignal("alice", signal, now))
                    // This call's own line, not one an earlier id left: for DECLINE, CANCEL and READY it is the only trace.
                    val said = synchronized(b.trace) { b.trace.drop(before) }
                    assertEquals("$id $phase", 1, said.size)
                    assertTrue("$id $phase", said.single().contains("refused a non-canonical id from alice (phase $phase)"))
                }
            }
            assertTrue(b.rows.isEmpty())
            assertTrue(b.signals.sent.isEmpty())
            assertTrue(
                b.manager.states.value
                    .isEmpty(),
            )
            assertFalse(File(modelRoot, "bob").exists())
        }

    @Test
    fun anAcceptNamingModelsThatWereNotOfferedIsCancelledAndNothingIsHosted() =
        runBlocking {
            for (md in listOf(listOf(P2), listOf(P1, P1), emptyList())) {
                val (a, b, _) = modelPair()
                b.signals.rewrite = { if (it.phase == TransferPayload.PHASE_ACCEPT) it.copy(md = md) else it }
                val id = offerModels(a, listOf(P1))
                awaitIncoming(b, id)
                assertNull(b.manager.accept(id))
                awaitPhase(a, id, TransferPhase.Failed)
                await("alice cancels for $md") { a.signals.sent.any { it.phase == TransferPayload.PHASE_CANCEL } }
                assertFalse("$md", "host" in a.log.events)
            }
        }

    @Test
    fun aFileAcceptCarryingPinsIsStillAFile() =
        runBlocking {
            val (a, b, _) = modelPair()
            a.withClip()
            b.signals.rewrite = { if (it.phase == TransferPayload.PHASE_ACCEPT) it.copy(md = listOf(P1)) else it }
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
            assertNull(a.record(id)!!.models)
        }

    @Test
    fun aModelThatFailsItsPinsIsCorruptOnBothCardsAndNothingIsInstalled() =
        runBlocking {
            val (a, b, shelves) = modelPair()
            shelves.first.corrupt += P2
            val id = offerModels(a, listOf(P1, P2))
            awaitIncoming(b, id)
            assertNull(b.manager.accept(id))
            awaitPhase(a, id, TransferPhase.Failed)
            awaitPhase(b, id, TransferPhase.Failed)
            // The stream's own seal passed: the pins are what refused it, before the verdict went back.
            assertEquals(TransferPayload.REASON_CORRUPT, a.record(id)!!.reason)
            assertEquals(TransferPayload.REASON_CORRUPT, b.record(id)!!.reason)
            assertTrue(shelves.second.installed.isEmpty())
            await("the stage is removed") { shelves.second.stages.none { it.exists() } }
        }

    @Test
    fun aCancelMidStreamOrAnInstallThatFailsLeavesNoStaging() =
        runBlocking {
            val (a, b, shelves) = modelPair()
            val gate = CountDownLatch(1).also { shelves.first.hold = it }
            val id = offerModels(a, listOf(P1))
            awaitIncoming(b, id)
            assertNull(b.manager.accept(id))
            awaitPhase(b, id, TransferPhase.Transferring)
            await("bob has staged something") { shelves.second.stages.any { it.exists() } }
            b.manager.cancel(id)
            gate.countDown()
            awaitPhase(b, id, TransferPhase.Cancelled)
            await("a cancelled stage is removed") { shelves.second.stages.none { it.exists() } }
            assertTrue(shelves.second.installed.isEmpty())

            // A commit that throws after the verdict, before the sink's own cleanup could run: the sender stays Done
            // whichever of the verdict and the receiver's CANCEL lands first, the receiver failed, and only finish's
            // discard can have removed the stage.
            shelves.first.hold = null
            shelves.second.commitThrows = true
            await("the first transfer ends on alice's side too") {
                a.manager.states.value
                    .isEmpty() &&
                    b.manager.states.value
                        .isEmpty()
            }
            val again = offerModels(a, listOf(P1))
            awaitIncoming(b, again)
            assertNull(b.manager.accept(again))
            awaitPhase(a, again, TransferPhase.Done)
            awaitPhase(b, again, TransferPhase.Failed)
            assertEquals("the models arrived; the install failed", TransferPayload.REASON_NOT_SAVED, b.record(again)!!.reason)
            await("a stage whose install failed is removed") { shelves.second.stages.none { it.exists() } }
            await("alice is done with it") { again !in a.manager.states.value }
            assertFalse("the sender heard the verdict and keeps Done", TransferPhase.Failed in a.phases(again))
        }

    @Test
    fun aCancelAfterTheLastByteWaitsForTheVerdict() =
        runBlocking {
            // Bob holds his verdict while a CANCEL reaches Alice, who has sent every byte: the verdict decides, and she
            // ends Done. This is the maintainer's rule, for a file and models alike: a receiver that read it intact
            // and then could not keep it says so on its own card.
            // Alice's one verdict read must outlast the held window on a starved runner, or she times out into Failed.
            val (a, b, shelves) = modelPair(modelTimings.copy(verdictWaitMs = 10_000))
            val gate = CountDownLatch(1).also { shelves.second.checkGate = it }
            try {
                val id = offerModels(a, listOf(P1))
                awaitIncoming(b, id)
                assertNull(b.manager.accept(id))
                await("alice has sent every byte") {
                    a.manager.states.value[id]
                        ?.let { it.bytes == it.size } == true
                }
                val cancel = TransferPayload(id = id, phase = TransferPayload.PHASE_CANCEL, reason = TransferPayload.REASON_CONNECTION)
                assertNull(a.manager.onSignal("bob", cancel, clock()))
                assertEquals("the cancel waits for the verdict", TransferPhase.Transferring, a.record(id)!!.phase)
                gate.countDown()
                awaitPhase(a, id, TransferPhase.Done)
                awaitPhase(b, id, TransferPhase.Done)
                assertEquals(listOf(P1), shelves.second.installed)
                assertFalse(TransferPhase.Failed in a.phases(id))
                assertTrue("alice sends no CANCEL back", a.signals.sent.none { it.phase == TransferPayload.PHASE_CANCEL })
            } finally {
                gate.countDown()
            }
        }

    @Test
    fun aRetryOfModelsOffersTheSamePinsAgain() =
        runBlocking {
            // Its offer must expire, so this case keeps the short TTL.
            val (a, b, _) = modelPair(fast)
            a.signals.drop = { it.phase == TransferPayload.PHASE_OFFER }
            val id = offerModels(a, listOf(P2))
            awaitPhase(a, id, TransferPhase.Expired)
            assertTrue(a.manager.retryReadable(a.record(id)!!))
            a.signals.drop = { false }
            await("alice drops it from the live map") { id !in a.manager.states.value }
            val retried = a.manager.retry(id) as OfferOutcome.Started
            assertEquals(
                listOf(P2),
                a.signals.sent
                    .last { it.phase == TransferPayload.PHASE_OFFER }
                    .md,
            )
            awaitIncoming(b, retried.id)
            assertTrue(a.record(id)!!.retried)
        }

    private companion object {
        const val P1 = 0x1111_1111L
        const val P2 = 0x2222_2222L
        const val P3 = 0x3333_3333L
    }
}
