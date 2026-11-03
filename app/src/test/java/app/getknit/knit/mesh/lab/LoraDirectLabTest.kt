package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.crypto.MessageContent
import app.getknit.knit.mesh.lora.FakeMeshtasticAir
import app.getknit.knit.mesh.lora.LoraReprobe
import app.getknit.knit.mesh.lora.MeshtasticProto
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.Protocol
import app.getknit.knit.mesh.protocol.WireCodec
import io.mockk.clearMocks
import io.mockk.coVerify
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
import kotlin.random.Random

/**
 * Compact LoRa DMs end to end (ADR 2026-10.x9fa): real stacks, each with the real LoRa plane over one
 * [FakeMeshtasticAir] whose boards answer in lab time ([MeshLab.quickAir]) and with the real pair seal, offered by the
 * real manager and delivered by the real pipeline. A DM, its ✓✓ and a reaction cross as one pair-sealed unicast each;
 * the signed copy never rides the air and reaches the other phone only by a link or a relay, where its ratchet step
 * still runs (`lora_direct_seen`). So every scenario that wants custody parity ends on a link: custody does not
 * converge over LoRa alone, by design.
 *
 * The pair meet over a link first — a profile never rides the board, and the board map is built from the pinned
 * profile that names the board and claims the door — then part, and only the air is left.
 */
@RunWith(RobolectricTestRunner::class)
class LoraDirectLabTest {
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

    /** Links [nodes], waits until each board holder knows every other's board ([LabNode.knowsBoardOf]), then parts them. */
    private suspend fun meetThenPart(
        vararg nodes: LabNode,
        boarded: List<LabNode> = nodes.toList(),
    ) {
        val pairs = nodes.flatMapIndexed { i, a -> nodes.drop(i + 1).map { b -> a to b } }
        lab.linkAll(*pairs.toTypedArray())
        lab.awaitAcquainted(*nodes)
        for (a in boarded) {
            for (b in boarded) {
                if (a !== b) {
                    lab.await(1) { a.knowsBoardOf(b) }
                    lab.await(1) { a.boardReadyFor(b) }
                }
            }
        }
        lab.awaitCustodyParity(*nodes)
        pairs.forEach { (a, b) -> lab.unlink(a, b) }
    }

    private fun FakeMeshtasticAir.knit(node: LabNode): Int = knitAirings(node.boardNum)

    /** 1 once [node] has notified a message reading [body], else 0 — a `tryAwait` count over the notifier mock. */
    private fun notified(
        node: LabNode,
        body: String,
    ): Int =
        runCatching {
            coVerify(atLeast = 1) { node.notifier.notify(match { it.body == body }, any(), any(), any(), any()) }
            1
        }.getOrDefault(0)

    /** 1 once [node] holds [id] from [author] in their DM thread, else 0. */
    private suspend fun holds(
        node: LabNode,
        author: LabNode,
        id: String,
    ): Int = node.decrypted(node.dmWith(author)).count { it.first == id }

