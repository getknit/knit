package app.getknit.knit.mesh.spool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A TURN server's entry (docs/SPOOL_PROTOCOL.md §7.6, "Several TURN servers"), read from its URLs and from a HELLO. */
class TurnHostsTest {
    @Test
    fun theSpecsExamplesGiveTheirEntries() {
        // The two examples the spec and the contract give, verbatim.
        assertEquals("us-west.turn.getknit.app:3478", TurnHosts.udpEntry("turn:US-West.turn.getknit.app?transport=udp"))
        assertEquals("[2001:db8::7]:3479", TurnHosts.udpEntry("turn:[2001:DB8::7]:3479"))
    }

    @Test
    fun aServersEntryIsItsFirstUdpUrls() {
        // `turns:` and `transport=tcp` are never the UDP URL; an absent transport is UDP (RFC 7065 §3).
        val urls =
            listOf(
                "turns:box.example:443?transport=tcp",
                "turn:box.example:3478?transport=tcp",
                "turn:box.example:3480",
                "turn:box.example:3478?transport=udp",
            )
        assertEquals("box.example:3480", TurnHosts.entryOf(urls))
        assertNull(TurnHosts.entryOf(listOf("turns:box.example:5349?transport=tcp")))
        assertNull(TurnHosts.entryOf(emptyList()))
        // ABNF literals ignore case, so `UDP` is UDP; `turns:` is TLS whatever its transport says.
        assertEquals("box.example:3478", TurnHosts.entryOf(listOf("turn:box.example?transport=UDP")))
        assertNull(TurnHosts.entryOf(listOf("turns:box.example:5349?transport=udp")))
    }

    @Test
    fun theEntryIsDerivedAsTheSpoolDerivesIt() {
        // knit-spool's TurnUrisTest at ee3497bd (feat/nearest-turn): the port as written, not as a number, and a URI
        // with no readable host names none. A credential's entry must equal the one its spool's HELLO listed.
        assertEquals("203.0.113.5:03478", TurnHosts.udpEntry("turn:203.0.113.5:03478"))
        assertEquals("[2001:db8::7]:3478", TurnHosts.udpEntry("turn:[2001:db8::7]?transport=udp"))
        listOf(
            "turn:?transport=udp",
            "turn::3478",
            "turn:[2001:db8::7",
            "turn:[]:3478",
            "turn:a.example:",
            "turn:a.example:34x8",
            "turn:a:b:c",
        ).forEach {
            assertNull(it, TurnHosts.udpEntry(it))
            assertNull(it, TurnHosts.urlHost(it))
        }
    }

    @Test
    fun aHellosEntryParsesOnlyInTheFormASpoolSends() {
        val v4 = TurnHosts.parse("spool.example:3478")!!
        assertEquals("spool.example", v4.host)
        assertEquals(3478, v4.port)
        val v6 = TurnHosts.parse("[2001:db8::7]:3478")!!
        assertEquals("2001:db8::7", v6.host)
        assertEquals(3478, v6.port)
        // The port as written still dials as its number.
        assertEquals(3478, TurnHosts.parse("spool.example:03478")!!.port)
        // No port, an upper-case host, a port no socket takes, a URI, a bare IPv6 literal, a path: none is an entry.
        listOf(
            "spool.example",
            "Spool.example:3478",
            "spool.example:0",
            "spool.example:65536",
            "turn:spool.example:3478",
            "2001:db8::7:3478",
            "spool.example:3478/x",
            "user@spool.example:3478",
            "",
            "a".repeat(300) + ":3478",
        ).forEach { assertNull(it, TurnHosts.parse(it)) }
    }

    @Test
    fun aRefusalsUrlNamesItsHostWhateverItsTransport() {
        // libwebrtc rebuilds the server's URL for a candidate error as `scheme:host:port?transport=…`.
        assertEquals("box.example", TurnHosts.urlHost("turns:Box.example:443?transport=tcp"))
        assertEquals("[2001:db8::7]", TurnHosts.urlHost("turn:[2001:db8::7]:3478?transport=udp"))
        assertNull(TurnHosts.urlHost("stun:box.example:3478"))
    }
}
