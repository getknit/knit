package app.getknit.knit

import app.getknit.knit.mesh.isValidBlobHash
import app.getknit.knit.mesh.sha256Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the blob content-address validation that backs the path-traversal and cache-poisoning
 * defences (`MeshBlobStore`, `WifiAwareTransport.finalizeIncomingFile`, `MeshManager.onAvatarReceived`).
 */
class BlobHashTest {

    private val validHash = "a".repeat(64)

    @Test
    fun acceptsA64CharLowercaseHexAddress() {
        assertTrue(isValidBlobHash(validHash))
        assertTrue(isValidBlobHash(sha256Hex("hello".toByteArray())))
    }

    @Test
    fun rejectsPathTraversalSequences() {
        // The exact primitive a malicious peer would use to escape the cache/transfer directory.
        assertFalse(isValidBlobHash("../../files/db.key"))
        assertFalse(isValidBlobHash("../" + validHash))
        assertFalse(isValidBlobHash("a/".repeat(32)))
    }

    @Test
    fun rejectsWrongLengthCasingOrCharset() {
        assertFalse(isValidBlobHash(""))
        assertFalse(isValidBlobHash("a".repeat(63)))
        assertFalse(isValidBlobHash("a".repeat(65)))
        assertFalse(isValidBlobHash("A".repeat(64))) // uppercase hex is not the canonical form
        assertFalse(isValidBlobHash("g".repeat(64))) // non-hex char
    }

    @Test
    fun sha256HexMatchesAKnownVector() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".toByteArray()),
        )
    }
}
