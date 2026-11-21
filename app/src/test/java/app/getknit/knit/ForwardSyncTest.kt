package app.getknit.knit

import app.getknit.knit.mesh.BlobExchange
import app.getknit.knit.mesh.BlobStore
import app.getknit.knit.mesh.CarriedFrame
import app.getknit.knit.mesh.CarryLedger
import app.getknit.knit.mesh.CarryReader
import app.getknit.knit.mesh.CustodyPace
import app.getknit.knit.mesh.FakeLoopTransport
import app.getknit.knit.mesh.FileMeta
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.ForwardSync
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.IndexedFrame
import app.getknit.knit.mesh.LinkCarrier
import app.getknit.knit.mesh.LinkOutbound
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshRouter
import app.getknit.knit.mesh.MeshTransport
import app.getknit.knit.mesh.PairView
import app.getknit.knit.mesh.PartialBlobs
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.ReceivedFile
import app.getknit.knit.mesh.RoundPlan
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.TransportKind
import app.getknit.knit.mesh.protocol.BlobReqContent
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.ReceiptContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.protocol.frameIdOf
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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

@Suppress("LargeClass") // one sync's whole contract: carry, digest, serve order, keys and the direct link's rounds
@OptIn(ExperimentalCoroutinesApi::class)
class ForwardSyncTest {
    /** In-memory [ForwardStore]; TTL/order mirror the real repository closely enough for the sync logic. */
    private class FakeForwardStore(
        private val ttlMs: Long = 60_000L,
        // Forces store() to refuse regardless of clocks (models a full/refusing repository).
        private val refuseAll: Boolean = false,
        // Told what is held, as the repository tells its carry ledger; null for the cases that read no ledger.
        private val carry: CarryReader? = null,
    ) : ForwardStore {
        private data class Row(
            val frame: CarriedFrame,
            val origin: Int,
            val expiresAt: Long,
            // When it arrived, as the repository's `receivedAt` orders it: a counter, so arrival is its own order and not
            // the frame's stamp or id.
            val arrived: Long,
        )

        private val rows = ConcurrentHashMap<String, Row>()
        private val arrivals = AtomicLong()

        override suspend fun store(
            frame: CarriedFrame,
            origin: Int,
            now: Long,
            source: String?,
        ): Boolean {
            if (refuseAll) return false
            // Guard-faithful to ForwardRepository: the expiry is frame-global (sentAt + TTL, ADR 006) and a
            // frame already past it is refused dead-on-arrival. The old arrival-clock expiry (now + ttlMs, no
            // guard) let a sentAt=0 receipt pass here while the real store refused it (work item #16).
            val expiresAt = frame.envelope.sentAt + ttlMs
            if (expiresAt < now) return false
            rows.computeIfAbsent(frame.envelope.id) { Row(frame, origin, expiresAt, arrivals.incrementAndGet()) }
            carry?.onKept(frame.envelope.id, source)
            return true
        }

        // How many times custody's index was read: what a look that only reads its links must leave alone.
        var indexReads = 0

        // The index as the repository builds it, `receivedAt` the arrival order ([Row.arrived]).
        override suspend fun liveIndex(now: Long): List<IndexedFrame> =
            rows.values.filter { it.expiresAt >= now }.also { indexReads++ }.map { row ->
                val env = row.frame.envelope
                IndexedFrame(
                    id = env.id,
                    type = env.type,
                    senderId = env.senderId,
                    recipientId = env.recipientId,
                    groupId = env.group?.id,
                    sentAt = env.sentAt,
                    receivedAt = row.arrived,
                    cost = IndexedFrame.wireCost(row.frame.sig.size, row.frame.signed.size),
                )
            }

        override suspend fun liveFrames(now: Long): List<CarriedFrame> =
            rows.values
                .filter { it.expiresAt >= now }
                .sortedByDescending { it.expiresAt }
                .map { it.frame }

        override suspend fun liveIds(now: Long): List<String> = rows.values.filter { it.expiresAt >= now }.map { it.frame.envelope.id }

        override suspend fun recipientOf(id: String): String? = rows[id]?.frame?.envelope?.recipientId

        override suspend fun has(id: String): Boolean = rows.containsKey(id)

        override suspend fun remove(id: String) {
            rows.remove(id)
            carry?.onLeft(id)
        }

        override suspend fun sweepExpired(now: Long): Int {
            val before = rows.size
            rows.entries.removeIf { it.value.expiresAt < now }
            carry?.retainOnly(rows.keys.toSet())
            return before - rows.size
        }

        // Attachment hashes of held chat frames (the fake doesn't track blob presence, so it doesn't filter
        // already-held ones — enough for the sync tests, which drive the pull via the onCarried hook).
        override suspend fun attachmentsNeedingFetch(now: Long): Map<String, String> =
            rows.values
                .mapNotNull { row ->
                    WireCodec.decodePayload<ChatContent>(row.frame.envelope.payload)?.attachmentHash?.let {
                        it to
                            row.frame.envelope.senderId
                    }
                }.toMap()
    }

    /** Records what the sync sends and exposes a fixed neighbor set. */
    private class RecordingTransport : MeshTransport {
        val sent = mutableListOf<Pair<WireEnvelope, Peer?>>()
        val digestsSent = mutableListOf<Pair<Peer, List<String>>>()

        // The direct link's view per peer ([pairView]): none, no link up.
        val views = mutableMapOf<String, PairView>()

        // The plane and link a send to each peer goes over ([carrier]): by default an unproven link of no plane in particular
        // holds every peer, as the sends of these cases always reached it; [unheld] says nothing holds a peer.
        val carriers = mutableMapOf<String, LinkCarrier>()
        val unheld = mutableSetOf<String>()

        // Run as a view is read: what a case uses to end an answer between the digest's read of it and its update.
        var onPairView: () -> Unit = {}

        override suspend fun pairView(nodeId: String): PairView? = views[nodeId].also { onPairView() }

        // What each peer's link holds unwritten, as a plane counts it ([LinkCarrier.outbound]); a peer not here has a link
        // that can't say. A frame sent to a peer here adds its bytes, as a link's queue counts it, until a case drains it.
        val outbound = mutableMapOf<String, Outbound>()

        // How many times a case's sync read a link ([carrier]): what "the link is read before the store" counts.
        var carrierReads = 0

        override fun carrier(nodeId: String): LinkCarrier? {
            carrierReads++
            if (nodeId in unheld) return null
            val c = carriers[nodeId] ?: LinkCarrier(TransportKind.Other, null)
            val o = outbound[nodeId] ?: return c
            return LinkCarrier(c.kind, c.link, c.proven, LinkOutbound(o.unwritten, o.rate, o.fileMoving))
        }

        override fun carries(
            kind: TransportKind,
            nodeId: String,
            link: Any?,
        ): Boolean = carriers[nodeId]?.let { it.kind == kind && it.link === link } == true

        // Who a radio holds: none unless a case links a peer ([radioLinked] reads it).
        val links = MutableStateFlow<Set<Peer>>(emptySet())
        override val neighbors = links.asStateFlow()
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
            to?.let { outbound[it.nodeId] }?.let { it.unwritten += WireCodec.encodeWire(wire).size }
        }

        override suspend fun sendFile(
            file: File,
            to: Peer,
            meta: FileMeta,
        ): Boolean = true

