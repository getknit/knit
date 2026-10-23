package app.getknit.knit.mesh.spool

import app.getknit.knit.fieldlog.FieldEvent
import app.getknit.knit.fieldlog.FieldRecorder
import app.getknit.knit.mesh.CarriedFrame
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.PRESENCE_FRESH_MS
import app.getknit.knit.mesh.SPOOL_COVER_MS
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RatchetHeader
import app.getknit.knit.mesh.protocol.RatchetInit
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.protocol.frameIdOf
import app.getknit.knit.mesh.sha256Hex
import com.google.crypto.tink.subtle.X25519
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plane end to end, two members over one in-process spool: §9.1's bidirectional heal, §9.3's
 * invalid-set quarantine, §9.2's outward dead-on-arrival guard, and §9.4's bridge into mesh delivery.
 *
 * This is the correctness oracle for the milestone — the device bench proves the socket, this proves
 * the protocol. Virtual time is stepped rather than drained (`advanceUntilIdle` would never return: the
 * supervisor, the per-spool worker and the tick loop are all deliberately infinite).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // cohesive single-SUT suite over one shared member()/pump() harness, as InboundPipelineTest
class ScopeSyncTest {
    private val alice = "aaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val bob = "bbbbbbbbbbbbbbbbbbbbbbbbbe"
    private val carol = "ccccccccccccccccccccccccce"

    /** A frame id the spool door admits ([FrameId.admits]), named by a label. */
    private fun fid(label: String) = frameIdOf(label)

    private val pairwiseRoot = ByteArray(32) { it.toByte() }
    private val groupRoot = ByteArray(32) { (it + 30).toByte() }
    private val now = 1_000L
    private val url = "ws://spool.test/spool/v1"

    private class Member(
        val custody: FakeCustody,
        val sync: ScopeSync,
        val metrics: MeshMetrics,
        val delivered: MutableList<RelayEnvelope>,
        val blobs: FakeBlobs,
        val obtained: MutableList<String>,
        // The wire each delivery came in, beside [delivered]: what a cast's delivery wrapper is checked on.
        val wires: MutableList<WireEnvelope> = mutableListOf(),
    )

    /** The local content-addressed store, in memory. Content-addressed, so a save is write-once. */
    private class FakeBlobs(
        vararg initial: Pair<String, ByteArray>,
    ) : ScopeBlobs {
        val stored = LinkedHashMap<String, ByteArray>().apply { putAll(initial) }
        val mimes = LinkedHashMap<String, String>()

        /** What this member's own screening refused, bytes dropped (#109). */
        val refused = mutableSetOf<String>()

        override suspend fun has(aHash: String): Boolean = aHash in stored

        override suspend fun refused(aHash: String): Boolean = aHash in refused

        override suspend fun bytes(aHash: String): ByteArray? = stored[aHash]

        override suspend fun save(
            aHash: String,
            mime: String,
            bytes: ByteArray,
        ) {
            stored[aHash] = bytes
            mimes[aHash] = mime
        }
    }

    private fun member(
        spool: FakeSpool,
        self: String,
        peer: String,
        custody: FakeCustody = FakeCustody(),
        carryGate: suspend (WireEnvelope, RelayEnvelope) -> Boolean = { _, _ -> true },
        groups: List<GroupScopeRoots> = emptyList(),
        blobs: FakeBlobs = FakeBlobs(),
        deferAttachment: suspend (Scope, ScopeAttachments.Ref) -> Boolean = { _, _ -> false },
        provedAttachment: suspend (Scope, ScopeAttachments.Ref) -> Boolean = { _, _ -> false },
        holdsForProof: suspend (Scope, ScopeAttachments.Ref) -> Boolean = { _, _ -> false },
        // The confirmed-session roots (a DM scope per entry) and the intro driver's pair-scope inputs
        // (spec §3.5), both read live so a test can move a peer between them mid-run.
        roots: () -> List<ScopeRoots> = { listOf(ScopeRoots(peer, pairwiseRoot)) },
        pairs: () -> List<PairScopeRoots> = { emptyList() },
        // This member's own wall clock. Only the per-scope presence stamp reads it against a frame's
        // `sentAt`, so running one member late is how a test makes an arriving frame look like a backlog
        // pull without moving the fixture's `now` under every other assertion.
        clock: () -> Long = { now },
        // What the member dials — the spool itself, or a wrapper that watches the dials and closes.
        dialer: SpoolDialer = spool,
        // The present-set callback (ADR 2026-09.y5f3); the default drops it, as every earlier test did.
        presence: (Set<String>) -> Unit = {},
        // The relays this member dials, and the tickets it holds for them (VOICE_CALLS.md §2.6);
        // [liveUrls] reads the list live instead, so a test can switch the relays off mid-run.
        urls: List<String> = listOf(url),
        liveUrls: () -> List<String> = { urls },
        tickets: SpoolTickets? = null,
        log: (String) -> Unit = {},
        // The refresher's wake for an upload locked for want of a ticket (C-7.6-27 amended).
        onUploadWanted: (String) -> Unit = {},
        field: FieldRecorder = FieldRecorder.NONE,
        // Where each scope lives (ADR 2026-10.8kfx); null, as before homes, puts every scope at every relay.
        placement: () -> ScopePlacement? = { null },
        onRelaysChanged: (Set<String>) -> Unit = {},
    ): Member {
        val metrics = MeshMetrics()
        val delivered = mutableListOf<RelayEnvelope>()
        val wires = mutableListOf<WireEnvelope>()
        val obtained = mutableListOf<String>()
        val sync =
            ScopeSync(
                registry =
                    ScopeRegistry(
                        selfId = { self },
                        roots = { roots() },
                        groupRoots = { groups },
                        pairs = { pairs() },
                        placement = { placement() },
                    ),
                dialer = dialer,
                store = custody,
                selfId = { self },
                urls = liveUrls,
                canCarry = carryGate,
                // Stands in for MeshRouter.handleInbound: deliver, then capture into custody exactly as
                // InboundPipeline.onDeliver does, so the next heal round folds the frame into our digest.
                deliver = { wire, env, _ ->
                    delivered.add(env)
                    wires.add(wire)
                    // A walkie chunk is never custodied (InboundPipeline.onDeliver carries custodial types alone).
                    if (env.type != FrameType.TALK) custody.store(CarriedFrame(env, wire.sig, wire.signed), ForwardStore.ORIGIN_RELAY, now)
                },
                blobs = blobs,
                onAttachmentObtained = { obtained.add(it) },
                deferAttachment = deferAttachment,
                provedAttachment = provedAttachment,
                holdsForProof = holdsForProof,
                onPresenceChanged = presence,
                metrics = metrics,
                clock = clock,
                jitter = { 0L },
                tickets = tickets,
                onUploadWanted = onUploadWanted,
                log = log,
                field = field,
                onRelaysChanged = onRelaysChanged,
            )
        return Member(custody, sync, metrics, delivered, blobs, obtained, wires)
    }

    /** Bytes plus their content address — what a frame's cleartext `attachmentHash` names. */
    private fun image(size: Int = 100_000): Pair<String, ByteArray> {
        val bytes = ByteArray(size) { ((it * 13) and 0xFF).toByte() }
        return sha256Hex(bytes) to bytes
    }

    private fun aidHex(
        self: String,
        peer: String,
        aHash: String,
    ): String {
        val id = ScopeCrypto.dmScopeId(pairwiseRoot, self, peer)
        val keys = ScopeCrypto.dmSealKeys(pairwiseRoot, self, peer)
        return hex(ScopeCrypto.attachmentId(keys, id, ScopeAttachments.hashBytes(aHash)!!))
    }

    private fun scopeHex(
        self: String,
        peer: String,
    ) = hex(ScopeCrypto.dmScopeId(pairwiseRoot, self, peer))

    // Whether each SUB of [scopeHex] carried a stamp, in order.
    private fun List<Pair<String, Boolean>>.stampedFor(scopeHex: String): List<Boolean> =
        synchronized(this) { filter { it.first == scopeHex }.map { it.second } }

    /** Steps virtual time in small slices so the infinite loops make progress without being drained. */
    private fun TestScope.pump(rounds: Int = 8) {
        repeat(rounds) {
            advanceTimeBy(1_000)
            runCurrent()
        }
    }

    /** A socket that is dead the moment it is handed over, as a refused WebSocket upgrade is. */
    private class DialLog(
        val retryAfterMs: Long?,
    ) {
        val at = mutableListOf<Long>()
    }

    private fun TestScope.busyRelay(log: DialLog): ScopeSync {
        val scheduler = testScheduler
        val dialer =
            object : SpoolDialer {
                override suspend fun dial(url: String): SpoolSocket {
                    log.at += scheduler.currentTime
                    return object : SpoolSocket {
                        private val ch = Channel<ByteArray>(Channel.UNLIMITED).also { it.close() }

                        override val incoming get() = ch
                        override val closeReason = "http 503"
                        override val retryAfterMs = log.retryAfterMs

                        override fun send(bytes: ByteArray) = false

                        override fun close(
                            code: Int,
                            reason: String,
                        ) = Unit
                    }
                }
            }
        return ScopeSync(
            registry = ScopeRegistry({ alice }, { listOf(ScopeRoots(bob, pairwiseRoot)) }),
            dialer = dialer,
            store = FakeCustody(),
            selfId = { alice },
            urls = { listOf(url) },
            canCarry = { _, _ -> true },
            deliver = { _, _, _ -> },
            clock = { now },
            // Fixed rather than zero: the floor must not be allowed to swallow the jitter that
            // de-synchronises a population of clients one full spool refused in the same instant.
            jitter = { 250L },
        )
    }

    /**
     * Watches one member's sessions against a real [FakeSpool]: when each socket was dialled and when the
     * client first closed it. The gap between a close and the next dial is the reconnect backoff — the
     * one observable that says whether a session counted as "reached".
     */
    private inner class SessionLog(
        private val spool: FakeSpool,
        private val scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
    ) : SpoolDialer {
        val dialedAt = mutableListOf<Long>()
        val closedAt = mutableListOf<Long>()

        override suspend fun dial(url: String): SpoolSocket {
            dialedAt += scheduler.currentTime
            val inner = spool.dial(url)
            val index = closedAt.size.also { closedAt += -1L }
            return object : SpoolSocket by inner {
                override fun close(
                    code: Int,
                    reason: String,
                ) {
                    if (closedAt[index] < 0) closedAt[index] = scheduler.currentTime
                    inner.close(code, reason)
                }
            }
        }

        /** Reconnect waits, in ms: each dial minus the close of the session before it. */
        fun gaps(): List<Long> = dialedAt.drop(1).mapIndexed { i, at -> at - closedAt[i] }
    }

