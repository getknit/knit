package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.mesh.DmAckCoalescer
import app.getknit.knit.mesh.lora.AirPurpose
import app.getknit.knit.mesh.lora.LoraReprobe
import app.getknit.knit.mesh.lora.RadioParams
import app.getknit.knit.mesh.lora.SigningFacts
import app.getknit.knit.mesh.lora.meshcore.Cobs
import app.getknit.knit.mesh.lora.meshcore.FakeContact
import app.getknit.knit.mesh.lora.meshcore.FakeForeignClient
import app.getknit.knit.mesh.lora.meshcore.FakeMeshcoreAir
import app.getknit.knit.mesh.lora.meshcore.MeshcoreProto
import app.getknit.knit.mesh.lora.meshcore.MeshcoreSession
import app.getknit.knit.mesh.lora.meshcore.MeshcoreTraits
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
import java.util.concurrent.atomic.AtomicInteger
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
@Suppress("LargeClass") // what two MeshCore nodes exchange over DMs, one fixture: the ladder's chain, neighbour and diamond too
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
     * unreachable. The next DM she types gets an attempt of its own past the mark (ADR 2026-10.x9fa's 2026-11-19
     * amendment), and gives up too. The board comes back, Bob posts, and alice's board hears it; once
     * the re-probe's gap since the failure has run the held DM is re-aired and lands, its ✓✓ following. Which re-air it is
     * stays open, as in `LoraDirectLabTest`: the hearing's resend when the gap had run by the time Bob's post was heard,
     * else the re-probe round once the clock passes it. Either way the first DM follows behind its board ack (#343).
     */
    @Test
    @Suppress("LongMethod") // one scenario: two give-ups, the hearing, the re-air and the older DM behind its ack
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
                    .loraDirectPastHold
                    .toInt()
            }
            lab.await(2) {
                alice.metrics
                    .lora()
                    .loraDirectUnreachable
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
            val resent = "lora resend $newest -> ${bob.nodeId}"
            val reprobed = "lora reprobe $newest -> ${bob.nodeId}"
            assertTrue(
                "the held DM was re-aired by neither the hearing nor the re-probe: ${alice.loraLog}",
                lab.tryAwait(1) {
                    if (alice.loraLog.any { it.startsWith(resent) || it.startsWith(reprobed) }) 1 else 0
                },
            )
            // On the re-probe's branch, the one #343 changed, the probe names the older DM it carries behind its ack.
            assertTrue(
                "the re-probe carried no backlog: ${alice.loraLog}",
                alice.loraLog.none { it.startsWith(reprobed) } ||
                    alice.loraLog.any { it == "$reprobed (+1 after its ack)" },
            )
            assertEquals(DeliveryPlane.LoRa, bob.receivedVia(bob.dmWith(alice), newest))
            assertEquals(DeliveryPlane.LoRa, lab.awaitReceipt(alice, newest, bob))
            // The older one rides behind the newest's board ack in the same round, whichever re-air it was (#343): no
            // second, doubled gap, so the clock stays where it is.
            val older = alice.ownMessageId(alice.dmWith(bob), "are you there")
            lab.await(1) { holds(bob, alice, older) }
            assertEquals(DeliveryPlane.LoRa, bob.receivedVia(bob.dmWith(alice), older))
            assertEquals(DeliveryPlane.LoRa, lab.awaitReceipt(alice, older, bob))
            converge(alice, bob, atLeast = 4)
        }

    /**
     * Path drift end to end (Q13, the design pass's oracle on the airings in order): bob's record of alice drifts to
     * UNKNOWN, silently. Alice's DM goes direct, bob's ack floods and the repeater re-airs it, and alice's board answers
     * the flooded ack with the direct PATH about 3 s later, which rewrites bob's record and pushes `0x81`. Bob's session
     * reads the record back on it — awaited on its counter — and writes nothing (ADR 2026-11.gsqu's 2026-11-19 amendment:
     * the firmware's route is kept); then the next DM goes, it and its ack both direct. Bob holds his ✓✓ for the field's
     * 45 s, so nothing of his own crosses the window.
     */
    @Test
    fun aDriftedRecordIsReadBackOnTheQ13PathAndTheNextDmGoesDirectBothWays() =
        runBlocking {
            val air = lab.meshcoreAir(channel = true)
            air.meshcoreEdit { repeater("relay", "alice", "bob") }
            val (alice, bob) = pair(air, bobLimits = fieldHold)
            val aliceBoard = checkNotNull(alice.meshcoreBoard)
            val bobBoard = checkNotNull(bob.meshcoreBoard)
            val bobSession = checkNotNull(bob.meshcoreSession)
            air.meshcoreEdit { bobBoard.driftPath(aliceBoard) }
            val routeReads = bobSession.counters.routeReads.get()
            val reads = readsOf(bob, aliceBoard.key)
            val pins = pinsOf(bob, aliceBoard.key)
            val t0 = air.now()

            assertTrue(alice.sendDm(bob, "after the drift"))
            lab.await((routeReads + 1).toInt()) {
                bobSession.counters.routeReads
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
     * next Ready's reconcile — read back from the board once the session's reconcile has run — and never written; Bob, whom
     * Knit taught, stays and still gets a DM over the air.
     */
    @Test
    fun aContactKnitDidNotTeachIsRemovedAtReadyAndNeverWritten() =
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
                    "routeReads=${ran.routeReads}\n${alice.loraLog.joinToString("\n")}",
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
            assertFalse(session.taughtAndRouted(foreign))
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
     * ADR 2026-11.f4d3 (the P9, 2026-11-18): each owner renames their board in another app — a second client on the board's
     * other interface, as the stock MeshCore app is. The name is the owner's handle, not a gate: alice's next Ready (a
     * restart) reconciles rather than dropping it, the names are never written over, and a DM crosses with its ✓✓ back over
     * the air.
     */
    @Test
    fun boardsRenamedInAnotherAppStayKnitsAndCarryADm() =
        runBlocking {
            val air = lab.meshcoreAir()
            val (alice, bob) = pair(air)
            bob.accept(bob.dmWith(alice))
            val aliceBoard = checkNotNull(alice.meshcoreBoard)
            val bobBoard = checkNotNull(bob.meshcoreBoard)

            suspend fun namesWritten() =
                (alice.meshcoreWritesEver() + bob.meshcoreWritesEver()).count { it[0].toInt() == MeshcoreProto.CMD_SET_ADVERT_NAME }
            val setups = namesWritten()
            air.meshcoreEdit {
                FakeForeignClient(aliceBoard).send(checkNotNull(MeshcoreProto.setAdvertName("Ana's radio")))
                FakeForeignClient(bobBoard).send(checkNotNull(MeshcoreProto.setAdvertName("Wren")))
            }

            alice.restart()
            val c = checkNotNull(alice.meshcoreSession).counters
            assertTrue(
                "the Ready never reconciled: skipped=${c.reconcilesSkipped} dropped=${c.reconcilesDropped}\n" +
                    alice.loraLog.joinToString("\n"),
                lab.tryAwait(1) { (c.reconciles.get() + c.reconcilesSkipped.get()).toInt() },
            )
            assertEquals("the reconcile was dropped: ${alice.loraLog}", 0L, c.reconcilesDropped.get())
            assertTrue(
                "the face never carried the new name",
                lab.tryAwait(1) { if (alice.meshcoreHandle()?.text == "Ana's radio") 1 else 0 },
            )

            assertTrue(alice.sendDm(bob, "under my own name"))
            val dm = alice.ownMessageId(alice.dmWith(bob), "under my own name")
            lab.await(1) { holds(bob, alice, dm) }
            assertEquals(DeliveryPlane.LoRa, bob.receivedVia(bob.dmWith(alice), dm))
            assertEquals("its ✓✓ came back over the board", DeliveryPlane.LoRa, lab.awaitReceipt(alice, dm, bob))
            assertEquals("Ana's radio", air.snapshot { aliceBoard.advertName.decodeToString() })
            assertEquals("Wren", air.snapshot { bobBoard.advertName.decodeToString() })
            assertEquals("Knit wrote a name since the setups' own", setups, namesWritten())
            converge(alice, bob, atLeast = 1)
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

    /**
     * ADR 2026-11.gsqu's 2026-11-19 amendment flips §5's flooded hearing: the firmware now routes a DM to a far board, so a
     * verified, fresh post a repeater carried lifts an Unreachable mark — and still counts no radio. Alice and Bob are out of
     * each other's range, a repeater beside both, Bob's board off: alice's DM goes up the ladder — zero hops, then one reset
     * and one flood — unanswered, and Bob is marked unreachable. His board back, his room post floods through the repeater
     * and lands at alice one hop on: its hearing lifts the mark (`lora direct heard`) with no radio counted. Past the
     * re-probe's gap the held DM goes up the ladder again — zero hops written first, then one flood — lands once, acked
     * through its PATH return, and its ✓✓ comes back over the board.
     */
    @Test
    fun aFloodedHearingLiftsAnUnreachableMarkAndTheLadderReachesTheFarBoard() =
        runBlocking {
            val air = lab.meshcoreAir()
            air.meshcoreEdit { repeater("relay", "alice", "bob") }
            val alice = lab.node("alice", quick, meshcore = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", quick.copy(meshcoreBackoffMs = 500L), meshcore = air).apply { setDisplayName("Bob") }
            lab.meetOverMeshcore(alice, bob)
            val plane = checkNotNull(alice.meshcore)
            val session = checkNotNull(alice.meshcoreSession)
            val bobBoard = checkNotNull(bob.meshcoreBoard)
            air.meshcoreEdit { bobBoard.powered = false }
            val unreachableBefore = alice.metrics.lora().loraDirectUnreachable
            val floods = session.counters.directFlooded.get()
            val resets = session.counters.routeResets.get()

            assertTrue(alice.sendDm(bob, "over the hill"))
            val dm = alice.ownMessageId(alice.dmWith(bob), "over the hill")
            lab.await((unreachableBefore + 1).toInt()) {
                alice.metrics
                    .lora()
                    .loraDirectUnreachable
                    .toInt()
            }
            lab.await(1) { unreachable(alice) }
            assertEquals("one flood in the flight", floods + 1, session.counters.directFlooded.get())
            assertEquals("after one reset", resets + 1, session.counters.routeResets.get())

            air.meshcoreEdit { bobBoard.powered = true }
            bob.awaitMeshcoreBack()
            val judged = alice.metrics.lora().loraHearingsJudged
            assertTrue(bob.sendRoom("from beyond the hill"))
            assertTrue(
                "a flooded post lifted nothing: ${alice.loraLog}",
                lab.tryAwait(1) { alice.loraLog.count { it == "lora direct heard ${bob.nodeId}" } },
            )
            // Judged after its effects, the status published among them: the mark is read past it.
            lab.await((judged + 1).toInt()) {
                alice.metrics
                    .lora()
                    .loraHearingsJudged
                    .toInt()
            }
            assertEquals("the flooded hearing lifted the mark", 0, unreachable(alice))
            assertEquals("and counted no radio: it came through the repeater", 0, plane.status.value.boardsHeard)

            lab.clock.advance(LoraReprobe.BASE_MS)
            lab.await(1) { holds(bob, alice, dm) }
            assertTrue(
                "bob's board never acked it: ${alice.loraLog}",
                lab.tryAwait(1) { alice.loraLog.count { it == "lora direct acked $dm -> ${bob.nodeId}" } },
            )
            assertEquals(DeliveryPlane.LoRa, bob.receivedVia(bob.dmWith(alice), dm))
            assertEquals("its ✓✓ came back over the board", DeliveryPlane.LoRa, lab.awaitReceipt(alice, dm, bob))
            assertEquals("it landed once", 1, holds(bob, alice, dm))
            converge(alice, bob, atLeast = 3)
        }

    /** [node]'s board's record of [peer]'s, as the board holds it now: its raw `out_path_len` and its path. */
    private suspend fun recordOf(
        air: FakeMeshcoreAir,
        node: LabNode,
        peer: LabNode,
    ): Pair<Int, List<Byte>>? {
        val board = checkNotNull(node.meshcoreBoard)
        val key = checkNotNull(peer.meshcoreBoard).key
        return air.snapshot { board.contact(key)?.let { it.outPathLen to it.outPath.toList() } }
    }

    private fun flooded(node: LabNode): Long =
        checkNotNull(node.meshcoreSession)
            .counters.directFlooded
            .get()

    private fun acked(
        node: LabNode,
        dm: String,
        to: LabNode,
    ): Int = node.loraLog.count { it == "lora direct acked $dm -> ${to.nodeId}" }

    /**
     * ADR 2026-11.gsqu's 2026-11-19 amendment, the chain on the channel air: alice – r1 – r2 – bob, the boards out of each
     * other's range. Alice's first DM goes zero hops, unanswered; the path is reset and the one flood is acked through bob's
     * PATH return, so both boards hold a two-hop route (bob's from alice's reciprocal PATH). The next DM and bob's reply go
     * direct both ways with no flood. Then r2 goes off: alice's DM down the dead route, zero hops and one flood all go
     * unanswered and bob is marked; r2 back, bob's post floods to alice two hops on and lifts the mark, and past the
     * re-probe's gap the held DM climbs the ladder again and a new route is learned — the same chain, the only path a chain has.
     */
    @Test
    @Suppress("LongMethod", "CyclomaticComplexMethod") // one scenario: the first flood, the learned route, the broken one, again
    fun aDmClimbsTheLadderAcrossAChainLearnsItsRouteAndLearnsItAgainAfterABreak() =
        runBlocking {
            val air = lab.meshcoreAir(channel = true)
            air.meshcoreEdit {
                repeater("r1", "alice")
                repeater("r2", "r1", "bob")
            }
            val alice = lab.node("alice", quick, meshcore = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", fieldHold.copy(meshcoreBackoffMs = 500L), meshcore = air).apply { setDisplayName("Bob") }
            lab.meetOverMeshcore(alice, bob)
            val aliceSession = checkNotNull(alice.meshcoreSession)
            val resets = aliceSession.counters.routeResets.get()
            val floods = flooded(alice)

            // 1. Zero hops, unanswered; the reset; the one flood, acked through bob's PATH return.
            assertTrue(alice.sendDm(bob, "across the valley"))
            val first = alice.ownMessageId(alice.dmWith(bob), "across the valley")
            assertTrue("never acked: ${alice.loraLog}", lab.tryAwait(1) { acked(alice, first, bob) })
            lab.await(1) { holds(bob, alice, first) }
            assertEquals("one reset", resets + 1, aliceSession.counters.routeResets.get())
            assertEquals("one flood", floods + 1, flooded(alice))

            // 2. Both boards hold the two-hop route: the next DM and bob's reply go direct, no flood either way.
            assertTrue(
                "alice's board never learned the route: ${recordOf(air, alice, bob)}",
                lab.tryAwait(1) { if (recordOf(air, alice, bob)?.first == 2) 1 else 0 },
            )
            assertTrue(
                "bob's board never took the reciprocal PATH: ${recordOf(air, bob, alice)}",
                lab.tryAwait(1) { if (recordOf(air, bob, alice)?.first == 2) 1 else 0 },
            )
            val bobFloods = flooded(bob)
            val t1 = air.now()
            assertTrue(alice.sendDm(bob, "along the route"))
            val second = alice.ownMessageId(alice.dmWith(bob), "along the route")
            assertTrue("never acked: ${alice.loraLog}", lab.tryAwait(1) { acked(alice, second, bob) })
            assertTrue(bob.sendDm(alice, "and back"))
            val reply = bob.ownMessageId(bob.dmWith(alice), "and back")
            assertTrue("never acked: ${bob.loraLog}", lab.tryAwait(1) { acked(bob, reply, alice) })
            lab.await(1) { holds(alice, bob, reply) }
            // Alice's ✓✓ of the reply is a DM of hers too: landed and acked by bob's board before the break, so the broken
            // flight below is her only one (the sweep's find: a ✓✓ whose board ack the break cut climbed its own ladder).
            assertEquals(DeliveryPlane.LoRa, lab.awaitReceipt(bob, reply, alice))
            lab.await(1) {
                val lora = alice.metrics.lora()
                if (lora.loraDirectSent > 0 && lora.loraDirectAcked >= lora.loraDirectSent) 1 else 0
            }
            assertEquals("no flood from alice", floods + 1, flooded(alice))
            assertEquals("none from bob", bobFloods, flooded(bob))
            val direct = air.snapshot { air.airings.filter { it.atMs >= t1 && it.type == "msg" && it.from in setOf("alice", "bob") } }
            assertTrue("each went direct along two hops: $direct", direct.isNotEmpty() && direct.all { !it.flood && it.hops == 2 })

            // 3. r2 off: the dead route, zero hops and one flood, all unanswered.
            air.meshcoreEdit { power("r2", on = false) }
            val unreachableBefore = alice.metrics.lora().loraDirectUnreachable
            val floodsBefore = flooded(alice)
            assertTrue(alice.sendDm(bob, "is the valley open"))
            val third = alice.ownMessageId(alice.dmWith(bob), "is the valley open")
            lab.await((unreachableBefore + 1).toInt()) {
                alice.metrics
                    .lora()
                    .loraDirectUnreachable
                    .toInt()
            }
            assertEquals(
                "one flood in the broken flight: ${alice.loraLog.filter { "direct" in it || "route" in it || "flood" in it }}",
                floodsBefore + 1,
                flooded(alice),
            )
            assertEquals(0, holds(bob, alice, third))

            // r2 back: bob's post reaches alice two hops on, lifts the mark, and past the gap a route is learned again.
            air.meshcoreEdit { power("r2", on = true) }
            assertTrue(bob.sendRoom("the valley is open"))
            assertTrue(
                "the far post lifted nothing: ${alice.loraLog}",
                lab.tryAwait(1) { alice.loraLog.count { it == "lora direct heard ${bob.nodeId}" } },
            )
            // lab-lint: allow clock-jump-over-48h — `LoraReprobe.BASE_MS` is 15 min; with the 46-s hold the jumps stay far under 48 h
            lab.clock.advance(LoraReprobe.BASE_MS)
            lab.await(1) { holds(bob, alice, third) }
            assertTrue("never acked: ${alice.loraLog}", lab.tryAwait(1) { acked(alice, third, bob) })
            assertTrue(
                "no route learned again: ${recordOf(air, alice, bob)}",
                lab.tryAwait(1) { if (recordOf(air, alice, bob)?.first == 2) 1 else 0 },
            )

            // 4. Bob's ✓✓s are in the field's hold: past it, over the link, the heal flushes them.
            lab.link(alice, bob)
            lab.clock.advance(46_000L) // past `DmAckCoalescer.HOLD_MS`, 45 s
            bob.heal()
            lab.assertConverged(listOf(alice, bob), atLeast = 4) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * The user's decision (b) on the channel air: a neighbour never pays a network flood once it is back. Alice and Bob in
     * range, Bob's board off: alice's DM goes zero hops, then one reset and one flood, unanswered — the most one flight
     * floods — and Bob is marked. His board back, still in range, his post is heard at zero hops; past the re-probe's gap the
     * held DM goes up the ladder from the reset route, zero hops written first, and lands there: no second flood.
     */
    @Test
    fun aDeadNeighbourCostsOneFloodAndBackInRangeIsReachedAtZeroHops() =
        runBlocking {
            val air = lab.meshcoreAir(channel = true)
            val (alice, bob) = pair(air, bobLimits = fieldHold.copy(meshcoreBackoffMs = 500L))
            val aliceSession = checkNotNull(alice.meshcoreSession)
            val bobBoard = checkNotNull(bob.meshcoreBoard)
            air.meshcoreEdit { bobBoard.powered = false }
            val unreachableBefore = alice.metrics.lora().loraDirectUnreachable
            val floods = flooded(alice)
            val resets = aliceSession.counters.routeResets.get()

            assertTrue(alice.sendDm(bob, "anyone home"))
            val dm = alice.ownMessageId(alice.dmWith(bob), "anyone home")
            lab.await((unreachableBefore + 1).toInt()) {
                alice.metrics
                    .lora()
                    .loraDirectUnreachable
                    .toInt()
            }
            assertEquals("at most one flood a flight", floods + 1, flooded(alice))
            assertEquals("after one reset", resets + 1, aliceSession.counters.routeResets.get())
            assertEquals("the record reset", MeshcoreProto.PATH_UNKNOWN, recordOf(air, alice, bob)?.first)

            air.meshcoreEdit { bobBoard.powered = true }
            bob.awaitMeshcoreBack()
            assertTrue(bob.sendRoom("home again"))
            assertTrue(
                "bob's post was no hearing: ${alice.loraLog}",
                lab.tryAwait(1) { alice.loraLog.count { it == "lora direct heard ${bob.nodeId}" } },
            )
            val zeroWrites = aliceSession.counters.zeroHopWrites.get()
            // lab-lint: allow clock-jump-over-48h — `LoraReprobe.BASE_MS` is 15 min; with the 46-s hold the jumps stay far under 48 h
            lab.clock.advance(LoraReprobe.BASE_MS)
            lab.await(1) { holds(bob, alice, dm) }
            assertTrue("never acked: ${alice.loraLog}", lab.tryAwait(1) { acked(alice, dm, bob) })
            assertEquals("no flood once bob was back in range", floods + 1, flooded(alice))
            assertTrue("zero hops written before the attempt", aliceSession.counters.zeroHopWrites.get() > zeroWrites)
            assertEquals("the record back at zero hops", 0, recordOf(air, alice, bob)?.first)
            assertEquals("it landed once", 1, holds(bob, alice, dm))

            lab.link(alice, bob)
            lab.clock.advance(46_000L) // past `DmAckCoalescer.HOLD_MS`, 45 s
            bob.heal()
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * A diamond on the channel air, alice – {r1, r2} – bob, the boards out of each other's range and the repeaters in each
     * other's (a hidden pair collides at bob: see the topology). The first DM's flood
     * teaches both boards a route. Then the records split, as a stale route leaves them: alice's runs through r2, bob's
     * through r1, and r1 goes off — one leg one-way. Alice's DM lands at bob through r2, but bob's ack, sent along r1, is
     * lost: her ladder writes zero hops, then resets and floods once, and bob's PATH return teaches her the r2 route while
     * her reciprocal PATH teaches him the same — one ladder heals both directions. Bob's reply then goes direct through r2,
     * no flood of his own.
     */
    @Test
    @Suppress("LongMethod") // one scenario: the first route, the split, the healing flight and the reply that proves it
    fun oneLadderHealsBothDirectionsOfADiamondWhoseLegWentOneWay() =
        runBlocking {
            val air = lab.meshcoreAir(channel = true)
            air.meshcoreEdit {
                repeater("r1", "alice", "bob")
                repeater("r2", "alice", "bob")
                // Within earshot of each other, so their copies of a flood queue behind each other at bob rather than collide
                // there: a hidden pair re-airing inside one airtime loses both copies (the first run's find, a field fact).
                link("r1", "r2")
            }
            val alice = lab.node("alice", quick, meshcore = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", fieldHold, meshcore = air).apply { setDisplayName("Bob") }
            lab.meetOverMeshcore(alice, bob)
            val aliceBoard = checkNotNull(alice.meshcoreBoard)
            val bobBoard = checkNotNull(bob.meshcoreBoard)

            assertTrue(alice.sendDm(bob, "which way"))
            val first = alice.ownMessageId(alice.dmWith(bob), "which way")
            assertTrue("never acked: ${alice.loraLog}", lab.tryAwait(1) { acked(alice, first, bob) })
            assertTrue(
                "bob's board never took a route: ${recordOf(air, bob, alice)}",
                lab.tryAwait(1) { if (recordOf(air, bob, alice)?.first.let { it != null && it in 1..2 }) 1 else 0 },
            )

            // The split: alice's record through r2, bob's through r1 (another client's write, or a PATH lost on the way), r1 off.
            // Every ring has forgotten the first exchange (160 newer packets): a reciprocal PATH along the route the first one
            // took is byte-identical to it (AES-ECB, no nonce), and a repeater or board still holding that one drops it as
            // heard — the chaos sweep's find (seed 736974568), owed to a board, and why bob's own ladder is the backstop.
            air.meshcoreEdit {
                aliceBoard.driftPath(bobBoard, "r2")
                bobBoard.driftPath(aliceBoard, "r1")
                power("r1", on = false)
                listOf("alice", "bob", "r2").forEach(::evictRing)
            }
            val floods = flooded(alice)
            val bobFloods = flooded(bob)
            val duplicates = bob.metrics.lora().loraDirectDuplicates
            assertTrue(alice.sendDm(bob, "the long way round"))
            val dm = alice.ownMessageId(alice.dmWith(bob), "the long way round")
            assertTrue("never acked: ${alice.loraLog}", lab.tryAwait(1) { acked(alice, dm, bob) })
            lab.await(1) { holds(bob, alice, dm) }
            // Two copies reached bob — attempt 0 through r2, its ack lost on r1, then the flood — the second absorbed.
            lab.await((duplicates + 1).toInt()) {
                bob.metrics
                    .lora()
                    .loraDirectDuplicates
                    .toInt()
            }
            assertEquals("one flood healed it", floods + 1, flooded(alice))
            val r2 = air.snapshot { air.hashOf("r2").toList() }
            assertTrue(
                "bob's board never took the r2 route from the reciprocal PATH: ${recordOf(air, bob, alice)}",
                lab.tryAwait(1) { if (recordOf(air, bob, alice) == (1 to r2)) 1 else 0 },
            )
            assertEquals("alice's board through r2", 1 to r2, recordOf(air, alice, bob))

            assertTrue(bob.sendDm(alice, "the long way back"))
            val reply = bob.ownMessageId(bob.dmWith(alice), "the long way back")
            assertTrue("never acked: ${bob.loraLog}", lab.tryAwait(1) { acked(bob, reply, alice) })
            lab.await(1) { holds(alice, bob, reply) }
            assertEquals("bob's reply never flooded", bobFloods, flooded(bob))

            lab.link(alice, bob)
            lab.clock.advance(46_000L) // past `DmAckCoalescer.HOLD_MS`, 45 s
            bob.heal()
            lab.assertConverged(listOf(alice, bob), atLeast = 3) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * lora NB2 and security's asymmetric pair (ADR 2026-11.gsqu's 2026-11-19 amendment): Bob's posts reach alice through a
     * repeater, but nothing of hers reaches it. Her first DM climbs zero hops twice and one flood, unanswered: Bob is marked.
     * His first far post lifts the mark (the one `lora direct heard`); her next DM, no longer held, climbs and fails again,
     * and Bob is marked again. His next four posts, all inside `FRESH_MS` and the re-probe's gap, lift nothing: a far
     * hearing lifts a mark at most once a gap. So the ladder's flash writes stay at the two flights' own (a reset, then a
     * zero-hop write and a reset) and the floods at two, whatever Bob posts.
     */
    @Test
    @Suppress("LongMethod") // one scenario: the give-up, the one far lift, the second give-up, four posts that lift nothing
    fun farPostsFromAnAsymmetricContactLiftItsMarkOnceAGapAndBoundTheLadder() =
        runBlocking {
            val air = lab.meshcoreAir()
            air.meshcoreEdit { repeater("relay", "alice", "bob") }
            val alice = lab.node("alice", quick, meshcore = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", quick, meshcore = air).apply { setDisplayName("Bob") }
            lab.meetOverMeshcore(alice, bob)
            air.meshcoreEdit { lossy("alice", "relay") }
            val c = checkNotNull(alice.meshcoreSession).counters
            val writes = c.zeroHopWrites.get() + c.routeResets.get()
            val floods = flooded(alice)
            val unreachableBefore = alice.metrics.lora().loraDirectUnreachable
            val resent = alice.metrics.lora().loraDirectResent

            suspend fun awaitMarked(times: Long) =
                lab.await((unreachableBefore + times).toInt()) {
                    alice.metrics
                        .lora()
                        .loraDirectUnreachable
                        .toInt()
                }

            suspend fun post(text: String) {
                val judged = alice.metrics.lora().loraHearingsJudged
                assertTrue(bob.sendRoom(text))
                lab.await((judged + 1).toInt()) {
                    alice.metrics
                        .lora()
                        .loraHearingsJudged
                        .toInt()
                }
            }

            assertTrue(alice.sendDm(bob, "can you hear me"))
            awaitMarked(1)
            lab.await(1) { unreachable(alice) }
            post("post 1")
            assertTrue("the first far post lifted nothing: ${alice.loraLog}", lab.tryAwait(1) { if (unreachable(alice) == 0) 1 else 0 })
            assertTrue(alice.sendDm(bob, "and now"))
            awaitMarked(2)
            lab.await(1) { unreachable(alice) }
            (2..5).forEach { post("post $it") }
            assertEquals("one far lift in the gap: ${alice.loraLog}", 1, alice.loraLog.count { it == "lora direct heard ${bob.nodeId}" })
            assertEquals("the mark stands", 1, unreachable(alice))
            assertEquals("nothing re-aired on a far post inside the gap", resent, alice.metrics.lora().loraDirectResent)
            assertEquals("two flights' ladder writes, no more", writes + 3, c.zeroHopWrites.get() + c.routeResets.get())
            assertEquals("two floods, no more", floods + 2, flooded(alice))

            air.meshcoreEdit { lossless("alice", "relay") }
            converge(alice, bob, atLeast = 2)
        }

    /**
     * security NB2: a contact blocked mid-flight is no longer taught, and the ladder floods nothing to it. Bob's board off, in
     * range: alice's DM goes zero hops, unanswered; she blocks Bob before the flood rung, and her session ends the flight with
     * no outcome (`meshcore direct flight ended: no longer taught`, or the core's own refusal of the flood): no reset, no flood.
     */
    @Test
    fun aContactBlockedMidFlightIsNeverFlooded() =
        runBlocking {
            val air = lab.meshcoreAir(channel = true)
            val (alice, bob) = pair(air)
            val bobBoard = checkNotNull(bob.meshcoreBoard)
            val c = checkNotNull(alice.meshcoreSession).counters
            air.meshcoreEdit { bobBoard.powered = false }
            val floods = flooded(alice)
            val resets = c.routeResets.get()
            val writes = alice.meshcoreWrites().count { it[0].toInt() == MeshcoreProto.CMD_SEND_TXT_MSG }

            assertTrue(alice.sendDm(bob, "before the block"))
            lab.await(writes + 1) { alice.meshcoreWrites().count { it[0].toInt() == MeshcoreProto.CMD_SEND_TXT_MSG } }
            alice.block(bob)
            assertTrue(
                "the flight never ended for the block: ${alice.loraLog}",
                lab.tryAwait(1) {
                    alice.loraLog.count {
                        it == "session: meshcore direct flight ended: no longer taught" ||
                            it == "session: meshcore direct flood skipped: no longer wanted"
                    }
                },
            )
            assertEquals("nothing flooded to a blocked contact", floods, flooded(alice))
            assertEquals("nor reset", resets, c.routeResets.get())

            alice.unblock(bob)
            air.meshcoreEdit { bobBoard.powered = true }
            converge(alice, bob, atLeast = 1)
        }

    /**
     * ADR 2026-11.gsqu §2: a doubled attempt draws one ✓✓. Bob's board's ack of alice's attempt 0 is lost (one-shot, that
     * pair and that attempt alone), so her session sends attempt 1 — zero hops again, the ladder's second zero-hop rung
     * (the user's decision of 2026-11-19: a lost ack costs no flood) — new ciphertext, which Bob's board delivers and acks
     * again. Bob's phone opens both: the second is absorbed (`loraDirectDuplicates`), one row, the id held for the ✓✓ once.
     * Both airings stay booked on alice's ledger (the third refunded on the ack: exactly two DIRECT_DM prices), and Bob's
     * plane booked its board's ack of each. Bob is on the field's 45-s hold, so nothing of his crosses the window; past it,
     * over the link, exactly one ✓✓ is sealed for alice.
     */
    @Test
    @Suppress("LongMethod") // one scenario: the lost ack, both copies at bob, the ledgers, then the flush and the oracle
    fun aDoubledAttemptIsAbsorbedAndDrawsOneTick() =
        runBlocking {
            val air = lab.meshcoreAir()
            val (alice, bob) = pair(air, bobLimits = fieldHold)
            val plane = checkNotNull(alice.meshcore)
            val aliceBoard = checkNotNull(alice.meshcoreBoard)
            val lost = AtomicInteger()
            air.meshcoreEdit {
                loseAck = { acker, sender, attempt ->
                    acker == "bob" && sender == "alice" && attempt == 0 && lost.getAndIncrement() == 0
                }
            }
            val duplicates = bob.metrics.lora().loraDirectDuplicates
            val acksBooked =
                bob.meshcoreHooks.counters.acksBooked
                    .get()
            val sealed = bob.metrics.receipts().receiptsSealed
            val used = plane.airtimeUsedMs()
            val writes = alice.meshcoreWrites().size

            assertTrue(alice.sendDm(bob, "say it twice"))
            val dm = alice.ownMessageId(alice.dmWith(bob), "say it twice")
            val delivered = "lora direct delivered $dm from ${alice.nodeId}"
            lab.await(2) { bob.loraLog.count { it == delivered } }
            air.meshcoreEdit { loseAck = { _, _, _ -> false } }
            assertEquals("one ack lost, and only one", 1, air.snapshot { lost.get() })
            lab.await((duplicates + 1).toInt()) {
                bob.metrics
                    .lora()
                    .loraDirectDuplicates
                    .toInt()
            }
            assertEquals("the second copy absorbed", duplicates + 1, bob.metrics.lora().loraDirectDuplicates)
            assertEquals("one row", 1, holds(bob, alice, dm))
            // One ✓✓ is read off what Bob seals after the flush (`receiptsSealed`), not off the hold here: the duplicate's
            // re-ack runs after the counter moves, so a read of the hold now could come before it (mesh-lab-reviewer, Stage 8).

            assertTrue(
                "bob's board never acked attempt 1: ${alice.loraLog}",
                lab.tryAwait(1) { alice.loraLog.count { it == "lora direct acked $dm -> ${bob.nodeId}" } },
            )
            val sends =
                alice.meshcoreWrites().drop(writes).filter {
                    it[0].toInt() == MeshcoreProto.CMD_SEND_TXT_MSG && it.copyOfRange(7, 13).contentEquals(boardPrefix(bob))
                }
            assertEquals("two attempts written: ${sends.map { it[2].toInt() }}", listOf(0, 1), sends.map { it[2].toInt() })
            val seal = checkNotNull(Cobs.decode(sends.first().copyOfRange(13, sends.first().size))) { "a send not in COBS" }.size
            val airing = air.snapshot { directAirMs(seal, aliceBoard.sf, aliceBoard.bwHz.toInt(), aliceBoard.cr) }
            assertEquals("both airings booked, the third refunded, no flood", 2 * airing, plane.airtimeUsedMs() - used)
            assertEquals(
                "bob's plane booked its board's ack of each",
                acksBooked + 2,
                bob.meshcoreHooks.counters.acksBooked
                    .get(),
            )

            // Bob's ✓✓ is still in the field's hold: past it, over the link, the heal flushes it. Both boards hold the link
            // first — the composite hands it to the plane on a coroutine of its own — or the flush's compact copy, offered
            // beside the signed tick, airs before the cover is known (3 of 100 seeds before this wait).
            lab.link(alice, bob)
            for ((node, peer) in listOf(alice to bob, bob to alice)) {
                lab.await(1) { if (peer.nodeId in checkNotNull(node.meshcore).linkIds) 1 else 0 }
            }
            lab.clock.advance(46_000L) // past `DmAckCoalescer.HOLD_MS`, 45 s
            bob.heal()
            assertTrue(
                "the ✓✓ came over the link, not the board: ${bob.loraLog}",
                lab.awaitReceipt(alice, dm, bob) != DeliveryPlane.LoRa,
            )
            lab.assertConverged(listOf(alice, bob), atLeast = 3) { it.dmWith(if (it === alice) bob else alice) }
            assertEquals("one ✓✓ sealed for it", sealed + 1, bob.metrics.receipts().receiptsSealed)
            assertEquals("one tick at alice", setOf(bob.nodeId), alice.receiptPlanes(dm).keys)
        }

    /**
     * ADR 2026-11.gsqu §2 and "what it costs": a receiving board that lacks the sender drops the DM unread and unanswered,
     * which looks exactly like an absent radio (`Mesh.cpp:147-191`). Alice is taken off Bob's board as another client's
     * remove would; each of her attempts — zero hops twice, then the ladder's one flood — is dropped there (the board's
     * `recv matches no peers`, counted by the fake) and no ack airs: Bob is unreachable at alice. The repair is Bob's
     * own teaching — his restart's Ready reconcile writes
     * her back — and then her re-probe past its gap: the DM lands once, acked by Bob's board, its ✓✓ back, and his board
     * drops nothing more.
     */
    @Test
    fun aReceiverMissingTheContactLandsTheDmAfterItsOwnTeach() =
        runBlocking {
            val air = lab.meshcoreAir()
            val (alice, bob) = pair(air)
            val aliceKey = checkNotNull(alice.meshcoreBoard).key
            val bobBoard = checkNotNull(bob.meshcoreBoard)
            air.meshcoreEdit { bobBoard.contacts.removeIf { it.key.contentEquals(aliceKey) } }
            val unreachableBefore = alice.metrics.lora().loraDirectUnreachable
            val dropped = air.snapshot { bobBoard.noPeerDrops }
            val acks = air.snapshot { bobBoard.acksAired.size }

            assertTrue(alice.sendDm(bob, "do you know me"))
            val dm = alice.ownMessageId(alice.dmWith(bob), "do you know me")
            lab.await((unreachableBefore + 1).toInt()) {
                alice.metrics
                    .lora()
                    .loraDirectUnreachable
                    .toInt()
            }
            lab.await(1) { unreachable(alice) }
            // Zero hops twice, then the ladder's one flood (ADR 2026-11.gsqu's 2026-11-19 amendment): all dropped there.
            assertEquals(
                "every attempt dropped at bob's board for want of the contact",
                dropped + MeshcoreSession.ATTEMPTS,
                air.snapshot { bobBoard.noPeerDrops },
            )
            assertEquals("and no ack aired", acks, air.snapshot { bobBoard.acksAired.size })
            assertEquals(0, holds(bob, alice, dm))

            bob.restart()
            lab.awaitTaught(bob, alice)
            val droppedAfterTeach = air.snapshot { bobBoard.noPeerDrops }
            lab.clock.advance(LoraReprobe.BASE_MS)
            lab.await(1) { holds(bob, alice, dm) }
            assertTrue(
                "the DM was re-aired by neither a resend nor the re-probe: ${alice.loraLog}",
                lab.tryAwait(1) {
                    // Exact: bob is sent one DM, so nothing rides behind the probe and a suffix would be a stray unacked DM.
                    val resend = "lora resend $dm -> ${bob.nodeId}"
                    if (alice.loraLog.any { it.startsWith(resend) || it == "lora reprobe $dm -> ${bob.nodeId}" }) 1 else 0
                },
            )
            assertTrue(
                "bob's board never acked it: ${alice.loraLog}",
                lab.tryAwait(1) { alice.loraLog.count { it == "lora direct acked $dm -> ${bob.nodeId}" } },
            )
            assertEquals("the taught board dropped nothing more", droppedAfterTeach, air.snapshot { bobBoard.noPeerDrops })
            assertEquals(DeliveryPlane.LoRa, bob.receivedVia(bob.dmWith(alice), dm))
            assertEquals("its ✓✓ came back over the board", DeliveryPlane.LoRa, lab.awaitReceipt(alice, dm, bob))
            assertEquals("it landed once", 1, holds(bob, alice, dm))
            converge(alice, bob, atLeast = 3)
        }

    /**
     * The 2026-11-19 field case (ADR 2026-10.x9fa's amendment of that day): Bob's ✓✓ for alice's DM is his only send, and
     * from then on nothing alice's board airs reaches his — the ✓✓ lands, its ack back never does, and his ladder gives it
     * up. The reply Bob typed while it was out was parked behind it; before, it went back to the source and waited for the
     * re-probe, 15 minutes on. Now it gets the next attempt the moment the ✓✓ fails, and lands at alice within one flight's
     * time of that failure.
     */
    @Test
    @Suppress("LongMethod") // one scenario: the DM, the lost ✓✓ flight, the reply parked behind it, its own attempt
    fun aReplyTypedDuringALostTicksFlightGoesTheMomentTheTickFailsNotAtTheReprobe() =
        runBlocking {
            val air = lab.meshcoreAir(channel = true)
            val (alice, bob) = pair(air, bobLimits = fieldHold)
            assertTrue(alice.sendDm(bob, "first"))
            val dm = alice.ownMessageId(alice.dmWith(bob), "first")
            lab.await(1) { holds(bob, alice, dm) }
            lab.await(1) { if (dm in bob.manager.dmAcks.pending(alice.nodeId)) 1 else 0 }

            // One way from here: Bob's board still reaches alice's, nothing of hers reaches his.
            air.meshcoreEdit { lossy("alice", "bob") }
            lab.clock.advance(46_000L) // past `DmAckCoalescer.HOLD_MS`, 45 s: the ✓✓ goes alone
            bob.heal()
            val tick = "lora tx direct:receipt:"
            lab.await(1) { bob.loraLog.count { it.startsWith(tick) } }
            val tickId =
                bob.loraLog
                    .first { it.startsWith(tick) }
                    .removePrefix(tick)
                    .substringBefore(' ')
            assertTrue(bob.sendDm(alice, "are you getting these"))
            val reply = bob.ownMessageId(bob.dmWith(alice), "are you getting these")

            val failed = "lora direct unreachable $tickId -> ${alice.nodeId}"
            lab.await(1) { bob.loraLog.count { it.startsWith(failed) } }
            val failedAt = lab.clock.now()
            assertTrue(
                "the reply was not parked behind the ✓✓: ${bob.loraLog}",
                lab.tryAwait(1) { bob.loraLog.count { it.startsWith("lora direct next $reply -> ${alice.nodeId}") } },
            )
            lab.await(1) { holds(alice, bob, reply) }
            val landedIn = lab.clock.now() - failedAt
            assertTrue("within one flight of the ✓✓'s failure: $landedIn ms", landedIn < MeshcoreSession.MAX_FLIGHT_MS)
            assertTrue("well inside the re-probe's gap", landedIn < LoraReprobe.BASE_MS)
            assertEquals(DeliveryPlane.LoRa, alice.receivedVia(alice.dmWith(bob), reply))
            assertEquals("bob reprobed nothing", 0L, bob.metrics.lora().loraReprobed)
            // The ride-only ✓✓ half — the lost ✓✓ riding Bob's next DM, or released behind its radio ack — is MeshManager's
            // and asserted with it.

            air.meshcoreEdit { lossless("alice", "bob") }
            lab.link(alice, bob)
            lab.clock.advance(46_000L) // past `DmAckCoalescer.HOLD_MS`: what either still holds flushes over the link
            listOf(alice, bob).forEach { it.heal() }
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * A board that is off, and a burst of five DMs typed at it: the first goes, every attempt of its ladder, and gives up;
     * of the four typed while it was out only the newest gets an attempt of its own, which gives up too. Two flights, never
     * five, and nothing more before the re-probe (ADR 2026-10.x9fa's 2026-11-19 amendment).
     */
    @Test
    fun aBurstOfFiveDmsAtADeadBoardCostsAtMostTwoFlights() =
        runBlocking {
            val air = lab.meshcoreAir()
            val (alice, bob) = pair(air)
            val board = checkNotNull(bob.meshcoreBoard)
            air.meshcoreEdit { board.powered = false }
            val sent = alice.metrics.lora().loraDirectSent
            val unreachable = alice.metrics.lora().loraDirectUnreachable

            val texts = (1..5).map { "dm $it" }
            texts.forEach { assertTrue(alice.sendDm(bob, it)) }
            lab.await((unreachable + 2).toInt()) {
                alice.metrics
                    .lora()
                    .loraDirectUnreachable
                    .toInt()
            }
            val newest = alice.ownMessageId(alice.dmWith(bob), texts.last())
            assertTrue(
                "the newest never took the turn: ${alice.loraLog}",
                alice.loraLog.any { it.startsWith("lora direct next $newest -> ${bob.nodeId}") },
            )
            // Whatever is still settling: no third flight comes of the burst.
            assertFalse(
                "a third flight: ${alice.loraLog}",
                lab.tryAwait((sent + 3).toInt(), timeoutMs = 3_000L) {
                    alice.metrics
                        .lora()
                        .loraDirectSent
                        .toInt()
                },
            )
            assertEquals("two flights, not five", sent + 2, alice.metrics.lora().loraDirectSent)

            air.meshcoreEdit { board.powered = true }
            converge(alice, bob, atLeast = 5)
        }

    /** The 6-byte prefix of [node]'s board key, as a `SEND_TXT_MSG` names its recipient. */
    private fun boardPrefix(node: LabNode): ByteArray = checkNotNull(node.meshcoreBoard).key.copyOf(MeshcoreProto.PREFIX_BYTES)

    /** One compact DM airing of [seal] bytes at the board's preset, as the plane prices it (`AirPurpose.DIRECT_DM`). */
    private fun directAirMs(
        seal: Int,
        sf: Int,
        bwHz: Int,
        cr: Int,
    ): Long =
        MeshcoreTraits.airMs(
            MeshcoreTraits.phyBytes(seal, MeshcoreTraits.price(AirPurpose.DIRECT_DM, SigningFacts(), false, false)),
            RadioParams(sf, bwHz, cr),
        )

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
