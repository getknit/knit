package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.bluetooth.BackoffConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
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
 * Drives [MeshtasticSession] against [FakeGattChannel] under virtual time. `now` is bound to the test
 * scheduler and every GATT op is instantaneous in the fake, so `runCurrent()` settles the handshake at
 * t=0 while the 180 s heartbeat stays scheduled in the future (never use `advanceUntilIdle` — the
 * heartbeat ticker would spin forever).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("LargeClass") // cohesive single-SUT suite over one shared FakeGattChannel/scriptBoard harness
class MeshtasticSessionTest {
    private val nonce = 0x11u

    private fun scriptHandshake(ch: FakeGattChannel) {
        ch.onWrite = { bytes ->
            if (BoardBytes.isWantConfig(bytes)) {
                ch.enqueueRead(BoardBytes.myInfo(0xABCDu, "heltec-v4"))
                ch.enqueueRead(BoardBytes.channel(1, "knit", 2))
                ch.enqueueRead(BoardBytes.configComplete(nonce))
            }
        }
    }

    private fun session(
        dialer: FakeGattDialer,
        scope: kotlinx.coroutines.CoroutineScope,
        now: () -> Long,
    ) = MeshtasticSession(
        dialer = dialer,
        scope = scope,
        backoff = BackoffConfig(baseMs = 5_000, maxMs = 180_000, jitterFraction = 0.0),
        now = now,
        rand = { 0.5 },
        nonce = { nonce },
        ids = PacketIdSource(1000L),
    )

    @Test
    fun handshakeReachesReadyWithBoardAndChannels() =
        runTest {
            val ch = FakeGattChannel().also(::scriptHandshake)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA:BB")
            runCurrent()

            val st = session.state.value
            assertTrue("state is Ready but was $st", st is LinkState.Ready)
            st as LinkState.Ready
            assertEquals(0xABCDu, st.board.myNodeNum)
            assertEquals("heltec-v4", st.board.pioEnv)
            assertEquals(listOf(ChannelInfo(1, "knit", 2)), st.channels)
            assertEquals(512, st.mtu)
            session.stop()
        }

    @Test
    fun aWrongNonceConfigCompleteIsIgnoredUntilTheRightOne() =
        runTest {
            val ch = FakeGattChannel()
            ch.onWrite = { bytes ->
                if (BoardBytes.isWantConfig(bytes)) {
                    ch.enqueueRead(BoardBytes.configComplete(0x99u)) // stale — from a previous handshake
                    ch.enqueueRead(BoardBytes.myInfo(0x1u, "heltec-v4"))
                    ch.enqueueRead(BoardBytes.configComplete(nonce)) // ours
                }
            }
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            assertTrue(session.state.value is LinkState.Ready)
            session.stop()
        }

    @Test
    fun sendReturnsQueuedWhenTheBoardAcksTheId() =
        runTest {
            val ch = FakeGattChannel().also(::scriptHandshake)
            ch.onWrite = { bytes ->
                if (BoardBytes.isWantConfig(bytes)) {
                    ch.enqueueRead(BoardBytes.myInfo(0xABCDu, "heltec-v4"))
                    ch.enqueueRead(BoardBytes.configComplete(nonce))
                } else if (BoardBytes.isPacket(bytes)) {
                    ch.enqueueRead(BoardBytes.queueStatus(free = 15, maxlen = 16, meshPacketId = BoardBytes.packetId(bytes)))
                }
            }
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            val send = async { session.send(byteArrayOf(1, 2, 3), channelIndex = 1) }
            runCurrent()
            val result = send.await()
            assertTrue("got $result", result is SendResult.Queued)
            result as SendResult.Queued
            assertEquals(1000u, result.id)
            assertEquals(15, result.queue.free)
            session.stop()
        }

    @Test
    fun sendFoldsInAnImmediateNak() =
        runTest {
            val ch = FakeGattChannel()
            ch.onWrite = { bytes ->
                when {
                    BoardBytes.isWantConfig(bytes) -> {
                        ch.enqueueRead(BoardBytes.myInfo(0xABCDu, "heltec-v4"))
                        ch.enqueueRead(BoardBytes.configComplete(nonce))
                    }

                    BoardBytes.isPacket(bytes) -> {
                        ch.enqueueRead(
                            BoardBytes.nak(from = 0xABCDu, requestId = BoardBytes.packetId(bytes), reason = RoutingError.NO_CHANNEL),
                        )
                    }
                }
            }
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            val send = async { session.send(byteArrayOf(1, 2, 3), channelIndex = 9) }
            runCurrent()
            val result = send.await()
            assertEquals(SendResult.Nak(1000u, RoutingError.NO_CHANNEL), result)
            session.stop()
        }

    @Test
    fun sendRefusesLocallyWhenOverThePayloadCap() =
        runTest {
            val ch = FakeGattChannel().also(::scriptHandshake)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            assertEquals(SendResult.TooLarge, session.send(ByteArray(MeshtasticProto.MAX_PAYLOAD + 1), channelIndex = 1))
            session.stop()
        }

    @Test
    fun sendIsBusyWhenTheBoardHasNoQueueHeadroom() =
        runTest {
            val ch = FakeGattChannel().also(::scriptHandshake)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            // Deliver a queueStatus with free=0 over the notify path so the session's queue view is full.
            ch.enqueueRead(BoardBytes.queueStatus(free = 0, maxlen = 16, meshPacketId = 0u))
            ch.notify()
            runCurrent()
            assertEquals(SendResult.Busy, session.send(byteArrayOf(1), channelIndex = 1))
            session.stop()
        }

