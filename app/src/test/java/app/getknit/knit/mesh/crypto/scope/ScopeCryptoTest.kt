package app.getknit.knit.mesh.crypto.scope

import app.getknit.knit.mesh.crypto.ratchet.RatchetCrypto
import com.google.crypto.tink.subtle.X25519
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.GeneralSecurityException
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Behavior anchors for [ScopeCrypto]. Every derivation is cross-checked against [referenceHkdf] — a
 * from-scratch RFC 5869 HKDF on bare [javax.crypto.Mac], never Tink validating Tink — and the seal's
 * two load-bearing properties are proven directly: determinism (any member seals a frame to the same
 * blob) and scope binding (a blob replanted into another scope fails to open). Byte-exact spec
 * vectors are pinned separately in [ScopeVectorTest]; docs/SPOOL_PROTOCOL.md is the normative spec.
 */
class ScopeCryptoTest {
    @Test
    fun dmScopeIdMatchesTheReferenceAndIsOrderInsensitive() {
        val root = deterministicBytes(1)

        val id = ScopeCrypto.dmScopeId(root, NODE_A, NODE_B)

        val info = "knit/scope/v1/dm/id".toByteArray() + "$NODE_A|$NODE_B|".toByteArray()
        assertArrayEquals(referenceHkdf(root, ByteArray(32), info, 32), id)
        assertArrayEquals(id, ScopeCrypto.dmScopeId(root, NODE_B, NODE_A))
        assertEquals(ScopeCrypto.SCOPE_ID_BYTES, id.size)
    }

    @Test
    fun dmSealKeysMatchTheReferenceAndDifferFromTheScopeId() {
        val root = deterministicBytes(1)

        val keys = ScopeCrypto.dmSealKeys(root, NODE_B, NODE_A)

        val info = "knit/scope/v1/seal".toByteArray() + "$NODE_A|$NODE_B|".toByteArray()
        val okm = referenceHkdf(root, ByteArray(32), info, 64)
        assertArrayEquals(okm.copyOfRange(0, 32), keys.sealKey)
        assertArrayEquals(okm.copyOfRange(32, 64), keys.nonceKey)
        assertFalse(keys.sealKey.contentEquals(ScopeCrypto.dmScopeId(root, NODE_A, NODE_B)))
        assertFalse(keys.sealKey.contentEquals(keys.nonceKey))
    }

    @Test
    fun groupDerivationsBindGroupIdAndRootVersion() {
        val root = deterministicBytes(2)

        val id = ScopeCrypto.groupScopeId(root, GROUP_ID, rootVersion = 1)

        val info = "knit/scope/v1/group/id".toByteArray() + "$GROUP_ID|".toByteArray() + byteArrayOf(0, 0, 0, 1)
        assertArrayEquals(referenceHkdf(root, ByteArray(32), info, 32), id)
        assertFalse(id.contentEquals(ScopeCrypto.groupScopeId(root, GROUP_ID, rootVersion = 2)))
        assertFalse(id.contentEquals(ScopeCrypto.groupScopeId(root, "g-ffeeddccbbaa99887766554433", rootVersion = 1)))
        assertFalse(
            ScopeCrypto.groupSealKeys(root, GROUP_ID, rootVersion = 1).sealKey.contentEquals(
                ScopeCrypto.groupSealKeys(root, GROUP_ID, rootVersion = 2).sealKey,
            ),
        )
    }

    @Test
    fun pairScopeIdIsSymmetricMatchesTheReferenceAndNeverCoincidesWithTheDmScope() {
        val alicePriv = deterministicBytes(6)
        val bobPriv = deterministicBytes(7)
        val alicePub = X25519.publicFromPrivate(alicePriv)
        val bobPub = X25519.publicFromPrivate(bobPriv)

        val aliceSecret = ScopeCrypto.pairSecret(alicePriv, bobPub)
        val bobSecret = ScopeCrypto.pairSecret(bobPriv, alicePub)
        assertArrayEquals("both parties agree on the pair secret", aliceSecret, bobSecret)

        val id = ScopeCrypto.pairScopeId(aliceSecret, NODE_A, NODE_B)
        val info = "knit/scope/v1/pair/id".toByteArray() + "$NODE_A|$NODE_B|".toByteArray()
        assertArrayEquals(referenceHkdf(aliceSecret, ByteArray(32), info, 32), id)
        assertArrayEquals(id, ScopeCrypto.pairScopeId(bobSecret, NODE_B, NODE_A))
        // Same context as the DM scope, different ikm and label: the two scopes of a pair never coincide.
        assertFalse(id.contentEquals(ScopeCrypto.dmScopeId(aliceSecret, NODE_A, NODE_B)))
        val keys = ScopeCrypto.pairSealKeys(aliceSecret, NODE_A, NODE_B)
        val sealInfo = "knit/scope/v1/seal".toByteArray() + "$NODE_A|$NODE_B|".toByteArray()
        assertArrayEquals(referenceHkdf(aliceSecret, ByteArray(32), sealInfo, 64).copyOfRange(0, 32), keys.sealKey)
        assertFalse(keys.sealKey.contentEquals(ScopeCrypto.dmSealKeys(deterministicBytes(1), NODE_A, NODE_B).sealKey))
    }

