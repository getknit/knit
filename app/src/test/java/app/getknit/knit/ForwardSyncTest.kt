package app.getknit.knit

import app.getknit.knit.mesh.FakeLoopTransport
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.ForwardSync
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshRouter
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.protocol.ChatFrame
import app.getknit.knit.mesh.protocol.Frame
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.ReactionFrame
import app.getknit.knit.mesh.protocol.ReceiptFrame
import app.getknit.knit.mesh.protocol.isStorable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoroutinesApi::class)
class ForwardSyncTest {

    /** In-memory [ForwardStore]; TTL/order mirror the real repository closely enough for the sync logic. */
    private class FakeForwardStore(private val ttlMs: Long = 60_000L) : ForwardStore {
        private data class Row(val frame: ChatFrame, val origin: Int, val expiresAt: Long)
        private val rows = ConcurrentHashMap<String, Row>()

        override suspend fun store(frame: ChatFrame, origin: Int, now: Long) {
            rows.putIfAbsent(frame.id, Row(frame, origin, now + ttlMs))
        }

        override suspend fun liveFrames(now: Long): List<ChatFrame> =
            rows.values.filter { it.expiresAt >= now }.sortedByDescending { it.expiresAt }.map { it.frame }

        override suspend fun recipientOf(id: String): String? = rows[id]?.frame?.recipientId
        override suspend fun has(id: String): Boolean = rows.containsKey(id)
        override suspend fun remove(id: String) { rows.remove(id) }
        override suspend fun sweepExpired(now: Long): Int {
            val before = rows.size
            rows.entries.removeIf { it.value.expiresAt < now }
            return before - rows.size
        }
    }

    /** Records what the sync sends and exposes a fixed neighbor set. */
    private class RecordingTransport : MeshTransport {
        val sent = mutableListOf<Pair<Frame, Peer?>>()
        override val neighbors = MutableStateFlow<Set<Peer>>(emptySet()).asStateFlow()
        override val health = MutableStateFlow(TransportHealth.Healthy).asStateFlow()
        override val inbound = MutableSharedFlow<InboundFrame>().asSharedFlow()
        override val incomingFiles = emptyFlow<ReceivedFile>()
        override fun start() = Unit
        override fun stop() = Unit
        override fun heal() = Unit
        override suspend fun send(frame: Frame, to: Peer?) { sent += frame to to }
        override suspend fun sendFile(file: File, to: Peer, meta: FileMeta) = Unit
    }

    private fun dm(id: String, sender: String, recipient: String) =
        ChatFrame(id = id, senderId = sender, sentAt = 1L, body = "", recipientId = recipient)

    private fun groupMsg(id: String, sender: String, members: List<String>) =
        ChatFrame(
            id = id,
            senderId = sender,
            sentAt = 1L,
            body = "",
            group = GroupInfo(id = "g-test", members = members, createdBy = members.first()),
        )

    private fun ack(messageId: String, by: String) =
        ReceiptFrame(id = "ack-$messageId-$by", senderId = by, ackId = messageId)

    // --- pure predicate ---

    @Test
    fun dmAndGroupChatFramesAreStorableButBroadcastIsNot() {
        assertTrue(dm("m", "a", "b").isStorable())
        assertTrue(groupMsg("g", "a", listOf("a", "b", "c")).isStorable())
        assertFalse(ChatFrame(id = "r", senderId = "a", sentAt = 1L, body = "hi").isStorable()) // room
        assertFalse(ReceiptFrame(id = "x", senderId = "a", ackId = "m").isStorable())
        assertFalse(ReactionFrame(id = "x", senderId = "a", messageId = "m", sentAt = 1L).isStorable())
    }

    // --- onSeen capture & authentication ---

    @Test
    fun relayedDmIsCarriedOnlyWhenAuthenticated() = runTest {
        val rejecting = ForwardSync(RecordingTransport(), FakeForwardStore(), clock = { 0L }, authenticate = { false })
        val store = FakeForwardStore()
        val accepting = ForwardSync(RecordingTransport(), store, clock = { 0L }, authenticate = { true })

        rejecting.onSeen(dm("m1", "a", "b"), ForwardStore.ORIGIN_RELAY)
        accepting.onSeen(dm("m2", "a", "b"), ForwardStore.ORIGIN_RELAY)

        assertTrue("authenticated relay is carried", store.has("m2"))
    }

    @Test
    fun ownSendBypassesAuthentication() = runTest {
        // We can't authenticate our own DM against a pinned key (we don't pin ourselves), so SELF skips it.
        val store = FakeForwardStore()
        val sync = ForwardSync(RecordingTransport(), store, clock = { 0L }, authenticate = { false })

        sync.onSeen(dm("m1", "me", "b"), ForwardStore.ORIGIN_SELF)

        assertTrue(store.has("m1"))
    }

