package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.TransportHealth
import app.getknit.knit.mesh.lora.BoardAddr
import app.getknit.knit.mesh.lora.BoardAir
import app.getknit.knit.mesh.lora.BoardBattery
import app.getknit.knit.mesh.lora.BoardLink
import app.getknit.knit.mesh.lora.BoardPacket
import app.getknit.knit.mesh.lora.BoardPlane
import app.getknit.knit.mesh.lora.BoardSend
import app.getknit.knit.mesh.lora.BoardSetupState
import app.getknit.knit.mesh.lora.BoardState
import app.getknit.knit.mesh.lora.DirectDm
import app.getknit.knit.mesh.lora.DirectKind
import app.getknit.knit.mesh.lora.LORA_DIRECT_TAG
import app.getknit.knit.mesh.lora.LoraAuthorCheck
import app.getknit.knit.mesh.lora.LoraConfig
import app.getknit.knit.mesh.lora.LoraDirectCrypto
import app.getknit.knit.mesh.lora.LoraDirectOpen
import app.getknit.knit.mesh.lora.LoraDirectSource
import app.getknit.knit.mesh.lora.LoraFrameCodec
import app.getknit.knit.mesh.lora.LoraFramePolicy
import app.getknit.knit.mesh.lora.LoraMeshTransport
import app.getknit.knit.mesh.lora.OpenedDirect
import app.getknit.knit.mesh.lora.PacketOutcome
import app.getknit.knit.mesh.lora.ProvisionResult
import app.getknit.knit.mesh.lora.ProvisionSpec
import app.getknit.knit.mesh.lora.QueueInfo
import app.getknit.knit.mesh.lora.RadioParams
import app.getknit.knit.mesh.lora.SendPurpose
import app.getknit.knit.mesh.lora.SendResult
import app.getknit.knit.mesh.protocol.ChatContent
import app.getknit.knit.mesh.protocol.FrameType
import app.getknit.knit.mesh.protocol.RelayEnvelope
import app.getknit.knit.mesh.protocol.WireCodec
import app.getknit.knit.mesh.protocol.WireEnvelope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hearing on a MeshCore plane (ADR 2026-11.gsqu §5 and its Stage 5a-2 amendment; lora-reviewer finding 4, the security
 * pass's B6 and replay items): the plane core over [MeshcoreTraits] and [MeshcoreHooks], a fake board link, and a board
 * holder whose DM went unanswered. A hearing comes only from a verified, fresh Knit frame — never from a sender prefix, a
 * non-Knit DM or a replayed post. At any hop count it lifts the mark (ADR 2026-11.gsqu's 2026-11-19 amendment: the firmware
 * routes a DM to a far board now); only one its author's radio aired at zero hops counts that radio, by author.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshcoreHearingTest {
    private class FakeBoard : BoardLink {
        val ready =
            BoardState.Ready(
                own = BoardAddr.MeshCore(ByteArray(6) { 1 }),
                payloadCap = MeshcoreProto.CHANNEL_DATA_CAP,
                radio = RadioParams(8, 62_500, 5),
                channel = KNIT_SLOT,
                detail =
                    MeshcoreDetail(
                        publicKey = ByteArray(32) { 1 },
                        versionCode = 11,
                        version = "v1.18.0",
                        defaultScope = false,
                        multiAcks = 0,
                        clientRepeat = false,
                        freqKhz = 869_525,
                        contactCapacity = 100,
                        slots = emptyList(),
                        manualAdd = 1,
                        dialled = BOARD_ADDRESS,
                    ),
            )
        override val state = MutableStateFlow<BoardState>(BoardState.Down(TransportHealth.Unavailable, MeshcoreLinkState.Idle))
        val inbound = MutableSharedFlow<BoardPacket>(extraBufferCapacity = 64)
        override val packets = inbound
        val outcome = MutableSharedFlow<PacketOutcome>(extraBufferCapacity = 64)
        override val outcomes = outcome
        override val headroom = MutableSharedFlow<Int>()
        override val headroomNow: Int? = null
        override val battery = MutableStateFlow<BoardBattery?>(null)
        override val boardAir = MutableStateFlow<BoardAir?>(null)
        val sent = mutableListOf<SendPurpose>()
        private var nextId = 1u

        override suspend fun send(
            payload: ByteArray,
            purpose: SendPurpose,
        ): BoardSend {
            sent += purpose
            return BoardSend(SendResult.Queued(nextId++, QueueInfo(10, 16, 0)), 0)
        }

        override suspend fun provision(spec: ProvisionSpec): ProvisionResult = ProvisionResult.Failed("not here")

        override fun start(address: String) {
            state.value = ready
        }

        override fun stop() = Unit
    }

    private class Ops : MeshcoreBoardOps {
        override val air = MutableSharedFlow<MeshcoreAir>()
        override val counters = MeshcoreCounters()
        override var observer: MeshcoreSessionObserver? = null

        override fun slotReading(): MeshcoreSlotReading = MeshcoreSlotReading(KNIT_SLOT, knit = true, atMs = 0)

        override suspend fun setup(): MeshcoreSetupResult = MeshcoreSetupResult.NotReady

        override suspend fun restore(): MeshcoreRestoreResult = MeshcoreRestoreResult.NotReady

        override suspend fun unbind(address: String): MeshcoreUnbind = MeshcoreUnbind.NOTHING

        // This phone set the board up (Stage 5b-2 part 2): the session's consent, which the offers need.
        override val consent =
            MutableStateFlow<MeshcoreConsent?>(
                MeshcoreConsent(BOARD_ADDRESS, MeshcoreHooks.digest(ByteArray(32) { 1 }), true, ByteArray(32) { 1 }),
            )

        override fun setKnitContacts(
            ranked: List<ByteArray>,
            withdrawn: Boolean,
        ) = Unit

        override suspend fun teach(key: ByteArray): ContactWrite = ContactWrite.NOT_READY

        override suspend fun removeUntaught(key: ByteArray): ContactWrite = ContactWrite.NOT_READY

        /** The session's setup state for the screen (Stage 7). */
        override val setupState = MutableStateFlow<MeshcoreSetupView?>(null)
    }

    /** Seals readably; opens a DM from bob whose second byte is [OPENS], stamped at its next four bytes' seconds. */
    private object Seal : LoraDirectCrypto {
        override fun seal(
            peerId: String,
            frameId: String,
            sentAtMs: Long,
            plaintext: ByteArray,
        ): ByteArray = byteArrayOf(LORA_DIRECT_TAG) + ByteArray(60) + plaintext

        override fun open(packet: ByteArray): LoraDirectOpen {
            if (packet.size < 6 || packet[1] != OPENS) return LoraDirectOpen.Stranger
            val secs =
                java.nio.ByteBuffer
                    .wrap(packet, 2, 4)
                    .int
                    .toLong()
            return LoraDirectOpen.Opened(OpenedDirect(BOB, "in-$secs", WALL + secs * 1_000, byteArrayOf(1)))
        }
    }

    /** A compact DM from bob's board, sent [ageMs] before now, at [pathLen]. */
    private fun TestScope.dmFromBob(
        ageMs: Long,
        pathLen: Int,
    ): BoardPacket {
        val secs = ((testScheduler.currentTime - ageMs) / 1_000).toInt()
        val payload =
            byteArrayOf(LORA_DIRECT_TAG, OPENS) +
                java.nio.ByteBuffer
                    .allocate(4)
                    .putInt(secs)
                    .array() + ByteArray(60)
        return BoardPacket(
            from = BOB_BOARD,
            toUs = true,
            channel = MeshcoreSession.DM_CHANNEL,
            payload = payload,
            rxSnr = 6f,
            rxRssi = null,
            hopsAway = null,
            rxTimeMs = null,
            kind =
                MeshcorePacket(
                    MeshcorePacket.Type.CONTACT_TEXT,
                    null,
                    pathLen,
                    MeshcoreProto.TXT_TYPE_PLAIN,
                    null,
                    null,
                    true,
                    true,
                    null,
                ),
        )
    }

    private object Source : LoraDirectSource {
        override suspend fun unackedTo(
            peerId: String,
            limit: Int,
            untakenOnly: Boolean,
            maxBytes: Int,
        ): List<DirectDm> = if (peerId == BOB) listOf(DirectDm("dm-1", WALL, byteArrayOf(1, 2, 3))) else emptyList()

        override suspend fun isUnacked(frameId: String): Boolean = frameId == "dm-1"
    }

    private class Node(
        val transport: LoraMeshTransport,
        val board: FakeBoard,
        val hooks: () -> MeshcoreHooks,
        val logs: MutableList<String>,
        val metrics: MeshMetrics,
    )

    private fun TestScope.node(
        ops: Ops = Ops(),
        hooksScope: CoroutineScope = backgroundScope,
        start: Boolean = true,
        authorCheck: LoraAuthorCheck = LoraAuthorCheck { wire, _ -> wire.sig[0] == HONEST },
    ): Node {
        val board = FakeBoard()
        val logs = mutableListOf<String>()
        val metrics = MeshMetrics()
        var made: MeshcoreHooks? = null
        val transport =
            LoraMeshTransport(
                selfId = { "alice" },
                plane =
                    BoardPlane(MeshcoreTraits, board) { core ->
                        MeshcoreHooks(
                            ops,
                            core,
                            hooksScope,
                            { testScheduler.currentTime },
                            { logs += it },
                        ).also { made = it }
                    },
                config = MutableStateFlow(LoraConfig("AA:1", 0, dms = true)),
                crypto = Seal,
                source = Source,
                boardMap =
                    flowOf(
                        MeshcoreContacts.holdersOf(
                            mapOf(
                                BOB to
                                    app.getknit.knit.mesh.MeshcoreBoardHolder(
                                        java.util.Base64
                                            .getEncoder()
                                            .encodeToString(BOB_KEY),
                                        app.getknit.knit.mesh.MeshcoreRank.ACCEPTED,
                                        1,
                                        false,
                                    ),
                            ),
                        ),
                    ),
                scope = backgroundScope,
                metrics = metrics,
                clock = { testScheduler.currentTime },
                wallClock = { WALL + testScheduler.currentTime },
                processStartWall = WALL,
                log = { logs += it },
                authorCheck = authorCheck,
            )
        // The session's observer runs before Ready: the ledger at the board's 10 % band.
        requireNotNull(ops.observer).beforeReady(board.ready)
        if (start) transport.start()
        runCurrent()
        return Node(transport, board, { requireNotNull(made) }, logs, metrics)
    }

    /**
     * Lora-reviewer (Stage 7a): the hooks start their collectors in their own constructor, before the transport's status fields
     * exist; a scope that runs them at once (here unconfined, in the field a multi-threaded one that wins the race) must not
     * reach `publishStatus` then. The move is published once construction ends, and the collector lives on to publish the next.
     */
    @Test
    fun aFaceMoveBeforeTheTransportIsBuiltNeverKillsItsCollector() =
        runTest {
            val ops = Ops().apply { setupState.value = MeshcoreSetupView(BOARD_ADDRESS, BoardSetupState.PENDING) }
            val eager = CoroutineScope(backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler))
            val n = node(ops, hooksScope = eager, start = false)
            // The move skipped during construction, published as construction ended: the status carries the face at all only
            // through that publish (its default has none), unknown until the plane's start names the bound address.
            assertEquals(
                BoardSetupState.UNKNOWN,
                n.transport.status.value.meshcore
                    ?.setup,
            )
            n.transport.start()
            runCurrent()
            ops.setupState.value = MeshcoreSetupView(BOARD_ADDRESS, BoardSetupState.SET_UP)
            runCurrent()
            assertEquals(
                BoardSetupState.SET_UP,
                n.transport.status.value.meshcore
                    ?.setup,
            )
        }

    /** Bob's board did not answer our DM: his mark is unreachable, as a lost board leaves it. */
    private fun TestScope.markBobUnreachable(n: Node) {
        n.transport.offer(BOB, "dm-1", WALL, byteArrayOf(1, 2, 3), DirectKind.MESSAGE)
        runCurrent()
        assertTrue("the DM went to bob's board", n.board.sent.any { it is SendPurpose.DirectDm })
        n.board.outcome.tryEmit(PacketOutcome.Unreachable(1u, flown = 3))
        runCurrent()
        assertEquals(1, n.transport.status.value.direct.unreachable)
    }

    private fun post(
        sentAt: Long,
        sig0: Byte = HONEST,
    ): ByteArray {
        val env =
            RelayEnvelope(
                type = FrameType.CHAT,
                id = "post-$sentAt-$sig0",
                senderId = BOB,
                sentAt = sentAt,
                payload = WireCodec.encodePayload(ChatContent(body = "north gate")),
            )
        val sig = ByteArray(64).also { it[0] = sig0 }
        val wire = WireEnvelope(relay = true, sig = sig, signed = WireCodec.encodeEnvelope(env))
        return requireNotNull(
            LoraFrameCodec.encodeBest(wire, 1, MeshcoreProto.CHANNEL_DATA_CAP, transcode = true, cost = null),
        ).parts.single()
    }

    private fun channelPacket(
        payload: ByteArray,
        pathLen: Int = 0,
        live: Boolean = true,
    ) = BoardPacket(
        from = null,
        toUs = false,
        channel = KNIT_SLOT,
        payload = payload,
        rxSnr = 6f,
        rxRssi = null,
        hopsAway = pathLen and MeshcoreProto.PATH_HOPS_MASK,
        rxTimeMs = null,
        kind =
            MeshcorePacket(
                MeshcorePacket.Type.CHANNEL_DATA,
                KNIT_SLOT,
                pathLen,
                null,
                MeshcoreProto.KNIT_DATA_TYPE,
                null,
                live,
                false,
                null,
            ),
    )

    private fun TestScope.hear(
        n: Node,
        packet: BoardPacket,
    ) {
        n.board.inbound.tryEmit(packet)
        runCurrent()
        advanceTimeBy(1_000)
        runCurrent()
    }

    private fun Node.heard(): Boolean = logs.any { it == "lora direct heard $BOB" }

    /** How many of bob's posts reached the router: the proof a negative case's packet got past the board's dispatch. */
    private fun Node.postsDecoded(): Int = logs.count { it.startsWith("lora rx ") && it.contains("id=post-") }

    /** How many compact DMs opened: the same proof for a DM. */
    private fun Node.dmsOpened(): Int = logs.count { it.startsWith("lora rx direct ") }

    @Test
    fun aFreshZeroHopVerifiedPostFromTheHoldersAuthorLiftsTheMarkAndCountsItsRadio() =
        runTest {
            val n = node()
            markBobUnreachable(n)
            hear(n, channelPacket(post(sentAt = WALL + testScheduler.currentTime)))
            assertEquals("the post reached the router", 1, n.postsDecoded())
            assertTrue(n.heard())
            assertEquals(0, n.transport.status.value.direct.unreachable)
            assertEquals("a radio, counted by author", 1, n.transport.status.value.boardsHeard)
        }

    private fun Node.judged(): Long = metrics.lora().loraHearingsJudged

    /**
     * Stage 8 (the lab's design pass): every exit of the post's hearing is counted once — the refusals at
     * once (a flood with hops, a stale post, a replay of the offline queue) and the author check's end either way — so a
     * scenario can wait for the judgement before it reads that nothing was lifted. A Public text never reaches it.
     */
    @Test
    fun everyExitOfAPostsHearingIsJudgedOnce() =
        runTest {
            val n = node()
            markBobUnreachable(n)
            val now = WALL + testScheduler.currentTime
            hear(n, channelPacket(post(sentAt = now - LoraFramePolicy.FRESH_MS - 60_000)))
            assertEquals("a stale post", 1L, n.judged())
            hear(n, channelPacket(post(sentAt = now + 2), live = false))
            assertEquals("a replay of the offline queue", 2L, n.judged())
            hear(n, channelPacket(post(sentAt = now + 3, sig0 = 9)))
            assertEquals("a post whose author check failed", 3L, n.judged())
            assertFalse("nothing so far was a hearing", n.heard())
            assertEquals(1, n.transport.status.value.direct.unreachable)

            // A flood with hops lifts it since ADR 2026-11.gsqu's 2026-11-19 amendment: the hearing itself.
            hear(n, channelPacket(post(sentAt = WALL + testScheduler.currentTime), pathLen = 2))
            assertEquals("the hearing itself", 4L, n.judged())
            assertTrue("and it lifted the mark", n.heard())
            assertEquals(0, n.transport.status.value.direct.unreachable)
            hear(n, channelPacket(post(sentAt = WALL + testScheduler.currentTime + 1)))
            assertEquals("a verified post with no mark left to lift", 5L, n.judged())
            assertEquals("every post reached the router", 5, n.postsDecoded())
        }

    /**
     * The judgement comes after the hearing's own effects (unit-test-reviewer, Stage 8): while the author check is still
     * running, nothing is counted and nothing is lifted; once it ends, both land together. A scenario waiting on the counter
     * therefore never reads the mark before a lift that was coming.
     */
    @Test
    fun aHearingIsJudgedOnlyOnceItsEffectsLanded() =
        runTest {
            val gate = CompletableDeferred<Unit>()
            var calls = 0
            // The decode (`readVerified`) checks the post's author first; the hearing's own check is the second call, and the
            // one held here, so the gate proves the order inside the hearing rather than holding the decode.
            val n =
                node(
                    authorCheck =
                        LoraAuthorCheck { wire, _ ->
                            if (++calls == 2) gate.await()
                            wire.sig[0] == HONEST
                        },
                )
            markBobUnreachable(n)
            val before = n.judged()
            n.board.inbound.tryEmit(channelPacket(post(sentAt = WALL + testScheduler.currentTime)))
            runCurrent()
            assertEquals("the decode's check, then the hearing's, held", 2, calls)
            assertEquals("the post is decoded", 1, n.postsDecoded())
            assertEquals("not judged while its author check runs", before, n.judged())
            assertFalse("nor lifted", n.heard())
            assertEquals(1, n.transport.status.value.direct.unreachable)

            gate.complete(Unit)
            runCurrent()
            assertEquals("judged once it ended", before + 1, n.judged())
            assertTrue("and lifted with it", n.heard())
            assertEquals(0, n.transport.status.value.direct.unreachable)
        }

    @Test
    fun aReplayedStaleSignedPostAtZeroHopsIsNoHearing() =
        runTest {
            val n = node()
            markBobUnreachable(n)
            val stale = WALL + testScheduler.currentTime - LoraFramePolicy.FRESH_MS - 60_000
            hear(n, channelPacket(post(sentAt = stale)))
            assertEquals("the post reached the router", 1, n.postsDecoded())
            assertFalse(n.heard())
            assertEquals(1, n.transport.status.value.direct.unreachable)
            assertEquals(0, n.transport.status.value.boardsHeard)
        }

    /**
     * Stage 6 (lora-reviewer's design pass): a text on Public slot 0 is the kind's and never a hearing — not even one whose
     * bytes are bob's valid, fresh, zero-hop, verified post. It never reaches the frame codec, the mark stays, no radio is
     * counted; it goes to the room.
     */
    @Test
    fun aPublicTextIsNoHearingEvenWhenItsBytesAreAVerifiedKnitPost() =
        runTest {
            val n = node()
            markBobUnreachable(n)
            val bytes = post(sentAt = WALL + testScheduler.currentTime)
            hear(
                n,
                BoardPacket(
                    from = null,
                    toUs = false,
                    channel = MeshcoreHooks.PUBLIC_SLOT,
                    payload = bytes,
                    rxSnr = 6f,
                    rxRssi = null,
                    hopsAway = 0,
                    rxTimeMs = null,
                    kind =
                        MeshcorePacket(
                            MeshcorePacket.Type.CHANNEL_TEXT,
                            MeshcoreHooks.PUBLIC_SLOT,
                            0,
                            MeshcoreProto.TXT_TYPE_PLAIN,
                            null,
                            1_790_000_000L,
                            live = true,
                            cobs = false,
                            text = bytes,
                        ),
                ),
            )
            assertEquals("never decoded as a frame", 0, n.postsDecoded())
            assertFalse(n.heard())
            assertEquals(1, n.transport.status.value.direct.unreachable)
            assertEquals(0, n.transport.status.value.boardsHeard)
            assertEquals(0, n.transport.status.value.heard)
            assertEquals(
                "handed to the room instead",
                1L,
                n
                    .hooks()
                    .counters.publicDelivered
                    .get(),
            )
        }

    @Test
    fun aFarHearingLiftsAMarkAtMostOnceAGapAndAZeroHopOneFreely() =
        runTest {
            // unit NB1 on ADR 2026-11.gsqu's 2026-11-19 amendment, through the transport: `onBoardHeard(far = !zeroHop)` with the
            // re-probe's gap (lora NB2). A far post lifts; bob marked again, a second far post inside the base gap does not; a
            // zero-hop post still does.
            val n = node()
            markBobUnreachable(n)
            hear(n, channelPacket(post(sentAt = WALL + testScheduler.currentTime), pathLen = 2))
            assertEquals("the first far post lifts", 0, n.transport.status.value.direct.unreachable)
            n.transport.offer(BOB, "dm-2", WALL, byteArrayOf(4, 5, 6), DirectKind.MESSAGE)
            runCurrent()
            val handle = (n.board.sent.size).toUInt()
            n.board.outcome.tryEmit(PacketOutcome.Unreachable(handle, flown = 3))
            runCurrent()
            assertEquals("marked again", 1, n.transport.status.value.direct.unreachable)
            advanceTimeBy(60_000) // well inside LoraReprobe.BASE_MS
            hear(n, channelPacket(post(sentAt = WALL + testScheduler.currentTime + 1), pathLen = 2))
            assertEquals("a second far post inside the gap lifts nothing", 1, n.transport.status.value.direct.unreachable)
            assertEquals(1, n.logs.count { it == "lora direct heard $BOB" })
            hear(n, channelPacket(post(sentAt = WALL + testScheduler.currentTime + 2)))
            assertEquals("a zero-hop post lifts freely", 0, n.transport.status.value.direct.unreachable)
            assertEquals(2, n.logs.count { it == "lora direct heard $BOB" })
        }

    @Test
    fun aMultiHopFloodLiftsTheMarkButCountsNoRadio() =
        runTest {
            // ADR 2026-11.gsqu's 2026-11-19 amendment: the firmware routes a DM to a far board now, so a verified, fresh post
            // that came through repeaters asks bob's board again; only a zero-hop one counts his radio.
            val n = node()
            markBobUnreachable(n)
            hear(n, channelPacket(post(sentAt = WALL + testScheduler.currentTime), pathLen = 2))
            assertEquals("the post reached the router", 1, n.postsDecoded())
            assertTrue(n.heard())
            assertEquals(0, n.transport.status.value.direct.unreachable)
            assertEquals(0, n.transport.status.value.boardsHeard)
        }

    @Test
    fun anUnverifiedPostOrAReplayOfTheOfflineQueueIsNoHearing() =
        runTest {
            val n = node()
            markBobUnreachable(n)
            // Anyone on the public Knit key can write bob's id on a post; only his pinned key's signature counts.
            hear(n, channelPacket(post(sentAt = WALL + testScheduler.currentTime, sig0 = 9)))
            hear(n, channelPacket(post(sentAt = WALL + testScheduler.currentTime + 1), live = false))
            assertEquals("both posts reached the router", 2, n.postsDecoded())
            assertFalse(n.heard())
            assertEquals(1, n.transport.status.value.direct.unreachable)
            assertEquals(0, n.transport.status.value.boardsHeard)
        }

    @Test
    fun aNonKnitDmWhosePrefixMatchesAnUnreachableContactLiftsNothing() =
        runTest {
            val n = node()
            markBobUnreachable(n)
            val text = "hello from bob's other app".encodeToByteArray()
            hear(
                n,
                BoardPacket(
                    from = BOB_BOARD,
                    toUs = true,
                    channel = MeshcoreSession.DM_CHANNEL,
                    payload = text,
                    rxSnr = 6f,
                    rxRssi = null,
                    hopsAway = null,
                    rxTimeMs = null,
                    kind =
                        MeshcorePacket(
                            MeshcorePacket.Type.CONTACT_TEXT,
                            null,
                            MeshcoreProto.PATH_UNKNOWN,
                            MeshcoreProto.TXT_TYPE_PLAIN,
                            null,
                            null,
                            live = true,
                            cobs = false,
                            text = text,
                        ),
                ),
            )
            assertFalse(n.heard())
            assertEquals(1, n.transport.status.value.direct.unreachable)
            assertEquals(0, n.transport.status.value.boardsHeard)
            assertEquals(
                1L,
                n
                    .hooks()
                    .counters.foreignDms
                    .get(),
            )
            assertFalse("never logged", n.logs.any { it.contains("other app") })
        }

    @Test
    fun aFreshOpenedDirectDmHearsItsBoardWhateverItsPath() =
        runTest {
            val n = node()
            markBobUnreachable(n)
            hear(n, dmFromBob(ageMs = 0, pathLen = MeshcoreProto.PATH_UNKNOWN))
            assertEquals("the DM opened", 1, n.dmsOpened())
            assertTrue(n.heard())
            assertEquals(0, n.transport.status.value.direct.unreachable)
            // It lifts the mark, but counts no radio: a DM along a learned path lands `0xFF` too (Stage 5c-2a).
            assertEquals(0, n.transport.status.value.boardsHeard)
        }

    @Test
    fun aStaleOpenedDmReairedDirectIsNoHearing() =
        runTest {
            val n = node()
            markBobUnreachable(n)
            // The seal opens for a day; a DM re-aired direct once MeshCore's dedup forgot it is no hearing (security N4).
            hear(n, dmFromBob(ageMs = LoraFramePolicy.FRESH_MS + 60_000, pathLen = MeshcoreProto.PATH_UNKNOWN))
            assertEquals("the DM opened", 1, n.dmsOpened())
            assertFalse(n.heard())
            assertEquals(1, n.transport.status.value.direct.unreachable)
            assertEquals(0, n.transport.status.value.boardsHeard)
        }

    @Test
    fun anOpenedFloodWithHopsLiftsTheMarkButCountsNoRadio() =
        runTest {
            // A far board's flooded DM reached us: its board is worth asking again (ADR 2026-11.gsqu's 2026-11-19 amendment).
            val n = node()
            markBobUnreachable(n)
            hear(n, dmFromBob(ageMs = 0, pathLen = 2))
            assertEquals("the DM opened", 1, n.dmsOpened())
            assertTrue(n.heard())
            assertEquals(0, n.transport.status.value.direct.unreachable)
            assertEquals(0, n.transport.status.value.boardsHeard)
        }

    private companion object {
        const val OPENS: Byte = 0x55
        const val KNIT_SLOT = 1
        const val BOB = "bob"
        const val HONEST: Byte = 7
        const val WALL = 1_800_000_000_000L

        /** Bob's MeshCore key: a real point, as the board map carries only those (Stage 5b-2: only taught keys are offered). */
        const val BOARD_ADDRESS = "AA:1"

        val BOB_KEY: ByteArray = FakeMeshcoreAir.keyOf("bob")
        val BOB_BOARD: BoardAddr.MeshCore = checkNotNull(MeshcorePins.addrOf(BOB_KEY))
    }
}
