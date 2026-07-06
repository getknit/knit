package app.getknit.knit.mesh

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.MeshBlobStore
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.InboundSettings
import app.getknit.knit.identity.IdentitySource
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.TinkInit
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.ProfileContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.notifications.Notifier
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.ConcurrentHashMap

/**
 * The keystone integration test the review's finding #4 called for: it drives the **real**
 * [InboundPipeline.onDeliver] end-to-end (verify → custody → dispatch → decrypt → deliver/ack) with real
 * Tink keypairs, the real DTN services ([ForwardSync]/[KeyExchange]/[PendingInbound]/[AckSync]), a
 * [FakeLoopTransport], and mockk stand-ins for the concrete Room-backed repos. It pins down the security
 * gate and all three ordering contracts the extraction exists to make mechanical:
 *
 *  - **custody-before-relay** — a relayed floodable frame is carried before it is (or isn't) delivered.
 *  - **replay-runs-last** — a profile pins the sender's key, then replays the frame parked before it.
 *  - **no-throw-out-of-onDeliver** — an unauthenticatable frame is dropped (counted) without throwing.
 *
 * Runs under Robolectric so `android.util.Log` (used throughout the pipeline) is shadowed on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
class InboundPipelineTest {
    /** A device identity: its cipher (private keys), its public bundle, and the nodeId it derives to. */
    private class Party(
        val crypto: MessageCrypto,
        val bundle: PublicKeyBundle,
    ) {
        val nodeId: String = NodeId.fromPublicKeyBundle(bundle.encoded)

        /** Wraps + signs [env] with this party's key (mirrors MeshManager.sign). */
        fun sign(
            env: RelayEnvelope,
            relay: Boolean = true,
        ): WireEnvelope {
            val signed = WireCodec.encodeEnvelope(env)
            return WireEnvelope(relay = relay, sig = crypto.signRaw(signed), signed = signed)
        }
    }

    private fun party(): Party {
        TinkInit.ensure()
        val hybrid = KeysetHandle.generateNew(KeyTemplates.get(HYBRID_TEMPLATE))
        val sig = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        return Party(MessageCrypto(hybrid, sig), PublicKeyBundle.fromPrivate(hybrid, sig))
    }

    private class FakeIdentity(
        private val self: Party,
    ) : IdentitySource {
        override suspend fun nodeId(): String = self.nodeId

        override fun publicKeyBundle(): String = self.bundle.encoded
    }

    private class FakeSettings : InboundSettings {
        val blocked = MutableStateFlow(emptySet<String>())
        override val blockedNodeIds get() = blocked
        override val blockedDeviceTags = MutableStateFlow(emptySet<String>())
        override val contentFilteringEnabled = MutableStateFlow(true)
        override val ownAvatarHash = MutableStateFlow<String?>(null)
        override val displayName = MutableStateFlow("Me")

        override suspend fun block(
            nodeId: String,
            deviceTag: String?,
        ) {
            blocked.value = blocked.value + nodeId
        }
    }

    /** Minimal in-memory [ForwardStore] so a test can assert what the pipeline custodied. */
    private class FakeForwardStore : ForwardStore {
        private val frames = linkedMapOf<String, CarriedFrame>()

        override suspend fun store(
            frame: CarriedFrame,
            origin: Int,
            now: Long,
        ): Boolean {
            frames.putIfAbsent(frame.envelope.id, frame)
            return true
        }

        override suspend fun liveFrames(now: Long): List<CarriedFrame> = frames.values.toList()

        override suspend fun liveIds(now: Long): List<String> = frames.keys.toList()

        override suspend fun attachmentHashesNeedingFetch(): List<String> = emptyList()

        override suspend fun recipientOf(id: String): String? = frames[id]?.envelope?.recipientId

        override suspend fun has(id: String): Boolean = frames.containsKey(id)

        override suspend fun remove(id: String) {
            frames.remove(id)
        }

        override suspend fun sweepExpired(now: Long): Int = 0
    }

    /** The pipeline under test wired with real DTN services + crypto + transport and mocked repos. */
    private inner class Rig(
        scope: CoroutineScope,
    ) {
        val self = party()
        val transport = FakeLoopTransport(self.nodeId)
        val metrics = MeshMetrics()
        val forwardStore = FakeForwardStore()
        val peerMap = ConcurrentHashMap<String, PeerEntity>()
        val msgMap = ConcurrentHashMap<String, MessageEntity>()
        val peers = mockk<PeerRepository>(relaxed = true)
        val messages = mockk<MessageRepository>(relaxed = true)
        val groups = mockk<GroupRepository>(relaxed = true)
        val reactions = mockk<ReactionRepository>(relaxed = true)
        val blobs = mockk<BlobRepository>(relaxed = true)
        val blobStore = mockk<MeshBlobStore>(relaxed = true)

        // A real in-memory DB purely as the transaction runner for reconcileGroup's db.withTransaction; the repos
        // are mocked, so the mocked find/upsert calls just run harmlessly inside a real (empty) transaction.
        val db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KnitDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        val notifier = mockk<Notifier>(relaxed = true)
        val settings = FakeSettings()
        val forwardSync = ForwardSync(transport, forwardStore, clock = { 0L })
        val blobExchange = BlobExchange(transport, blobStore, selfId = { self.nodeId }, onObtained = { _, _ -> })
        val keyExchange = KeyExchange(transport, selfId = { self.nodeId }, signRaw = self.crypto::signRaw, metrics = metrics)
        val ackSync = AckSync(transport, selfId = { self.nodeId }, signRaw = self.crypto::signRaw, metrics = metrics)
        val pendingInbound = PendingInbound(metrics = metrics)
        val typingTracker = TypingTracker(scope)
        val originated = mutableListOf<RelayEnvelope>()
        val flushed = mutableListOf<String>()
        var failClassify = false
        val pipeline: InboundPipeline

        init {
            coEvery { peers.find(any()) } answers { peerMap[firstArg()] }
            coEvery { peers.upsert(any()) } answers { peerMap[firstArg<PeerEntity>().nodeId] = firstArg() }
            coEvery { messages.exists(any()) } answers { msgMap.containsKey(firstArg<String>()) }
            coEvery { messages.save(any()) } answers { msgMap[firstArg<MessageEntity>().id] = firstArg() }
            coEvery { messages.recipientOf(any()) } answers { msgMap[firstArg<String>()]?.recipientId }
            pipeline =
                InboundPipeline(
                    transport = transport,
                    messages = messages,
                    groups = groups,
                    reactions = reactions,
                    peers = peers,
                    blobs = blobs,
                    blobStore = blobStore,
                    db = db,
                    identity = FakeIdentity(self),
                    settings = settings,
                    messageCrypto = self.crypto,
                    notifier = notifier,
                    metrics = metrics,
                    forwardSync = forwardSync,
                    blobExchange = blobExchange,
                    keyExchange = keyExchange,
                    ackSync = ackSync,
                    pendingInbound = pendingInbound,
                    typingTracker = typingTracker,
                    originate = { originated += it },
                    flushPending = { flushed += it },
                    classifyText = { _, _, _ -> if (failClassify) error("moderation boom") else false },
                )
        }

        /** Pins [p]'s real key under its nodeId, as [handleProfile] would after receiving its profile. */
        fun pin(p: Party) {
            peerMap[p.nodeId] = PeerEntity(nodeId = p.nodeId, pubKey = p.bundle.encoded, updatedAt = 1L)
        }

        fun drops(reason: DropReason): Long = metrics.snapshot().dropsByReason[reason] ?: 0L

        /** A plaintext broadcast-room chat frame. */
        fun broadcastChat(
            author: Party,
            id: String,
            body: String,
        ): RelayEnvelope =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = author.nodeId,
                sentAt = 5L,
                payload = WireCodec.encodePayload(ChatContent(body = body)),
            )

        /** An E2E DM chat frame from [author] addressed to [to], sealed to [to]'s bundle. */
        fun dmChat(
            author: Party,
            to: Party,
            id: String,
            body: String,
        ): RelayEnvelope {
            val header = MessageCrypto.header(id, author.nodeId, 5L, to.nodeId)
            val enc = author.crypto.seal(MessageContent(body = body).encode(), header, mapOf(to.nodeId to to.bundle))!!
            return RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = author.nodeId,
                sentAt = 5L,
                recipientId = to.nodeId,
                payload = WireCodec.encodePayload(ChatContent(enc = enc)),
            )
        }

        fun profile(author: Party): RelayEnvelope =
            RelayEnvelope(
                type = FrameType.PROFILE,
                id = "profile-${author.nodeId}",
                senderId = author.nodeId,
                sentAt = 6L,
                payload = WireCodec.encodePayload(ProfileContent(name = "Peer", status = "", pubKey = author.bundle.encoded)),
            )
    }

    @Test
    fun validDmToSelfIsDecryptedDeliveredAndAcked() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val env = rig.dmChat(alice, rig.self, id = "dm1", body = "hi dm")

            rig.pipeline.onDeliver(alice.sign(env), env, alice.nodeId)

            // Decrypted + delivered.
            assertEquals("hi dm", rig.msgMap["dm1"]?.body)
            // A DM addressed to us floods a RECEIPT back via the origination choke.
            assertTrue("a receipt should be originated", rig.originated.any { it.type == FrameType.RECEIPT })
            // A DM for us is delivered, not custodied.
            assertFalse(rig.forwardStore.has("dm1"))
        }

    @Test
    fun relayedDmForSomeoneElseIsCustodiedNotDelivered() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            val other = party()
            rig.pin(alice)
            val env = rig.dmChat(alice, other, id = "dm2", body = "secret")

            rig.pipeline.onDeliver(alice.sign(env), env, alice.nodeId)

            // custody-before-relay: we carry a DM we're only relaying toward someone else...
            assertTrue("relayed DM should be custodied", rig.forwardStore.has("dm2"))
            // ...but never deliver it locally (it isn't ours to read).
            assertFalse(rig.msgMap.containsKey("dm2"))
        }

    @Test
    fun badSignatureIsDroppedWithoutThrowingAndNotDelivered() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val env = rig.broadcastChat(alice, id = "c3", body = "hi")
            val signed = WireCodec.encodeEnvelope(env)
            // Signed by a different key than the one alice's pinned bundle holds.
            val forgedSig = party().crypto.signRaw(signed)
            val wire = WireEnvelope(relay = true, sig = forgedSig, signed = signed)

            rig.pipeline.onDeliver(wire, env, alice.nodeId) // must not throw

            assertEquals(1L, rig.drops(DropReason.SIG_INVALID))
            assertFalse(rig.msgMap.containsKey("c3"))
        }

    @Test
    fun frameFromUnpinnedSenderIsDroppedParkedAndKeyRequested() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party() // deliberately NOT pinned
            val env = rig.broadcastChat(alice, id = "c4", body = "early")

            rig.pipeline.onDeliver(alice.sign(env), env, alice.nodeId)

            assertEquals(1L, rig.drops(DropReason.NO_SENDER_KEY))
            assertFalse(rig.msgMap.containsKey("c4"))
            // The frame is parked for replay-on-key-arrival.
            val parked = rig.pendingInbound.release(alice.nodeId)
            assertEquals(1, parked.size)
            assertEquals("c4", parked.single().env.id)
        }

    @Test
    fun profileArrivalPinsKeyAndReplaysParkedFrameLast() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party() // unpinned

            // 1) A chat races ahead of alice's profile → dropped + parked, not delivered.
            val chat = rig.broadcastChat(alice, id = "c5", body = "replayed hi")
            rig.pipeline.onDeliver(alice.sign(chat), chat, alice.nodeId)
            assertFalse(rig.msgMap.containsKey("c5"))
            assertEquals(1L, rig.drops(DropReason.NO_SENDER_KEY))

            // 2) alice's profile arrives → pins her key, then (last) replays the parked chat.
            val profile = rig.profile(alice)
            rig.pipeline.onDeliver(alice.sign(profile), profile, alice.nodeId)

            assertNotNull("alice's key should be pinned", rig.peerMap[alice.nodeId]?.pubKey)
            assertEquals("the parked chat should now deliver", "replayed hi", rig.msgMap["c5"]?.body)
            assertTrue(rig.metrics.snapshot().framesReplayed >= 1)
        }

    @Test
    fun frameWhoseKeyDoesNotDeriveToSenderIdIsDropped() =
        runTest {
            val rig = Rig(backgroundScope)
            val victim = party()
            val attacker = party()
            // A corrupted/stale pin: attacker's key stored under the victim's nodeId.
            rig.peerMap[victim.nodeId] = PeerEntity(nodeId = victim.nodeId, pubKey = attacker.bundle.encoded, updatedAt = 1L)
            val env = rig.broadcastChat(victim, id = "c6", body = "hi")

            // Signed by the attacker (matches the bad pin), but the pinned key doesn't derive to the senderId.
            rig.pipeline.onDeliver(attacker.sign(env), env, victim.nodeId)

            assertEquals(1L, rig.drops(DropReason.KEY_NODEID_MISMATCH))
            assertFalse(rig.msgMap.containsKey("c6"))
            assertNull(rig.msgMap["c6"])
        }

    @Test
    fun aThrowingHandlerIsContainedAndDoesNotEscapeOnDeliver() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            rig.failClassify = true // deliverChat's on-device moderation classify() throws
            val env = rig.broadcastChat(alice, id = "c7", body = "hi")

            // Must NOT throw even though a handler does — the router relays only after onDeliver returns.
            rig.pipeline.onDeliver(alice.sign(env), env, alice.nodeId)

            // Delivery was aborted by the throw, but custody-before-relay already captured it, so it re-serves later.
            assertFalse(rig.msgMap.containsKey("c7"))
            assertTrue(rig.forwardStore.has("c7"))
        }

    private companion object {
        const val HYBRID_TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM"
    }
}