    @Test
    fun derivationsStayDomainSeparatedFromTheRatchetExports() {
        val root = deterministicBytes(3)

        assertFalse(ScopeCrypto.dmScopeId(root, NODE_A, NODE_B).contentEquals(RatchetCrypto.exportRoot(root)))
        assertFalse(ScopeCrypto.dmScopeId(root, NODE_A, NODE_B).contentEquals(RatchetCrypto.exportEpochSeal(root)))
        assertFalse(ScopeCrypto.dmSealKeys(root, NODE_A, NODE_B).sealKey.contentEquals(RatchetCrypto.exportRoot(root)))
        assertFalse(ScopeCrypto.groupScopeId(root, GROUP_ID, 1).contentEquals(ScopeCrypto.dmScopeId(root, NODE_A, NODE_B)))
    }

    @Test
    fun sealIsDeterministicAndRoundTrips() {
        val keys = ScopeCrypto.dmSealKeys(deterministicBytes(1), NODE_A, NODE_B)
        val scopeId = ScopeCrypto.dmScopeId(deterministicBytes(1), NODE_A, NODE_B)
        val sig = deterministicBytes(4, 64)
        val signed = deterministicBytes(5, 40)

        val blob = ScopeCrypto.seal(keys, scopeId, sig, signed)

        assertArrayEquals(blob, ScopeCrypto.seal(keys, scopeId, sig, signed))
        assertArrayEquals(ScopeCrypto.blobId(blob), ScopeCrypto.blobId(ScopeCrypto.seal(keys, scopeId, sig, signed)))
        val opened = ScopeCrypto.open(keys, scopeId, blob)
        assertArrayEquals(sig, opened.sig)
        assertArrayEquals(signed, opened.signed)
    }

    @Test
    fun sealedNonceMatchesTheReferenceDerivation() {
        val keys = ScopeCrypto.dmSealKeys(deterministicBytes(1), NODE_A, NODE_B)
        val scopeId = ScopeCrypto.dmScopeId(deterministicBytes(1), NODE_A, NODE_B)
        val sig = deterministicBytes(4, 64)
        val signed = deterministicBytes(5, 40)

        val blob = ScopeCrypto.seal(keys, scopeId, sig, signed)

        val ptHash = MessageDigest.getInstance("SHA-256").digest(sig + signed)
        val nonce = referenceHkdf(keys.nonceKey, ByteArray(32), "knit/scope/v1/nonce".toByteArray() + ptHash, 12)
        assertEquals(ScopeCrypto.SEAL_VERSION, blob[0])
        assertArrayEquals(nonce, blob.copyOfRange(1, 1 + ScopeCrypto.NONCE_BYTES))
    }

    @Test
    fun openRejectsTamperWrongScopeAndWrongVersion() {
        val keys = ScopeCrypto.dmSealKeys(deterministicBytes(1), NODE_A, NODE_B)
        val scopeId = ScopeCrypto.dmScopeId(deterministicBytes(1), NODE_A, NODE_B)
        val blob = ScopeCrypto.seal(keys, scopeId, deterministicBytes(4, 64), deterministicBytes(5, 40))

        val tampered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) { ScopeCrypto.open(keys, scopeId, tampered) }

        val otherScope = ScopeCrypto.dmScopeId(deterministicBytes(2), NODE_A, NODE_B)
        assertThrows(GeneralSecurityException::class.java) { ScopeCrypto.open(keys, otherScope, blob) }

