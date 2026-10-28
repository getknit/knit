package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.spool.SpoolTicketsTest.Companion.ticket
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One connection's side of sigs (spec §7.8): the hello's bounds, held to our own ceilings (C-7.8-9); when a sig may
 * leave at all; the local budget that stands in for a spool which drops silently; that no ticket enters into it
 * (S-7.8-4); and the inbound record. A sig is never a request: no `q`, no pending slot, no strike.
 */
class SpoolConnectionSigTest {
    private class RecordingLink : SpoolLink {
        val sent = mutableListOf<ByteArray>()
        var queue = 0L

        override fun send(bytes: ByteArray): Boolean {
            sent.add(bytes)
            return true
        }

        override fun close(
            code: Int,
            reason: String,
        ) = Unit

        override fun queued(): Long = queue

        fun sigs(): List<SpoolSig> =
            sent.filter { SpoolCodec.peekType(it) == SpoolRecordType.SIG }.map { SpoolCodec.decode<SpoolSig>(it)!! }
    }

    private val scope = ByteArray(32) { it.toByte() }
    private val other = ByteArray(32) { (it + 1).toByte() }
    private val data = ByteArray(285) { 5 }

    // Inside the test tickets' window, 1800000000..1800086400 (Unix seconds).
    private var nowMs = 1_800_000_100_000L
    private val heard = mutableListOf<SpoolSig>()
    private val heardCasts = mutableListOf<SpoolCast>()

    private fun connection(link: SpoolLink) =
        SpoolConnection(
            "wss://lab.example/spool/v1",
            link,
            onDigest = {},
            onEvent = {},
            clock = { nowMs },
            onCast = { heardCasts += it },
            onSig = { heard += it },
        )

    private fun serverHello(
        sig: SpoolSigInfo? = SpoolSigInfo(maxBytes = 1_024, rate = 1),
        cast: SpoolCastInfo? = null,
        ent: Boolean? = null,
        entGates: Long? = null,
        maxRecord: Int = 4_096,
    ) = SpoolCodec.encode(
        SpoolHello(
            t = SpoolRecordType.HELLO,
            v = SPOOL_RECORD_VERSION,
            min = SPOOL_RECORD_VERSION,
            limits = SpoolLimits(maxBlob = 64, maxRecord = maxRecord, maxScopes = 8, maxPull = 2, maxFramesCap = 10, maxTtlMs = 1),
            ent = ent,
            cast = cast,
            entGates = entGates,
            sig = sig,
        ),
    )

    private suspend fun ready(
        link: RecordingLink,
        hello: ByteArray = serverHello(),
    ): SpoolConnection =
        connection(link).also {
            it.onMessage(hello)
            it.sub(listOf(ScopeSub(scope = scope, bounds = ScopeRegistry.DEFAULT_BOUNDS)))
        }

    @Test
    fun `the hello's sig bounds are read and held to what a record can carry`() =
        runTest {
            val conn = connection(RecordingLink())
            conn.onMessage(serverHello(sig = SpoolSigInfo(maxBytes = 1_000_000, rate = 10_000), maxRecord = 2_048))
            assertEquals(2_048, conn.sig?.maxBytes)
            assertEquals(SpoolConnection.MAX_CAST_RATE, conn.sig?.rate)
            val none = connection(RecordingLink())
            none.onMessage(serverHello(sig = null))
            assertNull(none.sig)
            // S-7.8-1: rate 0 is the same as no `sig` at all.
            val off = connection(RecordingLink())
            off.onMessage(serverHello(sig = SpoolSigInfo(maxBytes = 1_024, rate = 0)))
            assertNull(off.sig)
        }

