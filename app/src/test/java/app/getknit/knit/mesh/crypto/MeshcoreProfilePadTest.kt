package app.getknit.knit.mesh.crypto

import app.getknit.knit.mesh.protocol.BadgeEntry
import app.getknit.knit.mesh.protocol.ProfilePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * One sealed profile version, one length (ADR 2026-11.gsqu's Stage 4 review): the keyless copy a stranger or a blocked
 * contact gets is padded to the keyed copy a direct contact gets, on both layouts and across the shapes a profile takes.
 */
class MeshcoreProfilePadTest {
    private fun profile(pr: ProfilePayload) = MessageContent(body = "", ctl = MessageContent.CTL_PROFILE, pr = pr)

    private val shapes =
        listOf(
            ProfilePayload(name = "A", status = "", version = 5L),
            ProfilePayload(name = "Ann", status = "hiking", avatarHash = "cd".repeat(32), version = 1_756_100_000_000L, openToChat = true),
            ProfilePayload(name = "x".repeat(40), status = "y".repeat(120), version = 1_756_100_000_000L, loraNode = 0xdeadbeefL),
            ProfilePayload(
                name = "Ann",
                status = "",
                version = 1_756_100_000_000L,
                loraNode = 7L,
                loraKey = KEY,
                badges = listOf(BadgeEntry(w = ByteArray(180) { 3 }, r = ByteArray(16))),
            ),
        )

    @Test
    fun aKeyedAndAPaddedKeylessCopyAreOneSealedLengthOnBothLayouts() {
        for (pr in shapes) {
            val keyless = profile(pr)
            val keyed = profile(pr.copy(meshcoreKey = KEY))
            for (v3 in listOf(true, false)) {
                val padded = MeshcoreProfilePad.padded(keyless, keyed, v3)
                assertEquals("v3=$v3 ${pr.name.take(4)}", keyed.sealBytes(v3).plaintext.size, padded.sealBytes(v3).plaintext.size)
                assertEquals(keyed.sealBytes(v3).scheme, padded.sealBytes(v3).scheme)
                assertNull("the padded copy carries no key", padded.pr?.meshcoreKey)
            }
        }
    }

    private companion object {
        /** RFC 8032 TEST 1's public key. */
        const val KEY = "11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo="
    }
}