        val wrongVersion = blob.copyOf().also { it[0] = 2 }
        assertThrows(IllegalArgumentException::class.java) { ScopeCrypto.open(keys, scopeId, wrongVersion) }
    }

    @Test
    fun attachmentIdMatchesTheReferenceAndIsKeyedByTheScope() {
        val keys = ScopeCrypto.dmSealKeys(deterministicBytes(1), NODE_A, NODE_B)
        val scopeId = ScopeCrypto.dmScopeId(deterministicBytes(1), NODE_A, NODE_B)
        val aHash = deterministicBytes(7)

        val aid = ScopeCrypto.attachmentId(keys, scopeId, aHash)

        val info = "knit/scope/v1/aid".toByteArray() + scopeId + aHash
        assertArrayEquals(referenceHkdf(keys.nonceKey, ByteArray(32), info, 32), aid)
        assertEquals(ScopeCrypto.ATTACH_ID_BYTES, aid.size)
        // The whole point of keying it: aHash is public on the mesh, the aid must not be recomputable.
        assertFalse(aid.contentEquals(aHash))
        val otherKeys = ScopeCrypto.dmSealKeys(deterministicBytes(2), NODE_A, NODE_B)
        val otherScope = ScopeCrypto.dmScopeId(deterministicBytes(2), NODE_A, NODE_B)
        assertFalse(aid.contentEquals(ScopeCrypto.attachmentId(otherKeys, otherScope, aHash)))
        assertFalse(aid.contentEquals(ScopeCrypto.attachmentId(keys, scopeId, deterministicBytes(8))))
    }

    @Test
    fun chunkSealIsDeterministicAndRoundTrips() {
        val keys = ScopeCrypto.dmSealKeys(deterministicBytes(1), NODE_A, NODE_B)
        val scopeId = ScopeCrypto.dmScopeId(deterministicBytes(1), NODE_A, NODE_B)
        val aHash = deterministicBytes(7)
        val data = deterministicBytes(9, 300)

        val blob = ScopeCrypto.sealChunk(keys, scopeId, aHash, index = 2, total = 5, chunk = data)

        assertArrayEquals(blob, ScopeCrypto.sealChunk(keys, scopeId, aHash, index = 2, total = 5, chunk = data))
        assertEquals(ScopeCrypto.ATTACH_SEAL_VERSION, blob[0])
        val opened = ScopeCrypto.openChunk(keys, scopeId, blob)
        assertArrayEquals(aHash, opened.aHash)
        assertEquals(2, opened.index)
        assertEquals(5, opened.total)
        assertArrayEquals(data, opened.data)
    }

    @Test
    fun chunkNonceMatchesTheReferenceDerivation() {
        val keys = ScopeCrypto.dmSealKeys(deterministicBytes(1), NODE_A, NODE_B)
        val scopeId = ScopeCrypto.dmScopeId(deterministicBytes(1), NODE_A, NODE_B)
        val aHash = deterministicBytes(7)
        val data = deterministicBytes(9, 300)

        val blob = ScopeCrypto.sealChunk(keys, scopeId, aHash, index = 2, total = 5, chunk = data)

        val pt = aHash + byteArrayOf(0, 0, 0, 2) + byteArrayOf(0, 0, 0, 5) + data
        val ptHash = MessageDigest.getInstance("SHA-256").digest(pt)
        val nonce = referenceHkdf(keys.nonceKey, ByteArray(32), "knit/scope/v1/anonce".toByteArray() + ptHash, 12)
        assertArrayEquals(nonce, blob.copyOfRange(1, 1 + ScopeCrypto.NONCE_BYTES))
    }

    @Test
    fun chunkSealBindsPositionAttachmentAndScope() {
        val keys = ScopeCrypto.dmSealKeys(deterministicBytes(1), NODE_A, NODE_B)
        val scopeId = ScopeCrypto.dmScopeId(deterministicBytes(1), NODE_A, NODE_B)
        val aHash = deterministicBytes(7)
        val data = deterministicBytes(9, 300)
        val blob = ScopeCrypto.sealChunk(keys, scopeId, aHash, index = 2, total = 5, chunk = data)

        // Position and attachment are inside the seal, so none of these can be forged by relabelling.
        assertFalse(blob.contentEquals(ScopeCrypto.sealChunk(keys, scopeId, aHash, 3, 5, data)))
        assertFalse(blob.contentEquals(ScopeCrypto.sealChunk(keys, scopeId, aHash, 2, 6, data)))
        assertFalse(blob.contentEquals(ScopeCrypto.sealChunk(keys, scopeId, deterministicBytes(8), 2, 5, data)))

        val otherScope = ScopeCrypto.dmScopeId(deterministicBytes(2), NODE_A, NODE_B)
        assertThrows(GeneralSecurityException::class.java) { ScopeCrypto.openChunk(keys, otherScope, blob) }

        val tampered = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        assertThrows(GeneralSecurityException::class.java) { ScopeCrypto.openChunk(keys, scopeId, tampered) }
    }

    @Test
    fun frameAndChunkSealsCannotBeFedToEachOther() {
        val keys = ScopeCrypto.dmSealKeys(deterministicBytes(1), NODE_A, NODE_B)
        val scopeId = ScopeCrypto.dmScopeId(deterministicBytes(1), NODE_A, NODE_B)
        val frame = ScopeCrypto.seal(keys, scopeId, deterministicBytes(4, 64), deterministicBytes(5, 40))
        val chunk = ScopeCrypto.sealChunk(keys, scopeId, deterministicBytes(7), 0, 1, deterministicBytes(9, 300))

        // First line of defence: the scheme version byte.
        assertThrows(IllegalArgumentException::class.java) { ScopeCrypto.open(keys, scopeId, chunk) }
        assertThrows(IllegalArgumentException::class.java) { ScopeCrypto.openChunk(keys, scopeId, frame) }

        // Second, independent line: the aad prefixes differ, so relabelling the version still fails.
        val relabelled = frame.copyOf().also { it[0] = ScopeCrypto.ATTACH_SEAL_VERSION }
        assertThrows(GeneralSecurityException::class.java) { ScopeCrypto.openChunk(keys, scopeId, relabelled) }
    }

    @Test
    fun sealChunkRejectsOutOfRangeHeadersAndSizes() {
        val keys = ScopeCrypto.dmSealKeys(deterministicBytes(1), NODE_A, NODE_B)
        val scopeId = ScopeCrypto.dmScopeId(deterministicBytes(1), NODE_A, NODE_B)
        val aHash = deterministicBytes(7)
        val data = deterministicBytes(9, 300)

        assertThrows(IllegalArgumentException::class.java) { ScopeCrypto.sealChunk(keys, scopeId, aHash, 5, 5, data) }
        assertThrows(IllegalArgumentException::class.java) { ScopeCrypto.sealChunk(keys, scopeId, aHash, -1, 5, data) }
        assertThrows(IllegalArgumentException::class.java) { ScopeCrypto.sealChunk(keys, scopeId, aHash, 0, 0, data) }
        assertThrows(IllegalArgumentException::class.java) { ScopeCrypto.sealChunk(keys, scopeId, aHash, 0, 1, ByteArray(0)) }
        assertThrows(IllegalArgumentException::class.java) {
            ScopeCrypto.sealChunk(keys, scopeId, aHash, 0, 1, ByteArray(ScopeCrypto.ATTACH_CHUNK_BYTES + 1))
        }
        assertThrows(IllegalArgumentException::class.java) { ScopeCrypto.attachmentId(keys, scopeId, ByteArray(16)) }
    }

    @Test
    fun scopeDigestFoldsOrderIndependentlyAndSelfInverse() {
        val a = deterministicBytes(11)
        val b = deterministicBytes(12)
        val c = deterministicBytes(13)

        assertEquals(0L, ScopeCrypto.scopeDigest(emptyList()))
        assertEquals(ScopeCrypto.scopeDigest(listOf(a, b, c)), ScopeCrypto.scopeDigest(listOf(c, a, b)))
        assertEquals(0L, ScopeCrypto.scopeDigest(listOf(a, a)))
        assertEquals(
            ScopeCrypto.fnv64(b),
            ScopeCrypto.scopeDigest(listOf(a, b)) xor ScopeCrypto.fnv64(a),
        )
        assertEquals(ScopeCrypto.fnv64(a) xor ScopeCrypto.fnv64(b) xor ScopeCrypto.fnv64(c), ScopeCrypto.scopeDigest(listOf(a, b, c)))
    }

    @Test
    fun digestBytesRoundTripsBigEndian() {
        assertArrayEquals(ByteArray(8), ScopeCrypto.digestBytes(0L))
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0, 0, 0, 1, 2),
            ScopeCrypto.digestBytes(0x0102L),
        )
        for (value in listOf(0L, 1L, -1L, Long.MIN_VALUE, Long.MAX_VALUE, 0x0123456789ABCDEFL)) {
            assertEquals(value, ScopeCrypto.digestValue(ScopeCrypto.digestBytes(value)))
        }
    }

    /**
     * Everything a relay hands back is untrusted: a blob of the wrong shape is refused as an
     * [IllegalArgumentException] before a cipher is built, never read past its end, and never confused
     * with an AEAD failure. The same holds for the local inputs a caller could get wrong.
     */
    @Test
    fun malformedInputsAreRefusedBeforeAnyCipherRuns() {
        val keys = ScopeCrypto.dmSealKeys(deterministicBytes(1), NODE_A, NODE_B)
        val scopeId = ScopeCrypto.dmScopeId(deterministicBytes(1), NODE_A, NODE_B)
        val refused = { block: () -> Unit -> assertThrows(IllegalArgumentException::class.java) { block() } }

        // A commons secret is exactly 32 bytes, for its scope id and its seal keys alike.
        for (size in listOf(0, 31, 33)) {
            refused { ScopeCrypto.commonsScopeId(ByteArray(size)) }
            refused { ScopeCrypto.commonsSealKeys(ByteArray(size)) }
        }
        // A frame seal takes a raw 64-byte signature over a non-empty body.
        refused { ScopeCrypto.seal(keys, scopeId, ByteArray(63), deterministicBytes(5, 40)) }
        refused { ScopeCrypto.seal(keys, scopeId, deterministicBytes(4, 64), ByteArray(0)) }
        // Opening: empty, short of a nonce and signature, or a chunk's version byte.
        refused { ScopeCrypto.open(keys, scopeId, ByteArray(0)) }
        refused { ScopeCrypto.open(keys, scopeId, byteArrayOf(ScopeCrypto.SEAL_VERSION) + ByteArray(ScopeCrypto.NONCE_BYTES + 10)) }
        // A chunk seal is keyed to a 32-byte attachment hash.
        refused { ScopeCrypto.sealChunk(keys, scopeId, ByteArray(16), 0, 1, deterministicBytes(9, 300)) }
        // Opening a chunk: empty, or too short to hold its header.
        refused { ScopeCrypto.openChunk(keys, scopeId, ByteArray(0)) }
        refused {
            ScopeCrypto.openChunk(
                keys,
                scopeId,
                byteArrayOf(ScopeCrypto.ATTACH_SEAL_VERSION) + ByteArray(ScopeCrypto.NONCE_BYTES + ScopeCrypto.ATTACH_HEADER_BYTES),
            )
        }
        // A scope digest is exactly eight bytes.
        refused { ScopeCrypto.digestValue(ByteArray(7)) }
        refused { ScopeCrypto.digestValue(ByteArray(9)) }
    }

    private companion object {
        // Valid-shape fixtures: 26-char base32 node ids, `g-` + 24-hex group id.
        const val NODE_A = "aaaaabbbbbcccccdddddeeeeef"
        const val NODE_B = "zzzzzyyyyyxxxxxwwwwwvvvvvu"
        const val GROUP_ID = "g-00112233445566778899aabb"

        fun deterministicBytes(
            seed: Int,
            n: Int = 32,
        ): ByteArray = ByteArray(n) { ((it * 31 + seed * 131) and 0xFF).toByte() }

        /** Independent RFC 5869 HKDF-SHA256 (extract then expand) on bare javax.crypto — no Tink. */
        fun referenceHkdf(
            ikm: ByteArray,
            salt: ByteArray,
            info: ByteArray,
            length: Int,
        ): ByteArray {
            val prk = hmac(salt, ikm)
            val out = ByteArray(length)
            var previous = ByteArray(0)
            var filled = 0
            var counter = 1
            while (filled < length) {
                previous = hmac(prk, previous + info + byteArrayOf(counter.toByte()))
                val take = minOf(previous.size, length - filled)
                System.arraycopy(previous, 0, out, filled, take)
                filled += take
                counter++
            }
            return out
        }

        fun hmac(
            key: ByteArray,
            data: ByteArray,
        ): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal(data)
            }
    }
}