    @Test
    fun broadcastAndNonChatFramesAreNotCarriedButGroupMessagesAre() = runTest {
        val store = FakeForwardStore()
        val sync = ForwardSync(RecordingTransport(), store, clock = { 0L })

        sync.onSeen(ChatFrame(id = "r", senderId = "a", sentAt = 1L, body = "hi"), ForwardStore.ORIGIN_RELAY)
        sync.onSeen(ReceiptFrame(id = "x", senderId = "a", ackId = "m"), ForwardStore.ORIGIN_RELAY)
        sync.onSeen(groupMsg("g1", "a", listOf("a", "b", "c")), ForwardStore.ORIGIN_RELAY)

        assertFalse("the plaintext broadcast room has no destination", store.has("r"))
        assertFalse("a receipt isn't a carried message", store.has("x"))
        assertTrue("a group message is carried for offline members", store.has("g1"))
    }

    // --- vaccine purge ---

    @Test
    fun recipientAckPurgesCarriedDmAndTombstonesIt() = runTest {
        val store = FakeForwardStore()
        val sync = ForwardSync(RecordingTransport(), store, clock = { 0L })
        sync.onSeen(dm("m1", "a", "b"), ForwardStore.ORIGIN_RELAY)
        assertTrue(store.has("m1"))

        sync.onAck(ack("m1", by = "b")) // ack from the addressed recipient
        assertFalse("delivered DM is purged", store.has("m1"))

        // A copy re-offered from an unvaccinated peer must not be re-stored (tombstone).
        sync.onSeen(dm("m1", "a", "b"), ForwardStore.ORIGIN_RELAY)
        assertFalse("tombstone blocks re-store", store.has("m1"))
    }

    @Test
    fun forgedAckFromNonRecipientDoesNotPurge() = runTest {
        val store = FakeForwardStore()
        val sync = ForwardSync(RecordingTransport(), store, clock = { 0L })
        sync.onSeen(dm("m1", "a", "b"), ForwardStore.ORIGIN_RELAY)

        sync.onAck(ack("m1", by = "attacker")) // not the recipient "b"

        assertTrue("forged receipt cannot evict an undelivered DM", store.has("m1"))
    }

    @Test
    fun groupMessageIsNotVaccinePurgedByAReceipt() = runTest {
        // A group has no single recipient, so the recipient-keyed purge can't apply: a group message is
        // bounded by TTL/caps only. Even a receipt from a real member must not evict it (other members
        // may still be offline).
        val store = FakeForwardStore()
        val sync = ForwardSync(RecordingTransport(), store, clock = { 0L })
        sync.onSeen(groupMsg("g1", sender = "a", members = listOf("a", "b", "c")), ForwardStore.ORIGIN_RELAY)
        assertTrue(store.has("g1"))

        sync.onAck(ack("g1", by = "b")) // a member, but a group frame has no recipientId to match

        assertTrue("a group message is never vaccine-purged", store.has("g1"))
    }

    // --- push on contact ---

    @Test
    fun pushesCarriedDmsToNewcomerOnceUntilItDeparts() = runTest {
        val transport = RecordingTransport()
        val store = FakeForwardStore()
        val sync = ForwardSync(transport, store, clock = { 0L })
        sync.onSeen(dm("m1", "a", "b"), ForwardStore.ORIGIN_SELF)

        sync.onNeighborAdded(Peer("b"))
        sync.onNeighborAdded(Peer("b")) // memo: not re-offered on a re-emit/flap
        assertEquals(listOf("m1"), transport.sent.map { it.first.id })

        sync.onNeighborRemoved("b")
        sync.onNeighborAdded(Peer("b")) // memo cleared on departure → re-offered
        assertEquals(listOf("m1", "m1"), transport.sent.map { it.first.id })
    }

    @Test
    fun doesNotPushADmBackToItsAuthor() = runTest {
        val transport = RecordingTransport()
        val sync = ForwardSync(transport, FakeForwardStore(), clock = { 0L })
        sync.onSeen(dm("m1", "a", "b"), ForwardStore.ORIGIN_RELAY)

        sync.onNeighborAdded(Peer("a")) // "a" authored m1

        assertTrue("a should not be handed back its own message", transport.sent.isEmpty())
    }

    @Test
    fun memberTargetedPushOnlyOffersAGroupMessageToRosterMembers() = runTest {
        val transport = RecordingTransport()
        val sync = ForwardSync(transport, FakeForwardStore(), clock = { 0L })
        sync.onSeen(groupMsg("g1", sender = "a", members = listOf("a", "b", "c")), ForwardStore.ORIGIN_SELF)

        sync.onNeighborAdded(Peer("x")) // not in the roster — must not be sprayed group traffic
        assertTrue("a non-member is never offered a group message", transport.sent.isEmpty())

        sync.onNeighborAdded(Peer("c")) // a roster member — offered once
        assertEquals(listOf("g1"), transport.sent.map { it.first.id })
    }

    // --- TTL sweep ---

