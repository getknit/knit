package app.getknit.knit

import app.getknit.knit.mesh.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Test

class ProtocolTest {
    @Test
    fun advertiseParseRoundTrips() {
        // A real 26-char base32 nodeId (contains no '|', so the split-on-'|' round-trips cleanly).
        val id = "ffbbh6thbepahqxsv2gqog45m4"
        val parsed = Protocol.parse(Protocol.advertise(id))
        assertEquals(id, parsed.nodeId)
        assertEquals(Protocol.VERSION, parsed.protoVersion)
        assertEquals(Protocol.LOCAL_CAPABILITIES, parsed.capabilities)
    }

    @Test
    fun bareLegacyNodeIdParsesToUnknownVersion() {
        // A peer that advertises just its nodeId (a pre-protocol build) is "unknown" — version 0, no caps.
        val parsed = Protocol.parse("abcd1234")
        assertEquals("abcd1234", parsed.nodeId)
        assertEquals(0, parsed.protoVersion)
        assertEquals(0L, parsed.capabilities)
    }

    @Test
    fun parseNeverThrowsOnGarbageSegments() {
        val parsed = Protocol.parse("abcd1234|notanint|zzz")
        assertEquals("abcd1234", parsed.nodeId)
        assertEquals(0, parsed.protoVersion)
        assertEquals(0L, parsed.capabilities)
    }

    @Test
    fun nodeIdIsAlwaysTheFirstSegment() {
        // Robust to any future suffix appended after the capabilities field.
        assertEquals("abcd1234", Protocol.parse("abcd1234|1|f|future|stuff").nodeId)
    }
}
