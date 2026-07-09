package app.getknit.knit.mesh

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.MeshBlobStore
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.message.MentionStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.TinkInit
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.moderation.ImageScreeningService
import app.getknit.knit.moderation.ScopedTextModerator
import app.getknit.knit.moderation.TextVerdict
import app.getknit.knit.notifications.Notifier
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Drives the **real** [MeshManager] outbound send-and-originate workflow — `sendChat` and the origination
 * choke it funnels through (`originateSigned` → `sign` → custody capture → fast-fanout) — with real Tink
 * keypairs / [MessageCrypto], a recording [MeshTransport], a real in-memory [ForwardStore], and mockk
 * stand-ins for the Room-backed repos. It pins the highest-risk branch surface of the class the mesh is
 * built around: the moderation block-on-send gate, the broadcast-plaintext vs DM/group-E2E-encrypt split,
 * the `pendingKey` deferral when a recipient's key isn't known yet, and the attachment re-seal.
 *
 * Runs under Robolectric so `android.util.Log` / `android.util.Base64` (used by the send + crypto path)
 * resolve on the JVM, mirroring [InboundPipelineTest]. Timestamps are pinned via the injected `clock`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class MeshManagerTest {
    /** A device identity: its cipher (private keys) + its published bundle; nodeId derives from the bundle. */
    private class Party(
        val crypto: MessageCrypto,
        val bundle: PublicKeyBundle,
    ) {
        val nodeId: String = NodeId.fromPublicKeyBundle(bundle.encoded)
    }

    private fun party(): Party {
        TinkInit.ensure()
        val hybrid = KeysetHandle.generateNew(KeyTemplates.get(HYBRID_TEMPLATE))
        val sig = KeysetHandle.generateNew(KeyTemplates.get("ED25519_RAW"))
        return Party(MessageCrypto(hybrid, sig), PublicKeyBundle.fromPrivate(hybrid, sig))
    }

    /** A [MeshTransport] that records every frame the manager originates (both flood + fast-fanout copies). */
    private class RecordingTransport : MeshTransport {
        val sent = mutableListOf<Pair<WireEnvelope, Peer?>>()
        override val neighbors = MutableStateFlow<Set<Peer>>(emptySet()).asStateFlow()
        override val health = MutableStateFlow(TransportHealth.Healthy).asStateFlow()
        override val inbound = MutableSharedFlow<InboundFrame>().asSharedFlow()
        override val incomingFiles = emptyFlow<ReceivedFile>()

        override fun start() = Unit

        override fun stop() = Unit

        override fun heal() = Unit

        override suspend fun send(
            wire: WireEnvelope,
            to: Peer?,
        ) {
            sent += wire to to
        }

        override suspend fun sendFile(
            file: File,
            to: Peer,
            meta: FileMeta,
        ): Boolean = true

        override suspend fun sendDigest(
            to: Peer,
            ids: List<String>,
        ) = Unit
    }

    /** Minimal in-memory [ForwardStore] so a test can assert what the send path captured for custody. */
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

    /** The manager under test, wired with real crypto + a recording transport + real custody + mocked repos. */
    private inner class Rig(
        scope: CoroutineScope,
    ) {
        val me = party()
        val bob = party()
        val transport = RecordingTransport()
        val forwardStore = FakeForwardStore()
        val messages = mockk<MessageRepository>(relaxed = true)
        val peers = mockk<PeerRepository>(relaxed = true)
        val blobs = mockk<BlobRepository>(relaxed = true)
        val groups = mockk<GroupRepository>(relaxed = true)
        val reactions = mockk<ReactionRepository>(relaxed = true)
        val settings = mockk<SettingsStore>(relaxed = true)
        val imageScreening = mockk<ImageScreeningService>(relaxed = true)
        val blobStore = mockk<MeshBlobStore>(relaxed = true)
        val notifier = mockk<Notifier>(relaxed = true)
        val textModeration = mockk<ScopedTextModerator>(relaxed = true)
        val identity = mockk<Identity>(relaxed = true)

        // A real (empty) in-memory DB as the manager's ctor arg; the send path never touches it (only the
        // inbound pipeline's reconcileGroup does), so it just satisfies construction. Mirrors InboundPipelineTest.
        val db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KnitDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        val saved = mutableListOf<MessageEntity>()
        val now = 1_700_000_000_000L
        val manager: MeshManager

        init {
            coEvery { identity.nodeId() } returns me.nodeId
            coEvery { textModeration.classify(any(), any()) } returns TextVerdict.ALLOWED
            coEvery { messages.save(any()) } answers { saved += firstArg<MessageEntity>() }
            coEvery { peers.find(any()) } returns null // default: no recipient key is known
            coEvery { blobs.bytes(any()) } returns null
            manager =
                MeshManager(
                    transport = transport,
                    messages = messages,
                    groups = groups,
                    reactions = reactions,
                    peers = peers,
                    identity = identity,
                    settings = settings,
                    blobs = blobs,
                    imageScreening = imageScreening,
                    blobStore = blobStore,
                    forwardStore = forwardStore,
                    notifier = notifier,
                    textModeration = textModeration,
                    messageCrypto = me.crypto,
                    scope = scope,
                    metrics = MeshMetrics(),
                    db = db,
                    clock = { now },
                )
        }

        /** Pins [p]'s real key under its nodeId, as the profile handler would once its profile arrives. */
        fun pin(p: Party) {
            coEvery { peers.find(p.nodeId) } returns PeerEntity(nodeId = p.nodeId, pubKey = p.bundle.encoded, updatedAt = 1L)
        }

        /** The distinct CHAT routing envelopes the manager originated (collapsing the flood + fast-fanout copies). */
        fun sentChatFrames(): List<RelayEnvelope> =
            transport.sent
                .mapNotNull { WireCodec.decodeEnvelope(it.first.signed) }
                .filter { it.type == FrameType.CHAT }
                .distinctBy { it.id }
    }

    // --- moderation gate ---

    @Test
    fun flaggedTextIsBlockedOnSendAndNeitherStoredNorFlooded() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            coEvery { rig.textModeration.classify(any(), any()) } returns
                TextVerdict(allowed = false, category = TextVerdict.Category.TOXICITY)

            val ok = rig.manager.sendChat("something abusive")
            advanceUntilIdle()

            assertFalse("block-on-send: a flagged message is refused", ok)
            assertTrue("nothing is persisted locally", rig.saved.isEmpty())
            assertTrue("and nothing hits the wire", rig.transport.sent.isEmpty())
            coVerify(exactly = 0) { rig.messages.save(any()) }
        }

    // --- broadcast room (plaintext) ---

    @Test
    fun broadcastMessageIsStoredPlaintextFloodedAndCustodyCaptured() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)

            val ok = rig.manager.sendChat("gm mesh")
            advanceUntilIdle()

            assertTrue(ok)
            val saved = rig.saved.single()
            assertEquals("gm mesh", saved.body)
            assertNull("the broadcast room has no addressed recipient", saved.recipientId)
            assertFalse("plaintext room message is never pending-key", saved.pendingKey)
            assertEquals(rig.now, saved.sentAt)

            val frame = rig.sentChatFrames().single()
            assertEquals(rig.me.nodeId, frame.senderId)
            assertNull(frame.recipientId)
            assertEquals("the flooded frame shares the stored copy's id + timestamp", saved.id, frame.id)
            assertEquals(rig.now, frame.sentAt)

            val content = WireCodec.decodePayload<ChatContent>(frame.payload)!!
            assertEquals("the room is plaintext — body rides in the clear", "gm mesh", content.body)
            assertNull("and is not encrypted", content.enc)
            assertTrue("the message is captured for store-and-forward custody", rig.forwardStore.has(frame.id))
        }

    // --- DM: end-to-end encrypted when the key is known ---

    @Test
    fun directMessageIsEncryptedToRecipientAndOnlyTheyCanDecryptIt() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pin(rig.bob)

            val ok = rig.manager.sendChat("meet at 8", recipientId = rig.bob.nodeId)
            advanceUntilIdle()

            assertTrue(ok)
            val saved = rig.saved.single()
            assertEquals("the sender keeps a local plaintext copy", "meet at 8", saved.body)
            assertEquals(rig.bob.nodeId, saved.recipientId)
            assertFalse("the key is known, so it is not deferred", saved.pendingKey)

            val frame = rig.sentChatFrames().single()
            val content = WireCodec.decodePayload<ChatContent>(frame.payload)!!
            assertEquals("no plaintext body leaks on the wire", "", content.body)
            assertNotNull("the encrypted envelope rides the frame", content.enc)

            val header = MessageCrypto.header(frame.id, rig.me.nodeId, frame.sentAt, rig.bob.nodeId)
            val opened = rig.bob.crypto.open(content.enc!!, header, rig.bob.nodeId)
            assertNotNull("the addressed recipient can decrypt", opened)
            assertEquals("meet at 8", opened!!.body)
        }

    // --- DM: deferred (pendingKey) when the recipient's key is not yet known ---

    @Test
    fun directMessageWithoutARecipientKeyIsParkedPendingKeyAndNotFlooded() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            // peers.find(bob) defaults to null → no published key → nothing can decrypt it yet.

            val ok = rig.manager.sendChat("ping", recipientId = rig.bob.nodeId)
            advanceUntilIdle()

            assertTrue("still succeeds: the local copy is stored", ok)
            val saved = rig.saved.single()
            assertTrue("and marked pending until the key arrives", saved.pendingKey)
            assertEquals("ping", saved.body)
            assertTrue("nothing is flooded — no peer could read it", rig.sentChatFrames().isEmpty())
        }

    // --- group: encrypt to members with keys, excluding self and keyless members ---

    @Test
    fun groupMessageEncryptsOnlyToMembersWithKeysAndCarriesTheRoster() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            val carol = party() // a member WITH a published key
            rig.pin(carol)
            // "dave" has no key (peers.find defaults to null) → excluded from the wrapped-key set.
            val members = listOf(rig.me.nodeId, carol.nodeId, "dave")
            val group = GroupInfo(id = "g-1", members = members, createdBy = rig.me.nodeId)

            val ok = rig.manager.sendChat("standup in 5", group = group)
            advanceUntilIdle()

            assertTrue(ok)
            val frame = rig.sentChatFrames().single()
            assertEquals("the roster rides on the frame so members can rebuild the group", members, frame.group?.members)

            val content = WireCodec.decodePayload<ChatContent>(frame.payload)!!
            val enc = content.enc!!
            assertEquals(
                "one wrapped key: the sender excludes itself and the keyless member",
                listOf(carol.nodeId),
                enc.keys.map { it.to },
            )
            val header = MessageCrypto.header(frame.id, rig.me.nodeId, frame.sentAt, group.id)
            assertEquals("the keyed member can decrypt", "standup in 5", carol.crypto.open(enc, header, carol.nodeId)?.body)
        }

    // --- attachment: re-seal under a ciphertext hash, key stays sealed ---

    @Test
    fun attachmentIsReSealedUnderItsCiphertextHashWithTheKeyKeptInsideTheSealedContent() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pin(rig.bob)
            val plainHash = "plain-hash"
            coEvery { rig.blobs.bytes(plainHash) } returns "raw-image-bytes".toByteArray()

            val ok =
                rig.manager.sendChat(
                    "look",
                    attachment = AttachmentStore.Ingested(hash = plainHash, mime = "image/jpeg"),
                    recipientId = rig.bob.nodeId,
                )
            advanceUntilIdle()

            assertTrue(ok)
            val frame = rig.sentChatFrames().single()
            val content = WireCodec.decodePayload<ChatContent>(frame.payload)!!
            val ctHash = content.attachmentHash!!

            assertNotEquals("the frame is re-addressed by the ciphertext hash, not the plaintext one", plainHash, ctHash)
            assertEquals("the mime is exposed in the clear so a blind carrier can custody the blob", "image/jpeg", content.attachmentMime)
            coVerify { rig.blobs.insert(ctHash, "image/jpeg", any()) } // ciphertext stored under its hash
            coVerify { rig.blobs.deleteIfUnreferenced(plainHash) } // now-unreferenced plaintext dropped

            // The decryption key is sealed inside the encrypted content (never in the cleartext frame).
            val header = MessageCrypto.header(frame.id, rig.me.nodeId, frame.sentAt, rig.bob.nodeId)
            val opened = rig.bob.crypto.open(content.enc!!, header, rig.bob.nodeId)!!
            assertEquals("the sealed content references the same ciphertext blob", ctHash, opened.attachmentHash)
            assertNotNull("and carries the AES key the recipient needs", opened.attachmentKey)
        }

    // --- reply + mentions ride the frame and are persisted ---

    @Test
    fun replyAndMentionsAreStoredAndRideOnTheBroadcastFrame() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            val mentions = listOf(Mention(nodeId = "u1", name = "Alice"))
            val reply = ReplyRef(messageId = "m0", authorId = "u1", author = "Alice", snippet = "hi")

            val ok = rig.manager.sendChat("@Alice yo", mentions = mentions, replyTo = reply)
            advanceUntilIdle()

            assertTrue(ok)
            val saved = rig.saved.single()
            assertEquals("mentions are persisted on the stored row", MentionStore.encode(mentions), saved.mentions)
            assertEquals("the quoted reply is denormalized onto the row", "m0", saved.replyToId)

            val content = WireCodec.decodePayload<ChatContent>(rig.sentChatFrames().single().payload)!!
            assertEquals(mentions, content.mentions)
            assertEquals(reply, content.replyTo)
        }

    private companion object {
        const val HYBRID_TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM_RAW"
    }
}
