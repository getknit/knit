package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.MeshManager
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshcoreBoardClaim
import app.getknit.knit.mesh.crypto.MeshcoreBind
import app.getknit.knit.mesh.crypto.MeshcoreBindFixture
import app.getknit.knit.mesh.crypto.b64
import app.getknit.knit.mesh.crypto.b64d
import app.getknit.knit.mesh.lora.meshcore.MeshcoreProto
import app.getknit.knit.mesh.lora.meshcore.MeshcoreUnbind
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.ProfileContent
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The MeshCore board a profile names, end to end (ADR 2026-11.gsqu, Stage 4): the key rides the sealed profile only, so
 * it reaches a contact's `peers` row through `CTL_PROFILE` and stays there across a sealed rename; unbinding the board
 * clears it there; a carrier that shares no session holds the cleartext frame and never the key; the cleartext profile's
 * version never moves for it; and no log line any node writes carries it. Since Stage 5d the key goes with the board's bind
 * of its holder's node id (`meshcoreSig`): a contact stores only a key whose bind names the frame's sender, so a forger who
 * seals another's key and signature is refused, and the bind itself is in no log line either.
 */
@RunWith(RobolectricTestRunner::class)
@Suppress("LargeClass") // the sealed claim's whole end-to-end set, Stage 4 to 5d, over one set of helpers
class MeshcoreProfileLabTest {
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

    private suspend fun awaitKey(
        viewer: LabNode,
        holder: LabNode,
        key: String?,
    ) = assertTrue(
        "${viewer.name} never held ${if (key == null) "no key" else "the key"} for ${holder.name}",
        lab.tryAwait(1) { if (viewer.peer(holder)?.meshcoreKey == key) 1 else 0 },
    )

    @Test
    fun theSealedProfileCarriesTheBoardToAContactAndNeverToACarrier() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            assertTrue(alice.sendDm(bob, "hi bob"))
            assertTrue(bob.sendDm(alice, "hi alice"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }

            val cleartextVersion = alice.settings.profileVersion.first()
            alice.driveMeshcoreBoard(SEED)
            awaitKey(bob, alice, KEY)
            assertEquals("the cleartext profile version never moved", cleartextVersion, alice.settings.profileVersion.first())
            // Bob wrote Alice a DM, so she is his contact, and the board map Stage 5 teaches from names her board.
            assertEquals(
                KEY,
                bob.manager
                    .meshcoreBoardMap()
                    .first()[alice.nodeId]
                    ?.key,
            )

            // A rename republishes both layouts: the cleartext one has no such field, the sealed one carries it again. The
            // cleartext frame is kept from Bob, on both routes, until the sealed copy has set the name, so only the sealed
            // writer can have: it is the one that must keep the key.
            val cleartext = { frame: WireEnvelope -> frame.isProfileFrom(alice.nodeId) }
            alice.transport.lossy(bob.transport, cleartext)
            carol.transport.lossy(bob.transport, cleartext)
            alice.setDisplayName("Alice Again")
            assertTrue(lab.tryAwait(1) { if (bob.peer(alice)?.name == "Alice Again") 1 else 0 })
            assertEquals("a sealed rename keeps the key", KEY, bob.peer(alice)?.meshcoreKey)
            alice.transport.lossy(bob.transport)
            carol.transport.lossy(bob.transport)
            // The dropped frames are custody rows Bob lacks, offered again only on a link's digest exchange: relink, so the
            // stores settle before the oracle compares them.
            lab.unlink(alice, bob)
            lab.unlink(carol, bob)
            lab.link(alice, bob)
            lab.link(carol, bob)
            lab.awaitCustodyParity(alice, bob, carol)

            alice.driveMeshcoreBoard(null)
            awaitKey(bob, alice, null)
            assertNull(bob.manager.meshcoreBoardMap().first()[alice.nodeId])

            assertTrue(alice.sendDm(bob, "still here"))
            lab.assertConverged(listOf(alice, bob), atLeast = 3) { it.dmWith(if (it === alice) bob else alice) }

            // Carol shares no session with Alice: her profile reached Carol in the clear only, and carried no key.
            assertNull(carol.peer(alice)?.meshcoreKey)
            for (node in listOf(carol, bob)) {
                val held = node.custodiedProfilePayloadsFrom(alice)
                assertTrue("${node.name} carries Alice's profile", held.isNotEmpty())
                for (payload in held) {
                    assertTrue(WireCodec.decodePayload<ProfileContent>(payload) != null)
                    assertFalse(
                        "${node.name}'s custody copy names a key",
                        payload.decodeToString(throwOnInvalidSequence = false).contains("meshcore"),
                    )
                    assertFalse("${node.name}'s custody copy holds the key's bytes", hex(payload).contains(KEY_HEX))
                }
            }

            // Never in a log line, in any spelling, on any node: the key, nor the bind's signature.
            assertNoLogCarries(KEY, MeshcoreBindFixture.sig(alice.nodeId, SEED))
        }

    /**
     * Whether [viewer] holds [holder]'s presentation at the newest version [holder] sealed to it ([copies]): what an
     * unaffected session holds after an audience-only mint, which marks it sent without sealing it the newer version.
     */
    private suspend fun holdsNewestSealedTo(
        viewer: LabNode,
        holder: LabNode,
        copies: List<MeshManager.SealedProfileSend>,
    ): Boolean {
        val newest = copies.filter { it.peerId == viewer.nodeId }.maxOfOrNull { it.version } ?: return false
        val own = holder.ownPresentation()
        return viewer.presentationOf(holder) == own.copy(version = newest)
    }

    /** Whether [viewer] holds [holder]'s presentation at the version [holder] last published: its newest sealed profile. */
    private suspend fun holdsLatest(
        viewer: LabNode,
        holder: LabNode,
    ): Boolean = viewer.presentationOf(holder) == holder.ownPresentation()

