package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.MeshMetrics
import app.getknit.knit.mesh.crypto.b64
import app.getknit.knit.mesh.link.FastFrameCodec
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MeshtasticHooks] (ADR 2026-11.gsqu, Stage 1c): the kind's half of the plane core, read through its own seam — the
 * dispatch table, the slot guards and their "no answer", the auto-reply memory's snapshot surface, and the board-ACK
 * booking. What each feature does end to end stays pinned by the transport suites over the fake air; these pin what the
 * core now asks the kind.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshtasticHooksTest {
    private class Link : MeshtasticLink {
        override val state = MutableStateFlow<LinkState>(LinkState.Idle)
        override val packets = MutableSharedFlow<ReceivedPacket>()
        override val outcomes = MutableSharedFlow<PacketOutcome>()
        override val queue = MutableStateFlow<QueueInfo?>(null)
        override val battery = MutableStateFlow<BoardBattery?>(null)
        override val boardAir = MutableStateFlow<BoardAir?>(null)
        override val nodes = MutableStateFlow<Map<UInt, BoardOwner>>(emptyMap())

        /** What the board's NodeInfo stream last showed per node (none by default). */
        var users: Map<UInt, StreamedUser> = emptyMap()

        override fun nodeUser(node: UInt): StreamedUser? = users[node]

        override fun userSeq(): Long = 0

        override suspend fun send(
            payload: ByteArray,
            channelIndex: Int,
            portnum: Int,
            hopLimit: Int?,
            destination: LinkDestination,
        ): SendResult = SendResult.Queued(1u, QueueInfo(3, 16, 0))

        override suspend fun provisionChannel(spec: ProvisionSpec): ProvisionResult = ProvisionResult.Restored

        override fun start(address: String) = Unit

        override fun stop() = Unit
    }

    private class Core : PlaneCore {
        override val airtime = LoraAirtime()
        override var config: LoraConfig? = LoraConfig("AA:1", KNIT_SLOT)
        override val maxPayload = LoraMeshTransport.PRE_READY_PAYLOAD
        override var restored = true
        val queued = mutableListOf<String>()
        val keysHeard = mutableListOf<BoardAddr>()
        var moved = 0

        override fun enqueue(
            parts: List<ByteArray>,
            label: String,
            ceiling: Ceiling,
            destination: Destination,
            to: BoardAddr?,
        ): Boolean {
            queued += "$label:$destination:${to?.meshtasticNode}"
            return true
        }

        override fun limitersMoved() {
            moved++
        }

        override fun onBoardKeyHeard(from: BoardAddr) {
            keysHeard += from
        }
    }

    private val link = Link()
    private val core = Core()
    private val now = 1_000L
    private val hooks =
        MeshtasticHooks(
            link,
            MeshtasticBoardLink(link),
            core,
            TestScope(UnconfinedTestDispatcher()),
            MeshMetrics(),
            clock = { now },
            log = {},
            onPublicPost = {},
            onBoardBound = {},
        )

    private fun ready(
        channels: List<ChannelInfo> = listOf(ChannelInfo(0, "", 1), ChannelInfo(KNIT_SLOT, KnitChannel.NAME, 2)),
        licensed: Boolean = false,
    ): LinkState.Ready = LinkState.Ready(BoardInfo(OWN, "heltec", "2.8.1.abc", licensed = licensed), channels, 255)

    private fun packet(
        portnum: Int,
        payload: ByteArray,
        channel: Int = KNIT_SLOT,
        from: UInt = 9u,
        to: UInt = MeshtasticProto.BROADCAST,
        id: UInt = 5u,
        wantAck: Boolean = false,
    ): BoardPacket {
        val raw =
            ReceivedPacket(
                from = from,
                to = to,
                id = id,
                channelIndex = channel,
                portnum = portnum,
                payload = payload,
                rxSnr = 6.5f,
                rxRssi = -85,
                hopsAway = 0,
                wantAck = wantAck,
            )
        return BoardPacket(BoardAddr(from), to == OWN, channel, payload, 6.5f, -85, 0, null, raw)
    }

    @Test
    fun theDispatchIsTheOrderTheTransportAlwaysReadItIn() {
        val compact = byteArrayOf(LORA_DIRECT_TAG, 1)
        val private = MeshtasticProto.PORT_PRIVATE_APP
        assertEquals(PacketRoute.DIRECT, hooks.route(packet(private, compact), KNIT_SLOT))
        assertEquals("2.8 reports a PKI DM on index 0", PacketRoute.DIRECT, hooks.route(packet(private, compact, channel = 0), KNIT_SLOT))
        assertEquals("nor a hearing off those two", PacketRoute.DROPPED, hooks.route(packet(private, compact, channel = 4), KNIT_SLOT))
        assertEquals(PacketRoute.KIND, hooks.route(packet(MeshtasticProto.PORT_NODEINFO, ByteArray(0)), KNIT_SLOT))
        assertEquals(PacketRoute.KIND, hooks.route(packet(MeshtasticProto.PORT_TEXT_MESSAGE, byteArrayOf(1), channel = 3), KNIT_SLOT))
        assertEquals(PacketRoute.HEARD, hooks.route(packet(MeshtasticProto.PORT_TEXT_MESSAGE, ByteArray(0)), KNIT_SLOT))
        assertEquals(PacketRoute.HEARD, hooks.route(packet(private, byteArrayOf(FastFrameCodec.TAG_COMPACT), channel = 4), KNIT_SLOT))
        assertEquals(PacketRoute.HEARD, hooks.route(packet(MeshtasticProto.PORT_ROUTING, byteArrayOf(1)), KNIT_SLOT))
        assertEquals(PacketRoute.KNIT, hooks.route(packet(private, byteArrayOf(FastFrameCodec.TAG_COMPACT)), KNIT_SLOT))
        assertEquals(
            "unbound reads every slot",
            PacketRoute.KNIT,
            hooks.route(packet(private, byteArrayOf(FastFrameCodec.TAG_COMPACT), channel = 4), null),
        )
        assertEquals(PacketRoute.RETIRED, hooks.route(packet(private, byteArrayOf(LoraMeshTransport.RETIRED_CTL_TAG, 1)), KNIT_SLOT))
        val retired = byteArrayOf(LoraMeshTransport.RETIRED_CTL_TAG, 1)
        assertEquals(
            "a retired tag off the bound slot is only a hearing",
            PacketRoute.HEARD,
            hooks.route(packet(private, retired, channel = 4), KNIT_SLOT),
        )
        assertEquals("and on another port", PacketRoute.HEARD, hooks.route(packet(MeshtasticProto.PORT_ROUTING, retired), KNIT_SLOT))
        assertEquals(
            "unbound, a compact DM still leaves only by index 0",
            PacketRoute.DROPPED,
            hooks.route(packet(private, compact, channel = 4), null),
        )
        val stranger = BoardPacket(BoardAddr(9u), false, KNIT_SLOT, byteArrayOf(1), null, null, null, null, kind = "another kind")
        assertEquals("not a Meshtastic record", PacketRoute.DROPPED, hooks.route(stranger, KNIT_SLOT))
    }

    @Test
    fun aNodeInfoWithAKeyIsTheCoresAndOneWithoutIsNothing() {
        val keyed =
            ProtoWriter()
                .bytes(
                    MeshtasticProto.USER_PUBLIC_KEY,
                    ByteArray(MeshtasticProto.NODE_PUBLIC_KEY_BYTES) { 2 },
                ).toByteArray()
        hooks.onKindPacket(packet(MeshtasticProto.PORT_NODEINFO, keyed))
        hooks.onKindPacket(packet(MeshtasticProto.PORT_NODEINFO, ByteArray(0), from = 10u))
        assertEquals(listOf(BoardAddr(9u)), core.keysHeard)
    }

    @Test
    fun aCompactDmLeavesFromAnySlotButZero() {
        assertTrue(hooks.directSlotRefused(PublicChannelPolicy.PRIMARY_INDEX))
        assertFalse(hooks.directSlotRefused(KNIT_SLOT))
        assertFalse("no config is no slot zero", hooks.directSlotRefused(null))
    }

    @Test
    fun theSlotGuardsReadTheTableAndAnotherKindHasNoAnswer() {
        val broadcast = OutboundFrame(listOf(byteArrayOf(1)), "room")
        val dm =
            OutboundFrame(
                listOf(byteArrayOf(1)),
                "dm",
                FrameClass.DIRECT,
                direct = DirectSend("bob", BoardAddr(9u), "f1", DirectKind.MESSAGE, airings = 5),
            )

        fun check(
            frame: OutboundFrame,
            state: Any,
            ch: Int = KNIT_SLOT,
        ) = hooks.checkSlot(frame, BoardState.Ready(BoardAddr(OWN), 228, null, KNIT_SLOT, state), ch)

        assertEquals(SlotCheck.Clear, check(broadcast, ready()))
        assertEquals("an unread table is the benefit of the doubt", SlotCheck.Clear, check(broadcast, ready(channels = emptyList())))
        assertTrue(check(broadcast, ready(), ch = 3) is SlotCheck.Refused)
        val aead = ready(channels = listOf(ChannelInfo(KNIT_SLOT, KnitChannel.NAME, 2, useAead = true)))
        assertTrue("a broadcast rides the AEAD slot", check(broadcast, aead) is SlotCheck.Refused)
        assertEquals("a PKI DM never touches it", SlotCheck.Clear, check(dm, aead))
        val ham = ready(channels = listOf(ChannelInfo(KNIT_SLOT, KnitChannel.NAME, 2, useAead = true)), licensed = true)
        assertTrue("a ham-mode DM rides the slot", check(dm, ham) is SlotCheck.Refused)
        assertNull("no Meshtastic session under it: no answer", check(broadcast, "another kind"))
    }

    @Test
    fun theAutoReplyMemoryRoundTripsCappedAndARestoredSenderIsNotAnsweredAgain() {
        val shift = WallShift(nowMono = now, nowWall = WALL)
        val stamps = (0..MeshtasticHooks.STATE_AUTO_REPLY_CAP).map { SeenStamp(DmAutoReplyPolicy.senderKey(100u + it.toUInt()), WALL - it) }
        hooks.restoreState(LoraPlaneSnapshot(savedAtWall = WALL, autoReplied = stamps), shift)
        val saved = hooks.snapshotState(shift)
        assertEquals(MeshtasticHooks.STATE_AUTO_REPLY_CAP, saved.size)
        assertEquals(
            "the newest kept (the policy's own cap, through the hooks)",
            stamps
                .dropLast(1)
                .map {
                    it.id
                }.toSet(),
            saved.map { it.id }.toSet(),
        )

        link.state.value = ready()
        val text = DmAutoReplyPolicy.TEXT.encodeToByteArray()
        hooks.onKindPacket(packet(MeshtasticProto.PORT_TEXT_MESSAGE, text, channel = 0, from = 100u, to = OWN))
        assertEquals("answered by the last process", emptyList<String>(), core.queued)
        hooks.onKindPacket(packet(MeshtasticProto.PORT_TEXT_MESSAGE, text, channel = 0, from = 7u, to = OWN))
        assertEquals(listOf("autoreply:Reply:7"), core.queued)
        assertEquals("the stamp asks for a save", 1, core.moved)
    }

    @Test
    fun theAutoReplyWaitsForTheRestoredMemory() {
        core.restored = false
        link.state.value = ready()
        hooks.onKindPacket(packet(MeshtasticProto.PORT_TEXT_MESSAGE, byteArrayOf(1), channel = 0, from = 7u, to = OWN))
        assertEquals(emptyList<String>(), core.queued)
        assertEquals("unstamped", emptyList<SeenStamp>(), hooks.snapshotState(WallShift(now, WALL)))
    }

    @Test
    fun ourBoardsAckIsBookedOnlyWhenItSentOneAndOnlyForAMeshtasticBoard() {
        val dm = byteArrayOf(LORA_DIRECT_TAG, 1)
        hooks.bookDirectAck(packet(MeshtasticProto.PORT_PRIVATE_APP, dm, to = OWN), BoardAddr(OWN), onBound = true)
        assertEquals("asked for none", 0L, core.airtime.snapshot(now).usedMs)
        hooks.bookDirectAck(packet(MeshtasticProto.PORT_PRIVATE_APP, dm, to = OWN, wantAck = true), BoardAddr.MeshCore(ByteArray(6)), true)
        assertEquals("not a Meshtastic board", 0L, core.airtime.snapshot(now).usedMs)
        hooks.bookDirectAck(packet(MeshtasticProto.PORT_PRIVATE_APP, dm, to = OWN, wantAck = true), BoardAddr(OWN), onBound = true)
        assertEquals(
            LoraAirtime().timeOnAirMs(LoraDirectPolicy.ACK_PAYLOAD_BYTES, LoraAirtime.UNSIGNED),
            core.airtime.snapshot(now).usedMs,
        )
    }

    @Test
    fun aKeyIsTaughtOnlyToAMeshtasticBoard() {
        val holder = LoraBoardHolder(BoardAddr(9u), direct = true)
        assertEquals(KeyTeach.Refused, hooks.teachKey("bob", holder, BoardAddr.MeshCore(ByteArray(6)), sentAfter = 0))
    }

    /**
     * The carried-forward half (Stage 1c's deferred unit-test finding): a contact whose profile key binds its board, and
     * a NodeInfo stream that showed that board's keyed `User` before the refused send (so its key is set aside and only
     * its names are read, `LoraContactPolicy.judge`): our Meshtastic board gets the `add_contact`, a MeshCore one nothing.
     */
    @Test
    fun aKeyedStreamedUserBeforeTheSendGetsAMeshtasticBoardAWriteAndAMeshcoreOneNothing() {
        val key = ByteArray(MeshtasticProto.NODE_PUBLIC_KEY_BYTES) { (it * 7 + 3).toByte() }
        val node = LoraContactPolicy.crc32(key)
        link.users = mapOf(node to StreamedUser(BoardOwnerRaw(LoraContactPolicy.contactUser(node, key, stored = null)), seq = 1))
        val holder = LoraBoardHolder(BoardAddr(node), direct = true, loraKey = b64(key), contact = true)
        assertTrue(hooks.teachKey("bob", holder, BoardAddr(OWN), sentAfter = 2) is KeyTeach.Write)
        assertEquals(KeyTeach.Refused, hooks.teachKey("bob", holder, BoardAddr.MeshCore(ByteArray(6)), sentAfter = 2))
    }

    /**
     * The other carried-forward case: our board's ACK of a PKI DM that came in on slot 0 (`onBound` false), on a 2.8.1
     * board whose primary channel is AEAD. #243's byte rule: a routing unicast is never signed outside ham mode, and an AEAD
     * channel adds its 12-byte tag — so the ACK costs its payload and 12 B, unsigned, and the bound slot's plain price does
     * not apply.
     */
    @Test
    fun ourBoardsAckOnAnAeadPrimarySlotPaysTheTagUnsigned() {
        core.airtime.onBoard(SigningFacts("2.8.1.8e6a88d", hasXeddsa = true), primaryAead = true, knitAead = false)
        val dm = byteArrayOf(LORA_DIRECT_TAG, 1)
        hooks.bookDirectAck(
            packet(MeshtasticProto.PORT_PRIVATE_APP, dm, channel = 0, to = OWN, wantAck = true),
            BoardAddr(OWN),
            onBound = false,
        )
        val tagged =
            LoraAirtime().timeOnAirMs(
                LoraDirectPolicy.ACK_PAYLOAD_BYTES + MeshtasticProto.AEAD_OVERHEAD_BYTES,
                LoraAirtime.UNSIGNED,
            )
        assertEquals(tagged, core.airtime.snapshot(now).usedMs)
        assertTrue(
            "dearer than on the plain bound slot",
            tagged > LoraAirtime().timeOnAirMs(LoraDirectPolicy.ACK_PAYLOAD_BYTES, LoraAirtime.UNSIGNED),
        )
    }

    @Test
    fun theInstanceStatusIsTheCompanions() {
        val base = LoraStatus(queued = 2)
        link.state.value = ready()
        val state = MeshtasticBoardLink(link).state.value
        val status = hooks.statusOf(state, base)
        assertEquals(MeshtasticHooks.statusOf(state, base), status)
        assertEquals("a Ready board names its node", OWN, status.boardNodeNum)
    }

    @Test
    fun theBindingCarriesTheAddressItsSessionDialled() {
        // The `dialled` race (ADR 2026-11.gsqu's Stage 5a-2 amendment): the binding names the session's own address, so a
        // late Ready from board A, after the plane moved to B, is written under A — which the settings edit refuses.
        val bound = mutableListOf<BoardBinding>()
        val reporting =
            MeshtasticHooks(
                link,
                MeshtasticBoardLink(link),
                core,
                TestScope(UnconfinedTestDispatcher()),
                MeshMetrics(),
                clock = { now },
                log = {},
                onPublicPost = {},
                onBoardBound = { bound += it },
            )
        link.state.value = ready().copy(address = "AA:AA")
        reporting.onReady(MeshtasticBoardLink(link).state.value as BoardState.Ready)
        assertEquals("AA:AA", bound.single().dialled)
        assertEquals(OWN, bound.single().nodeNum)
    }

    private companion object {
        const val KNIT_SLOT = 1
        const val OWN = 0xA1u
        const val WALL = 1_800_000_000_000L
    }
}
