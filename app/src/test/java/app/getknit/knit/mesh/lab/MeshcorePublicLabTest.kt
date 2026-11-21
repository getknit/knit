package app.getknit.knit.mesh.lab

import app.getknit.knit.data.ReactionRepository
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.MessageEntity
import app.getknit.knit.mesh.DropReason
import app.getknit.knit.mesh.MeshPostOrigin
import app.getknit.knit.mesh.MeshcoreReactionOutcome
import app.getknit.knit.mesh.NotificationLabels
import app.getknit.knit.mesh.PublicPostOutcome
import app.getknit.knit.mesh.PublicPostRefusal
import app.getknit.knit.mesh.lora.GattResult
import app.getknit.knit.mesh.lora.LoraFramePolicy
import app.getknit.knit.mesh.lora.MeshcoreRenameResult
import app.getknit.knit.mesh.lora.meshcore.FakeMeshcoreAir
import app.getknit.knit.mesh.lora.meshcore.FakeNusChannel
import app.getknit.knit.mesh.lora.meshcore.MeshcoreNames
import app.getknit.knit.mesh.lora.meshcore.MeshcoreProto
import app.getknit.knit.mesh.meshcoreSpeakerKey
import app.getknit.knit.mesh.protocol.FrameId
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.ReactionContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.notifications.NotifMessage
import app.getknit.knit.notifications.meshcoreReactionKey
import app.getknit.knit.ui.chat.DeliveryStatus
import io.mockk.coVerify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/**
 * The MeshCore Public room, end to end (ADR 2026-11.gsqu's Stage 6 amendment): real stacks with the real MeshCore plane and
 * the room's hooks wired as `di/MeshModule` wires them, over one [FakeMeshcoreAir], with [StockMeshcoreNode]s — boards with
 * no Knit behind them — posting plain `name: text` on Public under any name. A heard post is a stranger's, always: the
 * sentinel `originNode`, no peer, no face, never ours; it is never relayed, custodied or originated; and our own post's echo
 * dedups by id, never by name.
 *
 * Every wait reads the receiving phone ([LabNode.meshcorePostsHandled], a row, a counter at the event, a notification
 * line), never the air's record. Mirror rooms take no receipts, so the oracle runs on Nearby over a link at the end: each
 * case posts there once and ends in `assertConverged`.
 */
@RunWith(RobolectricTestRunner::class)
class MeshcorePublicLabTest {
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

    /** A DM's ✓✓ batches a second, not 45: [MeshLab.meetOverMeshcore] converges its DMs over the link. */
    private val quick = LabLimits(dmAckHoldMs = 1_000L, loraLingerSweepMs = 200L)

    /** [names] on [air], met over a link so each is pinned at every other, then parted. */
    private suspend fun boards(
        air: FakeMeshcoreAir,
        vararg names: String,
        limits: LabLimits = quick,
    ): List<LabNode> {
        val nodes = names.map { lab.node(it, limits, meshcore = air).apply { setDisplayName(it.replaceFirstChar(Char::titlecase)) } }
        val pairs = nodes.flatMapIndexed { i, a -> nodes.drop(i + 1).map { b -> a to b } }
        lab.linkAll(*pairs.toTypedArray())
        lab.awaitAcquainted(*nodes.toTypedArray())
        pairs.forEach { (a, b) -> lab.unlink(a, b) }
        return nodes
    }

    /** Every row [this] holds in the MeshCore room, its own and heard alike. */
    private suspend fun LabNode.mirror(): List<MessageEntity> =
        messages.observeNewestMessages(Conversations.MESHCORE_PUBLIC, MeshLab.WINDOW).first()

    private suspend fun LabNode.mirrored(body: String): List<MessageEntity> = mirror().filter { it.body == body }

    /** Waits for [node] to hold [body] in the MeshCore room; a failure names what its plane and room hold. */
    private suspend fun awaitMirrored(
        node: LabNode,
        body: String,
    ): MessageEntity {
        if (lab.tryAwait(1) { node.mirrored(body).size }) return node.mirrored(body).single()
        throw AssertionError(
            "${node.name} never mirrored \"$body\": room=${node.mirror().map { it.body }}, " +
                "hooks=${node.meshcoreHooks.counters}\n${lab.report(listOf(node))}",
        )
    }

    /** The MeshCore room's notification lines [this] boot posted, in order. */
    private fun LabNode.meshcoreLines(): List<NotifMessage> {
        val posted = mutableListOf<NotifMessage>()
        coVerify(atLeast = 0) { notifier.notify(capture(posted), any(), any(), any(), any()) }
        return posted.filter { it.conversationId == Conversations.MESHCORE_PUBLIC }
    }

    /** The notification line for [body], once it is posted (after the row's commit, so awaited on its own). */
    private suspend fun awaitLine(
        node: LabNode,
        body: String,
    ): NotifMessage {
        lab.await(1) { node.meshcoreLines().count { it.body == body } }
        return node.meshcoreLines().single { it.body == body }
    }

