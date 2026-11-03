package app.getknit.knit.mesh

import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.crypto.PresenceKeyFrame
import app.getknit.knit.mesh.crypto.PresenceKeyPolicy
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PresenceKeyEngine] (ADR 2026-11.xp6d) over in-memory stores and seams: who is handed which key and when, what a
 * removal, a corrected clock and a restore do, the order of grow, remove and send across passes, and two passes that
 * meet at its one lock (`twoConcurrentPassesMintOneGeneration`). The policy's branches are
 * `PresenceKeyPolicyTest`'s; the pair over real stacks is `PresenceKeyLabTest`'s.
 */
class PresenceKeyEngineTest {
    private class Rig {
        var wall = 1_793_577_600_000L
        var elapsed = 0L
        var direct = setOf("alice", "bob")

        /** The live links; assigning registers the arrivals and departures as the neighbour pass does. */
        var links = listOf(peer("alice"), peer("bob"))
            set(value) {
                val before = field.map { it.nodeId }.toSet()
                val after = value.map { it.nodeId }.toSet()
                field = value
                engine.onLinkDepart(before - after)
                engine.onLinkUp((after - before).toList())
            }
        val confirmed = mutableSetOf("alice", "bob")
        val roots = mutableMapOf("alice" to 1L, "bob" to 1L)
        val pinned = mutableMapOf<String, Long>()

        /** `(peer, content)` of every presence-key frame sealed, in order. */
        val sealed = mutableListOf<Pair<String, MessageContent>>()

        /** `(peer, frame id)` of every frame handed to a link. */
        val sentFrames = mutableListOf<Pair<String, String>>()

        /** `(peer, remark)` of every link reset sealed: a seal re-roots the session (a fresh tag), a remark keeps it. */
        val resetsSealed = mutableListOf<String>()
        val remarks = mutableListOf<String>()
        var declineResets = false

        /** Whether the session's init is still against the pinned prekey, per peer (default: yes). */
        val prekeyMoved = mutableSetOf<String>()

        /** Peers whose send throws. */
        val failSendTo = mutableSetOf<String>()
        private var nextRoot = 100L

        /** The store's writes in order: what a crash between two of them would leave. */
        val writes = mutableListOf<String>()
        val store = MemoryStore(writes)
        private var ids = 0
        val engine =
            PresenceKeyEngine(
                store = store,
                direct = { direct },
                linked = { links },
                pinnedCapabilities = { pinned[it] },
                rootTagFor = { roots[it] },
                sessionConfirmed = { it in confirmed },
                sealKeys = { peer, content ->
                    if (peer !in confirmed) {
                        null
                    } else {
                        sealed += peer to content
                        PresenceKeyEngine.Sealed(wire(), "pk-${ids++}", roots.getValue(peer))
                    }
                },
                sealReset = { peer, remark ->
                    if (declineResets) {
                        null
                    } else {
                        if (remark) {
                            remarks += peer
                        } else {
                            resetsSealed += peer
                            roots[peer] = nextRoot++
                            prekeyMoved -= peer
                        }
                        resetFrame(peer, roots.getValue(peer))
                    }
                },
                pendingReset = { peer ->
                    PresenceKeyEngine.PendingReset(
                        ownPendingReset = peer in resetsSealed && peer !in confirmed,
                        initOnPinnedPrekey = peer !in prekeyMoved,
                    )
                },
                send = { peer, frame ->
                    check(peer.nodeId !in failSendTo) { "link gone" }
                    sentFrames += peer.nodeId to frame.id
                },
                wall = { wall },
                elapsed = { elapsed },
            )

        init {
            engine.onLinkUp(links.map { it.nodeId })
        }

        /** [peer]'s link goes and a new one comes up, as the neighbour pass registers them, with the departure's clear. */
        suspend fun relink(peer: String) {
            engine.onLinkDepart(listOf(peer))
            engine.onLinksDown(listOf(peer))
            engine.onLinkUp(listOf(peer))
        }

        /** The generations each frame sealed to [peer] carried, in order. */
        fun handed(peer: String): List<List<Long>> =
            sealed.filter { it.first == peer }.map { (_, content) ->
                val pk = checkNotNull(content.pk)
                listOfNotNull(pk.generation, pk.nextGeneration)
            }

        suspend fun keys() = PresenceKeyPolicy.keysOf(store.ownKeys())