    /**
     * The key goes to direct contacts only, and follows the set (the crypto and security reviews of 2026-11-13): a stranger
     * who DMed Alice once holds her newest sealed profile and no key; a contact she blocks is sent a newer keyless version,
     * which clears it, and gets nothing at the next rebind either; and a stranger she then accepts gets a strictly newer
     * version carrying the key, so the keyless copy he held at an equal version cannot stand.
     */
    @Test
    fun theKeyGoesToDirectContactsOnlyAndFollowsTheContactSet() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val dave = lab.node("dave").apply { setDisplayName("Dave") }
            val eve = lab.node("eve").apply { setDisplayName("Eve") }
            // Every sealed profile copy Alice sends, with the length it sealed to.
            val copies = CopyOnWriteArrayList<MeshManager.SealedProfileSend>()
            alice.manager.onSealedProfile = { copies += it }
            lab.linkAll(alice to bob, alice to dave, alice to eve, bob to dave, dave to eve)
            lab.awaitAcquainted(alice, bob, dave, eve)
            // Bob is Alice's contact (she wrote to him); Dave and Eve each DM her once and are never answered.
            assertTrue(alice.sendDm(bob, "hi bob"))
            assertTrue(bob.sendDm(alice, "hi alice"))
            assertTrue(dave.sendDm(alice, "hello?"))
            assertTrue(eve.sendDm(alice, "hey"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }

            alice.driveMeshcoreBoard(SEED)
            awaitKey(bob, alice, KEY)
            alice.heal()
            for (stranger in listOf(dave, eve)) {
                assertTrue(
                    "${stranger.name} never got Alice's newest sealed profile",
                    lab.tryAwait(1) { if (holdsLatest(stranger, alice)) 1 else 0 },
                )
                assertNull("a stranger's session carries no key", stranger.peer(alice)?.meshcoreKey)
            }

            // A block, landed between a fan-out's audience read and its seal, with the mint it causes: the fan-out must not
            // seal the newer version for the older set. Bob keeps his session, and the newer version reaches him keyless.
            blockDuringAFanOut(alice, bob)
            awaitKey(bob, alice, null)
            assertCopiesOfHeldVersionKeyless(copies, viewer = bob, holder = alice)
            // The rebind after it goes to contacts only, and Bob is no longer one.
            alice.driveMeshcoreBoard(SEED_2)
            alice.heal()
            assertTrue(lab.tryAwait(1) { if (holdsLatest(bob, alice)) 1 else 0 })
            assertNull("a blocked contact never gets the next key", bob.peer(alice)?.meshcoreKey)
            assertTrue(lab.tryAwait(1) { if (holdsLatest(dave, alice)) 1 else 0 })
            assertNull(dave.peer(alice)?.meshcoreKey)
            val keylessAtDave = checkNotNull(dave.peer(alice)).updatedAt

            // Alice accepts Dave: a strictly newer version than the keyless one he holds now, carrying the key, reaches him.
            alice.accept(alice.dmWith(dave))
            awaitKey(dave, alice, KEY_2)
            assertTrue("the key came under a newer version", checkNotNull(dave.peer(alice)).updatedAt > keylessAtDave)
            val accepted = alice.meshcoreClaims.sealedVersion.first()
            assertTrue(lab.tryAwait(1) { copies.count { it.peerId == dave.nodeId && it.version == accepted } })
            alice.heal()
            // The accept is an audience-only mint (Stage 5b-1): Eve's answer did not change, so she is spared it — never
            // sealed the accept's version — and holds the older one Alice sealed her at the rebind, every field the same.
            assertTrue("Eve was sealed the accept", copies.none { it.peerId == eve.nodeId && it.version == accepted })
            val sealedEve = copies.filter { it.peerId == eve.nodeId }.maxOf { it.version }
            assertTrue("Eve's newest copy is not older than the accept", sealedEve < accepted)
            assertTrue(lab.tryAwait(1) { if (holdsNewestSealedTo(eve, alice, copies)) 1 else 0 })
            assertEquals(sealedEve, eve.presentationOf(alice)?.version)
            assertNull("Eve, never accepted, still holds none", eve.peer(alice)?.meshcoreKey)

            assertOneLengthPerVersion(copies, bob)
            assertTrue(alice.sendDm(dave, "welcome"))
            lab.assertConverged(listOf(alice, dave), atLeast = 2) { it.dmWith(if (it === alice) dave else alice) }
        }

    /**
     * An audience-only mint seals only the peer whose answer changed (ADR 2026-11.gsqu, Stage 5b-1, the maintainer's
     * decision of 2026-11-13): Alice accepting Dave seals him one copy, keyed and strictly newer, which a carrier with no
     * session holds; blocking him seals him one keyless and strictly newer. Bob and Carol, unchanged, are sent no new copy
     * and keep the key and the newest version sealed them; no node logs the key; a restart mints nothing and seals each
     * session at most once.
     */
    @Test
    fun aContactSetChangeSealsTheChangedPeerAndSparesTheRest() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            val dave = lab.node("dave").apply { setDisplayName("Dave") }
            val frank = lab.node("frank").apply { setDisplayName("Frank") }
            val (copies, copiesAt) = recordCopies(alice)
            // Every intro mark from the start, so a copy that spends one made before the watch is still named.
            val marks = CopyOnWriteArrayList<IntroMark>()
            alice.manager.onIntroMarked = { peerId, token -> marks += IntroMark(peerId, token, System.nanoTime()) }
            lab.linkAll(
                alice to bob,
                alice to carol,
                alice to dave,
                alice to frank,
                bob to carol,
                bob to dave,
                carol to dave,
                frank to bob,
                frank to carol,
                frank to dave,
            )
            lab.awaitAcquainted(alice, bob, carol, dave, frank)
            // Bob and Carol are Alice's contacts (she wrote to them); Dave DMs her once and is not answered; Frank, the
            // carrier, never writes her.
            listOf(bob, carol).forEach { exchange(alice, it, atLeast = 2) }
            assertTrue(dave.sendDm(alice, "hello?"))