    @Test
    fun sendBeforeReadyIsNotReady() =
        runTest {
            val ch = FakeGattChannel()
            val dialer = FakeGattDialer(ch)
            dialer.adapterOn.value = false // never reaches Ready
            val session = session(dialer, backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            val result = session.send(byteArrayOf(1), channelIndex = 1)
            assertTrue(result is SendResult.NotReady)
            session.stop()
        }

    @Test
    fun heartbeatIsWrittenAfterTheInterval() =
        runTest {
            val ch = FakeGattChannel().also(::scriptHandshake)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            val before = ch.writes.count { BoardBytes.isHeartbeat(it) }
            advanceTimeBy(180_001)
            runCurrent()
            assertEquals(before + 1, ch.writes.count { BoardBytes.isHeartbeat(it) })
            session.stop()
        }

    @Test
    fun aDialFailureBacksOffThenConnects() =
        runTest {
            val ch = FakeGattChannel().also(::scriptHandshake)
            val dialer = FakeGattDialer(ch)
            dialer.dialResults.addLast(DialResult.Failed(status = 133, phase = "connect"))
            val session = session(dialer, backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            val st = session.state.value
            assertTrue("first attempt backs off but was $st", st is LinkState.Disconnected)
            assertEquals(1, (st as LinkState.Disconnected).streak)

            advanceTimeBy(5_001) // the base backoff
            runCurrent()
            assertTrue(session.state.value is LinkState.Ready)
            assertEquals(2, dialer.dials)
            session.stop()
        }

    @Test
    fun adapterOffParksUnavailableThenConnectsWhenItReturns() =
        runTest {
            val ch = FakeGattChannel().also(::scriptHandshake)
            val dialer = FakeGattDialer(ch)
            dialer.adapterOn.value = false
            val session = session(dialer, backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            assertEquals(LinkState.Unavailable, session.state.value)
            assertEquals(0, dialer.dials)

            dialer.adapterOn.value = true
            runCurrent()
            assertTrue(session.state.value is LinkState.Ready)
            session.stop()
        }

    @Test
    fun aStaleBondIsTerminalAndStopsRetrying() =
        runTest {
            val ch = FakeGattChannel()
            ch.subscribeResult = GattResult.Failed(status = 137) // GATT_AUTH_FAIL
            val dialer = FakeGattDialer(ch).apply { bond = BondState.BONDED }
            val session = session(dialer, backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            assertEquals(LinkState.StaleBond("AA"), session.state.value)
            val dialsAfterTerminal = dialer.dials
            advanceTimeBy(600_000)
            runCurrent()
            assertEquals("terminal state must not keep dialling", dialsAfterTerminal, dialer.dials)
            session.stop()
        }

    @Test
    fun anUnbondedAuthFailureAsksForPairing() =
        runTest {
            val ch = FakeGattChannel()
            ch.subscribeResult = GattResult.Failed(status = 5) // insufficient auth, not yet bonded
            val dialer = FakeGattDialer(ch).apply { bond = BondState.NONE }
            val session = session(dialer, backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            assertEquals(LinkState.NeedsPairing("AA"), session.state.value)
            session.stop()
        }

    @Test
    fun aDisconnectEndsTheSessionAndReconnects() =
        runTest {
            val ch = FakeGattChannel().also(::scriptHandshake)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            assertTrue(session.state.value is LinkState.Ready)

            ch.disconnect(status = 19)
            runCurrent()
            assertTrue("a disconnect backs off", session.state.value is LinkState.Disconnected)
            advanceTimeBy(5_001)
            runCurrent()
            assertTrue(session.state.value is LinkState.Ready)
            session.stop()
        }

    @Test
    fun aRebootTriggersAFreshHandshake() =
        runTest {
            val ch = FakeGattChannel().also(::scriptHandshake)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            assertTrue(session.state.value is LinkState.Ready)

            ch.enqueueRead(BoardBytes.rebooted())
            ch.notify()
            runCurrent()
            advanceTimeBy(5_001) // reboot ends the session; it reconnects on the base backoff
            runCurrent()
            assertTrue(session.state.value is LinkState.Ready)
            session.stop()
        }

    @Test
    fun stopClosesTheChannelAndGoesIdle() =
        runTest {
            val ch = FakeGattChannel().also(::scriptHandshake)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()
            val closesBefore = ch.closes
            session.stop()
            runCurrent()
            assertEquals(LinkState.Idle, session.state.value)
            assertTrue("stop closes the channel", ch.closes > closesBefore)
        }

    @Test
    fun inboundPacketsSurfaceOnTheNotifyPath() =
        runTest {
            val ch = FakeGattChannel().also(::scriptHandshake)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            val received = mutableListOf<ReceivedPacket>()
            val collector = backgroundScope.launch { session.packets.collect { received += it } }
            session.start("AA")
            runCurrent()
            ch.enqueueRead(
                BoardBytes.packet(
                    from = 0x1234u,
                    channel = 1,
                    portnum = MeshtasticProto.PORT_PRIVATE_APP,
                    payload = byteArrayOf(9, 9),
                    id = 7u,
                ),
            )
            ch.notify()
            runCurrent()
            assertEquals(1, received.size)
            assertEquals(0x1234u, received.first().from)
            assertEquals(MeshtasticProto.PORT_PRIVATE_APP, received.first().portnum)
            collector.cancel()
            session.stop()
        }

    // --- channel provisioning ---

    private val provisionSpec = ProvisionSpec(name = "Knit", psk = byteArrayOf(1, 2, 3, 4))

    /** Firmware that stores the unmonitored mark, and the release just before it (ADR 2026-09.emd7). */
    private companion object {
        const val MARKING_FIRMWARE = "2.6.9.f223b8a"
        const val PRE_MARK_FIRMWARE = "2.6.8.ef9d0d7"
    }

    /** [BoardBytes.myInfo] hands the session node 0xABCD, so this is the identity Knit writes that board. */
    private val knitOwner = BoardOwner("Knit abcd", "Knit", unmessagable = true)

    /** The stock identity the same board arrives with — what a setup records and a restore puts back. */
    private val stockOwner = BoardOwner("Meshtastic abcd", "abcd")

    /**
     * The board's own `User`, as a real one reports it: the two names Knit rewrites, plus fields it must
     * carry through untouched — above all `is_licensed`, a presence-less bool a from-scratch `User` would
     * clear, taking the firmware's `override_duty_cycle` with it.
     */
    private val boardUser =
        ProtoWriter()
            .string(1, "!0000abcd")
            .string(MeshtasticProto.USER_LONG_NAME, stockOwner.longName)
            .string(MeshtasticProto.USER_SHORT_NAME, stockOwner.shortName)
            .varint(5, 9) // hw_model
            .varint(6, 1) // is_licensed
            .toByteArray()

    /**
     * A board an older Knit set up: named, but never marked — the ADR 2026-09.emd7 half of the unfinished
     * identity a re-run of the setup comes back to finish.
     */
    private val namedKnitUser =
        spliceStringFields(
            boardUser,
            mapOf(
                MeshtasticProto.USER_LONG_NAME to knitOwner.longName,
                MeshtasticProto.USER_SHORT_NAME to knitOwner.shortName,
            ),
        )!!

    /** The same board once Knit has written its whole identity — the starting point for a re-run. */
    private val knitUser = spliceVarintFields(namedKnitUser, mapOf(MeshtasticProto.USER_IS_UNMESSAGABLE to 1L))!!

    /**
     * The same finished board as a real one reports it: carrying the `public_key` the firmware publishes on
     * its own (every board since PKC landed). Knit never writes that field, so it must not count towards
     * whether the board already carries the identity Knit wants.
     */
    private val keyedKnitUser =
        knitUser +
            ProtoWriter()
                .bytes(MeshtasticProto.USER_PUBLIC_KEY, ByteArray(MeshtasticProto.NODE_PUBLIC_KEY_BYTES) { 7 })
                .toByteArray()

    /** What [boardConfigs] and [boardUser] say the board was set to, in the shape a setup reports back. */
    private val boardIntervals =
        BoardSettings(
            nodeInfoSecs = 900,
            positionSecs = 900,
            smartPosition = true,
            telemetrySecs = 1_800,
            rebroadcastMode = 0,
            owner = stockOwner,
        )

    /**
     * The board's own sub-configs as a real one would report them: values Knit rewrites *and* values it must
     * leave alone (`role`, `rebroadcast_mode`, `gps_mode`), which is what the read-modify-write is for.
     */
    private val boardConfigs =
        mapOf(
            BoardConfig.DEVICE to
                ProtoWriter()
                    .varint(1, 2)
                    .varint(7, 900)
                    .toByteArray(),
            BoardConfig.POSITION to
                ProtoWriter()
                    .varint(1, 900)
                    .varint(2, 1)
                    .varint(13, 1)
                    .toByteArray(),
            BoardConfig.TELEMETRY to ProtoWriter().varint(1, 1800).varint(3, 1).toByteArray(),
        )

    // The board's `Config.LoRaConfig` as the admin read returns it: US, on the shared slot. MediumFast
    // rather than the default LongFast on purpose — LongFast's code is 0, which proto3 writes by omission,
    // so it could not show that the splice preserved it.
    private val boardLoraConfig =
        ProtoWriter()
            .bool(1, true) // use_preset
            .varint(2, ModemPreset.MEDIUM_FAST.code)
            .varint(7, LoraRegion.US.code)
            .varint(8, 3) // hop_limit
            .varint(10, 27) // tx_power — must survive the splice untouched
            .toByteArray()

    /** The same board after a dedicate — what a restore reads back before putting the old values in. */
    private val quietedConfigs =
        boardConfigs.mapValues { (config, raw) -> spliceVarintFields(raw, BoardQuiet.quiet(config))!! }

    /** The board's side of the `want_config` handshake, up to and including `config_complete_id`. */
    private fun replyToWantConfig(
        ch: FakeGattChannel,
        channels: List<Triple<Int, String, Int>>,
        radio: ByteArray?,
        firmware: String?,
    ) {
        ch.enqueueRead(BoardBytes.myInfo(0xABCDu, "heltec-v4"))
        firmware?.let { ch.enqueueRead(BoardBytes.metadata(it)) }
        channels.forEach { (i, n, r) -> ch.enqueueRead(BoardBytes.channel(i, n, r)) }
        radio?.let { ch.enqueueRead(it) }
        ch.enqueueRead(BoardBytes.configComplete(nonce))
    }

    /**
     * Scripts a board: the config handshake reports [channels] (index, name, role) and [firmware], admin
     * GETs return a passkey, and admin SETs are ACKed — except the first [nakFirstSet] SETs, which NAK with
     * [nakReason].
     */
    private fun scriptBoard(
        ch: FakeGattChannel,
        channels: List<Triple<Int, String, Int>>,
        nakFirstSet: Int = 0,
        nakReason: RoutingError = RoutingError.ADMIN_BAD_SESSION_KEY,
        configs: Map<BoardConfig, ByteArray>? = null,
        user: ByteArray? = boardUser,
        radio: ByteArray? = null,
        firmware: String? = MARKING_FIRMWARE,
    ) {
        var sets = 0
        ch.onWrite = { bytes ->
            when {
                BoardBytes.isWantConfig(bytes) -> {
                    replyToWantConfig(ch, channels, radio, firmware)
                }

                BoardBytes.isAdminGetConfig(bytes) -> {
                    val config = BoardBytes.adminGetConfigType(bytes)
                    val raw = config?.let { configs?.get(it) }
                    // A board that never answers is the abort case: the flow must write nothing at all.
                    if (config != null && raw != null) {
                        ch.enqueueRead(
                            BoardBytes.adminGetConfigResponse(
                                from = 0xABCDu,
                                requestId = BoardBytes.packetId(bytes),
                                passkey = byteArrayOf(0x0A, 0x0B),
                                config = config,
                                raw = raw,
                            ),
                        )
                    }
                }

                BoardBytes.isAdminGetOwner(bytes) -> {
                    // A board that never answers is the abort case here too: nothing may be written.
                    if (user != null) {
                        ch.enqueueRead(
                            BoardBytes.adminGetOwnerResponse(
                                from = 0xABCDu,
                                requestId = BoardBytes.packetId(bytes),
                                passkey = byteArrayOf(0x0A, 0x0B),
                                user = user,
                            ),
                        )
                    }
                }

                BoardBytes.isAdminGet(bytes) -> {
                    ch.enqueueRead(BoardBytes.adminGetResponse(0xABCDu, BoardBytes.packetId(bytes), passkey = byteArrayOf(0x0A, 0x0B)))
                }

                BoardBytes.isAdminSet(bytes) -> {
                    val reason = if (sets++ < nakFirstSet) nakReason else RoutingError.NONE
                    ch.enqueueRead(BoardBytes.nak(0xABCDu, BoardBytes.packetId(bytes), reason))
                }

                BoardBytes.isAdmin(bytes) -> {
                    // begin/commit — a plain ACK
                    ch.enqueueRead(BoardBytes.nak(0xABCDu, BoardBytes.packetId(bytes), RoutingError.NONE))
                }
            }
        }
    }

    @Test
    fun provisionWritesKnitIntoAFreeSecondaryAndLeavesThePrimaryAlone() =
        runTest {
            val ch = FakeGattChannel()
            scriptBoard(ch, channels = listOf(Triple(0, "", 1)), configs = boardConfigs)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            val result = async { session.provisionChannel(provisionSpec) }.await()
            assertEquals(ProvisionResult.Provisioned(1, alreadyPresent = false, previous = boardIntervals), result)
            val writes = ch.writes.mapNotNull { BoardBytes.adminSetChannel(it) }
            assertEquals(1, writes.size)
            // Index 0 is untouched on purpose: the firmware hashes the *primary* name into the frequency, and
            // sharing the public one is what gets Knit's packets repeated by stock nodes for free.
            assertEquals(1, writes.first().index)
            assertEquals("Knit", writes.first().name)
            assertEquals(MeshtasticProto.ROLE_SECONDARY, writes.first().role)
            assertEquals("position sharing is turned off on the Knit channel", 0, writes.first().positionPrecision)
            session.stop()
        }

    @Test
    fun provisionRenamesTheBoardForKnitAndKeepsTheRestOfItsUser() =
        runTest {
            val ch = FakeGattChannel()
            scriptBoard(ch, channels = listOf(Triple(0, "", 1)), configs = boardConfigs)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            async { session.provisionChannel(provisionSpec) }.await()
            val user = ch.writes.mapNotNull { BoardBytes.adminSetOwnerRaw(it) }.single()
            assertEquals(knitOwner.longName, readStringField(user, MeshtasticProto.USER_LONG_NAME))
            assertEquals(knitOwner.shortName, readStringField(user, MeshtasticProto.USER_SHORT_NAME))
            assertEquals("the node id survived the read-modify-write", "!0000abcd", readStringField(user, 1))
            assertEquals("hw_model survived", 9L, readVarintField(user, 5))
            // A `User` built from scratch would read is_licensed = false and clear override_duty_cycle with it.
            assertEquals("is_licensed survived", 1L, readVarintField(user, 6))
            session.stop()
        }

    @Test
    fun provisionMarksTheBoardUnmonitored() =
        runTest {
            val ch = FakeGattChannel()
            scriptBoard(ch, channels = listOf(Triple(0, "", 1)), configs = boardConfigs)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            async { session.provisionChannel(provisionSpec) }.await()
            // Knit keeps only PRIVATE_APP off the air, so a stranger's Meshtastic DM here is ACKed by the
            // firmware and then dropped unseen; the mark is what stops their app offering the board at all.
            val user = ch.writes.mapNotNull { BoardBytes.adminSetOwnerRaw(it) }.single()
            assertEquals(1L, readVarintField(user, MeshtasticProto.USER_IS_UNMESSAGABLE))
            session.stop()
        }

    @Test
    fun provisionLeavesTheMarkOffFirmwareThatWouldOnlyDropIt() =
        runTest {
            val ch = FakeGattChannel()
            scriptBoard(ch, channels = listOf(Triple(0, "", 1)), configs = boardConfigs, firmware = PRE_MARK_FIRMWARE)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            async { session.provisionChannel(provisionSpec) }.await()
            // 2.6.8 drops field 9 and never echoes it back, so writing it would leave the setup looking
            // permanently unfinished on a board that is in fact fine. The rename still happens.
            val user = ch.writes.mapNotNull { BoardBytes.adminSetOwnerRaw(it) }.single()
            assertNull(readVarintField(user, MeshtasticProto.USER_IS_UNMESSAGABLE))
            assertEquals(knitOwner.longName, readStringField(user, MeshtasticProto.USER_LONG_NAME))
            session.stop()
        }

    @Test
    fun provisionOnANamedButUnmarkedBoardMarksItAndKeepsTheNameItRecorded() =
        runTest {
            val ch = FakeGattChannel()
            // Set up and named by an older Knit, but never marked: the ADR 2026-09.emd7 migration.
            scriptBoard(
                ch,
                channels = listOf(Triple(0, "", 1), Triple(2, "Knit", 2)),
                configs = boardConfigs,
                user = namedKnitUser,
            )
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            val stored = BoardSettings(900, 600, true, 1_800, rebroadcastMode = 0, owner = stockOwner)
            val result = async { session.provisionChannel(provisionSpec.copy(previous = stored)) }.await()
            // The board is *already* called "Knit abcd", so filling the record in from it here would
            // overwrite the stock name — the only copy a restore has to put back.
            assertEquals(ProvisionResult.Provisioned(2, alreadyPresent = true, previous = stored), result)
            val user = ch.writes.mapNotNull { BoardBytes.adminSetOwnerRaw(it) }.single()
            assertEquals(1L, readVarintField(user, MeshtasticProto.USER_IS_UNMESSAGABLE))
            assertTrue(
                "neither the channel table nor the intervals are rewritten",
                ch.writes.none { BoardBytes.isAdminSet(it) || BoardBytes.isAdminSetConfig(it) },
            )
            session.stop()
        }

    @Test
    fun provisionAbortsWithoutWritingWhenTheBoardWillNotReturnItsName() =
        runTest {
            val ch = FakeGattChannel()
            scriptBoard(ch, channels = listOf(Triple(0, "", 1)), configs = boardConfigs, user = null)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            val result = async { session.provisionChannel(provisionSpec) }.await()
            assertTrue("got $result", result is ProvisionResult.Failed)
            assertTrue(
                "nothing written",
                ch.writes.none { BoardBytes.isAdminSet(it) || BoardBytes.isAdminSetConfig(it) || BoardBytes.isAdminSetOwner(it) },
            )
            session.stop()
        }

    @Test
    fun provisionReportsNoFreeSlotWhenEverySecondaryIsTaken() =
        runTest {
            val ch = FakeGattChannel()
            scriptBoard(ch, channels = (0..7).map { Triple(it, "ch$it", if (it == 0) 1 else 2) }, configs = boardConfigs)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            assertEquals(ProvisionResult.NoFreeSlot, async { session.provisionChannel(provisionSpec) }.await())
            assertTrue("nothing written", ch.writes.none { BoardBytes.isAdminSet(it) || BoardBytes.isAdminSetConfig(it) })
            session.stop()
        }

    @Test
    fun provisionOnAnAlreadySetUpBoardKeepsTheRecordedIntervals() =
        runTest {
            val ch = FakeGattChannel()
            scriptBoard(ch, channels = listOf(Triple(0, "", 1), Triple(2, "Knit", 2)), configs = boardConfigs, user = knitUser)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            val result = async { session.provisionChannel(provisionSpec) }.await()
            // `previous` stays null so the caller keeps the intervals it stored the first time round.
            assertEquals(ProvisionResult.Provisioned(2, alreadyPresent = true), result)
            assertTrue(
                "nothing rewritten",
                ch.writes.none { BoardBytes.isAdminSet(it) || BoardBytes.isAdminSetConfig(it) || BoardBytes.isAdminSetOwner(it) },
            )
            session.stop()
        }

    @Test
    fun provisionOnAFinishedBoardThatPublishesAPublicKeyWritesNoOwner() =
        runTest {
            val ch = FakeGattChannel()
            scriptBoard(
                ch,
                channels = listOf(Triple(0, "", 1), Triple(2, "Knit", 2)),
                configs = boardConfigs,
                user = keyedKnitUser,
                firmware = MARKING_FIRMWARE,
            )
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            val result = async { session.provisionChannel(provisionSpec) }.await()
            assertEquals(ProvisionResult.Provisioned(2, alreadyPresent = true), result)
            assertTrue("no owner rewritten", ch.writes.none { BoardBytes.isAdminSetOwner(it) })
            session.stop()
        }

    @Test
    fun provisionOnABoardSetUpBeforeKnitNamedBoardsRenamesItAndNothingElse() =
        runTest {
            val ch = FakeGattChannel()
            // Set up (it carries the Knit channel) but still stock-named — every board provisioned before ADR 049.
            scriptBoard(ch, channels = listOf(Triple(0, "", 1), Triple(2, "Knit", 2)), configs = boardConfigs)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            val stored = BoardSettings(900, 600, true, 1_800, rebroadcastMode = 0)
            val result = async { session.provisionChannel(provisionSpec.copy(previous = stored)) }.await()
            // The caller's own record is carried forward with the old name filled in — re-recording the
            // intervals here would write back the *quieted* ones and destroy the only copy of the board's.
            assertEquals(
                ProvisionResult.Provisioned(2, alreadyPresent = true, previous = stored.copy(owner = stockOwner)),
                result,
            )
            assertEquals(1, ch.writes.count { BoardBytes.isAdminSetOwner(it) })
            assertTrue(
                "neither the channel table nor the intervals are rewritten",
                ch.writes.none { BoardBytes.isAdminSet(it) || BoardBytes.isAdminSetConfig(it) },
            )
            session.stop()
        }

    @Test
    fun provisionRetriesOnceAfterABadSessionKey() =
        runTest {
            val ch = FakeGattChannel()
            scriptBoard(ch, channels = listOf(Triple(0, "", 1)), nakFirstSet = 1, configs = boardConfigs)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            val call = async { session.provisionChannel(provisionSpec) }
            runCurrent()
            val result = call.await()
            assertEquals(ProvisionResult.Provisioned(1, alreadyPresent = false, previous = boardIntervals), result)
            assertEquals("first set NAK'd, second succeeded", 2, ch.writes.count { BoardBytes.isAdminSet(it) })
            session.stop()
        }

    // --- what the setup rewrites on the board (ADR 045) ---

    @Test
    fun provisionQuietsTheIntervalsAndLeavesEverythingElseAlone() =
        runTest {
            val ch = FakeGattChannel()
            scriptBoard(ch, channels = listOf(Triple(0, "", 1)), configs = boardConfigs)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            async { session.provisionChannel(provisionSpec) }.await()
            val written = ch.writes.mapNotNull { BoardBytes.adminSetConfigRaw(it) }
            assertEquals("one write per sub-config", 3, written.size)
            val device = written.first()
            assertEquals(BoardQuiet.QUIET_SECS.toLong(), readVarintField(device, MeshtasticProto.DEVICE_NODE_INFO_BROADCAST_SECS))
            assertEquals("role survived the read-modify-write", 2L, readVarintField(device, 1))
            assertEquals(
                "the board stops repeating strangers' traffic",
                BoardQuiet.REBROADCAST_LOCAL_ONLY.toLong(),
                readVarintField(device, MeshtasticProto.DEVICE_REBROADCAST_MODE),
            )
            val position = written[1]
            assertEquals(BoardQuiet.QUIET_SECS.toLong(), readVarintField(position, MeshtasticProto.POSITION_BROADCAST_SECS))
            assertNull("smart broadcast cleared", readVarintField(position, MeshtasticProto.POSITION_BROADCAST_SMART))
            assertEquals("gps_mode untouched", 1L, readVarintField(position, 13))
            val telemetry = written[2]
            assertEquals(BoardQuiet.QUIET_SECS.toLong(), readVarintField(telemetry, MeshtasticProto.TELEMETRY_DEVICE_UPDATE_INTERVAL))
            assertEquals("the other telemetry switches survived", 1L, readVarintField(telemetry, 3))
            session.stop()
        }

    @Test
    fun provisionAbortsWithoutWritingWhenTheBoardWillNotReturnItsConfig() =
        runTest {
            val ch = FakeGattChannel()
            scriptBoard(ch, channels = listOf(Triple(0, "", 1)), configs = null) // answers no get_config
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            val result = async { session.provisionChannel(provisionSpec) }.await()
            assertTrue("got $result", result is ProvisionResult.Failed)
            assertTrue("nothing written", ch.writes.none { BoardBytes.isAdminSet(it) || BoardBytes.isAdminSetConfig(it) })
            session.stop()
        }

    @Test
    fun restoreDisablesEveryKnitChannelAndNeverTouchesThePrimary() =
        runTest {
            val ch = FakeGattChannel()
            scriptBoard(
                ch,
                channels = listOf(Triple(0, "", 1), Triple(1, "Knit", 2), Triple(3, "Knit", 2)),
                configs = quietedConfigs,
                user = knitUser,
            )
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            val spec = provisionSpec.copy(mode = ProvisionMode.Restore, previous = boardIntervals)
            assertEquals(ProvisionResult.Restored, async { session.provisionChannel(spec) }.await())
            val writes = ch.writes.mapNotNull { BoardBytes.adminSetChannel(it) }
            assertEquals(listOf(1, 3), writes.map { it.index })
            assertTrue("both disabled", writes.all { it.role == 0 })
            session.stop()
        }

    @Test
    fun restoringABoardThatWasNeverSetUpIsRefused() =
        runTest {
            val ch = FakeGattChannel()
            scriptBoard(ch, channels = listOf(Triple(0, "MyChannel", 1)), configs = boardConfigs)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            // There is nothing to undo, and the config writes would push the board to values it never had.
            val spec = provisionSpec.copy(mode = ProvisionMode.Restore)
            val result = async { session.provisionChannel(spec) }.await()
            assertTrue("got $result", result is ProvisionResult.Failed)
            assertTrue("nothing written", ch.writes.none { BoardBytes.isAdminSet(it) || BoardBytes.isAdminSetConfig(it) })
            session.stop()
        }

    @Test
    fun restorePutsTheBoardsOwnIntervalsBackNotTheFirmwareDefaults() =
        runTest {
            val ch = FakeGattChannel()
            scriptBoard(ch, channels = listOf(Triple(0, "", 1), Triple(1, "Knit", 2)), configs = quietedConfigs, user = knitUser)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            val spec =
                provisionSpec.copy(
                    mode = ProvisionMode.Restore,
                    previous = BoardSettings(1_111, 2_222, true, 3_333, rebroadcastMode = 3, owner = stockOwner),
                )
            async { session.provisionChannel(spec) }.await()
            val written = ch.writes.mapNotNull { BoardBytes.adminSetConfigRaw(it) }
            assertEquals(3, written.size)
            assertEquals(1_111L, readVarintField(written[0], MeshtasticProto.DEVICE_NODE_INFO_BROADCAST_SECS))
            assertEquals(2_222L, readVarintField(written[1], MeshtasticProto.POSITION_BROADCAST_SECS))
            assertEquals(1L, readVarintField(written[1], MeshtasticProto.POSITION_BROADCAST_SMART))
            assertEquals(3_333L, readVarintField(written[2], MeshtasticProto.TELEMETRY_DEVICE_UPDATE_INTERVAL))
            assertEquals(3L, readVarintField(written[0], MeshtasticProto.DEVICE_REBROADCAST_MODE))
            val user = ch.writes.mapNotNull { BoardBytes.adminSetOwnerRaw(it) }.single()
            assertEquals(stockOwner.longName, readStringField(user, MeshtasticProto.USER_LONG_NAME))
            assertEquals(stockOwner.shortName, readStringField(user, MeshtasticProto.USER_SHORT_NAME))
            // The mark goes back with the name: a restored board is a stock node, and a stock node is read.
            // A zero is written by omission, which is the encoding of "never said" and reads as messagable.
            assertNull(readVarintField(user, MeshtasticProto.USER_IS_UNMESSAGABLE))
            session.stop()
        }

    @Test
    fun restoreWithNoRecordedNameWritesTheOneTheFirmwareWouldHaveGivenTheBoard() =
        runTest {
            val ch = FakeGattChannel()
            scriptBoard(ch, channels = listOf(Triple(0, "", 1), Triple(1, "Knit", 2)), configs = quietedConfigs, user = knitUser)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            // Nothing recorded (an older install, or a board set up on another phone): leaving it saying
            // "Knit" would be the one visible trace of a restore that is supposed to leave none.
            val spec = provisionSpec.copy(mode = ProvisionMode.Restore)
            assertEquals(ProvisionResult.Restored, async { session.provisionChannel(spec) }.await())
            val user = ch.writes.mapNotNull { BoardBytes.adminSetOwnerRaw(it) }.single()
            assertEquals("Meshtastic abcd", readStringField(user, MeshtasticProto.USER_LONG_NAME))
            assertEquals("abcd", readStringField(user, MeshtasticProto.USER_SHORT_NAME))
            session.stop()
        }

    @Test
    fun provisionOnANonReadyLinkIsRejected() =
        runTest {
            val ch = FakeGattChannel() // never handshakes → stays Connecting/Disconnected
            val dialer = FakeGattDialer(ch).apply { dialResults.addLast(DialResult.Timeout) }
            val session = session(dialer, backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            val result = session.provisionChannel(provisionSpec)
            assertTrue("got $result", result is ProvisionResult.NotReady)
            session.stop()
        }

    // --- the board's battery ---

    @Test
    fun theHandshakeReadsTheBoardsOwnBatteryAndIgnoresOtherNodes() =
        runTest {
            val ch = FakeGattChannel()
            ch.onWrite = { bytes ->
                if (BoardBytes.isWantConfig(bytes)) {
                    ch.enqueueRead(BoardBytes.myInfo(0xABCDu, "heltec-v4"))
                    ch.enqueueRead(BoardBytes.nodeInfo(0xABCDu, batteryLevel = 78, voltage = 3.92f))
                    ch.enqueueRead(BoardBytes.nodeInfo(0x9999u, batteryLevel = 5, voltage = 3.3f)) // a neighbour's
                    ch.enqueueRead(BoardBytes.configComplete(nonce))
                }
            }
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            assertTrue(session.state.value is LinkState.Ready)
            assertEquals(BoardBattery(percent = 78, voltage = 3.92f, powered = false), session.battery.value)
            session.stop()
            assertNull("a reading never outlives its link", session.battery.value)
        }

    @Test
    fun theHandshakeReadsTheBoardsOwnNameAndIgnoresOtherNodes() =
        runTest {
            val ch = FakeGattChannel()
            ch.onWrite = { bytes ->
                if (BoardBytes.isWantConfig(bytes)) {
                    ch.enqueueRead(BoardBytes.myInfo(0xABCDu, "heltec-v4"))
                    ch.enqueueRead(BoardBytes.nodeInfo(0xABCDu, owner = stockOwner))
                    ch.enqueueRead(BoardBytes.nodeInfo(0x9999u, owner = BoardOwner("Someone else", "else"))) // a neighbour's
                    ch.enqueueRead(BoardBytes.configComplete(nonce))
                }
            }
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            // This is what tells the setup screen a board still carries its old name (ADR 049).
            assertEquals(stockOwner, (session.state.value as LinkState.Ready).board.owner)
            session.stop()
        }

    @Test
    fun readyReportsTheBoardsOwnKeyAndWhetherItSigns() =
        runTest {
            // The two things the profile advertises for a signing board: its Curve25519 key, off its own
            // NodeInfo, and the firmware's own word that it signs (`DeviceMetadata.has_xeddsa`).
            val ch = FakeGattChannel()
            ch.onWrite = { bytes ->
                if (BoardBytes.isWantConfig(bytes)) {
                    ch.enqueueRead(BoardBytes.myInfo(0xABCDu, "heltec-v4"))
                    ch.enqueueRead(BoardBytes.metadata("2.8.0.47db0e3", hasXeddsa = true))
                    ch.enqueueRead(
                        BoardBytes.nodeInfo(0xABCDu, owner = stockOwner.copy(publicKey = "oR62IJmFUE0Tgcw0GcypU5ZqUFCQllVBy2snB/BKQA4=")),
                    )
                    ch.enqueueRead(BoardBytes.configComplete(nonce))
                }
            }
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            val board = (session.state.value as LinkState.Ready).board
            assertEquals("oR62IJmFUE0Tgcw0GcypU5ZqUFCQllVBy2snB/BKQA4=", board.owner?.publicKey)
            assertEquals(true, board.hasXeddsa)
            assertEquals("2.8.0.47db0e3", board.firmwareVersion)
            session.stop()
        }

    @Test
    fun aReceivedPacketCarriesItsSignatureAndTheBoardsVerdict() =
        runTest {
            val ch = FakeGattChannel().also(::scriptHandshake)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            val received = mutableListOf<ReceivedPacket>()
            val collector = backgroundScope.launch { session.packets.collect { received += it } }
            session.start("AA")
            runCurrent()
            val sig = ByteArray(64) { it.toByte() }
            ch.enqueueRead(
                BoardBytes.packet(
                    from = 0x1234u,
                    channel = 0,
                    portnum = MeshtasticProto.PORT_TEXT_MESSAGE,
                    payload = "hi".encodeToByteArray(),
                    id = 7u,
                    signature = sig,
                    xeddsaSigned = true,
                ),
            )
            ch.enqueueRead(
                BoardBytes.packet(
                    from = 0x1234u,
                    channel = 0,
                    portnum = MeshtasticProto.PORT_TEXT_MESSAGE,
                    payload = "yo".encodeToByteArray(),
                    id = 8u,
                ),
            )
            ch.notify()
            runCurrent()
            assertEquals(2, received.size)
            assertTrue(sig.contentEquals(received[0].signature))
            assertTrue("the board's own verdict rides through", received[0].boardVerified)
            assertNull("an unsigned packet carries no signature", received[1].signature)
            assertFalse(received[1].boardVerified)
            collector.cancel()
            session.stop()
        }

    @Test
    fun aBoardThatNeverSendsItsOwnNodeInfoHasNoName() =
        runTest {
            val ch = FakeGattChannel().also(::scriptHandshake)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            assertNull((session.state.value as LinkState.Ready).board.owner)
            session.stop()
        }

    @Test
    fun theBoardsTelemetryRefreshesTheBatteryAndIsNeverSurfaced() =
        runTest {
            val ch = FakeGattChannel().also(::scriptHandshake)
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            val received = mutableListOf<ReceivedPacket>()
            val collector = backgroundScope.launch { session.packets.collect { received += it } }
            session.start("AA")
            runCurrent()
            assertNull(session.battery.value) // this handshake carried no node_info

            ch.enqueueRead(BoardBytes.telemetry(from = 0xABCDu, batteryLevel = 101, voltage = 4.1f))
            ch.notify()
            runCurrent()
            assertEquals(BoardBattery(percent = null, voltage = 4.1f, powered = true), session.battery.value)
            assertTrue("the board's own telemetry is not an inbound packet", received.isEmpty())

            // A neighbour's telemetry is theirs, not ours: the reading stands.
            ch.enqueueRead(BoardBytes.telemetry(from = 0x1234u, batteryLevel = 9, voltage = 3.4f))
            ch.notify()
            runCurrent()
            assertEquals(true, session.battery.value?.powered)
            collector.cancel()
            session.stop()
        }

    // --- ADR 067: the debug-only dedicated-frequency setup ---

    @Test
    fun theOrdinarySetupNeverReadsOrWritesTheRadioConfig() =
        runTest {
            val ch = FakeGattChannel()
            scriptBoard(ch, channels = listOf(Triple(0, "", 1)), configs = boardConfigs + (BoardConfig.LORA to boardLoraConfig))
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            async { session.provisionChannel(provisionSpec) }.await()
            // ADR 045's promise, now that the codec *can* address the radio: the shared-frequency setup still
            // neither asks for it nor writes it, so a legally-scoped sub-config is never a setup's business.
            assertTrue(ch.writes.none { BoardBytes.adminGetConfigType(it) == BoardConfig.LORA })
            assertEquals("still one write per quieted sub-config", 3, ch.writes.mapNotNull { BoardBytes.adminSetConfigRaw(it) }.size)
            session.stop()
        }

    @Test
    fun theDedicatedSetupPinsTheSlotAndLeavesTheRestOfTheRadioAlone() =
        runTest {
            val ch = FakeGattChannel()
            scriptBoard(
                ch,
                channels = listOf(Triple(0, "", 1)),
                configs = boardConfigs + (BoardConfig.LORA to boardLoraConfig),
                radio = BoardBytes.loraConfig(LoraRegion.US, ModemPreset.MEDIUM_FAST),
            )
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            val result = async { session.provisionChannel(provisionSpec.copy(mode = ProvisionMode.SetupDedicated)) }.await()
            assertTrue("got ${'$'}result", result is ProvisionResult.Provisioned)
            val written = ch.writes.mapNotNull { BoardBytes.adminSetConfigRaw(it) }
            assertEquals("the three quieted sub-configs plus the radio", 4, written.size)
            val lora = written.last()
            val want = LoraSlot.forRegion(LoraRegion.US, ModemPreset.MEDIUM_FAST)!!.toLong()
            assertEquals("the radio is pinned to Knit's derived slot", want, readVarintField(lora, MeshtasticProto.LORA_CHANNEL_NUM))
            // Everything else about the radio is the user's legally-scoped call and must survive verbatim.
            assertEquals("region survived the read-modify-write", LoraRegion.US.code.toLong(), readVarintField(lora, 7))
            assertEquals("modem preset survived", ModemPreset.MEDIUM_FAST.code.toLong(), readVarintField(lora, 2))
            assertEquals("tx_power survived", 27L, readVarintField(lora, 10))
            session.stop()
        }

    @Test
    fun theDedicatedSetupIsRefusedWithoutWritingWhereKnitWillNotPlaceASlot() =
        runTest {
            val ch = FakeGattChannel()
            scriptBoard(
                ch,
                channels = listOf(Triple(0, "", 1)),
                configs = boardConfigs + (BoardConfig.LORA to boardLoraConfig),
                radio = BoardBytes.loraConfig(LoraRegion.EU_868),
            )
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            val result = async { session.provisionChannel(provisionSpec.copy(mode = ProvisionMode.SetupDedicated)) }.await()
            assertEquals(ProvisionResult.NoDedicatedSlot(LoraRegion.EU_868), result)
            // The refusal has to be total: a board left half set up on a frequency we would not pick is worse
            // than one left alone.
            assertTrue("nothing written", ch.writes.none { BoardBytes.isAdminSet(it) || BoardBytes.isAdminSetConfig(it) })
            session.stop()
        }

    @Test
    fun restoreHandsAPinnedRadioBackToTheSharedSlot() =
        runTest {
            val ch = FakeGattChannel()
            scriptBoard(
                ch,
                channels = listOf(Triple(0, "", 1), Triple(1, "Knit", 2)),
                configs = boardConfigs + (BoardConfig.LORA to boardLoraConfig),
                radio = BoardBytes.loraConfig(LoraRegion.US, channelNum = 10),
            )
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            val spec = provisionSpec.copy(mode = ProvisionMode.Restore, previous = boardIntervals)
            assertEquals(ProvisionResult.Restored, async { session.provisionChannel(spec) }.await())
            val lora = ch.writes.mapNotNull { BoardBytes.adminSetConfigRaw(it) }.last()
            // channel_num 0 is a proto3 default, so handing the slot back means the field goes away — the
            // firmware is deriving it from the primary's name again.
            assertNull("back on the shared slot", readVarintField(lora, MeshtasticProto.LORA_CHANNEL_NUM))
            assertEquals("the rest of the radio is untouched", 27L, readVarintField(lora, 10))
            session.stop()
        }

    @Test
    fun restoreOfABoardThatWasNeverPinnedNeverTouchesTheRadio() =
        runTest {
            val ch = FakeGattChannel()
            scriptBoard(
                ch,
                channels = listOf(Triple(0, "", 1), Triple(1, "Knit", 2)),
                configs = boardConfigs + (BoardConfig.LORA to boardLoraConfig),
                radio = BoardBytes.loraConfig(LoraRegion.US),
            )
            val session = session(FakeGattDialer(ch), backgroundScope) { testScheduler.currentTime }
            session.start("AA")
            runCurrent()

            val spec = provisionSpec.copy(mode = ProvisionMode.Restore, previous = boardIntervals)
            async { session.provisionChannel(spec) }.await()
            assertTrue(ch.writes.none { BoardBytes.adminGetConfigType(it) == BoardConfig.LORA })
            assertEquals("only the quieted sub-configs", 3, ch.writes.mapNotNull { BoardBytes.adminSetConfigRaw(it) }.size)
            session.stop()
        }
}