        private fun wire() = WireEnvelope(relay = false, sig = ByteArray(64), signed = ByteArray(8))

        /** A real signed frame, so a kept copy decodes back to its id. */
        private fun resetFrame(
            peer: String,
            root: Long,
        ): PresenceKeyEngine.Sealed {
            val id = "reset-${ids++}"
            val env = RelayEnvelope(type = FrameType.CHAT, id = id, senderId = "me", recipientId = peer, payload = ByteArray(4))
            return PresenceKeyEngine.Sealed(
                WireEnvelope(relay = false, sig = ByteArray(64), signed = WireCodec.encodeEnvelope(env)),
                id,
                root,
            )
        }

        /** Simulates our own other reset replacing the kept one (a heuristic or debug re-root): a new root, still ours. */
        fun reRootByAnotherReset(peer: String) {
            roots[peer] = nextRoot++
        }

        /** Simulates a crash after the seal and before its bytes were kept: the session holds the reset, the store nothing. */
        fun loseKeptBytes(peer: String) {
            store.owed[peer] = null
        }
    }

    @Test
    fun aLinkedCapableContactGetsTheCurrentAndNextKeysAndNoOneElseGetsAnything() =
        runTest {
            val rig = Rig()
            rig.direct = setOf("alice", "bob", "carol")
            rig.links = listOf(peer("alice"), peer("bob", caps = 0L), peer("stranger"), peer("carol"))
            rig.pinned["bob"] = 0L
            rig.confirmed -= "carol"
            rig.engine.start()

            val keys = rig.keys()
            assertEquals(listOf(listOf(keys.current!!.generation, keys.next!!.generation)), rig.handed("alice"))
            assertTrue("a peer whose HELLO and profile lack the bit is owed nothing", rig.handed("bob").isEmpty())
            assertTrue("a stranger is never handed a key", rig.handed("stranger").isEmpty())
            assertTrue("no confirmed session, no key: the engine never opens one", rig.handed("carol").isEmpty())
            assertEquals(setOf("alice", "bob", "carol"), keys.current.audience)
            // One size whether or not the next pair rides.
            assertEquals(
                PresenceKeyFrame.PLAINTEXT_BYTES,
                rig.sealed
                    .single()
                    .second
                    .encode()
                    .size,
            )

            // A pinned profile carrying the bit is as good as the HELLO's.
            rig.pinned["bob"] = Protocol.CAP_BLIND_ADVERT
            rig.engine.reconcile("test")
            assertEquals(1, rig.handed("bob").size)
        }

    /**
     * The sent record outlives a link: a relink with the same root and generations seals nothing — the peer may have
     * restored while apart, and a key sealed under the root it left would fail there and count toward its reset
     * heuristic. Adopting the peer's reset moves the root, and the keys are sealed again, once.
     */
    @Test
    fun aRelinkWithNothingChangedSendsNothingAndAnAdoptedResetResealsOnce() =
        runTest {
            val rig = Rig()
            rig.engine.start()
            rig.engine.reconcile("heal")
            assertEquals(1, rig.handed("alice").size)

            rig.links = listOf(peer("bob"))
            rig.engine.onLinksDown(listOf("alice"))
            rig.engine.reconcile("heal")
            rig.links = listOf(peer("alice"), peer("bob"))
            rig.engine.reconcile("link")
            assertEquals("a relink with nothing changed seals nothing", 1, rig.handed("alice").size)

            rig.roots["alice"] = 2L // we adopted alice's reset
            rig.engine.reconcile("root")
            rig.engine.reconcile("heal")
            assertEquals("the new root reseals, once", 2, rig.handed("alice").size)
            assertEquals("bob's root never moved", 1, rig.handed("bob").size)
        }

    @Test
    fun aMintWritesItsGenerationBeforeTheRowThatUsesIt() =
        runTest {
            val rig = Rig()
            rig.engine.start()
            val firstRow = rig.writes.indexOfFirst { it.startsWith("own") }
            val firstGeneration = rig.writes.indexOfFirst { it.startsWith("last") }
            assertTrue(rig.writes.toString(), firstGeneration in 0 until firstRow)
            assertEquals(rig.keys().next!!.generation, rig.store.last)
        }

