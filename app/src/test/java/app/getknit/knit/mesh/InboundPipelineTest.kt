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
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.InboundSettings
import app.getknit.knit.identity.IdentitySource
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.TinkInit
import app.getknit.knit.mesh.protocol.BlobReqContent
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.GroupLeaveContent
import app.getknit.knit.mesh.protocol.KeyReqContent
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ProfileContent
import app.getknit.knit.mesh.protocol.ReactionContent
import app.getknit.knit.mesh.protocol.ReceiptContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.TypingContent
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.notifications.Notifier
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import io.mockk.coEvery
import io.mockk.coVerify
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
import java.io.File
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
@Suppress("LargeClass", "TooManyFunctions") // cohesive single-SUT suite over one shared Rig; splitting would scatter it
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
        val sig = KeysetHandle.generateNew(KeyTemplates.get("ED25519_RAW"))
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
        val groupMap = ConcurrentHashMap<String, GroupEntity>()
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
            coEvery { groups.find(any()) } answers { groupMap[firstArg()] }
            coEvery { groups.upsert(any()) } answers { groupMap[firstArg<GroupEntity>().groupId] = firstArg() }
            // Realistic "nothing held" defaults: relaxed mockk otherwise returns "" for a String? and a bare
            // Object (not a byte[]) for a ByteArray?, which would crash the attachment-screen path in onObtained.
            coEvery { messages.attachmentKeyForHash(any()) } returns null
            coEvery { blobs.bytes(any()) } returns null
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

        /** Signs [env] with [author]'s key and drives it through the pipeline (the common onDeliver call). */
        suspend fun deliver(
            author: Party,
            env: RelayEnvelope,
        ) = pipeline.onDeliver(author.sign(env), env, author.nodeId)

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

        fun profile(
            author: Party,
            avatarHash: String? = null,
            sentAt: Long = 6L,
        ): RelayEnvelope =
            RelayEnvelope(
                type = FrameType.PROFILE,
                id = "profile-${author.nodeId}-$sentAt",
                senderId = author.nodeId,
                sentAt = sentAt,
                payload =
                    WireCodec.encodePayload(
                        ProfileContent(name = "Peer", status = "", pubKey = author.bundle.encoded, avatarHash = avatarHash),
                    ),
            )

        /** A plaintext broadcast chat that @-mentions [mentionOf]. */
        fun mentionChat(
            author: Party,
            id: String,
            body: String,
            mentionOf: Party,
        ): RelayEnvelope =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = author.nodeId,
                sentAt = 5L,
                payload =
                    WireCodec.encodePayload(
                        ChatContent(body = body, mentions = listOf(Mention(nodeId = mentionOf.nodeId, name = "Me"))),
                    ),
            )

        /** A plaintext group chat carrying [group]'s roster (delivered when we're a member). */
        fun groupChat(
            author: Party,
            group: GroupInfo,
            id: String,
            body: String,
        ): RelayEnvelope =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = author.nodeId,
                sentAt = 5L,
                group = group,
                payload = WireCodec.encodePayload(ChatContent(body = body)),
            )

        /** A plaintext broadcast chat that references an out-of-band attachment blob [attachmentHash]. */
        fun attachmentChat(
            author: Party,
            id: String,
            attachmentHash: String,
        ): RelayEnvelope =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = author.nodeId,
                sentAt = 5L,
                payload = WireCodec.encodePayload(ChatContent(body = "", attachmentHash = attachmentHash)),
            )

        /** A DM whose encrypted envelope claims crypto-scheme version [v] (for the decrypt version gate). */
        fun dmWithEnvVersion(
            author: Party,
            to: Party,
            id: String,
            v: Int,
        ): RelayEnvelope =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = author.nodeId,
                sentAt = 5L,
                recipientId = to.nodeId,
                payload =
                    WireCodec.encodePayload(
                        ChatContent(enc = EncEnvelope(v = v, nonce = ByteArray(12), ct = ByteArray(0), keys = emptyList())),
                    ),
            )

        fun reaction(
            author: Party,
            messageId: String,
            emoji: String?,
            sentAt: Long = 7L,
        ): RelayEnvelope =
            RelayEnvelope(
                type = FrameType.REACTION,
                id = "react-$messageId-${author.nodeId}",
                senderId = author.nodeId,
                sentAt = sentAt,
                payload = WireCodec.encodePayload(ReactionContent(messageId = messageId, emoji = emoji)),
            )

        fun receipt(
            author: Party,
            ackId: String,
        ): RelayEnvelope =
            RelayEnvelope(
                type = FrameType.RECEIPT,
                id = "receipt-$ackId-${author.nodeId}",
                senderId = author.nodeId,
                sentAt = 7L,
                payload = WireCodec.encodePayload(ReceiptContent(ackId = ackId)),
            )

        fun groupLeave(
            author: Party,
            groupId: String,
        ): RelayEnvelope =
            RelayEnvelope(
                type = FrameType.GROUP_LEAVE,
                id = "leave-$groupId-${author.nodeId}",
                senderId = author.nodeId,
                sentAt = 7L,
                payload = WireCodec.encodePayload(GroupLeaveContent(groupId = groupId)),
            )

        fun groupUpdate(
            author: Party,
            group: GroupInfo,
            sentAt: Long = 7L,
        ): RelayEnvelope =
            RelayEnvelope(
                type = FrameType.GROUP_UPDATE,
                id = "gupd-${group.id}-$sentAt",
                senderId = author.nodeId,
                sentAt = sentAt,
                group = group,
                payload = ByteArray(0),
            )

        fun typing(
            author: Party,
            groupId: String? = null,
            recipientId: String? = null,
        ): RelayEnvelope =
            RelayEnvelope(
                type = FrameType.TYPING,
                id = "typing-${author.nodeId}",
                senderId = author.nodeId,
                sentAt = 7L,
                recipientId = recipientId,
                payload = WireCodec.encodePayload(TypingContent(groupId = groupId)),
            )

        fun keyReq(
            author: Party,
            wanted: List<String>,
        ): RelayEnvelope =
            RelayEnvelope(
                type = FrameType.KEY_REQ,
                id = "keyreq-${author.nodeId}",
                senderId = author.nodeId,
                sentAt = 7L,
                payload = WireCodec.encodePayload(KeyReqContent(nodeIds = wanted)),
            )

        /** A blob request is unsigned by design; build the wire directly with an empty signature. */
        fun blobReqWire(
            fromNodeId: String,
            hash: String,
        ): Pair<WireEnvelope, RelayEnvelope> {
            val env =
                RelayEnvelope(
                    type = FrameType.BLOB_REQ,
                    id = "blobreq-$hash",
                    senderId = fromNodeId,
                    sentAt = 7L,
                    payload = WireCodec.encodePayload(BlobReqContent(hash = hash)),
                )
            return WireEnvelope(relay = false, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(env)) to env
        }

        fun group(
            id: String,
            members: List<String>,
            createdBy: String,
            name: String? = null,
            photoHash: String? = null,
            photoUpdatedAt: Long? = null,
        ) = GroupInfo(
            id = id,
            name = name,
            members = members,
            createdBy = createdBy,
            photoHash = photoHash,
            photoUpdatedAt = photoUpdatedAt,
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

    @Test
    fun profileWithADifferentKeyForAPinnedSenderIsRefusedAndKeepsVerified() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            val other = party() // stands in for the (infeasible-to-generate) colliding key

            // alice is already pinned AND user-verified. A real key-for-nodeId collision can't be
            // generated in a test (128-bit), so we plant a pin whose key differs from the incoming
            // self-certifying profile — reaching the exact "pinned key changed" branch in handleProfile.
            rig.peerMap[alice.nodeId] =
                PeerEntity(nodeId = alice.nodeId, pubKey = other.bundle.encoded, verified = true, updatedAt = 1L)

            // alice's genuine, self-certifying profile arrives (its key derives to her nodeId, newer sentAt).
            val profile = rig.profile(alice)
            rig.pipeline.onDeliver(alice.sign(profile), profile, alice.nodeId)

            // Refused: the first-pinned key and its verified badge are untouched, and the drop is counted.
            assertEquals("pin must not change", other.bundle.encoded, rig.peerMap[alice.nodeId]?.pubKey)
            assertTrue("verified badge must survive", rig.peerMap[alice.nodeId]?.verified == true)
            assertEquals(1L, rig.drops(DropReason.PIN_CHANGE_REFUSED))
        }

    @Test
    fun reProfileWithTheSameKeyIsNotRefusedAndKeepsVerified() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            // Pinned with her real key, user-verified, an older name.
            rig.peerMap[alice.nodeId] =
                PeerEntity(nodeId = alice.nodeId, pubKey = alice.bundle.encoded, verified = true, name = "Old", updatedAt = 1L)

            // A normal profile update (same key, newer sentAt, name "Peer") must pass the guard untouched.
            val profile = rig.profile(alice)
            rig.pipeline.onDeliver(alice.sign(profile), profile, alice.nodeId)

            assertEquals(0L, rig.drops(DropReason.PIN_CHANGE_REFUSED))
            assertTrue("verified must survive a same-key update", rig.peerMap[alice.nodeId]?.verified == true)
            assertEquals("name should update", "Peer", rig.peerMap[alice.nodeId]?.name)
            assertEquals(alice.bundle.encoded, rig.peerMap[alice.nodeId]?.pubKey)
        }

    // --- Tier 1: metadata handlers + dispatchByType arms ---

    @Test
    fun reactionFromAnAllowedSenderIsApplied() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val env = rig.reaction(alice, messageId = "m1", emoji = "👍")

            rig.pipeline.onDeliver(alice.sign(env), env, alice.nodeId)

            coVerify {
                rig.reactions.apply(
                    match {
                        it.messageId == "m1" && it.reactorNodeId == alice.nodeId && it.emoji == "👍" && it.updatedAt == 7L
                    },
                )
            }
        }

    @Test
    fun reactionFromABlockedSenderIsDropped() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            rig.settings.blocked.value = setOf(alice.nodeId)
            val env = rig.reaction(alice, messageId = "m1", emoji = "👍")

            rig.pipeline.onDeliver(alice.sign(env), env, alice.nodeId)

            coVerify(exactly = 0) { rig.reactions.apply(any()) }
        }

    @Test
    fun receiptFromTheDmRecipientMarksTheMessageReceived() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            // The acked DM's recipient is alice — she's allowed to ack it.
            rig.msgMap["m1"] = MessageEntity(id = "m1", senderId = rig.self.nodeId, recipientId = alice.nodeId, body = "", sentAt = 1L)
            val env = rig.receipt(alice, ackId = "m1")

            rig.pipeline.onDeliver(alice.sign(env), env, alice.nodeId)

            coVerify { rig.messages.markReceived("m1") }
        }

    @Test
    fun forgedReceiptFromANonRecipientDoesNotMarkReceived() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            // The acked DM is addressed to bob, not alice — alice can't spoof its delivery tick.
            rig.msgMap["m2"] = MessageEntity(id = "m2", senderId = rig.self.nodeId, recipientId = "bob", body = "", sentAt = 1L)
            val env = rig.receipt(alice, ackId = "m2")

            rig.pipeline.onDeliver(alice.sign(env), env, alice.nodeId)

            coVerify(exactly = 0) { rig.messages.markReceived("m2") }
        }

    @Test
    fun typingCueForADmScopesToTheSenderThread() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val env = rig.typing(alice, recipientId = rig.self.nodeId)

            rig.pipeline.onDeliver(alice.sign(env), env, alice.nodeId)

            assertTrue(
                rig.typingTracker.typing.value[alice.nodeId]
                    ?.contains(alice.nodeId) == true,
            )
        }

    @Test
    fun typingCueForTheBroadcastRoomScopesToNearby() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val env = rig.typing(alice)

            rig.pipeline.onDeliver(alice.sign(env), env, alice.nodeId)

            assertTrue(
                rig.typingTracker.typing.value[Conversations.NEARBY]
                    ?.contains(alice.nodeId) == true,
            )
        }

    @Test
    fun typingCueForAnUnknownGroupIsIgnored() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val env = rig.typing(alice, groupId = "g-unknown")

            rig.pipeline.onDeliver(alice.sign(env), env, alice.nodeId)

            assertTrue(
                "no typing recorded for a group we don't know",
                rig.typingTracker.typing.value
                    .isEmpty(),
            )
        }

    @Test
    fun ourOwnTypingCueLoopingBackIsIgnored() =
        runTest {
            val rig = Rig(backgroundScope)
            val env = rig.typing(rig.self)

            rig.pipeline.onDeliver(rig.self.sign(env), env, rig.self.nodeId)

            assertTrue(
                rig.typingTracker.typing.value
                    .isEmpty(),
            )
        }

    @Test
    fun anUnknownFrameTypeIsNeitherDeliveredNorThrows() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val env =
                RelayEnvelope(type = "mystery", id = "u1", senderId = alice.nodeId, sentAt = 7L, payload = ByteArray(0))

            rig.pipeline.onDeliver(alice.sign(env), env, alice.nodeId) // must not throw

            assertTrue(rig.msgMap.isEmpty())
        }

    @Test
    fun aBlobRequestIsDispatchedToBlobExchange() =
        runTest {
            val rig = Rig(backgroundScope)
            val requester = party()
            val (wire, env) = rig.blobReqWire(requester.nodeId, hash = "bhash")

            rig.pipeline.onDeliver(wire, env, requester.nodeId)

            // onRequest consults the store to decide whether to serve — proves the arm ran (blobreq is unsigned).
            coVerify { rig.blobStore.fileFor("bhash") }
        }

    @Test
    fun aKeyRequestIsDispatchedToKeyExchange() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            // A neighbor must exist for the recursive key request to actually go out (and bump the metric).
            rig.transport.connect(FakeLoopTransport("neighbor-node"))
            val env = rig.keyReq(alice, wanted = listOf("wanted-node"))

            rig.pipeline.onDeliver(alice.sign(env), env, alice.nodeId)

            // For an unheld key, onRequest records the wanter and fires an outbound key request to neighbors.
            assertTrue(rig.metrics.snapshot().keyRequestsSent >= 1)
        }

    // --- Tier 2: group path (reconcileGroup runs inside the rig's real in-memory Room transaction) ---

    private fun Rig.seedGroup(
        id: String,
        members: List<String>,
        createdBy: String,
        name: String = "",
        nameUpdatedAt: Long = 0L,
        left: Boolean = false,
    ) {
        groupMap[id] =
            GroupEntity(
                groupId = id,
                name = name,
                members = GroupMembersStore.encode(members),
                createdBy = createdBy,
                createdAt = 1L,
                nameUpdatedAt = nameUpdatedAt,
                left = left,
            )
    }

    @Test
    fun groupLeaveFromAMemberRecordsTheDeparture() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val env = rig.groupLeave(alice, groupId = "g-1")

            rig.pipeline.onDeliver(alice.sign(env), env, alice.nodeId)

            coVerify { rig.groups.recordDeparture("g-1", alice.nodeId, 7L) }
        }

    @Test
    fun groupLeaveFromABlockedSenderIsIgnored() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            rig.settings.blocked.value = setOf(alice.nodeId)
            val env = rig.groupLeave(alice, groupId = "g-1")

            rig.pipeline.onDeliver(alice.sign(env), env, alice.nodeId)

            coVerify(exactly = 0) { rig.groups.recordDeparture(any(), any(), any()) }
        }

    @Test
    fun aNewGroupUpdateCreatesTheGroupFromItsRoster() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val group = rig.group("g-2", members = listOf(rig.self.nodeId, alice.nodeId), createdBy = alice.nodeId, name = "Trip")

            rig.deliver(alice, rig.groupUpdate(alice, group, sentAt = 7L))

            val stored = rig.groupMap["g-2"]
            assertNotNull(stored)
            assertEquals("Trip", stored?.name)
            assertEquals(7L, stored?.nameUpdatedAt)
            assertEquals(alice.nodeId, stored?.createdBy)
            assertEquals(listOf(rig.self.nodeId, alice.nodeId), GroupMembersStore.decode(stored!!.members))
        }

    @Test
    fun aGroupNameIsLastWriterWinsOnSentAt() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            rig.seedGroup(
                "g-3",
                members = listOf(rig.self.nodeId, alice.nodeId),
                createdBy = alice.nodeId,
                name = "Old",
                nameUpdatedAt = 10L,
            )
            val members = listOf(rig.self.nodeId, alice.nodeId)

            // An older rename (sentAt 5 < clock 10) must not win.
            val stale = rig.group("g-3", members = members, createdBy = alice.nodeId, name = "Stale")
            rig.deliver(alice, rig.groupUpdate(alice, stale, sentAt = 5L))
            assertEquals("Old", rig.groupMap["g-3"]?.name)

            // A newer rename (sentAt 20 >= 10) wins.
            val newer = rig.group("g-3", members = members, createdBy = alice.nodeId, name = "Newer")
            rig.deliver(alice, rig.groupUpdate(alice, newer, sentAt = 20L))
            assertEquals("Newer", rig.groupMap["g-3"]?.name)
            assertEquals(20L, rig.groupMap["g-3"]?.nameUpdatedAt)
        }

    @Test
    fun aGroupFrameFromANonMemberIsRefused() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            // Roster does not include us → refused, nothing stored.
            val group = rig.group("g-4", members = listOf(alice.nodeId), createdBy = alice.nodeId, name = "Secret")

            rig.deliver(alice, rig.groupUpdate(alice, group))

            assertNull(rig.groupMap["g-4"])
        }

    @Test
    fun aFrameForALeftGroupDoesNotResurrectIt() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            rig.seedGroup("g-5", members = listOf(rig.self.nodeId, alice.nodeId), createdBy = alice.nodeId, name = "Gone", left = true)
            val group = rig.group("g-5", members = listOf(rig.self.nodeId, alice.nodeId), createdBy = alice.nodeId, name = "Back")

            rig.deliver(alice, rig.groupUpdate(alice, group, sentAt = 9L))

            assertTrue("a left group stays left", rig.groupMap["g-5"]?.left == true)
            assertEquals("Gone", rig.groupMap["g-5"]?.name)
        }

    @Test
    fun aNewGroupWhoseCreatorIsBlockedIsRefused() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            rig.settings.blocked.value = setOf("evil")
            // A non-blocked member (alice) relays the first frame for a group created by a blocked node.
            val group = rig.group("g-6", members = listOf(rig.self.nodeId, "evil"), createdBy = "evil")

            rig.deliver(alice, rig.groupUpdate(alice, group))

            assertNull(rig.groupMap["g-6"])
        }

    @Test
    fun aGroupPhotoWithLocalBytesIsAdoptedImmediately() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            coEvery { rig.blobStore.has("photoA") } returns true
            val group =
                rig.group(
                    "g-7",
                    members = listOf(rig.self.nodeId, alice.nodeId),
                    createdBy = alice.nodeId,
                    photoHash = "photoA",
                    photoUpdatedAt = 100L,
                )

            rig.deliver(alice, rig.groupUpdate(alice, group))

            assertEquals("photoA", rig.groupMap["g-7"]?.photoHash)
            assertEquals(100L, rig.groupMap["g-7"]?.photoUpdatedAt)
        }

    @Test
    fun aGroupPhotoWithoutLocalBytesIsPulledThenAdoptedOnArrival() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            // blobStore.has("photoB") defaults false → keep the old (null) photo but arm a pull.
            val group =
                rig.group(
                    "g-8",
                    members = listOf(rig.self.nodeId, alice.nodeId),
                    createdBy = alice.nodeId,
                    photoHash = "photoB",
                    photoUpdatedAt = 100L,
                )

            rig.deliver(alice, rig.groupUpdate(alice, group))
            assertNull("photo not shown until its bytes arrive", rig.groupMap["g-8"]?.photoHash)
            assertEquals(100L, rig.groupMap["g-8"]?.photoUpdatedAt)

            // The pulled blob lands → adopt it onto the group now that the clock still matches.
            rig.pipeline.onObtained("photoB")
            assertEquals("photoB", rig.groupMap["g-8"]?.photoHash)
        }

    @Test
    fun anExplicitGroupPhotoIsNotAdopted() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            coEvery { rig.blobs.isImageFlagged("photoC") } returns true
            val group =
                rig.group(
                    "g-9",
                    members = listOf(rig.self.nodeId, alice.nodeId),
                    createdBy = alice.nodeId,
                    photoHash = "photoC",
                    photoUpdatedAt = 100L,
                )

            rig.deliver(alice, rig.groupUpdate(alice, group))
            rig.pipeline.onObtained("photoC")

            assertNull("an explicit photo is dropped, not adopted", rig.groupMap["g-9"]?.photoHash)
            coVerify { rig.blobs.deleteIfUnreferenced("photoC") }
        }

    // --- Tier 3: custody gate, notifications, avatar path, attachment screening, decrypt version ---

    @Test
    fun canCarryRefusesABlockedSender() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            rig.settings.blocked.value = setOf(alice.nodeId)
            val env = rig.broadcastChat(alice, id = "c1", body = "hi")

            assertFalse(rig.pipeline.canCarry(alice.sign(env), env))
        }

    @Test
    fun canCarryRefusesADmWithNoEncryptedPayload() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            // A DM (recipientId set) whose ChatContent carries no enc envelope must not be custodied in the clear.
            val env =
                RelayEnvelope(
                    type = FrameType.CHAT,
                    id = "c2",
                    senderId = alice.nodeId,
                    sentAt = 5L,
                    recipientId = "bob",
                    payload = WireCodec.encodePayload(ChatContent(body = "plaintext dm")),
                )

            assertFalse(rig.pipeline.canCarry(alice.sign(env), env))
        }

    @Test
    fun canCarryRefusesAnUnauthenticatableFrame() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party() // deliberately NOT pinned → no key to verify with
            val env = rig.broadcastChat(alice, id = "c3", body = "hi")

            assertFalse(rig.pipeline.canCarry(alice.sign(env), env))
            assertEquals(1L, rig.drops(DropReason.CARRY_REFUSED))
        }

    @Test
    fun canCarryAcceptsAValidBroadcastFrame() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val env = rig.broadcastChat(alice, id = "c4", body = "hi")

            assertTrue(rig.pipeline.canCarry(alice.sign(env), env))
        }

    @Test
    fun onCarriedFrameWantsAnAbsentAttachmentBlob() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            val env = rig.attachmentChat(alice, id = "a1", attachmentHash = "att1")

            rig.pipeline.onCarriedFrame(env)

            // Passed the have-it gate (has=false) and the budget gate (bytes=0) → pulled.
            coVerify { rig.blobStore.has("att1") }
            coVerify { rig.blobs.carrierOnlyBlobBytes() }
        }

    @Test
    fun onCarriedFrameSkipsABlobWeAlreadyHold() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            coEvery { rig.blobStore.has("att2") } returns true
            val env = rig.attachmentChat(alice, id = "a2", attachmentHash = "att2")

            rig.pipeline.onCarriedFrame(env)

            // Short-circuited at has()==true, so the budget was never consulted.
            coVerify(exactly = 0) { rig.blobs.carrierOnlyBlobBytes() }
        }

    @Test
    fun onCarriedFrameSkipsAChatWithNoAttachment() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            val env = rig.broadcastChat(alice, id = "a3", body = "text only")

            rig.pipeline.onCarriedFrame(env)

            coVerify(exactly = 0) { rig.blobStore.has(any()) }
        }

    @Test
    fun onCarriedFrameSkipsWhenOverTheCarrierBudget() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            coEvery { rig.blobs.carrierOnlyBlobBytes() } returns Long.MAX_VALUE
            val env = rig.attachmentChat(alice, id = "a4", attachmentHash = "att4")

            rig.pipeline.onCarriedFrame(env)

            // Reached the budget gate (proving has()==false was passed) and bailed there.
            coVerify { rig.blobs.carrierOnlyBlobBytes() }
        }

    @Test
    fun aBroadcastChatNotifiesOnTheNearbyChannel() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)

            rig.deliver(alice, rig.broadcastChat(alice, id = "b1", body = "hello room"))

            assertEquals("hello room", rig.msgMap["b1"]?.body)
            coVerify { rig.notifier.notify(any(), any(), any(), any(), any()) }
        }

    @Test
    fun aMessageThatMentionsUsNotifiesOnTheMentionsChannel() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)

            rig.deliver(alice, rig.mentionChat(alice, id = "b2", body = "hey @me", mentionOf = rig.self))

            coVerify { rig.notifier.notifyMention(any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { rig.notifier.notify(any(), any(), any(), any(), any()) }
        }

    @Test
    fun aGroupMessageIsDeliveredAndNotifiedViaItsGroupConversation() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val group = rig.group("g-11", members = listOf(rig.self.nodeId, alice.nodeId), createdBy = alice.nodeId, name = "Crew")

            rig.deliver(alice, rig.groupChat(alice, group, id = "gm1", body = "team hi"))

            assertEquals("team hi", rig.msgMap["gm1"]?.body)
            coVerify { rig.notifier.notify(any(), any(), any(), any(), any()) }
        }

    @Test
    fun aRelayedPeerAvatarIsPulledThenAdoptedOnArrival() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            // A profile self-certifies its key, so no pin needed. blobStore.has("av1") is false → pull, don't adopt yet.
            rig.deliver(alice, rig.profile(alice, avatarHash = "av1"))
            assertNull("avatar not shown until its bytes arrive", rig.peerMap[alice.nodeId]?.avatarHash)

            rig.pipeline.onObtained("av1")

            assertEquals("av1", rig.peerMap[alice.nodeId]?.avatarHash)
        }

    @Test
    fun anExplicitRelayedAvatarIsNotAdopted() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            coEvery { rig.blobs.isImageFlagged("av2") } returns true

            rig.deliver(alice, rig.profile(alice, avatarHash = "av2"))
            rig.pipeline.onObtained("av2")

            assertNull(rig.peerMap[alice.nodeId]?.avatarHash)
            coVerify { rig.blobs.deleteIfUnreferenced("av2") }
        }

    @Test
    fun clearingAnAvatarReclaimsTheOldBlob() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            // Already pinned with an avatar; a newer profile with no avatar clears it and reclaims the blob.
            rig.peerMap[alice.nodeId] =
                PeerEntity(nodeId = alice.nodeId, pubKey = alice.bundle.encoded, avatarHash = "old", updatedAt = 1L)

            rig.deliver(alice, rig.profile(alice, avatarHash = null, sentAt = 6L))

            assertNull(rig.peerMap[alice.nodeId]?.avatarHash)
            coVerify { rig.blobs.deleteIfUnreferenced("old") }
        }

    @Test
    fun onAvatarReceivedAdoptsAValidPushedAvatar() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            val bytes = byteArrayOf(1, 2, 3, 4, 5)
            val hash = sha256Hex(bytes)
            val file = File.createTempFile("avatar", ".bin").apply { writeBytes(bytes) }

            rig.pipeline.onAvatarReceived(alice.nodeId, hash, "image/jpeg", file.absolutePath)

            assertEquals(hash, rig.peerMap[alice.nodeId]?.avatarHash)
            coVerify { rig.blobs.insert(hash, "image/jpeg", any()) }
            assertFalse("the staging file is deleted after ingest", file.exists())
        }

    @Test
    fun onAvatarReceivedRejectsBytesThatDoNotMatchTheClaimedHash() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            val bytes = byteArrayOf(9, 9, 9)
            val wrongHash = "0".repeat(64) // valid format, but not sha256(bytes)
            val file = File.createTempFile("avatar", ".bin").apply { writeBytes(bytes) }

            rig.pipeline.onAvatarReceived(alice.nodeId, wrongHash, "image/jpeg", file.absolutePath)

            assertNull(rig.peerMap[alice.nodeId])
            coVerify(exactly = 0) { rig.blobs.insert(any(), any(), any()) }
            assertFalse("a spoofed avatar's staging file is deleted", file.exists())
        }

    @Test
    fun onAvatarReceivedDropsAnExplicitPushedAvatar() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            val bytes = byteArrayOf(7, 7, 7, 7)
            val hash = sha256Hex(bytes)
            coEvery { rig.blobs.isImageFlagged(hash) } returns true
            val file = File.createTempFile("avatar", ".bin").apply { writeBytes(bytes) }

            rig.pipeline.onAvatarReceived(alice.nodeId, hash, "image/jpeg", file.absolutePath)

            assertNull("an explicit avatar is not adopted onto the peer", rig.peerMap[alice.nodeId]?.avatarHash)
            coVerify { rig.blobs.deleteIfUnreferenced(hash) }
        }

    @Test
    fun screenObtainedAttachmentIsANoOpWithoutAKey() =
        runTest {
            val rig = Rig(backgroundScope)
            // No attachment key held (the harness default) → screening is skipped, no bitmap decode.
            rig.pipeline.onObtained("somehash")

            coVerify { rig.messages.attachmentKeyForHash("somehash") }
            coVerify(exactly = 0) { rig.blobs.screenImage(any(), any()) }
        }

    @Test
    fun screenEncryptedAttachmentReturnsWhenTheBlobIsAbsent() =
        runTest {
            val rig = Rig(backgroundScope)
            // We hold the key but not the ciphertext blob yet → nothing to screen.
            coEvery { rig.messages.attachmentKeyForHash("h2") } returns "a2V5"

            rig.pipeline.onObtained("h2")

            coVerify { rig.blobs.bytes("h2") }
            coVerify(exactly = 0) { rig.blobs.screenImage(any(), any()) }
        }

    @Test
    fun aDmWithAnUnsupportedEnvelopeVersionIsDropped() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val env = rig.dmWithEnvVersion(alice, rig.self, id = "dmv", v = EncEnvelope.MAX_SUPPORTED_VERSION + 1)

            rig.deliver(alice, env)

            assertEquals(1L, rig.drops(DropReason.UNKNOWN_ENVELOPE_VERSION))
            assertFalse(rig.msgMap.containsKey("dmv"))
        }

    private companion object {
        const val HYBRID_TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM_RAW"
    }
}
