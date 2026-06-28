package app.getknit.knit

import app.getknit.knit.identity.DeviceTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the soft block-continuity [DeviceTag]: a stable, key-independent tag so a blocked peer that
 * regenerates its keypair (new nodeId) is still recognised by the same device tag.
 */
class DeviceTagTest {

    private val format = Regex("^[0-9a-f]{16}$")

    @Test
    fun deriveIsDeterministicAndShaped() {
        repeat(1_000) {
            val raw = "android-id-$it"
            val tag = DeviceTag.derive(raw)!!
            assertEquals("same device id must yield the same tag", tag, DeviceTag.derive(raw))
            assertTrue("'$tag' must be 16 hex chars (64-bit)", format.matches(tag))
        }
    }

    @Test
    fun blankOrMissingDeviceIdYieldsNull() {
        assertNull(DeviceTag.derive(null))
        assertNull(DeviceTag.derive(""))
        assertNull(DeviceTag.derive("   "))
    }

    @Test
    fun distinctDeviceIdsYieldDistinctTags() {
        val tags = (1..5_000).map { DeviceTag.derive("device-$it") }.toSet()
        assertEquals(5_000, tags.size)
    }

    @Test
    fun tagIsNotTheRawDeviceId() {
        // Salted: the advertised tag is never literally the device identifier.
        val raw = "9774d56d682e549c"
        assertNotEquals(raw, DeviceTag.derive(raw))
    }
}
