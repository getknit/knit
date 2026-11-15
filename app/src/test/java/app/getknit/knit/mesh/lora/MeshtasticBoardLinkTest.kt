package app.getknit.knit.mesh.lora

import app.getknit.knit.mesh.TransportHealth
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [MeshtasticBoardLink] (ADR 2026-11.gsqu, Stage 1b.2): each send purpose writes exactly the fields the transport wrote
 * before the seam, the state is a live view of the session's, and an inbound packet keeps its Meshtastic record.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MeshtasticBoardLinkTest {
    private class Sent(
        val channel: Int,
        val portnum: Int,
        val hopLimit: Int?,
        val destination: LinkDestination,
        val seqAtSend: Long,
    )

    /** A [MeshtasticLink] that records what it is asked to write and answers [answer]. */
    private class RecordingLink : MeshtasticLink {
        override val state = MutableStateFlow<LinkState>(LinkState.Idle)
        override val packets = MutableSharedFlow<ReceivedPacket>(extraBufferCapacity = 8)
        override val outcomes = MutableSharedFlow<PacketOutcome>(extraBufferCapacity = 8)
        override val queue = MutableStateFlow<QueueInfo?>(null)
        override val battery = MutableStateFlow<BoardBattery?>(null)
        override val boardAir = MutableStateFlow<BoardAir?>(null)
        override val nodes = MutableStateFlow<Map<UInt, BoardOwner>>(emptyMap())
        val sent = mutableListOf<Sent>()
        var seq = 0L
        var answer: SendResult = SendResult.Queued(7u, QueueInfo(3, 16, 0))

        override fun nodeUser(node: UInt): StreamedUser? = null

        override fun userSeq(): Long = seq

        override suspend fun send(
            payload: ByteArray,
            channelIndex: Int,
            portnum: Int,
            hopLimit: Int?,
            destination: LinkDestination,
        ): SendResult {
            sent += Sent(channelIndex, portnum, hopLimit, destination, seq)
            seq++ // what a NodeInfo streamed during the write would do: the token must be the one before it
            return answer
        }

        override suspend fun provisionChannel(spec: ProvisionSpec): ProvisionResult = ProvisionResult.Restored

        override fun start(address: String) = Unit

        override fun stop() = Unit
    }

    private val link = RecordingLink()
    private val board = MeshtasticBoardLink(link)

    private fun ready(
        node: UInt = 0xA1u,
        mtu: Int = 255,
        radio: LoraRadioConfig? = LoraRadioConfig(true, ModemPreset.MEDIUM_FAST, LoraRegion.EU_868, 3, false),
    ) = LinkState.Ready(
        BoardInfo(node, "heltec", "2.8.1.abc"),
        listOf(ChannelInfo(0, "", 1), ChannelInfo(2, KnitChannel.NAME, 2)),
        mtu,
        radio,
    )

    @Test
    fun eachPurposeWritesTheFieldsItAlwaysDid() =
        runTest {
            val payload = byteArrayOf(1, 2, 3)
            link.seq = 41
            val knit = board.send(payload, SendPurpose.KnitFrame(channel = 2))
            board.send(payload, SendPurpose.DirectDm(channel = 2, to = BoardAddr(0x1234u)))
            board.send(payload, SendPurpose.PublicPost())
            board.send(payload, SendPurpose.AutoReply(BoardAddr(0x99u)))
            board.send(payload, SendPurpose.Admin(BoardAddr(0xA1u)))

            val k = link.sent[0]
            val dm = link.sent[1]
            val public = link.sent[2]
            val reply = link.sent[3]
            val admin = link.sent[4]
            assertEquals(listOf(2, 2, 0, 0, 0), link.sent.map { it.channel })
            assertEquals(
                listOf(
                    MeshtasticProto.PORT_PRIVATE_APP,
                    MeshtasticProto.PORT_PRIVATE_APP,
                    MeshtasticProto.PORT_TEXT_MESSAGE,
                    MeshtasticProto.PORT_TEXT_MESSAGE,
                    MeshtasticProto.PORT_ADMIN,
                ),
                link.sent.map { it.portnum },
            )
            assertEquals("hop_limit stated on every packet but the admin write", listOf(3, 3, 3, 3, null), link.sent.map { it.hopLimit })
            assertEquals(LinkDestination.Broadcast, k.destination)
            assertEquals("a compact DM asks for want_ack", LinkDestination.Direct(BoardAddr(0x1234u), ack = true), dm.destination)
            assertEquals(LinkDestination.Broadcast, public.destination)
            assertEquals("the auto-reply never asks for one", LinkDestination.Direct(BoardAddr(0x99u), ack = false), reply.destination)
            assertEquals(LinkDestination.Direct(BoardAddr(0xA1u), ack = false), admin.destination)
            assertEquals("the token is stamped before the write", 41L, knit.preSend)
            assertEquals(link.answer, knit.result)
        }

    @Test
    fun aBoardThisLinkCannotAddressIsRefusedUnwritten() =
        runTest {
            val other = BoardAddr.MeshCore(ByteArray(6) { it.toByte() })
            val sent = board.send(byteArrayOf(1), SendPurpose.DirectDm(2, other))
            assertTrue(link.sent.isEmpty())
            assertEquals(SendResult.Rejected(0u, MeshtasticBoardLink.UNADDRESSABLE), sent.result)
            assertEquals(RefusalClass.OTHER, BoardTraits.MESHTASTIC.refusalOf(sent.result))
        }

    @Test
    fun theStateIsALiveViewOfTheSessions() {
        assertEquals(BoardState.Down(TransportHealth.Unavailable, LinkState.Idle), board.state.value)
        val up = ready()
        link.state.value = up
        val state = board.state.value as BoardState.Ready
        assertEquals(BoardAddr(0xA1u), state.own)
        assertEquals("the MTU less the ToRadio framing", 255 - LoraMeshTransport.TORADIO_OVERHEAD, state.payloadCap)
        assertEquals(LoraMeshTransport.PRE_READY_PAYLOAD, state.payloadCap)
        assertEquals(MeshtasticTraits.radioParams(ModemPreset.MEDIUM_FAST), state.radio)
        assertEquals("the slot the table names for Knit", 2, state.channel)
        assertEquals(up, state.detail)
        assertEquals(up, board.state.value.meshtasticReady)
        link.state.value = ready(mtu = 517, radio = null)
        assertEquals(MeshtasticProto.MAX_PAYLOAD, (board.state.value as BoardState.Ready).payloadCap)
        assertNull((board.state.value as BoardState.Ready).radio)
        for (s in listOf(LinkState.Connecting, LinkState.Bonding, LinkState.Handshaking(null), LinkState.Disconnected("x", 0, 1))) {
            link.state.value = s
            assertEquals(BoardState.Down(TransportHealth.Degraded, s), board.state.value)
        }
        for (s in listOf(LinkState.Idle, LinkState.Unavailable, LinkState.NeedsPairing("AA"), LinkState.StaleBond("AA"))) {
            link.state.value = s
            assertEquals(BoardState.Down(TransportHealth.Unavailable, s), board.state.value)
        }
    }

    @Test
    fun aCollectorSeesEveryStateMapped() =
        runTest(UnconfinedTestDispatcher()) {
            val seen = mutableListOf<BoardState>()
            val job = launch { board.state.take(2).toList(seen) }
            link.state.value = ready()
            job.join()
            assertTrue(seen[0] is BoardState.Down)
            assertEquals(BoardAddr(0xA1u), (seen[1] as BoardState.Ready).own)
        }

    @Test
    fun anInboundPacketKeepsItsMeshtasticRecordAndNamesItsBoard() =
        runTest(UnconfinedTestDispatcher()) {
            link.state.value = ready(node = 0xA1u)
            val got = mutableListOf<BoardPacket>()
            val job = launch { board.packets.take(3).toList(got) }
            val toUs =
                ReceivedPacket(
                    0x1234u,
                    0xA1u,
                    5u,
                    0,
                    MeshtasticProto.PORT_PRIVATE_APP,
                    byteArrayOf(0x11),
                    6.5f,
                    -97,
                    1,
                    rxTime = 1_700_000_000u,
                )
            val broadcast =
                ReceivedPacket(
                    0x1234u,
                    MeshtasticProto.BROADCAST,
                    6u,
                    2,
                    MeshtasticProto.PORT_PRIVATE_APP,
                    byteArrayOf(3),
                    null,
                    null,
                    null,
                )
            val undecoded =
                ReceivedPacket(0u, MeshtasticProto.BROADCAST, 7u, 2, MeshtasticProto.PORT_PRIVATE_APP, byteArrayOf(3), null, null, null)
            link.packets.emit(toUs)
            link.packets.emit(broadcast)
            link.packets.emit(undecoded)
            job.join()
            val (a, b, c) = got
            assertEquals(BoardAddr(0x1234u), a.from)
            assertTrue(a.toUs)
            assertEquals(0, a.channel)
            assertEquals(6.5f, a.rxSnr)
            assertEquals(-97, a.rxRssi)
            assertEquals(1, a.hopsAway)
            assertEquals(1_700_000_000_000L, a.rxTimeMs)
            assertEquals(toUs, a.meshtastic)
            assertFalse(b.toUs)
            assertNull(b.rxTimeMs)
            assertNull("an undecoded `from` names no board", c.from)
        }

    @Test
    fun theHeadroomIsTheBoardsFreeCount() =
        runTest(UnconfinedTestDispatcher()) {
            assertNull(board.headroomNow)
            link.queue.value = QueueInfo(free = 4, maxlen = 16, atMs = 1)
            assertEquals(4, board.headroomNow)
            assertEquals(4, board.headroom.first())
        }

    /** A board's address round-trips: built from a node, keyed, addressed and read back off a packet as the same board. */
    @Test
    fun aBoardAddressRoundTrips() =
        runTest(UnconfinedTestDispatcher()) {
            val addr = BoardAddr(0x1234u)
            assertEquals(BoardAddr.Meshtastic(0x1234u), addr)
            assertEquals(0x1234u, addr.node)
            val neutral: BoardAddr = addr
            assertEquals(0x1234u, neutral.meshtasticNode)
            assertEquals("it keys a map", "bob", mapOf<BoardAddr, String>(BoardAddr(0x1234u) to "bob")[addr])
            link.state.value = ready()
            board.send(byteArrayOf(1), SendPurpose.DirectDm(2, addr))
            val written = (link.sent.single().destination as LinkDestination.Direct).addr
            assertEquals(0x1234u, written.node)
            val heard = launch { assertEquals(addr, board.packets.first().from) }
            link.packets.emit(ReceivedPacket(written.node, 0xA1u, 1u, 2, MeshtasticProto.PORT_PRIVATE_APP, byteArrayOf(1), null, -90, 0))
            heard.join()

            val prefix = ByteArray(BoardAddr.MeshCore.PREFIX_BYTES) { (it + 1).toByte() }
            val core = BoardAddr.MeshCore(prefix)
            prefix[0] = 9
            assertEquals("copied in", BoardAddr.MeshCore(ByteArray(6) { (it + 1).toByte() }), core)
            assertEquals(BoardAddr.MeshCore(ByteArray(6) { (it + 1).toByte() }).hashCode(), core.hashCode())
            assertNotEquals(BoardAddr(0x1234u), core)
            assertNull("only a Meshtastic board has a node", core.meshtasticNode)
            core.prefix[0] = 9
            assertEquals("copied out", 1.toByte(), core.prefix[0])
            assertTrue(runCatching { BoardAddr.MeshCore(ByteArray(5)) }.isFailure)
        }

    @Test
    fun theStatusHalfIsTheSessionsStateAndItsBoardsNumber() {
        val base = LoraStatus(queued = 3)
        assertEquals(base, MeshtasticHooks.statusOf(board.state.value, base))
        val up = ready(node = 0xBEEFu)
        link.state.value = up
        assertEquals(base.copy(state = up, boardNodeNum = 0xBEEFu), MeshtasticHooks.statusOf(board.state.value, base))
    }
}