    /** What the notification names a heard author: the claimed name quoted and marked, whatever it matches. */
    private fun claimed(name: String): String = NotificationLabels.ENGLISH.claimedName.format(Locale.getDefault(), name)

    /** A row heard off the air: the sentinel origin under [name], no peer, in our sender column by convention only. */
    private fun assertStranger(
        node: LabNode,
        row: MessageEntity,
        name: String,
    ) {
        val what = "\"${row.body}\" at ${node.name}"
        assertEquals("$what: the sentinel origin", MeshPostOrigin.MESHCORE_NODE, row.originNode)
        assertEquals("$what: the name it came under", name, row.originName)
        assertNull("$what: resolved to a peer", row.originPeerId)
        assertEquals("$what: the sender column is the phone's, by convention", node.nodeId, row.senderId)
    }

    /** A notification line for a heard post: keyed on the name's hash, named as claimed, with no face. */
    private fun assertStrangerLine(
        line: NotifMessage,
        name: String,
        notThese: List<LabNode>,
    ) {
        assertEquals("keyed on the name's hash", meshcoreSpeakerKey(name), line.senderId)
        assertEquals("named as claimed", claimed(name), line.senderName)
        assertNull("no face", line.avatarBytes)
        assertTrue("keyed on a Knit identity: ${line.senderId}", notThese.none { it.nodeId == line.senderId })
    }

    /** Links every pair of [nodes], posts once in Nearby, and runs the oracle there: mirror rooms take no receipts. */
    private suspend fun converge(vararg nodes: LabNode) {
        val list = nodes.toList()
        lab.linkAll(*list.flatMapIndexed { i, a -> list.drop(i + 1).map { b -> a to b } }.toTypedArray())
        lab.awaitAcquainted(*nodes)
        assertTrue(list.first().sendRoom("over the link"))
        lab.assertConverged(list, atLeast = 1) { Conversations.NEARBY }
    }

    /** The `SEND_CHANNEL_DATA` and `SEND_CHANNEL_TXT_MSG` commands [node]'s board answered: Knit frames, and Public posts. */
    private suspend fun boardSends(node: LabNode): Pair<Int, Int> =
        node.meshcoreCommands().let { c ->
            c.count { it == MeshcoreProto.CMD_SEND_CHANNEL_DATA } to c.count { it == MeshcoreProto.CMD_SEND_CHANNEL_TXT_MSG }
        }

    /** Printable noise that does not deflate: a Nearby post of [LONG_POST] bytes, two bound parts on a MeshCore board. */
    private fun twoPartNoise(): String {
        val random = Random(1)
        return String(CharArray(LONG_POST) { (0x21 + random.nextInt(0x5E)).toChar() })
    }

    private fun boardName(node: LabNode): String = MeshcoreNames.forKey(checkNotNull(node.meshcoreBoard).key)

