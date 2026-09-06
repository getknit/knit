package app.getknit.knit.mesh.lora

import app.getknit.knit.TextLimits
import app.getknit.knit.mesh.meshNodeLabel
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Meshtastic room's inbound filter. Every case here is really one question — *is this packet a post on
 * the board's own primary* — asked of a different field. Slot 0 is mirrored as the user configured it, so
 * the filters are about what a post is, never about whether the channel is the stock one.
 */
class PublicChannelPolicyTest {
    private val stockPrimary = ChannelInfo(index = 0, name = "", role = 1)
    private val knitSecondary = ChannelInfo(index = 1, name = KnitChannel.NAME, role = 2, psk = ByteArray(16))
    private val radio =
        LoraRadioConfig(
            usePreset = true,
            modemPreset = ModemPreset.LONG_FAST,
            region = LoraRegion.US,
            hopLimit = 3,
            overrideDutyCycle = false,
        )

    private fun packet(
        from: UInt = 0x1234abcdu,
        to: UInt = MeshtasticProto.BROADCAST,
        id: UInt = 42u,
        portnum: Int = MeshtasticProto.PORT_TEXT_MESSAGE,
        body: String = "hi from the mesh",
        hopsAway: Int? = 2,
        rxSnr: Float? = -7.3f,
        viaMqtt: Boolean = false,
        signature: ByteArray? = null,
        boardVerified: Boolean = false,
    ) = ReceivedPacket(
        from = from,
        to = to,
        id = id,
        channelIndex = PublicChannelPolicy.PRIMARY_INDEX,
        portnum = portnum,
        payload = body.encodeToByteArray(),
        rxSnr = rxSnr,
        rxRssi = -95,
        hopsAway = hopsAway,
        viaMqtt = viaMqtt,
        signature = signature,
        boardVerified = boardVerified,
    )

    private fun judge(
        packet: ReceivedPacket = packet(),
        channels: List<ChannelInfo> = listOf(stockPrimary, knitSecondary),
        config: LoraRadioConfig? = radio,
        name: String? = null,
    ) = PublicChannelPolicy.judge(packet, channels, config, name)

    private fun post(
        channels: List<ChannelInfo> = listOf(stockPrimary, knitSecondary),
        config: LoraRadioConfig? = radio,
    ) = (judge(channels = channels, config = config) as PublicChannelPolicy.Verdict.Post).post

    private fun refusal(
        packet: ReceivedPacket = packet(),
        channels: List<ChannelInfo> = listOf(stockPrimary, knitSecondary),
        config: LoraRadioConfig? = radio,
    ) = (judge(packet, channels, config) as PublicChannelPolicy.Verdict.Refused).reason

    @Test
    fun `a broadcast text on the primary is a post`() {
        val post = post()
        assertEquals(0x1234abcdL, post.node)
        assertEquals(42L, post.packetId)
        assertEquals("hi from the mesh", post.body)
        assertEquals(2, post.hops)
        // -7.3 dB in tenths — the row stores an Int, and a tenth of a dB is well inside what anybody reads.
        assertEquals(-73, post.snrDeci)
    }

    @Test
    fun `the name the caller looked up rides along, blank treated as absent`() {
        assertEquals("Bob", (judge(name = "Bob") as PublicChannelPolicy.Verdict.Post).post.name)
        assertNull((judge(name = "   ") as PublicChannelPolicy.Verdict.Post).post.name)
        assertNull(post().name)
    }

    @Test
    fun `the channel name comes from the preset when the primary is unnamed`() {
        assertEquals("LongFast", post().channel)
        assertEquals("MediumFast", post(config = radio.copy(modemPreset = ModemPreset.MEDIUM_FAST)).channel)
        val named = listOf(stockPrimary.copy(name = "LongFast"), knitSecondary)
        assertEquals("LongFast", post(channels = named).channel)
    }

    @Test
    fun `a renamed or re-keyed primary is mirrored under its own name`() {
        // The user's own channel, on the user's own board, shown to the user — and nothing heard here ever
        // leaves the phone, so there is no room a private channel could leak into.
        val renamed = listOf(stockPrimary.copy(name = "BookClub"), knitSecondary)
        assertEquals("BookClub", post(channels = renamed).channel)
        val rekeyed = listOf(stockPrimary.copy(psk = ByteArray(16) { 7 }), knitSecondary)
        assertEquals("LongFast", post(channels = rekeyed).channel)
    }

    @Test
    fun `an explicit name is reported before the radio settings arrive`() {
        // The channel table lands in the handshake ahead of the radio config; a name the user gave slot 0 is
        // known on its own, and only an unnamed primary needs the preset to be called anything.
        val named = listOf(stockPrimary.copy(name = "Ridge"), knitSecondary)
        assertEquals("Ridge", post(channels = named, config = null).channel)
        assertNull(post(config = null).channel)
    }

    @Test
    fun `a board reporting no channel table still mirrors slot 0`() {
        // The opposite reading from the first design, which refused an unknown table because it flooded what
        // it read to a whole pocket. Nothing leaves the phone now, so going blind on unreadable firmware is
        // the worse failure — and a board with no table cannot have Knit at slot 0 either.
        assertTrue(judge(channels = emptyList(), config = null) is PublicChannelPolicy.Verdict.Post)
    }