    /**
     * The order of grow, remove and send across passes: a contact grown into the next key, then removed, then a send —
     * the removed peer is handed neither the key it was grown into nor the fresh pair, and the pair excludes it.
     */
    @Test
    fun growThenRemoveThenSendNeverHandsEitherKeyToTheRemovedPeer() =
        runTest {
            val rig = Rig()
            rig.direct = setOf("alice")
            rig.links = listOf(peer("alice"))
            rig.engine.start()
            rig.wall += 2 * PresenceKeyPolicy.DAY_MS

            // Grown into next (the current key aired two days), while carol is not linked.
            rig.direct = setOf("alice", "carol")
            rig.confirmed += "carol"
            rig.roots["carol"] = 1L
            rig.engine.reconcile("contacts")
            val grown = rig.keys().next!!
            assertTrue("carol" in grown.audience)
            assertFalse("carol" in rig.keys().current!!.audience)

            // Removed before any link to her came up; then she links.
            rig.direct = setOf("alice")
            rig.links = listOf(peer("alice"), peer("carol"))
            rig.engine.reconcile("link")

            assertTrue("carol is handed nothing", rig.handed("carol").isEmpty())
            val keys = rig.keys()
            assertNotEquals("the next key carol was grown into is gone", grown.generation, keys.next?.generation)
            assertFalse(rig.store.ownKeys().any { it.generation == grown.generation })
            assertFalse("carol" in keys.current!!.audience || "carol" in keys.next!!.audience)
            assertEquals("alice has the fresh pair", listOf(keys.current.generation, keys.next!!.generation), rig.handed("alice").last())
        }

    @Test
    fun aRemovalMintsAFreshPairAndKeepsTheOldCurrentOnlyAsThePrevious() =
        runTest {
            val rig = Rig()
            rig.engine.start()
            val before = rig.keys()
            rig.direct = setOf("alice")
            rig.engine.reconcile("contacts")
            val after = rig.keys()
            assertEquals(before.current!!.generation, after.previous?.generation)
            assertTrue(after.current!!.generation > before.next!!.generation)
            assertEquals(setOf("alice"), after.current.audience)
            assertEquals(setOf("alice"), after.next?.audience)
            assertEquals("bob is never handed the fresh pair", 1, rig.handed("bob").size)
            assertEquals(2, rig.handed("alice").size)
        }

    @Test
    fun aWeeklyAdvanceHandsTheNewNextAheadAndKeepsTheCurrentTheContactAlreadyHolds() =
        runTest {
            val rig = Rig()
            rig.engine.start()
            val first = rig.keys()
            rig.wall += PresenceKeyPolicy.WEEK_MS
            rig.engine.reconcile("heal")
            val second = rig.keys()
            assertEquals(
                "the next key the contact already holds is the one aired now",
                first.next!!.generation,
                second.current!!.generation,
            )
            assertEquals(listOf(second.current.generation, second.next!!.generation), rig.handed("alice").last())
        }

    @Test
    fun aClockOrMarkAdvanceRunsAtMostOnceADayOfElapsedTime() =
        runTest {
            val rig = Rig()
            rig.wall += 3 * PresenceKeyPolicy.DAY_MS // minted while the clock ran ahead
            rig.engine.start()
            val minted = rig.keys().current!!.generation
            rig.wall -= 3 * PresenceKeyPolicy.DAY_MS // corrected
            rig.engine.reconcile("heal")
            val once = rig.keys().current!!.generation
            assertNotEquals("the corrected clock advanced", minted, once)

            // Spoofed ahead and back again inside the day: the weekly rule moves it ahead, the clock rule is held.
            rig.wall += 8 * PresenceKeyPolicy.DAY_MS
            rig.engine.reconcile("heal")
            val ahead = rig.keys().current!!.generation
            assertNotEquals(once, ahead)
            rig.wall -= 8 * PresenceKeyPolicy.DAY_MS
            rig.engine.reconcile("heal")
            assertEquals("the clock rule waits a day of elapsed time", ahead, rig.keys().current!!.generation)

            rig.elapsed += PresenceKeyPolicy.DAY_MS
            rig.engine.reconcile("heal")
            assertNotEquals(ahead, rig.keys().current!!.generation)
            val settled = rig.keys().current!!.generation
            rig.engine.reconcile("heal")
            assertEquals("then it is quiet", settled, rig.keys().current!!.generation)
        }

