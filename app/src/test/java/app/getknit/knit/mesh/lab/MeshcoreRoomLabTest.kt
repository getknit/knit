package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.mesh.FastPathDrop
import app.getknit.knit.mesh.link.FastFrameCodec
import app.getknit.knit.mesh.lora.KnitChannel
import app.getknit.knit.mesh.lora.LoraMeshTransport
import app.getknit.knit.mesh.lora.meshcore.FakeForeignClient
import app.getknit.knit.mesh.lora.meshcore.FakeMeshcoreAir
import app.getknit.knit.mesh.lora.meshcore.MeshcoreProto
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Own room posts over MeshCore boards, end to end (ADR 2026-11.gsqu's Stage 5c-1 and 5c-2b amendments): real stacks over
 * one [FakeMeshcoreAir] the lab owns ([MeshLab.meshcoreAir]), a post longer than one packet in two bound parts, a short one
 * whole and elided (`0x06`). The nodes meet over a link first, so each author is a pinned peer the reassembler resolves,
 * then part. Every case ends on a link: a room tick never rides LoRa (ADR 2026-10.rp3s).
 *
 * The races are ordered by the air's own knobs, never a repeater draw: `hold` / `release` park a board's or a repeater's
 * airings before anyone hears them, a forger is a synchronous air listener installed before the send, and every wait reads
 * the receiving plane ([app.getknit.knit.mesh.MeshMetrics]' `loraBoundPartsTaken`, the drops), never the air's record.
 * The cap's whole window and its rates are `MeshcoreAirPhysicsTest`'s.
 */
@RunWith(RobolectricTestRunner::class)
class MeshcoreRoomLabTest {
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

    /** Printable noise that does not deflate: [bytes] of it is a two-part post past 57 B. */
    private fun noise(
        bytes: Int = LONG_POST,
        seed: Int = 1,
    ): String {
        val random = Random(seed)
        return String(CharArray(bytes) { (0x21 + random.nextInt(0x5E)).toChar() })
    }

    /** [nodes] on [air], met over a link so each is pinned at every other, then parted. */
    private suspend fun boards(
        air: FakeMeshcoreAir,
        vararg names: String,
        fragIds: Map<String, () -> Int> = emptyMap(),
    ): List<LabNode> {
        val nodes = names.map { lab.node(it, meshcore = air, fragIds = fragIds[it]).apply { setDisplayName(it) } }
        val pairs = nodes.flatMapIndexed { i, a -> nodes.drop(i + 1).map { b -> a to b } }
        lab.linkAll(*pairs.toTypedArray())
        lab.awaitAcquainted(*nodes.toTypedArray())
        pairs.forEach { (a, b) -> lab.unlink(a, b) }
        return nodes
    }

    /**
     * Waits for [node] to hold [author]'s post [text]; a failure names whether [node]'s plane resolved [author] as an author
     * and holds their key, what the verification of an elided post reads.
     */
    private suspend fun awaitHeard(
        node: LabNode,
        author: LabNode,
        text: String,
    ) {
        if (lab.tryAwait(1) { heard(node, author, text) }) return
        val plane = checkNotNull(node.meshcore)
        throw AssertionError(
            "${node.name} never held ${author.name}'s post: resolves ${author.name}=${plane.resolvesAuthor(author.nodeId)}, " +
                "pinned=${node.peer(author)?.pubKey != null}, ${node.loraLog.filter { "unverified" in it }}\n" +
                lab.report(listOf(node, author)),
        )
    }

    private suspend fun heard(
        node: LabNode,
        author: LabNode,
        text: String,
    ): Int = if (node.roomPosts()[author.nodeId]?.contains(text) == true) 1 else 0

    private fun LabNode.drops(reason: FastPathDrop): Long = metrics.fast().fastDropsByReason[reason] ?: 0L

    private fun LabNode.taken(): Long = metrics.lora().loraBoundPartsTaken

    private suspend fun converge(
        vararg nodes: LabNode,
        atLeast: Int = 1,
    ) {
        val list = nodes.toList()
        lab.linkAll(*list.flatMapIndexed { i, a -> list.drop(i + 1).map { b -> a to b } }.toTypedArray())
        lab.assertConverged(list, atLeast = atLeast) { Conversations.NEARBY }
    }

    /** Whether [data] is a bound head (`0x07`, part 0 in the header's high nibble). */
    private fun isHead(data: ByteArray?): Boolean =
        data != null && data[0] == FastFrameCodec.TAG_BOUND_FRAG && (data[FRAG_PART_BYTE].toInt() shr 4) == 0

    /**
     * A forger's board, [name], on the Knit channel: no phone, only a foreign client that writes `SEND_CHANNEL_DATA`
     * straight to it. Each part it airs differs, so no ring drops one.
     */
    private class Forger(
        val air: FakeMeshcoreAir,
        val name: String = "mallory",
    ) {
        private lateinit var client: FakeForeignClient
        private val n = AtomicInteger()

        suspend fun build(): Forger =
            apply {
                air.meshcoreEdit {
                    val board = board(name)
                    board.setChannel(KNIT_SLOT, KnitChannel.NAME, KnitChannel.PSK)
                    client = FakeForeignClient(board)
                }
            }

        /** One bound part of the shortest form under [fragId]: [part] of two, naming [names], one byte of junk. Serial only. */
        fun air(
            fragId: Int,
            part: Int,
            names: ByteArray = junkHash(),
        ) {
            val k = n.incrementAndGet()
            val header = byteArrayOf(FastFrameCodec.TAG_BOUND_FRAG, (fragId shr 8).toByte(), fragId.toByte(), ((part shl 4) or 2).toByte())
            client.send(checkNotNull(MeshcoreProto.sendChannelData(KNIT_SLOT, header + names + byteArrayOf(k.toByte()))))
        }

        private fun junkHash(): ByteArray =
            ByteBuffer
                .allocate(FastFrameCodec.COMMITMENT_BYTES)
                .putLong(0x5EED_0000L + n.get())
                .array()
    }

    /**
     * A two-part post through a repeater reaches a node out of its author's range: alice and bob each in range of the
     * relay alone, so bob hears both parts at one hop.
     */
    @Test
    fun aTwoPartPostCrossesARepeaterToANodeOutOfItsAuthorsRange() =
        runBlocking {
            val air = lab.meshcoreAir()
            air.meshcoreEdit { repeater("relay", "alice", "bob") }
            val (alice, bob) = boards(air, "alice", "bob")
            val text = noise()

            assertTrue(alice.sendRoom(text))
            val post = alice.ownMessageId(Conversations.NEARBY, text)
            awaitHeard(bob, alice, text)
            assertEquals(DeliveryPlane.LoRa, bob.receivedVia(Conversations.NEARBY, post))
            // The line is logged after the send returns, so it can trail the landing: awaited, not read.
            assertTrue("two parts: ${alice.loraLog}", lab.tryAwait(1) { alice.loraLog.count { it == "lora tx fanout:chat parts=2" } })
            assertTrue("through the relay", air.snapshot { air.airings.count { it.from == "relay" } } >= 2)
            converge(alice, bob)
        }

    /**
     * "Once" on a diamond: bob in range of alice and of the relay, the relay held. Bob takes alice's own copy; his ring is
     * then evicted (a busy mesh's 160 packets) and the relay released, so the repeat reaches bob's plane too — awaited at
     * his router's dedupe — and the post stands once.
     */
    @Test
    fun aPostHeardTwiceOnADiamondOnceTheRingForgetsLandsOnce() =
        runBlocking {
            val air = lab.meshcoreAir(channel = true)
            air.meshcoreEdit {
                link("alice", "bob")
                repeater("relay", "alice", "bob")
                hold("relay")
            }
            val (alice, bob) = boards(air, "alice", "bob")
            val deduped = bob.metrics.frames().framesDeduped
            val text = noise()

            assertTrue(alice.sendRoom(text))
            val post = alice.ownMessageId(Conversations.NEARBY, text)
            awaitHeard(bob, alice, text)
            lab.await(2) { air.snapshot { air.parked("relay").count { it.type == "grp_data" } } }
            air.meshcoreEdit {
                evictRing("bob")
                release("relay")
            }
            lab.await(1) { (bob.metrics.frames().framesDeduped - deduped).toInt() }
            val handed = bob.loraLog.count { it.startsWith("lora rx ") && "id=$post " in "$it " }
            assertEquals("the plane handed the repeat on: ${bob.loraLog}", 2, handed)
            assertEquals("one row", 1, bob.roomPosts()[alice.nodeId]?.size)
            converge(alice, bob)
        }

    /** A short post goes whole and elided: one `0x06` packet, read on the far side. */
    @Test
    fun aOnePartPostLandsElided() =
        runBlocking {
            val air = lab.meshcoreAir()
            air.meshcoreEdit { link("alice", "bob") }
            val (alice, bob) = boards(air, "alice", "bob")
            val sends = channelData(alice).size

            assertTrue(alice.sendRoom("one packet"))
            awaitHeard(bob, alice, "one packet")
            val written = channelData(alice).drop(sends)
            assertEquals("one packet: ${alice.loraLog}", 1, written.size)
            assertEquals("elided", FastFrameCodec.TAG_ELIDED, written.single()[CHANNEL_DATA_PAYLOAD])
            converge(alice, bob)
        }

    /**
     * A pocket-mate's post never airs on our board (ADR 2026-10.rp3s: only its own user's posts air): carol, linked to alice
     * and with no board, posts; alice holds it, and her board refuses it when her router's relay re-fans it (awaited). Then
     * alice posts her own, which airs and bob hears — the fence — and her board was asked to send channel data for hers
     * alone.
     */
    @Test
    fun aPocketMatesPostNeverAirsOnOurBoard() =
        runBlocking {
            val air = lab.meshcoreAir()
            air.meshcoreEdit { link("alice", "bob") }
            val alice = lab.node("alice", meshcore = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", meshcore = air).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", board = false).apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, alice to carol, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)
            lab.unlink(alice, bob)
            lab.unlink(bob, carol)
            val sends = alice.meshcoreCommands().count { it == MeshcoreProto.CMD_SEND_CHANNEL_DATA }

            assertTrue(carol.sendRoom("from the pocket"))
            val pocket = carol.ownMessageId(Conversations.NEARBY, "from the pocket")
            awaitHeard(alice, carol, "from the pocket")
            // The forbidden path is alice's router re-fanning carol's post through the composite, after its relay jitter:
            // the board's refusal is awaited at the event (`loraNotOurs`'s line names the frame), then the fence below.
            lab.await(1) { alice.loraLog.count { it == "lora not ours fanout:$pocket" } }
            assertTrue(alice.sendRoom("from alice"))
            awaitHeard(bob, alice, "from alice")
            assertEquals(
                "alice's board sent channel data for her own post alone",
                sends + 1,
                alice.meshcoreCommands().count { it == MeshcoreProto.CMD_SEND_CHANNEL_DATA },
            )
            assertEquals("bob never heard carol's", 0, bob.roomPosts()[carol.nodeId]?.size ?: 0)
            converge(alice, bob, carol, atLeast = 2)
        }

    /**
     * The junk-part-1 race, ordered: alice's board held, part 0 released alone; the forger, an air listener installed
     * before the send, hears it and airs three junk part-1s naming it — awaited at bob's plane — and only then does part 1
     * go. The post lands, no junk took a check, and the junk ages out: past `FRAG_TIMEOUT_MS` on the calendar one fresh
     * fragment runs the sweep, and the three are counted `FRAG_TIMEOUT`.
     */
    @Test
    fun junkPartOnesAiredBetweenTheHonestPartsLeaveThePostToLand() =
        runBlocking {
            val air = lab.meshcoreAir(channel = true)
            air.meshcoreEdit {
                link("alice", "bob")
                link("alice", "mallory")
                link("bob", "mallory")
            }
            val forger = Forger(air).build()
            val (alice, bob) = boards(air, "alice", "bob")
            val aired = AtomicInteger()
            air.meshcoreEdit {
                listeners += { a ->
                    val data = a.data
                    if (a.from == "alice" && isHead(data) && aired.compareAndSet(0, JUNK)) {
                        val id = fragIdOf(checkNotNull(data))
                        val named = FastFrameCodec.commitmentOf(data.copyOfRange(HEAD_SLICE, data.size))
                        repeat(JUNK) { forger.air(id, part = 1, names = named) }
                    }
                }
                hold("alice")
            }
            val taken = bob.taken()
            val text = noise()

            assertTrue(alice.sendRoom(text))
            lab.await(2) { air.snapshot { air.parked("alice").count { it.type == "grp_data" } } }
            air.meshcoreEdit { release("alice", stayHeld = true) { held -> held.filter { isHead(it.data) } } }
            lab.await(1 + JUNK) { (bob.taken() - taken).toInt() }
            air.meshcoreEdit { release("alice") }
            awaitHeard(bob, alice, text)
            assertEquals("no junk took a check", 0L, bob.drops(FastPathDrop.FRAG_UNVERIFIED))
            assertEquals(0L, bob.drops(FastPathDrop.FRAG_CANDIDATES_FULL))

            val timeouts = bob.drops(FastPathDrop.FRAG_TIMEOUT)
            val poked = bob.taken()
            lab.clock.advance(LoraMeshTransport.FRAG_TIMEOUT_MS + 1_000L)
            air.meshcoreEdit { forger.air(fragId = 0x0BAD, part = 0) }
            lab.await(1) { (bob.taken() - poked).toInt() }
            lab.await(JUNK) { (bob.drops(FastPathDrop.FRAG_TIMEOUT) - timeouts).toInt() }
            converge(alice, bob)
        }

    /**
     * The overtake, ordered: the relay between alice and bob held, the draw pinned at its maximum (the forger's best case).
     * The forger, in range of both, hears part 0 and lands a head copying its commitment and five short heads at bob first —
     * awaited at bob's plane — and only then is the relay released. The post lands and no fake took a check.
     */
    @Test
    fun headsThatOvertakeTheHonestOneBehindARepeaterLeaveThePostToLand() =
        runBlocking {
            val air = lab.meshcoreAir(channel = true)
            air.meshcoreEdit {
                repeater("relay", "alice", "bob")
                link("alice", "mallory")
                link("bob", "mallory")
                checkNotNull(channel).pinDraw = { it }
            }
            val forger = Forger(air).build()
            val (alice, bob) = boards(air, "alice", "bob")
            val aired = AtomicInteger()
            air.meshcoreEdit {
                listeners += { a ->
                    val data = a.data
                    if (a.from == "alice" && isHead(data) && aired.compareAndSet(0, 1 + HEADS)) {
                        val id = fragIdOf(checkNotNull(data))
                        forger.air(id, part = 0, names = data.copyOfRange(HEAD_COMMITMENT, HEAD_SLICE))
                        repeat(HEADS) { forger.air(id, part = 0) }
                    }
                }
                hold("relay")
            }
            val taken = bob.taken()
            val text = noise()

            assertTrue(alice.sendRoom(text))
            lab.await(1 + HEADS) { (bob.taken() - taken).toInt() }
            lab.await(2) { air.snapshot { air.parked("relay").count { it.type == "grp_data" } } }
            assertEquals("nothing honest yet", 0, heard(bob, alice, text))
            air.meshcoreEdit { release("relay") }
            awaitHeard(bob, alice, text)
            assertEquals("no fake took a check", 0L, bob.drops(FastPathDrop.FRAG_UNVERIFIED))
            assertEquals(0L, bob.drops(FastPathDrop.FRAG_CANDIDATES_FULL))
            converge(alice, bob)
        }

    /** The deep line: alice → r1 → r2 → bob, a two-part post delivered through two hops. */
    @Test
    fun aTwoPartPostCrossesTwoRepeaters() =
        runBlocking {
            val air = lab.meshcoreAir()
            air.meshcoreEdit {
                repeater("r1", "alice")
                repeater("r2", "r1", "bob")
            }
            val (alice, bob) = boards(air, "alice", "bob")
            val text = noise()

            assertTrue(alice.sendRoom(text))
            awaitHeard(bob, alice, text)
            assertTrue("through both: ${air.airings}", air.snapshot { air.airings.count { it.from == "r2" && it.hops == 2 } } >= 2)
            converge(alice, bob)
        }

    /**
     * Pre-filled ids, the sequential-id sibling: alice's plane draws its fragment ids in sequence (a seeded counter, the
     * form 5c-1's review found predictable), and the forger, knowing the next one, pre-fills both sides of its entry with
     * junk heads and tails before she posts — awaited at bob's plane. The bound form leaves no junk a pairing: the post
     * lands and no junk took a check. (With production's SecureRandom ids the property is the reassembler's JVM test.)
     */
    @Test
    fun junkPreFilledUnderASequentialIdLeavesThePostToLand() =
        runBlocking {
            val air = lab.meshcoreAir()
            air.meshcoreEdit {
                link("alice", "bob")
                link("bob", "mallory")
            }
            val forger = Forger(air).build()
            val next = AtomicInteger(FIRST_ID)
            val (alice, bob) = boards(air, "alice", "bob", fragIds = mapOf("alice" to { next.getAndIncrement() }))
            val taken = bob.taken()
            air.meshcoreEdit {
                repeat(PREFILL) {
                    forger.air(FIRST_ID, part = 0)
                    forger.air(FIRST_ID, part = 1)
                }
            }
            lab.await(2 * PREFILL) { (bob.taken() - taken).toInt() }
            val text = noise()

            assertTrue(alice.sendRoom(text))
            awaitHeard(bob, alice, text)
            assertEquals("under the id the forger predicted", listOf(FIRST_ID), alice.fragIdsDrawn.toList())
            assertEquals("no junk took a check", 0L, bob.drops(FastPathDrop.FRAG_UNVERIFIED))
            assertEquals(0L, bob.drops(FastPathDrop.FRAG_CANDIDATES_FULL))
            converge(alice, bob)
        }

    /** The `SEND_CHANNEL_DATA` frames [node]'s session wrote this boot. */
    private suspend fun channelData(node: LabNode): List<ByteArray> =
        node.meshcoreWrites().filter { it[0].toInt() == MeshcoreProto.CMD_SEND_CHANNEL_DATA }

    private fun fragIdOf(data: ByteArray): Int = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)

    private companion object {
        /** A post past one packet: two bound parts. */
        const val LONG_POST = 150

        /** The board slot the lab boards' setup gives Knit, the first free one past Public. */
        const val KNIT_SLOT = 1

        /** A bound part's header: the tag, the 16-bit id, then part and count; the commitment, then the slice. */
        const val FRAG_PART_BYTE = 3
        const val HEAD_COMMITMENT = 4
        const val HEAD_SLICE = HEAD_COMMITMENT + FastFrameCodec.COMMITMENT_BYTES

        /** `SEND_CHANNEL_DATA`'s own header: the code, the slot, the path and the 16-bit data type. */
        const val CHANNEL_DATA_PAYLOAD = 5

        const val JUNK = 3
        const val HEADS = 5
        const val PREFILL = 4
        const val FIRST_ID = 0x2A00
    }
}
