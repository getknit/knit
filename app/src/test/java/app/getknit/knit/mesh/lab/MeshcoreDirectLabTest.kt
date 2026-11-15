package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.mesh.DmAckCoalescer
import app.getknit.knit.mesh.lora.LoraReprobe
import app.getknit.knit.mesh.lora.meshcore.FakeContact
import app.getknit.knit.mesh.lora.meshcore.FakeForeignClient
import app.getknit.knit.mesh.lora.meshcore.FakeMeshcoreAir
import app.getknit.knit.mesh.lora.meshcore.MeshcoreProto
import app.getknit.knit.mesh.lora.meshcore.randomKey
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.random.Random

/**
 * Compact DMs between two MeshCore boards, end to end (ADR 2026-11.gsqu's Stage 5c-2b amendment): real stacks, each with
 * the real MeshCore plane — session, hooks and transport as `di/MeshModule` builds them — over one [FakeMeshcoreAir] the
 * lab owns ([MeshLab.meshcoreAir]), with the real pair seal. The pair meet over a link as contacts first
 * ([MeshLab.meetOverMeshcore]: a profile never rides the board, and a board is taught only a direct contact's key), part,
 * and only the air is left; every case ends on a link, since custody does not converge over LoRa alone.
 *
 * The air's physics (the cap's window, the overtake, the drifts' own shapes) are `MeshcoreAirPhysicsTest`'s: these prove
 * what two nodes exchange.
 */
@RunWith(RobolectricTestRunner::class)
class MeshcoreDirectLabTest {
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

    /** The DM ✓✓ batches a second, not 45; the re-probe's sweep runs every 200 ms so a moved calendar is noticed. */
    private val quick = LabLimits(dmAckHoldMs = 1_000L, loraLingerSweepMs = 200L)

    /** The field's 45-s hold: a ✓✓ waits out the scenario unless something carries it. */
    private val fieldHold = quick.copy(dmAckHoldMs = DmAckCoalescer.HOLD_MS)

    /** Alice and bob on [air], each in the other's range, met as contacts and parted. */
    private suspend fun pair(
        air: FakeMeshcoreAir,
        bobLimits: LabLimits = quick,
    ): Pair<LabNode, LabNode> {
        air.meshcoreEdit { link("alice", "bob") }
        val alice = lab.node("alice", quick, meshcore = air).apply { setDisplayName("Alice") }
        val bob = lab.node("bob", bobLimits, meshcore = air).apply { setDisplayName("Bob") }
        lab.meetOverMeshcore(alice, bob)
        return alice to bob
    }

    private suspend fun holds(
        node: LabNode,
        author: LabNode,
        id: String,
    ): Int = node.decrypted(node.dmWith(author)).count { it.first == id }

    private suspend fun converge(
        alice: LabNode,
        bob: LabNode,
        atLeast: Int,
    ) {
        lab.link(alice, bob)
        lab.assertConverged(listOf(alice, bob), atLeast = atLeast) { it.dmWith(if (it === alice) bob else alice) }
    }

    private fun unreachable(node: LabNode): Int =
        checkNotNull(node.meshcore)
            .status.value.direct.unreachable

    /**
     * A DM, its ✓✓ and a reaction to it cross between two MeshCore boards out of link range, each a compact unicast on the
     * air: the DM lands over LoRa, its ✓✓ comes back over LoRa, and the reaction lands on the DM.
     */
    @Test
    fun aDmItsTickAndAReactionCrossBetweenTwoMeshcoreBoards() =
        runBlocking {
            val (alice, bob) = pair(lab.meshcoreAir())
            val sent = alice.metrics.lora().loraDirectSent
            bob.accept(bob.dmWith(alice))

            assertTrue(alice.sendDm(bob, "over meshcore"))
            val dm = alice.ownMessageId(alice.dmWith(bob), "over meshcore")
            lab.await(1) { holds(bob, alice, dm) }
            lab.await(1) { bob.loraLog.count { it == "lora direct delivered $dm from ${alice.nodeId}" } }
            assertEquals(DeliveryPlane.LoRa, bob.receivedVia(bob.dmWith(alice), dm))
            assertEquals("its ✓✓ came back over the board", DeliveryPlane.LoRa, lab.awaitReceipt(alice, dm, bob))
            assertTrue("the DM went compact: ${alice.loraLog}", alice.metrics.lora().loraDirectSent > sent)

            bob.react(bob.dmWith(alice), dm, "👍")
            lab.await(1) { if (alice.reactions(dm).contains(bob.nodeId to "👍")) 1 else 0 }
            converge(alice, bob, atLeast = 3)
        }

