package app.getknit.knit.mesh.spool

import app.getknit.knit.mesh.crypto.scope.ScopeCrypto
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A HELLO's `rooms` (spec §7.4) as the connection reads it: every listed room held to the ceilings `commons` is. */
class SpoolConnectionRoomsTest {
    private class SilentLink : SpoolLink {
        override fun send(bytes: ByteArray): Boolean = true

        override fun close(
            code: Int,
            reason: String,
        ) = Unit
    }

    @Test
    fun `each listed room is clamped like the commons it sits beside`() =
        runTest {
            val conn = SpoolConnection("ws://spool/spool/v1", SilentLink(), {}, {}, { _, _, _ -> })
            val home = ScopeCrypto.commonsScopeId(ByteArray(32) { 1 })
            val team = ScopeCrypto.commonsScopeId(ByteArray(32) { 2 })
            conn.onMessage(
                SpoolCodec.encode(
                    SpoolHello(
                        t = SpoolRecordType.HELLO,
                        v = SPOOL_RECORD_VERSION,
                        limits = SpoolLimits(maxBlob = 64, maxRecord = 4096, maxScopes = 8, maxPull = 2, maxFramesCap = 10, maxTtlMs = 1),
                        commons = SpoolCommonsInfo(name = "Home", maxFrames = 5, ttlMs = 10L, maxBlob = 32),
                        rooms =
                            listOf(
                                SpoolCommonsRoom(ScopeCrypto.commonsTag(home), "Home", maxFrames = 5, ttlMs = 10L, maxBlob = 32),
                                SpoolCommonsRoom(
                                    ScopeCrypto.commonsTag(team),
                                    " ",
                                    maxFrames = Int.MAX_VALUE,
                                    ttlMs = 0L,
                                    maxBlob =
                                        1 shl 20,
                                ),
                            ),
                    ),
                ),
            )

            assertTrue(conn.awaitReady())
            val adverts = conn.adverts!!
            assertEquals(32, adverts.advertForScope(home).info?.maxBlob)
            val second = adverts.advertForScope(team).info!!
            assertNull("a blank name is no name", second.name)
            assertEquals(MAX_PINNED_FRAMES, second.maxFrames)
            assertEquals(1L, second.ttlMs)
            assertEquals("a room's blob cap cannot exceed the spool's", 64, second.maxBlob)
        }
}
