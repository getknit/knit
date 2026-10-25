package app.getknit.knit.mesh.spool

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A spool that names its TURN servers (HELLO `turnHosts`, S-7.6-48) and the `host` a `turnreq` carries (S-7.6-49), on
 * one connection: what is kept of the list, what is sent, and that a spool naming none gets the record it always got.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpoolConnectionTurnHostsTest {
    private class RecordingLink : SpoolLink {
        val sent = mutableListOf<ByteArray>()

        override fun send(bytes: ByteArray): Boolean {
            sent.add(bytes)
            return true
        }

        override fun close(
            code: Int,
            reason: String,
        ) = Unit

        inline fun <reified T> last(): T = SpoolCodec.decode<T>(sent.last())!!
    }

    private fun connection(link: SpoolLink) = SpoolConnection("ws://spool/spool/v1", link, {}, {}, { _, _, _ -> })

    private fun serverHello(
        turn: Boolean? = null,
        turnHosts: List<String>? = null,
    ) = SpoolCodec.encode(
        SpoolHello(
            t = SpoolRecordType.HELLO,
            v = SPOOL_RECORD_VERSION,
            min = SPOOL_RECORD_VERSION,
            limits = SpoolLimits(maxBlob = 64, maxRecord = 4096, maxScopes = 8, maxPull = 2, maxFramesCap = 10, maxTtlMs = 1),
            powBits = 0,
            turn = turn,
            turnHosts = turnHosts,
        ),
    )

    @Test
    fun `a turnreq to a spool that names its servers carries the host asked for`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello(turn = true, turnHosts = listOf("a.example:3478", "[2001:db8::7]:3478")))
            assertEquals(listOf("a.example:3478", "[2001:db8::7]:3478"), conn.turnHosts)

            launch { conn.turnCredential(3_000, host = "[2001:db8::7]:3478") }
            runCurrent()
            assertEquals("[2001:db8::7]:3478", link.last<SpoolTurnReq>().host)

            // A name the spool did not list is never sent: the spool would answer from server 1 anyway (S-7.6-49).
            launch { conn.turnCredential(3_000, host = "elsewhere.example:3478") }
            runCurrent()
            assertNull(link.last<SpoolTurnReq>().host)
        }

    @Test
    fun `a turnreq to a spool that names none is the record it always was`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello(turn = true))
            assertTrue(conn.turnHosts.isEmpty())

            launch { conn.turnCredential(3_000, host = "a.example:3478") }
            runCurrent()
            // Byte for byte the §13 `turnreq` shape at its own q: `{t: "turnreq", q}`, no `host` key at all.
            val q = link.last<SpoolTurnReq>().q
            assertEquals(
                SpoolCodec.encode(SpoolTurnReq(t = SpoolRecordType.TURNREQ, q = q)).toList(),
                link.sent.last().toList(),
            )
        }

    @Test
    fun `a hellos turnHosts is bounded before anything holds it`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            // Junk, a repeat and more than four entries: the connection keeps four it could dial, each once.
            val hostile =
                listOf("a.example:3478", "not an entry", "a.example:3478", "b.example:3478", "turn:c.example:3478") +
                    (1..6).map { "s$it.example:3478" }
            conn.onMessage(serverHello(turn = true, turnHosts = hostile))
            assertEquals(listOf("a.example:3478", "b.example:3478", "s1.example:3478", "s2.example:3478"), conn.turnHosts)
        }

    @Test
    fun `turnHosts without turn is not read`() =
        runTest {
            val link = RecordingLink()
            val conn = connection(link)
            conn.onMessage(serverHello(turnHosts = listOf("a.example:3478")))
            assertTrue(conn.turnHosts.isEmpty())
        }
}
