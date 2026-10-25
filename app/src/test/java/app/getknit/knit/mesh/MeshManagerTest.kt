package app.getknit.knit.mesh

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import app.getknit.knit.data.AttachmentIdentities
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.MeshBlobStore
import app.getknit.knit.data.MessageReceiptRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.VoiceAudio
import app.getknit.knit.data.crypto.SignedPrekey
import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.group.toFoundingInfo
import app.getknit.knit.data.message.AttachmentFetchRow
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MentionStore
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.MissingAttachment
import app.getknit.knit.data.peer.MetPeerRepository
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.ratchet.GroupRatchetRepository
import app.getknit.knit.data.ratchet.GroupRootRepository
import app.getknit.knit.data.ratchet.RatchetRepository
import app.getknit.knit.data.reaction.ReactionEntity
import app.getknit.knit.data.settings.LoraBoard
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.TinkInit
import app.getknit.knit.mesh.crypto.b64
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetSessions
import app.getknit.knit.mesh.crypto.ratchet.RatchetCrypto
import app.getknit.knit.mesh.crypto.ratchet.RatchetEngine
import app.getknit.knit.mesh.crypto.ratchet.RatchetSessions
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.lora.LoraSizeHint
import app.getknit.knit.mesh.protocol.BlobReqContent
import app.getknit.knit.mesh.protocol.BodyStyle
import app.getknit.knit.mesh.protocol.CallPayload
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.CommonsPost
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.GroupKeyPayload
import app.getknit.knit.mesh.protocol.GroupSeed
import app.getknit.knit.mesh.protocol.LinkPreviewBlob
import app.getknit.knit.mesh.protocol.Mention
import app.getknit.knit.mesh.protocol.ProfileContent
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.RatchetHeader
import app.getknit.knit.mesh.protocol.ReactionContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.mesh.protocol.StyleSpan
import app.getknit.knit.mesh.protocol.TransferPayload
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.spool.CommonsMember
import app.getknit.knit.mesh.spool.CommonsRoom
import app.getknit.knit.mesh.spool.CommonsRoots
import app.getknit.knit.mesh.spool.CommonsStore
import app.getknit.knit.mesh.spool.GroupRootStore
import app.getknit.knit.mesh.spool.ScopeFrames
import app.getknit.knit.mesh.spool.hex
import app.getknit.knit.moderation.ImageScreeningService
import app.getknit.knit.moderation.ScopedTextModerator
import app.getknit.knit.moderation.TextVerdict
import app.getknit.knit.notifications.Notifier
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
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
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
@Suppress("LargeClass") // cohesive single-SUT suite over one shared Rig; splitting would scatter it, as InboundPipelineTest
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
        val hybrid = KeysetHandle.generateNew(KeyTemplates.get(HYBRID_TEMPLATE).toParameters(), RegistryConfiguration.get())
        val sig = KeysetHandle.generateNew(KeyTemplates.get("ED25519_RAW").toParameters(), RegistryConfiguration.get())
        return Party(MessageCrypto(hybrid, sig), PublicKeyBundle.fromPrivate(hybrid, sig))
    }

    /** A [MeshTransport] that records every frame the manager originates (both flood + fast-fanout copies). */
    private class RecordingTransport(
        scope: CoroutineScope,
    ) : MeshTransport {
        // Appended by the manager's session coroutines (a real Default thread) while a test's `await` poll
        // reads them from another: snapshot-iterated, so a read never trips over a write in flight.
        val sent = CopyOnWriteArrayList<Pair<WireEnvelope, Peer?>>()
        val longRangeFanouts = CopyOnWriteArrayList<WireEnvelope>()
        val longRangeHints = CopyOnWriteArrayList<FanoutHint>()
        val fastFanouts = CopyOnWriteArrayList<WireEnvelope>()
        val fastSends = CopyOnWriteArrayList<Pair<WireEnvelope, Peer>>()
        val internetCovers = CopyOnWriteArrayList<Set<String>>()

        /** Drivable, so a test can bring a peer into range (the met-peers watcher reads the nearby set). */
        val nearby = MutableStateFlow<Set<Peer>>(emptySet())

        // Per live collector of [neighbors], the set it was last handed, and the one whose body it last returned from.
        private val handed = ConcurrentHashMap<Any, Set<Peer>>()
        private val handled = ConcurrentHashMap<Any, Set<Peer>>()

        /** [nearby] with the `StateFlow` contract kept, recording each collector's hand-off and its return. */
        @OptIn(ExperimentalForInheritanceCoroutinesApi::class) // value, replayCache and collect are the whole contract
        override val neighbors: StateFlow<Set<Peer>> =
            object : StateFlow<Set<Peer>> {
                override val value: Set<Peer> get() = nearby.value
                override val replayCache: List<Set<Peer>> get() = nearby.replayCache

                override suspend fun collect(collector: FlowCollector<Set<Peer>>): Nothing {
                    val token = Any()
                    try {
                        nearby.collect {
                            handed[token] = it
                            collector.emit(it)
                            handled[token] = it
                        }
                    } finally {
                        handed.remove(token)
                        handled.remove(token)
                    }
                }
            }

        /** Every collector of [neighbors] has been handed [links] — one busy elsewhere may not have acted on it yet. */
        fun everyCollectorHanded(links: Set<Peer>): Boolean = handed.isNotEmpty() && handed.values.all { it == links }

        /** Every collector of [neighbors] has returned from [links]. */
        fun everyCollectorHandled(links: Set<Peer>): Boolean = handed.isNotEmpty() && handed.keys.all { handled[it] == links }

        /** Peers sighted but not linked — an author heard only over a board. [reachable] is the union. */
        val sighted = MutableStateFlow<Set<Peer>>(emptySet())
        override val reachable: StateFlow<Set<Peer>> =
            combine(nearby, sighted) { linked, seen -> linked + seen }.stateIn(scope, SharingStarted.Eagerly, emptySet())
        override val health = MutableStateFlow(TransportHealth.Healthy).asStateFlow()
        override val inbound = MutableSharedFlow<InboundFrame>().asSharedFlow()
        override val incomingFiles = emptyFlow<ReceivedFile>()

        override fun start() = Unit

        override fun stop() = Unit

        /** How often the manager poked the radios — every [MeshManager.heal], basket or no basket. */
        val heals = AtomicInteger()

        override fun heal() {
            heals.incrementAndGet()
        }

        /** Runs inside [send], before it records — what a test reads at the moment a frame goes on the air. */
        @Volatile
        var onSend: suspend (WireEnvelope) -> Unit = {}

        override suspend fun send(
            wire: WireEnvelope,
            to: Peer?,
        ) {
            onSend(wire)
            sent += wire to to
        }

        override fun fastFanout(wire: WireEnvelope) {
            fastFanouts += wire
        }

        override fun fastSend(
            wire: WireEnvelope,
            to: Peer,
        ) {
            fastSends += wire to to
        }

        override fun coveredByInternet(peers: Set<String>) {
            internetCovers += peers
        }

        override fun longRangeFanout(
            wire: WireEnvelope,
            hint: FanoutHint,
        ) {
            longRangeFanouts += wire
            longRangeHints += hint
        }

        override suspend fun sendFile(
            file: File,
            to: Peer,
            meta: FileMeta,
        ): Boolean = true

        /** Whom each custody digest went to, in order — the newcomer batch's digest and the re-offer round's. */
        val digestsTo = CopyOnWriteArrayList<String>()

        override suspend fun sendDigest(
            to: Peer,
            ids: List<String>,
        ) {
            digestsTo += to.nodeId
        }
    }

    /** Minimal in-memory [ForwardStore] so a test can assert what the send path captured for custody. */
    private class FakeForwardStore : ForwardStore {
        // Written from the manager's session coroutines and read by a test's `await` poll on another thread.
        private val frames = linkedMapOf<String, CarriedFrame>()

        override suspend fun store(
            frame: CarriedFrame,
            origin: Int,
            now: Long,
        ): Boolean {
            synchronized(frames) { frames.putIfAbsent(frame.envelope.id, frame) }
            return true
        }

        override suspend fun liveFrames(now: Long): List<CarriedFrame> = frames()

        /** How often the custody replay asked for the group chat — the heal basket's hourly step (work item #63). */
        val groupChatReads = AtomicInteger()

        override suspend fun liveGroupChatFrames(
            excludingSender: String,
            now: Long,
        ): List<CarriedFrame> {
            groupChatReads.incrementAndGet()
            return super.liveGroupChatFrames(excludingSender, now)
        }

        /** While set, the custody id read throws: the digest a link-up or a re-offer round sends fails with it (#206). */
        @Volatile
        var liveIdsFail = false

        /** While set, the custody id read waits for it: a digest held mid-pass. [liveIdsWaiting] counts the reads held. */
        @Volatile
        var liveIdsGate: CompletableDeferred<Unit>? = null
        val liveIdsWaiting = AtomicInteger()

        override suspend fun liveIds(now: Long): List<String> {
            if (liveIdsFail) throw IOException("the custody read failed")
            liveIdsGate?.let {
                liveIdsWaiting.incrementAndGet()
                it.await()
            }
            return synchronized(frames) { frames.keys.toList() }
        }

        /** Carried frames naming an attachment we lack, as a test sets them (ADR 2026-10.js2p's custody re-arm). */
        @Volatile
        var carried: List<CarriedAttachment> = emptyList()

        /** Hashes custody's first read names and its per-frame read no longer has: a frame trimmed between the two (#141). */
        @Volatile
        var trimmed: Map<String, String> = emptyMap()

        // The trimmed hashes go first: the re-arm steps its wants in this order, so a test whose control is the last
        // carried hash has stepped every held one by the time its ask is seen.
        override suspend fun attachmentsNeedingFetch(now: Long): Map<String, String> =
            trimmed + carried.associate { it.hash to (it.senderId ?: "") }

        override suspend fun carriedAttachments(now: Long): List<CarriedAttachment> = carried

        override suspend fun recipientOf(id: String): String? = synchronized(frames) { frames[id]?.envelope?.recipientId }

        override suspend fun has(id: String): Boolean = synchronized(frames) { frames.containsKey(id) }

        override suspend fun remove(id: String) {
            synchronized(frames) { frames.remove(id) }
        }

        /** How often the TTL sweep — the heal basket's first step — ran; set [holdSweep] to park a basket there. */
        val sweeps = AtomicInteger()
        var holdSweep: CompletableDeferred<Unit>? = null

        override suspend fun sweepExpired(now: Long): Int {
            sweeps.incrementAndGet()
            holdSweep?.await()
            return 0
        }

        /** Insertion-ordered view of everything custodied, for asserting what a re-serve would hand over. */
        fun frames(): List<CarriedFrame> = synchronized(frames) { frames.values.toList() }
    }

    /** The real [GroupRootRepository] with its retention sweep counted — the heal basket's hourly step (work item #63). */
    private class CountingGroupRoots(
        private val delegate: GroupRootStore,
    ) : GroupRootStore by delegate {
        val sweeps = AtomicInteger()

        override suspend fun sweep(now: Long) {
            sweeps.incrementAndGet()
            delegate.sweep(now)
        }
    }

    /** An in-memory [CommonsStore]: one joined room, everything the manager writes to it recorded. */
    private class FakeCommonsStore(
        val conversationId: String,
        val secret: ByteArray,
    ) : CommonsStore {
        val posted = mutableListOf<Triple<MessageEntity, ByteArray, ByteArray>>()
        val reacted = mutableListOf<Pair<String, CarriedFrame>>()
        val members = mutableListOf<Pair<String, String>>()
        var swept: Long? = null

        override suspend fun roots(): List<CommonsRoots> = listOf(CommonsRoots(conversationId, "wss://home.test/spool/v1", secret))

        override suspend fun find(conversationId: String): CommonsRoom? =
            if (conversationId == this.conversationId) CommonsRoom(conversationId, "wss://home.test/spool/v1", secret, "Home") else null

        override suspend fun frames(conversationId: String): List<CarriedFrame> = emptyList()

        override suspend fun post(
            row: MessageEntity,
            sig: ByteArray,
            signed: ByteArray,
        ) {
            posted += Triple(row, sig, signed)
        }

        override suspend fun postReaction(
            conversationId: String,
            messageId: String,
            frameId: String,
            sentAt: Long,
            sig: ByteArray,
            signed: ByteArray,
        ) {
            reacted += messageId to CarriedFrame(envelope = WireCodec.decodeEnvelope(signed)!!, sig = sig, signed = signed)
        }

        override suspend fun recordMember(
            conversationId: String,
            nodeId: String,
            now: Long,
        ) {
            members += conversationId to nodeId
        }

        override suspend fun allMembers(): List<CommonsMember> = members.map { CommonsMember(it.first, it.second, 0L) }

        override suspend fun sweepOutbox(before: Long) {
            swept = before
        }
    }

    /** The manager under test, wired with real crypto + a recording transport + real custody + mocked repos. */
    private inner class Rig(
        private val scope: CoroutineScope,
        val commons: FakeCommonsStore? = null,
    ) {
        val me = party()
        val bob = party()
        val transport = RecordingTransport(scope)
        val forwardStore = FakeForwardStore()
        val messages = mockk<MessageRepository>(relaxed = true)
        val receipts = mockk<MessageReceiptRepository>(relaxed = true)
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
        val metrics = MeshMetrics()

        /**
         * The manager's clock. A `var` so a test that needs two *distinguishable* instants can advance it;
         * it starts at [now], so every test that never touches it sees the original fixed clock.
         */
        var clockNow = now

        /**
         * The avatar clock [MeshManager.watchProfileChanges] combines with the name and status. Hoisted
         * because nothing else in the manager collects it, which makes its `subscriptionCount` an exact
         * signal that the watcher is up — see [awaitProfileWatcher].
         */
        val avatarUpdatedAt = MutableStateFlow(0L)

        /** The own open-to-chat flag, the fourth input of the profile watcher's combine. */
        val openToChat = MutableStateFlow(false)

        /**
         * The status line, the second input — a replaying shared flow rather than a state flow, so a test
         * can re-emit an *equal* value: every [SettingsStore] projection is a `map` over the one DataStore,
         * which re-emits on a write to any key, and a `MutableStateFlow` dedupes exactly the case that
         * matters.
         */
        val status = MutableSharedFlow<String>(replay = 1).apply { tryEmit("") }

        /**
         * The bound LoRa board, the fifth input — null until one reports in. One [SettingsStore.loraBoard]
         * value per edit, as on a phone: a bind or an unbind reaches the watcher as exactly one emission of
         * one collector, so a test that saw the bind's flood knows the watcher holds the board before it
         * clears it. (Two projections of this flow gave the watcher two collectors, and the one that missed
         * the bind saw the unbind as no change — the clear step's CI flake.)
         */
        val loraBoard = MutableStateFlow<LoraBoard?>(null)

        // Hoisted so a test can pre-shape group-ratchet state (e.g. a stale outbox ack) around the
        // manager's own send/flush paths — same instance the manager is wired with below.
        val groupRatchet = GroupRatchetSessions(store = GroupRatchetRepository(db.groupRatchetDao()))

        /** The group-root store with its retention sweep counted; the manager is wired with this instance. */
        val groupRoots = CountingGroupRoots(GroupRootRepository(db.groupRootDao()))

        /**
         * The user's display name, live so a test can set it before a send reads it. Stubbed for every rig
         * rather than only the profile ones, since a relaxed mock hands back a Flow that never emits and a
         * `.first()` on one hangs rather than failing.
         */
        val displayName = MutableStateFlow("")

        /**
         * The user's Bluetooth download limit (ADR 2026-10.js2p), null for never asking — stubbed for every rig, since
         * the database re-arm reads it on every link-up and tick and a relaxed mock's flow would hang that read.
         */
        val bluetoothDownloadLimit = MutableStateFlow<Long?>(null)

        /** What the board was handed by [MeshManager.sendPublicPost], and what it answers. */
        val publicPosts = mutableListOf<String>()
        var publicChannelRefusal: PublicPostRefusal? = null
        val manager: MeshManager

        init {
            coEvery { identity.nodeId() } returns me.nodeId
            coEvery { settings.displayName } returns displayName
            coEvery { settings.loraBoard } returns loraBoard
            coEvery { settings.bluetoothDownloadLimitBytes } returns bluetoothDownloadLimit
            // The local delivery of anything we originate runs the inbound path, whose first act is a
            // blocklist read with `.first()`. A relaxed mock's empty flow throws there rather than hanging.
            coEvery { settings.blockedNodeIds } returns MutableStateFlow(emptySet())
            coEvery { settings.acceptedConversations } returns MutableStateFlow(emptySet())
            coEvery { textModeration.classify(any(), any()) } returns TextVerdict.ALLOWED
            coEvery { messages.save(any()) } answers { saved += firstArg<MessageEntity>() }
            // Nothing is held for the quote check (ADR 2026-10.gpa3): a relaxed mock would answer a made-up row.
            coEvery { messages.find(any()) } returns null
            coEvery { messages.repliesAwaiting(any(), any(), any()) } returns emptyList()
            coEvery { messages.unsettledQuotes(any(), any()) } returns emptyList()
            coEvery { peers.find(any()) } returns null // default: no recipient key is known
            // No proof of key held (ADR 2026-09.g64k): relaxed, this would hand KeyExchange a mock frame to serve.
            coEvery { peers.profileFrame(any()) } returns null
            coEvery { blobs.bytes(any()) } returns null
            manager = build()
        }

        /** A manager over this rig's stores — the same phone's next build when [extraCapabilities] differs. */
        fun build(extraCapabilities: Long = 0L): MeshManager =
            MeshManager(
                transport = transport,
                messages = messages,
                receipts = receipts,
                groups = groups,
                reactions = reactions,
                peers = peers,
                metPeers = MetPeerRepository(db.metPeerDao(), db),
                identity = identity,
                settings = settings,
                blobs = blobs,
                imageScreening = imageScreening,
                blobStore = blobStore,
                partials = PartialBlobs(Files.createTempDirectory("mm-parts").toFile()),
                forwardStore = forwardStore,
                notifier = notifier,
                textModeration = textModeration,
                messageCrypto = me.crypto,
                ratchet =
                    RatchetSessions(
                        store = RatchetRepository(db.ratchetDao(), clock = { now }),
                        dhIdentityPriv = { ByteArray(32) { 1 } }, // send-path tests never derive
                        spkPrivFor = { null },
                    ),
                groupRatchet = groupRatchet,
                groupRoots = groupRoots,
                scope = scope,
                metrics = metrics,
                // The relaxed settings mock is the journal: nothing here collects the totals, and a flush's
                // write is a no-op on it.
                ledger = ContributionLedger(journal = settings, selfId = { me.nodeId }, clock = { clockNow }),
                db = db,
                clock = { clockNow },
                publicChannel = { body ->
                    publicPosts += body
                    publicChannelRefusal
                },
                commons = commons,
                extraCapabilities = extraCapabilities,
            )

        /** Pins [p]'s real key under its nodeId, as the profile handler would once its profile arrives. */
        fun pin(p: Party) {
            coEvery { peers.find(p.nodeId) } returns PeerEntity(nodeId = p.nodeId, pubKey = p.bundle.encoded, updatedAt = 1L)
        }

        /**
         * Wires the settings/identity reads [MeshManager.currentProfileEnvelope] and
         * [MeshManager.watchProfileChanges] make, with [displayName] and [publishedAt] as *live* state
         * rather than fixed stubs: the manager writes the publish stamp itself, and the frame id it then
         * builds is derived from it, so a relaxed mock (which swallows the write) could not show the id
         * moving. Returns the display-name flow the test edits.
         */
        fun stubProfileState(publishedAt: MutableStateFlow<Long>): MutableStateFlow<String> {
            coEvery { settings.displayName } returns displayName
            coEvery { settings.status } returns status
            coEvery { settings.avatarUpdatedAt } returns avatarUpdatedAt
            coEvery { settings.openToChat } returns openToChat
            coEvery { settings.loraBoard } returns loraBoard
            // start() also reads the open-to-chat cue's persisted state once, with .first().
            coEvery { settings.openToChatNamed } returns MutableStateFlow(emptySet())
            coEvery { settings.openToChatLastPostAt } returns MutableStateFlow(0L)
            coEvery { settings.ownAvatarHash } returns MutableStateFlow(null)
            // 0 keeps broadcastSealedProfile's early return in play: the sealed CTL_PROFILE half is a
            // separate carrier with its own test surface, and this case is about the cleartext frame id.
            coEvery { settings.profileVersion } returns MutableStateFlow(0L)
            coEvery { settings.profilePublishedAt } returns publishedAt
            // start() also runs the group-root mint and the local-storage sweep before it seeds the
            // profile; both read a settings flow with .first(), which throws on a relaxed mock's empty flow
            // and would abort the seeding coroutine before it ever reached the profile.
            coEvery { settings.spoolEnabled } returns MutableStateFlow(false)
            coEvery { settings.activeSpoolUrls } returns MutableStateFlow(emptySet())
            coEvery { settings.acceptedConversations } returns MutableStateFlow(emptySet())
            // setProfilePublishedAt returns DataStore's Preferences; the test only cares about the write.
            coEvery { settings.setProfilePublishedAt(any()) } answers {
                publishedAt.value = firstArg()
                mockk(relaxed = true)
            }
            coEvery { identity.publicKeyBundle() } returns me.bundle.encoded
            coEvery { identity.deviceTag() } returns "tag"
            coEvery { identity.currentPrekey(any()) } returns SignedPrekey(1, ByteArray(32), ByteArray(64), now)
            return displayName
        }

        /**
         * What the start's seeding pass and [MeshManager.heal]'s basket read beyond [stubProfileState]: the
         * restore flag (`finishRestore`, ahead of the start's sweeps — on a relaxed mock's empty flow `.first()`
         * throws there, the seeding coroutine dies, and the profile watcher's `ownProfile()` seeds the row in its
         * place, which is why the other rigs never noticed) and the intro driver's two settings sets, which the
         * basket reads before its completion counter.
         */
        fun stubHealState() {
            coEvery { settings.restorePending } returns MutableStateFlow(false)
            coEvery { settings.pendingIntros } returns MutableStateFlow(emptySet())
            coEvery { settings.introGrace } returns MutableStateFlow(emptySet())
        }

        /** Runs [MeshManager.heal] and waits for its basket to run to the end (`healsCompleted`), as the lab does. */
        suspend fun healAndAwait() {
            val before = metrics.frames().healsCompleted.toInt()
            manager.heal()
            await(before + 1) { metrics.frames().healsCompleted.toInt() }
        }

        /** Every PROFILE frame that reached custody, oldest first — what a late joiner would be re-served. */
        fun custodiedProfiles(): List<RelayEnvelope> = forwardStore.frames().map { it.envelope }.filter { it.type == FrameType.PROFILE }

        /** The PROFILE frames the manager actually put on the wire (flood copies collapsed by id). */
        fun floodedProfiles(): List<RelayEnvelope> =
            transport.sent
                .mapNotNull { WireCodec.decodeEnvelope(it.first.signed) }
                .filter { it.type == FrameType.PROFILE }
                .distinctBy { it.id }

        /** Every flood copy of the PROFILE frame [id] put on the wire — NOT collapsed, so a re-flood counts. */
        fun profileCopiesOnWire(id: String): Int =
            transport.sent.count { (wire, to) -> to == null && WireCodec.decodeEnvelope(wire.signed)?.id == id }

        /**
         * Takes [peer] out of the sighted set and brings it back — a Wi-Fi Aware edge peer across the linger.
         * `reachable` is a StateFlow and the manager's collector runs on a real thread, so two writes back to
         * back conflate into no change at all; the departure is given time to land before the return.
         */
        suspend fun flap(peer: Peer) {
            transport.sighted.value = transport.sighted.value - peer
            withContext(Dispatchers.Default) { delay(200) }
            transport.sighted.value = transport.sighted.value + peer
        }

        /**
         * Polls [have] until it reaches [count]. [MeshManager.start] builds its session scope on
         * [Dispatchers.Default] rather than the injected [scope]'s dispatcher, so the profile watcher runs
         * on a real thread and `advanceUntilIdle()` cannot see it — poll on that same real dispatcher
         * instead of virtual time. Waits on work that happens either way (a seed, a flood), never on the
         * behaviour under test, so a regression fails its assertion instead of stalling to the timeout.
         */
        suspend fun await(
            count: Int,
            have: () -> Int,
        ) = withContext(Dispatchers.Default) {
            withTimeout(AWAIT_MS) { while (have() < count) delay(POLL_MS) }
        }

        /**
         * Waits until [MeshManager.watchProfileChanges] is collecting. `start()` launches that watcher and
         * the custody seed as siblings on `Dispatchers.Default`, so awaiting the seed does not order the
         * watcher: an edit written before its `combine` subscribes arrives as that combine's *initial*
         * value, `drop(1)` swallows it as the stored one, and nothing floods for the rest of the test.
         * [SettingsStore.avatarUpdatedAt] has no other collector in the manager, so one subscriber is it.
         */
        suspend fun awaitProfileWatcher() = await(1) { avatarUpdatedAt.subscriptionCount.value }

        /** The distinct CHAT routing envelopes the manager originated (collapsing the flood + fast-fanout copies). */
        fun sentChatFrames(): List<RelayEnvelope> = sentFrames().filter { it.type == FrameType.CHAT }

        /** Every distinct routing envelope the manager originated (collapsing the flood + fast-fanout copies). */
        fun sentFrames(): List<RelayEnvelope> =
            transport.sent
                .mapNotNull { WireCodec.decodeEnvelope(it.first.signed) }
                .distinctBy { it.id }
    }

    // --- moderation gate ---

    @Test
    fun flaggedTextIsBlockedOnSendInTheRoomAndNeitherStoredNorFlooded() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            coEvery { rig.textModeration.classify(any(), any()) } returns
                TextVerdict(allowed = false, category = TextVerdict.Category.TOXICITY)

            val ok = rig.manager.sendChat("something abusive")
            advanceUntilIdle()

            assertFalse("block-on-send: a flagged room message is refused", ok)
            assertTrue("nothing is persisted locally", rig.saved.isEmpty())
            assertTrue("and nothing hits the wire", rig.transport.sent.isEmpty())
            coVerify(exactly = 0) { rig.messages.save(any()) }
            coVerify(exactly = 1) { rig.textModeration.classify("something abusive", true) }
        }

    @Test
    fun aDmAndAGroupMessageAreNeverClassifiedOnTheWayOut() =
        runTest(UnconfinedTestDispatcher()) {
            // Only the public rooms screen what is sent (ADR 2026-10.9rh6): a private message's receivers
            // screen it and hide it behind their own Content filtering toggle.
            val rig = Rig(backgroundScope)
            rig.pin(rig.bob)
            coEvery { rig.textModeration.classify(any(), any()) } returns
                TextVerdict(allowed = false, category = TextVerdict.Category.TOXICITY)
            val group = GroupInfo(id = "g-1", members = listOf(rig.me.nodeId, rig.bob.nodeId), createdBy = rig.me.nodeId)

            assertTrue(rig.manager.sendChat("something abusive", recipientId = rig.bob.nodeId))
            assertTrue(rig.manager.sendChat("something abusive", group = group))
            advanceUntilIdle()

            assertEquals(2, rig.saved.size)
            assertTrue(rig.saved.all { it.moderation == MessageEntity.MODERATION_NONE })
            coVerify(exactly = 0) { rig.textModeration.classify(any(), any()) }
        }

    @Test
    fun aSharedPositionIsTakenOutOfTheTextBeforeAnyModeratorSeesIt() =
        runTest(UnconfinedTestDispatcher()) {
            // The room's lexical pass maps leet digits to letters, so a run of coordinates can spell a blocked
            // word (LexicalTextFilterTest pins one). The strip lives in `isTextFlagged`, which every send path
            // and the inbound classify go through, so one capture here covers all of them.
            val rig = Rig(backgroundScope)
            val classified = mutableListOf<String>()
            coEvery { rig.textModeration.classify(any(), any()) } answers {
                classified += firstArg<String>()
                TextVerdict.ALLOWED
            }

            assertTrue(rig.manager.sendChat("See you at the gate\ngeo:37.421998,-122.084000;u=12"))
            advanceUntilIdle()

            assertTrue(classified.isNotEmpty())
            assertEquals(listOf("See you at the gate"), classified.distinct())
            assertEquals("the body itself is stored whole", "See you at the gate\ngeo:37.421998,-122.084000;u=12", rig.saved.single().body)
        }

    @Test
    fun aPositionAloneIsNeverClassified() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            var classified = 0
            coEvery { rig.textModeration.classify(any(), any()) } answers {
                classified++
                TextVerdict(allowed = false, category = TextVerdict.Category.TOXICITY)
            }

            assertTrue(
                "numbers are not words; a moderator that would flag them is not asked",
                rig.manager.sendChat("geo:37.421998,-122.084000;u=12"),
            )
            advanceUntilIdle()

            assertEquals(0, classified)
            assertEquals(MessageEntity.MODERATION_NONE, rig.saved.single().moderation)
        }

    @Test
    fun aPublicPostIsScreenedByTheRoomModeratorOnce() =
        runTest(UnconfinedTestDispatcher()) {
            // `sendChat` infers the scope from the addressing shape, which for a post addressed to nobody
            // would say "not a room". A cleartext radio channel deserves the room moderator (profanity as well
            // as toxicity) at least as much as Nearby does. Once: the row is written directly, never
            // re-delivered through the inbound path.
            val rig = Rig(backgroundScope)
            val scopes = mutableListOf<Boolean>()
            coEvery { rig.textModeration.classify(any(), any()) } answers {
                scopes += secondArg<Boolean>()
                TextVerdict.ALLOWED
            }

            assertEquals(PublicPostOutcome.Queued, rig.manager.sendPublicPost("hello mesh"))
            advanceUntilIdle()

            assertEquals(listOf(true), scopes)
        }

    @Test
    fun aFlaggedPublicPostIsBlockedBeforeItReachesTheBoard() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            coEvery { rig.textModeration.classify(any(), any()) } returns
                TextVerdict(allowed = false, category = TextVerdict.Category.TOXICITY)

            assertEquals(PublicPostOutcome.Blocked, rig.manager.sendPublicPost("something abusive"))
            advanceUntilIdle()

            assertTrue("nothing is persisted locally", rig.saved.isEmpty())
            assertTrue("the board never sees it", rig.publicPosts.isEmpty())
            assertTrue("and nothing hits the wire", rig.transport.sent.isEmpty())
        }

    @Test
    fun aQueuedPublicPostIsHandedToTheBoardAsTypedAndStoredAsOurOwnRow() =
        runTest(UnconfinedTestDispatcher()) {
            // The board is handed the words and nothing else — the author's name never leaves this phone
            // (ADR 2026-09.9469) even when they have one — and the row is ours (no origin), on the LoRa
            // plane, with nothing of it crossing Knit's mesh.
            val rig = Rig(backgroundScope)
            rig.displayName.value = "Alice"

            assertEquals(PublicPostOutcome.Queued, rig.manager.sendPublicPost("hello mesh"))
            advanceUntilIdle()

            assertEquals(listOf("hello mesh"), rig.publicPosts)
            val row = rig.saved.single()
            assertEquals(Conversations.MESHTASTIC, row.conversationId)
            assertEquals("hello mesh", row.body)
            assertEquals(rig.me.nodeId, row.senderId)
            assertNull("our own post, so no origin", row.originNode)
            assertEquals(DeliveryPlane.LoRa.code, row.receivedVia)
            assertTrue("nothing crosses Knit's mesh", rig.transport.sent.isEmpty())
            assertTrue("and nothing is custodied", rig.forwardStore.frames().isEmpty())
        }

    @Test
    fun aRefusedPublicPostStoresNothing() =
        runTest(UnconfinedTestDispatcher()) {
            // The composer keeps the draft and says why; a row that never reached the air would lie.
            val rig = Rig(backgroundScope)
            rig.publicChannelRefusal = PublicPostRefusal.TOO_SOON

            assertEquals(PublicPostOutcome.Refused(PublicPostRefusal.TOO_SOON), rig.manager.sendPublicPost("hello mesh"))
            advanceUntilIdle()

            assertEquals(listOf("hello mesh"), rig.publicPosts)
            assertTrue(rig.saved.isEmpty())
            assertTrue(rig.transport.sent.isEmpty())
        }

    // --- the commons (docs/SPOOL_PROTOCOL.md §7.4) ---

    private val roomSecret = ByteArray(32) { (it + 3).toByte() }
    private val roomId = Conversations.commonsIdFor(hex(ScopeCrypto.commonsScopeId(roomSecret)))

    @Test
    fun aCommonsPostIsStoredWithItsSignedBytesAndNeverLeavesForTheRadios() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeCommonsStore(roomId, roomSecret)
            val rig = Rig(backgroundScope, commons = store)

            assertTrue(rig.manager.sendCommons(roomId, "dinner at 7?", emptyList(), null))
            advanceUntilIdle()

            val (row, sig, signed) = store.posted.single()
            assertEquals(roomId, row.conversationId)
            assertEquals(rig.me.nodeId, row.senderId)
            assertEquals(DeliveryPlane.Internet.code, row.receivedVia)
            assertEquals("dinner at 7?", row.body)
            // The bytes the plane will re-seal on every round: our signature over a `commons` frame naming the room.
            assertTrue(MessageCrypto.verify(rig.me.bundle, sig, signed))
            val env = checkNotNull(WireCodec.decodeEnvelope(signed))
            assertEquals(FrameType.COMMONS, env.type)
            assertEquals(row.id, env.id)
            val post = checkNotNull(WireCodec.decodePayload<CommonsPost>(env.payload))
            assertArrayEquals(ScopeCrypto.commonsScopeId(roomSecret), post.scope)
            val chat = checkNotNull(post.chat)
            assertEquals("dinner at 7?", chat.body)
            assertNull(chat.enc)
            assertNull(post.reaction)
            // Spool only: nothing originated, nothing custodied, and the row went through the commons store.
            assertTrue(rig.transport.sent.isEmpty())
            assertTrue(rig.transport.fastFanouts.isEmpty())
            assertTrue(rig.forwardStore.frames().isEmpty())
            assertTrue(rig.saved.isEmpty())
        }

    // --- formatting (ADR 2026-10.p95v) ---

    private val bold = listOf(StyleSpan(8, 12, BodyStyle.BOLD))

    @Test
    fun aRoomPostsStylesAreStoredAndRideTheRoomField() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)

            assertTrue(rig.manager.sendChat("make it bold", styles = bold + StyleSpan(40, 50, BodyStyle.ITALIC)))
            advanceUntilIdle()

            assertEquals("normalised against the text: the span past its end is gone", "[[8,4,0]]", rig.saved.single().styles)
            assertEquals(bold, WireCodec.decodePayload<ChatContent>(rig.sentChatFrames().single().payload)!!.styles)
        }

    @Test
    fun anUnformattedSendStoresAndCarriesNoStyles() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)

            assertTrue(rig.manager.sendChat("make it bold"))
            advanceUntilIdle()

            assertNull(rig.saved.single().styles)
            val payload = rig.sentChatFrames().single().payload
            assertEquals(emptyList<StyleSpan>(), WireCodec.decodePayload<ChatContent>(payload)!!.styles)
            assertArrayEquals("the room payload is the bytes it was", WireCodec.encodePayload(ChatContent(body = "make it bold")), payload)
        }

    /**
     * `sendChat` takes spans indexed against exactly the text it is handed and never rebases them: the caller trims and
     * rebases first. Spans given against the final text land unchanged, and only what runs past its end is clipped.
     */
    @Test
    fun aDmsStylesAreSealedNeverOnTheShellAndIndexedAgainstTheTextAsGiven() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pin(rig.bob)
            val text = "  make it bold"
            val styles = listOf(StyleSpan(0, 2, BodyStyle.ITALIC), StyleSpan(10, 30, BodyStyle.BOLD))

            assertTrue(rig.manager.sendChat(text, recipientId = rig.bob.nodeId, styles = styles))
            advanceUntilIdle()

            val expected = listOf(StyleSpan(0, 2, BodyStyle.ITALIC), StyleSpan(10, 14, BodyStyle.BOLD))
            assertEquals("[[0,2,1],[10,4,0]]", rig.saved.single().styles)
            val frame = rig.sentChatFrames().single()
            val content = WireCodec.decodePayload<ChatContent>(frame.payload)!!
            assertEquals("the cleartext shell carries none", emptyList<StyleSpan>(), content.styles)
            val header = MessageCrypto.header(frame.id, rig.me.nodeId, frame.sentAt, rig.bob.nodeId)
            assertEquals(
                expected,
                rig.bob.crypto
                    .open(content.enc!!, header, rig.bob.nodeId)!!
                    .styles,
            )
        }

    /** A reset's re-seal reads the row back through `sealedContent`, so it carries the styles the first seal did. */
    @Test
    fun aResealCarriesTheRowsStyles() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pin(rig.bob)
            assertTrue(rig.manager.sendChat("make it bold", recipientId = rig.bob.nodeId, styles = bold))
            advanceUntilIdle()
            val row = rig.saved.single()
            rig.transport.sent.clear()

            coEvery { rig.messages.unackedDmsTo(rig.bob.nodeId, rig.me.nodeId, any()) } returns listOf(row)
            rig.manager.resealRecentDmsTo(rig.bob.nodeId)
            advanceUntilIdle()

            val again = rig.sentChatFrames().single()
            assertEquals("the re-seal goes out under the original id", row.id, again.id)
            val enc = WireCodec.decodePayload<ChatContent>(again.payload)!!.enc!!
            val header = MessageCrypto.header(again.id, rig.me.nodeId, again.sentAt, rig.bob.nodeId)
            assertEquals(
                bold,
                rig.bob.crypto
                    .open(enc, header, rig.bob.nodeId)!!
                    .styles,
            )
        }

    /** ADR 054's room is counted with the styles' bytes: a body that leaves room for exactly one ack leaves none styled. */
    @Test
    fun aStyledDmsInlineAckRoomCountsItsStyles() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            val text = "x".repeat(LoraSizeHint.DM_BODY_BYTES - INLINE_ACK_BYTES)

            rig.manager.dmAcks.hold(rig.bob.nodeId, "in-1".padEnd(22, 'x'))
            assertTrue(rig.manager.sendChat(text, recipientId = rig.bob.nodeId))
            advanceUntilIdle()
            assertTrue(
                "unstyled, the one ack fits",
                rig.manager.dmAcks
                    .pending(rig.bob.nodeId)
                    .isEmpty(),
            )

            rig.manager.dmAcks.hold(rig.bob.nodeId, "in-2".padEnd(22, 'x'))
            assertTrue(rig.manager.sendChat(text, recipientId = rig.bob.nodeId, styles = listOf(StyleSpan(0, 4, BodyStyle.BOLD))))
            advanceUntilIdle()
            assertEquals("styled, it waits for its own tick", listOf("in-2".padEnd(22, 'x')), rig.manager.dmAcks.pending(rig.bob.nodeId))
        }

    @Test
    fun aCommonsPostsStylesRideItsChatAndItsRow() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeCommonsStore(roomId, roomSecret)
            val rig = Rig(backgroundScope, commons = store)

            assertTrue(rig.manager.sendCommons(roomId, "make it bold", emptyList(), null, styles = bold))
            advanceUntilIdle()

            val (row, _, signed) = store.posted.single()
            assertEquals("[[8,4,0]]", row.styles)
            val post = checkNotNull(WireCodec.decodePayload<CommonsPost>(checkNotNull(WireCodec.decodeEnvelope(signed)).payload))
            assertEquals(bold, checkNotNull(post.chat).styles)
        }

    @Test
    fun aCommonsPostIsNeverClassifiedOnTheWayOutAndARoomThisDeviceIsNotInRefusesIt() =
        runTest(UnconfinedTestDispatcher()) {
            // A commons lives on a private spool, so like a DM its members screen what arrives (ADR 2026-10.9rh6).
            val store = FakeCommonsStore(roomId, roomSecret)
            val rig = Rig(backgroundScope, commons = store)
            coEvery { rig.textModeration.classify(any(), any()) } returns
                TextVerdict(allowed = false, category = TextVerdict.Category.TOXICITY)

            assertTrue(rig.manager.sendCommons(roomId, "something vile", emptyList(), null))
            assertFalse(rig.manager.sendCommons(Conversations.commonsIdFor("ff".repeat(32)), "hello?", emptyList(), null))
            advanceUntilIdle()

            assertEquals(listOf("something vile"), store.posted.map { it.first.body })
            assertTrue(rig.transport.sent.isEmpty())
            coVerify(exactly = 0) { rig.textModeration.classify(any(), any()) }
        }

    @Test
    fun aCommonsAttachmentIsSealedToItsOwnKeyWithItsTypeAndNameAndNeverLeavesForTheRadios() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeCommonsStore(roomId, roomSecret)
            val rig = Rig(backgroundScope, commons = store)
            val plainHash = "ab".repeat(32)
            coEvery { rig.blobs.bytes(plainHash) } returns "raw-pdf-bytes".toByteArray()
            val file = AttachmentStore.Ingested(hash = plainHash, mime = "application/pdf", name = "menu.pdf", sizeBytes = 3_000_000)

            assertTrue(rig.manager.sendCommons(roomId, "menu", emptyList(), null, file))
            advanceUntilIdle()

            val (row, _, signed) = store.posted.single()
            val chat = checkNotNull(WireCodec.decodePayload<CommonsPost>(checkNotNull(WireCodec.decodeEnvelope(signed)).payload)?.chat)
            // The blob is the ciphertext under a fresh key: its address names nothing a holder of the PDF could compute.
            val ctHash = checkNotNull(chat.attachmentHash)
            assertNotEquals(plainHash, ctHash)
            coVerify { rig.blobs.insert(ctHash, "application/pdf", any()) }
            assertEquals(ctHash, row.attachmentHash)
            assertEquals(plainHash, row.attachmentPlainHash)
            assertEquals(checkNotNull(chat.attachmentKey), row.attachmentKey)
            assertEquals("application/pdf", chat.attachmentMime)
            assertEquals("menu.pdf", chat.attachmentName)
            assertEquals("menu.pdf", row.attachmentName)
            assertEquals(3_000_000L, chat.attachmentSize)
            assertNull(chat.enc)
            assertTrue(rig.transport.sent.isEmpty())
            assertTrue(rig.forwardStore.frames().isEmpty())
        }

    @Test
    fun aRoomPictureReusedInACommonsIsSealedFreshNeverPostedUnderItsPlaintextAddress() =
        runTest(UnconfinedTestDispatcher()) {
            // ADR 2026-10.mptm hands a picture this phone holds from the Nearby room its key-less identity; the room's
            // post must not name that (ADR 2026-10.xhfr), and one with no key would be refused by every member, us too.
            val store = FakeCommonsStore(roomId, roomSecret)
            val rig = Rig(backgroundScope, commons = store)
            val plainHash = "cd".repeat(32)
            coEvery { rig.blobs.exists(plainHash) } returns true
            coEvery { rig.blobs.bytes(plainHash) } returns "raw-image-bytes".toByteArray()
            val held = AttachmentIdentities.Held(hash = plainHash, key = null, mime = "image/jpeg", animated = false, plainHash = plainHash)
            val photo = AttachmentStore.Ingested(hash = plainHash, mime = "image/jpeg", reuse = held)

            assertTrue(rig.manager.sendCommons(roomId, "look", emptyList(), null, photo))
            advanceUntilIdle()

            val frame = checkNotNull(WireCodec.decodeEnvelope(store.posted.single().third))
            val chat = checkNotNull(WireCodec.decodePayload<CommonsPost>(frame.payload)?.chat)
            assertNotEquals(plainHash, chat.attachmentHash)
            assertNotNull(chat.attachmentKey)
            assertTrue(ScopeFrames.pushableToCommons(frame, rig.me.nodeId, ScopeCrypto.commonsScopeId(roomSecret)))
        }

    @Test
    fun aCommonsReactionIsPostedIntoTheRoomSignedAndNeverTakesTheMeshPath() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeCommonsStore(roomId, roomSecret)
            val rig = Rig(backgroundScope, commons = store)
            val target = FrameId.new()

            assertTrue(rig.manager.sendCommonsReaction(roomId, target, "👍"))
            advanceUntilIdle()

            coVerify(exactly = 1) { rig.reactions.apply(ReactionEntity(target, rig.me.nodeId, "👍", rig.now)) }
            val (messageId, frame) = store.reacted.single()
            assertEquals(target, messageId)
            assertTrue(MessageCrypto.verify(rig.me.bundle, frame.sig, frame.signed))
            assertEquals(FrameType.COMMONS, frame.envelope.type)
            val post = checkNotNull(WireCodec.decodePayload<CommonsPost>(frame.envelope.payload))
            assertArrayEquals(ScopeCrypto.commonsScopeId(roomSecret), post.scope)
            assertNull(post.chat)
            assertEquals(ReactionContent(target, "👍"), post.reaction)
            assertTrue(ScopeFrames.pushableToCommons(frame.envelope, rig.me.nodeId, ScopeCrypto.commonsScopeId(roomSecret)))
            // Neither the sealed nor the cleartext form, and nothing for the radios or custody.
            assertTrue(rig.transport.sent.isEmpty())
            assertTrue(rig.sentReactionFrames().isEmpty())
            assertTrue(rig.forwardStore.frames().isEmpty())
            assertTrue(store.posted.isEmpty())
        }

    @Test
    fun aCommonsReactionOnTheEmojiAlreadyChosenRetractsAndARoomThisDeviceIsNotInRefusesIt() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeCommonsStore(roomId, roomSecret)
            val rig = Rig(backgroundScope, commons = store)
            val target = FrameId.new()
            coEvery { rig.reactions.currentEmoji(target, rig.me.nodeId) } returns "👍"

            assertTrue(rig.manager.sendCommonsReaction(roomId, target, "👍"))
            assertFalse(rig.manager.sendCommonsReaction(Conversations.commonsIdFor("ff".repeat(32)), target, "👍"))
            assertFalse(rig.manager.sendCommonsReaction(roomId, target, "👍".repeat(17)))
            advanceUntilIdle()

            coVerify(exactly = 1) { rig.reactions.apply(ReactionEntity(target, rig.me.nodeId, null, rig.now)) }
            val post =
                checkNotNull(
                    WireCodec.decodePayload<CommonsPost>(
                        store.reacted
                            .single()
                            .second.envelope.payload,
                    ),
                )
            assertEquals(ReactionContent(target, null), post.reaction)
        }

    @Test
    fun aCommonsMemberIsRecordedAndBecomesAnAcceptedContactButNeverOurselves() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeCommonsStore(roomId, roomSecret)
            val rig = Rig(backgroundScope, commons = store)

            rig.manager.onCommonsMember(roomId, rig.bob.nodeId)
            rig.manager.onCommonsMember(roomId, rig.me.nodeId)
            advanceUntilIdle()

            assertEquals(listOf(roomId to rig.bob.nodeId), store.members)
            coVerify(exactly = 1) { rig.settings.accept(rig.bob.nodeId) }
            coVerify(exactly = 0) { rig.settings.accept(rig.me.nodeId) }
        }

    @Test
    fun aCommonsMemberIsAcceptedOnTheirFirstSightingOnly() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeCommonsStore(roomId, roomSecret)
            val rig = Rig(backgroundScope, commons = store)

            repeat(3) { rig.manager.onCommonsMember(roomId, rig.bob.nodeId) }
            advanceUntilIdle()

            // Every sighting is still recorded (it stamps the latest), but only the first made a contact.
            assertEquals(3, store.members.size)
            coVerify(exactly = 1) { rig.settings.accept(rig.bob.nodeId) }
        }

    @Test
    fun aRemovedCommonsMemberIsNotReacceptedWhenARestartRepullsTheRoom() =
        runTest(UnconfinedTestDispatcher()) {
            // Bob was a member before, and the user has since removed him from contacts (ADR 2026-09.adgd).
            val store = FakeCommonsStore(roomId, roomSecret)
            val rig = Rig(backgroundScope, commons = store)
            store.members += roomId to rig.bob.nodeId

            rig.manager.onCommonsMember(roomId, rig.bob.nodeId)
            advanceUntilIdle()

            coVerify(exactly = 0) { rig.settings.accept(any()) }
        }

    @Test
    fun cancelIntroWithdrawsThePendingIntroAndKeepsGrace() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            val carolId = "c".repeat(8)
            coEvery { rig.settings.pendingIntros } returns MutableStateFlow(setOf("${rig.bob.nodeId}|1000"))
            coEvery { rig.settings.introGrace } returns MutableStateFlow(setOf("$carolId|${Long.MAX_VALUE}"))

            rig.manager.cancelIntro(rig.bob.nodeId)

            coVerify(exactly = 1) { rig.settings.setIntroState(emptySet(), setOf("$carolId|${Long.MAX_VALUE}")) }
        }

    @Test
    fun importingAnIPhonesCodeRegistersNoIntro() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            coEvery { rig.settings.pendingIntros } returns MutableStateFlow(emptySet())
            coEvery { rig.settings.introGrace } returns MutableStateFlow(emptySet())
            // Met over the radios first: its profile pinned E2E | GROUPS | REACTIONS | STORE_FORWARD, no ratchet.
            coEvery { rig.peers.find(rig.bob.nodeId) } returns
                PeerEntity(
                    nodeId = rig.bob.nodeId,
                    pubKey = rig.bob.bundle.encoded,
                    protoVersion = Protocol.VERSION,
                    capabilities = 0xFL,
                    updatedAt = 1L,
                )

            rig.manager.importContact(rig.bob.nodeId)

            coVerify(exactly = 0) { rig.settings.setIntroState(any(), any()) }
            assertNull(rig.manager.introState(rig.bob.nodeId).first())
        }

    @Test
    fun importingACardWhoseProfileIsUnknownRegistersTheIntro() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            coEvery { rig.settings.pendingIntros } returns MutableStateFlow(emptySet())
            coEvery { rig.settings.introGrace } returns MutableStateFlow(emptySet())
            rig.pin(rig.bob) // the card's bundle alone: no version, no bits

            rig.manager.importContact(rig.bob.nodeId)

            coVerify(exactly = 1) { rig.settings.setIntroState(match { set -> set.single().startsWith(rig.bob.nodeId) }, emptySet()) }
        }

    @Test
    fun aCommonsSendWithoutTheStoreIsRefused() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            assertFalse(rig.manager.sendCommons(roomId, "hi", emptyList(), null))
            assertTrue(rig.transport.sent.isEmpty())
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
            assertNull("a message we authored never arrived anywhere — only the inbound path stamps that", saved.arrivedAt)

            val frame = rig.sentChatFrames().single()
            assertEquals(rig.me.nodeId, frame.senderId)
            assertNull(frame.recipientId)
            assertEquals("the flooded frame shares the stored copy's id + timestamp", saved.id, frame.id)
            assertEquals(rig.now, frame.sentAt)

            val content = WireCodec.decodePayload<ChatContent>(frame.payload)!!
            assertEquals("the room is plaintext — body rides in the clear", "gm mesh", content.body)
            assertNull("and is not encrypted", content.enc)
            assertTrue("the message is captured for store-and-forward custody", rig.forwardStore.has(frame.id))
            assertTrue("a room post is not offered to the long-range plane (ADR 039)", rig.transport.longRangeFanouts.isEmpty())
        }

    /**
     * Our own frame is in custody before it is on the air (#141): a link raised between the flood and a later custody
     * write would read a digest without it and have missed the flood, and get it only from the 60 s re-offer.
     */
    @Test
    fun anOwnFrameIsCustodiedBeforeItIsFlooded() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            val heldAtSend = CopyOnWriteArrayList<Pair<String, Boolean>>()
            rig.transport.onSend = { wire ->
                val id = WireCodec.decodeEnvelope(wire.signed)?.id
                if (id != null) heldAtSend += id to rig.forwardStore.has(id)
            }

            assertTrue(rig.manager.sendChat("custodied first"))
            advanceUntilIdle()

            val frame = rig.sentChatFrames().single()
            val copies = heldAtSend.filter { it.first == frame.id }
            assertTrue("the flood went out", copies.isNotEmpty())
            assertTrue("every copy went out with its custody row already written", copies.all { it.second })
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
            assertNull("a message we authored never arrived anywhere — only the inbound path stamps that", saved.arrivedAt)

            val frame = rig.sentChatFrames().single()
            val content = WireCodec.decodePayload<ChatContent>(frame.payload)!!
            assertEquals("no plaintext body leaks on the wire", "", content.body)
            assertNotNull("the encrypted envelope rides the frame", content.enc)

            val header = MessageCrypto.header(frame.id, rig.me.nodeId, frame.sentAt, rig.bob.nodeId)
            val opened = rig.bob.crypto.open(content.enc!!, header, rig.bob.nodeId)
            assertNotNull("the addressed recipient can decrypt", opened)
            assertEquals("meet at 8", opened!!.body)
            // ADR 039: a DM is also offered to the long-range (LoRa) plane, exactly once, as the same signed bytes.
            val far = rig.transport.longRangeFanouts.single()
            assertEquals(frame.id, WireCodec.decodeEnvelope(far.signed)!!.id)
            // ...and to the targeted coordination-plane arm, exactly once, addressed to the recipient and never
            // fanned at every neighbor (FrameFanout.shouldFastSend). Same signed bytes on both planes.
            val (near, to) = rig.transport.fastSends.single()
            assertEquals(frame.id, WireCodec.decodeEnvelope(near.signed)!!.id)
            assertEquals(rig.bob.nodeId, to.nodeId)
            assertTrue("a DM is never fanned at every neighbor", rig.transport.fastFanouts.isEmpty())
        }

    @Test
    fun aTransferSignalIsSealedToThePeerAsAControlDmAndRefusedForAnUnpinnedOne() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            // A peer that can answer at all runs this build: pinned with a prekey, so the seal has a session to open.
            rig.pinCryptoV3(rig.bob, RatchetCrypto.generateKeyPair().pub)
            val payload = TransferPayload(id = "t1", phase = TransferPayload.PHASE_OFFER, name = "clip.mp4", size = 5L, mime = "video/mp4")

            assertTrue(rig.manager.sendTransferSignal(rig.bob.nodeId, payload))
            advanceUntilIdle()

            val frame = rig.sentChatFrames().single()
            assertEquals(rig.bob.nodeId, frame.recipientId)
            val content = WireCodec.decodePayload<ChatContent>(frame.payload)!!
            assertEquals("no plaintext body leaks on the wire", "", content.body)
            // The transfer ctl has no compact form, so it rides the named v2 layout even toward a v3 reader.
            assertEquals(EncEnvelope.VERSION_RATCHET, content.enc!!.v)
            assertNull("a ctl carries no attachment hint", content.attachmentHash)
            assertTrue("a ctl is never a local message", rig.saved.isEmpty())
            assertFalse("nothing to seal to", rig.manager.sendTransferSignal("nobody", payload))
        }

    @Test
    fun aTransferSignalToAPeerWithoutTheRatchetSendsNothing() =
        runTest(UnconfinedTestDispatcher()) {
            // IOS-7: an iPhone's profile, E2E | GROUPS | REACTIONS | STORE_FORWARD, here even with a prekey pinned — the
            // case where sealDm would otherwise have started an X3DH the peer can never open.
            val rig = Rig(backgroundScope)
            rig.pinWithCaps(rig.bob, RatchetCrypto.generateKeyPair().pub, 0xFL)
            val payload = TransferPayload(id = "t1", phase = TransferPayload.PHASE_OFFER, name = "clip.mp4", size = 5L, mime = "video/mp4")

            assertFalse(rig.manager.sendTransferSignal(rig.bob.nodeId, payload))
            advanceUntilIdle()

            assertTrue(rig.sentChatFrames().isEmpty())
        }

    @Test
    fun aCallSignalIsSealedToThePeerAsAControlDmAndRefusedForAnUnpinnedOne() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pinCryptoV3(rig.bob, RatchetCrypto.generateKeyPair().pub)
            val payload = CallPayload(id = "c1", ph = CallPayload.PHASE_OFFER, sdp = "v=0")

            assertTrue(rig.manager.sendCallSignal(rig.bob.nodeId, payload))
            advanceUntilIdle()

            val frame = rig.sentChatFrames().single()
            assertEquals(rig.bob.nodeId, frame.recipientId)
            val content = WireCodec.decodePayload<ChatContent>(frame.payload)!!
            assertEquals("no plaintext body leaks on the wire", "", content.body)
            // The call ctl has no compact form either: the named v2 layout, even toward a v3 reader.
            assertEquals(EncEnvelope.VERSION_RATCHET, content.enc!!.v)
            assertTrue("a ctl is never a local message", rig.saved.isEmpty())
            assertFalse("nothing to seal to", rig.manager.sendCallSignal("nobody", payload))
        }

    // --- the long-range re-offer set (FarPeerFrameSource, ADR 039) ---

    /** A carried frame from [sender] to [recipient] as custody would hold it (the fake store never verifies). */
    private fun carried(
        id: String,
        sender: String,
        recipient: String,
        sentAt: Long,
    ): CarriedFrame {
        val env =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = sender,
                sentAt = sentAt,
                recipientId = recipient,
                payload = WireCodec.encodePayload(ChatContent(body = "")),
            )
        return CarriedFrame(env, sig = ByteArray(64), signed = WireCodec.encodeEnvelope(env))
    }

    @Test
    fun farPeerFramesAreOurUnackedDmsFirstThenTheRelayedOnes() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pin(rig.bob)
            val carol = party()
            assertTrue(rig.manager.sendChat("still unacked", recipientId = rig.bob.nodeId))
            rig.clockNow += 1_000
            assertTrue(rig.manager.sendChat("already acked", recipientId = rig.bob.nodeId))
            advanceUntilIdle()
            val (unacked, acked) = rig.saved
            rig.forwardStore.store(carried("relayed", carol.nodeId, rig.bob.nodeId, rig.now + 5_000), ForwardStore.ORIGIN_RELAY, rig.now)
            rig.forwardStore.store(carried("own-ctl", rig.me.nodeId, rig.bob.nodeId, rig.now + 9_000), ForwardStore.ORIGIN_SELF, rig.now)
            rig.forwardStore.store(carried("to-carol", rig.me.nodeId, carol.nodeId, rig.now + 9_500), ForwardStore.ORIGIN_SELF, rig.now)
            coEvery { rig.messages.unackedDmsTo(rig.bob.nodeId, rig.me.nodeId, any()) } returns listOf(unacked)

            val ids = rig.manager.framesFor(rig.bob.nodeId).map { WireCodec.decodeEnvelope(it.signed)!!.id }

            // Ours first (ADR 2026-10.a4pm: they are held live sends); our acked DM and our row-less (ctl) frame are
            // dropped, and a relayed frame is always offered.
            assertEquals(listOf(unacked.id, "relayed"), ids)
            assertFalse(acked.id in ids)
        }

    /**
     * The latent bug the split fixed: the old read took the newest four custody frames to the peer and only then
     * dropped our acked DMs and our own sealed ctls, so a ✓✓ or a reaction we sent could take every slot an
     * unacked DM needed. Our half is now chosen by id off the unacked rows, up to its own cap.
     */
    @Test
    fun ourUnackedDmsAreNotCrowdedOutByNewerAckedFramesOrOwnCtls() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pin(rig.bob)
            val ownLimit = 6 // MeshManager.FAR_REOFFER_OWN_LIMIT (the companion is private)
            repeat(ownLimit + 2) { i ->
                assertTrue(rig.manager.sendChat("unacked $i", recipientId = rig.bob.nodeId))
                rig.clockNow += 1_000
            }
            advanceUntilIdle()
            val unacked = rig.saved.toList()
            // Newer than every DM: our own sealed ctls to bob, which have no message row of their own.
            repeat(6) { i ->
                rig.forwardStore.store(
                    carried("own-ctl-$i", rig.me.nodeId, rig.bob.nodeId, rig.now + 60_000 + i),
                    ForwardStore.ORIGIN_SELF,
                    rig.now,
                )
            }
            coEvery { rig.messages.unackedDmsTo(rig.bob.nodeId, rig.me.nodeId, any()) } returns unacked

            val ids = rig.manager.framesFor(rig.bob.nodeId).map { WireCodec.decodeEnvelope(it.signed)!!.id }

            val newest = unacked.sortedByDescending { it.sentAt }.take(ownLimit).map { it.id }
            assertEquals("our newest unacked DMs, newest first, up to the own cap", newest, ids)
            assertTrue("no ctl took a slot", ids.none { it.startsWith("own-ctl") })
        }

    /** An unacked DM whose custody row is gone takes no slot: the over-fetch leaves it to an older one still held. */
    @Test
    fun anUnackedDmCustodyNoLongerHoldsLeavesItsSlotToAnOlderOne() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pin(rig.bob)
            repeat(7) { i ->
                assertTrue(rig.manager.sendChat("unacked $i", recipientId = rig.bob.nodeId))
                rig.clockNow += 1_000
            }
            advanceUntilIdle()
            val unacked = rig.saved.toList()
            val newest = unacked.maxBy { it.sentAt }
            rig.forwardStore.remove(newest.id) // evicted, or past its TTL
            coEvery { rig.messages.unackedDmsTo(rig.bob.nodeId, rig.me.nodeId, any()) } returns unacked

            val ids = rig.manager.framesFor(rig.bob.nodeId).map { WireCodec.decodeEnvelope(it.signed)!!.id }

            val held = unacked.filter { it.id != newest.id }.sortedByDescending { it.sentAt }.map { it.id }
            assertEquals("the six still held, the oldest included", held, ids)
        }

    /**
     * ADR 2026-10.a4pm's hint: a DM that never flew (parked on its key, sealed now) is the user's message and may
     * probe; the post-reset re-seal is not, because custody keeps the first-stored seal of that id, so a held
     * re-seal would be re-offered as bytes the reset peer cannot open.
     */
    @Test
    fun thePendingFlushHintsChatAndThePostResetResealHintsContent() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pin(rig.bob)
            assertTrue(rig.manager.sendChat("hello", recipientId = rig.bob.nodeId))
            advanceUntilIdle()
            val row = rig.saved.single()
            rig.transport.longRangeHints.clear()

            coEvery { rig.messages.pendingForRecipient(rig.bob.nodeId) } returns listOf(row)
            rig.manager.flushPendingFor(rig.bob.nodeId)
            advanceUntilIdle()
            assertEquals(listOf(FanoutHint.CHAT), rig.transport.longRangeHints)

            rig.transport.longRangeHints.clear()
            coEvery { rig.messages.unackedDmsTo(rig.bob.nodeId, rig.me.nodeId, any()) } returns listOf(row)
            rig.manager.resealRecentDmsTo(rig.bob.nodeId)
            advanceUntilIdle()
            assertEquals(listOf(FanoutHint.CONTENT), rig.transport.longRangeHints)
        }

    /**
     * #171 (ADR 2026-10.dmgw): a relay that saw an id in its ten-minute window drops the reset's re-seal, so the
     * same ids are re-sealed once more after [ResetReseal.SECOND_PASS_MS] — only those still unacked, once.
     */
    @Test
    fun aResetsResealGoesOutOnceMoreAfterTheDedupWindow() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pin(rig.bob)
            repeat(3) { i -> assertTrue(rig.manager.sendChat("dm $i", recipientId = rig.bob.nodeId)) }
            advanceUntilIdle()
            val (acked, stillOwed, alsoOwed) = rig.saved.toList()

            // Every distinct seal of [id] that went out: a re-seal keeps the original id, so count by payload.
            fun seals(id: String): Int =
                rig.transport.sent
                    .mapNotNull { WireCodec.decodeEnvelope(it.first.signed) }
                    .filter { it.type == FrameType.CHAT && it.id == id }
                    .distinctBy { it.payload.toList() }
                    .size

            coEvery { rig.messages.unackedDmsTo(rig.bob.nodeId, rig.me.nodeId, any()) } returns
                listOf(acked, stillOwed, alsoOwed)
            rig.manager.resealRecentDmsTo(rig.bob.nodeId)
            advanceUntilIdle()
            assertEquals("the reset re-seals each at once", listOf(2, 2, 2), rig.saved.take(3).map { seals(it.id) })

            rig.clockNow += ResetReseal.SECOND_PASS_MS - 1
            rig.manager.reofferToNeighbors()
            advanceUntilIdle()
            assertEquals("nothing before the window has passed", listOf(2, 2, 2), rig.saved.take(3).map { seals(it.id) })

            // Since the reset: one acked, and a new DM sent under the fresh session.
            assertTrue(rig.manager.sendChat("after the reset", recipientId = rig.bob.nodeId))
            advanceUntilIdle()
            val afterReset = rig.saved.last()
            coEvery { rig.messages.unackedDmsTo(rig.bob.nodeId, rig.me.nodeId, any()) } returns
                listOf(stillOwed, alsoOwed, afterReset)
            rig.clockNow += 1
            rig.manager.reofferToNeighbors()
            advanceUntilIdle()
            assertEquals("the acked one is left alone", 2, seals(acked.id))
            assertEquals("the still-unacked ones go once more", listOf(3, 3), listOf(seals(stillOwed.id), seals(alsoOwed.id)))
            assertEquals("a DM sent after the reset is not the reset's to re-seal", 1, seals(afterReset.id))

            rig.clockNow += ResetReseal.SECOND_PASS_MS
            rig.manager.reofferToNeighbors()
            advanceUntilIdle()
            assertEquals(
                "once per reset: the second pass never re-arms",
                listOf(2, 3, 3, 1),
                listOf(acked, stillOwed, alsoOwed, afterReset).map { seals(it.id) },
            )
        }

    /** The second pass honours a block that landed after the reset, as the first pass does. */
    @Test
    fun aResetsSecondResealSkipsAPeerBlockedSince() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pin(rig.bob)
            assertTrue(rig.manager.sendChat("hello", recipientId = rig.bob.nodeId))
            advanceUntilIdle()
            val row = rig.saved.single()
            coEvery { rig.messages.unackedDmsTo(rig.bob.nodeId, rig.me.nodeId, any()) } returns listOf(row)
            rig.manager.resealRecentDmsTo(rig.bob.nodeId)
            advanceUntilIdle()
            val sealsAfterReset = rig.transport.sent.size

            coEvery { rig.settings.blockedNodeIds } returns MutableStateFlow(setOf(rig.bob.nodeId))
            rig.clockNow += ResetReseal.SECOND_PASS_MS
            rig.manager.reofferToNeighbors()
            advanceUntilIdle()
            assertEquals("nothing sealed to a blocked peer", sealsAfterReset, rig.transport.sent.size)
        }

    @Test
    fun relayedFarPeerFramesAreCappedAtTheirOwnLimit() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            val carol = party()
            repeat(6) { i ->
                rig.forwardStore.store(carried("r$i", carol.nodeId, rig.bob.nodeId, rig.now + i), ForwardStore.ORIGIN_RELAY, rig.now)
            }
            val ids = rig.manager.framesFor(rig.bob.nodeId).map { WireCodec.decodeEnvelope(it.signed)!!.id }
            assertEquals("the newest four (MeshManager.FAR_REOFFER_RELAYED_LIMIT)", listOf("r5", "r4", "r3", "r2"), ids)
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
            assertTrue("group-form chat never rides the long-range plane (ADR 039)", rig.transport.longRangeFanouts.isEmpty())

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

    /** `@everyone` (ADR 2026-10.6h2g): a full group's seven same-named entries ride the sealed content unchanged. */
    @Test
    fun aFullGroupsSevenEveryoneEntriesRideTheSealedContentUnchanged() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            val carol = party()
            rig.pin(carol)
            val others = (1..6).map { "dave$it" }
            val members = listOf(rig.me.nodeId, carol.nodeId) + others
            val group = GroupInfo(id = "g-8", members = members, createdBy = rig.me.nodeId)
            val everyone = (members - rig.me.nodeId).sorted().map { Mention(nodeId = it, name = "everyone") }
            assertEquals(7, everyone.size)

            assertTrue(rig.manager.sendChat("@everyone standup", mentions = everyone, group = group))
            advanceUntilIdle()

            val frame = rig.sentChatFrames().single()
            val enc = WireCodec.decodePayload<ChatContent>(frame.payload)!!.enc!!
            val header = MessageCrypto.header(frame.id, rig.me.nodeId, frame.sentAt, group.id)
            val opened = carol.crypto.open(enc, header, carol.nodeId)!!
            assertEquals(everyone, opened.mentions)
            assertEquals(MentionStore.encode(everyone), rig.saved.single().mentions)
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
            assertNull(
                "the mime does NOT ride in the clear (ADR 035) — custody addresses bytes by hash and never needed the type",
                content.attachmentMime,
            )
            coVerify { rig.blobs.insert(ctHash, "image/jpeg", any()) } // ciphertext stored under its hash
            coVerify { rig.blobs.deleteIfUnreferenced(plainHash) } // now-unreferenced plaintext dropped

            // The decryption key AND the mime are sealed inside the encrypted content (never in the cleartext frame).
            val header = MessageCrypto.header(frame.id, rig.me.nodeId, frame.sentAt, rig.bob.nodeId)
            val opened = rig.bob.crypto.open(content.enc!!, header, rig.bob.nodeId)!!
            assertEquals("the sealed content references the same ciphertext blob", ctHash, opened.attachmentHash)
            assertEquals("and is the only carrier of the type", "image/jpeg", opened.attachmentMime)
            assertNotNull("and carries the AES key the recipient needs", opened.attachmentKey)
        }

    // --- attachment: a file this phone already holds goes out under that identity (ADR 2026-10.mptm) ---

    @Test
    fun aForwardGoesOutUnderTheIdentityItArrivedUnderAndSealsNothing() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pin(rig.bob)
            val held =
                AttachmentIdentities.Held(
                    hash = "ct-hash",
                    key = "a2V5",
                    mime = "image/jpeg",
                    animated = true,
                    plainHash = "plain-hash",
                )
            coEvery { rig.blobs.exists("ct-hash") } returns true

            assertTrue(
                rig.manager.sendChat(
                    "passing this on",
                    attachment = AttachmentStore.Ingested(hash = "plain-hash", mime = "image/jpeg", animated = true, reuse = held),
                    recipientId = rig.bob.nodeId,
                ),
            )
            advanceUntilIdle()

            val frame = rig.sentChatFrames().single()
            val content = WireCodec.decodePayload<ChatContent>(frame.payload)!!
            assertEquals("every holder of the first copy already holds this one", "ct-hash", content.attachmentHash)
            val header = MessageCrypto.header(frame.id, rig.me.nodeId, frame.sentAt, rig.bob.nodeId)
            val opened = rig.bob.crypto.open(content.enc!!, header, rig.bob.nodeId)!!
            assertEquals("ct-hash", opened.attachmentHash)
            assertEquals("the same key opens it", "a2V5", opened.attachmentKey)
            coVerify(exactly = 0) { rig.blobs.insert(any(), any(), any()) } // no fresh ciphertext
            coVerify { rig.blobs.deleteIfUnreferenced("plain-hash") } // the staged plaintext goes as ever
            val row = rig.saved.single()
            assertEquals("ct-hash", row.attachmentHash)
            assertEquals("a2V5", row.attachmentKey)
            assertEquals("the row keeps the plaintext's name for the next forward", "plain-hash", row.attachmentPlainHash)
        }

    @Test
    fun aRoomPictureForwardedToADmKeepsItsPublicKeylessName() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pin(rig.bob)
            val held =
                AttachmentIdentities.Held(
                    hash = "room-hash",
                    key = null,
                    mime = "image/jpeg",
                    animated = false,
                    plainHash = "room-hash",
                )
            coEvery { rig.blobs.exists("room-hash") } returns true

            assertTrue(
                rig.manager.sendChat(
                    "",
                    attachment = AttachmentStore.Ingested(hash = "room-hash", mime = "image/jpeg", reuse = held),
                    recipientId = rig.bob.nodeId,
                ),
            )
            advanceUntilIdle()

            val frame = rig.sentChatFrames().single()
            val content = WireCodec.decodePayload<ChatContent>(frame.payload)!!
            assertEquals("named as the room names it, so everyone nearby already holds it", "room-hash", content.attachmentHash)
            val header = MessageCrypto.header(frame.id, rig.me.nodeId, frame.sentAt, rig.bob.nodeId)
            val opened = rig.bob.crypto.open(content.enc!!, header, rig.bob.nodeId)!!
            assertNull("no key: the bytes are the room's plaintext", opened.attachmentKey)
            assertEquals("the mime still rides only inside the seal", "image/jpeg", opened.attachmentMime)
            assertNull(content.attachmentMime)
            coVerify(exactly = 0) { rig.blobs.deleteIfUnreferenced("room-hash") } // the room's blob is the one sent
            val row = rig.saved.single()
            assertNull(row.attachmentKey)
            assertNull("a key-less row's hash already is its plaintext's", row.attachmentPlainHash)
        }

    @Test
    fun anIdentityWhoseBlobWentBeforeTheSendIsSealedFresh() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pin(rig.bob)
            val held =
                AttachmentIdentities.Held(
                    hash = "ct-hash",
                    key = "a2V5",
                    mime = "image/jpeg",
                    animated = false,
                    plainHash = "plain-hash",
                )
            coEvery { rig.blobs.exists("ct-hash") } returns false
            coEvery { rig.blobs.bytes("plain-hash") } returns "raw-image-bytes".toByteArray()

            rig.manager.sendChat(
                "",
                attachment = AttachmentStore.Ingested(hash = "plain-hash", mime = "image/jpeg", reuse = held),
                recipientId = rig.bob.nodeId,
            )
            advanceUntilIdle()

            val ctHash = WireCodec.decodePayload<ChatContent>(rig.sentChatFrames().single().payload)!!.attachmentHash!!
            assertNotEquals("ct-hash", ctHash)
            coVerify { rig.blobs.insert(ctHash, "image/jpeg", any()) }
            assertEquals("plain-hash", rig.saved.single().attachmentPlainHash)
        }

    @Test
    fun ourOwnSendRemembersThePickersBytesWhenTheIngestChangedThem() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.manager.sendChat(
                "",
                attachment = AttachmentStore.Ingested(hash = "plain-hash", mime = "image/webp", sourceHash = "gif-hash"),
            )
            advanceUntilIdle()
            assertEquals("gif-hash", rig.saved.single().attachmentSourceHash)
        }

    @Test
    fun aVoiceNotesDescriptionIsStoredAgainstTheCiphertextHashTheRowActuallyHolds() =
        runTest(UnconfinedTestDispatcher()) {
            // The trap this pins: a voice note's duration/waveform are derived at ingest, when only the
            // *plaintext* hash exists — but a DM re-seals the attachment and the stored row records the
            // *ciphertext* hash. Writing the description anywhere that keys off the staged plaintext hash
            // silently updates no rows, and the sender's own bubble renders a length-less flat waveform
            // forever. Nothing on the wire changes either way, so only the stored row can catch it.
            val rig = Rig(backgroundScope)
            rig.pin(rig.bob)
            val plainHash = "plain-voice-hash"
            coEvery { rig.blobs.bytes(plainHash) } returns "raw-adts-bytes".toByteArray()

            val ok =
                rig.manager.sendChat(
                    "",
                    attachment =
                        AttachmentStore.Ingested(
                            hash = plainHash,
                            mime = VoiceAudio.MIME,
                            voice = VoiceAudio.Description(durationMs = 9_300, peaks = "AAECAw=="),
                        ),
                    recipientId = rig.bob.nodeId,
                )
            advanceUntilIdle()

            assertTrue(ok)
            val row = rig.saved.single()
            val frame = rig.sentChatFrames().single()
            val content = WireCodec.decodePayload<ChatContent>(frame.payload)!!

            assertEquals("the row is addressed by the ciphertext hash", content.attachmentHash, row.attachmentHash)
            assertNotEquals("which is not the hash the description was derived under", plainHash, row.attachmentHash)
            assertEquals("and the description landed on that row anyway", 9_300, row.voiceDurationMs)
            assertEquals("AAECAw==", row.voicePeaks)
            assertNull(
                "no audio/aac in the clear: since ADR 035 the wire cost of a voice note is its size alone",
                content.attachmentMime,
            )
            val header = MessageCrypto.header(frame.id, rig.me.nodeId, frame.sentAt, rig.bob.nodeId)
            assertEquals(
                "the type is sealed, where only the recipient reads it",
                VoiceAudio.MIME,
                rig.bob.crypto
                    .open(content.enc!!, header, rig.bob.nodeId)
                    ?.attachmentMime,
            )
        }

    @Test
    fun anOrdinaryImageLeavesTheVoiceColumnsNull() =
        runTest(UnconfinedTestDispatcher()) {
            // The other half of the rule: an attachment is not a voice note just by being an attachment.
            val rig = Rig(backgroundScope)
            rig.pin(rig.bob)
            coEvery { rig.blobs.bytes("plain-hash") } returns "raw-image-bytes".toByteArray()

            rig.manager.sendChat(
                "look",
                attachment = AttachmentStore.Ingested(hash = "plain-hash", mime = "image/jpeg"),
                recipientId = rig.bob.nodeId,
            )
            advanceUntilIdle()

            val row = rig.saved.single()
            assertNull(row.voiceDurationMs)
            assertNull(row.voicePeaks)
            assertNull("nor is it a file", row.attachmentName)
            assertNull(row.attachmentSize)
        }

    @Test
    fun aFilesNameAndSizeAreSealedAndLandOnTheCiphertextRow() =
        runTest(UnconfinedTestDispatcher()) {
            // The same trap the voice test pins, for the two facts a file cannot derive: the name and size are
            // known at ingest against the *plaintext* hash, while the row records the *ciphertext* one. And
            // the half that is unique to files: both must be inside the seal. A cleartext filename would tell
            // every relay and spool what the message is about, which is a louder signal than the mime ADR 035
            // deliberately removed.
            val rig = Rig(backgroundScope)
            rig.pin(rig.bob)
            val plainHash = "plain-file-hash"
            coEvery { rig.blobs.bytes(plainHash) } returns "%PDF-1.7 bytes".toByteArray()

            val ok =
                rig.manager.sendChat(
                    "",
                    attachment =
                        AttachmentStore.Ingested(
                            hash = plainHash,
                            mime = "application/pdf",
                            name = "quarterly-report.pdf",
                            sizeBytes = 1_400_000,
                        ),
                    recipientId = rig.bob.nodeId,
                )
            advanceUntilIdle()

            assertTrue(ok)
            val row = rig.saved.single()
            val frame = rig.sentChatFrames().single()
            val content = WireCodec.decodePayload<ChatContent>(frame.payload)!!

            assertEquals("the row is addressed by the ciphertext hash", content.attachmentHash, row.attachmentHash)
            assertNotEquals(plainHash, row.attachmentHash)
            assertEquals("and the name landed on that row anyway", "quarterly-report.pdf", row.attachmentName)
            assertEquals(1_400_000L, row.attachmentSize)

            val header = MessageCrypto.header(frame.id, rig.me.nodeId, frame.sentAt, rig.bob.nodeId)
            val opened = rig.bob.crypto.open(content.enc!!, header, rig.bob.nodeId)!!
            assertEquals("the recipient reads the name from inside the seal", "quarterly-report.pdf", opened.attachmentName)
            assertEquals(1_400_000L, opened.attachmentSize)
            assertEquals("application/pdf", opened.attachmentMime)
            assertNull("and a carrier learns neither the name nor the type", content.attachmentMime)
        }

    /**
     * #131: the re-seal rebuilt the sealed content by hand and dropped the file's name and size. After a reset its
     * copy is the first one the peer can open, so the file landed nameless and its bubble fell to the image arm.
     * Both re-seal doors — the reset's [MeshManager.resealRecentDmsTo] and the key-arrival flush — must seal what
     * the first send sealed.
     */
    @Test
    fun aResealedFileDmCarriesItsNameAndSize() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pin(rig.bob)
            val plainHash = "plain-file-hash"
            coEvery { rig.blobs.bytes(plainHash) } returns "%PDF-1.7 bytes".toByteArray()
            assertTrue(
                rig.manager.sendChat(
                    "",
                    attachment =
                        AttachmentStore.Ingested(
                            hash = plainHash,
                            mime = "application/pdf",
                            name = "quarterly-report.pdf",
                            sizeBytes = 1_400_000,
                        ),
                    recipientId = rig.bob.nodeId,
                ),
            )
            advanceUntilIdle()
            val row = rig.saved.single()

            // Every seal of this id that went out, in order: [Rig.sentChatFrames] collapses copies by id, and a
            // re-seal keeps the original one.
            fun seals(): List<RelayEnvelope> =
                rig.transport.sent
                    .mapNotNull { WireCodec.decodeEnvelope(it.first.signed) }
                    .filter { it.type == FrameType.CHAT && it.id == row.id }
                    .distinctBy { it.payload.toList() }

            fun opened(frame: RelayEnvelope): MessageContent {
                val content = WireCodec.decodePayload<ChatContent>(frame.payload)!!
                assertEquals("a re-seal still exposes only the ciphertext hash", row.attachmentHash, content.attachmentHash)
                assertNull(content.attachmentMime)
                val header = MessageCrypto.header(frame.id, rig.me.nodeId, frame.sentAt, rig.bob.nodeId)
                return rig.bob.crypto.open(content.enc!!, header, rig.bob.nodeId)!!
            }
            assertEquals(1, seals().size)

            coEvery { rig.messages.unackedDmsTo(rig.bob.nodeId, rig.me.nodeId, any()) } returns listOf(row)
            rig.manager.resealRecentDmsTo(rig.bob.nodeId)
            advanceUntilIdle()
            assertEquals("the reset re-sealed one fresh copy", 2, seals().size)
            val afterReset = opened(seals().last())
            assertEquals("the reset's re-seal names the file", "quarterly-report.pdf", afterReset.attachmentName)
            assertEquals(1_400_000L, afterReset.attachmentSize)
            assertEquals("application/pdf", afterReset.attachmentMime)
            assertEquals(row.attachmentKey, afterReset.attachmentKey)

            coEvery { rig.messages.pendingForRecipient(rig.bob.nodeId) } returns listOf(row)
            rig.manager.flushPendingFor(rig.bob.nodeId)
            advanceUntilIdle()
            assertEquals("the flush re-sealed one fresh copy", 3, seals().size)
            val afterFlush = opened(seals().last())
            assertEquals("the key-arrival flush names the file", "quarterly-report.pdf", afterFlush.attachmentName)
            assertEquals(1_400_000L, afterFlush.attachmentSize)
            assertEquals("application/pdf", afterFlush.attachmentMime)
            assertEquals(row.attachmentKey, afterFlush.attachmentKey)
        }

    @Test
    fun theBroadcastRoomStillFillsTheCleartextMimeBecauseTheRoomIsPlaintext() =
        runTest(UnconfinedTestDispatcher()) {
            // The deliberate exception to ADR 035. In the Nearby room ChatContent *is* the content — there is
            // no seal to move the mime into — so withholding it there would break rendering and buy nothing:
            // the body, the mentions and the attachment bytes are already flooded in the clear.
            val rig = Rig(backgroundScope)
            coEvery { rig.blobs.bytes("plain-hash") } returns "raw-image-bytes".toByteArray()

            val ok =
                rig.manager.sendChat(
                    "look",
                    attachment = AttachmentStore.Ingested(hash = "plain-hash", mime = "image/webp"),
                )
            advanceUntilIdle()

            assertTrue(ok)
            val content = WireCodec.decodePayload<ChatContent>(rig.sentChatFrames().single().payload)!!
            assertNull("a room attachment is not re-sealed, so the plaintext hash rides as-is", content.enc)
            assertEquals("plain-hash", content.attachmentHash)
            assertEquals("and its mime stays in the clear with the rest of the room's content", "image/webp", content.attachmentMime)
        }

    @Test
    fun aLinkPreviewCardRidesADmSealedLikeAPhotoAndTheRoomInTheClear() =
        runTest(UnconfinedTestDispatcher()) {
            // A card is an ordinary attachment under its own MIME (ADR 2026-09.n752): in a DM the container is
            // re-sealed under its ciphertext hash with the MIME and key inside the seal, and the cleartext frame
            // names the hash alone; in the room the plaintext container and its MIME ride as-is.
            val rig = Rig(backgroundScope)
            rig.pin(rig.bob)
            val container = LinkPreviewBlob(LinkPreviewBlob.VERSION, "https://example.com/a", "Title").encode()
            val card = LinkPreviewBlob.decodeOrNull(container)!!.toCard()
            coEvery { rig.blobs.bytes("card-plain") } returns container
            val staged = AttachmentStore.Ingested(hash = "card-plain", mime = LinkPreviewBlob.MIME, link = card)

            assertTrue(rig.manager.sendChat("see https://example.com/a", attachment = staged, recipientId = rig.bob.nodeId))
            advanceUntilIdle()
            val dm = WireCodec.decodePayload<ChatContent>(rig.sentChatFrames().single().payload)!!
            val ctHash = dm.attachmentHash!!
            assertNotEquals("card-plain", ctHash)
            assertNull("the card's type never rides the cleartext frame", dm.attachmentMime)
            coVerify { rig.blobs.insert(ctHash, LinkPreviewBlob.MIME, any()) }
            val header =
                MessageCrypto.header(
                    rig.sentChatFrames().single().id,
                    rig.me.nodeId,
                    rig.sentChatFrames().single().sentAt,
                    rig.bob.nodeId,
                )
            val opened = rig.bob.crypto.open(dm.enc!!, header, rig.bob.nodeId)!!
            assertEquals(LinkPreviewBlob.MIME, opened.attachmentMime)
            assertNotNull(opened.attachmentKey)
            assertNull("a card has no name and no declared size", opened.attachmentName)

            assertTrue(rig.manager.sendChat("see https://example.com/a", attachment = staged))
            advanceUntilIdle()
            val room = WireCodec.decodePayload<ChatContent>(rig.sentChatFrames().last().payload)!!
            assertNull(room.enc)
            assertEquals("card-plain", room.attachmentHash)
            assertEquals(LinkPreviewBlob.MIME, room.attachmentMime)
        }

    @Test
    fun theSpoolFetcherSavesThroughTheSameScreeningIngestAsARadioPull() =
        runTest(UnconfinedTestDispatcher()) {
            // #109: a bare insert here left a spool-pulled group photo or avatar with no verdict. The mime rule
            // and the screen both live in MeshBlobStore.ingest now (pinned in MeshBlobStoreTest).
            val rig = Rig(backgroundScope)
            val bytes = "group-photo-bytes".toByteArray()

            rig.manager.scopeBlobs().save("group-photo-hash", "image/jpeg", bytes)

            coVerify(exactly = 1) { rig.blobStore.ingest("group-photo-hash", "image/jpeg", bytes) }
            coVerify(exactly = 0) { rig.blobs.insert(any(), any(), any()) }
        }

    @Test
    fun theSpoolSkipsAPhotoScreeningRefusedOnlyWhileContentFilteringIsOn() =
        runTest(UnconfinedTestDispatcher()) {
            // The radios' re-want skips it the same way (rewantMissingBlobs, ADR 2026-09.nxcq).
            val rig = Rig(backgroundScope)
            val filtering = MutableStateFlow(true)
            coEvery { rig.settings.contentFilteringEnabled } returns filtering
            coEvery { rig.imageScreening.isImageFlagged("explicit") } returns true
            coEvery { rig.imageScreening.isImageFlagged("fine") } returns false
            val store = rig.manager.scopeBlobs()

            assertTrue(store.refused("explicit"))
            assertFalse(store.refused("fine"))
            filtering.value = false
            assertFalse("filtering off shows a flagged image, so it is fetched", store.refused("explicit"))
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

    // --- the v2 (epoch-ratchet) send gate ---

    /**
     * Pins [p] as ratchet-capable — CAP_RATCHET advertised plus a pinned prekey, as handleProfile stores them —
     * but WITHOUT `CAP_CRYPTO_V3`: the v2 peer every "seals v2" assertion below is about. [pinCryptoV3] is the
     * current build's full set.
     */
    private fun Rig.pinRatchetCapable(
        p: Party,
        prekeyPub: ByteArray,
    ) = pinWithCaps(p, prekeyPub, Protocol.LOCAL_CAPABILITIES and Protocol.CAP_CRYPTO_V3.inv())

    /** Pins [p] with this build's whole capability set — a peer that reads crypto scheme v3 (ADR 059). */
    private fun Rig.pinCryptoV3(
        p: Party,
        prekeyPub: ByteArray,
    ) = pinWithCaps(p, prekeyPub, Protocol.LOCAL_CAPABILITIES)

    /** Pins [p] with an explicit capability set plus a prekey — for the inline-ack gate (ADR 054). */
    private fun Rig.pinWithCaps(
        p: Party,
        prekeyPub: ByteArray,
        capabilities: Long,
    ) {
        coEvery { peers.find(p.nodeId) } returns
            PeerEntity(
                nodeId = p.nodeId,
                pubKey = p.bundle.encoded,
                capabilities = capabilities,
                prekeyId = 1,
                prekeyPub = b64(prekeyPub),
                prekeyProfileAt = 1L,
                updatedAt = 1L,
            )
    }

    /** ADR 054: LoRa-held receipts flush as ONE originated sealed tick, hinted TICK for the board, counted as coalesced. */
    @Test
    fun heldLoraReceiptsFlushAsOneHintedTick() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            rig.manager.dmAcks.hold(rig.bob.nodeId, "in-1")
            rig.manager.dmAcks.hold(rig.bob.nodeId, "in-2")
            rig.clockNow += DmAckCoalescer.HOLD_MS
            rig.manager.dmAcks.flushDue()
            advanceUntilIdle()

            val tick = rig.sentChatFrames().single()
            assertEquals(rig.bob.nodeId, tick.recipientId)
            assertEquals(listOf(FanoutHint.TICK), rig.transport.longRangeHints)
            assertEquals(1L, rig.metrics.receipts().receiptsCustodied)
            assertEquals(1L, rig.metrics.receipts().receiptsCoalesced)
            assertTrue(
                rig.manager.dmAcks
                    .pending(rig.bob.nodeId)
                    .isEmpty(),
            )
        }

    /**
     * ADR 054's piggyback: a DM to a peer that reads inline acks carries the receipts we owe it inside the
     * ciphertext (the only place they can go), and takes them out of the coalescer so no tick follows; a peer
     * whose profile lacks the bit is never sent one and keeps its standalone tick.
     */
    @Test
    fun aDmToAnInlineAckCapablePeerCarriesThePendingAcksAndOneWithoutTheBitDoesNot() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            assertTrue(rig.manager.sendChat("plain", recipientId = rig.bob.nodeId))
            advanceUntilIdle()
            val bare =
                WireCodec
                    .decodePayload<ChatContent>(rig.sentChatFrames().last().payload)!!
                    .enc!!
                    .ct.size

            // Real-length ids: the size check below is what pins INLINE_ACK_BYTES to the wire.
            rig.manager.dmAcks.hold(rig.bob.nodeId, "in-1".padEnd(22, 'x'))
            rig.manager.dmAcks.hold(rig.bob.nodeId, "in-2".padEnd(22, 'x'))
            assertTrue(rig.manager.sendChat("plain", recipientId = rig.bob.nodeId))
            advanceUntilIdle()
            val carrying =
                WireCodec
                    .decodePayload<ChatContent>(rig.sentChatFrames().last().payload)!!
                    .enc!!
                    .ct.size
            val grew = carrying - bare
            // Two 22-char ids plus the `acks` key and array header — nothing else in the frame changed.
            assertTrue("the two ids ride inside the ciphertext (+$grew B)", grew in 2 * INLINE_ACK_BYTES..2 * INLINE_ACK_BYTES + 12)
            assertTrue(
                "taken out of the coalescer — no standalone tick will follow",
                rig.manager.dmAcks
                    .pending(rig.bob.nodeId)
                    .isEmpty(),
            )
            assertEquals(2L, rig.metrics.receipts().receiptsCoalesced)
            assertEquals("a reply is our own DM, never a tick", listOf(FanoutHint.CHAT, FanoutHint.CHAT), rig.transport.longRangeHints)

            val carol = party()
            rig.pinWithCaps(carol, RatchetCrypto.generateKeyPair().pub, Protocol.LOCAL_CAPABILITIES and Protocol.CAP_INLINE_ACK.inv())
            rig.manager.dmAcks.hold(carol.nodeId, "c-1")
            assertTrue(rig.manager.sendChat("plain", recipientId = carol.nodeId))
            advanceUntilIdle()
            assertEquals(listOf("c-1"), rig.manager.dmAcks.pending(carol.nodeId))
            assertEquals(2L, rig.metrics.receipts().receiptsCoalesced)
        }

    /**
     * ADR 2026-09.aa27: a room tick waiting for a ride takes the inline-ack slots a DM ack leaves free, and
     * the coalesced `CTL_RECEIPT` carries whatever is still waiting. Both frames are ones this device was
     * sending to that author anyway, so the tick crosses for no frame and no custody row of its own.
     */
    @Test
    fun aRoomTickWaitingForARideTakesTheSlotsADmAckLeaves() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            rig.manager.dmAcks.hold(rig.bob.nodeId, "dm-1")
            rig.manager.ackSync.giveBackRiding(rig.bob.nodeId, listOf("room-1", "room-2"))

            assertTrue(rig.manager.sendChat("plain", recipientId = rig.bob.nodeId))
            advanceUntilIdle()

            assertEquals("the DM ack rode", 1L, rig.metrics.receipts().receiptsCoalesced)
            assertEquals("and the room ticks filled the rest of the slots", 2L, rig.metrics.receipts().receiptsRidden)
            assertEquals("nothing left waiting for a carrier", 0, rig.manager.ackSync.ridingFor(rig.bob.nodeId))
        }

    @Test
    fun theCoalescedTickCarriesTheRoomTicksWaitingForTheSameAuthor() =
        runTest(UnconfinedTestDispatcher()) {
            // The field case (Pixel 8, 2026-09-05): two room ticks owed since 19:08 and a DM ack flushing at
            // 19:11 toward the same absent author. One frame, already sealed to them and already custodied.
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            rig.manager.dmAcks.hold(rig.bob.nodeId, "dm-1")
            rig.manager.ackSync.giveBackRiding(rig.bob.nodeId, listOf("room-1", "room-2"))
            rig.clockNow += DmAckCoalescer.HOLD_MS
            rig.manager.dmAcks.flushDue()
            advanceUntilIdle()

            assertEquals("one originated tick, not three", 1, rig.sentChatFrames().size)
            assertEquals(1L, rig.metrics.receipts().receiptsCustodied)
            assertEquals(2L, rig.metrics.receipts().receiptsRidden)
            assertEquals(0, rig.manager.ackSync.ridingFor(rig.bob.nodeId))
        }

    @Test
    fun aCoalescedTickNeverCarriesMoreRidersThanTheLoraHopFits() =
        runTest(UnconfinedTestDispatcher()) {
            // The coalesced tick is built for the 3-packet LoRa hop, which `MAX_LORA_TICK_ACKS` sizes at
            // twelve ids in all; aa27 let up to 64 riders onto it (ADR 2026-09.y5f3 caps the riders).
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            repeat(DmAckCoalescer.MAX_LORA_TICK_ACKS) { rig.manager.dmAcks.hold(rig.bob.nodeId, "dm-$it") }
            rig.manager.ackSync.giveBackRiding(rig.bob.nodeId, List(5) { "room-$it" })
            rig.clockNow += DmAckCoalescer.HOLD_MS
            rig.manager.dmAcks.flushDue()
            advanceUntilIdle()

            assertEquals(1, rig.sentChatFrames().size)
            assertEquals("no room left on a full LoRa tick", 0L, rig.metrics.receipts().receiptsRidden)
            assertEquals("the riders wait for the next carrier", 5, rig.manager.ackSync.ridingFor(rig.bob.nodeId))
        }

    @Test
    fun theGroupBatchTickCarriesTheRoomTicksWaitingForTheSameAuthor() =
        runTest(UnconfinedTestDispatcher()) {
            // The third carrier (ADR 2026-09.y5f3): an escalated group tick is a frame already sealed and
            // custodied toward the author, so the room ticks parked for them ride it too.
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            rig.manager.ackSync.giveBackRiding(rig.bob.nodeId, listOf("room-1"))
            rig.manager.ackSync.owe("group-1", rig.bob.nodeId, escalatable = true)
            rig.clockNow += AckSync.TICK_BATCH_DEBOUNCE_MS + 1
            rig.manager.ackSync.retryPending()
            advanceUntilIdle()

            assertEquals("one originated tick", 1, rig.sentChatFrames().size)
            assertEquals(1L, rig.metrics.receipts().receiptsCustodied)
            assertEquals(1L, rig.metrics.receipts().receiptsRidden)
            assertEquals(0, rig.manager.ackSync.ridingFor(rig.bob.nodeId))
        }

    @Test
    fun theRideDeadlineSendsATickOverTheFastPlaneToAReachableAuthor() =
        runTest(UnconfinedTestDispatcher()) {
            // The field day's missing send (ADR 2026-09.y5f3): bob is heard over the board and nowhere else,
            // nothing rode, and the deadline puts one sealed `relay = false` tick on the targeted path.
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            rig.transport.sighted.value = setOf(Peer(rig.bob.nodeId))
            rig.manager.ackSync.giveBackRiding(rig.bob.nodeId, listOf("room-1", "room-2"))
            rig.clockNow += AckSync.RIDE_HOLD_MS + 1
            rig.manager.ackSync.retryPending()
            advanceUntilIdle()

            val (wire, to) = rig.transport.fastSends.single()
            assertEquals(rig.bob.nodeId, to.nodeId)
            assertFalse("point-to-point, never custodied", wire.relay)
            assertEquals(FrameType.CHAT, WireCodec.decodeEnvelope(wire.signed)!!.type)
            assertEquals("nothing originated into custody", 0, rig.sentChatFrames().size)
            assertEquals(1L, rig.metrics.receipts().receiptsSealed)
            assertEquals(0, rig.manager.ackSync.ridingFor(rig.bob.nodeId))
        }

    @Test
    fun theRideDeadlineKeepsHoldingWhenTheAuthorIsNowhere() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            rig.manager.ackSync.giveBackRiding(rig.bob.nodeId, listOf("room-1"))
            rig.clockNow += AckSync.RIDE_HOLD_MS + 1
            rig.manager.ackSync.retryPending()
            advanceUntilIdle()

            assertTrue(rig.transport.fastSends.isEmpty())
            assertEquals("no chain key spent on nobody", 0L, rig.metrics.receipts().receiptsSealed)
            assertEquals(1, rig.manager.ackSync.ridingFor(rig.bob.nodeId))
        }

    @Test
    fun aNewSightingOfTheAuthorFlushesADueRideAtOnce() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            rig.manager.start() // watchReachable is the session's; it is what turns a sighting into onReachable
            rig.manager.ackSync.giveBackRiding(rig.bob.nodeId, listOf("room-1"))
            rig.clockNow += AckSync.RIDE_HOLD_MS + 1
            rig.manager.ackSync.retryPending()
            advanceUntilIdle()
            assertTrue("nowhere to send it yet", rig.transport.fastSends.isEmpty())

            rig.transport.sighted.value = setOf(Peer(rig.bob.nodeId)) // the board hears bob
            rig.await(1) { rig.transport.fastSends.size }

            assertEquals("the sighting is the event the hold was waiting for", 1, rig.transport.fastSends.size)
            assertEquals(0, rig.manager.ackSync.ridingFor(rig.bob.nodeId))
        }

    /** A seal that falls back to v1 cannot carry inline acks (a v1 reader never looks): they go back to the coalescer. */
    @Test
    fun aV1FallbackGivesTheInlineAcksBack() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            // Capability bits without a pinned prekey: the seal falls back to v1 (the AND-gate case).
            coEvery { rig.peers.find(rig.bob.nodeId) } returns
                PeerEntity(
                    nodeId = rig.bob.nodeId,
                    pubKey = rig.bob.bundle.encoded,
                    capabilities = Protocol.LOCAL_CAPABILITIES,
                    updatedAt = 1L,
                )
            rig.manager.dmAcks.hold(rig.bob.nodeId, "in-1")

            assertTrue(rig.manager.sendChat("careful", recipientId = rig.bob.nodeId))
            advanceUntilIdle()

            val enc = WireCodec.decodePayload<ChatContent>(rig.sentChatFrames().single().payload)!!.enc!!
            assertEquals(1, enc.v)
            assertEquals("given back for the standalone tick", listOf("in-1"), rig.manager.dmAcks.pending(rig.bob.nodeId))
            assertEquals(0L, rig.metrics.receipts().receiptsCoalesced)
        }

    @Test
    fun aDmToARatchetCapablePeerSealsV2() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)

            assertTrue(rig.manager.sendChat("fs hello", recipientId = rig.bob.nodeId))
            advanceUntilIdle()

            val content = WireCodec.decodePayload<ChatContent>(rig.sentChatFrames().single().payload)!!
            val enc = content.enc!!
            assertEquals(EncEnvelope.VERSION_RATCHET, enc.v)
            assertTrue(enc.keys.isEmpty())
            val header = enc.r!!
            assertEquals(1, header.se)
            assertEquals(0, header.pe)
            assertNotNull("the first frame carries the X3DH init", header.init)
            assertEquals(1, header.init!!.pkid)
            assertFalse("the stored row is not pendingKey", rig.saved.single().pendingKey)
            assertEquals(1L, rig.metrics.seals().dmSealedV2)

            // A second DM continues the chain in the same epoch, init still attached (unconfirmed).
            assertTrue(rig.manager.sendChat("again", recipientId = rig.bob.nodeId))
            advanceUntilIdle()
            val second = WireCodec.decodePayload<ChatContent>(rig.sentChatFrames().last().payload)!!.enc!!.r!!
            assertEquals(1, second.se)
            assertEquals(1, second.n)
            assertNotNull(second.init)
        }

    /** ADR 059: a peer whose pinned profile carries `CAP_CRYPTO_V3` gets the compact scheme — still signed, still custodied. */
    @Test
    fun aDmToACryptoV3PeerSealsV3() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pinCryptoV3(rig.bob, RatchetCrypto.generateKeyPair().pub)

            assertTrue(rig.manager.sendChat("fs hello", recipientId = rig.bob.nodeId))
            advanceUntilIdle()

            val (wire, _) = rig.transport.sent.single()
            assertEquals("a flooded DM keeps its signature", 64, wire.sig.size)
            assertTrue(wire.relay)
            val enc = WireCodec.decodePayload<ChatContent>(rig.sentChatFrames().single().payload)!!.enc!!
            assertEquals(EncEnvelope.VERSION_DM_V3, enc.v)
            assertEquals("the nonce is derived, the field rides empty", 0, enc.nonce.size)
            assertTrue(enc.keys.isEmpty())
            assertNotNull(enc.r)
            assertEquals(1L, rig.metrics.seals().dmSealedV3)
            assertEquals("v3 is still a ratchet seal", 1L, rig.metrics.seals().dmSealedV2)

            // Inline acks ride the v3 arm too, and are taken out of the coalescer like on v2.
            rig.manager.dmAcks.hold(rig.bob.nodeId, FrameId.new())
            assertTrue(rig.manager.sendChat("reply", recipientId = rig.bob.nodeId))
            advanceUntilIdle()
            assertTrue(
                rig.manager.dmAcks
                    .pending(rig.bob.nodeId)
                    .isEmpty(),
            )
            assertEquals(1L, rig.metrics.receipts().receiptsCoalesced)
        }

    /** ADR 059: the coalesced LoRa tick toward a v3 author is v3 but ORIGINATED — flooded, custodied, and therefore signed. */
    @Test
    fun theCoalescedTickToAV3AuthorIsV3AndStillSigned() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pinCryptoV3(rig.bob, RatchetCrypto.generateKeyPair().pub)
            rig.manager.dmAcks.hold(rig.bob.nodeId, FrameId.new())
            rig.manager.dmAcks.hold(rig.bob.nodeId, FrameId.new())
            rig.clockNow += DmAckCoalescer.HOLD_MS
            rig.manager.dmAcks.flushDue()
            advanceUntilIdle()

            val (wire, _) = rig.transport.sent.single()
            assertEquals(64, wire.sig.size)
            assertTrue(wire.relay)
            val enc = WireCodec.decodePayload<ChatContent>(rig.sentChatFrames().single().payload)!!.enc!!
            assertEquals(EncEnvelope.VERSION_DM_V3, enc.v)
            assertEquals(0, enc.nonce.size)
            assertEquals(1L, rig.metrics.receipts().receiptsCustodied)
            assertEquals(0L, rig.metrics.receipts().ticksUnsigned)
        }

    /** A tick acking an id the compact codec cannot carry falls back to v2 — and a v2 tick is never unsigned. */
    @Test
    fun aTickAckingANonCanonicalIdToAV3AuthorFallsBackToV2() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pinCryptoV3(rig.bob, RatchetCrypto.generateKeyPair().pub)
            rig.manager.dmAcks.hold(rig.bob.nodeId, "not-a-frame-id")
            rig.clockNow += DmAckCoalescer.HOLD_MS
            rig.manager.dmAcks.flushDue()
            advanceUntilIdle()

            val enc = WireCodec.decodePayload<ChatContent>(rig.sentChatFrames().single().payload)!!.enc!!
            assertEquals(EncEnvelope.VERSION_RATCHET, enc.v)
            assertEquals(12, enc.nonce.size)
            assertEquals(0L, rig.metrics.seals().dmSealedV3)
        }

    @Test
    fun aDmToAPeerWithoutTheCapabilityStaysV1() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pin(rig.bob) // pinned key, no capabilities, no prekey — a pre-ratchet build

            assertTrue(rig.manager.sendChat("legacy", recipientId = rig.bob.nodeId))
            advanceUntilIdle()

            val enc = WireCodec.decodePayload<ChatContent>(rig.sentChatFrames().single().payload)!!.enc!!
            assertEquals(1, enc.v)
            assertNull(enc.r)
            assertTrue(enc.keys.isNotEmpty())
            assertEquals(0L, rig.metrics.seals().dmSealedV2)
        }

    @Test
    fun aCapableClaimWithoutAPrekeyFallsBackToV1AndCounts() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            // Capability bit without a pinned prekey — the stale/partial case the AND-gate exists for.
            coEvery { rig.peers.find(rig.bob.nodeId) } returns
                PeerEntity(
                    nodeId = rig.bob.nodeId,
                    pubKey = rig.bob.bundle.encoded,
                    capabilities = Protocol.LOCAL_CAPABILITIES,
                    updatedAt = 1L,
                )

            assertTrue(rig.manager.sendChat("careful", recipientId = rig.bob.nodeId))
            advanceUntilIdle()

            val enc = WireCodec.decodePayload<ChatContent>(rig.sentChatFrames().single().payload)!!.enc!!
            assertEquals(1, enc.v)
            assertEquals(1L, rig.metrics.seals().dmSealedV1Fallback)
        }

    // --- #172: a pinned key X25519 refuses is "cannot seal", never a throw ---

    /** 32 zero bytes: a point a peer can sign as its prekey (the signature verifies) and every X25519 DH refuses. */
    private val lowOrderPoint = ByteArray(RatchetCrypto.KEY_BYTES)

    @Test
    fun aDmToALowOrderPrekeyFallsBackToV1AndCounts() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, lowOrderPoint)

            assertTrue(rig.manager.sendChat("hello", recipientId = rig.bob.nodeId))
            advanceUntilIdle()

            val enc = WireCodec.decodePayload<ChatContent>(rig.sentChatFrames().single().payload)!!.enc!!
            assertEquals(1, enc.v)
            assertTrue(enc.keys.isNotEmpty())
            assertFalse(rig.saved.single().pendingKey)
            assertEquals(1L, rig.metrics.seals().dmSealedV1Fallback)
            assertEquals(0L, rig.metrics.seals().dmSealedV2)
        }

    @Test
    fun aRoomTickHeldForALowOrderPrekeysAuthorGoesAsTheCleartextReceiptOverTheLink() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, lowOrderPoint)
            rig.manager.ackSync.giveBackRiding(rig.bob.nodeId, listOf("room-1"))
            val bob = Peer(rig.bob.nodeId)
            rig.transport.nearby.value = setOf(bob)

            rig.manager.ackSync.onNeighborAdded(bob)
            advanceUntilIdle()

            val receipt =
                rig.transport.sent
                    .single { (_, to) -> to == bob }
                    .first
            assertEquals(FrameType.RECEIPT, WireCodec.decodeEnvelope(receipt.signed)?.type)
            assertEquals(1L, rig.metrics.receipts().receiptsSealedFallback)
            assertEquals(0, rig.manager.ackSync.ridingFor(rig.bob.nodeId))
        }

    @Test
    fun aGroupPostWhoseSeedCannotSealToALowOrderPrekeyFallsBackToV1() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, lowOrderPoint)
            val group = GroupInfo(id = "g-1", name = "Team", members = listOf(rig.me.nodeId, rig.bob.nodeId), createdBy = rig.me.nodeId)

            assertTrue(rig.manager.sendChat("group", group = group))
            advanceUntilIdle()

            val frame = rig.sentChatFrames().single()
            assertNotNull("the post itself, not a seed DM", frame.group)
            val enc = WireCodec.decodePayload<ChatContent>(frame.payload)!!.enc!!
            assertEquals(1, enc.v)
            assertTrue(enc.keys.isNotEmpty())
            assertEquals(1L, rig.metrics.groups().groupSealedV1Fallback)
            assertEquals(0L, rig.metrics.groups().groupSeedsSent)
        }

    /**
     * The reviewer's finding on #172: a group member whose identity key X25519 refuses used to strand the whole
     * group — no seed could seal to it, the v1 wrap then refused the whole message, and the row was saved unsent
     * with nothing to retry it. The group now goes v1 (no seed minted), wrapped to every member but that one.
     */
    @Test
    fun aGroupMemberWithALowOrderIdentityKeyIsSkippedAndTheRestGetThePost() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            val mallory = party()
            val forged = checkNotNull(PublicKeyBundle.fromRaw(mallory.bundle.sigPublicKey(), lowOrderPoint))
            coEvery { rig.peers.find(mallory.nodeId) } returns
                PeerEntity(
                    nodeId = mallory.nodeId,
                    pubKey = forged.encoded,
                    capabilities = Protocol.LOCAL_CAPABILITIES,
                    prekeyId = 1,
                    prekeyPub = b64(RatchetCrypto.generateKeyPair().pub),
                    prekeyProfileAt = 1L,
                    updatedAt = 1L,
                )
            val group =
                GroupInfo(
                    id = "g-1",
                    name = "Team",
                    members = listOf(rig.me.nodeId, rig.bob.nodeId, mallory.nodeId),
                    createdBy = rig.me.nodeId,
                )

            assertTrue(rig.manager.sendChat("still reaches bob", group = group))
            advanceUntilIdle()

            val frame = rig.sentChatFrames().single()
            assertNotNull("the post itself, no seed DM", frame.group)
            val enc = WireCodec.decodePayload<ChatContent>(frame.payload)!!.enc!!
            assertEquals(1, enc.v)
            assertEquals(listOf(rig.bob.nodeId), enc.keys.map { it.to })
            assertEquals(0L, rig.metrics.groups().groupSeedsSent)
            assertNull("no seed was minted for a distribution that cannot succeed", rig.groupRatchet.currentSeeds("g-1").firstOrNull())
        }

    /**
     * A low-order *identity* key: every DH against it refuses, the ratchet's X3DH and the v1 HPKE wrap alike. The
     * DM is kept as a pendingKey row, as for a peer whose key is unknown, rather than crashing the send.
     */
    @Test
    fun aDmToALowOrderIdentityKeyIsKeptPendingNotThrown() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            val bundle = checkNotNull(PublicKeyBundle.fromRaw(rig.bob.bundle.sigPublicKey(), lowOrderPoint))
            coEvery { rig.peers.find(rig.bob.nodeId) } returns
                PeerEntity(
                    nodeId = rig.bob.nodeId,
                    pubKey = bundle.encoded,
                    capabilities = Protocol.LOCAL_CAPABILITIES,
                    prekeyId = 1,
                    prekeyPub = b64(RatchetCrypto.generateKeyPair().pub),
                    prekeyProfileAt = 1L,
                    updatedAt = 1L,
                )

            assertTrue(rig.manager.sendChat("hello", recipientId = rig.bob.nodeId))
            advanceUntilIdle()

            assertTrue(rig.sentChatFrames().isEmpty())
            assertTrue(rig.saved.single().pendingKey)
            assertEquals(1L, rig.metrics.seals().dmSealedV1Fallback)
        }

    // --- the group sender-key send gate ---

    /** Pins [p] with [capabilities] and a prekey (the partially-capable cases the AND-gate exists for). */
    private fun Rig.pinWithCaps(
        p: Party,
        capabilities: Long,
    ) {
        coEvery { peers.find(p.nodeId) } returns
            PeerEntity(
                nodeId = p.nodeId,
                pubKey = p.bundle.encoded,
                capabilities = capabilities,
                prekeyId = 1,
                prekeyPub = b64(RatchetCrypto.generateKeyPair().pub),
                prekeyProfileAt = 1L,
                updatedAt = 1L,
            )
    }

    @Test
    fun aGroupWithAllCapableMembersSealsV3AndDistributesTheSeed() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            val group = GroupInfo(id = "g-1", name = "Team", members = listOf(rig.me.nodeId, rig.bob.nodeId), createdBy = rig.me.nodeId)

            assertTrue(rig.manager.sendChat("group fs", group = group))
            advanceUntilIdle()

            val frames = rig.sentChatFrames()
            // The group frame sealed under the ratchet: derived key, empty wraps, the tiny sender-key header.
            val groupEnc = WireCodec.decodePayload<ChatContent>(frames.single { it.group != null }.payload)!!.enc!!
            assertEquals(EncEnvelope.VERSION_RATCHET, groupEnc.v)
            assertTrue(groupEnc.keys.isEmpty())
            assertNull(groupEnc.r)
            val header = checkNotNull(groupEnc.g)
            assertEquals(1, header.se)
            assertEquals(0, header.n)
            // The minted epoch's seed rode ahead, pairwise, as a v2 ctl DM.
            val seedDm = frames.single { it.recipientId == rig.bob.nodeId }
            assertEquals(EncEnvelope.VERSION_RATCHET, WireCodec.decodePayload<ChatContent>(seedDm.payload)!!.enc!!.v)
            assertEquals(1L, rig.metrics.groups().groupSealedRatchet)
            assertEquals(1L, rig.metrics.groups().groupSeedsSent)
            assertFalse("the stored group row is never pendingKey", rig.saved.single().pendingKey)
        }

    @Test
    fun theSeedDmCarriesTheGroupsFoundingRosterWhenTheRowIsHeld() =
        runTest(UnconfinedTestDispatcher()) {
            // Work item #47: every CTL_GROUP_KEY carries the roster half of the row (toFoundingInfo — the
            // founding set, the creator, the name; never the photo), so a member holding no row can pin the
            // group from the seed. The rig cannot open bob's DM (his prekey private is discarded), so the
            // pin is the plaintext's growth: AES-GCM keeps `ct` at plaintext + tag, and the same content
            // sealed with and without the row differs by exactly the founding info's CBOR.
            suspend fun seedCtSize(withRow: Boolean): Int {
                val rig = Rig(backgroundScope)
                rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
                val group = GroupInfo(id = "g-1", members = listOf(rig.me.nodeId, rig.bob.nodeId), createdBy = rig.me.nodeId)
                // Explicit either way: the relaxed mock would otherwise hand back a blank entity, not null.
                coEvery { rig.groups.find("g-1") } returns
                    if (withRow) {
                        GroupEntity(
                            groupId = "g-1",
                            name = "Team",
                            members = GroupMembersStore.encode(group.members),
                            createdBy = rig.me.nodeId,
                            createdAt = 1L,
                            photoHash = "ph1",
                            photoUpdatedAt = 42L,
                        )
                    } else {
                        null
                    }
                assertTrue(rig.manager.sendChat("mint", group = group))
                advanceUntilIdle()
                val seedDm = rig.sentChatFrames().single { it.recipientId == rig.bob.nodeId }
                return WireCodec
                    .decodePayload<ChatContent>(seedDm.payload)!!
                    .enc!!
                    .ct.size
            }
            // Node ids are fixed-width, so any two stand in for the rigs' own when sizing the roster.
            val a = "a".repeat(NodeId.LENGTH)
            val b = "b".repeat(NodeId.LENGTH)
            val bare = GroupKeyPayload("g-1", keys = listOf(GroupSeed(epoch = 1, seed = ByteArray(32), mintedAt = 1L)))
            val founding =
                GroupEntity(
                    groupId = "g-1",
                    name = "Team",
                    members = GroupMembersStore.encode(listOf(a, b)),
                    createdBy = a,
                    createdAt = 1L,
                    photoHash = "ph1",
                    photoUpdatedAt = 42L,
                ).toFoundingInfo()
            assertEquals(GroupInfo(id = "g-1", name = "Team", members = listOf(a, b), createdBy = a), founding)
            val rosterBytes =
                WireCodec.encodePayload(bare.copy(group = founding)).size - WireCodec.encodePayload(bare).size

            val without = seedCtSize(withRow = false)
            val with = seedCtSize(withRow = true)
            assertEquals("without=$without with=$with roster=$rosterBytes", without + rosterBytes, with)
        }

    @Test
    fun aSecondGroupSendReusesTheChainWithoutRedistributing() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            val group = GroupInfo(id = "g-1", members = listOf(rig.me.nodeId, rig.bob.nodeId), createdBy = rig.me.nodeId)

            assertTrue(rig.manager.sendChat("one", group = group))
            assertTrue(rig.manager.sendChat("two", group = group))
            advanceUntilIdle()

            val frames = rig.sentChatFrames()
            assertEquals("one seed DM total — the chain is reused", 1, frames.count { it.recipientId == rig.bob.nodeId })
            val second = WireCodec.decodePayload<ChatContent>(frames.last { it.group != null }.payload)!!.enc!!.g!!
            assertEquals(1, second.se)
            assertEquals(1, second.n)
            assertEquals(2L, rig.metrics.groups().groupSealedRatchet)
        }

    @Test
    fun aGroupWithAnIncapableMemberFallsBackToV1Entirely() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            val carol = party()
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            // A pre-ratchet build's capability set (everything except CAP_RATCHET — one bit covers both
            // ratchet forms now, so "DM-capable but not group-capable" cannot exist).
            rig.pinWithCaps(
                carol,
                capabilities = Protocol.CAP_E2E or Protocol.CAP_GROUPS or Protocol.CAP_REACTIONS or Protocol.CAP_STORE_FORWARD,
            )
            val group =
                GroupInfo(id = "g-1", members = listOf(rig.me.nodeId, rig.bob.nodeId, carol.nodeId), createdBy = rig.me.nodeId)

            assertTrue(rig.manager.sendChat("mixed", group = group))
            advanceUntilIdle()

            val enc = WireCodec.decodePayload<ChatContent>(rig.sentChatFrames().single().payload)!!.enc!!
            assertEquals(1, enc.v)
            assertNull(enc.g)
            assertEquals(2, enc.keys.size)
            assertEquals(0L, rig.metrics.groups().groupSealedRatchet)
            // Ineligible (not eligible-but-fell-back): the fallback counter stays untouched — DM semantics.
            assertEquals(0L, rig.metrics.groups().groupSealedV1Fallback)
        }

    @Test
    fun anUnpinnedMemberKeepsTheGroupV1AndIsSkippedFromTheWraps() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            val ghost = party() // never pinned — no profile ever arrived
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            val group =
                GroupInfo(id = "g-1", members = listOf(rig.me.nodeId, rig.bob.nodeId, ghost.nodeId), createdBy = rig.me.nodeId)

            assertTrue(rig.manager.sendChat("who's there", group = group))
            advanceUntilIdle()

            val enc = WireCodec.decodePayload<ChatContent>(rig.sentChatFrames().single().payload)!!.enc!!
            assertEquals(1, enc.v)
            // The v1 silent-skip of unpinned members is unchanged (their recovery plane is the ratchet + NACK,
            // or the key-gap roadmap note for pure-v1 groups).
            assertEquals(listOf(rig.bob.nodeId), enc.keys.map { it.to })
        }

    @Test
    fun aKeyRequestReSendsCurrentSeedsOncePerFloor() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            val members = listOf(rig.me.nodeId, rig.bob.nodeId)
            val group = GroupInfo(id = "g-1", members = members, createdBy = rig.me.nodeId)
            coEvery { rig.groups.find("g-1") } returns
                GroupEntity(
                    groupId = "g-1",
                    name = "",
                    members = GroupMembersStore.encode(members),
                    createdBy = rig.me.nodeId,
                    createdAt = 1L,
                )
            assertTrue(rig.manager.sendChat("mint it", group = group))
            advanceUntilIdle()

            fun seedDmsToBob() = rig.sentChatFrames().count { it.recipientId == rig.bob.nodeId }
            val afterMint = seedDmsToBob()

            // A member's key request re-seals the current seeds…
            rig.manager.redistributeGroupKey("g-1", rig.bob.nodeId)
            advanceUntilIdle()
            assertEquals(afterMint + 1, seedDmsToBob())

            // …once per floor window (the fixed test clock keeps the window closed)…
            rig.manager.redistributeGroupKey("g-1", rig.bob.nodeId)
            advanceUntilIdle()
            assertEquals(afterMint + 1, seedDmsToBob())

            // …and a non-member's request re-seals nothing.
            val outsider = party()
            rig.manager.redistributeGroupKey("g-1", outsider.nodeId)
            advanceUntilIdle()
            assertEquals(0, rig.sentChatFrames().count { it.recipientId == outsider.nodeId })
        }

    @Test
    fun seedsAreStillDistributedToBlockedMembers() =
        runTest(UnconfinedTestDispatcher()) {
            // ADR 010: blocking is local presentation only — withholding a seed would reveal the block
            // through the blocked member's decrypt failures.
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            coEvery { rig.settings.blockedNodeIds } returns MutableStateFlow(setOf(rig.bob.nodeId))
            val group = GroupInfo(id = "g-1", members = listOf(rig.me.nodeId, rig.bob.nodeId), createdBy = rig.me.nodeId)

            assertTrue(rig.manager.sendChat("still sealed", group = group))
            advanceUntilIdle()

            assertEquals(1, rig.sentChatFrames().count { it.recipientId == rig.bob.nodeId })
            assertEquals(
                EncEnvelope.VERSION_RATCHET,
                WireCodec.decodePayload<ChatContent>(rig.sentChatFrames().single { it.group != null }.payload)!!.enc!!.v,
            )
        }

    @Test
    fun theProfileAdvertisesTheRatchetCapabilityAndAVerifiablePrekey() {
        assertTrue(Protocol.LOCAL_CAPABILITIES and Protocol.CAP_RATCHET != 0L)
    }

    @Test
    fun aForcedFlushBypassesTheStaleAckGuardAfterAPeerWipe() =
        runTest(UnconfinedTestDispatcher()) {
            // The wipe-recovery hole the branch review caught: bob acked our current epoch, then lost
            // his DB. His session reset must get the seed re-sent, but our outbox row still says
            // "acked" — only the forced (reset-path) flush may bypass that guard; the routine
            // profile-arrival/neighbor-join paths must keep short-circuiting on it.
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            val group = GroupInfo(id = "g-1", members = listOf(rig.me.nodeId, rig.bob.nodeId), createdBy = rig.me.nodeId)
            coEvery { rig.groups.groupsWith(rig.bob.nodeId) } returns
                listOf(
                    GroupEntity(
                        groupId = "g-1",
                        name = "Team",
                        members = GroupMembersStore.encode(group.members),
                        createdBy = rig.me.nodeId,
                        createdAt = 1L,
                    ),
                )
            assertTrue(rig.manager.sendChat("mint", group = group)) // mints epoch 1 + distributes its seed
            advanceUntilIdle()
            rig.groupRatchet.onKeyAck("g-1", rig.bob.nodeId, 1, rig.now) // bob acked… then wiped
            val seedsBefore = rig.sentChatFrames().count { it.recipientId == rig.bob.nodeId }

            rig.manager.flushPendingGroupKeysFor(rig.bob.nodeId) // routine path: stale ack short-circuits
            assertEquals(seedsBefore, rig.sentChatFrames().count { it.recipientId == rig.bob.nodeId })

            rig.manager.flushPendingGroupKeysFor(rig.bob.nodeId, force = true) // the session-reset path
            assertEquals(
                "the forced flush must actually originate a seed DM",
                seedsBefore + 1,
                rig.sentChatFrames().count { it.recipientId == rig.bob.nodeId },
            )
            assertEquals(2L, rig.metrics.groups().groupSeedsSent)
        }

    /**
     * ADR 2026-09.qerd: the seed floor counts only sends under the root we hold with the member now. A restored
     * member's reset lands after our post already sealed its seed under the session the restore wiped; the
     * forced flush the reset asks for must not meet the floor that dead send stamped. Under an unchanged root
     * the floor holds, forced or not.
     */
    @Test
    fun aSeedSentUnderARootTheMemberHasLeftDoesNotHoldBackTheNextOne() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            val group = GroupInfo(id = "g-1", members = listOf(rig.me.nodeId, rig.bob.nodeId), createdBy = rig.me.nodeId)
            coEvery { rig.groups.groupsWith(rig.bob.nodeId) } returns
                listOf(
                    GroupEntity(
                        groupId = "g-1",
                        name = "Team",
                        members = GroupMembersStore.encode(group.members),
                        createdBy = rig.me.nodeId,
                        createdAt = 1L,
                    ),
                )
            assertTrue(rig.manager.sendChat("mint", group = group))
            advanceUntilIdle()

            fun seeds() = rig.sentChatFrames().count { it.recipientId == rig.bob.nodeId && it.group == null }

            rig.manager.flushPendingGroupKeysFor(rig.bob.nodeId) // unacked: one re-send, which stamps the floor
            val floored = seeds()
            rig.manager.flushPendingGroupKeysFor(rig.bob.nodeId, force = true)
            assertEquals("same root: the floor holds, forced or not", floored, seeds())

            // Bob's replacement landed: the session we hold with him now has another root.
            val store = RatchetRepository(rig.db.ratchetDao(), clock = { rig.now })
            val session = checkNotNull(store.session(rig.bob.nodeId))
            store.upsertSession(session.copy(root = ByteArray(32) { 9 }))
            rig.manager.flushPendingGroupKeysFor(rig.bob.nodeId, force = true)
            assertEquals("the seed under the left root no longer counts", floored + 1, seeds())
            rig.manager.flushPendingGroupKeysFor(rig.bob.nodeId, force = true)
            assertEquals("and the new root has a floor of its own", floored + 1, seeds())
        }

    // --- sealed reactions (CTL_REACTION) ---

    /** The REACTION routing envelopes the manager originated (the legacy cleartext form). */
    private fun Rig.sentReactionFrames(): List<RelayEnvelope> =
        transport.sent
            .mapNotNull { WireCodec.decodeEnvelope(it.first.signed) }
            .filter { it.type == FrameType.REACTION }
            .distinctBy { it.id }

    @Test
    fun aDmReactionToACapablePeerRidesSealedNeverAsACleartextFrame() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)

            rig.manager.sendReaction("m1", "👍", recipientId = rig.bob.nodeId)
            advanceUntilIdle()

            assertTrue("no cleartext reaction may leak the target/emoji", rig.sentReactionFrames().isEmpty())
            val frame = rig.sentChatFrames().single()
            assertEquals(rig.bob.nodeId, frame.recipientId)
            val enc = WireCodec.decodePayload<ChatContent>(frame.payload)!!.enc!!
            assertEquals(EncEnvelope.VERSION_RATCHET, enc.v)
            assertTrue("the DM form carries the epoch-ratchet header", enc.r != null)
            // The local row applied optimistically with the same LWW clock the frame carries.
            coVerify(exactly = 1) { rig.reactions.apply(ReactionEntity("m1", rig.me.nodeId, "👍", rig.now)) }
            assertEquals(1L, rig.metrics.seals().reactionsSealed)
        }

    @Test
    fun aReactionToAnIncapablePeerFallsBackToTheCleartextFrame() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pin(rig.bob) // pinned, no CAP_RATCHET

            rig.manager.sendReaction("m1", "👍", recipientId = rig.bob.nodeId)
            advanceUntilIdle()

            val reaction = rig.sentReactionFrames().single()
            assertEquals("m1", WireCodec.decodePayload<ReactionContent>(reaction.payload)!!.messageId)
            assertTrue(rig.sentChatFrames().isEmpty())
            assertEquals(1L, rig.metrics.seals().reactionsSealedFallback)
        }

    @Test
    fun aGroupReactionWithEveryMemberEligibleRidesTheGroupFormAndDistributesItsSeed() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            val group = GroupInfo(id = "g-1", members = listOf(rig.me.nodeId, rig.bob.nodeId), createdBy = rig.me.nodeId)

            rig.manager.sendReaction("gm1", "🔥", group = group)
            advanceUntilIdle()

            assertTrue(rig.sentReactionFrames().isEmpty())
            val frames = rig.sentChatFrames()
            // First reaction in the group mints the sender epoch: its seed ctl DM to bob + the group frame.
            val groupFrame = frames.single { it.group != null }
            val enc = WireCodec.decodePayload<ChatContent>(groupFrame.payload)!!.enc!!
            assertEquals(EncEnvelope.VERSION_RATCHET, enc.v)
            assertTrue("the group form carries the sender-key header", enc.g != null)
            assertEquals(1, frames.count { it.recipientId == rig.bob.nodeId })
            assertEquals(1L, rig.metrics.seals().reactionsSealed)
        }

    @Test
    fun aGroupReactionWithAnIneligibleMemberFallsBackToTheCleartextFrame() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pin(rig.bob)
            val group = GroupInfo(id = "g-1", members = listOf(rig.me.nodeId, rig.bob.nodeId), createdBy = rig.me.nodeId)

            rig.manager.sendReaction("gm1", "🔥", group = group)
            advanceUntilIdle()

            assertTrue(rig.sentChatFrames().isEmpty())
            assertEquals(1, rig.sentReactionFrames().size)
            assertEquals(1L, rig.metrics.seals().reactionsSealedFallback)
        }

    @Test
    fun aBroadcastReactionStaysCleartextByDesignAndCountsNoFallback() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)

            rig.manager.sendReaction("room1", "👍")
            advanceUntilIdle()

            assertEquals(1, rig.sentReactionFrames().size)
            assertTrue(rig.sentChatFrames().isEmpty())
            assertEquals(0L, rig.metrics.seals().reactionsSealedFallback)
        }

    @Test
    fun aRetractionRidesTheSealedPathWithANullEmoji() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            coEvery { rig.reactions.currentEmoji("m1", rig.me.nodeId) } returns "👍" // same emoji → toggle off

            rig.manager.sendReaction("m1", "👍", recipientId = rig.bob.nodeId)
            advanceUntilIdle()

            coVerify(exactly = 1) { rig.reactions.apply(ReactionEntity("m1", rig.me.nodeId, null, rig.now)) }
            assertTrue(rig.sentReactionFrames().isEmpty())
            assertEquals(1L, rig.metrics.seals().reactionsSealed)
        }

    @Test
    fun aCtlReactionNeverFallsBackToTheV1Wrap() =
        runTest(UnconfinedTestDispatcher()) {
            // A capable-looking peer whose prekey is garbled: sealDm cannot bootstrap a session, and a
            // ctl must NEVER take the v1 wrap (a pre-ratchet build would decrypt it, strip the unknown
            // ctl field, and persist an empty bubble) — the fallback is the legacy cleartext frame.
            val rig = Rig(backgroundScope)
            coEvery { rig.peers.find(rig.bob.nodeId) } returns
                PeerEntity(
                    nodeId = rig.bob.nodeId,
                    pubKey = rig.bob.bundle.encoded,
                    capabilities = Protocol.LOCAL_CAPABILITIES,
                    prekeyId = 1,
                    prekeyPub = "not-base64!!!",
                    prekeyProfileAt = 1L,
                    updatedAt = 1L,
                )

            rig.manager.sendReaction("m1", "👍", recipientId = rig.bob.nodeId)
            advanceUntilIdle()

            assertTrue("no v1 EncEnvelope for a ctl payload, ever", rig.sentChatFrames().isEmpty())
            assertEquals(1, rig.sentReactionFrames().size)
            assertEquals(1L, rig.metrics.seals().reactionsSealedFallback)
        }

    @Test
    fun anOversizedReactionIsRefusedBeforeItTouchesTheRowOrTheWire() =
        runTest(UnconfinedTestDispatcher()) {
            // The picker can't produce one, but the debug bridge can: past TextLimits.REACTION the manager
            // logs and ignores — no optimistic row, no frame of either form, no counter.
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)

            rig.manager.sendReaction("m1", "👍".repeat(17), recipientId = rig.bob.nodeId)
            rig.manager.sendReaction("m1", " ", recipientId = rig.bob.nodeId)
            advanceUntilIdle()

            coVerify(exactly = 0) { rig.reactions.apply(any()) }
            assertTrue(rig.sentChatFrames().isEmpty())
            assertTrue(rig.sentReactionFrames().isEmpty())
            assertEquals(0L, rig.metrics.seals().reactionsSealed)
            assertEquals(0L, rig.metrics.seals().reactionsSealedFallback)
        }

    @Test
    fun aReactionAddressedToARoomIdIsRefusedBeforeItTouchesTheRowOrTheWire() =
        runTest(UnconfinedTestDispatcher()) {
            // A room id is no peer: nothing to seal for, so it used to reach the cleartext flood and name a commons
            // message (or a Meshtastic room post) to every node in range.
            val rig = Rig(backgroundScope)

            rig.manager.sendReaction("m1", "👍", recipientId = Conversations.COMMONS_PREFIX + "ab".repeat(32))
            rig.manager.sendReaction("m2", "👍", recipientId = Conversations.MESHTASTIC)
            advanceUntilIdle()

            coVerify(exactly = 0) { rig.reactions.apply(any()) }
            assertTrue(rig.sentChatFrames().isEmpty())
            assertTrue(rig.sentReactionFrames().isEmpty())
            assertEquals(0L, rig.metrics.seals().reactionsSealedFallback)
        }

    @Test
    fun theLongestRgiSequenceRidesSealedVerbatim() =
        runTest(UnconfinedTestDispatcher()) {
            // An open emoji set is no wire change: a 10-code-point ZWJ sequence is just a longer string.
            val rig = Rig(backgroundScope)
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            val kiss = "\uD83D\uDC69\uD83C\uDFFD\u200D\u2764\uFE0F\u200D\uD83D\uDC8B\u200D\uD83D\uDC68\uD83C\uDFFC"

            rig.manager.sendReaction("m1", kiss, recipientId = rig.bob.nodeId)
            advanceUntilIdle()

            coVerify(exactly = 1) { rig.reactions.apply(ReactionEntity("m1", rig.me.nodeId, kiss, rig.now)) }
            assertTrue(rig.sentReactionFrames().isEmpty())
            assertEquals(1, rig.sentChatFrames().size)
            assertEquals(1L, rig.metrics.seals().reactionsSealed)
        }

    // --- Ask before large Bluetooth-only downloads (ADR 2026-10.js2p) ---

    private fun missing(
        hash: String,
        sender: String,
        size: Long?,
        approved: Boolean = false,
    ) = MissingAttachment(hash, sender, listOf(AttachmentFetchRow(hash, sender, size, approved, own = false)))

    /** Every blob request the manager sent, as (to, content). */
    private fun Rig.blobAsks(): List<Pair<String?, BlobReqContent>> =
        transport.sent.mapNotNull { (wire, to) ->
            WireCodec
                .decodeEnvelope(wire.signed)
                ?.takeIf { it.type == FrameType.BLOB_REQ }
                ?.let { env -> WireCodec.decodePayload<BlobReqContent>(env.payload)?.let { to?.nodeId to it } }
        }

    /**
     * The distinct hashes asked for. A start-up with a link already up re-arms twice — the limit collector's first value
     * and the newcomer's pass, whose `linked` + `reask` may ask the author again — so a wait counts hashes, never asks.
     */
    private fun Rig.askedHashes(): Set<String> = blobAsks().mapTo(HashSet()) { it.second.hash }

    /**
     * Counts the re-arms that reached their wants, through [MeshManager.beforeRewantApplies]. Installed before `start()`,
     * so [awaitStartupReArms] can tell when both start-up passes have run.
     */
    private fun Rig.countReArms(): AtomicInteger = AtomicInteger().also { n -> manager.beforeRewantApplies = { n.incrementAndGet() } }

    /**
     * Returns once both start-up re-arms (the limit collector's first value and the newcomer's pass) have applied their
     * wants, so a "held" read sees every pass and not only the first. Two have entered the lock; the re-offer's own
     * re-arm queues behind the second and runs only after its wants applied — and is one more pass the hold must survive.
     */
    private suspend fun Rig.awaitStartupReArms(passes: AtomicInteger) {
        await(2) { passes.get() }
        manager.reofferToNeighbors()
    }

    @Test
    fun theDatabaseReArmHoldsAnAttachmentOverTheLimitToWifiAwareAndChoosingNeverFetchesItAtOnce() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            val author = "author-node"
            rig.bluetoothDownloadLimit.value = 1_000_000L
            coEvery { rig.messages.attachmentsNeedingFetch(any()) } returns
                listOf(
                    missing("big", author, 2_000_000L),
                    missing("tapped", author, 2_000_000L, approved = true),
                    missing("small", author, 400_000L),
                    // A stranger's row naming it with an inflated size: the contact's row, declaring nothing, fetches it.
                    MissingAttachment(
                        "shared",
                        author,
                        listOf(
                            AttachmentFetchRow("shared", author, null, false, false),
                            AttachmentFetchRow("shared", "x", 9_000_000L, false, false),
                        ),
                    ),
                )
            rig.transport.nearby.value = setOf(Peer(author)) // a Bluetooth link only: no Wi-Fi Aware route
            rig.stubProfileState(MutableStateFlow(0L)) // start() reads the profile settings too
            coEvery { rig.groups.observeGroups() } returns flowOf(emptyList()) // the startup storage sweep's read

            val passes = rig.countReArms()
            rig.manager.start()
            rig.await(3) { rig.askedHashes().size }
            rig.awaitStartupReArms(passes)
            assertEquals(
                "the tapped, the small and the shared one asked plain; the large one not at all",
                setOf("tapped", "small", "shared"),
                rig.blobAsks().mapTo(HashSet()) { it.second.hash },
            )
            assertTrue(rig.blobAsks().none { it.second.fastOnly != null })

            rig.bluetoothDownloadLimit.value = null // the user chose "Never ask"
            rig.await(4) { rig.askedHashes().size }
            assertEquals(
                author to BlobReqContent("big", pull = BlobExchange.PULL_BUDGET),
                rig.blobAsks().first { it.second.hash == "big" },
            )
            rig.manager.stop()
        }

    @Test
    fun theReArmCarriesADmsCustodyWindowAsItsAsksDeadlineAndNoneForAGroupsRow() =
        runTest(UnconfinedTestDispatcher()) {
            // #195, ADR 2026-10.2drc: the deadline is the latest DM row's sentAt plus custody's TTL; a group's row has none.
            val rig = Rig(backgroundScope)
            val author = "author-node"
            val sentAt = 1_000_000L
            coEvery { rig.messages.attachmentsNeedingFetch(any()) } returns
                listOf(
                    MissingAttachment(
                        "dm",
                        author,
                        listOf(AttachmentFetchRow("dm", author, null, false, false, sentAt = sentAt, dm = true)),
                    ),
                    MissingAttachment(
                        "group",
                        author,
                        listOf(AttachmentFetchRow("group", author, null, false, false, sentAt = sentAt, dm = false)),
                    ),
                )
            rig.transport.nearby.value = setOf(Peer(author))
            rig.stubProfileState(MutableStateFlow(0L))
            coEvery { rig.groups.observeGroups() } returns flowOf(emptyList())

            rig.manager.start()
            rig.await(2) { rig.askedHashes().size }
            val asks = rig.blobAsks().associate { it.second.hash to it.second }
            assertEquals(sentAt + ForwardRepository.DEFAULT_TTL_MS, asks.getValue("dm").until)
            assertEquals(null, asks.getValue("group").until)
            assertEquals(BlobExchange.PULL_BUDGET, asks.getValue("dm").pull)
            rig.manager.stop()
        }

    @Test
    fun aCarrierPullsADmsPictureThroughForItsCustodyWindowAndAGroupsWithoutOne() =
        runTest(UnconfinedTestDispatcher()) {
            // #195's review: a carrier's want of a DM's picture once had no deadline. It keys on the frame's own sentAt,
            // frame-global, so every carrier stops pulling it through at the same moment.
            val rig = Rig(backgroundScope)
            val author = "author-node"
            val sentAt = 1_000_000L
            rig.forwardStore.carried =
                listOf(
                    CarriedAttachment("dm", recipientId = "carol", carriedAt = 0L, senderId = author, sentAt = sentAt),
                    CarriedAttachment("group", recipientId = null, carriedAt = 0L, senderId = author, groupId = "g-1", sentAt = sentAt),
                )
            rig.transport.nearby.value = setOf(Peer(author))
            rig.stubProfileState(MutableStateFlow(0L))
            coEvery { rig.groups.observeGroups() } returns flowOf(emptyList())

            rig.manager.start()
            rig.await(2) { rig.askedHashes().size }
            val asks = rig.blobAsks().associate { it.second.hash to it.second }
            assertEquals(sentAt + ForwardRepository.DEFAULT_TTL_MS, asks.getValue("dm").until)
            assertEquals(null, asks.getValue("group").until)
            rig.manager.stop()
        }

    @Test
    fun anUnopenedCarriedAttachmentIsFastOnlyOnlyWhenEveryFrameNamingItIsOurs() =
        runTest(UnconfinedTestDispatcher()) {
            // Round 3 of ADR 2026-10.js2p's review: a stranger's DM to us naming a hash we carry for somebody else must
            // not make our carrier pull Wi-Fi Aware only. Folded as the rows are: it can lift the gate, never impose it.
            val rig = Rig(backgroundScope)
            val author = "author-node"
            val me = rig.me.nodeId
            rig.bluetoothDownloadLimit.value = 1_000_000L
            rig.forwardStore.carried =
                listOf(
                    CarriedAttachment("ours", recipientId = me, carriedAt = 0L, senderId = author),
                    CarriedAttachment("carried", recipientId = "carol", carriedAt = 0L, senderId = author),
                    CarriedAttachment("carried", recipientId = me, carriedAt = 0L, senderId = "stranger"),
                )
            rig.transport.nearby.value = setOf(Peer(author))
            rig.stubProfileState(MutableStateFlow(0L))
            coEvery { rig.groups.observeGroups() } returns flowOf(emptyList())

            val passes = rig.countReArms()
            rig.manager.start()
            rig.await(1) { rig.askedHashes().size }
            rig.awaitStartupReArms(passes)
            assertEquals(
                "the carrier pull stays plain; the unopened DM to us is held",
                setOf("carried"),
                rig.blobAsks().mapTo(HashSet()) {
                    it.second.hash
                },
            )
            rig.manager.stop()
        }

    @Test
    fun aCarriedRoomPostNoRowNamesIsJudgedByTheSizeItDeclares() =
        runTest(UnconfinedTestDispatcher()) {
            // ADR 2026-10.hmfe: a room post is every node's and declares its size in the clear. A tick between custody's
            // write and the delivery's row, a blocked sender's post, a swept row: the re-arm judges the post as its row
            // would be, never as a carrier pull over Bluetooth. Folded as the rows are: an undeclared post lifts it.
            val rig = Rig(backgroundScope)
            val author = "author-node"
            rig.bluetoothDownloadLimit.value = 1_000_000L
            rig.forwardStore.carried =
                listOf(
                    CarriedAttachment("big-clip", recipientId = null, carriedAt = 0L, senderId = author, declaredSize = 3_000_000L),
                    CarriedAttachment("small-clip", recipientId = null, carriedAt = 0L, senderId = author, declaredSize = 400_000L),
                    CarriedAttachment("lifted", recipientId = null, carriedAt = 0L, senderId = author, declaredSize = 3_000_000L),
                    CarriedAttachment("lifted", recipientId = null, carriedAt = 0L, senderId = "older-build"),
                )
            rig.transport.nearby.value = setOf(Peer(author)) // a Bluetooth link only: no Wi-Fi Aware route
            rig.stubProfileState(MutableStateFlow(0L))
            coEvery { rig.groups.observeGroups() } returns flowOf(emptyList())

            val passes = rig.countReArms()
            rig.manager.start()
            rig.await(2) { rig.askedHashes().size }
            rig.awaitStartupReArms(passes)
            assertEquals(
                "the large room post is held to Wi-Fi Aware; the small and the lifted one asked plain",
                setOf("small-clip", "lifted"),
                rig.blobAsks().mapTo(HashSet()) { it.second.hash },
            )
            assertTrue(rig.blobAsks().none { it.second.fastOnly != null })

            rig.bluetoothDownloadLimit.value = null // "Never ask": the room post is fetched as a carrier pull
            rig.await(3) { rig.askedHashes().size }
            assertEquals(
                author to BlobReqContent("big-clip", pull = BlobExchange.PULL_BUDGET),
                rig.blobAsks().first { it.second.hash == "big-clip" },
            )
            rig.manager.stop()
        }

    @Test
    fun aCarriedHashCustodyCouldNotJudgeBetweenTheReArmsReadsIsHeldUntilTheNext() =
        runTest(UnconfinedTestDispatcher()) {
            // #141's finding 6: a room post a row named when custody read it brought no size, and the rows' read after it
            // found no row (a room cap's sweep between them); a carried hash whose frame was trimmed between custody's own
            // two reads has no frame to judge at all. Neither is wanted plain on a guess: both are held to Wi-Fi Aware
            // until the next re-arm. The small post is the control: the rule still lifts what declares under the limit.
            val rig = Rig(backgroundScope)
            val author = "author-node"
            rig.bluetoothDownloadLimit.value = 1_000_000L
            rig.forwardStore.carried =
                listOf(
                    CarriedAttachment("swept-row", recipientId = null, carriedAt = 0L, senderId = author, rowCovered = true),
                    CarriedAttachment("small-clip", recipientId = null, carriedAt = 0L, senderId = author, declaredSize = 400_000L),
                )
            rig.forwardStore.trimmed = mapOf("trimmed" to author)
            rig.transport.nearby.value = setOf(Peer(author)) // a Bluetooth link only: no Wi-Fi Aware route
            rig.stubProfileState(MutableStateFlow(0L))
            coEvery { rig.groups.observeGroups() } returns flowOf(emptyList())

            val passes = rig.countReArms()
            rig.manager.start()
            rig.await(1) { rig.askedHashes().size }
            rig.awaitStartupReArms(passes)
            assertEquals("only the small post is asked for", setOf("small-clip"), rig.blobAsks().mapTo(HashSet()) { it.second.hash })

            rig.bluetoothDownloadLimit.value = null // "Never ask": nothing is held, so both are carrier pulls again
            rig.await(3) { rig.askedHashes().size }
            assertEquals(setOf("small-clip", "swept-row", "trimmed"), rig.blobAsks().mapTo(HashSet()) { it.second.hash })
            assertTrue(rig.blobAsks().none { it.second.fastOnly != null })
            rig.manager.stop()
        }

    @Test
    fun tapToDownloadApprovesTheRowsAndAsksTheAuthorPlainAtOnce() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            val author = "author-node"
            coEvery { rig.messages.attachmentFetchRows("big", any()) } returns
                listOf(AttachmentFetchRow("big", author, 2_000_000L, true, false))
            rig.transport.nearby.value = setOf(Peer(author))

            rig.manager.downloadAttachment("big")

            coVerify { rig.messages.approveAttachmentFetch("big") }
            assertEquals(listOf(author to BlobReqContent("big", pull = BlobExchange.PULL_BUDGET)), rig.blobAsks())
        }

    private companion object {
        const val HYBRID_TEMPLATE = "DHKEM_X25519_HKDF_SHA256_HKDF_SHA256_AES_256_GCM_RAW"

        /** Real-time budget for work the manager's session runs off the test dispatcher (see awaitCustodiedProfiles). */
        const val AWAIT_MS = 10_000L
        const val POLL_MS = 5L
    }

    // --- self hygiene at start ---

    /**
     * What the self-pin loop (`45763fb6`) left behind on a phone that ran it: the `peers` row is gone since
     * 2.6.0's first start, but the session the ratchet opened with ourselves and the seed-outbox rows the
     * group flush wrote toward us are keyed on our own id and nothing else removes them — the ratchet dump
     * walks `peers`, so they do not even show. Start sweeps all three, and only for our own id: another
     * peer's session survives untouched.
     */
    @Test
    fun startForgetsTheSessionAndOutboxRowsADeviceHeldWithItself() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            val store = RatchetRepository(rig.db.ratchetDao(), clock = { rig.now })
            store.upsertSession(selfSession(rig.me.nodeId))
            store.upsertSession(selfSession(rig.bob.nodeId))

            rig.manager.start()

            rig.await(1) { if (runBlocking { store.session(rig.me.nodeId) } == null) 1 else 0 }
            assertNotNull("another peer's session is not the sweep's business", store.session(rig.bob.nodeId))
            coVerify { rig.peers.forgetSelf(rig.me.nodeId) }
            // The outbox sweep runs after the session delete on the manager's real-thread session, so seeing
            // the row gone does not mean it has been called yet: wait for it rather than race it.
            coVerify(timeout = AWAIT_MS) { rig.groups.forgetSelf(rig.me.nodeId) }
        }

    private fun selfSession(peerId: String) =
        RatchetEngine.SessionState(
            peerId = peerId,
            confirmed = false,
            weAreInitiator = true,
            root = ByteArray(32) { 1 },
            establishedAt = 1L,
            sendEpoch = 4,
            sendEpochPub = ByteArray(32) { 2 },
            sendChainKey = ByteArray(32) { 3 },
            sendEpochStartedAt = 1L,
            highestPeAcked = 0,
        )

    // --- the link-set watcher outlives a failed pass (#206) ---

    /**
     * A newcomer hook that throws ends that pass, not the watcher: the next link-set change is acted on, and the newcomer
     * whose hooks failed is a newcomer again, never marked acted on. Before, the throw ended the collector for the
     * session, and no later departure or link-up was acted on.
     */
    @Test
    fun aLinkSetPassThatThrowsCostsThatPassAloneAndItsNewcomerIsActedOnAgain() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.stubProfileState(MutableStateFlow(0L))
            coEvery { rig.groups.observeGroups() } returns flowOf(emptyList())
            rig.forwardStore.liveIdsFail = true
            rig.manager.start()
            val bob = Peer(rig.bob.nodeId)

            rig.transport.nearby.value = setOf(bob)
            rig.await(1) { rig.failedPasses() }
            assertEquals("its departure step ran before the throw", setOf(bob.nodeId), rig.manager.linksActedOn)
            assertTrue(rig.transport.digestsTo.isEmpty())

            rig.forwardStore.liveIdsFail = false
            val carol = Peer("carol-node")
            rig.transport.nearby.value = setOf(bob, carol)
            rig.await(2) { rig.digestedPeers() }
            assertEquals(setOf(bob.nodeId, carol.nodeId), rig.manager.linksActedOn)
            assertEquals("bob's hooks ran again, beside carol's", setOf(bob.nodeId, carol.nodeId), rig.transport.digestsTo.toSet())
            assertEquals(1L, rig.metrics.frames().roundsFailedBy[MeshRound.NEIGHBORS])
            rig.manager.stop()
        }

    /**
     * `neighbors` emits only on a change, so a pass that failed on a link set that then holds still is re-run by the
     * re-offer round: the newcomer's profile push, which the round alone never sends, goes again.
     */
    @Test
    fun aFailedLinkSetPassIsRunAgainByTheReofferRound() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.stubProfileState(MutableStateFlow(0L))
            coEvery { rig.groups.observeGroups() } returns flowOf(emptyList())
            rig.forwardStore.liveIdsFail = true
            rig.manager.start()
            val bob = Peer(rig.bob.nodeId)

            fun profilePushes() =
                rig.transport.sent.count { (wire, to) -> to == bob && WireCodec.decodeEnvelope(wire.signed)?.type == FrameType.PROFILE }

            rig.transport.nearby.value = setOf(bob)
            rig.await(1) { rig.failedPasses() }
            assertEquals("the push ran before the digest threw", 1, profilePushes())

            rig.forwardStore.liveIdsFail = false
            rig.manager.reofferToNeighbors()
            assertEquals("the owed pass ran inside the round", 2, profilePushes())
            assertEquals("its digest and the round's own", listOf(bob.nodeId, bob.nodeId), rig.transport.digestsTo.toList())

            rig.manager.reofferToNeighbors()
            assertEquals("and once it ran to its end, nothing more is owed", 2, profilePushes())
            rig.manager.stop()
        }

    /**
     * A newcomer whose pass failed after its asks went out is still departed when it goes (#140): the ask on it moves to
     * the next holder at once, not at the first ask's grace. The pass never marked it known, and departures read against
     * the known links would never see it go.
     */
    @Test
    fun aNewcomerWhosePassFailedIsStillDepartedAndItsAskMovesOnAtOnce() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.stubProfileState(MutableStateFlow(0L))
            coEvery { rig.groups.observeGroups() } returns flowOf(emptyList())
            val p = Peer(rig.bob.nodeId)
            val q = Peer("q-node")
            val wanted = AtomicBoolean(false)
            coEvery { rig.messages.attachmentsNeedingFetch(any()) } answers {
                if (wanted.get()) listOf(missing("h", p.nodeId, null)) else emptyList()
            }
            val passes = rig.countReArms()
            rig.manager.start()
            rig.transport.nearby.value = setOf(q)
            rig.awaitStartupReArms(passes) // the limit collector's re-arm and q's newcomer pass, so neither asks q
            rig.await(1) { if (rig.transport.everyCollectorHandled(setOf(q))) 1 else 0 } // and q's pass has run to its end

            wanted.set(true)
            rig.forwardStore.liveIdsFail = true
            rig.transport.nearby.value = setOf(q, p)
            rig.await(1) { rig.failedPasses() }
            assertEquals("p, the author, is asked alone; its pass then threw", listOf(p.nodeId to "h"), rig.hAsks())

            rig.forwardStore.liveIdsFail = false
            rig.transport.nearby.value = setOf(q)
            rig.await(1) { if (rig.manager.linksActedOn == setOf(q.nodeId)) 1 else 0 }
            assertEquals(
                "p's departure moved the ask to q before the pass marked it acted on",
                listOf(p.nodeId to "h", q.nodeId to "h"),
                rig.hAsks(),
            )
            rig.manager.stop()
        }

    /**
     * A retry of a failed pass and the watcher's own pass act in the order the watcher was handed the link sets: a retry
     * that read a newer set than one the watcher holds queued would be undone by it, departing a peer still linked and
     * acting on it as a newcomer twice.
     */
    @Test
    fun aRetryNeverActsOnALinkSetNewerThanTheWatcherHolds() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.stubProfileState(MutableStateFlow(0L))
            coEvery { rig.groups.observeGroups() } returns flowOf(emptyList())
            rig.forwardStore.liveIdsFail = true
            rig.manager.start()
            val p = Peer(rig.bob.nodeId)
            val s = Peer("s-node")
            val r = Peer("r-node")
            rig.transport.nearby.value = setOf(p)
            rig.await(1) { rig.failedPasses() }

            // The first retry holds the pass lock in its digest; the second queues behind it, then the watcher behind that.
            rig.forwardStore.liveIdsFail = false
            val gate = CompletableDeferred<Unit>()
            rig.forwardStore.liveIdsGate = gate
            val first = launch(start = CoroutineStart.UNDISPATCHED) { rig.manager.reofferToNeighbors() }
            rig.await(1) { rig.forwardStore.liveIdsWaiting.get() }
            // Undispatched: it has reached the lock, queued behind the first, when launch returns.
            val second = launch(start = CoroutineStart.UNDISPATCHED) { rig.manager.reofferToNeighbors() }
            rig.transport.nearby.value = setOf(p, s)
            rig.await(1) { if (rig.transport.everyCollectorHanded(setOf(p, s))) 1 else 0 }
            rig.transport.nearby.value = setOf(p, s, r)
            gate.complete(Unit)
            first.join()
            second.join()
            rig.await(1) { if (rig.transport.everyCollectorHandled(setOf(p, s, r))) 1 else 0 }

            assertEquals("r is a newcomer once", 1, rig.profilePushesTo(r))
            assertEquals(setOf(p.nodeId, s.nodeId, r.nodeId), rig.manager.linksActedOn)
            rig.manager.stop()
        }

    /** How many link-set passes failed ([MeshRound.NEIGHBORS]). */
    private fun Rig.failedPasses(): Int = metrics.frames().roundsFailedBy[MeshRound.NEIGHBORS]?.toInt() ?: 0

    /** Every blob request for the hash `h`, as (to, hash), in send order. */
    private fun Rig.hAsks(): List<Pair<String?, String>> = blobAsks().map { it.first to it.second.hash }.filter { it.second == "h" }

    /** How many profile pushes went to [peer] point to point — a newcomer pass's, never a round's. */
    private fun Rig.profilePushesTo(peer: Peer): Int =
        transport.sent.count { (wire, to) -> to == peer && WireCodec.decodeEnvelope(wire.signed)?.type == FrameType.PROFILE }

    /** How many peers got a custody digest. */
    private fun Rig.digestedPeers(): Int = transport.digestsTo.toSet().size

    // --- people met ---

    /**
     * A phone entering the nearby set is recorded as met, once — the same set the header's count reads, so
     * "nearby" and "met" agree about what proximity means. A peer that leaves and returns never adds a row
     * (the repository test covers the `lastMetAt` touch; a StateFlow collector may conflate the leave away).
     */
    @Test
    fun aNearbyNewcomerIsRecordedAsMetOnce() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.manager.start()

            rig.transport.nearby.value = setOf(Peer(rig.bob.nodeId))
            rig.await(1) { runBlocking { rig.db.metPeerDao().count() } }
            rig.transport.nearby.value = emptySet()
            rig.clockNow = rig.now + 5_000
            rig.transport.nearby.value = setOf(Peer(rig.bob.nodeId), Peer("stranger"))
            rig.await(2) { runBlocking { rig.db.metPeerDao().count() } }

            val bob = rig.db.metPeerDao().find(rig.bob.nodeId)!!
            assertEquals("the first sighting's stamp stays", rig.now, bob.firstMetAt)
            assertEquals(
                "the stranger is the only new row",
                rig.now + 5_000,
                rig.db
                    .metPeerDao()
                    .find("stranger")!!
                    .firstMetAt,
            )
            assertEquals(2, rig.db.metPeerDao().count())
        }

    // --- the first start after a backup restore (ADR 2026-09.6mj7, ADR 2026-09.qerd) ---

    /**
     * #86: a contact we share only a group with lost its session in the restore as surely as a DM peer did —
     * group seeds travel as control DMs — so it gets a reset too. And the resets go out before the profile
     * bump, whose arrival makes a peer flush its seeds under whatever session it holds at that moment.
     */
    @Test
    fun aRestoreResetsEveryWipedPeerBeforeItsProfileBump() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.stubProfileState(MutableStateFlow(0L))
            rig.stubHealState()
            val restorePending = MutableStateFlow(true)
            coEvery { rig.settings.restorePending } returns restorePending
            coEvery { rig.settings.clearRestorePending() } answers {
                restorePending.value = false
                mockk(relaxed = true)
            }
            val carol = party()
            val dave = party()
            rig.pinRatchetCapable(rig.bob, RatchetCrypto.generateKeyPair().pub)
            rig.pinRatchetCapable(carol, RatchetCrypto.generateKeyPair().pub)
            rig.pinRatchetCapable(dave, RatchetCrypto.generateKeyPair().pub)
            coEvery { rig.messages.distinctConversations() } returns listOf(rig.bob.nodeId, "g-room")
            coEvery { rig.groups.active() } returns
                listOf(
                    GroupEntity(
                        groupId = "g-1",
                        name = "Group",
                        members = GroupMembersStore.encode(listOf(rig.me.nodeId, rig.bob.nodeId, carol.nodeId)),
                        createdBy = rig.me.nodeId,
                        createdAt = 1L,
                    ),
                    GroupEntity(
                        groupId = "g-2",
                        name = "Other",
                        members = GroupMembersStore.encode(listOf(rig.me.nodeId, dave.nodeId)),
                        createdBy = dave.nodeId,
                        createdAt = 1L,
                    ),
                )

            rig.manager.start()
            rig.await(1) { if (restorePending.value) 0 else 1 }

            val sent =
                rig.transport.sent
                    .mapNotNull { WireCodec.decodeEnvelope(it.first.signed) }
                    .distinctBy { it.id }
            val resets =
                sent.filter {
                    it.type == FrameType.CHAT &&
                        WireCodec
                            .decodePayload<ChatContent>(it.payload)
                            ?.enc
                            ?.r
                            ?.flags == RatchetHeader.FLAG_RESET
                }
            assertEquals(
                "one reset per wiped peer: the DM peer, and each group's other members, never ourselves",
                setOf(rig.bob.nodeId, carol.nodeId, dave.nodeId),
                resets.map { it.recipientId }.toSet(),
            )
            assertEquals(3, resets.size)
            val bump = sent.indexOfFirst { it.type == FrameType.PROFILE }
            assertTrue("the profile bump went out", bump >= 0)
            assertTrue("every reset precedes the profile bump", resets.all { sent.indexOf(it) < bump })
        }

    // --- the heal basket (work item #63) ---

    @Test
    fun healRunsTheRetentionSweepsAndTheCustodyReplayOncePastTheirHourNotOnEveryCall() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.stubProfileState(MutableStateFlow(0L))
            rig.stubHealState()

            rig.manager.start()
            rig.await(1) { rig.custodiedProfiles().size } // the start's own pass sweeps and replays before it seeds
            assertEquals(1, rig.groupRoots.sweeps.get())
            assertEquals(1, rig.forwardStore.groupChatReads.get())

            rig.clockNow += HealFloors.RETENTION_SWEEP_MS / 4 // the first heartbeat, a quarter of an hour on
            rig.healAndAwait()
            assertEquals("a heal inside the hour leaves the retention sweep to the next", 1, rig.groupRoots.sweeps.get())
            assertEquals("a heal inside the hour leaves the custody replay to the next", 1, rig.forwardStore.groupChatReads.get())

            rig.clockNow += HealFloors.RETENTION_SWEEP_MS
            rig.healAndAwait()
            assertEquals(2, rig.groupRoots.sweeps.get())
            assertEquals(2, rig.forwardStore.groupChatReads.get())

            rig.clockNow += HealFloors.GROUP_REPLAY_MS - 1
            rig.healAndAwait()
            assertEquals("the floor is measured from the last run, not the last heal", 2, rig.groupRoots.sweeps.get())
            assertEquals(2, rig.forwardStore.groupChatReads.get())
        }

    @Test
    fun aHealWhileTheBasketIsStillRunningPokesTheRadiosAndLaunchesNoSecondBasket() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.stubProfileState(MutableStateFlow(0L))
            rig.stubHealState()
            rig.manager.start()
            rig.await(1) { rig.custodiedProfiles().size }
            val healsBefore =
                rig.metrics
                    .frames()
                    .healsCompleted
                    .toInt()
            val sweepsBefore = rig.forwardStore.sweeps.get()

            val hold = CompletableDeferred<Unit>()
            rig.forwardStore.holdSweep = hold
            rig.manager.heal() // the basket parks on its first step
            rig.await(sweepsBefore + 1) { rig.forwardStore.sweeps.get() }
            rig.manager.heal() // the pocket's motion trigger a second ahead of the open app's resume
            rig.manager.heal()
            assertEquals("every heal reaches the radios", 3, rig.transport.heals.get())

            hold.complete(Unit)
            rig.await(healsBefore + 1) {
                rig.metrics
                    .frames()
                    .healsCompleted
                    .toInt()
            }
            assertEquals("one basket ran for the three heals", sweepsBefore + 1, rig.forwardStore.sweeps.get())

            rig.forwardStore.holdSweep = null
            rig.healAndAwait() // the finished basket no longer stands in the way of the next
            assertEquals(sweepsBefore + 2, rig.forwardStore.sweeps.get())
        }

    // --- profile propagation ---

    @Test
    fun profileEditPublishesUnderAFreshFrameIdSoNothingDedupesItAwayAsAlreadySeen() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            val publishedAt = MutableStateFlow(0L)
            val displayName = rig.stubProfileState(publishedAt)

            rig.manager.start()
            rig.await(1) { rig.custodiedProfiles().size } // the startup custody seed, still nameless
            val seeded = rig.custodiedProfiles().single()

            rig.awaitProfileWatcher() // the edit must not beat the watcher's combine to the flow
            // A real edit lands at a later instant than the seed — as the lab capture showed, a display name
            // is typically saved seconds after onboarding published the (still nameless) profile.
            rig.clockNow = rig.now + 26_000
            displayName.value = "Alex"
            rig.await(1) { rig.floodedProfiles().size } // the edit re-floods either way; the id is the question

            val flooded = rig.floodedProfiles().single()
            assertEquals("the edit floods the new name", "Alex", WireCodec.decodePayload<ProfileContent>(flooded.payload)?.name)
            assertNotEquals(
                "the edit must carry a FRESH frame id — under the seeded id a receiver drops it at the " +
                    "SeenSet before handleProfile ever parses it, and the new name is never applied",
                seeded.id,
                flooded.id,
            )

            val custodied = rig.custodiedProfiles()
            assertEquals(
                "and it must reach custody as its own row: on a repeated id ForwardSync.onSeen " +
                    "short-circuits on store.has(id), leaving the pre-edit bytes to be re-served",
                2,
                custodied.size,
            )
            assertEquals(
                "the row a late joiner is re-served carries the edited name",
                "Alex",
                WireCodec.decodePayload<ProfileContent>(custodied.last().payload)?.name,
            )
        }

    @Test
    fun aSettingsWriteThatChangesNothingNeverRepublishesTheProfile() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            val publishedAt = MutableStateFlow(0L)
            rig.stubProfileState(publishedAt)

            rig.manager.start()
            rig.await(1) { rig.custodiedProfiles().size } // the startup custody seed
            rig.awaitProfileWatcher()

            // What a write to ANY DataStore key looks like to the watcher: every projection re-emits its
            // unchanged value. With `drop(1)` ahead of `distinctUntilChanged` this was the first value distinct
            // ever saw, and every launch published a "changed" profile on the first settings write after start.
            rig.status.emit("")
            withContext(Dispatchers.Default) { delay(300) }
            assertEquals("nothing changed, so nothing floods", 0, rig.floodedProfiles().size)
            assertEquals("and custody keeps the one seeded row", 1, rig.custodiedProfiles().size)

            // A real edit still publishes, once.
            rig.clockNow = rig.now + 26_000
            rig.status.emit("out walking")
            rig.await(1) { rig.floodedProfiles().size }
            assertEquals("out walking", WireCodec.decodePayload<ProfileContent>(rig.floodedProfiles().single().payload)?.status)
        }

    /**
     * The first-sighting reflood (#73): a peer flapping in and out of `reachable` — a Wi-Fi Aware edge peer
     * across the 150 s linger — re-enters as a newcomer every time, but the frame id is stable and every
     * receiver drops a copy inside its SeenSet window, so re-flooding for it is airtime nobody keeps. One
     * flood per (frame, peer) per window; a genuinely new peer still floods, and a lapsed window floods again.
     */
    @Test
    fun aLingerFlapDoesNotRefloodTheProfileInsideTheSeenWindow() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.stubProfileState(MutableStateFlow(0L))
            rig.manager.start()
            rig.await(1) { rig.custodiedProfiles().size } // the startup custody seed
            val id = rig.custodiedProfiles().single().id

            rig.transport.sighted.value = setOf(Peer(rig.bob.nodeId)) // first sighting: the bootstrap flood
            rig.await(1) { rig.profileCopiesOnWire(id) }

            // Out past the linger and back, well past the 30 s floor: a newcomer again, but one that holds the id.
            rig.clockNow += 40_000
            rig.flap(Peer(rig.bob.nodeId))
            withContext(Dispatchers.Default) { delay(300) }
            assertEquals("a flap inside the seen window is not a flood", 1, rig.profileCopiesOnWire(id))

            // A peer never flooded for is still owed the frame on first sighting.
            rig.clockNow += 40_000
            rig.transport.sighted.value = setOf(Peer(rig.bob.nodeId), Peer("stranger"))
            rig.await(2) { rig.profileCopiesOnWire(id) }

            // Once the receivers' window has lapsed, a returning peer would keep a copy again — so it gets one.
            rig.clockNow += SeenSet.DEFAULT_TTL_MS + 1
            rig.flap(Peer(rig.bob.nodeId))
            rig.await(3) { rig.profileCopiesOnWire(id) }
        }

    /** The memo is keyed on the frame too: an edit's fresh id is owed to a flapping peer whatever it held before. */
    @Test
    fun aProfileEditIsRefloodedToAFlappingPeerOnce() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            val displayName = rig.stubProfileState(MutableStateFlow(0L))
            rig.manager.start()
            rig.await(1) { rig.custodiedProfiles().size }
            val seeded = rig.custodiedProfiles().single().id
            rig.awaitProfileWatcher()

            rig.transport.sighted.value = setOf(Peer(rig.bob.nodeId))
            rig.await(1) { rig.profileCopiesOnWire(seeded) }

            rig.clockNow = rig.now + 26_000
            displayName.value = "Alex"
            rig.await(2) { rig.floodedProfiles().size } // the edit floods under a fresh id, to everyone
            val edited = rig.floodedProfiles().map { it.id }.single { it != seeded }
            assertEquals(1, rig.profileCopiesOnWire(edited))

            // Bob flaps: it was memoed for the seeded frame, never for this one — so it floods, once.
            rig.clockNow += 40_000
            rig.flap(Peer(rig.bob.nodeId))
            rig.await(2) { rig.profileCopiesOnWire(edited) }

            rig.clockNow += 40_000
            rig.flap(Peer(rig.bob.nodeId))
            withContext(Dispatchers.Default) { delay(300) }
            assertEquals("the second flap is inside the window", 2, rig.profileCopiesOnWire(edited))
            assertEquals("and the seeded frame was never re-flooded", 1, rig.profileCopiesOnWire(seeded))
        }

    @Test
    fun anEditMadeWhileTheMeshWasStoppedIsPublishedWhenItStarts() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            val publishedAt = MutableStateFlow(0L)
            val displayName = rig.stubProfileState(publishedAt)

            rig.manager.start()
            rig.await(1) { rig.custodiedProfiles().size } // the startup custody seed, still nameless
            rig.awaitProfileWatcher()
            val seeded = rig.custodiedProfiles().single()
            rig.manager.stop()

            // The name is set while the mesh is off: no watcher runs, and the next start finds a stamp that
            // is not stale, so the seed reuses the custodied — nameless — bytes. The watcher's first value
            // is what catches it: custody presents something other than the settings, so it publishes once.
            displayName.value = "Alex"
            rig.clockNow = rig.now + 26_000
            rig.manager.start()
            rig.await(1) { rig.floodedProfiles().size }
            val flooded = rig.floodedProfiles().single()
            assertEquals("Alex", WireCodec.decodePayload<ProfileContent>(flooded.payload)?.name)
            assertNotEquals("under a fresh id, so nobody dedupes it as the seed", seeded.id, flooded.id)
        }

    @Test
    fun aBuildThatGainsACapabilityRepublishesItsProfileWhenItStarts() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            rig.stubProfileState(MutableStateFlow(0L))

            rig.manager.start()
            rig.await(1) { rig.custodiedProfiles().size }
            rig.awaitProfileWatcher()
            val seeded = rig.custodiedProfiles().single()
            assertEquals(0L, WireCodec.decodePayload<ProfileContent>(seeded.payload)!!.capabilities!! and Protocol.CAP_CALL)
            assertTrue("an unchanged build publishes nothing at start", rig.floodedProfiles().isEmpty())
            rig.manager.stop()

            // The same phone's next build wires calls. Its stamp is not stale, so without the capability check the
            // start would reuse the held bytes, and peers would not learn CAP_CALL until an edit or the 12 h republish.
            val calling = rig.build(extraCapabilities = Protocol.CAP_CALL)
            rig.clockNow = rig.now + 26_000
            calling.start()
            rig.await(1) { rig.floodedProfiles().size }
            val flooded = rig.floodedProfiles().single()
            assertEquals(Protocol.CAP_CALL, WireCodec.decodePayload<ProfileContent>(flooded.payload)!!.capabilities!! and Protocol.CAP_CALL)
            assertNotEquals("under a fresh stamp, never a second set of bytes under the old one", seeded.id, flooded.id)
            calling.stop()
        }

    @Test
    fun theProfileAdvertisesTheBoundBoardAndRepublishesWhenItChangesOrClears() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            val publishedAt = MutableStateFlow(0L)
            rig.stubProfileState(publishedAt)

            rig.manager.start()
            rig.await(1) { rig.custodiedProfiles().size } // the startup custody seed, no board
            assertNull(WireCodec.decodePayload<ProfileContent>(rig.custodiedProfiles().single().payload)!!.loraNode)

            rig.awaitProfileWatcher()
            rig.clockNow = rig.now + 26_000
            rig.loraBoard.value = LoraBoard(0xdeadbeefL, "oR62IJmFUE0Tgcw0GcypU5ZqUFCQllVBy2snB/BKQA4=") // one edit: one flood
            rig.await(1) { rig.floodedProfiles().size }
            val claimed = WireCodec.decodePayload<ProfileContent>(rig.floodedProfiles().single().payload)!!
            assertEquals("the profile now names the board", 0xdeadbeefL, claimed.loraNode)
            assertEquals("and the key it signs under", "oR62IJmFUE0Tgcw0GcypU5ZqUFCQllVBy2snB/BKQA4=", claimed.loraKey)

            rig.clockNow = rig.now + 52_000
            rig.loraBoard.value = null // unbound: the next profile omits both, which is how a clear arrives
            rig.await(2) { rig.floodedProfiles().size }
            val cleared = WireCodec.decodePayload<ProfileContent>(rig.floodedProfiles().last().payload)!!
            assertNull(cleared.loraNode)
            assertNull(cleared.loraKey)
        }

    @Test
    fun aBoardWithoutASigningKeyAdvertisesOnlyItsNumber() =
        runTest(UnconfinedTestDispatcher()) {
            // A pre-2.8 board reports in with no key (the transport withholds one that would verify nothing).
            val rig = Rig(backgroundScope)
            val publishedAt = MutableStateFlow(0L)
            rig.stubProfileState(publishedAt)
            rig.manager.start()
            rig.await(1) { rig.custodiedProfiles().size }
            rig.awaitProfileWatcher()
            rig.clockNow = rig.now + 26_000
            rig.loraBoard.value = LoraBoard(0xdeadbeefL, key = null)
            rig.await(1) { rig.floodedProfiles().size }
            val content = WireCodec.decodePayload<ProfileContent>(rig.floodedProfiles().single().payload)!!
            assertEquals(0xdeadbeefL, content.loraNode)
            assertNull("no key, no key field", content.loraKey)
        }

    @Test
    fun switchingOpenToChatOnRepublishesAProfileCarryingTheFlag() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            val publishedAt = MutableStateFlow(0L)
            rig.stubProfileState(publishedAt)

            rig.manager.start()
            rig.await(1) { rig.custodiedProfiles().size } // the startup custody seed, flag off
            assertFalse(WireCodec.decodePayload<ProfileContent>(rig.custodiedProfiles().single().payload)!!.openToChat)

            rig.awaitProfileWatcher()
            rig.clockNow = rig.now + 26_000
            rig.openToChat.value = true // the switch on the Settings screen: a profile edit like a rename
            rig.await(1) { rig.floodedProfiles().size }

            val flooded = rig.floodedProfiles().single()
            assertTrue(
                "the flip publishes a fresh profile carrying the flag",
                WireCodec.decodePayload<ProfileContent>(flooded.payload)!!.openToChat,
            )
        }
}
