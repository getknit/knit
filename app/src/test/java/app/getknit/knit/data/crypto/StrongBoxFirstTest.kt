package app.getknit.knit.data.crypto

import android.security.keystore.StrongBoxUnavailableException
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLog
import java.security.KeyStoreException
import java.security.ProviderException

/**
 * [StrongBoxFirst]'s rule (ADR 2026-10.47rw's 2026-11-19 amendment, #351): a key is asked of the StrongBox first, and
 * **any** exception from that attempt, not only "this phone has no StrongBox", clears the alias and asks the TEE. The
 * case it exists for is a Xiaomi 14 whose StrongBox answered every generation with a plain `ProviderException`.
 * Robolectric only for the log: the rule itself touches no Keystore.
 */
@RunWith(AndroidJUnit4::class)
class StrongBoxFirstTest {
    /** What was asked, in order: `strongbox`, `delete`, `tee`. */
    private val asked = mutableListOf<String>()

    private fun generate(
        strongBox: () -> String = { "a StrongBox key" },
        tee: () -> String = { "a TEE key" },
        delete: () -> Unit = {},
    ): String =
        StrongBoxFirst.generate(
            LABEL,
            { wanted ->
                asked += if (wanted) "strongbox" else "tee"
                if (wanted) strongBox() else tee()
            },
            {
                asked += "delete"
                delete()
            },
        )

    /** The Xiaomi 14's refusal: keystore2's UNIMPLEMENTED under a plain `ProviderException`. */
    private fun refusal() = ProviderException("Keystore key generation failed", IllegalStateException("Not implemented"))

    private fun warnings() = ShadowLog.getLogsForTag("StrongBoxFirst").filter { it.type == Log.WARN }

    @Test
    fun aWorkingStrongBoxKeyIsKept() {
        assertEquals("a StrongBox key", generate())
        assertEquals(listOf("strongbox"), asked)
        assertTrue(warnings().isEmpty())
    }

    @Test
    fun anyStrongBoxRefusalDeletesThenMakesATeeKey() {
        val refusal = refusal()
        assertEquals("a TEE key", generate(strongBox = { throw refusal }))
        assertEquals(listOf("strongbox", "delete", "tee"), asked)

        val warning = warnings().single()
        assertTrue(warning.msg, LABEL in warning.msg)
        assertSame(refusal, warning.throwable)
    }

    @Test
    fun noStrongBoxFallsBackQuietly() {
        assertEquals("a TEE key", generate(strongBox = { throw StrongBoxUnavailableException("Failed to generate key") }))
        assertEquals(listOf("strongbox", "delete", "tee"), asked)
        assertTrue("most phones have none: nothing to warn about", warnings().isEmpty())
    }

    @Test
    fun aFailedDeleteStillTriesTheTee() {
        assertEquals(
            "a TEE key",
            generate(strongBox = { throw refusal() }, delete = { throw KeyStoreException("Failed to delete entry") }),
        )
        assertEquals(listOf("strongbox", "delete", "tee"), asked)
    }

    @Test
    fun aTeeFailureThrowsItsOwnWithTheStrongBoxOneSuppressed() {
        val strongBox = refusal()
        val tee = ProviderException("Keystore key generation failed")
        val thrown = assertThrows(ProviderException::class.java) { generate(strongBox = { throw strongBox }, tee = { throw tee }) }
        assertSame(tee, thrown)
        assertSame(strongBox, thrown.suppressed.single())
    }

    @Test
    fun oneThrowableFromBothBackingsIsNotSuppressedIntoItself() {
        val both = refusal()
        val thrown = assertThrows(ProviderException::class.java) { generate(strongBox = { throw both }, tee = { throw both }) }
        assertSame(both, thrown)
        assertTrue(thrown.suppressed.isEmpty())
    }

    @Test
    fun anErrorIsNotAReasonToAskTheTee() {
        assertThrows(LinkageError::class.java) { generate(strongBox = { throw LinkageError("not the StrongBox's doing") }) }
        assertEquals(listOf("strongbox"), asked)
    }

    private companion object {
        const val LABEL = "test.key"
    }
}