    @Test
    fun sweepReclaimsExpiredCarriedDms() = runTest {
        val store = FakeForwardStore(ttlMs = 100L)
        var now = 0L
        val sync = ForwardSync(RecordingTransport(), store, clock = { now })
        sync.onSeen(dm("m1", "a", "b"), ForwardStore.ORIGIN_RELAY)

        now = 50L
        sync.sweepExpired()
        assertTrue("not yet expired", store.has("m1"))

        now = 200L
        sync.sweepExpired()
        assertFalse("expired DM reclaimed", store.has("m1"))
    }

    // --- integration: store-and-forward across a temporal gap ---

    /** A node that carries DMs, delivers ones addressed to it, and acks — a minimal MeshManager stand-in. */
    private class Node(val id: String, scope: CoroutineScope) {
        val transport = FakeLoopTransport(id)
        val store = FakeForwardStore()
        val delivered = mutableListOf<String>()
        val notified = mutableListOf<String>()
        private val seenDelivered = mutableSetOf<String>()
        val sync = ForwardSync(transport, store, clock = { 0L })
        private val router = MeshRouter(transport, scope, jitter = { 0L }) { frame, _ -> onDeliver(frame) }

        private suspend fun onDeliver(frame: Frame) {
            // Carry what we're relaying onward: a DM toward someone else, a group message for other
            // members (whether or not we're a member ourselves) — mirrors MeshManager's capture gate.
            if (frame is ChatFrame && frame.isStorable()) {
                val carry = frame.group != null || frame.recipientId != id
                if (carry) sync.onSeen(frame, ForwardStore.ORIGIN_RELAY)
            }
            when (frame) {
                is ChatFrame -> {
                    val members = frame.group?.members
                    val forMe = if (members != null) id in members else frame.recipientId == id
                    if (forMe) {
                        if (seenDelivered.add(frame.id)) notified += frame.id // first-delivery notify gate
                        delivered += frame.id
                        // Only a DM acks (a group has no single-recipient receipt).
                        if (members == null) {
                            val ack = ReceiptFrame(id = "ack-${frame.id}-$id", senderId = id, ackId = frame.id)
                            router.originate(ack)
                        }
                    }
                }
                is ReceiptFrame -> sync.onAck(frame)
                else -> Unit
            }
        }

        fun start(scope: CoroutineScope) {
            router.start()
            scope.launch {
                var known = emptySet<String>()
                transport.neighbors.collect { current ->
                    current.filter { it.nodeId !in known }.forEach { sync.onNeighborAdded(it) }
                    known = current.map { it.nodeId }.toSet()
                }
            }
        }

        suspend fun send(frame: ChatFrame) {
            router.originate(frame)
            sync.onSeen(frame, ForwardStore.ORIGIN_SELF)
        }
    }

    @Test
    fun dmReachesRecipientThatConnectsAfterTheFloodViaACarrier() = runTest(UnconfinedTestDispatcher()) {
        // a and c are never connected at the same time; b is the carrier that bridges the temporal gap.
        val a = Node("a", backgroundScope)
        val b = Node("b", backgroundScope)
        a.transport.connect(b.transport)
        a.start(backgroundScope); b.start(backgroundScope)

        a.send(dm("dm1", sender = "a", recipient = "c"))
        advanceUntilIdle()

        assertTrue("b carries the DM while c is away", b.store.has("dm1"))

        // c appears later, connecting only to b (a is no longer relevant).
        val c = Node("c", backgroundScope)
        c.start(backgroundScope)
        b.transport.connect(c.transport)
        advanceUntilIdle()

        assertEquals("c receives the carried DM exactly once", listOf("dm1"), c.delivered)
        assertEquals("and notifies once", listOf("dm1"), c.notified)
        assertFalse("c's ack vaccinates the carrier b", b.store.has("dm1"))
    }

    @Test
    fun groupMessageReachesMemberThatConnectsAfterTheFloodViaACarrier() = runTest(UnconfinedTestDispatcher()) {
        // Group {a, b, c}. a and c are never connected at the same time; member b carries the message and
        // re-offers it to c when c finally appears — the temporal-gap delivery, now for a group message.
        val members = listOf("a", "b", "c")
        val a = Node("a", backgroundScope)
        val b = Node("b", backgroundScope)
        a.transport.connect(b.transport)
        a.start(backgroundScope); b.start(backgroundScope)

        a.send(groupMsg("g1", sender = "a", members = members))
        advanceUntilIdle()

        assertTrue("b carries the group message while c is away", b.store.has("g1"))

        // c appears later, connecting only to b (a is gone).
        val c = Node("c", backgroundScope)
        c.start(backgroundScope)
        b.transport.connect(c.transport)
        advanceUntilIdle()

        assertEquals("c receives the carried group message exactly once", listOf("g1"), c.delivered)
        assertEquals("and notifies once", listOf("g1"), c.notified)
        assertTrue("groups aren't vaccine-purged — b keeps carrying it until TTL", b.store.has("g1"))
    }
}
