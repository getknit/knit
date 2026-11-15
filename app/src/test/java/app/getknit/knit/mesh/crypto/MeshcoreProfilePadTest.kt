package app.getknit.knit.mesh.crypto

import app.getknit.knit.mesh.protocol.BadgeEntry
import app.getknit.knit.mesh.protocol.ProfilePayload
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One sealed profile version, one length (ADR 2026-11.gsqu's Stage 4 review): the keyless copy a stranger or a blocked
 * contact gets is padded to the keyed copy a direct contact gets (the key and its bind signature, Stage 5d), on both
 * layouts and across the shapes a profile takes.
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
            // A direct contact's copy carries the key and its 64-byte bind signature (Stage 5d), both or neither.
            val keyed = profile(pr.copy(meshcoreKey = KEY, meshcoreSig = SIG))
            for (v3 in listOf(true, false)) {
                val padded = checkNotNull(MeshcoreProfilePad.padded(keyless, keyed, v3)) { "v3=$v3: the pad missed" }
                assertEquals("v3=$v3 ${pr.name.take(4)}", keyed.sealBytes(v3).plaintext.size, padded.sealBytes(v3).plaintext.size)
                assertEquals(keyed.sealBytes(v3).scheme, padded.sealBytes(v3).scheme)
                assertNull("the padded copy carries no key", padded.pr?.meshcoreKey)
                assertNull("nor the signature", padded.pr?.meshcoreSig)
            }
        }
    }

    /** Crypto B1 (Stage 5d): the named layout's gap with the signature is past the old bound of 128, and inside the new. */
    @Test
    fun theNamedLayoutsSignedGapNeedsMoreThanTheOldBound() {
        val keyless = profile(shapes.first())
        val keyed = profile(shapes.first().copy(meshcoreKey = KEY, meshcoreSig = SIG))
        val pad = checkNotNull(checkNotNull(MeshcoreProfilePad.padded(keyless, keyed, v3 = false)).pad)
        assertTrue("pad $pad", pad in 129..MeshcoreProfilePad.MAX_PAD)
    }

    /** A miss is loud: no pad reaching the keyed length answers null, never the bare keyless copy as if padded. */
    @Test
    fun aGapPastTheBoundIsAMissNotASilentKeylessCopy() {
        val keyless = profile(shapes.first())
        val keyed = profile(shapes.first().copy(status = "s".repeat(400)))
        for (v3 in listOf(true, false)) assertNull("v3=$v3", MeshcoreProfilePad.padded(keyless, keyed, v3))
    }

    private companion object {
        /** RFC 8032 TEST 1's public key. */
        const val KEY = "11qYAYKxCrfVS/7TyWQHOg7hcvPapiMlrwIaaPcHURo="

        /** A 64-byte signature's canonical base64: the pad measures its length, never its validity. */
        val SIG: String = b64(ByteArray(MeshcoreBind.SIG_BYTES) { 0x5a })
    }
}
