package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scope-table derivation: who gets a scope, from which root, and for how long (spec §3.1). */
class ScopeRegistryTest {
    private val me = "aaaaaaaaaaaaaaaaaaaaaaaaaa"
    private val bob = "bbbbbbbbbbbbbbbbbbbbbbbbbb"
    private val carol = "cccccccccccccccccccccccccc"

    private val groupId = "g-00112233445566778899aabb"

    private fun registry(
        roots: List<ScopeRoots> = emptyList(),
        groups: List<GroupScopeRoots> = emptyList(),
        pairs: List<PairScopeRoots> = emptyList(),
        commons: List<CommonsRoots> = emptyList(),
        restored: List<RestoredDmRoots> = emptyList(),
    ) = ScopeRegistry(
        selfId = { me },
        roots = { roots },
        groupRoots = { groups },
        pairs = { pairs },
        commons = { commons },
        restored = { restored },
    )

    private fun root(seed: Byte) = ByteArray(32) { seed }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    @Test
    fun `derives one scope per session peer, matching the spec's DM derivation`() =
        runTest {
            val scopes = registry(listOf(ScopeRoots(bob, root(1)))).scopes(now = 0L)
            assertEquals(1, scopes.size)
            assertArrayEquals(ScopeCrypto.dmScopeId(root(1), me, bob), scopes[0].id)
            assertArrayEquals(ScopeCrypto.dmSealKeys(root(1), me, bob).sealKey, scopes[0].keys.sealKey)
            assertEquals(bob, scopes[0].peerId)
            assertFalse(scopes[0].retiring)
        }

    @Test
    fun `both members derive the same scope id from their shared pairwise root`() =
        runTest {
            val mine = registry(listOf(ScopeRoots(bob, root(1)))).scopes(0L).single()
            val theirs =
                ScopeRegistry(selfId = { bob }, roots = { listOf(ScopeRoots(me, root(1))) })
                    .scopes(0L)
                    .single()
            assertEquals(mine.idHex, theirs.idHex)
        }

    /**
     * ADR 032. This gated on `Conversations.isAccepted` until 2026-08-22, which broke the plane for any
     * pair whose thread one side had only ever *received* in: acceptance is largely "I have authored
     * here", so the sender derived a scope and the receiver derived none. Neither end could tell — both
     * reported a connected spool, no error and an empty invalid set — and the DMs only ever landed when
     * the two came back into radio range. The scope table must depend on the session, never on a local
     * presentation decision (ADR 009).
     */
    @Test
    fun `a peer that is still a message request gets a scope anyway, symmetric with the sender's`() =
        runTest {
            // Bob has never authored in this thread; me has. Both must still derive the same scope.
            val receiver = registry(listOf(ScopeRoots(bob, root(1)))).scopes(0L)
            val sender = ScopeRegistry(selfId = { bob }, roots = { listOf(ScopeRoots(me, root(1))) }).scopes(0L)
            assertEquals(listOf(bob), receiver.map { it.peerId })
            assertEquals(receiver.single().idHex, sender.single().idHex)
        }

    @Test
    fun `every session peer gets a scope`() =
        runTest {
            val scopes = registry(listOf(ScopeRoots(bob, root(1)), ScopeRoots(carol, root(2)))).scopes(0L)
            assertEquals(listOf(bob, carol), scopes.map { it.peerId })
        }

    @Test
    fun `a replaced session keeps the retiring scope subscribed until its drain window closes`() =
        runTest {
            val entry = ScopeRoots(bob, root(3), prevPairwiseRoot = root(1), prevRootExpiresAt = 5_000L)
            val duringDrain = registry(listOf(entry)).scopes(now = 4_999L)
            assertEquals(2, duringDrain.size)
            assertArrayEquals(ScopeCrypto.dmScopeId(root(3), me, bob), duringDrain[0].id)
            assertArrayEquals(ScopeCrypto.dmScopeId(root(1), me, bob), duringDrain[1].id)
            assertTrue("the old scope is drained, never refilled", duringDrain[1].retiring)

            val afterDrain = registry(listOf(entry)).scopes(now = 5_000L)
            assertEquals(1, afterDrain.size)
            assertFalse(afterDrain[0].retiring)
        }

