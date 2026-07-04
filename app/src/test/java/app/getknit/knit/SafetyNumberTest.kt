package app.getknit.knit

import app.getknit.knit.mesh.crypto.SafetyNumber
import app.getknit.knit.mesh.crypto.VerifyPayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SafetyNumberTest {
    @Test
    fun symmetricAcrossEndpoints() {
        // Each side feeds in (self, peer); both must derive the identical number.
        val alice = SafetyNumber.compute("alice000", "bundleA", "bob00000", "bundleB")
        val bob = SafetyNumber.compute("bob00000", "bundleB", "alice000", "bundleA")
        assertEquals(alice, bob)
    }

    @Test
    fun deterministic() {
        assertEquals(
            SafetyNumber.compute("a", "x", "b", "y"),
            SafetyNumber.compute("a", "x", "b", "y"),
        )
    }

    @Test
    fun differsWhenAKeyChanges() {
        val original = SafetyNumber.compute("alice000", "bundleA", "bob00000", "bundleB")
        val tampered = SafetyNumber.compute("alice000", "bundleA", "bob00000", "bundleEVIL")
        assertNotEquals(original, tampered)
    }

    @Test
    fun formatIsEightFiveDigitGroups() {
        val number = SafetyNumber.compute("a", "x", "b", "y")
        val groups = number.split(" ")
        assertEquals(8, groups.size)
        groups.forEach { group ->
            assertEquals(5, group.length)
            assertTrue(group.all { it.isDigit() })
        }
    }

    @Test
    fun verifyPayloadRoundTrips() {
        val encoded = VerifyPayload.encode("bob00000", "c29tZStiYXNlNjQv==")
        val parsed = VerifyPayload.parse(encoded)
        assertEquals("bob00000", parsed?.nodeId)
        assertEquals("c29tZStiYXNlNjQv==", parsed?.bundle)
    }

    @Test
    fun verifyPayloadRejectsGarbage() {
        assertNull(VerifyPayload.parse("https://example.com"))
        assertNull(VerifyPayload.parse("knit-id:v1:onlynodeid"))
    }
}
