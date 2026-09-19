package app.getknit.knit.mesh.lora

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The DM auto-reply's rule. Two questions — *is this a message to our board* and *has the sender (or
 * anybody) heard the answer too recently* — each asked of a different field or clock, plus the memory that
 * carries the second across a restart.
 */
class DmAutoReplyPolicyTest {
    private val ownNode = 0x0badcafeu
    private var now = 1_000_000L
    private val policy = DmAutoReplyPolicy(clock = { now })

    private fun packet(
        from: UInt = 0x1234abcdu,
        to: UInt = ownNode,
        id: UInt = 42u,
        portnum: Int = MeshtasticProto.PORT_TEXT_MESSAGE,
        channelIndex: Int = PublicChannelPolicy.PRIMARY_INDEX,
        body: String = "anyone there?",
    ) = ReceivedPacket(
        from = from,
        to = to,
        id = id,
        channelIndex = channelIndex,
        portnum = portnum,
        payload = body.encodeToByteArray(),
        rxSnr = -7.3f,
        rxRssi = -95,
        hopsAway = 2,
    )

    private fun refusal(packet: ReceivedPacket): DmAutoReplyPolicy.Refusal =
        (policy.judge(packet, ownNode) as DmAutoReplyPolicy.Verdict.Refused).reason

    private fun reply(packet: ReceivedPacket): DmAutoReplyPolicy.Verdict.Reply =
        policy.judge(packet, ownNode) as DmAutoReplyPolicy.Verdict.Reply

    @Test
    fun `a text addressed to the board is answered, to its sender, with the fixed line`() {
        val reply = reply(packet(from = 0x1234abcdu))
        assertEquals(0x1234abcdu, reply.to)
        assertEquals(DmAutoReplyPolicy.TEXT, reply.text)
    }

    @Test
    fun `the line fits every client and every packet whole`() {
        val bytes = DmAutoReplyPolicy.TEXT.encodeToByteArray()
        assertTrue("under the 200-byte client convention: ${bytes.size}", bytes.size <= PublicPostPolicy.MAX_ON_AIR_BYTES)
        assertTrue("ASCII only, for the OLEDs", DmAutoReplyPolicy.TEXT.all { it.code < 0x80 })
        assertTrue("it says where Knit lives", DmAutoReplyPolicy.TEXT.contains("https://getknit.app"))
    }

    @Test
    fun `a broadcast is the room's business, not a DM`() {
        assertEquals(DmAutoReplyPolicy.Refusal.NOT_FOR_US, refusal(packet(to = MeshtasticProto.BROADCAST)))
    }

    @Test
    fun `a text addressed to some other node is not ours to answer`() {
        assertEquals(DmAutoReplyPolicy.Refusal.NOT_FOR_US, refusal(packet(to = 0x99u)))
    }

    @Test
    fun `our own board's text is never answered`() {
        assertEquals(DmAutoReplyPolicy.Refusal.OWN_BOARD, refusal(packet(from = ownNode)))
    }

    @Test
    fun `a unicast that is not chat is not answered`() {
        assertEquals(DmAutoReplyPolicy.Refusal.NOT_TEXT, refusal(packet(portnum = MeshtasticProto.PORT_PRIVATE_APP)))
    }

    @Test
    fun `a text with no packet id is not answered`() {
        assertEquals(DmAutoReplyPolicy.Refusal.NO_PACKET_ID, refusal(packet(id = 0u)))
    }

    @Test
    fun `the channel the DM was decoded on does not matter`() {
        // A 2.5+ board reports a PKI DM on index 0 whatever slot the sender used; an older one reports the
        // slot it decrypted on. Both are the same message to the same board.
        assertEquals(0x1234abcdu, reply(packet(channelIndex = 3)).to)
    }

    @Test
    fun `a sender hears the answer once a day, however many times they write`() {
        reply(packet(id = 1u))
        now += DmAutoReplyPolicy.FLOOR_MS
        assertEquals(DmAutoReplyPolicy.Refusal.REPLIED_RECENTLY, refusal(packet(id = 2u)))
        now += DmAutoReplyPolicy.PER_SENDER_MS - DmAutoReplyPolicy.FLOOR_MS - 1
        assertEquals("still inside the day", DmAutoReplyPolicy.Refusal.REPLIED_RECENTLY, refusal(packet(id = 3u)))
        now += 1
        assertEquals("the day is up", 0x1234abcdu, reply(packet(id = 4u)).to)
    }