    /**
     * C-3.1-4 (ADR 2026-10.j67x, #182): our own reset, unanswered, over a root it replaced. The peer is still on
     * the replaced root, so its scope stays — retiring, carrying the reset — beside the new root's, where the
     * peer's answer lands. Before this both left the table and a relay-only pair had no scope at all.
     */
    @Test
    fun `our unanswered reset keeps the scope it replaced, carrying the reset, beside the new one`() =
        runTest {
            val eph = ByteArray(32) { 9 }
            val entry = ScopeRoots(bob, root(3), prevPairwiseRoot = root(1), prevRootExpiresAt = 5_000L, pendingResetInit = eph)
            val scopes = registry(listOf(entry)).scopes(now = 4_999L)
            assertEquals(2, scopes.size)
            val (fresh, replaced) = scopes
            assertArrayEquals(ScopeCrypto.dmScopeId(root(3), me, bob), fresh.id)
            assertFalse(fresh.retiring)
            assertArrayEquals("the new scope takes the pending reset too, and no other of ours", eph, fresh.resetInit)
            assertArrayEquals(ScopeCrypto.dmScopeId(root(1), me, bob), replaced.id)
            assertTrue(replaced.retiring)
            assertArrayEquals("the replaced scope takes the reset", eph, replaced.resetInit)

            // Past the drain the session is any other unconfirmed initiation: off the table until the answer.
            assertTrue(registry(listOf(entry)).scopes(now = 5_000L).isEmpty())
        }

    /**
     * C-3.1-6 (ADR 2026-11.dgj2, #324). A restored phone holds no session, so its reset replaces no root and, alone,
     * exports nothing. The anchor its backup carried is the scope the peer still reads: it stays as the retiring scope
     * beside the reset's new root, both taking that reset alone. Before this the relay-only pair had no scope at all.
     */
    @Test
    fun `a restore's anchor is the retiring scope beside its reset's new root, both carrying the reset`() =
        runTest {
            val eph = ByteArray(32) { 9 }
            val anchorId = ByteArray(32) { 7 }
            val anchorKeys = ScopeCrypto.SealKeys(ByteArray(32) { 5 }, ByteArray(32) { 6 })
            val scopes = registry(restored = listOf(RestoredDmRoots(bob, root(3), eph, anchorId, anchorKeys))).scopes(0L)
            assertEquals(2, scopes.size)
            val (fresh, anchor) = scopes
            assertArrayEquals(ScopeCrypto.dmScopeId(root(3), me, bob), fresh.id)
            assertFalse(fresh.retiring)
            assertArrayEquals(eph, fresh.resetInit)
            assertArrayEquals(anchorId, anchor.id)
            assertArrayEquals(anchorKeys.sealKey, anchor.keys.sealKey)
            assertArrayEquals(anchorKeys.nonceKey, anchor.keys.nonceKey)
            assertTrue(anchor.retiring)
            assertEquals(bob, anchor.peerId)
            assertFalse(anchor.pair)
            assertArrayEquals("the anchor takes the restore's reset and nothing else of ours", eph, anchor.resetInit)
        }

    /**
     * R1 of the design pass: a reset that supersedes the restore's re-roots over a root the peer never derived, so the
     * ratchet now exports it beside that dead root. The anchor must stay: it is still the one scope the peer reads,
     * and it takes the superseding reset.
     */
    @Test
    fun `a reset that supersedes the restore's keeps the anchor beside the table's own pair`() =
        runTest {
            val eph = ByteArray(32) { 8 }
            val anchorId = ByteArray(32) { 7 }
            val anchorKeys = ScopeCrypto.SealKeys(ByteArray(32) { 5 }, ByteArray(32) { 6 })
            val exported = ScopeRoots(bob, root(4), prevPairwiseRoot = root(3), prevRootExpiresAt = 5_000L, pendingResetInit = eph)
            val restored = RestoredDmRoots(bob, root(4), eph, anchorId, anchorKeys)
            val scopes = registry(roots = listOf(exported), restored = listOf(restored)).scopes(now = 0L)
            assertEquals(
                "the new root once, the dead restore root and the anchor",
                listOf(
                    ScopeCrypto.dmScopeId(root(4), me, bob).toHex(),
                    ScopeCrypto.dmScopeId(root(3), me, bob).toHex(),
                    anchorId.toHex(),
                ).sorted(),
                scopes.map { it.idHex }.sorted(),
            )
            val anchor = scopes.single { it.id.contentEquals(anchorId) }
            assertTrue(anchor.retiring)
            assertArrayEquals("the anchor takes the superseding reset", eph, anchor.resetInit)
        }

    /**
     * Round-1 finding S3: an anchor comes from a restore file, hostile input. One whose id equals a scope this phone
     * derives itself (here a group's) must not replace it as a retiring scope under the file's keys: the derived one is
     * kept, live, with its own keys.
     */
    @Test
    fun `a restored anchor colliding with a derived scope never replaces it`() =
        runTest {
            val group = GroupScopeRoots(groupId, setOf(me, bob), root(7), rootVersion = 1)
            val derived = registry(groups = listOf(group)).scopes(0L).single()
            val hostile = ScopeCrypto.SealKeys(ByteArray(32) { 5 }, ByteArray(32) { 6 })
            val restored = RestoredDmRoots(bob, root(3), ByteArray(32) { 9 }, derived.id, hostile)
            val scope = registry(groups = listOf(group), restored = listOf(restored)).scopes(0L).single { it.id.contentEquals(derived.id) }
            assertEquals(groupId, scope.groupId)
            assertFalse(scope.retiring)
            assertArrayEquals(derived.keys.sealKey, scope.keys.sealKey)
        }