    /**
     * Alice, a contact of Bob's whose board key Bob's own board was taught, posts to Public. Bob's mirror holds it under
     * the post's id as a stranger called `Knit xxxx` — never resolved to Alice, her name or her face — and so does his
     * notification. A later stock post, heard by both, fences the mirrors: they hold the same ids. The one-row counts
     * after it are a smoke check only — nothing in this topology re-airs the post; the echo's dedup is the echo case's.
     */
    @Test
    fun aContactsPostIsMirroredAsAStrangerUnderItsBoardsName() =
        runBlocking {
            val air = lab.meshcoreAir()
            val stock = StockMeshcoreNode.on(lab, air, "stock")
            air.meshcoreEdit {
                link("alice", "bob")
                link("stock", "alice")
                link("stock", "bob")
            }
            val alice = lab.node("alice", quick, meshcore = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", quick, meshcore = air).apply { setDisplayName("Bob") }
            lab.meetOverMeshcore(alice, bob)
            assertTrue("a contact of bob's", bob.isContact(alice))
            val text = "north gate at noon"

            assertEquals(PublicPostOutcome.Queued, alice.postPublic(text))
            val own = alice.mirrored(text).single()
            val heard = awaitMirrored(bob, text)
            assertEquals("one id for both sides of the post", own.id, heard.id)
            assertStranger(bob, heard, boardName(alice))
            assertNull("alice's own row is hers", own.originNode)
            assertEquals(alice.nodeId, own.senderId)
            assertStrangerLine(awaitLine(bob, text), boardName(alice), listOf(alice, bob))

            stock.post("the fence", asName = "Stock")
            awaitMirrored(alice, "the fence")
            awaitMirrored(bob, "the fence")
            // Smoke only: nothing here re-airs the post (the echo case below proves the dedup).
            assertEquals("alice's own row", 1, alice.mirrored(text).size)
            assertEquals(1, bob.mirrored(text).size)
            assertEquals("the mirrors hold the same ids", alice.mirror().map { it.id }.toSet(), bob.mirror().map { it.id }.toSet())
            converge(alice, bob)
        }

    /**
     * A stock node posts under names that are someone's on Bob's phone: Alice's board's `Knit xxxx`, "You", Alice's display
     * name, a namesake's disambiguated label and a blank-named peer's alias. Each is stored as a stranger under exactly the
     * name it claimed — never Alice, never us — and its notification is keyed on that name's hash, quoted as unverified,
     * with no face. Alice is a pinned peer here, not a contact: a contact's sealed profile is spared the three others, so
     * the oracle would need each copy (the contact case is above). The bubble's clash line is `ChatViewModel`'s, which the
     * lab does not reach: its unit tests hold it.
     */
    @Test
    fun postsUnderNamesBobKnowsAreStrangersNeverAContactOrUs() =
        runBlocking {
            val air = lab.meshcoreAir()
            val stock = StockMeshcoreNode.on(lab, air, "stock")
            air.meshcoreEdit { link("stock", "bob") }
            val (alice, bob) = boards(air, "alice", "bob")
            val sam = lab.node("sam", board = false).apply { setDisplayName("Sam") }
            val samuel = lab.node("samuel", board = false).apply { setDisplayName("Sam") }
            val blank = lab.node("blank", board = false)
            lab.linkAll(bob to sam, bob to samuel, bob to blank)
            lab.awaitAcquainted(bob, sam, samuel, blank)
            val labels = bob.peers.labelIndex()
            val namesake = labels.labelFor(samuel.nodeId).text
            val alias = labels.labelFor(blank.nodeId).text
            assertTrue("a namesake's label is disambiguated: $namesake", namesake.startsWith("Sam (") && namesake.endsWith(")"))
            assertTrue("a blank name renders as an alias: $alias", alias.isNotBlank())
            val names = listOf(boardName(alice), "You", "Alice", namesake, alias)

            names.forEachIndexed { i, name -> stock.post("hostile $i", asName = name) }
            val knit = listOf(alice, bob, sam, samuel, blank)
            names.forEachIndexed { i, name ->
                // As the board advertises it: a label past 31 B is cut there (the 6c sweep's seed 924920534, a long alias).
                val claimed = StockMeshcoreNode.advertNameOf(name)
                assertStranger(bob, awaitMirrored(bob, "hostile $i"), claimed)
                assertStrangerLine(awaitLine(bob, "hostile $i"), claimed, knit)
            }
            converge(alice, bob, sam, samuel, blank)
        }

    /**
     * Our own post's echo: alice and bob each in range of a repeater alone, the repeater held. Alice posts; the repeater
     * parks its re-air, alice's board forgets the post (its 160-entry ring evicted, as a busy mesh would) and the repeater
     * goes. Bob hears the post, and alice's board hands her own echo up — awaited at her room, which the pipeline returned
     * from — and alice still holds one row, her own.
     */
    @Test
    fun ourPostEchoedAfterTheRingForgetsItStaysOneRow() =
        runBlocking {
            val air = lab.meshcoreAir(channel = true)
            air.meshcoreEdit { repeater("relay", "alice", "bob") }
            val (alice, bob) = boards(air, "alice", "bob")
            air.meshcoreEdit { hold("relay") }
            val handled = alice.meshcorePostsHandled.get()
            val text = "back by the bridge"

            assertEquals(PublicPostOutcome.Queued, alice.postPublic(text))
            val own = alice.mirrored(text).single()
            lab.await(1) { air.snapshot { air.parked("relay").count { it.type == "grp_txt" } } }
            air.meshcoreEdit {
                evictRing("alice")
                release("relay")
            }
            assertEquals(own.id, awaitMirrored(bob, text).id)
            lab.await(1) { alice.meshcorePostsHandled.get() - handled }
            val rows = alice.mirrored(text)
            assertEquals("one row: ${alice.rowsIn(Conversations.MESHCORE_PUBLIC)}", 1, rows.size)
            assertNull("still her own", rows.single().originNode)
            assertEquals(alice.nodeId, rows.single().senderId)
            assertTrue("no line for her own words", alice.meshcoreLines().none { it.body == text })
            converge(alice, bob)
        }

    /**
     * ADR 2026-11.f4d3: the board's name is its owner's handle. Alice names hers in Knit — 31 B, the firmware's longest, an
     * emoji in it — and posts under it: refused one byte past the name's own cap (127, never 149), taken at 127; Bob mirrors
     * it as a stranger under exactly that name, under the id Alice's own row took (the name's bytes in the key); and her own
     * echo, re-aired after her board's ring forgot it, dedups to her one row.
     */
    @Test
    fun aPostUnderTheOwnersHandleKeepsOneIdItsEchoDedupsAndItCarriesTheNamesCap() =
        runBlocking {
            val air = lab.meshcoreAir(channel = true)
            air.meshcoreEdit { repeater("relay", "alice", "bob") }
            val (alice, bob) = boards(air, "alice", "bob")
            val handle = "Ana \uD83C\uDF32 from the north ridge!!"
            assertEquals(31, handle.encodeToByteArray().size)
            assertEquals(MeshcoreRenameResult.DONE, checkNotNull(alice.meshcore).renameMeshcore(handle))
            // The rename's Ready reaches the face on the plane's board-state collector, after the call returns: awaited there,
            // as the composer reads it.
            assertTrue(
                "the face never carried the handle",
                lab.tryAwait(1) { if (alice.meshcoreHandle()?.text == handle) 1 else 0 },
            )
            val face = checkNotNull(alice.meshcore).status.value.meshcore
            assertEquals("the board's name is no longer the default", false, face?.namedDefault)
            val text = "north gate " + "x".repeat(116)
            assertEquals(127, text.encodeToByteArray().size)

            assertEquals(PublicPostOutcome.Refused(PublicPostRefusal.TOO_LARGE), alice.postPublic(text + "x"))
            air.meshcoreEdit { hold("relay") }
            val handled = alice.meshcorePostsHandled.get()
            assertEquals(PublicPostOutcome.Queued, alice.postPublic(text))
            val own = alice.mirrored(text).single()
            lab.await(1) { air.snapshot { air.parked("relay").count { it.type == "grp_txt" } } }
            air.meshcoreEdit {
                evictRing("alice")
                release("relay")
            }
            val heard = awaitMirrored(bob, text)
            assertEquals("one id for both sides of the post", own.id, heard.id)
            assertStranger(bob, heard, handle)
            lab.await(1) { alice.meshcorePostsHandled.get() - handled }
            assertEquals("her echo is her own row: ${alice.rowsIn(Conversations.MESHCORE_PUBLIC)}", 1, alice.mirrored(text).size)
            assertNull(alice.mirrored(text).single().originNode)
            converge(alice, bob)
        }

    /**
     * No relay: carol is linked to bob and has no board. A stock post lands in bob's mirror; once his room returned from it
     * and carol's router has handled all he sent, neither custody moved, nothing about the post crossed the link either
     * way and bob's board was asked to air nothing. Bob's two-part Nearby post then crosses the link and
     * airs — the fence, awaited at its own `parts=2` line, which the short heard post could never write. His pacer
     * dequeues by class, then oldest first, so a room-class frame queued for the heard post (a Knit fan-out, or a re-post
     * to Public) would have been written before it. Then carol holds no MeshCore row, bob's board took the fence alone
     * and no Public post, and no plane line names the post. Presence keys are off, so nothing of the meeting's own lands
     * in the window.
     *
     * Neither node's originated count is compared: behind bob's composite, his manager's link-up pass (the profile push, an
     * origination the link's crossing memo then skips) can still be running when `awaitAcquainted` returns, and it moved
     * bob's count 3 → 4 in the window (seed 991270176); carol's moved the same way once (seed 466670285), most likely set
     * off by it. What crossed the link
     * is read instead.
     */
    @Test
    fun aPublicPostReachesNoPhoneOffTheBoard() =
        runBlocking {
            val still = quick.copy(sendsPresenceKeys = false)
            val air = lab.meshcoreAir()
            val stock = StockMeshcoreNode.on(lab, air, "stock")
            air.meshcoreEdit { link("stock", "bob") }
            val bob = lab.node("bob", still, meshcore = air).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", still, board = false).apply { setDisplayName("Carol") }
            lab.link(bob, carol)
            lab.awaitAcquainted(bob, carol)
            val custody = listOf(bob.custodyFingerprint(), carol.custodyFingerprint())
            val carolSentBefore = carol.transport.sent.size
            val sent = bob.transport.sent.size
            val aired = boardSends(bob)
            val fanouts = bob.loraTx(ROOM_FANOUT)
            val fencesBefore = bob.loraTx("$ROOM_FANOUT parts=2")
            val handled = bob.meshcorePostsHandled.get()

            stock.post("only on the air", asName = "Stock")
            val heard = awaitMirrored(bob, "only on the air")
            lab.await(1) { bob.meshcorePostsHandled.get() - handled }
            withContext(Dispatchers.Default) { carol.transport.awaitInboundDrained() }
            val carolSent = carol.transport.sent.drop(carolSentBefore)
            assertTrue("carol sent: $carolSent", carolSent.none { " chat " in it || heard.id in it })
            assertEquals("custody", custody, listOf(bob.custodyFingerprint(), carol.custodyFingerprint()))
            val crossed = bob.transport.sent.drop(sent)
            assertTrue("over the link: $crossed", crossed.none { " chat " in it || heard.id in it })
            assertEquals("bob's board was asked to send", aired, boardSends(bob))

            // The fence goes in two parts, which the short heard post never would: its own `lora tx` line is one nothing else
            // in the window can write. Written in class-then-FIFO order, so anything queued for the heard post went first.
            val fence = twoPartNoise()
            assertTrue(bob.sendRoom(fence))
            lab.await(1) { if (carol.roomPosts()[bob.nodeId]?.contains(fence) == true) 1 else 0 }
            lab.await(1) { bob.loraTx("$ROOM_FANOUT parts=2") - fencesBefore }
            assertEquals("bob's room fan-outs, the fence alone: ${bob.loraLog}", fanouts + 1, bob.loraTx(ROOM_FANOUT))
            assertEquals(
                "no re-post to Public",
                aired.second,
                boardSends(bob).second,
            )
            assertTrue("bob's plane named the heard post: ${bob.loraLog}", bob.loraLog.none { heard.id in it })
            assertTrue("carol's mirror: ${carol.mirror().map { it.body }}", carol.mirror().isEmpty())
            assertNull(carol.row(heard.id))
            assertFalse(heard.id in carol.custodyIds())
            assertFalse(heard.id in bob.custodyIds())
            lab.assertConverged(listOf(bob, carol), atLeast = 1) { Conversations.NEARBY }
        }

    /**
     * Offline: bob's phone is away (a process restart, the board left on) while the stock node posts twenty, stamped hours
     * back and newest-first. His board's offline queue keeps the last [OFFLINE_QUEUE] and drops the oldest. Back, his
     * session drains it: each of those lands once — awaited at his room — on his own clock (arrival, never the sender's
     * stamp), shown in the order the board handed them over, and a live post afterwards fences the four the board dropped:
     * none of them is there.
     */
    @Test
    fun postsHeardWhileThePhoneWasAwayLandOnceTheQueuesLastSixteen() =
        runBlocking {
            val air = lab.meshcoreAir()
            val stock = StockMeshcoreNode.on(lab, air, "stock")
            air.meshcoreEdit { link("stock", "bob") }
            val (alice, bob) = boards(air, "alice", "bob")
            val board = checkNotNull(bob.meshcoreBoard)
            air.meshcoreEdit { board.offlineCap = OFFLINE_QUEUE }
            val handled = bob.meshcorePostsHandled.get()
            val stamp = air.snapshot { board.clockSecs } - HOURS_BACK_SECS
            val away = (1..AWAY_POSTS).map { "away $it" }
            val left = lab.clock.now()

            bob.restart {
                away.forEachIndexed { i, text -> stock.post(text, asName = "Stock", stampSecs = stamp - i) }
            }
            assertEquals("the board dropped the oldest", AWAY_POSTS - OFFLINE_QUEUE, air.snapshot { board.offlineDropped })
            lab.await(OFFLINE_QUEUE) { bob.meshcorePostsHandled.get() - handled }
            stock.post("live again", asName = "Stock")
            awaitMirrored(bob, "live again")

            val kept = away.takeLast(OFFLINE_QUEUE)
            // In the room's own display order (sentAt, then id): the board's, oldest first, though the stamps run newest first.
            assertEquals(
                "the queue's last sixteen, each once, in drain order",
                kept,
                bob
                    .mirror()
                    .map { it.body }
                    .filter { it in away },
            )
            away.dropLast(OFFLINE_QUEUE).forEach { assertTrue("$it was dropped by the board", bob.mirrored(it).isEmpty()) }
            kept.forEach { text ->
                val row = bob.mirrored(text).single()
                assertTrue("\"$text\" ordered by its arrival, not its stamp: ${row.sentAt}", row.sentAt >= left)
            }
            converge(alice, bob)
        }

    /**
     * The room switch at bob, as the plane's own room gate reads it: off, a stock post is dropped where it lands (counted,
     * awaited at the count); back on, a later post lands — the fence — and the one heard while off is not there, nor was it
     * notified.
     */
    @Test
    fun aPostHeardWithTheRoomOffIsDroppedAndALaterOneLands() =
        runBlocking {
            val air = lab.meshcoreAir()
            val stock = StockMeshcoreNode.on(lab, air, "stock")
            air.meshcoreEdit { link("stock", "bob") }
            val (alice, bob) = boards(air, "alice", "bob")
            val counters = bob.meshcoreHooks.counters

            bob.settings.setMeshcoreRoomEnabled(false)
            lab.await(1) { if (roomOff(bob)) 1 else 0 }
            val off = counters.publicRoomOff.get()
            stock.post("while off", asName = "Stock")
            lab.await(1) { (counters.publicRoomOff.get() - off).toInt() }

            bob.settings.setMeshcoreRoomEnabled(true)
            lab.await(1) { if (roomOff(bob)) 0 else 1 }
            stock.post("back on", asName = "Stock")
            awaitMirrored(bob, "back on")
            assertTrue("dropped where it landed", bob.mirrored("while off").isEmpty())
            assertTrue("never notified", bob.meshcoreLines().none { it.body == "while off" })
            converge(alice, bob)
        }

    /**
     * Whether [node]'s MeshCore plane reads its room switch as off — the gate a heard text and a post both ask
     * (`LoraConfig.room`, folded from `meshcoreRoomEnabled` on a collector of the plane's own). Asked with an empty post,
     * which every later guard refuses too (too large), so the probe never queues or claims the floor.
     */
    private suspend fun roomOff(node: LabNode): Boolean = checkNotNull(node.meshcore).postPublic("").refusal == PublicPostRefusal.ROOM_OFF

    /**
     * An own post that ends without airing: bob's board goes out of his phone's range at the very write (the write fails as
     * a link lost under it does, and every redial fails), so the post is owed, not ended. Past [LoraFramePolicy.FRESH_MS] on
     * the lab's calendar the board is back in range — powered throughout, so the duty law sees no reboot — and the pacer
     * takes the post up again, finds it stale and never writes it: the row reads "Not sent", and the board never saw it.
     */
    @Test
    fun anOwnPostHeldPastItsFreshnessWhileTheBoardIsAwayReadsNotSent() =
        runBlocking {
            val air = lab.meshcoreAir()
            val (alice, bob) = boards(air, "alice", "bob", limits = quick.copy(meshcoreBackoffMs = BACKOFF_MS))
            val board = checkNotNull(bob.meshcoreBoard)
            val armed = AtomicBoolean(true)
            air.meshcoreEdit {
                board.ifaces.filterIsInstance<FakeNusChannel>().forEach { ch ->
                    ch.writeResult = { frame ->
                        if (frame[0].toInt() == MeshcoreProto.CMD_SEND_CHANNEL_TXT_MSG && armed.compareAndSet(true, false)) {
                            bob.meshcoreOutOfRange(true)
                            GattResult.Closed
                        } else {
                            GattResult.Ok(Unit)
                        }
                    }
                }
            }
            val text = "the ford is passable"

            assertEquals(PublicPostOutcome.Queued, bob.postPublic(text))
            val id = bob.mirrored(text).single().id
            lab.await(1) { if (armed.get()) 0 else 1 }
            lab.clock.advance(LoraFramePolicy.FRESH_MS + MINUTE_MS)
            air.meshcoreEdit { bob.meshcoreOutOfRange(false) }
            bob.awaitMeshcoreBack()
            val ended =
                lab.tryAwait(1) {
                    if (bob.row(id)?.let(DeliveryStatus::of) == DeliveryStatus.NotSent) 1 else 0
                }
            assertTrue(
                "never marked not sent: ${bob.row(id)?.let(DeliveryStatus::of)}, ${bob.loraLog.filter { "public" in it }}\n" +
                    lab.report(listOf(bob)),
                ended,
            )
            assertEquals(
                1L,
                bob.meshcoreHooks.counters.publicPostsEnded
                    .get(),
            )
            assertTrue(bob.loraLog.contains("lora public post stale at the write"))
            assertFalse("the board never took it", MeshcoreProto.CMD_SEND_CHANNEL_TXT_MSG in bob.meshcoreCommands())
            converge(alice, bob)
        }

    /**
     * A fork's reaction in a bridged room (ADR 2026-11.gsqu's Stage 6 amendment: anyone can derive a heard row's id off the
     * air). Mallory, a Knit identity, reacts to a stranger's post in bob's MeshCore room and to one in carol's Meshtastic
     * room. Each holder counts it outside the thread — awaited at the count — draws nothing and notifies nothing, though the
     * row sits in its own sender column. Both reaction frames are still carried — each is in both holders' custody — and,
     * once mallory's links come up again, the oracle holds.
     */
    @Test
    fun aForksReactionInEitherBridgedRoomIsCountedAndNeverDrawn() =
        runBlocking {
            val air = lab.meshcoreAir()
            val stock = StockMeshcoreNode.on(lab, air, "stock")
            air.meshcoreEdit { link("stock", "bob") }
            val bob = lab.node("bob", quick, meshcore = air).apply { setDisplayName("Bob") }
            val carol = lab.node("carol", quick, air = MeshLab.quickAir()).apply { setDisplayName("Carol") }
            val mallory = lab.node("mallory", board = false).apply { setDisplayName("Mallory") }
            lab.linkAll(bob to carol, bob to mallory, carol to mallory)
            lab.awaitAcquainted(bob, carol, mallory)

            stock.post("a stranger's", asName = "Stock")
            val meshcore = awaitMirrored(bob, "a stranger's").id
            checkNotNull(carol.boardLink).deliverPublicText(from = STOCK_MESHTASTIC_NODE, body = "a neighbour's")
            lab.await(1) { carol.rowsIn(Conversations.MESHTASTIC).count { it.startsWith("a neighbour's@") } }
            val meshtastic =
                carol.messages
                    .observeNewestMessages(Conversations.MESHTASTIC, MeshLab.WINDOW)
                    .first()
                    .single { it.body == "a neighbour's" }
                    .id
            val outside = listOf(bob, carol).map { it.drops(DropReason.REACTION_OUTSIDE_THREAD) }

            val reactions = listOf(meshcore, meshtastic).map { mallory.reactTo(it) }
            lab.await(1) { (bob.drops(DropReason.REACTION_OUTSIDE_THREAD) - outside[0]).toInt() }
            lab.await(1) { (carol.drops(DropReason.REACTION_OUTSIDE_THREAD) - outside[1]).toInt() }
            assertEquals(emptySet<Pair<String, String>>(), bob.reactions(meshcore))
            assertEquals(emptySet<Pair<String, String>>(), bob.shownReactions(Conversations.MESHCORE_PUBLIC, meshcore))
            assertEquals(emptySet<Pair<String, String>>(), carol.reactions(meshtastic))
            assertEquals(emptySet<Pair<String, String>>(), carol.shownReactions(Conversations.MESHTASTIC, meshtastic))
            assertFalse(bob.reactionNotified(meshcore, mallory))
            assertFalse(carol.reactionNotified(meshtastic, mallory))
            val carried =
                lab.tryAwait(1) { if (listOf(bob, carol).all { n -> n.custodyIds().containsAll(reactions) }) 1 else 0 }
            assertTrue("the reactions are carried: ${listOf(bob, carol).map { it.custodyIds().intersect(reactions.toSet()) }}", carried)
            // Mallory keeps none of her own floods; the link-ups' digests hand them back to her.
            listOf(bob, carol).forEach { n ->
                lab.unlink(n, mallory)
                lab.link(n, mallory)
            }
            converge(bob, carol, mallory)
        }

    /** The claimed names' reactions [node] holds on [messageId], as (reactor, emoji). */
    private suspend fun claimedOn(
        node: LabNode,
        messageId: String,
    ): Set<Pair<String, String>> = node.reactions(messageId).filterTo(HashSet()) { it.first.startsWith("mc:") }

    /** The MeshCore room's notification lines [node] posted for reactions to [messageId]: one key, however many reactors. */
    private fun reactionLines(
        node: LabNode,
        messageId: String,
    ): List<NotifMessage> = node.meshcoreLines().filter { it.key == meshcoreReactionKey(messageId) }

    /**
     * MeshCore One's reaction (ADR 2026-11.gsqu's 2026-11-21 amendment), heard. Alice posts; Alice's own row and Bob's
     * mirror of it carry one tag, each computed by its own plane (hers over what she queued, his over what his board
     * drained). A stock node reacts in MeshCore One's form: Alice holds it on her post under the claimed name, no row of
     * its own, one notification line; Bob holds it on his mirror and is not notified (not his words). The stock node
     * reacts again (a later stamp, so a new packet): nothing more is drawn or notified. Then a flood of fresh names on the
     * one post: Alice keeps at most [ReactionRepository.MESHCORE_REACTORS_PER_POST] claimed names on it, evicting the
     * oldest, and every line it costs her is the post's one line.
     */
    @Test
    fun aMeshcoreOneReactionToOurPostLandsOnceAndAFloodOfNamesIsBounded() =
        runBlocking {
            val air = lab.meshcoreAir()
            val stock = StockMeshcoreNode.on(lab, air, "stock")
            air.meshcoreEdit {
                link("stock", "alice")
                link("stock", "bob")
                link("alice", "bob")
            }
            val (alice, bob) = boards(air, "alice", "bob")
            val text = "north gate at noon"

            assertEquals(PublicPostOutcome.Queued, alice.postPublic(text))
            val own = alice.mirrored(text).single()
            val heard = awaitMirrored(bob, text)
            val tag = checkNotNull(own.meshcoreTag) { "alice's own row carries no tag" }
            assertEquals("one tag for both sides of the post", tag, heard.meshcoreTag)
            val reaction = "@[${boardName(alice)}]👍\n$tag"
            val handled = alice.meshcorePostsHandled.get()
            val bobHandled = bob.meshcorePostsHandled.get()

            stock.post(reaction, asName = "Stock")
            lab.await(1) { claimedOn(alice, own.id).size }
            lab.await(1) { claimedOn(bob, heard.id).size }
            assertEquals(setOf("mc:Stock" to "👍"), claimedOn(alice, own.id))
            assertEquals(setOf("mc:Stock" to "👍"), claimedOn(bob, heard.id))
            assertEquals(setOf("mc:Stock" to "👍"), alice.shownReactions(Conversations.MESHCORE_PUBLIC, own.id))
            lab.await(1) { reactionLines(alice, own.id).size }
            assertTrue("never a row: ${alice.mirror().map { it.body }}", alice.mirror().none { it.body.contains(tag) })
            assertTrue(bob.mirror().none { it.body.contains(tag) })
            // Bob's notify decision is behind his handled count, not his reaction row.
            lab.await(1) { bob.meshcorePostsHandled.get() - bobHandled }
            assertTrue("not bob's words", reactionLines(bob, heard.id).isEmpty())

            stock.post(reaction, asName = "Stock", stampSecs = air.snapshot { checkNotNull(alice.meshcoreBoard).clockSecs } + LATER_SECS)
            lab.await(2) { alice.meshcorePostsHandled.get() - handled }
            assertEquals("the same emoji again notifies nothing", 1, reactionLines(alice, own.id).size)

            val evicted = alice.drops(DropReason.MESHCORE_REACTION_EVICTED)
            val names = (1..FLOOD_NAMES).map { "n$it" }
            names.forEach { stock.post(reaction, asName = it) }
            lab.await(2 + FLOOD_NAMES) { alice.meshcorePostsHandled.get() - handled }
            val kept = claimedOn(alice, own.id)
            assertEquals("the bound: $kept", ReactionRepository.MESHCORE_REACTORS_PER_POST, kept.size)
            assertTrue("the newest name is kept", "mc:${names.last()}" to "👍" in kept)
            assertFalse("the oldest went", "mc:Stock" to "👍" in kept)
            assertEquals(
                (FLOOD_NAMES + 1 - ReactionRepository.MESHCORE_REACTORS_PER_POST).toLong(),
                alice.drops(DropReason.MESHCORE_REACTION_EVICTED) - evicted,
            )
            assertEquals(
                "one line for the post, never one per name",
                setOf(meshcoreReactionKey(own.id)),
                alice
                    .meshcoreLines()
                    .mapNotNull { it.key }
                    .filter { it.startsWith("reaction:") }
                    .toSet(),
            )
            converge(alice, bob)
        }

    /**
     * MeshCore One's reaction, sent. A stock node posts; Alice reacts to her mirror of it from the picker. Her board airs
     * exactly MeshCore One's text, so Bob — whose plane computed the post's tag on its own — holds it on his mirror under
     * her board's claimed name, never as a row; Alice holds hers as her own, and no Knit frame crossed for it.
     */
    @Test
    fun ourMeshcoreReactionAirsMeshcoreOnesTextAndLandsOnTheSamePostElsewhere() =
        runBlocking {
            val air = lab.meshcoreAir()
            val stock = StockMeshcoreNode.on(lab, air, "stock")
            air.meshcoreEdit {
                link("stock", "alice")
                link("stock", "bob")
                link("alice", "bob")
            }
            val (alice, bob) = boards(air, "alice", "bob")
            stock.post("the ford is passable", asName = "Stock")
            val atAlice = awaitMirrored(alice, "the ford is passable")
            val atBob = awaitMirrored(bob, "the ford is passable")
            assertEquals(atAlice.meshcoreTag, atBob.meshcoreTag)
            val aired = boardSends(alice).second
            val sent = alice.transport.sent.size

            assertEquals(MeshcoreReactionOutcome.Queued, alice.reactMeshcore(atAlice.id, "🎉"))

            lab.await(1) { claimedOn(bob, atBob.id).size }
            assertEquals(setOf("mc:${boardName(alice)}" to "🎉"), claimedOn(bob, atBob.id))
            assertEquals(setOf(alice.nodeId to "🎉"), alice.reactions(atAlice.id))
            assertEquals("one Public post", aired + 1, boardSends(alice).second)
            assertTrue(bob.mirror().none { it.body.contains("🎉") })
            assertTrue(
                "no Knit frame: ${alice.transport.sent.drop(sent)}",
                alice.transport.sent
                    .drop(sent)
                    .none { atAlice.id in it },
            )
            converge(alice, bob)
        }

    /** A cleartext reaction to [messageId] signed by this node, as a fork sends whatever the room: returns its frame id. */
    private suspend fun LabNode.reactTo(messageId: String): String {
        val id = FrameId.new()
        floodSigned(
            RelayEnvelope(
                type = FrameType.REACTION,
                id = id,
                senderId = nodeId,
                sentAt = lab.clock.now(),
                payload = WireCodec.encodePayload(ReactionContent(messageId = messageId, emoji = "👍")),
            ),
        )
        return id
    }

    private companion object {
        /** The `lora tx` label of a room post's fan-out on the board ([LabNode.loraTx]). */
        const val ROOM_FANOUT = "fanout:chat"

        /** A Nearby post past one packet: two bound parts (as `MeshcoreRoomLabTest`'s). */
        const val LONG_POST = 150

        /** `OFFLINE_QUEUE_SIZE`'s source default (`MyMesh.h:61-62`), which four board variants keep. */
        const val OFFLINE_QUEUE = 16
        const val AWAY_POSTS = 20
        const val HOURS_BACK_SECS = 3 * 3_600L
        const val MINUTE_MS = 60_000L

        /** Bob's session redials half a second after his board comes back, not the field's 5 s. */
        const val BACKOFF_MS = 500L

        /** A later stamp for the same reaction: a new packet, which the boards' seen rings do not drop as the first. */
        const val LATER_SECS = 3_600L

        /** Fresh names a stock node reacts under in the flood: past the per-post bound. */
        const val FLOOD_NAMES = 40

        /** A stock Meshtastic node's number on carol's board. */
        const val STOCK_MESHTASTIC_NODE = 0x0A0B_0C0Du
    }
}
