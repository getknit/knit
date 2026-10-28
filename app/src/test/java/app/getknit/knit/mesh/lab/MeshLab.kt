package app.getknit.knit.mesh.lab

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.room3.Room
import androidx.room3.executeSQL
import androidx.room3.useReaderConnection
import androidx.room3.withWriteTransaction
import androidx.test.core.app.ApplicationProvider
import app.getknit.knit.TextLimits
import app.getknit.knit.badge.BadgeKeys
import app.getknit.knit.badge.PeerBadges
import app.getknit.knit.call.CallManager
import app.getknit.knit.call.CallSignals
import app.getknit.knit.call.TurnServer
import app.getknit.knit.call.TurnSource
import app.getknit.knit.contacts.ContactCards
import app.getknit.knit.contacts.ContactImporter
import app.getknit.knit.contacts.ContactRemover
import app.getknit.knit.data.AttachmentIdentities
import app.getknit.knit.data.AttachmentStore
import app.getknit.knit.data.BlobRepository
import app.getknit.knit.data.GroupRepository
import app.getknit.knit.data.KnitDatabase
import app.getknit.knit.data.LabelAnchors
import app.getknit.knit.data.LabelFade
import app.getknit.knit.data.MeshBlobStore
import app.getknit.knit.data.MessageReceiptRepository
import app.getknit.knit.data.MessageRepository
import app.getknit.knit.data.PeerRepository
import app.getknit.knit.data.PinRepository
import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.VoiceAudio
import app.getknit.knit.data.backup.BackupTables
import app.getknit.knit.data.commons.CommonsRepository
import app.getknit.knit.data.crypto.IdentityKeyStore
import app.getknit.knit.data.crypto.KeystoreSecret
import app.getknit.knit.data.crypto.Unwrapped
import app.getknit.knit.data.draft.DraftRepository
import app.getknit.knit.data.forward.ForwardRepository
import app.getknit.knit.data.group.GroupEntity
import app.getknit.knit.data.group.GroupMembersStore
import app.getknit.knit.data.group.toGroupInfo
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.data.message.StatusNotices
import app.getknit.knit.data.peer.MetPeerRepository
import app.getknit.knit.data.peer.PeerEntity
import app.getknit.knit.data.ratchet.GroupRatchetRepository
import app.getknit.knit.data.ratchet.GroupRootRepository
import app.getknit.knit.data.ratchet.RatchetRepository
import app.getknit.knit.data.relay.RelayInviteApplier
import app.getknit.knit.data.settings.BadgeStore
import app.getknit.knit.data.settings.CallTombStore
import app.getknit.knit.data.settings.ContributionTotals
import app.getknit.knit.data.settings.HomeStore
import app.getknit.knit.data.settings.SettingsKeys
import app.getknit.knit.data.settings.SettingsStore
import app.getknit.knit.data.settings.TicketKeyStore
import app.getknit.knit.identity.Identity
import app.getknit.knit.identity.NodeId
import app.getknit.knit.mesh.BlobExchange
import app.getknit.knit.mesh.CompositeMeshTransport
import app.getknit.knit.mesh.ContributionLedger
import app.getknit.knit.mesh.DmAckCoalescer
import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.IngressBudget
import app.getknit.knit.mesh.IntroState
import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.PartialBlobs
import app.getknit.knit.mesh.RatchetPeerState
import app.getknit.knit.mesh.SPOOL_COVER_MS
import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.crypto.AttachmentCrypto
import app.getknit.knit.mesh.crypto.ContactCard
import app.getknit.knit.mesh.crypto.MessageCrypto
import app.getknit.knit.mesh.crypto.PublicKeyBundle
import app.getknit.knit.mesh.crypto.b64d
import app.getknit.knit.mesh.crypto.ratchet.GroupRatchetSessions
import app.getknit.knit.mesh.crypto.ratchet.RatchetCrypto
import app.getknit.knit.mesh.crypto.ratchet.RatchetSessions
import app.getknit.knit.mesh.crypto.ratchet.SessionTransactor
import app.getknit.knit.mesh.link.FrameKey
import app.getknit.knit.mesh.lora.FakeMeshtasticAir
import app.getknit.knit.mesh.lora.FakeMeshtasticLink
import app.getknit.knit.mesh.lora.LoraConfig
import app.getknit.knit.mesh.lora.LoraGossipPolicy
import app.getknit.knit.mesh.lora.LoraMeshTransport
import app.getknit.knit.mesh.lora.LoraPacePolicy
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.EncEnvelope
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.PrekeyInfo
import app.getknit.knit.mesh.protocol.ProfileContent
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.ReplyRef
import app.getknit.knit.mesh.protocol.ScopeConfigPayload
import app.getknit.knit.mesh.protocol.StyleSpan
import app.getknit.knit.mesh.protocol.VideoInfo
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.sha256Hex
import app.getknit.knit.mesh.spool.FakeSpool
import app.getknit.knit.mesh.spool.PublicSpool
import app.getknit.knit.mesh.spool.PublicSpools
import app.getknit.knit.mesh.spool.RelayInvite
import app.getknit.knit.mesh.spool.ScopeConfig
import app.getknit.knit.mesh.spool.ScopeStatus
import app.getknit.knit.mesh.spool.ScopeSync
import app.getknit.knit.mesh.spool.spoolPresentPeers
import app.getknit.knit.moderation.ImageModerator
import app.getknit.knit.moderation.ImageScreeningService
import app.getknit.knit.moderation.ImageVerdict
import app.getknit.knit.moderation.ScopedTextModerator
import app.getknit.knit.moderation.TextVerdict
import app.getknit.knit.normalizeSingleLine
import app.getknit.knit.notifications.Notifier
import app.getknit.knit.ui.contacts.contactStanding
import app.getknit.knit.ui.contacts.observeContactSignals
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList

/**
 * **Mesh in a box**: N complete, real Knit stacks in one JVM — the real [MeshManager] (and so the real
 * `InboundPipeline`, `MeshRouter`, `ForwardSync`, `KeyExchange`, `AckSync`, ratchets), the Room-backed
 * repositories over Robolectric's in-memory SQLite, a real [IdentityKeyStore] over an in-memory secret, and a
 * DataStore-backed [SettingsStore] — linked through [LabTransport], an in-process [app.getknit.knit.mesh.MeshTransport]
 * with no radio behind it. The only doubles are the leaves with a hardware or UI side: the notifier, the tflite
 * text moderators and the image moderator.
 *
 * What it is for: the bug that lives *between* two nodes — A's real send order meeting B's real state — which
 * neither `MeshManagerTest` (real sender, recording transport, mocked repos) nor `InboundPipelineTest` (real
 * receiver, hand-built frames, mocked repos) can see, because each half is checked against a stand-in for the
 * other. The seed-before-roster race (cf94a06: the creator floods a group's sender-key seed before the frame
 * that carries its roster, and the receiver's DM ratchet consumed the seed for good) is the founding case.
 * Scenarios are written at user level (`createGroup`, `sendGroup`) and end in [assertConverged], the
 * universal oracle — every member holds the same decrypted messages — so a scenario catches what its author
 * did not think to assert.
 *
 * Time is real: `MeshManager.start` builds its session on `Dispatchers.Default`, so a scenario waits with
 * [await] rather than virtual time (the same reason `MeshManagerTest.await` exists). Nothing here is Koin;
 * the wiring mirrors `di/AppModule` + `di/MeshModule` by hand, so a constructor change shows up as a compile
 * error in this file rather than as a silently narrower rig.
 */
class MeshLab {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir: File = Files.createTempDirectory("meshlab").toFile()
    private val nodes = mutableListOf<LabNode>()

    /** The calendar every node reads; `clock.advance(ms)` moves it for all of them at once (see [LabClock]). */
    val clock = LabClock()

    /**
     * The seeded scheduling noise this scenario runs under ([LabChaos]), or null — the default — for a plain
     * run. Printed at once so a scenario that dies before any report still names its seed in its output.
     */
    internal val chaos: LabChaos? = LabChaos.current?.also { println("meshlab $it") }

    /**
     * Creates and starts a node — its own identity, database and settings file — and returns once its router
     * is listening, so a link made next cannot lose the profile push (see [LabTransport.collecting]).
     */
    internal suspend fun node(
        name: String,
        limits: LabLimits = LabLimits(),
        // A board on a shared air: the node gets the real LoRa plane beside its radio (ADR 2026-09.y5f3).
        air: FakeMeshtasticAir? = null,
        // A relay the node dials: the node gets the real Internet plane (`ScopeSync` over an in-process spool).
        spool: FakeSpool? = null,
        // Whether the rig opts the node into that relay at boot, as the relay editor would. False leaves the
        // plane off with the relay unknown — the state a relay invite (docs/RELAY_INVITE.md) starts from.
        spoolOptIn: Boolean = true,
        // A commons store (§7.4) behind the manager, so the node can join and post to a spool's room. Off by
        // default: every other scenario runs the manager exactly as it did before the commons existed.
        commons: Boolean = false,
        // The BLE side channel's air (ADR 2026-09.sjaa): the node's radio grows the fast plane the shipped
        // Bluetooth plane has — the link copy plus a page every member in range hears. Null keeps the radio bare.
        pages: LabPages? = null,
        // A second phone on ANOTHER node's identity — one backup restored twice (ADR 2026-09.ypcc). Same key,
        // same node id, its own database and settings; the two can never link (`LabTransport.connect` refuses
        // its own node id, as the radios do), they only meet each other's frames through a third node.
        sameIdentityAs: LabNode? = null,
        // A free issuer for the node's relay (docs/VOICE_CALLS.md §2.6): the node holds ticket keys, the person has
        // allowed free tickets, and the refresher makes a free account when an upload waits. Null wires no tickets.
        freeIssuer: LabFreeIssuer? = null,
        // The house relays and this node's [home] among them (ADR 2026-10.8kfx): the node holds the seeded public-1,
        // the real home store and the issuer, in place of [spool]. Null keeps every scope at every relay, as before.
        publicRelays: LabPublicRelays? = null,
        home: String? = null,
    ): LabNode {
        val started = System.nanoTime()
        val node =
            LabNode(
                name,
                context,
                File(dir, name).apply { mkdirs() },
                limits,
                air,
                spool,
                spoolOptIn,
                commons,
                clock,
                pages,
                seedIdentity = sameIdentityAs?.identityBytes(),
                chaos = chaos,
                freeIssuer = freeIssuer,
                publicRelays = publicRelays,
                home = home,
            )
        nodes += node
        node.boot()
        // The keygen, the Room build and the start, for the sweep's per-run timing ([LabChaos.noteBoot]).
        chaos?.noteBoot(System.nanoTime() - started, fresh = true)
        return node
    }

    /**
     * Links two started nodes both ways, as a data path coming up would; each side then pushes its profile. [arm]
     * runs once the pipes exist and before either end lists the link — in `neighbors.value` or to a collector — the
     * moment to hold or stream what the link-up itself will send (a re-ask on a newcomer is answered before a
     * scenario could arm after the link).
     */
    fun link(
        a: LabNode,
        b: LabNode,
        arm: () -> Unit = {},
    ) {
        a.transport.connect(b.transport, arm = arm)
    }

    /**
     * Takes a link down (out of range). Frames parked on it are lost, as a torn-down link loses them. Returns
     * only once both sides' neighbor collectors have been handed the departure ([LabTransport.awaitNeighborsObserved]):
     * `neighbors` is a conflating `StateFlow`, and a link that comes back inside a collector's wake-up is a
     * link that never went down to it — no newcomer, so no profile push, no digest exchange, no re-send of an
     * owed group seed. No radio flaps that fast; the lab can, and on one slow core a collector's wake-up is
     * long. A node with a board also waits for its LoRa child to hold the new link set
     * ([LabNode.awaitBoardSawLinks]) and for the composite's own merged `neighbors`: each is handed over from a
     * coroutine of its own, and a send that outruns it meets a stale election or a peer still counted as
     * linked. Both ends then finish every frame they were handed before the cut ([LabTransport.awaitInboundDrained]),
     * as [LabNode.restart]'s peers do: a receipt sealed after the cut goes to no one, and reaches the peer only
     * through the re-link's digest exchange, which must not run before the receipt and its custody row exist (#137).
     * Last, each manager must have acted on the departure ([LabNode.awaitLinksActedOn]) — on a board node it
     * collects the composite's `StateFlow`, a second conflation the radio's hand-off record cannot see.
     * The [SETTLE_MS] pause stays for the composite's other `StateFlow`s (`reachable`, the status), and for a
     * board node's `merge`, which makes the drained signal early.
     */
    suspend fun unlink(
        a: LabNode,
        b: LabNode,
    ) {
        a.transport.disconnect(b.transport)
        a.transport.awaitNeighborsObserved()
        b.transport.awaitNeighborsObserved()
        withContext(Dispatchers.Default) {
            a.transport.awaitInboundDrained()
            b.transport.awaitInboundDrained()
        }
        a.awaitBoardSawLinks()
        b.awaitBoardSawLinks()
        a.awaitLinksActedOn()
        b.awaitLinksActedOn()
        settle()
    }

    /** See [unlink]. */
    internal suspend fun settle() = withContext(Dispatchers.Default) { delay(SETTLE_MS) }

    /**
     * Brings a whole topology up at once: every link exists before any node's `neighbors` moves. With links
     * made one at a time, a relay fired in the gap between two of them (the router's 0–150 ms jitter) can
     * miss a node that is not linked yet, and the frame then waits for the 60 s custody re-offer — real-world
     * latency, but a stall in a scenario that only wants the topology to exist.
     */
    fun linkAll(vararg links: Pair<LabNode, LabNode>) {
        links.forEach { (a, b) -> a.transport.connect(b.transport, publish = false) }
        links.flatMap { it.toList() }.distinct().forEach { it.transport.publishNeighbors() }
    }

    /** Stops every node and drops its state. */
    fun close() {
        // Every node goes down even when one fails its shutdown: a node left running leaks its collectors into the
        // next test class in this JVM. The first failure is rethrown, the rest ride along as suppressed. A gate a failed
        // scenario left holding is released first: the delivery parked at it cannot be cancelled (ADR 2026-10.f82h),
        // and the scope join would wait out its bound on it and bury the scenario's own failure under that one.
        nodes.forEach { it.custodyGate.release() }
        val failures = nodes.mapNotNull { runCatching { it.shutdown() }.exceptionOrNull() }
        dir.deleteRecursively()
        failures.firstOrNull()?.let { first -> throw first.apply { failures.drop(1).forEach(::addSuppressed) } }
    }

    /**
     * Takes a node out of the mesh for good — the phone signed out, was wiped or lost. Its links drop as a
     * shutdown's do; [close] no longer sees it. A lab identity cannot change in place ([LabNode.nodeId] is
     * fixed at construction), so this is what "Sign out here" (ADR 2026-09.ypcc) is to a scenario.
     */
    fun retire(node: LabNode) {
        node.shutdown()
        nodes -= node
    }

    /**
     * Polls [have] until it reaches [count] or [timeoutMs] elapses, on a real dispatcher (the session scopes
     * run on `Dispatchers.Default`, so virtual time can't see them). Returns whether it got there; the caller
     * asserts, with a message that says what the node actually holds. For a wait whose failure needs no
     * bespoke wording — the DM that must land before its reply, the post a node must hear before the next
     * step — use [await], which fails the scenario itself.
     */
    suspend fun tryAwait(
        count: Int,
        timeoutMs: Long = AWAIT_MS,
        have: suspend () -> Int,
    ): Boolean =
        withContext(Dispatchers.Default) {
            withTimeoutOrNull(timeoutMs) {
                while (have() < count) delay(POLL_MS)
                true
            } == true
        }