    /**
     * Round-1 finding C2: once a re-send of a superseding reset ends the ratchet's own export, the restore's input still
     * carries the slot — the restore root, which the peer may have adopted — as a retiring scope taking that reset, until
     * the slot's drain ends.
     */
    @Test
    fun `a restore's slot stays a retiring scope carrying the pending reset until it drains`() =
        runTest {
            val eph = ByteArray(32) { 8 }
            val restored =
                RestoredDmRoots(
                    bob,
                    root(4),
                    eph,
                    ByteArray(32) { 7 },
                    ScopeCrypto.SealKeys(ByteArray(32) { 5 }, ByteArray(32) { 6 }),
                    prevPairwiseRoot = root(3),
                    prevRootExpiresAt = 5_000L,
                )
            val slotId = ScopeCrypto.dmScopeId(root(3), me, bob)
            val slot = registry(restored = listOf(restored)).scopes(0L).single { it.id.contentEquals(slotId) }
            assertTrue(slot.retiring)
            assertArrayEquals(eph, slot.resetInit)
            assertTrue(
                "gone once drained",
                registry(restored = listOf(restored)).scopes(5_000L).none { it.id.contentEquals(slotId) },
            )
        }

    @Test
    fun `a confirmed replacement's retiring scope carries no reset`() =
        runTest {
            val entry = ScopeRoots(bob, root(3), prevPairwiseRoot = root(1), prevRootExpiresAt = 5_000L)
            val scopes = registry(listOf(entry)).scopes(now = 0L)
            assertEquals(listOf(null, null), scopes.map { it.resetInit })
        }

    @Test
    fun `bounds default to the spec's constants so a stock spool clamps them to themselves`() =
        runTest {
            val bounds = registry(listOf(ScopeRoots(bob, root(1)))).scopes(0L).single().bounds
            assertEquals(400, bounds.maxFrames)
            assertEquals(48 * 60 * 60_000L, bounds.ttlMs)
            assertEquals(64 * 1024, bounds.maxBlob)
        }

    @Test
    fun `a group with a root gets a scope keyed by root and version, carrying its founding roster`() =
        runTest {
            val roster = setOf(me, bob, carol)
            val scope =
                registry(groups = listOf(GroupScopeRoots(groupId, roster, root(7), rootVersion = 1)))
                    .scopes(0L)
                    .single()
            assertArrayEquals(ScopeCrypto.groupScopeId(root(7), groupId, 1), scope.id)
            assertArrayEquals(ScopeCrypto.groupSealKeys(root(7), groupId, 1).sealKey, scope.keys.sealKey)
            assertEquals(groupId, scope.groupId)
            assertEquals(roster, scope.roster)
            // The DM discriminator stays null, which is what ScopeFrames dispatches the frame-set rule on.
            assertEquals(null, scope.peerId)
            assertEquals(groupId, scope.label)
        }

    @Test
    fun `a group with no root gets no scope at all`() =
        runTest {
            assertTrue(registry(groups = emptyList()).scopes(0L).isEmpty())
        }

    @Test
    fun `a departure re-mint rotates the id and drains the old lineage`() =
        runTest {
            val entry =
                GroupScopeRoots(
                    groupId = groupId,
                    roster = setOf(me, bob),
                    root = root(9),
                    rootVersion = 2,
                    prevRoot = root(7),
                    prevRootVersion = 1,
                    prevRootExpiresAt = 5_000L,
                )
            val duringDrain = registry(groups = listOf(entry)).scopes(now = 4_999L)
            assertEquals(2, duringDrain.size)
            assertArrayEquals(ScopeCrypto.groupScopeId(root(9), groupId, 2), duringDrain[0].id)
            assertArrayEquals(ScopeCrypto.groupScopeId(root(7), groupId, 1), duringDrain[1].id)
            // Unlinkable to a spool: the rotated scope is a fresh id, not a re-keying of the old one.
            assertTrue(duringDrain[0].idHex != duringDrain[1].idHex)
            assertTrue("the old scope is drained, never refilled", duringDrain[1].retiring)

            val afterDrain = registry(groups = listOf(entry)).scopes(now = 5_000L)
            assertEquals(1, afterDrain.size)
            assertFalse(afterDrain[0].retiring)
        }