    /**
     * Bob's board is off — deaf and mute on the air — so alice's DM goes out with every attempt and her board gives up:
     * unreachable, and the next DM is held, not aired. The board comes back, Bob posts, and alice's board hears it; once
     * the re-probe's gap since the failure has run the held DM is re-aired and lands, its ✓✓ following. Which re-air it is
     * stays open, as in `LoraDirectLabTest`: the hearing's resend when the gap had run by the time Bob's post was heard,
     * else the re-probe round once the clock passes it.
     */
    @Test
    fun aBoardThatWasOffIsHeardAgainAndTheHeldDmLands() =
        runBlocking {
            val air = lab.meshcoreAir()
            // Bob's session redials half a second after his board comes back, not the field's 5 s.
            val (alice, bob) = pair(air, bobLimits = quick.copy(meshcoreBackoffMs = 500L))
            val board = checkNotNull(bob.meshcoreBoard)
            air.meshcoreEdit {
                lossy("alice", "bob")
                board.powered = false
            }

            assertTrue(alice.sendDm(bob, "are you there"))
            lab.await(1) {
                alice.metrics
                    .lora()
                    .loraDirectUnreachable
                    .toInt()
            }
            assertTrue(alice.sendDm(bob, "still there?"))
            lab.await(1) {
                alice.metrics
                    .lora()
                    .loraDirectHeld
                    .toInt()
            }

            air.meshcoreEdit {
                lossless("alice", "bob")
                board.powered = true
            }
            bob.awaitMeshcoreBack()
            assertTrue(bob.sendRoom("back on the air"))
            assertTrue(
                "alice's board never heard bob's: ${alice.loraLog}",
                lab.tryAwait(1) { alice.loraLog.count { it == "lora direct heard ${bob.nodeId}" } },
            )
            lab.clock.advance(LoraReprobe.BASE_MS)
            val newest = alice.ownMessageId(alice.dmWith(bob), "still there?")
            lab.await(1) { holds(bob, alice, newest) }
            // Each line is logged after the enqueue that wakes the pacer, so it can trail the landing: awaited, not read.
            assertTrue(
                "the held DM was re-aired by neither the hearing nor the re-probe: ${alice.loraLog}",
                lab.tryAwait(1) {
                    val resent = "lora resend $newest -> ${bob.nodeId}"
                    if (alice.loraLog.any { it.startsWith(resent) || it == "lora reprobe $newest -> ${bob.nodeId}" }) 1 else 0
                },
            )
            assertEquals(DeliveryPlane.LoRa, bob.receivedVia(bob.dmWith(alice), newest))
            assertEquals(DeliveryPlane.LoRa, lab.awaitReceipt(alice, newest, bob))
            converge(alice, bob, atLeast = 4)
        }

