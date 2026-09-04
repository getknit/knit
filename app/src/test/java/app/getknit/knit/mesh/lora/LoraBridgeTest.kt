package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two-pocket bridge (ADR 044), end to end on the JVM.
 *
 * The scenario the plane exists for: two groups of phones, each meshed over BLE/NAN, too far apart to see
 * each other, with one board-holder in each. Both boards hear each other over LoRa. In this rig the LoRa
 * "air" reaches everyone (that is the whole point — the boards ARE in range); what separates the pockets is
 * `onForeignReachable`, which the composite transport populates only from short-range siblings and which
 * therefore means exactly "who is in my BLE/NAN clique". A board-less pocket member needs no transport here:
 * it exists only as a name in that set.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LoraBridgeTest {
    private var sigCounter = 0

    private fun frame(
        sender: String,
        body: String = "hello",
        sentAt: Long = 0L,
        type: String = FrameType.CHAT,
        recipientId: String? = null,
        relay: Boolean = true,
    ): WireEnvelope {
        val env =
            RelayEnvelope(
                type = type,
                id = "id-$sigCounter",
                senderId = sender,
                sentAt = sentAt,
                recipientId = recipientId,
                payload = WireCodec.encodePayload(ChatContent(body = body)),
            )
        val sig = ByteArray(64)
        sig[0] = (sigCounter shr 8).toByte()
        sig[1] = sigCounter.toByte()
        sigCounter++
        return WireEnvelope(relay = relay, sig = sig, signed = WireCodec.encodeEnvelope(env))
    }

    private fun idOf(wire: WireEnvelope) = WireCodec.decodeEnvelope(wire.signed)!!.id

    private fun prefixOf(wire: WireEnvelope) = LoraCtl.prefixOf(StoreDigest.hash64(idOf(wire)))

    /**
     * Stands in for `MeshManager`'s [app.getknit.knit.mesh.BridgeFrameSource] over `ForwardStore.liveFrames`:
     * a held set, the same prefix computation, and the same "what does their offer not name" diff.
     */
    private class FakeCustody {
        val held = mutableListOf<WireEnvelope>()
        var served = 0

        fun prefixes(limit: Int): IntArray = held.takeLast(limit).map { LoraCtl.prefixOf(StoreDigest.hash64(idFor(it))) }.toIntArray()

        fun missing(
            theirs: IntArray,
            limit: Int,
        ): List<WireEnvelope> {
            val have = theirs.toHashSet()
            return held
                .filter { LoraCtl.prefixOf(StoreDigest.hash64(idFor(it))) !in have }
                .sortedBy { LoraFramePolicy.backfillRank(WireCodec.decodeEnvelope(it.signed)!!) }
                .take(limit)
                .also { served += it.size }
        }

        private fun idFor(w: WireEnvelope) = WireCodec.decodeEnvelope(w.signed)!!.id
    }

    private class Rig(
        val node: String,
        val transport: LoraMeshTransport,
        val link: FakeMeshtasticLink,
        val metrics: MeshMetrics,
        val custody: FakeCustody,
        val received: MutableList<InboundFrame>,
    ) {
        fun status() = transport.status.value

        /**
         * One packet straight off the air onto this rig's board, bypassing the frame codec — what the
         * portnum/channel filter and [LoraMeshTransport.noteBoard] see. The defaults are a healthy
         * board-to-board reception on the bound channel.
         */
        fun hear(
            from: UInt,
            channelIndex: Int = 0,
            portnum: Int = MeshtasticProto.PORT_PRIVATE_APP,
            snr: Float? = 6.5f,
            rssi: Int? = -85,
        ) = link.deliver(from, channelIndex, portnum, byteArrayOf(1), snr, rssi)
    }

    private fun rig(
        air: FakeMeshtasticAir,
        nodeNum: UInt,
        node: String,
        scope: CoroutineScope,
        wallClock: (() -> Long)? = null,
        /**
         * True gives the node nothing to say at all — no profile to beacon and empty custody, so nothing it
         * holds can be backfilled either. Lets a test isolate what the control packets alone prove.
         */
        mute: Boolean = false,
        now: () -> Long,
    ): Rig {
        val link = FakeMeshtasticLink(nodeNum, air)
        val metrics = MeshMetrics()
        val custody = FakeCustody()
        // A node's own profile is in its own custody (`ORIGIN_SELF`), which is why its offer names it and a
        // far gateway never serves it back. One stable frame, like `MeshManager.signedProfile`'s stable id.
        val ownProfile = frame(node, type = FrameType.PROFILE, body = "p")
        if (!mute) custody.held += ownProfile
        val transport =
            LoraMeshTransport(
                selfId = { node },
                link = link,
                config = MutableStateFlow(LoraConfig("AA:$nodeNum", 0)),
                selfProfile = { ownProfile.takeIf { !mute } },
                scope = scope,
                metrics = metrics,
                clock = now,
                wallClock = wallClock ?: now,
                pace = LoraPacePolicy(minGapMs = 0),
                // No jitter: an offer goes out at exactly the midpoint of its interval.
                gossip = LoraGossipPolicy(random = { 0 }),
                offerPrefixes = { custody.prefixes(it) },
                framesMissing = { theirs, limit, _ -> custody.missing(theirs, limit) },
            )
        val received = mutableListOf<InboundFrame>()
        scope.launch {
            transport.inbound.collect {
                received += it
                // What `ForwardSync.onSeen` does for real on every first-seen relayed frame. Modelling it
                // matters here: it is what makes the far gateway's *next* offer name the frame, which is what
                // stops the bridge serving it again every round.
                custody.held += it.wire
            }
        }
        return Rig(node, transport, link, metrics, custody, received)
    }

    /**
     * Puts a second board in [a]'s pocket, **linked**, with whichever key ordering forces [a] passive, and
     * returns it. Retries node names until one hashes below [a]'s, since the election is keyed on the hash.
     */
    private fun forcePassive(
        a: Rig,
        air: FakeMeshtasticAir,
        scope: CoroutineScope,
        now: () -> Long,
    ): Rig {
        val selfKey = StoreDigest.hash64(a.node)
        val name = generateSequence(0) { it + 1 }.map { "mate$it" }.first { StoreDigest.hash64(it) < selfKey }
        val mate = rig(air, 8u, name, scope, now = now)
        mate.transport.start()
        a.transport.suppressDataPath(setOf(name))
        return mate
    }

    /** Long enough for the first gossip interval's midpoint (5 min / 2) plus a few pacer turns. */
    private val toFirstOffer = LoraGossipPolicy.MIN_INTERVAL_MS / 2 + 10_000

    @Test
    fun aFrameAuthoredByABoardLessPocketMemberAlreadyCrossesLive() =
        runTest {
            // The half of the bridge that shipped with ADR 038/039 and has never been pinned: onDeliver
            // re-fans a *relayed* frame, and nothing on the fan-out path checks authorship.
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            a.transport.onForeignReachable(setOf("a2"))
            b.transport.onForeignReachable(setOf("b2"))
            runCurrent()

            val fromA2 = frame("a2", body = "posted in pocket A", sentAt = testScheduler.currentTime)
            a.transport.fastFanout(fromA2)
            advanceTimeBy(5_000)
            runCurrent()

            assertTrue(
                "pocket B's gateway heard a frame nobody in its pocket authored",
                b.received.any { it.envelope.id == idOf(fromA2) },
            )
        }

    @Test
    fun aStaleFrameTheLiveFanOutRefusesStillCrossesViaTheBridge() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            a.transport.onForeignReachable(setOf("a2"))
            b.transport.onForeignReachable(setOf("b2"))
            runCurrent()

            // Said in pocket A while B's board was off, and now well past the freshness gate.
            val old = frame("a2", body = "said an hour ago", sentAt = 0L)
            advanceTimeBy(LoraFramePolicy.FRESH_MS + 60_000)
            runCurrent()
            a.transport.fastFanout(old)
            advanceTimeBy(5_000)
            runCurrent()
            assertFalse("the live plane refuses a custody re-serve", b.received.any { it.envelope.id == idOf(old) })
            assertTrue(a.metrics.snapshot().loraSuppressed > 0)

            // Let the two pockets converge on what they already hold, then introduce the stale frame.
            repeat(2) {
                advanceTimeBy(LoraGossipPolicy.MAX_INTERVAL_MS)
                runCurrent()
            }
            assertTrue("bob offered", b.metrics.snapshot().loraOfferSent > 0)
            assertTrue("alice heard it", a.metrics.snapshot().loraOfferReceived > 0)
            val bridgedBefore = a.metrics.snapshot().loraBridged

            // The bridge reads it from custody instead, on the next round of offers.
            a.custody.held += old
            repeat(3) {
                advanceTimeBy(LoraGossipPolicy.MAX_INTERVAL_MS)
                runCurrent()
            }

            assertTrue("which lands in pocket B", b.received.any { it.envelope.id == idOf(old) })
            assertEquals(
                "served once and not again, because bob's next offer names it",
                bridgedBefore + 1,
                a.metrics.snapshot().loraBridged,
            )
        }

    /**
     * The other half of Trickle, end to end: hearing an OFFER that announces a set which is not ours snaps
     * our own backed-off timer to the floor, so the second half of the exchange happens inside a short
     * reunion instead of waiting out up to fifteen minutes of somebody else's silence.
     *
     * Bob holds a **superset** of alice's set on purpose, and the one frame she lacks is a DM for a peer on
     * his own live link — which ADR 054 stops him serving. So nothing crosses the air in either direction:
     * neither `serveBackfill`'s reset nor the inbound-frame reset can explain the acceleration, only the
     * offer she heard. Bob is muted so his board's arrival is silent until that first OFFER.
     *
     * It pins the wake as much as the policy. Bob's offer lands early in alice's fifteen-minute interval, so
     * her sleeping gossip loop is parked *past* the end of the floor interval the reset opens: without
     * `gossipWake` she does not merely miss the acceleration, she wakes to an expired interval and doubles.
     */
    @Test
    fun hearingAnOfferForADifferentSetSnapsABackedOffTimerToTheFloor() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()

            // Alice's timer, undisturbed: intervals [0,5) [5,15) [15,30) [30,45) min, transmitting at each
            // midpoint — 2.5, 10, 22.5 and 37.5. Bob's board arrives at 28.3 min, so his first offer lands
            // at 30.8 — just inside her fourth interval, whose own transmit point is 37.5.
            advanceTimeBy(28 * 60_000 + 20_000)
            runCurrent()
            val offersBefore = a.metrics.snapshot().loraOfferSent
            assertEquals("three intervals, three offers", 3L, offersBefore)

            val b = rig(air, 2u, "bob", backgroundScope, mute = true) { testScheduler.currentTime }
            b.custody.held += a.custody.held // everything alice has...
            b.custody.held += frame("b3", body = "and one she lacks", recipientId = "b2")
            b.transport.start()
            b.transport.suppressDataPath(setOf("b2")) // ...which his own link covers, so he never serves it
            runCurrent()

            advanceTimeBy(toFirstOffer) // bob's first interval midpoint, at 30.8 min
            runCurrent()
            assertEquals("alice heard bob", 1L, a.metrics.snapshot().loraOfferReceived)
            assertEquals("and has nothing to serve him", 0L, a.metrics.snapshot().loraBridged)
            assertEquals("nor has she spoken yet", offersBefore, a.metrics.snapshot().loraOfferSent)

            // On her own schedule the next one was 6.7 minutes out, and 21.7 if she woke to a lapsed reset.
            advanceTimeBy(LoraGossipPolicy.MIN_INTERVAL_MS)
            runCurrent()
            assertEquals(
                "alice answers inside a floor interval rather than waiting out her backoff",
                offersBefore + 1,
                a.metrics.snapshot().loraOfferSent,
            )
            assertEquals("and no frame crossed to explain it", 0L, b.metrics.snapshot().loraBridged)
        }

    /**
     * The recipient gate on the bridge (ADR 054): a DM-form frame addressed to a peer this gateway holds a
     * live link to — or to the gateway itself — is never served across, however the far offer reads. The far
     * pocket would only ever be a carrier for it, and the link (or our own inbox) already has it.
     */
    @Test
    fun theBridgeNeverServesADmFormFrameItsAddresseeAlreadyHolds() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            a.transport.suppressDataPath(setOf("a2")) // a2 is on a live link in pocket A
            runCurrent()

            val toLinked = frame("a3", body = "dm for a2", recipientId = "a2")
            val toSelf = frame("a3", body = "dm for alice", recipientId = "alice")
            val toFar = frame("a3", body = "dm for b2", recipientId = "b2")
            val room = frame("a3", body = "room post")
            a.custody.held += listOf(toLinked, toSelf, toFar, room)

            advanceTimeBy(toFirstOffer)
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()

            assertTrue("the DM for a far peer crosses", b.received.any { it.envelope.id == idOf(toFar) })
            assertTrue("the room post crosses", b.received.any { it.envelope.id == idOf(room) })
            assertFalse("the DM for a linked peer stays off the air", b.received.any { it.envelope.id == idOf(toLinked) })
            assertFalse("the DM for the gateway itself stays off the air", b.received.any { it.envelope.id == idOf(toSelf) })
            assertEquals(2L, a.metrics.snapshot().loraSkippedLinked)
        }

    /**
     * ADR 057 stops the fan-out re-offering a profile publish it has already put on the air. That must not
     * take the *repair* path with it: a far gateway whose offer says it lacks a profile still has to be
     * served one, or a pocket that came up after the fan-out never gets a key it cannot ask for (the
     * plane refuses `keyreq`). `serveOne` is deliberately not gated on the publish dedup.
     */
    @Test
    fun theBridgeStillServesAProfileTheFanOutHasStoppedReOffering() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()

            // The fan-out puts carol's profile on the air once, to a horizon that is currently empty, and
            // then holds that publish back however many times it is re-offered (ADR 057).
            val carol = frame("carol", type = FrameType.PROFILE, body = "p")
            a.custody.held += carol
            a.transport.fastFanout(carol)
            advanceTimeBy(4_000)
            runCurrent()
            a.transport.fastFanout(carol)
            runCurrent()
            assertEquals("the fan-out stops re-offering the same publish", 1L, a.metrics.snapshot().loraProfileRefanSkipped)

            // A far pocket comes up afterwards, having never heard it — and cannot ask for it, since this
            // plane refuses `keyreq`. Its offer names what it holds, and the repair path must still answer.
            // Deliberately inside SIG_TTL_MS as well: since ADR 2026-09.y8pu neither dedup set gates the
            // repair, so this needs no wait to skip past the signature the unheard fan-out already recorded.
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            b.transport.start()
            runCurrent()
            advanceTimeBy(toFirstOffer)
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()

            assertTrue("the backfill still repairs a profile a far pocket lacks", b.received.any { it.envelope.id == idOf(carol) })
        }

    /**
     * The field failure this exists for (ADR 2026-09.y8pu, Pixel 9 / Pixel 7, 2026-09-02): a Nearby-room post
     * sent while the only other board was out of LoRa range, still undelivered after coming back into range.
     *
     * The fan-out put it on the air to an empty sky and recorded its signature in [LoraMeshTransport.sigSeen]
     * exactly as a heard transmission would — this plane has no acks, so the two are indistinguishable there.
     * For the ten minutes that followed, the one path that could repair it skipped it. A far gateway's offer
     * is positive evidence the frame did not arrive, so it now outranks that record.
     */
    @Test
    fun aRoomPostFannedOutToAnEmptySkyIsStillBackfilledInsideTheDedupWindow() =
        runTest {
            val air = FakeMeshtasticAir()
            // Pocket A is one phone with a board and no BLE/NAN links at all — so every publisher it hears
            // is a far gateway, and it is ACTIVE with no rival to stand down for.
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()

            val room = frame("alice", body = "posted while out of range", sentAt = testScheduler.currentTime)
            a.custody.held += room
            a.transport.fastFanout(room)
            advanceTimeBy(5_000)
            runCurrent()

            // Back in LoRa range a couple of minutes later — well inside the 10-minute signature window that
            // the fan-out nobody heard has already spent.
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            b.transport.start()
            runCurrent()
            advanceTimeBy(toFirstOffer)
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()

            assertTrue(
                "the room post is inside SIG_TTL_MS of its own unheard fan-out",
                testScheduler.currentTime < LoraMeshTransport.SIG_TTL_MS,
            )
            assertTrue("the backfill still carries it across", b.received.any { it.envelope.id == idOf(room) })
        }

    @Test
    fun theBridgeServesOnlyWhatTheOfferDoesNotName() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()

            val shared = frame("a2", body = "both pockets have this")
            val onlyA = frame("a2", body = "only pocket A has this")
            a.custody.held += listOf(shared, onlyA)
            b.custody.held += shared

            advanceTimeBy(toFirstOffer)
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()

            assertTrue("the frame B lacks crosses", b.received.any { it.envelope.id == idOf(onlyA) })
            assertFalse(
                "the frame B already holds is not re-sent",
                b.received.any { it.envelope.id == idOf(shared) },
            )
        }

    /**
     * ADR 2026-09.rre4: an offer buys four slots, and the room takes one before the DMs do.
     *
     * The scarce resource here is the slot, not the queue position — once both frames are paid for the pacing
     * queue still transmits the DM first, since [FrameClass] answers a different question. What the rank buys
     * is that a pocket with a backlog of DMs cannot spend every round on them while the room never crosses.
     */
    @Test
    fun aScarceAllowanceSpendsASlotOnTheRoomBeforeItSpendsFourOnDms() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            a.transport.onForeignReachable(setOf("a2"))
            b.transport.onForeignReachable(setOf("b2"))
            runCurrent()

            // More missing frames than one offer can buy. Alice's profile is not among them — her session-up
            // beacon already reached B, so B's offer names it — leaving one room post against a backlog of
            // five DMs for a peer only pocket B can reach.
            val room = frame("a2", body = "room post")
            val dms = List(5) { frame("a2", body = "dm $it", recipientId = "b2") }
            a.custody.held += room
            a.custody.held += dms

            advanceTimeBy(toFirstOffer)
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()

            val crossed = b.received.mapTo(HashSet()) { it.envelope.id }
            assertTrue("the room post takes one of the four slots", idOf(room) in crossed)
            assertEquals(
                "the room takes one of the four slots, so three of the five DMs cross rather than four",
                3,
                dms.count { idOf(it) in crossed },
            )
        }

    @Test
    fun aSecondBoardInThePocketGoesPassiveAndPutsNothingOnTheAir() =
        runTest {
            val air = FakeMeshtasticAir()
            // Two board-holders in pocket A. They see each other over BLE/NAN, so exactly one should speak.
            val a1 = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val a2 = rig(air, 3u, "amber", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a1.transport.start()
            a2.transport.start()
            b.transport.start()
            // One pocket = a live BLE/NAN link between them, which is what makes standing down safe: the
            // active board can actually be handed the passive one's traffic.
            a1.transport.suppressDataPath(setOf("amber"))
            a2.transport.suppressDataPath(setOf("alice"))
            b.transport.suppressDataPath(emptySet())
            runCurrent()

            // Let the offers settle the election.
            advanceTimeBy(toFirstOffer)
            runCurrent()
            advanceTimeBy(2 * LoraGossipPolicy.MIN_INTERVAL_MS)
            runCurrent()

            val roles = listOf(a1, a2).map { it.status().role }
            assertEquals(
                "exactly one board in the pocket is active",
                1,
                roles.count { it == LoraGatewayPolicy.Role.ACTIVE },
            )
            val passive = listOf(a1, a2).first { it.status().role == LoraGatewayPolicy.Role.PASSIVE }
            val active = listOf(a1, a2).first { it.status().role == LoraGatewayPolicy.Role.ACTIVE }
            val passiveSentBefore = passive.link.sent.size

            // A frame the whole pocket sees is fanned by both phones; only one of them may reach the air.
            val post = frame("a4", body = "one copy please", sentAt = testScheduler.currentTime)
            a1.transport.fastFanout(post)
            a2.transport.fastFanout(post)
            advanceTimeBy(5_000)
            runCurrent()

            assertEquals("the passive board transmitted nothing", passiveSentBefore, passive.link.sent.size)
            assertTrue(passive.metrics.snapshot().loraPassive > 0)
            assertTrue("the active one carried it", active.metrics.snapshot().loraSent > 0)
            assertEquals("bob got exactly one copy", 1, b.received.count { it.envelope.id == idOf(post) })

            // And bob, in the other pocket, is never suppressed by either of them.
            assertEquals(LoraGatewayPolicy.Role.ACTIVE, b.status().role)
        }

    @Test
    fun twoBoardsThatOnlySightEachOtherBothKeepTransmitting() =
        runTest {
            // The field regression: two phones far enough apart that neither holds a BLE/NAN link, but close
            // enough to have sighted each other (a presence advert, or a Wi-Fi Aware ghost that has not aged
            // out). Before the fix the higher-keyed one stood down and went completely silent — no room posts,
            // no DMs, and no ✓✓ ticks — with no peer carrying any of it.
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            // Sighted, but no live link either way.
            a.transport.onForeignReachable(setOf("bob"))
            b.transport.onForeignReachable(setOf("alice"))
            a.transport.suppressDataPath(emptySet())
            b.transport.suppressDataPath(emptySet())
            runCurrent()
            advanceTimeBy(toFirstOffer)
            runCurrent()
            advanceTimeBy(2 * LoraGossipPolicy.MIN_INTERVAL_MS)
            runCurrent()

            assertEquals(LoraGatewayPolicy.Role.ACTIVE, a.status().role)
            assertEquals(LoraGatewayPolicy.Role.ACTIVE, b.status().role)

            val fromB = frame("bob", body = "does this cross?", sentAt = testScheduler.currentTime)
            b.transport.fastFanout(fromB)
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue("both directions carry", a.received.any { it.envelope.id == idOf(fromB) })

            val fromA = frame("alice", body = "and back", sentAt = testScheduler.currentTime)
            a.transport.fastFanout(fromA)
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue("including from the one that used to fall silent", b.received.any { it.envelope.id == idOf(fromA) })
        }

    @Test
    fun aPassiveBoardStillSendsItsTargetedTicks() =
        runTest {
            // A `relay = false` targeted send is owed by exactly one node and is never flooded, so no
            // co-pocket gateway holds a copy to relay OR to duplicate. Gating it on the role stranded
            // AckSync's ✓✓ ticks on a passive board, which then retries them for 24 h and lands none.
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()

            // Alice hears bob so he is LoRa-reachable, then alice is forced passive by a linked co-pocket board.
            b.transport.fastFanout(frame("bob", body = "hi", sentAt = testScheduler.currentTime))
            advanceTimeBy(5_000)
            runCurrent()
            val passive = forcePassive(a, air, backgroundScope) { testScheduler.currentTime }
            advanceTimeBy(toFirstOffer + 30_000)
            runCurrent()
            assertEquals(LoraGatewayPolicy.Role.PASSIVE, a.status().role)

            val sentBefore = a.link.sent.size
            // AckSync's sealed ✓✓: a chat frame addressed to the author, `relay = false`.
            a.transport.fastSend(frame("alice", recipientId = "bob", body = "tick", relay = false), Peer("bob"))
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue("the tick went out despite the passive role", a.link.sent.size > sentBefore)
        }

    @Test
    fun oneRadioRelayingOthersFramesCountsAsOneRadioAndSeveralPeople() =
        runTest {
            // Field report: "3 peers heard over LoRa" with only two radios in existence. The count was of
            // frame *authors*, and a gateway relays and backfills frames authored by people nowhere near it,
            // so the number legitimately outran the hardware. Radios and people are now counted separately.
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()

            // Bob's single radio puts three different authors' frames on the air — his own and two he relays.
            listOf("bob", "carol", "dave").forEach { author ->
                b.transport.fastFanout(frame(author, body = "from $author", sentAt = testScheduler.currentTime))
                advanceTimeBy(5_000)
                runCurrent()
            }

            assertEquals("one other radio, however many people it speaks for", 1, a.status().boardsHeard)
            assertEquals("three people are reachable through it", 3, a.status().heard)
        }

    @Test
    fun aGatewayThatOnlyPublishesOffersStillCountsAsARadioInRange() =
        runTest {
            // An offer is a transmission: a board doing nothing but gossiping used to be invisible in the row.
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope, mute = true) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()
            advanceTimeBy(toFirstOffer + 30_000)
            runCurrent()

            assertTrue("bob offered", b.metrics.snapshot().loraOfferSent > 0)
            assertEquals("the offer alone proves a radio is there", 1, a.status().boardsHeard)
            assertEquals("but nobody has spoken, so no person is reachable yet", 0, a.status().heard)
        }

    @Test
    fun theSignalRowIgnoresEverythingTheBoardMerelyOverhears() =
        runTest {
            // Field report: the row decayed to -17 dB / -105 dBm over hours and looked healthy again only
            // after a board reboot, while the real board-to-board link measured +6 dB / -7 dBm throughout.
            // The reading was taken in the session, ahead of every filter, so a stock board's public primary
            // channel — strangers four to seven hops out, plus the NodeDB replay the firmware sends at each
            // handshake — pinned it at the noise floor and it stuck there.
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            a.hear(2u)
            runCurrent()

            // A stranger relayed off another channel, and the handshake replay's shape: an SNR, no RSSI.
            a.hear(3u, channelIndex = 1, snr = -17.25f, rssi = -105)
            a.hear(4u, portnum = MeshtasticProto.PORT_TELEMETRY, snr = -12.25f, rssi = null)
            runCurrent()

            assertEquals("the reading is still the radio we actually talk to", 6.5f, a.status().lastSnr!!, 0.001f)
            assertEquals(-85, a.status().lastRssi)
            assertEquals("and neither stranger invented a radio", 1, a.status().boardsHeard)
        }

    @Test
    fun aReceptionMissingAnRssiKeepsTheOneThatRadioLastGave() =
        runTest {
            // Every reception proves the radio is there and when; not all of them carry both numbers. Taking
            // the newer packet wholesale would blank half the row rather than refresh it.
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            a.hear(2u)
            a.hear(2u, snr = 7.5f, rssi = null)
            runCurrent()

            assertEquals("the fresher SNR", 7.5f, a.status().lastSnr!!, 0.001f)
            assertEquals("the RSSI it came with last", -85, a.status().lastRssi)
        }

    @Test
    fun theSignalReadingAgesOutWithTheRadioItBelongsTo() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            a.hear(2u)
            runCurrent()
            assertEquals(6.5f, a.status().lastSnr!!, 0.001f)

            advanceTimeBy(LoraMeshTransport.REACHABLE_LINGER_MS + LoraMeshTransport.LINGER_SWEEP_MS + 1_000)
            runCurrent()

            assertEquals("the radio aged out", 0, a.status().boardsHeard)
            assertNull("so there is no link left to report a reading for", a.status().lastSnr)
            assertNull(a.status().lastRssi)
        }

    @Test
    fun restartingThePlaneDropsTheSignalReading() =
        runTest {
            // It used to live in the link and survive `stop()`, so a stale number read as the state of a link
            // that had not been re-established yet — which is exactly what a board reboot looks like.
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            a.hear(2u)
            runCurrent()
            assertEquals(6.5f, a.status().lastSnr!!, 0.001f)

            a.transport.stop()
            runCurrent()

            assertNull(a.status().lastSnr)
            assertNull(a.status().lastRssi)
        }

    @Test
    fun aFarGatewayLeavingTheAirDoesNotStopTheOtherPocketBridging() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()
            advanceTimeBy(toFirstOffer)
            runCurrent()

            b.transport.stop()
            advanceTimeBy(60_000)
            runCurrent()

            // Alice's board is now alone; she must stay active rather than deferring to a gateway that left.
            assertEquals(LoraGatewayPolicy.Role.ACTIVE, a.status().role)
            val post = frame("a2", body = "still bridging", sentAt = testScheduler.currentTime)
            val before = a.link.sent.size
            a.transport.fastFanout(post)
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue(a.link.sent.size > before)
        }

    @Test
    fun aTransientListenerIsBackfilledWithoutMultiplyingWhatTheGatewaysSay() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            a.custody.held += frame("a2", body = "pocket A history")
            runCurrent()

            // Someone wanders into range with a board and an empty store, in nobody's pocket.
            val t = rig(air, 9u, "trav", backgroundScope) { testScheduler.currentTime }
            t.transport.start()
            runCurrent()
            advanceTimeBy(toFirstOffer)
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()

            assertTrue("the newcomer is served the history it lacks", t.received.isNotEmpty())
            assertTrue(
                "and it is served the author's profile first, so it can verify anything at all",
                a.metrics.snapshot().loraSent > 0,
            )
        }

    @Test
    fun onePublishersRepeatedOffersCannotDragTheWholeStoreOntoTheAir() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()
            repeat(40) { a.custody.held += frame("a2", body = "history $it") }

            // Bob keeps announcing what he holds, inside one serve window.
            repeat(6) {
                advanceTimeBy(LoraGossipPolicy.MIN_INTERVAL_MS)
                runCurrent()
            }

            val bridged = a.metrics.snapshot().loraBridged
            assertTrue("some history crossed", bridged > 0)
            assertTrue(
                "but one publisher cannot exceed its hourly allowance ($bridged)",
                bridged <= LoraMeshTransport.SERVE_CAP_PER_HOUR,
            )
        }
}
