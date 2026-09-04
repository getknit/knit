package app.getknit.knit.mesh.lora

import app.getknit.knit.TextLimits
import app.getknit.knit.mesh.meshNodeLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LongFast bridge's inbound filter. Every case here is really the same question — *is this packet public*
 * — asked of a different field, because everything narrower than the stock primary on its default key is
 * somebody's private business and must never reach a Knit room.
 */
class LongFastPolicyTest {
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
    ) = ReceivedPacket(
        from = from,
        to = to,
        id = id,
        channelIndex = LongFastPolicy.PRIMARY_INDEX,
        portnum = portnum,
        payload = body.encodeToByteArray(),
        rxSnr = rxSnr,
        rxRssi = -95,
        hopsAway = hopsAway,
        viaMqtt = viaMqtt,
    )

    private fun judge(
        packet: ReceivedPacket = packet(),
        channels: List<ChannelInfo> = listOf(stockPrimary, knitSecondary),
        config: LoraRadioConfig? = radio,
        name: String? = null,
    ) = LongFastPolicy.judge(packet, channels, config, name)

    private fun post() = (judge() as LongFastPolicy.Verdict.Post).post

    private fun refusal(
        packet: ReceivedPacket = packet(),
        channels: List<ChannelInfo> = listOf(stockPrimary, knitSecondary),
        config: LoraRadioConfig? = radio,
    ) = (judge(packet, channels, config) as LongFastPolicy.Verdict.Refused).reason

    @Test
    fun `a broadcast text on the stock primary is a public post`() {
        val post = post()
        assertEquals(0x1234abcdL, post.node)
        assertEquals(42L, post.packetId)
        assertEquals("hi from the mesh", post.body)
        assertEquals(2, post.hops)
        // -7.3 dB in tenths — the wire carries an Int, so the encoding pins byte-exactly where a float
        // would not, and a tenth of a dB is well inside what the volume measurement can use.
        assertEquals(-73, post.snrDeci)
    }

    @Test
    fun `the name the caller looked up rides along, blank treated as absent`() {
        assertEquals("Bob", (judge(name = "Bob") as LongFastPolicy.Verdict.Post).post.name)
        assertNull((judge(name = "   ") as LongFastPolicy.Verdict.Post).post.name)
        assertNull(post().name)
    }

    @Test
    fun `the channel name comes from the preset when the primary is unnamed`() {
        assertEquals("LongFast", post().channel)
        val named = listOf(stockPrimary.copy(name = "LongFast"), knitSecondary)
        assertEquals("LongFast", (judge(channels = named) as LongFastPolicy.Verdict.Post).post.channel)
    }

    @Test
    fun `a renamed primary is somebody's own channel, not the public one`() {
        val renamed = listOf(stockPrimary.copy(name = "BookClub"), knitSecondary)
        assertEquals(LongFastPolicy.Refusal.NOT_STOCK_PRIMARY, refusal(channels = renamed))
    }

    @Test
    fun `a primary that kept the stock name but changed its key is private too`() {
        // The name half alone is not enough: a group can leave the name and re-key, and then only its own
        // members can read the channel. Nothing about that is public.
        val rekeyed = listOf(stockPrimary.copy(psk = ByteArray(16) { 7 }), knitSecondary)
        assertEquals(LongFastPolicy.Refusal.NOT_STOCK_PRIMARY, refusal(channels = rekeyed))
    }

    @Test
    fun `the one-byte default key shorthand is the default key`() {
        val shorthand = listOf(stockPrimary.copy(psk = byteArrayOf(1)), knitSecondary)
        assertTrue(judge(channels = shorthand) is LongFastPolicy.Verdict.Post)
    }

    @Test
    fun `a board reporting no channels or no radio settings is refused, not guessed at`() {
        // The opposite reading from LoraMeshTransport.boundSlotIsKnit, which admits an empty table: going
        // mute on unreadable firmware is the worse failure there, and ingesting a stranger's private channel
        // is the worse failure here.
        assertEquals(LongFastPolicy.Refusal.NOT_STOCK_PRIMARY, refusal(channels = emptyList()))
        assertEquals(LongFastPolicy.Refusal.NOT_STOCK_PRIMARY, refusal(config = null))
    }

    @Test
    fun `a unicast text addressed to the board is not public`() {
        assertEquals(LongFastPolicy.Refusal.NOT_BROADCAST, refusal(packet(to = 0x99u)))
    }

    @Test
    fun `only chat is read, never position or telemetry or Knit's own traffic`() {
        assertEquals(LongFastPolicy.Refusal.NOT_TEXT, refusal(packet(portnum = MeshtasticProto.PORT_TELEMETRY)))
        assertEquals(LongFastPolicy.Refusal.NOT_TEXT, refusal(packet(portnum = MeshtasticProto.PORT_NODEINFO)))
        assertEquals(LongFastPolicy.Refusal.NOT_TEXT, refusal(packet(portnum = MeshtasticProto.PORT_PRIVATE_APP)))
    }

    @Test
    fun `an empty or whitespace-only body is nothing to show`() {
        assertEquals(LongFastPolicy.Refusal.EMPTY_BODY, refusal(packet(body = "")))
        assertEquals(LongFastPolicy.Refusal.EMPTY_BODY, refusal(packet(body = "   \n ")))
    }

    @Test
    fun `a packet with no id cannot mint a deterministic frame id`() {
        assertEquals(LongFastPolicy.Refusal.NO_PACKET_ID, refusal(packet(id = 0u)))
    }

    @Test
    fun `a body from an open channel is clamped like any other inbound text`() {
        val long = "x".repeat(TextLimits.MESSAGE * 2)
        assertEquals(TextLimits.MESSAGE, (judge(packet(body = long)) as LongFastPolicy.Verdict.Post).post.body.length)
    }

    @Test
    fun `via_mqtt is carried, never filtered`() {
        // Measuring how much of a neighbourhood's LongFast arrives off the internet is the point of the
        // receive-only phase; deciding to hide it comes after that number, not before.
        assertTrue((judge(packet(viaMqtt = true)) as LongFastPolicy.Verdict.Post).post.viaMqtt)
    }

    @Test
    fun `a missing snr or hop count stays missing rather than becoming zero`() {
        val post = (judge(packet(rxSnr = null, hopsAway = null)) as LongFastPolicy.Verdict.Post).post
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
}
