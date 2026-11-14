package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.MeshManager
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
 * version never moves for it; and no log line any node writes carries it.
 */
@RunWith(RobolectricTestRunner::class)
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
            alice.driveMeshcoreBoard(KEY)
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

            // Never in a log line, in any spelling, on any node.
            val lines = ShadowLog.getLogs().map { "${it.tag}: ${it.msg} ${it.throwable ?: ""}" }
            val leaks = lines.filter { line -> listOf(KEY, KEY.take(16), KEY_HEX, KEY_HEX.take(16)).any { it in line } }
            assertTrue("log lines carrying the key: $leaks", leaks.isEmpty())
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

            alice.driveMeshcoreBoard(KEY)
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
            alice.driveMeshcoreBoard(KEY_2)
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
            val copies = CopyOnWriteArrayList<MeshManager.SealedProfileSend>()
            alice.manager.onSealedProfile = { copies += it }
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

            alice.driveMeshcoreBoard(KEY)
            awaitKey(bob, alice, KEY)
            awaitKey(carol, alice, KEY)
            alice.heal()
            // The claim's mint is a full fan-out: everyone holds the newest version before the contact set moves.
            for (peer in listOf(bob, carol, dave)) assertTrue(lab.tryAwait(1) { if (holdsLatest(peer, alice)) 1 else 0 })
            assertNull(dave.peer(alice)?.meshcoreKey)

            val keylessAtDave = checkNotNull(dave.peer(alice)).updatedAt
            alice.accept(alice.dmWith(dave))
            awaitKey(dave, alice, KEY)
            val keyedAtDave = checkNotNull(dave.peer(alice)).updatedAt
            assertTrue("the key came under a newer version", keyedAtDave > keylessAtDave)
            alice.heal()
            assertSealedOnlyTo(copies, alice.meshcoreClaims.sealedVersion.first(), dave, carrier = frank)

            alice.block(dave)
            awaitKey(dave, alice, null)
            assertTrue("the block came under a newer version", checkNotNull(dave.peer(alice)).updatedAt > keyedAtDave)
            alice.heal()
            assertSealedOnlyTo(copies, alice.meshcoreClaims.sealedVersion.first(), dave, carrier = frank)

            for (contact in listOf(bob, carol)) {
                assertEquals("${contact.name} keeps the key", KEY, contact.peer(alice)?.meshcoreKey)
                assertTrue("${contact.name} holds an older version than its newest copy", holdsNewestSealedTo(contact, alice, copies))
            }
            assertTrue(alice.metrics.seals().sealedProfilesSpared >= 4)
            assertNoLogCarriesTheKey()
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
            alice.driveMeshcoreBoard(KEY)
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
            alice.driveMeshcoreBoard(KEY)
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
     * The copies of [version] went to [changed] alone, and [carrier], who shares no session, holds that one in custody: an
     * audience-only mint seals nobody whose answer did not change.
     */
    private suspend fun assertSealedOnlyTo(
        copies: List<MeshManager.SealedProfileSend>,
        version: Long,
        changed: LabNode,
        carrier: LabNode,
    ) {
        assertTrue("${changed.name} was never sealed v$version", lab.tryAwait(1) { copies.count { it.version == version } })
        // The caller healed first: the heal's fan-out waits on the mint's under the same lock, so every copy of the
        // version is recorded by now, and the heal itself seals nobody (every session is at it, sealed or spared).
        val id = checkNotNull(copies.first { it.version == version }.frameId)
        assertTrue("${carrier.name} never held the copy", lab.tryAwait(1) { if (id in carrier.custodyIds()) 1 else 0 })
        assertEquals("sealed beyond the changed peer", listOf(changed.nodeId), copies.filter { it.version == version }.map { it.peerId })
    }

    /** [assertConverged]'s parity mode for [owner]'s profile: the newest version [owner] sealed each viewer ([copies]). */
    private fun sealedTo(
        owner: LabNode,
        copies: List<MeshManager.SealedProfileSend>,
    ): suspend (LabNode, LabNode) -> Long? =
        { holder, viewer -> if (holder === owner) copies.filter { it.peerId == viewer.nodeId }.maxOfOrNull { it.version } else null }

    /** Never in a log line, in any spelling, on any node. */
    private fun assertNoLogCarriesTheKey() {
        val lines = ShadowLog.getLogs().map { "${it.tag}: ${it.msg} ${it.throwable ?: ""}" }
        val leaks = lines.filter { line -> listOf(KEY, KEY.take(16), KEY_HEX, KEY_HEX.take(16)).any { it in line } }
        assertTrue("log lines carrying the key: $leaks", leaks.isEmpty())
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
        sealedTo: (suspend (LabNode, LabNode) -> Long?)? = null,
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

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private companion object {
        /** RFC 8032 TEST 2's public key: a full-order Ed25519 point, as a MeshCore board's is. */
        const val KEY = "PUAXw+hDiVqStwqnTRt+vJyYLM8uxJaMwM1V8Sr0Zgw="
        const val KEY_HEX = "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c"

        /** RFC 8032 TEST 3's public key: the board Alice binds next. */
        const val KEY_2 = "/FHNjmIYoaONpH7QAjDwWAgW7RO6MwOsXeuRFUiQgCU="
    }
}