    @Test
    fun `a sig leaves only to a spool that advertised sigs, after the hello, for a subscribed scope`() =
        runTest {
            val older = RecordingLink()
            assertFalse(ready(older, serverHello(sig = null)).sigOut(scope, data))
            assertTrue("a spool that never advertised sigs is sent none", older.sigs().isEmpty())
            val castOnly = ready(RecordingLink(), serverHello(sig = null, cast = SpoolCastInfo(maxBytes = 2_048, rate = 4)))
            assertFalse("casts advertised are not sigs advertised", castOnly.sigOut(scope, data))

            val link = RecordingLink()
            val before = connection(link)
            assertFalse("nothing before the hello", before.sigOut(scope, data))

            val conn = ready(link)
            assertFalse("a scope this connection never subscribed", conn.sigOut(other, data))
            assertFalse("over the advertised maxBytes", conn.sigOut(scope, ByteArray(1_025)))
            assertTrue(conn.sigOut(scope, data))
            val sent = link.sigs().single()
            assertArrayEquals(scope, sent.scope)
            assertArrayEquals(data, sent.data)
            // Exactly the record, and so no `q`: nothing is waited on.
            assertArrayEquals(SpoolCodec.encode(SpoolSig(t = SpoolRecordType.SIG, scope = scope, data = data)), link.sent.last())
        }

    @Test
    fun `the advertised rate is kept here, because the spool drops past it without a word`() =
        runTest {
            val link = RecordingLink()
            val conn = ready(link)
            // Rate 1, burst 4: four go, the fifth waits for the bucket.
            repeat(4) { assertTrue(conn.sigOut(scope, data)) }
            assertFalse(conn.sigOut(scope, data))
            nowMs += 1_000
            assertTrue(conn.sigOut(scope, data))
            assertEquals(5, link.sigs().size)
            // However many were refused, the connection is as it was: no strike, no abort.
            repeat(SpoolConnection.MAX_SILENT_REQUESTS + 2) { conn.sigOut(scope, data) }
            assertTrue(conn.isReady)
            assertNull(conn.fault)
        }

    /** S-7.8-3: the two lanes' budgets are apart, so a walkie talking never spends the typing cues' bucket. */
    @Test
    fun `the sig budget is apart from the cast budget`() =
        runTest {
            val link = RecordingLink()
            val conn = ready(link, serverHello(cast = SpoolCastInfo(maxBytes = 2_048, rate = 1)))
            repeat(4) { assertTrue(conn.castOut(scope, data, peerTicketed = false)) }
            assertFalse("the cast bucket is spent", conn.castOut(scope, data, peerTicketed = false))
            assertTrue("and the sig bucket is not", conn.sigOut(scope, data))
        }

    @Test
    fun `a sig that would wait behind a blob is skipped`() =
        runTest {
            val link = RecordingLink()
            val conn = ready(link)
            link.queue = SpoolConnection.CAST_QUEUE_LIMIT + 1
            assertFalse(conn.sigOut(scope, data))
            link.queue = 0
            assertTrue(conn.sigOut(scope, data))
        }

    /** S-7.8-4: whatever the spool's `ent` and `entGates` say, and whatever ticket this phone holds, a sig leaves. */
    @Test
    fun `no ticket gates a sig`() =
        runTest {
            val link = RecordingLink()
            val conn = ready(link, serverHello(ent = true, entGates = SpoolTicket.LEGACY_GATES or SpoolTicket.FL_FILES))
            assertTrue("no ticket held here at all", conn.sigOut(scope, data))
            conn.present(ticket(fl = SpoolTicket.FL_TURN))
            assertTrue("nor one with any flag the sig might be read under", conn.sigOut(scope, data))
            assertEquals(2, link.sigs().size)
        }

    @Test
    fun `an inbound sig reaches its callback and nothing else`() =
        runTest {
            val conn = ready(RecordingLink())
            conn.onMessage(SpoolCodec.encode(SpoolSig(t = SpoolRecordType.SIG, scope = scope, data = data)))
            assertArrayEquals(data, heard.single().data)
            assertTrue("a sig is not a cast", heardCasts.isEmpty())
            conn.onMessage(byteArrayOf(0xa1.toByte(), 0x61, 0x74, 0x63) + "sig".toByteArray())
            assertEquals("an undecodable sig is dropped", 1, heard.size)
            assertTrue(conn.isReady)
        }
}