    /**
     * [tryAwait] that fails the scenario where the wait ran out, with every node's counters and sends. A
     * bare wait whose result was dropped used to let the scenario carry on — a reply sent before the first
     * DM had landed became the both-initiate race the fixture was written to avoid, and the failure that
     * surfaced was the oracle's, a step or two later and with the wrong name on it.
     */
    suspend fun await(
        count: Int,
        timeoutMs: Long = AWAIT_MS,
        have: suspend () -> Int,
    ) {
        if (!tryAwait(count, timeoutMs, have)) {
            throw AssertionError("never reached $count within $timeoutMs ms (have ${have()})\n${report(nodes)}")
        }
    }

    /**
     * What a failed wait reads next: every node's router and pipeline counters, every frame each transport
     * handed a peer, and the warnings the stacks logged — the inbound path never throws out of `onDeliver`
     * (`InboundPipeline`'s no-throw contract), it logs `Log.w` and drops, so a frame that vanished into a
     * swallowed exception is visible nowhere else. Robolectric records every `Log` call per test.
     */
    fun report(nodes: List<LabNode>): String =
        (chaos?.let { "$it\n" } ?: "") +
            nodes.joinToString("\n") { it.metricsLine() } + "\n" +
            nodes.joinToString("\n") { n -> "  ${n.name} sent: ${n.transport.sent}" } + "\n" +
            "  warnings:\n" +
            ShadowLog
                .getLogs()
                .filter { it.type >= Log.WARN }
                .takeLast(WARNINGS_KEPT)
                .joinToString("\n") { "    ${it.tag}: ${it.msg}${it.throwable?.let { t -> " ($t)" } ?: ""}" }

    /**
     * The universal oracle, in four parts, each awaited (the thing under test is exactly whether it happens)
     * and then asserted with a per-node listing so a failure reads as a diff:
     *
     * 1. **Messages.** Every node in [nodes] holds the same set of ordinary (decrypted) messages in the thread
     *    [conversation] names on it (a group id is the same everywhere; a DM thread is named after the *other*
     *    party, so it differs per node) — at least [atLeast] of them, none stranded as `pendingKey`.
     * 2. **Ticks.** Every message a node authored has been acked by every other node in [nodes] — the sealed
     *    receipt (ADR 018) is a second cross-node protocol under every message, and "the sender never saw a
     *    tick" was cf94a06's user-visible half.
     * 3. **Reactions, attachments, the group row.** Under every converged message the same (reactor, emoji)
     *    set; behind every message with an attachment the same bytes, held and unflagged, on every node;
     *    for a group thread the same roster, departed set, name and photo everywhere.
     * 4. **Custody.** The store-and-forward stores of [nodes] and [carriers] hold the same live id set
     *    (`liveFingerprint` parity, the same oracle the device soaks use). Two stores that quietly disagree
     *    while delivery looks fine are the "NAN churns forever" class (the self-frame wipe divergence).
     * 5. **Profiles.** Every node presents every other exactly as that node presents itself (name, status,
     *    flag, avatar, profile version) — the cleartext `profile` and the sealed `CTL_PROFILE` writers land
     *    on one row and must agree.
     * 6. **Sessions.** Every pair that holds a DM session on both sides holds *one* session: both confirmed,
     *    the same root, the same era, opposite roles — two devices confirmed on different roots is the
     *    "neither can read the other" class (ADR 023/024/027).
     * 7. No node is sitting on a parked group seed or a parked frame that never replayed, no node pinned its
     *    own key, and no custody holds a frame its sender addressed to themselves (the self-pin loop).
     *
     * [carriers] are nodes that relayed and custodied but are not party to the thread — they take part in the
     * custody and per-store checks only. [custodyAcross] narrows the custody leg alone, for a partition the
     * design does not close: a relay carries only your own scopes, so a node on the far island never holds
     * the DM-form frames two near-island nodes mint to each other (a seed or root re-send on the 60 s
     * re-offer) until they meet by radio again.
     */
    suspend fun assertConverged(
        nodes: List<LabNode>,
        atLeast: Int,
        carriers: List<LabNode> = emptyList(),
        timeoutMs: Long = AWAIT_MS,
        custodyAcross: List<LabNode> = nodes + carriers,
        conversation: (LabNode) -> String,
    ) {
        val names = nodes.map { it.name }
        val landed =
            tryAwait(1, timeoutMs) {
                val sets = nodes.map { it.decrypted(conversation(it)) }
                if (sets.all { it.size >= atLeast } && sets.distinct().size == 1) 1 else 0
            }
        assertTrue(
            "messages did not converge across $names within ${timeoutMs}ms:\n${listing(nodes, conversation)}\n${report(nodes)}",
            landed,
        )
        nodes.forEach { n ->
            val pending =
                n.messages
                    .observeNewestMessages(conversation(n), WINDOW)
                    .first()
                    .filter { it.pendingKey }
            assertTrue("${n.name} has messages stranded on pendingKey: ${pending.map { it.id }}", pending.isEmpty())
        }

        // The converged id set, read once: every node holds the same ids, so the first node's listing names them.
        val ids =
            nodes
                .first()
                .decrypted(conversation(nodes.first()))
                .map { it.first }
                .sorted()
        assertReactionsAgree(nodes, ids, timeoutMs)
        assertAttachmentsAgree(nodes, ids, timeoutMs, conversation)
        assertGroupAgrees(nodes, conversation(nodes.first()), timeoutMs)

        val ticked = tryAwait(1, timeoutMs) { if (nodes.all { n -> n.missingAcks(conversation(n), nodes).isEmpty() }) 1 else 0 }
        val owed = nodes.map { n -> "  ${n.name}: ${n.missingAcks(conversation(n), nodes)}" }.joinToString("\n")
        assertTrue(
            "delivery ticks did not converge across $names within ${timeoutMs}ms (message → who never acked):\n$owed\n${report(nodes)}",
            ticked,
        )

        val stores = nodes + carriers
        val custodied = tryAwait(1, timeoutMs) { if (custodyAcross.map { it.custodyFingerprint() }.distinct().size == 1) 1 else 0 }
        val rows = custodyAcross.map { n -> "  ${n.name}: ${n.custodyIds().sorted()}" }.joinToString("\n")
        assertTrue(
            "custody did not converge across ${custodyAcross.map { it.name }} within ${timeoutMs}ms:\n$rows\n${report(stores)}",
            custodied,
        )

        assertProfilesAgree(nodes, timeoutMs)
        assertSessionsAgree(nodes, timeoutMs)

        stores.forEach { n ->
            val snap = n.metrics.snapshot()
            assertEquals("${n.name} parked a group seed that never replayed", snap.groups.groupSeedsHeld, snap.groups.groupSeedsReplayed)
            assertEquals("${n.name} parked a frame for a missing key that never replayed", snap.keys.framesHeld, snap.keys.framesReplayed)
            // A node never pins its own key: its own profile loops back through every peer's custody, and a
            // self row turns every seal-to-a-pinned-peer path on ourselves (the hourly self-addressed frames
            // found in the lab fleet's custody, 2026-09-13). The custody rows are the same bug seen from a carrier.
            assertTrue("${n.name} pinned a peer row for itself", n.peers.find(n.nodeId) == null)
            assertTrue("${n.name} holds a ratchet session with itself", n.db.ratchetDao().session(n.nodeId) == null)
            assertTrue(
                "${n.name} custodies frames a sender addressed to themselves: ${n.selfAddressedCustody()}",
                n.selfAddressedCustody().isEmpty(),
            )
        }
    }

    private suspend fun assertReactionsAgree(
        nodes: List<LabNode>,
        ids: List<String>,
        timeoutMs: Long,
    ) {
        val reacted = tryAwait(1, timeoutMs) { if (ids.all { id -> nodes.map { it.reactions(id) }.distinct().size == 1 }) 1 else 0 }
        assertTrue(
            "reactions did not converge across ${nodes.map { it.name }} within ${timeoutMs}ms (message → per-node sets):\n" +
                ids.map { id -> "  $id: ${nodes.map { "${it.name}=${it.reactions(id)}" }}" }.joinToString("\n"),
            reacted,
        )
    }

    private suspend fun assertAttachmentsAgree(
        nodes: List<LabNode>,
        ids: List<String>,
        timeoutMs: Long,
        conversation: (LabNode) -> String,
    ) {
        val held = tryAwait(1, timeoutMs) { if (nodes.all { n -> ids.all { n.attachmentHeld(conversation(n), it) } }) 1 else 0 }
        if (!held) {
            // Who asked whom for the missing bytes and who served them: the two halves a lost pull can fail in.
            val missing = nodes.associateWith { n -> ids.filterNot { n.attachmentHeld(conversation(n), it) } }
            val hashes = missing.flatMap { (n, gone) -> gone.mapNotNull { n.attachmentHash(conversation(n), it) } }.toSet()
            throw AssertionError(
                "attachment bytes did not land on every node within ${timeoutMs}ms:\n" +
                    missing.entries.joinToString("\n") { (n, gone) -> "  ${n.name}: missing $gone" } + "\n" +
                    nodes.joinToString("\n") { n ->
                        "  ${n.name} asked ${n.transport.blobAsks.filter { it.hash in hashes }}, " +
                            "served ${n.transport.files.filter { f -> hashes.any { f.contains(it) } }}"
                    } + "\n" + report(nodes),
            )
        }
        // A lab that allows every picture never flags one; a node told to mind them all flags by design (#109).
        nodes.filterNot { it.picturesExplicit }.forEach { n ->
            ids.forEach { id -> assertTrue("${n.name} flagged the attachment on $id", !n.attachmentFlagged(conversation(n), id)) }
        }
        ids.forEach { id ->
            val plains = nodes.map { it.attachmentPlain(conversation(it), id)?.contentHashCode() }
            assertEquals("the attachment on $id reads differently across ${nodes.map { it.name }}: $plains", 1, plains.distinct().size)
        }
    }

    /** For a group thread only: every node's roster, departed set, name and photo agree (see [LabNode.groupShape]). */
    private suspend fun assertGroupAgrees(
        nodes: List<LabNode>,
        thread: String,
        timeoutMs: Long,
    ) {
        if (!thread.startsWith(Conversations.GROUP_ID_PREFIX)) return
        val agreed = tryAwait(1, timeoutMs) { if (nodes.map { it.groupShape(thread) }.distinct().size == 1) 1 else 0 }
        assertTrue(
            "the group's roster, name or photo did not converge across ${nodes.map { it.name }} within ${timeoutMs}ms:\n" +
                nodes.map { "  ${it.name}: ${it.groupShape(thread)}" }.joinToString("\n"),
            agreed,
        )
    }

    private suspend fun assertProfilesAgree(
        nodes: List<LabNode>,
        timeoutMs: Long,
    ) {
        val pairs = nodes.flatMap { a -> nodes.filter { it !== a }.map { b -> a to b } }
        val presented = tryAwait(1, timeoutMs) { if (pairs.all { (a, b) -> presentsAsItIs(a, b) }) 1 else 0 }
        assertTrue(
            "profiles did not converge across ${nodes.map { it.name }} within ${timeoutMs}ms:\n" +
                pairs
                    .map { (a, b) -> "  ${a.name} holds ${b.name} as ${a.presentationOf(b)}; ${b.name} is ${b.ownPresentation()}" }
                    .joinToString("\n") + "\n${report(nodes)}",
            presented,
        )
    }

    /**
     * Whether [a] presents [b] as [b] presents itself. The one difference allowed is an avatar [a]'s screening
     * refused with content filtering on: [a] must not show it, and keeps whatever it showed before (#109).
     */
    private suspend fun presentsAsItIs(
        a: LabNode,
        b: LabNode,
    ): Boolean {
        val held = a.presentationOf(b) ?: return false
        val own = b.ownPresentation()
        val avatar = own.avatarHash
        val refused = avatar != null && a.settings.contentFilteringEnabled.first() && a.imageFlagged(avatar)
        return if (refused) held.avatarHash != avatar && held.copy(avatarHash = null) == own.copy(avatarHash = null) else held == own
    }

    private suspend fun assertSessionsAgree(
        nodes: List<LabNode>,
        timeoutMs: Long,
    ) {
        val unordered = nodes.indices.flatMap { i -> (i + 1 until nodes.size).map { j -> nodes[i] to nodes[j] } }
        val oneSession = tryAwait(1, timeoutMs) { if (unordered.all { (a, b) -> sessionsAgree(a.session(b), b.session(a)) }) 1 else 0 }
        assertTrue(
            "DM sessions disagree across ${nodes.map { it.name }} within ${timeoutMs}ms:\n" +
                unordered
                    .map { (a, b) -> "  ${a.name}↔${b.name}: ${a.session(b).describe()} / ${b.session(a).describe()}" }
                    .joinToString("\n"),
            oneSession,
        )
    }

    /**
     * Two sides of one DM session agree when both are confirmed on the same root and era with opposite roles.
     * A pair with no session on either side (a room-only acquaintance) or on one side only (an init still in
     * flight, or a peer that only ever received) has nothing to disagree about.
     */
    private fun sessionsAgree(
        a: RatchetPeerState?,
        b: RatchetPeerState?,
    ): Boolean {
        if (a?.hasSession != true || b?.hasSession != true) return true
        return a.confirmed && b.confirmed && a.rootHash == b.rootHash && a.establishedAt == b.establishedAt &&
            a.weAreInitiator != b.weAreInitiator
    }

    private fun RatchetPeerState?.describe(): String =
        when {
            this == null -> "no row"
            !hasSession -> "no session"
            else -> "root=$rootHash era=$establishedAt confirmed=$confirmed initiator=$weAreInitiator epoch=$sendEpoch"
        }

    private suspend fun listing(
        nodes: List<LabNode>,
        conversation: (LabNode) -> String,
    ): String = nodes.map { n -> "  ${n.name}: ${n.decrypted(conversation(n)).map { it.second }}" }.joinToString("\n")

    /**
     * Waits until [node]'s relay lists the DM scope it shares with [peer] as converged — derived the moment
     * the session confirms (`RatchetSessions.rootChanges`, ADR 2026-09.dcah), healed on the SUB's digest.
     */
    suspend fun awaitDmScope(
        node: LabNode,
        peer: LabNode,
        // A scope the wait must not be satisfied by — the one the pair was on before a reset moved it (ADR
        // 2026-10.j67x): the table re-derives on its own coroutine, so the old scope is still listed, converged,
        // for a moment after the root moved.
        notScope: String? = null,
    ) = awaitScope(node, peer.nodeId, peer, notScope = notScope)

    /** Waits until [node]'s relay lists the scope of [groupId] as converged; [others] are listed on failure. */
    suspend fun awaitGroupScope(
        node: LabNode,
        groupId: String,
        vararg others: LabNode,
    ) = awaitScope(node, groupId, *others)