    /**
     * Path drift end to end (Q13, the design pass's oracle on the airings in order): bob's record of alice drifts to
     * UNKNOWN, silently. Alice's DM goes direct, bob's ack floods and the repeater re-airs it, and alice's board answers
     * the flooded ack with the direct PATH about 3 s later, which rewrites bob's record and pushes `0x81`. Bob's session
     * re-pins on it — awaited on its counter, the read-back finding zero hops and writing nothing — and only then does the
     * next DM go, it and its ack both direct. Bob holds his ✓✓ for the field's 45 s, so nothing of his own crosses the window.
     */
    @Test
    fun aDriftedRecordIsRepinnedOnTheQ13PathAndTheNextDmGoesDirectBothWays() =
        runBlocking {
            val air = lab.meshcoreAir(channel = true)
            air.meshcoreEdit { repeater("relay", "alice", "bob") }
            val (alice, bob) = pair(air, bobLimits = fieldHold)
            val aliceBoard = checkNotNull(alice.meshcoreBoard)
            val bobBoard = checkNotNull(bob.meshcoreBoard)
            val bobSession = checkNotNull(bob.meshcoreSession)
            air.meshcoreEdit { bobBoard.driftPath(aliceBoard) }
            val repins = bobSession.counters.repinsDone.get()
            val reads = readsOf(bob, aliceBoard.key)
            val pins = pinsOf(bob, aliceBoard.key)
            val t0 = air.now()

            assertTrue(alice.sendDm(bob, "after the drift"))
            lab.await((repins + 1).toInt()) {
                bobSession.counters.repinsDone
                    .get()
                    .toInt()
            }
            val aired = airedSince(air, t0)
            assertEquals(
                "the DM direct, bob's ack flooded and its repeat, then alice's direct PATH: $aired",
                listOf("alice msg direct", "bob ack flood", "relay ack flood", "alice path direct"),
                aired.take(4),
            )
            val gap =
                air.snapshot {
                    val ack = air.airings.first { it.atMs >= t0 && it.from == "bob" && it.type == "ack" }
                    val path = air.airings.first { it.atMs >= t0 && it.from == "alice" && it.type == "path" }
                    path.atMs - (ack.atMs + ack.airMs)
                }
            // The firmware's 3 s, each end resumed through the chaos dispatcher: room for two of its tail stalls (400 ms).
            assertTrue("about 3 s after the ack landed: $gap", gap in 3_000L..4_300L)
            assertEquals("bob's session read alice's record back after the push", reads + 1, readsOf(bob, aliceBoard.key))
            assertEquals("and needed no write", pins, pinsOf(bob, aliceBoard.key))
            assertEquals("bob's record reads zero hops", 0, air.snapshot { bobBoard.contact(aliceBoard.key)?.outPathLen })
            assertEquals(
                "alice's own DM never flooded",
                0L,
                checkNotNull(alice.meshcoreSession)
                    .counters.directFlooded
                    .get(),
            )

            val t1 = air.now()
            assertTrue(alice.sendDm(bob, "and the next one"))
            val next = alice.ownMessageId(alice.dmWith(bob), "and the next one")
            lab.await(1) { holds(bob, alice, next) }
            lab.await(2) { airedSince(air, t1).size }
            assertEquals("the next DM and its ack both direct", listOf("alice msg direct", "bob ack direct"), airedSince(air, t1))

            // Bob's ✓✓s are still in the field's hold: past it, over the link, the heal flushes them.
            lab.link(alice, bob)
            lab.clock.advance(46_000L) // past `DmAckCoalescer.HOLD_MS`, 45 s
            bob.heal()
            lab.assertConverged(listOf(alice, bob), atLeast = 4) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * A ✓✓ held for the field's 45 s rides the reply bob sends inside the hold: the ride itself is awaited (the ids bob's
     * reply carried, `MeshManager.onDmAcksInlined`), then the ✓✓ landing at alice — over LoRa, well inside the hold, so
     * nothing but the reply can have carried it — and the hold left empty for her. The reply's compact copy fit
     * (`loraDirectTooLong` unmoved since the pair parted), and past the re-probe's gap neither side re-probed anything.
     */
    @Test
    fun aTickHeldForTheFieldsHoldRidesTheReplySentInsideIt() =
        runBlocking {
            val (alice, bob) = pair(lab.meshcoreAir(), bobLimits = fieldHold)
            val inlined = CopyOnWriteArrayList<Pair<String, List<String>>>()
            bob.manager.onDmAcksInlined = { to, ids -> inlined += to to ids }
            val tooLong = bob.metrics.lora().loraDirectTooLong

            assertTrue(alice.sendDm(bob, "ping"))
            val dm = alice.ownMessageId(alice.dmWith(bob), "ping")
            lab.await(1) { holds(bob, alice, dm) }
            lab.await(1) { if (dm in bob.manager.dmAcks.pending(alice.nodeId)) 1 else 0 }
            assertTrue(bob.sendDm(alice, "pong"))
            lab.await(1) { if (inlined.any { (to, ids) -> to == alice.nodeId && dm in ids }) 1 else 0 }
            val reply = bob.ownMessageId(bob.dmWith(alice), "pong")
            lab.await(1) { holds(alice, bob, reply) }
            assertEquals("the ✓✓ rode the reply over the board", DeliveryPlane.LoRa, lab.awaitReceipt(alice, dm, bob))
            assertEquals("nothing left in the hold for alice", emptyList<String>(), bob.manager.dmAcks.pending(alice.nodeId))
            assertEquals("the reply's compact copy fit", tooLong, bob.metrics.lora().loraDirectTooLong)
            // Past the re-probe's gap the linger sweep's round judges each peer — the fence: it forgets one with nothing
            // unacked, or defers one whose ✓✓ is still on its way past the jumped calendar — and re-probes nothing.
            assertEquals(DeliveryPlane.LoRa, lab.awaitReceipt(bob, reply, alice))
            val reprobed = listOf(alice, bob).map { it.metrics.lora().loraReprobed }
            lab.clock.advance(LoraReprobe.BASE_MS)
            for ((node, peer) in listOf(alice to bob, bob to alice)) {
                val jumped = node.now()
                val plane = checkNotNull(node.meshcore)
                assertTrue(
                    "${node.name}'s round never judged ${peer.name}: ${plane.reprobeSchedule()}",
                    lab.tryAwait(1) {
                        val entry = plane.reprobeSchedule().firstOrNull { it.recipientId == peer.nodeId }
                        if (entry == null || entry.dueAt > jumped) 1 else 0
                    },
                )
            }
            assertEquals("nothing was re-probed", reprobed, listOf(alice, bob).map { it.metrics.lora().loraReprobed })
            converge(alice, bob, atLeast = 4)
        }

