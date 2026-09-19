package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.CarriedFrame
import app.getknit.knit.mesh.ForwardStore
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.PRESENCE_FRESH_MS
import app.getknit.knit.mesh.SPOOL_COVER_MS
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.sha256Hex
import com.google.crypto.tink.subtle.X25519
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
    private val bob = "bbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val carol = "cccccccccccccccccccccccccc"
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
    )

    /** The local content-addressed store, in memory. Content-addressed, so a save is write-once. */
    private class FakeBlobs(
        vararg initial: Pair<String, ByteArray>,
    ) : ScopeBlobs {
        val stored = LinkedHashMap<String, ByteArray>().apply { putAll(initial) }
        val mimes = LinkedHashMap<String, String>()

        override suspend fun has(aHash: String): Boolean = aHash in stored

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
    ): Member {
        val metrics = MeshMetrics()
        val delivered = mutableListOf<RelayEnvelope>()
        val obtained = mutableListOf<String>()
        val sync =
            ScopeSync(
                registry =
                    ScopeRegistry(
                        selfId = { self },
                        roots = { roots() },
                        groupRoots = { groups },
                        pairs = { pairs() },
                    ),
                dialer = dialer,
                store = custody,
                selfId = { self },
                urls = { listOf(url) },
                canCarry = carryGate,
                // Stands in for MeshRouter.handleInbound: deliver, then capture into custody exactly as
                // InboundPipeline.onDeliver does, so the next heal round folds the frame into our digest.
                deliver = { wire, env, _ ->
                    delivered.add(env)
                    custody.store(CarriedFrame(env, wire.sig, wire.signed), ForwardStore.ORIGIN_RELAY, now)
                },
                blobs = blobs,
                onAttachmentObtained = { obtained.add(it) },
                deferAttachment = deferAttachment,
                onPresenceChanged = presence,
                metrics = metrics,
                clock = clock,
                jitter = { 0L },
            )
        return Member(custody, sync, metrics, delivered, blobs, obtained)
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
            sender.custody.store(dmFrame("m1", from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)

            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump()

            assertEquals("the sender pushed its custody", 1, spool.pushed.size)
            assertEquals(listOf("m1"), receiver.delivered.map { it.id })
            assertTrue("the bridged frame lands in the receiver's custody", receiver.custody.has("m1"))
            assertEquals(1, sender.metrics.snapshot().spoolPushed)
            assertEquals(1, receiver.metrics.snapshot().spoolBridged)
            sender.sync.stop()
            receiver.sync.stop()
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

            sender.custody.store(dmFrame("m1", from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            sender.sync.start(backgroundScope)
            pump()

            assertEquals(listOf("m1"), receiver.delivered.map { it.id })
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
            sender.custody.store(dmFrame("m1", from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)

            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump()

            assertEquals("the frame still bridges — presence is never a delivery gate", listOf("m1"), receiver.delivered.map { it.id })
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

            sender.custody.store(dmFrame("m1", from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
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
            sender.custody.store(dmFrame("m1", from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
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

            val tick = dmFrame("tick-1", from = alice, to = bob, sentAt = now)
            assertTrue(sender.sync.pushDirect(bob, tick.envelope, tickWire(tick)))
            pump()

            assertEquals("the peer has it", listOf("tick-1"), receiver.delivered.map { it.id })
            assertEquals(1, spool.pushed.size)
            assertFalse("the pusher never custodied it", sender.custody.has("tick-1"))
            assertEquals(1, sender.metrics.snapshot().spoolPushed)

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
            val tick = dmFrame("tick-1", from = alice, to = bob, sentAt = now)
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
            val stray = dmFrame("stray", from = alice, to = "carol", sentAt = now)
            assertFalse(sender.sync.pushDirect(bob, stray.envelope, tickWire(stray)))
            val stale = dmFrame("stale", from = alice, to = bob, sentAt = now - ScopeRegistry.DEFAULT_TTL_MS)
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
            sender.custody.store(profileFrame("pa", from = alice, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            sender.custody.store(dmFrame("intro", from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            receiver.custody.store(profileFrame("pb", from = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)

            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump()

            val pairHex = hex(ScopeCrypto.pairScopeId(secretAtAlice, alice, bob))
            assertEquals("both sides derive one pair scope", pairHex, hex(ScopeCrypto.pairScopeId(secretAtBob, bob, alice)))
            assertEquals(setOf("pb"), sender.delivered.map { it.id }.toSet())
            assertEquals(setOf("pa", "intro"), receiver.delivered.map { it.id }.toSet())
            assertEquals(3, spool.liveIds(pairHex).size)

            // Both sessions confirmed: the driver stops naming the peer and the pair scope leaves the table.
            alicePairs = emptyList()
            bobPairs = emptyList()
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
                groupChatFrame("gc1", from = carol, groupId = groupId, members = roster.toList(), sentAt = now),
                ForwardStore.ORIGIN_RELAY,
                now,
            )
            sender.custody.store(groupLeaveFrame("gl1", from = carol, groupId = groupId, sentAt = now), ForwardStore.ORIGIN_RELAY, now)

            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump()

            assertEquals(setOf("gc1", "gl1"), receiver.delivered.map { it.id }.toSet())
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
                groupChatFrame("gc1", from = bob, groupId = groupId, members = roster.toList(), sentAt = now),
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

    @Test
    fun `heal is bidirectional — a member refills a spool that lost everything`() =
        runTest {
            val spool = FakeSpool()
            val holder = member(spool, alice, bob)
            listOf("m1", "m2", "m3").forEach {
                holder.custody.store(dmFrame(it, from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            }

            holder.sync.start(backgroundScope)
            pump()

            // A fresh spool starts empty and heals from whichever member is connected — the "client union
            // is the federation" property, with no spool-to-spool traffic anywhere.
            assertEquals(3, spool.liveIds(scopeHex(alice, bob)).size)
            assertEquals(3, holder.metrics.snapshot().spoolPushed)
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
            val frame = dmFrame("m1", from = alice, to = bob, sentAt = now)
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
                sender.custody.store(dmFrame(it, from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            }

            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump()

            // Events and the pull set race by construction — the pull set is computed before the events
            // land — so the same blob legitimately arrives twice. Re-delivering is harmless (the router's
            // SeenSet dedups) but it would double-count the number Diagnostics shows as messages received.
            assertEquals(3, receiver.delivered.size)
            assertEquals(3, receiver.metrics.snapshot().spoolBridged)
            assertEquals(3, receiver.metrics.snapshot().spoolPulled)
            sender.sync.stop()
            receiver.sync.stop()
        }

    @Test
    fun `a frame swept from custody while the spool still holds it is not re-pulled every heal round`() =
        runTest {
            val spool = FakeSpool()
            val holder = member(spool, alice, bob)
            holder.custody.store(dmFrame("m1", from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            holder.sync.start(backgroundScope)
            pump()
            assertEquals("pushed and converged first", 1, spool.pushed.size)

            // The scope TTL (48 h) deliberately outlives mesh custody (24 h), so a frame we already
            // delivered gets swept locally while the spool keeps it for another day. From then on it is
            // absent from `local` forever and our digest can never match — and re-pulling it achieves
            // nothing, because the custody store refuses it as dead on arrival every single time.
            holder.custody.sweep("m1")
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
            holder.custody.store(dmFrame("m1", from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            holder.sync.start(backgroundScope)
            pump()
            holder.custody.sweep("m1")
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
            assertEquals("bridged once, not once per connection", 1, holder.metrics.snapshot().spoolBridged)
            holder.sync.stop()
        }

    @Test
    fun `a scope the spool outlives converges anyway, on the accounted band`() =
        runTest {
            val spool = FakeSpool()
            val holder = member(spool, alice, bob)
            holder.custody.store(dmFrame("m1", from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            holder.sync.start(backgroundScope)
            pump()
            holder.custody.sweep("m1")
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
            assertEquals(1, holder.metrics.snapshot().spoolAccounted)
            holder.sync.stop()
        }

    @Test
    fun `an accounted blob the spool finally expires leaves our digest with it`() =
        runTest {
            val spool = FakeSpool()
            val holder = member(spool, alice, bob)
            holder.custody.store(dmFrame("m1", from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            holder.sync.start(backgroundScope)
            pump()
            val blobId = spool.pushed.single()
            holder.custody.sweep("m1")
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
            member.custody.store(dmFrame("m1", from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
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
            member.custody.store(dmFrame("m1", from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
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
        )

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
            // The 15 s reconcile wake keeps the heal loop asking; each LIST times out at 30 s, so the
            // third strike lands at 90 s and the redial two seconds later.
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
            assertEquals("its garbage was pulled once the spool answered", 1, holder.metrics.snapshot().spoolInvalid)
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
            val afterFirst = victim.metrics.snapshot().spoolInvalid
            pump()

            assertTrue("the garbage must be quarantined", afterFirst >= 1)
            assertTrue("nothing forged is ever delivered", victim.delivered.isEmpty())
            assertEquals("a quarantined id is never re-pulled", afterFirst, victim.metrics.snapshot().spoolInvalid)
            assertEquals(0, victim.metrics.snapshot().spoolBridged)
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
            val afterFirst = victim.metrics.snapshot().spoolInvalid
            pump()

            assertTrue("an oversize answer must still be accounted", afterFirst >= 1)
            assertTrue(victim.delivered.isEmpty())
            assertEquals("a quarantined id is never re-pulled", afterFirst, victim.metrics.snapshot().spoolInvalid)
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

            assertEquals("an oversize event is not ours to quarantine", 0, victim.metrics.snapshot().spoolInvalid)
            assertTrue(victim.delivered.isEmpty())
            victim.sync.stop()
        }

    @Test
    fun `a blob whose sender fails the mesh carry gate is quarantined, not delivered`() =
        runTest {
            val spool = FakeSpool()
            val sender = member(spool, alice, bob)
            val receiver = member(spool, bob, alice, carryGate = { _, _ -> false })
            sender.custody.store(dmFrame("m1", from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)

            // Pushed before the receiver connects, so m1 reaches it by SUB → LIST → pull: the pull path is
            // the one C-9.3-1 quarantines on. (Arriving as a live event instead, it is released and dropped.)
            sender.sync.start(backgroundScope)
            pump()
            receiver.sync.start(backgroundScope)
            pump()

            assertTrue(receiver.delivered.isEmpty())
            assertFalse(receiver.custody.has("m1"))
            assertTrue(receiver.metrics.snapshot().spoolInvalid >= 1)
            sender.sync.stop()
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
            assertEquals("an unsolicited failure is not ours to quarantine", 0, victim.metrics.snapshot().spoolInvalid)
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

            assertEquals(1, victim.metrics.snapshot().spoolInvalid)
            assertEquals("asked for exactly once", 1, spool.pulled.count { it == id })
            assertTrue(victim.delivered.isEmpty())
            victim.sync.stop()
        }

    @Test
    fun `a hostile event for a genuine id does not block the pull of that id`() =
        runTest {
            val spool = FakeSpool()
            val victim = member(spool, bob, alice)
            val frame = dmFrame("m1", from = alice, to = bob, sentAt = now)
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

            assertEquals(listOf("m1"), victim.delivered.map { it.id })
            assertEquals(1, victim.metrics.snapshot().spoolBridged)
            assertEquals(0, victim.metrics.snapshot().spoolInvalid)
            victim.sync.stop()
        }

    @Test
    fun `an event flood cannot evict a delivered id from the per-connection guard`() =
        runTest {
            val spool = FakeSpool()
            val sender = member(spool, alice, bob)
            val receiver = member(spool, bob, alice)
            val scope = unhex(scopeHex(bob, alice))
            sender.custody.store(dmFrame("m1", from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            sender.sync.start(backgroundScope)
            pump()
            receiver.sync.start(backgroundScope)
            pump()
            assertEquals(1, receiver.metrics.snapshot().spoolBridged)

            // More garbage than the guard holds. Every one of these claims a slot and fails; if a claim
            // could evict, m1 would be pushed out and — once custody sweeps it — pulled and bridged again.
            repeat(600) { n ->
                val junk = ByteArray(32) { (it + n).toByte() }
                spool.gossip(SpoolCodec.encode(SpoolEvent(t = SpoolRecordType.EVENT, scope = scope, blobId = sha256(junk), data = junk)))
            }
            pump()
            receiver.custody.sweep("m1")
            pump(rounds = 120)

            assertEquals("m1 stayed guarded — bridged once, not once more after the sweep", 1, receiver.metrics.snapshot().spoolBridged)
            assertEquals(0, receiver.metrics.snapshot().spoolInvalid)
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
            assertTrue(victim.metrics.snapshot().spoolErrors >= 1)
            assertEquals("and nothing quarantined on its say-so", 0, victim.metrics.snapshot().spoolInvalid)
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
            assertEquals(1, victim.metrics.snapshot().spoolInvalid)
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
                dmFrame("old", from = alice, to = bob, sentAt = now - ScopeRegistry.DEFAULT_TTL_MS),
                ForwardStore.ORIGIN_SELF,
                now,
            )
            sender.custody.store(dmFrame("fresh", from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)

            sender.sync.start(backgroundScope)
            pump()

            assertEquals(1, spool.pushed.size)
            assertEquals(1, sender.metrics.snapshot().spoolPushed)
            sender.sync.stop()
        }

    @Test
    fun `a frame that fails the frame-set rule is never sealed into the scope`() =
        runTest {
            val spool = FakeSpool()
            val sender = member(spool, alice, bob)
            val carol = "cccccccccccccccccccccccccc"
            sender.custody.store(dmFrame("mine", from = alice, to = bob, sentAt = now), ForwardStore.ORIGIN_SELF, now)
            sender.custody.store(dmFrame("theirs", from = alice, to = carol, sentAt = now), ForwardStore.ORIGIN_SELF, now)

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
            sender.custody.store(profileFrame("p1", from = alice, sentAt = now), ForwardStore.ORIGIN_SELF, now)

            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump()

            assertEquals(listOf("p1"), receiver.delivered.map { it.id })
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
            holder.custody.store(profileFrame("p2", from = carol, sentAt = now), ForwardStore.ORIGIN_RELAY, now)

            holder.sync.start(backgroundScope)
            reader.sync.start(backgroundScope)
            pump()

            assertEquals(listOf("p2"), reader.delivered.map { it.id })
            holder.sync.stop()
            reader.sync.stop()
        }

    @Test
    fun `pull batches at the spool's maxPull and re-pulls the truncated remainder`() =
        runTest {
            val spool = FakeSpool(maxPull = 2)
            val holder = member(spool, alice, bob)
            val ids = (1..5).map { "m$it" }
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

    // --- Attachments, spec §4.5/§9.5 ---

    @Test
    fun `an image one member holds reaches the other through the spool`() =
        runTest {
            val spool = FakeSpool()
            val (aHash, bytes) = image()
            val sender = member(spool, alice, bob, blobs = FakeBlobs(aHash to bytes))
            val receiver = member(spool, bob, alice)
            sender.custody.store(
                dmFrame("m1", from = alice, to = bob, sentAt = now, attachmentHash = aHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )

            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump(rounds = 16)

            // 100 000 bytes at the spec's 48 KiB chunk: three chunks, uploaded whole.
            val aid = aidHex(alice, bob, aHash)
            assertEquals(3, spool.chunkCount(scopeHex(alice, bob), aid))
            assertEquals(3, sender.metrics.snapshot().spoolAttachPushed)

            // The receiver got the frame, then the bytes it names — verified against that same address.
            assertEquals(listOf("m1"), receiver.delivered.map { it.id })
            assertTrue("the image landed locally", receiver.blobs.stored.containsKey(aHash))
            assertTrue(bytes.contentEquals(receiver.blobs.stored.getValue(aHash)))
            assertEquals(listOf(aHash), receiver.obtained)
            assertEquals(1, receiver.metrics.snapshot().spoolAttachPulled)
            assertEquals(0, receiver.metrics.snapshot().spoolInvalid)
            sender.sync.stop()
            receiver.sync.stop()
        }

    @Test
    fun `a spool that advertises no attachment support is never sent an attachment record`() =
        runTest {
            val spool = FakeSpool(attachments = false)
            val (aHash, bytes) = image()
            val sender = member(spool, alice, bob, blobs = FakeBlobs(aHash to bytes))
            val receiver = member(spool, bob, alice)
            sender.custody.store(
                dmFrame("m1", from = alice, to = bob, sentAt = now, attachmentHash = aHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )

            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump(rounds = 16)

            // The frame plane is unaffected — that is what makes attachments additive.
            assertEquals(listOf("m1"), receiver.delivered.map { it.id })
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
                dmFrame("m1", from = alice, to = bob, sentAt = now, attachmentHash = aHash),
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
                dmFrame("m1", from = alice, to = bob, sentAt = now, attachmentHash = aHash),
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
            assertEquals(1, receiver.metrics.snapshot().spoolInvalid)
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
                dmFrame("m1", from = alice, to = bob, sentAt = now, attachmentHash = aHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )
            sender.sync.start(backgroundScope)
            pump(rounds = 12)
            spool.corruptChunk(scope, aid, index = 0)
            receiver.sync.start(backgroundScope)
            pump(rounds = 16)
            assertEquals(1, receiver.metrics.snapshot().spoolInvalid)
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
                dmFrame("m1", from = alice, to = bob, sentAt = stale, attachmentHash = aHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )

            sender.sync.start(backgroundScope)
            pump(rounds = 12)

            assertEquals(emptyList<String>(), spool.chunksPut)
            assertEquals(0, sender.metrics.snapshot().spoolAttachPushed)
            sender.sync.stop()
        }

    @Test
    fun `a deferred attachment is neither uploaded nor asked about`() =
        runTest {
            val spool = FakeSpool()
            val (aHash, bytes) = image()
            val sender = member(spool, alice, bob, blobs = FakeBlobs(aHash to bytes), deferAttachment = { _, _ -> true })
            sender.custody.store(
                dmFrame("m1", from = alice, to = bob, sentAt = now, attachmentHash = aHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )

            sender.sync.start(backgroundScope)
            pump(rounds = 12)

            assertEquals(emptyList<String>(), spool.chunksPut)
            // Deferring before the `ahave` is the point: no chunks *and* no round trip.
            assertEquals(emptyList<String>(), spool.presenceAsks)
            assertEquals(0, sender.metrics.snapshot().spoolAttachPushed)
            assertTrue("the deferral is counted", sender.metrics.snapshot().spoolAttachDeferred > 0)
            // The frame itself is untouched by the gate — only its bytes wait.
            assertEquals(1, sender.metrics.snapshot().spoolPushed)
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
                dmFrame("m1", from = alice, to = bob, sentAt = now, attachmentHash = aHash),
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
    fun `a deferral never blocks the fetch half`() =
        runTest {
            val spool = FakeSpool()
            val (aHash, bytes) = image()
            val sender = member(spool, alice, bob, blobs = FakeBlobs(aHash to bytes))
            // The receiver defers everything, yet holds none of the bytes: the gate is push-only, so it
            // must not starve a member of an image it is missing.
            val receiver = member(spool, bob, alice, deferAttachment = { _, _ -> true })
            sender.custody.store(
                dmFrame("m1", from = alice, to = bob, sentAt = now, attachmentHash = aHash),
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
                    dmFrame("m$index", from = alice, to = bob, sentAt = now, attachmentHash = aHash),
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
                groupUpdateFrame("gu1", from = alice, groupId = groupId, members = roster.toList(), sentAt = now, photoHash = photoHash),
                ForwardStore.ORIGIN_SELF,
                now,
            )

            sender.sync.start(backgroundScope)
            receiver.sync.start(backgroundScope)
            pump(rounds = 16)

            assertEquals(listOf("gu1"), receiver.delivered.map { it.id })
            assertTrue("the group photo landed", receiver.blobs.stored.containsKey(photoHash))
            assertTrue(bytes.contentEquals(receiver.blobs.stored.getValue(photoHash)))
            // GroupInfo carries no mime, so the fetcher's fallback names it.
            assertEquals("image/jpeg", receiver.blobs.mimes[photoHash])
            sender.sync.stop()
            receiver.sync.stop()
        }
}
