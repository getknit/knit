package app.getknit.knit.mesh.lab

import app.getknit.knit.data.message.DeliveryPlane
import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.spool.AttachmentDeferPolicy
import app.getknit.knit.mesh.spool.FakeSpool
import app.getknit.knit.mesh.spool.ScopeSync
import app.getknit.knit.mesh.spool.SpoolCommonsInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * The Internet plane between real stacks — the real `ScopeSync` on every node over one in-process
 * [FakeSpool], which is the shape of the device trials the spool work still owes (ADR 064): two islands
 * sharing a group through one relay and a departure rotating its scope, a receive-only peer deriving the
 * same DM scope (ADR 032), a photo the radio already carried not being uploaded until the peers part
 * (ADR 021), a planted blob quarantined without breaking convergence (spec §9.3), two card holders meeting
 * at the pair scope with no radio (ADR 042), a relay that drops every socket, and a sealed profile update
 * crossing the relay (ADR 020). Slow by nature: the scope table derives on its inputs' events (a confirmed
 * session, a pair peer named or pinned, a root minted or adopted, a room joined — ADR 2026-09.dcah) with a
 * 60 s poll under the calendar-only transitions, a round runs on a change (a moved digest, a delivery, a
 * custody change) and a worker that missed one waits for its own 60 s tick (ADR 2026-09.wa79), hence
 * [MeshLab.SPOOL_AWAIT_MS]. A clock jump moves no round by itself: `manager.refreshRelays()` is the poke
 * that makes a worker look now. A scope a scenario waits more than a minute for is a missing hook.
 */
@RunWith(RobolectricTestRunner::class)
class InternetPlaneLabTest {
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

    private fun LabNode.dmThreadWith(other: LabNode): (LabNode) -> String = { if (it === this) dmWith(other) else it.dmWith(this) }

    /** Three nodes on one relay, acquainted and holding DM sessions both ways, so every DM scope derives. */
    private suspend fun threeOnOneRelay(spool: FakeSpool): Triple<LabNode, LabNode, LabNode> {
        val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
        val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
        val carol = lab.node("carol", spool = spool).apply { setDisplayName("Carol") }
        lab.linkAll(alice to bob, bob to carol, alice to carol)
        lab.awaitAcquainted(alice, bob, carol)
        listOf(alice to bob, alice to carol, bob to carol).forEach { (a, b) ->
            assertTrue(a.sendDm(b, "hello"))
            lab.await(1) { b.decrypted(b.dmWith(a)).size }
            assertTrue(b.sendDm(a, "hi"))
            lab.assertConverged(listOf(a, b), atLeast = 2) { a.dmThreadWith(b)(it) }
        }
        return Triple(alice, bob, carol)
    }