    /** A weekly turnover never spends the daily floor on clock-or-mark advances: only those two advances stamp it. */
    @Test
    fun aWeeklyAdvanceDoesNotSpendTheDailyClockFloor() =
        runTest {
            val rig = Rig()
            rig.wall += 3 * PresenceKeyPolicy.DAY_MS
            rig.engine.start()
            rig.wall -= 3 * PresenceKeyPolicy.DAY_MS
            rig.engine.reconcile("heal") // the corrected clock's advance: the floor runs from elapsed 0
            rig.elapsed += PresenceKeyPolicy.DAY_MS // a day on: the floor is over
            rig.wall += 8 * PresenceKeyPolicy.DAY_MS
            rig.engine.reconcile("heal") // a weekly advance
            val weekly = rig.keys().current!!.generation
            rig.wall -= 8 * PresenceKeyPolicy.DAY_MS
            rig.engine.reconcile("heal")
            assertNotEquals("the clock advance runs at once: the weekly one stamped no floor", weekly, rig.keys().current!!.generation)
        }

    /**
     * Two passes meet at the engine's lock while the first is inside a mint (its last-generation read is held): the
     * second waits and finds the keys minted, so there is one current/next pair and one mint. With the lock gone both
     * passes mint (verified by replacing the mutex with a no-op: two mint writes).
     */
    @OptIn(ExperimentalCoroutinesApi::class) // UnconfinedTestDispatcher: each pass runs eagerly to the lock
    @Test
    fun twoConcurrentPassesMintOneGeneration() =
        runTest {
            val rig = Rig()
            val gate = CompletableDeferred<Unit>()
            rig.store.gate = gate
            val first = launch(UnconfinedTestDispatcher(testScheduler)) { rig.engine.start() }
            val second = launch(UnconfinedTestDispatcher(testScheduler)) { rig.engine.start() }
            gate.complete(Unit)
            first.join()
            second.join()
            assertEquals("one mint of a current and a next key", 1, rig.writes.count { it.startsWith("own +2") })
            assertEquals(2, rig.store.ownKeys().size)
            assertTrue(rig.keys().current != null && rig.keys().next != null)
        }

    @Test
    fun aRestoreClampsAGenerationFarAheadAndOwesItsUncoveredContactsAResetOverALink() =
        runTest {
            val rig = Rig()
            rig.store.last = rig.wall + 2 * PresenceKeyPolicy.CLAMP_AHEAD_MS
            rig.confirmed.clear()
            rig.engine.onRestore(setOf("alice"))
            assertEquals(rig.wall, rig.store.last)

            rig.links = emptyList()
            rig.engine.start()
            assertTrue("no link, nothing sealed", rig.resetsSealed.isEmpty())

            rig.links = listOf(peer("alice"), peer("bob"))
            rig.engine.reconcile("link")
            assertEquals("sealed once, for the uncovered contact alone", listOf("alice"), rig.resetsSealed)
            assertEquals(1, rig.sentFrames.count { it.first == "alice" && it.second.startsWith("reset") })
            rig.engine.reconcile("heal")
            assertEquals("once per link", 1, rig.sentFrames.count { it.first == "alice" && it.second.startsWith("reset") })

            // The next link-up re-sends the kept bytes verbatim: no second seal.
            rig.relink("alice")
            rig.engine.reconcile("link")
            assertEquals(listOf("alice"), rig.resetsSealed)
            assertEquals(2, rig.sentFrames.count { it.first == "alice" })

            // Answered: dropped, and the keys follow on the confirmed session, once.
            rig.confirmed += "alice"
            rig.relink("alice")
            rig.engine.reconcile("link")
            assertTrue(rig.store.owed.isEmpty())
            assertEquals(2, rig.sentFrames.count { it.first == "alice" && it.second.startsWith("reset") })
            assertEquals(1, rig.handed("alice").size)
            rig.relink("alice")
            rig.engine.reconcile("link")
            assertEquals("a later relink owes nothing", 1, rig.handed("alice").size)
        }

    /**
     * A fast relink: the new link's pass runs before the departure's own clear reached the engine. The link epoch the
     * neighbour pass bumps on the arrival says the link is new, so the link-owed reset still goes on it.
     */
    @Test
    fun aRelinkWhosePassRunsBeforeTheDeparturesClearStillSendsTheLinkOwedReset() =
        runTest {
            val rig = Rig()
            rig.confirmed.clear()
            rig.engine.onRestore(setOf("alice"))
            rig.engine.start()
            assertEquals(1, rig.sentFrames.count { it.first == "alice" && it.second.startsWith("reset") })
            rig.engine.reconcile("heal")
            assertEquals("once per link", 1, rig.sentFrames.count { it.first == "alice" && it.second.startsWith("reset") })

            // The departure and the arrival as the neighbour pass registers them, its async clear not yet run.
            rig.engine.onLinkDepart(listOf("alice"))
            rig.engine.onLinkUp(listOf("alice"))
            rig.engine.reconcile("link")
            assertEquals("the new link gets it", 2, rig.sentFrames.count { it.first == "alice" && it.second.startsWith("reset") })
        }