    @Test
    fun `the board's reconnect replay of an answered message is not answered again`() {
        reply(packet(id = 7u))
        now += DmAutoReplyPolicy.FLOOR_MS
        assertEquals(DmAutoReplyPolicy.Refusal.REPLIED_RECENTLY, refusal(packet(id = 7u)))
    }

    @Test
    fun `a burst from several senders is one reply per floor`() {
        reply(packet(from = 0x1u, id = 1u))
        assertEquals(DmAutoReplyPolicy.Refusal.TOO_SOON, refusal(packet(from = 0x2u, id = 2u)))
        now += DmAutoReplyPolicy.FLOOR_MS - 1
        assertEquals(DmAutoReplyPolicy.Refusal.TOO_SOON, refusal(packet(from = 0x2u, id = 3u)))
        now += 1
        assertEquals(0x2u, reply(packet(from = 0x2u, id = 4u)).to)
    }

    @Test
    fun `a sender refused for the floor has not had their answer`() {
        // The floor is asked before the sender is stamped, so the second sender's next message — or the
        // board's replay of this one — still earns the reply once the floor has passed.
        reply(packet(from = 0x1u, id = 1u))
        refusal(packet(from = 0x2u, id = 2u))
        now += DmAutoReplyPolicy.FLOOR_MS
        assertEquals(0x2u, reply(packet(from = 0x2u, id = 2u)).to)
    }

    @Test
    fun `a refusal on the packet moves neither cap`() {
        refusal(packet(to = MeshtasticProto.BROADCAST))
        refusal(packet(from = ownNode))
        assertEquals("no floor was claimed", 0x1234abcdu, reply(packet()).to)
    }

    @Test
    fun `the senders answered are what a snapshot carries, and a restored set still refuses them`() {
        reply(packet(from = 0x1u, id = 1u))
        now += DmAutoReplyPolicy.FLOOR_MS
        reply(packet(from = 0x2u, id = 2u))
        val stamps = policy.stamps()
        assertEquals(listOf(DmAutoReplyPolicy.senderKey(0x1u), DmAutoReplyPolicy.senderKey(0x2u)), stamps.map { it.first })

        // A fresh process an hour on, with the same clock domain.
        now += 60 * 60_000L
        val restarted = DmAutoReplyPolicy(clock = { now })
        restarted.restore(stamps)
        assertEquals(
            DmAutoReplyPolicy.Refusal.REPLIED_RECENTLY,
            (restarted.judge(packet(from = 0x1u, id = 9u), ownNode) as DmAutoReplyPolicy.Verdict.Refused).reason,
        )
        assertEquals(
            "a stranger is still answered",
            0x3u,
            (restarted.judge(packet(from = 0x3u, id = 10u), ownNode) as DmAutoReplyPolicy.Verdict.Reply).to,
        )
    }

    @Test
    fun `a stamp past the window is not restored`() {
        reply(packet(from = 0x1u, id = 1u))
        val stamps = policy.stamps()
        now += DmAutoReplyPolicy.PER_SENDER_MS
        val restarted = DmAutoReplyPolicy(clock = { now })
        restarted.restore(stamps)
        assertEquals(0x1u, (restarted.judge(packet(from = 0x1u, id = 2u), ownNode) as DmAutoReplyPolicy.Verdict.Reply).to)
    }

    @Test
    fun `the memory is bounded, and a sender pushed out of it is answered again`() {
        val small = DmAutoReplyPolicy(clock = { now }, maxSenders = 2)
        small.judge(packet(from = 0x1u, id = 1u), ownNode)
        now += DmAutoReplyPolicy.FLOOR_MS
        small.judge(packet(from = 0x2u, id = 2u), ownNode)
        now += DmAutoReplyPolicy.FLOOR_MS
        small.judge(packet(from = 0x3u, id = 3u), ownNode) // evicts 0x1
        now += DmAutoReplyPolicy.FLOOR_MS
        assertTrue(small.judge(packet(from = 0x1u, id = 4u), ownNode) is DmAutoReplyPolicy.Verdict.Reply)
    }
}