    /**
     * The two-island trial ADR 064 still owes: a group the three founded together, then Alice and Bob one
     * island (linked) and Carol the other, all three on one relay. Carol writes across the relay through
     * the group scope and gets the others' ticks back; then she leaves — her leave crosses the relay, the
     * remainder rekeys and re-mints the group root, and a fresh scope replaces the old one.
     */
    @Test
    fun twoIslandsShareAGroupThroughOneRelayAndADepartureRotatesTheScope() =
        runBlocking {
            val spool = FakeSpool()
            val (alice, bob, carol) = threeOnOneRelay(spool)
            val groupId = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(groupId, "founded together"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1) { groupId }
            lab.unlink(alice, carol)
            lab.unlink(bob, carol)
            lab.awaitGroupScope(alice, groupId, bob, carol)
            lab.awaitGroupScope(carol, groupId, alice, bob)
            assertTrue(carol.sendGroup(groupId, "from the far island"))
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 2, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { groupId }

            carol.leaveGroup(groupId)
            assertTrue(
                "alice never learned of the departure",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    if (alice.groupShape(groupId)?.departed ==
                        setOf(carol.nodeId)
                    ) {
                        1
                    } else {
                        0
                    }
                },
            )
            assertTrue(bob.sendGroup(groupId, "after carol left"))
            // The re-mint that rotates the scope is damped by GroupRootPolicy's six-hour grace unless the
            // preferred minter is the one left standing; the rotation itself is the clock tier's to assert
            // (TimeLabTest), so here the departure and the remainder's convergence are what is pinned.
            // A relay carries only your own scopes, never a third party's DM-form frames (alice↔bob's ticks
            // and seeds), so the far island's custody agrees with the near one's only once they meet by
            // radio again — one ordinary digest exchange, which is what the re-link shows.
            lab.linkAll(alice to carol, bob to carol)
            lab.assertConverged(listOf(alice, bob), atLeast = 3, carriers = listOf(carol), timeoutMs = MeshLab.SPOOL_AWAIT_MS) { groupId }
        }

    /**
     * Work item #47 (found here 2026-09-14, fixed by ADR 2026-09.mjaj): a group founded while one member is
     * reachable only over the relay reaches that member. The seed rides Carol's DM scope carrying the founding
     * roster (`GroupKeyPayload.group`), so she pins the group from the seed itself, adopts the seed and the
     * root on the same pass, derives the group scope and pulls the founding frame — all before any radio
     * re-link. Before the field the seed parked (`PendingGroupKeys`, group unknown) with the root inside it,
     * the roster rode only the group scope that root derives, and Carol held nothing until the park expired.
     * The re-link at the end serves the oracle's custody leg alone: a relay carries only your own scopes, so
     * Alice→Bob's seed DM and Bob's ack can never reach Carol's custody (see the two-island case above).
     */
    @Test
    fun aGroupFoundedAcrossTheRelayReachesTheRelayOnlyMember() =
        runBlocking {
            val spool = FakeSpool()
            val (alice, bob, carol) = threeOnOneRelay(spool)
            lab.unlink(alice, carol)
            lab.unlink(bob, carol)
            lab.awaitDmScope(alice, carol)
            lab.awaitDmScope(carol, alice)

            val groupId = alice.createGroup(bob, carol)
            assertTrue(alice.sendGroup(groupId, "founded across the relay"))

            // The acceptance, while Carol is still relay-only: the seed pinned the group (nothing parked), the
            // scope derived from the root it carried, and the founding frame crossed the relay.
            lab.awaitGroupScope(carol, groupId, alice, bob)
            assertTrue(
                "carol never read the founding message over the relay",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { carol.decrypted(groupId).size },
            )
            assertEquals(alice.groupShape(groupId), carol.groupShape(groupId))
            assertEquals(0L, carol.metrics.snapshot().groupSeedsHeld)

            lab.linkAll(alice to carol, bob to carol)
            lab.assertConverged(listOf(alice, bob, carol), atLeast = 1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { groupId }
        }

    /**
     * ADR 032: a pair where only Alice ever wrote. Bob's side used to derive no scope because the thread was
     * an unaccepted request there; now both derive the same one, and a DM crosses the relay alone.
     */
    @Test
    fun aReceiveOnlyPeerDerivesTheSameDmScope() =
        runBlocking {
            val spool = FakeSpool()
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)
            assertTrue(alice.sendDm(bob, "a request bob never answers"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1) { alice.dmThreadWith(bob)(it) }
            lab.unlink(alice, bob)

            lab.awaitDmScope(alice, bob)
            lab.awaitDmScope(bob, alice)
            assertEquals(alice.dmScopeStatus(bob)?.scopeHex, bob.dmScopeStatus(alice)?.scopeHex)

            assertTrue(alice.sendDm(bob, "over the relay, still unanswered"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { alice.dmThreadWith(bob)(it) }
        }

    /**
     * ADR 021's owed trial, and the regression for issue #46. A photo the radios carried needs no second
     * copy at a relay, so the bytes wait while Bob is in range and go up once he leaves.
     *
     * The whole round is decided on its first pass, which is what #46 was about: `ScopeSync.onCustodyChanged`
     * wakes the worker the moment the frame is custodied — the send itself — and that round's
     * `healAttachments` asks `AttachmentDeferPolicy.defer` before it spends an `ahave`. The recipient's ack
     * is still a round trip away at that instant and cannot exist, so the old rule read "no ack" as "the
     * radios never carried this" and pushed, and every later deferral was moot because the chunks were
     * already at the relay. `ACK_GRACE_MS` separates the two: for a minute after the send an attachment on
     * a message we authored holds on the sighting alone, by which time the real ack has landed and the
     * ordinary rule takes over — so there is no round in which these bytes are pushable.
     *
     * Bob still gets the picture, over the radio link via `BlobExchange`; that is what `assertConverged`'s
     * attachment oracle checks, and what makes the relay copy redundant in the first place.
     */
    @Test
    fun aPhotoTheRadioCarriedIsNotUploadedUntilThePeersPart() =
        runBlocking {
            val spool = FakeSpool()
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
            lab.meetOnTheRelay(alice, bob)
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)

            val picture = Random(21).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "in range", to = bob))
            lab.assertConverged(listOf(alice, bob), atLeast = 3) { alice.dmThreadWith(bob)(it) }
            assertTrue(
                "the upload was never deferred",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    alice.metrics
                        .snapshot()
                        .spoolAttachDeferred
                        .toInt()
                },
            )
            // Neither end, not just the sender: Bob holds these bytes because a radio handed them to him, so
            // his own push would be the second copy this gate exists to prevent.
            val sentId = alice.ownMessageId(alice.dmWith(bob), "in range")
            assertTrue(
                "chunks went up while bob was in range: ${spool.chunksPut}; " +
                    "alice pushed=${alice.metrics.snapshot().spoolAttachPushed} bob pushed=${bob.metrics.snapshot().spoolAttachPushed} " +
                    "bob's row came via ${bob.receivedVia(bob.dmWith(alice), sentId)}\n${lab.report(listOf(alice, bob))}",
                spool.chunksPut.isEmpty(),
            )

            // `lastSeen` is a stamp, not a live read, so unlinking alone leaves Bob deferrable for the rest
            // of the sighting window — the lapse is the reversing half and it is measured on the calendar.
            // Well inside the 24 h custody TTL, so the frame still names the attachment and can still push.
            lab.unlink(alice, bob)
            lab.clock.advance(AttachmentDeferPolicy.RADIO_WINDOW_MS + 60_000L)
            assertTrue(
                "the upload never happened after they parted",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { spool.chunksPut.size },
            )
        }

    /**
     * The same photo with the relay winning the race to Bob. Both phones hold a live socket and the radio
     * link is slow — a BLE connect takes seconds where a spool round trip takes milliseconds — so Alice's
     * frame comes off the relay before the radio delivers it, and Bob's row records `Internet`. The bytes
     * still arrive over the radio, because Alice deferred her upload; that arrival is the evidence, and
     * Bob must not push the second copy the row's plane alone would let him. The sibling ordering — the
     * bytes landing before Bob's row is even committed — is the one CI hit in
     * [aPhotoTheRadioCarriedIsNotUploadedUntilThePeersPart] (2026-09-16) and cannot be forced from
     * outside; this one can, and the same evidence covers both (ADR 2026-09.e8yw).
     */
    @Test
    fun aPhotoWhoseFrameBeatTheRadioAcrossTheRelayIsStillNotReUploaded() =
        runBlocking {
            val spool = FakeSpool()
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
            lab.meetOnTheRelay(alice, bob)
            lab.link(alice, bob)
            lab.awaitAcquainted(alice, bob)

            // The slow radio: Alice's frames to Bob are parked, so the relay is the first to hand him this one.
            alice.transport.hold(bob.transport)
            val picture = Random(46).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "relay first", to = bob))
            val id = alice.ownMessageId(alice.dmWith(bob), "relay first")
            assertTrue(
                "bob never got the frame off the relay\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { bob.decrypted(bob.dmWith(alice)).count { it.first == id } },
            )
            assertEquals(DeliveryPlane.Internet, bob.receivedVia(bob.dmWith(alice), id))
            // The radio catches up: its copy of the frame is a duplicate, and the bytes Bob asks for cross it.
            alice.transport.release(bob.transport)
            lab.assertConverged(listOf(alice, bob), atLeast = 3) { alice.dmThreadWith(bob)(it) }

            // A send of Bob's own wakes his worker; that round finds the bytes in hand and has to judge them.
            assertTrue(bob.sendDm(alice, "got it"))
            assertTrue(
                "bob never judged the bytes\n${lab.report(listOf(alice, bob))}",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    bob.metrics.snapshot().let { (it.spoolAttachDeferred + it.spoolAttachPushed).toInt() }
                },
            )
            assertTrue(
                "bob re-uploaded a photo the radio handed him: ${spool.chunksPut}\n${lab.report(listOf(alice, bob))}",
                spool.chunksPut.isEmpty(),
            )
        }

    /**
     * Issue #51: `BlobExchange` marks a want the moment a frame names an attachment it lacks, and only
     * [app.getknit.knit.mesh.BlobExchange.onReceived] — the radio arrival — ever cleared it. When the spool
     * delivers the bytes instead (`ScopeSync.fetchAttachment` → `onAttachmentObtained`), the mark survived,
     * and `onNeighborAdded` asked the next neighbour to join for a picture this node already holds — a whole
     * attachment re-served over the radio, for the rest of the 30-minute fetch TTL.
     *
     * The pair are parted for the whole transfer, so the relay is the only path the bytes can take. The clock
     * jump is the deferral lapsing (ADR 021, and the shape [aPhotoTheRadioCarriedIsNotUploadedUntilThePeersPart]
     * pins): Alice saw Bob on the radios during the acquaintance phase, so her push waits out that sighting
     * before it uploads. The link that follows must produce no `blobreq` at all.
     */
    @Test
    fun aPhotoTheRelayDeliveredIsNotAskedForAgainWhenTheRadiosComeBack() =
        runBlocking {
            val spool = FakeSpool()
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
            // Derives both DM scopes, and leaves the pair unlinked.
            lab.meetOnTheRelay(alice, bob)

            val picture = Random(51).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "off the relay", to = bob))
            val id = alice.ownMessageId(alice.dmWith(bob), "off the relay")
            val hash = checkNotNull(alice.attachmentHash(alice.dmWith(bob), id))
            lab.clock.advance(AttachmentDeferPolicy.RADIO_WINDOW_MS + 60_000L)
            assertTrue(
                "bob never got the bytes over the relay",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { if (bob.blobs.exists(hash)) 1 else 0 },
            )

            // Everything so far crossed the spool; from here only the link-up's own frames are on the radio.
            bob.transport.sent.clear()
            lab.link(alice, bob)
            lab.assertConverged(listOf(alice, bob), atLeast = 3) { alice.dmThreadWith(bob)(it) }

            assertTrue(
                "bob re-asked for a picture the relay already gave him: ${bob.transport.sent}",
                bob.transport.sent.none { it.contains(" ${FrameType.BLOB_REQ} ") },
            )
        }

    /**
     * A frame whose relay copy reaches the bridging node before its radio copy is still relayed on over the
     * radio. Bob shares a DM scope with Alice and is linked to her; Carol, behind him, has no relay. The spool
     * copy is Bob's first sighting and seeds his pending relay's `heardFrom`; the radio copy then lands inside
     * the 0–150 ms jitter, and two "neighbours" meet the suppression threshold — cancelling the one hop Carol
     * depended on (found by chaos seeds 1000/1004 on the scenario below; ADR 2026-09.dcah fixed only the
     * mirror order, a spool copy arriving second).
     */
    @Test
    fun aFrameTheRelayDeliversFirstIsStillRelayedToTheCarrierBehindUs() =
        runBlocking {
            val spool = FakeSpool()
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") } // no relay: Bob is her only source
            lab.linkAll(alice to bob, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)
            assertTrue(alice.sendDm(bob, "hello"))
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).size }
            assertTrue(bob.sendDm(alice, "hi"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2, carriers = listOf(carol)) { alice.dmThreadWith(bob)(it) }
            lab.awaitDmScope(alice, bob)
            lab.awaitDmScope(bob, alice)

            val suppressed = bob.metrics.snapshot().framesSuppressed
            val decided = bob.metrics.snapshot().let { it.framesRelayed + it.framesSuppressed }
            // Each DM's spool copy is Bob's first sighting; its radio copy is released a little after his row
            // lands, at a spread of offsets, so some land after the relay is scheduled and inside its 0–150 ms
            // jitter — the window the router's own randomness decides.
            RADIO_LAG_MS.forEach { lag ->
                alice.transport.hold(bob.transport)
                val body = "the relay was first by $lag ms"
                assertTrue(alice.sendDm(bob, body))
                // A 1 ms poll, not tryAwait's 25 ms: the offset below is only as tight as this wait.
                val landed =
                    withTimeoutOrNull(MeshLab.SPOOL_AWAIT_MS) {
                        while (bob.decrypted(bob.dmWith(alice)).none { it.second == body }) delay(1)
                    } != null
                assertTrue("bob never got \"$body\" off the relay\n${lab.report(listOf(alice, bob, carol))}", landed)
                delay(lag) // an offset into the router's real-time jitter, not a sync point
                alice.transport.release(bob.transport)
            }

            // Every one of Bob's relay decisions made, then the bug named where it happens — before the oracle.
            lab.await(decided.toInt() + RADIO_LAG_MS.size) {
                bob.metrics
                    .snapshot()
                    .let { it.framesRelayed + it.framesSuppressed }
                    .toInt()
            }
            // `decided` can be one short (the pre-loop tick's relay counted after the snapshot), so the count alone
            // may pass before the last radio copy is judged; a drained inbound means every copy has been, and any
            // suppression it caused is counted — both run inline in `handleInbound`.
            bob.transport.awaitInboundDrained()
            assertEquals("bob cancelled a relay on the spool's copy", suppressed, bob.metrics.snapshot().framesSuppressed)
            lab.assertConverged(listOf(alice, bob), atLeast = 2 + RADIO_LAG_MS.size, carriers = listOf(carol)) {
                alice.dmThreadWith(bob)(it)
            }
        }

    /**
     * Issue #53 as ADR 2026-09.4tx5 (#79) leaves it: a neighbour that asked us for bytes we lacked is served on
     * its **next ask**, once whichever plane hands them to us has — and is never pushed them. The push #53 added
     * (ADR 2026-09.ywzn's wanter drain) could not tell an asker that still lacks the bytes from one whose copy
     * is already arriving from somebody else, and in a clique it bought every recipient a copy per neighbour.
     * The asker re-asks on its own 60 s tick while it still lacks them (`rewantMissingBlobs`, ADR 2026-09.ptv8),
     * so the wait is a minute at most, not the half hour #53 saw.
     *
     * Carol is the asker and holds the frame only as a carrier: she is linked to Bob alone, has no relay, and
     * Alice is off the radios entirely, so Bob is the one node that can ever hand her these bytes. The relay
     * starts out as a frames-only one (no attachment limits in its HELLO, so a conforming client sends it no
     * attachment record at all), which is what makes the order the case needs a fact rather than a race:
     * Carol necessarily asks while the picture exists nowhere but on Alice. Growing the support mid-run —
     * the spool re-advertises on the next dial — is what lands the bytes on Bob; the re-link is Carol's tick.
     */
    @Test
    fun aPhotoTheRelayDeliveredIsServedToTheNeighbourWhoAskedWhileWeLackedItOnItsNextAsk() =
        runBlocking {
            val spool = FakeSpool(attachments = false) // frames only, until the picture has been asked for
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
            val carol = lab.node("carol").apply { setDisplayName("Carol") } // no relay: Bob is her only source
            lab.linkAll(alice to bob, bob to carol)
            lab.awaitAcquainted(alice, bob, carol)
            // A reply, not a both-initiate race, so both sides confirm the session and the scope derives.
            assertTrue(alice.sendDm(bob, "hello"))
            lab.await(1) { bob.decrypted(bob.dmWith(alice)).size }
            assertTrue(bob.sendDm(alice, "hi"))
            lab.assertConverged(listOf(alice, bob), atLeast = 2, carriers = listOf(carol)) { alice.dmThreadWith(bob)(it) }
            lab.unlink(alice, bob)
            lab.awaitDmScope(alice, bob)

            val picture = Random(53).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "off the relay", to = bob))
            val id = alice.ownMessageId(alice.dmWith(bob), "off the relay")
            val hash = checkNotNull(alice.attachmentHash(alice.dmWith(bob), id))
            assertTrue(
                "bob never got the frame over the relay",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    bob.decrypted(bob.dmWith(alice)).count { it.second == "off the relay" }
                },
            )
            // Bob relays the frame on; Carol carries it, wants the picture it names, and asks the one
            // neighbour she has — who cannot serve her, because the bytes are still Alice's alone.
            assertTrue(
                "carol never asked bob for the picture: ${carol.transport.sent}",
                lab.tryAwait(1) { carol.transport.sent.count { it.contains(" ${FrameType.BLOB_REQ} ") } },
            )
            assertTrue("the bytes reached the relay after all: ${spool.chunksPut}", spool.chunksPut.isEmpty())
            assertFalse("bob held the picture before carol asked for it", bob.blobs.exists(hash))

            spool.attachments = true // the relay grows attachment support; the limits are read per dial
            spool.dropSockets()
            alice.heal()
            bob.heal()
            assertTrue(
                "bob never got the bytes over the relay",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { if (bob.blobs.exists(hash)) 1 else 0 },
            )

            // Bob remembers nothing about Carol's ask and pushes nothing: she may hold the bytes by now, or be
            // receiving them from someone Bob cannot see. Her own next ask is what says she still lacks them.
            val fileToCarol = "${carol.nodeId} ATTACHMENT $hash"
            assertTrue("bob pushed the picture unasked", bob.transport.files.none { it == fileToCarol })
            lab.unlink(bob, carol)
            lab.link(bob, carol) // the 60 s re-offer: Carol re-arms from the database and re-asks her one neighbour
            assertTrue(
                "carol was never served the picture bob pulled off the relay",
                lab.tryAwait(1) { if (carol.blobs.exists(hash)) 1 else 0 },
            )
            assertEquals("served once, to the fresh ask", 1, bob.transport.files.count { it == fileToCarol })
            // Carol carries the sealed blob and cannot read it; Bob, the addressee, is who the picture is for.
            assertArrayEquals(bob.blobs.bytes(hash), carol.blobs.bytes(hash))
            assertArrayEquals(picture, bob.attachmentPlain(bob.dmWith(alice), id))
        }

    /**
     * Issue #52: the deferral's evidence half must name a plane that could have carried the *bytes*. Alice saw
     * Bob on the radios minutes ago (well inside `RADIO_WINDOW_MS`), but the receipt for this image came back
     * across the spool — so the radios never had these bytes and nothing may hold them back. Before the fix
     * the ack was read plane-agnostically, the spool receipt satisfied the gate, and every round from then to
     * the end of the sighting window deferred.
     *
     * One lab-specific obstacle shapes the staging. `AttachmentDeferPolicy` samples the presence plane
     * **lazily**, from inside `defer` — correct in production, where a round runs constantly, but it means a
     * scenario must have an attachment pending while the peers are linked or no sighting is ever recorded.
     * An **avatar** is the one reference that can stamp it without deferring anything itself: it writes no
     * message row, so it is never ours to hold back, whatever its plane.
     *
     * The clock jump is `ACK_GRACE_MS` (issue #46), and it is what leaves this scenario asking one question.
     * A frame younger than the grace defers on the sighting alone, because no ack could have come back yet;
     * past it the ack has landed and only its plane is left to weigh. So the bytes reaching the spool while
     * the sighting is still fresh — 60 s against 15 minutes — can only mean the spool's own receipt was
     * refused as evidence. Before the fix the gate took it, and these chunks waited out the whole window.
     */
    @Test
    fun aPhotoAckedOnlyAcrossTheSpoolIsNeverDeferredOnThatAck() =
        runBlocking {
            val spool = FakeSpool()
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
            // Derives both DM scopes, and leaves the pair unlinked.
            lab.meetOnTheRelay(alice, bob)

            // Linked, with an avatar pending, so a round runs `defer` and stamps its sighting of bob.
            lab.link(alice, bob)
            alice.setAvatar(Random(520).nextBytes(2_048))
            assertTrue(
                "the avatar never reached the spool, so no round sighted bob",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { spool.chunksPut.size },
            )
            lab.unlink(alice, bob)
            spool.chunksPut.clear()

            val picture = Random(52).nextBytes(4_096)
            assertTrue(alice.sendImage(picture, "only the relay carried this", to = bob))
            lab.assertConverged(listOf(alice, bob), atLeast = 3, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                alice.dmThreadWith(bob)(it)
            }
            val thread = alice.dmWith(bob)
            val sent = alice.ownMessageId(thread, "only the relay carried this")
            assertTrue(
                "bob's receipt has to land before the attachment pass can weigh it",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    if (alice.receivedVia(thread, sent) == DeliveryPlane.Internet) 1 else 0
                },
            )

            lab.clock.advance(AttachmentDeferPolicy.ACK_GRACE_MS)
            assertTrue(
                "an ack that only crossed the spool is not evidence the radios carried the bytes",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { spool.chunksPut.size },
            )
        }

    /**
     * Spec §9.3: a blob the relay folds into its digest that nobody can open is quarantined — pulled once,
     * never again — and the pair keeps talking through the scope. The row's `converged` flag legitimately
     * reads false while the relay lists an id we refuse to count as held (C-9.3-2): the cost is one
     * bounded LIST per round, never a pull.
     */
    @Test
    fun aPlantedGarbageBlobIsQuarantinedOnceAndTheScopeKeepsWorking() =
        runBlocking {
            val spool = FakeSpool()
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
            lab.meetOnTheRelay(alice, bob)
            val scope = checkNotNull(alice.dmScopeStatus(bob)).scopeHex

            val id = spool.plantGarbage(scope, Random(22).nextBytes(200))
            spool.announce(scope) // a spool that gained a blob says so; the fake's plant deliberately does not
            assertTrue(
                "the garbage was never quarantined; spool holds ${spool.liveIds(
                    scope,
                )}, alice=${alice.dmScopeStatus(bob)?.invalidCount} bob=${bob.dmScopeStatus(alice)?.invalidCount}",
                lab.tryAwait(2, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    (
                        alice.metrics.snapshot().spoolInvalid +
                            bob.metrics.snapshot().spoolInvalid
                    ).toInt()
                },
            )
            assertEquals("asked for exactly once per member", 2, spool.pulled.count { it == id })

            assertTrue(alice.sendDm(bob, "still fine"))
            lab.assertConverged(listOf(alice, bob), atLeast = 3, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { alice.dmThreadWith(bob)(it) }
            assertEquals("still asked for exactly once", 2, spool.pulled.count { it == id })
        }

    /**
     * ADR 042: two people who only ever exchanged contact cards, out of radio range, one shared relay. Each
     * import sends an intro over the pair scope; the sessions confirm, the same DM scope derives on both
     * sides, and a DM crosses.
     */
    @Test
    fun twoCardHoldersMeetAtThePairScopeWithNoRadio() =
        runBlocking {
            val spool = FakeSpool()
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }

            val aliceCard = alice.mintCard()
            val bobCard = bob.mintCard()
            alice.importCard(bobCard)
            bob.importCard(aliceCard)

            // One intro at least, not one each: when Alice's lands before Bob's import runs, Bob already holds a
            // confirmed responder session, so his import needs no intro (`IntroSync.want`) and he answers hers.
            assertTrue(
                "no intro ever went out",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    (alice.metrics.snapshot().introsSent + bob.metrics.snapshot().introsSent).toInt()
                },
            )
            assertTrue(
                "the sessions never confirmed",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    if (alice.session(bob)?.confirmed == true &&
                        bob.session(alice)?.confirmed == true
                    ) {
                        1
                    } else {
                        0
                    }
                },
            )
            lab.awaitDmScope(alice, bob)
            lab.awaitDmScope(bob, alice)
            assertEquals(alice.dmScopeStatus(bob)?.scopeHex, bob.dmScopeStatus(alice)?.scopeHex)

            assertTrue(alice.sendDm(bob, "we met through a link"))
            lab.assertConverged(listOf(alice, bob), atLeast = 1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { alice.dmThreadWith(bob)(it) }
        }

    /**
     * docs/RELAY_INVITE.md: Bob has never turned relays on and knows no relay. Alice, who runs on the spool and
     * has joined its commons, shares the invite her relay row mints. Bob applies it once: consent is recorded,
     * the plane is on, the relay is stored, the room is joined and subscribed — and from there the two meet
     * as any two card holders do, plus a room post crosses.
     */
    @Test
    fun aRelayInviteTurnsThePlaneOnAddsTheRelayAndJoinsTheRoomInOneStep() =
        runBlocking {
            val secret = ByteArray(32) { ((it * 7 + 10) and 0xFF).toByte() }
            val spool =
                FakeSpool(
                    commons =
                        ScopeCrypto.commonsScopeId(secret) to
                            SpoolCommonsInfo(name = "Home", maxFrames = 500, ttlMs = 86_400_000L, maxBlob = 65_536),
                )
            val alice = lab.node("alice", spool = spool, commons = true).apply { setDisplayName("Alice") }
            val room = alice.joinCommons(secret, "Home")
            val bob = lab.node("bob", spool = spool, spoolOptIn = false, commons = true).apply { setDisplayName("Bob") }
            assertEquals(false, bob.settings.spoolEnabled.first())
            assertEquals(false, bob.settings.spoolConsented.first())
            assertEquals(emptySet<String>(), bob.settings.spoolUrls.first())

            bob.applyInvite(alice.mintInvite())

            assertTrue(bob.settings.spoolConsented.first())
            assertTrue(bob.settings.spoolEnabled.first())
            assertEquals(setOf(MeshLab.SPOOL_URL), bob.settings.spoolUrls.first())
            assertEquals(setOf(room), bob.joinedRooms())

            // The room pins its members to each other (§7.4): Bob learns Alice from her profile in the room.
            assertTrue(
                "the room never introduced them",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { if (bob.peers.find(alice.nodeId)?.pubKey != null) 1 else 0 },
            )
            assertTrue(alice.postCommons(room, "welcome to the house"))
            assertTrue(
                "the post never reached Bob",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { bob.decrypted(room).count { it.second == "welcome to the house" } },
            )
            // Applying the same invite again changes nothing: still one relay, still one room.
            bob.applyInvite(alice.mintInvite())
            assertEquals(setOf(MeshLab.SPOOL_URL), bob.settings.spoolUrls.first())
            assertEquals(setOf(room), bob.joinedRooms())
        }

    /** A relay that drops every socket mid-way: the clients reconnect on their own and the next DM still crosses. */
    @Test
    fun aRelayThatDropsEverySocketReconvergesOnAFreshConnection() =
        runBlocking {
            val spool = FakeSpool()
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
            lab.meetOnTheRelay(alice, bob)

            spool.dropSockets()
            assertTrue(alice.sendDm(bob, "after the relay restarted"))
            lab.assertConverged(listOf(alice, bob), atLeast = 3, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { alice.dmThreadWith(bob)(it) }
        }

    /**
     * Work item 50 / ADR 2026-09.vej5: a relay whose route swallows the socket — a captive Wi-Fi the platform
     * still calls validated — is reported unreachable and never connected, and heals on its own once the
     * route does. The lab's one oracle on connection state, over the real plane and the real backoff.
     */
    @Test
    fun aRelayWhoseRouteSwallowsTheSocketIsUnreachableNotConnected() =
        runBlocking {
            val spool = FakeSpool()
            spool.blackhole()
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            assertTrue(
                "the dead route never read as unreachable: ${alice.manager.spoolStatus().map { it.connected to it.lastError }}",
                lab.tryAwait(1, timeoutMs = 20_000L) {
                    val statuses = alice.manager.spoolStatus()
                    assertTrue("a socket nothing answered counted as connected", statuses.none { it.connected })
                    statuses.count { it.lastError == ScopeSync.UNREACHABLE && it.dialFailures >= 2 }
                },
            )

            spool.unblackhole()
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
            lab.meetOnTheRelay(alice, bob)
            assertTrue(
                alice.manager
                    .spoolStatus()
                    .single()
                    .connected,
            )
            assertEquals(
                0,
                alice.manager
                    .spoolStatus()
                    .single()
                    .dialFailures,
            )
            assertTrue(alice.sendDm(bob, "once the route came back"))
            lab.assertConverged(listOf(alice, bob), atLeast = 3, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { alice.dmThreadWith(bob)(it) }
        }

    /** ADR 020: a rename with no radio path reaches the contact sealed, through the DM scope. */
    @Test
    fun aSealedProfileUpdateCrossesTheRelay() =
        runBlocking {
            val spool = FakeSpool()
            val alice = lab.node("alice", spool = spool).apply { setDisplayName("Alice") }
            val bob = lab.node("bob", spool = spool).apply { setDisplayName("Bob") }
            lab.meetOnTheRelay(alice, bob)

            alice.setDisplayName("Alice, renamed")
            assertTrue(
                "bob never saw the rename",
                lab.tryAwait(1, timeoutMs = MeshLab.SPOOL_AWAIT_MS) {
                    if (bob.peer(alice)?.name ==
                        "Alice, renamed"
                    ) {
                        1
                    } else {
                        0
                    }
                },
            )
            lab.assertConverged(listOf(alice, bob), atLeast = 2, timeoutMs = MeshLab.SPOOL_AWAIT_MS) { alice.dmThreadWith(bob)(it) }
        }

    private companion object {
        /** When a DM's radio copy reaches Bob after its spool copy's row (`aFrameTheRelayDeliversFirst…`). */
        val RADIO_LAG_MS = listOf(0L, 15L, 30L, 45L, 60L, 90L)
    }
}
