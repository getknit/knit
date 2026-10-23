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
import app.getknit.knit.mesh.protocol.ReceiptContent
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CompletableDeferred
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
            val transcodedBefore = a.metrics.lora().loraTranscoded // the profile beacon already rode 0x05
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "north gate, ten minutes"))
            runCurrent()
            assertEquals("one 0x05 packet on the air", FastFrameCodec.TAG_TRANSCODED, a.link.sent.last()[0])
            assertEquals(transcodedBefore + 1, a.metrics.lora().loraTranscoded)
            assertEquals(0L, a.metrics.fast().transcodeFallbacks)
            assertTrue("bob decodes it through the same inbound path", b.received.any { it.envelope.senderId == "alice" })
            assertTrue(
                b.metrics
                    .fast()
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
            val paddedBefore = a.metrics.lora().loraPadded
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "north gate, ten minutes. bring the long cable."))
            runCurrent()
            val sent = a.link.sent.last()
            assertEquals("one packet, one byte past the cliff", MeshtasticProto.MAX_SIGNED_PAYLOAD + 1, sent.size)
            assertEquals(paddedBefore + 1, a.metrics.lora().loraPadded)
            assertTrue("and bob still decodes it through the same inbound path", b.received.any { it.envelope.senderId == "alice" })
            assertTrue(
                b.metrics
                    .fast()
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
            assertEquals(0L, a.metrics.lora().loraPadded)
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
            val transcodedBefore = a.metrics.lora().loraTranscoded
            a.transport.fastFanout(rawChat(byteArrayOf(0xA1.toByte()) + tstr("enc") + enc))
            runCurrent()
            assertEquals("0x03 carries it", FastFrameCodec.TAG_COMPACT, a.link.sent.last()[0])
            assertEquals(1L, a.metrics.fast().transcodeFallbacks)
            assertEquals("…and it is not counted as transcoded", transcodedBefore, a.metrics.lora().loraTranscoded)
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

    // --- Limiters that outlive the process (LoraPlaneState). ---

    /** An in-memory [LoraPlaneState] — the DataStore blob's stand-in, so a test can restart a plane. */
    private class FakePlaneState : LoraPlaneState {
        var snapshot: LoraPlaneSnapshot? = null
        var writes = 0

        override suspend fun load(): LoraPlaneSnapshot? = snapshot

        override suspend fun save(snapshot: LoraPlaneSnapshot) {
            this.snapshot = snapshot
            writes++
        }
    }

    @Test
    fun aRestartDoesNotBeaconAgainInsideTheProfileFloor() =
        runTest {
            // The lab's reinstall cycle: the process dies and comes straight back, and the beacon floor — an
            // in-memory timestamp — used to be void every time, so session-up put a profile on the air on
            // every launch however recently the last one had gone out.
            val air = FakeMeshtasticAir()
            val state = FakePlaneState()
            val first = rig(air, 1u, "alice", backgroundScope, state = state) { testScheduler.currentTime }
            first.transport.start()
            runCurrent()
            advanceTimeBy(1)
            runCurrent()
            assertEquals("session-up beacons the profile", 1, first.link.sent.size)
            first.transport.stop()
            runCurrent()
            assertTrue("stopping persisted the limiters", state.snapshot != null)

            advanceTimeBy(60_000) // a minute later — well inside PROFILE_FLOOR_MS
            val second = rig(air, 1u, "alice", backgroundScope, state = state) { testScheduler.currentTime }
            second.transport.start()
            runCurrent()
            advanceTimeBy(1)
            runCurrent()

            assertEquals("the floor survived the restart", 0, second.link.sent.size)
            second.transport.stop()
        }

    @Test
    fun aRestartInheritsTheSpentWindowRatherThanAFreshAllowance() =
        runTest {
            val air = FakeMeshtasticAir()
            val state = FakePlaneState()
            val first = rig(air, 1u, "alice", backgroundScope, state = state) { testScheduler.currentTime }
            first.transport.start()
            runCurrent()
            first.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "north gate in ten"))
            advanceTimeBy(10_000)
            runCurrent()
            first.transport.stop()
            runCurrent()

            val pace = LoraPacePolicy(minGapMs = 0)
            assertEquals("a fresh ledger starts at zero", 0L, pace.airtime.snapshot(testScheduler.currentTime).totalUsedMs)
            val second = rig(air, 1u, "alice", backgroundScope, pace = pace, state = state) { testScheduler.currentTime }
            second.transport.start()
            runCurrent()

            assertTrue(
                "the window still owes what the last process spent",
                pace.airtime.snapshot(testScheduler.currentTime).totalUsedMs > 0,
            )
            second.transport.stop()
        }

    @Test
    fun aSnapshotFromAheadOfTheWallClockIsRefusedRatherThanTrusted() =
        runTest {
            // A snapshot stamped in the future is a clock that moved backwards (a manual change, or an NTP
            // correction over a reboot). Every age in it would read as negative — i.e. never expiring — so the
            // limiters start clean instead, which costs one window.
            val air = FakeMeshtasticAir()
            val state = FakePlaneState()
            state.snapshot =
                LoraPlaneSnapshot(
                    savedAtWall = 60 * 60_000,
                    selfProfileAtWall = 60 * 60_000,
                )
            val rig = rig(air, 1u, "alice", backgroundScope, state = state) { testScheduler.currentTime }
            rig.transport.start()
            runCurrent()
            advanceTimeBy(1)
            runCurrent()

            assertEquals("a beacon that a bad snapshot would have suppressed", 1, rig.link.sent.size)
            rig.transport.stop()
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
        framesMissing: suspend (IntArray, Int, Boolean) -> List<WireEnvelope> = { _, _, _ -> emptyList() },
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
        state: LoraPlaneState = LoraPlaneState.None,
        selfId: suspend () -> String = { selfNode },
        now: () -> Long,
    ): Rig {
        val link = FakeMeshtasticLink(nodeNum, air, channelName, firmware, publicKey)
        val metrics = MeshMetrics()
        val transport =
            LoraMeshTransport(
                selfId = selfId,
                link = link,
                config = config,
                selfProfile = profileSource(selfNode),
                farFrames = farFrames,
                framesMissing = framesMissing,
                onPublicPost = onPublicPost,
                onBoardBound = onBoardBound,
                scope = scope,
                metrics = metrics,
                state = state,
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
            assertEquals(1L, a.metrics.lora().loraSessionUps)
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
            assertTrue("bob received at least the chat", b.metrics.lora().loraReceived >= 1)
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
        room: Boolean = true,
        // The board pinned to Knit's own dedicated RF slot (ADR 067).
        dedicated: Boolean = false,
        // A slot pinned by hand instead, which may be a community's; overrides [dedicated].
        pinnedSlot: Int? = null,
        state: LoraPlaneState = LoraPlaneState.None,
    ): Rig {
        val r =
            rig(
                air,
                1u,
                "alice",
                backgroundScope,
                config = MutableStateFlow(LoraConfig("AA:1", 1, room = room)),
                firmware = firmware,
                pace = LoraPacePolicy(minGapMs = 0, airtime = LoraAirtime(dedicatedUnlocksDuty = true)),
                onPublicPost = { posts += it },
                state = state,
            ) { testScheduler.currentTime }
        r.transport.start()
        // After runCurrent, not before: start() only *queues* the transport's jobs, and the link's own
        // start() then publishes the default single-Knit-channel Ready — which would overwrite this.
        runCurrent()
        val radio =
            LoraRadioConfig(
                usePreset = true,
                modemPreset = ModemPreset.LONG_FAST,
                region = LoraRegion.US,
                hopLimit = 3,
                overrideDutyCycle = false,
                channelNum = pinnedSlot ?: if (dedicated) LoraSlot.forRegion(LoraRegion.US, ModemPreset.LONG_FAST)!! else 0,
            )
        r.link.readyProvisioned(knitIndex = 1, primaryName = primaryName, primaryPsk = primaryPsk, radio = radio)
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
            assertEquals(1L, r.metrics.meshtastic().meshPostIngested)
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
            assertEquals(0L, r.metrics.lora().loraSent)
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
            assertEquals(1L, r.metrics.meshtastic().meshPostIngested)
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
            assertEquals(1L, r.metrics.meshtastic().publicPostSent)
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
            assertEquals(1L, r.metrics.meshtastic().meshPostHeard)
            assertEquals(1L, r.metrics.meshtastic().meshPostIngested)
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
            assertEquals(0L, a.metrics.meshtastic().meshPostHeard)
            assertTrue("and the Knit frame still arrived", a.received.any { it.envelope.senderId == "bob" })

            // A chat packet on that slot is heard, judged, and refused off the table: Knit sits at index 0.
            a.link.deliverPublicText(from = 0xdeadbeefu, body = "hi", id = 5u)
            runCurrent()
            assertTrue(posts.isEmpty())
            assertEquals(1L, a.metrics.meshtastic().meshPostHeard)
            assertEquals(
                1L,
                a.metrics.meshtastic().meshPostRefusedByReason[PublicChannelPolicy.Refusal.KNIT_ON_PRIMARY.name],
            )
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun aRoomSwitchedOffDropsThePrimaryPacketWhereItLands() =
        runTest {
            // The whole point of the switch is that nothing downstream runs: not the judge, not the
            // signature verify, not the row, not the notification. So the assertion is on the *counters* as
            // much as on the empty list — `meshPostHeard` is what onPrimaryPacket increments on its first
            // line, and it must stay at zero.
            val air = FakeMeshtasticAir()
            val posts = mutableListOf<MeshPost>()
            val r = bridgeRig(air, posts, room = false)

            r.link.deliverPublicText(from = 0xdeadbeefu, body = "anyone around?", id = 77u)
            runCurrent()

            assertTrue("nothing reached the room", posts.isEmpty())
            val snap = r.metrics.snapshot()
            assertEquals("the packet was never judged", 0L, snap.meshtastic.meshPostHeard)
            assertEquals(0L, snap.meshtastic.meshPostIngested)
            assertEquals(1L, snap.meshtastic.meshPostRefusedByReason[MESH_POST_ROOM_OFF])
            r.transport.stop()
        }

    @Test
    fun aRoomSwitchedOffStillCarriesKnitsOwnFrames() =
        runTest {
            // The switch hides a room, it does not stand the board down: a phone that wants the board as a
            // Knit radio and nothing else must keep every Knit frame it had.
            val air = FakeMeshtasticAir()
            val posts = mutableListOf<MeshPost>()
            val r = bridgeRig(air, posts, room = false)

            r.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "over the hill"))
            runCurrent()

            assertTrue("the Knit frame still went out", r.link.sent.isNotEmpty())
            r.transport.stop()
        }

    @Test
    fun aPostCannotLeaveADeviceWhoseRoomIsSwitchedOff() =
        runTest {
            // The composer goes with the row, so this is only reachable from a screen that outlived it or
            // the debug intent — but a post that left anyway would put words on a channel the user has
            // told Knit to stop reading, and they would never see the replies.
            val air = FakeMeshtasticAir()
            val r = bridgeRig(air, mutableListOf(), room = false)

            assertEquals(PublicPostRefusal.ROOM_OFF, r.transport.postToPublicChannel("meet at the trailhead"))
            runCurrent()

            assertTrue("nothing went on the air", r.link.sent.isEmpty())
            assertEquals(0L, r.metrics.meshtastic().publicPostSent)
            r.transport.stop()
        }

    @Test
    fun aBoardPinnedByHandOffKnitsSlotKeepsItsRoom() =
        runTest {
            // ADR 067's 2026-10-22 amendment: a slot pinned in the Meshtastic app may be a community's, and no
            // action of Knit's moves it back, so only Knit's own slot closes the room.
            val air = FakeMeshtasticAir()
            val posts = mutableListOf<MeshPost>()
            val r = bridgeRig(air, posts, pinnedSlot = 20)
            assertFalse(
                "the governor kept the board shared",
                r.transport.status.value.airtime
                    ?.dedicated == true,
            )

            r.link.deliverPublicText(from = 0xdeadbeefu, body = "anyone around?", id = 77u)
            runCurrent()
            assertEquals("anyone around?", posts.single().body)
            assertNull(r.transport.postToPublicChannel("meet at the trailhead"))
            r.transport.stop()
        }

    @Test
    fun aBoardOnADedicatedSlotHasNoRoomToReadOrPostTo() =
        runTest {
            // ADR 067: the room is hidden on a pinned board, so both doors close the way the room switch
            // closes them — a slot-0 post (only another pinned board's, never a public one) is dropped before
            // it is judged, and a post cannot leave. Knit's own frames are untouched; a shared board is.
            val air = FakeMeshtasticAir()
            val posts = mutableListOf<MeshPost>()
            val r = bridgeRig(air, posts, dedicated = true)
            assertTrue(
                "the governor read the pinned slot",
                r.transport.status.value.airtime
                    ?.dedicated == true,
            )

            r.link.deliverPublicText(from = 0xdeadbeefu, body = "anyone around?", id = 77u)
            runCurrent()
            assertTrue("nothing reached the room", posts.isEmpty())
            val snap = r.metrics.snapshot()
            assertEquals("the packet was never judged", 0L, snap.meshtastic.meshPostHeard)
            assertEquals(1L, snap.meshtastic.meshPostRefusedByReason[MESH_POST_DEDICATED])

            assertEquals(PublicPostRefusal.DEDICATED, r.transport.postToPublicChannel("meet at the trailhead"))
            runCurrent()
            assertTrue("nothing went on the air", r.link.sent.isEmpty())

            r.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "over the hill"))
            runCurrent()
            assertTrue("the Knit frame still went out", r.link.sent.isNotEmpty())
            r.transport.stop()

            // The same board under a shared slot — and under the release governor — keeps its room.
            val shared = bridgeRig(air, posts, dedicated = false)
            shared.link.deliverPublicText(from = 0xdeadbeefu, body = "anyone around?", id = 78u)
            runCurrent()
            assertEquals(1, posts.size)
            shared.transport.stop()
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
            assertEquals(1L, r.metrics.meshtastic().publicPostSent)
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
            assertEquals(1L, r.metrics.meshtastic().publicPostSent)
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
                r.metrics.meshtastic().publicPostRefusedByReason[PublicPostRefusal.TOO_SOON.name],
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
                r.metrics.meshtastic().publicPostRefusedByReason[PublicPostRefusal.KNIT_ON_PRIMARY.name],
            )
            r.transport.stop()
        }

    // --- The DM auto-reply (DmAutoReplyPolicy). ---

    @Test
    fun aMeshtasticDmToTheBoardIsAnsweredOnceAndNeverEntersTheRoom() =
        runTest {
            // A stranger's text addressed to the board: nobody on this phone reads it (ADR 2026-09.emd7), so
            // the one thing to do is tell them, once. The answer is a unicast on index 0 — which the firmware
            // turns into a PKI packet to that node — and the message itself is never a room post.
            val air = FakeMeshtasticAir()
            val posts = mutableListOf<MeshPost>()
            val r = bridgeRig(air, posts)

            r.link.deliverPublicText(from = 0xdeadbeefu, body = "hello? anyone there?", id = 11u, to = 1u)
            runCurrent()

            assertTrue("a DM is not a post", posts.isEmpty())
            val i = r.link.sent.indexOfFirst { it.decodeToString() == DmAutoReplyPolicy.TEXT }
            assertTrue("the reply went out", i >= 0)
            assertEquals(0xdeadbeefu, r.link.sentTos[i])
            assertEquals(PublicChannelPolicy.PRIMARY_INDEX, r.link.sentChannels[i])
            assertEquals(MeshtasticProto.PORT_TEXT_MESSAGE, r.link.sentPortnums[i])
            assertEquals(LoraMeshTransport.HOP_LIMIT, r.link.sentHopLimits[i])
            val snap = r.metrics.snapshot()
            assertEquals(1L, snap.meshtastic.autoReplyHeard)
            assertEquals(1L, snap.meshtastic.autoReplySent)
            assertEquals("a reply is not a post either", 0L, snap.meshtastic.publicPostSent)

            // The same sender again, and the board's replay of the first message: silence.
            advanceTimeBy(DmAutoReplyPolicy.FLOOR_MS)
            r.link.deliverPublicText(from = 0xdeadbeefu, body = "hello??", id = 12u, to = 1u)
            r.link.deliverPublicText(from = 0xdeadbeefu, body = "hello? anyone there?", id = 11u, to = 1u)
            runCurrent()

            assertEquals(1, r.link.sent.count { it.decodeToString() == DmAutoReplyPolicy.TEXT })
            assertEquals(
                2L,
                r.metrics.meshtastic().autoReplyRefusedByReason[DmAutoReplyPolicy.Refusal.REPLIED_RECENTLY.name],
            )
            r.transport.stop()
        }

    @Test
    fun aDmToSomeOtherNodeIsNeitherAnsweredNorMirrored() =
        runTest {
            val air = FakeMeshtasticAir()
            val posts = mutableListOf<MeshPost>()
            val r = bridgeRig(air, posts)

            r.link.deliverPublicText(from = 0xdeadbeefu, body = "psst", id = 11u, to = 0x99u)
            runCurrent()

            assertTrue(posts.isEmpty())
            assertTrue(r.link.sentPortnums.none { it == MeshtasticProto.PORT_TEXT_MESSAGE })
            assertEquals(
                1L,
                r.metrics.meshtastic().autoReplyRefusedByReason[DmAutoReplyPolicy.Refusal.NOT_FOR_US.name],
            )
            r.transport.stop()
        }

    @Test
    fun aBurstOfDmsIsOneReplyAtATime() =
        runTest {
            // Three neighbours try the new node inside one floor: one is answered now, and the others are
            // told nothing until they write again once the floor has passed — the floor never stamps them.
            val air = FakeMeshtasticAir()
            val r = bridgeRig(air, mutableListOf())

            r.link.deliverPublicText(from = 0x11u, body = "hi", id = 1u, to = 1u)
            r.link.deliverPublicText(from = 0x22u, body = "hi", id = 2u, to = 1u)
            r.link.deliverPublicText(from = 0x33u, body = "hi", id = 3u, to = 1u)
            runCurrent()

            assertEquals(
                listOf(0x11u),
                r.link.sentTos.filterIndexed { i, _ -> r.link.sentPortnums[i] == MeshtasticProto.PORT_TEXT_MESSAGE },
            )
            assertEquals(2L, r.metrics.meshtastic().autoReplyRefusedByReason[DmAutoReplyPolicy.Refusal.TOO_SOON.name])

            advanceTimeBy(DmAutoReplyPolicy.FLOOR_MS)
            r.link.deliverPublicText(from = 0x22u, body = "hi again", id = 4u, to = 1u)
            runCurrent()

            assertEquals(
                listOf(0x11u, 0x22u),
                r.link.sentTos.filterIndexed { i, _ ->
                    r.link.sentPortnums[i] ==
                        MeshtasticProto.PORT_TEXT_MESSAGE
                },
            )
            r.transport.stop()
        }

    @Test
    fun aBoardOnADedicatedSlotAnswersNoDm() =
        runTest {
            // No public radio can hear a pinned board (ADR 067), so a text addressed to it is another pinned
            // Knit board's — and the line would tell a Knit user their own node is unmonitored.
            val air = FakeMeshtasticAir()
            val r = bridgeRig(air, mutableListOf(), dedicated = true)

            r.link.deliverPublicText(from = 0xdeadbeefu, body = "hi", id = 11u, to = 1u)
            runCurrent()

            assertTrue(r.link.sentPortnums.none { it == MeshtasticProto.PORT_TEXT_MESSAGE })
            assertEquals(1L, r.metrics.meshtastic().autoReplyRefusedByReason[AutoReplyRefusal.DEDICATED.name])
            r.transport.stop()
        }

    @Test
    fun aBoardKnitNeverSetUpAnswersNoDm() =
        runTest {
            // A paired stock board: the setup's confirmation sheet is where the user agreed to the board
            // saying it is unmonitored, and this board never showed it. It still mirrors its primary.
            val air = FakeMeshtasticAir()
            val posts = mutableListOf<MeshPost>()
            val r = rig(air, 1u, "alice", backgroundScope, channelName = "", onPublicPost = { posts += it }) { testScheduler.currentTime }
            r.transport.start()
            runCurrent()

            r.link.deliverPublicText(from = 0xdeadbeefu, body = "hi", id = 11u, to = 1u)
            r.link.deliverPublicText(from = 0xdeadbeefu, body = "hi all", id = 12u)
            runCurrent()

            assertEquals("the room still works", "hi all", posts.single().body)
            assertTrue(r.link.sentPortnums.none { it == MeshtasticProto.PORT_TEXT_MESSAGE })
            assertEquals(1L, r.metrics.meshtastic().autoReplyRefusedByReason[AutoReplyRefusal.NOT_SET_UP.name])
            r.transport.stop()
        }

    @Test
    fun aDmTheRoomsAirShareCannotCarryIsRefusedNotQueued() =
        runTest {
            // Same rule as a post: a reply the window will not carry is counted now rather than sitting in
            // the queue looking sent. And a refusal for air is the transport's, so the sender *was* stamped —
            // the policy claimed the reply; the budget is what said no.
            val air = FakeMeshtasticAir()
            val airtime = LoraAirtime()
            val r =
                rig(
                    air,
                    1u,
                    "alice",
                    backgroundScope,
                    config = MutableStateFlow(LoraConfig("AA:1", 1)),
                    pace = LoraPacePolicy(minGapMs = 0, airtime = airtime),
                ) { testScheduler.currentTime }
            r.transport.start()
            runCurrent()
            r.link.readyProvisioned()
            runCurrent()
            // Spend the public share before the DM arrives — a share there was, on the LongFast/US board
            // `readyProvisioned` reports.
            assertTrue(airtime.budgetMs(AirBucket.PUBLIC) > 0)
            while (airtime.admits(AirBucket.PUBLIC, FrameClass.ROOM, listOf(150), testScheduler.currentTime)) {
                airtime.record(AirBucket.PUBLIC, 150, testScheduler.currentTime)
            }

            r.link.deliverPublicText(from = 0xdeadbeefu, body = "hi", id = 11u, to = 1u)
            runCurrent()

            assertTrue(r.link.sentPortnums.none { it == MeshtasticProto.PORT_TEXT_MESSAGE })
            assertEquals(1L, r.metrics.meshtastic().autoReplyRefusedByReason[AutoReplyRefusal.NO_AIR.name])
            r.transport.stop()
        }

    @Test
    fun theSendersAnsweredOutliveTheProcess() =
        runTest {
            // The board replays its queue on reconnect, and a restart is a reconnect: without the memory the
            // same message would be answered twice, which is exactly the spam the cap exists to prevent.
            val air = FakeMeshtasticAir()
            val state = FakePlaneState()
            val first = bridgeRig(air, mutableListOf(), state = state)
            first.link.deliverPublicText(from = 0xdeadbeefu, body = "hi", id = 11u, to = 1u)
            runCurrent()
            assertEquals(1L, first.metrics.meshtastic().autoReplySent)
            first.transport.stop()
            runCurrent()
            assertEquals(listOf(DmAutoReplyPolicy.senderKey(0xdeadbeefu)), state.snapshot?.autoReplied?.map { it.id })

            advanceTimeBy(60 * 60_000L) // an hour on — inside the day
            val second = bridgeRig(air, mutableListOf(), state = state)
            second.link.deliverPublicText(from = 0xdeadbeefu, body = "hi", id = 11u, to = 1u)
            runCurrent()

            assertEquals(0L, second.metrics.meshtastic().autoReplySent)
            assertEquals(
                1L,
                second.metrics.meshtastic().autoReplyRefusedByReason[DmAutoReplyPolicy.Refusal.REPLIED_RECENTLY.name],
            )
            second.transport.stop()
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
                r.metrics.meshtastic().meshPostRefusedByReason[PublicChannelPolicy.Refusal.OWN_BOARD.name],
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
            assertEquals(0, a.metrics.lora().loraSent)
            a.transport.stop()
            b.transport.stop()
        }

    /**
     * A board between sessions must cost the queue nothing. The two states look alike from the send path and
     * are opposites: a slot that is not the Knit channel is a frame that must never leave, while a link that
     * is merely down is a frame that has not left *yet*.
     *
     * Field-observed 2026-09-06: a lab Pixel 7's board flapped five times in twenty minutes, and the
     * escalating reconnect backoff (93 s by the fifth) let the pacer take and discard one queued frame per
     * gap — six destroyed in eighteen seconds, logged as `slot 1 is not the Knit channel — set this board up`
     * against a board whose setup was never wrong. Nothing else holds a copy of a frame the pacer has taken,
     * so this is silent, permanent loss on the one plane whose whole point is reaching a peer no other plane
     * can.
     */
    @Test
    fun framesQueuedWhileTheBoardIsReconnectingSurviveTheOutage() =
        runTest {
            val air = FakeMeshtasticAir()
            // The real pacer's floor, not the rig's default 0: the bug was one lost frame *per gap*, so a
            // test that never gaps cannot see how much an outage costs.
            val a = rig(air, 1u, "alice", backgroundScope, pace = LoraPacePolicy(minGapMs = 3_000)) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()
            advanceTimeBy(1)
            runCurrent()
            val airedBeforeOutage = a.link.sent.size

            a.link.drop()
            runCurrent()
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "north gate in ten"))
            runCurrent()
            advanceTimeBy(93_000) // the backoff the field actually saw — thirty-one pacer gaps
            runCurrent()

            assertEquals("nothing leaves a board that is not there", airedBeforeOutage, a.link.sent.size)
            assertEquals("and no frame is charged to the channel guard", 0L, a.metrics.lora().loraSuppressed)

            a.link.ready()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()

            assertTrue(
                "the frame the outage held reaches bob once the board is back",
                b.received.any { it.envelope.senderId == "alice" && it.envelope.type == FrameType.CHAT },
            )
            a.transport.stop()
            b.transport.stop()
        }

    /**
     * A board that is away must not keep the pacer awake. With a frame held over the outage and its due time
     * long past, the pacer's idle tick used to fire every second for as long as the board was gone — 3,600
     * wakeups an hour that could send nothing (#67). It parks on the link state now, and the session-up is
     * what moves it.
     */
    @Test
    fun aPacerWithAQueuedFrameSleepsThroughABoardOutage() =
        runTest {
            val air = FakeMeshtasticAir()
            var reads = 0
            val clock = {
                reads++
                testScheduler.currentTime
            }
            val a = rig(air, 1u, "alice", backgroundScope, pace = LoraPacePolicy(minGapMs = 3_000), now = clock)
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()
            advanceTimeBy(1)
            runCurrent()

            a.link.drop()
            runCurrent()
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "north gate in ten"))
            advanceTimeBy(10_000)
            runCurrent()

            val atRest = reads
            advanceTimeBy(10 * 60_000L)
            runCurrent()
            assertTrue(
                "the pacer slept through ten minutes of outage (${reads - atRest} clock reads; the old idle tick made 600)",
                reads - atRest < OUTAGE_READS_PER_TEN_MINUTES,
            )

            a.link.ready()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue(
                "the held frame leaves once the board is back",
                b.received.any { it.envelope.senderId == "alice" && it.envelope.type == FrameType.CHAT },
            )
            a.transport.stop()
            b.transport.stop()
        }

    /**
     * The queue is a **time-delayed commitment**, and this is the gap that costs airtime: a DM-form frame is
     * admitted because no better plane held a link to its addressee *at that instant*, then waits behind the
     * pacing floor, a full board queue and a spent airtime share while the answer changes underneath it.
     *
     * Field-observed 2026-09-09 on the lab Pixel 7. It had been alone for hours; two peers appeared on
     * Wi-Fi Aware at once and 107 frames landed in a minute. The receipts it owed for them were queued in the
     * four seconds before the second peer's link came up, and the pacer then spent the plane's entire
     * 45-second window putting on the air what that link had already carried — 22 packets for one typed
     * message. The far side proved it, receiving one frame id over Wi-Fi Aware and the same id over LoRa ten
     * minutes later.
     */
    @Test
    fun aQueuedDmIsAbandonedWhenItsRecipientLinksWhileItWaits() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            advanceTimeBy(1)
            runCurrent()

            hearFrom(a, "bob") // heard, so the reach gate lets the frame queue (ADR 2026-10.a4pm)
            runCurrent()
            a.link.free = 0 // the board has no room, so the frame sits in the pacer exactly as it did in the field
            runCurrent()
            val sentBefore = a.link.sent.size
            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", relay = false), FanoutHint.TICK)
            runCurrent()
            assertEquals("it is queued, not aired", sentBefore, a.link.sent.size)

            // Wi-Fi Aware links bob while the frame waits — the four seconds the field failure turned on.
            a.transport.suppressDataPath(setOf("bob"))
            a.link.updateHeadroom(16)
            advanceTimeBy(10_000)
            runCurrent()

            assertEquals("the link carried it; the board must not repeat it", sentBefore, a.link.sent.size)
            val snap = a.metrics.snapshot()
            assertEquals(1L, snap.lora.loraStaleAtSend)
            assertEquals(mapOf(StaleAtSend.LINKED.name to 1L), snap.lora.loraStaleAtSendByReason)
            assertEquals("nothing was shed that the plane wanted", 0L, snap.lora.loraDroppedQueue)
            a.transport.stop()
        }

    /**
     * The other half of the same rule, and the one this fix could most easily break: a *sighting* is not a
     * data path. BLE advertises far beyond L2CAP range and Wi-Fi Aware keeps a peer listed for 150 s after
     * its last cue, so refusing on one takes away a far peer's only route — ADR 044's field amendment, which
     * cost two Pixels across a field every message and every ✓✓ they had.
     */
    @Test
    fun aQueuedDmStillRidesWhenItsRecipientIsOnlySighted() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            advanceTimeBy(1)
            runCurrent()

            hearFrom(a, "bob") // heard, so the reach gate lets the frame queue (ADR 2026-10.a4pm)
            runCurrent()
            a.link.free = 0
            runCurrent()
            val sentBefore = a.link.sent.size
            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", relay = false), FanoutHint.TICK)
            runCurrent()

            a.transport.onForeignReachable(setOf("bob")) // heard, never linked
            a.link.updateHeadroom(16)
            advanceTimeBy(10_000)
            runCurrent()

            assertTrue("a merely-sighted peer's DM still rides", a.link.sent.size > sentBefore)
            assertEquals(0L, a.metrics.lora().loraStaleAtSend)
            a.transport.stop()
        }

    /**
     * The freshness gate has the same shape as the recipient gate and goes stale the same way: a chat is
     * admitted inside [LoraFramePolicy.FRESH_MS] and then waits past it, at which point it is a custody
     * re-serve and fanning it would spend a newcomer's whole backfill on the air. The queue is sized to hold
     * a fifteen-minute wait, so the dwell and the window are the same length by design.
     */
    @Test
    fun aQueuedChatThatAgesPastTheFreshnessWindowIsAbandonedAtSend() =
        runTest {
            val air = FakeMeshtasticAir()
            // Bridge off: this test has to advance past the gossip interval to age the frame, and an OFFER
            // put on the air on the way would be counted here as the frame that escaped.
            val a =
                rig(air, 1u, "alice", backgroundScope, config = MutableStateFlow(LoraConfig("AA:1", 0, bridge = false))) {
                    testScheduler.currentTime
                }
            a.transport.start()
            runCurrent()
            advanceTimeBy(1)
            runCurrent()

            a.link.free = 0
            runCurrent()
            val sentBefore = a.link.sent.size
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "north gate in ten", sentAt = testScheduler.currentTime))
            runCurrent()

            advanceTimeBy(LoraFramePolicy.FRESH_MS + 1_000)
            a.link.updateHeadroom(16)
            advanceTimeBy(10_000)
            runCurrent()

            assertEquals("a frame this old is custody's business, not a live plane's", sentBefore, a.link.sent.size)
            assertEquals(mapOf(StaleAtSend.STALE.name to 1L), a.metrics.lora().loraStaleAtSendByReason)
            a.transport.stop()
        }

    /**
     * Asked only of a frame that has not yet put a fragment on the air. A board that runs out of queue
     * part-way through a fragmented frame refuses the rest and the frame is requeued whole; abandoning it on
     * the second pass would strand the fragments the board already holds, and the far side would wait on a
     * reassembly that can never complete.
     */
    @Test
    fun aPartSentFrameFinishesItsFragmentsEvenWhenItsRecipientLinks() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            advanceTimeBy(1)
            runCurrent()

            // Two slots for a frame that needs more, so the board takes some fragments and refuses the rest.
            a.link.queueFills = true
            a.link.free = 2
            runCurrent()
            a.transport.longRangeFanout(
                frame(FrameType.CHAT, "alice", recipientId = "bob", relay = false, body = incompressibleBody(600)),
                FanoutHint.CONTENT,
            )
            advanceTimeBy(5_000)
            runCurrent()
            val partSent = a.link.sent.size
            assertTrue("the board took some of it and refused the rest", partSent > 0)

            a.transport.suppressDataPath(setOf("bob")) // bob links mid-frame
            a.link.queueFills = false
            a.link.updateHeadroom(16)
            advanceTimeBy(10_000)
            runCurrent()

            assertTrue("the fragments already on the air are finished, not stranded", a.link.sent.size > partSent)
            assertEquals(0L, a.metrics.lora().loraStaleAtSend)
            a.transport.stop()
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
            assertEquals(1L, b.metrics.lora().loraReassembled)
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
            assertEquals(1L, a.metrics.lora().loraDmSent)
            assertEquals(1L, b.metrics.lora().loraDmReceived)

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
            assertEquals(2L, a.metrics.lora().loraSuppressed)

            // A peer's profile carries its publish stamp (hours old) and is the key bootstrap — never refused.
            a.transport.fastFanout(frame(FrameType.PROFILE, "carol", body = "x".repeat(20), sentAt = 0L))
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("an old profile still rides", baseline + 1, a.link.sent.size)

            val now = testScheduler.currentTime
            hearFrom(a, "dave", sentAt = now) // heard, so the reach gate lets a relayed DM to him ride (ADR 2026-10.a4pm)
            advanceTimeBy(4_000)
            runCurrent()
            val beforeFresh = a.link.sent.size // past the first-hearing beacon that hearing set off
            a.transport.longRangeFanout(frame(FrameType.CHAT, "carol", recipientId = "dave", body = "fresh dm", sentAt = now))
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("a fresh DM rides", beforeFresh + 1, a.link.sent.size)
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
            // alice fanned the first DM live a moment ago (bob's board was off), as the probe to a peer she had not
            // heard (ADR 2026-10.a4pm) — still inside the dedup window.
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
            assertEquals(1L, a.metrics.lora().loraReoffered)
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
            // The live DM goes now its dedup window has lapsed; the carried one was re-offered inside the hour and
            // waits (ADR 2026-10.a4pm's per-frame memory).
            assertEquals(2L, a.metrics.lora().loraReoffered)
            a.transport.stop()
            b.transport.stop()
        }

    // --- The reach gate: a DM rides only to a recipient this plane has heard (ADR 2026-10.a4pm). ---

    /** A rig with the bridge off, so no OFFER lands among the frames a reach-gate test counts. */
    private fun TestScope.quietRig(
        air: FakeMeshtasticAir,
        nodeNum: UInt,
        selfNode: String,
        farFrames: suspend (String) -> List<WireEnvelope> = { emptyList() },
        pace: LoraPacePolicy = LoraPacePolicy(minGapMs = 0),
        state: LoraPlaneState = LoraPlaneState.None,
        framesMissing: suspend (IntArray, Int, Boolean) -> List<WireEnvelope> = { _, _, _ -> emptyList() },
        bridge: Boolean = false,
    ): Rig =
        rig(
            air,
            nodeNum,
            selfNode,
            backgroundScope,
            config = MutableStateFlow(LoraConfig("AA:$nodeNum", 0, bridge = bridge)),
            farFrames = farFrames,
            framesMissing = framesMissing,
            pace = pace,
            state = state,
        ) { testScheduler.currentTime }

    /**
     * The whole mechanism on two boards: alice texts bob three times while she has never heard him. The first
     * DM is the probe and airs; the other two stay in custody with their dedup slots free. Bob's board comes up,
     * alice hears him for the first time, and the re-offer carries the two she held — on the LIVE bucket, since
     * they were live sends that waited, not backfill.
     */
    @Test
    fun aDmToAnUnheardPeerProbesOnceAndTheRestRideItsFirstHearingOnTheLiveBucket() =
        runTest {
            val air = FakeMeshtasticAir()
            val dms = (1..3).map { frame(FrameType.CHAT, "alice", recipientId = "bob", body = "dm $it") }
            val pace = LoraPacePolicy(minGapMs = 0)
            val a = quietRig(air, 1u, "alice", farFrames = { dms.reversed() }, pace = pace)
            a.transport.start()
            runCurrent()
            advanceTimeBy(5_000) // the session-up beacon drains
            runCurrent()

            dms.forEach { a.transport.longRangeFanout(it, FanoutHint.CHAT) }
            advanceTimeBy(10_000)
            runCurrent()

            assertEquals("one probe", 1L, a.metrics.lora().loraDmProbes)
            assertEquals("the other two held", 2L, a.metrics.lora().loraUnheardHeld)
            assertEquals("only the probe reached the air", 1L, a.metrics.lora().loraDmSent)
            val before = pace.airtime.snapshot(testScheduler.currentTime)

            val b = quietRig(air, 2u, "bob")
            b.transport.start() // bob beacons; alice hears him for the first time
            advanceTimeBy(10_000)
            runCurrent()

            assertEquals("the two held DMs, not the probe the dedup window still holds", 2L, a.metrics.lora().loraReoffered)
            assertEquals(3L, a.metrics.lora().loraDmSent)
            // The probe went to a board that was off; once bob's ✓✓ for these lands, framesFor drops them and a
            // later hearing re-offers what is still unacked — the probe included, its dedup slot lapsed.
            assertTrue("bob has the two that were held", dms.drop(1).all { dm -> b.received.any { it.envelope.id == idOf(dm) } })
            val after = pace.airtime.snapshot(testScheduler.currentTime)
            assertTrue("our own re-offer spends LIVE", after.liveUsedMs > before.liveUsedMs)
            assertEquals("and none of BRIDGE", before.bridgeUsedMs, after.bridgeUsedMs)
            a.transport.stop()
            b.transport.stop()
        }

    /** A frame alice carries for someone else is backfill, and keeps the backfill's meter. */
    @Test
    fun aRelayedReofferStaysOnTheBridgeBucket() =
        runTest {
            val air = FakeMeshtasticAir()
            val carried = frame(FrameType.CHAT, "carol", recipientId = "bob", body = "relayed for carol")
            val pace = LoraPacePolicy(minGapMs = 0)
            val a = quietRig(air, 1u, "alice", farFrames = { listOf(carried) }, pace = pace)
            a.transport.start()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            val before = pace.airtime.snapshot(testScheduler.currentTime)

            val b = quietRig(air, 2u, "bob")
            b.transport.start()
            advanceTimeBy(10_000)
            runCurrent()

            assertEquals(1L, a.metrics.lora().loraReoffered)
            assertTrue(b.received.any { it.envelope.id == idOf(carried) })
            val after = pace.airtime.snapshot(testScheduler.currentTime)
            assertTrue("someone else's frame spends BRIDGE", after.bridgeUsedMs > before.bridgeUsedMs)
            assertEquals("and none of LIVE", before.liveUsedMs, after.liveUsedMs)
            a.transport.stop()
            b.transport.stop()
        }

    /**
     * One probe per recipient per window, and the window is a limiter: it survives a restart (ADR 2026-09.7svb)
     * and reopens once [LoraMeshTransport.DM_PROBE_WINDOW_MS] has passed — an absent recipient costs one DM's
     * air per window, never one per launch.
     */
    @Test
    fun theProbeWindowOutlivesARestartAndReopensWhenItEnds() =
        runTest {
            val air = FakeMeshtasticAir()
            val state = FakePlaneState()
            val first = quietRig(air, 1u, "alice", state = state)
            first.transport.start()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            val probedAt = testScheduler.currentTime
            first.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", sentAt = probedAt), FanoutHint.CHAT)
            first.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "dave", sentAt = probedAt), FanoutHint.CHAT)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("one probe per recipient", 2L, first.metrics.lora().loraDmProbes)
            first.transport.stop()
            runCurrent()
            assertEquals(
                setOf("bob", "dave"),
                state.snapshot
                    ?.dmProbes
                    ?.map { it.id }
                    ?.toSet(),
            )

            advanceTimeBy(60_000)
            val second = quietRig(air, 1u, "alice", state = state)
            second.transport.start()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            second.transport.longRangeFanout(
                frame(FrameType.CHAT, "alice", recipientId = "bob", sentAt = testScheduler.currentTime),
                FanoutHint.CHAT,
            )
            runCurrent()
            assertEquals("the restart did not reopen bob's window", 0L, second.metrics.lora().loraDmProbes)
            assertEquals(1L, second.metrics.lora().loraUnheardHeld)

            advanceTimeBy(probedAt + LoraMeshTransport.DM_PROBE_WINDOW_MS - testScheduler.currentTime)
            runCurrent()
            second.transport.longRangeFanout(
                frame(FrameType.CHAT, "alice", recipientId = "bob", sentAt = testScheduler.currentTime),
                FanoutHint.CHAT,
            )
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("the window has ended, so the next DM probes again", 1L, second.metrics.lora().loraDmProbes)
            assertEquals(1L, second.metrics.lora().loraDmSent)
            second.transport.stop()
        }

    /**
     * Our own ✓✓ is not gated (ADR 2026-10.a4pm): it exists only because its author's DM just reached us, and held
     * it would never be re-offered — the author would see one tick until radio contact. It never spends the probe
     * either. The room, addressed to nobody, is not gated at all.
     */
    @Test
    fun anOwnTickToAnUnheardPeerAirsWithoutSpendingTheProbeWhileTheRoomStillRides() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = quietRig(air, 1u, "alice")
            a.transport.start()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            val sentBefore = a.link.sent.size

            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", body = ""), FanoutHint.TICK)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("the tick aired", 1L, a.metrics.lora().loraDmSent)
            assertEquals(0L, a.metrics.lora().loraUnheardHeld)
            assertEquals(0L, a.metrics.lora().loraDmProbes)

            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "north gate in ten"))
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("the room rides with nobody heard", sentBefore + 2, a.link.sent.size)

            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", body = "still there?"), FanoutHint.CHAT)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("the tick left the window unspent", 1L, a.metrics.lora().loraDmProbes)
            a.transport.stop()
        }

    /**
     * The reach gate is re-asked on the way out (ADR 2026-09.5dt2): a DM queued while bob was heard is dropped
     * if he lingers out before it airs, and its dedup slot is handed back, so his next first hearing re-offers it.
     */
    @Test
    fun aQueuedDmWhoseRecipientLingersOutIsDroppedUnheardAndReofferedLater() =
        runTest {
            val air = FakeMeshtasticAir()
            val dm = mutableListOf<WireEnvelope>()
            val a = quietRig(air, 1u, "alice", farFrames = { dm.toList() })
            a.transport.start()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            hearFrom(a, "bob", sentAt = testScheduler.currentTime)
            runCurrent()
            advanceTimeBy(LoraMeshTransport.REACHABLE_LINGER_MS - 2 * 60_000)
            runCurrent()

            a.link.free = 0
            runCurrent()
            val sentBefore = a.link.sent.size
            dm += frame(FrameType.CHAT, "alice", recipientId = "bob", body = "late", sentAt = testScheduler.currentTime)
            a.transport.longRangeFanout(dm.single(), FanoutHint.CHAT)
            runCurrent()
            assertEquals("bob was heard, so it queued rather than probed", 0L, a.metrics.lora().loraDmProbes)

            advanceTimeBy(3 * 60_000) // bob lingers out while the board holds the queue
            runCurrent()
            a.link.updateHeadroom(16)
            advanceTimeBy(10_000)
            runCurrent()

            assertEquals("never aired", sentBefore, a.link.sent.size)
            assertEquals(mapOf(StaleAtSend.UNHEARD.name to 1L), a.metrics.lora().loraStaleAtSendByReason)

            hearFrom(a, "bob", sentAt = testScheduler.currentTime) // inside the frame's 10-min dedup window
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("the handed-back slot let the re-offer take it", 1L, a.metrics.lora().loraReoffered)
            assertEquals(1L, a.metrics.lora().loraDmSent)
            a.transport.stop()
        }

    /** The probe flew because its recipient was unheard, so that answer staying "unheard" must not drop it. */
    @Test
    fun aQueuedProbeIsNotDroppedForItsRecipientStillBeingUnheard() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = quietRig(air, 1u, "alice")
            a.transport.start()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            a.link.free = 0
            runCurrent()
            val sentBefore = a.link.sent.size
            a.transport.longRangeFanout(
                frame(FrameType.CHAT, "alice", recipientId = "bob", sentAt = testScheduler.currentTime),
                FanoutHint.CHAT,
            )
            runCurrent()
            assertEquals("it is queued, not aired", sentBefore, a.link.sent.size)

            a.link.updateHeadroom(16)
            advanceTimeBy(10_000)
            runCurrent()

            assertEquals(1L, a.metrics.lora().loraDmProbes)
            assertEquals(1L, a.metrics.lora().loraDmSent)
            assertEquals(0L, a.metrics.lora().loraStaleAtSend)
            a.transport.stop()
        }

    /**
     * The hint decides what the gate asks about. Our own sealed ctl (a reaction, a reset, a seed — hinted
     * [FanoutHint.CONTENT]) has no message row, so nothing would ever re-offer it: it flies as before. A relayed
     * DM-form frame is opaque: held, and it never spends the probe — that is our own DMs' to spend.
     */
    @Test
    fun anOwnCtlFliesToAnUnheardPeerWhileARelayedDmIsHeldAndNeverProbes() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = quietRig(air, 1u, "alice")
            a.transport.start()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()

            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", body = "a sealed reaction"))
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("our own ctl rode", 1L, a.metrics.lora().loraDmSent)
            assertEquals(0L, a.metrics.lora().loraUnheardHeld)

            a.transport.longRangeFanout(frame(FrameType.CHAT, "carol", recipientId = "bob", body = "carol's dm"))
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("the relayed one was held", 1L, a.metrics.lora().loraUnheardHeld)
            assertEquals("and spent no probe", 0L, a.metrics.lora().loraDmProbes)
            assertEquals(1L, a.metrics.lora().loraDmSent)

            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", body = "our dm"), FanoutHint.CHAT)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("our own DM still has the window", 1L, a.metrics.lora().loraDmProbes)
            a.transport.stop()
        }

    /**
     * A board-less pocket-mate's DM, held while we were its gateway, was a live send too: re-offered on LIVE.
     * Someone else's stays on BRIDGE (pinned by [aRelayedReofferStaysOnTheBridgeBucket]).
     */
    @Test
    fun aLinkedPocketMatesHeldDmIsReofferedOnTheLiveBucket() =
        runTest {
            val air = FakeMeshtasticAir()
            val carried = frame(FrameType.CHAT, "carol", recipientId = "bob", body = "from my pocket-mate")
            val pace = LoraPacePolicy(minGapMs = 0)
            val a = quietRig(air, 1u, "alice", farFrames = { listOf(carried) }, pace = pace)
            a.transport.start()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            a.transport.suppressDataPath(setOf("carol")) // carol is linked: she rides our board
            // Her first DM to the unheard bob takes the probe (into a sky bob's board is not yet in); the next is held.
            a.transport.longRangeFanout(frame(FrameType.CHAT, "carol", recipientId = "bob", body = "the probe"))
            a.transport.longRangeFanout(carried)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(1L, a.metrics.lora().loraDmProbes)
            assertEquals("held: bob is not heard", 1L, a.metrics.lora().loraUnheardHeld)
            val before = pace.airtime.snapshot(testScheduler.currentTime)

            val b = quietRig(air, 2u, "bob")
            b.transport.start()
            advanceTimeBy(10_000)
            runCurrent()

            assertEquals(1L, a.metrics.lora().loraReoffered)
            assertTrue(b.received.any { it.envelope.id == idOf(carried) })
            val after = pace.airtime.snapshot(testScheduler.currentTime)
            assertTrue("a pocket-mate's held DM spends LIVE", after.liveUsedMs > before.liveUsedMs)
            assertEquals(before.bridgeUsedMs, after.bridgeUsedMs)
            a.transport.stop()
            b.transport.stop()
        }

    /**
     * One frame is re-aired at most once an hour across first hearings, and the memory outlives a restart: a
     * recipient we hear only now and then would otherwise have the same backlog re-aired every linger cycle.
     */
    @Test
    fun aFrameIsReofferedAtMostOnceAnHourAcrossFirstHearingsAndRestarts() =
        runTest {
            val air = FakeMeshtasticAir()
            val state = FakePlaneState()
            val dm = frame(FrameType.CHAT, "alice", recipientId = "bob", body = "still unacked")
            val first = quietRig(air, 1u, "alice", farFrames = { listOf(dm) }, state = state)
            first.transport.start()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            hearFrom(first, "bob", sentAt = testScheduler.currentTime)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("the first hearing re-offers it", 1L, first.metrics.lora().loraReoffered)

            // Bob lingers out and is heard again 46 minutes on: past the 10-min dedup window, inside the hour.
            advanceTimeBy(LoraMeshTransport.REACHABLE_LINGER_MS + 60_000)
            runCurrent()
            hearFrom(first, "bob", sentAt = testScheduler.currentTime)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("not re-aired inside the hour", 1L, first.metrics.lora().loraReoffered)
            first.transport.stop()
            runCurrent()
            assertEquals(listOf(idOf(dm)), state.snapshot?.reoffered?.map { it.id })

            val second = quietRig(air, 1u, "alice", farFrames = { listOf(dm) }, state = state)
            second.transport.start()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            hearFrom(second, "bob", sentAt = testScheduler.currentTime)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("the restart did not forget it", 0L, second.metrics.lora().loraReoffered)

            advanceTimeBy(LoraMeshTransport.REACHABLE_LINGER_MS + 60_000) // past the hour, and bob lingered out
            runCurrent()
            hearFrom(second, "bob", sentAt = testScheduler.currentTime)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("an hour on, it may go again", 1L, second.metrics.lora().loraReoffered)
            second.transport.stop()
        }

    /**
     * A copy the bridge queued on BRIDGE holds the frame's dedup slot; when the recipient is then heard, the
     * re-offer lifts that copy onto LIVE in place instead of being turned away — the stall the lab found, where
     * a spent BRIDGE share held our own DM for a window while LIVE had air.
     */
    @Test
    fun aReofferLiftsTheBridgesQueuedCopyOntoLive() =
        runTest {
            val air = FakeMeshtasticAir()
            val dm = frame(FrameType.CHAT, "alice", recipientId = "bob", body = "held for bob")
            val pace = LoraPacePolicy(minGapMs = 0)
            val a =
                quietRig(
                    air,
                    1u,
                    "alice",
                    farFrames = { listOf(dm) },
                    pace = pace,
                    framesMissing = { _, _, _ -> listOf(dm) },
                    bridge = true,
                )
            a.transport.start()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            a.link.free = 0 // the board holds the queue, so the served copy waits as a spent share would hold it
            runCurrent()
            val sentBefore = a.link.sent.size
            // The bridge's copy: bob's board OFFERs a set that lacks it, and the serve queues it on BRIDGE.
            hearOffer(a, from = 9u, publisher = "bob")
            runCurrent()
            assertEquals("queued, not aired", sentBefore, a.link.sent.size)
            val queued = pace.pending

            hearFrom(a, "bob", sentAt = testScheduler.currentTime)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals("the re-offer took the queued copy's place", 1L, a.metrics.lora().loraReoffered)
            assertEquals("one copy, not two", queued, pace.pending)
            a.link.updateHeadroom(16)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("it aired once, on LIVE", 0L, pace.airtime.snapshot(testScheduler.currentTime).bridgeUsedMs)
            assertTrue(a.metrics.lora().loraDmSent >= 1)
            a.transport.stop()
        }

    /**
     * A probe the queue took by shedding an older frame is in the queue, so it spent the window and is counted
     * — `DROPPED_OLDEST` is an admission, only `REFUSED` gives the window back.
     */
    @Test
    fun aProbeThatShedAnOlderFrameStillSpentTheWindow() =
        runTest {
            val air = FakeMeshtasticAir()
            val pace = LoraPacePolicy(minGapMs = 0, queueCap = 1)
            val a = quietRig(air, 1u, "alice", pace = pace)
            a.transport.start()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            pace.onQueueStatus(0)
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "a room post fills the queue"))
            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", body = "probe"), FanoutHint.CHAT)
            assertEquals("the DM shed the room post", 1L, a.metrics.lora().loraDroppedQueue)
            assertEquals("and is the probe", 1L, a.metrics.lora().loraDmProbes)
            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", body = "second"), FanoutHint.CHAT)
            assertEquals("the window is spent", 1L, a.metrics.lora().loraUnheardHeld)
            a.transport.stop()
        }

    /**
     * As the gateway, a board-less pocket-mate's DM reaches the air only through us, unhinted: it may take the
     * probe for its recipient, or its DMs to a quiet far peer would wait for a hearing nothing provokes. A
     * stranger's relayed DM still may not.
     */
    @Test
    fun aLinkedPocketMatesDmMayProbeAsTheGateway() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = quietRig(air, 1u, "alice")
            a.transport.start()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            a.transport.suppressDataPath(setOf("carol"))
            a.transport.longRangeFanout(frame(FrameType.CHAT, "carol", recipientId = "bob", body = "carol's dm"))
            a.transport.longRangeFanout(frame(FrameType.CHAT, "carol", recipientId = "bob", body = "and another"))
            a.transport.longRangeFanout(frame(FrameType.CHAT, "erin", recipientId = "dave", body = "a stranger's"))
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("carol's first DM probed for bob", 1L, a.metrics.lora().loraDmProbes)
            assertEquals("her second and the stranger's were held", 2L, a.metrics.lora().loraUnheardHeld)
            assertEquals(1L, a.metrics.lora().loraDmSent)
            a.transport.stop()
        }

    /** A probe that never flew told nobody anything: dropped at send, it gives the window back. */
    @Test
    fun aProbeDroppedAtSendGivesItsWindowBack() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = quietRig(air, 1u, "alice")
            a.transport.start()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            a.link.free = 0
            runCurrent()
            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", body = "probe"), FanoutHint.CHAT)
            runCurrent()
            assertEquals(1L, a.metrics.lora().loraDmProbes)
            a.transport.suppressDataPath(setOf("bob")) // bob links while it waits: LINKED at send
            a.link.updateHeadroom(16)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(mapOf(StaleAtSend.LINKED.name to 1L), a.metrics.lora().loraStaleAtSendByReason)

            a.transport.suppressDataPath(emptySet()) // and goes again
            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", body = "again"), FanoutHint.CHAT)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("the window was given back, so this one probes", 2L, a.metrics.lora().loraDmProbes)
            assertEquals(1L, a.metrics.lora().loraDmSent)
            a.transport.stop()
        }

    /** A re-offer dropped at send gives its hour back, so the next first hearing can re-offer it. */
    @Test
    fun aReofferDroppedAtSendGivesItsHourBack() =
        runTest {
            val air = FakeMeshtasticAir()
            val dm = frame(FrameType.CHAT, "alice", recipientId = "bob", body = "held")
            val a = quietRig(air, 1u, "alice", farFrames = { listOf(dm) })
            a.transport.start()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            a.link.free = 0
            runCurrent()
            hearFrom(a, "bob", sentAt = testScheduler.currentTime)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals("re-offered on the first hearing", 1L, a.metrics.lora().loraReoffered)
            a.transport.suppressDataPath(setOf("bob")) // linked while it waits
            a.link.updateHeadroom(16)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(mapOf(StaleAtSend.LINKED.name to 1L), a.metrics.lora().loraStaleAtSendByReason)

            // The link goes, bob lingers out and is heard again inside the hour: the dropped re-offer may go again.
            a.transport.suppressDataPath(emptySet())
            advanceTimeBy(LoraMeshTransport.REACHABLE_LINGER_MS + 60_000)
            runCurrent()
            hearFrom(a, "bob", sentAt = testScheduler.currentTime)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(2L, a.metrics.lora().loraReoffered)
            assertEquals(1L, a.metrics.lora().loraDmSent)
            a.transport.stop()
        }

    /**
     * The bridge never queues a second copy of a DM-form frame the pacer already holds: superseding the probe (on
     * LIVE) with its own copy would push it onto BRIDGE, a share that may be spent, when it is going anyway.
     */
    @Test
    fun theBridgeDoesNotPushAQueuedProbeDownToBridge() =
        runTest {
            val air = FakeMeshtasticAir()
            val dm = frame(FrameType.CHAT, "alice", recipientId = "bob", body = "probe", sentAt = 5_000L)
            val pace = LoraPacePolicy(minGapMs = 0)
            val a = quietRig(air, 1u, "alice", pace = pace, framesMissing = { _, _, _ -> listOf(dm) }, bridge = true)
            a.transport.start()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            a.link.free = 0
            runCurrent()
            a.transport.longRangeFanout(dm, FanoutHint.CHAT)
            runCurrent()
            val queued = pace.pending
            hearOffer(a, from = 9u, publisher = "bob")
            runCurrent()
            assertEquals("no second copy, and the first not displaced", queued, pace.pending)
            a.link.updateHeadroom(16)
            advanceTimeBy(10_000)
            runCurrent()
            val air0 = pace.airtime.snapshot(testScheduler.currentTime)
            assertEquals("it left on LIVE", 0L, air0.bridgeUsedMs)
            assertEquals(1L, a.metrics.lora().loraDmSent)
            a.transport.stop()
        }

    /**
     * A probe the board refused on its first fragment (a duty-cycle NAK) never flew: it gives its window and its
     * dedup slot back, so the next DM probes and a re-offer could take the frame.
     */
    @Test
    fun aProbeNakedOnItsFirstFragmentGivesItsWindowBack() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = quietRig(air, 1u, "alice")
            a.transport.start()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            a.link.nakNext = RoutingError.DUTY_CYCLE_LIMIT
            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", body = "probe"), FanoutHint.CHAT)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals(1L, a.metrics.lora().loraNak)
            assertEquals(0L, a.metrics.lora().loraDmSent)

            advanceTimeBy(5 * 60_000) // past the NAK cool-down
            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", body = "again"), FanoutHint.CHAT)
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("the window came back, so this one probes", 2L, a.metrics.lora().loraDmProbes)
            assertEquals(1L, a.metrics.lora().loraDmSent)
            a.transport.stop()
        }

    /** A queued probe the pacer sheds for a higher class never flew either: it gives its window back. */
    @Test
    fun aProbeShedFromAFullQueueGivesItsWindowBack() =
        runTest {
            val air = FakeMeshtasticAir()
            val pace = LoraPacePolicy(minGapMs = 0, queueCap = 1)
            val a = quietRig(air, 1u, "alice", pace = pace)
            a.transport.start()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            pace.onQueueStatus(0)
            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", body = "probe"), FanoutHint.CHAT)
            assertEquals(1L, a.metrics.lora().loraDmProbes)
            a.transport.fastFanout(profile("carol")) // BOOTSTRAP outranks DM: the probe is shed
            assertEquals(1L, a.metrics.lora().loraDroppedQueue)
            pace.onQueueStatus(16) // the board frees up and the profile drains, leaving the queue room for the next
            advanceTimeBy(10_000)
            runCurrent()

            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", body = "again"), FanoutHint.CHAT)
            assertEquals("the shed probe gave its window back", 2L, a.metrics.lora().loraDmProbes)
            a.transport.stop()
        }

    /**
     * A re-offer the queue refused (full of higher classes) never queued: the pacer hands it back and its dedup slot
     * reopens, so a fan-out of the same frame inside the window still rides.
     */
    @Test
    fun aRefusedReofferGivesItsSlotBack() =
        runTest {
            val air = FakeMeshtasticAir()
            val dm = frame(FrameType.CHAT, "alice", recipientId = "bob", body = "held")
            val pace = LoraPacePolicy(minGapMs = 0, queueCap = 1)
            val a = quietRig(air, 1u, "alice", farFrames = { listOf(dm) }, pace = pace)
            a.transport.start()
            runCurrent()
            advanceTimeBy(5_000)
            runCurrent()
            pace.onQueueStatus(0)
            a.transport.fastFanout(profile("carol")) // BOOTSTRAP fills the one slot: a DM-class newcomer is refused
            hearFrom(a, "bob", sentAt = testScheduler.currentTime)
            runCurrent()
            assertEquals("refused, so not counted as re-offered", 0L, a.metrics.lora().loraReoffered)
            assertTrue(a.metrics.lora().loraDroppedQueue >= 1)

            pace.onQueueStatus(16)
            advanceTimeBy(10_000)
            runCurrent()
            a.transport.longRangeFanout(dm, FanoutHint.CHAT) // bob is heard: it rides if its slot came back
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("the slot came back", 1L, a.metrics.lora().loraDmSent)
            assertEquals(0L, a.metrics.lora().loraSuppressed)
            a.transport.stop()
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
            assertEquals(0L, a.metrics.lora().loraDmSent)
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
            assertEquals(0L, a.metrics.lora().loraReoffered)
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

    /** A cleartext `receipt` for [ackId] as AckSync builds each attempt: a fresh id and signature every time. */
    private fun cleartextReceipt(
        sender: String,
        ackId: String,
    ): WireEnvelope {
        val env =
            RelayEnvelope(
                type = FrameType.RECEIPT,
                id = "rcpt-$sigCounter",
                senderId = sender,
                payload = WireCodec.encodePayload(ReceiptContent(ackId)),
            )
        val sig = ByteArray(64)
        sig[0] = (sigCounter shr 8).toByte()
        sig[1] = sigCounter.toByte()
        sigCounter++
        return WireEnvelope(relay = false, sig = sig, signed = WireCodec.encodeEnvelope(env))
    }

    /**
     * [to] hears a fresh room chat [author] wrote, aired by board [from] — what puts [author] in its reach, and
     * so past the DM reach gate (ADR 2026-10.a4pm).
     */
    private fun hearFrom(
        to: Rig,
        author: String,
        from: UInt = 9u,
        sentAt: Long = 0L,
    ) = LoraFrameCodec.encodeBest(frame(FrameType.CHAT, author, body = "here", sentAt = sentAt), fragId = 0)!!.parts.forEach {
        to.link.deliver(from = from, channelIndex = 0, portnum = MeshtasticProto.PORT_PRIVATE_APP, payload = it)
    }

    /** [to] hears [publisher]'s OFFER aired by board [from] — how the plane learns whose radio that is. */
    private fun hearOffer(
        to: Rig,
        from: UInt,
        publisher: String,
    ) = to.link.deliver(
        from = from,
        channelIndex = 0,
        portnum = MeshtasticProto.PORT_PRIVATE_APP,
        payload = LoraCtl.encodeOffer(StoreDigest.hash64(publisher), IntArray(0), 200),
    )

    @Test
    fun aCoPocketGatewaysAiringIsNotReachForItsAuthors() =
        runTest {
            // Field, 2026-09-26: the lab P9 sat PASSIVE beside the P7 gateway it held a BLE link to. The P7 aired a
            // board-less test peer's room posts, the P9's board heard them, and the author went into the P9's
            // `reachable` — so every ✓✓ owed to it went onto the air, 218 at a time, and held the window full.
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()
            b.transport.suppressDataPath(setOf("alice")) // bob holds a live link to alice: same pocket
            hearOffer(b, from = 1u, publisher = "alice")
            runCurrent()

            a.transport.fastFanout(frame(FrameType.CHAT, "carol", body = "carol has no board"))
            advanceTimeBy(4_000)
            runCurrent()

            assertTrue("the frame still crosses — delivery is untouched", b.received.any { it.envelope.senderId == "carol" })
            assertFalse(
                "but a co-pocket board's airing puts nobody in reach",
                b.transport.reachable.value
                    .any { it.nodeId == "carol" },
            )
            val before = b.link.sent.size
            b.transport.fastSend(cleartextReceipt("bob", "m1"), Peer("carol"))
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("so carol's ✓✓ stays off the air", before, b.link.sent.size)
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun aFarGatewaysAiringStillPutsItsBoardlessAuthorInReach() =
        runTest {
            // ADR 2026-09.wkbk rests on this: a far pocket's ✓✓ goes back over LoRa to a board-less author because
            // the far gateway aired that author's post, and hands it the last hop over its own link.
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()
            hearOffer(b, from = 1u, publisher = "alice") // a gateway, but not one bob is linked to
            runCurrent()

            a.transport.fastFanout(frame(FrameType.CHAT, "carol", body = "from the far pocket"))
            advanceTimeBy(4_000)
            runCurrent()

            assertTrue(
                "carol is reachable through the far gateway",
                b.transport.reachable.value
                    .any { it.nodeId == "carol" },
            )
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun linkingTheGatewayWithdrawsTheReachItsBoardVouchedFor() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()
            hearOffer(b, from = 1u, publisher = "alice")
            a.transport.fastFanout(frame(FrameType.CHAT, "carol", body = "heard while alice was far"))
            advanceTimeBy(4_000)
            runCurrent()
            assertTrue(
                b.transport.reachable.value
                    .any { it.nodeId == "carol" },
            )

            // Alice walks into bob's pocket: her board now airs what bob's own radios carry.
            b.transport.suppressDataPath(setOf("alice"))
            assertFalse(
                "the hearing her board vouched for is withdrawn at once, not after the 45-min linger",
                b.transport.reachable.value
                    .any { it.nodeId == "carol" },
            )

            // And she walks out again: the next fresh airing is reach once more.
            b.transport.suppressDataPath(emptySet())
            a.transport.fastFanout(frame(FrameType.CHAT, "carol", body = "alice is far again"))
            advanceTimeBy(4_000)
            runCurrent()
            assertTrue(
                b.transport.reachable.value
                    .any { it.nodeId == "carol" },
            )
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun aRadioLearnedToBeCoPocketWithdrawsItsEarlierHearings() =
        runTest {
            // The OFFER can land after the frames: a hearing taken from a radio of unknown owner counts (the safe
            // reading), and is withdrawn once that radio's OFFER names a node bob is linked to.
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()
            b.transport.suppressDataPath(setOf("alice"))
            a.transport.fastFanout(frame(FrameType.CHAT, "carol", body = "before any offer"))
            advanceTimeBy(4_000)
            runCurrent()
            assertTrue(
                b.transport.reachable.value
                    .any { it.nodeId == "carol" },
            )

            hearOffer(b, from = 1u, publisher = "alice")
            runCurrent()
            assertFalse(
                b.transport.reachable.value
                    .any { it.nodeId == "carol" },
            )
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun aCleartextTicksRetriesDedupOnWhatTheySayNotOnTheirSignature() =
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

            b.transport.fastSend(cleartextReceipt("bob", "m1"), Peer("alice"))
            b.transport.fastSend(cleartextReceipt("bob", "m1"), Peer("alice")) // AckSync's next attempt: new id, new sig
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("one packet per tick inside the window", before + 1, b.link.sent.size)

            b.transport.fastSend(cleartextReceipt("bob", "m2"), Peer("alice"))
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("a tick for another message is its own packet", before + 2, b.link.sent.size)

            advanceTimeBy(LoraMeshTransport.SIG_TTL_MS)
            runCurrent()
            val lapsed = b.link.sent.size
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "still here")) // keep alice in reach
            advanceTimeBy(4_000)
            runCurrent()
            b.transport.fastSend(cleartextReceipt("bob", "m1"), Peer("alice"))
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("past the window the retry rides again", lapsed + 1, b.link.sent.size)
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

    /**
     * A phone with no board pays nothing for the plane. The child joins the composite on `BuildConfig.LORA_PLANE`
     * alone, so the fan-out is reached on every install — and it used to encode and queue whatever it was handed,
     * which is how a lab Pixel that has never been near a board came to log the ADR 2026-09.mhs5 pad decision.
     */
    @Test
    fun withNoBoardBoundAFannedFrameIsNeitherEncodedNorQueued() =
        runTest {
            val air = FakeMeshtasticAir()
            val pace = LoraPacePolicy(minGapMs = 0)
            val a =
                rig(air, 1u, "alice", backgroundScope, config = MutableStateFlow<LoraConfig?>(null), pace = pace) {
                    testScheduler.currentTime
                }
            a.transport.start()
            runCurrent()

            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "north gate, ten minutes"))
            a.transport.fastFanout(profile("bob")) // the other half of what the lab device was logging
            advanceTimeBy(4_000)
            runCurrent()

            assertEquals("the codec never ran", 0L, a.metrics.lora().loraTranscoded)
            assertEquals("nothing was queued for a plane that cannot send", 0, pace.pending)
            assertTrue("and nothing reached a board", a.link.sent.isEmpty())
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
            assertEquals(1L, a.metrics.lora().loraNak)
            assertEquals("attributable, not just counted", mapOf("DUTY_CYCLE_LIMIT" to 1L), a.metrics.lora().loraNakByReason)
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

            assertTrue("the offer reached the air", a.metrics.lora().loraOfferSent > 0)
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
            assertEquals(1L, a.metrics.lora().loraProfileRefanSkipped)
            assertEquals("and it is not counted as an ordinary dedup", 0L, a.metrics.lora().loraSuppressed)

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
            assertEquals(3L, a.metrics.lora().loraSkippedLinked)
            assertEquals("the sig dedup slot was not burned", 0L, a.metrics.lora().loraSuppressed)

            // A sighting is not a link: the same DM to a merely-sighted bob rides.
            a.transport.suppressDataPath(emptySet())
            a.transport.onForeignReachable(setOf("bob"))
            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", body = "sealed"))
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue("a DM to a sighted-but-unlinked peer rides", a.link.sent.size > afterBeacon)
            assertEquals(1L, a.metrics.lora().loraDmSent)

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
     * The recipient gate's "or to us" half, before the transport knows who "us" is. [LoraMeshTransport.start]
     * resolves the self id on a coroutine of its own, and a DM a pocket-mate hands us over the link is re-fanned
     * on relay the moment it lands: if that comes first, the enqueuing gate reads it as addressed to a stranger.
     * The pacer waits for the id, so the send-time gate refuses it instead. #141: the lab's linked-DM scenario
     * under a chaos stall of that one launch, the board airing a DM and a ✓✓ that were both addressed to itself.
     */
    @Test
    fun aDmToUsQueuedBeforeTheSelfIdIsKnownNeverRides() =
        runTest {
            val air = FakeMeshtasticAir()
            val self = CompletableDeferred<String>()
            val a = rig(air, 1u, "alice", backgroundScope, selfId = { self.await() }) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            advanceTimeBy(1)
            runCurrent()

            // Before the id is known the reach gate lets every DM-form frame through (ADR 2026-10.a4pm: it cannot
            // tell ours from a stranger's), so the send-time re-ask is the one thing between this and the air.
            a.transport.longRangeFanout(frame(FrameType.CHAT, "bob", recipientId = "alice", body = "sealed"))
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("nothing leaves before the id is known", 0, a.link.sent.size)

            self.complete("alice")
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue("the beacon queued meanwhile still goes", a.link.sent.isNotEmpty())
            assertEquals("the DM addressed to us stayed off the air", 0L, a.metrics.lora().loraDmSent)
            assertEquals(mapOf(StaleAtSend.LINKED.name to 1L), a.metrics.lora().loraStaleAtSendByReason)
            a.transport.stop()
        }

    /**
     * The park above must not become a silence: the production id is read off the Keystore-backed identity,
     * which can refuse (ADR 2026-10.47rw), and a read that throws is retried rather than leaving the pacer
     * parked for the life of the process.
     */
    @Test
    fun aSelfIdReadThatThrowsIsRetriedAndThePlaneComesBack() =
        runTest {
            val air = FakeMeshtasticAir()
            var reads = 0
            val selfId: suspend () -> String = {
                reads++
                if (reads == 1) error("keystore busy") else "alice"
            }
            val a = rig(air, 1u, "alice", backgroundScope, selfId = selfId) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            advanceTimeBy(1)
            runCurrent()
            assertEquals("nothing leaves while the id is unread", 0, a.link.sent.size)

            advanceTimeBy(LoraMeshTransport.SELF_ID_RETRY_MIN_MS + 1)
            runCurrent()
            assertEquals(2, reads)
            assertTrue("the beacon goes once the retry lands", a.link.sent.isNotEmpty())
            a.transport.stop()
        }

    // --- the Internet cover (ADR 2026-09.y5f3) ---

    @Test
    fun aDmFormFrameToAPeerTheInternetPlaneCarriesIsKeptOffTheAir() =
        runTest {
            // The second cover beside ADR 054's link: a peer a connected spool recently heard from has a
            // reliable path that costs no air, and every DM-form frame to them stays off the board — the
            // field day spent a window on DM copies the spool had already delivered. Counted apart from the
            // link so the two reasons can be told apart.
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            advanceTimeBy(1)
            runCurrent()
            val afterBeacon = a.link.sent.size

            a.transport.coveredByInternet(setOf("bob"))
            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", body = "sealed"))
            a.transport.longRangeFanout(frame(FrameType.CHAT, "carol", recipientId = "bob", body = "sealed tick"))
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("nothing on the air for a spool-covered recipient", afterBeacon, a.link.sent.size)
            assertEquals(2L, a.metrics.lora().loraSkippedInternet)
            assertEquals("not counted as a link", 0L, a.metrics.lora().loraSkippedLinked)
            assertEquals(1, a.transport.status.value.internetCovered)

            // The cover lapses with the spool's evidence: the same DM rides again.
            a.transport.coveredByInternet(emptySet())
            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", body = "sealed"))
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue("uncovered, it rides", a.link.sent.size > afterBeacon)
            a.transport.stop()
        }

    @Test
    fun aRoomPostIsNeverKeptOffTheAirByInternetCover() =
        runTest {
            // The spool does not carry the room, so the cover has nothing to say about a broadcast.
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            advanceTimeBy(1)
            runCurrent()
            val afterBeacon = a.link.sent.size
            a.transport.coveredByInternet(setOf("bob", "carol"))
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "room post"))
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue("a room post still rides", a.link.sent.size > afterBeacon)
            assertEquals(0L, a.metrics.lora().loraSkippedInternet)
            a.transport.stop()
        }

    @Test
    fun aTargetedTickToASpoolPresentPeerIsKeptOffTheAir() =
        runTest {
            // The first gate fastSend has had since ADR 044: a cover, not a role. The peer demonstrably has a
            // path that costs nothing, and the tick is in the spool anyway.
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "ping")) // bob hears alice
            runCurrent()
            val bSentBefore = b.link.sent.size

            b.transport.coveredByInternet(setOf("alice"))
            b.transport.fastSend(frame(FrameType.RECEIPT, "bob"), Peer("alice"))
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("the spool carries her tick", bSentBefore, b.link.sent.size)
            assertEquals(1L, b.metrics.lora().loraSkippedInternet)

            b.transport.coveredByInternet(emptySet())
            b.transport.fastSend(frame(FrameType.RECEIPT, "bob"), Peer("alice"))
            advanceTimeBy(4_000)
            runCurrent()
            assertTrue("uncovered, the targeted tick rides", b.link.sent.size > bSentBefore)
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun aReofferIsSkippedForAPeerTheInternetPlaneCarries() =
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
            // The spool re-serves to bob for real, and for free.
            a.transport.coveredByInternet(setOf("bob"))
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            b.transport.start()
            advanceTimeBy(4_000)
            runCurrent()
            assertTrue("custody is not even asked", asked.isEmpty())
            assertEquals(0L, a.metrics.lora().loraReoffered)
            assertEquals(1L, a.metrics.lora().loraSkippedInternet)
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun aQueuedFrameIsAbandonedWhenItsRecipientAppearsOnTheInternetPlaneWhileItWaits() =
        runTest {
            // The same race ADR 054's LINKED reason closes, for the spool: a frame queued before the peer's
            // presence arrived must not be aired after it.
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            advanceTimeBy(1)
            runCurrent()

            hearFrom(a, "bob") // heard, so the reach gate lets the frame queue (ADR 2026-10.a4pm)
            runCurrent()
            a.link.free = 0
            runCurrent()
            val sentBefore = a.link.sent.size
            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", relay = false), FanoutHint.TICK)
            runCurrent()
            assertEquals("it is queued, not aired", sentBefore, a.link.sent.size)

            a.transport.coveredByInternet(setOf("bob"))
            a.link.updateHeadroom(16)
            advanceTimeBy(10_000)
            runCurrent()

            assertEquals("the spool carried it; the board must not repeat it", sentBefore, a.link.sent.size)
            val snap = a.metrics.snapshot()
            assertEquals(1L, snap.lora.loraStaleAtSend)
            assertEquals(mapOf(StaleAtSend.INTERNET.name to 1L), snap.lora.loraStaleAtSendByReason)
            a.transport.stop()
        }

    @Test
    fun internetCoverNeverMovesTheGatewayRole() =
        runTest {
            // A spool-present board-holder is not a co-pocket rival: the election reads links alone.
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            advanceTimeBy(1)
            runCurrent()
            val role = a.transport.status.value.role
            a.transport.coveredByInternet(setOf("bob", "carol"))
            runCurrent()
            assertEquals(role, a.transport.status.value.role)
            assertEquals(0, a.transport.status.value.pocketLinks)
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
            hearFrom(a, "bob") // heard, so the reach gate lets every frame here reach the queue
            runCurrent()
            // Hold the board's queue full so nothing leaves the pacer while the two frames meet.
            pace.onQueueStatus(0)

            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", body = "tick"), FanoutHint.TICK)
            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", body = "dm"))
            assertEquals("the DM evicted the tick", 1L, a.metrics.lora().loraDroppedQueue)
            assertEquals(1, pace.pending)

            a.transport.longRangeFanout(frame(FrameType.CHAT, "alice", recipientId = "bob", body = "tick 2"), FanoutHint.TICK)
            assertEquals("a tick behind a DM yields", 2L, a.metrics.lora().loraDroppedQueue)

            // The same bytes without the hint are content: within one class the oldest goes, so the newcomer stays.
            a.transport.longRangeFanout(frame(FrameType.CHAT, "carol", recipientId = "bob", body = "relayed dm-form"))
            assertEquals(3L, a.metrics.lora().loraDroppedQueue)
            assertEquals(1, pace.pending)
            a.transport.stop()
        }

    @Test
    fun anUnconfiguredPlaneParksItsLoopsInsteadOfSweepingAndGossiping() =
        runTest {
            var reads = 0
            val clock = {
                reads++
                if (reads > SPIN_CAP) throw SpinCap()
                testScheduler.currentTime
            }
            val scope =
                CoroutineScope(
                    StandardTestDispatcher(testScheduler) + SupervisorJob() + CoroutineExceptionHandler { _, _ -> },
                )
            val a =
                rig(
                    FakeMeshtasticAir(),
                    1u,
                    "alice",
                    scope,
                    config = MutableStateFlow<LoraConfig?>(null),
                    now = clock,
                )
            a.transport.start()
            runCurrent()

            // The state most installs are in: the child is in the composite because BuildConfig.LORA_PLANE is
            // on, and no board has ever been paired. Before the loops were gated this cost a sweep a minute
            // -- an election over an empty heard set -- plus a gossip wake, for the life of the service.
            val atRest = reads
            advanceTimeBy(60 * 60_000)
            runCurrent()
            assertEquals(
                "an hour with no board reads the clock not at all (${reads - atRest} reads)",
                atRest,
                reads,
            )
            a.transport.stop()
            scope.cancel()
        }

    @Test
    fun losingTheBoardDropsWhatItHeardRatherThanLeavingItToTheSweep() =
        runTest {
            val air = FakeMeshtasticAir()
            val cfg = MutableStateFlow<LoraConfig?>(LoraConfig("AA:2", 0))
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope, config = cfg) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()

            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "north gate in ten"))
            runCurrent()
            assertTrue(
                "bob heard alice through the board",
                b.transport.reachable.value
                    .any { it.nodeId == "alice" },
            )

            // Unpairing is the case that makes parking the sweep safe to do at all: recomputeReachable is the
            // only thing that ages lastHeardAt out, so a plane that idles here would strand alice in reach
            // forever. The route she was reachable *through* is gone, so the set goes with it.
            cfg.value = null
            runCurrent()
            assertEquals(
                "reach empties with the board, not a linger window later",
                emptySet<Peer>(),
                b.transport.reachable.value,
            )
            a.transport.stop()
            b.transport.stop()
        }

    private companion object {
        /** Far more clock reads than a loop that suspends between passes can make; a spin blows past it at once. */
        const val SPIN_CAP = 5_000

        /** A minute of idling costs a wake per [LoraMeshTransport.IDLE_TICK_MS] at worst, times a read or two. */
        const val IDLE_WAKES_PER_MINUTE = 200

        /**
         * Ten minutes of outage with a frame held: the old 1 s idle tick alone made 600 reads, while the other
         * loops (the 60 s linger sweep, a gossip wake) make a handful.
         */
        const val OUTAGE_READS_PER_TEN_MINUTES = 100
    }
}
