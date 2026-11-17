package app.getknit.knit.mesh.lora.meshcore

import app.getknit.knit.mesh.lora.BoardSetupState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The radio screen's setup state (ADR 2026-11.gsqu's Stage 7 amendment, lora L9): never "none" for what was not read, and the
 * replace token's fingerprint, which moves with every field of the record.
 */
class MeshcoreSetupStatesTest {
    private val record =
        MeshcoreSetupRecord(1, slotWritten = true, priorName = "alice".encodeToByteArray(), priorManualAdd = 0, priorAutoadd = 7)
    private val mine = MeshcoreSetupEntry(record, DIGEST, pending = false)

    private fun of(
        read: Result<MeshcoreSetupEntry?>?,
        digest: String? = DIGEST,
        consent: Boolean = true,
        away: Boolean = false,
    ) = MeshcoreSetupStates.of(read, digest, consent, away)

    @Test
    fun eachReadMapsToOneStateAndUnknownIsNeverNone() {
        assertEquals(BoardSetupState.UNKNOWN, of(null))
        assertEquals(BoardSetupState.UNKNOWN, of(Result.failure(IllegalStateException())))
        assertEquals(BoardSetupState.NONE, of(Result.success(null)))
        assertEquals(BoardSetupState.SET_UP, of(Result.success(mine)))
        assertEquals("a proof not answered", BoardSetupState.UNKNOWN, of(Result.success(mine), consent = false))
        assertEquals("no digest read yet", BoardSetupState.UNKNOWN, of(Result.success(mine), digest = null))
        assertEquals("another key's record", BoardSetupState.NONE, of(Result.success(mine), digest = "other"))
        val pending = MeshcoreSetupEntry(record, DIGEST, pending = true)
        assertEquals(BoardSetupState.PENDING, of(Result.success(pending), consent = false))
        assertEquals(BoardSetupState.SET_UP, of(Result.success(mine), digest = null, consent = false, away = true))
        assertEquals(BoardSetupState.PENDING, of(Result.success(pending), digest = null, consent = false, away = true))
        assertEquals(BoardSetupState.NONE, of(Result.success(null), away = true))
    }

    @Test
    fun theFingerprintMovesWithEveryFieldAndTheTokenNamesNothing() {
        val print = mine.fingerprint()
        val variants =
            listOf(
                MeshcoreSetupEntry(record, "other", pending = false),
                MeshcoreSetupEntry(record, DIGEST, pending = true),
                MeshcoreSetupEntry(MeshcoreSetupRecord(2, true, "alice".encodeToByteArray(), 0, 7), DIGEST, false),
                MeshcoreSetupEntry(MeshcoreSetupRecord(1, false, "alice".encodeToByteArray(), 0, 7), DIGEST, false),
                MeshcoreSetupEntry(MeshcoreSetupRecord(1, true, "alicf".encodeToByteArray(), 0, 7), DIGEST, false),
                MeshcoreSetupEntry(MeshcoreSetupRecord(1, true, null, 0, 7), DIGEST, false),
                MeshcoreSetupEntry(MeshcoreSetupRecord(1, true, ByteArray(0), 0, 7), DIGEST, false),
                MeshcoreSetupEntry(MeshcoreSetupRecord(1, true, "alice".encodeToByteArray(), null, 7), DIGEST, false),
                MeshcoreSetupEntry(MeshcoreSetupRecord(1, true, "alice".encodeToByteArray(), 0, null), DIGEST, false),
            )
        val prints = variants.map { it.fingerprint().toList() }
        assertTrue("every field moves it", prints.none { it == print.toList() })
        assertEquals("and every variant is its own", prints.size, prints.toSet().size)
        val token = mine.tokenAt("AA:BB")
        assertTrue(token.matches("AA:BB", print))
        assertFalse(token.matches("AA:CC", print))
        assertEquals("ReplaceToken", token.toString())
    }

    private companion object {
        const val DIGEST = "0011223344556677"
    }
}
