package app.getknit.knit.mesh.crypto.scope

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The sig seal (spec §7.8, C-7.8-12): the cast's construction under its own version byte, nonce label and aad, on
 * [ScopeVectorTest]'s DM fixture. Its vector is `sigSeal`, pinned there with the spec's §13; this class holds the
 * properties a vector cannot show — what never opens as a sig, and which plaintexts the opener refuses.
 */
class SigSealTest {
    private fun bytes(
        n: Int,
        seed: Int,
    ) = ByteArray(n) { ((it * 7 + seed) and 0xFF).toByte() }

    private val keys = ScopeCrypto.dmSealKeys(bytes(32, 1), NODE_A, NODE_B)
    private val scopeId = ScopeCrypto.dmScopeId(bytes(32, 1), NODE_A, NODE_B)
    private val sig = bytes(64, 4)
    private val signed = bytes(40, 5)

    @Test
    fun aSigOpensBackToItsFrameInOnePaddedBlock() {
        val sealed = ScopeCrypto.sealSig(keys, scopeId, sig, signed)
        assertEquals(ScopeCrypto.SIG_SEAL_VERSION, sealed[0])
        assertEquals(1 + 12 + 128 + 16, sealed.size)
        val opened = ScopeCrypto.openSig(keys, scopeId, sealed)
        assertArrayEquals(sig, opened.sig)
        assertArrayEquals(signed, opened.signed)
        assertArrayEquals("deterministic, for §4.3's reason", sealed, ScopeCrypto.sealSig(keys, scopeId, sig, signed))
    }

    /** A sig, a cast, a frame blob and an attachment chunk never open as one another, whatever their bytes. */
    @Test
    fun noObjectClassOpensAsASigNorASigAsAnother() {
        val sigSealed = ScopeCrypto.sealSig(keys, scopeId, sig, signed)
        val cast = ScopeCrypto.sealCast(keys, scopeId, sig, signed)
        val blob = ScopeCrypto.seal(keys, scopeId, sig, signed)
        val chunk = ScopeCrypto.sealChunk(keys, scopeId, bytes(32, 6), index = 0, total = 1, chunk = signed)
        refused { ScopeCrypto.openSig(keys, scopeId, cast) }
        refused { ScopeCrypto.openSig(keys, scopeId, blob) }
        refused { ScopeCrypto.openSig(keys, scopeId, chunk) }
        refused { ScopeCrypto.openCast(keys, scopeId, sigSealed) }
        refused { ScopeCrypto.open(keys, scopeId, sigSealed) }
        refused { ScopeCrypto.openChunk(keys, scopeId, sigSealed) }
        // The version byte alone is not what keeps a sig and a cast apart: relabelled, each still fails the other's aad.
        refused { ScopeCrypto.openSig(keys, scopeId, byteArrayOf(ScopeCrypto.SIG_SEAL_VERSION) + cast.copyOfRange(1, cast.size)) }
        val relabelled = byteArrayOf(ScopeCrypto.CAST_SEAL_VERSION) + sigSealed.copyOfRange(1, sigSealed.size)
        refused { ScopeCrypto.openCast(keys, scopeId, relabelled) }
    }

    @Test
    fun aSigOpensOnlyInItsOwnScopeAndUntampered() {
        val sealed = ScopeCrypto.sealSig(keys, scopeId, sig, signed)
        refused { ScopeCrypto.openSig(keys, bytes(32, 9), sealed) }
        val tampered = sealed.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        refused { ScopeCrypto.openSig(keys, scopeId, tampered) }
    }

    /** C-7.8-12, by C-7.7-12's rule: whole blocks, a nonzero length inside them, at most one block of zero padding. */
    @Test
    fun aPlaintextThatIsNotCanonicalIsRefused() {
        val body = sig + byteArrayOf(0, signed.size.toByte()) + signed
        refused { ScopeCrypto.openSig(keys, scopeId, sealRaw(body)) } // not padded to a block
        refused { ScopeCrypto.openSig(keys, scopeId, sealRaw(body + ByteArray(22) + ByteArray(128))) } // a block too many
        refused { ScopeCrypto.openSig(keys, scopeId, sealRaw(body + ByteArray(21) + byteArrayOf(1))) } // padding not zero
        val long = sig + byteArrayOf(0, 100) + signed
        refused { ScopeCrypto.openSig(keys, scopeId, sealRaw(long + ByteArray(128 - long.size))) } // a length past the end
        val empty = sig + byteArrayOf(0, 0)
        refused { ScopeCrypto.openSig(keys, scopeId, sealRaw(empty + ByteArray(128 - empty.size))) } // nothing carried
        // And the canonical one does open, through the same hand-rolled seal.
        assertArrayEquals(signed, ScopeCrypto.openSig(keys, scopeId, sealRaw(body + ByteArray(22))).signed)
    }

    @Test
    fun theSealRefusesWhatCannotBeSent() {
        refused { ScopeCrypto.sealSig(keys, scopeId, ByteArray(0), signed) }
        refused { ScopeCrypto.sealSig(keys, scopeId, sig, ByteArray(0)) }
        refused { ScopeCrypto.sealSig(keys, scopeId, sig, ByteArray(0x10000)) }
    }

    /** The sig seal over an arbitrary plaintext, so the opener's canonical-form checks can be reached at all. */
    private fun sealRaw(pt: ByteArray): ByteArray {
        val nonce = ByteArray(12) { 7 }
        val cipher =
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.sealKey, "AES"), GCMParameterSpec(128, nonce))
                updateAAD("knit/scope/v1/sig".toByteArray() + scopeId)
            }
        return byteArrayOf(ScopeCrypto.SIG_SEAL_VERSION) + nonce + cipher.doFinal(pt)
    }

    private fun refused(block: () -> Unit) {
        val outcome = runCatching(block)
        assertTrue("expected a refusal, got ${outcome.getOrNull()}", outcome.isFailure)
    }

    private companion object {
        const val NODE_A = "aaaaabbbbbcccccdddddeeeeef"
        const val NODE_B = "zzzzzyyyyyxxxxxwwwwwvvvvvu"
    }
}
