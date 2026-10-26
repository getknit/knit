package app.getknit.knit.mesh.lab

import app.getknit.knit.data.VoiceAudio
import app.getknit.knit.data.adts
import app.getknit.knit.data.message.Conversations
import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.data.settings.BluetoothDownloadLimit
import app.getknit.knit.mesh.BlobExchange
import app.getknit.knit.mesh.lora.FakeMeshtasticAir
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import app.getknit.knit.mesh.sha256Hex
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
 * Bytes that ride beside a frame: the `blobreq` / file pull (`BlobExchange`, `MeshBlobStore`), the sealed
 * attachment whose frame names only the ciphertext hash (ADR 035), the carrier that eager-pulls what it
 * custodies so an absent recipient can fetch it later, and screening on the receiver (knit-next#30). The
 * oracle's attachment check reads the bytes back on every node and compares the plaintext.
 */
@RunWith(RobolectricTestRunner::class)
class AttachmentLabTest {
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

    @Test
    fun anImageDmArrivesWithItsBytesAndAScreenedVerdict() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)

            val picture = Random(1).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "a picture", to = bob))
            val id = alice.ownMessageId(alice.dmWith(bob), "a picture")

            lab.assertConverged(listOf(alice, bob), atLeast = 1) { it.dmWith(if (it === alice) bob else alice) }
            assertTrue(picture.contentEquals(bob.attachmentPlain(bob.dmWith(alice), id)))
            // A sealed picture is screened once it is held and decrypted (`InboundPipeline.onObtained`), so the held
            // bytes the oracle waited on do not mean the verdict exists yet.
            assertTrue(
                "bob screened what he received",
                lab.tryAwait(1) { if (bob.attachmentScreened(bob.dmWith(alice), id)) 1 else 0 },
            )
            assertEquals(
                "the frame names the ciphertext, never the picture",
                false,
                alice.attachmentHash(alice.dmWith(bob), id) ==
                    app.getknit.knit.mesh
                        .sha256Hex(picture),
            )
        }

    /**
     * Carol is away when Alice sends her a picture; Bob custodies the frame and pulls its bytes at once so
     * that, with Alice gone too, Carol can fetch both from him alone.
     */
    @Test
    fun anImageForSomeoneAwayIsCarriedWithItsBytesAndPulledFromTheCarrier() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)

            lab.unlink(bob, carol)
            val picture = Random(2).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "for carol", to = carol))
            val id = alice.ownMessageId(alice.dmWith(carol), "for carol")
            val hash = checkNotNull(alice.attachmentHash(alice.dmWith(carol), id))
            assertTrue("bob never pulled the bytes he carries the frame for", lab.tryAwait(1) { if (bob.blobs.exists(hash)) 1 else 0 })
            lab.unlink(alice, bob)

            lab.link(bob, carol)
            lab.assertConverged(listOf(carol), atLeast = 1, carriers = listOf(bob)) { it.dmWith(alice) }
            assertTrue(picture.contentEquals(carol.attachmentPlain(carol.dmWith(alice), id)))

            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, carol), atLeast = 1, carriers = listOf(bob)) { it.dmWith(if (it === alice) carol else alice) }
        }

    /** The room is the one cleartext surface: every receiver pulls the picture and screens it itself. */
    @Test
    fun aRoomImageIsScreenedOnEveryReceiver() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)

            val picture = Random(3).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "room picture"))
            val id = alice.ownMessageId(Conversations.NEARBY, "room picture")

            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { Conversations.NEARBY }
            listOf(bob, carol).forEach { n ->
                // A room picture is key-less, so `MeshBlobStore.ingest` screens it before it is held (#109).
                assertTrue(
                    "${n.name} screened the room picture",
                    lab.tryAwait(1) { if (n.attachmentScreened(Conversations.NEARBY, id)) 1 else 0 },
                )
                assertTrue(picture.contentEquals(n.attachmentPlain(Conversations.NEARBY, id)))
            }
        }

    /**
     * ADR 2026-10.79j9: the room's composer offers no mic and no paperclip, but a modified client can still name a voice
     * note or a file in a room post. Every receiver drops it from its row and custody's carrier column, so no re-arm and
     * no re-link ever pulls it: not one copy crosses a link. The voice note's post had nothing else, so it keeps no row.
     * The frame itself is custodied everywhere, and the picture beside it is the control, pulled by every receiver.
     */
    @Test
    fun aRoomVoiceNoteOrFileFromAModifiedClientIsNeverPulled() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)

            val note = adts(frames = 216)
            val doc = Random(5).nextBytes(2_048)
            val picture = Random(6).nextBytes(4_096)
            assertTrue(alice.sendVoice(note))
            assertTrue(alice.sendFile(doc, "menu.pdf", "application/pdf", text = "the menu"))
            assertTrue(alice.sendImage(picture, "control"))
            val noteId = alice.ownMessageId(Conversations.NEARBY, "")
            val docId = alice.ownMessageId(Conversations.NEARBY, "the menu")
            val refused = setOf(sha256Hex(note), sha256Hex(doc))
            assertEquals(
                refused,
                setOfNotNull(alice.attachmentHash(Conversations.NEARBY, noteId), alice.attachmentHash(Conversations.NEARBY, docId)),
            )

            // Alice's own rows name the bytes she sent, so she is a carrier here: the receivers are what must agree.
            lab.assertConverged(listOf(bob, carol), atLeast = 2, carriers = listOf(alice)) { Conversations.NEARBY }
            // The re-arm's two doors: the 60 s re-offer and a fresh link-up.
            listOf(alice, bob, carol).forEach { it.reoffer() }
            lab.unlink(bob, carol)
            lab.link(bob, carol)
            lab.assertConverged(listOf(bob, carol), atLeast = 2, carriers = listOf(alice)) { Conversations.NEARBY }

            listOf(bob, carol).forEach { n ->
                assertTrue("${n.name} kept a row for the voice note", n.decrypted(Conversations.NEARBY).none { it.first == noteId })
                assertNull("${n.name}'s row names the file", n.attachmentHash(Conversations.NEARBY, docId))
                assertTrue("${n.name} never pulled the control", n.holdsBlob(sha256Hex(picture)))
                assertTrue("${n.name}'s re-arm wants ${n.carrierWants()}", n.carrierWants().none { it in refused })
                // The live carrier pull's want lives in memory only: wanted, held or column-named covers every door.
                assertTrue("${n.name} wants a refused blob", n.manager.wantedBlobs().none { it in refused })
                refused.forEach { hash -> assertFalse("${n.name} holds a refused blob", n.holdsBlob(hash)) }
                assertTrue("${n.name} lost the frames from custody", n.custodyIds().containsAll(listOf(noteId, docId)))
            }
            listOf(alice, bob, carol).forEach { n ->
                val copies = n.transport.files.filter { line -> refused.any { line.contains(it) } }
                assertTrue("${n.name} served a refused blob: $copies", copies.isEmpty())
            }
        }

    @Test
    fun aGroupPhotoCrossesAsABlob() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            val groupId = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(groupId, "founded"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }

            val photo = Random(4).nextBytes(4_096)
            alice.setGroupPhoto(groupId, photo)

            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }
            val hash = checkNotNull(bob.group(groupId)?.photoHash)
            listOf(bob, carol).forEach { n ->
                assertTrue("${n.name} never pulled the group photo", lab.tryAwait(1) { if (n.blobs.exists(hash)) 1 else 0 })
                assertTrue(photo.contentEquals(n.blobs.bytes(hash)))
            }
        }

    /**
     * #108 (ADR 2026-09.nxcq): Bob hears of Alice's new photo but cannot reach its bytes, while Carol pulls them
     * and shows it. Bob's next message re-asserts the photo he has decided on — the new one — so Carol keeps it
     * and writes nothing. Before the fix Bob's row held the old hash at the new clock until his pull landed, his
     * message carried that pair, the clock tie went to it, and Carol switched back under a "changed the photo"
     * line moved to the end of her thread and credited to Bob.
     */
    @Test
    fun aMemberStillPullingANewGroupPhotoNeverSwitchesTheOthersBack() =
        runBlocking {
            val (alice, bob, carol) = groupOfThree()
            val groupId = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(groupId, "founded"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }
            val old = Random(5).nextBytes(4_096)
            val oldHash = sha256Hex(old)
            alice.setGroupPhoto(groupId, old)
            awaitShown(groupId, oldHash, bob, carol)

            // Bob's links are slow: headers cross, the bytes wait.
            alice.transport.holdFiles(bob.transport)
            carol.transport.holdFiles(bob.transport)
            val new = Random(6).nextBytes(4_096)
            val newHash = sha256Hex(new)
            alice.setGroupPhoto(groupId, new)
            lab.await(1) { if (bob.group(groupId)?.photoHash == newHash) 1 else 0 }
            awaitShown(groupId, newHash, carol)
            assertEquals("Bob still shows the photo whose bytes he holds", oldHash, bob.group(groupId)?.photoShownHash)
            val oldNotice = "gphoto:$groupId:$oldHash"
            val before = checkNotNull(carol.notice(groupId, oldNotice)) { "Carol never noted the first photo" }

            assertTrue(bob.sendGroup(groupId, "still pulling"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 2) { groupId }
            assertEquals("Carol keeps the new photo", newHash, carol.group(groupId)?.photoShownHash)
            assertEquals("and the line for the old one is untouched", before, carol.notice(groupId, oldNotice))

            alice.transport.releaseFiles(bob.transport)
            carol.transport.releaseFiles(bob.transport)
            awaitShown(groupId, newHash, bob)
            assertTrue(new.contentEquals(bob.blobs.bytes(newHash)))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 2) { groupId }
        }

    /**
     * The row is the want: Bob dies while his pull of the new photo is stuck, and no group frame reaches him
     * after the relaunch (the relink loses Carol's relays toward him). The database re-arm
     * (`MeshManager.rewantMissingBlobs`) still asks for the photo he decided on and does not show, and it lands.
     */
    @Test
    fun aGroupPhotoPullLostToARestartIsAskedForAgain() =
        runBlocking {
            val (alice, bob, carol) = groupOfThree()
            val groupId = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(groupId, "founded"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }

            alice.transport.holdFiles(bob.transport)
            carol.transport.holdFiles(bob.transport)
            val photo = Random(8).nextBytes(4_096)
            val hash = sha256Hex(photo)
            alice.setGroupPhoto(groupId, photo)
            lab.await(1) { if (bob.group(groupId)?.photoHash == hash) 1 else 0 }
            assertEquals(null, bob.group(groupId)?.photoShownHash)
            // Carol's own pull lands first: a want still open at the relink would re-ask the newcomer Bob, and his
            // answer to that ask (`want` on a hash he lacks) would land the photo without the database re-arm.
            awaitShown(groupId, hash, carol)

            bob.restart()
            // Alice (always) and Carol (when her copy landed before Bob's ask) served onto the held pipe, and a
            // holder refuses the same (hash, peer) for SERVE_MEMO_MS — a memo Bob's restart does not clear. Past
            // it, the relink's newcomer re-ask is the first one either can answer.
            lab.clock.advance(BlobExchange.SERVE_MEMO_MS)
            // Carol's relay of Alice's update reads its targets after the router's jitter, which can run out after
            // this relink, and the relaunched Bob's empty seen set would take it and re-arm the pull on the frame
            // path — the path this scenario must keep out. So the air loses Carol's relays toward him. His custody
            // took the update with his row, so no serve brings it either, and what else he needs reaches him direct.
            alice.transport.connect(bob.transport, publish = false)
            carol.transport.connect(bob.transport, publish = false, lossy = { it.hops > 0 })
            listOf(alice, bob, carol).forEach { it.transport.publishNeighbors() }
            awaitShown(groupId, hash, bob)
            assertTrue(photo.contentEquals(bob.blobs.bytes(hash)))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }
        }

    private suspend fun groupOfThree(): Triple<LabNode, LabNode, LabNode> {
        val alice = lab.node("alice").apply { setDisplayName("Alice") }
        val bob = lab.node("bob").apply { setDisplayName("Bob") }
        val carol = lab.node("carol").apply { setDisplayName("Carol") }
        lab.linkAll(alice to bob, bob to carol, alice to carol)
        lab.awaitAcquainted(alice, bob, carol)
        return Triple(alice, bob, carol)
    }

    /** Every one of [nodes] shows [hash] as [groupId]'s photo. */
    private suspend fun awaitShown(
        groupId: String,
        hash: String,
        vararg nodes: LabNode,
    ) = lab.await(nodes.size) { nodes.count { it.group(groupId)?.photoShownHash == hash } }

    /**
     * The 2026-09-15 field trial (ADR 2026-09.ptv8): a picture posted in the room reaches a phone that only
     * the board can hear, so its row arrives with a hash and no bytes, and the want it parks has nobody to
     * go to. The phone stays alone past `BlobExchange`'s 30-min fetch TTL — a real walk — and the sweep the
     * prune loop and the heartbeat run reclaims the want. When a neighbour finally links, the re-ask must
     * still name the picture: the database, not the in-memory memo, is what says it is missing. Before the
     * fix the row spun until the app was restarted; here Bob's re-link is the whole cure.
     */
    @Test
    fun aPictureHeardOverTheBoardWhileAloneIsStillFetchedAfterALongIsolation() =
        runBlocking {
            val air = FakeMeshtasticAir()
            val alice = lab.node("alice", air = air).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", air = air).apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            lab.awaitCustodyParity(alice, bob)
            lab.unlink(alice, bob)

            val picture = Random(5).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "from the trail"))
            val id = alice.ownMessageId(Conversations.NEARBY, "from the trail")
            assertTrue("bob never heard the post", lab.tryAwait(1) { bob.roomPosts()[alice.nodeId].orEmpty().size })
            assertEquals(DeliveryPlane.LoRa, bob.receivedVia(Conversations.NEARBY, id))
            assertFalse("a board carries a frame, never its bytes", bob.attachmentHeld(Conversations.NEARBY, id))

            // Half an hour alone, then the sweep that reclaims a want nobody could be asked for.
            lab.clock.advance(WANT_TTL_LAPSED_MS)
            bob.sweepExpired()
            assertFalse(bob.attachmentHeld(Conversations.NEARBY, id))

            lab.link(alice, bob)
            assertTrue(
                "the picture was never fetched once a holder linked",
                lab.tryAwait(1) { if (bob.attachmentHeld(Conversations.NEARBY, id)) 1 else 0 },
            )
            assertTrue(picture.contentEquals(bob.attachmentPlain(Conversations.NEARBY, id)))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { Conversations.NEARBY }
        }

    /**
     * Work item #79 (ADR 2026-09.4tx5): three phones linked over BLE, a 700 KB picture Alice sends Bob, and
     * the Moto G's slow controller — Bob's copy takes minutes to land. In that window the 60 s re-offer
     * re-asked every neighbour for it, Alice served it again, and Carol — who had Bob's first ask on file as
     * a *wanter* and pulled the bytes for it — pushed him a third copy the moment hers landed; Bob then pushed
     * Carol one she held. Since #136 (ADR 2026-10.f7gg) Bob's first ask goes to Alice alone, the author, and the
     * round widens to Carol only if no header lands within its grace. Here Alice's link to Bob is the slow one
     * (her serve is parked with its header across), so the round never widens: Carol hears of the picture only
     * from Bob's relay of the frame, carries it without pulling its bytes — Bob, its recipient, is linked to her and
     * fetching them (ADR 2026-10.g24x) — and never has an ask of Bob's on file. The re-link Bob ↔ Carol is the
     * tick, and the file recorder on every transport is the phone's `file …` line: exactly one copy per (hash, link),
     * and Bob asks once.
     */
    @Test
    fun aPictureAlreadyStreamingInIsNeitherAskedForAgainNorServedTwice() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") }
            lab.linkAll(alice to bob, bob to carol, alice to carol)
            lab.awaitAcquainted(alice, bob, carol)
            // Each end's link-up hooks have run (its digest goes out after its re-ask), so none of them asks below.
            lab.await(1) {
                val ran = listOf(bob to listOf(alice, carol), carol to listOf(alice, bob))
                ran.all { (node, peers) -> peers.all { it.nodeId in node.transport.digestsSent } }.let { if (it) 1 else 0 }
            }

            alice.transport.holdFiles(bob.transport) // Bob's link is the Moto's: the header lands, the bytes take their time
            alice.transport.hold(carol.transport) // Carol hears of the picture from Bob's relay, never from Alice's frame
            val deferred = carol.metrics.files().carrierPullsDeferred
            val picture = Random(7).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "for bob", to = bob))
            val id = alice.ownMessageId(alice.dmWith(bob), "for bob")
            val hash = checkNotNull(alice.attachmentHash(alice.dmWith(bob), id))
            val fileToBob = "${bob.nodeId} ATTACHMENT $hash"

            // Alice's serve to Bob is on the link, header across. Carol carries Bob's relay of the frame and leaves its
            // bytes to Bob, the recipient she is linked to (ADR 2026-10.g24x).
            lab.await(1) { alice.transport.heldFiles(bob.transport).count { it == hash } }
            lab.await(1) { if (carol.metrics.files().carrierPullsDeferred > deferred) 1 else 0 }
            assertTrue("the header across is what Bob reads as arriving", hash in bob.transport.arrivingFiles())
            alice.transport.release(carol.transport) // Alice's copy of the frame lands on Carol, behind Bob's relay of it

            // Past the first ask's grace, and a round on: the 60 s re-offer, for the link that is not busy. Bob
            // re-arms from the database and steps every blob he lacks — but this one's bytes are already on the way.
            lab.clock.advance(BlobExchange.ROUND_MS + MeshLab.FIRST_ASK_GRACE_MS)
            val digests = bob.transport.digestsSent.count { it == carol.nodeId }
            // ADR 2026-10.g24x: Carol, linked to Bob until here, left the picture to him. Read now: a tick of hers in the
            // re-link gap below would find him gone and pull it, as the policy means it to.
            assertTrue("Carol asked for it: ${carol.transport.blobAsks}", carol.transport.blobAsks.none { it.hash == hash })
            assertTrue("Alice fed Carol a copy", alice.transport.files.none { it == "${carol.nodeId} ATTACHMENT $hash" })
            lab.unlink(bob, carol)
            lab.link(bob, carol)
            lab.await(digests + 1) { bob.transport.digestsSent.count { it == carol.nodeId } } // the batch's last hook
            bob.reoffer()
            val asks = bob.transport.blobAsks.filter { it.hash == hash }
            assertEquals("one ask, of the author alone, none while the bytes stream in:\n$asks", listOf(alice.nodeId), asks.map { it.to })
            assertEquals("Alice served Bob once", 1, alice.transport.files.count { it == fileToBob })

            alice.transport.releaseFiles(bob.transport) // the bytes land
            lab.assertConverged(listOf(alice, bob), atLeast = 1, carriers = listOf(carol)) { it.dmWith(if (it === alice) bob else alice) }
            assertTrue(picture.contentEquals(bob.attachmentPlain(bob.dmWith(alice), id)))
            assertEquals("Alice served Bob once", 1, alice.transport.files.count { it == fileToBob })
            assertTrue("Carol never pushed Bob a copy", carol.transport.files.none { it == fileToBob })
            val bobToCarol = bob.transport.files.filter { it.startsWith(carol.nodeId) }
            assertTrue("Bob never pushed Carol a copy: $bobToCarol", bobToCarol.isEmpty())
        }

    @Test
    fun aPictureStillInTheHoldersStackIsNotAskedForAgainWhileTheOneAheadOfItStreamsIn() =
        runBlocking {
            // #121, the shape the P7 caught on 2026-10-03: Alice serves Bob two pictures, her feed outpaces his drain,
            // and the second sits whole in her Bluetooth stack behind the first. Bob does not see it arriving, and
            // Alice (a holder that cannot see her own stack) no longer counts it in flight. Bob's 60 s tick must not
            // ask her for it again while the first is still streaming in from her.
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            // Each end's link-up hooks have run (its digest goes out after its re-ask), so none of them asks below.
            lab.await(1) {
                if (alice.nodeId in bob.transport.digestsSent && bob.nodeId in alice.transport.digestsSent) 1 else 0
            }

            alice.transport.holdFiles(bob.transport) // the first picture's header is across, its bytes take their time
            val first = Random(11).nextBytes(4_096)
            assertTrue(alice.sendImage(first, "first for bob", to = bob))
            val firstId = alice.ownMessageId(alice.dmWith(bob), "first for bob")
            val firstHash = checkNotNull(alice.attachmentHash(alice.dmWith(bob), firstId))
            lab.await(1) { alice.transport.heldFiles(bob.transport).count { it == firstHash } }

            alice.transport.stackFiles(bob.transport) // the second is fed whole, behind it
            val second = Random(12).nextBytes(4_096)
            assertTrue(alice.sendImage(second, "second for bob", to = bob))
            val secondId = alice.ownMessageId(alice.dmWith(bob), "second for bob")
            val secondHash = checkNotNull(alice.attachmentHash(alice.dmWith(bob), secondId))
            lab.await(1) { alice.transport.heldFiles(bob.transport).count { it == secondHash } }
            assertTrue("the first is streaming in", firstHash in bob.transport.arrivingFiles())
            assertFalse("the second is not seen arriving", secondHash in bob.transport.arrivingFiles())
            assertFalse("nor counted in flight by its holder", alice.transport.fileInFlightTo(bob.nodeId, secondHash))
            val asksBefore = bob.transport.blobAsks.count { it.hash == secondHash }
            assertEquals("Bob asked for the second once, on hearing of it", 1, asksBefore)

            // The serve memo runs from the enqueue, and a serve that waited behind the first has outlived it.
            lab.clock.advance(BlobExchange.SERVE_MEMO_MS + 1_000)
            bob.reoffer() // Bob's 60 s tick
            assertEquals(
                "no re-ask while the picture ahead of it streams in: ${bob.transport.blobAsks}",
                asksBefore,
                bob.transport.blobAsks.count { it.hash == secondHash },
            )

            alice.transport.releaseFiles(bob.transport) // both land, in order
            lab.assertConverged(listOf(alice, bob), atLeast = 2) { it.dmWith(if (it === alice) bob else alice) }
            assertTrue(first.contentEquals(bob.attachmentPlain(bob.dmWith(alice), firstId)))
            assertTrue(second.contentEquals(bob.attachmentPlain(bob.dmWith(alice), secondId)))
            listOf(firstHash, secondHash).forEach { hash ->
                val which = if (hash == firstHash) "the first picture" else "the second picture"
                assertEquals(
                    "Alice served $which once; Bob's asks for it: ${bob.transport.blobAsks.filter { it.hash == hash }}",
                    1,
                    alice.transport.files.count { it == "${bob.nodeId} ATTACHMENT $hash" },
                )
            }
        }

    /**
     * #132: a group voice note whose bytes reach a member before its message does. Bob hears the group frame
     * ahead of its sender-key seed, so he can carry it but not open it, and custody pulls its blob in the
     * meantime. When the seed arrives the frame opens on a blob already held, so no obtained hook follows the row,
     * and the delivery has to read the note's duration itself. Before the fix, Bob's bubble stayed flat with no
     * length for good.
     *
     * Alice's sealed DMs to Bob (the seed, Bob's key-request answer, ticks) are the air's to lose while the blob
     * comes in. The group frame and the blob exchange pass, and the files are never held. The re-link's digest
     * exchange then repairs the seed from Alice's custody.
     *
     * Bob never asks before a large Bluetooth download: a frame addressed to him that he cannot open yet has its size
     * sealed inside, so with the default limit its blob would wait for Wi-Fi Aware until it opened (ADR 2026-10.js2p).
     */
    @Test
    fun aGroupVoiceNoteWhoseBytesOutrunItsSeedIsDescribedWhenItOpens() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            bob.settings.setBluetoothDownloadLimit(BluetoothDownloadLimit.NEVER)
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)

            alice.transport.hold(bob.transport)
            val groupId = alice.createGroup(bob)
            val note = adts(frames = 216)
            assertTrue(alice.sendVoice(note, groupId = groupId))
            val noteId = alice.ownMessageId(groupId, "")
            val hash = checkNotNull(alice.attachmentHash(groupId, noteId))
            lab.await(1) { alice.transport.held(bob.transport).count { it.isGroupFrame() } }
            alice.transport.lossy(bob.transport) { it.isSealedDmTo(bob) }
            // The group frame alone: the seed parked beside it is lost with the rest of the batch.
            val released = alice.transport.release(bob.transport) { batch -> batch.filter { it.isGroupFrame() } }
            assertEquals(1, released.size)

            lab.await(1) { if (bob.holdsBlob(hash)) 1 else 0 }
            assertNull("the note opened before its bytes landed: the fixture lost its order", bob.attachmentHash(groupId, noteId))

            alice.transport.lossy(bob.transport) // a clean link again, and the re-link's digest exchange serves the seed
            lab.unlink(alice, bob)
            lab.link(alice, bob)
            assertTrue(
                "Bob's row for the voice note never learned its duration: ${bob.rowsIn(groupId)}",
                lab.tryAwait(1) { if (bob.voiceDurationOf(groupId, noteId) == VoiceAudio.durationMs(note)) 1 else 0 },
            )
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { groupId }
        }

    /**
     * The same shape under the default Bluetooth download limit (ADR 2026-10.js2p), with a file over it: a group frame
     * addressed to Bob that he cannot open yet has its size sealed inside. Custody leaves its bytes to the delivery that
     * follows the seed, and the tick's re-arm holds it to Wi-Fi Aware — by the roster the frame itself carries, since Bob
     * has no group row before the seed (the mesh-lab review's finding: by group rows alone the tick fetched it over
     * Bluetooth). Nothing is asked until Bob's row exists; once it opens, over the limit, it waits for the tap, and the
     * tap fetches it.
     */
    @Test
    fun aLargeGroupFileHeardBeforeItsSeedIsNotAskedForUntilItOpens() =
        runBlocking {
            val alice = lab.node("alice").apply { setDisplayName("Alice") }
            val bob = lab.node("bob").apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)

            alice.transport.hold(bob.transport)
            val groupId = alice.createGroup(bob)
            assertTrue(alice.sendFile(Random(2007).nextBytes(2_000_000), "plans.zip", "application/zip", text = "plans", groupId = groupId))
            val id = alice.ownMessageId(groupId, "plans")
            val hash = checkNotNull(alice.attachmentHash(groupId, id))
            lab.await(1) { alice.transport.held(bob.transport).count { it.isGroupFrame() } }
            alice.transport.lossy(bob.transport) { it.isSealedDmTo(bob) }
            val released = alice.transport.release(bob.transport) { batch -> batch.filter { it.isGroupFrame() } }
            assertEquals(1, released.size)

            // Bob carries the frame he cannot open; the tick's re-arm sees it and asks nobody over Bluetooth.
            lab.await(1) { if (id in bob.custodyIds()) 1 else 0 }
            assertNull("the frame opened before the tick: the fixture lost its order", bob.row(id))
            val heldBefore = bob.metrics.files().blobFastOnlyHeld
            bob.reoffer()
            // Wanted, and held to Wi-Fi Aware: a plain want would have asked Alice over the link instead.
            lab.await(1) { if (bob.metrics.files().blobFastOnlyHeld > heldBefore) 1 else 0 }
            assertTrue(
                "asked over Bluetooth before the frame opened: ${bob.transport.blobAsks}",
                bob.transport.blobAsks.none { it.hash == hash },
            )

            alice.transport.lossy(bob.transport) // a clean link again, and the re-link's digest exchange serves the seed
            lab.unlink(alice, bob)
            lab.link(alice, bob)
            lab.await(1) { if (bob.row(id) != null) 1 else 0 }
            bob.manager.downloadAttachment(hash) // over the limit: the tap
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { groupId }
        }

    private fun WireEnvelope.isGroupFrame(): Boolean = WireCodec.decodeEnvelope(signed)?.group != null

    private fun WireEnvelope.isSealedDmTo(node: LabNode): Boolean =
        WireCodec.decodeEnvelope(signed)?.let { it.type == FrameType.CHAT && it.group == null && it.recipientId == node.nodeId } == true

    private companion object {
        /** Past `BlobExchange.FETCH_TTL_MS` (30 min), well inside the 48 h a lab clock jump may span. */
        const val WANT_TTL_LAPSED_MS = 31 * 60_000L
    }
}
