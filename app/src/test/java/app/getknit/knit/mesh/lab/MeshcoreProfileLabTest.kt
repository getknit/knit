package app.getknit.knit.mesh.lab

import app.getknit.knit.mesh.MeshManager
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
            alice.heal()
            assertTrue(lab.tryAwait(1) { if (holdsLatest(eve, alice)) 1 else 0 })
            assertNull("Eve, never accepted, still holds none", eve.peer(alice)?.meshcoreKey)

            assertOneLengthPerVersion(copies, bob)
            assertTrue(alice.sendDm(dave, "welcome"))
            lab.assertConverged(listOf(alice, dave), atLeast = 2) { it.dmWith(if (it === alice) dave else alice) }
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