        override suspend fun sendDigest(
            to: Peer,
            ids: List<String>,
        ) {
            digestsSent += to to ids
        }
    }

    /** One peer's link as a plane counts it: its unwritten bytes, the rate its estimate drains at, a file moving on it. */
    private class Outbound(
        var unwritten: Long = 0L,
        var rate: Long? = null,
        var fileMoving: Boolean = false,
    )

    private fun dm(
        id: String,
        sender: String,
        recipient: String,
    ) = RelayEnvelope(
        type = FrameType.CHAT,
        id = id,
        senderId = sender,
        sentAt = 1L,
        recipientId = recipient,
        payload = WireCodec.encodePayload(ChatContent(body = "")),
    )

    private fun dmWithAttachment(
        id: String,
        sender: String,
        recipient: String,
        hash: String,
    ) = RelayEnvelope(
        type = FrameType.CHAT,
        id = id,
        senderId = sender,
        sentAt = 1L,
        recipientId = recipient,
        payload = WireCodec.encodePayload(ChatContent(body = "", attachmentHash = hash, attachmentMime = "image/jpeg")),
    )

    private fun groupMsg(
        id: String,
        sender: String,
        members: List<String>,
    ) = RelayEnvelope(
        type = FrameType.CHAT,
        id = id,
        senderId = sender,
        sentAt = 1L,
        group = GroupInfo(id = "g-test", members = members, createdBy = members.first()),
        payload = WireCodec.encodePayload(ChatContent(body = "")),
    )

    private fun broadcast(id: String) = RelayEnvelope(type = FrameType.CHAT, id = id, senderId = "a", sentAt = 1L, payload = ByteArray(0))

    private fun post(
        id: String,
        sender: String,
    ) = RelayEnvelope(type = FrameType.CHAT, id = id, senderId = sender, sentAt = 1L, payload = ByteArray(0))

    private fun receipt(id: String) = RelayEnvelope(type = FrameType.RECEIPT, id = id, senderId = "a", payload = ByteArray(0))

    private fun reaction(id: String) = RelayEnvelope(type = FrameType.REACTION, id = id, senderId = "a", payload = ByteArray(0))

    private fun profile(
        id: String,
        sender: String = "a",
        sentAt: Long = 0L,
    ) = RelayEnvelope(type = FrameType.PROFILE, id = id, senderId = sender, sentAt = sentAt, payload = ByteArray(0))

    private fun keyReq(id: String) = RelayEnvelope(type = FrameType.KEY_REQ, id = id, senderId = "a", payload = ByteArray(0))

    /** Wraps an envelope with an empty signature (these tests authenticate via the lambda, not crypto). */
    private fun wireOf(env: RelayEnvelope) = WireEnvelope(sig = ByteArray(0), signed = WireCodec.encodeEnvelope(env))

    /** Decodes the id of a frame the transport sent (the wrapper carries only the opaque signed blob). */
    private fun WireEnvelope.frameId(): String = WireCodec.decodeEnvelope(signed)!!.id

    /**
     * A DM between two others, [sender] to "q": what a contact's direct link may carry in these tests' views, where a room
     * post never crosses one (and is not even decoded for it, ADR 2026-10.a5yj).
     */
    private fun carried(
        id: String,
        sender: String,
    ) = dm(id, sender, "q")

    /**
     * Lets every live answer run out: a look at its age ends it (a link that can't say never reads the store to learn it has
     * nothing left), so the next digest starts a new answer (the custody-carry-policy contract's §2). What a case about the
     * memo, not the rounds, does between two digests.
     */
    private suspend fun ForwardSync.settle(now: Long) = serveDue(now + ForwardSync.ANSWER_MAX_AGE_MS)

    // --- pure predicate ---

    @Test
    fun everyFloodableFrameIsStorableButControlFramesAreNot() {
        assertTrue(dm("m", "a", "b").isStorable())
        assertTrue(groupMsg("g", "a", listOf("a", "b", "c")).isStorable())
        assertTrue("broadcast is carried so brief encounters backfill it", broadcast("r").isStorable())
        assertTrue("receipts are now custodied so the ✓✓ converges mesh-wide", receipt("x").isStorable())
        assertTrue("reactions are now custodied so every peer eventually sees them", reaction("k").isStorable())
        assertTrue("profiles are now custodied so they propagate delay-tolerantly", profile("p").isStorable())
        assertFalse("a point-to-point key request is never carried", keyReq("q").isStorable())
    }

    // --- onSeen capture & authentication ---

    @Test
    fun relayedDmIsCarriedOnlyWhenAuthenticated() =
        runTest {
            val rejecting = ForwardSync(RecordingTransport(), FakeForwardStore(), clock = { 0L }, authenticate = { _, _ -> false })
            val store = FakeForwardStore()
            val accepting = ForwardSync(RecordingTransport(), store, clock = { 0L }, authenticate = { _, _ -> true })

            rejecting.onSeen(wireOf(dm("m1", "a", "b")), dm("m1", "a", "b"), ForwardStore.ORIGIN_RELAY)
            accepting.onSeen(wireOf(dm("m2", "a", "b")), dm("m2", "a", "b"), ForwardStore.ORIGIN_RELAY)

            assertTrue("authenticated relay is carried", store.has("m2"))
        }

    @Test
    fun ownSendBypassesAuthentication() =
        runTest {
            // We can't authenticate our own DM against a pinned key (we don't pin ourselves), so SELF skips it.
            val store = FakeForwardStore()
            val sync = ForwardSync(RecordingTransport(), store, clock = { 0L }, authenticate = { _, _ -> false })

            val env = dm("m1", "me", "b")
            sync.onSeen(wireOf(env), env, ForwardStore.ORIGIN_SELF)

            assertTrue(store.has("m1"))
        }

    @Test
    fun everyFloodableFrameIsCarriedIncludingMetadataButControlIsNot() =
        runTest {
            val store = FakeForwardStore()
            val sync = ForwardSync(RecordingTransport(), store, clock = { 0L })

            sync.onSeen(wireOf(broadcast("r")), broadcast("r"), ForwardStore.ORIGIN_RELAY)
            sync.onSeen(wireOf(receipt("x")), receipt("x"), ForwardStore.ORIGIN_RELAY)
            sync.onSeen(wireOf(reaction("k")), reaction("k"), ForwardStore.ORIGIN_RELAY)
            sync.onSeen(wireOf(profile("p")), profile("p"), ForwardStore.ORIGIN_RELAY)
            val g = groupMsg("g1", "a", listOf("a", "b", "c"))
            sync.onSeen(wireOf(g), g, ForwardStore.ORIGIN_RELAY)
            sync.onSeen(wireOf(keyReq("q")), keyReq("q"), ForwardStore.ORIGIN_RELAY)

            assertTrue("broadcast is carried so a passing phone backfills our ambient history", store.has("r"))
            assertTrue("a receipt is now carried so the ✓✓ converges mesh-wide", store.has("x"))
            assertTrue("a reaction is now carried so it converges mesh-wide", store.has("k"))
            assertTrue("a profile is now carried so it propagates delay-tolerantly", store.has("p"))
            assertTrue("a group message is carried for offline members", store.has("g1"))
            assertFalse("a point-to-point key request is not carried", store.has("q"))
        }

    @Test
    fun aFrameWithoutASentAtIsRefusedCustodyDeadOnArrival() =
        runTest {
            val store = FakeForwardStore()
            val now = 1_000_000_000L
            val sync = ForwardSync(RecordingTransport(), store, clock = { now })

            // The work-item-#16 shape: an origination that forgot sentAt (wire default 0) computes a 1970
            // frame-global expiry, so every store — the author's own ORIGIN_SELF capture included — must
            // refuse it rather than custody it against the local arrival clock.
            val dead = RelayEnvelope(type = FrameType.RECEIPT, id = "r0", senderId = "a", payload = ByteArray(0))
            sync.onSeen(wireOf(dead), dead, ForwardStore.ORIGIN_SELF)
            assertFalse("a sentAt=0 receipt is dead on arrival, never carried", store.has("r0"))

            val live = RelayEnvelope(type = FrameType.RECEIPT, id = "r1", senderId = "a", sentAt = now, payload = ByteArray(0))
            sync.onSeen(wireOf(live), live, ForwardStore.ORIGIN_SELF)
            assertTrue("a wall-clock-stamped receipt is custodied", store.has("r1"))
        }

    @Test
    fun reservesAnAuthorItsOwnBroadcastOnlyWhenItsDigestShowsItLostIt() =
        runTest {
            val transport = RecordingTransport()
            val sync = ForwardSync(transport, FakeForwardStore(), clock = { 0L })
            val env = broadcast("r1") // authored by "a"
            sync.onSeen(wireOf(env), env, ForwardStore.ORIGIN_RELAY)

            // Author "a" still holds r1 (advertises it): the `have` diff elides it — the common case, no re-serve.
            sync.onDigest("a", listOf("r1"))
            assertTrue("a frame the author still holds is not re-served", transport.sent.isEmpty())

            // Author "a" LACKS r1 (empty digest — e.g. its custody was wiped by a destructive DB migration): now
            // re-served so the author re-carries its own send and the content digest reconverges.
            sync.onDigest("a", emptyList())
            assertEquals("an author that lost its own frame is re-served it", listOf("r1"), transport.sent.map { it.first.frameId() })

            // Any other newcomer lacking it is offered it too (no recipient/roster to target).
            transport.sent.clear()
            sync.onDigest("z", emptyList())
            assertEquals(listOf("r1"), transport.sent.map { it.first.frameId() })
        }

    @Test
    fun reservesAnAuthorItsOwnMetadataOnlyWhenItsDigestShowsItLostIt() =
        runTest {
            val transport = RecordingTransport()
            val sync = ForwardSync(transport, FakeForwardStore(), clock = { 0L })
            val react = reaction("k1") // authored by "a"
            val prof = profile("p1") // authored by "a"
            sync.onSeen(wireOf(react), react, ForwardStore.ORIGIN_RELAY)
            sync.onSeen(wireOf(prof), prof, ForwardStore.ORIGIN_RELAY)

            // Author "a" still holds both: the `have` diff elides them — no re-serve.
            sync.onDigest("a", listOf("k1", "p1"))
            assertTrue("metadata the author still holds is not re-served", transport.sent.isEmpty())

            // Author "a" lost its own metadata (empty digest → custody wiped): re-served so its custody reconverges.
            sync.onDigest("a", emptyList())
            assertEquals(
                "author is re-served its own lost metadata",
                listOf("k1", "p1"),
                transport.sent.map { it.first.frameId() }.sorted(),
            )

            // Any other newcomer lacking them is offered both.
            transport.sent.clear()
            sync.onDigest("z", emptyList())
            assertEquals(listOf("k1", "p1"), transport.sent.map { it.first.frameId() }.sorted())
        }

    // --- onCarried hook (blob custody) ---

    @Test
    fun onCarriedFiresExactlyOnceWhenAFrameIsActuallyStored() =
        runTest {
            val carried = mutableListOf<String>()
            val store = FakeForwardStore()
            val sync = ForwardSync(RecordingTransport(), store, clock = { 0L }, onCarried = { env, _ -> carried += env.id })

            val relay = dm("m1", "a", "b")
            sync.onSeen(wireOf(relay), relay, ForwardStore.ORIGIN_RELAY)
            sync.onSeen(wireOf(relay), relay, ForwardStore.ORIGIN_RELAY) // already held → dedup, no second fire
            val own = dm("m2", "me", "b")
            sync.onSeen(wireOf(own), own, ForwardStore.ORIGIN_SELF)

            assertEquals("onCarried fires once per frame actually stored (self + relay)", listOf("m1", "m2"), carried)
        }

    @Test
    fun onCarriedDoesNotFireForRejectedOrNonStorableFrames() =
        runTest {
            val carried = mutableListOf<String>()
            val rejecting =
                ForwardSync(
                    RecordingTransport(),
                    FakeForwardStore(),
                    clock = { 0L },
                    authenticate = { _, _ -> false },
                    onCarried = { env, _ -> carried += env.id },
                )
            val relay = dm("m1", "a", "b")
            rejecting.onSeen(wireOf(relay), relay, ForwardStore.ORIGIN_RELAY) // auth-rejected → not stored

            val allowing =
                ForwardSync(RecordingTransport(), FakeForwardStore(), clock = { 0L }, onCarried = { env, _ -> carried += env.id })
            val kr = keyReq("q")
            allowing.onSeen(wireOf(kr), kr, ForwardStore.ORIGIN_RELAY) // non-storable control frame → not stored

            assertTrue("no custody pull for an auth-rejected or non-storable frame", carried.isEmpty())
        }

    @Test
    fun onCarriedDoesNotFireForADeadOnArrivalFrame() =
        runTest {
            val carried = mutableListOf<String>()
            // The store refusing (the repository's dead-on-arrival guard: the frame's frame-global expiry has
            // already passed, e.g. a skewed-clock peer re-serving what everyone swept) must skip the blob pull —
            // otherwise we'd eager-fetch and pin bytes for a frame we hold no custody row for.
            val sync =
                ForwardSync(
                    RecordingTransport(),
                    FakeForwardStore(refuseAll = true),
                    clock = { 0L },
                    onCarried = { env, _ -> carried += env.id },
                )
            val env = dm("m1", "a", "b")
            sync.onSeen(wireOf(env), env, ForwardStore.ORIGIN_RELAY)

            assertTrue("no custody pull for a dead-on-arrival (store-refused) frame", carried.isEmpty())
        }

    @Test
    fun onCarriedDoesNotFireForAVaccinatedFrame() =
        runTest {
            val carried = mutableListOf<String>()
            val store = FakeForwardStore()
            val sync = ForwardSync(RecordingTransport(), store, clock = { 0L }, onCarried = { env, _ -> carried += env.id })
            val env = dm("m1", "a", "b")
            sync.onSeen(wireOf(env), env, ForwardStore.ORIGIN_RELAY)
            sync.onAck("m1", senderId = "b") // recipient ack → purge + tombstone
            carried.clear()

            sync.onSeen(wireOf(env), env, ForwardStore.ORIGIN_RELAY) // tombstoned → not re-stored

            assertTrue("a vaccinated (tombstoned) frame doesn't re-fire the custody pull", carried.isEmpty())
        }

    // --- vaccine purge ---

    @Test
    fun recipientAckPurgesCarriedDmAndTombstonesIt() =
        runTest {
            val store = FakeForwardStore()
            val sync = ForwardSync(RecordingTransport(), store, clock = { 0L })
            val env = dm("m1", "a", "b")
            sync.onSeen(wireOf(env), env, ForwardStore.ORIGIN_RELAY)
            assertTrue(store.has("m1"))

            sync.onAck("m1", senderId = "b") // ack from the addressed recipient
            assertFalse("delivered DM is purged", store.has("m1"))

            // A copy re-offered from an unvaccinated peer must not be re-stored (tombstone).
            sync.onSeen(wireOf(env), env, ForwardStore.ORIGIN_RELAY)
            assertFalse("tombstone blocks re-store", store.has("m1"))
        }

    @Test
    fun forgedAckFromNonRecipientDoesNotPurge() =
        runTest {
            val store = FakeForwardStore()
            val sync = ForwardSync(RecordingTransport(), store, clock = { 0L })
            val env = dm("m1", "a", "b")
            sync.onSeen(wireOf(env), env, ForwardStore.ORIGIN_RELAY)

            sync.onAck("m1", senderId = "attacker") // not the recipient "b"

            assertTrue("forged receipt cannot evict an undelivered DM", store.has("m1"))
        }

    @Test
    fun groupMessageIsNotVaccinePurgedByAReceipt() =
        runTest {
            val store = FakeForwardStore()
            val sync = ForwardSync(RecordingTransport(), store, clock = { 0L })
            val g = groupMsg("g1", sender = "a", members = listOf("a", "b", "c"))
            sync.onSeen(wireOf(g), g, ForwardStore.ORIGIN_RELAY)
            assertTrue(store.has("g1"))

            sync.onAck("g1", senderId = "b") // a member, but a group frame has no recipientId to match

            assertTrue("a group message is never vaccine-purged", store.has("g1"))
        }

    // --- a receipt that lands before its DM (#100) ---

    @Test
    fun aReceiptBeforeItsDmVaccinatesTheDmOnArrival() =
        runTest {
            val carried = mutableListOf<String>()
            val store = FakeForwardStore()
            val sync = ForwardSync(RecordingTransport(), store, clock = { 0L }, onCarried = { env, _ -> carried += env.id })
            val env = dm("m1", "a", "b")

            sync.onAck("m1", senderId = "b") // the recipient's receipt, served ahead of the DM
            sync.onSeen(wireOf(env), env, ForwardStore.ORIGIN_RELAY)

            assertFalse("the DM its receipt beat here is never carried", store.has("m1"))
            assertTrue("nor is its blob pulled", carried.isEmpty())
            sync.onSeen(wireOf(env), env, ForwardStore.ORIGIN_RELAY)
            assertFalse("and it is tombstoned as a purge leaves it", store.has("m1"))
        }

    @Test
    fun aReceiptHandledWhileTheDmsCustodyWriteIsInFlightStillPurgesIt() =
        runTest {
            val fake = FakeForwardStore()
            lateinit var sync: ForwardSync
            // The receipt lands on another dispatcher after onSeen's first check and before its row is visible.
            val racing =
                object : ForwardStore by fake {
                    override suspend fun store(
                        frame: CarriedFrame,
                        origin: Int,
                        now: Long,
                        source: String?,
                    ): Boolean {
                        sync.onAck(frame.envelope.id, senderId = "b")
                        return fake.store(frame, origin, now, source)
                    }
                }
            sync = ForwardSync(RecordingTransport(), racing, clock = { 0L })
            val env = dm("m1", "a", "b")

            sync.onSeen(wireOf(env), env, ForwardStore.ORIGIN_SELF)

            assertFalse("the receipt that raced the write still purges the DM", fake.has("m1"))
            sync.onSeen(wireOf(env), env, ForwardStore.ORIGIN_RELAY)
            assertFalse("and tombstones it", fake.has("m1"))
        }

    @Test
    fun anEarlyReceiptFromANonRecipientVaccinatesNothing() =
        runTest {
            val store = FakeForwardStore()
            val sync = ForwardSync(RecordingTransport(), store, clock = { 0L })
            val env = dm("m1", "a", "b")

            sync.onAck("m1", senderId = "attacker") // not the recipient "b"
            sync.onSeen(wireOf(env), env, ForwardStore.ORIGIN_RELAY)

            assertTrue("a receipt from anyone but the recipient cannot pre-empt the DM", store.has("m1"))
        }

    @Test
    fun anEarlyReceiptLapsesWithTheTombstoneTtl() =
        runTest {
            var now = 0L
            val store = FakeForwardStore(ttlMs = 48 * 60 * 60_000L)
            val sync = ForwardSync(RecordingTransport(), store, clock = { now })
            val env = dm("m1", "a", "b")

            sync.onAck("m1", senderId = "b")
            now = 24 * 60 * 60_000L + 1
            sync.onSeen(wireOf(env), env, ForwardStore.ORIGIN_RELAY)

            assertTrue("the memo is bounded by the tombstone's lifetime", store.has("m1"))
        }

    @Test
    fun anEarlyReceiptNeverTouchesAGroupOrRoomFrame() =
        runTest {
            val store = FakeForwardStore()
            val sync = ForwardSync(RecordingTransport(), store, clock = { 0L })
            val g = groupMsg("g1", sender = "a", members = listOf("a", "b", "c"))
            val room = broadcast("r1")

            sync.onAck("g1", senderId = "b")
            sync.onAck("r1", senderId = "b")
            sync.onSeen(wireOf(g), g, ForwardStore.ORIGIN_RELAY)
            sync.onSeen(wireOf(room), room, ForwardStore.ORIGIN_RELAY)

            assertTrue("a group message has no recipient to match", store.has("g1"))
            assertTrue("nor has a room post", store.has("r1"))
        }

    // --- push on contact ---

    @Test
    fun onNeighborAddedAdvertisesOurHeldIdsWithoutPushingFrames() =
        runTest {
            val transport = RecordingTransport()
            val store = FakeForwardStore()
            val sync = ForwardSync(transport, store, clock = { 0L })
            val env = dm("m1", "a", "b")
            sync.onSeen(wireOf(env), env, ForwardStore.ORIGIN_SELF)

            sync.onNeighborAdded(Peer("b"))

            assertEquals(
                "advertises the ids we hold so the peer replies with only what it lacks",
                listOf(Peer("b") to listOf("m1")),
                transport.digestsSent,
            )
            assertTrue("no frames are pushed until the peer's digest arrives", transport.sent.isEmpty())
        }

    @Test
    fun onDigestSendsOnlyTheFramesThePeerLacks() =
        runTest {
            val transport = RecordingTransport()
            val sync = ForwardSync(transport, FakeForwardStore(), clock = { 0L })
            listOf("r1", "r2", "r3").forEach {
                val e = broadcast(it)
                sync.onSeen(wireOf(e), e, ForwardStore.ORIGIN_RELAY)
            }

            sync.onDigest("z", listOf("r2")) // peer already holds r2 → push only the diff

            assertEquals(setOf("r1", "r3"), transport.sent.map { it.first.frameId() }.toSet())
        }

    /**
     * A sender's profile goes out ahead of the rest of the reply, wherever the store ranks it (ADR 2026-09.9xuu):
     * a peer meeting the sender for the first time can then verify the backlog behind it instead of parking it.
     * The store here ranks the profile last (it is the oldest frame), as the real one's newest-received-first
     * read usually does; the chat keeps its order behind it.
     */
    @Test
    fun onDigestServesProfilesAheadOfEverythingElse() =
        runTest {
            val transport = RecordingTransport()
            val sync = ForwardSync(transport, FakeForwardStore(), clock = { 0L })
            listOf(broadcast("r1"), profile("p1"), broadcast("r2")).forEach { sync.onSeen(wireOf(it), it, ForwardStore.ORIGIN_RELAY) }

            sync.onDigest("z", emptyList())

            val order = transport.sent.map { it.first.frameId() }
            assertEquals("p1", order.first())
            assertEquals(setOf("r1", "r2"), order.drop(1).toSet())
        }

    /**
     * The `onServed` report is the fact behind the Your mesh screen's "passed along": one call per frame
     * `onDigest` actually sends, naming the peer it went to, and none for a frame the peer's digest showed
     * it already holds. The ledger downstream decides what counts; the sync only reports.
     */
    @Test
    fun onDigestReportsEachServedFrameToThePeerItWentTo() =
        runTest {
            val transport = RecordingTransport()
            val served = mutableListOf<Pair<String, String>>()
            val sync =
                ForwardSync(transport, FakeForwardStore(), clock = { 0L }, onServed = { env, to -> served += env.id to to })
            listOf("r1", "r2", "r3").forEach {
                val e = broadcast(it)
                sync.onSeen(wireOf(e), e, ForwardStore.ORIGIN_RELAY)
            }

            sync.onDigest("z", listOf("r2"))

            assertEquals(setOf("r1" to "z", "r3" to "z"), served.toSet())
            assertEquals("one report per frame actually sent", transport.sent.size, served.size)
        }

    /**
     * A sync whose every sender's key is held, and whose key sends record (sender, peer, frames already sent) — the last
     * says whether it went first.
     */
    private inner class KeyedSync(
        var now: Long = 0L,
    ) {
        val ledger = CarryLedger()
        val transport = RecordingTransport().apply { carriers["z"] = LinkCarrier(TransportKind.DirectLink, Any(), proven = true) }
        val keys = mutableListOf<Triple<String, String, Int>>()
        val sync =
            ForwardSync(
                transport,
                FakeForwardStore(carry = ledger),
                clock = { now },
                keyFor = { sender ->
                    wireOf(profile("key-$sender", sender)).let { WireEnvelope(relay = false, sig = it.sig, signed = it.signed) }
                },
                sendKey = { key, to -> keys += Triple(WireCodec.decodeEnvelope(key.signed)!!.senderId, to.nodeId, transport.sent.size) },
                carry = ledger,
            )

        suspend fun carry(vararg envs: RelayEnvelope) = envs.forEach { sync.onSeen(wireOf(it), it, ForwardStore.ORIGIN_RELAY) }

        // A direct-link digest from "z", then its answer left to run out.
        suspend fun digest(ids: List<String>) {
            sync.onDigest("z", ids, TransportKind.DirectLink)
            sync.settle(now)
        }
    }

    /**
     * Custody holds no profile of a sender whose backlog goes to a peer holding none of it (ADR 2026-09.g64k): their
     * key is served ahead of every frame, once per sender, to the peer that asked.
     */
    @Test
    fun onDigestServesAKeylessSendersKeyAheadOfTheirBacklog() =
        runTest {
            val k = KeyedSync()
            k.carry(post("r1", "a"), post("r2", "a"), post("s1", "b"))

            k.sync.onDigest("z", emptyList())

            assertEquals(setOf(Triple("a", "z", 0), Triple("b", "z", 0)), k.keys.toSet())
            assertEquals("one key per sender", 2, k.keys.size)
            assertEquals(3, k.transport.sent.size)
        }

    /** A profile of theirs in custody goes first already, or the peer holds it: no second copy of the key. */
    @Test
    fun onDigestServesNoKeyForASenderWhoseProfileCustodyHolds() =
        runTest {
            val k = KeyedSync()
            k.carry(profile("p1"), post("r1", "a"))

            k.sync.onDigest("z", emptyList())
            k.sync.onDigest("y", listOf("p1"))

            assertTrue(k.keys.isEmpty())
        }

    /**
     * A peer that custodies a frame of the sender's verified it, so it holds their key: two carriers of one chatty
     * sender whose profile the quota evicted do not trade the key on every reconcile.
     */
    @Test
    fun onDigestServesNoKeyToAPeerHoldingAFrameOfTheSenders() =
        runTest {
            val k = KeyedSync()
            k.carry(post("r1", "a"), post("r2", "a"))

            k.sync.onDigest("z", listOf("r1"))

            assertEquals(listOf("r2"), k.transport.sent.map { it.first.frameId() })
            assertTrue(k.keys.isEmpty())
        }

    /** The peer's own frames coming home after a wipe: it is their key. */
    @Test
    fun onDigestServesNoKeyForTheDigestsOwnSender() =
        runTest {
            val k = KeyedSync()
            k.carry(post("r1", "z"))

            k.sync.onDigest("z", emptyList())

            assertEquals(listOf("r1"), k.transport.sent.map { it.first.frameId() })
            assertTrue(k.keys.isEmpty())
        }

    /**
     * A key rides only with a backlog: nothing of the sender's goes out (all held — a group the peer is not in is
     * served like anything else since ADR 2026-10.hr9f, so only the diff can withhold it).
     */
    @Test
    fun onDigestServesNoKeyWhenNothingOfTheSendersGoesOut() =
        runTest {
            val k = KeyedSync()
            k.carry(post("r1", "a"), groupMsg("g1", "b", listOf("b", "c")))

            k.sync.onDigest("z", listOf("r1", "g1"))

            assertTrue(k.transport.sent.isEmpty())
            assertTrue(k.keys.isEmpty())
        }

    /**
     * A group's backlog to a non-member is a backlog like any other (ADR 2026-10.hr9f): a sender whose profile custody
     * no longer holds has their key served ahead of it (ADR 2026-09.g64k), or the non-member refuses what it cannot verify.
     */
    @Test
    fun onDigestServesTheKeyAheadOfAGroupBacklogToANonMember() =
        runTest {
            val k = KeyedSync()
            k.carry(groupMsg("g1", "b", listOf("b", "c")), groupMsg("g2", "b", listOf("b", "c")))

            k.sync.onDigest("z", emptyList())

            assertEquals(listOf(Triple("b", "z", 0)), k.keys)
            assertEquals(
                setOf("g1", "g2"),
                k.transport.sent
                    .map { it.first.frameId() }
                    .toSet(),
            )
        }

    @Test
    fun onDigestSendsNothingWhenThePeerHoldsEverything() =
        runTest {
            val transport = RecordingTransport()
            val sync = ForwardSync(transport, FakeForwardStore(), clock = { 0L })
            listOf("r1", "r2").forEach {
                val e = broadcast(it)
                sync.onSeen(wireOf(e), e, ForwardStore.ORIGIN_RELAY)
            }

            sync.onDigest("z", listOf("r1", "r2")) // peer's set is a superset of ours

            assertTrue("an identical/superset digest transfers nothing", transport.sent.isEmpty())
        }

    @Test
    fun reOffersOnEveryDigestSoALostOfferSelfHeals() =
        runTest {
            val transport = RecordingTransport()
            val store = FakeForwardStore(ttlMs = 10 * 60_000L)
            val sync = ForwardSync(transport, store, clock = { 0L })
            val env = dm("m1", "a", "b")
            sync.onSeen(wireOf(env), env, ForwardStore.ORIGIN_SELF)

            // A data-path link forms only when the digest gate says the two stores differ, so each contact re-runs
            // the diff: a peer that still lacks m1 (its digest doesn't list it) is re-sent it, so an offer lost to a
            // torn-down ephemeral link self-heals on the next contact rather than stalling for a dedup timer. A
            // duplicate that did land is dropped by the receiver's SeenSet, so re-offering only ever costs bytes.
            // Each digest after the answer before it ran out (a paced answer never re-releases what it released).
            repeat(3) {
                sync.onDigest("b", emptyList())
                sync.settle(0L)
            }
            assertEquals(listOf("m1", "m1", "m1"), transport.sent.map { it.first.frameId() })
        }

    @Test
    fun reservesADmAuthorItsOwnMessageOnlyWhenItsDigestShowsItLostIt() =
        runTest {
            val transport = RecordingTransport()
            val sync = ForwardSync(transport, FakeForwardStore(), clock = { 0L })
            val env = dm("m1", "a", "b") // DM from "a" to "b"
            sync.onSeen(wireOf(env), env, ForwardStore.ORIGIN_RELAY)

            // Author "a" still holds m1: the `have` diff elides it (the common case).
            sync.onDigest("a", listOf("m1"))
            assertTrue("a DM the author still holds is not re-served", transport.sent.isEmpty())

            // Author "a" lost m1 (custody wiped by a destructive DB migration): re-served so "a" re-carries its
            // own undelivered DM and the content digest reconverges — the same recovery as broadcast/group/metadata.
            sync.onDigest("a", emptyList())
            assertEquals("a DM author that lost its own message is re-served it", listOf("m1"), transport.sent.map { it.first.frameId() })
        }

    /**
     * A group frame goes to any peer whose digest lacks it, member or not (ADR 2026-10.hr9f): a non-member left
     * short of a member's posts keeps the older frames every member evicted and re-serves them at every exchange.
     */
    @Test
    fun aNonMemberThatLacksAGroupFrameIsServedIt() =
        runTest {
            val transport = RecordingTransport()
            val served = mutableListOf<Pair<String, String>>()
            val sync = ForwardSync(transport, FakeForwardStore(), clock = { 0L }, onServed = { env, to -> served += env.id to to })
            val g = groupMsg("g1", sender = "a", members = listOf("a", "b", "c"))
            sync.onSeen(wireOf(g), g, ForwardStore.ORIGIN_SELF)

            sync.onDigest("x", emptyList()) // not in the roster, and lacks the frame
            assertEquals("a non-member that lacks a group frame is served it", listOf("g1"), transport.sent.map { it.first.frameId() })
            assertEquals("to that peer", listOf("x"), transport.sent.map { it.second?.nodeId })
            assertEquals("and the serve is reported once", listOf("g1" to "x"), served)

            sync.onDigest("y", listOf("g1")) // a non-member that already holds it: the diff elides it
            assertEquals("a peer holding the frame is not served it again", 1, transport.sent.size)
        }

    // --- TTL sweep ---

    @Test
    fun sweepReclaimsExpiredCarriedDms() =
        runTest {
            val store = FakeForwardStore(ttlMs = 100L)
            var now = 0L
            val sync = ForwardSync(RecordingTransport(), store, clock = { now })
            val env = dm("m1", "a", "b")
            sync.onSeen(wireOf(env), env, ForwardStore.ORIGIN_RELAY)

            now = 50L
            sync.sweepExpired()
            assertTrue("not yet expired", store.has("m1"))

            now = 200L
            sync.sweepExpired()
            assertFalse("expired DM reclaimed", store.has("m1"))
        }

    // --- integration: store-and-forward across a temporal gap ---

    /** In-memory, content-addressed [BlobStore] over a temp dir (mirrors BlobExchangeTest's fake). */
    private class FakeBlobStore(
        private val dir: File,
    ) : BlobStore {
        private val mimes = ConcurrentHashMap<String, String>()

        fun seed(
            hash: String,
            mime: String,
            bytes: ByteArray,
        ) {
            File(dir, hash).writeBytes(bytes)
            mimes[hash] = mime
        }

        override suspend fun has(hash: String): Boolean = File(dir, hash).exists()

        override suspend fun fileFor(hash: String): File? = File(dir, hash).takeIf { it.exists() }

        override suspend fun mimeFor(hash: String): String? = mimes[hash]

        override suspend fun saveIncoming(
            hash: String,
            mime: String,
            srcPath: String,
        ): File {
            val dest = File(dir, hash)
            File(srcPath).copyTo(dest, overwrite = true)
            mimes[hash] = mime
            return dest
        }
    }

    /**
     * A node that carries frames, delivers ones addressed to it, acks, AND custodies referenced image blobs — a
     * minimal MeshManager stand-in. The onCarried hook eager-pulls a carried chat frame's blob (so a late joiner
     * can pull it from this carrier); a delivered chat frame pulls its own blob (the recipient path).
     */
    private class Node(
        val id: String,
        scope: CoroutineScope,
    ) {
        val transport = FakeLoopTransport(id)
        val store = FakeForwardStore()
        val blobStore = FakeBlobStore(Files.createTempDirectory("fwd-blob-$id").toFile())
        val blobExchange =
            BlobExchange(
                transport,
                blobStore,
                selfId = { id },
                onObtained = { _, _ -> },
                partials = PartialBlobs(Files.createTempDirectory("fwd-parts-$id").toFile()),
            )
        val delivered = mutableListOf<String>()
        val notified = mutableListOf<String>()
        private val seenDelivered = mutableSetOf<String>()
        val sync = ForwardSync(transport, store, clock = { 0L }, onCarried = { env, _ -> onCarried(env) })
        private val router = MeshRouter(transport, scope, jitter = { 0L }) { wire, env, from, _, _, _ -> onDeliver(wire, env, from) }

        // Custody a carried chat frame's image: eager-pull the blob so a late joiner can pull it from us
        // (mirrors MeshManager.onCarriedFrame; no budget cap in the test).
        private suspend fun onCarried(env: RelayEnvelope) {
            if (env.type != FrameType.CHAT) return
            val hash = WireCodec.decodePayload<ChatContent>(env.payload)?.attachmentHash ?: return
            if (!blobStore.has(hash)) blobExchange.want(hash)
        }

        private suspend fun onDeliver(
            wire: WireEnvelope,
            env: RelayEnvelope,
            fromNodeId: String,
        ) {
            // Carry what we're relaying onward: a DM toward someone else, a group message for other
            // members (whether or not we're a member ourselves) — mirrors MeshManager's capture gate.
            if (env.isStorable()) {
                val carry = env.group != null || env.recipientId != id
                if (carry) sync.onSeen(wire, env, ForwardStore.ORIGIN_RELAY)
            }
            when (env.type) {
                FrameType.CHAT -> {
                    deliverChat(env)
                }

                FrameType.RECEIPT -> {
                    val ackId = WireCodec.decodePayload<ReceiptContent>(env.payload)?.ackId ?: return
                    sync.onAck(ackId, env.senderId)
                }

                FrameType.BLOB_REQ -> {
                    WireCodec.decodePayload<BlobReqContent>(env.payload)?.let { blobExchange.onRequest(it.hash, fromNodeId) }
                }

                else -> {}
            }
        }

        private suspend fun deliverChat(env: RelayEnvelope) {
            val members = env.group?.members
            val forMe = if (members != null) id in members else env.recipientId == id
            if (!forMe) return
            if (seenDelivered.add(env.id)) notified += env.id // first-delivery notify gate
            delivered += env.id
            // Recipient blob pull: fetch the referenced image unless already held (mirrors MeshManager.deliverChat).
            WireCodec.decodePayload<ChatContent>(env.payload)?.attachmentHash?.let {
                if (!blobStore.has(it)) blobExchange.want(it)
            }
            if (members != null) return // a group has no single-recipient receipt
            val ack =
                RelayEnvelope(
                    type = FrameType.RECEIPT,
                    id = frameIdOf("ack-${env.id}-$id"),
                    senderId = id,
                    // Mirrors InboundPipeline.acknowledge: sentAt = clock() (this rig's clock is fixed at 0) —
                    // custody derives the frame-global expiry from it (work item #16).
                    sentAt = 0L,
                    payload = WireCodec.encodePayload(ReceiptContent(env.id)),
                )
            // Through the origination choke (flood + ORIGIN_SELF capture), like production originateSigned.
            send(ack)
        }

        fun start(scope: CoroutineScope) {
            router.start()
            scope.launch {
                var known = emptySet<String>()
                transport.neighbors.collect { current ->
                    current.filter { it.nodeId !in known }.forEach {
                        sync.onNeighborAdded(it)
                        blobExchange.reask()
                    }
                    known = current.map { it.nodeId }.toSet()
                }
            }
            scope.launch { transport.incomingDigests.collect { sync.onDigest(it.fromNodeId, it.ids) } }
            scope.launch {
                transport.incomingFiles.collect { blobExchange.onReceived(it.key, it.mime, it.path) }
            }
        }

        suspend fun send(env: RelayEnvelope) {
            val wire = WireEnvelope(sig = ByteArray(0), signed = WireCodec.encodeEnvelope(env))
            router.originate(wire, env.id)
            sync.onSeen(wire, env, ForwardStore.ORIGIN_SELF)
        }
    }

    @Test
    fun dmReachesRecipientThatConnectsAfterTheFloodViaACarrier() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            a.transport.connect(b.transport)
            a.start(backgroundScope)
            b.start(backgroundScope)

            a.send(dm(frameIdOf("dm1"), sender = "a", recipient = "c"))
            advanceUntilIdle()

            assertTrue("b carries the DM while c is away", b.store.has(frameIdOf("dm1")))

            // c appears later, connecting only to b (a is no longer relevant).
            val c = Node("c", backgroundScope)
            c.start(backgroundScope)
            b.transport.connect(c.transport)
            advanceUntilIdle()

            assertEquals("c receives the carried DM exactly once", listOf(frameIdOf("dm1")), c.delivered)
            assertEquals("and notifies once", listOf(frameIdOf("dm1")), c.notified)
            assertFalse("c's ack vaccinates the carrier b", b.store.has(frameIdOf("dm1")))
        }

    @Test
    fun custodiedReceiptVaccinatesACarrierThatMissedTheLiveAck() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            val d = Node("d", backgroundScope)
            a.transport.connect(b.transport)
            a.transport.connect(d.transport)
            a.start(backgroundScope)
            b.start(backgroundScope)
            d.start(backgroundScope)

            a.send(dm(frameIdOf("dm1"), sender = "a", recipient = "c"))
            advanceUntilIdle()
            assertTrue("b carries the DM while c is away", b.store.has(frameIdOf("dm1")))
            assertTrue("d carries the DM while c is away", d.store.has(frameIdOf("dm1")))

            // d wanders off before the recipient appears, so it misses the live ack flood entirely.
            a.transport.disconnect(d.transport)

            val c = Node("c", backgroundScope)
            c.start(backgroundScope)
            b.transport.connect(c.transport)
            advanceUntilIdle()

            assertEquals("c receives the carried DM", listOf(frameIdOf("dm1")), c.delivered)
            assertFalse("the live ack vaccinates b", b.store.has(frameIdOf("dm1")))

            // d meets b much later. The receipt is custodied like any flood frame, so b re-serves it and it
            // purges d's carried copy — the delay-tolerant half work item #16 restored (an uncustodied
            // receipt reached only the carriers online at ack time, and d would re-serve for the full TTL).
            b.transport.connect(d.transport)
            advanceUntilIdle()

            assertFalse("the custodied receipt vaccinates the late carrier d", d.store.has(frameIdOf("dm1")))
        }

    @Test
    fun groupMessageReachesMemberThatConnectsAfterTheFloodViaACarrier() =
        runTest(UnconfinedTestDispatcher()) {
            val members = listOf("a", "b", "c")
            val a = Node("a", backgroundScope)
            val b = Node("b", backgroundScope)
            a.transport.connect(b.transport)
            a.start(backgroundScope)
            b.start(backgroundScope)

            a.send(groupMsg(frameIdOf("g1"), sender = "a", members = members))
            advanceUntilIdle()

            assertTrue("b carries the group message while c is away", b.store.has(frameIdOf("g1")))

            // c appears later, connecting only to b (a is gone).
            val c = Node("c", backgroundScope)
            c.start(backgroundScope)
            b.transport.connect(c.transport)
            advanceUntilIdle()

            assertEquals("c receives the carried group message exactly once", listOf(frameIdOf("g1")), c.delivered)
            assertEquals("and notifies once", listOf(frameIdOf("g1")), c.notified)
            assertTrue("groups aren't vaccine-purged — b keeps carrying it until TTL", b.store.has(frameIdOf("g1")))
        }

    @Test
    fun custodiedImageReachesRecipientThatConnectsAfterTheSenderLeft() =
        runTest(UnconfinedTestDispatcher()) {
            val a = Node("a", backgroundScope) // sender + original blob holder
            val b = Node("b", backgroundScope) // carrier (relays the DM toward c)
            a.transport.connect(b.transport)
            a.start(backgroundScope)
            b.start(backgroundScope)
            val bytes = "an-image-blob".toByteArray()
            a.blobStore.seed("H", "image/jpeg", bytes)

            a.send(dmWithAttachment(frameIdOf("dm1"), sender = "a", recipient = "c", hash = "H"))
            advanceUntilIdle()

            assertTrue("b carries the DM while c is away", b.store.has(frameIdOf("dm1")))
            assertTrue("b eager-pulled + holds the image, so it can serve a late joiner", b.blobStore.has("H"))

            // The sender leaves entirely: a is gone, so the ONLY in-range holder for c is the carrier b. Without
            // blob custody, c's pull would find no source and the image would be lost with the sender.
            a.transport.disconnect(b.transport)

            val c = Node("c", backgroundScope)
            c.start(backgroundScope)
            b.transport.connect(c.transport)
            advanceUntilIdle()

            assertEquals("c receives the carried DM", listOf(frameIdOf("dm1")), c.delivered)
            assertTrue("c pulls the custodied image from the carrier b (the sender is gone)", c.blobStore.has("H"))
            assertArrayEquals("and the bytes are intact", bytes, c.blobStore.fileFor("H")!!.readBytes())
        }

    /**
     * A direct link's view that admits the frames of the senders in [senders], and the profiles of those in [profiles]
     * (ADR 2026-10.a5yj).
     */
    private class SendersView(
        private val senders: Set<String>,
        private val profiles: Set<String> = senders,
    ) : PairView {
        override fun admits(env: RelayEnvelope): Boolean = env.senderId in if (env.type == FrameType.PROFILE) profiles else senders

        override suspend fun admitsBlob(hash: String): Boolean = false
    }

    /**
     * A digest that crossed a contact's direct link is answered inside that link's rule (ADR 2026-10.a5yj): only the
     * frames its view admits go out, and only those are reported served — the peer's digest was filtered by the same
     * rule, so an unfiltered answer pushed everything outside it as a gap.
     */
    @Test
    fun aDigestOffADirectLinkIsAnsweredWithTheFramesTheLinkAdmits() =
        runTest {
            val transport = RecordingTransport()
            val served = mutableListOf<String>()
            val sync = ForwardSync(transport, FakeForwardStore(), clock = { 0L }, onServed = { env, _ -> served += env.id })
            listOf(carried("r1", "a"), carried("r2", "b")).forEach { sync.onSeen(wireOf(it), it, ForwardStore.ORIGIN_RELAY) }
            transport.views["z"] = SendersView(setOf("a"))

            sync.onDigest("z", emptyList(), TransportKind.DirectLink)

            assertEquals(listOf("r1"), transport.sent.map { it.first.frameId() })
            assertEquals(listOf("r1"), served)
        }

    /** The link went between the digest and its answer: no view, nothing sent — never the unfiltered set. */
    @Test
    fun aDigestOffADirectLinkThatIsGoneIsAnsweredWithNothing() =
        runTest {
            val transport = RecordingTransport()
            val sync = ForwardSync(transport, FakeForwardStore(), clock = { 0L })
            post("r1", "a").let { sync.onSeen(wireOf(it), it, ForwardStore.ORIGIN_RELAY) }

            sync.onDigest("z", emptyList(), TransportKind.DirectLink)

            assertTrue(transport.sent.isEmpty())
        }

    /**
     * The plane the digest crossed decides, never which plane holds the peer at reply time: a radio's digest is answered
     * in full even while a direct link to the same peer is up.
     */
    @Test
    fun aRadioDigestIsAnsweredInFullWhateverLinkTheyAlsoHold() =
        runTest {
            val transport = RecordingTransport()
            val sync = ForwardSync(transport, FakeForwardStore(), clock = { 0L })
            listOf(post("r1", "a"), post("r2", "b")).forEach { sync.onSeen(wireOf(it), it, ForwardStore.ORIGIN_RELAY) }
            transport.views["z"] = SendersView(emptySet())

            sync.onDigest("z", emptyList(), TransportKind.Bluetooth)

            assertEquals(setOf("r1", "r2"), transport.sent.map { it.first.frameId() }.toSet())
        }

    /** A key goes over a direct link only for a sender whose profile the link would carry (ADR 2026-09.g64k, a5yj). */
    @Test
    fun aDirectLinksAnswerServesAKeyOnlyForASenderWhoseProfileTheLinkCarries() =
        runTest {
            val k = KeyedSync()
            k.carry(carried("r1", "a"), carried("s1", "b"))
            k.transport.views["z"] = SendersView(senders = setOf("a", "b"), profiles = setOf("a"))

            k.sync.onDigest("z", emptyList(), TransportKind.DirectLink)

            assertEquals(listOf(Triple("a", "z", 0)), k.keys)
            assertEquals(
                setOf("r1", "s1"),
                k.transport.sent
                    .map { it.first.frameId() }
                    .toSet(),
            )
        }

    /** A sync whose store tells [carry] what it holds, as the repository does, and whose peer "z" a direct link carries. */
    private inner class MemoSync(
        var now: Long = 0L,
    ) {
        val ledger = CarryLedger()
        val store = FakeForwardStore(ttlMs = 60 * 60_000L, carry = ledger)
        val transport = RecordingTransport().apply { carriers["z"] = LinkCarrier(TransportKind.DirectLink, Any(), proven = true) }
        val sync = ForwardSync(transport, store, clock = { now }, carry = ledger)

        suspend fun carry(vararg envs: RelayEnvelope) = envs.forEach { sync.onSeen(wireOf(it), it, ForwardStore.ORIGIN_RELAY) }

        fun releases(id: String) = transport.sent.count { it.first.frameId() == id }

        // A digest, then the answer to it left to run out: each digest below is then answered by an answer of its own.
        suspend fun digest(
            ids: List<String>,
            via: TransportKind = TransportKind.DirectLink,
            proven: Boolean = false,
            link: Any? = null,
        ) {
            sync.onDigest("z", ids, via, proven, link)
            sync.settle(now)
        }

        // The link plane adopts a new link to "z": a new link object, then the link-up hook.
        fun relink() {
            transport.carriers["z"] = LinkCarrier(TransportKind.DirectLink, Any(), proven = true)
            sync.linked(listOf("z"))
        }
    }

    /**
     * Two filtered views can differ for good (a full per-sender bucket on one end, the global cap, a frame dead on arrival,
     * a tombstone): a frame released that the peer's next digest still lacks is not released again (#235, the custody-carry-
     * policy contract's §6) — not while it is pending, not when their digest moves, which it does with every frame they
     * originate, nor when ours does. The peer linking again, or the mark lapsing, releases it once more.
     */
    @Test
    fun aDirectLinksPushThePeerDidNotKeepIsNotPushedAgainUntilItLapsesOrTheyLinkAgain() =
        runTest {
            val m = MemoSync()
            m.carry(carried("r1", "a"))
            m.transport.views["z"] = SendersView(setOf("a", "b"))
            val counts = { listOf(m.releases("r1"), m.releases("r2")) }

            m.digest(listOf("x"))
            m.digest(listOf("x"))
            assertEquals("pending: not released again", 1, m.releases("r1"))

            m.now += CarryLedger.JUDGE_AFTER_FAST_MS
            m.digest(listOf("x", "y"))
            assertEquals("judged not kept; their digest moved, the gap stayed: nothing", 1, m.releases("r1"))

            m.carry(carried("r2", "b"))
            m.digest(listOf("x", "y"))
            assertEquals("ours moved: the new frame alone", listOf(1, 1), counts())

            m.relink()
            m.digest(listOf("x", "y"))
            assertEquals("linked again: a release may have died with the old link", listOf(2, 2), counts())
            m.digest(listOf("x", "y"))
            assertEquals(listOf(2, 2), counts())

            m.now += CarryLedger.JUDGE_AFTER_FAST_MS
            m.digest(listOf("x", "y"))
            val marked = m.now
            m.now = marked + CarryLedger.NOT_KEPT_LAPSE_MS - 1
            m.digest(listOf("x", "y", "w"))
            assertEquals("a digest that still lacks it never refreshes the mark", listOf(2, 2), counts())
            m.now = marked + CarryLedger.NOT_KEPT_LAPSE_MS
            m.digest(listOf("x", "y", "w"))
            assertEquals("the mark lapsed: released once more", listOf(3, 3), counts())
            m.digest(listOf("x", "y", "w"))
            assertEquals("and pending again", listOf(3, 3), counts())

            m.digest(listOf("x", "y"), TransportKind.Other)
            assertEquals("a digest whose link proved nothing is answered in full", listOf(4, 4), counts())
        }

    /** Custody counts the neighbour a frame came from among its holders only when that neighbour's link proved it (§3). */
    @Test
    fun aRelayedFramesSourceIsCountedOnlyOffAProvenLink() =
        runTest {
            val m = MemoSync()
            val proven = post("p1", "a")
            val unproven = post("u1", "a")
            val own = post("o1", "a")

            m.sync.onSeen(wireOf(proven), proven, ForwardStore.ORIGIN_RELAY, from = "n", fromProven = true)
            m.sync.onSeen(wireOf(unproven), unproven, ForwardStore.ORIGIN_RELAY, from = "n")
            m.sync.onSeen(wireOf(own), own, ForwardStore.ORIGIN_SELF)

            assertEquals(listOf(1, 0, 0), listOf("p1", "u1", "o1").map(m.ledger::spread))
        }

    /**
     * A digest whose link proved nothing is answered in full and leaves nothing behind (§3, §6): its node id is only a
     * claim. A proven one names its peer a holder of what it lists, and what it is released stays pending.
     */
    @Test
    fun onlyAProvenDigestFeedsTheLedgerAndHoldsBackWhatItWasReleased() =
        runTest {
            val m = MemoSync()
            val link = Any()
            m.transport.carriers["z"] = LinkCarrier(TransportKind.Bluetooth, link, proven = true)
            m.carry(post("r1", "a"), post("r2", "a"))

            repeat(2) { m.digest(listOf("r1"), TransportKind.Bluetooth) }
            assertEquals("unproven: answered in full each time", 2, m.releases("r2"))
            assertEquals("and no holder", 0, m.ledger.spread("r1"))

            repeat(2) { m.digest(listOf("r1"), TransportKind.Bluetooth, proven = true, link = link) }
            assertEquals("proven: pending, not released again", 3, m.releases("r2"))
            assertEquals(1, m.ledger.spread("r1"))
        }

    /** A recipient's own mail is never withheld on a memo's word (§6): it goes at every digest that lacks it. */
    @Test
    fun aFrameAddressedToThePeerIsReleasedAtEveryDigestThatLacksIt() =
        runTest {
            val m = MemoSync()
            val link = Any()
            m.transport.carriers["z"] = LinkCarrier(TransportKind.Bluetooth, link, proven = true)
            m.carry(dm("d1", "a", "z"), post("r1", "a"))

            repeat(2) {
                m.now += CarryLedger.JUDGE_AFTER_BLUETOOTH_MS
                m.digest(emptyList(), TransportKind.Bluetooth, proven = true, link = link)
            }

            assertEquals(listOf(2, 1), listOf(m.releases("d1"), m.releases("r1")))
        }

    /** A frame the peer kept shows in its digest, so it leaves the diff: nothing is marked and nothing held back. */
    @Test
    fun aDirectLinksPushThePeerKeptIsNeverMarked() =
        runTest {
            val m = MemoSync()
            m.carry(carried("r1", "a"))
            m.transport.views["z"] = SendersView(setOf("a"))

            m.digest(emptyList())
            m.now += CarryLedger.JUDGE_AFTER_FAST_MS
            m.digest(listOf("r1"))
            m.carry(carried("r2", "a"))
            m.digest(listOf("r1"))
            assertEquals(listOf("r1", "r2"), m.transport.sent.map { it.first.frameId() })

            // The peer kept r2 and has since evicted r1: r1 was judged kept and never marked, so it goes again at once.
            m.now += CarryLedger.JUDGE_AFTER_FAST_MS
            m.digest(listOf("r2"))
            assertEquals(listOf("r1", "r2", "r1"), m.transport.sent.map { it.first.frameId() })
        }

    /** An id our custody no longer holds leaves the memo: carried again later, it is released as a frame never released. */
    @Test
    fun aDirectLinksMemoForgetsAFrameWeNoLongerHold() =
        runTest {
            val m = MemoSync()
            val r1 = carried("r1", "a")
            m.carry(r1)
            m.transport.views["z"] = SendersView(setOf("a"))

            m.digest(emptyList())
            m.digest(emptyList())
            assertEquals(1, m.releases("r1"))

            m.store.remove("r1")
            m.digest(emptyList())
            m.carry(r1)
            m.digest(emptyList())
            assertEquals("carried again, after it left custody", 2, m.releases("r1"))
        }

    /**
     * A key goes out over a direct link only for the frames this round pushes (ADR 2026-09.g64k): a sender whose frames
     * the peer was pushed and did not keep is not keyed again with the next sender's.
     */
    @Test
    fun aDirectLinksKeyServeCoversOnlyTheFramesItStillPushes() =
        runTest {
            val k = KeyedSync()
            k.carry(carried("r1", "a"))
            k.transport.views["z"] = SendersView(senders = setOf("a", "b"), profiles = setOf("a", "b"))

            k.digest(emptyList())
            k.digest(emptyList())
            assertEquals("nothing left to push: no key either", listOf(Triple("a", "z", 0)), k.keys)

            k.carry(carried("s1", "b"))
            k.digest(emptyList())

            assertEquals(listOf(Triple("a", "z", 0), Triple("b", "z", 1)), k.keys)
            assertEquals(listOf("r1", "s1"), k.transport.sent.map { it.first.frameId() })
        }

    // --- paced rounds and the serve order (the custody-carry-policy contract's §1, §2) ---

    /** A chat frame of [bytes] payload bytes, sent at [sentAt]: what a round's budget is charged by its encoded length. */
    private fun chat(
        id: String,
        sender: String,
        sentAt: Long,
        recipient: String? = null,
        members: List<String>? = null,
        bytes: Int = 0,
    ) = RelayEnvelope(
        type = FrameType.CHAT,
        id = id,
        senderId = sender,
        sentAt = sentAt,
        recipientId = recipient,
        group = members?.let { GroupInfo(id = "g-$id", members = it, createdBy = it.first()) },
        payload = ByteArray(bytes),
    )

    /**
     * A sync over a store that tells its ledger what it holds, a peer "z" a Bluetooth link carries (and holds as a radio
     * neighbour) and counts what it holds for, and every sender's key held unless [keys] is false. [pace] defaults to the
     * contract's table. The link never drains by itself: a case drains it ([drain]) and looks ([look]).
     */
    private inner class Rounds(
        var now: Long = 0L,
        pace: CustodyPace = CustodyPace.CONTRACT,
        keys: Boolean = false,
        val metrics: MeshMetrics = MeshMetrics(),
    ) {
        val ledger = CarryLedger(metrics = metrics)
        val store = FakeForwardStore(ttlMs = DAY_MS, carry = ledger)
        val link = Any()
        val transport =
            RecordingTransport().apply {
                carriers["z"] = LinkCarrier(TransportKind.Bluetooth, link, proven = true)
                links.value = setOf(Peer("z"))
                outbound["z"] = Outbound()
            }

        // "z"'s link as it counts what it holds.
        val out get() = transport.outbound.getValue("z")

        // "z"'s link wrote everything it held.
        fun drain() {
            out.unwritten = 0L
        }

        // One look of the serve loop at [at].
        suspend fun look(at: Long = now) {
            now = at
            sync.serveDue(at)
        }

        val keysSent = mutableListOf<String>()
        val sync =
            ForwardSync(
                transport,
                store,
                clock = { now },
                keyFor = { sender -> if (keys) wireOf(profile("key-$sender", sender)) else null },
                sendKey = { key, _ -> keysSent += WireCodec.decodeEnvelope(key.signed)!!.senderId },
                carry = ledger,
                pace = pace,
                metrics = metrics,
            )

        suspend fun carry(vararg envs: RelayEnvelope) = envs.forEach { sync.onSeen(wireOf(it), it, ForwardStore.ORIGIN_RELAY) }

        fun sent() = transport.sent.map { it.first.frameId() }

        fun cost(env: RelayEnvelope) = WireCodec.encodeWire(wireOf(env)).size
    }

    /**
     * The four tiers in order (§1): profiles first, newest received first; then what is addressed to the peer and then the
     * group frames whose own roster lists it, each oldest first by `(sentAt, id)`; then everything else, newest received
     * first. Each profile and rest pair arrives in the reverse of both its `sentAt` order and its id order, so only the
     * arrival order puts the one that came last first.
     */
    @Test
    fun aRoundServesProfilesThenThePeersOwnMailThenItsGroupsThenTheRest() =
        runTest {
            val r = Rounds()
            r.carry(
                chat("rest-a", "a", sentAt = 60),
                profile("p-a", sentAt = 70),
                chat("grp-new", "a", sentAt = 50, members = listOf("a", "z")),
                chat("dm-new", "a", sentAt = 40, recipient = "z"),
                chat("grp-old", "a", sentAt = 20, members = listOf("a", "z")),
                chat("dm-b", "a", sentAt = 30, recipient = "z"),
                chat("dm-a", "a", sentAt = 30, recipient = "z"),
                profile("p-b", sentAt = 5),
                chat("rest-b", "a", sentAt = 10),
            )

            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)

            assertEquals(
                listOf("p-b", "p-a", "dm-a", "dm-b", "dm-new", "grp-old", "grp-new", "rest-b", "rest-a"),
                r.sent(),
            )
        }

    /**
     * The oldest DM for the peer goes right after the profiles and the keys, ahead of a store full of newer mail for others
     * (#352): a sidewalk pass is over in seconds, and the peer's own mail used to wait behind everyone else's.
     */
    @Test
    fun theOldestDmForThePeerGoesRightAfterTheProfilesAndKeys() =
        runTest {
            val r = Rounds(keys = true)
            r.carry(chat("mine", "s", sentAt = 1, recipient = "z"))
            (1..50).forEach { r.carry(chat("other-$it", "o$it", sentAt = 100L + it, recipient = "q", bytes = 1_000)) }
            r.carry(profile("p1", "o1"))

            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)

            assertEquals(listOf("p1", "mine"), r.sent().take(2))
            assertEquals("the DM's sender's key went ahead of the round", "s", r.keysSent.first())
        }

    /** Tier 3 reads the frame's own roster, for a group this phone is not in: a carrier needs no group row for it. */
    @Test
    fun theGroupTierReadsTheRosterOfAGroupThatIsNotOurs() =
        runTest {
            val r = Rounds()
            r.carry(chat("room", "a", sentAt = 99), chat("theirs", "x", sentAt = 1, members = listOf("x", "y", "z")))

            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)

            assertEquals(listOf("theirs", "room"), r.sent())
        }

    /** A roster longer than eight, which no honest build makes, is not remembered: its frame orders with the rest. */
    @Test
    fun aNineMemberRosterOrdersInTheLastTier() =
        runTest {
            val r = Rounds()
            val nine = listOf("z") + (1..8).map { "m$it" }
            r.carry(chat("nine", "m1", sentAt = 1, members = nine), chat("room", "a", sentAt = 99))

            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)

            assertEquals(listOf("room", "nine"), r.sent())
        }

    /**
     * A row whose roster was never read (custody held it before a restart emptied the ledger) orders with the rest, and
     * only the rebuild off the request path, which loads the group rows alone, moves it up.
     */
    @Test
    fun anUnreadRosterOrdersInTheLastTierUntilTheRebuildReadsIt() =
        runTest {
            val r = Rounds()
            val group = chat("grp", "x", sentAt = 1, members = listOf("x", "z"))
            r.store.store(CarriedFrame(group, ByteArray(0), WireCodec.encodeEnvelope(group)), ForwardStore.ORIGIN_RELAY, 0L)
            r.carry(chat("room", "a", sentAt = 99))

            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)
            assertEquals(listOf("room", "grp"), r.sent())

            r.sync.settle(r.now)
            r.sync.readRosters(r.now)
            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)
            assertEquals(listOf("room", "grp", "grp", "room"), r.sent())
        }

    /** A store that counts what is decoded: the index is free, every frame loaded is counted by id. */
    private class CountingStore(
        private val inner: FakeForwardStore,
    ) : ForwardStore by inner {
        var wholeReads = 0
        val loaded = mutableListOf<String>()

        override suspend fun liveIndex(now: Long): List<IndexedFrame> = inner.liveIndex(now)

        override suspend fun liveFrames(now: Long): List<CarriedFrame> = inner.liveFrames(now).also { wholeReads++ }

        override suspend fun liveFramesByIds(
            ids: Collection<String>,
            now: Long,
        ): List<CarriedFrame> = inner.liveFramesByIds(ids, now).also { loaded += ids }
    }

    /** A round reads the index and loads only what it sends: an empty digest costs no decode of the whole store. */
    @Test
    fun aRoundLoadsNoRowItDoesNotSend() =
        runTest {
            val store = CountingStore(FakeForwardStore(ttlMs = DAY_MS))
            val transport = RecordingTransport()
            val sync = ForwardSync(transport, store, clock = { 0L })
            (1..10).forEach {
                val e = chat("r$it", "a", sentAt = it.toLong(), bytes = 5_000)
                sync.onSeen(wireOf(e), e, ForwardStore.ORIGIN_RELAY)
            }

            sync.onDigest("z", emptyList())

            val sent = transport.sent.map { it.first.frameId() }
            assertEquals("a Bluetooth round of 16 KiB carries three of the ten", 3, sent.size)
            assertEquals(sent.toSet(), store.loaded.toSet())
            assertEquals("no read of every row", 0, store.wholeReads)
        }

    /** The first pick always goes, whatever its size; the round stops at the first later pick past the budget, skipping none. */
    @Test
    fun aRoundSendsItsFirstPickWhateverItsSizeAndStopsNeverSkips() =
        runTest {
            val r = Rounds()
            r.carry(
                chat("big", "a", sentAt = 3, recipient = "z", bytes = 20_000),
                chat("next", "a", sentAt = 4, recipient = "z", bytes = 1_000),
                chat("small", "a", sentAt = 1),
            )

            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)
            assertEquals("past the budget alone, and the next waits", listOf("big"), r.sent())
            r.look(60_000L)
            assertEquals("a first pick over the budget holds the next round while the link holds it", listOf("big"), r.sent())

            r.drain()
            r.look()
            assertEquals(listOf("big", "next", "small"), r.sent())
        }

    // --- release on drain (§2, revisions 13 and 14) ---

    /**
     * A link under one round unwritten gets its next round at once, with no wait on the clock; one that holds a round waits
     * for its drain, and its drain signal wakes the loop at the same instant ([ForwardSync.onDrained]) while a drain for a
     * peer custody isn't answering wakes nothing.
     */
    @Test
    fun aLinkThatDrainedGetsItsNextRoundAtOnce() =
        runTest {
            val r = Rounds()
            r.carry(*(1..4).map { chat("r$it", "a", sentAt = it.toLong(), bytes = 10_000) }.toTypedArray())

            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)
            assertEquals("one 10 KB frame a 16 KiB round", 1, r.sent().size)
            assertEquals("the link holds under a round: the next look is now", r.now, r.sync.nextDueAt())
            r.look()
            assertEquals(2, r.sent().size)

            r.look()
            assertEquals("holding a round: no third", 2, r.sent().size)
            r.sync.awaitWake(1L) // the digest's wake
            val asleep = testScheduler.currentTime
            r.sync.onDrained("y")
            r.sync.awaitWake(10_000L)
            assertEquals("another peer's drain wakes nothing", asleep + 10_000L, testScheduler.currentTime)
            r.drain()
            r.sync.onDrained("z")
            r.sync.awaitWake(10_000L)
            assertEquals("z's drain wakes the loop at once", asleep + 10_000L, testScheduler.currentTime)
            r.look()
            assertEquals("the round followed the drain on the same instant", 3, r.sent().size)
        }

    /**
     * A link that never drains gets no second round however long it stays up (§2), and its looks read only the link, at
     * the fallback beside its drain signal, never custody's index. Its answer still ends by age, cut short, its first
     * round's credit is dropped uncredited at the same age, and no look outlives them.
     */
    @Test
    fun aLinkThatNeverDrainsGetsOneRoundAndItsAnswerStillEndsByAge() =
        runTest {
            val r = Rounds()
            r.carry(chat("r1", "a", sentAt = 1, bytes = 10_000), chat("r2", "a", sentAt = 2, bytes = 10_000))
            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth, proven = true, link = r.link)
            r.out.unwritten = 1_000_000L
            val reads = r.store.indexReads

            while (r.now < ForwardSync.ANSWER_MAX_AGE_MS - CustodyPace.SIGNAL_FALLBACK_MS) {
                r.look(r.sync.nextDueAt()!!)
            }
            assertEquals("no second round", 1, r.sent().size)
            assertEquals("no look read the index", reads, r.store.indexReads)
            assertEquals(1, r.sync.creditsWaiting())

            r.look(ForwardSync.ANSWER_MAX_AGE_MS)
            assertEquals(null, r.sync.answerSizes("z"))
            assertEquals(1L, r.metrics.frames().custodyAnswersCutShort)
            assertEquals("dropped uncredited", 0, r.sync.creditsWaiting())
            assertEquals(0, r.ledger.spread("r1") + r.ledger.spread("r2"))
            assertEquals("no look outlives the answer", null, r.sync.nextDueAt())
        }

    /**
     * A link is never handed a round beyond what it can take: across looks as often as the loop likes, with the link
     * draining a little between them, what it holds unwritten never reaches two rounds.
     */
    @Test
    fun aLinkIsNeverHandedTwoRoundsBeyondWhatItHeld() =
        runTest {
            val r = Rounds()
            r.carry(*(1..60).map { chat("r$it", "a", sentAt = it.toLong(), bytes = 3_000) }.toTypedArray())
            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)
            var most = r.out.unwritten
            repeat(200) {
                r.look()
                r.look()
                most = maxOf(most, r.out.unwritten)
                r.out.unwritten = maxOf(0L, r.out.unwritten - 2_500L)
            }
            assertEquals("every frame went, once", 60, r.sent().toSet().size)
            assertEquals(60, r.sent().size)
            assertTrue("held $most", most < 2L * CustodyPace.BLUETOOTH_ROUND_BYTES)
        }

    /** A newer digest replaces the one the rounds are cut from, and cuts no round of its own: the link's drain cuts it. */
    @Test
    fun aNewerDigestReplacesTheDigestAndCutsNoRoundOfItsOwn() =
        runTest {
            val r = Rounds()
            r.carry(chat("big", "a", sentAt = 1, recipient = "z", bytes = 20_000), chat("held", "a", sentAt = 2, recipient = "z"))
            r.carry(chat("other", "a", sentAt = 3, recipient = "z"))

            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)
            r.now = 1_000L
            r.sync.onDigest("z", listOf("held"), TransportKind.Bluetooth)
            assertEquals("nothing while the link holds the first round", listOf("big"), r.sent())
            r.look()
            assertEquals(listOf("big"), r.sent())

            r.drain()
            r.look()
            assertEquals("cut from the newer digest", listOf("big", "other"), r.sent())
        }

    /**
     * A link that can't say what it holds ([LinkCarrier.outbound] null) gets the first round of each digest and no other
     * (§2), however long it stays up, and its hand-offs are never credited: nothing says the round left.
     */
    @Test
    fun aLinkThatCantSayGetsOneRoundADigest() =
        runTest {
            val r = Rounds()
            r.transport.outbound.remove("z")
            r.carry(*(1..4).map { chat("r$it", "a", sentAt = it.toLong(), bytes = 10_000) }.toTypedArray())

            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth, proven = true, link = r.link)
            repeat(30) { r.look(r.sync.nextDueAt()!!) }
            assertEquals(1, r.sent().size)

            r.sync.onDigest("z", r.sent(), TransportKind.Bluetooth, proven = true, link = r.link)
            assertEquals("a newer digest cuts no round of its own", 1, r.sent().size)
            r.look()
            r.look()
            assertEquals("its one round, at the look after it", 2, r.sent().size)
            assertEquals("never credited", 0, r.ledger.spread(r.sent().last()))
        }

    /**
     * A link that can't say is looked at only for its endings after its digest's round: its looks read no index while it
     * lives, however much is left, and the look at its age reads it once, to end it cut short.
     */
    @Test
    fun aLinkThatCantSayReadsNoIndexAfterItsRound() =
        runTest {
            val r = Rounds()
            r.transport.outbound.remove("z")
            r.carry(*(1..4).map { chat("r$it", "a", sentAt = it.toLong(), bytes = 10_000) }.toTypedArray())
            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)
            val reads = r.store.indexReads

            while (r.sync.nextDueAt()!! < ForwardSync.ANSWER_MAX_AGE_MS) r.look(r.sync.nextDueAt()!!)
            assertEquals("no look read the index", reads, r.store.indexReads)
            assertEquals(CustodyPace.SIGNAL_FALLBACK_MS, r.sync.nextDueAt()!! - r.now)

            r.look(ForwardSync.ANSWER_MAX_AGE_MS)
            assertEquals(null, r.sync.answerSizes("z"))
            assertEquals(1L, r.metrics.frames().custodyAnswersCutShort)
            assertEquals(1, r.store.indexReads - reads)
        }

    /**
     * A round whose Bluetooth link ended while Wi-Fi Aware still holds the peer is owed again at once over Aware (§6: what
     * the link carried may have died in its queue), though the answer lives on and no new link replaced the old on its
     * own plane.
     */
    @Test
    fun aRoundWhoseBluetoothLinkEndedUnderAwareIsReleasedAgainOverAware() =
        runTest {
            val r = Rounds()
            r.carry(chat("x", "a", sentAt = 1, bytes = 20_000))
            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth, proven = true, link = r.link)
            assertEquals(listOf("x"), r.sent())

            r.transport.carriers["z"] = LinkCarrier(TransportKind.WifiAware, Any(), proven = true)
            r.drain()
            r.look(1_000L)
            assertEquals("released again over Aware", listOf("x", "x"), r.sent())
            assertEquals(0, r.ledger.spread("x"))
        }

    /**
     * A Wi-Fi Aware path cut with the answer live (the plane's `answerCut`) while its round still waited below it: the round
     * is owed again over the next path before any digest, and is never pending, so a digest past the judging delay that
     * lacks it marks nothing not kept. A path that closed at quiescence would leave it pending for that digest instead.
     */
    @Test
    fun anAwarePathCutWithARoundBelowItOwesTheRoundAgainAndMarksNothing() =
        runTest {
            val r = Rounds()
            r.transport.carriers["z"] = LinkCarrier(TransportKind.WifiAware, Any(), proven = true)
            // Arrival order: x, received last, is the round's first pick, and the big frame waits past the budget.
            r.carry(chat("big", "a", sentAt = 0, bytes = 600_000), chat("x", "a", sentAt = 1))
            r.sync.onDigest("z", emptyList(), TransportKind.WifiAware, proven = true)
            assertEquals(listOf("x"), r.sent().take(1))
            r.out.unwritten = 2_000_000L
            r.look(500L)
            assertEquals("the round waits below the path", 1, r.sync.creditsWaiting())

            r.ledger.answerCut("z", TransportKind.WifiAware)
            r.transport.carriers["z"] = LinkCarrier(TransportKind.WifiAware, Any(), proven = true)
            r.drain()
            r.look(1_000L)
            assertEquals("sent again over the new path, before any digest", 2, r.sent().count { it == "x" })

            r.out.unwritten = 2_000_000L
            r.now = 1_000L + CarryLedger.JUDGE_AFTER_FAST_MS
            r.sync.onDigest("z", emptyList(), TransportKind.WifiAware, proven = true)
            assertEquals("nothing marked not kept", 0L, r.metrics.frames().custodyNotKept)
        }

    /**
     * A round still below a link that stays full [ForwardSync.ANSWER_MAX_AGE_MS] after its cut is dropped uncredited and
     * written then: the answer, kept alive by digests, does not queue it again behind itself, and the first digest past the
     * judging delay from then that still lacks it owes it again.
     */
    @Test
    fun aRoundAgedOnAFullLinkIsWrittenAtItsDropAndJudgedFromThere() =
        runTest {
            val r = Rounds()
            r.carry(chat("x", "a", sentAt = 1, bytes = 20_000), chat("y", "a", sentAt = 0, bytes = 20_000))
            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)
            r.out.unwritten = 1_000_000L
            r.now = 100_000L
            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)
            r.look(ForwardSync.ANSWER_MAX_AGE_MS)
            assertEquals("dropped uncredited", 0, r.sync.creditsWaiting())

            r.drain()
            r.look(ForwardSync.ANSWER_MAX_AGE_MS + 1L)
            assertEquals("not queued again behind itself", listOf("y", "x"), r.sent())
            r.out.unwritten = 1_000_000L
            r.now = ForwardSync.ANSWER_MAX_AGE_MS + CarryLedger.JUDGE_AFTER_BLUETOOTH_MS
            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)
            r.drain()
            r.look()
            assertEquals("judged from its drop: owed again", listOf("y", "x", "y"), r.sent())
        }

    /**
     * A hand-off is credited at the first look after its round that finds its own link under one round unwritten (§3): not
     * while the link holds the round, and never once the link dropped first; a link that proved nothing adds no holder.
     */
    @Test
    fun aHandOffIsCreditedAtTheDrainedLookAndNeverWhenItsLinkDroppedFirst() =
        runTest {
            val r = Rounds()
            r.carry(chat("r1", "a", sentAt = 1))
            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth, proven = true, link = r.link)
            r.out.unwritten = 20_000L
            r.look(5_000L)
            assertEquals("the link still holds it", 0, r.ledger.spread("r1"))
            r.drain()
            r.look()
            assertEquals("credited: one holder", 1, r.ledger.spread("r1"))

            val cut = Rounds()
            cut.carry(chat("r1", "a", sentAt = 1))
            cut.sync.onDigest("z", emptyList(), TransportKind.Bluetooth, proven = true, link = cut.link)
            cut.transport.carriers["z"] = LinkCarrier(TransportKind.Bluetooth, Any(), proven = true)
            cut.drain()
            cut.look()
            assertEquals("the link dropped first: nothing credited", 0, cut.ledger.spread("r1"))
            assertEquals("what went over the old link went again over the new, and waits for its own", 1, cut.sync.creditsWaiting())
            cut.look()
            assertEquals("credited on the new link", 1, cut.ledger.spread("r1"))

            val unproven = Rounds()
            unproven.carry(chat("r1", "a", sentAt = 1))
            unproven.sync.onDigest("z", emptyList(), TransportKind.Bluetooth, link = unproven.link)
            unproven.look()
            assertEquals("an unproven link adds no holder", 0, unproven.ledger.spread("r1"))
        }

    /**
     * The last round's credit settles after its answer ended (§3): an answer that aged out with a round still below its
     * link is credited at the drain that comes after; on a link that never drains the credit is dropped
     * [ForwardSync.ANSWER_MAX_AGE_MS] after its round, and no look outlives it.
     */
    @Test
    fun theLastRoundsCreditSettlesAfterItsAnswerEnded() =
        runTest {
            for (drains in listOf(true, false)) {
                val r = Rounds()
                r.carry(chat("r1", "a", sentAt = 1))
                r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth, proven = true, link = r.link)
                r.out.unwritten = 20_000L // the answer lives on, its link full
                r.now = 110_000L
                r.carry(chat("r2", "a", sentAt = 110_000L))
                r.drain()
                r.look()
                assertEquals(listOf("r1", "r2"), r.sent())
                r.out.unwritten = 20_000L
                r.look(ForwardSync.ANSWER_MAX_AGE_MS)
                assertEquals("aged out", null, r.sync.answerSizes("z"))
                assertEquals("its credit waits", 1, r.sync.creditsWaiting())

                if (drains) r.drain()
                while (r.sync.nextDueAt() != null) r.look(r.sync.nextDueAt()!!)
                assertEquals("drains: $drains", if (drains) 1 else 0, r.ledger.spread("r2"))
                assertTrue("dropped by ${r.now}", drains || r.now <= 110_000L + ForwardSync.ANSWER_MAX_AGE_MS)
            }
        }

    // --- the estimate (§2, revision 14) ---

    /**
     * Where the plane estimates the buffer below its link, the next look is at the time the estimate gives, (unwritten −
     * round) at the link's measured rate: twice the rate, half the wait; no rate, the fallback beside its signal.
     */
    @Test
    fun aMeasuredRateTimesTheNextLookInProportion() =
        runTest {
            for ((rate, wait) in listOf(16_384L to 501L, 32_768L to 251L, null to CustodyPace.SIGNAL_FALLBACK_MS)) {
                val r = Rounds()
                r.carry(chat("r1", "a", sentAt = 1, bytes = 20_000), chat("r2", "a", sentAt = 2))
                r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)
                r.out.unwritten = CustodyPace.BLUETOOTH_ROUND_BYTES + 8_192L
                r.out.rate = rate
                r.look(1_000L)
                assertEquals("at $rate B/s", 1_000L + wait, r.sync.nextDueAt())
            }
        }

    /**
     * With a measured rate the buffer below is never handed more than two rounds: a plane that counts what it handed down
     * less what its rate has sent since, looked at only when the estimate says, holds under two rounds at every look while
     * the answer runs, and the answer goes as fast as the rate.
     */
    @Test
    fun withAMeasuredRateTheBufferNeverHoldsTwoRounds() =
        runTest {
            val r = Rounds()
            val rate = 4_096L
            r.out.rate = rate
            r.carry(*(1..40).map { chat("r$it", "a", sentAt = it.toLong(), bytes = 3_000) }.toTypedArray())
            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)
            var most = r.out.unwritten
            while (r.sync.nextDueAt() != null) {
                val next = r.sync.nextDueAt()!!
                r.out.unwritten = maxOf(0L, r.out.unwritten - (next - r.now) * rate / 1_000L)
                r.look(next)
                most = maxOf(most, r.out.unwritten)
            }
            assertEquals(40, r.sent().size)
            assertTrue("held $most", most < 2L * CustodyPace.BLUETOOTH_ROUND_BYTES)
            val bytes = 40L * r.cost(chat("r1", "a", sentAt = 1, bytes = 3_000))
            assertTrue("done in ${r.now} ms", r.now <= (bytes - CustodyPace.BLUETOOTH_ROUND_BYTES) * 1_000L / rate + 2_000L)
        }

    // --- a file comes first (§2, revisions 14 and 16) ---

    /**
     * While a file moves on the peer's link (either way; the plane says, and a stalled one does not count), a round carries
     * only frames addressed to the peer, with their keys; with none left the answer waits for the file reading only the
     * link, and the other tiers go once it stops. A file moving again holds them again.
     */
    @Test
    fun whileAFileMovesARoundCarriesOnlyThePeersOwnMailAndItsKeys() =
        runTest {
            val r = Rounds(keys = true)
            r.carry(
                profile("p", "o", sentAt = 1),
                chat("grp", "g", sentAt = 2, members = listOf("g", "z")),
                chat("rest", "q", sentAt = 3),
                chat("dm", "s", sentAt = 4, recipient = "z"),
                chat("rest2", "q2", sentAt = 5),
            )
            r.out.fileMoving = true

            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)
            assertEquals(listOf("dm"), r.sent())
            assertEquals("its key went ahead", listOf("s"), r.keysSent)
            r.drain()
            r.look()
            val reads = r.store.indexReads
            repeat(5) { r.look(r.sync.nextDueAt()!!) }
            assertEquals("the rest wait for the file", listOf("dm"), r.sent())
            assertEquals("and the looks read only the link", reads, r.store.indexReads)
            assertEquals(CustodyPace.ROUND_CHECK_MS, r.sync.nextDueAt()!! - r.now)

            r.out.fileMoving = false
            r.look(r.sync.nextDueAt()!!)
            assertEquals(listOf("dm", "p", "grp", "rest2", "rest"), r.sent())

            r.carry(chat("later", "q3", sentAt = 6))
            r.out.fileMoving = true
            r.drain()
            r.look()
            assertEquals("a file moving again holds the rest again", 5, r.sent().size)
            r.out.fileMoving = false
            r.look(r.sync.nextDueAt()!!)
            assertEquals("later", r.sent().last())
        }

    /** A frame addressed to the peer that custody keeps while a file holds the answer goes beside the file. */
    @Test
    fun aFrameAddressedToThePeerIsNotHeldByTheFile() =
        runTest {
            val r = Rounds()
            r.carry(chat("rest", "q", sentAt = 1))
            r.out.fileMoving = true
            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)
            r.look(r.sync.nextDueAt()!!)
            assertEquals(emptyList<String>(), r.sent())

            r.carry(chat("dm", "s", sentAt = 2, recipient = "z"))
            r.look(r.sync.nextDueAt()!!)
            assertEquals(listOf("dm"), r.sent())
            r.out.fileMoving = false
            r.drain()
            r.look()
            assertEquals(listOf("dm", "rest"), r.sent())
        }

    /** An answer ends when nothing is left: no round is due after its last. */
    @Test
    fun anAnswerEndsWhenNothingIsLeft() =
        runTest {
            val r = Rounds()
            r.carry(chat("r1", "a", sentAt = 1))

            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)
            r.look()

            assertEquals(null, r.sync.answerSizes("z"))
            assertEquals("and its credit settled in the same look", null, r.sync.nextDueAt())
            assertEquals("nothing was left", 0L, r.metrics.frames().custodyAnswersCutShort)
        }

    /**
     * A radio answer ends once no radio holds the peer, and counts as cut short with something left to release: judged
     * before the link's count, so a link holding a round still ends it.
     */
    @Test
    fun aRadioAnswerEndsWhenNoRadioHoldsThePeer() =
        runTest {
            val r = Rounds()
            r.carry(chat("left", "a", sentAt = 0), chat("big", "a", sentAt = 1, bytes = 20_000))

            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)
            r.transport.links.value = emptySet()
            r.look()

            assertEquals(listOf("big"), r.sent())
            assertEquals(null, r.sync.answerSizes("z"))
            assertEquals(1L, r.metrics.frames().custodyAnswersCutShort)
        }

    /** A direct link's answer ends with its view, though a radio still holds the peer. */
    @Test
    fun aDirectLinksAnswerEndsWithItsView() =
        runTest {
            val r = Rounds()
            r.transport.views["z"] = SendersView(setOf("a"))
            r.carry(chat("big", "a", sentAt = 1, recipient = "q", bytes = 300_000), carried("left", "a"))

            r.sync.onDigest("z", emptyList(), TransportKind.DirectLink)
            r.transport.views.remove("z")
            r.look(60_000L)

            assertEquals(1, r.sent().size)
            assertEquals(null, r.sync.answerSizes("z"))
            assertEquals(1L, r.metrics.frames().custodyAnswersCutShort)
        }

    /** An answer ends [ForwardSync.ANSWER_MAX_AGE_MS] after its latest digest: what is left waits for the next one. */
    @Test
    fun anAnswerEndsAtItsAgeAfterItsLatestDigest() =
        runTest {
            // One frame a round, the link draining every 50 s.
            val slow = RoundPlan(bytes = 100)
            val r = Rounds(pace = CustodyPace(slow, slow, slow))
            r.carry(chat("r1", "a", sentAt = 1), chat("r2", "a", sentAt = 2), chat("r3", "a", sentAt = 3), chat("r4", "a", sentAt = 4))

            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)
            listOf(50_000L, 100_000L).forEach {
                r.drain()
                r.look(it)
            }
            assertEquals(3, r.sent().size)
            r.drain()
            r.look(150_000L)

            assertEquals("aged out with one left", 3, r.sent().size)
            assertEquals(null, r.sync.answerSizes("z"))
            assertEquals(1L, r.metrics.frames().custodyAnswersCutShort)
        }

    /**
     * What a plane reads to hold a link not idle while custody answers its peer over it (`CustodyProgress.answerCarrier`,
     * the Wi-Fi Aware hold): the carrying plane from the digest that opens the answer, moved by each round's carrier, until
     * a round finds the answer over, and nothing after a session reset.
     */
    @Test
    fun anAnswersCarrierIsReadByThePlanesFromItsDigestUntilItEnds() =
        runTest {
            val r = Rounds()
            r.carry(chat("big", "a", sentAt = 1, bytes = 20_000), chat("left", "a", sentAt = 0))
            assertNull("no digest, no answer", r.ledger.answerCarrier("z"))

            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)
            assertEquals("one round out over Bluetooth, one left", TransportKind.Bluetooth, r.ledger.answerCarrier("z"))
            r.transport.carriers["z"] = LinkCarrier(TransportKind.WifiAware, r.link) // Bluetooth dropped; an Aware path holds it
            r.drain()
            r.out.unwritten = CustodyPace.BLUETOOTH_ROUND_BYTES.toLong() // over Bluetooth's line, under Aware's
            r.look()
            assertEquals(
                "the last round went over Aware; the answer ends at the next cut",
                TransportKind.WifiAware,
                r.ledger.answerCarrier("z"),
            )
            r.look()
            assertNull("nothing left", r.ledger.answerCarrier("z"))

            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)
            assertEquals(TransportKind.WifiAware, r.ledger.answerCarrier("z"))
            r.sync.resetSession()
            assertNull("a mesh start ends every answer", r.ledger.answerCarrier("z"))
        }

    /** The carrying plane picks the budget: Bluetooth's whenever Bluetooth holds the peer, whatever the digest crossed. */
    @Test
    fun theCarryingPlanePicksTheRoundsBudget() =
        runTest {
            val frames = (1..3).map { chat("r$it", "a", sentAt = it.toLong(), bytes = 10_000) }
            val overBluetooth = Rounds().apply { carry(*frames.toTypedArray()) }
            overBluetooth.sync.onDigest("z", emptyList(), TransportKind.WifiAware)
            assertEquals("an Aware digest carried by Bluetooth: one round of 16 KiB", 1, overBluetooth.sent().size)

            val overAware =
                Rounds().apply {
                    carry(*frames.toTypedArray())
                    transport.carriers["z"] = LinkCarrier(TransportKind.WifiAware, Any(), proven = true)
                }
            overAware.sync.onDigest("z", emptyList(), TransportKind.WifiAware)
            assertEquals("carried by Aware: one round of 512 KiB", 3, overAware.sent().size)
        }

    /** A key goes to a peer at most once per answer: its digest never shows a key, so a later round could not tell. */
    @Test
    fun aKeyGoesOncePerAnswerAndIsChargedToItsRound() =
        runTest {
            val r = Rounds(keys = true)
            r.carry(chat("big", "s", sentAt = 1, recipient = "z", bytes = 20_000), chat("then", "s", sentAt = 2, recipient = "z"))

            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)
            r.drain()
            r.look()

            assertEquals(listOf("big", "then"), r.sent())
            assertEquals(listOf("s"), r.keysSent)
        }

    /** A round that releases a frame whose sender's key the peer may lack, with none held, says so (§9's late arrival). */
    @Test
    fun aFrameReleasedWithoutItsSendersKeyIsCounted() =
        runTest {
            val r = Rounds(keys = false)
            r.carry(chat("r1", "s", sentAt = 1), chat("r2", "s", sentAt = 2), profile("p1", "t"), chat("t1", "t", sentAt = 3))

            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)

            assertEquals("the profile's sender is keyed by its profile", 2L, r.metrics.frames().custodyKeylessReleased)
        }

    /** An answer keeps of a digest only the ids custody holds: a hundred thousand junk ids leave nothing behind. */
    @Test
    fun aDigestOfAHundredThousandJunkIdsKeepsNoneInTheAnswer() =
        runTest {
            val r = Rounds()
            r.carry(chat("r1", "a", sentAt = 1), chat("r2", "a", sentAt = 2))

            r.sync.onDigest("z", (1..100_000).map { "junk-$it" } + "r1", TransportKind.Bluetooth)

            assertEquals("of the digest, the one id custody holds", 1, r.sync.answerSizes("z")!!.first())
            assertEquals("r2 went, r1 the peer holds", listOf("r2"), r.sent())
        }

    /** A frame that came in on the peer's own link after its digest is not served back to it by the answer under way. */
    @Test
    fun aFrameFromThePeerAfterItsDigestIsNotSentBack() =
        runTest {
            val r = Rounds()
            r.carry(chat("left", "a", sentAt = 0), chat("big", "a", sentAt = 1, bytes = 20_000))

            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)
            val theirs = chat("theirs", "y", sentAt = 5)
            r.sync.onSeen(wireOf(theirs), theirs, ForwardStore.ORIGIN_RELAY, from = "z", fromProven = true, fromLink = true)
            r.drain()
            r.look()

            assertEquals(listOf("big", "left"), r.sent())
        }

    /**
     * Everything an answer remembers is trimmed to custody's live ids at every round (contract revision 6, §2): a peer that
     * digests every minute for hours keeps one answer alive, and no set it keeps outgrows what custody holds.
     */
    @Test
    fun anAnswerKeptAliveForHoursRemembersNoMoreThanCustodyHolds() =
        runTest {
            val ttl = 10 * 60_000L
            val r = Rounds()
            val store = FakeForwardStore(ttlMs = ttl, carry = r.ledger)
            // One frame a round, two drains a minute against five new frames: the answer never runs out.
            val slow = RoundPlan(bytes = 4_000)
            val sync =
                ForwardSync(
                    r.transport,
                    store,
                    clock = { r.now },
                    keyFor = { wireOf(profile("key-$it", it)) },
                    carry = r.ledger,
                    pace = CustodyPace(slow, slow, slow),
                )
            var largest = 0
            repeat(4 * 60) { minute ->
                r.now = minute * 60_000L
                (1..5).forEach {
                    val e = chat("m$minute-$it", "s$minute", sentAt = r.now, bytes = 4_000)
                    sync.onSeen(wireOf(e), e, ForwardStore.ORIGIN_RELAY)
                }
                sync.sweepExpired()
                val live = store.liveIds(r.now)
                sync.onDigest("z", live.take(2) + (1..50).map { "junk-$minute-$it" }, TransportKind.Bluetooth)
                (1..2).forEach { step ->
                    r.drain()
                    sync.serveDue(r.now + step * 20_000L)
                }
                sync.answerSizes("z")?.let { sizes ->
                    val held = store.liveIds(r.now).size
                    sizes.forEach { assertTrue("an answer's set of $it outgrew custody's $held live ids", it <= held) }
                    largest = maxOf(largest, sizes.max())
                }
            }
            assertTrue("the answer stayed alive and remembered something", largest > 0)
        }

    /** A digest that comes while custody's index cannot be read is dropped, and the answer under way stays as it was. */
    @Test
    fun aDigestWhoseIndexReadFailsIsDroppedAndTheAnswerStays() =
        runTest {
            var failing = false
            val inner = FakeForwardStore(ttlMs = DAY_MS)
            val store =
                object : ForwardStore by inner {
                    override suspend fun liveIndex(now: Long): List<IndexedFrame> =
                        if (failing) error("disk I/O error") else inner.liveIndex(now)
                }
            val transport =
                RecordingTransport().apply {
                    links.value = setOf(Peer("z"))
                    outbound["z"] = Outbound()
                }
            val dropped = mutableListOf<Exception>()
            val sync = ForwardSync(transport, store, clock = { 0L }, onDigestDropped = { dropped += it })
            listOf(chat("left", "a", sentAt = 0), chat("big", "a", sentAt = 1, bytes = 20_000)).forEach {
                sync.onSeen(wireOf(it), it, ForwardStore.ORIGIN_RELAY)
            }
            sync.onDigest("z", emptyList())
            val before = sync.answerSizes("z")

            failing = true
            sync.onDigest("z", listOf("big", "left"))

            assertEquals(1, dropped.size)
            assertEquals("the answer under way kept its digest", before, sync.answerSizes("z"))
            failing = false
            transport.outbound.getValue("z").unwritten = 0L
            sync.serveDue(0L)
            assertEquals(listOf("big", "left"), transport.sent.map { it.first.frameId() })
        }

    /** A Bluetooth link replaced under a peer still linked: what went over the old one is owed again. */
    @Test
    fun aRelinkMidAnswerReleasesWhatWentOverTheOldLinkAgain() =
        runTest {
            val r = Rounds()
            r.carry(chat("dm", "a", sentAt = 1, recipient = "z", bytes = 20_000), chat("left", "a", sentAt = 0))

            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)
            r.transport.carriers["z"] = LinkCarrier(TransportKind.Bluetooth, Any(), proven = true)
            r.drain()
            r.look()

            assertEquals(listOf("dm", "dm"), r.sent())
        }

    // --- the re-offer back-off (§7) ---

    /** A sync whose neighbour "z" links at 0 and whose tick runs every 60 s from then: the times its digest went. */
    private inner class Ticks {
        var now = 0L
        val metrics = MeshMetrics()
        val transport = RecordingTransport()
        val sync = ForwardSync(transport, FakeForwardStore(ttlMs = DAY_MS), clock = { now }, metrics = metrics)
        val offered = mutableListOf<Long>()

        suspend fun linkUp(at: Long) {
            now = at
            sync.onNeighborAdded(Peer("z"))
            offered += at
        }

        suspend fun tick(at: Long): Boolean {
            now = at
            return sync.reoffer(Peer("z")).also { if (it) offered += at }
        }

        fun gaps() = offered.zipWithNext { a, b -> (b - a) / 1_000 }
    }

    /**
     * The tick's neighbours are what [ForwardSync.retainOffers] keeps: a neighbour no longer linked loses its back-off,
     * and when it is seen again its first tick offers at once and starts the interval at 60 s, as a link-up does; one
     * still linked keeps its own.
     */
    @Test
    fun retainOffersForgetsTheBackOffOfANeighbourNoLongerLinked() =
        runTest {
            val t = Ticks()
            t.linkUp(0L)
            t.sync.onNeighborAdded(Peer("y"))
            (1..3).forEach { t.tick(it * 60_000L) } // z's offers at 60 and 180 s: its interval is 240 s now
            assertEquals(240_000L, t.sync.reofferIntervalMs("z"))

            t.sync.retainOffers(setOf("y"))
            assertEquals(null, t.sync.reofferIntervalMs("z"))
            assertEquals(ForwardSync.REOFFER_MS, t.sync.reofferIntervalMs("y"))

            assertTrue("a neighbour back in the tick's set is offered at once", t.tick(240_000L))
            assertEquals(ForwardSync.REOFFER_MS, t.sync.reofferIntervalMs("z"))
        }

    /** A neighbour that never draws is offered at gaps of 60, 120, 240, 480, then 600 s, and each held tick counts. */
    @Test
    fun aNeighbourThatNeverDrawsIsOfferedAtDoublingGapsUpToTenMinutes() =
        runTest {
            val t = Ticks()
            t.linkUp(0L)
            val ticks = 40
            (1..ticks).forEach { t.tick(it * 60_000L) }

            assertEquals(listOf(60L, 120L, 240L, 480L, 600L, 600L), t.gaps())
            assertEquals("every tick that did not offer held", (ticks - t.gaps().size).toLong(), t.metrics.frames().custodyReoffersHeld)
            assertEquals(t.offered.size, t.transport.digestsSent.size)
        }

    /** At the 480 s wait, a frame kept off the neighbour's link 100 s in brings the offer at the next tick, then 60 s gaps. */
    @Test
    fun aKeptFrameResetsTheIntervalAtOnce() =
        runTest {
            val t = Ticks()
            t.linkUp(0L)
            (1..7).forEach { t.tick(it * 60_000L) }
            assertEquals("the offer before the 480 s wait", 420_000L, t.offered.last())

            t.now = 520_000L
            val e = chat("theirs", "y", sentAt = 520_000L)
            t.sync.onSeen(wireOf(e), e, ForwardStore.ORIGIN_RELAY, from = "z", fromLink = true)
            assertTrue(t.tick(540_000L))
            assertTrue("the offer before drew: the next gap is 60 s", t.tick(600_000L))
            assertFalse("and that one drew nothing", t.tick(660_000L))
        }

    /** A frame replayed off the key-wait park did not come in on the link: it marks no draw. */
    @Test
    fun aReplayedFrameMarksNoDraw() =
        runTest {
            val t = Ticks()
            t.linkUp(0L)
            (1..7).forEach { t.tick(it * 60_000L) }
            t.now = 520_000L
            val e = chat("parked", "y", sentAt = 520_000L)
            t.sync.onSeen(wireOf(e), e, ForwardStore.ORIGIN_RELAY, from = "z")
            assertFalse(t.tick(540_000L))
        }

    /** The link-up offer counts as one: a tick 20 s after it is held, one 40 s after it offers. */
    @Test
    fun aTickTwentySecondsAfterTheLinkUpIsHeldAndFortyOffers() =
        runTest {
            val t = Ticks()
            t.linkUp(0L)
            assertFalse(t.tick(20_000L))
            assertTrue(t.tick(40_000L))
        }

    /** The half tick of tolerance: a tick landing 10 ms early at the 60 s step still offers. */
    @Test
    fun aTickAFewMillisecondsEarlyStillOffers() =
        runTest {
            val t = Ticks()
            t.linkUp(0L)
            assertTrue(t.tick(59_990L))
        }

    /** A neighbour linking anew starts at 60 s, and the link-up offer is never held, whatever the back-off was. */
    @Test
    fun aRelinkStartsTheBackOffOver() =
        runTest {
            val t = Ticks()
            t.linkUp(0L)
            (1..15).forEach { t.tick(it * 60_000L) }
            t.linkUp(960_000L)
            assertFalse(t.tick(980_000L))
            assertTrue(t.tick(1_020_000L))
            assertEquals(120_000L, t.sync.reofferIntervalMs("z"))
        }

    /** With the back-off off (the lab's default, never production's), every tick offers, as the tick did before it. */
    @Test
    fun withoutTheBackOffEveryTickOffers() =
        runTest {
            val transport = RecordingTransport()
            var now = 0L
            val sync = ForwardSync(transport, FakeForwardStore(ttlMs = DAY_MS), clock = { now }, reofferBackoff = false)
            sync.onNeighborAdded(Peer("z"))
            (1..5).forEach {
                now = it * 1_000L
                assertTrue(sync.reoffer(Peer("z")))
            }
            assertEquals(6, transport.digestsSent.size)
        }

    /** The lab's "offer now": a forced offer goes whatever the back-off says. */
    @Test
    fun aForcedOfferGoesWhateverTheBackOff() =
        runTest {
            val t = Ticks()
            t.linkUp(0L)
            t.now = 1_000L
            assertTrue(t.sync.reoffer(Peer("z"), force = true))
            assertEquals(2, t.transport.digestsSent.size)
        }

    // --- contract revision 7, and the B2 review's fixes ---

    /**
     * One answer kept alive by digests (revision 7, §2): a digest inside the judging delay forgets no release; a later one
     * forgets it, and then a frame addressed to the peer goes again and a frame the memo marked not kept waits out its mark.
     */
    @Test
    fun aDigestForgetsAReleaseOnlyOnceItCanSpeakToItAndTheMemoDecides() =
        runTest {
            val pace = RoundPlan(bytes = 1_000_000).let { CustodyPace(it, it, it) }
            val r = Rounds(pace = pace)
            // A link that can't say: a round a digest, each release written at its cut.
            r.transport.outbound.remove("z")
            r.carry(chat("dm", "a", sentAt = 1, recipient = "z"), chat("post", "a", sentAt = 2))
            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth, proven = true, link = r.link)
            assertEquals(listOf("post", "dm").sorted(), r.sent().sorted())

            r.now = 16_000L
            r.carry(chat("n1", "a", sentAt = 16_000L))
            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth, proven = true, link = r.link)
            r.look()
            assertEquals("inside the judging delay: nothing forgotten", listOf("n1"), r.sent().drop(2))

            r.now = 32_000L
            r.carry(chat("n2", "a", sentAt = 32_000L))
            r.sync.onDigest("z", listOf("n1"), TransportKind.Bluetooth, proven = true, link = r.link)
            r.look()
            assertEquals("the peer's own mail goes again; the post waits out its mark", listOf("dm", "n2"), r.sent().drop(3).sorted())
            assertEquals(1L, r.metrics.frames().custodyNotKept)
        }

    /**
     * A release behind a full link is judged from its write, not its cut: a round cut at 0 that the link holds until 25 s
     * becomes pending at the look that credits it, so a digest at 30 s (past the judging delay from the cut, inside it
     * from the write) marks nothing, and holds the frame back as pending; one at 46 s judges it and marks it not kept.
     */
    @Test
    fun aReleaseBehindAFullLinkIsJudgedFromItsWriteNotItsCut() =
        runTest {
            val r = Rounds()
            r.carry(chat("post", "a", sentAt = 2))
            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth, proven = true, link = r.link)
            r.out.unwritten = 40_000L
            r.look(10_000L)
            r.drain()
            r.look(25_000L)
            assertEquals(1, r.ledger.spread("post"))

            r.now = 30_000L
            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth, proven = true, link = r.link)
            r.look()
            assertEquals("not judged, and not released again while pending", listOf("post"), r.sent())
            assertEquals(0L, r.metrics.frames().custodyNotKept)

            r.now = 46_000L
            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth, proven = true, link = r.link)
            r.look()
            assertEquals("judged from its write: not kept", 1L, r.metrics.frames().custodyNotKept)
            assertEquals(listOf("post"), r.sent())
        }

    /** The same answer from a link that proved nothing: once a digest can speak to a release, every frame it lacks goes again. */
    @Test
    fun anUnprovenAnswerReleasesAgainWhatALaterDigestStillLacks() =
        runTest {
            val pace = RoundPlan(bytes = 1_000_000).let { CustodyPace(it, it, it) }
            val r = Rounds(pace = pace)
            r.transport.outbound.remove("z")
            r.carry(chat("post", "a", sentAt = 2))
            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)
            r.now = 16_000L
            r.carry(chat("n1", "a", sentAt = 16_000L))
            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)
            r.look()
            r.now = 32_000L
            r.carry(chat("n2", "a", sentAt = 32_000L))
            r.sync.onDigest("z", listOf("n1"), TransportKind.Bluetooth)
            r.look()

            assertEquals(listOf("post", "n1", "n2", "post"), r.sent().let { it.take(2) + it.drop(2).sorted() })
        }

    /** A digest off a link that proved nothing leaves a proven peer's live answer as it was: its digest, proof and arrivals. */
    @Test
    fun anUnprovenDigestNeverOverwritesAProvenAnswer() =
        runTest {
            val r = Rounds()
            r.carry(chat("big", "a", sentAt = 1, bytes = 20_000), chat("left", "a", sentAt = 0), chat("held", "a", sentAt = 3))
            r.sync.onDigest("z", listOf("held"), TransportKind.Bluetooth, proven = true, link = r.link)
            val claimed = chat("claimed", "y", sentAt = 5)
            r.sync.onSeen(wireOf(claimed), claimed, ForwardStore.ORIGIN_RELAY, from = "z", fromLink = true)
            val before = r.sync.answerSizes("z")
            assertEquals("an unproven arrival names nothing the answer holds back", 0, before!![3])

            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth)
            assertEquals(before, r.sync.answerSizes("z"))
            r.drain()
            r.look()
            r.drain()
            r.look(60_000L)
            assertFalse("still cut from the proven digest", "held" in r.sent())
        }

    /**
     * One peer's round that throws costs that peer a retry-after of [ForwardSync.CUSTODY_RETRY_MS], not a pace: the other
     * peer's round in the same look still goes, and the failed one is tried again no sooner than that.
     */
    @Test
    fun aRoundThatThrowsForOnePeerLeavesTheOthersRound() =
        runTest {
            var failing = false
            var now = 0L
            val failures = mutableListOf<Exception>()
            val transport =
                RecordingTransport().apply {
                    links.value = setOf(Peer("z"), Peer("y"))
                    outbound["z"] = Outbound()
                    outbound["y"] = Outbound()
                }
            val drain = { transport.outbound.values.forEach { it.unwritten = 0L } }
            // A frame and its sender's key fit one round; a second frame does not.
            val slow = RoundPlan(bytes = 150).let { CustodyPace(it, it, it) }
            val sync =
                ForwardSync(
                    transport,
                    FakeForwardStore(ttlMs = DAY_MS),
                    clock = { now },
                    keyFor = { sender ->
                        if (failing &&
                            sender == "bad"
                        ) {
                            error("pin store read failed")
                        } else {
                            wireOf(profile("k-$sender", sender))
                        }
                    },
                    pace = slow,
                    onRoundFailed = { failures += it },
                )
            listOf(chat("b1", "bad", sentAt = 1), chat("b2", "bad", sentAt = 2), chat("a1", "s", sentAt = 3), chat("a2", "s", sentAt = 4))
                .forEach { sync.onSeen(wireOf(it), it, ForwardStore.ORIGIN_RELAY) }
            sync.onDigest("z", listOf("b1"))
            sync.onDigest("y", listOf("a1"))
            failing = true
            drain()
            sync.serveDue(0L)

            assertEquals(1, failures.size)
            // The frames each peer was sent, its keys left out.
            val frames = { to: String ->
                transport.sent
                    .filter { it.second?.nodeId == to }
                    .map { it.first.frameId() }
                    .filterNot { it.startsWith("k-") }
            }
            assertEquals("z's second round went", listOf("a2", "a1"), frames("z").take(2))
            assertEquals(listOf("a2"), frames("y"))

            failing = false
            now = ForwardSync.CUSTODY_RETRY_MS - 1
            drain()
            sync.serveDue(now)
            assertEquals("not before its retry-after", listOf("a2"), frames("y"))
            now = ForwardSync.CUSTODY_RETRY_MS
            drain()
            sync.serveDue(now)
            assertEquals(listOf("a2", "b2"), frames("y"))
        }

    /** With nothing holding the peer a round sends nothing and records nothing; the answer's head is not spent on no link. */
    @Test
    fun aRoundWithNoCarrierSendsAndRecordsNothing() =
        runTest {
            val r = Rounds()
            r.transport.unheld += "z"
            r.carry(chat("r1", "a", sentAt = 1))
            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth, proven = true, link = r.link)
            assertEquals(emptyList<String>(), r.sent())
            assertEquals(listOf(0, 0, 0, 0, 0), r.sync.answerSizes("z"))
            assertEquals("looked at again soon", CustodyPace.ROUND_CHECK_MS, r.sync.nextDueAt())

            r.transport.unheld -= "z"
            r.look(CustodyPace.ROUND_CHECK_MS)
            assertEquals(listOf("r1"), r.sent())
        }

    /** The memo and the credit follow the proof of the link that carries the round, whatever the digest proved. */
    @Test
    fun aRoundOverAnUnprovenLinkLeavesNoPendingAndNoCredit() =
        runTest {
            val r = Rounds()
            r.transport.carriers["z"] = LinkCarrier(TransportKind.Bluetooth, r.link, proven = false)
            r.carry(chat("r1", "a", sentAt = 1))
            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth, proven = true, link = r.link)
            r.look()

            assertEquals("no credit", 0, r.ledger.spread("r1"))
            assertFalse("nothing pending", r.ledger.blocked("z", chat("r1", "a", sentAt = 1), 0L, CarryLedger.Links.ALWAYS))
        }

    /** An index row that never loads is refused for the answer: it is not the first pick of every round. */
    @Test
    fun anIndexRowThatNeverLoadsIsNotTheFirstPickOfEveryRound() =
        runTest {
            val inner = FakeForwardStore(ttlMs = DAY_MS)
            val ghost = IndexedFrame("ghost", FrameType.CHAT, "a", null, null, sentAt = 9, receivedAt = Long.MAX_VALUE, cost = 20_000)
            val store =
                object : ForwardStore by inner {
                    override suspend fun liveIndex(now: Long): List<IndexedFrame> = inner.liveIndex(now) + ghost
                }
            val transport = RecordingTransport().apply { links.value = setOf(Peer("z")) }
            val sync = ForwardSync(transport, store, clock = { 0L })
            listOf(
                chat("r1", "a", sentAt = 1),
                chat("r2", "a", sentAt = 2),
            ).forEach { sync.onSeen(wireOf(it), it, ForwardStore.ORIGIN_RELAY) }

            sync.onDigest("z", emptyList())
            sync.serveDue(sync.nextDueAt()!!)

            assertEquals(listOf("r2", "r1"), transport.sent.map { it.first.frameId() })
            assertEquals(1, sync.answerSizes("z")?.get(4))
        }

    /**
     * A look, a credit or an offer stamped before the wall clock went back is due now, not after the jump: the drained link's
     * next round goes and the first round's hand-off is credited at once. The answer's age restarts at the jump, so it still
     * ends [ForwardSync.ANSWER_MAX_AGE_MS] later, with what is left unsent.
     */
    @Test
    fun stampsFromBeforeTheWallClockWentBackAreDueNow() =
        runTest {
            val r = Rounds()
            r.now = 1_000_000L
            // Arrival order: the newest received goes first in tier 4, and each big frame is a round of its own.
            r.carry(
                chat("left", "a", sentAt = 0),
                chat("big2", "a", sentAt = 1, bytes = 20_000),
                chat("big", "a", sentAt = 2, bytes = 20_000),
            )
            r.sync.onDigest("z", emptyList(), TransportKind.Bluetooth, proven = true, link = r.link)
            assertEquals(listOf("big"), r.sent())
            r.now = 500_000L
            assertTrue("the loop does not sleep the jump", r.sync.nextDueAt()!! <= r.now + ForwardSync.ANSWER_MAX_AGE_MS)
            r.drain()
            r.look()
            assertEquals(listOf("big", "big2"), r.sent())
            assertEquals("the first round's credit, stamped before the jump, is credited now", 1, r.ledger.spread("big"))

            r.look(r.now + ForwardSync.ANSWER_MAX_AGE_MS)
            assertEquals("the answer aged out with one frame left", listOf("big", "big2"), r.sent())
            assertEquals(null, r.sync.answerSizes("z"))
            assertEquals(1L, r.metrics.frames().custodyAnswersCutShort)

            val t = Ticks()
            t.linkUp(1_000_000L)
            assertTrue(t.tick(100_000L))
        }

    /** An answer cut short ends Wi-Fi Aware's pending only when its rounds went over Aware. */
    @Test
    fun anAnswerCutShortEndsAwarePendingOnlyWhenAwareCarriedIt() =
        runTest {
            for (aware in listOf(false, true)) {
                val r = Rounds()
                val path = Any()
                if (aware) r.transport.carriers["z"] = LinkCarrier(TransportKind.WifiAware, path, proven = true)
                val x = chat("x", "a", sentAt = 5)
                r.carry(x, chat("big", "a", sentAt = 1, bytes = 600_000), chat("left", "a", sentAt = 0))
                r.ledger.released("z", listOf(x), LinkCarrier(TransportKind.WifiAware, path, proven = true), false, 0L)
                r.sync.onDigest("z", listOf("x"), TransportKind.WifiAware, proven = true, link = path)
                r.transport.links.value = emptySet()
                r.sync.serveDue(100_000L)

                assertEquals(1L, r.metrics.frames().custodyAnswersCutShort)
                assertEquals("Aware carried it: $aware", !aware, r.ledger.blocked("z", x, 100_000L, CarryLedger.Links.ALWAYS))
            }
        }

    /**
     * A Wi-Fi Aware path cut with the answer live (the plane's `answerCut`): the answer forgets what that path carried, so
     * the next path's round releases it again; a path that only changed, uncut, forgets nothing.
     */
    @Test
    fun anAwarePathCutWithTheAnswerLiveOwesItsReleasesAgain() =
        runTest {
            for (cutPath in listOf(false, true)) {
                val r = Rounds()
                r.transport.carriers["z"] = LinkCarrier(TransportKind.WifiAware, Any(), proven = true)
                r.carry(chat("left", "a", sentAt = 0), chat("big", "a", sentAt = 1, bytes = 600_000))
                r.sync.onDigest("z", emptyList(), TransportKind.WifiAware)
                if (cutPath) r.ledger.answerCut("z", TransportKind.WifiAware)
                r.transport.carriers["z"] = LinkCarrier(TransportKind.WifiAware, Any(), proven = true)
                r.drain()
                r.look(60_000L)

                assertEquals("path cut: $cutPath", if (cutPath) listOf("big", "big") else listOf("big", "left"), r.sent())
            }
        }

    /**
     * A direct link adopted between a digest's read of the peer's answer and its update ends that answer: the digest starts
     * a new one rather than updating the ended one, so it is answered.
     */
    @Test
    fun aDigestRacingALinkUpStartsANewAnswerRatherThanUpdatingTheEndedOne() =
        runTest {
            val r = Rounds()
            r.transport.carriers["z"] = LinkCarrier(TransportKind.DirectLink, Any(), proven = true)
            r.transport.views["z"] = SendersView(setOf("a"))
            r.carry(carried("r1", "a"))
            r.sync.onDigest("z", emptyList(), TransportKind.DirectLink)

            var once = true
            r.transport.onPairView = {
                if (once) {
                    once = false
                    r.transport.carriers["z"] = LinkCarrier(TransportKind.DirectLink, Any(), proven = true)
                    r.sync.linked(listOf("z"))
                }
            }
            r.sync.onDigest("z", emptyList(), TransportKind.DirectLink)

            assertEquals("the new link's answer released it again", listOf("r1", "r1"), r.sent())
        }

    private companion object {
        // A custody TTL no case here outlives.
        const val DAY_MS = 24 * 60 * 60_000L
    }
}