    /**
     * Security (b): a contact on alice's board that Knit did not teach (the user's other app added it) is removed by the
     * next Ready's reconcile — read back from the board once the session's reconcile has run — and never pinned; Bob, whom
     * Knit taught, stays and still gets a DM over the air.
     */
    @Test
    fun aContactKnitDidNotTeachIsRemovedAtReadyAndNeverPinned() =
        runBlocking {
            val air = lab.meshcoreAir()
            val (alice, bob) = pair(air)
            val board = checkNotNull(alice.meshcoreBoard)
            val foreign = randomKey(Random(7))
            air.meshcoreEdit {
                board.contacts +=
                    FakeContact(foreign, MeshcoreProto.ADV_TYPE_CHAT, 0, 0, "Someone".encodeToByteArray(), board.clockSecs)
            }

            alice.restart()
            val session = checkNotNull(alice.meshcoreSession)
            val ran = session.counters
            assertTrue(
                "the Ready never reconciled: skipped=${ran.reconcilesSkipped} dropped=${ran.reconcilesDropped} " +
                    "handler=${ran.handlerFailures} " +
                    "consentRead=${ran.consentReadFailures} record=${ran.recordReadFailures} consent=${session.consent.value} " +
                    "pinFailures=${ran.pinFailures}\n${alice.loraLog.joinToString("\n")}",
                lab.tryAwait(1) { (ran.reconciles.get() + ran.reconcilesSkipped.get()).toInt() },
            )
            val c = session.counters
            assertTrue("the Ready's listing came short: ${alice.loraLog}", c.reconciles.get() > 0)
            assertTrue(
                "the reconcile removed nobody: failed=${c.reconcileFailed} deferred=${c.reconcileDeferred}: ${alice.loraLog}",
                lab.tryAwait(1) { c.reconcileRemoved.get().toInt() },
            )
            assertNull("the foreign contact stands", air.snapshot { board.contact(foreign) })
            assertNotNull("bob's was removed too", air.snapshot { board.contact(checkNotNull(bob.meshcoreBoard).key) })
            assertFalse(session.taughtAndPinned(foreign))
            val pinned =
                alice.meshcoreWritesEver().filter {
                    it[0].toInt() == MeshcoreProto.CMD_ADD_UPDATE_CONTACT && it.copyOfRange(1, 33).contentEquals(foreign)
                }
            assertTrue("the foreign contact was written: ${pinned.size}", pinned.isEmpty())

            assertTrue(alice.sendDm(bob, "still over the board"))
            val dm = alice.ownMessageId(alice.dmWith(bob), "still over the board")
            lab.await(1) { holds(bob, alice, dm) }
            assertEquals(DeliveryPlane.LoRa, bob.receivedVia(bob.dmWith(alice), dm))
            converge(alice, bob, atLeast = 3)
        }

