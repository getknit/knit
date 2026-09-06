package app.getknit.knit.mesh

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import app.getknit.knit.TextLimits
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.MeshBlobStore
import app.getknit.knit.data.MessageReceiptRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.VoiceAudio
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.PeerRename
import app.getknit.knit.data.message.isStatusNotice
import app.getknit.knit.data.message.receivedPlane
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.ratchet.GroupRatchetRepository
import app.getknit.knit.data.ratchet.RatchetRepository
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.data.receipt.MessageReceiptEntity
import app.getknit.knit.data.settings.InboundSettings
import app.getknit.knit.identity.Alias
import app.getknit.knit.identity.IdentitySource
import app.getknit.knit.identity.NodeId
import app.getknit.knit.identity.PeerLabels
import app.getknit.knit.mesh.crypto.AttachmentCrypto
import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.TinkInit
import app.getknit.knit.mesh.crypto.b64
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetEngine
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetSessions
import app.getknit.knit.mesh.crypto.ratchet.RatchetCrypto
import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine
import app.getknit.knit.mesh.crypto.ratchet.RatchetSessions
import app.getknit.knit.mesh.crypto.sealBytes
import app.getknit.knit.mesh.protocol.BlobReqContent
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.GroupKeyPayload
import app.getknit.knit.mesh.protocol.GroupLeaveContent
import app.getknit.knit.mesh.protocol.GroupRatchetHeader
import app.getknit.knit.mesh.protocol.GroupSeed
import app.getknit.knit.mesh.protocol.KeyReqContent
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.PrekeyInfo
import app.getknit.knit.mesh.protocol.ProfileContent
import app.getknit.knit.mesh.protocol.ProfilePayload
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.RatchetHeader
import app.getknit.knit.mesh.protocol.RatchetInit
import app.getknit.knit.mesh.protocol.ReactionContent
import app.getknit.knit.mesh.protocol.ReactionPayload
import app.getknit.knit.mesh.protocol.ReceiptContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.TypingContent
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.spool.ScopeSync
import app.getknit.knit.moderation.ImageScreeningService
import app.getknit.knit.notifications.NotifMessage
import app.getknit.knit.notifications.Notifier
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.hybrid.HpkePrivateKey
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
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
        /** The raw X25519 identity scalar (the extraction IdentityKeyStore.dhIdentityPrivate performs). */
        val dhPriv: ByteArray,
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
        val dhPriv =
            (hybrid.primary.key as HpkePrivateKey).privateKeyBytes.toByteArray(InsecureSecretKeyAccess.get())
        return Party(MessageCrypto(hybrid, sig), PublicKeyBundle.fromPrivate(hybrid, sig), dhPriv)
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
        val accepted = MutableStateFlow(emptySet<String>())
        override val acceptedConversations get() = accepted

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

        // (messageId, acker) -> the stored per-recipient delivery row, so a test can assert WHO acked.
        val receiptMap = ConcurrentHashMap<Pair<String, String>, MessageReceiptEntity>()
        val receipts = mockk<MessageReceiptRepository>(relaxed = true)
        val groups = mockk<GroupRepository>(relaxed = true)
        val reactions = mockk<ReactionRepository>(relaxed = true)
        val blobs = mockk<BlobRepository>(relaxed = true)
        val imageScreening = mockk<ImageScreeningService>(relaxed = true)
        val blobStore = mockk<MeshBlobStore>(relaxed = true)

        // A real in-memory DB purely as the transaction runner for reconcileGroup's db.withWriteTransaction; the repos
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

        // AckSync's custody-escalation hooks, read lazily so a test can arm them (defaults keep escalation
        // off — every pre-existing test behaves exactly as before). ackNowMs is the tick clock a test
        // advances past the batch debounce.
        var ackNowMs = 0L
        var canSealTick: suspend (String) -> Boolean = { false }
        var originateTickHook: suspend (String, List<String>) -> Boolean = { _, _ -> false }
        val ackSync =
            AckSync(
                transport,
                selfId = { self.nodeId },
                signRaw = self.crypto::signRaw,
                metrics = metrics,
                now = { ackNowMs },
                canSeal = { canSealTick(it) },
                originateTick = { authorId, ids -> originateTickHook(authorId, ids) },
            )
        val pendingInbound = PendingInbound(metrics = metrics)
        val typingTracker = TypingTracker(scope)

        // Our side of the v2 epoch ratchet: a signed-prekey pair the fake "identity" serves, and the
        // session service over the REAL Room-backed store (the in-memory DB above) — v2 tests exercise
        // the actual applyOpen/commit SQL, not a fake.
        val selfSpk = RatchetCrypto.generateKeyPair()
        val ratchetMutex = Mutex()
        val ratchetStore = RatchetRepository(db.ratchetDao(), clock = { 5L })
        val ratchet =
            RatchetSessions(
                store = ratchetStore,
                dhIdentityPriv = { self.dhPriv },
                spkPrivFor = { id -> if (id == SPK_ID) selfSpk.priv else null },
                mutex = ratchetMutex,
            )

        // The group-ratchet facade over the REAL Room-backed store, sharing the DM facade's mutex exactly
        // as production wiring does (seed adoption runs inside a DM commit under that one lock).
        val groupRatchetStore = GroupRatchetRepository(db.groupRatchetDao())
        val groupRatchet = GroupRatchetSessions(store = groupRatchetStore, mutex = ratchetMutex)
        val originated = mutableListOf<RelayEnvelope>()
        val dmFlushes = mutableListOf<Pair<String, List<String>>>()
        val dmAcks = DmAckCoalescer(now = { nowMs }, flush = { a, ids -> dmFlushes += a to ids })

        /**
         * The pipeline's clock. Fixed by default so every existing test behaves exactly as before; a test
         * that needs time to pass — the per-peer replacement floors are the only such case — advances it.
         */
        var nowMs = 42L
        val flushed = mutableListOf<String>()
        val resealed = mutableListOf<String>()
        val redistributed = mutableListOf<Pair<String, String>>()
        val groupKeysFlushed = mutableListOf<Pair<String, Boolean>>()
        val custodyReplays = mutableListOf<Pair<String?, String?>>()
        var failClassify = false

        /** When armed, records the `isRoom` scope each inbound classification ran under. */
        var classifyScopes: MutableList<Boolean>? = null

        /** `(name, body)` for every post the pipeline handed to the foreign public channel. */
        val pipeline: InboundPipeline

        init {
            coEvery { peers.find(any()) } answers { peerMap[firstArg()] }
            coEvery { peers.upsert(any()) } answers { peerMap[firstArg<PeerEntity>().nodeId] = firstArg() }
            // The real query orders claimants by updatedAt DESC and takes the first; the fake does the same.
            coEvery { peers.findByLoraNode(any()) } answers {
                val node = firstArg<Long>()
                peerMap.values.filter { it.loraNode == node }.maxByOrNull { it.updatedAt }
            }
            // isAccepted now reads the batch "verified" signal; derive it from the same fake map the repo backs.
            coEvery { peers.verifiedNodeIds() } answers { peerMap.values.filter { it.verified }.map { it.nodeId } }
            coEvery { peers.labelIndex() } answers { PeerLabels.index(peerMap.values.map { it.nodeId to it.name }) }
            coEvery { messages.exists(any()) } answers { msgMap.containsKey(firstArg<String>()) }
            coEvery { messages.save(any()) } answers { msgMap[firstArg<MessageEntity>().id] = firstArg() }
            coEvery { messages.saveIfAbsent(any()) } answers {
                msgMap.putIfAbsent(firstArg<MessageEntity>().id, firstArg()) == null
            }
            coEvery { messages.recipientOf(any()) } answers { msgMap[firstArg<String>()]?.recipientId }
            coEvery { messages.conversationOf(any()) } answers { msgMap[firstArg<String>()]?.conversationId }
            // The real repository writes the tick and the acker row in one transaction; the fake keeps that
            // pairing so the existing markReceived verifications still describe what the pipeline did.
            coEvery { receipts.record(any(), any(), any(), any()) } coAnswers {
                val id = firstArg<String>()
                val acker = secondArg<String?>()
                val via = thirdArg<DeliveryPlane>()
                messages.markReceived(id, via)
                if (acker != null) receiptMap.putIfAbsent(id to acker, MessageReceiptEntity(id, acker, arg(3), via.code))
                Unit
            }
            coEvery { groups.find(any()) } answers { groupMap[firstArg()] }
            coEvery { groups.upsert(any()) } answers { groupMap[firstArg<GroupEntity>().groupId] = firstArg() }
            // isAccepted's group branch reads the thread's senders; back it with the fake message map.
            // Status notices are excluded exactly as the real query is: a notice's senderId is the
            // event's subject, not an author, so it must not make anyone count as having spoken here.
            coEvery { messages.sendersIn(any()) } answers {
                msgMap.values
                    .filter { it.conversationId == firstArg<String>() && !it.isStatusNotice }
                    .map { it.senderId }
                    .distinct()
            }
            // The gate on writing a peer status notice — an ordinary message must already exist in the
            // thread. Mirrors the real query, status rows included in what does NOT satisfy it.
            coEvery { messages.hasMessagesIn(any()) } answers {
                msgMap.values.any { it.conversationId == firstArg<String>() && !it.isStatusNotice }
            }
            // Realistic "nothing held" defaults: relaxed mockk otherwise returns "" for a String? and a bare
            // Object (not a byte[]) for a ByteArray?, which would crash the attachment-screen path in onObtained.
            coEvery { messages.attachmentKeyForHash(any()) } returns null
            coEvery { blobs.bytes(any()) } returns null
            pipeline =
                InboundPipeline(
                    transport = transport,
                    messages = messages,
                    receipts = receipts,
                    groups = groups,
                    reactions = reactions,
                    peers = peers,
                    blobs = blobs,
                    imageScreening = imageScreening,
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
                    ratchet = ratchet,
                    groupRatchet = groupRatchet,
                    clock = { nowMs },
                    originate = { originated += it },
                    dmAcks = dmAcks,
                    flushPending = { flushed += it },
                    classifyText = { _, _, isRoom ->
                        if (failClassify) error("moderation boom")
                        classifyScopes?.add(isRoom)
                        false
                    },
                    resealUnacked = { resealed += it },
                    redistributeGroupKey = { groupId, requester -> redistributed += groupId to requester },
                    flushGroupKeys = { member, force -> groupKeysFlushed += member to force },
                    replayGroupCustody = { groupId, senderId -> custodyReplays += groupId to senderId },
                )
        }

        /** Pins [p]'s real key under its nodeId, as [handleProfile] would after receiving its profile. */
        fun pin(p: Party) {
            peerMap[p.nodeId] = PeerEntity(nodeId = p.nodeId, pubKey = p.bundle.encoded, updatedAt = 1L)
        }

        /** Pins [p] as ratchet-capable (CAP_RATCHET + a prekey), as [handleProfile] stores a v2 profile. */
        fun pinRatchetCapable(
            p: Party,
            prekeyPub: ByteArray,
        ) {
            peerMap[p.nodeId] =
                PeerEntity(
                    nodeId = p.nodeId,
                    pubKey = p.bundle.encoded,
                    capabilities = Protocol.LOCAL_CAPABILITIES,
                    prekeyId = 2,
                    prekeyPub = b64(prekeyPub),
                    prekeyProfileAt = 1L,
                    updatedAt = 1L,
                )
        }

        /**
         * Signs [env] with [author]'s key and drives it through the pipeline (the common onDeliver call).
         * [from] names the source it arrived from — a neighbouring node by default, or a spool-tagged
         * source (`ScopeSync.SPOOL_SOURCE_PREFIX`) to stand in for a pull off the Internet plane; [kind]
         * the radio it came over (the composite's stamp), LoRa standing in for a frame off the board.
         */
        suspend fun deliver(
            author: Party,
            env: RelayEnvelope,
            from: String = author.nodeId,
            kind: TransportKind = TransportKind.Other,
        ) = pipeline.onDeliver(author.sign(env), env, from, kind)

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
            name: String = "Peer",
            openToChat: Boolean = false,
            loraNode: Long? = null,
            loraKey: String? = null,
        ): RelayEnvelope =
            RelayEnvelope(
                type = FrameType.PROFILE,
                id = "profile-${author.nodeId}-$sentAt",
                senderId = author.nodeId,
                sentAt = sentAt,
                payload =
                    WireCodec.encodePayload(
                        ProfileContent(
                            name = name,
                            status = "",
                            pubKey = author.bundle.encoded,
                            avatarHash = avatarHash,
                            openToChat = openToChat,
                            loraNode = loraNode,
                            loraKey = loraKey,
                        ),
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

        /** Builds a self-describing roster; [id] defaults to the real derivation over the founding set
         *  (members ∪ departed), which `vetRoster` verifies on first sight. Pass an explicit id only to
         *  model a forged or legacy-truncated frame. */
        fun group(
            members: List<String>,
            createdBy: String,
            id: String? = null,
            name: String? = null,
            photoHash: String? = null,
            photoUpdatedAt: Long? = null,
            departed: List<String>? = null,
        ) = GroupInfo(
            id = id ?: Conversations.groupIdFor(members + departed.orEmpty()),
            name = name,
            members = members,
            createdBy = createdBy,
            photoHash = photoHash,
            photoUpdatedAt = photoUpdatedAt,
            departed = departed,
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
            val ack = rig.originated.filter { it.type == FrameType.RECEIPT }
            assertTrue("a receipt should be originated", ack.isNotEmpty())
            // Custody derives the frame-global expiry from sentAt, so an unset 0 is refused dead-on-arrival
            // at every store and the receipt is never carried (work item #16).
            assertEquals("the receipt must be stamped with the wall clock", 42L, ack.single().sentAt)
            // A DM for us is delivered, not custodied.
            assertFalse(rig.forwardStore.has("dm1"))
        }

    @Test
    fun aSealedAttachmentsTypeIsReadFromInsideTheSealNotTheCleartextFrame() =
        runTest {
            // The receiving half of ADR 035. The frame names the ciphertext hash and NOTHING else about the
            // attachment; the type lives in the sealed MessageContent, and plaintextContent is what carries
            // it out to the row. Get this wrong and every inbound voice note renders as a broken photo —
            // the bubble forks on the row's mime (VoiceAudio.isVoice), not on the bytes.
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val id = "dm-voice"
            val header = MessageCrypto.header(id, alice.nodeId, 5L, rig.self.nodeId)
            val sealed =
                MessageContent(
                    body = "",
                    attachmentHash = "ct-hash",
                    attachmentMime = VoiceAudio.MIME,
                    attachmentKey = "a2V5",
                )
            val enc = alice.crypto.seal(sealed.encode(), header, mapOf(rig.self.nodeId to rig.self.bundle))!!
            val env =
                RelayEnvelope(
                    type = FrameType.CHAT,
                    id = id,
                    senderId = alice.nodeId,
                    sentAt = 5L,
                    recipientId = rig.self.nodeId,
                    // The cleartext hint a carrier sees: the hash alone, no mime.
                    payload = WireCodec.encodePayload(ChatContent(enc = enc, attachmentHash = "ct-hash")),
                )

            rig.pipeline.onDeliver(alice.sign(env), env, alice.nodeId)

            val row = rig.msgMap.getValue(id)
            assertEquals("the row is addressed by the cleartext hash", "ct-hash", row.attachmentHash)
            assertEquals("and typed from inside the seal, the only place the mime rides", VoiceAudio.MIME, row.attachmentMime)
            assertNull(
                "nothing on the frame said so — a carrier relaying this never learned it was audio",
                WireCodec.decodePayload<ChatContent>(env.payload)!!.attachmentMime,
            )
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

    // --- Status notices (MessageEntity.kind) -------------------------------------------------------
    // Every one of these is derived on-device from a change both ends can already see, so none of them
    // costs a wire field. What the tests pin is the two things that makes fragile: that a notice fires on
    // a real CHANGE rather than on every re-assertion of the same value, and that it stays out of threads
    // it has no business creating.

    @Test
    fun aRenamedContactGetsOneNoticeCarryingBothNames() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.peerMap[alice.nodeId] =
                PeerEntity(nodeId = alice.nodeId, pubKey = alice.bundle.encoded, name = "Old", updatedAt = 1L)
            // The DM thread must already hold a real message, or the notice is suppressed (see below).
            rig.msgMap["m1"] = MessageEntity(id = "m1", senderId = alice.nodeId, conversationId = alice.nodeId, body = "hi", sentAt = 1L)

            val profile = rig.profile(alice, name = "New", sentAt = 7L)
            rig.pipeline.onDeliver(alice.sign(profile), profile, alice.nodeId)

            val notice = rig.msgMap.values.single { it.kind == MessageEntity.KIND_PEER_RENAMED }
            assertEquals("the notice belongs in the DM thread with them", alice.nodeId, notice.conversationId)
            assertEquals("its subject is the peer, not an author", alice.nodeId, notice.senderId)
            // Both names are stored, so the line stays a record of this one step after a later rename.
            assertEquals(PeerRename(from = "Old", to = "New"), PeerRename.decode(notice.body))
            assertEquals("New", rig.peerMap[alice.nodeId]?.name)
        }

    @Test
    fun aSecondRenameReadsAsAProgressionRatherThanTwoLinesEndingInTheNewestName() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.peerMap[alice.nodeId] =
                PeerEntity(nodeId = alice.nodeId, pubKey = alice.bundle.encoded, name = "I am a songwriter", updatedAt = 1L)
            rig.msgMap["m1"] = MessageEntity(id = "m1", senderId = alice.nodeId, conversationId = alice.nodeId, body = "hi", sentAt = 1L)

            // The bug this pins: with only the old name stored, each line's second half was the live label,
            // so once "Kai" arrived both lines read "… is now Kai" and the first looked like a duplicate.
            val first = rig.profile(alice, name = "Bushybramblepatch", sentAt = 7L)
            rig.pipeline.onDeliver(alice.sign(first), first, alice.nodeId)
            val second = rig.profile(alice, name = "Kai", sentAt = 9L)
            rig.pipeline.onDeliver(alice.sign(second), second, alice.nodeId)

            val renames =
                rig.msgMap.values
                    .filter { it.kind == MessageEntity.KIND_PEER_RENAMED }
                    .sortedBy { it.sentAt }
                    .map { PeerRename.decode(it.body) }
            assertEquals(
                listOf(
                    PeerRename(from = "I am a songwriter", to = "Bushybramblepatch"),
                    PeerRename(from = "Bushybramblepatch", to = "Kai"),
                ),
                renames,
            )
        }

    @Test
    fun aRepublishedProfileAtTheSameVersionDoesNotStackASecondNotice() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.peerMap[alice.nodeId] =
                PeerEntity(nodeId = alice.nodeId, pubKey = alice.bundle.encoded, name = "Old", updatedAt = 1L)
            rig.msgMap["m1"] = MessageEntity(id = "m1", senderId = alice.nodeId, conversationId = alice.nodeId, body = "hi", sentAt = 1L)

            // The same publish arriving twice — the ordinary case, since a profile is re-flooded on every
            // peer-epoch and re-served from custody. The deterministic row id is what collapses them.
            val profile = rig.profile(alice, name = "New", sentAt = 7L)
            rig.pipeline.onDeliver(alice.sign(profile), profile, alice.nodeId)
            rig.pipeline.onDeliver(alice.sign(profile), profile, alice.nodeId)

            assertEquals(1, rig.msgMap.values.count { it.kind == MessageEntity.KIND_PEER_RENAMED })
        }

    @Test
    fun anUnchangedProfileProducesNoNotice() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.peerMap[alice.nodeId] =
                PeerEntity(nodeId = alice.nodeId, pubKey = alice.bundle.encoded, name = "Peer", updatedAt = 1L)
            rig.msgMap["m1"] = MessageEntity(id = "m1", senderId = alice.nodeId, conversationId = alice.nodeId, body = "hi", sentAt = 1L)

            // A profile republishes every 12h whether or not anything changed. Winning the last-writer-wins
            // race is not the same question as being different, and only the second earns a line.
            val profile = rig.profile(alice, name = "Peer", sentAt = 99L)
            rig.pipeline.onDeliver(alice.sign(profile), profile, alice.nodeId)

            assertTrue(rig.msgMap.values.none { it.isStatusNotice })
        }

    @Test
    fun aFirstProfileIsNotARenameAndProducesNoNotice() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            // No stored row at all: this is first contact. There is no old name to have changed from, and
            // pinning a stranger's key must not conjure a thread out of nothing.
            val profile = rig.profile(alice, name = "Peer")
            rig.pipeline.onDeliver(alice.sign(profile), profile, alice.nodeId)

            assertTrue(rig.msgMap.values.none { it.isStatusNotice })
        }

    @Test
    fun aStrangersRenameNeverConjuresAThread() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            // Known and renamed, but we have never exchanged a message. A `profile` frame floods the whole
            // mesh, so without this gate the chat list would slowly fill with people we have never spoken to.
            rig.peerMap[alice.nodeId] =
                PeerEntity(nodeId = alice.nodeId, pubKey = alice.bundle.encoded, name = "Old", updatedAt = 1L)

            val profile = rig.profile(alice, name = "New", sentAt = 7L)
            rig.pipeline.onDeliver(alice.sign(profile), profile, alice.nodeId)

            assertEquals("New", rig.peerMap[alice.nodeId]?.name)
            assertTrue("the rename applied, but silently", rig.msgMap.isEmpty())
        }

    @Test
    fun aNewAvatarIsAnnouncedOnceEvenBeforeItsBytesLand() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.peerMap[alice.nodeId] =
                PeerEntity(nodeId = alice.nodeId, pubKey = alice.bundle.encoded, name = "Peer", updatedAt = 1L)
            rig.msgMap["m1"] = MessageEntity(id = "m1", senderId = alice.nodeId, conversationId = alice.nodeId, body = "hi", sentAt = 1L)

            // The hash isn't adopted until its blob arrives, so "stored != advertised" stays true on every
            // re-serve. Keying the notice on the profile version rather than the hash is what bounds it to one.
            val profile = rig.profile(alice, avatarHash = "avatar-hash", sentAt = 7L)
            rig.pipeline.onDeliver(alice.sign(profile), profile, alice.nodeId)
            rig.pipeline.onDeliver(alice.sign(profile), profile, alice.nodeId)

            val notice = rig.msgMap.values.single { it.kind == MessageEntity.KIND_PEER_AVATAR }
            assertEquals(alice.nodeId, notice.conversationId)
            assertEquals("", notice.body)
        }

    @Test
    fun aRefusedPinChangeIsSurfacedInTheThread() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            val other = party()
            rig.peerMap[alice.nodeId] =
                PeerEntity(nodeId = alice.nodeId, pubKey = other.bundle.encoded, verified = true, updatedAt = 1L)
            rig.msgMap["m1"] = MessageEntity(id = "m1", senderId = alice.nodeId, conversationId = alice.nodeId, body = "hi", sentAt = 1L)

            val profile = rig.profile(alice)
            rig.pipeline.onDeliver(alice.sign(profile), profile, alice.nodeId)

            // Counted AND visible: this is the one profile event a user could act on, so a metric only a
            // maintainer reads is the wrong place to leave it.
            assertEquals(1L, rig.drops(DropReason.PIN_CHANGE_REFUSED))
            assertEquals(1, rig.msgMap.values.count { it.kind == MessageEntity.KIND_KEY_PIN_REFUSED })
        }

    @Test
    fun firstSightOfAGroupIsCreatedNotRenamed() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val g = rig.group(members = listOf(rig.self.nodeId, alice.nodeId), createdBy = alice.nodeId, name = "Book Club")

            rig.deliver(alice, rig.groupUpdate(alice, g, sentAt = 7L))

            // A group arriving with a name and a photo has not been renamed or re-photographed — it has
            // been created, and it gets exactly one line saying so. (It is also the only join-shaped
            // notice there is: a group's id is the hash of its founding roster, so nobody ever joins one.)
            val notice = rig.msgMap.values.single { it.isStatusNotice }
            assertEquals(MessageEntity.KIND_GROUP_CREATED, notice.kind)
            assertEquals(g.id, notice.conversationId)
            assertEquals("its subject is the creator", alice.nodeId, notice.senderId)
        }

    @Test
    fun aGroupRenameIsAnnouncedOnceAndItsReAssertionIsNot() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val members = listOf(rig.self.nodeId, alice.nodeId)
            rig.seedGroup("g-r", members = members, createdBy = alice.nodeId, name = "Book Club", nameUpdatedAt = 5L)

            val renamed = rig.group(members = members, createdBy = alice.nodeId, id = "g-r", name = "Reading Group")
            rig.deliver(alice, rig.groupUpdate(alice, renamed, sentAt = 8L))
            // GroupInfo rides on every frame, so the new name is re-asserted constantly. Winning the
            // last-writer-wins race is not the same question as being different.
            rig.deliver(alice, rig.groupUpdate(alice, renamed, sentAt = 9L))

            val notice = rig.msgMap.values.single { it.kind == MessageEntity.KIND_GROUP_RENAMED }
            // The NEW name is stored, the mirror image of a peer rename: a group's old name is gone from
            // live state, so carrying the new one keeps the line readable after a LATER rename too.
            assertEquals("Reading Group", notice.body)
            assertEquals("its subject is whoever renamed it", alice.nodeId, notice.senderId)
            assertEquals("g-r", notice.conversationId)
        }

    @Test
    fun aGroupPhotoChangeIsAnnouncedOnceAndItsReAssertionIsNot() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val members = listOf(rig.self.nodeId, alice.nodeId)
            rig.seedGroup("g-p", members = members, createdBy = alice.nodeId)

            val withPhoto =
                rig.group(members = members, createdBy = alice.nodeId, id = "g-p", photoHash = "ph", photoUpdatedAt = 8L)
            rig.deliver(alice, rig.groupUpdate(alice, withPhoto, sentAt = 8L))
            // Announced when the change is DECIDED, not when the bytes land — "they changed the photo" is
            // true either way and the image fills in behind it. So the re-assertion must not add a second.
            rig.deliver(alice, rig.groupUpdate(alice, withPhoto, sentAt = 9L))

            assertEquals(1, rig.msgMap.values.count { it.kind == MessageEntity.KIND_GROUP_PHOTO })
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
    fun anOversizedCleartextReactionIsRefusedAndCountedButStillCustodied() =
        runTest {
            // The open emoji set's one receiver rule: past TextLimits.REACTION nothing is applied — never
            // truncated, never read as a retraction — while custody (and so relay) is untouched, because a
            // size gate is a delivery gate and canCarry never reads the emoji.
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val env = rig.reaction(alice, messageId = "m1", emoji = "👍".repeat(17))

            rig.pipeline.onDeliver(alice.sign(env), env, alice.nodeId)

            coVerify(exactly = 0) { rig.reactions.apply(any()) }
            assertEquals(1L, rig.drops(DropReason.REACTION_REFUSED))
            assertTrue("still custodied for onward carry", rig.forwardStore.has(env.id))
        }

    @Test
    fun aBlankCleartextReactionIsRefusedRatherThanReadAsARetraction() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val env = rig.reaction(alice, messageId = "m1", emoji = " ")

            rig.pipeline.onDeliver(alice.sign(env), env, alice.nodeId)

            coVerify(exactly = 0) { rig.reactions.apply(any()) }
            assertEquals(1L, rig.drops(DropReason.REACTION_REFUSED))
        }

    @Test
    fun theLongestRgiSequenceIsAppliedVerbatim() =
        runTest {
            // Length-only on purpose: a 10-code-point ZWJ sequence — or any emoji Unicode adds later — must
            // never be dropped by a build that predates it.
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val env = rig.reaction(alice, messageId = "m1", emoji = LONGEST_RGI)

            rig.pipeline.onDeliver(alice.sign(env), env, alice.nodeId)

            coVerify(exactly = 1) { rig.reactions.apply(ReactionEntity("m1", alice.nodeId, LONGEST_RGI, 7L)) }
            assertEquals(0L, rig.drops(DropReason.REACTION_REFUSED))
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

            // Delivered from a neighbouring node, so the tick records the radio plane.
            coVerify { rig.messages.markReceived("m1", DeliveryPlane.Nearby) }
        }

    @Test
    fun receiptPulledOffASpoolMarksTheTickInternetDelivered() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            rig.msgMap["m1"] = MessageEntity(id = "m1", senderId = rig.self.nodeId, recipientId = alice.nodeId, body = "", sentAt = 1L)
            val env = rig.receipt(alice, ackId = "m1")

            // The same receipt, bridged in by ScopeSync instead of a radio: same delivery, different plane.
            rig.deliver(alice, env, from = ScopeSync.SPOOL_SOURCE_PREFIX + "spool.example")

            coVerify { rig.messages.markReceived("m1", DeliveryPlane.Internet) }
        }

    @Test
    fun anIncomingDmRecordsThePlaneItArrivedOn() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)

            // An inbound message needs no receipt to know how it travelled — the frame IS the evidence.
            rig.deliver(
                alice,
                rig.dmChat(alice, rig.self, id = "in-relay", body = "from far away"),
                from = ScopeSync.SPOOL_SOURCE_PREFIX + "spool.example",
            )
            rig.deliver(alice, rig.dmChat(alice, rig.self, id = "in-radio", body = "from next door"))

            assertEquals(DeliveryPlane.Internet, rig.msgMap["in-relay"]?.receivedPlane)
            assertEquals(DeliveryPlane.Nearby, rig.msgMap["in-radio"]?.receivedPlane)
        }

    @Test
    fun aDmHeardOverLoraRecordsTheLoraPlane() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)

            rig.deliver(alice, rig.dmChat(alice, rig.self, id = "in-lora", body = "from the hills"), kind = TransportKind.LoRa)
            rig.deliver(alice, rig.dmChat(alice, rig.self, id = "in-ble", body = "from next door"), kind = TransportKind.Bluetooth)

            assertEquals(DeliveryPlane.LoRa, rig.msgMap["in-lora"]?.receivedPlane)
            // The phone radios still collapse to Nearby on purpose — the UI has nothing different to say about them.
            assertEquals(DeliveryPlane.Nearby, rig.msgMap["in-ble"]?.receivedPlane)
        }

    @Test
    fun aReceiptHeardOverLoraMarksTheTickLoraDelivered() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            rig.msgMap["m1"] = MessageEntity(id = "m1", senderId = rig.self.nodeId, recipientId = alice.nodeId, body = "", sentAt = 1L)

            rig.deliver(alice, rig.receipt(alice, ackId = "m1"), kind = TransportKind.LoRa)

            coVerify { rig.messages.markReceived("m1", DeliveryPlane.LoRa) }
        }

    @Test
    fun aParkedLoraFrameKeepsItsPlaneThroughTheReplay() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party() // unpinned: the chat races ahead of her beacon, as it does over LoRa

            val chat = rig.broadcastChat(alice, id = "c-lora", body = "over the hills")
            rig.deliver(alice, chat, kind = TransportKind.LoRa)
            assertFalse(rig.msgMap.containsKey("c-lora"))

            // The beacon lands (over whichever plane) and the parked frame replays — as the LoRa arrival it was.
            rig.deliver(alice, rig.profile(alice))

            assertEquals("over the hills", rig.msgMap["c-lora"]?.body)
            assertEquals(DeliveryPlane.LoRa, rig.msgMap["c-lora"]?.receivedPlane)
        }

    @Test
    fun aReServedRoomPostKeepsThePlaneItFirstArrivedOn() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val post = rig.broadcastChat(alice, id = "room-1", body = "hello valley")

            rig.deliver(alice, post, kind = TransportKind.LoRa)
            // The same signed frame, re-served over Bluetooth custody once the router's SeenSet lapsed: the
            // plaintext room path runs deliverChat again, and the row must keep the plane it first arrived on.
            rig.deliver(alice, post, kind = TransportKind.Bluetooth)

            assertEquals(DeliveryPlane.LoRa, rig.msgMap["room-1"]?.receivedPlane)
        }

    @Test
    fun anInboundMessageIsStampedWithOurClockNotTheSenders() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            rig.nowMs = 9_000L
            val env = rig.dmChat(alice, rig.self, id = "dm-arrived", body = "hi dm")

            rig.deliver(alice, env)

            val row = rig.msgMap.getValue("dm-arrived")
            // sentAt is Alice's clock off the frame; arrivedAt is ours. The gap between them is the
            // store-and-forward latency, which is the whole reason the column exists.
            assertEquals(5L, row.sentAt)
            assertEquals(9_000L, row.arrivedAt)
        }

    @Test
    fun aReServedRoomPostKeepsTheArrivalTimeOfItsFirstCrossing() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val post = rig.broadcastChat(alice, id = "room-arrived", body = "hello valley")

            rig.nowMs = 1_000L
            rig.deliver(alice, post)
            // The plaintext room path deliberately skips the pre-decrypt exists-gate and runs deliverChat on
            // every re-serve, so first-write-wins rests entirely on saveIfAbsent — hours later, same frame.
            rig.nowMs = 5_000_000L
            rig.deliver(alice, post)

            assertEquals(1_000L, rig.msgMap.getValue("room-arrived").arrivedAt)
        }

    @Test
    fun ourOwnRoomPostLoopingBackAfterRetentionIsNeverStampedAsArrived() =
        runTest {
            val rig = Rig(backgroundScope)
            // Our own post, re-served by a peer after the retention sweep took our row: deliverChat writes it
            // afresh, but we did not receive this message — we sent it — so it must carry no arrival time.
            val post = rig.broadcastChat(rig.self, id = "mine-arrived", body = "my own post")

            rig.deliver(rig.self, post)

            val row = rig.msgMap.getValue("mine-arrived")
            assertEquals(rig.self.nodeId, row.senderId)
            assertNull(row.arrivedAt)
        }

    @Test
    fun ourOwnRoomPostReServedByAPeerNeverResetsItsTick() =
        runTest {
            val rig = Rig(backgroundScope)
            val post = rig.broadcastChat(rig.self, id = "mine-1", body = "my own post")
            rig.msgMap["mine-1"] =
                MessageEntity(
                    id = "mine-1",
                    senderId = rig.self.nodeId,
                    body = "my own post",
                    sentAt = 5L,
                    received = true,
                    receivedVia = DeliveryPlane.Nearby.code,
                )

            // Our own frame looping back after our SeenSet lapsed (a peer re-served it): verifyInbound admits
            // it so custody re-carries it, but the row we wrote when we sent it must stay exactly as it is.
            rig.deliver(rig.self, post, kind = TransportKind.LoRa)

            val row = rig.msgMap.getValue("mine-1")
            assertTrue(row.received)
            assertEquals(DeliveryPlane.Nearby, row.receivedPlane)
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

            coVerify(exactly = 0) { rig.messages.markReceived("m2", any()) }
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
            val group = rig.group(members = listOf(rig.self.nodeId, alice.nodeId), createdBy = alice.nodeId, name = "Trip")

            rig.deliver(alice, rig.groupUpdate(alice, group, sentAt = 7L))

            val stored = rig.groupMap[group.id]
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
            val stale = rig.group(members = members, createdBy = alice.nodeId, id = "g-3", name = "Stale")
            rig.deliver(alice, rig.groupUpdate(alice, stale, sentAt = 5L))
            assertEquals("Old", rig.groupMap["g-3"]?.name)

            // A newer rename (sentAt 20 >= 10) wins.
            val newer = rig.group(members = members, createdBy = alice.nodeId, id = "g-3", name = "Newer")
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
            // Roster does not include us → not our group: refused silently (we are merely a carrier —
            // this is every relayed foreign-group frame, so it must NOT pollute the drops dashboard).
            val group = rig.group(members = listOf(alice.nodeId), createdBy = alice.nodeId, name = "Secret")

            rig.deliver(alice, rig.groupUpdate(alice, group))

            assertNull(rig.groupMap[group.id])
            assertEquals(0L, rig.drops(DropReason.GROUP_ROSTER_REFUSED))
        }

    @Test
    fun aFrameForALeftGroupDoesNotResurrectIt() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            rig.seedGroup("g-5", members = listOf(rig.self.nodeId, alice.nodeId), createdBy = alice.nodeId, name = "Gone", left = true)
            val group = rig.group(members = listOf(rig.self.nodeId, alice.nodeId), createdBy = alice.nodeId, id = "g-5", name = "Back")

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
            val group = rig.group(members = listOf(rig.self.nodeId, "evil", alice.nodeId), createdBy = "evil")

            rig.deliver(alice, rig.groupUpdate(alice, group))

            assertNull(rig.groupMap[group.id])
        }

    // --- Roster integrity (vetRoster): the pin, the derivation check, and the sender gate ---

    @Test
    fun aFirstSightGroupWithANonDerivedIdIsRefused() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            // Claims an id the roster does not derive to — an id forgery, refused before any pin exists.
            val group = rig.group(members = listOf(rig.self.nodeId, alice.nodeId), createdBy = alice.nodeId, id = "g-forged")

            rig.deliver(alice, rig.groupUpdate(alice, group))

            assertNull(rig.groupMap["g-forged"])
            assertEquals(1L, rig.drops(DropReason.GROUP_ROSTER_REFUSED))
        }

    @Test
    fun aPinnedRosterRefusesASmuggledMember() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val members = listOf(rig.self.nodeId, alice.nodeId)
            val id = Conversations.groupIdFor(members)
            rig.seedGroup(id, members = members, createdBy = alice.nodeId)
            // A member re-broadcasts the roster with a sock puppet appended, keeping the pinned id: no set
            // containing the puppet derives to that id, so the frame is refused outright.
            val forged = rig.group(members = members + "puppet-node", createdBy = alice.nodeId, id = id)

            rig.deliver(alice, rig.groupChat(alice, forged, id = "gm-smuggle", body = "hi"))

            assertEquals(members, GroupMembersStore.decode(rig.groupMap[id]!!.members))
            assertFalse("a frame with a smuggled member must not deliver", rig.msgMap.containsKey("gm-smuggle"))
            assertEquals(1L, rig.drops(DropReason.GROUP_ROSTER_REFUSED))
        }

    @Test
    fun aShrunkRosterIsAcceptedButNeverMutatesThePin() =
        runTest {
            // A frame whose roster omits bob (a "kick" attempt, or just a stale view) still delivers — but
            // the stored roster keeps bob: membership shrinks only via his own signed groupleave.
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val members = listOf(rig.self.nodeId, alice.nodeId, "bob-node")
            val id = Conversations.groupIdFor(members)
            rig.seedGroup(id, members = members, createdBy = alice.nodeId)
            val shrunk = rig.group(members = listOf(rig.self.nodeId, alice.nodeId), createdBy = alice.nodeId, id = id)

            rig.deliver(alice, rig.groupChat(alice, shrunk, id = "gm-shrunk", body = "hi"))

            assertEquals("hi", rig.msgMap["gm-shrunk"]?.body)
            assertEquals(members, GroupMembersStore.decode(rig.groupMap[id]!!.members))
        }

    @Test
    fun aFrameOmittingUsStillDeliversOncePinned() =
        runTest {
            // Anti-starvation: once pinned, membership is decided by the pin, not by whichever roster a
            // frame happens to carry — a member can't cut us out by flooding rosters without us.
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val members = listOf(rig.self.nodeId, alice.nodeId, "bob-node")
            val id = Conversations.groupIdFor(members)
            rig.seedGroup(id, members = members, createdBy = alice.nodeId)
            val cutOut = rig.group(members = listOf(alice.nodeId, "bob-node"), createdBy = alice.nodeId, id = id)

            rig.deliver(alice, rig.groupChat(alice, cutOut, id = "gm-cut", body = "still ours"))

            assertEquals("still ours", rig.msgMap["gm-cut"]?.body)
            assertEquals(members, GroupMembersStore.decode(rig.groupMap[id]!!.members))
        }

    @Test
    fun aNonFoundingSenderIsRefused() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            val carol = party()
            rig.pin(alice)
            rig.pin(carol)
            val members = listOf(rig.self.nodeId, alice.nodeId)
            val id = Conversations.groupIdFor(members)
            rig.seedGroup(id, members = members, createdBy = alice.nodeId)
            // carol (never a founding member) sends a chat carrying the true roster.
            val group = rig.group(members = members, createdBy = alice.nodeId)

            rig.deliver(carol, rig.groupChat(carol, group, id = "gm-outsider", body = "let me in"))

            assertFalse(rig.msgMap.containsKey("gm-outsider"))
            assertEquals(1L, rig.drops(DropReason.GROUP_ROSTER_REFUSED))
        }

    @Test
    fun aFirstSightWithDepartedTombstonesVerifiesTheFoundingSet() =
        runTest {
            // The id derives over members ∪ departed, so a first sight after a departure verifies. The
            // departed list is arithmetic only: bob stays in our effective roster until his own signed
            // groupleave arrives (adopting another member's word for a departure would be a kick-forgery).
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val group =
                rig.group(
                    members = listOf(rig.self.nodeId, alice.nodeId),
                    createdBy = alice.nodeId,
                    departed = listOf("bob-node"),
                )

            rig.deliver(alice, rig.groupUpdate(alice, group))

            val stored = rig.groupMap[group.id]
            assertNotNull(stored)
            assertTrue(
                "bob stays effective until his signed leave arrives",
                "bob-node" in GroupMembersStore.decode(stored!!.members),
            )
            assertEquals(emptyList<String>(), GroupMembersStore.decode(stored.departed))
        }

    @Test
    fun aVerifiedSupersetRepairsATruncatedPin() =
        runTest {
            // A pin first made from a legacy departed-less frame (founding truncated to the then-effective
            // roster) heals when a full, self-verifying roster arrives.
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val founding = listOf(rig.self.nodeId, alice.nodeId, "bob-node")
            val id = Conversations.groupIdFor(founding)
            rig.seedGroup(id, members = listOf(rig.self.nodeId, alice.nodeId), createdBy = alice.nodeId)
            val full = rig.group(members = founding, createdBy = alice.nodeId)

            rig.deliver(alice, rig.groupUpdate(alice, full))

            assertEquals(founding, GroupMembersStore.decode(rig.groupMap[id]!!.members))
        }

    @Test
    fun anOversizedFoundingRosterIsRefusedOnFirstSight() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val members = listOf(rig.self.nodeId, alice.nodeId) + (1..7).map { "extra-$it" }
            val group = rig.group(members = members, createdBy = alice.nodeId)

            rig.deliver(alice, rig.groupUpdate(alice, group))

            assertNull(rig.groupMap[group.id])
            assertEquals(1L, rig.drops(DropReason.GROUP_ROSTER_REFUSED))
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
                    members = listOf(rig.self.nodeId, alice.nodeId),
                    createdBy = alice.nodeId,
                    photoHash = "photoA",
                    photoUpdatedAt = 100L,
                )

            rig.deliver(alice, rig.groupUpdate(alice, group))

            assertEquals("photoA", rig.groupMap[group.id]?.photoHash)
            assertEquals(100L, rig.groupMap[group.id]?.photoUpdatedAt)
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
                    members = listOf(rig.self.nodeId, alice.nodeId),
                    createdBy = alice.nodeId,
                    photoHash = "photoB",
                    photoUpdatedAt = 100L,
                )

            rig.deliver(alice, rig.groupUpdate(alice, group))
            assertNull("photo not shown until its bytes arrive", rig.groupMap[group.id]?.photoHash)
            assertEquals(100L, rig.groupMap[group.id]?.photoUpdatedAt)

            // The pulled blob lands → adopt it onto the group now that the clock still matches.
            rig.pipeline.onObtained("photoB")
            assertEquals("photoB", rig.groupMap[group.id]?.photoHash)
        }

    @Test
    fun anExplicitGroupPhotoIsNotAdopted() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            coEvery { rig.imageScreening.isImageFlagged("photoC") } returns true
            val group =
                rig.group(
                    members = listOf(rig.self.nodeId, alice.nodeId),
                    createdBy = alice.nodeId,
                    photoHash = "photoC",
                    photoUpdatedAt = 100L,
                )

            rig.deliver(alice, rig.groupUpdate(alice, group))
            rig.pipeline.onObtained("photoC")

            assertNull("an explicit photo is dropped, not adopted", rig.groupMap[group.id]?.photoHash)
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

    /** A notification names its sender by the collision-aware label when another pinned peer shares the name (ADR 058). */
    @Test
    fun aNotificationFromOneOfTwoSameNamedPeersCarriesTheAlias() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            val alice2 = party()
            rig.pin(alice)
            rig.pin(alice2)
            rig.peerMap[alice.nodeId] = rig.peerMap.getValue(alice.nodeId).copy(name = "Alice")
            rig.peerMap[alice2.nodeId] = rig.peerMap.getValue(alice2.nodeId).copy(name = "alice")
            val posted = slot<NotifMessage>()

            rig.deliver(alice, rig.broadcastChat(alice, id = "b1", body = "hello room"))

            coVerify { rig.notifier.notify(capture(posted), any(), any(), any(), any()) }
            assertEquals("Alice (${Alias.aliasFor(alice.nodeId)})", posted.captured.senderName)
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
    fun anAcceptedGroupMessageIsDeliveredAndNotifiedViaItsGroupConversation() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val group = rig.group(members = listOf(rig.self.nodeId, alice.nodeId), createdBy = alice.nodeId, name = "Crew")
            rig.settings.accepted.value = setOf(group.id) // an accepted group — not a stranger request

            rig.deliver(alice, rig.groupChat(alice, group, id = "gm1", body = "team hi"))

            assertEquals("team hi", rig.msgMap["gm1"]?.body)
            coVerify { rig.notifier.notify(any(), any(), any(), any(), any()) }
        }

    // --- Message Requests: a stranger's DM/group is delivered + acked but silent until "accepted" ---

    @Test
    fun aStrangerGroupInviteIsDeliveredButNotNotified() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice) // key pinned (so it verifies) but the group is neither accepted nor one we've posted in
            val group = rig.group(members = listOf(rig.self.nodeId, alice.nodeId), createdBy = alice.nodeId, name = "Randos")

            rig.deliver(alice, rig.groupChat(alice, group, id = "gm-req", body = "join us"))

            assertEquals("join us", rig.msgMap["gm-req"]?.body) // still delivered/persisted…
            coVerify(exactly = 0) { rig.notifier.notify(any(), any(), any(), any(), any()) } // …but not notified
        }

    @Test
    fun aGroupMessageFromAKnownSenderNotifiesLikeANormalChat() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            // alice is pinned AND out-of-band verified → a known contact who has now spoken in the group.
            rig.peerMap[alice.nodeId] =
                PeerEntity(nodeId = alice.nodeId, pubKey = alice.bundle.encoded, verified = true, updatedAt = 1L)
            val group =
                rig.group(members = listOf(rig.self.nodeId, alice.nodeId), createdBy = alice.nodeId, name = "Crew")

            rig.deliver(alice, rig.groupChat(alice, group, id = "gm-known", body = "hey all"))

            assertEquals("hey all", rig.msgMap["gm-known"]?.body) // delivered…
            coVerify { rig.notifier.notify(any(), any(), any(), any(), any()) } // …and notified as a normal chat
            coVerify(exactly = 0) { rig.notifier.notifyMessageRequests(any()) } // not silenced as a request
        }

    @Test
    fun aGroupMessageFromAStrangerIsSilentEvenWhenAKnownPeerIsAMember() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice) // the sender is pinned (verifies) but NOT verified/accepted → a stranger
            // A verified contact "bob" is in the roster but has not posted here — membership isn't enough.
            rig.peerMap["bob-node"] = PeerEntity(nodeId = "bob-node", verified = true, updatedAt = 1L)
            val group =
                rig.group(
                    members = listOf(rig.self.nodeId, alice.nodeId, "bob-node"),
                    createdBy = alice.nodeId,
                    name = "Randos",
                )

            rig.deliver(alice, rig.groupChat(alice, group, id = "gm-mixed", body = "join us"))

            assertEquals("join us", rig.msgMap["gm-mixed"]?.body) // delivered…
            coVerify(exactly = 0) { rig.notifier.notify(any(), any(), any(), any(), any()) } // …but silent: the sender is a stranger
        }

    @Test
    fun aStrangerDmIsDeliveredAndAckedButNotNotified() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice) // pinned so it verifies, but NOT verified/accepted and we've never replied → a request

            rig.deliver(alice, rig.dmChat(alice, rig.self, id = "dm-req", body = "hi stranger"))

            assertEquals("hi stranger", rig.msgMap["dm-req"]?.body) // delivered
            assertTrue("keep-ack: a pending DM still acks", rig.originated.any { it.type == FrameType.RECEIPT })
            coVerify(exactly = 0) { rig.notifier.notify(any(), any(), any(), any(), any()) } // but silent
        }

    @Test
    fun anExplicitlyAcceptedDmSenderNotifies() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            rig.settings.accepted.value = setOf(alice.nodeId) // the user accepted this DM request

            rig.deliver(alice, rig.dmChat(alice, rig.self, id = "dm-ok", body = "thanks"))

            coVerify { rig.notifier.notify(any(), any(), any(), any(), any()) }
        }

    @Test
    fun aVerifiedDmSenderNotifiesWithoutAnExplicitAccept() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            // Pinned AND out-of-band verified → a known contact, not a request.
            rig.peerMap[alice.nodeId] =
                PeerEntity(nodeId = alice.nodeId, pubKey = alice.bundle.encoded, verified = true, updatedAt = 1L)

            rig.deliver(alice, rig.dmChat(alice, rig.self, id = "dm-v", body = "hey"))

            coVerify { rig.notifier.notify(any(), any(), any(), any(), any()) }
        }

    @Test
    fun aDmInAThreadWeHaveAlreadyRepliedInNotifies() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            // We've authored in this thread → known. isAccepted now reads conversationsIAuthoredIn (batch).
            coEvery { rig.messages.conversationsIAuthoredIn(rig.self.nodeId) } returns listOf(alice.nodeId)

            rig.deliver(alice, rig.dmChat(alice, rig.self, id = "dm-r", body = "reply?"))

            coVerify { rig.notifier.notify(any(), any(), any(), any(), any()) }
        }

    @Test
    fun aStrangerDmRefreshesTheCoalescedRequestNotificationInsteadOfNotifying() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice) // pinned (verifies) but not accepted/verified/replied → a request
            // Two pending request threads on file, so the coalesced heads-up shows the running total.
            coEvery { rig.messages.distinctConversations() } returns listOf(alice.nodeId, "bob-node")

            rig.deliver(alice, rig.dmChat(alice, rig.self, id = "dm-req2", body = "hello?"))

            coVerify { rig.notifier.notifyMessageRequests(2) } // one quiet coalesced heads-up…
            coVerify(exactly = 0) { rig.notifier.notify(any(), any(), any(), any(), any()) } // …not a per-message notify
        }

    @Test
    fun anAcceptedDmTakesTheNormalNotifyPathNotTheRequestSummary() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            rig.settings.accepted.value = setOf(alice.nodeId)

            rig.deliver(alice, rig.dmChat(alice, rig.self, id = "dm-ok2", body = "yo"))

            coVerify { rig.notifier.notify(any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { rig.notifier.notifyMessageRequests(any()) }
        }

    // --- Blocking is a local presentation choice: a blocked sender's *broadcast/group* message is never
    //     surfaced, but its best-effort delivery tick still goes home so blocking stays invisible and the
    //     sender's ✓✓ isn't stranded when we're their only reachable acker. A blocked DM is not acked. ---

    /**
     * Links [author] as a live neighbor and records every frame we send it over that link, so a test can
     * observe the best-effort broadcast/group delivery tick [AckSync] routes home. Call before delivering.
     */
    private fun TestScope.recordFramesSentTo(
        rig: Rig,
        author: Party,
    ): List<RelayEnvelope> {
        val theirs = FakeLoopTransport(author.nodeId)
        rig.transport.connect(theirs)
        val received = mutableListOf<RelayEnvelope>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            theirs.inbound.collect { received += it.envelope }
        }
        return received
    }

    // --- The Meshtastic room: a post the bound board heard on its primary channel, delivered locally. Its
    //     author is an attribution, never an identity, and nothing about it ever leaves this phone. ---

    private fun heardPost(
        body: String,
        node: Long = 0x1234abcd,
        packetId: Long = 9911,
        name: String? = "Bob",
        viaMqtt: Boolean = false,
        signature: ByteArray? = null,
        boardVerified: Boolean = false,
    ) = MeshPost(
        node = node,
        packetId = packetId,
        body = body,
        name = name,
        channel = "LongFast",
        hops = 2,
        snrDeci = -73,
        viaMqtt = viaMqtt,
        signature = signature,
        boardVerified = boardVerified,
    )

    // A post the lab board `!e681a7c3` signed under its real key (`XeddsaVerifyTest` vector c): the
    // Meshtastic room's own shape, TEXT_MESSAGE_APP, verified off-board before it was pinned here.
    private val labNode = 0xe681a7c3L
    private val labPacketId = 0x1234abcdL
    private val labBody = "hello from Knit a7c3"
    private val labKey = "7Wtkw91w64ahkmd8ESSsCtQ5oSnjy6lMR+fies5QPls="
    private val otherKey = "oR62IJmFUE0Tgcw0GcypU5ZqUFCQllVBy2snB/BKQA4="
    private val labSignature =
        "fbda52354547235b7400409ac34bdfbaea6a9e5d89cae1134e92efaefbe8179b2d581fc366e0af43f04e7228096e0b5b0a6f02da87c2721d8f9a79c31975760d"
            .chunked(
                2,
            ).map {
                it.toInt(16).toByte()
            }.toByteArray()

    private fun labPost(
        signature: ByteArray? = labSignature,
        boardVerified: Boolean = false,
        packetId: Long = labPacketId,
    ) = heardPost(labBody, node = labNode, packetId = packetId, name = "Knit a7c3", signature = signature, boardVerified = boardVerified)

    @Test
    fun aSignedPostFromAContactsAdvertisedKeyIsVerifiedAtIngest() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.deliver(alice, rig.profile(alice, sentAt = 10L, name = "Alice", loraNode = labNode, loraKey = labKey))

            rig.pipeline.deliverMeshPost(labPost())
            advanceUntilIdle()

            val row = rig.msgMap.values.single()
            assertEquals(alice.nodeId, row.originPeerId)
            assertEquals("the words provably came from her radio", MessageEntity.ORIGIN_SIGNED_BY_CONTACT, row.originSigned)
            assertEquals(1L, rig.metrics.snapshot().meshPostVerified)
            assertEquals(1L, rig.metrics.snapshot().meshPostMatched)
        }

    @Test
    fun aSignedPostThatFailsTheContactsKeyIsNotAttributedToThem() =
        runTest {
            // Alice's profile names a different key than the one that signed — some other radio is on her
            // number. Worse than a stranger, and shown as one: her name goes nowhere near the words.
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.deliver(alice, rig.profile(alice, sentAt = 10L, name = "Alice", loraNode = labNode, loraKey = otherKey))

            rig.pipeline.deliverMeshPost(labPost())
            advanceUntilIdle()

            val row = rig.msgMap.values.single()
            assertNull("never attributed", row.originPeerId)
            assertEquals(MessageEntity.ORIGIN_SIGNATURE_MISMATCH, row.originSigned)
            assertEquals("Knit a7c3", row.originName)
            assertEquals(1L, rig.metrics.snapshot().meshPostSignatureMismatch)
            assertEquals("a mismatch is not a match", 0L, rig.metrics.snapshot().meshPostMatched)
        }

    @Test
    fun anUnsignedPostFromAContactsBoardStaysAnUnverifiedAttribution() =
        runTest {
            // Unsigned is not evidence — a long post cannot be signed and a pre-2.8 radio never signs — so the
            // match stands exactly as it did before signatures existed: attributed, and unverified.
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.deliver(alice, rig.profile(alice, sentAt = 10L, name = "Alice", loraNode = labNode, loraKey = labKey))

            rig.pipeline.deliverMeshPost(labPost(signature = null))
            advanceUntilIdle()

            val row = rig.msgMap.values.single()
            assertEquals(alice.nodeId, row.originPeerId)
            assertEquals(MessageEntity.ORIGIN_UNSIGNED, row.originSigned)
            assertEquals(0L, rig.metrics.snapshot().meshPostVerified)
        }

    @Test
    fun aStrangersPostTheBoardVouchedForIsMarkedSignedByBoard() =
        runTest {
            val rig = Rig(backgroundScope)

            rig.pipeline.deliverMeshPost(heardPost("hi", signature = labSignature, boardVerified = true))
            advanceUntilIdle()

            val row = rig.msgMap.values.single()
            assertNull(row.originPeerId)
            assertEquals(MessageEntity.ORIGIN_SIGNED_BY_BOARD, row.originSigned)
            assertEquals(1L, rig.metrics.snapshot().meshPostBoardVerified)
        }

    @Test
    fun aContactWithoutAnAdvertisedKeyGetsTheBoardsVerdictAtMost() =
        runTest {
            // A contact on a build before this one, or on a board that does not sign: nothing on this phone
            // can bind the signature to them, so the board's word is the ceiling and the match stays unverified.
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.deliver(alice, rig.profile(alice, sentAt = 10L, name = "Alice", loraNode = labNode))

            rig.pipeline.deliverMeshPost(labPost(boardVerified = true))
            advanceUntilIdle()

            val row = rig.msgMap.values.single()
            assertEquals(alice.nodeId, row.originPeerId)
            assertEquals(MessageEntity.ORIGIN_SIGNED_BY_BOARD, row.originSigned)
        }

    @Test
    fun theVerdictIsFrozenWhenTheContactLaterRotatesTheirKey() =
        runTest {
            // Judged once, at ingest, and written once: the board replaying the packet after Alice's profile
            // moved to a new key re-judges it (a mismatch now) but never rewrites the row it already made.
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.deliver(alice, rig.profile(alice, sentAt = 10L, name = "Alice", loraNode = labNode, loraKey = labKey))
            rig.pipeline.deliverMeshPost(labPost())
            advanceUntilIdle()
            assertEquals(
                MessageEntity.ORIGIN_SIGNED_BY_CONTACT,
                rig.msgMap.values
                    .single()
                    .originSigned,
            )

            rig.deliver(alice, rig.profile(alice, sentAt = 11L, name = "Alice", loraNode = labNode, loraKey = otherKey))
            rig.pipeline.deliverMeshPost(labPost())
            advanceUntilIdle()

            val row = rig.msgMap.values.single()
            assertEquals("still her words, judged when they were said", MessageEntity.ORIGIN_SIGNED_BY_CONTACT, row.originSigned)
            assertEquals(alice.nodeId, row.originPeerId)
        }

    @Test
    fun aProfileCarryingABoardKeyStoresItOnThePeerAndAClearedOneRemovesIt() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()

            rig.deliver(alice, rig.profile(alice, sentAt = 10L, loraNode = labNode, loraKey = labKey))
            assertEquals(labKey, checkNotNull(rig.peerMap[alice.nodeId]).loraKey)

            // Absent on a newer profile clears it — a board that stopped signing, or was unpaired.
            rig.deliver(alice, rig.profile(alice, sentAt = 11L, loraNode = labNode))
            assertNull(checkNotNull(rig.peerMap[alice.nodeId]).loraKey)
            assertEquals("the number is its own field", labNode, rig.peerMap[alice.nodeId]?.loraNode)
        }

    @Test
    fun aSealedProfileCarriesTheBoardKeyWithTheRestOfThePresentation() =
        runTest {
            // The sealed path copies the whole presentation set (ProfilePayload): a key it did not carry
            // would be reverted by every sealed update after the cleartext frame that set it.
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.deliver(alice, rig.profileWithPrekey(alice, sentAt = 10L, prekey = null, version = 10L))
            val author = V2Author(alice, rig)
            rig.deliver(
                alice,
                author.dm(
                    "ctl-key-1",
                    "",
                    ctl = MessageContent.CTL_PROFILE,
                    pr = ProfilePayload(name = "Ann", status = "", version = 20L, loraNode = labNode, loraKey = labKey),
                ),
            )
            assertEquals(labKey, checkNotNull(rig.peerMap[alice.nodeId]).loraKey)
            assertEquals(labNode, rig.peerMap[alice.nodeId]?.loraNode)
            rig.deliver(
                alice,
                author.dm("ctl-key-2", "", ctl = MessageContent.CTL_PROFILE, pr = ProfilePayload(name = "Ann", status = "", version = 30L)),
            )
            assertNull(checkNotNull(rig.peerMap[alice.nodeId]).loraKey)
        }

    @Test
    fun aMismatchFromABlockedContactsNumberIsAStrangersPostNotTheirs() =
        runTest {
            // Blocking Alice drops her board's posts; a post on her number that her key disowns is not her
            // board's, so it lands as any stranger's would rather than vanishing under her block.
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.deliver(alice, rig.profile(alice, sentAt = 10L, loraNode = labNode, loraKey = otherKey))
            rig.settings.blocked.value = setOf(alice.nodeId)

            rig.pipeline.deliverMeshPost(labPost())
            advanceUntilIdle()

            val row = rig.msgMap.values.single()
            assertNull(row.originPeerId)
            assertEquals(MessageEntity.ORIGIN_SIGNATURE_MISMATCH, row.originSigned)
            assertNull(rig.metrics.snapshot().meshPostRefusedByReason[MESH_POST_BLOCKED_CONTACT])
        }

    @Test
    fun aHeardPostLandsInItsOwnRoomWithItsSpeakerAttributed() =
        runTest {
            val rig = Rig(backgroundScope)

            rig.pipeline.deliverMeshPost(heardPost("anyone around?", viaMqtt = true))
            advanceUntilIdle()

            val row = rig.msgMap.values.single()
            assertEquals("one packet, one row, whoever hears it", FrameId.forMeshPost(0x1234abcd, 9911), row.id)
            assertEquals(Conversations.MESHTASTIC, row.conversationId)
            assertEquals("anyone around?", row.body)
            // The sender column is ours by convention — there is no frame and no signer — and the origin
            // beside it is what says the words are somebody else's.
            assertEquals(rig.self.nodeId, row.senderId)
            assertEquals(0x1234abcdL, row.originNode)
            assertEquals("Bob", row.originName)
            assertEquals("LongFast", row.originChannel)
            assertEquals(2, row.originHops)
            assertEquals(-73, row.originSnrDeci)
            assertTrue(row.originViaMqtt)
            assertEquals("it reached us over the board", DeliveryPlane.LoRa.code, row.receivedVia)
        }

    @Test
    fun aHeardPostIsNeverAckedAndNeverLeavesThePhone() =
        runTest {
            // There is nobody to tick — the speaker has no Knit identity — and, more than that, nothing about
            // a heard post is originated at all: no frame of any type may leave for it.
            val rig = Rig(backgroundScope)

            rig.pipeline.deliverMeshPost(heardPost("hi"))
            advanceUntilIdle()

            assertEquals(1, rig.msgMap.size)
            assertTrue("nothing is originated for a heard post", rig.originated.isEmpty())
            assertEquals(0, rig.metrics.snapshot().receiptsResent)
        }

    @Test
    fun aHeardPostCreatesNoPeerRowForItsSpeaker() =
        runTest {
            // A Meshtastic node number is not a Knit identity and is trivially spoofable, so nothing about the
            // speaker may reach `peers` — no presence, no contacts entry, no DM target.
            val rig = Rig(backgroundScope)

            rig.pipeline.deliverMeshPost(heardPost("hi", name = "Bob"))
            advanceUntilIdle()

            assertTrue(rig.peerMap.isEmpty())
        }

    @Test
    fun aHeardPostNotifiesUnderItsSpeakersNameEvenThoughThisPhoneIsItsSender() =
        runTest {
            // The row's senderId is ours by convention, and `incomingNotification` suppresses our own messages
            // — so the notification must be keyed on the speaker, or the one device that hears the channel is
            // the one device that never notifies.
            val rig = Rig(backgroundScope)
            val notification = slot<NotifMessage>()
            coEvery { rig.notifier.notify(capture(notification), any(), any(), any(), any()) } returns Unit

            rig.pipeline.deliverMeshPost(heardPost("anyone around?", name = "Bob"))
            advanceUntilIdle()

            assertEquals("Bob", notification.captured.senderName)
            assertEquals("keyed on the speaker, not on us", "!1234abcd", notification.captured.senderId)
            assertNull("a stranger wears no face", notification.captured.avatarBytes)
        }

    @Test
    fun aHeardPostIsModeratedAsAPublicRoom() =
        runTest {
            // Its authors are strangers by definition and nothing screens them before they reach the air, so
            // it takes the room moderator (the lexical profanity pass on top of the ML one) — the same
            // `isRoom = true` Nearby gets, which is what `Conversations.isPublicRoom` is for.
            val rig = Rig(backgroundScope)
            val scopes = mutableListOf<Boolean>()
            rig.classifyScopes = scopes

            rig.pipeline.deliverMeshPost(heardPost("hi"))
            advanceUntilIdle()

            assertEquals(listOf(true), scopes)
        }

    @Test
    fun theBoardReplayingAPacketWritesOneRowAndNotifiesOnce() =
        runTest {
            // The firmware replays the packets it queued while the phone was away. The derived row id makes
            // the second hearing a no-op on the exists-gate, and `isNew` keeps the notification to the first.
            val rig = Rig(backgroundScope)
            var notified = 0
            coEvery { rig.notifier.notify(any(), any(), any(), any(), any()) } answers { notified += 1 }

            rig.pipeline.deliverMeshPost(heardPost("hi"))
            rig.pipeline.deliverMeshPost(heardPost("hi"))
            advanceUntilIdle()

            assertEquals(1, rig.msgMap.size)
            assertEquals(1, notified)
        }

    @Test
    fun aProfileCarryingABoardNodeStoresItOnThePeerAndAClearedOneRemovesIt() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()

            rig.deliver(alice, rig.profile(alice, sentAt = 10L, loraNode = 0x1234abcdL))
            assertEquals(0x1234abcdL, checkNotNull(rig.peerMap[alice.nodeId]).loraNode)

            // Absent on a newer profile clears it — an unpaired or handed-on board is unsaid by omission.
            rig.deliver(alice, rig.profile(alice, sentAt = 11L))
            assertNull(checkNotNull(rig.peerMap[alice.nodeId]).loraNode)
        }

    @Test
    fun aHeardPostFromAContactsBoardIsAttributedToThatContactAtIngest() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.deliver(alice, rig.profile(alice, sentAt = 10L, name = "Alice", loraNode = 0x1234abcdL))
            val notification = slot<NotifMessage>()
            coEvery { rig.notifier.notify(capture(notification), any(), any(), any(), any()) } returns Unit

            rig.pipeline.deliverMeshPost(heardPost("hi", node = 0x1234abcdL, name = "Knit abcd"))
            advanceUntilIdle()

            val row = rig.msgMap.values.single()
            assertEquals(alice.nodeId, row.originPeerId)
            assertEquals("the row keeps what went on the air", "hi", row.body)
            assertEquals("Knit abcd", row.originName)
            assertEquals("the notification is the contact's, keyed like their DM", alice.nodeId, notification.captured.senderId)
            assertEquals("Alice", notification.captured.senderName)
            assertEquals("the words are the whole post", "hi", notification.captured.body)
            assertEquals(1L, rig.metrics.snapshot().meshPostMatched)
        }

    @Test
    fun aHeardPostFromABlockedContactsBoardIsDropped() =
        runTest {
            // A stranger cannot be blocked (a node number is spoofable), but a contact can, and their board is
            // their board.
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.deliver(alice, rig.profile(alice, sentAt = 10L, loraNode = 0x1234abcdL))
            rig.settings.blocked.value = setOf(alice.nodeId)

            rig.pipeline.deliverMeshPost(heardPost("hi", node = 0x1234abcdL))
            advanceUntilIdle()

            assertTrue(rig.msgMap.isEmpty())
            assertEquals(1L, rig.metrics.snapshot().meshPostRefusedByReason[MESH_POST_BLOCKED_CONTACT])
        }

    @Test
    fun whenTwoPeersClaimOneNodeTheNewerProfileWins() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            val bob = party()
            rig.deliver(alice, rig.profile(alice, sentAt = 10L, loraNode = 0x1234abcdL))
            rig.deliver(bob, rig.profile(bob, sentAt = 20L, loraNode = 0x1234abcdL)) // the board changed hands

            rig.pipeline.deliverMeshPost(heardPost("hi", node = 0x1234abcdL))
            advanceUntilIdle()

            assertEquals(
                bob.nodeId,
                rig.msgMap.values
                    .single()
                    .originPeerId,
            )
        }

    @Test
    fun aLaterProfileChangeNeverReattributesHistory() =
        runTest {
            // Resolved once, at ingest, and frozen on the row: what Alice said while she held the board stays
            // hers after Bob takes it over.
            val rig = Rig(backgroundScope)
            val alice = party()
            val bob = party()
            rig.deliver(alice, rig.profile(alice, sentAt = 10L, loraNode = 0x1234abcdL))
            rig.pipeline.deliverMeshPost(heardPost("first", node = 0x1234abcdL, packetId = 1))
            advanceUntilIdle()
            rig.deliver(bob, rig.profile(bob, sentAt = 20L, loraNode = 0x1234abcdL))
            rig.pipeline.deliverMeshPost(heardPost("second", node = 0x1234abcdL, packetId = 2))
            advanceUntilIdle()

            val byBody = rig.msgMap.values.associateBy { it.body }
            assertEquals(alice.nodeId, byBody.getValue("first").originPeerId)
            assertEquals(bob.nodeId, byBody.getValue("second").originPeerId)
        }

    @Test
    fun aHeardPostStampsWhenItArrived() =
        runTest {
            // Our own room posts looping back get no arrivedAt (we sent them); a heard post sits in our sender
            // column by convention yet did arrive, off the board, so the origin overrides the sender test.
            val rig = Rig(backgroundScope)

            rig.pipeline.deliverMeshPost(heardPost("hi"))
            advanceUntilIdle()

            assertNotNull(
                rig.msgMap.values
                    .single()
                    .arrivedAt,
            )
        }

    @Test
    fun aBlockedSendersBroadcastIsNotSurfacedButStillAcked() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            rig.settings.blocked.value = setOf(alice.nodeId)
            val acksToAlice = recordFramesSentTo(rig, alice)

            rig.deliver(alice, rig.broadcastChat(alice, id = "blk-b", body = "hi room"))
            advanceUntilIdle()

            // Nothing from a blocked sender is surfaced locally…
            assertFalse("a blocked sender's message is not persisted", rig.msgMap.containsKey("blk-b"))
            coVerify(exactly = 0) { rig.notifier.notify(any(), any(), any(), any(), any()) }
            // …but the best-effort delivery tick still goes back, so blocking stays invisible and their ✓✓
            // isn't stranded when we're their only reachable acker (the reported bug).
            assertTrue(
                "a blocked broadcast should still be acked",
                acksToAlice.any {
                    it.type == FrameType.RECEIPT && WireCodec.decodePayload<ReceiptContent>(it.payload)?.ackId == "blk-b"
                },
            )
        }

    @Test
    fun aBlockedMembersGroupMessageIsNotSurfacedButStillAcked() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            // We're a member of this group locally; we then blocked co-member alice.
            val group = rig.group(members = listOf(rig.self.nodeId, alice.nodeId), createdBy = rig.self.nodeId)
            rig.groupMap[group.id] =
                GroupEntity(
                    groupId = group.id,
                    name = "",
                    members = GroupMembersStore.encode(listOf(rig.self.nodeId, alice.nodeId)),
                    createdBy = rig.self.nodeId,
                    createdAt = 1L,
                )
            rig.settings.blocked.value = setOf(alice.nodeId)
            val acksToAlice = recordFramesSentTo(rig, alice)

            rig.deliver(alice, rig.groupChat(alice, group, id = "blk-g", body = "team hi"))
            advanceUntilIdle()

            assertFalse("a blocked member's group message is not persisted", rig.msgMap.containsKey("blk-g"))
            assertTrue(
                "a blocked member's group message should still be acked when we're a member",
                acksToAlice.any {
                    it.type == FrameType.RECEIPT && WireCodec.decodePayload<ReceiptContent>(it.payload)?.ackId == "blk-g"
                },
            )
        }

    @Test
    fun aBlockedSendersDmIsNeitherDeliveredNorAcked() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            rig.settings.blocked.value = setOf(alice.nodeId)

            rig.deliver(alice, rig.dmChat(alice, rig.self, id = "blk-dm", body = "let me in"))

            // A DM is deliberately excluded: not delivered, and no flooded receipt originated (which would
            // also vaccine-purge it from custody) — unlike the best-effort broadcast/group tick above.
            assertFalse(rig.msgMap.containsKey("blk-dm"))
            assertTrue(
                "a blocked DM must not be acked",
                rig.originated.none { it.type == FrameType.RECEIPT },
            )
        }

    // --- Fix 1: inbound body + future-dated sentAt are clamped ---

    @Test
    fun anOversizedInboundBodyIsClampedToTheMessageLimit() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)

            rig.deliver(alice, rig.broadcastChat(alice, id = "big", body = "x".repeat(TextLimits.MESSAGE * 3)))

            assertEquals(TextLimits.MESSAGE, rig.msgMap["big"]?.body?.length)
        }

    @Test
    fun aFutureDatedInboundSentAtIsClampedForLocalOrdering() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val farFuture = System.currentTimeMillis() + 3_650L * 24 * 60 * 60_000 // ~10 years ahead
            val env =
                RelayEnvelope(
                    type = FrameType.CHAT,
                    id = "fut",
                    senderId = alice.nodeId,
                    sentAt = farFuture,
                    payload = WireCodec.encodePayload(ChatContent(body = "from the future")),
                )

            rig.deliver(alice, env)

            val stored = rig.msgMap["fut"]!!.sentAt
            assertTrue("a far-future sentAt must be clamped down", stored < farFuture)
            assertTrue(stored <= System.currentTimeMillis() + Protocol.MAX_FUTURE_SKEW_MS)
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
            coEvery { rig.imageScreening.isImageFlagged("av2") } returns true

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
            coEvery { rig.imageScreening.isImageFlagged(hash) } returns true
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
            coVerify(exactly = 0) { rig.imageScreening.screenImage(any(), any()) }
            coVerify(exactly = 0) { rig.imageScreening.screenAttachment(any(), any(), any(), any()) }
        }

    @Test
    fun screenEncryptedAttachmentReturnsWhenTheBlobIsAbsent() =
        runTest {
            val rig = Rig(backgroundScope)
            // We hold the key but not the ciphertext blob yet → nothing to screen.
            coEvery { rig.messages.attachmentKeyForHash("h2") } returns "a2V5"

            rig.pipeline.onObtained("h2")

            coVerify { rig.blobs.bytes("h2") }
            coVerify(exactly = 0) { rig.imageScreening.screenImage(any(), any()) }
            coVerify(exactly = 0) { rig.imageScreening.screenAttachment(any(), any(), any(), any()) }
        }

    @Test
    fun aSealedCardIsDecryptedAndScreenedAsACardOnceItsBytesLand() =
        runTest {
            // A link-preview card in a DM is sealed like any attachment; the container is opened after decryption
            // and screened as a card (picture and text), under the ciphertext hash the row and the UI both use.
            val rig = Rig(backgroundScope)
            val plain = LinkPreviewBlob(LinkPreviewBlob.VERSION, "https://example.com/", "Title").encode()
            val sealed = AttachmentCrypto.seal(plain)
            coEvery { rig.messages.attachmentKeyForHash("card-ct") } returns b64(sealed.key)
            coEvery { rig.messages.attachmentMimeForHash("card-ct") } returns LinkPreviewBlob.MIME
            coEvery { rig.blobs.bytes("card-ct") } returns sealed.blob

            rig.pipeline.onObtained("card-ct")

            coVerify { rig.imageScreening.screenAttachment("card-ct", plain, LinkPreviewBlob.MIME, isRoom = false) }
        }

    @Test
    fun aRoomCardHeldBeforeItsRowArrivedIsScreenedAsACardAtDelivery() =
        runTest {
            // The blob was relayed first (so saveIncoming saw no row and could only try it as an image, a no-op on
            // a container); when the room message arrives naming it, the delivery screens it as a card, room scope.
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val container = LinkPreviewBlob(LinkPreviewBlob.VERSION, "https://example.com/", "Title").encode()
            coEvery { rig.blobStore.has("card-plain") } returns true
            coEvery { rig.blobs.bytes("card-plain") } returns container
            val env =
                RelayEnvelope(
                    type = FrameType.CHAT,
                    id = "room-card",
                    senderId = alice.nodeId,
                    sentAt = 5L,
                    recipientId = null,
                    payload =
                        WireCodec.encodePayload(
                            ChatContent(
                                body = "see https://example.com/",
                                attachmentHash = "card-plain",
                                attachmentMime = LinkPreviewBlob.MIME,
                            ),
                        ),
                )

            rig.pipeline.onDeliver(alice.sign(env), env, alice.nodeId)

            coVerify { rig.imageScreening.screenAttachment("card-plain", container, LinkPreviewBlob.MIME, isRoom = true) }
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

    // --- the v2 epoch-ratchet decrypt path ---

    /** Author-side v2 ratchet: real engine state driving real wire frames at the pipeline. */
    private inner class V2Author(
        val party: Party,
        private val rig: Rig,
        /** The author's session-establishment clock (a later value models a wiped, re-initiating device). */
        private val at: Long = 5L,
    ) {
        private val engine = RatchetEngine()
        private var session: RatchetEngine.SessionState

        init {
            val initiation =
                engine.initiate(
                    peerId = rig.self.nodeId,
                    ownIkPriv = party.dhPriv,
                    peerIkPub = rig.self.bundle.dhPublicKey(),
                    peerSpk = RatchetEngine.PeerPrekey(id = SPK_ID, pub = rig.selfSpk.pub),
                    now = at,
                )
            session = initiation.session
        }

        fun dm(
            id: String,
            body: String,
            ctl: Int? = null,
            gk: GroupKeyPayload? = null,
            ack: String? = null,
            rp: ReactionPayload? = null,
            pr: ProfilePayload? = null,
            acks: List<String>? = null,
            sentAt: Long = 5L,
            /** Seal crypto scheme v3 (derived nonce, compact plaintext, header-bound AAD) instead of v2. */
            v3: Boolean = false,
            /** Overrides the envelope's nonce field after sealing — the malformed-shape tests. */
            nonceOverride: ByteArray? = null,
            mutateHeader: (RatchetHeader) -> RatchetHeader = { it },
        ): RelayEnvelope {
            val to = rig.self.nodeId
            val aad = MessageCrypto.header(id, party.nodeId, sentAt, to)
            val (plain, scheme) = MessageContent(body = body, ctl = ctl, gk = gk, ack = ack, rp = rp, pr = pr, acks = acks).sealBytes(v3)
            check(!v3 || scheme == EncEnvelope.VERSION_DM_V3) { "fixture asked for v3 but the content has no compact form" }
            val sealed = checkNotNull(engine.seal(session, plain, aad, rig.selfSpk.pub, now = 5L, v3 = v3))
            session = sealed.session
            val h = sealed.header
            val header =
                mutateHeader(
                    RatchetHeader(
                        se = h.se,
                        ek = h.ek,
                        pe = h.pe,
                        n = h.n,
                        init = h.init?.let { RatchetInit(eph = it.eph, pkid = it.pkid, at = it.at) },
                        flags = h.flags,
                    ),
                )
            return RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = party.nodeId,
                sentAt = sentAt,
                recipientId = to,
                payload =
                    WireCodec.encodePayload(
                        ChatContent(
                            enc =
                                EncEnvelope(
                                    v = scheme,
                                    nonce = nonceOverride ?: sealed.nonce ?: ByteArray(0),
                                    ct = sealed.ct,
                                    keys = emptyList(),
                                    r = header,
                                ),
                        ),
                    ),
            )
        }
    }

    @Test
    fun aV2InitDmDecryptsPersistsAndEstablishesTheSession() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val author = V2Author(alice, rig)

            rig.deliver(alice, author.dm("v2-1", "ratchet hello"))

            assertEquals("ratchet hello", rig.msgMap["v2-1"]?.body)
            val session = rig.ratchetStore.session(alice.nodeId)
            assertTrue(session != null && session.confirmed && !session.weAreInitiator)
            assertEquals(1, rig.ratchetStore.recvEpoch(alice.nodeId, 1)?.next)
            // The DM ack flooded back as usual.
            assertTrue(rig.originated.any { it.type == FrameType.RECEIPT })
        }

    @Test
    fun v2OutOfOrderDeliveryDrainsThroughSkippedKeys() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val author = V2Author(alice, rig)
            val m0 = author.dm("v2-m0", "first")
            val m1 = author.dm("v2-m1", "second")

            rig.deliver(alice, m1)
            rig.deliver(alice, m0)

            assertEquals("second", rig.msgMap["v2-m1"]?.body)
            assertEquals("first", rig.msgMap["v2-m0"]?.body)
            // The out-of-order key was stored, consumed, and removed.
            assertEquals(null, rig.ratchetStore.skippedKey(alice.nodeId, 1, 0))
        }

    @Test
    fun aReServedV2FrameShortCircuitsBeforeTheRatchetAndStillAcks() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val author = V2Author(alice, rig)
            val frame = author.dm("v2-r", "once")
            rig.deliver(alice, frame)
            val acksAfterFirst = rig.originated.count { it.type == FrameType.RECEIPT }

            // Nuke the ratchet state entirely: if the re-serve reached the engine it would now fail and
            // count a ratchet drop. The exists-gate must answer before any crypto runs.
            rig.ratchetStore.deletePeer(alice.nodeId)
            rig.deliver(alice, frame)

            assertEquals("once", rig.msgMap["v2-r"]?.body)
            assertEquals(acksAfterFirst + 1, rig.originated.count { it.type == FrameType.RECEIPT })
            assertEquals(0L, rig.drops(DropReason.RATCHET_NO_SESSION))
            assertEquals(0L, rig.drops(DropReason.DECRYPT_FAILED))
        }

    @Test
    fun aV2EnvelopeWithoutItsHeaderIsBadHeaderNotACrash() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val env = rig.dmWithEnvVersion(alice, rig.self, id = "v2-bad", v = EncEnvelope.VERSION_RATCHET)

            rig.deliver(alice, env)

            assertEquals(1L, rig.drops(DropReason.RATCHET_BAD_HEADER))
            assertFalse(rig.msgMap.containsKey("v2-bad"))
        }

    @Test
    fun aV2FrameWithoutInitToAFreshDeviceIsNoSession() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val author = V2Author(alice, rig)
            // Strip the init and claim an established epoch — what a peer with stale session state sends.
            val orphan =
                author.dm("v2-orphan", "lost") { h ->
                    RatchetHeader(se = h.se, ek = h.ek, pe = 1, n = h.n, init = null, flags = h.flags)
                }

            rig.deliver(alice, orphan)

            assertEquals(1L, rig.drops(DropReason.RATCHET_NO_SESSION))
            assertFalse(rig.msgMap.containsKey("v2-orphan"))
        }

    @Test
    fun aSplitBrainRootRequestsAResetInsteadOfDeadlockingForever() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            val alicePrekey = RatchetCrypto.generateKeyPair()
            rig.pinRatchetCapable(alice, alicePrekey.pub)
            val author = V2Author(alice, rig)

            // Key material resolves (the init establishes the session) but the AEAD refuses, because the
            // header claims a chain index the sender never sealed at. That is the shape of a divergent root
            // era: both sides hold a session, neither can read the other. Three DISTINCT frames, since one
            // stuck frame re-served from custody must never trigger anything.
            listOf("sb1", "sb2", "sb3").forEach { id ->
                rig.deliver(
                    alice,
                    author.dm(
                        id,
                        "unreadable",
                    ) { h -> RatchetHeader(se = h.se, ek = h.ek, pe = h.pe, n = h.n + 5, init = h.init, flags = h.flags) },
                )
            }

            assertEquals(3L, rig.drops(DropReason.RATCHET_AEAD_FAIL))
            assertEquals("a split brain must not be filed under the generic v1 failure", 0L, rig.drops(DropReason.DECRYPT_FAILED))
            // Exactly one reset for the burst — the per-peer floor holds, so a peer re-serving custody at us
            // cannot turn every undecryptable frame into a fresh X3DH init.
            val resets =
                rig.originated.filter {
                    WireCodec
                        .decodePayload<ChatContent>(it.payload)
                        ?.enc
                        ?.r
                        ?.init != null
                }
            assertEquals(1, resets.size)
            assertEquals(alice.nodeId, resets.single().recipientId)
        }

    @Test
    fun sealingAResetDropsOurOwnRecvStateSoThePeersFreshEpochsAreNotJudgedStale() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            val alicePrekey = RatchetCrypto.generateKeyPair()
            rig.pinRatchetCapable(alice, alicePrekey.pub)
            val author = V2Author(alice, rig)

            // A good frame first, so we actually hold a recv epoch for alice to go stale.
            val good = author.dm("ok-1", "hello")
            rig.deliver(alice, good)
            val se = checkNotNull(WireCodec.decodePayload<ChatContent>(good.payload)?.enc?.r).se
            assertNotNull("precondition: a recv epoch exists", rig.ratchetStore.recvEpoch(alice.nodeId, se))

            // Now drive the reset heuristic, which seals a reset and abandons that root era.
            listOf("sb1", "sb2", "sb3").forEach { id ->
                rig.deliver(
                    alice,
                    author.dm(
                        id,
                        "unreadable",
                    ) { h -> RatchetHeader(se = h.se, ek = h.ek, pe = h.pe, n = h.n + 5, init = h.init, flags = h.flags) },
                )
            }
            assertTrue(
                "precondition: a reset was actually sent",
                rig.originated.any {
                    WireCodec
                        .decodePayload<ChatContent>(it.payload)
                        ?.enc
                        ?.r
                        ?.init !=
                        null
                },
            )

            // The dead era's recv rows must be gone. Left behind, they judge alice's post-replacement
            // epochs — whose numbers may reuse these — against a stale chain index, dropping fresh frames
            // as DUPLICATE. That is terminal: a duplicate is benign, so it triggers no further recovery.
            assertNull(rig.ratchetStore.recvEpoch(alice.nodeId, se))
        }

    @Test
    fun distinctFramesLandingOnConsumedChainIndicesNeverRequestAReset() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            val alicePrekey = RatchetCrypto.generateKeyPair()
            rig.pinRatchetCapable(alice, alicePrekey.pub)
            val author = V2Author(alice, rig)
            // Consume a few indices normally, so replays of them land as DUPLICATE.
            val consumed = listOf("d1", "d2", "d3").map { author.dm(it, "body-$it") }
            consumed.forEach { rig.deliver(alice, it) }

            // Three DISTINCT frames each claiming an already-consumed index. This is what a re-served
            // BACKLOG looks like — many ids, every one of them ciphertext we already read — and the
            // distinct-id rule cannot tell it from a sender that restarted its chain. Since a consumed
            // index is proof the frame decrypted once, the benign reading is the only sound one, and
            // acting on it re-roots a healthy session and strands a whole TTL of custody (ADR 024).
            val consumedIndices = consumed.map { checkNotNull(WireCodec.decodePayload<ChatContent>(it.payload)?.enc?.r).n }
            consumedIndices.forEachIndexed { i, n ->
                // Keep the live header — only the chain index goes backwards onto an index we already used.
                rig.deliver(
                    alice,
                    author.dm(
                        "replayer-$i",
                        "x",
                    ) { h -> RatchetHeader(se = h.se, ek = h.ek, pe = h.pe, n = n, init = h.init, flags = h.flags) },
                )
            }
            assertEquals(3L, rig.drops(DropReason.RATCHET_DUPLICATE))
            assertEquals(
                "a duplicate is our own delivered history coming back, never evidence of divergence",
                0,
                rig.originated.count {
                    WireCodec
                        .decodePayload<ChatContent>(it.payload)
                        ?.enc
                        ?.r
                        ?.init != null
                },
            )
        }

    @Test
    fun framesSealedBeforeTheCurrentEraAreNotEvidenceThatItIsBroken() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pinRatchetCapable(alice, RatchetCrypto.generateKeyPair().pub)

            // Establish an era at t=1000. `establishedAt` is the init's `at`, which both peers converge on.
            val live = V2Author(alice, rig, at = 1_000L)
            rig.deliver(alice, live.dm("era-1", "hello", sentAt = 1_000L))
            assertEquals(1_000L, rig.ratchetStore.session(alice.nodeId)?.establishedAt)

            // Three DISTINCT undecryptable frames authored BEFORE that era — the tail every reset leaves
            // in custody, re-served for a full TTL. Past the distinct-frame rule (three ids, not one
            // repeated) and past the 6 h floor, so the era stamp is the only thing standing between us and
            // re-rooting a session that is working.
            listOf("old1", "old2", "old3").forEach { id ->
                rig.deliver(
                    alice,
                    live.dm(
                        id,
                        "unreadable",
                        sentAt = 900L,
                    ) { h -> RatchetHeader(se = h.se, ek = h.ek, pe = h.pe, n = h.n + 5, init = h.init, flags = h.flags) },
                )
            }

            assertEquals(3L, rig.drops(DropReason.RATCHET_AEAD_FAIL))
            assertEquals(
                "ciphertext from an era we already left is unreadable by construction",
                0,
                rig.originated.count {
                    WireCodec
                        .decodePayload<ChatContent>(it.payload)
                        ?.enc
                        ?.r
                        ?.init != null
                },
            )

            // Same failure, authored inside the live era: still a reset, so the gate narrows the
            // population rather than disarming the heuristic.
            listOf("new1", "new2", "new3").forEach { id ->
                rig.deliver(
                    alice,
                    live.dm(
                        id,
                        "unreadable",
                        sentAt = 1_000L,
                    ) { h -> RatchetHeader(se = h.se, ek = h.ek, pe = h.pe, n = h.n + 9, init = h.init, flags = h.flags) },
                )
            }
            assertEquals(
                1,
                rig.originated.count {
                    WireCodec
                        .decodePayload<ChatContent>(it.payload)
                        ?.enc
                        ?.r
                        ?.init != null
                },
            )
        }

    /**
     * Drives the rig into the INITIATOR half of the era gate — the half `establishedAt` is stamped from
     * OUR clock, and the only one `env.sentAt` cannot be compared against directly (ADR 026). [era] is
     * both the session's era stamp and its outbound-reset stamp, so placing it further back than
     * `RESET_MIN_INTERVAL_MS` is what leaves the heuristic reachable again.
     */
    private suspend fun Rig.initiateEraAt(
        peer: Party,
        era: Long,
    ) {
        pinRatchetCapable(peer, RatchetCrypto.generateKeyPair().pub)
        assertNull(pipeline.sendSessionReset(peer.nodeId, self.nodeId, era))
        val session = checkNotNull(ratchetStore.session(peer.nodeId))
        assertTrue("the rig must be the initiator for these cases", session.weAreInitiator)
        assertEquals(era, session.establishedAt)
    }

    /** Reset requests we have originated: a v2 DM whose header carries a fresh X3DH init. */
    private fun Rig.resetsSent(): Int =
        originated.count {
            WireCodec
                .decodePayload<ChatContent>(it.payload)
                ?.enc
                ?.r
                ?.init != null
        }

    /**
     * [count] undecryptable frames from [peer], each stamped [sentAt] on the PEER's clock. The init is
     * dropped and the base epoch moved to our own live one: steady-state traffic from a peer that
     * considers the session settled, DHing against a key we hold but sealed under a root we no longer
     * share. That is the split-brain shape — signature-valid, authenticated, and `AEAD_FAIL` — so with
     * distinct ids only the era gate stands between these and a reset request.
     */
    private suspend fun Rig.deliverUnreadable(
        peer: Party,
        tag: String,
        sentAt: Long,
        count: Int = RatchetSessions.RESET_DISTINCT_FRAMES,
    ) {
        val author = V2Author(peer, this)
        repeat(count) { i ->
            deliver(
                peer,
                author.dm("$tag-$i", "unreadable", sentAt = sentAt) { h ->
                    RatchetHeader(se = h.se, ek = h.ek, pe = 1, n = h.n, init = null, flags = h.flags)
                },
            )
        }
    }

    @Test
    fun aPeerWhoseClockLagsOursStillProvesTheSessionIsBroken() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            val era = System.currentTimeMillis() - 7 * HOUR_MS
            rig.initiateEraAt(alice, era)
            val before = rig.resetsSent()

            // Alice authored these AFTER our era began, but her clock lags ours, so they carry a `sentAt`
            // behind our `establishedAt`. Raw, that comparison spans two devices' clocks: it read live
            // failures as a doomed pre-era tail and silently disarmed the heuristic in this direction for
            // as long as the skew lasted (GitLab #22). Within MAX_FUTURE_SKEW_MS the house tolerance
            // covers it — beyond that only the retention-window escape below does.
            rig.deliverUnreadable(alice, "lag", sentAt = era - (Protocol.MAX_FUTURE_SKEW_MS - 60_000L))

            assertEquals(3L, rig.drops(DropReason.RATCHET_AEAD_FAIL))
            assertEquals(
                "clock skew within the house tolerance must not disarm the reset heuristic",
                before + 1,
                rig.resetsSent(),
            )
        }

    @Test
    fun aTailFromTheEraWeLeftIsStillNotEvidenceOnTheInitiatorSide() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            val era = System.currentTimeMillis() - 7 * HOUR_MS
            rig.initiateEraAt(alice, era)
            val before = rig.resetsSent()

            // 23 h before the era — far past any clock disagreement worth tolerating, and the era is still
            // young enough that custody could genuinely be re-serving that tail. ADR 024's case, unchanged.
            rig.deliverUnreadable(alice, "tail", sentAt = System.currentTimeMillis() - 30 * HOUR_MS)

            assertEquals(3L, rig.drops(DropReason.RATCHET_AEAD_FAIL))
            assertEquals(
                "ciphertext from an era we already left is unreadable by construction",
                before,
                rig.resetsSent(),
            )
        }

    @Test
    fun anEraOlderThanEveryRetentionWindowRearmsTheHeuristic() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            // Older than RatchetSessions.STRANDED_TAIL_MS: custody (24 h) and the spool's default scope
            // retention (48 h) have both expired, so nothing sealed under the previous era survives
            // anywhere to be re-served at us.
            val era = System.currentTimeMillis() - 49 * HOUR_MS
            rig.initiateEraAt(alice, era)
            val before = rig.resetsSent()

            // Stamped well before the era, which on this half proves nothing — the peer's clock could be
            // set to any year. The local elapsed measure is what decides, and it says no tail can survive.
            rig.deliverUnreadable(alice, "stale", sentAt = System.currentTimeMillis() - 60 * HOUR_MS)

            assertEquals(
                "past every retention window an unreadable frame is real divergence, whatever the clocks say",
                before + 1,
                rig.resetsSent(),
            )
        }

    @Test
    fun oneFrameReservedFromCustodyNeverRequestsAResetHoweverOftenItArrives() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            val alicePrekey = RatchetCrypto.generateKeyPair()
            rig.pinRatchetCapable(alice, alicePrekey.pub)
            val author = V2Author(alice, rig)
            val once = author.dm("d1", "body")
            rig.deliver(alice, once)

            // The same id over and over — custody re-serving it, or two links delivering it. The router's
            // SeenSet catches most of these upstream; the heuristic must be safe even when one slips past.
            repeat(8) { rig.deliver(alice, once) }

            assertEquals(
                "a replay must never reach the distinct threshold",
                0,
                rig.originated.count {
                    WireCodec
                        .decodePayload<ChatContent>(it.payload)
                        ?.enc
                        ?.r
                        ?.init !=
                        null
                },
            )
        }

    @Test
    fun aCtlFrameAdvancesTheChainButNeverPersistsNotifiesOrAcks() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val author = V2Author(alice, rig)

            rig.deliver(alice, author.dm("v2-ctl", "", ctl = MessageContent.CTL_SESSION_RESET))

            assertFalse(rig.msgMap.containsKey("v2-ctl"))
            assertTrue(rig.originated.none { it.type == FrameType.RECEIPT })
            // The session still advanced — the control frame's chain step is not a hole.
            assertEquals(1, rig.ratchetStore.recvEpoch(alice.nodeId, 1)?.next)
        }

    // --- session reset ---

    @Test
    fun repeatedUndecryptableFramesTriggerExactlyOneRateLimitedResetRequest() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pinRatchetCapable(alice, RatchetCrypto.generateKeyPair().pub)
            val author = V2Author(alice, rig)

            fun orphan(id: String) =
                author.dm(id, "lost") { h -> RatchetHeader(se = h.se, ek = h.ek, pe = 1, n = h.n, init = null, flags = h.flags) }

            rig.deliver(alice, orphan("o1"))
            rig.deliver(alice, orphan("o2"))
            assertTrue("below the distinct-frames threshold, no reset yet", rig.originated.isEmpty())

            rig.deliver(alice, orphan("o3"))
            val resets =
                rig.originated.mapNotNull { WireCodec.decodePayload<ChatContent>(it.payload)?.enc?.r }.filter {
                    it.flags and RatchetHeader.FLAG_RESET != 0
                }
            assertEquals(1, resets.size)
            assertNotNull("the reset carries a fresh X3DH init", resets.single().init)

            // More undecryptable traffic inside the rate-limit window must not fire again.
            rig.deliver(alice, orphan("o4"))
            val after =
                rig.originated.mapNotNull { WireCodec.decodePayload<ChatContent>(it.payload)?.enc?.r }.count {
                    it.flags and RatchetHeader.FLAG_RESET != 0
                }
            assertEquals(1, after)
        }

    /**
     * A structurally invalid header says nothing about our session, so it must never walk the reset
     * heuristic. `pe = -1` used to slip past `headerSane` on the strength of its init and land as
     * `EPOCH_GONE` — a member of `RESET_TRIGGERING_DROPS` — so three of them bought a reset request.
     */
    @Test
    fun structurallyInvalidHeadersNeverTriggerAResetRequest() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pinRatchetCapable(alice, RatchetCrypto.generateKeyPair().pub)
            val author = V2Author(alice, rig)

            repeat(RatchetSessions.RESET_DISTINCT_FRAMES) { i ->
                rig.deliver(
                    alice,
                    author.dm("bad-$i", "malformed") { h ->
                        RatchetHeader(se = h.se, ek = h.ek, pe = -1, n = h.n, init = h.init, flags = h.flags)
                    },
                )
            }

            assertEquals(RatchetSessions.RESET_DISTINCT_FRAMES.toLong(), rig.drops(DropReason.RATCHET_BAD_HEADER))
            assertEquals(0L, rig.drops(DropReason.RATCHET_EPOCH_GONE))
            assertEquals("a malformed header is not evidence the session is broken", 0, rig.resetsSent())
        }

    @Test
    fun anInboundResetReplacesTheSessionAndTriggersTheUnackedReseal() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pinRatchetCapable(alice, RatchetCrypto.generateKeyPair().pub)
            rig.deliver(alice, V2Author(alice, rig, at = 5L).dm("pre-wipe", "before"))
            assertEquals(5L, rig.ratchetStore.session(alice.nodeId)?.establishedAt)

            // Alice wipes and re-initiates: a fresh engine with a later establishment clock sends the
            // reset control frame (what maybeRequestReset's counterpart produces on her side).
            val reborn = V2Author(alice, rig, at = 60_000L)
            rig.deliver(alice, reborn.dm("reset-1", "", ctl = MessageContent.CTL_SESSION_RESET))

            assertEquals(60_000L, rig.ratchetStore.session(alice.nodeId)?.establishedAt)
            assertEquals(listOf(alice.nodeId), rig.resealed)
            assertFalse("a control frame is never persisted", rig.msgMap.containsKey("reset-1"))
            val ackedIds =
                rig.originated
                    .filter { it.type == FrameType.RECEIPT }
                    .mapNotNull { WireCodec.decodePayload<ReceiptContent>(it.payload)?.ackId }
            assertFalse("a control frame is never acked (the pre-wipe DM's ack is fine)", "reset-1" in ackedIds)
        }

    @Test
    fun aSecondReplacementInsideTheRateLimitWindowIsInert() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pinRatchetCapable(alice, RatchetCrypto.generateKeyPair().pub)
            rig.deliver(alice, V2Author(alice, rig, at = 5L).dm("est", "hello"))
            rig.deliver(alice, V2Author(alice, rig, at = 60_000L).dm("r1", "", ctl = MessageContent.CTL_SESSION_RESET))
            assertEquals(60_000L, rig.ratchetStore.session(alice.nodeId)?.establishedAt)

            // A third session claim right away: the replacement gate holds the previous session.
            rig.deliver(alice, V2Author(alice, rig, at = 120_000L).dm("r2", "", ctl = MessageContent.CTL_SESSION_RESET))

            assertEquals(60_000L, rig.ratchetStore.session(alice.nodeId)?.establishedAt)
            assertEquals(listOf(alice.nodeId), rig.resealed)
        }

    // --- profile prekey pinning ---

    private fun Rig.profileWithPrekey(
        author: Party,
        sentAt: Long,
        prekey: PrekeyInfo?,
        version: Long? = null,
        name: String = "Ann",
    ): RelayEnvelope =
        RelayEnvelope(
            type = FrameType.PROFILE,
            id = "prof-${author.nodeId}-$sentAt",
            senderId = author.nodeId,
            sentAt = sentAt,
            payload =
                WireCodec.encodePayload(
                    ProfileContent(
                        name = name,
                        status = "",
                        pubKey = author.bundle.encoded,
                        prekey = prekey,
                        version = version,
                    ),
                ),
        )

    private fun signedPrekey(
        author: Party,
        id: Int = 7,
    ): PrekeyInfo {
        val pub = RatchetCrypto.generateKeyPair().pub
        return PrekeyInfo(id = id, pub = pub, sig = author.crypto.signRaw(RatchetCrypto.spkSigningBytes(id, pub)))
    }

    @Test
    fun aProfilePinsItsVerifiedPrekeyAndAForgedOneIsIgnored() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            val good = signedPrekey(alice)

            rig.deliver(alice, rig.profileWithPrekey(alice, sentAt = 10L, prekey = good))
            assertEquals(7, rig.peerMap[alice.nodeId]?.prekeyId)
            assertEquals(b64(good.pub), rig.peerMap[alice.nodeId]?.prekeyPub)

            // A newer profile with a tampered signature: profile applies, prekey pin is dropped (treated
            // as "no prekey" — the field is independent of the rest of the profile).
            val forged = PrekeyInfo(id = 8, pub = good.pub, sig = ByteArray(64))
            rig.deliver(alice, rig.profileWithPrekey(alice, sentAt = 11L, prekey = forged))
            assertEquals(null, rig.peerMap[alice.nodeId]?.prekeyId)
        }

    @Test
    fun aNewerProfileWithoutAPrekeyClearsThePin() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.deliver(alice, rig.profileWithPrekey(alice, sentAt = 10L, prekey = signedPrekey(alice)))
            assertTrue(rig.peerMap[alice.nodeId]?.prekeyPub != null)

            rig.deliver(alice, rig.profileWithPrekey(alice, sentAt = 20L, prekey = null))

            assertEquals(null, rig.peerMap[alice.nodeId]?.prekeyPub)
            assertEquals("Ann", rig.peerMap[alice.nodeId]?.name)
        }

    @Test
    fun theProfileVersionOrdersLwwAndFallsBackToSentAtForAPeerWithoutTheField() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()

            // version wins over sentAt: a frame published later but carrying an OLDER version must not
            // overwrite the newer profile — that is the re-publish case (fresh stamp, unchanged profile).
            rig.deliver(alice, rig.profileWithPrekey(alice, sentAt = 10L, prekey = null, version = 50L, name = "New"))
            rig.deliver(alice, rig.profileWithPrekey(alice, sentAt = 99L, prekey = null, version = 20L, name = "Old"))
            assertEquals("New", rig.peerMap[alice.nodeId]?.name)
            assertEquals(50L, rig.peerMap[alice.nodeId]?.updatedAt)

            // A peer predating the field sends no version, and for those sentAt IS the version.
            val bob = party()
            rig.deliver(bob, rig.profileWithPrekey(bob, sentAt = 70L, prekey = null, version = null, name = "Legacy"))
            assertEquals("Legacy", rig.peerMap[bob.nodeId]?.name)
            assertEquals(70L, rig.peerMap[bob.nodeId]?.updatedAt)
        }

    // --- the open-to-chat profile flag (rides both profile paths under the presentation watermark) ---

    @Test
    fun theOpenToChatFlagArrivesWithTheCleartextProfileAndLeavesByOmission() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.deliver(alice, rig.profile(alice, sentAt = 10L, openToChat = true))
            assertTrue(checkNotNull(rig.peerMap[alice.nodeId]).openToChat)
            // A newer profile without the key is the peer switching it off: absence reads false.
            rig.deliver(alice, rig.profile(alice, sentAt = 11L))
            assertFalse(checkNotNull(rig.peerMap[alice.nodeId]).openToChat)
            // A re-served older copy cannot switch it back on.
            rig.deliver(alice, rig.profile(alice, sentAt = 10L, openToChat = true))
            assertFalse(checkNotNull(rig.peerMap[alice.nodeId]).openToChat)
        }

    @Test
    fun theSealedProfileCarriesTheFlagAndAStaleOneCannotRevertIt() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.deliver(alice, rig.profileWithPrekey(alice, sentAt = 10L, prekey = null, version = 10L))
            assertFalse(checkNotNull(rig.peerMap[alice.nodeId]).openToChat)
            val author = V2Author(alice, rig)
            rig.deliver(
                alice,
                author.dm(
                    "ctl-otc-1",
                    "",
                    ctl = MessageContent.CTL_PROFILE,
                    pr = ProfilePayload(name = "Ann", status = "", version = 20L, openToChat = true),
                ),
            )
            assertTrue(checkNotNull(rig.peerMap[alice.nodeId]).openToChat)
            assertEquals(20L, rig.peerMap[alice.nodeId]?.updatedAt)
            // An older sealed update is ignored whole, flag included.
            rig.deliver(
                alice,
                author.dm("ctl-otc-2", "", ctl = MessageContent.CTL_PROFILE, pr = ProfilePayload(name = "Ann", status = "", version = 15L)),
            )
            assertTrue(checkNotNull(rig.peerMap[alice.nodeId]).openToChat)
            // A newer one that omits it switches it off — the sealed path copies the whole presentation set.
            rig.deliver(
                alice,
                author.dm("ctl-otc-3", "", ctl = MessageContent.CTL_PROFILE, pr = ProfilePayload(name = "Ann", status = "", version = 30L)),
            )
            assertFalse(checkNotNull(rig.peerMap[alice.nodeId]).openToChat)
        }

    @Test
    fun aPrekeyOnlyAdmissionOfAnOlderProfileDoesNotRegressTheFlag() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.deliver(alice, rig.profileWithPrekey(alice, sentAt = 10L, prekey = null, version = 10L))
            rig.deliver(
                alice,
                V2Author(
                    alice,
                    rig,
                ).dm(
                    "ctl-otc-4",
                    "",
                    ctl = MessageContent.CTL_PROFILE,
                    pr = ProfilePayload(name = "Ann", status = "", version = 100L, openToChat = true),
                ),
            )
            assertTrue(checkNotNull(rig.peerMap[alice.nodeId]).openToChat)
            // Older on the presentation clock, newer on the prekey clock (the ADR 022 race): admitted for its
            // prekey only, so the flag — a presentation field — must not move.
            rig.deliver(alice, rig.profileWithPrekey(alice, sentAt = 50L, prekey = signedPrekey(alice), version = 50L))
            assertTrue("the stale half must not revert the flag", checkNotNull(rig.peerMap[alice.nodeId]).openToChat)
        }

    @Test
    fun anOpenToChatFlipProducesNoStatusNotice() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.peerMap[alice.nodeId] =
                PeerEntity(nodeId = alice.nodeId, pubKey = alice.bundle.encoded, name = "Peer", updatedAt = 1L)
            rig.msgMap["m1"] = MessageEntity(id = "m1", senderId = alice.nodeId, conversationId = alice.nodeId, body = "hi", sentAt = 1L)
            val profile = rig.profile(alice, name = "Peer", sentAt = 99L, openToChat = true)
            rig.pipeline.onDeliver(alice.sign(profile), profile, alice.nodeId)
            assertTrue(checkNotNull(rig.peerMap[alice.nodeId]).openToChat)
            assertTrue("a flag flip is not a rename or a new photo", rig.msgMap.values.none { it.isStatusNotice })
        }

    @Test
    fun aRepublishedProfileDoesNotAdvanceTheWatermarkSoItIsNotMistakenForAnEdit() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.deliver(alice, rig.profileWithPrekey(alice, sentAt = 10L, prekey = null, version = 10L))
            // Same profile, refreshed publish stamp — exactly what republishProfileIfStale emits to keep the
            // frame inside custody's `sentAt + ttl` window. The watermark must track the version, not the stamp.
            rig.deliver(alice, rig.profileWithPrekey(alice, sentAt = 9_000L, prekey = null, version = 10L))
            assertEquals(10L, rig.peerMap[alice.nodeId]?.updatedAt)
        }

    @Test
    fun aSealedProfileUpdateCannotSuppressTheCleartextFrameCarryingThePrekey() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            val prekey = signedPrekey(alice)
            // Pin the key at an early version, so the prekey watermark sits well behind the presentation one.
            rig.deliver(alice, rig.profileWithPrekey(alice, sentAt = 10L, prekey = null, version = 10L, name = "Ann"))

            // A sealed CTL_PROFILE carries presentation only — never a prekey (ADR 020) — yet advances
            // `updatedAt`. This is the real field race: a live spool EVENT delivers it before the heal round
            // pulls the cleartext profile behind it.
            rig.deliver(
                alice,
                V2Author(
                    alice,
                    rig,
                ).dm("ctl-p1", "", ctl = MessageContent.CTL_PROFILE, pr = ProfilePayload(name = "Newest", status = "", version = 100L)),
            )
            assertEquals("Newest", rig.peerMap[alice.nodeId]?.name)
            assertEquals(100L, rig.peerMap[alice.nodeId]?.updatedAt)

            // Now the cleartext frame carrying the prekey arrives, older on the presentation clock. Under one
            // shared watermark it was dropped whole, so the prekey never landed and a broken DM session with
            // an Internet-only peer could never be re-established.
            rig.deliver(alice, rig.profileWithPrekey(alice, sentAt = 50L, prekey = prekey, version = 50L, name = "Older"))

            assertEquals(b64(prekey.pub), rig.peerMap[alice.nodeId]?.prekeyPub)
            assertEquals("the stale half must not revert presentation", "Newest", rig.peerMap[alice.nodeId]?.name)
            assertEquals("nor drag the watermark back", 100L, rig.peerMap[alice.nodeId]?.updatedAt)
        }

    @Test
    fun aPrekeyOlderThanTheOneWeHoldIsIgnoredOnItsOwnWatermark() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            val newer = signedPrekey(alice, id = 9)
            rig.deliver(alice, rig.profileWithPrekey(alice, sentAt = 80L, prekey = newer, version = 80L))
            assertEquals(9, rig.peerMap[alice.nodeId]?.prekeyId)

            // A re-served older profile must not roll the prekey back, and must not clear it either.
            rig.deliver(alice, rig.profileWithPrekey(alice, sentAt = 40L, prekey = signedPrekey(alice, id = 3), version = 40L))
            assertEquals(9, rig.peerMap[alice.nodeId]?.prekeyId)

            rig.deliver(alice, rig.profileWithPrekey(alice, sentAt = 41L, prekey = null, version = 41L))
            assertEquals(9, rig.peerMap[alice.nodeId]?.prekeyId)
        }

    @Test
    fun aClearedPrekeyPinIsNotReopenedByAReservedOlderProfile() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.deliver(alice, rig.profileWithPrekey(alice, sentAt = 80L, prekey = signedPrekey(alice, id = 9), version = 80L))
            // The peer downgrades: a newer profile carrying no prekey clears the pin (ADR 020).
            rig.deliver(alice, rig.profileWithPrekey(alice, sentAt = 100L, prekey = null, version = 100L))
            assertEquals(null, rig.peerMap[alice.nodeId]?.prekeyId)

            // Custody re-serves the pre-downgrade profile. The clear advanced the prekey watermark, so this
            // must not look newer and re-pin a key the peer has stopped serving.
            rig.deliver(alice, rig.profileWithPrekey(alice, sentAt = 50L, prekey = signedPrekey(alice, id = 3), version = 50L))
            assertEquals(null, rig.peerMap[alice.nodeId]?.prekeyId)
        }

    // --- group sender-key ratchet (v2 group form): seed adoption over real ctl DMs + the group decrypt path ---

    /** Drives a real [GroupRatchetEngine] as one group member authoring group-form frames toward the rig. */
    private inner class GroupRatchetAuthor(
        val party: Party,
        private val groupId: String,
        at: Long = 5L,
    ) {
        private val engine = GroupRatchetEngine()
        var chain = engine.mint(groupId, party.nodeId, prevEpoch = 0, now = at)

        fun seed() = GroupSeed(epoch = chain.epoch, seed = chain.seed, mintedAt = chain.mintedAt)

        /** Models a device wipe: all chain state lost, numbering restarts at 1 with a fresh mint. */
        fun wipeAndRemint(at: Long) {
            chain = engine.mint(groupId, party.nodeId, prevEpoch = 0, now = at)
        }

        fun groupFrame(
            group: GroupInfo,
            id: String,
            body: String,
            sentAt: Long = 5L,
            ctl: Int? = null,
            rp: ReactionPayload? = null,
        ): RelayEnvelope {
            val aad = MessageCrypto.header(id, party.nodeId, sentAt, groupId)
            val sealed = checkNotNull(engine.seal(chain, MessageContent(body = body, ctl = ctl, rp = rp).encode(), aad))
            chain = sealed.chain
            return RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = party.nodeId,
                sentAt = sentAt,
                group = group,
                payload =
                    WireCodec.encodePayload(
                        ChatContent(
                            enc =
                                EncEnvelope(
                                    v = EncEnvelope.VERSION_RATCHET,
                                    nonce = sealed.nonce,
                                    ct = sealed.ct,
                                    keys = emptyList(),
                                    g = GroupRatchetHeader(se = sealed.header.se, n = sealed.header.n),
                                ),
                        ),
                    ),
            )
        }
    }

    /** Seeds the rig's group row + returns the wire GroupInfo, both from the derived id. */
    private fun Rig.seedRatchetGroup(vararg others: Party): GroupInfo {
        val members = listOf(self.nodeId) + others.map { it.nodeId }
        val id = Conversations.groupIdFor(members)
        seedGroup(id, members = members, createdBy = others.first().nodeId)
        return group(members = members, createdBy = others.first().nodeId)
    }

    @Test
    fun aSeedCtlDmAdoptsTheChainAndIsNeverPersistedOrAcked() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val group = rig.seedRatchetGroup(alice)
            val author = GroupRatchetAuthor(alice, group.id)

            rig.deliver(
                alice,
                V2Author(
                    alice,
                    rig,
                ).dm("seed1", "", ctl = MessageContent.CTL_GROUP_KEY, gk = GroupKeyPayload(group.id, keys = listOf(author.seed()))),
            )

            // The chain was adopted through the real ctl path…
            assertEquals(1, rig.groupRatchetStore.recvChains(group.id, alice.nodeId, 1).size)
            // …the ctl frame was never persisted, notified, or acked as a message…
            assertFalse(rig.msgMap.containsKey("seed1"))
            assertFalse(rig.originated.any { it.type == FrameType.RECEIPT })
            coVerify(exactly = 0) { rig.notifier.notify(any(), any(), any(), any(), any()) }
            // …and an adoption ack rode back to the sender as an ordinary v2 ctl DM.
            assertTrue(rig.originated.any { it.type == FrameType.CHAT && it.recipientId == alice.nodeId })
        }

    @Test
    fun aSealedDmReceiptFlipsTheTickWithoutPurgingCustody() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            // Our outbound DM to alice: known to messages (the forged-ack guard reads its recipient)
            // and held in our own custody, exactly the state after a real send.
            rig.msgMap["dm-out"] =
                MessageEntity(id = "dm-out", senderId = rig.self.nodeId, recipientId = alice.nodeId, body = "", sentAt = 1L)
            val outbound = rig.dmChat(rig.self, alice, id = "dm-out", body = "hi")
            rig.forwardSync.onSeen(rig.self.sign(outbound), outbound, ForwardStore.ORIGIN_SELF)

            rig.deliver(alice, V2Author(alice, rig).dm("ctl-r1", "", ctl = MessageContent.CTL_RECEIPT, ack = "dm-out"))

            // The tick flipped through the sealed path, on the radio plane it arrived by…
            coVerify(exactly = 1) { rig.messages.markReceived("dm-out", DeliveryPlane.Nearby) }
            // …but nothing vaccine-purged: a carrier can't read a sealed receipt, so nobody purges —
            // the delivered DM ages out on the custody TTL uniformly (the retirement contract).
            assertTrue(rig.forwardStore.has("dm-out"))
            // And the ctl frame kept the machinery contract: no row, no notification, no ack-of-an-ack.
            assertFalse(rig.msgMap.containsKey("ctl-r1"))
            assertFalse(rig.originated.any { it.type == FrameType.RECEIPT })
            coVerify(exactly = 0) { rig.notifier.notify(any(), any(), any(), any(), any()) }
        }

    @Test
    fun aDeliveredDmAcksSealedWhenTheAuthorIsCapable() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pinRatchetCapable(alice, RatchetCrypto.generateKeyPair().pub)
            val author = V2Author(alice, rig)

            rig.deliver(alice, author.dm("v2-cap1", "hello"))

            assertEquals("hello", rig.msgMap["v2-cap1"]?.body)
            // The ack rode back sealed — an ordinary v2 ctl chat frame, never a cleartext RECEIPT…
            assertFalse(rig.originated.any { it.type == FrameType.RECEIPT })
            val ctlAck = rig.originated.single { it.type == FrameType.CHAT && it.recipientId == alice.nodeId }
            // …custodial like any chat frame, so its sentAt must carry the wall clock (work item #16).
            assertEquals(42L, ctlAck.sentAt)
            assertEquals(1L, rig.metrics.snapshot().receiptsSealed)
            // Sealed-era custody contract: the delivered DM stays in our own custody (nobody purges;
            // it ages out on the TTL with every carrier's copy — that convergence IS the retirement).
            assertTrue(rig.forwardStore.has("v2-cap1"))
        }

    // --- crypto scheme v3 and the unsigned door (ADR 059) ---

    /** The unsigned form of [env]: `relay = false`, an empty signature — what `MeshManager.sealDeliveryTick` sends a v3 author. */
    private fun unsigned(env: RelayEnvelope): WireEnvelope =
        WireEnvelope(relay = false, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(env))

    /** [env] with its ciphertext replaced by garbage under the same (v3) header — a forgery that will fail the AEAD. */
    private fun forged(env: RelayEnvelope): RelayEnvelope {
        val enc = checkNotNull(WireCodec.decodePayload<ChatContent>(env.payload)?.enc)
        val junk =
            EncEnvelope(
                v = enc.v,
                nonce = enc.nonce,
                ct = ByteArray(enc.ct.size) { (it * 37 + 11).toByte() },
                keys = emptyList(),
                r = enc.r,
            )
        return RelayEnvelope(
            env.type,
            env.id,
            env.senderId,
            env.sentAt,
            env.recipientId,
            env.group,
            WireCodec.encodePayload(ChatContent(enc = junk)),
        )
    }

    /** Our outbound DM [id] to [peer], as the forged-ack guard and the custody store know it after a real send. */
    private suspend fun Rig.ownDmTo(
        peer: Party,
        id: String,
    ) {
        msgMap[id] = MessageEntity(id = id, senderId = self.nodeId, recipientId = peer.nodeId, body = "", sentAt = 1L)
        val outbound = dmChat(self, peer, id = id, body = "hi")
        forwardSync.onSeen(self.sign(outbound), outbound, ForwardStore.ORIGIN_SELF)
    }

    @Test
    fun anUnsignedV3TickFromAPinnedPeerAppliesTheReceipt() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pinRatchetCapable(alice, RatchetCrypto.generateKeyPair().pub)
            val dmOut = FrameId.new()
            rig.ownDmTo(alice, dmOut)

            val tick = V2Author(alice, rig).dm("tick-1", "", ctl = MessageContent.CTL_RECEIPT, ack = dmOut, v3 = true)
            rig.pipeline.onDeliver(unsigned(tick), tick, alice.nodeId, TransportKind.Bluetooth)

            coVerify(exactly = 1) { rig.messages.markReceived(dmOut, DeliveryPlane.Nearby) }
            assertEquals(0L, rig.drops(DropReason.UNSIGNED_REFUSED))
            assertEquals(0L, rig.drops(DropReason.SIG_INVALID))
            // A ctl frame, so no row, no ack-of-an-ack, and — being relay = false — nothing custodied.
            assertFalse(rig.msgMap.containsKey("tick-1"))
            assertTrue(rig.originated.none { it.type == FrameType.CHAT && it.recipientId == alice.nodeId })
            assertFalse(rig.forwardStore.has("tick-1"))
        }

    @Test
    fun theUnsignedDoorRefusesEveryOtherShape() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            val bob = party()
            rig.pinRatchetCapable(alice, RatchetCrypto.generateKeyPair().pub)
            val dmOut = FrameId.new()
            rig.ownDmTo(alice, dmOut)
            val author = V2Author(alice, rig)

            // Flooded: the signature is what every relay and carrier would need, so an empty one is refused.
            val flooded = author.dm("u-relay", "", ctl = MessageContent.CTL_RECEIPT, ack = dmOut, v3 = true)
            rig.pipeline.onDeliver(
                WireEnvelope(relay = true, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(flooded)),
                flooded,
                alice.nodeId,
            )
            assertEquals(1L, rig.drops(DropReason.UNSIGNED_REFUSED))
            assertFalse("never custodied either", rig.forwardStore.has("u-relay"))

            // Addressed to someone else: not our tick, not our session.
            val foreign = author.dm("u-foreign", "", ctl = MessageContent.CTL_RECEIPT, ack = dmOut, v3 = true)
            val reAddressed = RelayEnvelope(foreign.type, foreign.id, foreign.senderId, foreign.sentAt, bob.nodeId, null, foreign.payload)
            rig.pipeline.onDeliver(unsigned(reAddressed), reAddressed, alice.nodeId)
            assertEquals(2L, rig.drops(DropReason.UNSIGNED_REFUSED))

            // A v2 envelope: the right shape at the door, the wrong scheme behind it.
            val v2 = author.dm("u-v2", "", ctl = MessageContent.CTL_RECEIPT, ack = dmOut)
            rig.pipeline.onDeliver(unsigned(v2), v2, alice.nodeId)
            assertEquals(3L, rig.drops(DropReason.UNSIGNED_REFUSED))

            // A cleartext DM with no seal at all.
            val clear = rig.dmChat(alice, rig.self, id = "u-clear", body = "hello")
            rig.pipeline.onDeliver(unsigned(clear), clear, alice.nodeId)
            assertEquals(4L, rig.drops(DropReason.UNSIGNED_REFUSED))
            assertFalse(rig.msgMap.containsKey("u-clear"))

            // A v3 plain chat with its signature stripped: it OPENS, and is refused anyway — before commit, so
            // the chain index is untouched and the genuine signed copy still delivers afterwards.
            val plain = author.dm("u-plain", "a real message", v3 = true)
            rig.pipeline.onDeliver(unsigned(plain), plain, alice.nodeId)
            assertEquals(5L, rig.drops(DropReason.UNSIGNED_REFUSED))
            assertFalse(rig.msgMap.containsKey("u-plain"))
            rig.deliver(alice, plain)
            assertEquals("a real message", rig.msgMap["u-plain"]?.body)

            coVerify(exactly = 0) { rig.messages.markReceived(dmOut, any()) }
            assertEquals(0, rig.resetsSent())
        }

    @Test
    fun aMisshapenNonceIsABadHeaderNeverAResetTrigger() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pinRatchetCapable(alice, RatchetCrypto.generateKeyPair().pub)
            val author = V2Author(alice, rig)

            // v3 must carry the field empty; v2 must carry twelve bytes.
            rig.deliver(alice, author.dm("v3-with-nonce", "x", v3 = true, nonceOverride = ByteArray(12)))
            rig.deliver(alice, author.dm("v2-without-nonce", "y", nonceOverride = ByteArray(0)))
            rig.deliver(alice, author.dm("v2-short-nonce", "z", nonceOverride = ByteArray(3)))

            assertEquals(3L, rig.drops(DropReason.RATCHET_BAD_HEADER))
            assertEquals(0L, rig.drops(DropReason.RATCHET_AEAD_FAIL))
            assertEquals(0, rig.resetsSent())
            assertTrue(rig.msgMap.isEmpty())
        }

    @Test
    fun forgedUnsignedFramesFeedNeitherTheResetHeuristicNorTheExistsGate() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pinRatchetCapable(alice, RatchetCrypto.generateKeyPair().pub)
            val author = V2Author(alice, rig)
            // A live session, so the forgeries carry a header the receiver can resolve — the worst case.
            val real1 = FrameId.new()
            rig.deliver(alice, author.dm(real1, "hello", v3 = true))
            assertEquals("hello", rig.msgMap[real1]?.body)
            // (The genuine DM earned its one sealed receipt; nothing below may add another.)
            val receiptsBefore = rig.originated.count { it.type == FrameType.CHAT && it.recipientId == alice.nodeId }
            assertEquals(1, receiptsBefore)

            repeat(RatchetSessions.RESET_DISTINCT_FRAMES) { i ->
                val f = forged(author.dm("forged-$i", "", ctl = MessageContent.CTL_RECEIPT, ack = real1, v3 = true))
                rig.pipeline.onDeliver(unsigned(f), f, alice.nodeId)
            }
            assertEquals(RatchetSessions.RESET_DISTINCT_FRAMES.toLong(), rig.drops(DropReason.RATCHET_AEAD_FAIL))
            assertEquals("forgeries anyone can mint must never buy a session reset", 0, rig.resetsSent())

            // A forgery naming an id we already hold must not make us re-acknowledge it: the unsigned door
            // never takes the exists-gate shortcut.
            val oracle = forged(author.dm(real1, "", ctl = MessageContent.CTL_RECEIPT, ack = real1, v3 = true))
            rig.pipeline.onDeliver(unsigned(oracle), oracle, alice.nodeId)
            assertEquals(receiptsBefore, rig.originated.count { it.type == FrameType.CHAT && it.recipientId == alice.nodeId })
            assertTrue(rig.originated.none { it.type == FrameType.RECEIPT })

            // And the genuine session is unharmed: the next real v3 frame still opens.
            rig.deliver(alice, author.dm("real-2", "still here", v3 = true))
            assertEquals("still here", rig.msgMap["real-2"]?.body)

            // An unsigned tick from a sender we have never pinned is a missing-key drop, not an unsigned refusal.
            val stranger = party()
            val orphan = V2Author(stranger, rig).dm("orphan", "", ctl = MessageContent.CTL_RECEIPT, ack = real1, v3 = true)
            rig.pipeline.onDeliver(unsigned(orphan), orphan, stranger.nodeId)
            assertEquals(1L, rig.drops(DropReason.NO_SENDER_KEY))
            assertEquals(0L, rig.drops(DropReason.UNSIGNED_REFUSED))
        }

    @Test
    fun aV3DmDecryptsWithItsCompactContentAndItsInlineAcks() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pinRatchetCapable(alice, RatchetCrypto.generateKeyPair().pub)
            val mine1 = FrameId.new()
            val mine2 = FrameId.new()
            rig.ownDmTo(alice, mine1)
            rig.ownDmTo(alice, mine2)
            val author = V2Author(alice, rig)

            // A real frame id: the receipt names it, and the compact codec carries only canonical ids.
            val dmId = FrameId.new()
            val frame = author.dm(dmId, "compact hello", acks = listOf(mine1, mine2), v3 = true)
            val enc = checkNotNull(WireCodec.decodePayload<ChatContent>(frame.payload)?.enc)
            assertEquals(EncEnvelope.VERSION_DM_V3, enc.v)
            assertEquals(0, enc.nonce.size)
            rig.deliver(alice, frame, kind = TransportKind.Bluetooth)

            assertEquals("compact hello", rig.msgMap[dmId]?.body)
            coVerify(exactly = 1) { rig.messages.markReceived(mine1, DeliveryPlane.Nearby) }
            coVerify(exactly = 1) { rig.messages.markReceived(mine2, DeliveryPlane.Nearby) }
            // The instant receipt back to a v3 author seals v3 — and, being originated, stays signed and custodied.
            val receipt = rig.originated.single { it.type == FrameType.CHAT && it.recipientId == alice.nodeId }
            val receiptEnc = checkNotNull(WireCodec.decodePayload<ChatContent>(receipt.payload)?.enc)
            assertEquals(EncEnvelope.VERSION_DM_V3, receiptEnc.v)
            assertEquals(0, receiptEnc.nonce.size)
            assertEquals(1L, rig.metrics.snapshot().receiptsSealed)
            assertEquals(1L, rig.metrics.snapshot().dmSealedV3)
            assertEquals(0L, rig.metrics.snapshot().ticksUnsigned)
        }

    /**
     * ADR 054: a DM off the board holds its ✓✓ for the coalescer — no instant seal, one id however many
     * copies re-deliver inside the hold — and the batch flushes once as one tick when the hold runs out.
     */
    @Test
    fun aDmHeardOverLoraHoldsItsReceiptThenTicksOnce() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pinRatchetCapable(alice, RatchetCrypto.generateKeyPair().pub)
            val author = V2Author(alice, rig)

            rig.deliver(alice, author.dm("lora-1", "hello from the hills"), kind = TransportKind.LoRa)

            assertEquals("hello from the hills", rig.msgMap["lora-1"]?.body)
            assertTrue("no instant receipt", rig.originated.none { it.type == FrameType.CHAT && it.recipientId == alice.nodeId })
            assertEquals(0L, rig.metrics.snapshot().receiptsSealed)
            assertEquals(1L, rig.metrics.snapshot().loraTickDeferred)
            assertEquals(listOf("lora-1"), rig.dmAcks.pending(alice.nodeId))

            // A second DM joins the batch; a re-delivery of the first (the exists-gate) adds nothing.
            rig.deliver(alice, author.dm("lora-2", "still here"), kind = TransportKind.LoRa)
            rig.deliver(alice, author.dm("lora-1", "hello from the hills"), kind = TransportKind.LoRa)
            assertEquals(listOf("lora-1", "lora-2"), rig.dmAcks.pending(alice.nodeId))
            assertEquals(3L, rig.metrics.snapshot().loraTickDeferred)

            rig.nowMs += DmAckCoalescer.HOLD_MS
            rig.dmAcks.flushDue()
            assertEquals(listOf(alice.nodeId to listOf("lora-1", "lora-2")), rig.dmFlushes)
            assertTrue(rig.dmAcks.pending(alice.nodeId).isEmpty())
        }

    /** The hold is a LoRa-only trade: a DM off the phone radios keeps today's instant sealed receipt. */
    @Test
    fun aDmHeardOverBluetoothStillAcksInstantly() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pinRatchetCapable(alice, RatchetCrypto.generateKeyPair().pub)

            rig.deliver(alice, V2Author(alice, rig).dm("ble-1", "next door"), kind = TransportKind.Bluetooth)

            assertEquals(1L, rig.metrics.snapshot().receiptsSealed)
            assertEquals(0L, rig.metrics.snapshot().loraTickDeferred)
            assertTrue(rig.dmAcks.pending(alice.nodeId).isEmpty())
        }

    /** An author who cannot read a sealed receipt gets the cleartext one at once — there is nothing to coalesce. */
    @Test
    fun anIncapableAuthorsLoraDmKeepsTheInstantCleartextReceipt() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)

            rig.deliver(alice, rig.dmChat(alice, rig.self, id = "lora-clear", body = "hi"), kind = TransportKind.LoRa)

            assertTrue(rig.originated.any { it.type == FrameType.RECEIPT })
            assertTrue(rig.dmAcks.pending(alice.nodeId).isEmpty())
            assertEquals(0L, rig.metrics.snapshot().loraTickDeferred)
        }

    /**
     * ADR 054's piggyback, receive side: a plain sealed DM's `acks` flip our ticks under the same forged-ack
     * guard as a `CTL_RECEIPT` (an id addressed to someone else never flips), and a re-delivered copy never
     * re-applies them — the exists-gate stops it before the decrypt.
     */
    @Test
    fun aPlainDmCarryingInlineAcksFlipsThoseTicksUnderTheForgedAckGuard() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pinRatchetCapable(alice, RatchetCrypto.generateKeyPair().pub)
            val author = V2Author(alice, rig)
            rig.msgMap["dm-out"] =
                MessageEntity(id = "dm-out", senderId = rig.self.nodeId, recipientId = alice.nodeId, body = "", sentAt = 1L)
            rig.msgMap["dm-x"] =
                MessageEntity(id = "dm-x", senderId = rig.self.nodeId, recipientId = "someone-else", body = "", sentAt = 1L)

            rig.deliver(alice, author.dm("reply-1", "thanks", acks = listOf("dm-out", "dm-x")))

            assertEquals("thanks", rig.msgMap["reply-1"]?.body)
            coVerify(exactly = 1) { rig.messages.markReceived("dm-out", DeliveryPlane.Nearby) }
            coVerify(exactly = 0) { rig.messages.markReceived("dm-x", any()) }

            rig.deliver(alice, author.dm("reply-1", "thanks", acks = listOf("dm-out", "dm-x")))
            coVerify(exactly = 1) { rig.messages.markReceived("dm-out", any()) }
        }

    @Test
    fun aBatchedSealedTickFlipsEveryListedId() =
        runTest {
            // The custody-escalated group tick: one sealed CTL_RECEIPT whose `acks` list covers several
            // messages. The forged-ack guard runs PER id — group sends (null recipient) flip, a DM
            // addressed to someone else does not — and the machinery contract holds (no row, no ack).
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val author = V2Author(alice, rig)
            rig.msgMap["gm-a"] = MessageEntity(id = "gm-a", senderId = rig.self.nodeId, body = "", sentAt = 1L)
            rig.msgMap["gm-b"] = MessageEntity(id = "gm-b", senderId = rig.self.nodeId, body = "", sentAt = 1L)
            rig.msgMap["dm-x"] = MessageEntity(id = "dm-x", senderId = rig.self.nodeId, recipientId = "bob", body = "", sentAt = 1L)

            rig.deliver(alice, author.dm("tick-b1", "", ctl = MessageContent.CTL_RECEIPT, acks = listOf("gm-a", "gm-b", "dm-x")))

            coVerify { rig.messages.markReceived("gm-a", any()) }
            coVerify { rig.messages.markReceived("gm-b", any()) }
            coVerify(exactly = 0) { rig.messages.markReceived("dm-x", any()) }
            assertFalse(rig.msgMap.containsKey("tick-b1"))
            assertFalse(rig.originated.any { it.type == FrameType.RECEIPT })
        }

    @Test
    fun aDeliveredGroupMessageBatchesItsTickIntoCustodyWhenTheAuthorIsAbsent() =
        runTest {
            // End-to-end: a real group-ratchet delivery owes its tick; with the author absent and
            // sealed-capable, the tick batches and — once past the debounce — escalates through the
            // originate hook (production: sealed relay = true → custody/flood/spool) instead of any
            // unicast receipt.
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pinRatchetCapable(alice, RatchetCrypto.generateKeyPair().pub)
            val group = rig.seedRatchetGroup(alice)
            val author = GroupRatchetAuthor(alice, group.id)
            rig.deliver(
                alice,
                V2Author(
                    alice,
                    rig,
                ).dm("seed-t1", "", ctl = MessageContent.CTL_GROUP_KEY, gk = GroupKeyPayload(group.id, keys = listOf(author.seed()))),
            )
            val escalated = mutableListOf<Pair<String, List<String>>>()
            rig.canSealTick = { true }
            rig.originateTickHook = { authorId, ids ->
                escalated += authorId to ids
                true
            }

            rig.deliver(alice, author.groupFrame(group, "g-m1", "hello group"))

            assertEquals("hello group", rig.msgMap["g-m1"]?.body)
            assertTrue("no unicast tick while the batch debounces", rig.originated.none { it.type == FrameType.RECEIPT })
            assertTrue(escalated.isEmpty())

            rig.ackNowMs += AckSync.TICK_BATCH_DEBOUNCE_MS + 1
            rig.ackSync.retryPending()

            assertEquals(listOf(alice.nodeId to listOf("g-m1")), escalated)
        }

    @Test
    fun anIncapableAuthorsDmGetsTheCleartextReceiptAndSelfVaccinates() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice) // pinned but NOT ratchet-capable — the legacy population
            val env = rig.dmChat(alice, rig.self, id = "v1-dm1", body = "old style")

            rig.pipeline.onDeliver(alice.sign(env), env, alice.nodeId)

            assertEquals("old style", rig.msgMap["v1-dm1"]?.body)
            // Cleartext receipt flooded (the old author's build can read nothing else)…
            assertTrue(rig.originated.any { it.type == FrameType.RECEIPT })
            // …and our own custody copy followed the same rule every carrier applies to that receipt:
            // captured at delivery (we custody our own inbound DMs now), then vaccine-purged right out.
            assertFalse(rig.forwardStore.has("v1-dm1"))
            // The tombstone half: a stale carrier re-planting the delivered DM is refused.
            rig.forwardSync.onSeen(alice.sign(env), env, ForwardStore.ORIGIN_RELAY)
            assertFalse(rig.forwardStore.has("v1-dm1"))
        }

    @Test
    fun aReServedDeliveredDmReAcksInTheSealedForm() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pinRatchetCapable(alice, RatchetCrypto.generateKeyPair().pub)
            val author = V2Author(alice, rig)
            val dm = author.dm("v2-re1", "again")

            rig.deliver(alice, dm)
            rig.deliver(alice, dm) // a custody re-serve after genuine divergence: exists-gate path

            // Delivered once, re-acked per copy (the receipt custody is the recovery channel), both sealed.
            assertEquals("again", rig.msgMap["v2-re1"]?.body)
            assertFalse(rig.originated.any { it.type == FrameType.RECEIPT })
            assertEquals(2L, rig.metrics.snapshot().receiptsSealed)
        }

    @Test
    fun aSealedReceiptFromANonRecipientIsIgnored() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            // The acked DM is addressed to bob, not to alice — alice's receipt must not flip it.
            rig.msgMap["dm-bob"] = MessageEntity(id = "dm-bob", senderId = rig.self.nodeId, recipientId = "bob", body = "", sentAt = 1L)

            rig.deliver(alice, V2Author(alice, rig).dm("ctl-r2", "", ctl = MessageContent.CTL_RECEIPT, ack = "dm-bob"))

            coVerify(exactly = 0) { rig.messages.markReceived(any(), any()) }
        }

    @Test
    fun aSealedTickForAGroupMessageStillFlipsTheTick() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            // A group/broadcast message has no recipientId, so recipientOf is null — the null-allowing
            // guard (mirroring the cleartext path) is what lets the sealed AckSync tick land.
            rig.msgMap["gm-out"] = MessageEntity(id = "gm-out", senderId = rig.self.nodeId, conversationId = "g-1", body = "", sentAt = 1L)

            rig.deliver(alice, V2Author(alice, rig).dm("ctl-r3", "", ctl = MessageContent.CTL_RECEIPT, ack = "gm-out"))

            coVerify(exactly = 1) { rig.messages.markReceived("gm-out", DeliveryPlane.Nearby) }
        }

    // --- Per-recipient delivery: the receipt's acker, kept locally (message_receipts) ---
    //
    // The acker was always on the wire as the receipt's authenticated senderId; these pin that we now
    // store it, and — the part that matters — that storing it changes NOTHING about when the tick flips.
    // The tick's "≥1 recipient received it" rule is a wire semantic; the row is local bookkeeping, so the
    // row (and only the row) takes a roster gate the tick must never inherit.

    @Test
    fun aGroupMembersSealedTickRecordsWhichMemberItWas() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            rig.seedGroup("g-2", members = listOf(rig.self.nodeId, alice.nodeId), createdBy = rig.self.nodeId)
            rig.msgMap["gm-1"] =
                MessageEntity(id = "gm-1", senderId = rig.self.nodeId, conversationId = "g-2", body = "", sentAt = 1L)

            rig.deliver(alice, V2Author(alice, rig).dm("ctl-p1", "", ctl = MessageContent.CTL_RECEIPT, ack = "gm-1"))

            coVerify(exactly = 1) { rig.messages.markReceived("gm-1", DeliveryPlane.Nearby) }
            assertEquals(alice.nodeId, rig.receiptMap["gm-1" to alice.nodeId]?.ackerNodeId)
            assertEquals(DeliveryPlane.Nearby.code, rig.receiptMap["gm-1" to alice.nodeId]?.via)
        }

    @Test
    fun aNonMembersGroupTickStillFlipsTheTickButNamesNobody() =
        runTest {
            // The forged-ack guard's null arm accepts any signed sender, and it must keep doing so — that
            // null arm IS the group tick (ADR 033). What must NOT happen is a stranger writing themselves
            // into the roster list the author reads as "who has this".
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            rig.seedGroup("g-3", members = listOf(rig.self.nodeId, "sam"), createdBy = rig.self.nodeId)
            rig.msgMap["gm-2"] =
                MessageEntity(id = "gm-2", senderId = rig.self.nodeId, conversationId = "g-3", body = "", sentAt = 1L)

            rig.deliver(alice, V2Author(alice, rig).dm("ctl-p2", "", ctl = MessageContent.CTL_RECEIPT, ack = "gm-2"))

            coVerify(exactly = 1) { rig.messages.markReceived("gm-2", DeliveryPlane.Nearby) }
            assertTrue(rig.receiptMap.isEmpty())
        }

    @Test
    fun aBatchedSealedTickRecordsItsSenderAgainstEveryIdItCovers() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            rig.seedGroup("g-4", members = listOf(rig.self.nodeId, alice.nodeId), createdBy = rig.self.nodeId)
            rig.msgMap["gb-a"] =
                MessageEntity(id = "gb-a", senderId = rig.self.nodeId, conversationId = "g-4", body = "", sentAt = 1L)
            rig.msgMap["gb-b"] =
                MessageEntity(id = "gb-b", senderId = rig.self.nodeId, conversationId = "g-4", body = "", sentAt = 1L)

            rig.deliver(alice, V2Author(alice, rig).dm("ctl-p3", "", ctl = MessageContent.CTL_RECEIPT, acks = listOf("gb-a", "gb-b")))

            assertEquals(setOf("gb-a" to alice.nodeId, "gb-b" to alice.nodeId), rig.receiptMap.keys.toSet())
        }

    @Test
    fun aCleartextBroadcastReceiptRecordsItsSender() =
        runTest {
            // The public room has no roster to check against, so any signed peer counts — an open
            // "received by" list is the only honest shape there.
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            rig.msgMap["room-1"] =
                MessageEntity(
                    id = "room-1",
                    senderId = rig.self.nodeId,
                    conversationId = Conversations.NEARBY,
                    body = "",
                    sentAt = 1L,
                )
            val env = rig.receipt(alice, ackId = "room-1")

            rig.pipeline.onDeliver(alice.sign(env), env, alice.nodeId)

            assertEquals(alice.nodeId, rig.receiptMap["room-1" to alice.nodeId]?.ackerNodeId)
        }

    @Test
    fun aReServedReceiptKeepsTheFirstCrossingsTimeAndPlane() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            rig.seedGroup("g-5", members = listOf(rig.self.nodeId, alice.nodeId), createdBy = rig.self.nodeId)
            rig.msgMap["gm-3"] =
                MessageEntity(id = "gm-3", senderId = rig.self.nodeId, conversationId = "g-5", body = "", sentAt = 1L)
            val author = V2Author(alice, rig)

            rig.deliver(alice, author.dm("ctl-p4", "", ctl = MessageContent.CTL_RECEIPT, ack = "gm-3"))
            val first = rig.receiptMap.getValue("gm-3" to alice.nodeId)
            rig.nowMs += 60_000L
            rig.deliver(
                alice,
                author.dm("ctl-p5", "", ctl = MessageContent.CTL_RECEIPT, ack = "gm-3"),
                from = ScopeSync.SPOOL_SOURCE_PREFIX + "spool.example",
            )

            // First evidence wins on BOTH columns, the markReceived rule one table over: the row says when
            // the message got there and how, not every route its receipt has since travelled.
            assertEquals(first, rig.receiptMap.getValue("gm-3" to alice.nodeId))
            assertEquals(DeliveryPlane.Nearby.code, rig.receiptMap.getValue("gm-3" to alice.nodeId).via)
        }

    @Test
    fun aReceiptForAMessageWeDoNotHoldRecordsNothing() =
        runTest {
            // markReceived is already a harmless no-op for an unheld id; the row must be too, or a peer
            // could plant orphans keyed on ids we never sent.
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val env = rig.receipt(alice, ackId = "never-sent")

            rig.pipeline.onDeliver(alice.sign(env), env, alice.nodeId)

            assertTrue(rig.receiptMap.isEmpty())
        }

    @Test
    fun aSealedDmReceiptPulledOffASpoolMarksTheTickInternetDelivered() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            rig.msgMap["dm-out"] =
                MessageEntity(id = "dm-out", senderId = rig.self.nodeId, recipientId = alice.nodeId, body = "", sentAt = 1L)

            // The sealed receipt is the DM path's tick, so this is the case that normally paints the globe:
            // alice was nowhere near a radio and answered us across the spool.
            rig.deliver(
                alice,
                V2Author(alice, rig).dm("ctl-r4", "", ctl = MessageContent.CTL_RECEIPT, ack = "dm-out"),
                from = ScopeSync.SPOOL_SOURCE_PREFIX + "spool.example",
            )

            coVerify(exactly = 1) { rig.messages.markReceived("dm-out", DeliveryPlane.Internet) }
        }

    @Test
    fun aSealedDmReactionAppliesAndRetractsWithTheFramesSentAtAsTheLwwClock() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val author = V2Author(alice, rig)

            rig.deliver(alice, author.dm("ctl-x1", "", ctl = MessageContent.CTL_REACTION, rp = ReactionPayload("m1", "👍"), sentAt = 7L))
            rig.deliver(alice, author.dm("ctl-x2", "", ctl = MessageContent.CTL_REACTION, rp = ReactionPayload("m1"), sentAt = 9L))

            // Same table, same LWW clock as the cleartext path: reactor = authenticated sender,
            // updatedAt = the frame's signed sentAt; emoji null inside a present payload = retraction.
            coVerify(exactly = 1) { rig.reactions.apply(ReactionEntity("m1", alice.nodeId, "👍", 7L)) }
            coVerify(exactly = 1) { rig.reactions.apply(ReactionEntity("m1", alice.nodeId, null, 9L)) }
            assertFalse(rig.msgMap.containsKey("ctl-x1"))
            assertFalse(rig.originated.any { it.type == FrameType.RECEIPT })
            coVerify(exactly = 0) { rig.notifier.notify(any(), any(), any(), any(), any()) }
        }

    @Test
    fun aSealedGroupReactionAppliesInsideTheChainCommit() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val group = rig.seedRatchetGroup(alice)
            val author = GroupRatchetAuthor(alice, group.id)
            rig.deliver(
                alice,
                V2Author(
                    alice,
                    rig,
                ).dm("seed-x", "", ctl = MessageContent.CTL_GROUP_KEY, gk = GroupKeyPayload(group.id, keys = listOf(author.seed()))),
            )

            rig.deliver(
                alice,
                author.groupFrame(
                    group,
                    id = "ctl-gx1",
                    body = "",
                    ctl = MessageContent.CTL_REACTION,
                    rp = ReactionPayload("gm7", "🔥"),
                    sentAt = 11L,
                ),
            )

            coVerify(exactly = 1) { rig.reactions.apply(ReactionEntity("gm7", alice.nodeId, "🔥", 11L)) }
            // The chain advanced through the commit (n consumed), and the ctl kept its contract:
            // never persisted, never notified, and no delivery tick owed for machinery.
            assertFalse(rig.msgMap.containsKey("ctl-gx1"))
            coVerify(exactly = 0) { rig.notifier.notify(any(), any(), any(), any(), any()) }
            assertFalse(rig.originated.any { it.type == FrameType.RECEIPT })
        }

    @Test
    fun anOversizedSealedDmReactionAdvancesTheChainAndAppliesNothing() =
        runTest {
            // The sealed twin of the cleartext refusal: the ratchet consumed the frame (the next one from
            // the same author still opens), the row was never written, and the drop is counted.
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val author = V2Author(alice, rig)

            rig.deliver(
                alice,
                author.dm("ctl-o1", "", ctl = MessageContent.CTL_REACTION, rp = ReactionPayload("m1", "👍".repeat(17)), sentAt = 7L),
            )
            rig.deliver(alice, author.dm("ctl-o2", "", ctl = MessageContent.CTL_REACTION, rp = ReactionPayload("m1", "👍"), sentAt = 9L))

            coVerify(exactly = 1) { rig.reactions.apply(any()) }
            coVerify(exactly = 1) { rig.reactions.apply(ReactionEntity("m1", alice.nodeId, "👍", 9L)) }
            assertEquals(1L, rig.drops(DropReason.REACTION_REFUSED))
            assertFalse(rig.msgMap.containsKey("ctl-o1"))
        }

    @Test
    fun anOversizedSealedGroupReactionAppliesNothingInsideTheChainCommit() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val group = rig.seedRatchetGroup(alice)
            val author = GroupRatchetAuthor(alice, group.id)
            rig.deliver(
                alice,
                V2Author(
                    alice,
                    rig,
                ).dm("seed-o", "", ctl = MessageContent.CTL_GROUP_KEY, gk = GroupKeyPayload(group.id, keys = listOf(author.seed()))),
            )

            rig.deliver(
                alice,
                author.groupFrame(
                    group,
                    id = "ctl-go1",
                    body = "",
                    ctl = MessageContent.CTL_REACTION,
                    rp = ReactionPayload("gm7", " "),
                    sentAt = 11L,
                ),
            )
            rig.deliver(
                alice,
                author.groupFrame(
                    group,
                    id = "ctl-go2",
                    body = "",
                    ctl = MessageContent.CTL_REACTION,
                    rp = ReactionPayload("gm7", "🔥"),
                    sentAt = 12L,
                ),
            )

            coVerify(exactly = 1) { rig.reactions.apply(any()) }
            coVerify(exactly = 1) { rig.reactions.apply(ReactionEntity("gm7", alice.nodeId, "🔥", 12L)) }
            assertEquals(1L, rig.drops(DropReason.REACTION_REFUSED))
        }

    @Test
    fun aSealedV3ReactionWithTheLongestRgiSequenceApplies() =
        runTest {
            // The v3 compact layout raw-encodes the message id but keeps the emoji a text string: a 35-byte
            // sequence round-trips untouched.
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val author = V2Author(alice, rig)
            val target = FrameId.new() // the compact form needs a canonical id to raw-encode

            rig.deliver(
                alice,
                author.dm(
                    "ctl-v3l",
                    "",
                    ctl = MessageContent.CTL_REACTION,
                    rp = ReactionPayload(target, LONGEST_RGI),
                    sentAt = 7L,
                    v3 = true,
                ),
            )

            coVerify(exactly = 1) { rig.reactions.apply(ReactionEntity(target, alice.nodeId, LONGEST_RGI, 7L)) }
            assertEquals(0L, rig.drops(DropReason.REACTION_REFUSED))
        }

    @Test
    fun aBlockedPeersSealedCtlDiesAtTheBlockedGate() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            rig.settings.blocked.value = setOf(alice.nodeId)

            rig.deliver(alice, V2Author(alice, rig).dm("ctl-b1", "", ctl = MessageContent.CTL_REACTION, rp = ReactionPayload("m1", "👍")))

            coVerify(exactly = 0) { rig.reactions.apply(any()) }
        }

    @Test
    fun anUnknownCtlCodeAdvancesTheChainAndDoesNothing() =
        runTest {
            // The forward-compat contract that lets ctl values ship additively: a build without a code
            // (a ratchet-era lab build seeing CTL_RECEIPT, or this build seeing a future 99) consumes
            // the frame as a silent no-op — chain advanced, nothing persisted, notified, or acked.
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val group = rig.seedRatchetGroup(alice)
            val author = GroupRatchetAuthor(alice, group.id)
            val v2 = V2Author(alice, rig)
            rig.deliver(
                alice,
                v2.dm("seed-u", "", ctl = MessageContent.CTL_GROUP_KEY, gk = GroupKeyPayload(group.id, keys = listOf(author.seed()))),
            )

            rig.deliver(alice, author.groupFrame(group, id = "ctl-u1", body = "", ctl = 99))
            rig.deliver(alice, v2.dm("ctl-u2", "", ctl = 99))
            rig.deliver(alice, v2.dm("dm-after", "still alive"))

            // Both forms advanced their state (the later DM opens against the same session), and the
            // unknown ctl frames left no trace.
            assertEquals("still alive", rig.msgMap["dm-after"]?.body)
            assertFalse(rig.msgMap.containsKey("ctl-u1"))
            assertFalse(rig.msgMap.containsKey("ctl-u2"))
            coVerify(exactly = 0) { rig.notifier.notify(any(), any(), any(), any(), any()) }
        }

    @Test
    fun aGroupRatchetFrameAfterItsSeedDecryptsAndDelivers() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val group = rig.seedRatchetGroup(alice)
            val author = GroupRatchetAuthor(alice, group.id)
            rig.deliver(
                alice,
                V2Author(
                    alice,
                    rig,
                ).dm("seed1", "", ctl = MessageContent.CTL_GROUP_KEY, gk = GroupKeyPayload(group.id, keys = listOf(author.seed()))),
            )

            rig.deliver(alice, author.groupFrame(group, id = "gm1", body = "sealed team hi"))

            assertEquals("sealed team hi", rig.msgMap["gm1"]?.body)
            assertEquals(group.id, rig.msgMap["gm1"]?.conversationId)
        }

    @Test
    fun aGroupRatchetFrameBeforeItsSeedDropsNoKeyAndStillCustodies() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val group = rig.seedRatchetGroup(alice)
            val author = GroupRatchetAuthor(alice, group.id)

            rig.deliver(alice, author.groupFrame(group, id = "gm-early", body = "too soon"))

            assertFalse(rig.msgMap.containsKey("gm-early"))
            assertEquals(1L, rig.drops(DropReason.GROUP_RATCHET_NO_KEY))
            // Delivery-local: the frame still custodied for members who CAN read it.
            assertTrue(rig.forwardStore.has("gm-early"))
        }

    @Test
    fun outOfOrderGroupFramesGapFillAndConsumeSkippedKeys() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val group = rig.seedRatchetGroup(alice)
            val author = GroupRatchetAuthor(alice, group.id)
            rig.deliver(
                alice,
                V2Author(
                    alice,
                    rig,
                ).dm("seed1", "", ctl = MessageContent.CTL_GROUP_KEY, gk = GroupKeyPayload(group.id, keys = listOf(author.seed()))),
            )
            val frames = (0..2).map { author.groupFrame(group, id = "gm$it", body = "msg $it") }

            rig.deliver(alice, frames[2])
            rig.deliver(alice, frames[0])
            rig.deliver(alice, frames[1])

            assertEquals("msg 0", rig.msgMap["gm0"]?.body)
            assertEquals("msg 1", rig.msgMap["gm1"]?.body)
            assertEquals("msg 2", rig.msgMap["gm2"]?.body)
            // Both banked keys were consumed on use.
            assertTrue(rig.groupRatchetStore.skippedKeys(group.id, alice.nodeId, 1, 0).isEmpty())
            assertTrue(rig.groupRatchetStore.skippedKeys(group.id, alice.nodeId, 1, 1).isEmpty())
        }

    @Test
    fun aReServedGroupFrameShortCircuitsAtTheExistsGate() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val group = rig.seedRatchetGroup(alice)
            val author = GroupRatchetAuthor(alice, group.id)
            rig.deliver(
                alice,
                V2Author(
                    alice,
                    rig,
                ).dm("seed1", "", ctl = MessageContent.CTL_GROUP_KEY, gk = GroupKeyPayload(group.id, keys = listOf(author.seed()))),
            )
            val frame = author.groupFrame(group, id = "gm-r", body = "once")
            rig.deliver(alice, frame)

            rig.deliver(alice, frame)

            assertEquals("once", rig.msgMap["gm-r"]?.body)
            assertEquals(0L, rig.drops(DropReason.GROUP_RATCHET_DUPLICATE))
            assertEquals(0L, rig.drops(DropReason.GROUP_RATCHET_AEAD_FAIL))
        }

    @Test
    fun aReMintedSeedIsAdoptedAndTheOldEraStillDrains() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val group = rig.seedRatchetGroup(alice)
            val author = GroupRatchetAuthor(alice, group.id)
            rig.deliver(
                alice,
                V2Author(
                    alice,
                    rig,
                ).dm("seed1", "", ctl = MessageContent.CTL_GROUP_KEY, gk = GroupKeyPayload(group.id, keys = listOf(author.seed()))),
            )
            val preWipe = author.groupFrame(group, id = "gm-old", body = "old era")

            author.wipeAndRemint(at = 60_000L)
            rig.deliver(
                alice,
                V2Author(
                    alice,
                    rig,
                    at = 60_000L,
                ).dm("seed2", "", ctl = MessageContent.CTL_GROUP_KEY, gk = GroupKeyPayload(group.id, keys = listOf(author.seed()))),
            )
            rig.deliver(alice, author.groupFrame(group, id = "gm-new", body = "new era"))
            rig.deliver(alice, preWipe)

            // Both eras of epoch 1 decrypt: the re-mint on its fresh chain, the old frame on the drain.
            assertEquals("new era", rig.msgMap["gm-new"]?.body)
            assertEquals("old era", rig.msgMap["gm-old"]?.body)
            assertEquals(2, rig.groupRatchetStore.recvChains(group.id, alice.nodeId, 1).size)
        }

    @Test
    fun aSeedFromANonMemberAdoptsNothing() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            val carol = party()
            rig.pin(alice)
            rig.pin(carol)
            val group = rig.seedRatchetGroup(alice) // carol is NOT in the roster
            val author = GroupRatchetAuthor(carol, group.id)

            rig.deliver(
                carol,
                V2Author(
                    carol,
                    rig,
                ).dm("seed-c", "", ctl = MessageContent.CTL_GROUP_KEY, gk = GroupKeyPayload(group.id, keys = listOf(author.seed()))),
            )

            assertTrue(rig.groupRatchetStore.recvChains(group.id, carol.nodeId, 1).isEmpty())
            // No adoption ⇒ no ack DM back to carol either.
            assertFalse(rig.originated.any { it.type == FrameType.CHAT && it.recipientId == carol.nodeId })
        }

    @Test
    fun aGroupRatchetFrameForALeftGroupIsRefusedBeforeDecrypt() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val members = listOf(rig.self.nodeId, alice.nodeId)
            val id = Conversations.groupIdFor(members)
            rig.seedGroup(id, members = members, createdBy = alice.nodeId, left = true)
            val group = rig.group(members = members, createdBy = alice.nodeId)
            val author = GroupRatchetAuthor(alice, id)

            rig.deliver(alice, author.groupFrame(group, id = "gm-left", body = "gone"))

            assertFalse(rig.msgMap.containsKey("gm-left"))
            assertEquals(0L, rig.drops(DropReason.GROUP_RATCHET_NO_KEY))
        }

    @Test
    fun aGroupAddressedEnvelopeWithoutTheGroupHeaderIsMalformed() =
        runTest {
            // A group-addressed v2 envelope routes to the group form, which requires `g`: header-less
            // is malformed by construction (regression pin on the routing + gate).
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val group = rig.seedRatchetGroup(alice)
            val env =
                RelayEnvelope(
                    type = FrameType.CHAT,
                    id = "v2-grouped",
                    senderId = alice.nodeId,
                    sentAt = 5L,
                    group = group,
                    payload =
                        WireCodec.encodePayload(
                            ChatContent(
                                enc =
                                    EncEnvelope(
                                        v = EncEnvelope.VERSION_RATCHET,
                                        nonce = ByteArray(12),
                                        ct = ByteArray(4),
                                        keys = emptyList(),
                                    ),
                            ),
                        ),
                )

            rig.deliver(alice, env)

            assertEquals(1L, rig.drops(DropReason.GROUP_RATCHET_BAD_HEADER))
        }

    @Test
    fun aDmAddressedEnvelopeWithOnlyAGroupHeaderIsMalformed() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val env =
                RelayEnvelope(
                    type = FrameType.CHAT,
                    id = "gdm",
                    senderId = alice.nodeId,
                    sentAt = 5L,
                    recipientId = rig.self.nodeId,
                    payload =
                        WireCodec.encodePayload(
                            ChatContent(
                                enc =
                                    EncEnvelope(
                                        v = EncEnvelope.VERSION_RATCHET,
                                        nonce = ByteArray(12),
                                        ct = ByteArray(4),
                                        keys = emptyList(),
                                        g = GroupRatchetHeader(se = 1, n = 0),
                                    ),
                            ),
                        ),
                )

            rig.deliver(alice, env)

            // A DM routes to the DM form, which requires `r` — a stray `g` doesn't make it a group frame.
            assertEquals(1L, rig.drops(DropReason.RATCHET_BAD_HEADER))
            assertFalse(rig.msgMap.containsKey("gdm"))
        }

    @Test
    fun aV3GroupAddressedEnvelopeIsABadHeaderNeverAGroupFrame() =
        runTest {
            // v3 is the DM form only (ADR 059): a group-addressed v3 envelope, even one carrying a
            // well-formed `g`, routes to the DM arm and is refused there as structural — before the group
            // engine, the group key-request heuristic, and the reset heuristic. This is the executable
            // half of "a compact group form takes v4": a v3 build drops it rather than misrouting it.
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val group = rig.seedRatchetGroup(alice)

            fun chatFramesToAlice() = rig.originated.count { it.type == FrameType.CHAT && it.recipientId == alice.nodeId }

            // Three distinct ids: enough to fire the group key-request heuristic, were it ever reached.
            repeat(3) { i ->
                rig.deliver(
                    alice,
                    RelayEnvelope(
                        type = FrameType.CHAT,
                        id = "v3-grouped-$i",
                        senderId = alice.nodeId,
                        sentAt = 5L,
                        group = group,
                        payload =
                            WireCodec.encodePayload(
                                ChatContent(
                                    enc =
                                        EncEnvelope(
                                            v = EncEnvelope.VERSION_DM_V3,
                                            nonce = ByteArray(0),
                                            ct = ByteArray(4),
                                            keys = emptyList(),
                                            g = GroupRatchetHeader(se = 1, n = i),
                                        ),
                                ),
                            ),
                    ),
                )
            }

            assertEquals(3L, rig.drops(DropReason.RATCHET_BAD_HEADER))
            assertEquals(0L, rig.drops(DropReason.GROUP_RATCHET_BAD_HEADER))
            assertEquals(0L, rig.drops(DropReason.GROUP_RATCHET_AEAD_FAIL))
            assertEquals(0L, rig.drops(DropReason.GROUP_RATCHET_NO_KEY))
            assertEquals(0, chatFramesToAlice())
            assertEquals(0, rig.resetsSent())
            assertTrue(rig.msgMap.isEmpty())
        }

    @Test
    fun threeDistinctUndecryptableGroupFramesTriggerOneRateLimitedKeyRequest() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            rig.pinRatchetCapable(alice, RatchetCrypto.generateKeyPair().pub) // request needs cap + prekey
            val group = rig.seedRatchetGroup(alice)
            val author = GroupRatchetAuthor(alice, group.id)

            fun chatFramesToAlice() = rig.originated.count { it.type == FrameType.CHAT && it.recipientId == alice.nodeId }
            val fresh = System.currentTimeMillis() // the pipeline age-gates on the real clock

            // Two distinct undecryptable frames: heuristic not yet satisfied.
            rig.deliver(alice, author.groupFrame(group, id = "nk1", body = "x", sentAt = fresh))
            rig.deliver(alice, author.groupFrame(group, id = "nk2", body = "x", sentAt = fresh))
            assertEquals(0, chatFramesToAlice())

            // The third distinct id fires exactly one CTL_GROUP_KEY_REQ…
            rig.deliver(alice, author.groupFrame(group, id = "nk3", body = "x", sentAt = fresh))
            assertEquals(1, chatFramesToAlice())

            // …and further undecryptable frames inside the floor add nothing.
            rig.deliver(alice, author.groupFrame(group, id = "nk4", body = "x", sentAt = fresh))
            rig.deliver(alice, author.groupFrame(group, id = "nk5", body = "x", sentAt = fresh))
            rig.deliver(alice, author.groupFrame(group, id = "nk6", body = "x", sentAt = fresh))
            assertEquals(1, chatFramesToAlice())
        }

    @Test
    fun ancientUndecryptableFramesNeverBurnTheKeyRequestBudget() =
        runTest {
            // Replay of frames older than the age window (their epoch is legitimately swept everywhere)
            // must not feed the heuristic — the custody dead-on-arrival guard applied to recovery.
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            rig.pinRatchetCapable(alice, RatchetCrypto.generateKeyPair().pub)
            val group = rig.seedRatchetGroup(alice)
            val author = GroupRatchetAuthor(alice, group.id)

            repeat(4) { i ->
                // sentAt = 1L: far past REQUEST_MAX_FRAME_AGE_MS relative to the pipeline's wall clock.
                rig.deliver(alice, author.groupFrame(group, id = "old$i", body = "x", sentAt = 1L))
            }

            assertEquals(0, rig.originated.count { it.type == FrameType.CHAT && it.recipientId == alice.nodeId })
            assertTrue(rig.drops(DropReason.GROUP_RATCHET_NO_KEY) >= 4L)
        }

    @Test
    fun aKeyRequestCtlInvokesTheRedistributionLambda() =
        runTest {
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val group = rig.seedRatchetGroup(alice)

            rig.deliver(
                alice,
                V2Author(alice, rig).dm("req1", "", ctl = MessageContent.CTL_GROUP_KEY_REQ, gk = GroupKeyPayload(group.id)),
            )

            assertEquals(listOf(group.id to alice.nodeId), rig.redistributed)
            assertFalse(rig.msgMap.containsKey("req1"))
        }

    @Test
    fun aSessionResetAlsoReSendsGroupSeeds() =
        runTest {
            // ctl frames are never persisted, so resealRecentDmsTo can't recover a seed DM the wiped
            // peer lost — the reset hook is the wipe-side seed plane.
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)

            rig.deliver(alice, V2Author(alice, rig).dm("est", "hello"))
            rig.deliver(alice, V2Author(alice, rig, at = 60_000L).dm("rst", "", ctl = MessageContent.CTL_SESSION_RESET))

            assertEquals(listOf(alice.nodeId), rig.resealed)
            // Forced: the peer's pre-wipe ack of our current epoch is exactly the stale outbox state
            // that would otherwise swallow this re-send (the acked-epoch guard in
            // MeshManager.flushPendingGroupKeysFor — bypass pinned there by
            // aForcedFlushBypassesTheStaleAckGuardAfterAPeerWipe).
            assertEquals(listOf(alice.nodeId to true), rig.groupKeysFlushed)
        }

    @Test
    fun aSeedAdoptionReplaysOurCustodyForThatGroupAndSender() =
        runTest {
            // A group frame that arrives before its seed is custodied by US — and a frame we already
            // hold is never re-served by a peer (no digest divergence), so adoption must trigger the
            // local replay half of the re-serve heal (found by the first on-device smoke).
            val rig = Rig(backgroundScope)
            val alice = party()
            rig.pin(alice)
            val group = rig.seedRatchetGroup(alice)
            val author = GroupRatchetAuthor(alice, group.id)

            rig.deliver(
                alice,
                V2Author(
                    alice,
                    rig,
                ).dm("seed1", "", ctl = MessageContent.CTL_GROUP_KEY, gk = GroupKeyPayload(group.id, keys = listOf(author.seed()))),
            )

            assertEquals(listOf<Pair<String?, String?>>(group.id to alice.nodeId), rig.custodyReplays)
        }

    private companion object {
        const val HYBRID_TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM_RAW"

        /** Era/skew offsets in the reset-heuristic cases are all whole hours. */
        const val HOUR_MS = 60 * 60_000L

        /** The prekey id the Rig's fake identity serves (mirrors IdentityKeyStore.prekeyPrivFor). */
        const val SPK_ID = 1

        /** 👩🏽‍❤️‍💋‍👨🏼 — the longest RGI emoji sequence Unicode ships: 10 code points, 15 UTF-16 units, 35 B UTF-8. */
        const val LONGEST_RGI = "\uD83D\uDC69\uD83C\uDFFD\u200D\u2764\uFE0F\u200D\uD83D\uDC8B\u200D\uD83D\uDC68\uD83C\uDFFC"
    }
}
