package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.FanoutHint
import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshPost
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.PublicPostRefusal
import app.getknit.knit.mesh.StoreDigest
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.link.FastFrameCodec
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // one rig, many scenarios — splitting it would duplicate the rig, not clarify
class LoraMeshTransportTest {
    private var sigCounter = 0

    /** A decodable signed frame with a unique 64-byte sig (the transport never verifies it — only decodes). */
    private fun frame(
        type: String,
        sender: String,
        recipientId: String? = null,
        group: GroupInfo? = null,
        relay: Boolean = true,
        body: String = "hi there",
        sentAt: Long = 0L,
    ): WireEnvelope {
        val env =
            RelayEnvelope(
                type = type,
                id = "id-$sigCounter",
                senderId = sender,
                sentAt = sentAt,
                recipientId = recipientId,
                group = group,
                payload = WireCodec.encodePayload(ChatContent(body = body)),
            )
        val sig = ByteArray(64)
        sig[0] = (sigCounter shr 8).toByte()
        sig[1] = sigCounter.toByte()
        sigCounter++
        return WireEnvelope(relay = relay, sig = sig, signed = WireCodec.encodeEnvelope(env))
    }

    private fun profile(sender: String): WireEnvelope = frame(FrameType.PROFILE, sender, body = "x".repeat(20))