    @Test
    fun `every member derives the same group scope id from the shared root`() =
        runTest {
            val roster = setOf(me, bob)
            val mine = registry(groups = listOf(GroupScopeRoots(groupId, roster, root(7), 1))).scopes(0L).single()
            val theirs =
                ScopeRegistry(
                    selfId = { bob },
                    roots = { emptyList() },
                    groupRoots = { listOf(GroupScopeRoots(groupId, roster, root(7), 1)) },
                ).scopes(0L).single()
            assertEquals(mine.idHex, theirs.idHex)
        }

    /** Spec §3.5: a pending intro yields a DM-form scope keyed by the identity pair secret, not a session. */
    @Test
    fun `a pending-intro peer gets a pair scope shaped exactly like a DM scope`() =
        runTest {
            val scope = registry(pairs = listOf(PairScopeRoots(bob, root(5)))).scopes(0L).single()
            assertArrayEquals(ScopeCrypto.pairScopeId(root(5), me, bob), scope.id)
            assertArrayEquals(ScopeCrypto.pairSealKeys(root(5), me, bob).sealKey, scope.keys.sealKey)
            assertEquals(bob, scope.peerId)
            assertEquals(null, scope.groupId)
            assertFalse("a pair scope is pushed into, never merely drained", scope.retiring)
        }

    @Test
    fun `both parties derive the same pair scope id from the shared pair secret`() =
        runTest {
            val mine = registry(pairs = listOf(PairScopeRoots(bob, root(5)))).scopes(0L).single()
            val theirs =
                ScopeRegistry(
                    selfId = { bob },
                    roots = { emptyList() },
                    pairs = { listOf(PairScopeRoots(me, root(5))) },
                ).scopes(0L).single()
            assertEquals(mine.idHex, theirs.idHex)
        }

    @Test
    fun `a peer's pair scope and DM scope coexist under distinct ids until the pair scope is dropped`() =
        runTest {
            val both = registry(roots = listOf(ScopeRoots(bob, root(1))), pairs = listOf(PairScopeRoots(bob, root(5)))).scopes(0L)
            assertEquals(listOf(bob, bob), both.map { it.peerId })
            assertTrue(both[0].idHex != both[1].idHex)
            // The driver stops naming the peer (confirmed + grace elapsed) → only the DM scope remains.
            val after = registry(roots = listOf(ScopeRoots(bob, root(1)))).scopes(0L)
            assertEquals(1, after.size)
            assertArrayEquals(ScopeCrypto.dmScopeId(root(1), me, bob), after[0].id)
        }

    @Test
    fun `DM and group scopes coexist in one table`() =
        runTest {
            val scopes =
                registry(
                    roots = listOf(ScopeRoots(bob, root(1))),
                    groups = listOf(GroupScopeRoots(groupId, setOf(me, bob), root(7), 1)),
                ).scopes(0L)
            assertEquals(listOf(bob, groupId), scopes.map { it.label })
        }

    @Test
    fun `a joined commons is one scope, keyed by its secret, bound to its relay, with the daemon's default bounds`() =
        runTest {
            val secret = root(9)
            val id = "c-" + hex(ScopeCrypto.commonsScopeId(secret))
            val scopes = registry(commons = listOf(CommonsRoots(id, "wss://home.test/spool/v1", secret))).scopes(0L)
            assertEquals(1, scopes.size)
            val scope = scopes.single()
            assertArrayEquals(ScopeCrypto.commonsScopeId(secret), scope.id)
            assertArrayEquals(ScopeCrypto.commonsSealKeys(secret).nonceKey, scope.keys.nonceKey)
            assertEquals(id, scope.commonsId)
            assertEquals(id, scope.label)
            assertEquals("wss://home.test/spool/v1", scope.spoolUrl)
            assertEquals(ScopeRegistry.COMMONS_DEFAULT_MAX_FRAMES, scope.bounds.maxFrames)
            assertEquals(ScopeRegistry.COMMONS_DEFAULT_TTL_MS, scope.bounds.ttlMs)
            assertFalse(scope.retiring)
            assertTrue(scope.peerId == null && scope.groupId == null)
        }

    @Test
    fun `every member of a commons derives the same scope from the same invite`() =
        runTest {
            val secret = root(9)
            val id = "c-" + hex(ScopeCrypto.commonsScopeId(secret))
            val mine = registry(commons = listOf(CommonsRoots(id, "wss://home.test/spool/v1", secret))).scopes(0L).single()
            val theirs =
                ScopeRegistry(selfId = {
                    bob
                }, roots = { emptyList() }, commons = { listOf(CommonsRoots(id, "wss://home.test/spool/v1", secret)) }).scopes(0L).single()
            assertEquals(mine.idHex, theirs.idHex)
            assertArrayEquals(mine.keys.sealKey, theirs.keys.sealKey)
        }
}
