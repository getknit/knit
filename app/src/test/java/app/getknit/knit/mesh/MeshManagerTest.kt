package app.getknit.knit.mesh

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
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
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.group.toFoundingInfo
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MentionStore
import app.getknit.knit.data.message.MessageEntity
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
import app.getknit.knit.mesh.protocol.ReactionContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.mesh.protocol.TransferPayload
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.spool.CommonsMember
import app.getknit.knit.mesh.spool.CommonsRoom
import app.getknit.knit.mesh.spool.CommonsRoots
import app.getknit.knit.mesh.spool.CommonsStore
import app.getknit.knit.mesh.spool.GroupRootStore
import app.getknit.knit.mesh.spool.hex
import app.getknit.knit.moderation.ImageScreeningService
import app.getknit.knit.moderation.ScopedTextModerator
import app.getknit.knit.moderation.TextVerdict
import app.getknit.knit.notifications.Notifier
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.stateIn
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
import java.util.concurrent.CopyOnWriteArrayList
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
        val hybrid = KeysetHandle.generateNew(KeyTemplates.get(HYBRID_TEMPLATE))
        val sig = KeysetHandle.generateNew(KeyTemplates.get("ED25519_RAW"))
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
        override val neighbors = nearby.asStateFlow()

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

        override suspend fun send(
            wire: WireEnvelope,
            to: Peer?,
        ) {
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

        override suspend fun sendDigest(
            to: Peer,
            ids: List<String>,
        ) = Unit
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

        override suspend fun liveIds(now: Long): List<String> = synchronized(frames) { frames.keys.toList() }

        override suspend fun attachmentHashesNeedingFetch(): List<String> = emptyList()

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
        scope: CoroutineScope,
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

        /** What the board was handed by [MeshManager.sendPublicPost], and what it answers. */
        val publicPosts = mutableListOf<String>()
        var publicChannelRefusal: PublicPostRefusal? = null
        val manager: MeshManager

        init {
            coEvery { identity.nodeId() } returns me.nodeId
            coEvery { settings.displayName } returns displayName
            coEvery { settings.loraBoard } returns loraBoard
            // The local delivery of anything we originate runs the inbound path, whose first act is a
            // blocklist read with `.first()`. A relaxed mock's empty flow throws there rather than hanging.
            coEvery { settings.blockedNodeIds } returns MutableStateFlow(emptySet())
            coEvery { settings.acceptedConversations } returns MutableStateFlow(emptySet())
            coEvery { textModeration.classify(any(), any()) } returns TextVerdict.ALLOWED
            coEvery { messages.save(any()) } answers { saved += firstArg<MessageEntity>() }
            coEvery { peers.find(any()) } returns null // default: no recipient key is known
            coEvery { blobs.bytes(any()) } returns null
            manager =
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
                )
        }

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
            val before = metrics.snapshot().healsCompleted.toInt()
            manager.heal()
            await(before + 1) { metrics.snapshot().healsCompleted.toInt() }
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
            assertEquals("dinner at 7?", post.chat.body)
            assertNull(post.chat.enc)
            // Spool only: nothing originated, nothing custodied, and the row went through the commons store.
            assertTrue(rig.transport.sent.isEmpty())
            assertTrue(rig.transport.fastFanouts.isEmpty())
            assertTrue(rig.forwardStore.frames().isEmpty())
            assertTrue(rig.saved.isEmpty())
        }

    @Test
    fun aCommonsPostIsRefusedByRoomModerationAndByARoomThisDeviceIsNotIn() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeCommonsStore(roomId, roomSecret)
            val rig = Rig(backgroundScope, commons = store)
            coEvery { rig.textModeration.classify(any(), true) } returns
                TextVerdict(allowed = false, category = TextVerdict.Category.TOXICITY)

            assertFalse(rig.manager.sendCommons(roomId, "something vile", emptyList(), null))
            coEvery { rig.textModeration.classify(any(), any()) } returns TextVerdict.ALLOWED
            assertFalse(rig.manager.sendCommons(Conversations.commonsIdFor("ff".repeat(32)), "hello?", emptyList(), null))
            advanceUntilIdle()

            assertTrue(store.posted.isEmpty())
            assertTrue(rig.transport.sent.isEmpty())
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
    fun farPeerFramesAreTheNewestCarriedDmsToThePeerMinusOurAckedOnes() =
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

            // Newest first; our acked DM and our row-less (ctl) frame are dropped, a relayed frame is always offered.
            assertEquals(listOf("relayed", unacked.id), ids)
            assertFalse(acked.id in ids)
        }

    @Test
    fun farPeerFramesAreCappedAtTheReofferLimit() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)
            val carol = party()
            repeat(6) { i ->
                rig.forwardStore.store(carried("r$i", carol.nodeId, rig.bob.nodeId, rig.now + i), ForwardStore.ORIGIN_RELAY, rig.now)
            }
            val ids = rig.manager.framesFor(rig.bob.nodeId).map { WireCodec.decodeEnvelope(it.signed)!!.id }
            assertEquals(listOf("r5", "r4", "r3", "r2"), ids)
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
    fun theSpoolFetcherStoresAnAttachmentUnderTheMimeOurOwnDecryptedRowNames() =
        runTest(UnconfinedTestDispatcher()) {
            // Since ADR 035 a sealed frame names no mime, so ScopeSync hands this seam only its §9.5 default.
            // The row — written from the *sealed* MessageContent — is what actually knows the type, and a
            // voice note stored as image/jpeg would render as a broken photo instead of a waveform.
            val rig = Rig(backgroundScope)
            val store = rig.manager.scopeBlobs()
            val bytes = "sealed-attachment-bytes".toByteArray()
            coEvery { rig.messages.attachmentMimeForHash("voice-ct-hash") } returns VoiceAudio.MIME
            coEvery { rig.messages.attachmentMimeForHash("group-photo-hash") } returns null

            store.save("voice-ct-hash", "image/jpeg", bytes)
            store.save("group-photo-hash", "image/jpeg", bytes)

            coVerify { rig.blobs.insert("voice-ct-hash", VoiceAudio.MIME, bytes) }
            // No row names a group photo or an avatar, so the fetcher's default still stands — unchanged.
            coVerify { rig.blobs.insert("group-photo-hash", "image/jpeg", bytes) }
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
            assertEquals(1L, rig.metrics.snapshot().receiptsCustodied)
            assertEquals(1L, rig.metrics.snapshot().receiptsCoalesced)
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
            assertEquals(2L, rig.metrics.snapshot().receiptsCoalesced)
            assertEquals("a reply is content, never a tick", listOf(FanoutHint.CONTENT, FanoutHint.CONTENT), rig.transport.longRangeHints)

            val carol = party()
            rig.pinWithCaps(carol, RatchetCrypto.generateKeyPair().pub, Protocol.LOCAL_CAPABILITIES and Protocol.CAP_INLINE_ACK.inv())
            rig.manager.dmAcks.hold(carol.nodeId, "c-1")
            assertTrue(rig.manager.sendChat("plain", recipientId = carol.nodeId))
            advanceUntilIdle()
            assertEquals(listOf("c-1"), rig.manager.dmAcks.pending(carol.nodeId))
            assertEquals(2L, rig.metrics.snapshot().receiptsCoalesced)
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

            assertEquals("the DM ack rode", 1L, rig.metrics.snapshot().receiptsCoalesced)
            assertEquals("and the room ticks filled the rest of the slots", 2L, rig.metrics.snapshot().receiptsRidden)
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
            assertEquals(1L, rig.metrics.snapshot().receiptsCustodied)
            assertEquals(2L, rig.metrics.snapshot().receiptsRidden)
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
            assertEquals("no room left on a full LoRa tick", 0L, rig.metrics.snapshot().receiptsRidden)
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
            assertEquals(1L, rig.metrics.snapshot().receiptsCustodied)
            assertEquals(1L, rig.metrics.snapshot().receiptsRidden)
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
            assertEquals(1L, rig.metrics.snapshot().receiptsSealed)
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
            assertEquals("no chain key spent on nobody", 0L, rig.metrics.snapshot().receiptsSealed)
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
            assertEquals(0L, rig.metrics.snapshot().receiptsCoalesced)
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
            assertEquals(1L, rig.metrics.snapshot().dmSealedV2)

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
            assertEquals(1L, rig.metrics.snapshot().dmSealedV3)
            assertEquals("v3 is still a ratchet seal", 1L, rig.metrics.snapshot().dmSealedV2)

            // Inline acks ride the v3 arm too, and are taken out of the coalescer like on v2.
            rig.manager.dmAcks.hold(rig.bob.nodeId, FrameId.new())
            assertTrue(rig.manager.sendChat("reply", recipientId = rig.bob.nodeId))
            advanceUntilIdle()
            assertTrue(
                rig.manager.dmAcks
                    .pending(rig.bob.nodeId)
                    .isEmpty(),
            )
            assertEquals(1L, rig.metrics.snapshot().receiptsCoalesced)
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
            assertEquals(1L, rig.metrics.snapshot().receiptsCustodied)
            assertEquals(0L, rig.metrics.snapshot().ticksUnsigned)
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
            assertEquals(0L, rig.metrics.snapshot().dmSealedV3)
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
            assertEquals(0L, rig.metrics.snapshot().dmSealedV2)
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
            assertEquals(1L, rig.metrics.snapshot().dmSealedV1Fallback)
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
            assertEquals(1L, rig.metrics.snapshot().groupSealedRatchet)
            assertEquals(1L, rig.metrics.snapshot().groupSeedsSent)
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
            assertEquals(2L, rig.metrics.snapshot().groupSealedRatchet)
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
            assertEquals(0L, rig.metrics.snapshot().groupSealedRatchet)
            // Ineligible (not eligible-but-fell-back): the fallback counter stays untouched — DM semantics.
            assertEquals(0L, rig.metrics.snapshot().groupSealedV1Fallback)
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
            assertEquals(2L, rig.metrics.snapshot().groupSeedsSent)
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
            assertEquals(1L, rig.metrics.snapshot().reactionsSealed)
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
            assertEquals(1L, rig.metrics.snapshot().reactionsSealedFallback)
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
            assertEquals(1L, rig.metrics.snapshot().reactionsSealed)
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
            assertEquals(1L, rig.metrics.snapshot().reactionsSealedFallback)
        }

    @Test
    fun aBroadcastReactionStaysCleartextByDesignAndCountsNoFallback() =
        runTest(UnconfinedTestDispatcher()) {
            val rig = Rig(backgroundScope)

            rig.manager.sendReaction("room1", "👍")
            advanceUntilIdle()

            assertEquals(1, rig.sentReactionFrames().size)
            assertTrue(rig.sentChatFrames().isEmpty())
            assertEquals(0L, rig.metrics.snapshot().reactionsSealedFallback)
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
            assertEquals(1L, rig.metrics.snapshot().reactionsSealed)
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
            assertEquals(1L, rig.metrics.snapshot().reactionsSealedFallback)
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
            assertEquals(0L, rig.metrics.snapshot().reactionsSealed)
            assertEquals(0L, rig.metrics.snapshot().reactionsSealedFallback)
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
            assertEquals(1L, rig.metrics.snapshot().reactionsSealed)
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
            coVerify { rig.groups.forgetSelf(rig.me.nodeId) }
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
                    .snapshot()
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
                    .snapshot()
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