    /**
     * A link listed again by a retried or displaced neighbour pass, with no departure in between, is the same link: the
     * link-owed reset goes on it once. And a peer the neighbour pass has not registered yet is no pass's to deal with.
     */
    @Test
    fun aLinkListedTwiceWithNoDepartureGetsTheLinkOwedResetOnce() =
        runTest {
            val rig = Rig()
            rig.confirmed.clear()
            rig.engine.onRestore(setOf("alice"))
            rig.engine.onLinkDepart(listOf("alice")) // linked on the transport, not yet registered by the pass
            rig.engine.start()
            assertEquals("unregistered: nothing yet", 0, rig.sentFrames.count { it.first == "alice" })
            rig.engine.onLinkUp(listOf("alice"))
            rig.engine.reconcile("link")
            rig.engine.onLinkUp(listOf("alice")) // the same link, listed again
            rig.engine.reconcile("link")
            assertEquals("once on the one link", 1, rig.sentFrames.count { it.first == "alice" && it.second.startsWith("reset") })
        }

    @Test
    fun aDeclinedLinkResetIsDroppedAndAnUncoveredContactThatLeavesIsDroppedToo() =
        runTest {
            val rig = Rig()
            rig.confirmed.clear()
            rig.engine.onRestore(setOf("alice", "bob"))
            rig.links = listOf(peer("alice"))
            rig.declineResets = true
            rig.engine.start()
            assertFalse("alice" in rig.store.owed)
            rig.direct = setOf("alice")
            rig.engine.reconcile("contacts")
            assertFalse("bob" in rig.store.owed)
        }

    @Test
    fun theKeysOfPeersWhoLeaveTheDirectContactsAreDeleted() =
        runTest {
            val rig = Rig()
            rig.store.peerKeys += setOf("alice", "bob", "removed-while-stopped")
            rig.engine.start()
            assertEquals("the first pass deletes whoever is not a direct contact", setOf("alice", "bob"), rig.store.peerKeys)
            rig.store.peerKeys += "pending" // a sender we have not accepted yet: kept until it leaves a set it is not in
            rig.direct = setOf("alice")
            rig.engine.reconcile("contacts")
            assertEquals(setOf("alice", "pending"), rig.store.peerKeys)
        }

    /**
     * Crypto review item 1: a kept reset another reset of ours has since replaced is never resent — the peer could adopt
     * it after the replacement, which then lands inside its floor as a duplicate and strands the pair. The entry goes.
     */
    @Test
    fun aKeptResetAnotherResetReplacedIsDroppedNotResent() =
        runTest {
            val rig = Rig()
            rig.confirmed.clear()
            rig.engine.onRestore(setOf("alice"))
            rig.engine.start()
            assertEquals(listOf("alice"), rig.resetsSealed)
            rig.relink("alice")
            rig.reRootByAnotherReset("alice")
            rig.engine.reconcile("link")
            assertEquals("never resent", 1, rig.sentFrames.count { it.first == "alice" && it.second.startsWith("reset") })
            assertFalse("alice" in rig.store.owed)
        }

    /** A kept reset whose init the peer's prekey has moved past is sealed again through the ordinary plan, and kept anew. */
    @Test
    fun aKeptResetOnAPrekeyThePeerLeftIsSealedAgain() =
        runTest {
            val rig = Rig()
            rig.confirmed.clear()
            rig.engine.onRestore(setOf("alice"))
            rig.engine.start()
            val first = rig.store.owed.getValue("alice")
            rig.relink("alice")
            rig.prekeyMoved += "alice"
            rig.engine.reconcile("link")
            assertEquals(listOf("alice", "alice"), rig.resetsSealed)
            assertNotEquals(
                first?.rootTag,
                rig.store.owed
                    .getValue("alice")
                    ?.rootTag,
            )
            assertEquals(
                rig.roots["alice"],
                rig.store.owed
                    .getValue("alice")
                    ?.rootTag,
            )
        }

