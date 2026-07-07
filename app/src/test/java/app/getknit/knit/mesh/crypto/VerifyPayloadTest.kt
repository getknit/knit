package app.getknit.knit.mesh.crypto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VerifyPayloadTest {
    @Test
    fun `encode then parse round-trips node id and bundle`() {
        val parsed = VerifyPayload.parse(VerifyPayload.encode("node1234", "YmFzZTY0Kw=="))
        assertEquals("node1234", parsed?.nodeId)
        assertEquals("YmFzZTY0Kw==", parsed?.bundle)
    }

    @Test
    fun `input without the knit-id prefix is rejected`() {
        assertNull(VerifyPayload.parse("https://example.com"))
        assertNull(VerifyPayload.parse("node1234:YmFzZTY0"))
    }

    @Test
    fun `a missing bundle segment is rejected`() {
        // No third ':' at all — the bundle resolves to blank.
        assertNull(VerifyPayload.parse("knit-id:v1:onlynodeid"))
        // Trailing ':' with nothing after it — bundle is blank.
        assertNull(VerifyPayload.parse("knit-id:v1:node1234:"))
    }

    @Test
    fun `a blank node id is rejected`() {
        assertNull(VerifyPayload.parse("knit-id:v1::YmFzZTY0"))
    }

    @Test
    fun `extra colons after the node id are kept as part of the bundle`() {
        // The first three ':' delimit the fields; the base64 bundle alphabet has no ':',
        // but the parser must not choke if extra ones appear — they belong to the bundle.
        val parsed = VerifyPayload.parse("knit-id:v1:node1234:a:b:c")
        assertEquals("node1234", parsed?.nodeId)
        assertEquals("a:b:c", parsed?.bundle)
    }
}