    @Test
    fun `a frame one member custodies reaches the other through the spool`() =
        runTest {
            val spool = FakeSpool()
            val sender = member(spool, alice, bob)
            val receiver = member(spool, bob, alice)
            sender.custody.store(dmFrame(fid("m1"), from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)

            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump()

            assertEquals("the sender pushed its custody", 1, spool.pushed.size)
            assertEquals(listOf(fid("m1")), receiver.delivered.map { it.id })
            assertTrue("the bridged frame lands in the receiver's custody", receiver.custody.has(fid("m1")))
            assertEquals(1, sender.metrics.spool().spoolPushed)
            assertEquals(1, receiver.metrics.spool().spoolBridged)
            sender.sync.stop()
            receiver.sync.stop()
        }

    @Test
    fun `a call asks a turn spool carrying the peer's scope for a credential and no other spool at all`() =
        runTest {
            val spool = FakeSpool(turn = true)
            val caller = member(spool, alice, bob)
            caller.sync.start(backgroundScope)
            pump()

            // Experimental (docs/VOICE_CALLS.md §2): the DM scope is subscribed and answered, so the spool counts it.
            val creds = caller.sync.turnCredentials(bob)
            assertEquals(listOf(listOf("turn:spool.test:3478?transport=udp")), creds.map { it.urls })
            assertEquals(1, spool.turnIssued)
            // A peer this phone shares no scope with gets none, and costs the spool nothing.
            assertTrue(caller.sync.turnCredentials("nobody").isEmpty())
            assertEquals(1, spool.turnIssued)
            caller.sync.stop()

            // C-7.6-3: a spool that never advertised turn is never sent a TURNREQ it would leave hanging.
            val older = FakeSpool()
            val other = member(older, alice, bob)
            other.sync.start(backgroundScope)
            pump()
            assertTrue(other.sync.turnCredentials(bob).isEmpty())
            assertFalse(SpoolRecordType.TURNREQ in older.skippedRecords)
            other.sync.stop()
        }

    /** Dials each relay URL to its own [FakeSpool]: several spools, one member. */
    private class Relays(
        val byUrl: Map<String, FakeSpool>,
    ) : SpoolDialer {
        override suspend fun dial(url: String): SpoolSocket = byUrl.getValue(url).dial(url)
    }

    @Test
    fun `a ticketed spool is asked for turn on every call and first, and an unticketed ent spool not at all`() =
        runTest {
            // Two plain relays carry the peer's scope; the lab relay takes tickets, and holds nothing of Bob's.
            val plain1 = FakeSpool(turn = true)
            val plain2 = FakeSpool(turn = true)
            val lab = FakeSpool(turn = true, ent = true, stun = listOf("stun:lab.example:3478"))
            val urls = listOf("ws://one.test/spool/v1", "ws://two.test/spool/v1", "ws://lab.example:9480/spool/v1?k=t")
            val relays = Relays(mapOf(urls[0] to plain1, urls[1] to plain2, urls[2] to lab))
            val journal = SpoolTicketsTest.MemoryJournal()
            val tickets = SpoolTickets(journal) { now }
            val lines = mutableListOf<String>()
            val caller = member(plain1, alice, bob, dialer = relays, urls = urls, tickets = tickets, log = { lines += it })
            caller.sync.start(backgroundScope)
            pump()

            // No ticket yet: S-7.6-16 would refuse a turnreq there, so none goes, and the log says it was skipped.
            assertEquals(2, caller.sync.turnCredentials(bob).size)
            assertEquals(0, lab.turnIssued)
            assertTrue(lab.turnRefusals.isEmpty())
            assertTrue(lines.any { it.startsWith("lab.example:9480: TURN ask skipped") })
            // What "Relay all calls" is offered on: the plain relays issue TURN to anyone, the lab one not yet.
            assertEquals(
                listOf(true, true, false),
                urls.map { url ->
                    caller.sync
                        .status()
                        .single { it.url == url }
                        .turnForUs
                },
            )

            // Imported mid-connection, the ticket goes out at once and is accepted.
            assertTrue(tickets.import(urls[2], SpoolTicketsTest.TEXT) is SpoolTickets.Import.Held)
            pump(2)
            assertEquals(1, lab.ents.size)
            val status = caller.sync.status().single { it.url == urls[2] }
            assertTrue(status.ent)
            assertTrue(status.turn)
            assertEquals(listOf("stun:lab.example:3478"), status.stun)
            assertEquals(SpoolTicketState.Accepted(exp = 1_800_086_400L, fl = SpoolTicket.FL_TURN), status.ticket)
            assertTrue(status.turnForUs)

            // C-7.6-21: asked with no answered SUB for the peer (a peer this phone shares no scope with)...
            assertEquals(1, caller.sync.turnCredentials("nobody").size)
            assertEquals(1, lab.turnIssuedTicketed)
            // ...and first: three candidates, two asked, and the ticketed one is always among them.
            val before = plain1.turnIssued + plain2.turnIssued
            assertEquals(2, caller.sync.turnCredentials(bob).size)
            assertEquals(2, lab.turnIssuedTicketed)
            assertEquals(before + 1, plain1.turnIssued + plain2.turnIssued)
            assertEquals(listOf("stun:lab.example:3478"), caller.sync.stunUrls())
            caller.sync.stop()
        }

    @Test
    fun `a refused ticket is not presented again on every round, and only early earns a second connection`() =
        runTest {
            val lab = FakeSpool(turn = true, ent = true)
            val labUrl = "ws://lab.example:9480/spool/v1"
            val journal = SpoolTicketsTest.MemoryJournal()
            val tickets = SpoolTickets(journal) { now }
            tickets.import(labUrl, SpoolTicketsTest.TEXT)
            lab.entAnswer = { "early" }
            val member = member(lab, alice, bob, urls = listOf(labUrl), tickets = tickets)
            member.sync.start(backgroundScope)
            pump()
            // Right after the hello, once — the verdict it left changes the store, and that does not re-send it.
            assertEquals(1, lab.ents.size)
            assertEquals(
                SpoolTicketState.Refused("early"),
                member.sync
                    .status()
                    .single()
                    .ticket,
            )
            pump(70)
            assertEquals(1, lab.ents.size)

            lab.dropSockets()
            pump(30)
            assertEquals("early's one retry, on the next connection", 2, lab.ents.size)
            lab.dropSockets()
            pump(30)
            assertEquals(2, lab.ents.size)
            assertEquals(
                "early",
                journal.state.value
                    .getValue("lab.example")
                    .verdict,
            )

            // A different ticket is a fresh start; `expired` drops it, so the next connection presents nothing.
            tickets.import(labUrl, SpoolTicketsTest.ticket(sigByte = 3).text)
            lab.entAnswer = { "expired" }
            pump(2)
            assertEquals(3, lab.ents.size)
            assertTrue(journal.state.value.isEmpty())
            lab.dropSockets()
            pump(30)
            assertEquals(3, lab.ents.size)

            // `signature` keeps the bytes but never presents them again, a reconnect included.
            tickets.import(labUrl, SpoolTicketsTest.ticket(sigByte = 4).text)
            lab.entAnswer = { "signature" }
            pump(2)
            assertEquals(4, lab.ents.size)
            lab.dropSockets()
            pump(30)
            assertEquals(4, lab.ents.size)
            // And none of it ever cost the connection a strike.
            assertTrue(
                member.sync
                    .status()
                    .single()
                    .connected,
            )
            member.sync.stop()
        }

    @Test
    fun `no log line the plane writes carries a ticket`() =
        runTest {
            val lab = FakeSpool(turn = true, ent = true, stun = listOf("stun:lab.example:3478"))
            val labUrl = "ws://lab.example:9480/spool/v1"
            val tickets = SpoolTickets(SpoolTicketsTest.MemoryJournal()) { now }
            tickets.import(labUrl, SpoolTicketsTest.TEXT)
            val lines = mutableListOf<String>()
            val member = member(lab, alice, bob, urls = listOf(labUrl), tickets = tickets, log = { lines += it })
            member.sync.start(backgroundScope)
            pump()
            member.sync.turnCredentials(bob)
            lab.entAnswer = { "signature" }
            tickets.import(labUrl, SpoolTicketsTest.ticket(sigByte = 6).text)
            pump(2)
            member.sync.stop()

            assertTrue(lines.toString(), lines.any { "ticket accepted" in it } && lines.any { "ticket refused: signature" in it })
            assertTrue(lines.any { "TURN ask (ticketed): credential" in it })
            for (line in lines) assertFalse(line, SpoolTicketsTest.leaks(line) || "fake-pass" in line)
        }

    @Test
    fun `a ticket the refresher fetches is presented at once and no line names the key or the ticket`() =
        runTest {
            // ADR 2026-10.3sk7: the key swap's ticket enters through the import door, so the live connection's store
            // watch presents it with no adb line, and the refresher's lines join the plane's under the same guard.
            val lab = FakeSpool(turn = true, ent = true)
            val labUrl = "ws://lab.example:9480/spool/v1"
            val ticketJournal = SpoolTicketsTest.MemoryJournal()
            val tickets = SpoolTickets(ticketJournal) { now }
            val keys = TicketRefresherTest.MemoryKeyJournal(ticketJournal)
            val key = "kak1_FBsiKTA3PkVMU1phaG92fYSLkpmgp661vMPK0djf5u0"
            keys.putKey("lab.example", HeldKey("https://anywhere.example.org", key))
            val lines = mutableListOf<String>()
            val member = member(lab, alice, bob, urls = listOf(labUrl), tickets = tickets, log = { lines += it })
            member.sync.start(backgroundScope)
            pump()
            assertEquals(0, lab.ents.size)
            val issuer =
                object : TicketIssuer {
                    override suspend fun swap(
                        origin: String,
                        key: String,
                        device: DeviceSigner?,
                    ) = IssuerAnswer.Issued(SpoolTicketsTest.TEXT, now + 30L * 86_400_000L)
                }
            val refresher =
                TicketRefresher(keys, tickets, issuer, MutableStateFlow(setOf(labUrl)), clock = { now }, log = { lines += it })
            refresher.runOnce()
            pump(2)
            assertEquals(1, lab.ents.size)
            member.sync.stop()

            assertTrue(lines.toString(), lines.any { "ticket fetched" in it } && lines.any { "ticket presented" in it })
            for (line in lines) {
                assertFalse(line, SpoolTicketsTest.leaks(line))
                assertFalse(line, key.removePrefix("kak1_").take(12) in line)
            }
        }

    /**
     * A scope is derived from the pairwise ratchet root, so it is subscribed and converged whether or not
     * its peer has been online this month — which is why nothing else on [ScopeStatus] can be read as
     * "this peer is reachable". [ScopeStatus.peerSeenAt] is the only field that says otherwise, and it is
     * set only by that peer's own recent traffic (ADR 2026-09.2ajk).
     */
    @Test
    fun `a scope reports its peer seen only once that peer has pushed something recent`() =
        runTest {
            val spool = FakeSpool()
            val sender = member(spool, alice, bob)
            val receiver = member(spool, bob, alice)
            receiver.sync.start(backgroundScope)
            pump()

            assertNull(
                "converged and connected, but alice has never spoken here",
                receiver.sync
                    .status()
                    .single()
                    .scopes
                    .single()
                    .peerSeenAt,
            )

            sender.custody.store(dmFrame(fid("m1"), from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            sender.sync.start(backgroundScope)
            pump()

            assertEquals(listOf(fid("m1")), receiver.delivered.map { it.id })
            assertEquals(
                "alice's own fresh frame is what makes the scope a live path",
                now,
                receiver.sync
                    .status()
                    .single()
                    .scopes
                    .single()
                    .peerSeenAt,
            )
            sender.sync.stop()
            receiver.sync.stop()
        }

    @Test
    fun `a connected spool reports the build it published, and stops claiming one when the session ends`() =
        runTest {
            // The row is a fact about the live connection, like the attachment budget and the PoW cost
            // beside it: a redeploy is exactly what drops the session, so a build left standing under
            // "offline" would name the version of a process that is no longer there.
            val spool = FakeSpool()
            val publishing =
                object : SpoolDialer by spool {
                    override suspend fun fetchSoftware(url: String) =
                        parseSpoolSoftware("""{"name":"knit-spool","version":"0.3.0","commit":"e8a7790"}""")
                }
            val member = member(spool, alice, bob, dialer = publishing)
            member.sync.start(backgroundScope)
            pump()

            assertEquals(
                "knit-spool 0.3.0 (e8a7790)",
                member.sync
                    .status()
                    .single()
                    .software
                    ?.label,
            )

            // The spool goes away; read the row before the reconnect backoff expires and re-fetches.
            spool.dropSockets()
            runCurrent()
            val offline = member.sync.status().single()
            assertFalse(offline.connected)
            assertNull("no connection, nothing to claim about it", offline.software)
            member.sync.stop()
        }

    @Test
    fun `a spool that publishes no build is reported without one`() =
        runTest {
            // A third-party spool implementation, or ours behind a proxy that swallows the route. Nothing
            // about the plane depends on the answer, so the whole row is simply absent.
            val spool = FakeSpool()
            val member = member(spool, alice, bob)
            member.sync.start(backgroundScope)
            pump()

            assertTrue(
                "still a working connection",
                member.sync
                    .status()
                    .single()
                    .connected,
            )
            assertNull(
                member.sync
                    .status()
                    .single()
                    .software,
            )
            member.sync.stop()
        }

    @Test
    fun `a backlog pull does not make its author present`() =
        runTest {
            val spool = FakeSpool()
            val sender = member(spool, alice, bob)
            // A spool holds blobs for 48 h and a client pulls whatever it lacks whenever it next connects,
            // so a scope yields old frames as a matter of course. Bob comes back long after alice wrote.
            val late = now + PRESENCE_FRESH_MS + 1
            val receiver = member(spool, bob, alice, clock = { late })
            sender.custody.store(dmFrame(fid("m1"), from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)

            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump()

            assertEquals("the frame still bridges — presence is never a delivery gate", listOf(fid("m1")), receiver.delivered.map { it.id })
            assertNull(
                "but a frame alice wrote before she left does not put her back on the plane",
                receiver.sync
                    .status()
                    .single()
                    .scopes
                    .single()
                    .peerSeenAt,
            )
            sender.sync.stop()
            receiver.sync.stop()
        }

    @Test
    fun `a peer's presence is reported once when it appears and withdrawn when it lapses`() =
        runTest {
            // The mesh reads presence as a push, not a poll (ADR 2026-09.y5f3): the LoRa plane's Internet cover
            // and AckSync's spool route follow this callback, at the 15-min window, and a stamp that lapses
            // is withdrawn on the worker's own tick.
            val spool = FakeSpool()
            var t = now
            val seen = mutableListOf<Set<String>>()
            val sender = member(spool, alice, bob)
            val receiver = member(spool, bob, alice, clock = { t }, presence = { seen += it })
            receiver.sync.start(backgroundScope)
            pump()
            assertTrue("a connected, converged scope says nothing about its peer", seen.none { alice in it })

            sender.custody.store(dmFrame(fid("m1"), from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            sender.sync.start(backgroundScope)
            pump()
            assertEquals("alice's fresh frame puts her on the plane, once", listOf(setOf(alice)), seen.filter { it.isNotEmpty() })
            assertEquals(setOf(alice), receiver.sync.presentPeers(t))

            t = now + SPOOL_COVER_MS + 1
            pump(70) // past the worker's 60 s tick, which is what lets a stamp lapse
            assertEquals("and the cover is withdrawn when the window closes", emptySet<String>(), seen.last())
            assertEquals(emptySet<String>(), receiver.sync.presentPeers(t))
            sender.sync.stop()
            receiver.sync.stop()
        }

    @Test
    fun `switching the relays off withdraws every peer they covered`() =
        runTest {
            // #196: the switch-off empties the relay list, and the reconcile that removes the workers is the
            // last thing to run on this plane — a removed worker's stop() never reaches its session's
            // teardown. Without a republish there the LoRa plane keeps the old cover and never airs a DM.
            val spool = FakeSpool()
            val seen = mutableListOf<Set<String>>()
            var relays = listOf(url)
            val sender = member(spool, alice, bob)
            val receiver = member(spool, bob, alice, presence = { seen += it }, liveUrls = { relays })
            sender.custody.store(dmFrame(fid("m1"), from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            receiver.sync.start(backgroundScope)
            sender.sync.start(backgroundScope)
            pump()
            assertEquals("alice's fresh frame puts her on the plane", setOf(alice), seen.lastOrNull())

            relays = emptyList()
            receiver.sync.onScopeTableChanged()
            pump()
            assertEquals("the receiver's workers are gone", emptyList<SpoolStatus>(), receiver.sync.status())
            assertEquals("and so is the cover they gave", emptySet<String>(), seen.last())
            sender.sync.stop()
            receiver.sync.stop()
        }

    @Test
    fun `presence is stamped before the frame is delivered`() =
        runTest {
            // The receipt answering the DM that reveals the peer is originated inside `deliver`; the cover has
            // to already know the peer for that receipt to stay off the board (ADR 2026-09.y5f3). Still never a
            // gate — the frame is delivered either way.
            val spool = FakeSpool()
            val sender = member(spool, alice, bob)
            var presentAtDelivery: Set<String>? = null
            val custody = FakeCustody()
            lateinit var receiverSync: ScopeSync
            receiverSync =
                ScopeSync(
                    registry = ScopeRegistry({ bob }, { listOf(ScopeRoots(alice, pairwiseRoot)) }),
                    dialer = spool,
                    store = custody,
                    selfId = { bob },
                    urls = { listOf(url) },
                    canCarry = { _, _ -> true },
                    deliver = { wire, env, _ ->
                        presentAtDelivery = receiverSync.presentPeers(now)
                        custody.store(CarriedFrame(env, wire.sig, wire.signed), ForwardStore.ORIGIN_RELAY, now)
                    },
                    clock = { now },
                    jitter = { 0L },
                )
            sender.custody.store(dmFrame(fid("m1"), from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            sender.sync.start(backgroundScope)
            receiverSync.start(backgroundScope)
            pump()

            assertEquals(setOf(alice), presentAtDelivery)
            sender.sync.stop()
            receiverSync.stop()
        }

    // --- the direct push (spec §9.4 C-9.4-3, ADR 2026-09.y5f3) ---

    /** A room post's delivery tick as the ride deadline seals it: signed, `relay = false`, and never custodied here. */
    private fun tickWire(frame: CarriedFrame) = WireEnvelope(relay = false, sig = frame.sig, signed = frame.signed)

    @Test
    fun `a directly pushed tick reaches the peer, is never re-pulled by its pusher, and leaves both digests converged`() =
        runTest {
            val spool = FakeSpool()
            val sender = member(spool, alice, bob)
            val receiver = member(spool, bob, alice)
            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump()

            val tick = dmFrame(fid("tick-1"), from = alice, to = bob, sentAt = now)
            assertTrue(sender.sync.pushDirect(bob, tick.envelope, tickWire(tick)))
            pump()

            assertEquals("the peer has it", listOf(fid("tick-1")), receiver.delivered.map { it.id })
            assertEquals(1, spool.pushed.size)
            assertFalse("the pusher never custodied it", sender.custody.has(fid("tick-1")))
            assertEquals(1, sender.metrics.spool().spoolPushed)

            // The pusher's own heal loop must neither pull it back nor list on its account: many ticks, then
            // a dropped socket and a fresh session, and the scope reads converged throughout.
            pump(130)
            spool.dropSockets()
            pump(130)
            assertTrue("never delivered back to its pusher", sender.delivered.isEmpty())
            val scope =
                sender.sync
                    .status()
                    .single()
                    .scopes
                    .single()
            assertTrue("the pusher's digest matches the spool's", scope.converged)
            assertEquals("carried in the accounted band, not custody", 1, scope.accountedCount)
            assertEquals(scope.spoolCount, scope.localCount)
            assertTrue(
                "and the peer's does too",
                receiver.sync
                    .status()
                    .single()
                    .scopes
                    .single()
                    .converged,
            )
            sender.sync.stop()
            receiver.sync.stop()
        }

    @Test
    fun `a direct push reports false when no connected spool holds the scope`() =
        runTest {
            val spool = FakeSpool()
            val sender = member(spool, alice, bob)
            val tick = dmFrame(fid("tick-1"), from = alice, to = bob, sentAt = now)
            assertFalse("not started: nothing is connected", sender.sync.pushDirect(bob, tick.envelope, tickWire(tick)))

            sender.sync.start(backgroundScope)
            pump()
            assertFalse("no scope with carol", sender.sync.pushDirect("carol", tick.envelope, tickWire(tick)))
            assertTrue(spool.pushed.isEmpty())
            sender.sync.stop()
        }

    @Test
    fun `a direct push is refused for a frame outside the scope rule`() =
        runTest {
            // §4.4 governs both directions; the direct push is not a way around it.
            val spool = FakeSpool()
            val sender = member(spool, alice, bob)
            sender.sync.start(backgroundScope)
            pump()
            val stray = dmFrame(fid("stray"), from = alice, to = "carol", sentAt = now)
            assertFalse(sender.sync.pushDirect(bob, stray.envelope, tickWire(stray)))
            val stale = dmFrame(fid("stale"), from = alice, to = bob, sentAt = now - ScopeRegistry.DEFAULT_TTL_MS)
            assertFalse("dead on arrival at the spool's own TTL", sender.sync.pushDirect(bob, stale.envelope, tickWire(stale)))
            assertTrue(spool.pushed.isEmpty())
            sender.sync.stop()
        }

    /**
     * The contact-card bootstrap (spec §3.5, ADR 042): two members with NO session share only each other's
     * identity. Each derives the pair scope from the identity DH agreement; the profiles (the prekeys) and
     * the sealed intro cross it under the ordinary DM rule; once the intro driver stops naming the peer,
     * the scope leaves the table and only a DM scope would remain.
     */
    @Test
    fun `two members with no session meet at the pair scope and the intro crosses it`() =
        runTest {
            val spool = FakeSpool()
            val alicePriv = ByteArray(32) { (it + 1).toByte() }
            val bobPriv = ByteArray(32) { (it + 101).toByte() }
            val secretAtAlice = ScopeCrypto.pairSecret(alicePriv, X25519.publicFromPrivate(bobPriv))
            val secretAtBob = ScopeCrypto.pairSecret(bobPriv, X25519.publicFromPrivate(alicePriv))
            var alicePairs = listOf(PairScopeRoots(bob, secretAtAlice))
            var bobPairs = listOf(PairScopeRoots(alice, secretAtBob))
            val sender = member(spool, alice, bob, roots = { emptyList() }, pairs = { alicePairs })
            val receiver = member(spool, bob, alice, roots = { emptyList() }, pairs = { bobPairs })
            // Alice custodies her own profile plus the intro — a sealed DM-form ctl to Bob; Bob only his profile.
            sender.custody.store(profileFrame(FrameId.profileId(alice, now), from = alice, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            sender.custody.store(dmFrame(fid("intro"), from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            receiver.custody.store(profileFrame(FrameId.profileId(bob, now), from = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)

            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump()

            val pairHex = hex(ScopeCrypto.pairScopeId(secretAtAlice, alice, bob))
            assertEquals("both sides derive one pair scope", pairHex, hex(ScopeCrypto.pairScopeId(secretAtBob, bob, alice)))
            assertEquals(setOf(FrameId.profileId(bob, now)), sender.delivered.map { it.id }.toSet())
            assertEquals(setOf(FrameId.profileId(alice, now), fid("intro")), receiver.delivered.map { it.id }.toSet())
            assertEquals(3, spool.liveIds(pairHex).size)

            // Both sessions confirmed and the grace lapsed: the driver stops naming the peer and reports it
            // (`IntroSync.onPairsChanged`, ADR 2026-09.dcah); the pair scope leaves the table on that cue.
            alicePairs = emptyList()
            bobPairs = emptyList()
            sender.sync.onScopeTableChanged()
            receiver.sync.onScopeTableChanged()
            pump()
            assertTrue(
                sender.sync
                    .status()
                    .flatMap { it.scopes }
                    .none { it.scopeHex == pairHex },
            )
            assertTrue(
                receiver.sync
                    .status()
                    .flatMap { it.scopes }
                    .none { it.scopeHex == pairHex },
            )
            sender.sync.stop()
            receiver.sync.stop()
        }

    /**
     * The table derives on its inputs' events (ADR 2026-09.dcah), and a burst of them — every DM session on a
     * phone confirming as it comes back online — is one re-derivation, not one per event: a request that lands
     * while one is queued folds into it, and one that lands while one *runs* queues exactly one more, since the
     * running one may have read its inputs before the change.
     */
    @Test
    fun `a burst of table changes is one queued re-derivation`() =
        runTest {
            val spool = FakeSpool()
            var derivations = 0
            val member =
                member(spool, alice, bob, roots = {
                    derivations++
                    listOf(ScopeRoots(bob, pairwiseRoot))
                })
            member.sync.start(backgroundScope)
            pump()
            assertEquals("the start derives once", 1, derivations)

            repeat(5) { member.sync.onScopeTableChanged() }
            pump()
            assertEquals("five events before any ran are one derivation", 2, derivations)

            pump(rounds = 60)
            assertEquals("the poll is a minute, not fifteen seconds", 3, derivations)
            assertEquals("the device oracle counts the same derivations", 3L, member.metrics.spool().spoolTablesDerived)
            member.sync.stop()
        }

    @Test
    fun `a group frame bridges to another member over the group scope`() =
        runTest {
            val spool = FakeSpool()
            val groupId = "g-00112233445566778899aabb"
            val roster = setOf(alice, bob, carol)
            val group = GroupScopeRoots(groupId, roster, groupRoot, rootVersion = 1)
            val sender = member(spool, alice, bob, groups = listOf(group))
            val receiver = member(spool, bob, alice, groups = listOf(group))
            // A chat from carol that alice merely CARRIES: the bridge is a custody property, not an
            // authorship one, so a third member's frame crosses the Internet through either of the two.
            sender.custody.store(
                groupChatFrame(fid("gc1"), from = carol, groupId = groupId, members = roster.toList(), sentAt = now),
                ForwardStore.ORIGIN_RELAY,
                now,
            )
            sender.custody.store(groupLeaveFrame(fid("gl1"), from = carol, groupId = groupId, sentAt = now), ForwardStore.ORIGIN_RELAY, now)

            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump()

            assertEquals(setOf(fid("gc1"), fid("gl1")), receiver.delivered.map { it.id }.toSet())
            assertEquals(2, spool.liveIds(hex(ScopeCrypto.groupScopeId(groupRoot, groupId, 1))).size)
            sender.sync.stop()
            receiver.sync.stop()
        }

    @Test
    fun `a re-minted root moves the group to a fresh scope and drains the old one`() =
        runTest {
            val spool = FakeSpool()
            val groupId = "g-00112233445566778899aabb"
            val roster = setOf(alice, bob, carol)
            val v1 = hex(ScopeCrypto.groupScopeId(groupRoot, groupId, 1))
            val newRoot = ByteArray(32) { (it + 60).toByte() }
            val v2 = hex(ScopeCrypto.groupScopeId(newRoot, groupId, 2))

            // Post-departure state: the rotated root is live, the old lineage is still inside its drain.
            val rotated =
                GroupScopeRoots(
                    groupId = groupId,
                    roster = roster,
                    root = newRoot,
                    rootVersion = 2,
                    prevRoot = groupRoot,
                    prevRootVersion = 1,
                    prevRootExpiresAt = now + 1_000_000L,
                )
            val holder = member(spool, alice, bob, groups = listOf(rotated))
            holder.custody.store(
                groupChatFrame(fid("gc1"), from = bob, groupId = groupId, members = roster.toList(), sentAt = now),
                ForwardStore.ORIGIN_RELAY,
                now,
            )

            holder.sync.start(backgroundScope)
            pump()

            // §3.3: old blobs are never migrated. The frame is re-sealed under the new keys into a fresh,
            // unlinkable id; the retiring scope is subscribed and healed but never refilled.
            assertEquals(1, spool.liveIds(v2).size)
            assertTrue("the retiring scope is drained, not refilled", spool.liveIds(v1).isEmpty())
            assertTrue(v1 != v2)
            holder.sync.stop()
        }

    /**
     * C-3.1-5 (ADR 2026-10.j67x, #182), end to end over one spool: alice reset the session and bob, away from her
     * radios, is still on the old root. Her retiring scope — the one bob is subscribed to — takes the reset and
     * only the reset; the new scope takes everything of hers; bob pulls the reset out of the scope he holds, and
     * his own old-era DM still drains to her through it.
     */
    @Test
    fun `our unanswered reset is pushed into the scope it replaced, and nothing else of ours is`() =
        runTest {
            val spool = FakeSpool()
            val newRoot = ByteArray(32) { (it + 90).toByte() }
            val eph = ByteArray(32) { 3 }
            val old = hex(ScopeCrypto.dmScopeId(pairwiseRoot, alice, bob))
            val fresh = hex(ScopeCrypto.dmScopeId(newRoot, alice, bob))
            val resetting =
                member(
                    spool,
                    alice,
                    bob,
                    roots = {
                        listOf(
                            ScopeRoots(
                                bob,
                                newRoot,
                                prevPairwiseRoot = pairwiseRoot,
                                prevRootExpiresAt = now + 1_000_000L,
                                pendingResetInit = eph,
                            ),
                        )
                    },
                )
            val init = RatchetInit(eph = eph, pkid = 1, at = now)
            val header = { flags: Int -> RatchetHeader(se = 1, ek = ByteArray(32) { 7 }, pe = 0, n = 0, init = init, flags = flags) }
            val reset = dmFrame(fid("reset"), from = alice, to = bob, sentAt = now, header = header(RatchetHeader.FLAG_RESET))
            val after = dmFrame(fid("after"), from = alice, to = bob, sentAt = now, header = header(0))
            resetting.custody.store(reset, ForwardStore.ORIGIN_SELF, now)
            resetting.custody.store(after, ForwardStore.ORIGIN_SELF, now)
            val away = member(spool, bob, alice)
            away.custody.store(dmFrame(fid("old-era"), from = bob, to = alice, sentAt = now), ForwardStore.ORIGIN_SELF, now)

            resetting.sync.start(backgroundScope)
            away.sync.start(backgroundScope)
            pump()

            fun blob(
                root: ByteArray,
                frame: CarriedFrame,
            ): String {
                val scope =
                    Scope(ScopeCrypto.dmScopeId(root, alice, bob), ScopeCrypto.dmSealKeys(root, alice, bob), ScopeRegistry.DEFAULT_BOUNDS)
                return hex(ScopeFrames.seal(scope, frame.sig, frame.signed).blobId)
            }
            assertTrue("the reset refills the scope bob is on", blob(pairwiseRoot, reset) in spool.liveIds(old))
            assertFalse("and nothing else of alice's does (#90)", blob(pairwiseRoot, after) in spool.liveIds(old))
            assertTrue(
                "everything of alice's goes to the new scope",
                spool.liveIds(fresh).containsAll(listOf(blob(newRoot, reset), blob(newRoot, after))),
            )
            assertTrue("bob pulled the reset", fid("reset") in away.delivered.map { it.id })
            assertTrue("bob's old-era DM still drains to alice", fid("old-era") in resetting.delivered.map { it.id })
            resetting.sync.stop()
            away.sync.stop()
        }

    /**
     * The custody change that carries our reset can wake a round before the table has re-derived: that round
     * sees the old scope still live and the reset not yet pending, and rightly refuses it. The table change that
     * follows keeps every scope id — the old scope only turns retiring and starts carrying the reset — so it must
     * mark that scope itself, or the reset waits out the 60 s tick (found by the chaos sweep on
     * `ResetOverRelayLabTest`, 14 runs in 20).
     */
    @Test
    fun `a scope whose push set changed with the table is healed at once, not on the tick`() =
        runTest {
            val spool = FakeSpool()
            val newRoot = ByteArray(32) { (it + 90).toByte() }
            val eph = ByteArray(32) { 3 }
            val old = hex(ScopeCrypto.dmScopeId(pairwiseRoot, alice, bob))
            var table = listOf(ScopeRoots(bob, pairwiseRoot))
            val member = member(spool, alice, bob, roots = { table })
            member.sync.start(backgroundScope)
            pump(3)

            val header =
                RatchetHeader(
                    se = 1,
                    ek = ByteArray(32) { 7 },
                    pe = 0,
                    n = 0,
                    init = RatchetInit(eph, 1, now),
                    flags = RatchetHeader.FLAG_RESET,
                )
            member.custody.store(
                dmFrame(fid("reset"), from = alice, to = bob, sentAt = now, header = header),
                ForwardStore.ORIGIN_SELF,
                now,
            )
            member.sync.onCustodyChanged() // the round runs on the old table and refuses the reset
            pump(2)
            assertEquals("the stale-table round refused it", 0, spool.liveIds(old).size)

            table =
                listOf(
                    ScopeRoots(bob, newRoot, prevPairwiseRoot = pairwiseRoot, prevRootExpiresAt = now + 1_000_000L, pendingResetInit = eph),
                )
            member.sync.onScopeTableChanged()
            pump(3)
            assertEquals("the retiring scope took the reset without waiting for the tick", 1, spool.liveIds(old).size)
            member.sync.stop()
        }

    @Test
    fun `heal is bidirectional — a member refills a spool that lost everything`() =
        runTest {
            val spool = FakeSpool()
            val holder = member(spool, alice, bob)
            listOf("m1", "m2", "m3").forEach {
                holder.custody.store(dmFrame(fid(it), from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            }

            holder.sync.start(backgroundScope)
            pump()

            // A fresh spool starts empty and heals from whichever member is connected — the "client union
            // is the federation" property, with no spool-to-spool traffic anywhere.
            assertEquals(3, spool.liveIds(scopeHex(alice, bob)).size)
            assertEquals(3, holder.metrics.spool().spoolPushed)
            holder.sync.stop()
        }

    @Test
    fun `both members converge on the same blob ids because the seal is deterministic`() =
        runTest {
            val spool = FakeSpool()
            val one = member(spool, alice, bob)
            val two = member(spool, bob, alice)
            // The same frame in both custodies: a naive random-nonce seal would upload it twice under two
            // ids and the digests would never converge.
            val frame = dmFrame(fid("m1"), from = alice, to = bob, sentAt = now)
            one.custody.store(frame, ForwardStore.ORIGIN_SELF, now)
            two.custody.store(frame, ForwardStore.ORIGIN_RELAY, now)

            one.sync.start(backgroundScope)
            two.sync.start(backgroundScope)
            pump()

            assertEquals("one frame, one blob", 1, spool.liveIds(scopeHex(alice, bob)).size)
            assertEquals(1, spool.pushed.size)
            listOf(one, two).forEach { m ->
                val scope =
                    m.sync
                        .status()
                        .single()
                        .scopes
                        .single()
                assertTrue("digests must agree after a heal round", scope.converged)
                assertEquals(1, scope.localCount)
            }
            one.sync.stop()
            two.sync.stop()
        }

    @Test
    fun `a blob delivered by a live event is not delivered again by the heal round that raced it`() =
        runTest {
            val spool = FakeSpool()
            val sender = member(spool, alice, bob)
            val receiver = member(spool, bob, alice)
            listOf("m1", "m2", "m3").forEach {
                sender.custody.store(dmFrame(fid(it), from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            }

            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump()

            // Events and the pull set race by construction — the pull set is computed before the events
            // land — so the same blob legitimately arrives twice. Re-delivering is harmless (the router's
            // SeenSet dedups) but it would double-count the number Diagnostics shows as messages received.
            assertEquals(3, receiver.delivered.size)
            assertEquals(3, receiver.metrics.spool().spoolBridged)
            assertEquals(3, receiver.metrics.spool().spoolPulled)
            sender.sync.stop()
            receiver.sync.stop()
        }

    @Test
    fun `a frame swept from custody while the spool still holds it is not re-pulled every heal round`() =
        runTest {
            val spool = FakeSpool()
            val holder = member(spool, alice, bob)
            holder.custody.store(dmFrame(fid("m1"), from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            holder.sync.start(backgroundScope)
            pump()
            assertEquals("pushed and converged first", 1, spool.pushed.size)

            // The scope TTL (48 h) deliberately outlives mesh custody (24 h), so a frame we already
            // delivered gets swept locally while the spool keeps it for another day. From then on it is
            // absent from `local` forever and our digest can never match — and re-pulling it achieves
            // nothing, because the custody store refuses it as dead on arrival every single time.
            holder.custody.sweep(fid("m1"))
            pump(rounds = 120) // several 60 s heal ticks
            val afterFirstSweep = spool.pulled.size
            pump(rounds = 120)

            assertEquals("pulled at most once after the sweep", 1, afterFirstSweep)
            assertEquals("and never again", afterFirstSweep, spool.pulled.size)
            holder.sync.stop()
        }

    @Test
    fun `a swept frame the spool still holds is not re-pulled after a reconnect either`() =
        runTest {
            val spool = FakeSpool()
            val holder = member(spool, alice, bob)
            holder.custody.store(dmFrame(fid("m1"), from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            holder.sync.start(backgroundScope)
            pump()
            holder.custody.sweep(fid("m1"))
            pump(rounds = 120)
            val afterSweep = spool.pulled.size
            assertEquals("pulled once after the sweep", 1, afterSweep)

            // ADR 062. The per-connection guard held inside one session and nowhere else, so every
            // reconnect — Doze, a Wi-Fi flip, a spool restart — re-pulled the whole aged band and
            // re-bridged it into delivery. On a lab Pixel that was ~200 undecryptable re-deliveries per
            // reconnect, every 15-20 minutes, with nobody touching the app.
            repeat(3) {
                spool.dropSockets()
                pump(rounds = 120)
            }

            assertEquals("and never again, across three reconnects", afterSweep, spool.pulled.size)
            assertEquals("bridged once, not once per connection", 1, holder.metrics.spool().spoolBridged)
            holder.sync.stop()
        }

    @Test
    fun `a scope the spool outlives converges anyway, on the accounted band`() =
        runTest {
            val spool = FakeSpool()
            val holder = member(spool, alice, bob)
            holder.custody.store(dmFrame(fid("m1"), from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            holder.sync.start(backgroundScope)
            pump()
            holder.custody.sweep(fid("m1"))
            pump(rounds = 120)

            // The whole point of §9.6: a blob custody can never hold again is folded in as if held, so the
            // two digests agree and the round goes quiet. Before it, this scope sat `converged = false`
            // for the second half of the spool's retention and LISTed on every tick to prove it.
            val scope =
                holder.sync
                    .status()
                    .single()
                    .scopes
                    .single { it.scopeHex == scopeHex(alice, bob) }
            assertTrue("the aged band is accounted, so the digests agree", scope.converged)
            assertEquals("and it is counted, so local == spool still reads as converged", scope.spoolCount, scope.localCount)
            assertEquals(1, scope.accountedCount)
            assertEquals(1, holder.metrics.spool().spoolAccounted)
            holder.sync.stop()
        }

    @Test
    fun `an accounted blob the spool finally expires leaves our digest with it`() =
        runTest {
            val spool = FakeSpool()
            val holder = member(spool, alice, bob)
            holder.custody.store(dmFrame(fid("m1"), from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            holder.sync.start(backgroundScope)
            pump()
            val blobId = spool.pushed.single()
            holder.custody.sweep(fid("m1"))
            pump(rounds = 120)

            // The mirror of the bug: an accounted id the spool has since aged out would leave our fold
            // carrying something the spool no longer counts — permanent divergence, the other way round.
            spool.expire(scopeHex(alice, bob), blobId)
            pump(rounds = 120)

            val scope =
                holder.sync
                    .status()
                    .single()
                    .scopes
                    .single { it.scopeHex == scopeHex(alice, bob) }
            assertEquals("pruned once the listing stopped naming it", 0, scope.accountedCount)
            assertTrue("and an empty scope converges on an empty fold", scope.converged)
            holder.sync.stop()
        }

    @Test
    fun `a spool that rejects the connection reports why, instead of just looking disconnected`() =
        runTest {
            // A spool with a token configured closes 4001 before saying anything. Without the close code
            // reaching the status, that is indistinguishable from "not connected yet" — which is exactly
            // how a wrong/missing `?k=` token presents in the field.
            val rejecting =
                object : SpoolDialer {
                    override suspend fun dial(url: String): SpoolSocket =
                        object : SpoolSocket {
                            private val ch = Channel<ByteArray>(Channel.UNLIMITED).also { it.close() }

                            override val incoming get() = ch
                            override val closeReason = "close 4001 auth"

                            override fun send(bytes: ByteArray) = false

                            override fun close(
                                code: Int,
                                reason: String,
                            ) = Unit
                        }
                }
            val member =
                member(FakeSpool(), alice, bob).let { base ->
                    ScopeSync(
                        registry = ScopeRegistry({ alice }, { listOf(ScopeRoots(bob, pairwiseRoot)) }),
                        dialer = rejecting,
                        store = base.custody,
                        selfId = { alice },
                        urls = { listOf(url) },
                        canCarry = { _, _ -> true },
                        deliver = { _, _, _ -> },
                        clock = { now },
                        jitter = { 0L },
                    )
                }

            member.start(backgroundScope)
            pump()

            val status = member.status().single()
            assertFalse(status.connected)
            assertEquals("close 4001 auth", status.lastError)
            member.stop()
        }

    @Test
    fun `a spool that answers Retry-After is not dialled again before it asked`() =
        runTest {
            // A spool at its connection cap refuses the upgrade (503) and says how long to stay away.
            // The default backoff would dial ~7 times in the first two minutes, and each of those costs
            // its reverse proxy a full TLS handshake for a refusal the daemon makes for free.
            val refused = DialLog(retryAfterMs = 30_000L)
            val plain = DialLog(retryAfterMs = null)
            for (log in listOf(refused, plain)) {
                val sync = busyRelay(log)
                sync.start(backgroundScope)
                pump(rounds = 200)
                sync.stop()
            }

            val gaps = refused.at.zipWithNext { a, b -> b - a }
            // A floor, not a replacement, and the jitter survives it: the first wait is the spool's 30 s
            // winning over the backoff, plus the fixed jitter this test injects.
            assertEquals("honoured the spool's ask over the initial backoff", 30_250L, gaps.first())
            assertTrue("never dialled sooner than asked", gaps.all { it >= 30_000L })
            // Once the exponential backoff overtakes the floor it keeps growing — a floor that pinned
            // every wait at 30 s would make a long outage *more* expensive than doing nothing.
            assertTrue("the backoff still grows underneath", gaps.any { it > 30_250L })
            assertEquals("the control run keeps its own first retry", 2_250L, plain.at.zipWithNext { a, b -> b - a }.first())
            assertTrue("and dials more often for it", plain.at.size > refused.at.size)
        }

    @Test
    fun `a socket that will not open at all is reported as unreachable`() =
        runTest {
            val dead =
                object : SpoolDialer {
                    override suspend fun dial(url: String): SpoolSocket? = null
                }
            val member =
                ScopeSync(
                    registry = ScopeRegistry({ alice }, { listOf(ScopeRoots(bob, pairwiseRoot)) }),
                    dialer = dead,
                    store = FakeCustody(),
                    selfId = { alice },
                    urls = { listOf(url) },
                    canCarry = { _, _ -> true },
                    deliver = { _, _, _ -> },
                    clock = { now },
                    jitter = { 0L },
                )

            member.start(backgroundScope)
            pump()

            assertEquals(ScopeSync.UNREACHABLE, member.status().single().lastError)
            member.stop()
        }

    /**
     * A socket that opens and then dies before any hello, the way a validated-but-dead Wi-Fi ends every
     * dial: open for [aliveMs], then closed with the dialer's `unreachable` verdict. Records when each
     * dial happened and when each socket died, so the reconnect backoff is observable.
     */
    @Test
    fun `an idle converged relay reads custody once per tick, not per reconcile`() =
        runTest {
            // The 15 s reconcile used to wake every worker whether or not its scope table changed, and each
            // wake ran a full custody read per scope: four rounds a minute on a converged, idle relay.
            val spool = FakeSpool()
            val member = member(spool, alice, bob)
            member.custody.store(dmFrame(fid("m1"), from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            member.sync.start(backgroundScope)
            pump(rounds = 8)
            assertTrue(
                member.sync
                    .status()
                    .single()
                    .scopes
                    .single()
                    .converged,
            )

            val reads = member.custody.liveFramesReads
            pump(rounds = 120) // two ticks; eight of the old reconcile wakes
            assertEquals("one custody read per 60 s tick", reads + 2, member.custody.liveFramesReads)
            member.sync.stop()
        }

    @Test
    fun `one custody read serves every scope in a round`() =
        runTest {
            val spool = FakeSpool()
            val group = GroupScopeRoots("g-00112233445566778899aabb", setOf(alice, bob, carol), groupRoot, rootVersion = 1)
            val member = member(spool, alice, bob, groups = listOf(group))
            member.sync.start(backgroundScope)
            pump(rounds = 8)
            assertEquals(
                2,
                member.sync
                    .status()
                    .single()
                    .scopes.size,
            )

            val reads = member.custody.liveFramesReads
            pump(rounds = 60) // one tick, two scopes
            assertEquals("the round reads custody once, not once per scope", reads + 1, member.custody.liveFramesReads)
            member.sync.stop()
        }

    @Test
    fun `an unchanged digest does not wake the heal, a changed one heals at once`() =
        runTest {
            val spool = FakeSpool()
            val member = member(spool, alice, bob)
            member.custody.store(dmFrame(fid("m1"), from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            member.sync.start(backgroundScope)
            pump(rounds = 8)
            val reads = member.custody.liveFramesReads

            // The spool's unsolicited fan-out repeats an anchor we already hold: nothing to look at.
            spool.announce(scopeHex(alice, bob))
            pump(rounds = 3)
            assertEquals("a repeated digest is not a reason to heal", reads, member.custody.liveFramesReads)

            // A digest that moved is — the round runs on the digest, not on the tick.
            spool.expire(scopeHex(alice, bob), spool.liveIds(scopeHex(alice, bob)).single())
            pump(rounds = 3)
            assertTrue("a moved digest heals inside the tick", member.custody.liveFramesReads > reads)
            member.sync.stop()
        }

    @Test
    fun `a dead relay backs off to fifteen minutes and a new network starts it over`() =
        runTest {
            // ~1,440 dials a day at the old 60 s ceiling, each a DNS lookup, a TCP connect and a TLS handshake
            // on a radio that could sleep; the long tier makes it ~100 and a route change still dials at once.
            val relay = DeadRelay(this)
            val sync = lone(relay)
            sync.start(backgroundScope)
            pump(rounds = 3_200)

            val gaps = relay.gaps()
            assertEquals("the status names when the last dial began", now, sync.status().single().lastDialAt)
            assertEquals("the first tier is the curve it always was", listOf(2_000L, 4_000L, 8_000L), gaps.take(3))
            assertTrue("the first tier's ceiling is a minute", 60_000L in gaps)
            assertTrue("then the long tier doubles on", gaps.containsAll(listOf(120_000L, 240_000L, 480_000L)))
            assertEquals("to a quarter of an hour", 900_000L, gaps.last())

            val before = relay.dialedAt.size
            sync.onRouteChanged()
            pump(rounds = 1)
            assertEquals("a new network dials at once", before + 1, relay.dialedAt.size)
            pump(rounds = 3)
            assertEquals("and the backoff is the first step again", 2_000L, relay.gaps().last())
            sync.stop()
        }

    @Test
    fun `Retry-After still floors the long tier`() =
        runTest {
            val log = DialLog(retryAfterMs = 30_000L)
            val sync = busyRelay(log)
            sync.start(backgroundScope)
            pump(rounds = 3_600)
            sync.stop()

            val gaps = log.at.zipWithNext { a, b -> b - a }
            assertEquals(30_250L, gaps.first())
            assertTrue("never sooner than the spool asked", gaps.all { it >= 30_000L })
            assertEquals("and the long tier runs above the floor", 900_250L, gaps.last())
        }

    @Test
    fun `no route means no dial until one appears`() =
        runTest {
            // A dial with no validated route can only fail, and would count against a relay that did nothing.
            val relay = DeadRelay(this)
            var online = false
            val sync = lone(relay, online = { online })
            sync.start(backgroundScope)
            pump(rounds = 90)
            assertTrue("dialled with no route: ${relay.dialedAt}", relay.dialedAt.isEmpty())
            assertNull("no verdict was invented", sync.status().single().lastError)
            assertNull("and no dial was stamped", sync.status().single().lastDialAt)

            online = true
            sync.onRouteChanged()
            pump(rounds = 1)
            assertEquals("the new route is dialled at once", 1, relay.dialedAt.size)
            sync.stop()
        }

    @Test
    fun `an offline phone keeps the relay's last verdict`() =
        runTest {
            val relay = DeadRelay(this)
            var online = true
            val sync = lone(relay, online = { online })
            sync.start(backgroundScope)
            pump(rounds = 2)
            assertEquals(ScopeSync.UNREACHABLE, sync.status().single().lastError)
            val dials = relay.dialedAt.size

            online = false
            pump(rounds = 300)
            assertEquals("no dial while offline", dials, relay.dialedAt.size)
            assertEquals("the verdict is the relay's, not the phone's", ScopeSync.UNREACHABLE, sync.status().single().lastError)
            sync.stop()
        }

    private inner class BlackHole(
        private val scope: TestScope,
        private val aliveMs: Long,
    ) : SpoolDialer {
        val dialedAt = mutableListOf<Long>()
        val diedAt = mutableListOf<Long>()

        override suspend fun dial(url: String): SpoolSocket {
            dialedAt += scope.testScheduler.currentTime
            val ch = Channel<ByteArray>(Channel.UNLIMITED)
            var dead = false
            scope.backgroundScope.launch {
                delay(aliveMs)
                dead = true
                diedAt += scope.testScheduler.currentTime
                ch.close()
            }
            return object : SpoolSocket {
                override val incoming get() = ch
                override val closeReason get() = if (dead) ScopeSync.UNREACHABLE else null

                override fun send(bytes: ByteArray) = !dead

                override fun close(
                    code: Int,
                    reason: String,
                ) {
                    ch.close()
                }
            }
        }

        /** Reconnect waits, in ms: each dial minus the death of the socket before it. */
        fun gaps(): List<Long> = dialedAt.drop(1).mapIndexed { i, at -> at - diedAt[i] }
    }

    private fun TestScope.lone(
        dialer: SpoolDialer,
        urls: List<String> = listOf(url),
        online: () -> Boolean = { true },
        field: FieldRecorder = FieldRecorder.NONE,
    ): ScopeSync =
        ScopeSync(
            registry = ScopeRegistry({ alice }, { listOf(ScopeRoots(bob, pairwiseRoot)) }),
            dialer = dialer,
            store = FakeCustody(),
            selfId = { alice },
            urls = { urls },
            canCarry = { _, _ -> true },
            deliver = { _, _, _ -> },
            online = online,
            clock = { now },
            jitter = { 0L },
            field = field,
        )

    /** What a member wrote to the field log, in order. */
    private class FieldLines : FieldRecorder {
        private val events = mutableListOf<FieldEvent>()

        override fun record(event: FieldEvent) {
            synchronized(events) { events += event }
        }

        fun <T : FieldEvent> of(kind: Class<T>): List<T> = synchronized(events) { events.filterIsInstance(kind) }
    }

    @Test
    fun `the field log keeps a spool's hello and how its session ended, under the relay's redacted url`() =
        runTest {
            val spool = FakeSpool()
            val lines = FieldLines()
            val holder = member(spool, alice, bob, urls = listOf("$url?k=secret"), field = lines)

            holder.sync.start(backgroundScope)
            pump()
            val up = lines.of(FieldEvent.SpoolUp::class.java).single()
            assertEquals(url, up.relay)
            assertEquals(0, up.fails)
            assertTrue("this spool carries attachments", up.attachments)

            spool.dropSockets()
            pump(rounds = 30)
            val down = lines.of(FieldEvent.SpoolDown::class.java).single()
            assertEquals(url, down.relay)
            assertEquals("the spool's end, not ours", SpoolVerdict.NONE, down.reason)
            assertFalse(down.ours)
            assertEquals("a dropped session reconnects and says hello again", 2, lines.of(FieldEvent.SpoolUp::class.java).size)
            assertEquals(emptyList<FieldEvent.SpoolFail>(), lines.of(FieldEvent.SpoolFail::class.java))
            holder.sync.stop()
        }

    @Test
    fun `the field log keeps each dial that never reached a hello, with its streak`() =
        runTest {
            val lines = FieldLines()
            val member = lone(DeadRelay(this), field = lines)

            member.start(backgroundScope)
            pump(rounds = 20)
            val fails = lines.of(FieldEvent.SpoolFail::class.java)
            assertTrue("dialled more than twice: ${fails.size}", fails.size >= 3)
            assertEquals(listOf(SpoolVerdict.UNREACHABLE), fails.map { it.reason }.distinct())
            assertEquals((1..fails.size).toList(), fails.map { it.streak })
            assertEquals(emptyList<FieldEvent.SpoolUp>(), lines.of(FieldEvent.SpoolUp::class.java))
            member.stop()
        }

    @Test
    fun `a refused upgrade is logged with its status and the spool's retry-after`() =
        runTest {
            val busy =
                object : SpoolDialer {
                    override suspend fun dial(url: String): SpoolSocket =
                        object : SpoolSocket {
                            private val ch = Channel<ByteArray>(Channel.UNLIMITED).also { it.close() }

                            override val incoming get() = ch
                            override val closeReason = "http 503"
                            override val retryAfterMs = 30_000L

                            override fun send(bytes: ByteArray) = false

                            override fun close(
                                code: Int,
                                reason: String,
                            ) = Unit
                        }
                }
            val lines = FieldLines()
            val member = lone(busy, field = lines)

            member.start(backgroundScope)
            pump(rounds = 2)
            val fail = lines.of(FieldEvent.SpoolFail::class.java).first()
            assertEquals(SpoolVerdict.HTTP, fail.reason)
            assertEquals(503, fail.code)
            assertEquals(30_000L, fail.retryAfterMs)
            assertEquals(1, fail.streak)
            member.stop()
        }

    /** A relay nobody answers at: every dial is the dialer's `unreachable` verdict, logged by the clock. */
    private inner class DeadRelay(
        private val scope: TestScope,
    ) : SpoolDialer {
        val dialedAt = mutableListOf<Long>()

        override suspend fun dial(url: String): SpoolSocket? {
            dialedAt += scope.testScheduler.currentTime
            return null
        }

        override suspend fun fetchSoftware(url: String): SpoolSoftware? = null

        fun gaps(): List<Long> = dialedAt.zipWithNext { a, b -> b - a }
    }

    @Test
    fun `a socket that dies before the hello is unreachable and never counts as connected`() =
        runTest {
            // Work item 50: the P7 sat on a public Wi-Fi Android had validated that black-holed the relay
            // host. Each dial held a socket for the whole connect timeout, and "a socket exists" was what
            // the status called connected — so the plane, the header and every coverage rule said live
            // about a relay that had never said a word.
            val route = BlackHole(this, aliveMs = 1_500L)
            val member = lone(route)

            member.start(backgroundScope)
            repeat(40) {
                pump(rounds = 1)
                assertFalse("connected at t=${testScheduler.currentTime}", member.status().single().connected)
            }

            val status = member.status().single()
            assertEquals(ScopeSync.UNREACHABLE, status.lastError)
            assertTrue("failures keep counting: ${status.dialFailures}", status.dialFailures >= 3)
            // Still a failed session each time, so the backoff still grows rather than dialling once a second.
            assertEquals(listOf(2_000L, 4_000L, 8_000L), route.gaps().take(3))
            member.stop()
        }

    @Test
    fun `a socket that opens and never says hello is dropped as no_hello, and a real hello clears it`() =
        runTest {
            // A proxy that accepts the upgrade and forwards it nowhere. Before, the handshake timeout left
            // `lastError` untouched, so the row read "Connecting…" for as long as the condition lasted.
            var silent = true
            val spool = FakeSpool()
            val dialer =
                object : SpoolDialer {
                    override suspend fun dial(url: String): SpoolSocket {
                        if (!silent) return spool.dial(url)
                        val ch = Channel<ByteArray>(Channel.UNLIMITED)
                        return object : SpoolSocket {
                            override val incoming get() = ch

                            override fun send(bytes: ByteArray) = true

                            override fun close(
                                code: Int,
                                reason: String,
                            ) {
                                ch.close()
                            }
                        }
                    }
                }
            val member = lone(dialer)

            member.start(backgroundScope)
            repeat(19) {
                pump(rounds = 1)
                assertFalse(member.status().single().connected)
            }
            pump(rounds = 3) // past the 20 s handshake timeout
            val dropped = member.status().single()
            assertFalse(dropped.connected)
            assertEquals(ScopeSync.NO_HELLO, dropped.lastError)
            assertEquals(1, dropped.dialFailures)

            silent = false
            // The dial already in flight is a second silent socket: it sits out its own 20 s, then the
            // grown backoff, and only the dial after that meets a spool.
            pump(rounds = 30)
            val back = member.status().single()
            assertTrue("reconnected to a spool that says hello", back.connected)
            assertNull("a completed hello is the whole condition, no request needed", back.lastError)
            assertEquals(0, back.dialFailures)
            member.stop()
        }

    @Test
    fun `a new default network re-dials a failing relay at once and starts its backoff over`() =
        runTest {
            // Leaving the Wi-Fi that swallowed the socket used to recover only at the end of whatever
            // backoff the dead route had earned — up to a minute — while the phone already had a good one.
            val route = BlackHole(this, aliveMs = 1_500L)
            val member = lone(route)

            member.start(backgroundScope)
            pump(rounds = 12) // dials at 0, 3.5 and 9 s; the third socket died at 10.5 s, the next dial is due at 18.5 s
            assertEquals(3, route.dialedAt.size)

            member.onRouteChanged()
            pump(rounds = 1)
            assertEquals("dialled on the nudge, not at the end of the 8 s wait", 12_000L, route.dialedAt.last())

            pump(rounds = 6)
            // That dial failed too, and the backoff that follows it is the *first* step again, not the fourth.
            assertEquals(2_000L, route.gaps().last())
            member.stop()
        }

    @Test
    fun `a route change never shortens a spool's Retry-After`() =
        runTest {
            // A spool at its connection cap said 30 s. A new network changes nothing about its load, so the
            // nudge that shortens our own backoff leaves its ask exactly where it was.
            val log = DialLog(retryAfterMs = 30_000L)
            val sync = busyRelay(log)

            sync.start(backgroundScope)
            pump(rounds = 5)
            sync.onRouteChanged()
            pump(rounds = 40)

            assertEquals("the second dial waited out the floor", 30_250L, log.at[1] - log.at[0])
            sync.stop()
        }

    @Test
    fun `a route change while a relay is connected does not cost it an extra dial later`() =
        runTest {
            // A worker with a live session ignores the nudge; and when that session later ends on the
            // spool's terms, the reconnect is the ordinary first backoff, not an instant re-dial with the
            // backoff reset by a token that sat in the channel for the whole session.
            val spool = FakeSpool()
            val log = SessionLog(spool, testScheduler)
            val member = member(spool, alice, bob, dialer = log)

            member.sync.start(backgroundScope)
            pump()
            assertTrue(
                member.sync
                    .status()
                    .single()
                    .connected,
            )
            member.sync.onRouteChanged()
            pump()
            assertEquals("no re-dial while connected", 1, log.dialedAt.size)

            spool.dropSockets()
            pump(rounds = 5)
            assertEquals(2, log.dialedAt.size)
            assertEquals("a reached session reconnects after MIN_BACKOFF, untouched by the stale nudge", 1_000L, log.gaps().single())
            member.sync.stop()
        }

    @Test
    fun `a frames-only relay that is up and a photo relay that is dead read as exactly that`() =
        runTest {
            // The field shape behind work item 50 and the chat's loading hint: two relays configured, the
            // one that carries photos black-holed, the frames-only one fine. The status must show one
            // connected relay with no attachment budget and one unreachable — not two connected relays.
            val framesOnly = FakeSpool(attachments = false)
            val photos = "ws://photos.test/spool/v1"
            val dead = BlackHole(this, aliveMs = 1_500L)
            val dialer =
                object : SpoolDialer {
                    override suspend fun dial(url: String): SpoolSocket = if (url == photos) dead.dial(url) else framesOnly.dial(url)
                }
            val member = lone(dialer, urls = listOf(url, photos))

            member.start(backgroundScope)
            pump(rounds = 20)

            val byUrl = member.status().associateBy { it.url }
            val up = byUrl.getValue(url)
            assertTrue(up.connected)
            assertNull("a frames-only relay advertises no budget", up.maxAttachBytes)
            assertNull(up.lastError)
            val down = byUrl.getValue(photos)
            assertFalse(down.connected)
            assertEquals(ScopeSync.UNREACHABLE, down.lastError)
            assertTrue(down.dialFailures >= 2)
            member.stop()
        }

    @Test
    fun `no more scopes are subscribed than the spool's advertised maxScopes`() =
        runTest {
            val spool = FakeSpool(maxScopes = 1)
            val group = GroupScopeRoots("g-00112233445566778899aabb", setOf(alice, bob, carol), groupRoot, rootVersion = 1)
            val holder = member(spool, alice, bob, groups = listOf(group))

            holder.sync.start(backgroundScope)
            pump(rounds = 70)

            assertEquals("one scope, however many we carry", 1, spool.subscribedScopes.size)
            assertNull(
                "and no refusal to show for it",
                holder.sync
                    .status()
                    .single()
                    .lastError,
            )
            holder.sync.stop()
        }

    @Test
    fun `a scope the spool refused is parked, not asked for again every tick`() =
        runTest {
            // The daemon's cap is spool-wide, so the client cannot know in advance which SUB will be the
            // one over it: the refusal is the signal, and it must not be re-provoked on every 60 s tick.
            val spool = FakeSpool(scopeQuota = 1)
            val group = GroupScopeRoots("g-00112233445566778899aabb", setOf(alice, bob, carol), groupRoot, rootVersion = 1)
            // The park is measured on the member's own clock, so this one has to move with virtual time.
            val holder = member(spool, alice, bob, groups = listOf(group), clock = { now + testScheduler.currentTime })

            holder.sync.start(backgroundScope)
            pump(rounds = 200)
            val refused = spool.subAttempts.keys.single { it !in spool.subscribedScopes }
            assertEquals("asked once, then parked", 1, spool.subAttempts[refused])
            assertEquals(
                SpoolErrCode.QUOTA,
                holder.sync
                    .status()
                    .single()
                    .lastError,
            )

            pump(rounds = 200) // past the 5 min park: one more try, then parked again
            assertEquals(2, spool.subAttempts[refused])
            holder.sync.stop()
        }

    @Test
    fun `a refusal the spool repeats in one session is one field log line`() =
        runTest {
            val spool = FakeSpool(scopeQuota = 1)
            val group = GroupScopeRoots("g-00112233445566778899aabb", setOf(alice, bob, carol), groupRoot, rootVersion = 1)
            val lines = FieldLines()
            val holder = member(spool, alice, bob, groups = listOf(group), clock = { now + testScheduler.currentTime }, field = lines)

            holder.sync.start(backgroundScope)
            pump(rounds = 400) // refused, parked, asked again past the 5 min park and refused again
            val refused = spool.subAttempts.keys.single { it !in spool.subscribedScopes }
            assertEquals("the spool refused it twice", 2, spool.subAttempts[refused])
            assertEquals(listOf(SpoolVerdict.QUOTA), lines.of(FieldEvent.SpoolErr::class.java).map { it.code })
            holder.sync.stop()
        }

    @Test
    fun `a spool's retryMs on a refusal is a hint inside our own window, never a permanent park`() =
        runTest {
            val spool = FakeSpool(scopeQuota = 1, quotaRetryMs = 10L * 24 * 60 * 60_000L)
            val group = GroupScopeRoots("g-00112233445566778899aabb", setOf(alice, bob, carol), groupRoot, rootVersion = 1)
            val holder = member(spool, alice, bob, groups = listOf(group), clock = { now + testScheduler.currentTime })

            holder.sync.start(backgroundScope)
            pump(rounds = 3700) // just past the one-hour cap
            val refused = spool.subAttempts.keys.single { it !in spool.subscribedScopes }

            assertEquals("ten days asked for, one hour granted", 2, spool.subAttempts[refused])
            holder.sync.stop()
        }

    @Test
    fun `a spool whose maxRecord cannot carry a sub is dropped and reported, not shown connected forever`() =
        runTest {
            // Large enough for our 12-byte hello reply — the handshake passes — and too small for a SUB.
            val spool = FakeSpool(maxRecord = 32)
            val log = SessionLog(spool, testScheduler)
            val holder = member(spool, alice, bob, dialer = log)

            holder.sync.start(backgroundScope)
            pump(rounds = 12)

            assertEquals(
                SpoolErrCode.TOO_LARGE,
                holder.sync
                    .status()
                    .single()
                    .lastError,
            )
            assertFalse(
                holder.sync
                    .status()
                    .single()
                    .connected,
            )
            assertTrue(spool.subscribedScopes.isEmpty())
            // A session we had to abort is not a reached one: the reconnect backoff grows.
            assertEquals(listOf(2_000L, 4_000L), log.gaps().take(2))
            holder.sync.stop()
        }

    @Test
    fun `a spool that goes quiet after the handshake is dropped after three unanswered requests`() =
        runTest {
            val spool = FakeSpool()
            spool.mute()
            val log = SessionLog(spool, testScheduler)
            val holder = member(spool, alice, bob, dialer = log)
            // Something to ask about, so each heal round issues a request the spool then swallows.
            spool.plantGarbage(scopeHex(alice, bob), "never served".toByteArray())

            holder.sync.start(backgroundScope)
            // An unanswered LIST re-marks its scope, so the heal loop keeps asking; each LIST times out at
            // 30 s, so the third strike lands at 90 s and the redial two seconds later.
            pump(rounds = 120)

            assertEquals("the first socket was dropped and a second dialled", 2, log.dialedAt.size)
            assertTrue(spool.swallowed.count { it == SpoolRecordType.LIST } >= 3)
            assertEquals("and not dialled again a second later", 2_000L, log.gaps().single())
            // Still the diagnosis while the second session runs: the spool answered the hello again, but
            // "connected" would be the lie the relay row told for the whole first session.
            assertEquals(
                SpoolConnection.UNRESPONSIVE,
                holder.sync
                    .status()
                    .single()
                    .lastError,
            )

            // Until it demonstrably talks again — one answered request, and the verdict is retired.
            spool.unmute()
            pump(rounds = 70)
            assertNull(
                holder.sync
                    .status()
                    .single()
                    .lastError,
            )
            assertEquals("its garbage was pulled once the spool answered", 1, holder.metrics.spool().spoolInvalid)
            holder.sync.stop()
        }

    @Test
    fun `a garbage blob at the spool is quarantined once, never delivered, never re-pulled`() =
        runTest {
            val spool = FakeSpool()
            val victim = member(spool, bob, alice)
            spool.plantGarbage(scopeHex(bob, alice), "not a sealed frame".toByteArray())

            victim.sync.start(backgroundScope)
            pump()
            val afterFirst = victim.metrics.spool().spoolInvalid
            pump()

            assertTrue("the garbage must be quarantined", afterFirst >= 1)
            assertTrue("nothing forged is ever delivered", victim.delivered.isEmpty())
            assertEquals("a quarantined id is never re-pulled", afterFirst, victim.metrics.spool().spoolInvalid)
            assertEquals(0, victim.metrics.spool().spoolBridged)
            victim.sync.stop()
        }

    @Test
    fun `a blob over the bound we declared at sub is quarantined, never buffered`() =
        runTest {
            val spool = FakeSpool()
            val victim = member(spool, bob, alice)
            // Bigger than the `maxBlob` this scope declares at SUB, so it can never be a frame we hold —
            // but the spool still lists it, so merely dropping it would diverge the digests forever.
            val id = spool.plantGarbage(scopeHex(bob, alice), ByteArray(200_000))

            victim.sync.start(backgroundScope)
            pump()
            val afterFirst = victim.metrics.spool().spoolInvalid
            pump()

            assertTrue("an oversize answer must still be accounted", afterFirst >= 1)
            assertTrue(victim.delivered.isEmpty())
            assertEquals("a quarantined id is never re-pulled", afterFirst, victim.metrics.spool().spoolInvalid)
            assertEquals("...so it is asked for exactly once", 1, spool.pulled.count { it == id })
            victim.sync.stop()
        }

    @Test
    fun `an oversize live event never reaches the quarantine set`() =
        runTest {
            val spool = FakeSpool()
            val victim = member(spool, bob, alice)
            val scope = unhex(scopeHex(bob, alice))

            victim.sync.start(backgroundScope)
            pump()
            // An event is unsolicited by design, so nothing correlates it — without a size gate a flood
            // of these evicts genuine entries from the bounded per-scope sets `accept` writes.
            repeat(20) { n ->
                spool.gossip(
                    SpoolCodec.encode(
                        SpoolEvent(
                            t = SpoolRecordType.EVENT,
                            scope = scope,
                            blobId = sha256(byteArrayOf(n.toByte())),
                            data = ByteArray(200_000),
                        ),
                    ),
                )
            }
            pump()

            assertEquals("an oversize event is not ours to quarantine", 0, victim.metrics.spool().spoolInvalid)
            assertTrue(victim.delivered.isEmpty())
            victim.sync.stop()
        }

    @Test
    fun `a blob whose sender fails the mesh carry gate is quarantined, not delivered`() =
        runTest {
            val spool = FakeSpool()
            val sender = member(spool, alice, bob)
            val receiver = member(spool, bob, alice, carryGate = { _, _ -> false })
            sender.custody.store(dmFrame(fid("m1"), from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)

            // Pushed before the receiver connects, so m1 reaches it by SUB → LIST → pull: the pull path is
            // the one C-9.3-1 quarantines on. (Arriving as a live event instead, it is released and dropped.)
            sender.sync.start(backgroundScope)
            pump()
            receiver.sync.start(backgroundScope)
            pump()

            assertTrue(receiver.delivered.isEmpty())
            assertFalse(receiver.custody.has(fid("m1")))
            assertTrue(receiver.metrics.spool().spoolInvalid >= 1)
            sender.sync.stop()
            receiver.sync.stop()
        }

    @Test
    fun `a blob whose frame id is not canonical is quarantined, never accounted and never pulled again`() =
        runTest {
            // #178: on Android the twin's id binds as the real one's, so had it reached custody, `store.has` would answer
            // for the real frame, §9.6 would never account the blob and every connection would pull it again. It is
            // refused at the open instead, before the carry gate, and quarantined.
            val spool = FakeSpool()
            val sender = member(spool, alice, bob)
            val receiver = member(spool, bob, alice)
            sender.custody.store(dmFrame("\uFEFF" + fid("m1"), from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            sender.custody.store(dmFrame(fid("m1"), from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            sender.sync.start(backgroundScope)
            pump()
            sender.sync.stop()

            receiver.sync.start(backgroundScope)
            pump()
            assertEquals("each blob pulled once", 2, spool.pulled.size)
            assertEquals(listOf(fid("m1")), receiver.delivered.map { it.id })
            assertEquals(1, receiver.metrics.spool().spoolInvalid)
            assertEquals("only the real frame bridged", 1, receiver.metrics.spool().spoolBridged)
            assertEquals("the twin is never accounted", 0, receiver.metrics.spool().spoolAccounted)

            repeat(3) {
                spool.dropSockets()
                pump(rounds = 120)
            }
            assertEquals("the quarantine outlives every reconnect", 2, spool.pulled.size)
            assertEquals(listOf(fid("m1")), receiver.delivered.map { it.id })
            receiver.sync.stop()
        }

    @Test
    fun `a garbage live event is dropped, not quarantined — the listing that names it is what quarantines it`() =
        runTest {
            val spool = FakeSpool()
            val victim = member(spool, bob, alice)
            val scope = unhex(scopeHex(bob, alice))
            val garbage = "not a sealed frame".toByteArray()
            val id = hex(sha256(garbage))

            victim.sync.start(backgroundScope)
            pump()
            // Under the size gate, so it reaches `accept` and fails there. C-9.3-1 is written for a
            // *pulled* blob: an id nobody asked for cannot start the re-pull loop the invalid set stops,
            // and letting it in would hand the spool a way to evict the entries that matter.
            spool.gossip(SpoolCodec.encode(SpoolEvent(t = SpoolRecordType.EVENT, scope = scope, blobId = unhex(id), data = garbage)))
            pump()
            assertEquals("an unsolicited failure is not ours to quarantine", 0, victim.metrics.spool().spoolInvalid)
            assertEquals(
                0,
                victim.sync
                    .status()
                    .single()
                    .scopes
                    .single()
                    .invalidCount,
            )

            // The spool really holds it: the listing names it, the pull path quarantines it — once.
            spool.plantGarbage(scopeHex(bob, alice), garbage)
            spool.announce(scopeHex(bob, alice))
            pump()
            pump()

            assertEquals(1, victim.metrics.spool().spoolInvalid)
            assertEquals("asked for exactly once", 1, spool.pulled.count { it == id })
            assertTrue(victim.delivered.isEmpty())
            victim.sync.stop()
        }

    @Test
    fun `a hostile event for a genuine id does not block the pull of that id`() =
        runTest {
            val spool = FakeSpool()
            val victim = member(spool, bob, alice)
            val frame = dmFrame(fid("m1"), from = alice, to = bob, sentAt = now)
            val blob =
                ScopeCrypto.seal(
                    ScopeCrypto.dmSealKeys(pairwiseRoot, alice, bob),
                    ScopeCrypto.dmScopeId(pairwiseRoot, alice, bob),
                    frame.sig,
                    frame.signed,
                )
            val id = hex(ScopeCrypto.blobId(blob))
            val scope = unhex(scopeHex(bob, alice))

            victim.sync.start(backgroundScope)
            pump()
            // The event claims m1's id with bytes that do not open. If the claim were kept — or the id
            // quarantined — the genuine blob behind that id could never be pulled on this connection.
            spool.gossip(
                SpoolCodec.encode(SpoolEvent(t = SpoolRecordType.EVENT, scope = scope, blobId = unhex(id), data = ByteArray(64) { 9 })),
            )
            pump()
            spool.plantGarbage(scopeHex(bob, alice), blob) // "garbage" to the fake; a real sealed frame to bob
            spool.announce(scopeHex(bob, alice))
            pump()

            assertEquals(listOf(fid("m1")), victim.delivered.map { it.id })
            assertEquals(1, victim.metrics.spool().spoolBridged)
            assertEquals(0, victim.metrics.spool().spoolInvalid)
            victim.sync.stop()
        }

    @Test
    fun `an event flood cannot evict a delivered id from the per-connection guard`() =
        runTest {
            val spool = FakeSpool()
            val sender = member(spool, alice, bob)
            val receiver = member(spool, bob, alice)
            val scope = unhex(scopeHex(bob, alice))
            sender.custody.store(dmFrame(fid("m1"), from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            sender.sync.start(backgroundScope)
            pump()
            receiver.sync.start(backgroundScope)
            pump()
            assertEquals(1, receiver.metrics.spool().spoolBridged)

            // More garbage than the guard holds. Every one of these claims a slot and fails; if a claim
            // could evict, m1 would be pushed out and — once custody sweeps it — pulled and bridged again.
            repeat(600) { n ->
                val junk = ByteArray(32) { (it + n).toByte() }
                spool.gossip(SpoolCodec.encode(SpoolEvent(t = SpoolRecordType.EVENT, scope = scope, blobId = sha256(junk), data = junk)))
            }
            pump()
            receiver.custody.sweep(fid("m1"))
            pump(rounds = 120)

            assertEquals("m1 stayed guarded — bridged once, not once more after the sweep", 1, receiver.metrics.spool().spoolBridged)
            assertEquals(0, receiver.metrics.spool().spoolInvalid)
            sender.sync.stop()
            receiver.sync.stop()
        }

    @Test
    fun `a quarantined id the spool drops leaves the invalid set with it`() =
        runTest {
            val spool = FakeSpool()
            val victim = member(spool, bob, alice)
            val g1 = spool.plantGarbage(scopeHex(bob, alice), "garbage one".toByteArray())
            spool.plantGarbage(scopeHex(bob, alice), "garbage two".toByteArray())

            victim.sync.start(backgroundScope)
            pump()
            assertEquals(
                2,
                victim.sync
                    .status()
                    .single()
                    .scopes
                    .single()
                    .invalidCount,
            )

            // The quarantine exists to stop a re-pull; an id the listing no longer names cannot be
            // re-pulled, so holding it any longer only denies a clean re-push after the garbage expired.
            spool.expire(scopeHex(bob, alice), g1)
            pump(rounds = 70)

            assertEquals(
                1,
                victim.sync
                    .status()
                    .single()
                    .scopes
                    .single()
                    .invalidCount,
            )
            assertEquals("the entry that left was the one the spool dropped", 1, spool.pulled.count { it == g1 })
            victim.sync.stop()
        }

    @Test
    fun `a spool listing more ids than a conforming one can hold is refused, not pulled from`() =
        runTest {
            // Well past §12.2's default `maxFrames` (400) and the set bound (512) the client tracks with,
            // but comfortably inside the 128 KiB record cap that was the only bound on it.
            val spool = FakeSpool(listingPadding = 600)
            val victim = member(spool, bob, alice)
            spool.plantGarbage(scopeHex(bob, alice), "forces a listing".toByteArray())

            victim.sync.start(backgroundScope)
            pump(rounds = 70)

            assertTrue("nothing from that listing is pulled", spool.pulled.isEmpty())
            assertEquals(
                ScopeSync.OVERLONG_LISTING,
                victim.sync
                    .status()
                    .single()
                    .lastError,
            )
            assertTrue(victim.metrics.spool().spoolErrors >= 1)
            assertEquals("and nothing quarantined on its say-so", 0, victim.metrics.spool().spoolInvalid)
            victim.sync.stop()
        }

    @Test
    fun `a spool listing more tombstones than the count bound is refused too`() =
        runTest {
            val spool = FakeSpool(tombstonePadding = 1100)
            val victim = member(spool, bob, alice)
            spool.plantGarbage(scopeHex(bob, alice), "forces a listing".toByteArray())

            victim.sync.start(backgroundScope)
            pump(rounds = 70)

            assertTrue(spool.pulled.isEmpty())
            assertEquals(
                ScopeSync.OVERLONG_LISTING,
                victim.sync
                    .status()
                    .single()
                    .lastError,
            )
            victim.sync.stop()
        }

    @Test
    fun `a listing inside the bound is worked whole — the threshold is a refusal, not a truncation`() =
        runTest {
            val spool = FakeSpool(listingPadding = 100)
            val victim = member(spool, bob, alice)
            val id = spool.plantGarbage(scopeHex(bob, alice), "garbage".toByteArray())

            victim.sync.start(backgroundScope)
            pump()

            assertEquals("the round ran: the garbage it named was pulled and quarantined", 1, spool.pulled.count { it == id })
            assertEquals(1, victim.metrics.spool().spoolInvalid)
            assertNull(
                victim.sync
                    .status()
                    .single()
                    .lastError,
            )
            victim.sync.stop()
        }

    @Test
    fun `an expired frame is never pushed — the outward dead-on-arrival guard`() =
        runTest {
            val spool = FakeSpool()
            val sender = member(spool, alice, bob, custody = FakeCustody(ttlMs = Long.MAX_VALUE / 2))
            // Held locally (the custody TTL here is generous) but already past the scope's 48 h horizon.
            sender.custody.store(
                dmFrame(fid("old"), from = alice, to = bob, sentAt = now - ScopeRegistry.DEFAULT_TTL_MS),
                ForwardStore.ORIGIN_SELF,
                now,
            )
            sender.custody.store(dmFrame(fid("fresh"), from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)

            sender.sync.start(backgroundScope)
            pump()

            assertEquals(1, spool.pushed.size)
            assertEquals(1, sender.metrics.spool().spoolPushed)
            sender.sync.stop()
        }

    @Test
    fun `a frame that fails the frame-set rule is never sealed into the scope`() =
        runTest {
            val spool = FakeSpool()
            val sender = member(spool, alice, bob)
            val carol = "ccccccccccccccccccccccccce"
            sender.custody.store(dmFrame(fid("mine"), from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            sender.custody.store(dmFrame(fid("theirs"), from = alice, to = carol, sentAt = now), ForwardStore.ORIGIN_SELF, now)

            sender.sync.start(backgroundScope)
            pump()

            assertEquals("only this scope's own DM may ride it", 1, spool.pushed.size)
            sender.sync.stop()
        }

    @Test
    fun `a profile crosses the DM scope, which is how a prekey reaches a peer off the radios`() =
        runTest {
            val spool = FakeSpool()
            val sender = member(spool, alice, bob)
            val receiver = member(spool, bob, alice)
            sender.custody.store(profileFrame(FrameId.profileId(alice, now), from = alice, sentAt = now), ForwardStore.ORIGIN_SELF, now)

            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump()

            assertEquals(listOf(FrameId.profileId(alice, now)), receiver.delivered.map { it.id })
            sender.sync.stop()
            receiver.sync.stop()
        }

    @Test
    fun `a co-member's profile crosses the group scope even with no DM session between them`() =
        runTest {
            val spool = FakeSpool()
            // carol shares only the group with bob — no DM scope exists between them, which is exactly the
            // case that stranded group sender-key seeds: the seed rides a ctl DM that needs carol's prekey.
            val groupId = "g-00112233445566778899aabb"
            val roster = setOf(alice, bob, carol)
            val group = GroupScopeRoots(groupId, roster, groupRoot, rootVersion = 1)
            val holder = member(spool, alice, bob, groups = listOf(group))
            val reader = member(spool, bob, alice, groups = listOf(group))
            holder.custody.store(profileFrame(FrameId.profileId(carol, now), from = carol, sentAt = now), ForwardStore.ORIGIN_RELAY, now)

            holder.sync.start(backgroundScope)
            reader.sync.start(backgroundScope)
            pump()

            assertEquals(listOf(FrameId.profileId(carol, now)), reader.delivered.map { it.id })
            holder.sync.stop()
            reader.sync.stop()
        }

    @Test
    fun `pull batches at the spool's maxPull and re-pulls the truncated remainder`() =
        runTest {
            val spool = FakeSpool(maxPull = 2)
            val holder = member(spool, alice, bob)
            val ids = (1..5).map { fid("m$it") }
            ids.forEach { holder.custody.store(dmFrame(it, from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now) }
            holder.sync.start(backgroundScope)
            pump()
            holder.sync.stop()

            val receiver = member(spool, bob, alice)
            receiver.sync.start(backgroundScope)
            pump()

            assertEquals("every blob arrives despite the 2-per-PULL cap", ids.toSet(), receiver.delivered.map { it.id }.toSet())
            receiver.sync.stop()
        }

    @Test
    fun `mines a hashcash stamp only when the spool demands one`() =
        runTest {
            val open = FakeSpool(powBits = 0)
            val gated = FakeSpool(powBits = 8)
            val a = member(open, alice, bob)
            val b = member(gated, alice, bob)

            a.sync.start(backgroundScope)
            b.sync.start(backgroundScope)
            pump()

            assertTrue("a PoW-free spool must not be handed a stamp", open.stamps.isEmpty())
            val stamp = gated.stamps[scopeHex(alice, bob)]
            assertTrue("the gated spool must get a valid stamp", stamp != null)
            a.sync.stop()
            b.sync.stop()
        }

    @Test
    fun `a scope the spool asks a stamp for is re-sent with one at once, with no error and no park`() =
        runTest {
            val spool = FakeSpool(powBits = 8)
            val hex = scopeHex(alice, bob)
            val member = member(spool, alice, bob)

            member.sync.start(backgroundScope)
            pump()

            assertEquals("asked bare, refused, asked again with a stamp", listOf(false, true), spool.subStamped.stampedFor(hex))
            assertEquals(1, spool.powRefusals[hex])
            assertTrue("subscribed well inside the 5 min park", hex in spool.subscribedScopes)
            assertNull(
                "a demand for a stamp is the protocol working, not a fault",
                member.sync
                    .status()
                    .single()
                    .lastError,
            )
            member.sync.stop()
        }

    @Test
    fun `a spool that already holds the scope is never sent a stamp`() =
        runTest {
            val spool = FakeSpool(powBits = 8)
            val hex = scopeHex(alice, bob)
            val first = member(spool, alice, bob)
            first.sync.start(backgroundScope)
            pump()
            first.sync.stop()
            val before = spool.subStamped.size

            // The peer's phone, on the same DM scope the spool now holds: no demand, so nothing to mine.
            val second = member(spool, bob, alice)
            second.sync.start(backgroundScope)
            pump()

            val later = spool.subStamped.drop(before).filter { it.first == hex }
            assertTrue("the peer subscribed", later.isNotEmpty())
            assertTrue("and never with a stamp", later.none { it.second })
            assertEquals("only the scope's creation met the gate", 1, spool.powRefusals[hex])
            second.sync.stop()
        }

    @Test
    fun `a reconnect to a spool that still holds the scope sends no stamp, one that shed it is asked again`() =
        runTest {
            val spool = FakeSpool(powBits = 8)
            val hex = scopeHex(alice, bob)
            val member = member(spool, alice, bob)
            member.sync.start(backgroundScope)
            pump()

            spool.dropSockets()
            pump(rounds = 40)
            assertEquals("the reconnect's SUB went bare and was taken", listOf(false, true, false), spool.subStamped.stampedFor(hex))

            spool.forget(hex)
            spool.dropSockets()
            pump(rounds = 40)
            assertEquals(listOf(false, true, false, false, true), spool.subStamped.stampedFor(hex))
            assertEquals(2, spool.powRefusals[hex])
            assertTrue("subscribed again", hex in spool.subscribedScopes)
            member.sync.stop()
        }

    @Test
    fun `a stamp the spool turns down parks the scope`() =
        runTest {
            val spool = FakeSpool(powBits = 8, rejectStamps = true)
            val hex = scopeHex(alice, bob)
            // The park is measured on the member's own clock, so this one has to move with virtual time.
            val member = member(spool, alice, bob, clock = { now + testScheduler.currentTime })

            member.sync.start(backgroundScope)
            pump(rounds = 200)

            assertEquals("asked bare, then with a stamp, then parked", listOf(false, true), spool.subStamped.stampedFor(hex))
            assertEquals(
                SpoolErrCode.POW,
                member.sync
                    .status()
                    .single()
                    .lastError,
            )
            member.sync.stop()
        }

    // --- Attachments, spec §4.5/§9.5 ---

    @Test
    fun `an image one member holds reaches the other through the spool`() =
        runTest {
            val spool = FakeSpool()
            val (aHash, bytes) = image()
            val sender = member(spool, alice, bob, blobs = FakeBlobs(aHash to bytes))
            val receiver = member(spool, bob, alice)
            sender.custody.store(
                dmFrame(fid("m1"), from = alice, to = bob, sentAt = now, attachmentHash = aHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )

            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump(rounds = 16)

            // 100 000 bytes at the spec's 48 KiB chunk: three chunks, uploaded whole.
            val aid = aidHex(alice, bob, aHash)
            assertEquals(3, spool.chunkCount(scopeHex(alice, bob), aid))
            assertEquals(3, sender.metrics.spool().spoolAttachPushed)

            // The receiver got the frame, then the bytes it names — verified against that same address.
            assertEquals(listOf(fid("m1")), receiver.delivered.map { it.id })
            assertTrue("the image landed locally", receiver.blobs.stored.containsKey(aHash))
            assertTrue(bytes.contentEquals(receiver.blobs.stored.getValue(aHash)))
            assertEquals(listOf(aHash), receiver.obtained)
            assertEquals(1, receiver.metrics.spool().spoolAttachPulled)
            assertEquals(0, receiver.metrics.spool().spoolInvalid)
            sender.sync.stop()
            receiver.sync.stop()
        }

    /** A files-gated lab spool (knit-spool ADR 2026-10.h6pt): `ent`, and `entGates` naming bit 1 alone. */
    private val filesUrl = "ws://lab.example:9480/spool/v1"

    private suspend fun filesTickets(vararg held: HeldTicket): SpoolTickets =
        SpoolTickets(SpoolTicketsTest.MemoryJournal()) { now }.also { tickets -> held.forEach { tickets.import(filesUrl, it.text) } }

    @Test
    fun `an unticketed member never uploads to a files-gated spool, and still fetches what a ticketed one sent`() =
        runTest {
            val spool = FakeSpool(ent = true, entGates = SpoolTicket.FL_FILES)
            val (mine, mineBytes) = image()
            val (theirs, theirBytes) = image(size = 60_000)
            // Alice holds no ticket; Bob holds one that grants files.
            val alicePhone = member(spool, alice, bob, blobs = FakeBlobs(mine to mineBytes), urls = listOf(filesUrl))
            val bobTickets = filesTickets(SpoolTicketsTest.ticket(fl = SpoolTicket.FL_FILES))
            val bobPhone = member(spool, bob, alice, blobs = FakeBlobs(theirs to theirBytes), urls = listOf(filesUrl), tickets = bobTickets)
            alicePhone.custody.store(
                dmFrame(fid("m1"), from = alice, to = bob, sentAt = now, attachmentHash = mine),
                ForwardStore.ORIGIN_SELF,
                now,
            )
            bobPhone.custody.store(
                dmFrame(fid("m2"), from = bob, to = alice, sentAt = now, attachmentHash = theirs),
                ForwardStore.ORIGIN_SELF,
                now,
            )

            // Alice alone first: the attachment's id is the same for both members, so Bob's fetch would ask about it too.
            alicePhone.sync.start(backgroundScope)
            pump(rounds = 16)
            val aliceStatus = alicePhone.sync.status().single()
            assertEquals(SpoolTicket.FL_FILES, aliceStatus.entGates)
            assertEquals(SpoolUploads.Locked(SpoolUploads.MISSING), aliceStatus.uploads)
            // Locked at once: not one `aput` for her photo, nor the `ahave` that would precede one.
            val mineAid = aidHex(alice, bob, mine)
            assertTrue(spool.aputs.isEmpty())
            assertFalse(mineAid in spool.presenceAsks)
            assertTrue(alicePhone.metrics.spool().spoolAttachLocked > 0)
            // Settled, not owed: the 15 s re-look never comes back for it.
            pump(rounds = 40)
            assertTrue(spool.aputs.isEmpty())
            assertFalse(mineAid in spool.presenceAsks)
            assertTrue("the spool refused nothing: nothing was tried", spool.aputRefusals.isEmpty())

            // The ticketed member uploads, and the unticketed one fetches it (S-7.6-23).
            bobPhone.sync.start(backgroundScope)
            pump(rounds = 16)
            assertEquals(
                SpoolUploads.Open,
                bobPhone.sync
                    .status()
                    .single()
                    .uploads,
            )
            assertTrue(alicePhone.blobs.stored.containsKey(theirs))
            assertTrue(spool.uploadCharged(SpoolTicketsTest.ticket(fl = SpoolTicket.FL_FILES)) > 0)
            // Still nothing of hers went up, and the text plane is untouched by the gate.
            assertTrue(spool.aputs.none { it.startsWith(mineAid) })
            assertTrue(spool.aputRefusals.isEmpty())
            assertEquals(listOf(fid("m2")), alicePhone.delivered.map { it.id })
            alicePhone.sync.stop()
            bobPhone.sync.stop()
        }

    @Test
    fun `a spent budget holds across a reconnect and a same-ps ticket, and another pseudonym reopens uploads`() =
        runTest {
            // Room for one sealed chunk a day: the second chunk is refused `budget`.
            val spool = FakeSpool(ent = true, entGates = SpoolTicket.FL_FILES, ticketAttachBytes = 60_000L)
            val (first, firstBytes) = image()
            val (second, secondBytes) = image(size = 30_000)
            val today = SpoolTicketsTest.ticket(fl = SpoolTicket.FL_FILES)
            val tickets = filesTickets(today)
            val phone =
                member(
                    spool,
                    alice,
                    bob,
                    blobs = FakeBlobs(first to firstBytes, second to secondBytes),
                    urls = listOf(filesUrl),
                    tickets = tickets,
                )
            phone.custody.store(
                dmFrame(fid("m1"), from = alice, to = bob, sentAt = now, attachmentHash = first),
                ForwardStore.ORIGIN_SELF,
                now,
            )
            phone.custody.store(
                dmFrame(fid("m2"), from = alice, to = bob, sentAt = now, attachmentHash = second),
                ForwardStore.ORIGIN_SELF,
                now,
            )
            phone.sync.start(backgroundScope)
            pump(rounds = 16)

            assertEquals(listOf("budget"), spool.aputRefusals)
            assertEquals(
                SpoolUploads.Spent,
                phone.sync
                    .status()
                    .single()
                    .uploads,
            )
            val tried = spool.aputs.size
            pump(rounds = 40)
            assertEquals("no aput after budget, nor the re-look", tried, spool.aputs.size)

            // A reconnect presents the same ticket again; its pseudonym is still spent here.
            spool.dropSockets()
            pump(rounds = 30)
            assertEquals(2, spool.ents.size)
            assertEquals(
                SpoolUploads.Spent,
                phone.sync
                    .status()
                    .single()
                    .uploads,
            )
            assertEquals(tried, spool.aputs.size)

            // So does a different ticket of the same day (the refresher's next fetch): same `ps`.
            tickets.import(filesUrl, SpoolTicketsTest.ticket(fl = SpoolTicket.FL_FILES, sigByte = 7).text)
            pump(rounds = 16)
            assertEquals(3, spool.ents.size)
            assertEquals(tried, spool.aputs.size)

            // A ticket with another pseudonym reopens uploads at once, without waiting for the hourly recheck.
            val tomorrow = SpoolTicketsTest.ticket(fl = SpoolTicket.FL_FILES, sigByte = 8, psSeed = 1)
            tickets.import(filesUrl, tomorrow.text)
            pump(rounds = 16)
            assertTrue(spool.aputs.size > tried)
            assertTrue(spool.uploadCharged(tomorrow) > 0)
            phone.sync.stop()
        }

    @Test
    fun `a files ticket accepted after a round locked the upload sends it on the next round`() =
        runTest {
            // The spool is slow to judge the ticket: the first rounds see it pending, and lock the upload.
            val spool = FakeSpool(ent = true, entGates = SpoolTicket.FL_FILES).apply { holdEnts = true }
            val (aHash, bytes) = image()
            val tickets = filesTickets(SpoolTicketsTest.ticket(fl = SpoolTicket.FL_FILES))
            val phone = member(spool, alice, bob, blobs = FakeBlobs(aHash to bytes), urls = listOf(filesUrl), tickets = tickets)
            phone.custody.store(
                dmFrame(fid("m1"), from = alice, to = bob, sentAt = now, attachmentHash = aHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )
            phone.sync.start(backgroundScope)
            pump(rounds = 16)
            assertEquals(
                SpoolUploads.Locked(SpoolUploads.PENDING),
                phone.sync
                    .status()
                    .single()
                    .uploads,
            )
            assertTrue(spool.aputs.isEmpty())
            assertTrue(phone.metrics.spool().spoolAttachLocked > 0)

            // Accepted: the locked attachment is looked at again at once — not at the 15 s re-look, and not at the
            // timed round's ten-minute recheck.
            spool.releaseEnts()
            pump(rounds = 4)
            assertEquals(
                SpoolUploads.Open,
                phone.sync
                    .status()
                    .single()
                    .uploads,
            )
            assertEquals(3, spool.chunkCount(scopeHex(alice, bob), aidHex(alice, bob, aHash)))
            phone.sync.stop()
        }

    @Test
    fun `an attachment over the ticket's am is never uploaded there, and a smaller one is`() =
        runTest {
            // am = 49 152 B: one chunk (C-7.6-32). A 100 000 B photo is three; a 30 000 B one fits.
            val spool = FakeSpool(ent = true, entGates = SpoolTicket.FL_FILES)
            val (big, bigBytes) = image()
            val (small, smallBytes) = image(size = 30_000)
            val tickets = filesTickets(SpoolTicketsTest.ticket(fl = SpoolTicket.FL_FILES, am = 49_152))
            val phone =
                member(
                    spool,
                    alice,
                    bob,
                    blobs = FakeBlobs(big to bigBytes, small to smallBytes),
                    urls = listOf(filesUrl),
                    tickets = tickets,
                )
            phone.custody.store(
                dmFrame(fid("m1"), from = alice, to = bob, sentAt = now, attachmentHash = big),
                ForwardStore.ORIGIN_SELF,
                now,
            )
            phone.custody.store(
                dmFrame(fid("m2"), from = alice, to = bob, sentAt = now, attachmentHash = small),
                ForwardStore.ORIGIN_SELF,
                now,
            )
            phone.sync.start(backgroundScope)
            pump(rounds = 16)
            val status = phone.sync.status().single()
            assertEquals(SpoolUploads.Open, status.uploads)
            assertEquals(1, status.uploadLimitChunks)
            // The big one: not an `aput`, nor the `ahave` before one. The small one went up whole.
            val bigAid = aidHex(alice, bob, big)
            assertTrue(spool.aputs.none { it.startsWith(bigAid) })
            assertFalse(bigAid in spool.presenceAsks)
            assertEquals(1, spool.chunkCount(scopeHex(alice, bob), aidHex(alice, bob, small)))
            assertTrue("nothing was refused: nothing too large was tried", spool.aputRefusals.isEmpty())
            // Settled: the re-look never asks about it again.
            pump(rounds = 40)
            assertFalse(bigAid in spool.presenceAsks)
            phone.sync.stop()
        }

    @Test
    fun `the last hello's attachments and gates are kept for a photo staged while the relay is down`() =
        runTest {
            val spool = FakeSpool(ent = true, entGates = SpoolTicket.FL_FILES)
            var down = false
            val dialer =
                object : SpoolDialer {
                    override suspend fun dial(url: String): SpoolSocket? = if (down) null else spool.dial(url)
                }
            val phone = lone(dialer, urls = listOf(filesUrl))
            assertNull(phone.status().singleOrNull()?.seenGates)
            phone.start(backgroundScope)
            pump(rounds = 8)
            val up = phone.status().single()
            assertTrue(up.connected)
            assertEquals(true, up.seenAttachments)
            assertEquals(SpoolTicket.FL_FILES, up.seenGates)
            // The relay goes away and stays away: the status forgets the live hello, not what it said.
            down = true
            spool.dropSockets()
            pump(rounds = 8)
            val gone = phone.status().single()
            assertFalse(gone.connected)
            assertNull(gone.maxAttachBytes)
            assertEquals(true, gone.seenAttachments)
            assertEquals(SpoolTicket.FL_FILES, gone.seenGates)
            phone.stop()
        }

    @Test
    fun `a size refusal settles that attachment without locking uploads or spending the ticket`() =
        runTest {
            val spool = FakeSpool(ent = true, entGates = SpoolTicket.FL_FILES).apply { refuseSizeOver = 1 }
            val (big, bigBytes) = image()
            val (small, smallBytes) = image(size = 30_000)
            val ticket = SpoolTicketsTest.ticket(fl = SpoolTicket.FL_FILES)
            val phone =
                member(
                    spool,
                    alice,
                    bob,
                    blobs = FakeBlobs(big to bigBytes, small to smallBytes),
                    urls = listOf(filesUrl),
                    tickets = filesTickets(ticket),
                )
            phone.custody.store(
                dmFrame(fid("m1"), from = alice, to = bob, sentAt = now, attachmentHash = big),
                ForwardStore.ORIGIN_SELF,
                now,
            )
            phone.sync.start(backgroundScope)
            pump(rounds = 16)
            assertEquals(listOf("size"), spool.aputRefusals)
            // Not a lock and not a spent day: uploads stay open, and nothing was charged.
            assertEquals(
                SpoolUploads.Open,
                phone.sync
                    .status()
                    .single()
                    .uploads,
            )
            assertEquals(0L, spool.uploadCharged(ticket))
            val tried = spool.aputs.size
            pump(rounds = 40)
            assertEquals("the refused attachment is not tried again", tried, spool.aputs.size)
            // The next attachment goes up under the same ticket.
            phone.custody.store(
                dmFrame(fid("m2"), from = alice, to = bob, sentAt = now, attachmentHash = small),
                ForwardStore.ORIGIN_SELF,
                now,
            )
            pump(rounds = 16)
            assertEquals(1, spool.chunkCount(scopeHex(alice, bob), aidHex(alice, bob, small)))
            assertEquals(listOf("size"), spool.aputRefusals)
            phone.sync.stop()
        }

    @Test
    fun `an upload locked for want of a ticket wakes the refresher, and one waiting on its answer does not`() =
        runTest {
            val wanted = mutableListOf<String>()
            val (aHash, bytes) = image()
            // No ticket: locked `missing`, a reason to ask now.
            val bare = FakeSpool(ent = true, entGates = SpoolTicket.FL_FILES)
            val phone =
                member(bare, alice, bob, blobs = FakeBlobs(aHash to bytes), urls = listOf(filesUrl), onUploadWanted = { wanted += it })
            phone.custody.store(
                dmFrame(fid("m1"), from = alice, to = bob, sentAt = now, attachmentHash = aHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )
            phone.sync.start(backgroundScope)
            pump(rounds = 16)
            assertTrue(wanted.isNotEmpty())
            assertTrue(wanted.all { it == filesUrl })
            phone.sync.stop()

            // A ticket on its way (`pending`) is no reason to ask for another.
            val slow = FakeSpool(ent = true, entGates = SpoolTicket.FL_FILES).apply { holdEnts = true }
            val waiting = mutableListOf<String>()
            val other =
                member(
                    slow,
                    alice,
                    bob,
                    blobs = FakeBlobs(aHash to bytes),
                    urls = listOf(filesUrl),
                    tickets = filesTickets(SpoolTicketsTest.ticket(fl = SpoolTicket.FL_FILES)),
                    onUploadWanted = { waiting += it },
                )
            other.custody.store(
                dmFrame(fid("m1"), from = alice, to = bob, sentAt = now, attachmentHash = aHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )
            other.sync.start(backgroundScope)
            pump(rounds = 16)
            assertEquals(
                SpoolUploads.Locked(SpoolUploads.PENDING),
                other.sync
                    .status()
                    .single()
                    .uploads,
            )
            assertTrue(waiting.isEmpty())
            other.sync.stop()
        }

    @Test
    fun `a photo in its proof round on a locked relay wakes the refresher once, when the round ends without proof`() =
        runTest {
            // ADR 2026-10.vy27: inside the round it is no upload that waits yet, so nothing may ask for a ticket.
            val wanted = mutableListOf<String>()
            val (aHash, bytes) = image()
            val roundEnds = testScheduler.currentTime + AttachmentDeferPolicy.PROOF_WAIT_MS
            val locked = FakeSpool(ent = true, entGates = SpoolTicket.FL_FILES) // no ticket: locked `missing`
            val phone =
                member(
                    locked,
                    alice,
                    bob,
                    blobs = FakeBlobs(aHash to bytes),
                    urls = listOf(filesUrl),
                    holdsForProof = { _, _ -> testScheduler.currentTime < roundEnds },
                    onUploadWanted = { wanted += it },
                )
            phone.custody.store(
                dmFrame(fid("m1"), from = alice, to = bob, sentAt = now, attachmentHash = aHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )
            phone.sync.start(backgroundScope)
            pump(rounds = 3)
            // However often the scope is passed inside the round, none of them locks it or wakes anything.
            repeat(3) {
                phone.sync.onCustodyChanged()
                pump(rounds = 2)
            }
            assertTrue("the round was held", phone.metrics.spool().spoolAttachDeferred > 0)
            assertEquals(0L, phone.metrics.spool().spoolAttachLocked)
            assertEquals(emptyList<String>(), wanted)

            // The re-look the held pass scheduled comes back after the round and locks it: one wake, and no more.
            advanceTimeBy(ScopeSync.ATTACHMENT_RETRY_MS)
            runCurrent()
            assertEquals(1L, phone.metrics.spool().spoolAttachLocked)
            assertEquals(listOf(filesUrl), wanted)
            pump(rounds = 60)
            assertEquals("one wake per round", listOf(filesUrl), wanted)
            assertEquals(emptyList<String>(), locked.chunksPut)
            phone.sync.stop()
        }

    @Test
    fun `a photo proved inside its proof round on a locked relay settles proved and wakes nothing`() =
        runTest {
            val wanted = mutableListOf<String>()
            val (aHash, bytes) = image()
            val start = testScheduler.currentTime
            val locked = FakeSpool(ent = true, entGates = SpoolTicket.FL_FILES)
            val phone =
                member(
                    locked,
                    alice,
                    bob,
                    blobs = FakeBlobs(aHash to bytes),
                    urls = listOf(filesUrl),
                    // The recipient's HAVE lands a few seconds into the round.
                    provedAttachment = { _, _ -> testScheduler.currentTime >= start + 3_000 },
                    holdsForProof = { _, _ -> testScheduler.currentTime < start + AttachmentDeferPolicy.PROOF_WAIT_MS },
                    onUploadWanted = { wanted += it },
                )
            phone.custody.store(
                dmFrame(fid("m1"), from = alice, to = bob, sentAt = now, attachmentHash = aHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )
            phone.sync.start(backgroundScope)
            pump(rounds = 2)
            assertTrue("held while unproved", phone.metrics.spool().spoolAttachDeferred > 0)

            advanceTimeBy(ScopeSync.ATTACHMENT_RETRY_MS)
            runCurrent()
            pump(rounds = 60)
            assertTrue("settled on the proof", phone.metrics.spool().spoolAttachProved > 0)
            assertEquals(0L, phone.metrics.spool().spoolAttachLocked)
            assertEquals("nothing asked for a ticket, in the round or after it", emptyList<String>(), wanted)
            assertEquals(emptyList<String>(), locked.chunksPut)
            phone.sync.stop()
        }

    @Test
    fun `a free phone's locked upload fetches a free ticket and goes up, after a reconnect too`() =
        runTest {
            // The whole loop on JVM: the plane's wake, the refresher's free account and swap (scripted issuer), the
            // import, the plane presenting the ticket, the upload. The socket drops while the ticket is fetched.
            val spool = FakeSpool(ent = true, entGates = SpoolTicket.FL_FILES)
            val (aHash, bytes) = image(size = 30_000)
            val journal = SpoolTicketsTest.MemoryJournal()
            val tickets = SpoolTickets(journal) { now }
            val keys = TicketRefresherTest.MemoryKeyJournal(journal)
            val host = SpoolUrl.hostname(filesUrl)!!
            val swap = LoopSwap(host) { now }
            val refresher = freeRefresher(host, keys, tickets, swap)
            val phone =
                member(
                    spool,
                    alice,
                    bob,
                    blobs = FakeBlobs(aHash to bytes),
                    urls = listOf(filesUrl),
                    tickets = tickets,
                    onUploadWanted = refresher::uploadWanted,
                )
            phone.custody.store(
                dmFrame(fid("m1"), from = alice, to = bob, sentAt = now, attachmentHash = aHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )
            phone.sync.start(backgroundScope)
            pump(rounds = 16)
            assertEquals(
                SpoolUploads.Locked(SpoolUploads.MISSING),
                phone.sync
                    .status()
                    .single()
                    .uploads,
            )
            assertTrue(refresher.isWaiting(host))
            assertTrue(spool.aputs.isEmpty())

            // Offline while the ticket is fetched: the socket goes, the refresher makes the account and swaps its key.
            spool.dropSockets()
            refresher.runOnce()
            assertEquals(1, swap.asks)
            assertTrue(
                keys.held.value
                    .getValue(host)
                    .free,
            )
            assertFalse(refresher.isWaiting(host))
            // Back online: the new connection presents the ticket, and the waiting photo goes up whole.
            pump(rounds = 30)
            assertEquals(
                SpoolUploads.Open,
                phone.sync
                    .status()
                    .single()
                    .uploads,
            )
            assertEquals(1, spool.chunkCount(scopeHex(alice, bob), aidHex(alice, bob, aHash)))
            // Nothing more waits, so the free key asks nothing more, however long the session runs.
            refresher.runOnce()
            assertEquals(1, swap.asks)
            phone.sync.stop()
        }

    /** A refresher for [filesUrl] whose host has a free issuer the person allowed, over [keys] and [tickets]. */
    private fun freeRefresher(
        host: String,
        keys: TicketRefresherTest.MemoryKeyJournal,
        tickets: SpoolTickets,
        swap: TicketIssuer,
    ): TicketRefresher {
        val free =
            MemoryFree(keys).apply {
                issuers.value = mapOf(host to "https://free.example")
                consent.value = mapOf(host to true)
            }
        return TicketRefresher(
            keys = keys,
            tickets = tickets,
            issuer = swap,
            urls = MutableStateFlow(setOf(filesUrl)),
            clock = { now },
            free = free,
            accounts = LoopAccounts(),
        )
    }

    /** The key swap for the free loop: one 25 h files ticket with a five-chunk `am` per ask, counted. */
    private class LoopSwap(
        private val host: String,
        private val clock: () -> Long,
    ) : TicketIssuer {
        var asks = 0

        override suspend fun swap(
            origin: String,
            key: String,
            device: DeviceSigner?,
        ): IssuerAnswer {
            asks++
            val nowS = clock() / 1_000
            val ticket = SpoolTicketsTest.ticket(aud = host, nbf = nowS, exp = nowS + 90_000, fl = SpoolTicket.FL_FILES, am = 262_144)
            return IssuerAnswer.Issued(ticket.text, clock() + 30 * 86_400_000L)
        }
    }

    /** The account route for the free loop: a 4-bit stamp, and an account for any stamp. */
    private class LoopAccounts : AccountIssuer {
        override suspend fun accountParams(
            origin: String,
            aud: String,
        ) = AccountAnswer.Params(aud, 4, 20_000)

        override suspend fun createAccount(
            origin: String,
            aud: String,
            day: Long,
            nonce: ByteArray,
            n: Long,
        ) = AccountAnswer.Created("kak1_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", aud)
    }

    /** The free half of the key store in memory, beside [TicketRefresherTest.MemoryKeyJournal]'s one slot per host. */
    private class MemoryFree(
        private val keys: TicketRefresherTest.MemoryKeyJournal,
    ) : FreeKeyJournal {
        val issuers = MutableStateFlow<Map<String, String>>(emptyMap())
        val consent = MutableStateFlow<Map<String, Boolean>>(emptyMap())
        val states = MutableStateFlow<Map<String, FreeState>>(emptyMap())
        override val freeIssuers: Flow<Map<String, String>> = issuers
        override val freeConsent: Flow<Map<String, Boolean>> = consent
        override val freeStates: Flow<Map<String, FreeState>> = states

        override suspend fun setFreeIssuer(
            host: String,
            origin: String?,
        ) {
            issuers.value = if (origin == null) issuers.value - host else issuers.value + (host to origin)
        }

        override suspend fun setFreeConsent(
            host: String,
            allowed: Boolean?,
        ) {
            consent.value = if (allowed == null) consent.value - host else consent.value + (host to allowed)
        }

        override suspend fun putFreeState(
            host: String,
            state: FreeState,
        ) {
            states.value += host to state
        }

        override suspend fun forgetFreeKey(
            host: String,
            key: HeldKey,
            notBefore: Long,
        ) {
            if (keys.held.value[host] == key) keys.forgetHost(host)
            states.value += host to FreeState(notBefore = notBefore)
        }
    }

    @Test
    fun `a free phone on a spool that gates only files still asks it for turn`() =
        runTest {
            val spool = FakeSpool(turn = true, ent = true, entGates = SpoolTicket.FL_FILES)
            val lines = mutableListOf<String>()
            val phone = member(spool, alice, bob, urls = listOf(filesUrl), log = { lines += it })
            phone.sync.start(backgroundScope)
            pump()
            assertEquals(1, phone.sync.turnCredentials(bob).size)
            assertEquals(1, spool.turnIssued)
            assertTrue(spool.turnRefusals.isEmpty())
            assertFalse(lines.any { "TURN ask skipped" in it })
            assertTrue(
                phone.sync
                    .status()
                    .single()
                    .turnForUs,
            )
            assertTrue(phone.sync.turnAvailable())
            phone.sync.stop()
        }

    @Test
    fun `a free files ticket on a spool that gates turn leaves no turn for relay-only calls`() =
        runTest {
            val lab = FakeSpool(turn = true, ent = true)
            val url = "ws://lab.example:9480/spool/v1?k=t"
            val tickets = SpoolTickets(SpoolTicketsTest.MemoryJournal()) { now }
            val phone = member(lab, alice, bob, urls = listOf(url), tickets = tickets)
            phone.sync.start(backgroundScope)
            pump()
            val free = SpoolTicketsTest.ticket(fl = SpoolTicket.FL_FILES)
            assertTrue(tickets.import(url, free.text) is SpoolTickets.Import.Held)
            pump(2)
            val status = phone.sync.status().single()
            assertTrue(status.turn)
            assertEquals(SpoolTicketState.Accepted(exp = 1_800_086_400L, fl = SpoolTicket.FL_FILES), status.ticket)
            assertFalse(status.turnForUs)
            assertFalse(phone.sync.turnAvailable())
            assertTrue(phone.sync.turnCredentials(bob).isEmpty())
            phone.sync.stop()
        }

    @Test
    fun `a late ticket refusal of an aput leaves the scope subscribed`() =
        runTest {
            val spool = FakeSpool(ent = true, entGates = SpoolTicket.FL_FILES)
            val phone = member(spool, alice, bob, urls = listOf(filesUrl))
            phone.sync.start(backgroundScope)
            pump()
            val scope = scopeHex(alice, bob)
            val subs = spool.subAttempts[scope]
            // An `aput`'s refusal whose request has already timed out names a `q` nothing waits on any more.
            spool.gossip(
                SpoolCodec.encode(
                    SpoolErr(t = SpoolRecordType.ERR, code = SpoolErrCode.TICKET, q = 9_999L, scope = unhex(scope), msg = "missing"),
                ),
            )
            pump(rounds = 70)
            val status = phone.sync.status().single()
            assertNull(status.lastError)
            assertEquals("never re-subscribed as if the SUB had been refused", subs, spool.subAttempts[scope])
            assertTrue(status.scopes.single().converged)
            phone.sync.stop()
        }

    @Test
    fun `a spool that advertises no attachment support is never sent an attachment record`() =
        runTest {
            val spool = FakeSpool(attachments = false)
            val (aHash, bytes) = image()
            val sender = member(spool, alice, bob, blobs = FakeBlobs(aHash to bytes))
            val receiver = member(spool, bob, alice)
            sender.custody.store(
                dmFrame(fid("m1"), from = alice, to = bob, sentAt = now, attachmentHash = aHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )

            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump(rounds = 16)

            // The frame plane is unaffected — that is what makes attachments additive.
            assertEquals(listOf(fid("m1")), receiver.delivered.map { it.id })
            // Not one attachment record went out. A v1 spool would have skipped it without answering,
            // stalling that q until the request timeout.
            assertEquals(emptyList<String>(), spool.skippedRecords)
            assertEquals(emptyList<String>(), spool.chunksPut)
            assertFalse(receiver.blobs.stored.containsKey(aHash))
            sender.sync.stop()
            receiver.sync.stop()
        }

    @Test
    fun `an upload resumes from the spool's bitmap instead of restarting`() =
        runTest {
            val spool = FakeSpool()
            val (aHash, bytes) = image()
            val sender = member(spool, alice, bob, blobs = FakeBlobs(aHash to bytes))
            sender.custody.store(
                dmFrame(fid("m1"), from = alice, to = bob, sentAt = now, attachmentHash = aHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )
            sender.sync.start(backgroundScope)
            pump(rounds = 12)
            val aid = aidHex(alice, bob, aHash)
            assertEquals(3, spool.chunkCount(scopeHex(alice, bob), aid))

            // The spool loses the middle chunk. The bitmap is what tells the client which one — asked again on
            // the next session (or a timed round ten minutes on): a pushed-whole attachment is settled against
            // the connection it was pushed over, and nothing on the wire says a spool dropped a chunk.
            spool.dropChunk(scopeHex(alice, bob), aid, index = 1)
            spool.chunksPut.clear()
            spool.dropSockets()
            pump(rounds = 12)

            assertEquals(3, spool.chunkCount(scopeHex(alice, bob), aid))
            assertEquals("only the missing chunk is re-sent", listOf("$aid:1"), spool.chunksPut)
            sender.sync.stop()
        }

    @Test
    fun `a chunk that fails to open quarantines the attachment instead of being refetched forever`() =
        runTest {
            val spool = FakeSpool()
            val (aHash, bytes) = image()
            val sender = member(spool, alice, bob, blobs = FakeBlobs(aHash to bytes))
            val receiver = member(spool, bob, alice)
            sender.custody.store(
                dmFrame(fid("m1"), from = alice, to = bob, sentAt = now, attachmentHash = aHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )
            sender.sync.start(backgroundScope)
            pump(rounds = 12)
            // A spool is untrusted storage: it can serve bytes no member ever sealed.
            spool.corruptChunk(scopeHex(alice, bob), aidHex(alice, bob, aHash), index = 0)

            receiver.sync.start(backgroundScope)
            pump(rounds = 16)
            val afterFirst = spool.chunkGets.size
            pump(rounds = 16)

            assertFalse("the garbage never becomes a stored image", receiver.blobs.stored.containsKey(aHash))
            assertEquals(1, receiver.metrics.spool().spoolInvalid)
            // The whole point of the invalid set: an accounted failure, not an infinite re-pull.
            assertEquals("no further aget after the quarantine", afterFirst, spool.chunkGets.size)
            sender.sync.stop()
            receiver.sync.stop()
        }

    @Test
    fun `a quarantined attachment is tried again once the scope TTL has passed`() =
        runTest {
            val spool = FakeSpool()
            val (aHash, bytes) = image()
            val sender = member(spool, alice, bob, blobs = FakeBlobs(aHash to bytes))
            var receiverClock = now
            // Custody kept long, so the frame still names the attachment once the receiver's clock moves.
            val receiver = member(spool, bob, alice, custody = FakeCustody(ttlMs = Long.MAX_VALUE / 2), clock = { receiverClock })
            val scope = scopeHex(alice, bob)
            val aid = aidHex(alice, bob, aHash)
            sender.custody.store(
                dmFrame(fid("m1"), from = alice, to = bob, sentAt = now, attachmentHash = aHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )
            sender.sync.start(backgroundScope)
            pump(rounds = 12)
            spool.corruptChunk(scope, aid, index = 0)
            receiver.sync.start(backgroundScope)
            pump(rounds = 16)
            assertEquals(1, receiver.metrics.spool().spoolInvalid)
            assertFalse(receiver.blobs.stored.containsKey(aHash))

            // The spool's copy is mended (a member re-uploads the chunk it now lacks, on its next session —
            // a pushed-whole attachment is settled for the connection it went over) — which within the
            // horizon changes nothing, because a bad chunk is permanent for a copy's life (S-6.5-7) and a
            // retry against a hostile spool buys nothing at all.
            spool.dropChunk(scope, aid, index = 0)
            spool.dropSockets()
            pump(rounds = 70)
            assertEquals(3, spool.chunkCount(scope, aid))
            val gets = spool.chunkGets.size
            pump(rounds = 70)
            assertEquals("still quarantined inside the horizon", gets, spool.chunkGets.size)

            // Past the scope TTL the poisoned copy would be gone from an honest spool anyway (S-6.5-4), so
            // the quarantine is spent: one more look, and the image lands.
            receiverClock = now + ScopeRegistry.DEFAULT_TTL_MS + 1
            pump(rounds = 70)

            assertTrue("fetched after the horizon", receiver.blobs.stored.containsKey(aHash))
            assertEquals(listOf(aHash), receiver.obtained)
            sender.sync.stop()
            receiver.sync.stop()
        }

    @Test
    fun `an attachment whose frame has aged out is not uploaded`() =
        runTest {
            val spool = FakeSpool()
            val (aHash, bytes) = image()
            val sender = member(spool, alice, bob, blobs = FakeBlobs(aHash to bytes))
            // sentAt far enough back that the scope TTL has lapsed: §9.2's guard, on the frame that
            // references the image. Custody still holds it (its own TTL is longer than this gap).
            val stale = now - ScopeRegistry.DEFAULT_TTL_MS
            sender.custody.store(
                dmFrame(fid("m1"), from = alice, to = bob, sentAt = stale, attachmentHash = aHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )

            sender.sync.start(backgroundScope)
            pump(rounds = 12)

            assertEquals(emptyList<String>(), spool.chunksPut)
            assertEquals(0, sender.metrics.spool().spoolAttachPushed)
            sender.sync.stop()
        }

    @Test
    fun `a deferred attachment is neither uploaded nor asked about`() =
        runTest {
            val spool = FakeSpool()
            val (aHash, bytes) = image()
            val sender = member(spool, alice, bob, blobs = FakeBlobs(aHash to bytes), deferAttachment = { _, _ -> true })
            sender.custody.store(
                dmFrame(fid("m1"), from = alice, to = bob, sentAt = now, attachmentHash = aHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )

            sender.sync.start(backgroundScope)
            pump(rounds = 12)

            assertEquals(emptyList<String>(), spool.chunksPut)
            // Deferring before the `ahave` is the point: no chunks *and* no round trip.
            assertEquals(emptyList<String>(), spool.presenceAsks)
            assertEquals(0, sender.metrics.spool().spoolAttachPushed)
            assertTrue("the deferral is counted", sender.metrics.spool().spoolAttachDeferred > 0)
            // The frame itself is untouched by the gate — only its bytes wait.
            assertEquals(1, sender.metrics.spool().spoolPushed)
            sender.sync.stop()
        }

    @Test
    fun `a deferred attachment uploads once the deferral lapses`() =
        runTest {
            val spool = FakeSpool()
            val (aHash, bytes) = image()
            var deferring = true
            val sender = member(spool, alice, bob, blobs = FakeBlobs(aHash to bytes), deferAttachment = { _, _ -> deferring })
            sender.custody.store(
                dmFrame(fid("m1"), from = alice, to = bob, sentAt = now, attachmentHash = aHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )

            sender.sync.start(backgroundScope)
            pump(rounds = 12)
            assertEquals(emptyList<String>(), spool.chunksPut)

            // The peer walks out of range: the gate re-opens by itself, no restart, no new custody event.
            deferring = false
            pump(rounds = 12)

            val aid = aidHex(alice, bob, aHash)
            assertEquals(ScopeAttachments.chunkCount(bytes.size), spool.chunkCount(scopeHex(alice, bob), aid))
            sender.sync.stop()
        }

    @Test
    fun `an attachment its recipient provably holds is never uploaded, asked about or deferred`() =
        runTest {
            val spool = FakeSpool()
            val (aHash, bytes) = image()
            // Deferral says push (the peers parted): the proof still holds it, for good (ADR 2026-10.vy27).
            val sender =
                member(
                    spool,
                    alice,
                    bob,
                    blobs = FakeBlobs(aHash to bytes),
                    deferAttachment = { _, _ -> false },
                    provedAttachment = { _, ref -> ref.aHash == aHash },
                )
            sender.custody.store(
                dmFrame(fid("m1"), from = alice, to = bob, sentAt = now, attachmentHash = aHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )

            sender.sync.start(backgroundScope)
            pump(rounds = 12)

            assertEquals(emptyList<String>(), spool.chunksPut)
            assertEquals("settled before the `ahave`: no round trip", emptyList<String>(), spool.presenceAsks)
            assertEquals(0, sender.metrics.spool().spoolAttachPushed)
            assertEquals("settled, not deferred", 0L, sender.metrics.spool().spoolAttachDeferred)
            assertEquals("counted once, then settled: no retry", 1L, sender.metrics.spool().spoolAttachProved)
            assertEquals("the frame goes up regardless (C-9.5-9)", 1, sender.metrics.spool().spoolPushed)
            sender.sync.stop()
        }

    @Test
    fun `a deferral never blocks the fetch half`() =
        runTest {
            val spool = FakeSpool()
            val (aHash, bytes) = image()
            val sender = member(spool, alice, bob, blobs = FakeBlobs(aHash to bytes))
            // The receiver defers everything, yet holds none of the bytes: the gate is push-only, so it
            // must not starve a member of an image it is missing.
            val receiver = member(spool, bob, alice, deferAttachment = { _, _ -> true })
            sender.custody.store(
                dmFrame(fid("m1"), from = alice, to = bob, sentAt = now, attachmentHash = aHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )

            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump(rounds = 16)

            assertTrue("the image still landed", receiver.blobs.stored.containsKey(aHash))
            assertTrue(bytes.contentEquals(receiver.blobs.stored.getValue(aHash)))
            sender.sync.stop()
            receiver.sync.stop()
        }

    @Test
    fun `a photo our own screening refused is neither asked about nor fetched until it is no longer refused`() =
        runTest {
            val spool = FakeSpool()
            val (aHash, bytes) = image()
            val sender = member(spool, alice, bob, blobs = FakeBlobs(aHash to bytes))
            var receiverClock = now
            val receiver =
                member(
                    spool,
                    bob,
                    alice,
                    custody = FakeCustody(ttlMs = Long.MAX_VALUE / 2),
                    blobs = FakeBlobs().apply { refused += aHash },
                    clock = { receiverClock },
                )
            sender.custody.store(
                dmFrame(fid("m1"), from = alice, to = bob, sentAt = now, attachmentHash = aHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )
            sender.sync.start(backgroundScope)
            pump(rounds = 12)
            sender.sync.stop()
            val aid = aidHex(alice, bob, aHash)
            assertEquals(ScopeAttachments.chunkCount(bytes.size), spool.chunkCount(scopeHex(alice, bob), aid))
            spool.presenceAsks.clear()

            receiver.sync.start(backgroundScope)
            pump(rounds = 16)

            // #109: the bytes were dropped on purpose and would only be dropped again — the radios skip it too.
            assertTrue("the frame itself still crossed", receiver.delivered.isNotEmpty())
            assertFalse(receiver.blobs.stored.containsKey(aHash))
            assertEquals(emptyList<String>(), receiver.obtained)
            assertEquals("no round trip for a refused photo", emptyList<String>(), spool.presenceAsks)
            assertEquals(emptyList<String>(), spool.chunkGets)

            // Filtering turned off: the next timed look fetches it like any other.
            receiver.blobs.refused.clear()
            receiverClock = now + 11 * 60_000L
            pump(rounds = 70)

            assertTrue("fetched once no longer refused", receiver.blobs.stored.containsKey(aHash))
            assertEquals(listOf(aHash), receiver.obtained)
            receiver.sync.stop()
        }

    @Test
    fun `deferred attachments spend none of the round-trip budget`() =
        runTest {
            val spool = FakeSpool()
            // Five attachments against a four-per-round budget: the first four defer, so the fifth must
            // still be reached in the same round rather than queuing behind them.
            val images = (1..5).map { image(1_000 * it) }
            val held = FakeBlobs(*images.toTypedArray())
            val wanted = images.last().first
            val sender =
                member(spool, alice, bob, blobs = held, deferAttachment = { _, ref -> ref.aHash != wanted })
            images.forEachIndexed { index, (aHash, _) ->
                sender.custody.store(
                    dmFrame(fid("m$index"), from = alice, to = bob, sentAt = now, attachmentHash = aHash),
                    ForwardStore.ORIGIN_SELF,
                    now,
                )
            }

            sender.sync.start(backgroundScope)
            pump(rounds = 12)

            val aid = aidHex(alice, bob, wanted)
            assertEquals(listOf(aid), spool.presenceAsks.distinct())
            assertEquals(
                ScopeAttachments.chunkCount(images.last().second.size),
                spool.chunkCount(scopeHex(alice, bob), aid),
            )
            sender.sync.stop()
        }

    @Test
    fun `a group photo crosses on the groupupdate that advertises it`() =
        runTest {
            val spool = FakeSpool()
            val groupId = "g-00112233445566778899aabb"
            val roster = setOf(alice, bob, carol)
            val group = GroupScopeRoots(groupId, roster, groupRoot, rootVersion = 1)
            val (photoHash, bytes) = image(60_000)
            val sender = member(spool, alice, bob, groups = listOf(group), blobs = FakeBlobs(photoHash to bytes))
            val receiver = member(spool, bob, alice, groups = listOf(group))
            sender.custody.store(
                groupUpdateFrame(
                    fid("gu1"),
                    from = alice,
                    groupId = groupId,
                    members = roster.toList(),
                    sentAt = now,
                    photoHash = photoHash,
                ),
                ForwardStore.ORIGIN_SELF,
                now,
            )

            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump(rounds = 16)

            assertEquals(listOf(fid("gu1")), receiver.delivered.map { it.id })
            assertTrue("the group photo landed", receiver.blobs.stored.containsKey(photoHash))
            assertTrue(bytes.contentEquals(receiver.blobs.stored.getValue(photoHash)))
            // GroupInfo carries no mime, so the fetcher's fallback names it.
            assertEquals("image/jpeg", receiver.blobs.mimes[photoHash])
            sender.sync.stop()
            receiver.sync.stop()
        }

    // ---- casts (experimental, docs/VOICE_CALLS.md §2.7) ----

    private val castBounds = SpoolCastInfo(maxBytes = 2_048, rate = 4)
    private val castSig = ByteArray(ScopeCrypto.SIG_BYTES) { 7 }

    private fun talk(
        id: String = "t1",
        from: String = alice,
        to: String = bob,
    ) = RelayEnvelope(
        type = FrameType.TALK,
        id = id,
        senderId = from,
        sentAt = now,
        recipientId = to,
        payload = ByteArray(40) { it.toByte() },
    )

    private suspend fun Member.castTalk(
        env: RelayEnvelope = talk(),
        sig: ByteArray = castSig,
        peerTicketed: Boolean = false,
    ): Int =
        sync.cast(peerId = env.recipientId!!, peerTicketed = peerTicketed, env = env, sig = sig, signed = WireCodec.encodeEnvelope(env))

    @Test
    fun `a cast reaches the peer as a frame for it alone, and the spool stores nothing`() =
        runTest {
            val spool = FakeSpool(cast = castBounds)
            val sender = member(spool, alice, bob)
            val receiver = member(spool, bob, alice)
            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump()

            assertTrue(sender.sync.canCast(bob, peerTicketed = false))
            // The relay row's call details read the hello's bounds off the status.
            assertEquals(
                2_048,
                sender.sync
                    .status()
                    .single()
                    .cast
                    ?.maxBytes,
            )
            assertEquals(1, sender.castTalk())
            pump(1)

            assertEquals(listOf("t1"), receiver.delivered.map { it.id })
            val wire = receiver.wires.single()
            assertEquals("delivered to this phone and flooded nowhere", 0, wire.ttl)
            assertFalse(wire.relay)
            assertTrue("the cast's signature rides as the frame's own", wire.sig.contentEquals(castSig))
            assertTrue("nothing was pushed: a cast is never a blob", spool.pushed.isEmpty())
            assertEquals(1, spool.castsFanned)
            assertFalse(receiver.custody.has("t1"))
            assertEquals(0, receiver.metrics.spool().spoolInvalid)
            assertEquals(0, receiver.metrics.spool().spoolAccounted)
            sender.sync.stop()
            receiver.sync.stop()
        }

    @Test
    fun `a spool that never advertised casts is sent none`() =
        runTest {
            val spool = FakeSpool()
            val sender = member(spool, alice, bob)
            sender.sync.start(backgroundScope)
            pump()
            assertFalse(sender.sync.canCast(bob, peerTicketed = true))
            assertNull(
                sender.sync
                    .status()
                    .single()
                    .cast,
            )
            assertEquals(0, sender.castTalk())
            assertFalse(SpoolRecordType.CAST in spool.skippedRecords)
            assertTrue(spool.casts.isEmpty())
            // And a peer this phone shares no DM scope with is cast nothing anywhere.
            assertFalse(sender.sync.canCast("nobody", peerTicketed = true))
            sender.sync.stop()
        }

    @Test
    fun `a spool that would cast covers the peer only once the peer was seen on it`() =
        runTest {
            // C-3.3-5: the walkie's "through the relay" needs the peer on that relay; a cast it would take but fan out to
            // nobody (two phones on disjoint relays, device-observed 2026-10-14) is still cast, but labelled for what it is.
            val spool = FakeSpool(cast = castBounds)
            val sender = member(spool, alice, bob)
            val receiver = member(spool, bob, alice)
            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump()
            assertTrue(sender.sync.canCast(bob, peerTicketed = false))
            assertFalse("subscribed and converged says nothing about bob", sender.sync.castCovers(bob, peerTicketed = false))

            receiver.custody.store(dmFrame(fid("m1"), from = bob, to = alice, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            receiver.sync.onCustodyChanged()
            pump()
            assertTrue("bob's own fresh frame came through this spool", sender.sync.castCovers(bob, peerTicketed = false))
            assertFalse(sender.sync.castCovers("nobody", peerTicketed = false))
            sender.sync.stop()
            receiver.sync.stop()
        }

    @Test
    fun `a cast goes to every spool that carries it`() =
        runTest {
            val one = FakeSpool(cast = castBounds)
            val two = FakeSpool(cast = castBounds)
            val urls = listOf("ws://one.test/spool/v1", "ws://two.test/spool/v1")
            val relays = Relays(mapOf(urls[0] to one, urls[1] to two))
            val sender = member(one, alice, bob, dialer = relays, urls = urls)
            val receiver = member(one, bob, alice, dialer = relays, urls = urls)
            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump()

            assertEquals(2, sender.castTalk())
            pump(1)
            assertEquals(1, one.castsFanned)
            assertEquals(1, two.castsFanned)
            // Both copies are the same frame; the router behind `deliver` keeps one (`SeenSet`, by id).
            assertEquals(listOf("t1", "t1"), receiver.delivered.map { it.id })
            sender.sync.stop()
            receiver.sync.stop()
        }

    /** A cast never waits on a heal round: the round lock is held, for up to a request timeout, while the spool is silent. */
    @Test
    fun `a heal round stuck on a silent spool does not hold a cast back`() =
        runTest {
            val spool = FakeSpool(cast = castBounds)
            val sender = member(spool, alice, bob)
            val receiver = member(spool, bob, alice)
            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump()

            spool.mute()
            sender.custody.store(dmFrame(fid("m1"), from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            sender.sync.onCustodyChanged()
            pump(2)
            assertTrue("the round is waiting on a request the spool swallowed", spool.swallowed.isNotEmpty())

            assertEquals(1, sender.castTalk())
            runCurrent()
            assertEquals(listOf("t1"), receiver.delivered.map { it.id })
            sender.sync.stop()
            receiver.sync.stop()
        }

    @Test
    fun `a cast the carry gate refuses, or outside the cast set, is dropped and remembered nowhere`() =
        runTest {
            val spool = FakeSpool(cast = castBounds)
            val sender = member(spool, alice, bob)
            val receiver = member(spool, bob, alice, carryGate = { wire, _ -> wire.sig.contentEquals(castSig) })
            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump()

            assertEquals(1, sender.castTalk(talk(id = "forged"), sig = ByteArray(ScopeCrypto.SIG_BYTES)))
            // A chat frame is not cast, whatever the caller asks: the outward set refuses it before the seal.
            assertEquals(0, sender.sync.cast(bob, false, dmFrame(fid("c1"), alice, bob).envelope, castSig, ByteArray(8)))
            assertEquals(1, sender.castTalk(talk(id = "real")))
            pump(1)
            assertEquals(listOf("real"), receiver.delivered.map { it.id })
            assertEquals(0, receiver.metrics.spool().spoolInvalid)
            sender.sync.stop()
            receiver.sync.stop()
        }

    /**
     * On a spool that sells casts (`ent`), a cast leaves only beside a cast ticket — ours, or the peer's by its walkie
     * signal — and the spool carries it only once one subscriber of the scope holds one.
     */
    @Test
    fun `an ent spool carries casts once a subscriber holds a cast ticket`() =
        runTest {
            val lab = FakeSpool(cast = castBounds, ent = true)
            val labUrl = "ws://lab.example:9480/spool/v1"
            val tickets = SpoolTickets(SpoolTicketsTest.MemoryJournal()) { now }
            val payer = member(lab, alice, bob, urls = listOf(labUrl), tickets = tickets)
            val friend = member(lab, bob, alice, urls = listOf(labUrl))
            payer.sync.start(backgroundScope)
            friend.sync.start(backgroundScope)
            pump()

            assertFalse(payer.sync.canCast(bob, peerTicketed = false))
            assertFalse(payer.sync.castTicketed())
            // The friend believes the payer holds a ticket, but nobody does yet: the cast goes, and the spool drops it.
            assertEquals(1, friend.castTalk(talk(id = "early", from = bob, to = alice), peerTicketed = true))
            pump(1)
            assertTrue(payer.delivered.isEmpty())

            // A TURN-only ticket unlocks nothing; one that carries the cast flag does, both ways.
            assertTrue(
                tickets.import(
                    labUrl,
                    SpoolTicketsTest.ticket(fl = SpoolTicket.FL_TURN or SpoolTicket.FL_CAST).text,
                ) is SpoolTickets.Import.Held,
            )
            pump(2)
            assertTrue(payer.sync.castTicketed())
            assertTrue(payer.sync.canCast(bob, peerTicketed = false))
            assertEquals(1, payer.castTalk(talk(id = "paid")))
            assertEquals(1, friend.castTalk(talk(id = "free", from = bob, to = alice), peerTicketed = true))
            pump(1)
            assertEquals(listOf("paid"), friend.delivered.map { it.id })
            assertEquals(listOf("free"), payer.delivered.map { it.id })
            payer.sync.stop()
            friend.sync.stop()
        }

    @Test
    fun `a scope is subscribed at its own relays alone, and a worker follows it when they move`() =
        runTest {
            // ADR 2026-10.8kfx: the base is public-1, and Bob's DM lives at public-2 (both homes there), which this phone
            // dials for it alone; when the DM moves to public-1 the public-2 worker goes.
            val p1 = PublicSpools.LEGACY_URL
            val p2 = PublicSpools.urlOf("public-2.spool.getknit.app")
            val one = FakeSpool()
            val two = FakeSpool()
            val directory = listOf("public-1", "public-2").map { PublicSpool("$it.spool.getknit.app", PublicSpool.OPEN, 1) }
            var home = "public-2.spool.getknit.app"
            var configured = listOf(p2)
            val relays = mutableListOf<Set<String>>()
            val member =
                member(
                    one,
                    alice,
                    bob,
                    dialer = Relays(mapOf(p1 to one, p2 to two)),
                    urls = listOf(p1),
                    placement = {
                        ScopePlacement(
                            configs = mapOf(bob to ScopeConfig(bob, null, bob, configured, 400, 1_000L, 64, 1)),
                            declared = setOf(bob),
                            home = home,
                            active = setOf(p1),
                            parked = emptySet(),
                            directory = directory,
                        )
                    },
                    onRelaysChanged = { relays += it },
                )
            member.sync.start(backgroundScope)
            pump()
            val scope = scopeHex(alice, bob)
            assertEquals(setOf(p1, p2), relays.last())
            assertTrue(scope in two.subscribedScopes)
            // The public-1 worker has had its hello, so its not subscribing is a decision, not a round still to come.
            assertTrue(
                member.sync
                    .status()
                    .single { it.url == p1 }
                    .connected,
            )
            assertFalse(scope in one.subscribedScopes)

            home = PublicSpools.LEGACY_HOST
            configured = listOf(p1)
            member.sync.onScopeTableChanged()
            pump()
            assertEquals(setOf(p1), relays.last())
            assertTrue(scope in one.subscribedScopes)
            assertEquals(listOf(p1), member.sync.status().map { it.url })
            member.sync.stop()
            assertEquals(emptySet<String>(), relays.last())
        }
}