    /**
     * (a) A DM and its ✓✓ over LoRa, one Knit airing each way. Then a link: the signed copies cross, bob's ratchet takes
     * alice's (her X3DH init included) without rewriting or re-notifying the row, no skipped key is left for the index the
     * compact copy delivered, and the next DM, carried by the link alone, opens through the ratchet.
     */
    @Test
    fun aDmAndItsTickCrossAsOneUnicastEachAndTheSignedCopyStillRunsTheRatchet() =
        runBlocking {
            val air = MeshLab.quickAir()
            val alice = lab.node("alice", quick, air = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", quick, air = air).apply { setDisplayName("Bob") }
            meetThenPart(alice, bob)
            val (aliceBefore, bobBefore) = air.knit(alice) to air.knit(bob)
            // Accepted, so a delivery notifies through `notify` (a request's goes to the coalesced heads-up instead).
            bob.accept(bob.dmWith(alice))

            assertTrue(alice.sendDm(bob, "over the board"))
            val dm = alice.ownMessageId(alice.dmWith(bob), "over the board")
            lab.await(1) { holds(bob, alice, dm) }
            lab.await(1) { bob.loraLog.count { it == "lora direct delivered $dm from ${alice.nodeId}" } }
            coVerify(exactly = 1) { bob.notifier.notify(any(), any(), any(), any(), any()) }
            assertEquals(DeliveryPlane.LoRa, bob.receivedVia(bob.dmWith(alice), dm))
            assertEquals("its ✓✓ came back over the board", DeliveryPlane.LoRa, lab.awaitReceipt(alice, dm, bob))
            assertEquals("one Knit airing for the DM: ${alice.loraLog}", aliceBefore + 1, air.knit(alice))
            assertEquals("one for its ✓✓: ${bob.loraLog}", bobBefore + 1, air.knit(bob))
            assertEquals("no signed copy crossed the air", 0, bob.custodiedChatsFrom(alice, bob))

            clearMocks(bob.notifier, answers = false, recordedCalls = true, childMocks = false, exclusionRules = false)
            lab.link(alice, bob)
            assertTrue(
                "the signed copy's ratchet step never ran on bob",
                lab.tryAwait(1) { if (bob.loraSeen(dm)?.ratchetDone == true) 1 else 0 },
            )
            assertEquals("the compact copy's row stands", DeliveryPlane.LoRa, bob.receivedVia(bob.dmWith(alice), dm))

            assertTrue(alice.sendDm(bob, "and now over the link"))
            val next = alice.ownMessageId(alice.dmWith(bob), "and now over the link")
            lab.await(1) { holds(bob, alice, next) }
            // The mark lands inside the signed copy's delivery, before anything that would notify. The router handles one
            // frame at a time, in order, so once the next DM over the same link has notified, the signed copy's delivery
            // has returned.
            lab.await(1) { notified(bob, "and now over the link") }
            coVerify(exactly = 0) { bob.notifier.notify(match { it.body == "over the board" }, any(), any(), any(), any()) }
            assertTrue("bob's session is confirmed", lab.tryAwait(1) { if (bob.session(alice)?.confirmed == true) 1 else 0 })
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            assertEquals("no skipped key left behind", 0, bob.skippedDmKeys())
        }

    /**
     * (a′) Both initiate over LoRa: neither holds a session, each sends a first DM the other gets compact, and each one's
     * signed copy carries its own X3DH init. The link brings both inits to both ratchets, the race resolves as it always
     * does (the smaller node id wins, ADR 2026-09.pz9g), and the pair ends on one root.
     */
    @Test
    fun bothSidesOpenOverLoraAndTheLinkLeavesThemOnOneRoot() =
        runBlocking {
            val air = MeshLab.quickAir()
            val alice = lab.node("alice", quick, air = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", quick, air = air).apply { setDisplayName("Bob") }
            meetThenPart(alice, bob)
            assertFalse("no session yet on alice", alice.session(bob)?.hasSession == true)
            assertFalse("nor on bob", bob.session(alice)?.hasSession == true)

            assertTrue(alice.sendDm(bob, "from alice"))
            assertTrue(bob.sendDm(alice, "from bob"))
            val fromAlice = alice.ownMessageId(alice.dmWith(bob), "from alice")
            val fromBob = bob.ownMessageId(bob.dmWith(alice), "from bob")
            lab.await(1) { holds(bob, alice, fromAlice) }
            lab.await(1) { holds(alice, bob, fromBob) }
            assertEquals(DeliveryPlane.LoRa, bob.receivedVia(bob.dmWith(alice), fromAlice))
            assertEquals(DeliveryPlane.LoRa, alice.receivedVia(alice.dmWith(bob), fromBob))

            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            // Each signed copy's ratchet step must have run before the read: an answer that crossed ahead of it banks the
            // skipped key that copy then spends (boards chaos seed 2002).
            lab.await(1) { if (alice.loraSeen(fromBob)?.ratchetDone == true) 1 else 0 }
            lab.await(1) { if (bob.loraSeen(fromAlice)?.ratchetDone == true) 1 else 0 }
            assertEquals("no skipped key left behind", 0, alice.skippedDmKeys() + bob.skippedDmKeys())
        }

    /**
     * (b) Bob's board is off: alice's DM goes out once, every retry with it, and her board gives up — unreachable, and the
     * next DM is held, not aired. The board comes back and airs a room post; alice's board hears it, and once the re-probe's
     * gap since the failure has run the held DM is re-aired and lands, its ✓✓ following. Which of the two re-airs it is
     * left open on purpose: the hearing's resend when the gap had run by the time a packet of bob's was heard, else the
     * re-probe round once the clock passes it. Either is the plane working; the scenario asserts one of them aired it.
     */
    @Test
    fun aBoardThatWasOffIsHeardAgainAndTheHeldDmLands() =
        runBlocking {
            val air = MeshLab.quickAir()
            val alice = lab.node("alice", quick, air = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", quick, air = air).apply { setDisplayName("Bob") }
            meetThenPart(alice, bob)
            air.lossy = { from, to -> from == bob.boardNum || to == bob.boardNum }

            assertTrue(alice.sendDm(bob, "are you there"))
            lab.await(1) {
                alice.metrics
                    .lora()
                    .loraDirectUnreachable
                    .toInt()
            }
            assertEquals("the send and its four retries aired", FakeMeshtasticAir.UNICAST_ATTEMPTS, air.knit(alice))
            assertTrue(alice.sendDm(bob, "still there?"))
            lab.await(1) {
                alice.metrics
                    .lora()
                    .loraDirectHeld
                    .toInt()
            }
            assertEquals("the second is held, not aired", FakeMeshtasticAir.UNICAST_ATTEMPTS, air.knit(alice))

            air.lossy = { _, _ -> false }
            assertTrue(bob.sendRoom("back on the air"))
            assertTrue(
                "alice's board never heard bob's: ${alice.loraLog}",
                lab.tryAwait(1) {
                    alice.loraLog.count {
                        it ==
                            "lora direct heard ${bob.nodeId}"
                    }
                },
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

            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * (c) A recipient with no board, or whose profile names a board without claiming the door (DMs over LoRa switched
     * off), never gets a compact DM: nothing goes on the air, and the signed copies arrive once the link is back.
     */
    @Test
    fun noCompactDmToARecipientWithoutABoardOrWithoutTheCapability() =
        runBlocking {
            val air = MeshLab.quickAir()
            val alice = lab.node("alice", quick, air = air).apply { setDisplayName("Alice") }
            val carol = lab.node("carol", quick, board = false).apply { setDisplayName("Carol") } // no board in any run: the subject
            val dave = lab.node("dave", quick, air = air).apply { setDisplayName("Dave") }
            dave.settings.setLoraDmEnabled(false)
            lab.linkAll(alice to carol, alice to dave, carol to dave)
            lab.awaitAcquainted(alice, carol, dave)
            // Dave's switch went off at boot: the profile that names his board does not claim the door. Awaited while the
            // links stand, since a profile never rides the board.
            lab.await(1) {
                val row = alice.peer(dave)
                val claims = (row?.capabilities ?: 0L) and Protocol.CAP_LORA_DIRECT != 0L
                if (row?.loraNode == dave.boardNum.toLong() && !claims) 1 else 0
            }
            lab.awaitCustodyParity(alice, carol, dave)
            listOf(alice to carol, alice to dave, carol to dave).forEach { (a, b) -> lab.unlink(a, b) }
            val before = air.knit(alice)

            // The offer's gates run inside the send: by the time it returns, nothing for either was queued.
            assertTrue(alice.sendDm(carol, "no board"))
            assertTrue(alice.sendDm(dave, "no door"))
            // The synchronous signal: a DM the transport took for a door holder is held for its re-probe at once
            // (`LoraMeshTransport.offer`); the air counts below could otherwise be read before the pacer airs one.
            val held = checkNotNull(alice.lora).reprobeSchedule()
            assertTrue("a compact DM was taken: $held", held.none { it.recipientId == carol.nodeId || it.recipientId == dave.nodeId })
            assertEquals("nothing compact went on the air: ${alice.loraLog}", before, air.knit(alice))
            assertEquals(0L, alice.metrics.lora().loraDirectSent)

            lab.linkAll(alice to carol, alice to dave, carol to dave)
            lab.assertConverged(listOf(alice, carol), atLeast = 1, carriers = listOf(dave)) {
                it.dmWith(
                    if (it ===
                        alice
                    ) {
                        carol
                    } else {
                        alice
                    },
                )
            }
            lab.assertConverged(listOf(alice, dave), atLeast = 1, carriers = listOf(carol)) { it.dmWith(if (it === alice) dave else alice) }
            assertEquals(DeliveryPlane.Nearby, dave.receivedVia(dave.dmWith(alice), dave.decrypted(dave.dmWith(alice)).single().first))
        }

    /**
     * (d) Bob deletes a DM the compact copy delivered. Its signed copy then arrives over a link and the compact packet is
     * replayed off the air: the row stays gone, nothing notifies, and the ratchet still took the signed copy.
     */
    @Test
    fun aDeletedCompactDmStaysGoneWhenItsSignedCopyArrivesAndItsPacketReplays() =
        runBlocking {
            val air = MeshLab.quickAir()
            val alice = lab.node("alice", quick, air = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", quick, air = air).apply { setDisplayName("Bob") }
            meetThenPart(alice, bob)
            bob.accept(bob.dmWith(alice))

            val sentBefore = checkNotNull(alice.boardLink).sent.size
            assertTrue(alice.sendDm(bob, "read and delete"))
            val dm = alice.ownMessageId(alice.dmWith(bob), "read and delete")
            lab.await(1) { holds(bob, alice, dm) }
            lab.await(1) { bob.loraLog.count { it == "lora direct delivered $dm from ${alice.nodeId}" } }
            coVerify(exactly = 1) { bob.notifier.notify(any(), any(), any(), any(), any()) }
            lab.awaitReceipt(alice, dm, bob)
            val packet = checkNotNull(alice.boardLink).sent.drop(sentBefore).first { it[0] == LORA_DIRECT_TAG_BYTE }

            bob.deleteLocally(dm)
            alice.deleteLocally(dm)
            clearMocks(bob.notifier, answers = false, recordedCalls = true, childMocks = false, exclusionRules = false)
            lab.link(alice, bob)
            assertTrue("the signed copy's ratchet step never ran", lab.tryAwait(1) { if (bob.loraSeen(dm)?.ratchetDone == true) 1 else 0 })
            // As a 2.8 board replays it: PKI-decrypted, on index 0 (ADR 2026-10.x9fa's PKI amendment).
            checkNotNull(bob.boardLink).deliver(
                alice.boardNum,
                0,
                MeshtasticProto.PORT_PRIVATE_APP,
                packet,
                to = bob.boardNum,
                wantAck = true,
                pkiEncrypted = true,
            )
            // The replay's delivery has returned, not merely opened: only then can "nothing notifies" mean anything.
            lab.await(2) { bob.loraLog.count { it == "lora direct delivered $dm from ${alice.nodeId}" } }
            assertNull("the row stays gone", bob.row(dm))
            coVerify(exactly = 0) { bob.notifier.notify(any(), any(), any(), any(), any()) }

            assertTrue(alice.sendDm(bob, "after the delete"))
            assertTrue(bob.sendDm(alice, "seen it"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * (e) A burst of fifteen to a board nobody has answered yet: one goes, the rest park behind it (at most
     * `LoraDirectPolicy.MAX_PARKED_PER_PEER`, the oldest dropped back to the source) and follow its ack. Every ✓✓ for what
     * crossed comes back in compact ticks of one packet each, at most `LORA_DIRECT_TICK_ACKS` (9) ids apiece, never one too
     * long for the packet. The link then brings the rest, and with nothing unacked left the re-probe lets bob go without airing.
     */
    @Test
    fun aBurstOfFifteenIsTickedInOnePacketTicksAndTheResendsStop() =
        runBlocking {
            val air = MeshLab.quickAir()
            val alice = lab.node("alice", quick, air = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", quick, air = air).apply { setDisplayName("Bob") }
            meetThenPart(alice, bob)

            repeat(BURST) { assertTrue(alice.sendDm(bob, "burst $it")) }
            // The one in flight and every one parked behind it: what crosses without a re-probe.
            lab.await(CROSSES_AT_ONCE) { bob.decrypted(bob.dmWith(alice)).size }
            val crossed = bob.decrypted(bob.dmWith(alice)).map { it.first }
            assertTrue(
                "the ✓✓ for what crossed never all came back over the board",
                lab.tryAwait(1) { if (crossed.all { alice.receiptPlanes(it)[bob.nodeId] == DeliveryPlane.LoRa }) 1 else 0 },
            )
            val ticks = bob.loraLog.filter { it.startsWith("lora tx direct:receipt:") }
            assertTrue(
                "$CROSSES_AT_ONCE ids in ticks of at most LORA_DIRECT_TICK_ACKS (9): $ticks",
                ticks.size >= 1 && ticks.all { it.endsWith("parts=1") },
            )
            assertEquals("no tick past one packet", 0L, bob.metrics.lora().loraDirectTooLong)

            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = BURST) { it.dmWith(if (it === alice) bob else alice) }
            lab.unlink(alice, bob)
            val settled = air.knit(alice)

            // The re-probe's next round finds nothing unacked and drops bob's schedule, airing nothing.
            lab.clock.advance(LoraReprobe.BASE_MS)
            val board = checkNotNull(alice.lora)
            assertTrue(
                "the re-probe never let bob go: ${board.reprobeSchedule()}",
                lab.tryAwait(1) { if (board.reprobeSchedule().none { it.recipientId == bob.nodeId }) 1 else 0 },
            )
            assertEquals("nothing re-aired once every ✓✓ landed: ${alice.loraLog.takeLast(10)}", settled, air.knit(alice))
        }

    /**
     * (f) A DM with a picture, small enough to ride compact: whichever copy wins, the row carries the sealed reference
     * and the bytes come by the link — a board carries a frame, never its bytes.
     */
    @Test
    fun aDmWithAPictureArrivesWithItsPictureWhicheverCopyWins() =
        runBlocking {
            val air = MeshLab.quickAir()
            val alice = lab.node("alice", quick, air = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", quick, air = air).apply { setDisplayName("Bob") }
            meetThenPart(alice, bob)

            val picture = Random(11).nextBytes(2_048)
            assertTrue(alice.sendImage(picture, "", to = bob))
            val dm = alice.decrypted(alice.dmWith(bob)).single().first
            lab.await(1) { holds(bob, alice, dm) }
            assertEquals("the compact copy won", DeliveryPlane.LoRa, bob.receivedVia(bob.dmWith(alice), dm))
            assertFalse("a board carries no bytes", bob.attachmentHeld(bob.dmWith(alice), dm))

            lab.link(alice, bob)
            assertTrue("the picture never came by the link", lab.tryAwait(1) { if (bob.attachmentHeld(bob.dmWith(alice), dm)) 1 else 0 })
            assertTrue(picture.contentEquals(bob.attachmentPlain(bob.dmWith(alice), dm)))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * (g) Bob's phone is away from his board: the board acks alice's DM, the phone never reads it. The ack says nothing of
     * the phone, so the re-probe re-airs the DM a gap later; bob's phone back on its board takes that copy, and its ✓✓
     * ends the schedule.
     */
    @Test
    fun aPhoneAwayFromItsBoardStillGetsTheDmOnTheReprobe() =
        runBlocking {
            val air = MeshLab.quickAir()
            val alice = lab.node("alice", quick, air = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", quick, air = air).apply { setDisplayName("Bob") }
            meetThenPart(alice, bob)
            val bobBoard = checkNotNull(bob.boardLink)
            bobBoard.drop()

            assertTrue(alice.sendDm(bob, "while you were away"))
            val dm = alice.ownMessageId(alice.dmWith(bob), "while you were away")
            lab.await(1) {
                alice.metrics
                    .lora()
                    .loraDirectAcked
                    .toInt()
            }
            assertEquals("acked by the board, read by nobody", 0, holds(bob, alice, dm))

            bobBoard.readyProvisioned(knitIndex = MeshLab.KNIT_SLOT)
            lab.clock.advance(LoraReprobe.BASE_MS)
            // The re-probe round re-aired it: the board's ack ended no schedule, and nothing of bob's was missed to hear.
            lab.await(1) { alice.loraLog.count { it == "lora reprobe $dm -> ${bob.nodeId}" } }
            lab.await(1) { holds(bob, alice, dm) }
            assertEquals(DeliveryPlane.LoRa, lab.awaitReceipt(alice, dm, bob))

            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * (h) An asymmetric link — alice reaches bob, bob never reaches alice. The DM lands once, its ACK is lost, and both
     * boards give up after their retries. Each re-probe round costs at most one send and its retries each way, and the
     * rounds back off: the air stays bounded however long the pair stays like this.
     */
    @Test
    fun anAsymmetricLinkKeepsTheAiringsBounded() =
        runBlocking {
            val air = MeshLab.quickAir()
            val alice = lab.node("alice", quick, air = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", quick, air = air).apply { setDisplayName("Bob") }
            meetThenPart(alice, bob)
            air.lossy = { from, _ -> from == bob.boardNum }

            assertTrue(alice.sendDm(bob, "one way"))
            val dm = alice.ownMessageId(alice.dmWith(bob), "one way")
            lab.await(1) { holds(bob, alice, dm) }
            lab.await(1) {
                alice.metrics
                    .lora()
                    .loraDirectUnreachable
                    .toInt()
            }
            assertEquals("the phone saw it once", 1, bob.loraLog.count { it == "lora rx direct $dm from ${alice.nodeId}" })

            // Two rounds of the backoff, 15 min and then 30 more, each awaited to its give-up.
            for ((round, step) in listOf(LoraReprobe.BASE_MS, 2 * LoraReprobe.BASE_MS).withIndex()) {
                lab.clock.advance(step)
                lab.await(round + 1) {
                    alice.metrics
                        .lora()
                        .loraReprobed
                        .toInt()
                }
                lab.await(round + 2) {
                    alice.metrics
                        .lora()
                        .loraDirectUnreachable
                        .toInt()
                }
            }
            val rounds = 3
            val perRound = FakeMeshtasticAir.UNICAST_ATTEMPTS
            assertTrue(
                "alice aired ${air.knit(alice)} in $rounds rounds: ${alice.loraLog.takeLast(15)}",
                air.knit(alice) <= rounds * perRound,
            )
            assertTrue("bob aired ${air.knit(bob)}: ${bob.loraLog.takeLast(15)}", air.knit(bob) <= rounds * perRound)

            air.lossy = { _, _ -> false }
            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * (i) A quiet far board: off for alice's DM, then back but never airing a thing of its own. Nothing is heard, so
     * only the re-probe can ask again — and it does, a gap after the failure, and the DM lands.
     */
    @Test
    fun aQuietBoardThatIsNeverHeardIsReachedByTheReprobe() =
        runBlocking {
            val air = MeshLab.quickAir()
            val alice = lab.node("alice", quick, air = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", quick, air = air).apply { setDisplayName("Bob") }
            meetThenPart(alice, bob)
            air.lossy = { from, to -> from == bob.boardNum || to == bob.boardNum }

            assertTrue(alice.sendDm(bob, "quiet one"))
            val dm = alice.ownMessageId(alice.dmWith(bob), "quiet one")
            lab.await(1) {
                alice.metrics
                    .lora()
                    .loraDirectUnreachable
                    .toInt()
            }
            air.lossy = { _, _ -> false }

            lab.clock.advance(LoraReprobe.BASE_MS)
            lab.await(1) { holds(bob, alice, dm) }
            val log = alice.loraLog.toList()
            val reprobed = log.indexOfFirst { it.startsWith("lora reprobe $dm") }
            val heard = log.indexOfFirst { it == "lora direct heard ${bob.nodeId}" }
            assertTrue("the re-probe carried it: ${log.takeLast(10)}", reprobed >= 0)
            // Its destination's ACK is the first thing alice's board hears of bob's: nothing was heard before it asked.
            assertTrue("bob's board was heard before the re-probe: $log", heard == -1 || heard > reprobed)
            assertEquals(DeliveryPlane.LoRa, lab.awaitReceipt(alice, dm, bob))

            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
        }

    /**
     * (j) First contact over LoRa only, then a restore (ADR 2026-10.x9fa's answer gate, ADR 2026-10.4mjm's rule C). Alice
     * and Bob have met but hold no session; each sends a first DM the other reads compact and ✓✓s compact, while Carol,
     * linked to both, holds their signed copies. No ✓✓ opens a session of its own: with only its sender's own init
     * unanswered, it goes compact alone. Carol lets the race's winner read the loser's init first, so the winner's intro
     * answer carries its own init (pz9g), then the loser; the pair links and converges on one root.
     *
     * Past the skew bound, the larger-id node (the race's loser) restores. Its reset reaches the winner over a link of
     * their own, the winner takes it and answers — and the winner's custody re-serve brings the restored node the old
     * intro, carrying the winner's init, ahead of that answer. That init wins `resolveRace` against the restored node's
     * own unanswered reset (#189); the answer under the reset then moves it back (rule C: the reset was its own init,
     * lost to a race, and the peer sealed under it as responder). Only then does Carol re-serve her custody to it. One
     * root, the swap counted, no reset sent back, and no ✓✓ or reaction anywhere ever carried an init.
     */
    @Test
    fun aLoraFirstContactLeavesNoInitInAnyTickAndSurvivesARestore() =
        runBlocking {
            val air = MeshLab.quickAir()
            val alice = lab.node("alice", quick, air = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", quick, air = air).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", quick, board = false).apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, alice to carol, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)
            lab.await(1) { alice.boardReadyFor(bob) }
            lab.await(1) { bob.boardReadyFor(alice) }
            lab.unlink(alice, bob)
            carol.transport.hold(alice.transport)
            carol.transport.hold(bob.transport)
            val (winner, loser) = if (alice.nodeId < bob.nodeId) alice to bob else bob to alice

            assertTrue(alice.sendDm(bob, "first from alice"))
            assertTrue(bob.sendDm(alice, "first from bob"))
            val fromAlice = alice.ownMessageId(alice.dmWith(bob), "first from alice")
            val fromBob = bob.ownMessageId(bob.dmWith(alice), "first from bob")
            lab.await(1) { holds(bob, alice, fromAlice) }
            lab.await(1) { holds(alice, bob, fromBob) }
            assertEquals(DeliveryPlane.LoRa, lab.awaitReceipt(alice, fromAlice, bob))
            assertEquals(DeliveryPlane.LoRa, lab.awaitReceipt(bob, fromBob, alice))

            // The winner reads the loser's signed copy first: its intro answer is sealed on its own unanswered init.
            val losersFirst = if (loser === alice) fromAlice else fromBob
            lab.await(1) { if (losersFirst in carol.custodyIds()) 1 else 0 }
            carol.transport.release(winner.transport)
            lab.await(1) { if (winner.loraSeen(losersFirst)?.ratchetDone == true) 1 else 0 }
            lab.await(1) { winner.initBearingCtlsFrom(winner).size }
            carol.transport.release(loser.transport)
            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 2, carriers = listOf(carol)) { it.dmWith(if (it === alice) bob else alice) }

            // The answer under the restore's reset must be sealed past the winning init's stamp plus the skew bound.
            lab.clock.advance(Protocol.MAX_FUTURE_SKEW_MS + 60_000L)
            val restored = loser
            val other = winner
            lab.unlink(alice, bob)
            lab.unlink(carol, restored)
            restoreBehindTheWinnersOldIntro(restored, other)
            lab.link(carol, restored)
            assertTrue(alice.sendDm(bob, "after the restore"))
            assertTrue(bob.sendDm(alice, "seen"))
            lab.assertConverged(listOf(alice, bob), atLeast = 4, carriers = listOf(carol)) { it.dmWith(if (it === alice) bob else alice) }
            assertEquals("one root", alice.session(bob)?.rootHash, bob.session(alice)?.rootHash)
            assertTrue("the restored node moved back onto its reset", restored.metrics.keys().ratchetSwappedBack >= 1)
            assertEquals("no reset sent back", 0L, other.session(restored)?.lastResetSentAt)
            assertNoAnswerCarriesAnInit(alice, bob, carriers = listOf(carol))
        }

    /** No ✓✓ or reaction between [one] and [two], in either's or a carrier's custody, carries an X3DH init (x9fa's gate). */
    private suspend fun assertNoAnswerCarriesAnInit(
        one: LabNode,
        two: LabNode,
        carriers: List<LabNode>,
    ) {
        for (holder in listOf(one, two) + carriers) {
            for ((author, reader) in listOf(one to two, two to one)) {
                assertEquals(
                    "${author.name}'s ✓✓s and reactions carrying an init, in ${holder.name}'s custody",
                    emptyList<String>(),
                    holder.initBearingAnswersFrom(author, reader),
                )
            }
        }
    }

    /**
     * Restores [restored] and links it to [other], the race's winner, whose side of the link is held until it carries
     * both its old init-bearing intro and an answer sealed since the restore; the intro goes through first — the order
     * #189 splits the pair on — and the restore's reset must end answered, [restored] its confirmed initiator.
     */
    private suspend fun restoreBehindTheWinnersOldIntro(
        restored: LabNode,
        other: LabNode,
    ) {
        restored.restoreFromBackup()
        val restoredAt = lab.clock.now()
        lab.link(restored, other) { other.transport.hold(restored.transport) }
        lab.await(1) { if (other.holdsOldInitCtlAndAnswer(other, restored, restoredAt)) 1 else 0 }
        other.releaseInitsFirst(other, restored)
        assertTrue(
            "the restore's reset was never answered",
            lab.tryAwait(1) { restored.session(other)?.let { if (it.confirmed && it.weAreInitiator) 1 else 0 } ?: 0 },
        )
    }

    /**
     * (k) #87's shape with boards: alice resets while bob's DM reaches her compact. Her ✓✓ for it is an answer with no
     * session to answer under — her own reset is unanswered — so it crosses compact alone, no signed ✓✓ of hers is sealed
     * meanwhile, and the id is held (ADR 2026-10.x9fa). The link brings bob her reset, bob answers it, and the held ✓✓ is
     * sealed then, under the session bob holds, and lands on him.
     */
    @Test
    fun aTickHeldOnAnUnansweredResetIsSealedOnceTheResetIsAnswered() =
        runBlocking {
            val air = MeshLab.quickAir()
            val alice = lab.node("alice", quick, air = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", quick, air = air).apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            lab.await(1) { alice.boardReadyFor(bob) }
            lab.await(1) { bob.boardReadyFor(alice) }
            assertTrue(alice.sendDm(bob, "hello"))
            lab.await(1) { if (bob.session(alice)?.confirmed == true) 1 else 0 }
            assertTrue(bob.sendDm(alice, "hi"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            lab.unlink(alice, bob)

            assertNull("alice's reset went out", alice.resetSession(bob))
            val before = alice.ctlsFromTo(alice, bob)
            assertTrue(bob.sendDm(alice, "while you reset"))
            val dm = bob.ownMessageId(bob.dmWith(alice), "while you reset")
            lab.await(1) { holds(alice, bob, dm) }
            assertEquals("its ✓✓ crossed the board", DeliveryPlane.LoRa, lab.awaitReceipt(bob, dm, alice))
            assertEquals("no signed ✓✓ of alice's sealed on her unanswered reset", before, alice.ctlsFromTo(alice, bob))

            answerTheResetAndPeekTheHeldTick(alice, bob, before, dm)
        }

    /**
     * (k′) The carrier variant of (k): the signed copy of bob's DM reaches alice through carol while alice is still apart
     * from bob, her reset unanswered. Alice runs its ratchet step off the compact copy's mark, and her re-ack of it is an
     * answer with nothing to answer under — so still nothing signed of hers for bob, and the id stays held. Only once
     * bob answers the reset is the ✓✓ sealed, and it names the DM. Without the hold (`MeshManager.holdDeclinedTicks`) the
     * signed ✓✓ is lost for good here: the coalescer's flush and the signed copy's re-ack both declined.
     */
    @Test
    fun aTickHeldOnAnUnansweredResetSurvivesTheSignedCopyArrivingByCarrier() =
        runBlocking {
            val air = MeshLab.quickAir()
            val alice = lab.node("alice", quick, air = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", quick, air = air).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", quick, board = false).apply { setDisplayName("Carol") } // the carrier alone
            lab.linkAll(alice to bob, alice to carol, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)
            lab.await(1) { alice.boardReadyFor(bob) }
            lab.await(1) { bob.boardReadyFor(alice) }
            assertTrue(alice.sendDm(bob, "hello"))
            lab.await(1) { if (bob.session(alice)?.confirmed == true) 1 else 0 }
            assertTrue(bob.sendDm(alice, "hi"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2, carriers = listOf(carol)) { it.dmWith(if (it === alice) bob else alice) }
            listOf(alice to bob, alice to carol, bob to carol).forEach { (a, b) -> lab.unlink(a, b) }

            assertNull("alice's reset went out", alice.resetSession(bob))
            val before = alice.ctlsFromTo(alice, bob)
            lab.link(carol, bob)
            lab.awaitAcquainted(carol, bob)
            assertTrue(bob.sendDm(alice, "while you reset"))
            val dm = bob.ownMessageId(bob.dmWith(alice), "while you reset")
            lab.await(1) { holds(alice, bob, dm) }
            assertEquals("its ✓✓ crossed the board", DeliveryPlane.LoRa, lab.awaitReceipt(bob, dm, alice))
            lab.await(1) { if (dm in carol.custodyIds()) 1 else 0 }
            lab.unlink(carol, bob)

            val heldBefore = alice.metrics.receipts().receiptsHeld
            lab.link(carol, alice)
            // The signed copy reached alice's ratchet-only door: its step ran, or it could not and was counted — her reset
            // purged the era bob sealed it in. Either way the re-ack is declined, and the id stays held.
            lab.await(1) {
                val stepped = alice.loraSeen(dm)?.ratchetDone == true
                if (stepped || alice.drops(DropReason.LORA_DIRECT_RATCHET_MISSED) > 0) 1 else 0
            }
            // Both of those land before the re-ack is judged; the hand-off to the hold is what the checks below follow.
            lab.await(1) { (alice.metrics.receipts().receiptsHeld - heldBefore).toInt() }
            lab.awaitAcquainted(carol, alice)
            assertEquals("no signed ✓✓ of alice's sealed on her unanswered reset", before, alice.ctlsFromTo(alice, bob))
            assertEquals("nor carried by carol", emptySet<String>(), carol.ctlsFromTo(alice, bob) - before)

            answerTheResetAndPeekTheHeldTick(alice, bob, before, dm, carriers = listOf(carol))
        }

    /**
     * Links [alice] and [bob], alice's side of the pipe held: every frame goes on as it comes, but each new ctl of
     * alice's to bob is first read by a peek on bob's ratchet ([LabNode.peekDmCtl]) while its key is unspent — each pass
     * lets through exactly the frames it read (`LabTransport.releaseFirst`), so none reaches bob unpeeked. The first
     * that is a ✓✓ must name [dm] — the held one, sealed once bob has answered alice's reset — and then the pipe opens.
     */
    private suspend fun answerTheResetAndPeekTheHeldTick(
        alice: LabNode,
        bob: LabNode,
        before: Set<String>,
        dm: String,
        carriers: List<LabNode> = emptyList(),
    ) {
        var tick: MessageContent? = null
        val peeked = HashSet<String>()
        lab.link(alice, bob) { alice.transport.hold(bob.transport) }
        val found =
            lab.tryAwait(1) {
                // Only what was read here goes on: a frame parked after the read is read on the next pass, before bob.
                val read = alice.transport.held(bob.transport)
                for (frame in read) {
                    val env = WireCodec.decodeEnvelope(frame.signed) ?: continue
                    val candidate =
                        env.type == FrameType.CHAT && env.senderId == alice.nodeId && env.recipientId == bob.nodeId &&
                            env.id !in before && peeked.add(env.id) && alice.messages.find(env.id) == null
                    val content = if (candidate) bob.peekDmCtl(frame, alice) else null
                    if (content?.ctl == MessageContent.CTL_RECEIPT) {
                        tick = content
                        return@tryAwait 1
                    }
                }
                alice.transport.releaseFirst(bob.transport, read.size)
                0
            }
        alice.transport.release(bob.transport)
        assertTrue("no signed ✓✓ of alice's after bob answered her reset", found)
        val acked = checkNotNull(tick).let { listOfNotNull(it.ack) + it.acks.orEmpty() }
        assertTrue("the signed ✓✓ names $dm: $acked", dm in acked)
        assertTrue("bob answered the reset", alice.session(bob)?.confirmed == true)
        lab.assertConverged(listOf(alice, bob), atLeast = 3, carriers = carriers) { it.dmWith(if (it === alice) bob else alice) }
    }

    private companion object {
        const val BURST = 15

        /** One send in flight to a board not yet heard from, and `LoraDirectPolicy.MAX_PARKED_PER_PEER` (8) behind it. */
        const val CROSSES_AT_ONCE = 9

        /** `LORA_DIRECT_TAG`, the compact DM's first byte, as the fake board's sent payload starts. */
        const val LORA_DIRECT_TAG_BYTE: Byte = 0x11
    }
}
