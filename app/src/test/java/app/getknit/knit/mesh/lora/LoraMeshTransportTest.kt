package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.InboundFrame
import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.MeshPost
import app.getknit.knit.mesh.Peer
import app.getknit.knit.mesh.PublicPostRefusal
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.bluetooth.BackoffConfig
import app.getknit.knit.mesh.link.FastFrameCodec
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.GroupInfo
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
import org.junit.Assert.assertNotNull
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

    /**
     * ADR 2026-10.ug4a: the ledger is no longer persisted — a restart starts the window empty, and the snapshot the last
     * process wrote carries only the limiters this build keeps (the auto-reply memory and the re-probe schedule).
     */
    @Test
    fun aRestartStartsTheWindowEmptyAndAirsAtOnce() =
        runTest {
            val air = FakeMeshtasticAir()
            val state = FakePlaneState()
            val firstPace = LoraPacePolicy(minGapMs = 0)
            val first = rig(air, 1u, "alice", backgroundScope, pace = firstPace, state = state) { testScheduler.currentTime }
            first.transport.start()
            runCurrent()
            first.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "north gate in ten"))
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue("the first process spent air", firstPace.airtime.usedMs(testScheduler.currentTime) > 0)
            first.transport.stop()
            runCurrent()
            assertNotNull("the snapshot was still written, for the limiters it keeps", state.snapshot)

            val pace = LoraPacePolicy(minGapMs = 0)
            val second = rig(air, 1u, "alice", backgroundScope, pace = pace, state = state) { testScheduler.currentTime }
            second.transport.start()
            runCurrent()
            assertEquals("the window starts empty", 0L, pace.airtime.snapshot(testScheduler.currentTime).usedMs)
            second.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "here now", sentAt = testScheduler.currentTime))
            runCurrent()
            assertEquals("a post typed after the restart airs at once", 1, second.link.sent.size)
            second.transport.stop()
        }

    @Test
    fun aSnapshotFromAheadOfTheWallClockIsRefusedRatherThanTrusted() =
        runTest {
            // A snapshot stamped in the future is a clock that moved backwards (a manual change, or an NTP
            // correction over a reboot). Every age in it would read as negative — i.e. never expiring — so the
            // limiters start clean instead.
            val air = FakeMeshtasticAir()
            val state = FakePlaneState()
            state.snapshot =
                LoraPlaneSnapshot(
                    savedAtWall = 60 * 60_000,
                    reprobes = listOf(ReprobeStamp("bob", dueAtWall = 60 * 60_000 + LoraReprobe.CAP_MS, gapMs = LoraReprobe.CAP_MS)),
                )
            val rig = rig(air, 1u, "alice", backgroundScope, state = state) { testScheduler.currentTime }
            rig.transport.start()
            runCurrent()
            assertTrue("no schedule from a snapshot ahead of the clock", rig.transport.reprobeSchedule().isEmpty())
            rig.transport.stop()
        }

    /**
     * ADR 2026-10.ug4a: an own room post written before this process started is a re-serve — a neighbour's custody or a
     * spool pull after a restart emptied the router's seen window, which `InboundPipeline.onDeliver` re-fans like any
     * first-seen frame — and never re-airs. One written since does.
     */
    @Test
    fun anOwnPostWrittenBeforeThisProcessStartedNeverReAirs() =
        runTest {
            advanceTimeBy(5 * 60_000L) // the last process posted, crashed, and this one started
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            val started = testScheduler.currentTime
            val suppressed = a.metrics.lora().loraSuppressed
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "posted just before the crash", sentAt = started - 60_000))
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue("fresh, ours, never fanned this process — and still not re-aired", a.link.sent.isEmpty())
            assertEquals(suppressed + 1, a.metrics.lora().loraSuppressed)

            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "typed now", sentAt = started))
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals("a post typed in this process airs", 1, a.link.sent.size)
            a.transport.stop()
        }

    @Test
    fun anOwnPostReServedPastTheRoutersWindowButStillFreshDoesNotAirAgain() =
        runTest {
            // #239: the router's seen window lapses at ten minutes, a room post stays fresh for fifteen. A neighbour's
            // custody re-serving our own post in that gap is re-fanned by `InboundPipeline.onDeliver`.
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            val post = frame(FrameType.CHAT, "alice", body = "north gate, ten minutes", sentAt = testScheduler.currentTime)
            a.transport.fastFanout(post)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals("the post airs once", 1, a.link.sent.size)

            advanceTimeBy(11 * 60_000L)
            runCurrent()
            val suppressed = a.metrics.lora().loraSuppressed
            a.transport.fastFanout(post)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals("fresh, already aired: not again", 1, a.link.sent.size)
            assertEquals(suppressed + 1, a.metrics.lora().loraSuppressed)
            a.transport.stop()
        }

    @Test
    fun anOwnPostRefusedAtSendHoldsNoDedupSlotAndAirsOnItsNextFanout() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            val post = frame(FrameType.CHAT, "alice", body = "north gate, ten minutes", sentAt = testScheduler.currentTime)
            // The board refuses the only packet, twice: each time the frame is abandoned unsent, the same path a send-time
            // re-ask takes. Twice, because the old `SeenSet.reopen` handed back only the first slot in each window (#239).
            repeat(2) { refusal ->
                a.link.nakNext = RoutingError.NO_CHANNEL // used up by one send
                a.transport.fastFanout(post)
                advanceTimeBy(5_000)
                runCurrent()
                assertTrue("refusal ${refusal + 1}: nothing reached the air", a.link.sent.isEmpty())
                advanceTimeBy(60_000)
            }
            runCurrent()
            a.transport.fastFanout(post)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals("never flew, so it is not marked: the next fan-out airs it", 1, a.link.sent.size)
            a.transport.stop()
        }

    // --- The shared-band brake (ADR 2026-10.ug4a) ---

    /** A provisioned board, its Knit channel on slot 1 — the shape on which the Meshtastic room has a primary to post on. */
    private fun TestScope.provisionedRig(
        air: FakeMeshtasticAir,
        pace: LoraPacePolicy = LoraPacePolicy(minGapMs = 0),
        radio: LoraRadioConfig =
            LoraRadioConfig(
                usePreset = true,
                modemPreset = ModemPreset.LONG_FAST,
                region = LoraRegion.US,
                hopLimit = 3,
                overrideDutyCycle = false,
            ),
    ): Rig {
        val r =
            rig(
                air,
                1u,
                "alice",
                backgroundScope,
                config = MutableStateFlow(LoraConfig("AA:1", 1)),
                pace = pace,
            ) { testScheduler.currentTime }
        r.transport.start()
        runCurrent()
        r.link.readyProvisioned(radio = radio)
        runCurrent()
        return r
    }

    private fun TestScope.reading(
        r: Rig,
        channelUtil: Float,
    ) {
        r.link.boardAir.value = BoardAir(channelUtil, airUtilTxPercent = 1.5f, atMs = testScheduler.currentTime)
        runCurrent()
    }

    @Test
    fun aBusyBandHoldsAKnitRoomPostWhileTheMeshtasticRoomPostsAndANewQuietReadingLetsItGo() =
        runTest {
            val air = FakeMeshtasticAir()
            val r = provisionedRig(air)
            reading(r, 40f)
            assertTrue("40 % brakes: the firmware's max_channel_util_percent, compared >=", r.transport.status.value.braked)

            r.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "north gate", sentAt = testScheduler.currentTime))
            assertNull(r.transport.postToPublicChannel("meet at the trailhead"))
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals("only the Meshtastic room's post went", listOf(MeshtasticProto.PORT_TEXT_MESSAGE), r.link.sentPortnums)
            assertEquals("the Knit post waits, queued", 1, r.transport.status.value.queued)
            assertEquals(1L, r.metrics.lora().loraBandBraked)
            assertEquals("a brake is not an airtime hold", 0L, r.metrics.lora().loraAirtimeHeld)

            advanceTimeBy(60_000)
            runCurrent()
            assertEquals("a minute on, the same busy band: still waiting, counted once", 1, r.link.sent.size)
            assertEquals(1L, r.metrics.lora().loraBandBraked)

            reading(r, 30f)
            assertFalse(r.transport.status.value.braked)
            assertEquals("the first quiet reading lets it go", MeshtasticProto.PORT_PRIVATE_APP, r.link.sentPortnums.last())
            assertEquals(2, r.link.sent.size)
            r.transport.stop()
        }

    @Test
    fun aStaleReadingDoesNotBrake() =
        runTest {
            val air = FakeMeshtasticAir()
            val r = provisionedRig(air)
            reading(r, 60f)
            // Past the reading's age, and past the linger sweep that republishes the status the row and the dump read.
            advanceTimeBy(LoraPacePolicy.BRAKE_FRESH_MS + LoraMeshTransport.LINGER_SWEEP_MS)
            runCurrent()
            assertFalse("a board that stopped reporting brakes nothing", r.transport.status.value.braked)
            r.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "north gate", sentAt = testScheduler.currentTime))
            runCurrent()
            assertEquals(1, r.link.sent.size)
            r.transport.stop()
        }

    @Test
    fun aBrakedPostTheBandHoldsPastTheReadingsAgeGoesWhenItGoesStale() =
        runTest {
            // The brake lifts on its own when the last reading ages out: the pacer sleeps to that, not for ever.
            val air = FakeMeshtasticAir()
            val r = provisionedRig(air)
            reading(r, 40f)
            r.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "north gate", sentAt = testScheduler.currentTime))
            advanceTimeBy(LoraPacePolicy.BRAKE_FRESH_MS - 1_000)
            runCurrent()
            assertTrue(r.link.sent.isEmpty())
            advanceTimeBy(2_000)
            runCurrent()
            assertEquals(1, r.link.sent.size)
            r.transport.stop()
        }

    @Test
    fun aDedicatedSlotIsNeverBraked() =
        runTest {
            // ADR 067 lifts the manners this is on a slot of Knit's own: any congestion there is Knit's.
            val air = FakeMeshtasticAir()
            val slot = LoraSlot.forRegion(LoraRegion.US, ModemPreset.LONG_FAST)!!
            val r =
                provisionedRig(
                    air,
                    pace = LoraPacePolicy(minGapMs = 0, airtime = LoraAirtime(dedicatedUnlocksDuty = true)),
                    radio =
                        LoraRadioConfig(
                            usePreset = true,
                            modemPreset = ModemPreset.LONG_FAST,
                            region = LoraRegion.US,
                            hopLimit = 3,
                            overrideDutyCycle = false,
                            channelNum = slot,
                        ),
                )
            assertEquals(
                true,
                r.transport.status.value.airtime
                    ?.dedicated,
            )
            reading(r, 90f)
            assertFalse(r.transport.status.value.braked)
            r.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "north gate", sentAt = testScheduler.currentTime))
            runCurrent()
            assertEquals(1, r.link.sent.size)
            r.transport.stop()
        }

    @Test
    fun losingTheBoardForgetsItsReading() =
        runTest {
            val air = FakeMeshtasticAir()
            val config = MutableStateFlow<LoraConfig?>(LoraConfig("AA:1", 0))
            val r = rig(air, 1u, "alice", backgroundScope, config = config) { testScheduler.currentTime }
            r.transport.start()
            runCurrent()
            reading(r, 50f)
            assertTrue(r.transport.status.value.braked)
            config.value = null
            runCurrent()
            assertFalse(r.transport.status.value.braked)
            assertNull(r.transport.status.value.bandReadingAgeMs)
            r.transport.stop()
        }

    /** A high-entropy body that will not deflate below the LoRa packet cap, so the frame truly fragments. */
    private fun incompressibleBody(chars: Int): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val rng = kotlin.random.Random(1234)
        return buildString(chars) { repeat(chars) { append(alphabet[rng.nextInt(alphabet.length)]) } }
    }

    private class Rig(
        val transport: LoraMeshTransport,
        val link: FakeMeshtasticLink,
        val metrics: MeshMetrics,
        val received: MutableList<InboundFrame>,
        /** The rig's clock — the wall clock too: a post's `sentAt` read off it is one typed in this process. */
        val now: () -> Long,
    )

    private fun rig(
        air: FakeMeshtasticAir,
        nodeNum: UInt,
        selfNode: String,
        scope: kotlinx.coroutines.CoroutineScope,
        config: kotlinx.coroutines.flow.Flow<LoraConfig?> = MutableStateFlow(LoraConfig("AA:$nodeNum", 0)),
        channelName: String = KnitChannel.NAME,
        pace: LoraPacePolicy = LoraPacePolicy(minGapMs = 0),
        firmware: String = "2.5.0",
        publicKey: String? = null,
        onPublicPost: suspend (MeshPost) -> Unit = {},
        onBoardBound: suspend (BoardBinding) -> Unit = {},
        state: LoraPlaneState = LoraPlaneState.None,
        selfId: suspend () -> String = { selfNode },
        signaturePolicy: SignaturePolicy? = null,
        knitAead: Boolean = false,
        now: () -> Long,
    ): Rig {
        val link =
            FakeMeshtasticLink(
                nodeNum,
                air,
                channelName,
                firmware,
                publicKey,
                scope = scope,
                signaturePolicy = signaturePolicy,
                knitAead = knitAead,
            )
        val metrics = MeshMetrics()
        val transport =
            LoraMeshTransport(
                selfId = selfId,
                link = link,
                config = config,
                onPublicPost = onPublicPost,
                onBoardBound = onBoardBound,
                scope = scope,
                metrics = metrics,
                state = state,
                clock = now,
                wallClock = now,
                pace = pace,
            )
        val received = mutableListOf<InboundFrame>()
        scope.launch { transport.inbound.collect { received += it } }
        return Rig(transport, link, metrics, received, now)
    }

    @Test
    fun readyMakesTheTransportHealthyAndAirsNothingOfItsOwnAccord() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            advanceTimeBy(1)
            runCurrent()
            assertEquals(TransportHealth.Healthy, a.transport.health.value)
            assertEquals(1L, a.metrics.lora().loraSessionUps)
            // No profile beacon on session up any more (ADR 2026-10.rp3s): an idle board airs nothing.
            assertTrue(a.link.sent.isEmpty())
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
        signaturePolicy: SignaturePolicy? = null,
        primaryAead: Boolean = false,
        knitAead: Boolean = false,
    ): Rig {
        val r =
            rig(
                air,
                1u,
                "alice",
                backgroundScope,
                config = MutableStateFlow(LoraConfig("AA:1", 1, room = room)),
                firmware = firmware,
                signaturePolicy = signaturePolicy,
                knitAead = knitAead,
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
        r.link.readyProvisioned(knitIndex = 1, primaryName = primaryName, primaryPsk = primaryPsk, radio = radio, primaryAead = primaryAead)
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
            // What the profile advertises so a contact can line a heard post up with this phone. A pre-2.8 board (the
            // fake's default) publishes no key, so none rides beside the number.
            val air = FakeMeshtasticAir()
            val bound = mutableListOf<BoardBinding>()
            val r = rig(air, 7u, "alice", backgroundScope, onBoardBound = { bound += it }) { testScheduler.currentTime }
            r.transport.start()
            runCurrent()

            assertEquals(listOf(BoardBinding(7u, key = null)), bound)
            r.transport.stop()
        }

    /** The lab board `!64761b18`'s key: its CRC32 is that node number, the binding 2.8 makes at keygen. */
    private val labBoardKey = "oR62IJmFUE0Tgcw0GcypU5ZqUFCQllVBy2snB/BKQA4="
    private val labBoardNode = 0x64761b18u

    private fun TestScope.boundKeyOf(
        node: UInt,
        firmware: String,
        publicKey: String?,
        signaturePolicy: SignaturePolicy? = null,
    ): BoardBinding {
        val bound = mutableListOf<BoardBinding>()
        val r =
            rig(
                FakeMeshtasticAir(),
                node,
                "alice",
                backgroundScope,
                firmware = firmware,
                publicKey = publicKey,
                onBoardBound = { bound += it },
                signaturePolicy = signaturePolicy,
            ) { testScheduler.currentTime }
        r.transport.start()
        runCurrent()
        r.transport.stop()
        return bound.single()
    }

    @Test
    fun aBoardWhoseNumberIsItsKeysCrcReportsItsKeyWhetherOrNotItSigns() =
        runTest {
            // #243: the key is what lets a contact's board PKI-encrypt a compact DM to ours (ADR 2026-10.aajj), so it rides
            // on the CRC binding, not on signing. A 2.8.2 board under COMPATIBLE signs nothing and still publishes it.
            assertEquals(
                BoardBinding(labBoardNode, labBoardKey),
                boundKeyOf(labBoardNode, "2.8.2.34cb8d5", labBoardKey, SignaturePolicy.COMPATIBLE),
            )
            assertEquals(BoardBinding(labBoardNode, labBoardKey), boundKeyOf(labBoardNode, "2.8.1.8e6a88d", labBoardKey))
        }

    @Test
    fun aBoardWhoseNumberIsNotItsKeysCrcReportsNoKey() =
        runTest {
            // A MAC-derived number (pre-2.8, or a 2.8 board before its keygen) is not bound to the key, whatever it signs.
            assertEquals(BoardBinding(7u, null), boundKeyOf(7u, "2.8.1.8e6a88d", labBoardKey))
            assertEquals(BoardBinding(7u, null), boundKeyOf(7u, "2.8.2.34cb8d5", labBoardKey, SignaturePolicy.BALANCED))
            assertEquals(BoardBinding(8u, null), boundKeyOf(8u, "2.5.0", labBoardKey))
            // A key that is not 32 bytes is no key, even when its CRC is the number.
            val short = "oR62IJmFUE0Tgcw0GcypU5ZqUFCQllVBy2snB/BKQA=="
            assertEquals(BoardBinding(316_254_257u, null), boundKeyOf(316_254_257u, "2.8.2.34cb8d5", short, SignaturePolicy.COMPATIBLE))
            assertEquals("no key at all", BoardBinding(labBoardNode, null), boundKeyOf(labBoardNode, "2.8.2.34cb8d5", null))
        }

    @Test
    fun aCompatible282BoardSignsNothingSoAKnitPostIsNeitherPaddedNorBookedSigned() =
        runTest {
            // #243: `has_xeddsa` is set on every XEdDSA build, so it used to read as signing; the policy says it does not.
            val air = FakeMeshtasticAir()
            val airtime = LoraAirtime()
            val a =
                rig(
                    air,
                    1u,
                    "alice",
                    backgroundScope,
                    firmware = "2.8.2.34cb8d5",
                    signaturePolicy = SignaturePolicy.COMPATIBLE,
                    pace = LoraPacePolicy(minGapMs = 0, airtime = airtime),
                ) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            val snapshot = checkNotNull(a.transport.status.value.airtime)
            assertFalse("no signature charged on the Knit slot", snapshot.signing)
            assertEquals("and the room composer keeps the client cap", LoraAirtime.UNSIGNED, snapshot.roomSignedUpTo)
            val usedBefore = airtime.usedMs(testScheduler.currentTime)
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "north gate, ten minutes. bring the long cable."))
            runCurrent()
            val sent = a.link.sent.last()
            assertTrue("under the cliff and left there", sent.size <= MeshtasticProto.MAX_SIGNED_PAYLOAD)
            assertEquals(0L, a.metrics.lora().loraPadded)
            assertEquals(
                "booked at the unsigned price",
                airtime.timeOnAirMs(sent.size, LoraAirtime.UNSIGNED),
                airtime.usedMs(testScheduler.currentTime) - usedBefore,
            )
            a.transport.stop()
        }

    @Test
    fun aKnitBroadcastIsNeverWrittenToAKnitSlotSwitchedToAead() =
        runTest {
            // #243: AEAD changes the channel hash (`Channels.cpp:53`), so no other Knit board decodes what it airs.
            val air = FakeMeshtasticAir()
            val logs = mutableListOf<String>()
            val link = FakeMeshtasticLink(1u, air, KnitChannel.NAME, "2.8.2.34cb8d5", knitAead = true)
            val transport =
                LoraMeshTransport(
                    selfId = { "alice" },
                    link = link,
                    config = MutableStateFlow(LoraConfig("AA:1", 0)),
                    scope = backgroundScope,
                    metrics = MeshMetrics(),
                    clock = { testScheduler.currentTime },
                    wallClock = { testScheduler.currentTime },
                    pace = LoraPacePolicy(minGapMs = 0),
                    log = { logs += it },
                )
            transport.start()
            runCurrent()
            transport.fastFanout(frame(FrameType.CHAT, "alice", body = "hello"))
            runCurrent()
            assertTrue("nothing aired", link.sent.isEmpty())
            assertTrue(logs.any { it.startsWith("lora send skipped: slot 0 is AEAD") })
            transport.stop()
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
    fun aCompatible282BoardKeepsTheClientCap() =
        runTest {
            // #243: a 2.8.2 board under COMPATIBLE reports `has_xeddsa` and signs nothing, so no post is held to 166.
            val r =
                bridgeRig(FakeMeshtasticAir(), mutableListOf(), firmware = "2.8.2.34cb8d5", signaturePolicy = SignaturePolicy.COMPATIBLE)

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
    fun a281BoardWhoseSlotZeroIsAeadTrimsAPostToTheCliffItCanStillSendSigned() =
        runTest {
            // 2.8.1 signs on an AEAD channel and then refuses what the 12-byte tag pushes over (`Router.cpp:1377-1379`):
            // only a post of 154 B or less leaves signed, and one between 155 and 166 would never leave at all.
            val r = bridgeRig(FakeMeshtasticAir(), mutableListOf(), firmware = "2.8.1.8e6a88d", primaryAead = true)
            assertEquals(
                154,
                r.transport.status.value.airtime
                    ?.roomSignedUpTo,
            )

            assertNull(r.transport.postToPublicChannel("x".repeat(PublicPostPolicy.MAX_ON_AIR_BYTES)))
            runCurrent()

            assertEquals(
                154,
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
    fun theAutoReplyStillAnswersOnABoardWhoseKnitSlotIsAead() =
        runTest {
            // The reply goes on slot 0 as a PKI unicast; the Knit slot's AEAD never touches it, and the board is still set
            // up for Knit (#243: the AEAD gate is the bound slot's broadcasts', never the setup check).
            val r = bridgeRig(FakeMeshtasticAir(), mutableListOf(), knitAead = true)

            r.link.deliverPublicText(from = 0xdeadbeefu, body = "hello? anyone there?", id = 11u, to = 1u)
            runCurrent()

            assertEquals(1L, r.metrics.meshtastic().autoReplySent)
            assertTrue(r.link.sent.any { it.decodeToString() == DmAutoReplyPolicy.TEXT })
            r.transport.stop()
        }

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

    /**
     * The auto-reply is a PKI unicast too, and our board refuses it with `39` when it holds no key for the sender. That
     * refusal never teaches the board anybody's key: no profile stands behind a Meshtastic sender (ADR 2026-10.aajj).
     */
    @Test
    fun anAutoReplyRefusedForAMissingKeyNeverWritesAContact() =
        runTest {
            val air = FakeMeshtasticAir()
            val r = bridgeRig(air, mutableListOf())
            r.link.nakNext = RoutingError.PKI_SEND_FAIL_PUBLIC_KEY
            r.link.deliverPublicText(from = 0xdeadbeefu, body = "hello?", id = 11u, to = 1u)
            runCurrent()
            assertTrue("no add_contact", r.link.contactsWritten.isEmpty())
            assertEquals(0L, r.metrics.lora().loraDirectNoKey)
            assertEquals(1L, r.metrics.meshtastic().autoReplyRefusedByReason[AutoReplyRefusal.NAK.name])
            // The queueStatus order too: a `Rejected(res = 39)` on the reply never reaches the compact DM's path.
            advanceTimeBy(DmAutoReplyPolicy.FLOOR_MS)
            r.link.rejectNext = RoutingError.PKI_SEND_FAIL_PUBLIC_KEY.code
            r.link.deliverPublicText(from = 0xfeedbeefu, body = "hello?", id = 12u, to = 1u)
            runCurrent()
            assertTrue(r.link.contactsWritten.isEmpty())
            assertEquals("its late self-NAK is the reply's, never a compact DM's missing key", 0L, r.metrics.lora().loraDirectNoKey)
            assertEquals(
                "both refused replies are ordinary NAKs",
                2L,
                r.metrics.lora().loraNakByReason[RoutingError.PKI_SEND_FAIL_PUBLIC_KEY.name],
            )
            r.transport.stop()
        }

    /**
     * A Meshtastic room post the session ended under is requeued, not refused (#307): it airs after the reconnect and is
     * counted sent once, and the refusal counter never moves for it.
     */
    @Test
    fun aRoomPostCaughtByABoardDropIsCountedSentOnceAndNeverRefused() =
        runTest {
            val air = FakeMeshtasticAir()
            val r = bridgeRig(air, mutableListOf())
            val up = r.link.state.value
            r.link.sessionEndsNext = true

            assertNull(r.transport.postToPublicChannel("meet at the trailhead"))
            runCurrent()
            // The barrier: the scripted drop runs inside the send, so a link out of Ready says the send was made.
            assertFalse("the session ended under the send", r.link.state.value is LinkState.Ready)
            assertTrue("nothing reached the board", r.link.sent.isEmpty())
            assertEquals(0L, r.metrics.meshtastic().publicPostSent)

            r.link.resume(up)
            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(1, r.link.sent.count { it.decodeToString() == "meet at the trailhead" })
            assertEquals(1L, r.metrics.meshtastic().publicPostSent)
            assertEquals("a requeue is not a refusal", emptyMap<String, Long>(), r.metrics.meshtastic().publicPostRefusedByReason)
            r.transport.stop()
        }

    /** The auto-reply's half of #307: requeued at a board drop, sent once after the reconnect, never counted refused. */
    @Test
    fun anAutoReplyCaughtByABoardDropIsCountedSentOnceAndNeverRefused() =
        runTest {
            val air = FakeMeshtasticAir()
            val r = bridgeRig(air, mutableListOf())
            val up = r.link.state.value
            r.link.sessionEndsNext = true

            r.link.deliverPublicText(from = 0xdeadbeefu, body = "hello? anyone there?", id = 11u, to = 1u)
            runCurrent()
            assertFalse("the session ended under the reply", r.link.state.value is LinkState.Ready)
            assertTrue("nothing reached the board", r.link.sent.isEmpty())
            assertEquals(0L, r.metrics.meshtastic().autoReplySent)

            r.link.resume(up)
            advanceTimeBy(5_000)
            runCurrent()

            assertEquals(1, r.link.sent.count { it.decodeToString() == DmAutoReplyPolicy.TEXT })
            assertEquals(1L, r.metrics.meshtastic().autoReplySent)
            assertEquals("a requeue is not a refusal", emptyMap<String, Long>(), r.metrics.meshtastic().autoReplyRefusedByReason)
            r.transport.stop()
        }

    /** A Meshtastic-room post our board refuses with 39 is the room's refusal alone: no contact, no keyless mark. */
    @Test
    fun aRoomPostRefusedWithA39NeverWritesAContact() =
        runTest {
            val air = FakeMeshtasticAir()
            val r = provisionedRig(air)
            r.link.rejectNext = RoutingError.PKI_SEND_FAIL_PUBLIC_KEY.code
            assertNull(r.transport.postToPublicChannel("hello room"))
            advanceTimeBy(5_000)
            runCurrent()
            assertTrue("it was written and refused", r.link.rejectNext == null)
            assertTrue(r.link.contactsWritten.isEmpty())
            assertEquals(0L, r.metrics.lora().loraDirectNoKey)
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
    fun aDmTheWindowCannotCarryIsRefusedNotQueuedAndTheSenderIsStillOwedTheirAnswer() =
        runTest {
            // Same rule as a post: a reply the window will not carry is counted now rather than sitting in the queue
            // looking sent. And since ADR 2026-10.ug4a the air is asked before the sender is stamped: the refusal uses up
            // nobody's one answer, so their next message, once the window has air, is answered.
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
            // Spend the window to its half before the DM arrives — the ceiling the auto-reply answers to.
            assertTrue(airtime.publicCeilingMs() > 0)
            while (airtime.admits(Ceiling.HALF, listOf(150), testScheduler.currentTime)) {
                airtime.record(150, testScheduler.currentTime)
            }

            r.link.deliverPublicText(from = 0xdeadbeefu, body = "hi", id = 11u, to = 1u)
            runCurrent()

            assertTrue(r.link.sentPortnums.none { it == MeshtasticProto.PORT_TEXT_MESSAGE })
            assertEquals(1L, r.metrics.meshtastic().autoReplyRefusedByReason[DmAutoReplyPolicy.Refusal.NO_AIR.name])

            // The window rolls; the same sender writes again and is answered — once, priced unsigned (a PKI unicast).
            advanceTimeBy(LoraAirtime.WINDOW_MS)
            runCurrent()
            r.link.deliverPublicText(from = 0xdeadbeefu, body = "hello?", id = 12u, to = 1u)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(1L, r.metrics.meshtastic().autoReplySent)
            assertEquals(
                "unsigned, with the PKI unicast's 12 B the firmware adds",
                airtime.timeOnAirMs(
                    DmAutoReplyPolicy.TEXT.encodeToByteArray().size + MeshtasticProto.PKC_OVERHEAD_BYTES,
                    LoraAirtime.UNSIGNED,
                ),
                airtime.usedMs(testScheduler.currentTime),
            )
            r.transport.stop()
        }

    /**
     * ADR 2026-10.ug4a: the sender is stamped at receipt, on the packet collector, which does not wait for the restore —
     * so until the once-a-day memory is back the reply is refused, unstamped, and the board's replay of the same DM is
     * judged against the memory.
     */
    @Test
    fun aDmBeforeTheMemoryIsBackIsNotAnsweredAndItsReplayIsJudgedAgainstTheMemory() =
        runTest {
            val air = FakeMeshtasticAir()
            val load = CompletableDeferred<LoraPlaneSnapshot?>()
            val state =
                object : LoraPlaneState {
                    override suspend fun load(): LoraPlaneSnapshot? = load.await()

                    override suspend fun save(snapshot: LoraPlaneSnapshot) = Unit
                }
            val r = bridgeRig(air, mutableListOf(), state = state)
            r.link.deliverPublicText(from = 0xdeadbeefu, body = "hi", id = 11u, to = 1u)
            runCurrent()
            assertEquals(0L, r.metrics.meshtastic().autoReplySent)
            assertEquals(1L, r.metrics.meshtastic().autoReplyRefusedByReason[AutoReplyRefusal.NOT_READY.name])

            // The last process answered this sender an hour ago.
            load.complete(
                LoraPlaneSnapshot(
                    savedAtWall = testScheduler.currentTime,
                    autoReplied = listOf(SeenStamp(DmAutoReplyPolicy.senderKey(0xdeadbeefu), testScheduler.currentTime - 60 * 60_000L)),
                ),
            )
            runCurrent()
            r.link.deliverPublicText(from = 0xdeadbeefu, body = "hi", id = 11u, to = 1u) // the same sender again
            r.link.deliverPublicText(from = 0x0badf00du, body = "hello", id = 12u, to = 1u) // somebody new
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals(
                1L,
                r.metrics.meshtastic().autoReplyRefusedByReason[DmAutoReplyPolicy.Refusal.REPLIED_RECENTLY.name],
            )
            assertEquals(
                "the new sender is answered at once — which also proves the pre-restore DM left the 30 s floor alone",
                1L,
                r.metrics.meshtastic().autoReplySent,
            )
            r.transport.stop()
        }

    /**
     * The sibling: the restored memory does not name this sender, so whether the pre-restore refusal stamped them is read
     * on their own key. It did not — their next DM after the restore is answered (ADR 2026-10.ug4a).
     */
    @Test
    fun aDmRefusedBeforeTheMemoryIsBackLeavesItsSenderUnstamped() =
        runTest {
            val air = FakeMeshtasticAir()
            val load = CompletableDeferred<LoraPlaneSnapshot?>()
            val state =
                object : LoraPlaneState {
                    override suspend fun load(): LoraPlaneSnapshot? = load.await()

                    override suspend fun save(snapshot: LoraPlaneSnapshot) = Unit
                }
            val r = bridgeRig(air, mutableListOf(), state = state)
            r.link.deliverPublicText(from = 0xdeadbeefu, body = "hi", id = 11u, to = 1u)
            runCurrent()
            assertEquals(1L, r.metrics.meshtastic().autoReplyRefusedByReason[AutoReplyRefusal.NOT_READY.name])

            load.complete(
                LoraPlaneSnapshot(
                    savedAtWall = testScheduler.currentTime,
                    autoReplied = listOf(SeenStamp(DmAutoReplyPolicy.senderKey(0x0badf00du), testScheduler.currentTime - 60 * 60_000L)),
                ),
            )
            runCurrent()
            r.link.deliverPublicText(from = 0xdeadbeefu, body = "still there?", id = 12u, to = 1u)
            advanceTimeBy(1_000)
            runCurrent()
            assertEquals("the sender's next DM is answered", 1L, r.metrics.meshtastic().autoReplySent)
            assertNull(r.metrics.meshtastic().autoReplyRefusedByReason[DmAutoReplyPolicy.Refusal.REPLIED_RECENTLY.name])
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

    /** A real session over [ch], on a board that queues every packet, for a test the fake link has no actor to stand behind. */
    private fun TestScope.realSession(ch: FakeGattChannel): MeshtasticSession {
        val nonce = 0x11u
        ch.onWrite = { bytes ->
            if (BoardBytes.isWantConfig(bytes)) {
                ch.enqueueRead(BoardBytes.myInfo(1u, "heltec-v4"))
                ch.enqueueRead(BoardBytes.channel(0, KnitChannel.NAME, 1))
                ch.enqueueRead(BoardBytes.configComplete(nonce))
            } else if (BoardBytes.isPacket(bytes)) {
                ch.enqueueRead(BoardBytes.queueStatus(free = 15, maxlen = 16, meshPacketId = BoardBytes.packetId(bytes)))
            }
        }
        return MeshtasticSession(
            dialer = FakeGattDialer(ch),
            scope = backgroundScope,
            backoff = BackoffConfig(baseMs = 5_000, maxMs = 180_000, jitterFraction = 0.0),
            now = { testScheduler.currentTime },
            rand = { 0.5 },
            nonce = { nonce },
            ids = PacketIdSource(1000L),
        )
    }

    /**
     * The real session under the transport. A notify holds the actor in a read while the pacer posts, then that read
     * fails and the session ends with the frame still in its inbox.
     */
    @Test(timeout = 60_000) // the order this guards breaks as a spin on the test's one thread, which runTest cannot time out
    fun aFrameBehindTheActorWhenTheSessionEndsAirsOnceAfterTheReconnect() =
        runTest {
            val now = { testScheduler.currentTime }
            val ch = FakeGattChannel()
            val session = realSession(ch)
            val metrics = MeshMetrics()
            val pace = LoraPacePolicy(minGapMs = 0)
            val transport =
                LoraMeshTransport(
                    selfId = { "alice" },
                    link = session,
                    config = MutableStateFlow(LoraConfig("AA", 0)),
                    onPublicPost = {},
                    onBoardBound = {},
                    scope = backgroundScope,
                    metrics = metrics,
                    state = LoraPlaneState.None,
                    clock = now,
                    wallClock = now,
                    pace = pace,
                )
            transport.start()
            runCurrent()
            assertTrue(session.state.value is LinkState.Ready)

            val gate = CompletableDeferred<GattResult<ByteArray>>()
            ch.readGate = gate
            ch.notify()
            runCurrent()
            transport.fastFanout(frame(FrameType.CHAT, "alice", body = "north gate in ten", sentAt = testScheduler.currentTime))
            runCurrent()
            val packets = { ch.writes.count { BoardBytes.isPacket(it) } }
            assertEquals("behind the actor, not yet written", 0, packets())
            assertEquals("the pacer holds it, posted to the session", 0, pace.pending)

            gate.complete(GattResult.Closed)
            runCurrent()
            assertTrue(session.state.value is LinkState.Disconnected)
            assertEquals(0, packets())
            assertEquals("refused and back on the queue, not held for the next session", 1, pace.pending)
            assertEquals(0L, pace.airtime.snapshot(testScheduler.currentTime).usedMs)

            advanceTimeBy(5_001) // the base backoff
            runCurrent()
            assertTrue(session.state.value is LinkState.Ready)
            assertEquals("it airs after the reconnect", 1, packets())
            assertEquals(1L, metrics.lora().loraSent)
            val booked = pace.airtime.snapshot(testScheduler.currentTime).usedMs
            assertTrue("booked", booked > 0L)

            advanceTimeBy(60_000)
            runCurrent()
            assertEquals("once", 1, packets())
            assertEquals("and booked once", booked, pace.airtime.snapshot(testScheduler.currentTime).usedMs)
            transport.stop()
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
                rig(air, 1u, "alice", backgroundScope, config = MutableStateFlow(LoraConfig("AA:1", 0))) {
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
    fun aStaleChatIsNotFannedButAFreshOneIs() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            advanceTimeBy(20 * 60_000) // 20 min into the session
            val baseline = a.link.sent.size

            // A custody re-serve of our own: a room post stamped 20 min ago re-enters the pipeline and is re-fanned.
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "old room post", sentAt = 0L))
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("stale chat never rides a live plane", baseline, a.link.sent.size)
            assertEquals(1L, a.metrics.lora().loraSuppressed)

            // A profile, however fresh, never rides any more (ADR 2026-10.rp3s).
            a.transport.fastFanout(frame(FrameType.PROFILE, "alice", body = "x".repeat(20), sentAt = testScheduler.currentTime))
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals(baseline, a.link.sent.size)

            val now = testScheduler.currentTime
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "fresh post", sentAt = now))
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("a fresh one rides", baseline + 1, a.link.sent.size)
            a.transport.stop()
        }

    private fun idOf(wire: WireEnvelope): String = WireCodec.decodeEnvelope(wire.signed)!!.id

    // --- The dedup window, then the transport's lifecycle and status. ---

    @Test
    fun aVerbatimResendIsSuppressedForAsLongAsThePostCouldAir() =
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
            assertEquals("suppressed at once", afterFirst, a.link.sent.size)

            // Not when a ten-minute window lapses (#239): until the post's own freshness ends, after which the fan-out
            // refuses it as stale anyway.
            advanceTimeBy(LoraFramePolicy.FRESH_MS - 8_000)
            a.transport.fastFanout(wire)
            advanceTimeBy(4_000)
            runCurrent()
            assertEquals("still fresh, still suppressed", afterFirst, a.link.sent.size)
            a.transport.stop()
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

    /**
     * [wire] handed to [to] off the air from board [from], as an older build's gateway still airs frames it did not
     * write — what no build since ADR 2026-10.rp3s puts on the air, and every build still reads.
     */
    private fun deliverFrame(
        to: Rig,
        wire: WireEnvelope,
        from: UInt = 9u,
        snr: Float? = 6.5f,
        rssi: Int? = -85,
    ) = LoraFrameCodec.encodeBest(wire, fragId = sigCounter and 0xFFFF)!!.parts.forEach {
        to.link.deliver(from = from, channelIndex = 0, portnum = MeshtasticProto.PORT_PRIVATE_APP, payload = it, rxSnr = snr, rxRssi = rssi)
    }

    private fun profileFrame(sender: String): WireEnvelope = frame(FrameType.PROFILE, sender, body = "x".repeat(20))

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
            // design and must keep counting. No build since ADR 2026-10.rp3s airs one, but an older build's gateway
            // still does, so the board hands it in as it always did.
            advanceTimeBy(12 * 60 * 60_000L)
            runCurrent()
            deliverFrame(b, profileFrame("carol"), from = 1u)
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
            deliverFrame(b, profileFrame("dave"), from = 1u)
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
                airtime.record(200, at)
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
            // Cancelled whatever the outcome: the scope is not the test's, and a plane left running in it would have
            // runTest's final advanceUntilIdle chase its sweep's timer for ever.
            try {
                pacerStaysParked(a, pace) { reads }
            } finally {
                a.transport.stop()
                scope.cancel()
            }
        }

    private fun TestScope.pacerStaysParked(
        a: Rig,
        pace: LoraPacePolicy,
        reads: () -> Int,
    ) {
        a.transport.start()
        runCurrent()

        a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "north gate in ten", sentAt = a.now()))
        // Past the inter-packet gap, so nothing but the spent budget is holding the frame back — this is
        // the window in which the old code's due time fell into the past.
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals("the frame is queued, not sent — the budget is spent", 1, pace.pending)

        val atRest = reads()
        advanceTimeBy(60_000)
        runCurrent()
        assertTrue(
            "the pacer stayed parked over a quiet minute (${reads() - atRest} clock reads; a spin blows past $SPIN_CAP)",
            reads() - atRest < IDLE_WAKES_PER_MINUTE,
        )
        assertTrue("the clock cap was never hit", reads() <= SPIN_CAP)
    }

    @Test
    fun nothingAirsBeforeTheSelfIdIsKnownAndOnlyOurOwnFramesAirAfter() =
        runTest {
            val air = FakeMeshtasticAir()
            val self = CompletableDeferred<String>()
            val a = rig(air, 1u, "alice", backgroundScope, selfId = { self.await() }) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            advanceTimeBy(1)
            runCurrent()

            // Before the id is known the admit rule cannot be asked (ADR 2026-10.rp3s), so every frame of a kind the
            // plane carries is queued with its author, and the send-time re-ask is what sorts ours from the rest. A
            // post typed during a Keystore backoff must still ride; nobody else's may.
            val now = testScheduler.currentTime
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "ours, typed during the backoff", sentAt = now))
            a.transport.fastFanout(frame(FrameType.CHAT, "bob", body = "a pocket-mate's", sentAt = now))
            advanceTimeBy(10_000)
            runCurrent()
            assertEquals("nothing leaves before the id is known", 0, a.link.sent.size)

            self.complete("alice")
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals("our own post went, and nothing else", 1, a.link.sent.size)
            assertEquals(mapOf(StaleAtSend.NOT_OWN.name to 1L), a.metrics.lora().loraStaleAtSendByReason)
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
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "typed during the backoff", sentAt = testScheduler.currentTime))
            advanceTimeBy(1)
            runCurrent()
            assertEquals("nothing leaves while the id is unread", 0, a.link.sent.size)

            advanceTimeBy(LoraMeshTransport.SELF_ID_RETRY_MIN_MS + 1)
            runCurrent()
            assertEquals(2, reads)
            assertTrue("our own post goes once the retry lands", a.link.sent.isNotEmpty())
            a.transport.stop()
        }

    // --- the Internet cover (ADR 2026-09.y5f3) ---

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
    fun anUnconfiguredPlaneParksItsLoopsInsteadOfSweeping() =
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
            // on, and no board has ever been paired. Before the loops were gated this cost a sweep a minute for the
            // life of the service.
            try {
                val atRest = reads
                advanceTimeBy(60 * 60_000)
                runCurrent()
                assertEquals(
                    "an hour with no board reads the clock not at all (${reads - atRest} reads)",
                    atRest,
                    reads,
                )
            } finally {
                a.transport.stop()
                scope.cancel()
            }
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

    // --- ADR 2026-10.rp3s: the board airs only its own user's frames. ---

    @Test
    fun aRelayedRoomPostNeverAirs() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            a.transport.fastFanout(frame(FrameType.CHAT, "bob", body = "a pocket-mate's post", sentAt = testScheduler.currentTime))
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue("the composite re-fans every relay, and this board airs none of them", a.link.sent.isEmpty())
            a.transport.stop()
        }

    @Test
    fun ourOwnOnePacketRoomPostAirsAndNothingElseDoes() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            val b = rig(air, 2u, "bob", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            b.transport.start()
            runCurrent()
            advanceTimeBy(60_000)
            runCurrent()
            assertTrue("no beacon, no OFFER: an idle board airs nothing", a.link.sent.isEmpty())

            val post = frame(FrameType.CHAT, "alice", body = "north gate in ten", sentAt = testScheduler.currentTime)
            a.transport.fastFanout(post)
            advanceTimeBy(5_000)
            runCurrent()
            assertEquals(1, a.link.sent.size)
            assertTrue(b.received.any { it.envelope.id == idOf(post) })
            a.transport.stop()
            b.transport.stop()
        }

    @Test
    fun ourOwnTwoPacketRoomPostDoesNotAirAndIsCountedApart() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = incompressibleBody(300), sentAt = testScheduler.currentTime))
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue("a room post rides in one packet or not at all", a.link.sent.isEmpty())
            assertEquals(1L, a.metrics.lora().loraRoomTooLong)
            assertEquals("not past the plane's three packets, so not too big", 0L, a.metrics.lora().loraTooBig)
            a.transport.stop()
        }

    @Test
    fun aRoomReactionAndAProfileOursOrRelayedNeverAir() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            val now = testScheduler.currentTime
            a.transport.fastFanout(frame(FrameType.REACTION, "alice", sentAt = now))
            a.transport.fastFanout(profileFrame("alice"))
            a.transport.fastFanout(profileFrame("bob"))
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue(a.link.sent.isEmpty())
            a.transport.stop()
        }

    @Test
    fun fastSendRefusesEverything() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            hearFrom(a, "bob", sentAt = testScheduler.currentTime)
            runCurrent()
            assertTrue(
                a.transport.reachable.value
                    .any { it.nodeId == "bob" },
            )
            // The room ✓✓ in both its forms: AckSync's cleartext receipt and its sealed escalation.
            a.transport.fastSend(frame(FrameType.RECEIPT, "alice", recipientId = "bob", relay = false), Peer("bob"))
            a.transport.fastSend(frame(FrameType.CHAT, "alice", recipientId = "bob", body = "tick", relay = false), Peer("bob"))
            advanceTimeBy(10_000)
            runCurrent()
            assertTrue("no room tick rides LoRa", a.link.sent.isEmpty())
            a.transport.stop()
        }

    @Test
    fun anOlderBuildsOfferIsDroppedUnderItsOwnCounterAndStillCountsTheRadio() =
        runTest {
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            // An older build's gateway OFFER: first byte 0x10, then a publisher digest and prefixes.
            a.link.deliver(2u, 0, MeshtasticProto.PORT_PRIVATE_APP, byteArrayOf(LoraMeshTransport.RETIRED_CTL_TAG, 1, 2, 3, 4))
            runCurrent()
            assertEquals(1L, a.metrics.lora().loraRetiredCtl)
            assertEquals(
                "an unknown tag stays one nobody assigned",
                null,
                a.metrics
                    .fast()
                    .fastDropsByReason[app.getknit.knit.mesh.FastPathDrop.UNKNOWN_TAG]
                    ?.takeIf { it > 0 },
            )
            assertEquals("the radio is still a radio in range", 1, a.status().boardsHeard)
            assertEquals("but nobody has spoken", 0, a.status().heard)
            a.transport.stop()
        }

    // --- The signal row and the radio count (moved from the bridge's tests). ---

    private fun Rig.status() = transport.status.value

    /**
     * One packet straight off the air onto this rig's board, bypassing the frame codec — what the portnum/channel
     * filter and [LoraMeshTransport.noteBoard] see. The defaults are a healthy board-to-board reception on the bound
     * channel.
     */
    private fun Rig.hear(
        from: UInt,
        channelIndex: Int = 0,
        portnum: Int = MeshtasticProto.PORT_PRIVATE_APP,
        snr: Float? = 6.5f,
        rssi: Int? = -85,
    ) = link.deliver(from, channelIndex, portnum, byteArrayOf(1), snr, rssi)

    @Test
    fun oneRadioAiringOthersFramesCountsAsOneRadioAndSeveralPeople() =
        runTest {
            // Field report: "3 peers heard over LoRa" with only two radios in existence. The count was of frame
            // *authors*, and an older build's gateway still airs frames authored by people nowhere near it, so the
            // number legitimately outruns the hardware. Radios and people are counted separately.
            val air = FakeMeshtasticAir()
            val a = rig(air, 1u, "alice", backgroundScope) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()

            listOf("bob", "carol", "dave").forEach { author ->
                deliverFrame(a, frame(FrameType.CHAT, author, body = "from $author", sentAt = testScheduler.currentTime), from = 2u)
                advanceTimeBy(5_000)
                runCurrent()
            }

            assertEquals("one other radio, however many people it speaks for", 1, a.status().boardsHeard)
            assertEquals("three people are reachable through it", 3, a.status().heard)
            a.transport.stop()
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

    /**
     * Before our own id is read only [LoraMeshTransport.PRE_ID_ADMISSIONS] frames are admitted, first come first
     * served: an own post fanned ahead of a burst of relayed re-fans takes a slot, and the re-fans past the cap are
     * refused rather than shedding it from a full queue.
     */
    @Test
    fun beforeTheSelfIdIsKnownAnOwnPostFannedFirstKeepsItsSlotAndLaterReFansAreRefused() =
        runTest {
            val air = FakeMeshtasticAir()
            val self = CompletableDeferred<String>()
            val pace = LoraPacePolicy(minGapMs = 0, queueCap = LoraMeshTransport.PRE_ID_ADMISSIONS)
            val a = rig(air, 1u, "alice", backgroundScope, pace = pace, selfId = { self.await() }) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            val now = testScheduler.currentTime
            val post = frame(FrameType.CHAT, "alice", body = "typed during the backoff", sentAt = now)
            a.transport.fastFanout(post)
            // A burst of relayed re-fans: uncapped, they would shed the room post, the oldest of its class, from a full queue.
            repeat(6) { a.transport.fastFanout(frame(FrameType.CHAT, "carol", body = "relayed $it", sentAt = now)) }
            assertEquals("only the cap's worth reached the queue", LoraMeshTransport.PRE_ID_ADMISSIONS, pace.pending)
            assertEquals(3L, a.metrics.lora().loraPreIdRefused)
            assertEquals("nothing was shed", 0L, a.metrics.lora().loraDroppedQueue)

            self.complete("alice")
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals("our own post went, and nothing else", 1, a.link.sent.size)
            a.transport.stop()
        }

    /**
     * The pre-id cap's trade-off, recorded (ADR 2026-10.rp3s): before the id the fan-out cannot tell our post from a
     * relayed one, so the cap is first come first served. Four relayed re-fans ahead of an own post take every slot,
     * and the post is refused under `loraPreIdRefused` — lost to the air, though the radios and the spool still carry
     * it — rather than any frame being shed from the queue.
     */
    @Test
    fun beforeTheSelfIdIsKnownReFansFannedFirstTakeEverySlotAndOurOwnPostIsRefused() =
        runTest {
            val air = FakeMeshtasticAir()
            val self = CompletableDeferred<String>()
            val pace = LoraPacePolicy(minGapMs = 0, queueCap = LoraMeshTransport.PRE_ID_ADMISSIONS)
            val a = rig(air, 1u, "alice", backgroundScope, pace = pace, selfId = { self.await() }) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            val now = testScheduler.currentTime
            repeat(LoraMeshTransport.PRE_ID_ADMISSIONS) {
                a.transport.fastFanout(frame(FrameType.CHAT, "carol", body = "relayed $it", sentAt = now))
            }
            assertEquals("the re-fans took every slot", LoraMeshTransport.PRE_ID_ADMISSIONS, pace.pending)
            assertEquals(0L, a.metrics.lora().loraPreIdRefused)

            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "typed during the backoff", sentAt = now))
            assertEquals("our post is refused past the cap", 1L, a.metrics.lora().loraPreIdRefused)
            assertEquals("not queued", LoraMeshTransport.PRE_ID_ADMISSIONS, pace.pending)
            assertEquals("nothing was shed", 0L, a.metrics.lora().loraDroppedQueue)

            self.complete("alice")
            advanceTimeBy(30_000)
            runCurrent()
            assertTrue("nothing aired: the re-fans are not ours, and our post never queued", a.link.sent.isEmpty())
            a.transport.stop()
        }

    /**
     * The pre-id cap charges only a frame that reaches the queue (ADR 2026-10.rp3s): a burst of stale and two-packet
     * frames fanned before the id must not use up the slots an own post typed then needs.
     */
    @Test
    fun beforeTheSelfIdIsKnownFramesThatNeverQueueDoNotUseUpTheCap() =
        runTest {
            val air = FakeMeshtasticAir()
            val self = CompletableDeferred<String>()
            val pace = LoraPacePolicy(minGapMs = 0)
            val a = rig(air, 1u, "alice", backgroundScope, pace = pace, selfId = { self.await() }) { testScheduler.currentTime }
            a.transport.start()
            runCurrent()
            advanceTimeBy(2 * LoraFramePolicy.FRESH_MS)
            val now = testScheduler.currentTime
            repeat(LoraMeshTransport.PRE_ID_ADMISSIONS) {
                a.transport.fastFanout(frame(FrameType.CHAT, "carol", body = "stale $it", sentAt = 0L))
                a.transport.fastFanout(frame(FrameType.CHAT, "carol", body = incompressibleBody(300) + it, sentAt = now))
            }
            assertEquals("none of them queued", 0, pace.pending)

            a.transport.fastFanout(frame(FrameType.CHAT, "alice", body = "typed during the backoff", sentAt = now))
            assertEquals("our post still found a slot", 1, pace.pending)
            assertEquals(0L, a.metrics.lora().loraPreIdRefused)

            self.complete("alice")
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals("our own post went", 1, a.link.sent.size)
            a.transport.stop()
        }
}