    @Test
    fun `slot 0 that is the Knit channel itself is refused`() {
        // The lab shape: the debug bridge bound index 0 and set it up. Slot 0 then carries Knit's own frames
        // and there is no primary to mirror. Decided off the table, never off the bound index, which defaults
        // to 0 on a board that never ran the setup.
        val knitAtZero = listOf(ChannelInfo(index = 0, name = KnitChannel.NAME, role = 1, psk = KnitChannel.PSK))
        assertEquals(PublicChannelPolicy.Refusal.KNIT_ON_PRIMARY, refusal(channels = knitAtZero))
        assertTrue(PublicChannelPolicy.isKnitPrimary(knitAtZero))
        assertTrue(!PublicChannelPolicy.isKnitPrimary(listOf(stockPrimary, knitSecondary)))
        assertTrue(!PublicChannelPolicy.isKnitPrimary(emptyList()))
    }

    @Test
    fun `a unicast text addressed to the board is not a post`() {
        assertEquals(PublicChannelPolicy.Refusal.NOT_BROADCAST, refusal(packet(to = 0x99u)))
    }

    @Test
    fun `only chat is read, never position or telemetry or Knit's own traffic`() {
        assertEquals(PublicChannelPolicy.Refusal.NOT_TEXT, refusal(packet(portnum = MeshtasticProto.PORT_TELEMETRY)))
        assertEquals(PublicChannelPolicy.Refusal.NOT_TEXT, refusal(packet(portnum = MeshtasticProto.PORT_NODEINFO)))
        assertEquals(PublicChannelPolicy.Refusal.NOT_TEXT, refusal(packet(portnum = MeshtasticProto.PORT_PRIVATE_APP)))
    }

    @Test
    fun `an empty or whitespace-only body is nothing to show`() {
        assertEquals(PublicChannelPolicy.Refusal.EMPTY_BODY, refusal(packet(body = "")))
        assertEquals(PublicChannelPolicy.Refusal.EMPTY_BODY, refusal(packet(body = "   \n ")))
    }

    @Test
    fun `a packet with no id cannot derive a row id`() {
        assertEquals(PublicChannelPolicy.Refusal.NO_PACKET_ID, refusal(packet(id = 0u)))
    }

    @Test
    fun `a body from an open channel is clamped like any other inbound text`() {
        val long = "x".repeat(TextLimits.MESSAGE * 2)
        assertEquals(TextLimits.MESSAGE, (judge(packet(body = long)) as PublicChannelPolicy.Verdict.Post).post.body.length)
    }

    @Test
    fun `via_mqtt is carried, never filtered`() {
        // The room says where a post came from rather than hiding it.
        assertTrue((judge(packet(viaMqtt = true)) as PublicChannelPolicy.Verdict.Post).post.viaMqtt)
    }

    @Test
    fun `a missing snr or hop count stays missing rather than becoming zero`() {
        val post = (judge(packet(rxSnr = null, hopsAway = null)) as PublicChannelPolicy.Verdict.Post).post
        assertNull(post.snrDeci)
        assertNull(post.hops)
    }

    @Test
    fun `a node number is rendered the way every Meshtastic client renders it`() {
        assertEquals("!1234abcd", meshNodeLabel(0x1234abcdL))
        // Zero-padded to eight digits, and a widened unsigned value never prints sixteen.
        assertEquals("!0000000f", meshNodeLabel(15L))
        assertEquals("!ffffffff", meshNodeLabel(0xFFFFFFFFL))
    }

    @Test
    fun `a slot 0 on the published default key reads as public, and a real key does not`() {
        // Which of the room's two privacy notices it gets. The firmware's psk encoding is the whole test:
        // absent and one-byte are the published default family, 16 and 32 bytes are a key somebody chose.
        assertTrue("absent: the firmware substitutes its own default", PublicChannelPolicy.primaryKeyIsPublic(listOf(stockPrimary)))
        assertTrue(
            "the one-byte default, as `initDefaultChannel` writes it",
            PublicChannelPolicy.primaryKeyIsPublic(listOf(stockPrimary.copy(psk = byteArrayOf(1)))),
        )
        assertTrue(
            "one byte 0 is no encryption at all, which is not the private case either",
            PublicChannelPolicy.primaryKeyIsPublic(listOf(stockPrimary.copy(psk = byteArrayOf(0)))),
        )
        assertFalse(
            "16 bytes is a key somebody chose",
            PublicChannelPolicy.primaryKeyIsPublic(listOf(stockPrimary.copy(psk = ByteArray(16) { 7 }))),
        )
        assertFalse("and so is 32", PublicChannelPolicy.primaryKeyIsPublic(listOf(stockPrimary.copy(psk = ByteArray(32) { 7 }))))
        assertTrue("no table to read answers with the safe half of the claim", PublicChannelPolicy.primaryKeyIsPublic(emptyList()))
        assertTrue(
            "and so does a board reporting only its secondaries",
            PublicChannelPolicy.primaryKeyIsPublic(listOf(knitSecondary)),
        )
    }

    @Test
    fun `a post carries the signature and verdict it arrived with, over the bytes as heard`() {
        // The body is trimmed for reading; the signature is over the payload byte for byte, so both ride.
        val sig = ByteArray(64) { it.toByte() }
        val verdict = judge(packet(body = "  hi from the mesh  ", signature = sig, boardVerified = true))
        val post = (verdict as PublicChannelPolicy.Verdict.Post).post
        assertEquals("hi from the mesh", post.body)
        assertArrayEquals("  hi from the mesh  ".encodeToByteArray(), post.payload)
        assertArrayEquals(sig, post.signature)
        assertTrue(post.boardVerified)
        val unsigned = (judge(packet()) as PublicChannelPolicy.Verdict.Post).post
        assertNull(unsigned.signature)
        assertFalse(unsigned.boardVerified)
    }
}
