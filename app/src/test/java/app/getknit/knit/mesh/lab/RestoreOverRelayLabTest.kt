package app.getknit.knit.mesh.lab

import app.getknit.knit.data.crypto.IdentityKeyStore
import app.getknit.knit.data.scopeanchor.DmScopeAnchorEntity
import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.crypto.ratchet.RatchetSessions
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RatchetHeader
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.spool.DmScopeAnchors
import app.getknit.knit.mesh.spool.FakeSpool
import app.getknit.knit.mesh.spool.hex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A restore toward a contact the relay is the only path to (#324, ADR 2026-11.dgj2, spec C-3.1-6). A restore holds no
 * session, so its resets replace no root and C-3.1-4 alone left them on the radios, while the contact, still confirmed on
 * the old session, kept reading the old DM scope. The backup carries that scope as the contact's **anchor**; armed by the
 * restore, it takes the one pending reset (C-3.1-5) until the contact answers, re-sent while it waits, for at most
 * [DmScopeAnchors.ARM_WINDOW_MS]. Every node here runs without a board, and the pair parts its radios after meeting, so
 * what is judged is what the relay alone carried. [RestoreLabTest] holds the radio cases.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreOverRelayLabTest {
    private lateinit var lab: MeshLab

    @get:Rule
    val chaos = LabChaos.rule()

    @Before
    fun setUp() {
        lab = MeshLab()
    }

    @After
    fun tearDown() {
        lab.close()
    }

    /** Alice and Bob, boards off, met on [spool] and then parted on the radios: the relay is their only path. */
    private suspend fun relayOnlyPair(spool: FakeSpool): Pair<LabNode, LabNode> {
        val alice = lab.node("alice", spool = spool, board = false).apply { setDisplayName("Alice") }
        val bob = lab.node("bob", spool = spool, board = false).apply { setDisplayName("Bob") }
        lab.meetOnTheRelay(alice, bob)
        return alice to bob
    }

    private suspend fun LabNode.anchorFor(peer: LabNode): DmScopeAnchorEntity? =
        db.dmScopeAnchorDao().all().firstOrNull {
            it.peerId ==
                peer.nodeId
        }

    /**
     * The frames [from] put into [anchor]'s scope, in push order, opened with the anchor's own keys. The spool cannot tell
     * pushers apart, and the peer still pushes into the scope it reads, so a count of [from]'s alone is the oracle.
     */
    private fun FakeSpool.framesFrom(
        from: LabNode,
        anchor: DmScopeAnchorEntity,
    ): List<RelayEnvelope> =
        pushedTo(hex(anchor.scopeId)).mapNotNull { id ->
            val blob = pushedData[id] ?: return@mapNotNull null
            val sealed =
                runCatching {
                    ScopeCrypto.open(
                        ScopeCrypto.SealKeys(anchor.sealKey, anchor.nonceKey),
                        anchor.scopeId,
                        blob,
                    )
                }.getOrNull()
            sealed?.let { WireCodec.decodeEnvelope(it.signed) }?.takeIf { it.senderId == from.nodeId }
        }

    /** Whether [env] is a flagged reset (a `chat` whose ratchet header sets `FLAG_RESET` and carries an init). */
    private fun isFlaggedReset(env: RelayEnvelope): Boolean {
        val r = WireCodec.decodePayload<ChatContent>(env.payload)?.enc?.r ?: return false
        return env.type == FrameType.CHAT && r.init != null && r.flags and RatchetHeader.FLAG_RESET != 0
    }

    private fun LabNode.listsScope(scopeHex: String): Boolean = manager.spoolStatus().flatMap { it.scopes }.any { it.scopeHex == scopeHex }

    /** Waits until [node]'s keeper holds [peer]'s anchor at the pair's live DM scope (the backup's), and returns its hex. */
    private suspend fun awaitAnchor(
        node: LabNode,
        peer: LabNode,
    ): String {
        val live = checkNotNull(node.dmScopeStatus(peer)) { "${node.name} holds no DM scope with ${peer.name}" }.scopeHex
        assertTrue(
            "${node.name}'s keeper never wrote ${peer.name}'s anchor at $live: ${node.anchorFor(peer)?.scopeId?.let { hex(it) }}",
            lab.tryAwait(1) { if (node.anchorFor(peer)?.scopeId?.let { hex(it) } == live) 1 else 0 },
        )
        return live
    }

    /**
     * The restored [alice]'s reset answered over the relay alone: she confirms, both sides list one new DM scope, the
     * [anchor] leaves her table and its row moves to the new scope, unarmed, and a fresh DM each way lands over the relay.
     * [atLeast] is what the thread holds once both fresh DMs are in.
     *
     * [withheld] says Alice holds a flagged reset of hers that is no longer the pending one (a superseded reset), which
     * C-3.1-5 keeps out of every DM scope: her side of the new scope then never converges, so only Bob's is awaited, and
     * the radios re-link after the relay delivered both DMs, for the oracle's custody leg alone (as
     * `ResetOverRelayLabTest`'s second-reset case does).
     */
    private suspend fun assertAnsweredOverTheRelay(
        alice: LabNode,
        bob: LabNode,
        anchor: String,
        atLeast: Int,
        withheld: Boolean = false,
    ) {
        assertTrue(
            "alice never confirmed over the relay (alice on ${alice.session(bob)?.rootHash}, bob on ${bob.session(alice)?.rootHash})",
            lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { if (alice.session(bob)?.confirmed == true) 1 else 0 },
        )
        if (!withheld) lab.awaitDmScope(alice, bob, notScope = anchor)
        lab.awaitDmScope(bob, alice, notScope = anchor)
        val scope = checkNotNull(bob.dmScopeStatus(alice)).scopeHex
        assertEquals("both sides on one scope", scope, alice.dmScopeStatus(bob)?.scopeHex)
        assertTrue(
            "the anchor stayed on alice's table after the answer",
            lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { if (alice.listsScope(anchor)) 0 else 1 },
        )
        // One local keeper pass, not a spool round: the default wait.
        val moved = lab.tryAwait(1) { alice.anchorFor(bob)?.let { if (hex(it.scopeId) == scope && it.armedUntil == 0L) 1 else 0 } ?: 0 }
        // Read after the wait, so the message says what the keeper ended on and which of its deletes could have fired:
        // `keep` (our pending reset), `!in targets`, or `deleteUnarmed` (the arming against now).
        val row = alice.anchorFor(bob)
        assertTrue(
            "the keeper never moved the anchor to $scope: row ${row?.let { hex(it.scopeId) }} armedUntil=${row?.armedUntil} " +
                "now=${alice.now()}, own pending reset=${alice.holdsOwnPendingResetTo(bob)}, target=${alice.keepsAnchorFor(bob)}, " +
                "session ${alice.session(bob)?.let { "confirmed=${it.confirmed} initiator=${it.weAreInitiator} root=${it.rootHash}" }}",
            moved,
        )
        assertTrue(alice.sendDm(bob, "back on the relay"))
        assertTrue(bob.sendDm(alice, "welcome back"))
        lab.await(atLeast, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
            minOf(alice.decrypted(alice.dmWith(bob)).size, bob.decrypted(bob.dmWith(alice)).size)
        }
        if (withheld) lab.link(alice, bob)
        lab.assertConverged(listOf(alice, bob), atLeast = atLeast, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
            it.dmWith(if (it === alice) bob else alice)
        }
    }

    /**
     * #324: Alice restores while the relay is the only path to Bob. Her restore reset replaces no root, so before the
     * anchor it went out on the radios alone, and Bob, still confirmed on the old session, kept reading the old scope and
     * never heard it. Her backup carried that scope as Bob's anchor: armed, it takes exactly the reset (C-3.1-5), Bob pulls
     * and adopts it and answers into the new scope. Pass: a fresh DM each way lands over the relay, the anchor scope
     * leaves the table, and the spool took exactly one blob into the anchor from the restore onwards, none after the
     * answer. (A DM Bob sent while she was away is the next case's, parked.)
     */
    @Test
    fun aRestoredNodeReachesAContactItMeetsOnlyOnTheRelay() =
        runBlocking {
            val spool = FakeSpool()
            val (alice, bob) = relayOnlyPair(spool)
            val anchor = awaitAnchor(alice, bob)
            val row = checkNotNull(alice.anchorFor(bob))

            lab.leaveRelay(alice)
            alice.restoreFromBackup()
            lab.await(1) { if (alice.settings.restorePending.first()) 0 else 1 }
            assertTrue("armed by the restore", checkNotNull(alice.anchorFor(bob)).armedUntil > alice.now())
            val beforeReset = spool.framesFrom(alice, row).size
            lab.rejoinRelay(alice)

            assertAnsweredOverTheRelay(alice, bob, anchor, atLeast = 4)
            val ours = spool.framesFrom(alice, row).drop(beforeReset)
            assertTrue("only a flagged reset of alice's went into the anchor: ${ours.map { it.id }}", ours.all(::isFlaggedReset))
            assertEquals("exactly the reset went into the anchor, nothing after the answer", 1, ours.size)
        }

    /**
     * #324's other half: a DM Bob sent while Alice was away, sealed under the session her restore wiped. Bob adopts her
     * reset over the anchor and re-seals it under the new root, same id, but the re-seal only floods (`resealRecentDmsTo`):
     * every custody keeps the first bytes it stored under an id (`ForwardSync.onSeen`, ADR 2026-10.dmgw), Bob's own
     * included, so `ScopeSync` pushes the original again and never the re-seal, and a pair the relay alone connects
     * never meets it. Parked until a re-seal reaches a scope.
     */
    @Ignore("#324: a reset's re-seal floods only; custody keeps the original bytes, so a relay-only peer never gets it")
    @Test
    fun aDmSentWhileARestoredRelayOnlyContactWasAwayReachesItOnTheReseal() =
        runBlocking {
            val spool = FakeSpool()
            val (alice, bob) = relayOnlyPair(spool)
            val anchor = awaitAnchor(alice, bob)
            lab.leaveRelay(alice)
            assertTrue(bob.sendDm(alice, "while you were away"))
            alice.restoreFromBackup()
            lab.await(1) { if (alice.settings.restorePending.first()) 0 else 1 }
            lab.rejoinRelay(alice)
            assertAnsweredOverTheRelay(alice, bob, anchor, atLeast = 5)
        }

    /**
     * Design-pass R2 under round-1 finding S1: the backup is older than Bob's prekey retention (current plus four), and
     * Bob keeps signing profiles that each pin a prekey five ids on — what a peer minting ids to make us flood resets
     * would send. Alice's restore reset, sealed against the prekey the backup pinned, is one Bob can only refuse. His
     * profiles reach her through the anchor's drain, and each would owe a re-root: none goes inside the hour since her
     * last reset (each counted `RESTORE_RESET_REFUSED`), then exactly one, against the newest pin, which the anchor
     * carries. Pass: the pair converges over the relay alone on that one root.
     */
    @Test
    fun aRestoredNodeReachesAContactWhosePrekeyRotatedPastItsBackup() =
        runBlocking {
            val spool = FakeSpool()
            val (alice, bob) = relayOnlyPair(spool)
            val anchor = awaitAnchor(alice, bob)
            lab.leaveRelay(alice)
            val pinned = checkNotNull(alice.session(bob)?.peerPrekeyId)
            alice.restoreFromBackup()
            // The restore's own reset first: `finishRestore` runs on the boot's seeding coroutine, after this returns.
            lab.await(1) { if (alice.settings.restorePending.first()) 0 else 1 }
            assertEquals("the backup's pin", pinned, alice.session(bob)?.peerPrekeyId)
            val restoreEra = checkNotNull(alice.session(bob)).establishedAt
            repeat(PREKEY_PROFILES) { profile ->
                repeat(IdentityKeyStore.SPK_KEEP) { bob.identity.rotatePrekey(bob.now()) }
                bob.setStatus("rotated ${profile + 1}")
            }
            val newest = pinned + PREKEY_PROFILES * IdentityKeyStore.SPK_KEEP

            lab.rejoinRelay(alice)
            assertTrue(
                "bob's newest prekey never reached alice through the anchor: still ${alice.session(bob)?.peerPrekeyId}",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { if (alice.session(bob)?.peerPrekeyId == newest) 1 else 0 },
            )
            assertEquals("no re-root inside the hour", restoreEra, alice.session(bob)?.establishedAt)
            assertTrue(
                "the held re-roots were counted: ${alice.session(bob)?.let {
                    "pk=${it.peerPrekeyId} confirmed=${it.confirmed} last=${it.lastResetSentAt} era=${it.establishedAt}"
                }} now=${alice.now()} armed=${alice.anchorFor(bob)?.armedUntil} drops=${alice.metrics.frames().dropsByReason}",
                lab.tryAwait(1) { if ((alice.metrics.frames().dropsByReason[DropReason.RESTORE_RESET_REFUSED] ?: 0L) > 0L) 1 else 0 },
            )

            lab.clock.advance(RatchetSessions.RESTORE_REROOT_FLOOR_MS)
            assertTrue(
                "the heal basket never re-rooted past the floor",
                lab.tryAwait(1) {
                    alice.heal()
                    if (alice.session(bob)?.establishedAt != restoreEra) 1 else 0
                },
            )
            val reRooted = alice.session(bob)?.establishedAt
            alice.heal()
            assertEquals("one re-root, not one per profile", reRooted, alice.session(bob)?.establishedAt)
            // The restore's own reset, on the prekey Bob left, is no longer the pending one: it stays out of every scope.
            assertAnsweredOverTheRelay(alice, bob, anchor, atLeast = 4, withheld = true)
        }

    /**
     * Round-1 finding C1: Bob rotated twice since the backup, which he still keeps (current plus four). Alice's restore
     * reset, against the pinned prekey, is one he opens, so his newer profile owes nothing: a second root then would land
     * inside the floor his adoption of the first starts. Alice is on the relay as she restores, so her reset is in the
     * anchor before his profile drains. Pass: the pair converges over the relay on the restore's own root, and Bob never
     * floored a reset of hers.
     */
    @Test
    fun aRestoredNodeReachesAContactWhosePrekeyRotatedWithinRetention() =
        runBlocking {
            val spool = FakeSpool()
            val (alice, bob) = relayOnlyPair(spool)
            val anchor = awaitAnchor(alice, bob)
            lab.leaveRelay(alice)
            repeat(ROTATIONS_KEPT) {
                bob.identity.rotatePrekey(bob.now())
                bob.setStatus("rotated ${it + 1}")
            }
            alice.relayLeft = false // back on the relay from the restore's own boot
            alice.restoreFromBackup()
            lab.await(1) { if (alice.settings.restorePending.first()) 0 else 1 }
            val restoreEra = checkNotNull(alice.session(bob)).establishedAt

            assertAnsweredOverTheRelay(alice, bob, anchor, atLeast = 4)
            assertEquals("on the restore's own root", restoreEra, alice.session(bob)?.establishedAt)
            assertEquals(
                "bob floored no reset of alice's",
                0L,
                bob.metrics.frames().dropsByReason[DropReason.RATCHET_RESET_FLOORED] ?: 0L,
            )
        }

    /**
     * Design-pass R1: a second reset over the restore's, inside the window, re-roots and puts the restore's root (which
     * Bob never derived) in its slot. The anchor follows the pending reset whatever the slot holds, so it carries the
     * second, and only it: the first is no longer pending (C-3.1-5). Alice is off the relay for both.
     */
    @Test
    fun aSecondResetAfterARestoreIsCarriedByTheAnchor() =
        runBlocking {
            val spool = FakeSpool()
            val (alice, bob) = relayOnlyPair(spool)
            val anchor = awaitAnchor(alice, bob)
            lab.leaveRelay(alice)
            alice.restoreFromBackup()
            lab.await(1) { if (alice.settings.restorePending.first()) 0 else 1 }
            val restoreRoot = checkNotNull(alice.session(bob)).rootHash
            lab.clock.advance(RatchetSessions.RESET_REPLACEMENT_MIN_INTERVAL_MS)
            assertEquals("the second reset went out", null, alice.resetSession(bob))
            val newRoot = checkNotNull(alice.session(bob)).rootHash
            assertNotEquals("the second re-rooted", restoreRoot, newRoot)
            val row = checkNotNull(alice.anchorFor(bob))
            val before = spool.framesFrom(alice, row).size
            lab.rejoinRelay(alice)

            assertTrue(
                "bob never adopted the second reset: bob on ${bob.session(alice)?.rootHash}, alice on $newRoot",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { if (bob.session(alice)?.rootHash == newRoot) 1 else 0 },
            )
            assertAnsweredOverTheRelay(alice, bob, anchor, atLeast = 4, withheld = true)
            val ours = spool.framesFrom(alice, row).drop(before)
            assertEquals("the second reset alone went into the anchor: ${ours.map { it.id }}", 1, ours.size)
            assertTrue(isFlaggedReset(ours.single()))
        }

    /**
     * Design-pass R3: a removed contact's anchor goes at once and does not come back on a restart (the keeper writes only
     * for the peers a restore resets, and the removal deleted the thread that made Bob one). A later restore pushes nothing
     * into Bob's old scope. Its subject is the anchor's absence, which the oracle cannot see: Alice's side of the thread is
     * gone by the removal, so the pair has no common thread left to converge.
     *
     * lab-lint: allow missing-oracle — the removal deletes Alice's thread, so there is no shared conversation to converge
     */
    @Test
    fun aRemovedContactKeepsNoAnchorAndARestorePushesNothingIntoItsScope() =
        runBlocking {
            val spool = FakeSpool()
            val (alice, bob) = relayOnlyPair(spool)
            val anchor = awaitAnchor(alice, bob)
            alice.removeContact(bob)
            assertNull("dropped with the contact", alice.anchorFor(bob))
            alice.restart()
            alice.heal() // its basket runs a full reconcile: the restart's pass is now observed, not assumed
            assertNull("no row after a restart", alice.anchorFor(bob))
            lab.leaveRelay(bob)
            val before = spool.pushedTo(anchor).size

            alice.restoreFromBackup()
            lab.await(1) { if (alice.settings.restorePending.first()) 0 else 1 }
            assertNull("no row after a restart and a restore", alice.anchorFor(bob))
            assertFalse(
                "a push went into the removed contact's scope",
                lab.tryAwait(1, timeoutMs = NOTHING_MORE_MS) { spool.pushedTo(anchor).size - before },
            )
            assertFalse("the old scope is not on alice's table", alice.listsScope(anchor))
        }

    /**
     * Design-pass R5: a restore file is hostile input. A row of the wrong lengths and an arming far past any window do no
     * harm: the restore disarms every row and arms Bob's for the window alone, the closing reconcile drops the malformed
     * row, and the pair converges as in the plain case.
     */
    @Test
    fun aRestoreFileWithMalformedOrOverArmedAnchorsDoesNoHarm() =
        runBlocking {
            val spool = FakeSpool()
            val (alice, bob) = relayOnlyPair(spool)
            val anchor = awaitAnchor(alice, bob)
            lab.leaveRelay(alice)
            val anchors = alice.db.dmScopeAnchorDao()
            alice.restoreFromBackup {
                anchors.put(
                    DmScopeAnchorEntity("0".repeat(MALFORMED_PEER_LENGTH), ByteArray(3), ByteArray(5), ByteArray(32), Long.MAX_VALUE, 1L),
                )
                anchors.arm(bob.nodeId, Long.MAX_VALUE)
            }
            lab.await(1) { if (alice.settings.restorePending.first()) 0 else 1 }
            val armed = checkNotNull(alice.anchorFor(bob)).armedUntil
            assertTrue("armed for the window at most: $armed", armed in (alice.now() + 1)..(alice.now() + DmScopeAnchors.ARM_WINDOW_MS))
            assertTrue(
                "the malformed row outlived the restore's reconcile",
                lab.tryAwait(1) { if (anchors.all().any { it.peerId != bob.nodeId }) 0 else 1 },
            )
            lab.rejoinRelay(alice)
            assertAnsweredOverTheRelay(alice, bob, anchor, atLeast = 4)
        }

    /**
     * A stale anchor: the pair reset after the backup, so the file carries a scope of an era they left. The restore pushes
     * at most its reset there (C-3.1-5 holds for any anchor) and nothing breaks. Bob still drains that scope for the
     * replaced root's 48 h, so here it can even carry the reset; past that it reaches nobody and the radios, as before the
     * anchor, bring the pair back.
     */
    @Test
    fun aStaleAnchorPushesNothingButTheResetAndThePairStillConverges() =
        runBlocking {
            val spool = FakeSpool()
            val (alice, bob) = relayOnlyPair(spool)
            val staleScope = awaitAnchor(alice, bob)
            val stale = checkNotNull(alice.anchorFor(bob))
            assertEquals("bob's reset went out", null, bob.resetSession(alice))
            assertTrue(
                "alice never adopted bob's reset over the relay",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    if (alice.session(bob)?.rootHash == bob.session(alice)?.rootHash && bob.session(alice)?.confirmed == true) 1 else 0
                },
            )
            lab.awaitDmScope(alice, bob, notScope = staleScope)
            awaitAnchor(alice, bob)
            // The backup predates Bob's reset by hours, as a real one does. With no gap, Bob's flagged reset, re-served to
            // the restored Alice inside the same second, wins her race, and rule C's freshness half (a frame sealed more than
            // the skew bound past the winning init, ADR 2026-10.4mjm) cannot tell his answer to her reset from it.
            lab.clock.advance(STALE_ERA_GAP_MS)
            lab.leaveRelay(alice)
            val before = spool.pushedTo(staleScope).size

            alice.restoreFromBackup { db.dmScopeAnchorDao().put(stale) } // the older backup's row
            lab.await(1) { if (alice.settings.restorePending.first()) 0 else 1 }
            lab.rejoinRelay(alice)
            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 2, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                it.dmWith(if (it === alice) bob else alice)
            }
            assertTrue("only the reset went into the stale scope", spool.pushedTo(staleScope).size - before <= 1)
        }

    /**
     * ADR 2026-11.dgj2's re-send: Bob is off the relay for three days after the restore, past our custody's day and the
     * spool's two (modelled by expiring every blob the spool holds for the pair). Without a re-send nothing would put the
     * reset back where he reads. The heal basket re-sends it under the same init once its last copy is a day old, and Bob,
     * back on the relay, adopts it. The jump is past the 48 h lint bound on purpose: that is the outage under test, and
     * Alice holds no receive epoch of Bob's for the sweep to reap (her restore wiped them, and Bob has not answered).
     */
    @Test
    fun aRestoreResetReachesAContactOffTheRelayForThreeDays() =
        runBlocking {
            val spool = FakeSpool()
            val (alice, bob) = relayOnlyPair(spool)
            val anchor = awaitAnchor(alice, bob)
            val row = checkNotNull(alice.anchorFor(bob))
            // Alice's flagged resets in the anchor, counted from before the restore: meeting on the relay already pushed
            // into that scope, so its size alone says nothing about the reset.
            val resetsBefore = spool.framesFrom(alice, row).count(::isFlaggedReset)
            lab.leaveRelay(bob)
            alice.restoreFromBackup()
            lab.await(1) { if (alice.settings.restorePending.first()) 0 else 1 }
            val firstSent = checkNotNull(alice.session(bob)).lastResetSentAt
            assertTrue(
                "the restore reset never reached the anchor",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    spool.framesFrom(alice, row).count(::isFlaggedReset) - resetsBefore
                },
            )
            val pendingInit = checkNotNull(alice.session(bob)).establishedAt
            // Everything Alice pushes for Bob is at the spool before the calendar moves (her live scope converged; the
            // anchor never does on her side, as she refills it with nothing but the reset), so the TTL below takes all of it: a
            // frame pushed only after the jump would carry a stamp Bob's carry gate refuses as three days old, and his side
            // of that scope would never converge here, while a real spool holds it for its own two days.
            assertTrue(
                "alice's scopes for bob never settled at the spool",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    val mine =
                        alice.manager
                            .spoolStatus()
                            .flatMap { it.scopes }
                            .filter { it.label == bob.nodeId && !it.pair && !it.retiring }
                    if (mine.isNotEmpty() && mine.all { it.converged }) 1 else 0
                },
            )

            // lab-lint: allow clock-jump-over-48h — the three-day outage is the subject; Alice holds no receive epoch to reap
            lab.clock.advance(THREE_DAYS_MS)
            // The spool's own 48 h TTL, which this one does not keep: everything pushed before the jump is gone.
            spool.pushedInto.toList().forEach { (scope, blob) -> spool.expire(scope, blob) }
            val before = spool.framesFrom(alice, row).count(::isFlaggedReset)
            // A basket already under way when the clock jumped judged the old calendar and counts as this heal: heal again.
            assertTrue(
                "never re-sent from the heal basket",
                lab.tryAwait(1) {
                    alice.heal()
                    if (checkNotNull(alice.session(bob)).lastResetSentAt > firstSent) 1 else 0
                },
            )
            val resent = checkNotNull(alice.session(bob))
            assertEquals("under the same init, no new root", pendingInit, resent.establishedAt)
            lab.await(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { spool.framesFrom(alice, row).count(::isFlaggedReset) - before }

            lab.rejoinRelay(bob)
            assertAnsweredOverTheRelay(alice, bob, anchor, atLeast = 4)
        }

    /**
     * The negative control: thirty days with no answer and the anchor's window is over. The scope leaves Alice's table and
     * the heal basket re-sends nothing. Its subject is what the restored side stops doing, with Bob still away, so there is
     * no pair to converge.
     *
     * lab-lint: allow missing-oracle — the window's end is the subject; Bob never comes back, so no pair converges
     */
    @Test
    fun anAnchorUnansweredForItsWholeWindowLeavesTheTableAndIsNoLongerReSent() =
        runBlocking {
            val spool = FakeSpool()
            val (alice, bob) = relayOnlyPair(spool)
            val anchor = awaitAnchor(alice, bob)
            lab.leaveRelay(bob)
            alice.restoreFromBackup()
            lab.await(1) { if (alice.settings.restorePending.first()) 0 else 1 }
            assertTrue(
                "the anchor never joined alice's table",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { if (alice.listsScope(anchor)) 1 else 0 },
            )

            // lab-lint: allow clock-jump-over-48h — the window's end is the subject; Bob never answers
            lab.clock.advance(DmScopeAnchors.ARM_WINDOW_MS + 1)
            val sentBefore = checkNotNull(alice.session(bob)).lastResetSentAt
            alice.heal()
            alice.heal() // the second is sure to run on the new calendar: a basket under way at the jump answers the first
            assertEquals("no re-send past the window", sentBefore, alice.session(bob)?.lastResetSentAt)
            assertTrue(
                "the anchor stayed on alice's table past its window",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    // The window's end is the calendar's, which only the 60 s poll would notice: ask for a derivation now.
                    alice.manager.refreshRelays()
                    if (alice.listsScope(anchor)) 0 else 1
                },
            )
            assertNotNull("still unanswered", alice.session(bob)?.takeIf { !it.confirmed })
        }

    private companion object {
        /** How many prekey-moving profiles Bob signs, each five ids on (round-1 finding S1). */
        const val PREKEY_PROFILES = 50

        /** Rotations Bob still keeps the prekey from (fewer than `IdentityKeyStore.SPK_KEEP`). */
        const val ROTATIONS_KEPT = 2
        const val THREE_DAYS_MS = 3L * 24 * 60 * 60_000L
        const val NOTHING_MORE_MS = 5_000L
        const val MALFORMED_PEER_LENGTH = 16

        /** How long before the restore the pair's last reset was: well past `Protocol.MAX_FUTURE_SKEW_MS`. */
        const val STALE_ERA_GAP_MS = 60 * 60_000L
    }
}