            alice.driveMeshcoreBoard(SEED)
            awaitKey(bob, alice, KEY)
            awaitKey(carol, alice, KEY)
            alice.heal()
            // The claim's mint is a full fan-out: everyone holds the newest version before the contact set moves.
            for (peer in listOf(bob, carol, dave)) assertTrue(lab.tryAwait(1) { if (holdsLatest(peer, alice)) 1 else 0 })
            assertNull(dave.peer(alice)?.meshcoreKey)

            val keylessAtDave = checkNotNull(dave.peer(alice)).updatedAt
            val beforeAccept = IntroWatch.of(alice, dave, marks, copiesAt)
            alice.accept(alice.dmWith(dave))
            awaitKey(dave, alice, KEY)
            val keyedAtDave = checkNotNull(dave.peer(alice)).updatedAt
            assertTrue("the key came under a newer version", keyedAtDave > keylessAtDave)
            alice.heal()
            assertSealedOnlyTo(copies, alice.meshcoreClaims.sealedVersion.first(), dave, carrier = frank, since = beforeAccept)

            val beforeBlock = IntroWatch.of(alice, dave, marks, copiesAt)
            alice.block(dave)
            awaitKey(dave, alice, null)
            assertTrue("the block came under a newer version", checkNotNull(dave.peer(alice)).updatedAt > keyedAtDave)
            alice.heal()
            assertSealedOnlyTo(copies, alice.meshcoreClaims.sealedVersion.first(), dave, carrier = frank, since = beforeBlock)

            for (contact in listOf(bob, carol)) {
                assertEquals("${contact.name} keeps the key", KEY, contact.peer(alice)?.meshcoreKey)
                assertTrue("${contact.name} holds an older version than its newest copy", holdsNewestSealedTo(contact, alice, copies))
            }
            assertTrue(alice.metrics.seals().sealedProfilesSpared >= 4)
            assertNoLogCarriesTheKey(alice)
            for (contact in listOf(bob, carol)) exchange(alice, contact, atLeast = 4, sealedTo = sealedTo(alice, copies))