    private suspend fun awaitScope(
        node: LabNode,
        label: String,
        vararg peers: LabNode,
        notScope: String? = null,
    ) {
        // The live scope only: a retiring one is drained and never converges on what the pair says now.
        val ok =
            tryAwait(1, timeoutMs = SPOOL_AWAIT_MS) {
                val live =
                    node.manager
                        .spoolStatus()
                        .flatMap { it.scopes }
                        .firstOrNull { it.label == label && !it.pair && !it.retiring }
                if (live != null && live.scopeHex != notScope && live.converged) 1 else 0
            }
        if (ok) return
        val peer = peers.firstOrNull() ?: node
        // A scope that will not converge is nearly always one frame id held as two byte-variants (the
        // profile case ADR 2026-09.y5f3 fixed); the per-row listing shows which, the id sets whether it is
        // that or a frame one side simply never got.
        val mine = node.custodyFrames()
        val theirs = peer.custodyFrames()
        throw AssertionError(
            "${node.name}'s relay never converged the scope $label: ${node.manager.spoolStatus().map { spool ->
                "${spool.url} connected=${spool.connected} err=${spool.lastError} scopes=${spool.scopes.map {
                    "${it.label.take(
                        8,
                    )} local=${it.localCount} spool=${it.spoolCount} converged=${it.converged} invalid=${it.invalidCount} accounted=${it.accountedCount}"
                }}"
            }}\n  ${node.name}-only rows: ${mine - theirs.toSet()}\n  ${peer.name}-only rows: ${theirs - mine.toSet()}",
        )
    }

    /**
     * Two nodes that know each other, hold DM sessions both ways (the DM scope derives from the confirmed
     * ratchet root) and have each heard the other **through the relay**: a link, a DM each way, the link
     * gone, both scopes converged, then — with [air] made lossy so the relay is the only path — one more DM
     * each way until presence is stamped (it is stamped only on a frame the spool *pulls*, and everything
     * from the link phase is already in both custodies). Returns with every receipt landed, so a scenario
     * reads its baselines from a settled pair. Without an [air] the presence half is skipped: no board, no
     * cover to prove. With one, a DM that fans out before its peer's presence reaches the sender's board goes
     * as that board's probe into the dark air (ADR 2026-10.a4pm) and spends the window, so a scenario that
     * later needs a DM on the air has the peer's board heard first rather than count on the probe.
     */
    internal suspend fun meetOnTheRelay(
        alice: LabNode,
        bob: LabNode,
        air: FakeMeshtasticAir? = null,
    ) {
        link(alice, bob)
        awaitAcquainted(alice, bob)
        // A reply, not a both-initiate race: bob answers the session alice opened, so both sides confirm it
        // at once and each derives the scope on its own confirmation.
        assertTrue(alice.sendDm(bob, "hello"))
        await(1) { bob.decrypted(bob.dmWith(alice)).size }
        assertTrue(bob.sendDm(alice, "hi"))
        assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        unlink(alice, bob)
        awaitDmScope(alice, bob)
        awaitDmScope(bob, alice)
        if (air == null) return
        air.lossy = { _, _ -> true }
        assertTrue(alice.sendDm(bob, "are you on the relay?"))
        assertTrue(bob.sendDm(alice, "i am"))
        awaitSpoolPresent(alice, bob)
        awaitSpoolPresent(bob, alice)
        air.lossy = { _, _ -> false }
        // Settled before the scenario reads any baseline: the receipts for those two DMs are frames too.
        assertConverged(listOf(alice, bob), atLeast = 4) { it.dmWith(if (it === alice) bob else alice) }
    }

    /**
     * Three nodes on one relay — alice, bob and carol — linked, acquainted and holding DM sessions both ways, so
     * every DM scope derives. Still linked on return: a scenario parts whichever radios it means to.
     */
    internal suspend fun threeOnOneRelay(
        spool: FakeSpool,
        limits: LabLimits = LabLimits(),
    ): Triple<LabNode, LabNode, LabNode> {
        val alice = node("alice", limits, spool = spool).apply { setDisplayName("Alice") }
        val bob = node("bob", limits, spool = spool).apply { setDisplayName("Bob") }
        val carol = node("carol", limits, spool = spool).apply { setDisplayName("Carol") }
        linkAll(alice to bob, bob to carol, alice to carol)
        awaitAcquainted(alice, bob, carol)
        listOf(alice to bob, alice to carol, bob to carol).forEach { (a, b) ->
            assertTrue(a.sendDm(b, "hello"))
            await(1) { b.decrypted(b.dmWith(a)).size }
            assertTrue(b.sendDm(a, "hi"))
            assertConverged(listOf(a, b), atLeast = 2) { it.dmWith(if (it === a) b else a) }
        }
        return Triple(alice, bob, carol)
    }

    /**
     * Takes [node] off its relay and waits until no worker is left — a phone with no route to the Internet, its
     * radios untouched, and a restart meanwhile keeps it off. [rejoinRelay] brings it back. The settings write is the
     * relay editor's own switch.
     */
    suspend fun leaveRelay(node: LabNode) {
        node.relayLeft = true
        node.settings.setSpoolEnabled(false)
        node.manager.refreshRelays()
        val gone = tryAwait(1) { if (node.manager.spoolStatus().isEmpty()) 1 else 0 }
        assertTrue("${node.name}'s relay workers never stopped: ${node.manager.spoolStatus().map { it.url }}", gone)
    }

    /** Puts [node] back on its relay and waits for the hello. */
    suspend fun rejoinRelay(node: LabNode) {
        node.relayLeft = false
        node.settings.setSpoolEnabled(true)
        node.manager.refreshRelays()
        val back = tryAwait(1, timeoutMs = SPOOL_AWAIT_MS) { if (node.manager.spoolStatus().any { it.connected }) 1 else 0 }
        assertTrue("${node.name} never reconnected to its relay", back)
    }

    /** Waits until [node]'s spool has heard from [peer] recently enough for the mesh to route on it. */
    suspend fun awaitSpoolPresent(
        node: LabNode,
        peer: LabNode,
    ) {
        // The scope is derived the moment the session confirms, healed on its SUB's digest, then the
        // profiles cross.
        val ok = tryAwait(1, timeoutMs = SPOOL_AWAIT_MS) { if (node.spoolPresent(peer)) 1 else 0 }
        assertTrue("${node.name} never saw ${peer.name} on the spool", ok)
    }

    /** Waits until [author]'s row for [messageId] carries [acker]'s receipt, and returns the plane it came over. */
    suspend fun awaitReceipt(
        author: LabNode,
        messageId: String,
        acker: LabNode,
    ): DeliveryPlane {
        val ok = tryAwait(1) { if (author.receiptPlanes(messageId).containsKey(acker.nodeId)) 1 else 0 }
        assertTrue(
            "${author.name} never got ${acker.name}'s tick for $messageId\n${report(nodes)}\n" +
                nodes.filter { it.loraLog.isNotEmpty() }.joinToString("\n") { "  ${it.name} lora: ${it.loraLog.takeLast(WINDOW_LORA)}" },
            ok,
        )
        return author.receiptPlanes(messageId).getValue(acker.nodeId)
    }

    /**
     * Waits until the custody stores of [nodes] agree — what a link's digest exchange settles a moment after
     * the profiles cross. [awaitAcquainted] folds this in, so a scenario that takes a link down the moment
     * the nodes have met cannot strand the later profile stamps on one side (no plane fans an old stamp
     * again); this stays for the fixtures that settle the stores at other points.
     */
    suspend fun awaitCustodyParity(vararg nodes: LabNode) {
        val ok = tryAwait(1) { if (nodes.map { it.custodyFingerprint() }.distinct().size == 1) 1 else 0 }
        assertTrue(
            "custody never settled among ${nodes.map { it.name }}:\n" +
                nodes.map { "  ${it.name}: ${it.custodyIds().sorted()}" }.joinToString("\n") + "\n${report(nodes.toList())}",
            ok,
        )
    }

    /**
     * Waits until every pair in [nodes] has pinned the other's key and the custody stores of [nodes] agree —
     * the two halves of the handshake a link-up starts (the profile push, then the digest exchange). Met means
     * both: a scenario may cut a link the moment this returns, and a node with a board and no link then has
     * nothing to fan an old profile stamp with (`RoomTickPlanesLabTest` found the stores still settling on
     * one slow core). A node with a board also waits for its LoRa child to hold the radio's links
     * ([LabNode.awaitBoardSawLinks]), as [unlink] does for a departure: the composite hands the link set over
     * from a coroutine of its own, and under a stall the handshake can finish first — a DM sent next then met
     * a board that still counted its peer as unlinked and aired it (`LoraPocketLabTest`'s linked-DM gate under
     * chaos seed 279241075, 2026-09-28).
     */
    suspend fun awaitAcquainted(vararg nodes: LabNode) {
        val pairs = nodes.flatMap { a -> nodes.filter { it !== a }.map { b -> a to b } }
        val ok = tryAwait(1) { if (pairs.all { (a, b) -> a.knows(b) }) 1 else 0 }
        val missing = pairs.filterNot { (a, b) -> a.knows(b) }.map { (a, b) -> "${a.name}→${b.name}" }
        assertTrue("profiles never exchanged among ${nodes.map { it.name }}; missing $missing\n${report(nodes.toList())}", ok)
        awaitCustodyParity(*nodes)
        nodes.forEach { it.awaitBoardSawLinks() }
    }

    companion object {
        /**
         * How long a scenario waits for anything it expects. A convergence takes a few hundred ms on a
         * workstation and a few seconds on one core, so this is headroom for the CI runner — one shared
         * vCPU with 2 GB and swap, where a GC or a page-in can stall the whole JVM for seconds — not a
         * budget any scenario is meant to spend. A real failure costs one of these per unmet await.
         */
        const val AWAIT_MS = 30_000L
        const val POLL_MS = 25L
        const val WINDOW = 500

        /**
         * A group tick toward an absent author batches this long before it escalates into custody. The field
         * value is 45 s ([app.getknit.knit.mesh.AckSync.TICK_BATCH_DEBOUNCE_MS]); the lab shortens it so a tick
         * crossing a relay converges inside [AWAIT_MS] without changing which path it takes.
         */
        const val TICK_DEBOUNCE_MS = 300L

        /**
         * The ride hold, shortened the same way ([app.getknit.knit.mesh.AckSync.RIDE_HOLD_MS] is 60 s): a
         * scenario that wants the ride to win passes a longer one through [LabLimits.rideHoldMs].
         */
        const val RIDE_HOLD_MS = 300L

        /**
         * The field's re-offer interval (`MeshManager.NEIGHBOR_REOFFER_INTERVAL_MS`, private to its companion): a node
         * boots with it unless a scenario about the loop shortens it ([LabLimits.reofferIntervalMs]).
         */
        const val REOFFER_INTERVAL_MS = 60_000L

        /**
         * A blob round's first ask, lengthened ([app.getknit.knit.mesh.BlobExchange.FIRST_ASK_GRACE_MS] is 5 s): its
         * timer runs on wall time, and a scenario that counts copies must not race a slow runner's stall. A scenario
         * widens a round by advancing the clock this far and running a tick. Below
         * [app.getknit.knit.mesh.BlobExchange.ROUND_MS], so that jump never also opens the next round.
         */
        const val FIRST_ASK_GRACE_MS = 20_000L

        /**
         * The lab's proof round ([app.getknit.knit.mesh.spool.AttachmentDeferPolicy.PROOF_WAIT_MS], ADR 2026-10.vy27).
         * The hold runs on wall time, and a scenario that asserts nothing went up must not race a slow runner's stall;
         * the happy path settles on the next re-look once the answer is in, so a longer bound costs it nothing. A
         * scenario that means to push past the round advances the clock this far.
         */
        const val PROOF_WAIT_MS = 30_000L

        /**
         * How long a held pipe's feed reads as live before it counts as stalled (`FeedMeter.STALL_MS` is 15 s on a
         * phone; ADR 2026-10.nsr7), shortened: a holder parks every other asker behind a live feed for as long as it
         * lasts, and a scenario that holds one peer's files would otherwise stall the rest. A scenario about parking
         * boots its holder with a long one.
         */
        const val FEED_STALL_MS = 2_000L

        /**
         * How long a fetch waits for its holder queries' answers, lengthened the same way
         * ([app.getknit.knit.mesh.BlobExchange.QUERY_WAIT_MS] is 5 s; ADR 2026-10.6e5t): a scenario that counts asks
         * must not race a slow runner's answer. With [FIRST_ASK_GRACE_MS] it stays under
         * [app.getknit.knit.mesh.BlobExchange.CYCLE_MS], so a wait and a grace never also open the next cycle.
         */
        const val QUERY_WAIT_MS = 15_000L

        /**
         * When a fetch queries again a neighbour that said it is fetching the blob, or that it asked to pull it through,
         * lengthened the same way ([app.getknit.knit.mesh.BlobExchange.FETCHING_RECHECK_MS] is 10 s): only a scenario's
         * clock jump and tick move it, never a slow run's wall clock. Below [app.getknit.knit.mesh.BlobExchange.CYCLE_MS]; on a run slow
         * enough that the jump also opens the next cycle, that cycle queries the same neighbour again, to the same end.
         */
        const val FETCHING_RECHECK_MS = 30_000L

        /**
         * Long enough for the scope reconcile (15 s) and a worker that missed an event and waits for its own
         * 60 s tick — the slow path a real relay client takes too; the fast path is a second or two.
         */
        const val SPOOL_AWAIT_MS = 90_000L

        /** What a node with a [FakeSpool] dials; the fake ignores the address. */
        const val SPOOL_URL = "wss://lab.spool/spool/v1"

        /**
         * The Internet plane's timed round and attachment re-look under [LabLimits.calendarRounds] — fields
         * 60 s and 15 s. Long enough that a round is never back to back with the event-driven one before it.
         */
        const val CALENDAR_ROUND_MS = 1_000L

        /** How long a departure is left visible before the next topology change ([unlink], [LabNode.restart]). */
        const val SETTLE_MS = 100L

        /** The newest warnings a failure report quotes ([report]); the stacks log a lot at boot. */
        const val WARNINGS_KEPT = 40

        /** How many of a board's last log lines a failed wait prints. */
        const val WINDOW_LORA = 40
    }
}

/**
 * The storage and ingress policy numbers a node boots with. Production takes the field defaults; a flood
 * scenario shrinks them so a handful of posts is a flood — same rules, same paths, smaller numbers.
 */
