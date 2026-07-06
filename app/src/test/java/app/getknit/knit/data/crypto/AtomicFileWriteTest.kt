package app.getknit.knit.data.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Unit tests for [writeBytesAtomically] — the atomic (temp + fsync + rename) key-file write behind
 * [DatabaseKey]/[KeystoreSecret] (ARCHITECTURE_REVIEW.md item #12). The helper is deliberately
 * Android-free (only `java.io`/`java.nio`), so it runs on the plain JVM without Robolectric or an
 * AndroidKeyStore provider — the previously-untestable slice of the key-file write path.
 */
class AtomicFileWriteTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `writes bytes that read back exactly`() {
        val target = File(tmp.root, "key")
        val bytes = byteArrayOf(1, 2, 3, 4, 5)

        target.writeBytesAtomically(bytes)

        assertArrayEquals(bytes, target.readBytes())
    }

    @Test
    fun `overwrites an existing file wholesale`() {
        val target = File(tmp.root, "key")
        val v1 = ByteArray(64) { 0x11 }
        val v2 = ByteArray(8) { 0x22 } // shorter — a truncating writer would leave v1's tail behind

        target.writeBytesAtomically(v1)
        target.writeBytesAtomically(v2)

        assertArrayEquals(v2, target.readBytes())
    }

    @Test
    fun `leaves no temp sibling after a successful write`() {
        val target = File(tmp.root, "key")

        target.writeBytesAtomically(byteArrayOf(9, 8, 7))

        assertFalse("stale .tmp should not remain", File(tmp.root, "key.tmp").exists())
        assertTrue(target.exists())
    }

    @Test
    fun `a stale temp sibling does not corrupt the result`() {
        val target = File(tmp.root, "key")
        // Simulate a prior crash that left a partial .tmp behind.
        File(tmp.root, "key.tmp").writeBytes(ByteArray(100) { 0x7F })
        val bytes = byteArrayOf(4, 2)

        target.writeBytesAtomically(bytes)

        assertArrayEquals(bytes, target.readBytes())
        assertFalse(File(tmp.root, "key.tmp").exists())
    }
}