            assertRestartMintsNothingAndSealsOnce(alice, listOf(bob, carol, dave, frank), blocked = dave)
            assertNull(dave.peer(alice)?.meshcoreKey)
            for (contact in listOf(bob, carol)) {
                exchange(alice, contact, atLeast = 6)
                assertEquals(KEY, contact.peer(alice)?.meshcoreKey)
            }
        }

    /**
     * A copy sealed to a contact dark past custody's TTL is gone, so the next contact-set change seals it again rather
     * than spare it (the 5b-1 review): Erin is away across the claim's mint and a day past it, every carrier's copy of it
     * gone, comes back holding no key, and gets it with the next accept, which would otherwise have sealed Dave alone.
     */
    @Test
    fun aContactDarkPastCustodysTtlGetsTheKeyAtTheNextContactChange() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val erin = lab.node("erin").apply { setDisplayName("Erin") }
            val dave = lab.node("dave").apply { setDisplayName("Dave") }
            lab.linkAll(alice to bob, alice to erin, alice to dave, bob to erin, bob to dave, dave to erin)
            lab.awaitAcquainted(alice, bob, erin, dave)
            listOf(bob, erin).forEach { exchange(alice, it, atLeast = 2) }
            assertTrue(dave.sendDm(alice, "hello?"))
            lab.assertConverged(listOf(alice, dave), atLeast = 1) { it.dmWith(if (it === alice) dave else alice) }

            val copies = CopyOnWriteArrayList<MeshManager.SealedProfileSend>()
            alice.manager.onSealedProfile = { copies += it }
            lab.unlink(alice, erin)
            lab.unlink(bob, erin)
            lab.unlink(dave, erin)
            // Alice's DMs to Erin stay in her own custody alone while Erin is away: no carrier relays them on, so the
            // eviction below takes every copy there is.
            val toErinChat = { frame: WireEnvelope ->
                WireCodec.decodeEnvelope(frame.signed)?.let {
                    it.type == FrameType.CHAT && it.senderId == alice.nodeId && it.recipientId == erin.nodeId
                } == true
            }
            alice.transport.lossy(bob.transport, toErinChat)
            alice.transport.lossy(dave.transport, toErinChat)
            alice.driveMeshcoreBoard(SEED)
            awaitKey(bob, alice, KEY)
            alice.heal()

            // A day and more, and the claim's seal to Erin gone with it (its TTL, or the quota).
            assertTrue(lab.tryAwait(1) { copies.count { it.peerId == erin.nodeId } })
            evictEverySealTo(alice, erin, copies)
            alice.transport.lossy(bob.transport)
            alice.transport.lossy(dave.transport)
            // 25 h, past custody's 24 h TTL (`ForwardRepository.DEFAULT_TTL_MS`) and the stale bound, in two steps with a
            // heal between: past the 12 h republish floor, the heal restamps Alice's cleartext profile at its version, so
            // its custody row never expires — an expired one would read as an edit, and the version that edit mints
            // fans out to everyone, Erin's keyed copy among them, before the accept. Under 48 h before a sweep.
            lab.clock.advance(13 * 60 * 60_000L)
            alice.heal()
            lab.clock.advance(12 * 60 * 60_000L)
            alice.heal()
            lab.link(alice, erin)
            lab.link(bob, erin)
            lab.link(dave, erin)
            lab.awaitCustodyParity(alice, bob, dave, erin)
            alice.heal()
            assertNull("the claim's copy reached Erin after all", erin.peer(alice)?.meshcoreKey)

            alice.accept(alice.dmWith(dave))
            awaitKey(dave, alice, KEY)
            awaitKey(erin, alice, KEY)
            // The profile Alice republished across the jump is a custody row Erin lacks, offered again only on a link's
            // digest exchange: relink, so the stores settle before the oracle compares them.
            lab.unlink(alice, erin)
            lab.link(alice, erin)
            exchange(alice, erin, atLeast = 4, sealedTo = sealedTo(alice, copies))
        }

    /**
     * A contact that restores from a backup re-forms its session with an init, which Alice answers with a contact-card
     * intro; the intro marks the contact, so the next audience-only mint seals it beside the changed peer, though its
     * answer did not change, and it ends holding the keyed current copy.
     */
    @Test
    fun aContactThatResetsHoldsTheKeyedCurrentCopyAfterTheNextContactChange() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            val dave = lab.node("dave").apply { setDisplayName("Dave") }
            val copies = CopyOnWriteArrayList<MeshManager.SealedProfileSend>()
            alice.manager.onSealedProfile = { copies += it }
            lab.linkAll(alice to bob, alice to carol, alice to dave, bob to carol, carol to dave, bob to dave)
            lab.awaitAcquainted(alice, bob, carol, dave)
            listOf(bob, carol).forEach { exchange(alice, it, atLeast = 2) }
            assertTrue(dave.sendDm(alice, "hello?"))
            alice.driveMeshcoreBoard(SEED)
            awaitKey(bob, alice, KEY)
            awaitKey(carol, alice, KEY)
            alice.heal()
            for (peer in listOf(bob, carol, dave)) assertTrue(lab.tryAwait(1) { if (holdsLatest(peer, alice)) 1 else 0 })

            bob.restoreFromBackup()
            // As `RestoreLabTest` does: Alice writes while Bob is away, under the session his restore wiped; his restore's
            // reset re-forms it with an init, which Alice answers with the intro, and re-seals what she sent.
            assertTrue(alice.sendDm(bob, "while you were away"))
            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 3) { it.dmWith(if (it === alice) bob else alice) }
            lab.link(bob, carol)
            lab.link(bob, dave)
            assertTrue(bob.sendDm(alice, "back"))
            lab.assertConverged(listOf(alice, bob), atLeast = 4) { it.dmWith(if (it === alice) bob else alice) }

            alice.accept(alice.dmWith(dave))
            awaitKey(dave, alice, KEY)
            assertTrue("Bob never held the current version", lab.tryAwait(1) { if (holdsLatest(bob, alice)) 1 else 0 })
            assertEquals(KEY, bob.peer(alice)?.meshcoreKey)
            exchange(alice, bob, atLeast = 6, sealedTo = sealedTo(alice, copies))
        }

    /**
     * Every DM [owner] sealed [peer] while it was away, the claim's copy and any other, leaves [owner]'s custody: none
     * reached a carrier, so [owner]'s holds all there are. Evicted before a clock jump, which would hide the rows from the
     * live read the eviction goes by.
     */
    private suspend fun evictEverySealTo(
        owner: LabNode,
        peer: LabNode,
        copies: List<MeshManager.SealedProfileSend>,
    ) {
        val rows = ":${FrameType.CHAT}:${owner.nodeId.take(6)}→${peer.nodeId.take(6)}@"
        val ids =
            owner
                .custodyFrames()
                .filter { rows in it }
                .map { it.substringBefore(':') }
                .toSet()
        val sealed = copies.filter { it.peerId == peer.nodeId }.map { it.frameId }
        assertTrue("a sealed profile to ${peer.name} is not in ${owner.name}'s custody", sealed.all { it in ids })
        owner.forgetCustody { it in ids }
        assertTrue(lab.tryAwait(1) { if (owner.custodyFrames().none { rows in it }) 1 else 0 })
    }

    /**
     * The copies of [version] went to [changed] alone, and [carrier], who shares no session, holds one in custody: an
     * audience-only mint seals nobody whose answer did not change. The one re-seal allowed is the fan-out's designed one:
     * a session a contact-card intro marked owed is sealed at the next fan-out whatever it was last sent (ADR
     * 2026-11.gsqu, 5b-1). Counted exactly, through the token (`MeshManager.onIntroMarked`): every copy past the first
     * names the intro mark it superseded ([MeshManager.SealedProfileSend.mark]), a mark made for [changed], and no mark is
     * spent twice — so a mark spent at another version inside the window, or one straddling the capture, cannot be counted
     * toward this one. Never for no mark, and never anyone else.
     */
    private suspend fun assertSealedOnlyTo(
        copies: List<MeshManager.SealedProfileSend>,
        version: Long,
        changed: LabNode,
        carrier: LabNode,
        since: IntroWatch,
    ) {
        assertTrue("${changed.name} was never sealed v$version", lab.tryAwait(1) { copies.count { it.version == version } })
        // The caller healed first: the heal's fan-out waits on the mint's under the same lock, so every copy of the
        // version is recorded by now. The heal seals only a session an intro marked since that fan-out read the marks.
        val atVersion = copies.filter { it.version == version }
        val id = checkNotNull(atVersion.first().frameId)
        assertTrue("${carrier.name} never held the copy", lab.tryAwait(1) { if (id in carrier.custodyIds()) 1 else 0 })
        val trigger = since.describe()
        assertEquals("sealed beyond the changed peer; $trigger", setOf(changed.nodeId), atVersion.map { it.peerId }.toSet())
        val spent = atVersion.mapNotNull { it.mark }
        assertTrue(
            "${changed.name} sealed v$version ${atVersion.size} times, ${atVersion.size - spent.size} for no intro mark; $trigger",
            atVersion.count { it.mark == null } <= 1 && atVersion.size <= 1 + spent.size,
        )
        assertEquals("an intro mark spent twice at v$version: $spent; $trigger", spent.distinct(), spent)
        val markedFor = since.tokensOf(changed)
        assertTrue("a copy of v$version spent a mark not made for ${changed.name}: $spent; $trigger", markedFor.containsAll(spent))
    }

    /**
     * Records every sealed profile copy [owner]'s fan-out sends, and each again stamped on a monotonic clock for an
     * [IntroWatch]'s description: the lab's calendar does not move unless a scenario jumps it, so it would stamp every copy
     * alike.
     */
    private fun recordCopies(
        owner: LabNode,
    ): Pair<CopyOnWriteArrayList<MeshManager.SealedProfileSend>, CopyOnWriteArrayList<Pair<Long, MeshManager.SealedProfileSend>>> {
        val copies = CopyOnWriteArrayList<MeshManager.SealedProfileSend>()
        val stamped = CopyOnWriteArrayList<Pair<Long, MeshManager.SealedProfileSend>>()
        owner.manager.onSealedProfile = {
            copies += it
            stamped += System.nanoTime() to it
        }
        return copies to stamped
    }

    /** One intro mark (`MeshManager.onIntroMarked`): the peer, its token, and when it was seen on a monotonic clock. */
    private class IntroMark(
        val peerId: String,
        val token: Long,
        val atNanos: Long,
    )

    /**
     * What may make [owner]'s fan-out seal [peer] again at one version, read at [of] and described against now: the intro
     * marks since (`MeshManager.onIntroMarked`) with their tokens, and the copies sealed since, each stamped on a monotonic
     * clock so the order shows; the intros answered and sent on both sides (`MeshMetrics`, [owner]'s from the moment the
     * keyless copy was read at [peer], [peer]'s own key-exchange deltas beside them); and the session's root and era on both
     * sides — a moved root is a reset, an unmoved one an intro answered twice or re-sent. Named in a failure.
     */
    private class IntroWatch(
        private val owner: LabNode,
        private val peer: LabNode,
        private val marks: List<IntroMark>,
        private val marksAt: Int,
        private val copies: List<Pair<Long, MeshManager.SealedProfileSend>>,
        private val copiesAt: Int,
        private val answered: Long,
        private val sent: Long,
        private val peerKeys: MeshMetrics.Snapshot.Keys,
        private val root: String?,
        private val peerEra: Long?,
        private val startedNanos: Long,
    ) {
        /** Every token a mark for [node] ever carried: a copy may spend one made before the watch began. */
        fun tokensOf(node: LabNode): Set<Long> = marks.filter { it.peerId == node.nodeId }.mapTo(HashSet()) { it.token }

        suspend fun describe(): String {
            val keys = owner.metrics.keys()
            val theirKeys = peer.metrics.keys()
            val names = mapOf(peer.nodeId to peer.name)
            val ours = owner.session(peer)
            val theirs = peer.session(owner)
            val ms = { nanos: Long -> (nanos - startedNanos) / NANOS_PER_MS }
            val marked = marks.drop(marksAt).map { "${names[it.peerId] ?: it.peerId.take(6)}#${it.token}@+${ms(it.atNanos)}ms" }
            val sealed =
                copies.drop(copiesAt).map { (at, c) ->
                    "${names[c.peerId] ?: c.peerId.take(6)} v${c.version} mark=${c.mark}@+${ms(at)}ms"
                }
            // An intro answered with the root unmoved is a second answer to an init, not a reset inside the window; how
            // old the peer's era is says whether that init was a fresh one (a race's loser re-initiating) or the first.
            return "intro marks since: $marked, copies since: $sealed, ${owner.name} introsAnswered +" +
                "${keys.introsAnswered - answered}, introsSent +${keys.introsSent - sent}; ${peer.name} introsAnswered +" +
                "${theirKeys.introsAnswered - peerKeys.introsAnswered}, introsSent +${theirKeys.introsSent - peerKeys.introsSent}; " +
                "${owner.name}↔${peer.name} root $root→${ours?.rootHash}, ${peer.name}'s era $peerEra→${theirs?.establishedAt} " +
                "(${owner.now() - (theirs?.establishedAt ?: 0L)} ms old), initiator ${owner.name}=${ours?.weAreInitiator} " +
                "${peer.name}=${theirs?.weAreInitiator}, ${peer.name}'s last reset sent at ${theirs?.lastResetSentAt}"
        }

        companion object {
            suspend fun of(
                owner: LabNode,
                peer: LabNode,
                marks: List<IntroMark>,
                copies: List<Pair<Long, MeshManager.SealedProfileSend>>,
            ): IntroWatch {
                val keys = owner.metrics.keys()
                return IntroWatch(
                    owner,
                    peer,
                    marks,
                    marks.size,
                    copies,
                    copies.size,
                    keys.introsAnswered,
                    keys.introsSent,
                    peer.metrics.keys(),
                    owner.session(peer)?.rootHash,
                    peer.session(owner)?.establishedAt,
                    System.nanoTime(),
                )
            }
        }
    }

    /** [assertConverged]'s parity mode for [owner]'s profile: the newest version [owner] sealed each viewer ([copies]). */
    private fun sealedTo(
        owner: LabNode,
        copies: List<MeshManager.SealedProfileSend>,
    ): suspend (LabNode, LabNode) -> MeshLab.SealedCopy? =
        { holder, viewer ->
            if (holder === owner) {
                copies
                    .filter { it.peerId == viewer.nodeId }
                    .maxByOrNull { it.version }
                    ?.let { MeshLab.SealedCopy(it.version, it.content.pr?.meshcoreKey) }
            } else {
                null
            }
        }

    /** Never in a log line, in any spelling, on any node: the key, nor the bind's signature. */
    private fun assertNoLogCarriesTheKey(owner: LabNode) = assertNoLogCarries(KEY, MeshcoreBindFixture.sig(owner.nodeId, SEED))

    /**
     * Neither [key] nor [sig] (canonical base64) is in any log line on any node, in base64 or in hex, whole or by its first
     * 16 characters.
     */
    private fun assertNoLogCarries(
        key: String,
        sig: String,
    ) {
        val spellings = listOf(key, sig, hex(b64d(key)), hex(b64d(sig))).flatMap { listOf(it, it.take(16)) }
        val lines = ShadowLog.getLogs().map { "${it.tag}: ${it.msg} ${it.throwable ?: ""}" }
        val leaks = lines.filter { line -> spellings.any { it in line } }
        assertTrue("log lines carrying the key or its bind: $leaks", leaks.isEmpty())
    }

    /**
     * [a] writes [b], [b] answers once it has opened it, and the thread converges on both with at least [atLeast]
     * messages. In turn, so [b]'s answer rides [a]'s session rather than racing an init of its own: a raced init's frames
     * carry it until answered, and each is answered with a contact-card intro, a sealed profile outside the fan-out.
     */
    private suspend fun exchange(
        a: LabNode,
        b: LabNode,
        atLeast: Int,
        sealedTo: (suspend (LabNode, LabNode) -> MeshLab.SealedCopy?)? = null,
    ) {
        val before = b.decrypted(b.dmWith(a)).size
        assertTrue(a.sendDm(b, "hi ${b.name}"))
        lab.await(before + 1) { b.decrypted(b.dmWith(a)).size }
        assertTrue(b.sendDm(a, "hi ${a.name}"))
        lab.assertConverged(listOf(a, b), atLeast = atLeast, sealedTo = sealedTo) { it.dmWith(if (it === a) b else a) }
    }

    /**
     * [owner] restarts and heals twice: the claim and its audience are the fingerprint published last, so nothing is
     * minted; nothing is known of what any session holds, so each is sealed the current version at most once, and
     * [blocked]'s copy carries no key.
     */
    private suspend fun assertRestartMintsNothingAndSealsOnce(
        owner: LabNode,
        others: List<LabNode>,
        blocked: LabNode,
    ) {
        val sealedVersion = owner.meshcoreClaims.sealedVersion.first()
        owner.restart()
        val copies = CopyOnWriteArrayList<MeshManager.SealedProfileSend>()
        owner.manager.onSealedProfile = { copies += it }
        others.forEach { lab.link(owner, it) }
        owner.heal()
        owner.heal()
        assertEquals("a restart minted a version", sealedVersion, owner.meshcoreClaims.sealedVersion.first())
        val perPeer = copies.groupBy { it.peerId }
        assertTrue("a session was sealed twice after the restart: $perPeer", perPeer.values.all { it.size == 1 })
        assertTrue("a copy after the restart was not the current version", copies.all { it.version == sealedVersion })
        assertTrue(
            "a key went to the blocked peer after the restart",
            copies.none { it.peerId == blocked.nodeId && it.content.pr?.meshcoreKey != null },
        )
    }

    /**
     * [owner] blocks [contact] between a fan-out's audience read and its seal, and waits there for the mint the block
     * causes: the fan-out must then seal the newer version for the newer set, never for the older one.
     */
    private suspend fun blockDuringAFanOut(
        owner: LabNode,
        contact: LabNode,
    ) {
        val before = owner.meshcoreClaims.sealedVersion.first()
        val raced = AtomicBoolean(false)
        owner.manager.beforeSealedProfileFanOut = {
            if (raced.compareAndSet(false, true)) {
                owner.block(contact)
                assertTrue(lab.tryAwait(1) { if (owner.meshcoreClaims.sealedVersion.first() > before) 1 else 0 })
            }
        }
        owner.heal()
        owner.manager.beforeSealedProfileFanOut = null
        assertTrue("the race ran", raced.get())
    }

    /**
     * Every copy of the version [viewer] holds from [holder] was sent keyless. The row is a state and the record an event
     * the fan-out writes after its send returns, so this waits for the record of the version the row holds, then judges
     * every copy of it, never the last one recorded.
     */
    private suspend fun assertCopiesOfHeldVersionKeyless(
        copies: List<MeshManager.SealedProfileSend>,
        viewer: LabNode,
        holder: LabNode,
    ) {
        val held = checkNotNull(viewer.peer(holder)).updatedAt
        assertTrue(lab.tryAwait(1) { if (copies.any { it.peerId == viewer.nodeId && it.version >= held }) 1 else 0 })
        val atHeld = copies.filter { it.peerId == viewer.nodeId && it.version == held }
        assertTrue("no copy of version $held to ${viewer.name} was recorded", atHeld.isNotEmpty())
        assertTrue("a copy of the version ${viewer.name} holds carried the key", atHeld.all { it.content.pr?.meshcoreKey == null })
    }

    /** One version, one length: keyed and padded keyless copies alike, [blocked]'s after the block included. */
    private fun assertOneLengthPerVersion(
        copies: List<MeshManager.SealedProfileSend>,
        blocked: LabNode,
    ) {
        val byVersion = copies.groupBy { it.version }
        for ((version, sent) in byVersion) {
            val lengths = sent.map { it.plaintextBytes }.distinct()
            assertEquals("copies of version $version differ in length: $lengths", 1, lengths.size)
        }
        assertTrue(
            "a version went to ${blocked.name} keyless and to someone else",
            byVersion.values.any { v -> v.size > 1 && v.any { it.peerId == blocked.nodeId && it.content.pr?.meshcoreKey == null } },
        )
    }

    /** Limits for two nodes on a MeshCore air: the DM ✓✓ batched in a second, the re-probe's sweep at 200 ms. */
    private val quick = LabLimits(dmAckHoldMs = 1_000L, loraLingerSweepMs = 200L)

    /** Alice and Bob, each with a MeshCore board on one air in range of the other's, met as contacts, taught, parted. */
    private suspend fun boardedPair(): Pair<LabNode, LabNode> {
        val air = lab.meshcoreAir()
        air.meshcoreEdit { link("alice", "bob") }
        val alice = lab.node("alice", quick, meshcore = air).apply { setDisplayName("Alice") }
        val bob = lab.node("bob", quick, meshcore = air).apply { setDisplayName("Bob") }
        lab.meetOverMeshcore(alice, bob)
        return alice to bob
    }

    /**
     * The copy attack (ADR 2026-11.gsqu, Stage 5d): Mallory, a live direct contact of Bob's — so his board map keeps her row
     * up to the contest rule — seals Alice's board key **and** Alice's signature as her own claim. The signature binds
     * Alice's node, never Mallory's, so Bob drops the field and stores no key for Mallory; Alice's board stays taught on
     * Bob's and uncontested; Bob counts the refusal.
     */
    @Test
    fun aForgerSealingAnothersKeyAndBindIsRefusedAndTheOwnerStaysTaught() =
        runBlocking {
            val (alice, bob) = boardedPair()
            val aliceKey = b64(checkNotNull(alice.meshcoreBoard).key)
            awaitKey(bob, alice, aliceKey)
            val mallory = lab.node("mallory", quick).apply { setDisplayName("Mallory") }
            val bobCopies = CopyOnWriteArrayList<MeshManager.SealedProfileSend>()
            bob.manager.onSealedProfile = { bobCopies += it }
            // Contacts both ways, Bob's audience mint for her sealed once their session confirmed (the meeting's heal).
            lab.meetOverMeshcore(bob, mallory)
            lab.link(bob, mallory)
            assertTrue("Mallory is Bob's direct contact", bob.isDirectContact(mallory))
            assertTrue("Bob is Mallory's: her keyed copy goes to him", mallory.isDirectContact(bob))

            val unproven = bob.metrics.seals().meshcoreBindUnproven
            val before = mallory.meshcoreClaims.sealedVersion.first()
            val stolen = alice.meshcoreClaims.board.first()
            mallory.manager.meshcoreClaimForTest.value = MeshcoreBoardClaim(checkNotNull(stolen.key), checkNotNull(stolen.sig))
            // The fence: the version Mallory minted for the forged claim is the one Bob applied.
            assertTrue(lab.tryAwait(1) { if (mallory.meshcoreClaims.sealedVersion.first() > before) 1 else 0 })
            val forged = mallory.meshcoreClaims.sealedVersion.first()
            mallory.heal()
            assertTrue(
                "Bob never applied Mallory's forged version",
                lab.tryAwait(1) { if (bob.peer(mallory)?.updatedAt == forged) 1 else 0 },
            )

            assertNull("Bob stored the copied key for Mallory", bob.peer(mallory)?.meshcoreKey)
            assertTrue("the refusal was counted", bob.metrics.seals().meshcoreBindUnproven > unproven)
            val map = bob.manager.meshcoreBoardMap().first()
            assertNull(map[mallory.nodeId])
            val owner = checkNotNull(map[alice.nodeId]) { "Alice left Bob's board map" }
            assertEquals(aliceKey, owner.key)
            assertFalse("a forger contests nothing", owner.contested)
            assertTrue("Alice's board stays taught on Bob's", bob.meshcoreTaught(checkNotNull(alice.meshcoreBoard).key))
            assertEquals(aliceKey, bob.peer(alice)?.meshcoreKey)

            exchange(bob, mallory, atLeast = 4, sealedTo = sealedTo(bob, bobCopies))
        }

    /**
     * The board signs on every proof (security B3(a), lora N4), and determinism makes that free: a second Ready on Alice — a
     * relaunch — asks the board again, gets the same bytes, and mints no sealed version. An unbind then withdraws the key
     * and its bind, and Bob clears Alice's key. Neither the key nor the bind is in any log line.
     */
    @Test
    fun aSecondReadyMintsNothingAndAnUnbindClearsTheKeyAtTheContact() =
        runBlocking {
            val (alice, bob) = boardedPair()
            val aliceKey = b64(checkNotNull(alice.meshcoreBoard).key)
            awaitKey(bob, alice, aliceKey)
            assertEquals("every copy Bob applied carried a bind that verifies", 0L, bob.metrics.seals().meshcoreBindUnproven)
            val version = alice.meshcoreClaims.sealedVersion.first()
            val bind =
                checkNotNull(
                    alice.meshcoreClaims.board
                        .first()
                        .sig,
                )
            val asked = bindsAsked(alice)
            val writes = alice.meshcoreClaimWritesEver.get()

            alice.restart()
            assertTrue("the new Ready asked the board to sign again", bindsAsked(alice) > asked)
            // The fence: the second Ready's consent reached the claim store, then a heal's sealed send ran the mint check
            // under the profile lock — where a claim that changed would mint.
            assertTrue(
                "the second Ready's consent never reached the claim store",
                lab.tryAwait(1) { if (alice.meshcoreClaimWritesEver.get() > writes) 1 else 0 },
            )
            alice.heal()
            assertEquals("a second Ready minted a sealed version", version, alice.meshcoreClaims.sealedVersion.first())
            // What the board signed this time (the consent is published only after the bind), not the store's copy.
            assertEquals(
                "the board's bind is byte-identical",
                bind,
                checkNotNull(alice.meshcoreSession)
                    .consent.value
                    ?.sig
                    ?.let(::b64),
            )

            assertEquals(MeshcoreUnbind.CLEARED, alice.meshcoreHooks.unbind(alice.meshcoreAddress))
            assertTrue(
                lab.tryAwait(1) {
                    if (alice.meshcoreClaims.board
                            .first()
                            .key == null
                    ) {
                        1
                    } else {
                        0
                    }
                },
            )
            assertNull(
                "the bind went with the key",
                alice.meshcoreClaims.board
                    .first()
                    .sig,
            )
            lab.link(alice, bob)
            awaitKey(bob, alice, null)
            assertNull(bob.manager.meshcoreBoardMap().first()[alice.nodeId])
            exchange(alice, bob, atLeast = 4)
            assertNoLogCarries(aliceKey, bind)
        }

    /**
     * A non-contact's copy carries neither the key nor its bind, padded to the contact's keyed length, on both layouts
     * (crypto B1, B2): Bob, a contact, and Dave, a stranger who wrote once, are sealed the claim's version in the compact
     * layout, then — sealed to as an older build — the next board's version in the named one, every other field the same.
     */
    @Test
    fun aNonContactsCopyCarriesNeitherFieldAndIsOneLengthOnBothLayouts() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val dave = lab.node("dave").apply { setDisplayName("Dave") }
            val copies = CopyOnWriteArrayList<MeshManager.SealedProfileSend>()
            alice.manager.onSealedProfile = { copies += it }
            lab.linkAll(alice to bob, alice to dave, bob to dave)
            lab.awaitAcquainted(alice, bob, dave)
            exchange(alice, bob, atLeast = 2)
            assertTrue(dave.sendDm(alice, "hello?"))
            lab.assertConverged(listOf(alice, dave), atLeast = 1) { it.dmWith(if (it === alice) dave else alice) }

            alice.driveMeshcoreBoard(SEED)
            awaitKey(bob, alice, KEY)
            alice.heal()
            val compact = alice.meshcoreClaims.sealedVersion.first()
            val compactLength = assertOneLengthNeitherFieldToTheStranger(copies, compact, contact = bob, stranger = dave)

            alice.sealToAsOlderBuild(bob)
            alice.sealToAsOlderBuild(dave)
            alice.driveMeshcoreBoard(SEED_2)
            awaitKey(bob, alice, KEY_2)
            alice.heal()
            val named = alice.meshcoreClaims.sealedVersion.first()
            val namedLength = assertOneLengthNeitherFieldToTheStranger(copies, named, contact = bob, stranger = dave)
            // Every field but the version and the key the same: only the layout can move the length.
            assertTrue("the second version went out in the other layout ($compactLength, $namedLength)", namedLength != compactLength)
            assertNull(dave.peer(alice)?.meshcoreKey)

            alice.sealToAsOlderBuild(bob, v3 = true)
            alice.sealToAsOlderBuild(dave, v3 = true)
            exchange(alice, bob, atLeast = 4)
        }

    /**
     * The copies of [version] to [contact] and to [stranger] are recorded, one length: the contact's carries the key and its
     * bind, the stranger's neither. Answers that length.
     */
    private suspend fun assertOneLengthNeitherFieldToTheStranger(
        copies: List<MeshManager.SealedProfileSend>,
        version: Long,
        contact: LabNode,
        stranger: LabNode,
    ): Int {
        val wanted = setOf(contact.nodeId, stranger.nodeId)
        assertTrue(
            "v$version never went to both",
            lab.tryAwait(1) {
                if (copies
                        .filter { it.version == version }
                        .map { it.peerId }
                        .toSet()
                        .containsAll(wanted)
                ) {
                    1
                } else {
                    0
                }
            },
        )
        val atVersion = copies.filter { it.version == version && it.peerId in wanted }
        val toContact = atVersion.filter { it.peerId == contact.nodeId }
        val toStranger = atVersion.filter { it.peerId == stranger.nodeId }
        assertTrue(
            "the contact's copy carries both",
            toContact.all { c ->
                c.content.pr?.let {
                    it.meshcoreKey != null &&
                        it.meshcoreSig != null
                } ==
                    true
            },
        )
        assertTrue(
            "the stranger's copy carries neither",
            toStranger.all { c ->
                c.content.pr?.let {
                    it.meshcoreKey == null &&
                        it.meshcoreSig == null
                } !=
                    false
            },
        )
        val lengths = atVersion.map { it.plaintextBytes }.distinct()
        assertEquals("copies of v$version differ in length: $lengths", 1, lengths.size)
        return lengths.single()
    }

    /** How many binds [node]'s sessions asked its board over all its boots (`SIGN_DATA` of the bind's domain). */
    private suspend fun bindsAsked(node: LabNode): Int =
        node.meshcoreWritesEver().count { w ->
            w[0].toInt() == MeshcoreProto.CMD_SIGN_DATA &&
                w.size > MeshcoreBind.DOMAIN.size &&
                w.copyOfRange(1, 1 + MeshcoreBind.DOMAIN.size).contentEquals(MeshcoreBind.DOMAIN)
        }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L

        /** RFC 8032 TEST 2's public key: a full-order Ed25519 point, as a MeshCore board's is. */
        const val KEY = "PUAXw+hDiVqStwqnTRt+vJyYLM8uxJaMwM1V8Sr0Zgw="
        const val KEY_HEX = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"

        /** RFC 8032 TEST 3's public key: the board Alice binds next. */
        const val KEY_2 = "/FHNjmIYoaONpH7QAjDwWAgW7RO6MwOsXeuRFUiQgCU="

        /** RFC 8032 TEST 2's and TEST 3's secret keys: the boards behind [KEY] and [KEY_2], which sign the bind. */
        val SEED: ByteArray = MeshcoreBindFixture.hex("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb")
        val SEED_2: ByteArray = MeshcoreBindFixture.hex("c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7")
    }
}
