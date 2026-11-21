package app.getknit.knit.transfer

import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.TransferPhase
import app.getknit.knit.data.message.TransferRecord
import app.getknit.knit.mesh.protocol.AppPin
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.TransferPayload
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Collections
import kotlin.random.Random

/**
 * A peer update over a direct transfer (ADR 2026-11.29m7): Alice offers her APK in answer to Bob's ask, and Bob's
 * manager accepts it by itself only when his own ask names it. Two managers back to back through the fakes of
 * `TransferManagerTest`, in real time over loopback, each case waiting on the rows the managers write.
 */
class TransferAppUpdateTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = { System.currentTimeMillis() }
    private val timings =
        TransferTimings(
            offerTtlMs = 5_000,
            readyWaitMs = 2_000,
            groupUpMs = 200,
            hostWindowMs = 3_000,
            joinStartDelayMs = 20,
            joinWindowMs = 2_000,
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
    private val apk = Random(11).nextBytes(TransferStream.CHUNK_BYTES * 2 + 77)
    private val requestId = FrameId.new()

    /** This app's own update as a fake: the sender's APK and what it pins, and the receiver's asks and staging. */
    private class FakeAppUpdate(
        private val bytes: ByteArray,
        private val versionCode: Long,
        // The SHA-256 the sender's offer pins; a different one makes the pin and the bytes disagree.
        private val pinned: ByteArray = sha256(bytes),
    ) : TransferAppUpdate {
        /** The receiver's open asks, as (peer, request id, version); each is spent by the first offer naming it. */
        val asks: MutableSet<Triple<String, String, Long>> = Collections.synchronizedSet(mutableSetOf())
        val asked: MutableList<AppPin> = Collections.synchronizedList(mutableListOf())
        val sinks: MutableList<Sink> = Collections.synchronizedList(mutableListOf())

        /** Every [ended] call, as (peer, request id, done). */
        val ends: MutableList<Triple<String, String, Boolean>> = Collections.synchronizedList(mutableListOf())
        var archiveOk = true

        override suspend fun source(): AppSource =
            AppSource(
                TransferSource("Knit-2.14.0.apk", bytes.size.toLong(), OfferReading.APK_MIME) {
                    ByteArrayInputStream(bytes)
                },
                versionCode,
                pinned,
            )

        override fun expects(
            peerId: String,
            pin: AppPin,
            size: Long,
        ): Boolean {
            asked += pin
            return asks.remove(Triple(peerId, pin.requestId, pin.versionCode))
        }

        override suspend fun sink(
            transferId: String,
            pin: AppPin,
            size: Long,
        ): TransferSink = Sink(transferId, pin) { archiveOk }.also { sinks += it }

        override fun ended(
            peerId: String,
            pin: AppPin,
            done: Boolean,
        ) {
            ends += Triple(peerId, pin.requestId, done)
        }

        class Sink(
            val transferId: String,
            private val pin: AppPin,
            private val archiveOk: () -> Boolean,
        ) : TransferSink {
            val buffer = ByteArrayOutputStream()

            @Volatile var committed = false

            @Volatile var discarded = false

            override val uri: String = "staging://$transferId"

            override fun stream(): OutputStream = buffer

            override suspend fun check(): Boolean = sha256(buffer.toByteArray()).contentEquals(pin.sha256) && archiveOk()

            override suspend fun commit() {
                committed = true
            }

            override suspend fun discard() {
                discarded = true
            }
        }
    }

    private class Side(
        val name: String,
        val rows: MutableList<MessageEntity>,
        val signals: FakeTransferSignals,
        val manager: TransferManager,
        val app: FakeAppUpdate?,
        val files: FakeTransferFiles,
        /** The manager's own log lines: a refusal's reason is the barrier a negative case waits on. */
        val trace: MutableList<String>,
    ) {
        fun refused(why: String): Boolean = synchronized(trace) { trace.any { it.endsWith("refused: $why") } }

        fun record(id: String): TransferRecord? =
            synchronized(rows) { rows.lastOrNull { it.id == TransferRecord.rowId(id) } }?.let { TransferRecord.decode(it.body) }
    }

    private fun side(
        name: String,
        app: FakeAppUpdate?,
    ): Side {
        val log = SideLog(name)
        val rows = Collections.synchronizedList(mutableListOf<MessageEntity>())
        val messages = mockk<MessageRepository>()
        coEvery { messages.save(any()) } answers { rows += firstArg<MessageEntity>() }
        every { messages.observeMessage(any()) } answers {
            val rowId = firstArg<String>()
            flowOf(synchronized(rows) { rows.lastOrNull { it.id == rowId } })
        }
        val signals = FakeTransferSignals(log, scope, clock)
        val files = FakeTransferFiles()
        val trace = Collections.synchronizedList(mutableListOf<String>())
        val manager =
            TransferManager(
                messages = messages,
                signals = signals,
                wifi = FakeDirectWifi(log),
                files = files,
                scope = scope,
                selfId = { name },
                peerNearby = { true },
                timings = timings,
                clock = clock,
                newPort = FakeDirectWifi::freeLoopbackPort,
                log = { trace += it },
                app = app,
            )
        return Side(name, rows, signals, manager, app, files, trace)
    }

    private fun pair(
        alice: FakeAppUpdate = FakeAppUpdate(apk, VERSION),
        bob: FakeAppUpdate? = FakeAppUpdate(apk, VERSION),
    ): Pair<Side, Side> {
        val a = side("alice", alice)
        val b = side("bob", bob)
        a.signals.peer = b.manager
        b.signals.peer = a.manager
        return a to b
    }

    private suspend fun await(
        what: String,
        condition: () -> Boolean,
    ) {
        val met =
            withTimeoutOrNull(TIMEOUT_MS) {
                while (!condition()) delay(10)
                true
            }
        if (met == null) fail("$what (timed out)")
    }

    private suspend fun offer(a: Side): String = (a.manager.offerApp("bob", requestId, VERSION) as OfferOutcome.Started).id

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun anUpdateThisPhoneAskedForAcceptsItselfAndArrivesStagedNotSaved() =
        runBlocking {
            val (a, b) = pair()
            b.app!!.asks += Triple("alice", requestId, VERSION)
            val id = offer(a)
            // Nobody taps Accept on Bob's side: the ask was the answer.
            await("alice is done") { a.record(id)?.phase == TransferPhase.Done }
            await("bob is done") { b.record(id)?.phase == TransferPhase.Done }
            val sink = b.app.sinks.single()
            assertArrayEquals(apk, sink.buffer.toByteArray())
            assertTrue(sink.committed)
            assertEquals(id, sink.transferId)
            // Staged, never published: nothing for an Open, and both records carry what the offer pinned.
            assertNull(b.record(id)!!.savedUri)
            assertEquals(VERSION, b.record(id)!!.app!!.versionCode)
            assertEquals(sha256(apk).joinToString("") { "%02x".format(it) }, b.record(id)!!.app!!.sha256)
            assertEquals(VERSION, a.record(id)!!.app!!.versionCode)
            // The sender's row names the ask it answered, which folds this transfer into that ask's card.
            assertEquals(requestId, a.record(id)!!.app!!.requestId)
            // The ask is spent: it named this offer once.
            assertTrue(b.app.asks.isEmpty())
            // The receiver's name for it is its own, never the sender's file name.
            assertEquals(OfferReading.APP_LABEL, b.record(id)!!.name)
            // Never offered again from the sender's card.
            assertFalse(a.record(id)!!.retryable(live = false))
            // The receiver's coordinator hears it landed, once; the sender's is told nothing (it asked for nothing).
            await("bob's end is told") { b.app.ends.isNotEmpty() }
            assertEquals(listOf(Triple("alice", requestId, true)), b.app.ends.toList())
            assertTrue(a.app!!.ends.isEmpty())
        }

    @Test
    fun anUpdateNobodyAskedForIsDroppedWithoutARow() =
        runBlocking {
            val (a, b) = pair()
            val id = offer(a)
            await("bob refused the offer") { b.refused("an update offer nobody asked for") }
            assertTrue(b.app!!.asked.isNotEmpty())
            assertNull(b.record(id))
            assertTrue(
                b.manager.states.value
                    .isEmpty(),
            )
            assertTrue(b.app.sinks.isEmpty())
            // Bob says nothing back: a refusal would tell a contact which offers this phone would take.
            assertTrue(b.signals.sent.isEmpty())
        }

    @Test
    fun anUpdateToABuildThatTakesNoneIsDroppedRatherThanSavedToDownloads() =
        runBlocking {
            val (a, b) = pair(bob = null)
            val id = offer(a)
            await("bob refused the offer") { b.refused("an update offer to a build that takes none") }
            assertNull(b.record(id))
            assertTrue(
                b.manager.states.value
                    .isEmpty(),
            )
        }

    @Test
    fun anUpdateWhoseBytesDoNotMatchItsPinReadsCorruptOnBothCards() =
        runBlocking {
            val (a, b) = pair(alice = FakeAppUpdate(apk, VERSION, pinned = sha256(byteArrayOf(1, 2, 3))))
            b.app!!.asks += Triple("alice", requestId, VERSION)
            val id = offer(a)
            await("bob fails it") { b.record(id)?.phase == TransferPhase.Failed }
            await("alice fails it") { a.record(id)?.phase == TransferPhase.Failed }
            assertEquals(TransferPayload.REASON_CORRUPT, b.record(id)!!.reason)
            assertEquals(TransferPayload.REASON_CORRUPT, a.record(id)!!.reason)
            // A transfer short of done frees the ask at once (the hint's Ask again), told once.
            await("bob's end is told") { b.app.ends.isNotEmpty() }
            assertEquals(listOf(Triple("alice", requestId, false)), b.app.ends.toList())
            await("bob threw the staging away") {
                b.app.sinks
                    .single()
                    .discarded
            }
            assertFalse(
                b.app.sinks
                    .single()
                    .committed,
            )
        }

    @Test
    fun anArchiveTheReceiverRefusesReadsCorruptOnBothCards() =
        runBlocking {
            val (a, b) = pair()
            b.app!!.asks += Triple("alice", requestId, VERSION)
            b.app.archiveOk = false
            val id = offer(a)
            await("bob fails it") { b.record(id)?.phase == TransferPhase.Failed }
            await("alice fails it") { a.record(id)?.phase == TransferPhase.Failed }
            assertEquals(TransferPayload.REASON_CORRUPT, a.record(id)!!.reason)
        }

    @Test
    fun anUpdateThisPhoneAskedForAndHasNoRoomForIsDeclinedSoTheSenderStops() =
        runBlocking {
            // Asked for, but the phone has filled up since: its own accept refuses, and it says why rather than leaving
            // the sender's card to wait out the offer.
            val (a, b) = pair()
            b.app!!.asks += Triple("alice", requestId, VERSION)
            b.files.free = 0L
            val id = offer(a)
            await("alice hears the decline") { a.record(id)?.phase == TransferPhase.Declined }
            assertEquals(TransferPayload.REASON_NO_SPACE, a.record(id)!!.reason)
            assertEquals(TransferPhase.Declined, b.record(id)!!.phase)
            assertTrue(b.app.sinks.isEmpty())
        }

    @Test
    fun aStaleUpdateOfferNeverSpendsTheAskItNames() =
        runBlocking {
            // A custody replay of an old offer is dropped before its pin is read, so the ask still waits for a live one.
            val (_, b) = pair()
            b.app!!.asks += Triple("alice", requestId, VERSION)
            val offer =
                TransferPayload(
                    id = FrameId.new(),
                    phase = TransferPayload.PHASE_OFFER,
                    name = "Knit-2.14.0.apk",
                    size = apk.size.toLong(),
                    ap = AppPin(requestId, VERSION, sha256(apk)),
                )
            assertNull(b.manager.onSignal("alice", offer, clock() - TransferManager.STALE_OFFER_MS - 1))
            assertTrue(b.app.asked.isEmpty())
            assertEquals(1, b.app.asks.size)
        }

    @Test
    fun anAskForAnotherVersionIsRefusedAsGoneBeforeAnythingIsOffered() =
        runBlocking {
            val (a, _) = pair()
            assertEquals(OfferOutcome.Refused(TransferRefusal.Gone), a.manager.offerApp("bob", requestId, VERSION + 1))
            assertTrue(a.signals.sent.isEmpty())
        }

    @Test
    fun aMalformedPinNeverSpendsTheAsk() =
        runBlocking {
            val (_, b) = pair()
            b.app!!.asks += Triple("alice", "not-a-frame-id", VERSION)
            val offer =
                TransferPayload(
                    id = FrameId.new(),
                    phase = TransferPayload.PHASE_OFFER,
                    name = "Knit-2.14.0.apk",
                    size = apk.size.toLong(),
                    ap = AppPin("not-a-frame-id", VERSION, sha256(apk)),
                )
            assertNull(b.manager.onSignal("alice", offer, clock()))
            assertTrue("the pin was refused before the ask was read", b.app.asked.isEmpty())
            assertEquals(1, b.app.asks.size)
        }

    private companion object {
        const val VERSION = 21_400_900L
        const val TIMEOUT_MS = 8_000L

        fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
    }
}