    /**
     * Security (d)/(f): a DM that is not Knit's — the user's own MeshCore app on Bob's very board, writing alice a plain
     * text — lifts nothing. Alice's board marked Bob unreachable first (her DMs went unheard); the foreign DM is counted and
     * discarded (`meshcoreForeignDms`, awaited), and only then is the mark read: it stands, and no hearing was logged.
     */
    @Test
    fun aDmThatIsNotKnitsIsCountedDiscardedAndLiftsNothing() =
        runBlocking {
            val air = lab.meshcoreAir()
            val (alice, bob) = pair(air)
            air.meshcoreEdit { lossy("alice", "bob") }
            assertTrue(alice.sendDm(bob, "are you there"))
            lab.await(1) {
                alice.metrics
                    .lora()
                    .loraDirectUnreachable
                    .toInt()
            }
            assertEquals(1, unreachable(alice))

            val counters = alice.meshcoreHooks.counters
            val foreignBefore = counters.foreignDms.get()
            val bobBoard = checkNotNull(bob.meshcoreBoard)
            val prefix = checkNotNull(alice.meshcoreBoard).key.copyOf(MeshcoreProto.PREFIX_BYTES)
            air.meshcoreEdit {
                val app = FakeForeignClient(bobBoard)
                app.send(checkNotNull(MeshcoreProto.sendText(prefix, 0, bobBoard.clockSecs, "from the app".encodeToByteArray())))
            }
            lab.await(1) { (counters.foreignDms.get() - foreignBefore).toInt() }
            assertEquals("a foreign DM lifted the mark", 1, unreachable(alice))
            assertTrue("a foreign DM was a hearing: ${alice.loraLog}", alice.loraLog.none { it == "lora direct heard ${bob.nodeId}" })
            assertEquals("nor did it reach the inbox", 0, bob.decrypted(bob.dmWith(alice)).count { it.second == "from the app" })

            air.meshcoreEdit { lossless("alice", "bob") }
            converge(alice, bob, atLeast = 3)
        }

    /** The `GET_CONTACT_BY_KEY` reads [node]'s session made of [key]'s record this boot. */
    private suspend fun readsOf(
        node: LabNode,
        key: ByteArray,
    ): Int =
        node.meshcoreWrites().count {
            it[0].toInt() == MeshcoreProto.CMD_GET_CONTACT_BY_KEY && it.copyOfRange(1, 33).contentEquals(key)
        }

    /** The `ADD_UPDATE_CONTACT` writes [node]'s session made for [key] this boot. */
    private suspend fun pinsOf(
        node: LabNode,
        key: ByteArray,
    ): Int =
        node.meshcoreWrites().count {
            it[0].toInt() == MeshcoreProto.CMD_ADD_UPDATE_CONTACT && it.copyOfRange(1, 33).contentEquals(key)
        }

    /** The DM, ack and PATH airings since [t], in order, as `<from> <type> <flood|direct>`. */
    private suspend fun airedSince(
        air: FakeMeshcoreAir,
        t: Long,
    ): List<String> =
        air.snapshot {
            air.airings
                .filter { it.atMs >= t && it.type in setOf("msg", "ack", "path") }
                .map { "${it.from} ${it.type} ${if (it.flood) "flood" else "direct"}" }
        }
}