data class LabLimits(
    /** Newest posts a room keeps ([MessageRepository]'s `nearbyMaxMessages`). */
    val roomMaxMessages: Int = 2_000,
    /** Newest posts a room keeps per stranger ([MessageRepository]'s `roomMaxPerStranger`). */
    val roomMaxPerStranger: Int = 200,
    /** Newest rows a request thread keeps ([MessageRepository]'s `maxPerPendingThread`). */
    val maxPerPendingThread: Int = 50,
    /** Room posts one link may hand over at once ([IngressBudget]'s `burst`). */
    val ingressBurst: Int = IngressBudget.DEFAULT_BURST,
    /** Room posts per minute one link may sustain ([IngressBudget]'s `perMinute`). */
    val ingressPerMinute: Int = IngressBudget.DEFAULT_PER_MINUTE,
    /**
     * How long a room tick waits for a ride before it goes on its own
     * ([app.getknit.knit.mesh.AckSync.RIDE_HOLD_MS], ADR 2026-09.y5f3).
     */
    val rideHoldMs: Long = MeshLab.RIDE_HOLD_MS,
    /** How long a blob round's first ask has before every other neighbour is asked ([MeshLab.FIRST_ASK_GRACE_MS]). */
    val firstAskGraceMs: Long = MeshLab.FIRST_ASK_GRACE_MS,
    /**
     * How long a send of ours with nothing else to defer on holds for its proof round ([MeshLab.PROOF_WAIT_MS]; ADR
     * 2026-10.vy27): on the wall clock, so longer than production's, which a stalled node could outlast.
     */
    val proofWaitMs: Long = MeshLab.PROOF_WAIT_MS,
    /**
     * How often the manager's re-offer round runs ([MeshLab.REOFFER_INTERVAL_MS], the field value by default, so no
     * scenario's loop moves). A scenario about the loop itself shortens it; every other one pokes a round
     * with [LabNode.reoffer].
     */
    val reofferIntervalMs: Long = MeshLab.REOFFER_INTERVAL_MS,
    /**
     * How long a held pipe's feed reads as live before it stalls ([MeshLab.FEED_STALL_MS]; ADR 2026-10.nsr7): a parked
     * ask waits that long behind it. Short by default: a held pipe is a feed that never ends, and only a scenario about
     * parking means to wait on one.
     */
    val feedStallMs: Long = MeshLab.FEED_STALL_MS,
    /**
     * Whether the node queries its neighbours for a blob's holders before it asks (ADR 2026-10.6e5t;
     * `BuildConfig.BLOB_QUERY`, on in debug and off in release). Off by default, so every other scenario asks blind,
     * exactly as before the query existed.
     */
    val blobQuery: Boolean = false,
    /** How long its fetch waits for the answers ([MeshLab.QUERY_WAIT_MS]). */
    val queryWaitMs: Long = MeshLab.QUERY_WAIT_MS,
    /** When it queries a neighbour fetching the blob again ([MeshLab.FETCHING_RECHECK_MS]). */
    val fetchingRecheckMs: Long = MeshLab.FETCHING_RECHECK_MS,
    /**
     * Whether the node's link advertises that it answers a holder query (`CAP_BLOB_HAVE`) — every build does. False
     * plays a neighbour that predates it, the iOS port today: never queried, asked blind.
     */
    val answersHolderQueries: Boolean = true,
    /**
     * Whether the node's blob exchange carries the pull budget and deadline (ADR 2026-10.2drc). False plays the iOS port's
     * rules today, with [answersHolderQueries] false and [blobQuery] off beside it: its asks name no `pull` or `until`,
     * any ask refreshes its wants, and every echo of its pulls through without bound.
     */
    val pullBudgets: Boolean = true,
    /**
     * The custody store's bounds ([ForwardRepository]'s constructor). Give every node in a scenario the
     * same numbers: the content digest is folded over what these keep, and the bound must be identical
     * everywhere or the stores diverge by construction (`rules/mesh.md`).
     */
    val custodyTtlMs: Long = ForwardRepository.DEFAULT_TTL_MS,
    val custodyBroadcastTtlMs: Long = ForwardRepository.DEFAULT_BROADCAST_TTL_MS,
    val custodyMaxRows: Int = ForwardRepository.DEFAULT_MAX_ROWS,
    val custodyMaxPerSender: Int = ForwardRepository.DEFAULT_MAX_PER_SENDER,
    val custodyMaxPerGroup: Int = ForwardRepository.DEFAULT_MAX_PER_GROUP,
    val custodyMaxBroadcast: Int = ForwardRepository.DEFAULT_MAX_BROADCAST,
    /**
     * The LoRa bridge's Trickle interval ([LoraGossipPolicy]; 5–15 min in the field). A scenario that waits
     * for an OFFER or the backfill it drives shortens it; the field numbers would never fire inside an await.
     */
    val loraGossipMinMs: Long = LoraGossipPolicy.MIN_INTERVAL_MS,
    val loraGossipMaxMs: Long = LoraGossipPolicy.MAX_INTERVAL_MS,
    /**
     * How long the ✓✓ for a DM that arrived over the board batches ([DmAckCoalescer.HOLD_MS], ADR 054). The
     * field value by default: a scenario that waits for that tick shortens it, as [loraGossipMinMs].
     */
    val dmAckHoldMs: Long = DmAckCoalescer.HOLD_MS,
    /**
     * Whether the boards run the ADR 044 bridge (`LoraConfig.bridge`, the "Bridge pockets" switch, on in the
     * field). A scenario about the live fan-out and the first-hearing re-offer turns it off, because a far
     * board's OFFER can have the bridge serve the same frames first, out of a share that carries about three
     * one-packet frames a window at the lab's fallback preset — and a served frame takes the dedup slot the
     * re-offer would use.
     */
    val loraBridge: Boolean = true,
    /**
     * How often the board's linger sweep re-reads its heard set (`LoraMeshTransport.LINGER_SWEEP_MS`, a minute in the
     * field). A scenario that moves the calendar past `REACHABLE_LINGER_MS` shortens it, so the boards forget each
     * other inside an await — the jump moves the decision, never the `delay` that asks it.
     */
    val loraLingerSweepMs: Long = LoraMeshTransport.LINGER_SWEEP_MS,
    /**
     * The Internet plane's timed round and attachment re-look ([ScopeSync.TICK_INTERVAL_MS],
     * [ScopeSync.ATTACHMENT_RETRY_MS]). The field values by default, so every spool scenario still converges on
     * the events and a lost wake shows as a slow scenario (`scripts/lab-chaos.sh` names those). Only a scenario
     * whose step waits on a timer no poke reaches shortens them: a calendar transition — a [LabClock.advance] the
     * next timed round must notice, as [calendarRounds] does, since the jump cannot move a `delay` — or the spool's
     * ask-only attachment presence, where a first look before the upload lands waits out the re-look alone.
     */
    val spoolTickMs: Long = ScopeSync.TICK_INTERVAL_MS,
    val spoolAttachmentRetryMs: Long = ScopeSync.ATTACHMENT_RETRY_MS,
) {
    /** These limits with the Internet plane's timed waits cut to [MeshLab.CALENDAR_ROUND_MS] (see [spoolTickMs]). */
    fun calendarRounds(): LabLimits = copy(spoolTickMs = MeshLab.CALENDAR_ROUND_MS, spoolAttachmentRetryMs = MeshLab.CALENDAR_ROUND_MS)
}

/**
 * One phone. The persistent half (identity secret, database, settings file) outlives [boot]/[shutdown], so
 * [restart] is a real process death: every in-memory structure — `PendingInbound`, `PendingGroupKeys`, the
 * ratchet caches, the seen set — is rebuilt from what was committed.
 */