    /**
     * The unsigned form (ADR 059): a `relay = false` DM-form chat from [sender] to [to] with an EMPTY sig — the
     * v3 live-link tick as the transport sees it (it never verifies; the policy admits it by shape).
     */
    private fun unsignedTick(
        sender: String,
        to: String,
    ): WireEnvelope {
        val env =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = "tick-$sigCounter",
                senderId = sender,
                recipientId = to,
                payload = WireCodec.encodePayload(ChatContent(body = "")),
            )
        sigCounter++
        return WireEnvelope(relay = false, sig = ByteArray(0), signed = WireCodec.encodeEnvelope(env))
    }

    /**
     * ADR 059: an unsigned tick has no signature to dedup on, so the window keys it by frame id — two ticks
     * both ride, a verbatim resend does not.
     */
    @Test
    fun unsignedTicksDedupByIdNotByTheirEmptySignature() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "ping"))
            advanceTimeBy(4_000)
            runCurrent()
            val before = b.link.sent.size

            val first = unsignedTick("bob", "alice")
            b.transport.fastSend(first, Peer("alice"))
            advanceTimeBy(4_000)
            runCurrent()
            val afterFirst = b.link.sent.size
            assertEquals("an unsigned tick rides as one packet", before + 1, afterFirst)

            b.transport.fastSend(unsignedTick("bob", "alice"), Peer("alice"))
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("a second unsigned tick with its own id is not shadowed by the first", afterFirst + 1, b.link.sent.size)

            b.transport.fastSend(first, Peer("alice")) // AckSync's verbatim retry inside the window
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("the verbatim resend is suppressed", afterFirst + 1, b.link.sent.size)
            a.transport.stop()
            b.transport.stop()
        }

    /** A decodable broadcast chat around a hand-built [payload] (the transport only decodes the envelope, never the content). */
    private fun rawChat(payload: ByteArray): WireEnvelope {
        val env = RelayEnvelope(type = FrameType.CHAT, id = "id-$sigCounter", senderId = "alice", payload = payload)
        val sig = ByteArray(64)
        sig[1] = sigCounter.toByte()
        sigCounter++
        return WireEnvelope(relay = true, sig = sig, signed = WireCodec.encodeEnvelope(env))
    }

    @Test
    fun everyPacketLeavesWithHopsToSpend() =
        runTest {
            // Measured on a 2.8 board: omitting `hop_limit` does not ride the node's configured default, it
            // rides zero, and a packet born with no hops left is repeated by nobody — so ADR 045's borrowed
            // hops never happened and ADR 044's bridge only ever worked board-to-board direct. The field has
            // to be stated, and nothing that reaches the air may leave it out.
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "north gate, ten minutes"))
            runCurrent()

            assertTrue("something was sent", a.link.sentHopLimits.isNotEmpty())
            assertTrue(
                "every packet — beacon, offer and chat alike — carries ${LoraMeshTransport.HOP_LIMIT} hops",
                a.link.sentHopLimits.all { it == LoraMeshTransport.HOP_LIMIT },
            )
        }

    /** ADR 060: on this plane every frame the transcoder reproduces rides `0x05` (the flag-day), and lands. */
    @Test
    fun aTranscodableFrameLeavesTranscodedAndLandsOnTheOtherBoard() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()
            val transcodedBefore = a.metrics.snapshot().loraTranscoded // the profile beacon already rode 0x05
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "north gate, ten minutes"))
            runCurrent()
            assertEquals("one 0x05 packet on the air", FastFrameCodec.TAG_TRANSCODED, a.link.sent.last()[0])
            assertEquals(transcodedBefore + 1, a.metrics.snapshot().loraTranscoded)
            assertEquals(0L, a.metrics.snapshot().transcodeFallbacks)
            assertTrue("bob decodes it through the same inbound path", b.received.any { it.envelope.senderId == "alice" })
            assertTrue(
                b.metrics
                    .snapshot()
                    .fastDropsByReason
                    .isEmpty(),
            )
            a.transport.stop()
            b.transport.stop()
        }

    /**
     * ADR 2026-09.mhs5: against a 2.8 board a frame under the signature cliff leaves **grown past it** — the firmware
     * would otherwise bolt on 66 bytes of its own — and the far side decodes it exactly as before.
     */
    @Test
    fun aFrameUnderTheSignatureCliffLeavesPaddedPastItOnA28Board() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope, firmware = "2.8.0.7239fe8") { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()
            val paddedBefore = a.metrics.snapshot().loraPadded
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "north gate, ten minutes. bring the long cable."))
            runCurrent()
            val sent = a.link.sent.last()
            assertEquals("one packet, one byte past the cliff", MeshtasticProto.MAX_SIGNED_PAYLOAD + 1, sent.size)
            assertEquals(paddedBefore + 1, a.metrics.snapshot().loraPadded)
            assertTrue("and bob still decodes it through the same inbound path", b.received.any { it.envelope.senderId == "alice" })
            assertTrue(
                b.metrics
                    .snapshot()
                    .fastDropsByReason
                    .isEmpty(),
            )
            a.transport.stop()
            b.transport.stop()
        }

    /** The same frame against a pre-2.8 board, which signs nothing: a pad there would be pure loss. */
    @Test
    fun aPre28BoardIsLeftExactlyAsItWas() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "north gate, ten minutes. bring the long cable."))
            runCurrent()
            assertTrue(
                "still under the cliff, unpadded",
                a.link.sent
                    .last()
                    .size <= MeshtasticProto.MAX_SIGNED_PAYLOAD,
            )
            assertEquals(0L, a.metrics.snapshot().loraPadded)
            a.transport.stop()
            b.transport.stop()
        }

    /** A frame the transcoder cannot reproduce keeps the `0x03` framing and is counted, never lost or mangled. */
    @Test
    fun aFrameTheTranscoderRefusesStillRidesCompactAndIsCounted() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()

            // An EncEnvelope hand-encoded with `nonce` before `v` — a key order kotlinx never emits, so the
            // rebuild would put the elided nonce back in the wrong slot and the transcoder refuses the frame.
            fun tstr(s: String) = byteArrayOf((0x60 + s.length).toByte()) + s.encodeToByteArray()
            val enc =
                byteArrayOf(0xA4.toByte()) + tstr("nonce") + byteArrayOf(0x40) + tstr("v") + byteArrayOf(0x03) +
                    tstr("ct") + byteArrayOf(0x44, 1, 2, 3, 4) + tstr("keys") + byteArrayOf(0x80.toByte())
            val transcodedBefore = a.metrics.snapshot().loraTranscoded
            a.transport.fastFanout(rawChat(byteArrayOf(0xA1.toByte()) + tstr("enc") + enc))
            runCurrent()
            assertEquals("0x03 carries it", FastFrameCodec.TAG_COMPACT, a.link.sent.last()[0])
            assertEquals(1L, a.metrics.snapshot().transcodeFallbacks)
            assertEquals("…and it is not counted as transcoded", transcodedBefore, a.metrics.snapshot().loraTranscoded)
            assertTrue(b.received.any { it.envelope.senderId == "alice" })
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun theToRadioOverheadIsMeasuredAndAnMtu255BoardTakes228BytePayloads() {
        assertEquals(27, LoraMeshTransport.TORADIO_OVERHEAD)
        assertEquals(228, 255 - LoraMeshTransport.TORADIO_OVERHEAD)
        assertEquals(228, LoraMeshTransport.PRE_READY_PAYLOAD)
    }

    /**
     * A frame fanned out while the board is still connecting is chunked for the pre-Ready floor, never the
     * protocol maximum. The `TOO_LARGE` NAKs the lab saw at every session-up were exactly these: the pacer
     * drops one queued frame per tick while the link is not Ready, the rest of the start-up burst waits in the
     * queue chunked at `maxPayload`'s initial value — 233 until this fix — and drains into the router the
     * moment Ready lands. Once Ready, the negotiated cap (231 at MTU 512) applies to new frames.
     */
    @Test
    fun framesFannedOutBeforeReadyAreChunkedForTheFloorAndNeverPastTheCap() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            // Incompressible bodies (random printable ASCII): the codec deflates text, so a repeated letter
            // would ride in one packet and exercise no chunking at all.
            val random = Random(11)

            fun noise() = String(CharArray(450) { (0x21 + random.nextInt(0x5E)).toChar() }) // ~3 parts at either cap

            a.link.readyOnStart = false // the board is still connecting when the mesh fans frames at us
            a.transport.start()
            runCurrent()
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = noise()))
            a.link.ready() // the handshake completes (at MTU 512) with that frame still queued
            runCurrent()
            advanceTimeBy(30_000)
            runCurrent()
            val preReady = a.link.sent.toList()
            assertTrue("the queued frame fragmented (${preReady.map { it.size }})", preReady.count { it.size >= 200 } >= 2)
            assertTrue("every part fits an MTU-255 board", preReady.all { it.size <= LoraMeshTransport.PRE_READY_PAYLOAD })
            assertTrue("and none was chunked at the old protocol maximum", preReady.none { it.size > 228 })

            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = noise()))
            advanceTimeBy(30_000)
            runCurrent()
            val afterReady = a.link.sent.drop(preReady.size)
            assertTrue("after Ready the negotiated cap (231 at MTU 512) applies", afterReady.any { it.size == MeshtasticProto.MAX_PAYLOAD })
            assertTrue(afterReady.all { it.size <= MeshtasticProto.MAX_PAYLOAD })
            a.transport.stop()
        }

    /** A high-entropy body that will not deflate below the LoRa packet cap, so the frame truly fragments. */
    private fun incompressibleBody(chars: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val rng = kotlin.random.Random(1234)
        return buildString(chars) { repeat(chars) { append(alphabet[rng.nextInt(alphabet.length)]) } }
    }

    private fun profileSource(sender: String): suspend () -> WireEnvelope = { profile(sender) }

    private class Rig(
        val transport: LoraMeshTransport,
        val link: FakeMeshtasticLink,
        val metrics: MeshMetrics,
        val received: MutableList<InboundFrame>,
    )

    private fun rig(
        air: FakeMeshtasticAir,
        nodeNum: UInt,
        selfNode: String,
        scope: kotlinx.coroutines.CoroutineScope,
        config: kotlinx.coroutines.flow.Flow<LoraConfig?> = MutableStateFlow(LoraConfig("AA:$nodeNum", 0)),
        farFrames: suspend (String) -> List<WireEnvelope> = { emptyList() },
        channelName: String = KnitChannel.NAME,
        pace: LoraPacePolicy = LoraPacePolicy(minGapMs = 0),
        // No jitter, for the reason LoraGossipPolicy documents the seam: its Trickle timer transmits at a
        // *random* point in each interval's second half, so the default policy puts an OFFER on the air at a
        // wall-clock-independent but run-dependent time. Any test that advances virtual time across an
        // interval boundary then counts a packet it did not send — which is not hypothetical: it made
        // `aProfileIsFannedOncePerPublishNotOnEverySeenSetLapse` fail on CI (`expected:<3> but was:<4>`,
        // the offer landing inside its 4 s re-fan window) while passing everywhere else. With `random = { 0 }`
        // the offer goes out at exactly the midpoint, as it already does in LoraBridgeTest.
        gossip: LoraGossipPolicy = LoraGossipPolicy(random = { 0 }),
        firmware: String = "2.5.0",
        publicKey: String? = null,
        onPublicPost: suspend (MeshPost) -> Unit = {},
        onBoardBound: suspend (BoardBinding) -> Unit = {},
        now: () -> Long,
    ): Rig {
        val link = FakeMeshtasticLink(nodeNum, air, channelName, firmware, publicKey)
        val metrics = MeshMetrics()
        val transport =
            LoraMeshTransport(
                selfId = { selfNode },
                link = link,
                config = config,
                selfProfile = profileSource(selfNode),
                farFrames = farFrames,
                onPublicPost = onPublicPost,
                onBoardBound = onBoardBound,
                scope = scope,
                metrics = metrics,
                clock = now,
                wallClock = now,
                pace = pace,
                gossip = gossip,
            )
        val received = mutableListOf<InboundFrame>()
        scope.launch { transport.inbound.collect { received += it } }
        return Rig(transport, link, metrics, received)
    }

    @Test
    fun readyMakesTheTransportHealthyAndBeaconsAProfile() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            advanceTimeBy(1)
            runCurrent()
            assertEquals(TransportHealth.Healthy, a.transport.health.value)
            assertEquals(1L, a.metrics.snapshot().loraSessionUps)
            assertTrue("a self-profile beacon went out on session up", a.link.sent.isNotEmpty())
            a.transport.stop()
        }

    @Test
    fun aRoomChatCrossesToTheOtherNodeAndMarksItReachable() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()

            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "north gate in ten"))
            runCurrent()

            val delivered = b.received.firstOrNull { it.envelope.type == FrameType.CHAT && it.envelope.senderId == "alice" }
            assertTrue("bob received alice's room chat over LoRa", delivered != null)
            assertEquals("fromNodeId is the frame's senderId", "alice", delivered!!.fromNodeId)
            assertTrue(
                "bob now sees alice as reachable",
                b.transport.reachable.value
                    .any { it.nodeId == "alice" },
            )
            assertTrue("bob received at least the chat", b.metrics.snapshot().loraReceived >= 1)
            a.transport.stop()
            b.transport.stop()
        }

    // --- The Meshtastic room: reading the board's own primary channel into a local room. ---

    /**
     * A rig on a **provisioned** board — the primary at index 0, Knit in a secondary. The default rig binds
     * the Knit channel to index 0 (the lab shape), and on such a board there is no primary to mirror.
     */
    private fun TestScope.bridgeRig(
        air: FakeMeshtasticAir,
        posts: MutableList<MeshPost>,
        primaryName: String = "",
        primaryPsk: ByteArray = ByteArray(0),
        firmware: String = "2.5.0",
    ): Rig {
        val r =
            rig(
                air,
                1u,
                "alice",
                backgroundScope,
                config = MutableStateFlow(LoraConfig("AA:1", 1)),
                firmware = firmware,
                onPublicPost = { posts += it },
            ) { testScheduler.currentTime }
        r.transport.start()
        // After runCurrent, not before: start() only *queues* the transport's jobs, and the link's own
        // start() then publishes the default single-Knit-channel Ready — which would overwrite this.
        runCurrent()
        r.link.readyProvisioned(knitIndex = 1, primaryName = primaryName, primaryPsk = primaryPsk)
        runCurrent()
        return r
    }

    @Test
    fun aPublicChatOnThePrimaryIsPublishedIntoKnitWithItsNodeDbName() =
        runTest {
            val air = FakeMeshtasticAir()
            val posts = mutableListOf<MeshPost>()
            val r = bridgeRig(air, posts)
            r.link.nodes.value = mapOf(0xdeadbeefu to BoardOwner(longName = "Bob", shortName = "Bob"))

            r.link.deliverPublicText(from = 0xdeadbeefu, body = "anyone around?", id = 77u)
            runCurrent()

            val post = posts.single()
            assertEquals(0xdeadbeefL, post.node)
            assertEquals(77L, post.packetId)
            assertEquals("anyone around?", post.body)
            assertEquals("the NodeDB puts a name on the speaker", "Bob", post.name)
            assertEquals("LongFast", post.channel)
            assertEquals(1L, r.metrics.snapshot().meshPostIngested)
            r.transport.stop()
        }

    @Test
    fun readingThePublicChannelCostsNoAirtimeAtAll() =
        runTest {
            // The whole premise of the receive-only phase. Nothing is transmitted, so nothing may touch the
            // pacer, the queue or the airtime ledger — hearing a neighbourhood's chat must be free.
            val air = FakeMeshtasticAir()
            val posts = mutableListOf<MeshPost>()
            val r = bridgeRig(air, posts)
            val before = r.link.sent.size

            repeat(5) { i -> r.link.deliverPublicText(from = 0xdeadbeefu, body = "post $i", id = (100 + i).toUInt()) }
            runCurrent()

            assertEquals(5, posts.size)
            assertEquals("not one packet went out", before, r.link.sent.size)
            assertEquals(0L, r.metrics.snapshot().loraSent)
            r.transport.stop()
        }

    @Test
    fun aStockNeighbourIsNotCountedAsARadioInRange() =
        runTest {
            // `boardsHeard` means "radios that sent a *Knit* frame" — which is what makes "heard nobody" the
            // ordinary state of a solo user, and why the preset-mismatch notice cannot be gated on evidence.
            // Counting the whole neighbourhood's stock radios here would quietly change all of that.
            val air = FakeMeshtasticAir()
            val posts = mutableListOf<MeshPost>()
            val r = bridgeRig(air, posts)

            r.link.deliverPublicText(from = 0xdeadbeefu, body = "hi", id = 5u)
            runCurrent()

            assertEquals(1, posts.size)
            assertEquals(0, r.transport.status.value.boardsHeard)
            assertTrue(
                "nor is its author a reachable Knit peer",
                r.transport.reachable.value
                    .isEmpty(),
            )
            r.transport.stop()
        }

    @Test
    fun aRenamedOrRekeyedPrimaryIsMirroredAsConfigured() =
        runTest {
            // The user's own channel on the user's own board, shown to the user under the name they gave it.
            // Nothing heard here leaves the phone, so there is no room a private channel could leak into; the
            // `lora_custom_primary` warning is about Knit's own RF slot, and still shows.
            val air = FakeMeshtasticAir()
            val posts = mutableListOf<MeshPost>()
            val r = bridgeRig(air, posts, primaryName = "Crew", primaryPsk = ByteArray(16) { 7 })

            r.link.deliverPublicText(from = 0xdeadbeefu, body = "our own business", id = 5u)
            runCurrent()

            assertEquals("Crew", posts.single().channel)
            assertEquals(1L, r.metrics.snapshot().meshPostIngested)
            r.transport.stop()
        }

    @Test
    fun anUnprovisionedBoardStillMirrorsItsPrimary() =
        runTest {
            // A board that was paired but never ran Knit's setup: the bound index is the settings default, 0,
            // and slot 0 is the stock primary. Knit's own frames stay off it (boundSlotIsKnit), but the room
            // must still read it and post to it — the rule is decided off the table, never off the index.
            val air = FakeMeshtasticAir()
            val posts = mutableListOf<MeshPost>()
            val r = rig(air, 1u, "alice", backgroundScope, channelName = "", onPublicPost = { posts += it }) { testScheduler.currentTime }
            r.transport.start()
            runCurrent()

            r.link.deliverPublicText(from = 0xdeadbeefu, body = "hi", id = 5u)
            runCurrent()
            assertNull(r.transport.postToPublicChannel("hello mesh"))
            runCurrent()

            assertEquals("hi", posts.single().body)
            assertEquals(
                "hello mesh",
                r.link.sent
                    .single { it.decodeToString() == "hello mesh" }
                    .decodeToString(),
            )
            assertEquals(1L, r.metrics.snapshot().publicPostSent)
            r.transport.stop()
        }

    @Test
    fun aReadyBoardReportsItsNodeNumberUpward() =
        runTest {
            // What the profile advertises so a contact can line a heard post up with this phone. A pre-2.8
            // board (the fake's default) signs nothing, so no key rides beside the number.
            val air = FakeMeshtasticAir()
            val bound = mutableListOf<BoardBinding>()
            val r = rig(air, 7u, "alice", backgroundScope, onBoardBound = { bound += it }) { testScheduler.currentTime }
            r.transport.start()
            runCurrent()

            assertEquals(listOf(BoardBinding(7u, signingKey = null)), bound)
            r.transport.stop()
        }

    @Test
    fun aSigningBoardReportsItsKeyUpwardAndAPreSigningOneDoesNot() =
        runTest {
            // The key rides beside the number only when the firmware signs: on 2.8 it is what lets a contact
            // verify our posts, on 2.5 it would verify nothing and only grow the profile.
            val air = FakeMeshtasticAir()
            val key = "oR62IJmFUE0Tgcw0GcypU5ZqUFCQllVBy2snB/BKQA4="
            val bound = mutableListOf<BoardBinding>()
            val signing =
                rig(
                    air,
                    7u,
                    "alice",
                    backgroundScope,
                    firmware = "2.8.0.47db0e3",
                    publicKey = key,
                    onBoardBound = { bound += it },
                ) { testScheduler.currentTime }
            signing.transport.start()
            runCurrent()
            assertEquals(listOf(BoardBinding(7u, signingKey = key)), bound)
            signing.transport.stop()

            bound.clear()
            val old =
                rig(
                    air,
                    8u,
                    "bob",
                    backgroundScope,
                    firmware = "2.5.0",
                    publicKey = key,
                    onBoardBound = { bound += it },
                ) { testScheduler.currentTime }
            old.transport.start()
            runCurrent()
            assertEquals(listOf(BoardBinding(8u, signingKey = null)), bound)
            old.transport.stop()
        }

    @Test
    fun aPassiveGatewayStillReadsItsOwnBoardsChannel() =
        runTest {
            // ADR 044's election decides who speaks for the pocket on Knit's channel. It has no say here: the
            // room is this phone's own window onto its own board, nothing heard leaves the phone, and a
            // board-holder standing down on the Knit hop must not go blind on the channel it paid for.
            val air = FakeMeshtasticAir()
            val posts = mutableListOf<MeshPost>()
            val r = bridgeRig(air, posts)
            // A co-pocket rival with a lower publisher key takes the ACTIVE role; we stand down.
            r.transport.suppressDataPath(setOf("bob"))
            val bobKey = StoreDigest.hash64("bob")
            r.link.deliver(
                from = 9u,
                channelIndex = 1,
                portnum = MeshtasticProto.PORT_PRIVATE_APP,
                payload = LoraCtl.encodeOffer(bobKey, IntArray(0), 200),
            )
            runCurrent()

            r.link.deliverPublicText(from = 0xdeadbeefu, body = "hi", id = 5u)
            runCurrent()

            assertEquals("the passive board still reads its own channel", 1, posts.size)
            assertEquals(1L, r.metrics.snapshot().meshPostHeard)
            assertEquals(1L, r.metrics.snapshot().meshPostIngested)
            r.transport.stop()
        }

    @Test
    fun aBoardWithKnitBoundToIndexZeroReadsNoPublicPrimary() =
        runTest {
            // The debug bridge can bind any index by hand. On such a board index 0 IS Knit's own traffic, so
            // the bridge must not treat it as somebody else's public channel — which is why the branch is
            // decided off the bound slot rather than off ADR 045's provisioning rule.
            val air = FakeMeshtasticAir()
            val posts = mutableListOf<MeshPost>()
            val a = rig(air, 1u, "alice", backgroundScope, onPublicPost = { posts += it }) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()

            b.transport.fastFanout(frame(FrameType.CHAT, "bob", body = "still a Knit room post"))
            runCurrent()

            assertTrue("nothing was mistaken for a public post", posts.isEmpty())
            assertEquals(0L, a.metrics.snapshot().meshPostHeard)
            assertTrue("and the Knit frame still arrived", a.received.any { it.envelope.senderId == "bob" })

            // A chat packet on that slot is heard, judged, and refused off the table: Knit sits at index 0.
            a.link.deliverPublicText(from = 0xdeadbeefu, body = "hi", id = 5u)
            runCurrent()
            assertTrue(posts.isEmpty())
            assertEquals(1L, a.metrics.snapshot().meshPostHeard)
            assertEquals(
                1L,
                a.metrics.snapshot().meshPostRefusedByReason[PublicChannelPolicy.Refusal.KNIT_ON_PRIMARY.name],
            )
            a.transport.stop()
            b.transport.stop()
        }

    // --- The LongFast bridge: posting on the foreign mesh's public primary. ---

    @Test
    fun aPostGoesOutOnThePublicPrimaryAsPlainMeshtasticChat() =
        runTest {
            // Index 0 and TEXT_MESSAGE_APP are the whole difference between this and a Knit frame, and
            // neither is visible in the payload — so they are asserted rather than inferred. The hop limit
            // is load-bearing for the same reason it is on the Knit path: omit it and nothing repeats us.
            val air = FakeMeshtasticAir()
            val r = bridgeRig(air, mutableListOf())

            assertNull(r.transport.postToPublicChannel("meet at the trailhead"))
            runCurrent()

            assertEquals(
                "meet at the trailhead",
                r.link.sent
                    .single()
                    .decodeToString(),
            )
            assertEquals(PublicChannelPolicy.PRIMARY_INDEX, r.link.sentChannels.single())
            assertEquals(MeshtasticProto.PORT_TEXT_MESSAGE, r.link.sentPortnums.single())
            assertEquals(LoraMeshTransport.HOP_LIMIT, r.link.sentHopLimits.single())
            assertEquals(1L, r.metrics.snapshot().publicPostSent)
            r.transport.stop()
        }

    @Test
    fun aPostOnASigningBoardIsTrimmedToTheSignableCap() =
        runTest {
            // The composer caps a draft at the same figure, so this only ever trims a draft typed while the
            // facts still said 200 — but the wire must never carry a byte past the cliff either way, or the
            // post leaves unsigned with nothing on the author's screen to say so.
            val air = FakeMeshtasticAir()
            val r = bridgeRig(air, mutableListOf(), firmware = "2.8.0.47db0e3")

            assertNull(r.transport.postToPublicChannel("x".repeat(PublicPostPolicy.MAX_ON_AIR_BYTES)))
            runCurrent()

            assertEquals(
                PublicPostPolicy.MAX_SIGNED_TEXT_BYTES,
                r.link.sent
                    .single()
                    .size,
            )
            r.transport.stop()
        }

    @Test
    fun aPreSigningBoardKeepsTheClientCap() =
        runTest {
            val air = FakeMeshtasticAir()
            val r = bridgeRig(air, mutableListOf(), firmware = "2.5.0")

            assertNull(r.transport.postToPublicChannel("x".repeat(PublicPostPolicy.MAX_ON_AIR_BYTES + 5)))
            runCurrent()

            assertEquals(
                PublicPostPolicy.MAX_ON_AIR_BYTES,
                r.link.sent
                    .single()
                    .size,
            )
            r.transport.stop()
        }

    @Test
    fun aKnitFrameStillGoesToTheBoundSlotWhileThePublicPathIsInUse() =
        runTest {
            // The two paths share one queue and one duty-cycle ledger but must never share a destination.
            val air = FakeMeshtasticAir()
            val r = bridgeRig(air, mutableListOf())

            r.transport.postToPublicChannel("hello mesh")
            runCurrent()
            r.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "a Knit room post"))
            advanceTimeBy(4_000)
            runCurrent()

            assertEquals(listOf(PublicChannelPolicy.PRIMARY_INDEX, 1), r.link.sentChannels)
            assertEquals(
                listOf(MeshtasticProto.PORT_TEXT_MESSAGE, MeshtasticProto.PORT_PRIVATE_APP),
                r.link.sentPortnums,
            )
            r.transport.stop()
        }

    @Test
    fun aPassiveGatewayStillPostsThroughItsOwnBoard() =
        runTest {
            // Each user posts through their own board. Standing down on Knit's hop (ADR 044) says nothing
            // about the user's own channel, and a post that silently went nowhere is the failure this room's
            // whole design exists to avoid.
            val air = FakeMeshtasticAir()
            val r = bridgeRig(air, mutableListOf())
            // A co-pocket rival with a lower publisher key takes the ACTIVE role; we stand down (ADR 044).
            r.transport.suppressDataPath(setOf("bob"))
            r.link.deliver(
                from = 9u,
                channelIndex = 1,
                portnum = MeshtasticProto.PORT_PRIVATE_APP,
                payload = LoraCtl.encodeOffer(StoreDigest.hash64("bob"), IntArray(0), 200),
            )
            runCurrent()

            assertNull(r.transport.postToPublicChannel("hello mesh"))
            runCurrent()

            assertEquals(
                "hello mesh",
                r.link.sent
                    .single()
                    .decodeToString(),
            )
            assertEquals(1L, r.metrics.snapshot().publicPostSent)
            r.transport.stop()
        }

    @Test
    fun aSecondPostInsideTheFloorIsRefusedRatherThanQueued() =
        runTest {
            // The floor is about how often this gateway decides to speak, so it is claimed at the decision
            // and not at the write — otherwise a burst queues up behind one inter-packet gap and goes out
            // anyway, a few seconds later.
            val air = FakeMeshtasticAir()
            val r = bridgeRig(air, mutableListOf())

            assertNull(r.transport.postToPublicChannel("first"))
            assertEquals(PublicPostRefusal.TOO_SOON, r.transport.postToPublicChannel("second"))
            advanceTimeBy(LoraMeshTransport.PUBLIC_POST_FLOOR_MS)
            runCurrent()

            assertEquals(listOf("first"), r.link.sent.map { it.decodeToString() })
            assertEquals(
                1L,
                r.metrics.snapshot().publicPostRefusedByReason[PublicPostRefusal.TOO_SOON.name],
            )
            r.transport.stop()
        }

    @Test
    fun aRenamedPrimaryIsPostedToAsConfigured() =
        runTest {
            // The write half of the same rule: slot 0 is whatever the user made it, and their words go there.
            val air = FakeMeshtasticAir()
            val r = bridgeRig(air, mutableListOf(), primaryName = "Book club")

            assertNull(r.transport.postToPublicChannel("hello mesh"))
            runCurrent()

            assertEquals(
                "hello mesh",
                r.link.sent
                    .single()
                    .decodeToString(),
            )
            assertEquals(PublicChannelPolicy.PRIMARY_INDEX, r.link.sentChannels.single())
            r.transport.stop()
        }

    @Test
    fun aBoardWithKnitBoundToIndexZeroHasNoPublicPrimaryToPostOn() =
        runTest {
            // The write-side twin of `aBoardWithKnitBoundToIndexZeroReadsNoPublicPrimary`: on such a board
            // index 0 is Knit's own traffic, so a "public" post there would land on the Knit channel.
            val air = FakeMeshtasticAir()
            val r = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            r.transport.start()
            runCurrent()

            assertEquals(PublicPostRefusal.KNIT_ON_PRIMARY, r.transport.postToPublicChannel("hello mesh"))
            runCurrent()

            // Not "nothing was sent" and not "nothing went to index 0" — this rig beacons its own profile
            // on session-up, and on this board index 0 *is* where Knit's own traffic goes. The claim is
            // that no Meshtastic chat packet went out, which is the shape a public post would have had.
            assertTrue(r.link.sentPortnums.none { it == MeshtasticProto.PORT_TEXT_MESSAGE })
            assertEquals(
                1L,
                r.metrics.snapshot().publicPostRefusedByReason[PublicPostRefusal.KNIT_ON_PRIMARY.name],
            )
            r.transport.stop()
        }

    @Test
    fun ourOwnBoardsTransmissionIsNeverReadBackInAsSomebodyElsesPost() =
        runTest {
            // The echo case. Narrow on purpose: only *our* board, never every radio that also speaks Knit —
            // a far pocket's board hearing this post off the air is exactly how it reaches those people.
            val air = FakeMeshtasticAir()
            val posts = mutableListOf<MeshPost>()
            val r = bridgeRig(air, posts)

            r.link.deliverPublicText(from = 1u, body = "hello mesh", id = 42u)
            runCurrent()

            assertTrue(posts.isEmpty())
            assertEquals(
                1L,
                r.metrics.snapshot().meshPostRefusedByReason[PublicChannelPolicy.Refusal.OWN_BOARD.name],
            )
            r.transport.stop()
        }

    @Test
    fun aBoardWhoseSlotIsNotTheKnitChannelStaysSilent() =
        runTest {
            val air = FakeMeshtasticAir()
            // The board was restored to Meshtastic defaults (or never set up) while the plane stayed on.
            // Sending here would put Knit's cleartext frames on whatever channel the board landed back on.
            val a = rig(air, 1u, "alice", backgroundScope, channelName = "LongFast") { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()

            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "north gate in ten"))
            runCurrent()

            assertTrue("nothing reached the air", b.received.none { it.envelope.senderId == "alice" })
            assertEquals(0, a.metrics.snapshot().loraSent)
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun aLongRoomChatFragmentsAndReassembles() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = incompressibleBody(400)))
            runCurrent()
            assertTrue("a 300-char post arrives reassembled", b.received.any { it.envelope.senderId == "alice" })
            assertEquals(1L, b.metrics.snapshot().loraReassembled)
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun aFrameReceivedOverLoraIsNotReFannedBackOverLora() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()
            val wire = frame(FrameType.CHAT, "alice", body = "echo test")
            a.transport.fastFanout(wire)
            runCurrent()
            val bSentBefore = b.link.sent.size
            // The composite re-calls fastFanout on relay of a received frame; bob must NOT bounce it back.
            b.transport.fastFanout(b.received.first { it.envelope.senderId == "alice" }.wire)
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("bob does not re-send a LoRa-received frame over LoRa", bSentBefore, b.link.sent.size)
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun aSealedDmCrossesOverLoraAndIsNotReFannedBack() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()

            // The long-range fan-out is the DM's only path onto this plane (ADR 039).
            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", body = "sealed bytes"))
            runCurrent()

            val delivered = b.received.firstOrNull { it.envelope.type == FrameType.CHAT && it.envelope.recipientId == "bob" }
            assertTrue("bob received alice's DM over LoRa", delivered != null)
            assertEquals("fromNodeId is the frame's senderId", "alice", delivered!!.fromNodeId)
            assertEquals(1L, a.metrics.snapshot().loraDmSent)
            assertEquals(1L, b.metrics.snapshot().loraDmReceived)

            // The pipeline re-fans a relayed DM over the long-range plane; a copy heard over LoRa must not bounce.
            val bSentBefore = b.link.sent.size
            b.transport.longRangeFanout(delivered.wire)
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("bob does not re-send a LoRa-received DM over LoRa", bSentBefore, b.link.sent.size)
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun aStaleChatIsNotFannedButAProfileAndAFreshChatAre() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            advanceTimeBy(20 * 60_000) // 20 min into the session
            val baseline = a.link.sent.size

            // A custody re-serve: a room post / DM stamped 20 min ago re-enters the pipeline and is re-fanned.
            a.transport.fastFanout(frame(FrameType.CHAT, "carol", body = "old room post", sentAt = 0L))
            // (Addressed to a third party: a DM to *us* is refused by the recipient gate before freshness is asked.)
            a.transport.longRangeFanout(frame(FrameType.CHAT, "carol", recipientId = "dave", body = "old dm", sentAt = 0L))
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("stale chat never rides a live plane", baseline, a.link.sent.size)
            assertEquals(2L, a.metrics.snapshot().loraSuppressed)

            // A peer's profile carries its publish stamp (hours old) and is the key bootstrap — never refused.
            a.transport.fastFanout(frame(FrameType.PROFILE, "carol", body = "x".repeat(20), sentAt = 0L))
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("an old profile still rides", baseline + 1, a.link.sent.size)

            val now = testScheduler.currentTime
            a.transport.longRangeFanout(frame(FrameType.CHAT, "carol", recipientId = "dave", body = "fresh dm", sentAt = now))
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("a fresh DM rides", baseline + 2, a.link.sent.size)
            a.transport.stop()
        }

    @Test
    fun aFirstHearingBeaconsAfterASixtySecondGapWhileSessionUpKeepsTheFloor() =
        runTest {
            val air = FakeMeshtasticAir()
            // This counts every packet alice sends, and the assertions are about beacons — so put her first
            // gossip OFFER (a floor interval's midpoint, 2.5 min in) beyond the whole timeline rather than
            // relying on the inbound-frame resets to keep shifting it out of the way.
            val quiet = LoraGossipPolicy(minIntervalMs = 30 * 60_000, random = { 0 })
            val a = rig(air, 1u, "alice", backgroundScope, gossip = quiet) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            assertEquals("session-up beacon", 1, a.link.sent.size)

            // Two minutes later bob comes up and beacons; alice has never heard him, so she beacons again —
            // her last beacon was inside the 5-min floor but past the 60-s first-hearing gap.
            advanceTimeBy(2 * 60_000)
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            b.transport.start()
            runCurrent()
            assertEquals("alice re-beacons for a newly heard peer", 2, a.link.sent.size)
            assertEquals("bob's own session-up beacon; alice's reply is inside his gap", 1, b.link.sent.size)

            // Ten seconds later carol comes up: inside everyone's 60-s gap, so nobody re-beacons.
            advanceTimeBy(10_000)
            val c = rig(air, 3u, "carol", backgroundScope) { testScheduler.currentTime }
            c.transport.start()
            runCurrent()
            assertEquals(2, a.link.sent.size)
            assertEquals(1, b.link.sent.size)

            // A reconnect one minute later is a session-up trigger and keeps the 5-min floor.
            advanceTimeBy(60_000)
            a.link.drop()
            runCurrent()
            a.link.start("AA:1")
            runCurrent()
            assertEquals("no session-up beacon inside the 5-min floor", 2, a.link.sent.size)
            a.transport.stop()
            b.transport.stop()
            c.transport.stop()
        }

    private fun idOf(wire: WireEnvelope): String = WireCodec.decodeEnvelope(wire.signed)!!.id

    @Test
    fun firstHearingALoraOnlyPeerReoffersItsCarriedDms() =
        runTest {
            val air = FakeMeshtasticAir()
            val fannedLive = frame(FrameType.CHAT, "alice", recipientId = "bob", body = "sent while bob was off")
            val carried = frame(FrameType.CHAT, "carol", recipientId = "bob", body = "relayed for carol")
            val notForBob = frame(FrameType.CHAT, "alice", recipientId = "dave", body = "custody's mistake")
            val asked = mutableListOf<String>()
            val a =
                rig(air, 1u, "alice", backgroundScope, farFrames = { peer ->
                    asked += peer
                    listOf(fannedLive, carried, notForBob)
                }) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            // alice fanned the first DM live a moment ago (bob's board was off) — still inside the dedup window.
            a.transport.longRangeFanout(fannedLive)
            advanceTimeBy(2 * 60_000)
            runCurrent()
            val aSentBefore = a.link.sent.size

            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            b.transport.start() // bob beacons; alice hears him for the first time
            advanceTimeBy(4_000)
            runCurrent()

            assertEquals("custody is asked once, for bob", listOf("bob"), asked)
            // The addressee is double-checked, and the frame fanned inside the dedup window is skipped.
            assertEquals(1L, a.metrics.snapshot().loraReoffered)
            assertEquals("alice's first-hearing beacon + one re-offered frame", aSentBefore + 2, a.link.sent.size)
            assertTrue("bob received the re-offered DM", b.received.any { it.envelope.id == idOf(carried) })
            assertFalse(b.received.any { it.envelope.id == idOf(notForBob) })

            // Hearing bob again inside the linger is not a first hearing.
            b.transport.fastFanout(frame(FrameType.CHAT, "bob", body = "hi", sentAt = testScheduler.currentTime))
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals(listOf("bob"), asked)

            // Once bob ages out of reachable and reappears, custody is asked again (and the dedup window has lapsed).
            advanceTimeBy(46 * 60_000)
            b.transport.fastFanout(frame(FrameType.CHAT, "bob", body = "back", sentAt = testScheduler.currentTime))
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals(listOf("bob", "bob"), asked)
            assertEquals(3L, a.metrics.snapshot().loraReoffered)
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun withDmsOffTheRoomStillRidesButDmsAndReoffersDoNot() =
        runTest {
            val air = FakeMeshtasticAir()
            val asked = mutableListOf<String>()
            val a =
                rig(
                    air,
                    1u,
                    "alice",
                    backgroundScope,
                    config = MutableStateFlow(LoraConfig("AA:1", 0, dms = false)),
                    farFrames = { peer ->
                        asked += peer
                        listOf(frame(FrameType.CHAT, "alice", recipientId = "bob"))
                    },
                ) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            val baseline = a.link.sent.size
            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", body = "private"))
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("a DM stays off the plane", baseline, a.link.sent.size)
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "room post"))
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("the room keeps riding", baseline + 1, a.link.sent.size)

            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            b.transport.start()
            advanceTimeBy(4_000)
            runCurrent()
            assertTrue("no re-offer either", asked.isEmpty())
            assertEquals(0L, a.metrics.snapshot().loraDmSent)
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun aReofferIsSkippedForAPeerAnotherPlaneAlreadyCarries() =
        runTest {
            val air = FakeMeshtasticAir()
            val asked = mutableListOf<String>()
            val a =
                rig(air, 1u, "alice", backgroundScope, farFrames = { peer ->
                    asked += peer
                    listOf(frame(FrameType.CHAT, "alice", recipientId = "bob"))
                }) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            // Bob is on a live BLE/NAN link — custody's digest exchange syncs to him there for real.
            a.transport.suppressDataPath(setOf("bob"))
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            b.transport.start()
            advanceTimeBy(4_000)
            runCurrent()
            assertTrue("custody is not even asked", asked.isEmpty())
            assertEquals(0L, a.metrics.snapshot().loraReoffered)
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun aVerbatimResendIsSuppressedInsideTheWindowThenAllowedAfter() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            val baseline = a.link.sent.size
            val wire = frame(FrameType.CHAT, "alice", body = "same frame")
            a.transport.fastFanout(wire)
            runCurrent()
            val afterFirst = a.link.sent.size
            assertTrue("first send goes out", afterFirst > baseline)

            a.transport.fastFanout(wire) // verbatim retry (AckSync re-sends these for 24 h)
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("suppressed inside the 10-min dedup window", afterFirst, a.link.sent.size)

            advanceTimeBy(10 * 60_000)
            a.transport.fastFanout(wire)
            advanceTimeBy(4_000)
            runCurrent()
            assertTrue("allowed again after the window", a.link.sent.size > afterFirst)
            a.transport.stop()
        }

    @Test
    fun fastSendOnlyReachesLoraReachablePeersNotServedByAnotherPlane() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()
            // bob hears alice, so alice becomes reachable to bob.
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "ping"))
            runCurrent()
            val bSentBefore = b.link.sent.size

            // A tick toward a peer bob has never heard is dropped.
            b.transport.fastSend(frame(FrameType.RECEIPT, "bob"), Peer("stranger"))
            runCurrent()
            assertEquals("no send to an unreachable peer", bSentBefore, b.link.sent.size)

            // A tick toward alice, whom another plane holds a LIVE LINK to, is skipped — she gets it there.
            b.transport.suppressDataPath(setOf("alice"))
            b.transport.fastSend(frame(FrameType.RECEIPT, "bob"), Peer("alice"))
            runCurrent()
            assertEquals("no send to a peer another plane covers", bSentBefore, b.link.sent.size)

            // But a peer merely *sighted* on BLE/NAN is covered by nothing, so the tick must still ride.
            // Read as coverage, a sighting refused a far peer's only path to its ✓✓ (field, 2026-08-25).
            b.transport.suppressDataPath(emptySet())
            b.transport.onForeignReachable(setOf("alice"))
            b.transport.fastSend(frame(FrameType.RECEIPT, "bob"), Peer("alice"))
            advanceTimeBy(4_000)
            runCurrent()
            assertTrue("a tick to a LoRa-reachable, uncovered peer rides", b.link.sent.size > bSentBefore)
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun ineligibleFramesAreNeverSent() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            val baseline = a.link.sent.size
            a.transport.fastFanout(
                frame(FrameType.CHAT, "alice", group = GroupInfo("g-x", members = listOf("alice", "bob"), createdBy = "alice")),
            )
            a.transport.fastFanout(frame(FrameType.TYPING, "alice"))
            a.transport.fastFanout(frame(FrameType.GROUP_UPDATE, "alice"))
            advanceTimeBy(20_000)
            runCurrent()
            assertEquals("none of the ineligible frames ride LoRa", baseline, a.link.sent.size)
            a.transport.stop()
        }

    @Test
    fun aNullConfigStopsTheLinkAndReportsUnavailable() =
        runTest {
            val air = FakeMeshtasticAir()
            val cfg = MutableStateFlow<LoraConfig?>(LoraConfig("AA", 0))
            val a = rig(air, 1u, "alice", backgroundScope, config = cfg) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            assertEquals(TransportHealth.Healthy, a.transport.health.value)

            cfg.value = null // the user turned the plane off / unpaired the board
            runCurrent()
            assertEquals(TransportHealth.Unavailable, a.transport.health.value)
            val baseline = a.link.sent.size
            a.transport.fastFanout(frame(FrameType.CHAT, "alice"))
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("nothing sends while the plane is off", baseline, a.link.sent.size)
            a.transport.stop()
        }

    @Test
    fun aDisconnectDegradesAndReadyRestoresHealthy() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            assertEquals(TransportHealth.Healthy, a.transport.health.value)
            a.link.drop()
            runCurrent()
            assertEquals(TransportHealth.Degraded, a.transport.health.value)
            a.link.start("AA") // reconnects
            runCurrent()
            assertEquals(TransportHealth.Healthy, a.transport.health.value)
            a.transport.stop()
        }

    @Test
    fun aNakIsCountedAndPacesWithoutBlockingForever() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            a.link.emitNak(id = 5u, reason = RoutingError.DUTY_CYCLE_LIMIT)
            runCurrent()
            assertEquals(1L, a.metrics.snapshot().loraNak)
            assertEquals("attributable, not just counted", mapOf("DUTY_CYCLE_LIMIT" to 1L), a.metrics.snapshot().loraNakByReason)
            a.transport.stop()
        }

    /**
     * The bug this guards: presence here is keyed on the frame **author**, and a gateway routinely puts other
     * people's frames on air — the ADR 044 backfill, this ADR 039 re-offer, and `onDeliver`'s re-fan of
     * anything first-seen, *including what the Internet plane just pulled off a spool*. A phone switched off
     * for days therefore showed up as a live neighbour on every listener for the whole 45-minute linger.
     */
    @Test
    fun aReofferedDmCrossesButDoesNotPutItsAuthorOnTheAir() =
        runTest {
            val air = FakeMeshtasticAir()
            val carried = frame(FrameType.CHAT, "carol", recipientId = "bob", body = "sent long before bob came up")
            val a = rig(air, 1u, "alice", backgroundScope, farFrames = { listOf(carried) }) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            // Carol's DM is now well past the fan-out's freshness window; only the re-offer would still carry it.
            advanceTimeBy(30 * 60_000)
            runCurrent()

            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            b.transport.start() // bob beacons; alice hears him for the first time and re-offers carol's DM
            advanceTimeBy(4_000)
            runCurrent()

            assertTrue("bob still receives the re-offered DM", b.received.any { it.envelope.id == idOf(carried) })
            assertFalse(
                "but carol is not on the air — alice carried that frame for her",
                b.transport.reachable.value
                    .any { it.nodeId == "carol" },
            )
            assertTrue(
                "alice, whose own beacon bob heard, is",
                b.transport.reachable.value
                    .any { it.nodeId == "alice" },
            )
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun aProfileCountsUntilItsAuthorStopsRepublishingIt() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()

            // A profile's sentAt is a publish stamp refreshed every 12 h, so an idle node's beacon is old by
            // design and must keep counting — it is what triggers the ADR 039 re-offer on first hearing.
            advanceTimeBy(12 * 60 * 60_000L)
            runCurrent()
            a.transport.fastFanout(profile("carol"))
            advanceTimeBy(4_000)
            runCurrent()
            assertTrue(
                "a 12-hour-old beacon still means carol is there",
                b.transport.reachable.value
                    .any { it.nodeId == "carol" },
            )

            // Dave stopped republishing a day ago, but his profile is still in somebody's custody and still
            // gets re-fanned. The frame must cross; dave must not come back to life.
            advanceTimeBy(14 * 60 * 60_000L)
            runCurrent()
            a.transport.fastFanout(profile("dave"))
            advanceTimeBy(4_000)
            runCurrent()
            assertTrue("the profile still crosses — the key bootstrap is untouched", b.received.any { it.envelope.senderId == "dave" })
            assertFalse(
                "but a node that stopped republishing is not a neighbour",
                b.transport.reachable.value
                    .any { it.nodeId == "dave" },
            )
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun theReachableLingerExpiresAPeer() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "hello"))
            runCurrent()
            assertTrue(
                b.transport.reachable.value
                    .any { it.nodeId == "alice" },
            )

            advanceTimeBy(46 * 60_000) // past the 45-min linger
            runCurrent()
            assertFalse(
                "alice ages out of reachable after the linger",
                b.transport.reachable.value
                    .any { it.nodeId == "alice" },
            )
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun provisionKnitChannelDelegatesToTheLinkWithTheDerivedKnitChannel() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.link.provisionResult = ProvisionResult.Provisioned(index = 3, alreadyPresent = false)

            val result = a.transport.provisionKnitChannel()

            assertEquals(ProvisionResult.Provisioned(3, false), result)
            assertEquals(1, a.link.provisioned.size)
            assertEquals(
                KnitChannel.NAME,
                a.link.provisioned
                    .single()
                    .name,
            )
            assertArrayEquals(
                KnitChannel.PSK,
                a.link.provisioned
                    .single()
                    .psk,
            )
            a.transport.stop()
        }

    @Test
    fun theStatusSnapshotCarriesTheBoardsBattery() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            assertNull(a.transport.status.value.battery)

            val battery = BoardBattery(percent = 42, voltage = 3.7f, powered = false)
            a.link.battery.value = battery
            runCurrent()
            assertEquals(battery, a.transport.status.value.battery)
        }

    /**
     * A board that fills its queue part-way through a fragmented frame refuses the rest, and the frame is
     * requeued whole. It must resume, not restart: the fragments the board already took are on the air and
     * their airtime is booked, so re-sending them books the cost a second time. Because the ledger only ever
     * grows on a retry, that inflated the hourly budget past 100 % — after which it refused every other frame
     * on the plane, which is the state the pacer then spun in.
     *
     * The invariant is exact: **what the ledger has booked equals what the board was actually handed.**
     */
    @Test
    fun aFrameTheBoardRefusesPartWayResumesInsteadOfRebookingItsAirtime() =
        runTest {
            val pace = LoraPacePolicy(minGapMs = 0)
            val a = rig(FakeMeshtasticAir(), 1u, "alice", backgroundScope, pace = pace) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()

            // Room for one fragment, then the board is full: the rest of the frame comes back Busy.
            a.link.queueFills = true
            a.link.free = 1
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = incompressibleBody(600)))
            runCurrent()
            val partial = a.link.sent.size
            assertTrue("the frame really did fragment and stall part-way", partial in 1 until LoraMeshTransport.FRAG_CAP)
            assertEquals("the rest of it is queued", 1, pace.pending)

            // The board drains; the frame must pick up where it stopped.
            a.link.queueFills = false
            a.link.free = 16
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals("the frame finished", 0, pace.pending)
            assertTrue("it made progress past the stall", a.link.sent.size > partial)

            // The defect is a *replayed* fragment: the board is handed one it already has, which costs real
            // air and books it a second time. The ledger stays consistent with the board either way, so the
            // duplicate is what has to be asserted on.
            val distinct =
                a.link.sent
                    .mapTo(HashSet()) { it.toList() }
                    .size
            assertEquals("no fragment was handed to the board twice", a.link.sent.size, distinct)
            // Across every bucket: the session's self-profile beacon books BOOTSTRAP (ADR 056), the frame
            // under test books LIVE, and the invariant is about the ledger as a whole.
            assertEquals(
                "and the ledger booked exactly what went out",
                a.link.sent.sumOf { pace.airtime.timeOnAirMs(it.size) },
                AirBucket.entries.sumOf { pace.airtime.usedMs(it, testScheduler.currentTime) },
            )
            a.transport.stop()
        }

    /**
     * ADR 057. A relayed `profile` was gated only by [LoraMeshTransport.SIG_TTL_MS] — the same 10 minutes as
     * `MeshRouter`'s SeenSet — so a profile that kept arriving looked first-seen again on every lapse and
     * re-fanned indefinitely. `LoraFramePolicy.isFresh` exempts a profile from the staleness check (its
     * `sentAt` is a publish stamp, hours old by design), so nothing else stopped it either. Now it rides once
     * per **publish**, and a republish — which mints a new frame id — rides on its own merits.
     */
    @Test
    fun aProfileBacklogCannotStarveTheGatewayOffer() =
        runTest {
            // The lab failure, end to end (2026-09-04). Profiles outrank the OFFER in the dequeue
            // (BOOTSTRAP < GOSSIP), so a growing backlog of them took every window's freed bootstrap air and
            // the offer was never *chosen*: `loraOfferSent` stuck at 0 for the whole session, and the two
            // boards in the pocket therefore both stayed ACTIVE and transmitted every public post twice.
            // Bounded to one queued copy per author, the backlog drains and the offer gets its turn.
            val a = rig(FakeMeshtasticAir(), 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()

            // A run of republishes from one author, faster than the plane can drain them.
            repeat(12) {
                a.transport.fastFanout(frame(FrameType.PROFILE, "carol", body = "x".repeat(20)))
                runCurrent()
            }
            // Well past a Trickle interval, so the gossip loop has had several chances to be heard.
            advanceTimeBy(3 * LoraGossipPolicy.MAX_INTERVAL_MS)
            runCurrent()

            assertTrue("the offer reached the air", a.metrics.snapshot().loraOfferSent > 0)
            assertTrue(
                "and the backlog did not grow past one copy of one author's profile",
                a.link.sent.count { it.size > 0 } < 12,
            )
            a.transport.stop()
        }

    @Test
    fun aProfileIsFannedOncePerPublishNotOnEverySeenSetLapse() =
        runTest {
            val a = rig(FakeMeshtasticAir(), 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()

            val published = frame(FrameType.PROFILE, "carol", body = "x".repeat(20))
            var before = a.link.sent.size
            a.transport.fastFanout(published)
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("a profile rides the first time it is seen", before + 1, a.link.sent.size)

            // Past the flood-suppression window: this is where the old behaviour started over, and over.
            advanceTimeBy(LoraMeshTransport.SIG_TTL_MS + 60_000)
            runCurrent()
            before = a.link.sent.size
            a.transport.fastFanout(published)
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("the same publish is not put back on the air", before, a.link.sent.size)
            assertEquals(1L, a.metrics.snapshot().loraProfileRefanSkipped)
            assertEquals("and it is not counted as an ordinary dedup", 0L, a.metrics.snapshot().loraSuppressed)

            // A republish stamps a new frame id, so it is a different fact and rides.
            before = a.link.sent.size
            a.transport.fastFanout(frame(FrameType.PROFILE, "carol", body = "x".repeat(20)))
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("a republished profile still rides", before + 1, a.link.sent.size)
            a.transport.stop()
        }

    /**
     * ADR 056. A relayed `profile` is the key bootstrap, so it is judged outside the window's total — but it
     * has its own share, and once that is gone it waits like anything else. Before the cap, `admits` returned
     * true for every BOOTSTRAP frame *and* recorded it, so a profile re-fanned on each SeenSet lapse could
     * spend the whole allowance and leave the plane refusing traffic a human had typed: on the lab gateway
     * 79 % of every frame it had ever sent was a profile.
     */
    @Test
    fun aRelayedProfileIsMeteredAndStopsAtItsShareInsteadOfBlankingThePlane() =
        runTest {
            val pace = LoraPacePolicy(minGapMs = 3_000)
            val a = rig(FakeMeshtasticAir(), 1u, "alice", backgroundScope, pace = pace) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            val baseline = a.link.sent.size

            // Fan a distinct peer profile until the plane stops taking them.
            var carried = 0
            repeat(12) { i ->
                a.transport.fastFanout(frame(FrameType.PROFILE, "carol", body = "profile-$i".padEnd(20, 'x')))
                advanceTimeBy(4_000)
                runCurrent()
            }
            carried = a.link.sent.size - baseline
            assertTrue("some bootstrap always rides", carried > 0)
            val spent = pace.airtime.usedMs(AirBucket.BOOTSTRAP, testScheduler.currentTime)
            assertTrue(
                "the bootstrap is booked to its own bucket now, not silently to LIVE",
                spent > 0 && pace.airtime.usedMs(AirBucket.LIVE, testScheduler.currentTime) == 0L,
            )
            assertTrue("and it stops at its share", spent <= pace.airtime.budgetMs(AirBucket.BOOTSTRAP))
            assertTrue(
                "twelve profiles must not all ride — that is the unbounded behaviour ADR 056 removed",
                carried < 12,
            )

            // The window is not blank: three quarters of it is still there for a message.
            val sentSoFar = a.link.sent.size
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "north gate in ten"))
            advanceTimeBy(4_000)
            runCurrent()
            assertTrue("content still goes out with the bootstrap share spent", a.link.sent.size > sentSoFar)
            a.transport.stop()
        }

    private class SpinCap : RuntimeException("the pacer loop went round far more times than a suspending loop can")

    /**
     * The pacer must **suspend** when it holds frames it cannot send. With the hour's airtime spent, a live
     * frame queued and the inter-packet gap long since elapsed, `take` returns null on every pass — and before
     * the fix `waitForNextSend` derived a zero wait from a due time already in the past, so the loop never
     * suspended: it pegged a core for the rest of the hour and, having no suspension point in it, could not
     * even be cancelled by [LoraMeshTransport.stop].
     *
     * A spinning loop hangs the scheduler outright (virtual time cannot advance past a task that never
     * yields), so the clock throws once it has been read more times than any suspending loop could manage.
     * The scope swallows that, leaving the assertions below to report it.
     */
    @Test
    fun thePacerSuspendsInsteadOfSpinningWhenTheBudgetIsSpent() =
        runTest {
            val airtime = LoraAirtime()
            val pace = LoraPacePolicy(minGapMs = 3_000, airtime = airtime)
            var at = 0L
            var spent = 0L
            while (spent < airtime.allowanceMs()) {
                airtime.record(AirBucket.LIVE, 200, at)
                spent += airtime.timeOnAirMs(200)
                at += 3_000
            }

            var reads = 0
            val clock = {
                reads++
                if (reads > SPIN_CAP) throw SpinCap()
                at + testScheduler.currentTime
            }
            val scope =
                CoroutineScope(
                    StandardTestDispatcher(testScheduler) + SupervisorJob() + CoroutineExceptionHandler { _, _ -> },
                )
            val a = rig(FakeMeshtasticAir(), 1u, "alice", scope, pace = pace, now = clock)
            a.transport.start()
            runCurrent()

            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = incompressibleBody(200)))
            // Past the inter-packet gap, so nothing but the spent budget is holding the frame back — this is
            // the window in which the old code's due time fell into the past.
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("the frame is queued, not sent — the budget is spent", 1, pace.pending)

            val atRest = reads
            advanceTimeBy(60_000)
            runCurrent()
            assertTrue(
                "the pacer stayed parked over a quiet minute (${reads - atRest} clock reads; a spin blows past $SPIN_CAP)",
                reads - atRest < IDLE_WAKES_PER_MINUTE,
            )
            assertTrue("the clock cap was never hit", reads <= SPIN_CAP)
            a.transport.stop()
            scope.cancel()
        }

    /**
     * The recipient gate (ADR 054). A DM-form frame whose recipient a higher-preference plane holds a live
     * link to — or who is us — already has a data path, so it must not spend LoRa air: before this, texting a
     * pocket-mate over Bluetooth spent the whole airtime budget on DMs and ✓✓s nobody needed over the board,
     * and a far peer then went without. A *sighting* is not coverage (the field lesson of ADR 044's amendment).
     */
    @Test
    fun aDmFormFrameToALinkedPeerOrToSelfNeverRidesTheFanOut() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            advanceTimeBy(1)
            runCurrent()
            val afterBeacon = a.link.sent.size

            // bob is on a live BLE/NAN link: our DM to him and a relayed ✓✓ toward him both stay off the air,
            // as does a DM addressed to us that the composite re-fans on relay (we are its only reader).
            a.transport.suppressDataPath(setOf("bob"))
            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", body = "sealed"))
            a.transport.longRangeFanout(frame(FrameType.CHAT, "carol", recipientId = "bob", body = "sealed tick"))
            a.transport.longRangeFanout(frame(FrameType.CHAT, "carol", recipientId = "alice", body = "sealed"))
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("nothing on the air for a linked or self recipient", afterBeacon, a.link.sent.size)
            assertEquals(3L, a.metrics.snapshot().loraSkippedLinked)
            assertEquals("the sig dedup slot was not burned", 0L, a.metrics.snapshot().loraSuppressed)

            // A sighting is not a link: the same DM to a merely-sighted bob rides.
            a.transport.suppressDataPath(emptySet())
            a.transport.onForeignReachable(setOf("bob"))
            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", body = "sealed"))
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue("a DM to a sighted-but-unlinked peer rides", a.link.sent.size > afterBeacon)
            assertEquals(1L, a.metrics.snapshot().loraDmSent)

            // The room is addressed to nobody and the gate never touches it.
            val beforeRoom = a.link.sent.size
            a.transport.suppressDataPath(setOf("bob"))
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "room post"))
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue("a room post still rides", a.link.sent.size > beforeRoom)
            a.transport.stop()
        }

    /**
     * The originator's [FanoutHint.TICK] lands as [FrameClass.TICK] (ADR 054): with the queue full, a DM evicts a
     * hinted tick, and a tick arriving behind a DM yields — while the same bytes without the hint are DM class
     * and stand their ground. A relayed frame never carries the hint, so the plane never guesses.
     */
    @Test
    fun aHintedTickIsTheFirstThingAFullQueueGivesUp() =
        runTest {
            val air = FakeMeshtasticAir()
            val pace = LoraPacePolicy(minGapMs = 0, queueCap = 1)
            val a = rig(air, 1u, "alice", backgroundScope, pace = pace) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            advanceTimeBy(5_000) // the profile beacon drains
            runCurrent()
            // Hold the board's queue full so nothing leaves the pacer while the two frames meet.
            pace.onQueueStatus(0)

            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", body = "tick"), FanoutHint.TICK)
            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", body = "dm"))
            assertEquals("the DM evicted the tick", 1L, a.metrics.snapshot().loraDroppedQueue)
            assertEquals(1, pace.pending)

            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", body = "tick 2"), FanoutHint.TICK)
            assertEquals("a tick behind a DM yields", 2L, a.metrics.snapshot().loraDroppedQueue)

            // The same bytes without the hint are content: within one class the oldest goes, so the newcomer stays.
            a.transport.longRangeFanout(frame(FrameType.CHAT, "carol", recipientId = "bob", body = "relayed dm-form"))
            assertEquals(3L, a.metrics.snapshot().loraDroppedQueue)
            assertEquals(1, pace.pending)
            a.transport.stop()
        }

    private companion object {
        /** Far more clock reads than a loop that suspends between passes can make; a spin blows past it at once. */
        const val SPIN_CAP = 5_000

        /** A minute of idling costs a wake per [LoraMeshTransport.IDLE_TICK_MS] at worst, times a read or two. */
        const val IDLE_WAKES_PER_MINUTE = 200
    }
}