    /**
     * Crypto review item 2: a reset sealed and lost before its bytes were kept (a crash) is marked again under our own
     * pending init — never dropped as "another went", never a new root.
     */
    @Test
    fun aResetLostBeforeItWasKeptIsMarkedAgainUnderOurOwnInit() =
        runTest {
            val rig = Rig()
            rig.confirmed.clear()
            rig.engine.onRestore(setOf("alice"))
            rig.engine.start()
            val root = rig.roots["alice"]
            rig.loseKeptBytes("alice")
            rig.relink("alice")
            rig.engine.reconcile("link")
            assertEquals(listOf("alice"), rig.remarks)
            assertEquals("no new root", root, rig.roots["alice"])
            assertEquals(
                root,
                rig.store.owed
                    .getValue("alice")
                    ?.rootTag,
            )
            assertEquals(2, rig.sentFrames.count { it.first == "alice" && it.second.startsWith("reset") })
        }

    @Test
    fun onlyAProfileCarryingTheBitAsksForAReconcile() {
        val rig = Rig()
        assertTrue(rig.engine.asksForKeys(Protocol.CAP_BLIND_ADVERT))
        assertTrue(rig.engine.asksForKeys(Protocol.CAP_BLIND_ADVERT or Protocol.CAP_RATCHET))
        assertFalse(rig.engine.asksForKeys(0L))
        assertFalse(rig.engine.asksForKeys(null))
    }

    /** Security review item 5: a send that throws loses no other peer's frame, and its peer is resent on the next pass. */
    @Test
    fun aFailedSendLosesNoOtherFrameAndIsResentNextPass() =
        runTest {
            val rig = Rig()
            rig.failSendTo += "alice"
            rig.engine.start()
            assertEquals("bob still got his", 1, rig.sentFrames.count { it.first == "bob" })
            rig.failSendTo.clear()
            rig.engine.reconcile("heal")
            assertEquals(1, rig.sentFrames.count { it.first == "alice" })
            assertEquals(2, rig.handed("alice").size)
        }

    /** The engine's store in memory, logging each write so a test can read their order. */
    private class MemoryStore(
        private val writes: MutableList<String>,
    ) : PresenceKeyEngine.Store {
        private val rows = linkedMapOf<Long, PresenceKeyPolicy.Own>()
        var last = 0L
        val peerKeys = mutableSetOf<String>()
        val owed = linkedMapOf<String, PresenceKeyEngine.KeptReset?>()

        override suspend fun ownKeys() = rows.values.toList()

        override suspend fun applyOwn(
            inserted: List<PresenceKeyPolicy.Own>,
            updated: List<PresenceKeyPolicy.Own>,
            keep: Set<Long>,
        ) {
            updated.forEach { rows[it.generation] = it }
            inserted.forEach {
                check(it.generation !in rows) { "a mint never reuses a generation" }
                rows[it.generation] = it
            }
            rows.keys.retainAll(keep)
            writes += "own +${inserted.size} ~${updated.size}" + if (updated.any { it.activatedAt != null }) " activated" else ""
        }

        /** Awaited by the first [lastGeneration] read, then cleared: holds a pass inside its mint. */
        var gate: CompletableDeferred<Unit>? = null

        override suspend fun lastGeneration(): Long {
            gate?.let {
                gate = null
                it.await()
            }
            return last
        }

        override suspend fun setLastGeneration(generation: Long) {
            last = generation
            writes += "last $generation"
        }

        override suspend fun peersWithKeys() = peerKeys.toList()

        override suspend fun deletePeerKeys(nodeIds: Collection<String>) {
            peerKeys.removeAll(nodeIds.toSet())
        }

        override suspend fun linkOwedResets(): Map<String, PresenceKeyEngine.KeptReset?> = owed.toMap()

        override suspend fun setLinkOwedResets(peers: Set<String>) {
            owed.clear()
            peers.forEach { owed[it] = null }
        }

        override suspend fun recordLinkOwedReset(
            peer: String,
            kept: PresenceKeyEngine.KeptReset,
        ) {
            if (peer in owed) owed[peer] = kept
        }

        override suspend fun dropLinkOwedReset(peer: String) {
            owed.remove(peer)
        }
    }

    private companion object {
        fun peer(
            id: String,
            caps: Long = Protocol.CAP_BLIND_ADVERT,
        ) = Peer(id, capabilities = caps)
    }
}