@Suppress("LargeClass") // one phone's whole DI wiring plus every verb a scenario drives it with; splitting would scatter the mirror
class LabNode internal constructor(
    val name: String,
    private val context: Context,
    private val dir: File,
    private val limits: LabLimits,
    private val air: FakeMeshtasticAir?,
    private val spool: FakeSpool?,
    private val spoolOptIn: Boolean,
    private val withCommons: Boolean,
    clock: LabClock,
    private val pages: LabPages? = null,
    // Another node's identity file, for a twin (`MeshLab.node(sameIdentityAs)`); null mints a fresh one.
    seedIdentity: ByteArray? = null,
    // The lab's scheduling noise ([LabChaos]): this node's dispatchers and pipes run under it. Null = none.
    private val chaos: LabChaos? = null,
    // A free issuer for this node's relay ([MeshLab.node]); null wires no tickets.
    private val freeIssuer: LabFreeIssuer? = null,
    // The house relays and this node's home among them ([MeshLab.node]); null leaves the node on [spool] alone.
    private val publicRelays: LabPublicRelays? = null,
    private val home: String? = null,
) {
    /** [delegate], or its chaos-lagged form when the lab runs under [chaos]. */
    private fun dispatcher(delegate: CoroutineDispatcher): CoroutineDispatcher = chaos?.dispatcher(delegate) ?: delegate

    /** This node's clock: the lab's shared calendar, plus its own skew if a scenario gave it one. */
    val now: () -> Long = clock.forNode(name)

    // --- persistent across restarts ---

    // The keystore-wrapped identity file, held as bytes: IdentityKeyStore only ever calls read()/store().
    private var secretBytes: ByteArray? = seedIdentity?.copyOf()
    private val secret =
        mockk<KeystoreSecret> {
            every { read() } answers { secretBytes?.let { Unwrapped.Present(it.copyOf()) } ?: Unwrapped.Absent }
            every { store(any(), any()) } answers { secretBytes = firstArg<ByteArray>().copyOf() }
        }
    private val keyStore = IdentityKeyStore(secret)
    val identity = Identity(keyStore) { "device-$name" }

    val db: KnitDatabase =
        Room
            .inMemoryDatabaseBuilder(context, KnitDatabase::class.java)
            .allowMainThreadQueries()
            .apply { if (chaos != null) setQueryCoroutineContext(dispatcher(Dispatchers.IO)) }
            .build()

    private val settingsScope = CoroutineScope(SupervisorJob() + dispatcher(Dispatchers.IO))
    private val dataStore = PreferenceDataStoreFactory.create(scope = settingsScope) { File(dir, "settings.preferences_pb") }
    val settings = SettingsStore(dataStore)

    /** The ticket keys and the free half (docs/VOICE_CALLS.md §2.6), seeded with [freeIssuer] for its audience. */
    val ticketKeys = TicketKeyStore(dataStore, freeIssuer?.let { mapOf(it.aud to it.origin) }.orEmpty())

    // What the manager is handed: the store only where a free issuer is wired, so every other node runs without tickets.
    private val freeKeys: TicketKeyStore? = ticketKeys.takeIf { freeIssuer != null }

    /** The holder's side of the supporter badge (ADR 2026-10.r99s), in the same settings file, kept across restarts. */
    val badges = BadgeStore(dataStore)

    /**
     * Whether this node's image classifier calls every picture it decodes explicit — what the bundled model
     * says of a picture it minds. Read at each classification, so a scenario can flip it mid-run; kept across
     * restarts. Content filtering is on by default, so a flagged picture is also a refused one.
     */
    @Volatile
    var picturesExplicit = false

    /**
     * The texts this node's text moderator flags, whole — what the bundled models would say of words they mind. Read
     * at each classification, so a scenario can set it mid-run; empty, every text is allowed.
     */
    @Volatile
    var flaggedText: Set<String> = emptySet()

    /** This node's custody writes, holdable by a scenario ([LabCustodyGate]): a database slow to commit. */
    val custodyGate = LabCustodyGate()

    // --- the live stack, rebuilt by boot() ---

    /**
     * The short-range radio — links, holds, releases and, on a node with pages, the side channel. The LoRa
     * child, when there is one, sits beside it.
     */
    lateinit var transport: LabTransport
        private set

    /** The real LoRa plane over the shared air, or null for a node without a board. */
    internal var lora: LoraMeshTransport? = null
        private set

    /** The composite the manager runs on when the node has a board ([lora] beside the radio), else null. */
    private var composite: CompositeMeshTransport? = null

    /** Set by [MeshLab.leaveRelay] until [MeshLab.rejoinRelay]: a [restart] meanwhile boots with the relay still off. */
    @Volatile
    internal var relayLeft = false

    /** Whether a boot opts this node into its relay, as the relay editor would. */
    private fun optsIntoRelay(): Boolean = (spool != null || publicRelays != null) && spoolOptIn && !relayLeft

    // The home store over this node's settings, with the house relays (ADR 2026-10.8kfx); null without them.
    private val homes: HomeStore? = publicRelays?.let { HomeStore(dataStore) }

    /** This node's home relay host, or null without the house relays. */
    suspend fun home(): String? = homes?.home?.first()

    /** Replaces the directory this node holds, as a read would; the next start settles the home against it. */
    suspend fun putDirectory(entries: List<PublicSpool>) {
        checkNotNull(homes) { "$name has no house relays" }.putDirectory(entries, now())
    }

    /** Everything the LoRa plane logged this boot — `lora tx <label> parts=N` lines are the air oracle. */
    val loraLog = CopyOnWriteArrayList<String>()

    /** Every walkie chunk's plaintext this node's pipeline opened and handed the call layer (§3.11). */
    val talks = CopyOnWriteArrayList<ByteArray>()

    // The talk plaintexts of [talks] a spool cast here, rather than a radio.
    val talksViaRelay = CopyOnWriteArrayList<ByteArray>()

    private fun heardTalk(
        chunk: ByteArray,
        viaRelay: Boolean,
    ) {
        talks += chunk
        if (viaRelay) talksViaRelay += chunk
    }

    /**
     * The call layer (docs/VOICE_CALLS.md §3), rebuilt by boot() as the debug build's DI builds it, minus the media
     * ([LabCallMedia]), TURN, the Wi-Fi Direct leg and the walkie. Its tombstones go to the settings file, so a
     * restart() carries them as a process death does (C-3.4-3).
     */
    lateinit var calls: CallManager
        private set

    /**
     * Every line this boot's call layer logged: `call <id> from <peer>: …` is its decision oracle. Each boot starts a
     * list only its own [calls] writes, so a line here is never the dead process's.
     */
    var callLog: List<String> = emptyList()
        private set
    lateinit var manager: MeshManager
        private set
    lateinit var messages: MessageRepository
        private set
    lateinit var groups: GroupRepository
        private set
    lateinit var peers: PeerRepository
    private var commons: CommonsRepository? = null
        private set
    lateinit var metrics: MeshMetrics
        private set
    lateinit var blobs: BlobRepository
        private set

    /** The prefixes of transfers a cut link left this node, rebuilt each boot over one directory as a process is. */
    lateinit var partials: PartialBlobs
        private set
    lateinit var reactionStore: ReactionRepository
        private set
    private lateinit var messageCrypto: MessageCrypto

    /** The composer's picker door, for a picture this node already holds ([forwardImage], ADR 2026-10.mptm). */
    private lateinit var attachments: AttachmentStore

    /** What this node has done for other people's messages — the Your mesh screen's lifetime numbers. */
    lateinit var ledger: ContributionLedger
        private set
    private lateinit var receipts: MessageReceiptRepository
    private lateinit var forwardStore: ForwardRepository
    private var scope: CoroutineScope? = null

    /** The self-certifying id, computed once from the bundle exactly as [Identity.nodeId] does. */
    val nodeId: String = NodeId.fromPublicKeyBundle(identity.publicKeyBundle())

    /** This node's identity file, for a twin built over it. Minted by the [nodeId] read above, so never null. */
    internal fun identityBytes(): ByteArray = checkNotNull(secretBytes) { "$name has no identity" }.copyOf()

    @Suppress("LongMethod") // the DI module's wiring, mirrored in one place on purpose
    internal suspend fun boot() {
        val session = dispatcher(Dispatchers.Default)
        val scope = CoroutineScope(SupervisorJob() + session).also { this.scope = it }
        partials = PartialBlobs(File(dir, "blob-partials"), now = now)
        transport =
            LabTransport(nodeId, File(dir, "rx"), partials, pages, chaos).apply {
                advertises = if (limits.answersHolderQueries) Protocol.CAP_BLOB_HAVE else 0L
                clock = now
                feedStallMs = limits.feedStallMs
            }
        metrics = MeshMetrics()
        // The Internet plane is opted into through the same two settings the relay editor writes; the
        // settings file persists, so a restart() dials the same spool again.
        optIntoRelays()
        // The real DataStore-backed settings are the journal, so a restart() proves the totals persist.
        ledger = ContributionLedger(journal = settings, selfId = { nodeId }, clock = now)
        val keys = keyStore.keys()
        messageCrypto = MessageCrypto(keys.hybridPrivate, keys.sigPrivate)
        messages =
            MessageRepository(
                db.messageDao(),
                nearbyMaxMessages = limits.roomMaxMessages,
                roomMaxPerStranger = limits.roomMaxPerStranger,
                maxPerPendingThread = limits.maxPerPendingThread,
            )
        commons = if (withCommons) CommonsRepository(db.commonsDao(), messages, db) else null
        val reactions = ReactionRepository(db.reactionDao(), db).also { reactionStore = it }
        receipts = MessageReceiptRepository(db.messageReceiptDao(), messages, db)
        blobs =
            BlobRepository(
                db.blobDao(),
                db.messageDao(),
                db.peerDao(),
                settings,
                db.blobVerdictDao(),
                db.groupDao(),
                db.forwardDao(),
                db,
                db.savedFileDao(),
                db.attachmentHolderDao(),
            )
        val groupRatchetStore = GroupRatchetRepository(db.groupRatchetDao())
        val groupRoots = GroupRootRepository(db.groupRootDao())
        groups = GroupRepository(db.groupDao(), messages, db, groupRatchetStore, groupRoots)
        // The name-clash fade, as the DI module wires it (ADR 2026-10.k8ge), on this node's clock. One tick, not the
        // hourly one: a scenario reads a directory afresh after moving the clock, and each read judges the window then.
        val fade =
            LabelFade(
                anchors = LabelAnchors.from(messages, groups, settings, identity, scope),
                clock = now,
                ticks = flowOf(Unit),
                transact = { block -> db.withWriteTransaction { block() } },
            )
        peers = PeerRepository(db.peerDao(), settings, identity, fade = fade)
        forwardStore =
            ForwardRepository(
                db.forwardDao(),
                StoreDigest(clock = now),
                db,
                ttlMs = limits.custodyTtlMs,
                broadcastTtlMs = limits.custodyBroadcastTtlMs,
                maxRows = limits.custodyMaxRows,
                maxPerSender = limits.custodyMaxPerSender,
                maxPerGroup = limits.custodyMaxPerGroup,
                maxBroadcast = limits.custodyMaxBroadcast,
                metrics = metrics,
            )
        // The leaves with a hardware or UI side. Text allowed unless [flaggedText], every picture unless [picturesExplicit],
        // notifications swallowed. (A relaxed ImageModerator mock hands back a mocked verdict whose `flagged`
        // reads false by accident; the explicit answer is what the tflite model would say about a picture it does
        // not mind.)
        val textModerator = mockk<ScopedTextModerator> { coEvery { classify(any(), any()) } answers { textVerdict(firstArg()) } }
        val pictures =
            mockk<ImageModerator> {
                coEvery { classify(any()) } answers
                    { if (picturesExplicit) EXPLICIT else ImageVerdict.ALLOWED }
            }
        val imageScreening = ImageScreeningService(pictures, db.blobVerdictDao(), textModerator)
        val blobStore = MeshBlobStore(blobs, messages, imageScreening, File(dir, "blobtx"))
        attachments = AttachmentStore(context, blobs, imageScreening, AttachmentIdentities(messages, blobs))
        val notifier = mockk<Notifier>(relaxed = true)
        // THE ratchet lock + the transaction that encloses it, shared by both session services (di/MeshModule).
        val ratchetMutex = Mutex()
        val transactor =
            object : SessionTransactor {
                override suspend fun <T> transact(block: suspend () -> T): T = db.withWriteTransaction { block() }
            }
        // The LoRa child is built the way `di/MeshModule` builds it — the real transport over the fake air,
        // its frame sources late-bound to the manager exactly as the DI lambdas are — and composed with the
        // radio through the real composite, so the cover, the election inputs and the fast-plane dispatch
        // are the production ones.
        lora =
            air?.let { air ->
                LoraMeshTransport(
                    selfId = { nodeId },
                    link = FakeMeshtasticLink(loraNodeNum(), air),
                    config = MutableStateFlow(LoraConfig("AA:$name", 0, bridge = limits.loraBridge)),
                    selfProfile = { manager.signedProfile() },
                    farFrames = { manager.framesFor(it) },
                    offerPrefixes = { manager.offerPrefixes(it) },
                    framesMissing = { prefixes, limit, dms -> manager.framesMissing(prefixes, limit, dms) },
                    onPublicPost = { manager.onPublicPostHeard(it) },
                    scope = scope,
                    metrics = metrics,
                    clock = now,
                    wallClock = now,
                    log = { loraLog += it },
                    // No inter-packet gap and no Trickle jitter: the scenario's clock is the wall clock.
                    pace = LoraPacePolicy(minGapMs = 0),
                    gossip =
                        LoraGossipPolicy(
                            minIntervalMs = limits.loraGossipMinMs,
                            maxIntervalMs = limits.loraGossipMaxMs,
                            random = { 0 },
                        ),
                    lingerSweepMs = limits.loraLingerSweepMs,
                )
            }
        composite = lora?.let { CompositeMeshTransport(listOf(transport, it), scope) }
        val meshTransport: MeshTransport = composite ?: transport
        manager =
            MeshManager(
                transport = meshTransport,
                messages = messages,
                receipts = receipts,
                groups = groups,
                reactions = reactions,
                pins = PinRepository(db.pinDao(), db),
                peers = peers,
                metPeers = MetPeerRepository(db.metPeerDao(), db),
                identity = identity,
                settings = settings,
                blobs = blobs,
                imageScreening = imageScreening,
                blobStore = blobStore,
                partials = partials,
                forwardStore = custodyGate.over(forwardStore),
                notifier = notifier,
                textModeration = textModerator,
                messageCrypto = messageCrypto,
                ratchet =
                    RatchetSessions(
                        store = RatchetRepository(db.ratchetDao()),
                        dhIdentityPriv = keyStore::dhIdentityPrivate,
                        spkPrivFor = keyStore::prekeyPrivFor,
                        mutex = ratchetMutex,
                        transact = transactor,
                    ),
                groupRatchet = GroupRatchetSessions(store = groupRatchetStore, mutex = ratchetMutex, transact = transactor),
                groupRoots = groupRoots,
                scope = scope,
                metrics = metrics,
                ledger = ledger,
                db = db,
                clock = now,
                tickDebounceMs = MeshLab.TICK_DEBOUNCE_MS,
                rideHoldMs = limits.rideHoldMs,
                dmAckHoldMs = limits.dmAckHoldMs,
                spoolTickMs = limits.spoolTickMs,
                spoolAttachmentRetryMs = limits.spoolAttachmentRetryMs,
                firstAskGraceMs = limits.firstAskGraceMs,
                proofWaitMs = limits.proofWaitMs,
                reofferIntervalMs = limits.reofferIntervalMs,
                blobHolderQuery =
                    BlobExchange.HolderQuery(
                        enabled = limits.blobQuery,
                        waitMs = limits.queryWaitMs,
                        fetchingRecheckMs = limits.fetchingRecheckMs,
                    ),
                blobPullBudgets = limits.pullBudgets,
                sessionDispatcher = session,
                ingressBudget = IngressBudget(burst = limits.ingressBurst, perMinute = limits.ingressPerMinute, clock = now),
                spoolDialer = spool ?: publicRelays?.dialer,
                commons = commons,
                homes = homes,
                ticketKeys = freeKeys,
                ticketIssuer = freeIssuer,
                freeKeys = freeKeys,
                accountIssuer = freeIssuer,
                badgeJournal = badges,
                badgePins = listOf(BadgeKeys.RFC8032_TEST1),
                onCallSignal = { sender, payload, sentAt -> calls.onSignal(sender, payload, sentAt) },
                onCallTalk = { _, chunk, _, _, viaRelay -> heardTalk(chunk, viaRelay) },
            )
        val callLines = CopyOnWriteArrayList<String>().also { callLog = it }
        calls =
            CallManager(
                signals = CallSignals { peerId, payload -> manager.sendCallSignal(peerId, payload) },
                turn =
                    object : TurnSource {
                        override suspend fun turnServers(peerId: String): List<TurnServer> = emptyList()

                        override fun stunUrls(): List<String> = emptyList()
                    },
                media = LabCallMedia(),
                scope = scope,
                selfId = { nodeId },
                clock = now,
                log = { callLines += it },
                tombs = CallTombStore(dataStore),
            )
        manager.start()
        // The session's collectors subscribe asynchronously; a frame sent before that is emitted into nobody.
        // The startup seed of our own profile into custody is asynchronous too, and the lab has no cue plane:
        // a custody row that lands after a link's first digest exchange is not offered again until the 60 s
        // re-offer, so a scenario that links straight after boot would sometimes leave one node's seed
        // unconverged inside the oracle's window. Wait for the seed, so every link starts from a settled store.
        withContext(Dispatchers.Default) {
            withTimeout(MeshLab.AWAIT_MS) {
                while (!transport.collecting) delay(1)
                transport.joinPages()
                while (custodyIds().none { it.startsWith("profile-$nodeId-") }) delay(1)
            }
        }
    }

    /**
     * The relay opt-in [boot] makes, as the relay editor would, and on a first boot with the house relays the lab's
     * directory and the scenario's home, before the refresher settles one.
     */
    private suspend fun optIntoRelays() {
        if (optsIntoRelay()) {
            settings.setSpoolEnabled(true)
            settings.addSpoolUrl(if (publicRelays != null) PublicSpools.LEGACY_URL else MeshLab.SPOOL_URL)
        }
        val relays = publicRelays ?: return
        val store = homes ?: return
        if (store.directoryReadAt() != 0L) return
        store.putDirectory(relays.directory, now())
        home?.let { host -> dataStore.edit { it[stringPreferencesKey(SettingsKeys.SPOOL_HOME)] = host } }
    }

    /** [boot], counted toward the sweep's per-run boot time ([LabChaos.noteBoot]). */
    private suspend fun timedBoot() {
        val started = System.nanoTime()
        boot()
        chaos?.noteBoot(System.nanoTime() - started, fresh = false)
    }

    /**
     * Process death and relaunch: the live stack goes, the identity, database and settings stay. The links
     * go too, and every peer's neighbor collector is handed the departure before the node is back (see
     * [MeshLab.unlink]). [whileDown] runs between the two: a settings edit made while the mesh was stopped.
     */
    suspend fun restart(whileDown: suspend LabNode.() -> Unit = {}) {
        val peers = shutdownLive()
        awaitPeersSettled(peers)
        whileDown()
        timedBoot()
    }

    /**
     * Every peer of a node going down has been handed the departure, and has finished handling each frame it
     * was handed before it ([LabTransport.awaitInboundDrained]) — its custody row, a DM receipt it seals inline.
     * A frame sent into the dying link reaches the relaunched node only through its digest exchange, which must not
     * run before the frame and its custody row exist, or the 60 s re-offer is left to carry it. The settle stays for a
     * peer with a board, whose composite `merge` makes the drained signal early.
     */
    private suspend fun awaitPeersSettled(peers: List<LabTransport>) {
        peers.forEach { it.awaitNeighborsObserved() }
        withContext(Dispatchers.Default) { peers.forEach { it.awaitInboundDrained() } }
        withContext(Dispatchers.Default) { delay(MeshLab.SETTLE_MS) }
    }

    /**
     * Process death and relaunch **from a backup of this node**: the live stack goes, and what comes back is
     * what `data/backup` restores — the identity and settings as they were, the database with its
     * [BackupTables.TRANSIENT] tables (custody, both ratchets) emptied exactly as the export leaves them, and
     * the settings carrying the restore mark that makes the next `MeshManager.start` run `finishRestore`.
     * The snapshot is taken at the moment of the call; nothing this node held in memory survives.
     */
    suspend fun restoreFromBackup() {
        val peers = shutdownLive()
        awaitPeersSettled(peers)
        db.withWriteTransaction { BackupTables.TRANSIENT.forEach { executeSQL("DELETE FROM $it") } }
        dataStore.edit { it[booleanPreferencesKey(SettingsKeys.RESTORE_PENDING)] = true }
        timedBoot()
    }

    internal fun shutdown() {
        shutdownLive()
        settingsScope.cancel()
        db.close()
    }

    /** A stable board number per name, so two nodes on one air are never the same board. */
    private fun loraNodeNum(): UInt = (name.hashCode().toUInt() and 0x7FFF_FFFFu) + 1u

    /** Tears the live stack down and returns the transports it was linked to. */
    private fun shutdownLive(): List<LabTransport> {
        val linked = transport.disconnectAll()
        transport.detach()
        loraLog.clear()
        // stop() banks the ledger and the name-clash fade's lastSeenAt stamps on the app scope, which the next line
        // cancels; in the lab the "app" scope is this session's, so bank them here first — the process-death the
        // restart models is the orderly kind.
        runBlocking {
            ledger.flush()
            peers.flushSeen()
        }
        manager.stop()
        // Joined, not only cancelled: a collector still mid-query (the name-clash fade's anchors) when [shutdown] closes
        // the database next throws on the closed pool, and that lands as an uncaught exception on whichever `runTest`
        // class this JVM runs after the scenario. Bounded and checked: a child that ignores cancellation fails the
        // scenario that owns it, by name, instead of racing the database close as before.
        // The session goes first, the app scope after it, as on a phone: a delivery the router admitted runs on under
        // `NonCancellable` past stop() (ADR 2026-10.f82h) and may still read what the app scope serves (the
        // name-clash fade's shared anchors, `LabelAnchors.current`). Cancelling both at once left it waiting on a
        // shared flow whose producer was gone, and the join below waited out its bound on it.
        scope?.coroutineContext?.job?.let { job ->
            val joined =
                runBlocking {
                    withTimeoutOrNull(SCOPE_JOIN_MS) {
                        job.children
                            .filter { it.isCancelled }
                            .toList()
                            .joinAll()
                        job.cancelAndJoin()
                    }
                }
            check(joined != null) { "$name: session scope still running $SCOPE_JOIN_MS ms after cancel: ${job.children.toList()}" }
        }
        scope = null
        return linked
    }

    // --- what a user does ---

    suspend fun setDisplayName(value: String) {
        if (settings.displayName.first() == value) return
        published { settings.setDisplayName(value) }
    }

    /**
     * Runs one profile edit and returns once the manager has published it: the version moved and custody holds
     * the frame under the current publish stamp, past the one before the edit. `broadcastProfile` writes the
     * three under `profileLock` before it originates the frame, so a link brought up next finds the frame in
     * the digest, and a parity wait next waits for the flood. The settings write returns before the profile
     * watcher runs; a scenario that linked or unlinked in that gap found the edit's frame originated to nobody
     * and stranded in this node's custody (`RoomTickPlanesLabTest` under the throttled loop, 2026-09-16). The
     * version alone is not the event — it moves before the frame is signed — and neither is `framesOriginated`,
     * which a first-contact push or a reachable reflood moves too: a wait on the two returned before the edit's
     * frame existed, and a link taken down next lost it (`CloneLabTest` under chaos, 2026-09-24). A flood
     * record on [LabTransport] would miss a board node, whose composite never hands its child a null-target
     * send. The caller has already checked the edit is a real change — a no-op write publishes nothing and this
     * would wait on it forever.
     */
    private suspend fun published(edit: suspend () -> Unit) {
        val version = settings.profileVersion.first()
        val stamp = settings.profilePublishedAt.first()
        edit()
        val done =
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(MeshLab.AWAIT_MS) {
                    while (true) {
                        val now = settings.profilePublishedAt.first()
                        if (settings.profileVersion.first() > version && now > stamp && "profile-$nodeId-$now" in custodyIds()) break
                        delay(MeshLab.POLL_MS)
                    }
                }
            } != null
        val held = custodyIds().filter { it.startsWith("profile-$nodeId-") }
        check(done) {
            "$name's profile edit was never published: version ${settings.profileVersion.first()} (was $version), " +
                "stamp ${settings.profilePublishedAt.first()} (was $stamp), custody $held\n  warnings:\n" +
                ShadowLog.getLogs().filter { it.type >= Log.WARN }.takeLast(MeshLab.WARNINGS_KEPT).joinToString("\n") {
                    "    ${it.tag}: ${it.msg}${it.throwable?.let { t -> " ($t)" } ?: ""}"
                }
        }
    }

    /** Posts in the Nearby room, quoting [replyTo] when given, formatted by [styles]; the frame the app's composer would send. */
    suspend fun sendRoom(
        text: String,
        replyTo: ReplyRef? = null,
        styles: List<StyleSpan> = emptyList(),
    ): Boolean = spaced { manager.sendChat(text = text, replyTo = replyTo, styles = styles) }

    /** The call ids whose end this node's call layer has written to its settings file (C-3.4-3). */
    suspend fun savedCallTombs(): Set<String> = CallTombStore(dataStore).load().mapTo(HashSet()) { it.id }

    /** Runs the local-storage sweep the 10-minute prune loop runs, now. */
    suspend fun sweepLocalStorage() = manager.sweepLocalStorage()

    /** Runs the TTL sweep the 10-minute prune loop and the heartbeat run (custody, parked frames, wants), now. */
    suspend fun sweepExpired() = manager.sweepExpired()

    /** Runs one round of the 60 s re-offer to every linked neighbour (custody digest, blob and key re-asks), now. */
    suspend fun reoffer() = manager.reofferToNeighbors()

    /**
     * Sends one chunk of a walkie burst to [to], as `WalkieTalk` streams one (docs/VOICE_CALLS.md §3.11); [castToo] is
     * the walkie's verdict that its radio path loses chunks (`TalkRoute`).
     */
    suspend fun sendTalk(
        to: LabNode,
        chunk: ByteArray,
        castToo: Boolean = false,
    ): Boolean = spaced { manager.sendCallTalk(to.nodeId, chunk, castToo = castToo) != null }

    /** Sends a DM, formatted by [styles]; the frame the app's composer would send. */
    suspend fun sendDm(
        to: LabNode,
        text: String,
        styles: List<StyleSpan> = emptyList(),
    ): Boolean = spaced { manager.sendChat(text = text, recipientId = to.nodeId, styles = styles) }

    /**
     * Makes this node seal its DMs to [peer] in the named v2 layout, as it does toward a build without crypto scheme
     * v3 (ADR 059): clears `CAP_CRYPTO_V3` on the pinned row, the bit `CryptoScheme` reads. [v3] true puts it back. A
     * later profile frame from [peer] carries the bit back too, so call it once the pair is acquainted.
     */
    suspend fun sealToAsOlderBuild(
        peer: LabNode,
        v3: Boolean = false,
    ) {
        val row = checkNotNull(peers.find(peer.nodeId)) { "$name has no pin for ${peer.name}" }
        val caps = row.capabilities ?: 0L
        peers.upsert(row.copy(capabilities = if (v3) caps or Protocol.CAP_CRYPTO_V3 else caps and Protocol.CAP_CRYPTO_V3.inv()))
    }

    /**
     * Makes this node answer [author]'s DMs with the legacy cleartext receipt, as it answers an author that cannot
     * read a sealed one (knit-ios without its ratchet, an older build): clears `CAP_RATCHET` on the pinned row, the
     * bit `InboundPipeline.sealDmReceipt` reads. Only the receipt's form moves; the DMs still arrive sealed. A later
     * profile frame from [author] carries the bit back, so call it once the pair is acquainted. [cleartext] false
     * puts the bit back — a cleartext receipt never confirms the author's ratchet, so the session oracle wants a
     * sealed frame from this node before the scenario ends.
     */
    suspend fun answerWithCleartextReceipts(
        author: LabNode,
        cleartext: Boolean = true,
    ) {
        val row = checkNotNull(peers.find(author.nodeId)) { "$name has no pin for ${author.name}" }
        val caps = row.capabilities ?: 0L
        peers.upsert(row.copy(capabilities = if (cleartext) caps and Protocol.CAP_RATCHET.inv() else caps or Protocol.CAP_RATCHET))
    }

    /**
     * Creates a group with [others], the way `ContactsViewModel.createGroup` does (mirrored here because the
     * ViewModel needs a Main dispatcher; the body is the same three writes). Returns the group id — which is
     * the hash of the member set, so the same people are always the same group.
     */
    suspend fun createGroup(vararg others: LabNode): String = createGroupWith(others.map { it.nodeId })

    /** [createGroup] over raw member ids — for a member no [LabNode] stands behind (a forged profile's phantom id). */
    suspend fun createGroupWith(otherIds: List<String>): String {
        val me = nodeId
        val members = (otherIds + me).distinct()
        val groupId = Conversations.groupIdFor(members)
        val existing = groups.find(groupId)
        if (existing != null && !existing.left) return groupId
        val createdAt = now()
        groups.upsert(
            GroupEntity(
                groupId = groupId,
                name = "",
                members = GroupMembersStore.encode(members),
                createdBy = me,
                createdAt = createdAt,
                nameUpdatedAt = 0L,
                left = false,
            ),
        )
        messages.save(StatusNotices.groupCreated(groupId, me, createdAt))
        manager.mintGroupRoots()
        return groupId
    }

    /**
     * Floods over this node's links the profile a modified client can publish (#172): this node's own Ed25519 key
     * beside a low-order X25519 key (32 zero bytes) that every DH and HPKE wrap refuses, with a prekey signed by that
     * same Ed25519 key. The bundle self-certifies a phantom node id, which every receiver pins like any profile; the
     * phantom is returned. Nothing stands behind it — no stack answers for it.
     */
    suspend fun floodLowOrderIdentityProfile(name: String): String {
        val sigPub = checkNotNull(PublicKeyBundle.decode(identity.publicKeyBundle())).sigPublicKey()
        val forged = checkNotNull(PublicKeyBundle.fromRaw(sigPub, ByteArray(RatchetCrypto.KEY_BYTES)))
        val phantom = NodeId.fromPublicKeyBundle(forged.encoded)
        val spk = RatchetCrypto.generateKeyPair().pub
        val at = now()
        val content =
            ProfileContent(
                name = name,
                status = "",
                pubKey = forged.encoded,
                protoVersion = Protocol.VERSION,
                capabilities = Protocol.LOCAL_CAPABILITIES,
                prekey = PrekeyInfo(id = 1, pub = spk, sig = messageCrypto.signRaw(RatchetCrypto.spkSigningBytes(1, spk))),
                version = at,
            )
        val env =
            RelayEnvelope(
                type = FrameType.PROFILE,
                id = "profile-$phantom-$at",
                senderId = phantom,
                sentAt = at,
                payload = WireCodec.encodePayload(content),
            )
        val signed = WireCodec.encodeEnvelope(env)
        transport.send(WireEnvelope(sig = messageCrypto.signRaw(signed), signed = signed), null)
        return phantom
    }

    /**
     * Floods over this node's links a Nearby-room post signed by this node under an id it chose, [id], rather than one
     * it minted — what a modified client can send (#178). Nothing is stored here: the post goes straight to the air.
     */
    suspend fun floodRoomPostUnder(
        id: String,
        text: String,
    ) {
        val env =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = id,
                senderId = nodeId,
                sentAt = now(),
                payload = WireCodec.encodePayload(ChatContent(body = text)),
            )
        val signed = WireCodec.encodeEnvelope(env)
        transport.send(WireEnvelope(sig = messageCrypto.signRaw(signed), signed = signed), null)
    }

    /** Floods [frame] over this node's links as it stands, signed by whoever signed it (a [LabSybil]): a fork's radio. */
    suspend fun flood(frame: WireEnvelope) = transport.send(frame, null)

    /** Floods [env] signed by this node, whatever it says: a frame a modified client sends and no build would. */
    suspend fun floodSigned(env: RelayEnvelope) {
        val signed = WireCodec.encodeEnvelope(env)
        transport.send(WireEnvelope(sig = messageCrypto.signRaw(signed), signed = signed), null)
    }

    /** Sends into a group this node holds, quoting [replyTo] when given; the frame the chat screen's composer would send. */
    suspend fun sendGroup(
        groupId: String,
        text: String,
        replyTo: ReplyRef? = null,
        styles: List<StyleSpan> = emptyList(),
    ): Boolean {
        val group = checkNotNull(groups.find(groupId)) { "$name holds no group $groupId" }
        return spaced { manager.sendChat(text = text, group = group.toGroupInfo(), replyTo = replyTo, styles = styles) }
    }

    /**
     * Reacts to [messageId] in [conversationId] with [emoji] — or retracts, when that is the emoji already
     * held — as tapping the chip does (`ChatViewModel.react`): the thread decides the form, sealed for a DM
     * or a group, cleartext for the room.
     */
    suspend fun react(
        conversationId: String,
        messageId: String,
        emoji: String,
    ) {
        val isRoom = Conversations.isPublicRoom(conversationId)
        val group = if (isRoom) null else groups.find(conversationId)?.toGroupInfo()
        val recipientId = if (isRoom || group != null) null else conversationId
        spaced { manager.sendReaction(messageId, emoji, recipientId, group) }
    }

    /** Leaves [groupId] as the group screen does: the signed leave floods first, then the local tombstone. */
    suspend fun leaveGroup(groupId: String) {
        spaced { manager.sendGroupLeave(groupId) }
        groups.leave(groupId)
    }

    /** Renames [groupId] as the group screen does; last writer wins on the frame's clock. */
    suspend fun renameGroup(
        groupId: String,
        newName: String,
    ) {
        val trimmed = normalizeSingleLine(newName).take(TextLimits.GROUP_NAME)
        val group = checkNotNull(groups.find(groupId)) { "$name holds no group $groupId" }
        val updated = group.copy(name = trimmed, nameUpdatedAt = now())
        groups.upsert(updated)
        spaced { manager.sendGroupUpdate(updated.toGroupInfo()) }
    }

    suspend fun setStatus(value: String) {
        if (settings.status.first() == value) return
        published { settings.setStatus(value) }
    }

    suspend fun setOpenToChat(value: Boolean) {
        if (settings.openToChat.first() == value) return
        published { settings.setOpenToChat(value) }
    }

    /** Blocks [peer] as every Block button does (the device tag rides along so a re-keyed peer stays blocked). */
    suspend fun block(peer: LabNode) = settings.block(peer.nodeId, peers.find(peer.nodeId)?.deviceTag)

    suspend fun unblock(peer: LabNode) = settings.unblock(peer.nodeId, peers.find(peer.nodeId)?.deviceTag)

    /** Accepts a message request — one settings write, the whole of what the Requests inbox does. */
    suspend fun accept(conversationId: String) = settings.accept(conversationId)

    /**
     * Removes [peer] from contacts as the profile's Remove does: the real `ContactRemover` over this node's
     * stores (ADR 2026-09.adgd). The notifier is a relaxed double, as the booted stack's is. The draft
     * store launches its delete on the scope it is given, so it gets this call's own: the helper returns only
     * once that delete has run, rather than leaving it on the session scope past the call.
     */
    suspend fun removeContact(peer: LabNode) =
        coroutineScope {
            val drafts = DraftRepository(db.draftDao(), this, Dispatchers.Unconfined)
            ContactRemover(settings, peers, messages, groups, drafts, manager, mockk(relaxed = true), identity).remove(peer.nodeId)
        }

    /** Whether [peer] is in this node's contacts, by the rule the picker draws (`contactIds`). */
    suspend fun isContact(peer: LabNode): Boolean {
        val signals = observeContactSignals(messages, groups, settings, flowOf(nodeId)).first()
        return contactStanding(peer.nodeId, signals, peers.verifiedNodeIds().toSet(), settings.blockedNodeIds.first()).isContact
    }

    /** Whether this node's DM thread with [peer] sits in Message Requests, by ADR 009's one shared rule. */
    suspend fun isRequest(peer: LabNode): Boolean {
        val signals = observeContactSignals(messages, groups, settings, flowOf(nodeId)).first()
        return !Conversations.isAccepted(dmWith(peer), signals.accepted, peers.verifiedNodeIds().toSet(), signals.authored)
    }

    /**
     * Sends [bytes] as an image — to [to], into [groupId], or into the room when both are null — the frame
     * the composer sends after the picker. The bytes go straight into the blob store under their own hash,
     * skipping `AttachmentStore.ingest`: under Robolectric's legacy graphics `Bitmap.compress` writes a
     * placeholder, so two different pictures would collapse into one hash. From there the path is the app's:
     * a DM or group seals the bytes to a fresh key and the frame carries the ciphertext hash (ADR 035).
     */
    suspend fun sendImage(
        bytes: ByteArray,
        text: String = "",
        to: LabNode? = null,
        groupId: String? = null,
    ): Boolean {
        val hash = sha256Hex(bytes)
        blobs.insert(hash, IMAGE_MIME, bytes)
        val attachment = AttachmentStore.Ingested(hash = hash, mime = IMAGE_MIME, sizeBytes = bytes.size)
        val group = groupId?.let { checkNotNull(groups.find(it)) { "$name holds no group $it" }.toGroupInfo() }
        return spaced {
            manager.sendChat(
                text = text,
                attachment = attachment,
                mentions = emptyList(),
                recipientId = to?.nodeId,
                group = group,
                replyTo = null,
            )
        }
    }

    /**
     * Sends [bytes] as an arbitrary file named [fileName] — to [to], into [groupId], or into the room when both are
     * null — the frame the composer sends for a picked document. Like [sendImage] the bytes go straight into the blob
     * store under their own hash; what differs is the ingest's name, which makes this a file: a DM or group seals the
     * name and the byte count inside the content (ADR 2026-09.qq2r), and a receiver's row carries both only when the
     * copy it opened did.
     */
    suspend fun sendFile(
        bytes: ByteArray,
        fileName: String,
        mime: String,
        text: String = "",
        to: LabNode? = null,
        groupId: String? = null,
    ): Boolean {
        val hash = sha256Hex(bytes)
        blobs.insert(hash, mime, bytes)
        val attachment = AttachmentStore.Ingested(hash = hash, mime = mime, name = fileName, sizeBytes = bytes.size)
        val group = groupId?.let { checkNotNull(groups.find(it)) { "$name holds no group $it" }.toGroupInfo() }
        return spaced {
            manager.sendChat(
                text = text,
                attachment = attachment,
                mentions = emptyList(),
                recipientId = to?.nodeId,
                group = group,
                replyTo = null,
            )
        }
    }

    /**
     * Sends [bytes] as a short video described by [video] — to [to], into [groupId], or into the room when both are
     * null — the frame the composer sends once the re-encode is done (ADR 2026-10.hmfe). Like [sendImage] the bytes go
     * straight into the blob store under their own hash: the re-encode needs the platform codecs Robolectric does not
     * have, and the mesh's half is the same for any bytes. The description is the sender's label, carried as is.
     */
    suspend fun sendVideo(
        bytes: ByteArray,
        video: VideoInfo,
        text: String = "",
        to: LabNode? = null,
        groupId: String? = null,
    ): Boolean {
        val hash = sha256Hex(bytes)
        blobs.insert(hash, VIDEO_MIME, bytes)
        val attachment = AttachmentStore.Ingested(hash = hash, mime = VIDEO_MIME, sizeBytes = bytes.size, video = video)
        val group = groupId?.let { checkNotNull(groups.find(it)) { "$name holds no group $it" }.toGroupInfo() }
        return spaced {
            manager.sendChat(
                text = text,
                attachment = attachment,
                mentions = emptyList(),
                recipientId = to?.nodeId,
                group = group,
                replyTo = null,
            )
        }
    }

    /**
     * Sends [bytes] as a voice note — to [to], into [groupId], or into the room when both are null — the frame the
     * composer sends when a recording stops. The sender describes its own copy at ingest, as `AttachmentStore` does;
     * the duration is header arithmetic, so an `adts` fixture times on the host, while the waveform needs the
     * platform decoder Robolectric does not have and stays null. Nothing about either rides the wire.
     */
    suspend fun sendVoice(
        bytes: ByteArray,
        to: LabNode? = null,
        groupId: String? = null,
    ): Boolean {
        val hash = sha256Hex(bytes)
        blobs.insert(hash, VoiceAudio.MIME, bytes)
        val attachment =
            AttachmentStore.Ingested(hash = hash, mime = VoiceAudio.MIME, voice = VoiceAudio.describe(bytes), sizeBytes = bytes.size)
        val group = groupId?.let { checkNotNull(groups.find(it)) { "$name holds no group $it" }.toGroupInfo() }
        return spaced {
            manager.sendChat(
                text = "",
                attachment = attachment,
                mentions = emptyList(),
                recipientId = to?.nodeId,
                group = group,
                replyTo = null,
            )
        }
    }

    /**
     * Sends [bytes] the way the composer sends a picture saved out of a chat and picked again (ADR 2026-10.mptm) —
     * to [to], into [groupId], or into the room when both are null — through the real picker door, so it goes out
     * as the identity this node already holds it under. The scenario owns that it does: a pick nothing here
     * matches would take the bitmap path, which Robolectric's legacy graphics turn into a placeholder (see
     * [sendImage]), so a miss fails here rather than as a puzzle in the oracle.
     */
    suspend fun forwardImage(
        bytes: ByteArray,
        text: String = "",
        to: LabNode? = null,
        groupId: String? = null,
    ): Boolean {
        val picked = attachments.ingestPicked(bytes, IMAGE_MIME)
        val attachment = (picked as? AttachmentStore.IngestResult.Success)?.ingested
        check(attachment?.reuse != null) { "$name holds no identity for those bytes: $picked" }
        val group = groupId?.let { checkNotNull(groups.find(it)) { "$name holds no group $it" }.toGroupInfo() }
        return spaced {
            manager.sendChat(
                text = text,
                attachment = attachment,
                mentions = emptyList(),
                recipientId = to?.nodeId,
                group = group,
                replyTo = null,
            )
        }
    }

    /** Whether a pick of [bytes] here would go out as an identity this node already holds (ADR 2026-10.mptm). */
    suspend fun holdsIdentityFor(bytes: ByteArray): Boolean = AttachmentIdentities(messages, blobs).find(sha256Hex(bytes)) != null

    /** Sets our avatar to [bytes], as the Profile screen's crop confirm does once the store holds the JPEG. */
    suspend fun setAvatar(bytes: ByteArray) {
        val hash = sha256Hex(bytes)
        val old = settings.ownAvatarHash.first()
        blobs.insert(hash, IMAGE_MIME, bytes)
        settings.setOwnAvatarHash(hash)
        published { settings.setAvatarUpdatedAt(now()) }
        if (old != null && old != hash) blobs.deleteIfUnreferenced(old)
    }

    /** Sets [groupId]'s photo to [bytes], as the group screen's crop confirm does; the bytes cross as a blob. */
    suspend fun setGroupPhoto(
        groupId: String,
        bytes: ByteArray,
    ) {
        val hash = sha256Hex(bytes)
        blobs.insert(hash, IMAGE_MIME, bytes)
        val group = checkNotNull(groups.find(groupId)) { "$name holds no group $groupId" }
        val updated = group.copy(photoHash = hash, photoUpdatedAt = now(), photoShownHash = hash)
        groups.upsert(updated)
        spaced { manager.sendGroupUpdate(updated.toGroupInfo()) }
    }

    /** Our contact card in its compact link form, as the Verify screen shares it. */
    suspend fun mintCard(): String = ContactCards(identity, settings, signRaw = messageCrypto::signRaw).mint().compact

    /** Imports a card as the Add-by-link screen does; the intro driver and a key request start from here. */
    suspend fun importCard(card: String) {
        val importer = ContactImporter(peers, settings, identity, manager, internetPlane = spool != null)
        val ready =
            checkNotNull(importer.preview(ContactCard.parse(card)) as? ContactImporter.Preview.Ready) { "$name could not import the card" }
        importer.import(ready)
    }

    /**
     * The relay invite for this node's spool as the relay row shares it (docs/RELAY_INVITE.md §4): the URL
     * as stored, plus the room this node has joined there, if any.
     */
    suspend fun mintInvite(): String {
        val room = commons?.roots()?.firstOrNull { it.spoolUrl == MeshLab.SPOOL_URL }
        val name = room?.let { commons?.find(it.conversationId)?.name }
        return RelayInvite.url(RelayInvite.mint(MeshLab.SPOOL_URL, room?.secret, name))
    }

    /** Applies a relay invite as the sheet's confirmation does: consent, the relay, the room, dial now. */
    suspend fun applyInvite(link: String) {
        val invite =
            checkNotNull(RelayInvite.parse(link, allowCleartext = false) as? RelayInvite.Parsed.Invite) { "$name got no invite: $link" }
        val applier = RelayInviteApplier(settings, commons, manager, clock = now)
        applier.apply(applier.preview(invite))
    }

    /** Joins the spool's commons as the relay row's Join does, and nudges the plane so it subscribes now. */
    suspend fun joinCommons(
        secret: ByteArray,
        roomName: String?,
    ): String {
        val id = checkNotNull(commons) { "$name has no commons store" }.join(MeshLab.SPOOL_URL, secret, roomName, now())
        manager.refreshRelays()
        return id
    }

    /** The rooms this node has joined, by conversation id. */
    suspend fun joinedRooms(): Set<String> =
        commons
            ?.roots()
            ?.map { it.conversationId }
            ?.toSet()
            .orEmpty()

    /** Posts to a joined commons; false when the room is unknown or the text was refused. */
    suspend fun postCommons(
        conversationId: String,
        text: String,
    ): Boolean = spaced { manager.sendCommons(conversationId, text, emptyList(), null) }

    /** Forces a DM session reset toward [peer] (Diagnostics' button): null once it went out, else the refusal. */
    suspend fun resetSession(peer: LabNode): String? = manager.forceRatchetReset(peer.nodeId)

    /**
     * Runs the 15-minute heartbeat basket now and returns once it has run to its end (`healsCompleted`).
     * `MeshManager.heal` only launches the basket; a scenario that re-linked the node the moment it returned
     * raced the basket's tail — the republished profile is *seeded into custody, never flooded*, and a link-up
     * whose digest exchange ran before that row existed left it for the 60 s re-offer, past every await
     * (`TimeLabTest`, three scenarios, on one slow core). The sealed `CTL_PROFILE` the basket sends after it
     * is custodied at origination, so once the basket is done a re-link's exchange carries both.
     */
    suspend fun heal() {
        val before = metrics.frames().healsCompleted
        manager.heal()
        val done =
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(MeshLab.AWAIT_MS) {
                    while (metrics.frames().healsCompleted <= before) delay(MeshLab.POLL_MS)
                }
            } != null
        check(done) { "$name's heal basket never finished (healsCompleted stayed at $before)" }
    }

    /**
     * Returns once this node's LoRa child (if it has a board) holds the radio's link set — the composite's
     * `suppressDataPath` hand-off, which runs on a coroutine of its own after the radio's `neighbors` publish.
     * The child's routing (the election, `fastSend`'s linked-peer skip, the send-time `LINKED` drop) reads that
     * set, not the radio's. Compared as sets, on the routing field itself: `status.pocketLinks` is a count, and
     * `publishStatus` has several unlocked writers, so it can keep a stale value long after the hand-off.
     *
     * The composite's own `neighbors` — what the manager reads for "is the author linked" (`AckSync`, the
     * newcomer hooks) — is a third copy, a `combine … stateIn` with a collector of its own, so it is waited for
     * too. The 100 ms settle used to cover it; a chaos stall outlasts that, and a room tick owed just after an
     * unlink then took the live-link branch toward a peer whose link was gone and was lost with the send
     * (`RoomTickPlanesLabTest`'s LoRa-only tick under chaos seed 7424, 2026-09-28 — the loss itself is #96).
     */
    internal suspend fun awaitBoardSawLinks() {
        val board = lora ?: return
        val stack = checkNotNull(composite) { "$name has a board and no composite" }
        val radio = { transport.neighbors.value.mapTo(HashSet()) { it.nodeId } }
        val merged = { stack.neighbors.value.mapTo(HashSet()) { it.nodeId } }
        val seen =
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(MeshLab.AWAIT_MS) {
                    while (board.pocketLinkIds != radio() || merged() != radio()) delay(MeshLab.POLL_MS)
                }
            } != null
        check(seen) { "$name's board never took the radio's links ${radio()} (holds ${board.pocketLinkIds}, the stack reads ${merged()})" }
    }

    /**
     * Returns once this node's manager has acted on the radio's current link set ([MeshManager.linksActedOn]).
     * [LabTransport.awaitNeighborsObserved] proves the radio's own collectors were handed a change; a node with
     * a board runs its manager on the composite's merged `StateFlow`, which conflates on its own, and a re-link
     * inside that window was no newcomer to the manager — no blob re-ask, no digest, until the 60 s re-offer
     * (`AttachmentLabTest`'s long-isolation picture under chaos seed 913483320, 2026-09-28).
     */
    internal suspend fun awaitLinksActedOn() {
        val radio = { transport.neighbors.value.mapTo(HashSet()) { it.nodeId } }
        val seen =
            withContext(Dispatchers.Default) {
                withTimeoutOrNull(MeshLab.AWAIT_MS) { while (manager.linksActedOn != radio()) delay(1) }
            } != null
        check(seen) { "$name's manager never acted on the links ${radio()} (last acted on ${manager.linksActedOn})" }
    }

    /** Drops every custody row, as a wiped database would; the digest is rebuilt over nothing. */
    suspend fun wipeCustody() {
        forwardStore.sweepExpired(Long.MAX_VALUE)
    }

    /**
     * Drops the custody rows whose id [matches] — a frame the per-sender quota evicted here, say, which the
     * store would otherwise hold for as long as its TTL. The digest follows, as for any removal. Off the
     * scenario thread: run on Robolectric's main thread, the removal left this node's inbound path parked for
     * good (it never delivered another frame).
     */
    suspend fun forgetCustody(matches: (String) -> Boolean) {
        withContext(Dispatchers.Default) { custodyIds().filter(matches).forEach { forwardStore.remove(it) } }
    }

    /**
     * Runs one send, then lets the wall clock tick over before returning, so no two of this node's frames
     * share a `sentAt`. A warm JIT sends in under a millisecond, and two posts stamped alike have no
     * "newer": the DAO's tiebreak is the random frame id, so which one a room sweep keeps — or which of two
     * reactions wins the LWW race — is a coin flip (`RoomFloodLabTest` flaked on CI exactly there). A phone
     * never produces that tie; the lab must not either. A spin, not a skewed clock: the manager's clock
     * stays the wall clock every peer shares, so nothing here can push a frame into a peer's future.
     */
    private inline fun <T> spaced(send: () -> T): T {
        val result = send()
        val stamped = now()
        while (now() <= stamped) Thread.onSpinWait()
        return result
    }

    // --- what a test reads back ---

    /** The ordinary (decrypted, non-notice) messages this node holds in [conversationId], as (id, body). */
    suspend fun decrypted(conversationId: String): Set<Pair<String, String>> =
        messages
            .observeNewestMessages(conversationId, MeshLab.WINDOW)
            .first()
            .filter { it.kind == MessageEntity.KIND_NORMAL && !it.pendingKey }
            .map { it.id to it.body }
            .toSet()

    /**
     * For every ordinary message this node authored in [conversationId]: the members of [among] (other than
     * itself) whose delivery receipt has not reached it, keyed by body. Empty when every tick has landed.
     */
    suspend fun missingAcks(
        conversationId: String,
        among: List<LabNode>,
    ): Map<String, List<String>> {
        val others = among.filter { it !== this }
        return messages
            .observeNewestMessages(conversationId, MeshLab.WINDOW)
            .first()
            .filter { it.kind == MessageEntity.KIND_NORMAL && it.senderId == nodeId }
            .associate { m ->
                val ackers =
                    receipts
                        .observeForMessage(m.id)
                        .first()
                        .map { it.ackerNodeId }
                        .toSet()
                m.body to others.filter { it.nodeId !in ackers }.map { it.name }
            }.filterValues { it.isNotEmpty() }
    }

    /** Every row in [conversationId] as `body@sentAt via plane` — the listing a diagnosis wants. */
    suspend fun rowsIn(conversationId: String): List<String> =
        messages
            .observeNewestMessages(conversationId, MeshLab.WINDOW)
            .first()
            .sortedBy { it.sentAt }
            .map { "${it.body}@${it.sentAt} via ${DeliveryPlane.fromCode(it.receivedVia)} kind=${it.kind}" }

    /** The ordinary Nearby-room posts this node holds, keyed by author node id → bodies. */
    suspend fun roomPosts(): Map<String, Set<String>> =
        messages
            .observeNewestMessages(Conversations.NEARBY, MeshLab.WINDOW)
            .first()
            .filter { it.kind == MessageEntity.KIND_NORMAL }
            .groupBy({ it.senderId }, { it.body })
            .mapValues { it.value.toSet() }

    /** How many inbound frames this node refused for [reason] this session. */
    fun drops(reason: DropReason): Long = metrics.frames().dropsByReason[reason] ?: 0L

    /** The sealed envelope of [messageId]'s copy in this node's custody — which form it went out in — or null. */
    suspend fun custodiedSeal(messageId: String): EncEnvelope? =
        forwardStore
            .liveFrames(now())
            .firstOrNull { it.envelope.id == messageId }
            ?.let { WireCodec.decodePayload<ChatContent>(it.envelope.payload)?.enc }

    /** The custody store's live id set, as the digest exchange advertises it. */
    suspend fun custodyIds(): Set<String> = forwardStore.liveIds(now()).toSet()

    /** The carried hashes the database re-arm would want now: custody's carrier column, less what is held. */
    suspend fun carrierWants(): Set<String> = forwardStore.attachmentsNeedingFetch(now()).keys

    /** Every live custody row as `id:type:sender→recipient@sentAt#sigHash` — a diff of two of these names the odd frame out. */
    suspend fun custodyFrames(): List<String> =
        forwardStore
            .liveFrames(now())
            .map {
                "${it.envelope.id}:${it.envelope.type}:${it.envelope.senderId.take(
                    6,
                )}→${it.envelope.recipientId?.take(6)}@${it.envelope.sentAt}#${it.sig.contentHashCode()}/${it.signed.contentHashCode()}"
            }.sorted()

    /**
     * The key [messageId]'s custodied frame crosses a pipe under (`FrameKey`, what [LabTransport.skipsToward] counts),
     * or null while this node does not carry it.
     */
    suspend fun crossingKey(messageId: String): String? =
        forwardStore
            .liveFrames(now())
            .firstOrNull { it.envelope.id == messageId }
            ?.let { FrameKey.ofSigned(WireEnvelope(sig = it.sig, signed = it.signed)) }

    /** `liveFingerprint`: the digest recomputed over the live rows — what two converged stores share. */
    suspend fun custodyFingerprint(): Long = StoreDigest.fingerprint(custodyIds())

    /** Whether this node has pinned [peer]'s key (its profile arrived). */
    suspend fun knows(peer: LabNode): Boolean = peers.find(peer.nodeId)?.pubKey != null

    /** The peer row this node pinned for [nodeId] — for an id no [LabNode] stands behind (a forged profile's phantom). */
    suspend fun pinned(nodeId: String): PeerEntity? = peers.find(nodeId)

    /** How many distinct phones this node has been in range of — the met-peers table's count. */
    suspend fun peopleMet(): Int = db.metPeerDao().count()

    /** The lifetime contribution numbers as the Your mesh screen would read them (persisted + unflushed). */
    suspend fun contributions(): ContributionTotals = ledger.totals.first()

    /** The router counters that explain a frame that never arrived: delivered / relayed / deduped / suppressed. */
    fun metricsLine(): String =
        metrics.snapshot().let { snap ->
            val f = snap.frames
            val k = snap.keys
            val g = snap.groups
            "  $name: originated=${f.framesOriginated} delivered=${f.framesDelivered} relayed=${f.framesRelayed} " +
                "deduped=${f.framesDeduped} suppressed=${f.framesSuppressed} drops=${f.dropsByReason} " +
                "held=${k.framesHeld}/${k.framesReplayed} seedsSent=${g.groupSeedsSent} seedsAdopted=${g.groupSeedsAdopted} " +
                "seedsHeld=${g.groupSeedsHeld} seedsReplayed=${g.groupSeedsReplayed} keyReq=${g.groupKeyRequestsSent}"
        } + loraLine()

    /** The LoRa plane's counters and the radio's own recent log lines — empty for a node without a board. */
    fun loraLine(): String {
        if (lora == null) return ""
        val s = metrics.lora()
        val lines =
            loraLog
                .filter { l -> LORA_LOG_KEYS.any { it in l } && "bridge held" !in l && "offer" !in l }
                .takeLast(LORA_LOG_LINES)
                .joinToString("\n         ")
        return "\n    lora: sent=${s.loraSent} received=${s.loraReceived} suppressed=${s.loraSuppressed} passive=${s.loraPassive} " +
            "offers=${s.loraOfferSent}/${s.loraOfferReceived} bridged=${s.loraBridged} nak=${s.loraNak} dropped=${s.loraDroppedQueue} " +
            "stale=${s.loraStaleAtSendByReason} unheard=${s.loraUnheardHeld} probes=${s.loraDmProbes} " +
            "reoffered=${s.loraReoffered} tickDeferred=${s.loraTickDeferred} reassembled=${s.loraReassembled} " +
            "frag=${s.loraFragSent}\n    rx/tx: $lines"
    }

    /** The DM thread id between this node and [peer], as this node names it. */
    fun dmWith(peer: LabNode): String = Conversations.idFor(nodeId, peer.nodeId, nodeId)

    /** The id of the ordinary message this node authored in [conversationId] with [body], once it is in its store. */
    suspend fun ownMessageId(
        conversationId: String,
        body: String,
    ): String =
        checkNotNull(
            messages
                .observeNewestMessages(conversationId, MeshLab.WINDOW)
                .first()
                .firstOrNull { it.kind == MessageEntity.KIND_NORMAL && it.senderId == nodeId && it.body == body }
                ?.id,
        ) { "$name holds no own message \"$body\" in $conversationId" }

    /** What this node's text moderator says of [text]: flagged when it is one of [flaggedText], else allowed. */
    private fun textVerdict(text: String): TextVerdict = if (text in flaggedText) FLAGGED_TEXT else TextVerdict.ALLOWED

    /** This node's stored row for [messageId], or null — the quote columns a reply's check wrote (ADR 2026-10.gpa3). */
    suspend fun row(messageId: String): MessageEntity? = messages.find(messageId)

    /** The plane this node's row for [messageId] in [conversationId] crossed to get here, as the bubble would say it. */
    suspend fun receivedVia(
        conversationId: String,
        messageId: String,
    ): DeliveryPlane {
        val row = messages.observeNewestMessages(conversationId, MeshLab.WINDOW).first().firstOrNull { it.id == messageId }
        return DeliveryPlane.fromCode(checkNotNull(row) { "$name holds no $messageId in $conversationId" }.receivedVia)
    }

    /** Who has told this node they received [messageId], and over which plane each tick arrived. */
    suspend fun receiptPlanes(messageId: String): Map<String, DeliveryPlane> =
        receipts.observeForMessage(messageId).first().associate { it.ackerNodeId to DeliveryPlane.fromCode(it.via) }

    /** Whether a connected spool has heard from [peer] within the mesh's cover window (ADR 2026-09.y5f3). */
    fun spoolPresent(peer: LabNode): Boolean = peer.nodeId in spoolPresentPeers(manager.spoolStatus(), now(), SPOOL_COVER_MS)

    /**
     * The DM scope this node shares with [peer] on its spool, as the relay editor would list it — the live
     * one, never the pair scope, which carries the same label for its 48 h grace (spec §3.5) and converges
     * *before* the session does now that the table derives on each side's own confirmation (ADR
     * 2026-09.dcah): the responder holds a DM scope while the initiator still has only the pair scope, and a
     * lookup by label alone compared the two.
     */
    fun dmScopeStatus(peer: LabNode): ScopeStatus? =
        manager.spoolStatus().flatMap { it.scopes }.firstOrNull { it.label == peer.nodeId && !it.pair && !it.retiring }

    /** The scope labelled [label] (a peer id for a DM scope, a group id for a group scope) on this node's spool. */
    fun scopeStatus(label: String): ScopeStatus? = manager.spoolStatus().flatMap { it.scopes }.firstOrNull { it.label == label && !it.pair }

    /** How many DM-form chat frames this node custodies that it authored toward [peer] — a room tick must add none. */
    suspend fun custodiedChatsTo(peer: LabNode): Int =
        forwardStore.liveFrames(now()).count {
            it.envelope.type == FrameType.CHAT && it.envelope.senderId == nodeId && it.envelope.recipientId == peer.nodeId
        }

    /** How many live frames of any type this node's custody holds from [senderId] — a [LabSybil]'s bucket. */
    suspend fun custodiedFrom(senderId: String): Int = forwardStore.liveFrames(now()).count { it.envelope.senderId == senderId }

    /** How many DM-form chat frames this node custodies from [sender] to [recipient] — what a carrier holds for an absent peer. */
    suspend fun custodiedChatsFrom(
        sender: LabNode,
        recipient: LabNode,
    ): Int =
        forwardStore.liveFrames(now()).count {
            it.envelope.type == FrameType.CHAT && it.envelope.senderId == sender.nodeId && it.envelope.recipientId == recipient.nodeId
        }

    /** How many `lora tx <label>…` lines this boot logged — what the board actually put on the air. */
    fun loraTx(label: String): Int = loraLog.count { it.startsWith("lora tx $label") }

    /**
     * 1 once this node's board has put frame [id] on the air, live or as the bridge's backfill, else 0 — a
     * `tryAwait` count. Live is a `lora tx <live>` line past the [liveBefore] a scenario read before the frame
     * existed (the fan-out's label names the type, never the id); backfill is `lora tx bridge:<id>`.
     *
     * Either one is the board carrying it, and a scenario cannot pin which. Custody comes first on both roads
     * to the fan-out (`MeshManager.originateSigned`, `InboundPipeline.onDeliver`), so a far gateway's OFFER that
     * lands in between makes the bridge serve the frame, take its signature slot, and the fan-out is then deduped
     * on it (ADR 2026-09.y8pu) — still one transmission (#141, Findings 4 and 5). Assert `loraTx` on the live label
     * alone only where no far board can be heard while the frame is fanned.
     */
    fun loraAired(
        id: String,
        live: String,
        liveBefore: Int = 0,
    ): Int = if (loraTx(live) > liveBefore || loraTx("bridge:$id") > 0) 1 else 0

    /** Who reacted with what on [messageId], as (reactor, emoji). A retraction is a tombstone and is not listed. */
    suspend fun reactions(messageId: String): Set<Pair<String, String>> =
        reactionStore
            .observeReactionsFor(messageId)
            .first()
            .mapNotNull { r -> r.emoji?.let { r.reactorNodeId to it } }
            .toSet()

    /** The group row as this node holds it, or null. */
    suspend fun group(groupId: String): GroupEntity? = groups.find(groupId)

    /**
     * Issues [payload] as this node's scope config for [conversationId] — a peer's node id or a group's id
     * (`MeshManager.sendScopeConfig`, docs/SPOOL_PROTOCOL.md §5). True when it was sealed and flooded.
     */
    suspend fun sendScopeConfig(
        conversationId: String,
        payload: ScopeConfigPayload,
    ): Boolean = manager.sendScopeConfig(conversationId, payload)

    /**
     * The scope config [conversationId] runs on here, with the conversation id blanked: a DM's is the *peer's* id on
     * each side, so two nodes holding the same config compare equal only without it.
     */
    suspend fun scopeConfig(conversationId: String): ScopeConfig? = manager.scopeConfigs.winning(conversationId)?.copy(conversationId = "")

    /**
     * The versions of every scope-config row this node holds for [conversationId], eligible or deferred, [issuer]'s alone
     * when given: what a node whose clock runs behind has stored but cannot read yet, or the proof that a peer's row
     * landed beside our own.
     */
    suspend fun heldScopeConfigs(
        conversationId: String,
        issuer: LabNode? = null,
    ): Set<Int> =
        db
            .scopeConfigDao()
            .configsOf(conversationId)
            .filter { issuer == null || it.issuer == issuer.nodeId }
            .mapTo(HashSet()) { it.version }

    /** Drops every scope-config row this node holds for [conversationId]: a restore from before them, or rows lost. */
    suspend fun forgetScopeConfigs(conversationId: String) {
        val dao = db.scopeConfigDao()
        dao.configsOf(conversationId).forEach { dao.deleteIssuer(conversationId, it.issuer) }
    }

    /**
     * Whether this node holds a committed receive chain for [sender]'s messages in [groupId] — a seed adopted
     * *and* committed. `groupSeedsAdopted` moves inside the seed's ratchet transaction, before it commits, so a
     * restart in that gap rolls the chain back while the counter says adopted.
     */
    suspend fun holdsGroupChainFrom(
        groupId: String,
        sender: LabNode,
    ): Boolean =
        db.useReaderConnection { connection ->
            connection.usePrepared("SELECT 1 FROM group_recv_chains WHERE groupId = ? AND senderId = ? LIMIT 1") {
                it.bindText(1, groupId)
                it.bindText(2, sender.nodeId)
                it.step()
            }
        }

    /**
     * What every member must agree on about a group: who is in, who left, the name and the photo. Not
     * `nameUpdatedAt` — the renamer stamps it from the wall clock and everyone else from the frame, so it
     * legitimately differs by a few milliseconds.
     */
    suspend fun groupShape(groupId: String): GroupShape? =
        group(groupId)?.let {
            GroupShape(
                members = GroupMembersStore.decode(it.members).toSet(),
                departed = GroupMembersStore.decode(it.departed).toSet(),
                name = it.name,
                photoHash = it.photoHash,
                photoUpdatedAt = it.photoUpdatedAt,
                left = it.left,
            )
        }

    private suspend fun row(
        conversationId: String,
        messageId: String,
    ): MessageEntity? = messages.observeNewestMessages(conversationId, MeshLab.WINDOW).first().firstOrNull { it.id == messageId }

    /** Whether the bytes behind the message's attachment are held here (true for a message that has none). */
    suspend fun attachmentHeld(
        conversationId: String,
        messageId: String,
    ): Boolean {
        val hash = row(conversationId, messageId)?.attachmentHash ?: return true
        return blobs.exists(hash)
    }

    /** Whether the blob [hash] is held here, whether or not a row names it — a carried attachment's included. */
    suspend fun holdsBlob(hash: String): Boolean = blobs.exists(hash)

    /** The playing time this node's row for the voice note [messageId] shows, or null: undescribed, or no row. */
    suspend fun voiceDurationOf(
        conversationId: String,
        messageId: String,
    ): Int? = row(conversationId, messageId)?.voiceDurationMs

    /** Whether screening here flagged the blob [hash] — an avatar or a group photo, which no message row names. */
    suspend fun imageFlagged(hash: String): Boolean = db.blobVerdictDao().find(hash)?.flagged == true

    /** The content hash the message's row names (the ciphertext hash for a DM or a group), or null. */
    suspend fun attachmentHash(
        conversationId: String,
        messageId: String,
    ): String? = row(conversationId, messageId)?.attachmentHash

    /** The key the message's row opens its attachment with, or null — a room picture, or a key-less forward of one. */
    suspend fun attachmentKey(
        conversationId: String,
        messageId: String,
    ): String? = row(conversationId, messageId)?.attachmentKey

    /** Whether screening here has reached a verdict on the message's attachment. */
    suspend fun attachmentScreened(
        conversationId: String,
        messageId: String,
    ): Boolean {
        val hash = row(conversationId, messageId)?.attachmentHash ?: return false
        return db.blobVerdictDao().find(hash) != null
    }

    /** Whether screening here flagged the message's attachment. */
    suspend fun attachmentFlagged(
        conversationId: String,
        messageId: String,
    ): Boolean {
        val hash = row(conversationId, messageId)?.attachmentHash ?: return false
        return db.blobVerdictDao().find(hash)?.flagged == true
    }

    /** The attachment's plaintext as this node can read it (opened with the sealed key for a DM or a group). */
    suspend fun attachmentPlain(
        conversationId: String,
        messageId: String,
    ): ByteArray? {
        val row = row(conversationId, messageId) ?: return null
        val hash = row.attachmentHash ?: return null
        val stored = blobs.bytes(hash) ?: return null
        val key = row.attachmentKey ?: return stored
        return AttachmentCrypto.open(stored, b64d(key))
    }

    /** [peer]'s row on this node: name, status, flag, avatar and the profile version they were last seen at. */
    suspend fun peer(peer: LabNode): PeerEntity? = peers.find(peer.nodeId)

    /** How many badge rows this node stores for [peer], verified or not. */
    suspend fun badgeRowsFor(peer: LabNode): Int = peers.observeBadges().first().count { it.nodeId == peer.nodeId }

    /** The badge ids this node would draw on [peer]'s avatar now: its stored rows, verified at its own clock. */
    suspend fun badgesShownFor(peer: LabNode): List<String> =
        PeerBadges
            .verifyAll(peers.observeBadges().first(), now() / 1_000, listOf(BadgeKeys.RFC8032_TEST1))[peer.nodeId]
            ?.map { it.b }
            .orEmpty()

    /** How this node presents [peer], in the fields a profile carries; null without a row. */
    suspend fun presentationOf(peer: LabNode): Presentation? =
        peer(peer)?.let { Presentation(it.name, it.status, it.openToChat, it.avatarHash, it.updatedAt) }

    /** How this node presents itself — what every other node's [presentationOf] must converge on. */
    suspend fun ownPresentation(): Presentation =
        Presentation(
            settings.displayName.first(),
            settings.status.first(),
            settings.openToChat.first(),
            settings.ownAvatarHash.first(),
            // The version peers hold: past the cleartext one when a badge-only change was published since (ADR 2026-10.r99s).
            maxOf(settings.profileVersion.first(), badges.sealedVersion.first()),
        )

    /** The DM session with [peer] as Diagnostics lists it, or null when this node holds no row for them. */
    suspend fun session(peer: LabNode): RatchetPeerState? = manager.ratchetState().firstOrNull { it.peerId == peer.nodeId }

    /** Live custody rows addressed by their sender to themselves — always a bug (the self-pin loop's signature). */
    suspend fun selfAddressedCustody(): List<String> =
        forwardStore
            .liveFrames(now())
            .filter { it.envelope.recipientId != null && it.envelope.recipientId == it.envelope.senderId }
            .map { it.envelope.id }

    /** The status-notice kinds in [conversationId], oldest first (`MessageEntity.KIND_*`). */
    suspend fun notices(conversationId: String): List<Int> =
        messages
            .observeNewestMessages(conversationId, MeshLab.WINDOW)
            .first()
            .filter { it.kind != MessageEntity.KIND_NORMAL }
            .sortedBy { it.sentAt }
            .map { it.kind }

    /** The status notice [id] in [conversationId] as this node holds it — its subject and `sentAt` — or null. */
    suspend fun notice(
        conversationId: String,
        id: String,
    ): MessageEntity? =
        messages
            .observeNewestMessages(conversationId, MeshLab.WINDOW)
            .first()
            .firstOrNull { it.id == id && it.kind != MessageEntity.KIND_NORMAL }

    /** Where the intro toward [peer] stands, or null when none is pending. */
    suspend fun introState(peer: LabNode): IntroState? = manager.introState(peer.nodeId).first()

    private companion object {
        const val IMAGE_MIME = "image/jpeg"
        const val VIDEO_MIME = "video/mp4"
        const val LORA_LOG_LINES = 24
        const val SCOPE_JOIN_MS = 5_000L

        /** The text classifier's answer to words it minds, under [flaggedText]. */
        val FLAGGED_TEXT = TextVerdict(allowed = false, category = TextVerdict.Category.TOXICITY, score = 0.99f)

        /** The classifier's answer to a picture it minds, under [picturesExplicit]. */
        val EXPLICIT = ImageVerdict(allowed = false, score = 0.99f)

        /** The LoRa log lines worth reading on a failure — the radio's own traffic, not the bridge loop's bookkeeping. */
        val LORA_LOG_KEYS = listOf("rx ", "profile-self", "ready", "fastSend", "send:", "far:", "fanout:", "held", "drop", "stale")
    }
}

/** A peer as a profile presents them — the fields every other node's row must converge on. */
data class Presentation(
    val name: String,
    val status: String,
    val openToChat: Boolean,
    val avatarHash: String?,
    val version: Long,
)

/** The part of a group row every member must hold alike (see [LabNode.groupShape]). */
data class GroupShape(
    val members: Set<String>,
    val departed: Set<String>,
    val name: String,
    val photoHash: String?,
    val photoUpdatedAt: Long,
    val left: Boolean,
)
